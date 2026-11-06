package io.github.shaonialife.buicklinkplus.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Link Plus Trace updater. Reuses CarDeck E5's safe rules:
 * schema=2, exact GitHub source/asset, SHA256, package/version/signature checks,
 * independently routed HTTPS, and the Android system installer.
 * Auto mode only checks and notifies: never installs, downloads or changes
 * CarPlay binding without a deliberate foreground user action.
 */
object TraceUpdater {
    private const val PREFS = "link_plus_trace_updater"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_LAST_ATTEMPT = "last_attempt_check_ms"
    private const val KEY_LAST_SUCCESS = "last_success_check_ms"
    private const val KEY_LAST_RESULT = "last_check_result"
    private val main = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)

    fun autoEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun installedVersion(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun currentCertificate(context: Context): String? = runCatching {
        val pkg = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        certificateDigest(pkg.signingInfo?.apkContentsSigners).singleOrNull()
    }.getOrNull()

    fun fixedSigningInstalled(context: Context): Boolean =
        currentCertificate(context) == TraceReleasePolicy.CERT_SHA256

    fun maybeAutoCheck(activity: Activity) {
        if (!autoEnabled(activity)) return
        val pref = activity.getSharedPreferences(PREFS, 0)
        if (!TraceUpdateCheckPolicy.shouldAutoCheck(
                System.currentTimeMillis(),
                pref.getLong(KEY_LAST_SUCCESS, 0L),
                pref.getLong(KEY_LAST_ATTEMPT, 0L),
            )) return
        check(activity, silent = true)
    }

    fun lastCheckSummary(context: Context): String {
        val pref = context.getSharedPreferences(PREFS, 0)
        val last = pref.getLong(KEY_LAST_ATTEMPT, 0L)
        if (last <= 0) return "更新检查：尚未执行"
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
        return when (pref.getString(KEY_LAST_RESULT, "")) {
            "success" -> "最近检查：$time · 成功"
            "failure" -> "最近检查：$time · 失败（自动检查将在至少 10 分钟后重试）"
            else -> "最近检查：$time · 检查中"
        }
    }

    fun check(activity: Activity, silent: Boolean = false, onFinished: (() -> Unit)? = null) {
        if (!checking.compareAndSet(false, true)) {
            if (!silent) ui(activity) { showMessage(activity, "软件更新", "正在检查更新，请勿重复操作。") }
            return
        }
        val prefs = activity.getSharedPreferences(PREFS, 0)
        prefs.edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT, "checking").apply()
        thread(name = "trace-update-check") {
            try {
                val json = JSONArray(fetchText(activity, TraceReleasePolicy.RELEASES_URL, 2 * 1024 * 1024))
                val installed = installedVersion(activity)
                val match = TraceReleasePolicy.newest(json, installed)
                prefs.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                    .putString(KEY_LAST_RESULT, "success").apply()
                ui(activity) {
                    when {
                        match == null && !silent -> showMessage(activity, "软件更新", "当前没有符合 Trace 固定签名通道的更高版本。已安装版本码：$installed。")
                        match != null && !fixedSigningInstalled(activity) ->
                            if (!silent) showMessage(activity, "需要切换固定签名", "目前安装的是临时签名测试包，不能通过系统覆盖升级到固定签名。请先导出日志并备份 Gateway 配置，再卸载当前 Trace 安装第一个固定签名版。原 `.buick` 包不受影响。")
                        match != null -> {
                            AlertDialog.Builder(activity)
                                .setTitle("发现 Link Plus Trace 更新")
                                .setMessage(
                                    "版本：${match.versionName}（${match.versionCode}）\n" +
                                    "构建：${match.builtAt}\n来源：Trace 独立实验发布\n\n" +
                                    "${match.notes}\n\n" +
                                    "仅在停车、断开 CarPlay 后点击下载安装。会验证 SHA256、包名、版本及固定签名；安装仍需系统确认。",
                                )
                                .setPositiveButton("下载并安装") { _, _ -> download(activity, match) }
                                .setNegativeButton("稍后", null)
                                .show()
                        }
                    }
                }
            } catch (error: Exception) {
                prefs.edit().putString(KEY_LAST_RESULT, "failure").apply()
                if (!silent) ui(activity) { showMessage(activity, "检查失败", "无法检查更新：${error.javaClass.simpleName}（${error.message?.take(150)}）") }
            } finally {
                checking.set(false)
                if (onFinished != null) ui(activity) { onFinished() }
            }
        }
    }

    private fun ui(activity: Activity, run: () -> Unit) {
        main.post { if (!activity.isFinishing && !activity.isDestroyed) run() }
    }

    private fun showMessage(activity: Activity, title: String, detail: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(detail)
            .setPositiveButton("确定", null).show()
    }

    @Suppress("DEPRECATION")
    private fun routes(activity: Activity): List<Network> {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = cm.allNetworks.mapNotNull { net ->
            val cap = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
            val internet = cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (!internet) return@mapNotNull null
            val vpn = cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val verified = cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!vpn && !verified) return@mapNotNull null
            // The head-unit hotspot is process-bound for CarPlay and must never
            // be unbound just to perform an update check.
            if (net == cm.boundNetworkForProcess && !vpn) return@mapNotNull null
            val rank = when {
                vpn -> 0
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                else -> 3
            }
            rank to net
        }.sortedBy { it.first }
        // A running VPN should never silently fall back to a bypass route.
        return if (candidates.any { it.first == 0 }) candidates.filter { it.first == 0 }.map { it.second }
        else candidates.map { it.second }
    }

    private fun connection(route: Network, url: String, readTimeout: Int): HttpURLConnection {
        require(url.startsWith("https://")) { "Only HTTPS update endpoints allowed" }
        return (route.openConnection(URL(url)) as HttpURLConnection).apply {
            connectTimeout = 15_000
            this.readTimeout = readTimeout
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LinkPlus-Trace-Update/2")
        }
    }

    private fun fetchText(activity: Activity, url: String, maxBytes: Int): String {
        var last: Exception? = null
        for (route in routes(activity)) {
            try {
                val conn = connection(route, url, 25_000)
                try {
                    require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                    val data = java.io.ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val bytes = ByteArray(8192)
                        while (true) {
                            val n = input.read(bytes)
                            if (n < 0) break
                            if (n == 0) continue
                            require(data.size() + n <= maxBytes) { "更新元数据超过限制" }
                            data.write(bytes, 0, n)
                        }
                    }
                    return data.toString(Charsets.UTF_8.name())
                } finally {
                    conn.disconnect()
                }
            } catch (error: Exception) {
                last = error
            }
        }
        throw IOException("无可用的独立外网（请打开移动网络/VPN），${last?.javaClass?.simpleName ?: "NO_ROUTE"}")
    }

    private fun download(activity: Activity, candidate: TraceReleasePolicy.Candidate) {
        thread(name = "trace-update-download") {
            val target = File(activity.cacheDir, "trace-updates/${candidate.tag}/${TraceReleasePolicy.APK_NAME}")
            val temporary = File(target.parentFile, "${target.name}.part")
            try {
                target.parentFile?.mkdirs()
                // Release body and .sha256 asset must independently agree.
                val expectedFileDigest = fetchText(activity, candidate.digestUrl, 1024)
                    .trim().substringBefore(' ').substringBefore('\n').lowercase()
                require(expectedFileDigest == candidate.digest) { "发布正文和摘要文件不一致" }
                var last: Exception? = null
                var success = false
                for (route in routes(activity)) {
                    try {
                        temporary.delete()
                        val conn = connection(route, candidate.apkUrl, 60_000)
                        try {
                            require(conn.responseCode == 200) { "APK HTTP ${conn.responseCode}" }
                            val digest = MessageDigest.getInstance("SHA-256")
                            var bytes = 0L
                            conn.inputStream.use { input ->
                                temporary.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        if (n == 0) continue
                                        bytes += n
                                        require(bytes <= 250L * 1024 * 1024) { "APK 超过下载上限" }
                                        digest.update(buffer, 0, n)
                                        output.write(buffer, 0, n)
                                    }
                                }
                            }
                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                            require(bytes > 0 && actual == candidate.digest) { "SHA256 校验失败" }
                        } finally {
                            conn.disconnect()
                        }
                        success = true
                        break
                    } catch (error: Exception) {
                        last = error
                    }
                }
                require(success) { "下载失败：${last?.message ?: "无可用线路"}" }
                verifyArchive(activity, temporary, candidate)
                if (target.exists()) target.delete()
                require(temporary.renameTo(target)) { "无法保存已验证安装包" }
                ui(activity) { promptInstall(activity, target) }
            } catch (error: Exception) {
                temporary.delete()
                ui(activity) { showMessage(activity, "升级失败", "已拒绝不符合要求的安装包：${error.message?.take(180)}") }
            }
        }
    }

    /**
     * Recovery after the user grants REQUEST_INSTALL_PACKAGES: reuse only an
     * APK whose hash, release metadata, package, version and certificate all
     * validate again. Do not trust a stale file merely because it exists.
     */
    fun installCached(activity: Activity) {
        thread(name = "trace-update-verify-cache") {
            try {
                val releases = JSONArray(fetchText(activity, TraceReleasePolicy.RELEASES_URL, 2 * 1024 * 1024))
                val candidate = TraceReleasePolicy.newest(releases, installedVersion(activity))
                    ?: throw IllegalStateException("当前没有可安装的更高版本")
                val target = File(activity.cacheDir, "trace-updates/${candidate.tag}/${TraceReleasePolicy.APK_NAME}")
                require(target.isFile && target.length() > 0L && target.length() <= 250L * 1024 * 1024) {
                    "没有缓存的更新包，请先下载"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                target.inputStream().use { input ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        if (n > 0) digest.update(bytes, 0, n)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == candidate.digest) { "缓存 SHA256 校验失败" }
                verifyArchive(activity, target, candidate)
                ui(activity) { promptInstall(activity, target) }
            } catch (error: Exception) {
                ui(activity) {
                    showMessage(activity, "缓存安装不可用", "${error.message?.take(180)}。可重新点击检查更新并下载。")
                }
            }
        }
    }

    private fun certificateDigest(signatures: Array<android.content.pm.Signature>?): List<String> =
        signatures.orEmpty().map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

    private fun verifyArchive(activity: Activity, file: File, candidate: TraceReleasePolicy.Candidate) {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            activity.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: throw IllegalArgumentException("APK 无法解析")
        require(info.packageName == activity.packageName &&
            info.packageName == TraceReleasePolicy.PACKAGE_NAME) { "APK 包名不匹配" }
        require(info.longVersionCode == candidate.versionCode) { "APK 版本与 Release 不符" }
        require(info.longVersionCode > installedVersion(activity)) { "禁止覆盖成旧版本" }
        val digest = certificateDigest(info.signingInfo?.apkContentsSigners).singleOrNull()
        require(digest == TraceReleasePolicy.CERT_SHA256) { "APK 签名证书不匹配" }
        require(fixedSigningInstalled(activity)) { "当前安装包尚未切换到固定签名" }
    }

    private fun promptInstall(activity: Activity, target: File) {
        AlertDialog.Builder(activity).setTitle("已验证更新包")
            .setMessage("签名、SHA256、版本和包名已全部验证。请先停车并断开 CarPlay，再调用 Android 系统安装器；不会静默安装。")
            .setPositiveButton("打开系统安装器") { _, _ -> install(activity, target) }
            .setNegativeButton("稍后", null).show()
    }

    private fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("允许安装应用更新")
                .setMessage("请允许本应用安装未知应用，返回后重新检查更新。")
                .setPositiveButton("前往设置") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }.setNegativeButton("取消", null).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.traceupdate", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (error: Exception) {
            showMessage(activity, "无法打开安装程序", "校验过的 APK 已保留在缓存：${error.javaClass.simpleName}。")
        }
    }
}
