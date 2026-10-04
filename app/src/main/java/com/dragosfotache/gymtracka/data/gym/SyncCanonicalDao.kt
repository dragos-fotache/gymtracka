package com.dragosfotache.gymtracka.data.gym

import androidx.room.Dao
import androidx.room.Query

/**
 * Storage/capture primitives, not a remote-apply or publication API. Mutating
 * primitives must be used together inside the caller's fenced Room transaction.
 * INSERT OR IGNORE + UPDATE supports SQLite on API 24 without modern UPSERT syntax.
 */
@Dao
abstract class SyncCanonicalDao {
    @Query(
        """SELECT * FROM sync_canonical_roots
            WHERE account_id = :accountId AND aggregate_type = :aggregateType
                AND aggregate_id = :aggregateId""",
    )
    abstract suspend fun getRoot(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
    ): SyncCanonicalRootEntity?

    @Query(
        """SELECT * FROM sync_canonical_roots WHERE account_id = :accountId
            ORDER BY aggregate_type, aggregate_id""",
    )
    abstract suspend fun getRootsForAccount(accountId: String): List<SyncCanonicalRootEntity>

    @Query("SELECT * FROM sync_account_cache WHERE account_id = :accountId")
    abstract suspend fun getAccountCache(accountId: String): SyncAccountCacheEntity?

    @Query(
        """INSERT OR IGNORE INTO sync_canonical_roots
            (account_id, aggregate_type, aggregate_id, local_generation_watermark)
            VALUES (:accountId, :aggregateType, :aggregateId, :generation)""",
    )
    internal abstract suspend fun insertReservation(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        generation: Long,
    )

    /** Only the watermark changes; canonical revision/body/state/epoch stay intact. */
    @Query(
        """UPDATE sync_canonical_roots SET local_generation_watermark = :generation
            WHERE account_id = :accountId AND aggregate_type = :aggregateType
                AND aggregate_id = :aggregateId AND local_generation_watermark <= :generation""",
    )
    internal abstract suspend fun advanceWatermark(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        generation: Long,
    ): Int

    @Query("INSERT OR IGNORE INTO sync_account_cache (account_id) VALUES (:accountId)")
    internal abstract suspend fun insertAccountCache(accountId: String)

    @Query("UPDATE sync_account_cache SET projection_dirty = 1 WHERE account_id = :accountId")
    internal abstract suspend fun markProjectionDirty(accountId: String): Int
}
