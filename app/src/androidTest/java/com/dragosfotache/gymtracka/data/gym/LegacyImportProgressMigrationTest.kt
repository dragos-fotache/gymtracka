package com.dragosfotache.gymtracka.data.gym

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks additive 13 -> 14 import-progress/receipt migration with literal v13 journal and entry
 * rows. Prior rows, request bytes and sequence allocation survive; lineage is initialized without
 * fabricated consent or acceptance.
 */
@RunWith(AndroidJUnit4::class)
class LegacyImportProgressMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migrationHelper = MigrationTestHelper(instrumentation, GymDatabase::class.java)

    @Test
    fun capturedVersionThirteen_initializesPreparingProgressWithNoFabricatedReceipts() {
        val databaseName = "migration-captured-13-to-14.db"
        instrumentation.targetContext.deleteDatabase(databaseName)
        var beforeJournal = emptyList<List<String?>>()
        var beforeEntries = emptyList<List<String?>>()
        migrationHelper.createDatabase(databaseName, 13).use { database ->
            seedCapturedVersionThirteen(database)
            beforeJournal = snapshotRows(database, JOURNAL_SQL, 13)
            beforeEntries = snapshotRows(database, ENTRIES_SQL, 8)
            assertEquals(1, beforeJournal.size)
            assertEquals(2, beforeEntries.size)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            *GymDatabase.MIGRATIONS,
        ).use { database ->
            // Immutable local capture evidence bytes preserved exactly.
            assertEquals(beforeJournal, snapshotRows(database, JOURNAL_SQL, 13))
            assertEquals(beforeEntries, snapshotRows(database, ENTRIES_SQL, 8))
            // Progress anchor initialized from the captured journal's recorded
            // PREPARING lineage: room_revision/session_generation/operation.
            val progress = snapshotRows(database, PROGRESS_SQL, 6)
            assertEquals(1, progress.size)
            val row = progress.single()
            assertEquals("1", row[0])
            assertEquals("1", row[1])
            assertEquals("1", row[2])
            assertEquals("preparing", row[3])
            assertEquals(CAPTURED_OPERATION, row[4])
            assertEquals(null, row[5])
            // No fabricated acceptance receipts or consent.
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import_acceptances", 3).size)
        }
    }

    @Test
    fun blockedVersionThirteen_gainsNoProgressRowAndNoReceipts() {
        val databaseName = "migration-blocked-13-to-14.db"
        instrumentation.targetContext.deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 13).use { database ->
            seedBlockedVersionThirteen(database)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            *GymDatabase.MIGRATIONS,
        ).use { database ->
            assertEquals(1, snapshotRows(database, JOURNAL_SQL, 13).size)
            assertEquals(0, snapshotRows(database, ENTRIES_SQL, 8).size)
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import_progress", 6).size)
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import_acceptances", 3).size)
        }
    }

    @Test
    fun emptyVersionThirteen_gainsNoProgressOrReceiptRows() {
        val databaseName = "migration-empty-13-to-14.db"
        instrumentation.targetContext.deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 13).use { }
        migrationHelper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            *GymDatabase.MIGRATIONS,
        ).use { database ->
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import", 13).size)
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import_entries", 8).size)
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import_progress", 6).size)
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import_acceptances", 3).size)
            database.query("SELECT sql FROM sqlite_master WHERE name = 'sync_legacy_import_progress'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val sql = cursor.getString(0)
                for (column in listOf("room_revision", "room_session_generation", "room_phase", "operation_id", "active_consent_mutation_id")) {
                    assertTrue("progress missing <$column> in <$sql>", sql.contains(column))
                }
            }
            database.query("SELECT sql FROM sqlite_master WHERE name = 'sync_legacy_import_acceptances'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val sql = cursor.getString(0)
                for (column in listOf("ordinal", "mutation_id", "batch_json")) {
                    assertTrue("acceptances missing <$column> in <$sql>", sql.contains(column))
                }
            }
        }
    }

    private fun snapshotRows(
        database: SupportSQLiteDatabase,
        sql: String,
        columns: Int,
    ): List<List<String?>> =
        database.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun seedCapturedVersionThirteen(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO sync_legacy_import (id, evidence_version, service_origin, account_id, operation_id," +
                " source_revision, source_session_generation, room_revision, room_session_generation," +
                " phase, source_json, import_count, error_code)" +
                " VALUES (1, 1, '$CAPTURED_ORIGIN', '$CAPTURED_ACCOUNT', '$CAPTURED_OPERATION'," +
                " 0, 0, 1, 1, 'captured', '{\"seed\":\"import-captured\"}', 2, NULL)",
        )
        database.execSQL(
            "INSERT INTO sync_legacy_import_entries (ordinal, aggregate_type, aggregate_id, mutation_id," +
                " sequence, local_generation, payload_version, payload_json)" +
                " VALUES (0, 'workout', '$ENTRY_WORKOUT', '$ENTRY_MUTATION_1', 10, 2, 1, '{\"seed\":\"entry-0\"}')",
        )
        database.execSQL(
            "INSERT INTO sync_legacy_import_entries (ordinal, aggregate_type, aggregate_id, mutation_id," +
                " sequence, local_generation, payload_version, payload_json)" +
                " VALUES (1, 'routine', '$ENTRY_ROUTINE', '$ENTRY_MUTATION_2', 11, 1, 1, '{\"seed\":\"entry-1\"}')",
        )
    }

    private fun seedBlockedVersionThirteen(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO sync_legacy_import (id, evidence_version, service_origin, account_id, operation_id," +
                " source_revision, source_session_generation, room_revision, room_session_generation," +
                " phase, source_json, import_count, error_code)" +
                " VALUES (1, 1, '$CAPTURED_ORIGIN', '$CAPTURED_ACCOUNT', '$CAPTURED_OPERATION'," +
                " 0, 0, 0, 0, 'blocked', '{\"seed\":\"import-blocked\"}', 0, 'invalid_source')",
        )
    }

    private companion object {
        const val CAPTURED_ORIGIN = "https://sync-import.example.test:443"
        const val CAPTURED_ACCOUNT = "c13b0001-1111-4111-8111-111111111111"
        const val CAPTURED_OPERATION = "d13b0001-1111-4111-8111-111111111111"
        const val ENTRY_WORKOUT = "e13b0001-1111-4111-8111-111111111111"
        const val ENTRY_ROUTINE = "e13b0002-2222-4222-8222-222222222222"
        const val ENTRY_MUTATION_1 = "f13b0001-1111-4111-8111-111111111111"
        const val ENTRY_MUTATION_2 = "f13b0002-2222-4222-8222-222222222222"
        const val JOURNAL_SQL =
            "SELECT id, evidence_version, service_origin, account_id, operation_id, source_revision," +
                " source_session_generation, room_revision, room_session_generation, phase," +
                " source_json, import_count, error_code FROM sync_legacy_import ORDER BY id"
        const val ENTRIES_SQL =
            "SELECT ordinal, aggregate_type, aggregate_id, mutation_id, sequence, local_generation," +
                " payload_version, payload_json FROM sync_legacy_import_entries ORDER BY ordinal"
        const val PROGRESS_SQL =
            "SELECT id, room_revision, room_session_generation, room_phase, operation_id," +
                " active_consent_mutation_id FROM sync_legacy_import_progress ORDER BY id"
    }
}
