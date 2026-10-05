package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.store.PullCheckpoint
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StalePullCheckpointException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R07a independent pull-checkpoint fence acceptance: session/epoch fences,
 * exact-row admission, baseline loss, foreign/superseded/consumed/closed
 * capabilities, enclosing transactions, same-token concurrency, mid-write
 * cancellation, queued cancellation and shared-owner serialization.
 *
 * Real Room, no mocked DAOs, no sleeps. Every expectation is a literal
 * authored from docs/archive/remaining-work/R07a-task.md; no production source was
 * inspected and no production oracle is used.
 */
class PullCheckpointFenceTest : GymDatabaseTest() {

    private suspend fun seedPull(): String {
        database.pullSeedBaseline()
        return PULL_CURSOR_1
    }

    private fun pullBatchFor(
        targetId: String = PULL_WORKOUT_A,
        revision: Long = 7L,
        cursor: String = PULL_CURSOR_2,
    ) = pullBatch(
        changes = listOf(
            pullUpsert(
                AggregateType.WORKOUT,
                targetId,
                revision,
                pullWorkoutRecord(id = targetId, revision = revision),
            ),
        ),
        cursor = cursor,
    )

    @Test
    fun sessionAndEpochChanges_rejectWithoutWrites() = runTest {
        val changes = listOf(
            "account-switch" to
                "UPDATE sync_device_state SET active_account_id = '$PULL_ACCOUNT_B', session_generation = 99",
            "generation-bump" to "UPDATE sync_device_state SET session_generation = 8",
            "sign-out" to "UPDATE sync_device_state SET active_account_id = NULL",
            "cache-advance" to
                "UPDATE sync_account_cache SET cache_epoch = 1 WHERE account_id = '$PULL_ACCOUNT_A'",
        )
        changes.forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            seedPull()
            database.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "$label must read before tamper" }
                database.claimExec(sql)
                val live = database.claimSnapshot()
                // Read-only check revalidates fences without writing.
                try {
                    run.checkPullCheckpoint(checkpoint)
                    fail("$label must reject check")
                } catch (_: StaleSessionException) {
                } catch (_: StaleCanonicalEpochException) {
                }
                assertEquals("$label check-no-writes", live, database.claimSnapshot())
                // Commit revalidates fences before any write.
                try {
                    run.applyNextPullBatch(checkpoint, pullBatchFor())
                    fail("$label must reject commit")
                } catch (_: StaleSessionException) {
                } catch (_: StaleCanonicalEpochException) {
                }
                assertEquals("$label commit-no-writes", live, database.claimSnapshot())
                assertNull("$label canonical", database.ackCanonicalJson(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
            }
            database.claimSeedDevice()
        }
    }

    @Test
    fun exactRowChangeOrRemoval_rejectsAsStale() = runTest {
        val tampers = listOf(
            "cursor-rewrite" to "UPDATE sync_state SET cursor = 'moved' WHERE account_id = '$PULL_ACCOUNT_A'",
            "success-rewrite" to "UPDATE sync_state SET last_success_millis = 42 WHERE account_id = '$PULL_ACCOUNT_A'",
            "row-delete" to "DELETE FROM sync_state WHERE account_id = '$PULL_ACCOUNT_A'",
        )
        tampers.forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            seedPull()
            database.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "$label must read before tamper" }
                database.claimExec(sql)
                val before = database.claimSnapshot()
                try {
                    run.checkPullCheckpoint(checkpoint)
                    fail("$label must invalidate the checkpoint")
                } catch (error: StalePullCheckpointException) {
                    assertEquals(label, PULL_STALE_MESSAGE, error.message)
                }
                assertEquals("$label no-writes", before, database.claimSnapshot())
                try {
                    run.applyNextPullBatch(checkpoint, pullBatchFor())
                    fail("$label must invalidate the commit")
                } catch (error: StalePullCheckpointException) {
                    assertEquals(label, PULL_STALE_MESSAGE, error.message)
                }
                assertEquals("$label commit-no-writes", before, database.claimSnapshot())
            }
        }
    }

    @Test
    fun lostBaseline_rejectsAsStale() = runTest {
        listOf(
            "flag-cleared" to
                "UPDATE sync_account_cache SET baseline_complete = 0 WHERE account_id = '$PULL_ACCOUNT_A'",
            "cache-deleted" to "DELETE FROM sync_account_cache WHERE account_id = '$PULL_ACCOUNT_A'",
        ).forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            seedPull()
            database.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "$label must read before tamper" }
                database.claimExec(sql)
                val before = database.claimSnapshot()
                try {
                    run.checkPullCheckpoint(checkpoint)
                    fail("$label must reject check")
                } catch (_: StalePullCheckpointException) {
                }
                try {
                    run.applyNextPullBatch(checkpoint, pullBatchFor())
                    fail("$label must reject commit")
                } catch (_: StalePullCheckpointException) {
                }
                assertEquals("$label no-writes", before, database.claimSnapshot())
            }
        }
    }

    @Test
    fun foreignSupersededConsumedClosedAndEnclosing_reject() = runTest {
        seedPull()
        val owner = claimOwner()
        var leakedCheckpoint: PullCheckpoint? = null
        var leakedRun: PushRun? = null
        database.claimPushStore(owner = owner).withRun(PULL_EPOCH) { run ->
            leakedRun = run
            val first = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            leakedCheckpoint = first
            val committed = run.applyNextPullBatch(first, pullBatchFor())
            assertEquals(PULL_CURSOR_2, committed.checkpoint.cursor)
            // Consumed in its own live run: stale for every sibling use.
            try {
                run.checkPullCheckpoint(first)
                fail("Consumed checkpoint must be stale in its own run")
            } catch (_: StalePullCheckpointException) {
            }
        }
        // Cross-run reuse is foreign use: the token's run is not the current
        // run, so the contract surfaces IllegalStateException.
        database.claimPushStore(owner = owner).withRun(PULL_EPOCH) { run ->
            val foreign = checkNotNull(leakedCheckpoint) { "Checkpoint must leak across runs" }
            try {
                run.applyNextPullBatch(foreign, pullBatchFor(cursor = PULL_CURSOR_3))
                fail("Foreign-run commit must be rejected")
            } catch (_: IllegalStateException) {
            }
            try {
                run.checkPullCheckpoint(foreign)
                fail("Foreign-run check must be rejected")
            } catch (_: IllegalStateException) {
            }
        }
        // Closed run handle rejects every use.
        val closedRun = checkNotNull(leakedRun) { "Run must leak" }
        val closedToken = checkNotNull(leakedCheckpoint) { "Checkpoint must leak" }
        try {
            closedRun.checkPullCheckpoint(closedToken)
            fail("Closed-run check must be rejected")
        } catch (_: IllegalStateException) {
        }
        try {
            closedRun.readPullCheckpoint()
            fail("Closed-run read must be rejected")
        } catch (_: IllegalStateException) {
        }
        // Foreign run handle must not accept the token either.
        database.claimPushStore(owner = claimOwner()).withRun(PULL_EPOCH) { run ->
            val foreignOwner = checkNotNull(leakedCheckpoint) { "Checkpoint must leak" }
            try {
                run.applyNextPullBatch(foreignOwner, pullBatchFor(cursor = PULL_CURSOR_3))
                fail("Foreign-owner commit must be rejected")
            } catch (_: IllegalStateException) {
            }
        }
        // Enclosing Room transaction rejects entry, following the R06a
        // precedent of accepting either owned-entry exception shape.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_canonical_roots")
        seedPull()
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        val snapBefore = database.claimSnapshot()
        captureStore.withFencedTransaction {
            try {
                database.claimPushStore().withRun(PULL_EPOCH) { run ->
                    val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
                    run.applyNextPullBatch(checkpoint, pullBatchFor())
                }
                fail("Entry from an active Room transaction must be rejected")
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals(snapBefore, database.claimSnapshot())
    }

    @Test
    fun sameCheckpointConcurrentCommits_settleOnce() = runTest {
        seedPull()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            var successes = 0
            var stales = 0
            coroutineScope {
                // Each child classifies its own expected stale outcome: an
                // uncaught child failure would cancel the parent scope before
                // the awaits below could count it.
                val first = async {
                    try {
                        val result = run.applyNextPullBatch(checkpoint, pullBatchFor())
                        assertEquals(PULL_CURSOR_2, result.checkpoint.cursor)
                        "committed"
                    } catch (_: StalePullCheckpointException) {
                        "stale"
                    }
                }
                val second = async {
                    try {
                        // Identical batch: either child may win, but the
                        // durable boundary is the same either way.
                        val result = run.applyNextPullBatch(checkpoint, pullBatchFor())
                        assertEquals(PULL_CURSOR_2, result.checkpoint.cursor)
                        "committed"
                    } catch (_: StalePullCheckpointException) {
                        "stale"
                    }
                }
                listOf(first.await(), second.await()).forEach { outcome ->
                    when (outcome) {
                        "committed" -> successes++
                        else -> stales++
                    }
                }
            }
            assertEquals(1, successes)
            assertEquals(1, stales)
            // The winner's boundary is durable; the loser skipped nothing.
            assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
            // The consumed token cannot be reused by any sibling branch.
            try {
                run.checkPullCheckpoint(checkpoint)
                fail("Consumed token must be stale for checks too")
            } catch (_: StalePullCheckpointException) {
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sharedOwner_serializesRunsAcrossCursorChain() = runTest {
        seedPull()
        val owner = claimOwner()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            database.claimPushStore(owner = owner).withRun(PULL_EPOCH) { run ->
                events += "first-enter"
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
                assertEquals(PULL_CURSOR_1, checkpoint.cursor)
                entered.complete(Unit)
                gate.await()
                run.applyNextPullBatch(checkpoint, pullBatchFor())
                events += "first-exit"
            }
        }
        entered.await()
        val second = async {
            database.claimPushStore(owner = owner).withRun(PULL_EPOCH) { run ->
                events += "second-enter"
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
                assertEquals(PULL_CURSOR_2, checkpoint.cursor)
            }
        }
        runCurrent()
        assertEquals(listOf("first-enter"), events)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-enter", "first-exit", "second-enter"), events)
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
    }

    @Test
    fun callerCancellationMidWrite_rollsBackAndConsumesToken() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inCommit = AtomicBoolean(false)
        val writeSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        lateinit var runnerJob: Job
        // Test-only real Room query hook: observe the first commit write
        // (canonical/cursor), then cancel the caller Job on the following
        // query. Callbacks fire before SQL, so cancelling on the write
        // itself would only prove pre-execution interruption.
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (inCommit.get()) {
                        val lower = sql.lowercase()
                        val isWrite = lower.contains("insert") || lower.contains("update") ||
                            lower.contains("delete")
                        val isCommitTable = lower.contains("sync_canonical_roots") ||
                            lower.contains("sync_state") || lower.contains("sync_account_cache") ||
                            lower.contains("workouts") || lower.contains("routines") ||
                            lower.contains("exercises")
                        if (!writeSeen.get() && isWrite && isCommitTable) {
                            writeSeen.set(true)
                        } else if (writeSeen.getAndSet(false)) {
                            fired.set(true)
                            inCommit.set(false)
                            runnerJob.cancel(CancellationException("pull-test post-write cancel"))
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.pullSeedBaseline()
            val owner = claimOwner()
            callbackDb.claimPushStore(owner = owner).withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
                // Oracle taken after admission, not before it.
                val admitted = callbackDb.claimSnapshot()
                // The commit runs in a child inheriting the live run's
                // coroutine context; the query hook cancels that child after
                // the first observed write while the parent run stays live to
                // prove rollback and token consumption.
                coroutineScope {
                    val child = async {
                        run.applyNextPullBatch(checkpoint, pullBatchFor())
                    }
                    runnerJob = child
                    inCommit.set(true)
                    try {
                        child.await()
                        fail("Mid-write cancellation must propagate, never a commit")
                    } catch (_: CancellationException) {
                    } finally {
                        inCommit.set(false)
                    }
                }
                assertTrue(fired.get())
                // Only the admitted state stands; nothing else moved.
                assertEquals(admitted, callbackDb.claimSnapshot())
                assertNull(callbackDb.ackCanonicalJson(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
                assertEquals(PULL_CURSOR_1, callbackDb.pullSyncCursor())
                // Conservative consumption while the run survives: the
                // canceled child's token is stale for every sibling branch.
                try {
                    run.checkPullCheckpoint(checkpoint)
                    fail("Canceled child's token must be stale in the live run")
                } catch (_: StalePullCheckpointException) {
                }
                // Fresh reread in the same live run permits the same durable
                // identity and settles from the exact boundary.
                val next = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
                assertEquals(PULL_CURSOR_1, next.cursor)
                val result = run.applyNextPullBatch(next, pullBatchFor())
                assertEquals(1, result.canonical.appliedRoots)
                assertEquals(PULL_CURSOR_2, result.checkpoint.cursor)
            }
            assertEquals(PULL_CURSOR_2, callbackDb.pullSyncCursor())
        } finally {
            callbackDb.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun queuedCancellation_leavesNoWritesAndLateCallbackCannotCommit() = runTest {
        seedPull()
        val owner = claimOwner()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val before = database.claimSnapshot()
        val first = async {
            database.claimPushStore(owner = owner).withRun(PULL_EPOCH) {
                entered.complete(Unit)
                gate.await()
            }
        }
        entered.await()
        val second = async {
            database.claimPushStore(owner = owner).withRun(PULL_EPOCH) {
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
        // Ownership released and no pull moved: a fresh run reads the same
        // boundary, and a leaked late callback for a committed token cannot
        // commit after the winner.
        database.claimPushStore(owner = owner).withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            assertEquals(PULL_CURSOR_1, checkpoint.cursor)
            val committed = run.applyNextPullBatch(checkpoint, pullBatchFor())
            assertEquals(PULL_CURSOR_2, committed.checkpoint.cursor)
            try {
                run.applyNextPullBatch(checkpoint, pullBatchFor(cursor = PULL_CURSOR_3))
                fail("Leaked late callback must not commit")
            } catch (_: StalePullCheckpointException) {
            }
        }
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        assertFalse(database.claimSnapshot() == before)
    }

    @Test
    fun postWriteEpochTamper_rollsBackUncommittedCommit() = runTest {
        seedPull()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val preCommit = database.claimSnapshot()
            // A trigger advances the cache epoch behind the cursor write; the
            // post-write fence recheck must roll the batch back with it.
            database.claimExec(
                "CREATE TRIGGER pull_epoch_trip AFTER UPDATE ON sync_state " +
                    "BEGIN UPDATE sync_account_cache SET cache_epoch = 99 " +
                    "WHERE account_id = '$PULL_ACCOUNT_A'; END",
            )
            try {
                try {
                    run.applyNextPullBatch(checkpoint, pullBatchFor())
                    fail("Epoch-tripping trigger must abort the commit")
                } catch (_: StaleCanonicalEpochException) {
                }
            } finally {
                database.pullDropTrigger("pull_epoch_trip")
            }
            assertEquals(preCommit, database.claimSnapshot())
            assertNull(database.ackCanonicalJson(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
            assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
            try {
                run.checkPullCheckpoint(checkpoint)
                fail("Rolled-back token must be consumed")
            } catch (_: StalePullCheckpointException) {
            }
        }
    }

    @Test
    fun postWriteLastSuccessTamper_rollsBackBatchAndCheckpoint() = runTest {
        seedPull()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val preCommit = database.claimSnapshot()
            // A trigger rewrites last-success behind the canonical write; the
            // post-write row recheck must roll the batch back with it.
            database.claimExec(
                "CREATE TRIGGER pull_success_trip AFTER INSERT ON sync_canonical_roots " +
                    "BEGIN UPDATE sync_state SET last_success_millis = 5 " +
                    "WHERE account_id = '$PULL_ACCOUNT_A'; END",
            )
            try {
                try {
                    run.applyNextPullBatch(checkpoint, pullBatchFor())
                    fail("Last-success-tripping trigger must abort the commit")
                } catch (_: StalePullCheckpointException) {
                }
            } finally {
                database.pullDropTrigger("pull_success_trip")
            }
            assertEquals(preCommit, database.claimSnapshot())
            assertNull(database.ackCanonicalJson(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
            assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
            assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
            try {
                run.checkPullCheckpoint(checkpoint)
                fail("Rolled-back token must be consumed")
            } catch (_: StalePullCheckpointException) {
            }
        }
    }

    @Test
    fun postWriteSessionTamper_rollsBackBatchAndCheckpoint() = runTest {
        seedPull()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val preCommit = database.claimSnapshot()
            // A trigger bumps the session generation behind the canonical
            // write; the post-write fence recheck must roll everything back.
            database.claimExec(
                "CREATE TRIGGER pull_session_trip AFTER INSERT ON sync_canonical_roots " +
                    "BEGIN UPDATE sync_device_state SET session_generation = 99; END",
            )
            try {
                try {
                    run.applyNextPullBatch(checkpoint, pullBatchFor())
                    fail("Session-tripping trigger must abort the commit")
                } catch (_: StaleSessionException) {
                }
            } finally {
                database.pullDropTrigger("pull_session_trip")
            }
            assertEquals(preCommit, database.claimSnapshot())
            assertNull(database.ackCanonicalJson(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
            assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
            try {
                run.checkPullCheckpoint(checkpoint)
                fail("Rolled-back token must be consumed")
            } catch (_: StalePullCheckpointException) {
            }
        }
    }
}
