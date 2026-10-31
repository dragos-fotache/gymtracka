package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
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
 * Real Room checks for snapshot transport fences, whole-page ownership before suspension,
 * capability retirement, cancellation, file reopen recovery, owner serialization and
 * foreign/closed/enclosing refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotDeliveryFenceTest : GymDatabaseTest() {

    private fun dataPage(
        id: String = STAGE_WORKOUT_1,
        baseline: String = DELIVERY_BASELINE_1,
        token: String? = DELIVERY_TOKEN_1,
        hasMore: Boolean = true,
    ) = stagePage(listOf(stageWorkoutUpsert(id = id)), baseline, token, hasMore)

    @Test
    fun lateSessionOrEpochChange_fencesLateDataPage() = runTest {
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
            val api = SnapshotDeliveryGatedApi(database, snapshotAfter = SnapshotScriptResponse.Page(dataPage()))
            val store = database.claimPushStore(claimSession(), claimOwner())
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            supervisorScope {
                val runner = async {
                    store.withRun(DELIVERY_EPOCH) { run ->
                        loop.start(run, DELIVERY_ID_1)
                    }
                }
                api.snapshotEntered.await()
                database.claimExec(sql)
                val beforeRelease = database.claimSnapshot()
                api.snapshotGate.complete(Unit)
                try {
                    runner.await()
                    fail("$label must fence the late data page")
                } catch (_: StaleSessionException) {
                } catch (_: StaleCanonicalEpochException) {
                }
                assertEquals("$label one call", 1, api.snapshotCalls.size)
                assertEquals("$label no late writes", beforeRelease, database.claimSnapshot())
                assertEquals("$label no records", 0, database.stageRecordRows(DELIVERY_ID_1).size)
            }
            database.claimSeedDevice()
        }
    }

    @Test
    fun lateSourceRowChange_fencesLateDataPageAsStale() = runTest {
        val tampers = listOf(
            "cursor-rewrite" to "UPDATE sync_state SET cursor = 'moved' WHERE account_id = '$CLAIM_ACCOUNT_A'",
            "success-rewrite" to "UPDATE sync_state SET last_success_millis = 42 WHERE account_id = '$CLAIM_ACCOUNT_A'",
            "row-delete" to "DELETE FROM sync_state WHERE account_id = '$CLAIM_ACCOUNT_A'",
            "flag-cleared" to
                "UPDATE sync_account_cache SET baseline_complete = 0 WHERE account_id = '$CLAIM_ACCOUNT_A'",
        )
        tampers.forEach { (label, sql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_snapshot_stages")
            database.claimExec("DELETE FROM sync_snapshot_records")
            database.claimExec("DELETE FROM sync_snapshot_tokens")
            database.stageSeedBaseline()
            val api = SnapshotDeliveryGatedApi(database, snapshotAfter = SnapshotScriptResponse.Page(dataPage()))
            val store = database.claimPushStore(claimSession(), claimOwner())
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            supervisorScope {
                val runner = async {
                    store.withRun(DELIVERY_EPOCH) { run ->
                        loop.start(run, DELIVERY_ID_1)
                    }
                }
                api.snapshotEntered.await()
                database.claimExec(sql)
                val beforeRelease = database.claimSnapshot()
                api.snapshotGate.complete(Unit)
                try {
                    runner.await()
                    fail("$label must fence the late data page")
                } catch (_: StaleSnapshotStageException) {
                }
                assertEquals("$label no late writes", beforeRelease, database.claimSnapshot())
                assertEquals("$label no records", 0, database.stageRecordRows(DELIVERY_ID_1).size)
            }
        }
    }

    @Test
    fun lateSessionChange_fencesEmptyTailMalformedFailureAndExpiry() = runTest {
        // Empty terminal page is still fenced after session loss.
        database.stageSeedBaseline()
        val emptyApi = SnapshotDeliveryGatedApi(
            database,
            snapshotAfter = SnapshotScriptResponse.Page(
                stagePage(emptyList(), DELIVERY_BASELINE_1, null, false),
            ),
        )
        val emptyStore = database.claimPushStore(claimSession(), claimOwner())
        val emptyLoop = SnapshotDeliveryLoop(emptyStore, emptyApi) { DELIVERY_NOW_MILLIS }
        supervisorScope {
            val runner = async {
                emptyStore.withRun(DELIVERY_EPOCH) { run ->
                    emptyLoop.start(run, DELIVERY_ID_1)
                }
            }
            emptyApi.snapshotEntered.await()
            database.claimExec("UPDATE sync_device_state SET session_generation = 8")
            val beforeRelease = database.claimSnapshot()
            emptyApi.snapshotGate.complete(Unit)
            try {
                runner.await()
                fail("late session change must fence the empty tail")
            } catch (_: StaleSessionException) {
            }
            assertEquals(beforeRelease, database.claimSnapshot())
        }
        database.claimSeedDevice()
        // Malformed page racing an epoch bump surfaces the fence, never Failed.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_snapshot_stages")
        database.claimExec("DELETE FROM sync_snapshot_records")
        database.claimExec("DELETE FROM sync_snapshot_tokens")
        database.stageSeedBaseline()
        val malformedApi = SnapshotDeliveryGatedApi(
            database,
            snapshotAfter = SnapshotScriptResponse.Page(
                stagePage(emptyList(), DELIVERY_BASELINE_1, DELIVERY_TOKEN_1, true),
            ),
        )
        val malformedStore = database.claimPushStore(claimSession(), claimOwner())
        val malformedLoop = SnapshotDeliveryLoop(malformedStore, malformedApi) { DELIVERY_NOW_MILLIS }
        supervisorScope {
            val runner = async {
                malformedStore.withRun(DELIVERY_EPOCH) { run ->
                    malformedLoop.start(run, DELIVERY_ID_1)
                }
            }
            malformedApi.snapshotEntered.await()
            database.claimUpdateCache(CLAIM_ACCOUNT_A, 9L)
            val beforeRelease = database.claimSnapshot()
            malformedApi.snapshotGate.complete(Unit)
            try {
                runner.await()
                fail("late epoch bump must fence the malformed page")
            } catch (_: StaleCanonicalEpochException) {
            }
            assertEquals(beforeRelease, database.claimSnapshot())
        }
        // Authentic expiry racing a source rewrite surfaces the fence, never RestartRequired.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_snapshot_stages")
        database.claimExec("DELETE FROM sync_snapshot_records")
        database.claimExec("DELETE FROM sync_snapshot_tokens")
        database.stageSeedBaseline()
        val expiryApi = SnapshotDeliveryGatedApi(
            database,
            snapshotAfter = SnapshotScriptResponse.Failure(SyncApiException.SnapshotExpired()),
        )
        val expiryStore = database.claimPushStore(claimSession(), claimOwner())
        val expiryLoop = SnapshotDeliveryLoop(expiryStore, expiryApi) { DELIVERY_NOW_MILLIS }
        supervisorScope {
            val runner = async {
                expiryStore.withRun(DELIVERY_EPOCH) { run ->
                    expiryLoop.start(run, DELIVERY_ID_1)
                }
            }
            expiryApi.snapshotEntered.await()
            database.claimExec("UPDATE sync_state SET cursor = 'moved' WHERE account_id = '$CLAIM_ACCOUNT_A'")
            val beforeRelease = database.claimSnapshot()
            expiryApi.snapshotGate.complete(Unit)
            try {
                runner.await()
                fail("late source rewrite must fence the expiry")
            } catch (_: StaleSnapshotStageException) {
            }
            assertEquals(beforeRelease, database.claimSnapshot())
        }
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
            hookDb.stageSeedBaseline()
            // Caller-mutable nested lists: the driver must synchronously
            // deep-own the ENTIRE page immediately after API return +
            // cancellation check, before the suspending post-response fence.
            val nested = mutableListOf(stageWorkoutEntry())
            val records = mutableListOf(stageWorkoutUpsert(exercises = nested))
            val page = stageMutablePage(records, DELIVERY_BASELINE_1, null, false)
            val apiReturned = CompletableDeferred<Unit>()
            val api = object : GymSyncApi {
                override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                    check(!hookDb.inTransaction()) { "snapshot must run outside Room" }
                    assertNull(pageToken)
                    apiReturned.complete(Unit)
                    armed.set(true)
                    return page
                }

                override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                    check(!hookDb.inTransaction()) { "push must run outside Room" }
                    assertTrue(requests.isEmpty())
                    return com.dragosfotache.gymtracka.data.sync.codec.decodePushResult("{\"results\":[]}")
                }

                override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                    throw AssertionError("pull must not be called")
                }
            }
            val store = hookDb.claimPushStore(hookDb.claimSessionForTest(), hookDb.claimOwnerForTest())
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            val started = store.withRun(DELIVERY_EPOCH) { run ->
                coroutineScope {
                    val committing = async(start = CoroutineStart.UNDISPATCHED) {
                        loop.start(run, DELIVERY_ID_1)
                    }
                    val runnable = captured.await()
                    assertTrue(apiReturned.isCompleted)
                    assertTrue(!committing.isCompleted)
                    // Mutate caller-owned nested lists only after suspension,
                    // at transaction-executor admission after API return.
                    nested.clear()
                    records.clear()
                    records.add(stageRoutineUpsert())
                    backingTx.execute(runnable)
                    committing.await()
                }
            }
            armed.set(false)
            val download = checkNotNull(started.download) { "must download" }
            assertTrue(download.stop is SnapshotDownloadStop.Downloaded)
            // Deep ownership preceded suspension: the workout committed.
            val stored = JSONObject(hookDb.stageRecordRows(DELIVERY_ID_1).single().canonicalJson)
            assertEquals(STAGE_WORKOUT_1, stored.getString("id"))
            assertEquals("Staged Workout", stored.getString("name"))
            assertEquals(1, stored.getJSONArray("exercises").length())
        } finally {
            hookDb.close()
            queryExecutor.shutdown()
            backingTx.shutdown()
        }
    }

    @Test
    fun cancelDuringSnapshot_stopsWithoutLaterFetch_earlierCommitsDurable() = runTest {
        database.stageSeedBaseline()
        val first = dataPage()
        val enteredSecond = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : GymSyncApi {
            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                check(!database.inTransaction()) { "snapshot must run outside Room" }
                calls += 1
                return if (calls == 1) {
                    assertNull(pageToken)
                    first
                } else {
                    assertEquals(DELIVERY_TOKEN_1, pageToken)
                    enteredSecond.complete(Unit)
                    secondGate.await()
                    throw AssertionError("cancelled snapshot must not return")
                }
            }

            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                check(!database.inTransaction()) { "push must run outside Room" }
                return com.dragosfotache.gymtracka.data.sync.codec.decodePushResult("{\"results\":[]}")
            }

            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                throw AssertionError("pull must not be called")
            }
        }
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        supervisorScope {
            val runner = async {
                store.withRun(DELIVERY_EPOCH) { run ->
                    loop.start(run, DELIVERY_ID_1, maxPages = 10)
                }
            }
            enteredSecond.await()
            runner.cancel()
            try {
                runner.await()
                fail("cancelled snapshot must propagate cancellation")
            } catch (_: CancellationException) {
            }
            // No later fetch after cancellation; the first page stays durable.
            assertEquals(2, calls)
            assertEquals(1, database.stageRecordRows(DELIVERY_ID_1).size)
            assertEquals(1L, database.stageRowsFor(CLAIM_ACCOUNT_A).single().pageCount)
        }
    }

    @Test
    fun injectedCancellationInSnapshot_propagatesWithoutConversion() = runTest {
        database.stageSeedBaseline()
        val before = database.stagePriorSnapshot()
        val injected = CancellationException("injected-snapshot-cancel")
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Unexpected(injected)),
        )
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        supervisorScope {
            val runner = async {
                try {
                    store.withRun(DELIVERY_EPOCH) { run ->
                        loop.start(run, DELIVERY_ID_1)
                    }
                    fail("injected cancellation must propagate")
                } catch (expected: CancellationException) {
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
        assertEquals(1, api.snapshotCalls.size)
        assertEquals(before, database.stagePriorSnapshot())
        assertEquals(0, database.stageRecordRows(DELIVERY_ID_1).size)
    }

    @Test
    fun commitFault_retiresTokenRequiringExplicitRereadAbandonAndNewUuidRestart() = runTest {
        database.stageSeedBaseline()
        val first = dataPage()
        val second = stagePage(
            listOf(stageCustomUpsert()), DELIVERY_BASELINE_1, DELIVERY_TOKEN_2, true,
        )
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Page(first), SnapshotScriptResponse.Page(second)),
        )
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        // Commit the first page under a unit budget, then fault the second
        // page's commit with a record-insert trigger armed only between the
        // two driver invocations on the SAME admitted run.
        store.withRun(DELIVERY_EPOCH) { run ->
            val limited = checkNotNull(loop.start(run, DELIVERY_ID_1, maxPages = 1).download) {
                "first page must limit"
            }
            assertTrue(limited.stop is SnapshotDownloadStop.LimitReached)
            database.claimExec(
                "CREATE TRIGGER delivery_block_records BEFORE INSERT ON sync_snapshot_records " +
                    "BEGIN SELECT RAISE(ABORT, 'delivery_blocked'); END",
            )
            try {
                try {
                    loop.continueDownload(run, limited, maxPages = 5)
                    fail("trigger fault must surface as owned storage failure")
                } catch (error: com.dragosfotache.gymtracka.data.sync.store.SnapshotStageStorageException) {
                    assertEquals(STAGE_STORAGE_MESSAGE, error.message)
                    assertFalse((error.message ?: "").contains("delivery_blocked"))
                    assertNull(error.cause)
                }
            } finally {
                database.claimExec("DROP TRIGGER IF EXISTS delivery_block_records")
            }
            val callsAfterFault = api.snapshotCalls.size
            // The failed write retired the process-local token: retrying the
            // old LimitReached result while the SAME run is still live is
            // stale BEFORE another snapshot fetch.
            try {
                loop.continueDownload(run, limited, maxPages = 5)
                fail("faulted token must be stale without another fetch")
            } catch (_: StaleSnapshotStageException) {
            }
            assertEquals(callsAfterFault, api.snapshotCalls.size)
            // Earlier committed page survives inside the SAME live run: the
            // failed page left no partial rows and no header update. These
            // survival assertions must precede the reread/abandon below.
            assertEquals(1, database.stageRecordRows(DELIVERY_ID_1).size)
            assertEquals(1, database.stageTokenRows(DELIVERY_ID_1).size)
            val committedHeader = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
            assertEquals(1L, committedHeader.pageCount)
            assertEquals(1L, committedHeader.recordCount)
            assertEquals(DELIVERY_TOKEN_1, committedHeader.nextPageToken)
            // Uncertain commit requires durable reread and explicit abandon.
            val observation = checkNotNull(run.readSnapshotStage()) { "must reread" }
            assertFalse(observation.canAppend)
            run.abandonSnapshotStage(observation)
        }
        // Abandoned inline above: no stage, record or token rows remain for
        // the NEW UUID restart.
        assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
        assertEquals(emptyList<Any>(), database.stageRecordRows(DELIVERY_ID_1))
        assertEquals(emptyList<Any>(), database.stageTokenRows(DELIVERY_ID_1))
        val restartApi = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(
                SnapshotScriptResponse.Page(stagePage(emptyList(), DELIVERY_BASELINE_1, null, false)),
            ),
        )
        val restartLoop = SnapshotDeliveryLoop(store, restartApi) { DELIVERY_NOW_MILLIS }
        val restarted = store.withRun(DELIVERY_EPOCH) { run ->
            restartLoop.start(run, DELIVERY_ID_2)
        }
        assertTrue(checkNotNull(restarted.download).stop is SnapshotDownloadStop.Downloaded)
        assertEquals(listOf(RecordedSnapshotCall(null, 200)), restartApi.snapshotCalls)
    }

    @Test
    fun postWriteCancellation_retiresAndRestartsExplicitly() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inCommit = AtomicBoolean(false)
        val writeSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        var runnerJob: Job? = null
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
                            runnerJob?.cancel(CancellationException("delivery-test post-write cancel"))
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.stageSeedBaseline()
            val prior = callbackDb.stagePriorSnapshot()
            val owner = claimOwner()
            val store = callbackDb.claimPushStore(claimSession(), owner)
            // Arm ONLY synchronously in the actual snapshot response, after
            // begin committed: captures the actual caller Job there, so the
            // callback cancels on the FOLLOWING query after a page write.
            val api = object : GymSyncApi {
                override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                    check(!callbackDb.inTransaction()) { "snapshot must run outside Room" }
                    // Explicit child context: unqualified coroutineContext can
                    // bind the enclosing runTest receiver instead of this call.
                    runnerJob = currentCoroutineContext()[Job]
                    inCommit.set(true)
                    return stagePage(listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, null, false)
                }

                override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                    check(!callbackDb.inTransaction()) { "push must run outside Room" }
                    return com.dragosfotache.gymtracka.data.sync.codec.decodePushResult("{\"results\":[]}")
                }

                override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                    throw AssertionError("pull must not be called")
                }
            }
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            store.withRun(DELIVERY_EPOCH) { run ->
                coroutineScope {
                    val child = async {
                        loop.start(run, DELIVERY_ID_1)
                    }
                    try {
                        child.await()
                        fail("mid-write cancellation must propagate, never a commit")
                    } catch (_: CancellationException) {
                    } finally {
                        inCommit.set(false)
                    }
                }
                assertTrue("hook must fire mid page-commit", fired.get())
                // Cancellation propagates unchanged, never converted into a
                // storage or stale failure; prior tables stand.
                assertEquals(prior, callbackDb.stagePriorSnapshot())
                // The begin-committed stage header survives at zero with no
                // partial records from the rolled-back page commit.
                assertEquals(1, callbackDb.stageRowsFor(CLAIM_ACCOUNT_A).size)
                assertEquals(0L, callbackDb.stageRowsFor(CLAIM_ACCOUNT_A).single().pageCount)
                assertEquals(0, callbackDb.stageRecordRows(DELIVERY_ID_1).size)
                // Uncertain commit requires explicit reread and abandon.
                val observation = checkNotNull(run.readSnapshotStage()) { "must reread" }
                assertFalse(observation.canAppend)
                run.abandonSnapshotStage(observation)
            }
            assertEquals(emptyList<Any>(), callbackDb.stageRowsFor(CLAIM_ACCOUNT_A))
        } finally {
            callbackDb.close()
            queryExecutor.shutdown()
        }
    }

    @Test
    fun fileReopen_preservesEarlierPagesRequiringExplicitAbandonAndNewUuidRestart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = claimUniqueDbName("delivery-partial")
        val partialDb = claimFileDatabase(context, dbName)
        var outboxBytes = emptyList<String>()
        var afterCapturePrior = ""
        try {
            partialDb.stageSeedBaseline()
            var snapshotCalls = 0
            val api = object : GymSyncApi {
                override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                    check(!partialDb.inTransaction()) { "snapshot must run outside Room" }
                    snapshotCalls += 1
                    // Allowed newer capture AFTER begin, inside the live run.
                    partialDb.ackCaptureWorkoutHead(
                        mutationId = CLAIM_MUTATION_1,
                        rootId = CLAIM_WORKOUT_A,
                        name = "Delivery Workout",
                    )
                    outboxBytes = partialDb.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson }
                    afterCapturePrior = partialDb.stagePriorSnapshot()
                    return dataPage()
                }

                override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                    check(!partialDb.inTransaction()) { "push must run outside Room" }
                    return com.dragosfotache.gymtracka.data.sync.codec.decodePushResult("{\"results\":[]}")
                }

                override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                    throw AssertionError("pull must not be called")
                }
            }
            val store = partialDb.claimPushStore(claimSession(), claimOwner())
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            store.withRun(DELIVERY_EPOCH) { run ->
                val started = loop.start(run, DELIVERY_ID_1, maxPages = 1)
                val limited = checkNotNull(started.download) { "must limit" }
                assertTrue(limited.stop is SnapshotDownloadStop.LimitReached)
            }
            assertEquals(1, snapshotCalls)
        } finally {
            partialDb.close()
        }
        val reopened = claimFileDatabase(context, dbName)
        try {
            reopened.claimSeedDevice()
            // No implicit resume, publication or cleanup across restart, and
            // the complete prior-table snapshot stands across reopen.
            assertEquals(1, reopened.stageRowsFor(CLAIM_ACCOUNT_A).size)
            assertEquals(1, reopened.stageRecordRows(DELIVERY_ID_1).size)
            assertEquals(afterCapturePrior, reopened.stagePriorSnapshot())
            assertEquals(outboxBytes, reopened.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson })
            val store = reopened.claimPushStore(claimSession(), claimOwner())
            store.withRun(DELIVERY_EPOCH) { run ->
                val observation = checkNotNull(run.readSnapshotStage()) { "partial must survive reopen" }
                assertFalse(observation.canAppend)
                assertEquals(DELIVERY_BASELINE_1, observation.baselineCursor)
                // Old results never continue in a new run; observation never appends.
                try {
                    run.appendSnapshotPage(
                        observation,
                        stagePage(
                            listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)),
                            DELIVERY_BASELINE_1, DELIVERY_TOKEN_2, true,
                        ),
                    )
                    fail("restart observation must never resume append authority")
                } catch (_: StaleSnapshotStageException) {
                }
                run.abandonSnapshotStage(observation)
            }
            assertEquals(emptyList<Any>(), reopened.stageRowsFor(CLAIM_ACCOUNT_A))
            // Explicit abandon touches staging only: prior tables and the
            // exact retained intent bytes stand; nothing is cleared to force
            // a purported recovery.
            assertEquals(afterCapturePrior, reopened.stagePriorSnapshot())
            assertEquals(outboxBytes, reopened.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson })
            assertEquals(STAGE_SYNC_CURSOR, reopened.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
            // Legitimate restart drains the retained intent through a real
            // accepted exact push ack to fenced Empty, then a NEW UUID stage
            // with a null request token.
            val pushResult = ackAcceptedResult(
                CLAIM_MUTATION_1,
                ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L)),
            )
            val restartApi = SnapshotDeliveryScriptApi(
                reopened,
                mutableListOf(SyncPushScript.Respond(pushResult)),
                mutableListOf(
                    SnapshotScriptResponse.Page(stagePage(emptyList(), DELIVERY_BASELINE_1, null, false)),
                ),
            )
            val restartStore = reopened.claimPushStore(claimSession(), claimOwner())
            val restartLoop = SnapshotDeliveryLoop(restartStore, restartApi) { DELIVERY_NOW_MILLIS }
            val restarted = restartStore.withRun(DELIVERY_EPOCH) { run ->
                restartLoop.start(run, DELIVERY_ID_2)
            }
            assertEquals(1, restarted.push.acknowledgedMutations)
            assertTrue(restarted.push.stop is PushDeliveryStop.Empty)
            assertTrue(checkNotNull(restarted.download).stop is SnapshotDownloadStop.Downloaded)
            assertEquals(1, restartApi.pushCalls.size)
            assertEquals(listOf(RecordedSnapshotCall(null, 200)), restartApi.snapshotCalls)
            assertEquals(0, reopened.claimOutboxFor(CLAIM_ACCOUNT_A).size)
        } finally {
            reopened.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun sharedOwner_serializesSnapshotRunsAcrossNetworkContinuations() = runTest {
        database.stageSeedBaseline()
        val owner = claimOwner()
        val gate = CompletableDeferred<Unit>()
        var snapshotCalls = 0
        val calls = mutableListOf<RecordedSnapshotCall>()
        // First page nonterminal, second empty terminal; the continuation
        // fetch gates so the queued second run provably waits for the chain.
        val api = object : GymSyncApi {
            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                check(!database.inTransaction()) { "snapshot must run outside Room" }
                snapshotCalls += 1
                calls += RecordedSnapshotCall(pageToken, limit)
                return if (snapshotCalls == 1) {
                    assertNull(pageToken)
                    dataPage()
                } else {
                    assertEquals(DELIVERY_TOKEN_1, pageToken)
                    gate.await()
                    stagePage(emptyList(), DELIVERY_BASELINE_1, null, false)
                }
            }

            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                check(!database.inTransaction()) { "push must run outside Room" }
                return com.dragosfotache.gymtracka.data.sync.codec.decodePushResult("{\"results\":[]}")
            }

            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                throw AssertionError("pull must not be called")
            }
        }
        val store = database.claimPushStore(claimSession(), owner)
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        val events = mutableListOf<String>()
        val limitedSignal = CompletableDeferred<Unit>()
        val first = async {
            store.withRun(DELIVERY_EPOCH) { run ->
                events += "first-enter"
                val limited = checkNotNull(loop.start(run, DELIVERY_ID_1, maxPages = 1).download) {
                    "first page must limit"
                }
                assertTrue(limited.stop is SnapshotDownloadStop.LimitReached)
                events += "first-limited"
                limitedSignal.complete(Unit)
                // The shared physicalDB owner stays held across this SAME-run
                // continuation: the queued second run must wait for it.
                val settled = loop.continueDownload(run, limited, maxPages = 1)
                assertTrue(settled.stop is SnapshotDownloadStop.Downloaded)
                events += "first-exit"
            }
        }
        limitedSignal.await()
        val second = async {
            store.withRun(DELIVERY_EPOCH) { run ->
                events += "second-enter"
                val observation = checkNotNull(run.readSnapshotStage()) { "must observe settled stage" }
                assertTrue(observation.downloaded)
            }
        }
        runCurrent()
        assertEquals(listOf("first-enter", "first-limited"), events)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-enter", "first-limited", "first-exit", "second-enter"), events)
        // Exact two-request chain across the continuation boundary.
        assertEquals(
            listOf(RecordedSnapshotCall(null, 200), RecordedSnapshotCall(DELIVERY_TOKEN_1, 200)),
            calls,
        )
    }

    @Test
    fun queuedCancellation_leavesNoWrites() = runTest {
        database.stageSeedBaseline()
        val owner = claimOwner()
        val api = SnapshotDeliveryGatedApi(
            database,
            snapshotAfter = SnapshotScriptResponse.Page(
                stagePage(listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, null, false),
            ),
        )
        val store = database.claimPushStore(claimSession(), owner)
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        val first = async {
            store.withRun(DELIVERY_EPOCH) { run ->
                loop.start(run, DELIVERY_ID_1)
            }
        }
        api.snapshotEntered.await()
        // Preservation oracle AFTER the first snapshot entered (begin already
        // committed the header, page fetch gated) and BEFORE the second run
        // is queued.
        val gated = database.claimSnapshot()
        // Queue a second run while the first holds the shared physicalDB owner,
        // then cancel it before admission: it must never enter or write.
        val second = async {
            store.withRun(DELIVERY_EPOCH) { _ ->
                fail("cancelled run must never enter")
            }
        }
        runCurrent()
        second.cancel()
        try {
            second.await()
            fail("queued run must observe cancellation")
        } catch (_: CancellationException) {
        }
        // Immediately after cancel, while the first run remains gated.
        assertEquals(gated, database.claimSnapshot())
        api.snapshotGate.complete(Unit)
        first.await()
        // First run's terminal commit stands; the cancelled queue left nothing extra.
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
        assertEquals(1, database.stageRecordRows(DELIVERY_ID_1).size)
        assertTrue(gated != database.claimSnapshot())
    }

    @Test
    fun foreignClosedOutsideAndEnclosing_rejectWithoutWrites() = runTest {
        database.stageSeedBaseline()
        val api = SnapshotDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        var closedRun: com.dragosfotache.gymtracka.data.sync.store.PushRun? = null
        store.withRun(DELIVERY_EPOCH) { run ->
            closedRun = run
        }
        val live = database.claimSnapshot()
        // Closed-run capability cannot drive the loop after its run completed.
        try {
            // Suspend call on a closed run must reject without HTTP/effects.
            store.withRun(DELIVERY_EPOCH) { _ ->
                @Suppress("DEPRECATION")
                loop.start(checkNotNull(closedRun) { "must retain" }, DELIVERY_ID_1)
            }
            fail("closed run must reject use")
        } catch (_: IllegalStateException) {
        }
        assertEquals(live, database.claimSnapshot())
        // Enclosing-transaction use rejects as well.
        store.withRun(DELIVERY_EPOCH) { run ->
            database.openHelper.writableDatabase.beginTransaction()
            try {
                try {
                    loop.start(run, DELIVERY_ID_1)
                    fail("enclosing-transaction use must reject")
                } catch (_: IllegalStateException) {
                }
            } finally {
                database.openHelper.writableDatabase.endTransaction()
            }
        }
        assertEquals(live, database.claimSnapshot())
        assertEquals(0, api.snapshotCalls.size)
    }
}

private fun GymDatabase.claimSessionForTest(): com.dragosfotache.gymtracka.data.sync.store.RepositorySession =
    claimSession()

private fun GymDatabase.claimOwnerForTest(): com.dragosfotache.gymtracka.data.sync.store.PushRunOwner =
    claimOwner()
