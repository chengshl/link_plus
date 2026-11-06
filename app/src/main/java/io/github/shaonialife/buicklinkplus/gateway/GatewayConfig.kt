package io.github.shaonialife.buicklinkplus.gateway

import java.net.URI

/** 地址与凭证由用户配置；日志中禁止回显凭证。 */
class EntryConfig(
    val gatewayUrl: String,
    val token: String,
) {
    override fun toString() = "EntryConfig(configured=${gatewayUrl.isNotEmpty()}, token=<redacted>)"
}

object EntryAddress {
    fun normalize(value: String): String {
        val address = value.trim()
        val uri = runCatching { URI(address) }.getOrNull()
        require(
            uri != null && uri.scheme.equals("wss", ignoreCase = true) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535) && !uri.rawPath.isNullOrEmpty(),
        ) { "请填写完整的 wss:// 地址，包含服务路径；不要在地址中填写凭证或查询参数。" }
        return address
    }
}
