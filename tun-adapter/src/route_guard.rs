//! 路由恢复 Drop Guard
//!
//! 记录所有添加的系统路由，在进程退出（包括 panic）时自动恢复原始路由表。

use std::net::IpAddr;
use std::process::Command;

use crate::config::{BypassConfig, IntranetDnsConfig, TunConfig};
use crate::error::TunError;

/// 单条路由记录
#[derive(Debug, Clone)]
pub struct RouteEntry {
    /// 目标网段
    pub destination: String,
    /// 网关
    pub gateway: String,
}

/// 路由恢复守卫：Drop 时自动删除添加的路由并恢复原始默认网关和 DNS
pub struct RouteGuard {
    /// 原始默认网关
    original_gateway: Option<IpAddr>,
    /// TUN 设备名称
    _tun_name: String,
    /// 记录所有添加的路由
    routes_added: Vec<RouteEntry>,
    /// 原始 DNS 服务器设置（用于恢复）
    original_dns: Option<DnsSettings>,
    /// macOS: 已创建的 /etc/resolver/ 文件列表
    /// Windows: NRPT 规则的 namespace 列表（用于恢复时删除）
    resolver_files: Vec<String>,
}

/// 保存的原始 DNS 设置
#[derive(Debug, Clone)]
struct DnsSettings {
    /// macOS: 网络服务名（如 "Wi-Fi"）
    /// Windows: 网络接口名（如 "Ethernet"）
    service_name: String,
    /// 原始 DNS 服务器列表（"Empty"/"DHCP" 表示自动获取）
    servers: Vec<String>,
}

// ============================================================================
// Platform-conditional DNS backup file path
// ============================================================================

/// 获取 DNS 备份文件路径（运行时确定，跨平台）
fn get_dns_backup_file_path() -> String {
    #[cfg(target_os = "macos")]
    {
        "/tmp/tun-adapter-dns-backup.conf".to_string()
    }
    #[cfg(target_os = "windows")]
    {
        let temp = std::env::var("TEMP")
            .unwrap_or_else(|_| std::env::var("TMP")
                .unwrap_or_else(|_| "C:\\Windows\\Temp".to_string()));
        format!("{}\\tun-adapter-dns-backup.conf", temp)
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        "/tmp/tun-adapter-dns-backup.conf".to_string()
    }
}

// ============================================================================
// Helper: CIDR to IP + subnet mask (for Windows `route add` command)
// ============================================================================

/// Convert CIDR notation (e.g. "10.0.0.0/8") to (ip, subnet_mask) tuple.
/// Returns ("10.0.0.0", "255.0.0.0") for the example above.
/// If no prefix length is specified, assumes /32.
#[cfg(target_os = "windows")]
fn cidr_to_ip_and_mask(cidr: &str) -> (String, String) {
    let (ip, prefix_len) = if let Some(slash_pos) = cidr.find('/') {
        let ip_part = &cidr[..slash_pos];
        let prefix: u8 = cidr[slash_pos + 1..].parse().unwrap_or(32);
        (ip_part.to_string(), prefix)
    } else {
        (cidr.to_string(), 32u8)
    };

    let mask_bits: u32 = if prefix_len == 0 {
        0
    } else {
        !0u32 << (32 - prefix_len)
    };

    let mask = format!(
        "{}.{}.{}.{}",
        (mask_bits >> 24) & 0xFF,
        (mask_bits >> 16) & 0xFF,
        (mask_bits >> 8) & 0xFF,
        mask_bits & 0xFF
    );

    (ip, mask)
}

impl RouteGuard {
    // ========================================================================
    // setup() — macOS
    // ========================================================================

    /// 检测当前网络环境并设置路由 (macOS)
    #[cfg(target_os = "macos")]
    pub async fn setup(
        config: &TunConfig,
        bypass: &BypassConfig,
        intranet_dns: &IntranetDnsConfig,
        iface_name: &str,
    ) -> Result<Self, TunError> {
        tracing::info!("Setting up route guard for TUN interface: {}", iface_name);

        let original_gateway = Self::get_default_gateway()?;
        tracing::info!("Original default gateway: {:?}", original_gateway);

        let mut guard = Self {
            original_gateway,
            _tun_name: config.name.clone(),
            routes_added: Vec::new(),
            original_dns: None,
            resolver_files: Vec::new(),
        };

        if let Some(orig_gw) = &guard.original_gateway {
            let gw_str = orig_gw.to_string();

            for ip in &bypass.proxy_remote_ips {
                let host_route = if ip.contains('/') {
                    ip.clone()
                } else {
                    format!("{}/32", ip)
                };
                tracing::info!("Adding bypass route for proxy-remote: {} via {}", host_route, gw_str);
                guard.add_route(&host_route, &gw_str)?;
            }

            for cidr in &bypass.extra_cidrs {
                tracing::info!("Adding bypass route for CIDR: {} via {}", cidr, gw_str);
                guard.add_route(cidr, &gw_str)?;
            }

            for ip in &bypass.dns_bypass_ips {
                let host_route = if ip.contains('/') {
                    ip.clone()
                } else {
                    format!("{}/32", ip)
                };
                tracing::info!("Adding bypass route for DNS server: {} via {}", host_route, gw_str);
                guard.add_route(&host_route, &gw_str)?;
            }
        } else {
            tracing::warn!("No original gateway detected; bypass routes will not be added");
        }

        // 【顺序关键】先创建 /etc/resolver/ 分流文件，再修改系统 DNS
        let resolvers_ok = if !intranet_dns.servers.is_empty() && !intranet_dns.domains.is_empty() {
            match guard.setup_intranet_resolvers(intranet_dns) {
                Ok(()) => true,
                Err(e) => {
                    tracing::error!("Intranet DNS resolver setup failed: {}. Will NOT modify system DNS.", e);
                    false
                }
            }
        } else {
            true
        };

        if resolvers_ok {
            if let Err(e) = guard.setup_dns() {
                tracing::warn!("Failed to set system DNS (non-fatal): {}", e);
            }
        } else {
            tracing::warn!(
                "Skipping system DNS modification due to resolver setup failure. \
                 External domains may not be intercepted by FakeDNS."
            );
        }

        // 添加主路由规则：0.0.0.0/1 和 128.0.0.0/1 指向 TUN 接口
        let tun_peer = Self::get_tun_peer_address(iface_name)?;
        tracing::info!("TUN peer address for routing: {}", tun_peer);
        guard.add_route("0.0.0.0/1", &tun_peer)?;
        guard.add_route("128.0.0.0/1", &tun_peer)?;

        tracing::info!("Routes configured successfully");
        Ok(guard)
    }

    // ========================================================================
    // setup() — Windows
    // ========================================================================

    /// 检测当前网络环境并设置路由 (Windows)
    #[cfg(target_os = "windows")]
    pub async fn setup(
        config: &TunConfig,
        bypass: &BypassConfig,
        intranet_dns: &IntranetDnsConfig,
        iface_name: &str,
    ) -> Result<Self, TunError> {
        tracing::info!("Setting up route guard for TUN interface (Windows): {}", iface_name);

        let original_gateway = Self::get_default_gateway()?;
        tracing::info!("Original default gateway: {:?}", original_gateway);

        let mut guard = Self {
            original_gateway,
            _tun_name: config.name.clone(),
            routes_added: Vec::new(),
            original_dns: None,
            resolver_files: Vec::new(),
        };

        if let Some(orig_gw) = &guard.original_gateway {
            let gw_str = orig_gw.to_string();

            for ip in &bypass.proxy_remote_ips {
                let host_route = if ip.contains('/') {
                    ip.clone()
                } else {
                    format!("{}/32", ip)
                };
                tracing::info!("Adding bypass route for proxy-remote: {} via {}", host_route, gw_str);
                guard.add_route(&host_route, &gw_str)?;
            }

            for cidr in &bypass.extra_cidrs {
                tracing::info!("Adding bypass route for CIDR: {} via {}", cidr, gw_str);
                guard.add_route(cidr, &gw_str)?;
            }

            for ip in &bypass.dns_bypass_ips {
                let host_route = if ip.contains('/') {
                    ip.clone()
                } else {
                    format!("{}/32", ip)
                };
                tracing::info!("Adding bypass route for DNS server: {} via {}", host_route, gw_str);
                guard.add_route(&host_route, &gw_str)?;
            }
        } else {
            tracing::warn!("No original gateway detected; bypass routes will not be added");
        }

        // Step 1: 设置内网域名 DNS 分流（NRPT 规则）
        let resolvers_ok = if !intranet_dns.servers.is_empty() && !intranet_dns.domains.is_empty() {
            match guard.setup_intranet_resolvers(intranet_dns) {
                Ok(()) => true,
                Err(e) => {
                    tracing::error!("Intranet DNS NRPT setup failed: {}. Will NOT modify system DNS.", e);
                    false
                }
            }
        } else {
            true
        };

        // Step 2: 修改系统 DNS 为 198.18.0.2（FakeDNS）
        if resolvers_ok {
            if let Err(e) = guard.setup_dns() {
                tracing::warn!("Failed to set system DNS (non-fatal): {}", e);
            }
        } else {
            tracing::warn!(
                "Skipping system DNS modification due to NRPT setup failure. \
                 External domains may not be intercepted by FakeDNS."
            );
        }

        // 添加主路由规则：0.0.0.0/1 和 128.0.0.0/1 指向 TUN 接口
        // Windows: 使用 TUN 接口的网关地址
        let tun_gateway = Self::get_tun_gateway_address(iface_name)?;
        tracing::info!("TUN gateway for routing: {}", tun_gateway);
        guard.add_route("0.0.0.0/1", &tun_gateway)?;
        guard.add_route("128.0.0.0/1", &tun_gateway)?;

        tracing::info!("Routes configured successfully (Windows)");
        Ok(guard)
    }

    // ========================================================================
    // setup() — Fallback (unsupported platforms)
    // ========================================================================

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    pub async fn setup(
        config: &TunConfig,
        bypass: &BypassConfig,
        intranet_dns: &IntranetDnsConfig,
        iface_name: &str,
    ) -> Result<Self, TunError> {
        let _ = (bypass, intranet_dns, iface_name);
        tracing::warn!("Route guard not implemented for this platform");
        Ok(Self {
            original_gateway: None,
            _tun_name: config.name.clone(),
            routes_added: Vec::new(),
            original_dns: None,
            resolver_files: Vec::new(),
        })
    }

    // ========================================================================
    // setup_dns()
    // ========================================================================

    /// 设置系统 DNS 为 FakeDNS (198.18.0.2) — macOS
    #[cfg(target_os = "macos")]
    fn setup_dns(&mut self) -> Result<(), TunError> {
        let service_name = Self::get_active_network_service()?;
        tracing::info!("Active network service: {}", service_name);

        let current_dns = Self::get_dns_servers(&service_name)?;
        tracing::info!("Original DNS servers for '{}': {:?}", service_name, current_dns);

        self.original_dns = Some(DnsSettings {
            service_name: service_name.clone(),
            servers: current_dns.clone(),
        });

        Self::persist_dns_backup(&service_name, &current_dns);

        let output = Command::new("networksetup")
            .args(["-setdnsservers", &service_name, "198.18.0.2"])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to set DNS: {}", e)))?;

        if output.status.success() {
            tracing::info!("System DNS set to 198.18.0.2 (FakeDNS). Intranet domains use /etc/resolver/ split.");
        } else {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let _ = std::fs::remove_file(get_dns_backup_file_path());
            return Err(TunError::SystemRoute(format!("networksetup -setdnsservers failed: {}", stderr)));
        }

        let _ = Command::new("dscacheutil").arg("-flushcache").output();
        let _ = Command::new("killall").args(["-HUP", "mDNSResponder"]).output();
        tracing::info!("DNS cache flushed");

        Ok(())
    }

    /// 设置系统 DNS 为 FakeDNS (198.18.0.2) — Windows
    ///
    /// 使用 PowerShell Set-DnsClientServerAddress 设置接口 DNS。
    #[cfg(target_os = "windows")]
    fn setup_dns(&mut self) -> Result<(), TunError> {
        let interface_name = Self::get_active_interface_name()?;
        tracing::info!("Active network interface: {}", interface_name);

        let current_dns = Self::get_dns_servers_win(&interface_name)?;
        tracing::info!("Original DNS servers for '{}': {:?}", interface_name, current_dns);

        self.original_dns = Some(DnsSettings {
            service_name: interface_name.clone(),
            servers: current_dns.clone(),
        });

        Self::persist_dns_backup(&interface_name, &current_dns);

        // 使用 PowerShell 设置 DNS（比 netsh 更可靠，支持接口名含空格）
        let ps_cmd = format!(
            "Set-DnsClientServerAddress -InterfaceAlias '{}' -ServerAddresses ('198.18.0.2')",
            interface_name
        );

        let output = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &ps_cmd])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to set DNS via PowerShell: {}", e)))?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let _ = std::fs::remove_file(get_dns_backup_file_path());
            return Err(TunError::SystemRoute(format!(
                "Set-DnsClientServerAddress failed: {}", stderr.trim()
            )));
        }

        tracing::info!("System DNS set to 198.18.0.2 (FakeDNS) on interface '{}'", interface_name);

        // 刷新 DNS 缓存
        let _ = Command::new("ipconfig").arg("/flushdns").output();
        tracing::info!("DNS cache flushed (ipconfig /flushdns)");

        Ok(())
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    fn setup_dns(&mut self) -> Result<(), TunError> {
        Ok(())
    }

    // ========================================================================
    // DNS backup persistence (shared logic)
    // ========================================================================

    /// 将原始 DNS 设置持久化到文件（供异常退出后恢复）
    fn persist_dns_backup(service_name: &str, servers: &[String]) {
        let mut content = format!("service:{}\n", service_name);
        // 当原始 DNS 为「自动获取」时，统一写入规范标记 "Empty"。
        // 注意：macOS `networksetup -setdnsservers <svc> Empty` 中的 "Empty" 才是合法值，
        // "DHCP" 并不是合法参数（会报 "DHCP is not a valid IP address"），
        // 因此这里必须写 "Empty"，与 restore-dns.sh 的解析约定保持一致。
        if servers.is_empty() || (servers.len() == 1 && (servers[0] == "Empty" || servers[0] == "DHCP")) {
            content.push_str("dns:Empty\n");
        } else {
            for s in servers {
                content.push_str(&format!("dns:{}\n", s));
            }
        }

        let backup_path = get_dns_backup_file_path();
        match std::fs::write(&backup_path, &content) {
            Ok(()) => tracing::info!("DNS backup saved to {}", backup_path),
            Err(e) => tracing::warn!("Failed to save DNS backup: {} (recovery may fail on crash)", e),
        }
    }

    /// 将 resolver/NRPT 规则列表追加到备份文件
    fn persist_resolver_list(resolver_files: &[String]) {
        use std::io::Write;
        let backup_path = get_dns_backup_file_path();
        if let Ok(mut file) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&backup_path)
        {
            for path in resolver_files {
                #[cfg(target_os = "macos")]
                {
                    let _ = writeln!(file, "resolver:{}", path);
                }
                #[cfg(target_os = "windows")]
                {
                    let _ = writeln!(file, "nrpt:{}", path);
                }
                #[cfg(not(any(target_os = "macos", target_os = "windows")))]
                {
                    let _ = writeln!(file, "resolver:{}", path);
                }
            }
        }
    }

    // ========================================================================
    // Intranet DNS resolvers (split DNS)
    // ========================================================================

    /// 设置内网域名 DNS 分流 — macOS (/etc/resolver/)
    #[cfg(target_os = "macos")]
    fn setup_intranet_resolvers(&mut self, intranet_dns: &IntranetDnsConfig) -> Result<(), TunError> {
        use std::fs;

        let resolver_dir = "/etc/resolver";
        if !std::path::Path::new(resolver_dir).exists() {
            let output = Command::new("mkdir")
                .args(["-p", resolver_dir])
                .output()
                .map_err(|e| TunError::SystemRoute(format!("failed to spawn mkdir: {}", e)))?;
            if !output.status.success() {
                let stderr = String::from_utf8_lossy(&output.stderr);
                return Err(TunError::SystemRoute(format!(
                    "mkdir -p {} failed: {}", resolver_dir, stderr
                )));
            }
            tracing::info!("Created directory: {}", resolver_dir);
        }

        let mut content = String::new();
        for server in &intranet_dns.servers {
            content.push_str(&format!("nameserver {}\n", server));
        }

        let mut failed_domains = Vec::new();
        for domain in &intranet_dns.domains {
            let file_path = format!("{}/{}", resolver_dir, domain);
            match fs::write(&file_path, &content) {
                Ok(()) => {
                    tracing::info!("Created resolver file: {} -> {:?}", file_path, intranet_dns.servers);
                    self.resolver_files.push(file_path);
                }
                Err(e) => {
                    tracing::error!("CRITICAL: Failed to write {}: {}", file_path, e);
                    failed_domains.push(domain.clone());
                }
            }
        }

        if !failed_domains.is_empty() {
            tracing::error!(
                "Failed to create resolver files for: {:?}. These domains will NOT resolve via intranet DNS!",
                failed_domains
            );
            return Err(TunError::SystemRoute(format!(
                "Failed to create resolver files for domains: {:?}. Aborting DNS setup to prevent connectivity loss.",
                failed_domains
            )));
        }

        let _ = Command::new("dscacheutil").arg("-flushcache").output();
        let _ = Command::new("killall").args(["-HUP", "mDNSResponder"]).output();
        tracing::info!(
            "Intranet DNS resolvers configured successfully: {} domains -> {:?}",
            intranet_dns.domains.len(), intranet_dns.servers
        );

        Self::persist_resolver_list(&self.resolver_files);
        Ok(())
    }

    /// 设置内网域名 DNS 分流 — Windows (NRPT: Name Resolution Policy Table)
    ///
    /// 使用 PowerShell `Add-DnsClientNrptRule` 为每个内网域名添加 NRPT 规则，
    /// 让 Windows 对这些域名的 DNS 查询走内网 DNS 服务器。
    #[cfg(target_os = "windows")]
    fn setup_intranet_resolvers(&mut self, intranet_dns: &IntranetDnsConfig) -> Result<(), TunError> {
        // 构建 NameServers 参数：逗号分隔的 IP 列表
        let servers_joined: Vec<String> = intranet_dns.servers.iter()
            .map(|s| format!("'{}'", s))
            .collect();
        let servers_param = servers_joined.join(",");

        let mut failed_domains = Vec::new();

        for domain in &intranet_dns.domains {
            // NRPT namespace 格式：".sankuai.com" (带前导点表示该域名及所有子域名)
            let namespace = if domain.starts_with('.') {
                domain.clone()
            } else {
                format!(".{}", domain)
            };

            let ps_cmd = format!(
                "Add-DnsClientNrptRule -Namespace '{}' -NameServers @({}) -ErrorAction Stop",
                namespace, servers_param
            );

            tracing::info!("Adding NRPT rule: namespace={} servers={:?}", namespace, intranet_dns.servers);

            let output = Command::new("powershell")
                .args(["-NoProfile", "-NonInteractive", "-Command", &ps_cmd])
                .output()
                .map_err(|e| TunError::SystemRoute(format!("failed to spawn powershell for NRPT: {}", e)))?;

            if output.status.success() {
                tracing::info!("NRPT rule added for namespace: {}", namespace);
                self.resolver_files.push(namespace);
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr);
                tracing::error!("Failed to add NRPT rule for {}: {}", namespace, stderr.trim());
                failed_domains.push(domain.clone());
            }
        }

        if !failed_domains.is_empty() {
            tracing::error!(
                "Failed to create NRPT rules for: {:?}. These domains will NOT resolve via intranet DNS!",
                failed_domains
            );
            return Err(TunError::SystemRoute(format!(
                "Failed to create NRPT rules for domains: {:?}. Aborting DNS setup to prevent connectivity loss.",
                failed_domains
            )));
        }

        let _ = Command::new("ipconfig").arg("/flushdns").output();
        tracing::info!(
            "Intranet DNS NRPT rules configured successfully: {} domains -> {:?}",
            intranet_dns.domains.len(), intranet_dns.servers
        );

        Self::persist_resolver_list(&self.resolver_files);
        Ok(())
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    fn setup_intranet_resolvers(&mut self, _intranet_dns: &IntranetDnsConfig) -> Result<(), TunError> {
        Ok(())
    }

    // ========================================================================
    // Restore intranet resolvers
    // ========================================================================

    /// 清理 /etc/resolver/ 文件 — macOS
    #[cfg(target_os = "macos")]
    fn restore_intranet_resolvers(&self) {
        use std::fs;

        for file_path in &self.resolver_files {
            match fs::remove_file(file_path) {
                Ok(()) => tracing::info!("Removed resolver file: {}", file_path),
                Err(e) => tracing::warn!("Failed to remove {}: {}", file_path, e),
            }
        }

        if !self.resolver_files.is_empty() {
            if let Ok(entries) = std::fs::read_dir("/etc/resolver") {
                if entries.count() == 0 {
                    let _ = std::fs::remove_dir("/etc/resolver");
                    tracing::info!("Removed empty /etc/resolver/ directory");
                }
            }
        }
    }

    /// 清理 NRPT 规则 — Windows
    #[cfg(target_os = "windows")]
    fn restore_intranet_resolvers(&self) {
        if self.resolver_files.is_empty() {
            return;
        }

        for namespace in &self.resolver_files {
            let ps_cmd = format!(
                "Get-DnsClientNrptRule | Where-Object {{ $_.Namespace -eq '{}' }} | Remove-DnsClientNrptRule -Force -ErrorAction SilentlyContinue",
                namespace
            );

            tracing::info!("Removing NRPT rule for namespace: {}", namespace);

            let result = Command::new("powershell")
                .args(["-NoProfile", "-NonInteractive", "-Command", &ps_cmd])
                .output();

            match result {
                Ok(output) => {
                    if output.status.success() {
                        tracing::info!("NRPT rule removed for: {}", namespace);
                    } else {
                        let stderr = String::from_utf8_lossy(&output.stderr);
                        tracing::warn!("Failed to remove NRPT rule for {}: {}", namespace, stderr.trim());
                    }
                }
                Err(e) => {
                    tracing::error!("Failed to run PowerShell to remove NRPT rule: {}", e);
                }
            }
        }

        // 刷新 DNS 缓存
        let _ = Command::new("ipconfig").arg("/flushdns").output();
        tracing::info!("NRPT rules cleanup completed, DNS cache flushed");
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    fn restore_intranet_resolvers(&self) {}

    // ========================================================================
    // Restore DNS
    // ========================================================================

    /// 恢复原始 DNS 设置 — macOS
    #[cfg(target_os = "macos")]
    fn restore_dns(&self) {
        if let Some(ref dns_settings) = self.original_dns {
            let args: Vec<&str> = if dns_settings.servers.is_empty()
                || (dns_settings.servers.len() == 1 && dns_settings.servers[0] == "Empty")
            {
                vec!["-setdnsservers", &dns_settings.service_name, "Empty"]
            } else {
                let mut args = vec!["-setdnsservers", &dns_settings.service_name];
                for server in &dns_settings.servers {
                    args.push(server.as_str());
                }
                args
            };

            tracing::info!("Restoring DNS for '{}': {:?}", dns_settings.service_name, dns_settings.servers);
            let result = Command::new("networksetup").args(&args).output();

            match result {
                Ok(output) => {
                    if output.status.success() {
                        tracing::info!("DNS restored successfully");
                        let _ = std::fs::remove_file(get_dns_backup_file_path());
                        tracing::info!("DNS backup file removed (restore complete)");
                    } else {
                        let stderr = String::from_utf8_lossy(&output.stderr);
                        tracing::warn!("DNS restore may have issues: {}", stderr);
                    }
                }
                Err(e) => {
                    tracing::error!("Failed to restore DNS: {} (backup file preserved at {})", e, get_dns_backup_file_path());
                }
            }

            let _ = Command::new("dscacheutil").arg("-flushcache").output();
            let _ = Command::new("killall").args(["-HUP", "mDNSResponder"]).output();
        }
    }

    /// 恢复原始 DNS 设置 — Windows
    #[cfg(target_os = "windows")]
    fn restore_dns(&self) {
        if let Some(ref dns_settings) = self.original_dns {
            tracing::info!(
                "Restoring DNS for '{}': {:?}",
                dns_settings.service_name, dns_settings.servers
            );

            let ps_cmd = if dns_settings.servers.is_empty()
                || (dns_settings.servers.len() == 1
                    && (dns_settings.servers[0] == "DHCP" || dns_settings.servers[0] == "Empty"))
            {
                // 恢复为 DHCP 自动获取
                format!(
                    "Set-DnsClientServerAddress -InterfaceAlias '{}' -ResetServerAddresses",
                    dns_settings.service_name
                )
            } else {
                // 恢复原始 DNS 服务器列表
                let servers: Vec<String> = dns_settings.servers.iter()
                    .map(|s| format!("'{}'", s))
                    .collect();
                format!(
                    "Set-DnsClientServerAddress -InterfaceAlias '{}' -ServerAddresses @({})",
                    dns_settings.service_name,
                    servers.join(",")
                )
            };

            let result = Command::new("powershell")
                .args(["-NoProfile", "-NonInteractive", "-Command", &ps_cmd])
                .output();

            match result {
                Ok(output) => {
                    if output.status.success() {
                        tracing::info!("DNS restored successfully");
                        let _ = std::fs::remove_file(get_dns_backup_file_path());
                        tracing::info!("DNS backup file removed (restore complete)");
                    } else {
                        let stderr = String::from_utf8_lossy(&output.stderr);
                        tracing::warn!("DNS restore may have issues: {}", stderr.trim());
                    }
                }
                Err(e) => {
                    tracing::error!(
                        "Failed to restore DNS: {} (backup file preserved at {})",
                        e, get_dns_backup_file_path()
                    );
                }
            }

            // 刷新 DNS 缓存
            let _ = Command::new("ipconfig").arg("/flushdns").output();
        }
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    fn restore_dns(&self) {}

    // ========================================================================
    // get_default_gateway()
    // ========================================================================

    /// 获取当前系统默认网关 — macOS
    #[cfg(target_os = "macos")]
    fn get_default_gateway() -> Result<Option<IpAddr>, TunError> {
        let output = Command::new("route")
            .args(["-n", "get", "default"])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!("failed to run 'route get default': {}", e))
            })?;

        if !output.status.success() {
            tracing::warn!("Could not determine default gateway");
            return Ok(None);
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        for line in stdout.lines() {
            let line = line.trim();
            if line.starts_with("gateway:") {
                let gateway_str = line.trim_start_matches("gateway:").trim();
                if let Ok(ip) = gateway_str.parse::<IpAddr>() {
                    return Ok(Some(ip));
                }
            }
        }

        Ok(None)
    }

    /// 获取当前系统默认网关 — Windows
    ///
    /// 使用 PowerShell Get-NetRoute 获取默认路由的 NextHop。
    #[cfg(target_os = "windows")]
    fn get_default_gateway() -> Result<Option<IpAddr>, TunError> {
        let ps_cmd = "(Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Sort-Object RouteMetric | Select-Object -First 1).NextHop";

        let output = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", ps_cmd])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!("failed to run PowerShell Get-NetRoute: {}", e))
            })?;

        if !output.status.success() {
            tracing::warn!("Could not determine default gateway via PowerShell");
            return Ok(None);
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        let gateway_str = stdout.trim();

        if gateway_str.is_empty() {
            tracing::warn!("No default gateway found");
            return Ok(None);
        }

        match gateway_str.parse::<IpAddr>() {
            Ok(ip) => Ok(Some(ip)),
            Err(e) => {
                tracing::warn!("Failed to parse gateway '{}': {}", gateway_str, e);
                Ok(None)
            }
        }
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    fn get_default_gateway() -> Result<Option<IpAddr>, TunError> {
        tracing::warn!("get_default_gateway not implemented for this platform");
        Ok(None)
    }

    // ========================================================================
    // add_route()
    // ========================================================================

    /// 添加一条路由（通过网关 IP）并记录 — macOS
    #[cfg(target_os = "macos")]
    fn add_route(&mut self, destination: &str, gateway: &str) -> Result<(), TunError> {
        tracing::info!("Executing: route -n add -net {} {}", destination, gateway);

        let output = Command::new("route")
            .args(["-n", "add", "-net", destination, gateway])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!(
                    "failed to add route {} via {}: {}",
                    destination, gateway, e
                ))
            })?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        if output.status.success() {
            tracing::info!("Route added successfully: {} -> {}", destination, gateway);
        } else if !stderr.contains("File exists") {
            tracing::warn!("Route add failed (exit={}): stdout={}, stderr={}", output.status, stdout.trim(), stderr.trim());
        } else {
            tracing::info!("Route already exists: {} -> {}", destination, gateway);
        }

        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: gateway.to_string(),
        });

        Ok(())
    }

    /// 添加一条路由（通过网关 IP）并记录 — Windows
    ///
    /// Windows `route add` 命令需要 IP + mask 格式（不支持 CIDR）。
    /// 例如: `route add 10.0.0.0 mask 255.0.0.0 192.168.1.1`
    #[cfg(target_os = "windows")]
    fn add_route(&mut self, destination: &str, gateway: &str) -> Result<(), TunError> {
        let (dest_ip, mask) = cidr_to_ip_and_mask(destination);

        tracing::info!("Executing: route add {} mask {} {}", dest_ip, mask, gateway);

        let output = Command::new("route")
            .args(["add", &dest_ip, "mask", &mask, gateway])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!(
                    "failed to add route {} mask {} via {}: {}",
                    dest_ip, mask, gateway, e
                ))
            })?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);

        if output.status.success() {
            tracing::info!("Route added successfully: {}/{} -> {}", dest_ip, mask, gateway);
        } else if stdout.contains("already exists") || stderr.contains("already exists") {
            tracing::info!("Route already exists: {} mask {} -> {}", dest_ip, mask, gateway);
        } else {
            tracing::warn!(
                "Route add may have failed (exit={}): stdout={}, stderr={}",
                output.status, stdout.trim(), stderr.trim()
            );
        }

        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: gateway.to_string(),
        });

        Ok(())
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    fn add_route(&mut self, destination: &str, gateway: &str) -> Result<(), TunError> {
        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: gateway.to_string(),
        });
        Ok(())
    }

    // ========================================================================
    // add_route_via_interface() (macOS only, Windows uses gateway-based routing)
    // ========================================================================

    /// 添加一条路由（通过接口名）并记录 — macOS point-to-point 接口
    #[cfg(target_os = "macos")]
    fn add_route_via_interface(&mut self, destination: &str, iface: &str) -> Result<(), TunError> {
        tracing::debug!("Adding route: {} via interface {}", destination, iface);

        let output = Command::new("route")
            .args(["-n", "add", "-net", destination, "-interface", iface])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!(
                    "failed to add route {} via interface {}: {}",
                    destination, iface, e
                ))
            })?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            if !stderr.contains("File exists") {
                tracing::warn!("Route add via interface may have failed: {}", stderr);
            }
        }

        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: format!("-interface {}", iface),
        });

        Ok(())
    }

    #[cfg(not(target_os = "macos"))]
    #[allow(dead_code)]
    fn add_route_via_interface(&mut self, destination: &str, iface: &str) -> Result<(), TunError> {
        self.routes_added.push(RouteEntry {
            destination: destination.to_string(),
            gateway: format!("-interface {}", iface),
        });
        Ok(())
    }

    // ========================================================================
    // delete_route()
    // ========================================================================

    /// 删除一条路由 — macOS
    #[cfg(target_os = "macos")]
    fn delete_route(destination: &str) {
        tracing::debug!("Deleting route: {}", destination);
        let result = Command::new("route")
            .args(["-n", "delete", "-net", destination])
            .output();

        match result {
            Ok(output) => {
                if !output.status.success() {
                    let stderr = String::from_utf8_lossy(&output.stderr);
                    tracing::warn!("Failed to delete route {}: {}", destination, stderr);
                }
            }
            Err(e) => {
                tracing::error!("Failed to run route delete command: {}", e);
            }
        }
    }

    /// 删除一条路由 — Windows
    #[cfg(target_os = "windows")]
    fn delete_route(destination: &str) {
        let (dest_ip, _mask) = cidr_to_ip_and_mask(destination);

        tracing::debug!("Deleting route: {} (ip={})", destination, dest_ip);

        let result = Command::new("route")
            .args(["delete", &dest_ip])
            .output();

        match result {
            Ok(output) => {
                if !output.status.success() {
                    let stderr = String::from_utf8_lossy(&output.stderr);
                    let stdout = String::from_utf8_lossy(&output.stdout);
                    tracing::warn!(
                        "Failed to delete route {}: stdout={}, stderr={}",
                        dest_ip, stdout.trim(), stderr.trim()
                    );
                } else {
                    tracing::debug!("Route deleted: {}", dest_ip);
                }
            }
            Err(e) => {
                tracing::error!("Failed to run route delete command: {}", e);
            }
        }
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    fn delete_route(_destination: &str) {}

    // ========================================================================
    // Platform-specific helpers — macOS
    // ========================================================================

    /// 获取当前活跃的网络服务名（如 "Wi-Fi" 或 "Ethernet"）— macOS
    #[cfg(target_os = "macos")]
    fn get_active_network_service() -> Result<String, TunError> {
        let output = Command::new("networksetup")
            .args(["-listallnetworkservices"])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to list network services: {}", e)))?;

        if !output.status.success() {
            return Err(TunError::SystemRoute("networksetup -listallnetworkservices failed".to_string()));
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        for line in stdout.lines().skip(1) {
            let service = line.trim().trim_start_matches('*');
            if service.is_empty() {
                continue;
            }

            let ip_output = Command::new("networksetup")
                .args(["-getinfo", service])
                .output();

            if let Ok(ip_out) = ip_output {
                let info = String::from_utf8_lossy(&ip_out.stdout);
                for info_line in info.lines() {
                    if info_line.starts_with("IP address:") {
                        let addr = info_line.trim_start_matches("IP address:").trim();
                        if !addr.is_empty() && addr != "none" {
                            return Ok(service.to_string());
                        }
                    }
                }
            }
        }

        Ok("Wi-Fi".to_string())
    }

    /// 获取指定网络服务的当前 DNS 服务器列表 — macOS
    #[cfg(target_os = "macos")]
    fn get_dns_servers(service_name: &str) -> Result<Vec<String>, TunError> {
        let output = Command::new("networksetup")
            .args(["-getdnsservers", service_name])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to get DNS servers: {}", e)))?;

        if !output.status.success() {
            return Ok(vec!["Empty".to_string()]);
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        let servers: Vec<String> = stdout
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect();

        if servers.len() == 1 && servers[0].contains("aren't any") {
            return Ok(vec!["Empty".to_string()]);
        }

        Ok(servers)
    }

    /// 获取 TUN 接口的 peer/destination 地址（用于路由网关）— macOS
    #[cfg(target_os = "macos")]
    fn get_tun_peer_address(iface_name: &str) -> Result<String, TunError> {
        let output = Command::new("ifconfig")
            .arg(iface_name)
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!("failed to run ifconfig {}: {}", iface_name, e))
            })?;

        if !output.status.success() {
            return Err(TunError::SystemRoute(format!(
                "ifconfig {} failed: {}",
                iface_name,
                String::from_utf8_lossy(&output.stderr)
            )));
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        for line in stdout.lines() {
            let line = line.trim();
            if line.starts_with("inet ") && line.contains("-->") {
                if let Some(arrow_pos) = line.find("-->") {
                    let after_arrow = &line[arrow_pos + 4..];
                    let peer = after_arrow.split_whitespace().next().unwrap_or("");
                    if peer.parse::<IpAddr>().is_ok() {
                        return Ok(peer.to_string());
                    }
                }
            }
        }

        Err(TunError::SystemRoute(format!(
            "could not find peer address for interface {}",
            iface_name
        )))
    }

    // ========================================================================
    // Platform-specific helpers — Windows
    // ========================================================================

    /// 获取当前活跃的网络接口名 — Windows
    ///
    /// 通过 Get-NetRoute 找到默认路由对应的 InterfaceIndex，
    /// 再通过 Get-NetAdapter 获取接口的 Name。
    #[cfg(target_os = "windows")]
    fn get_active_interface_name() -> Result<String, TunError> {
        let ps_cmd = r#"
            $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Sort-Object RouteMetric | Select-Object -First 1
            if ($route) {
                (Get-NetAdapter -InterfaceIndex $route.InterfaceIndex -ErrorAction SilentlyContinue).Name
            }
        "#;

        let output = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", ps_cmd])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to get active interface name: {}", e)))?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(TunError::SystemRoute(format!(
                "PowerShell get active interface failed: {}", stderr.trim()
            )));
        }

        let name = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if name.is_empty() {
            // 回退：尝试获取第一个 Up 状态的接口
            tracing::warn!("Could not determine active interface via default route, trying fallback");
            let fallback_cmd = "(Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and $_.InterfaceDescription -notlike '*Virtual*' -and $_.InterfaceDescription -notlike '*Loopback*' } | Select-Object -First 1).Name";
            let fallback_output = Command::new("powershell")
                .args(["-NoProfile", "-NonInteractive", "-Command", fallback_cmd])
                .output()
                .map_err(|e| TunError::SystemRoute(format!("fallback interface detection failed: {}", e)))?;

            let fallback_name = String::from_utf8_lossy(&fallback_output.stdout).trim().to_string();
            if fallback_name.is_empty() {
                return Err(TunError::SystemRoute("could not determine active network interface".to_string()));
            }
            return Ok(fallback_name);
        }

        Ok(name)
    }

    /// 获取指定网络接口的当前 DNS 服务器列表 — Windows
    #[cfg(target_os = "windows")]
    fn get_dns_servers_win(interface_name: &str) -> Result<Vec<String>, TunError> {
        let ps_cmd = format!(
            "(Get-DnsClientServerAddress -InterfaceAlias '{}' -AddressFamily IPv4 -ErrorAction SilentlyContinue).ServerAddresses -join ','",
            interface_name
        );

        let output = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &ps_cmd])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to get DNS servers: {}", e)))?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let result = stdout.trim();

        if result.is_empty() {
            // 没有手动设置的 DNS，表示使用 DHCP
            return Ok(vec!["DHCP".to_string()]);
        }

        let servers: Vec<String> = result
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();

        if servers.is_empty() {
            Ok(vec!["DHCP".to_string()])
        } else {
            Ok(servers)
        }
    }

    /// 获取 TUN 接口的网关地址 — Windows
    ///
    /// 在 Windows 上使用 WinTUN 时，TUN 接口的本地地址即可作为路由网关。
    /// 通过 Get-NetIPAddress 获取 TUN 接口的 IPv4 地址。
    #[cfg(target_os = "windows")]
    fn get_tun_gateway_address(iface_name: &str) -> Result<String, TunError> {
        // 尝试通过接口名获取 IP 地址
        let ps_cmd = format!(
            "(Get-NetIPAddress -InterfaceAlias '{}' -AddressFamily IPv4 -ErrorAction SilentlyContinue).IPAddress",
            iface_name
        );

        let output = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &ps_cmd])
            .output()
            .map_err(|e| {
                TunError::SystemRoute(format!("failed to get TUN interface address: {}", e))
            })?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let addr = stdout.trim();

        if !addr.is_empty() && addr.parse::<IpAddr>().is_ok() {
            return Ok(addr.to_string());
        }

        // 回退：尝试通过 ipconfig 解析
        tracing::warn!("PowerShell method failed, trying ipconfig for TUN address");
        let ipconfig_output = Command::new("ipconfig")
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to run ipconfig: {}", e)))?;

        let ipconfig_stdout = String::from_utf8_lossy(&ipconfig_output.stdout);
        let mut in_tun_section = false;

        for line in ipconfig_stdout.lines() {
            if line.contains(iface_name) {
                in_tun_section = true;
                continue;
            }
            // 新的 adapter section 开始
            if in_tun_section && !line.starts_with(' ') && !line.starts_with('\t') && !line.is_empty() {
                break;
            }
            if in_tun_section {
                // 查找 IPv4 Address 行
                if line.contains("IPv4") || line.contains("IP Address") {
                    if let Some(colon_pos) = line.rfind(':') {
                        let ip_str = line[colon_pos + 1..].trim();
                        if ip_str.parse::<IpAddr>().is_ok() {
                            return Ok(ip_str.to_string());
                        }
                    }
                }
            }
        }

        Err(TunError::SystemRoute(format!(
            "could not find gateway address for TUN interface '{}'",
            iface_name
        )))
    }

    /// 获取 TUN 接口的 interface index — Windows (辅助方法)
    #[cfg(target_os = "windows")]
    #[allow(dead_code)]
    fn get_interface_index(iface_name: &str) -> Result<u32, TunError> {
        let ps_cmd = format!(
            "(Get-NetAdapter -Name '{}' -ErrorAction SilentlyContinue).InterfaceIndex",
            iface_name
        );

        let output = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", &ps_cmd])
            .output()
            .map_err(|e| TunError::SystemRoute(format!("failed to get interface index: {}", e)))?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let idx_str = stdout.trim();

        idx_str.parse::<u32>().map_err(|e| {
            TunError::SystemRoute(format!(
                "failed to parse interface index '{}' for '{}': {}",
                idx_str, iface_name, e
            ))
        })
    }
}

// ============================================================================
// Drop implementation
// ============================================================================

impl Drop for RouteGuard {
    fn drop(&mut self) {
        tracing::info!(
            "RouteGuard dropping: restoring {} routes + DNS + {} resolver entries",
            self.routes_added.len(),
            self.resolver_files.len()
        );

        // 1. 清理 DNS 分流规则（/etc/resolver/ 或 NRPT）
        self.restore_intranet_resolvers();

        // 2. 恢复 DNS（在删除路由之前，确保网络仍可达）
        self.restore_dns();

        // 3. 删除所有添加的路由（逆序删除）
        for entry in self.routes_added.iter().rev() {
            Self::delete_route(&entry.destination);
        }

        // 4. 恢复原始默认路由
        if let Some(gateway) = &self.original_gateway {
            tracing::info!("Restoring original default gateway: {}", gateway);

            #[cfg(target_os = "macos")]
            {
                let result = Command::new("route")
                    .args(["-n", "add", "default", &gateway.to_string()])
                    .output();

                match result {
                    Ok(output) => {
                        if output.status.success() {
                            tracing::info!("Default gateway restored successfully");
                        } else {
                            let stderr = String::from_utf8_lossy(&output.stderr);
                            tracing::warn!("Gateway restore may have issues: {}", stderr);
                        }
                    }
                    Err(e) => {
                        tracing::error!("Failed to restore default gateway: {}", e);
                    }
                }
            }

            #[cfg(target_os = "windows")]
            {
                let result = Command::new("route")
                    .args(["add", "0.0.0.0", "mask", "0.0.0.0", &gateway.to_string()])
                    .output();

                match result {
                    Ok(output) => {
                        if output.status.success() {
                            tracing::info!("Default gateway restored successfully");
                        } else {
                            let stdout = String::from_utf8_lossy(&output.stdout);
                            let stderr = String::from_utf8_lossy(&output.stderr);
                            tracing::warn!(
                                "Gateway restore may have issues: stdout={}, stderr={}",
                                stdout.trim(), stderr.trim()
                            );
                        }
                    }
                    Err(e) => {
                        tracing::error!("Failed to restore default gateway: {}", e);
                    }
                }
            }
        }

        tracing::info!("RouteGuard cleanup completed");
    }
}
