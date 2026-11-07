package com.proxy.android

import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityRegressionTest {
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
