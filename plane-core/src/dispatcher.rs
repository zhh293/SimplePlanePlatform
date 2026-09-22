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
const TARGET_RECOVERY_TIMEOUT: Duration = Duration::from_millis(500);
const TARGET_RECOVERY_MAX_BYTES: usize = 32 * 1024;

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
                // hostname. A cached FakeIP can outlive the native process after a
                // VPN restart, though, so recover the hostname from TLS SNI or an
                // HTTP Host header before opening the remote stream. Never forward
                // an unknown 198.18/15 address as if it were a real public IP.
                let (mapped_host, is_fake_ip) = {
                    let mut engine = fake_dns.lock().await;
                    (engine.lookup_domain(&dst_ip), engine.is_fake_ip(&dst_ip))
                };
                let mut prefetched_payload = Vec::new();
                let target_host = if let Some(host) = mapped_host {
                    host
                } else if is_fake_ip {
                    match recover_target_from_initial_payload(&mut stream_rx).await {
                        Some((host, payload)) => {
                            tracing::info!(
                                fake_ip = %dst_ip,
                                recovered_host = %host,
                                "recovered target from initial application payload"
                            );
                            prefetched_payload = payload;
                            host
                        }
                        None => {
                            tracing::warn!(
                                fake_ip = %dst_ip,
                                "dropping TCP stream with no FakeDNS mapping or recoverable host"
                            );
                            let _ = stream_tx.try_send(StreamCommand::Close);
                            continue;
                        }
                    }
                } else {
                    tracing::debug!(
                        target = %dst_ip,
                        "no FakeDNS mapping; forwarding direct IPv4 target"
                    );
                    dst_ip.to_string()
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
                        let local = SmolTcpStream::new_with_initial(
                            stream_tx,
                            stream_rx,
                            notify_tx.clone(),
                            prefetched_payload,
                        );
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

async fn recover_target_from_initial_payload(
    stream_rx: &mut mpsc::Receiver<StreamCommand>,
) -> Option<(String, Vec<u8>)> {
    let deadline = Instant::now() + TARGET_RECOVERY_TIMEOUT;
    let mut payload = Vec::new();

    loop {
        if let Some(host) = extract_target_host(&payload) {
            return Some((host, payload));
        }
        if payload.len() >= TARGET_RECOVERY_MAX_BYTES {
            return None;
        }

        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return None;
        }
        match tokio::time::timeout(remaining, stream_rx.recv())
            .await
            .ok()?
        {
            Some(StreamCommand::Data(data)) => payload.extend_from_slice(&data),
            Some(StreamCommand::Close) | None => return None,
        }
    }
}

fn extract_target_host(payload: &[u8]) -> Option<String> {
    extract_tls_sni(payload).or_else(|| extract_http_host(payload))
}

fn extract_http_host(payload: &[u8]) -> Option<String> {
    let header_end = payload
        .windows(4)
        .position(|window| window == b"\r\n\r\n")?;
    let headers = std::str::from_utf8(&payload[..header_end]).ok()?;
    headers.lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        if !name.trim().eq_ignore_ascii_case("host") {
            return None;
        }
        normalize_target_host(value.trim())
    })
}

fn extract_tls_sni(payload: &[u8]) -> Option<String> {
    if payload.len() < 5 || payload[0] != 0x16 || payload[1] != 0x03 {
        return None;
    }
    let record_len = u16::from_be_bytes([payload[3], payload[4]]) as usize;
    if payload.len() < 5 + record_len {
        return None;
    }
    let handshake = &payload[5..5 + record_len];
    if handshake.len() < 4 || handshake[0] != 0x01 {
        return None;
    }
    let hello_len =
        ((handshake[1] as usize) << 16) | ((handshake[2] as usize) << 8) | handshake[3] as usize;
    if handshake.len() < 4 + hello_len {
        return None;
    }
    let hello = &handshake[4..4 + hello_len];
    let mut offset = 2 + 32;
    if hello.len() < offset + 1 {
        return None;
    }
    let session_len = hello[offset] as usize;
    offset += 1 + session_len;
    if hello.len() < offset + 2 {
        return None;
    }
    let cipher_len = u16::from_be_bytes([hello[offset], hello[offset + 1]]) as usize;
    offset += 2 + cipher_len;
    if hello.len() < offset + 1 {
        return None;
    }
    offset += 1 + hello[offset] as usize;
    if hello.len() < offset + 2 {
        return None;
    }
    let extensions_len = u16::from_be_bytes([hello[offset], hello[offset + 1]]) as usize;
    offset += 2;
    if hello.len() < offset + extensions_len {
        return None;
    }
    let extensions_end = offset + extensions_len;
    while offset + 4 <= extensions_end {
        let extension_type = u16::from_be_bytes([hello[offset], hello[offset + 1]]);
        let extension_len = u16::from_be_bytes([hello[offset + 2], hello[offset + 3]]) as usize;
        offset += 4;
        if offset + extension_len > extensions_end {
            return None;
        }
        if extension_type == 0x0000 {
            let names = &hello[offset..offset + extension_len];
            if names.len() < 5 {
                return None;
            }
            let mut name_offset = 2;
            if names.len() < name_offset + 3 || names[name_offset] != 0 {
                return None;
            }
            name_offset += 1;
            let name_len =
                u16::from_be_bytes([names[name_offset], names[name_offset + 1]]) as usize;
            name_offset += 2;
            if names.len() < name_offset + name_len {
                return None;
            }
            let name = std::str::from_utf8(&names[name_offset..name_offset + name_len]).ok()?;
            return normalize_target_host(name);
        }
        offset += extension_len;
    }
    None
}

fn normalize_target_host(raw: &str) -> Option<String> {
    let host = raw.trim().trim_end_matches('.');
    if host.is_empty()
        || host
            .bytes()
            .any(|byte| byte.is_ascii_control() || byte.is_ascii_whitespace())
    {
        return None;
    }
    if host.starts_with('[') {
        return host
            .strip_prefix('[')
            .and_then(|value| value.split_once(']'))
            .map(|(value, _)| value.to_string());
    }
    if host.matches(':').count() == 1 {
        if let Some((name, port)) = host.rsplit_once(':') {
            if port.parse::<u16>().is_ok() && !name.is_empty() {
                return Some(name.to_string());
            }
        }
    }
    Some(host.to_string())
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

    #[test]
    fn recovers_http_host_from_initial_payload() {
        let payload = b"GET / HTTP/1.1\r\nHost: example.com:443\r\n\r\n";
        assert_eq!(extract_target_host(payload).as_deref(), Some("example.com"));
    }

    #[test]
    fn unknown_fake_ip_has_no_direct_target_fallback() {
        let engine = FakeDnsEngine::new("198.18.0.0/15", 16);
        let ip = Ipv4Addr::new(198, 18, 0, 55);
        assert!(engine.is_fake_ip(&ip));
        assert_eq!(engine.lookup_domain(&ip), None);
    }
}
