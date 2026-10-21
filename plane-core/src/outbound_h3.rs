//! HTTP/3/QUIC outbound client compatible with `proxy-transport-http3`.
//!
//! The Java server expects one HTTP/3 bidirectional request stream per proxied
//! TCP connection. The stream starts with POST `/proxy` and the
//! `x-plane-protocol: proxy-message-v1` header. Its body is the existing
//! length-prefixed, ChaCha20-encrypted `ProxyMessage` byte stream.

use std::net::{SocketAddr, UdpSocket};
use std::os::unix::io::AsRawFd;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use std::time::Duration;

use bytes::{Buf, Bytes};
use futures_util::future;
use h3_quinn::quinn;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::watch;

use crate::crypto::Cipher;
use crate::error::{CoreError, Result};
use crate::outbound::{
    encode_encrypted_frame, next_stream_id, InboundReassembler, RequestIdGen, SocketProtector,
};
use crate::proxy_proto::{MessageType, ProxyMessage};
use crate::stats::CoreStats;

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
    standard: Arc<rustls::client::WebPkiServerVerifier>,
}

impl PinnedServerCertVerifier {
    fn new(legacy_ca_certificates: Vec<Vec<u8>>, roots: rustls::RootCertStore) -> Result<Self> {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let standard = rustls::client::WebPkiServerVerifier::builder(Arc::new(roots))
            .build()
            .map_err(|e| CoreError::Protocol(format!("HTTP/3 trust store failed: {e}")))?;
        Ok(Self {
            legacy_ca_certificates,
            provider,
            standard,
        })
    }
}

impl rustls::client::danger::ServerCertVerifier for PinnedServerCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &rustls::pki_types::CertificateDer<'_>,
        intermediates: &[rustls::pki_types::CertificateDer<'_>],
        server_name: &rustls::pki_types::ServerName<'_>,
        ocsp_response: &[u8],
        now: rustls::pki_types::UnixTime,
    ) -> std::result::Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        let standard = self.standard.verify_server_cert(
            end_entity,
            intermediates,
            server_name,
            ocsp_response,
            now,
        );
        if standard.is_ok() {
            return standard;
        }
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
            standard
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

const QUIC_STREAM_RECEIVE_WINDOW: u32 = 4 * 1024 * 1024;
const QUIC_SEND_WINDOW: u32 = 16 * 1024 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

type H3RequestStream = h3::client::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>;

/// One QUIC connection carrying many HTTP/3 request streams.
#[derive(Clone)]
pub struct OutboundConnection {
    endpoint: quinn::Endpoint,
    send_request: h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
    cipher: Cipher,
    req_id_gen: Arc<RequestIdGen>,
    config: OutboundConfig,
    health: Arc<ConnectionHealth>,
}

/// Shared lifecycle state for one HTTP/3 connection.
///
/// Every proxy stream subscribes to this state. When the HTTP/3 driver exits,
/// all streams are woken up immediately instead of waiting for their individual
/// DATA reads to time out.
struct ConnectionHealth {
    alive: AtomicBool,
    changed: watch::Sender<bool>,
}

impl ConnectionHealth {
    fn new() -> Arc<Self> {
        let (changed, _receiver) = watch::channel(true);
        Arc::new(Self {
            alive: AtomicBool::new(true),
            changed,
        })
    }

    fn subscribe(&self) -> watch::Receiver<bool> {
        self.changed.subscribe()
    }

    fn is_alive(&self) -> bool {
        self.alive.load(Ordering::Acquire)
    }

    fn mark_dead(&self) {
        if self.alive.swap(false, Ordering::Release) {
            self.changed.send_replace(false);
        }
    }
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
                roots.add(certificate).map_err(|e| {
                    CoreError::Protocol(format!("HTTP/3 invalid CA certificate: {e}"))
                })?;
            }
            if pinned_legacy_certificates.is_empty() {
                return Err(CoreError::Protocol(
                    "HTTP/3 CA PEM contains no certificates".into(),
                ));
            }
        }
        let mut tls_config = if pinned_legacy_certificates.is_empty() {
            rustls::ClientConfig::builder()
                .with_root_certificates(roots)
                .with_no_client_auth()
        } else {
            let verifier = Arc::new(PinnedServerCertVerifier::new(
                pinned_legacy_certificates,
                roots,
            )?);
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
        let connecting = endpoint
            .connect(addr, server_name)
            .map_err(|e| CoreError::Protocol(format!("HTTP/3 QUIC connect failed: {e}")))?;
        let connection = tokio::time::timeout(CONNECT_TIMEOUT, connecting)
            .await
            .map_err(|_| {
                CoreError::Protocol(format!(
                    "HTTP/3 QUIC handshake with {addr} timed out after 15s"
                ))
            })?
            .map_err(|e| CoreError::Protocol(format!("HTTP/3 QUIC handshake failed: {e}")))?;

        let health = ConnectionHealth::new();

        let quinn_connection = h3_quinn::Connection::new(connection);
        let (mut driver, send_request) = h3::client::new(quinn_connection)
            .await
            .map_err(|e| CoreError::Protocol(format!("HTTP/3 session setup failed: {e:?}")))?;
        let driver_health = Arc::clone(&health);
        tokio::spawn(async move {
            let result = future::poll_fn(|cx| driver.poll_close(cx)).await;
            driver_health.mark_dead();
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
            health,
        })
    }

    pub async fn open_proxy_stream(&mut self, host: &str, port: u16) -> Result<OutboundStream> {
        tokio::time::timeout(CONNECT_TIMEOUT, self.open_proxy_stream_inner(host, port))
            .await
            .map_err(|_| {
                CoreError::Protocol(format!(
                    "HTTP/3 CONNECT {host}:{port} timed out after 15s; check node protocol and key"
                ))
            })?
    }

    async fn open_proxy_stream_inner(&mut self, host: &str, port: u16) -> Result<OutboundStream> {
        let cipher = self.cipher.fresh_session()?;
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

        // Netty's HTTP/3 transport sends the response headers as soon as the
        // request headers are accepted.  Wait for those headers before writing
        // the first proxy frame, matching the Java client and avoiding an
        // early request-body/response-header race in the native QUIC stack.
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

        let request_id = self.req_id_gen.next_id();
        // The Java remote keeps outbound sessions in a process-wide map keyed
        // by stream_id. Using a small sequential ID after every VPN restart
        // lets delayed frames from the previous process collide with a new
        // session. The process-unique positive namespace also remains stable
        // across QUIC reconnects within this process.
        let stream_id = next_stream_id();
        let connect_msg = ProxyMessage::connect(request_id, stream_id, host, port);
        let frame = encode_encrypted_frame(&cipher, &connect_msg)?;
        stream
            .send_data(Bytes::from(frame))
            .await
            .map_err(|e| CoreError::Protocol(format!("send HTTP/3 CONNECT failed: {e:?}")))?;

        let mut stream = OutboundStream {
            request_id,
            stream_id,
            target_host: host.to_string(),
            target_port: port,
            cipher: cipher.clone(),
            stream,
            reassembler: InboundReassembler::new(cipher),
            health: Arc::clone(&self.health),
            pending_messages: Vec::new(),
            // Dropping the last H3 SendRequest actively closes the connection,
            // even with request streams still alive. Retain both owners until
            // this flow ends, including when the dispatcher replaces its cache.
            _sender: self.send_request.clone(),
            _endpoint: self.endpoint.clone(),
        };
        // HTTP headers do not prove the encrypted CONNECT or target succeeded.
        // Java replies with stream_id=0, so correlate using request_id instead.
        loop {
            let messages = stream.recv_wire_messages().await?.ok_or_else(|| {
                CoreError::Protocol("HTTP/3 closed before CONNECT_RESPONSE".into())
            })?;
            let mut connected = false;
            for message in messages {
                if message.type_ == MessageType::ConnectResponse {
                    if message.request_id != request_id {
                        return Err(CoreError::Protocol(format!(
                            "HTTP/3 CONNECT {host}:{port} rejected: status={}, request_id={}",
                            message.status, message.request_id
                        )));
                    }
                    if message.status != 200 {
                        return Err(CoreError::TargetRejected(format!(
                            "{host}:{port}, status={}",
                            message.status
                        )));
                    }
                    connected = true;
                } else {
                    stream.pending_messages.push(message);
                }
            }
            if connected {
                return Ok(stream);
            }
        }
    }

    pub fn config(&self) -> &OutboundConfig {
        &self.config
    }

    pub fn is_alive(&self) -> bool {
        self.health.is_alive() && self.endpoint.open_connections() > 0
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
    _sender: h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>,
    _endpoint: quinn::Endpoint,
    request_id: i64,
    stream_id: i64,
    target_host: String,
    target_port: u16,
    cipher: Cipher,
    stream: H3RequestStream,
    reassembler: InboundReassembler,
    health: Arc<ConnectionHealth>,
    pending_messages: Vec<ProxyMessage>,
}

impl OutboundStream {
    pub fn request_id(&self) -> i64 {
        self.request_id
    }

    pub async fn send_payload(&mut self, payload: &[u8]) -> Result<()> {
        let frame =
            encode_encrypted_frame(&self.cipher, &ProxyMessage::data(self.stream_id, payload))?;
        self.stream
            .send_data(Bytes::from(frame))
            .await
            .map_err(|e| CoreError::Protocol(format!("send HTTP/3 DATA failed: {e:?}")))
    }

    pub async fn send_disconnect(&mut self) -> Result<()> {
        let frame = encode_encrypted_frame(
            &self.cipher,
            &ProxyMessage::disconnect(self.request_id, self.stream_id),
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
        if !self.pending_messages.is_empty() {
            return Ok(Some(std::mem::take(&mut self.pending_messages)));
        }
        self.recv_wire_messages().await
    }

    async fn recv_wire_messages(&mut self) -> Result<Option<Vec<ProxyMessage>>> {
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
                        return Err(CoreError::TargetRejected(format!(
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
    stream: OutboundStream,
    local: L,
    read_buf_size: usize,
) -> Result<()>
where
    L: AsyncRead + AsyncWrite + Unpin,
{
    proxy_via_remote_counted(stream, local, read_buf_size, None).await
}

pub async fn proxy_via_remote_counted<L>(
    mut stream: OutboundStream,
    mut local: L,
    read_buf_size: usize,
    stats: Option<Arc<CoreStats>>,
) -> Result<()>
where
    L: AsyncRead + AsyncWrite + Unpin,
{
    let mut buf = vec![0u8; read_buf_size.max(1)];
    let mut health_receiver = stream.health.subscribe();
    let result = async {
        loop {
            tokio::select! {
                    changed = health_receiver.changed() => {
                    if changed.is_err() || !*health_receiver.borrow() {
                        return Err(CoreError::Protocol("HTTP/3 connection lost".into()));
                    }
                }
                read = local.read(&mut buf) => {
                    let n = read.map_err(CoreError::Io)?;
                    if n == 0 {
                        stream.send_disconnect().await?;
                        break;
                    }
                    stream.send_payload(&buf[..n]).await?;
                    if let Some(stats) = &stats {
                        stats.add_upload(n);
                    }
                }
                recv = stream.recv_messages() => {
                    match recv? {
                        Some(messages) => for message in messages {
                            match message.type_ {
                                MessageType::Data => {
                                    local.write_all(&message.data).await.map_err(CoreError::Io)?;
                                    if let Some(stats) = &stats {
                                        stats.add_download(message.data.len());
                                    }
                                }
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
    .await;

    // Closing the local side is essential: otherwise the TCP stack keeps the
    // old application flow alive after its QUIC stream has died.
    let _ = local.shutdown().await;
    result
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
pub(crate) mod tests {
    use super::*;
    use crate::outbound::NoopProtector;

    pub(crate) async fn mock_proxy(
        http_status: u16,
        ack_status: Option<u8>,
    ) -> (SocketAddr, String, tokio::task::JoinHandle<()>) {
        mock_proxy_with_cipher(http_status, ack_status, "chacha20", b"test-key").await
    }

    async fn mock_proxy_with_cipher(
        http_status: u16,
        ack_status: Option<u8>,
        suite: &'static str,
        secret: &'static [u8],
    ) -> (SocketAddr, String, tokio::task::JoinHandle<()>) {
        let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let ca_pem = certificate.cert.pem();
        let key = rustls::pki_types::PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der());
        let mut tls = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![certificate.cert.der().clone()], key.into())
            .unwrap();
        tls.alpn_protocols = vec![b"h3".to_vec()];
        let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).unwrap();
        let config = quinn::ServerConfig::with_crypto(Arc::new(crypto));
        let endpoint = quinn::Endpoint::server(config, "127.0.0.1:0".parse().unwrap()).unwrap();
        let addr = endpoint.local_addr().unwrap();
        let task = tokio::spawn(async move {
            let incoming = endpoint.accept().await.unwrap();
            let Ok(connection) = incoming.await else {
                return;
            };
            let mut server = h3::server::Connection::new(h3_quinn::Connection::new(connection))
                .await
                .unwrap();
            while let Ok(Some(resolver)) = server.accept().await {
                tokio::spawn(async move {
                    let (_, mut stream) = resolver.resolve_request().await.unwrap();
                    let response = http::Response::builder()
                        .status(http_status)
                        .header("x-plane-protocol", "proxy-message-v1")
                        .body(())
                        .unwrap();
                    stream.send_response(response).await.unwrap();
                    if http_status != 200 {
                        let _ = stream.finish().await;
                        return;
                    }
                    let cipher = Cipher::test_server(suite, secret).unwrap();
                    let mut reassembler = InboundReassembler::new(cipher.clone());
                    while let Ok(Some(mut chunk)) = stream.recv_data().await {
                        let bytes = chunk.copy_to_bytes(chunk.remaining());
                        let Ok(messages) = reassembler.push_encrypted_frame(&bytes) else {
                            return;
                        };
                        for message in messages {
                            let response = match message.type_ {
                                MessageType::Connect => {
                                    if message.host == "reset.test" {
                                        stream.stop_stream(h3::error::Code::H3_REQUEST_REJECTED);
                                        return;
                                    }
                                    if message.host == "slow.test" {
                                        continue;
                                    }
                                    let Some(status) = ack_status else { continue };
                                    let ack = ProxyMessage {
                                        type_: MessageType::ConnectResponse,
                                        status: if message.host == "reject.test" {
                                            244
                                        } else {
                                            status
                                        },
                                        request_id: message.request_id,
                                        stream_id: 0,
                                        host: String::new(),
                                        port: 0,
                                        data: Vec::new(),
                                    };
                                    let mut bytes = encode_encrypted_frame(&cipher, &ack).unwrap();
                                    bytes.extend(
                                        encode_encrypted_frame(
                                            &cipher,
                                            &ProxyMessage::data(message.stream_id, b"greeting"),
                                        )
                                        .unwrap(),
                                    );
                                    bytes
                                }
                                MessageType::Data => {
                                    encode_encrypted_frame(&cipher, &message).unwrap()
                                }
                                MessageType::Disconnect => {
                                    let _ = stream.finish().await;
                                    return;
                                }
                                _ => continue,
                            };
                            // Exercise reassembly across independent QUIC DATA frames.
                            let split = response.len() / 2;
                            if stream
                                .send_data(Bytes::copy_from_slice(&response[..split]))
                                .await
                                .is_err()
                            {
                                return;
                            }
                            if stream
                                .send_data(Bytes::copy_from_slice(&response[split..]))
                                .await
                                .is_err()
                            {
                                return;
                            }
                        }
                    }
                });
            }
        });
        (addr, ca_pem, task)
    }

    async fn connect(addr: SocketAddr, ca_pem: String) -> Result<OutboundConnection> {
        OutboundConnection::connect(
            addr,
            &NoopProtector,
            Cipher::new(b"test-key").unwrap(),
            OutboundConfig {
                server_host: "localhost".into(),
                server_port: addr.port(),
                server_name: "localhost".into(),
                ca_pem,
                tls: true,
            },
        )
        .await
    }

    #[tokio::test]
    async fn http3_v2_sessions_are_independent_per_stream() {
        for suite in ["chacha20-poly1305-v2", "aes-gcm-v2"] {
            let (addr, ca_pem, server) =
                mock_proxy_with_cipher(200, Some(200), suite, &[0x42; 32]).await;
            let mut connection = OutboundConnection::connect(
                addr,
                &NoopProtector,
                Cipher::from_name(suite, &[0x42; 32]).unwrap(),
                OutboundConfig {
                    server_host: "localhost".into(),
                    server_port: addr.port(),
                    server_name: "localhost".into(),
                    ca_pem,
                    tls: true,
                },
            )
            .await
            .unwrap();
            tokio::time::timeout(Duration::from_secs(5), async {
                let mut other = connection.clone();
                let (first, second) = tokio::join!(
                    connection.open_proxy_stream("one.test", 80),
                    other.open_proxy_stream("two.test", 80),
                );
                for mut stream in [first.unwrap(), second.unwrap()] {
                    let mut greeting = Vec::new();
                    while greeting.len() < 8 {
                        for message in stream.recv_messages().await.unwrap().unwrap() {
                            greeting.extend(message.data);
                        }
                    }
                    assert_eq!(greeting, b"greeting");
                    let payload = vec![0x7a; 32769];
                    stream.send_payload(&payload).await.unwrap();
                    let mut echoed = Vec::new();
                    while echoed.len() < payload.len() {
                        for message in stream.recv_messages().await.unwrap().unwrap() {
                            echoed.extend(message.data);
                        }
                    }
                    assert_eq!(echoed, payload);
                    stream.send_disconnect().await.unwrap();
                }
            })
            .await
            .expect("v2 HTTP/3 streams stalled");
            server.abort();
        }
    }

    #[tokio::test]
    async fn http3_active_stream_survives_connection_owner_drop() {
        let (addr, ca, server) = mock_proxy(200, Some(200)).await;
        let mut connection = connect(addr, ca).await.unwrap();
        let mut stream = connection
            .open_proxy_stream("example.com", 80)
            .await
            .unwrap();
        drop(connection);
        tokio::time::timeout(Duration::from_secs(2), async {
            let mut greeting = Vec::new();
            while greeting.len() < 8 {
                for message in stream.recv_messages().await.unwrap().unwrap() {
                    greeting.extend(message.data);
                }
            }
            stream.send_payload(b"still connected").await.unwrap();
            let mut echoed = Vec::new();
            while echoed.len() < 15 {
                for message in stream.recv_messages().await.unwrap().unwrap() {
                    echoed.extend(message.data);
                }
            }
            assert_eq!(echoed, b"still connected");
        })
        .await
        .unwrap();
        server.abort();
    }

    #[tokio::test]
    async fn http3_stream_reset_does_not_mark_the_shared_connection_dead() {
        let (addr, ca, server) = mock_proxy(200, Some(200)).await;
        let mut connection = connect(addr, ca).await.unwrap();
        assert!(connection
            .open_proxy_stream("reset.test", 80)
            .await
            .is_err());
        assert!(
            connection.is_alive(),
            "one rejected stream killed the shared connection"
        );
        let _healthy = connection
            .open_proxy_stream("example.com", 80)
            .await
            .unwrap();
        server.abort();
    }

    #[test]
    fn closed_connection_is_visible_to_late_subscribers() {
        let health = ConnectionHealth::new();
        health.mark_dead();
        assert!(!*health.subscribe().borrow());
        assert!(!health.is_alive());
    }

    #[tokio::test]
    async fn http3_connect_and_data_survive_repeated_streams_and_split_frames() {
        let (addr, ca, server) = mock_proxy(200, Some(200)).await;
        let mut connection = connect(addr, ca).await.unwrap();
        tokio::time::timeout(Duration::from_secs(10), async {
            let mut previous_id = None;
            for sequence in 0..65 {
                let mut stream = connection
                    .open_proxy_stream("example.com", 80)
                    .await
                    .unwrap();
                assert_ne!(previous_id, Some(stream.stream_id));
                previous_id = Some(stream.stream_id);
                let mut greeting = Vec::new();
                while greeting.len() < 8 {
                    for message in stream.recv_messages().await.unwrap().unwrap() {
                        greeting.extend(message.data);
                    }
                }
                assert_eq!(greeting, b"greeting");
                let payload = vec![sequence as u8; 16384];
                stream.send_payload(&payload).await.unwrap();
                let mut echoed = Vec::new();
                while echoed.len() < payload.len() {
                    for message in stream.recv_messages().await.unwrap().unwrap() {
                        echoed.extend(message.data);
                    }
                }
                assert_eq!(echoed, payload);
                stream.send_disconnect().await.unwrap();
                while stream.recv_messages().await.unwrap().is_some() {}
            }
        })
        .await
        .expect("HTTP/3 streams stalled");
        server.abort();
    }

    #[tokio::test]
    async fn http3_rejects_http_error_and_encrypted_connect_failure() {
        for (http_status, ack_status) in [(503, Some(200)), (200, Some(244))] {
            let (addr, ca, server) = mock_proxy(http_status, ack_status).await;
            let mut connection = connect(addr, ca).await.unwrap();
            let result = connection.open_proxy_stream("example.com", 80).await;
            assert!(result.is_err(), "HTTP 200 alone must not mean connected");
            server.abort();
        }
    }

    #[tokio::test]
    async fn http3_connect_requires_acknowledgement_before_timeout() {
        let (addr, ca, server) = mock_proxy(200, None).await;
        let mut connection = connect(addr, ca).await.unwrap();
        let error = connection
            .open_proxy_stream("example.com", 80)
            .await
            .err()
            .unwrap();
        assert!(error.to_string().contains("timed out after 15s"));
        server.abort();
    }

    #[tokio::test]
    async fn http3_does_not_trust_an_unrelated_certificate() {
        let (addr, _, server) = mock_proxy(200, Some(200)).await;
        let other = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        assert!(connect(addr, other.cert.pem()).await.is_err());
        server.abort();
    }

    #[tokio::test]
    async fn http3_requires_socket_protection_before_connecting() {
        struct Denied;
        impl SocketProtector for Denied {
            fn protect(&self, _: i32) -> bool {
                false
            }
        }
        let error = OutboundConnection::connect(
            "127.0.0.1:9".parse().unwrap(),
            &Denied,
            Cipher::new(b"test-key").unwrap(),
            OutboundConfig {
                server_host: "localhost".into(),
                server_port: 9,
                server_name: String::new(),
                ca_pem: String::new(),
                tls: true,
            },
        )
        .await
        .err()
        .unwrap();
        assert!(error.to_string().contains("protect QUIC UDP socket"));
    }
}
