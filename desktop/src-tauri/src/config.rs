use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// 代理配置（对应 proxy.yml 的结构）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyConfig {
    pub local: LocalConfig,
    pub remote: RemoteConfig,
    #[serde(default)]
    pub route: RouteConfig,
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
    #[serde(default)]
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

fn default_tun_name() -> String { "utun9".to_string() }
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

    serde_yaml::from_str(&content).map_err(|e| format!("Failed to parse config: {}", e))
}

/// 保存代理配置
pub fn save_proxy_config(config: &ProxyConfig) -> Result<(), String> {
    let config_path = get_config_dir().join("proxy.yml");

    let content =
        serde_yaml::to_string(config).map_err(|e| format!("Failed to serialize config: {}", e))?;

    fs::write(&config_path, content).map_err(|e| format!("Failed to write config: {}", e))
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

    toml::from_str(&content).map_err(|e| format!("Failed to parse tun config: {}", e))
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
            proxy_providers: vec![
                "classpath:routing/providers/proxy-list.txt".to_string(),
            ],
            system_direct_list: Vec::new(),
            rules: Vec::new(),
        },
    }
}

/// 默认 TUN 配置模板
fn get_default_tun_config() -> TunConfig {
    TunConfig {
        tun: TunSection {
            name: "utun9".to_string(),
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
name = "utun9"
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
"#.to_string()
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
pub fn parse_servers_from_yaml(yaml_content: &str) -> Result<Vec<RemoteConfig>, String> {
    let value: serde_yaml::Value = serde_yaml::from_str(yaml_content)
        .map_err(|e| format!("YAML 解析失败: {}", e))?;

    let mut servers = Vec::new();

    // 尝试格式一：remoteServers 列表
    if let Some(remote_servers) = value.get("remoteServers").and_then(|v| v.as_sequence()) {
        for item in remote_servers {
            servers.push(parse_server_entry(item)?);
        }
        if !servers.is_empty() {
            return Ok(servers);
        }
    }

    // 尝试格式二：servers 列表
    if let Some(server_list) = value.get("servers").and_then(|v| v.as_sequence()) {
        for item in server_list {
            servers.push(parse_server_entry(item)?);
        }
        if !servers.is_empty() {
            return Ok(servers);
        }
    }

    // 尝试格式三：顶层就是单个服务器
    if value.get("host").is_some() {
        servers.push(parse_server_entry(&value)?);
        return Ok(servers);
    }

    // 尝试格式四：顶层是数组
    if let Some(arr) = value.as_sequence() {
        for item in arr {
            servers.push(parse_server_entry(item)?);
        }
        if !servers.is_empty() {
            return Ok(servers);
        }
    }

    Err("无法识别的 YAML 格式，请确保包含 remoteServers 或 servers 字段".to_string())
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

    // remoteServers (Java 支持多服务器列表)
    yaml.push_str("remoteServers:\n");
    yaml.push_str(&format!("  - host: {}\n", config.remote.host));
    yaml.push_str(&format!("    port: {}\n", config.remote.port));
    yaml.push_str(&format!("    cipher: {}\n", config.remote.cipher));
    yaml.push_str(&format!("    cipherKey: \"{}\"\n", config.remote.key));
    yaml.push_str("    ssl: false\n");
    yaml.push_str(&format!("    transport: {}\n", config.remote.transport));
    yaml.push_str("    http3:\n");
    yaml.push_str(&format!("      serverName: \"{}\"\n", config.remote.http3.server_name));
    yaml.push_str(&format!("      caFile: \"{}\"\n", config.remote.http3.ca_file));
    yaml.push_str(&format!("      certificatePin: \"{}\"\n", config.remote.http3.certificate_pin));
    yaml.push_str(&format!("      handshakeTimeoutMs: {}\n", config.remote.http3.handshake_timeout_ms));
    yaml.push_str(&format!("      idleTimeoutMs: {}\n", config.remote.http3.idle_timeout_ms));
    yaml.push_str(&format!("      maxStreams: {}\n", config.remote.http3.max_streams));
    yaml.push_str(&format!("      initialConnectionWindow: {}\n", config.remote.http3.initial_connection_window));
    yaml.push_str(&format!("      initialStreamWindow: {}\n", config.remote.http3.initial_stream_window));
    yaml.push_str(&format!("      maxProxyMessageBytes: {}\n", config.remote.http3.max_proxy_message_bytes));
    yaml.push_str(&format!("      streamPendingHardLimit: {}\n", config.remote.http3.stream_pending_hard_limit));
    yaml.push_str(&format!("      connectionPendingHardLimit: {}\n", config.remote.http3.connection_pending_hard_limit));
    yaml.push_str(&format!("      writeBufferLowWaterMark: {}\n", config.remote.http3.write_buffer_low_water_mark));
    yaml.push_str(&format!("      writeBufferHighWaterMark: {}\n", config.remote.http3.write_buffer_high_water_mark));

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
    fs::write(&java_config_path, java_yaml)
        .map_err(|e| format!("Failed to write Java config: {}", e))?;
    Ok(java_config_path)
}
