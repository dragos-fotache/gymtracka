package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StalePullCheckpointException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real Room checks for transport-time session/epoch/row/baseline fences, cancellation, whole-
 * response ownership before suspending checkpoint checks, and shared-owner admitted-run reuse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PullDeliveryFenceTest : GymDatabaseTest() {

    private fun dataPage(
        batchId: String = PULL_BATCH_1,
        cursor: String = PULL_CURSOR_2,
    ) = pullDeliveryPage(
        batches = listOf(
            pullBatch(
                changes = listOf(
                    pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
                ),
                batchId = batchId, cursor = cursor,
            ),
        ),
        nextCursor = cursor, hasMore = false,
    )

    @Test
    fun lateSessionOrEpochChange_fencesCommittedData() = runTest {
        val tampers = listOf(
            "account-switch" to
                "UPDATE sync_device_state SET active_account_id = '$PULL_ACCOUNT_B', session_generation = 99",
            "generation-bump" to "UPDATE sync_device_state SET session_generation = 8",
            "sign-out" to "UPDATE sync_device_state SET active_account_id = NULL",
            "cache-advance" to
                "UPDATE sync_account_cache SET cache_epoch = 1 WHERE account_id = '$PULL_ACCOUNT_A'",
        )
        tampers.forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline()
            val api = PullDeliveryGatedApi(database, PullScriptResponse.Page(dataPage()))
            supervisorScope {
                val runner = async {
                    database.claimPushStore().withRun(PULL_EPOCH) { run ->
                        PullDeliveryLoop(api).pull(run)
                    }
                }
                api.entered.await()
                database.claimExec(sql)
                val beforeRelease = database.claimSnapshot()
                api.gate.complete(Unit)
                try {
                    runner.await()
                    fail("$label must fence the late data page")
                } catch (_: StaleSessionException) {
                } catch (_: StaleCanonicalEpochException) {
                }
                assertEquals("$label one call", 1, api.calls.size)
                assertEquals("$label no late writes", beforeRelease, database.claimSnapshot())
                assertNull("$label canonical", database.ackCanonicalJson(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
                assertEquals("$label cursor", PULL_CURSOR_1, database.pullSyncCursor())
            }
            database.claimSeedDevice()
        }
    }

    @Test
    fun lateRowOrBaselineChange_fencesCommittedData() = runTest {
        val tampers = listOf(
            "cursor-rewrite" to "UPDATE sync_state SET cursor = 'moved' WHERE account_id = '$PULL_ACCOUNT_A'",
            "success-rewrite" to "UPDATE sync_state SET last_success_millis = 42 WHERE account_id = '$PULL_ACCOUNT_A'",
            "row-delete" to "DELETE FROM sync_state WHERE account_id = '$PULL_ACCOUNT_A'",
            "flag-cleared" to
                "UPDATE sync_account_cache SET baseline_complete = 0 WHERE account_id = '$PULL_ACCOUNT_A'",
            "cache-deleted" to "DELETE FROM sync_account_cache WHERE account_id = '$PULL_ACCOUNT_A'",
        )
        tampers.forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline()
            val api = PullDeliveryGatedApi(database, PullScriptResponse.Page(dataPage()))
            supervisorScope {
                val runner = async {
                    database.claimPushStore().withRun(PULL_EPOCH) { run ->
                        PullDeliveryLoop(api).pull(run)
                    }
                }
                api.entered.await()
                database.claimExec(sql)
                val beforeRelease = database.claimSnapshot()
                api.gate.complete(Unit)
                try {
                    runner.await()
                    fail("$label must fence the late data page")
                } catch (_: StalePullCheckpointException) {
                }
                assertEquals("$label no late writes", beforeRelease, database.claimSnapshot())
                assertNull("$label canonical", database.ackCanonicalJson(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
            }
        }
    }

    @Test
    fun lateSessionChange_fencesEmptyTailAndTypedFailure() = runTest {
        // Empty terminal page is still fenced after session loss.
        database.pullSeedBaseline()
        val emptyApi = PullDeliveryGatedApi(
            database,
            PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
        )
        supervisorScope {
            val runner = async {
                database.claimPushStore().withRun(PULL_EPOCH) { run ->
                    PullDeliveryLoop(emptyApi).pull(run)
                }
            }
            emptyApi.entered.await()
            database.claimExec("UPDATE sync_device_state SET session_generation = 8")
            val beforeRelease = database.claimSnapshot()
            emptyApi.gate.complete(Unit)
            try {
                runner.await()
                fail("late session change must fence the empty tail")
            } catch (_: StaleSessionException) {
            }
            assertEquals(beforeRelease, database.claimSnapshot())
            assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        }
        database.claimSeedDevice()
        // A typed failure racing an epoch bump surfaces the fence, never Failed.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.pullSeedBaseline()
        val failureApi = PullDeliveryGatedApi(
            database, PullScriptResponse.Failure(SyncApiException.Transient(5_000L)),
        )
        supervisorScope {
            val runner = async {
                database.claimPushStore().withRun(PULL_EPOCH) { run ->
                    PullDeliveryLoop(failureApi).pull(run)
                }
            }
            failureApi.entered.await()
            database.claimUpdateCache(PULL_ACCOUNT_A, 9L)
            val beforeRelease = database.claimSnapshot()
            failureApi.gate.complete(Unit)
            try {
                runner.await()
                fail("late epoch bump must fence the typed failure")
            } catch (_: StaleCanonicalEpochException) {
            }
            assertEquals(beforeRelease, database.claimSnapshot())
            assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        }
    }

    @Test
    fun cancelDuringHttp_stopsWithoutLaterFetch_earlierCommitsDurable() = runTest {
        database.pullSeedBaseline()
        val first = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                    cursor = PULL_CURSOR_2,
                ),
            ),
            nextCursor = PULL_CURSOR_2, hasMore = true,
        )
        val enteredSecond = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : GymSyncApi {
            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                check(!database.inTransaction()) { "pull must run outside Room" }
                calls += 1
                return if (calls == 1) {
                    check(cursor == PULL_CURSOR_1) { "first fetch must use the admitted boundary" }
                    first
                } else {
                    check(cursor == PULL_CURSOR_2) { "second fetch must chain the fresh boundary" }
                    enteredSecond.complete(Unit)
                    secondGate.await()
                    throw AssertionError("cancelled HTTP must not return")
                }
            }

            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                throw AssertionError("push must not be called")
            }

            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                throw AssertionError("snapshot must not be called")
            }
        }
        supervisorScope {
            val runner = async {
                database.claimPushStore().withRun(PULL_EPOCH) { run ->
                    PullDeliveryLoop(api).pull(run)
                }
            }
            enteredSecond.await()
            runner.cancel()
            try {
                runner.await()
                fail("cancelled HTTP must propagate cancellation")
            } catch (_: CancellationException) {
            }
            // No later fetch after cancellation; the first page stays durable.
            assertEquals(2, calls)
            assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
            assertEquals(7L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
        }
    }

    @Test
    fun injectedCancellationInApi_propagatesWithoutConversion() = runTest {
        database.pullSeedBaseline()
        val before = database.claimSnapshot()
        val injected = CancellationException("injected-pull-cancel")
        val api = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Unexpected(injected)))
        supervisorScope {
            val runner = async {
                try {
                    database.claimPushStore().withRun(PULL_EPOCH) { run ->
                        PullDeliveryLoop(api).pull(run)
                    }
                    fail("injected cancellation must propagate")
                } catch (expected: CancellationException) {
                    // Identity asserted inside the actual caller, not across await recovery.
                    assertTrue(expected === injected)
                    throw expected
                }
            }
            try {
                runner.await()
                fail("injected cancellation must propagate")
            } catch (_: CancellationException) {
            }
        }
        assertEquals(1, api.calls.size)
        assertEquals(before, database.claimSnapshot())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        assertNull(database.ackCanonicalJson(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
    }

    @Test
    fun ownedWholePage_cannotMutateWhileFirstCommitSuspends() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryExecutor = Executors.newSingleThreadExecutor()
        val backingTx = Executors.newSingleThreadExecutor()
        val armed = AtomicBoolean(false)
        val captured = CompletableDeferred<Runnable>()
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
            hookDb.pullSeedBaseline()
            // Caller-mutable nested lists: the driver must deep-own the full
            // typed page immediately after API return + ensureActive, before
            // the suspending post-response checkpoint fence.
            val changes = mutableListOf(
                pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
            )
            val batches = mutableListOf(
                pullDeliveryMutableBatch(changes, PULL_BATCH_1, PULL_CURSOR_2),
            )
            val page = pullDeliveryMutablePage(batches, PULL_CURSOR_2, false)
            // Arm interception from the API-return boundary: the flag flips
            // only after the transport returns, so the captured runnable is
            // the post-response fence/commit path, never the initial read.
            val apiReturned = CompletableDeferred<Unit>()
            val api = object : GymSyncApi {
                override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                    check(!hookDb.inTransaction()) { "pull must run outside Room" }
                    val result = PullDeliveryScriptApi(
                        hookDb,
                        mutableListOf(PullScriptResponse.Page(page)),
                    ).pull(cursor, limit)
                    apiReturned.complete(Unit)
                    armed.set(true)
                    return result
                }

                override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                    throw AssertionError("push must not be called")
                }

                override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                    throw AssertionError("snapshot must not be called")
                }
            }
            val result = hookDb.claimPushStore().withRun(PULL_EPOCH) { run ->
                coroutineScope {
                    val committing = async(start = CoroutineStart.UNDISPATCHED) {
                        PullDeliveryLoop(api).pull(run)
                    }
                    val runnable = captured.await()
                    assertTrue(apiReturned.isCompleted)
                    assertTrue(!committing.isCompleted)
                    // Mutate caller-owned nested lists only after suspension,
                    // at transaction-executor admission after API return.
                    batches.clear()
                    batches.add(
                        pullBatch(
                            changes = listOf(pullDelete(AggregateType.WORKOUT, PULL_UNKNOWN_CLOUD, 11L)),
                            batchId = PULL_BATCH_2, cursor = PULL_CURSOR_3,
                        ),
                    )
                    changes.clear()
                    changes.add(pullDelete(AggregateType.WORKOUT, PULL_UNKNOWN_CLOUD, 11L))
                    backingTx.execute(runnable)
                    committing.await()
                }
            }
            armed.set(false)
            // Deep ownership preceded suspension: the workout committed.
            assertEquals(1, result.committedBatches)
            assertTrue(result.stop is PullDeliveryStop.Tail)
            assertEquals(
                7L,
                hookDb.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision,
            )
            assertNull(hookDb.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_UNKNOWN_CLOUD))
            assertEquals(PULL_CURSOR_2, hookDb.pullSyncCursor())
        } finally {
            hookDb.close()
            queryExecutor.shutdown()
            backingTx.shutdown()
        }
    }

    @Test
    fun sharedOwner_serializesPullTransportAndReusesAdmittedRun() = runTest {
        database.pullSeedBaseline()
        val owner = claimOwner()
        val firstApi = PullDeliveryGatedApi(database, PullScriptResponse.Page(dataPage()))
        var secondCalls = 0
        val secondApi = object : GymSyncApi {
            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                check(!database.inTransaction()) { "pull must run outside Room" }
                secondCalls += 1
                // Serialized after the first driver: sees the fresh boundary.
                assertEquals(PULL_CURSOR_2, cursor)
                return pullDeliveryPage(emptyList(), PULL_CURSOR_2, false)
            }

            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                throw AssertionError("push must not be called")
            }

            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                throw AssertionError("snapshot must not be called")
            }
        }
        val events = mutableListOf<String>()
        val first = async {
            database.claimPushStore(claimSession(), owner).withRun(PULL_EPOCH) { run ->
                events += "first-enter"
                // Caller uses the admitted run before the driver without nesting.
                val before = checkNotNull(run.readPullCheckpoint()) { "Must read before driver" }
                assertEquals(PULL_CURSOR_1, before.cursor)
                val result = PullDeliveryLoop(firstApi).pull(run)
                assertEquals(1, result.committedBatches)
                assertTrue(result.stop is PullDeliveryStop.Tail)
                // And after the driver on the same run.
                val after = checkNotNull(run.readPullCheckpoint()) { "Must reread after driver" }
                assertEquals(PULL_CURSOR_2, after.cursor)
                events += "first-exit"
            }
        }
        firstApi.entered.await()
        val second = async {
            database.claimPushStore(claimSession(), owner).withRun(PULL_EPOCH) { run ->
                events += "second-enter"
                PullDeliveryLoop(secondApi).pull(run)
            }
        }
        runCurrent()
        assertEquals(listOf("first-enter"), events)
        firstApi.gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-enter", "first-exit", "second-enter"), events)
        assertEquals(1, firstApi.calls.size)
        assertEquals(1, secondCalls)
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
    }

    @Test
    fun queuedRunCancellation_createsNoFetch() = runTest {
        database.pullSeedBaseline()
        val before = database.claimSnapshot()
        val owner = claimOwner()
        val enteredOwner = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        val first = async {
            database.claimPushStore(claimSession(), owner).withRun(PULL_EPOCH) {
                enteredOwner.complete(Unit)
                releaseOwner.await()
            }
        }
        enteredOwner.await()
        val secondApi = PullDeliveryScriptApi(database, mutableListOf())
        val second = async {
            database.claimPushStore(claimSession(), owner).withRun(PULL_EPOCH) { run ->
                PullDeliveryLoop(secondApi).pull(run)
            }
        }
        runCurrent()
        second.cancel()
        try {
            second.await()
            fail("queued pull must observe cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(0, secondApi.calls.size)
        assertEquals(before, database.claimSnapshot())
        releaseOwner.complete(Unit)
        first.await()
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
    }

    @Test
    fun postWriteCancellationDuringSecondCommit_rollsBackKeepsEarlierBatchWithoutSuccessorFetch() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inCommit = AtomicBoolean(false)
        val writeSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        lateinit var runnerJob: kotlinx.coroutines.Job
        // Test-only real Room query hook, reusing the checkpoints mid-write
        // technique: observe the second commit's first write, then cancel
        // the driver Job on the following query. The first page commits
        // before the hook is armed, so only the second commit rolls back.
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryCallback(
                androidx.room.RoomDatabase.QueryCallback { sql, _ ->
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
            val firstPage = pullDeliveryPage(
                batches = listOf(
                    pullBatch(
                        changes = listOf(
                            pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
                        ),
                        cursor = PULL_CURSOR_2,
                    ),
                ),
                nextCursor = PULL_CURSOR_2, hasMore = true,
            )
            val secondPage = pullDeliveryPage(
                batches = listOf(
                    pullBatch(
                        changes = listOf(
                            pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord()),
                        ),
                        batchId = PULL_BATCH_2, cursor = PULL_CURSOR_3,
                    ),
                ),
                nextCursor = PULL_CURSOR_3, hasMore = false,
            )
            var calls = 0
            val api = object : GymSyncApi {
                override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                    check(!callbackDb.inTransaction()) { "pull must run outside Room" }
                    calls += 1
                    return if (calls == 1) {
                        firstPage
                    } else {
                        // Arm the post-write hook only for the second commit.
                        inCommit.set(true)
                        secondPage
                    }
                }

                override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                    throw AssertionError("push must not be called")
                }

                override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                    throw AssertionError("snapshot must not be called")
                }
            }
            val owner = claimOwner()
            supervisorScope {
                val runner = async {
                    try {
                        callbackDb.claimPushStore(claimSession(), owner).withRun(PULL_EPOCH) { run ->
                            PullDeliveryLoop(api).pull(run)
                        }
                        fail("post-write cancellation must propagate")
                    } catch (expected: CancellationException) {
                        throw expected
                    }
                }
                runnerJob = runner
                try {
                    runner.await()
                    fail("post-write cancellation must propagate")
                } catch (_: CancellationException) {
                }
            }
            assertTrue(fired.get())
            // Earlier committed page survives; the cancelled page fetched
            // nothing further and committed nothing.
            assertEquals(2, calls)
            assertEquals(PULL_CURSOR_2, callbackDb.pullSyncCursor())
            assertEquals(7L, callbackDb.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
            assertNull(callbackDb.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "routine", PULL_ROUTINE_A))
            assertEquals(PULL_LAST_SUCCESS, callbackDb.pullLastSuccess())
        } finally {
            callbackDb.close()
        }
    }
}
