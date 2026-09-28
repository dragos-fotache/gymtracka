package com.dragosfotache.gymtracka.data.sync.model

/**
 * Shape invariants for custom-exercise aggregates. First-release rules: at most one
 * primary muscle, distinct secondaries with no primary overlap, at most one equipment
 * item, primary/secondary relationships only, and a quantity only with equipment.
 * Tertiary relationships are rejected, never dropped. Names must be nonblank.
 * IDs are never generated or remapped here.
 */

private fun validateRelationshipIds(
    primaryMuscleGroupId: String?,
    secondaryMuscleGroupIds: List<String>,
    equipmentId: String?,
    equipmentNumItems: Int?,
) {
    primaryMuscleGroupId?.let { requireWorkoutUuid(it, "primaryMuscleGroupId") }
    secondaryMuscleGroupIds.forEach { requireWorkoutUuid(it, "secondaryMuscleGroupIds[]") }
    equipmentId?.let { requireWorkoutUuid(it, "equipmentId") }
    if (secondaryMuscleGroupIds.toSet().size != secondaryMuscleGroupIds.size) {
        throw SyncModelException("Custom exercise has duplicate secondary muscles")
    }
    if (primaryMuscleGroupId != null && secondaryMuscleGroupIds.contains(primaryMuscleGroupId)) {
        throw SyncModelException("Custom exercise primary muscle overlaps secondaries")
    }
    if (equipmentNumItems != null) {
        if (equipmentNumItems < 0) throw SyncModelException("Custom exercise has negative equipment quantity")
        if (equipmentId == null) throw SyncModelException("Custom exercise quantity requires equipment")
    }
}

private fun validateName(name: String) {
    if (name.isBlank()) throw SyncModelException("Custom exercise name must be nonblank")
}

fun validateCustomExerciseInputModel(input: CustomExerciseInputModel): CustomExerciseInputModel {
    requireWorkoutUuid(input.id, "id")
    validateName(input.name)
    validateRelationshipIds(
        input.primaryMuscleGroupId,
        input.secondaryMuscleGroupIds,
        input.equipmentId,
        input.equipmentNumItems,
    )
    return input.copy(secondaryMuscleGroupIds = input.secondaryMuscleGroupIds.sorted())
}

private fun muscleRank(type: String): Int = when (type) {
    "primary" -> 0
    "secondary" -> 1
    else -> throw SyncModelException("Custom exercise supports primary/secondary relationships only")
}

/** Validates canonical relationships and returns them stabilized: primary first, then UUID. */
internal fun validateRecordRelationships(
    muscleGroups: List<MuscleGroupRefModel>,
    equipment: List<EquipmentRefModel>,
): Pair<List<MuscleGroupRefModel>, List<EquipmentRefModel>> {
    muscleGroups.forEach {
        requireWorkoutUuid(it.id, "muscleGroups[].id")
        muscleRank(it.type)
    }
    equipment.forEach {
        requireWorkoutUuid(it.id, "equipment[].id")
        if (it.numItems != null && it.numItems < 0) {
            throw SyncModelException("Custom exercise has negative equipment quantity")
        }
    }
    if (muscleGroups.count { it.type == "primary" } > 1) {
        throw SyncModelException("Custom exercise has more than one primary muscle")
    }
    val muscleIds = muscleGroups.map { it.id }
    if (muscleIds.toSet().size != muscleIds.size) {
        throw SyncModelException("Custom exercise has duplicate muscles")
    }
    val primaryId = muscleGroups.singleOrNull { it.type == "primary" }?.id
    if (primaryId != null && muscleGroups.any { it.type == "secondary" && it.id == primaryId }) {
        throw SyncModelException("Custom exercise primary muscle overlaps secondaries")
    }
    if (equipment.size > 1) throw SyncModelException("Custom exercise has more than one equipment")
    val sortedMuscles = muscleGroups.sortedWith(compareBy({ muscleRank(it.type) }, { it.id }))
    val sortedEquipment = equipment.sortedBy { it.id }
    return sortedMuscles to sortedEquipment
}

fun validateCustomExerciseRecordModel(record: CustomExerciseRecordModel): CustomExerciseRecordModel {
    requireWorkoutUuid(record.id, "id")
    validateName(record.name)
    if (record.type != "reps") throw SyncModelException("Custom exercise type must be 'reps'")
    if (!record.isUserCreated) throw SyncModelException("Canonical custom record requires isUserCreated")
    requirePositiveRevision(record.revision, "Custom exercise record")
    val (muscles, equipment) = validateRecordRelationships(record.muscleGroups, record.equipment)
    return record.copy(muscleGroups = muscles, equipment = equipment)
}
