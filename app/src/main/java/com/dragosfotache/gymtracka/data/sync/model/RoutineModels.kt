package com.dragosfotache.gymtracka.data.sync.model

/**
 * Transport-independent routine aggregates. Mirrors OpenAPI RoutineInput/Routine,
 * RoutineExerciseInput/RoutineExercise, and RoutineSet. Routine sets never carry
 * completion or recorded rest. Input omits revision and display names; canonical
 * records carry a positive revision and display exercise names. Blank names and
 * empty children are valid shapes here: save/promotion rules belong to the service.
 */
data class RoutineSetModel(
    val id: String,
    val position: Int,
    val repetitions: Int?,
    val weightKg: Double?,
)

data class RoutineExerciseInputModel(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val restDurationSeconds: Int,
    val sets: List<RoutineSetModel>,
)

data class RoutineExerciseModel(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val restDurationSeconds: Int,
    val sets: List<RoutineSetModel>,
    /** Display name only; never a catalogue mutation input. */
    val exerciseName: String,
)

data class RoutineInputModel(
    val id: String,
    val name: String,
    val isDraft: Boolean,
    val exercises: List<RoutineExerciseInputModel>,
)

data class RoutineRecordModel(
    val id: String,
    val name: String,
    val isDraft: Boolean,
    val revision: Long,
    val exercises: List<RoutineExerciseModel>,
)
