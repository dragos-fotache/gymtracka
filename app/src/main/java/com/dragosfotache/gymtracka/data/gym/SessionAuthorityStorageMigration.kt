package com.dragosfotache.gymtracka.data.gym

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the independent inert session-authority journal table. No previous value,
 * byte, sequence or schema changes; the new table starts empty for every upgrade
 * path. Existing legacy active device rows are physically preserved but cannot
 * become recovered account authority merely through migration.
 */
internal object SessionAuthorityStorageMigration : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_session_authority` (
                `id` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `session_generation` INTEGER NOT NULL,
                `phase` TEXT NOT NULL,
                `service_origin` TEXT,
                `account_id` TEXT,
                `operation_id` TEXT,
                PRIMARY KEY(`id`)
            )""",
        )
    }
}
