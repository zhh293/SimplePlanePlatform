package com.proxy.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/** Main Android client screen: configuration, connection state and safe lifecycle controls. */
class MainActivity : AppCompatActivity() {
    private lateinit var hostInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var keyInput: TextInputEditText
    private lateinit var toggleButton: MaterialButton
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusBadge: TextView
    private lateinit var endpointSummary: TextView
    private lateinit var statusDot: View

    private var pendingConfig: AppPreferences.Config? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PlaneVpnService.ACTION_STATUS) return
            val state = intent.getStringExtra(PlaneVpnService.EXTRA_STATE)
                ?: PlaneVpnService.STATE_IDLE
            val detail = intent.getStringExtra(PlaneVpnService.EXTRA_DETAIL).orEmpty()
            renderState(state, detail)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) pendingConfig?.let(::startVpn)
        pendingConfig = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingConfig?.let(::prepareVpn)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        keyInput = findViewById(R.id.keyInput)
        toggleButton = findViewById(R.id.toggleVpnButton)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        statusBadge = findViewById(R.id.statusBadge)
        endpointSummary = findViewById(R.id.endpointSummary)
        statusDot = findViewById(R.id.statusDot)

        val config = AppPreferences.load(this)
        hostInput.setText(config.host)
        portInput.setText(config.port.toString())
        keyInput.setText(config.key)

        findViewById<TextView>(R.id.nativeVersionText).text =
            getString(R.string.native_version_label, readNativeVersion())
        findViewById<View>(R.id.saveConfigButton).setOnClickListener {
            readConfig()?.let {
                AppPreferences.save(this, it)
                updateEndpoint(it)
                Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            }
        }
        toggleButton.setOnClickListener { onToggleClicked() }
        updateEndpoint(config)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(PlaneVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        renderState(PlaneVpnService.currentState, "")
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun readNativeVersion(): String =
        runCatching { NativeBridge().nativeVersion() }
            .getOrElse { "不可用" }

    private fun onToggleClicked() {
        if (PlaneVpnService.isRunning()) {
            stopVpn()
            return
        }
        val config = readConfig() ?: return
        AppPreferences.save(this, config)
        pendingConfig = config
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            prepareVpn(config)
        }
    }

    private fun prepareVpn(config: AppPreferences.Config) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingConfig = config
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpn(config)
            pendingConfig = null
        }
    }

    private fun startVpn(config: AppPreferences.Config) {
        val intent = Intent(this, PlaneVpnService::class.java)
            .putExtra(PlaneVpnService.EXTRA_REMOTE_HOST, config.host)
            .putExtra(PlaneVpnService.EXTRA_REMOTE_PORT, config.port)
            .putExtra(PlaneVpnService.EXTRA_REMOTE_KEY, config.key)
            .putExtra(PlaneVpnService.EXTRA_TLS, config.tls)
        ContextCompat.startForegroundService(this, intent)
        renderState(PlaneVpnService.STATE_STARTING, "正在建立安全网络接口")
    }

    private fun stopVpn() {
        startService(
            Intent(this, PlaneVpnService::class.java)
                .setAction(PlaneVpnService.ACTION_STOP),
        )
        renderState(PlaneVpnService.STATE_STOPPED, "手机网络已恢复为系统默认连接")
    }

    private fun readConfig(): AppPreferences.Config? {
        val host = hostInput.text?.toString()?.trim().orEmpty()
        val port = portInput.text?.toString()?.trim()?.toIntOrNull()
        val key = keyInput.text?.toString().orEmpty()

        hostInput.error = null
        portInput.error = null
        keyInput.error = null
        when {
            host.isBlank() -> {
                hostInput.error = getString(R.string.invalid_host)
                hostInput.requestFocus()
            }
            port == null || port !in 1..65535 -> {
                portInput.error = getString(R.string.invalid_port)
                portInput.requestFocus()
            }
            key.isBlank() -> {
                keyInput.error = getString(R.string.invalid_key)
                keyInput.requestFocus()
            }
            else -> return AppPreferences.Config(host, port, key, tls = false)
        }
        return null
    }

    private fun updateEndpoint(config: AppPreferences.Config) {
        endpointSummary.text = if (AppPreferences.isUsable(config)) {
            "当前节点  ·  ${config.host}:${config.port}  ·  HTTP/2 h2c"
        } else {
            "节点未配置完整，连接前请填写共享密钥"
        }
    }

    private fun renderState(state: String, detail: String) {
        val (title, fallback, color, badge, buttonText) = when (state) {
            PlaneVpnService.STATE_STARTING -> StateUi("正在启动", "正在创建 VPN 接口", R.color.plane_warning, "STARTING", "正在启动…")
            PlaneVpnService.STATE_CONNECTING -> StateUi("正在连接节点", "正在绕过 VPN 回环并连接远端", R.color.plane_warning, "CONNECTING", "正在连接…")
            PlaneVpnService.STATE_CONNECTED -> StateUi("隧道已连接", "所有支持的 IPv4 流量正在通过远端节点", R.color.plane_lime, "SECURE", getString(R.string.disconnect_vpn))
            PlaneVpnService.STATE_ERROR -> StateUi("连接失败", "未建立 VPN，手机网络保持可用", R.color.plane_error, "SAFE", getString(R.string.toggle_vpn))
            PlaneVpnService.STATE_NODE_DOWN -> StateUi("节点不可达", "未建立 VPN，请检查地址、端口与服务端配置", R.color.plane_error, "SAFE", getString(R.string.toggle_vpn))
            PlaneVpnService.STATE_STOPPED -> StateUi("已断开", "手机网络已恢复为系统默认连接", R.color.plane_muted, "SAFE", getString(R.string.toggle_vpn))
            else -> StateUi("未连接", "VPN 未启动，手机网络保持不变", R.color.plane_muted, "SAFE", getString(R.string.toggle_vpn))
        }
        statusTitle.text = title
        statusDetail.text = detail.takeIf { it.isNotBlank() } ?: fallback
        statusBadge.text = badge
        statusBadge.setTextColor(ContextCompat.getColor(this, color))
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        toggleButton.text = buttonText
        toggleButton.isEnabled = state != PlaneVpnService.STATE_STARTING && state != PlaneVpnService.STATE_CONNECTING
        toggleButton.alpha = if (toggleButton.isEnabled) 1f else 0.72f
    }

    private data class StateUi(
        val title: String,
        val fallback: String,
        val color: Int,
        val badge: String,
        val buttonText: String,
    )
}
