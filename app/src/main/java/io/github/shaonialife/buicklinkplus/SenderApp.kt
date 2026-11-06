package io.github.shaonialife.buicklinkplus

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.shaonialife.buicklinkplus.audio.MediaMuteController
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.util.Bg
import io.github.shaonialife.buicklinkplus.util.DebugFileLog
import io.github.shaonialife.buicklinkplus.gateway.GatewayAutoUpload
import io.github.shaonialife.buicklinkplus.util.ProtocolTrace

/** 记录未捕获的线程异常，避免单个后台线程退出导致整个进程被关闭 */
class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        io.github.shaonialife.buicklinkplus.ui.UiAppearance
            .initialize(this)
        // 先写本次启动标记，让日志导出能排除之前的记录
        DebugFileLog.markSessionStart(this)
        GatewayAutoUpload.initialize(this)
        ProtocolTrace.initialize(this)
        io.github.shaonialife.buicklinkplus.ui.ScreenDimmer.enabled =
            getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("alwaysOn", false)
        io.github.shaonialife.buicklinkplus.ui.ScreenDimmer
            .recoverTimeout(applicationContext)
        Bg.run("media-mute-recover") {
            MediaMuteController.recover(applicationContext) { DebugFileLog.append(this, it) }
        }
        Bg.run("embedded-service-state") {
            io.github.shaonialife.buicklinkplus.shizuku.EmbeddedAdb
                .initialize(applicationContext)
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "未捕获异常（线程 ${thread.name}，已吞掉保进程）", throwable)
            // 不调用 previous：默认处理器会 killProcess 杀死整个 app
        }
        // 应用安装/卸载/更新 → 桌面与车机设置的共享应用清单缓存失效
        val packageFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    VirtualLauncher
                        .invalidateAppsCache()
                    Log.i(TAG, "应用清单缓存已失效（包变化）")
                }
            },
            packageFilter,
        )
    }

    companion object {
        private const val TAG = "CarPlaySender"
    }
}
