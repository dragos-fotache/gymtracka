package com.dragosfotache.gymtracka.data.gym

import androidx.room.Dao
import androidx.room.Query

/** Root sync metadata projected for capture decisions. */
data class SyncRootMetadata(
    val accountId: String?,
    val serverRevision: Long?,
    val localGeneration: Long,
    val syncState: String,
    val deletedAtMillis: Long?,
)

/** Root sync metadata plus the catalogue ownership flag for custom exercises. */
data class SyncExerciseMetadata(
    val accountId: String?,
    val serverRevision: Long?,
    val localGeneration: Long,
    val syncState: String,
    val deletedAtMillis: Long?,
    val isUserCreated: Boolean,
)

/**
 * Narrow metadata/lookup/tail primitives for fenced capture. Domain reads and
 * writes stay in the feature DAOs; this DAO only serves the capture transaction.
 */
@Dao
interface SyncCaptureDao {
    @Query(
        """SELECT account_id AS accountId, server_revision AS serverRevision,
            local_generation AS localGeneration, sync_state AS syncState,
            deleted_at_millis AS deletedAtMillis FROM workouts WHERE id = :id""",
    )
    suspend fun getWorkoutMetadata(id: String): SyncRootMetadata?

    @Query(
        """SELECT account_id AS accountId, server_revision AS serverRevision,
            local_generation AS localGeneration, sync_state AS syncState,
            deleted_at_millis AS deletedAtMillis FROM routines WHERE id = :id""",
    )
    suspend fun getRoutineMetadata(id: String): SyncRootMetadata?

    @Query(
        """SELECT account_id AS accountId, server_revision AS serverRevision,
            local_generation AS localGeneration, sync_state AS syncState,
            deleted_at_millis AS deletedAtMillis, is_user_created AS isUserCreated
            FROM exercises WHERE id = :id""",
    )
    suspend fun getExerciseMetadata(id: String): SyncExerciseMetadata?

    @Query(
        "UPDATE workouts SET local_generation = :generation, sync_state = :state WHERE id = :id",
    )
    suspend fun updateWorkoutMetadata(id: String, generation: Long, state: String)

    /**
     * Acknowledges one workout root at its settled generation. Only the server
     * revision and sync state change; generations and business data stay intact.
     * Callers must verify the affected count is exactly one.
     */
    @Query(
        "UPDATE workouts SET server_revision = :serverRevision, sync_state = :state WHERE id = :id",
    )
    suspend fun acknowledgeWorkoutMetadata(id: String, serverRevision: Long?, state: String): Int

    @Query(
        "UPDATE routines SET local_generation = :generation, sync_state = :state WHERE id = :id",
    )
    suspend fun updateRoutineMetadata(id: String, generation: Long, state: String)

    /**
     * Acknowledges one routine root at its settled generation. Only the server
     * revision and sync state change; generations and business data stay intact.
     * Callers must verify the affected count is exactly one.
     */
    @Query(
        "UPDATE routines SET server_revision = :serverRevision, sync_state = :state WHERE id = :id",
    )
    suspend fun acknowledgeRoutineMetadata(id: String, serverRevision: Long?, state: String): Int

    @Query(
        "UPDATE exercises SET local_generation = :generation, sync_state = :state WHERE id = :id",
    )
    suspend fun updateExerciseMetadata(id: String, generation: Long, state: String)

    /**
     * Acknowledges one custom exercise root at its settled generation. Only the
     * server revision and sync state change; generations and business data stay
     * intact. Callers must verify the affected count is exactly one.
     */
    @Query(
        "UPDATE exercises SET server_revision = :serverRevision, sync_state = :state WHERE id = :id",
    )
    suspend fun acknowledgeExerciseMetadata(id: String, serverRevision: Long?, state: String): Int

    @Query(
        "SELECT * FROM sync_outbox WHERE account_id = :accountId ORDER BY sequence DESC LIMIT 1",
    )
    suspend fun getOutboxTail(accountId: String): SyncOutboxEntity?

    /**
     * Replaces a never-sent pending tail payload in place. The sequence predicate pins
     * the update to the tail row read in the same transaction, the generation guard
     * requires a strictly newer generation, and the account-scoped subselect refuses
     * to touch the row when it is no longer the current tail. Callers must verify the
     * affected count is exactly one.
     */
    @Query(
        """UPDATE sync_outbox
            SET payload_json = :payloadJson, payload_version = :payloadVersion,
                local_generation = :localGeneration
            WHERE mutation_id = :mutationId AND account_id = :accountId
                AND sequence = :sequence AND state = 'pending' AND attempt_count = 0
                AND local_generation < :localGeneration
                AND sequence = (SELECT MAX(sequence) FROM sync_outbox WHERE account_id = :accountId)""",
    )
    suspend fun replaceTailPayload(
        mutationId: String,
        accountId: String,
        sequence: Long,
        payloadJson: String,
        payloadVersion: Int,
        localGeneration: Long,
    ): Int
}
