package io.github.shaonialife.buicklinkplus.gateway

import org.junit.Assert.*
import org.junit.Test

class LogRedactorTest {
    @Test fun masksVehicleBluetoothNameInDiagnosticLogs() {
        val syntheticName = "BUICK_" + "1234"
        val result = LogRedactor().line("BT-C: connected to $syntheticName")
        assertFalse(result.contains(syntheticName))
        assertTrue(result.contains("[车机蓝牙名]"))
    }

    @org.junit.Test
    fun airplaySessionKeyFragmentsNeverLeaveTheDevice() {
        val redactor = LogRedactor()
        val line = "AIRPLAY paired writeKey=abcdef012345 readKey=abcdef987654"
        org.junit.Assert.assertEquals("[已隐藏含凭证或密钥的日志行]", redactor.line(line))
        org.junit.Assert.assertEquals("[已隐藏含凭证或密钥的日志行]", redactor.line("AirPlay READKEY=1234"))
    }

    @Test fun masksCredentialsAddressesIdentifiersAndControlCharacters() {
        val redactor = LogRedactor(listOf("exact-short-secret"))
        assertFalse(redactor.line("value=exact-short-secret").contains("exact-short-secret"))
        assertEquals("[已隐藏含凭证或密钥的日志行]", redactor.line("Authorization: Bearer sample"))
        val safe = redactor.line("192.0.2.200 00:11:22:33:44:55 user@example.com wss://example.org/?key=sample\n\u0000")
        assertFalse(safe.contains("192.0.2.200"))
        assertFalse(safe.contains("00:11:22"))
        assertFalse(safe.contains("user@example"))
        assertFalse(safe.contains("wss://"))
        assertFalse(safe.contains('\n'))
        assertFalse(safe.contains('\u0000'))
        assertFalse(redactor.line("ssid=private-wifi").contains("private-wifi"))
        assertFalse(redactor.line("blob=" + "a".repeat(64)).contains("a".repeat(32)))
    }

    @Test fun preservesLogEventTimeButStillMasksIpv6() {
        val redact = LogRedactor()
        val local = redact.line("[20:19:53.527] DropBox event 2026-10-23 20:19:53 SYSTEM_RESTART")
        assertTrue(local.contains("20:19:53.527"))
        assertTrue(local.contains("2026-10-23 20:19:53"))
        val utc = redact.line("2026-10-23T12:19:53.294Z system_server_crash")
        assertTrue(utc.contains("12:19:53.294Z"))
        val ip = redact.line("address=fe80::1234 and 2001:db8:85a3::8a2e:370:7334")
        assertFalse(ip.contains("fe80::1234"))
        assertFalse(ip.contains("2001:db8"))
        assertTrue(ip.contains("[IPv6]"))
        assertEquals("clock 20:19:53 masked [IPv6]",
            redact.line("clock 20:19:53 masked fe80::abcd"))

    }

    @Test fun boundsLinesAndDropsOldestUnderBackpressure() {
        val buffer = GatewayLogBuffer(LogRedactor(), 3)
        listOf("old", "new1", "new2", "new3").forEach(buffer::offer)
        assertEquals(1L, buffer.dropped)
        assertEquals(listOf("new1", "new2", "new3"), buffer.take())
        buffer.offer("x".repeat(9000))
        assertEquals(listOf("[超长日志已省略]"), buffer.take())
    }
}
