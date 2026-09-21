package com.proxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Foreground VPN service with safe startup, explicit state reporting and clean teardown. */
class PlaneVpnService : VpnService() {
    private val bridge: NativeBridge by lazy { NativeBridge(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nativeHandle: Long = 0L
    private var tunInterface: ParcelFileDescriptor? = null
    private var stopping = false
    private var networkMonitorRegistered = false
    private val monitoredNetworks = ConcurrentHashMap.newKeySet<Network>()
    private val networkLossRunnable = Runnable {
        if (nativeHandle != 0L && monitoredNetworks.isEmpty()) {
            stopSelfSafely(STATE_NODE_DOWN, getString(R.string.network_unavailable))
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            monitoredNetworks += network
            mainHandler.removeCallbacks(networkLossRunnable)
        }

        override fun onLost(network: Network) {
            monitoredNetworks -= network
            if (monitoredNetworks.isEmpty()) {
                mainHandler.removeCallbacks(networkLossRunnable)
                mainHandler.postDelayed(networkLossRunnable, NETWORK_LOSS_GRACE_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely(STATE_STOPPED, "手机网络已恢复为系统默认连接")
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        startForegroundCompat(STATE_STARTING)
        if (nativeHandle != 0L) return START_STICKY

        val config = readConfig(intent)
        if (!AppPreferences.isUsable(config)) {
            publishStatus(STATE_ERROR, "节点配置不完整，未建立 VPN")
            stopSelfSafely(STATE_ERROR, "节点配置不完整，未建立 VPN")
            return START_NOT_STICKY
        }
        if (config.tls) {
            publishStatus(STATE_ERROR, getString(R.string.tls_not_supported))
            stopSelfSafely(STATE_ERROR, getString(R.string.tls_not_supported))
            return START_NOT_STICKY
        }

        registerNetworkMonitor()
        publishStatus(STATE_CONNECTING, "正在连接 ${config.host}:${config.port}")
        if (!startDataPlane(config)) {
            stopSelfSafely(STATE_ERROR, "数据面启动失败，未建立 VPN")
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun readConfig(intent: Intent?): AppPreferences.Config {
        val saved = AppPreferences.load(this)
        return AppPreferences.Config(
            host = intent?.getStringExtra(EXTRA_REMOTE_HOST)?.takeIf { it.isNotBlank() } ?: saved.host,
            port = intent?.getIntExtra(EXTRA_REMOTE_PORT, 0)?.takeIf { it != 0 } ?: saved.port,
            key = intent?.getStringExtra(EXTRA_REMOTE_KEY)?.takeIf { it.isNotBlank() } ?: saved.key,
            tls = intent?.getBooleanExtra(EXTRA_TLS, saved.tls) ?: saved.tls,
        )
    }

    private fun startDataPlane(config: AppPreferences.Config): Boolean {
        val pfd = establishTun() ?: run {
            Log.e(TAG, "VpnService.establish() failed")
            return false
        }
        tunInterface = pfd
        val fd = pfd.detachFd()
        tunInterface = null

        val handle = runCatching { bridge.nativeStart(fd, buildConfigJson(config)) }
            .onFailure { Log.e(TAG, "nativeStart failed", it) }
            .getOrDefault(0L)
        if (handle == 0L) {
            Log.e(TAG, "nativeStart returned 0")
            return false
        }
        nativeHandle = handle
        publishStatus(STATE_CONNECTING, "VPN 已准备，正在等待远程节点握手")
        return true
    }

    private fun establishTun(): ParcelFileDescriptor? = runCatching {
        Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            // The native MVP only supports IPv4. Capturing IPv6 prevents silent bypass;
            // applications that support Happy Eyeballs can fall back to IPv4.
            .addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
            .addRoute("::", 0)
            .addDnsServer(FAKE_DNS_SERVER)
            .establish()
    }.onFailure { Log.e(TAG, "establish failed", it) }.getOrNull()

    private fun buildConfigJson(config: AppPreferences.Config): String = JSONObject()
        .put("mtu", TUN_MTU)
        .put("remote_host", config.host)
        .put("remote_port", config.port)
        .put("remote_key", config.key)
        .put("tls", config.tls)
        .toString()

    /** Rust callback. Errors stop the VPN so a dead tunnel can never black-hole the phone. */
    fun onNativeStatus(state: String) {
        Log.i(TAG, "native status: $state")
        when {
            state == STATE_CONNECTED -> publishStatus(STATE_CONNECTED, "所有支持的 IPv4 流量正在通过远端节点")
            state == STATE_NODE_DOWN -> {
                publishStatus(STATE_NODE_DOWN, "远程节点不可达，已自动断开以恢复手机网络")
                mainHandler.post { stopSelfSafely(STATE_NODE_DOWN, "远程节点不可达，VPN 已安全断开") }
            }
            state == STATE_ERROR -> {
                publishStatus(STATE_ERROR, "数据面发生错误，VPN 已安全断开")
                mainHandler.post { stopSelfSafely(STATE_ERROR, "数据面发生错误，VPN 已安全断开") }
            }
            else -> publishStatus(state, "正在处理网络连接")
        }
    }

    override fun onRevoke() {
        stopSelfSafely(STATE_STOPPED, "VPN 权限已撤销")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopSelfSafely(STATE_STOPPED, "VPN 已断开")
        super.onDestroy()
    }

    private fun stopSelfSafely(finalState: String, detail: String) {
        if (stopping) return
        stopping = true
        unregisterNetworkMonitor()
        if (nativeHandle != 0L) {
            runCatching { bridge.nativeStop(nativeHandle) }
                .onFailure { Log.e(TAG, "nativeStop failed", it) }
            nativeHandle = 0L
        }
        tunInterface?.let { runCatching { it.close() } }
        tunInterface = null
        currentState = finalState
        publishStatus(finalState, detail)
        stopForegroundCompat()
        stopSelf()
    }

    /** Monitor only physical Internet-capable networks, never the VPN network itself. */
    private fun registerNetworkMonitor() {
        if (networkMonitorRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.registerNetworkCallback(request, networkCallback)
            networkMonitorRegistered = true
        }.onFailure { Log.w(TAG, "underlying network monitor unavailable", it) }
    }

    private fun unregisterNetworkMonitor() {
        mainHandler.removeCallbacks(networkLossRunnable)
        monitoredNetworks.clear()
        if (!networkMonitorRegistered) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { manager.unregisterNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "underlying network monitor cleanup failed", it) }
        networkMonitorRegistered = false
    }

    private fun publishStatus(state: String, detail: String) {
        currentState = state
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_DETAIL, detail)
        sendBroadcast(intent)
        if (state == STATE_STARTING || state == STATE_CONNECTING || state == STATE_CONNECTED) {
            updateNotification(state)
        }
    }

    private fun startForegroundCompat(state: String) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: String): Notification {
        val stopIntent = Intent(this, PlaneVpnService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            STATE_CONNECTED -> "隧道已连接 · IPv4 流量受保护"
            STATE_CONNECTING, STATE_STARTING -> "正在建立安全隧道"
            else -> "VPN 已停止"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(state == STATE_CONNECTED || state == STATE_CONNECTING || state == STATE_STARTING)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_stop), stopPending)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "PlaneVpnService"
        private const val CHANNEL_ID = "plane_vpn_status"
        private const val NOTIFICATION_ID = 1001
        private const val TUN_MTU = 1500
        private const val TUN_ADDRESS = "198.19.255.254"
        private const val TUN_PREFIX = 15
        private const val TUN_ADDRESS_V6 = "fd00::2"
        private const val TUN_PREFIX_V6 = 128
        private const val FAKE_DNS_SERVER = "198.18.0.1"
        private const val NETWORK_LOSS_GRACE_MS = 3_000L

        const val ACTION_STATUS = "com.proxy.android.STATUS"
        const val ACTION_STOP = "com.proxy.android.STOP"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_REMOTE_HOST = "extra_remote_host"
        const val EXTRA_REMOTE_PORT = "extra_remote_port"
        const val EXTRA_REMOTE_KEY = "extra_remote_key"
        const val EXTRA_TLS = "extra_tls"

        const val STATE_IDLE = "idle"
        const val STATE_STARTING = "starting"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_NODE_DOWN = "node_down"
        const val STATE_ERROR = "error"
        const val STATE_STOPPED = "stopped"

        @Volatile
        var currentState: String = STATE_IDLE

        fun isRunning(): Boolean = currentState == STATE_STARTING ||
            currentState == STATE_CONNECTING || currentState == STATE_CONNECTED
    }
}
