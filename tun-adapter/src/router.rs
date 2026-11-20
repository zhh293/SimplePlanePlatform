//! 路由决策引擎模块
//!
//! 基于域名后缀、域名关键词、IP CIDR 和端口进行路由决策，
//! 决定每个连接走代理、直连还是拦截。

use std::collections::HashSet;
use std::net::IpAddr;

use ipnet::IpNet;

use crate::config::RoutingConfig;
use crate::error::RouterError;

/// 路由动作
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RouteAction {
    /// 走 SOCKS5 代理 → proxy-local
    Proxy,
    /// 直连（bypass，从物理网卡出去）
    Direct,
    /// 丢弃（黑洞）
    Reject,
}

/// 连接信息，用于路由决策
#[derive(Debug, Clone)]
pub struct ConnectionInfo {
    /// 源 IP 地址
    pub src_ip: IpAddr,
    /// 目标 IP 地址
    pub dst_ip: IpAddr,
    /// 目标端口
    pub dst_port: u16,
    /// 域名（如果有，从 FakeDNS 反查得到）
    pub domain: Option<String>,
    /// 协议类型
    pub protocol: Protocol,
}

/// 协议类型
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Protocol {
    Tcp,
    Udp,
}

/// 路由规则 trait
pub trait Rule: Send + Sync {
    /// 判断是否匹配，返回匹配后的动作
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction>;
    /// 规则名称（用于日志和调试）
    fn name(&self) -> &str;
}

/// 路由引擎
pub struct Router {
    /// 有序规则列表
    rules: Vec<Box<dyn Rule>>,
    /// 默认动作（所有规则都不匹配时使用）
    default_action: RouteAction,
}

// ===== 规则实现 =====

/// 域名后缀匹配规则
struct DomainSuffixRule {
    suffix: String,
    action: RouteAction,
}

/// 域名关键词匹配规则
struct DomainKeywordRule {
    keyword: String,
    action: RouteAction,
}

/// 域名精确匹配规则
struct DomainFullRule {
    domain: String,
    action: RouteAction,
}

/// IP CIDR 匹配规则
struct IpCidrRule {
    cidr: IpNet,
    action: RouteAction,
}

/// 端口匹配规则
struct PortRule {
    port: u16,
    end: u16,
    action: RouteAction,
}

struct ProtocolRule {
    protocol: Protocol,
    action: RouteAction,
}
struct MatchRule {
    action: RouteAction,
}
struct DomainProviderRule {
    source: String,
    suffixes: HashSet<String>,
    exact: HashSet<String>,
    action: RouteAction,
}

impl DomainProviderRule {
    fn load(source: &str, action: RouteAction) -> Result<Self, RouterError> {
        let contents = match source.trim() {
            "classpath:routing/providers/direct-list.txt" => {
                std::borrow::Cow::Borrowed(include_str!(
                    "../../proxy-local/src/main/resources/routing/providers/direct-list.txt"
                ))
            }
            "classpath:routing/providers/proxy-list.txt" => {
                std::borrow::Cow::Borrowed(include_str!(
                    "../../proxy-local/src/main/resources/routing/providers/proxy-list.txt"
                ))
            }
            path if path.starts_with("classpath:") => {
                return Err(RouterError::InvalidProvider(format!(
                    "Unknown bundled provider: {path}"
                )))
            }
            path => std::borrow::Cow::Owned(
                std::fs::read_to_string(path)
                    .map_err(|e| RouterError::InvalidProvider(format!("{path}: {e}")))?,
            ),
        };
        let mut rule = Self {
            source: source.into(),
            suffixes: HashSet::new(),
            exact: HashSet::new(),
            action,
        };
        for line in contents.lines() {
            let domain = line.split('#').next().unwrap_or("").trim();
            if domain.is_empty() || domain.starts_with(';') {
                continue;
            }
            let (exact, domain) = match domain.strip_prefix("full:") {
                Some(domain) => (true, domain),
                None => (false, domain),
            };
            let domain = domain
                .strip_prefix("*.")
                .or_else(|| domain.strip_prefix("+."))
                .unwrap_or(domain);
            let domain = domain
                .strip_prefix("domain:")
                .or_else(|| domain.strip_prefix("domain_suffix:"))
                .unwrap_or(domain);
            let domain = normalize_domain(domain);
            if domain.is_empty() || domain.parse::<IpAddr>().is_ok() {
                continue;
            }
            if exact {
                rule.exact.insert(domain);
            } else {
                rule.suffixes.insert(domain);
            }
        }
        tracing::info!(
            "Route provider {} loaded: {} suffixes, {} exact domains",
            source,
            rule.suffixes.len(),
            rule.exact.len()
        );
        Ok(rule)
    }
}

impl Rule for DomainProviderRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        let domain = info.domain.as_deref()?;
        if self.exact.contains(domain) {
            return Some(self.action.clone());
        }
        let mut suffix = domain;
        loop {
            if self.suffixes.contains(suffix) {
                return Some(self.action.clone());
            }
            suffix = suffix.split_once('.')?.1;
        }
    }
    fn name(&self) -> &str {
        &self.source
    }
}

impl Rule for ProtocolRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        (info.protocol == self.protocol).then(|| self.action.clone())
    }
    fn name(&self) -> &str {
        "protocol"
    }
}
impl Rule for MatchRule {
    fn matches(&self, _: &ConnectionInfo) -> Option<RouteAction> {
        Some(self.action.clone())
    }
    fn name(&self) -> &str {
        "match"
    }
}

pub(crate) fn normalize_domain(value: &str) -> String {
    value.trim().trim_end_matches('.').to_lowercase()
}

impl Rule for DomainSuffixRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if let Some(domain) = &info.domain {
            // 匹配完整后缀：.cn 匹配 www.baidu.cn，cn 匹配所有 .cn 域名
            if domain
                .strip_suffix(&self.suffix)
                .is_some_and(|prefix| prefix.ends_with('.'))
                || domain == &self.suffix
            {
                return Some(self.action.clone());
            }
        }
        None
    }

    fn name(&self) -> &str {
        &self.suffix
    }
}

impl Rule for DomainKeywordRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if let Some(domain) = &info.domain {
            if domain.contains(&self.keyword) {
                return Some(self.action.clone());
            }
        }
        None
    }

    fn name(&self) -> &str {
        &self.keyword
    }
}

impl Rule for DomainFullRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if let Some(domain) = &info.domain {
            if domain == &self.domain {
                return Some(self.action.clone());
            }
        }
        None
    }

    fn name(&self) -> &str {
        &self.domain
    }
}

impl Rule for IpCidrRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if self.cidr.contains(&info.dst_ip) {
            return Some(self.action.clone());
        }
        None
    }

    fn name(&self) -> &str {
        "ip_cidr"
    }
}

impl Rule for PortRule {
    fn matches(&self, info: &ConnectionInfo) -> Option<RouteAction> {
        if (self.port..=self.end).contains(&info.dst_port) {
            return Some(self.action.clone());
        }
        None
    }

    fn name(&self) -> &str {
        "port"
    }
}

impl Router {
    /// 从配置构建路由引擎
    pub fn from_config(routing_config: &RoutingConfig) -> Result<Self, RouterError> {
        let default_action = parse_action(&routing_config.default_action)?;
        let mut rules: Vec<Box<dyn Rule>> = Vec::new();

        for rule_cfg in &routing_config.rules {
            let action = parse_action(&rule_cfg.action)?;
            let rule: Box<dyn Rule> = match rule_cfg.rule_type.trim().to_lowercase().as_str() {
                "domain_suffix" => Box::new(DomainSuffixRule {
                    suffix: normalize_domain(&rule_cfg.value),
                    action,
                }),
                "domain_keyword" => Box::new(DomainKeywordRule {
                    keyword: normalize_domain(&rule_cfg.value),
                    action,
                }),
                "domain_full" => Box::new(DomainFullRule {
                    domain: normalize_domain(&rule_cfg.value),
                    action,
                }),
                "ip_cidr" | "ip" => {
                    let value = rule_cfg.value.trim();
                    let literal;
                    let value = if !value.contains('/') {
                        let ip = value
                            .trim_matches(['[', ']'])
                            .parse::<IpAddr>()
                            .map_err(|_| RouterError::InvalidCidr(value.into()))?;
                        literal = format!("{}/{}", ip, if ip.is_ipv4() { 32 } else { 128 });
                        literal.as_str()
                    } else {
                        value
                    };
                    let cidr: IpNet = value
                        .parse()
                        .map_err(|_| RouterError::InvalidCidr(rule_cfg.value.clone()))?;
                    Box::new(IpCidrRule { cidr, action })
                }
                "port" => {
                    let value = rule_cfg.value.trim();
                    let (start, end) = value.split_once('-').unwrap_or((value, value));
                    let invalid = || {
                        RouterError::InvalidRuleType(format!("invalid port: {}", rule_cfg.value))
                    };
                    let port: u16 = start.parse().map_err(|_| invalid())?;
                    let end: u16 = end.parse().map_err(|_| invalid())?;
                    if port == 0 || end < port {
                        return Err(invalid());
                    }
                    Box::new(PortRule { port, end, action })
                }
                "protocol" => Box::new(ProtocolRule {
                    protocol: match normalize_domain(&rule_cfg.value).as_str() {
                        "tcp" => Protocol::Tcp,
                        "udp" => Protocol::Udp,
                        other => {
                            return Err(RouterError::InvalidRuleType(format!(
                                "invalid protocol: {other}"
                            )))
                        }
                    },
                    action,
                }),
                "match" => Box::new(MatchRule { action }),
                "domain_provider" => Box::new(DomainProviderRule::load(&rule_cfg.value, action)?),
                other => {
                    return Err(RouterError::InvalidRuleType(other.to_string()));
                }
            };
            rules.push(rule);
        }

        tracing::info!(
            "Router initialized with {} rules, default: {:?}",
            rules.len(),
            default_action
        );
        Ok(Self {
            rules,
            default_action,
        })
    }

    /// 对连接信息进行路由决策（first-match-wins）
    pub fn route(&self, info: &ConnectionInfo) -> RouteAction {
        let normalized = ConnectionInfo {
            domain: info.domain.as_deref().map(normalize_domain),
            src_ip: info.src_ip,
            dst_ip: info.dst_ip,
            dst_port: info.dst_port,
            protocol: info.protocol.clone(),
        };
        let info = &normalized;
        for rule in &self.rules {
            if let Some(action) = rule.matches(info) {
                tracing::debug!(
                    "Route match: {:?}:{} (domain={:?}) -> {:?} (rule: {})",
                    info.dst_ip,
                    info.dst_port,
                    info.domain,
                    action,
                    rule.name()
                );
                return action;
            }
        }

        tracing::debug!(
            "Route no match: {:?}:{} (domain={:?}) -> {:?} (default)",
            info.dst_ip,
            info.dst_port,
            info.domain,
            self.default_action
        );
        self.default_action.clone()
    }
}

/// 解析路由动作字符串
fn parse_action(action: &str) -> Result<RouteAction, RouterError> {
    match action.trim().to_lowercase().as_str() {
        "proxy" => Ok(RouteAction::Proxy),
        "direct" => Ok(RouteAction::Direct),
        "reject" => Ok(RouteAction::Reject),
        other => Err(RouterError::InvalidAction(other.to_string())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{RoutingConfig, RuleConfig};
    use std::net::Ipv4Addr;

    fn make_info(dst_ip: IpAddr, dst_port: u16, domain: Option<&str>) -> ConnectionInfo {
        ConnectionInfo {
            src_ip: IpAddr::V4(Ipv4Addr::new(192, 168, 1, 100)),
            dst_ip,
            dst_port,
            domain: domain.map(|s| s.to_string()),
            protocol: Protocol::Tcp,
        }
    }

    #[test]
    fn test_domain_suffix_rule() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![RuleConfig {
                rule_type: "domain_suffix".to_string(),
                value: "cn".to_string(),
                action: "direct".to_string(),
            }],
        };

        let router = Router::from_config(&config).unwrap();

        // *.cn 域名走直连
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 5)),
            80,
            Some("www.baidu.cn"),
        );
        assert_eq!(router.route(&info), RouteAction::Direct);

        // 非 .cn 域名走代理
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 6)),
            443,
            Some("www.google.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_domain_keyword_rule() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![RuleConfig {
                rule_type: "domain_keyword".to_string(),
                value: "google".to_string(),
                action: "proxy".to_string(),
            }],
        };

        let router = Router::from_config(&config).unwrap();

        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 5)),
            443,
            Some("www.google.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_ip_cidr_rule() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![RuleConfig {
                rule_type: "ip_cidr".to_string(),
                value: "192.168.0.0/16".to_string(),
                action: "direct".to_string(),
            }],
        };

        let router = Router::from_config(&config).unwrap();

        let info = make_info(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 100)), 80, None);
        assert_eq!(router.route(&info), RouteAction::Direct);

        let info = make_info(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 53, None);
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_rule_priority_first_match_wins() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![
                RuleConfig {
                    rule_type: "domain_full".to_string(),
                    value: "special.google.com".to_string(),
                    action: "direct".to_string(),
                },
                RuleConfig {
                    rule_type: "domain_keyword".to_string(),
                    value: "google".to_string(),
                    action: "proxy".to_string(),
                },
            ],
        };

        let router = Router::from_config(&config).unwrap();

        // 精确匹配优先
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 5)),
            443,
            Some("special.google.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Direct);

        // 非精确匹配走第二条规则
        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 18, 0, 6)),
            443,
            Some("www.google.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_no_domain_fallback_to_ip() {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![
                RuleConfig {
                    rule_type: "domain_suffix".to_string(),
                    value: "cn".to_string(),
                    action: "direct".to_string(),
                },
                RuleConfig {
                    rule_type: "ip_cidr".to_string(),
                    value: "10.0.0.0/8".to_string(),
                    action: "direct".to_string(),
                },
            ],
        };

        let router = Router::from_config(&config).unwrap();

        // 无域名但 IP 匹配
        let info = make_info(IpAddr::V4(Ipv4Addr::new(10, 1, 1, 1)), 80, None);
        assert_eq!(router.route(&info), RouteAction::Direct);

        // 无域名且 IP 不匹配，走默认
        let info = make_info(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 80, None);
        assert_eq!(router.route(&info), RouteAction::Proxy);
    }

    #[test]
    fn test_default_action() {
        let config = RoutingConfig {
            default_action: "reject".to_string(),
            rules: vec![],
        };

        let router = Router::from_config(&config).unwrap();

        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)),
            80,
            Some("unknown.example.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Reject);
    }

    // =====================================================================
    // 《项目测试计划与清单》§4.2 + §6 回归用例
    //
    // 系统在 TUN 模式下的 bypass 规则（见 route_guard.rs::setup）会把
    // proxy-remote 出口 IP 与内网 extra_cidrs 以 `ip_cidr -> direct` 注入路由表，
    // 普通公网走默认 `proxy`。下面用与生产一致的规则组合来锁定这三类决策，
    // 尤其是 TC-REG-001：proxy-remote IP 若未被判为 direct 会导致“路由环路 / 全网不通”。
    // =====================================================================

    /// 构造一份与 TUN 模式 bypass 等价的路由配置：
    /// proxy-remote IP 与内网网段走 direct，默认走 proxy。
    fn bypass_like_router(proxy_remote_ip: &str, extra_cidr: &str) -> Router {
        let config = RoutingConfig {
            default_action: "proxy".to_string(),
            rules: vec![
                RuleConfig {
                    rule_type: "ip_cidr".to_string(),
                    value: format!("{}/32", proxy_remote_ip),
                    action: "direct".to_string(),
                },
                RuleConfig {
                    rule_type: "ip_cidr".to_string(),
                    value: extra_cidr.to_string(),
                    action: "direct".to_string(),
                },
            ],
        };
        Router::from_config(&config).unwrap()
    }

    /// TC-REG-001 / TC-TUN-001 [P0]：proxy-remote 出口 IP 必须判为 direct（bypass），
    /// 绝不能再进 TUN 走 proxy，否则会形成“代理流量再次被劫持”的路由环路。
    #[test]
    fn test_reg001_proxy_remote_ip_must_be_direct() {
        let router = bypass_like_router("203.0.113.10", "10.0.0.0/8");

        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)),
            9090, // proxy-remote 监听端口
            None, // 出站到 remote 时按 IP 决策，无域名
        );
        assert_eq!(
            router.route(&info),
            RouteAction::Direct,
            "proxy-remote IP 必须 bypass（direct），否则触发路由环路全网不通"
        );
    }

    /// TC-TUN-002 [P0]：内网网段（extra_cidrs）走 direct，保证开启 TUN 后内网仍可达。
    #[test]
    fn test_tun002_intranet_cidr_is_direct() {
        let router = bypass_like_router("203.0.113.10", "10.0.0.0/8");

        let info = make_info(IpAddr::V4(Ipv4Addr::new(10, 12, 34, 56)), 22, None);
        assert_eq!(
            router.route(&info),
            RouteAction::Direct,
            "内网网段必须走 direct，否则内网资源不可达"
        );
    }

    /// TC-TUN-003 [P0]：普通公网 IP（既非 remote 也非内网）走 proxy（默认动作）。
    #[test]
    fn test_tun003_public_ip_is_proxy() {
        let router = bypass_like_router("203.0.113.10", "10.0.0.0/8");

        let info = make_info(IpAddr::V4(Ipv4Addr::new(142, 250, 72, 14)), 443, None);
        assert_eq!(
            router.route(&info),
            RouteAction::Proxy,
            "普通公网 IP 应走 proxy（默认动作）"
        );
    }

    /// 补充：proxy-remote IP 即使带任意域名/端口也应稳定 direct（first-match-wins 不被域名误导）。
    #[test]
    fn test_reg001_proxy_remote_ip_direct_regardless_of_domain() {
        let router = bypass_like_router("198.51.100.7", "192.168.0.0/16");

        let info = make_info(
            IpAddr::V4(Ipv4Addr::new(198, 51, 100, 7)),
            9090,
            Some("my-remote.example.com"),
        );
        assert_eq!(router.route(&info), RouteAction::Direct);
    }
}
