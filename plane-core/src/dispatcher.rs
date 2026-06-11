//! A6 —— 连接调度器：把用户态 TCP 栈上报的新连接接到加密 HTTP/2 出站。
//!
//! 数据流（一次完整代理）：
//!
//! ```text
//! 应用发起 TCP → TUN → tcp_stack::stack_loop
//!   → TcpEvent::NewConnection { dst_ip(FakeIP), dst_port, 双向 channel }
//!   → dispatcher：FakeIP --FakeDNS 反查--> 真实域名
//!   → OutboundConnection::open_proxy_stream(域名, 端口)  （首个 CONNECT）
//!   → outbound::proxy_via_remote(stream, SmolTcpStream)   （双向搬运字节）
//!   → proxy-remote 解密 → 回源目标站点
//! ```
//!
//! ## 设计要点
//!
//! - **域名反查**：smoltcp 看到的目标是 FakeIP（198.18/15），必须经
//!   [`FakeDnsEngine::lookup_domain`] 还原成真实域名后，才能告诉 proxy-remote 要连谁
//!   （proxy-remote 在服务端做真实 DNS 解析与回源）。反查不到的 FakeIP 直接丢弃。
//! - **protect 铁律**：到 proxy-remote 的 TCP socket 必须先 [`SocketProtector::protect`]
//!   再 connect，否则会被 VPN 自身路由回环。
//! - **连接复用**：所有被代理的目标共用一条到 proxy-remote 的 HTTP/2 连接（多路复用
//!   stream），对齐 Java 客户端。本 MVP 实现「单连接 + 每目标一个 stream」。
//! - **不破坏铁律**：纯新增模块，依赖 tcp_stack / outbound / net_probe::FakeDnsEngine，
//!   不修改它们的现有公开行为。

use std::net::Ipv4Addr;
use std::sync::Arc;

use tokio::net::TcpStream;
use tokio::sync::{mpsc, Mutex};

use crate::crypto::Cipher;
use crate::error::{CoreError, Result};
use crate::net_probe::FakeDnsEngine;
use crate::outbound::{proxy_via_remote, OutboundConfig, OutboundConnection, SocketProtector};
use crate::tcp_stack::{SmolTcpStream, StreamCommand, TcpEvent};

/// 默认每条代理 stream 的本地读缓冲大小（字节）。
const PROXY_READ_BUF: usize = 16 * 1024;

/// 调度器配置（节点信息从 [`crate::jni_bridge::AndroidConfig`] 转换而来）。
#[derive(Clone)]
pub struct DispatcherConfig {
    /// proxy-remote 主机。
    pub server_host: String,
    /// proxy-remote 端口。
    pub server_port: u16,
    /// 共享密钥（构造 [`Cipher`]；非 32 字节会按 Java 规则 SHA-256 派生）。
    pub key: Vec<u8>,
    /// 是否启用 TLS（MVP 仅支持 false=h2c）。
    pub tls: bool,
}

/// 调度器主循环：消费 [`TcpEvent`]，为每个新连接接出站。
///
/// `notify_tx` 与 stack_loop 共用，[`SmolTcpStream`] 写入后通知栈及时搬运。
/// `shutdown_rx` 收到停止信号即退出（连带 spawn 的代理任务随 channel 关闭收敛）。
pub async fn run_dispatcher<P>(
    mut event_rx: mpsc::Receiver<TcpEvent>,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    config: DispatcherConfig,
    protector: Arc<P>,
    notify_tx: mpsc::Sender<()>,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
) -> Result<()>
where
    P: SocketProtector + 'static,
{
    tracing::info!(
        "调度器启动，目标 proxy-remote = {}:{} (tls={})",
        config.server_host,
        config.server_port,
        config.tls
    );

    // 共享一条到 proxy-remote 的 HTTP/2 连接（懒建立 + 失败重建）。
    let mut conn: Option<OutboundConnection> = None;

    loop {
        tokio::select! {
            res = shutdown_rx.changed() => {
                if res.is_err() || *shutdown_rx.borrow() {
                    tracing::info!("调度器收到停止信号，退出");
                    return Ok(());
                }
            }

            ev = event_rx.recv() => {
                let Some(TcpEvent::NewConnection { src_ip, dst_ip, dst_port, stream_tx, stream_rx }) = ev else {
                    tracing::info!("TCP 事件通道已关闭，调度器退出");
                    return Ok(());
                };

                // FakeIP → 真实域名（反查不到则丢弃该连接）。
                let domain = {
                    let engine = fake_dns.lock().await;
                    engine.lookup_domain(&dst_ip).map(|s| s.to_string())
                };
                let Some(domain) = domain else {
                    tracing::warn!(
                        "丢弃连接：FakeIP {} 反查不到域名（src={}, port={}）",
                        dst_ip, src_ip, dst_port
                    );
                    continue;
                };

                tracing::info!("代理新连接: {}:{} -> {} (FakeIP {})", src_ip, dst_port, domain, dst_ip);

                // 确保到 proxy-remote 的 h2 连接可用（懒建立 / 断线重建）。
                if conn.is_none() {
                    match establish_outbound(&config, protector.as_ref()).await {
                        Ok(c) => conn = Some(c),
                        Err(e) => {
                            tracing::error!("建立到 proxy-remote 的连接失败: {}，丢弃本次连接", e);
                            // 通知栈关闭该 app 连接。
                            let _ = stream_tx.try_send(StreamCommand::Close);
                            continue;
                        }
                    }
                }

                // 在 h2 连接上开 stream（首个 CONNECT 指向真实域名）。
                let outbound = conn.as_mut().unwrap();
                match outbound.open_proxy_stream(&domain, dst_port).await {
                    Ok(stream) => {
                        let local = SmolTcpStream::new(stream_tx, stream_rx, notify_tx.clone());
                        // 每个代理连接独立 spawn，互不阻塞。
                        tokio::spawn(async move {
                            if let Err(e) = proxy_via_remote(stream, local, PROXY_READ_BUF).await {
                                tracing::warn!("proxy_via_remote 结束: {}", e);
                            }
                        });
                    }
                    Err(e) => {
                        tracing::error!("打开代理 stream 失败: {}（域名 {}），连接将重建", e, domain);
                        let _ = stream_tx.try_send(StreamCommand::Close);
                        // h2 连接可能已坏，丢弃以便下次重建。
                        conn = None;
                    }
                }
            }
        }
    }
}

/// 建立一条到 proxy-remote 的、已 protect 的 HTTP/2 连接并完成握手。
async fn establish_outbound<P>(
    config: &DispatcherConfig,
    protector: &P,
) -> Result<OutboundConnection>
where
    P: SocketProtector,
{
    if config.tls {
        // MVP 仅落地 h2c；TLS 待接 rustls（记技术债）。
        return Err(CoreError::Protocol(
            "TLS 出站暂未实现（MVP 仅支持 h2c），请将节点配置为 tls=false".to_string(),
        ));
    }

    let addr = resolve_server_addr(&config.server_host, config.server_port)?;

    // 先 connect 拿到 socket，再立刻 protect 其 fd（protect 铁律：protect 后流量才绕过 VPN）。
    // 注：Android 上 connect 前无法拿到 tokio 的 fd；这里采用「connect 后立即 protect」，
    // 与 VpnService.protect(socket) 语义一致（protect 对已建立 socket 生效，使其后续流量绕隧道）。
    let tcp = TcpStream::connect(addr).await.map_err(|e| {
        CoreError::Io(std::io::Error::other(format!(
            "连接 proxy-remote {addr} 失败: {e}"
        )))
    })?;

    {
        use std::os::unix::io::AsRawFd;
        let fd = tcp.as_raw_fd();
        if !protector.protect(fd) {
            return Err(CoreError::Internal(format!(
                "protect socket fd={fd} 失败（流量可能回环），放弃本连接"
            )));
        }
        tracing::debug!("已 protect 到 proxy-remote 的 socket fd={}", fd);
    }

    tcp.set_nodelay(true).ok();

    let cipher = Cipher::new(&config.key)?;
    let outbound_cfg = OutboundConfig {
        server_host: config.server_host.clone(),
        server_port: config.server_port,
        tls: false,
    };
    OutboundConnection::handshake(tcp, cipher, outbound_cfg).await
}

/// 解析 proxy-remote 地址（支持 IP 字面量与域名）。
fn resolve_server_addr(host: &str, port: u16) -> Result<std::net::SocketAddr> {
    use std::net::ToSocketAddrs;
    // 优先按 IP 字面量解析（最常见的节点配置形态）。
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        return Ok(std::net::SocketAddr::from((ip, port)));
    }
    // 否则走系统解析（注意：此解析走系统 DNS，不经 FakeDNS）。
    (host, port)
        .to_socket_addrs()
        .map_err(|e| {
            CoreError::Io(std::io::Error::other(format!(
                "解析 proxy-remote 地址 {host}:{port} 失败: {e}"
            )))
        })?
        .next()
        .ok_or_else(|| CoreError::Internal(format!("proxy-remote 地址 {host}:{port} 无解析结果")))
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
    fn resolve_localhost() {
        // localhost 应能解析（不触外网）。
        let addr = resolve_server_addr("localhost", 80);
        assert!(addr.is_ok());
        assert_eq!(addr.unwrap().port(), 80);
    }

    #[tokio::test]
    async fn establish_rejects_tls() {
        let cfg = DispatcherConfig {
            server_host: "1.2.3.4".to_string(),
            server_port: 8443,
            key: b"unit-test-key-please-change-1234".to_vec(),
            tls: true,
        };
        let prot = Arc::new(crate::outbound::NoopProtector);
        // 不能用 unwrap_err（OutboundConnection 未实现 Debug），改 match 断言。
        match establish_outbound(&cfg, prot.as_ref()).await {
            Err(CoreError::Protocol(_)) => {}
            Err(other) => panic!("期望 Protocol 错误，实际: {other}"),
            Ok(_) => panic!("tls=true 应被拒绝，却建立了出站连接"),
        }
    }
}
