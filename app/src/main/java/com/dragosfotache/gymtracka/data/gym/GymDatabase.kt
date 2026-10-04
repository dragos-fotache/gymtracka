package com.dragosfotache.gymtracka.data.gym

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MuscleGroupEntity::class,
        EquipmentEntity::class,
        ExerciseEntity::class,
        ExerciseMuscleGroupEntity::class,
        ExerciseEquipmentEntity::class,
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        WorkoutSetEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        RoutineSetEntity::class,
        SyncOutboxEntity::class,
        SyncStateEntity::class,
        SyncDeviceStateEntity::class,
        CatalogueStateEntity::class,
        SyncCanonicalRootEntity::class,
        SyncAccountCacheEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(GymTypeConverters::class)
abstract class GymDatabase : RoomDatabase() {
    abstract fun muscleGroupDao(): MuscleGroupDao
    abstract fun equipmentDao(): EquipmentDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun routineDao(): RoutineDao
    abstract fun syncDao(): SyncDao
    abstract fun syncCaptureDao(): SyncCaptureDao
    abstract fun syncCanonicalDao(): SyncCanonicalDao

    companion object {
        private const val DATABASE_NAME = "gym.db"
        private const val ASSET_PATH = "databases/gym.db"

        @Volatile private var instance: GymDatabase? = null

        fun getInstance(context: Context): GymDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GymDatabase::class.java,
                    DATABASE_NAME,
                )
                    .createFromAsset(ASSET_PATH)
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `workouts` (
                        `id` TEXT NOT NULL,
                        `started_at_millis` INTEGER NOT NULL,
                        `finished_at_millis` INTEGER,
                        `status` TEXT NOT NULL DEFAULT 'active',
                        PRIMARY KEY(`id`)
                    )""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `workout_exercises` (
                        `id` TEXT NOT NULL,
                        `workout_id` TEXT NOT NULL,
                        `exercise_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`workout_id`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )""",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_exercises_workout_id` ON `workout_exercises` (`workout_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_exercises_exercise_id` ON `workout_exercises` (`exercise_id`)",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `workout_sets` (
                        `id` TEXT NOT NULL,
                        `workout_exercise_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `repetitions` INTEGER,
                        `weight_kg` REAL,
                        `is_completed` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`workout_exercise_id`) REFERENCES `workout_exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_sets_workout_exercise_id` ON `workout_sets` (`workout_exercise_id`)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `workouts` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    """UPDATE `workouts` SET `name` = CASE
                        WHEN CAST(strftime('%H', started_at_millis / 1000, 'unixepoch', 'localtime') AS INTEGER)
                            BETWEEN 5 AND 11 THEN 'Morning Workout'
                        WHEN CAST(strftime('%H', started_at_millis / 1000, 'unixepoch', 'localtime') AS INTEGER)
                            BETWEEN 12 AND 16 THEN 'Afternoon Workout'
                        WHEN CAST(strftime('%H', started_at_millis / 1000, 'unixepoch', 'localtime') AS INTEGER)
                            BETWEEN 17 AND 20 THEN 'Evening Workout'
                        ELSE 'Night Workout'
                    END WHERE `name` = ''""",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `workout_exercises` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `workout_sets` ADD COLUMN `rest_seconds` INTEGER",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `workout_exercises` ADD COLUMN `rest_duration_seconds` INTEGER NOT NULL DEFAULT 30",
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `routines` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `is_draft` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `routine_exercises` (
                        `id` TEXT NOT NULL,
                        `routine_id` TEXT NOT NULL,
                        `exercise_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `rest_duration_seconds` INTEGER NOT NULL DEFAULT 30,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`routine_id`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )""",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_routine_exercises_routine_id` ON `routine_exercises` (`routine_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_routine_exercises_exercise_id` ON `routine_exercises` (`exercise_id`)",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `routine_sets` (
                        `id` TEXT NOT NULL,
                        `routine_exercise_id` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `repetitions` INTEGER,
                        `weight_kg` REAL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`routine_exercise_id`) REFERENCES `routine_exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_routine_sets_routine_exercise_id` ON `routine_sets` (`routine_exercise_id`)",
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `exercises` ADD COLUMN `is_user_created` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workouts` ADD COLUMN `account_id` TEXT")
                db.execSQL("ALTER TABLE `workouts` ADD COLUMN `server_revision` INTEGER")
                db.execSQL(
                    "ALTER TABLE `workouts` ADD COLUMN `local_generation` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `workouts` ADD COLUMN `sync_state` TEXT NOT NULL DEFAULT 'local_only'",
                )
                db.execSQL("ALTER TABLE `workouts` ADD COLUMN `deleted_at_millis` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workouts_account_id_deleted_at_millis`" +
                        " ON `workouts` (`account_id`, `deleted_at_millis`)",
                )
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `account_id` TEXT")
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `server_revision` INTEGER")
                db.execSQL(
                    "ALTER TABLE `routines` ADD COLUMN `local_generation` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `routines` ADD COLUMN `sync_state` TEXT NOT NULL DEFAULT 'local_only'",
                )
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `deleted_at_millis` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_routines_account_id_deleted_at_millis`" +
                        " ON `routines` (`account_id`, `deleted_at_millis`)",
                )
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `account_id` TEXT")
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `server_revision` INTEGER")
                db.execSQL(
                    "ALTER TABLE `exercises` ADD COLUMN `local_generation` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `exercises` ADD COLUMN `sync_state` TEXT NOT NULL DEFAULT 'local_only'",
                )
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `deleted_at_millis` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exercises_account_id_deleted_at_millis`" +
                        " ON `exercises` (`account_id`, `deleted_at_millis`)",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_outbox` (
                        `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `mutation_id` TEXT NOT NULL,
                        `account_id` TEXT NOT NULL,
                        `aggregate_type` TEXT NOT NULL,
                        `aggregate_id` TEXT NOT NULL,
                        `mutation_type` TEXT NOT NULL,
                        `local_generation` INTEGER NOT NULL,
                        `payload_version` INTEGER NOT NULL DEFAULT 1,
                        `payload_json` TEXT NOT NULL,
                        `state` TEXT NOT NULL DEFAULT 'pending',
                        `attempt_count` INTEGER NOT NULL DEFAULT 0,
                        `retry_after_millis` INTEGER,
                        `error_code` TEXT,
                        `error_message` TEXT
                    )""",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_mutation_id`" +
                        " ON `sync_outbox` (`mutation_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_outbox_account_id_sequence`" +
                        " ON `sync_outbox` (`account_id`, `sequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS" +
                        " `index_sync_outbox_account_id_aggregate_type_aggregate_id`" +
                        " ON `sync_outbox` (`account_id`, `aggregate_type`, `aggregate_id`)",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_state` (
                        `account_id` TEXT NOT NULL,
                        `cursor` TEXT,
                        `last_success_millis` INTEGER,
                        PRIMARY KEY(`account_id`)
                    )""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_device_state` (
                        `id` INTEGER NOT NULL,
                        `active_account_id` TEXT,
                        `cached_account_id` TEXT,
                        `session_generation` INTEGER NOT NULL DEFAULT 0,
                        `cache_state` TEXT NOT NULL DEFAULT 'local_only',
                        PRIMARY KEY(`id`)
                    )""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `catalogue_state` (
                        `id` INTEGER NOT NULL,
                        `version` TEXT,
                        PRIMARY KEY(`id`)
                    )""",
                )
            }
        }

        internal val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            CanonicalStorageMigration,
        )

    }
}
