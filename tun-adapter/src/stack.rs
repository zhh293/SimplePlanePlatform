//! 用户态 TCP/IP 栈模块
//!
//! 将 smoltcp 接入 TUN 设备，实现原始 IP 包的接收、TCP 连接的握手与字节流提取。
//! 使用动态端口监听方式：当收到 SYN 包时动态创建对应端口的监听 socket。
//!
//! 核心架构：使用 tokio::select! 同时等待 TUN 数据和连接 channel 数据，
//! 避免单线程轮询的时延瓶颈和死锁风险。

use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{Ipv4Addr, SocketAddr};

use smoltcp::iface::{Config as SmolConfig, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::tcp::{self, Socket as TcpSocket, State as TcpState};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{
    HardwareAddress, IpAddress, IpCidr, IpListenEndpoint, IpProtocol, Ipv4Packet, TcpPacket,
    UdpPacket,
};
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, Mutex};

use std::sync::Arc;

use crate::error::TunError;
use crate::fake_dns::FakeDnsEngine;
use crate::tun_device::{TunReader, TunWriter};

/// TCP 事件：新建连接通知
#[derive(Debug)]
pub enum TcpEvent {
    /// 新的 TCP 连接建立
    NewConnection {
        /// 源 IP
        src_ip: Ipv4Addr,
        /// 目标 IP（FakeIP）
        dst_ip: Ipv4Addr,
        /// 目标端口
        dst_port: u16,
        /// 用于发送数据到应用的 channel
        stream_tx: mpsc::Sender<StreamCommand>,
        /// 用于从应用接收数据的 channel
        stream_rx: mpsc::Receiver<StreamCommand>,
    },
}

/// TCP 流命令（在 smoltcp poll loop 和转发任务之间传递数据）
#[derive(Debug)]
pub enum StreamCommand {
    /// 从应用读到的数据
    Data(Vec<u8>),
    /// 连接关闭
    Close,
}

/// 用户态协议栈主循环
///
/// 从 TUN 读取 IP 包，通过 smoltcp 处理 TCP/UDP，
/// 将新建的 TCP 连接通过事件通道上报给连接调度器。
///
/// 架构要点：
/// - TUN 读取使用批量模式，一次迭代处理尽可能多的包
/// - 使用 tokio::select! 同时等待 TUN 读取和连接 channel 通知
/// - 避免死锁：stack_loop 必须及时消费 channel 数据
/// 内网 DNS 配置（传递给协议栈用于 DNS 查询转发判断）
#[derive(Debug, Clone)]
pub struct IntranetDnsInfo {
    /// 内网 DNS 服务器 IP 集合
    pub servers: HashSet<Ipv4Addr>,
    /// 内网域名后缀列表（小写）
    pub domain_suffixes: Vec<String>,
}

pub async fn stack_loop(
    mut tun_reader: TunReader,
    mut tun_writer: TunWriter,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    tcp_event_tx: mpsc::Sender<TcpEvent>,
    notify_tx: mpsc::Sender<()>,
    mut notify_rx: mpsc::Receiver<()>,
    intranet_dns: IntranetDnsInfo,
) -> Result<(), TunError> {
    tracing::info!("User-space TCP/IP stack starting");

    // 创建 smoltcp 接口
    let mut device = VirtualDevice::new();
    let smol_config = SmolConfig::new(HardwareAddress::Ip);
    let mut iface = Interface::new(smol_config, &mut device, SmolInstant::now());

    // 配置 IP 地址
    iface.update_ip_addrs(|addrs| {
        addrs
            .push(IpCidr::new(IpAddress::v4(198, 18, 0, 1), 15))
            .ok();
    });

    // set_any_ip(true) 让 smoltcp 接受所有目标 IP 的包
    iface.set_any_ip(true);

    // 配置默认路由
    iface
        .routes_mut()
        .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(198, 18, 0, 1))
        .ok();

    let mut sockets = SocketSet::new(Vec::new());

    // 每个端口最多一个 Listen 状态的 socket（避免 smoltcp 同端口多 Listen 的路由混乱）
    let mut listening_ports: HashMap<u16, SocketHandle> = HashMap::new();
    // 所有等待 Established 的 socket（已从 Listen → SynReceived 或刚创建）
    let mut pending_handles: Vec<(SocketHandle, std::time::Instant)> = Vec::new();
    let mut active_connections: Vec<ActiveConnection> = Vec::new();
    // 已建立并交给 dispatcher 的连接四元组（防止重复通知）
    let mut accepted_connections: HashSet<(Ipv4Addr, u16, Ipv4Addr, u16)> = HashSet::new();

    let mut read_buf = vec![0u8; 2048];

    tracing::info!("Stack loop running, any_ip=true");

    // 统计计数器
    let mut pkt_count: u64 = 0;
    let mut last_stats = std::time::Instant::now();

    loop {
        // ======== 核心 select! ========
        // 同时等待 TUN 数据 / channel 通知 / 定时器
        let has_active = !active_connections.is_empty();
        let poll_interval = if has_active {
            tokio::time::Duration::from_millis(5)
        } else {
            tokio::time::Duration::from_millis(50)
        };

        tokio::select! {
            biased;  // 优先处理 TUN 读取

            read_result = tun_reader.read(&mut read_buf) => {
                match read_result {
                    Ok(n) if n > 0 => {
                        pkt_count += 1;
                        let packet_data = &read_buf[..n];

                        if pkt_count % 200 == 1 || last_stats.elapsed() > std::time::Duration::from_secs(10) {
                            tracing::info!("Stack stats: pkts={}, listening={}, pending={}, active={}, accepted={}",
                                pkt_count, listening_ports.len(), pending_handles.len(),
                                active_connections.len(), accepted_connections.len());
                            last_stats = std::time::Instant::now();
                        }

                        if pkt_count <= 5 {
                            if packet_data.len() >= 20 {
                                let version = (packet_data[0] >> 4) & 0x0F;
                                let proto = packet_data[9];
                                let src = format!("{}.{}.{}.{}", packet_data[12], packet_data[13], packet_data[14], packet_data[15]);
                                let dst = format!("{}.{}.{}.{}", packet_data[16], packet_data[17], packet_data[18], packet_data[19]);
                                let proto_name = match proto {
                                    6 => "TCP",
                                    17 => "UDP",
                                    1 => "ICMP",
                                    _ => "OTHER",
                                };
                                tracing::info!("Packet #{}: IPv{} {} {} -> {} ({} bytes)", pkt_count, version, proto_name, src, dst, n);
                            }
                        }

                        // DNS 拦截
                        if let Some(dns_response) = try_handle_dns_packet(packet_data, &fake_dns, &intranet_dns).await {
                            if let Err(e) = tun_writer.write_all(&dns_response).await {
                                tracing::warn!("Failed to write DNS response to TUN: {}", e);
                            }
                        } else {
                            // TCP 包处理：过滤掉未知连接的非 SYN 包，避免 smoltcp 回 RST
                            let should_feed = if is_tcp_packet(packet_data) {
                                if let Some(syn_info) = extract_tcp_syn_info(packet_data) {
                                    // SYN 包 — 确保目标端口有 Listen socket
                                    let (_src_ip_addr, _src_port, _dst_ip_addr, dst_port) = syn_info;

                                    let need_new = match listening_ports.get(&dst_port) {
                                        None => true,
                                        Some(&handle) => {
                                            let socket = sockets.get_mut::<TcpSocket>(handle);
                                            socket.state() != TcpState::Listen
                                        }
                                    };

                                    if need_new {
                                        let tcp_rx_buffer = tcp::SocketBuffer::new(vec![0u8; 65536]);
                                        let tcp_tx_buffer = tcp::SocketBuffer::new(vec![0u8; 65536]);
                                        let mut tcp_socket = TcpSocket::new(tcp_rx_buffer, tcp_tx_buffer);
                                        let listen_ep = IpListenEndpoint {
                                            addr: None,
                                            port: dst_port,
                                        };
                                        if tcp_socket.listen(listen_ep).is_ok() {
                                            let handle = sockets.add(tcp_socket);
                                            if let Some(old_handle) = listening_ports.insert(dst_port, handle) {
                                                pending_handles.push((old_handle, std::time::Instant::now()));
                                            }
                                            tracing::debug!("Ensured TCP listen on port {} [listening={}, pending={}]",
                                                dst_port, listening_ports.len(), pending_handles.len());
                                        }
                                    }
                                    true // SYN 包总是喂给 smoltcp
                                } else {
                                    // 非 SYN 的 TCP 包（ACK/DATA/FIN/RST）
                                    // 只有当我们有对应 socket（SynReceived 或 Established）在跟踪时才喂给 smoltcp
                                    // Listen socket 不接受非 SYN 包（会回 RST），所以不算
                                    // 丢弃未知连接的包，防止 smoltcp 回 RST 杀死启动前已有连接
                                    let tcp_tuple = extract_tcp_4tuple(packet_data);
                                    let has_socket = tcp_tuple.map_or(false, |(src_ip, src_port, dst_ip, dst_port)| {
                                        // 检查 pending_handles 中是否有匹配的 socket
                                        // （SynReceived 状态，需要 ACK 来完成握手）
                                        for &(handle, _) in pending_handles.iter() {
                                            let socket = sockets.get_mut::<TcpSocket>(handle);
                                            // 对于 SynReceived 的 socket，smoltcp 已经记录了远端地址
                                            if let (Some(local_ep), Some(remote_ep)) = (socket.local_endpoint(), socket.remote_endpoint()) {
                                                if local_ep.port == dst_port {
                                                    // 匹配本地端口就够了，因为 SynReceived 的 socket
                                                    // remote_endpoint 应该就是这个连接的
                                                    let remote_matches = match remote_ep.addr {
                                                        IpAddress::Ipv4(ip) => {
                                                            Ipv4Addr::new(ip.0[0], ip.0[1], ip.0[2], ip.0[3]) == src_ip
                                                                && remote_ep.port == src_port
                                                        }
                                                        _ => false,
                                                    };
                                                    let local_matches = match local_ep.addr {
                                                        IpAddress::Ipv4(ip) => {
                                                            Ipv4Addr::new(ip.0[0], ip.0[1], ip.0[2], ip.0[3]) == dst_ip
                                                        }
                                                        _ => false,
                                                    };
                                                    if remote_matches && local_matches {
                                                        return true;
                                                    }
                                                }
                                            }
                                        }
                                        // 检查 active_connections（四元组精确匹配）
                                        for conn in active_connections.iter() {
                                            if conn.conn_tuple == (src_ip, src_port, dst_ip, dst_port) {
                                                return true;
                                            }
                                        }
                                        false
                                    });
                                    if !has_socket && pkt_count <= 50 {
                                        tracing::debug!("Dropping non-SYN TCP (prevent RST for pre-existing conn)");
                                    }
                                    has_socket
                                }
                            } else {
                                // 非 TCP 包（ICMP 等）— 不喂给 smoltcp，直接丢弃
                                false
                            };

                            if should_feed {
                                device.rx_queue.push_back(read_buf[..n].to_vec());
                            }
                        }
                    }
                    Ok(_) => {}
                    Err(e) => {
                        tracing::error!("TUN read error: {}", e);
                        return Err(e);
                    }
                }
            }

            // channel 通知
            _ = notify_rx.recv() => {
                // drain all pending notifications
                while notify_rx.try_recv().is_ok() {}
            }

            // 定时器
            _ = tokio::time::sleep(poll_interval) => {}
        }

        // ======== 驱动 smoltcp ========
        let timestamp = SmolInstant::now();
        iface.poll(timestamp, &mut device, &mut sockets);

        // ======== 检查 listening_ports 中 socket 状态变化 ========
        // 如果某个 Listen socket 已经变成了 SynReceived/Established，把它移到 pending
        let mut ports_to_refresh: Vec<u16> = Vec::new();
        for (&port, &handle) in listening_ports.iter() {
            let socket = sockets.get_mut::<TcpSocket>(handle);
            if socket.state() != TcpState::Listen {
                ports_to_refresh.push(port);
            }
        }
        for port in ports_to_refresh {
            if let Some(handle) = listening_ports.remove(&port) {
                pending_handles.push((handle, std::time::Instant::now()));
            }
        }

        // ======== 检查 pending_handles 中是否有 Established 的连接 ========
        let mut established_handles: Vec<usize> = Vec::new();
        for (idx, &(handle, _)) in pending_handles.iter().enumerate() {
            let socket = sockets.get_mut::<TcpSocket>(handle);
            if matches!(socket.state(), TcpState::Established | TcpState::CloseWait) {
                // 从 socket 读取实际建立的四元组
                let local_ep = socket.local_endpoint();
                let remote_ep = socket.remote_endpoint();

                if let (Some(local), Some(remote)) = (local_ep, remote_ep) {
                    let src_ip = match remote.addr {
                        IpAddress::Ipv4(ip) => Ipv4Addr::new(ip.0[0], ip.0[1], ip.0[2], ip.0[3]),
                        _ => continue,
                    };
                    let dst_ip = match local.addr {
                        IpAddress::Ipv4(ip) => Ipv4Addr::new(ip.0[0], ip.0[1], ip.0[2], ip.0[3]),
                        _ => continue,
                    };
                    let src_port = remote.port;
                    let dst_port = local.port;
                    let conn_tuple = (src_ip, src_port, dst_ip, dst_port);

                    // 去重
                    if accepted_connections.contains(&conn_tuple) {
                        socket.abort();
                        sockets.remove(handle);
                        established_handles.push(idx);
                        continue;
                    }
                    accepted_connections.insert(conn_tuple);

                    tracing::info!(
                        "New TCP connection ESTABLISHED: {}:{} -> {}:{}",
                        src_ip,
                        src_port,
                        dst_ip,
                        dst_port
                    );

                    // channel 容量 512
                    let (app_tx, stack_rx) = mpsc::channel(512);
                    let (stack_tx, app_rx) = mpsc::channel(512);

                    let event = TcpEvent::NewConnection {
                        src_ip,
                        dst_ip,
                        dst_port,
                        stream_tx: stack_tx,
                        stream_rx: stack_rx,
                    };

                    if tcp_event_tx.send(event).await.is_err() {
                        tracing::error!("TCP event channel closed");
                        return Err(TunError::Stack("event channel closed".to_string()));
                    }

                    active_connections.push(ActiveConnection {
                        handle,
                        tx: Some(app_tx),
                        rx: app_rx,
                        conn_tuple,
                        notify: notify_tx.clone(),
                        pending_write: None,
                        write_closed: false,
                        closing_since: None,
                    });

                    established_handles.push(idx);
                }
            }
        }

        // 从 pending 中移除已建立的（倒序删除避免索引偏移）
        established_handles.sort_unstable();
        for &idx in established_handles.iter().rev() {
            pending_handles.swap_remove(idx);
        }

        let mut retired = retire_pending(
            &mut sockets,
            &mut pending_handles,
            std::time::Instant::now(),
        );
        active_connections.retain_mut(|conn| {
            if conn.pump(
                sockets.get_mut::<TcpSocket>(conn.handle),
                std::time::Instant::now(),
            ) {
                true
            } else {
                accepted_connections.remove(&conn.conn_tuple);
                retired.push(conn.handle);
                false
            }
        });

        // Drive newly queued data/FIN/RST before releasing socket ownership.
        iface.poll(SmolInstant::now(), &mut device, &mut sockets);
        for handle in retired {
            sockets.remove(handle);
        }

        while let Some(packet) = device.tx_queue.pop_front() {
            // 诊断：打印前几个输出包的信息
            if pkt_count <= 20 && packet.len() >= 20 {
                let proto = packet[9];
                let src = format!(
                    "{}.{}.{}.{}",
                    packet[12], packet[13], packet[14], packet[15]
                );
                let dst = format!(
                    "{}.{}.{}.{}",
                    packet[16], packet[17], packet[18], packet[19]
                );
                if proto == 6 && packet.len() >= 34 {
                    // TCP — 解析 flags
                    let ihl = (packet[0] & 0x0F) as usize * 4;
                    if packet.len() > ihl + 13 {
                        let flags = packet[ihl + 13];
                        let flag_str = format!(
                            "{}{}{}{}",
                            if flags & 0x02 != 0 { "SYN " } else { "" },
                            if flags & 0x10 != 0 { "ACK " } else { "" },
                            if flags & 0x01 != 0 { "FIN " } else { "" },
                            if flags & 0x04 != 0 { "RST " } else { "" },
                        );
                        let src_port = u16::from_be_bytes([packet[ihl], packet[ihl + 1]]);
                        let dst_port = u16::from_be_bytes([packet[ihl + 2], packet[ihl + 3]]);
                        tracing::info!(
                            "TX: TCP {} {}:{} -> {}:{} [{}] ({} bytes)",
                            flag_str,
                            src,
                            src_port,
                            dst,
                            dst_port,
                            flags,
                            packet.len()
                        );
                    }
                }
            }
            if let Err(e) = tun_writer.write_all(&packet).await {
                tracing::warn!("TUN write error: {}", e);
            }
        }

        // 清理 listening_ports 中已关闭的 socket
        listening_ports.retain(|_port, handle| {
            let socket = sockets.get_mut::<TcpSocket>(*handle);
            if socket.state() == TcpState::Closed {
                sockets.remove(*handle);
                false
            } else {
                true
            }
        });
    }
}

/// 尝试在 IP 层面处理 DNS 请求
///
/// 判断逻辑（按优先级）：
/// 1. 如果查询域名匹配内网域名后缀 → 转发给内网 DNS 服务器获取真实 IP
/// 2. 如果目标 DNS IP 是内网 DNS 服务器 → 同样转发（兼容 /etc/resolver/ 生效的情况）
/// 3. 否则 → 使用 FakeDNS 引擎分配 FakeIP
async fn try_handle_dns_packet(
    packet: &[u8],
    fake_dns: &Arc<Mutex<FakeDnsEngine>>,
    intranet_dns: &IntranetDnsInfo,
) -> Option<Vec<u8>> {
    if packet.is_empty() {
        return None;
    }

    let version = (packet[0] >> 4) & 0x0F;
    if version != 4 {
        return None;
    }

    let ipv4 = Ipv4Packet::new_checked(packet).ok()?;

    if ipv4.next_header() != IpProtocol::Udp {
        return None;
    }

    let udp_data = ipv4.payload();
    let udp = UdpPacket::new_checked(udp_data).ok()?;

    if udp.dst_port() != 53 {
        return None;
    }

    let dns_payload = udp.payload();
    if dns_payload.is_empty() {
        return None;
    }

    let src_ip = ipv4.src_addr();
    let dst_ip = ipv4.dst_addr();
    let src_port = udp.src_port();
    let dst_port = udp.dst_port();

    let query_domain = extract_dns_query_name(dns_payload).unwrap_or_else(|| "unknown".to_string());
    let domain_lower = query_domain.to_lowercase();

    // 判断是否需要转发给内网 DNS：
    // 条件 1：域名匹配内网域名后缀（如 *.sankuai.com, *.meituan.com）
    // 条件 2：目标 DNS IP 是内网 DNS 服务器（/etc/resolver/ 生效时）
    let dst_ipv4 = Ipv4Addr::new(dst_ip.0[0], dst_ip.0[1], dst_ip.0[2], dst_ip.0[3]);
    let is_intranet_domain = intranet_dns.domain_suffixes.iter().any(|suffix| {
        domain_lower.ends_with(suffix) || domain_lower.ends_with(&format!(".{}", suffix))
    });
    let is_intranet_dns_target = intranet_dns.servers.contains(&dst_ipv4);

    if is_intranet_domain || is_intranet_dns_target {
        // 选择第一个可用的内网 DNS 服务器作为转发目标
        let forward_server = if is_intranet_dns_target {
            dst_ipv4
        } else {
            // 域名匹配但目标不是内网 DNS（如发到了 198.18.0.2），用第一个内网 DNS
            match intranet_dns.servers.iter().next() {
                Some(&server) => server,
                None => {
                    tracing::warn!(
                        "Intranet domain {} matched but no DNS server configured",
                        query_domain
                    );
                    // 降级到 FakeDNS
                    return handle_with_fake_dns(
                        fake_dns,
                        dns_payload,
                        &query_domain,
                        src_ip,
                        dst_ip,
                        src_port,
                        dst_port,
                    )
                    .await;
                }
            }
        };

        tracing::info!(
            "DNS forward: {} -> real DNS {} (domain_match={}, dns_target_match={})",
            query_domain,
            forward_server,
            is_intranet_domain,
            is_intranet_dns_target
        );

        match forward_dns_to_real_server(dns_payload, forward_server).await {
            Ok(real_response) => {
                tracing::info!(
                    "Real DNS response for {}: {} bytes from {}",
                    query_domain,
                    real_response.len(),
                    forward_server
                );
                let response_packet =
                    build_udp_response(dst_ip.0, src_ip.0, dst_port, src_port, &real_response);
                return Some(response_packet);
            }
            Err(e) => {
                tracing::warn!(
                    "DNS forward failed for {} -> {}: {}. Falling back to FakeDNS.",
                    query_domain,
                    forward_server,
                    e
                );
                // 降级到 FakeDNS
            }
        }
    }

    // FakeDNS 处理（默认路径，或内网 DNS 转发失败时的降级）
    handle_with_fake_dns(
        fake_dns,
        dns_payload,
        &query_domain,
        src_ip,
        dst_ip,
        src_port,
        dst_port,
    )
    .await
}

/// FakeDNS 处理辅助函数
async fn handle_with_fake_dns(
    fake_dns: &Arc<Mutex<FakeDnsEngine>>,
    dns_payload: &[u8],
    query_domain: &str,
    src_ip: smoltcp::wire::Ipv4Address,
    dst_ip: smoltcp::wire::Ipv4Address,
    src_port: u16,
    dst_port: u16,
) -> Option<Vec<u8>> {
    tracing::info!(
        "DNS query intercepted: {} (from {} -> DNS server {})",
        query_domain,
        src_ip,
        dst_ip
    );

    let mut dns_engine = fake_dns.lock().await;
    let dns_response = match dns_engine.handle_dns_query(dns_payload) {
        Ok(resp) => resp,
        Err(e) => {
            tracing::warn!("FakeDNS error for {}: {}", query_domain, e);
            return None;
        }
    };

    tracing::info!(
        "DNS response sent: {} -> {}:{} ({} bytes)",
        dst_ip,
        src_ip,
        src_port,
        dns_response.len()
    );

    let response_packet = build_udp_response(dst_ip.0, src_ip.0, dst_port, src_port, &dns_response);

    Some(response_packet)
}

/// 将 DNS 查询真实转发给指定的 DNS 服务器（通过系统 UDP socket）
///
/// 通过 tokio UdpSocket 发送，走正常系统路由（不进 TUN）。
/// 内网 DNS 服务器 IP 在 bypass 路由中（如 11.0.0.0/8），
/// 流量会走 VPN/en0 到达内网 DNS 服务器。
async fn forward_dns_to_real_server(
    query_payload: &[u8],
    dns_server: Ipv4Addr,
) -> Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
    // 绑定到 0.0.0.0:0 让系统选择出口和端口
    let socket = UdpSocket::bind("0.0.0.0:0").await?;

    let target = SocketAddr::new(std::net::IpAddr::V4(dns_server), 53);
    socket.send_to(query_payload, target).await?;

    // 等待响应（超时 3 秒）
    let mut buf = vec![0u8; 4096];
    let recv_future = socket.recv_from(&mut buf);
    let result = tokio::time::timeout(std::time::Duration::from_secs(3), recv_future).await;

    match result {
        Ok(Ok((n, _addr))) => {
            buf.truncate(n);
            Ok(buf)
        }
        Ok(Err(e)) => Err(Box::new(e)),
        Err(_) => Err("DNS forward timeout (3s)".into()),
    }
}

/// 构造 IPv4 + UDP 响应包
fn build_udp_response(
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let udp_len = 8 + payload.len();
    let ip_total_len = 20 + udp_len;

    let mut packet = vec![0u8; ip_total_len];

    packet[0] = 0x45;
    packet[1] = 0x00;
    packet[2] = (ip_total_len >> 8) as u8;
    packet[3] = (ip_total_len & 0xFF) as u8;
    packet[4] = 0x00;
    packet[5] = 0x00;
    packet[6] = 0x40;
    packet[7] = 0x00;
    packet[8] = 64;
    packet[9] = 17;
    packet[10] = 0x00;
    packet[11] = 0x00;
    packet[12..16].copy_from_slice(&src_ip);
    packet[16..20].copy_from_slice(&dst_ip);

    let checksum = ip_checksum(&packet[..20]);
    packet[10] = (checksum >> 8) as u8;
    packet[11] = (checksum & 0xFF) as u8;

    let udp_start = 20;
    packet[udp_start] = (src_port >> 8) as u8;
    packet[udp_start + 1] = (src_port & 0xFF) as u8;
    packet[udp_start + 2] = (dst_port >> 8) as u8;
    packet[udp_start + 3] = (dst_port & 0xFF) as u8;
    packet[udp_start + 4] = (udp_len >> 8) as u8;
    packet[udp_start + 5] = (udp_len & 0xFF) as u8;
    packet[udp_start + 6] = 0x00;
    packet[udp_start + 7] = 0x00;

    packet[udp_start + 8..].copy_from_slice(payload);

    packet
}

/// 计算 IP 头部校验和
fn ip_checksum(header: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i < header.len() {
        let word = ((header[i] as u32) << 8) | (header[i + 1] as u32);
        sum += word;
        i += 2;
    }
    while sum > 0xFFFF {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !sum as u16
}

/// 从 DNS payload 中提取查询的域名
fn extract_dns_query_name(payload: &[u8]) -> Option<String> {
    if payload.len() < 13 {
        return None;
    }

    let mut name = String::new();
    let mut pos = 12;

    loop {
        if pos >= payload.len() {
            break;
        }
        let len = payload[pos] as usize;
        if len == 0 {
            break;
        }
        pos += 1;
        if pos + len > payload.len() {
            break;
        }
        if !name.is_empty() {
            name.push('.');
        }
        name.push_str(&String::from_utf8_lossy(&payload[pos..pos + len]));
        pos += len;
    }

    if name.is_empty() {
        None
    } else {
        Some(name)
    }
}

/// 从原始 IP 包中提取 TCP SYN 的完整信息
fn extract_tcp_syn_info(packet: &[u8]) -> Option<(Ipv4Addr, u16, Ipv4Addr, u16)> {
    if packet.is_empty() {
        return None;
    }

    let version = (packet[0] >> 4) & 0x0F;
    if version != 4 {
        return None;
    }

    let ipv4 = Ipv4Packet::new_checked(packet).ok()?;

    if ipv4.next_header() != IpProtocol::Tcp {
        return None;
    }

    let tcp_data = ipv4.payload();
    let tcp = TcpPacket::new_checked(tcp_data).ok()?;

    if tcp.syn() && !tcp.ack() {
        let src_ip = Ipv4Addr::new(
            ipv4.src_addr().0[0],
            ipv4.src_addr().0[1],
            ipv4.src_addr().0[2],
            ipv4.src_addr().0[3],
        );
        let dst_ip = Ipv4Addr::new(
            ipv4.dst_addr().0[0],
            ipv4.dst_addr().0[1],
            ipv4.dst_addr().0[2],
            ipv4.dst_addr().0[3],
        );
        Some((src_ip, tcp.src_port(), dst_ip, tcp.dst_port()))
    } else {
        None
    }
}

/// 从 TCP 包中提取完整四元组 (src_ip, src_port, dst_ip, dst_port)
fn extract_tcp_4tuple(packet: &[u8]) -> Option<(Ipv4Addr, u16, Ipv4Addr, u16)> {
    if packet.len() < 20 {
        return None;
    }
    let version = (packet[0] >> 4) & 0x0F;
    if version != 4 {
        return None;
    }
    if packet[9] != 6 {
        return None; // not TCP
    }
    let ihl = (packet[0] & 0x0F) as usize * 4;
    if packet.len() < ihl + 4 {
        return None;
    }
    let src_ip = Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]);
    let dst_ip = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
    let src_port = u16::from_be_bytes([packet[ihl], packet[ihl + 1]]);
    let dst_port = u16::from_be_bytes([packet[ihl + 2], packet[ihl + 3]]);
    Some((src_ip, src_port, dst_ip, dst_port))
}

/// 检查 IP 包是否为 TCP 协议
fn is_tcp_packet(packet: &[u8]) -> bool {
    if packet.len() < 20 {
        return false;
    }
    let version = (packet[0] >> 4) & 0x0F;
    if version != 4 {
        return false;
    }
    packet[9] == 6 // protocol field = TCP
}

/// 活跃 TCP 连接追踪
struct ActiveConnection {
    handle: SocketHandle,
    tx: Option<mpsc::Sender<StreamCommand>>,
    rx: mpsc::Receiver<StreamCommand>,
    conn_tuple: (Ipv4Addr, u16, Ipv4Addr, u16),
    /// 通知 stack_loop 有数据需要处理（未使用但保留用于 SmolTcpStream 通知机制）
    #[allow(dead_code)]
    notify: mpsc::Sender<()>,
    pending_write: Option<(Vec<u8>, usize)>,
    write_closed: bool,
    closing_since: Option<std::time::Instant>,
}

impl ActiveConnection {
    /// Returns false only when the socket can be reclaimed after one final poll.
    fn pump(&mut self, socket: &mut TcpSocket, now: std::time::Instant) -> bool {
        if socket.state() == TcpState::Closed {
            return false;
        }

        // Reserve before consuming acknowledged TCP bytes (TS-01).
        if let Some(tx) = &self.tx {
            if tx.is_closed() {
                socket.abort();
                return false;
            }
            while socket.can_recv() {
                let permit = match tx.try_reserve() {
                    Ok(permit) => permit,
                    Err(mpsc::error::TrySendError::Full(_)) => break,
                    Err(mpsc::error::TrySendError::Closed(_)) => {
                        socket.abort();
                        return false;
                    }
                };
                let mut buf = vec![0; 16384];
                match socket.recv_slice(&mut buf) {
                    Ok(n) if n > 0 => {
                        buf.truncate(n);
                        permit.send(StreamCommand::Data(buf));
                    }
                    _ => break,
                }
            }
            if !socket.may_recv() {
                // Drop sender only after draining the socket: queued bytes precede EOF.
                self.tx = None;
            }
        }

        while !self.write_closed {
            if let Some((data, offset)) = &mut self.pending_write {
                if !socket.can_send() {
                    break;
                }
                match socket.send_slice(&data[*offset..]) {
                    Ok(0) => break,
                    Ok(n) => *offset += n,
                    Err(_) => {
                        socket.abort();
                        return false;
                    }
                }
                if *offset < data.len() {
                    break;
                }
                self.pending_write = None;
            }
            match self.rx.try_recv() {
                Ok(StreamCommand::Data(data)) if !data.is_empty() => {
                    self.pending_write = Some((data, 0));
                }
                Ok(StreamCommand::Data(_)) => {}
                Ok(StreamCommand::Close) => {
                    // FIN follows all bytes already accepted by smoltcp.
                    socket.close();
                    self.write_closed = true;
                }
                Err(mpsc::error::TryRecvError::Disconnected) => {
                    // Graceful shutdown must enqueue Close; a drop without it is failure.
                    socket.abort();
                    return false;
                }
                Err(mpsc::error::TryRecvError::Empty) => break,
            }
        }

        // Keep half-open streams alive while the opposite direction is still active.
        // Once both application halves ended, bound FIN/ACK cleanup (TS-02).
        if self.write_closed && self.tx.is_none() {
            let since = self.closing_since.get_or_insert(now);
            if now.duration_since(*since) >= std::time::Duration::from_secs(30) {
                socket.abort();
                return false;
            }
        }
        true
    }
}

fn retire_pending(
    sockets: &mut SocketSet<'_>,
    pending: &mut Vec<(SocketHandle, std::time::Instant)>,
    now: std::time::Instant,
) -> Vec<SocketHandle> {
    let mut retired = Vec::new();
    pending.retain(|(handle, created)| {
        let socket = sockets.get_mut::<TcpSocket>(*handle);
        let limit = match socket.state() {
            TcpState::Closed => 0,
            TcpState::Listen => 5,
            TcpState::SynReceived => 15,
            _ => 30,
        };
        if now.duration_since(*created) >= std::time::Duration::from_secs(limit) {
            socket.abort();
            retired.push(*handle);
            false
        } else {
            true
        }
    });
    retired
}

/// smoltcp 虚拟设备：桥接 TUN 设备与 smoltcp
struct VirtualDevice {
    rx_queue: VecDeque<Vec<u8>>,
    tx_queue: VecDeque<Vec<u8>>,
}

impl VirtualDevice {
    fn new() -> Self {
        Self {
            rx_queue: VecDeque::with_capacity(256),
            tx_queue: VecDeque::with_capacity(256),
        }
    }
}

impl Device for VirtualDevice {
    type RxToken<'a> = VirtualRxToken;
    type TxToken<'a> = VirtualTxToken<'a>;

    fn receive(
        &mut self,
        _timestamp: SmolInstant,
    ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        if let Some(packet) = self.rx_queue.pop_front() {
            let rx = VirtualRxToken { buffer: packet };
            let tx = VirtualTxToken {
                queue: &mut self.tx_queue,
            };
            Some((rx, tx))
        } else {
            None
        }
    }

    fn transmit(&mut self, _timestamp: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(VirtualTxToken {
            queue: &mut self.tx_queue,
        })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = 1500;
        caps
    }
}

struct VirtualRxToken {
    buffer: Vec<u8>,
}

impl RxToken for VirtualRxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        f(&mut self.buffer)
    }
}

struct VirtualTxToken<'a> {
    queue: &'a mut VecDeque<Vec<u8>>,
}

impl<'a> TxToken for VirtualTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buffer = vec![0u8; len];
        let result = f(&mut buffer);
        self.queue.push_back(buffer);
        result
    }
}

/// reserve future 类型别名
type ReserveFuture = std::pin::Pin<
    Box<
        dyn std::future::Future<
                Output = Result<mpsc::OwnedPermit<StreamCommand>, mpsc::error::SendError<()>>,
            > + Send,
    >,
>;

/// 异步 TCP 流封装 - 将 smoltcp 的 TCP 连接包装为 AsyncRead + AsyncWrite
///
/// poll_write 在 channel 满时使用 reserve_owned() 正确挂起，
/// 成功发送后通过 notify channel 通知 stack_loop 及时处理数据。
pub struct SmolTcpStream {
    tx: mpsc::Sender<StreamCommand>,
    rx: mpsc::Receiver<StreamCommand>,
    read_buf: Vec<u8>,
    pending_reserve: Option<ReserveFuture>,
    read_eof: bool,
    write_closed: bool,
    /// 通知 stack_loop 有数据写入 channel
    notify: mpsc::Sender<()>,
}

impl SmolTcpStream {
    /// 从事件中的 channel 创建
    pub fn new(
        tx: mpsc::Sender<StreamCommand>,
        rx: mpsc::Receiver<StreamCommand>,
        notify: mpsc::Sender<()>,
    ) -> Self {
        Self {
            tx,
            rx,
            read_buf: Vec::new(),
            pending_reserve: None,
            read_eof: false,
            write_closed: false,
            notify,
        }
    }

    fn poll_permit(
        &mut self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<mpsc::OwnedPermit<StreamCommand>>> {
        if self.pending_reserve.is_none() {
            let tx = self.tx.clone();
            self.pending_reserve = Some(Box::pin(async move { tx.reserve_owned().await }));
        }
        match self.pending_reserve.as_mut().unwrap().as_mut().poll(cx) {
            std::task::Poll::Ready(result) => {
                self.pending_reserve = None;
                std::task::Poll::Ready(result.map_err(|_| {
                    std::io::Error::new(std::io::ErrorKind::BrokenPipe, "stream closed")
                }))
            }
            std::task::Poll::Pending => std::task::Poll::Pending,
        }
    }
}

impl tokio::io::AsyncRead for SmolTcpStream {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if buf.remaining() == 0 || self.read_eof {
            return std::task::Poll::Ready(Ok(()));
        }
        if !self.read_buf.is_empty() {
            let n = std::cmp::min(buf.remaining(), self.read_buf.len());
            buf.put_slice(&self.read_buf[..n]);
            self.read_buf.drain(..n);
            return std::task::Poll::Ready(Ok(()));
        }

        match self.rx.poll_recv(cx) {
            std::task::Poll::Ready(Some(StreamCommand::Data(data))) => {
                let n = std::cmp::min(buf.remaining(), data.len());
                buf.put_slice(&data[..n]);
                if n < data.len() {
                    self.read_buf.extend_from_slice(&data[n..]);
                }
                std::task::Poll::Ready(Ok(()))
            }
            std::task::Poll::Ready(Some(StreamCommand::Close)) | std::task::Poll::Ready(None) => {
                self.read_eof = true;
                std::task::Poll::Ready(Ok(())) // EOF
            }
            std::task::Poll::Pending => std::task::Poll::Pending,
        }
    }
}

impl tokio::io::AsyncWrite for SmolTcpStream {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        if self.write_closed {
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "write half closed",
            )));
        }
        if buf.is_empty() {
            return std::task::Poll::Ready(Ok(0));
        }
        let permit = std::task::ready!(self.poll_permit(cx))?;
        permit.send(StreamCommand::Data(buf.to_vec()));
        let _ = self.notify.try_send(());
        std::task::Poll::Ready(Ok(buf.len()))
    }

    fn poll_flush(
        self: std::pin::Pin<&mut Self>,
        _cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::task::Poll::Ready(Ok(()))
    }

    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.write_closed {
            return std::task::Poll::Ready(Ok(()));
        }
        let permit = std::task::ready!(self.poll_permit(cx))?;
        permit.send(StreamCommand::Close);
        self.write_closed = true;
        let _ = self.notify.try_send(());
        std::task::Poll::Ready(Ok(()))
    }
}

#[cfg(test)]
#[path = "stack_tests.rs"]
mod tests;
