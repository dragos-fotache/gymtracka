package com.dragosfotache.gymtracka.ui.account

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountRetryTest {
    @Test
    fun fiveMinuteDelay_waitsUntilEquality_andRoundsCountdownUp() = runTest {
        val remaining = mutableListOf<Long>()
        var retried = false
        launch {
            awaitAccountRetry(300_000L, { testScheduler.currentTime }, remaining::add)
            retried = true
        }
        runCurrent()
        assertEquals(300L, remaining.last())
        advanceTimeBy(299_999L)
        assertFalse(retried)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(retried)
        assertEquals(0L, remaining.last())
        assertEquals("Sync will retry in 5m 0s while the app is open.", accountRetryText(300L))
        assertEquals("Sync will retry in 1s while the app is open.", accountRetryText(1L))
    }

    @Test
    fun cancellationDuringWait_neverRetries() = runTest {
        var retried = false
        val job = launch {
            awaitAccountRetry(300_000L, { testScheduler.currentTime }) { }
            retried = true
        }
        runCurrent()
        job.cancel()
        advanceTimeBy(300_000L)
        runCurrent()
        assertFalse(retried)
    }

    @Test
    fun backwardWallClock_rechecksDeadline_andForwardJumpFinishes() = runTest {
        var now = 9_999L
        var retried = false
        val remaining = mutableListOf<Long>()
        launch {
            awaitAccountRetry(10_000L, { now }, remaining::add)
            retried = true
        }
        runCurrent()
        assertEquals(1L, remaining.last())
        now = 8_000L
        advanceTimeBy(1L)
        runCurrent()
        assertFalse(retried)
        assertEquals(2L, remaining.last())
        now = 10_001L
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(retried)
    }
}
