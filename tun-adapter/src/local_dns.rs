//! Windows loopback DNS proxy. System DNS points here while TUN is active.

use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use hickory_proto::op::{Message, ResponseCode};
use hickory_proto::serialize::binary::BinDecodable;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::Mutex;

use crate::config::IntranetDnsConfig;
use crate::fake_dns::FakeDnsEngine;

const QUERY_TIMEOUT: Duration = Duration::from_secs(3);
const MAX_DNS_MESSAGE: usize = 4096;

pub struct LocalDnsServer {
    udp: Arc<UdpSocket>,
    tcp: TcpListener,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    intranet_servers: Vec<SocketAddr>,
    intranet_domains: Vec<String>,
}

impl LocalDnsServer {
    /// Bind both transports before changing any system DNS settings.
    pub async fn bind(
        fake_dns: Arc<Mutex<FakeDnsEngine>>,
        config: &IntranetDnsConfig,
    ) -> std::io::Result<Self> {
        let addr = SocketAddr::from(([127, 0, 0, 1], 53));
        let udp = Arc::new(UdpSocket::bind(addr).await?);
        let tcp = TcpListener::bind(addr).await?;
        Ok(Self {
            udp,
            tcp,
            fake_dns,
            intranet_servers: config
                .servers
                .iter()
                .filter_map(|s| {
                    s.parse::<Ipv4Addr>()
                        .ok()
                        .map(|ip| SocketAddr::from((ip, 53)))
                })
                .collect(),
            intranet_domains: config
                .domains
                .iter()
                .map(|s| s.trim_matches('.').to_lowercase())
                .collect(),
        })
    }

    pub async fn run(self) {
        let Self {
            udp,
            tcp,
            fake_dns,
            intranet_servers,
            intranet_domains,
        } = self;
        let mut packet = vec![0u8; MAX_DNS_MESSAGE];
        let concurrency = Arc::new(tokio::sync::Semaphore::new(128));
        loop {
            tokio::select! {
                received = udp.recv_from(&mut packet) => {
                    match received {
                        Ok((len, peer)) => {
                            let query = packet[..len].to_vec();
                            let engine = fake_dns.clone();
                            let servers = intranet_servers.clone();
                            let domains = intranet_domains.clone();
                            let socket = udp.clone();
                            let permit = match concurrency.clone().try_acquire_owned() {
                                Ok(p) => p,
                                Err(_) => continue,
                            };
                            tokio::spawn(async move {
                                let _permit = permit;
                                if let Some(response) = process_query(&query, &engine, &servers, &domains).await {
                                    let _ = socket.send_to(&response, peer).await;
                                }
                            });
                        }
                        Err(e) => tracing::error!("Local DNS UDP receive failed: {e}"),
                    }
                }
                accepted = tcp.accept() => {
                    match accepted {
                        Ok((stream, _)) => {
                            let engine = fake_dns.clone();
                            let servers = intranet_servers.clone();
                            let domains = intranet_domains.clone();
                            let permit = match concurrency.clone().try_acquire_owned() {
                                Ok(p) => p,
                                Err(_) => continue,
                            };
                            tokio::spawn(async move {
                                let _permit = permit;
                                if let Err(e) = handle_tcp(stream, engine, servers, domains).await {
                                    tracing::debug!("Local DNS TCP client closed: {e}");
                                }
                            });
                        }
                        Err(e) => tracing::error!("Local DNS TCP accept failed: {e}"),
                    }
                }
            }
        }
    }
}

async fn handle_tcp(
    mut stream: TcpStream,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    servers: Vec<SocketAddr>,
    domains: Vec<String>,
) -> std::io::Result<()> {
    loop {
        let mut length = [0u8; 2];
        match stream.read_exact(&mut length).await {
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(()),
            Err(e) => return Err(e),
        }
        let len = u16::from_be_bytes(length) as usize;
        if len < 12 || len > MAX_DNS_MESSAGE {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "invalid DNS TCP frame length",
            ));
        }
        let mut query = vec![0; len];
        stream.read_exact(&mut query).await?;
        let response = process_query(&query, &fake_dns, &servers, &domains)
            .await
            .unwrap_or_else(|| servfail_response(&query));
        if response.len() > u16::MAX as usize {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "DNS response too large",
            ));
        }
        stream
            .write_all(&(response.len() as u16).to_be_bytes())
            .await?;
        stream.write_all(&response).await?;
    }
}

async fn process_query(
    query: &[u8],
    fake_dns: &Arc<Mutex<FakeDnsEngine>>,
    servers: &[SocketAddr],
    domains: &[String],
) -> Option<Vec<u8>> {
    let message = Message::from_bytes(query).ok()?;
    let name = message
        .queries()
        .first()?
        .name()
        .to_string()
        .trim_end_matches('.')
        .to_lowercase();
    let is_intranet = domains
        .iter()
        .any(|suffix| name == *suffix || name.ends_with(&format!(".{suffix}")));

    if is_intranet {
        if let Some(server) = servers.first() {
            if let Ok(socket) = UdpSocket::bind("0.0.0.0:0").await {
                let target = *server;
                if socket.send_to(query, target).await.is_ok() {
                    let mut response = vec![0u8; MAX_DNS_MESSAGE];
                    if let Ok(Ok((len, _))) =
                        tokio::time::timeout(QUERY_TIMEOUT, socket.recv_from(&mut response)).await
                    {
                        response.truncate(len);
                        return Some(response);
                    }
                }
            }
            tracing::warn!("Intranet DNS query timed out for {name}; returning SERVFAIL");
            return Some(servfail_response(query));
        }
    }

    fake_dns.lock().await.handle_dns_query(query).ok()
}

fn servfail_response(query: &[u8]) -> Vec<u8> {
    let Ok(request) = Message::from_bytes(query) else {
        return Vec::new();
    };
    let mut response = Message::new();
    let mut header = hickory_proto::op::Header::response_from_request(request.header());
    header.set_response_code(ResponseCode::ServFail);
    header.set_recursion_available(true);
    response.set_header(header);
    for q in request.queries() {
        response.add_query(q.clone());
    }
    response.to_vec().unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use hickory_proto::op::{MessageType, OpCode, Query};
    use hickory_proto::rr::{Name, RecordType};

    fn query(name: &str) -> Vec<u8> {
        let mut message = Message::new();
        message.set_id(0x4321);
        message.set_message_type(MessageType::Query);
        message.set_op_code(OpCode::Query);
        message.set_recursion_desired(true);
        let mut q = Query::new();
        q.set_name(Name::from_ascii(name).unwrap());
        q.set_query_type(RecordType::A);
        message.add_query(q);
        message.to_vec().unwrap()
    }

    #[tokio::test]
    async fn public_queries_are_answered_by_fakedns() {
        let engine = Arc::new(Mutex::new(FakeDnsEngine::new("198.18.0.0/15", 128)));
        let response = process_query(&query("example.com"), &engine, &[], &[])
            .await
            .unwrap();
        let message = Message::from_bytes(&response).unwrap();
        assert_eq!(message.message_type(), MessageType::Response);
        assert_eq!(message.answers().len(), 1);
        assert_eq!(
            message.answers()[0].record_type(),
            hickory_proto::rr::RecordType::A
        );
    }

    #[tokio::test]
    async fn intranet_queries_are_forwarded_to_the_configured_server() {
        let upstream = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let upstream_addr = upstream.local_addr().unwrap();
        let upstream_task = tokio::spawn(async move {
            let mut buf = [0u8; 512];
            let (len, peer) = upstream.recv_from(&mut buf).await.unwrap();
            upstream.send_to(&buf[..len], peer).await.unwrap();
        });
        let request = query("service.corp.test");
        let response = process_query(
            &request,
            &Arc::new(Mutex::new(FakeDnsEngine::new("198.18.0.0/15", 128))),
            &[upstream_addr],
            &["corp.test".to_string()],
        )
        .await
        .unwrap();
        assert_eq!(response, request);
        upstream_task.await.unwrap();
    }
}
