package com.proxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import org.json.JSONObject

class PlaneVpnService : VpnService() {
    private val bridge: NativeBridge by lazy { NativeBridge(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var nativeHandle: Long = 0L
    private var foregroundStarted = false
    private var tunInterface: ParcelFileDescriptor? = null
    private var watchingNetwork = false
    @Volatile
    private var underlyingNetwork: Network? = null
    private val availableNetworks = mutableMapOf<Network, NetworkCapabilities>()
    private var recoveringNetwork = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            recordNetwork(network, getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network))
        }
        override fun onLost(network: Network) = recordNetwork(network, null)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = recordNetwork(network, capabilities)
    }

    private fun recordNetwork(network: Network, capabilities: NetworkCapabilities?) {
        mainHandler.post {
            if (!watchingNetwork) return@post
            if (capabilities != null && isPhysicalInternet(capabilities)) {
                availableNetworks[network] = capabilities
            } else {
                availableNetworks.remove(network)
            }
            refreshUnderlyingNetwork()
        }
    }

    private val statsTicker = object : Runnable {
        override fun run() {
            val handle = nativeHandle
            if (handle == 0L) return
            val json = runCatching { bridge.nativeStats(handle) }
                .onFailure { Log.w(TAG, "nativeStats failed", it) }
                .getOrElse { """{"running":true,"state":"stats_error"}""" }
            latestStatsJson = json
            refreshNativeStatus(json, recordTraffic = true)
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

        if (nativeHandle != 0L) {
            publishStatus(running = true, message = latestStatusMessage)
            return START_STICKY
        }

        ensureNotificationChannel()
        startForegroundCompat(getString(R.string.vpn_status_connecting))
        publishStatus(running = true, message = getString(R.string.vpn_status_connecting))
        watchUnderlyingNetwork()

        val failure = try {
            if (startDataPlane(intent)) null else getString(R.string.vpn_status_error)
        } catch (_: NoAvailableVpnApps) {
            getString(R.string.app_routing_unavailable)
        }
        if (failure != null) {
            stopSelfSafely()
            publishStatus(running = false, message = failure)
            return START_NOT_STICKY
        }

        refreshNativeStatus(bridge.nativeStats(nativeHandle))
        return START_STICKY
    }

    private fun startDataPlane(intent: Intent?): Boolean {
        val config = runCatching { buildConfig(intent) }
            .onFailure {
                Log.e(TAG, "VPN configuration could not be loaded", it)
                AppLogStore.add("配置或证书加载失败，请检查节点配置")
            }
            .getOrNull() ?: return false
        val configJson = config.toJsonString()
        val pfd = establishTun(config.appRouting) ?: run {
            Log.e(TAG, "establish TUN failed")
            AppLogStore.add("建立 TUN 失败，请确认 VPN 授权")
            return false
        }
        tunInterface = pfd

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
        sessionTraffic.start(SystemClock.elapsedRealtime())
        setUnderlyingNetworks(underlyingNetwork?.let { arrayOf(it) } ?: emptyArray())
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

    private fun establishTun(appRouting: AppRoutingConfig): ParcelFileDescriptor? =
        try {
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                .apply { applyAppRouting(this, appRouting) }
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                // The native data plane forwards IPv4 only. Leaving IPv6
                // unconfigured makes Android block it and use IPv4 fallback.
                .addDnsServer(FAKE_DNS_SERVER)
                .establish()
        } catch (missing: NoAvailableVpnApps) {
            throw missing
        } catch (error: Exception) {
            Log.e(TAG, "establish threw", error)
            null
        }

    private fun applyAppRouting(builder: Builder, policy: AppRoutingConfig) {
        fun add(name: String, register: (String) -> Builder): Boolean =
            try {
                // Some Android versions accept an unknown package in Builder
                // and only omit its UID later. Check availability explicitly.
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(name, 0)
                register(name)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
        val unavailable = policy.applyToVpn(
            ownPackage = packageName,
            allow = { add(it, builder::addAllowedApplication) },
            exclude = { add(it, builder::addDisallowedApplication) },
        )
        if (unavailable > 0) {
            AppLogStore.add("应用分流已跳过 ${unavailable} 个不可用应用")
        }
    }

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

        val network = underlyingNetwork
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
    fun resolveIpOutsideVpn(host: String): String? {
        val network = underlyingNetwork ?: run {
            Log.w(TAG, "resolveIpOutsideVpn($host): no non-VPN network")
            return null
        }
        return runCatching {
            val addresses = network.getAllByName(host)
            (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())
                ?.hostAddress
        }.onFailure {
            Log.w(TAG, "resolveIpOutsideVpn($host) on $network failed", it)
        }.getOrNull()
    }

    private fun isPhysicalInternet(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun selectUnderlyingNetwork(): Network? =
        getSystemService(ConnectivityManager::class.java).activeNetwork?.takeIf { it in availableNetworks }
            ?: availableNetworks.maxByOrNull { (_, caps) ->
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0)
            }?.key

    private fun watchUnderlyingNetwork() {
        if (watchingNetwork) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        availableNetworks.clear()
        // Seed once, then treat onLost as authoritative. Re-querying allNetworks
        // inside onLost can still return the just-disconnected network.
        @Suppress("DEPRECATION")
        connectivity.allNetworks.forEach { network ->
            connectivity.getNetworkCapabilities(network)?.takeIf(::isPhysicalInternet)?.let {
                availableNetworks[network] = it
            }
        }
        underlyingNetwork = selectUnderlyingNetwork()
        recoveringNetwork = false
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            networkCallback,
        )
        watchingNetwork = true
        setUnderlyingNetworks(underlyingNetwork?.let { arrayOf(it) } ?: emptyArray())
    }

    private fun refreshUnderlyingNetwork() {
        val next = selectUnderlyingNetwork()
        if (next == underlyingNetwork) return
        underlyingNetwork = next
        setUnderlyingNetworks(next?.let { arrayOf(it) } ?: emptyArray())
        if (nativeHandle == 0L) return
        recoveringNetwork = true
        bridge.nativeNetworkChanged(nativeHandle)
        AppLogStore.add(if (next == null) "底层网络不可用，等待网络恢复" else "底层网络已切换，已清理旧连接")
        publishStatus(true, getString(if (next == null) R.string.vpn_status_no_network else R.string.vpn_status_recovering))
    }

    private fun stopWatchingNetwork() {
        if (watchingNetwork) {
            watchingNetwork = false
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        availableNetworks.clear()
        underlyingNetwork = null
        recoveringNetwork = false
    }

    private fun buildConfig(intent: Intent?): AndroidVpnConfig {
        val explicit = intent?.getStringExtra(EXTRA_CONFIG_JSON)?.takeIf { it.isNotBlank() }
        val json = explicit ?: VpnConfigStore.load(this).toJsonString()
        val savedConfig = AndroidVpnConfig.fromJson(json)
        val config = savedConfig.forConnection()
        // Trust the deployment certificate only for its configured node. Other
        // hosts use system roots or an explicitly imported CA, never this pin.
        val nativeConfig = config.copy(remotes = config.remotes.map { node ->
            if (node.transport == "http3" && node.host == VpnConfigStore.DEFAULT_REMOTE_HOST && node.caPem.isBlank()) {
                node.copy(caPem = assets.open("http3-remote-ca.crt").bufferedReader().use { it.readText() })
            } else node
        })
        val selection = if (savedConfig.selectedNodeIndex < 0) "自动" else "手动"
        val summary = "$selection，使用 ${config.remotes.size}/${savedConfig.remotes.size} 个节点，协议 ${config.remotes.joinToString { it.transport }}，规则 ${config.routing.rules.size}"
        AppLogStore.add("加载配置：$summary")
        Log.i(TAG, "VPN config: $summary")
        return nativeConfig
    }

    fun onNativeStatus(state: String) {
        Log.i(TAG, "native status: $state")
        AppLogStore.add("native: $state")
        // Native callbacks may run on a Tokio thread, including during startup.
        mainHandler.post {
            if (nativeHandle != 0L) {
                runCatching { bridge.nativeStats(nativeHandle) }.onSuccess(::refreshNativeStatus)
            }
        }
    }

    private fun refreshNativeStatus(json: String, recordTraffic: Boolean = false) {
        val stats = runCatching { JSONObject(json) }.getOrNull()
        if (recordTraffic) {
            sessionTraffic.record(SystemClock.elapsedRealtime(),
                stats?.optLong("upload_bytes", -1L) ?: -1L, stats?.optLong("download_bytes", -1L) ?: -1L)
        }
        if (stats == null) return
        val state = stats.optString("state")
        val message = if (underlyingNetwork == null && nativeHandle != 0L) {
            getString(R.string.vpn_status_no_network)
        } else when (state) {
            "connected" -> {
                recoveringNetwork = false
                getString(R.string.vpn_status_connected)
            }
            "ready" -> getString(if (recoveringNetwork) R.string.vpn_status_recovering else R.string.vpn_status_ready)
            "proxy_error" -> {
                val detail = stats.optString("last_error").ifBlank { getString(R.string.vpn_status_error) }
                getString(R.string.vpn_status_proxy_failure_detail, detail)
            }
            "error" -> {
                val detail = stats.optString("last_error").ifBlank { getString(R.string.vpn_status_error) }
                getString(R.string.vpn_status_failure_detail, detail)
            }
            "stopped" -> getString(R.string.vpn_status_idle)
            else -> getString(R.string.vpn_status_connecting)
        }
        if (message != latestStatusMessage) {
            publishStatus(running = nativeHandle != 0L, message = message)
        }
    }

    override fun onRevoke() {
        stopSelfSafely()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (nativeHandle != 0L || foregroundStarted) cleanupDataPlane()
        stopWatchingNetwork()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        cleanupDataPlane()
        stopSelf()
    }

    private fun cleanupDataPlane() {
        stopWatchingNetwork()
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
        sessionTraffic.stop()
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
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .addAction(0, getString(R.string.disconnect_vpn), PendingIntent.getService(
                this, 1, Intent(this, PlaneVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun updateForegroundNotification(statusText: String) {
        if (!foregroundStarted) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun publishStatus(running: Boolean, message: String) {
        isRunning = running
        latestStatusMessage = message
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
        internal val sessionTraffic = SessionTraffic()

        @Volatile
        var latestStatusMessage: String = "未连接"
            private set

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
        private const val FAKE_DNS_SERVER = "198.18.0.1"
    }
}
