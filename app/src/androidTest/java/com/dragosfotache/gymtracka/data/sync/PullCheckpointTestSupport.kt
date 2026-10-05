package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * R07a independent QA fixtures. Hand-written from docs/archive/remaining-work/R07a-task.md,
 * the coordinator's QA API/harness facts and allowed existing client test
 * sources only.
 *
 * Every expected value below is a literal authored here; no production encoder
 * output is used as an oracle. Durable seeding reuses the existing vetted R06a
 * claim/capture helpers and R06c acknowledgment observation helpers; only
 * pull-seam-specific builders and cursor literals are new. No production
 * declaration, implementation, generated production code, diff or Worker
 * output was inspected.
 */

// Distinct pull-seam literals (not production oracles).
internal const val PULL_ACCOUNT_A = CLAIM_ACCOUNT_A
internal const val PULL_ACCOUNT_B = CLAIM_ACCOUNT_B
internal const val PULL_GENERATION = CLAIM_GENERATION
internal const val PULL_EPOCH = 0L

internal const val PULL_CURSOR_1 = "pull-cursor-1"
internal const val PULL_CURSOR_2 = "pull-cursor-2"
internal const val PULL_CURSOR_3 = "pull-cursor-3"
internal const val PULL_CURSOR_4 = "pull-cursor-4"
internal const val PULL_WHITESPACE_CURSOR = "   "
internal const val PULL_OPAQUE_CURSOR = "opaque \t pull-token-9"

internal const val PULL_BATCH_1 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001"
internal const val PULL_BATCH_2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0002"
internal const val PULL_BATCH_3 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0003"

internal const val PULL_WORKOUT_A = "a1111111-1111-1111-1111-111111111111"
internal const val PULL_WORKOUT_B = "a2222222-2222-2222-2222-222222222222"
internal const val PULL_ROUTINE_A = "d1111111-1111-1111-1111-111111111111"
internal const val PULL_CUSTOM_A = "e1111111-1111-1111-1111-111111111111"
internal const val PULL_UNKNOWN_CLOUD = "f1111111-1111-1111-1111-111111111111"
internal const val PULL_FOREIGN_WORKOUT = "b1111111-1111-1111-1111-111111111111"

internal const val PULL_LAST_SUCCESS = 1_790_258_500_000L

internal const val PULL_STALE_MESSAGE = "Stale pull checkpoint"
internal const val PULL_STORAGE_MESSAGE = "Pull checkpoint storage operation failed"

/** Seeds device, complete baseline cache and one sync-state row. */
internal suspend fun GymDatabase.pullSeedBaseline(
    cursor: String? = PULL_CURSOR_1,
    lastSuccessMillis: Long? = PULL_LAST_SUCCESS,
    account: String = PULL_ACCOUNT_A,
    cacheEpoch: Long = PULL_EPOCH,
    baselineComplete: Boolean = true,
) {
    claimSeedDevice(account, PULL_GENERATION)
    claimExec("DELETE FROM sync_account_cache")
    claimSeedCache(account, cacheEpoch, baselineComplete = baselineComplete)
    syncDao().upsertSyncState(SyncStateEntity(account, cursor, lastSuccessMillis))
}

internal suspend fun GymDatabase.pullSyncCursor(account: String = PULL_ACCOUNT_A): String? =
    syncDao().getSyncState(account)?.cursor

internal suspend fun GymDatabase.pullLastSuccess(account: String = PULL_ACCOUNT_A): Long? =
    syncDao().getSyncState(account)?.lastSuccessMillis

internal fun pullWorkoutRecord(
    id: String = PULL_WORKOUT_A,
    name: String = "Pulled Workout",
    revision: Long = 7L,
    exercises: List<WorkoutExerciseModel> = emptyList(),
): CanonicalRecord.Workout = CanonicalRecord.Workout(
    WorkoutRecordModel(id, name, "active", CLAIM_STARTED_MILLIS, null, revision, exercises),
)

internal fun pullRoutineRecord(
    id: String = PULL_ROUTINE_A,
    name: String = "Pulled Routine",
    revision: Long = 3L,
): CanonicalRecord.Routine = CanonicalRecord.Routine(
    RoutineRecordModel(id, name, false, revision, emptyList()),
)

internal fun pullCustomRecord(
    id: String = PULL_CUSTOM_A,
    name: String = "Pulled Custom",
    revision: Long = 5L,
): CanonicalRecord.CustomExercise = CanonicalRecord.CustomExercise(
    CustomExerciseRecordModel(
        id, name, "reps", null, null, null, null, null,
        true, revision, emptyList(), emptyList(),
    ),
)

internal fun pullUpsert(
    aggregateType: AggregateType,
    aggregateId: String,
    revision: Long,
    record: CanonicalRecord,
): SyncChangeModel = SyncChangeModel(aggregateType, aggregateId, ChangeOperation.UPSERT, revision, record)

internal fun pullDelete(
    aggregateType: AggregateType = AggregateType.WORKOUT,
    aggregateId: String = PULL_UNKNOWN_CLOUD,
    revision: Long = 11L,
): SyncChangeModel = SyncChangeModel(aggregateType, aggregateId, ChangeOperation.DELETE, revision, null)

/** Shared-contract order: DELETE changes first, then UPSERT observations. */
internal fun pullBatch(
    changes: List<SyncChangeModel>,
    batchId: String = PULL_BATCH_1,
    cursor: String = PULL_CURSOR_2,
): SyncBatchModel = SyncBatchModel(batchId = batchId, cursor = cursor, changes = changes)

/** Hand-authored wire shell for one pull batch; decoded with the existing codec, never an encoder. */
internal fun pullWireBatch(
    batchId: String = PULL_BATCH_1,
    cursor: String = PULL_CURSOR_2,
    changes: JSONArray,
): String =
    JSONObject()
        .put(
            "batches",
            JSONArray().put(
                JSONObject()
                    .put("batchId", batchId)
                    .put("cursor", cursor)
                    .put("changes", changes),
            ),
        )
        .put("nextCursor", cursor)
        .put("hasMore", false)
        .toString()

internal fun pullWireChange(
    aggregateType: String,
    aggregateId: String,
    operation: String,
    revision: Long,
    record: JSONObject?,
): JSONObject =
    JSONObject()
        .put("aggregateType", aggregateType)
        .put("aggregateId", aggregateId)
        .put("operation", operation)
        .put("revision", revision)
        .put("record", record ?: JSONObject.NULL)

internal fun pullWireWorkoutRecord(
    id: String = PULL_WORKOUT_A,
    name: String = "Pulled Workout",
    revision: Long = 7L,
    exercises: JSONArray = JSONArray(),
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("status", "active")
        .put("startedAt", "2026-09-24T14:00:00Z")
        .put("finishedAt", JSONObject.NULL)
        .put("revision", revision)
        .put("exercises", exercises)
        .put("totalVolumeKg", 0.0)

/** Hand-authored workout set entry; shapes mirror allowed R05b fixtures, literals are new. */
internal fun pullWireSet(
    id: String = "33333333-3333-3333-3333-333333333333",
    position: Int = 0,
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("position", position)
        .put("repetitions", 8)
        .put("weightKg", 50.0)
        .put("isCompleted", false)
        .put("restSeconds", JSONObject.NULL)

/** Hand-authored workout exercise entry referencing the given catalogue exercise. */
internal fun pullWireExerciseEntry(
    id: String = "11111111-1111-1111-1111-111111111111",
    exerciseId: String = PULL_CUSTOM_A,
    exerciseName: String = "Pulled Custom",
    sets: JSONArray = JSONArray().put(pullWireSet()),
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("exerciseId", exerciseId)
        .put("exerciseName", exerciseName)
        .put("position", 0)
        .put("notes", "")
        .put("restDurationSeconds", 30)
        .put("sets", sets)

internal fun GymDatabase.pullBlockCursorWrites(trigger: String = "pull_block_cursor") {
    openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER $trigger BEFORE UPDATE ON sync_state " +
            "BEGIN SELECT RAISE(ABORT, 'pull_blocked'); END",
    )
}

internal fun GymDatabase.pullDropTrigger(trigger: String) {
    openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
}

/** Redaction oracle: no cursor, account, batch, root or domain body may appear in display text. */
internal fun pullAssertRedacted(text: String, label: String) {
    val secrets = listOf(
        PULL_CURSOR_1, PULL_CURSOR_2, PULL_CURSOR_3, PULL_CURSOR_4,
        PULL_OPAQUE_CURSOR,
        PULL_BATCH_1, PULL_BATCH_2, PULL_BATCH_3,
        PULL_WORKOUT_A, PULL_ROUTINE_A, PULL_CUSTOM_A, PULL_UNKNOWN_CLOUD,
        PULL_ACCOUNT_A, PULL_ACCOUNT_B,
        "Pulled Workout", "Pulled Routine", "Pulled Custom",
    )
    secrets.forEach { secret ->
        check(!text.contains(secret)) { "$label display leaks [$secret] in [$text]" }
    }
    check(text.isNotBlank()) { "$label display must be non-blank" }
}
