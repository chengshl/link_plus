package io.github.shaonialife.buicklinkplus.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceReleasePolicyTest {
    private val tag = "exp-link-plus-trace-v155-123456789abc"
    private val digest = "a".repeat(64)
    private val apkUrl = "https://github.com/chengshl/link_plus/releases/download/$tag/LinkPlus-Trace.apk"

    private fun candidate(bodyExtra: String = ""): JSONObject {
        val body = listOf(
            "updaterSchema=2",
            "artifactKind=experiment",
            "baseBranch=${TraceReleasePolicy.BASE_BRANCH}",
            "sourceBranch=${TraceReleasePolicy.SOURCE_BRANCH}",
            "applicationId=${TraceReleasePolicy.PACKAGE_NAME}",
            "versionCode=155",
            "versionName=0.0.9-trace-r4",
            "certificateSha256=${TraceReleasePolicy.CERT_SHA256}",
            "sha256=$digest",
            "builtAt=2026-11-07T00:30:00Z",
            "summary=诊断与在线升级改进",
            bodyExtra,
        ).joinToString("\n")
        val assets = JSONArray()
        assets.put(JSONObject().put("name", "LinkPlus-Trace.apk").put("browser_download_url", apkUrl))
        assets.put(JSONObject().put("name", "LinkPlus-Trace.apk.sha256").put("browser_download_url", "$apkUrl.sha256"))
        return JSONObject().put("draft", false).put("prerelease", true)
            .put("tag_name", tag).put("body", body).put("assets", assets)
    }

    @Test fun acceptsOnlySignedOwnTrack() {
        val parsed = TraceReleasePolicy.parse(JSONArray().put(candidate()))
        assertEquals(1, parsed.size)
        assertEquals(155L, parsed.first().versionCode)
        assertEquals(digest, parsed.first().digest)
        assertEquals(apkUrl, parsed.first().apkUrl)
        assertEquals("0.0.9-trace-r4", parsed.first().versionName)
    }

    @Test fun newerVersionRequired() {
        val json = JSONArray().put(candidate())
        assertEquals(155L, TraceReleasePolicy.newest(json, 154L)?.versionCode)
        assertNull(TraceReleasePolicy.newest(json, 155L))
    }

    @Test fun rejectsOtherSourceAndApplication() {
        val wrongApp = candidate().put("body", candidate().getString("body").replace(
            "applicationId=${TraceReleasePolicy.PACKAGE_NAME}",
            "applicationId=io.github.buick.e5.cardeck",
        ))
        val wrongSource = candidate().put("body", candidate().getString("body").replace(
            "sourceBranch=${TraceReleasePolicy.SOURCE_BRANCH}",
            "sourceBranch=experiment/buick-e5-updater-b-minimal",
        ))
        assertTrue(TraceReleasePolicy.parse(JSONArray().put(wrongApp).put(wrongSource)).isEmpty())
    }

    @Test fun rejectsWrongCertificateTagAndAssetHost() {
        val wrongCert = candidate().put("body", candidate().getString("body").replace(
            TraceReleasePolicy.CERT_SHA256, "f".repeat(64),
        ))
        val wrongTag = candidate().put("tag_name", "exp-buick-e5-update-155")
        val wrongAsset = candidate().getJSONArray("assets").getJSONObject(0)
            .put("browser_download_url", "https://example.com/fake.apk")
        assertTrue(TraceReleasePolicy.parse(JSONArray().put(wrongCert).put(wrongTag)).isEmpty())
        assertTrue(TraceReleasePolicy.parse(JSONArray().put(candidate().put("assets", JSONArray().put(wrongAsset)))).isEmpty())
    }

    @Test fun doesNotAcceptSchemaOneOrRetiredRelease() {
        val oldSchema = candidate().put("body", candidate().getString("body").replace("updaterSchema=2", "updaterSchema=1"))
        val retired = candidate().put("body", candidate().getString("body").replace("artifactKind=experiment", "artifactKind=historical"))
        assertTrue(TraceReleasePolicy.parse(JSONArray().put(oldSchema).put(retired)).isEmpty())
    }
}
