package com.dragosfotache.gymtracka.data.gym

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface RoutineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutine(routine: RoutineEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutineExercises(exercises: List<RoutineExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutineSets(sets: List<RoutineSetEntity>)

    @Transaction
    suspend fun insertExercisesWithInitialSets(
        exercises: List<RoutineExerciseEntity>,
        sets: List<RoutineSetEntity>,
    ) {
        insertRoutineExercises(exercises)
        insertRoutineSets(sets)
    }

    @Transaction
    suspend fun insertRoutineWithExercisesAndSets(
        routine: RoutineEntity,
        exercises: List<RoutineExerciseEntity>,
        sets: List<RoutineSetEntity>,
    ) {
        insertRoutine(routine)
        insertRoutineExercises(exercises)
        insertRoutineSets(sets)
    }

    @Query("UPDATE routines SET name = :name, is_draft = 0 WHERE id = :routineId")
    suspend fun saveRoutine(routineId: String, name: String)

    @Query("UPDATE routines SET name = :name WHERE id = :routineId")
    suspend fun updateRoutineName(routineId: String, name: String)

    @Query("DELETE FROM routines WHERE id = :routineId")
    suspend fun deleteRoutine(routineId: String)

    @Query("SELECT * FROM routines WHERE id = :routineId")
    suspend fun getRoutine(routineId: String): RoutineEntity?

    /**
     * Account-scoped routine reads. Only current-account nondeleted roots are visible;
     * signed-out null accounts match local rows through the SQLite IS operator.
     */
    @Query(
        """SELECT * FROM routines WHERE id = :routineId AND account_id IS :accountId
            AND deleted_at_millis IS NULL""",
    )
    suspend fun getScopedRoutine(routineId: String, accountId: String?): RoutineEntity?

    @Query(
        """SELECT * FROM routines WHERE is_draft = 0 AND account_id IS :accountId
            AND deleted_at_millis IS NULL ORDER BY name COLLATE NOCASE""",
    )
    suspend fun getScopedSavedRoutines(accountId: String?): List<RoutineEntity>

    /**
     * Owned child references to a catalogue exercise, including children retained by
     * tombstoned roots. Only the physical removal of the child (or its aggregate)
     * releases the reference; hidden roots never justify stripping relationships.
     */
    @Query(
        """SELECT COUNT(*) FROM routine_exercises
            INNER JOIN routines ON routines.id = routine_exercises.routine_id
            WHERE routine_exercises.exercise_id = :exerciseId
                AND routines.account_id IS :accountId""",
    )
    suspend fun countRetainedRoutineReferences(exerciseId: String, accountId: String?): Int

    @Query("SELECT * FROM routines WHERE is_draft = 0 ORDER BY name COLLATE NOCASE")
    suspend fun getSavedRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routine_exercises WHERE routine_id = :routineId ORDER BY position")
    suspend fun getRoutineExercises(routineId: String): List<RoutineExerciseEntity>

    @Query("SELECT * FROM routine_exercises WHERE id = :routineExerciseId")
    suspend fun getRoutineExercise(routineExerciseId: String): RoutineExerciseEntity?

    @Query(
        """SELECT routine_exercises.id, routine_exercises.routine_id AS routineId,
            routine_exercises.exercise_id AS exerciseId, routine_exercises.position,
            routine_exercises.rest_duration_seconds AS restDurationSeconds, exercises.name AS exerciseName
            FROM routine_exercises INNER JOIN exercises ON exercises.id = routine_exercises.exercise_id
            WHERE routine_exercises.routine_id = :routineId ORDER BY routine_exercises.position""",
    )
    suspend fun getRoutineExercisesWithNames(routineId: String): List<RoutineExerciseWithName>

    @Query("SELECT * FROM routine_sets WHERE routine_exercise_id = :routineExerciseId ORDER BY position")
    suspend fun getRoutineSets(routineExerciseId: String): List<RoutineSetEntity>

    @Query("SELECT * FROM routine_sets WHERE id = :setId")
    suspend fun getRoutineSet(setId: String): RoutineSetEntity?

    /** Updates only editable set values, preserving position, ID, and parent. */
    @Query(
        """UPDATE routine_sets SET repetitions = :repetitions, weight_kg = :weightKg
            WHERE id = :setId""",
    )
    suspend fun updateRoutineSetValues(setId: String, repetitions: Int?, weightKg: Double?)

    /** Removes every child exercise (cascading sets) while preserving the root row. */
    @Query("DELETE FROM routine_exercises WHERE routine_id = :routineId")
    suspend fun deleteRoutineExercises(routineId: String)

    /** Hides an owned routine while preserving its row for publication. */
    @Query(
        """UPDATE routines SET deleted_at_millis = :deletedAtMillis, sync_state = 'pending'
            WHERE id = :routineId""",
    )
    suspend fun tombstoneRoutine(routineId: String, deletedAtMillis: Long)

    @Update
    suspend fun updateRoutineSet(set: RoutineSetEntity)

    @Query("UPDATE routine_exercises SET rest_duration_seconds = :seconds WHERE id = :routineExerciseId")
    suspend fun updateRestDuration(routineExerciseId: String, seconds: Int)

    @Query("DELETE FROM routine_exercises WHERE id = :routineExerciseId")
    suspend fun deleteRoutineExercise(routineExerciseId: String)

    @Query("UPDATE routine_exercises SET position = :position WHERE id = :routineExerciseId")
    suspend fun updateRoutineExercisePosition(routineExerciseId: String, position: Int)

    @Transaction
    suspend fun deleteRoutineExerciseAndReorder(routineExerciseId: String, routineId: String) {
        deleteRoutineExercise(routineExerciseId)
        getRoutineExercises(routineId).forEachIndexed { position, exercise ->
            if (exercise.position != position) updateRoutineExercisePosition(exercise.id, position)
        }
    }

    @Delete
    suspend fun deleteRoutineSet(set: RoutineSetEntity)

    @Transaction
    suspend fun deleteRoutineSetAndReorder(set: RoutineSetEntity) {
        deleteRoutineSet(set)
        getRoutineSets(set.routineExerciseId).forEachIndexed { position, remainingSet ->
            if (remainingSet.position != position) updateRoutineSet(remainingSet.copy(position = position))
        }
    }

    @Transaction
    suspend fun getRoutineDetails(routineId: String): RoutineDetails? {
        val routine = getRoutine(routineId) ?: return null
        val exercises = getRoutineExercisesWithNames(routineId).map { exercise ->
            RoutineExerciseDetails(exercise, getRoutineSets(exercise.id))
        }
        return RoutineDetails(routine, exercises)
    }

    @Transaction
    suspend fun getScopedRoutineDetails(routineId: String, accountId: String?): RoutineDetails? {
        val routine = getScopedRoutine(routineId, accountId) ?: return null
        val exercises = getScopedRoutineExercisesWithNames(routineId, accountId).map { exercise ->
            RoutineExerciseDetails(exercise, getRoutineSets(exercise.id))
        }
        return RoutineDetails(routine, exercises)
    }

    /**
     * Exercise names resolve only through visible references: bundled rows or owned
     * nondeleted customs. Hidden foreign-account names never leak through an owned
     * root; raw internal reads stay separate so filtering cannot drop persisted
     * children from a future upload.
     */
    @Query(
        """SELECT routine_exercises.id, routine_exercises.routine_id AS routineId,
            routine_exercises.exercise_id AS exerciseId, routine_exercises.position,
            routine_exercises.rest_duration_seconds AS restDurationSeconds, exercises.name AS exerciseName
            FROM routine_exercises INNER JOIN exercises ON exercises.id = routine_exercises.exercise_id
                AND (exercises.is_user_created = 0 OR exercises.account_id IS :accountId)
                AND exercises.deleted_at_millis IS NULL
            WHERE routine_exercises.routine_id = :routineId ORDER BY routine_exercises.position""",
    )
    suspend fun getScopedRoutineExercisesWithNames(
        routineId: String,
        accountId: String?,
    ): List<RoutineExerciseWithName>

    @Transaction
    suspend fun getSavedRoutineDetails(): List<RoutineDetails> =
        getSavedRoutines().mapNotNull { routine -> getRoutineDetails(routine.id) }

    @Transaction
    suspend fun getScopedSavedRoutineDetails(accountId: String?): List<RoutineDetails> =
        getScopedSavedRoutines(accountId).mapNotNull { routine ->
            getScopedRoutineDetails(routine.id, accountId)
        }
}

data class RoutineDetails(
    val routine: RoutineEntity,
    val exercises: List<RoutineExerciseDetails>,
)

data class RoutineExerciseDetails(
    val routineExercise: RoutineExerciseWithName,
    val sets: List<RoutineSetEntity>,
)

data class RoutineExerciseWithName(
    val id: String,
    val routineId: String,
    val exerciseId: String,
    val position: Int,
    val restDurationSeconds: Int,
    val exerciseName: String,
)
