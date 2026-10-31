package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.store.PullStorageException
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StalePullCheckpointException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real Room checks for transactional valid-tail and last-success bookkeeping. Input checkpoints
 * retire before writes, including rollback or uncertain return; expected rows and times are
 * literal values.
 */
class PullTailCommitTest : GymDatabaseTest() {

    @Test
    fun fullRowAndNullLastSuccess_fencesRejectWithoutWrites() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "eligible baseline must yield a checkpoint" }
            // Missing row fences closed.
            database.claimExec("DELETE FROM sync_state WHERE account_id = '$PULL_ACCOUNT_A'")
            try {
                run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                fail("missing row must fence tail")
            } catch (_: StalePullCheckpointException) {
            }
            assertNull(database.pullLastSuccess())
        }
        // Changed cursor fences closed; baseline-incomplete fences closed.
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read before tamper" }
            database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, SYNC_CURSOR_MID, null))
            try {
                run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                fail("changed cursor must fence tail")
            } catch (_: StalePullCheckpointException) {
            }
            assertNull(database.pullLastSuccess())
        }
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read before tamper" }
            database.claimExec("UPDATE sync_account_cache SET baseline_complete = 0 WHERE account_id = '$PULL_ACCOUNT_A'")
            try {
                run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                fail("incomplete baseline must fence tail")
            } catch (_: StalePullCheckpointException) {
            }
            assertNull(database.pullLastSuccess())
        }
        // Changed last-success fences closed: the tampered value is preserved
        // verbatim, never overwritten by the rejected tail.
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read before tamper" }
            database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, PULL_CURSOR_1, 4242L))
            try {
                run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                fail("changed last-success must fence tail")
            } catch (_: StalePullCheckpointException) {
            }
            assertEquals(4242L, database.pullLastSuccess())
        }
        // Session and epoch tampers keep their exact fence types at the tail.
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read before tamper" }
            database.claimSeedDevice(PULL_ACCOUNT_A, PULL_GENERATION + 1)
            try {
                run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                fail("session change must fence tail")
            } catch (_: StaleSessionException) {
            }
            assertNull(database.pullLastSuccess())
        }
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read before tamper" }
            database.claimUpdateCache(PULL_ACCOUNT_A, 9L)
            try {
                run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                fail("epoch change must fence tail")
            } catch (_: StaleCanonicalEpochException) {
            }
            assertNull(database.pullLastSuccess())
        }
        // Admitted-row corruption (not fresh-read corruption): tampering the
        // live row to an empty cursor or negative last-success after admission
        // must reject as stale against the exact admitted state, preserving
        // the tampered values verbatim.
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read before tamper" }
            database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, "", null))
            try {
                run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                fail("emptied admitted cursor must fence tail")
            } catch (_: StalePullCheckpointException) {
            }
            assertEquals("", database.pullSyncCursor())
        }
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read before tamper" }
            database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, PULL_CURSOR_1, -5L))
            try {
                run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                fail("negative admitted last-success must fence tail")
            } catch (_: StalePullCheckpointException) {
            }
            assertEquals(-5L, database.pullLastSuccess())
        }
        // Negative clock rejects before any write.
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        val before = database.claimSnapshot()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read" }
            try {
                run.recordPullTail(checkpoint, -1L)
                fail("negative nowMillis must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(before, database.claimSnapshot())
        assertNull(database.pullLastSuccess())
    }

    @Test
    fun corruptCursorOrSuccess_failsClosedWithoutRepair() = runTest {
        listOf(
            "empty-cursor" to SyncStateEntity(PULL_ACCOUNT_A, "", PULL_LAST_SUCCESS),
            "negative-success" to SyncStateEntity(PULL_ACCOUNT_A, PULL_CURSOR_1, -5L),
        ).forEach { (label, row) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline(cursor = PULL_CURSOR_1)
            database.syncDao().upsertSyncState(row)
            val before = database.claimSnapshot()
            database.claimPushStore().withRun(PULL_EPOCH) { run ->
                try {
                    run.readPullCheckpoint()
                    fail("$label must fail closed")
                } catch (error: PullStorageException) {
                    assertEquals(label, PULL_STORAGE_MESSAGE, error.message)
                    assertNull("$label cause", error.cause)
                }
                assertEquals("$label no-repair", before, database.claimSnapshot())
            }
            // Stored corruption is preserved verbatim for recovery tooling.
            val retained = database.syncDao().getSyncState(PULL_ACCOUNT_A)!!
            assertEquals("$label cursor", row.cursor, retained.cursor)
            assertEquals("$label success", row.lastSuccessMillis, retained.lastSuccessMillis)
        }
    }

    @Test
    fun newSelectedOutbox_observedAsOutstandingWithoutBlockingTail() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read" }
            val committed = run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
            assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
            // Selected-account presence is an observation, not a fence: the
            // pending head is reported and preserved exactly.
            assertEquals(true, committed.hasOutstandingLocalWork)
            val survivor = checkNotNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
            assertEquals("pending", survivor.state)
        }
    }

    @Test
    fun exactTimestamp_equalAndBackwardClockPreserved_onlySelectedAccountObserved() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        // Foreign-account outbox must not count toward the selected observation.
        database.claimSeedDevice(PULL_ACCOUNT_B, PULL_GENERATION)
        database.claimSeedCache(PULL_ACCOUNT_B, 3L)
        database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_B, "foreign-cursor", 777L))
        val foreignCapture = database.claimCaptureStore(claimSession(PULL_ACCOUNT_B), mutableListOf(CLAIM_MUTATION_2))
        database.claimSeedWorkout(CLAIM_WORKOUT_B, PULL_ACCOUNT_B, "Foreign")
        database.claimCaptureStart(foreignCapture, PULL_ACCOUNT_B, CLAIM_WORKOUT_B, "Foreign")
        // The foreign capture leaves device B active: restore the selected
        // account device before admitting the A-scoped tail run.
        database.claimSeedDevice(PULL_ACCOUNT_A, PULL_GENERATION)

        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val first = checkNotNull(run.readPullCheckpoint()) { "must read" }
            val committed = run.recordPullTail(first, SYNC_NOW_MILLIS)
            assertEquals(false, committed.hasOutstandingLocalWork)
            assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
            // Fresh chaining: the returned checkpoint binds the new full row.
            val second = committed.checkpoint
            run.checkPullCheckpoint(second)
            // Equal timestamps are not no-ops: retire then rewrite exactly.
            val equal = run.recordPullTail(second, SYNC_NOW_MILLIS)
            assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
            // Backward clock is observation metadata: preserved exactly, never max-clamped.
            val backward = run.recordPullTail(equal.checkpoint, SYNC_NOW_MILLIS - 60_000L)
            assertEquals(SYNC_NOW_MILLIS - 60_000L, database.pullLastSuccess())
            assertEquals(false, backward.hasOutstandingLocalWork)
        }
        assertEquals("foreign-cursor", database.pullSyncCursor(PULL_ACCOUNT_B))
        assertEquals(777L, database.pullLastSuccess(PULL_ACCOUNT_B))
    }

    @Test
    fun freshChaining_oldTokensStayStale() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val first = checkNotNull(run.readPullCheckpoint()) { "must read" }
            val committed = run.recordPullTail(first, SYNC_NOW_MILLIS)
            // Successful writes return fresh tokens; old tokens stay stale.
            try {
                run.checkPullCheckpoint(first)
                fail("consumed token must stay stale")
            } catch (_: StalePullCheckpointException) {
            }
            try {
                run.recordPullTail(first, SYNC_NOW_MILLIS + 1L)
                fail("retired token must not record twice")
            } catch (_: StalePullCheckpointException) {
            }
            // Same-run follow-on uses the new token.
            run.checkPullCheckpoint(committed.checkpoint)
            val second = run.recordPullTail(committed.checkpoint, SYNC_NOW_MILLIS + 1L)
            assertEquals(SYNC_NOW_MILLIS + 1L, database.pullLastSuccess())
            assertEquals(false, second.hasOutstandingLocalWork)
        }
    }

    private data class TailTripCase(
        val label: String,
        val trigger: String,
        val triggerSql: String,
        val expected: KClass<out Throwable>,
        val pendingIntent: Boolean = false,
    )

    @Test
    fun postWriteTrips_rollBackTailWithExactTypes_retireTokenAndRecover() = runTest {
        // Representative real-Room trip triggers: suppressed update count,
        // post-write row/cursor mismatch, and post-write baseline/epoch/
        // session tampers. Each must roll back fully with its exact type.
        val cases = listOf(
            TailTripCase(
                "ignore-suppresses-update",
                "tail_ignore_update",
                "CREATE TRIGGER tail_ignore_update BEFORE UPDATE ON sync_state " +
                    "BEGIN SELECT RAISE(IGNORE); END",
                StalePullCheckpointException::class,
            ),
            TailTripCase(
                "cursor-rewrite-behind-write",
                "tail_cursor_trip",
                "CREATE TRIGGER tail_cursor_trip AFTER UPDATE OF last_success_millis ON sync_state " +
                    "BEGIN UPDATE sync_state SET cursor = 'moved' WHERE account_id = '$PULL_ACCOUNT_A'; END",
                StalePullCheckpointException::class,
            ),
            TailTripCase(
                "baseline-clear-behind-write",
                "tail_baseline_trip",
                "CREATE TRIGGER tail_baseline_trip AFTER UPDATE ON sync_state " +
                    "BEGIN UPDATE sync_account_cache SET baseline_complete = 0 " +
                    "WHERE account_id = '$PULL_ACCOUNT_A'; END",
                StalePullCheckpointException::class,
            ),
            TailTripCase(
                "epoch-advance-behind-write",
                "tail_epoch_trip",
                "CREATE TRIGGER tail_epoch_trip AFTER UPDATE ON sync_state " +
                    "BEGIN UPDATE sync_account_cache SET cache_epoch = 99 " +
                    "WHERE account_id = '$PULL_ACCOUNT_A'; END",
                StaleCanonicalEpochException::class,
            ),
            TailTripCase(
                "session-bump-behind-write",
                "tail_session_trip",
                "CREATE TRIGGER tail_session_trip AFTER UPDATE ON sync_state " +
                    "BEGIN UPDATE sync_device_state SET session_generation = 99; END",
                StaleSessionException::class,
            ),
            TailTripCase(
                "outstanding-intent-removed-behind-write",
                "tail_presence_trip",
                "CREATE TRIGGER tail_presence_trip AFTER UPDATE ON sync_state " +
                    "BEGIN DELETE FROM sync_outbox WHERE account_id = '$PULL_ACCOUNT_A'; END",
                StalePullCheckpointException::class,
                pendingIntent = true,
            ),
        )
        cases.forEach { case ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
            if (case.pendingIntent) {
                database.ackCaptureWorkoutHead()
            }
            database.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "${case.label} must read" }
                val preCommit = database.claimSnapshot()
                database.claimExec(case.triggerSql)
                try {
                    val thrown: Throwable? = try {
                        run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                        null
                    } catch (e: PullStorageException) {
                        e
                    } catch (e: StalePullCheckpointException) {
                        e
                    } catch (e: StaleCanonicalEpochException) {
                        e
                    } catch (e: StaleSessionException) {
                        e
                    }
                    assertTrue(
                        "${case.label} must propagate ${case.expected.simpleName}, got ${thrown?.javaClass?.simpleName}",
                        case.expected.isInstance(thrown),
                    )
                    // Full rollback: trip effects roll back with the tail.
                    assertEquals("${case.label} full rollback", preCommit, database.claimSnapshot())
                    assertNull("${case.label} no last-success", database.pullLastSuccess())
                    assertEquals("${case.label} cursor", PULL_CURSOR_1, database.pullSyncCursor())
                    // Input token remains retired despite rollback.
                    try {
                        run.checkPullCheckpoint(checkpoint)
                        fail("${case.label} rolled-back token must stay retired")
                    } catch (_: StalePullCheckpointException) {
                    }
                } finally {
                    database.pullDropTrigger(case.trigger)
                }
                // Fresh reread after trigger cleanup settles from the exact boundary.
                val fresh = checkNotNull(run.readPullCheckpoint()) { "${case.label} must reread" }
                val committed = run.recordPullTail(fresh, SYNC_NOW_MILLIS)
                assertEquals(case.pendingIntent, committed.hasOutstandingLocalWork)
            }
            assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
        }
    }

    @Test
    fun triggerRollback_retiresTokenWithoutClaimingSuccess_recoveryRereads() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        // Seed one durable batch boundary that must survive the tail rollback.
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read" }
            val batch = pullBatch(
                changes = listOf(
                    pullUpsert(
                        com.dragosfotache.gymtracka.data.sync.model.AggregateType.WORKOUT,
                        PULL_WORKOUT_A, 7L, pullWorkoutRecord(),
                    ),
                ),
                batchId = PULL_BATCH_1, cursor = PULL_CURSOR_2,
            )
            run.applyNextPullBatch(checkpoint, batch)
        }
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())

        database.pullBlockCursorWrites("pull_tail_block")
        try {
            database.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must reread durable boundary" }
                try {
                    run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                    fail("blocked tail write must propagate")
                } catch (error: PullStorageException) {
                    assertEquals("Pull checkpoint storage operation failed", error.message)
                    assertNull(error.cause)
                }
                // Uncertain commits never claim recorded success; the input
                // token is still retired even though nothing committed.
                try {
                    run.checkPullCheckpoint(checkpoint)
                    fail("failed write token must stay retired")
                } catch (_: StalePullCheckpointException) {
                }
            }
        } finally {
            database.pullDropTrigger("pull_tail_block")
        }
        // Full rollback: no last-success, earlier batch boundary survives.
        assertNull(database.pullLastSuccess())
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        assertEquals(7L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
        // Durable reread recovers and records.
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val fresh = checkNotNull(run.readPullCheckpoint()) { "durable reread must yield a checkpoint" }
            val committed = run.recordPullTail(fresh, SYNC_NOW_MILLIS)
            assertEquals(false, committed.hasOutstandingLocalWork)
        }
        assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
    }

    @Test
    fun canceledChildWriteToken_staysRetiredInSurvivingParentRun() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inTail = AtomicBoolean(false)
        val writeSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        lateinit var runnerJob: Job
        // Test-only real Room query hook: observe the first tail write
        // (UPDATE sync_state), then cancel the caller Job on the following
        // query. Callbacks fire before SQL, so cancelling on the write itself
        // would only prove pre-execution interruption.
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (inTail.get()) {
                        val lower = sql.lowercase()
                        val isWrite = lower.contains("insert") || lower.contains("update") ||
                            lower.contains("delete")
                        if (!writeSeen.get() && isWrite && lower.contains("sync_state")) {
                            writeSeen.set(true)
                        } else if (writeSeen.getAndSet(false)) {
                            fired.set(true)
                            inTail.set(false)
                            runnerJob.cancel(CancellationException("tail-test post-write cancel"))
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
            // Oracle taken after admission, not before it.
            val admitted = callbackDb.claimSnapshot()
            callbackDb.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
                // The tail commit runs in a child inheriting the live run's
                // coroutine context; the query hook cancels that child after
                // the first observed write while the parent run stays live to
                // prove rollback and token consumption.
                coroutineScope {
                    val child = async {
                        run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                    }
                    runnerJob = child
                    inTail.set(true)
                    try {
                        child.await()
                        fail("Mid-write cancellation must propagate, never a commit")
                    } catch (_: CancellationException) {
                    } finally {
                        inTail.set(false)
                    }
                }
                assertTrue("post-write hook must fire", fired.get())
                // Full rollback: only the admitted state stands.
                assertEquals(admitted, callbackDb.claimSnapshot())
                assertNull(callbackDb.pullLastSuccess())
                assertEquals(PULL_CURSOR_1, callbackDb.pullSyncCursor())
                // Canceled child's token is stale in the surviving inherited parent.
                try {
                    run.checkPullCheckpoint(checkpoint)
                    fail("Canceled child's token must be stale in the live run")
                } catch (_: StalePullCheckpointException) {
                }
                // Fresh reread in the same live run settles from the exact boundary.
                val next = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
                val committed = run.recordPullTail(next, SYNC_NOW_MILLIS)
                assertEquals(false, committed.hasOutstandingLocalWork)
            }
            assertEquals(SYNC_NOW_MILLIS, callbackDb.pullLastSuccess())
        } finally {
            callbackDb.close()
        }
    }

    @Test
    fun failedAttemptLeavesNoTrace_reopenRereadThenRecords() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("pull-tail-reopen")
        val fileDatabase = claimFileDatabase(context, name)
        try {
            fileDatabase.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
            // A blocked tail attempt retires its token but commits nothing.
            fileDatabase.pullBlockCursorWrites("pull_tail_block")
            try {
                fileDatabase.claimPushStore().withRun(PULL_EPOCH) { run ->
                    val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read" }
                    try {
                        run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
                        fail("blocked tail write must propagate")
                    } catch (_: PullStorageException) {
                    }
                }
            } finally {
                fileDatabase.pullDropTrigger("pull_tail_block")
            }
            assertNull(fileDatabase.pullLastSuccess())
            assertEquals(PULL_CURSOR_1, fileDatabase.pullSyncCursor())
        } finally {
            fileDatabase.close()
        }
        val reopened = claimFileDatabase(context, name)
        try {
            reopened.claimSeedDevice()
            // Durable state after restart shows the pre-attempt boundary with
            // no last-success: the failed attempt left no commit evidence.
            assertNull(reopened.pullLastSuccess())
            assertEquals(PULL_CURSOR_1, reopened.pullSyncCursor())
            // Closed-run tokens are not reusable authority: a durable reread
            // is required, and only its success records the timestamp.
            reopened.claimPushStore().withRun(PULL_EPOCH) { run ->
                val fresh = checkNotNull(run.readPullCheckpoint()) { "reopen must reread" }
                val committed = run.recordPullTail(fresh, SYNC_NOW_MILLIS)
                assertEquals(false, committed.hasOutstandingLocalWork)
            }
            assertEquals(SYNC_NOW_MILLIS, reopened.pullLastSuccess())
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun redactedTailDisplay_omitsCursorAccountBody() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "must read" }
            val committed = run.recordPullTail(checkpoint, SYNC_NOW_MILLIS)
            syncAssertRedacted(committed.toString(), "tail-result")
            syncAssertRedacted(committed.checkpoint.toString(), "tail-checkpoint")
        }
    }
}
