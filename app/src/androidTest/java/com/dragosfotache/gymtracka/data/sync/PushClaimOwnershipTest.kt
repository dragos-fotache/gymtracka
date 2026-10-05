package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.store.PushClaim
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06a independent ownership acceptance: single-process mutual exclusion
 * across caller HTTP, queued cancellation, nested/closed/wrong-run misuse,
 * enclosing Room transactions, structured children, self-cancel discipline
 * and release on every failure path. Real Room, no mocked DAOs, no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushClaimOwnershipTest : GymDatabaseTest() {

    private suspend fun seedClaimable() {
        database.claimSeedDevice()
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        val store = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
    }

    @Test
    fun constructor_rejectsSignedOutAndZeroGeneration() = runTest {
        seedClaimable()
        try {
            database.claimPushStore(RepositorySession.SIGNED_OUT)
            fail("Signed-out construction must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            database.claimPushStore(claimSession(generation = 0L))
            fail("Zero generation construction must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun negativeEpoch_rejectsBeforeBlockAndMutation() = runTest {
        seedClaimable()
        var entered = false
        val before = database.claimSnapshot()
        try {
            database.claimPushStore().withRun(-1L) {
                entered = true
            }
            fail("Negative cache epoch must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(entered)
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun sharedOwner_serializesRunsIncludingCallerHttp() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = async {
            database.claimPushStore(owner = owner).withRun(0L) { run ->
                events += "first-enter"
                run.claimHead(CLAIM_NOW_MILLIS)
                events += "first-claimed"
                entered.complete(Unit)
                gate.await()
                events += "first-exit"
            }
        }
        entered.await()
        val second = async {
            database.claimPushStore(owner = owner).withRun(0L) {
                events += "second-enter"
            }
        }
        runCurrent()
        assertEquals(listOf("first-enter", "first-claimed"), events)

        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-enter", "first-claimed", "first-exit", "second-enter"), events)
    }

    @Test
    fun queuedCancellation_performsNoRoomWork() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val before = database.claimSnapshot()

        val first = async {
            database.claimPushStore(owner = owner).withRun(0L) {
                entered.complete(Unit)
                gate.await()
            }
        }
        entered.await()
        val second = async {
            database.claimPushStore(owner = owner).withRun(0L) {
                fail("Cancelled run must never enter")
            }
        }
        runCurrent()
        second.cancel()
        try {
            second.await()
            fail("Queued run must observe cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(before, database.claimSnapshot())

        gate.complete(Unit)
        first.await()
        // Ownership released: a fresh run claims normally.
        var claimed = false
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            claimed = run.claimHead(CLAIM_NOW_MILLIS) is PushHeadResult.Claimed
        }
        assertTrue(claimed)
    }

    @Test
    fun nestedWithRun_rejectsBeforeWaiting() = runTest {
        seedClaimable()
        val owner = claimOwner()

        database.claimPushStore(owner = owner).withRun(0L) {
            try {
                withTimeout(5_000L) {
                    database.claimPushStore(owner = owner).withRun(0L) { }
                }
                fail("Nested same-owner entry must be rejected")
            } catch (_: IllegalStateException) {
            }
            try {
                withTimeout(5_000L) {
                    database.claimPushStore(owner = claimOwner()).withRun(0L) { }
                }
                fail("Nested different-owner entry must be rejected")
            } catch (_: IllegalStateException) {
            }
        }

        // No deadlock and no leak: the owner admits a fresh run.
        var claimed = false
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            claimed = run.claimHead(CLAIM_NOW_MILLIS) is PushHeadResult.Claimed
        }
        assertTrue(claimed)
    }

    @Test
    fun enclosingRoomTransaction_rejectsEntry() = runTest {
        seedClaimable()
        database.claimSeedDevice()
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        val before = database.claimSnapshot()

        captureStore.withFencedTransaction {
            try {
                database.claimPushStore().withRun(0L) { }
                fail("Entry from an active Room transaction must be rejected")
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun closedCapability_rejectsClaimAndValidation() = runTest {
        seedClaimable()
        val store = database.claimPushStore()
        var claimed: PushHeadResult.Claimed? = null
        store.withRun(0L) { run ->
            claimed = run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed
        }

        // The block closed: neither claim nor validation escapes it.
        val leaked = claimed!!
        try {
            withTimeout(5_000L) {
                store.withRun(0L) { run ->
                    run.checkClaim(leaked.claim)
                }
            }
            fail("Closed-run claim must not validate in another run")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun leakedRunHandle_rejectsUseAfterClosure() = runTest {
        seedClaimable()
        val owner = claimOwner()
        var leakedRun: PushRun? = null
        var leakedClaim: PushClaim? = null
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            leakedRun = run
            leakedClaim = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
        }
        assertTrue(leakedClaim != null)
        val before = database.claimSnapshot()

        // The retained run handle is closed: neither claim nor validation
        // escapes the block, and neither mutates storage.
        try {
            withTimeout(5_000L) {
                leakedRun!!.claimHead(CLAIM_NOW_MILLIS)
            }
            fail("Closed run must not claim")
        } catch (_: IllegalStateException) {
        }
        try {
            withTimeout(5_000L) {
                leakedRun!!.checkClaim(leakedClaim!!)
            }
            fail("Closed run must not validate")
        } catch (_: IllegalStateException) {
        }
        assertEquals(before, database.claimSnapshot())

        // The legitimate in-block claim froze the head exactly once.
        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)
        assertEquals("frozen", head?.state)
        assertEquals(1, head?.attemptCount)
    }

    @Test
    fun nestedDifferentOwner_rejectsWhileOriginatingRunStaysValid() = runTest {
        seedClaimable()
        val owner = claimOwner()

        database.claimPushStore(owner = owner).withRun(0L) { first ->
            val claimed = (first.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            // Nested entry with a different owner must reject before waiting.
            val secondStore = database.claimPushStore(owner = claimOwner())
            try {
                withTimeout(5_000L) {
                    secondStore.withRun(0L) { second ->
                        second.checkClaim(claimed)
                    }
                }
                fail("Foreign-run capability must be rejected")
            } catch (_: IllegalStateException) {
            }
            // The originating live run still validates.
            first.checkClaim(claimed)
        }
        // The in-block claims froze the head exactly once; nothing else moved.
        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)
        assertEquals("frozen", head?.state)
        assertEquals(1, head?.attemptCount)
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun structuredChildren_completeBeforeNextRunEnters() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val gate = CompletableDeferred<Unit>()
        var childDone = false
        val secondEntered = CompletableDeferred<Unit>()

        val entered = CompletableDeferred<Unit>()
        val first = async {
            database.claimPushStore(owner = owner).withRun(0L) {
                entered.complete(Unit)
                // Structured child of the admitted run, parented to the block's
                // own Job: it must settle before ownership passes on, even
                // though the block itself returns first.
                CoroutineScope(currentCoroutineContext()).async {
                    gate.await()
                    childDone = true
                }
                "returned-while-child-pending"
            }
        }
        entered.await()
        val second = async {
            database.claimPushStore(owner = owner).withRun(0L) {
                secondEntered.complete(Unit)
            }
        }
        runCurrent()
        assertFalse(secondEntered.isCompleted)
        gate.complete(Unit)
        first.await()
        second.await()
        assertTrue(childDone)
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun selfCancelledBlock_returnIsNotAcceptedAsSuccess() = runTest {
        seedClaimable()
        val owner = claimOwner()

        // The caller block cancels its own Job and swallows the resulting
        // CancellationException. withRun must still check cancellation before
        // normal return instead of reporting the swallowed value.
        val runner = async {
            database.claimPushStore(owner = owner).withRun(0L) { run ->
                val claimed = run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed
                assertEquals(CLAIM_MUTATION_1, claimed.claim.row.mutationId)
                currentCoroutineContext()[Job]?.cancel(CancellationException("self-cancel"))
                try {
                    currentCoroutineContext().ensureActive()
                } catch (_: CancellationException) {
                    // Caller swallows; the run must not accept success.
                }
                "swallowed-success"
            }
        }
        runCurrent()
        try {
            runner.await()
            fail("Self-cancelled run must surface cancellation")
        } catch (_: CancellationException) {
        }

        // Owner released and already committed frozen work remains durable.
        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)
        assertEquals("frozen", head?.state)
        assertEquals(1, head?.attemptCount)
        var replayed = false
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            val next = run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed
            assertEquals(2, next.claim.row.attemptCount)
            assertEquals(head?.payloadJson, next.claim.row.payloadJson)
            replayed = true
        }
        assertTrue(replayed)
    }

    @Test
    fun ownership_releasedAfterCallerFault() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val boom = IllegalStateException("caller-boom")

        try {
            database.claimPushStore(owner = owner).withRun(0L) {
                throw boom
            }
            fail("Caller fault must propagate")
        } catch (error: IllegalStateException) {
            assertEquals("caller-boom", error.message)
        }

        var claimed = false
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            claimed = run.claimHead(CLAIM_NOW_MILLIS) is PushHeadResult.Claimed
        }
        assertTrue(claimed)
    }

    @Test
    fun claimHarness_provesClaimReplayAdmissionWithoutAcknowledgment() = runTest {
        seedClaimable()
        val transport = ClaimTestTransport(database)
        val owner = claimOwner()

        database.claimPushStore(owner = owner).withRun(0L) { run ->
            val result = run.claimHead(CLAIM_NOW_MILLIS)
            val claimed = result as PushHeadResult.Claimed
            run.checkClaim(claimed.claim)
            transport.push(listOf(claimed.claim.request))
            run.checkClaim(claimed.claim)
        }

        // No acknowledgment is performed: the row stays frozen with one claim.
        assertEquals(1, transport.calls.size)
        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)
        assertEquals("frozen", head?.state)
        assertEquals(1, head?.attemptCount)
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun captureInsideClaimBlock_usesSeparateCapturePath() = runTest {
        database.claimSeedDevice()
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        database.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)

        // Ordinary domain capture still works alongside the claim boundary.
        captureStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT,
                CLAIM_WORKOUT_A,
                MutationKind.WORKOUT_UPSERT,
                changed = false,
            ) {
                MutationPayload.WorkoutUpsert(
                    com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel(
                        CLAIM_WORKOUT_A,
                        "Claimed Workout",
                        "active",
                        CLAIM_STARTED_MILLIS,
                        emptyList(),
                    ),
                )
            }
        }
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }
}
