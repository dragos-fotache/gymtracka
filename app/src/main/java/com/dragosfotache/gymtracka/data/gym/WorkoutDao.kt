package com.dragosfotache.gymtracka.data.gym

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkout(workout: WorkoutEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutSet(workoutSet: WorkoutSetEntity)

    @Transaction
    suspend fun insertExercisesWithInitialSets(
        workoutExercises: List<WorkoutExerciseEntity>,
        workoutSets: List<WorkoutSetEntity>,
    ) {
        insertWorkoutExercises(workoutExercises)
        insertWorkoutSets(workoutSets)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutExercises(workoutExercises: List<WorkoutExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutSets(workoutSets: List<WorkoutSetEntity>)

    @Update
    suspend fun updateWorkout(workout: WorkoutEntity)

    @Query("UPDATE workouts SET name = :name WHERE id = :workoutId")
    suspend fun updateWorkoutName(workoutId: String, name: String)

    @Query(
        "UPDATE workouts SET status = 'finished', finished_at_millis = :finishedAtMillis WHERE id = :workoutId",
    )
    suspend fun finishWorkout(workoutId: String, finishedAtMillis: Long)

    @Query(
        """SELECT COUNT(*) FROM workout_sets
            INNER JOIN workout_exercises ON workout_exercises.id = workout_sets.workout_exercise_id
            WHERE workout_exercises.workout_id = :workoutId AND workout_sets.is_completed = 1""",
    )
    suspend fun completedSetCount(workoutId: String): Int

    @Query(
        """DELETE FROM workout_sets
            WHERE is_completed = 0
            AND workout_exercise_id IN (
                SELECT id FROM workout_exercises WHERE workout_id = :workoutId
            )""",
    )
    suspend fun deleteUnfinishedWorkoutSets(workoutId: String)

    @Query(
        """DELETE FROM workout_exercises
            WHERE workout_id = :workoutId
            AND NOT EXISTS (
                SELECT 1 FROM workout_sets
                WHERE workout_sets.workout_exercise_id = workout_exercises.id
            )""",
    )
    suspend fun deleteEmptyWorkoutExercises(workoutId: String)

    /**
     * A workout is only finishable once it contains a completed set. Its unfinished draft data
     * is removed before it becomes history, leaving only completed sets and their exercises.
     */
    @Transaction
    suspend fun finishWorkoutAndDiscardUnfinishedSets(
        workoutId: String,
        finishedAtMillis: Long,
    ): Boolean {
        if (completedSetCount(workoutId) == 0) return false

        deleteUnfinishedWorkoutSets(workoutId)
        deleteEmptyWorkoutExercises(workoutId)
        getWorkoutExercises(workoutId).forEachIndexed { position, workoutExercise ->
            if (workoutExercise.position != position) {
                updateWorkoutExercisePosition(workoutExercise.id, position)
            }
        }
        finishWorkout(workoutId, finishedAtMillis)
        return true
    }

    @Query("DELETE FROM workouts WHERE id = :workoutId")
    suspend fun deleteWorkout(workoutId: String)

    @Query("DELETE FROM workout_exercises WHERE id = :workoutExerciseId")
    suspend fun deleteWorkoutExercise(workoutExerciseId: String)

    @Query("UPDATE workout_exercises SET position = :position WHERE id = :workoutExerciseId")
    suspend fun updateWorkoutExercisePosition(workoutExerciseId: String, position: Int)

    @Query("UPDATE workout_exercises SET notes = :notes WHERE id = :workoutExerciseId")
    suspend fun updateWorkoutExerciseNotes(workoutExerciseId: String, notes: String)

    @Transaction
    suspend fun deleteWorkoutExerciseAndReorder(workoutExerciseId: String, workoutId: String) {
        deleteWorkoutExercise(workoutExerciseId)
        getWorkoutExercises(workoutId).forEachIndexed { position, workoutExercise ->
            if (workoutExercise.position != position) {
                updateWorkoutExercisePosition(workoutExercise.id, position)
            }
        }
    }

    @Update
    suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity)

    @Query("UPDATE workout_sets SET rest_seconds = :elapsedSeconds WHERE id = :workoutSetId")
    suspend fun updateWorkoutSetRestSeconds(workoutSetId: String, elapsedSeconds: Int)

    @Delete
    suspend fun deleteWorkoutSet(workoutSet: WorkoutSetEntity)

    @Transaction
    suspend fun deleteWorkoutSetAndReorder(workoutSet: WorkoutSetEntity) {
        deleteWorkoutSet(workoutSet)
        getWorkoutSets(workoutSet.workoutExerciseId).forEachIndexed { position, set ->
            if (set.position != position) {
                updateWorkoutSet(set.copy(position = position))
            }
        }
    }

    @Query("SELECT * FROM workouts WHERE id = :workoutId")
    suspend fun getWorkout(workoutId: String): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE status = 'active' ORDER BY started_at_millis DESC LIMIT 1")
    suspend fun getActiveWorkout(): WorkoutEntity?

    @Query("SELECT * FROM workouts ORDER BY started_at_millis DESC")
    suspend fun getAllWorkouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE status = 'finished' ORDER BY finished_at_millis DESC")
    suspend fun getFinishedWorkouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workout_exercises WHERE workout_id = :workoutId ORDER BY position")
    suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity>

    @Query(
        """SELECT workout_exercises.id, workout_exercises.workout_id AS workoutId,
            workout_exercises.exercise_id AS exerciseId, workout_exercises.position, workout_exercises.notes,
            exercises.name AS exerciseName
            FROM workout_exercises
            INNER JOIN exercises ON exercises.id = workout_exercises.exercise_id
            WHERE workout_exercises.workout_id = :workoutId
            ORDER BY workout_exercises.position""",
    )
    suspend fun getWorkoutExercisesWithNames(workoutId: String): List<WorkoutExerciseWithName>

    @Query("SELECT * FROM workout_sets WHERE workout_exercise_id = :workoutExerciseId ORDER BY position")
    suspend fun getWorkoutSets(workoutExerciseId: String): List<WorkoutSetEntity>

    /** Returns the completed sets from this exercise's most recently finished workout. */
    @Query(
        """SELECT workout_sets.* FROM workout_sets
            INNER JOIN workout_exercises ON workout_exercises.id = workout_sets.workout_exercise_id
            WHERE workout_exercises.id = (
                SELECT previous_workout_exercises.id FROM workout_exercises AS previous_workout_exercises
                INNER JOIN workouts AS previous_workouts
                    ON previous_workouts.id = previous_workout_exercises.workout_id
                WHERE previous_workout_exercises.exercise_id = :exerciseId
                    AND previous_workouts.status = 'finished'
                ORDER BY previous_workouts.finished_at_millis DESC, previous_workout_exercises.position
                LIMIT 1
            )
            AND workout_sets.is_completed = 1
            ORDER BY workout_sets.position""",
    )
    suspend fun previousCompletedSetsForExercise(exerciseId: String): List<WorkoutSetEntity>

    @Transaction
    suspend fun getWorkoutDetails(workoutId: String): WorkoutDetails? {
        val workout = getWorkout(workoutId) ?: return null
        val exercises = getWorkoutExercisesWithNames(workoutId).map { workoutExercise ->
            WorkoutExerciseDetails(
                workoutExercise = workoutExercise,
                sets = getWorkoutSets(workoutExercise.id),
            )
        }
        return WorkoutDetails(workout, exercises)
    }

    @Transaction
    suspend fun getFinishedWorkoutDetails(): List<WorkoutDetails> =
        getFinishedWorkouts().mapNotNull { workout -> getWorkoutDetails(workout.id) }
}

data class WorkoutDetails(
    val workout: WorkoutEntity,
    val exercises: List<WorkoutExerciseDetails>,
)

data class WorkoutExerciseDetails(
    val workoutExercise: WorkoutExerciseWithName,
    val sets: List<WorkoutSetEntity>,
)

data class WorkoutExerciseWithName(
    val id: String,
    val workoutId: String,
    val exerciseId: String,
    val position: Int,
    val notes: String,
    val exerciseName: String,
)
