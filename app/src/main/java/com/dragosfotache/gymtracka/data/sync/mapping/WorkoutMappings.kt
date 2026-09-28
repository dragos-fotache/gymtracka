package com.dragosfotache.gymtracka.data.sync.mapping

import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutRecordModel

/**
 * Explicit mappings between Room workout aggregates and transport models.
 * No database writes, no cascades, no catalogue modifications, no state-holder
 * integration. IDs pass through unchanged; mappings never generate IDs.
 */

/** Flat Room rows produced from a canonical record. */
data class WorkoutRoomAggregate(
    val workout: WorkoutEntity,
    val exercises: List<WorkoutExerciseEntity>,
    val sets: List<WorkoutSetEntity>,
)

private fun mapSets(
    sets: List<WorkoutSetEntity>,
    parentExerciseId: String,
    rootId: String,
): List<WorkoutSetModel> {
    sets.forEach { set ->
        if (set.workoutExerciseId != parentExerciseId) {
            throw SyncModelException(
                "Set ${set.id} does not belong to workout exercise $parentExerciseId of workout $rootId",
            )
        }
    }
    return sets.map { set ->
        WorkoutSetModel(
            id = set.id,
            position = set.position,
            repetitions = set.repetitions,
            weightKg = set.weightKg,
            isCompleted = set.isCompleted,
            restSeconds = set.restSeconds,
        )
    }
}

/**
 * Maps local details to active upload input. Requires an active, non-deleted root
 * with no finished timestamp; finished history cannot be uploaded as input.
 * Children are validated and returned sorted by explicit position.
 */
fun WorkoutDetails.toWorkoutInput(): WorkoutInputModel {
    val root = workout
    if (root.status != "active") throw SyncModelException("Only active workouts map to upload input")
    if (root.finishedAtMillis != null) throw SyncModelException("Active upload input must not carry finishedAt")
    if (root.deletedAtMillis != null) throw SyncModelException("Deleted workouts cannot be uploaded")
    val entries = exercises.map { entry ->
        val exercise = entry.workoutExercise
        if (exercise.workoutId != root.id) {
            throw SyncModelException("Exercise ${exercise.id} does not belong to workout ${root.id}")
        }
        WorkoutExerciseInputModel(
            id = exercise.id,
            exerciseId = exercise.exerciseId,
            position = exercise.position,
            notes = exercise.notes,
            restDurationSeconds = exercise.restDurationSeconds,
            sets = mapSets(entry.sets, exercise.id, root.id),
        )
    }
    return validateWorkoutInputModel(
        WorkoutInputModel(
            id = root.id,
            name = root.name,
            status = root.status,
            startedAtMillis = root.startedAtMillis,
            exercises = entries,
        ),
    )
}

/**
 * Maps local details to a canonical record, but only when an explicit positive
 * server revision is supplied. Display names come from the loaded details.
 */
fun WorkoutDetails.toWorkoutRecord(revision: Long): WorkoutRecordModel {
    val root = workout
    if (revision < 1) throw SyncModelException("Canonical record requires a positive revision")
    if (root.deletedAtMillis != null) throw SyncModelException("Deleted workouts cannot map to records")
    if (root.status != "active" && root.status != "finished") {
        throw SyncModelException("Workout record status must be 'active' or 'finished'")
    }
    val entries = exercises.map { entry ->
        val exercise = entry.workoutExercise
        if (exercise.workoutId != root.id) {
            throw SyncModelException("Exercise ${exercise.id} does not belong to workout ${root.id}")
        }
        WorkoutExerciseModel(
            id = exercise.id,
            exerciseId = exercise.exerciseId,
            position = exercise.position,
            notes = exercise.notes,
            restDurationSeconds = exercise.restDurationSeconds,
            sets = mapSets(entry.sets, exercise.id, root.id),
            exerciseName = exercise.exerciseName,
        )
    }
    return validateWorkoutRecordModel(
        WorkoutRecordModel(
            id = root.id,
            name = root.name,
            status = root.status,
            startedAtMillis = root.startedAtMillis,
            finishedAtMillis = root.finishedAtMillis,
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
fun WorkoutRecordModel.toRoomAggregate(accountId: String, localGeneration: Long): WorkoutRoomAggregate {
    if (accountId.isBlank()) throw SyncModelException("Room aggregate requires a nonblank accountId")
    if (localGeneration < 0) throw SyncModelException("Room aggregate requires localGeneration >= 0")
    val record = validateWorkoutRecordModel(this)
    val workout = WorkoutEntity(
        id = record.id,
        name = record.name,
        startedAtMillis = record.startedAtMillis,
        finishedAtMillis = record.finishedAtMillis,
        status = record.status,
        accountId = accountId,
        serverRevision = record.revision,
        localGeneration = localGeneration,
        syncState = "synced",
        deletedAtMillis = null,
    )
    val exercises = record.exercises.map { entry ->
        WorkoutExerciseEntity(
            id = entry.id,
            workoutId = record.id,
            exerciseId = entry.exerciseId,
            position = entry.position,
            notes = entry.notes,
            restDurationSeconds = entry.restDurationSeconds,
        )
    }
    val sets = record.exercises.flatMap { entry ->
        entry.sets.map { set ->
            WorkoutSetEntity(
                id = set.id,
                workoutExerciseId = entry.id,
                position = set.position,
                repetitions = set.repetitions,
                weightKg = set.weightKg,
                isCompleted = set.isCompleted,
                restSeconds = set.restSeconds,
            )
        }
    }
    return WorkoutRoomAggregate(workout, exercises, sets)
}
