package com.dragosfotache.gymtracka.testing

import com.dragosfotache.gymtracka.data.gym.ActiveWorkoutStateRepository
import com.dragosfotache.gymtracka.data.gym.GymAppStateRepository
import com.dragosfotache.gymtracka.data.gym.EquipmentEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseDetails
import com.dragosfotache.gymtracka.data.gym.ExerciseStateRepository
import com.dragosfotache.gymtracka.data.gym.ExerciseSummary
import com.dragosfotache.gymtracka.data.gym.MuscleGroupEntity
import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.RoutineStateRepository
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutHistoryStateRepository
import com.dragosfotache.gymtracka.data.gym.WorkoutHistorySummary
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity

internal open class FakeExerciseRepository : ExerciseStateRepository {
    var summaries: List<ExerciseSummary> = emptyList()
    var detailsProvider: suspend (String) -> ExerciseDetails? = { null }
    var muscleGroupsResult: List<MuscleGroupEntity> = emptyList()
    var equipmentResult: List<EquipmentEntity> = emptyList()
    var categoriesResult: List<String> = emptyList()
    val addedToWorkout = mutableListOf<Pair<String, Collection<String>>>()
    val addedToRoutine = mutableListOf<Pair<String, Collection<String>>>()
    val replacements = mutableListOf<Pair<String, String>>()
    val deletedExercises = mutableListOf<String>()
    val createdNames = mutableListOf<String>()
    val updatedNames = mutableListOf<Pair<String, String>>()

    override suspend fun muscleGroups() = muscleGroupsResult
    override suspend fun equipment() = equipmentResult
    override suspend fun exerciseCategories() = categoriesResult
    override suspend fun exerciseSummaries() = summaries
    override suspend fun exerciseDetails(exerciseId: String) = detailsProvider(exerciseId)

    override suspend fun createCustomExercise(
        name: String,
        primaryMuscleGroupId: String?,
        secondaryMuscleGroupIds: Set<String>,
        equipmentId: String?,
        category: String?,
        instructions: String,
    ): String {
        createdNames += name
        return "created"
    }

    override suspend fun updateCustomExercise(
        exerciseId: String,
        name: String,
        primaryMuscleGroupId: String?,
        secondaryMuscleGroupIds: Set<String>,
        equipmentId: String?,
        category: String?,
        instructions: String,
    ) {
        updatedNames += exerciseId to name
    }

    override suspend fun deleteCustomExercise(exerciseId: String): Boolean {
        deletedExercises += exerciseId
        return true
    }

    override suspend fun addExercisesToWorkout(
        workoutId: String,
        exerciseIds: Collection<String>,
    ) {
        addedToWorkout += workoutId to exerciseIds.toList()
    }

    override suspend fun addExercisesToRoutine(
        routineId: String,
        exerciseIds: Collection<String>,
    ) {
        addedToRoutine += routineId to exerciseIds.toList()
    }

    override suspend fun replaceWorkoutExercise(workoutExerciseId: String, exerciseId: String) {
        replacements += workoutExerciseId to exerciseId
    }
}

internal open class FakeActiveWorkoutRepository : ActiveWorkoutStateRepository {
    var detailsProvider: suspend (String) -> WorkoutDetails? = { null }
    var nameUpdater: suspend (String, String) -> Unit = { _, _ -> }
    val nameUpdates = mutableListOf<Pair<String, String>>()
    val noteUpdates = mutableListOf<Pair<String, String>>()
    val restUpdates = mutableListOf<Pair<String, Int>>()
    val setUpdates = mutableListOf<WorkoutSetEntity>()
    var updateSetTransitionResult = false

    override suspend fun workoutDetails(workoutId: String) = detailsProvider(workoutId)

    override suspend fun updateWorkoutName(workoutId: String, name: String) {
        nameUpdates += workoutId to name
        nameUpdater(workoutId, name)
    }

    override suspend fun updateWorkoutExerciseNotes(workoutExerciseId: String, notes: String) {
        noteUpdates += workoutExerciseId to notes
    }

    override suspend fun updateWorkoutExerciseRestDuration(
        workoutExerciseId: String,
        restDurationSeconds: Int,
    ) {
        restUpdates += workoutExerciseId to restDurationSeconds
    }

    override suspend fun addSetToWorkoutExercise(
        workoutExerciseId: String,
        position: Int,
        repetitions: Int?,
        weightKg: Double?,
    ) = WorkoutSetEntity("new-set", workoutExerciseId, position, repetitions, weightKg)

    override suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity): Boolean {
        setUpdates += workoutSet
        return updateSetTransitionResult
    }

    override suspend fun deleteWorkoutSet(workoutSet: WorkoutSetEntity) = Unit
    override suspend fun deleteExerciseFromWorkout(workoutExerciseId: String, workoutId: String) = Unit
}

internal open class FakeRoutineRepository : RoutineStateRepository {
    var detailsProvider: suspend (String) -> RoutineDetails? = { null }
    var savedDetails: List<RoutineDetails> = emptyList()
    var nameUpdater: suspend (String, String) -> Unit = { _, _ -> }
    val nameUpdates = mutableListOf<Pair<String, String>>()
    val savedNames = mutableListOf<Pair<String, String>>()
    val deletedRoutineIds = mutableListOf<String>()
    data class AddedRoutineSet(
        val routineExerciseId: String,
        val position: Int,
        val repetitions: Int?,
        val weightKg: Double?,
    )
    val addedSets = mutableListOf<AddedRoutineSet>()

    override suspend fun savedRoutineDetails() = savedDetails
    override suspend fun routineDetails(routineId: String) = detailsProvider(routineId)

    override suspend fun saveRoutine(routineId: String, name: String) {
        savedNames += routineId to name
    }

    override suspend fun updateRoutineName(routineId: String, name: String) {
        nameUpdates += routineId to name
        nameUpdater(routineId, name)
    }

    override suspend fun deleteRoutine(routineId: String) {
        deletedRoutineIds += routineId
    }

    override suspend fun addSetToRoutineExercise(
        routineExerciseId: String,
        position: Int,
        repetitions: Int?,
        weightKg: Double?,
    ) {
        addedSets += AddedRoutineSet(routineExerciseId, position, repetitions, weightKg)
    }

    override suspend fun updateRoutineSet(set: RoutineSetEntity) = Unit
    override suspend fun updateRoutineExerciseRestDuration(routineExerciseId: String, seconds: Int) = Unit
    override suspend fun deleteRoutineExercise(routineExerciseId: String, routineId: String) = Unit
    override suspend fun deleteRoutineSet(set: RoutineSetEntity) = Unit
}

internal open class FakeWorkoutHistoryRepository : WorkoutHistoryStateRepository {
    var summaries: List<WorkoutHistorySummary> = emptyList()
    var details: WorkoutDetails? = null
    var saveResult: RoutineEntity? = null
    var saveError: Throwable? = null
    val deletedWorkoutIds = mutableListOf<String>()
    val saveRequests = mutableListOf<Pair<String, String>>()

    override suspend fun finishedWorkoutSummaries() = summaries
    override suspend fun workoutDetails(workoutId: String) = details

    override suspend fun deleteWorkout(workoutId: String) {
        deletedWorkoutIds += workoutId
    }

    override suspend fun saveWorkoutAsRoutine(workoutId: String, routineName: String): RoutineEntity? {
        saveRequests += workoutId to routineName
        saveError?.let { throw it }
        return saveResult
    }
}

internal class FakeGymAppStateRepository : GymAppStateRepository {
    var active: WorkoutEntity? = null
    var details: WorkoutDetails? = null
    var completedSets = true
    var finishResult = true
    var startEmptyResult = WorkoutEntity("empty", "Empty", 0L)
    var performAgainResult: WorkoutEntity? = WorkoutEntity("clone", "Clone", 0L)
    var startRoutineResult: WorkoutEntity? = WorkoutEntity("routine-start", "Routine", 0L)
    var activeWorkoutError: Throwable? = null
    var startError: Throwable? = null
    var performAgainError: Throwable? = null
    var routineStartError: Throwable? = null
    var discardError: Throwable? = null
    var finishError: Throwable? = null
    var hasCompletedSetsError: Throwable? = null
    var restUpdate: suspend (String, Int) -> Unit = { _, _ -> }
    val startedEmptyAt = mutableListOf<Long>()
    val performedAgainAt = mutableListOf<Pair<String, Long>>()
    val startedRoutineAt = mutableListOf<Pair<String, Long>>()
    val discarded = mutableListOf<String>()
    val finished = mutableListOf<Pair<String, Long>>()
    val restUpdates = mutableListOf<Pair<String, Int>>()
    val committedRestUpdates = mutableListOf<Pair<String, Int>>()

    override suspend fun deleteCustomExercise(exerciseId: String) = true
    override suspend fun createRoutineDraft() = RoutineEntity("draft", "", isDraft = true)
    override suspend fun deleteRoutine(routineId: String) = Unit

    override suspend fun activeWorkout(): WorkoutEntity? {
        activeWorkoutError?.let { throw it }
        return active
    }

    override suspend fun startEmptyWorkout(startedAtMillis: Long): WorkoutEntity {
        startError?.let { throw it }
        startedEmptyAt += startedAtMillis
        val created = startEmptyResult.copy(startedAtMillis = startedAtMillis)
        active = created
        return created
    }

    override suspend fun performWorkoutAgain(workoutId: String, startedAtMillis: Long): WorkoutEntity? {
        performAgainError?.let { throw it }
        performedAgainAt += workoutId to startedAtMillis
        val created = performAgainResult?.copy(startedAtMillis = startedAtMillis)
        created?.let { active = it }
        return created
    }

    override suspend fun startWorkoutFromRoutine(routineId: String, startedAtMillis: Long): WorkoutEntity? {
        routineStartError?.let { throw it }
        startedRoutineAt += routineId to startedAtMillis
        val created = startRoutineResult?.copy(startedAtMillis = startedAtMillis)
        created?.let { active = it }
        return created
    }

    override suspend fun discardWorkout(workoutId: String) {
        discardError?.let { throw it }
        discarded += workoutId
    }

    override suspend fun hasCompletedSets(workoutId: String): Boolean {
        hasCompletedSetsError?.let { throw it }
        return completedSets
    }

    override suspend fun finishWorkout(workoutId: String, finishedAtMillis: Long): Boolean {
        finishError?.let { throw it }
        finished += workoutId to finishedAtMillis
        return finishResult
    }

    override suspend fun workoutDetails(workoutId: String) = details

    override suspend fun updateWorkoutSetRestSeconds(workoutSetId: String, elapsedSeconds: Int) {
        val update = workoutSetId to elapsedSeconds
        restUpdates += update
        restUpdate(workoutSetId, elapsedSeconds)
        committedRestUpdates += update
    }
}
