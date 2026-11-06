package io.github.shaonialife.buicklinkplus.gateway

/** Both preview and transmission use this exact bounded representation. */
class LogRedactor(private val secrets: List<String> = emptyList()) {
    private val sensitive = Regex("authorization|bearer|password|passwd|token|secret|private.?key|pairing.?key|session.?key|write.?key|read.?key|credential|nonce|salt|signature|密码|凭证", RegexOption.IGNORE_CASE)
    private val replacements = listOf(
        Regex("\\b[a-z][a-z0-9+.-]*://\\S+", RegexOption.IGNORE_CASE) to "[地址]",
        Regex("\\b(?:BUICK|CHEVROLET|CADILLAC)[_-][0-9]{3,}\\b", RegexOption.IGNORE_CASE) to "[车机蓝牙名]",
        Regex("\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b", RegexOption.IGNORE_CASE) to "[MAC]",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b") to "[IP]",
        // IPv6 with :: compression (e.g. fe80::1234), including one leading block.
        Regex("""(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){1,7}:[0-9A-Fa-f:]{0,39}(?![0-9A-Fa-f:])""", RegexOption.IGNORE_CASE) to "[IPv6]",
        Regex("(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]+", RegexOption.IGNORE_CASE) to "[IPv6]",
        Regex("\\b[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}\\b") to "[邮箱]",
        Regex("[A-Za-z0-9_+/=-]{32,}") to "[长标识]",
        Regex("\\b\\d{11,18}\\b") to "[数字标识]",
        Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b") to "[车辆标识]",
    )

    // Clock time is diagnostic data, not an IPv6 address.
    private val clockTime = Regex("""(?<![0-9A-Fa-f:])(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?![0-9A-Fa-f:])""")

    fun line(raw: String): String {
        if (raw.length > 8192) return "[超长日志已省略]"
        var text = raw
        for (secret in secrets.filter { it.isNotEmpty() }) text = text.replace(secret, "[已隐藏凭证]")
        if (sensitive.containsMatchIn(text)) return "[已隐藏含凭证或密钥的日志行]"
        val times = ArrayList<String>()
        text = clockTime.replace(text) { matched ->
            val id = times.size
            times.add(matched.value)
            "~CARDECK_TIME_" + id + "~"
        }
        for ((pattern, replacement) in replacements) text = pattern.replace(text, replacement)
        for ((index, value) in times.withIndex()) text = text.replace("~CARDECK_TIME_" + index + "~", value)
        text = Regex("(ssid|serial|device.?id|vin|latitude|longitude)\\s*[:=].*", RegexOption.IGNORE_CASE)
            .replace(text) { "${it.groupValues[1]}=[已隐藏]" }
        return text.map { if (it.code < 32 || it.code == 127) ' ' else it }.joinToString("").take(256)
    }
}
