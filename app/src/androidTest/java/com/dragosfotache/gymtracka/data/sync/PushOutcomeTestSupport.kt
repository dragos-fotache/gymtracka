package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationOutcomeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.store.PushClaim
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult

/**
 * Literal outcome/batch fixtures and fixed safe-message expectations reuse durable claim/capture
 * helpers.
 */

// Fixed persistence literals from the push outcomes specification (not production oracles).
internal const val OUTCOME_REJECTED_MESSAGE = "Push mutation was rejected; recovery required."
internal const val OUTCOME_MALFORMED_MESSAGE = "Push response was invalid; recovery required."
internal const val OUTCOME_TOO_LARGE_MESSAGE = "Push request was too large; recovery required."
internal const val OUTCOME_REQUEST_REJECTED_MESSAGE = "Push request was rejected; recovery required."
internal const val OUTCOME_RETRY_EXHAUSTED_MESSAGE = "Push retry time was exhausted; recovery required."

internal const val OUTCOME_MALFORMED_CODE = "malformed_response"
internal const val OUTCOME_TOO_LARGE_CODE = "request_too_large"
internal const val OUTCOME_RETRY_EXHAUSTED_CODE = "retry_time_exhausted"

internal const val OUTCOME_BATCH_1 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
internal const val OUTCOME_CURSOR_1 = "cursor-1"
internal const val OUTCOME_UNKNOWN_ROOT = "dddddddd-dddd-dddd-dddd-dddddddddddd"

internal const val OUTCOME_NOW_MILLIS = CLAIM_NOW_MILLIS

internal suspend fun GymDatabase.outcomeSeedFenced() {
    claimSeedDevice()
    claimExec("DELETE FROM sync_account_cache")
    claimSeedCache(CLAIM_ACCOUNT_A, 0L)
}

internal suspend fun GymDatabase.outcomeCaptureHead(
    mutationId: String = CLAIM_MUTATION_1,
    rootId: String = CLAIM_WORKOUT_A,
): SyncOutboxEntity {
    claimSeedWorkout(rootId, CLAIM_ACCOUNT_A)
    val store = claimCaptureStore(claimSession(), mutableListOf(mutationId))
    return claimCaptureStart(store, CLAIM_ACCOUNT_A, rootId)
}

internal suspend fun GymDatabase.outcomeFreshClaim(
    epoch: Long = 0L,
    nowMillis: Long = OUTCOME_NOW_MILLIS,
): PushClaim {
    var claimed: PushClaim? = null
    claimPushStore().withRun(epoch) { run ->
        claimed = (run.claimHead(nowMillis) as PushHeadResult.Claimed).claim
    }
    return claimed!!
}

internal suspend fun GymDatabase.outcomeRow(
    mutationId: String = CLAIM_MUTATION_1,
    account: String = CLAIM_ACCOUNT_A,
): SyncOutboxEntity = checkNotNull(syncDao().getOutboxMutation(mutationId, account)) {
    "outcome row must be readable for $mutationId"
}

/** Minimal valid workout record for accepted batches (empty exercises are valid). */
internal fun outcomeWorkoutRecord(
    id: String = CLAIM_WORKOUT_A,
    revision: Long = 7L,
): CanonicalRecord.Workout = CanonicalRecord.Workout(
    WorkoutRecordModel(id, "W", "active", CLAIM_STARTED_MILLIS, null, revision, emptyList()),
)

/**
 * Valid accepted batch for a non-delete claim: shared-contract order of DELETE
 * for the old unknown root first, then UPSERT for the envelope target.
 */
internal fun outcomeBatch(
    targetId: String = CLAIM_WORKOUT_A,
    targetRevision: Long = 7L,
    extraDeletes: List<String> = emptyList(),
    batchId: String = OUTCOME_BATCH_1,
    cursor: String = OUTCOME_CURSOR_1,
): SyncBatchModel = SyncBatchModel(
    batchId = batchId,
    cursor = cursor,
    changes = extraDeletes.map { unknown ->
        SyncChangeModel(AggregateType.WORKOUT, unknown, ChangeOperation.DELETE, 11L, null)
    } + listOf(
        SyncChangeModel(
            AggregateType.WORKOUT,
            targetId,
            ChangeOperation.UPSERT,
            targetRevision,
            outcomeWorkoutRecord(id = targetId, revision = targetRevision),
        ),
    ),
)

internal fun outcomeResult(
    mutationId: String = CLAIM_MUTATION_1,
    status: MutationStatus,
    errorCode: String? = null,
    batch: SyncBatchModel? = null,
): PushResultModel = PushResultModel(
    results = listOf(
        MutationOutcomeModel(
            mutationId = mutationId,
            status = status,
            errorCode = errorCode,
            batch = batch,
        ),
    ),
)

internal fun outcomeAcceptedResult(
    mutationId: String = CLAIM_MUTATION_1,
    batch: SyncBatchModel = outcomeBatch(),
): PushResultModel = outcomeResult(mutationId, MutationStatus.ACCEPTED, null, batch)

internal fun outcomeRejectedResult(
    mutationId: String = CLAIM_MUTATION_1,
    errorCode: String,
): PushResultModel = outcomeResult(mutationId, MutationStatus.REJECTED, errorCode, null)
