//! A6 —— 用户态 TCP/IP 协议栈（Android 数据面核心）。
//!
//! 本模块是 A4 在 `net_probe.rs` 里登记的「技术债」的偿还：A4 只做到「识别 TCP SYN
//! 并打印日志」，没有真正的 TCP 握手与字节流提取，因此无法把流量接到加密出站。
//! 本模块把桌面 `tun-adapter/src/stack.rs`（基于 smoltcp、经过真机实战）的 TCP 栈
//! **移植**进 plane-core，并做三处适配：
//!
//! 1. **TUN IO 类型**：用 [`crate::android_tun::TunReader`] / [`TunWriter`]（A3，签名
//!    与桌面 `tun_device` 完全一致，故 `stack_loop` 主体几乎零改动）。
//! 2. **FakeDNS**：用 [`crate::net_probe::FakeDnsEngine`]（A4，逻辑与桌面对齐），
//!    **去掉**桌面专属的「内网 DNS 转发」分支（手机端 MVP 不需要，全部走 FakeIP）。
//! 3. **下游**：连接建立后通过 [`TcpEvent::NewConnection`] 上报，dst_ip 为 FakeIP，
//!    由 [`crate::dispatcher`] 反查域名并接 [`crate::outbound::proxy_via_remote`]。
//!    桌面的 SOCKS5 转发逻辑不在本模块。
//!
//! ## 设计铁律
//!
//! - **不修改** `net_probe.rs`（保住 A4 验收）与桌面 `tun-adapter`（独立可运行）。
//!   本模块是纯新增，与它们并存。
//! - [`SmolTcpStream`] 实现 `AsyncRead + AsyncWrite`，正是 `proxy_via_remote` 的
//!   `local` 入参所需，从而把「TUN 里的 TCP 字节流」无缝喂给加密出站。
//!
//! 移植自桌面 `stack.rs` 的核心不变量（踩坑换来的，不得擅动）：
//! - 每端口至多一个 Listen socket（避免 smoltcp 同端口多 Listen 路由混乱）；
//! - 非 SYN 的未知连接包一律丢弃（防止 smoltcp 回 RST 杀死启动前已存在的连接）；
//! - `set_any_ip(true)` + 默认路由，接受所有目标 IP（FakeIP 是任意 198.18/15）。

use std::collections::{HashMap, HashSet, VecDeque};
use std::net::Ipv4Addr;
use std::sync::Arc;

use smoltcp::iface::{Config as SmolConfig, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::tcp::{self, Socket as TcpSocket, State as TcpState};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{
    HardwareAddress, IpAddress, IpCidr, IpListenEndpoint, IpProtocol, Ipv4Packet, TcpPacket,
};
use tokio::sync::{mpsc, Mutex};

use crate::android_tun::{TunReader, TunWriter};
use crate::error::CoreError;
use crate::net_probe::FakeDnsEngine;

/// TCP 事件：新连接建立通知（上报给 [`crate::dispatcher`]）。
#[derive(Debug)]
pub enum TcpEvent {
    /// 一条新的 TCP 连接已建立（smoltcp 三次握手完成）。
    NewConnection {
        /// 源 IP（TUN 内的应用侧）。
        src_ip: Ipv4Addr,
        /// 目标 IP（FakeIP，需经 FakeDNS 反查真实域名）。
        dst_ip: Ipv4Addr,
        /// 目标端口（如 443/80）。
        dst_port: u16,
        /// 栈 → 应用方向的发送端（栈把收到的字节通过它交给上层）。
        stream_tx: mpsc::Sender<StreamCommand>,
        /// 应用 → 栈方向的接收端（上层回写的字节由栈取出送回 TUN）。
        stream_rx: mpsc::Receiver<StreamCommand>,
    },
}

/// 栈与转发任务之间传递的流命令（移植自桌面 `stack.rs`）。
#[derive(Debug)]
pub enum StreamCommand {
    /// 一段字节数据。
    Data(Vec<u8>),
    /// 连接关闭。
    Close,
}

/// 用户态 TCP/IP 栈主循环。
///
/// 从 TUN 读 IP 包 → smoltcp 处理 → DNS 交 FakeDNS 原路写回 → 新建 TCP 连接经
/// `tcp_event_tx` 上报。`shutdown_rx` 收到信号后优雅退出（A6 的 stop 路径）。
///
/// 移植自桌面 `stack_loop`，删除内网 DNS 转发；DNS 一律走 FakeDNS（FakeIP）。
pub async fn stack_loop(
    mut tun_reader: TunReader,
    mut tun_writer: TunWriter,
    fake_dns: Arc<Mutex<FakeDnsEngine>>,
    tcp_event_tx: mpsc::Sender<TcpEvent>,
    notify_tx: mpsc::Sender<()>,
    mut notify_rx: mpsc::Receiver<()>,
    mut shutdown_rx: tokio::sync::watch::Receiver<bool>,
) -> Result<(), CoreError> {
    tracing::info!("用户态 TCP/IP 栈启动");

    let mut device = VirtualDevice::new();
    let smol_config = SmolConfig::new(HardwareAddress::Ip);
    let mut iface = Interface::new(smol_config, &mut device, SmolInstant::now());

    // FakeIP 池为 198.18.0.0/15，给接口配一个池内地址 + 默认路由 + any_ip。
    iface.update_ip_addrs(|addrs| {
        addrs
            .push(IpCidr::new(IpAddress::v4(198, 18, 0, 1), 15))
            .ok();
    });
    iface.set_any_ip(true);
    iface
        .routes_mut()
        .add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(198, 18, 0, 1))
        .ok();

    let mut sockets = SocketSet::new(Vec::new());

    let mut listening_ports: HashMap<u16, SocketHandle> = HashMap::new();
    let mut pending_handles: Vec<(SocketHandle, std::time::Instant)> = Vec::new();
    let mut active_connections: Vec<ActiveConnection> = Vec::new();
    let mut accepted_connections: HashSet<(Ipv4Addr, u16, Ipv4Addr, u16)> = HashSet::new();

    let mut read_buf = vec![0u8; 2048];

    let mut pkt_count: u64 = 0;
    let mut last_stats = std::time::Instant::now();

    tracing::info!("栈主循环开始运行，any_ip=true");

    loop {
        let has_active = !active_connections.is_empty();
        let poll_interval = if has_active {
            tokio::time::Duration::from_millis(5)
        } else {
            tokio::time::Duration::from_millis(50)
        };

        tokio::select! {
            biased;

            // 优雅停止：watch 置 true 即退出主循环。
            res = shutdown_rx.changed() => {
                if res.is_err() || *shutdown_rx.borrow() {
                    tracing::info!("栈收到停止信号，退出主循环");
                    return Ok(());
                }
            }

            read_result = tun_reader.read(&mut read_buf) => {
                match read_result {
                    Ok(n) if n > 0 => {
                        pkt_count += 1;
                        let packet_data = &read_buf[..n];

                        if pkt_count % 200 == 1 || last_stats.elapsed() > std::time::Duration::from_secs(10) {
                            tracing::info!(
                                "栈统计: pkts={}, listening={}, pending={}, active={}, accepted={}",
                                pkt_count, listening_ports.len(), pending_handles.len(),
                                active_connections.len(), accepted_connections.len()
                            );
                            last_stats = std::time::Instant::now();
                        }

                        // DNS 拦截：dst port 53 → FakeDNS 分配 FakeIP，原路写回响应。
                        if let Some(dns_response) = try_handle_dns_packet(packet_data, &fake_dns).await {
                            if let Err(e) = tun_writer.write_all(&dns_response).await {
                                tracing::warn!("写回 DNS 响应到 TUN 失败: {}", e);
                            }
                        } else {
                            // TCP 包处理：过滤未知连接的非 SYN 包，避免 smoltcp 回 RST。
                            let should_feed = if is_tcp_packet(packet_data) {
                                if let Some((_s_ip, _s_port, _d_ip, dst_port)) = extract_tcp_syn_info(packet_data) {
                                    // SYN 包 —— 确保目标端口有 Listen socket。
                                    let need_new = match listening_ports.get(&dst_port) {
                                        None => true,
                                        Some(&handle) => {
                                            sockets.get_mut::<TcpSocket>(handle).state() != TcpState::Listen
                                        }
                                    };
                                    if need_new {
                                        let rx_buf = tcp::SocketBuffer::new(vec![0u8; 65536]);
                                        let tx_buf = tcp::SocketBuffer::new(vec![0u8; 65536]);
                                        let mut sock = TcpSocket::new(rx_buf, tx_buf);
                                        let listen_ep = IpListenEndpoint { addr: None, port: dst_port };
                                        if sock.listen(listen_ep).is_ok() {
                                            let handle = sockets.add(sock);
                                            if let Some(old) = listening_ports.insert(dst_port, handle) {
                                                pending_handles.push((old, std::time::Instant::now()));
                                            }
                                        }
                                    }
                                    true
                                } else {
                                    // 非 SYN：仅当有对应 socket 跟踪时才喂给 smoltcp。
                                    let tcp_tuple = extract_tcp_4tuple(packet_data);
                                    tcp_tuple.is_some_and(|(src_ip, src_port, dst_ip, dst_port)| {
                                        for &(handle, _) in pending_handles.iter() {
                                            let socket = sockets.get_mut::<TcpSocket>(handle);
                                            if let (Some(local_ep), Some(remote_ep)) =
                                                (socket.local_endpoint(), socket.remote_endpoint())
                                            {
                                                if local_ep.port == dst_port {
                                                    let remote_ok = matches!(remote_ep.addr,
                                                        IpAddress::Ipv4(ip)
                                                        if Ipv4Addr::new(ip.0[0], ip.0[1], ip.0[2], ip.0[3]) == src_ip
                                                            && remote_ep.port == src_port);
                                                    let local_ok = matches!(local_ep.addr,
                                                        IpAddress::Ipv4(ip)
                                                        if Ipv4Addr::new(ip.0[0], ip.0[1], ip.0[2], ip.0[3]) == dst_ip);
                                                    if remote_ok && local_ok {
                                                        return true;
                                                    }
                                                }
                                            }
                                        }
                                        active_connections
                                            .iter()
                                            .any(|c| c.conn_tuple == (src_ip, src_port, dst_ip, dst_port))
                                    })
                                }
                            } else {
                                false
                            };

                            if should_feed {
                                device.rx_queue.push_back(read_buf[..n].to_vec());
                            }
                        }
                    }
                    Ok(_) => {}
                    Err(e) => {
                        tracing::error!("TUN 读取错误: {}", e);
                        return Err(CoreError::Io(e));
                    }
                }
            }

            _ = notify_rx.recv() => {
                while notify_rx.try_recv().is_ok() {}
            }

            _ = tokio::time::sleep(poll_interval) => {}
        }

        // ===== 驱动 smoltcp =====
        iface.poll(SmolInstant::now(), &mut device, &mut sockets);

        // ===== Listen → 非 Listen 的迁移到 pending =====
        let mut ports_to_refresh: Vec<u16> = Vec::new();
        for (&port, &handle) in listening_ports.iter() {
            if sockets.get_mut::<TcpSocket>(handle).state() != TcpState::Listen {
                ports_to_refresh.push(port);
            }
        }
        for port in ports_to_refresh {
            if let Some(handle) = listening_ports.remove(&port) {
                pending_handles.push((handle, std::time::Instant::now()));
            }
        }

        // ===== pending 中 Established 的连接上报 =====
        let mut established_idx: Vec<usize> = Vec::new();
        for (idx, &(handle, _)) in pending_handles.iter().enumerate() {
            let socket = sockets.get_mut::<TcpSocket>(handle);
            if socket.state() != TcpState::Established {
                continue;
            }
            let (Some(local), Some(remote)) = (socket.local_endpoint(), socket.remote_endpoint())
            else {
                continue;
            };
            let src_ip = match remote.addr {
                IpAddress::Ipv4(ip) => Ipv4Addr::new(ip.0[0], ip.0[1], ip.0[2], ip.0[3]),
                _ => continue,
            };
            let dst_ip = match local.addr {
                IpAddress::Ipv4(ip) => Ipv4Addr::new(ip.0[0], ip.0[1], ip.0[2], ip.0[3]),
                _ => continue,
            };
            let conn_tuple = (src_ip, remote.port, dst_ip, local.port);

            if accepted_connections.contains(&conn_tuple) {
                established_idx.push(idx);
                continue;
            }
            accepted_connections.insert(conn_tuple);

            tracing::info!(
                "新 TCP 连接已建立: {}:{} -> {}:{}",
                src_ip,
                remote.port,
                dst_ip,
                local.port
            );

            let (app_tx, stack_rx) = mpsc::channel(512);
            let (stack_tx, app_rx) = mpsc::channel(512);

            let event = TcpEvent::NewConnection {
                src_ip,
                dst_ip,
                dst_port: local.port,
                stream_tx: stack_tx,
                stream_rx: stack_rx,
            };
            if tcp_event_tx.send(event).await.is_err() {
                tracing::error!("TCP 事件通道已关闭，栈退出");
                return Err(CoreError::Internal("tcp event channel closed".to_string()));
            }

            active_connections.push(ActiveConnection {
                handle,
                tx: app_tx,
                rx: app_rx,
                pending_to_stack: None,
                conn_tuple,
                notify: notify_tx.clone(),
            });
            established_idx.push(idx);
        }
        established_idx.sort_unstable();
        for &idx in established_idx.iter().rev() {
            pending_handles.swap_remove(idx);
        }

        // ===== 清理 pending 中失败/超时 socket =====
        pending_handles.retain(|(handle, created_at)| {
            let state = sockets.get_mut::<TcpSocket>(*handle).state();
            let age_ms = created_at.elapsed().as_millis();
            match state {
                TcpState::Closed => false,
                TcpState::SynReceived if age_ms > 15000 => false,
                TcpState::Listen if age_ms > 5000 => false,
                _ if age_ms > 30000 => false,
                _ => true,
            }
        });

        // ===== 活跃连接双向数据搬运 =====
        let mut had_data = false;
        let mut closed_tuples: Vec<(Ipv4Addr, u16, Ipv4Addr, u16)> = Vec::new();

        active_connections.retain_mut(|conn| {
            let socket = sockets.get_mut::<TcpSocket>(conn.handle);
            let state = socket.state();
            if state == TcpState::Closed || state == TcpState::TimeWait {
                closed_tuples.push(conn.conn_tuple);
                return false;
            }

            // smoltcp → app
            while socket.can_recv() {
                // Reserve capacity before recv_slice consumes bytes from smoltcp.
                let permit = match conn.tx.try_reserve() {
                    Ok(permit) => permit,
                    Err(mpsc::error::TrySendError::Full(_)) => break,
                    Err(mpsc::error::TrySendError::Closed(_)) => {
                        socket.close();
                        closed_tuples.push(conn.conn_tuple);
                        return false;
                    }
                };
                let mut buf = vec![0u8; 16384];
                match socket.recv_slice(&mut buf) {
                    Ok(n) if n > 0 => {
                        buf.truncate(n);
                        permit.send(StreamCommand::Data(buf));
                        had_data = true;
                    }
                    _ => break,
                }
            }

            // app → smoltcp
            while socket.can_send() {
                if conn.pending_to_stack.is_none() {
                    match conn.rx.try_recv() {
                        Ok(StreamCommand::Data(data)) => {
                            conn.pending_to_stack = Some(PendingStackWrite::new(data));
                        }
                        Ok(StreamCommand::Close) => {
                            socket.close();
                            closed_tuples.push(conn.conn_tuple);
                            return false;
                        }
                        Err(mpsc::error::TryRecvError::Disconnected) => {
                            socket.close();
                            closed_tuples.push(conn.conn_tuple);
                            return false;
                        }
                        Err(mpsc::error::TryRecvError::Empty) => break,
                    }
                }

                let pending = conn.pending_to_stack.as_mut().unwrap();
                if pending.is_complete() {
                    conn.pending_to_stack = None;
                    continue;
                }
                let written = match socket.send_slice(pending.remaining()) {
                    Ok(written) => written,
                    Err(_) => break,
                };
                if written == 0 {
                    break;
                }

                pending.advance(written);
                had_data = true;
                if pending.is_complete() {
                    conn.pending_to_stack = None;
                } else {
                    // Keep the unsent tail until smoltcp has buffer space again.
                    break;
                }
            }
            true
        });

        for tuple in &closed_tuples {
            accepted_connections.remove(tuple);
        }

        if had_data {
            iface.poll(SmolInstant::now(), &mut device, &mut sockets);
        }

        // ===== 写回 TUN =====
        while let Some(packet) = device.tx_queue.pop_front() {
            if let Err(e) = tun_writer.write_all(&packet).await {
                tracing::warn!("TUN 写回错误: {}", e);
            }
        }

        listening_ports.retain(|_port, handle| {
            sockets.get_mut::<TcpSocket>(*handle).state() != TcpState::Closed
        });
    }
}

/// 尝试在 IP 层处理 DNS 请求（dst port 53）。
///
/// 与桌面不同：**不做内网 DNS 转发**，一律交 FakeDNS 分配 FakeIP 并原路写回。
async fn try_handle_dns_packet(
    packet: &[u8],
    fake_dns: &Arc<Mutex<FakeDnsEngine>>,
) -> Option<Vec<u8>> {
    if packet.is_empty() || (packet[0] >> 4) & 0x0F != 4 {
        return None;
    }
    let ipv4 = Ipv4Packet::new_checked(packet).ok()?;
    if ipv4.next_header() != IpProtocol::Udp {
        return None;
    }
    let udp = smoltcp::wire::UdpPacket::new_checked(ipv4.payload()).ok()?;
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

    let dns_response = {
        let mut engine = fake_dns.lock().await;
        match engine.handle_dns_query(dns_payload) {
            Ok(resp) => resp,
            Err(e) => {
                tracing::warn!("FakeDNS 处理失败: {}", e);
                return None;
            }
        }
    };

    // 响应包：源/目的 IP、端口互换（DNS 服务器 → 客户端）。
    Some(build_udp_response(
        dst_ip.0,
        src_ip.0,
        dst_port,
        src_port,
        &dns_response,
    ))
}

/// 构造 IPv4 + UDP 响应包（移植自桌面 `stack.rs`）。
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
    packet[2] = (ip_total_len >> 8) as u8;
    packet[3] = (ip_total_len & 0xFF) as u8;
    packet[6] = 0x40;
    packet[8] = 64;
    packet[9] = 17; // UDP
    packet[12..16].copy_from_slice(&src_ip);
    packet[16..20].copy_from_slice(&dst_ip);
    let checksum = ip_checksum(&packet[..20]);
    packet[10] = (checksum >> 8) as u8;
    packet[11] = (checksum & 0xFF) as u8;

    let u = 20;
    packet[u] = (src_port >> 8) as u8;
    packet[u + 1] = (src_port & 0xFF) as u8;
    packet[u + 2] = (dst_port >> 8) as u8;
    packet[u + 3] = (dst_port & 0xFF) as u8;
    packet[u + 4] = (udp_len >> 8) as u8;
    packet[u + 5] = (udp_len & 0xFF) as u8;
    packet[u + 8..].copy_from_slice(payload);
    packet
}

/// IP 头部校验和（移植自桌面 `stack.rs`）。
fn ip_checksum(header: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i + 1 < header.len() {
        sum += ((header[i] as u32) << 8) | (header[i + 1] as u32);
        i += 2;
    }
    while sum > 0xFFFF {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !sum as u16
}

/// 从原始 IP 包中提取 TCP SYN（且非 ACK）的四元组。
fn extract_tcp_syn_info(packet: &[u8]) -> Option<(Ipv4Addr, u16, Ipv4Addr, u16)> {
    if packet.is_empty() || (packet[0] >> 4) & 0x0F != 4 {
        return None;
    }
    let ipv4 = Ipv4Packet::new_checked(packet).ok()?;
    if ipv4.next_header() != IpProtocol::Tcp {
        return None;
    }
    let tcp = TcpPacket::new_checked(ipv4.payload()).ok()?;
    if tcp.syn() && !tcp.ack() {
        let s = ipv4.src_addr().0;
        let d = ipv4.dst_addr().0;
        Some((
            Ipv4Addr::new(s[0], s[1], s[2], s[3]),
            tcp.src_port(),
            Ipv4Addr::new(d[0], d[1], d[2], d[3]),
            tcp.dst_port(),
        ))
    } else {
        None
    }
}

/// 从 TCP 包提取完整四元组 (src_ip, src_port, dst_ip, dst_port)。
fn extract_tcp_4tuple(packet: &[u8]) -> Option<(Ipv4Addr, u16, Ipv4Addr, u16)> {
    if packet.len() < 20 || (packet[0] >> 4) & 0x0F != 4 || packet[9] != 6 {
        return None;
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

/// 是否为 IPv4 TCP 包。
fn is_tcp_packet(packet: &[u8]) -> bool {
    packet.len() >= 20 && (packet[0] >> 4) & 0x0F == 4 && packet[9] == 6
}

/// 活跃 TCP 连接追踪（移植自桌面 `stack.rs`）。
struct ActiveConnection {
    handle: SocketHandle,
    tx: mpsc::Sender<StreamCommand>,
    rx: mpsc::Receiver<StreamCommand>,
    pending_to_stack: Option<PendingStackWrite>,
    conn_tuple: (Ipv4Addr, u16, Ipv4Addr, u16),
    #[allow(dead_code)]
    notify: mpsc::Sender<()>,
}

/// A channel message can be larger than the free space in smoltcp's send buffer.
/// Retain its unwritten tail so TCP receives the exact ordered byte stream.
struct PendingStackWrite {
    data: Vec<u8>,
    offset: usize,
}

impl PendingStackWrite {
    fn new(data: Vec<u8>) -> Self {
        Self { data, offset: 0 }
    }

    fn remaining(&self) -> &[u8] {
        &self.data[self.offset..]
    }

    fn advance(&mut self, written: usize) {
        debug_assert!(written <= self.remaining().len());
        self.offset += written;
    }

    fn is_complete(&self) -> bool {
        self.offset == self.data.len()
    }
}

/// smoltcp 虚拟设备：桥接 TUN 与 smoltcp（移植自桌面 `stack.rs`）。
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

/// `reserve_owned` future 类型别名（移植自桌面 `stack.rs`）。
type ReserveFuture = std::pin::Pin<
    Box<
        dyn std::future::Future<
                Output = Result<mpsc::OwnedPermit<StreamCommand>, mpsc::error::SendError<()>>,
            > + Send,
    >,
>;

/// 把 smoltcp TCP 连接包装为 `AsyncRead + AsyncWrite`，供 [`crate::outbound::proxy_via_remote`]
/// 作为 `local` 字节流使用（移植自桌面 `stack.rs::SmolTcpStream`）。
pub struct SmolTcpStream {
    tx: mpsc::Sender<StreamCommand>,
    rx: mpsc::Receiver<StreamCommand>,
    read_buf: Vec<u8>,
    pending_reserve: Option<ReserveFuture>,
    notify: mpsc::Sender<()>,
    shutdown_sent: bool,
}

impl SmolTcpStream {
    /// 从 [`TcpEvent::NewConnection`] 的 channel 构造。
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
            notify,
            shutdown_sent: false,
        }
    }
}

impl tokio::io::AsyncRead for SmolTcpStream {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
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
        // Complete an earlier reservation before trying a fresh send. Otherwise
        // a stale waiter can survive a successful try_send and reorder messages.
        if let Some(reserve_fut) = self.pending_reserve.as_mut() {
            match reserve_fut.as_mut().poll(cx) {
                std::task::Poll::Ready(Ok(permit)) => {
                    self.pending_reserve = None;
                    permit.send(StreamCommand::Data(buf.to_vec()));
                    let _ = self.notify.try_send(());
                    return std::task::Poll::Ready(Ok(buf.len()));
                }
                std::task::Poll::Ready(Err(_)) => {
                    self.pending_reserve = None;
                    return std::task::Poll::Ready(Err(std::io::Error::new(
                        std::io::ErrorKind::BrokenPipe,
                        "stream closed",
                    )));
                }
                std::task::Poll::Pending => return std::task::Poll::Pending,
            }
        }

        match self.tx.try_send(StreamCommand::Data(buf.to_vec())) {
            Ok(()) => {
                let _ = self.notify.try_send(());
                return std::task::Poll::Ready(Ok(buf.len()));
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                return std::task::Poll::Ready(Err(std::io::Error::new(
                    std::io::ErrorKind::BrokenPipe,
                    "stream closed",
                )));
            }
            Err(mpsc::error::TrySendError::Full(_)) => {}
        }

        if self.pending_reserve.is_none() {
            let tx = self.tx.clone();
            self.pending_reserve = Some(Box::pin(async move { tx.reserve_owned().await }));
        }
        let reserve_fut = self.pending_reserve.as_mut().unwrap();
        match reserve_fut.as_mut().poll(cx) {
            std::task::Poll::Ready(Ok(permit)) => {
                self.pending_reserve = None;
                permit.send(StreamCommand::Data(buf.to_vec()));
                let _ = self.notify.try_send(());
                std::task::Poll::Ready(Ok(buf.len()))
            }
            std::task::Poll::Ready(Err(_)) => {
                self.pending_reserve = None;
                std::task::Poll::Ready(Err(std::io::Error::new(
                    std::io::ErrorKind::BrokenPipe,
                    "stream closed",
                )))
            }
            std::task::Poll::Pending => std::task::Poll::Pending,
        }
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
        if self.shutdown_sent {
            return std::task::Poll::Ready(Ok(()));
        }

        if self.pending_reserve.is_none() {
            match self.tx.try_send(StreamCommand::Close) {
                Ok(()) => {
                    self.shutdown_sent = true;
                    let _ = self.notify.try_send(());
                    return std::task::Poll::Ready(Ok(()));
                }
                Err(mpsc::error::TrySendError::Closed(_)) => {
                    return std::task::Poll::Ready(Err(std::io::Error::new(
                        std::io::ErrorKind::BrokenPipe,
                        "stream closed",
                    )));
                }
                Err(mpsc::error::TrySendError::Full(_)) => {
                    let tx = self.tx.clone();
                    self.pending_reserve = Some(Box::pin(async move { tx.reserve_owned().await }));
                }
            }
        }

        let reserve_fut = self.pending_reserve.as_mut().unwrap();
        match reserve_fut.as_mut().poll(cx) {
            std::task::Poll::Ready(Ok(permit)) => {
                self.pending_reserve = None;
                permit.send(StreamCommand::Close);
                self.shutdown_sent = true;
                let _ = self.notify.try_send(());
                std::task::Poll::Ready(Ok(()))
            }
            std::task::Poll::Ready(Err(_)) => {
                self.pending_reserve = None;
                std::task::Poll::Ready(Err(std::io::Error::new(
                    std::io::ErrorKind::BrokenPipe,
                    "stream closed",
                )))
            }
            std::task::Poll::Pending => std::task::Poll::Pending,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ip_checksum_matches_known_header() {
        // 一个标准 IPv4 头（校验和位置先置 0），校验和应使全头补码和为 0。
        let mut hdr = vec![
            0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46, 0x40, 0x00, 0x40, 0x06, 0x00, 0x00, 0xac, 0x10,
            0x0a, 0x63, 0xac, 0x10, 0x0a, 0x0c,
        ];
        let cks = ip_checksum(&hdr);
        hdr[10] = (cks >> 8) as u8;
        hdr[11] = (cks & 0xFF) as u8;
        // 填回校验和后，整头重新计算应为 0。
        assert_eq!(ip_checksum(&hdr), 0);
    }

    #[test]
    fn build_udp_response_has_valid_ip_header() {
        let payload = b"dns-resp";
        let pkt = build_udp_response([198, 18, 0, 1], [10, 0, 0, 2], 53, 5353, payload);
        assert_eq!(pkt.len(), 20 + 8 + payload.len());
        assert_eq!(pkt[0], 0x45); // IPv4, IHL=5
        assert_eq!(pkt[9], 17); // UDP
        assert_eq!(&pkt[12..16], &[198, 18, 0, 1]); // src = DNS 服务器
        assert_eq!(&pkt[16..20], &[10, 0, 0, 2]); // dst = 客户端
        assert_eq!(u16::from_be_bytes([pkt[20], pkt[21]]), 53); // src port 53
        assert_eq!(u16::from_be_bytes([pkt[22], pkt[23]]), 5353); // dst port
        assert_eq!(&pkt[28..], payload);
        // IP 头校验和自洽。
        assert_eq!(ip_checksum(&pkt[..20]), 0);
    }

    #[test]
    fn is_tcp_packet_detects_protocol() {
        let mut tcp = vec![0u8; 20];
        tcp[0] = 0x45;
        tcp[9] = 6;
        assert!(is_tcp_packet(&tcp));
        tcp[9] = 17;
        assert!(!is_tcp_packet(&tcp));
        assert!(!is_tcp_packet(&[0u8; 10])); // 太短
    }

    #[test]
    fn extract_tcp_4tuple_parses_ports() {
        // IPv4(20B, IHL=5) + TCP 头：src 1.2.3.4:1111 -> dst 5.6.7.8:443
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[9] = 6; // TCP
        pkt[12..16].copy_from_slice(&[1, 2, 3, 4]);
        pkt[16..20].copy_from_slice(&[5, 6, 7, 8]);
        pkt[20..22].copy_from_slice(&1111u16.to_be_bytes()); // src port
        pkt[22..24].copy_from_slice(&443u16.to_be_bytes()); // dst port
        let t = extract_tcp_4tuple(&pkt).unwrap();
        assert_eq!(t.0, Ipv4Addr::new(1, 2, 3, 4));
        assert_eq!(t.1, 1111);
        assert_eq!(t.2, Ipv4Addr::new(5, 6, 7, 8));
        assert_eq!(t.3, 443);
    }

    #[test]
    fn extract_tcp_syn_info_only_matches_pure_syn() {
        // 构造一个结构完整的 IPv4+TCP SYN 包：smoltcp 的 new_checked 会校验
        // IP total_length / TCP data_offset 等结构字段，必须填全，否则解析返回 None。
        let mut pkt = vec![0u8; 40];
        // ---- IPv4 头（20B，IHL=5）----
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&40u16.to_be_bytes()); // total length = 40
        pkt[8] = 64; // TTL
        pkt[9] = 6; // protocol = TCP
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[198, 18, 0, 5]);
        let ip_ck = ip_checksum(&pkt[..20]);
        pkt[10..12].copy_from_slice(&ip_ck.to_be_bytes());
        // ---- TCP 头（20B）----
        pkt[20..22].copy_from_slice(&2222u16.to_be_bytes()); // src port
        pkt[22..24].copy_from_slice(&80u16.to_be_bytes()); // dst port
        pkt[32] = 0x50; // data offset = 5（高 4 位）
        pkt[33] = 0x02; // flags：SYN
        let syn = extract_tcp_syn_info(&pkt).unwrap();
        assert_eq!(syn.3, 80);
        // 加上 ACK（0x10）后不应再匹配（SYN-ACK 不是发起 SYN）。
        pkt[33] = 0x12;
        assert!(extract_tcp_syn_info(&pkt).is_none());
    }

    #[test]
    fn pending_stack_write_retains_partial_tail() {
        let original = b"0123456789".to_vec();
        let mut pending = PendingStackWrite::new(original.clone());
        let mut forwarded = Vec::new();

        for capacity in [3, 2, 5] {
            let written = capacity.min(pending.remaining().len());
            forwarded.extend_from_slice(&pending.remaining()[..written]);
            pending.advance(written);
        }

        assert!(pending.is_complete());
        assert_eq!(forwarded, original);
    }

    #[tokio::test]
    async fn stream_write_waits_for_channel_capacity() {
        use tokio::io::AsyncWriteExt;

        let (tx, mut stack_rx) = mpsc::channel(1);
        tx.try_send(StreamCommand::Data(b"first".to_vec())).unwrap();
        let (_incoming_tx, incoming_rx) = mpsc::channel(1);
        let (notify_tx, _notify_rx) = mpsc::channel(1);
        let mut stream = SmolTcpStream::new(tx, incoming_rx, notify_tx);

        let writer = tokio::spawn(async move { stream.write_all(b"second").await });
        tokio::task::yield_now().await;
        assert!(!writer.is_finished());

        let first = stack_rx.recv().await.unwrap();
        assert!(matches!(first, StreamCommand::Data(data) if data == b"first"));

        let second = tokio::time::timeout(std::time::Duration::from_secs(1), stack_rx.recv())
            .await
            .unwrap()
            .unwrap();
        assert!(matches!(second, StreamCommand::Data(data) if data == b"second"));
        writer.await.unwrap().unwrap();
    }
}
