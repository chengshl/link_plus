package io.github.shaonialife.buicklinkplus.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTraceFormatTest {
    @Test
    fun csmExposesStructureNotSecrets() {
        val secret = "SECRET_CAR_WIFI_PASSWORD".toByteArray()
        val body = byteArrayOf(0, (secret.size + 4).toByte(), 0, 2) + secret
        val trace = ProtocolTraceFormat.csm("RX", 0x4301, "CARPLAY_START_SESSION", body)
        assertTrue(trace.contains("id=0x4301"))
        assertTrue(trace.contains("2:${secret.size}"))
        assertFalse(trace.contains("SECRET"))
    }

    @Test
    fun rtspExcludesQueryAndReasonPhrase() {
        val trace = ProtocolTraceFormat.rtsp("RX", "POST", "/pair-setup?token=secret", 41, 77, "RTSP/1.0 403 Forbidden", 123)
        assertTrue(trace.contains("path=/pair-setup"))
        assertTrue(trace.contains("cseq=41"))
        assertTrue(trace.contains("status=403"))
        assertFalse(trace.contains("secret"))
        assertFalse(trace.contains("Forbidden"))
    }

    @Test
    fun inboundEventUsesLocalCorrelationWhenRemoteCseqMissing() {
        val received = ProtocolTraceFormat.eventCorrelation(42L, -1, 91)
        val next = ProtocolTraceFormat.eventCorrelation(43L, 7, 170)
        assertTrue(received.contains("local=42 remoteCseq=absent bytes=91"))
        assertTrue(next.contains("local=43 remoteCseq=7 bytes=170"))
        assertFalse(received.contains("cseq=42"))
    }

    @Test
    fun unknownCsmPayloadDoesNotLeak() {
        val trace = ProtocolTraceFormat.csm("RX", 0xaa03, "MFI_RESPONSE", "private-key".toByteArray())
        assertTrue(trace.contains("params=opaque"))
        assertFalse(trace.contains("private-key"))
    }
}
