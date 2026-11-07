package com.proxy.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AdapterView
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var navigation: BottomNavigationView
    private lateinit var nodeSelectionSpinner: Spinner
    private var selectedPage = R.id.nav_connection
    private var selectedNodeIndex = -1
    private var formMtu = VpnConfigStore.DEFAULT_MTU
    private var originalRules: List<RouteRuleConfig> = emptyList()
    private var pendingExportJson: String? = null
    private var transferInProgress = false

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

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_LONG).show()
        updateNotificationButton()
    }

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readConfigFile(uri)
    }

    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingExportJson
        pendingExportJson = null
        if (uri != null && text != null) writeConfigFile(uri, text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_main)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            resources.getBoolean(R.bool.light_system_bars)
        listOf(R.id.connectionPageTitle, R.id.nodesPageTitle, R.id.routingPageTitle, R.id.settingsPageTitle).forEach {
            ViewCompat.setAccessibilityHeading(findViewById(it), true)
        }

        mainContent = findViewById(R.id.mainContent)
        findViewById<TextView>(R.id.nativeVersionText).text =
            getString(R.string.version_label, packageManager.getPackageInfo(packageName, 0).versionName, readNativeVersion())
        vpnStatusText = findViewById(R.id.vpnStatusText)
        statsText = findViewById(R.id.statsText)
        nodeSelectionSpinner = findViewById<Spinner>(R.id.nodeSelectionSpinner).apply {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedNodeIndex = position - 1
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        nodesContainer = findViewById(R.id.nodesContainer)
        addNodeButton = findViewById<Button>(R.id.addNodeButton).apply {
            setOnClickListener { addNodeRow(RemoteNodeConfig(name = "节点 ${nodeRows.size + 1}", host = "", port = 9090, key = "")) }
        }
        defaultRouteSpinner = findViewById(R.id.defaultRouteSpinner)
        defaultRouteSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("代理", "直连", "拒绝"))
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
        findViewById<Button>(R.id.importFileButton).setOnClickListener {
            importFileLauncher.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.exportFileButton).setOnClickListener { exportConfigFile() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            AppLogStore.clear()
            updateLogView()
        }
        navigation = findViewById<BottomNavigationView>(R.id.bottomNavigation).apply {
            setOnItemSelectedListener { showPage(it.itemId); true }
        }
        findViewById<Button>(R.id.notificationPermissionButton).setOnClickListener { configureNotifications() }

        refreshPresets()
        bindConfigToForm(VpnConfigStore.load(this))
        savedInstanceState?.getBundle(FORM_DRAFT)?.let(::restoreDraft)
        pendingExportJson = savedInstanceState?.getString(PENDING_EXPORT)
        navigation.selectedItemId = savedInstanceState?.getInt(SELECTED_PAGE, R.id.nav_connection) ?: R.id.nav_connection
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
        updateNotificationButton()
        uiHandler.post(uiTicker)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        uiHandler.removeCallbacks(uiTicker)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(FORM_DRAFT, Bundle().apply {
            putParcelableArrayList("nodes", ArrayList(nodeRows.map { it.saveDraft() }))
            putInt("defaultRoute", defaultRouteSpinner.selectedItemPosition)
            putBoolean("cnDirect", cnDirectCheckBox.isChecked)
            putInt("preset", presetSpinner.selectedItemPosition)
            putInt("selectedNode", selectedNodeIndex)
            putInt("mtu", formMtu)
            putString("ruleOrder", JSONArray().apply {
                originalRules.forEach { put(JSONObject().put("type", it.type).put("value", it.value).put("action", it.action)) }
            }.toString())
            draftInputs().forEach { (key, input) -> putString(key, input.text.toString()) }
        })
        outState.putInt(SELECTED_PAGE, selectedPage)
        outState.putString(PENDING_EXPORT, pendingExportJson)
        super.onSaveInstanceState(outState)
    }

    private fun draftInputs() = mapOf(
        "direct" to directListInput, "proxy" to proxyListInput, "rules" to advancedRulesInput,
        "presetName" to presetNameInput, "import" to importConfigInput,
    )

    private fun restoreDraft(draft: Bundle) {
        @Suppress("DEPRECATION")
        val nodes = draft.getParcelableArrayList<Bundle>("nodes")
        if (!nodes.isNullOrEmpty()) {
            nodesContainer.removeAllViews()
            nodeRows.clear()
            nodes.forEach {
                addNodeRow(RemoteNodeConfig(name = "", host = "", port = 9090, key = ""))
                nodeRows.last().restoreDraft(it)
            }
        }
        defaultRouteSpinner.setSelection(draft.getInt("defaultRoute"))
        cnDirectCheckBox.isChecked = draft.getBoolean("cnDirect")
        presetSpinner.setSelection(draft.getInt("preset"))
        selectedNodeIndex = draft.getInt("selectedNode", -1)
        formMtu = draft.getInt("mtu", VpnConfigStore.DEFAULT_MTU)
        draft.getString("ruleOrder")?.let { json ->
            val rules = JSONArray(json)
            originalRules = (0 until rules.length()).map {
                val rule = rules.getJSONObject(it)
                RouteRuleConfig(rule.getString("type"), rule.getString("value"), rule.getString("action"))
            }
        }
        draftInputs().forEach { (key, input) -> input.setText(draft.getString(key, "")) }
        refreshNodeChoices()
    }

    private fun showPage(page: Int) {
        selectedPage = page
        mapOf(R.id.nav_connection to R.id.connectionPage, R.id.nav_nodes to R.id.nodesPage,
            R.id.nav_routing to R.id.routingPage, R.id.nav_settings to R.id.settingsPage).forEach { (item, view) ->
            findViewById<View>(view).visibility = if (item == page) View.VISIBLE else View.GONE
        }
        saveConfigButton.visibility = if (page == R.id.nav_nodes || page == R.id.nav_routing) View.VISIBLE else View.GONE
        findViewById<View>(R.id.configLockedText).visibility = if (vpnRunning && page != R.id.nav_connection) View.VISIBLE else View.GONE
        if (page == R.id.nav_connection) refreshNodeChoices()
    }

    private fun refreshNodeChoices() {
        val names = listOf(getString(R.string.node_selection_auto)) + nodeRows.mapIndexed { index, row ->
            val name = row.nameInput.text.toString().ifBlank { "节点 ${index + 1}" }
            if (row.enabledCheckBox.isChecked) "${index + 1}. $name" else "${index + 1}. $name（已停用）"
        }
        nodeSelectionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        nodeSelectionSpinner.setSelection(selectedNodeIndex + 1)
    }

    private fun configureNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificationRequested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationRequested", true).apply()
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        }
    }

    private fun updateNotificationButton() {
        findViewById<Button>(R.id.notificationPermissionButton).setText(
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) R.string.notification_settings else R.string.notification_enable,
        )
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
        selectedNodeIndex = config.selectedNodeIndex
        formMtu = config.mtu
        originalRules = config.routing.rules
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
        refreshNodeChoices()
    }

    private fun addNodeRow(node: RemoteNodeConfig) {
        val row = NodeRow.create(this, node) {
            if (nodeRows.size <= 1) {
                Toast.makeText(this, R.string.node_keep_one, Toast.LENGTH_SHORT).show()
            } else {
                val removed = nodeRows.indexOf(it)
                nodesContainer.removeView(it.root)
                nodeRows.remove(it)
                if (selectedNodeIndex == removed) {
                    selectedNodeIndex = -1
                    Toast.makeText(this, R.string.selected_node_removed, Toast.LENGTH_SHORT).show()
                } else if (selectedNodeIndex > removed) selectedNodeIndex -= 1
                refreshNodeChoices()
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
        originalRules = config.routing.rules
        if (showSavedToast) Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        return config
    }

    private fun readConfigFromForm(): AndroidVpnConfig? {
        val remotes = nodeRows.mapIndexed { index, row ->
            row.clearErrors()
            val port = row.portInput.text.toString().trim().toIntOrNull() ?: 0
            if (row.enabledCheckBox.isChecked) {
                if (row.hostInput.text.toString().trim().isBlank()) {
                    navigation.selectedItemId = R.id.nav_nodes
                    row.hostInput.error = getString(R.string.config_error_host)
                    row.hostInput.requestFocus()
                    return null
                }
                if (port !in 1..65535) {
                    navigation.selectedItemId = R.id.nav_nodes
                    row.portInput.error = getString(R.string.config_error_port)
                    row.portInput.requestFocus()
                    return null
                }
                if (row.keyInput.text.toString().isBlank()) {
                    navigation.selectedItemId = R.id.nav_nodes
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
                transport = if (row.transportSpinner.selectedItemPosition == 1) "http3" else "http2",
                serverName = row.serverNameInput.text.toString().trim(),
                caPem = row.caPem,
            )
        }

        val rules = mutableListOf<RouteRuleConfig>()
        rules += readDomainPatternRules(directListInput, "direct")
        rules += readDomainPatternRules(proxyListInput, "proxy")
        rules += readAdvancedRules() ?: return null

        return AndroidVpnConfig(
            mtu = formMtu,
            remotes = remotes,
            selectedNodeIndex = selectedNodeIndex,
            routing = AndroidRoutingConfig(
                defaultAction = routeActions[defaultRouteSpinner.selectedItemPosition],
                cnDirect = cnDirectCheckBox.isChecked,
                // Splitting fields into categories must not reorder an imported
                // first-match ruleset when the user has not edited its rules.
                rules = if (rules == groupedRules(originalRules)) originalRules else rules,
            ),
        )
    }

    private fun groupedRules(rules: List<RouteRuleConfig>): List<RouteRuleConfig> =
        rules.filter { it.type == "domain_pattern" && it.action == "direct" } +
            rules.filter { it.type == "domain_pattern" && it.action == "proxy" } +
            rules.filterNot { it.type == "domain_pattern" && it.action in listOf("direct", "proxy") }

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
                navigation.selectedItemId = R.id.nav_routing
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
        previewImport(imported)
    }

    private fun exportConfig() {
        val config = saveCurrentConfig(showSavedToast = false) ?: return
        importConfigInput.setText(config.toJsonString(2))
        Toast.makeText(this, R.string.config_exported, Toast.LENGTH_SHORT).show()
    }

    private fun previewImport(imported: AndroidVpnConfig) {
        imported.validate()?.let { throw IllegalArgumentException(it) }
        val choice = imported.remotes.getOrNull(imported.selectedNodeIndex)?.name ?: getString(R.string.node_selection_auto)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_preview_title)
            .setMessage(getString(R.string.import_preview, imported.remotes.size,
                imported.remotes.count { it.enabled }, imported.routing.rules.size, choice))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_apply) { _, _ ->
                if (PlaneVpnService.isRunning) {
                    Toast.makeText(this, R.string.config_locked, Toast.LENGTH_LONG).show()
                } else {
                    bindConfigToForm(imported)
                    VpnConfigStore.save(this, imported)
                    Toast.makeText(this, R.string.config_imported, Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun readConfigFile(uri: Uri) = transfer {
        val imported = withContext(Dispatchers.IO) {
            val text = contentResolver.openInputStream(uri)?.use(ConfigFiles::read)
                ?: error(getString(R.string.file_unavailable))
            AndroidVpnConfig.fromImportText(text)
        }
        previewImport(imported)
    }

    private fun exportConfigFile() {
        val config = saveCurrentConfig(showSavedToast = false) ?: return
        pendingExportJson = runCatching { ConfigFiles.backup(config) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            .getOrNull() ?: return
        exportFileLauncher.launch("SimplePlane-backup.json")
    }

    private fun writeConfigFile(uri: Uri, text: String) = transfer {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri, "wt")?.use { ConfigFiles.write(it, text) }
                ?: error(getString(R.string.file_unavailable))
        }
        Toast.makeText(this, R.string.file_exported, Toast.LENGTH_SHORT).show()
    }

    private fun transfer(action: suspend () -> Unit) {
        if (transferInProgress) return
        transferInProgress = true
        updateVpnUi(PlaneVpnService.isRunning)
        lifecycleScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.file_error, error.message.orEmpty()), Toast.LENGTH_LONG).show()
            } finally {
                transferInProgress = false
                if (!isDestroyed) updateVpnUi(PlaneVpnService.isRunning)
            }
        }
    }

    private fun updateVpnUi(running: Boolean, message: String? = null) {
        vpnRunning = running
        toggleVpnButton.text = getString(if (running) R.string.disconnect_vpn else R.string.connect_vpn)
        val status = message ?: PlaneVpnService.latestStatusMessage
        val failed = status.startsWith("VPN 已启动；代理请求失败") || status.startsWith("连接失败") || status == getString(R.string.vpn_status_error)
        vpnStatusText.text = if (failed) getString(R.string.vpn_status_error) else status
        findViewById<TextView>(R.id.vpnDetailText).setText(when {
            failed -> R.string.failure_detail
            status == getString(R.string.vpn_status_no_network) -> R.string.offline_detail
            status == getString(R.string.vpn_status_recovering) -> R.string.recovery_detail
            status == getString(R.string.vpn_status_connected) -> R.string.connected_detail
            running -> R.string.ready_detail
            else -> R.string.idle_detail
        })
        toggleVpnButton.isEnabled = !transferInProgress
        findViewById<View>(R.id.fileTransferProgress).visibility = if (transferInProgress) View.VISIBLE else View.GONE
        setConfigInputsEnabled(!running && !transferInProgress)
        showPage(selectedPage)
    }

    private fun setConfigInputsEnabled(enabled: Boolean) {
        nodeRows.forEach { it.setEnabled(enabled) }
        listOf(
            addNodeButton,
            nodeSelectionSpinner,
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
            findViewById<Button>(R.id.importFileButton),
            findViewById<Button>(R.id.exportFileButton),
        ).forEach { view ->
            view.isEnabled = enabled
            ((view.parent as? View)?.parent as? TextInputLayout)?.isEnabled = enabled
        }
    }

    private fun updateStats(json: String) {
        val text = runCatching {
            val obj = JSONObject(json.ifBlank { "{}" })
            val up = formatBytes(obj.optLong("upload_bytes", 0))
            val down = formatBytes(obj.optLong("download_bytes", 0))
            findViewById<TextView>(R.id.uploadTotalText).text = up
            findViewById<TextView>(R.id.downloadTotalText).text = down
            val active = obj.optLong("active_connections", 0)
            val total = obj.optLong("total_connections", 0)
            val proxy = obj.optLong("proxy_connections", 0)
            val direct = obj.optLong("direct_connections", 0)
            val rejected = obj.optLong("rejected_connections", 0)
            val node = obj.optString("active_node", "-").ifBlank { "-" }
            "当前节点  $node\n活跃连接 $active · 累计 $total\n代理 $proxy · 直连 $direct · 拒绝 $rejected"
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
        return if (unit == 0) "$value B" else String.format(Locale.US, "%.1f %s", size, units[unit])
    }

    companion object {
        private const val FORM_DRAFT = "formDraft"
        private const val SELECTED_PAGE = "selectedPage"
        private const val PENDING_EXPORT = "pendingExportJson"
        private const val UI_REFRESH_MS = 1000L
    }
}
