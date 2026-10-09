package com.dragosfotache.gymtracka.data.gym

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Narrow storage primitives for the R13a legacy-import journal and entries.
 * Raw observation queries return open [Cursor] rows so the store can inspect
 * exact SQLite storage types without Room coercion; the caller closes them.
 * Association updates bind local-only roots to the service-confirmed target
 * with exact affected-count fencing; the caller rechecks post-write metadata.
 * Existence checks support the fresh-storage foreign-evidence refusal.
 * Never app-facing activation.
 */
@Dao
interface SyncLegacyImportDao {
    @Query(
        """SELECT id, evidence_version, service_origin, account_id, operation_id,
            source_revision, source_session_generation, room_revision,
            room_session_generation, phase, source_json, import_count, error_code
            FROM sync_legacy_import""",
    )
    fun observeImportRows(): Cursor

    @Query(
        """SELECT ordinal, aggregate_type, aggregate_id, mutation_id, sequence,
            local_generation, payload_version, payload_json
            FROM sync_legacy_import_entries ORDER BY ordinal""",
    )
    fun observeEntryRows(): Cursor

    @Query("SELECT EXISTS(SELECT 1 FROM sync_legacy_import WHERE id = 1)")
    suspend fun hasJournal(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_legacy_import_entries)")
    suspend fun hasEntries(): Boolean

    @Query("SELECT COUNT(*) FROM sync_legacy_import")
    suspend fun journalRowCount(): Long

    @Query("SELECT COUNT(*) FROM sync_legacy_import_entries")
    suspend fun entryRowCount(): Long

    @Query("SELECT * FROM sync_legacy_import WHERE id = 1")
    suspend fun getJournal(): SyncLegacyImportEntity?

    @Query("SELECT * FROM sync_legacy_import_entries ORDER BY ordinal")
    suspend fun getEntries(): List<SyncLegacyImportEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJournal(row: SyncLegacyImportEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(rows: List<SyncLegacyImportEntryEntity>)

    @Query(
        """UPDATE workouts SET account_id = :accountId
            WHERE id = :id AND account_id IS NULL""",
    )
    suspend fun associateWorkoutAccount(id: String, accountId: String): Int

    @Query(
        """UPDATE routines SET account_id = :accountId
            WHERE id = :id AND account_id IS NULL""",
    )
    suspend fun associateRoutineAccount(id: String, accountId: String): Int

    @Query(
        """UPDATE exercises SET account_id = :accountId
            WHERE id = :id AND account_id IS NULL AND is_user_created = 1""",
    )
    suspend fun associateCustomAccount(id: String, accountId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM workouts WHERE account_id IS NOT NULL)")
    suspend fun hasAccountWorkoutRoot(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM routines WHERE account_id IS NOT NULL)")
    suspend fun hasAccountRoutineRoot(): Boolean

    @Query(
        """SELECT EXISTS(SELECT 1 FROM exercises
            WHERE is_user_created = 1 AND account_id IS NOT NULL)""",
    )
    suspend fun hasAccountCustomRoot(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM exercises WHERE account_id IS NOT NULL)")
    suspend fun hasAccountAnyExerciseRoot(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox)")
    suspend fun hasAnyOutbox(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_state)")
    suspend fun hasAnySyncState(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_account_cache)")
    suspend fun hasAnyAccountCache(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_canonical_roots)")
    suspend fun hasAnyCanonicalRoots(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_snapshot_stages)")
    suspend fun hasAnySnapshotStage(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_snapshot_records)")
    suspend fun hasAnySnapshotRecord(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_snapshot_tokens)")
    suspend fun hasAnySnapshotToken(): Boolean

    @Query("SELECT MAX(sequence) FROM sync_outbox")
    suspend fun maxOutboxSequence(): Long?

    @Query("SELECT id FROM workouts WHERE account_id IS NULL ORDER BY id")
    suspend fun localWorkoutIds(): List<String>

    @Query("SELECT id FROM routines WHERE account_id IS NULL ORDER BY id")
    suspend fun localRoutineIds(): List<String>

    @Query(
        """SELECT id FROM exercises
            WHERE is_user_created = 1 AND account_id IS NULL ORDER BY id""",
    )
    suspend fun localCustomIds(): List<String>
}
