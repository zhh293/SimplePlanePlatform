//! Windows direct egress: pin both DNS and TCP to the pre-TUN interface.
use hickory_proto::{
    op::{Message, MessageType, Query, ResponseCode},
    rr::{Name, RData, RecordType},
};
use std::{
    io,
    net::{Ipv4Addr, SocketAddr},
    sync::{Arc, RwLock},
    time::Duration,
};
use tokio::net::{TcpSocket, TcpStream, UdpSocket};

#[derive(Clone)]
pub struct DirectEgress {
    current: Arc<RwLock<EgressSnapshot>>,
    last_refresh: Arc<tokio::sync::Mutex<Option<std::time::Instant>>>,
}

#[derive(Clone, PartialEq, Eq)]
struct EgressSnapshot {
    index: u32,
    source: Ipv4Addr,
    dns: Vec<Ipv4Addr>,
}
impl DirectEgress {
    pub fn discover() -> io::Result<Self> {
        Ok(Self::from_snapshot(EgressSnapshot::discover()?))
    }
    fn from_snapshot(snapshot: EgressSnapshot) -> Self {
        Self {
            current: Arc::new(RwLock::new(snapshot)),
            last_refresh: Arc::new(tokio::sync::Mutex::new(None)),
        }
    }
    pub async fn connect(&self, host: &str, port: u16) -> io::Result<TcpStream> {
        self.connect_with(host, port, EgressSnapshot::discover)
            .await
    }
    async fn connect_with<F>(&self, host: &str, port: u16, discover: F) -> io::Result<TcpStream>
    where
        F: FnOnce() -> io::Result<EgressSnapshot> + Send + 'static,
    {
        let snapshot = self.current.read().unwrap().clone();
        match snapshot.connect(host, port).await {
            Ok(stream) => Ok(stream),
            Err(error) => {
                // Only network-state failures trigger OS discovery. NXDOMAIN,
                // invalid DNS answers and unreachable individual sites do not.
                if !matches!(
                    error.kind(),
                    io::ErrorKind::AddrNotAvailable
                        | io::ErrorKind::NetworkUnreachable
                        | io::ErrorKind::NotConnected
                        | io::ErrorKind::HostUnreachable
                ) {
                    return Err(error);
                }
                match self.refresh_from(&snapshot, discover).await {
                    Ok(updated) if updated != snapshot => updated.connect(host, port).await,
                    Ok(_) => Err(error),
                    Err(refresh) => Err(io::Error::new(
                        error.kind(),
                        format!("{error}; egress refresh failed: {refresh}"),
                    )),
                }
            }
        }
    }
    async fn refresh_from<F>(
        &self,
        previous: &EgressSnapshot,
        discover: F,
    ) -> io::Result<EgressSnapshot>
    where
        F: FnOnce() -> io::Result<EgressSnapshot> + Send + 'static,
    {
        let mut last = self.last_refresh.lock().await;
        let current = self.current.read().unwrap().clone();
        if &current != previous || last.is_some_and(|at| at.elapsed() < Duration::from_secs(2)) {
            return Ok(current);
        }
        *last = Some(std::time::Instant::now());
        let updated = tokio::task::spawn_blocking(discover)
            .await
            .map_err(io::Error::other)??;
        tracing::info!(
            "Direct egress refreshed: interface {} source {} -> interface {} source {}",
            current.index,
            current.source,
            updated.index,
            updated.source
        );
        *self.current.write().unwrap() = updated.clone();
        Ok(updated)
    }
}

impl EgressSnapshot {
    fn discover() -> io::Result<Self> {
        #[cfg(windows)]
        {
            let script = include_str!("windows/discover-egress.ps1");
            let out = std::process::Command::new("powershell")
                .args(["-NoProfile", "-NonInteractive", "-Command", script])
                .output()?;
            if !out.status.success() {
                return Err(io::Error::other(
                    String::from_utf8_lossy(&out.stderr).to_string(),
                ));
            }
            Self::parse(&String::from_utf8_lossy(&out.stdout))
        }
        #[cfg(not(windows))]
        {
            Ok(Self {
                index: 0,
                source: Ipv4Addr::UNSPECIFIED,
                dns: Vec::new(),
            })
        }
    }
    fn parse(text: &str) -> io::Result<Self> {
        let mut lines = text.lines().map(str::trim).filter(|s| !s.is_empty());
        let index = lines
            .next()
            .and_then(|s| s.parse::<u32>().ok())
            .filter(|i| *i > 0)
            .ok_or_else(|| io::Error::other("Missing egress index"))?;
        let source = lines
            .next()
            .and_then(|s| s.parse::<Ipv4Addr>().ok())
            .ok_or_else(|| io::Error::other("Missing egress IPv4"))?;
        if (source.octets()[0] == 198 && (source.octets()[1] == 18 || source.octets()[1] == 19))
            || source.is_unspecified()
            || source.is_loopback()
            || source.is_link_local()
        {
            return Err(io::Error::other("Invalid physical interface"));
        }
        let dns: Vec<Ipv4Addr> = lines
            .filter_map(|s| s.parse().ok())
            .filter(|ip: &Ipv4Addr| {
                !(ip.octets()[0] == 198 && (ip.octets()[1] == 18 || ip.octets()[1] == 19))
            })
            .collect();
        if dns.is_empty() {
            return Err(io::Error::other("No physical DNS servers"));
        }
        Ok(Self { index, source, dns })
    }
    async fn resolve(&self, host: &str) -> io::Result<Vec<Ipv4Addr>> {
        if let Ok(ip) = host.parse::<Ipv4Addr>() {
            if ip.octets()[0] == 198 && (ip.octets()[1] == 18 || ip.octets()[1] == 19) {
                return Err(io::Error::other("Unmapped FakeIP cannot be sent directly"));
            }
            return Ok(vec![ip]);
        }
        let name = Name::from_ascii(host).map_err(io::Error::other)?;
        static ID: std::sync::atomic::AtomicU16 = std::sync::atomic::AtomicU16::new(1);
        let mut query = Message::new();
        query
            .set_id(ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed))
            .set_recursion_desired(true)
            .add_query(Query::query(name, RecordType::A));
        let bytes = query.to_vec().map_err(io::Error::other)?;
        let mut failure = io::Error::other("No physical DNS servers");
        for server in &self.dns {
            let result = tokio::time::timeout(Duration::from_secs(3), async {
                let socket = UdpSocket::bind((self.source, 0)).await?;
                self.pin(&socket)?;
                socket.connect((*server, 53)).await?;
                socket.send(&bytes).await?;
                let mut buf = [0u8; 4096];
                let n = socket.recv(&mut buf).await?;
                let reply = Message::from_vec(&buf[..n]).map_err(io::Error::other)?;
                Self::answers(&query, &reply)
            })
            .await;
            match result {
                Ok(Ok(ips)) => return Ok(ips),
                Ok(Err(error)) => failure = error,
                Err(_) => {
                    failure =
                        io::Error::new(io::ErrorKind::TimedOut, format!("DNS {server} timed out"))
                }
            }
        }
        Err(io::Error::new(
            failure.kind(),
            format!(
                "Direct DNS failed for {host} via {} (interface {}): {failure}",
                self.source, self.index
            ),
        ))
    }
    fn answers(query: &Message, reply: &Message) -> io::Result<Vec<Ipv4Addr>> {
        if reply.id() != query.id()
            || reply.queries() != query.queries()
            || reply.message_type() != MessageType::Response
            || reply.truncated()
            || reply.response_code() != ResponseCode::NoError
        {
            return Err(io::Error::other("Invalid direct DNS response"));
        }
        let ips: Vec<_> = reply
            .answers()
            .iter()
            .filter_map(|r| match r.data() {
                Some(RData::A(a)) => Some(a.0),
                _ => None,
            })
            .filter(|ip| !(ip.octets()[0] == 198 && (ip.octets()[1] == 18 || ip.octets()[1] == 19)))
            .collect();
        if ips.is_empty() {
            return Err(io::Error::other("No real IPv4 DNS answers"));
        }
        Ok(ips)
    }
    async fn connect(&self, host: &str, port: u16) -> io::Result<TcpStream> {
        let ips = self.resolve(host).await?;
        for ip in ips {
            let socket = TcpSocket::new_v4()?;
            self.pin(&socket)?;
            socket.bind(SocketAddr::from((self.source, 0)))?;
            if let Ok(Ok(stream)) = tokio::time::timeout(
                Duration::from_secs(5),
                socket.connect(SocketAddr::from((ip, port))),
            )
            .await
            {
                stream.set_nodelay(true)?;
                tracing::info!(
                    "DIRECT connected {host}:{port} -> {ip} via {} (interface {})",
                    self.source,
                    self.index
                );
                return Ok(stream);
            }
        }
        Err(io::Error::other(format!(
            "Direct connect failed for {host}:{port}; reconnect TUN if network changed"
        )))
    }
    #[cfg(windows)]
    fn pin<S: std::os::windows::io::AsRawSocket>(&self, socket: &S) -> io::Result<()> {
        #[link(name = "Ws2_32")]
        extern "system" {
            fn setsockopt(s: usize, level: i32, name: i32, value: *const u8, len: i32) -> i32;
            fn WSAGetLastError() -> i32;
        }
        // IP_UNICAST_IF requires the interface index in network byte order.
        let index = self.index.to_be();
        if unsafe {
            setsockopt(
                socket.as_raw_socket() as usize,
                0,
                31,
                (&index as *const u32).cast(),
                4,
            )
        } != 0
        {
            return Err(io::Error::from_raw_os_error(unsafe { WSAGetLastError() }));
        }
        Ok(())
    }
    #[cfg(not(windows))]
    fn pin<S>(&self, _: &S) -> io::Result<()> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "Direct egress is Windows-only",
        ))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn discovery_requires_real_interface_and_dns() {
        assert!(EgressSnapshot::parse("3\n172.16.56.7\n202.118.66.6").is_ok());
        assert!(EgressSnapshot::parse("3\n198.18.0.1\n198.18.0.2").is_err());
        assert!(EgressSnapshot::parse("0\n172.16.56.7").is_err());
    }
    #[test]
    fn invalid_dns_is_rejected() {
        let mut q = Message::new();
        q.set_id(42);
        let mut r = q.clone();
        r.set_message_type(MessageType::Response);
        assert!(EgressSnapshot::answers(&q, &r).is_err());
        r.set_id(43);
        assert!(EgressSnapshot::answers(&q, &r).is_err());
    }
    #[tokio::test]
    async fn concurrent_failures_share_one_refresh_and_publish_new_source() {
        let old = EgressSnapshot::parse("3\n172.16.40.47\n202.118.66.6").unwrap();
        let next = EgressSnapshot::parse("3\n172.16.47.199\n202.118.66.6").unwrap();
        let policy = DirectEgress::from_snapshot(old.clone());
        let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let loader = || {
            let calls = calls.clone();
            let next = next.clone();
            move || {
                calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                std::thread::sleep(Duration::from_millis(20));
                Ok(next)
            }
        };
        let (a, b) = tokio::join!(
            policy.refresh_from(&old, loader()),
            policy.refresh_from(&old, loader())
        );
        assert_eq!(a.unwrap().source, next.source);
        assert_eq!(b.unwrap().source, next.source);
        assert_eq!(policy.current.read().unwrap().source, next.source);
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 1);
    }
    #[tokio::test]
    async fn failed_refresh_keeps_snapshot_and_limits_retry_frequency() {
        let old = EgressSnapshot::parse("3\n172.16.40.47\n202.118.66.6").unwrap();
        let policy = DirectEgress::from_snapshot(old.clone());
        assert!(policy
            .refresh_from(&old, || Err(io::Error::other("DHCP unavailable")))
            .await
            .is_err());
        assert_eq!(policy.current.read().unwrap().source, old.source);
        let same = policy
            .refresh_from(&old, || {
                panic!("rapid failures must not rediscover repeatedly")
            })
            .await
            .unwrap();
        assert_eq!(same.source, old.source);
    }
}

#[cfg(all(test, windows))]
mod windows_tests {
    use super::*;
    #[tokio::test]
    async fn lost_source_address_refreshes_and_retries_the_same_connection() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let old = EgressSnapshot {
            index: 1,
            source: "192.0.2.123".parse().unwrap(),
            dns: vec![],
        };
        let policy = DirectEgress::from_snapshot(old);
        let updated = EgressSnapshot {
            index: 1,
            source: Ipv4Addr::LOCALHOST,
            dns: vec![],
        };
        let stream = policy
            .connect_with(
                "127.0.0.1",
                listener.local_addr().unwrap().port(),
                move || Ok(updated),
            )
            .await
            .unwrap();
        let (_, peer) = tokio::time::timeout(Duration::from_secs(2), listener.accept())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(peer.ip(), Ipv4Addr::LOCALHOST);
        assert_eq!(policy.current.read().unwrap().source, Ipv4Addr::LOCALHOST);
        drop(stream);
    }
    #[tokio::test]
    async fn dns_bind_failure_preserves_address_not_available_for_refresh() {
        let old = EgressSnapshot {
            index: 1,
            source: "192.0.2.123".parse().unwrap(),
            dns: vec![Ipv4Addr::LOCALHOST],
        };
        let error = old.resolve("fixture.invalid").await.unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::AddrNotAvailable);
        assert!(error.to_string().contains("192.0.2.123"));
    }
    #[test]
    fn discovery_recovers_dhcp_dns_while_fakedns_is_active() {
        let directory = tempfile::tempdir().unwrap();
        std::fs::write(
            directory.path().join("tun-adapter-dns-backup.conf"),
            "service:fixture\ndns:Empty\n",
        )
        .unwrap();
        let mocks = format!(
            r#"
$env:TEMP='{}'
function Get-NetRoute {{param($AddressFamily,$DestinationPrefix,$ErrorAction) [pscustomobject]@{{InterfaceIndex=3;InterfaceAlias='fixture';RouteMetric=1;InterfaceMetric=1;NextHop='172.16.40.1'}}}}
function Get-NetIPAddress {{param($InterfaceIndex,$AddressFamily,$ErrorAction) [pscustomobject]@{{IPAddress='172.16.47.199';AddressState='Preferred'}}}}
function Get-DnsClientServerAddress {{param($InterfaceIndex,$AddressFamily,$ErrorAction) [pscustomobject]@{{ServerAddresses=@('198.18.0.2')}}}}
function Get-NetAdapter {{param($ErrorAction) [pscustomobject]@{{ifIndex=3;InterfaceGuid='00000000-0000-0000-0000-000000000003'}}}}
function Get-ItemProperty {{param($LiteralPath,$ErrorAction) [pscustomobject]@{{DhcpNameServer='202.118.66.6'}}}}
"#,
            directory.path().display().to_string().replace('\'', "''")
        );
        let script = format!(
            "{mocks}\n& {{\n{}\n}} -TimeoutSeconds 1 -PollMilliseconds 10",
            include_str!("windows/discover-egress.ps1")
        );
        let output = std::process::Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let snapshot = EgressSnapshot::parse(&String::from_utf8_lossy(&output.stdout)).unwrap();
        assert_eq!(
            snapshot.source,
            "172.16.47.199".parse::<Ipv4Addr>().unwrap()
        );
        assert_eq!(
            snapshot.dns,
            vec!["202.118.66.6".parse::<Ipv4Addr>().unwrap()]
        );
    }
    #[test]
    fn discovery_waits_for_dhcp_and_skips_a_stale_tun_default_route() {
        let mocks = r#"
$script:attempts=0
function Get-NetRoute {
    param($AddressFamily,$DestinationPrefix,$ErrorAction)
    $script:attempts+=1
    if ($script:attempts -eq 1) { return }
    [pscustomobject]@{InterfaceIndex=99;RouteMetric=1;InterfaceMetric=1;NextHop='198.18.255.254'}
    [pscustomobject]@{InterfaceIndex=3;RouteMetric=10;InterfaceMetric=10;NextHop='172.16.40.1'}
}
function Get-NetIPAddress {
    param($InterfaceIndex,$AddressFamily,$ErrorAction)
    $ip=if($InterfaceIndex -eq 99){'198.18.0.1'}else{'172.16.40.47'}
    [pscustomobject]@{IPAddress=$ip;AddressState='Preferred'}
}
function Get-DnsClientServerAddress {
    param($InterfaceIndex,$AddressFamily,$ErrorAction)
    [pscustomobject]@{ServerAddresses=@('198.18.0.2','202.118.66.6')}
}
"#;
        let script = format!(
            "{mocks}\n& {{\n{}\n}} -PollMilliseconds 10",
            include_str!("windows/discover-egress.ps1")
        );
        let output = std::process::Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let egress = EgressSnapshot::parse(&String::from_utf8_lossy(&output.stdout)).unwrap();
        assert_eq!(egress.index, 3);
        assert_eq!(egress.source, "172.16.40.47".parse::<Ipv4Addr>().unwrap());
        assert_eq!(
            egress.dns,
            vec!["202.118.66.6".parse::<Ipv4Addr>().unwrap()]
        );
    }
    #[test]
    fn discovery_reports_a_bounded_failure_when_network_is_absent() {
        let script = format!("function Get-NetRoute {{ param($AddressFamily,$DestinationPrefix,$ErrorAction) }}\n& {{\n{}\n}} -TimeoutSeconds 1 -PollMilliseconds 10", include_str!("windows/discover-egress.ps1"));
        let output = std::process::Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .output()
            .unwrap();
        assert!(!output.status.success());
        assert!(String::from_utf8_lossy(&output.stderr).contains("No usable physical IPv4"));
    }
    #[tokio::test]
    async fn pinned_socket_can_relay_without_a_socks_server() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let egress = DirectEgress::from_snapshot(EgressSnapshot {
            index: 1,
            source: Ipv4Addr::LOCALHOST,
            dns: vec![],
        });
        let stream = egress.connect("127.0.0.1", port).await.unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        let (mut client, app) = tokio::io::duplex(64);
        let relay = tokio::spawn(crate::socks5::relay_with_idle_timeout(
            app,
            stream,
            Duration::from_secs(2),
        ));
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        client.write_all(b"request").await.unwrap();
        client.shutdown().await.unwrap();
        let mut bytes = vec![];
        server.read_to_end(&mut bytes).await.unwrap();
        assert_eq!(bytes, b"request");
        server.write_all(b"response").await.unwrap();
        server.shutdown().await.unwrap();
        bytes.clear();
        client.read_to_end(&mut bytes).await.unwrap();
        assert_eq!(bytes, b"response");
        relay.await.unwrap().unwrap();
    }
    #[tokio::test]
    async fn stale_fake_ip_fails_before_connect() {
        let e = DirectEgress::from_snapshot(EgressSnapshot {
            index: 1,
            source: Ipv4Addr::LOCALHOST,
            dns: vec![],
        });
        assert!(e.connect("198.18.0.15", 443).await.is_err());
    }
}
