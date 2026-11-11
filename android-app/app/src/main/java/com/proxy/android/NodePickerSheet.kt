package com.proxy.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** The index always refers to the configuration, including when the list is filtered. */
internal data class NodeChoice(val index: Int, val name: String, val detail: String, val enabled: Boolean)

class NodePickerSheet : BottomSheetDialogFragment() {
    private val host get() = requireActivity() as MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.sheet_node_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.node_picker_title))
        val list = view.findViewById<ListView>(R.id.nodeChoiceList)
        val adapter = ChoiceAdapter()
        list.adapter = adapter
        list.emptyView = view.findViewById(R.id.noNodeChoices)
        list.setOnItemClickListener { _, _, position, _ ->
            if (host.selectDashboardNode(adapter.getItem(position).index)) dismiss()
        }
        view.findViewById<EditText>(R.id.nodeSearchInput).doAfterTextChanged { refresh() }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        refresh()
    }

    internal fun refresh() {
        val root = view ?: return
        val adapter = root.findViewById<ListView>(R.id.nodeChoiceList).adapter as? ChoiceAdapter ?: return
        val query = root.findViewById<EditText>(R.id.nodeSearchInput).text.toString().trim()
        val locked = host.nodeSelectionLocked
        root.findViewById<View>(R.id.nodeLockHint).visibility = if (locked) View.VISIBLE else View.GONE
        adapter.locked = locked
        adapter.items = host.dashboardNodes().filter {
            it.name.contains(query, ignoreCase = true) || it.detail.contains(query, ignoreCase = true)
        }
        adapter.notifyDataSetChanged()
    }

    private inner class ChoiceAdapter : BaseAdapter() {
        var items: List<NodeChoice> = emptyList()
        var locked = false
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = (getItem(position).index + 1).toLong()
        override fun hasStableIds() = true
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = !locked && getItem(position).enabled

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_node_choice, parent, false)
            val choice = getItem(position)
            val selected = choice.index == host.selectedNodeIndex
            row.findViewById<TextView>(R.id.choiceName).text = choice.name
            val detail = if (choice.enabled) choice.detail else
                getString(R.string.node_disabled) + " · " + choice.detail
            row.findViewById<TextView>(R.id.choiceDetail).text = detail
            row.findViewById<RadioButton>(R.id.choiceRadio).apply {
                isChecked = selected
                isEnabled = this@ChoiceAdapter.isEnabled(position)
            }
            ViewCompat.setStateDescription(row, if (selected) getString(R.string.node_selected) else null)
            return row
        }
    }

    companion object {
        const val TAG = "nodePicker"
    }
}
