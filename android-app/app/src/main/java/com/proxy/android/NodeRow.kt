package com.proxy.android

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** A node editor keeps raw text in its draft, including incomplete/invalid ports. */
internal class NodeRow private constructor(
    val root: MaterialCardView,
    val nameInput: EditText,
    val hostInput: EditText,
    val portInput: EditText,
    val keyInput: EditText,
    val cipherInput: EditText,
    val transportSpinner: Spinner,
    val serverNameInput: EditText,
    var caPem: String,
    val tlsCheckBox: CheckBox,
    val enabledCheckBox: CheckBox,
    private val removeButton: Button,
    private val fields: List<TextInputLayout>,
    private val advancedFields: View,
) {
    private val draftInputs get() = mapOf(
        "name" to nameInput, "host" to hostInput, "port" to portInput,
        "key" to keyInput, "cipher" to cipherInput, "serverName" to serverNameInput,
    )

    fun saveDraft() = Bundle().apply {
        draftInputs.forEach { (name, input) -> putString(name, input.text.toString()) }
        putString("caPem", caPem)
        putInt("transport", transportSpinner.selectedItemPosition)
        putBoolean("tls", tlsCheckBox.isChecked)
        putBoolean("enabled", enabledCheckBox.isChecked)
        putBoolean("advanced", advancedFields.visibility == View.VISIBLE)
    }

    fun restoreDraft(draft: Bundle) {
        draftInputs.forEach { (name, input) -> input.setText(draft.getString(name, "")) }
        caPem = draft.getString("caPem", "")
        transportSpinner.setSelection(draft.getInt("transport"))
        tlsCheckBox.isChecked = draft.getBoolean("tls")
        enabledCheckBox.isChecked = draft.getBoolean("enabled")
        advancedFields.visibility = if (draft.getBoolean("advanced")) View.VISIBLE else View.GONE
    }

    fun clearErrors() {
        hostInput.error = null
        portInput.error = null
        keyInput.error = null
    }

    fun setEnabled(enabled: Boolean) {
        fields.forEach { it.isEnabled = enabled }
        listOf(transportSpinner, enabledCheckBox, removeButton).forEach { it.isEnabled = enabled }
        tlsCheckBox.isEnabled = enabled && transportSpinner.selectedItemPosition == 0
    }

    companion object {
        fun create(context: Context, node: RemoteNodeConfig, onRemove: (NodeRow) -> Unit): NodeRow {
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
            val root = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
                radius = dp(20).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.app_surface))
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(12))
            }
            root.addView(content)
            val fields = mutableListOf<TextInputLayout>()
            fun input(parent: LinearLayout, tag: String, label: String, text: String, type: Int = InputType.TYPE_CLASS_TEXT, helper: String? = null): EditText {
                val field = TextInputLayout(context).apply {
                    hint = label
                    isExpandedHintEnabled = false
                    helperText = helper
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
                }
                val edit = TextInputEditText(field.context).apply {
                    this.tag = tag
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                    // setSingleLine installs a transformation; assign inputType
                    // afterwards so it cannot replace the password mask.
                    isSingleLine = true
                    inputType = type
                    isSaveEnabled = false // Raw drafts are restored explicitly.
                    setText(text)
                }
                if (type and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                    edit.transformationMethod = PasswordTransformationMethod.getInstance()
                    field.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                field.addView(edit)
                parent.addView(field)
                fields += field
                return edit
            }
            val name = input(content, "nodeName", context.getString(R.string.node_name), node.name)
            val host = input(content, "nodeHost", context.getString(R.string.node_host), node.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val port = input(content, "nodePort", context.getString(R.string.node_port), node.port.toString(), InputType.TYPE_CLASS_NUMBER)
            val key = input(content, "nodeKey", context.getString(R.string.node_key), node.key, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val transport = Spinner(context).apply {
                contentDescription = context.getString(R.string.node_transport)
                minimumHeight = dp(48)
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("HTTP/2 · TCP", "HTTP/3 · QUIC"))
                setSelection(if (node.transport == "http3") 1 else 0)
            }
            content.addView(TextView(context).apply { text = context.getString(R.string.node_transport); setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary)) })
            content.addView(transport)
            val advancedButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = context.getString(R.string.node_advanced) }
            content.addView(advancedButton)
            val advanced = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            content.addView(advanced)
            advancedButton.setOnClickListener { advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            val cipher = input(advanced, "nodeCipher", context.getString(R.string.node_cipher), node.cipher)
            val serverName = input(advanced, "nodeServerName", context.getString(R.string.node_server_name), node.serverName, helper = context.getString(R.string.node_server_name_helper))
            val tls = MaterialCheckBox(context).apply {
                text = context.getString(R.string.node_tls)
                isChecked = node.tls || node.transport == "http3"
                isEnabled = node.transport != "http3"
            }
            advanced.addView(tls)
            transport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    tls.isEnabled = transport.isEnabled && position == 0
                    if (position == 1) tls.isChecked = true
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            val enabled = MaterialCheckBox(context).apply { text = context.getString(R.string.node_enabled); isChecked = node.enabled }
            val remove = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = context.getString(R.string.node_remove) }
            content.addView(enabled)
            content.addView(remove, LinearLayout.LayoutParams(-1, -2))
            val row = NodeRow(root, name, host, port, key, cipher, transport, serverName, node.caPem, tls, enabled, remove, fields, advanced)
            remove.setOnClickListener { onRemove(row) }
            return row
        }
    }
}
