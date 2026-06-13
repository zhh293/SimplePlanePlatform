//! 日志初始化 —— 把 `tracing` 事件接到平台后端。
//!
//! 此前 crate 内大量使用 `tracing::info!/error!/...`，但**从未初始化任何
//! subscriber**，导致：
//!
//! - Android 上：所有 native 日志被丢弃，`logcat` 一条都看不到，
//!   线上「开了 VPN 但没网」无法定位（连 native 是否启动、connect 是否失败
//!   都无从得知）。
//! - 桌面（cargo test / 本机调试）上：同样静默。
//!
//! 本模块提供 [`init`]：进程内**幂等**（[`std::sync::Once`]）初始化一次：
//!
//! - `cfg(target_os = "android")`：经 `tracing-android` 写入 logcat，
//!   tag 为 `plane-core`，应用层用 `adb logcat -s plane-core` 即可单独过滤。
//! - 其它平台：经 `tracing-subscriber` 的 `fmt` 写 stdout，
//!   方便桌面单测与本机调试。
//!
//! 日志级别默认 `info`，可由环境变量 `RUST_LOG` 覆盖（如 `RUST_LOG=debug`）。
//! 多次调用安全（仅首次生效），故可在任意 JNI 入口放心调用。

use std::sync::Once;

/// logcat 的统一 tag，便于 `adb logcat -s plane-core` 单独过滤。
/// 仅 Android 后端使用；非 Android 平台允许其未被引用（避免 `-D warnings` 误报）。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const LOG_TAG: &str = "plane-core";

/// 默认日志级别（`RUST_LOG` 未设置时生效）。
const DEFAULT_LEVEL: &str = "info";

static INIT: Once = Once::new();

/// 初始化全局日志 subscriber（进程内仅首次生效，后续调用为 no-op）。
///
/// 应在每个会被外部最先触达的 JNI 入口处调用（如 `nativeVersion` / `nativeStart`），
/// 多次调用安全。失败时静默吞掉错误——日志初始化失败绝不能影响主链路，
/// 也不能 panic 跨越 FFI 边界。
pub fn init() {
    INIT.call_once(|| {
        // 解析级别：优先 RUST_LOG，缺省回退 DEFAULT_LEVEL。
        let filter = std::env::var("RUST_LOG").unwrap_or_else(|_| DEFAULT_LEVEL.to_string());
        init_backend(&filter);
    });
}

#[cfg(target_os = "android")]
fn init_backend(filter: &str) {
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;
    use tracing_subscriber::EnvFilter;

    // tracing-android：事件写入 Android logcat，priority 由 tracing level 映射。
    let android_layer = match tracing_android::layer(LOG_TAG) {
        Ok(layer) => layer,
        // 构建失败时直接放弃日志（不影响主链路）。
        Err(_) => return,
    };

    let env_filter = EnvFilter::try_new(filter).unwrap_or_else(|_| EnvFilter::new(DEFAULT_LEVEL));

    // try_init：若进程内已被其它路径初始化过，返回 Err，这里忽略即可。
    let _ = tracing_subscriber::registry()
        .with(env_filter)
        .with(android_layer)
        .try_init();
}

#[cfg(not(target_os = "android"))]
fn init_backend(filter: &str) {
    use tracing_subscriber::EnvFilter;

    let env_filter = EnvFilter::try_new(filter).unwrap_or_else(|_| EnvFilter::new(DEFAULT_LEVEL));

    // 桌面/CI：写 stdout，带 target 与级别，便于本机调试与单测观察。
    let _ = tracing_subscriber::fmt()
        .with_env_filter(env_filter)
        .with_target(true)
        .try_init();
}

#[cfg(test)]
mod tests {
    use super::*;

    /// init 必须幂等：多次调用不得 panic（底层 Once + try_init 保证）。
    #[test]
    fn init_is_idempotent() {
        init();
        init();
        init();
        // 能走到这里即说明多次初始化不会 panic。
        tracing::info!("logging init smoke test");
    }
}
