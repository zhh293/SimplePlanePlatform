package com.proxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Inet4Address

class PlaneVpnService : VpnService() {
    private val bridge: NativeBridge by lazy { NativeBridge(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var nativeHandle: Long = 0L
    private var foregroundStarted = false
    private var tunInterface: ParcelFileDescriptor? = null

    private val statsTicker = object : Runnable {
        override fun run() {
            val handle = nativeHandle
            if (handle == 0L) return
            val json = runCatching { bridge.nativeStats(handle) }
                .onFailure { Log.w(TAG, "nativeStats failed", it) }
                .getOrElse { """{"running":true,"state":"stats_error"}""" }
            latestStatsJson = json
            publishStats(json)
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLogStore.add("收到停止 VPN 指令")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        startForegroundCompat(getString(R.string.vpn_status_connecting))
        publishStatus(running = true, message = getString(R.string.vpn_status_connecting))

        if (nativeHandle != 0L) {
            publishStatus(running = true, message = getString(R.string.vpn_status_connected))
            return START_STICKY
        }

        if (!startDataPlane(intent)) {
            publishStatus(running = false, message = getString(R.string.vpn_status_error))
            stopSelfSafely()
            return START_NOT_STICKY
        }

        publishStatus(running = true, message = getString(R.string.vpn_status_connected))
        return START_STICKY
    }

    private fun startDataPlane(intent: Intent?): Boolean {
        val pfd = establishTun() ?: run {
            Log.e(TAG, "establish TUN failed")
            AppLogStore.add("建立 TUN 失败，请确认 VPN 授权")
            return false
        }
        tunInterface = pfd

        val configJson = buildConfigJson(intent)
        val nativeBridge = runCatching { bridge }
            .onFailure { Log.e(TAG, "NativeBridge init failed", it) }
            .getOrElse {
                closeTunBeforeDetach()
                return false
            }

        val fd = pfd.detachFd()
        tunInterface = null
        val handle = runCatching { nativeBridge.nativeStart(fd, configJson) }
            .onFailure { Log.e(TAG, "nativeStart threw", it) }
            .getOrDefault(0L)

        if (handle == 0L) {
            AppLogStore.add("nativeStart 返回 0，数据面启动失败")
            return false
        }

        nativeHandle = handle
        startStatsTicker()
        AppLogStore.add("数据面已启动")
        Log.i(TAG, "data plane started, handle=$handle")
        return true
    }

    private fun closeTunBeforeDetach() {
        tunInterface?.let {
            runCatching { it.close() }.onFailure { e -> Log.w(TAG, "close TUN failed", e) }
        }
        tunInterface = null
    }

    private fun establishTun(): ParcelFileDescriptor? =
        runCatching {
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                // The data plane runs in this process. Excluding our own UID is
                // the last line of defense if a per-socket protect call regresses.
                .addDisallowedApplication(packageName)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                .addRoute("::", 0)
                .addDnsServer(FAKE_DNS_SERVER)
                .establish()
        }.onFailure { Log.e(TAG, "establish threw", it) }.getOrNull()

    /**
     * Excludes an outbound native socket from this VPN and pins it to the
     * currently usable physical network. Both direct and proxy connections
     * call this before connect, so neither can re-enter the TUN interface.
     */
    fun protectOutboundSocket(fd: Int): Boolean {
        if (!protect(fd)) {
            Log.e(TAG, "VpnService.protect($fd) returned false")
            AppLogStore.add("出站 socket 保护失败: fd=$fd")
            return false
        }

        val network = findUnderlyingNetwork()
        if (network == null) {
            Log.e(TAG, "no non-VPN network available for fd=$fd")
            AppLogStore.add("没有可用的底层网络")
            return false
        }

        return runCatching {
            // fromFd duplicates the descriptor. Binding the duplicate changes
            // the underlying socket while closing it leaves Rust's fd alive.
            ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                network.bindSocket(duplicate.fileDescriptor)
            }
            Log.d(TAG, "protected fd=$fd on network=$network")
            true
        }.onFailure {
            Log.e(TAG, "bind fd=$fd to network=$network failed", it)
            AppLogStore.add("出站 socket 绑定底层网络失败: ${it.message}")
        }.getOrDefault(false)
    }

    /** Resolves through one physical network, never through the VPN DNS path. */
    fun resolveIpv4OutsideVpn(host: String): String? {
        val network = findUnderlyingNetwork() ?: run {
            Log.w(TAG, "resolveIpv4OutsideVpn($host): no non-VPN network")
            return null
        }
        return runCatching {
            network.getAllByName(host)
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.onFailure {
            Log.w(TAG, "resolveIpv4OutsideVpn($host) on $network failed", it)
        }.getOrNull()
    }

    private fun findUnderlyingNetwork(): Network? {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return null

        fun isUsable(network: Network): Boolean {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        return connectivity.activeNetwork?.takeIf(::isUsable)
    }

    private fun buildConfigJson(intent: Intent?): String {
        val explicit = intent?.getStringExtra(EXTRA_CONFIG_JSON)?.takeIf { it.isNotBlank() }
        val json = explicit ?: VpnConfigStore.load(this).toJsonString()
        val summary = runCatching {
            val config = AndroidVpnConfig.fromJson(json)
            "节点 ${config.remotes.count { it.enabled }}/${config.remotes.size}，规则 ${config.routing.rules.size}，默认 ${config.routing.defaultAction}"
        }.getOrDefault("原始配置")
        AppLogStore.add("加载配置：$summary")
        Log.i(TAG, "VPN config: $summary")
        return json
    }

    fun onNativeStatus(state: String) {
        Log.i(TAG, "native status: $state")
        AppLogStore.add("native: $state")
        val message = when (state) {
            "connected" -> getString(R.string.vpn_status_connected)
            "error" -> getString(R.string.vpn_status_error)
            "stopped" -> getString(R.string.vpn_status_idle)
            else -> state
        }
        publishStatus(running = isRunning, message = message)
    }

    override fun onRevoke() {
        stopSelfSafely()
        super.onRevoke()
    }

    override fun onDestroy() {
        cleanupDataPlane()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        cleanupDataPlane()
        stopSelf()
    }

    private fun cleanupDataPlane() {
        if (nativeHandle != 0L) {
            runCatching { bridge.nativeStop(nativeHandle) }
                .onFailure { Log.e(TAG, "nativeStop threw", it) }
            nativeHandle = 0L
        }
        stopStatsTicker()
        closeTunBeforeDetach()
        stopForegroundRemoveCompat()
        publishStatus(running = false, message = getString(R.string.vpn_status_idle))
    }

    private fun startStatsTicker() {
        mainHandler.removeCallbacks(statsTicker)
        mainHandler.post(statsTicker)
    }

    private fun stopStatsTicker() {
        mainHandler.removeCallbacks(statsTicker)
        latestStatsJson = """{"running":false,"state":"stopped"}"""
        publishStats(latestStatsJson)
    }

    private fun stopForegroundRemoveCompat() {
        if (!foregroundStarted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundStarted = false
    }

    private fun startForegroundCompat(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(statusText: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()

    private fun updateForegroundNotification(statusText: String) {
        if (!foregroundStarted) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun publishStatus(running: Boolean, message: String) {
        isRunning = running
        AppLogStore.add(message)
        updateForegroundNotification(message)
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_STATUS_MESSAGE, message),
        )
    }

    private fun publishStats(json: String) {
        sendBroadcast(
            Intent(ACTION_STATS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_STATS_JSON, json),
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "PlaneVpnService"
        private const val CHANNEL_ID = "plane_vpn_status"
        private const val NOTIFICATION_ID = 1001
        private const val STATS_INTERVAL_MS = 1000L

        const val ACTION_START = "com.proxy.android.action.START_VPN"
        const val ACTION_STOP = "com.proxy.android.action.STOP_VPN"
        const val ACTION_STATUS_CHANGED = "com.proxy.android.action.VPN_STATUS_CHANGED"
        const val ACTION_STATS_CHANGED = "com.proxy.android.action.VPN_STATS_CHANGED"

        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_STATS_JSON = "extra_stats_json"
        const val EXTRA_CONFIG_JSON = "extra_config_json"

        // Kept for compatibility with older local smoke tests.
        const val EXTRA_REMOTE_HOST = "extra_remote_host"
        const val EXTRA_REMOTE_PORT = "extra_remote_port"
        const val EXTRA_REMOTE_KEY = "extra_remote_key"
        const val EXTRA_CIPHER = "extra_cipher"
        const val EXTRA_TLS = "extra_tls"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var latestStatsJson: String = """{"running":false,"state":"stopped"}"""
            private set

        private const val TUN_MTU = 1500
        private const val TUN_ADDRESS = "198.19.255.254"
        private const val TUN_PREFIX = 15
        private const val TUN_ADDRESS_V6 = "fd00::2"
        private const val TUN_PREFIX_V6 = 128
        private const val FAKE_DNS_SERVER = "198.18.0.1"
    }
}
