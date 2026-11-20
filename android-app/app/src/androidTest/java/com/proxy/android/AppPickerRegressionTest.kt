package com.proxy.android

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPickerRegressionTest {
    private val unavailable = "com.example.no.longer.installed"

    private fun launch(): ActivityScenario<AppPickerActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), AppPickerActivity::class.java)
            .putStringArrayListExtra(AppPickerActivity.EXTRA_PACKAGES, arrayListOf(unavailable))
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) options.launchDisplayId = 0
        return ActivityScenario.launchActivityForResult(intent, options.toBundle())
    }

    private fun loaded(scenario: ActivityScenario<AppPickerActivity>) {
        repeat(60) {
            var ready = false
            scenario.onActivity { ready = it.findViewById<View>(R.id.appPickerProgress).visibility == View.GONE }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Application picker did not finish loading")
    }

    @Test fun searchAndSelectionSurviveRecreationAndMissingAppsCanBeRemoved() {
        launch().use { scenario ->
            loaded(scenario)
            scenario.onActivity { it.findViewById<EditText>(R.id.appSearchInput).setText(unavailable) }
            scenario.recreate()
            loaded(scenario)
            scenario.onActivity { activity ->
                val list = activity.findViewById<ListView>(R.id.appList)
                assertEquals(unavailable, activity.findViewById<EditText>(R.id.appSearchInput).text.toString())
                assertEquals(1, list.count)
                assertTrue(list.isItemChecked(0))
                assertEquals(activity.getString(R.string.app_picker_count, 1), activity.findViewById<TextView>(R.id.appPickerCount).text)
                list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                assertFalse(list.isItemChecked(0))
                activity.findViewById<Button>(R.id.appPickerDone).performClick()
            }
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals(emptyList<String>(), scenario.result.resultData.getStringArrayListExtra(AppPickerActivity.EXTRA_PACKAGES))
        }
    }

    @Test fun cancelDoesNotReturnAReplacementSelection() {
        launch().use { scenario ->
            scenario.onActivity { it.findViewById<Button>(R.id.appPickerCancel).performClick() }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }
}
