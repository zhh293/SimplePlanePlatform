//! TUN 透明代理适配器
//!
//! 通过 TUN 虚拟网卡实现系统级透明代理，将所有出站流量（TCP/DNS）
//! 通过 SOCKS5 协议转发给 proxy-local。

#![allow(dead_code)]

mod config;
mod error;
mod fake_dns;
mod route_guard;
mod router;
mod socks5;
mod stack;
mod tun_device;

use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use clap::Parser;
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Mutex};

use crate::config::Config;
use crate::error::TunError;
use crate::fake_dns::FakeDnsEngine;
use crate::router::{ConnectionInfo, Protocol, RouteAction, Router};
use crate::socks5::proxy_tcp_stream;
use crate::stack::{SmolTcpStream, TcpEvent};


/// TUN 透明代理适配器 - 系统级流量劫持与转发
#[derive(Parser, Debug)]
#[command(name = "tun-adapter", version, about)]
struct Args {
    /// 配置文件路径
    #[arg(short, long, default_value = "config/tun.toml")]
    config: PathBuf,
}

/// 初始化 tracing 日志系统（输出到终端 + 日志文件）
fn init_tracing(log_config: &config::LogConfig) {
    use tracing_subscriber::{fmt, EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new(&log_config.level));

    // 终端输出层 - compact 格式方便查看
    let stdout_layer = fmt::layer()
        .compact()
        .with_target(true);

    // 文件输出层 - 仅当配置了 log.file 时才尝试写文件。
    // 注意：以 root/sudo 启动时，相对路径或已存在文件的属主可能导致创建失败；
    // 这种情况下绝不能让进程 panic 退出（否则 TUN 主功能也起不来），
    // 而应打印一条警告并降级为只输出到终端。
    let file_layer = if log_config.file.trim().is_empty() {
        None
    } else {
        match std::fs::File::create(&log_config.file) {
            Ok(f) => Some(
                fmt::layer()
                    .with_ansi(false)
                    .with_target(true)
                    .with_writer(f),
            ),
            Err(e) => {
                eprintln!(
                    "[tun-adapter] WARN: 无法创建日志文件 {:?}: {}. 将仅输出到终端。",
                    log_config.file, e
                );
                None
            }
        }
    };

    tracing_subscriber::registry()
        .with(filter)
        .with(stdout_layer)
        .with(file_layer)
        .init();
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // 0. 提升文件描述符限制（TUN 代理会产生大量并发连接）
    raise_fd_limit();

    // 1. 解析命令行参数
    let args = Args::parse();

    // 2. 加载并校验配置
    let config = Config::load(&args.config)?;

    // 3. 初始化日志
    init_tracing(&config.log);

    // 4. 打印启动信息
    tracing::info!("tun-adapter v{} starting", env!("CARGO_PKG_VERSION"));
    config.print_summary();

    // 5. 创建 TUN 设备（包括路由设置和回环防护）
    tracing::info!("Creating TUN device...");
    let tun_mgr = tun_device::TunManager::new(&config.tun, &config.bypass, &config.intranet_dns).await?;
    let (tun_reader, tun_writer, _route_guard) = tun_mgr.split();
    tracing::info!("TUN device ready (route guard active)");

    // 6. 初始化 FakeDNS
    let fake_dns = Arc::new(Mutex::new(FakeDnsEngine::new(
        &config.fakeip.range,
        config.fakeip.capacity,
    )));
    tracing::info!("FakeDNS engine initialized");

    // 7. 初始化路由引擎
    let router = Arc::new(
        Router::from_config(&config.routing)
            .map_err(|e| TunError::Route(format!("{}", e)))?,
    );
    tracing::info!("Router initialized");

    // 8. 健康状态标记
    let health_state = Arc::new(AtomicBool::new(true));
    let socks5_addr: SocketAddr = config.socks5_socket_addr();

    // 9. 启动健康检查后台任务
    let health_state_clone = health_state.clone();
    let health_interval = Duration::from_secs(config.proxy.health_check_interval);
    let health_threshold = config.proxy.health_failure_threshold;
    let health_handle = tokio::spawn(health_check_loop(
        socks5_addr,
        health_interval,
        health_threshold,
        health_state_clone,
    ));

    // 10. 创建共享的 notify channel（连接数据通知机制）
    let (notify_tx, notify_rx) = mpsc::channel::<()>(256);

    // 11. 启动用户态协议栈事件循环
    let (tcp_event_tx, tcp_event_rx) = mpsc::channel(1024);
    let fake_dns_clone = fake_dns.clone();
    let notify_tx_stack = notify_tx.clone();

    // 构建内网 DNS 配置（用于 DNS 查询真实转发判断）
    let intranet_dns_info = stack::IntranetDnsInfo {
        servers: config.intranet_dns.servers.iter()
            .filter_map(|s| s.parse::<Ipv4Addr>().ok())
            .collect(),
        domain_suffixes: config.intranet_dns.domains.iter()
            .map(|d| d.to_lowercase())
            .collect(),
    };
    tracing::info!("Intranet DNS: servers={:?}, domains={:?}", intranet_dns_info.servers, intranet_dns_info.domain_suffixes);

    let stack_handle = tokio::spawn(async move {
        stack::stack_loop(tun_reader, tun_writer, fake_dns_clone, tcp_event_tx, notify_tx_stack, notify_rx, intranet_dns_info).await
    });

    // 12. 启动连接调度器
    let fake_dns_dispatch = fake_dns.clone();
    let router_dispatch = router.clone();
    let health_dispatch = health_state.clone();
    let notify_tx_dispatch = notify_tx.clone();
    let conn_handle = tokio::spawn(connection_dispatcher(
        tcp_event_rx,
        fake_dns_dispatch,
        router_dispatch,
        socks5_addr,
        health_dispatch,
        notify_tx_dispatch,
    ));

    tracing::info!("All modules started, TUN adapter is running");
    tracing::info!("Press Ctrl+C to stop");

    // 12. 等待退出信号或任一核心任务异常
    // 同时监听 SIGTERM（来自 cleanup 中的 sudo kill）
    let mut sigterm = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("failed to register SIGTERM handler");

    tokio::select! {
        _ = tokio::signal::ctrl_c() => {
            tracing::info!("Received Ctrl+C, shutting down...");
        }
        _ = sigterm.recv() => {
            tracing::info!("Received SIGTERM, shutting down...");
        }
        res = stack_handle => {
            tracing::error!("Stack loop exited unexpectedly: {:?}", res);
        }
        res = conn_handle => {
            tracing::error!("Connection dispatcher exited unexpectedly: {:?}", res);
        }
    }

    // 13. 优雅关闭
    tracing::info!("Shutting down gracefully...");
    health_handle.abort();

    // Drop Guard 会自动恢复路由表
    tracing::info!("TUN adapter stopped");
    Ok(())
}

/// 连接调度器：消费 TCP 事件，为每个新连接 spawn 转发任务
async fn connection_dispatcher(
    mut rx: mpsc::Receiver<TcpEvent>,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    router: Arc<Router>,
    socks5_addr: SocketAddr,
    health_state: Arc<AtomicBool>,
    notify_tx: mpsc::Sender<()>,
) {
    tracing::info!("Connection dispatcher started");

    // 并发连接数限制，防止 fd 耗尽
    let semaphore = Arc::new(tokio::sync::Semaphore::new(512));

    while let Some(event) = rx.recv().await {
        match event {
            TcpEvent::NewConnection {
                src_ip,
                dst_ip,
                dst_port,
                stream_tx,
                stream_rx,
            } => {
                // 1. FakeIP 反查域名
                let domain = {
                    let dns_engine = fake_dns.lock().await;
                    dns_engine.lookup_domain(&dst_ip).map(|s| s.to_string())
                };

                // 2. 路由决策
                let info = ConnectionInfo {
                    src_ip: std::net::IpAddr::V4(src_ip),
                    dst_ip: std::net::IpAddr::V4(dst_ip),
                    dst_port,
                    domain: domain.clone(),
                    protocol: Protocol::Tcp,
                };

                let mut action = router.route(&info);

                // 3. 健康检查降级：代理不可用时所有代理流量走直连
                if action == RouteAction::Proxy && !health_state.load(Ordering::Relaxed) {
                    tracing::warn!(
                        "Degraded mode: routing {}:{} (domain={:?}) to Direct",
                        dst_ip, dst_port, domain
                    );
                    action = RouteAction::Direct;
                }

                // 4. 尝试获取并发许可（非阻塞）
                let permit = match semaphore.clone().try_acquire_owned() {
                    Ok(p) => p,
                    Err(_) => {
                        tracing::warn!("Connection limit reached, rejecting {}:{} ({})",
                            dst_ip, dst_port, domain.as_deref().unwrap_or("no-domain"));
                        drop(stream_tx);
                        drop(stream_rx);
                        continue;
                    }
                };

                // 5. 根据决策 spawn 转发任务
                let target = domain.clone().unwrap_or_else(|| dst_ip.to_string());
                tracing::info!("Dispatching: {} {}:{} ({})",
                    match action { RouteAction::Proxy => "PROXY", RouteAction::Direct => "DIRECT", RouteAction::Reject => "REJECT" },
                    dst_ip, dst_port, target);

                match action {
                    RouteAction::Proxy => {
                        let notify = notify_tx.clone();
                        tokio::spawn(async move {
                            let _permit = permit;
                            let stream = SmolTcpStream::new(stream_tx, stream_rx, notify);
                            if let Err(e) = proxy_tcp_stream(&target, dst_port, stream, socks5_addr).await {
                                tracing::warn!("Proxy connection error for {}:{}: {}", target, dst_port, e);
                            }
                        });
                    }
                    RouteAction::Direct => {
                        let target_owned = target.clone();
                        let socks5 = socks5_addr;
                        let notify = notify_tx.clone();
                        tokio::spawn(async move {
                            let _permit = permit;
                            let stream = SmolTcpStream::new(stream_tx, stream_rx, notify);
                            if let Err(e) = direct_tcp_stream(stream, &target_owned, dst_port, socks5).await {
                                tracing::warn!("Direct connection error for {}:{}: {}", target_owned, dst_port, e);
                            }
                        });
                    }
                    RouteAction::Reject => {
                        drop(permit);
                        drop(stream_tx);
                        drop(stream_rx);
                    }
                }
            }
        }
    }

    tracing::info!("Connection dispatcher stopped");
}

/// "直连" 转发：在全局 TUN 透明代理模式下，"Direct" 实际上也走 SOCKS5 代理
///
/// 原因：TUN 截获了所有出站流量（0.0.0.0/1 + 128.0.0.0/1），
/// 如果在本机做 TcpStream::connect() 直连，TCP 包会再次进入 TUN 造成回环。
/// 系统 DNS 也被 FakeDNS 接管，无法在本机做真实 DNS 解析。
///
/// MVP 方案：所有流量（包括 Direct）都通过 SOCKS5 代理出去，
/// 让 proxy-remote 来完成实际的 DNS 解析和 TCP 连接。
/// 将来可以在 proxy-remote 侧实现分流策略（如国内直连、国外走二级代理等）。
async fn direct_tcp_stream(
    app_stream: SmolTcpStream,
    target_domain: &str,
    target_port: u16,
    socks5_addr: SocketAddr,
) -> Result<(), TunError> {
    tracing::debug!("Direct (via proxy): {}:{} through {}", target_domain, target_port, socks5_addr);
    proxy_tcp_stream(target_domain, target_port, app_stream, socks5_addr)
        .await
        .map_err(|e| TunError::Socks5(e))?;
    Ok(())
}

/// 健康检查循环：定期检测 proxy-local 可用性
async fn health_check_loop(
    socks5_addr: SocketAddr,
    interval: Duration,
    failure_threshold: u32,
    state: Arc<AtomicBool>,
) {
    tracing::info!(
        "Health checker started: checking {} every {:?}, threshold: {}",
        socks5_addr, interval, failure_threshold
    );

    let mut consecutive_failures: u32 = 0;

    loop {
        tokio::time::sleep(interval).await;

        match check_proxy_health(socks5_addr).await {
            Ok(()) => {
                if consecutive_failures > 0 {
                    tracing::info!("proxy-local recovered after {} failures", consecutive_failures);
                }
                consecutive_failures = 0;
                let was_degraded = !state.load(Ordering::Relaxed);
                state.store(true, Ordering::Relaxed);
                if was_degraded {
                    tracing::info!("Proxy mode restored: traffic will use SOCKS5 again");
                }
            }
            Err(e) => {
                consecutive_failures += 1;
                tracing::warn!(
                    "Health check failed ({}/{}): {}",
                    consecutive_failures, failure_threshold, e
                );
                if consecutive_failures >= failure_threshold {
                    let was_healthy = state.load(Ordering::Relaxed);
                    state.store(false, Ordering::Relaxed);
                    if was_healthy {
                        tracing::error!(
                            "Entering DEGRADED mode: all proxy traffic will be direct-connected"
                        );
                    }
                }
            }
        }
    }
}

/// 单次健康检查：尝试连接 SOCKS5 并完成认证握手
async fn check_proxy_health(socks5_addr: SocketAddr) -> Result<(), TunError> {
    let timeout = Duration::from_secs(3);
    let mut stream = tokio::time::timeout(timeout, TcpStream::connect(socks5_addr))
        .await
        .map_err(|_| TunError::Network("health check timeout".to_string()))?
        .map_err(|e| TunError::Network(format!("cannot connect to proxy: {}", e)))?;

    socks5::socks5_auth_handshake(&mut stream)
        .await
        .map_err(|e| TunError::Network(format!("SOCKS5 auth handshake failed: {}", e)))?;

    Ok(())
}

/// 提升进程的文件描述符限制
///
/// TUN 透明代理会为每个 TCP 连接打开到 SOCKS5 的连接，
/// macOS 默认 fd 限制(256)远远不够。提升到系统允许的最大值。
fn raise_fd_limit() {
    #[cfg(unix)]
    {
        use std::io;
        unsafe {
            let mut rlim: libc::rlimit = std::mem::zeroed();
            if libc::getrlimit(libc::RLIMIT_NOFILE, &mut rlim) == 0 {
                let old_soft = rlim.rlim_cur;
                // 尝试设置为硬限制（通常是 unlimited 或很大的值）
                rlim.rlim_cur = rlim.rlim_max;
                if libc::setrlimit(libc::RLIMIT_NOFILE, &rlim) == 0 {
                    eprintln!("FD limit raised: {} -> {}", old_soft, rlim.rlim_cur);
                } else {
                    // 硬限制不允许，尝试设一个合理的值
                    rlim.rlim_cur = 10240;
                    if libc::setrlimit(libc::RLIMIT_NOFILE, &rlim) == 0 {
                        eprintln!("FD limit raised: {} -> 10240", old_soft);
                    } else {
                        let err = io::Error::last_os_error();
                        eprintln!("Warning: failed to raise FD limit (current: {}): {}", old_soft, err);
                    }
                }
            }
        }
    }
}
