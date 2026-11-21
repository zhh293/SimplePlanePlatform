//! Bounded UDP flows. IP packet validation, SOCKS framing and flow tasks are independent of TCP.
use crate::{
    direct::DirectEgress,
    fake_dns::FakeDnsEngine,
    route_policy::RoutePolicy,
    router::{ConnectionInfo, Protocol, RouteAction},
};
use std::{
    collections::HashMap,
    io,
    net::{Ipv4Addr, SocketAddr, SocketAddrV4},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpSocket, TcpStream, UdpSocket},
    sync::{mpsc, Mutex},
    task::JoinSet,
};

pub const MAX_PAYLOAD: usize = 1472;
const MAX_FLOWS: usize = 512;
const IDLE: Duration = Duration::from_secs(120);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct FlowKey {
    pub src: SocketAddrV4,
    pub dst: SocketAddrV4,
}
#[derive(Debug)]
pub struct Datagram {
    pub key: FlowKey,
    pub payload: Vec<u8>,
}

fn sum(bytes: &[u8]) -> u32 {
    bytes
        .chunks(2)
        .map(|p| {
            if p.len() == 2 {
                u16::from_be_bytes([p[0], p[1]]) as u32
            } else {
                (p[0] as u32) << 8
            }
        })
        .sum()
}
fn finish(mut value: u32) -> u16 {
    while value >> 16 != 0 {
        value = (value & 65535) + (value >> 16);
    }
    !(value as u16)
}
pub fn is_udp(packet: &[u8]) -> bool {
    packet.len() >= 20 && packet[0] >> 4 == 4 && packet[9] == 17
}
pub fn parse(packet: &[u8]) -> Option<Datagram> {
    if !is_udp(packet) || packet.len() > 1500 {
        return None;
    }
    let ihl = (packet[0] as usize & 15) * 4;
    if ihl < 20 || packet.len() < ihl + 8 || packet[8] == 0 {
        return None;
    }
    let total = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    if total != packet.len()
        || u16::from_be_bytes([packet[6], packet[7]]) & 0xbfff != 0
        || finish(sum(&packet[..ihl])) != 0
    {
        return None;
    }
    let udp = &packet[ihl..];
    let length = u16::from_be_bytes([udp[4], udp[5]]) as usize;
    if length != udp.len() || length < 8 || length - 8 > MAX_PAYLOAD {
        return None;
    }
    let src = Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]);
    let dst = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
    if udp[6..8] != [0, 0] && finish(sum(&packet[12..20]) + 17 + length as u32 + sum(udp)) != 0 {
        return None;
    }
    let src_port = u16::from_be_bytes([udp[0], udp[1]]);
    let dst_port = u16::from_be_bytes([udp[2], udp[3]]);
    if src_port == 0
        || dst_port == 0
        || src.is_multicast()
        || dst.is_multicast()
        || dst.is_broadcast()
        || dst.is_unspecified()
    {
        return None;
    }
    Some(Datagram {
        key: FlowKey {
            src: SocketAddrV4::new(src, src_port),
            dst: SocketAddrV4::new(dst, dst_port),
        },
        payload: udp[8..].to_vec(),
    })
}
pub fn packet(key: FlowKey, payload: &[u8]) -> Vec<u8> {
    assert!(payload.len() <= MAX_PAYLOAD);
    let mut p = vec![0u8; 28 + payload.len()];
    p[0] = 0x45;
    p[2..4].copy_from_slice(&((28 + payload.len()) as u16).to_be_bytes());
    p[6] = 0x40;
    p[8] = 64;
    p[9] = 17;
    p[12..16].copy_from_slice(&key.src.ip().octets());
    p[16..20].copy_from_slice(&key.dst.ip().octets());
    p[20..22].copy_from_slice(&key.src.port().to_be_bytes());
    p[22..24].copy_from_slice(&key.dst.port().to_be_bytes());
    p[24..26].copy_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
    p[28..].copy_from_slice(payload);
    let ip = finish(sum(&p[..20]));
    p[10..12].copy_from_slice(&ip.to_be_bytes());
    let udp = finish(sum(&p[12..20]) + 17 + (8 + payload.len()) as u32 + sum(&p[20..]));
    p[26..28].copy_from_slice(&(if udp == 0 { 65535 } else { udp }).to_be_bytes());
    p
}

pub fn socks_encode(host: &str, port: u16, payload: &[u8]) -> io::Result<Vec<u8>> {
    if payload.len() > MAX_PAYLOAD || port == 0 {
        return Err(io::Error::other("Invalid UDP size/port"));
    }
    let mut p = vec![0, 0, 0];
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        p.push(1);
        p.extend(ip.octets());
    } else {
        if host.is_empty() || host.len() > 255 || !host.is_ascii() || host.contains(':') {
            return Err(io::Error::other("Unsupported UDP target"));
        }
        p.extend([3, host.len() as u8]);
        p.extend(host.as_bytes());
    }
    p.extend(port.to_be_bytes());
    p.extend(payload);
    Ok(p)
}
pub fn socks_decode(p: &[u8]) -> Option<(String, u16, &[u8])> {
    if p.len() < 4 || p[..3] != [0, 0, 0] {
        return None;
    }
    let (host, pos) = match p[3] {
        1 if p.len() >= 10 => (Ipv4Addr::new(p[4], p[5], p[6], p[7]).to_string(), 8),
        3 if p.len() >= 5 && p[4] > 0 && p.len() >= 7 + p[4] as usize => {
            let n = p[4] as usize;
            (
                std::str::from_utf8(&p[5..5 + n]).ok()?.to_ascii_lowercase(),
                5 + n,
            )
        }
        _ => return None,
    };
    let port = u16::from_be_bytes([p[pos], p[pos + 1]]);
    let payload = &p[pos + 2..];
    if port == 0 || payload.len() > MAX_PAYLOAD {
        return None;
    }
    Some((host, port, payload))
}

async fn associate(proxy: SocketAddr) -> io::Result<(UdpSocket, TcpStream)> {
    if !proxy.is_ipv4() || !proxy.ip().is_loopback() {
        return Err(io::Error::other("SOCKS UDP requires a loopback IPv4 proxy"));
    }
    let tcp = TcpSocket::new_v4()?;
    tcp.bind("127.0.0.2:0".parse().unwrap())?;
    let mut control = tcp.connect(proxy).await?;
    let udp = UdpSocket::bind("127.0.0.2:0").await?;
    control.write_all(&[5, 1, 0]).await?;
    let mut auth = [0u8; 2];
    control.read_exact(&mut auth).await?;
    if auth != [5, 0] {
        return Err(io::Error::other("SOCKS UDP authentication rejected"));
    }
    let endpoint = udp.local_addr()?;
    let mut request = vec![5, 3, 0, 1, 127, 0, 0, 2];
    request.extend(endpoint.port().to_be_bytes());
    control.write_all(&request).await?;
    let mut reply = [0u8; 10];
    control.read_exact(&mut reply).await?;
    if reply[..4] != [5, 0, 0, 1] {
        return Err(io::Error::other("SOCKS UDP association rejected"));
    }
    let ip = Ipv4Addr::new(reply[4], reply[5], reply[6], reply[7]);
    let port = u16::from_be_bytes([reply[8], reply[9]]);
    if !ip.is_loopback() || port == 0 {
        return Err(io::Error::other("Invalid SOCKS UDP relay"));
    }
    udp.connect((ip, port)).await?;
    Ok((udp, control))
}

struct Flow {
    generation: u64,
    tx: mpsc::Sender<Vec<u8>>,
}
pub struct UdpManager {
    flows: HashMap<FlowKey, Flow>,
    tasks: JoinSet<(FlowKey, u64)>,
    generation: u64,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    router: Arc<RoutePolicy>,
    proxy: SocketAddr,
    direct: Arc<DirectEgress>,
    replies: mpsc::Sender<Vec<u8>>,
    pub responses: mpsc::Receiver<Vec<u8>>,
}
impl UdpManager {
    pub fn new(
        fake_dns: Arc<Mutex<FakeDnsEngine>>,
        router: Arc<RoutePolicy>,
        proxy: SocketAddr,
        direct: Arc<DirectEgress>,
    ) -> Self {
        let (replies, responses) = mpsc::channel(256);
        Self {
            flows: HashMap::new(),
            tasks: JoinSet::new(),
            generation: 0,
            fake_dns,
            router,
            proxy,
            direct,
            replies,
            responses,
        }
    }
    pub fn reap(&mut self) {
        while let Some(result) = self.tasks.try_join_next() {
            if let Ok((key, generation)) = result {
                if self
                    .flows
                    .get(&key)
                    .is_some_and(|flow| flow.generation == generation)
                {
                    self.flows.remove(&key);
                }
            }
        }
        self.flows.retain(|_, flow| !flow.tx.is_closed());
    }
    pub async fn handle(&mut self, bytes: &[u8]) {
        self.reap();
        let Some(datagram) = parse(bytes) else {
            tracing::debug!("UDP drop: invalid IP datagram");
            return;
        };
        let key = datagram.key;
        let mut payload = datagram.payload;
        if let Some(flow) = self.flows.get(&key) {
            match flow.tx.try_send(payload) {
                Ok(()) | Err(mpsc::error::TrySendError::Full(_)) => return,
                Err(mpsc::error::TrySendError::Closed(data)) => {
                    self.flows.remove(&key);
                    payload = data;
                }
            }
        }
        if self.tasks.len() >= MAX_FLOWS {
            tracing::debug!("UDP drop: flow limit");
            return;
        }
        let dns = self.fake_dns.lock().await;
        let domain = dns.lookup_domain(key.dst.ip()).map(str::to_string);
        if dns.is_fake_ip(key.dst.ip()) && domain.is_none() {
            tracing::debug!("UDP drop: unmapped FakeIP");
            return;
        }
        drop(dns);
        let info = ConnectionInfo {
            src_ip: (*key.src.ip()).into(),
            dst_ip: (*key.dst.ip()).into(),
            dst_port: key.dst.port(),
            domain: domain.clone(),
            protocol: Protocol::Udp,
        };
        let action = self.router.route(&info);
        if action == RouteAction::Reject {
            return;
        }
        let target = domain.unwrap_or_else(|| key.dst.ip().to_string());
        let (tx, rx) = mpsc::channel(64);
        let _ = tx.try_send(payload);
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        self.flows.insert(key, Flow { generation, tx });
        let proxy = self.proxy;
        let direct = self.direct.clone();
        let replies = self.replies.clone();
        self.tasks.spawn(async move {
            if let Err(error) = run_flow(key, target, action, proxy, direct, rx, replies).await {
                tracing::debug!("UDP flow {key:?} closed: {error}");
            }
            (key, generation)
        });
    }
}

async fn run_flow(
    key: FlowKey,
    target: String,
    action: RouteAction,
    proxy: SocketAddr,
    direct: Arc<DirectEgress>,
    mut rx: mpsc::Receiver<Vec<u8>>,
    replies: mpsc::Sender<Vec<u8>>,
) -> io::Result<()> {
    let (socket, mut control) = tokio::time::timeout(Duration::from_secs(5), async {
        match action {
            RouteAction::Proxy => associate(proxy)
                .await
                .map(|(socket, control)| (socket, Some(control))),
            RouteAction::Direct => direct
                .connect_udp(&target, key.dst.port())
                .await
                .map(|socket| (socket, None)),
            RouteAction::Reject => unreachable!(),
        }
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "UDP setup timeout"))??;
    let mut buffer = vec![0u8; 65535];
    let mut deadline = tokio::time::Instant::now() + IDLE;
    loop {
        tokio::select! {
            payload=rx.recv()=> {
                let Some(payload)=payload else {return Ok(());};
                if control.is_some() { socket.send(&socks_encode(&target,key.dst.port(),&payload)?).await?; }
                else { socket.send(&payload).await?; }
                deadline=tokio::time::Instant::now()+IDLE;
            },
            result=socket.recv(&mut buffer)=> {
                let n=result?;
                let payload=if control.is_some() {
                    let Some((host,port,payload))=socks_decode(&buffer[..n]) else {continue;};
                    if host!=target.to_ascii_lowercase() || port!=key.dst.port() {continue;}
                    payload
                } else { &buffer[..n] };
                if payload.len()>MAX_PAYLOAD {continue;}
                let reply=packet(FlowKey{src:key.dst,dst:key.src},payload);
                let _=replies.try_send(reply); deadline=tokio::time::Instant::now()+IDLE;
            },
            _=async { if let Some(stream)=control.as_mut() {let mut one=[0u8;1]; let _=stream.read(&mut one).await;} else {std::future::pending::<()>().await;} }=>return Ok(()),
            _=tokio::time::sleep_until(deadline)=>return Ok(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn key() -> FlowKey {
        FlowKey {
            src: "192.168.1.10:53000".parse().unwrap(),
            dst: "198.18.0.8:443".parse().unwrap(),
        }
    }
    #[test]
    fn ip_round_trip_preserves_empty_maximum_and_fake_ip() {
        for n in [0, 1, 1472] {
            let payload = vec![n as u8; n];
            let bytes = packet(key(), &payload);
            let decoded = parse(&bytes).unwrap();
            assert_eq!(decoded.key, key());
            assert_eq!(decoded.payload, payload);
            let response = packet(
                FlowKey {
                    src: key().dst,
                    dst: key().src,
                },
                &payload,
            );
            assert_eq!(parse(&response).unwrap().key.src, key().dst);
        }
    }
    #[test]
    fn rejects_truncation_fragments_checksums_and_oversize() {
        let original = packet(key(), &[1, 2, 3]);
        for n in 0..original.len() {
            assert!(parse(&original[..n]).is_none());
        }
        for offset in [0, 2, 6, 8, 10, 12, 20, 22, 24, 26, 28] {
            let mut bad = original.clone();
            bad[offset] ^= 1;
            assert!(parse(&bad).is_none(), "offset {offset}");
        }
        let mut fragmented = original.clone();
        fragmented[6] = 0x20;
        fragmented[10] = 0;
        fragmented[11] = 0;
        let check = finish(sum(&fragmented[..20]));
        fragmented[10..12].copy_from_slice(&check.to_be_bytes());
        assert!(parse(&fragmented).is_none());
        let mut oversized = packet(key(), &vec![0; 1472]);
        oversized.push(1);
        assert!(parse(&oversized).is_none());
        let mut optional_udp_checksum = original;
        optional_udp_checksum[26..28].fill(0);
        assert!(parse(&optional_udp_checksum).is_some());
    }
    #[test]
    fn socks_codec_keeps_boundary_and_rejects_fragments_and_bad_lengths() {
        for host in ["1.2.3.4", "echo.test"] {
            for n in [0, 1, 1472] {
                let bytes = socks_encode(host, 443, &vec![42; n]).unwrap();
                let (h, p, data) = socks_decode(&bytes).unwrap();
                assert_eq!((h.as_str(), p, data.len()), (host, 443, n));
                for len in 0..bytes.len() - n {
                    assert!(socks_decode(&bytes[..len]).is_none());
                }
                let mut bad = bytes;
                bad[2] = 1;
                assert!(socks_decode(&bad).is_none());
            }
        }
        assert!(socks_encode("echo.test", 443, &vec![0; 1473]).is_err());
        assert!(socks_encode("::1", 443, &[]).is_err());
    }
    fn manager(action: &str, proxy: SocketAddr) -> UdpManager {
        let config = crate::config::RoutingConfig {
            default_action: action.into(),
            rules: vec![],
        };
        let (routes, _) = RoutePolicy::new(&config, None).unwrap();
        UdpManager::new(
            Arc::new(Mutex::new(FakeDnsEngine::new("198.18.0.0/15", 100))),
            Arc::new(routes),
            proxy,
            Arc::new(DirectEgress::test_egress()),
        )
    }
    #[tokio::test]
    async fn reject_and_expired_fake_ip_create_no_association() {
        let mut rejected = manager("reject", "127.0.0.1:1".parse().unwrap());
        let real = FlowKey {
            dst: "8.8.8.8:443".parse().unwrap(),
            ..key()
        };
        rejected.handle(&packet(real, &[1])).await;
        assert!(rejected.flows.is_empty());
        let mut missing = manager("proxy", "127.0.0.1:1".parse().unwrap());
        missing.handle(&packet(key(), &[1])).await;
        assert!(missing.flows.is_empty());
    }

    #[tokio::test]
    async fn opening_queue_is_bounded_and_cancellation_closes_control() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut manager = manager("proxy", listener.local_addr().unwrap());
        let ip = manager.fake_dns.lock().await.allocate_ip("echo.test");
        let flow = FlowKey {
            dst: SocketAddrV4::new(ip, 443),
            ..key()
        };
        manager.handle(&packet(flow, &[1])).await;
        let (mut control, _) = listener.accept().await.unwrap();
        let mut auth = [0; 3];
        control.read_exact(&mut auth).await.unwrap();
        // Hold authentication open while application data keeps arriving.
        for _ in 0..1000 {
            manager.handle(&packet(flow, &[1; 1472])).await;
        }
        assert_eq!(manager.flows.len(), 1);
        assert_eq!(manager.tasks.len(), 1);
        assert_eq!(manager.flows[&flow].tx.capacity(), 0);
        drop(manager);
        let count = tokio::time::timeout(Duration::from_secs(2), control.read(&mut [0; 1]))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(count, 0);
    }
    #[tokio::test]
    async fn fake_ip_flow_passes_socks_and_restores_original_tuple_and_closes_on_drop() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy_addr = listener.local_addr().unwrap();
        let relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let relay_addr = relay.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut control, peer) = listener.accept().await.unwrap();
            assert_eq!(peer.ip().to_string(), "127.0.0.2");
            let mut auth = [0; 3];
            control.read_exact(&mut auth).await.unwrap();
            assert_eq!(auth, [5, 1, 0]);
            control.write_all(&[5, 0]).await.unwrap();
            let mut request = [0; 10];
            control.read_exact(&mut request).await.unwrap();
            assert_eq!(&request[..8], &[5, 3, 0, 1, 127, 0, 0, 2]);
            let mut response = vec![5, 0, 0, 1, 127, 0, 0, 1];
            response.extend(relay_addr.port().to_be_bytes());
            control.write_all(&response).await.unwrap();
            let mut bytes = [0; 2048];
            for length in [0, 1, 1472] {
                let (n, peer) = relay.recv_from(&mut bytes).await.unwrap();
                let (host, port, data) = socks_decode(&bytes[..n]).unwrap();
                assert_eq!(
                    (host.as_str(), port, data.len()),
                    ("echo.test", 443, length)
                );
                relay.send_to(&bytes[..n], peer).await.unwrap();
            }
            assert_eq!(control.read(&mut [0; 1]).await.unwrap(), 0);
        });
        let mut manager = manager("proxy", proxy_addr);
        let fake_ip = manager.fake_dns.lock().await.allocate_ip("echo.test");
        let flow = FlowKey {
            dst: SocketAddrV4::new(fake_ip, 443),
            ..key()
        };
        for length in [0, 1, 1472] {
            let data = vec![length as u8; length];
            manager.handle(&packet(flow, &data)).await;
            let response = tokio::time::timeout(Duration::from_secs(2), manager.responses.recv())
                .await
                .unwrap()
                .unwrap();
            let reply = parse(&response).unwrap();
            assert_eq!(
                reply.key,
                FlowKey {
                    src: flow.dst,
                    dst: flow.src
                }
            );
            assert_eq!(reply.payload, data);
        }
        drop(manager);
        tokio::time::timeout(Duration::from_secs(2), server)
            .await
            .unwrap()
            .unwrap();
    }
}
