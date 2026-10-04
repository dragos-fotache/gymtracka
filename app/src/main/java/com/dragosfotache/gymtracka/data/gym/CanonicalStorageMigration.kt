package com.dragosfotache.gymtracka.data.gym

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow

/** Adds storage, not server observations. Existing requests are never rewritten. */
internal object CanonicalStorageMigration : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_canonical_roots` (
                `account_id` TEXT NOT NULL,
                `aggregate_type` TEXT NOT NULL,
                `aggregate_id` TEXT NOT NULL,
                `server_revision` INTEGER,
                `canonical_state` TEXT NOT NULL DEFAULT 'unobserved',
                `canonical_json` TEXT,
                `local_generation_watermark` INTEGER NOT NULL DEFAULT 0,
                `cache_epoch` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`account_id`, `aggregate_type`, `aggregate_id`)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_account_cache` (
                `account_id` TEXT NOT NULL,
                `baseline_complete` INTEGER NOT NULL DEFAULT 0,
                `cache_epoch` INTEGER NOT NULL DEFAULT 0,
                `projection_dirty` INTEGER NOT NULL DEFAULT 0,
                `materialized_epoch` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`account_id`)
            )""",
        )
        // Domain metadata is local intent, not a complete canonical server body.
        // Keep its revision in the original row, and seed only durable watermarks.
        db.execSQL(
            """INSERT INTO sync_canonical_roots
                (account_id, aggregate_type, aggregate_id, local_generation_watermark)
                SELECT account_id, aggregate_type, aggregate_id, MAX(local_generation)
                FROM (
                    SELECT account_id, 'workout' AS aggregate_type, id AS aggregate_id,
                        local_generation FROM workouts WHERE account_id IS NOT NULL
                    UNION ALL
                    SELECT account_id, 'routine', id, local_generation
                        FROM routines WHERE account_id IS NOT NULL
                    UNION ALL
                    SELECT account_id, 'customExercise', id, local_generation
                        FROM exercises WHERE account_id IS NOT NULL AND is_user_created = 1
                    UNION ALL
                    SELECT account_id, aggregate_type, aggregate_id, local_generation
                        FROM sync_outbox
                        WHERE aggregate_type IN ('workout', 'routine', 'customExercise')
                ) WHERE local_generation >= 0
                GROUP BY account_id, aggregate_type, aggregate_id""",
        )
        // Valid v1 wrappers can also retain effects whose domain rows are gone.
        // Unsupported/corrupt rows stay retained failures: never guess their extras.
        db.query("SELECT * FROM sync_outbox ORDER BY sequence").use { cursor ->
            while (cursor.moveToNext()) {
                val row = cursor.outboxRow()
                val request = try {
                    readDurableOutboxRow(row).request
                } catch (_: SyncModelException) {
                    continue
                } catch (_: UnsupportedDurableVersionException) {
                    continue
                }
                for (effect in request.affectedRoots) {
                    val args = arrayOf<Any>(
                        row.accountId, effect.aggregateType.wireValue, effect.aggregateId,
                        effect.localGeneration,
                    )
                    db.execSQL(
                        """INSERT OR IGNORE INTO sync_canonical_roots
                            (account_id, aggregate_type, aggregate_id, local_generation_watermark)
                            VALUES (?, ?, ?, ?)""",
                        args,
                    )
                    db.execSQL(
                        """UPDATE sync_canonical_roots
                            SET local_generation_watermark = MAX(local_generation_watermark, ?)
                            WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?""",
                        arrayOf<Any>(args[3], args[0], args[1], args[2]),
                    )
                }
            }
        }
        // Even an old cursor/active cache label cannot prove a canonical baseline.
        db.execSQL(
            """INSERT INTO sync_account_cache (account_id, projection_dirty)
                SELECT account_id, 1 FROM (
                    SELECT account_id FROM workouts WHERE account_id IS NOT NULL
                    UNION SELECT account_id FROM routines WHERE account_id IS NOT NULL
                    UNION SELECT account_id FROM exercises
                        WHERE account_id IS NOT NULL AND is_user_created = 1
                    UNION SELECT account_id FROM sync_outbox
                    UNION SELECT account_id FROM sync_state
                    UNION SELECT active_account_id FROM sync_device_state
                        WHERE active_account_id IS NOT NULL
                    UNION SELECT cached_account_id FROM sync_device_state
                        WHERE cached_account_id IS NOT NULL
                )""",
        )
    }

    private fun Cursor.outboxRow() = SyncOutboxEntity(
        sequence = getLong(getColumnIndexOrThrow("sequence")),
        mutationId = string("mutation_id"),
        accountId = string("account_id"),
        aggregateType = string("aggregate_type"),
        aggregateId = string("aggregate_id"),
        mutationType = string("mutation_type"),
        localGeneration = getLong(getColumnIndexOrThrow("local_generation")),
        payloadVersion = getInt(getColumnIndexOrThrow("payload_version")),
        payloadJson = string("payload_json"),
        state = string("state"),
        attemptCount = getInt(getColumnIndexOrThrow("attempt_count")),
        retryAfterMillis = nullableLong("retry_after_millis"),
        errorCode = nullableString("error_code"),
        errorMessage = nullableString("error_message"),
    )

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.nullableLong(column: String): Long? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
}
