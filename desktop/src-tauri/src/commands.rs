use crate::config;
use crate::dns;
use crate::logs::LogEntry;
use crate::process;
use crate::proxy;
use crate::state::{AppState, ProxyMode, ServiceStatus};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::Mutex;

/// 状态响应结构
#[derive(Debug, Serialize)]
pub struct StatusResponse {
    pub proxy_status: ServiceStatus,
    pub tun_status: ServiceStatus,
    pub proxy_mode: ProxyMode,
    pub proxy_port: u16,
    pub http_port: u16,
    pub proxy_port_listening: bool,
}

/// 预设配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Preset {
    pub name: String,
    pub description: String,
    pub config: config::ProxyConfig,
}

/// 连接 —— 启动代理并设置系统代理
#[tauri::command]
pub async fn connect(
    mode: String,
    state: tauri::State<'_, Arc<Mutex<AppState>>>,
) -> Result<String, String> {
    let mut s = state.lock().await;

    let proxy_mode = match mode.as_str() {
        "tun" => ProxyMode::Tun,
        _ => ProxyMode::System,
    };
    s.proxy_mode = proxy_mode.clone();

    // 1. 启动代理核心
    process::start_proxy_local(&mut s).await?;

    // 2. 根据模式设置系统代理或启动 TUN
    match proxy_mode {
        ProxyMode::System => {
            if let Err(e) = proxy::set_system_proxy(&mut s).await {
                // 系统代理设置失败不是致命错误，代理核心已经在运行
                // 用户可以手动配置系统代理指向 127.0.0.1:1080
                log::warn!("Failed to set system proxy (non-fatal): {}", e);
                s.log_manager
                    .lock()
                    .await
                    .push("warning", "proxy", &format!("系统代理设置失败（代理已启动，可手动配置）: {}", e));
            }
        }
        ProxyMode::Tun => {
            match process::start_tun_adapter(&mut s).await {
                Ok(_) => {}
                Err(e) => {
                    // TUN 失败，降级到系统代理模式
                    log::warn!("TUN failed, falling back to system proxy: {}", e);
                    s.proxy_mode = ProxyMode::System;
                    if let Err(proxy_err) = proxy::set_system_proxy(&mut s).await {
                        log::warn!("System proxy fallback also failed: {}", proxy_err);
                    }
                    return Ok(format!(
                        "TUN 模式启动失败，已降级到系统代理模式。原因：{}",
                        e
                    ));
                }
            }
        }
    }

    Ok("connected".to_string())
}

/// 断开 —— 停止代理并还原系统代理
#[tauri::command]
pub async fn disconnect(state: tauri::State<'_, Arc<Mutex<AppState>>>) -> Result<String, String> {
    let mut s = state.lock().await;

    // 1. 还原系统代理（失败不阻塞断开流程）
    if s.original_proxy_state.is_some() {
        if let Err(e) = proxy::restore_system_proxy(&mut s).await {
            log::warn!("Failed to restore system proxy (non-fatal): {}", e);
        }
    }

    // 2. 停止 TUN（如果有）
    if s.tun_status != ServiceStatus::Stopped {
        process::stop_tun_adapter(&mut s).await?;
    }

    // 3. 停止代理核心
    process::stop_proxy_local(&mut s).await?;

    Ok("disconnected".to_string())
}

/// 查询状态
#[tauri::command]
pub async fn status(state: tauri::State<'_, Arc<Mutex<AppState>>>) -> Result<StatusResponse, String> {
    let mut s = state.lock().await;

    let port = s.proxy_port;
    let proxy_port_listening = process::is_port_listening(port);

    // 如果端口不再监听但状态仍显示 running，更新为 error
    if s.proxy_status == ServiceStatus::Running && !proxy_port_listening {
        // 检查进程是否还活着
        if let Some(ref mut child) = s.proxy_process {
            match child.try_wait() {
                Ok(Some(_)) => {
                    // 进程已退出
                    s.proxy_status = ServiceStatus::Error;
                    s.proxy_process = None;
                }
                Ok(None) => {
                    // 进程还在，可能端口还没就绪
                }
                Err(_) => {
                    s.proxy_status = ServiceStatus::Error;
                }
            }
        } else {
            s.proxy_status = ServiceStatus::Error;
        }
    }

    Ok(StatusResponse {
        proxy_status: s.proxy_status.clone(),
        tun_status: s.tun_status.clone(),
        proxy_mode: s.proxy_mode.clone(),
        proxy_port: s.proxy_port,
        http_port: s.http_port,
        proxy_port_listening,
    })
}

/// 获取代理配置
#[tauri::command]
pub async fn get_config() -> Result<config::ProxyConfig, String> {
    config::load_proxy_config()
}

/// 保存代理配置
#[tauri::command]
pub async fn save_config(
    config_data: config::ProxyConfig,
    state: tauri::State<'_, Arc<Mutex<AppState>>>,
) -> Result<String, String> {
    // 更新运行时端口
    let mut s = state.lock().await;
    s.proxy_port = config_data.local.port;
    s.http_port = config_data.local.http_proxy_port;
    drop(s);

    config::save_proxy_config(&config_data)?;
    Ok("saved".to_string())
}

/// 获取 TUN 配置
#[tauri::command]
pub async fn get_tun_config() -> Result<config::TunConfig, String> {
    config::load_tun_config()
}

/// 保存 TUN 配置
#[tauri::command]
pub async fn save_tun_config(config_data: config::TunConfig) -> Result<String, String> {
    config::save_tun_config(&config_data)?;
    Ok("saved".to_string())
}

/// 获取 TUN 配置原始文本（供前端编辑器使用）
#[tauri::command]
pub async fn get_tun_config_raw() -> Result<String, String> {
    config::load_tun_config_raw()
}

/// 保存 TUN 配置原始文本
#[tauri::command]
pub async fn save_tun_config_raw(content: String) -> Result<String, String> {
    config::save_tun_config_raw(&content)?;
    Ok("saved".to_string())
}

/// 获取路由规则
#[tauri::command]
pub async fn get_route_config() -> Result<config::RouteConfig, String> {
    let proxy_config = config::load_proxy_config()?;
    Ok(proxy_config.route)
}

/// 保存路由规则
#[tauri::command]
pub async fn save_route_config(route: config::RouteConfig) -> Result<String, String> {
    let mut proxy_config = config::load_proxy_config()?;
    proxy_config.route = route;
    config::save_proxy_config(&proxy_config)?;
    Ok("saved".to_string())
}

/// 获取预设列表
#[tauri::command]
pub async fn get_presets() -> Result<Vec<Preset>, String> {
    load_presets()
}

/// 保存预设
#[tauri::command]
pub async fn save_preset(preset: Preset) -> Result<String, String> {
    let mut presets = load_presets().unwrap_or_default();
    // 如果同名预设已存在，覆盖之
    if let Some(pos) = presets.iter().position(|p| p.name == preset.name) {
        presets[pos] = preset;
    } else {
        presets.push(preset);
    }
    write_presets(&presets)?;
    Ok("preset saved".to_string())
}

/// 删除预设
#[tauri::command]
pub async fn delete_preset(name: String) -> Result<String, String> {
    let mut presets = load_presets().unwrap_or_default();
    presets.retain(|p| p.name != name);
    write_presets(&presets)?;
    Ok("preset deleted".to_string())
}

/// 应用预设（加载到当前配置）
#[tauri::command]
pub async fn apply_preset(
    name: String,
    state: tauri::State<'_, Arc<Mutex<AppState>>>,
) -> Result<String, String> {
    let presets = load_presets().unwrap_or_default();
    let preset = presets
        .iter()
        .find(|p| p.name == name)
        .ok_or_else(|| format!("Preset '{}' not found", name))?;

    // 保存预设中的配置为当前配置
    let mut s = state.lock().await;
    s.proxy_port = preset.config.local.port;
    s.http_port = preset.config.local.http_proxy_port;
    drop(s);

    config::save_proxy_config(&preset.config)?;
    Ok(format!("Preset '{}' applied", name))
}

/// 导入服务器配置（支持 YAML 格式）
/// 前端传入 YAML 字符串，后端解析出服务器列表返回
#[tauri::command]
pub async fn import_servers(yaml_content: String) -> Result<Vec<config::RemoteConfig>, String> {
    config::parse_servers_from_yaml(&yaml_content)
}

/// 紧急重置网络 —— 关闭代理、还原系统设置、恢复 DNS
#[tauri::command]
pub async fn reset_network(state: tauri::State<'_, Arc<Mutex<AppState>>>) -> Result<String, String> {
    let mut s = state.lock().await;

    // 停止所有进程
    if s.tun_status != ServiceStatus::Stopped {
        let _ = process::stop_tun_adapter(&mut s).await;
    }
    if s.proxy_status != ServiceStatus::Stopped {
        let _ = process::stop_proxy_local(&mut s).await;
    }

    // 强制关闭系统代理
    if s.original_proxy_state.is_some() {
        let _ = proxy::restore_system_proxy(&mut s).await;
    } else {
        // 即使没有保存原始状态，也尝试关闭系统代理
        #[cfg(target_os = "macos")]
        {
            let _ = std::process::Command::new("networksetup")
                .args(["-setsocksfirewallproxystate", "Wi-Fi", "off"])
                .output();
            let _ = std::process::Command::new("networksetup")
                .args(["-setwebproxystate", "Wi-Fi", "off"])
                .output();
            let _ = std::process::Command::new("networksetup")
                .args(["-setsecurewebproxystate", "Wi-Fi", "off"])
                .output();
        }
    }

    // DNS 兜底恢复（对标 dashboard 的 restoreDnsFallback）
    let dns_msg = dns::restore_dns_if_needed();
    let result = if let Some(msg) = dns_msg {
        format!("network reset complete; DNS: {}", msg)
    } else {
        "network reset complete".to_string()
    };

    s.log_manager
        .lock()
        .await
        .push("success", "system", "网络已恢复");

    Ok(result)
}

/// 获取应用实时日志（从内存环形缓冲区读取）
#[tauri::command]
pub async fn get_logs(
    service: Option<String>,
    count: Option<usize>,
    state: tauri::State<'_, Arc<Mutex<AppState>>>,
) -> Result<Vec<LogEntry>, String> {
    let s = state.lock().await;
    let lm = s.log_manager.lock().await;
    let max = count.unwrap_or(200);
    Ok(lm.get_logs(service.as_deref(), max))
}

/// 获取自某时间戳之后的增量日志（前端轮询使用）
#[tauri::command]
pub async fn get_logs_stream(
    since: u64,
    service: Option<String>,
    state: tauri::State<'_, Arc<Mutex<AppState>>>,
) -> Result<Vec<LogEntry>, String> {
    let s = state.lock().await;
    let lm = s.log_manager.lock().await;
    Ok(lm.get_logs_since(since, service.as_deref()))
}

/// 清空日志
#[tauri::command]
pub async fn clear_logs(
    service: Option<String>,
    state: tauri::State<'_, Arc<Mutex<AppState>>>,
) -> Result<String, String> {
    let s = state.lock().await;
    let mut lm = s.log_manager.lock().await;
    lm.clear(service.as_deref());
    Ok("logs cleared".to_string())
}

/// 获取配置文件目录路径
#[tauri::command]
pub async fn get_config_dir() -> Result<String, String> {
    Ok(config::get_config_dir().to_string_lossy().to_string())
}

/// TUN 诊断 —— 检查二进制、权限、网卡、DNS 状态
#[tauri::command]
pub async fn diagnose_tun() -> Result<Vec<String>, String> {
    Ok(dns::diagnose_tun())
}

// ============ 预设文件操作 ============

fn get_presets_path() -> std::path::PathBuf {
    config::get_config_dir().join("presets.json")
}

fn load_presets() -> Result<Vec<Preset>, String> {
    let path = get_presets_path();
    if !path.exists() {
        // 返回内置默认预设
        return Ok(get_builtin_presets());
    }

    let content =
        std::fs::read_to_string(&path).map_err(|e| format!("Failed to read presets: {}", e))?;

    serde_json::from_str(&content).map_err(|e| format!("Failed to parse presets: {}", e))
}

fn write_presets(presets: &[Preset]) -> Result<(), String> {
    let path = get_presets_path();
    let content = serde_json::to_string_pretty(presets)
        .map_err(|e| format!("Failed to serialize presets: {}", e))?;
    std::fs::write(&path, content).map_err(|e| format!("Failed to write presets: {}", e))
}

/// 内置预设（首次运行时作为默认值）
fn get_builtin_presets() -> Vec<Preset> {
    vec![
        Preset {
            name: "办公模式".to_string(),
            description: "仅代理海外站点，国内直连。适合日常办公使用。".to_string(),
            config: config::ProxyConfig {
                local: config::LocalConfig {
                    port: 1080,
                    http_proxy_enabled: true,
                    http_proxy_port: 1080,
                },
                remote: config::RemoteConfig {
                    host: "54.234.196.30".to_string(),
                    port: 9090,
                    cipher: "chacha20".to_string(),
                    key: String::new(),
                },
                route: config::RouteConfig {
                    default_route: "direct".to_string(),
                    proxy_list: vec![
                        "google.com".to_string(),
                        "github.com".to_string(),
                        "stackoverflow.com".to_string(),
                        "npmjs.com".to_string(),
                        "docker.io".to_string(),
                        "cloudflare.com".to_string(),
                    ],
                    direct_list: vec![],
                },
            },
        },
        Preset {
            name: "全局代理".to_string(),
            description: "所有流量通过代理。适合需要全局翻墙的场景。".to_string(),
            config: config::ProxyConfig {
                local: config::LocalConfig {
                    port: 1080,
                    http_proxy_enabled: true,
                    http_proxy_port: 1080,
                },
                remote: config::RemoteConfig {
                    host: "54.234.196.30".to_string(),
                    port: 9090,
                    cipher: "chacha20".to_string(),
                    key: String::new(),
                },
                route: config::RouteConfig {
                    default_route: "proxy".to_string(),
                    proxy_list: vec![],
                    direct_list: vec![
                        "meituan.com".to_string(),
                        "sankuai.com".to_string(),
                        "localhost".to_string(),
                        "10.0.0.0/8".to_string(),
                        "172.16.0.0/12".to_string(),
                        "192.168.0.0/16".to_string(),
                    ],
                },
            },
        },
        Preset {
            name: "开发调试".to_string(),
            description: "为开发者优化的配置，代理 GitHub/NPM/Docker 等开发资源。".to_string(),
            config: config::ProxyConfig {
                local: config::LocalConfig {
                    port: 7890,
                    http_proxy_enabled: true,
                    http_proxy_port: 7891,
                },
                remote: config::RemoteConfig {
                    host: "54.234.196.30".to_string(),
                    port: 9090,
                    cipher: "chacha20".to_string(),
                    key: String::new(),
                },
                route: config::RouteConfig {
                    default_route: "direct".to_string(),
                    proxy_list: vec![
                        "github.com".to_string(),
                        "githubusercontent.com".to_string(),
                        "npmjs.org".to_string(),
                        "npmjs.com".to_string(),
                        "yarnpkg.com".to_string(),
                        "docker.io".to_string(),
                        "docker.com".to_string(),
                        "gcr.io".to_string(),
                        "ghcr.io".to_string(),
                        "registry.k8s.io".to_string(),
                        "pypi.org".to_string(),
                        "crates.io".to_string(),
                        "rubygems.org".to_string(),
                        "golang.org".to_string(),
                        "pkg.go.dev".to_string(),
                        "rust-lang.org".to_string(),
                        "stackexchange.com".to_string(),
                        "stackoverflow.com".to_string(),
                    ],
                    direct_list: vec![],
                },
            },
        },
    ]
}
