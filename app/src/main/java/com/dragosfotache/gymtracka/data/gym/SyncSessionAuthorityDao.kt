package com.dragosfotache.gymtracka.data.gym

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Narrow storage primitives for the inert R09a session-authority journal.
 * Raw observation queries return open [Cursor] rows so the store can inspect
 * exact SQLite storage types without Room coercion; the caller closes them.
 * Insert/update primitives carry exact expected-row predicates where the store
 * must prove affected-count fenced writes. Never app-facing activation.
 */
@Dao
interface SyncSessionAuthorityDao {
    @Query(
        """SELECT id, revision, session_generation, phase,
            service_origin, account_id, operation_id
            FROM sync_session_authority""",
    )
    fun observeAuthorityRows(): Cursor

    @Query(
        """SELECT id, active_account_id, cached_account_id,
            session_generation, cache_state
            FROM sync_device_state""",
    )
    fun observeDeviceRows(): Cursor

    @Query(
        """SELECT account_id, baseline_complete, cache_epoch,
            projection_dirty, materialized_epoch
            FROM sync_account_cache WHERE account_id = :accountId""",
    )
    fun observeAccountCache(accountId: String): Cursor

    @Query("SELECT EXISTS(SELECT 1 FROM workouts WHERE account_id IS NULL)")
    fun hasLocalOnlyWorkoutRoot(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM routines WHERE account_id IS NULL)")
    fun hasLocalOnlyRoutineRoot(): Boolean

    @Query(
        """SELECT EXISTS(SELECT 1 FROM exercises
            WHERE is_user_created = 1 AND account_id IS NULL)""",
    )
    fun hasLocalOnlyCustomExerciseRoot(): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuthority(row: SyncSessionAuthorityEntity): Long

    /**
     * Replaces the one journal row only when it still carries the exact
     * previously observed values. Returns the affected row count; the caller
     * must verify it is exactly one and recheck post-write rows before commit.
     */
    @Query(
        """UPDATE sync_session_authority
            SET revision = :newRevision, session_generation = :newGeneration,
                phase = :newPhase, service_origin = :newOrigin,
                account_id = :newAccountId, operation_id = :newOperationId
            WHERE id = 1 AND revision = :expectedRevision
                AND session_generation = :expectedGeneration
                AND phase = :expectedPhase
                AND service_origin IS :expectedOrigin
                AND account_id IS :expectedAccountId
                AND operation_id IS :expectedOperationId""",
    )
    suspend fun updateAuthority(
        newRevision: Long,
        newGeneration: Long,
        newPhase: String,
        newOrigin: String?,
        newAccountId: String?,
        newOperationId: String?,
        expectedRevision: Long,
        expectedGeneration: Long,
        expectedPhase: String,
        expectedOrigin: String?,
        expectedAccountId: String?,
        expectedOperationId: String?,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDeviceState(row: SyncDeviceStateEntity): Long

    /**
     * Replaces the singleton device row only when it still carries the exact
     * previously observed values. Returns the affected row count; the caller
     * must verify it is exactly one and recheck post-write rows before commit.
     */
    @Query(
        """UPDATE sync_device_state
            SET active_account_id = :newActiveAccountId,
                cached_account_id = :newCachedAccountId,
                session_generation = :newGeneration,
                cache_state = :newCacheState
            WHERE id = 1
                AND active_account_id IS :expectedActiveAccountId
                AND cached_account_id IS :expectedCachedAccountId
                AND session_generation = :expectedGeneration
                AND cache_state IS :expectedCacheState""",
    )
    suspend fun updateDeviceState(
        newActiveAccountId: String?,
        newCachedAccountId: String?,
        newGeneration: Long,
        newCacheState: String,
        expectedActiveAccountId: String?,
        expectedCachedAccountId: String?,
        expectedGeneration: Long,
        expectedCacheState: String?,
    ): Int
}
