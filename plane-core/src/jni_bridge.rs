//! JNI 桥接最小闭环（Task A2）：Kotlin ↔ Rust 双向调用 + protect 回调。
//!
//! 本模块实现任务文档 A2 的契约：
//!
//! ```text
//! Kotlin → Rust:
//!   nativeStart(tunFd: Int, configJson: String): Long   // 返回 handle（0=失败）
//!   nativeStop(handle: Long)
//!   nativeStats(handle: Long): String                    // 返回当前状态 JSON
//! Rust → Kotlin（回调）:
//!   protect(fd: Int): Boolean
//!   onStatus(state: String)
//! ```
//!
//! ## 设计要点（对齐任务文档 0.3 / A2）
//!
//! - **panic 隔离**：每个 `extern "C"` 函数体用 [`std::panic::catch_unwind`] 包裹，
//!   panic 时记日志并返回 0/false/空串，绝不让 panic 跨越 JNI 边界（铁律 0.3-4）。
//! - **handle 管理**：`nativeStart` 返回 `Box::into_raw` 的 [`CoreHandle`] 指针（转 i64），
//!   `nativeStop` 用 `Box::from_raw` 回收。不使用全局可变单例，以支持多次 start/stop
//!   （切换节点场景）。
//! - **回调机制**：保存 [`jni::JavaVM`] 与 `NativeBridge` 实例的 [`GlobalRef`]，
//!   任意线程回调时 `attach_current_thread` 拿到 `JNIEnv` 再 `call_method`。
//!
//! 当前实现已把 TUN、用户态 TCP/FakeDNS 栈、JNI protect 回调和加密 HTTP/3 出站
//! 连接串成完整数据面；启动时会先验证节点配置，真实出站 socket 建立前再执行 protect。

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicU8, Ordering};

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use serde::Deserialize;

use crate::android_tun::AndroidTun;
use crate::dispatcher::{run_dispatcher, DispatcherConfig, StatusReporter};
use crate::error::{CoreError, Result};
use crate::net_probe::FakeDnsEngine;
use crate::outbound::SocketProtector;
use crate::tcp_stack::{stack_loop, TcpEvent};

/// FakeIP 地址池 CIDR（与 net_probe / 桌面一致）。
const FAKE_IP_CIDR: &str = "198.18.0.0/15";
/// FakeDNS LRU 容量（与桌面默认对齐）。
const FAKE_DNS_CAPACITY: usize = 4096;
/// TcpEvent 通道缓冲（突发新连接的背压上限）。
const TCP_EVENT_CHANNEL_CAP: usize = 256;
/// notify 通道缓冲（栈与流之间的就绪通知）。
const NOTIFY_CHANNEL_CAP: usize = 1024;

/// 节点与运行时配置（由 Kotlin 侧 `configJson` 下发）。
///
/// A2 阶段仅解析最小字段，证明 JSON 通路可用；A5/B6 会扩充节点地址、密钥、cipher、
/// 路由规则等。所有字段都给出默认值，保证旧/简化配置仍能解析（向前兼容）。
#[derive(Debug, Clone, Deserialize)]
pub struct AndroidConfig {
    /// TUN 的 MTU，默认 1500（对齐桌面与 A3 Builder 配置）。
    #[serde(default = "default_mtu")]
    pub mtu: usize,

    /// remote 节点地址；nativeStart 要求与端口、密钥同时存在。
    #[serde(default)]
    pub remote_host: String,

    /// remote 节点端口；nativeStart 要求与地址、密钥同时存在。
    #[serde(default)]
    pub remote_port: u16,

    /// Optional HTTP/3 TLS SNI name; defaults to the remote host.
    #[serde(default)]
    pub server_name: String,

    /// Optional PEM CA for the HTTP/3 server certificate.
    #[serde(default)]
    pub ca_pem: String,

    /// A6：与 proxy-remote 共享的密钥（构造 ChaCha20-Poly1305 Cipher）。
    /// 非 32 字节会按 Java 规则 SHA-256 派生。缺省为空串时 nativeStart 会拒绝启动。
    #[serde(default)]
    pub remote_key: String,

    /// A6：cipher 名称，默认 "chacha20"（当前仅支持 chacha20，与 ChaCha20Cipher 兼容）。
    #[serde(default = "default_cipher")]
    pub cipher: String,

    /// 兼容旧配置字段；HTTP/3 出站始终启用 TLS。
    #[serde(default)]
    pub tls: bool,
}

fn default_mtu() -> usize {
    1500
}

fn default_cipher() -> String {
    "chacha20".to_string()
}

impl AndroidConfig {
    /// 从 `configJson` 字符串解析配置。
    ///
    /// 空串视为「全部使用默认值」，便于 A2 阶段用最简配置完成闭环验证。
    pub fn from_json(json: &str) -> Result<Self> {
        let trimmed = json.trim();
        if trimmed.is_empty() {
            return Ok(Self {
                mtu: default_mtu(),
                remote_host: String::new(),
                remote_port: 0,
                server_name: String::new(),
                ca_pem: String::new(),
                remote_key: String::new(),
                cipher: default_cipher(),
                tls: false,
            });
        }
        Ok(serde_json::from_str(trimmed)?)
    }

    /// 是否具备真正出站所需的最小配置（节点地址 + 端口 + 密钥齐全）。
    ///
    /// 节点配置不齐全时 [`native_start_impl`] 会直接拒绝，避免建立一个必然黑洞的 VPN。
    pub fn outbound_ready(&self) -> bool {
        !self.remote_host.is_empty() && self.remote_port != 0 && !self.remote_key.is_empty()
    }
}

/// Rust → Kotlin 回调上下文：持有跨线程回调所需的 `JavaVM` 与 bridge 全局引用。
///
/// 单独抽出并以 [`std::sync::Arc`] 共享，使其可被 clone 进任意 tokio worker 线程
/// 的 spawn 闭包中执行回调。`JavaVM` 与 [`GlobalRef`] 均为 `Send + Sync`，跨线程安全。
pub struct CallbackCtx {
    /// 进程级 `JavaVM`，用于在任意 Rust 线程 attach 后回调 Kotlin。
    vm: JavaVM,
    /// `NativeBridge` 实例的全局引用，回调 `protect` / `onStatus` 的接收者。
    bridge: GlobalRef,
}

impl CallbackCtx {
    /// 通过 `JavaVM` attach 当前线程，回调 Kotlin 侧 `NativeBridge.protect(fd)`。
    ///
    /// 这是「Rust → Kotlin」回调的核心：任意 tokio worker 线程都可调用本方法
    /// 把出站 socket fd 交给 `VpnService.protect` 排除出 TUN（防回环，铁律 0.3-1）。
    ///
    /// 返回 `Ok(true)` 表示 Kotlin 侧 protect 成功。
    pub fn protect(&self, fd: i32) -> Result<bool> {
        let mut env = self.vm.attach_current_thread()?;
        let ret = env.call_method(self.bridge.as_obj(), "protect", "(I)Z", &[JValue::Int(fd)])?;
        Ok(ret.z()?)
    }

    /// 回调 Kotlin 侧 `NativeBridge.onStatus(state)` 上报状态（best-effort）。
    pub fn on_status(&self, state: &str) -> Result<()> {
        let mut env = self.vm.attach_current_thread()?;
        let jstr = env.new_string(state)?;
        env.call_method(
            self.bridge.as_obj(),
            "onStatus",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&jstr)],
        )?;
        Ok(())
    }
}

/// JNI-backed status reporter. It also gives nativeStats a race-free state value.
struct JniStatus {
    cb: std::sync::Arc<CallbackCtx>,
    state: std::sync::Arc<AtomicU8>,
}

impl StatusReporter for JniStatus {
    fn report(&self, state: &str) {
        self.state.store(status_code(state), Ordering::Release);
        if let Err(error) = self.cb.on_status(state) {
            tracing::debug!(%error, "status callback failed");
        }
    }
}

fn status_code(state: &str) -> u8 {
    match state {
        "starting" => 1,
        "connecting" => 2,
        "connected" => 3,
        "node_down" => 4,
        "error" => 5,
        _ => 0,
    }
}

fn status_name(code: u8) -> &'static str {
    match code {
        1 => "starting",
        2 => "connecting",
        3 => "connected",
        4 => "node_down",
        5 => "error",
        _ => "stopped",
    }
}

/// 把 [`CallbackCtx`] 适配为 [`SocketProtector`]：出站 socket 的 protect 经 JNI
/// 回调到 Kotlin 侧 `VpnService.protect(fd)`（protect 铁律 0.3-1）。
///
/// `dispatcher` 在 connect proxy-remote 后立即用它 protect socket fd。回调失败
/// （含 JNI 异常）一律视为 `false`，让上层放弃该连接而非回环。
struct JniProtector {
    cb: std::sync::Arc<CallbackCtx>,
}

impl SocketProtector for JniProtector {
    fn protect(&self, fd: i32) -> bool {
        match self.cb.protect(fd) {
            Ok(ok) => ok,
            Err(e) => {
                tracing::warn!(fd, error = %e, "JNI protect 回调失败，按未保护处理");
                false
            }
        }
    }
}

/// 一次 VPN 会话的运行时上下文。
///
/// 由 `nativeStart` 创建并 `Box::into_raw` 交给 Kotlin 持有（以 i64 handle 形式），
/// `nativeStop` 时 `Box::from_raw` 回收。Drop 时关闭 tokio 运行时并发出 shutdown 信号。
pub struct CoreHandle {
    /// 数据面 tokio 运行时，负责用户态 TCP 栈与加密 HTTP/3 出站调度。
    rt: tokio::runtime::Runtime,
    /// 关停信号发送端，`nativeStop`/Drop 时置 true 通知数据面任务退出。
    shutdown: tokio::sync::watch::Sender<bool>,
    /// 解析后的配置（A3+ 使用）。
    #[allow(dead_code)]
    config: AndroidConfig,
    /// Last native data-plane state, exposed through nativeStats.
    state: std::sync::Arc<AtomicU8>,
}

impl Drop for CoreHandle {
    fn drop(&mut self) {
        // 通知数据面任务退出；忽略接收端已全部关闭的错误。
        let _ = self.shutdown.send(true);
        tracing::debug!("CoreHandle dropped, shutdown signalled");
        // rt 在字段 drop 顺序中随后被回收（Runtime::drop 完成线程回收）。
    }
}

/// 把可能 panic 的闭包执行结果归一为 `jlong`：成功返回句柄值，失败/ panic 返回 0。
///
/// 任务文档 A2 测试要求：「闭包 panic 时返回 0，正常返回非 0」。
/// 这是 `nativeStart` 的 panic 防火墙（铁律 0.3-4）。
pub(crate) fn catch_unwind_to_jlong<F>(f: F) -> jlong
where
    F: FnOnce() -> Result<jlong>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => {
            tracing::error!(error = %e, "nativeStart 失败");
            0
        }
        Err(panic) => {
            tracing::error!(
                panic = %panic_payload_message(panic.as_ref()),
                "nativeStart 捕获到 panic，已隔离，返回 0"
            );
            0
        }
    }
}

/// 将跨 unwind 边界传回的 panic payload 转成可读日志。
///
/// Android 上默认 panic hook 通常不会出现在应用筛选的 logcat 中；如果这里只记录
/// “捕获到 panic”，就无法区分无效 fd、运行时初始化失败或 JNI 调用错误。
fn panic_payload_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        return (*message).to_string();
    }
    if let Some(message) = payload.downcast_ref::<String>() {
        return message.clone();
    }
    "非字符串 panic payload".to_string()
}

/// 把可能 panic 的闭包执行结果归一为 `()`：失败/ panic 仅记录日志。
///
/// 用于 `nativeStop` 等无返回值且必须不 panic 跨 FFI 的入口。
pub(crate) fn catch_unwind_to_unit<F>(f: F)
where
    F: FnOnce() -> Result<()>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(())) => {}
        Ok(Err(e)) => tracing::error!(error = %e, "JNI 调用失败"),
        Err(panic) => tracing::error!(
            panic = %panic_payload_message(panic.as_ref()),
            "JNI 调用捕获到 panic，已隔离"
        ),
    }
}

/// `nativeStart` 的纯逻辑实现（与 JNI 解耦，便于单测）。
///
/// 解析配置、保存 `JavaVM` 与 bridge 全局引用、构建运行时，组装成 [`CoreHandle`]
/// 并以裸指针（i64）返回。失败返回 [`CoreError`]，由外层 `catch_unwind_to_jlong`
/// 转成 0。
fn native_start_impl(
    env: &mut JNIEnv,
    this: &JObject,
    tun_fd: jint,
    config_json: &JString,
) -> Result<jlong> {
    if tun_fd < 0 {
        return Err(CoreError::InvalidArgument(format!(
            "tunFd 必须 >= 0，实际为 {tun_fd}"
        )));
    }

    let json: String = env.get_string(config_json)?.into();
    let config = AndroidConfig::from_json(&json)?;
    if !config.outbound_ready() {
        return Err(CoreError::InvalidArgument(
            "remote_host、remote_port、remote_key 必须完整配置".to_string(),
        ));
    }

    let vm = env.get_java_vm()?;
    let bridge = env.new_global_ref(this)?;

    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?;
    // shutdown 广播：stack_loop / dispatcher 各 subscribe 一份 receiver，
    // nativeStop/Drop 时 send(true) 让二者优雅退出。
    let (shutdown, _rx) = tokio::sync::watch::channel(false);

    let cb = std::sync::Arc::new(CallbackCtx { vm, bridge });
    let state = std::sync::Arc::new(AtomicU8::new(status_code("starting")));

    let handle = Box::new(CoreHandle {
        rt,
        shutdown,
        config: config.clone(),
        state: std::sync::Arc::clone(&state),
    });

    // A6 完整数据面：TUN → 用户态 TCP 栈 → 调度器 → 加密出站 → proxy-remote。
    //
    // AndroidTun::from_raw_fd() 注册 Tokio AsyncFd，需要当前线程已经处于 Tokio
    // runtime/reactor 上下文。nativeStart 是由 Android 主线程直接调用的，不能在
    // 这里裸调用 spawn_data_plane，否则会触发 “there is no reactor running”。
    // 用刚创建的 runtime 执行一次初始化 future，随后数据面任务继续由 runtime
    // 后台 worker 线程运行。
    handle
        .rt
        .block_on(async { spawn_data_plane(&handle, &cb, &state, tun_fd, &config) })?;
    let _ = cb.on_status("starting");

    Ok(Box::into_raw(handle) as jlong)
}

/// 在句柄的 tokio 运行时上拉起完整数据面：用户态 TCP 栈 + 连接调度器。
///
/// - `AndroidTun::from_raw_fd(tun_fd)` 接管 TUN fd（所有权移交，stop/Drop 时由其 close）。
/// - 一个 `Arc<Mutex<FakeDnsEngine>>` 同时供栈（写入/反查 FakeIP）与调度器（反查域名）使用。
/// - `tcp_event` 通道把栈上报的新连接交给调度器；`notify` 通道做栈/流之间的就绪通知。
/// - 栈与调度器各订阅一份 `shutdown` receiver，stop 时一起退出。
fn spawn_data_plane(
    handle: &CoreHandle,
    cb: &std::sync::Arc<CallbackCtx>,
    state: &std::sync::Arc<AtomicU8>,
    tun_fd: jint,
    config: &AndroidConfig,
) -> Result<()> {
    // SAFETY: tun_fd 由 Kotlin VpnService.establish().detachFd() 移交，独占且有效。
    let tun = unsafe { AndroidTun::from_raw_fd(tun_fd, config.mtu) }?;
    let (tun_reader, tun_writer) = tun.split();

    let fake_dns = std::sync::Arc::new(tokio::sync::Mutex::new(FakeDnsEngine::new(
        FAKE_IP_CIDR,
        FAKE_DNS_CAPACITY,
    )));

    let (tcp_event_tx, tcp_event_rx) =
        tokio::sync::mpsc::channel::<TcpEvent>(TCP_EVENT_CHANNEL_CAP);
    let (notify_tx, notify_rx) = tokio::sync::mpsc::channel::<()>(NOTIFY_CHANNEL_CAP);

    let dispatcher_cfg = DispatcherConfig {
        server_host: config.remote_host.clone(),
        server_port: config.remote_port,
        server_name: config.server_name.clone(),
        ca_pem: config.ca_pem.clone(),
        key: config.remote_key.clone().into_bytes(),
        tls: config.tls,
    };
    let protector = std::sync::Arc::new(JniProtector {
        cb: std::sync::Arc::clone(cb),
    });
    let status = std::sync::Arc::new(JniStatus {
        cb: std::sync::Arc::clone(cb),
        state: std::sync::Arc::clone(state),
    });

    // 栈循环：notify_tx 同时给栈与 SmolTcpStream 使用（调度器侧 clone）。
    let stack_fake_dns = std::sync::Arc::clone(&fake_dns);
    let stack_notify_tx = notify_tx.clone();
    let stack_shutdown_rx = handle.shutdown.subscribe();
    let stack_status = std::sync::Arc::clone(&status);
    handle.rt.spawn(async move {
        let res = stack_loop(
            tun_reader,
            tun_writer,
            stack_fake_dns,
            tcp_event_tx,
            stack_notify_tx,
            notify_rx,
            stack_shutdown_rx,
        )
        .await;
        match res {
            Ok(()) => tracing::info!("用户态 TCP 栈已退出"),
            Err(e) => {
                tracing::error!(error = %e, "用户态 TCP 栈异常退出");
                stack_status.report("error");
            }
        }
    });

    // 调度器循环：消费 TcpEvent，接出站。
    let disp_shutdown_rx = handle.shutdown.subscribe();
    let disp_status = std::sync::Arc::clone(&status);
    handle.rt.spawn(async move {
        let res = run_dispatcher(
            tcp_event_rx,
            fake_dns,
            dispatcher_cfg,
            protector,
            notify_tx,
            disp_shutdown_rx,
            std::sync::Arc::clone(&disp_status),
        )
        .await;
        match res {
            Ok(()) => tracing::info!("调度器已退出"),
            Err(e) => tracing::error!(error = %e, "调度器异常退出"),
        }
    });

    tracing::info!("A6 数据面已启动（栈 + 调度器）");
    Ok(())
}

/// Kotlin → Rust：启动数据面会话，返回 handle（0 表示失败）。
///
/// # Safety
///
/// 由 JNI 运行时调用，`env` / `this` / `config_json` 均为 JVM 保证有效的引用。
/// 函数内部用 `catch_unwind` 隔离 panic，保证不跨越 FFI 边界。
#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStart(
    mut env: JNIEnv,
    this: JObject,
    tun_fd: jint,
    config_json: JString,
) -> jlong {
    // 幂等初始化日志：即使 Kotlin 侧未先调用 nativeVersion，也保证从启动这一刻起
    // 所有 native 日志都能进入 logcat（tag=plane-core）。
    crate::logging::init();
    tracing::info!(tun_fd, "nativeStart 被调用，数据面准备启动");
    catch_unwind_to_jlong(|| native_start_impl(&mut env, &this, tun_fd, &config_json))
}

/// Kotlin → Rust：停止并回收一个 handle 对应的会话。
///
/// 传入 0 或已回收的 handle 是安全的（视为 no-op）。
///
/// # Safety
///
/// `handle` 必须是此前 `nativeStart` 返回且尚未被回收的有效值，或为 0。
/// 重复传入同一非 0 handle 属调用方错误（use-after-free），调用方（Kotlin）
/// 通过「回收后立即把 handle 置 0」来杜绝。
#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStop(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    catch_unwind_to_unit(|| {
        if handle == 0 {
            return Ok(());
        }
        // SAFETY: handle 来自 nativeStart 的 Box::into_raw，且调用方保证不重复回收。
        let core = unsafe { Box::from_raw(handle as *mut CoreHandle) };
        let _ = core.shutdown.send(true);
        // 显式 drop 以触发运行时回收与日志（亦可隐式 drop）。
        drop(core);
        tracing::info!("nativeStop 完成，handle 已回收");
        Ok(())
    });
}

/// Kotlin → Rust：返回当前会话状态 JSON。
///
/// # Safety
///
/// `handle` 必须是有效的 `nativeStart` 返回值或 0。本函数仅读取，不回收 handle。
#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStats(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
) -> jstring {
    let state = if handle == 0 {
        "stopped"
    } else {
        // SAFETY: handle is owned by Kotlin and remains valid during this read.
        let core = unsafe { &*(handle as *const CoreHandle) };
        status_name(core.state.load(Ordering::Acquire))
    };
    let json = format!(r#"{{"state":"{state}"}}"#);
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catch_unwind_returns_zero_on_panic() {
        let v = catch_unwind_to_jlong(|| panic!("boom"));
        assert_eq!(v, 0);
    }

    #[test]
    fn catch_unwind_returns_zero_on_err() {
        let v = catch_unwind_to_jlong(|| Err(CoreError::InvalidArgument("x".into())));
        assert_eq!(v, 0);
    }

    #[test]
    fn catch_unwind_returns_value_on_ok() {
        let v = catch_unwind_to_jlong(|| Ok(42));
        assert_eq!(v, 42);
    }

    #[test]
    fn catch_unwind_unit_swallows_panic() {
        // 不应 panic 逃逸；测试通过即代表被隔离。
        catch_unwind_to_unit(|| panic!("boom"));
        catch_unwind_to_unit(|| Err(CoreError::InvalidArgument("x".into())));
        catch_unwind_to_unit(|| Ok(()));
    }

    #[test]
    fn config_empty_uses_defaults() {
        let c = AndroidConfig::from_json("").expect("empty json should parse");
        assert_eq!(c.mtu, 1500);
        assert_eq!(c.remote_port, 0);
        assert!(c.remote_host.is_empty());
    }

    #[test]
    fn config_parses_fields() {
        let c =
            AndroidConfig::from_json(r#"{"mtu":1400,"remote_host":"1.2.3.4","remote_port":8443}"#)
                .expect("valid json should parse");
        assert_eq!(c.mtu, 1400);
        assert_eq!(c.remote_host, "1.2.3.4");
        assert_eq!(c.remote_port, 8443);
    }

    #[test]
    fn config_partial_json_fills_defaults() {
        let c = AndroidConfig::from_json(r#"{"remote_host":"example.com"}"#)
            .expect("partial json should parse");
        assert_eq!(c.mtu, 1500);
        assert_eq!(c.remote_host, "example.com");
    }

    #[test]
    fn config_invalid_json_errors() {
        let err = AndroidConfig::from_json("{not json}");
        assert!(err.is_err());
    }
}
