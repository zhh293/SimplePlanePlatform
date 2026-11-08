use crate::state::{AppState, OriginalProxyState};
#[cfg(target_os = "macos")]
use std::process::Command;

// A reconnect must keep the snapshot taken before this application first took
// ownership. Keeping it on failure also permits recovery after a partial write.
fn apply_with_original(
    original: &mut Option<OriginalProxyState>,
    read: impl FnOnce() -> Result<OriginalProxyState, String>,
    apply: impl FnOnce() -> Result<(), String>,
) -> Result<(), String> {
    if original.is_none() {
        *original = Some(read()?);
    }
    apply()
}

/// 设置系统代理指向本地端口
pub async fn set_system_proxy(state: &mut AppState) -> Result<(), String> {
    let socks_port = state.proxy_port;
    let http_port = state.http_port;

    apply_with_original(&mut state.original_proxy_state, get_current_proxy_state, || {
        #[cfg(target_os = "macos")]
        set_system_proxy_macos(socks_port, http_port)?;
        #[cfg(target_os = "windows")]
        set_system_proxy_windows(http_port)?;
        Ok(())
    })?;

    log::info!(
        "System proxy set: SOCKS5={}, HTTP={}",
        socks_port,
        http_port
    );
    Ok(())
}

/// 还原系统代理到设置前的状态
pub async fn restore_system_proxy(state: &mut AppState) -> Result<(), String> {
    if let Some(ref original) = state.original_proxy_state {
        #[cfg(target_os = "macos")]
        {
            restore_proxy_macos(original)?;
        }

        #[cfg(target_os = "windows")]
        {
            restore_proxy_windows(original)?;
        }

        log::info!("System proxy restored to original state");
    }
    state.original_proxy_state = None;
    Ok(())
}

/// 获取当前系统代理状态
fn get_current_proxy_state() -> Result<OriginalProxyState, String> {
    #[cfg(target_os = "macos")]
    {
        get_proxy_state_macos()
    }
    #[cfg(target_os = "windows")]
    {
        get_proxy_state_windows()
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        Ok(OriginalProxyState {
            http_enabled: false,
            https_enabled: false,
            socks_enabled: false,
            http_host: String::new(),
            http_port: String::new(),
            https_host: String::new(),
            https_port: String::new(),
            socks_host: String::new(),
            socks_port: String::new(),
        })
    }
}

// ============ macOS 实现 ============

#[cfg(target_os = "macos")]
fn get_network_service() -> String {
    // 获取默认网络服务名称
    let output = Command::new("networksetup")
        .args(["-listallnetworkservices"])
        .output();

    if let Ok(out) = output {
        let stdout = String::from_utf8_lossy(&out.stdout);
        // 优先 Wi-Fi，否则取第一个非标记行
        for line in stdout.lines() {
            if line.contains("Wi-Fi") || line.contains("Ethernet") {
                return line.trim().replace('*', "").trim().to_string();
            }
        }
        // fallback：取第一个非标题行
        for line in stdout.lines().skip(1) {
            let trimmed = line.trim().replace('*', "");
            if !trimmed.is_empty() {
                return trimmed.trim().to_string();
            }
        }
    }
    "Wi-Fi".to_string()
}

#[cfg(target_os = "macos")]
fn get_proxy_state_macos() -> Result<OriginalProxyState, String> {
    let service = get_network_service();

    let socks_output = Command::new("networksetup")
        .args(["-getsocksfirewallproxy", &service])
        .output()
        .map_err(|e| format!("Failed to get SOCKS proxy: {}", e))?;

    let socks_str = String::from_utf8_lossy(&socks_output.stdout);
    let socks_enabled = socks_str.contains("Enabled: Yes");
    let socks_host = parse_proxy_field(&socks_str, "Server");
    let socks_port = parse_proxy_field(&socks_str, "Port");

    let http_output = Command::new("networksetup")
        .args(["-getwebproxy", &service])
        .output()
        .map_err(|e| format!("Failed to get HTTP proxy: {}", e))?;

    let http_str = String::from_utf8_lossy(&http_output.stdout);
    let http_enabled = http_str.contains("Enabled: Yes");
    let http_host = parse_proxy_field(&http_str, "Server");
    let http_port = parse_proxy_field(&http_str, "Port");

    let https_output = Command::new("networksetup")
        .args(["-getsecurewebproxy", &service])
        .output()
        .map_err(|e| format!("Failed to get HTTPS proxy: {}", e))?;

    let https_str = String::from_utf8_lossy(&https_output.stdout);
    let https_enabled = https_str.contains("Enabled: Yes");
    let https_host = parse_proxy_field(&https_str, "Server");
    let https_port = parse_proxy_field(&https_str, "Port");

    Ok(OriginalProxyState {
        http_enabled,
        https_enabled,
        socks_enabled,
        http_host,
        http_port,
        https_host,
        https_port,
        socks_host,
        socks_port,
    })
}

#[cfg(target_os = "macos")]
fn parse_proxy_field(output: &str, field: &str) -> String {
    for line in output.lines() {
        if line.starts_with(field) {
            if let Some(value) = line.split(':').nth(1) {
                return value.trim().to_string();
            }
        }
    }
    String::new()
}

#[cfg(target_os = "macos")]
fn set_system_proxy_macos(socks_port: u16, http_port: u16) -> Result<(), String> {
    let service = get_network_service();
    let host = "127.0.0.1";

    // 将所有 networksetup 命令合并为一个脚本，避免多次弹授权框
    let script = format!(
        r#"networksetup -setsocksfirewallproxy "{service}" {host} {socks_port}
networksetup -setsocksfirewallproxystate "{service}" on
networksetup -setwebproxy "{service}" {host} {http_port}
networksetup -setwebproxystate "{service}" on
networksetup -setsecurewebproxy "{service}" {host} {http_port}
networksetup -setsecurewebproxystate "{service}" on"#,
        service = service,
        host = host,
        socks_port = socks_port,
        http_port = http_port
    );

    run_networksetup_script(&script)
}

#[cfg(target_os = "macos")]
fn restore_proxy_macos(original: &OriginalProxyState) -> Result<(), String> {
    let service = get_network_service();
    let mut commands = Vec::new();

    // 还原 SOCKS
    if original.socks_enabled && !original.socks_host.is_empty() {
        commands.push(format!(
            "networksetup -setsocksfirewallproxy \"{}\" {} {}",
            service, original.socks_host, original.socks_port
        ));
        commands.push(format!(
            "networksetup -setsocksfirewallproxystate \"{}\" on",
            service
        ));
    } else {
        commands.push(format!(
            "networksetup -setsocksfirewallproxystate \"{}\" off",
            service
        ));
    }

    // 还原 HTTP
    if original.http_enabled && !original.http_host.is_empty() {
        commands.push(format!(
            "networksetup -setwebproxy \"{}\" {} {}",
            service, original.http_host, original.http_port
        ));
        commands.push(format!(
            "networksetup -setwebproxystate \"{}\" on",
            service
        ));
    } else {
        commands.push(format!(
            "networksetup -setwebproxystate \"{}\" off",
            service
        ));
    }

    // 还原 HTTPS
    if original.https_enabled && !original.https_host.is_empty() {
        commands.push(format!(
            "networksetup -setsecurewebproxy \"{}\" {} {}",
            service, original.https_host, original.https_port
        ));
        commands.push(format!(
            "networksetup -setsecurewebproxystate \"{}\" on",
            service
        ));
    } else {
        commands.push(format!(
            "networksetup -setsecurewebproxystate \"{}\" off",
            service
        ));
    }

    run_networksetup_script(&commands.join("\n"))
}

/// 执行一组 networksetup 命令
/// 权限不足时静默跳过（开发环境常见，正式 .app 签名后不会有此问题）
#[cfg(target_os = "macos")]
fn run_networksetup_script(script: &str) -> Result<(), String> {
    let output = Command::new("sh")
        .arg("-c")
        .arg(script)
        .output()
        .map_err(|e| format!("networksetup failed: {}", e))?;

    if output.status.success() {
        return Ok(());
    }

    // 权限不足（macOS Ventura+），静默跳过，不阻塞用户
    let stderr = String::from_utf8_lossy(&output.stderr);
    let stdout = String::from_utf8_lossy(&output.stdout);
    log::warn!(
        "networksetup failed (likely permission issue in dev mode), skipping. stdout={}, stderr={}",
        stdout.trim(),
        stderr.trim()
    );
    Ok(())
}

// ============ Windows 实现 ============

#[cfg(target_os = "windows")]
fn get_proxy_state_windows() -> Result<OriginalProxyState, String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let internet_settings = hkcu
        .open_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
        .map_err(|e| format!("Failed to open registry: {}", e))?;

    let proxy_enable: u32 = internet_settings
        .get_value("ProxyEnable")
        .unwrap_or(0);
    let proxy_server: String = internet_settings
        .get_value("ProxyServer")
        .unwrap_or_default();

    Ok(OriginalProxyState {
        http_enabled: proxy_enable == 1,
        https_enabled: proxy_enable == 1,
        socks_enabled: false,
        http_host: proxy_server.clone(),
        http_port: String::new(),
        https_host: proxy_server,
        https_port: String::new(),
        socks_host: String::new(),
        socks_port: String::new(),
    })
}

#[cfg(target_os = "windows")]
fn set_system_proxy_windows(http_port: u16) -> Result<(), String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (internet_settings, _) = hkcu
        .create_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
        .map_err(|e| format!("Failed to open registry: {}", e))?;

    // A bare Windows socks= entry means SOCKS4 to Chromium, including its
    // WebSocket fallback. Our listener supports HTTP CONNECT and SOCKS5 only.
    // HTTP/HTTPS mappings also let WebSockets use the HTTP CONNECT listener.
    let proxy_server = format!("http=127.0.0.1:{http_port};https=127.0.0.1:{http_port}");

    internet_settings
        .set_value("ProxyServer", &proxy_server)
        .map_err(|e| format!("Failed to set ProxyServer: {}", e))?;
    internet_settings
        .set_value("ProxyEnable", &1u32)
        .map_err(|e| format!("Failed to set ProxyEnable: {}", e))?;

    // 通知系统设置已更改
    notify_proxy_change_windows()?;

    Ok(())
}

#[cfg(target_os = "windows")]
fn restore_proxy_windows(original: &OriginalProxyState) -> Result<(), String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (internet_settings, _) = hkcu
        .create_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
        .map_err(|e| format!("Failed to open registry: {}", e))?;

    // Restore the configured address even when the previous proxy was disabled.
    internet_settings
        .set_value("ProxyServer", &original.http_host)
        .map_err(|e| format!("Failed to restore ProxyServer: {}", e))?;
    internet_settings
        .set_value("ProxyEnable", &(u32::from(original.http_enabled)))
        .map_err(|e| format!("Failed to restore ProxyEnable: {}", e))?;

    notify_proxy_change_windows()?;
    Ok(())
}

#[cfg(target_os = "windows")]
fn notify_proxy_change_windows() -> Result<(), String> {
    use windows_sys::Win32::Networking::WinInet::{
        InternetSetOptionW, INTERNET_OPTION_REFRESH, INTERNET_OPTION_SETTINGS_CHANGED,
    };
    // Flushing DNS does not refresh WinINet's cached proxy configuration.
    for option in [INTERNET_OPTION_SETTINGS_CHANGED, INTERNET_OPTION_REFRESH] {
        let ok = unsafe { InternetSetOptionW(std::ptr::null_mut(), option, std::ptr::null(), 0) };
        if ok == 0 {
            return Err(format!("Failed to refresh Windows proxy: {}", std::io::Error::last_os_error()));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    fn snapshot(enabled: bool, server: &str) -> OriginalProxyState {
        OriginalProxyState {
            http_enabled: enabled, https_enabled: enabled, socks_enabled: false,
            http_host: server.into(), http_port: String::new(),
            https_host: server.into(), https_port: String::new(),
            socks_host: String::new(), socks_port: String::new(),
        }
    }

    #[test]
    fn reconnect_and_port_change_restore_the_proxy_from_before_the_session() {
        let system = RefCell::new(snapshot(true, "127.0.0.1:7890"));
        let mut original = None;
        for port in [1080, 1080, 2080] {
            apply_with_original(&mut original, || Ok(system.borrow().clone()), || {
                *system.borrow_mut() = snapshot(true, &format!("127.0.0.1:{port}"));
                Ok(())
            }).unwrap();
        }
        *system.borrow_mut() = original.take().unwrap();
        assert_eq!(system.borrow().http_host, "127.0.0.1:7890");
        assert!(system.borrow().http_enabled);
        // A later session captures the user's new configuration, not stale state.
        *system.borrow_mut() = snapshot(false, "previous.example:8080");
        apply_with_original(&mut original, || Ok(system.borrow().clone()), || Ok(())).unwrap();
        assert!(!original.as_ref().unwrap().http_enabled);
        assert_eq!(original.as_ref().unwrap().http_host, "previous.example:8080");
    }

    #[test]
    fn failed_apply_and_retry_preserve_the_recovery_snapshot() {
        let mut original = None;
        assert!(apply_with_original(&mut original, || Ok(snapshot(false, "old:8080")),
            || Err("partial registry write".into())).is_err());
        apply_with_original(&mut original, || panic!("must not capture a partial update"), || Ok(())).unwrap();
        assert!(!original.as_ref().unwrap().http_enabled);
        assert_eq!(original.as_ref().unwrap().http_host, "old:8080");
    }

    #[test]
    fn failed_snapshot_does_not_modify_the_system() {
        let mut original = None;
        assert!(apply_with_original(&mut original, || Err("cannot read registry".into()),
            || panic!("must not overwrite without a backup")).is_err());
        assert!(original.is_none());
    }
}
