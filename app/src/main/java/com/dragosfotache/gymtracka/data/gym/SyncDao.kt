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
     * state and attempt count change, except an explicitly authorized connection retry
     * also clears its local deadline/cause. Identity and payload stay untouched. The
     * expected predicates pin the update to the head read in the same transaction.
     * Callers must verify the affected count is exactly one.
     */
    @Query(
        """UPDATE sync_outbox SET state = 'frozen', attempt_count = :newAttemptCount,
                retry_after_millis = CASE WHEN :resetConnectionDelay THEN NULL ELSE retry_after_millis END,
                retry_cause = CASE WHEN :resetConnectionDelay THEN 'unknown' ELSE retry_cause END
            WHERE mutation_id = :mutationId AND account_id = :accountId
                AND state = :expectedState AND attempt_count = :expectedAttempts""",
    )
    suspend fun freezeClaimedHead(
        mutationId: String,
        accountId: String,
        expectedState: String,
        expectedAttempts: Int,
        newAttemptCount: Int,
        resetConnectionDelay: Boolean = false,
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
                error_message = :errorMessage, retry_after_millis = :retryAfterMillis, retry_cause = :retryCause
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
        retryCause: String,
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

    /**
     * selected-account outbox size for presentation only. Counts every row
     * for the account, including frozen/rejected; foreign rows never match.
     * Additive scalar observation; no schema change.
     */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE account_id = :accountId")
    suspend fun countOutboxForAccount(accountId: String): Long

    /**
     * retained-error presence for presentation only. True when any selected
     * row is rejected or carries stored error presence; never exposes codes.
     * Additive scalar observation; no schema change.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE account_id = :accountId " +
            "AND (state = 'rejected' OR error_code IS NOT NULL OR error_message IS NOT NULL))",
    )
    suspend fun hasRetainedOutboxError(accountId: String): Boolean

    /** Advisory recovery candidates only; the facade validates durable ordinary rejection. */
    @Query("SELECT * FROM sync_outbox WHERE account_id = :accountId AND state = 'rejected' ORDER BY sequence")
    suspend fun getRejectedOutboxForAccount(accountId: String): List<SyncOutboxEntity>

    /**
     * selected-account last-success observation for presentation only.
     * Null when no row or null column; foreign rows never match. Negative
     * values are returned so callers fail closed, never normalized.
     * Additive scalar observation; no schema change.
     */
    @Query("SELECT last_success_millis FROM sync_state WHERE account_id = :accountId")
    suspend fun getLastSuccessMillis(accountId: String): Long?

    /**
     * selected-account last-success storage-type observation for presentation
     * only. SQLite INTEGER-affinity columns can hold nonnumeric TEXT/REAL/BLOB,
     * which a typed getter may coerce; callers must check this type first in the
     * same transaction and refuse non-integer storage. Null when no row matches.
     * Additive scalar observation; no schema change.
     */
    @Query("SELECT typeof(last_success_millis) FROM sync_state WHERE account_id = :accountId")
    suspend fun getLastSuccessStorageType(accountId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeviceState(state: SyncDeviceStateEntity)

    @Query("SELECT * FROM sync_device_state WHERE id = 1")
    suspend fun getDeviceState(): SyncDeviceStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogueState(state: CatalogueStateEntity)

    @Query("SELECT * FROM catalogue_state WHERE id = 1")
    suspend fun getCatalogueState(): CatalogueStateEntity?

    /**
     * removes exactly one captured ordinary row. Predicates pin the delete to the
     * full-row observation (sequence/state/attempts) made in the same fenced decision;
     * callers verify the affected count is exactly one per row and recheck post-write state.
     */
    @Query(
        """DELETE FROM sync_outbox
            WHERE mutation_id = :mutationId AND account_id = :accountId
                AND sequence = :sequence AND state = :expectedState
                AND attempt_count = :expectedAttempts""",
    )
    suspend fun deleteCapturedOrdinaryRow(
        mutationId: String,
        accountId: String,
        sequence: Long,
        expectedState: String,
        expectedAttempts: Int,
    ): Int

    /**
     * resets only the selected pull cursor to null after a confirmed ordinary discard.
     * Last-success is preserved untouched; callers verify it post-write. Never inserts a row.
     */
    @Query("UPDATE sync_state SET cursor = NULL WHERE account_id = :accountId")
    suspend fun nullCursorPreservingLastSuccess(accountId: String): Int
}
