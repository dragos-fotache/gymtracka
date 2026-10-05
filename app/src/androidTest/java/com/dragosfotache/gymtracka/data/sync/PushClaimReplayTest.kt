package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.store.ExtraAffectedRoot
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.PushClaim
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.PushStorageException
import com.dragosfotache.gymtracka.data.sync.store.StalePushClaimException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06a independent replay/validation acceptance: frozen-head replay across
 * runs/reopen/new owners, exact checkClaim fencing per tampered field,
 * defensive request ownership, later-edit preservation, trigger rollback,
 * caller-fault provenance and cancellation/transient recovery. Real Room, no
 * mocked DAOs, no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushClaimReplayTest : GymDatabaseTest() {

    private suspend fun seedClaimable() {
        database.claimSeedDevice()
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        val store = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
    }

    private suspend fun claimInFreshRun(
        epoch: Long = 0L,
        owner: com.dragosfotache.gymtracka.data.sync.store.PushRunOwner = claimOwner(),
    ): PushClaim {
        var claimed: PushClaim? = null
        database.claimPushStore(owner = owner).withRun(epoch) { run ->
            claimed = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
        }
        return claimed!!
    }

    @Test
    fun legacyFrozenZero_replaysIdenticallyAndNeverCoalesces() = runTest {
        seedClaimable()
        val captured = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        // Legacy primitive froze without an admission count; attempts stay zero.
        database.syncDao().freezePendingMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)
        assertEquals(0, database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)?.attemptCount)

        val first = claimInFreshRun()
        assertEquals("frozen", first.row.state)
        assertEquals(1, first.row.attemptCount)
        assertEquals(captured.payloadJson, first.row.payloadJson)
        assertEquals(captured.mutationId, first.request.envelope.mutationId)

        val second = claimInFreshRun()
        assertEquals(2, second.row.attemptCount)
        assertEquals(first.request.requestJson, second.request.requestJson)
        assertEquals(first.request.affectedRoots, second.request.affectedRoots)
        assertEquals(first.request.envelope, second.request.envelope)
        assertEquals(first.row.payloadJson, second.row.payloadJson)

        // A frozen head never becomes coalescible again: autosaves append.
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        val outcome = captureStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT,
                CLAIM_WORKOUT_A,
                com.dragosfotache.gymtracka.data.sync.model.MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) {
                com.dragosfotache.gymtracka.data.sync.model.MutationPayload.WorkoutUpsert(
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
        assertEquals(false, outcome?.coalesced)
        assertEquals(2, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
        assertEquals(captured.payloadJson, database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)?.payloadJson)
    }

    @Test
    fun checkClaim_acceptsUnchangedHeadAcrossTransport() = runTest {
        seedClaimable()
        val transport = ClaimTestTransport(database)

        database.claimPushStore().withRun(0L) { run ->
            val result = run.claimHead(CLAIM_NOW_MILLIS)
            assertTrue(result is PushHeadResult.Claimed)
            val claimed = (result as PushHeadResult.Claimed).claim
            run.checkClaim(claimed)
            transport.push(listOf(claimed.request))
            run.checkClaim(claimed)
        }
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun checkClaim_rejectsEachTamperedRowField() = runTest {
        seedClaimable()
        val owner = claimOwner()

        database.claimPushStore(owner = owner).withRun(0L) { run ->
            val live = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val sequence = live.row.sequence
            val original = live.row

            fun set(assignment: String, args: Array<Any?> = emptyArray(), whereSeq: Long = sequence) {
                database.claimExec(
                    "UPDATE sync_outbox SET $assignment WHERE sequence = $whereSeq",
                    args,
                )
            }

            // Each field is tampered independently against the live run's own
            // claim, then restored; the restored row validates again.
            val cases: List<Triple<String, Pair<String, Array<Any?>>, Pair<String, Array<Any?>>>> = listOf(
                Triple("account", Pair("account_id = ?", arrayOf<Any?>(CLAIM_ACCOUNT_B)), Pair("account_id = ?", arrayOf<Any?>(original.accountId))),
                Triple("mutationId", Pair("mutation_id = ?", arrayOf<Any?>("aaaaaaaa-1111-1111-1111-111111111111")), Pair("mutation_id = ?", arrayOf<Any?>(original.mutationId))),
                Triple("aggregateType", Pair("aggregate_type = ?", arrayOf<Any?>("routine")), Pair("aggregate_type = ?", arrayOf<Any?>(original.aggregateType))),
                Triple("aggregateId", Pair("aggregate_id = ?", arrayOf<Any?>(CLAIM_WORKOUT_B)), Pair("aggregate_id = ?", arrayOf<Any?>(original.aggregateId))),
                Triple("mutationType", Pair("mutation_type = ?", arrayOf<Any?>("workout.finish")), Pair("mutation_type = ?", arrayOf<Any?>(original.mutationType))),
                Triple("localGeneration", Pair("local_generation = ?", arrayOf<Any?>(99L)), Pair("local_generation = ?", arrayOf<Any?>(original.localGeneration))),
                Triple("payloadVersion", Pair("payload_version = ?", arrayOf<Any?>(2)), Pair("payload_version = ?", arrayOf<Any?>(original.payloadVersion))),
                Triple("wrapperBytes", Pair("payload_json = ?", arrayOf<Any?>(original.payloadJson.replaceFirst("Claimed Workout", "Claimed Workxut"))), Pair("payload_json = ?", arrayOf<Any?>(original.payloadJson))),
                Triple("state", Pair("state = ?", arrayOf<Any?>("pending")), Pair("state = ?", arrayOf<Any?>(original.state))),
                Triple("attemptCount", Pair("attempt_count = ?", arrayOf<Any?>(7)), Pair("attempt_count = ?", arrayOf<Any?>(original.attemptCount))),
                Triple("retryAfter", Pair("retry_after_millis = ?", arrayOf<Any?>(12_345L)), Pair("retry_after_millis = ?", arrayOf<Any?>(original.retryAfterMillis))),
                Triple("errorCode", Pair("error_code = ?", arrayOf<Any?>("gone")), Pair("error_code = ?", arrayOf<Any?>(original.errorCode))),
                Triple("errorMessage", Pair("error_message = ?", arrayOf<Any?>("secret-bytes")), Pair("error_message = ?", arrayOf<Any?>(original.errorMessage))),
            )
            cases.forEach { (label, tamper, restore) ->
                set(tamper.first, tamper.second)
                try {
                    run.checkClaim(live)
                    fail("$label tamper must invalidate the callback")
                } catch (_: StalePushClaimException) {
                }
                set(restore.first, restore.second)
                try {
                    run.checkClaim(live)
                } catch (error: StalePushClaimException) {
                    throw AssertionError("$label restore must validate again", error)
                }
            }

            // The moved primary key restores by its moved value: the original
            // WHERE matches no row after the move.
            val moved = sequence + 100
            set("sequence = ?", arrayOf<Any?>(moved))
            try {
                run.checkClaim(live)
                fail("Sequence tamper must invalidate the callback")
            } catch (_: StalePushClaimException) {
            }
            set("sequence = ?", arrayOf<Any?>(sequence), whereSeq = moved)
            try {
                run.checkClaim(live)
            } catch (error: StalePushClaimException) {
                throw AssertionError("Sequence restore must validate again", error)
            }

            // No substitution or repair happened across the failed checks.
            assertEquals(original, database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
        }
    }

    @Test
    fun checkClaim_rejectsStaleCallbackAfterReadmission() = runTest {
        seedClaimable()
        val owner = claimOwner()
        var second: PushClaim? = null
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            val first = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            // Same live run re-admits the retained frozen head with unchanged
            // wire bytes but a new admission count.
            second = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val current = checkNotNull(second)
            assertEquals(first.request.requestJson, current.request.requestJson)
            assertEquals(1, first.row.attemptCount)
            assertEquals(2, current.row.attemptCount)
            try {
                run.checkClaim(first)
                fail("Prior admission count must invalidate the older callback")
            } catch (_: StalePushClaimException) {
            }
            run.checkClaim(current)
        }
        // The failed validation substituted nothing and removed nothing.
        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals(2, head.attemptCount)
        assertEquals(second!!.row, head)
    }

    @Test
    fun checkClaim_rejectsMissingAndReplacedHead() = runTest {
        seedClaimable()
        val owner = claimOwner()

        database.claimPushStore(owner = owner).withRun(0L) { run ->
            val stale = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val sequence = stale.row.sequence

            // Missing head.
            database.claimExec("DELETE FROM sync_outbox WHERE sequence = $sequence")
            try {
                run.checkClaim(stale)
                fail("Missing head must invalidate the callback")
            } catch (_: StalePushClaimException) {
            }

            // Replaced head: a brand-new row becomes head; the old callback
            // must fail and must never be substituted with the new head.
            val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
            database.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
            try {
                run.checkClaim(stale)
                fail("Replaced head must invalidate the callback")
            } catch (_: StalePushClaimException) {
            }
            val replacement = database.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A)!!
            assertEquals("pending", replacement.state)
            assertEquals(0, replacement.attemptCount)
            assertFalse(replacement.sequence == sequence)
        }
    }

    @Test
    fun claimedRequest_cannotBeModifiedThroughExposedLists() = runTest {
        // Multi-root fixture: when the exposed list permits mutation, the
        // external change is exercised on more than a singleton element.
        database.claimSeedDevice()
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        database.claimSeedWorkout(CLAIM_WORKOUT_OLD, CLAIM_ACCOUNT_A)
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        database.claimCaptureStart(
            captureStore,
            CLAIM_ACCOUNT_A,
            CLAIM_WORKOUT_A,
            "Claimed Workout",
            extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, CLAIM_WORKOUT_OLD)),
        )
        val claim = claimInFreshRun()
        val snapshot = claim.request.affectedRoots.map {
            Triple(it.aggregateType, it.aggregateId, it.localGeneration)
        }
        assertEquals(2, snapshot.size)
        val jsonBefore = claim.request.requestJson

        // A read-only view may reject mutation outright (Kotlin is-MutableList
        // is no proof the runtime list permits it); a mutable exposure must be
        // deep-owned. Either way the retained claim identity is unaffected.
        val exposed = claim.request.affectedRoots
        try {
            if (exposed is MutableList) {
                exposed.clear()
                exposed.add(
                    AffectedRootModel(
                        AggregateType.ROUTINE,
                        "intruder",
                        9L,
                    ),
                )
            }
        } catch (_: UnsupportedOperationException) {
            // Immutable view: mutation rejected at the boundary.
        }

        // Fresh getters still expose the retained claim identity.
        assertEquals(
            snapshot,
            claim.request.affectedRoots.map { Triple(it.aggregateType, it.aggregateId, it.localGeneration) },
        )
        assertEquals(jsonBefore, claim.request.requestJson)
        database.claimPushStore().withRun(0L) { run ->
            val fresh = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(jsonBefore, fresh.request.requestJson)
            assertEquals(
                snapshot,
                fresh.request.affectedRoots.map { Triple(it.aggregateType, it.aggregateId, it.localGeneration) },
            )
        }
    }

    @Test
    fun laterLocalEdits_preserveFrozenPredecessor() = runTest {
        seedClaimable()
        val claim = claimInFreshRun()
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        database.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Edited")

        val rows = database.claimOutboxFor(CLAIM_ACCOUNT_A)
        assertEquals(2, rows.size)
        assertEquals(claim.row, rows[0])
        assertEquals("pending", rows[1].state)
        assertEquals(2L, rows[1].localGeneration)
        // The frozen predecessor is untouched and still validates.
        assertEquals(claim.request.requestJson, rows[0].payloadJson.let { JSONObject(it).getString("requestJson") })
        var validated = false
        database.claimPushStore().withRun(0L) { run ->
            val current = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(claim.request.requestJson, current.request.requestJson)
            run.checkClaim(current)
            validated = true
        }
        assertTrue(validated)
        // No root is marked synced by claiming or by later edits.
        assertEquals(
            0,
            database.claimCount("SELECT COUNT(*) FROM workouts WHERE sync_state = 'synced'"),
        )
        assertEquals("pending", database.syncCaptureDao().getWorkoutMetadata(CLAIM_WORKOUT_A)?.syncState)
    }

    @Test
    fun triggerFailureAfterUpdate_rollsBackFreezeAndIncrement() = runTest {
        seedClaimable()
        database.claimBlockOutboxUpdates()
        try {
            try {
                claimInFreshRun()
                fail("Trigger failure must surface as owned storage failure")
            } catch (error: PushStorageException) {
                // Fixed safe error: no raw trigger/SQL text may leak.
                val message = error.message ?: ""
                assertFalse(message.contains("push_claim_blocked"))
                assertFalse(message.contains("RAISE"))
                assertFalse(message.contains("sync_outbox"))
                assertNull(error.cause)
            }
        } finally {
            database.claimDropOutboxTrigger()
        }

        // Rollback left both freeze and increment unchanged.
        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals("pending", head.state)
        assertEquals(0, head.attemptCount)

        // Recovery: a fresh run claims normally after the fault is removed.
        val recovered = claimInFreshRun()
        assertEquals(1, recovered.row.attemptCount)
        assertEquals("frozen", recovered.row.state)
    }

    @Test
    fun callerFault_keepsProvenanceAndFrozenWork() = runTest {
        seedClaimable()
        val boom = IllegalStateException("caller-boom")

        try {
            database.claimPushStore().withRun(0L) { run ->
                run.claimHead(CLAIM_NOW_MILLIS)
                throw boom
            }
            fail("Caller fault must propagate")
        } catch (error: IllegalStateException) {
            assertSame(boom, error)
        }

        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
    }

    @Test
    fun callerSqliteFailure_notMislabeledAsStorageFailure() = runTest {
        seedClaimable()

        try {
            database.claimPushStore().withRun(0L) { run ->
                run.claimHead(CLAIM_NOW_MILLIS)
                // Caller-origin SQLite failure inside the caller block.
                database.syncDao().insertOutbox(
                    SyncOutboxEntity(
                        mutationId = CLAIM_MUTATION_1,
                        accountId = CLAIM_ACCOUNT_A,
                        aggregateType = "workout",
                        aggregateId = CLAIM_WORKOUT_A,
                        mutationType = "workout.upsert",
                        localGeneration = 9L,
                        payloadVersion = 1,
                        payloadJson = "{}",
                    ),
                )
                fail("Duplicate mutation insert must abort")
            }
            fail("Caller SQLite failure must propagate")
        } catch (error: SQLiteConstraintException) {
            assertEquals(SQLiteConstraintException::class.java, error::class.java)
        }

        // The committed freeze from the same run is untouched by the late fault.
        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
    }

    @Test
    fun lostResponse_preservesUncertainWorkForReclaim() = runTest {
        seedClaimable()
        val transport = ClaimTestTransport(
            database,
            ClaimTransportBehavior.ThrowTransient(1_500L),
        )

        try {
            database.claimPushStore().withRun(0L) { run ->
                val claimed = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
                run.checkClaim(claimed)
                transport.push(listOf(claimed.request))
                fail("Transient transport failure must propagate")
            }
            fail("Transient must propagate")
        } catch (error: SyncApiException.Transient) {
            assertEquals(1_500L, error.retryAfterMillis)
        }

        // Uncertain work is preserved unchanged: still frozen, same bytes.
        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        val replayed = claimInFreshRun()
        assertEquals(2, replayed.row.attemptCount)
        assertEquals(head.payloadJson, replayed.row.payloadJson)
        assertEquals(head.payloadJson.let { JSONObject(it).getString("requestJson") }, replayed.request.requestJson)
    }

    @Test
    fun cancellationDuringTransport_preservesWorkAndReleasesOwner() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val enteredPush = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val transport = ClaimTestTransport(
            database,
            ClaimTransportBehavior.Gate(gate, ClaimTransportBehavior.ReturnEmpty),
        )
        transport.onPushEntry = { enteredPush.complete(Unit) }

        val runner = async {
            database.claimPushStore(owner = owner).withRun(0L) { run ->
                val claimed = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
                transport.push(listOf(claimed.request))
            }
        }
        enteredPush.await()
        runner.cancel()
        try {
            runner.await()
            fail("Cancelled transport must surface cancellation")
        } catch (_: CancellationException) {
        }
        gate.complete(Unit)

        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        val replayed = claimInFreshRun(owner = owner)
        assertEquals(2, replayed.row.attemptCount)
        assertEquals(head.payloadJson, replayed.row.payloadJson)
    }

    @Test
    fun responseAfterCancellation_isIgnoredWithoutSideEffects() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val enteredPush = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val transport = ClaimTestTransport(
            database,
            ClaimTransportBehavior.Gate(gate, ClaimTransportBehavior.ReturnRejected("gone")),
        )
        transport.onPushEntry = { enteredPush.complete(Unit) }

        var leakedRun: PushRun? = null
        var leakedClaim: PushClaim? = null
        val runner = async {
            database.claimPushStore(owner = owner).withRun(0L) { run ->
                leakedRun = run
                leakedClaim = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val forTransport = checkNotNull(leakedClaim)
                transport.push(listOf(forTransport.request))
            }
        }
        enteredPush.await()
        runner.cancel()
        try {
            runner.await()
        } catch (_: CancellationException) {
        }
        // The late response genuinely arrives after cancellation.
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, transport.lateResponses)

        // The canceled caller cannot validate with it: no success, no writes.
        try {
            leakedRun!!.checkClaim(leakedClaim!!)
            fail("Canceled run must not validate a late response")
        } catch (_: CancellationException) {
        } catch (_: IllegalStateException) {
        }

        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun lateResponseReachesCancelledCaller_withoutSuccessOrWrites() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val transport = ClaimAdversarialTransport(database)
        val enteredPush = CompletableDeferred<Unit>()
        transport.onPushEntry = { enteredPush.complete(Unit) }
        var leakedRun: PushRun? = null
        var leakedClaim: PushClaim? = null
        var sawResponse = false
        var checkedCancellation = false

        val runner = async {
            database.claimPushStore(owner = owner).withRun(0L) { run ->
                leakedRun = run
                leakedClaim = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val forTransport = checkNotNull(leakedClaim)
                transport.push(listOf(forTransport.request))
                sawResponse = true
                // Validation inside the actual canceled caller must surface
                // cancellation, not success, before withRun rejects return.
                try {
                    run.checkClaim(checkNotNull(leakedClaim))
                    fail("Canceled caller must surface cancellation")
                } catch (_: CancellationException) {
                    checkedCancellation = true
                }
            }
        }
        enteredPush.await()
        runner.cancel()
        // Release the gate only after cancellation: the shielded transport
        // still delivers its response into the canceled caller.
        transport.gate.complete(Unit)
        transport.responseReturned.await()
        assertEquals(1, transport.responsesReturned)
        assertTrue(sawResponse)
        try {
            runner.await()
            fail("Canceled run must surface cancellation despite the late response")
        } catch (_: CancellationException) {
        }
        assertTrue(checkedCancellation)

        // The closed handle cannot validate after closure either: no writes.
        try {
            checkNotNull(leakedRun).checkClaim(checkNotNull(leakedClaim))
            fail("Closed run must not validate")
        } catch (_: CancellationException) {
        } catch (_: IllegalStateException) {
        }

        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun cancellationBeforeTransport_leavesHeadUnclaimed() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val runner = async {
            database.claimPushStore(owner = owner).withRun(0L) {
                entered.complete(Unit)
                gate.await()
                fail("Cancelled waiter must never claim")
            }
        }
        entered.await()
        runner.cancel()
        try {
            runner.await()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
        }
        gate.complete(Unit)

        val head = database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
        assertEquals("pending", head.state)
        assertEquals(0, head.attemptCount)
    }

    @Test
    fun cancellationInsideUncommittedClaim_rollsBackFreezeAndIncrement() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val armed = AtomicBoolean(false)
        lateinit var runnerJob: Job
        // Test-only real Room query hook: cancel the owning Job when the
        // claim freeze UPDATE is issued, still inside the uncommitted claim.
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (armed.get() && sql.contains("sync_outbox", ignoreCase = true) &&
                        sql.contains("update", ignoreCase = true)
                    ) {
                        armed.set(false)
                        runnerJob.cancel(CancellationException("claim-test mid-claim cancel"))
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.claimSeedDevice()
            callbackDb.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
            val captureStore = callbackDb.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
            callbackDb.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
            val owner = claimOwner()
            armed.set(true)
            val runner = async {
                callbackDb.claimPushStore(owner = owner).withRun(0L) { run ->
                    run.claimHead(CLAIM_NOW_MILLIS)
                }
            }
            runnerJob = runner
            try {
                runner.await()
                fail("Mid-claim cancellation must propagate")
            } catch (_: CancellationException) {
            }
            // The uncommitted freeze rolled back: still pending, zero attempts.
            val head = callbackDb.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
            assertEquals("pending", head.state)
            assertEquals(0, head.attemptCount)
            // Owner released; disarmed recovery claims normally.
            var replayed = false
            callbackDb.claimPushStore(owner = owner).withRun(0L) { run ->
                val next = run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed
                assertEquals(1, next.claim.row.attemptCount)
                assertEquals("frozen", next.claim.row.state)
                replayed = true
            }
            assertTrue(replayed)
        } finally {
            callbackDb.close()
        }
    }

    @Test
    fun diskReopen_preservesFrozenHeadForNewOwner() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("reopen")
        val fileDatabase = claimFileDatabase(context, name)
        try {
            fileDatabase.claimSeedDevice()
            fileDatabase.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
            val store = fileDatabase.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
            val captured = fileDatabase.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
            assertEquals("pending", captured.state)
            val transport = ClaimTestTransport(fileDatabase)

            fileDatabase.claimPushStore().withRun(0L) { run ->
                val claimed = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
                run.checkClaim(claimed)
                transport.push(listOf(claimed.request))
                run.checkClaim(claimed)
            }
            assertEquals(1, transport.calls.size)
        } finally {
            fileDatabase.close()
        }

        // Simulated process restart: a new owner recovers the identical head.
        val reopened = claimFileDatabase(context, name)
        try {
            reopened.claimSeedDevice()
            var replayedBytes = ""
            reopened.claimPushStore().withRun(0L) { run ->
                val next = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
                replayedBytes = next.request.requestJson
                assertEquals(2, next.row.attemptCount)
                assertEquals("frozen", next.row.state)
                run.checkClaim(next)
            }
            val captured = reopened.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
            assertEquals(
                JSONObject(captured.payloadJson).getString("requestJson"),
                replayedBytes,
            )
            assertEquals(1, reopened.claimOutboxFor(CLAIM_ACCOUNT_A).size)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun blockedHead_neverSelectsSuccessorOrRepairs() = runTest {
        seedClaimable()
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A)
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        database.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Later")
        database.claimExec(
            "UPDATE sync_outbox SET state = 'rejected', error_code = 'gone' WHERE mutation_id = ? AND account_id = ?",
            arrayOf<Any?>(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A),
        )
        val before = database.claimSnapshot()

        database.claimPushStore().withRun(0L) { run ->
            val result = run.claimHead(CLAIM_NOW_MILLIS)
            val blocked = result as PushHeadResult.Blocked
            assertEquals(PushBlockReason.REJECTED, blocked.reason)
            assertEquals("gone", blocked.errorCode)
        }

        assertEquals(before, database.claimSnapshot())
    }

}
