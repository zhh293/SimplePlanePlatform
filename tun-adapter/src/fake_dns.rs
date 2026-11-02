//! FakeDNS 引擎模块
//!
//! 拦截 DNS 查询请求，分配假 IP 地址并维护双向映射表。

use std::net::Ipv4Addr;
use std::num::NonZeroUsize;

use lru::LruCache;

use crate::error::DnsError;

/// FakeDNS 引擎：管理域名与假 IP 的双向映射
pub struct FakeDnsEngine {
    /// IP → 域名 映射
    ip_to_domain: LruCache<Ipv4Addr, String>,
    /// 域名 → IP 映射
    domain_to_ip: LruCache<String, Ipv4Addr>,
    /// IP 池起始地址（数值形式）
    pool_start: u32,
    /// IP 池结束地址（数值形式）
    pool_end: u32,
    /// 下一个可分配的 IP（数值形式）
    next_ip: u32,
}

impl FakeDnsEngine {
    /// 创建新的 FakeDNS 引擎
    ///
    /// # Arguments
    /// * `pool_cidr` - FakeIP 地址池的 CIDR 范围，如 "198.18.0.0/15"
    /// * `capacity` - LRU 缓存容量
    pub fn new(pool_cidr: &str, capacity: usize) -> Self {
        let network: ipnet::Ipv4Net = pool_cidr
            .parse()
            .expect("invalid FakeIP CIDR");

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

    /// 为域名分配一个 FakeIP（如果已分配则返回已有的）
    pub fn allocate_ip(&mut self, domain: &str) -> Ipv4Addr {
        // 如果域名已有映射，直接返回
        if let Some(&ip) = self.domain_to_ip.get(domain) {
            return ip;
        }

        // 分配新 IP
        let ip = self.next_available_ip();
        let domain_owned = domain.to_lowercase();

        // 如果该 IP 之前被其他域名使用，清理旧映射
        if let Some(old_domain) = self.ip_to_domain.pop(&ip) {
            self.domain_to_ip.pop(&old_domain);
        }

        self.ip_to_domain.put(ip, domain_owned.clone());
        self.domain_to_ip.put(domain_owned, ip);

        tracing::debug!("FakeDNS: allocated {} -> {}", domain, ip);
        ip
    }

    /// 获取下一个可用的 IP 地址，跳过 .0 和 .255
    fn next_available_ip(&mut self) -> Ipv4Addr {
        loop {
            let ip = Ipv4Addr::from(self.next_ip);
            self.next_ip += 1;

            // 循环分配：到达池尾回到池头
            if self.next_ip > self.pool_end {
                self.next_ip = self.pool_start;
            }

            // 跳过 .0 和 .255 地址
            let octets = ip.octets();
            // Reserve the interface, FakeDNS endpoint and Windows peer address.
            if octets[3] == 0 || octets[3] == 255
                || ip == Ipv4Addr::new(198, 18, 0, 1)
                || ip == Ipv4Addr::new(198, 18, 0, 2)
                || ip == Ipv4Addr::new(198, 18, 255, 254) {
                continue;
            }

            return ip;
        }
    }

    /// 处理 DNS 查询的原始 UDP payload，返回 DNS 响应的原始 bytes
    pub fn handle_dns_query(&mut self, query_payload: &[u8]) -> Result<Vec<u8>, DnsError> {
        use hickory_proto::op::{Header, Message, OpCode, ResponseCode};
        use hickory_proto::rr::{DNSClass, RData, Record, RecordType};
        use hickory_proto::serialize::binary::BinDecodable;

        let request = Message::from_bytes(query_payload)
            .map_err(|e| DnsError::ParseError(format!("{}", e)))?;

        let mut response = Message::new();
        let mut header = Header::response_from_request(request.header());
        header.set_recursion_available(true);
        header.set_op_code(OpCode::Query);
        header.set_response_code(ResponseCode::NoError);
        response.set_header(header);

        // 复制查询部分到响应
        for query in request.queries() {
            response.add_query(query.clone());

            match query.query_type() {
                RecordType::A => {
                    // A 记录查询：分配 FakeIP
                    let domain = query.name().to_string().trim_end_matches('.').to_lowercase();
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
                    // AAAA 查询：返回空 answer（MVP 不支持 IPv6 FakeIP）
                    // 不添加 answer，返回空响应即可
                    tracing::debug!("FakeDNS: AAAA query for {}, returning empty", query.name());
                }
                other => {
                    tracing::debug!("FakeDNS: unsupported query type {:?}, returning empty", other);
                }
            }
        }

        let response_bytes = response.to_vec()
            .map_err(|e| DnsError::EncodeError(format!("{}", e)))?;

        Ok(response_bytes)
    }

    /// 根据假 IP 反查域名
    pub fn lookup_domain(&self, fake_ip: &Ipv4Addr) -> Option<&str> {
        self.ip_to_domain.peek(fake_ip).map(|s| s.as_str())
    }

    /// 判断一个 IP 是否在 FakeIP 池范围内
    pub fn is_fake_ip(&self, ip: &Ipv4Addr) -> bool {
        let ip_val = u32::from(*ip);
        ip_val >= self.pool_start && ip_val <= self.pool_end
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

    #[test]
    fn test_reserved_endpoints_never_allocated_after_wraparound() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/24", 256);
        for i in 0..600 {
            let ip = engine.allocate_ip(&format!("site{i}.example.com"));
            assert_ne!(ip, Ipv4Addr::new(198, 18, 0, 1));
            assert_ne!(ip, Ipv4Addr::new(198, 18, 0, 2));
        }
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 256);
        engine.next_ip = u32::from(Ipv4Addr::new(198, 18, 255, 254));
        assert_eq!(engine.allocate_ip("peer.example.com"), Ipv4Addr::new(198, 19, 0, 1));
    }

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
    fn test_pool_wraparound() {
        // 使用一个非常小的池来测试循环分配
        let mut engine = FakeDnsEngine::new("198.18.0.0/24", 256);
        let mut ips = Vec::new();

        // 分配足够多的 IP 触发循环
        for i in 0..300 {
            let domain = format!("test{}.example.com", i);
            let ip = engine.allocate_ip(&domain);
            ips.push(ip);
        }

        // 不应该 panic
        assert!(!ips.is_empty());
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

        assert_eq!(response.id(), 0x1234);
        assert_eq!(response.message_type(), MessageType::Response);
        assert_eq!(response.answers().len(), 0); // AAAA 返回空
    }

    #[test]
    fn test_skips_dot_zero_and_255() {
        let mut engine = FakeDnsEngine::new("198.18.0.0/15", 65536);

        // 分配大量 IP，确保没有 .0 和 .255
        for i in 0..1000 {
            let domain = format!("test{}.example.com", i);
            let ip = engine.allocate_ip(&domain);
            let octets = ip.octets();
            assert_ne!(octets[3], 0, "Got .0 address: {}", ip);
            assert_ne!(octets[3], 255, "Got .255 address: {}", ip);
        }
    }
}
