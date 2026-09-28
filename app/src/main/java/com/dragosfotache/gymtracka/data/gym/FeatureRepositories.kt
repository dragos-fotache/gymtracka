package com.dragosfotache.gymtracka.data.gym

/** Narrow app-shell persistence boundary used by GymAppState and its JVM fake. */
internal interface GymAppStateRepository {
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
    suspend fun updateWorkoutSetRestSeconds(workoutSetId: String, elapsedSeconds: Int)
}

/** Narrow persistence contract used by exercise state holders and their JVM fakes. */
internal interface ExerciseStateRepository {
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
internal interface ActiveWorkoutStateRepository {
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
internal interface RoutineStateRepository {
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
internal interface WorkoutHistoryStateRepository {
    suspend fun finishedWorkoutSummaries(): List<WorkoutHistorySummary>
    suspend fun workoutDetails(workoutId: String): WorkoutDetails?
    suspend fun deleteWorkout(workoutId: String)
    suspend fun saveWorkoutAsRoutine(workoutId: String, routineName: String): RoutineEntity?
}
