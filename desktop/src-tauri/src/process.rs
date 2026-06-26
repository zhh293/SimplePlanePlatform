use std::net::TcpStream;
use std::process::{Child, Command, Stdio};
use std::time::Duration;

use crate::config;
use crate::dns;
use crate::logs;
use crate::state::{AppState, ServiceStatus};

/// 检查端口是否在监听
pub fn is_port_listening(port: u16) -> bool {
    TcpStream::connect_timeout(
        &format!("127.0.0.1:{}", port).parse().unwrap(),
        Duration::from_millis(500),
    )
    .is_ok()
}

/// 获取资源目录路径
fn get_resource_path() -> std::path::PathBuf {
// 开发模式下使用相对路径
if cfg!(debug_assertions) {
std::path::PathBuf::from("resources")
} else {
// 生产模式下，资源在 app bundle 内
let exe = std::env::current_exe().unwrap_or_default();
let exe_dir = exe.parent().unwrap_or(std::path::Path::new("."));

#[cfg(target_os = "macos")]
{
// macOS: AppName.app/Contents/MacOS/executable -> ../Resources/resources/
// Tauri 将 bundle.resources 中的文件放在 Contents/Resources/resources/ 下
exe_dir.join("../Resources/resources")
}
#[cfg(target_os = "windows")]
{
// Windows: 安装目录/executable -> ./resources
exe_dir.join("resources")
}
#[cfg(not(any(target_os = "macos", target_os = "windows")))]
{
            exe_dir.join("resources")
        }
    }
}

/// 获取 java 可执行路径：优先使用内嵌 JRE，不存在则 fallback 到系统 java
fn get_java_path() -> std::path::PathBuf {
    let resources = get_resource_path();
    #[cfg(target_os = "windows")]
    let bundled = resources.join("jre").join("bin").join("java.exe");
    #[cfg(not(target_os = "windows"))]
    let bundled = resources.join("jre").join("bin").join("java");

    if bundled.exists() {
        return bundled;
    }

    // Fallback: 查找系统 PATH 中的 java
    if let Ok(output) = std::process::Command::new("which").arg("java").output() {
        if output.status.success() {
            let path_str = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !path_str.is_empty() {
                return std::path::PathBuf::from(path_str);
            }
        }
    }

    // Windows fallback
    #[cfg(target_os = "windows")]
    {
        if let Ok(output) = std::process::Command::new("where").arg("java").output() {
            if output.status.success() {
                let path_str = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if let Some(first_line) = path_str.lines().next() {
                    return std::path::PathBuf::from(first_line);
                }
            }
        }
    }

    // 最终返回内嵌路径（后续会报错 JRE not found）
    bundled
}

/// 获取 proxy-local.jar 路径：优先 resources 内，fallback 到 Maven 构建产物
fn get_jar_path() -> std::path::PathBuf {
    let bundled = get_resource_path().join("proxy-local.jar");
    if bundled.exists() {
        return bundled;
    }

    // Fallback: 开发时使用 Maven 编译产物
    let exe = std::env::current_exe().unwrap_or_default();
    // 从 desktop/src-tauri/target/release/exe 推导项目根目录
    let project_root = exe
        .parent() // target/release
        .and_then(|p| p.parent()) // target
        .and_then(|p| p.parent()) // src-tauri
        .and_then(|p| p.parent()) // desktop
        .and_then(|p| p.parent()); // project root

    if let Some(root) = project_root {
        let dev_jar = root.join("proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar");
        if dev_jar.exists() {
            return dev_jar;
        }
    }

    bundled
}

/// 获取 tun-adapter 可执行路径（内部使用）
fn get_tun_path() -> std::path::PathBuf {
    let resources = get_resource_path();
    #[cfg(target_os = "windows")]
    {
        resources.join("tun-adapter.exe")
    }
    #[cfg(not(target_os = "windows"))]
    {
        resources.join("tun-adapter")
    }
}

/// 获取 tun-adapter 路径（公开，供 dns 诊断模块使用）
pub fn get_tun_path_public() -> std::path::PathBuf {
    get_tun_path()
}


/// 从当前 exe 位置向上寻找项目根目录（含 proxy-local/ 的目录）
fn find_project_root() -> Option<std::path::PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let mut dir = exe.parent()?;
    // 最多向上找 6 层
    for _ in 0..6 {
        if dir.join("proxy-local").is_dir() {
            return Some(dir.to_path_buf());
        }
        dir = dir.parent()?;
    }
    // 也尝试从 cwd 向上找
    let cwd = std::env::current_dir().ok()?;
    let mut dir = cwd.as_path();
    for _ in 0..4 {
        if dir.join("proxy-local").is_dir() {
            return Some(dir.to_path_buf());
        }
        dir = dir.parent()?;
    }
    None
}

/// 启动 proxy-local（Java 代理核心）
/// 对标 dashboard 的 startProxyLocal：先检测端口是否已被占用（外部复用），再决定是否启动
pub async fn start_proxy_local(state: &mut AppState) -> Result<(), String> {
    if state.proxy_status == ServiceStatus::Running {
        return Ok(());
    }

    let port = state.proxy_port;

    // 外部进程复用检测（对标 dashboard 的逻辑）
    if is_port_listening(port) {
        log::info!(
            "Port {} already in use, reusing external process",
            port
        );
        state.proxy_status = ServiceStatus::Running;
        state.proxy_external = true;
        state.proxy_process = None;

        // 写入日志
        let lm = state.log_manager.clone();
        lm.lock().await.push(
            "info",
            "proxy-local",
            &format!("端口 {} 已被占用，复用外部进程 (running external)", port),
        );

        return Ok(());
    }

    state.proxy_status = ServiceStatus::Starting;
    state.proxy_external = false;

    let java_path = get_java_path();
    let jar_path = get_jar_path();

    if !java_path.exists() {
        state.proxy_status = ServiceStatus::Error;
        return Err(format!("JRE not found at: {:?}", java_path));
    }

    if !jar_path.exists() {
        state.proxy_status = ServiceStatus::Error;
        return Err(format!("proxy-local.jar not found at: {:?}", jar_path));
    }

    log::info!("Starting proxy-local with JRE: {:?}", java_path);
    log::info!("Jar path: {:?}", jar_path);

    let mut cmd = Command::new(&java_path);

    // 设置工作目录：开发模式用项目根目录，生产模式用资源目录
    let work_dir = if cfg!(debug_assertions) {
        // 开发模式：项目根
        std::env::current_dir().unwrap_or_default()
    } else {
        // 生产模式也用 exe 所在的顶层目录
        let exe = std::env::current_exe().unwrap_or_default();
        #[cfg(target_os = "macos")]
        {
            // .app/Contents/MacOS/exe -> .app/Contents/Resources（这是资源目录）
            exe.parent()
                .unwrap_or(std::path::Path::new("."))
                .join("../Resources")
        }
        #[cfg(not(target_os = "macos"))]
        {
            exe.parent()
                .unwrap_or(std::path::Path::new("."))
                .to_path_buf()
        }
    };

    // 开发环境下找项目根目录（从 desktop/src-tauri 向上找到含 proxy-local/ 的目录）
    let project_root = find_project_root().unwrap_or(work_dir);
    cmd.current_dir(&project_root);
    log::info!("Working dir: {:?}", project_root);

    // 不再传递自定义 DNS 参数：
    // 1. 系统代理模式：DirectRelayHandler 应使用系统 DNS（JVM 默认），
    //    自定义 DNS (114/223) 的 UDP 查询可能被网络封锁导致解析失败。
    // 2. TUN 模式：所有流量（含 Direct）都经 SOCKS5 → proxy-remote，
    //    由远端做 DNS 解析，本地 DirectRelayHandler 不会被调用；
    //    即使被调用，UDP 查询也会被 TUN 截获造成回环。

    cmd.arg("-jar")
        .arg(&jar_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    // 生成 Java 兼容格式的配置文件（扁平驼峰命名：localPort, remoteServers 等）
    // Rust 桌面端的 proxy.yml 是嵌套蛇形格式，与 Java 不兼容
    // 因此转换为 proxy-java.yml 单独给 Java 进程使用
    let tun_mode = state.proxy_mode == crate::state::ProxyMode::Tun;
    match config::write_java_config(tun_mode) {
        Ok(java_config_path) => {
            cmd.arg(java_config_path.to_str().unwrap_or_default());
            log::info!("Generated Java-compatible config: {:?}", java_config_path);
        }
        Err(e) => {
            log::warn!("Failed to generate Java config, using jar defaults: {}", e);
        }
    }

    match cmd.spawn() {
        Ok(mut child) => {
            // 启动日志捕获线程
            if let Some(stdout) = child.stdout.take() {
                logs::spawn_log_reader(
                    stdout,
                    "proxy-local".to_string(),
                    state.log_manager.clone(),
                );
            }
            if let Some(stderr) = child.stderr.take() {
                logs::spawn_log_reader(
                    stderr,
                    "proxy-local".to_string(),
                    state.log_manager.clone(),
                );
            }

            state.proxy_process = Some(child);

            // 等待端口就绪（最多等 10 秒），同时检测进程 stdout 输出
            for _ in 0..20 {
                tokio::time::sleep(Duration::from_millis(500)).await;
                if is_port_listening(port) {
                    state.proxy_status = ServiceStatus::Running;
                    log::info!("proxy-local is running on port {}", port);
                    state
                        .log_manager
                        .lock()
                        .await
                        .push("success", "proxy-local", &format!("已启动，监听端口 {}", port));
                    return Ok(());
                }
                // 检查进程是否已退出
                if let Some(ref mut p) = state.proxy_process {
                    if let Ok(Some(exit)) = p.try_wait() {
                        state.proxy_status = ServiceStatus::Error;
                        // 从日志管理器取最近的错误信息
                        let recent_logs = state.log_manager.lock().await.get_recent("proxy-local", 5);
                        let log_detail = if recent_logs.is_empty() {
                            String::new()
                        } else {
                            format!("\n最近日志:\n{}", recent_logs.join("\n"))
                        };
                        return Err(format!(
                            "启动异常，退出码为{}{}",
                            exit.code().unwrap_or(-1),
                            log_detail
                        ));
                    }
                }
            }
            state.proxy_status = ServiceStatus::Error;
            Err("proxy-local started but port not listening after 10s".to_string())
        }
        Err(e) => {
            state.proxy_status = ServiceStatus::Error;
            Err(format!("Failed to start proxy-local: {}", e))
        }
    }
}

/// 停止 proxy-local
pub async fn stop_proxy_local(state: &mut AppState) -> Result<(), String> {
    // 外部进程不停止
    if state.proxy_external {
        log::info!("proxy-local is external, not stopping");
        state.proxy_status = ServiceStatus::Stopped;
        state.proxy_external = false;
        state
            .log_manager
            .lock()
            .await
            .push("info", "proxy-local", "外部进程，已断开连接但未停止进程");
        return Ok(());
    }

    state.proxy_status = ServiceStatus::Stopping;

    if let Some(ref mut child) = state.proxy_process {
        // 先尝试优雅停止
        let _ = child.kill();

        // 等待进程结束（最多 3 秒）
        for _ in 0..6 {
            tokio::time::sleep(Duration::from_millis(500)).await;
            match child.try_wait() {
                Ok(Some(_)) => break,
                _ => continue,
            }
        }

        // 如果仍在运行，强制终止
        let _ = child.kill();
        let _ = child.wait();
    }

    state.proxy_process = None;
    state.proxy_status = ServiceStatus::Stopped;
    state
        .log_manager
        .lock()
        .await
        .push("info", "proxy-local", "已停止");
    log::info!("proxy-local stopped");
    Ok(())
}

/// 检测 TUN 是否已在运行（三重检测，对标 dashboard 的 isTunRunning）
pub fn is_tun_running() -> bool {
    // 1. 检查 utun9 网卡接口（macOS）
    #[cfg(target_os = "macos")]
    {
        let output = Command::new("ifconfig").arg("utun9").output();
        if let Ok(out) = output {
            if out.status.success() {
                let stdout = String::from_utf8_lossy(&out.stdout);
                if stdout.contains("198.18.0.1") {
                    return true;
                }
            }
        }
    }

    // 2. 通过 pgrep 检查进程
    #[cfg(not(target_os = "windows"))]
    {
        let output = Command::new("pgrep").arg("-f").arg("tun-adapter").output();
        if let Ok(out) = output {
            if out.status.success() && !out.stdout.is_empty() {
                return true;
            }
        }
    }

    #[cfg(target_os = "windows")]
    {
        let output = Command::new("tasklist")
            .args(["/FI", "IMAGENAME eq tun-adapter.exe"])
            .output();
        if let Ok(out) = output {
            let stdout = String::from_utf8_lossy(&out.stdout);
            if stdout.contains("tun-adapter.exe") {
                return true;
            }
        }
    }

    false
}

/// 启动 tun-adapter（TUN 透明代理）
pub async fn start_tun_adapter(state: &mut AppState) -> Result<(), String> {
    if state.tun_status == ServiceStatus::Running {
        return Ok(());
    }

    // 外部 TUN 复用检测
    if is_tun_running() {
        log::info!("tun-adapter already running externally, reusing");
        state.tun_status = ServiceStatus::Running;
        state.tun_process = None;
        state
            .log_manager
            .lock()
            .await
            .push("info", "tun-adapter", "检测到已在运行的 TUN 进程，复用中");
        return Ok(());
    }

    state.tun_status = ServiceStatus::Starting;

    let tun_path = get_tun_path();
    if !tun_path.exists() {
        state.tun_status = ServiceStatus::Error;
        return Err(format!("tun-adapter not found at: {:?}", tun_path));
    }

    // 确保 tun.toml 配置文件存在（首次运行自动生成默认配置）
    let tun_config_path = config::get_config_dir().join("tun.toml");
    if !tun_config_path.exists() {
        log::info!("tun.toml not found, generating default config");
        if let Err(e) = config::load_tun_config() {
            log::warn!("Failed to generate default tun.toml: {}", e);
        }
    }

    log::info!("Starting tun-adapter: {:?}", tun_path);
    state
        .log_manager
        .lock()
        .await
        .push("info", "tun-adapter", "正在启动 TUN adapter...");

    // TUN 需要提权运行（macOS/Linux 需要 sudo）
    let child_result = start_tun_with_privilege(&tun_path, &tun_config_path);

    match child_result {
        Ok(mut child) => {
            // 启动日志捕获
            if let Some(stdout) = child.stdout.take() {
                logs::spawn_log_reader(
                    stdout,
                    "tun-adapter".to_string(),
                    state.log_manager.clone(),
                );
            }
            if let Some(stderr) = child.stderr.take() {
                logs::spawn_log_reader(
                    stderr,
                    "tun-adapter".to_string(),
                    state.log_manager.clone(),
                );
            }

            state.tun_process = Some(child);

            // 等待 TUN 设备就绪（检测网卡接口而非简单 sleep）
            for _ in 0..10 {
                tokio::time::sleep(Duration::from_millis(500)).await;

                // 检查进程是否已退出（权限错误会立即退出）
                if let Some(ref mut p) = state.tun_process {
                    if let Ok(Some(exit)) = p.try_wait() {
                        state.tun_status = ServiceStatus::Error;
                        let code = exit.code().unwrap_or(-1);
                        // 尝试从日志中获取实际错误信息
                        let recent_logs = state.log_manager.lock().await
                            .get_recent("tun-adapter", 5);
                        let stderr_content = recent_logs.join("\n");
                        // 用实际错误内容分类，如果日志为空则用退出码
                        let error_input = if stderr_content.contains("not permitted")
                            || stderr_content.contains("password")
                            || stderr_content.contains("Operation not permitted")
                        {
                            stderr_content.clone()
                        } else {
                            format!("进程退出，退出码 {}。{}", code, stderr_content)
                        };
                        return Err(classify_tun_error(&error_input));
                    }
                }

                // 检测 TUN 接口是否已创建
                if is_tun_running() {
                    state.tun_status = ServiceStatus::Running;
                    log::info!("tun-adapter is running");
                    state
                        .log_manager
                        .lock()
                        .await
                        .push("success", "tun-adapter", "TUN 网卡已创建，运行中");
                    return Ok(());
                }
            }

            // 5 秒后仍然检测不到接口，假定成功（某些系统检测不到）
            state.tun_status = ServiceStatus::Running;
            log::info!("tun-adapter assumed running (interface check inconclusive)");
            Ok(())
        }
        Err(e) => {
            state.tun_status = ServiceStatus::Error;
            let classified = classify_tun_error(&e);
            state
                .log_manager
                .lock()
                .await
                .push("error", "tun-adapter", &classified);
            Err(classified)
        }
    }
}

/// 平台特定的提权启动 TUN
#[cfg(target_os = "macos")]
fn start_tun_with_privilege(
    tun_path: &std::path::Path,
    config_path: &std::path::Path,
) -> Result<Child, String> {
    // macOS: 使用 sudo 运行（需要用户一次性授权）
    Command::new("sudo")
        .arg("-n") // 非交互式
        .arg(tun_path)
        .arg("--config")
        .arg(config_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("sudo spawn failed: {}", e))
}

#[cfg(target_os = "windows")]
fn start_tun_with_privilege(
    tun_path: &std::path::Path,
    config_path: &std::path::Path,
) -> Result<Child, String> {
    // Windows 策略：
    // 1. 先尝试直接启动（主程序已经是管理员时可行）
    // 2. 如果失败且是权限错误(740)，尝试通过计划任务启动
    // 3. 都不行则返回提示让用户手动配置

    match Command::new(tun_path)
        .arg("--config")
        .arg(config_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
    {
        Ok(child) => Ok(child),
        Err(e) => {
            let is_elevation_error = e.raw_os_error() == Some(740); // ERROR_ELEVATION_REQUIRED
            if is_elevation_error {
                // 尝试通过预配置的计划任务启动
                log::info!("Direct spawn requires elevation, trying scheduled task...");
                let task_result = Command::new("schtasks")
                    .args(["/Run", "/TN", "SimplePlane-TUN"])
                    .output();

                match task_result {
                    Ok(output) if output.status.success() => {
                        // 计划任务触发成功，但我们无法获取子进程句柄
                        // 返回一个 dummy 进程用于状态追踪（通过 is_tun_running 检测）
                        // 这里返回错误让上层通过轮询 is_tun_running() 来确认
                        log::info!("Scheduled task triggered successfully");
                        // 启动一个 ping 占位进程（马上会被检测逻辑跳过）
                        Command::new("cmd")
                            .args(["/c", "timeout /t 30 /nobreak >nul"])
                            .stdout(Stdio::piped())
                            .stderr(Stdio::piped())
                            .spawn()
                            .map_err(|e2| format!("placeholder process failed: {}", e2))
                    }
                    _ => {
                        // 计划任务未配置或执行失败
                        Err("elevation required".to_string())
                    }
                }
            } else {
                Err(format!("tun-adapter spawn failed: {}", e))
            }
        }
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn start_tun_with_privilege(
    tun_path: &std::path::Path,
    config_path: &std::path::Path,
) -> Result<Child, String> {
    Command::new("sudo")
        .arg(tun_path)
        .arg("--config")
        .arg(config_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("spawn failed: {}", e))
}

/// 停止 tun-adapter
/// 对标 dashboard 的 stopTunAdapter：杀进程 → 等待 Drop handler → DNS 兜底恢复
pub async fn stop_tun_adapter(state: &mut AppState) -> Result<(), String> {
    state.tun_status = ServiceStatus::Stopping;

    state
        .log_manager
        .lock()
        .await
        .push("info", "tun-adapter", "正在停止...");

    if let Some(ref mut child) = state.tun_process {
        let _ = child.kill();
        // 等待最多 5 秒让 Rust Drop handler 恢复 DNS/路由（对标 dashboard 的等待逻辑）
        for _ in 0..10 {
            tokio::time::sleep(Duration::from_millis(500)).await;
            if let Ok(Some(_)) = child.try_wait() {
                break;
            }
        }
        // 强制终止
        let _ = child.kill();
        let _ = child.wait();
    }

    // macOS 上通过 sudo pkill 兜底（对标 dashboard 的三重 kill）
    #[cfg(target_os = "macos")]
    {
        let _ = Command::new("sudo")
            .arg("-n")
            .arg("pkill")
            .arg("-f")
            .arg("tun-adapter")
            .output();
    }

    state.tun_process = None;
    state.tun_status = ServiceStatus::Stopped;

    // 等待 2 秒后执行 DNS 兜底恢复（给 tun-adapter 的 Drop handler 时间完成）
    tokio::time::sleep(Duration::from_secs(2)).await;
    if let Some(msg) = dns::restore_dns_if_needed() {
        state
            .log_manager
            .lock()
            .await
            .push("warning", "tun-adapter", &msg);
    }

    state
        .log_manager
        .lock()
        .await
        .push("info", "tun-adapter", "已停止");
    log::info!("tun-adapter stopped");
    Ok(())
}

/// 分类 TUN 启动错误（对标 server.js 的 classifySudoFailure，增强版）
fn classify_tun_error(error: &str) -> String {
    let err_lower = error.to_lowercase();

    if err_lower.contains("no such file") || err_lower.contains("not found") {
        return "tun-adapter 二进制文件缺失，请重新安装软件。".to_string();
    }

    if err_lower.contains("address already in use") || err_lower.contains("already exists") {
        return "TUN 设备或地址冲突：可能有另一个 VPN 或 TUN 实例在运行。\n\
            请先关闭其他 VPN 软件再重试。".to_string();
    }

    // 权限相关错误：根据平台给出不同提示
    let is_permission_error = err_lower.contains("password is required")
        || err_lower.contains("sudo: a password is required")
        || err_lower.contains("sudo: a terminal is required")
        || err_lower.contains("operation not permitted")
        || err_lower.contains("permission denied")
        || err_lower.contains("elevation required")
        || err_lower.contains("requires elevation")
        || err_lower.contains("退出码 1")
        || err_lower.contains("exit code 1")
        || err_lower.contains("os error 740");

    if is_permission_error {
        return get_permission_error_message();
    }

    format!("TUN 启动失败：{}", error)
}

/// macOS/Linux: 返回 sudoers 配置提示
#[cfg(not(target_os = "windows"))]
fn get_permission_error_message() -> String {
    let tun_path = get_tun_path();
    let tun_path_str = tun_path.to_string_lossy();
    format!(
        "TUN 模式需要免密 sudo 权限，当前未配置。\n\n\
        请在终端执行以下命令配置免密（仅需一次）：\n\n\
          sudo visudo -f /etc/sudoers.d/simpleplane\n\n\
        在打开的编辑器中添加一行：\n\n\
          {} ALL=(ALL) NOPASSWD: {}\n\n\
        保存退出后重试即可。",
        std::env::var("USER").unwrap_or_else(|_| "你的用户名".to_string()),
        tun_path_str
    )
}

/// Windows: 返回管理员权限运行提示
#[cfg(target_os = "windows")]
fn get_permission_error_message() -> String {
    let tun_path = get_tun_path();
    let tun_path_str = tun_path.to_string_lossy();
    let config_dir = crate::config::get_config_dir();
    let config_path_str = config_dir.join("tun.toml").to_string_lossy().to_string();
    format!(
        "TUN 模式需要管理员权限，当前权限不足。\n\n\
        方法一：右键点击 SimplePlane 应用，选择「以管理员身份运行」\n\n\
        方法二：在管理员 PowerShell 中执行以下命令（一次性配置计划任务，之后自动提权）：\n\n\
          schtasks /Create /TN \"SimplePlane-TUN\" /TR \"'{}' --config '{}'\" /SC ONDEMAND /RL HIGHEST /F\n\n\
        配置后程序会自动通过计划任务启动 TUN，无需每次手动提权。",
        tun_path_str,
        config_path_str
    )
}
