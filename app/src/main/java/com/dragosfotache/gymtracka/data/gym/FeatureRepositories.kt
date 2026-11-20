package com.dragosfotache.gymtracka.data.gym

/**
 * Narrow projection-observation contract for projection refresh feature refresh.
 *
 * The cold stream emits the current selected-scope `materialized_epoch` initially
 * (0 when absent) then distinct selected-scope epochs only, after committed Room
 * invalidations. Canonical/baseline-only/dirty/outbox changes and other-account
 * epochs never produce a new value. The compatibility default makes old
 * hand-written fakes inert; production GymRepository MUST override it.
 */
internal interface ProjectionObservationRepository {
    fun observeProjectionEpoch(): kotlinx.coroutines.flow.Flow<Long> =
        kotlinx.coroutines.flow.flowOf(0L)
}

/** Narrow app-shell persistence boundary used by GymAppState and its JVM fake. */
internal interface GymAppStateRepository : ProjectionObservationRepository {
    suspend fun activeWorkout(): WorkoutEntity?
    suspend fun deleteCustomExercise(exerciseId: String): Boolean
    suspend fun createRoutineDraft(): RoutineEntity
    suspend fun deleteRoutine(routineId: String)
    suspend fun startEmptyWorkout(startedAtMillis: Long = System.currentTimeMillis()): WorkoutEntity
    suspend fun performWorkoutAgain(
        workoutId: String,
        startedAtMillis: Long = System.currentTimeMillis(),
    ): WorkoutEntity?
    suspend fun startWorkoutFromRoutine(
        routineId: String,
        startedAtMillis: Long = System.currentTimeMillis(),
    ): WorkoutEntity?
    suspend fun discardWorkout(workoutId: String)
    suspend fun hasCompletedSets(workoutId: String): Boolean
    suspend fun finishWorkout(
        workoutId: String,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ): Boolean
    suspend fun workoutDetails(workoutId: String): WorkoutDetails?
    suspend fun routineDetails(routineId: String): RoutineDetails?
    suspend fun exerciseDetails(exerciseId: String): ExerciseDetails?
    suspend fun updateWorkoutSetRestSeconds(workoutSetId: String, elapsedSeconds: Int)
}

/** Narrow persistence contract used by exercise state holders and their JVM fakes. */
internal interface ExerciseStateRepository : ProjectionObservationRepository {
    suspend fun muscleGroups(): List<MuscleGroupEntity>
    suspend fun equipment(): List<EquipmentEntity>
    suspend fun exerciseCategories(): List<String>
    suspend fun exerciseSummaries(): List<ExerciseSummary>
    suspend fun exerciseDetails(exerciseId: String): ExerciseDetails?
    suspend fun createCustomExercise(
        name: String,
        primaryMuscleGroupId: String?,
        secondaryMuscleGroupIds: Set<String>,
        equipmentId: String?,
        category: String?,
        instructions: String,
    ): String
    suspend fun updateCustomExercise(
        exerciseId: String,
        name: String,
        primaryMuscleGroupId: String?,
        secondaryMuscleGroupIds: Set<String>,
        equipmentId: String?,
        category: String?,
        instructions: String,
    )
    suspend fun deleteCustomExercise(exerciseId: String): Boolean
    suspend fun addExercisesToWorkout(workoutId: String, exerciseIds: Collection<String>)
    suspend fun addExercisesToRoutine(routineId: String, exerciseIds: Collection<String>)
    suspend fun replaceWorkoutExercise(workoutExerciseId: String, exerciseId: String)
}

/** Narrow persistence contract used by the active-workout feature. */
internal interface ActiveWorkoutStateRepository : ProjectionObservationRepository {
    suspend fun workoutDetails(workoutId: String): WorkoutDetails?
    suspend fun updateWorkoutName(workoutId: String, name: String)
    suspend fun updateWorkoutExerciseNotes(workoutExerciseId: String, notes: String)
    suspend fun updateWorkoutExerciseRestDuration(
        workoutExerciseId: String,
        restDurationSeconds: Int,
    )
    suspend fun addSetToWorkoutExercise(
        workoutExerciseId: String,
        position: Int,
        repetitions: Int? = null,
        weightKg: Double? = null,
    ): WorkoutSetEntity
    suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity): Boolean
    suspend fun deleteWorkoutSet(workoutSet: WorkoutSetEntity)
    suspend fun deleteExerciseFromWorkout(workoutExerciseId: String, workoutId: String)
}

/** Narrow persistence contract used by routine list/detail/editor state holders. */
internal interface RoutineStateRepository : ProjectionObservationRepository {
    suspend fun savedRoutineDetails(): List<RoutineDetails>
    suspend fun routineDetails(routineId: String): RoutineDetails?
    suspend fun saveRoutine(routineId: String, name: String)
    suspend fun updateRoutineName(routineId: String, name: String)
    suspend fun deleteRoutine(routineId: String)
    suspend fun addSetToRoutineExercise(
        routineExerciseId: String,
        position: Int,
        repetitions: Int? = null,
        weightKg: Double? = null,
    )
    suspend fun updateRoutineSet(set: RoutineSetEntity)
    suspend fun updateRoutineExerciseRestDuration(routineExerciseId: String, seconds: Int)
    suspend fun deleteRoutineExercise(routineExerciseId: String, routineId: String)
    suspend fun deleteRoutineSet(set: RoutineSetEntity)
}

/** Narrow persistence contract used by workout-history state holders. */
internal interface WorkoutHistoryStateRepository : ProjectionObservationRepository {
    suspend fun finishedWorkoutSummaries(): List<WorkoutHistorySummary>
    suspend fun workoutDetails(workoutId: String): WorkoutDetails?
    suspend fun deleteWorkout(workoutId: String)
    suspend fun saveWorkoutAsRoutine(workoutId: String, routineName: String): RoutineEntity?
}

/** Read-only history for one visible catalogue exercise in the selected account. */
internal interface ExerciseHistoryStateRepository : ProjectionObservationRepository {
    suspend fun exerciseHistory(exerciseId: String): ExerciseHistory?
}
