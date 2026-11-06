package io.github.shaonialife.buicklinkplus.update

import org.json.JSONArray

/**
 * CarDeck Updater-B schema 2 adapted to the independent Link Plus Trace track.
 * A candidate must originate from this repo and update line; never cross-install
 * an E5, Test, upstream, or another experimental Android application package.
 */
internal object TraceReleasePolicy {
    const val REPOSITORY = "chengshl/link_plus"
    const val BASE_BRANCH = "experiment/link-plus-protocol-trace-20261106"
    const val SOURCE_BRANCH = "experiment/link-plus-trace-fixed-update-20261107"
    const val PACKAGE_NAME = "io.github.shaonialife.buicklinkplus.buick.trace"
    const val APK_NAME = "LinkPlus-Trace.apk"
    const val CERT_SHA256 = "3fc11b60da53fd634d668bde7f7e9e19f6ed042d59502de2797b949e53e482e3"
    const val RELEASES_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"
    private val tagPattern = Regex("exp-link-plus-trace-v[1-9][0-9]*-[a-f0-9]{12}")
    private val digestPattern = Regex("[a-f0-9]{64}")

    data class Candidate(
        val tag: String,
        val versionCode: Long,
        val versionName: String,
        val builtAt: String,
        val apkUrl: String,
        val digestUrl: String,
        val digest: String,
        val notes: String,
    )

    fun parse(releases: JSONArray): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft") || !release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            if (!tagPattern.matches(tag)) continue
            val body = release.optString("body")
            fun field(key: String): String? =
                body.lineSequence().map(String::trim)
                    .firstOrNull { it.startsWith("$key=") }
                    ?.substringAfter('=')?.trim()
            if (field("updaterSchema") != "2" || field("artifactKind") != "experiment") continue
            if (field("baseBranch") != BASE_BRANCH || field("sourceBranch") != SOURCE_BRANCH) continue
            if (field("applicationId") != PACKAGE_NAME) continue
            if (field("certificateSha256")?.lowercase() != CERT_SHA256) continue
            val versionCode = field("versionCode")?.toLongOrNull() ?: continue
            if (versionCode <= 0 || tag.substringAfter("-v").substringBefore("-").toLongOrNull() != versionCode) continue
            val versionName = field("versionName")?.takeIf { it.length in 1..100 } ?: continue
            val digest = field("sha256")?.lowercase()?.takeIf(digestPattern::matches) ?: continue
            val builtAt = field("builtAt")?.takeIf { it.matches(Regex("[0-9TZ:._+-]{16,40}")) } ?: "unknown"
            val prefix = "https://github.com/$REPOSITORY/releases/download/$tag/"
            val assets = release.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            var shaUrl: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val url = asset.optString("browser_download_url")
                when (asset.optString("name")) {
                    APK_NAME -> if (url == prefix + APK_NAME) apkUrl = url
                    "$APK_NAME.sha256" -> if (url == prefix + "$APK_NAME.sha256") shaUrl = url
                }
            }
            if (apkUrl == null || shaUrl == null) continue
            val notes = field("summary")?.take(300) ?: "本次更新包含已审查的 Link Plus Trace 优化。"
            candidates.add(Candidate(tag, versionCode, versionName, builtAt, apkUrl, shaUrl, digest, notes))
        }
        return candidates.sortedWith(compareByDescending<Candidate> { it.versionCode }.thenByDescending { it.tag })
    }

    fun newest(releases: JSONArray, installed: Long): Candidate? =
        parse(releases).firstOrNull { it.versionCode > installed }
}
