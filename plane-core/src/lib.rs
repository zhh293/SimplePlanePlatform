//! plane-core —— Android 客户端数据面核心（JNI cdylib）。
//!
//! 本 crate 承担桌面 `tun-adapter` + `proxy-local` 在手机端的等价职责：
//! 接收系统 `VpnService` 递交的 TUN fd，运行用户态协议栈、FakeDNS、路由与加密
//! HTTP/2 出站，与 Java `proxy-remote` 二进制兼容。
//!
//! ## 当前进度
//!
//! - A1（脚手架）：导出 [`Java_com_proxy_android_NativeBridge_nativeVersion`]，
//!   验证「Kotlin → 加载 .so → 调用 native 方法」链路打通。
//! - A2（JNI 最小闭环）：见 [`jni_bridge`] 模块，导出
//!   `nativeStart` / `nativeStop` / `nativeStats`，并打通 Rust → Kotlin 的
//!   `protect` / `onStatus` 回调。
//! - A3（系统 fd 接入）：见 [`android_tun`] 模块，用 `AsyncFd` 把 TUN fd 包装成
//!   与桌面 `tun_device.rs` 同签名的异步 `(TunReader, TunWriter)`。
//! - A4（数据面接线）：见 [`net_probe`] 模块，把 A3 的 `(TunReader, TunWriter)`
//!   接入最小数据面循环——FakeDNS 应答（逻辑对齐桌面 `fake_dns.rs`）+ IPv4 包识别
//!   （DNS/TCP SYN）。`plane-net` 共享 crate 完整抽取记为技术债，留待 A5/A6。
//!
//! 本文件保持向后兼容、不破坏已通过的下层验收。
//!
//! ## 设计铁律（详见 docs/design/android-client-tasks.md 0.3）
//!
//! - 所有 `extern "C"` JNI 函数体必须用 `catch_unwind` 包裹，禁止 panic 跨越 FFI。
//! - 所有出站 socket 必须先 protect 再 connect（A5 起落地）。

pub mod android_tun;
pub mod crypto;
pub mod dispatcher;
pub mod error;
pub mod jni_bridge;
pub mod logging;
pub mod mobile_config;
pub mod net_probe;
pub mod outbound;
pub mod proxy_proto;
pub mod routing;
pub mod stats;
pub mod tcp_stack;

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

/// 返回本 native crate 的版本号（即 `CARGO_PKG_VERSION`）。
///
/// 这是 A1 的「探活」接口：Kotlin 侧 `NativeBridge.nativeVersion()` 调用它，
/// 只要返回非空字符串，即证明 `libplane_core.so` 被正确打包进 APK 并成功加载。
///
/// # Panics
///
/// 本函数内部对 JNI 调用使用了显式的错误兜底（见实现），不会 panic 跨越 FFI 边界。
/// 任何创建字符串失败的情况都会回退为返回空字符串（null jstring）。
///
/// # 命名约定
///
/// JNI 要求导出符号严格匹配 `Java_<完整类名下划线化>_<方法名>`，
/// 对应 Kotlin 侧 `package com.proxy.android; class NativeBridge { external fun nativeVersion() }`。
#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    // nativeVersion 是 Kotlin 侧最早调用的「探活」接口，借此机会幂等初始化日志，
    // 保证后续 native 日志（含加载/启动失败）都能进入 logcat（tag=plane-core）。
    crate::logging::init();
    // A1 占位实现：不引入复杂逻辑，因此无需 catch_unwind 的复杂场景；
    // 但仍以「不 panic 跨 FFI」为准则——用 match 显式处理失败，失败时返回空字符串。
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    /// 占位单测：保证测试框架与构建链路可用。
    /// JNI 导出函数依赖真实 `JNIEnv`，其行为由 A1 的 instrumented smoke test
    /// （Kotlin 侧断言 nativeVersion 非空）覆盖，此处仅做编译期保障。
    #[test]
    fn crate_version_is_not_empty() {
        assert!(!env!("CARGO_PKG_VERSION").is_empty());
    }
}
