//! IPv4 UDP associations for the Android TUN data path.
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddrV4};
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::time::Duration;

use tokio::net::UdpSocket;
use tokio::sync::{mpsc, watch, Mutex};
use tokio::task::JoinSet;

use crate::dispatcher::{
    new_connection_pool, open_with_failover, resolve_server_addr_with_protector, ConnectionPool,
    DispatcherConfig,
};
use crate::error::{CoreError, Result};
use crate::net_probe::FakeDnsEngine;
use crate::outbound::SocketProtector;
use crate::proxy_proto::MessageType;
use crate::routing::{ConnectionInfo, Protocol, RouteAction, Router};
use crate::stats::CoreStats;
use crate::transport::ProxyStream;

pub const MAX_PAYLOAD: usize = 1472;
const MAX_ASSOCIATIONS: usize = 1024;
const QUEUE_SIZE: usize = 32;
const IDLE: Duration = Duration::from_secs(60);

#[derive(Debug)]
pub struct Datagram {
    pub src: SocketAddrV4,
    pub dst: SocketAddrV4,
    pub payload: Vec<u8>,
}

/// Reject fragmented, truncated, corrupt and oversized UDP before interpreting ports.
pub fn parse_packet(packet: &[u8]) -> std::result::Result<Datagram, &'static str> {
    if packet.len() < 28 || packet[0] >> 4 != 4 || packet[9] != 17 {
        return Err("not IPv4 UDP");
    }
    let ihl = (packet[0] & 15) as usize * 4;
    let total = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    if ihl < 20 || total != packet.len() || total < ihl + 8 || total > 1500 {
        return Err("invalid IP length or MTU exceeded");
    }
    if u16::from_be_bytes([packet[6], packet[7]]) & 0x3fff != 0 {
        return Err("IP fragmentation unsupported");
    }
    if checksum(&packet[..ihl]) != 0 {
        return Err("invalid IP checksum");
    }
    let udp = &packet[ihl..];
    if u16::from_be_bytes([udp[4], udp[5]]) as usize != udp.len() {
        return Err("invalid UDP length");
    }
    if udp[6..8] != [0, 0] && udp_checksum(&packet[12..20], udp) != 0 {
        return Err("invalid UDP checksum");
    }
    let src = SocketAddrV4::new(
        Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]),
        u16::from_be_bytes([udp[0], udp[1]]),
    );
    let dst = SocketAddrV4::new(
        Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]),
        u16::from_be_bytes([udp[2], udp[3]]),
    );
    if src.port() == 0 || dst.port() == 0 {
        return Err("zero UDP port");
    }
    Ok(Datagram {
        src,
        dst,
        payload: udp[8..].to_vec(),
    })
}

/// Construct a complete, checksummed IPv4 UDP packet for the TUN writer.
pub fn build_packet(src: SocketAddrV4, dst: SocketAddrV4, payload: &[u8]) -> Result<Vec<u8>> {
    if payload.len() > MAX_PAYLOAD {
        return Err(CoreError::Protocol("UDP payload exceeds MTU".into()));
    }
    let mut packet = vec![0u8; 28 + payload.len()];
    packet[0] = 0x45;
    let total = packet.len() as u16;
    packet[2..4].copy_from_slice(&total.to_be_bytes());
    packet[6] = 0x40;
    packet[8] = 64;
    packet[9] = 17;
    packet[12..16].copy_from_slice(&src.ip().octets());
    packet[16..20].copy_from_slice(&dst.ip().octets());
    let ip_checksum = checksum(&packet[..20]);
    packet[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
    packet[20..22].copy_from_slice(&src.port().to_be_bytes());
    packet[22..24].copy_from_slice(&dst.port().to_be_bytes());
    packet[24..26].copy_from_slice(&((payload.len() + 8) as u16).to_be_bytes());
    packet[28..].copy_from_slice(payload);
    let csum = udp_checksum(&packet[12..20], &packet[20..]);
    packet[26..28].copy_from_slice(&(if csum == 0 { 0xffff } else { csum }).to_be_bytes());
    Ok(packet)
}

fn checksum(bytes: &[u8]) -> u16 {
    let mut sum: u32 = bytes
        .chunks(2)
        .map(|c| ((c[0] as u32) << 8) | c.get(1).copied().unwrap_or(0) as u32)
        .sum();
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}
fn udp_checksum(addresses: &[u8], udp: &[u8]) -> u16 {
    let mut pseudo = Vec::with_capacity(12 + udp.len());
    pseudo.extend_from_slice(addresses);
    pseudo.extend_from_slice(&[0, 17]);
    pseudo.extend_from_slice(&(udp.len() as u16).to_be_bytes());
    pseudo.extend_from_slice(udp);
    checksum(&pseudo)
}

// A sized delegate also preserves Android's protected DNS policy.
struct Protector(Arc<dyn SocketProtector>);
impl SocketProtector for Protector {
    fn protect(&self, fd: i32) -> bool {
        self.0.protect(fd)
    }
    fn resolve_ip(&self, host: &str) -> Option<IpAddr> {
        self.0.resolve_ip(host)
    }
    fn allow_unprotected_dns_fallback(&self) -> bool {
        self.0.allow_unprotected_dns_fallback()
    }
}

pub struct UdpForwarder {
    associations: HashMap<(SocketAddrV4, SocketAddrV4), mpsc::Sender<Vec<u8>>>,
    tasks: JoinSet<()>,
    config: DispatcherConfig,
    protector: Arc<Protector>,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    replies: mpsc::Sender<Vec<u8>>,
    shutdown: watch::Receiver<bool>,
    pool: Arc<ConnectionPool>,
    router: Arc<Router>,
}

struct ActiveAssociation(Arc<CoreStats>);
impl Drop for ActiveAssociation {
    fn drop(&mut self) {
        self.0.end_connection();
    }
}

impl UdpForwarder {
    pub fn new(
        config: DispatcherConfig,
        protector: Arc<dyn SocketProtector>,
        fake_dns: Arc<Mutex<FakeDnsEngine>>,
        replies: mpsc::Sender<Vec<u8>>,
        shutdown: watch::Receiver<bool>,
    ) -> Result<Self> {
        let router = Arc::new(Router::from_config(&config.routing)?);
        let pool = Arc::new(new_connection_pool(config.nodes.len()));
        Ok(Self {
            associations: HashMap::new(),
            tasks: JoinSet::new(),
            config,
            protector: Arc::new(Protector(protector)),
            fake_dns,
            replies,
            shutdown,
            pool,
            router,
        })
    }

    /// Never wait on network IO or a full queue in the TUN reader.
    pub fn forward(&mut self, datagram: Datagram) {
        while self.tasks.try_join_next().is_some() {}
        self.associations.retain(|_, tx| !tx.is_closed());
        let key = (datagram.src, datagram.dst);
        if let Some(tx) = self.associations.get(&key) {
            if tx.try_send(datagram.payload).is_err() {
                tracing::debug!(?key, "UDP drop: association queue full");
            }
            return;
        }
        if self.associations.len() >= MAX_ASSOCIATIONS {
            tracing::debug!("UDP drop: association limit");
            return;
        }
        let info = ConnectionInfo {
            src_ip: (*datagram.src.ip()).into(),
            dst_ip: (*datagram.dst.ip()).into(),
            dst_port: datagram.dst.port(),
            domain: None,
            protocol: Protocol::Udp,
        };
        let router = self.router.clone();
        let config = self.config.clone();
        let fake_dns = self.fake_dns.clone();
        let protector = self.protector.clone();
        let replies = self.replies.clone();
        let pool = self.pool.clone();
        let mut shutdown = self.shutdown.clone();
        let (tx, rx) = mpsc::channel(QUEUE_SIZE);
        tx.try_send(datagram.payload)
            .expect("new queue has capacity");
        self.associations.insert(key, tx);
        self.tasks.spawn(async move {
            if *shutdown.borrow() { return; }
            let forward = async {
                let domain = fake_dns.lock().await.lookup_domain(datagram.dst.ip()).map(str::to_owned);
                // A stale FakeIP must not escape as a real network destination.
                if domain.is_none() && datagram.dst.ip().octets()[0] == 198 && datagram.dst.ip().octets()[1] & 0xfe == 18 {
                    return Err(CoreError::Protocol("UDP FakeIP mapping expired".into()));
                }
                let host = domain.clone().unwrap_or_else(|| datagram.dst.ip().to_string());
                let action = router.route(&ConnectionInfo { domain, ..info });
                if action == RouteAction::Reject { config.stats.inc_rejected(); return Ok(()); }
                tracing::info!(?key, ?action, host, "UDP association opened");
                let remote = tokio::time::timeout(Duration::from_secs(30), async {
                    match action {
                        RouteAction::Direct => {
                            let addr = resolve_server_addr_with_protector(&host, datagram.dst.port(), protector.as_ref())?;
                            let socket = std::net::UdpSocket::bind(if addr.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" })?;
                            if !protector.protect(socket.as_raw_fd()) { return Err(CoreError::Internal("protect UDP socket failed".into())); }
                            socket.set_nonblocking(true)?;
                            let socket = UdpSocket::from_std(socket)?;
                            socket.connect(addr).await?;
                            config.stats.inc_direct();
                            Ok(Remote::Direct(socket))
                        }
                        RouteAction::Proxy => {
                            let (stream, node) = open_with_failover(&config, protector.as_ref(), &pool, &host, datagram.dst.port(), true).await?;
                            config.stats.inc_proxy();
                            config.stats.set_active_node(node);
                            config.stats.set_state("connected");
                            Ok(Remote::Proxy(stream))
                        }
                        RouteAction::Reject => unreachable!(),
                    }
                }).await.map_err(|_| CoreError::Protocol("UDP association open timed out".into()))??;
                config.stats.begin_connection();
                let _active = ActiveAssociation(config.stats.clone());
                relay(remote, rx, replies, key, &config).await
            };
            let result = tokio::select! { biased; _ = shutdown.changed() => Ok(()), result = forward => result };
            if let Err(error) = result {
                config.stats.inc_failed();
                config.stats.set_error(format!("UDP: {error}"));
                tracing::warn!(?key, %error, "UDP association failed");
            }
            tracing::debug!(?key, "UDP association released");
        });
    }
}

enum Remote {
    Direct(UdpSocket),
    Proxy(ProxyStream),
}
impl Remote {
    async fn send(&mut self, bytes: &[u8]) -> Result<()> {
        match self {
            Self::Direct(s) => {
                s.send(bytes).await?;
                Ok(())
            }
            Self::Proxy(s) => s.send_datagram(bytes).await,
        }
    }
    async fn recv(&mut self, buf: &mut [u8]) -> Result<Option<Vec<Vec<u8>>>> {
        match self {
            Self::Direct(s) => {
                let n = s.recv(buf).await?;
                Ok(Some(vec![buf[..n].to_vec()]))
            }
            Self::Proxy(s) => match s.recv_messages().await? {
                None => Ok(None),
                Some(messages) => {
                    let mut datagrams = Vec::new();
                    for message in messages {
                        match message.type_ {
                            MessageType::Data => datagrams.push(message.data),
                            MessageType::Disconnect => return Ok(None),
                            MessageType::ConnectResponse if message.status != 200 => {
                                return Err(CoreError::Protocol("UDP rejected by remote".into()))
                            }
                            _ => {}
                        }
                    }
                    Ok(Some(datagrams))
                }
            },
        }
    }
}

async fn relay(
    mut remote: Remote,
    mut rx: mpsc::Receiver<Vec<u8>>,
    replies: mpsc::Sender<Vec<u8>>,
    key: (SocketAddrV4, SocketAddrV4),
    config: &DispatcherConfig,
) -> Result<()> {
    // Read the entire datagram so oversize replies are dropped, never truncated into valid packets.
    let mut buf = vec![0u8; 65535];
    let idle = tokio::time::sleep(IDLE);
    tokio::pin!(idle);
    let result = loop {
        tokio::select! {
            _ = &mut idle => break Ok(()),
            data = rx.recv() => {
                let Some(data) = data else { break Ok(()); };
                match tokio::time::timeout(IDLE, remote.send(&data)).await {
                    Ok(Ok(())) => {},
                    Ok(Err(e)) => break Err(e),
                    Err(_) => break Err(CoreError::Protocol("UDP send stalled for 60s".into())),
                }
                config.stats.add_upload(data.len());
                idle.as_mut().reset(tokio::time::Instant::now() + IDLE);
            }
            data = remote.recv(&mut buf) => {
                match data {
                    Err(e) => break Err(e),
                    Ok(None) => break Ok(()),
                    Ok(Some(datagrams)) => for data in datagrams {
                        if data.len() > MAX_PAYLOAD { tracing::debug!("UDP drop: response exceeds MTU"); continue; }
                        let packet = build_packet(key.1, key.0, &data)?;
                        if replies.try_send(packet).is_err() { tracing::debug!("UDP drop: TUN reply queue full"); continue; }
                        config.stats.add_download(data.len());
                        idle.as_mut().reset(tokio::time::Instant::now() + IDLE);
                    }
                }
            }
        }
    };
    if let Remote::Proxy(stream) = &mut remote {
        let _ = tokio::time::timeout(Duration::from_secs(1), stream.send_disconnect()).await;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mobile_config::RoutingConfig;
    use crate::outbound::NoopProtector;

    #[tokio::test]
    async fn association_and_datagram_queues_are_bounded() {
        let stats = Arc::new(CoreStats::new());
        let config = DispatcherConfig {
            nodes: vec![],
            routing: RoutingConfig::default(),
            stats,
        };
        let (replies, _) = mpsc::channel(1);
        let (_stop, shutdown) = watch::channel(false);
        let mut forwarder = UdpForwarder::new(
            config,
            Arc::new(NoopProtector),
            Arc::new(Mutex::new(FakeDnsEngine::new("198.18.0.0/15", 8))),
            replies,
            shutdown,
        )
        .unwrap();
        let dst: SocketAddrV4 = "203.0.113.1:443".parse().unwrap();
        let make = |port| Datagram {
            src: SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), port),
            dst,
            payload: vec![1; MAX_PAYLOAD],
        };
        // Do not yield: opening tasks have not run yet, so this also tests the pending-open cap.
        for port in 1000..1000 + MAX_ASSOCIATIONS as u16 + 1 {
            forwarder.forward(make(port));
        }
        assert_eq!(forwarder.associations.len(), MAX_ASSOCIATIONS);
        assert_eq!(forwarder.tasks.len(), MAX_ASSOCIATIONS);
        for _ in 0..QUEUE_SIZE + 1 {
            forwarder.forward(make(1000));
        }
        assert_eq!(
            forwarder
                .associations
                .get(&(make(1000).src, dst))
                .unwrap()
                .capacity(),
            0
        );
    }
}
