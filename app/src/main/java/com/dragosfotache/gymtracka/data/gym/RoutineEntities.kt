package com.dragosfotache.gymtracka.data.gym

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A reusable workout template. Draft routines are hidden until explicitly saved. */
@Entity(
    tableName = "routines",
    indices = [Index(value = ["account_id", "deleted_at_millis"])],
)
data class RoutineEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "is_draft", defaultValue = "0") val isDraft: Boolean = false,
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    @ColumnInfo(name = "server_revision") val serverRevision: Long? = null,
    @ColumnInfo(name = "local_generation", defaultValue = "0") val localGeneration: Long = 0,
    @ColumnInfo(name = "sync_state", defaultValue = "'local_only'") val syncState: String = "local_only",
    @ColumnInfo(name = "deleted_at_millis") val deletedAtMillis: Long? = null,
)

@Entity(
    tableName = "routine_exercises",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
        ),
    ],
    indices = [Index("routine_id"), Index("exercise_id")],
)
data class RoutineExerciseEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "routine_id") val routineId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "rest_duration_seconds", defaultValue = "30") val restDurationSeconds: Int = 30,
)

@Entity(
    tableName = "routine_sets",
    foreignKeys = [
        ForeignKey(
            entity = RoutineExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["routine_exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routine_exercise_id")],
)
data class RoutineSetEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "routine_exercise_id") val routineExerciseId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "repetitions") val repetitions: Int? = null,
    @ColumnInfo(name = "weight_kg") val weightKg: Double? = null,
)
