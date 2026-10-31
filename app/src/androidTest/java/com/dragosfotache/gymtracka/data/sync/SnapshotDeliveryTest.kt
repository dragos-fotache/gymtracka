package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodePushResult
import com.dragosfotache.gymtracka.data.sync.engine.MAX_PUSH_DELIVERY_MUTATIONS
import com.dragosfotache.gymtracka.data.sync.engine.MAX_SNAPSHOT_DELIVERY_PAGES
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageIntentException
import com.dragosfotache.gymtracka.data.sync.store.StaleSnapshotStageException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real Room checks for push-first snapshot admission, exact token/limit chaining, complete multi-
 * family values, bounded same-run continuation beyond 100 pages, typed failure/expiry and HTTP
 * adapter composition.
 */
class SnapshotDeliveryTest : GymDatabaseTest() {

    @Test
    fun initialNullThenOpaqueContinuationWithFixedLimit_commitsWholeMultiFamilyPages() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        val cursorBefore = database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor
        val successBefore = database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis
        // Page one carries a large whole workout whose exercise references a
        // custom record that only arrives on a LATER page, plus a routine.
        val workout = stageWorkoutUpsert(
            id = STAGE_WORKOUT_1,
            revision = 7L,
            exercises = listOf(stageWorkoutEntry(exerciseId = STAGE_CUSTOM_1, notes = STAGE_LARGE_NOTES)),
        )
        val pageOne = stagePage(
            listOf(workout, stageRoutineUpsert(id = STAGE_ROUTINE_1, revision = 3L)),
            DELIVERY_BASELINE_1, DELIVERY_TOKEN_1, true,
        )
        // Opaque continuation includes whitespace; fixed limit preserved end to end.
        val wsToken = DELIVERY_WS_TOKEN
        val pageTwo = stagePage(
            listOf(stageCustomUpsert(id = STAGE_CUSTOM_1, revision = 5L)),
            DELIVERY_BASELINE_1, wsToken, true,
        )
        val pageThree = stagePage(
            listOf(stageRoutineUpsert(id = STAGE_WORKOUT_1, name = "Namespaced Routine", revision = 9L)),
            DELIVERY_BASELINE_1, null, false,
        )
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(
                SnapshotScriptResponse.Page(pageOne),
                SnapshotScriptResponse.Page(pageTwo),
                SnapshotScriptResponse.Page(pageThree),
            ),
        )
        // Drive through the loop's admitted run: push-empty then three pages
        // in a single start (default budget covers all three).
        val result = loopHarness(api, DELIVERY_ID_1, pageLimit = DELIVERY_LIMIT)
        assertTrue(result.download?.stop is SnapshotDownloadStop.Downloaded)
        val download = checkNotNull(result.download) { "empty push must authorize download" }
        assertTrue(result.push.stop is PushDeliveryStop.Empty)
        assertEquals(0, result.push.acknowledgedMutations)
        assertEquals(3, download.committedPages)
        assertEquals(4, download.committedRecords)
        assertEquals(DELIVERY_ID_1, download.stage.stageId)
        assertEquals(DELIVERY_LIMIT, download.stage.pageLimit)
        assertEquals(DELIVERY_BASELINE_1, download.stage.baselineCursor)
        assertNull(download.stage.nextPageToken)
        assertTrue(download.stage.downloaded)
        assertFalse(download.stage.canAppend)
        assertEquals(3L, download.stage.pageCount)
        assertEquals(4L, download.stage.recordCount)
        // Exact opaque chaining: null initial, then opaque, then whitespace.
        assertEquals(
            listOf(
                RecordedSnapshotCall(null, DELIVERY_LIMIT),
                RecordedSnapshotCall(DELIVERY_TOKEN_1, DELIVERY_LIMIT),
                RecordedSnapshotCall(wsToken, DELIVERY_LIMIT),
            ),
            api.snapshotCalls,
        )
        assertEquals(listOf(false, false, false), api.seenSnapshotInTransaction)
        assertEquals(0, api.pushCalls.size)
        // Whole-record fidelity incl. later dependency and 128KiB body.
        val records = database.stageRecordRows(DELIVERY_ID_1)
        assertEquals(4, records.size)
        assertEquals(
            listOf("workout", "routine", "customExercise", "routine"),
            records.map { it.aggregateType },
        )
        val workoutJson = JSONObject(
            records.first { it.aggregateId == STAGE_WORKOUT_1 && it.aggregateType == "workout" }.canonicalJson,
        )
        assertEquals("Staged Workout", workoutJson.getString("name"))
        assertEquals(DELIVERY_WS_TOKEN, database.stageTokenRows(DELIVERY_ID_1)[1].pageToken)
        assertEquals(STAGE_LARGE_NOTES, workoutJson.getJSONArray("exercises").getJSONObject(0).getString("notes"))
        // Staging leaves prior tables, sync cursor and last-success untouched.
        assertEquals(prior, database.stagePriorSnapshot())
        assertEquals(cursorBefore, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        assertEquals(successBefore, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis)
        assertEquals(0, database.claimCount("SELECT COUNT(*) FROM sync_canonical_roots"))
    }

    @Test
    fun emptyTerminalPage_downloadsWithoutRecordsAndNoExtraProbe() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(
                SnapshotScriptResponse.Page(stagePage(emptyList(), DELIVERY_BASELINE_1, null, false)),
            ),
        )
        val download = checkNotNull(loopHarness(api, DELIVERY_ID_1).download) { "must download" }
        assertTrue(download.stop is SnapshotDownloadStop.Downloaded)
        assertEquals(1, download.committedPages)
        assertEquals(0, download.committedRecords)
        assertTrue(download.stage.downloaded)
        assertEquals(1, api.snapshotCalls.size)
        assertEquals(RecordedSnapshotCall(null, 200), api.snapshotCalls.single())
        assertEquals(emptyList<Any>(), database.stageRecordRows(DELIVERY_ID_1))
        assertEquals(emptyList<Any>(), database.stageTokenRows(DELIVERY_ID_1))
        assertEquals(prior, database.stagePriorSnapshot())
    }

    @Test
    fun terminalOnFinalBudgetPage_winsWithoutExtraProbe() = runTest {
        database.stageSeedBaseline()
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(
                SnapshotScriptResponse.Page(
                    stagePage(listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, null, false),
                ),
            ),
        )
        val download = checkNotNull(loopHarness(api, DELIVERY_ID_1, maxPages = 1).download) {
            "terminal on final page must download"
        }
        assertTrue(download.stop is SnapshotDownloadStop.Downloaded)
        assertEquals(1, download.committedPages)
        assertEquals(1, api.snapshotCalls.size)
    }

    @Test
    fun strictPreeffectBounds_rejectBeforePushRoomOrHttp() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        assertEquals(100, MAX_SNAPSHOT_DELIVERY_PAGES)
        var clockCalls = 0
        val api = SnapshotDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) {
            clockCalls += 1
            DELIVERY_NOW_MILLIS
        }
        store.withRun(DELIVERY_EPOCH) { run ->
            // Invalid stage IDs BEFORE any push: push must never run.
            listOf("not-a-uuid", "", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA").forEach { badId ->
                try {
                    loop.start(run, badId, pageLimit = DELIVERY_LIMIT)
                    fail("Bad stage ID [$badId] must fail before effects")
                } catch (e: Exception) {
                    assertTrue(e is SyncModelException || e is IllegalArgumentException)
                }
            }
            // Budget bounds BEFORE any clock, Room or HTTP effects.
            listOf(0, -1, MAX_SNAPSHOT_DELIVERY_PAGES + 1, 101, Int.MAX_VALUE).forEach { bad ->
                try {
                    loop.start(run, DELIVERY_ID_1, maxPages = bad, pageLimit = DELIVERY_LIMIT)
                    fail("maxPages=$bad must be rejected")
                } catch (_: IllegalArgumentException) {
                }
            }
            listOf(0, -1, MAX_PUSH_DELIVERY_MUTATIONS + 1, 101).forEach { bad ->
                try {
                    loop.start(run, DELIVERY_ID_1, maxPushMutations = bad, pageLimit = DELIVERY_LIMIT)
                    fail("maxPush=$bad must be rejected")
                } catch (_: IllegalArgumentException) {
                }
            }
            listOf(0, -1, 501, 1000, Int.MAX_VALUE).forEach { bad ->
                try {
                    loop.start(run, DELIVERY_ID_1, pageLimit = bad)
                    fail("pageLimit=$bad must be rejected")
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        assertEquals("rejected starts must not read the clock", 0, clockCalls)
        assertEquals(0, api.pushCalls.size)
        assertEquals(0, api.snapshotCalls.size)
        assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
        assertEquals(prior, database.stagePriorSnapshot())
        // Continuation budgets and same-run superseded results reject without HTTP.
        val pageOne = stagePage(listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, DELIVERY_TOKEN_1, true)
        val pageTwo = stagePage(
            listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), DELIVERY_BASELINE_1, null, false,
        )
        val api2 = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Page(pageOne), SnapshotScriptResponse.Page(pageTwo)),
        )
        val loop2 = SnapshotDeliveryLoop(store, api2) { DELIVERY_NOW_MILLIS }
        store.withRun(DELIVERY_EPOCH) { run ->
            val limited = checkNotNull(loop2.start(run, DELIVERY_ID_1, maxPages = 1).download) {
                "first page must limit"
            }
            assertTrue(limited.stop is SnapshotDownloadStop.LimitReached)
            assertEquals(1, api2.snapshotCalls.size)
            listOf(0, -1, MAX_SNAPSHOT_DELIVERY_PAGES + 1, 101).forEach { bad ->
                try {
                    loop2.continueDownload(run, limited, maxPages = bad)
                    fail("continue maxPages=$bad must be rejected")
                } catch (_: IllegalArgumentException) {
                }
            }
            assertEquals(1, api2.snapshotCalls.size)
            val downloaded = loop2.continueDownload(run, limited, maxPages = 5)
            assertTrue(downloaded.stop is SnapshotDownloadStop.Downloaded)
            assertEquals(2, api2.snapshotCalls.size)
            // The superseded prior result is stale in the same run even
            // though it was issued by this driver: no further HTTP.
            try {
                loop2.continueDownload(run, limited, maxPages = 5)
                fail("superseded prior result must be stale")
            } catch (_: StaleSnapshotStageException) {
            }
            assertEquals(2, api2.snapshotCalls.size)
        }
    }

    @Test
    fun nonterminalBudget_returnsLimitWithoutProbe_thenSameRunContinuationBeyond100Pages() = runTest {
        database.stageSeedBaseline()
        // 102 total pages prove no permanent 100-page stage ceiling: the
        // per-invocation budget binds each call, never the stage total.
        val totalPages = 102
        val pages = mutableListOf<SnapshotScriptResponse>()
        var baseline = DELIVERY_BASELINE_1
        repeat(totalPages) { index ->
            val recordId = deliveryUuid(index)
            val token = if (index == totalPages - 1) null else deliveryToken(index)
            val hasMore = index != totalPages - 1
            pages += SnapshotScriptResponse.Page(
                stagePage(listOf(stageWorkoutUpsert(id = recordId, revision = 7L)), baseline, token, hasMore),
            )
        }
        val api = SnapshotDeliveryScriptApi(database, mutableListOf(), pages)
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) {
            DELIVERY_NOW_MILLIS
        }
        store.withRun(DELIVERY_EPOCH) { run ->
            var progress = checkNotNull(loop.start(run, DELIVERY_ID_1, maxPages = 1).download) {
                "first budget must authorize download"
            }
            assertTrue(progress.stop is SnapshotDownloadStop.LimitReached)
            assertEquals(1, progress.committedPages)
            assertEquals(1, api.snapshotCalls.size)
            var totalCommitted = progress.committedPages
            var totalRecords = progress.committedRecords
            // SAME-run bounded continuation, one page per invocation.
            while (progress.stop is SnapshotDownloadStop.LimitReached) {
                progress = loop.continueDownload(run, progress, maxPages = 1)
                totalCommitted += progress.committedPages
                totalRecords += progress.committedRecords
                if (totalCommitted > totalPages) fail("must terminate at $totalPages pages")
            }
            assertTrue(progress.stop is SnapshotDownloadStop.Downloaded)
            assertEquals(totalPages, totalCommitted)
            assertEquals(totalPages, totalRecords)
            assertEquals(totalPages.toLong(), progress.stage.pageCount)
            assertEquals(totalPages.toLong(), progress.stage.recordCount)
        }
        assertEquals(totalPages, api.snapshotCalls.size)
        // First call null, then exact opaque chain, fixed default limit 200.
        assertEquals(null, api.snapshotCalls.first().token)
        assertEquals(deliveryToken(0), api.snapshotCalls[1].token)
        assertTrue(api.snapshotCalls.all { it.limit == 200 })
        assertEquals(totalPages, database.stageRecordRows(DELIVERY_ID_1).size)
        assertEquals("downloaded", database.stageRowsFor(CLAIM_ACCOUNT_A).single().phase)
    }

    @Test
    fun staleWrongDriverAndNonLimitReachedContinuations_rejectedBeforeHttp() = runTest {
        database.stageSeedBaseline()
        val pageOne = stagePage(listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, DELIVERY_TOKEN_1, true)
        val pageTwo = stagePage(
            listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), DELIVERY_BASELINE_1, null, false,
        )
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Page(pageOne), SnapshotScriptResponse.Page(pageTwo)),
        )
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        val foreignLoop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        var limited: com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadResult? = null
        store.withRun(DELIVERY_EPOCH) { run ->
            val started = loop.start(run, DELIVERY_ID_1, maxPages = 1)
            limited = checkNotNull(started.download) { "must limit" }
            assertTrue(checkNotNull(limited).stop is SnapshotDownloadStop.LimitReached)
            // Wrong-driver continuation rejected before HTTP.
            try {
                foreignLoop.continueDownload(run, checkNotNull(limited), maxPages = 1)
                fail("wrong-driver continuation must reject")
            } catch (_: IllegalArgumentException) {
            }
            // Non-LimitReached results rejected: complete the download first
            // in a fresh stage to mint Downloaded/Failed/RestartRequired shapes.
            try {
                loop.continueDownload(run, loop.continueDownload(run, checkNotNull(limited), maxPages = 5), maxPages = 1)
                fail("Downloaded continuation must reject")
            } catch (_: IllegalArgumentException) {
            }
        }
        val callsAfterLive = api.snapshotCalls.size
        // New admitted run can only read/abandon, never continue old results.
        store.withRun(DELIVERY_EPOCH) { run ->
            try {
                loop.continueDownload(run, checkNotNull(limited) { "must retain" }, maxPages = 1)
                fail("new-run continuation must reject")
            } catch (e: Exception) {
                assertTrue(e is IllegalArgumentException || e is IllegalStateException)
            }
            // Durable observation is never an append/continue grant.
            val observation = checkNotNull(run.readSnapshotStage()) { "must observe" }
            assertFalse(observation.canAppend)
        }
        assertEquals(callsAfterLive, api.snapshotCalls.size)
        // Failed and RestartRequired shapes never continue.
        database.claimExec("DELETE FROM sync_snapshot_stages")
        database.claimExec("DELETE FROM sync_snapshot_records")
        database.claimExec("DELETE FROM sync_snapshot_tokens")
        val failApi = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Failure(SyncApiException.Transient())),
        )
        val failStore = database.claimPushStore(claimSession(), claimOwner())
        val failLoop = SnapshotDeliveryLoop(failStore, failApi) { DELIVERY_NOW_MILLIS }
        failStore.withRun(DELIVERY_EPOCH) { run ->
            val started = failLoop.start(run, DELIVERY_ID_2)
            val failed = checkNotNull(started.download) { "must summarize failure" }
            assertTrue(failed.stop is SnapshotDownloadStop.Failed)
            try {
                failLoop.continueDownload(run, failed, maxPages = 1)
                fail("Failed continuation must reject")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(1, failApi.snapshotCalls.size)
    }

    @Test
    fun pushFirst_acceptedAckThenEmptyAuthorizesSnapshotOnSameRun() = runTest {
        database.pullSeedBaseline(cursor = STAGE_SYNC_CURSOR, lastSuccessMillis = STAGE_LAST_SUCCESS)
        val head = database.ackCaptureWorkoutHead(
            mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Delivery Workout",
        )
        val cacheAfterCapture = database.ackCacheRow(CLAIM_ACCOUNT_A)
        val headInner = JSONObject(head.payloadJson).getString("requestJson")
        val pushResult = ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L)))
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(SyncPushScript.Respond(pushResult)),
            mutableListOf(
                SnapshotScriptResponse.Page(stagePage(emptyList(), DELIVERY_BASELINE_1, null, false)),
            ),
        )
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        val started = store.withRun(DELIVERY_EPOCH) { run ->
            loop.start(run, DELIVERY_ID_1)
        }
        assertEquals(1, started.push.acknowledgedMutations)
        assertTrue(started.push.stop is PushDeliveryStop.Empty)
        assertEquals(1, api.pushCalls.size)
        assertEquals(headInner, api.pushCalls.single().single().requestJson)
        assertEquals(listOf(false), api.seenPushInTransaction)
        val download = checkNotNull(started.download) { "empty push must authorize snapshot" }
        assertTrue(download.stop is SnapshotDownloadStop.Downloaded)
        assertEquals(listOf(RecordedSnapshotCall(null, 200)), api.snapshotCalls)
        // Allowed push effects only: outbox drained, ack canonical present,
        // cache/dirty unchanged since capture, cursor/success untouched.
        assertEquals(0, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
        assertEquals(cacheAfterCapture, database.ackCacheRow(CLAIM_ACCOUNT_A))
        assertEquals(STAGE_SYNC_CURSOR, database.pullSyncCursor())
        assertEquals(STAGE_LAST_SUCCESS, database.pullLastSuccess())
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        assertEquals("Delivery Workout", database.workoutDao().getWorkout(CLAIM_WORKOUT_A)?.name)
        assertEquals(0, database.claimCount("SELECT COUNT(*) FROM sync_canonical_roots WHERE aggregate_id = '$STAGE_WORKOUT_1'"))
    }

    @Test
    fun pushFirst_representativeNonEmptyStopsNeverStartSnapshot() = runTest {
        val malformed = outcomeResult("other-mutation-id", MutationStatus.ACCEPTED, null, ackBatch(target = ackWorkoutChange()))
        val cases = listOf(
            "rejected" to SyncPushScript.Respond(outcomeRejectedResult(CLAIM_MUTATION_1, "gone")),
            "auth" to SyncPushScript.Fail(SyncApiException.AuthenticationRequired()),
            "malformed" to SyncPushScript.Respond(malformed),
            "oversize" to SyncPushScript.Fail(SyncApiException.RequestTooLarge()),
        )
        cases.forEach { (label, push) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.claimExec("DELETE FROM sync_snapshot_stages")
            database.claimExec("DELETE FROM sync_snapshot_records")
            database.claimExec("DELETE FROM sync_snapshot_tokens")
            database.claimExec("DELETE FROM workouts")
            database.pullSeedBaseline(cursor = STAGE_SYNC_CURSOR, lastSuccessMillis = STAGE_LAST_SUCCESS)
            database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Delivery Workout")
            val api = SnapshotDeliveryScriptApi(database, mutableListOf(push), mutableListOf())
            val store = database.claimPushStore(claimSession(), claimOwner())
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            val started = store.withRun(DELIVERY_EPOCH) { run ->
                loop.start(run, DELIVERY_ID_1)
            }
            assertEquals("$label push calls", 1, api.pushCalls.size)
            assertEquals("$label no snapshot transport", 0, api.snapshotCalls.size)
            assertNull("$label download must be null", started.download)
            assertFalse("$label push must not be Empty", started.push.stop is PushDeliveryStop.Empty)
            assertEquals("$label no stage", emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
            assertEquals("$label cursor", STAGE_SYNC_CURSOR, database.pullSyncCursor())
            assertEquals("$label success", STAGE_LAST_SUCCESS, database.pullLastSuccess())
        }
        // Budget after final ack never probes snapshot either.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.claimExec("DELETE FROM sync_snapshot_stages")
        database.claimExec("DELETE FROM sync_snapshot_records")
        database.claimExec("DELETE FROM sync_snapshot_tokens")
        database.claimExec("DELETE FROM workouts")
        database.pullSeedBaseline(cursor = STAGE_SYNC_CURSOR, lastSuccessMillis = STAGE_LAST_SUCCESS)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Delivery Workout")
        val limitedApi = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(),
        )
        val limitedStore = database.claimPushStore(claimSession(), claimOwner())
        val limitedLoop = SnapshotDeliveryLoop(limitedStore, limitedApi) { DELIVERY_NOW_MILLIS }
        val limited = limitedStore.withRun(DELIVERY_EPOCH) { run ->
            limitedLoop.start(run, DELIVERY_ID_1, maxPushMutations = 1)
        }
        assertEquals(1, limited.push.acknowledgedMutations)
        assertTrue(limited.push.stop is PushDeliveryStop.LimitReached)
        assertEquals(1, limitedApi.pushCalls.size)
        assertEquals(0, limitedApi.snapshotCalls.size)
        assertNull(limited.download)
    }

    @Test
    fun retainedStage_blocksStartWithoutSilentBypass() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
        }
        val live = database.claimSnapshot()
        val api = SnapshotDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        // Storage contract: a selected durable stage surfaces
        // PushHeadResult.Blocked(SNAPSHOT_STAGE, null) before head selection,
        // so the driver returns push-only Blocked with null download.
        val started = store.withRun(DELIVERY_EPOCH) { run ->
            loop.start(run, DELIVERY_ID_2)
        }
        val blocked = started.push.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.SNAPSHOT_STAGE, blocked.reason)
        assertNull(blocked.errorCode)
        assertEquals(0, started.push.acknowledgedMutations)
        assertNull(started.download)
        assertEquals(0, api.pushCalls.size)
        assertEquals(0, api.snapshotCalls.size)
        assertEquals(live, database.claimSnapshot())
        assertEquals(prior, database.stagePriorSnapshot())
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
        assertEquals(STAGE_ID_1, database.stageRowsFor(CLAIM_ACCOUNT_A).single().stageId)
    }

    @Test
    fun newIntentBetweenEmptyAndBegin_rejectsAndPreservesIntent() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryExecutor = Executors.newSingleThreadExecutor()
        val backingTx = Executors.newSingleThreadExecutor()
        val armed = AtomicBoolean(false)
        val first = CompletableDeferred<Runnable>()
        val second = CompletableDeferred<Runnable>()
        // Deterministic transaction-admission barrier: armed only inside the
        // live run (admission itself passes unhooked). The fenced empty
        // claimHead ALSO uses a Room transaction, so the first captured
        // runnable is the claim (released to observe Empty) and the second
        // is the begin admission (held). A separate query executor keeps
        // reads off the barrier.
        val hookDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(
                Executor { command ->
                    if (armed.get()) {
                        if (!first.isCompleted) {
                            first.complete(command)
                        } else if (!second.isCompleted) {
                            second.complete(command)
                        } else {
                            backingTx.execute(command)
                        }
                    } else {
                        backingTx.execute(command)
                    }
                },
            )
            .build()
        try {
            hookDb.stageSeedBaseline()
            val api = SnapshotDeliveryScriptApi(hookDb, mutableListOf(), mutableListOf())
            val store = hookDb.claimPushStore(claimSession(), claimOwner())
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            store.withRun(DELIVERY_EPOCH) { run ->
                armed.set(true)
                coroutineScope {
                    // Inherit the run context: start suspends at transaction
                    // admission; the claim admission is released first so the
                    // driver observes fenced Empty, then begin is held. The
                    // expected IntentException is caught/asserted INSIDE the
                    // starting child: a non-cancellation async failure would
                    // otherwise cancel this structured scope despite an
                    // outer await catch.
                    val starting = async(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            loop.start(run, DELIVERY_ID_1)
                            "unexpected-success"
                        } catch (e: SnapshotStageIntentException) {
                            assertEquals(STAGE_INTENT_MESSAGE, e.message)
                            assertNull(e.cause)
                            "intent-rejected"
                        }
                    }
                    val claimRunnable = first.await()
                    assertFalse(starting.isCompleted)
                    backingTx.execute(claimRunnable)
                    val beginRunnable = second.await()
                    assertFalse(starting.isCompleted)
                    // Inject an independently literal selected outbox row via
                    // direct SQL while begin is held: races begin's check.
                    hookDb.claimExec(
                        "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                            " mutation_type, local_generation, payload_version, payload_json, state," +
                            " attempt_count, retry_after_millis, error_code, error_message)" +
                            " VALUES (40, '$CLAIM_MUTATION_2', '$CLAIM_ACCOUNT_A', 'workout', '$CLAIM_WORKOUT_B'," +
                            " 'workout.upsert', 1, 1, '{broken', 'pending', 0, NULL, NULL, NULL)",
                    )
                    // Complete preservation oracle AFTER injection and BEFORE
                    // releasing begin: later equality must tolerate the
                    // deliberate row while proving nothing else moved.
                    val oracle = hookDb.stagePriorSnapshot()
                    val injectedBytes = checkNotNull(
                        hookDb.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A),
                    ) { "injected intent must be present before release" }.payloadJson
                    // Admit the held begin on the owned backing executor.
                    backingTx.execute(beginRunnable)
                    assertEquals("intent-rejected", starting.await())
                    assertEquals(oracle, hookDb.stagePriorSnapshot())
                    assertEquals(
                        injectedBytes,
                        hookDb.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A)?.payloadJson,
                    )
                }
                armed.set(false)
            }
            assertEquals(0, api.pushCalls.size)
            assertEquals(0, api.snapshotCalls.size)
            // Complete intent preservation, no stage.
            assertEquals(
                1,
                hookDb.claimCount("SELECT COUNT(*) FROM sync_outbox WHERE account_id = '$CLAIM_ACCOUNT_A'"),
            )
            checkNotNull(hookDb.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A)) {
                "injected intent must be preserved"
            }
            assertEquals(emptyList<Any>(), hookDb.stageRowsFor(CLAIM_ACCOUNT_A))
        } finally {
            hookDb.close()
            queryExecutor.shutdown()
            backingTx.shutdown()
        }
    }

    @Test
    fun captureAfterBegin_preservedThroughDownload() = runTest {
        database.stageSeedBaseline()
        var snapshotCalls = 0
        val terminal = stagePage(listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, null, false)
        val api = object : com.dragosfotache.gymtracka.data.sync.api.GymSyncApi {
            override suspend fun push(requests: List<com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel>): PushResultModel {
                check(!database.inTransaction()) { "push must run outside Room" }
                assertTrue(requests.isEmpty())
                return decodePushForTest()
            }

            override suspend fun pull(cursor: String?, limit: Int): com.dragosfotache.gymtracka.data.sync.model.PullResultModel {
                throw AssertionError("pull must not be called")
            }

            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                check(!database.inTransaction()) { "snapshot must run outside Room" }
                snapshotCalls += 1
                // Allowed newer capture AFTER begin: append must not demand a clean outbox.
                if (snapshotCalls == 1) {
                    database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
                    val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
                    database.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
                }
                return terminal
            }
        }
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        val started = store.withRun(DELIVERY_EPOCH) { run ->
            loop.start(run, DELIVERY_ID_1)
        }
        val download = checkNotNull(started.download) { "must download" }
        assertTrue(download.stop is SnapshotDownloadStop.Downloaded)
        assertEquals(1, snapshotCalls)
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
        assertEquals("downloaded", database.stageRowsFor(CLAIM_ACCOUNT_A).single().phase)
    }

    @Test
    fun invalidCompletePage_meansZeroPageCommitWithTypedMalformedFailure() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        // Empty nonterminal page is malformed: zero commit, typed failure.
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(
                SnapshotScriptResponse.Page(stagePage(emptyList(), DELIVERY_BASELINE_1, DELIVERY_TOKEN_1, true)),
            ),
        )
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        val started = store.withRun(DELIVERY_EPOCH) { run ->
            loop.start(run, DELIVERY_ID_1)
        }
        val download = checkNotNull(started.download) { "malformed must still summarize" }
        assertTrue(download.stop is SnapshotDownloadStop.Failed)
        val failed = download.stop as SnapshotDownloadStop.Failed
        assertTrue(failed.failure is SyncApiException.MalformedResponse)
        assertEquals(0, download.committedPages)
        assertEquals(0, download.committedRecords)
        assertEquals(1, api.snapshotCalls.size)
        // Begin happened, but zero page commit: header stands at zero.
        val header = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
        assertEquals(0L, header.pageCount)
        assertEquals(0L, header.recordCount)
        assertEquals(emptyList<Any>(), database.stageRecordRows(DELIVERY_ID_1))
        assertEquals(emptyList<Any>(), database.stageTokenRows(DELIVERY_ID_1))
        assertEquals(prior, database.stagePriorSnapshot())
    }

    @Test
    fun crossPageFailures_propagateUnchangedWithEarlierCommitsSurviving() = runTest {
        database.stageSeedBaseline()
        val validFirst = stagePage(listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, DELIVERY_TOKEN_1, true)
        val cases = listOf(
            "cross-page-duplicate-identical" to stagePage(
                listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, DELIVERY_TOKEN_2, true,
            ),
            "baseline-mismatch" to stagePage(
                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), DELIVERY_BASELINE_2, DELIVERY_TOKEN_2, true,
            ),
            "token-cycle" to stagePage(
                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), DELIVERY_BASELINE_1, DELIVERY_TOKEN_1, true,
            ),
        )
        cases.forEach { (label, badSecond) ->
            database.claimExec("DELETE FROM sync_snapshot_stages")
            database.claimExec("DELETE FROM sync_snapshot_records")
            database.claimExec("DELETE FROM sync_snapshot_tokens")
            val api = SnapshotDeliveryScriptApi(
                database,
                mutableListOf(),
                mutableListOf(SnapshotScriptResponse.Page(validFirst), SnapshotScriptResponse.Page(badSecond)),
            )
            val store = database.claimPushStore(claimSession(), claimOwner())
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            store.withRun(DELIVERY_EPOCH) { run ->
                try {
                    loop.start(run, DELIVERY_ID_1)
                    fail("$label must propagate storage validation")
                } catch (e: SyncModelException) {
                    e.message?.let { deliveryAssertRedacted(it, "$label message") }
                }
            }
            assertEquals("$label calls", 2, api.snapshotCalls.size)
            // Earlier committed page survives; failed page left nothing partial.
            assertEquals("$label records", 1, database.stageRecordRows(DELIVERY_ID_1).size)
            val header = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
            assertEquals("$label pages", 1L, header.pageCount)
            assertEquals("$label records", 1L, header.recordCount)
            assertEquals("$label token", DELIVERY_TOKEN_1, header.nextPageToken)
        }
    }

    @Test
    fun typedFailures_exactInstanceNoCleanupEarlierSurvives_authenticExpiryRestartsExplicitly() = runTest {
        // Seed the legitimate run first: device, cache, cursor and success.
        database.stageSeedBaseline()
        // Earlier commit survives every typed failure; authentic expiry maps
        // RestartRequired with retention, never silent abandon/begin.
        val validFirst = stagePage(listOf(stageWorkoutUpsert()), DELIVERY_BASELINE_1, DELIVERY_TOKEN_1, true)
        val failures: List<Pair<String, SyncApiException>> = listOf(
            "invalid-snapshot" to SyncApiException.InvalidSnapshotToken(),
            "wrong-context-cursor-expired" to SyncApiException.CursorExpired(),
            "wrong-context-invalid-cursor" to SyncApiException.InvalidCursor(),
            "wrong-context-rejected-token" to SyncApiException.RequestRejected("invalid_snapshot_token"),
            "auth" to SyncApiException.AuthenticationRequired(),
            "reauth" to SyncApiException.ReauthenticationRequired(),
            "transient" to SyncApiException.Transient(),
            "transient-hint" to SyncApiException.Transient(5_000L),
            "oversize" to SyncApiException.RequestTooLarge(),
            "malformed" to SyncApiException.MalformedResponse(),
            "unknown-rejected" to SyncApiException.RequestRejected("future_permanent_code"),
        )
        failures.forEach { (label, failure) ->
            database.claimExec("DELETE FROM sync_snapshot_stages")
            database.claimExec("DELETE FROM sync_snapshot_records")
            database.claimExec("DELETE FROM sync_snapshot_tokens")
            val api = SnapshotDeliveryScriptApi(
                database,
                mutableListOf(),
                mutableListOf(
                    SnapshotScriptResponse.Page(validFirst),
                    SnapshotScriptResponse.Failure(failure),
                ),
            )
            val store = database.claimPushStore(claimSession(), claimOwner())
            val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
            val started = store.withRun(DELIVERY_EPOCH) { run ->
                loop.start(run, DELIVERY_ID_1, maxPages = 5)
            }
            val download = checkNotNull(started.download) { "$label must summarize" }
            assertTrue("$label must be Failed, got ${download.stop}", download.stop is SnapshotDownloadStop.Failed)
            val failed = download.stop as SnapshotDownloadStop.Failed
            assertSame("$label identity", failure, failed.failure)
            assertEquals("$label commits", 1, download.committedPages)
            assertEquals("$label records", 1, download.committedRecords)
            assertEquals("$label calls", 2, api.snapshotCalls.size)
            // No cleanup/retry: earlier page retained, stage present.
            assertEquals("$label records", 1, database.stageRecordRows(DELIVERY_ID_1).size)
            assertEquals("$label pages", 1L, database.stageRowsFor(CLAIM_ACCOUNT_A).single().pageCount)
        }
        // Authentic expiry: RestartRequired, retains stage/pages for explicit policy.
        database.claimExec("DELETE FROM sync_snapshot_stages")
        database.claimExec("DELETE FROM sync_snapshot_records")
        database.claimExec("DELETE FROM sync_snapshot_tokens")
        val expiry = SyncApiException.SnapshotExpired()
        val expiryApi = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(
                SnapshotScriptResponse.Page(validFirst),
                SnapshotScriptResponse.Failure(expiry),
            ),
        )
        val expiryStore = database.claimPushStore(claimSession(), claimOwner())
        val expiryLoop = SnapshotDeliveryLoop(expiryStore, expiryApi) { DELIVERY_NOW_MILLIS }
        val expired = expiryStore.withRun(DELIVERY_EPOCH) { run ->
            expiryLoop.start(run, DELIVERY_ID_1, maxPages = 5)
        }
        val restart = checkNotNull(expired.download) { "expiry must summarize" }
        assertTrue(restart.stop is SnapshotDownloadStop.RestartRequired)
        assertEquals(1, restart.committedPages)
        assertEquals(1, restart.committedRecords)
        assertEquals(2, expiryApi.snapshotCalls.size)
        // NEVER silently abandons or begins: stage/pages retained.
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
        assertEquals(1, database.stageRecordRows(DELIVERY_ID_1).size)
        // Explicit valid abandon + NEW UUID/null-token restart required.
        expiryStore.withRun(DELIVERY_EPOCH) { run ->
            val observation = checkNotNull(run.readSnapshotStage()) { "must reread" }
            assertFalse(observation.canAppend)
            run.abandonSnapshotStage(observation)
        }
        assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
        val restartApi = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(
                SnapshotScriptResponse.Page(stagePage(emptyList(), DELIVERY_BASELINE_1, null, false)),
            ),
        )
        val restartStore = database.claimPushStore(claimSession(), claimOwner())
        val restartLoop = SnapshotDeliveryLoop(restartStore, restartApi) { DELIVERY_NOW_MILLIS }
        val restarted = restartStore.withRun(DELIVERY_EPOCH) { run ->
            restartLoop.start(run, DELIVERY_ID_2)
        }
        assertTrue(checkNotNull(restarted.download).stop is SnapshotDownloadStop.Downloaded)
        assertEquals(listOf(RecordedSnapshotCall(null, 200)), restartApi.snapshotCalls)
    }

    @Test
    fun unexpectedTransportError_propagatesUnchangedWithBeginRetained() = runTest {
        database.stageSeedBaseline()
        val boom = IllegalStateException("boom-snapshot-transport")
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Unexpected(boom)),
        )
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        store.withRun(DELIVERY_EPOCH) { run ->
            try {
                loop.start(run, DELIVERY_ID_1)
                fail("unexpected error must propagate")
            } catch (error: IllegalStateException) {
                assertSame(boom, error)
            }
        }
        assertEquals(1, api.snapshotCalls.size)
        // Begin happened before the faulty fetch; nothing committed.
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
        assertEquals(0, database.stageRecordRows(DELIVERY_ID_1).size)
    }

    @Test
    fun productionHttpAdapter_composesSnapshotToRoomStagingWithExactQuery() = runTest {
        // CLAIM_ACCOUNT_A is Room-only; HTTP pairing uses the SAME valid
        // wire UUID for binding, device, cache, session and inspection.
        database.stageSeedBaseline(account = DELIVERY_WIRE_ACCOUNT)
        val prior = database.stagePriorSnapshot()
        // Intercepted production adapter: no live backend, factory captures all.
        val factory = InterceptedSnapshotFactory(
            bodies = mutableListOf(
                """{"records":[],"baselineCursor":"adapter baseline","nextPageToken":null,"hasMore":false}""",
            ),
        )
        val binding = com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding(
            com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin.parse("https://example.com"),
            DELIVERY_WIRE_ACCOUNT,
            CLAIM_GENERATION,
        )
        val provider = com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessTokenProvider { target ->
            check(target == binding) { "provider must receive the bound session" }
            com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessToken(
                binding,
                com.dragosfotache.gymtracka.data.sync.api.OpaqueToken("test-only"),
            )
        }
        val transport = com.dragosfotache.gymtracka.data.sync.http.OkHttpServiceTransport(binding.origin, factory)
        val api = com.dragosfotache.gymtracka.data.sync.http.HttpGymSyncApi(binding, provider, transport)
        val store = database.claimPushStore(claimSession(DELIVERY_WIRE_ACCOUNT), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        val started = store.withRun(DELIVERY_EPOCH) { run ->
            loop.start(run, DELIVERY_ID_1, pageLimit = DELIVERY_LIMIT)
        }
        val download = checkNotNull(started.download) { "adapter page must download" }
        assertTrue(download.stop is SnapshotDownloadStop.Downloaded)
        assertEquals(1, download.committedPages)
        // Exact fixed query and terminal state are independent oracles.
        assertEquals(1, factory.captured.size)
        val req = factory.captured.single()
        assertEquals("/v1/sync/snapshot", req.url.encodedPath)
        assertEquals(null, req.url.queryParameter("pageToken"))
        assertEquals(DELIVERY_LIMIT.toString(), req.url.queryParameter("limit"))
        val header = database.stageRowsFor(DELIVERY_WIRE_ACCOUNT).single()
        assertEquals("adapter baseline", header.baselineCursor)
        assertEquals("downloaded", header.phase)
        assertEquals(prior, database.stagePriorSnapshot())
        assertEquals("SnapshotDeliveryLoop", loop.toString())
        assertEquals("SnapshotStartResult", started.toString())
        assertEquals("SnapshotDownloadResult", download.toString())
        assertEquals("Downloaded", download.stop.toString())
        deliveryAssertRedacted(loop.toString(), "loop display")
        deliveryAssertRedacted(started.toString(), "start display")
        deliveryAssertRedacted(download.toString(), "download display")
        deliveryAssertRedacted(download.stop.toString(), "stop display")
    }

    @Test
    fun redactedDisplays_useFixedSafeTypeNames() = runTest {
        database.stageSeedBaseline()
        val failure = SyncApiException.RequestRejected("future_permanent_code")
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Failure(failure)),
        )
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        val started = store.withRun(DELIVERY_EPOCH) { run ->
            loop.start(run, DELIVERY_ID_1)
        }
        val download = checkNotNull(started.download) { "must summarize" }
        val failed = download.stop as SnapshotDownloadStop.Failed
        assertEquals("SnapshotDeliveryLoop", loop.toString())
        assertEquals("SnapshotStartResult", started.toString())
        assertEquals("SnapshotDownloadResult", download.toString())
        assertEquals("Failed", failed.toString())
        deliveryAssertRedacted(loop.toString(), "loop display")
        deliveryAssertRedacted(started.toString(), "start display")
        deliveryAssertRedacted(download.toString(), "download display")
        deliveryAssertRedacted(failed.toString(), "failed display")
        assertSame(failure, failed.failure)
    }

    // ---- Harness helpers (test-only, no production logic) ----

    private suspend fun loopHarness(
        api: SnapshotDeliveryScriptApi,
        stageId: String,
        maxPages: Int = MAX_SNAPSHOT_DELIVERY_PAGES,
        pageLimit: Int = 200,
    ): com.dragosfotache.gymtracka.data.sync.engine.SnapshotStartResult {
        val store = database.claimPushStore(claimSession(), claimOwner())
        val loop = SnapshotDeliveryLoop(store, api) { DELIVERY_NOW_MILLIS }
        return store.withRun(DELIVERY_EPOCH) { run ->
            loop.start(run, stageId, maxPages = maxPages, pageLimit = pageLimit)
        }
    }

    private fun decodePushForTest(): PushResultModel {
        return decodePushResult("{\"results\":[]}")
    }

    private class InterceptedSnapshotFactory(
        val bodies: MutableList<String>,
    ) : okhttp3.Call.Factory {
        val captured = mutableListOf<okhttp3.Request>()

        override fun newCall(request: okhttp3.Request): okhttp3.Call {
            captured.add(request)
            return InterceptedSnapshotCall(request, bodies.removeFirst())
        }
    }

    private class InterceptedSnapshotCall(
        private val req: okhttp3.Request,
        private val bodyText: String,
    ) : okhttp3.Call {
        private var executed = false
        private var canceled = false

        override fun request(): okhttp3.Request = req

        override fun execute(): okhttp3.Response = build()

        override fun enqueue(responseCallback: okhttp3.Callback) {
            executed = true
            if (canceled) {
                responseCallback.onFailure(this, java.io.IOException("canceled"))
                return
            }
            responseCallback.onResponse(this, build())
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): okio.Timeout = okio.Timeout()

        override fun addEventListener(eventListener: okhttp3.EventListener) {
        }

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun clone(): okhttp3.Call = InterceptedSnapshotCall(req, bodyText)

        private fun build(): okhttp3.Response {
            val media = "application/json".toMediaType()
            return okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .header("Content-Type", "application/json")
                .body(bodyText.toResponseBody(media))
                .build()
        }
    }
}
