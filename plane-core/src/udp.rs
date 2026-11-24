//! Android IPv4 UDP data plane. One fixed peer per application four-tuple.
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::os::unix::io::AsRawFd;
use std::sync::Arc;
use std::time::Duration;

use tokio::net::UdpSocket;
use tokio::sync::{mpsc, watch};
use tokio::task::JoinSet;

use crate::dispatcher::{
    open_udp_with_failover, resolve_server_addr_async, ConnectionPool, DispatcherConfig,
};
use crate::error::{CoreError, Result};
use crate::outbound::SocketProtector;
use crate::proxy_proto::MessageType;
use crate::routing::RouteAction;
use crate::stats::CoreStats;
use crate::tcp_stack::build_udp_response;
use crate::transport::ProxyStream;

// Matches proxy-remote OutboundSession.MAX_UDP_PAYLOAD (1500 - IPv4/UDP headers).
pub const MAX_UDP_PAYLOAD: usize = 1472;
const MAX_ASSOCIATIONS: usize = 256;
const QUEUE_CAPACITY: usize = 32;
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct UdpFlow {
    pub src_ip: Ipv4Addr,
    pub src_port: u16,
    pub dst_ip: Ipv4Addr,
    pub dst_port: u16,
}

impl UdpFlow {
    fn response(&self, payload: &[u8]) -> Vec<u8> {
        build_udp_response(
            self.dst_ip.octets(),
            self.src_ip.octets(),
            self.dst_port,
            self.src_port,
            payload,
        )
    }
}

#[derive(Debug)]
pub struct UdpDatagram {
    pub flow: UdpFlow,
    pub payload: Vec<u8>,
}

/// Reject fragments, invalid lengths/checksums and oversized datagrams before DNS or routing.
pub fn parse_ipv4_udp(packet: &[u8]) -> Option<UdpDatagram> {
    if packet.len() < 28 || packet[0] >> 4 != 4 || packet[9] != 17 {
        return None;
    }
    let header_len = (packet[0] & 15) as usize * 4;
    let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    if header_len < 20
        || total_len != packet.len()
        || total_len < header_len + 8
        || u16::from_be_bytes([packet[6], packet[7]]) & 0xbfff != 0
    {
        return None;
    }
    let ipv4 = smoltcp::wire::Ipv4Packet::new_checked(packet).ok()?;
    if !ipv4.verify_checksum() {
        return None;
    }
    let udp = smoltcp::wire::UdpPacket::new_checked(&packet[header_len..total_len]).ok()?;
    if udp.len() as usize != total_len - header_len
        || udp.payload().len() > MAX_UDP_PAYLOAD
        || udp.src_port() == 0
        || udp.dst_port() == 0
        || !udp.verify_checksum(&ipv4.src_addr().into(), &ipv4.dst_addr().into())
    {
        return None;
    }
    Some(UdpDatagram {
        flow: UdpFlow {
            src_ip: Ipv4Addr::from(ipv4.src_addr().0),
            src_port: udp.src_port(),
            dst_ip: Ipv4Addr::from(ipv4.dst_addr().0),
            dst_port: udp.dst_port(),
        },
        payload: udp.payload().to_vec(),
    })
}

pub(crate) struct UdpRequest {
    pub datagram: UdpDatagram,
    pub host: String,
    pub action: RouteAction,
    pub response_tx: mpsc::Sender<Vec<u8>>,
}

pub(crate) struct UdpAssociations<P> {
    flows: HashMap<UdpFlow, mpsc::Sender<Vec<u8>>>,
    pub tasks: JoinSet<()>,
    config: DispatcherConfig,
    protector: Arc<P>,
}

impl<P: SocketProtector + 'static> UdpAssociations<P> {
    pub fn new(config: DispatcherConfig, protector: Arc<P>) -> Self {
        Self {
            flows: HashMap::new(),
            tasks: JoinSet::new(),
            config,
            protector,
        }
    }

    pub fn clear(&mut self) {
        self.flows.clear();
    }

    pub fn dispatch(
        &mut self,
        mut request: UdpRequest,
        pool: Arc<ConnectionPool>,
        mut shutdown: watch::Receiver<bool>,
        mut network: watch::Receiver<u64>,
    ) {
        let flow = request.datagram.flow;
        self.flows.retain(|_, sender| !sender.is_closed());
        if let Some(sender) = self.flows.get(&flow) {
            // UDP overload drops a whole datagram; it never stalls the TUN/TCP loop.
            match sender.try_send(request.datagram.payload) {
                Ok(()) | Err(mpsc::error::TrySendError::Full(_)) => return,
                Err(mpsc::error::TrySendError::Closed(payload)) => {
                    request.datagram.payload = payload;
                    self.flows.remove(&flow);
                }
            }
        }
        if request.action == RouteAction::Reject {
            self.config.stats.inc_rejected();
            return;
        }
        if self.flows.len() >= MAX_ASSOCIATIONS || self.tasks.len() >= MAX_ASSOCIATIONS {
            return;
        }
        let (sender, mut receiver) = mpsc::channel(QUEUE_CAPACITY);
        // The queue is new, so its first datagram always fits, including an empty one.
        let _ = sender.try_send(request.datagram.payload);
        self.flows.insert(flow, sender);
        let config = self.config.clone();
        let protector = Arc::clone(&self.protector);
        self.tasks.spawn(async move {
            config.stats.begin_connection();
            let _active = ActiveAssociation(Arc::clone(&config.stats));
            let setup = async {
                match request.action {
                    RouteAction::Direct => {
                        let addr = resolve_server_addr_async(&request.host, flow.dst_port, Arc::clone(&protector)).await?;
                        let socket = UdpSocket::bind(if addr.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" }).await?;
                        if !protector.protect(socket.as_raw_fd()) {
                            return Err(CoreError::Internal("protect direct UDP socket failed".into()));
                        }
                        socket.connect(addr).await?;
                        config.stats.inc_direct();
                        Ok(UdpOutbound::Direct(socket))
                    }
                    RouteAction::Proxy => {
                        let (stream, node) = open_udp_with_failover(&config, protector, &pool, &request.host, flow.dst_port).await?;
                        config.stats.inc_proxy();
                        config.stats.set_state("connected");
                        config.stats.set_active_node(node);
                        config.stats.set_error("");
                        Ok(UdpOutbound::Proxy(stream))
                    }
                    RouteAction::Reject => unreachable!(),
                }
            };
            let result = tokio::select! {
                biased;
                _ = shutdown.changed() => return,
                _ = network.changed() => return,
                result = tokio::time::timeout(Duration::from_secs(30), setup) => result.unwrap_or_else(|_| Err(CoreError::Timeout("UDP association setup timed out".into()))),
            };
            let result = match result {
                Ok(mut outbound) => {
                    let result = tokio::select! {
                        biased;
                        _ = shutdown.changed() => Ok(()),
                        _ = network.changed() => Ok(()),
                        result = outbound.forward(flow, &mut receiver, &request.response_tx, &config.stats, IDLE_TIMEOUT) => result,
                    };
                    if let UdpOutbound::Proxy(stream) = &mut outbound {
                        let _ = tokio::time::timeout(Duration::from_millis(250), stream.send_disconnect()).await;
                    }
                    result
                }
                Err(error) => Err(error),
            };
            if let Err(error) = result {
                config.stats.inc_failed();
                config.stats.set_error(format!("udp: {error}"));
                tracing::warn!(?flow, %error, "UDP association ended");
            }
        });
    }
}

struct ActiveAssociation(Arc<CoreStats>);
impl Drop for ActiveAssociation {
    fn drop(&mut self) {
        self.0.end_connection();
    }
}

enum UdpOutbound {
    Direct(UdpSocket),
    Proxy(ProxyStream),
}

impl UdpOutbound {
    async fn forward(
        &mut self,
        flow: UdpFlow,
        receiver: &mut mpsc::Receiver<Vec<u8>>,
        response_tx: &mpsc::Sender<Vec<u8>>,
        stats: &CoreStats,
        idle_timeout: Duration,
    ) -> Result<()> {
        // One extra byte makes an oversized UDP response detectable without truncating it into a valid one.
        let mut buffer = vec![0; MAX_UDP_PAYLOAD + 1];
        let idle = tokio::time::sleep(idle_timeout);
        tokio::pin!(idle);
        loop {
            tokio::select! {
                _ = &mut idle => return Ok(()),
                payload = receiver.recv() => {
                    let Some(payload) = payload else { return Ok(()); };
                    tokio::time::timeout(Duration::from_secs(5), async {
                        match self {
                            Self::Direct(socket) => { socket.send(&payload).await?; }
                            Self::Proxy(stream) => { stream.send_datagram(&payload).await?; }
                        }
                        Ok::<_, CoreError>(())
                    }).await.map_err(|_| CoreError::Timeout("UDP send stalled".into()))??;
                    stats.add_upload(payload.len());
                    idle.as_mut().reset(tokio::time::Instant::now() + idle_timeout);
                }
                replies = self.recv(&mut buffer) => {
                    let (replies, closed) = replies?;
                    for payload in replies {
                        if payload.len() <= MAX_UDP_PAYLOAD {
                            let len = payload.len();
                            if response_tx.try_send(flow.response(&payload)).is_ok() {
                                stats.add_download(len);
                            }
                        }
                    }
                    if closed { return Ok(()); }
                    idle.as_mut().reset(tokio::time::Instant::now() + idle_timeout);
                }
            }
        }
    }

    async fn recv(&mut self, buffer: &mut [u8]) -> Result<(Vec<Vec<u8>>, bool)> {
        match self {
            Self::Direct(socket) => {
                let len = socket.recv(buffer).await?;
                Ok((vec![buffer[..len].to_vec()], false))
            }
            Self::Proxy(stream) => {
                let Some(messages) = stream.recv_messages().await? else {
                    return Ok((vec![], true));
                };
                let mut replies = Vec::new();
                let mut closed = false;
                for message in messages {
                    if message.stream_id != stream.stream_id() {
                        continue;
                    }
                    match message.type_ {
                        MessageType::Data => replies.push(message.data),
                        MessageType::Disconnect => {
                            closed = true;
                            break;
                        }
                        _ => {}
                    }
                }
                Ok((replies, closed))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::android_tun::AndroidTun;
    use crate::dispatcher::{run_dispatcher, RemoteNodeConfig};
    use crate::mobile_config::{RoutingConfig, Transport};
    use crate::net_probe::FakeDnsEngine;
    use crate::tcp_stack::stack_loop;
    use std::net::IpAddr;
    use std::os::unix::io::IntoRawFd;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Mutex;

    #[derive(Default)]
    struct Protector {
        deny: bool,
        calls: AtomicUsize,
        names: Mutex<Vec<String>>,
    }
    impl SocketProtector for Protector {
        fn protect(&self, fd: i32) -> bool {
            // A connected socket here would violate Android's protect-before-connect rule.
            let mut peer = std::mem::MaybeUninit::<libc::sockaddr_storage>::uninit();
            let mut len = std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t;
            let result = unsafe { libc::getpeername(fd, peer.as_mut_ptr().cast(), &mut len) };
            assert_eq!(result, -1, "protect called after connect");
            self.calls.fetch_add(1, Ordering::SeqCst);
            !self.deny
        }
        fn resolve_ip(&self, host: &str) -> Option<IpAddr> {
            self.names.lock().unwrap().push(host.into());
            Some(Ipv4Addr::LOCALHOST.into())
        }
        fn allow_unprotected_dns_fallback(&self) -> bool {
            false
        }
    }

    struct Harness {
        app: tokio::net::UnixDatagram,
        shutdown: watch::Sender<bool>,
        network: watch::Sender<u64>,
        tasks: Vec<tokio::task::JoinHandle<()>>,
        stats: Arc<CoreStats>,
        dns: Arc<tokio::sync::Mutex<FakeDnsEngine>>,
    }
    impl Drop for Harness {
        fn drop(&mut self) {
            self.shutdown.send_replace(true);
            for task in &self.tasks {
                task.abort();
            }
        }
    }

    fn node() -> RemoteNodeConfig {
        RemoteNodeConfig {
            name: "unused".into(),
            host: "127.0.0.1".into(),
            port: 9,
            key: b"test-key".to_vec(),
            cipher: "chacha20".into(),
            tls: false,
            transport: Transport::Http2,
            server_name: String::new(),
            ca_pem: String::new(),
            subscription_id: String::new(),
        }
    }

    fn harness(action: &str, node: RemoteNodeConfig, protector: Arc<Protector>) -> Harness {
        let (tun, app) = std::os::unix::net::UnixDatagram::pair().unwrap();
        app.set_nonblocking(true).unwrap();
        let app = tokio::net::UnixDatagram::from_std(app).unwrap();
        // The fd is owned only by AndroidTun after this transfer.
        let (reader, writer) = unsafe { AndroidTun::from_raw_fd(tun.into_raw_fd(), 1500) }
            .unwrap()
            .split();
        let dns = Arc::new(tokio::sync::Mutex::new(FakeDnsEngine::new(
            "198.18.0.0/15",
            64,
        )));
        let stats = Arc::new(CoreStats::new());
        let config = DispatcherConfig {
            nodes: vec![node],
            routing: RoutingConfig {
                default_action: action.into(),
                cn_direct: false,
                rules: vec![],
            },
            stats: Arc::clone(&stats),
        };
        let (events, event_rx) = mpsc::channel(256);
        let (notify, notifications) = mpsc::channel(256);
        let (shutdown, shutdown_rx) = watch::channel(false);
        let (network, network_rx) = watch::channel(0);
        let stack_dns = Arc::clone(&dns);
        let stack_notify = notify.clone();
        let stack_shutdown = shutdown_rx.clone();
        let stack = tokio::spawn(async move {
            stack_loop(
                reader,
                writer,
                stack_dns,
                events,
                stack_notify,
                notifications,
                stack_shutdown,
            )
            .await
            .unwrap();
        });
        let dispatcher_dns = Arc::clone(&dns);
        let dispatcher = tokio::spawn(async move {
            run_dispatcher(
                event_rx,
                dispatcher_dns,
                config,
                protector,
                notify,
                shutdown_rx,
                network_rx,
            )
            .await
            .unwrap();
        });
        Harness {
            app,
            shutdown,
            network,
            tasks: vec![stack, dispatcher],
            stats,
            dns,
        }
    }

    fn packet(src_port: u16, dst: Ipv4Addr, dst_port: u16, payload: &[u8]) -> Vec<u8> {
        build_udp_response([10, 0, 0, 2], dst.octets(), src_port, dst_port, payload)
    }

    async fn receive(app: &tokio::net::UnixDatagram) -> UdpDatagram {
        let mut buffer = [0; 2048];
        let len = tokio::time::timeout(Duration::from_secs(3), app.recv(&mut buffer))
            .await
            .unwrap()
            .unwrap();
        parse_ipv4_udp(&buffer[..len]).expect("invalid response packet")
    }

    #[test]
    fn udp_packet_validation_preserves_boundaries_and_rejects_fragments() {
        for size in [0, 1, MAX_UDP_PAYLOAD] {
            let raw = packet(1234, Ipv4Addr::LOCALHOST, 443, &vec![7; size]);
            assert_eq!(parse_ipv4_udp(&raw).unwrap().payload, vec![7; size]);
            for end in 0..raw.len() {
                assert!(parse_ipv4_udp(&raw[..end]).is_none());
            }
        }
        assert!(parse_ipv4_udp(&packet(
            1234,
            Ipv4Addr::LOCALHOST,
            443,
            &vec![0; MAX_UDP_PAYLOAD + 1]
        ))
        .is_none());
        let original = packet(1234, Ipv4Addr::LOCALHOST, 443, b"hello");
        for (offset, value) in [(6, 0x20), (7, 1), (0, 0x44), (9, 6), (25, 8), (27, 1)] {
            let mut bad = original.clone();
            bad[offset] = value;
            assert!(
                parse_ipv4_udp(&bad).is_none(),
                "accepted bad field {offset}"
            );
        }
        let mut padded = original;
        padded.push(0);
        assert!(parse_ipv4_udp(&padded).is_none());
    }

    #[tokio::test]
    async fn direct_udp_runs_through_android_tun_with_empty_maximum_and_isolated_flows() {
        let protector = Arc::new(Protector::default());
        let harness = harness("direct", node(), Arc::clone(&protector));
        let target = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let port = target.local_addr().unwrap().port();
        let mut first_peer = None;
        let mut buffer = [0; 2048];
        for (src_port, payload) in [
            (4000, vec![]),
            (4000, vec![5; MAX_UDP_PAYLOAD]),
            (4001, b"other app".to_vec()),
        ] {
            harness
                .app
                .send(&packet(src_port, Ipv4Addr::LOCALHOST, port, &payload))
                .await
                .unwrap();
            let (len, peer) =
                tokio::time::timeout(Duration::from_secs(3), target.recv_from(&mut buffer))
                    .await
                    .unwrap()
                    .unwrap();
            assert_eq!(&buffer[..len], payload);
            if src_port == 4000 {
                assert_eq!(*first_peer.get_or_insert(peer), peer);
            } else {
                assert_ne!(first_peer.unwrap(), peer);
            }
            target.send_to(&buffer[..len], peer).await.unwrap();
            let response = receive(&harness.app).await;
            assert_eq!(response.payload, payload);
            assert_eq!(response.flow.dst_port, src_port);
            assert_eq!(response.flow.src_port, port);
            assert_eq!(response.flow.dst_ip, Ipv4Addr::new(10, 0, 0, 2));
        }
        assert_eq!(protector.calls.load(Ordering::SeqCst), 2);
        harness.network.send_replace(1);
        tokio::time::timeout(Duration::from_secs(2), async {
            while serde_json::from_str::<serde_json::Value>(&harness.stats.snapshot_json()).unwrap()
                ["active_connections"]
                != 0
            {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        harness
            .app
            .send(&packet(4000, Ipv4Addr::LOCALHOST, port, b"new network"))
            .await
            .unwrap();
        let (len, peer) =
            tokio::time::timeout(Duration::from_secs(3), target.recv_from(&mut buffer))
                .await
                .unwrap()
                .unwrap();
        assert_eq!(&buffer[..len], b"new network");
        assert_ne!(first_peer.unwrap(), peer);
        assert_eq!(protector.calls.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn reject_and_protect_failure_never_send_udp() {
        for (action, deny, calls) in [("reject", false, 0), ("direct", true, 1)] {
            let protector = Arc::new(Protector {
                deny,
                ..Protector::default()
            });
            let harness = harness(action, node(), Arc::clone(&protector));
            let target = UdpSocket::bind("127.0.0.1:0").await.unwrap();
            harness
                .app
                .send(&packet(
                    4000,
                    Ipv4Addr::LOCALHOST,
                    target.local_addr().unwrap().port(),
                    b"blocked",
                ))
                .await
                .unwrap();
            assert!(
                tokio::time::timeout(Duration::from_millis(120), target.recv(&mut [0; 64]))
                    .await
                    .is_err()
            );
            assert_eq!(protector.calls.load(Ordering::SeqCst), calls);
        }
    }

    #[tokio::test]
    async fn fakeip_udp_uses_protected_domain_resolution_and_replies_from_original_ip() {
        let protector = Arc::new(Protector::default());
        let harness = harness("direct", node(), Arc::clone(&protector));
        let fake_ip = harness.dns.lock().await.allocate_ip("udp.test");
        let target = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let port = target.local_addr().unwrap().port();
        harness
            .app
            .send(&packet(4000, fake_ip, port, b"domain"))
            .await
            .unwrap();
        let mut buffer = [0; 64];
        let (len, peer) =
            tokio::time::timeout(Duration::from_secs(3), target.recv_from(&mut buffer))
                .await
                .unwrap()
                .unwrap();
        target.send_to(&buffer[..len], peer).await.unwrap();
        let response = receive(&harness.app).await;
        assert_eq!(response.flow.src_ip, fake_ip);
        assert_eq!(protector.names.lock().unwrap().as_slice(), &["udp.test"]);
        harness
            .app
            .send(&packet(
                4001,
                Ipv4Addr::new(198, 19, 200, 1),
                port,
                b"expired mapping",
            ))
            .await
            .unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(100), target.recv(&mut buffer))
                .await
                .is_err()
        );
        assert_eq!(protector.calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn direct_udp_drops_wrong_peer_and_oversized_responses() {
        let harness = harness("direct", node(), Arc::new(Protector::default()));
        let target = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let spoof = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        harness
            .app
            .send(&packet(
                4000,
                Ipv4Addr::LOCALHOST,
                target.local_addr().unwrap().port(),
                b"request",
            ))
            .await
            .unwrap();
        let (_, peer) =
            tokio::time::timeout(Duration::from_secs(3), target.recv_from(&mut [0; 64]))
                .await
                .unwrap()
                .unwrap();
        spoof.send_to(b"wrong peer", peer).await.unwrap();
        target
            .send_to(&vec![5; MAX_UDP_PAYLOAD + 1], peer)
            .await
            .unwrap();
        target.send_to(b"valid", peer).await.unwrap();
        assert_eq!(receive(&harness.app).await.payload, b"valid");
    }

    #[tokio::test]
    async fn dns_stays_local_and_does_not_create_udp_associations() {
        use hickory_proto::op::Message;
        use hickory_proto::op::Query;
        use hickory_proto::rr::{Name, RecordType};
        let protector = Arc::new(Protector::default());
        let harness = harness("proxy", node(), Arc::clone(&protector));
        let mut query = Message::new();
        query.set_id(123).add_query(Query::query(
            Name::from_ascii("udp.test").unwrap(),
            RecordType::A,
        ));
        harness
            .app
            .send(&packet(
                4000,
                Ipv4Addr::new(198, 18, 0, 1),
                53,
                &query.to_vec().unwrap(),
            ))
            .await
            .unwrap();
        let response = receive(&harness.app).await;
        let answer = Message::from_vec(&response.payload).unwrap();
        assert_eq!(answer.id(), 123);
        assert_eq!(answer.answers().len(), 1);
        // Empty or malformed DNS must also stay local.
        harness
            .app
            .send(&packet(4000, Ipv4Addr::LOCALHOST, 53, b"bad dns"))
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert_eq!(protector.calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn udp_idle_timeout_closes_without_waiting_for_eof() {
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect("127.0.0.1:9").await.unwrap();
        let mut outbound = UdpOutbound::Direct(socket);
        let (_tx, mut rx) = mpsc::channel(1);
        let (responses, _) = mpsc::channel(1);
        let flow = UdpFlow {
            src_ip: Ipv4Addr::LOCALHOST,
            src_port: 4000,
            dst_ip: Ipv4Addr::LOCALHOST,
            dst_port: 9,
        };
        tokio::time::timeout(
            Duration::from_secs(1),
            outbound.forward(
                flow,
                &mut rx,
                &responses,
                &CoreStats::new(),
                Duration::from_millis(30),
            ),
        )
        .await
        .unwrap()
        .unwrap();
    }

    #[tokio::test]
    async fn http3_udp_runs_through_actual_tun_and_keeps_application_flows_isolated() {
        let (addr, ca_pem, server) = crate::outbound_h3::tests::mock_proxy(200, Some(200)).await;
        let mut remote = node();
        remote.transport = Transport::Http3;
        remote.host = addr.ip().to_string();
        remote.port = addr.port();
        remote.server_name = "localhost".into();
        remote.ca_pem = ca_pem;
        let harness = harness("proxy", remote, Arc::new(Protector::default()));
        let target_ip = Ipv4Addr::new(203, 0, 113, 10);
        for src_port in [4000, 4001] {
            harness
                .app
                .send(&packet(src_port, target_ip, 443, &[]))
                .await
                .unwrap();
            let greeting = receive(&harness.app).await;
            assert_eq!(greeting.payload, b"greeting");
            assert_eq!(greeting.flow.dst_port, src_port);
            let response = receive(&harness.app).await;
            assert!(response.payload.is_empty());
            assert_eq!(response.flow.src_ip, target_ip);
            assert_eq!(response.flow.dst_port, src_port);
        }
        harness
            .app
            .send(&packet(4000, target_ip, 443, b"close-after-echo"))
            .await
            .unwrap();
        assert_eq!(receive(&harness.app).await.payload, b"close-after-echo");
        server.abort();
    }
}
