package com.dragosfotache.gymtracka.data.gym

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A workout session. `status` is `active` or `finished`. */
@Entity(
    tableName = "workouts",
    indices = [Index(value = ["account_id", "deleted_at_millis"])],
)
data class WorkoutEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name", defaultValue = "''") val name: String,
    @ColumnInfo(name = "started_at_millis") val startedAtMillis: Long,
    @ColumnInfo(name = "finished_at_millis") val finishedAtMillis: Long? = null,
    @ColumnInfo(name = "status", defaultValue = "'active'") val status: String = "active",
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    @ColumnInfo(name = "server_revision") val serverRevision: Long? = null,
    @ColumnInfo(name = "local_generation", defaultValue = "0") val localGeneration: Long = 0,
    @ColumnInfo(name = "sync_state", defaultValue = "'local_only'") val syncState: String = "local_only",
    @ColumnInfo(name = "deleted_at_millis") val deletedAtMillis: Long? = null,
)

/** An exercise added to a particular workout, in display order. */
@Entity(
    tableName = "workout_exercises",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workout_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
        ),
    ],
    indices = [Index("workout_id"), Index("exercise_id")],
)
data class WorkoutExerciseEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workout_id") val workoutId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "notes", defaultValue = "''") val notes: String = "",
    @ColumnInfo(name = "rest_duration_seconds", defaultValue = "30") val restDurationSeconds: Int = 30,
)

/** One set for an exercise in a workout. Weight is stored in kilograms. */
@Entity(
    tableName = "workout_sets",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["workout_exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workout_exercise_id")],
)
data class WorkoutSetEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workout_exercise_id") val workoutExerciseId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "repetitions") val repetitions: Int? = null,
    @ColumnInfo(name = "weight_kg") val weightKg: Double? = null,
    @ColumnInfo(name = "is_completed", defaultValue = "0") val isCompleted: Boolean = false,
    /** Actual rest elapsed after this set, in seconds. */
    @ColumnInfo(name = "rest_seconds") val restSeconds: Int? = null,
)
