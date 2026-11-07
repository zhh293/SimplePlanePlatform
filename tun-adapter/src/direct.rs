//! Windows direct egress: pin both DNS and TCP to the pre-TUN interface.
use hickory_proto::{
    op::{Message, MessageType, Query, ResponseCode},
    rr::{Name, RData, RecordType},
};
use std::{
    io,
    net::{Ipv4Addr, SocketAddr},
    time::Duration,
};
use tokio::net::{TcpSocket, TcpStream, UdpSocket};

#[derive(Clone)]
pub struct DirectEgress {
    index: u32,
    source: Ipv4Addr,
    dns: Vec<Ipv4Addr>,
}
impl DirectEgress {
    pub fn discover() -> io::Result<Self> {
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
            if let Ok(Ok(ips)) = result {
                return Ok(ips);
            }
        }
        Err(io::Error::other(format!("Direct DNS failed for {host}")))
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
    pub async fn connect(&self, host: &str, port: u16) -> io::Result<TcpStream> {
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
        assert!(DirectEgress::parse("3\n172.16.56.7\n202.118.66.6").is_ok());
        assert!(DirectEgress::parse("3\n198.18.0.1\n198.18.0.2").is_err());
        assert!(DirectEgress::parse("0\n172.16.56.7").is_err());
    }
    #[test]
    fn invalid_dns_is_rejected() {
        let mut q = Message::new();
        q.set_id(42);
        let mut r = q.clone();
        r.set_message_type(MessageType::Response);
        assert!(DirectEgress::answers(&q, &r).is_err());
        r.set_id(43);
        assert!(DirectEgress::answers(&q, &r).is_err());
    }
}

#[cfg(all(test, windows))]
mod windows_tests {
    use super::*;
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
        let script = format!("{mocks}\n& {{\n{}\n}} -PollMilliseconds 10", include_str!("windows/discover-egress.ps1"));
        let output = std::process::Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", &script]).output().unwrap();
        assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
        let egress = DirectEgress::parse(&String::from_utf8_lossy(&output.stdout)).unwrap();
        assert_eq!(egress.index, 3);
        assert_eq!(egress.source, "172.16.40.47".parse::<Ipv4Addr>().unwrap());
        assert_eq!(egress.dns, vec!["202.118.66.6".parse::<Ipv4Addr>().unwrap()]);
    }
    #[test]
    fn discovery_reports_a_bounded_failure_when_network_is_absent() {
        let script = format!("function Get-NetRoute {{ param($AddressFamily,$DestinationPrefix,$ErrorAction) }}\n& {{\n{}\n}} -TimeoutSeconds 1 -PollMilliseconds 10", include_str!("windows/discover-egress.ps1"));
        let output = std::process::Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", &script]).output().unwrap();
        assert!(!output.status.success());
        assert!(String::from_utf8_lossy(&output.stderr).contains("No usable physical IPv4"));
    }
    #[tokio::test]
    async fn pinned_socket_can_relay_without_a_socks_server() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let egress = DirectEgress {
            index: 1,
            source: Ipv4Addr::LOCALHOST,
            dns: vec![],
        };
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
        let e = DirectEgress {
            index: 1,
            source: Ipv4Addr::LOCALHOST,
            dns: vec![],
        };
        assert!(e.connect("198.18.0.15", 443).await.is_err());
    }
}
