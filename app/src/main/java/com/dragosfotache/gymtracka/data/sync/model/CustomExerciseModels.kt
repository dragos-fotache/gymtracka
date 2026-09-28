package com.dragosfotache.gymtracka.data.sync.model

/**
 * Transport-independent custom-exercise aggregates. Mirrors OpenAPI CustomExerciseInput
 * and canonical Exercise (custom rows only; there is no bundled upload model).
 * The input carries relationship IDs; the canonical record carries display names.
 * Current custom type is always `reps` and is never request-controlled.
 */
data class MuscleGroupRefModel(
    val id: String,
    val name: String,
    val type: String,
)

data class EquipmentRefModel(
    val id: String,
    val name: String,
    val numItems: Int?,
)

data class CustomExerciseInputModel(
    val id: String,
    val name: String,
    val primaryMuscleGroupId: String?,
    val secondaryMuscleGroupIds: List<String>,
    val equipmentId: String?,
    val equipmentNumItems: Int?,
    val category: String?,
    val difficultyLevel: String?,
    val forceType: String?,
    val mechanics: String?,
    val instructions: List<String>?,
)

data class CustomExerciseRecordModel(
    val id: String,
    val name: String,
    val type: String,
    val category: String?,
    val difficultyLevel: String?,
    val forceType: String?,
    val mechanics: String?,
    val instructions: List<String>?,
    val isUserCreated: Boolean,
    val revision: Long,
    val muscleGroups: List<MuscleGroupRefModel>,
    val equipment: List<EquipmentRefModel>,
)
