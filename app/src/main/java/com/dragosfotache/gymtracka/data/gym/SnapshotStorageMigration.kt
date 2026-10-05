package com.dragosfotache.gymtracka.data.gym

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the three inert frozen-snapshot staging tables. No previous value, byte,
 * sequence or schema changes; the new tables start empty for every upgrade path.
 * Index names follow the standard Room convention; the primary-key prefixes cover
 * the stage foreign keys.
 */
internal object SnapshotStorageMigration : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_snapshot_stages` (
                `stage_id` TEXT NOT NULL,
                `account_id` TEXT NOT NULL,
                `session_generation` INTEGER NOT NULL,
                `cache_epoch` INTEGER NOT NULL,
                `page_limit` INTEGER NOT NULL,
                `source_state_present` INTEGER NOT NULL,
                `source_cursor` TEXT,
                `source_last_success_millis` INTEGER,
                `source_baseline_complete` INTEGER NOT NULL,
                `baseline_cursor` TEXT,
                `next_page_token` TEXT,
                `phase` TEXT NOT NULL DEFAULT 'downloading',
                `page_count` INTEGER NOT NULL DEFAULT 0,
                `record_count` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`stage_id`)
            )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_snapshot_stages_account_id`" +
                " ON `sync_snapshot_stages` (`account_id`)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_snapshot_records` (
                `stage_id` TEXT NOT NULL,
                `aggregate_type` TEXT NOT NULL,
                `aggregate_id` TEXT NOT NULL,
                `server_revision` INTEGER NOT NULL,
                `canonical_json` TEXT NOT NULL,
                `page_number` INTEGER NOT NULL,
                `record_position` INTEGER NOT NULL,
                PRIMARY KEY(`stage_id`, `aggregate_type`, `aggregate_id`),
                FOREIGN KEY(`stage_id`) REFERENCES `sync_snapshot_stages`(`stage_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS" +
                " `index_sync_snapshot_records_stage_id_page_number_record_position`" +
                " ON `sync_snapshot_records` (`stage_id`, `page_number`, `record_position`)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_snapshot_tokens` (
                `stage_id` TEXT NOT NULL,
                `page_token` TEXT NOT NULL,
                `page_number` INTEGER NOT NULL,
                PRIMARY KEY(`stage_id`, `page_token`),
                FOREIGN KEY(`stage_id`) REFERENCES `sync_snapshot_stages`(`stage_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_snapshot_tokens_stage_id_page_number`" +
                " ON `sync_snapshot_tokens` (`stage_id`, `page_number`)",
        )
    }
}
