package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadResult
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.SnapshotBaselineResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * Literal baseline fixtures reuse whole-page staging, claim/capture and publication helpers. Same-
 * run download/completion helpers retain the driver's actual issued capabilities.
 */

// Fresh baseline literals: valid lowercase UUIDs, distinct from every
// CLAIM_*/PULL_*/PD_*/ACK_*/OUTCOME_*/SYNC_*/STAGE_*/DELIVERY_*/CANONICAL_*/PROJECTION_*
// literal and from each other, so cross-scope and collision tests use exact equality.
internal const val BASE_EPOCH = 0L
internal const val BASE_NOW_MILLIS = 1_790_258_750_000L
internal const val BASE_LAST_SUCCESS = 1_790_258_700_000L
internal const val BASE_PAGE_LIMIT = 5

internal const val BASE_WORKOUT_1 = "b1111111-1111-4111-8111-111111111111"
internal const val BASE_WORKOUT_2 = "b2222222-2222-4222-8222-222222222222"
internal const val BASE_ROUTINE_1 = "b3333333-3333-4333-8333-333333333333"
internal const val BASE_ROUTINE_2 = "b4444444-4444-4444-8444-444444444444"
internal const val BASE_CUSTOM_1 = "b5555555-5555-4555-8555-555555555555"
internal const val BASE_CUSTOM_2 = "b6666666-6666-4666-8666-666666666666"
internal const val BASE_UNKNOWN = "b7777777-7777-4777-8777-777777777777"
internal const val BASE_LOCAL_NEW = "b8888888-8888-4888-8888-888888888888"
internal const val BASE_ENTRY_1 = "b9999999-9999-4999-8999-999999999999"
internal const val BASE_ENTRY_2 = "baaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
internal const val BASE_SET_1 = "babababa-baba-4aba-8aba-abababababab"
internal const val BASE_SET_2 = "bcbcbcbc-bcbc-4cbc-8cbc-bcbcbcbcbcbc"

internal const val BASE_STAGE_1 = "bb0a0a0a-1111-4111-8111-111111111111"
internal const val BASE_STAGE_2 = "bb0b0b0b-2222-4222-8222-222222222222"
internal const val BASE_STAGE_3 = "bb0c0c0c-3333-4333-8333-333333333333"
internal const val BASE_STAGE_4 = "bb0d0d0d-4444-4444-8444-444444444444"

internal const val BASE_BASELINE_1 = "base-baseline-1"
internal const val BASE_BASELINE_2 = "base-baseline-2"
internal const val BASE_TOKEN_1 = "base-token-1"

internal const val BASE_MUTATION_1 = "c1111111-1111-4111-8111-111111111111"
internal const val BASE_MUTATION_2 = "c2222222-2222-4222-8222-222222222222"

internal const val BASE_MUSCLE_UNKNOWN = "bccccccc-cccc-4ccc-8ccc-cccccccccccc"
internal const val BASE_EQUIPMENT_UNKNOWN = "bddddddd-dddd-4ddd-8ddd-dddddddddddd"
internal const val BASE_FINISHED_MILLIS = 1_790_258_500_000L

internal const val BASE_MUSCLE_SEED = "be000001-0000-4000-8000-000000000001"
internal const val BASE_EQUIPMENT_SEED = "be000002-0000-4000-8000-000000000002"
internal const val BASE_BUNDLED_SEED = "be000003-0000-4000-8000-000000000003"

/** Staged workout entry with caller-visible convenience label, mirroring [stageWorkoutEntry] shape. */
internal fun baseWorkoutEntry(
    id: String = BASE_ENTRY_1,
    exerciseId: String = BASE_CUSTOM_1,
    setId: String = BASE_SET_1,
    exerciseName: String = "Base Press",
    notes: String = "Base keep",
): WorkoutExerciseModel = WorkoutExerciseModel(
    id = id, exerciseId = exerciseId, position = 0, notes = notes,
    restDurationSeconds = 60,
    sets = listOf(
        WorkoutSetModel(
            id = setId, position = 0, repetitions = 8, weightKg = 80.0,
            isCompleted = true, restSeconds = 90,
        ),
    ),
    exerciseName = exerciseName,
)

/** Staged draft-routine upsert, mirroring [stageRoutineUpsert] with draft status set. */
internal fun baseDraftRoutineUpsert(
    id: String = BASE_ROUTINE_2,
    name: String = "Base Draft",
    revision: Long = 1L,
): SyncChangeModel = SyncChangeModel(
    AggregateType.ROUTINE,
    id,
    ChangeOperation.UPSERT,
    revision,
    CanonicalRecord.Routine(RoutineRecordModel(id, name, true, revision, emptyList())),
)

/** Staged finished-workout upsert with completed sets, isolating family violations from multiple-active. */
internal fun baseFinishedWorkoutUpsert(
    id: String = BASE_WORKOUT_2,
    name: String = "Base Finished",
    revision: Long = 9L,
    exercises: List<WorkoutExerciseModel> = listOf(baseWorkoutEntry()),
): SyncChangeModel = SyncChangeModel(
    AggregateType.WORKOUT,
    id,
    ChangeOperation.UPSERT,
    revision,
    CanonicalRecord.Workout(
        WorkoutRecordModel(id, name, "finished", CLAIM_STARTED_MILLIS, BASE_FINISHED_MILLIS, revision, exercises),
    ),
)

/** Staged custom upsert with explicit global relationship bindings, mirroring [stageCustomUpsert] shape. */
internal fun baseCustomUpsertWithRels(
    id: String = BASE_CUSTOM_2,
    name: String = "Ghost Custom",
    revision: Long = 2L,
    muscles: List<MuscleGroupRefModel> = emptyList(),
    equipment: List<EquipmentRefModel> = emptyList(),
): SyncChangeModel = SyncChangeModel(
    AggregateType.CUSTOM_EXERCISE,
    id,
    ChangeOperation.UPSERT,
    revision,
    CanonicalRecord.CustomExercise(
        CustomExerciseRecordModel(
            id, name, "reps", null, null, null, null, null,
            true, revision, muscles, equipment,
        ),
    ),
)

/** Empty terminal snapshot page: a valid complete empty account graph. */
internal fun baseEmptyTerminalPage(baselineCursor: String): SnapshotResultModel =
    stagePage(emptyList(), baselineCursor, null, false)

/**
 * Admits one run at [epoch] on a store sharing [owner]/[session] and invokes
 * [block] with the live run and a loop bound to [api]. The caller owns the
 * scripted transport lifetime; start/continue/complete/publication sequences
 * that must share ONE uninterrupted run belong inside a single [block].
 */
internal suspend fun <T> GymDatabase.baseWithLoop(
    owner: PushRunOwner,
    api: GymSyncApi,
    epoch: Long = BASE_EPOCH,
    session: RepositorySession = claimSession(),
    block: suspend (PushRun, SnapshotDeliveryLoop) -> T,
): T {
    val store = claimPushStore(session, owner)
    val loop = SnapshotDeliveryLoop(store, api) { BASE_NOW_MILLIS }
    return store.withRun(epoch) { run -> block(run, loop) }
}

/**
 * Drives one uninterrupted run: fenced push Empty authorizes begin, all [pages]
 * append, then the actually issued terminal Downloaded result completes the
 * canonical baseline on the SAME run. Returns the download and baseline results.
 */
internal suspend fun GymDatabase.baseDownloadAndComplete(
    owner: PushRunOwner,
    stageId: String,
    pages: List<SnapshotScriptResponse>,
    pageLimit: Int = BASE_PAGE_LIMIT,
    epoch: Long = BASE_EPOCH,
    maxPages: Int = 100,
    session: RepositorySession = claimSession(),
): Pair<SnapshotDownloadResult, SnapshotBaselineResult> {
    val api = SnapshotDeliveryScriptApi(this, mutableListOf(), pages.toMutableList())
    return baseWithLoop(owner, api, epoch, session) { run, loop ->
        val started = loop.start(run, stageId, maxPages = maxPages, pageLimit = pageLimit)
        val download = checkNotNull(started.download) { "fenced push Empty must authorize download" }
        download to loop.complete(run, download)
    }
}

/** Explicit valid-stage abandon followed by run closure, the only recovery path for a retained stage. */
internal suspend fun GymDatabase.baseAbandonObserved(
    owner: PushRunOwner,
    epoch: Long = BASE_EPOCH,
    session: RepositorySession = claimSession(),
) {
    val api = SnapshotDeliveryScriptApi(this, mutableListOf(), mutableListOf())
    baseWithLoop(owner, api, epoch, session) { run, _ ->
        run.abandonSnapshotStage(checkNotNull(run.readSnapshotStage()) { "a stage must be observable" })
    }
}

/** Raw canonical-ledger field read; column names follow the vetted canonical batches seeding contract. */
internal fun GymDatabase.baseCanonicalField(
    account: String,
    aggregateType: String,
    aggregateId: String,
    column: String,
): Any? = projectionQuery(
    "SELECT $column FROM sync_canonical_roots WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
    arrayOf(account, aggregateType, aggregateId),
).singleOrNull()?.get(column)

internal fun GymDatabase.baseCanonicalState(account: String, aggregateType: String, aggregateId: String): String? =
    baseCanonicalField(account, aggregateType, aggregateId, "canonical_state") as String?

internal fun GymDatabase.baseCanonicalRevision(account: String, aggregateType: String, aggregateId: String): Long? =
    baseCanonicalField(account, aggregateType, aggregateId, "server_revision") as Long?

internal fun GymDatabase.baseCanonicalJson(account: String, aggregateType: String, aggregateId: String): String? =
    baseCanonicalField(account, aggregateType, aggregateId, "canonical_json") as String?

internal fun GymDatabase.baseWatermark(account: String, aggregateType: String, aggregateId: String): Long? =
    baseCanonicalField(account, aggregateType, aggregateId, "local_generation_watermark") as Long?

/**
 * Checks rejection without depending on a particular exception subtype. Cancellation is rethrown
 * unchanged; callers separately compare complete durable state to prove no mutation.
 */
internal suspend fun expectBaselineReject(label: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        return
    }
    fail("$label must reject, but it succeeded")
}

/** Whole visible-domain oracle: every domain table whose rows publication may touch, and nothing else.
 * Canonical/cache/checkpoint/staging rows are intentionally excluded; their
 * distinct assertions stay alongside this oracle, never folded into it. */
internal fun GymDatabase.baseDomainSnapshot(): Map<String, List<String>> =
    listOf(
        "workouts", "workout_exercises", "workout_sets",
        "routines", "routine_exercises", "routine_sets",
        "exercises", "exercise_muscle_groups", "exercise_equipment",
        "muscle_groups", "equipment",
    ).associateWith { projectionDump(it) }

/** Redaction oracle: no baseline/account/token/body literal may appear in display text. */
internal fun baseAssertRedacted(text: String, label: String) {
    val secrets = listOf(
        BASE_WORKOUT_1, BASE_WORKOUT_2, BASE_ROUTINE_1, BASE_ROUTINE_2,
        BASE_CUSTOM_1, BASE_CUSTOM_2, BASE_UNKNOWN, BASE_LOCAL_NEW,
        BASE_ENTRY_1, BASE_ENTRY_2, BASE_SET_1, BASE_SET_2,
        BASE_STAGE_1, BASE_STAGE_2, BASE_STAGE_3, BASE_STAGE_4,
        BASE_BASELINE_1, BASE_BASELINE_2, BASE_TOKEN_1,
        BASE_MUTATION_1, BASE_MUTATION_2,
        BASE_MUSCLE_UNKNOWN, BASE_EQUIPMENT_UNKNOWN,
        BASE_MUSCLE_SEED, BASE_EQUIPMENT_SEED, BASE_BUNDLED_SEED,
        "Base Press", "Renamed Press", "Ghost Muscle", "Ghost Gear",
        STAGE_SYNC_CURSOR, STAGE_SYNC_CURSOR_2,
        CLAIM_ACCOUNT_A, CLAIM_ACCOUNT_B,
        "Base Press", "Base Draft", "Base keep",
        "Staged Workout", "Staged Routine", "Staged Custom",
    )
    secrets.forEach { secret ->
        check(!text.contains(secret)) { "$label display leaks [$secret] in [$text]" }
    }
    check(text.isNotBlank()) { "$label display must be non-blank" }
}

/** The committed baseline result carries durable totals and the exact frozen baselineCursor. */
internal fun assertBaselineResult(
    baseline: SnapshotBaselineResult,
    cacheEpoch: Long,
    baselineCursor: String,
    pageCount: Long,
    recordCount: Long,
    hasOutstandingLocalWork: Boolean,
) {
    assertEquals("SnapshotBaselineResult", baseline.toString())
    assertEquals(cacheEpoch, baseline.cacheEpoch)
    assertEquals(baselineCursor, baseline.baselineCursor)
    assertEquals(pageCount, baseline.pageCount)
    assertEquals(recordCount, baseline.recordCount)
    assertEquals(hasOutstandingLocalWork, baseline.hasOutstandingLocalWork)
}
