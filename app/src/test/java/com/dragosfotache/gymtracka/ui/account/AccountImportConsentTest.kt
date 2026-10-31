package com.dragosfotache.gymtracka.ui.account

import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementRequest
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time checks for one nonqueueing opaque import decision, cancellation release, stale-
 * token refusal and permanent close. Request data is neither displayed nor persisted.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountImportConsentTest {

    private companion object {
        const val ORIGIN = "https://sync-startup.example.test"
        const val ACCOUNT = "c13c9001-1111-4111-8111-111111111111"
        const val OPERATION = "d13c9001-1111-4111-8111-111111111111"
        const val MUTATION = "e13c9001-1111-4111-8111-111111111111"
    }

    private fun request(mutation: String = MUTATION): LegacyImportReplacementRequest =
        LegacyImportReplacementRequest(
            target = LegacyImportTarget(
                origin = ServiceOrigin.parse(ORIGIN),
                accountId = ACCOUNT,
                operationId = OPERATION,
            ),
            mutationId = mutation,
        )

    @Test
    fun confirmTrue_returnsTrueAndClearsPending() = runTest {
        val consent = AccountImportConsent()
        assertNull(consent.pending.value)
        val awaiting = async { consent.confirm(request()) }
        runCurrent()
        val decision = checkNotNull(consent.pending.value) { "confirm must publish opaque token" }
        consent.answer(decision, true)
        runCurrent()
        assertTrue(awaiting.await())
        assertNull(consent.pending.value)
    }

    @Test
    fun answerFalse_returnsFalseNeverProceeds() = runTest {
        val consent = AccountImportConsent()
        val awaiting = async { consent.confirm(request()) }
        runCurrent()
        val decision = checkNotNull(consent.pending.value)
        consent.answer(decision, false)
        runCurrent()
        assertFalse(awaiting.await())
        assertNull(consent.pending.value)
    }

    @Test
    fun callerCancellation_releasesTokenAndStaleCannotConsentFuture() = runTest {
        val consent = AccountImportConsent()
        val first = async { consent.confirm(request()) }
        runCurrent()
        val stale = checkNotNull(consent.pending.value)
        first.cancel()
        runCurrent()
        try {
            first.await()
            assertTrue("cancelled confirm must not succeed", false)
        } catch (_: CancellationException) {
        }
        assertNull(consent.pending.value)
        // Stale token answer after cancellation cannot pre-consent the next request.
        consent.answer(stale, true)
        runCurrent()
        assertNull(consent.pending.value)
        val second = async { consent.confirm(request("e13c9002-2222-4222-8222-222222222222")) }
        runCurrent()
        val fresh = checkNotNull(consent.pending.value) { "new decision required" }
        assertNotSame(stale, fresh)
        consent.answer(fresh, true)
        runCurrent()
        assertTrue(second.await())
    }

    @Test
    fun close_fencesPendingAndFutureRequests() = runTest {
        val consent = AccountImportConsent()
        val awaiting = async { consent.confirm(request()) }
        runCurrent()
        val stale = checkNotNull(consent.pending.value)
        consent.close()
        runCurrent()
        // Settled contract: close/caller cancellation throws CancellationException.
        var closedFailed = false
        try {
            awaiting.await()
            assertTrue("closed pending must not succeed", false)
        } catch (_: CancellationException) {
            closedFailed = true
        }
        assertTrue(closedFailed)
        assertNull(consent.pending.value)
        // Stale answer after close cannot open a future decision.
        consent.answer(stale, true)
        runCurrent()
        assertNull(consent.pending.value)
        var failed = false
        try {
            consent.confirm(request())
            runCurrent()
        } catch (_: CancellationException) {
            failed = true
        }
        assertTrue("confirm after close must fail fast, never hang as success", failed)
        assertNotNull(consent)
    }

    @Test
    fun sequentialDecisions_areIsolatedTokens() = runTest {
        val consent = AccountImportConsent()
        val first = async { consent.confirm(request()) }
        runCurrent()
        val tokenA = checkNotNull(consent.pending.value)
        consent.answer(tokenA, false)
        runCurrent()
        assertFalse(first.await())
        val second = async { consent.confirm(request()) }
        runCurrent()
        val tokenB = checkNotNull(consent.pending.value)
        assertNotSame(tokenA, tokenB)
        // Old token cannot answer the new decision.
        consent.answer(tokenA, true)
        runCurrent()
        assertNotNull(consent.pending.value)
        consent.answer(tokenB, true)
        runCurrent()
        assertTrue(second.await())
    }
}
