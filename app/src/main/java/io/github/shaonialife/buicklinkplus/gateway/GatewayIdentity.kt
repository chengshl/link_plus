package io.github.shaonialife.buicklinkplus.gateway

import android.content.Context
import android.os.Build
import java.io.File
import java.util.UUID

object GatewayIdentity {
    /** Called on Bg, persisted outside Android backup; uninstall creates a new identity. */
    @Synchronized
    fun load(context: Context): GatewayDeviceInfo {
        val file = File(context.noBackupFilesDir, "gateway-installation-id")
        val existing = if (file.exists()) file.readText() else null
        val id = existing?.takeIf { value ->
            runCatching { UUID.fromString(value).let { it.toString() == value && it.version() == 4 } }.getOrDefault(false)
        } ?: UUID.randomUUID().toString().also { file.writeText(it) }
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        return GatewayDeviceInfo(id, version, Build.VERSION.SDK_INT)
    }
}
