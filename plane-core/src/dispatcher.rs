use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};
use tokio::sync::{mpsc, Mutex};
use tokio_rustls::{rustls, TlsConnector};

use crate::crypto::Cipher;
use crate::error::{CoreError, Result};
use crate::mobile_config::{RoutingConfig, Transport};
use crate::net_probe::FakeDnsEngine;
use crate::outbound::{OutboundConfig, OutboundConnection, SocketProtector};
use crate::outbound_h3;
use crate::routing::{ConnectionInfo, Protocol, RouteAction, Router};
use crate::stats::CoreStats;
use crate::tcp_stack::{SmolTcpStream, StreamCommand, TcpEvent};
use crate::transport::{proxy_via_remote_counted, ProxyConnection, ProxyStream};

const PROXY_READ_BUF: usize = 16 * 1024;
const NODE_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
type ConnectionPool = Vec<Mutex<Option<Arc<ProxyConnection>>>>;

fn new_connection_pool(node_count: usize) -> ConnectionPool {
    (0..node_count).map(|_| Mutex::new(None)).collect()
}

#[derive(Clone)]
pub struct RemoteNodeConfig {
    pub name: String,
    pub host: String,
    pub port: u16,
    pub key: Vec<u8>,
    pub cipher: String,
    pub tls: bool,
    pub transport: Transport,
    pub server_name: String,
    pub ca_pem: String,
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
    let proxy_conn = Arc::new(new_connection_pool(config.nodes.len()));

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
                        let config = config.clone();
                        let protector = Arc::clone(&protector);
                        let proxy_conn = Arc::clone(&proxy_conn);
                        let notify_tx = notify_tx.clone();
                        let mut shutdown_rx = shutdown_rx.clone();
                        tokio::spawn(async move {
                            // A slow/dead proxy node must not block the dispatcher
                            // from handling direct connections or the stop signal.
                            let open = open_proxy_with_failover(&config, protector.as_ref(), &proxy_conn, &target_host, dst_port);
                            let result = tokio::select! {
                                _ = shutdown_rx.changed() => return,
                                result = tokio::time::timeout(Duration::from_secs(30), open) => {
                                    result.unwrap_or_else(|_| Err(CoreError::Protocol(
                                        format!("proxy connection to {target_host}:{dst_port} timed out after 30s")
                                    )))
                                }
                            };
                            match result {
                                Ok((stream, node_name)) => {
                                    config.stats.set_state("connected");
                                    config.stats.set_error("");
                                    config.stats.begin_connection();
                                    config.stats.inc_proxy();
                                    config.stats.set_active_node(node_name);
                                    let local = SmolTcpStream::new(stream_tx, stream_rx, notify_tx.clone());
                                    let stats = Arc::clone(&config.stats);
                                    let forwarding = proxy_via_remote_counted(stream, local, PROXY_READ_BUF, Some(Arc::clone(&stats)));
                                    let result = tokio::select! {
                                        _ = shutdown_rx.changed() => Ok(()),
                                        result = forwarding => result,
                                    };
                                    if let Err(e) = result {
                                        stats.set_state("proxy_error");
                                        stats.inc_failed();
                                        stats.set_error(format!("proxy: {e}"));
                                        tracing::warn!("proxy connection ended with error: {e}");
                                    }
                                    stats.end_connection();
                                }
                                Err(e) => {
                                    if !matches!(&e, CoreError::TargetRejected(_)) {
                                        config.stats.set_state("proxy_error");
                                    }
                                    config.stats.inc_failed();
                                    config.stats.set_error(format!("proxy open: {e}"));
                                    tracing::error!("failed to open proxy stream for {target_host}:{dst_port}: {e}");
                                    let _ = stream_tx.try_send(StreamCommand::Close);
                                }
                            }
                        });
                    }
                }
            }
        }
    }
}

async fn open_proxy_with_failover<P>(
    config: &DispatcherConfig,
    protector: &P,
    pool: &ConnectionPool,
    host: &str,
    port: u16,
) -> Result<(ProxyStream, String)>
where
    P: SocketProtector,
{
    let mut last_error: Option<CoreError> = None;
    for (idx, node) in config.nodes.iter().enumerate() {
        // Serialize only connection creation. Waiting for a target's CONNECT
        // acknowledgement must not hold up requests to other destinations.
        let connection = {
            let mut cached = pool[idx].lock().await;
            if let Some(connection) = cached.as_ref() {
                Arc::clone(connection)
            } else {
                match establish_outbound(node, protector).await {
                    Ok(connection) => {
                        let connection = Arc::new(connection);
                        *cached = Some(Arc::clone(&connection));
                        connection
                    }
                    Err(e) => {
                        tracing::warn!("node {} connect failed: {e}", node.name);
                        last_error = Some(e);
                        continue;
                    }
                }
            }
        };
        let mut sender = connection.as_ref().clone();
        match sender.open_proxy_stream(host, port).await {
            Ok(stream) => return Ok((stream, node.name.clone())),
            // The node decrypted and answered CONNECT. A destination refusal
            // is not a broken node and must not tear down unrelated flows.
            Err(e @ CoreError::TargetRejected(_)) => return Err(e),
            Err(e) => {
                tracing::warn!("node {} proxy stream failed: {e}", node.name);
                let mut cached = pool[idx].lock().await;
                if cached
                    .as_ref()
                    .is_some_and(|current| Arc::ptr_eq(current, &connection))
                {
                    *cached = None;
                }
                last_error = Some(e);
            }
        }
    }

    Err(last_error.unwrap_or_else(|| {
        CoreError::Internal("no remote node could open a proxy stream".to_string())
    }))
}

async fn establish_outbound<P>(node: &RemoteNodeConfig, protector: &P) -> Result<ProxyConnection>
where
    P: SocketProtector,
{
    let cipher = Cipher::from_name(&node.cipher.to_ascii_lowercase(), &node.key)?;
    if node.transport == Transport::Http3 {
        let addr = resolve_server_addr_with_protector(&node.host, node.port, protector)?;
        return outbound_h3::OutboundConnection::connect(
            addr,
            protector,
            cipher,
            outbound_h3::OutboundConfig {
                server_host: node.host.clone(),
                server_port: node.port,
                server_name: node.server_name.clone(),
                ca_pem: node.ca_pem.clone(),
                tls: true,
            },
        )
        .await
        .map(ProxyConnection::Http3);
    }

    let tcp = connect_protected(&node.host, node.port, protector).await?;
    let outbound_cfg = OutboundConfig {
        server_host: node.host.clone(),
        server_port: node.port,
        tls: node.tls,
    };

    if node.tls {
        let server_name = if node.server_name.is_empty() {
            &node.host
        } else {
            &node.server_name
        };
        let tls = connect_tls(tcp, server_name).await?;
        OutboundConnection::handshake(tls, cipher, outbound_cfg)
            .await
            .map(ProxyConnection::Http2)
    } else {
        OutboundConnection::handshake(tcp, cipher, outbound_cfg)
            .await
            .map(ProxyConnection::Http2)
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
    let socket = if addr.is_ipv6() {
        TcpSocket::new_v6()
    } else {
        TcpSocket::new_v4()
    }
    .map_err(|e| CoreError::Io(std::io::Error::other(format!("create socket failed: {e}"))))?;
    let fd = socket.as_raw_fd();
    if !protector.protect(fd) {
        return Err(CoreError::Internal(format!(
            "protect socket fd={fd} failed"
        )));
    }

    let tcp = tokio::time::timeout(NODE_CONNECT_TIMEOUT, socket.connect(addr))
        .await
        .map_err(|_| {
            CoreError::Io(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!("TCP connect {host}:{port} ({addr}) timed out after 10s"),
            ))
        })?
        .map_err(|e| {
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
    tokio::time::timeout(NODE_CONNECT_TIMEOUT, connector.connect(server_name, tcp))
        .await
        .map_err(|_| {
            CoreError::Io(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!("TLS handshake with {host} timed out after 10s"),
            ))
        })?
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
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::from((ip, port)));
    }

    let mut addresses: Vec<_> = (host, port)
        .to_socket_addrs()
        .map_err(|e| {
            CoreError::Io(std::io::Error::other(format!(
                "resolve {host}:{port} failed: {e}"
            )))
        })?
        .collect();
    addresses.sort_by_key(|addr| addr.is_ipv6());
    addresses
        .into_iter()
        .next()
        .ok_or_else(|| CoreError::Internal(format!("no IP address for {host}:{port}")))
}

fn resolve_server_addr_with_protector<P>(host: &str, port: u16, protector: &P) -> Result<SocketAddr>
where
    P: SocketProtector,
{
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::from((ip, port)));
    }
    if let Some(ip) = protector.resolve_ip(host) {
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
    use std::net::Ipv4Addr;

    struct ProtectedDnsOnly {
        resolved: Option<IpAddr>,
    }

    impl SocketProtector for ProtectedDnsOnly {
        fn protect(&self, _fd: i32) -> bool {
            true
        }

        fn resolve_ip(&self, _host: &str) -> Option<IpAddr> {
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
            resolved: Some(Ipv4Addr::new(203, 0, 113, 7).into()),
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

    #[tokio::test]
    async fn protected_ipv6_nodes_keep_their_address_family() {
        let listener = tokio::net::TcpListener::bind("[::1]:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let protector = ProtectedDnsOnly {
            resolved: Some("::1".parse().unwrap()),
        };
        assert_eq!(
            resolve_server_addr_with_protector("::1", port, &protector).unwrap(),
            listener.local_addr().unwrap(),
        );
        let stream = connect_protected("ipv6.test", port, &protector)
            .await
            .unwrap();
        assert!(stream.peer_addr().unwrap().is_ipv6());
        let _accepted = listener.accept().await.unwrap();
    }

    #[tokio::test]
    async fn stalled_proxy_does_not_block_direct_connections_or_shutdown() {
        use crate::mobile_config::RuleConfig;
        use crate::outbound::NoopProtector;
        use tokio::net::TcpListener;

        // Accept TCP but never answer HTTP/2; this models a wrong-protocol or
        // unresponsive node while a separate local destination remains healthy.
        let stalled_node = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let direct_target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let direct_port = direct_target.local_addr().unwrap().port();
        let (event_tx, event_rx) = mpsc::channel(8);
        let (notify_tx, _notify_rx) = mpsc::channel(8);
        let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
        let stats = Arc::new(CoreStats::new());
        let config = DispatcherConfig {
            nodes: vec![RemoteNodeConfig {
                name: "stalled".to_string(),
                host: "127.0.0.1".to_string(),
                port: stalled_node.local_addr().unwrap().port(),
                key: b"test-key".to_vec(),
                cipher: "chacha20".to_string(),
                tls: false,
                transport: Transport::Http2,
                server_name: String::new(),
                ca_pem: String::new(),
            }],
            routing: RoutingConfig {
                default_action: "proxy".to_string(),
                cn_direct: false,
                rules: vec![RuleConfig {
                    rule_type: "port".to_string(),
                    value: direct_port.to_string(),
                    action: "direct".to_string(),
                }],
            },
            stats,
        };
        let dispatcher = tokio::spawn(run_dispatcher(
            event_rx,
            Arc::new(Mutex::new(FakeDnsEngine::new("198.18.0.0/15", 32))),
            config,
            Arc::new(NoopProtector),
            notify_tx,
            shutdown_rx,
        ));
        let (proxy_tx, _proxy_rx) = mpsc::channel(4);
        let (_proxy_data_tx, proxy_data_rx) = mpsc::channel(4);
        event_tx
            .send(TcpEvent::NewConnection {
                src_ip: Ipv4Addr::LOCALHOST,
                dst_ip: Ipv4Addr::LOCALHOST,
                dst_port: 443,
                stream_tx: proxy_tx,
                stream_rx: proxy_data_rx,
            })
            .await
            .unwrap();
        let (_stalled_socket, _) =
            tokio::time::timeout(Duration::from_secs(2), stalled_node.accept())
                .await
                .unwrap()
                .unwrap();

        let (direct_tx, _direct_rx) = mpsc::channel(4);
        let (_direct_data_tx, direct_data_rx) = mpsc::channel(4);
        event_tx
            .send(TcpEvent::NewConnection {
                src_ip: Ipv4Addr::LOCALHOST,
                dst_ip: Ipv4Addr::LOCALHOST,
                dst_port: direct_port,
                stream_tx: direct_tx,
                stream_rx: direct_data_rx,
            })
            .await
            .unwrap();
        let (_direct_socket, _) =
            tokio::time::timeout(Duration::from_secs(2), direct_target.accept())
                .await
                .expect("direct traffic blocked by pending proxy CONNECT")
                .unwrap();
        shutdown_tx.send(true).unwrap();
        tokio::time::timeout(Duration::from_secs(2), dispatcher)
            .await
            .expect("dispatcher ignored shutdown while CONNECT was pending")
            .unwrap()
            .unwrap();
    }

    #[tokio::test]
    async fn slow_or_rejected_target_does_not_block_or_replace_other_proxy_streams() {
        let (addr, ca_pem, server) = crate::outbound_h3::tests::mock_proxy(200, Some(200)).await;
        let config = DispatcherConfig {
            nodes: vec![RemoteNodeConfig {
                name: "mock".into(),
                host: "127.0.0.1".into(),
                port: addr.port(),
                key: b"test-key".to_vec(),
                cipher: "chacha20".into(),
                tls: true,
                transport: Transport::Http3,
                server_name: "localhost".into(),
                ca_pem,
            }],
            routing: RoutingConfig::default(),
            stats: Arc::new(CoreStats::new()),
        };
        let pool = new_connection_pool(1);
        let protector = crate::outbound::NoopProtector;
        let (mut original_stream, _) =
            open_proxy_with_failover(&config, &protector, &pool, "example.com", 80)
                .await
                .unwrap();
        let original_connection = pool[0].lock().await.as_ref().unwrap().clone();

        let slow = open_proxy_with_failover(&config, &protector, &pool, "slow.test", 80);
        tokio::pin!(slow);
        assert!(tokio::time::timeout(Duration::from_millis(50), &mut slow)
            .await
            .is_err());
        let (_second, _) = tokio::time::timeout(
            Duration::from_secs(2),
            open_proxy_with_failover(&config, &protector, &pool, "example.com", 80),
        )
        .await
        .expect("slow destination blocked another proxy connection")
        .unwrap();

        let rejection =
            open_proxy_with_failover(&config, &protector, &pool, "reject.test", 80).await;
        assert!(matches!(rejection, Err(CoreError::TargetRejected(_))));
        assert!(Arc::ptr_eq(
            pool[0].lock().await.as_ref().unwrap(),
            &original_connection
        ));
        tokio::time::timeout(Duration::from_secs(2), async {
            let mut greeting = Vec::new();
            while greeting.len() < 8 {
                for message in original_stream.recv_messages().await.unwrap().unwrap() {
                    greeting.extend(message.data);
                }
            }
            original_stream.send_payload(b"alive").await.unwrap();
            let mut echoed = Vec::new();
            while echoed.len() < 5 {
                for message in original_stream.recv_messages().await.unwrap().unwrap() {
                    echoed.extend(message.data);
                }
            }
            assert_eq!(echoed, b"alive");
        })
        .await
        .expect("rejected destination interrupted an existing stream");
        server.abort();
    }

    #[tokio::test]
    #[ignore = "requires PLANE_NODE_HOST, PLANE_NODE_PORT and PLANE_NODE_KEY"]
    async fn configured_node_http_probe() {
        let node = RemoteNodeConfig {
            name: "diagnostic".to_string(),
            host: std::env::var("PLANE_NODE_HOST").expect("PLANE_NODE_HOST"),
            port: std::env::var("PLANE_NODE_PORT")
                .expect("PLANE_NODE_PORT")
                .parse()
                .unwrap(),
            key: std::env::var("PLANE_NODE_KEY")
                .expect("PLANE_NODE_KEY")
                .into_bytes(),
            cipher: "chacha20".to_string(),
            tls: std::env::var("PLANE_NODE_TLS").as_deref() == Ok("true"),
            transport: if std::env::var("PLANE_NODE_TRANSPORT").as_deref() == Ok("http3") {
                Transport::Http3
            } else {
                Transport::Http2
            },
            server_name: std::env::var("PLANE_NODE_SERVER_NAME").unwrap_or_default(),
            ca_pem: std::env::var("PLANE_NODE_CA_FILE")
                .ok()
                .map(|path| std::fs::read_to_string(path).unwrap())
                .unwrap_or_default(),
        };
        let mut connection = establish_outbound(&node, &crate::outbound::NoopProtector)
            .await
            .expect("node transport handshake failed");
        async fn request<T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin>(
            mut io: T,
        ) -> String {
            io.write_all(b"GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
                .await
                .unwrap();
            let mut data = Vec::new();
            let mut buf = [0; 4096];
            loop {
                let n = io.read(&mut buf).await.unwrap();
                if n == 0 {
                    break;
                }
                data.extend_from_slice(&buf[..n]);
                if data.windows(2).any(|pair| pair == b"\r\n") {
                    break;
                }
            }
            let _ = io.shutdown().await;
            String::from_utf8_lossy(&data)
                .lines()
                .next()
                .unwrap_or("<empty>")
                .to_string()
        }

        // Exercise the actual bidirectional forwarding loop for both HTTP and
        // HTTPS, including certificate verification at the destination website.
        for port in [80, 443] {
            let stream = connection
                .open_proxy_stream("example.com", port)
                .await
                .expect("encrypted CONNECT failed");
            let (local, application) = tokio::io::duplex(65536);
            let forwarding = tokio::spawn(proxy_via_remote_counted(
                stream,
                local,
                PROXY_READ_BUF,
                None,
            ));
            let status = tokio::time::timeout(Duration::from_secs(15), async {
                if port == 443 {
                    let mut roots = rustls::RootCertStore::empty();
                    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
                    let tls = rustls::ClientConfig::builder()
                        .with_root_certificates(roots)
                        .with_no_client_auth();
                    let connector = TlsConnector::from(Arc::new(tls));
                    let tls = connector
                        .connect("example.com".try_into().unwrap(), application)
                        .await
                        .unwrap();
                    request(tls).await
                } else {
                    request(application).await
                }
            })
            .await
            .expect("web response through tunnel timed out");
            eprintln!("Tunnel example.com:{port}: {status}");
            assert!(
                status.starts_with("HTTP/1."),
                "no HTTP response through proxy"
            );
            forwarding.abort();
        }
    }
}
