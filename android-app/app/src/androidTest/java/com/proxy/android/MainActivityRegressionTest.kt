package com.proxy.android

import android.app.ActivityOptions
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityRegressionTest {
    @Test
    fun missingSelectedApplicationPreventsVpnStart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue("Requires the user's existing VPN consent", VpnService.prepare(context) == null)
        val original = VpnConfigStore.load(context)
        val missingApp = AppRoutingConfig(AppRoutingConfig.INCLUDE, listOf("com.example.qa.never.installed"))
        try {
            VpnConfigStore.save(context, original.copy(appRouting = missingApp))
            launch().use { scenario ->
                scenario.onActivity { it.findViewById<Button>(R.id.toggleVpnButton).performClick() }
                val expected = context.getString(R.string.app_routing_unavailable)
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (PlaneVpnService.latestStatusMessage != expected && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(100)
                }
                assertEquals(expected, PlaneVpnService.latestStatusMessage)
                assertFalse(PlaneVpnService.isRunning)
                scenario.onActivity {
                    assertEquals(expected, it.findViewById<android.widget.TextView>(R.id.vpnStatusText).text)
                }
            }
        } finally {
            context.stopService(Intent(context, PlaneVpnService::class.java))
            VpnConfigStore.save(context, original)
        }
    }

    @Test
    fun selectionAndExportPreserveImportedSettingsAfterRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val original = VpnConfigStore.load(context)
        val fixture = original.copy(
            mtu = 1400,
            remotes = listOf(original.remotes.first().copy(name = "FIRST"), original.remotes.first().copy(name = "SECOND")),
            selectedNodeIndex = 1,
            appRouting = AppRoutingConfig(AppRoutingConfig.EXCLUDE, listOf("com.android.chrome", "com.example.mail")),
            routing = AndroidRoutingConfig("proxy", false, listOf(
                RouteRuleConfig("port", "443", "reject"),
                RouteRuleConfig("domain_pattern", "*.example.com", "direct"),
                RouteRuleConfig("domain_pattern", "proxy.example", "proxy"),
            )),
        )
        try {
            VpnConfigStore.save(context, fixture)
            launch().use { scenario ->
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(2, activity.findViewById<Spinner>(R.id.nodeSelectionSpinner).selectedItemPosition)
                    assertEquals(2, activity.findViewById<Spinner>(R.id.appRoutingModeSpinner).selectedItemPosition)
                    activity.findViewById<Button>(R.id.exportConfigButton).performClick()
                    val exported = AndroidVpnConfig.fromImportText(activity.findViewById<EditText>(R.id.importConfigInput).text.toString())
                    assertEquals(fixture, exported)
                }
            }
        } finally { VpnConfigStore.save(context, original) }
    }

    @Test
    fun deletingAnEarlierNodeKeepsTheSelectedNode() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val original = VpnConfigStore.load(context)
        val chosen = original.remotes.first().copy(name = "CHOSEN")
        try {
            VpnConfigStore.save(context, original.copy(remotes = listOf(original.remotes.first(), chosen), selectedNodeIndex = 1))
            launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<LinearLayout>(R.id.nodesContainer).getChildAt(0).findViewWithTag<Button>("removeNode").performClick()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.exportConfigButton).performClick()
                    val exported = AndroidVpnConfig.fromImportText(activity.findViewById<EditText>(R.id.importConfigInput).text.toString())
                    assertEquals(0, exported.selectedNodeIndex)
                    assertEquals(listOf(chosen), exported.remotes)
                }
            }
        } finally { VpnConfigStore.save(context, original) }
    }

    private fun launch(): ActivityScenario<MainActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) options.launchDisplayId = 0
        return ActivityScenario.launch(intent, options.toBundle())
    }

    @Test
    fun nodeKeyIsMaskedInitiallyAndAfterRecreation() {
        launch().use { scenario ->
            repeat(2) { pass ->
                if (pass == 1) scenario.recreate()
                scenario.onActivity { activity ->
                    activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_nodes
                    val key = activity.findViewById<LinearLayout>(R.id.nodesContainer).findViewWithTag<EditText>("nodeKey")
                    if (pass == 0) key.setText("mask-regression-key")
                    assertTrue("Password input must keep its masking transformation", key.transformationMethod is PasswordTransformationMethod)
                    assertNotEquals(key.text.toString(), key.transformationMethod.getTransformation(key.text, key).toString())
                }
            }
        }
    }

    @Test
    fun recreationKeepsRawUnsavedMultiNodeAndRoutingDraft() {
        launch().use { scenario ->
            var savedConfig = ""
            var initialNodes = 0
            scenario.onActivity { activity ->
                savedConfig = VpnConfigStore.load(activity).toJsonString()
                val nodes = activity.findViewById<LinearLayout>(R.id.nodesContainer)
                initialNodes = nodes.childCount
                nodes.findViewWithTag<EditText>("nodeName").setText("UNSAVED_DRAFT")
                nodes.findViewWithTag<EditText>("nodePort").setText("") // Invalid draft must also survive.
                activity.findViewById<Button>(R.id.addNodeButton).performClick()
                nodes.getChildAt(nodes.childCount - 1).findViewWithTag<EditText>("nodeHost").setText("draft.example")
                activity.findViewById<EditText>(R.id.directListInput).setText("*.draft.test\n# unfinished")
                activity.findViewById<EditText>(R.id.advancedRulesInput).setText("ip_cidr,")
                activity.findViewById<EditText>(R.id.importConfigInput).setText("unfinished import")
                activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_routing
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val nodes = activity.findViewById<LinearLayout>(R.id.nodesContainer)
                assertEquals(initialNodes + 1, nodes.childCount)
                assertEquals("UNSAVED_DRAFT", nodes.findViewWithTag<EditText>("nodeName").text.toString())
                assertEquals("", nodes.findViewWithTag<EditText>("nodePort").text.toString())
                assertEquals("draft.example", nodes.getChildAt(nodes.childCount - 1).findViewWithTag<EditText>("nodeHost").text.toString())
                assertEquals("*.draft.test\n# unfinished", activity.findViewById<EditText>(R.id.directListInput).text.toString())
                assertEquals("ip_cidr,", activity.findViewById<EditText>(R.id.advancedRulesInput).text.toString())
                assertEquals("unfinished import", activity.findViewById<EditText>(R.id.importConfigInput).text.toString())
                assertEquals(R.id.nav_routing, activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.routingPage).visibility)
                assertEquals(savedConfig, VpnConfigStore.load(activity).toJsonString())
            }
        }
    }
}
