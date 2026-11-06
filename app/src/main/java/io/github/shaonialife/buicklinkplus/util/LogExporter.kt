package io.github.shaonialife.buicklinkplus.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.shizuku.EmbeddedServiceProvider
import io.github.shaonialife.buicklinkplus.shizuku.Shell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将日志导出到 MediaStore.Downloads，无需存储权限
 * 优先读取文件日志，失败时使用界面缓冲
 */
object LogExporter {
    /** 日志导出范围，默认本次启动 */
    enum class Scope(
        val label: String,
        val fileNameTag: String,
    ) {
        CURRENT_SESSION("仅本次启动", ""),
        ALL("全部历史日志", "-all"),
    }

    data class Result(
        val uri: Uri,
        val fileName: String,
        /** 给用户看的绝对路径（文件管理器里按这个找）。 */
        val displayPath: String,
        val bytes: Long,
        val scope: Scope,
    )

    /** 导出目录（相对外部存储根）：下载/Buick Link＋。 */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/Buick Link＋"

    /** 文件管理器里展示的绝对路径前缀（主用户外部存储；分区存储下即 /sdcard）。 */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** 后台导出日志，写入失败返回 null */
    fun export(
        context: Context,
        fallbackText: String,
        scope: Scope = Scope.CURRENT_SESSION,
    ): Result? {
        val body = LogRedactor.redact(buildContent(context, fallbackText, scope))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "Buick Link＋-log-$stamp${scope.fileNameTag}.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                // 先标 pending 再写：避免文件管理器/相册扫描到半截文件
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream 返回 null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result(uri, fileName, "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName", bytes.size.toLong(), scope)
        } catch (_: Exception) {
            // 半截文件（IS_PENDING=1）对用户不可见，但会占空间——清掉
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun buildContent(
        context: Context,
        fallbackText: String,
        scope: Scope,
    ): String =
        buildString {
            appendLine("Buick Link＋ 运行日志")
            appendLine("导出时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("应用版本: " + versionName(context))
            appendLine("内部版本码: ${io.github.shaonialife.buicklinkplus.BuildConfig.VERSION_CODE}")
            appendLine("构建标识: ${io.github.shaonialife.buicklinkplus.BuildConfig.BUILD_ID}")
            appendLine("会话快照: ${io.github.shaonialife.buicklinkplus.session.SessionController.diagnosticSnapshot()}")
            appendLine("导出范围: " + scope.label)
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} | " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("==================================================")
            val fileLog = DebugFileLog.read(context, currentSessionOnly = scope == Scope.CURRENT_SESSION)
            if (!fileLog.isNullOrBlank()) {
                if (scope == Scope.CURRENT_SESSION) {
                    appendLine("（仅本次进程启动之后的日志；需要历史请点「导出全部」）")
                } else {
                    appendLine("（全部文件日志；文件最多 2MB，超出时系统只保留最近一段）")
                }
                append(fileLog)
                if (!fileLog.endsWith('\n')) appendLine()
            } else {
                // 启动标记刚写、其余日志还没落盘（或文件不可读）时，界面缓冲仍是有效来源
                appendLine("（文件日志不可用——导出界面缓冲，可能不完整）")
                append(fallbackText)
            }
            appendLine()
            appendLine("================ 协议追踪（仅元数据，不含密钥和原始载荷） ================")
            appendLine(ProtocolTrace.export(context, scope == Scope.CURRENT_SESSION))
            appendLine("================ SHELL-INPUT 输入诊断 ================")
            appendLine("采集经过时间=${android.os.SystemClock.elapsedRealtime()}ms；只读末尾 ${ShellInputLogExcerpt.MAX_BYTES} 字节")
            appendLine(collectInputDiagnostics(context))
        }

    /**
     * 只在用户导出时后台读取固定文件，服务更换即丢弃结果，失败不影响主日志导出
     */
    private fun collectInputDiagnostics(context: Context): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return "未采集：输入诊断不能阻塞界面线程"
        val owner = EmbeddedServiceProvider.shell ?: return "未采集：内置服务未连接，未读取历史 shell 文件"
        val result = Shell.exec("tail -c ${ShellInputLogExcerpt.MAX_BYTES} /data/local/tmp/carinput_svc.log", timeoutMs = 1500)
        if (EmbeddedServiceProvider.shell !== owner) return "未采集：读取期间内置服务已更换或断开，已丢弃旧结果"
        if (!result.ok) return "未采集：shell 读取失败，退出码=${result.exitCode}；未导出原始错误文本"
        return ShellInputLogExcerpt.extract(result.output, context.packageName)
    }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("?")
}
