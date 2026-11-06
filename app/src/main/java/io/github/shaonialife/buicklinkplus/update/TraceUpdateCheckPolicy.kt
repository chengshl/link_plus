package io.github.shaonialife.buicklinkplus.update

/**
 * Auto-check scheduling must never treat a failed/offline attempt as a
 * successful 24-hour check. User-initiated checks bypass this cooldown.
 */
internal object TraceUpdateCheckPolicy {
    const val SUCCESS_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val FAILURE_RETRY_MS = 10L * 60 * 1000

    fun shouldAutoCheck(nowMs: Long, lastSuccessMs: Long, lastAttemptMs: Long): Boolean {
        if (lastSuccessMs > 0 && nowMs >= lastSuccessMs &&
            nowMs - lastSuccessMs < SUCCESS_INTERVAL_MS) return false
        if (lastAttemptMs > 0 && nowMs >= lastAttemptMs &&
            nowMs - lastAttemptMs < FAILURE_RETRY_MS) return false
        return true
    }
}
