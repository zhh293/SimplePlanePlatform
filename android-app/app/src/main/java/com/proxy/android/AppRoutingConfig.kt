package com.proxy.android

import org.json.JSONArray
import org.json.JSONObject

/** Android selects which applications enter the VPN; core routing still applies within it. */
data class AppRoutingConfig(val mode: String = ALL, val packages: List<String> = emptyList()) {
    fun validate(): String? = when {
        mode !in MODES -> "应用分流模式无效"
        packages.any { it != "android" && (it.length > 255 || !PACKAGE_NAME.matches(it)) } -> "应用分流包含无效包名"
        mode == INCLUDE && packages.isEmpty() -> "请至少选择一个进入 VPN 的应用"
        else -> null
    }

    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("packages", JSONArray(packages))

    /** The builder callbacks return false when Android cannot resolve a package. */
    internal fun applyToVpn(ownPackage: String, allow: (String) -> Boolean, exclude: (String) -> Boolean): Int {
        validate()?.let { throw IllegalArgumentException(it) }
        if (mode != INCLUDE) check(exclude(ownPackage)) { "无法排除 VPN 服务自身" }
        if (mode == ALL) return 0

        val selected = packages.distinct().filter { it != ownPackage }
        val accepted = selected.count(if (mode == INCLUDE) allow else exclude)
        // An empty Android allowlist would send all applications through the VPN.
        if (mode == INCLUDE && accepted == 0) throw NoAvailableVpnApps()
        return selected.size - accepted
    }

    companion object {
        const val ALL = "all"
        const val INCLUDE = "include"
        const val EXCLUDE = "exclude"
        val MODES = listOf(ALL, INCLUDE, EXCLUDE)
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

        fun fromJson(root: JSONObject): AppRoutingConfig {
            if (!root.has("app_routing")) return AppRoutingConfig()
            val policy = root.getJSONObject("app_routing")
            val packages = policy.getJSONArray("packages")
            return AppRoutingConfig(policy.getString("mode"), (0 until packages.length()).map {
                require(packages.get(it) is String) { "应用包名必须是文本" }
                packages.getString(it)
            }.distinct())
        }
    }
}

internal class NoAvailableVpnApps : IllegalArgumentException("所选应用均不可用")
