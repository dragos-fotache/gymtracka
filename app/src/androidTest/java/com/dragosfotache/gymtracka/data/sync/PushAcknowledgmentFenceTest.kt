package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.store.PushAcknowledgmentResult
import com.dragosfotache.gymtracka.data.sync.store.PushClaim
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushOutcomeResult
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.PushStorageException
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StalePushClaimException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06c independent fence acceptance: stale/exact-row/session/epoch checks,
 * foreign/closed/enclosing capability use, concurrent settlement, post-write
 * cancellation, trigger-induced rollback including watermark tampering, and
 * conservative token consumption. Real Room, no mocked DAOs, no sleeps.
 */
class PushAcknowledgmentFenceTest : GymDatabaseTest() {

    private suspend fun seedWorkoutHead(): String {
        database.ackSeedFenced()
        return database.ackCaptureWorkoutHead().mutationId
    }

    private suspend fun admitAndAccept(
        run: PushRun,
        batchRevision: Long = 7L,
    ): Pair<PushClaim, PushOutcomeResult.Accepted> {
        val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
        val accepted = run.handleResult(
            claim,
            ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, batchRevision))),
        ) as PushOutcomeResult.Accepted
        return claim to accepted
    }

    @Test
    fun staleClaim_rejectsAcknowledgmentBeforeMutation() = runTest {
        seedWorkoutHead()
        database.claimPushStore().withRun(0L) { run ->
            val first = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val (live, accepted) = admitAndAccept(run)
            assertEquals(first.request.requestJson, live.request.requestJson)
            // First callback was consumed by readmission: stale, never evidence.
            try {
                run.acknowledgeAccepted(first, accepted)
                fail("Stale claim must not acknowledge")
            } catch (_: StalePushClaimException) {
            }
            assertEquals(live.row, database.ackRow(live.row.mutationId))
            // Live callback still acknowledges normally.
            val result = run.acknowledgeAccepted(live, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.acknowledgedRoots)
        }
    }

    @Test
    fun tamperedRow_rejectsWithoutWrites() = runTest {
        val tampers = listOf(
            "mutation-id" to "mutation_id = 'aaaaaaaa-1111-1111-1111-111111111111'",
            "attempt-count" to "attempt_count = 7",
            "wrapper-bytes" to "payload_json = payload_json || ' '",
            "state" to "state = 'pending'",
        )
        tampers.forEach { (label, assignment) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.claimExec("DELETE FROM sync_account_cache")
            seedWorkoutHead()
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val accepted = run.handleResult(
                    claim,
                    ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange())),
                ) as PushOutcomeResult.Accepted
                // Tamper after the valid snapshot: the acknowledgment write
                // path revalidates the exact row and must see the mismatch.
                database.claimExec("UPDATE sync_outbox SET $assignment WHERE sequence = ${claim.row.sequence}")
                val tampered = database.claimSnapshot()
                try {
                    run.acknowledgeAccepted(claim, accepted)
                    fail("$label tamper must invalidate acknowledgment")
                } catch (_: StalePushClaimException) {
                }
                try {
                    assertEquals("$label no-writes", tampered, database.claimSnapshot())
                    assertNull("$label canonical", database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
                } catch (error: AssertionError) {
                    throw AssertionError("$label: ${error.message}", error)
                }
            }
        }
    }

    @Test
    fun sessionAndEpochChanges_rejectWithoutWrites() = runTest {
        val changes = listOf(
            "account-switch" to
                "UPDATE sync_device_state SET active_account_id = '$CLAIM_ACCOUNT_B', session_generation = 99",
            "generation-bump" to "UPDATE sync_device_state SET session_generation = 8",
            "sign-out" to "UPDATE sync_device_state SET active_account_id = NULL",
            "cache-advance" to
                "UPDATE sync_account_cache SET cache_epoch = 1 WHERE account_id = '$CLAIM_ACCOUNT_A'",
        )
        changes.forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.claimExec("DELETE FROM sync_account_cache")
            seedWorkoutHead()
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val live = database.ackRow()
                val accepted = run.handleResult(
                    claim,
                    ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange())),
                ) as PushOutcomeResult.Accepted
                database.claimExec(sql)
                try {
                    run.acknowledgeAccepted(claim, accepted)
                    fail("$label must reject acknowledgment")
                } catch (_: StaleSessionException) {
                } catch (_: StaleCanonicalEpochException) {
                }
                assertEquals("$label row", live.mutationId, database.ackRow().mutationId)
                assertNull("$label canonical", database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            }
            database.claimSeedDevice()
        }
    }

    @Test
    fun consumedForeignClosedAndEnclosing_reject() = runTest {
        seedWorkoutHead()
        val owner = claimOwner()
        var leakedClaim: PushClaim? = null
        var leakedRun: PushRun? = null
        var savedAccepted: PushOutcomeResult.Accepted? = null
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            leakedRun = run
            val (claim, accepted) = admitAndAccept(run)
            leakedClaim = claim
            savedAccepted = accepted
            val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.acknowledgedRoots)
        }
        // Consumed token cannot be reused, even with its own snapshot.
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            try {
                run.acknowledgeAccepted(leakedClaim!!, savedAccepted!!)
                fail("Consumed claim must not acknowledge again")
            } catch (_: StalePushClaimException) {
            } catch (_: IllegalStateException) {
            }
        }
        try {
            leakedRun!!.acknowledgeAccepted(leakedClaim!!, savedAccepted!!)
            fail("Closed-run acknowledgment must be rejected")
        } catch (_: IllegalStateException) {
        }

        // Foreign run handle must not accept the consumed claim either.
        database.claimPushStore(owner = owner).withRun(0L) { run ->
            try {
                run.acknowledgeAccepted(leakedClaim!!, savedAccepted!!)
                fail("Foreign-run acknowledgment must be rejected")
            } catch (_: StalePushClaimException) {
            } catch (_: IllegalStateException) {
            }
        }

        // Enclosing Room transaction rejects entry, following the R06a precedent
        // of accepting either owned-entry exception shape. Clear the fixtures
        // consumed above: reseeding over the surviving physical row would
        // collide on its primary key.
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM workouts")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.claimExec("DELETE FROM sync_account_cache")
        seedWorkoutHead()
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        val snapBefore = database.claimSnapshot()
        captureStore.withFencedTransaction {
            try {
                database.claimPushStore().withRun(0L) { run ->
                    val (claim, accepted) = admitAndAccept(run)
                    run.acknowledgeAccepted(claim, accepted)
                }
                fail("Entry from an active Room transaction must be rejected")
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals(snapBefore, database.claimSnapshot())
    }

    @Test
    fun concurrentAcknowledgments_settleOnce() = runTest {
        seedWorkoutHead()
        database.claimPushStore().withRun(0L) { run ->
            val (claim, accepted) = admitAndAccept(run)
            var successes = 0
            var stales = 0
            coroutineScope {
                // Each child contains its own expected stale outcome: an
                // uncaught child failure would cancel the parent scope before
                // the awaits below could classify it.
                val first = async {
                    try {
                        val result = run.acknowledgeAccepted(claim, accepted)
                            as PushAcknowledgmentResult.Acknowledged
                        assertEquals(1, result.acknowledgedRoots)
                        "acknowledged"
                    } catch (_: StalePushClaimException) {
                        "stale"
                    }
                }
                val second = async {
                    try {
                        val result = run.acknowledgeAccepted(claim, accepted)
                            as PushAcknowledgmentResult.Acknowledged
                        assertEquals(1, result.acknowledgedRoots)
                        "acknowledged"
                    } catch (_: StalePushClaimException) {
                        "stale"
                    }
                }
                listOf(first.await(), second.await()).forEach { outcome ->
                    when (outcome) {
                        "acknowledged" -> successes++
                        else -> stales++
                    }
                }
            }
            assertEquals(1, successes)
            assertEquals(1, stales)
            // Competing outcome/failure handling serializes too: the consumed
            // claim cannot be reused by any sibling branch.
            try {
                run.handleResult(claim, ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange())))
                fail("Consumed claim must be stale for results too")
            } catch (_: StalePushClaimException) {
            }
            try {
                run.handleFailure(claim, SyncApiException.Transient(), ACK_NOW_MILLIS)
                fail("Consumed claim must be stale for failures too")
            } catch (_: StalePushClaimException) {
            }
            try {
                run.checkClaim(claim)
                fail("Consumed claim must be stale for validation too")
            } catch (_: StalePushClaimException) {
            }
        }
        assertEquals(0, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun callerCancellationMidWrite_rollsBackAndConsumesToken() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inAck = AtomicBoolean(false)
        val writeSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        lateinit var runnerJob: Job
        // Test-only real Room query hook: observe the first acknowledgment
        // write (canonical/metadata/outbox), then cancel the caller Job on the
        // following query. Callbacks fire before SQL, so cancelling on the
        // write itself would only prove pre-execution interruption.
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (inAck.get()) {
                        val lower = sql.lowercase()
                        val isWrite = lower.contains("insert") || lower.contains("update") ||
                            lower.contains("delete")
                        val isAckTable = lower.contains("workouts") || lower.contains("routines") ||
                            lower.contains("exercises") || lower.contains("sync_canonical_roots") ||
                            lower.contains("sync_outbox") || lower.contains("sync_account_cache")
                        if (!writeSeen.get() && isWrite && isAckTable) {
                            writeSeen.set(true)
                        } else if (writeSeen.getAndSet(false)) {
                            fired.set(true)
                            inAck.set(false)
                            runnerJob.cancel(CancellationException("ack-test post-write cancel"))
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.ackSeedFenced()
            callbackDb.ackCaptureWorkoutHead()
            val owner = claimOwner()
            callbackDb.claimPushStore(owner = owner).withRun(0L) { run ->
                val (claim, accepted) = admitAndAccept(run)
                // Oracle taken after the admission freeze, not before it.
                val admitted = callbackDb.claimSnapshot()
                // The acknowledgment runs in a child inheriting the live run's
                // coroutine context; the query hook cancels that child after
                // the first observed write while the parent run stays live to
                // prove rollback and token consumption.
                coroutineScope {
                    val child = async {
                        run.acknowledgeAccepted(claim, accepted)
                    }
                    runnerJob = child
                    inAck.set(true)
                    try {
                        child.await()
                        fail("Mid-write cancellation must propagate, never acknowledgment")
                    } catch (_: CancellationException) {
                    } finally {
                        inAck.set(false)
                    }
                }
                assertTrue(fired.get())
                // Only the committed admission freeze stands; nothing else moved.
                assertEquals(admitted, callbackDb.claimSnapshot())
                assertNull(callbackDb.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
                // Conservative consumption while the run survives: the
                // canceled child's token is stale for every sibling branch.
                try {
                    run.checkClaim(claim)
                    fail("Canceled child's token must be stale in the live run")
                } catch (_: StalePushClaimException) {
                }
                // Fresh admission succeeds in the same live run and settles.
                val next = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(2, next.row.attemptCount)
                assertEquals(claim.row.payloadJson, next.row.payloadJson)
                val liveAccepted = run.handleResult(
                    next,
                    ackAcceptedResult(next.row.mutationId, ackBatch(target = ackWorkoutChange())),
                ) as PushOutcomeResult.Accepted
                val result = run.acknowledgeAccepted(next, liveAccepted) as PushAcknowledgmentResult.Acknowledged
                assertEquals(1, result.acknowledgedRoots)
            }
            assertEquals(0, callbackDb.claimOutboxFor(CLAIM_ACCOUNT_A).size)
            assertEquals(7L, callbackDb.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        } finally {
            callbackDb.close()
        }
    }

    @Test
    fun triggerAborts_rollbackAndConsumeToken() = runTest {
        val faults = listOf(
            Triple(
                "block-metadata",
                "CREATE TRIGGER ack_block_meta BEFORE UPDATE ON workouts " +
                    "BEGIN SELECT RAISE(ABORT, 'ack_blocked'); END",
                "ack_block_meta",
            ),
            Triple(
                "block-canonical",
                "CREATE TRIGGER ack_block_canon BEFORE UPDATE ON sync_canonical_roots " +
                    "BEGIN SELECT RAISE(ABORT, 'ack_blocked'); END",
                "ack_block_canon",
            ),
            Triple(
                "block-removal",
                "CREATE TRIGGER ack_block_delete BEFORE DELETE ON sync_outbox " +
                    "BEGIN SELECT RAISE(ABORT, 'ack_blocked'); END",
                "ack_block_delete",
            ),
        )
        faults.forEach { (label, create, trigger) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.claimExec("DELETE FROM sync_account_cache")
            seedWorkoutHead()
            database.claimPushStore().withRun(0L) { run ->
                val (claim, accepted) = admitAndAccept(run)
                val preAck = database.claimSnapshot()
                database.claimExec(create)
                try {
                    try {
                        run.acknowledgeAccepted(claim, accepted)
                        fail("$label trigger failure must surface as owned storage failure")
                    } catch (error: PushStorageException) {
                        val message = error.message ?: ""
                        assertFalse("$label leak", message.contains("ack_blocked"))
                        assertFalse("$label leak", message.contains("RAISE"))
                        assertFalse("$label leak", message.contains("sync_outbox"))
                        assertNull("$label cause", error.cause)
                    }
                } finally {
                    database.claimExec("DROP TRIGGER IF EXISTS $trigger")
                }
                assertEquals("$label rollback", preAck, database.claimSnapshot())
                // Rollback never releases the token: no failed-write reuse.
                try {
                    run.acknowledgeAccepted(claim, accepted)
                    fail("$label rolled-back write still consumes the token")
                } catch (_: StalePushClaimException) {
                }
            }
            // Reclaim the same frozen identity and settle after fault removal.
            database.claimPushStore().withRun(0L) { run ->
                val (live, liveAccepted) = admitAndAccept(run)
                assertEquals(2, live.row.attemptCount)
                val result = run.acknowledgeAccepted(live, liveAccepted) as PushAcknowledgmentResult.Acknowledged
                assertEquals("$label recovery", 1, result.acknowledgedRoots)
            }
        }
    }

    @Test
    fun postWriteWatermarkTamper_rollsBackAndConsumesToken() = runTest {
        seedWorkoutHead()
        // Representative deterministic case: a trigger rewrites the affected
        // generation watermark behind the acknowledgment write. The post-write
        // recheck must roll the whole transaction back, never commit tampered
        // evidence, and still consume the process-local claim token.
        database.claimPushStore().withRun(0L) { run ->
            val (claim, accepted) = admitAndAccept(run)
            val preAck = database.claimSnapshot()
            database.claimExec(
                "CREATE TRIGGER ack_watermark_trip AFTER UPDATE ON workouts " +
                    "BEGIN UPDATE sync_canonical_roots SET local_generation_watermark = 99 " +
                    "WHERE account_id = '$CLAIM_ACCOUNT_A' AND aggregate_type = 'workout' " +
                    "AND aggregate_id = '$CLAIM_WORKOUT_A'; END",
            )
            try {
                try {
                    run.acknowledgeAccepted(claim, accepted)
                    fail("Watermark-tripping trigger must abort acknowledgment")
                } catch (_: IllegalStateException) {
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS ack_watermark_trip")
            }
            assertEquals(claim.row, database.ackRow(claim.row.mutationId))
            assertEquals(preAck, database.claimSnapshot())
            assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            // Conservative consumption despite rollback.
            try {
                run.acknowledgeAccepted(claim, accepted)
                fail("Rolled-back watermark write still consumes the token")
            } catch (_: StalePushClaimException) {
            }
        }

        // Recovery after the fault is removed reclaims the unchanged bytes.
        database.claimPushStore().withRun(0L) { run ->
            val next = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(2, next.row.attemptCount)
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            val accepted = run.handleResult(
                next,
                ackAcceptedResult(next.row.mutationId, ackBatch(target = ackWorkoutChange())),
            ) as PushOutcomeResult.Accepted
            val result = run.acknowledgeAccepted(next, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.acknowledgedRoots)
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        }
    }

    @Test
    fun postWriteHeadTamper_rollsBackAndConsumesToken() = runTest {
        seedWorkoutHead()
        // Representative deterministic case: a trigger mutates the frozen
        // head's bookkeeping behind the canonical write. The exact pre-remove
        // recheck must fail, roll the whole transaction back, never commit
        // tampered evidence, and still consume the process-local claim token.
        database.claimPushStore().withRun(0L) { run ->
            val (claim, accepted) = admitAndAccept(run)
            val preAck = database.claimSnapshot()
            database.claimExec(
                "CREATE TRIGGER ack_head_trip AFTER UPDATE ON sync_canonical_roots " +
                    "BEGIN UPDATE sync_outbox SET attempt_count = 99 " +
                    "WHERE mutation_id = '${claim.row.mutationId}' AND account_id = '${claim.accountId}'; END",
            )
            try {
                try {
                    run.acknowledgeAccepted(claim, accepted)
                    fail("Head-tripping trigger must abort acknowledgment")
                } catch (_: IllegalStateException) {
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS ack_head_trip")
            }
            assertEquals(claim.row, database.ackRow(claim.row.mutationId))
            assertEquals(preAck, database.claimSnapshot())
            assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            // Conservative consumption despite rollback.
            try {
                run.acknowledgeAccepted(claim, accepted)
                fail("Rolled-back head write still consumes the token")
            } catch (_: StalePushClaimException) {
            }
        }

        // Recovery after the fault is removed reclaims the unchanged bytes.
        database.claimPushStore().withRun(0L) { run ->
            val (live, liveAccepted) = admitAndAccept(run)
            assertEquals(2, live.row.attemptCount)
            val result = run.acknowledgeAccepted(live, liveAccepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.acknowledgedRoots)
            assertEquals(7L, database.ackMeta("workouts", CLAIM_WORKOUT_A)["server_revision"])
            assertEquals("synced", database.ackMeta("workouts", CLAIM_WORKOUT_A)["sync_state"])
        }
    }

    @Test
    fun postWriteFenceChange_rollsBackUncommittedAcknowledgment() = runTest {
        seedWorkoutHead()
        database.claimPushStore().withRun(0L) { run ->
            val (claim, accepted) = admitAndAccept(run)
            val preAck = database.claimSnapshot()
            database.claimExec(
                "CREATE TRIGGER ack_fence_trip AFTER UPDATE ON workouts " +
                    "BEGIN UPDATE sync_device_state SET session_generation = 99; END",
            )
            try {
                try {
                    run.acknowledgeAccepted(claim, accepted)
                    fail("Fence-tripping trigger must abort acknowledgment")
                } catch (_: StaleSessionException) {
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS ack_fence_trip")
            }
            assertEquals(claim.row, database.ackRow(claim.row.mutationId))
            assertEquals(preAck, database.claimSnapshot())
        }

        database.claimPushStore().withRun(0L) { run ->
            val next = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(2, next.row.attemptCount)
            assertEquals("frozen", next.row.state)
        }
    }

}
