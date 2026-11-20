package com.dragosfotache.gymtracka.data.gym

data class ExerciseHistory(
    val exerciseId: String,
    val exerciseName: String,
    val workouts: List<ExerciseHistoryWorkout>,
)

data class ExerciseHistoryWorkout(
    val workoutId: String,
    val workoutName: String,
    val performedAtMillis: Long,
    val notes: List<String>,
    val sets: List<ExerciseHistorySet>,
)

data class ExerciseHistorySet(
    val id: String,
    val weightKg: Double?,
    val repetitions: Int?,
)

/** Flat Room projection; the repository groups occurrences into one panel per workout. */
data class ExerciseHistorySetRow(
    val workoutId: String,
    val workoutName: String,
    val performedAtMillis: Long,
    val entryId: String,
    val notes: String,
    val setId: String,
    val weightKg: Double?,
    val repetitions: Int?,
)
