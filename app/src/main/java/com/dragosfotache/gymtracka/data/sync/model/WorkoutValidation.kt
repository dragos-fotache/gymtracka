package com.dragosfotache.gymtracka.data.sync.model

import java.util.UUID

/**
 * Shape and domain invariants for workout aggregates. Used by the JSON codec and the
 * Room mappings alike. These are aggregate invariants only; server source-state and
 * authorization rules are validated by the later service layer, not here.
 */

internal fun requireWorkoutUuid(value: String, field: String): String {
    // UUID.fromString alone accepts abbreviated groups on the JVM; the wire contract
    // additionally requires canonical LOWERCASE 36-character IDs so case aliases can
    // never turn into separate Room rows. Invalid input is rejected, never remapped.
    if (!CANONICAL_UUID_SHAPE.matches(value)) {
        throw SyncModelException("$field is not a canonical lowercase UUID: $value")
    }
    try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw SyncModelException("$field is not a valid UUID: $value")
    }
    return value
}

private val CANONICAL_UUID_SHAPE =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private fun validateWorkoutSet(set: WorkoutSetModel, finished: Boolean, where: String) {
    requireWorkoutUuid(set.id, "$where sets[].id")
    if (set.position < 0) throw SyncModelException("$where set has negative position")
    val repetitions = set.repetitions
    if (repetitions != null && repetitions < 0) {
        throw SyncModelException("$where set has negative repetitions")
    }
    val weightKg = set.weightKg
    if (weightKg != null && (weightKg < 0.0 || !weightKg.isFinite())) {
        throw SyncModelException("$where set has invalid weightKg")
    }
    val restSeconds = set.restSeconds
    if (restSeconds != null && restSeconds < 0) {
        throw SyncModelException("$where set has negative restSeconds")
    }
    if (set.isCompleted && (repetitions == null || weightKg == null)) {
        throw SyncModelException("$where completed set requires repetitions and weightKg")
    }
    if (finished && !set.isCompleted) {
        throw SyncModelException("$where finished records contain only completed sets")
    }
}

private fun validateExerciseScalars(
    id: String,
    exerciseId: String,
    restDurationSeconds: Int,
    sets: List<WorkoutSetModel>,
    finished: Boolean,
    where: String,
) {
    requireWorkoutUuid(id, "$where id")
    requireWorkoutUuid(exerciseId, "$where exerciseId")
    if (restDurationSeconds < 0) throw SyncModelException("$where has negative restDurationSeconds")
    if (finished && sets.isEmpty()) throw SyncModelException("$where finished records contain no empty exercises")
}

/** Sorts sets by explicit position, validates them, and returns the sorted list. */
private fun validatedSets(
    sets: List<WorkoutSetModel>,
    finished: Boolean,
    where: String,
): List<WorkoutSetModel> {
    val sorted = sets.sortedBy { it.position }
    sorted.forEachIndexed { index, set ->
        if (set.position != index) throw SyncModelException("$where sets have a position gap or duplicate")
        validateWorkoutSet(set, finished, where)
    }
    return sorted
}

private fun checkPositions(positions: List<Int>, where: String) {
    positions.forEachIndexed { index, position ->
        if (position != index) throw SyncModelException("$where have a position gap or duplicate")
    }
}

/** Validates entries, sorts them by explicit position, and returns the sorted list. */
internal fun validateInputExercises(
    exercises: List<WorkoutExerciseInputModel>,
    finished: Boolean,
): List<WorkoutExerciseInputModel> {
    val ids = exercises.map { it.id }
    if (ids.toSet().size != ids.size) throw SyncModelException("Workout has duplicate exercise IDs")
    val sorted = exercises.sortedBy { it.position }
    checkPositions(sorted.map { it.position }, "Workout exercises")
    // workout_sets has one global primary key: set IDs are unique across the aggregate.
    val allSetIds = sorted.flatMap { entry -> entry.sets.map { it.id } }
    if (allSetIds.toSet().size != allSetIds.size) throw SyncModelException("Workout has duplicate set IDs")
    return sorted.map { entry ->
        validateExerciseScalars(
            entry.id,
            entry.exerciseId,
            entry.restDurationSeconds,
            entry.sets,
            finished,
            "Workout exercise",
        )
        entry.copy(sets = validatedSets(entry.sets, finished, "Workout exercise"))
    }
}

/** Validates entries, sorts them by explicit position, and returns the sorted list. */
internal fun validateRecordExercises(
    exercises: List<WorkoutExerciseModel>,
    finished: Boolean,
): List<WorkoutExerciseModel> {
    val ids = exercises.map { it.id }
    if (ids.toSet().size != ids.size) throw SyncModelException("Workout has duplicate exercise IDs")
    val sorted = exercises.sortedBy { it.position }
    checkPositions(sorted.map { it.position }, "Workout exercises")
    // workout_sets has one global primary key: set IDs are unique across the aggregate.
    val allSetIds = sorted.flatMap { entry -> entry.sets.map { it.id } }
    if (allSetIds.toSet().size != allSetIds.size) throw SyncModelException("Workout has duplicate set IDs")
    return sorted.map { entry ->
        validateExerciseScalars(
            entry.id,
            entry.exerciseId,
            entry.restDurationSeconds,
            entry.sets,
            finished,
            "Workout exercise",
        )
        entry.copy(sets = validatedSets(entry.sets, finished, "Workout exercise"))
    }
}

fun validateWorkoutInputModel(input: WorkoutInputModel): WorkoutInputModel {
    requireWorkoutUuid(input.id, "id")
    if (input.status != "active") throw SyncModelException("WorkoutInput status must be 'active'")
    requireWireMillis(input.startedAtMillis, "startedAt")
    val exercises = validateInputExercises(input.exercises, finished = false)
    return input.copy(exercises = exercises)
}

fun validateWorkoutRecordModel(record: WorkoutRecordModel): WorkoutRecordModel {
    requireWorkoutUuid(record.id, "id")
    if (record.status != "active" && record.status != "finished") {
        throw SyncModelException("Workout record status must be 'active' or 'finished'")
    }
    if (record.revision < 1) throw SyncModelException("Workout record revision must be positive")
    requireWireMillis(record.startedAtMillis, "startedAt")
    record.finishedAtMillis?.let { requireWireMillis(it, "finishedAt") }
    if (record.status == "active" && record.finishedAtMillis != null) {
        throw SyncModelException("Active workout record must not carry finishedAt")
    }
    if (record.status == "finished" && record.finishedAtMillis == null) {
        throw SyncModelException("Finished workout record requires finishedAt")
    }
    val finished = record.status == "finished"
    val exercises = validateRecordExercises(record.exercises, finished)
    if (finished && exercises.none { exercise -> exercise.sets.any { it.isCompleted } }) {
        throw SyncModelException("Finished workout record requires at least one completed set")
    }
    return record.copy(exercises = exercises)
}
