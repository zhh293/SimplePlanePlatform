//! Dispatches TUN TCP streams to protected HTTP/3/QUIC proxy streams.

use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use tokio::sync::{mpsc, Mutex};

use crate::crypto::Cipher;
use crate::error::{CoreError, Result};
use crate::net_probe::FakeDnsEngine;
use crate::outbound::{proxy_via_remote, OutboundConfig, OutboundConnection, SocketProtector};
use crate::tcp_stack::{SmolTcpStream, StreamCommand, TcpEvent};

const PROXY_READ_BUF: usize = 16 * 1024;
const RECONNECT_RETRY_DELAY: Duration = Duration::from_secs(2);
const CONNECTION_HEALTH_POLL: Duration = Duration::from_millis(500);

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
    let mut conn = match establish_outbound_with_timeout(&config, protector.as_ref()).await {
        Ok(connection) => {
            status.report("connected");
            Some(connection)
        }
        Err(error) => {
            tracing::error!(%error, "initial HTTP/3 proxy connection failed");
            status.report("node_down");
            return Err(error);
        }
    };

    let mut health_tick = tokio::time::interval(CONNECTION_HEALTH_POLL);
    let mut next_reconnect = Instant::now() + RECONNECT_RETRY_DELAY;

    loop {
        tokio::select! {
            changed = shutdown_rx.changed() => {
                if changed.is_err() || *shutdown_rx.borrow() {
                    return Ok(());
                }
            }
            _ = health_tick.tick() => {
                if conn.as_ref().is_some_and(|connection| !connection.is_alive()) {
                    tracing::warn!("HTTP/3 connection lost; closing all proxy streams and scheduling reconnect");
                    conn = None;
                    status.report("connecting");
                    next_reconnect = Instant::now();
                }

                if conn.is_none() && Instant::now() >= next_reconnect {
                    next_reconnect = Instant::now() + RECONNECT_RETRY_DELAY;
                    match establish_outbound_with_timeout(&config, protector.as_ref()).await {
                        Ok(connection) => {
                            tracing::info!("HTTP/3 connection rebuilt");
                            conn = Some(connection);
                            status.report("connected");
                        }
                        Err(error) => {
                            tracing::warn!(%error, "HTTP/3 automatic reconnect failed; will retry");
                            status.report("connecting");
                        }
                    }
                }
            }
            event = event_rx.recv() => {
                let Some(TcpEvent::NewConnection { src_ip: _, dst_ip, dst_port, stream_tx, stream_rx }) = event else {
                    return Ok(());
                };

                // FakeDNS is the preferred path because it preserves the original
                // hostname.  Some Android apps/browsers still open TCP directly to
                // a cached, DoH-resolved, or hard-coded IPv4 address.  Dropping
                // those connections makes the VPN look connected while every page
                // that bypasses the system DNS hangs forever.  The TCP payload
                // still carries HTTP Host/TLS SNI, so forwarding the literal IPv4
                // address is a valid and useful fallback.
                let target_host = {
                    let engine = fake_dns.lock().await;
                    engine
                        .lookup_domain(&dst_ip)
                        .map(ToString::to_string)
                        .unwrap_or_else(|| {
                            tracing::info!(
                                "no FakeDNS mapping for {}; forwarding direct IPv4 target",
                                dst_ip
                            );
                            dst_ip.to_string()
                        })
                };

                if conn.is_none() {
                    status.report("connecting");
                    match establish_outbound_with_timeout(&config, protector.as_ref()).await {
                        Ok(connection) => {
                            conn = Some(connection);
                            status.report("connected");
                            next_reconnect = Instant::now() + RECONNECT_RETRY_DELAY;
                        }
                        Err(error) => {
                            tracing::warn!(%error, "HTTP/3 reconnect failed; will retry");
                            status.report("connecting");
                            next_reconnect = Instant::now() + RECONNECT_RETRY_DELAY;
                            let _ = stream_tx.try_send(StreamCommand::Close);
                            continue;
                        }
                    }
                }

                let Some(outbound) = conn.as_mut() else {
                    tracing::warn!("HTTP/3 connection unavailable after reconnect attempt");
                    let _ = stream_tx.try_send(StreamCommand::Close);
                    continue;
                };
                tracing::debug!(
                    target = %target_host,
                    port = dst_port,
                    "opening HTTP/3 proxy stream"
                );
                match outbound.open_proxy_stream(&target_host, dst_port).await {
                    Ok(stream) => {
                        let local = SmolTcpStream::new(stream_tx, stream_rx, notify_tx.clone());
                        tokio::spawn(async move {
                            if let Err(error) = proxy_via_remote(stream, local, PROXY_READ_BUF).await {
                                tracing::warn!(%error, "HTTP/3 proxy stream ended");
                            }
                        });
                    }
                    Err(error) => {
                        tracing::warn!(
                            %error,
                            target = %target_host,
                            port = dst_port,
                            "failed to open HTTP/3 proxy stream"
                        );
                        let _ = stream_tx.try_send(StreamCommand::Close);
                        // A target refusal (for example a blocked or offline
                        // website) is a stream-local error.  Keep the shared
                        // HTTP/3 connection alive; only reconnect when QUIC
                        // itself has gone away.
                        if !outbound.is_alive() {
                            conn = None;
                        }
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

async fn establish_outbound_with_timeout<P>(
    config: &DispatcherConfig,
    protector: &P,
) -> Result<OutboundConnection>
where
    P: SocketProtector,
{
    match tokio::time::timeout(
        Duration::from_secs(12),
        establish_outbound(config, protector),
    )
    .await
    {
        Ok(result) => result,
        Err(_) => Err(CoreError::Protocol(
            "HTTP/3 QUIC handshake timed out after 12s".into(),
        )),
    }
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

    #[test]
    fn direct_ipv4_target_is_preserved_when_fake_dns_has_no_mapping() {
        let engine = FakeDnsEngine::new("198.18.0.0/15", 16);
        let ip = Ipv4Addr::new(120, 53, 64, 82);
        let target = engine
            .lookup_domain(&ip)
            .map(ToString::to_string)
            .unwrap_or_else(|| ip.to_string());
        assert_eq!(target, "120.53.64.82");
    }
}
