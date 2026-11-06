package io.github.shaonialife.buicklinkplus.gateway

/**
 * Auto-upload-only privacy boundary. The phone's diagnostic export remains untouched.
 * Redact human-readable song/lyrics text and packet previews *before* the shared
 * Gateway redactor performs its existing credential/address/identifier filtering.
 */
object GatewayLogPolicy {
    private val playing = Regex("""\bplaying=(true|false)\b""")
    private val http = Regex("""\bHTTP=(\d{3}|未收到响应)""")
    private val preview = Regex("""(?i)(?:\b(?:head|hex|raw|payload|pub)\s*[:=]|全字段\s*[:=]).*$""")

    fun prepare(line: String): String? {
        if (line.startsWith("APP MEDIA:")) {
            val state = playing.find(line)?.groupValues?.get(1) ?: "unknown"
            return "APP MEDIA: playing=$state (track metadata omitted)"
        }
        if (line.startsWith("APP LYRICS:")) {
            // Retain transport diagnostics only. Never transmit lyrics or song titles.
            val code = http.find(line)?.groupValues?.get(1) ?: return null
            return "APP LYRICS: request HTTP=$code"
        }
        val safe = preview.replace(line, "[packet preview omitted]")
        return safe.take(2048).takeIf { it.isNotBlank() }
    }
}
