package com.dragosfotache.gymtracka.data.sync.store

/** Deliberate client cap for a computed push retry delay: five minutes. */
internal const val MAX_PUSH_RETRY_DELAY_MILLIS = 300_000L

/**
 * Pure bounded retry-time helper. Returns the absolute due time for admission
 * count [attemptCount] at [nowMillis] with an optional service hint, or null
 * when checked timestamp addition overflows so the caller retains instead of
 * scheduling an immediately due retry.
 *
 * Backoff after admission n is `min(1000 * 2^(n-1), 300000)` milliseconds;
 * the delay is `max(backoff, clamped hint)` capped to 300000 with no jitter.
 * Hints clamp into 0..300000. Nonnegative time and positive admission count are
 * required; violations throw [IllegalArgumentException]. No suspension, I/O or
 * randomness.
 */
internal fun pushRetryAtMillis(
    nowMillis: Long,
    attemptCount: Int,
    retryHintMillis: Long?,
): Long? {
    if (nowMillis < 0) {
        throw IllegalArgumentException("Push retry time must be nonnegative")
    }
    if (attemptCount <= 0) {
        throw IllegalArgumentException("Push retry admission count must be positive")
    }
    val hint = retryHintMillis?.coerceIn(0L, MAX_PUSH_RETRY_DELAY_MILLIS)
    // 1000 * 2^9 already exceeds the cap, so n >= 10 saturates without shifting.
    val backoff = if (attemptCount >= 10) {
        MAX_PUSH_RETRY_DELAY_MILLIS
    } else {
        1_000L shl (attemptCount - 1)
    }
    val delay = if (hint == null) backoff else maxOf(backoff, hint)
    val bounded = delay.coerceIn(0L, MAX_PUSH_RETRY_DELAY_MILLIS)
    if (nowMillis > Long.MAX_VALUE - bounded) {
        return null
    }
    return nowMillis + bounded
}
