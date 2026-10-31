package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.MAX_PULL_DELIVERY_PAGES
import com.dragosfotache.gymtracka.data.sync.engine.MAX_PUSH_DELIVERY_MUTATIONS
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PullRecoveryReason
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
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
 * Real Room and scripted transport checks for uninterrupted push-before-pull composition and
 * valid-tail bookkeeping.
 */
class SyncDeliveryTest : GymDatabaseTest() {

    @Test
    fun acceptedPushThenEmptyTail_recordsLastSuccessWithoutProjection() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        val head = database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        // Capture/ack legitimately advance watermarks and dirty state: the
        // no-publication baseline is taken AFTER capture, not before it.
        val cacheAfterCapture = database.ackCacheRow(PULL_ACCOUNT_A)
        val headInner = JSONObject(head.payloadJson).getString("requestJson")
        val pushResult = ackAcceptedResult(
            CLAIM_MUTATION_1,
            ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L)),
        )
        val api = SyncDeliveryScriptApi(
            database,
            mutableListOf(SyncPushScript.Respond(pushResult)),
            mutableListOf(
                PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
            ),
        )
        val owner = claimOwner()
        val result = SyncDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH)

        // Push drained exactly one head with exact frozen bytes, outside Room.
        assertEquals(1, result.push.acknowledgedMutations)
        assertTrue(result.push.stop is PushDeliveryStop.Empty)
        assertEquals(1, api.pushCalls.size)
        assertEquals(headInner, api.pushCalls.single().single().requestJson)
        assertEquals(listOf(false), api.seenPushInTransaction)
        // Pull ran once from the exact push-empty boundary.
        val pull = checkNotNull(result.pull) { "empty push must authorize pull" }
        assertEquals(0, pull.committedBatches)
        assertTrue(pull.stop is PullDeliveryStop.Tail)
        assertEquals(listOf(RecordedPullCall(PULL_CURSOR_1, 100)), api.pullCalls)
        assertEquals(listOf(false), api.seenPullInTransaction)
        // Valid empty tail records last-success on the same boundary.
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
        // No outstanding local work after the single accepted head.
        assertEquals(false, result.hasOutstandingLocalWork)
        assertEquals(0, database.claimOutboxFor(PULL_ACCOUNT_A).size)
        // No projection publication: epochs/materialized/dirty unchanged since capture, domain intact.
        assertEquals(cacheAfterCapture, database.ackCacheRow(PULL_ACCOUNT_A))
        assertEquals("Sync Workout", database.workoutDao().getWorkout(CLAIM_WORKOUT_A)?.name)
    }

    @Test
    fun acceptedPushThenNonemptyTail_commitsBatchAtExactTerminalBoundary() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val pushResult = ackAcceptedResult(
            CLAIM_MUTATION_1,
            ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L)),
        )
        val page = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(
                        pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
                    ),
                    batchId = SYNC_BATCH_S1, cursor = SYNC_CURSOR_TAIL,
                ),
            ),
            nextCursor = SYNC_CURSOR_TAIL, hasMore = false,
        )
        val api = SyncDeliveryScriptApi(
            database,
            mutableListOf(SyncPushScript.Respond(pushResult)),
            mutableListOf(PullScriptResponse.Page(page)),
        )
        val result = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), api) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH)

        assertEquals(1, result.push.acknowledgedMutations)
        val pull = checkNotNull(result.pull)
        assertEquals(1, pull.committedBatches)
        assertTrue(pull.stop is PullDeliveryStop.Tail)
        // Push cursors never skip unseen pull boundaries: durable cursor is the
        // exact terminal page boundary, not the push acknowledgment.
        assertEquals(SYNC_CURSOR_TAIL, database.pullSyncCursor())
        assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
        assertEquals(false, result.hasOutstandingLocalWork)
        assertEquals(7L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
        // Accepted push canonical evidence coexists with pulled canonical evidence.
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
    }

    @Test
    fun representativePushStops_neverPullOrRecordTail() = runTest {
        data class Case(val label: String, val push: SyncPushScript, val limit: Int = 100)
        val malformed = outcomeResult("other-mutation-id", MutationStatus.ACCEPTED, null, ackBatch(target = ackWorkoutChange()))
        val cases = listOf(
            Case("rejected", SyncPushScript.Respond(outcomeRejectedResult(CLAIM_MUTATION_1, "gone"))),
            Case("auth", SyncPushScript.Fail(SyncApiException.AuthenticationRequired())),
            Case("malformed", SyncPushScript.Respond(malformed)),
            Case("oversize", SyncPushScript.Fail(SyncApiException.RequestTooLarge())),
        )
        cases.forEach { (label, push) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
            database.claimExec("DELETE FROM workouts")
            database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
            val api = SyncDeliveryScriptApi(database, mutableListOf(push), mutableListOf())
            val result = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), api) { SYNC_NOW_MILLIS }
                .deliver(SYNC_EPOCH)
            assertEquals("$label push calls", 1, api.pushCalls.size)
            assertEquals("$label no pull transport", 0, api.pullCalls.size)
            assertNull("$label pull summary must be null", result.pull)
            assertNull("$label flag must be null (not false)", result.hasOutstandingLocalWork)
            assertNull("$label never records last-success", database.pullLastSuccess())
            assertEquals("$label cursor unchanged", PULL_CURSOR_1, database.pullSyncCursor())
        }
        // Budget limit after a final acknowledgment still cannot probe pull.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimExec("DELETE FROM workouts")
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val limitedApi = SyncDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(),
        )
        val limited = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), limitedApi) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH, maxPushMutations = 1)
        // Exactly one head acknowledged at the budget; LimitReached never probes another head or pull.
        assertEquals(1, limited.push.acknowledgedMutations)
        assertTrue(limited.push.stop is PushDeliveryStop.LimitReached)
        assertEquals(1, limitedApi.pushCalls.size)
        assertEquals(0, limitedApi.pullCalls.size)
        assertNull(limited.pull)
        assertNull(limited.hasOutstandingLocalWork)
        assertNull(database.pullLastSuccess())
    }

    @Test
    fun pullFailureOrRecovery_neverRecordsLastSuccess_earlierAckSurvives() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val transient = SyncApiException.Transient(5_000L)
        val api = SyncDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(PullScriptResponse.Failure(transient)),
        )
        val result = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), api) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH)
        assertEquals(1, result.push.acknowledgedMutations)
        val pull = checkNotNull(result.pull) { "pull attempt must be summarized" }
        assertEquals(0, pull.committedBatches)
        assertTrue(pull.stop is PullDeliveryStop.Failed)
        assertNull(result.hasOutstandingLocalWork)
        assertNull(database.pullLastSuccess())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        // Earlier returned acknowledgment remains durable after the later pull failure.
        assertNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
    }

    @Test
    fun pullBudgetRecoveryMalformedInvalidToken_neverRecordTail_earlierWorkSurvives() = runTest {
        // Budget boundary with a committed batch: the durable boundary advances
        // but no valid tail is reached, so no last-success is recorded.
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val limitApi = SyncDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(
                PullScriptResponse.Page(
                    pullDeliveryPage(
                        batches = listOf(
                            pullBatch(
                                changes = listOf(
                                    pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
                                ),
                                batchId = SYNC_BATCH_S1, cursor = SYNC_CURSOR_MID,
                            ),
                        ),
                        nextCursor = SYNC_CURSOR_MID, hasMore = true,
                    ),
                ),
            ),
        )
        val limited = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), limitApi) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH, maxPullPages = 1)
        val limitedPull = checkNotNull(limited.pull) { "budget pull must be summarized" }
        assertEquals(1, limitedPull.committedBatches)
        assertTrue(limitedPull.stop is PullDeliveryStop.LimitReached)
        assertNull(limited.hasOutstandingLocalWork)
        assertNull(database.pullLastSuccess())
        assertEquals(SYNC_CURSOR_MID, database.pullSyncCursor())
        // Earlier push ack plus the committed batch both survive the budget stop.
        assertNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        assertEquals(7L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)

        // Explicit expiry: recovery without writes; the push ack survives.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimExec("DELETE FROM workouts")
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val expiredApi = SyncDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(PullScriptResponse.Failure(SyncApiException.CursorExpired())),
        )
        val expired = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), expiredApi) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH)
        val expiredPull = checkNotNull(expired.pull)
        assertEquals(0, expiredPull.committedBatches)
        val expiredStop = expiredPull.stop as PullDeliveryStop.RecoveryRequired
        assertEquals(PullRecoveryReason.CURSOR_EXPIRED, expiredStop.reason)
        assertNull(expired.hasOutstandingLocalWork)
        assertNull(database.pullLastSuccess())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        assertNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))

        // Invalid cursor: Failed carrying the exact failure; no writes, no tail time.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimExec("DELETE FROM workouts")
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val invalidCursor = SyncApiException.InvalidCursor()
        val invalidApi = SyncDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(PullScriptResponse.Failure(invalidCursor)),
        )
        val invalid = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), invalidApi) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH)
        val invalidPull = checkNotNull(invalid.pull)
        assertEquals(0, invalidPull.committedBatches)
        val invalidStop = invalidPull.stop as PullDeliveryStop.Failed
        assertSame(invalidCursor, invalidStop.failure)
        assertNull(invalid.hasOutstandingLocalWork)
        assertNull(database.pullLastSuccess())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())

        // Malformed terminal page: Failed with MalformedResponse before any commit.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.claimExec("DELETE FROM workouts")
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val malformedApi = SyncDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(
                PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, true)),
            ),
        )
        val malformed = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), malformedApi) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH)
        val malformedPull = checkNotNull(malformed.pull)
        assertEquals(0, malformedPull.committedBatches)
        val malformedStop = malformedPull.stop as PullDeliveryStop.Failed
        assertTrue(malformedStop.failure is SyncApiException.MalformedResponse)
        assertNull(malformed.hasOutstandingLocalWork)
        assertNull(database.pullLastSuccess())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        assertNull(database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))

        // Missing baseline and null cursor: recovery without HTTP or snapshot.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.claimSeedDevice()
        database.syncDao().upsertSyncState(
            com.dragosfotache.gymtracka.data.gym.SyncStateEntity(PULL_ACCOUNT_A, PULL_CURSOR_1, null),
        )
        val absentApi = SyncDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        val absent = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), absentApi) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH)
        assertTrue(absent.push.stop is PushDeliveryStop.Empty)
        val absentPull = checkNotNull(absent.pull)
        assertEquals(
            PullRecoveryReason.MISSING_BASELINE_OR_CURSOR,
            (absentPull.stop as PullDeliveryStop.RecoveryRequired).reason,
        )
        assertNull(absent.hasOutstandingLocalWork)
        assertNull(database.pullLastSuccess())
        assertEquals(0, absentApi.pullCalls.size)

        database.pullSeedBaseline(cursor = null, lastSuccessMillis = null)
        val nullCursorApi = SyncDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        val nullCursor = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), nullCursorApi) { SYNC_NOW_MILLIS }
            .deliver(SYNC_EPOCH)
        val nullCursorPull = checkNotNull(nullCursor.pull)
        assertEquals(
            PullRecoveryReason.MISSING_BASELINE_OR_CURSOR,
            (nullCursorPull.stop as PullDeliveryStop.RecoveryRequired).reason,
        )
        assertNull(nullCursor.hasOutstandingLocalWork)
        assertNull(database.pullLastSuccess())
        assertEquals(0, nullCursorApi.pullCalls.size)
    }

    @Test
    fun strictInputBounds_rejectBeforeAnySideEffect() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val before = database.claimSnapshot()
        val api = SyncDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        val loop = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), api) { SYNC_NOW_MILLIS }
        assertEquals(100, MAX_PUSH_DELIVERY_MUTATIONS)
        assertEquals(100, MAX_PULL_DELIVERY_PAGES)
        try {
            loop.deliver(-1L)
            fail("negative epoch must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        listOf(0, -1, MAX_PUSH_DELIVERY_MUTATIONS + 1, 101).forEach { bad ->
            try {
                loop.deliver(SYNC_EPOCH, maxPushMutations = bad)
                fail("maxPushMutations=$bad must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        listOf(0, -1, MAX_PULL_DELIVERY_PAGES + 1, 101).forEach { bad ->
            try {
                loop.deliver(SYNC_EPOCH, maxPullPages = bad)
                fail("maxPullPages=$bad must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        listOf(0, -1, 201, 1000).forEach { bad ->
            try {
                loop.deliver(SYNC_EPOCH, pullPageLimit = bad)
                fail("pullPageLimit=$bad must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(0, api.pushCalls.size)
        assertEquals(0, api.pullCalls.size)
        assertEquals(before, database.claimSnapshot())
        assertNull(database.pullLastSuccess())
    }

    @Test
    fun fixedSafeDisplays_omitSecrets_standaloneLoopsUnchanged() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val api = SyncDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(
                PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
            ),
        )
        val loop = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), api) { SYNC_NOW_MILLIS }
        assertEquals("SyncDeliveryLoop", loop.toString())
        val result = loop.deliver(SYNC_EPOCH)
        syncAssertRedacted(result.toString(), "sync-result")
        syncAssertRedacted(result.push.toString(), "push-result")
        syncAssertRedacted(checkNotNull(result.pull).toString(), "pull-result")
        syncAssertRedacted(checkNotNull(result.pull).stop.toString(), "pull-stop")
        // Standalone public drivers keep their signatures/behavior: epoch push
        // still drains, and standalone pull still returns Tail without tail time.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = 111L)
        database.claimExec("DELETE FROM workouts")
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val standalonePushApi = SyncDeliveryScriptApi(
            database,
            mutableListOf(
                SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
            ),
            mutableListOf(),
        )
        val standalonePush = PushDeliveryLoop(
            database.claimPushStore(claimSession(), claimOwner()),
            standalonePushApi,
        ) { SYNC_NOW_MILLIS }.deliver(SYNC_EPOCH, 100)
        assertEquals(1, standalonePush.acknowledgedMutations)
        assertTrue(standalonePush.stop is PushDeliveryStop.Empty)
        assertEquals(111L, database.pullLastSuccess())
        // Standalone pull on the admitted run returns Tail without tail time;
        // the admitted push overload composes before and after checkpoint on
        // the same run without nested admission.
        val standalonePullApi = SyncDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(
                PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
            ),
        )
        val admittedOnlyApi = SyncDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        val admittedStore = database.claimPushStore(claimSession(), claimOwner())
        admittedStore.withRun(SYNC_EPOCH) { run ->
            val admittedBefore = PushDeliveryLoop(admittedStore, admittedOnlyApi) { SYNC_NOW_MILLIS }
                .deliver(run, 100)
            assertEquals(0, admittedBefore.acknowledgedMutations)
            assertTrue(admittedBefore.stop is PushDeliveryStop.Empty)
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "standalone run must read" }
            val admittedAfter = PushDeliveryLoop(admittedStore, admittedOnlyApi) { SYNC_NOW_MILLIS }
                .deliver(run, 100)
            assertTrue(admittedAfter.stop is PushDeliveryStop.Empty)
            run.checkPullCheckpoint(checkpoint)
            val standalonePull = PullDeliveryLoop(standalonePullApi).pull(run)
            assertEquals(0, standalonePull.committedBatches)
            assertTrue(standalonePull.stop is PullDeliveryStop.Tail)
        }
        assertEquals(0, admittedOnlyApi.pushCalls.size)
        assertEquals(111L, database.pullLastSuccess())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
    }

}
