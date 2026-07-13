use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs};
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};
use tokio::sync::{mpsc, Mutex};
use tokio_rustls::{rustls, TlsConnector};

use crate::crypto::Cipher;
use crate::error::{CoreError, Result};
use crate::mobile_config::RoutingConfig;
use crate::net_probe::FakeDnsEngine;
use crate::outbound::{
    proxy_via_remote_counted, OutboundConfig, OutboundConnection, OutboundStream, SocketProtector,
};
use crate::routing::{ConnectionInfo, Protocol, RouteAction, Router};
use crate::stats::CoreStats;
use crate::tcp_stack::{SmolTcpStream, StreamCommand, TcpEvent};

const PROXY_READ_BUF: usize = 16 * 1024;

#[derive(Clone)]
pub struct RemoteNodeConfig {
    pub name: String,
    pub host: String,
    pub port: u16,
    pub key: Vec<u8>,
    pub cipher: String,
    pub tls: bool,
}

#[derive(Clone)]
pub struct DispatcherConfig {
    pub nodes: Vec<RemoteNodeConfig>,
    pub routing: RoutingConfig,
    pub stats: Arc<CoreStats>,
}

pub async fn run_dispatcher<P>(
    mut event_rx: mpsc::Receiver<TcpEvent>,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    config: DispatcherConfig,
    protector: Arc<P>,
    notify_tx: mpsc::Sender<()>,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
) -> Result<()>
where
    P: SocketProtector + 'static,
{
    if config.nodes.is_empty() {
        return Err(CoreError::InvalidArgument(
            "dispatcher requires at least one ready remote node".to_string(),
        ));
    }

    let router = Router::from_config(&config.routing)?;
    let mut proxy_conn: Option<(usize, OutboundConnection)> = None;

    tracing::info!(
        "dispatcher started with {} node(s), default route={}",
        config.nodes.len(),
        config.routing.default_action
    );

    loop {
        tokio::select! {
            res = shutdown_rx.changed() => {
                if res.is_err() || *shutdown_rx.borrow() {
                    tracing::info!("dispatcher received shutdown");
                    config.stats.set_state("stopped");
                    return Ok(());
                }
            }

            ev = event_rx.recv() => {
                let Some(TcpEvent::NewConnection { src_ip, dst_ip, dst_port, stream_tx, stream_rx }) = ev else {
                    tracing::info!("tcp event channel closed, dispatcher exiting");
                    config.stats.set_state("stopped");
                    return Ok(());
                };

                let domain = {
                    let engine = fake_dns.lock().await;
                    engine.lookup_domain(&dst_ip).map(|s| s.to_string())
                };
                let target_host = domain.clone().unwrap_or_else(|| dst_ip.to_string());
                let info = ConnectionInfo {
                    src_ip: IpAddr::V4(src_ip),
                    dst_ip: IpAddr::V4(dst_ip),
                    dst_port,
                    domain: domain.clone(),
                    protocol: Protocol::Tcp,
                };
                let action = router.route(&info);

                tracing::info!(
                    "connection {} -> {}:{} domain={:?} route={:?}",
                    src_ip,
                    dst_ip,
                    dst_port,
                    domain,
                    action
                );

                match action {
                    RouteAction::Reject => {
                        config.stats.inc_rejected();
                        let _ = stream_tx.try_send(StreamCommand::Close);
                    }
                    RouteAction::Direct => {
                        let local = SmolTcpStream::new(stream_tx, stream_rx, notify_tx.clone());
                        config.stats.begin_connection();
                        config.stats.inc_direct();
                        let stats = Arc::clone(&config.stats);
                        let protector = Arc::clone(&protector);
                        tokio::spawn(async move {
                            if let Err(e) = proxy_direct(target_host, dst_port, local, protector, Arc::clone(&stats)).await {
                                stats.inc_failed();
                                stats.set_error(format!("direct: {e}"));
                                tracing::warn!("direct connection ended with error: {e}");
                            }
                            stats.end_connection();
                        });
                    }
                    RouteAction::Proxy => {
                        match open_proxy_with_failover(&config, protector.as_ref(), &mut proxy_conn, &target_host, dst_port).await {
                            Ok((stream, node_name)) => {
                                config.stats.begin_connection();
                                config.stats.inc_proxy();
                                config.stats.set_active_node(node_name);
                                let local = SmolTcpStream::new(stream_tx, stream_rx, notify_tx.clone());
                                let stats = Arc::clone(&config.stats);
                                tokio::spawn(async move {
                                    if let Err(e) = proxy_via_remote_counted(stream, local, PROXY_READ_BUF, Some(Arc::clone(&stats))).await {
                                        stats.inc_failed();
                                        stats.set_error(format!("proxy: {e}"));
                                        tracing::warn!("proxy connection ended with error: {e}");
                                    }
                                    stats.end_connection();
                                });
                            }
                            Err(e) => {
                                config.stats.inc_failed();
                                config.stats.set_error(format!("proxy open: {e}"));
                                tracing::error!("failed to open proxy stream for {target_host}:{dst_port}: {e}");
                                let _ = stream_tx.try_send(StreamCommand::Close);
                                proxy_conn = None;
                            }
                        }
                    }
                }
            }
        }
    }
}

async fn open_proxy_with_failover<P>(
    config: &DispatcherConfig,
    protector: &P,
    active: &mut Option<(usize, OutboundConnection)>,
    host: &str,
    port: u16,
) -> Result<(OutboundStream, String)>
where
    P: SocketProtector,
{
    if let Some((idx, conn)) = active.as_mut() {
        match conn.open_proxy_stream(host, port).await {
            Ok(stream) => {
                let name = config.nodes[*idx].name.clone();
                return Ok((stream, name));
            }
            Err(e) => {
                tracing::warn!(
                    "active node {} failed to open stream: {e}; trying failover",
                    config.nodes[*idx].name
                );
            }
        }
    }
    *active = None;

    let mut last_error: Option<CoreError> = None;
    for (idx, node) in config.nodes.iter().enumerate() {
        match establish_outbound(node, protector).await {
            Ok(mut conn) => match conn.open_proxy_stream(host, port).await {
                Ok(stream) => {
                    let name = node.name.clone();
                    *active = Some((idx, conn));
                    return Ok((stream, name));
                }
                Err(e) => {
                    tracing::warn!("node {} opened tcp/h2 but stream failed: {e}", node.name);
                    last_error = Some(e);
                }
            },
            Err(e) => {
                tracing::warn!("node {} connect failed: {e}", node.name);
                last_error = Some(e);
            }
        }
    }

    Err(last_error.unwrap_or_else(|| {
        CoreError::Internal("no remote node could open a proxy stream".to_string())
    }))
}

async fn establish_outbound<P>(node: &RemoteNodeConfig, protector: &P) -> Result<OutboundConnection>
where
    P: SocketProtector,
{
    if !node.cipher.eq_ignore_ascii_case("chacha20") {
        return Err(CoreError::InvalidArgument(format!(
            "unsupported cipher for Android data plane: {}",
            node.cipher
        )));
    }

    let tcp = connect_protected(&node.host, node.port, protector).await?;
    let cipher = Cipher::new(&node.key)?;
    let outbound_cfg = OutboundConfig {
        server_host: node.host.clone(),
        server_port: node.port,
        tls: node.tls,
    };

    if node.tls {
        let tls = connect_tls(tcp, &node.host).await?;
        OutboundConnection::handshake(tls, cipher, outbound_cfg).await
    } else {
        OutboundConnection::handshake(tcp, cipher, outbound_cfg).await
    }
}

async fn proxy_direct<P>(
    host: String,
    port: u16,
    mut local: SmolTcpStream,
    protector: Arc<P>,
    stats: Arc<CoreStats>,
) -> Result<()>
where
    P: SocketProtector + 'static,
{
    let mut remote = connect_protected(&host, port, protector.as_ref()).await?;
    let mut up = vec![0u8; PROXY_READ_BUF];
    let mut down = vec![0u8; PROXY_READ_BUF];

    loop {
        tokio::select! {
            read = local.read(&mut up) => {
                let n = read.map_err(CoreError::Io)?;
                if n == 0 {
                    let _ = remote.shutdown().await;
                    break;
                }
                stats.add_upload(n);
                remote.write_all(&up[..n]).await.map_err(CoreError::Io)?;
            }
            read = remote.read(&mut down) => {
                let n = read.map_err(CoreError::Io)?;
                if n == 0 {
                    let _ = local.shutdown().await;
                    break;
                }
                stats.add_download(n);
                local.write_all(&down[..n]).await.map_err(CoreError::Io)?;
            }
        }
    }
    Ok(())
}

async fn connect_protected<P>(host: &str, port: u16, protector: &P) -> Result<TcpStream>
where
    P: SocketProtector,
{
    use std::os::unix::io::AsRawFd;

    let addr = resolve_server_addr_with_protector(host, port, protector)?;
    let socket = TcpSocket::new_v4()
        .map_err(|e| CoreError::Io(std::io::Error::other(format!("create socket failed: {e}"))))?;
    let fd = socket.as_raw_fd();
    if !protector.protect(fd) {
        return Err(CoreError::Internal(format!(
            "protect socket fd={fd} failed"
        )));
    }

    let tcp = socket.connect(addr).await.map_err(|e| {
        CoreError::Io(std::io::Error::other(format!(
            "connect {host}:{port} ({addr}) failed: {e}"
        )))
    })?;
    tcp.set_nodelay(true).ok();
    Ok(tcp)
}

async fn connect_tls(
    tcp: TcpStream,
    host: &str,
) -> Result<tokio_rustls::client::TlsStream<TcpStream>> {
    let mut tls_config = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoCertificateVerification))
        .with_no_client_auth();
    tls_config.alpn_protocols = vec![b"h2".to_vec()];

    let server_name = rustls::pki_types::ServerName::try_from(host.to_string())
        .map_err(|e| CoreError::InvalidArgument(format!("invalid TLS server name {host}: {e}")))?;
    let connector = TlsConnector::from(Arc::new(tls_config));
    connector
        .connect(server_name, tcp)
        .await
        .map_err(|e| CoreError::Io(std::io::Error::other(format!("TLS connect failed: {e}"))))
}

#[derive(Debug)]
struct NoCertificateVerification;

impl rustls::client::danger::ServerCertVerifier for NoCertificateVerification {
    fn verify_server_cert(
        &self,
        _end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> std::result::Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> std::result::Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> std::result::Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        vec![
            rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
            rustls::SignatureScheme::ECDSA_NISTP384_SHA384,
            rustls::SignatureScheme::ED25519,
            rustls::SignatureScheme::RSA_PSS_SHA256,
            rustls::SignatureScheme::RSA_PSS_SHA384,
            rustls::SignatureScheme::RSA_PKCS1_SHA256,
            rustls::SignatureScheme::RSA_PKCS1_SHA384,
        ]
    }
}

fn resolve_server_addr(host: &str, port: u16) -> Result<SocketAddr> {
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        return Ok(SocketAddr::from((ip, port)));
    }

    (host, port)
        .to_socket_addrs()
        .map_err(|e| {
            CoreError::Io(std::io::Error::other(format!(
                "resolve {host}:{port} failed: {e}"
            )))
        })?
        .find(|addr| addr.is_ipv4())
        .ok_or_else(|| CoreError::Internal(format!("no IPv4 address for {host}:{port}")))
}

fn resolve_server_addr_with_protector<P>(host: &str, port: u16, protector: &P) -> Result<SocketAddr>
where
    P: SocketProtector,
{
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        return Ok(SocketAddr::from((ip, port)));
    }
    if let Some(ip) = protector.resolve_ipv4(host) {
        return Ok(SocketAddr::from((ip, port)));
    }
    if !protector.allow_unprotected_dns_fallback() {
        return Err(CoreError::Internal(format!(
            "protected DNS resolution failed for {host}:{port}"
        )));
    }
    resolve_server_addr(host, port)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct ProtectedDnsOnly {
        resolved: Option<Ipv4Addr>,
    }

    impl SocketProtector for ProtectedDnsOnly {
        fn protect(&self, _fd: i32) -> bool {
            true
        }

        fn resolve_ipv4(&self, _host: &str) -> Option<Ipv4Addr> {
            self.resolved
        }

        fn allow_unprotected_dns_fallback(&self) -> bool {
            false
        }
    }

    #[test]
    fn resolve_ip_literal() {
        let addr = resolve_server_addr("1.2.3.4", 8443).unwrap();
        assert_eq!(addr.to_string(), "1.2.3.4:8443");
    }

    #[test]
    fn resolve_localhost() {
        let addr = resolve_server_addr("localhost", 80);
        assert!(addr.is_ok());
        assert_eq!(addr.unwrap().port(), 80);
    }

    #[test]
    fn protected_resolver_address_is_used() {
        let protector = ProtectedDnsOnly {
            resolved: Some(Ipv4Addr::new(203, 0, 113, 7)),
        };
        let addr = resolve_server_addr_with_protector("example.test", 443, &protector).unwrap();
        assert_eq!(addr, SocketAddr::from(([203, 0, 113, 7], 443)));
    }

    #[test]
    fn protected_resolver_does_not_fall_back_into_vpn_dns() {
        let protector = ProtectedDnsOnly { resolved: None };
        let err = resolve_server_addr_with_protector("example.invalid", 443, &protector)
            .expect_err("protected DNS failure must be terminal");
        assert!(err.to_string().contains("protected DNS resolution failed"));
    }
}
