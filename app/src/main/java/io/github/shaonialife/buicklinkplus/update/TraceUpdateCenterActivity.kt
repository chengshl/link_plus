package io.github.shaonialife.buicklinkplus.update

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** E5 Updater-B style, scoped to one independent Link+ Trace update track. */
class TraceUpdateCenterActivity : AppCompatActivity() {
    private val foreground = Color.rgb(236, 241, 250)
    private val muted = Color.rgb(159, 169, 190)
    private val background = Color.rgb(17, 22, 33)
    private val panel = Color.rgb(29, 36, 51)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "软件更新 · Link Plus Trace"
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (density * n + 0.5f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            setBackgroundColor(background)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = s
                textSize = sp
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(7), 0, dp(7))
                root.addView(this)
            }
        text("软件更新", 26f, foreground, true)
        text("独立 Trace 通道 · 固定签名 · 用户确认安装", 14f, muted)
        val currentVersion = runCatching { TraceUpdater.installedVersion(this) }.getOrDefault(0L)
        val displayVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown")
        text("当前版本：$displayVersion（$currentVersion）", 17f, foreground)
        text(
            if (TraceUpdater.fixedSigningInstalled(this))
                "签名：固定证书已验证，可以接收后续同签名更新"
            else
                "签名：当前是临时调试签名。首次切换固定签名需要先导出日志、备份配置，再重装。",
            14f, muted,
        )
        text("更新来源由应用内部固定为 chengshl/link_plus 的 Trace 独立发布，不跨项目、不跨分支，也不允许安装旧版本。", 14f, muted)
        SwitchCompat(this).apply {
            text = "启动时自动检查更新（仅通知）"
            textSize = 15f
            setTextColor(foreground)
            isChecked = TraceUpdater.autoEnabled(this@TraceUpdateCenterActivity)
            setOnCheckedChangeListener { _, checked ->
                TraceUpdater.setAutoEnabled(this@TraceUpdateCenterActivity, checked)
            }
            root.addView(this)
        }
        Button(this).apply {
            text = "立即检查最新版本"
            setOnClickListener { TraceUpdater.check(this@TraceUpdateCenterActivity) }
            background = GradientDrawable().apply {
                setColor(panel)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(70, 85, 109))
            }
            setTextColor(foreground)
            root.addView(this, LinearLayout.LayoutParams(-1, dp(56)))
        }
        text("更新检查使用手机移动网络或已验证的独立互联网，不重新绑定车机 Wi-Fi。只有你确认后才下载，下载成功还会校验 SHA256、包名、版本码与固定签名，并调用 Android 系统安装器。", 14f, muted)
        text("温馨提示：正在 CarPlay 会话中请不要安装更新，先正常断开车机。Gateway 自动上报配置存储在当前应用数据中，覆盖升级通常会保留；卸载重装不会保留。", 13f, muted)
    }
}
