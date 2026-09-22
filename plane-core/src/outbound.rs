//! HTTP/3/QUIC outbound client compatible with `proxy-transport-http3`.
//!
//! The Java server expects one HTTP/3 bidirectional request stream per proxied
//! TCP connection. The stream starts with POST `/proxy` and the
//! `x-plane-protocol: proxy-message-v1` header. Its body is the existing
//! length-prefixed, ChaCha20-encrypted `ProxyMessage` byte stream.

use std::net::{SocketAddr, UdpSocket};
use std::os::unix::io::AsRawFd;
use std::sync::Arc;
use std::time::Duration;

use bytes::{Buf, Bytes};
use futures_util::future;
use h3_quinn::quinn;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::crypto::Cipher;
use crate::error::{CoreError, Result};
use crate::proxy_proto::{self, MessageType, ProxyMessage};

/// Socket protection callback corresponding to Android `VpnService.protect`.
pub trait SocketProtector: Send + Sync {
    /// Protect a socket before its first packet is sent, avoiding TUN loops.
    fn protect(&self, fd: i32) -> bool;
}

#[derive(Debug, Clone, Copy, Default)]
pub struct NoopProtector;

impl SocketProtector for NoopProtector {
    fn protect(&self, _fd: i32) -> bool {
        true
    }
}

/// Outbound node settings. HTTP/3 always uses TLS 1.3 over QUIC.
#[derive(Debug, Clone)]
pub struct OutboundConfig {
    pub server_host: String,
    pub server_port: u16,
    /// SNI / certificate name. Empty means `server_host`.
    pub server_name: String,
    /// Optional PEM CA supplied by the Android app for a private HTTP/3 CA.
    pub ca_pem: String,
    /// Kept for config compatibility; HTTP/3 is always encrypted.
    pub tls: bool,
}

/// Verifies the exact certificate bundled for the legacy HTTP/3 deployment.
///
/// That deployment incorrectly used its self-signed CA certificate as the server
/// certificate, so WebPKI correctly rejects it as `CaUsedAsEndEntity`. Keep this
/// compatibility path narrowly pinned to the configured DER certificate until the
/// server is rotated to a normal CA-signed leaf certificate.
#[derive(Debug)]
struct PinnedServerCertVerifier {
    legacy_ca_certificates: Vec<Vec<u8>>,
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl PinnedServerCertVerifier {
    fn new(legacy_ca_certificates: Vec<Vec<u8>>) -> Self {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        Self {
            legacy_ca_certificates,
            provider,
        }
    }
}

impl rustls::client::danger::ServerCertVerifier for PinnedServerCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> std::result::Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        let exact_pin = self
            .legacy_ca_certificates
            .iter()
            .any(|certificate| certificate.as_slice() == end_entity.as_ref());
        if exact_pin {
            tracing::warn!(
                "HTTP/3 server uses a legacy pinned CA certificate as its end-entity; replace the server certificate with a CA-signed leaf certificate"
            );
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "HTTP/3 server certificate does not match the pinned certificate".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> std::result::Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> std::result::Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

pub const CIPHER_LENGTH_PREFIX: usize = 4;
const QUIC_STREAM_RECEIVE_WINDOW: u32 = 4 * 1024 * 1024;
const QUIC_SEND_WINDOW: u32 = 16 * 1024 * 1024;
static NEXT_PROXY_STREAM_ID: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);

pub fn encode_encrypted_frame(cipher: &Cipher, msg: &ProxyMessage) -> Result<Vec<u8>> {
    let plaintext = msg.encode();
    let ciphertext = cipher.encrypt(&plaintext)?;
    let mut framed = Vec::with_capacity(CIPHER_LENGTH_PREFIX + ciphertext.len());
    framed.extend_from_slice(&(ciphertext.len() as u32).to_be_bytes());
    framed.extend_from_slice(&ciphertext);
    Ok(framed)
}

/// Reassembles HTTP/3 DATA payloads into encrypted frames and ProxyMessages.
pub struct InboundReassembler {
    cipher: Cipher,
    cipher_buffer: Vec<u8>,
    plain_buffer: Vec<u8>,
}

impl InboundReassembler {
    pub fn new(cipher: Cipher) -> Self {
        Self {
            cipher,
            cipher_buffer: Vec::new(),
            plain_buffer: Vec::new(),
        }
    }

    pub fn push_encrypted_frame(&mut self, frame: &[u8]) -> Result<Vec<ProxyMessage>> {
        self.cipher_buffer.extend_from_slice(frame);
        let mut offset = 0usize;
        while self.cipher_buffer.len() - offset >= CIPHER_LENGTH_PREFIX {
            let len_bytes = &self.cipher_buffer[offset..offset + CIPHER_LENGTH_PREFIX];
            let ct_len = u32::from_be_bytes(len_bytes.try_into().expect("four bytes")) as usize;
            if self.cipher_buffer.len() - offset < CIPHER_LENGTH_PREFIX + ct_len {
                break;
            }
            let start = offset + CIPHER_LENGTH_PREFIX;
            let end = start + ct_len;
            self.plain_buffer
                .extend_from_slice(&self.cipher.decrypt(&self.cipher_buffer[start..end])?);
            offset = end;
        }
        if offset > 0 {
            self.cipher_buffer.drain(..offset);
        }
        self.drain_messages()
    }

    fn drain_messages(&mut self) -> Result<Vec<ProxyMessage>> {
        let mut messages = Vec::new();
        while let Some((message, consumed)) = proxy_proto::try_decode_one(&self.plain_buffer)? {
            messages.push(message);
            self.plain_buffer.drain(..consumed);
        }
        Ok(messages)
    }

    pub fn buffered_len(&self) -> usize {
        self.plain_buffer.len()
    }

    pub fn cipher_buffered_len(&self) -> usize {
        self.cipher_buffer.len()
    }
}

#[derive(Debug, Default)]
pub struct RequestIdGen {
    next: std::sync::atomic::AtomicI64,
}

impl RequestIdGen {
    pub fn new() -> Self {
        Self {
            next: std::sync::atomic::AtomicI64::new(1),
        }
    }

    pub fn next_id(&self) -> i64 {
        self.next.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    }
}

type H3RequestStream = h3::client::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>;

/// One QUIC connection carrying many HTTP/3 request streams.
pub struct OutboundConnection {
    endpoint: quinn::Endpoint,
    send_request: h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
    cipher: Cipher,
    req_id_gen: Arc<RequestIdGen>,
    config: OutboundConfig,
}

impl OutboundConnection {
    /// Create a protected UDP socket, establish QUIC/TLS, then start HTTP/3.
    pub async fn connect<P>(
        addr: SocketAddr,
        protector: &P,
        cipher: Cipher,
        config: OutboundConfig,
    ) -> Result<Self>
    where
        P: SocketProtector,
    {
        let bind_addr = if addr.is_ipv4() {
            "0.0.0.0:0"
        } else {
            "[::]:0"
        };
        let socket = UdpSocket::bind(bind_addr).map_err(CoreError::Io)?;
        socket.set_nonblocking(true).map_err(CoreError::Io)?;
        let fd = socket.as_raw_fd();
        if !protector.protect(fd) {
            return Err(CoreError::Internal(format!(
                "protect QUIC UDP socket fd={fd} failed"
            )));
        }

        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let mut pinned_legacy_certificates = Vec::new();
        if !config.ca_pem.trim().is_empty() {
            let mut pem = config.ca_pem.as_bytes();
            for certificate in rustls_pemfile::certs(&mut pem) {
                let certificate = certificate.map_err(|e| {
                    CoreError::Protocol(format!("HTTP/3 CA certificate parse failed: {e}"))
                })?;
                pinned_legacy_certificates.push(certificate.as_ref().to_vec());
            }
        }
        let mut tls_config = if pinned_legacy_certificates.is_empty() {
            rustls::ClientConfig::builder()
                .with_root_certificates(roots)
                .with_no_client_auth()
        } else {
            let verifier = Arc::new(PinnedServerCertVerifier::new(pinned_legacy_certificates));
            rustls::ClientConfig::builder()
                .dangerous()
                .with_custom_certificate_verifier(verifier)
                .with_no_client_auth()
        };
        tls_config.alpn_protocols = vec![b"h3".to_vec()];
        let quic_crypto = quinn::crypto::rustls::QuicClientConfig::try_from(tls_config)
            .map_err(|e| CoreError::Protocol(format!("HTTP/3 TLS config failed: {e}")))?;
        let mut client_config = quinn::ClientConfig::new(Arc::new(quic_crypto));
        let mut transport = quinn::TransportConfig::default();
        transport
            .stream_receive_window(quinn::VarInt::from_u32(QUIC_STREAM_RECEIVE_WINDOW))
            .send_window(QUIC_SEND_WINDOW as u64);
        transport.keep_alive_interval(Some(Duration::from_secs(15)));
        client_config.transport_config(Arc::new(transport));

        let mut endpoint = quinn::Endpoint::new(
            quinn::EndpointConfig::default(),
            None,
            socket,
            Arc::new(quinn::TokioRuntime),
        )
        .map_err(CoreError::Io)?;
        endpoint.set_default_client_config(client_config);
        let server_name = if config.server_name.trim().is_empty() {
            config.server_host.as_str()
        } else {
            config.server_name.as_str()
        };
        let connection = endpoint
            .connect(addr, server_name)
            .map_err(|e| CoreError::Protocol(format!("HTTP/3 QUIC connect failed: {e}")))?
            .await
            .map_err(|e| CoreError::Protocol(format!("HTTP/3 QUIC handshake failed: {e}")))?;

        let quinn_connection = h3_quinn::Connection::new(connection);
        let (mut driver, send_request) = h3::client::new(quinn_connection)
            .await
            .map_err(|e| CoreError::Protocol(format!("HTTP/3 session setup failed: {e:?}")))?;
        tokio::spawn(async move {
            let result = future::poll_fn(|cx| driver.poll_close(cx)).await;
            if !result.is_h3_no_error() {
                tracing::warn!("HTTP/3 connection driver ended: {result:?}");
            }
        });

        Ok(Self {
            endpoint,
            send_request,
            cipher,
            req_id_gen: Arc::new(RequestIdGen::new()),
            config,
        })
    }

    pub async fn open_proxy_stream(&mut self, host: &str, port: u16) -> Result<OutboundStream> {
        let authority = format_authority(&self.config.server_host, self.config.server_port);
        let uri = format!("https://{authority}/proxy");
        let request = http::Request::builder()
            .method(http::Method::POST)
            .uri(uri)
            .header("content-type", "application/octet-stream")
            .header("x-plane-protocol", "proxy-message-v1")
            .body(())
            .map_err(|e| CoreError::Protocol(format!("build HTTP/3 request failed: {e}")))?;
        let mut stream = self
            .send_request
            .clone()
            .send_request(request)
            .await
            .map_err(|e| CoreError::Protocol(format!("open HTTP/3 stream failed: {e:?}")))?;

        let request_id = self.req_id_gen.next_id();
        // The Java remote keeps outbound sessions in a process-wide map keyed
        // by stream_id.  Using zero for every HTTP/3 request causes concurrent
        // browser connections to replace one another.  A process-wide positive
        // id remains unique across reconnects as well as within one QUIC
        // connection.
        let stream_id = NEXT_PROXY_STREAM_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let connect_msg = ProxyMessage::connect_on_stream(request_id, stream_id, host, port);
        let frame = encode_encrypted_frame(&self.cipher, &connect_msg)?;
        stream
            .send_data(Bytes::from(frame))
            .await
            .map_err(|e| CoreError::Protocol(format!("send HTTP/3 CONNECT failed: {e:?}")))?;

        let response = stream
            .recv_response()
            .await
            .map_err(|e| CoreError::Protocol(format!("receive HTTP/3 response failed: {e:?}")))?;
        let protocol = response
            .headers()
            .get("x-plane-protocol")
            .and_then(|v| v.to_str().ok());
        if response.status() != http::StatusCode::OK || protocol != Some("proxy-message-v1") {
            return Err(CoreError::Protocol(format!(
                "HTTP/3 proxy rejected stream: status={}, protocol={protocol:?}",
                response.status()
            )));
        }

        Ok(OutboundStream {
            request_id,
            stream_id,
            target_host: host.to_string(),
            target_port: port,
            cipher: self.cipher.clone(),
            stream,
            reassembler: InboundReassembler::new(self.cipher.clone()),
        })
    }

    pub fn config(&self) -> &OutboundConfig {
        &self.config
    }

    pub fn is_alive(&self) -> bool {
        self.endpoint.open_connections() > 0
    }
}

fn format_authority(host: &str, port: u16) -> String {
    if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    }
}

pub struct OutboundStream {
    request_id: i64,
    stream_id: i64,
    target_host: String,
    target_port: u16,
    cipher: Cipher,
    stream: H3RequestStream,
    reassembler: InboundReassembler,
}

impl OutboundStream {
    pub fn request_id(&self) -> i64 {
        self.request_id
    }

    pub async fn send_payload(&mut self, payload: &[u8]) -> Result<()> {
        let frame = encode_encrypted_frame(
            &self.cipher,
            &ProxyMessage::data_on_stream(self.request_id, self.stream_id, payload),
        )?;
        self.stream
            .send_data(Bytes::from(frame))
            .await
            .map_err(|e| CoreError::Protocol(format!("send HTTP/3 DATA failed: {e:?}")))
    }

    pub async fn send_disconnect(&mut self) -> Result<()> {
        let frame = encode_encrypted_frame(
            &self.cipher,
            &ProxyMessage::disconnect_on_stream(self.request_id, self.stream_id),
        )?;
        self.stream
            .send_data(Bytes::from(frame))
            .await
            .map_err(|e| CoreError::Protocol(format!("send HTTP/3 DISCONNECT failed: {e:?}")))?;
        self.stream
            .finish()
            .await
            .map_err(|e| CoreError::Protocol(format!("finish HTTP/3 stream failed: {e:?}")))
    }

    pub async fn recv_messages(&mut self) -> Result<Option<Vec<ProxyMessage>>> {
        match self.stream.recv_data().await {
            Ok(Some(mut chunk)) => {
                let mut frame = vec![0u8; chunk.remaining()];
                chunk.copy_to_slice(&mut frame);
                let messages = self.reassembler.push_encrypted_frame(&frame)?;
                for message in &messages {
                    if message.type_ == MessageType::ConnectResponse
                        && message.request_id == self.request_id
                        && message.status != 0
                        && message.status != 200
                    {
                        let detail = if message.data.is_empty() {
                            String::new()
                        } else {
                            format!(": {}", String::from_utf8_lossy(&message.data))
                        };
                        return Err(CoreError::Protocol(format!(
                            "remote CONNECT rejected {}:{} stream {} with status {}{}",
                            self.target_host,
                            self.target_port,
                            self.stream_id,
                            message.status,
                            detail
                        )));
                    }
                }
                Ok(Some(messages))
            }
            Ok(None) => Ok(None),
            Err(e) => Err(CoreError::Protocol(format!(
                "receive HTTP/3 DATA failed: {e:?}"
            ))),
        }
    }
}

pub async fn proxy_via_remote<L>(
    mut stream: OutboundStream,
    mut local: L,
    read_buf_size: usize,
) -> Result<()>
where
    L: AsyncRead + AsyncWrite + Unpin,
{
    let mut buf = vec![0u8; read_buf_size.max(1)];
    loop {
        tokio::select! {
            read = local.read(&mut buf) => {
                let n = read.map_err(CoreError::Io)?;
                if n == 0 {
                    stream.send_disconnect().await?;
                    break;
                }
                stream.send_payload(&buf[..n]).await?;
            }
            recv = stream.recv_messages() => {
                match recv? {
                    Some(messages) => for message in messages {
                        match message.type_ {
                            MessageType::Data => local.write_all(&message.data).await.map_err(CoreError::Io)?,
                            MessageType::Disconnect => return Ok(()),
                            _ => {}
                        }
                    },
                    None => break,
                }
            }
        }
    }
    Ok(())
}

pub fn spawn_proxy<L>(stream: OutboundStream, local: L, read_buf_size: usize)
where
    L: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    tokio::spawn(async move {
        if let Err(error) = proxy_via_remote(stream, local, read_buf_size).await {
            tracing::warn!("proxy_via_remote ended with error: {error}");
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_cipher() -> Cipher {
        Cipher::new(b"unit-test-key-please-change-1234").unwrap()
    }

    #[test]
    fn encrypted_frame_roundtrip() {
        let cipher = test_cipher();
        let msg = ProxyMessage::connect(42, "example.com", 443);
        let frame = encode_encrypted_frame(&cipher, &msg).unwrap();
        let mut reassembler = InboundReassembler::new(cipher);
        assert_eq!(reassembler.push_encrypted_frame(&frame).unwrap(), vec![msg]);
    }

    #[test]
    fn reassembler_handles_split_quic_data_chunks() {
        let cipher = test_cipher();
        let frame = encode_encrypted_frame(&cipher, &ProxyMessage::data(1, b"payload")).unwrap();
        let split = frame.len() / 2;
        let mut reassembler = InboundReassembler::new(cipher);
        assert!(reassembler
            .push_encrypted_frame(&frame[..split])
            .unwrap()
            .is_empty());
        assert_eq!(
            reassembler.push_encrypted_frame(&frame[split..]).unwrap(),
            vec![ProxyMessage::data(1, b"payload")]
        );
    }

    #[test]
    fn request_id_gen_is_monotonic() {
        let generator = RequestIdGen::new();
        assert_eq!(generator.next_id(), 1);
        assert_eq!(generator.next_id(), 2);
        assert_eq!(generator.next_id(), 3);
    }

    #[test]
    fn ipv6_authority_is_bracketed() {
        assert_eq!(format_authority("::1", 8443), "[::1]:8443");
        assert_eq!(format_authority("example.com", 8443), "example.com:8443");
    }
}
