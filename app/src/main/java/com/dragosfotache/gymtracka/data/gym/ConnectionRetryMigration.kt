package com.dragosfotache.gymtracka.data.gym

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing retry deadlines may contain service hints, so migration never makes them resettable. */
internal object ConnectionRetryMigration : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sync_outbox` ADD COLUMN `retry_cause` TEXT NOT NULL DEFAULT 'unknown'")
    }
}
