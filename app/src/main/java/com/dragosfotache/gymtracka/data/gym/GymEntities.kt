package com.dragosfotache.gymtracka.data.gym

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "muscle_groups",
)
data class MuscleGroupEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "slug") val slug: String,
    @ColumnInfo(name = "name") val name: String,
)

@Entity(tableName = "equipment")
data class EquipmentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "usage_type") val usageType: String?,
)

@Entity(
    tableName = "exercises",
    indices = [Index(value = ["account_id", "deleted_at_millis"])],
)
data class ExerciseEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "difficulty_level") val difficultyLevel: String?,
    @ColumnInfo(name = "force_type") val forceType: String?,
    @ColumnInfo(name = "mechanics") val mechanics: String?,
    @ColumnInfo(name = "category") val category: String?,
    @ColumnInfo(name = "instructions") val instructions: List<String>?,
    @ColumnInfo(name = "is_user_created", defaultValue = "0") val isUserCreated: Boolean = false,
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    @ColumnInfo(name = "server_revision") val serverRevision: Long? = null,
    @ColumnInfo(name = "local_generation", defaultValue = "0") val localGeneration: Long = 0,
    @ColumnInfo(name = "sync_state", defaultValue = "'local_only'") val syncState: String = "local_only",
    @ColumnInfo(name = "deleted_at_millis") val deletedAtMillis: Long? = null,
)

@Entity(
    tableName = "exercise_muscle_groups",
    primaryKeys = ["exercise_id", "muscle_group_id", "type"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
        ),
        ForeignKey(
            entity = MuscleGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["muscle_group_id"],
        ),
    ],
)
data class ExerciseMuscleGroupEntity(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "muscle_group_id") val muscleGroupId: String,
    /** `primary`, `secondary`, or `tertiary`. */
    @ColumnInfo(name = "type") val type: String,
)

@Entity(
    tableName = "exercise_equipment",
    primaryKeys = ["exercise_id", "equipment_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
        ),
        ForeignKey(
            entity = EquipmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["equipment_id"],
        ),
    ],
)
data class ExerciseEquipmentEntity(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "equipment_id") val equipmentId: String,
    @ColumnInfo(name = "num_items") val numberOfItems: Int?,
)
