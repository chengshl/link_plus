package io.github.shaonialife.buicklinkplus.gateway

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** One-time user configuration. Credentials never appear in app logs or on the status screen. */
class GatewaySettingsActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var address: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            val snapshot = GatewayAutoUpload.currentStatus()
            status.text = "状态：${snapshot.state}\nACK 确认日志：${snapshot.acknowledged} 行\n本轮丢弃：${snapshot.dropped} 行\n心跳：${snapshot.heartbeat}"
            main.postDelayed(this, 1_500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Once 日志自动上报"
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt())
        }
        val scroll = ScrollView(this).apply { addView(container) }
        setContentView(scroll)
        fun text(value: String) = TextView(this).apply {
            this.text = value
            textSize = 15f
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            container.addView(this)
        }
        text("配置一次后，本应用启动时自动向现有 Once Gateway 上报脱敏应用日志和协议取证事件。手机连接车机热点时，上传使用独立互联网线路；没有可用线路会重试。")
        text("Gateway WebSocket 地址（必须是 wss://，含完整路径）")
        address = EditText(this).apply {
            setSingleLine(true)
            hint = "wss://gateway.example.com/ws"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            container.addView(this, ViewGroup.LayoutParams(-1, -2))
        }
        text("Gateway 访问令牌（保存在 Android Keystore 中；以后留空表示继续使用原令牌）")
        token = EditText(this).apply {
            setSingleLine(true)
            hint = "粘贴 Gateway 令牌"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            container.addView(this, ViewGroup.LayoutParams(-1, -2))
        }
        val save = Button(this).apply {
            text = "保存并开启自动上报"
            setOnClickListener {
                val url = address.text.toString().trim()
                val supplied = token.text.toString()
                isEnabled = false
                io.execute {
                    val outcome = runCatching {
                        val retained = if (supplied.isBlank()) EntryConfigStore.load(applicationContext)?.token else null
                        EntryConfigStore.save(applicationContext, url, retained ?: supplied)
                        GatewayAutoUpload.reload()
                    }
                    runOnUiThread {
                        isEnabled = true
                        if (outcome.isSuccess) {
                            token.text.clear()
                            Toast.makeText(this@GatewaySettingsActivity, "已保存，自动上报已启用", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@GatewaySettingsActivity, outcome.exceptionOrNull()?.message ?: "保存失败", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            container.addView(this)
        }
        Button(this).apply {
            text = "停止自动上报并清除保存的令牌"
            setOnClickListener {
                GatewayAutoUpload.disable()
                token.text.clear()
                Toast.makeText(this@GatewaySettingsActivity, "正在停止并清除配置", Toast.LENGTH_SHORT).show()
            }
            container.addView(this)
        }
        status = text("状态读取中")
        text("仅上传应用层脱敏日志；不自动采集系统 logcat，不上传原始配对密钥、热点密码或完整抓包。网络中断期间只保留有限的内存队列，设备重启后不能补传丢失的历史批次。")
        io.execute {
            val existing = runCatching { EntryConfigStore.load(applicationContext) }.getOrNull()
            if (existing != null) runOnUiThread { address.setText(existing.gatewayUrl) }
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
