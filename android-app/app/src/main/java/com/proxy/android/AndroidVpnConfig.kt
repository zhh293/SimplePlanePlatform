package com.proxy.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RemoteNodeConfig(
    val name: String,
    val host: String,
    val port: Int,
    val key: String,
    val cipher: String = VpnConfigStore.DEFAULT_CIPHER,
    val tls: Boolean = false,
    val enabled: Boolean = true,
    val transport: String = "http2",
    val serverName: String = "",
    val caPem: String = "",
)

data class RouteRuleConfig(
    val type: String,
    val value: String,
    val action: String,
)

data class AndroidRoutingConfig(
    val defaultAction: String = "proxy",
    val cnDirect: Boolean = true,
    val rules: List<RouteRuleConfig> = emptyList(),
)

data class AndroidVpnConfig(
    val mtu: Int = VpnConfigStore.DEFAULT_MTU,
    val remotes: List<RemoteNodeConfig>,
    val routing: AndroidRoutingConfig = AndroidRoutingConfig(),
) {
    fun validate(): String? {
        val enabled = remotes.filter { it.enabled }
        if (enabled.isEmpty()) return "至少需要一个已启用节点"
        enabled.forEachIndexed { index, node ->
            if (node.host.isBlank()) return "节点 ${index + 1} 缺少地址"
            if (node.port !in 1..65535) return "节点 ${index + 1} 端口必须在 1-65535"
            if (node.key.isBlank()) return "节点 ${index + 1} 缺少加密密钥"
            if (node.transport !in setOf("http2", "http3")) return "节点 ${index + 1} 协议必须是 HTTP/2 或 HTTP/3"
            val cipher = node.cipher.lowercase(Locale.ROOT)
            if (cipher !in SUPPORTED_CIPHERS) {
                return "节点 ${index + 1} 加密方式不支持: ${node.cipher}"
            }
            if (cipher.endsWith("-v2") && node.key.toByteArray(Charsets.UTF_8).size != 32) {
                return "节点 ${index + 1} 的 v2 加密密钥必须是 32 字节"
            }
        }
        if (routing.defaultAction !in setOf("proxy", "direct", "reject")) {
            return "默认路由只能是 proxy/direct/reject"
        }
        routing.rules.forEachIndexed { index, rule ->
            if (rule.type !in SUPPORTED_RULE_TYPES) return "规则 ${index + 1} 类型不支持: ${rule.type}"
            if (rule.action !in setOf("proxy", "direct", "reject")) return "规则 ${index + 1} 动作不支持: ${rule.action}"
            if (rule.value.isBlank()) return "规则 ${index + 1} 内容为空"
        }
        return null
    }

    fun toJsonString(indent: Int = 0): String {
        val root = JSONObject()
            .put("mtu", mtu)
            .put("remotes", JSONArray().apply {
                remotes.forEach { node ->
                    put(JSONObject()
                        .put("name", node.name)
                        .put("host", node.host)
                        .put("port", node.port)
                        .put("key", node.key)
                        .put("cipher", node.cipher)
                        .put("tls", node.tls)
                        .put("enabled", node.enabled)
                        .put("transport", node.transport)
                        .put("server_name", node.serverName)
                        .put("ca_pem", node.caPem))
                }
            })
            .put("routing", JSONObject()
                .put("default_action", routing.defaultAction)
                .put("cn_direct", routing.cnDirect)
                .put("rules", JSONArray().apply {
                    routing.rules.forEach { rule ->
                        put(JSONObject()
                            .put("type", rule.type)
                            .put("value", rule.value)
                            .put("action", rule.action))
                    }
                }))
        return if (indent > 0) root.toString(indent) else root.toString()
    }

    fun toDesktopYaml(): String {
        val builder = StringBuilder()
        builder.appendLine("remoteServers:")
        remotes.forEach { node ->
            builder.appendLine("  - host: ${node.host}")
            builder.appendLine("    port: ${node.port}")
            builder.appendLine("    cipher: ${node.cipher}")
            builder.appendLine("    cipherKey: \"${node.key.replace("\"", "\\\"")}\"")
            builder.appendLine("    ssl: ${node.tls}")
            builder.appendLine("    transport: ${node.transport}")
            if (node.serverName.isNotBlank()) builder.appendLine("    serverName: ${JSONObject.quote(node.serverName)}")
            if (node.caPem.isNotBlank()) builder.appendLine("    caPem: ${JSONObject.quote(node.caPem)}")
        }
        builder.appendLine("route:")
        builder.appendLine("  defaultRoute: ${routing.defaultAction}")
        val proxyList = routing.rules.filter { it.type == "domain_pattern" && it.action == "proxy" }
        val directList = routing.rules.filter { it.type == "domain_pattern" && it.action == "direct" }
        if (proxyList.isNotEmpty()) {
            builder.appendLine("  proxyList:")
            proxyList.forEach { builder.appendLine("    - \"${it.value}\"") }
        }
        if (directList.isNotEmpty()) {
            builder.appendLine("  directList:")
            directList.forEach { builder.appendLine("    - \"${it.value}\"") }
        }
        return builder.toString()
    }

    companion object {
        private val SUPPORTED_CIPHERS = setOf("chacha20", "chacha20-poly1305-v2", "aes-gcm-v2")
        private val SUPPORTED_RULE_TYPES = setOf(
            "domain_pattern",
            "domain_suffix",
            "domain_keyword",
            "domain_full",
            "ip_cidr",
            "port",
        )

        fun defaultConfig(): AndroidVpnConfig = AndroidVpnConfig(
            remotes = listOf(
                RemoteNodeConfig(
                    name = "默认节点",
                    host = VpnConfigStore.DEFAULT_REMOTE_HOST,
                    port = VpnConfigStore.DEFAULT_REMOTE_PORT,
                    key = VpnConfigStore.DEFAULT_REMOTE_KEY,
                    transport = "http3",
                    tls = true,
                ),
            ),
            routing = AndroidRoutingConfig(
                defaultAction = "proxy",
                rules = listOf(
                    RouteRuleConfig("domain_pattern", "*.cn", "direct"),
                    RouteRuleConfig("domain_pattern", "baidu.com", "direct"),
                    RouteRuleConfig("domain_pattern", "qq.com", "direct"),
                    RouteRuleConfig("ip_cidr", "10.0.0.0/8", "direct"),
                    RouteRuleConfig("ip_cidr", "172.16.0.0/12", "direct"),
                    RouteRuleConfig("ip_cidr", "192.168.0.0/16", "direct"),
                ),
            ),
        )

        fun fromJson(text: String): AndroidVpnConfig {
            val root = JSONObject(text)
            val remotes = parseRemotes(root)
            val routing = parseRouting(root)
            return AndroidVpnConfig(
                mtu = root.optInt("mtu", VpnConfigStore.DEFAULT_MTU),
                remotes = remotes.ifEmpty {
                    listOf(RemoteNodeConfig(
                        name = "默认节点",
                        host = root.optString("remote_host", VpnConfigStore.DEFAULT_REMOTE_HOST),
                        port = root.optInt("remote_port", VpnConfigStore.DEFAULT_REMOTE_PORT),
                        key = root.optString("remote_key", VpnConfigStore.DEFAULT_REMOTE_KEY),
                        cipher = root.optString("cipher", VpnConfigStore.DEFAULT_CIPHER),
                        tls = root.optBoolean("tls", false),
                        transport = normalizeTransport(root.optString("transport", "http2")),
                        serverName = root.optString("server_name", root.optString("serverName")),
                        caPem = root.optString("ca_pem", root.optString("caPem")),
                    ))
                },
                routing = routing,
            )
        }

        fun fromImportText(text: String): AndroidVpnConfig {
            val trimmed = text.trim()
            if (trimmed.isBlank()) error("导入内容为空")
            return if (trimmed.startsWith("{")) {
                fromJson(trimmed)
            } else {
                parseDesktopYaml(trimmed)
            }
        }

        private fun parseRemotes(root: JSONObject): List<RemoteNodeConfig> {
            val array = root.optJSONArray("remotes") ?: root.optJSONArray("remoteServers") ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(RemoteNodeConfig(
                        name = item.optString("name", "节点 ${i + 1}"),
                        host = item.optString("host"),
                        port = item.optInt("port", VpnConfigStore.DEFAULT_REMOTE_PORT),
                        key = item.optString("key", item.optString("cipherKey", item.optString("password"))),
                        cipher = item.optString("cipher", VpnConfigStore.DEFAULT_CIPHER),
                        tls = item.optBoolean("tls", item.optBoolean("ssl", false)),
                        enabled = item.optBoolean("enabled", true),
                        transport = normalizeTransport(item.optString("transport", "http2")),
                        serverName = item.optString("server_name", item.optString("serverName")),
                        caPem = item.optString("ca_pem", item.optString("caPem")),
                    ))
                }
            }
        }

        private fun parseRouting(root: JSONObject): AndroidRoutingConfig {
            val routing = root.optJSONObject("routing")
            if (routing != null) {
                val rulesArray = routing.optJSONArray("rules") ?: JSONArray()
                return AndroidRoutingConfig(
                    defaultAction = routing.optString("default_action", routing.optString("defaultAction", "proxy")),
                    cnDirect = routing.optBoolean("cn_direct", routing.optBoolean("cnDirect", true)),
                    rules = parseRulesArray(rulesArray),
                )
            }

            val route = root.optJSONObject("route") ?: return AndroidRoutingConfig()
            val rules = mutableListOf<RouteRuleConfig>()
            val direct = route.optJSONArray("directList") ?: route.optJSONArray("direct_list")
            val proxy = route.optJSONArray("proxyList") ?: route.optJSONArray("proxy_list")
            addPatternRules(direct, "direct", rules)
            addPatternRules(proxy, "proxy", rules)
            return AndroidRoutingConfig(
                defaultAction = route.optString("defaultRoute", route.optString("default_route", "proxy")),
                cnDirect = true,
                rules = rules,
            )
        }

        private fun parseRulesArray(array: JSONArray): List<RouteRuleConfig> = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(RouteRuleConfig(
                    type = item.optString("type", item.optString("rule_type")),
                    value = item.optString("value"),
                    action = item.optString("action"),
                ))
            }
        }

        private fun addPatternRules(array: JSONArray?, action: String, out: MutableList<RouteRuleConfig>) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) out += RouteRuleConfig("domain_pattern", value, action)
            }
        }

        private fun parseDesktopYaml(text: String): AndroidVpnConfig {
            val nodes = mutableListOf<MutableMap<String, String>>()
            var currentNode: MutableMap<String, String>? = null
            var inRoute = false
            var listMode: String? = null
            var defaultRoute = "proxy"
            val proxyList = mutableListOf<String>()
            val directList = mutableListOf<String>()

            text.lineSequence().forEach { raw ->
                val line = raw.substringBefore("#").trimEnd()
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@forEach

                when {
                    trimmed == "remoteServers:" || trimmed == "remotes:" -> {
                        inRoute = false
                        listMode = null
                    }
                    trimmed == "route:" || trimmed == "routing:" -> {
                        inRoute = true
                        listMode = null
                    }
                    trimmed.startsWith("- ") && !inRoute -> {
                        currentNode = mutableMapOf()
                        nodes += currentNode!!
                        parseKeyValue(trimmed.removePrefix("- "))?.let { (k, v) -> currentNode!![k] = v }
                    }
                    currentNode != null && !inRoute && trimmed.contains(":") -> {
                        parseKeyValue(trimmed)?.let { (k, v) -> currentNode!![k] = v }
                    }
                    inRoute && trimmed.startsWith("defaultRoute:") -> {
                        defaultRoute = cleanValue(trimmed.substringAfter(":"))
                    }
                    inRoute && trimmed.startsWith("default_action:") -> {
                        defaultRoute = cleanValue(trimmed.substringAfter(":"))
                    }
                    inRoute && (trimmed == "proxyList:" || trimmed == "proxy_list:") -> listMode = "proxy"
                    inRoute && (trimmed == "directList:" || trimmed == "direct_list:") -> listMode = "direct"
                    inRoute && trimmed.startsWith("- ") && listMode == "proxy" -> proxyList += cleanValue(trimmed.removePrefix("- "))
                    inRoute && trimmed.startsWith("- ") && listMode == "direct" -> directList += cleanValue(trimmed.removePrefix("- "))
                }
            }

            val remotes = nodes.mapIndexed { index, map ->
                RemoteNodeConfig(
                    name = map["name"].orEmpty().ifBlank { "节点 ${index + 1}" },
                    host = map["host"].orEmpty(),
                    port = map["port"]?.toIntOrNull() ?: VpnConfigStore.DEFAULT_REMOTE_PORT,
                    key = map["key"] ?: map["cipherKey"] ?: map["password"] ?: "",
                    cipher = map["cipher"] ?: VpnConfigStore.DEFAULT_CIPHER,
                    tls = (map["tls"] ?: map["ssl"]).equals("true", ignoreCase = true),
                    enabled = !map["enabled"].equals("false", ignoreCase = true),
                    transport = normalizeTransport(map["transport"] ?: "http2"),
                    serverName = map["server_name"] ?: map["serverName"] ?: "",
                    caPem = map["ca_pem"] ?: map["caPem"] ?: "",
                )
            }
            val rules = directList.map { RouteRuleConfig("domain_pattern", it, "direct") } +
                proxyList.map { RouteRuleConfig("domain_pattern", it, "proxy") }

            return AndroidVpnConfig(
                remotes = remotes.ifEmpty { defaultConfig().remotes },
                routing = AndroidRoutingConfig(
                    defaultAction = defaultRoute,
                    cnDirect = true,
                    rules = rules,
                ),
            )
        }

        private fun parseKeyValue(text: String): Pair<String, String>? {
            if (!text.contains(":")) return null
            val key = text.substringBefore(":").trim()
            val value = cleanValue(text.substringAfter(":"))
            return key to value
        }

        private fun cleanValue(value: String): String {
            val trimmed = value.trim()
            if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
                return runCatching { JSONArray("[$trimmed]").getString(0) }.getOrDefault(trimmed.trim('"'))
            }
            return trimmed.trim('\'')
        }

        private fun normalizeTransport(value: String): String = when (val lower = value.lowercase(Locale.ROOT)) {
            "h2" -> "http2"
            "h3", "quic" -> "http3"
            else -> lower
        }
    }
}

data class ConfigPreset(
    val name: String,
    val config: AndroidVpnConfig,
)

object VpnConfigStore {
    const val DEFAULT_MTU = 1500
    const val DEFAULT_REMOTE_HOST = "54.172.101.190"
    const val DEFAULT_REMOTE_PORT = 9090
    const val DEFAULT_REMOTE_KEY = "your-cipher-key"
    const val DEFAULT_CIPHER = "chacha20"

    private const val PREFS_NAME = "plane_vpn_config"
    private const val KEY_CONFIG_JSON = "config_json_v2"
    private const val KEY_PRESETS = "presets_json"

    fun load(context: Context): AndroidVpnConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIG_JSON, null)
        if (!json.isNullOrBlank()) {
            val saved = runCatching { AndroidVpnConfig.fromJson(json) }.getOrElse { AndroidVpnConfig.defaultConfig() }
            val migrated = migrateLegacyDefault(saved)
            if (migrated != saved) {
                save(context, migrated)
                AppLogStore.add("旧默认节点已更新为 $DEFAULT_REMOTE_HOST:$DEFAULT_REMOTE_PORT HTTP/3")
            }
            return migrated
        }
        return AndroidVpnConfig.defaultConfig()
    }

    // Only migrate the untouched node shipped in the July APK. User-configured
    // hosts, credentials, protocols and multi-node lists must stay intact.
    internal fun migrateLegacyDefault(config: AndroidVpnConfig): AndroidVpnConfig {
        val node = config.remotes.singleOrNull() ?: return config
        if (node.host != "54.234.196.30" || node.port != DEFAULT_REMOTE_PORT ||
            node.key != DEFAULT_REMOTE_KEY || node.cipher != DEFAULT_CIPHER ||
            node.transport != "http2" || node.tls || node.serverName.isNotBlank() || node.caPem.isNotBlank()
        ) return config
        return config.copy(remotes = listOf(node.copy(host = DEFAULT_REMOTE_HOST, transport = "http3", tls = true)))
    }

    fun save(context: Context, config: AndroidVpnConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG_JSON, config.toJsonString())
            .apply()
    }

    fun loadPresets(context: Context): List<ConfigPreset> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PRESETS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(ConfigPreset(
                        name = item.optString("name"),
                        config = AndroidVpnConfig.fromJson(item.getJSONObject("config").toString()),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun savePreset(context: Context, name: String, config: AndroidVpnConfig) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val next = loadPresets(context)
            .filterNot { it.name == trimmed }
            .plus(ConfigPreset(trimmed, config))
        writePresets(context, next)
    }

    fun deletePreset(context: Context, name: String) {
        writePresets(context, loadPresets(context).filterNot { it.name == name })
    }

    private fun writePresets(context: Context, presets: List<ConfigPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject()
                .put("name", preset.name)
                .put("config", JSONObject(preset.config.toJsonString())))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, array.toString())
            .apply()
    }
}

object AppLogStore {
    private const val MAX_LINES = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(message: String) {
        lines.addLast("${timeFormat.format(Date())}  $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
