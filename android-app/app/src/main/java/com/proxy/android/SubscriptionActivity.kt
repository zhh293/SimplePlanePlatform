package com.proxy.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

class SubscriptionActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var action: MaterialButton
    private lateinit var logout: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "订阅节点"
        val padding = (24 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding,padding,padding,padding) }
        status = TextView(this).apply { textSize = 18f }
        action = MaterialButton(this)
        logout = MaterialButton(this).apply { text = "退出订阅账号" }
        body.addView(status); body.addView(action); body.addView(logout); setContentView(body)
        update()
        action.setOnClickListener { runTask {
            if (!NodeSubscription.bound(this)) {
                val pair = NodeSubscription.start(this)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        status.text = "请在浏览器核对授权码\n${pair.code}"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pair.uri)))
                    }
                }
                while (true) {
                    Thread.sleep(3000)
                    when (NodeSubscription.poll(this,pair)) {
                        "pending" -> continue
                        "approved" -> break
                        else -> error("本次设备授权已结束")
                    }
                }
            }
            val count = NodeSubscription.sync(this)
            runOnUiThread { setResult(RESULT_OK); update("已同步 $count 个节点。") }
        } }
        logout.setOnClickListener { runTask {
            NodeSubscription.logout(this)
            runOnUiThread { setResult(RESULT_OK); update("已退出订阅账号。") }
        } }
    }

    private fun runTask(block: () -> Unit) {
        action.isEnabled = false; logout.isEnabled = false
        worker.submit {
            try { block() } catch (failure: Exception) {
                runOnUiThread { if (!isDestroyed) update(failure.message ?: "无法连接订阅服务") }
            }
        }
    }

    private fun update(message: String? = null) {
        val bound = NodeSubscription.bound(this)
        action.isEnabled = true; logout.isEnabled = true
        action.text = if (bound) "同步节点" else "用浏览器登录"
        status.text = message ?: if (bound) "这台设备已绑定订阅账号。" else "登录后，可用节点会自动添加到客户端。"
        logout.visibility = if (bound) View.VISIBLE else View.GONE
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
