package com.dragosfotache.gymtracka.data.sync

import android.database.Cursor
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.store.ExtraAffectedRoot

/**
 * Literal accepted-batch fixtures and acknowledgment observations reuse durable claim/capture
 * helpers. Byte equality checks retained requests without regenerating them.
 */

// Fixed persistence literals from the acknowledgment specification (not production oracles).
internal const val ACK_CONTEXT_CORRUPT_CODE = "ack_context_corrupt"
internal const val ACK_CONTEXT_CORRUPT_MESSAGE = "Push acknowledgment context was invalid; recovery required."
internal const val ACK_MISMATCH_MESSAGE = "Accepted outcome does not match push claim"

internal const val ACK_BATCH_1 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
internal const val ACK_BATCH_2 = "11111111-2222-3333-3333-555555555555"
internal const val ACK_CURSOR_1 = "cursor-1"
internal const val ACK_CURSOR_2 = "cursor-2"

internal const val ACK_ROUTINE_A = "dddddddd-dddd-dddd-dddd-dddddddddddd"
internal const val ACK_CUSTOM_A = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
internal const val ACK_UNKNOWN_CLOUD = "dddddddd-dddd-dddd-dddd-ddddddddddde"
internal const val ACK_NOW_MILLIS = CLAIM_NOW_MILLIS

internal suspend fun GymDatabase.ackSeedFenced() {
    claimSeedDevice()
    claimExec("DELETE FROM sync_account_cache")
    claimSeedCache(CLAIM_ACCOUNT_A, 0L)
}

internal suspend fun GymDatabase.ackCaptureWorkoutHead(
    mutationId: String = CLAIM_MUTATION_1,
    rootId: String = CLAIM_WORKOUT_A,
    name: String = "Ack Workout",
    extraRoots: List<ExtraAffectedRoot> = emptyList(),
): SyncOutboxEntity {
    claimSeedWorkout(rootId, CLAIM_ACCOUNT_A, name)
    val store = claimCaptureStore(claimSession(), mutableListOf(mutationId))
    return claimCaptureStart(store, CLAIM_ACCOUNT_A, rootId, name, extraRoots)
}

internal suspend fun GymDatabase.ackCaptureRoutineHead(
    mutationId: String,
    rootId: String = ACK_ROUTINE_A,
    name: String = "Ack Routine",
): SyncOutboxEntity {
    routineDao().insertRoutine(
        RoutineEntity(
            id = rootId,
            name = name,
            isDraft = true,
            accountId = CLAIM_ACCOUNT_A,
        ),
    )
    val store = claimCaptureStore(claimSession(), mutableListOf(mutationId))
    val outcome = store.withFencedTransaction { transaction ->
        transaction.captureEdit(
            AggregateType.ROUTINE,
            rootId,
            MutationKind.ROUTINE_UPSERT,
            changed = true,
        ) {
            MutationPayload.RoutineUpsert(
                RoutineInputModel(id = rootId, name = name, isDraft = true, exercises = emptyList()),
            )
        }
    }
    checkNotNull(outcome) { "test capture must append for $rootId" }
    val capturedId = checkNotNull(outcome.request) { "test capture must carry a request" }.envelope.mutationId
    return checkNotNull(syncDao().getOutboxMutation(capturedId, CLAIM_ACCOUNT_A)) {
        "captured routine row must be readable"
    }
}

internal suspend fun GymDatabase.ackCaptureCustomHead(
    mutationId: String,
    rootId: String = ACK_CUSTOM_A,
    name: String = "Ack Custom",
): SyncOutboxEntity {
    exerciseDao().insertExercise(
        ExerciseEntity(
            id = rootId,
            name = name,
            type = "reps",
            difficultyLevel = null,
            forceType = null,
            mechanics = null,
            category = null,
            instructions = null,
            isUserCreated = true,
            accountId = CLAIM_ACCOUNT_A,
        ),
    )
    val store = claimCaptureStore(claimSession(), mutableListOf(mutationId))
    val outcome = store.withFencedTransaction { transaction ->
        transaction.captureEdit(
            AggregateType.CUSTOM_EXERCISE,
            rootId,
            MutationKind.CUSTOM_EXERCISE_UPSERT,
            changed = true,
        ) {
            MutationPayload.CustomExerciseUpsert(
                CustomExerciseInputModel(
                    id = rootId,
                    name = name,
                    primaryMuscleGroupId = null,
                    secondaryMuscleGroupIds = emptyList(),
                    equipmentId = null,
                    equipmentNumItems = null,
                    category = null,
                    difficultyLevel = null,
                    forceType = null,
                    mechanics = null,
                    instructions = null,
                ),
            )
        }
    }
    checkNotNull(outcome) { "test capture must append for $rootId" }
    val capturedId = checkNotNull(outcome.request) { "test capture must carry a request" }.envelope.mutationId
    return checkNotNull(syncDao().getOutboxMutation(capturedId, CLAIM_ACCOUNT_A)) {
        "captured custom row must be readable"
    }
}

internal suspend fun GymDatabase.ackRow(
    mutationId: String = CLAIM_MUTATION_1,
    account: String = CLAIM_ACCOUNT_A,
): SyncOutboxEntity = checkNotNull(syncDao().getOutboxMutation(mutationId, account)) {
    "ack row must be readable for $mutationId"
}

internal fun ackWorkoutChange(
    targetId: String = CLAIM_WORKOUT_A,
    targetRevision: Long = 7L,
): SyncChangeModel = SyncChangeModel(
    AggregateType.WORKOUT,
    targetId,
    ChangeOperation.UPSERT,
    targetRevision,
    CanonicalRecord.Workout(
        WorkoutRecordModel(targetId, "W", "active", CLAIM_STARTED_MILLIS, null, targetRevision, emptyList()),
    ),
)

internal fun ackRoutineChange(
    targetId: String = ACK_ROUTINE_A,
    targetRevision: Long = 7L,
): SyncChangeModel = SyncChangeModel(
    AggregateType.ROUTINE,
    targetId,
    ChangeOperation.UPSERT,
    targetRevision,
    CanonicalRecord.Routine(
        RoutineRecordModel(targetId, "R", false, targetRevision, emptyList()),
    ),
)

internal fun ackCustomChange(
    targetId: String = ACK_CUSTOM_A,
    targetRevision: Long = 7L,
): SyncChangeModel = SyncChangeModel(
    AggregateType.CUSTOM_EXERCISE,
    targetId,
    ChangeOperation.UPSERT,
    targetRevision,
    CanonicalRecord.CustomExercise(
        CustomExerciseRecordModel(
            targetId, "Custom", "reps", null, null, null, null, null,
            true, targetRevision, emptyList(), emptyList(),
        ),
    ),
)

internal fun ackDeleteChange(
    aggregateType: AggregateType = AggregateType.WORKOUT,
    targetId: String = ACK_UNKNOWN_CLOUD,
    revision: Long = 11L,
): SyncChangeModel = SyncChangeModel(aggregateType, targetId, ChangeOperation.DELETE, revision, null)

/** Shared-contract order: DELETE changes first, then the envelope target UPSERT. */
internal fun ackBatch(
    target: SyncChangeModel,
    extraDeletes: List<SyncChangeModel> = emptyList(),
    batchId: String = ACK_BATCH_1,
    cursor: String = ACK_CURSOR_1,
): SyncBatchModel = SyncBatchModel(
    batchId = batchId,
    cursor = cursor,
    changes = extraDeletes + listOf(target),
)

internal fun ackAcceptedResult(
    mutationId: String = CLAIM_MUTATION_1,
    batch: SyncBatchModel,
    status: MutationStatus = MutationStatus.ACCEPTED,
): PushResultModel = outcomeResult(mutationId, status, null, batch)

// NOTE: claim admission, handleResult and acknowledgeAccepted must share one
// withRun block (claims are run-bound capabilities). Tests below therefore
// admit, snapshot and acknowledge inside explicit withRun blocks. A saved
// accepted snapshot from a live block may be reused with a freshly admitted
// exact unchanged request in a later run; nothing here leaks closed claims.

internal fun GymDatabase.ackMeta(
    table: String,
    id: String,
): Map<String, Any?> {
    openHelper.writableDatabase.query(
        "SELECT account_id, server_revision, local_generation, sync_state, deleted_at_millis" +
            " FROM $table WHERE id = ?",
        arrayOf(id),
    ).use { cursor ->
        check(cursor.moveToFirst()) { "missing $table row $id" }
        return mapOf(
            "account_id" to if (cursor.isNull(0)) null else cursor.getString(0),
            "server_revision" to if (cursor.isNull(1)) null else cursor.getLong(1),
            "local_generation" to cursor.getLong(2),
            "sync_state" to cursor.getString(3),
            "deleted_at_millis" to if (cursor.isNull(4)) null else cursor.getLong(4),
        )
    }
}

internal fun GymDatabase.ackIds(table: String): List<String> {
    val ids = mutableListOf<String>()
    openHelper.writableDatabase.query("SELECT id FROM \"$table\" ORDER BY id").use { cursor ->
        while (cursor.moveToNext()) {
            ids += cursor.getString(0)
        }
    }
    return ids
}

internal fun GymDatabase.ackCount(sql: String): Int =
    openHelper.writableDatabase.query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

internal suspend fun GymDatabase.ackCanonicalJson(accountId: String, aggregateType: String, aggregateId: String): String? =
    syncCanonicalDao().getRoot(accountId, aggregateType, aggregateId)?.canonicalJson

internal suspend fun GymDatabase.ackCanonicalRevision(accountId: String, aggregateType: String, aggregateId: String): Long? =
    syncCanonicalDao().getRoot(accountId, aggregateType, aggregateId)?.serverRevision

internal suspend fun GymDatabase.ackWatermark(accountId: String, aggregateType: String, aggregateId: String): Long? =
    syncCanonicalDao().getRoot(accountId, aggregateType, aggregateId)?.localGenerationWatermark

internal suspend fun GymDatabase.ackProjectionDirty(accountId: String): Boolean? =
    syncCanonicalDao().getAccountCache(accountId)?.projectionDirty

internal fun GymDatabase.ackCacheRow(accountId: String): String {
    openHelper.writableDatabase.query(
        "SELECT baseline_complete, cache_epoch, projection_dirty, materialized_epoch" +
            " FROM sync_account_cache WHERE account_id = ?",
        arrayOf(accountId),
    ).use { cursor ->
        check(cursor.moveToFirst()) { "missing cache row $accountId" }
        return listOf(
            cursor.getLong(0).toString(),
            cursor.getLong(1).toString(),
            cursor.getLong(2).toString(),
            cursor.getLong(3).toString(),
        ).joinToString("|")
    }
}

internal fun GymDatabase.ackSyncState(accountId: String): String? {
    openHelper.writableDatabase.query(
        "SELECT cursor, last_success_millis FROM sync_state WHERE account_id = ?",
        arrayOf(accountId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        val cursorValue = if (cursor.getType(0) == Cursor.FIELD_TYPE_NULL) "null" else cursor.getString(0)
        val successValue = if (cursor.getType(1) == Cursor.FIELD_TYPE_NULL) {
            "null"
        } else {
            cursor.getLong(1).toString()
        }
        return "$cursorValue|$successValue"
    }
}

