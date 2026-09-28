package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.testing.service.FakeAccountSyncApi
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import org.junit.Assert.assertEquals

/**
 * Shared builders for the Assignment 016 independent fake-command tests.
 * Every expected record and batch in the tests is constructed by hand from
 * these builders and the specification; nothing derives expected values from
 * the service under test.
 */
object FakeSyncFixtures {
    const val ACCOUNT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    const val ACCOUNT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    const val BUNDLED_PUSH_UP = "44444444-4444-4444-4444-444444444444"
    const val BUNDLED_SQUAT = "44444444-4444-4444-4444-444444444445"
    const val MUSCLE_CHEST = "55555555-5555-5555-5555-555555555555"
    const val MUSCLE_BICEPS = "55555555-5555-5555-5555-555555555556"
    const val EQUIPMENT_DUMBBELL = "66666666-6666-6666-6666-666666666666"

    const val STARTED_AT = 1_780_000_000_000L
    const val FINISHED_AT = 1_780_008_000_000L

    /** Deterministic batch-ID UUIDs: each accepted mutation consumes two. */
    fun batchUuids(count: Int): List<String> =
        List(count) { index -> "bbbbbbbb-bbbb-bbbb-bbbb-%012x".format(index) }

    fun workoutSet(
        id: String,
        position: Int,
        repetitions: Int? = 10,
        weightKg: Double? = 60.0,
        isCompleted: Boolean = false,
        restSeconds: Int? = null,
    ) = WorkoutSetModel(id, position, repetitions, weightKg, isCompleted, restSeconds)

    fun workoutEntry(
        id: String,
        exerciseId: String,
        position: Int,
        sets: List<WorkoutSetModel>,
        notes: String = "",
        restDurationSeconds: Int = 30,
    ) = WorkoutExerciseInputModel(id, exerciseId, position, notes, restDurationSeconds, sets)

    fun workoutInput(
        id: String,
        name: String = "Morning Workout",
        startedAtMillis: Long = STARTED_AT,
        exercises: List<WorkoutExerciseInputModel> = emptyList(),
    ) = WorkoutInputModel(id, name, "active", startedAtMillis, exercises)

    fun routineEntry(
        id: String,
        exerciseId: String,
        position: Int,
        sets: List<RoutineSetModel>,
        restDurationSeconds: Int = 30,
    ) = RoutineExerciseInputModel(id, exerciseId, position, restDurationSeconds, sets)

    fun routineInput(
        id: String,
        name: String = "Strength Plan",
        isDraft: Boolean = true,
        exercises: List<RoutineExerciseInputModel> = emptyList(),
    ) = RoutineInputModel(id, name, isDraft, exercises)

    fun customInput(
        id: String,
        name: String = "Cable Fly",
        primaryMuscleGroupId: String? = MUSCLE_CHEST,
        secondaryMuscleGroupIds: List<String> = emptyList(),
        equipmentId: String? = null,
        equipmentNumItems: Int? = null,
        category: String? = null,
        difficultyLevel: String? = null,
        forceType: String? = null,
        mechanics: String? = null,
        instructions: List<String>? = null,
    ) = CustomExerciseInputModel(
        id,
        name,
        primaryMuscleGroupId,
        secondaryMuscleGroupIds,
        equipmentId,
        equipmentNumItems,
        category,
        difficultyLevel,
        forceType,
        mechanics,
        instructions,
    )
}

/** Per-test harness: seeded service, bound adapters, and request builders. */
class FakeSyncHarness(batchUuidCount: Int = 32) {
    private val batchIds = FakeSyncFixtures.batchUuids(batchUuidCount)
    private val batchQueue = ArrayDeque(batchIds)
    val service = FakeSyncService(uuidProvider = { batchQueue.removeFirst() })
    val apiA = FakeAccountSyncApi(FakeSyncFixtures.ACCOUNT_A, service)
    val apiB = FakeAccountSyncApi(FakeSyncFixtures.ACCOUNT_B, service)
    private var mutationCounter = 0

    init {
        service.seedBundledExercises(
            listOf(
                FakeSyncService.CatalogueEntry(FakeSyncFixtures.BUNDLED_PUSH_UP, "Push-Up"),
                FakeSyncService.CatalogueEntry(FakeSyncFixtures.BUNDLED_SQUAT, "Squat"),
            ),
        )
        service.seedMuscleGroups(
            listOf(
                FakeSyncService.CatalogueEntry(FakeSyncFixtures.MUSCLE_CHEST, "Chest"),
                FakeSyncService.CatalogueEntry(FakeSyncFixtures.MUSCLE_BICEPS, "Biceps"),
            ),
        )
        service.seedEquipment(
            listOf(
                FakeSyncService.CatalogueEntry(FakeSyncFixtures.EQUIPMENT_DUMBBELL, "Dumbbell"),
            ),
        )
    }

    fun nextMutationId(): String = "12345678-1234-1234-1234-%012x".format(mutationCounter++)

    private fun capture(
        kind: MutationKind,
        aggregateType: AggregateType,
        aggregateId: String,
        payload: MutationPayload,
        generation: Long = 1L,
        knownServerRevision: Long? = null,
    ): DurableRequestModel =
        captureDurableRequest(
            MutationEnvelopeModel(
                mutationId = nextMutationId(),
                kind = kind,
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                localGeneration = generation,
                knownServerRevision = knownServerRevision,
                payload = payload,
            ),
            listOf(AffectedRootModel(aggregateType, aggregateId, generation)),
        )

    fun startRequest(id: String, name: String = "Morning Workout", startedAt: Long = FakeSyncFixtures.STARTED_AT) =
        capture(
            MutationKind.WORKOUT_START,
            AggregateType.WORKOUT,
            id,
            MutationPayload.WorkoutStart(id, name, startedAt),
        )

    fun upsertRequest(
        input: WorkoutInputModel,
        generation: Long = 1L,
        knownServerRevision: Long? = null,
    ) = capture(
        MutationKind.WORKOUT_UPSERT,
        AggregateType.WORKOUT,
        input.id,
        MutationPayload.WorkoutUpsert(input),
        generation,
        knownServerRevision,
    )

    fun routineUpsertRequest(input: RoutineInputModel) =
        capture(
            MutationKind.ROUTINE_UPSERT,
            AggregateType.ROUTINE,
            input.id,
            MutationPayload.RoutineUpsert(input),
        )

    fun routineSaveRequest(routineId: String, name: String) =
        capture(
            MutationKind.ROUTINE_SAVE,
            AggregateType.ROUTINE,
            routineId,
            MutationPayload.RoutineSave(name),
        )

    fun customUpsertRequest(input: CustomExerciseInputModel) =
        capture(
            MutationKind.CUSTOM_EXERCISE_UPSERT,
            AggregateType.CUSTOM_EXERCISE,
            input.id,
            MutationPayload.CustomExerciseUpsert(input),
        )

    fun importRequest(record: ImportRecord): DurableRequestModel {
        val (type, id) = when (record) {
            is ImportRecord.Workout -> AggregateType.WORKOUT to record.input.id
            is ImportRecord.Routine -> AggregateType.ROUTINE to record.input.id
            is ImportRecord.CustomExercise -> AggregateType.CUSTOM_EXERCISE to record.input.id
        }
        return capture(MutationKind.AGGREGATE_IMPORT, type, id, MutationPayload.AggregateImport(record))
    }

    fun deleteRequest(aggregateType: AggregateType, aggregateId: String) =
        capture(
            MutationKind.AGGREGATE_DELETE,
            aggregateType,
            aggregateId,
            MutationPayload.AggregateDelete,
        )

    suspend fun pushA(vararg requests: DurableRequestModel) = apiA.push(requests.toList())

    suspend fun pushB(vararg requests: DurableRequestModel) = apiB.push(requests.toList())

    /** Hand-built expected batch for the nth accepted mutation (two UUIDs each). */
    fun expectedBatch(acceptedIndex: Int, changes: List<SyncChangeModel>): SyncBatchModel =
        SyncBatchModel(
            batchId = batchIds[acceptedIndex * 2],
            cursor = "cursor-${batchIds[acceptedIndex * 2 + 1]}",
            changes = changes,
        )

    fun acceptedBatches() = service.loggedBatches(FakeSyncFixtures.ACCOUNT_A)

    /** Snapshots observable state for exact unchanged-after-rejection checks. */
    fun captureState(
        vararg keys: FakeSyncService.RootKey,
        account: String = FakeSyncFixtures.ACCOUNT_A,
    ): CapturedState =
        CapturedState(
            service = service,
            account = account,
            keys = keys.toList(),
            records = keys.associateWith { service.inspectRecord(account, it.aggregateType, it.aggregateId) },
            revisions = keys.associateWith { service.inspectRevision(account, it.aggregateType, it.aggregateId) },
            owners = keys.associateWith { service.ownerOf(it.aggregateType, it.aggregateId) },
            tombstoned = keys.associateWith { service.isTombstoned(account, it.aggregateType, it.aggregateId) },
            batches = service.loggedBatches(account),
            active = service.activeWorkoutId(account),
        )

    class CapturedState(
        private val service: FakeSyncService,
        private val account: String,
        private val keys: List<FakeSyncService.RootKey>,
        private val records: Map<FakeSyncService.RootKey, CanonicalRecord?>,
        private val revisions: Map<FakeSyncService.RootKey, Long?>,
        private val owners: Map<FakeSyncService.RootKey, String?>,
        private val tombstoned: Map<FakeSyncService.RootKey, Boolean>,
        private val batches: List<SyncBatchModel>,
        private val active: String?,
    ) {
        fun assertUnchanged() {
            assertEquals(records, keys.associateWith { service.inspectRecord(account, it.aggregateType, it.aggregateId) })
            assertEquals(revisions, keys.associateWith { service.inspectRevision(account, it.aggregateType, it.aggregateId) })
            assertEquals(owners, keys.associateWith { service.ownerOf(it.aggregateType, it.aggregateId) })
            assertEquals(tombstoned, keys.associateWith { service.isTombstoned(account, it.aggregateType, it.aggregateId) })
            assertEquals(batches, service.loggedBatches(account))
            assertEquals(active, service.activeWorkoutId(account))
        }
    }

    companion object {
        fun assertRejectedWithCode(
            result: com.dragosfotache.gymtracka.data.sync.model.PushResultModel,
            index: Int,
            errorCode: String,
        ) {
            val outcome = result.results[index]
            assertEquals(MutationStatus.REJECTED, outcome.status)
            assertEquals(errorCode, outcome.errorCode)
            assertEquals(null, outcome.batch)
        }
    }
}
