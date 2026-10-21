use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// 代理配置（对应 proxy.yml 的结构）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyConfig {
    pub local: LocalConfig,
    pub remote: RemoteConfig,
    #[serde(default)]
    pub nodes: Vec<RemoteNode>,
    #[serde(default)]
    pub selected_node_id: String,
    #[serde(default)]
    pub route: RouteConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteNode {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(flatten)]
    pub remote: RemoteConfig,
}

impl ProxyConfig {
    pub fn normalize_nodes(&mut self) -> Result<(), String> {
        if self.nodes.is_empty() && self.remote.transport == "http3" {
            self.nodes.push(RemoteNode { id: "legacy-node".into(), name: String::new(), remote: self.remote.clone() });
            self.selected_node_id = "legacy-node".into();
        }
        if self.nodes.is_empty() { return Ok(()); }
        let mut ids = std::collections::HashSet::new();
        for node in &self.nodes {
            if node.id.trim().is_empty() || !ids.insert(node.id.clone()) {
                return Err("节点 ID 不能为空或重复".into());
            }
            if node.remote.transport != "http3" || node.remote.host.trim().is_empty() || node.remote.port == 0 {
                return Err("手动多节点目前仅支持有效的 HTTP/3 节点".into());
            }
        }
        self.remote = self.nodes.iter().find(|node| node.id == self.selected_node_id)
            .ok_or("请选择一个存在的节点")?.remote.clone();
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocalConfig {
    #[serde(default = "default_socks_port")]
    pub port: u16,
    #[serde(default = "default_http_enabled")]
    pub http_proxy_enabled: bool,
    #[serde(default = "default_http_port")]
    pub http_proxy_port: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteConfig {
    pub host: String,
    pub port: u16,
    #[serde(default = "default_cipher")]
    pub cipher: String,
    #[serde(default)]
    pub key: String,
    #[serde(default = "default_transport")]
    pub transport: String,
    #[serde(default)]
    pub http3: Http3Config,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Http3Config {
    #[serde(default = "default_http3_server_name", alias = "serverName")]
    pub server_name: String,
    #[serde(default = "default_http3_ca_file", alias = "caFile")]
    pub ca_file: String,
    #[serde(default = "default_http3_certificate_pin", alias = "certificatePin")]
    pub certificate_pin: String,
    #[serde(default = "default_http3_handshake_timeout_ms", alias = "handshakeTimeoutMs")]
    pub handshake_timeout_ms: u64,
    #[serde(default = "default_http3_idle_timeout_ms", alias = "idleTimeoutMs")]
    pub idle_timeout_ms: u64,
    #[serde(default = "default_http3_max_streams", alias = "maxStreams")]
    pub max_streams: u64,
    #[serde(default = "default_http3_initial_connection_window", alias = "initialConnectionWindow")]
    pub initial_connection_window: u64,
    #[serde(default = "default_http3_initial_stream_window", alias = "initialStreamWindow")]
    pub initial_stream_window: u64,
    #[serde(default = "default_http3_max_proxy_message_bytes", alias = "maxProxyMessageBytes")]
    pub max_proxy_message_bytes: u64,
    #[serde(default = "default_http3_stream_pending_hard_limit", alias = "streamPendingHardLimit")]
    pub stream_pending_hard_limit: u64,
    #[serde(default = "default_http3_connection_pending_hard_limit", alias = "connectionPendingHardLimit")]
    pub connection_pending_hard_limit: u64,
    #[serde(default = "default_http3_write_buffer_low_water_mark", alias = "writeBufferLowWaterMark")]
    pub write_buffer_low_water_mark: u64,
    #[serde(default = "default_http3_write_buffer_high_water_mark", alias = "writeBufferHighWaterMark")]
    pub write_buffer_high_water_mark: u64,
}

impl Default for Http3Config {
    fn default() -> Self {
        Self {
            server_name: default_http3_server_name(),
            ca_file: default_http3_ca_file(),
            certificate_pin: default_http3_certificate_pin(),
            handshake_timeout_ms: default_http3_handshake_timeout_ms(),
            idle_timeout_ms: default_http3_idle_timeout_ms(),
            max_streams: default_http3_max_streams(),
            initial_connection_window: default_http3_initial_connection_window(),
            initial_stream_window: default_http3_initial_stream_window(),
            max_proxy_message_bytes: default_http3_max_proxy_message_bytes(),
            stream_pending_hard_limit: default_http3_stream_pending_hard_limit(),
            connection_pending_hard_limit: default_http3_connection_pending_hard_limit(),
            write_buffer_low_water_mark: default_http3_write_buffer_low_water_mark(),
            write_buffer_high_water_mark: default_http3_write_buffer_high_water_mark(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RouteConfig {
    #[serde(default = "default_route_mode")]
    pub default_route: String,
    #[serde(default)]
    pub proxy_list: Vec<String>,
    #[serde(default)]
    pub direct_list: Vec<String>,
    #[serde(default = "default_direct_providers")]
    pub direct_providers: Vec<String>,
    #[serde(default = "default_proxy_providers")]
    pub proxy_providers: Vec<String>,
    #[serde(default)]
    pub system_direct_list: Vec<String>,
    #[serde(default)]
    pub rules: Vec<RouteRuleConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RouteRuleConfig {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(rename = "type")]
    pub rule_type: String,
    pub value: String,
    pub action: String,
    #[serde(default)]
    pub priority: i32,
}

fn default_socks_port() -> u16 { 1080 }
fn default_http_enabled() -> bool { true }
fn default_http_port() -> u16 { 1080 }
fn default_cipher() -> String { "chacha20".to_string() }
pub fn default_cipher_key() -> String { "your-cipher-key".to_string() }
fn default_route_mode() -> String { "proxy".to_string() }
fn default_direct_providers() -> Vec<String> {
    vec!["classpath:routing/providers/direct-list.txt".to_string()]
}
fn default_proxy_providers() -> Vec<String> {
    vec!["classpath:routing/providers/proxy-list.txt".to_string()]
}
fn default_transport() -> String { "http3".to_string() }
fn default_http3_server_name() -> String { "54.172.101.190".to_string() }
fn default_http3_ca_file() -> String { "http3-remote-ca.crt".to_string() }
fn default_http3_certificate_pin() -> String { String::new() }
fn default_http3_handshake_timeout_ms() -> u64 { 5000 }
fn default_http3_idle_timeout_ms() -> u64 { 60000 }
fn default_http3_max_streams() -> u64 { 1000 }
fn default_http3_initial_connection_window() -> u64 { 16777216 }
fn default_http3_initial_stream_window() -> u64 { 1048576 }
fn default_http3_max_proxy_message_bytes() -> u64 { 8388608 }
fn default_http3_stream_pending_hard_limit() -> u64 { 4194304 }
fn default_http3_connection_pending_hard_limit() -> u64 { 67108864 }
fn default_http3_write_buffer_low_water_mark() -> u64 { 262144 }
fn default_http3_write_buffer_high_water_mark() -> u64 { 1048576 }

/// TUN 配置（对应 tun.toml）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunConfig {
    #[serde(default)]
    pub tun: TunSection,
    #[serde(default)]
    pub dns: DnsSection,
    #[serde(default)]
    pub proxy: TunProxySection,
    #[serde(default)]
    pub routing: Option<RoutingSection>,
    #[serde(default)]
    pub bypass: Option<BypassSection>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunSection {
    #[serde(default = "default_tun_name")]
    pub name: String,
    #[serde(default = "default_tun_address")]
    pub address: String,
    #[serde(default = "default_tun_netmask")]
    pub netmask: Option<String>,
    #[serde(default = "default_tun_mtu")]
    pub mtu: u16,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

impl Default for TunSection {
    fn default() -> Self {
        Self {
            name: default_tun_name(),
            address: default_tun_address(),
            netmask: Some("255.254.0.0".to_string()),
            mtu: default_tun_mtu(),
            enabled: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DnsSection {
    #[serde(default = "default_dns_listen")]
    pub listen: String,
    #[serde(default = "default_dns_upstream")]
    pub upstream: String,
}

impl Default for DnsSection {
    fn default() -> Self {
        Self {
            listen: default_dns_listen(),
            upstream: default_dns_upstream(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunProxySection {
    #[serde(default = "default_proxy_socks")]
    pub socks5: Option<String>,
    #[serde(default)]
    pub socks5_addr: Option<String>,
}

impl Default for TunProxySection {
    fn default() -> Self {
        Self {
            socks5: default_proxy_socks(),
            socks5_addr: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoutingSection {
    #[serde(default = "default_route_proxy")]
    pub default_action: String,
    #[serde(default)]
    pub rules: Vec<RoutingRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoutingRule {
    #[serde(rename = "type")]
    pub rule_type: String,
    pub value: String,
    pub action: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BypassSection {
    #[serde(default)]
    pub proxy_remote_ips: Vec<String>,
    #[serde(default)]
    pub extra_cidrs: Vec<String>,
    #[serde(default)]
    pub dns_bypass_ips: Vec<String>,
}

fn default_tun_name() -> String {
    #[cfg(target_os = "windows")]
    {
        return "SimplePlane".to_string();
    }

    #[cfg(not(target_os = "windows"))]
    {
        "utun9".to_string()
    }
}
fn default_tun_address() -> String { "198.18.0.1".to_string() }
fn default_tun_netmask() -> Option<String> { Some("255.254.0.0".to_string()) }
fn default_tun_mtu() -> u16 { 1500 }
fn default_true() -> bool { true }
fn default_dns_listen() -> String { "127.0.0.1:53".to_string() }
fn default_dns_upstream() -> String { "8.8.8.8:53".to_string() }
fn default_proxy_socks() -> Option<String> { Some("127.0.0.1:1080".to_string()) }
fn default_route_proxy() -> String { "proxy".to_string() }

/// 获取用户配置目录
pub fn get_config_dir() -> PathBuf {
    let base = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
    let dir = base.join("SimplePlane");
    if !dir.exists() {
        let _ = fs::create_dir_all(&dir);
    }
    dir
}

/// 读取代理配置
pub fn load_proxy_config() -> Result<ProxyConfig, String> {
    let config_path = get_config_dir().join("proxy.yml");

    if !config_path.exists() {
        // 首次运行，写入默认配置
        let default_config = get_default_proxy_config();
        save_proxy_config(&default_config)?;
        return Ok(default_config);
    }

    let content =
        fs::read_to_string(&config_path).map_err(|e| format!("Failed to read config: {}", e))?;

    let mut config: ProxyConfig = serde_yaml::from_str(&content).map_err(|e| format!("Failed to parse config: {}", e))?;
    config.normalize_nodes()?;
    Ok(config)
}

/// 保存代理配置
pub fn save_proxy_config(config: &ProxyConfig) -> Result<(), String> {
    let mut config = config.clone();
    config.normalize_nodes()?;
    let config_path = get_config_dir().join("proxy.yml");

    let content =
        serde_yaml::to_string(&config).map_err(|e| format!("Failed to serialize config: {}", e))?;

    atomic_write(&config_path, &content)?;
    atomic_write(&get_config_dir().join("proxy-java.yml"), &generate_java_compatible_yaml(&config))
}

fn atomic_write(path: &std::path::Path, content: &str) -> Result<(), String> {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let temporary = path.with_extension(format!("tmp-{}-{}", std::process::id(), NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)));
    fs::write(&temporary, content).map_err(|e| format!("Failed to write configuration: {}", e))?;
    if let Err(e) = fs::rename(&temporary, path) {
        let _ = fs::remove_file(&temporary);
        return Err(format!("Failed to replace configuration: {}", e));
    }
    Ok(())
}

pub fn import_node_certificate(directory: &std::path::Path, pem: &str) -> Result<String, String> {
    let pem = pem.trim();
    if pem.len() > 262144 || !pem.starts_with("-----BEGIN CERTIFICATE-----")
        || !pem.ends_with("-----END CERTIFICATE-----") || pem.contains("PRIVATE KEY") {
        return Err("请选择 PEM 格式的公开证书（.pem/.crt），不要导入私钥；最大 256 KiB".into());
    }
    fs::create_dir_all(directory).map_err(|e| format!("无法创建证书目录: {}", e))?;
    let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| e.to_string())?.as_nanos();
    // Each import has its own path, so saving a replacement also triggers the config watcher.
    let path = directory.join(format!("node-{}.pem", stamp));
    use std::io::Write;
    let mut file = fs::OpenOptions::new().write(true).create_new(true).open(&path)
        .map_err(|e| format!("无法保存证书: {}", e))?;
    file.write_all(pem.as_bytes()).map_err(|e| format!("无法保存证书: {}", e))?;
    Ok(path.to_string_lossy().into_owned())
}

pub fn read_node_status() -> Option<serde_json::Value> {
    let text = fs::read_to_string(get_config_dir().join("proxy-java.yml.status.json")).ok()?;
    let status: serde_json::Value = serde_json::from_str(&text).ok()?;
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).ok()?.as_millis() as u64;
    let updated = status.get("updatedAt")?.as_u64()?;
    if status.get("running")?.as_bool()? && now.saturating_sub(updated) < 10000 { Some(status) } else { None }
}

pub fn write_tun_node_config() -> Result<PathBuf, String> {
    use std::net::ToSocketAddrs;
    let mut value: toml::Value = toml::from_str(&load_tun_config_raw()?).map_err(|e| format!("Invalid TUN config: {}", e))?;
    let proxy = load_proxy_config()?;
    let hosts: Vec<String> = if proxy.nodes.is_empty() { vec![proxy.remote.host] }
        else { proxy.nodes.iter().map(|node| node.remote.host.clone()).collect() };
    let mut ips = std::collections::BTreeSet::new();
    for host in hosts {
        for address in (host.as_str(), 0).to_socket_addrs().map_err(|e| format!("Cannot resolve node address: {}", e))? {
            ips.insert(address.ip().to_string());
        }
    }
    let root = value.as_table_mut().ok_or("TUN configuration must be a table")?;
    if !root.contains_key("bypass") { root.insert("bypass".into(), toml::Value::Table(toml::map::Map::new())); }
    let bypass = root.get_mut("bypass").and_then(|value| value.as_table_mut()).ok_or("TUN bypass must be a table")?;
    let mut existing = bypass.get("proxy_remote_ips").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    for ip in ips {
        if !existing.iter().any(|v| v.as_str() == Some(ip.as_str())) { existing.push(toml::Value::String(ip)); }
    }
    bypass.insert("proxy_remote_ips".into(), toml::Value::Array(existing));
    let path = get_config_dir().join("tun-nodes.toml");
    atomic_write(&path, &toml::to_string(&value).map_err(|e| e.to_string())?)?;
    Ok(path)
}

/// 读取 TUN 配置
pub fn load_tun_config() -> Result<TunConfig, String> {
    let config_path = get_config_dir().join("tun.toml");

    if !config_path.exists() {
        // 首次运行，写入带注释的默认配置
        let commented_content = get_default_tun_config_with_comments();
        fs::write(&config_path, &commented_content)
            .map_err(|e| format!("Failed to write tun config: {}", e))?;
        return toml::from_str(&commented_content)
            .map_err(|e| format!("Failed to parse default tun config: {}", e));
    }

    let content =
        fs::read_to_string(&config_path).map_err(|e| format!("Failed to read tun config: {}", e))?;

    let mut config: TunConfig = toml::from_str(&content)
        .map_err(|e| format!("Failed to parse tun config: {}", e))?;

    #[cfg(target_os = "windows")]
    if config.tun.name == "utun9" {
        config.tun.name = default_tun_name();
        let migrated_content = content.replace("name = \"utun9\"", "name = \"SimplePlane\"");
        fs::write(&config_path, migrated_content)
            .map_err(|e| format!("Failed to migrate Windows tun config: {}", e))?;
    }

    Ok(config)
}

/// 保存 TUN 配置
pub fn save_tun_config(config: &TunConfig) -> Result<(), String> {
    let config_path = get_config_dir().join("tun.toml");

    let content =
        toml::to_string_pretty(config).map_err(|e| format!("Failed to serialize tun config: {}", e))?;

    fs::write(&config_path, content).map_err(|e| format!("Failed to write tun config: {}", e))
}

/// 读取 TUN 配置为原始文本（供前端编辑器使用）
pub fn load_tun_config_raw() -> Result<String, String> {
    let config_path = get_config_dir().join("tun.toml");
    if !config_path.exists() {
        // 写入默认配置并返回
        let default_config = get_default_tun_config();
        save_tun_config(&default_config)?;
    }
    fs::read_to_string(get_config_dir().join("tun.toml"))
        .map_err(|e| format!("Failed to read tun.toml: {}", e))
}

/// 保存 TUN 配置原始文本
pub fn save_tun_config_raw(content: &str) -> Result<(), String> {
    // 先验证是合法 TOML
    let _: toml::Value = toml::from_str(content)
        .map_err(|e| format!("Invalid TOML: {}", e))?;

    let config_path = get_config_dir().join("tun.toml");
    fs::write(&config_path, content).map_err(|e| format!("Failed to write tun.toml: {}", e))
}

/// 默认代理配置模板（可直接连接）
fn get_default_proxy_config() -> ProxyConfig {
    ProxyConfig {
        nodes: Vec::new(),
        selected_node_id: String::new(),
        local: LocalConfig {
            port: 1080,
            http_proxy_enabled: true,
            http_proxy_port: 1080,
        },
        remote: RemoteConfig {
            host: "54.172.101.190".to_string(),
            port: 9090,
            cipher: "chacha20".to_string(),
            key: default_cipher_key(),
            transport: default_transport(),
            http3: Http3Config::default(),
        },
        route: RouteConfig {
            default_route: "proxy".to_string(),
            proxy_list: vec![
                "google.com".to_string(),
                "github.com".to_string(),
                "youtube.com".to_string(),
                "twitter.com".to_string(),
                "openai.com".to_string(),
                "anthropic.com".to_string(),
                "reddit.com".to_string(),
                "stackoverflow.com".to_string(),
                "docker.io".to_string(),
                "npmjs.com".to_string(),
                "cloudflare.com".to_string(),
            ],
            direct_list: vec![
                "meituan.com".to_string(),
                "sankuai.com".to_string(),
                "baidu.com".to_string(),
                "qq.com".to_string(),
                "weixin.qq.com".to_string(),
                "aliyun.com".to_string(),
                "taobao.com".to_string(),
                "jd.com".to_string(),
                "bilibili.com".to_string(),
                "zhihu.com".to_string(),
                "douyin.com".to_string(),
                "csdn.net".to_string(),
                "163.com".to_string(),
                "apple.com".to_string(),
                "localhost".to_string(),
            ],
            direct_providers: default_direct_providers(),
            proxy_providers: default_proxy_providers(),
            system_direct_list: Vec::new(),
            rules: Vec::new(),
        },
    }
}

/// 默认 TUN 配置模板
fn get_default_tun_config() -> TunConfig {
    TunConfig {
        tun: TunSection {
            name: default_tun_name(),
            address: "198.18.0.1".to_string(),
            netmask: Some("255.254.0.0".to_string()),
            mtu: 1500,
            enabled: true,
        },
        dns: DnsSection {
            listen: "127.0.0.1:53".to_string(),
            upstream: "8.8.8.8:53".to_string(),
        },
        proxy: TunProxySection {
            socks5: None,
            socks5_addr: Some("127.0.0.1:1080".to_string()),
        },
        routing: Some(RoutingSection {
            default_action: "proxy".to_string(),
            rules: vec![
                RoutingRule { rule_type: "domain_suffix".to_string(), value: "sankuai.com".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "domain_suffix".to_string(), value: "meituan.com".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "domain_suffix".to_string(), value: "cn".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "ip_cidr".to_string(), value: "10.0.0.0/8".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "ip_cidr".to_string(), value: "172.16.0.0/12".to_string(), action: "direct".to_string() },
                RoutingRule { rule_type: "ip_cidr".to_string(), value: "192.168.0.0/16".to_string(), action: "direct".to_string() },
            ],
        }),
        bypass: Some(BypassSection {
            proxy_remote_ips: vec!["54.172.101.190".to_string()],
            extra_cidrs: vec![
                "10.0.0.0/8".to_string(),
                "11.0.0.0/8".to_string(),
                "172.16.0.0/12".to_string(),
                "192.168.0.0/16".to_string(),
            ],
            dns_bypass_ips: vec!["114.114.114.114".to_string(), "223.5.5.5".to_string()],
        }),
    }
}

/// 生成带中文注释的默认 TUN 配置文件内容
fn get_default_tun_config_with_comments() -> String {
    r#"# SimplePlane TUN 模式配置
# TUN 模式会创建一个虚拟网卡，接管系统所有网络流量（比系统代理更彻底）

[tun]
# 虚拟网卡名称（macOS 为 utun9，Linux 为 tun0，Windows 为 wintun）
name = "__PLATFORM_TUN_NAME__"
# TUN 网卡的虚拟 IP 地址（用于路由劫持，不要与你的局域网 IP 段冲突）
address = "198.18.0.1"
# 子网掩码（255.254.0.0 表示劫持 198.18.0.0/15 整个段的流量）
netmask = "255.254.0.0"
# 最大传输单元（MTU），一般保持 1500 即可，无需修改
mtu = 1500
# 是否启用 TUN 设备（设为 false 可临时禁用而不删除配置）
enabled = true

[dns]
# 本地 DNS 监听地址（TUN 会把系统 DNS 劫持到这里进行分流）
listen = "127.0.0.1:53"
# 上游 DNS 服务器（实际负责域名解析的 DNS，推荐使用国外 DNS 避免污染）
upstream = "8.8.8.8:53"

[proxy]
# TUN 抓到的流量转发到的 SOCKS5 代理地址（即 proxy-local 监听的端口）
socks5_addr = "127.0.0.1:1080"

[routing]
# 默认路由动作：proxy = 所有流量走代理，direct = 所有流量直连
# 配合下方 rules 使用，rules 中匹配的条目按 action 执行，其余走 default_action
default_action = "proxy"

# 路由规则列表（按顺序匹配，首条命中即停止）
# type 支持：domain_suffix（域名后缀）、domain_keyword（域名关键词）、ip_cidr（IP 网段）
# action 支持：direct（直连）、proxy（走代理）

[[routing.rules]]
# 美团内网直连
type = "domain_suffix"
value = "sankuai.com"
action = "direct"

[[routing.rules]]
# 美团外网直连
type = "domain_suffix"
value = "meituan.com"
action = "direct"

[[routing.rules]]
# 所有 .cn 域名直连
type = "domain_suffix"
value = "cn"
action = "direct"

[[routing.rules]]
# 内网 10.x.x.x 直连
type = "ip_cidr"
value = "10.0.0.0/8"
action = "direct"

[[routing.rules]]
# 内网 172.16-31.x.x 直连
type = "ip_cidr"
value = "172.16.0.0/12"
action = "direct"

[[routing.rules]]
# 内网 192.168.x.x 直连
type = "ip_cidr"
value = "192.168.0.0/16"
action = "direct"

[bypass]
# 代理远程服务器自身的 IP（必须绕过 TUN，否则流量会死循环）
proxy_remote_ips = ["54.172.101.190"]
# 额外需要绕过 TUN 的网段（公司内网等，确保这些 IP 不会被劫持到代理）
extra_cidrs = ["10.0.0.0/8", "11.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]
# DNS 服务器 IP（这些 IP 的 53 端口请求需要绕过，否则 DNS 查询本身也会被劫持）
dns_bypass_ips = ["114.114.114.114", "223.5.5.5"]
"#.to_string().replace("__PLATFORM_TUN_NAME__", &default_tun_name())
}

/// 从 YAML 字符串解析服务器列表（支持多种格式）
/// 格式一（Java 兼容）：
///   remoteServers:
///     - host: 1.2.3.4
///       port: 9090
///       cipher: chacha20
///       cipherKey: "key"
/// 格式二（简化）：
///   servers:
///     - host: 1.2.3.4
///       port: 9090
///       cipher: chacha20
///       key: "key"
/// 格式三（单服务器）：
///   host: 1.2.3.4
///   port: 9090
///   cipher: chacha20
///   key: "key"
pub fn parse_servers_from_yaml(yaml_content: &str) -> Result<Vec<RemoteNode>, String> {
    let value: serde_yaml::Value = serde_yaml::from_str(yaml_content)
        .map_err(|e| format!("YAML 解析失败: {}", e))?;

    let mut servers = Vec::new();

    // 尝试格式一：remoteServers 列表
    if let Some(remote_servers) = value.get("remoteServers").and_then(|v| v.as_sequence()) {
        for item in remote_servers {
            servers.push(parse_node_entry(item, servers.len())?);
        }
        if !servers.is_empty() {
            return Ok(servers);
        }
    }

    // 尝试格式二：servers 列表
    if let Some(server_list) = value.get("servers").and_then(|v| v.as_sequence()) {
        for item in server_list {
            servers.push(parse_node_entry(item, servers.len())?);
        }
        if !servers.is_empty() {
            return Ok(servers);
        }
    }

    // 尝试格式三：顶层就是单个服务器
    if value.get("host").is_some() {
        servers.push(parse_node_entry(&value, 0)?);
        return Ok(servers);
    }

    // 尝试格式四：顶层是数组
    if let Some(arr) = value.as_sequence() {
        for item in arr {
            servers.push(parse_node_entry(item, servers.len())?);
        }
        if !servers.is_empty() {
            return Ok(servers);
        }
    }

    Err("无法识别的 YAML 格式，请确保包含 remoteServers 或 servers 字段".to_string())
}

fn parse_node_entry(value: &serde_yaml::Value, index: usize) -> Result<RemoteNode, String> {
    Ok(RemoteNode {
        id: value.get("id").and_then(|v| v.as_str()).map(String::from)
            .unwrap_or_else(|| format!("imported-{}", index + 1)),
        name: value.get("name").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
        remote: parse_server_entry(value)?,
    })
}

/// 从单个 YAML 节点解析出一个 RemoteConfig
fn parse_server_entry(value: &serde_yaml::Value) -> Result<RemoteConfig, String> {
    let host = value
        .get("host")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    if host.is_empty() {
        return Err("服务器缺少 host 字段".to_string());
    }

    let port = value
        .get("port")
        .and_then(|v| v.as_u64())
        .unwrap_or(9090) as u16;

    let cipher = value
        .get("cipher")
        .and_then(|v| v.as_str())
        .unwrap_or_else(|| {
            // Java 格式用 cipher，也兼容 method
            value.get("method").and_then(|v| v.as_str()).unwrap_or("chacha20")
        })
        .to_string();

    let key = value
        .get("key")
        .or_else(|| value.get("cipherKey"))
        .or_else(|| value.get("password"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    let transport = value
        .get("transport")
        .and_then(|v| v.as_str())
        .unwrap_or("http2")
        .to_string();

    let http3 = value
        .get("http3")
        .cloned()
        .map(|v| serde_yaml::from_value::<Http3Config>(v))
        .transpose()
        .map_err(|e| format!("HTTP/3 配置解析失败: {}", e))?
        .unwrap_or_default();

    Ok(RemoteConfig {
        host,
        port,
        cipher,
        key,
        transport,
        http3,
    })
}

/// 将 ProxyConfig 转为 Java 兼容的 YAML 格式字符串
/// Java 的 ProxyConfig 使用扁平驼峰命名：localPort, remoteServers, httpProxyEnabled 等
pub fn generate_java_compatible_yaml(config: &ProxyConfig) -> String {
    let mut yaml = String::new();

    // localPort
    yaml.push_str(&format!("localPort: {}\n", config.local.port));

    let nodes: Vec<RemoteNode> = if config.nodes.is_empty() {
        vec![RemoteNode { id: "legacy-node".into(), name: String::new(), remote: config.remote.clone() }]
    } else { config.nodes.clone() };
    let mut servers = Vec::new();
    for node in &nodes {
        let remote = &node.remote;
        let h = &remote.http3;
        servers.push(serde_json::json!({
            "id": node.id, "name": node.name, "host": remote.host, "port": remote.port,
            "cipher": remote.cipher, "cipherKey": remote.key, "ssl": false, "transport": remote.transport,
            "http3": {
                "serverName": if h.server_name.trim().is_empty() { &remote.host } else { &h.server_name }, "caFile": h.ca_file, "certificatePin": h.certificate_pin,
                "handshakeTimeoutMs": h.handshake_timeout_ms, "idleTimeoutMs": h.idle_timeout_ms,
                "maxStreams": h.max_streams, "initialConnectionWindow": h.initial_connection_window,
                "initialStreamWindow": h.initial_stream_window, "maxProxyMessageBytes": h.max_proxy_message_bytes,
                "streamPendingHardLimit": h.stream_pending_hard_limit, "connectionPendingHardLimit": h.connection_pending_hard_limit,
                "writeBufferLowWaterMark": h.write_buffer_low_water_mark, "writeBufferHighWaterMark": h.write_buffer_high_water_mark
            }
        }));
    }
    let mut selection = serde_json::json!({ "remoteServers": servers });
    if nodes.iter().all(|node| node.remote.transport == "http3") {
        let selected = if config.selected_node_id.is_empty() { &nodes[0].id } else { &config.selected_node_id };
        selection["nodeSelection"] = serde_json::json!({ "mode": "manual", "selectedNodeId": selected });
    }
    yaml.push_str(&serde_yaml::to_string(&selection).expect("serializable node configuration"));

    // cluster & loadBalance
    yaml.push_str("cluster: failover\n");
    yaml.push_str("loadBalance: roundrobin\n");
    yaml.push_str("timeoutMs: 8000\n");
    yaml.push_str("connectionsPerNode: 1\n");

    // httpProxyEnabled
    yaml.push_str(&format!("httpProxyEnabled: {}\n", config.local.http_proxy_enabled));

    // route
    yaml.push_str("route:\n");
    yaml.push_str(&format!("  defaultRoute: {}\n", config.route.default_route));

    if !config.route.proxy_list.is_empty() {
        yaml.push_str("  proxyList:\n");
        for item in &config.route.proxy_list {
            yaml.push_str(&format!("    - \"{}\"\n", item));
        }
    }

    if !config.route.direct_list.is_empty() {
        yaml.push_str("  directList:\n");
        for item in &config.route.direct_list {
            yaml.push_str(&format!("    - \"{}\"\n", item));
        }
    }

    if !config.route.direct_providers.is_empty() {
        yaml.push_str("  directProviders:\n");
        for item in &config.route.direct_providers {
            yaml.push_str(&format!("    - \"{}\"\n", item));
        }
    }

    if !config.route.proxy_providers.is_empty() {
        yaml.push_str("  proxyProviders:\n");
        for item in &config.route.proxy_providers {
            yaml.push_str(&format!("    - \"{}\"\n", item));
        }
    }

    if !config.route.system_direct_list.is_empty() {
        yaml.push_str("  systemDirectList:\n");
        for item in &config.route.system_direct_list {
            yaml.push_str(&format!("    - \"{}\"\n", item));
        }
    }

    if !config.route.rules.is_empty() {
        yaml.push_str("  rules:\n");
        for rule in &config.route.rules {
            yaml.push_str("    -\n");
            if let Some(id) = &rule.id {
                yaml.push_str(&format!("      id: \"{}\"\n", id));
            }
            yaml.push_str(&format!("      type: \"{}\"\n", rule.rule_type));
            yaml.push_str(&format!("      value: \"{}\"\n", rule.value));
            yaml.push_str(&format!("      action: \"{}\"\n", rule.action));
            yaml.push_str(&format!("      priority: {}\n", rule.priority));
        }
    }

    // systemProxy
    yaml.push_str("systemProxy:\n");
    yaml.push_str("  enabled: false\n");
    yaml.push_str(&format!("  host: 127.0.0.1\n"));

    yaml
}

/// 生成 Java 兼容配置文件并写入磁盘，返回文件路径
pub fn write_java_config() -> Result<PathBuf, String> {
    let config = load_proxy_config()?;
    let java_yaml = generate_java_compatible_yaml(&config);
    let java_config_path = get_config_dir().join("proxy-java.yml");
    atomic_write(&java_config_path, &java_yaml)?;
    Ok(java_config_path)
}

#[cfg(test)]
mod node_tests {
    use super::*;

    #[test]
    fn blank_tls_name_uses_each_node_host_without_changing_other_options() {
        let mut config = get_default_proxy_config();
        config.normalize_nodes().unwrap();
        config.nodes[0].remote.host = "20.78.53.111".into();
        config.nodes[0].remote.http3.server_name.clear();
        config.nodes[0].remote.http3.ca_file.clear();
        let value: serde_yaml::Value = serde_yaml::from_str(&generate_java_compatible_yaml(&config)).unwrap();
        assert_eq!(value["remoteServers"][0]["http3"]["serverName"].as_str(), Some("20.78.53.111"));
        assert_eq!(value["remoteServers"][0]["http3"]["caFile"].as_str(), Some(""));
        assert_eq!(value["remoteServers"][0]["http3"]["initialStreamWindow"].as_u64(), Some(1048576));
    }

    #[test]
    fn importing_public_certificate_copies_it_and_reimport_changes_config_path() {
        let directory = std::env::temp_dir().join(format!("simpleplane-certificate-test-{}", std::process::id()));
        let pem = include_str!("../../../proxy-local/src/main/resources/http3-remote-ca.crt");
        let first = import_node_certificate(&directory, pem).unwrap();
        let second = import_node_certificate(&directory, pem).unwrap();
        assert_ne!(first, second);
        assert_eq!(fs::read_to_string(&first).unwrap(), pem.trim());
        assert!(std::path::Path::new(&first).starts_with(&directory));
        fs::remove_file(first).unwrap();
        fs::remove_file(second).unwrap();
        fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn importing_certificate_rejects_private_keys_and_non_pem_files() {
        let directory = std::env::temp_dir().join("simpleplane-invalid-certificate-test");
        for pem in ["not a certificate", "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----",
                    "-----BEGIN CERTIFICATE-----\nPRIVATE KEY\n-----END CERTIFICATE-----"] {
            assert!(import_node_certificate(&directory, pem).is_err());
        }
        assert!(import_node_certificate(&directory, &"x".repeat(262145)).is_err());
    }

    #[test]
    fn legacy_config_migrates_to_one_stable_manual_node() {
        let mut config = get_default_proxy_config();
        config.normalize_nodes().unwrap();
        assert_eq!(config.nodes.len(), 1);
        assert_eq!(config.selected_node_id, "legacy-node");
        let saved = serde_yaml::to_string(&config).unwrap();
        let mut loaded: ProxyConfig = serde_yaml::from_str(&saved).unwrap();
        loaded.normalize_nodes().unwrap();
        assert_eq!(loaded.nodes[0].id, "legacy-node");
        assert_eq!(loaded.remote.key, config.remote.key);
    }

    #[test]
    fn java_yaml_contains_all_nodes_selection_and_escaped_credentials() {
        let mut config = get_default_proxy_config();
        config.normalize_nodes().unwrap();
        let mut second = config.nodes[0].clone();
        second.id = "node-b".into();
        second.remote.host = "b.example".into();
        second.remote.key = "quote\" slash\\ newline\n".into();
        second.remote.http3.ca_file = "C:\\certs\\ca.pem".into();
        config.nodes.push(second);
        config.selected_node_id = "node-b".into();
        config.normalize_nodes().unwrap();
        let value: serde_yaml::Value = serde_yaml::from_str(&generate_java_compatible_yaml(&config)).unwrap();
        assert_eq!(value["remoteServers"].as_sequence().unwrap().len(), 2);
        assert_eq!(value["nodeSelection"]["selectedNodeId"].as_str(), Some("node-b"));
        assert_eq!(value["remoteServers"][1]["cipherKey"].as_str(), Some(config.remote.key.as_str()));
        assert_eq!(value["remoteServers"][1]["http3"]["caFile"].as_str(), Some("C:\\certs\\ca.pem"));
    }

    #[test]
    fn duplicate_and_missing_selected_node_are_rejected() {
        let mut config = get_default_proxy_config();
        config.normalize_nodes().unwrap();
        config.nodes.push(config.nodes[0].clone());
        assert!(config.normalize_nodes().is_err());
        config.nodes.pop();
        config.selected_node_id = "missing".into();
        assert!(config.normalize_nodes().is_err());
    }

    #[test]
    fn importing_nodes_preserves_ids_and_http3_options() {
        let nodes = parse_servers_from_yaml("remoteServers:\n  - id: stable-a\n    name: A\n    host: a.example\n    port: 9090\n    transport: http3\n    http3:\n      caFile: ca.pem\n").unwrap();
        assert_eq!(nodes[0].id, "stable-a");
        assert_eq!(nodes[0].remote.http3.ca_file, "ca.pem");
    }
}
