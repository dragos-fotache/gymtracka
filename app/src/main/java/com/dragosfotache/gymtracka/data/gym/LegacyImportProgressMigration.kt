package com.dragosfotache.gymtracka.data.gym

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Additive data-preserving 13→14 migration for private import delivery.
 * Creates dedicated progress/acceptance tables; local capture journal/entries stay
 * immutable. Valid captured local capture rows initialize the current-lineage anchor
 * to their recorded PREPARING revision/generation/operation; blocked and
 * no-journal rows gain no progress row. No consent/receipt is fabricated.
 */
internal object LegacyImportProgressMigration : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_legacy_import_progress` (
                `id` INTEGER NOT NULL,
                `room_revision` INTEGER NOT NULL,
                `room_session_generation` INTEGER NOT NULL,
                `room_phase` TEXT NOT NULL,
                `operation_id` TEXT,
                `active_consent_mutation_id` TEXT,
                PRIMARY KEY(`id`)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_legacy_import_acceptances` (
                `ordinal` INTEGER NOT NULL,
                `mutation_id` TEXT NOT NULL,
                `batch_json` TEXT NOT NULL,
                PRIMARY KEY(`ordinal`)
            )""",
        )
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_legacy_import_acceptances_mutation_id`
                ON `sync_legacy_import_acceptances` (`mutation_id`)""",
        )
        db.query(
            """SELECT room_revision, room_session_generation, operation_id, phase
                FROM sync_legacy_import WHERE id = 1""",
        ).use { c ->
            if (c.count == 0) return
            if (c.count != 1) return
            c.moveToFirst()
            if (c.getType(3) != android.database.Cursor.FIELD_TYPE_STRING) return
            if (c.getString(3) != "captured") return
            if (c.getType(0) != android.database.Cursor.FIELD_TYPE_INTEGER) return
            if (c.getType(1) != android.database.Cursor.FIELD_TYPE_INTEGER) return
            if (c.getType(2) != android.database.Cursor.FIELD_TYPE_STRING) return
            val revision = c.getLong(0)
            val generation = c.getLong(1)
            val operationId = c.getString(2)
            if (revision < 0L || generation < 0L) return
            db.execSQL(
                """INSERT OR ABORT INTO `sync_legacy_import_progress`
                    (`id`, `room_revision`, `room_session_generation`, `room_phase`,
                     `operation_id`, `active_consent_mutation_id`)
                    VALUES (1, ?, ?, 'preparing', ?, NULL)""",
                arrayOf<Any>(revision, generation, operationId),
            )
        }
    }
}
