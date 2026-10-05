package com.dragosfotache.gymtracka.data.gym

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Narrow storage primitives for frozen-snapshot staging. The three read methods are
 * the public durable-evidence surface; every mutator is internal and runs only
 * inside the caller's fenced Room transaction. No network, parsing policy or
 * cleanup authority lives here.
 */
@Dao
abstract class SyncSnapshotDao {
    @Query("SELECT * FROM sync_snapshot_stages WHERE account_id = :accountId")
    abstract suspend fun getStageForAccount(accountId: String): SyncSnapshotStageEntity?

    @Query(
        """SELECT * FROM sync_snapshot_records WHERE stage_id = :stageId
            ORDER BY page_number, record_position""",
    )
    abstract suspend fun getRecords(stageId: String): List<SyncSnapshotRecordEntity>

    @Query(
        """SELECT * FROM sync_snapshot_tokens WHERE stage_id = :stageId
            ORDER BY page_number""",
    )
    abstract suspend fun getTokens(stageId: String): List<SyncSnapshotTokenEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    internal abstract suspend fun insertStage(stage: SyncSnapshotStageEntity)

    @Query("SELECT * FROM sync_snapshot_stages WHERE stage_id = :stageId")
    internal abstract suspend fun getStage(stageId: String): SyncSnapshotStageEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    internal abstract suspend fun insertRecords(records: List<SyncSnapshotRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    internal abstract suspend fun insertToken(token: SyncSnapshotTokenEntity)

    /**
     * Advances exactly the admitted stage header: baseline, continuation, phase and
     * both counters change together. The expected predicates pin the update to the
     * header read in the same transaction. Callers must verify the affected count
     * is exactly one; zero means the header changed and is stale.
     */
    @Query(
        """UPDATE sync_snapshot_stages
            SET baseline_cursor = :baselineCursor, next_page_token = :nextPageToken,
                phase = :phase, page_count = :pageCount, record_count = :recordCount
            WHERE stage_id = :stageId AND account_id = :accountId
                AND phase = :expectedPhase AND page_count = :expectedPageCount
                AND record_count = :expectedRecordCount""",
    )
    internal abstract suspend fun advanceStage(
        stageId: String,
        accountId: String,
        expectedPhase: String,
        expectedPageCount: Long,
        expectedRecordCount: Long,
        baselineCursor: String?,
        nextPageToken: String?,
        phase: String,
        pageCount: Long,
        recordCount: Long,
    ): Int

    /**
     * Deletes exactly the admitted stage row; child records and tokens cascade.
     * Callers must verify the affected count is exactly one; zero means the header
     * changed and is stale. Never deletes across stages or accounts.
     */
    @Query(
        """DELETE FROM sync_snapshot_stages
            WHERE stage_id = :stageId AND account_id = :accountId""",
    )
    internal abstract suspend fun deleteStage(stageId: String, accountId: String): Int
}
