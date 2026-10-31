package com.dragosfotache.gymtracka.ui.settings

import com.dragosfotache.gymtracka.data.sync.api.ServerChecker
import com.dragosfotache.gymtracka.data.sync.api.ServerCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectionStateTest {
    @Test
    fun onlyConfirmedOfflineToOnline_emitsOneReconnect_acrossCheckingAndForegroundReturn() = runTest {
        var answer = ServerCheckResult.CONNECTED
        val state = ServerConnectionState(ServerChecker { answer }, backgroundScope)
        state.setActive(true)
        runCurrent()
        assertEquals(0L, state.reconnections.value)
        state.recheck()
        runCurrent()
        assertEquals(0L, state.reconnections.value)
        answer = ServerCheckResult.UNREACHABLE
        state.recheck()
        runCurrent()
        state.setActive(false)
        answer = ServerCheckResult.CONNECTED
        state.setActive(true)
        runCurrent()
        assertEquals(1L, state.reconnections.value)
        state.recheck()
        runCurrent()
        assertEquals(1L, state.reconnections.value)
        answer = ServerCheckResult.SERVER_ERROR
        state.recheck()
        runCurrent()
        answer = ServerCheckResult.CONNECTED
        state.recheck()
        runCurrent()
        assertEquals(1L, state.reconnections.value)
        state.dispose()
    }

    @Test
    fun inertUntilEntry_duplicatesRefuse_recheckIsExplicit() = runTest {
        var calls = 0
        var answer = CompletableDeferred<ServerCheckResult>()
        val state = ServerConnectionState(ServerChecker { calls++; answer.await() }, backgroundScope)
        state.recheck()
        runCurrent()
        assertEquals(0, calls)
        assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
        state.setActive(true)
        state.setActive(true)
        state.recheck()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(ServerConnectionStatus.CHECKING, state.status.value)
        answer.complete(ServerCheckResult.CONNECTED)
        runCurrent()
        assertEquals(ServerConnectionStatus.CONNECTED, state.status.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
        answer = CompletableDeferred()
        state.recheck()
        state.recheck()
        runCurrent()
        assertEquals(2, calls)
        answer.complete(ServerCheckResult.UNREACHABLE)
        runCurrent()
        assertEquals(ServerConnectionStatus.UNREACHABLE, state.status.value)
    }

    @Test
    fun syncFailureRechecksAndFencesAnOlderProbe_withoutOverlappingOrCheckingWhileInactive() = runTest {
        val old = CompletableDeferred<ServerCheckResult>()
        val fresh = CompletableDeferred<ServerCheckResult>()
        var calls = 0
        val state = ServerConnectionState(
            ServerChecker {
                when (++calls) {
                    1 -> ServerCheckResult.CONNECTED
                    2 -> withContext(NonCancellable) { old.await() }
                    3 -> fresh.await()
                    else -> ServerCheckResult.CONNECTED
                }
            },
            backgroundScope,
        )
        state.refreshAfterSyncFailure()
        runCurrent()
        assertEquals(0, calls)
        state.setActive(true)
        runCurrent()
        assertEquals(ServerConnectionStatus.CONNECTED, state.status.value)
        state.recheck()
        runCurrent()
        state.refreshAfterSyncFailure()
        state.refreshAfterSyncFailure()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ServerConnectionStatus.CHECKING, state.status.value)
        old.complete(ServerCheckResult.CONNECTED)
        runCurrent()
        assertEquals(3, calls)
        assertEquals(ServerConnectionStatus.CHECKING, state.status.value)
        fresh.complete(ServerCheckResult.UNREACHABLE)
        runCurrent()
        assertEquals(ServerConnectionStatus.UNREACHABLE, state.status.value)
        state.recheck()
        runCurrent()
        assertEquals(ServerConnectionStatus.CONNECTED, state.status.value)
        state.setActive(false)
        state.refreshAfterSyncFailure()
        state.dispose()
        state.refreshAfterSyncFailure()
        runCurrent()
        assertEquals(4, calls)
        assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
    }

    @Test
    fun resultsStayDistinctAndDoNotRetryAutomatically() = runTest {
        val expected = mapOf(
            ServerCheckResult.CONNECTED to ServerConnectionStatus.CONNECTED,
            ServerCheckResult.UNREACHABLE to ServerConnectionStatus.UNREACHABLE,
            ServerCheckResult.SERVER_ERROR to ServerConnectionStatus.SERVER_ERROR,
            ServerCheckResult.UNEXPECTED_RESPONSE to ServerConnectionStatus.UNEXPECTED_RESPONSE,
        )
        for ((result, status) in expected) {
            var calls = 0
            val state = ServerConnectionState(ServerChecker { calls++; result }, backgroundScope)
            state.setActive(true)
            runCurrent()
            assertEquals(status, state.status.value)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(1, calls)
            state.dispose()
        }
    }

    @Test
    fun timeoutCancelsProbeAndAllowsManualRetry() = runTest {
        var cancellations = 0
        val state = ServerConnectionState(
            ServerChecker {
                try { awaitCancellation() } finally { cancellations++ }
            },
            backgroundScope,
            timeoutMillis = 500,
        )
        state.setActive(true)
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertEquals(ServerConnectionStatus.CHECKING, state.status.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, cancellations)
        assertEquals(ServerConnectionStatus.UNREACHABLE, state.status.value)
        state.recheck()
        runCurrent()
        assertEquals(ServerConnectionStatus.CHECKING, state.status.value)
        state.dispose()
        runCurrent()
        assertEquals(2, cancellations)
    }

    @Test
    fun leavingOrBackgroundingCancels_entryAndForegroundReturnCheckFresh() = runTest {
        var calls = 0
        var cancellations = 0
        val state = ServerConnectionState(
            ServerChecker {
                calls++
                try { awaitCancellation() } finally { cancellations++ }
            },
            backgroundScope,
        )
        repeat(2) {
            state.setActive(true)
            runCurrent()
            state.setActive(false)
            runCurrent()
            assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
            state.recheck()
        }
        assertEquals(2, calls)
        assertEquals(2, cancellations)
        var scopeStillAlive = false
        backgroundScope.launch { scopeStillAlive = true }
        runCurrent()
        assertTrue(scopeStillAlive)
    }

    @Test
    fun resistantLateResultCannotPublishOrOverlapFreshEntry() = runTest {
        val old = CompletableDeferred<ServerCheckResult>()
        val fresh = CompletableDeferred<ServerCheckResult>()
        var calls = 0
        var active = 0
        var peak = 0
        val state = ServerConnectionState(
            ServerChecker {
                val call = ++calls
                active++
                peak = maxOf(peak, active)
                try {
                    if (call == 1) withContext(NonCancellable) { old.await() } else fresh.await()
                } finally {
                    active--
                }
            },
            backgroundScope,
        )
        state.setActive(true)
        runCurrent()
        state.setActive(false)
        state.setActive(true)
        state.recheck()
        runCurrent()
        assertEquals(1, calls)
        old.complete(ServerCheckResult.CONNECTED)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ServerConnectionStatus.CHECKING, state.status.value)
        assertEquals(1, peak)
        fresh.complete(ServerCheckResult.UNREACHABLE)
        runCurrent()
        assertEquals(ServerConnectionStatus.UNREACHABLE, state.status.value)
    }

    @Test
    fun disposalFencesResistantResultAndDoesNotCancelBorrowedScope() = runTest {
        val answer = CompletableDeferred<ServerCheckResult>()
        var calls = 0
        val state = ServerConnectionState(
            ServerChecker { calls++; withContext(NonCancellable) { answer.await() } },
            backgroundScope,
        )
        state.setActive(true)
        runCurrent()
        state.dispose()
        state.dispose()
        state.setActive(true)
        state.recheck()
        answer.complete(ServerCheckResult.CONNECTED)
        runCurrent()
        assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
        assertEquals(1, calls)
        var scopeStillAlive = false
        backgroundScope.launch { scopeStillAlive = true }
        runCurrent()
        assertTrue(scopeStillAlive)
    }

    @Test
    fun cancellationIsNotConnectivityFailureAndUnexpectedExceptionIsClosed() = runTest {
        var cancel = true
        val state = ServerConnectionState(
            ServerChecker {
                if (cancel) throw CancellationException("cancelled")
                throw IllegalStateException("sensitive details")
            },
            backgroundScope,
        )
        state.setActive(true)
        runCurrent()
        assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
        cancel = false
        state.recheck()
        runCurrent()
        assertEquals(ServerConnectionStatus.UNEXPECTED_RESPONSE, state.status.value)
        assertFalse(state.status.value.toString().contains("sensitive"))
    }

    @Test
    fun borrowedScopeCancellationRetiresProbeAndRefusesFurtherEntry() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        var cancelled = false
        val state = ServerConnectionState(
            ServerChecker {
                try { awaitCancellation() } finally { cancelled = true }
            },
            scope,
        )
        state.setActive(true)
        runCurrent()
        scope.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
        state.setActive(true)
        state.recheck()
        runCurrent()
        assertEquals(ServerConnectionStatus.NOT_CHECKED, state.status.value)
    }
}
