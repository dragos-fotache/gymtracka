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
        for (startVersion in 1 until 8) {
            val databaseName = "migration-$startVersion-to-8.db"
            migrationHelper.createDatabase(databaseName, startVersion).use { database ->
                seedLegacyDatabase(database, startVersion)
            }

            migrationHelper.runMigrationsAndValidate(
                databaseName,
                8,
                true,
                *GymDatabase.MIGRATIONS,
            ).use { database ->
                assertMigratedData(database, startVersion)
            }
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
        } finally {
            database.close()
        }
    }

    private fun seedLegacyDatabase(database: SupportSQLiteDatabase, version: Int) {
        database.execSQL("INSERT INTO muscle_groups (id, slug, name) VALUES ('muscle', 'muscle', 'Muscle')")
        database.execSQL("INSERT INTO equipment (id, name, type, usage_type) VALUES ('equipment', 'Equipment', 'weight', NULL)")
        database.execSQL(
            """INSERT INTO exercises
                (id, name, type, difficulty_level, force_type, mechanics, category, instructions)
                VALUES ('exercise', 'Legacy Exercise', 'reps', NULL, NULL, NULL, 'strength', '["Step"]')""",
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
    }

    private fun assertMigratedData(database: SupportSQLiteDatabase, startVersion: Int) {
        database.query("SELECT name, is_user_created FROM exercises WHERE id = 'exercise'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Legacy Exercise", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM exercise_muscle_groups"))
        assertEquals(1, database.singleInt("SELECT COUNT(*) FROM exercise_equipment"))

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
        } else {
            assertEquals(0, database.singleInt("SELECT COUNT(*) FROM routines"))
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
    }
}
