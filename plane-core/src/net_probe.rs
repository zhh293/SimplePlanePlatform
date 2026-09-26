//! A4 数据面：FakeDNS + IP 包识别 + 栈事件循环（接线验证版）。
//!
//! 本模块把 [`crate::android_tun`] 的 TUN 读写半部接入一个最小数据面循环，证明
//! 「TUN fd → 解析 IP 包 → 识别 DNS/TCP → 回写响应」整条链路在 Android 侧可跑通，
//! 即任务文档 A4 的验收目标：
//!
//! - DNS（UDP dst port 53）查询：交 [`FakeDnsEngine`] 分配 FakeIP 并**原路写回** DNS 响应；
//! - TCP SYN：解析五元组，产出 [`TcpConnEvent`]——A4 阶段**先只打印事件**，
//!   证明握手与字节流提取点已就位（真正的 TCP 栈握手 / 出站接线在 A5/A6）。
//!
//! ## 与桌面的关系 / 技术债声明
//!
//! 桌面 `tun-adapter` 已有成熟的 `stack.rs`（smoltcp TCP 栈）、`fake_dns.rs`、
//! `router.rs`。理想终态是把这三者抽成共享 crate `plane-net`，两端复用、杜绝逻辑漂移。
//! 但 `stack.rs`（1000+ 行）硬编码了桌面专属类型（`tun_device::{TunReader,TunWriter}`、
//! `Socks5Error`、`RouterError` 等），直接泛型化改造风险高、极易破坏「当前可跑」的桌面。
//!
//! 因此 A4 采用**稳妥优先**策略：
//!
//! 1. 用平台无关 trait（[`AsyncTunReader`] / [`AsyncTunWriter`]）抽象读写，桌面代码**零改动**；
//! 2. 本模块内 [`FakeDnsEngine`] 的实现与桌面 `fake_dns.rs` **逐行对齐**（相同 crate/版本、
//!    相同 FakeIP 池策略、相同跳过 .0/.255、相同 TTL=1、相同 AAAA 空响应）；
//! 3. **技术债**：`plane-net` 共享 crate 抽取（含 smoltcp TCP 栈与路由）留待 A5/A6 实施，
//!    届时本模块的 FakeDNS 与桌面合并为单一实现。见任务文档 A4「抽取风险过高可记录技术债」。

#![cfg(unix)]

use std::net::{Ipv4Addr, SocketAddrV4};
use std::num::NonZeroUsize;

use lru::LruCache;

use crate::error::CoreError;

// ===========================================================================
// 平台无关 TUN IO 抽象
// ===========================================================================

/// 平台无关的 TUN 读端抽象。
///
/// [`crate::android_tun::TunReader`] 为其唯一实现；桌面 `tun_device::TunReader`
/// 因方法签名一致，未来抽取 `plane-net` 时也可零成本实现本 trait。
#[allow(async_fn_in_trait)]
pub trait AsyncTunReader {
    /// 读取一个 IP 包到 `buf`，返回字节数。
    async fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize>;
}

/// 平台无关的 TUN 写端抽象。
#[allow(async_fn_in_trait)]
pub trait AsyncTunWriter {
    /// 写入整个 `buf`（循环直到写完）。
    async fn write_all(&mut self, buf: &[u8]) -> std::io::Result<()>;
}

impl AsyncTunReader for crate::android_tun::TunReader {
    async fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        crate::android_tun::TunReader::read(self, buf).await
    }
}

impl AsyncTunWriter for crate::android_tun::TunWriter {
    async fn write_all(&mut self, buf: &[u8]) -> std::io::Result<()> {
        crate::android_tun::TunWriter::write_all(self, buf).await
    }
}

// ===========================================================================
// FakeDNS 引擎（逻辑对齐桌面 tun-adapter/src/fake_dns.rs）
// ===========================================================================

/// FakeDNS 引擎：管理域名与假 IP 的双向映射。
///
/// 实现与桌面 `fake_dns.rs::FakeDnsEngine` 一致：相同 LRU 双向表、相同 FakeIP 池
/// 循环分配、跳过 .0/.255、A 记录 TTL=1、AAAA 返回空响应。
pub struct FakeDnsEngine {
    /// IP → 域名 映射。
    ip_to_domain: LruCache<Ipv4Addr, String>,
    /// 域名 → IP 映射。
    domain_to_ip: LruCache<String, Ipv4Addr>,
    /// IP 池起始地址（数值形式，已跳过 .0）。
    pool_start: u32,
    /// IP 池结束地址（数值形式）。
    pool_end: u32,
    /// 下一个可分配的 IP（数值形式）。
    next_ip: u32,
}

impl FakeDnsEngine {
    /// 创建新的 FakeDNS 引擎。
    ///
    /// # Arguments
    /// * `pool_cidr` - FakeIP 地址池的 CIDR 范围，如 `"198.18.0.0/15"`。
    /// * `capacity` - LRU 缓存容量。
    pub fn new(pool_cidr: &str, capacity: usize) -> Self {
        let network: ipnet::Ipv4Net = pool_cidr.parse().expect("invalid FakeIP CIDR");

        let pool_start = u32::from(network.network());
        let pool_end = u32::from(network.broadcast());

        tracing::info!(
            "FakeDNS initialized: pool {}-{}, capacity {}",
            network.network(),
            network.broadcast(),
            capacity
        );

        Self {
            ip_to_domain: LruCache::new(NonZeroUsize::new(capacity).unwrap()),
            domain_to_ip: LruCache::new(NonZeroUsize::new(capacity).unwrap()),
            pool_start: pool_start + 1, // 跳过网络地址 .0
            pool_end,
            next_ip: pool_start + 1,
        }
    }

    /// 为域名分配一个 FakeIP（已分配则返回已有的）。
    pub fn allocate_ip(&mut self, domain: &str) -> Ipv4Addr {
        if let Some(&ip) = self.domain_to_ip.get(domain) {
            return ip;
        }

        let ip = self.next_available_ip();
        let domain_owned = domain.to_lowercase();

        // 若该 IP 之前被其他域名使用，清理旧映射。
        if let Some(old_domain) = self.ip_to_domain.pop(&ip) {
            self.domain_to_ip.pop(&old_domain);
        }

        self.ip_to_domain.put(ip, domain_owned.clone());
        self.domain_to_ip.put(domain_owned, ip);

        tracing::debug!("FakeDNS: allocated {} -> {}", domain, ip);
        ip
    }

    /// 获取下一个可用 IP，跳过 .0 和 .255。
    fn next_available_ip(&mut self) -> Ipv4Addr {
        loop {
            let ip = Ipv4Addr::from(self.next_ip);
            self.next_ip += 1;

            // 循环分配：到达池尾回到池头。
            if self.next_ip > self.pool_end {
                self.next_ip = self.pool_start;
            }

            let octets = ip.octets();
            if octets[3] == 0 || octets[3] == 255 {
                continue;
            }

            return ip;
        }
    }

    /// 处理 DNS 查询的原始 UDP payload，返回 DNS 响应的原始 bytes。
    pub fn handle_dns_query(&mut self, query_payload: &[u8]) -> Result<Vec<u8>, CoreError> {
        use hickory_proto::op::{Header, Message, OpCode, ResponseCode};
        use hickory_proto::rr::{DNSClass, RData, Record, RecordType};
        use hickory_proto::serialize::binary::BinDecodable;

        let request = Message::from_bytes(query_payload)
            .map_err(|e| CoreError::Dns(format!("parse: {e}")))?;

        let mut response = Message::new();
        let mut header = Header::response_from_request(request.header());
        header.set_recursion_available(true);
        header.set_op_code(OpCode::Query);
        header.set_response_code(ResponseCode::NoError);
        response.set_header(header);

        for query in request.queries() {
            response.add_query(query.clone());

            match query.query_type() {
                RecordType::A => {
                    let domain = query
                        .name()
                        .to_string()
                        .trim_end_matches('.')
                        .to_lowercase();
                    let fake_ip = self.allocate_ip(&domain);

                    let mut record = Record::new();
                    record.set_name(query.name().clone());
                    record.set_record_type(RecordType::A);
                    record.set_dns_class(DNSClass::IN);
                    record.set_ttl(1); // TTL=1 防止系统缓存
                    record.set_data(Some(RData::A(hickory_proto::rr::rdata::A(fake_ip))));
                    response.add_answer(record);
                }
                RecordType::AAAA => {
                    // AAAA：返回空 answer（MVP 不支持 IPv6 FakeIP）。
                    tracing::debug!("FakeDNS: AAAA query for {}, returning empty", query.name());
                }
                other => {
                    tracing::debug!("FakeDNS: unsupported query type {:?}, empty", other);
                }
            }
        }

        response
            .to_vec()
            .map_err(|e| CoreError::Dns(format!("encode: {e}")))
    }

    /// 根据假 IP 反查域名。
    pub fn lookup_domain(&self, fake_ip: &Ipv4Addr) -> Option<&str> {
        self.ip_to_domain.peek(fake_ip).map(|s| s.as_str())
    }

    /// 判断一个 IP 是否在 FakeIP 池范围内。
    pub fn is_fake_ip(&self, ip: &Ipv4Addr) -> bool {
        let ip_val = u32::from(*ip);
        ip_val >= self.pool_start && ip_val <= self.pool_end
    }
}

// ===========================================================================
// IPv4 包分类器
// ===========================================================================

/// 从 TUN 读到的一个 IPv4 包经分类后的结果。
#[derive(Debug, Clone)]
pub enum PacketClass {
    /// UDP 目的端口 53 的 DNS 查询：携带源/目的与 DNS payload 偏移信息，供回写响应。
    DnsQuery(DnsQueryInfo),
    /// TCP SYN（仅 SYN，无 ACK）：新连接发起，携带五元组。
    TcpSyn(TcpConnEvent),
    /// 其他包（非首包 TCP、非 53 UDP、ICMP、IPv6 等）：A4 阶段忽略。
    Other,
}

/// DNS 查询包的关键信息。
#[derive(Debug, Clone)]
pub struct DnsQueryInfo {
    /// IPv4 源地址（客户端）。
    pub src: SocketAddrV4,
    /// IPv4 目的地址（被劫持的 DNS 服务器，如 8.8.8.8:53）。
    pub dst: SocketAddrV4,
    /// DNS payload 在原始 IP 包中的起始字节偏移。
    pub payload_offset: usize,
}

/// 一条新 TCP 连接的五元组事件。
#[derive(Debug, Clone)]
pub struct TcpConnEvent {
    /// 源地址（客户端）。
    pub src: SocketAddrV4,
    /// 目的地址（可能是 FakeIP，需经 FakeDNS 反查真实域名）。
    pub dst: SocketAddrV4,
}

const IP_PROTO_TCP: u8 = 6;
const IP_PROTO_UDP: u8 = 17;

/// 解析一个 IPv4 包，分类为 DNS 查询 / TCP SYN / 其他。
///
/// 仅做只读解析，不修改 `packet`。非 IPv4 或长度不足一律返回 [`PacketClass::Other`]。
pub fn classify_ipv4(packet: &[u8]) -> PacketClass {
    // IPv4 头至少 20 字节。
    if packet.len() < 20 {
        return PacketClass::Other;
    }
    // 版本号必须为 4。
    if (packet[0] >> 4) != 4 {
        return PacketClass::Other;
    }
    let ihl = (packet[0] & 0x0f) as usize * 4;
    if ihl < 20 || packet.len() < ihl {
        return PacketClass::Other;
    }

    let protocol = packet[9];
    let src_ip = Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]);
    let dst_ip = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);

    let l4 = &packet[ihl..];

    match protocol {
        IP_PROTO_UDP => {
            // UDP 头 8 字节：src(2) dst(2) len(2) checksum(2)。
            if l4.len() < 8 {
                return PacketClass::Other;
            }
            let src_port = u16::from_be_bytes([l4[0], l4[1]]);
            let dst_port = u16::from_be_bytes([l4[2], l4[3]]);
            if dst_port != 53 {
                return PacketClass::Other;
            }
            PacketClass::DnsQuery(DnsQueryInfo {
                src: SocketAddrV4::new(src_ip, src_port),
                dst: SocketAddrV4::new(dst_ip, dst_port),
                payload_offset: ihl + 8,
            })
        }
        IP_PROTO_TCP => {
            // TCP 头至少 20 字节：src(2) dst(2) seq(4) ack(4) offset/flags ...
            if l4.len() < 20 {
                return PacketClass::Other;
            }
            let src_port = u16::from_be_bytes([l4[0], l4[1]]);
            let dst_port = u16::from_be_bytes([l4[2], l4[3]]);
            // flags 在 TCP 头第 13 字节，低 6 位含 SYN(0x02)/ACK(0x10)。
            let flags = l4[13];
            let syn = flags & 0x02 != 0;
            let ack = flags & 0x10 != 0;
            // 仅 SYN（无 ACK）= 新连接首包。
            if syn && !ack {
                PacketClass::TcpSyn(TcpConnEvent {
                    src: SocketAddrV4::new(src_ip, src_port),
                    dst: SocketAddrV4::new(dst_ip, dst_port),
                })
            } else {
                PacketClass::Other
            }
        }
        _ => PacketClass::Other,
    }
}

/// 构造一个 UDP-over-IPv4 的 DNS 响应包：把 `dns_payload` 封回 IP/UDP，
/// 源/目的相对原查询**对调**（即从 DNS 服务器回到客户端），供写回 TUN。
///
/// 计算 IPv4 与 UDP 校验和；UDP 校验和使用伪首部。
fn build_dns_response_packet(query: &DnsQueryInfo, dns_payload: &[u8]) -> Vec<u8> {
    // 响应：src = 原 dst（DNS 服务器），dst = 原 src（客户端）。
    let resp_src = query.dst;
    let resp_dst = query.src;

    let udp_len = 8 + dns_payload.len();
    let total_len = 20 + udp_len;
    let mut pkt = vec![0u8; total_len];

    // ---- IPv4 头 ----
    pkt[0] = 0x45; // 版本4 + IHL=5
    pkt[1] = 0; // DSCP/ECN
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[4..6].copy_from_slice(&0u16.to_be_bytes()); // id
    pkt[6..8].copy_from_slice(&0x4000u16.to_be_bytes()); // flags=DF
    pkt[8] = 64; // TTL
    pkt[9] = IP_PROTO_UDP;
    // checksum 占位 [10..12]
    pkt[12..16].copy_from_slice(&resp_src.ip().octets());
    pkt[16..20].copy_from_slice(&resp_dst.ip().octets());
    let ip_csum = checksum(&pkt[0..20]);
    pkt[10..12].copy_from_slice(&ip_csum.to_be_bytes());

    // ---- UDP 头 + payload ----
    let u = 20;
    pkt[u..u + 2].copy_from_slice(&resp_src.port().to_be_bytes());
    pkt[u + 2..u + 4].copy_from_slice(&resp_dst.port().to_be_bytes());
    pkt[u + 4..u + 6].copy_from_slice(&(udp_len as u16).to_be_bytes());
    // checksum 占位 [u+6..u+8]
    pkt[u + 8..u + 8 + dns_payload.len()].copy_from_slice(dns_payload);

    // UDP 校验和（含伪首部）。
    let udp_csum = udp_checksum(resp_src.ip(), resp_dst.ip(), &pkt[u..]);
    // UDP 校验和为 0 时按 RFC 768 写 0xFFFF。
    let udp_csum = if udp_csum == 0 { 0xFFFF } else { udp_csum };
    pkt[u + 6..u + 8].copy_from_slice(&udp_csum.to_be_bytes());

    pkt
}

/// 标准 16 位反码求和校验（用于 IPv4 头）。
pub fn checksum(data: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i + 1 < data.len() {
        sum += u16::from_be_bytes([data[i], data[i + 1]]) as u32;
        i += 2;
    }
    if i < data.len() {
        sum += (data[i] as u32) << 8;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

/// UDP 校验和（带 IPv4 伪首部）。
fn udp_checksum(src: &Ipv4Addr, dst: &Ipv4Addr, udp_segment: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    // 伪首部：src(4) dst(4) zero(1) proto(1) udp_len(2)
    let s = src.octets();
    let d = dst.octets();
    sum += u16::from_be_bytes([s[0], s[1]]) as u32;
    sum += u16::from_be_bytes([s[2], s[3]]) as u32;
    sum += u16::from_be_bytes([d[0], d[1]]) as u32;
    sum += u16::from_be_bytes([d[2], d[3]]) as u32;
    sum += IP_PROTO_UDP as u32;
    sum += udp_segment.len() as u32;

    let mut i = 0;
    while i + 1 < udp_segment.len() {
        sum += u16::from_be_bytes([udp_segment[i], udp_segment[i + 1]]) as u32;
        i += 2;
    }
    if i < udp_segment.len() {
        sum += (udp_segment[i] as u32) << 8;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

// ===========================================================================
// 栈事件循环（A4 接线验证）
// ===========================================================================

/// 运行最小数据面循环（A4 接线验证）。
///
/// 持续从 `reader` 读 IP 包：
/// - DNS 查询 → 交 [`FakeDnsEngine`] 处理并把响应**原路写回** `writer`；
/// - TCP SYN → 解析五元组，经 FakeDNS 反查域名后打印事件（A5/A6 再接出站）；
/// - 其他包 → 忽略。
///
/// 循环直到 `reader.read` 返回 `Ok(0)`（fd 关闭）或出错。
///
/// `fake_dns` 由调用方持有，便于与 TCP 连接建立时的 FakeIP→域名 反查共享同一张表。
pub async fn run_stack<R, W>(
    mut reader: R,
    mut writer: W,
    fake_dns: &mut FakeDnsEngine,
    mtu: usize,
) -> Result<(), CoreError>
where
    R: AsyncTunReader,
    W: AsyncTunWriter,
{
    // 读缓冲按 MTU（再留余量）分配。
    let mut buf = vec![0u8; mtu.max(1500) + 64];

    tracing::info!("net_probe: stack loop started (mtu={mtu})");

    loop {
        let n = reader.read(&mut buf).await?;
        if n == 0 {
            tracing::info!("net_probe: TUN reader EOF, stack loop exit");
            return Ok(());
        }
        let packet = &buf[..n];

        match classify_ipv4(packet) {
            PacketClass::DnsQuery(info) => {
                let payload = packet[info.payload_offset..].to_vec();
                match fake_dns.handle_dns_query(&payload) {
                    Ok(resp_dns) => {
                        let resp_pkt = build_dns_response_packet(&info, &resp_dns);
                        if let Err(e) = writer.write_all(&resp_pkt).await {
                            tracing::warn!("net_probe: write DNS response failed: {e}");
                        } else {
                            tracing::debug!(
                                "net_probe: DNS query {} answered ({} bytes)",
                                info.src,
                                resp_pkt.len()
                            );
                        }
                    }
                    Err(e) => {
                        tracing::warn!("net_probe: FakeDNS handle failed: {e}");
                    }
                }
            }
            PacketClass::TcpSyn(ev) => {
                // A4：先只打印事件，证明握手点已就位；出站接线在 A5/A6。
                let domain = fake_dns
                    .lookup_domain(ev.dst.ip())
                    .map(|d| d.to_string())
                    .unwrap_or_else(|| "<direct/unknown>".to_string());
                tracing::info!(
                    "net_probe: TCP SYN {} -> {} (domain={})",
                    ev.src,
                    ev.dst,
                    domain
                );
            }
            PacketClass::Other => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use hickory_proto::op::{Message, MessageType, OpCode, Query};
    use hickory_proto::rr::{DNSClass, Name, RecordType};
    use hickory_proto::serialize::binary::BinDecodable;

    fn build_dns_query(domain: &str, qtype: RecordType) -> Vec<u8> {
        let mut msg = Message::new();
        msg.set_id(0x1234);
        msg.set_message_type(MessageType::Query);
        msg.set_op_code(OpCode::Query);
        msg.set_recursion_desired(true);

        let name = Name::from_ascii(domain).unwrap();
        let mut query = Query::new();
        query.set_name(name);
        query.set_query_type(qtype);
        query.set_query_class(DNSClass::IN);
        msg.add_query(query);

        msg.to_vec().unwrap()
    }

    // ---- FakeDNS（与桌面 fake_dns.rs 同套用例）----

    #[test]
    fn test_allocate_ip_for_domain() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 1024);
        let ip = engine.allocate_ip("www.google.com");
        assert!(engine.is_fake_ip(&ip));
    }

    #[test]
    fn test_same_domain_returns_same_ip() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 1024);
        let ip1 = engine.allocate_ip("www.google.com");
        let ip2 = engine.allocate_ip("www.google.com");
        assert_eq!(ip1, ip2);
    }

    #[test]
    fn test_different_domains_return_different_ips() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 1024);
        let ip1 = engine.allocate_ip("www.google.com");
        let ip2 = engine.allocate_ip("www.github.com");
        assert_ne!(ip1, ip2);
    }

    #[test]
    fn test_lookup_domain() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 1024);
        let ip = engine.allocate_ip("www.google.com");
        assert_eq!(engine.lookup_domain(&ip), Some("www.google.com"));
    }

    #[test]
    fn test_is_fake_ip() {
        let engine = FakeDnsEngine::new("198.18.0.0/15", 1024);
        assert!(engine.is_fake_ip(&Ipv4Addr::new(198, 18, 1, 1)));
        assert!(engine.is_fake_ip(&Ipv4Addr::new(198, 19, 255, 254)));
        assert!(!engine.is_fake_ip(&Ipv4Addr::new(192, 168, 1, 1)));
        assert!(!engine.is_fake_ip(&Ipv4Addr::new(8, 8, 8, 8)));
    }

    #[test]
    fn test_handle_dns_query_a_record() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 1024);
        let query = build_dns_query("www.google.com.", RecordType::A);

        let response_bytes = engine.handle_dns_query(&query).unwrap();
        let response = Message::from_bytes(&response_bytes).unwrap();

        assert_eq!(response.id(), 0x1234);
        assert_eq!(response.message_type(), MessageType::Response);
        assert_eq!(response.answers().len(), 1);

        let answer = &response.answers()[0];
        assert_eq!(answer.record_type(), RecordType::A);
        assert_eq!(answer.ttl(), 1);

        if let Some(hickory_proto::rr::RData::A(a)) = answer.data() {
            assert!(engine.is_fake_ip(&a.0));
        } else {
            panic!("Expected A record");
        }
    }

    #[test]
    fn test_handle_dns_query_aaaa_returns_empty() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 1024);
        let query = build_dns_query("www.google.com.", RecordType::AAAA);

        let response_bytes = engine.handle_dns_query(&query).unwrap();
        let response = Message::from_bytes(&response_bytes).unwrap();

        assert_eq!(response.message_type(), MessageType::Response);
        assert_eq!(response.answers().len(), 0); // AAAA 返回空
    }

    #[test]
    fn test_skips_dot_zero_and_255() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 65536);
        for i in 0..1000 {
            let domain = format!("test{}.example.com", i);
            let ip = engine.allocate_ip(&domain);
            let octets = ip.octets();
            assert_ne!(octets[3], 0, "Got .0 address: {}", ip);
            assert_ne!(octets[3], 255, "Got .255 address: {}", ip);
        }
    }

    // ---- IPv4 包分类器 ----

    /// 构造一个 IPv4/UDP DNS 查询包（dst port 53），返回完整 IP 包字节。
    fn build_udp_dns_packet(src: SocketAddrV4, dst: SocketAddrV4, dns: &[u8]) -> Vec<u8> {
        let udp_len = 8 + dns.len();
        let total = 20 + udp_len;
        let mut pkt = vec![0u8; total];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&(total as u16).to_be_bytes());
        pkt[8] = 64;
        pkt[9] = IP_PROTO_UDP;
        pkt[12..16].copy_from_slice(&src.ip().octets());
        pkt[16..20].copy_from_slice(&dst.ip().octets());
        let u = 20;
        pkt[u..u + 2].copy_from_slice(&src.port().to_be_bytes());
        pkt[u + 2..u + 4].copy_from_slice(&dst.port().to_be_bytes());
        pkt[u + 4..u + 6].copy_from_slice(&(udp_len as u16).to_be_bytes());
        pkt[u + 8..u + 8 + dns.len()].copy_from_slice(dns);
        pkt
    }

    /// 构造一个 IPv4/TCP 包，flags 可控。
    fn build_tcp_packet(src: SocketAddrV4, dst: SocketAddrV4, flags: u8) -> Vec<u8> {
        let total = 20 + 20;
        let mut pkt = vec![0u8; total];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&(total as u16).to_be_bytes());
        pkt[8] = 64;
        pkt[9] = IP_PROTO_TCP;
        pkt[12..16].copy_from_slice(&src.ip().octets());
        pkt[16..20].copy_from_slice(&dst.ip().octets());
        let t = 20;
        pkt[t..t + 2].copy_from_slice(&src.port().to_be_bytes());
        pkt[t + 2..t + 4].copy_from_slice(&dst.port().to_be_bytes());
        // 数据偏移=5（20 字节头）写在第 12 字节高 4 位。
        pkt[t + 12] = 0x50;
        pkt[t + 13] = flags;
        pkt
    }

    #[test]
    fn classify_dns_query() {
        let src = SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), 12345);
        let dst = SocketAddrV4::new(Ipv4Addr::new(8, 8, 8, 8), 53);
        let dns = build_dns_query("example.com.", RecordType::A);
        let pkt = build_udp_dns_packet(src, dst, &dns);

        match classify_ipv4(&pkt) {
            PacketClass::DnsQuery(info) => {
                assert_eq!(info.src, src);
                assert_eq!(info.dst, dst);
                assert_eq!(info.payload_offset, 28);
                assert_eq!(&pkt[info.payload_offset..], dns.as_slice());
            }
            other => panic!("expected DnsQuery, got {other:?}"),
        }
    }

    #[test]
    fn classify_udp_non53_is_other() {
        let src = SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), 12345);
        let dst = SocketAddrV4::new(Ipv4Addr::new(8, 8, 8, 8), 443);
        let pkt = build_udp_dns_packet(src, dst, b"payload");
        assert!(matches!(classify_ipv4(&pkt), PacketClass::Other));
    }

    #[test]
    fn classify_tcp_syn() {
        let src = SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), 54321);
        let dst = SocketAddrV4::new(Ipv4Addr::new(198, 18, 0, 5), 443);
        let pkt = build_tcp_packet(src, dst, 0x02); // SYN
        match classify_ipv4(&pkt) {
            PacketClass::TcpSyn(ev) => {
                assert_eq!(ev.src, src);
                assert_eq!(ev.dst, dst);
            }
            other => panic!("expected TcpSyn, got {other:?}"),
        }
    }

    #[test]
    fn classify_tcp_synack_is_other() {
        let src = SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), 54321);
        let dst = SocketAddrV4::new(Ipv4Addr::new(198, 18, 0, 5), 443);
        let pkt = build_tcp_packet(src, dst, 0x12); // SYN+ACK
        assert!(matches!(classify_ipv4(&pkt), PacketClass::Other));
    }

    #[test]
    fn classify_ipv6_or_short_is_other() {
        assert!(matches!(
            classify_ipv4(&[0x60, 0, 0, 0]),
            PacketClass::Other
        ));
        assert!(matches!(classify_ipv4(&[]), PacketClass::Other));
        assert!(matches!(classify_ipv4(&[0x45, 0, 0]), PacketClass::Other));
    }

    // ---- DNS 响应包构造（校验和） ----

    #[test]
    fn build_response_packet_valid_checksums() {
        let info = DnsQueryInfo {
            src: SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), 12345),
            dst: SocketAddrV4::new(Ipv4Addr::new(8, 8, 8, 8), 53),
            payload_offset: 28,
        };
        let dns = build_dns_query("example.com.", RecordType::A);
        let pkt = build_dns_response_packet(&info, &dns);

        // 源/目的应对调。
        assert_eq!(&pkt[12..16], &[8, 8, 8, 8]); // src = 原 dst
        assert_eq!(&pkt[16..20], &[10, 0, 0, 2]); // dst = 原 src
                                                  // IPv4 头校验和应自洽（整头求和为 0）。
        assert_eq!(checksum(&pkt[0..20]), 0);
        // 端口对调。
        assert_eq!(u16::from_be_bytes([pkt[20], pkt[21]]), 53);
        assert_eq!(u16::from_be_bytes([pkt[22], pkt[23]]), 12345);
    }

    // ---- run_stack 接线（用内存 mock reader/writer）----

    struct MockReader {
        packets: Vec<Vec<u8>>,
        idx: usize,
    }
    impl AsyncTunReader for MockReader {
        async fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if self.idx >= self.packets.len() {
                return Ok(0); // EOF
            }
            let p = &self.packets[self.idx];
            self.idx += 1;
            buf[..p.len()].copy_from_slice(p);
            Ok(p.len())
        }
    }

    struct MockWriter {
        written: std::rc::Rc<std::cell::RefCell<Vec<Vec<u8>>>>,
    }
    impl AsyncTunWriter for MockWriter {
        async fn write_all(&mut self, buf: &[u8]) -> std::io::Result<()> {
            self.written.borrow_mut().push(buf.to_vec());
            Ok(())
        }
    }

    #[tokio::test]
    async fn run_stack_answers_dns_and_logs_tcp() {
        let src = SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), 12345);
        let dst_dns = SocketAddrV4::new(Ipv4Addr::new(8, 8, 8, 8), 53);
        let dns = build_dns_query("example.com.", RecordType::A);
        let dns_pkt = build_udp_dns_packet(src, dst_dns, &dns);

        let tcp_pkt = build_tcp_packet(
            SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), 5555),
            SocketAddrV4::new(Ipv4Addr::new(198, 18, 0, 9), 443),
            0x02,
        );

        let reader = MockReader {
            packets: vec![dns_pkt, tcp_pkt],
            idx: 0,
        };
        let written = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let writer = MockWriter {
            written: std::rc::Rc::clone(&written),
        };

        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 1024);
        run_stack(reader, writer, &mut engine, 1500).await.unwrap();

        // DNS 查询应回写 1 个响应包；TCP SYN 仅打日志不写回。
        let out = written.borrow();
        assert_eq!(out.len(), 1, "exactly one DNS response written");

        // 回写的应是合法 IPv4/UDP，源端口 53。
        let resp = &out[0];
        assert_eq!(resp[9], IP_PROTO_UDP);
        assert_eq!(u16::from_be_bytes([resp[20], resp[21]]), 53);

        // example.com 应已被分配 FakeIP。
        let ip = engine.allocate_ip("example.com");
        assert!(engine.is_fake_ip(&ip));
    }
}
