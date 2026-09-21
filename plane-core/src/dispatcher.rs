//! Dispatches TUN TCP streams to protected HTTP/3/QUIC proxy streams.

use std::net::Ipv4Addr;
use std::sync::Arc;

use tokio::sync::{mpsc, Mutex};

use crate::crypto::Cipher;
use crate::error::{CoreError, Result};
use crate::net_probe::FakeDnsEngine;
use crate::outbound::{proxy_via_remote, OutboundConfig, OutboundConnection, SocketProtector};
use crate::tcp_stack::{SmolTcpStream, StreamCommand, TcpEvent};

const PROXY_READ_BUF: usize = 16 * 1024;

#[derive(Clone)]
pub struct DispatcherConfig {
    pub server_host: String,
    pub server_port: u16,
    /// HTTP/3 TLS SNI / certificate name; empty means server_host.
    pub server_name: String,
    /// Optional PEM CA used to validate the HTTP/3 server certificate.
    pub ca_pem: String,
    pub key: Vec<u8>,
    /// Kept in the JNI configuration for compatibility. HTTP/3 always uses TLS.
    pub tls: bool,
}

/// Kept independent from JNI so the dispatcher remains testable.
pub trait StatusReporter: Send + Sync {
    fn report(&self, state: &str);
}

pub async fn run_dispatcher<P, S>(
    mut event_rx: mpsc::Receiver<TcpEvent>,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    config: DispatcherConfig,
    protector: Arc<P>,
    notify_tx: mpsc::Sender<()>,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
    status: Arc<S>,
) -> Result<()>
where
    P: SocketProtector + 'static,
    S: StatusReporter + 'static,
{
    tracing::info!(
        "HTTP/3 dispatcher starting for {}:{} (sni={})",
        config.server_host,
        config.server_port,
        if config.server_name.is_empty() {
            &config.server_host
        } else {
            &config.server_name
        }
    );

    status.report("connecting");
    let mut conn = match tokio::time::timeout(
        std::time::Duration::from_secs(12),
        establish_outbound(&config, protector.as_ref()),
    )
    .await
    {
        Ok(Ok(connection)) => {
            status.report("connected");
            Some(connection)
        }
        Ok(Err(error)) => {
            tracing::error!(%error, "initial HTTP/3 proxy connection failed");
            status.report("node_down");
            return Err(error);
        }
        Err(_) => {
            let error = CoreError::Protocol("HTTP/3 QUIC handshake timed out after 12s".into());
            tracing::error!(%error, "initial HTTP/3 proxy connection timed out");
            status.report("node_down");
            return Err(error);
        }
    };

    loop {
        tokio::select! {
            changed = shutdown_rx.changed() => {
                if changed.is_err() || *shutdown_rx.borrow() {
                    return Ok(());
                }
            }
            event = event_rx.recv() => {
                let Some(TcpEvent::NewConnection { src_ip, dst_ip, dst_port, stream_tx, stream_rx }) = event else {
                    return Ok(());
                };

                let domain = {
                    let engine = fake_dns.lock().await;
                    engine.lookup_domain(&dst_ip).map(ToString::to_string)
                };
                let Some(domain) = domain else {
                    tracing::warn!("no FakeDNS mapping for {}:{}", dst_ip, dst_port);
                    continue;
                };

                if conn.is_none() {
                    status.report("connecting");
                    match establish_outbound(&config, protector.as_ref()).await {
                        Ok(connection) => {
                            conn = Some(connection);
                            status.report("connected");
                        }
                        Err(error) => {
                            tracing::error!(%error, "HTTP/3 reconnect failed");
                            status.report("node_down");
                            let _ = stream_tx.try_send(StreamCommand::Close);
                            continue;
                        }
                    }
                }

                let outbound = conn.as_mut().expect("connection was restored");
                match outbound.open_proxy_stream(&domain, dst_port).await {
                    Ok(stream) => {
                        let local = SmolTcpStream::new(stream_tx, stream_rx, notify_tx.clone());
                        tokio::spawn(async move {
                            if let Err(error) = proxy_via_remote(stream, local, PROXY_READ_BUF).await {
                                tracing::warn!(%error, "HTTP/3 proxy stream ended");
                            }
                        });
                    }
                    Err(error) => {
                        tracing::error!(%error, "failed to open HTTP/3 proxy stream");
                        status.report("node_down");
                        let _ = stream_tx.try_send(StreamCommand::Close);
                        conn = None;
                    }
                }
            }
        }
    }
}

async fn establish_outbound<P>(
    config: &DispatcherConfig,
    protector: &P,
) -> Result<OutboundConnection>
where
    P: SocketProtector,
{
    let addr = resolve_server_addr(&config.server_host, config.server_port)?;
    let cipher = Cipher::new(&config.key)?;
    let outbound_config = OutboundConfig {
        server_host: config.server_host.clone(),
        server_port: config.server_port,
        server_name: config.server_name.clone(),
        ca_pem: config.ca_pem.clone(),
        tls: true,
    };
    OutboundConnection::connect(addr, protector, cipher, outbound_config).await
}

fn resolve_server_addr(host: &str, port: u16) -> Result<std::net::SocketAddr> {
    use std::net::ToSocketAddrs;

    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        return Ok(std::net::SocketAddr::from((ip, port)));
    }
    if let Ok(ip) = host.parse::<std::net::Ipv6Addr>() {
        return Ok(std::net::SocketAddr::V6(std::net::SocketAddrV6::new(
            ip, port, 0, 0,
        )));
    }
    let mut addresses = (host, port).to_socket_addrs().map_err(|e| {
        CoreError::Io(std::io::Error::other(format!(
            "resolve proxy-remote {host}:{port} failed: {e}"
        )))
    })?;
    addresses
        .find(|addr| addr.is_ipv4())
        .or_else(|| addresses.next())
        .ok_or_else(|| CoreError::Internal(format!("proxy-remote {host}:{port} has no address")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_ip_literal() {
        let addr = resolve_server_addr("1.2.3.4", 8443).unwrap();
        assert_eq!(addr.to_string(), "1.2.3.4:8443");
    }

    #[test]
    fn resolve_ipv6_literal() {
        let addr = resolve_server_addr("::1", 8443).unwrap();
        assert!(addr.is_ipv6());
        assert_eq!(addr.port(), 8443);
    }

    #[test]
    fn resolve_localhost() {
        let addr = resolve_server_addr("localhost", 80).unwrap();
        assert_eq!(addr.port(), 80);
    }
}
