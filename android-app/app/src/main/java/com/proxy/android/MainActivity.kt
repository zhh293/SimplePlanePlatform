package com.proxy.android

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 主界面。
 *
 * A1 职责：
 *  1. 显示 native（plane-core）版本号，证明 `.so` 已加载、JNI 可调用。
 *  2. 一个开关按钮，走 [VpnService.prepare]：若未授权则拉起系统 VPN 授权框，
 *     授权通过后启动前台 [PlaneVpnService]。
 *
 * 后续阶段（B6）会把界面扩展为节点/规则配置与状态展示，这里只保留最小可验证骨架。
 */
class MainActivity : AppCompatActivity() {

    // 系统 VPN 授权对话框的结果回调：用户点「允许」后启动 VPN 服务。
    private val prepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val versionView = findViewById<TextView>(R.id.nativeVersionText)
        versionView.text = getString(R.string.native_version_label, readNativeVersion())

        findViewById<Button>(R.id.toggleVpnButton).setOnClickListener { onToggleClicked() }
    }

    private fun readNativeVersion(): String =
        runCatching { NativeBridge().nativeVersion() }
            .getOrElse { "<load failed: ${it.message}>" }

    private fun onToggleClicked() {
        // prepare 返回非 null Intent 表示尚未授权，需要拉起系统授权框；
        // 返回 null 表示已授权，可直接启动。
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) {
            prepareLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        // 节点配置已在 PlaneVpnService 中写死（对接固定服务端），这里直接启动即可。
        val intent = Intent(this, PlaneVpnService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
