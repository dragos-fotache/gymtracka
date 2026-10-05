package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.store.PushFailureResult
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushOutcomeResult
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.PushClaim
import com.dragosfotache.gymtracka.data.sync.store.PushStorageException
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StalePushClaimException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06b independent fence acceptance: stale exact-row/session/epoch checks,
 * foreign/closed capability use, caller cancellation, late responses with
 * newer tail edits, trigger-induced rollback and unrelated-table isolation.
 * Real Room, no mocked DAOs, no sleeps.
 */
class PushOutcomeFenceTest : GymDatabaseTest() {

    private suspend fun seedClaimable() {
        database.outcomeSeedFenced()
        database.outcomeCaptureHead()
    }

    @Test
    fun staleClaim_rejectsEveryBranchBeforeMutation() = runTest {
        val branches: List<Pair<String, suspend (PushRun, PushClaim) -> Any?>> = listOf(
            "accepted" to { run, claim ->
                run.handleResult(claim, outcomeAcceptedResult(claim.row.mutationId))
            },
            "rejected" to { run, claim ->
                run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
            },
            "malformed" to { run, claim ->
                run.handleResult(claim, PushResultModel(emptyList()))
            },
            "transient" to { run, claim ->
                run.handleFailure(claim, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
            },
            "too-large" to { run, claim ->
                run.handleFailure(claim, SyncApiException.RequestTooLarge(), OUTCOME_NOW_MILLIS)
            },
            "auth" to { run, claim ->
                run.handleFailure(claim, SyncApiException.AuthenticationRequired(), OUTCOME_NOW_MILLIS)
            },
        )
        branches.forEach { (label, action) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            seedClaimable()
            database.claimPushStore().withRun(0L) { run ->
                val first = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                // Readmission consumes the first callback: same bytes, new count.
                val second = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(first.request.requestJson, second.request.requestJson)
                try {
                    action(run, first)
                    fail("$label with a stale claim must be rejected")
                } catch (_: StalePushClaimException) {
                }
                // The live callback still validates; the failed use wrote nothing.
                run.checkClaim(second)
                assertEquals(second.row, database.outcomeRow())
            }
        }
    }

    @Test
    fun tamperedRow_rejectsHandlingWithoutWrites() = runTest {
        val tampers = listOf(
            "mutation-id" to "mutation_id = 'aaaaaaaa-1111-1111-1111-111111111111'",
            "attempt-count" to "attempt_count = 7",
            "wrapper-bytes" to "payload_json = payload_json || ' '",
            "state" to "state = 'pending'",
        )
        tampers.forEach { (label, assignment) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            seedClaimable()
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val sequence = claim.row.sequence
                database.claimExec("UPDATE sync_outbox SET $assignment WHERE sequence = $sequence")
                try {
                    run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
                    fail("$label tamper must invalidate result handling")
                } catch (_: StalePushClaimException) {
                }
                try {
                    run.handleFailure(claim, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
                    fail("$label tamper must invalidate failure handling")
                } catch (_: StalePushClaimException) {
                }
            }
        }
    }

    @Test
    fun sessionAndEpochChanges_rejectWithoutWrites() = runTest {
        val changes = listOf(
            Triple(
                "account-switch",
                "UPDATE sync_device_state SET active_account_id = '$CLAIM_ACCOUNT_B', session_generation = 99",
                StaleSessionException::class.java,
            ),
            Triple(
                "generation-bump",
                "UPDATE sync_device_state SET session_generation = 8",
                StaleSessionException::class.java,
            ),
            Triple(
                "sign-out",
                "UPDATE sync_device_state SET active_account_id = NULL",
                StaleSessionException::class.java,
            ),
            Triple(
                "cache-advance",
                "UPDATE sync_account_cache SET cache_epoch = 1 WHERE account_id = '$CLAIM_ACCOUNT_A'",
                StaleCanonicalEpochException::class.java,
            ),
        )
        changes.forEach { (label, sql, expected) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            database.claimExec("DELETE FROM sync_account_cache")
            seedClaimable()
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val live = database.outcomeRow()
                database.claimExec(sql)
                try {
                    run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
                    fail("$label must reject result handling")
                } catch (error: Exception) {
                    assertEquals("$label result type", expected, error::class.java)
                }
                try {
                    run.handleFailure(claim, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
                    fail("$label must reject failure handling")
                } catch (error: Exception) {
                    assertEquals("$label failure type", expected, error::class.java)
                }
                // Neither failed write moved the frozen head.
                assertEquals("$label row", live, database.outcomeRow())
            }
            database.claimSeedDevice()
        }
    }

    @Test
    fun negativeHandlingTime_rejectsBeforeMutation() = runTest {
        seedClaimable()
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val live = database.outcomeRow()
            listOf(
                SyncApiException.Transient(),
                SyncApiException.RequestTooLarge(),
                SyncApiException.AuthenticationRequired(),
            ).forEach { failure ->
                try {
                    run.handleFailure(claim, failure, -1L)
                    fail("Negative handling time must be rejected")
                } catch (_: IllegalArgumentException) {
                }
            }
            assertEquals(live, database.outcomeRow())
            // The live claim still validates after the rejected timestamps.
            run.checkClaim(claim)
        }
    }

    @Test
    fun foreignAndClosedCapability_rejectHandling() = runTest {
        seedClaimable()
        val owner = claimOwner()
        var leakedClaim: PushClaim? = null
        var leakedRun: PushRun? = null
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            leakedRun = run
            leakedClaim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
        }
        val before = database.claimSnapshot()

        // Another run (same owner, new block) must not accept the foreign claim.
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            val foreign = checkNotNull(leakedClaim)
            try {
                run.handleResult(foreign, outcomeRejectedResult(foreign.row.mutationId, "gone"))
                fail("Foreign-run result handling must be rejected")
            } catch (_: IllegalStateException) {
            }
            try {
                run.handleFailure(foreign, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
                fail("Foreign-run failure handling must be rejected")
            } catch (_: IllegalStateException) {
            }
        }

        // The closed run handle itself must not escape its block either.
        try {
            leakedRun!!.handleResult(
                checkNotNull(leakedClaim),
                outcomeRejectedResult(checkNotNull(leakedClaim).row.mutationId, "gone"),
            )
            fail("Closed-run result handling must be rejected")
        } catch (_: IllegalStateException) {
        }
        try {
            leakedRun!!.handleFailure(
                checkNotNull(leakedClaim),
                SyncApiException.Transient(),
                OUTCOME_NOW_MILLIS,
            )
            fail("Closed-run failure handling must be rejected")
        } catch (_: IllegalStateException) {
        }
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun selfCancelledCaller_surfacesCancellationWithoutWrites() = runTest {
        seedClaimable()
        val owner = claimOwner()
        val runner = async {
            database.claimPushStore(owner = owner).withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val live = database.outcomeRow()
                currentCoroutineContext()[Job]?.cancel(CancellationException("outcome-test self-cancel"))
                try {
                    currentCoroutineContext().ensureActive()
                } catch (_: CancellationException) {
                }
                try {
                    run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
                    fail("Self-cancelled handling must surface cancellation, never rejection")
                } catch (_: CancellationException) {
                }
                assertEquals(live, database.outcomeRow())
                "swallowed-success"
            }
        }
        try {
            runner.await()
            fail("Self-cancelled run must surface cancellation")
        } catch (_: CancellationException) {
        }

        // Owner released; the frozen head replays with unchanged bytes.
        val head = database.outcomeRow()
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        val replayed = database.outcomeFreshClaim()
        assertEquals(2, replayed.row.attemptCount)
        assertEquals(head.payloadJson, replayed.row.payloadJson)
    }

    @Test
    fun callerCancellationMidWrite_rollsBackWithoutConversion() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inHandle = AtomicBoolean(false)
        val updateSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        lateinit var runnerJob: Job
        // Test-only real Room query hook: track the handling bookkeeping UPDATE,
        // then cancel the original caller Job on the following head SELECT from
        // sync_outbox. The callback fires before SQL executes, so cancelling on
        // the UPDATE itself would only prove pre-execution interruption; the
        // post-write SELECT proves post-write cancellation rollback as specified.
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (inHandle.get() && sql.contains("sync_outbox", ignoreCase = true)) {
                        if (sql.contains("update", ignoreCase = true)) {
                            updateSeen.set(true)
                        } else if (updateSeen.getAndSet(false)) {
                            fired.set(true)
                            inHandle.set(false)
                            runnerJob.cancel(CancellationException("outcome-test post-write cancel"))
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.outcomeSeedFenced()
            callbackDb.outcomeCaptureHead()
            val owner = claimOwner()
            val runner = async {
                callbackDb.claimPushStore(owner = owner).withRun(0L) { run ->
                    val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                    val admitted = callbackDb.claimSnapshot()
                    inHandle.set(true)
                    try {
                        run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
                        fail("Mid-write cancellation must propagate, never rejection")
                    } catch (cancelled: CancellationException) {
                        // Provenance preserved and the uncommitted write rolled back.
                        inHandle.set(false)
                        assertEquals(admitted, callbackDb.claimSnapshot())
                        throw cancelled
                    }
                }
            }
            runnerJob = runner
            try {
                runner.await()
                fail("Mid-write cancellation must propagate to the caller")
            } catch (_: CancellationException) {
            }
            // The hook fired on the post-write head SELECT, after the UPDATE executed.
            assertTrue(fired.get())
            // Only the committed admission freeze stands; nothing else moved.
            val head = callbackDb.outcomeRow()
            assertEquals("frozen", head.state)
            assertEquals(1, head.attemptCount)
            assertNull(head.errorCode)
            assertNull(head.retryAfterMillis)
            // Fresh admission reclaims the same frozen request identity.
            var replayed = false
            callbackDb.claimPushStore(owner = owner).withRun(0L) { run ->
                val next = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(2, next.row.attemptCount)
                assertEquals(head.payloadJson, next.row.payloadJson)
                replayed = true
            }
            assertTrue(replayed)
        } finally {
            callbackDb.close()
        }
    }

    @Test
    fun lateTransportResponseAndTailEdit_preserveNewerWork() = runTest {
        database.outcomeSeedFenced()
        val head = database.outcomeCaptureHead()
        val gate = CompletableDeferred<Unit>()
        val transport = ClaimTestTransport(
            database,
            ClaimTransportBehavior.Gate(gate, ClaimTransportBehavior.ReturnRejected("gone")),
        )
        val enteredPush = CompletableDeferred<Unit>()
        transport.onPushEntry = { enteredPush.complete(Unit) }
        val owner = claimOwner()

        database.claimPushStore(owner = owner).withRun(0L) { run ->
            val first = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            // Genuine transport admission outside any Room transaction.
            val inFlight = async { transport.push(listOf(first.request)) }
            enteredPush.await()
            // Newer same-root upsert tail lands while the response is in flight.
            val tailStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
            val tail = database.claimCaptureUpsert(tailStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Edited Later")
            assertEquals(2L, tail.localGeneration)
            // Readmission for the same wire identity consumes the first callback...
            val second = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(first.request.requestJson, second.request.requestJson)
            // ...then the late response genuinely arrives.
            gate.complete(Unit)
            val late = inFlight.await()
            assertEquals(1, late.results.size)
            assertEquals(first.row.mutationId, late.results.single().mutationId)
            // The consumed first callback cannot apply the late response.
            try {
                run.handleResult(first, late)
                fail("Late response on a consumed claim must be stale")
            } catch (_: StalePushClaimException) {
            }
            // The live callback handles normally; the tail is byte-identical.
            val rejected = run.handleResult(second, late) as PushOutcomeResult.Rejected
            assertEquals("gone", rejected.errorCode)
            val tailAfter = database.outcomeRow(tail.mutationId)
            assertEquals(tail, tailAfter)
            assertEquals("pending", tailAfter.state)
            assertEquals(0, tailAfter.attemptCount)
            assertEquals(2L, tailAfter.localGeneration)
            val headAfter = database.outcomeRow(head.mutationId)
            assertEquals(head.payloadJson, headAfter.payloadJson)
            assertEquals("rejected", headAfter.state)
            assertEquals("gone", headAfter.errorCode)
            assertEquals(2, headAfter.attemptCount)
        }
    }

    @Test
    fun triggerFailure_rollsBackOutcomeAndRetryWithoutDiagnostics() = runTest {
        seedClaimable()
        // The trigger is installed after admission: claimHead commits its
        // freeze first, then each handling write aborts on the trigger.
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val preHandle = database.claimSnapshot()
            database.claimBlockOutboxUpdates()
            try {
                try {
                    run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
                    fail("Trigger failure must surface as owned storage failure")
                } catch (error: PushStorageException) {
                    val message = error.message ?: ""
                    assertFalse(message.contains("push_claim_blocked"))
                    assertFalse(message.contains("RAISE"))
                    assertFalse(message.contains("sync_outbox"))
                    assertNull(error.cause)
                }
            } finally {
                database.claimDropOutboxTrigger()
            }
            assertEquals(claim.row, database.outcomeRow())
            assertEquals(preHandle, database.claimSnapshot())
            // Consumed even though the write rolled back: no failed-write reuse.
            try {
                run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
                fail("Rolled-back write still consumes the callback token")
            } catch (_: StalePushClaimException) {
            }
            assertEquals(claim.row, database.outcomeRow())
        }

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(2, claim.row.attemptCount)
            assertEquals(CLAIM_MUTATION_1, claim.row.mutationId)
            database.claimBlockOutboxUpdates()
            try {
                try {
                    run.handleFailure(claim, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
                    fail("Trigger failure must surface as owned storage failure")
                } catch (error: PushStorageException) {
                    assertNull(error.cause)
                }
            } finally {
                database.claimDropOutboxTrigger()
            }
            assertEquals(claim.row, database.outcomeRow())
        }

        // Both handling writes rolled back; only the two admissions committed.
        val head = database.outcomeRow()
        assertEquals("frozen", head.state)
        assertEquals(2, head.attemptCount)
        assertNull(head.retryAfterMillis)
        assertNull(head.errorCode)

        // Recovery after the fault is removed claims and schedules normally.
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(3, claim.row.attemptCount)
            val scheduled = run.handleFailure(claim, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
                as PushFailureResult.RetryScheduled
            // Third admission backs off 4000 ms.
            assertEquals(OUTCOME_NOW_MILLIS + 4_000L, scheduled.retryAfterMillis)
        }
    }

    @Test
    fun postWriteFenceChange_rollsBackUncommittedHandling() = runTest {
        seedClaimable()
        // AFTER UPDATE triggers installed after admission: each handling write
        // fires its trip, the post-write fence aborts, and the write rolls back
        // while the earlier committed admission freeze stands.
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val preHandle = database.claimSnapshot()
            database.claimExec(
                "CREATE TRIGGER push_outcome_fence_trip AFTER UPDATE ON sync_outbox " +
                    "BEGIN UPDATE sync_device_state SET session_generation = 99; END",
            )
            try {
                try {
                    run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
                    fail("Fence-tripping trigger must abort result handling")
                } catch (_: StaleSessionException) {
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS push_outcome_fence_trip")
            }
            assertEquals(claim.row, database.outcomeRow())
            assertEquals(preHandle, database.claimSnapshot())
        }

        // Same rollback guarantee on the retry path with a cache-epoch trip.
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(2, claim.row.attemptCount)
            val preHandle = database.claimSnapshot()
            database.claimExec(
                "CREATE TRIGGER push_outcome_epoch_trip AFTER UPDATE ON sync_outbox " +
                    "BEGIN UPDATE sync_account_cache SET cache_epoch = 9 WHERE account_id = '$CLAIM_ACCOUNT_A'; END",
            )
            try {
                try {
                    run.handleFailure(claim, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
                    fail("Epoch-tripping trigger must abort failure handling")
                } catch (_: StaleCanonicalEpochException) {
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS push_outcome_epoch_trip")
            }
            assertEquals(claim.row, database.outcomeRow())
            assertEquals(preHandle, database.claimSnapshot())
        }

        // Recovery after both trigger faults are removed.
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(3, claim.row.attemptCount)
            assertEquals("frozen", claim.row.state)
        }
    }

    @Test
    fun unrelatedTables_surviveOutcomeWrites() = runTest {
        database.outcomeSeedFenced()
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_B)
        val foreignStore = database.claimCaptureStore(
            claimSession(CLAIM_ACCOUNT_B),
            mutableListOf(CLAIM_MUTATION_2),
        )
        database.claimSeedDevice(CLAIM_ACCOUNT_B, CLAIM_GENERATION)
        val foreign = database.claimCaptureStart(foreignStore, CLAIM_ACCOUNT_B, CLAIM_WORKOUT_B, "Foreign")
        database.claimSeedDevice()
        val local = database.outcomeCaptureHead()
        database.syncDao().upsertSyncState(
            com.dragosfotache.gymtracka.data.gym.SyncStateEntity(CLAIM_ACCOUNT_A, "cursor-9", 12345L),
        )

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val admitted = claim.row
            val preHandle = database.claimSnapshot()
            run.handleResult(claim, outcomeRejectedResult(claim.row.mutationId, "gone"))
            // Explicit expected head: only the fixed rejection bookkeeping changed.
            val expected = admitted.copy(
                state = "rejected",
                errorCode = "gone",
                errorMessage = OUTCOME_REJECTED_MESSAGE,
                retryAfterMillis = null,
            )
            assertEquals(expected, database.outcomeRow())
            val postWrite = database.claimSnapshot()
            // Restore ONLY head bookkeeping to the admitted values: the full-table
            // snapshot must then equal the pre-write snapshot, proving isolation.
            database.claimExec(
                "UPDATE sync_outbox SET state = 'frozen', error_code = NULL, " +
                    "error_message = NULL, retry_after_millis = NULL WHERE sequence = ${admitted.sequence}",
            )
            assertEquals(preHandle, database.claimSnapshot())
            // Restore the expected write and confirm the full post-write snapshot.
            database.claimExec(
                "UPDATE sync_outbox SET state = 'rejected', error_code = 'gone', " +
                    "error_message = '$OUTCOME_REJECTED_MESSAGE' WHERE sequence = ${admitted.sequence}",
            )
            assertEquals(postWrite, database.claimSnapshot())
            // Foreign rows, domain, cursor and cache are untouched throughout.
            assertEquals(foreign, database.syncDao().getOutboxMutation(foreign.mutationId, CLAIM_ACCOUNT_B))
            assertEquals("pending", database.syncDao().getOutboxMutation(foreign.mutationId, CLAIM_ACCOUNT_B)?.state)
            assertEquals("cursor-9", database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
            assertEquals(12345L, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis)
        }

        assertEquals(local.mutationId, database.outcomeRow().mutationId)
        assertEquals("rejected", database.outcomeRow().state)
        assertEquals("gone", database.outcomeRow().errorCode)
    }
}
