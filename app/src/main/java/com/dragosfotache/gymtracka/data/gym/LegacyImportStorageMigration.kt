package com.dragosfotache.gymtracka.data.gym

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the independent inert legacy-import journal and entry tables.
 * No previous value, byte, sequence or schema changes; both new tables start
 * empty for every upgrade path. Evidence tables carry no foreign keys to
 * domain/outbox so they survive later removal of those rows.
 */
internal object LegacyImportStorageMigration : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_legacy_import` (
                `id` INTEGER NOT NULL,
                `evidence_version` INTEGER NOT NULL,
                `service_origin` TEXT NOT NULL,
                `account_id` TEXT NOT NULL,
                `operation_id` TEXT NOT NULL,
                `source_revision` INTEGER NOT NULL,
                `source_session_generation` INTEGER NOT NULL,
                `room_revision` INTEGER NOT NULL,
                `room_session_generation` INTEGER NOT NULL,
                `phase` TEXT NOT NULL,
                `source_json` TEXT NOT NULL,
                `import_count` INTEGER NOT NULL,
                `error_code` TEXT,
                PRIMARY KEY(`id`)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_legacy_import_entries` (
                `ordinal` INTEGER NOT NULL,
                `aggregate_type` TEXT NOT NULL,
                `aggregate_id` TEXT NOT NULL,
                `mutation_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `local_generation` INTEGER NOT NULL,
                `payload_version` INTEGER NOT NULL,
                `payload_json` TEXT NOT NULL,
                PRIMARY KEY(`ordinal`)
            )""",
        )
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_legacy_import_entries_mutation_id`
                ON `sync_legacy_import_entries` (`mutation_id`)""",
        )
        db.execSQL(
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_legacy_import_entries_sequence`
                ON `sync_legacy_import_entries` (`sequence`)""",
        )
    }
}
