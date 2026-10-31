package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.api.DEFAULT_PULL_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.engine.MAX_PULL_DELIVERY_PAGES
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PullRecoveryReason
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.store.CanonicalConflictException
import com.dragosfotache.gymtracka.data.sync.store.PullStorageException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real Room and scripted transport checks for page order/chaining, tails, budgets, bounds,
 * malformed-page rejection, failure classification, earlier-commit survival and redaction.
 */
class PullDeliveryTest : GymDatabaseTest() {

    @Test
    fun multiPageOrderedReplacementAndLateDependency_preservesUntouchedScopes() = runTest {
        // Whitespace initial cursor must be preserved exactly end to end.
        database.pullSeedBaseline(cursor = PULL_WHITESPACE_CURSOR)
        database.claimSeedWorkout(PULL_FOREIGN_WORKOUT, PULL_ACCOUNT_B, "Foreign")
        database.canonicalSeedCanonicalRow(
            PULL_ACCOUNT_B, "workout", PULL_FOREIGN_WORKOUT, 2L,
            "upsert", "{\"revision\":2}", 0L, 7L,
        )
        database.claimSeedCache(
            PULL_ACCOUNT_B, 7L, baselineComplete = true,
            projectionDirty = true, materializedEpoch = 2L,
        )
        // Old active root: canonical established, domain newer intent pending.
        database.claimSeedWorkout(PULL_WORKOUT_B, PULL_ACCOUNT_A, "Local Newer", generation = 4L)
        database.canonicalSeedCanonicalRow(
            PULL_ACCOUNT_A, "workout", PULL_WORKOUT_B, 4L,
            "upsert", canonicalWorkoutRecord(id = PULL_WORKOUT_B, name = "Local Newer", revision = 4L).toString(), 0L, PULL_EPOCH,
        )
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        database.claimCaptureStart(captureStore, PULL_ACCOUNT_A, PULL_WORKOUT_B, "Local Newer")
        database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_B, "foreign-cursor", 777L))
        val outboxBefore = database.claimOutboxFor(PULL_ACCOUNT_A).single()
        val domainBefore = database.workoutDao().getWorkout(PULL_WORKOUT_B)!!

        // ONE complete replacement batch: retires the old active root and
        // observes the new active root whose custom-exercise reference has
        // no canonical row yet. Hand-authored wire decoded with the existing
        // codec, never an encoder; literals authored here.
        val replacementChanges = JSONArray()
            .put(pullWireChange("workout", PULL_WORKOUT_B, "delete", 5L, null))
            .put(
                pullWireChange(
                    "workout", PULL_WORKOUT_A, "upsert", 7L,
                    pullWireWorkoutRecord(
                        revision = 7L,
                        exercises = JSONArray().put(pullWireExerciseEntry()),
                    ),
                ),
            )
        val replacementBatch = decodePullResult(
            pullWireBatch(
                batchId = PULL_BATCH_1,
                cursor = PULL_OPAQUE_CURSOR,
                changes = replacementChanges,
            ),
        ).batches.single()
        val pageOne = pullDeliveryPage(
            batches = listOf(replacementBatch),
            nextCursor = PULL_OPAQUE_CURSOR, hasMore = true,
        )
        // The referenced custom arrives on the next page: a later dependency.
        val pageTwo = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(
                        pullUpsert(AggregateType.CUSTOM_EXERCISE, PULL_CUSTOM_A, 5L, pullCustomRecord()),
                    ),
                    batchId = PULL_BATCH_2, cursor = PULL_CURSOR_2,
                ),
            ),
            nextCursor = PULL_CURSOR_2, hasMore = false,
        )
        val api = PullDeliveryScriptApi(
            database,
            mutableListOf(PullScriptResponse.Page(pageOne), PullScriptResponse.Page(pageTwo)),
        )
        val result = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(api).pull(run)
        }
        assertEquals(2, result.committedBatches)
        assertTrue(result.stop is PullDeliveryStop.Tail)
        // Exact opaque chaining: whitespace input, then the opaque token with
        // embedded whitespace, each with the exact requested limit.
        assertEquals(
            listOf(
                RecordedPullCall(PULL_WHITESPACE_CURSOR, 100),
                RecordedPullCall(PULL_OPAQUE_CURSOR, 100),
            ),
            api.calls,
        )
        assertEquals(listOf(false, false), api.seenInTransaction)
        // Complete replacement settled atomically from the single batch.
        val tomb = database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_B)!!
        assertEquals("delete", tomb.canonicalState)
        assertNull(tomb.canonicalJson)
        assertEquals(7L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
        // Later dependency resolves without early publication.
        assertEquals(5L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "customExercise", PULL_CUSTOM_A)?.serverRevision)
        assertNull(database.workoutDao().getWorkout(PULL_WORKOUT_A))
        // Only selected-account cursor/canonical move; everything else stands.
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
        assertEquals("foreign-cursor", database.pullSyncCursor(PULL_ACCOUNT_B))
        assertEquals(outboxBefore, database.claimOutboxFor(PULL_ACCOUNT_A).single())
        assertEquals(domainBefore, database.workoutDao().getWorkout(PULL_WORKOUT_B))
        assertEquals(0L, database.ackWatermark(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
        assertEquals(0L, database.ackWatermark(PULL_ACCOUNT_A, "customExercise", PULL_CUSTOM_A))
        assertEquals("Foreign", database.workoutDao().getWorkout(PULL_FOREIGN_WORKOUT)?.name)
        assertEquals("1|7|1|2", database.ackCacheRow(PULL_ACCOUNT_B))
    }

    @Test
    fun emptyTerminalPage_returnsTailWithoutWrites() = runTest {
        database.pullSeedBaseline()
        val before = database.claimSnapshot()
        val api = PullDeliveryScriptApi(
            database,
            mutableListOf(
                PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
            ),
        )
        val result = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(api).pull(run)
        }
        assertEquals(0, result.committedBatches)
        assertTrue(result.stop is PullDeliveryStop.Tail)
        assertEquals(listOf(RecordedPullCall(PULL_CURSOR_1, 100)), api.calls)
        assertEquals(before, database.claimSnapshot())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
    }

    @Test
    fun budgetExhaustion_returnsLimitWithoutExtraProbe_thenResumes() = runTest {
        database.pullSeedBaseline()
        val pageOne = pullDeliveryPage(
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
        val api = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Page(pageOne)))
        val owner = claimOwner()
        val limited = database.claimPushStore(claimSession(), owner).withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(api).pull(run, maxPages = 1)
        }
        assertEquals(1, limited.committedBatches)
        assertTrue(limited.stop is PullDeliveryStop.LimitReached)
        assertEquals(1, api.calls.size)
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        // Resume fetches from the durable chain boundary with no skipped tail.
        val pageTwo = pullDeliveryPage(
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
        val resumeApi = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Page(pageTwo)))
        val resumed = database.claimPushStore(claimSession(), owner).withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(resumeApi).pull(run, maxPages = 1)
        }
        assertEquals(1, resumed.committedBatches)
        assertTrue(resumed.stop is PullDeliveryStop.Tail)
        assertEquals(listOf(RecordedPullCall(PULL_CURSOR_2, 100)), resumeApi.calls)
        assertEquals(PULL_CURSOR_3, database.pullSyncCursor())
    }

    @Test
    fun strictBounds_rejectBeforeCheckpointOrTransport() = runTest {
        database.pullSeedBaseline()
        val before = database.claimSnapshot()
        val api = PullDeliveryScriptApi(database, mutableListOf())
        assertEquals(100, MAX_PULL_DELIVERY_PAGES)
        assertEquals(100, DEFAULT_PULL_LIMIT)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val loop = PullDeliveryLoop(api)
            listOf(0, -1, MAX_PULL_DELIVERY_PAGES + 1, 101).forEach { bad ->
                try {
                    loop.pull(run, maxPages = bad)
                    fail("maxPages=$bad must be rejected")
                } catch (_: IllegalArgumentException) {
                }
            }
            listOf(0, -1, 201, 1000).forEach { bad ->
                try {
                    loop.pull(run, pageLimit = bad)
                    fail("pageLimit=$bad must be rejected")
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        assertEquals(0, api.calls.size)
        assertEquals(before, database.claimSnapshot())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        // Defaults use the normative page limit with no clamping.
        val tailApi = PullDeliveryScriptApi(
            database,
            mutableListOf(PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false))),
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(tailApi).pull(run)
        }
        assertEquals(listOf(RecordedPullCall(PULL_CURSOR_1, DEFAULT_PULL_LIMIT)), tailApi.calls)
    }

    @Test
    fun missingBaselineOrCursor_returnsRecoveryWithoutTransport() = runTest {
        // Absent cache row.
        database.claimSeedDevice()
        database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, PULL_CURSOR_1, PULL_LAST_SUCCESS))
        val absentCacheApi = PullDeliveryScriptApi(database, mutableListOf())
        val absentCache = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(absentCacheApi).pull(run)
        }
        assertEquals(0, absentCache.committedBatches)
        assertTrue(absentCache.stop is PullDeliveryStop.RecoveryRequired)
        assertEquals(
            PullRecoveryReason.MISSING_BASELINE_OR_CURSOR,
            (absentCache.stop as PullDeliveryStop.RecoveryRequired).reason,
        )
        assertEquals(0, absentCacheApi.calls.size)
        // Incomplete baseline flag.
        database.pullSeedBaseline(baselineComplete = false)
        val incompleteApi = PullDeliveryScriptApi(database, mutableListOf())
        val incomplete = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(incompleteApi).pull(run)
        }
        assertEquals(PullRecoveryReason.MISSING_BASELINE_OR_CURSOR, (incomplete.stop as PullDeliveryStop.RecoveryRequired).reason)
        assertEquals(0, incompleteApi.calls.size)
        // Absent sync-state row.
        database.claimSeedDevice()
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimSeedCache(PULL_ACCOUNT_A, PULL_EPOCH)
        database.claimExec("DELETE FROM sync_state")
        val absentRowApi = PullDeliveryScriptApi(database, mutableListOf())
        val absentRow = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(absentRowApi).pull(run)
        }
        assertEquals(PullRecoveryReason.MISSING_BASELINE_OR_CURSOR, (absentRow.stop as PullDeliveryStop.RecoveryRequired).reason)
        assertEquals(0, absentRowApi.calls.size)
        // Null cursor requires snapshots, never null-cursor bootstrap.
        database.pullSeedBaseline(cursor = null)
        val nullCursorApi = PullDeliveryScriptApi(database, mutableListOf())
        val nullCursor = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(nullCursorApi).pull(run)
        }
        assertEquals(0, nullCursor.committedBatches)
        assertEquals(PullRecoveryReason.MISSING_BASELINE_OR_CURSOR, (nullCursor.stop as PullDeliveryStop.RecoveryRequired).reason)
        assertEquals(0, nullCursorApi.calls.size)
        assertNull(database.pullSyncCursor())
    }

    @Test
    fun corruptState_propagatesWithoutRepairOrTransport() = runTest {
        database.pullSeedBaseline()
        database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, "", PULL_LAST_SUCCESS))
        val before = database.claimSnapshot()
        val api = PullDeliveryScriptApi(database, mutableListOf())
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            try {
                PullDeliveryLoop(api).pull(run)
                fail("corrupt cursor must propagate")
            } catch (_: PullStorageException) {
            }
        }
        assertEquals(0, api.calls.size)
        assertEquals(before, database.claimSnapshot())
        assertEquals("", database.syncDao().getSyncState(PULL_ACCOUNT_A)?.cursor)
    }

    @Test
    fun wholePageMalformed_rejectedBeforeFirstCommit() = runTest {
        data class Case(val label: String, val page: () -> PullResultModel)
        val cases = listOf(
            Case("excess-batches", {
                pullDeliveryPage(
                    batches = listOf(
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                            batchId = PULL_BATCH_1, cursor = PD_CURSOR_5,
                        ),
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord())),
                            batchId = PULL_BATCH_2, cursor = PULL_CURSOR_2,
                        ),
                    ),
                    nextCursor = PULL_CURSOR_2, hasMore = false,
                ).let { page ->
                    // Caller requests limit=1 but page carries 2 batches.
                    assertEquals(2, page.batches.size)
                    page
                }
            }),
            Case("empty-nextCursor", {
                pullDeliveryPage(
                    batches = listOf(
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                            cursor = PULL_CURSOR_2,
                        ),
                    ),
                    nextCursor = "", hasMore = false,
                )
            }),
            Case("empty-page-hasMore", {
                pullDeliveryPage(emptyList(), PULL_CURSOR_1, true)
            }),
            Case("empty-page-moved-cursor", {
                pullDeliveryPage(emptyList(), PULL_CURSOR_2, false)
            }),
            Case("nextCursor-not-last", {
                pullDeliveryPage(
                    batches = listOf(
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                            cursor = PULL_CURSOR_2,
                        ),
                    ),
                    nextCursor = PD_CURSOR_5, hasMore = false,
                )
            }),
            Case("duplicate-batch-ids", {
                pullDeliveryPage(
                    batches = listOf(
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                            batchId = PULL_BATCH_1, cursor = PD_CURSOR_5,
                        ),
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord())),
                            batchId = PULL_BATCH_1, cursor = PULL_CURSOR_2,
                        ),
                    ),
                    nextCursor = PULL_CURSOR_2, hasMore = false,
                )
            }),
            Case("duplicate-cursors-in-page", {
                pullDeliveryPage(
                    batches = listOf(
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                            batchId = PULL_BATCH_1, cursor = PULL_CURSOR_2,
                        ),
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord())),
                            batchId = PULL_BATCH_2, cursor = PULL_CURSOR_2,
                        ),
                    ),
                    nextCursor = PULL_CURSOR_2, hasMore = false,
                )
            }),
            Case("batch-cursor-equals-initial", {
                pullDeliveryPage(
                    batches = listOf(
                        pullBatch(
                            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                            cursor = PULL_CURSOR_1,
                        ),
                    ),
                    nextCursor = PULL_CURSOR_1, hasMore = false,
                )
            }),
            Case("empty-batches-nonempty-cursor-hasMore-false", {
                // Non-terminal shape cannot advance: empty page must preserve input.
                pullDeliveryPage(emptyList(), PD_CURSOR_5, false)
            }),
        )
        cases.forEach { (label, build) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline()
            val limit = if (label == "excess-batches") 1 else 100
            val api = PullDeliveryScriptApi(
                database, mutableListOf(PullScriptResponse.Page(build())),
            )
            val result = database.claimPushStore().withRun(PULL_EPOCH) { run ->
                if (label == "excess-batches") {
                    PullDeliveryLoop(api).pull(run, pageLimit = 1)
                } else {
                    PullDeliveryLoop(api).pull(run)
                }
            }
            assertEquals("$label commits", 0, result.committedBatches)
            assertTrue("$label must return Failed, got ${result.stop}", result.stop is PullDeliveryStop.Failed)
            val failed = result.stop as PullDeliveryStop.Failed
            assertTrue("$label malformed", failed.failure is SyncApiException.MalformedResponse)
            assertEquals("$label one call", 1, api.calls.size)
            assertEquals("$label no canonical", null, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
            assertEquals("$label cursor", PULL_CURSOR_1, database.pullSyncCursor())
        }
    }

    @Test
    fun crossPageBatchIdAndCursorCycles_rejectedAfterEarlierCommitsSurvive() = runTest {
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
        // Second page reuses the first page's batch ID with a fresh cursor.
        val idCycle = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord())),
                    batchId = PULL_BATCH_1, cursor = PULL_CURSOR_3,
                ),
            ),
            nextCursor = PULL_CURSOR_3, hasMore = false,
        )
        val idApi = PullDeliveryScriptApi(
            database, mutableListOf(PullScriptResponse.Page(first), PullScriptResponse.Page(idCycle)),
        )
        val idResult = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(idApi).pull(run)
        }
        assertEquals(1, idResult.committedBatches)
        assertTrue(idResult.stop is PullDeliveryStop.Failed)
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        assertEquals(7L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
        assertNull(database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "routine", PULL_ROUTINE_A))
        // Cursor cycle: fresh batch ID but a cursor already seen as the initial boundary.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.pullSeedBaseline()
        val cursorCycle = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord())),
                    batchId = PULL_BATCH_2, cursor = PULL_CURSOR_1,
                ),
            ),
            nextCursor = PULL_CURSOR_1, hasMore = false,
        )
        val cursorApi = PullDeliveryScriptApi(
            database,
            mutableListOf(
                PullScriptResponse.Page(first),
                PullScriptResponse.Page(cursorCycle),
            ),
        )
        val cursorResult = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(cursorApi).pull(run)
        }
        assertEquals(1, cursorResult.committedBatches)
        assertTrue(cursorResult.stop is PullDeliveryStop.Failed)
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
    }

    @Test
    fun cursorExpired_returnsRecoveryWithoutWrites() = runTest {
        database.pullSeedBaseline()
        val before = database.claimSnapshot()
        val failure = SyncApiException.CursorExpired()
        val api = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Failure(failure)))
        val result = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(api).pull(run)
        }
        assertEquals(0, result.committedBatches)
        assertTrue("expiry must return RecoveryRequired, got ${result.stop}", result.stop is PullDeliveryStop.RecoveryRequired)
        val recovery = result.stop as PullDeliveryStop.RecoveryRequired
        assertEquals(PullRecoveryReason.CURSOR_EXPIRED, recovery.reason)
        assertEquals(1, api.calls.size)
        assertEquals(before, database.claimSnapshot())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
    }

    @Test
    fun otherTypedFailures_returnFailedCarryingExactFailure() = runTest {
        val failures: List<Pair<String, SyncApiException>> = listOf(
            "invalid-cursor" to SyncApiException.InvalidCursor(),
            "foreign-rejected" to SyncApiException.RequestRejected("foreign_cursor"),
            "wrong-snapshot-token" to SyncApiException.RequestRejected("invalid_snapshot_token"),
            "wrong-snapshot-expired" to SyncApiException.RequestRejected("snapshot_expired"),
            "malformed" to SyncApiException.MalformedResponse(),
            "auth" to SyncApiException.AuthenticationRequired(),
            "reauth" to SyncApiException.ReauthenticationRequired(),
            "transient" to SyncApiException.Transient(),
            "transient-hint" to SyncApiException.Transient(5_000L),
            "oversized" to SyncApiException.RequestTooLarge(),
            "unknown-rejected" to SyncApiException.RequestRejected("future_permanent_code"),
            "invalid-snapshot" to SyncApiException.InvalidSnapshotToken(),
            "snapshot-expired" to SyncApiException.SnapshotExpired(),
        )
        failures.forEach { (label, failure) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline()
            val before = database.claimSnapshot()
            val api = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Failure(failure)))
            val result = database.claimPushStore().withRun(PULL_EPOCH) { run ->
                PullDeliveryLoop(api).pull(run)
            }
            assertEquals("$label commits", 0, result.committedBatches)
            assertTrue("$label must return Failed, got ${result.stop}", result.stop is PullDeliveryStop.Failed)
            val failed = result.stop as PullDeliveryStop.Failed
            assertSame("$label identity", failure, failed.failure)
            assertEquals("$label one call", 1, api.calls.size)
            assertEquals("$label no writes", before, database.claimSnapshot())
            assertEquals("$label cursor", PULL_CURSOR_1, database.pullSyncCursor())
        }
    }

    @Test
    fun earlierCommitsSurviveLaterPageTransportFailure() = runTest {
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
        val transient = SyncApiException.Transient(5_000L)
        val api = PullDeliveryScriptApi(
            database,
            mutableListOf(PullScriptResponse.Page(first), PullScriptResponse.Failure(transient)),
        )
        val result = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(api).pull(run)
        }
        assertEquals(1, result.committedBatches)
        assertTrue("later transport failure must return Failed, got ${result.stop}", result.stop is PullDeliveryStop.Failed)
        val failed = result.stop as PullDeliveryStop.Failed
        assertSame(transient, failed.failure)
        assertEquals(2, api.calls.size)
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        assertEquals(7L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
    }

    @Test
    fun unexpectedApiError_propagatesWithProvenance() = runTest {
        database.pullSeedBaseline()
        val before = database.claimSnapshot()
        val boom = IllegalStateException("boom-pull-transport")
        val api = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Unexpected(boom)))
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            try {
                PullDeliveryLoop(api).pull(run)
                fail("unexpected error must propagate")
            } catch (error: IllegalStateException) {
                assertSame(boom, error)
            }
        }
        assertEquals(1, api.calls.size)
        assertEquals(before, database.claimSnapshot())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
    }

    @Test
    fun samePageEarlyCommitSurvivesLaterCanonicalConflict_resumesExactly() = runTest {
        database.pullSeedBaseline()
        val page = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 5L, pullWorkoutRecord(revision = 5L))),
                    batchId = PULL_BATCH_1, cursor = PULL_CURSOR_2,
                ),
                pullBatch(
                    changes = listOf(
                        pullUpsert(
                            AggregateType.WORKOUT, PULL_WORKOUT_A, 5L,
                            pullWorkoutRecord(name = "Conflicting Name", revision = 5L),
                        ),
                    ),
                    batchId = PULL_BATCH_2, cursor = PULL_CURSOR_3,
                ),
            ),
            nextCursor = PULL_CURSOR_3, hasMore = false,
        )
        val api = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Page(page)))
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            try {
                PullDeliveryLoop(api).pull(run)
                fail("equal conflict must propagate")
            } catch (_: CanonicalConflictException) {
            }
        }
        // First batch of the page committed; the conflicting batch did not.
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        assertEquals(5L, database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
        // Recovery rereads the exact durable boundary and settles the tail.
        val retry = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 6L, pullWorkoutRecord(revision = 6L))),
                    batchId = PD_BATCH_4, cursor = PULL_CURSOR_3,
                ),
            ),
            nextCursor = PULL_CURSOR_3, hasMore = false,
        )
        val retryApi = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Page(retry)))
        val recovered = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(retryApi).pull(run)
        }
        assertEquals(1, recovered.committedBatches)
        assertTrue(recovered.stop is PullDeliveryStop.Tail)
        assertEquals(listOf(RecordedPullCall(PULL_CURSOR_2, 100)), retryApi.calls)
        assertEquals(PULL_CURSOR_3, database.pullSyncCursor())
    }

    @Test
    fun cursorWriteTrigger_rollsBackBatchAndResumes() = runTest {
        database.pullSeedBaseline()
        val page = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                    cursor = PULL_CURSOR_2,
                ),
            ),
            nextCursor = PULL_CURSOR_2, hasMore = false,
        )
        val api = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Page(page)))
        database.pullBlockCursorWrites()
        try {
            database.claimPushStore().withRun(PULL_EPOCH) { run ->
                try {
                    PullDeliveryLoop(api).pull(run)
                    fail("cursor trigger failure must propagate")
                } catch (error: PullStorageException) {
                    assertEquals("Pull checkpoint storage operation failed", error.message)
                    assertNull(error.cause)
                }
            }
        } finally {
            database.pullDropTrigger("pull_block_cursor")
        }
        assertNull(database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        // Recovery from the exact durable boundary settles.
        val retryApi = PullDeliveryScriptApi(
            database,
            mutableListOf(
                PullScriptResponse.Page(
                    pullDeliveryPage(
                        batches = listOf(
                            pullBatch(
                                changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                                cursor = PULL_CURSOR_2,
                            ),
                        ),
                        nextCursor = PULL_CURSOR_2, hasMore = false,
                    ),
                ),
            ),
        )
        val recovered = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(retryApi).pull(run)
        }
        assertEquals(1, recovered.committedBatches)
        assertTrue(recovered.stop is PullDeliveryStop.Tail)
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
    }

    @Test
    fun diskReopen_recoversExactCursorBoundary() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("pull-delivery-reopen")
        val fileDatabase = claimFileDatabase(context, name)
        try {
            fileDatabase.pullSeedBaseline()
            val first = pullDeliveryPage(
                batches = listOf(
                    pullBatch(
                        changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                        cursor = PULL_CURSOR_2,
                    ),
                ),
                nextCursor = PULL_CURSOR_2, hasMore = true,
            )
            val api = PullDeliveryScriptApi(fileDatabase, mutableListOf(PullScriptResponse.Page(first)))
            // Budget stops after the first page even though more remains.
            val limited = fileDatabase.claimPushStore().withRun(PULL_EPOCH) { run ->
                PullDeliveryLoop(api).pull(run, maxPages = 1)
            }
            assertEquals(1, limited.committedBatches)
            assertTrue(limited.stop is PullDeliveryStop.LimitReached)
            assertEquals(PULL_CURSOR_2, fileDatabase.pullSyncCursor())
        } finally {
            fileDatabase.close()
        }
        val reopened = claimFileDatabase(context, name)
        try {
            reopened.claimSeedDevice()
            assertEquals(PULL_CURSOR_2, reopened.pullSyncCursor())
            val second = pullDeliveryPage(
                batches = listOf(
                    pullBatch(
                        changes = listOf(pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord())),
                        batchId = PULL_BATCH_2, cursor = PULL_CURSOR_3,
                    ),
                ),
                nextCursor = PULL_CURSOR_3, hasMore = false,
            )
            val resumeApi = PullDeliveryScriptApi(reopened, mutableListOf(PullScriptResponse.Page(second)))
            val resumed = reopened.claimPushStore().withRun(PULL_EPOCH) { run ->
                PullDeliveryLoop(resumeApi).pull(run)
            }
            assertEquals(1, resumed.committedBatches)
            assertTrue(resumed.stop is PullDeliveryStop.Tail)
            assertEquals(listOf(RecordedPullCall(PULL_CURSOR_2, 100)), resumeApi.calls)
            assertEquals(PULL_CURSOR_3, reopened.pullSyncCursor())
            assertEquals(7L, reopened.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun redactedDisplays_omitSecrets() = runTest {
        database.pullSeedBaseline()
        val page = pullDeliveryPage(
            batches = listOf(
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                    cursor = PULL_CURSOR_2,
                ),
            ),
            nextCursor = PULL_CURSOR_2, hasMore = false,
        )
        val api = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Page(page)))
        assertEquals("PullDeliveryLoop", PullDeliveryLoop(api).toString())
        val result = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(api).pull(run)
        }
        pullDeliveryAssertRedacted(result.toString(), "result")
        pullDeliveryAssertRedacted(result.stop.toString(), "stop")
        // Failed display never prints the supplied exception or service code.
        database.claimExec("DELETE FROM sync_state")
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.pullSeedBaseline()
        val rejected = SyncApiException.RequestRejected("future_permanent_code")
        val failedApi = PullDeliveryScriptApi(database, mutableListOf(PullScriptResponse.Failure(rejected)))
        val failed = database.claimPushStore().withRun(PULL_EPOCH) { run ->
            PullDeliveryLoop(failedApi).pull(run)
        }
        assertTrue("expected Failed, got ${failed.stop}", failed.stop is PullDeliveryStop.Failed)
        val failedStop = failed.stop as PullDeliveryStop.Failed
        assertSame(rejected, failedStop.failure)
        pullDeliveryAssertRedacted(failed.toString(), "failed-result")
        pullDeliveryAssertRedacted(failedStop.toString(), "failed-stop")
    }
}
