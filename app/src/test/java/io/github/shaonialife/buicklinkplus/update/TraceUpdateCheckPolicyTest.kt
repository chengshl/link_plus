package io.github.shaonialife.buicklinkplus.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceUpdateCheckPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun freshInstallChecksImmediately() {
        assertTrue(TraceUpdateCheckPolicy.shouldAutoCheck(now, 0L, 0L))
    }

    @Test fun successIsNotRepeatedWithin24Hours() {
        assertFalse(TraceUpdateCheckPolicy.shouldAutoCheck(now, now - 60_000L, now - 60_000L))
        assertFalse(TraceUpdateCheckPolicy.shouldAutoCheck(now, now - 23L * 60 * 60 * 1000, now - 700_000L))
        assertTrue(TraceUpdateCheckPolicy.shouldAutoCheck(now, now - 24L * 60 * 60 * 1000, now - 700_000L))
    }

    @Test fun offlineFailureRetriesAtTenMinutesNot24Hours() {
        val oldSuccess = now - 26L * 60 * 60 * 1000
        val failedAt = now - 9L * 60 * 1000
        assertFalse(TraceUpdateCheckPolicy.shouldAutoCheck(now, oldSuccess, failedAt))
        assertTrue(TraceUpdateCheckPolicy.shouldAutoCheck(now + 60_000L, oldSuccess, failedAt))
    }

    @Test fun unexpectedClockChangeDoesNotSuppressChecksFor24Hours() {
        assertTrue(TraceUpdateCheckPolicy.shouldAutoCheck(now, now + 5000, now + 5000))
    }
}
