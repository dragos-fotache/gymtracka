package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ChildExerciseBinding
import com.dragosfotache.gymtracka.data.sync.model.ChildSetBinding
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.ExerciseAddition
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import java.util.UUID

/**
 * Test-only durable-request builders for real-backend integration tests.
 *
 * Migrated from the retired-harness builders: every request goes through the
 * production [captureDurableRequest], so the exact frozen bytes the future
 * engine will send are what these tests push. Catalogue references always use
 * caller-supplied real seed IDs resolved from the isolated backend — no
 * invented bundled/muscle/equipment UUIDs appear here. All other IDs are fresh
 * random UUIDs per call site via [uid].
 */
object RealBackendRequests {
    fun uid(): String = UUID.randomUUID().toString().lowercase()

    fun mutationId(counter: Int): String = "12345678-1234-1234-1234-%012x".format(counter)

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
        startedAtMillis: Long = 1_780_000_000_000L,
        exercises: List<WorkoutExerciseInputModel> = emptyList(),
    ) = WorkoutInputModel(id, name, "active", startedAtMillis, exercises)

    fun routineSet(id: String, position: Int, repetitions: Int? = 10, weightKg: Double? = 60.0) =
        RoutineSetModel(id, position, repetitions, weightKg)

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
        primaryMuscleGroupId: String?,
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

    fun childBinding(sourceExerciseId: String, id: String, setBindings: List<Pair<String, String>>) =
        ChildExerciseBinding(sourceExerciseId, id, setBindings.map { ChildSetBinding(it.first, it.second) })

    private fun capture(
        mutationId: String,
        kind: MutationKind,
        aggregateType: AggregateType,
        aggregateId: String,
        payload: MutationPayload,
        generation: Long = 1L,
        knownServerRevision: Long? = null,
    ): DurableRequestModel =
        captureDurableRequest(
            MutationEnvelopeModel(
                mutationId = mutationId,
                kind = kind,
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                localGeneration = generation,
                knownServerRevision = knownServerRevision,
                payload = payload,
            ),
            listOf(AffectedRootModel(aggregateType, aggregateId, generation)),
        )

    fun startRequest(mutationId: String, id: String, name: String = "Morning Workout", startedAt: Long = 1_780_000_000_000L) =
        capture(mutationId, MutationKind.WORKOUT_START, AggregateType.WORKOUT, id, MutationPayload.WorkoutStart(id, name, startedAt))

    fun upsertRequest(mutationId: String, input: WorkoutInputModel, generation: Long = 1L, knownServerRevision: Long? = null) =
        capture(mutationId, MutationKind.WORKOUT_UPSERT, AggregateType.WORKOUT, input.id, MutationPayload.WorkoutUpsert(input), generation, knownServerRevision)

    fun finishRequest(mutationId: String, workoutId: String, finishedAt: Long = 1_780_008_000_000L, generation: Long = 1L, knownServerRevision: Long? = null) =
        capture(mutationId, MutationKind.WORKOUT_FINISH, AggregateType.WORKOUT, workoutId, MutationPayload.WorkoutFinish(finishedAt), generation, knownServerRevision)

    fun performAgainRequest(mutationId: String, sourceWorkoutId: String, id: String, startedAt: Long, childIds: List<ChildExerciseBinding>) =
        capture(mutationId, MutationKind.WORKOUT_PERFORM_AGAIN, AggregateType.WORKOUT, id, MutationPayload.WorkoutPerformAgain(sourceWorkoutId, id, startedAt, childIds))

    fun startWorkoutRequest(mutationId: String, sourceRoutineId: String, id: String, startedAt: Long, childIds: List<ChildExerciseBinding>) =
        capture(mutationId, MutationKind.ROUTINE_START_WORKOUT, AggregateType.WORKOUT, id, MutationPayload.RoutineStartWorkout(sourceRoutineId, id, startedAt, childIds))

    fun saveAsRoutineRequest(mutationId: String, sourceWorkoutId: String, id: String, name: String, childIds: List<ChildExerciseBinding>) =
        capture(mutationId, MutationKind.WORKOUT_SAVE_AS_ROUTINE, AggregateType.ROUTINE, id, MutationPayload.WorkoutSaveAsRoutine(sourceWorkoutId, id, name, childIds))

    fun addExercisesRequest(mutationId: String, workoutId: String, additions: List<ExerciseAddition>) =
        capture(mutationId, MutationKind.WORKOUT_ADD_EXERCISES, AggregateType.WORKOUT, workoutId, MutationPayload.WorkoutAddExercises(additions))

    fun replaceExerciseRequest(mutationId: String, workoutId: String, workoutExerciseId: String, exerciseId: String) =
        capture(mutationId, MutationKind.WORKOUT_REPLACE_EXERCISE, AggregateType.WORKOUT, workoutId, MutationPayload.WorkoutReplaceExercise(workoutExerciseId, exerciseId))

    fun deleteWorkoutExerciseRequest(mutationId: String, workoutId: String, workoutExerciseId: String) =
        capture(mutationId, MutationKind.WORKOUT_DELETE_EXERCISE, AggregateType.WORKOUT, workoutId, MutationPayload.WorkoutDeleteExercise(workoutExerciseId))

    fun deleteWorkoutSetRequest(mutationId: String, workoutId: String, workoutSetId: String) =
        capture(mutationId, MutationKind.WORKOUT_DELETE_SET, AggregateType.WORKOUT, workoutId, MutationPayload.WorkoutDeleteSet(workoutSetId))

    fun routineUpsertRequest(mutationId: String, input: RoutineInputModel) =
        capture(mutationId, MutationKind.ROUTINE_UPSERT, AggregateType.ROUTINE, input.id, MutationPayload.RoutineUpsert(input))

    fun routineSaveRequest(mutationId: String, routineId: String, name: String) =
        capture(mutationId, MutationKind.ROUTINE_SAVE, AggregateType.ROUTINE, routineId, MutationPayload.RoutineSave(name))

    fun deleteRoutineExerciseRequest(mutationId: String, routineId: String, routineExerciseId: String) =
        capture(mutationId, MutationKind.ROUTINE_DELETE_EXERCISE, AggregateType.ROUTINE, routineId, MutationPayload.RoutineDeleteExercise(routineExerciseId))

    fun deleteRoutineSetRequest(mutationId: String, routineId: String, routineSetId: String) =
        capture(mutationId, MutationKind.ROUTINE_DELETE_SET, AggregateType.ROUTINE, routineId, MutationPayload.RoutineDeleteSet(routineSetId))

    fun customUpsertRequest(mutationId: String, input: CustomExerciseInputModel) =
        capture(mutationId, MutationKind.CUSTOM_EXERCISE_UPSERT, AggregateType.CUSTOM_EXERCISE, input.id, MutationPayload.CustomExerciseUpsert(input))

    fun importRequest(mutationId: String, record: ImportRecord) =
        capture(
            mutationId,
            MutationKind.AGGREGATE_IMPORT,
            when (record) {
                is ImportRecord.Workout -> AggregateType.WORKOUT
                is ImportRecord.Routine -> AggregateType.ROUTINE
                is ImportRecord.CustomExercise -> AggregateType.CUSTOM_EXERCISE
            },
            record.aggregateId,
            MutationPayload.AggregateImport(record),
        )

    fun deleteRequest(mutationId: String, aggregateType: AggregateType, aggregateId: String) =
        capture(mutationId, MutationKind.AGGREGATE_DELETE, aggregateType, aggregateId, MutationPayload.AggregateDelete)

    fun workoutImportRecord(
        id: String,
        name: String = "Imported Workout",
        status: String = "active",
        startedAt: Long = 1_780_000_000_000L,
        finishedAt: Long? = null,
        exercises: List<WorkoutExerciseInputModel> = emptyList(),
    ) = ImportRecord.Workout(WorkoutImportModel(id, name, status, startedAt, finishedAt, exercises))
}
