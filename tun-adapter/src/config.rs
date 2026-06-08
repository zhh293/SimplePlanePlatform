//! 配置结构体定义与 TOML 反序列化
//!
//! 对应 `config/tun.toml` 中的所有配置字段，使用 `serde(default)` 提供合理默认值。

use std::net::{Ipv4Addr, SocketAddr};
use std::path::Path;

use serde::Deserialize;

use crate::error::TunError;

/// 顶级配置结构体
#[derive(Debug, Clone, Deserialize)]
pub struct Config {
    /// TUN 设备配置
    #[serde(default)]
    pub tun: TunConfig,

    /// FakeIP 配置
    #[serde(default)]
    pub fakeip: FakeIpConfig,

    /// 代理配置
    #[serde(default)]
    pub proxy: ProxyConfig,

    /// 路由规则配置
    #[serde(default)]
    pub routing: RoutingConfig,

    /// 日志配置
    #[serde(default)]
    pub log: LogConfig,

    /// 回环防护配置
    #[serde(default)]
    pub bypass: BypassConfig,

    /// 内网 DNS 分流配置
    #[serde(default)]
    pub intranet_dns: IntranetDnsConfig,
}

/// TUN 设备配置
#[derive(Debug, Clone, Deserialize)]
pub struct TunConfig {
    /// TUN 设备名称
    #[serde(default = "default_tun_name")]
    pub name: String,

    /// TUN 设备 IP 地址
    #[serde(default = "default_tun_address")]
    pub address: Ipv4Addr,

    /// 子网掩码
    #[serde(default = "default_tun_netmask")]
    pub netmask: String,

    /// MTU 值
    #[serde(default = "default_mtu")]
    pub mtu: u16,

    /// 是否启用
    #[serde(default = "default_true")]
    pub enabled: bool,
}

/// FakeIP 配置
#[derive(Debug, Clone, Deserialize)]
pub struct FakeIpConfig {
    /// FakeIP 地址池范围（CIDR 格式）
    #[serde(default = "default_fakeip_range")]
    pub range: String,

    /// LRU 缓存容量
    #[serde(default = "default_fakeip_capacity")]
    pub capacity: usize,
}

/// 代理配置
#[derive(Debug, Clone, Deserialize)]
pub struct ProxyConfig {
    /// SOCKS5 代理地址
    #[serde(default = "default_socks5_addr")]
    pub socks5_addr: String,

    /// 健康检查间隔（秒）
    #[serde(default = "default_health_check_interval")]
    pub health_check_interval: u64,

    /// 健康检查连续失败阈值
    #[serde(default = "default_health_failure_threshold")]
    pub health_failure_threshold: u32,
}

/// 路由规则配置
#[derive(Debug, Clone, Deserialize)]
pub struct RoutingConfig {
    /// 默认路由动作
    #[serde(default = "default_route_action")]
    pub default_action: String,

    /// 路由规则列表
    #[serde(default)]
    pub rules: Vec<RuleConfig>,
}

/// 单条路由规则配置
#[derive(Debug, Clone, Deserialize)]
pub struct RuleConfig {
    /// 规则类型: domain_suffix, domain_keyword, domain_full, ip_cidr, port
    #[serde(rename = "type")]
    pub rule_type: String,

    /// 规则匹配值
    pub value: String,

    /// 匹配后的动作: proxy, direct, reject
    pub action: String,
}

/// 日志配置
#[derive(Debug, Clone, Deserialize)]
pub struct LogConfig {
    /// 日志级别: trace, debug, info, warn, error
    #[serde(default = "default_log_level")]
    pub level: String,

    /// 日志格式: pretty, json, compact
    #[serde(default = "default_log_format")]
    pub format: String,
}

/// 内网 DNS 分流配置
///
/// macOS: 通过 `/etc/resolver/` 机制分流
/// Windows: 通过 NRPT (Name Resolution Policy Table) 分流
/// 让指定域名的 DNS 查询走内网 DNS 服务器（bypass 网段），不经过 TUN/FakeDNS。
#[derive(Debug, Clone, Deserialize)]
pub struct IntranetDnsConfig {
    /// 内网 DNS 服务器 IP 列表
    #[serde(default)]
    pub servers: Vec<String>,

    /// 需要走内网 DNS 的域名后缀列表
    #[serde(default)]
    pub domains: Vec<String>,
}

impl Default for IntranetDnsConfig {
    fn default() -> Self {
        Self {
            servers: Vec::new(),
            domains: Vec::new(),
        }
    }
}

/// 回环防护配置
#[derive(Debug, Clone, Deserialize)]
pub struct BypassConfig {
    /// proxy-remote 的真实 IP 列表（需要排除路由）
    #[serde(default)]
    pub proxy_remote_ips: Vec<String>,

    /// 额外排除的 CIDR 网段
    #[serde(default = "default_extra_cidrs")]
    pub extra_cidrs: Vec<String>,

    /// DNS bypass IP 列表（这些 DNS 服务器的流量不走 TUN，供 proxy-local 直连使用）
    #[serde(default = "default_dns_bypass_ips")]
    pub dns_bypass_ips: Vec<String>,
}

// ===== 默认值函数 =====

fn default_tun_name() -> String {
    #[cfg(target_os = "macos")]
    {
        "utun9".to_string()
    }
    #[cfg(target_os = "windows")]
    {
        "SimplePlane".to_string()
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        "tun0".to_string()
    }
}

fn default_tun_address() -> Ipv4Addr {
    Ipv4Addr::new(198, 18, 0, 1)
}

fn default_tun_netmask() -> String {
    "255.254.0.0".to_string()
}

fn default_mtu() -> u16 {
    1500
}

fn default_true() -> bool {
    true
}

fn default_fakeip_range() -> String {
    "198.18.0.0/15".to_string()
}

fn default_fakeip_capacity() -> usize {
    65536
}

fn default_socks5_addr() -> String {
    "127.0.0.1:1080".to_string()
}

fn default_health_check_interval() -> u64 {
    5
}

fn default_health_failure_threshold() -> u32 {
    3
}

fn default_route_action() -> String {
    "proxy".to_string()
}

fn default_log_level() -> String {
    "info".to_string()
}

fn default_log_format() -> String {
    "pretty".to_string()
}

fn default_extra_cidrs() -> Vec<String> {
    vec![
        "10.0.0.0/8".to_string(),
        "172.16.0.0/12".to_string(),
        "192.168.0.0/16".to_string(),
    ]
}

fn default_dns_bypass_ips() -> Vec<String> {
    vec![
        "114.114.114.114".to_string(),
        "223.5.5.5".to_string(),
    ]
}

// ===== Default trait 实现 =====

impl Default for TunConfig {
    fn default() -> Self {
        Self {
            name: default_tun_name(),
            address: default_tun_address(),
            netmask: default_tun_netmask(),
            mtu: default_mtu(),
            enabled: default_true(),
        }
    }
}

impl Default for FakeIpConfig {
    fn default() -> Self {
        Self {
            range: default_fakeip_range(),
            capacity: default_fakeip_capacity(),
        }
    }
}

impl Default for ProxyConfig {
    fn default() -> Self {
        Self {
            socks5_addr: default_socks5_addr(),
            health_check_interval: default_health_check_interval(),
            health_failure_threshold: default_health_failure_threshold(),
        }
    }
}

impl Default for RoutingConfig {
    fn default() -> Self {
        Self {
            default_action: default_route_action(),
            rules: Vec::new(),
        }
    }
}

impl Default for LogConfig {
    fn default() -> Self {
        Self {
            level: default_log_level(),
            format: default_log_format(),
        }
    }
}

impl Default for BypassConfig {
    fn default() -> Self {
        Self {
            proxy_remote_ips: Vec::new(),
            extra_cidrs: default_extra_cidrs(),
            dns_bypass_ips: default_dns_bypass_ips(),
        }
    }
}

impl Config {
    /// 从 TOML 文件加载配置
    pub fn load(path: &Path) -> Result<Self, TunError> {
        let content = std::fs::read_to_string(path).map_err(|e| {
            TunError::Config(format!("failed to read config file {:?}: {}", path, e))
        })?;

        let config: Config = toml::from_str(&content).map_err(|e| {
            TunError::Config(format!("failed to parse config file {:?}: {}", path, e))
        })?;

        config.validate()?;
        Ok(config)
    }

    /// 校验配置合法性
    fn validate(&self) -> Result<(), TunError> {
        // 校验 SOCKS5 地址格式
        self.proxy
            .socks5_addr
            .parse::<SocketAddr>()
            .map_err(|e| TunError::Config(format!("invalid socks5_addr: {}", e)))?;

        // 校验 FakeIP 范围格式
        self.fakeip
            .range
            .parse::<ipnet::Ipv4Net>()
            .map_err(|e| TunError::Config(format!("invalid fakeip range: {}", e)))?;

        // 校验 MTU 值
        if self.tun.mtu < 576 || self.tun.mtu > 9000 {
            return Err(TunError::Config(format!(
                "invalid MTU value {}: must be between 576 and 9000",
                self.tun.mtu
            )));
        }

        // 校验路由规则
        for rule in &self.routing.rules {
            match rule.rule_type.as_str() {
                "domain_suffix" | "domain_keyword" | "domain_full" | "ip_cidr" | "port" => {}
                other => {
                    return Err(TunError::Config(format!("unknown rule type: {}", other)));
                }
            }
            match rule.action.as_str() {
                "proxy" | "direct" | "reject" => {}
                other => {
                    return Err(TunError::Config(format!("unknown route action: {}", other)));
                }
            }
        }

        Ok(())
    }

    /// 获取解析后的 SOCKS5 地址
    pub fn socks5_socket_addr(&self) -> SocketAddr {
        self.proxy
            .socks5_addr
            .parse()
            .expect("socks5_addr already validated")
    }

    /// 打印配置摘要
    pub fn print_summary(&self) {
        tracing::info!("=== TUN Adapter Configuration ===");
        tracing::info!("TUN device: {} ({})", self.tun.name, self.tun.address);
        tracing::info!("TUN netmask: {}, MTU: {}", self.tun.netmask, self.tun.mtu);
        tracing::info!("FakeIP range: {}, capacity: {}", self.fakeip.range, self.fakeip.capacity);
        tracing::info!("SOCKS5 proxy: {}", self.proxy.socks5_addr);
        tracing::info!("Health check: {}s interval, {} failures threshold",
            self.proxy.health_check_interval, self.proxy.health_failure_threshold);
        tracing::info!("Routing: {} rules, default action: {}",
            self.routing.rules.len(), self.routing.default_action);
        tracing::info!("Log level: {}, format: {}", self.log.level, self.log.format);
        tracing::info!("=================================");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn test_load_default_config() {
        let toml_content = r#"
[tun]
name = "utun9"
address = "198.18.0.1"
netmask = "255.254.0.0"
mtu = 1500

[fakeip]
range = "198.18.0.0/15"
capacity = 65536

[proxy]
socks5_addr = "127.0.0.1:1080"
health_check_interval = 5
health_failure_threshold = 3

[routing]
default_action = "proxy"

[log]
level = "info"
format = "pretty"
"#;
        let mut tmp = tempfile::NamedTempFile::new().unwrap();
        tmp.write_all(toml_content.as_bytes()).unwrap();

        let config = Config::load(tmp.path()).unwrap();
        assert_eq!(config.tun.name, "utun9");
        assert_eq!(config.tun.mtu, 1500);
        assert_eq!(config.proxy.socks5_addr, "127.0.0.1:1080");
    }

    #[test]
    fn test_invalid_mtu() {
        let toml_content = r#"
[tun]
mtu = 100

[proxy]
socks5_addr = "127.0.0.1:1080"

[fakeip]
range = "198.18.0.0/15"
"#;
        let mut tmp = tempfile::NamedTempFile::new().unwrap();
        tmp.write_all(toml_content.as_bytes()).unwrap();

        let result = Config::load(tmp.path());
        assert!(result.is_err());
    }

    #[test]
    fn test_invalid_socks5_addr() {
        let toml_content = r#"
[proxy]
socks5_addr = "not_an_address"

[fakeip]
range = "198.18.0.0/15"
"#;
        let mut tmp = tempfile::NamedTempFile::new().unwrap();
        tmp.write_all(toml_content.as_bytes()).unwrap();

        let result = Config::load(tmp.path());
        assert!(result.is_err());
    }
}
