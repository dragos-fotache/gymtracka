package com.dragosfotache.gymtracka.data.gym

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GymDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val assetDatabaseName = "real-v1-asset-migration.db"

    @get:Rule
    val migrationHelper = MigrationTestHelper(instrumentation, GymDatabase::class.java)

    @After
    fun cleanUpAssetDatabase() {
        context.deleteDatabase(assetDatabaseName)
    }

    @Test
    fun everySupportedVersion_migratesToCurrentSchemaAndPreservesAvailableData() {
        for (startVersion in 1..14) {
            val databaseName = "migration-$startVersion-to-15.db"
            migrationHelper.createDatabase(databaseName, startVersion).use { database ->
                seedLegacyDatabase(database, startVersion)
            }

            migrationHelper.runMigrationsAndValidate(
                databaseName,
                15,
                true,
                *GymDatabase.MIGRATIONS,
            ).use { database ->
                assertMigratedData(database, startVersion)
            }
        }
    }

    @Test
    fun versionFourteen_preservesAllOutboxBytesAndKeepsExistingDeadlinesProtected() {
        val name = "migration-retry-cause-14-to-15.db"
        val columns = "sequence, mutation_id, account_id, aggregate_type, aggregate_id, mutation_type," +
            " local_generation, payload_version, payload_json, state, attempt_count, retry_after_millis, error_code, error_message"
        val before = migrationHelper.createDatabase(name, 14).use { database ->
            seedPopulatedVersionNine(database)
            snapshotRows(database, "SELECT $columns FROM sync_outbox ORDER BY sequence", 14)
        }
        migrationHelper.runMigrationsAndValidate(name, 15, true, *GymDatabase.MIGRATIONS).use { database ->
            assertEquals(before, snapshotRows(database, "SELECT $columns FROM sync_outbox ORDER BY sequence", 14))
            assertEquals(6, database.singleInt("SELECT COUNT(*) FROM sync_outbox WHERE retry_cause = 'unknown'"))
        }
    }

    @Test
    fun bundledVersionOneAsset_migratesWithCatalogueIntact() {
        context.deleteDatabase(assetDatabaseName)
        val databaseFile = context.getDatabasePath(assetDatabaseName)
        databaseFile.parentFile?.mkdirs()
        context.assets.open("databases/gym.db").use { source ->
            databaseFile.outputStream().use(source::copyTo)
        }

        val database = Room.databaseBuilder(context, GymDatabase::class.java, assetDatabaseName)
            .addMigrations(*GymDatabase.MIGRATIONS)
            .build()
        try {
            val sqliteDatabase = database.openHelper.writableDatabase

            assertEquals(899, sqliteDatabase.singleInt("SELECT COUNT(*) FROM exercises"))
            assertEquals(17, sqliteDatabase.singleInt("SELECT COUNT(*) FROM muscle_groups"))
            assertEquals(36, sqliteDatabase.singleInt("SELECT COUNT(*) FROM equipment"))
            assertEquals(
                0,
                sqliteDatabase.singleInt(
                    "SELECT COUNT(*) FROM exercises WHERE is_user_created != 0",
                ),
            )
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM workouts"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM routines"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_state"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_device_state"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM catalogue_state"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_canonical_roots"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_account_cache"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_snapshot_stages"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_snapshot_records"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_snapshot_tokens"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_session_authority"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_legacy_import_entries"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_legacy_import_progress"))
            assertEquals(0, sqliteDatabase.singleInt("SELECT COUNT(*) FROM sync_legacy_import_acceptances"))
        } finally {
            database.close()
        }
    }

    @Test
    fun populatedVersionNine_upgradesToTenWithLedgerReservationsAndNoFabrication() {
        val databaseName = "migration-populated-9-to-10.db"
        val before = mutableMapOf<String, List<List<String?>>>()
        migrationHelper.createDatabase(databaseName, 9).use { database ->
            seedPopulatedVersionNine(database)
            before["outbox"] = snapshotRows(database, "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id, mutation_type, local_generation, payload_version, payload_json, state, attempt_count, retry_after_millis, error_code, error_message FROM sync_outbox ORDER BY sequence", 14)
            before["workouts"] = snapshotRows(database, "SELECT id, name, started_at_millis, finished_at_millis, status, account_id, server_revision, local_generation, sync_state, deleted_at_millis FROM workouts ORDER BY id", 10)
            before["routines"] = snapshotRows(database, "SELECT id, name, is_draft, account_id, server_revision, local_generation, sync_state, deleted_at_millis FROM routines ORDER BY id", 8)
            before["exercises"] = snapshotRows(database, "SELECT id, name, type, difficulty_level, force_type, mechanics, category, instructions, is_user_created, account_id, server_revision, local_generation, sync_state, deleted_at_millis FROM exercises ORDER BY id", 14)
            before["workout_exercises"] = snapshotRows(database, "SELECT id, workout_id, exercise_id, position, notes, rest_duration_seconds FROM workout_exercises ORDER BY id", 6)
            before["workout_sets"] = snapshotRows(database, "SELECT id, workout_exercise_id, position, repetitions, weight_kg, is_completed, rest_seconds FROM workout_sets ORDER BY id", 7)
            before["routine_exercises"] = snapshotRows(database, "SELECT id, routine_id, exercise_id, position, rest_duration_seconds FROM routine_exercises ORDER BY id", 5)
            before["routine_sets"] = snapshotRows(database, "SELECT id, routine_exercise_id, position, repetitions, weight_kg FROM routine_sets ORDER BY id", 5)
            before["exercise_muscle_groups"] = snapshotRows(database, "SELECT exercise_id, muscle_group_id, type FROM exercise_muscle_groups ORDER BY exercise_id", 3)
            before["exercise_equipment"] = snapshotRows(database, "SELECT exercise_id, equipment_id, num_items FROM exercise_equipment ORDER BY exercise_id", 3)
            before["sync_state"] = snapshotRows(database, "SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id", 3)
            before["sync_device_state"] = snapshotRows(database, "SELECT id, active_account_id, cached_account_id, session_generation, cache_state FROM sync_device_state", 5)
            before["catalogue_state"] = snapshotRows(database, "SELECT id, version FROM catalogue_state", 2)
            before["muscle_groups"] = snapshotRows(database, "SELECT id, slug, name FROM muscle_groups ORDER BY id", 3)
            before["equipment"] = snapshotRows(database, "SELECT id, name, type, usage_type FROM equipment ORDER BY id", 4)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            *GymDatabase.MIGRATIONS,
        ).use { database ->
            assertPopulatedVersionTen(database, before)
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

    private fun seedPopulatedVersionNine(database: SupportSQLiteDatabase) {
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
                " VALUES ('$POP_ACCOUNT_A', 'cursor-a', 7000)",
        )
        database.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$POP_ACCOUNT_B', 'cursor-b', NULL)",
        )
        database.execSQL(
            "INSERT INTO sync_device_state" +
                " (id, active_account_id, cached_account_id, session_generation, cache_state)" +
                " VALUES (1, '$POP_ACCOUNT_A', '$POP_ACCOUNT_A', 7, 'local_only')",
        )
        database.execSQL("INSERT INTO catalogue_state (id, version) VALUES (1, 'catalogue-12')")

        val validInner =
            "{\"mutationId\" : \"$POP_MUTATION_VALID\", \"type\" : \"workout.start\"," +
                " \"aggregateType\" : \"workout\", \"aggregateId\" : \"$POP_WORKOUT\"," +
                " \"localGeneration\" : 4, \"knownServerRevision\" : null," +
                " \"payload\" : {\"id\" : \"$POP_WORKOUT\"," +
                " \"name\" : \"W \\\"Q\\\" \\\\ S\", \"startedAt\" : \"2026-09-24T14:00:00Z\"}}"
        val validWrapper =
            "{\"payloadVersion\":1,\"requestJson\":" + quoteJson(validInner) +
                ",\"affectedRoots\":[{\"aggregateType\":\"workout\"," +
                "\"aggregateId\":\"$POP_WORKOUT\",\"localGeneration\":4}," +
                "{\"aggregateType\":\"workout\",\"aggregateId\":\"$POP_VANISHED\"," +
                "\"localGeneration\":2}]}"
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (10, '$POP_MUTATION_VALID', '$POP_ACCOUNT_A', 'workout', '$POP_WORKOUT'," +
                " 'workout.start', 4, 1, '$validWrapper', 'pending', 0, NULL, NULL, NULL)",
        )

        val frozenInner =
            "{\"mutationId\":\"$POP_MUTATION_FROZEN\",\"type\":\"routine.save\"," +
                "\"aggregateType\":\"routine\",\"aggregateId\":\"$POP_ROUTINE\"," +
                "\"localGeneration\":2,\"knownServerRevision\":null,\"payload\":{\"name\":\"Saved\"}}"
        val frozenWrapper =
            "{\"payloadVersion\":1,\"requestJson\":" + quoteJson(frozenInner) +
                ",\"affectedRoots\":[{\"aggregateType\":\"routine\"," +
                "\"aggregateId\":\"$POP_ROUTINE\",\"localGeneration\":2}]}"
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (20, '$POP_MUTATION_FROZEN', '$POP_ACCOUNT_A', 'routine', '$POP_ROUTINE'," +
                " 'routine.save', 2, 1, '$frozenWrapper', 'frozen', 1, NULL, NULL, NULL)",
        )

        val rejectedInner =
            "{\"mutationId\":\"$POP_MUTATION_REJECTED\",\"type\":\"aggregate.delete\"," +
                "\"aggregateType\":\"customExercise\",\"aggregateId\":\"$POP_CUSTOM\"," +
                "\"localGeneration\":2,\"knownServerRevision\":null,\"payload\":{}}"
        val rejectedWrapper =
            "{\"payloadVersion\":1,\"requestJson\":" + quoteJson(rejectedInner) +
                ",\"affectedRoots\":[{\"aggregateType\":\"customExercise\"," +
                "\"aggregateId\":\"$POP_CUSTOM\",\"localGeneration\":2}]}"
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (30, '$POP_MUTATION_REJECTED', '$POP_ACCOUNT_A', 'customExercise', '$POP_CUSTOM'," +
                " 'aggregate.delete', 2, 1, '$rejectedWrapper', 'rejected', 1, NULL, 'gone', 'Gone')",
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

        val foreignInner =
            "{\"mutationId\":\"$POP_MUTATION_FOREIGN\",\"type\":\"aggregate.delete\"," +
                "\"aggregateType\":\"workout\",\"aggregateId\":\"$POP_FOREIGN\"," +
                "\"localGeneration\":5,\"knownServerRevision\":null,\"payload\":{}}"
        val foreignWrapper =
            "{\"payloadVersion\":1,\"requestJson\":" + quoteJson(foreignInner) +
                ",\"affectedRoots\":[{\"aggregateType\":\"workout\"," +
                "\"aggregateId\":\"$POP_FOREIGN\",\"localGeneration\":5}]}"
        database.execSQL(
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (60, '$POP_MUTATION_FOREIGN', '$POP_ACCOUNT_B', 'workout', '$POP_FOREIGN'," +
                " 'aggregate.delete', 5, 1, '$foreignWrapper', 'pending', 0, NULL, NULL, NULL)",
        )
    }

    private fun assertPopulatedVersionTen(
        database: SupportSQLiteDatabase,
        before: Map<String, List<List<String?>>>,
    ) {
        assertEquals(before["outbox"], snapshotRows(database, "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id, mutation_type, local_generation, payload_version, payload_json, state, attempt_count, retry_after_millis, error_code, error_message FROM sync_outbox ORDER BY sequence", 14))
        assertEquals(before["workouts"], snapshotRows(database, "SELECT id, name, started_at_millis, finished_at_millis, status, account_id, server_revision, local_generation, sync_state, deleted_at_millis FROM workouts ORDER BY id", 10))
        assertEquals(before["routines"], snapshotRows(database, "SELECT id, name, is_draft, account_id, server_revision, local_generation, sync_state, deleted_at_millis FROM routines ORDER BY id", 8))
        assertEquals(before["exercises"], snapshotRows(database, "SELECT id, name, type, difficulty_level, force_type, mechanics, category, instructions, is_user_created, account_id, server_revision, local_generation, sync_state, deleted_at_millis FROM exercises ORDER BY id", 14))
        assertEquals(before["workout_exercises"], snapshotRows(database, "SELECT id, workout_id, exercise_id, position, notes, rest_duration_seconds FROM workout_exercises ORDER BY id", 6))
        assertEquals(before["workout_sets"], snapshotRows(database, "SELECT id, workout_exercise_id, position, repetitions, weight_kg, is_completed, rest_seconds FROM workout_sets ORDER BY id", 7))
        assertEquals(before["routine_exercises"], snapshotRows(database, "SELECT id, routine_id, exercise_id, position, rest_duration_seconds FROM routine_exercises ORDER BY id", 5))
        assertEquals(before["routine_sets"], snapshotRows(database, "SELECT id, routine_exercise_id, position, repetitions, weight_kg FROM routine_sets ORDER BY id", 5))
        assertEquals(before["exercise_muscle_groups"], snapshotRows(database, "SELECT exercise_id, muscle_group_id, type FROM exercise_muscle_groups ORDER BY exercise_id", 3))
        assertEquals(before["exercise_equipment"], snapshotRows(database, "SELECT exercise_id, equipment_id, num_items FROM exercise_equipment ORDER BY exercise_id", 3))
        assertEquals(before["sync_state"], snapshotRows(database, "SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id", 3))
        assertEquals(before["sync_device_state"], snapshotRows(database, "SELECT id, active_account_id, cached_account_id, session_generation, cache_state FROM sync_device_state", 5))
        assertEquals(before["catalogue_state"], snapshotRows(database, "SELECT id, version FROM catalogue_state", 2))
        assertEquals(before["muscle_groups"], snapshotRows(database, "SELECT id, slug, name FROM muscle_groups ORDER BY id", 3))
        assertEquals(before["equipment"], snapshotRows(database, "SELECT id, name, type, usage_type FROM equipment ORDER BY id", 4))
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM muscle_groups"))
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM equipment"))
        assertEquals(2, database.singleInt("SELECT COUNT(*) FROM exercises"))
        assertEquals(4, database.singleInt("SELECT COUNT(*) FROM workouts"))
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM workout_exercises"))
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM workout_sets"))
        assertEquals(2, database.singleInt("SELECT COUNT(*) FROM routines"))
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM routine_exercises"))
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM routine_sets"))
        database.query(
            "SELECT account_id, server_revision, local_generation, sync_state, deleted_at_millis" +
                " FROM workouts WHERE id = '$POP_WORKOUT'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(POP_ACCOUNT_A, cursor.getString(0))
            assertEquals(5L, cursor.getLong(1))
            assertEquals(3L, cursor.getLong(2))
            assertEquals("pending", cursor.getString(3))
            assertTrue(cursor.isNull(4))
        }
        assertEquals(
            75,
            database.query("SELECT rest_duration_seconds FROM workout_exercises WHERE id = 'we-active'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            },
        )
        database.query(
            "SELECT position, repetitions, weight_kg, is_completed, rest_seconds" +
                " FROM workout_sets WHERE id = 'ws-active'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(8, cursor.getInt(1))
            assertEquals(70.0, cursor.getDouble(2), 0.0)
            assertEquals(1, cursor.getInt(3))
            assertEquals(60, cursor.getInt(4))
        }
        database.query(
            "SELECT is_draft, account_id, local_generation FROM routines WHERE id = '$POP_ROUTINE'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(POP_ACCOUNT_A, cursor.getString(1))
            assertEquals(1L, cursor.getLong(2))
        }
        database.query(
            "SELECT is_user_created, account_id, local_generation FROM exercises WHERE id = '$POP_CUSTOM'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(POP_ACCOUNT_A, cursor.getString(1))
            assertEquals(2L, cursor.getLong(2))
        }

        assertEquals(6, database.singleInt("SELECT COUNT(*) FROM sync_outbox"))
        database.query(
            "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, error_code, error_message FROM sync_outbox ORDER BY sequence",
        ).use { cursor ->
            val sequences = mutableListOf<Long>()
            val mutationIds = mutableListOf<String>()
            val states = mutableListOf<String>()
            val wrappers = mutableListOf<String>()
            while (cursor.moveToNext()) {
                sequences.add(cursor.getLong(0))
                mutationIds.add(cursor.getString(1))
                states.add(cursor.getString(9))
                wrappers.add(cursor.getString(8))
                val expectedVersion = if (cursor.getString(1) == POP_MUTATION_UNSUPPORTED) 2 else 1
                assertEquals(expectedVersion, cursor.getInt(7))
            }
            assertEquals(listOf(10L, 20L, 30L, 40L, 50L, 60L), sequences)
            assertEquals(
                listOf(
                    POP_MUTATION_VALID, POP_MUTATION_FROZEN, POP_MUTATION_REJECTED,
                    POP_MUTATION_CORRUPT, POP_MUTATION_UNSUPPORTED, POP_MUTATION_FOREIGN,
                ),
                mutationIds,
            )
            assertEquals(
                listOf("pending", "frozen", "rejected", "pending", "pending", "pending"),
                states,
            )
            assertTrue(wrappers[0].contains(POP_VANISHED))
            assertEquals("{broken", wrappers[3])
            assertEquals("{}", wrappers[4])
            assertEquals("gone", database.singleString("SELECT error_code FROM sync_outbox WHERE mutation_id = '$POP_MUTATION_REJECTED'"))
            assertEquals("Gone", database.singleString("SELECT error_message FROM sync_outbox WHERE mutation_id = '$POP_MUTATION_REJECTED'"))
        }

        database.query(
            "SELECT cursor, last_success_millis FROM sync_state WHERE account_id = '$POP_ACCOUNT_A'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("cursor-a", cursor.getString(0))
            assertEquals(7000L, cursor.getLong(1))
        }
        assertEquals(
            "cursor-b",
            database.singleString("SELECT cursor FROM sync_state WHERE account_id = '$POP_ACCOUNT_B'"),
        )
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
        assertEquals("catalogue-12", database.singleString("SELECT version FROM catalogue_state WHERE id = 1"))

        // Supported outbox column targets reserve unobserved watermarks from their row
        // metadata even when the wrapper bytes are corrupt/unsupported; only EXTRA effects
        // require a valid strict v1 read, so no unvalidated extra is ever inferred.
        val expectedWatermarks = mapOf(
            Triple(POP_ACCOUNT_A, "workout", POP_WORKOUT) to 4L,
            Triple(POP_ACCOUNT_A, "workout", POP_TOMB) to 2L,
            Triple(POP_ACCOUNT_B, "workout", POP_FOREIGN) to 5L,
            Triple(POP_ACCOUNT_A, "routine", POP_ROUTINE) to 2L,
            Triple(POP_ACCOUNT_A, "routine", POP_ROUTINE_TOMB) to 2L,
            Triple(POP_ACCOUNT_A, "customExercise", POP_CUSTOM) to 2L,
            Triple(POP_ACCOUNT_A, "workout", POP_VANISHED) to 2L,
            Triple(POP_ACCOUNT_A, "workout", POP_CORRUPT) to 1L,
            Triple(POP_ACCOUNT_A, "routine", POP_UNSUPPORTED) to 1L,
        )
        assertEquals(
            expectedWatermarks.size,
            database.singleInt("SELECT COUNT(*) FROM sync_canonical_roots"),
        )
        database.query(
            "SELECT account_id, aggregate_type, aggregate_id, server_revision," +
                " canonical_state, canonical_json, local_generation_watermark, cache_epoch" +
                " FROM sync_canonical_roots",
        ).use { cursor ->
            val seen = mutableSetOf<Triple<String, String, String>>()
            while (cursor.moveToNext()) {
                val key = Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                assertTrue(seen.add(key))
                assertTrue(cursor.isNull(3))
                assertEquals("unobserved", cursor.getString(4))
                assertTrue(cursor.isNull(5))
                assertEquals(expectedWatermarks[key], cursor.getLong(6))
                assertEquals(0L, cursor.getLong(7))
            }
            assertEquals(expectedWatermarks.keys, seen)
        }
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_canonical_roots WHERE aggregate_id = '$POP_LOCAL'"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_canonical_roots WHERE aggregate_id = '$POP_BUNDLED'"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_canonical_roots WHERE server_revision IS NOT NULL"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_canonical_roots WHERE canonical_json IS NOT NULL"))
        assertEquals(
            0,
            database.singleInt("SELECT COUNT(*) FROM sync_canonical_roots WHERE canonical_state != 'unobserved'"),
        )

        database.query("SELECT account_id, baseline_complete, cache_epoch, projection_dirty, materialized_epoch FROM sync_account_cache").use { cursor ->
            val seen = mutableMapOf<String, List<Long>>()
            while (cursor.moveToNext()) {
                seen[cursor.getString(0)] = listOf(
                    cursor.getLong(1), cursor.getLong(2), cursor.getLong(3), cursor.getLong(4),
                )
            }
            assertEquals(setOf(POP_ACCOUNT_A, POP_ACCOUNT_B), seen.keys)
            assertEquals(listOf(0L, 0L, 1L, 0L), seen[POP_ACCOUNT_A])
            assertEquals(listOf(0L, 0L, 1L, 0L), seen[POP_ACCOUNT_B])
        }
    }

    private fun quoteJson(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun SupportSQLiteDatabase.singleString(query: String): String =
        this.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun seedLegacyDatabase(database: SupportSQLiteDatabase, version: Int) {
        database.execSQL("INSERT INTO muscle_groups (id, slug, name) VALUES ('muscle', 'muscle', 'Muscle')")
        database.execSQL(
            "INSERT INTO equipment (id, name, type, usage_type) VALUES ('equipment', 'Equipment', 'weight', NULL)",
        )
        val catalogueExerciseColumns = buildList {
            addAll(
                listOf(
                    "id",
                    "name",
                    "type",
                    "difficulty_level",
                    "force_type",
                    "mechanics",
                    "category",
                    "instructions",
                ),
            )
            if (version >= 8) add("is_user_created")
        }
        val catalogueExerciseValues = buildList {
            addAll(
                listOf(
                    "'exercise'",
                    "'Legacy Exercise'",
                    "'reps'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "'strength'",
                    "'[\"Step\"]'",
                ),
            )
            if (version >= 8) add("0")
        }
        database.execSQL(
            "INSERT INTO exercises (${catalogueExerciseColumns.joinToString()}) VALUES (${catalogueExerciseValues.joinToString()})",
        )
        database.execSQL(
            "INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES ('exercise', 'muscle', 'primary')",
        )
        database.execSQL(
            "INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items) VALUES ('exercise', 'equipment', 2)",
        )

        if (version < 2) return

        val workoutColumns = buildList {
            add("id")
            add("started_at_millis")
            add("finished_at_millis")
            add("status")
            if (version >= 3) add("name")
        }
        val workoutValues = buildList {
            add("'workout'")
            add(LEGACY_STARTED_AT_MILLIS.toString())
            add("2000")
            add("'finished'")
            if (version >= 3) add("'Legacy Workout'")
        }
        database.execSQL(
            "INSERT INTO workouts (${workoutColumns.joinToString()}) VALUES (${workoutValues.joinToString()})",
        )

        val exerciseColumns = buildList {
            addAll(listOf("id", "workout_id", "exercise_id", "position"))
            if (version >= 4) add("notes")
            if (version >= 6) add("rest_duration_seconds")
        }
        val exerciseValues = buildList {
            addAll(listOf("'workout-exercise'", "'workout'", "'exercise'", "2"))
            if (version >= 4) add("'Legacy notes'")
            if (version >= 6) add("75")
        }
        database.execSQL(
            "INSERT INTO workout_exercises (${exerciseColumns.joinToString()}) VALUES (${exerciseValues.joinToString()})",
        )

        val setColumns = buildList {
            addAll(
                listOf(
                    "id",
                    "workout_exercise_id",
                    "position",
                    "repetitions",
                    "weight_kg",
                    "is_completed",
                ),
            )
            if (version >= 5) add("rest_seconds")
        }
        val setValues = buildList {
            addAll(listOf("'workout-set'", "'workout-exercise'", "3", "NULL", "42.5", "1"))
            if (version >= 5) add("55")
        }
        database.execSQL(
            "INSERT INTO workout_sets (${setColumns.joinToString()}) VALUES (${setValues.joinToString()})",
        )

        if (version < 7) return

        database.execSQL("INSERT INTO routines (id, name, is_draft) VALUES ('routine', 'Legacy Routine', 0)")
        database.execSQL(
            """INSERT INTO routine_exercises
                (id, routine_id, exercise_id, position, rest_duration_seconds)
                VALUES ('routine-exercise', 'routine', 'exercise', 4, 90)""",
        )
        database.execSQL(
            """INSERT INTO routine_sets
                (id, routine_exercise_id, position, repetitions, weight_kg)
                VALUES ('routine-set', 'routine-exercise', 5, NULL, 37.5)""",
        )

        if (version < 8) return

        database.execSQL(
            """INSERT INTO exercises
                (id, name, type, difficulty_level, force_type, mechanics, category,
                    instructions, is_user_created)
                VALUES ('custom-exercise', 'Legacy Custom', 'reps', NULL, NULL, NULL,
                    'strength', '["Custom step"]', 1)""",
        )
        database.execSQL(
            "INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES ('custom-exercise', 'muscle', 'primary')",
        )
        database.execSQL(
            "INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items) VALUES ('custom-exercise', 'equipment', NULL)",
        )
    }

    private fun assertMigratedData(database: SupportSQLiteDatabase, startVersion: Int) {
        database.query("SELECT name, is_user_created FROM exercises WHERE id = 'exercise'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Legacy Exercise", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        assertRootDefaults(database, "exercises", "id = 'exercise'")
        val expectedRelationCount = if (startVersion >= 8) 2 else 1
        assertEquals(
            expectedRelationCount,
            database.singleInt("SELECT COUNT(*) FROM exercise_muscle_groups"),
        )
        assertEquals(
            expectedRelationCount,
            database.singleInt("SELECT COUNT(*) FROM exercise_equipment"),
        )
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM muscle_groups"))
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM equipment"))

        if (startVersion >= 8) {
            database.query(
                """SELECT name, is_user_created, category, instructions, account_id,
                    server_revision, local_generation, sync_state, deleted_at_millis
                    FROM exercises WHERE id = 'custom-exercise'""",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy Custom", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("strength", cursor.getString(2))
                assertEquals("[\"Custom step\"]", cursor.getString(3))
                assertTrue(cursor.isNull(4))
                assertTrue(cursor.isNull(5))
                assertEquals(0L, cursor.getLong(6))
                assertEquals("local_only", cursor.getString(7))
                assertTrue(cursor.isNull(8))
            }
        }

        if (startVersion >= 2) {
            database.query(
                """SELECT name, status, finished_at_millis FROM workouts
                    WHERE id = 'workout'""",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val expectedName = if (startVersion == 2) {
                    expectedDefaultWorkoutName()
                } else {
                    "Legacy Workout"
                }
                assertEquals(expectedName, cursor.getString(0))
                assertEquals("finished", cursor.getString(1))
                assertEquals(2_000L, cursor.getLong(2))
            }
            database.query(
                """SELECT position, notes, rest_duration_seconds FROM workout_exercises
                    WHERE id = 'workout-exercise'""",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
                assertEquals(if (startVersion >= 4) "Legacy notes" else "", cursor.getString(1))
                assertEquals(if (startVersion >= 6) 75 else 30, cursor.getInt(2))
            }
            database.query(
                """SELECT position, repetitions, weight_kg, is_completed, rest_seconds
                    FROM workout_sets WHERE id = 'workout-set'""",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
                assertEquals(42.5, cursor.getDouble(2), 0.0)
                assertEquals(1, cursor.getInt(3))
                if (startVersion >= 5) assertEquals(55, cursor.getInt(4)) else assertTrue(cursor.isNull(4))
            }
            assertRootDefaults(database, "workouts", "id = 'workout'")
        } else {
            assertEquals(0, database.singleInt("SELECT COUNT(*) FROM workouts"))
        }

        if (startVersion >= 7) {
            database.query(
                """SELECT routines.name, routine_exercises.position,
                    routine_exercises.rest_duration_seconds, routine_sets.position,
                    routine_sets.repetitions, routine_sets.weight_kg
                    FROM routines
                    JOIN routine_exercises ON routine_exercises.routine_id = routines.id
                    JOIN routine_sets ON routine_sets.routine_exercise_id = routine_exercises.id
                    WHERE routines.id = 'routine'""",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy Routine", cursor.getString(0))
                assertEquals(4, cursor.getInt(1))
                assertEquals(90, cursor.getInt(2))
                assertEquals(5, cursor.getInt(3))
                assertTrue(cursor.isNull(4))
                assertEquals(37.5, cursor.getDouble(5), 0.0)
            }
            assertRootDefaults(database, "routines", "id = 'routine'")
        } else {
            assertEquals(0, database.singleInt("SELECT COUNT(*) FROM routines"))
        }

        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_state"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_device_state"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM catalogue_state"))
        assertEmptyCanonicalTables(database)
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_snapshot_stages"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_snapshot_records"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_snapshot_tokens"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_legacy_import_entries"))
    }

    private fun assertEmptyCanonicalTables(database: SupportSQLiteDatabase) {
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_canonical_roots"))
        assertEquals(0, database.singleInt("SELECT COUNT(*) FROM sync_account_cache"))
    }

    private fun assertRootDefaults(
        database: SupportSQLiteDatabase,
        table: String,
        predicate: String,
    ) {
        database.query(
            """SELECT account_id, server_revision, local_generation, sync_state,
                deleted_at_millis FROM $table WHERE $predicate""",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals("local_only", cursor.getString(3))
            assertTrue(cursor.isNull(4))
        }
    }

    private fun expectedDefaultWorkoutName(): String {
        val hour = Calendar.getInstance().run {
            timeInMillis = LEGACY_STARTED_AT_MILLIS
            get(Calendar.HOUR_OF_DAY)
        }
        val prefix = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
        return "$prefix Workout"
    }

    private fun SupportSQLiteDatabase.singleInt(query: String): Int =
        this.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val LEGACY_STARTED_AT_MILLIS = 1_700_000_000_000L
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
        const val POP_VANISHED = "33333333-3333-3333-3333-333333333333"
        const val POP_CORRUPT = "44444444-4444-4444-4444-444444444444"
        const val POP_UNSUPPORTED = "55555555-5555-5555-5555-555555555555"
        const val POP_MUTATION_VALID = "66666666-6666-6666-6666-666666666666"
        const val POP_MUTATION_FROZEN = "77777777-7777-7777-7777-777777777777"
        const val POP_MUTATION_REJECTED = "88888888-8888-8888-8888-888888888888"
        const val POP_MUTATION_CORRUPT = "99999999-9999-9999-9999-999999999999"
        const val POP_MUTATION_UNSUPPORTED = "aaaaaaaa-1111-1111-1111-111111111111"
        const val POP_MUTATION_FOREIGN = "bbbbbbbb-1111-1111-1111-111111111111"
    }
}
