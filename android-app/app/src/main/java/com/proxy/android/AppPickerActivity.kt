package com.proxy.android

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.scale
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/** Selection stays local until Done, including across search and activity recreation. */
class AppPickerActivity : AppCompatActivity() {
    private data class App(val packageName: String, val label: String)
    private val selected = linkedSetOf<String>()
    private var apps: List<App> = emptyList()
    private var visibleApps: List<App> = emptyList()
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var adapter: ArrayAdapter<CharSequence>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            resources.getBoolean(R.bool.light_system_bars)
        selected.addAll(savedInstanceState?.getStringArrayList(EXTRA_PACKAGES)
            ?: intent.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty())
        selected.remove(packageName)
        search = findViewById(R.id.appSearchInput)
        list = findViewById(R.id.appList)
        adapter = ArrayAdapter(this, R.layout.item_app_choice, mutableListOf<CharSequence>())
        list.adapter = adapter
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.setOnItemClickListener { _, _, position, _ ->
            val name = visibleApps[position].packageName
            if (list.isItemChecked(position)) selected.add(name) else selected.remove(name)
            updateCount()
        }
        findViewById<Button>(R.id.appPickerCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.appPickerDone).setOnClickListener {
            setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(selected.sorted())))
            finish()
        }
        search.setText(savedInstanceState?.getString(SEARCH).orEmpty())
        search.doAfterTextChanged { filterApps() }
        updateCount()
        lifecycleScope.launch {
            try {
                val savedSelection = selected.toSet()
                apps = withContext(Dispatchers.IO) { loadApps(savedSelection) }
                filterApps()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                findViewById<TextView>(R.id.appPickerEmpty).setText(R.string.app_picker_failed)
                findViewById<View>(R.id.appPickerEmpty).visibility = View.VISIBLE
            } finally {
                findViewById<View>(R.id.appPickerProgress).visibility = View.GONE
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadApps(savedSelection: Set<String>): List<App> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = packageManager.queryIntentActivities(launcher, 0)
            .associate { it.activityInfo.packageName to it.activityInfo.applicationInfo }
        val entries = (launchable.keys + savedSelection - packageName).map { name ->
            val label = try {
                val info = launchable[name] ?: packageManager.getApplicationInfo(name, 0)
                info.loadLabel(packageManager).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                getString(R.string.app_picker_unavailable)
            }
            App(name, label)
        }
        val collator = Collator.getInstance()
        return entries.sortedWith(compareByDescending<App> { it.packageName in savedSelection }
            .thenBy(collator) { it.label }.thenBy { it.packageName })
    }

    private fun filterApps() {
        val query = search.text.toString().trim()
        visibleApps = apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        val secondary = ContextCompat.getColor(this, R.color.app_text_secondary)
        adapter.setNotifyOnChange(false)
        adapter.clear()
        adapter.addAll(visibleApps.map { app ->
            buildSpannedString {
                bold { append(app.label) }
                append('\n')
                color(secondary) { scale(0.88f) { append(app.packageName) } }
            }
        })
        adapter.notifyDataSetChanged()
        list.clearChoices()
        visibleApps.forEachIndexed { index, app -> list.setItemChecked(index, app.packageName in selected) }
        findViewById<View>(R.id.appPickerEmpty).visibility = if (visibleApps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateCount() {
        findViewById<TextView>(R.id.appPickerCount).text = getString(R.string.app_picker_count, selected.size)
        findViewById<Button>(R.id.appPickerDone).isEnabled =
            !intent.getBooleanExtra(EXTRA_REQUIRE_SELECTION, false) || selected.isNotEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(EXTRA_PACKAGES, ArrayList(selected))
        outState.putString(SEARCH, search.text.toString())
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_PACKAGES = "selectedPackages"
        const val EXTRA_REQUIRE_SELECTION = "requireSelection"
        private const val SEARCH = "appSearch"
    }
}
