package com.dragosfotache.gymtracka.data.sync.mapping

import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineRecordModel

/**
 * Explicit mappings between Room routine aggregates and transport models.
 * No database writes, no promotion/save validation, no state-holder integration.
 * Blank names and empty children pass through; IDs pass through unchanged.
 */

/** Flat Room rows produced from a canonical record. */
data class RoutineRoomAggregate(
    val routine: RoutineEntity,
    val exercises: List<RoutineExerciseEntity>,
    val sets: List<RoutineSetEntity>,
)

private fun mapSets(
    sets: List<RoutineSetEntity>,
    parentExerciseId: String,
    rootId: String,
): List<RoutineSetModel> {
    sets.forEach { set ->
        if (set.routineExerciseId != parentExerciseId) {
            throw SyncModelException(
                "Set ${set.id} does not belong to routine exercise $parentExerciseId of routine $rootId",
            )
        }
    }
    return sets.map { set ->
        RoutineSetModel(
            id = set.id,
            position = set.position,
            repetitions = set.repetitions,
            weightKg = set.weightKg,
        )
    }
}

/**
 * Maps local details to upload input. Requires a non-deleted root; drafts (even
 * empty or blank-named) map like saved routines. Children are validated and
 * returned sorted by explicit position.
 */
fun RoutineDetails.toRoutineInput(): RoutineInputModel {
    val root = routine
    if (root.deletedAtMillis != null) throw SyncModelException("Deleted routines cannot be uploaded")
    val entries = exercises.map { entry ->
        val exercise = entry.routineExercise
        if (exercise.routineId != root.id) {
            throw SyncModelException("Exercise ${exercise.id} does not belong to routine ${root.id}")
        }
        RoutineExerciseInputModel(
            id = exercise.id,
            exerciseId = exercise.exerciseId,
            position = exercise.position,
            restDurationSeconds = exercise.restDurationSeconds,
            sets = mapSets(entry.sets, exercise.id, root.id),
        )
    }
    return validateRoutineInputModel(
        RoutineInputModel(
            id = root.id,
            name = root.name,
            isDraft = root.isDraft,
            exercises = entries,
        ),
    )
}

/**
 * Maps local details to a canonical record, but only when an explicit positive
 * server revision is supplied. Display names come from the loaded details.
 */
fun RoutineDetails.toRoutineRecord(revision: Long): RoutineRecordModel {
    val root = routine
    if (revision < 1) throw SyncModelException("Canonical record requires a positive revision")
    if (root.deletedAtMillis != null) throw SyncModelException("Deleted routines cannot map to records")
    val entries = exercises.map { entry ->
        val exercise = entry.routineExercise
        if (exercise.routineId != root.id) {
            throw SyncModelException("Exercise ${exercise.id} does not belong to routine ${root.id}")
        }
        RoutineExerciseModel(
            id = exercise.id,
            exerciseId = exercise.exerciseId,
            position = exercise.position,
            restDurationSeconds = exercise.restDurationSeconds,
            sets = mapSets(entry.sets, exercise.id, root.id),
            exerciseName = exercise.exerciseName,
        )
    }
    return validateRoutineRecordModel(
        RoutineRecordModel(
            id = root.id,
            name = root.name,
            isDraft = root.isDraft,
            revision = revision,
            exercises = entries,
        ),
    )
}

/**
 * Flattens a validated canonical record to Room rows. The caller supplies the owning
 * account and local generation explicitly; rows carry synced metadata and the
 * canonical revision. Display names are not written anywhere.
 */
fun RoutineRecordModel.toRoomAggregate(accountId: String, localGeneration: Long): RoutineRoomAggregate {
    if (accountId.isBlank()) throw SyncModelException("Room aggregate requires a nonblank accountId")
    if (localGeneration < 0) throw SyncModelException("Room aggregate requires localGeneration >= 0")
    val record = validateRoutineRecordModel(this)
    val routine = RoutineEntity(
        id = record.id,
        name = record.name,
        isDraft = record.isDraft,
        accountId = accountId,
        serverRevision = record.revision,
        localGeneration = localGeneration,
        syncState = "synced",
        deletedAtMillis = null,
    )
    val exercises = record.exercises.map { entry ->
        RoutineExerciseEntity(
            id = entry.id,
            routineId = record.id,
            exerciseId = entry.exerciseId,
            position = entry.position,
            restDurationSeconds = entry.restDurationSeconds,
        )
    }
    val sets = record.exercises.flatMap { entry ->
        entry.sets.map { set ->
            RoutineSetEntity(
                id = set.id,
                routineExerciseId = entry.id,
                position = set.position,
                repetitions = set.repetitions,
                weightKg = set.weightKg,
            )
        }
    }
    return RoutineRoomAggregate(routine, exercises, sets)
}
