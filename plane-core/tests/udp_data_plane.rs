#![cfg(unix)]
//! Exercises the actual Android stack entry with packet-preserving TUN IO.
use std::net::{Ipv4Addr, SocketAddrV4};
use std::os::fd::IntoRawFd;
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
use std::time::Duration;

use plane_core::android_tun::AndroidTun;
use plane_core::dispatcher::{DispatcherConfig, RemoteNodeConfig};
use plane_core::mobile_config::{RoutingConfig, Transport};
use plane_core::net_probe::FakeDnsEngine;
use plane_core::outbound::SocketProtector;
use plane_core::stats::CoreStats;
use plane_core::tcp_stack::stack_loop_with_udp;
use plane_core::udp::{build_packet, parse_packet, MAX_PAYLOAD};
use tokio::net::{UdpSocket, UnixDatagram};
use tokio::sync::{mpsc, watch, Mutex};

#[derive(Default)]
struct Protection {
    calls: AtomicUsize,
    deny: bool,
}
impl SocketProtector for Protection {
    fn protect(&self, _: i32) -> bool {
        self.calls.fetch_add(1, Ordering::SeqCst);
        !self.deny
    }
}

struct Harness {
    peer: Arc<UnixDatagram>,
    shutdown: watch::Sender<bool>,
    stack: tokio::task::JoinHandle<plane_core::error::Result<()>>,
    protector: Arc<Protection>,
    stats: Arc<CoreStats>,
    dns: Arc<Mutex<FakeDnsEngine>>,
}
impl Harness {
    async fn start(action: &str, node: Option<RemoteNodeConfig>, deny: bool) -> Self {
        let (fd, peer) = std::os::unix::net::UnixDatagram::pair().unwrap();
        peer.set_nonblocking(true).unwrap();
        let peer = Arc::new(UnixDatagram::from_std(peer).unwrap());
        let (reader, writer) = unsafe { AndroidTun::from_raw_fd(fd.into_raw_fd(), 1500) }
            .unwrap()
            .split();
        let dns = Arc::new(Mutex::new(FakeDnsEngine::new("198.18.0.0/15", 64)));
        let protector = Arc::new(Protection {
            deny,
            ..Protection::default()
        });
        let stats = Arc::new(CoreStats::new());
        let config = DispatcherConfig {
            nodes: node.into_iter().collect(),
            routing: RoutingConfig {
                default_action: action.into(),
                cn_direct: false,
                rules: vec![],
            },
            stats: stats.clone(),
        };
        let (event_tx, _event_rx) = mpsc::channel(16);
        let (notify_tx, notify_rx) = mpsc::channel(16);
        let (shutdown, shutdown_rx) = watch::channel(false);
        let stack = tokio::spawn(stack_loop_with_udp(
            reader,
            writer,
            dns.clone(),
            event_tx,
            notify_tx,
            notify_rx,
            shutdown_rx,
            Some((config, protector.clone())),
        ));
        Self {
            peer,
            shutdown,
            stack,
            protector,
            stats,
            dns,
        }
    }
    async fn send(&self, source: u16, dst: SocketAddrV4, bytes: &[u8]) {
        let src = SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), source);
        self.peer
            .send(&build_packet(src, dst, bytes).unwrap())
            .await
            .unwrap();
    }
    async fn receive(&self) -> plane_core::udp::Datagram {
        let mut buf = [0u8; 65535];
        let n = tokio::time::timeout(Duration::from_secs(10), self.peer.recv(&mut buf))
            .await
            .expect("TUN response timeout")
            .unwrap();
        parse_packet(&buf[..n]).expect("response must have valid lengths and IP/UDP checksums")
    }
    async fn stop(self) {
        self.shutdown.send(true).unwrap();
        tokio::time::timeout(Duration::from_secs(2), self.stack)
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }
        let stats: serde_json::Value = serde_json::from_str(&self.stats.snapshot_json()).unwrap();
        assert_eq!(
            stats["active_connections"], 0,
            "stop must release associations"
        );
    }
}

async fn echo() -> (SocketAddrV4, tokio::task::JoinHandle<()>) {
    let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let addr = match socket.local_addr().unwrap() {
        std::net::SocketAddr::V4(a) => a,
        _ => unreachable!(),
    };
    let task = tokio::spawn(async move {
        let mut buf = [0u8; 65535];
        loop {
            let (n, peer) = socket.recv_from(&mut buf).await.unwrap();
            if &buf[..n] == b"oversize" {
                socket
                    .send_to(&vec![7; MAX_PAYLOAD + 1], peer)
                    .await
                    .unwrap();
            } else {
                socket.send_to(&buf[..n], peer).await.unwrap();
            }
        }
    });
    (addr, task)
}

#[test]
fn udp_packet_boundaries_and_rejections() {
    let src = "10.0.0.2:1234".parse().unwrap();
    let dst = "203.0.113.5:443".parse().unwrap();
    for n in [0, 1, 99, MAX_PAYLOAD] {
        let bytes = vec![0xa5; n];
        let packet = build_packet(src, dst, &bytes).unwrap();
        let parsed = parse_packet(&packet).unwrap();
        assert_eq!((parsed.src, parsed.dst, parsed.payload), (src, dst, bytes));
        for end in 0..packet.len() {
            assert!(parse_packet(&packet[..end]).is_err());
        }
        let mut corrupt = packet.clone();
        corrupt[8] ^= 1;
        assert!(parse_packet(&corrupt).is_err());
        if n > 0 {
            let mut corrupt = packet.clone();
            corrupt[28] ^= 1;
            assert!(parse_packet(&corrupt).is_err());
        }
        let mut fragment = packet;
        fragment[6] |= 0x20;
        assert!(parse_packet(&fragment).is_err());
    }
    assert!(build_packet(src, dst, &vec![0; MAX_PAYLOAD + 1]).is_err());
}

#[tokio::test]
async fn direct_udp_actual_tun_roundtrip_empty_maximum_and_isolation() {
    let (dst, echo) = echo().await;
    let h = Harness::start("direct", None, false).await;
    for n in [0, 1, 31, MAX_PAYLOAD] {
        let bytes = vec![n as u8; n];
        h.send(24000, dst, &bytes).await;
        let got = h.receive().await;
        assert_eq!(got.payload, bytes);
        assert_eq!(got.src, dst);
        assert_eq!(got.dst.port(), 24000);
    }
    for source in 24001..24017 {
        h.send(source, dst, &source.to_be_bytes()).await;
    }
    let mut seen = std::collections::HashSet::new();
    for _ in 0..16 {
        let got = h.receive().await;
        assert_eq!(got.payload, got.dst.port().to_be_bytes());
        seen.insert(got.dst.port());
    }
    assert_eq!(seen.len(), 16);
    assert_eq!(h.protector.calls.load(Ordering::SeqCst), 17);
    h.send(24000, dst, b"oversize").await;
    assert!(
        tokio::time::timeout(Duration::from_millis(100), h.peer.recv(&mut [0; 1600]))
            .await
            .is_err()
    );
    h.send(24000, dst, b"still alive").await;
    assert_eq!(h.receive().await.payload, b"still alive");
    h.stop().await;
    echo.abort();
}

#[tokio::test]
async fn reject_and_failed_protect_never_send_udp() {
    for (action, deny) in [("reject", false), ("direct", true)] {
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let dst = match socket.local_addr().unwrap() {
            std::net::SocketAddr::V4(a) => a,
            _ => unreachable!(),
        };
        let h = Harness::start(action, None, deny).await;
        h.send(24000, dst, b"must not escape").await;
        assert!(
            tokio::time::timeout(Duration::from_millis(100), socket.recv(&mut [0; 100]))
                .await
                .is_err()
        );
        assert_eq!(h.protector.calls.load(Ordering::SeqCst), usize::from(deny));
        let stats: serde_json::Value = serde_json::from_str(&h.stats.snapshot_json()).unwrap();
        if deny {
            assert!(stats.to_string().contains("protect UDP socket failed"));
        }
        h.stop().await;
    }
}

#[tokio::test]
async fn idle_association_expires_and_next_packet_reopens_it() {
    let (dst, echo) = echo().await;
    let h = Harness::start("direct", None, false).await;
    h.send(24000, dst, b"first").await;
    assert_eq!(h.receive().await.payload, b"first");
    tokio::time::pause();
    tokio::time::advance(Duration::from_secs(61)).await;
    for _ in 0..10 {
        tokio::task::yield_now().await;
    }
    tokio::time::resume();
    let stats: serde_json::Value = serde_json::from_str(&h.stats.snapshot_json()).unwrap();
    assert_eq!(stats["active_connections"], 0);
    h.send(24000, dst, b"second").await;
    assert_eq!(h.receive().await.payload, b"second");
    assert_eq!(h.protector.calls.load(Ordering::SeqCst), 2);
    h.stop().await;
    echo.abort();
}

#[tokio::test]
async fn dns_remains_in_tun_and_fakeip_udp_uses_the_domain() {
    let (dst, echo) = echo().await;
    let h = Harness::start("direct", None, false).await;
    let fake = h.dns.lock().await.allocate_ip("localhost");
    let fake_dst = SocketAddrV4::new(fake, dst.port());
    h.send(25000, fake_dst, b"fakeip").await;
    let got = h.receive().await;
    assert_eq!(got.src, fake_dst);
    assert_eq!(got.payload, b"fakeip");
    // The actual DNS path (not just pre-populating the map).
    let query = hickory_proto::op::Message::new()
        .add_query(hickory_proto::op::Query::query(
            hickory_proto::rr::Name::from_ascii("localhost.").unwrap(),
            hickory_proto::rr::RecordType::A,
        ))
        .to_vec()
        .unwrap();
    h.send(25001, "198.18.0.1:53".parse().unwrap(), &query)
        .await;
    let got = h.receive().await;
    let response = hickory_proto::op::Message::from_vec(&got.payload).unwrap();
    assert_eq!(response.answers().len(), 1);
    h.stop().await;
    echo.abort();
}

#[tokio::test]
#[ignore = "run scripts/test-android-udp.py: requires real Java proxy-remote"]
async fn java_remote_udp_actual_tun_end_to_end() {
    let transport: Transport = serde_json::from_str(&format!(
        "\"{}\"",
        std::env::var("UDP_TEST_TRANSPORT").unwrap()
    ))
    .unwrap();
    let node = RemoteNodeConfig {
        name: "udp-test".into(),
        host: "127.0.0.1".into(),
        port: std::env::var("UDP_TEST_PORT").unwrap().parse().unwrap(),
        key: b"0123456789abcdef0123456789abcdef".to_vec(),
        cipher: std::env::var("UDP_TEST_CIPHER").unwrap(),
        tls: false,
        transport,
        server_name: "localhost".into(),
        ca_pem: std::fs::read_to_string(std::env::var("UDP_TEST_CA").unwrap()).unwrap(),
    };
    let (dst, echo) = echo().await;
    let h = Harness::start("proxy", Some(node), false).await;
    let fake = h.dns.lock().await.allocate_ip("localhost");
    let fake_dst = SocketAddrV4::new(fake, dst.port());
    for n in [1, 0, 17, MAX_PAYLOAD] {
        eprintln!("UDP E2E payload_length={n}");
        let bytes = vec![n as u8; n];
        h.send(26000, fake_dst, &bytes).await;
        let got = h.receive().await;
        assert_eq!(got.src, fake_dst);
        assert_eq!(got.dst.port(), 26000);
        assert_eq!(got.payload, bytes);
    }
    for _ in 0..32 {
        h.send(26000, fake_dst, b"").await;
        let got = h.receive().await;
        assert_eq!(got.src, fake_dst);
        assert_eq!(got.payload, b"");
    }
    for source in 26001u16..26033 {
        h.send(source, dst, &source.to_be_bytes()).await;
    }
    let mut seen = std::collections::HashSet::new();
    for _ in 0..32 {
        let got = h.receive().await;
        assert_eq!(got.src, dst);
        assert_eq!(got.payload, got.dst.port().to_be_bytes());
        seen.insert(got.dst.port());
    }
    assert_eq!(seen.len(), 32);
    // Many differently sized messages in flight on one association: no merging or splitting.
    for round in 0..24 {
        h.send(26000, fake_dst, &vec![round; 50 + round as usize])
            .await;
    }
    for round in 0..24 {
        assert_eq!(h.receive().await.payload, vec![round; 50 + round as usize]);
    }
    assert!(h.protector.calls.load(Ordering::SeqCst) > 0);
    let stats: serde_json::Value = serde_json::from_str(&h.stats.snapshot_json()).unwrap();
    assert_eq!(
        stats["proxy_connections"], 33,
        "each association must be acknowledged by Java"
    );
    assert_eq!(
        stats["direct_connections"], 0,
        "the E2E test must not bypass the proxy"
    );
    h.stop().await;
    echo.abort();
    eprintln!("ANDROID_UDP_JAVA_E2E_OK fakeip=true concurrent_associations=33 datagrams=92 empty=true max_payload=1472 checksums=valid");
}
