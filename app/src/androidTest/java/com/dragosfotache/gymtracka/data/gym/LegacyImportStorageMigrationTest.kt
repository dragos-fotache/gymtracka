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
 * R13a independent QA: additive 12 -> 13 legacy-import storage migration.
 *
 * Derived ONLY from docs/archive/remaining-work/R13a-task.md frozen source/schema13
 * table contract, exported schemas 1-12 column facts and the allowed
 * SessionAuthority/Snapshot storage-migration harness pattern. No production
 * declarations/diffs, new schema13 implementation, Worker work or decompiled
 * classes inspected. Every seeded value is an independent literal.
 *
 * The every-supported-start 1..12 -> 13 loop and copied-asset empty-new-table
 * check live in [GymDatabaseMigrationTest]; here a populated v12
 * migrates to 13 with every prior table byte-identical, sqlite sequence
 * allocation preserved, and the two new legacy tables present but empty.
 */
@RunWith(AndroidJUnit4::class)
class LegacyImportStorageMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migrationHelper = MigrationTestHelper(instrumentation, GymDatabase::class.java)

    @Test
    fun populatedVersionTwelve_upgradesToThirteenWithLegacyTablesEmptyAndPriorBytesPreserved() {
        val databaseName = "migration-populated-12-to-13.db"
        instrumentation.targetContext.deleteDatabase(databaseName)
        val beforeRows = mutableMapOf<String, List<List<String?>>>()
        var beforeMaster = emptyList<List<String?>>()
        var beforeSequence = emptyList<List<String?>>()
        migrationHelper.createDatabase(databaseName, 12).use { database ->
            seedPopulatedVersionTwelve(database)
            beforeRows["outbox"] = snapshotRows(database, OUTBOX_SQL, 14)
            beforeRows["workouts"] = snapshotRows(database, WORKOUTS_SQL, 10)
            beforeRows["routines"] = snapshotRows(database, ROUTINES_SQL, 8)
            beforeRows["exercises"] = snapshotRows(database, EXERCISES_SQL, 14)
            beforeRows["workout_exercises"] = snapshotRows(database, WORKOUT_EXERCISES_SQL, 6)
            beforeRows["workout_sets"] = snapshotRows(database, WORKOUT_SETS_SQL, 7)
            beforeRows["routine_exercises"] = snapshotRows(database, ROUTINE_EXERCISES_SQL, 5)
            beforeRows["routine_sets"] = snapshotRows(database, ROUTINE_SETS_SQL, 5)
            beforeRows["exercise_muscle_groups"] = snapshotRows(database, EXERCISE_MUSCLE_GROUPS_SQL, 3)
            beforeRows["exercise_equipment"] = snapshotRows(database, EXERCISE_EQUIPMENT_SQL, 3)
            beforeRows["sync_state"] = snapshotRows(database, SYNC_STATE_SQL, 3)
            beforeRows["sync_device_state"] = snapshotRows(database, SYNC_DEVICE_STATE_SQL, 5)
            beforeRows["catalogue_state"] = snapshotRows(database, CATALOGUE_STATE_SQL, 2)
            beforeRows["muscle_groups"] = snapshotRows(database, MUSCLE_GROUPS_SQL, 3)
            beforeRows["equipment"] = snapshotRows(database, EQUIPMENT_SQL, 4)
            beforeRows["canonical_roots"] = snapshotRows(database, CANONICAL_ROOTS_SQL, 8)
            beforeRows["account_cache"] = snapshotRows(database, ACCOUNT_CACHE_SQL, 5)
            beforeRows["stages"] = snapshotRows(database, STAGES_SQL, 14)
            beforeRows["records"] = snapshotRows(database, RECORDS_SQL, 7)
            beforeRows["tokens"] = snapshotRows(database, TOKENS_SQL, 3)
            beforeRows["authority"] = snapshotRows(database, AUTHORITY_SQL, 7)
            beforeMaster = snapshotRows(
                database,
                "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                    "WHERE name != 'room_master_table' ORDER BY name",
                4,
            )
            beforeSequence = snapshotRows(database, "SELECT name, seq FROM sqlite_sequence ORDER BY name", 2)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            13,
            true,
            *GymDatabase.MIGRATIONS,
        ).use { database ->
            assertEquals(beforeRows["outbox"], snapshotRows(database, OUTBOX_SQL, 14))
            assertEquals(beforeRows["workouts"], snapshotRows(database, WORKOUTS_SQL, 10))
            assertEquals(beforeRows["routines"], snapshotRows(database, ROUTINES_SQL, 8))
            assertEquals(beforeRows["exercises"], snapshotRows(database, EXERCISES_SQL, 14))
            assertEquals(beforeRows["workout_exercises"], snapshotRows(database, WORKOUT_EXERCISES_SQL, 6))
            assertEquals(beforeRows["workout_sets"], snapshotRows(database, WORKOUT_SETS_SQL, 7))
            assertEquals(beforeRows["routine_exercises"], snapshotRows(database, ROUTINE_EXERCISES_SQL, 5))
            assertEquals(beforeRows["routine_sets"], snapshotRows(database, ROUTINE_SETS_SQL, 5))
            assertEquals(beforeRows["exercise_muscle_groups"], snapshotRows(database, EXERCISE_MUSCLE_GROUPS_SQL, 3))
            assertEquals(beforeRows["exercise_equipment"], snapshotRows(database, EXERCISE_EQUIPMENT_SQL, 3))
            assertEquals(beforeRows["sync_state"], snapshotRows(database, SYNC_STATE_SQL, 3))
            assertEquals(beforeRows["sync_device_state"], snapshotRows(database, SYNC_DEVICE_STATE_SQL, 5))
            assertEquals(beforeRows["catalogue_state"], snapshotRows(database, CATALOGUE_STATE_SQL, 2))
            assertEquals(beforeRows["muscle_groups"], snapshotRows(database, MUSCLE_GROUPS_SQL, 3))
            assertEquals(beforeRows["equipment"], snapshotRows(database, EQUIPMENT_SQL, 4))
            assertEquals(beforeRows["canonical_roots"], snapshotRows(database, CANONICAL_ROOTS_SQL, 8))
            assertEquals(beforeRows["account_cache"], snapshotRows(database, ACCOUNT_CACHE_SQL, 5))
            assertEquals(beforeRows["stages"], snapshotRows(database, STAGES_SQL, 14))
            assertEquals(beforeRows["records"], snapshotRows(database, RECORDS_SQL, 7))
            assertEquals(beforeRows["tokens"], snapshotRows(database, TOKENS_SQL, 3))
            assertEquals(beforeRows["authority"], snapshotRows(database, AUTHORITY_SQL, 7))
            assertEquals(beforeSequence, snapshotRows(database, "SELECT name, seq FROM sqlite_sequence ORDER BY name", 2))

            // Prior schema objects preserved; new tables/indexes carry the new tbl_names.
            val afterMaster = snapshotRows(
                database,
                "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                    "WHERE name != 'room_master_table' ORDER BY name",
                4,
            )
            val legacyNames = setOf("sync_legacy_import", "sync_legacy_import_entries")
            assertEquals(
                beforeMaster,
                afterMaster.filter { it[2] !in legacyNames },
            )
            val afterNames = afterMaster.map { it[1] }.toSet()
            assertTrue(afterNames.contains("sync_legacy_import"))
            assertTrue(afterNames.contains("sync_legacy_import_entries"))
            // Required UNIQUE indexes on entries mutation_id/sequence (tbl_name = entries).
            val entryIndexSql = afterMaster
                .filter { it[0] == "index" && it[2] == "sync_legacy_import_entries" }
                .map { it[3] ?: "" }
            assertTrue(
                "entries mutation_id unique index missing",
                entryIndexSql.any { it.contains("mutation_id") && it.contains("UNIQUE") },
            )
            assertTrue(
                "entries sequence unique index missing",
                entryIndexSql.any { it.contains("sequence") && it.contains("UNIQUE") },
            )
            // Next-sequence allocation continues past the preserved sqlite_sequence.
            val preservedOutboxSeq = beforeSequence.firstOrNull { it[0] == "sync_outbox" }?.get(1)?.toLong()
            if (preservedOutboxSeq != null) {
                database.execSQL(
                    "INSERT INTO sync_outbox (mutation_id, account_id, aggregate_type, aggregate_id," +
                        " mutation_type, local_generation, payload_version, payload_json)" +
                        " VALUES ('probe-mutation', '$POP_ACCOUNT_A', 'workout', '$POP_WORKOUT'," +
                        " 'aggregate.import', 9, 1, '{}')",
                )
                val allocated = snapshotRows(
                    database,
                    "SELECT sequence FROM sync_outbox WHERE mutation_id = 'probe-mutation'",
                    1,
                ).single()[0]!!.toLong()
                assertEquals(preservedOutboxSeq + 1, allocated)
                database.execSQL("DELETE FROM sync_outbox WHERE mutation_id = 'probe-mutation'")
            }

            // New legacy tables exist with contract columns and start empty.
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import", 13).size)
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import_entries", 8).size)
            database.query("SELECT sql FROM sqlite_master WHERE name = 'sync_legacy_import'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val sql = cursor.getString(0)
                for (column in listOf("evidence_version", "service_origin", "account_id", "operation_id", "source_revision", "source_session_generation", "room_revision", "room_session_generation", "phase", "source_json", "import_count", "error_code")) {
                    assertTrue("sync_legacy_import missing <$column> in <$sql>", sql.contains(column))
                }
            }
            database.query("SELECT sql FROM sqlite_master WHERE name = 'sync_legacy_import_entries'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val sql = cursor.getString(0)
                for (column in listOf("ordinal", "aggregate_type", "aggregate_id", "mutation_id", "sequence", "local_generation", "payload_version", "payload_json")) {
                    assertTrue("sync_legacy_import_entries missing <$column> in <$sql>", sql.contains(column))
                }
            }
        }
    }

    @Test
    fun emptyVersionTwelve_upgradesToThirteenWithLegacyTablesEmpty() {
        val databaseName = "migration-empty-12-to-13.db"
        instrumentation.targetContext.deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 12).use { }
        migrationHelper.runMigrationsAndValidate(
            databaseName,
            13,
            true,
            *GymDatabase.MIGRATIONS,
        ).use { database ->
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import", 13).size)
            assertEquals(0, snapshotRows(database, "SELECT * FROM sync_legacy_import_entries", 8).size)
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

    private fun seedPopulatedVersionTwelve(database: SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO muscle_groups (id, slug, name) VALUES ('muscle', 'muscle', 'Muscle')")
        database.execSQL(
            "INSERT INTO equipment (id, name, type, usage_type) VALUES ('equipment', 'Equipment', 'weight', NULL)",
        )
        database.execSQL(
            """INSERT INTO exercises
                (id, name, type, difficulty_level, force_type, mechanics, category,
                    instructions, is_user_created, account_id, server_revision,
                    local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_BUNDLED', 'Bundled Press', 'reps', NULL, NULL, NULL,
                    'strength', '["Step"]', 0, NULL, NULL, 0, 'local_only', NULL)""",
        )
        database.execSQL(
            """INSERT INTO exercises
                (id, name, type, difficulty_level, force_type, mechanics, category,
                    instructions, is_user_created, account_id, server_revision,
                    local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_CUSTOM', 'Owned Custom', 'reps', NULL, NULL, NULL,
                    'strength', '["Custom step"]', 1, NULL, NULL, 2, 'local_only', NULL)""",
        )
        database.execSQL(
            "INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type)" +
                " VALUES ('$POP_CUSTOM', 'muscle', 'primary')",
        )
        database.execSQL(
            "INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items)" +
                " VALUES ('$POP_CUSTOM', 'equipment', NULL)",
        )
        database.execSQL(
            """INSERT INTO workouts
                (id, name, started_at_millis, finished_at_millis, status,
                    account_id, server_revision, local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_WORKOUT', 'Local W', 4000, NULL, 'active',
                    NULL, NULL, 6, 'local_only', NULL)""",
        )
        database.execSQL(
            """INSERT INTO workout_exercises
                (id, workout_id, exercise_id, position, notes, rest_duration_seconds)
                VALUES ('we-local', '$POP_WORKOUT', '$POP_BUNDLED', 0, 'Keep', 75)""",
        )
        database.execSQL(
            """INSERT INTO workout_sets
                (id, workout_exercise_id, position, repetitions, weight_kg, is_completed, rest_seconds)
                VALUES ('ws-local', 'we-local', 0, 8, 70.0, 1, 60)""",
        )
        database.execSQL(
            """INSERT INTO routines
                (id, name, is_draft, account_id, server_revision,
                    local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_ROUTINE', 'Draft R', 1, NULL, NULL, 1, 'local_only', NULL)""",
        )
        database.execSQL(
            """INSERT INTO routine_exercises
                (id, routine_id, exercise_id, position, rest_duration_seconds)
                VALUES ('re-draft', '$POP_ROUTINE', '$POP_BUNDLED', 0, 30)""",
        )
        database.execSQL(
            """INSERT INTO routine_sets
                (id, routine_exercise_id, position, repetitions, weight_kg)
                VALUES ('rs-draft', 're-draft', 0, NULL, NULL)""",
        )
        database.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$POP_ACCOUNT_A', 'cursor-a', 7000)",
        )
        database.execSQL(
            "INSERT INTO sync_device_state" +
                " (id, active_account_id, cached_account_id, session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        database.execSQL("INSERT INTO catalogue_state (id, version) VALUES (1, NULL)")
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (10, '$POP_MUTATION', '$POP_ACCOUNT_A', 'workout', '$POP_WORKOUT'," +
                " 'workout.start', 4, 1, '{}', 'pending', 0, NULL, NULL, NULL)",
        )
        database.execSQL(
            "INSERT INTO sync_session_authority" +
                " (id, revision, session_generation, phase, service_origin, account_id, operation_id)" +
                " VALUES (1, 0, 0, 'signed_out', NULL, NULL, NULL)",
        )
    }

    private companion object {
        const val POP_ACCOUNT_A = "account-a"
        const val POP_WORKOUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val POP_ROUTINE = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val POP_CUSTOM = "11111111-1111-1111-1111-111111111111"
        const val POP_BUNDLED = "22222222-2222-2222-2222-222222222222"
        const val POP_MUTATION = "66666666-6666-6666-6666-666666666666"
        const val OUTBOX_SQL =
            "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id, mutation_type," +
                " local_generation, payload_version, payload_json, state, attempt_count," +
                " retry_after_millis, error_code, error_message FROM sync_outbox ORDER BY sequence"
        const val WORKOUTS_SQL =
            "SELECT id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis FROM workouts ORDER BY id"
        const val ROUTINES_SQL =
            "SELECT id, name, is_draft, account_id, server_revision," +
                " local_generation, sync_state, deleted_at_millis FROM routines ORDER BY id"
        const val EXERCISES_SQL =
            "SELECT id, name, type, difficulty_level, force_type, mechanics, category, instructions," +
                " is_user_created, account_id, server_revision, local_generation, sync_state," +
                " deleted_at_millis FROM exercises ORDER BY id"
        const val WORKOUT_EXERCISES_SQL =
            "SELECT id, workout_id, exercise_id, position, notes, rest_duration_seconds" +
                " FROM workout_exercises ORDER BY id"
        const val WORKOUT_SETS_SQL =
            "SELECT id, workout_exercise_id, position, repetitions, weight_kg, is_completed," +
                " rest_seconds FROM workout_sets ORDER BY id"
        const val ROUTINE_EXERCISES_SQL =
            "SELECT id, routine_id, exercise_id, position, rest_duration_seconds" +
                " FROM routine_exercises ORDER BY id"
        const val ROUTINE_SETS_SQL =
            "SELECT id, routine_exercise_id, position, repetitions, weight_kg FROM routine_sets ORDER BY id"
        const val EXERCISE_MUSCLE_GROUPS_SQL =
            "SELECT exercise_id, muscle_group_id, type FROM exercise_muscle_groups ORDER BY exercise_id"
        const val EXERCISE_EQUIPMENT_SQL =
            "SELECT exercise_id, equipment_id, num_items FROM exercise_equipment ORDER BY exercise_id"
        const val SYNC_STATE_SQL = "SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id"
        const val SYNC_DEVICE_STATE_SQL =
            "SELECT id, active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state"
        const val CATALOGUE_STATE_SQL = "SELECT id, version FROM catalogue_state"
        const val MUSCLE_GROUPS_SQL = "SELECT id, slug, name FROM muscle_groups ORDER BY id"
        const val EQUIPMENT_SQL = "SELECT id, name, type, usage_type FROM equipment ORDER BY id"
        const val CANONICAL_ROOTS_SQL =
            "SELECT account_id, aggregate_type, aggregate_id, server_revision, canonical_state," +
                " canonical_json, local_generation_watermark, cache_epoch" +
                " FROM sync_canonical_roots ORDER BY account_id, aggregate_type, aggregate_id"
        const val ACCOUNT_CACHE_SQL =
            "SELECT account_id, baseline_complete, cache_epoch, projection_dirty, materialized_epoch" +
                " FROM sync_account_cache ORDER BY account_id"
        const val STAGES_SQL =
            "SELECT stage_id, account_id, session_generation, cache_epoch, page_limit," +
                " source_state_present, source_cursor, source_last_success_millis," +
                " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                " page_count, record_count FROM sync_snapshot_stages ORDER BY stage_id"
        const val RECORDS_SQL =
            "SELECT stage_id, aggregate_type, aggregate_id, server_revision, canonical_json," +
                " page_number, record_position FROM sync_snapshot_records" +
                " ORDER BY stage_id, aggregate_type, aggregate_id"
        const val TOKENS_SQL =
            "SELECT stage_id, page_token, page_number FROM sync_snapshot_tokens" +
                " ORDER BY stage_id, page_number"
        const val AUTHORITY_SQL =
            "SELECT id, revision, session_generation, phase, service_origin, account_id, operation_id" +
                " FROM sync_session_authority ORDER BY id"
    }
}
