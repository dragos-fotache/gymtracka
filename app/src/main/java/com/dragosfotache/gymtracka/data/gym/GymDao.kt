package com.dragosfotache.gymtracka.data.gym

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MuscleGroupDao {
    @Query("SELECT * FROM muscle_groups ORDER BY name")
    suspend fun getAll(): List<MuscleGroupEntity>

    @Query("SELECT * FROM muscle_groups WHERE id = :id")
    suspend fun getById(id: String): MuscleGroupEntity?

    @Query("SELECT * FROM muscle_groups WHERE slug = :slug")
    suspend fun getBySlug(slug: String): MuscleGroupEntity?
}

@Dao
interface EquipmentDao {
    @Query("SELECT * FROM equipment ORDER BY name")
    suspend fun getAll(): List<EquipmentEntity>

    @Query("SELECT * FROM equipment WHERE id = :id")
    suspend fun getById(id: String): EquipmentEntity?
}

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises ORDER BY name")
    suspend fun getAll(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE name LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY name")
    suspend fun searchByName(query: String): List<ExerciseEntity>

    @Query(
        """SELECT exercises.* FROM exercises
            INNER JOIN exercise_muscle_groups ON exercise_muscle_groups.exercise_id = exercises.id
            WHERE exercise_muscle_groups.muscle_group_id = :muscleGroupId
            ORDER BY exercises.name""",
    )
    suspend fun getForMuscleGroup(muscleGroupId: String): List<ExerciseEntity>

    @Query(
        """SELECT muscle_groups.id, muscle_groups.slug, muscle_groups.name,
            exercise_muscle_groups.type AS relationshipType
            FROM exercise_muscle_groups
            INNER JOIN muscle_groups ON muscle_groups.id = exercise_muscle_groups.muscle_group_id
            WHERE exercise_muscle_groups.exercise_id = :exerciseId
            ORDER BY CASE exercise_muscle_groups.type
                WHEN 'primary' THEN 0 WHEN 'secondary' THEN 1 ELSE 2 END, muscle_groups.name""",
    )
    suspend fun getMuscleGroups(exerciseId: String): List<ExerciseMuscleGroup>

    @Query(
        """SELECT equipment.id, equipment.name, equipment.type, equipment.usage_type AS usageType,
            exercise_equipment.num_items AS numberOfItems
            FROM exercise_equipment
            INNER JOIN equipment ON equipment.id = exercise_equipment.equipment_id
            WHERE exercise_equipment.exercise_id = :exerciseId
            ORDER BY equipment.name""",
    )
    suspend fun getEquipment(exerciseId: String): List<ExerciseEquipment>

    @Query(
        """SELECT exercise_muscle_groups.exercise_id AS exerciseId,
            muscle_groups.id, muscle_groups.slug, muscle_groups.name,
            exercise_muscle_groups.type AS relationshipType
            FROM exercise_muscle_groups
            INNER JOIN muscle_groups ON muscle_groups.id = exercise_muscle_groups.muscle_group_id
            ORDER BY exercise_muscle_groups.exercise_id, muscle_groups.name""",
    )
    suspend fun getAllMuscleGroups(): List<ExerciseMuscleGroupRow>

    @Query(
        """SELECT exercise_equipment.exercise_id AS exerciseId,
            equipment.id, equipment.name, equipment.type, equipment.usage_type AS usageType,
            exercise_equipment.num_items AS numberOfItems
            FROM exercise_equipment
            INNER JOIN equipment ON equipment.id = exercise_equipment.equipment_id
            ORDER BY exercise_equipment.exercise_id, equipment.name""",
    )
    suspend fun getAllEquipment(): List<ExerciseEquipmentRow>

    @Transaction
    suspend fun getDetails(exerciseId: String): ExerciseDetails? {
        val exercise = getById(exerciseId) ?: return null
        return ExerciseDetails(
            exercise = exercise,
            muscleGroups = getMuscleGroups(exerciseId),
            equipment = getEquipment(exerciseId),
        )
    }

    /** Returns every exercise and its list-display metadata without per-row database queries. */
    @Transaction
    suspend fun getAllSummaries(): List<ExerciseSummary> {
        val muscleGroupsByExercise = getAllMuscleGroups().groupBy { it.exerciseId }
        val equipmentByExercise = getAllEquipment().groupBy { it.exerciseId }

        return getAll().map { exercise ->
            ExerciseSummary(
                exercise = exercise,
                muscleGroups = muscleGroupsByExercise[exercise.id].orEmpty(),
                equipment = equipmentByExercise[exercise.id].orEmpty(),
            )
        }
    }
}

data class ExerciseMuscleGroup(
    val id: String,
    val slug: String,
    val name: String,
    val relationshipType: String,
)

data class ExerciseMuscleGroupRow(
    val exerciseId: String,
    val id: String,
    val slug: String,
    val name: String,
    val relationshipType: String,
)

data class ExerciseEquipment(
    val id: String,
    val name: String,
    val type: String,
    val usageType: String?,
    val numberOfItems: Int?,
)

data class ExerciseEquipmentRow(
    val exerciseId: String,
    val id: String,
    val name: String,
    val type: String,
    val usageType: String?,
    val numberOfItems: Int?,
)

data class ExerciseDetails(
    val exercise: ExerciseEntity,
    val muscleGroups: List<ExerciseMuscleGroup>,
    val equipment: List<ExerciseEquipment>,
)

data class ExerciseSummary(
    val exercise: ExerciseEntity,
    val muscleGroups: List<ExerciseMuscleGroupRow>,
    val equipment: List<ExerciseEquipmentRow>,
)
