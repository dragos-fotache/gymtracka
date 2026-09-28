package com.dragosfotache.gymtracka.data.sync.model

/** Explicit failure for invalid sync aggregates, JSON, or timestamps. Never silently default. */
class SyncModelException(message: String) : IllegalArgumentException(message)

/**
 * One workout set. Mirrors OpenAPI WorkoutSet; shared by upload input and canonical records.
 * Weight is kilograms; restSeconds is recorded elapsed rest, distinct from the exercise's
 * configured restDurationSeconds.
 */
data class WorkoutSetModel(
    val id: String,
    val position: Int,
    val repetitions: Int?,
    val weightKg: Double?,
    val isCompleted: Boolean,
    val restSeconds: Int?,
)

/** One exercise entry for active upload input. Mirrors OpenAPI WorkoutExerciseInput. */
data class WorkoutExerciseInputModel(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val notes: String,
    val restDurationSeconds: Int,
    val sets: List<WorkoutSetModel>,
)

/** One exercise entry of a canonical server record. Mirrors OpenAPI WorkoutExercise. */
data class WorkoutExerciseModel(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val notes: String,
    val restDurationSeconds: Int,
    val sets: List<WorkoutSetModel>,
    /** Display name only; never a catalogue mutation input. */
    val exerciseName: String,
)

/**
 * Active upload input. Mirrors OpenAPI WorkoutInput: no revision, no display names,
 * no derived volume, no account metadata. Status is always `active`.
 */
data class WorkoutInputModel(
    val id: String,
    val name: String,
    val status: String,
    val startedAtMillis: Long,
    val exercises: List<WorkoutExerciseInputModel>,
)

/**
 * Canonical server workout record. Mirrors OpenAPI Workout: positive revision, display
 * exercise names, `active` or `finished` status, nullable finishedAt.
 */
data class WorkoutRecordModel(
    val id: String,
    val name: String,
    val status: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val revision: Long,
    val exercises: List<WorkoutExerciseModel>,
)
