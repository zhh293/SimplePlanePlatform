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
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RouteConfig {
    #[serde(default = "default_route_mode")]
    pub default_route: String,
    #[serde(default)]
    pub proxy_list: Vec<String>,
    #[serde(default)]
    pub direct_list: Vec<String>,
}

fn default_socks_port() -> u16 { 1080 }
fn default_http_enabled() -> bool { true }
fn default_http_port() -> u16 { 1080 }
fn default_cipher() -> String { "chacha20".to_string() }
fn default_route_mode() -> String { "proxy".to_string() }

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
            host: "54.234.196.30".to_string(),
            port: 9090,
            cipher: "chacha20".to_string(),
            key: String::new(),
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
            proxy_remote_ips: vec!["54.234.196.30".to_string()],
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
proxy_remote_ips = ["54.234.196.30"]
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

    Ok(RemoteConfig {
        host,
        port,
        cipher,
        key,
    })
}

/// 将 ProxyConfig 转为 Java 兼容的 YAML 格式字符串
/// Java 的 ProxyConfig 使用扁平驼峰命名：localPort, remoteServers, httpProxyEnabled 等
pub fn generate_java_compatible_yaml(config: &ProxyConfig, tun_mode: bool) -> String {
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

    // cluster & loadBalance
    yaml.push_str("cluster: failover\n");
    yaml.push_str("loadBalance: roundrobin\n");
    yaml.push_str("timeoutMs: 8000\n");
    yaml.push_str("connectionsPerNode: 1\n");

    // httpProxyEnabled
    yaml.push_str(&format!("httpProxyEnabled: {}\n", config.local.http_proxy_enabled));

    // tunMode: TUN 模式下所有流量走 proxy-remote，避免 DirectRelayHandler 回环
    yaml.push_str(&format!("tunMode: {}\n", tun_mode));

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

    // systemProxy
    yaml.push_str("systemProxy:\n");
    yaml.push_str("  enabled: false\n");
    yaml.push_str(&format!("  host: 127.0.0.1\n"));

    yaml
}

/// 生成 Java 兼容配置文件并写入磁盘，返回文件路径
/// tun_mode: 是否为 TUN 模式（影响 RouteRule 行为）
pub fn write_java_config(tun_mode: bool) -> Result<PathBuf, String> {
    let config = load_proxy_config()?;
    let java_yaml = generate_java_compatible_yaml(&config, tun_mode);
    let java_config_path = get_config_dir().join("proxy-java.yml");
    fs::write(&java_config_path, java_yaml)
        .map_err(|e| format!("Failed to write Java config: {}", e))?;
    Ok(java_config_path)
}
