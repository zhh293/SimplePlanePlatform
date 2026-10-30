use std::net::TcpStream;
use std::process::{Command, Stdio};
use std::time::Duration;

use crate::config;
use crate::dns;
use crate::logs;
use crate::state::{AppState, ServiceStatus};

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

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
        log::info!("Port {} already in use, reusing external process", port);
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
    match config::write_java_config() {
        Ok(java_config_path) => {
            let _ =
                std::fs::remove_file(config::get_config_dir().join("proxy-java.yml.status.json"));
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
                    state.log_manager.lock().await.push(
                        "success",
                        "proxy-local",
                        &format!("已启动，监听端口 {}", port),
                    );
                    return Ok(());
                }
                // 检查进程是否已退出
                if let Some(ref mut p) = state.proxy_process {
                    if let Ok(Some(exit)) = p.try_wait() {
                        state.proxy_status = ServiceStatus::Error;
                        // 从日志管理器取最近的错误信息
                        let recent_logs =
                            state.log_manager.lock().await.get_recent("proxy-local", 5);
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
        state.log_manager.lock().await.push(
            "info",
            "proxy-local",
            "外部进程，已断开连接但未停止进程",
        );
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
    #[cfg(windows)]
    let output = Command::new("tasklist")
        .args(["/FI", "IMAGENAME eq tun-adapter.exe", "/FO", "CSV", "/NH"])
        .creation_flags(0x08000000)
        .output();
    #[cfg(not(windows))]
    let output = Command::new("pgrep").args(["-x", "tun-adapter"]).output();
    output.is_ok_and(|out| {
        #[cfg(windows)]
        {
            out.status.success()
                && String::from_utf8_lossy(&out.stdout)
                    .to_lowercase()
                    .contains("\"tun-adapter.exe\"")
        }
        #[cfg(not(windows))]
        {
            out.status.success() && !out.stdout.is_empty()
        }
    })
}

/// Refresh from the owned OS process, never a placeholder or an interface alone.
pub async fn refresh_tun_status(state: &mut AppState) {
    if state.tun_status != ServiceStatus::Running {
        return;
    }
    let observed = state.tun_process.as_mut().map(|p| p.try_exit());
    let message = match observed {
        Some(Ok(None)) => return,
        Some(Ok(Some(code))) => {
            state.tun_process = None;
            if let Some(run) = state.tun_run.take() {
                run.cleanup();
            }
            format!("TUN 进程已退出，退出码 {}；请检查 tun-adapter.log", code)
        }
        Some(Err(error)) => format!("无法读取 TUN 进程状态: {}", error),
        None => "TUN 缺少受管理的进程，连接状态已失效".into(),
    };
    state.tun_status = ServiceStatus::Error;
    state
        .log_manager
        .lock()
        .await
        .push("error", "tun-adapter", &message);
}

pub async fn start_tun_adapter(state: &mut AppState) -> Result<(), String> {
    refresh_tun_status(state).await;
    if state.tun_status == ServiceStatus::Running {
        return Ok(());
    }
    if state.tun_process.is_some() {
        stop_tun_adapter(state).await?;
    }
    state.tun_status = ServiceStatus::Starting;
    let result = start_managed_tun(state).await;
    if let Err(error) = result {
        let cleanup = if state.tun_process.is_some() {
            stop_tun_adapter(state).await
        } else {
            Ok(())
        };
        if state.tun_process.is_none() {
            if let Some(run) = state.tun_run.take() {
                run.cleanup();
            }
        }
        state.tun_status = ServiceStatus::Error;
        let message = match cleanup {
            Ok(()) => classify_tun_error(&error),
            Err(stop) => format!("{}；清理失败: {}", error, stop),
        };
        state
            .log_manager
            .lock()
            .await
            .push("error", "tun-adapter", &message);
        return Err(message);
    }
    Ok(())
}

async fn start_managed_tun(state: &mut AppState) -> Result<(), String> {
    if is_tun_running() {
        return Err("检测到外部 TUN 进程，请先通过启动它的程序停止，再连接。".into());
    }
    let path = get_tun_path();
    if !path.is_file() {
        return Err(format!("tun-adapter not found at {:?}", path));
    }
    let config_path = config::write_tun_node_config()?;
    let run =
        crate::tun_process::TunRun::create(&config::get_config_dir()).map_err(|e| e.to_string())?;
    let arguments = run.arguments(&config_path);
    state.log_manager.lock().await.push(
        "info",
        "tun-adapter",
        &format!("正在启动 TUN；诊断日志: {}", run.log.display()),
    );
    state.tun_run = Some(run);
    let mut process = start_tun_with_privilege(&path, &arguments)?;
    if let Some(stdout) = process.take_stdout() {
        logs::spawn_log_reader(stdout, "tun-adapter".into(), state.log_manager.clone());
    }
    if let Some(stderr) = process.take_stderr() {
        logs::spawn_log_reader(stderr, "tun-adapter".into(), state.log_manager.clone());
    }
    state.tun_process = Some(process);
    wait_for_tun_ready(
        state.tun_process.as_mut().unwrap(),
        state.tun_run.as_ref().unwrap(),
        Duration::from_secs(30),
    )
    .await?;
    state.tun_status = ServiceStatus::Running;
    state
        .log_manager
        .lock()
        .await
        .push("success", "tun-adapter", "TUN 路由与协议栈已就绪");
    Ok(())
}

async fn wait_for_tun_ready(
    process: &mut crate::tun_process::TunProcess,
    run: &crate::tun_process::TunRun,
    timeout: Duration,
) -> Result<(), String> {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        if let Some(code) = process.try_exit().map_err(|e| e.to_string())? {
            return Err(format!(
                "TUN 启动期间退出，退出码 {}；请检查 tun-adapter.log",
                code
            ));
        }
        #[cfg(windows)]
        let expected_pid = Some(process.id());
        #[cfg(not(windows))]
        let expected_pid = None; // sudo may wrap the actual adapter.
        if run.is_ready(expected_pid) {
            return Ok(());
        }
        if tokio::time::Instant::now() >= deadline {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    Err(format!(
        "TUN 启动超时：{} 秒内未收到就绪信号；请检查 tun-adapter.log",
        timeout.as_secs()
    ))
}

#[cfg(not(windows))]
fn start_tun_with_privilege(
    path: &std::path::Path,
    arguments: &[std::ffi::OsString],
) -> Result<crate::tun_process::TunProcess, String> {
    Command::new("sudo")
        .arg("-n")
        .arg(path)
        .args(arguments)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map(crate::tun_process::TunProcess::Child)
        .map_err(|e| format!("sudo spawn failed: {}", e))
}

#[cfg(windows)]
fn start_tun_with_privilege(
    path: &std::path::Path,
    arguments: &[std::ffi::OsString],
) -> Result<crate::tun_process::TunProcess, String> {
    let elevated = Command::new("fltmc")
        .creation_flags(0x08000000)
        .output()
        .is_ok_and(|out| out.status.success());
    if !elevated {
        return start_tun_with_native_uac(path, arguments);
    }
    match Command::new(path)
        .args(arguments)
        .creation_flags(0x08000000)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
    {
        Ok(child) => Ok(crate::tun_process::TunProcess::Child(child)),
        Err(error) if error.raw_os_error() == Some(740) => {
            start_tun_with_native_uac(path, arguments)
        }
        Err(error) => Err(format!("tun-adapter spawn failed: {}", error)),
    }
}

#[cfg(windows)]
fn start_tun_with_native_uac(
    path: &std::path::Path,
    arguments: &[std::ffi::OsString],
) -> Result<crate::tun_process::TunProcess, String> {
    use std::os::windows::{
        ffi::OsStrExt,
        io::{FromRawHandle, OwnedHandle},
    };
    use windows_sys::Win32::UI::Shell::{
        ShellExecuteExW, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW,
    };
    let verb: Vec<u16> = std::ffi::OsStr::new("runas")
        .encode_wide()
        .chain(Some(0))
        .collect();
    let file: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let args = arguments
        .iter()
        .map(|a| crate::tun_process::quote_argument(a))
        .collect::<Vec<_>>()
        .join(" ");
    let parameters: Vec<u16> = std::ffi::OsStr::new(&args)
        .encode_wide()
        .chain(Some(0))
        .collect();
    let directory: Vec<u16> = path
        .parent()
        .unwrap_or(std::path::Path::new("."))
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect();
    let mut info: SHELLEXECUTEINFOW = unsafe { std::mem::zeroed() };
    info.cbSize = std::mem::size_of::<SHELLEXECUTEINFOW>() as u32;
    info.fMask = SEE_MASK_NOCLOSEPROCESS;
    info.lpVerb = verb.as_ptr();
    info.lpFile = file.as_ptr();
    info.lpParameters = parameters.as_ptr();
    info.lpDirectory = directory.as_ptr();
    info.nShow = 0;
    if unsafe { ShellExecuteExW(&mut info) } == 0 {
        return Err(format!(
            "Windows UAC elevation failed: {}",
            std::io::Error::last_os_error()
        ));
    }
    if info.hProcess.is_null() {
        return Err("Windows UAC did not return the TUN process handle".into());
    }
    Ok(crate::tun_process::TunProcess::Elevated(unsafe {
        OwnedHandle::from_raw_handle(info.hProcess)
    }))
}

async fn wait_for_tun_exit(
    process: &mut crate::tun_process::TunProcess,
    attempts: usize,
) -> Result<bool, String> {
    for _ in 0..attempts {
        if process.try_exit().map_err(|e| e.to_string())?.is_some() {
            return Ok(true);
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
    Ok(process.try_exit().map_err(|e| e.to_string())?.is_some())
}

pub async fn stop_tun_adapter(state: &mut AppState) -> Result<(), String> {
    state.tun_status = ServiceStatus::Stopping;
    let result = stop_managed_tun(state).await;
    if let Err(error) = result {
        state.tun_status = ServiceStatus::Error;
        state
            .log_manager
            .lock()
            .await
            .push("error", "tun-adapter", &error);
        return Err(error);
    }
    state.tun_process = None;
    if let Some(run) = state.tun_run.take() {
        run.cleanup();
    }
    state.tun_status = ServiceStatus::Stopped;
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
        .push("info", "tun-adapter", "TUN 已停止");
    Ok(())
}

async fn stop_managed_tun(state: &mut AppState) -> Result<(), String> {
    if let Some(process) = &mut state.tun_process {
        if process.try_exit().map_err(|e| e.to_string())?.is_some() {
            return Ok(());
        }
        if let Some(run) = &state.tun_run {
            run.request_stop()
                .map_err(|e| format!("无法请求 TUN 退出: {}", e))?;
            if wait_for_tun_exit(process, 120).await? {
                return Ok(());
            }
        }
        process
            .terminate()
            .map_err(|e| format!("无法终止所管理的 TUN 进程 {}: {}", process.id(), e))?;
        if !wait_for_tun_exit(process, 20).await? {
            return Err("TUN 停止超时，进程仍在运行".into());
        }
    }
    Ok(())
}

fn classify_tun_error(error: &str) -> String {
    let err_lower = error.to_lowercase();

    if err_lower.contains("no such file") || err_lower.contains("not found") {
        return "tun-adapter 二进制文件缺失，请重新安装软件。".to_string();
    }

    if err_lower.contains("address already in use") || err_lower.contains("already exists") {
        return "TUN 设备或地址冲突：可能有另一个 VPN 或 TUN 实例在运行。\n\
            请先关闭其他 VPN 软件再重试。"
            .to_string();
    }

    // 权限相关错误：根据平台给出不同提示
    let is_permission_error = err_lower.contains("password is required")
        || err_lower.contains("sudo: a password is required")
        || err_lower.contains("sudo: a terminal is required")
        || err_lower.contains("operation not permitted")
        || err_lower.contains("permission denied")
        || err_lower.contains("elevation required")
        || err_lower.contains("requires elevation")
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
    "TUN 模式需要管理员权限，请允许本次 Windows UAC 请求，或以管理员身份运行 SimplePlane。".into()
}

#[cfg(all(test, windows))]
mod tun_lifecycle_tests {
    use super::*;
    use crate::tun_process::{TunProcess, TunRun};

    fn sleeper() -> TunProcess {
        TunProcess::Child(
            Command::new("powershell.exe")
                .args([
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Start-Sleep -Seconds 10",
                ])
                .creation_flags(0x08000000)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap(),
        )
    }

    #[tokio::test]
    async fn missing_readiness_times_out_even_when_process_alive() {
        let dir = tempfile::tempdir().unwrap();
        let run = TunRun::create(dir.path()).unwrap();
        let mut process = sleeper();
        let result = wait_for_tun_ready(&mut process, &run, Duration::from_millis(80)).await;
        process.terminate().unwrap();
        assert!(wait_for_tun_exit(&mut process, 20).await.unwrap());
        run.cleanup();
        assert!(result.unwrap_err().contains("启动超时"));
    }

    #[tokio::test]
    async fn exited_process_cannot_be_ready_and_running_becomes_error() {
        let dir = tempfile::tempdir().unwrap();
        let run = TunRun::create(dir.path()).unwrap();
        let child = Command::new("cmd.exe")
            .args(["/D", "/C", "exit 7"])
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        let mut process = TunProcess::Child(child);
        std::fs::write(&run.ready, process.id().to_string()).unwrap();
        assert!(wait_for_tun_exit(&mut process, 20).await.unwrap());
        assert!(
            wait_for_tun_ready(&mut process, &run, Duration::from_millis(10))
                .await
                .unwrap_err()
                .contains("退出码 7")
        );
        let mut state = AppState::default();
        state.tun_process = Some(process);
        state.tun_run = Some(run);
        state.tun_status = ServiceStatus::Running;
        refresh_tun_status(&mut state).await;
        assert_eq!(state.tun_status, ServiceStatus::Error);
        assert!(state.tun_process.is_none());
        assert!(
            state.log_manager.lock().await.get_recent("tun-adapter", 1)[0].contains("退出码 7")
        );
    }

    #[tokio::test]
    async fn stopping_owned_process_leaves_unrelated_process_alive() {
        let mut other = sleeper();
        let mut state = AppState::default();
        state.tun_process = Some(sleeper());
        // No control file: exercise exact owned-handle fallback, without DNS changes.
        let result = stop_managed_tun(&mut state).await;
        let other_alive = other.try_exit().unwrap().is_none();
        other.terminate().unwrap();
        assert!(wait_for_tun_exit(&mut other, 20).await.unwrap());
        result.unwrap();
        assert!(state
            .tun_process
            .as_mut()
            .unwrap()
            .try_exit()
            .unwrap()
            .is_some());
        assert!(other_alive);
    }
}
