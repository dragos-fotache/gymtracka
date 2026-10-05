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
        "SELECT * FROM sync_outbox WHERE account_id = :accountId ORDER BY sequence LIMIT 1",
    )
    suspend fun getOutboxHead(accountId: String): SyncOutboxEntity?

    /**
     * Freezes one claimable head row and records one more admission. Only the
     * state and attempt count change; every other column stays untouched. The
     * expected predicates pin the update to the head read in the same transaction.
     * Callers must verify the affected count is exactly one.
     */
    @Query(
        """UPDATE sync_outbox SET state = 'frozen', attempt_count = :newAttemptCount
            WHERE mutation_id = :mutationId AND account_id = :accountId
                AND state = :expectedState AND attempt_count = :expectedAttempts""",
    )
    suspend fun freezeClaimedHead(
        mutationId: String,
        accountId: String,
        expectedState: String,
        expectedAttempts: Int,
        newAttemptCount: Int,
    ): Int

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

    /**
     * Records one push outcome/retry decision on the exact claimed head row. Only
     * the outcome bookkeeping columns change; identity, sequence, payload and
     * generation stay untouched. The expected predicates pin the update to the
     * head read in the same transaction. Callers must verify the affected count
     * is exactly one and recheck the expected post-write head before commit.
     */
    @Query(
        """UPDATE sync_outbox
            SET state = :newState, error_code = :errorCode,
                error_message = :errorMessage, retry_after_millis = :retryAfterMillis
            WHERE mutation_id = :mutationId AND account_id = :accountId
                AND state = :expectedState AND attempt_count = :expectedAttempts""",
    )
    suspend fun updatePushOutcome(
        mutationId: String,
        accountId: String,
        expectedState: String,
        expectedAttempts: Int,
        newState: String,
        errorCode: String?,
        errorMessage: String?,
        retryAfterMillis: Long?,
    ): Int

    @Query("DELETE FROM sync_outbox WHERE mutation_id = :mutationId AND account_id = :accountId")
    suspend fun deleteMutation(mutationId: String, accountId: String)

    /**
     * Removes exactly the acknowledged frozen head row. The expected predicates pin
     * the delete to the head verified in the same transaction. Callers must verify
     * the affected count is exactly one and the row is absent afterward.
     */
    @Query(
        """DELETE FROM sync_outbox
            WHERE mutation_id = :mutationId AND account_id = :accountId
                AND state = :expectedState AND attempt_count = :expectedAttempts""",
    )
    suspend fun deleteExactFrozenHead(
        mutationId: String,
        accountId: String,
        expectedState: String,
        expectedAttempts: Int,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE account_id = :accountId")
    suspend fun getSyncState(accountId: String): SyncStateEntity?

    /**
     * Advances only the pull cursor from the exact previously observed cursor to the
     * committed batch cursor. Never inserts a missing row; only the cursor column
     * changes and last-success stays untouched. Full row and baseline checks remain
     * the transaction caller's responsibility. Callers must verify the affected
     * count is exactly one and recheck the expected post-write row before commit.
     */
    @Query(
        """UPDATE sync_state SET cursor = :nextCursor
            WHERE account_id = :accountId AND cursor = :expectedCursor""",
    )
    suspend fun advancePullCursor(
        accountId: String,
        expectedCursor: String,
        nextCursor: String,
    ): Int

    /**
     * Records one valid-tail last-success observation on the exact previously observed
     * pull boundary. Only the last-success column changes; the row is never inserted or
     * replaced and the cursor never advances. The null-safe predicate pins the update to
     * the full row observed by the caller. Callers must verify the affected count is
     * exactly one and recheck the expected post-write row before commit.
     */
    @Query(
        """UPDATE sync_state SET last_success_millis = :nowMillis
            WHERE account_id = :accountId AND cursor = :expectedCursor
                AND last_success_millis IS :expectedLastSuccess""",
    )
    suspend fun updatePullLastSuccess(
        accountId: String,
        expectedCursor: String,
        expectedLastSuccess: Long?,
        nowMillis: Long,
    ): Int

    /**
     * Selected-account outbox presence observation for valid-tail bookkeeping. Reports
     * whether any row exists for the account without parsing, filtering, claiming or
     * updating any row. Foreign-account rows never match. This is not an admission
     * to send.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE account_id = :accountId)")
    suspend fun hasOutboxForAccount(accountId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeviceState(state: SyncDeviceStateEntity)

    @Query("SELECT * FROM sync_device_state WHERE id = 1")
    suspend fun getDeviceState(): SyncDeviceStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogueState(state: CatalogueStateEntity)

    @Query("SELECT * FROM catalogue_state WHERE id = 1")
    suspend fun getCatalogueState(): CatalogueStateEntity?
}
