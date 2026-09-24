package com.dragosfotache.gymtracka.data.gym

import android.content.Context
import java.util.UUID
import java.util.Calendar

/** Application-facing entry point for the bundled gym catalogue. */
class GymRepository(context: Context) {
    private val database = GymDatabase.getInstance(context)

    suspend fun muscleGroups() = database.muscleGroupDao().getAll()
    suspend fun muscleGroup(id: String) = database.muscleGroupDao().getById(id)
    suspend fun muscleGroupBySlug(slug: String) = database.muscleGroupDao().getBySlug(slug)

    suspend fun equipment() = database.equipmentDao().getAll()
    suspend fun equipment(id: String) = database.equipmentDao().getById(id)

    suspend fun exercises() = database.exerciseDao().getAll()
    suspend fun exerciseSummaries() = database.exerciseDao().getAllSummaries()
    suspend fun exercise(id: String) = database.exerciseDao().getById(id)
    suspend fun searchExercises(query: String) = database.exerciseDao().searchByName(query)
    suspend fun exercisesForMuscleGroup(muscleGroupId: String) =
        database.exerciseDao().getForMuscleGroup(muscleGroupId)

    suspend fun exerciseDetails(exerciseId: String) = database.exerciseDao().getDetails(exerciseId)

    /** Creates and persists an empty workout immediately, ready to be autosaved as it is edited. */
    suspend fun startEmptyWorkout(startedAtMillis: Long = System.currentTimeMillis()): WorkoutEntity {
        val workout = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            name = defaultWorkoutName(startedAtMillis),
            startedAtMillis = startedAtMillis,
        )
        database.workoutDao().insertWorkout(workout)
        return workout
    }

    suspend fun activeWorkout() = database.workoutDao().getActiveWorkout()
    suspend fun workoutDetails(workoutId: String) = database.workoutDao().getWorkoutDetails(workoutId)
    suspend fun workouts() = database.workoutDao().getAllWorkouts()
    suspend fun finishedWorkoutDetails() = database.workoutDao().getFinishedWorkoutDetails()

    suspend fun addExerciseToWorkout(
        workoutId: String,
        exerciseId: String,
        position: Int,
    ): WorkoutExerciseEntity {
        val workoutExercise = WorkoutExerciseEntity(
            id = UUID.randomUUID().toString(),
            workoutId = workoutId,
            exerciseId = exerciseId,
            position = position,
        )
        database.workoutDao().insertWorkoutExercise(workoutExercise)
        return workoutExercise
    }

    suspend fun addSetToWorkoutExercise(
        workoutExerciseId: String,
        position: Int,
        repetitions: Int? = null,
        weightKg: Double? = null,
    ): WorkoutSetEntity {
        val workoutSet = WorkoutSetEntity(
            id = UUID.randomUUID().toString(),
            workoutExerciseId = workoutExerciseId,
            position = position,
            repetitions = repetitions,
            weightKg = weightKg,
        )
        database.workoutDao().insertWorkoutSet(workoutSet)
        return workoutSet
    }

    suspend fun addExercisesToWorkout(workoutId: String, exerciseIds: Collection<String>) {
        if (exerciseIds.isEmpty()) return

        val workoutDao = database.workoutDao()
        val firstPosition = workoutDao.getWorkoutExercises(workoutId).size
        val workoutExercises = exerciseIds.mapIndexed { index, exerciseId ->
            WorkoutExerciseEntity(
                id = UUID.randomUUID().toString(),
                workoutId = workoutId,
                exerciseId = exerciseId,
                position = firstPosition + index,
            )
        }
        val workoutSets = workoutExercises.flatMap { workoutExercise ->
            val previousSets = workoutDao.previousCompletedSetsForExercise(
                workoutExercise.exerciseId,
            )
            if (previousSets.isEmpty()) {
                listOf(
                    WorkoutSetEntity(
                        id = UUID.randomUUID().toString(),
                        workoutExerciseId = workoutExercise.id,
                        position = 0,
                    ),
                )
            } else {
                previousSets.mapIndexed { position, previousSet ->
                    WorkoutSetEntity(
                        id = UUID.randomUUID().toString(),
                        workoutExerciseId = workoutExercise.id,
                        position = position,
                        repetitions = previousSet.repetitions,
                        weightKg = previousSet.weightKg,
                    )
                }
            }
        }
        workoutDao.insertExercisesWithInitialSets(workoutExercises, workoutSets)
    }

    suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity) =
        database.workoutDao().updateWorkoutSet(workoutSet)

    suspend fun updateWorkoutSetRestSeconds(workoutSetId: String, elapsedSeconds: Int) =
        database.workoutDao().updateWorkoutSetRestSeconds(workoutSetId, elapsedSeconds)

    suspend fun updateWorkoutName(workoutId: String, name: String) =
        database.workoutDao().updateWorkoutName(workoutId, name)

    suspend fun updateWorkoutExerciseNotes(workoutExerciseId: String, notes: String) =
        database.workoutDao().updateWorkoutExerciseNotes(workoutExerciseId, notes)

    /** Discarding a workout removes it and its cascaded exercises and sets. */
    suspend fun discardWorkout(workoutId: String) = database.workoutDao().deleteWorkout(workoutId)

    /**
     * Finishes a workout only if it has a completed set, discarding unfinished sets and any
     * exercises left empty by that cleanup. Returns whether the workout was finished.
     */
    suspend fun finishWorkout(
        workoutId: String,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ): Boolean = database.workoutDao().finishWorkoutAndDiscardUnfinishedSets(
        workoutId,
        finishedAtMillis,
    )

    suspend fun hasCompletedSets(workoutId: String): Boolean =
        database.workoutDao().completedSetCount(workoutId) > 0

    suspend fun deleteWorkout(workoutId: String) = database.workoutDao().deleteWorkout(workoutId)

    suspend fun deleteExerciseFromWorkout(workoutExerciseId: String, workoutId: String) =
        database.workoutDao().deleteWorkoutExerciseAndReorder(workoutExerciseId, workoutId)

    suspend fun deleteWorkoutSet(workoutSet: WorkoutSetEntity) =
        database.workoutDao().deleteWorkoutSetAndReorder(workoutSet)

    private fun defaultWorkoutName(startedAtMillis: Long): String {
        val hour = Calendar.getInstance().run {
            timeInMillis = startedAtMillis
            get(Calendar.HOUR_OF_DAY)
        }
        val timeOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
        return "$timeOfDay Workout"
    }
}
