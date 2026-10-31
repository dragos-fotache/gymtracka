package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.store.PushFailureResult
import com.dragosfotache.gymtracka.data.sync.store.PushOutcomeResult
import com.dragosfotache.gymtracka.data.sync.store.pushRetryAtMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic JVM checks for durable retry backoff, hints, caps, overflow and fixed safe
 * diagnostics using literal expected values.
 */
class PushRetryPolicyTest {

    @Test
    fun invalidTimeAndCount_throwBeforeArithmetic() {
        val badCalls: List<Triple<String, Long, Int>> = listOf(
            Triple("negative-now", -1L, 1),
            Triple("long-min-now", Long.MIN_VALUE, 1),
            Triple("zero-count", 0L, 0),
            Triple("negative-count", 0L, -3),
            Triple("both-bad", -7L, 0),
        )
        badCalls.forEach { (label, now, count) ->
            try {
                pushRetryAtMillis(now, count, null)
                fail("$label must throw IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
            try {
                pushRetryAtMillis(now, count, 1_000L)
                fail("$label with hint must throw IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun backoff_doublesPerAdmissionCappedAtFiveMinutes() {
        // min(1000 * 2^(n-1), 300000) as absolute times from now = 0.
        val cases = listOf(
            1 to 1_000L,
            2 to 2_000L,
            3 to 4_000L,
            4 to 8_000L,
            5 to 16_000L,
            6 to 32_000L,
            7 to 64_000L,
            8 to 128_000L,
            9 to 256_000L,
            10 to 300_000L,
            11 to 300_000L,
            30 to 300_000L,
            1_000 to 300_000L,
        )
        cases.forEach { (admissions, expected) ->
            assertEquals(
                "admission $admissions",
                expected,
                pushRetryAtMillis(0L, admissions, null),
            )
        }
    }

    @Test
    fun backoffAtIntMax_doesNotOverflow() {
        assertEquals(300_000L, pushRetryAtMillis(0L, Int.MAX_VALUE, null))
        assertEquals(300_000L, pushRetryAtMillis(0L, Int.MAX_VALUE, 0L))
        assertEquals(300_000L, pushRetryAtMillis(0L, Int.MAX_VALUE, 300_000L))
    }

    @Test
    fun hints_clampToZeroAndCapBeforeComparison() {
        // Admission 1 backs off 1000; delay is max(backoff, clamped hint).
        val cases = listOf(
            Triple("null-hint", null, 1_000L),
            Triple("negative-hint", -5L, 1_000L),
            Triple("min-hint", Long.MIN_VALUE, 1_000L),
            Triple("zero-hint", 0L, 1_000L),
            Triple("below-backoff", 500L, 1_000L),
            Triple("equal-backoff", 1_000L, 1_000L),
            Triple("above-backoff", 5_000L, 5_000L),
            Triple("at-cap", 300_000L, 300_000L),
            Triple("above-cap", 300_001L, 300_000L),
            Triple("huge-hint", 1_000_000_000L, 300_000L),
            Triple("max-hint", Long.MAX_VALUE, 300_000L),
        )
        cases.forEach { (label, hint, expected) ->
            assertEquals(label, expected, pushRetryAtMillis(0L, 1, hint))
        }
    }

    @Test
    fun delay_isCappedAfterMax() {
        // Capped backoff (300000) still wins over small hints and caps large ones.
        assertEquals(300_000L, pushRetryAtMillis(0L, 10, 0L))
        assertEquals(300_000L, pushRetryAtMillis(0L, 10, 1_000L))
        assertEquals(300_000L, pushRetryAtMillis(0L, 10, 999_999L))
        assertEquals(300_000L, pushRetryAtMillis(0L, Int.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun absoluteTime_addsDelayWithCheckedOverflow() {
        assertEquals(6_000L, pushRetryAtMillis(5_000L, 1, null))
        assertEquals(10_000L, pushRetryAtMillis(5_000L, 1, 5_000L))
        assertEquals(Long.MAX_VALUE, pushRetryAtMillis(Long.MAX_VALUE - 1_000L, 1, null))
        assertNull(pushRetryAtMillis(Long.MAX_VALUE - 999L, 1, null))
        assertNull(pushRetryAtMillis(Long.MAX_VALUE, 1, null))
        assertNull(pushRetryAtMillis(Long.MAX_VALUE, 1, 0L))
        assertNull(pushRetryAtMillis(Long.MAX_VALUE, Int.MAX_VALUE, null))
        assertNull(pushRetryAtMillis(Long.MAX_VALUE - 299_999L, Int.MAX_VALUE, null))
        assertEquals(Long.MAX_VALUE, pushRetryAtMillis(Long.MAX_VALUE - 300_000L, Int.MAX_VALUE, null))
    }

    @Test
    fun results_areDeterministicWithoutJitter() {
        val first = pushRetryAtMillis(1_790_258_500_000L, 3, 1_500L)
        val second = pushRetryAtMillis(1_790_258_500_000L, 3, 1_500L)
        assertEquals(1_790_258_500_000L + 4_000L, first)
        assertEquals(first, second)
        assertEquals(
            pushRetryAtMillis(0L, 9, 300_000L),
            pushRetryAtMillis(0L, 9, 300_000L),
        )
    }

    @Test
    fun failureDiagnostics_neverLeakUnknownCodes() {
        val rejected = PushOutcomeResult.Rejected("sentinel-secret-code")
        assertEquals("sentinel-secret-code", rejected.errorCode)
        assertFalse(rejected.toString().contains("sentinel-secret-code"))
        assertTrue(rejected.toString().isNotBlank())

        val retained = PushFailureResult.Retained("another-secret-code")
        assertEquals("another-secret-code", retained.errorCode)
        assertFalse(retained.toString().contains("another-secret-code"))
        assertTrue(retained.toString().isNotBlank())

        val scheduled = PushFailureResult.RetryScheduled(4_000L)
        assertEquals(4_000L, scheduled.retryAfterMillis)
        assertTrue(scheduled.toString().isNotBlank())

        assertTrue(PushFailureResult.AuthenticationRequired.toString().isNotBlank())
        assertTrue(PushFailureResult.ReauthenticationRequired.toString().isNotBlank())
        assertTrue(PushOutcomeResult.Malformed.toString().isNotBlank())
    }
}
