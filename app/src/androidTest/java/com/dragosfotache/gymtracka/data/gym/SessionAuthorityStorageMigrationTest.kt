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
 * R09a independent QA: populated 11 -> 12 storage migration acceptance.
 *
 * Derived ONLY from docs/archive/remaining-work/R09a-task.md (additive
 * sync_session_authority, no fabricated authority) plus exported schemas 1-11
 * column facts and the existing SnapshotStorageMigrationTest harness pattern.
 * No production declarations/diffs, new schema12, Worker work or decompiled
 * classes inspected. Every seeded value is an independent literal; canonical
 * bodies are hand-authored bytes compared for exact preservation.
 *
 * The every-supported-start 1..11 -> 12 loop and copied-asset empty-new-table
 * check live in [GymDatabaseMigrationTest]; here a fully populated v11
 * migrates to 12 with every prior table byte-identical, sequence allocation
 * and FK/index schema preserved, and the single new authority table present
 * but empty.
 */
@RunWith(AndroidJUnit4::class)
class SessionAuthorityStorageMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migrationHelper = MigrationTestHelper(instrumentation, GymDatabase::class.java)

    @Test
    fun populatedVersionEleven_upgradesToTwelveWithAuthorityEmptyAndPriorBytesPreserved() {
        val databaseName = "migration-populated-11-to-12.db"
        instrumentation.targetContext.deleteDatabase(databaseName)
        val beforeRows = mutableMapOf<String, List<List<String?>>>()
        var beforeMaster = emptyList<List<String?>>()
        var beforeSequence = emptyList<List<String?>>()
        migrationHelper.createDatabase(databaseName, 11).use { database ->
            seedPopulatedVersionEleven(database)
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
            12,
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

            val afterMaster = snapshotRows(
                database,
                "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                    "WHERE name != 'room_master_table' ORDER BY name",
                4,
            )
            assertEquals(
                beforeMaster,
                afterMaster.filter { it[2] != "sync_session_authority" },
            )
            assertEquals(beforeSequence, snapshotRows(database, "SELECT name, seq FROM sqlite_sequence ORDER BY name", 2))
            assertPopulatedSpotChecks(database)
            assertAuthorityTableEmptyWithExpectedShape(database)
            database.execSQL(
                "INSERT INTO sync_outbox (mutation_id, account_id, aggregate_type, aggregate_id," +
                    " mutation_type, local_generation, payload_json)" +
                    " VALUES ('aaaaaaaa-aaaa-4111-8111-aaaaaaaaaaaa', '$POP_ACCOUNT_A', 'workout'," +
                    " '$POP_WORKOUT', 'workout.upsert', 9, '{}')",
            )
            assertEquals(
                61,
                database.query("SELECT seq FROM sqlite_sequence WHERE name = 'sync_outbox'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getInt(0)
                },
            )
        }
        instrumentation.targetContext.deleteDatabase(databaseName)
    }

    private fun assertPopulatedSpotChecks(database: SupportSQLiteDatabase) {
        // Retained legacy active device physically preserved, never adopted.
        database.query(
            "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(POP_ACCOUNT_A, cursor.getString(0))
            assertEquals(POP_ACCOUNT_A, cursor.getString(1))
            assertEquals(7L, cursor.getLong(2))
            assertEquals("local_only", cursor.getString(3))
        }
        // Corrupt/exact outbox bytes preserved.
        database.query(
            "SELECT payload_json FROM sync_outbox WHERE mutation_id = '$POP_MUTATION_CORRUPT'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{broken", cursor.getString(0))
        }
        database.query(
            "SELECT payload_json, payload_version FROM sync_outbox WHERE mutation_id = '$POP_MUTATION_UNSUPPORTED'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
        }
        // Nonempty stage preserved with exact cursor bytes.
        assertEquals(1, database.query("SELECT COUNT(*) FROM sync_snapshot_stages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        })
        database.query(
            "SELECT source_cursor FROM sync_snapshot_stages WHERE stage_id = 'stage-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("opaque-stage-cursor-a", cursor.getString(0))
        }
        assertEquals(1, database.query("SELECT COUNT(*) FROM sync_snapshot_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        })
        assertEquals(1, database.query("SELECT COUNT(*) FROM sync_snapshot_tokens").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        })
    }

    private fun assertAuthorityTableEmptyWithExpectedShape(database: SupportSQLiteDatabase) {
        assertEquals(0, database.query("SELECT COUNT(*) FROM sync_session_authority").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        })
        // Exact additive columns: id PK + revision/generation/phase + nullable
        // origin/account/operation, no domain credentials.
        database.query("PRAGMA table_info(sync_session_authority)").use { cursor ->
            val columns = buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getInt(3).toString(),
                            cursor.getString(4),
                            cursor.getInt(5).toString(),
                        ),
                    )
                }
            }
            assertEquals(
                listOf(
                    listOf("id", "INTEGER", "1", null, "1"),
                    listOf("revision", "INTEGER", "1", null, "0"),
                    listOf("session_generation", "INTEGER", "1", null, "0"),
                    listOf("phase", "TEXT", "1", null, "0"),
                    listOf("service_origin", "TEXT", "0", null, "0"),
                    listOf("account_id", "TEXT", "0", null, "0"),
                    listOf("operation_id", "TEXT", "0", null, "0"),
                ),
                columns,
            )
        }
        // Single-table authority: no FKs, no secondary indexes.
        database.query("PRAGMA foreign_key_list(sync_session_authority)").use { cursor ->
            assertTrue(!cursor.moveToFirst())
        }
        val indexNames = database.query(
            "SELECT name FROM sqlite_master WHERE type = 'index'" +
                " AND tbl_name = 'sync_session_authority' ORDER BY name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }.filterNot { it.startsWith("sqlite_autoindex_") }
        }
        assertEquals(emptyList<String>(), indexNames)
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

    private fun seedPopulatedVersionEleven(database: SupportSQLiteDatabase) {
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
                    'strength', '["Custom step"]', 1, '$POP_ACCOUNT_A', NULL,
                    2, 'pending', NULL)""",
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
                VALUES ('$POP_WORKOUT', 'Active W', 5000, NULL, 'active',
                    '$POP_ACCOUNT_A', 5, 3, 'pending', NULL)""",
        )
        database.execSQL(
            """INSERT INTO workout_exercises
                (id, workout_id, exercise_id, position, notes, rest_duration_seconds)
                VALUES ('we-active', '$POP_WORKOUT', '$POP_BUNDLED', 0, 'Keep', 75)""",
        )
        database.execSQL(
            """INSERT INTO workout_sets
                (id, workout_exercise_id, position, repetitions, weight_kg, is_completed, rest_seconds)
                VALUES ('ws-active', 'we-active', 0, 8, 70.0, 1, 60)""",
        )
        database.execSQL(
            """INSERT INTO workouts
                (id, name, started_at_millis, finished_at_millis, status,
                    account_id, server_revision, local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_TOMB', 'Tomb W', 4000, 4500, 'finished',
                    '$POP_ACCOUNT_A', NULL, 2, 'pending', 9000)""",
        )
        database.execSQL(
            """INSERT INTO workouts
                (id, name, started_at_millis, finished_at_millis, status,
                    account_id, server_revision, local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_FOREIGN', 'Foreign W', 4000, NULL, 'active',
                    '$POP_ACCOUNT_B', NULL, 4, 'pending', NULL)""",
        )
        database.execSQL(
            """INSERT INTO workouts
                (id, name, started_at_millis, finished_at_millis, status,
                    account_id, server_revision, local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_LOCAL', 'Local W', 4000, NULL, 'active',
                    NULL, NULL, 6, 'local_only', NULL)""",
        )

        database.execSQL(
            """INSERT INTO routines
                (id, name, is_draft, account_id, server_revision,
                    local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_ROUTINE', 'Draft R', 1, '$POP_ACCOUNT_A', NULL, 1, 'pending', NULL)""",
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
            """INSERT INTO routines
                (id, name, is_draft, account_id, server_revision,
                    local_generation, sync_state, deleted_at_millis)
                VALUES ('$POP_ROUTINE_TOMB', 'Tomb R', 0, '$POP_ACCOUNT_A', 3, 2, 'pending', 9000)""",
        )

        database.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$POP_ACCOUNT_A', 'opaque-stage-cursor-a', 7000)",
        )
        database.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$POP_ACCOUNT_B', 'cursor-b', NULL)",
        )
        // Retained legacy active device: preserved physically, never adopted.
        database.execSQL(
            "INSERT INTO sync_device_state" +
                " (id, active_account_id, cached_account_id, session_generation, cache_state)" +
                " VALUES (1, '$POP_ACCOUNT_A', '$POP_ACCOUNT_A', 7, 'local_only')",
        )
        database.execSQL("INSERT INTO catalogue_state (id, version) VALUES (1, 'catalogue-12')")

        database.execSQL(
            "INSERT INTO sync_canonical_roots (account_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_state, canonical_json," +
                " local_generation_watermark, cache_epoch)" +
                " VALUES ('$POP_ACCOUNT_A', 'workout', '$POP_WORKOUT', 7, 'upsert'," +
                " '$POP_WORKOUT_JSON', 4, 5)",
        )
        database.execSQL(
            "INSERT INTO sync_canonical_roots (account_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_state, canonical_json," +
                " local_generation_watermark, cache_epoch)" +
                " VALUES ('$POP_ACCOUNT_A', 'customExercise', '$POP_CUSTOM', NULL, 'unobserved'," +
                " NULL, 2, 0)",
        )
        database.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$POP_ACCOUNT_A', 1, 5, 0, 4)",
        )
        database.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$POP_ACCOUNT_B', 1, 2, 1, 1)",
        )

        database.execSQL(
            "INSERT INTO sync_snapshot_stages (stage_id, account_id, session_generation, cache_epoch," +
                " page_limit, source_state_present, source_cursor, source_last_success_millis," +
                " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                " page_count, record_count)" +
                " VALUES ('stage-1', '$POP_ACCOUNT_A', 7, 5, 50, 1, 'opaque-stage-cursor-a', 7000, 1," +
                " 'baseline', NULL, 'downloading', 1, 1)",
        )
        database.execSQL(
            "INSERT INTO sync_snapshot_records (stage_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_json, page_number, record_position)" +
                " VALUES ('stage-1', 'workout', '$POP_WORKOUT', 7, '$POP_WORKOUT_JSON', 0, 0)",
        )
        database.execSQL(
            "INSERT INTO sync_snapshot_tokens (stage_id, page_token, page_number)" +
                " VALUES ('stage-1', 'token-1', 0)",
        )

        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (10, '$POP_MUTATION_VALID', '$POP_ACCOUNT_A', 'workout', '$POP_WORKOUT'," +
                " 'workout.start', 4, 1, '{}', 'pending', 0, NULL, NULL, NULL)",
        )
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (20, '$POP_MUTATION_FROZEN', '$POP_ACCOUNT_A', 'routine', '$POP_ROUTINE'," +
                " 'routine.save', 2, 1, '{}', 'frozen', 1, NULL, NULL, NULL)",
        )
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (30, '$POP_MUTATION_REJECTED', '$POP_ACCOUNT_A', 'customExercise', '$POP_CUSTOM'," +
                " 'aggregate.delete', 2, 1, '{}', 'rejected', 1, NULL, 'gone', 'Gone')",
        )
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (40, '$POP_MUTATION_CORRUPT', '$POP_ACCOUNT_A', 'workout', '$POP_CORRUPT'," +
                " 'workout.upsert', 1, 1, '{broken', 'pending', 0, NULL, NULL, NULL)",
        )
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (50, '$POP_MUTATION_UNSUPPORTED', '$POP_ACCOUNT_A', 'routine', '$POP_UNSUPPORTED'," +
                " 'routine.upsert', 1, 2, '{}', 'pending', 0, NULL, NULL, NULL)",
        )
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (60, '$POP_MUTATION_FOREIGN', '$POP_ACCOUNT_B', 'workout', '$POP_FOREIGN'," +
                " 'aggregate.delete', 5, 1, '{}', 'pending', 0, NULL, NULL, NULL)",
        )
    }

    private companion object {
        const val POP_ACCOUNT_A = "account-a"
        const val POP_ACCOUNT_B = "account-b"
        const val POP_WORKOUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val POP_TOMB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val POP_FOREIGN = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val POP_LOCAL = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val POP_ROUTINE = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val POP_ROUTINE_TOMB = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        const val POP_CUSTOM = "11111111-1111-1111-1111-111111111111"
        const val POP_BUNDLED = "22222222-2222-2222-2222-222222222222"
        const val POP_CORRUPT = "44444444-4444-4444-4444-444444444444"
        const val POP_UNSUPPORTED = "55555555-5555-5555-5555-555555555555"
        const val POP_MUTATION_VALID = "66666666-6666-6666-6666-666666666666"
        const val POP_MUTATION_FROZEN = "77777777-7777-7777-7777-777777777777"
        const val POP_MUTATION_REJECTED = "88888888-8888-8888-8888-888888888888"
        const val POP_MUTATION_CORRUPT = "99999999-9999-9999-9999-999999999999"
        const val POP_MUTATION_UNSUPPORTED = "aaaaaaaa-1111-1111-1111-111111111111"
        const val POP_MUTATION_FOREIGN = "bbbbbbbb-1111-1111-1111-111111111111"
        const val POP_WORKOUT_JSON =
            "{\"id\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"name\":\"Active W\"," +
                "\"status\":\"finished\",\"startedAt\":\"2026-09-24T14:00:00Z\"," +
                "\"finishedAt\":\"2026-09-24T15:00:00Z\",\"revision\":7}"
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
    }
}
