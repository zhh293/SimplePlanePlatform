package com.proxy.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppRoutingConfigTest {
    private val own = "com.proxy.android"
    private val browser = "com.android.chrome"
    private val mail = "com.example.mail"

    @Test fun oldConfigurationsDefaultToAllAppsAndExcludeTheVpnItself() {
        val config = AndroidVpnConfig.defaultConfig()
        val json = JSONObject(config.toJsonString()).apply { remove("app_routing") }
        assertEquals(AppRoutingConfig(), AndroidVpnConfig.fromJson(json.toString()).appRouting)
        val excluded = mutableListOf<String>()
        assertEquals(0, AppRoutingConfig().applyToVpn(own, { error("Unexpected allowlist") }, excluded::add))
        assertEquals(listOf(own), excluded)
    }

    @Test fun includeUsesOnlyAvailableSelectedAppsAndNeverTheVpnItself() {
        val policy = AppRoutingConfig(AppRoutingConfig.INCLUDE, listOf(own, browser, mail))
        val attempted = mutableListOf<String>()
        val unavailable = policy.applyToVpn(own, { name ->
            attempted.add(name)
            name == browser
        }, { error("Unexpected denylist") })
        assertEquals(listOf(browser, mail), attempted)
        assertEquals(1, unavailable)
    }

    @Test fun emptyOrUnavailableAllowlistCannotSilentlyBecomeAllApps() {
        for (packages in listOf(emptyList(), listOf(mail), listOf(own))) {
            assertThrows(IllegalArgumentException::class.java) {
                AppRoutingConfig(AppRoutingConfig.INCLUDE, packages).applyToVpn(own, { it == browser }, { error("Unexpected denylist") })
            }
        }
    }

    @Test fun excludeKeepsTheVpnAndSelectedAppsOutsideTheTunnel() {
        val excluded = mutableListOf<String>()
        val policy = AppRoutingConfig(AppRoutingConfig.EXCLUDE, listOf(browser, mail, browser, own))
        val unavailable = policy.applyToVpn(own, { error("Unexpected allowlist") }, { name ->
            name != mail && excluded.add(name)
        })
        assertEquals(listOf(own, browser), excluded)
        assertEquals(1, unavailable)
    }

    @Test fun backupAndRuntimeConfigPreserveApplicationPolicy() {
        val config = AndroidVpnConfig.defaultConfig().copy(appRouting = AppRoutingConfig(AppRoutingConfig.EXCLUDE, listOf(browser, mail)))
        assertEquals(config, AndroidVpnConfig.fromImportText(ConfigFiles.backup(config)))
        assertEquals(config.appRouting, config.forConnection().appRouting)
    }

    @Test fun malformedAppPolicyDoesNotFallBackToAllApps() {
        assertNotNull(AppRoutingConfig("unknown").validate())
        assertNotNull(AppRoutingConfig(AppRoutingConfig.EXCLUDE, listOf("not a package")).validate())
        for (json in listOf("null", "[]", "{\"mode\":\"include\",\"packages\":[123]}", "{\"mode\":\"include\"}")) {
            assertThrows(Exception::class.java) { AppRoutingConfig.fromJson(JSONObject("{\"app_routing\":$json}")) }
        }
    }
}
