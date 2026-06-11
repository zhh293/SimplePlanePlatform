package com.proxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * 系统 VPN 服务。
 *
 * 进度：
 *
 * - A1：最小骨架——声明清单、被系统拉起并进入前台，但**不建立 TUN、不启动数据面**。
 * - A2：持有 [NativeBridge]，对接 Rust → Kotlin 的 `protect` / `onStatus` 回调。
 *   `protect(fd)` 复用 [VpnService] 自带方法，把出站 socket 排除出 TUN（防回环 0.3-1）。
 *
 * 真正的 `establish()` + `nativeStart(fd, configJson)` 仍在 Task A3/A6 接入；A2 不在
 * `onStartCommand` 里启动数据面，以免破坏 A1 已通过的生命周期验收。
 */
class PlaneVpnService : VpnService() {

    /**
     * JNI 桥接实例，关联本服务以便 Rust 回调 `protect` / `onStatus`。
     * A3/A6 起用它调用 `nativeStart` / `nativeStop`。
     */
    private val bridge: NativeBridge by lazy { NativeBridge(this) }

    /** 当前会话 handle（0 = 未启动）。由 `nativeStart` 赋值。 */
    private var nativeHandle: Long = 0L

    /**
     * 当前 TUN 接口（仅用于在未成功 detachFd 前的兜底关闭）。
     *
     * 正常路径下 [establishTun] 会 `detachFd()` 把 fd 所有权移交 Rust，此后由
     * native 侧在 stop/Drop 时 `close(fd)`；本字段届时为 null。仅当 establish 成功
     * 但 nativeStart 失败时，用它关闭尚未移交的接口，避免泄漏。
     */
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForegroundCompat()

        // 已在运行则不重复启动（避免重复 establish/ nativeStart）。
        if (nativeHandle != 0L) {
            Log.i(TAG, "数据面已在运行，忽略重复 onStartCommand")
            return START_STICKY
        }

        if (!startDataPlane(intent)) {
            // 启动失败：退出前台并停止自身，保证不残留半启动状态。
            Log.e(TAG, "数据面启动失败，停止服务")
            stopSelfSafely()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * 建立 TUN 并拉起 native 数据面。成功返回 true。
     *
     * 流程（A6）：
     *  1. [establishTun] 配置路由/DNS/MTU 并 `establish()`。
     *  2. `detachFd()` 取出 fd，**所有权移交 Rust**（此后 Java 不再 close）。
     *  3. [NativeBridge.nativeStart] 传入 fd 与 configJson 启动栈+调度器。
     */
    private fun startDataPlane(intent: Intent?): Boolean {
        val pfd = establishTun() ?: run {
            Log.e(TAG, "establish TUN 失败（可能未授权或被系统拒绝）")
            return false
        }
        tunInterface = pfd

        val configJson = buildConfigJson(intent)
        val fd = pfd.detachFd() // 所有权移交 native；detach 后 pfd 不再持有 fd。
        tunInterface = null // 已移交，置空避免误 close。

        val handle = runCatching { bridge.nativeStart(fd, configJson) }
            .onFailure { Log.e(TAG, "nativeStart 抛出异常", it) }
            .getOrDefault(0L)

        if (handle == 0L) {
            // nativeStart 失败：fd 已 detach 给 native，但 native 启动失败时其内部
            // 会负责回收（from_raw_fd 失败路径不持有 fd / 成功则 Drop 时 close）。
            // 这里仅记录，无法再安全 close 已 detach 的 fd。
            Log.e(TAG, "nativeStart 返回 0（启动失败）")
            return false
        }
        nativeHandle = handle
        Log.i(TAG, "数据面已启动，handle=$handle")
        return true
    }

    /**
     * 配置并建立 TUN 接口。
     *
     * - 地址：给 TUN 一个内网私有地址（10.0.0.2/32），作为应用流量的源。
     * - 路由：`0.0.0.0/0` 全局接管（含 FakeIP 段 198.18.0.0/15）。
     * - DNS：指向 FakeDNS 服务器 198.18.0.1，使 DNS 查询进栈走 FakeDNS。
     * - MTU：1500（与 native 默认一致）。
     */
    private fun establishTun(): ParcelFileDescriptor? {
        return runCatching {
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                // 全局接管所有 IPv4 流量（含 FakeIP 198.18.0.0/15）。
                .addRoute("0.0.0.0", 0)
                // DNS 指向 FakeDNS 服务器地址，DNS 查询将被用户态栈用 FakeIP 应答。
                .addDnsServer(FAKE_DNS_SERVER)
                .establish()
        }.onFailure { Log.e(TAG, "establish 异常", it) }.getOrNull()
    }

    /**
     * 组装下发给 native 的 configJson。
     *
     * 节点配置来自启动 Intent 的 extra（由 [MainActivity] 填入）；缺省时字段为空，
     * native 侧会因 `outbound_ready()=false` 退回 protect 自检（不建立出站，不崩溃）。
     */
    private fun buildConfigJson(intent: Intent?): String {
        val host = intent?.getStringExtra(EXTRA_REMOTE_HOST).orEmpty()
        val port = intent?.getIntExtra(EXTRA_REMOTE_PORT, 0) ?: 0
        val key = intent?.getStringExtra(EXTRA_REMOTE_KEY).orEmpty()
        val tls = intent?.getBooleanExtra(EXTRA_TLS, false) ?: false

        val json = JSONObject()
            .put("mtu", TUN_MTU)
            .put("remote_host", host)
            .put("remote_port", port)
            .put("remote_key", key)
            .put("tls", tls)
        if (host.isEmpty() || port == 0 || key.isEmpty()) {
            Log.w(TAG, "节点配置不完整（host/port/key），native 将仅做 protect 自检")
        }
        return json.toString()
    }

    /**
     * 接收来自 Rust（经 [NativeBridge.onStatus]）的状态上报。
     *
     * A2 阶段仅记录日志；B5/B7 会据此刷新通知与 UI 状态。
     */
    fun onNativeStatus(state: String) {
        Log.i(TAG, "native status: $state")
    }

    /**
     * 用户在系统设置里撤销 VPN 授权时回调。A6 起须在此 nativeStop 并清理。
     */
    override fun onRevoke() {
        stopSelfSafely()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopSelfSafely()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        // 1) 回收 native 会话：nativeStop 内部 send(shutdown) 让栈/调度器优雅退出，
        //    并在 Drop 时 close(TUN fd)。handle 为 0 时为 no-op。
        if (nativeHandle != 0L) {
            runCatching { bridge.nativeStop(nativeHandle) }
                .onFailure { Log.e(TAG, "nativeStop 异常", it) }
            nativeHandle = 0L
        }
        // 2) 兜底：仅当 establish 成功但尚未 detach 给 native（nativeStart 之前失败）时，
        //    此处关闭未移交的接口，避免 fd 泄漏。正常路径 tunInterface 已为 null。
        tunInterface?.let {
            runCatching { it.close() }.onFailure { e -> Log.w(TAG, "关闭未移交 TUN 失败", e) }
        }
        tunInterface = null
        stopForegroundRemoveCompat()
        stopSelf()
    }

    /** 退出前台并移除通知，兼容 API < 33（`stopForeground(int)` 重载 API 33+）。 */
    private fun stopForegroundRemoveCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_status_idle))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // targetSdk 34：前台服务必须指定类型，VPN 使用 specialUse。
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val TAG = "PlaneVpnService"
        private const val CHANNEL_ID = "plane_vpn_status"
        private const val NOTIFICATION_ID = 1001

        /** TUN MTU，与 native AndroidConfig 默认一致。 */
        private const val TUN_MTU = 1500

        /** TUN 接口内网地址与前缀（应用流量源地址）。 */
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_PREFIX = 32

        /** FakeDNS 服务器地址（与 native FakeIP 池 198.18.0.1 一致）。 */
        private const val FAKE_DNS_SERVER = "198.18.0.1"

        /** 启动 Intent 的节点配置 extra key（由 MainActivity 填入）。 */
        const val EXTRA_REMOTE_HOST = "extra_remote_host"
        const val EXTRA_REMOTE_PORT = "extra_remote_port"
        const val EXTRA_REMOTE_KEY = "extra_remote_key"
        const val EXTRA_TLS = "extra_tls"
    }
}
