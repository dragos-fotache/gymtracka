package com.dragosfotache.gymtracka.data.gym

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Focused publication primitives for R05c canonical projection. Reads cover the
 * whole current domain for family-scoped collision checks; writes replace only
 * the selected account graph with explicit deletes/inserts/updates, never root
 * `INSERT OR REPLACE`. All calls must run inside the caller's fenced Room
 * transaction. Workout and routine namespaces are independent; exercise roots
 * validate within the exercises table only.
 */
@Dao
interface SyncProjectionDao {
    @Query("SELECT * FROM workouts")
    suspend fun getAllWorkouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workout_exercises")
    suspend fun getAllWorkoutExercises(): List<WorkoutExerciseEntity>

    @Query("SELECT * FROM workout_sets")
    suspend fun getAllWorkoutSets(): List<WorkoutSetEntity>

    @Query("SELECT * FROM routines")
    suspend fun getAllRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routine_exercises")
    suspend fun getAllRoutineExercises(): List<RoutineExerciseEntity>

    @Query("SELECT * FROM routine_sets")
    suspend fun getAllRoutineSets(): List<RoutineSetEntity>

    @Query("SELECT * FROM exercises")
    suspend fun getAllExercises(): List<ExerciseEntity>

    @Query("SELECT id FROM muscle_groups")
    suspend fun getMuscleGroupIds(): List<String>

    @Query("SELECT id FROM equipment")
    suspend fun getEquipmentIds(): List<String>

    @Query(
        """DELETE FROM workout_exercises WHERE workout_id IN
            (SELECT id FROM workouts WHERE account_id = :accountId)""",
    )
    suspend fun deleteWorkoutExercisesForAccount(accountId: String)

    @Query("DELETE FROM workouts WHERE account_id = :accountId")
    suspend fun deleteWorkoutsForAccount(accountId: String)

    @Query(
        """DELETE FROM routine_exercises WHERE routine_id IN
            (SELECT id FROM routines WHERE account_id = :accountId)""",
    )
    suspend fun deleteRoutineExercisesForAccount(accountId: String)

    @Query("DELETE FROM routines WHERE account_id = :accountId")
    suspend fun deleteRoutinesForAccount(accountId: String)

    @Query(
        """DELETE FROM exercise_muscle_groups WHERE exercise_id IN
            (SELECT id FROM exercises WHERE account_id = :accountId AND is_user_created = 1)""",
    )
    suspend fun deleteCustomMuscleGroupsForAccount(accountId: String)

    @Query(
        """DELETE FROM exercise_equipment WHERE exercise_id IN
            (SELECT id FROM exercises WHERE account_id = :accountId AND is_user_created = 1)""",
    )
    suspend fun deleteCustomEquipmentForAccount(accountId: String)

    @Query(
        """DELETE FROM exercises
            WHERE account_id = :accountId AND is_user_created = 1 AND id = :id""",
    )
    suspend fun deleteCustomExerciseById(accountId: String, id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExercises(exercises: List<ExerciseEntity>)

    @Update
    suspend fun updateExercises(exercises: List<ExerciseEntity>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExerciseMuscleGroups(rows: List<ExerciseMuscleGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExerciseEquipment(rows: List<ExerciseEquipmentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkouts(workouts: List<WorkoutEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutExercises(exercises: List<WorkoutExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkoutSets(sets: List<WorkoutSetEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutines(routines: List<RoutineEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutineExercises(exercises: List<RoutineExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutineSets(sets: List<RoutineSetEntity>)
}
