package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.store.CanonicalConflictException
import com.dragosfotache.gymtracka.data.sync.store.PullStorageException
import com.dragosfotache.gymtracka.data.sync.store.StalePullCheckpointException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R07a independent pull-checkpoint acceptance: eligible read/check/commit,
 * null-vs-corrupt boundaries, opaque cursors, multi-root commit preservation,
 * unknown-DELETE evidence, no-op advance, conflict rollback, invalid input,
 * owned-input race, trigger rollback and redacted displays.
 *
 * Real Room, no mocked DAOs, no sleeps. Every expectation is a literal
 * authored from docs/archive/remaining-work/R07a-task.md; no production encoder
 * output is used as an oracle and no production source was inspected.
 */
class PullCheckpointTest : GymDatabaseTest() {

    @Test
    fun eligibleReadAndCheck_areSideEffectFree() = runTest {
        database.pullSeedBaseline()
        val before = database.claimSnapshot()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Eligible baseline must yield a checkpoint" }
            assertEquals(PULL_CURSOR_1, checkpoint.cursor)
            // Read-only check is evidence only: no stored data changes.
            run.checkPullCheckpoint(checkpoint)
            assertEquals(before, database.claimSnapshot())
            run.checkPullCheckpoint(checkpoint)
            assertEquals(before, database.claimSnapshot())
            // Second read supersedes the first token without touching storage.
            val fresh = checkNotNull(run.readPullCheckpoint()) { "Second eligible read must yield a checkpoint" }
            assertEquals(PULL_CURSOR_1, fresh.cursor)
            assertEquals(before, database.claimSnapshot())
            try {
                run.checkPullCheckpoint(checkpoint)
                fail("Superseded token must be stale")
            } catch (_: StalePullCheckpointException) {
            }
            assertEquals(before, database.claimSnapshot())
        }
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun missingBaselineVariants_returnNullWithoutRepair() = runTest {
        // Absent cache row.
        database.claimSeedDevice()
        database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, PULL_CURSOR_1, PULL_LAST_SUCCESS))
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            assertNull(run.readPullCheckpoint())
        }
        // Incomplete baseline flag.
        database.pullSeedBaseline(baselineComplete = false)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            assertNull(run.readPullCheckpoint())
        }
        // Absent sync-state row.
        database.claimSeedDevice()
        database.claimExec("DELETE FROM sync_account_cache")
        database.claimSeedCache(PULL_ACCOUNT_A, PULL_EPOCH)
        database.claimExec("DELETE FROM sync_state")
        val cacheOnly = database.claimSnapshot()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            assertNull(run.readPullCheckpoint())
            assertEquals(cacheOnly, database.claimSnapshot())
        }
        // Null cursor means bootstrap needed, never retained-history authorization.
        database.pullSeedBaseline(cursor = null)
        val nullCursor = database.claimSnapshot()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            assertNull(run.readPullCheckpoint())
            assertEquals(nullCursor, database.claimSnapshot())
        }
        // Null read repairs nothing: the row is still absent/null afterwards.
        assertNull(database.pullSyncCursor())
    }

    @Test
    fun corruptState_failsClosedWithoutRepair() = runTest {
        val cases = listOf(
            Triple("empty-cursor", SyncStateEntity(PULL_ACCOUNT_A, "", PULL_LAST_SUCCESS), null),
            Triple("negative-success", SyncStateEntity(PULL_ACCOUNT_A, PULL_CURSOR_1, -5L), null),
            Triple(
                "empty-cursor-absent-cache",
                SyncStateEntity(PULL_ACCOUNT_A, "", PULL_LAST_SUCCESS),
                "DELETE FROM sync_account_cache WHERE account_id = '$PULL_ACCOUNT_A'",
            ),
            Triple(
                "empty-cursor-incomplete-baseline",
                SyncStateEntity(PULL_ACCOUNT_A, "", PULL_LAST_SUCCESS),
                "UPDATE sync_account_cache SET baseline_complete = 0 WHERE account_id = '$PULL_ACCOUNT_A'",
            ),
            Triple(
                "negative-success-null-cursor",
                SyncStateEntity(PULL_ACCOUNT_A, null, -5L),
                null,
            ),
        )
        cases.forEach { (label, row, cacheSql) ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline(cursor = PULL_CURSOR_1)
            database.syncDao().upsertSyncState(row)
            if (cacheSql != null) {
                database.claimExec(cacheSql)
            }
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
    fun whitespaceCursor_preservedExactlyAndCommits() = runTest {
        database.pullSeedBaseline(cursor = PULL_WHITESPACE_CURSOR)
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Whitespace token must be admitted exactly" }
            assertEquals(PULL_WHITESPACE_CURSOR, checkpoint.cursor)
            run.checkPullCheckpoint(checkpoint)
            val batch = pullBatch(
                changes = listOf(
                    pullUpsert(
                        AggregateType.WORKOUT,
                        PULL_WORKOUT_A,
                        7L,
                        pullWorkoutRecord(revision = 7L),
                    ),
                ),
                cursor = PULL_OPAQUE_CURSOR,
            )
            val result = run.applyNextPullBatch(checkpoint, batch)
            assertEquals(PULL_OPAQUE_CURSOR, result.checkpoint.cursor)
            assertEquals(1, result.canonical.appliedRoots)
        }
        assertEquals(PULL_OPAQUE_CURSOR, database.pullSyncCursor())
    }

    @Test
    fun completeMultiRootCommit_advancesCursorExactly() = runTest {
        database.pullSeedBaseline()
        // Untouched scopes: foreign account rows, local domain aggregate,
        // outstanding outbox intent and a newer local generation.
        database.claimSeedWorkout(PULL_FOREIGN_WORKOUT, PULL_ACCOUNT_B, "Foreign")
        database.r05bSeedCanonicalRow(
            PULL_ACCOUNT_B,
            "workout",
            PULL_FOREIGN_WORKOUT,
            2L,
            "upsert",
            "{\"revision\":2}",
            0L,
            7L,
        )
        database.claimSeedCache(
            PULL_ACCOUNT_B,
            7L,
            baselineComplete = true,
            projectionDirty = true,
            materializedEpoch = 2L,
        )
        database.claimSeedWorkout(PULL_WORKOUT_B, PULL_ACCOUNT_A, "Local Newer", generation = 4L)
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        database.claimCaptureStart(captureStore, PULL_ACCOUNT_A, PULL_WORKOUT_B, "Local Newer")
        database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_B, "foreign-cursor", 777L))
        val outboxBefore = database.claimOutboxFor(PULL_ACCOUNT_A).single()
        val domainBefore = database.workoutDao().getWorkout(PULL_WORKOUT_B)!!
        val batch = pullBatch(
            changes = listOf(
                pullDelete(AggregateType.WORKOUT, PULL_UNKNOWN_CLOUD, 11L),
                pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
                pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord()),
                pullUpsert(AggregateType.CUSTOM_EXERCISE, PULL_CUSTOM_A, 5L, pullCustomRecord()),
            ),
            cursor = PULL_CURSOR_2,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val result = run.applyNextPullBatch(checkpoint, batch)
            assertEquals(4, result.canonical.appliedRoots)
            assertEquals(0, result.canonical.ignoredRoots)
            assertEquals(PULL_CURSOR_2, result.checkpoint.cursor)
            // Unknown DELETE stores evidence without fabricating a domain row.
            val tomb = database.syncCanonicalDao()
                .getRoot(PULL_ACCOUNT_A, "workout", PULL_UNKNOWN_CLOUD)!!
            assertEquals("delete", tomb.canonicalState)
            assertNull(tomb.canonicalJson)
            assertNull(database.workoutDao().getWorkout(PULL_UNKNOWN_CLOUD))
            // Representative observations land with exact revisions.
            assertEquals(
                7L,
                database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision,
            )
            assertEquals(
                3L,
                database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "routine", PULL_ROUTINE_A)?.serverRevision,
            )
            assertEquals(
                5L,
                database.syncCanonicalDao()
                    .getRoot(PULL_ACCOUNT_A, "customExercise", PULL_CUSTOM_A)?.serverRevision,
            )
        }
        // Only selected-account cursor/canonical/dirty move; everything else stands.
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
        assertEquals("foreign-cursor", database.pullSyncCursor(PULL_ACCOUNT_B))
        assertEquals(outboxBefore, database.claimOutboxFor(PULL_ACCOUNT_A).single())
        assertEquals(domainBefore, database.workoutDao().getWorkout(PULL_WORKOUT_B))
        // Fresh observations carry a zero generation watermark.
        assertEquals(0L, database.ackWatermark(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
        assertEquals(0L, database.ackWatermark(PULL_ACCOUNT_A, "routine", PULL_ROUTINE_A))
        assertEquals(0L, database.ackWatermark(PULL_ACCOUNT_A, "customExercise", PULL_CUSTOM_A))
        // Foreign state, domain, canonical evidence and cache stand exactly.
        assertEquals("Foreign", database.workoutDao().getWorkout(PULL_FOREIGN_WORKOUT)?.name)
        val foreignRoot = database.syncCanonicalDao().getRoot(PULL_ACCOUNT_B, "workout", PULL_FOREIGN_WORKOUT)!!
        assertEquals(2L, foreignRoot.serverRevision)
        assertEquals("upsert", foreignRoot.canonicalState)
        assertEquals(0L, foreignRoot.localGenerationWatermark)
        assertEquals("1|7|1|2", database.ackCacheRow(PULL_ACCOUNT_B))
        assertEquals(
            true,
            database.syncCanonicalDao().getAccountCache(PULL_ACCOUNT_A)?.projectionDirty,
        )
        val cache = database.syncCanonicalDao().getAccountCache(PULL_ACCOUNT_A)!!
        assertEquals(PULL_EPOCH, cache.cacheEpoch)
        assertEquals(0L, cache.materializedEpoch)
    }

    @Test
    fun unknownDeleteEvidence_thenLateDependencyResolves() = runTest {
        database.pullSeedBaseline()
        val tombBatch = pullBatch(
            changes = listOf(pullDelete(AggregateType.WORKOUT, PULL_UNKNOWN_CLOUD, 11L)),
            cursor = PULL_CURSOR_2,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val first = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val committed = run.applyNextPullBatch(first, tombBatch)
            assertEquals(1, committed.canonical.appliedRoots)
            assertEquals(PULL_CURSOR_2, committed.checkpoint.cursor)
        }
        // A later batch may supply the previously unknown dependency; the
        // tombstone revision does not block a newer observation.
        val lateBatch = pullBatch(
            changes = listOf(
                pullUpsert(
                    AggregateType.WORKOUT,
                    PULL_UNKNOWN_CLOUD,
                    12L,
                    pullWorkoutRecord(id = PULL_UNKNOWN_CLOUD, revision = 12L),
                ),
            ),
            batchId = PULL_BATCH_2,
            cursor = PULL_CURSOR_3,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val second = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            assertEquals(PULL_CURSOR_2, second.cursor)
            val result = run.applyNextPullBatch(second, lateBatch)
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(PULL_CURSOR_3, result.checkpoint.cursor)
        }
        val resolved = database.syncCanonicalDao()
            .getRoot(PULL_ACCOUNT_A, "workout", PULL_UNKNOWN_CLOUD)!!
        assertEquals(12L, resolved.serverRevision)
        assertEquals("upsert", resolved.canonicalState)
        assertEquals(PULL_CURSOR_3, database.pullSyncCursor())
    }

    @Test
    fun allObsoleteBatch_advancesOnlyCursor() = runTest {
        database.pullSeedBaseline()
        val live = pullBatch(
            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
            cursor = PULL_CURSOR_2,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val first = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val result = run.applyNextPullBatch(first, live)
            assertEquals(1, result.canonical.appliedRoots)
        }
        val storedJson = database.syncCanonicalDao()
            .getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.canonicalJson
        val dirtyAfterLive = database.syncCanonicalDao().getAccountCache(PULL_ACCOUNT_A)?.projectionDirty
        // An older batch is a canonical no-op that still advances the cursor.
        val stale = pullBatch(
            changes = listOf(
                pullUpsert(
                    AggregateType.WORKOUT,
                    PULL_WORKOUT_A,
                    4L,
                    pullWorkoutRecord(name = "Stale Name", revision = 4L),
                ),
            ),
            batchId = PULL_BATCH_2,
            cursor = PULL_CURSOR_3,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val second = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            assertEquals(PULL_CURSOR_2, second.cursor)
            val replay = run.applyNextPullBatch(second, stale)
            assertEquals(0, replay.canonical.appliedRoots)
            assertEquals(1, replay.canonical.ignoredRoots)
            assertEquals(PULL_CURSOR_3, replay.checkpoint.cursor)
        }
        assertEquals(PULL_CURSOR_3, database.pullSyncCursor())
        assertEquals(
            storedJson,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.canonicalJson,
        )
        assertEquals(
            dirtyAfterLive,
            database.syncCanonicalDao().getAccountCache(PULL_ACCOUNT_A)?.projectionDirty,
        )
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
    }

    @Test
    fun equalConflict_rollsBackBatchAndCursor() = runTest {
        database.pullSeedBaseline()
        val first = pullBatch(
            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 5L, pullWorkoutRecord(revision = 5L))),
            cursor = PULL_CURSOR_2,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            run.applyNextPullBatch(checkpoint, first)
        }
        val durableCursor = database.pullSyncCursor()
        val durableJson = database.syncCanonicalDao()
            .getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.canonicalJson
        val before = database.claimSnapshot()
        // Equal revision with a conflicting body must roll the whole batch back.
        val conflicting = pullBatch(
            changes = listOf(
                pullUpsert(
                    AggregateType.WORKOUT,
                    PULL_WORKOUT_A,
                    5L,
                    pullWorkoutRecord(name = "Conflicting Name", revision = 5L),
                ),
            ),
            batchId = PULL_BATCH_2,
            cursor = PULL_CURSOR_3,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            try {
                run.applyNextPullBatch(checkpoint, conflicting)
                fail("Equal conflict must roll back")
            } catch (_: CanonicalConflictException) {
            }
            assertEquals(before, database.claimSnapshot())
            // The rolled-back token is consumed even though nothing committed.
            try {
                run.applyNextPullBatch(
                    checkpoint,
                    pullBatch(
                        changes = listOf(
                            pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 6L, pullWorkoutRecord(revision = 6L)),
                        ),
                        batchId = PULL_BATCH_3,
                        cursor = PULL_CURSOR_3,
                    ),
                )
                fail("Rolled-back token must be consumed")
            } catch (_: StalePullCheckpointException) {
            }
        }
        // Earlier committed batch remains durable; retry rereads the boundary.
        assertEquals(durableCursor, database.pullSyncCursor())
        assertEquals(
            durableJson,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.canonicalJson,
        )
        val retry = pullBatch(
            changes = listOf(
                pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 6L, pullWorkoutRecord(revision = 6L)),
            ),
            batchId = PULL_BATCH_3,
            cursor = PULL_CURSOR_3,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            assertEquals(durableCursor, checkpoint.cursor)
            val result = run.applyNextPullBatch(checkpoint, retry)
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(PULL_CURSOR_3, result.checkpoint.cursor)
        }
        assertEquals(PULL_CURSOR_3, database.pullSyncCursor())
    }

    @Test
    fun invalidBatchAndNoProgress_rejectedWithoutWrites() = runTest {
        database.pullSeedBaseline()
        val before = database.claimSnapshot()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            // UPSERT without a record body is a model failure, never a commit.
            val missingBody = pullBatch(
                changes = listOf(
                    SyncChangeModel(
                        AggregateType.WORKOUT,
                        PULL_WORKOUT_A,
                        ChangeOperation.UPSERT,
                        7L,
                        null,
                    ),
                ),
                cursor = PULL_CURSOR_2,
            )
            try {
                run.applyNextPullBatch(checkpoint, missingBody)
                fail("Bodyless UPSERT must be rejected")
            } catch (_: SyncModelException) {
            }
            // Batch cursor equal to the checkpoint cursor is no progress, even
            // for a structurally valid batch with a matching record body.
            val noProgress = pullBatch(
                changes = listOf(
                    pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord(revision = 7L)),
                ),
                cursor = PULL_CURSOR_1,
            )
            try {
                run.applyNextPullBatch(checkpoint, noProgress)
                fail("Equal cursor must be rejected as no progress")
            } catch (_: SyncModelException) {
            }
            assertEquals(before, database.claimSnapshot())
        }
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        assertNull(
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A),
        )
    }

    @Test
    fun callerMutationAfterSuspension_cannotChangeOwnedBatch() = runTest {
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
            val changes = mutableListOf(
                pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
            )
            val mutableBatch = pullBatch(changes = changes, cursor = PULL_CURSOR_2)
            hookDb.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
                armed.set(true)
                val result = coroutineScope {
                    val committing = async(start = CoroutineStart.UNDISPATCHED) {
                        run.applyNextPullBatch(checkpoint, mutableBatch)
                    }
                    val runnable = captured.await()
                    assertFalse(committing.isCompleted)
                    // Mutate caller-owned nested lists only after suspension.
                    changes.clear()
                    changes.add(pullDelete(AggregateType.WORKOUT, PULL_UNKNOWN_CLOUD, 11L))
                    backingTx.execute(runnable)
                    committing.await()
                }
                armed.set(false)
                // Deep ownership preceded suspension: the workout committed.
                assertEquals(1, result.canonical.appliedRoots)
                assertEquals(PULL_CURSOR_2, result.checkpoint.cursor)
            }
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
    fun wireDecodedBatch_commitsWithoutEncoderOracle() = runTest {
        database.pullSeedBaseline()
        val wire = pullWireBatch(
            changes = JSONArray().put(
                pullWireChange("workout", PULL_WORKOUT_A, "upsert", 7L, pullWireWorkoutRecord()),
            ),
        )
        val decoded = decodePullResult(wire).batches.single()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val result = run.applyNextPullBatch(checkpoint, decoded)
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(PULL_CURSOR_2, result.checkpoint.cursor)
        }
        val stored = JSONObject(
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.canonicalJson!!,
        )
        assertEquals("Pulled Workout", stored.getString("name"))
        assertEquals(7L, stored.getLong("revision"))
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
    }

    @Test
    fun cursorWriteTrigger_rollsBackWithSafeError() = runTest {
        database.pullSeedBaseline()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val preCommit = database.claimSnapshot()
            val batch = pullBatch(
                changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                cursor = PULL_CURSOR_2,
            )
            database.pullBlockCursorWrites()
            try {
                try {
                    run.applyNextPullBatch(checkpoint, batch)
                    fail("Cursor trigger failure must surface as owned storage failure")
                } catch (error: PullStorageException) {
                    val message = error.message ?: ""
                    assertEquals(PULL_STORAGE_MESSAGE, error.message)
                    assertFalse(message.contains("pull_blocked"))
                    assertFalse(message.contains("RAISE"))
                    assertFalse(message.contains("sync_state"))
                    assertNull(error.cause)
                }
            } finally {
                database.pullDropTrigger("pull_block_cursor")
            }
            // Canonical writes roll back together with the cursor.
            assertEquals(preCommit, database.claimSnapshot())
            assertNull(database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
            // Rollback still consumes the process-local token.
            try {
                run.applyNextPullBatch(checkpoint, batch)
                fail("Rolled-back token must be consumed")
            } catch (_: StalePullCheckpointException) {
            }
        }
        // Recovery rereads the exact durable boundary and settles.
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            assertEquals(PULL_CURSOR_1, checkpoint.cursor)
            val result = run.applyNextPullBatch(
                checkpoint,
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                    cursor = PULL_CURSOR_2,
                ),
            )
            assertEquals(1, result.canonical.appliedRoots)
        }
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
    }

    @Test
    fun postWriteCursorTamper_rollsBackBatchAndCheckpoint() = runTest {
        database.pullSeedBaseline()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val preCommit = database.claimSnapshot()
            // A trigger rewrites the cursor behind the commit write; the
            // post-write recheck must roll the batch back with it.
            database.claimExec(
                "CREATE TRIGGER pull_cursor_trip AFTER UPDATE ON sync_state " +
                    "BEGIN UPDATE sync_state SET cursor = 'tampered' " +
                    "WHERE account_id = '$PULL_ACCOUNT_A'; END",
            )
            try {
                try {
                    run.applyNextPullBatch(
                        checkpoint,
                        pullBatch(
                            changes = listOf(
                                pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
                            ),
                            cursor = PULL_CURSOR_2,
                        ),
                    )
                    fail("Cursor-tripping trigger must abort the commit")
                } catch (_: IllegalStateException) {
                }
            } finally {
                database.pullDropTrigger("pull_cursor_trip")
            }
            assertEquals(preCommit, database.claimSnapshot())
            assertNull(database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A))
            try {
                run.checkPullCheckpoint(checkpoint)
                fail("Rolled-back token must be consumed")
            } catch (_: StalePullCheckpointException) {
            }
        }
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
    }

    @Test
    fun knownDeleteAndNewUpsert_commitTogether() = runTest {
        database.pullSeedBaseline()
        val established = pullBatch(
            changes = listOf(
                pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_B, 4L, pullWorkoutRecord(id = PULL_WORKOUT_B, revision = 4L)),
            ),
            cursor = PULL_CURSOR_2,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val first = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            run.applyNextPullBatch(first, established)
        }
        // One batch retires the old active root and observes the new one.
        val replacement = pullBatch(
            changes = listOf(
                pullDelete(AggregateType.WORKOUT, PULL_WORKOUT_B, 5L),
                pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord(revision = 7L)),
            ),
            batchId = PULL_BATCH_2,
            cursor = PULL_CURSOR_3,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val second = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            assertEquals(PULL_CURSOR_2, second.cursor)
            val result = run.applyNextPullBatch(second, replacement)
            assertEquals(2, result.canonical.appliedRoots)
            assertEquals(0, result.canonical.ignoredRoots)
            assertEquals(PULL_CURSOR_3, result.checkpoint.cursor)
        }
        val tomb = database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_B)!!
        assertEquals("delete", tomb.canonicalState)
        assertNull(tomb.canonicalJson)
        assertEquals(
            7L,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision,
        )
        assertNull(database.workoutDao().getWorkout(PULL_WORKOUT_A))
        assertNull(database.workoutDao().getWorkout(PULL_WORKOUT_B))
        assertEquals(PULL_CURSOR_3, database.pullSyncCursor())
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
    }

    @Test
    fun unresolvedExerciseReference_thenDependencyArrives() = runTest {
        database.pullSeedBaseline()
        // The workout references a custom exercise with no canonical row yet.
        val wire = pullWireBatch(
            cursor = PULL_CURSOR_2,
            changes = JSONArray().put(
                pullWireChange(
                    "workout",
                    PULL_WORKOUT_A,
                    "upsert",
                    7L,
                    pullWireWorkoutRecord(
                        revision = 7L,
                        exercises = JSONArray().put(pullWireExerciseEntry()),
                    ),
                ),
            ),
        )
        val observed = decodePullResult(wire).batches.single()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val first = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val result = run.applyNextPullBatch(first, observed)
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(PULL_CURSOR_2, result.checkpoint.cursor)
        }
        assertEquals(
            7L,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision,
        )
        assertNull(database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "customExercise", PULL_CUSTOM_A))
        assertNull(database.workoutDao().getWorkout(PULL_WORKOUT_A))
        // The dependency arrives in a later batch; nothing was published early.
        val dependency = pullBatch(
            changes = listOf(
                pullUpsert(AggregateType.CUSTOM_EXERCISE, PULL_CUSTOM_A, 5L, pullCustomRecord(revision = 5L)),
            ),
            batchId = PULL_BATCH_2,
            cursor = PULL_CURSOR_3,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val second = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            assertEquals(PULL_CURSOR_2, second.cursor)
            val result = run.applyNextPullBatch(second, dependency)
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(PULL_CURSOR_3, result.checkpoint.cursor)
        }
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "customExercise", PULL_CUSTOM_A)?.serverRevision,
        )
        assertEquals(PULL_CURSOR_3, database.pullSyncCursor())
    }

    @Test
    fun pendingLocalIntent_preservedAcrossPull() = runTest {
        database.pullSeedBaseline()
        // Newer local intent is captured and still pending when the pull lands.
        database.claimSeedWorkout(PULL_WORKOUT_B, PULL_ACCOUNT_A, "Local Newer")
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        val pending = database.claimCaptureStart(captureStore, PULL_ACCOUNT_A, PULL_WORKOUT_B, "Local Newer")
        val domainBefore = database.workoutDao().getWorkout(PULL_WORKOUT_B)!!
        val batch = pullBatch(
            changes = listOf(
                pullUpsert(
                    AggregateType.WORKOUT,
                    PULL_WORKOUT_B,
                    7L,
                    pullWorkoutRecord(id = PULL_WORKOUT_B, name = "Remote", revision = 7L),
                ),
            ),
            cursor = PULL_CURSOR_2,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            run.applyNextPullBatch(checkpoint, batch)
        }
        // The canonical-only seam advances its cursor without touching the
        // pending intent: outbox row and domain aggregate stand exactly.
        assertEquals(PULL_CURSOR_2, database.pullSyncCursor())
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
        assertEquals(pending, database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, PULL_ACCOUNT_A))
        assertEquals(domainBefore, database.workoutDao().getWorkout(PULL_WORKOUT_B))
    }

    @Test
    fun noopBatches_keepCleanCacheUndirtied() = runTest {
        database.pullSeedBaseline()
        assertEquals(false, database.syncCanonicalDao().getAccountCache(PULL_ACCOUNT_A)?.projectionDirty)
        val live = pullBatch(
            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord(revision = 7L))),
            cursor = PULL_CURSOR_2,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val first = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val result = run.applyNextPullBatch(first, live)
            assertEquals(1, result.canonical.appliedRoots)
        }
        assertEquals(true, database.syncCanonicalDao().getAccountCache(PULL_ACCOUNT_A)?.projectionDirty)
        val storedJson = database.syncCanonicalDao()
            .getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.canonicalJson
        // Clean the dirty marker without touching anything else: later
        // no-ops must not re-dirty the cache.
        database.claimExec("UPDATE sync_account_cache SET projection_dirty = 0 WHERE account_id = '$PULL_ACCOUNT_A'")
        val equal = pullBatch(
            changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord(revision = 7L))),
            batchId = PULL_BATCH_2,
            cursor = PULL_CURSOR_3,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val second = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            assertEquals(PULL_CURSOR_2, second.cursor)
            val replay = run.applyNextPullBatch(second, equal)
            assertEquals(0, replay.canonical.appliedRoots)
            assertEquals(1, replay.canonical.ignoredRoots)
            assertEquals(PULL_CURSOR_3, replay.checkpoint.cursor)
        }
        assertEquals(false, database.syncCanonicalDao().getAccountCache(PULL_ACCOUNT_A)?.projectionDirty)
        assertEquals(
            storedJson,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.canonicalJson,
        )
        // An obsolete revision is likewise a cursor-only advance.
        val obsolete = pullBatch(
            changes = listOf(
                pullUpsert(
                    AggregateType.WORKOUT,
                    PULL_WORKOUT_A,
                    4L,
                    pullWorkoutRecord(name = "Stale", revision = 4L),
                ),
            ),
            batchId = PULL_BATCH_3,
            cursor = PULL_CURSOR_4,
        )
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val third = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
            assertEquals(PULL_CURSOR_3, third.cursor)
            val replay = run.applyNextPullBatch(third, obsolete)
            assertEquals(0, replay.canonical.appliedRoots)
            assertEquals(1, replay.canonical.ignoredRoots)
            assertEquals(PULL_CURSOR_4, replay.checkpoint.cursor)
        }
        assertEquals(PULL_CURSOR_4, database.pullSyncCursor())
        assertEquals(false, database.syncCanonicalDao().getAccountCache(PULL_ACCOUNT_A)?.projectionDirty)
        assertEquals(
            storedJson,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.canonicalJson,
        )
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
    }

    @Test
    fun diskReopen_recoversExactCursorBoundary() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("pull-reopen")
        val fileDatabase = claimFileDatabase(context, name)
        try {
            fileDatabase.pullSeedBaseline()
            fileDatabase.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
                val result = run.applyNextPullBatch(
                    checkpoint,
                    pullBatch(
                        changes = listOf(
                            pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord(revision = 7L)),
                        ),
                        cursor = PULL_CURSOR_2,
                    ),
                )
                assertEquals(1, result.canonical.appliedRoots)
            }
            assertEquals(PULL_CURSOR_2, fileDatabase.pullSyncCursor())
        } finally {
            fileDatabase.close()
        }
        // Simulated process restart: only the session is reseeded; the
        // durable cursor boundary and canonical evidence must stand.
        val reopened = claimFileDatabase(context, name)
        try {
            reopened.claimSeedDevice()
            assertEquals(PULL_CURSOR_2, reopened.pullSyncCursor())
            assertEquals(
                7L,
                reopened.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision,
            )
            reopened.claimPushStore().withRun(PULL_EPOCH) { run ->
                val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must reread" }
                assertEquals(PULL_CURSOR_2, checkpoint.cursor)
                val result = run.applyNextPullBatch(
                    checkpoint,
                    pullBatch(
                        changes = listOf(
                            pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord(revision = 3L)),
                        ),
                        batchId = PULL_BATCH_2,
                        cursor = PULL_CURSOR_3,
                    ),
                )
                assertEquals(1, result.canonical.appliedRoots)
                assertEquals(PULL_CURSOR_3, result.checkpoint.cursor)
            }
            assertEquals(PULL_CURSOR_3, reopened.pullSyncCursor())
            assertEquals(
                7L,
                reopened.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision,
            )
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun failedAndNullReads_retirePriorToken() = runTest {
        database.pullSeedBaseline()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val live = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            assertEquals(PULL_CURSOR_1, live.cursor)
            val before = database.claimSnapshot()
            // A failed reread retires the prior token even after the durable
            // row is repaired: retirement is process-local, not storage.
            database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, "", PULL_LAST_SUCCESS))
            try {
                run.readPullCheckpoint()
                fail("Corrupt row must fail the reread")
            } catch (error: PullStorageException) {
                assertEquals(PULL_STORAGE_MESSAGE, error.message)
            }
            database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, PULL_CURSOR_1, PULL_LAST_SUCCESS))
            try {
                run.checkPullCheckpoint(live)
                fail("Failed-read retirement must be sticky after repair")
            } catch (_: StalePullCheckpointException) {
            }
            assertEquals(before, database.claimSnapshot())
            // A null reread likewise retires: the boundary row is gone, so
            // the prior token can never validate again.
            val fresh = checkNotNull(run.readPullCheckpoint()) { "Must reread repaired boundary" }
            assertEquals(PULL_CURSOR_1, fresh.cursor)
            database.claimExec("DELETE FROM sync_state WHERE account_id = '$PULL_ACCOUNT_A'")
            assertNull(run.readPullCheckpoint())
            try {
                run.checkPullCheckpoint(fresh)
                fail("Null-read retirement must invalidate the token")
            } catch (_: StalePullCheckpointException) {
            }
        }
        assertNull(database.pullSyncCursor())
    }

    @Test
    fun sameRunSequentialCommits_useFreshCheckpointWithoutReread() = runTest {
        database.pullSeedBaseline()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val first = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val one = run.applyNextPullBatch(
                first,
                pullBatch(
                    changes = listOf(
                        pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord(revision = 7L)),
                    ),
                    cursor = PULL_CURSOR_2,
                ),
            )
            assertEquals(1, one.canonical.appliedRoots)
            assertEquals(PULL_CURSOR_2, one.checkpoint.cursor)
            // The fresh token validates without an explicit reread.
            run.checkPullCheckpoint(one.checkpoint)
            val two = run.applyNextPullBatch(
                one.checkpoint,
                pullBatch(
                    changes = listOf(
                        pullUpsert(AggregateType.ROUTINE, PULL_ROUTINE_A, 3L, pullRoutineRecord(revision = 3L)),
                    ),
                    batchId = PULL_BATCH_2,
                    cursor = PULL_CURSOR_3,
                ),
            )
            assertEquals(1, two.canonical.appliedRoots)
            assertEquals(PULL_CURSOR_3, two.checkpoint.cursor)
            run.checkPullCheckpoint(two.checkpoint)
            // The original token stays stale; last-success never moves.
            try {
                run.checkPullCheckpoint(first)
                fail("Original token must stay stale after chaining")
            } catch (_: StalePullCheckpointException) {
            }
            assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
        }
        assertEquals(PULL_CURSOR_3, database.pullSyncCursor())
        assertEquals(PULL_LAST_SUCCESS, database.pullLastSuccess())
        assertEquals(
            7L,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "workout", PULL_WORKOUT_A)?.serverRevision,
        )
        assertEquals(
            3L,
            database.syncCanonicalDao().getRoot(PULL_ACCOUNT_A, "routine", PULL_ROUTINE_A)?.serverRevision,
        )
    }

    @Test
    fun redactedDisplays_omitSecrets() = runTest {
        database.pullSeedBaseline()
        database.claimPushStore().withRun(PULL_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read" }
            val checkpointText = checkpoint.toString()
            assertFalse(checkpointText.contains(PULL_CURSOR_1))
            assertFalse(checkpointText.contains(PULL_ACCOUNT_A))
            assertTrue(checkpointText.isNotBlank())
            val result = run.applyNextPullBatch(
                checkpoint,
                pullBatch(
                    changes = listOf(pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord())),
                    cursor = PULL_CURSOR_2,
                ),
            )
            pullAssertRedacted(result.toString(), "commit")
            pullAssertRedacted(result.checkpoint.toString(), "fresh-checkpoint")
        }
    }
}
