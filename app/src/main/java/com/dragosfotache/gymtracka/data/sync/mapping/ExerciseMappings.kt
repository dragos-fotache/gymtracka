package com.dragosfotache.gymtracka.data.sync.mapping

import com.dragosfotache.gymtracka.data.gym.ExerciseDetails
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseEquipmentEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseMuscleGroupEntity
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.validateCustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateCustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.validateRecordRelationships

/**
 * Explicit mappings between Room custom exercises and transport models.
 * Bundled catalogue rows can never be uploaded; display names are never written
 * back to the catalogue. No database writes. IDs pass through unchanged.
 */

/** Flat Room rows produced from a canonical custom record. */
data class CustomExerciseRoomAggregate(
    val exercise: ExerciseEntity,
    val muscleGroups: List<ExerciseMuscleGroupEntity>,
    val equipment: List<ExerciseEquipmentEntity>,
)

private fun splitRelationships(details: ExerciseDetails): Triple<String?, List<String>, Pair<String?, Int?>> {
    val primaries = details.muscleGroups.filter { it.relationshipType == "primary" }
    val secondaries = details.muscleGroups.filter { it.relationshipType == "secondary" }
    if (details.muscleGroups.any { it.relationshipType != "primary" && it.relationshipType != "secondary" }) {
        throw SyncModelException("Custom exercise has an unsupported relationship shape")
    }
    if (primaries.size > 1) throw SyncModelException("Custom exercise has more than one primary muscle")
    if (details.equipment.size > 1) throw SyncModelException("Custom exercise has more than one equipment")
    val equipment = details.equipment.singleOrNull()
    return Triple(
        primaries.singleOrNull()?.id,
        secondaries.map { it.id },
        Pair(equipment?.id, equipment?.numberOfItems),
    )
}

private fun requireUploadable(details: ExerciseDetails) {
    val exercise = details.exercise
    if (!exercise.isUserCreated) throw SyncModelException("Bundled exercises cannot be uploaded")
    if (exercise.deletedAtMillis != null) throw SyncModelException("Deleted exercises cannot be uploaded")
    if (exercise.type != "reps") throw SyncModelException("Custom exercise type must be 'reps'")
}

/**
 * Maps local details to upload input. Rejects bundled, deleted, and non-reps rows
 * plus lossy relationship shapes (tertiary, multiple primaries/equipment).
 */
fun ExerciseDetails.toCustomExerciseInput(): CustomExerciseInputModel {
    requireUploadable(this)
    val (primary, secondaries, equipment) = splitRelationships(this)
    return validateCustomExerciseInputModel(
        CustomExerciseInputModel(
            id = exercise.id,
            name = exercise.name,
            primaryMuscleGroupId = primary,
            secondaryMuscleGroupIds = secondaries,
            equipmentId = equipment.first,
            equipmentNumItems = equipment.second,
            category = exercise.category,
            difficultyLevel = exercise.difficultyLevel,
            forceType = exercise.forceType,
            mechanics = exercise.mechanics,
            instructions = exercise.instructions,
        ),
    )
}

/**
 * Maps local details to a canonical record, but only when an explicit positive
 * server revision is supplied. Display names come from the loaded details.
 */
fun ExerciseDetails.toCustomExerciseRecord(revision: Long): CustomExerciseRecordModel {
    requireUploadable(this)
    if (revision < 1) throw SyncModelException("Canonical record requires a positive revision")
    val (_, _, equipment) = splitRelationships(this)
    val muscleRefs = muscleGroups
        .filter { it.relationshipType == "primary" || it.relationshipType == "secondary" }
        .map { MuscleGroupRefModel(it.id, it.name, it.relationshipType) }
    val equipmentRefs = equipment.first?.let { id ->
        val row = this.equipment.single { it.id == id }
        listOf(EquipmentRefModel(row.id, row.name, row.numberOfItems))
    }.orEmpty()
    return validateCustomExerciseRecordModel(
        CustomExerciseRecordModel(
            id = exercise.id,
            name = exercise.name,
            type = exercise.type,
            category = exercise.category,
            difficultyLevel = exercise.difficultyLevel,
            forceType = exercise.forceType,
            mechanics = exercise.mechanics,
            instructions = exercise.instructions,
            isUserCreated = true,
            revision = revision,
            muscleGroups = muscleRefs,
            equipment = equipmentRefs,
        ),
    )
}

/**
 * Flattens a validated canonical record to an ExerciseEntity plus exact relationship
 * rows. The caller supplies the owning account and local generation explicitly;
 * rows carry synced metadata, the canonical revision, and isUserCreated.
 */
fun CustomExerciseRecordModel.toRoomAggregate(
    accountId: String,
    localGeneration: Long,
): CustomExerciseRoomAggregate {
    if (accountId.isBlank()) throw SyncModelException("Room aggregate requires a nonblank accountId")
    if (localGeneration < 0) throw SyncModelException("Room aggregate requires localGeneration >= 0")
    val record = validateCustomExerciseRecordModel(this)
    val (muscles, equipment) = validateRecordRelationships(record.muscleGroups, record.equipment)
    val exercise = ExerciseEntity(
        id = record.id,
        name = record.name,
        type = record.type,
        difficultyLevel = record.difficultyLevel,
        forceType = record.forceType,
        mechanics = record.mechanics,
        category = record.category,
        instructions = record.instructions,
        isUserCreated = true,
        accountId = accountId,
        serverRevision = record.revision,
        localGeneration = localGeneration,
        syncState = "synced",
        deletedAtMillis = null,
    )
    return CustomExerciseRoomAggregate(
        exercise,
        muscles.map { ExerciseMuscleGroupEntity(record.id, it.id, it.type) },
        equipment.map { ExerciseEquipmentEntity(record.id, it.id, it.numItems) },
    )
}
