package com.proxy.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val routeActions = listOf("proxy", "direct", "reject")
    private val nodeRows = mutableListOf<NodeRow>()
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var mainContent: LinearLayout
    private lateinit var toggleVpnButton: Button
    private lateinit var saveConfigButton: Button
    private lateinit var vpnStatusText: TextView
    private lateinit var statsText: TextView
    private lateinit var nodesContainer: LinearLayout
    private lateinit var addNodeButton: Button
    private lateinit var defaultRouteSpinner: Spinner
    private lateinit var cnDirectCheckBox: CheckBox
    private lateinit var directListInput: EditText
    private lateinit var proxyListInput: EditText
    private lateinit var advancedRulesInput: EditText
    private lateinit var presetNameInput: EditText
    private lateinit var presetSpinner: Spinner
    private lateinit var importConfigInput: EditText
    private lateinit var logText: TextView

    private var vpnRunning = false
    private var presets: List<ConfigPreset> = emptyList()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaneVpnService.ACTION_STATUS_CHANGED -> {
                    val running = intent.getBooleanExtra(PlaneVpnService.EXTRA_RUNNING, false)
                    val message = intent.getStringExtra(PlaneVpnService.EXTRA_STATUS_MESSAGE)
                    updateVpnUi(running, message)
                    updateLogView()
                }
                PlaneVpnService.ACTION_STATS_CHANGED -> {
                    updateStats(intent.getStringExtra(PlaneVpnService.EXTRA_STATS_JSON).orEmpty())
                }
            }
        }
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            updateStats(PlaneVpnService.latestStatsJson)
            updateLogView()
            uiHandler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    private val prepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            updateVpnUi(false, getString(R.string.vpn_status_idle))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_main)

        mainContent = findViewById(R.id.mainContent)
        findViewById<TextView>(R.id.nativeVersionText).text =
            getString(R.string.native_version_label, readNativeVersion())
        vpnStatusText = findViewById(R.id.vpnStatusText)
        statsText = findViewById(R.id.statsText)
        nodesContainer = findViewById(R.id.nodesContainer)
        addNodeButton = findViewById<Button>(R.id.addNodeButton).apply {
            setOnClickListener { addNodeRow(RemoteNodeConfig(name = "节点 ${nodeRows.size + 1}", host = "", port = 9090, key = "")) }
        }
        defaultRouteSpinner = findViewById(R.id.defaultRouteSpinner)
        defaultRouteSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, routeActions)
        cnDirectCheckBox = findViewById(R.id.cnDirectCheckBox)
        directListInput = findViewById(R.id.directListInput)
        proxyListInput = findViewById(R.id.proxyListInput)
        advancedRulesInput = findViewById(R.id.advancedRulesInput)
        presetNameInput = findViewById(R.id.presetNameInput)
        presetSpinner = findViewById(R.id.presetSpinner)
        importConfigInput = findViewById(R.id.importConfigInput)
        logText = findViewById(R.id.logText)

        saveConfigButton = findViewById<Button>(R.id.saveConfigButton).apply {
            setOnClickListener { saveCurrentConfig(showSavedToast = true) }
        }
        toggleVpnButton = findViewById<Button>(R.id.toggleVpnButton).apply {
            setOnClickListener { onToggleClicked() }
        }
        findViewById<Button>(R.id.savePresetButton).setOnClickListener { savePreset() }
        findViewById<Button>(R.id.loadPresetButton).setOnClickListener { loadSelectedPreset() }
        findViewById<Button>(R.id.deletePresetButton).setOnClickListener { deleteSelectedPreset() }
        findViewById<Button>(R.id.importConfigButton).setOnClickListener { importConfig() }
        findViewById<Button>(R.id.exportConfigButton).setOnClickListener { exportConfig() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            AppLogStore.clear()
            updateLogView()
        }

        refreshPresets()
        bindConfigToForm(VpnConfigStore.load(this))
        mainContent.requestFocus()
        updateVpnUi(PlaneVpnService.isRunning)
        updateStats(PlaneVpnService.latestStatsJson)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(PlaneVpnService.ACTION_STATUS_CHANGED)
            addAction(PlaneVpnService.ACTION_STATS_CHANGED)
        }
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        updateVpnUi(PlaneVpnService.isRunning)
        uiHandler.post(uiTicker)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        uiHandler.removeCallbacks(uiTicker)
        super.onStop()
    }

    private fun readNativeVersion(): String =
        runCatching { NativeBridge().nativeVersion() }
            .getOrElse { "<load failed: ${it.message}>" }

    private fun onToggleClicked() {
        if (vpnRunning) {
            stopVpn()
            return
        }
        if (saveCurrentConfig(showSavedToast = false) == null) return
        val intent = VpnService.prepare(this)
        if (intent != null) prepareLauncher.launch(intent) else startVpn()
    }

    private fun startVpn() {
        val config = saveCurrentConfig(showSavedToast = false) ?: return
        val intent = Intent(this, PlaneVpnService::class.java)
            .setAction(PlaneVpnService.ACTION_START)
            .putExtra(PlaneVpnService.EXTRA_CONFIG_JSON, config.toJsonString())
        ContextCompat.startForegroundService(this, intent)
        updateVpnUi(true, getString(R.string.vpn_status_connecting))
    }

    private fun stopVpn() {
        startService(Intent(this, PlaneVpnService::class.java).setAction(PlaneVpnService.ACTION_STOP))
        updateVpnUi(false, getString(R.string.vpn_status_disconnecting))
    }

    private fun bindConfigToForm(config: AndroidVpnConfig) {
        nodesContainer.removeAllViews()
        nodeRows.clear()
        config.remotes.forEach { addNodeRow(it) }
        if (nodeRows.isEmpty()) addNodeRow(AndroidVpnConfig.defaultConfig().remotes.first())

        defaultRouteSpinner.setSelection(routeActions.indexOf(config.routing.defaultAction).coerceAtLeast(0))
        cnDirectCheckBox.isChecked = config.routing.cnDirect
        directListInput.setText(
            config.routing.rules
                .filter { it.type == "domain_pattern" && it.action == "direct" }
                .joinToString("\n") { it.value },
        )
        proxyListInput.setText(
            config.routing.rules
                .filter { it.type == "domain_pattern" && it.action == "proxy" }
                .joinToString("\n") { it.value },
        )
        advancedRulesInput.setText(
            config.routing.rules
                .filterNot { it.type == "domain_pattern" && (it.action == "direct" || it.action == "proxy") }
                .joinToString("\n") { "${it.type},${it.value},${it.action}" },
        )
    }

    private fun addNodeRow(node: RemoteNodeConfig) {
        val row = NodeRow.create(this, node) {
            if (nodeRows.size <= 1) {
                Toast.makeText(this, R.string.node_keep_one, Toast.LENGTH_SHORT).show()
            } else {
                nodesContainer.removeView(it.root)
                nodeRows.remove(it)
            }
        }
        nodeRows += row
        nodesContainer.addView(row.root)
    }

    private fun saveCurrentConfig(showSavedToast: Boolean): AndroidVpnConfig? {
        val config = readConfigFromForm() ?: return null
        config.validate()?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return null
        }
        VpnConfigStore.save(this, config)
        if (showSavedToast) Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        return config
    }

    private fun readConfigFromForm(): AndroidVpnConfig? {
        val remotes = nodeRows.mapIndexed { index, row ->
            row.clearErrors()
            val port = row.portInput.text.toString().trim().toIntOrNull() ?: 0
            if (row.enabledCheckBox.isChecked) {
                if (row.hostInput.text.toString().trim().isBlank()) {
                    row.hostInput.error = getString(R.string.config_error_host)
                    row.hostInput.requestFocus()
                    return null
                }
                if (port !in 1..65535) {
                    row.portInput.error = getString(R.string.config_error_port)
                    row.portInput.requestFocus()
                    return null
                }
                if (row.keyInput.text.toString().isBlank()) {
                    row.keyInput.error = getString(R.string.config_error_key)
                    row.keyInput.requestFocus()
                    return null
                }
            }
            RemoteNodeConfig(
                name = row.nameInput.text.toString().trim().ifBlank { "节点 ${index + 1}" },
                host = row.hostInput.text.toString().trim(),
                port = port,
                key = row.keyInput.text.toString(),
                cipher = row.cipherInput.text.toString().trim().ifBlank { VpnConfigStore.DEFAULT_CIPHER },
                tls = row.tlsCheckBox.isChecked,
                enabled = row.enabledCheckBox.isChecked,
            )
        }

        val rules = mutableListOf<RouteRuleConfig>()
        rules += readDomainPatternRules(directListInput, "direct")
        rules += readDomainPatternRules(proxyListInput, "proxy")
        rules += readAdvancedRules() ?: return null

        return AndroidVpnConfig(
            remotes = remotes,
            routing = AndroidRoutingConfig(
                defaultAction = routeActions[defaultRouteSpinner.selectedItemPosition],
                cnDirect = cnDirectCheckBox.isChecked,
                rules = rules,
            ),
        )
    }

    private fun readDomainPatternRules(input: EditText, action: String): List<RouteRuleConfig> =
        input.text.lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() }
            .map { RouteRuleConfig("domain_pattern", it, action) }
            .toList()

    private fun readAdvancedRules(): List<RouteRuleConfig>? {
        val rules = mutableListOf<RouteRuleConfig>()
        advancedRulesInput.text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore("#").trim()
            if (line.isBlank()) return@forEachIndexed
            val parts = if (line.contains(",")) {
                line.split(",").map { it.trim() }
            } else {
                line.split(Regex("\\s+")).map { it.trim() }
            }
            if (parts.size < 3) {
                advancedRulesInput.error = "第 ${index + 1} 行格式应为 type,value,action"
                advancedRulesInput.requestFocus()
                return null
            }
            rules += RouteRuleConfig(parts[0], parts[1], parts[2])
        }
        advancedRulesInput.error = null
        return rules
    }

    private fun refreshPresets() {
        presets = VpnConfigStore.loadPresets(this)
        val names = presets.map { it.name }.ifEmpty { listOf(getString(R.string.no_presets)) }
        presetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun savePreset() {
        val config = saveCurrentConfig(showSavedToast = false) ?: return
        val name = presetNameInput.text.toString().trim()
        if (name.isBlank()) {
            presetNameInput.error = getString(R.string.preset_name_required)
            presetNameInput.requestFocus()
            return
        }
        VpnConfigStore.savePreset(this, name, config)
        refreshPresets()
        Toast.makeText(this, R.string.preset_saved, Toast.LENGTH_SHORT).show()
    }

    private fun loadSelectedPreset() {
        val preset = selectedPreset() ?: return
        bindConfigToForm(preset.config)
        VpnConfigStore.save(this, preset.config)
        Toast.makeText(this, R.string.preset_loaded, Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedPreset() {
        val preset = selectedPreset() ?: return
        VpnConfigStore.deletePreset(this, preset.name)
        refreshPresets()
        Toast.makeText(this, R.string.preset_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun selectedPreset(): ConfigPreset? {
        if (presets.isEmpty()) {
            Toast.makeText(this, R.string.no_presets, Toast.LENGTH_SHORT).show()
            return null
        }
        return presets.getOrNull(presetSpinner.selectedItemPosition)
    }

    private fun importConfig() {
        val imported = runCatching { AndroidVpnConfig.fromImportText(importConfigInput.text.toString()) }
            .onFailure { Toast.makeText(this, "导入失败: ${it.message}", Toast.LENGTH_LONG).show() }
            .getOrNull() ?: return
        imported.validate()?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        bindConfigToForm(imported)
        VpnConfigStore.save(this, imported)
        Toast.makeText(this, R.string.config_imported, Toast.LENGTH_SHORT).show()
    }

    private fun exportConfig() {
        val config = saveCurrentConfig(showSavedToast = false) ?: return
        importConfigInput.setText(config.toDesktopYaml())
        Toast.makeText(this, R.string.config_exported, Toast.LENGTH_SHORT).show()
    }

    private fun updateVpnUi(running: Boolean, message: String? = null) {
        vpnRunning = running
        toggleVpnButton.text = getString(if (running) R.string.disconnect_vpn else R.string.connect_vpn)
        vpnStatusText.text = message ?: getString(if (running) R.string.vpn_status_connected else R.string.vpn_status_idle)
        setConfigInputsEnabled(!running)
    }

    private fun setConfigInputsEnabled(enabled: Boolean) {
        nodeRows.forEach { it.setEnabled(enabled) }
        listOf(
            addNodeButton,
            saveConfigButton,
            defaultRouteSpinner,
            cnDirectCheckBox,
            directListInput,
            proxyListInput,
            advancedRulesInput,
            presetNameInput,
            presetSpinner,
            importConfigInput,
            findViewById<Button>(R.id.savePresetButton),
            findViewById<Button>(R.id.loadPresetButton),
            findViewById<Button>(R.id.deletePresetButton),
            findViewById<Button>(R.id.importConfigButton),
            findViewById<Button>(R.id.exportConfigButton),
        ).forEach { it.isEnabled = enabled }
    }

    private fun updateStats(json: String) {
        val text = runCatching {
            val obj = JSONObject(json.ifBlank { "{}" })
            val up = formatBytes(obj.optLong("upload_bytes", 0))
            val down = formatBytes(obj.optLong("download_bytes", 0))
            val active = obj.optLong("active_connections", 0)
            val total = obj.optLong("total_connections", 0)
            val proxy = obj.optLong("proxy_connections", 0)
            val direct = obj.optLong("direct_connections", 0)
            val rejected = obj.optLong("rejected_connections", 0)
            val node = obj.optString("active_node", "-").ifBlank { "-" }
            "上行 $up / 下行 $down\n连接 活跃 $active / 总计 $total · 代理 $proxy · 直连 $direct · 拒绝 $rejected\n当前节点 $node"
        }.getOrElse {
            getString(R.string.stats_empty)
        }
        statsText.text = text
    }

    private fun updateLogView() {
        logText.text = AppLogStore.snapshot().takeLast(80).joinToString("\n")
    }

    private fun formatBytes(value: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit += 1
        }
        return if (unit == 0) "${value}B" else String.format(Locale.US, "%.1f%s", size, units[unit])
    }

    private class NodeRow private constructor(
        val root: LinearLayout,
        val nameInput: EditText,
        val hostInput: EditText,
        val portInput: EditText,
        val keyInput: EditText,
        val cipherInput: EditText,
        val tlsCheckBox: CheckBox,
        val enabledCheckBox: CheckBox,
        private val removeButton: Button,
    ) {
        fun clearErrors() {
            hostInput.error = null
            portInput.error = null
            keyInput.error = null
        }

        fun setEnabled(enabled: Boolean) {
            listOf(nameInput, hostInput, portInput, keyInput, cipherInput, tlsCheckBox, enabledCheckBox, removeButton)
                .forEach { it.isEnabled = enabled }
        }

        companion object {
            fun create(context: Context, node: RemoteNodeConfig, onRemove: (NodeRow) -> Unit): NodeRow {
                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 8.dp(context), 0, 18.dp(context))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                fun input(hint: String, text: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
                    EditText(context).apply {
                        this.hint = hint
                        setText(text)
                        inputType = type
                        isSingleLine = type != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                    }

                val name = input("名称", node.name)
                val host = input("地址，例如 1.2.3.4 或 example.com", node.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                val port = input("端口", node.port.toString(), InputType.TYPE_CLASS_NUMBER)
                val key = input("加密密钥", node.key, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                val cipher = input("加密方式", node.cipher)
                val tls = CheckBox(context).apply {
                    text = "TLS 出站"
                    isChecked = node.tls
                }
                val enabled = CheckBox(context).apply {
                    text = "启用节点"
                    isChecked = node.enabled
                }
                val remove = Button(context).apply { text = "删除节点" }
                root.addView(name)
                root.addView(host)
                root.addView(port)
                root.addView(key)
                root.addView(cipher)
                root.addView(tls)
                root.addView(enabled)
                root.addView(remove)
                lateinit var row: NodeRow
                row = NodeRow(root, name, host, port, key, cipher, tls, enabled, remove)
                remove.setOnClickListener { onRemove(row) }
                return row
            }

            private fun Int.dp(context: Context): Int =
                (this * context.resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        private const val UI_REFRESH_MS = 1000L
    }
}
