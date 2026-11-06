package io.github.shaonialife.buicklinkplus.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayLogBufferVehicleTest {
    @Test fun capturesVehicleAtOfferTimeAndNeverMixesCarBatches() {
        val idA = "74a97cc7-51f6-4e10-acd7-e9d4c3a25130"
        val idB = "75b50dd8-2a71-4e3a-9bca-1b0ac96f1d04"
        var current: String? = idA
        val buffer = GatewayLogBuffer(LogRedactor(), vehicleProvider = { current })
        buffer.offer("A first")
        current = idB
        buffer.offer("B second")
        current = null
        buffer.offer("unassigned third")
        assertEquals(listOf(GatewayLogBuffer.TaggedLine("A first", idA)), buffer.takeTagged())
        val b = buffer.takeTagged()
        assertEquals(listOf(GatewayLogBuffer.TaggedLine("B second", idB)), b)
        buffer.retryTagged(b)
        current = idA
        assertEquals(b, buffer.takeTagged())
        assertEquals(listOf(GatewayLogBuffer.TaggedLine("unassigned third", null)), buffer.takeTagged())
        assertEquals(emptyList<GatewayLogBuffer.TaggedLine>(), buffer.takeTagged())
    }
}
