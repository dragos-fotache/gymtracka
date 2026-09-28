package com.dragosfotache.gymtracka.data.gym

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Narrow storage primitives for the sync foundation tables. Not a sync engine. */
@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(entry: SyncOutboxEntity): Long

    @Query("SELECT * FROM sync_outbox WHERE account_id = :accountId ORDER BY sequence")
    suspend fun getOutboxForAccount(accountId: String): List<SyncOutboxEntity>

    @Query(
        "SELECT * FROM sync_outbox WHERE mutation_id = :mutationId AND account_id = :accountId",
    )
    suspend fun getOutboxMutation(mutationId: String, accountId: String): SyncOutboxEntity?

    /**
     * Replaces the payload of a never-sent pending mutation only, and only with a
     * strictly newer generation. Returns the affected row count: 1 when replaced, 0
     * when the mutation is missing, belongs to another account, is frozen/rejected,
     * or carries a stale/same generation.
     */
    @Query(
        """UPDATE sync_outbox
            SET payload_json = :payloadJson, payload_version = :payloadVersion,
                local_generation = :localGeneration
            WHERE mutation_id = :mutationId AND account_id = :accountId
                AND state = 'pending' AND local_generation < :localGeneration""",
    )
    suspend fun replacePendingPayload(
        mutationId: String,
        accountId: String,
        payloadJson: String,
        payloadVersion: Int,
        localGeneration: Long,
    ): Int

    /**
     * Freezes a never-sent pending mutation; a frozen payload cannot be replaced by
     * [replacePendingPayload]. Returns the affected row count.
     */
    @Query(
        """UPDATE sync_outbox SET state = 'frozen'
            WHERE mutation_id = :mutationId AND account_id = :accountId
                AND state = 'pending'""",
    )
    suspend fun freezePendingMutation(mutationId: String, accountId: String): Int

    @Query("DELETE FROM sync_outbox WHERE mutation_id = :mutationId AND account_id = :accountId")
    suspend fun deleteMutation(mutationId: String, accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE account_id = :accountId")
    suspend fun getSyncState(accountId: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeviceState(state: SyncDeviceStateEntity)

    @Query("SELECT * FROM sync_device_state WHERE id = 1")
    suspend fun getDeviceState(): SyncDeviceStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogueState(state: CatalogueStateEntity)

    @Query("SELECT * FROM catalogue_state WHERE id = 1")
    suspend fun getCatalogueState(): CatalogueStateEntity?
}
