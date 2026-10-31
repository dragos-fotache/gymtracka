package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageActiveException
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageStorageException
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.StaleSnapshotStageException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real Room checks for stage session/epoch/source fences, invalid capability contexts,
 * trigger/count-zero rollback, cancellation, concurrency, deep ownership and explicit
 * abandon/restart.
 */
class SnapshotStageFenceTest : GymDatabaseTest() {

    @Test
    fun sessionAndEpochChanges_rejectWithoutWrites() = runTest {
        val tampers = listOf(
            "account-switch" to
                "UPDATE sync_device_state SET active_account_id = '$CLAIM_ACCOUNT_B', session_generation = 99",
            "generation-bump" to "UPDATE sync_device_state SET session_generation = 8",
            "sign-out" to "UPDATE sync_device_state SET active_account_id = NULL",
            "cache-advance" to
                "UPDATE sync_account_cache SET cache_epoch = 1 WHERE account_id = '$CLAIM_ACCOUNT_A'",
        )
        tampers.forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_snapshot_stages")
            database.claimExec("DELETE FROM sync_snapshot_records")
            database.claimExec("DELETE FROM sync_snapshot_tokens")
            database.stageSeedBaseline()
            database.claimPushStore().withRun(STAGE_EPOCH) { run ->
                val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                database.claimExec(sql)
                val live = database.claimSnapshot()
                try {
                    run.checkSnapshotStage(token)
                    fail("$label must reject check")
                } catch (_: StaleSessionException) {
                } catch (_: StaleCanonicalEpochException) {
                }
                assertEquals("$label check-no-writes", live, database.claimSnapshot())
                try {
                    run.appendSnapshotPage(
                        token,
                        stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                    )
                    fail("$label must reject append")
                } catch (_: StaleSessionException) {
                } catch (_: StaleCanonicalEpochException) {
                }
                assertEquals("$label append-no-writes", live, database.claimSnapshot())
            }
            database.claimSeedDevice()
        }
    }

    @Test
    fun sourceRowChange_rejectsAsStaleWithoutWrites() = runTest {
        val tampers = listOf(
            "cursor-rewrite" to
                "UPDATE sync_state SET cursor = 'moved' WHERE account_id = '$CLAIM_ACCOUNT_A'",
            "success-rewrite" to
                "UPDATE sync_state SET last_success_millis = 42 WHERE account_id = '$CLAIM_ACCOUNT_A'",
            "row-delete" to "DELETE FROM sync_state WHERE account_id = '$CLAIM_ACCOUNT_A'",
            "baseline-loss" to
                "UPDATE sync_account_cache SET baseline_complete = 0 WHERE account_id = '$CLAIM_ACCOUNT_A'",
        )
        tampers.forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_snapshot_stages")
            database.claimExec("DELETE FROM sync_snapshot_records")
            database.claimExec("DELETE FROM sync_snapshot_tokens")
            database.stageSeedBaseline()
            database.claimPushStore().withRun(STAGE_EPOCH) { run ->
                val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                database.claimExec(sql)
                val live = database.claimSnapshot()
                try {
                    run.appendSnapshotPage(
                        token,
                        stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                    )
                    fail("$label must reject append")
                } catch (_: StaleSnapshotStageException) {
                }
                assertEquals("$label no-writes", live, database.claimSnapshot())
                assertEquals(0, database.stageRecordRows(STAGE_ID_1).size)
            }
        }
    }

    @Test
    fun foreignClosedOutsideAndEnclosing_useRejectWithoutWrites() = runTest {
        database.stageSeedBaseline()
        var closedRun: PushRun? = null
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            closedRun = run
        }
        val live = database.claimSnapshot()
        // Closed-run capability cannot be reused after its run completed.
        try {
            checkNotNull(closedRun).beginSnapshotStage(STAGE_ID_2, STAGE_LIMIT)
            fail("Closed run must reject use")
        } catch (_: IllegalStateException) {
        }
        assertEquals(live, database.claimSnapshot())
        // A token admitted in one run is foreign in another live run.
        val foreign = database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            checkNotNull(run.readSnapshotStage()) { "Must observe staged presence" }
        }
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.appendSnapshotPage(
                    foreign,
                    stagePage(listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                )
                fail("Foreign-run token must reject append")
            } catch (_: IllegalStateException) {
            }
            // Enclosing-transaction use rejects as well.
            val current = checkNotNull(run.readSnapshotStage()) { "Must observe in live run" }
            database.openHelper.writableDatabase.beginTransaction()
            try {
                try {
                    run.checkSnapshotStage(current)
                    fail("Enclosing-transaction use must reject")
                } catch (_: IllegalStateException) {
                }
            } finally {
                database.openHelper.writableDatabase.endTransaction()
            }
        }
        assertEquals(live, database.claimSnapshot())
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun supersededAndRecoveryTokens_cannotAppend() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val first = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            // A fresh read supersedes the prior token even without failure.
            val second = checkNotNull(run.readSnapshotStage()) { "Must reread" }
            assertFalse(second.canAppend)
            try {
                run.appendSnapshotPage(
                    first,
                    stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                )
                fail("Superseded token must not append")
            } catch (_: StaleSnapshotStageException) {
            }
            // Recovery observations are abandonable, never append authority.
            try {
                run.appendSnapshotPage(
                    second,
                    stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                )
                fail("Recovery observation must not append")
            } catch (e: StaleSnapshotStageException) {
                assertEquals(STAGE_STALE_MESSAGE, e.message)
                assertNull(e.cause)
            }
            try {
                run.checkSnapshotStage(first)
                fail("Superseded check must be stale")
            } catch (_: StaleSnapshotStageException) {
            }
            // Explicit abandonment with the recovery observation still works.
            run.abandonSnapshotStage(second)
        }
        assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
    }

    @Test
    fun postWriteSourceTamper_rollsBackWholePageAndConsumesToken() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            val preCommit = database.claimSnapshot()
            // A trigger trips the source cursor behind the header write; the
            // post-write source recheck must roll the page back with it.
            // The exact source-row mismatch is stale, with full rollback and
            // a retired token; unrelated storage/admission failures cannot
            // satisfy this oracle.
            database.claimExec(
                "CREATE TRIGGER stage_cursor_trip AFTER UPDATE ON sync_snapshot_stages " +
                    "BEGIN UPDATE sync_state SET cursor = 'tampered' " +
                    "WHERE account_id = '$CLAIM_ACCOUNT_A'; END",
            )
            try {
                try {
                    run.appendSnapshotPage(
                        token,
                        stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                    )
                    fail("Source-tripping trigger must abort the page")
                } catch (error: StaleSnapshotStageException) {
                    assertEquals(STAGE_STALE_MESSAGE, error.message)
                    assertNull(error.cause)
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS stage_cursor_trip")
            }
            assertEquals(preCommit, database.claimSnapshot())
            assertEquals(0, database.stageRecordRows(STAGE_ID_1).size)
            assertEquals(0, database.stageTokenRows(STAGE_ID_1).size)
            try {
                run.appendSnapshotPage(
                    token,
                    stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                )
                fail("Tripped token must be consumed")
            } catch (_: StaleSnapshotStageException) {
            }
        }
    }

    @Test
    fun triggerRollback_abortsWholePageAndRetiresToken() = runTest {
        database.stageSeedBaseline()
        val page = stagePage(
            listOf(stageWorkoutUpsert(), stageRoutineUpsert()),
            STAGE_BASELINE_1, STAGE_TOKEN_1, true,
        )
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val token = run.appendSnapshotPage(run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT), page)
            val committedHeader = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
            database.claimExec(
                "CREATE TRIGGER stage_block_records BEFORE INSERT ON sync_snapshot_records " +
                    "BEGIN SELECT RAISE(ABORT, 'stage_blocked'); END",
            )
            try {
                try {
                    run.appendSnapshotPage(
                        token,
                        stagePage(listOf(stageCustomUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_2, true),
                    )
                    fail("Trigger failure must surface as owned storage failure")
                } catch (error: SnapshotStageStorageException) {
                    assertEquals(STAGE_STORAGE_MESSAGE, error.message)
                    assertFalse((error.message ?: "").contains("stage_blocked"))
                    assertFalse((error.message ?: "").contains("RAISE"))
                    assertNull(error.cause)
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS stage_block_records")
            }
            // Earlier committed pages survive; the failed page left no
            // partial rows and no header update.
            assertEquals(2, database.stageRecordRows(STAGE_ID_1).size)
            assertEquals(1, database.stageTokenRows(STAGE_ID_1).size)
            val after = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
            assertEquals(committedHeader.pageCount, after.pageCount)
            assertEquals(committedHeader.recordCount, after.recordCount)
            assertEquals(committedHeader.nextPageToken, after.nextPageToken)
            // Rollback still consumes the process-local token: retrying it
            // must be stale even though durable state did not advance.
            try {
                run.appendSnapshotPage(
                    token,
                    stagePage(listOf(stageCustomUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_2, true),
                )
                fail("Rolled-back token must be consumed")
            } catch (_: StaleSnapshotStageException) {
            }
            // Recovery is explicit reread, abandon and NEW-stage restart.
            val observation = checkNotNull(run.readSnapshotStage()) { "Must reread boundary" }
            assertFalse(observation.canAppend)
            run.abandonSnapshotStage(observation)
            val restarted = run.beginSnapshotStage(STAGE_ID_2, STAGE_LIMIT)
            assertTrue(restarted.canAppend)
            val settled = run.appendSnapshotPage(
                restarted,
                stagePage(listOf(stageCustomUpsert()), STAGE_BASELINE_1, null, false),
            )
            assertTrue(settled.downloaded)
        }
    }

    @Test
    fun ignoredHeaderWrite_yieldsCountZeroStaleWithFullRollback() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            val before = database.claimSnapshot()
            database.claimExec(
                "CREATE TRIGGER stage_ignore_header BEFORE UPDATE ON sync_snapshot_stages " +
                    "BEGIN SELECT RAISE(IGNORE); END",
            )
            try {
                try {
                    run.appendSnapshotPage(
                        token,
                        stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                    )
                    fail("Ignored header write must surface as count-zero stale")
                } catch (_: StaleSnapshotStageException) {
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS stage_ignore_header")
            }
            // Whole page rolled back: header, records and tokens unchanged.
            assertEquals(before, database.claimSnapshot())
            assertEquals(0, database.stageRecordRows(STAGE_ID_1).size)
            assertEquals(0, database.stageTokenRows(STAGE_ID_1).size)
            // The uncertain token retired without proving its outcome.
            try {
                run.appendSnapshotPage(
                    token,
                    stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                )
                fail("Uncertain token must retire")
            } catch (_: StaleSnapshotStageException) {
            }
        }
    }

    @Test
    fun postWriteCancellation_rollsBackRetiresAndRestartsExplicitly() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inCommit = AtomicBoolean(false)
        val writeSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        lateinit var runnerJob: Job
        // Test-only real Room query hook: observe the first staging write,
        // then cancel the caller Job on the FOLLOWING query. Callbacks fire
        // before SQL, so cancelling on the write itself would only prove
        // pre-execution interruption.
        val queryExecutor = Executors.newSingleThreadExecutor()
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (inCommit.get()) {
                        val lower = sql.lowercase()
                        val isWrite = lower.contains("insert") || lower.contains("update") ||
                            lower.contains("delete")
                        val isStageTable = lower.contains("sync_snapshot_stages") ||
                            lower.contains("sync_snapshot_records") ||
                            lower.contains("sync_snapshot_tokens")
                        if (!writeSeen.get() && isWrite && isStageTable) {
                            writeSeen.set(true)
                        } else if (writeSeen.getAndSet(false)) {
                            fired.set(true)
                            inCommit.set(false)
                            runnerJob.cancel(CancellationException("stage-test post-write cancel"))
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.stageSeedBaseline()
            val owner = claimOwner()
            callbackDb.claimPushStore(owner = owner).withRun(STAGE_EPOCH) { run ->
                val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                // Oracle taken after admission, not before it.
                val admitted = callbackDb.claimSnapshot()
                // The append runs in a child inheriting the live run's
                // coroutine context; the hook cancels that child after the
                // first observed write while the parent run stays live.
                coroutineScope {
                    val child = async {
                        run.appendSnapshotPage(
                            token,
                            stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                        )
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
                assertEquals(0, callbackDb.stageRecordRows(STAGE_ID_1).size)
                // Cancellation propagates unchanged: never converted into a
                // storage or stale failure, and the token retired.
                try {
                    run.checkSnapshotStage(token)
                    fail("Canceled child's token must be stale in the live run")
                } catch (_: StaleSnapshotStageException) {
                }
                // Uncertain commit requires explicit reread, abandon and a
                // NEW stage UUID restart, never token inference.
                val observation = checkNotNull(run.readSnapshotStage()) { "Must reread" }
                assertFalse(observation.canAppend)
                run.abandonSnapshotStage(observation)
                val restarted = run.beginSnapshotStage(STAGE_ID_2, STAGE_LIMIT)
                val settled = run.appendSnapshotPage(
                    restarted,
                    stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, null, false),
                )
                assertTrue(settled.downloaded)
            }
        } finally {
            callbackDb.close()
            queryExecutor.shutdown()
        }
    }

    @Test
    fun concurrentSameToken_settlesExactlyOnce() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            // Explicit inherited coroutineScope inside the live run preserves
            // context in children; never the runTest receiver scope.
            val outcomes = coroutineScope {
                val first = async {
                    try {
                        run.appendSnapshotPage(
                            token,
                            stagePage(
                                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)),
                                STAGE_BASELINE_1, STAGE_TOKEN_2, true,
                            ),
                        )
                        "first-ok"
                    } catch (_: StaleSnapshotStageException) {
                        "first-stale"
                    }
                }
                val second = async {
                    try {
                        run.appendSnapshotPage(
                            token,
                            stagePage(
                                listOf(stageRoutineUpsert(id = STAGE_ROUTINE_2)),
                                STAGE_BASELINE_1, STAGE_TOKEN_2, true,
                            ),
                        )
                        "second-ok"
                    } catch (_: StaleSnapshotStageException) {
                        "second-stale"
                    }
                }
                listOf(first.await(), second.await())
            }
            assertEquals(1, outcomes.count { it.endsWith("ok") })
            assertEquals(1, outcomes.count { it.endsWith("stale") })
            // Durable evidence matches exactly the winner's page, never a mix.
            val records = database.stageRecordRows(STAGE_ID_1)
            assertEquals(1, records.size)
            assertTrue(
                records.single().aggregateId == STAGE_WORKOUT_2 ||
                    records.single().aggregateId == STAGE_ROUTINE_2,
            )
            assertEquals(1L, database.stageRowsFor(CLAIM_ACCOUNT_A).single().pageCount)
            assertEquals(1L, database.stageRowsFor(CLAIM_ACCOUNT_A).single().recordCount)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sharedOwner_serializesStageRunsAcrossChain() = runTest {
        database.stageSeedBaseline()
        val owner = claimOwner()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            database.claimPushStore(owner = owner).withRun(STAGE_EPOCH) { run ->
                events += "first-enter"
                val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                entered.complete(Unit)
                gate.await()
                run.appendSnapshotPage(
                    token,
                    stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, null, false),
                )
                events += "first-exit"
            }
        }
        entered.await()
        val second = async {
            database.claimPushStore(owner = owner).withRun(STAGE_EPOCH) { run ->
                events += "second-enter"
                val observation = checkNotNull(run.readSnapshotStage()) { "Must observe settled stage" }
                assertTrue(observation.downloaded)
            }
        }
        runCurrent()
        assertEquals(listOf("first-enter"), events)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-enter", "first-exit", "second-enter"), events)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun queuedCancellation_leavesNoWrites() = runTest {
        database.stageSeedBaseline()
        val owner = claimOwner()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val before = database.claimSnapshot()
        val first = async {
            database.claimPushStore(owner = owner).withRun(STAGE_EPOCH) {
                entered.complete(Unit)
                gate.await()
            }
        }
        entered.await()
        val second = async {
            database.claimPushStore(owner = owner).withRun(STAGE_EPOCH) {
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
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun deepOwnership_mutatedCallerListsCommitAdmittedCopy() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryExecutor = Executors.newSingleThreadExecutor()
        val backingTx = Executors.newSingleThreadExecutor()
        val armed = AtomicBoolean(false)
        val captured = CompletableDeferred<Runnable>()
        // Deterministic admission barrier: the transaction executor decorator
        // runs normally until armed, then captures the next transaction
        // Runnable without running it. A separate query executor keeps reads
        // off the barrier.
        val hookDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(
                Executor { command ->
                    if (armed.getAndSet(false)) {
                        captured.complete(command)
                    } else {
                        backingTx.execute(command)
                    }
                },
            )
            .build()
        try {
            hookDb.stageSeedBaseline()
            hookDb.claimPushStore().withRun(STAGE_EPOCH) { run ->
                val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                val nested = mutableListOf(stageWorkoutEntry())
                val records = mutableListOf(
                    stageWorkoutUpsert(exercises = nested),
                )
                val page = stageMutablePage(records, STAGE_BASELINE_1, STAGE_TOKEN_1, true)
                armed.set(true)
                coroutineScope {
                    // Inherit the run context: the append suspends only after
                    // synchronous deep-ownership through admission.
                    val committing = async(start = CoroutineStart.UNDISPATCHED) {
                        run.appendSnapshotPage(token, page)
                    }
                    // The captured transaction proves the append ran
                    // synchronously to admission and is now suspended.
                    val runnable = captured.await()
                    assertFalse(committing.isCompleted)
                    // Mutate caller-owned nested lists only after suspension.
                    nested.clear()
                    records.clear()
                    records.add(stageRoutineUpsert())
                    // Admit the transaction on the owned backing executor.
                    backingTx.execute(runnable)
                    val settled = committing.await()
                    assertEquals(1L, settled.pageCount)
                }
                armed.set(false)
                // The retained body matches the admitted copy, not the
                // post-suspension caller mutation.
                val stored = JSONObject(hookDb.stageRecordRows(STAGE_ID_1).single().canonicalJson)
                assertEquals(STAGE_WORKOUT_1, stored.getString("id"))
                assertEquals("Staged Workout", stored.getString("name"))
                assertEquals(1, stored.getJSONArray("exercises").length())
            }
        } finally {
            hookDb.close()
            queryExecutor.shutdown()
            backingTx.shutdown()
        }
    }

    @Test
    fun abandonAfterSessionChange_preservesForeignStageAndRetainsOnCancel() = runTest {
        database.stageSeedBaseline()
        database.stageSeedSecondScope()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
        }
        // Foreign scope operates under its own authorized session.
        database.claimSeedDevice(CLAIM_ACCOUNT_B)
        database.claimPushStore(claimSession(CLAIM_ACCOUNT_B)).withRun(STAGE_EPOCH) { run ->
            run.beginSnapshotStage(STAGE_ID_FOREIGN, STAGE_LIMIT)
        }
        // A new run under the CURRENT authorized session/epoch may
        // observe/abandon the old same-account stage; it never adopts the
        // old origin fence for writes.
        database.claimSeedDevice(CLAIM_ACCOUNT_A, CLAIM_GENERATION + 1)
        database.claimPushStore(claimSession(CLAIM_ACCOUNT_A, CLAIM_GENERATION + 1)).withRun(STAGE_EPOCH) { run ->
            val observation = checkNotNull(run.readSnapshotStage()) { "Must observe old stage" }
            assertFalse(observation.canAppend)
            run.abandonSnapshotStage(observation)
        }
        assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
        // Other-account stages remain untouched by the abandonment.
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_B).size)
        assertEquals(STAGE_ID_FOREIGN, database.stageRowsFor(CLAIM_ACCOUNT_B).single().stageId)
        // Cancellation or run close without an explicit transaction retains
        // the foreign stage: no finally cleanup exists. Reseed the foreign
        // session first so admission itself cannot fail before cancellation.
        database.claimSeedDevice(CLAIM_ACCOUNT_B)
        val cancelling = async {
            database.claimPushStore(claimSession(CLAIM_ACCOUNT_B)).withRun(STAGE_EPOCH) { run ->
                checkNotNull(run.readSnapshotStage()) { "Must observe foreign stage" }
            }
        }
        cancelling.cancel()
        try {
            cancelling.await()
            fail("Cancelled observation must propagate")
        } catch (_: CancellationException) {
        }
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_B).size)
    }
}
