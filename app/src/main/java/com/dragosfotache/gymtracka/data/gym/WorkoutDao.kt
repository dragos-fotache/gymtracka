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
    suspend fun insertWorkoutSet(workoutSet: WorkoutSetEntity)

    @Transaction
    suspend fun insertExercisesWithInitialSets(
        workoutExercises: List<WorkoutExerciseEntity>,
        workoutSets: List<WorkoutSetEntity>,
    ) {
        insertWorkoutExercises(workoutExercises)
        insertWorkoutSets(workoutSets)
    }

    /** Persists a complete active-workout copy as one transaction. */
    @Transaction
    suspend fun insertWorkoutWithExercisesAndSets(
        workout: WorkoutEntity,
        workoutExercises: List<WorkoutExerciseEntity>,
        workoutSets: List<WorkoutSetEntity>,
    ) {
        insertWorkout(workout)
        insertWorkoutExercises(workoutExercises)
        insertWorkoutSets(workoutSets)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutExercises(workoutExercises: List<WorkoutExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutSets(workoutSets: List<WorkoutSetEntity>)

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

    /** Removes every child exercise (cascading sets) while preserving the root row. */
    @Query("DELETE FROM workout_exercises WHERE workout_id = :workoutId")
    suspend fun deleteWorkoutExercises(workoutId: String)

    /** Hides an owned workout while preserving its row for publication. */
    @Query(
        """UPDATE workouts SET deleted_at_millis = :deletedAtMillis, sync_state = 'pending'
            WHERE id = :workoutId""",
    )
    suspend fun tombstoneWorkout(workoutId: String, deletedAtMillis: Long)

    @Query("DELETE FROM workout_exercises WHERE id = :workoutExerciseId")
    suspend fun deleteWorkoutExercise(workoutExerciseId: String)

    @Query("UPDATE workout_exercises SET position = :position WHERE id = :workoutExerciseId")
    suspend fun updateWorkoutExercisePosition(workoutExerciseId: String, position: Int)

    /** Removes exactly the given sets, used by finish cleanup for non-survivors. */
    @Query("DELETE FROM workout_sets WHERE id IN (:setIds)")
    suspend fun deleteWorkoutSetsByIds(setIds: List<String>)

    /** Repositions one surviving set during finish renumbering. */
    @Query("UPDATE workout_sets SET position = :position WHERE id = :workoutSetId")
    suspend fun updateWorkoutSetPosition(workoutSetId: String, position: Int)

    @Query("UPDATE workout_exercises SET notes = :notes WHERE id = :workoutExerciseId")
    suspend fun updateWorkoutExerciseNotes(workoutExerciseId: String, notes: String)

    @Query(
        "UPDATE workout_exercises SET rest_duration_seconds = :restDurationSeconds WHERE id = :workoutExerciseId",
    )
    suspend fun updateWorkoutExerciseRestDuration(
        workoutExerciseId: String,
        restDurationSeconds: Int,
    )

    @Query("UPDATE workout_exercises SET exercise_id = :exerciseId WHERE id = :workoutExerciseId")
    suspend fun replaceWorkoutExercise(workoutExerciseId: String, exerciseId: String)

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

    /**
     * Selective set-value write for repository capture: values, completion, and
     * recorded rest apply explicitly while the stored ID, parent, and position
     * stay untouched, so stale caller snapshots can never reparent or reorder.
     */
    @Query(
        """UPDATE workout_sets SET repetitions = :repetitions, weight_kg = :weightKg,
            is_completed = :isCompleted, rest_seconds = :restSeconds WHERE id = :workoutSetId""",
    )
    suspend fun updateWorkoutSetValues(
        workoutSetId: String,
        repetitions: Int?,
        weightKg: Double?,
        isCompleted: Boolean,
        restSeconds: Int?,
    )

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

    /**
     * Account-scoped workout reads. Only current-account nondeleted roots are visible;
     * signed-out null accounts match local rows through the SQLite IS operator.
     */
    @Query(
        """SELECT * FROM workouts WHERE status = 'active' AND account_id IS :accountId
            AND deleted_at_millis IS NULL ORDER BY started_at_millis DESC LIMIT 1""",
    )
    suspend fun getScopedActiveWorkout(accountId: String?): WorkoutEntity?

    @Query(
        """SELECT * FROM workouts WHERE id = :workoutId AND account_id IS :accountId
            AND deleted_at_millis IS NULL""",
    )
    suspend fun getScopedWorkout(workoutId: String, accountId: String?): WorkoutEntity?

    @Query(
        """SELECT * FROM workouts WHERE account_id IS :accountId
            AND deleted_at_millis IS NULL ORDER BY started_at_millis DESC""",
    )
    suspend fun getScopedAllWorkouts(accountId: String?): List<WorkoutEntity>

    @Query(
        """SELECT * FROM workouts WHERE status = 'finished' AND account_id IS :accountId
            AND deleted_at_millis IS NULL ORDER BY finished_at_millis DESC""",
    )
    suspend fun getScopedFinishedWorkouts(accountId: String?): List<WorkoutEntity>

    @Transaction
    suspend fun getScopedWorkoutDetails(workoutId: String, accountId: String?): WorkoutDetails? {
        val workout = getScopedWorkout(workoutId, accountId) ?: return null
        val exercises = getScopedWorkoutExercisesWithNames(workoutId, accountId).map { workoutExercise ->
            WorkoutExerciseDetails(
                workoutExercise = workoutExercise,
                sets = getWorkoutSets(workoutExercise.id),
            )
        }
        return WorkoutDetails(workout, exercises)
    }

    /**
     * Exercise names resolve only through visible references: bundled rows or owned
     * nondeleted customs. Hidden foreign-account names never leak through an owned
     * root; raw internal reads stay separate so filtering cannot drop persisted
     * children from a future upload.
     */
    @Query(
        """SELECT workout_exercises.id, workout_exercises.workout_id AS workoutId,
            workout_exercises.exercise_id AS exerciseId, workout_exercises.position, workout_exercises.notes,
            workout_exercises.rest_duration_seconds AS restDurationSeconds,
            exercises.name AS exerciseName
            FROM workout_exercises
            INNER JOIN exercises ON exercises.id = workout_exercises.exercise_id
                AND (exercises.is_user_created = 0 OR exercises.account_id IS :accountId)
                AND exercises.deleted_at_millis IS NULL
            WHERE workout_exercises.workout_id = :workoutId
            ORDER BY workout_exercises.position""",
    )
    suspend fun getScopedWorkoutExercisesWithNames(
        workoutId: String,
        accountId: String?,
    ): List<WorkoutExerciseWithName>

    /**
     * Owned child references to a catalogue exercise, including children retained by
     * tombstoned roots. Only the physical removal of the child (or its aggregate)
     * releases the reference; hidden roots never justify stripping relationships.
     */
    @Query(
        """SELECT COUNT(*) FROM workout_exercises
            INNER JOIN workouts ON workouts.id = workout_exercises.workout_id
            WHERE workout_exercises.exercise_id = :exerciseId
                AND workouts.account_id IS :accountId""",
    )
    suspend fun countRetainedWorkoutReferences(exerciseId: String, accountId: String?): Int

    @Query(
        """SELECT * FROM workouts WHERE status = 'active' AND account_id IS :accountId
            AND deleted_at_millis IS NULL ORDER BY started_at_millis""",
    )
    suspend fun getScopedActiveWorkouts(accountId: String?): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE status = 'active' ORDER BY started_at_millis DESC LIMIT 1")
    suspend fun getActiveWorkout(): WorkoutEntity?

    @Query("SELECT * FROM workouts ORDER BY started_at_millis DESC")
    suspend fun getAllWorkouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE status = 'finished' ORDER BY finished_at_millis DESC")
    suspend fun getFinishedWorkouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workout_exercises WHERE id = :workoutExerciseId")
    suspend fun getWorkoutExercise(workoutExerciseId: String): WorkoutExerciseEntity?

    @Query("SELECT * FROM workout_sets WHERE id = :workoutSetId")
    suspend fun getWorkoutSet(workoutSetId: String): WorkoutSetEntity?

    @Query("SELECT * FROM workout_exercises WHERE workout_id = :workoutId ORDER BY position")
    suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity>

    @Query(
        """SELECT workout_exercises.id, workout_exercises.workout_id AS workoutId,
            workout_exercises.exercise_id AS exerciseId, workout_exercises.position, workout_exercises.notes,
            workout_exercises.rest_duration_seconds AS restDurationSeconds,
            exercises.name AS exerciseName
            FROM workout_exercises
            INNER JOIN exercises ON exercises.id = workout_exercises.exercise_id
            WHERE workout_exercises.workout_id = :workoutId
            ORDER BY workout_exercises.position""",
    )
    suspend fun getWorkoutExercisesWithNames(workoutId: String): List<WorkoutExerciseWithName>

    @Query("SELECT * FROM workout_sets WHERE workout_exercise_id = :workoutExerciseId ORDER BY position")
    suspend fun getWorkoutSets(workoutExerciseId: String): List<WorkoutSetEntity>

    /**
     * Returns the card-level history data for every exercise in two queries overall,
     * rather than loading each finished workout and each of its set lists separately.
     */
    @Query(
        """SELECT workout_exercises.id AS workoutExerciseId,
            workout_exercises.workout_id AS workoutId,
            workout_exercises.position AS position,
            exercises.name AS exerciseName,
            COUNT(workout_sets.id) AS setCount,
            COALESCE(
                SUM(
                    COALESCE(workout_sets.weight_kg, 0.0) *
                        COALESCE(workout_sets.repetitions, 0)
                ),
                0.0
            ) AS volumeKg,
            (
                SELECT best_set.weight_kg
                FROM workout_sets AS best_set
                WHERE best_set.workout_exercise_id = workout_exercises.id
                ORDER BY
                    COALESCE(best_set.weight_kg, 0.0) *
                        COALESCE(best_set.repetitions, 0) DESC,
                    best_set.position
                LIMIT 1
            ) AS bestSetWeightKg,
            (
                SELECT best_set.repetitions
                FROM workout_sets AS best_set
                WHERE best_set.workout_exercise_id = workout_exercises.id
                ORDER BY
                    COALESCE(best_set.weight_kg, 0.0) *
                        COALESCE(best_set.repetitions, 0) DESC,
                    best_set.position
                LIMIT 1
            ) AS bestSetRepetitions
            FROM workout_exercises
            INNER JOIN workouts ON workouts.id = workout_exercises.workout_id
            INNER JOIN exercises ON exercises.id = workout_exercises.exercise_id
            LEFT JOIN workout_sets ON workout_sets.workout_exercise_id = workout_exercises.id
            WHERE workouts.status = 'finished'
            GROUP BY
                workout_exercises.id,
                workout_exercises.workout_id,
                workout_exercises.position,
                exercises.name
            ORDER BY workouts.finished_at_millis DESC, workout_exercises.position""",
    )
    suspend fun getFinishedWorkoutExerciseSummaries(): List<WorkoutExerciseHistorySummary>

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

    /**
     * Deterministic account-scoped history selection: finished time, then workout UUID
     * as a stable tie breaker, then the first workout-exercise position. Configured
     * rest and copied sets always come from this same selected occurrence.
     */
    @Query(
        """SELECT workout_sets.* FROM workout_sets
            INNER JOIN workout_exercises ON workout_exercises.id = workout_sets.workout_exercise_id
            WHERE workout_exercises.id = (
                SELECT previous_workout_exercises.id FROM workout_exercises AS previous_workout_exercises
                INNER JOIN workouts AS previous_workouts
                    ON previous_workouts.id = previous_workout_exercises.workout_id
                WHERE previous_workout_exercises.exercise_id = :exerciseId
                    AND previous_workouts.status = 'finished'
                    AND previous_workouts.account_id IS :accountId
                    AND previous_workouts.deleted_at_millis IS NULL
                ORDER BY previous_workouts.finished_at_millis DESC, previous_workouts.id DESC,
                    previous_workout_exercises.position
                LIMIT 1
            )
            AND workout_sets.is_completed = 1
            ORDER BY workout_sets.position""",
    )
    suspend fun previousScopedCompletedSetsForExercise(
        exerciseId: String,
        accountId: String?,
    ): List<WorkoutSetEntity>

    /** Returns the configured rest duration from this exercise's most recent finished workout. */
    @Query(
        """SELECT previous_workout_exercises.rest_duration_seconds
            FROM workout_exercises AS previous_workout_exercises
            INNER JOIN workouts AS previous_workouts
                ON previous_workouts.id = previous_workout_exercises.workout_id
            WHERE previous_workout_exercises.exercise_id = :exerciseId
                AND previous_workouts.status = 'finished'
            ORDER BY previous_workouts.finished_at_millis DESC, previous_workout_exercises.position
            LIMIT 1""",
    )
    suspend fun previousRestDurationForExercise(exerciseId: String): Int?

    /** Account-scoped rest lookup from the same deterministic historical occurrence. */
    @Query(
        """SELECT previous_workout_exercises.rest_duration_seconds
            FROM workout_exercises AS previous_workout_exercises
            INNER JOIN workouts AS previous_workouts
                ON previous_workouts.id = previous_workout_exercises.workout_id
            WHERE previous_workout_exercises.exercise_id = :exerciseId
                AND previous_workouts.status = 'finished'
                AND previous_workouts.account_id IS :accountId
                AND previous_workouts.deleted_at_millis IS NULL
            ORDER BY previous_workouts.finished_at_millis DESC, previous_workouts.id DESC,
                previous_workout_exercises.position
            LIMIT 1""",
    )
    suspend fun previousScopedRestDurationForExercise(
        exerciseId: String,
        accountId: String?,
    ): Int?

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
    suspend fun getFinishedWorkoutSummaries(): List<WorkoutHistorySummary> {
        val exercisesByWorkout = getFinishedWorkoutExerciseSummaries().groupBy { it.workoutId }
        return getFinishedWorkouts().map { workout ->
            WorkoutHistorySummary(
                workout = workout,
                exercises = exercisesByWorkout[workout.id].orEmpty(),
            )
        }
    }

    @Query(
        """SELECT workout_exercises.id AS workoutExerciseId,
            workout_exercises.workout_id AS workoutId,
            workout_exercises.position AS position,
            exercises.name AS exerciseName,
            COUNT(workout_sets.id) AS setCount,
            COALESCE(
                SUM(
                    COALESCE(workout_sets.weight_kg, 0.0) *
                        COALESCE(workout_sets.repetitions, 0)
                ),
                0.0
            ) AS volumeKg,
            (
                SELECT best_set.weight_kg
                FROM workout_sets AS best_set
                WHERE best_set.workout_exercise_id = workout_exercises.id
                ORDER BY
                    COALESCE(best_set.weight_kg, 0.0) *
                        COALESCE(best_set.repetitions, 0) DESC,
                    best_set.position
                LIMIT 1
            ) AS bestSetWeightKg,
            (
                SELECT best_set.repetitions
                FROM workout_sets AS best_set
                WHERE best_set.workout_exercise_id = workout_exercises.id
                ORDER BY
                    COALESCE(best_set.weight_kg, 0.0) *
                        COALESCE(best_set.repetitions, 0) DESC,
                    best_set.position
                LIMIT 1
            ) AS bestSetRepetitions
            FROM workout_exercises
            INNER JOIN workouts ON workouts.id = workout_exercises.workout_id
            INNER JOIN exercises ON exercises.id = workout_exercises.exercise_id
                AND (exercises.is_user_created = 0 OR exercises.account_id IS :accountId)
                AND exercises.deleted_at_millis IS NULL
            LEFT JOIN workout_sets ON workout_sets.workout_exercise_id = workout_exercises.id
            WHERE workouts.status = 'finished'
                AND workouts.account_id IS :accountId
                AND workouts.deleted_at_millis IS NULL
            GROUP BY
                workout_exercises.id,
                workout_exercises.workout_id,
                workout_exercises.position,
                exercises.name
            ORDER BY workouts.finished_at_millis DESC, workout_exercises.position""",
    )
    suspend fun getScopedFinishedWorkoutExerciseSummaries(
        accountId: String?,
    ): List<WorkoutExerciseHistorySummary>

    @Transaction
    suspend fun getScopedFinishedWorkoutSummaries(
        accountId: String?,
    ): List<WorkoutHistorySummary> {
        val exercisesByWorkout = getScopedFinishedWorkoutExerciseSummaries(accountId).groupBy { it.workoutId }
        return getScopedFinishedWorkouts(accountId).map { workout ->
            WorkoutHistorySummary(
                workout = workout,
                exercises = exercisesByWorkout[workout.id].orEmpty(),
            )
        }
    }
}

data class WorkoutHistorySummary(
    val workout: WorkoutEntity,
    val exercises: List<WorkoutExerciseHistorySummary>,
)

data class WorkoutExerciseHistorySummary(
    val workoutExerciseId: String,
    val workoutId: String,
    val position: Int,
    val exerciseName: String,
    val setCount: Int,
    val volumeKg: Double,
    val bestSetWeightKg: Double?,
    val bestSetRepetitions: Int?,
)

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
    val restDurationSeconds: Int,
    val exerciseName: String,
)
