package com.proxy.android

import android.util.Log

/**
 * Kotlin ↔ Rust（plane-core）的 JNI 桥接层。
 *
 * 职责分两类：
 *
 * 1. **Kotlin → Rust**：[nativeVersion]（A1 探活）、[nativeStart] / [nativeStop] /
 *    [nativeStats]（A2 数据面生命周期）。
 * 2. **Rust → Kotlin 回调**：[protect] / [onStatus]，由 Rust 在任意线程经
 *    `attach_current_thread` 反射调用。**这两个方法名/签名必须与
 *    `plane-core/src/jni_bridge.rs` 中的 `call_method` 严格一致**，且不可被混淆
 *    （已在 proguard-rules.pro 中 keep 本类）。
 *
 * ## 构造方式（兼容 A1）
 *
 * - 无参构造 [NativeBridge]：仅用于 A1 的 [nativeVersion] 探活，无需 VpnService。
 * - 带 [vpn] 的构造：数据面场景，[protect] 回调转发给 [PlaneVpnService.protect]。
 *
 * 之所以保留无参构造，是为了不破坏 A1 已通过的 `MainActivity` 探活与 smoke test。
 *
 * @property vpn 关联的 VPN 服务；为 null 时 [protect] 回调将返回 false 并记日志
 *   （仅探活场景会出现，数据面场景必须传入）。
 */
class NativeBridge(private val vpn: PlaneVpnService? = null) {

    /**
     * 返回 native crate 的版本号。仅用于 A1 探活，证明 JNI 链路与 .so 加载正常。
     *
     * @return plane-core 的 Cargo 包版本号，例如 "0.1.0"。
     */
    external fun nativeVersion(): String

    /**
     * 启动一次数据面会话。
     *
     * @param tunFd `VpnService.establish().detachFd()` 得到的 TUN 文件描述符
     *   （A2 阶段可传入任意有效 fd 用于打通闭环；真实 TUN 在 A3 接入）。
     * @param configJson 节点与运行时配置（JSON）；空串表示全部使用默认值。
     * @return 运行时 handle（i64），**0 表示启动失败**。后续 [nativeStop] / [nativeStats]
     *   需带上此 handle。
     */
    external fun nativeStart(tunFd: Int, configJson: String): Long

    /**
     * 停止并回收一个会话。传入 0 或已回收的 handle 是安全的（no-op）。
     *
     * 调用方必须在调用后立即把本地保存的 handle 置 0，避免重复回收（use-after-free）。
     */
    external fun nativeStop(handle: Long)

    /**
     * 返回会话统计 JSON。A2 阶段固定返回 `"{}"`，真实统计在 B7 填充。
     */
    external fun nativeStats(handle: Long): String

    /**
     * **被 Rust 回调**：把出站 socket fd 交给 [PlaneVpnService.protect]，
     * 使其不经过 TUN（防回环铁律，见任务文档 0.3-1）。
     *
     * @param fd 待保护的 socket 文件描述符。
     * @return protect 是否成功；无关联 VpnService 时返回 false。
     */
    fun protect(fd: Int): Boolean {
        val service = vpn
        if (service == null) {
            Log.w(TAG, "protect($fd) 被调用但未关联 VpnService，返回 false")
            return false
        }
        return runCatching { service.protectOutboundSocket(fd) }
            .onFailure { Log.e(TAG, "protect($fd) 抛出异常", it) }
            .getOrDefault(false)
    }

    fun resolveIpv4(host: String): String? {
        val service = vpn ?: return null
        return runCatching { service.resolveIpv4OutsideVpn(host) }
            .onFailure { Log.w(TAG, "resolveIpv4($host) failed", it) }
            .getOrNull()
    }

    /**
     * **被 Rust 回调**：上报数据面状态（如 "connected" / "error" / "node_down"）。
     *
     * A2 阶段仅记录日志；B5/B7 会把状态投递到 UI 与通知栏。
     */
    fun onStatus(state: String) {
        Log.i(TAG, "onStatus: $state")
        vpn?.onNativeStatus(state)
    }

    companion object {
        private const val TAG = "NativeBridge"

        init {
            // 加载 libplane_core.so（由 cargo-ndk 编译、Gradle 打包进 jniLibs）。
            System.loadLibrary("plane_core")
        }
    }
}
