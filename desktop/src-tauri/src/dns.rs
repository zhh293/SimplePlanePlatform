//! DNS 兜底恢复模块
//! 对标 dashboard 的 restoreDnsFallback() 和 restore-dns.sh 逻辑
//! TUN adapter 正常退出时其 Rust Drop handler 会恢复 DNS，
//! 但如果被强杀则需要本模块做 safety net。

use std::process::Command;

/// DNS 备份文件路径（tun-adapter 启动时生成）
const DNS_BACKUP_PATH: &str = "/tmp/tun-adapter-dns-backup.conf";

/// TUN 使用的 FakeDNS 地址
const FAKE_DNS_ADDRESSES: &[&str] = &["198.18.0.2", "198.18.0.1"];

/// 检查系统 DNS 是否仍被 TUN 的 FakeDNS 劫持
#[cfg(target_os = "macos")]
pub fn is_dns_hijacked() -> bool {
    // 检查所有网络服务的 DNS 设置
    let output = Command::new("networksetup")
        .args(["-getdnsservers", "Wi-Fi"])
        .output();

    if let Ok(out) = output {
        let stdout = String::from_utf8_lossy(&out.stdout);
        for fake_dns in FAKE_DNS_ADDRESSES {
            if stdout.contains(fake_dns) {
                return true;
            }
        }
    }

    // 也检查 /etc/resolv.conf
    if let Ok(content) = std::fs::read_to_string("/etc/resolv.conf") {
        for fake_dns in FAKE_DNS_ADDRESSES {
            if content.contains(fake_dns) {
                return true;
            }
        }
    }

    false
}

#[cfg(target_os = "windows")]
pub fn is_dns_hijacked() -> bool {
    // Windows 上 TUN adapter 修改的是 adapter 级别 DNS，一般不会残留
    // 但仍检查主网卡的 DNS
    let output = Command::new("netsh")
        .args(["interface", "ip", "show", "dns"])
        .output();

    if let Ok(out) = output {
        let stdout = String::from_utf8_lossy(&out.stdout);
        for fake_dns in FAKE_DNS_ADDRESSES {
            if stdout.contains(fake_dns) {
                return true;
            }
        }
    }
    false
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
pub fn is_dns_hijacked() -> bool {
    if let Ok(content) = std::fs::read_to_string("/etc/resolv.conf") {
        for fake_dns in FAKE_DNS_ADDRESSES {
            if content.contains(fake_dns) {
                return true;
            }
        }
    }
    false
}

/// 恢复系统 DNS 到正常状态
/// 优先使用备份文件还原，否则使用默认公共 DNS
#[cfg(target_os = "macos")]
pub fn restore_dns() -> Result<String, String> {
    let service = get_network_service();

    // 尝试从备份文件恢复
    if let Ok(backup) = std::fs::read_to_string(DNS_BACKUP_PATH) {
        let dns_servers: Vec<&str> = backup
            .lines()
            .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
            .collect();

        if !dns_servers.is_empty() {
            log::info!("Restoring DNS from backup: {:?}", dns_servers);
            let mut args = vec!["-setdnsservers", &service];
            args.extend(dns_servers.iter());

            let output = Command::new("networksetup")
                .args(&args)
                .output()
                .map_err(|e| format!("networksetup failed: {}", e))?;

            if output.status.success() {
                // 清理备份文件
                let _ = std::fs::remove_file(DNS_BACKUP_PATH);
                flush_dns_cache();
                return Ok(format!("DNS 已从备份恢复: {}", dns_servers.join(", ")));
            }
        }
    }

    // 备份文件不存在或失败，使用"空"恢复（让系统使用 DHCP 分配的 DNS）
    log::info!("Restoring DNS to DHCP default for service: {}", service);
    let output = Command::new("networksetup")
        .args(["-setdnsservers", &service, "empty"])
        .output()
        .map_err(|e| format!("networksetup failed: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("DNS 恢复失败: {}", stderr));
    }

    // 清理可能残留的备份文件
    let _ = std::fs::remove_file(DNS_BACKUP_PATH);
    flush_dns_cache();
    Ok("DNS 已恢复为 DHCP 自动获取".to_string())
}

#[cfg(target_os = "windows")]
pub fn restore_dns() -> Result<String, String> {
    // Windows: 将主网卡 DNS 设为自动获取
    let output = Command::new("netsh")
        .args(["interface", "ip", "set", "dns", "name=\"Wi-Fi\"", "source=dhcp"])
        .output()
        .map_err(|e| format!("netsh failed: {}", e))?;

    if !output.status.success() {
        // 尝试以太网
        let _ = Command::new("netsh")
            .args(["interface", "ip", "set", "dns", "name=\"Ethernet\"", "source=dhcp"])
            .output();
    }

    flush_dns_cache();
    Ok("DNS 已恢复为 DHCP 自动获取".to_string())
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
pub fn restore_dns() -> Result<String, String> {
    // Linux: 尝试从备份还原 resolv.conf
    if let Ok(backup) = std::fs::read_to_string(DNS_BACKUP_PATH) {
        let _ = std::fs::write("/etc/resolv.conf", backup);
        let _ = std::fs::remove_file(DNS_BACKUP_PATH);
        return Ok("DNS 已从备份恢复".to_string());
    }
    // 写入默认 DNS
    let default_resolv = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n";
    std::fs::write("/etc/resolv.conf", default_resolv)
        .map_err(|e| format!("Failed to write resolv.conf: {}", e))?;
    Ok("DNS 已恢复为默认 (8.8.8.8)".to_string())
}

/// DNS 兜底恢复：检查是否被劫持，是则恢复
pub fn restore_dns_if_needed() -> Option<String> {
    if is_dns_hijacked() {
        log::warn!("DNS is still hijacked by TUN FakeDNS, restoring...");
        match restore_dns() {
            Ok(msg) => {
                log::info!("DNS restored: {}", msg);
                Some(msg)
            }
            Err(e) => {
                log::error!("DNS restore failed: {}", e);
                Some(format!("DNS 恢复失败: {}", e))
            }
        }
    } else {
        log::info!("DNS check passed, no hijacking detected");
        None
    }
}

/// 刷新 DNS 缓存
fn flush_dns_cache() {
    #[cfg(target_os = "macos")]
    {
        let _ = Command::new("dscacheutil").arg("-flushcache").output();
        let _ = Command::new("sudo")
            .args(["-n", "killall", "-HUP", "mDNSResponder"])
            .output();
    }
    #[cfg(target_os = "windows")]
    {
        let _ = Command::new("ipconfig").arg("/flushdns").output();
    }
}

/// 获取 macOS 网络服务名
#[cfg(target_os = "macos")]
fn get_network_service() -> String {
    let output = Command::new("networksetup")
        .args(["-listallnetworkservices"])
        .output();

    if let Ok(out) = output {
        let stdout = String::from_utf8_lossy(&out.stdout);
        for line in stdout.lines() {
            if line.contains("Wi-Fi") || line.contains("Ethernet") {
                return line.trim().replace('*', "").trim().to_string();
            }
        }
        for line in stdout.lines().skip(1) {
            let trimmed = line.trim().replace('*', "");
            if !trimmed.is_empty() {
                return trimmed.trim().to_string();
            }
        }
    }
    "Wi-Fi".to_string()
}

/// TUN 诊断：检查权限配置是否正确
pub fn diagnose_tun() -> Vec<String> {
    let mut results = Vec::new();

    // 1. 检查 tun-adapter 二进制是否存在
    let tun_path = crate::process::get_tun_path_public();
    if tun_path.exists() {
        #[cfg(target_os = "windows")]
        {
            let wintun_path = tun_path
                .parent()
                .unwrap_or(std::path::Path::new("."))
                .join("wintun.dll");
            if wintun_path.exists() {
                results.push(format!("Wintun DLL exists: {:?}", wintun_path));
            } else {
                results.push(format!("Wintun DLL missing: {:?}", wintun_path));
            }
        }
        results.push(format!("✓ tun-adapter 存在: {:?}", tun_path));
    } else {
        results.push(format!("✗ tun-adapter 缺失: {:?}", tun_path));
        return results;
    }

    // 2. 检查 sudo 是否可用（macOS/Linux）
    #[cfg(not(target_os = "windows"))]
    {
        let sudo_check = Command::new("sudo").arg("-n").arg("true").output();
        match sudo_check {
            Ok(out) if out.status.success() => {
                results.push("✓ sudo 免密已配置".to_string());
            }
            _ => {
                results.push("✗ sudo 免密未配置 — TUN 模式需要一次性配置 sudoers 规则".to_string());
                results.push("  修复方法: 在终端执行以下命令:".to_string());
                results.push(format!(
                    "  echo \"$USER ALL=(ALL) NOPASSWD: {:?}\" | sudo tee /etc/sudoers.d/simpleplane-tun",
                    tun_path
                ));
            }
        }
    }

    // 3. 检查 TUN 网卡接口
    #[cfg(target_os = "macos")]
    {
        let ifconfig = Command::new("ifconfig").arg("utun9").output();
        match ifconfig {
            Ok(out) if out.status.success() => {
                let stdout = String::from_utf8_lossy(&out.stdout);
                if stdout.contains("198.18.0.1") {
                    results.push("✓ TUN 网卡 utun9 已存在且配置正确 (198.18.0.1)".to_string());
                } else {
                    results.push("△ TUN 网卡 utun9 存在但地址异常".to_string());
                }
            }
            _ => {
                results.push("- TUN 网卡 utun9 未创建（正常 — 仅在 TUN 模式运行时存在）".to_string());
            }
        }
    }

    // 4. 检查 DNS 状态
    if is_dns_hijacked() {
        results.push("⚠ 系统 DNS 仍指向 FakeDNS 地址，可能是上次 TUN 未正常退出".to_string());
        results.push("  建议执行「恢复网络」操作".to_string());
    } else {
        results.push("✓ 系统 DNS 正常".to_string());
    }

    results
}
