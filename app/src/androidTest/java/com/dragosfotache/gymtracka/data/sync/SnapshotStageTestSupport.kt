package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel

/**
 * R08a independent QA fixtures. Hand-written from docs/archive/remaining-work/R08a-task.md
 * and the coordinator's source-independent API/harness facts only.
 *
 * Every expected value below is a literal authored here; no production encoder
 * output is used as an oracle and no production source was inspected. Durable
 * seeding reuses the vetted R06a claim/capture helpers; only staging-seam
 * builders and fresh stage literals are new.
 */

// Fresh stage literals: valid lowercase UUIDs, distinct from every
// CLAIM_*/PULL_*/PD_*/ACK_*/OUTCOME_*/SYNC_* literal and from each other,
// so cross-page duplicate and foreign-collision tests use exact equality.
internal const val STAGE_EPOCH = 0L
internal const val STAGE_NOW_MILLIS = 1_790_258_700_000L

/** Deterministic 128KiB whole-string body for large-record fidelity. */
internal val STAGE_LARGE_NOTES: String = "0123456789abcdef".repeat(8192)

internal const val STAGE_ID_1 = "a0a0a0a0-1111-4111-8111-111111111111"
internal const val STAGE_ID_2 = "b0b0b0b0-2222-4222-8222-222222222222"
internal const val STAGE_ID_3 = "c0c0c0c0-3333-4333-8333-333333333333"
internal const val STAGE_ID_FOREIGN = "d0d0d0d0-4444-4444-8444-444444444444"

internal const val STAGE_LIMIT = 5
internal const val STAGE_SMALL_LIMIT = 2

internal const val STAGE_SYNC_CURSOR = "stage-sync-cursor"
internal const val STAGE_SYNC_CURSOR_2 = "stage-sync-cursor-2"
internal const val STAGE_BASELINE_1 = "stage-baseline-1"
internal const val STAGE_BASELINE_2 = "stage-baseline-2"
internal const val STAGE_TOKEN_1 = "stage-token-1"
internal const val STAGE_TOKEN_2 = "stage-token-2"
internal const val STAGE_TOKEN_3 = "stage-token-3"
internal const val STAGE_LAST_SUCCESS = 1_790_258_650_000L

internal const val STAGE_WORKOUT_1 = "e1111111-1111-4111-8111-111111111111"
internal const val STAGE_WORKOUT_2 = "e2222222-2222-4222-8222-222222222222"
internal const val STAGE_WORKOUT_3 = "e3333333-3333-4333-8333-333333333333"
internal const val STAGE_ROUTINE_1 = "e4444444-4444-4444-8444-444444444444"
internal const val STAGE_ROUTINE_2 = "e5555555-5555-4555-8555-555555555555"
internal const val STAGE_CUSTOM_1 = "e6666666-6666-4666-8666-666666666666"
internal const val STAGE_CUSTOM_2 = "e7777777-7777-4777-8777-777777777777"
internal const val STAGE_UNKNOWN = "e8888888-8888-4888-8888-888888888888"

// Fixed safe stage messages from the R08a specification (not production oracles).
internal const val STAGE_STALE_MESSAGE = "Stale snapshot stage"
internal const val STAGE_STORAGE_MESSAGE = "Snapshot stage storage operation failed"
internal const val STAGE_ACTIVE_MESSAGE = "Snapshot stage requires explicit completion or abandonment"
internal const val STAGE_INTENT_MESSAGE = "Snapshot staging requires an empty account outbox"

/**
 * Seeds device, per-account cache rows and sync-state rows WITHOUT clearing
 * other scopes first, so two-account isolation tests keep both rows. Unlike
 * [pullSeedBaseline] (which clears ALL cache rows), this preserves any
 * previously seeded scope.
 */
internal suspend fun GymDatabase.stageSeedBaseline(
    cursor: String? = STAGE_SYNC_CURSOR,
    lastSuccessMillis: Long? = STAGE_LAST_SUCCESS,
    account: String = CLAIM_ACCOUNT_A,
    cacheEpoch: Long = STAGE_EPOCH,
    baselineComplete: Boolean = true,
) {
    claimSeedDevice(account, CLAIM_GENERATION)
    claimSeedCache(account, cacheEpoch, baselineComplete = baselineComplete)
    syncDao().upsertSyncState(SyncStateEntity(account, cursor, lastSuccessMillis))
}

/** Direct test-owned initial-stage header insert with literal
 * begin-equivalent bytes. Used only where claim/checkpoint coexistence is
 * unreachable via API (begin needs an empty outbox; claimHead blocks when
 * staged) and a standard begin would retire the tokens under test. Column
 * names and the initial-stage shape are normative spec literals. */
internal fun GymDatabase.stageInsertInitialHeader(
    stageId: String,
    pageLimit: Int,
    account: String = CLAIM_ACCOUNT_A,
    generation: Long = CLAIM_GENERATION,
    cacheEpoch: Long = STAGE_EPOCH,
    cursor: String? = STAGE_SYNC_CURSOR,
    lastSuccessMillis: Long? = STAGE_LAST_SUCCESS,
    baselineComplete: Boolean = true,
) {
    claimExec(
        "INSERT INTO sync_snapshot_stages (stage_id, account_id, session_generation, cache_epoch," +
            " page_limit, source_state_present, source_cursor, source_last_success_millis," +
            " source_baseline_complete, baseline_cursor, next_page_token, phase, page_count, record_count)" +
            " VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, NULL, NULL, 'downloading', 0, 0)",
        arrayOf<Any?>(
            stageId, account, generation, cacheEpoch, pageLimit,
            cursor, lastSuccessMillis, if (baselineComplete) 1 else 0,
        ),
    )
}

/** Seeds the second scope AFTER the first, preserving both rows. */
internal suspend fun GymDatabase.stageSeedSecondScope(
    cursor: String? = STAGE_SYNC_CURSOR_2,
    lastSuccessMillis: Long? = null,
    account: String = CLAIM_ACCOUNT_B,
    cacheEpoch: Long = STAGE_EPOCH,
) {
    claimSeedCache(account, cacheEpoch, baselineComplete = true)
    syncDao().upsertSyncState(SyncStateEntity(account, cursor, lastSuccessMillis))
}

/** Seeds one pending outbox row for the selected account via the vetted capture path. */
internal suspend fun GymDatabase.stageSeedOutboxPending(
    rootId: String = CLAIM_WORKOUT_A,
    mutationId: String = CLAIM_MUTATION_1,
    account: String = CLAIM_ACCOUNT_A,
) {
    claimSeedWorkout(rootId, account)
    val store = claimCaptureStore(claimSession(account), mutableListOf(mutationId))
    claimCaptureStart(store, account, rootId)
}

/** Builds a typed snapshot page directly; caller owns the nested lists. */
internal fun stagePage(
    records: List<SyncChangeModel>,
    baselineCursor: String,
    nextPageToken: String?,
    hasMore: Boolean,
): SnapshotResultModel = SnapshotResultModel(
    records = records,
    baselineCursor = baselineCursor,
    nextPageToken = nextPageToken,
    hasMore = hasMore,
)

/** Builds a typed snapshot page with caller-mutable records for ownership tests. */
internal fun stageMutablePage(
    records: MutableList<SyncChangeModel>,
    baselineCursor: String,
    nextPageToken: String?,
    hasMore: Boolean,
): SnapshotResultModel = SnapshotResultModel(
    records = records,
    baselineCursor = baselineCursor,
    nextPageToken = nextPageToken,
    hasMore = hasMore,
)

internal fun stageWorkoutUpsert(
    id: String = STAGE_WORKOUT_1,
    name: String = "Staged Workout",
    revision: Long = 7L,
    exercises: List<WorkoutExerciseModel> = emptyList(),
): SyncChangeModel = SyncChangeModel(
    AggregateType.WORKOUT,
    id,
    ChangeOperation.UPSERT,
    revision,
    CanonicalRecord.Workout(
        WorkoutRecordModel(id, name, "active", CLAIM_STARTED_MILLIS, null, revision, exercises),
    ),
)

internal fun stageRoutineUpsert(
    id: String = STAGE_ROUTINE_1,
    name: String = "Staged Routine",
    revision: Long = 3L,
): SyncChangeModel = SyncChangeModel(
    AggregateType.ROUTINE,
    id,
    ChangeOperation.UPSERT,
    revision,
    CanonicalRecord.Routine(RoutineRecordModel(id, name, false, revision, emptyList())),
)

internal fun stageCustomUpsert(
    id: String = STAGE_CUSTOM_1,
    name: String = "Staged Custom",
    revision: Long = 5L,
    userCreated: Boolean = true,
): SyncChangeModel = SyncChangeModel(
    AggregateType.CUSTOM_EXERCISE,
    id,
    ChangeOperation.UPSERT,
    revision,
    CanonicalRecord.CustomExercise(
        CustomExerciseRecordModel(
            id, name, "reps", null, null, null, null, null,
            userCreated, revision, emptyList(), emptyList(),
        ),
    ),
)

internal fun stageDelete(
    aggregateType: AggregateType = AggregateType.WORKOUT,
    id: String = STAGE_UNKNOWN,
    revision: Long = 11L,
): SyncChangeModel = SyncChangeModel(aggregateType, id, ChangeOperation.DELETE, revision, null)

internal fun stageWorkoutEntry(
    id: String = "11111111-1111-4111-8111-111111111111",
    exerciseId: String = STAGE_CUSTOM_1,
    setId: String = "33333333-3333-4333-8333-333333333333",
    notes: String = "Keep",
): WorkoutExerciseModel = WorkoutExerciseModel(
    id = id, exerciseId = exerciseId, position = 0, notes = notes,
    restDurationSeconds = 60,
    sets = listOf(
        WorkoutSetModel(
            id = setId, position = 0, repetitions = 8, weightKg = 80.0,
            isCompleted = true, restSeconds = 90,
        ),
    ),
    exerciseName = "Press",
)

/** Read-only DAO evidence without naming production entity types. */
internal suspend fun GymDatabase.stageRowsFor(account: String) =
    listOfNotNull(syncSnapshotDao().getStageForAccount(account))

internal suspend fun GymDatabase.stageRecordRows(stageId: String) =
    syncSnapshotDao().getRecords(stageId)

internal suspend fun GymDatabase.stageTokenRows(stageId: String) =
    syncSnapshotDao().getTokens(stageId)

/**
 * Test-only snapshot of every application table EXCEPT the three staging
 * tables, for proving that staging leaves all prior-table contents
 * byte-identical. Sections follow [claimSnapshot]'s "[table]" framing.
 */
internal suspend fun GymDatabase.stagePriorSnapshot(): String {
    val excluded = setOf("sync_snapshot_stages", "sync_snapshot_records", "sync_snapshot_tokens")
    val sections = claimSnapshot().split(Regex("(?=^\\[)", RegexOption.MULTILINE))
    return sections.filter { section ->
        val name = section.lineSequence().firstOrNull()?.removePrefix("[")?.removeSuffix("]")
        name != null && name !in excluded
    }.joinToString("")
}

/** Redaction oracle: no stage/account/token/body may appear in display text. */
internal fun stageAssertRedacted(text: String, label: String) {
    val secrets = listOf(
        STAGE_ID_1, STAGE_ID_2, STAGE_ID_3, STAGE_ID_FOREIGN,
        STAGE_BASELINE_1, STAGE_BASELINE_2, STAGE_TOKEN_1, STAGE_TOKEN_2, STAGE_TOKEN_3,
        STAGE_SYNC_CURSOR, STAGE_SYNC_CURSOR_2,
        STAGE_WORKOUT_1, STAGE_WORKOUT_2, STAGE_WORKOUT_3,
        STAGE_ROUTINE_1, STAGE_ROUTINE_2, STAGE_CUSTOM_1, STAGE_CUSTOM_2,
        CLAIM_ACCOUNT_A, CLAIM_ACCOUNT_B,
        "Staged Workout", "Staged Routine", "Staged Custom",
    )
    secrets.forEach { secret ->
        check(!text.contains(secret)) { "$label display leaks [$secret] in [$text]" }
    }
    check(text.isNotBlank()) { "$label display must be non-blank" }
}
