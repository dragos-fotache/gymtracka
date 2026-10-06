package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R09a independent QA: atomicity, reopen and rich preservation obligations.
 *
 * Contract-only; raw SQL literals; existing file-reopen + trigger patterns
 * reused from CanonicalBatchTest/PushClaimTestSupport shapes (no production
 * inspection). Covers task obligations 4 and 6: abort-trigger rollback with
 * reread, reopen of preparing/private states with no in-memory success
 * dependency, and exact preservation of a rich outbox/cursor/stage/canonical/
 * watermark/domain/foreign/cache/catalogue fixture through private fence.
 */
@RunWith(AndroidJUnit4::class)
class RoomSessionAuthorityPreservationTest {
    private lateinit var database: GymDatabase
    private lateinit var authority: RoomSessionAuthority

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val ACCOUNT_B = "c6938596-d7e7-50bb-0689-8b3f748dccd1"
        const val OP_A = "11111111-1111-1111-1111-111111111111"
        const val ORIGIN_A = "https://sync.example.test:443"
        const val WORKOUT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val WORKOUT_FOREIGN = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val ROUTINE_A = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val CUSTOM_A = "11111111-1111-1111-1111-111111111112"
        const val MUT_VALID = "66666666-6666-6666-6666-666666666666"
        const val MUT_FROZEN = "77777777-7777-7777-7777-777777777777"
        const val MUT_REJECTED = "88888888-8888-8888-8888-888888888888"
    }

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        authority = RoomSessionAuthority(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun snapshotRows(sql: String, columns: Int): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun seedRichFixture() {
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO muscle_groups (id, slug, name) VALUES ('muscle', 'muscle', 'Muscle')")
        db.execSQL(
            "INSERT INTO equipment (id, name, type, usage_type) VALUES ('equipment', 'Equipment', 'weight', NULL)",
        )
        db.execSQL(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('bundled-1', 'Bundled Press', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Step\"]', 0, NULL, NULL, 0, 'local_only', NULL)",
        )
        db.execSQL(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_A', 'Owned Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Custom step\"]', 1, '$ACCOUNT_A', NULL, 2, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type)" +
                " VALUES ('$CUSTOM_A', 'muscle', 'primary')",
        )
        db.execSQL(
            "INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items)" +
                " VALUES ('$CUSTOM_A', 'equipment', NULL)",
        )
        db.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_A', 'Active W', 5000, NULL, 'active'," +
                " '$ACCOUNT_A', 5, 3, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds)" +
                " VALUES ('we-active', '$WORKOUT_A', 'bundled-1', 0, 'Keep', 75)",
        )
        db.execSQL(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds)" +
                " VALUES ('ws-active', 'we-active', 0, 8, 70.0, 1, 60)",
        )
        db.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FOREIGN', 'Foreign W', 4000, NULL, 'active'," +
                " '$ACCOUNT_B', NULL, 4, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision," +
                " local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$ROUTINE_A', 'Draft R', 1, '$ACCOUNT_A', NULL, 1, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO routine_exercises (id, routine_id, exercise_id, position," +
                " rest_duration_seconds)" +
                " VALUES ('re-draft', '$ROUTINE_A', 'bundled-1', 0, 30)",
        )
        db.execSQL(
            "INSERT INTO routine_sets (id, routine_exercise_id, position, repetitions, weight_kg)" +
                " VALUES ('rs-draft', 're-draft', 0, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$ACCOUNT_A', 'cursor-a', 7000)",
        )
        db.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$ACCOUNT_B', 'cursor-b', NULL)",
        )
        db.execSQL("INSERT INTO catalogue_state (id, version) VALUES (1, 'catalogue-12')")
        db.execSQL(
            "INSERT INTO sync_canonical_roots (account_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_state, canonical_json," +
                " local_generation_watermark, cache_epoch)" +
                " VALUES ('$ACCOUNT_A', 'workout', '$WORKOUT_A', 7, 'upsert'," +
                " '{\"id\":\"$WORKOUT_A\"}', 4, 5)",
        )
        db.execSQL(
            "INSERT INTO sync_canonical_roots (account_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_state, canonical_json," +
                " local_generation_watermark, cache_epoch)" +
                " VALUES ('$ACCOUNT_A', 'customExercise', '$CUSTOM_A', NULL, 'unobserved'," +
                " NULL, 2, 0)",
        )
        db.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$ACCOUNT_A', 1, 5, 0, 4)",
        )
        db.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$ACCOUNT_B', 1, 2, 1, 1)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_stages (stage_id, account_id, session_generation, cache_epoch," +
                " page_limit, source_state_present, source_cursor, source_last_success_millis," +
                " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                " page_count, record_count)" +
                " VALUES ('stage-1', '$ACCOUNT_A', 7, 5, 50, 1, 'cursor-a', 7000, 1," +
                " 'baseline', NULL, 'downloading', 1, 1)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_records (stage_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_json, page_number, record_position)" +
                " VALUES ('stage-1', 'workout', '$WORKOUT_A', 7, '{\"id\":\"$WORKOUT_A\"}', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_tokens (stage_id, page_token, page_number)" +
                " VALUES ('stage-1', 'token-1', 0)",
        )
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (10, '$MUT_VALID', '$ACCOUNT_A', 'workout', '$WORKOUT_A'," +
                " 'workout.start', 4, 1, '{}', 'pending', 0, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (20, '$MUT_FROZEN', '$ACCOUNT_A', 'routine', '$ROUTINE_A'," +
                " 'routine.save', 2, 1, '{}', 'frozen', 1, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (30, '$MUT_REJECTED', '$ACCOUNT_A', 'customExercise', '$CUSTOM_A'," +
                " 'aggregate.delete', 2, 1, '{}', 'rejected', 1, NULL, 'gone', 'Gone')",
        )
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (40, '99999999-9999-9999-9999-999999999999', '$ACCOUNT_A', 'workout'," +
                " '44444444-4444-4444-4444-444444444444', 'workout.upsert', 1, 1, '{broken'," +
                " 'pending', 0, NULL, NULL, NULL)",
        )
    }

    @Test
    fun richFixture_preservedExactlyThroughPrivateFence() = runTest {
        seedRichFixture()
        val before = mapOf(
            "outbox" to snapshotRows(
                "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                    " mutation_type, local_generation, payload_version, payload_json, state," +
                    " attempt_count, retry_after_millis, error_code, error_message" +
                    " FROM sync_outbox ORDER BY sequence",
                14,
            ),
            "sync_state" to snapshotRows("SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id", 3),
            "canonical" to snapshotRows(
                "SELECT account_id, aggregate_type, aggregate_id, server_revision, canonical_state," +
                    " canonical_json, local_generation_watermark, cache_epoch" +
                    " FROM sync_canonical_roots ORDER BY account_id, aggregate_type, aggregate_id",
                8,
            ),
            "cache" to snapshotRows(
                "SELECT account_id, baseline_complete, cache_epoch, projection_dirty," +
                    " materialized_epoch FROM sync_account_cache ORDER BY account_id",
                5,
            ),
            "stages" to snapshotRows(
                "SELECT stage_id, account_id, session_generation, cache_epoch, page_limit," +
                    " source_state_present, source_cursor, source_last_success_millis," +
                    " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                    " page_count, record_count FROM sync_snapshot_stages",
                14,
            ),
            "records" to snapshotRows(
                "SELECT stage_id, aggregate_type, aggregate_id, server_revision, canonical_json," +
                    " page_number, record_position FROM sync_snapshot_records",
                7,
            ),
            "tokens" to snapshotRows(
                "SELECT stage_id, page_token, page_number FROM sync_snapshot_tokens",
                3,
            ),
            "workouts" to snapshotRows(
                "SELECT id, name, started_at_millis, finished_at_millis, status, account_id," +
                    " server_revision, local_generation, sync_state, deleted_at_millis" +
                    " FROM workouts ORDER BY id",
                10,
            ),
            "routines" to snapshotRows(
                "SELECT id, name, is_draft, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis FROM routines ORDER BY id",
                8,
            ),
            "exercises" to snapshotRows(
                "SELECT id, name, type, difficulty_level, force_type, mechanics, category," +
                    " instructions, is_user_created, account_id, server_revision," +
                    " local_generation, sync_state, deleted_at_millis FROM exercises ORDER BY id",
                14,
            ),
            "workout_exercises" to snapshotRows(
                "SELECT id, workout_id, exercise_id, position, notes, rest_duration_seconds" +
                    " FROM workout_exercises ORDER BY id",
                6,
            ),
            "workout_sets" to snapshotRows(
                "SELECT id, workout_exercise_id, position, repetitions, weight_kg, is_completed," +
                    " rest_seconds FROM workout_sets ORDER BY id",
                7,
            ),
            "routine_exercises" to snapshotRows(
                "SELECT id, routine_id, exercise_id, position, rest_duration_seconds" +
                    " FROM routine_exercises ORDER BY id",
                5,
            ),
            "routine_sets" to snapshotRows(
                "SELECT id, routine_exercise_id, position, repetitions, weight_kg" +
                    " FROM routine_sets ORDER BY id",
                5,
            ),
            "exercise_muscle_groups" to snapshotRows(
                "SELECT exercise_id, muscle_group_id, type FROM exercise_muscle_groups" +
                    " ORDER BY exercise_id, muscle_group_id",
                3,
            ),
            "exercise_equipment" to snapshotRows(
                "SELECT exercise_id, equipment_id, num_items FROM exercise_equipment" +
                    " ORDER BY exercise_id, equipment_id",
                3,
            ),
            "catalogue" to snapshotRows("SELECT id, version FROM catalogue_state", 2),
        )

        val fresh = authority.read()
        val preparing = authority.beginPreparation(
            fresh,
            ServiceOrigin.parse(ORIGIN_A),
            ACCOUNT_A,
            OP_A,
        )
        val fenced = authority.fencePrivate(preparing)
        assertEquals(RecoveryRoomPhase.SIGNED_OUT, fenced.phase)
        assertEquals(ACCOUNT_A, fenced.cachedAccountId)

        // Every rich row byte-identical; only journal + device move.
        assertEquals(
            before["outbox"],
            snapshotRows(
                "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                    " mutation_type, local_generation, payload_version, payload_json, state," +
                    " attempt_count, retry_after_millis, error_code, error_message" +
                    " FROM sync_outbox ORDER BY sequence",
                14,
            ),
        )
        assertEquals(
            before["sync_state"],
            snapshotRows("SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id", 3),
        )
        assertEquals(
            before["canonical"],
            snapshotRows(
                "SELECT account_id, aggregate_type, aggregate_id, server_revision, canonical_state," +
                    " canonical_json, local_generation_watermark, cache_epoch" +
                    " FROM sync_canonical_roots ORDER BY account_id, aggregate_type, aggregate_id",
                8,
            ),
        )
        assertEquals(
            before["cache"],
            snapshotRows(
                "SELECT account_id, baseline_complete, cache_epoch, projection_dirty," +
                    " materialized_epoch FROM sync_account_cache ORDER BY account_id",
                5,
            ),
        )
        assertEquals(
            before["stages"],
            snapshotRows(
                "SELECT stage_id, account_id, session_generation, cache_epoch, page_limit," +
                    " source_state_present, source_cursor, source_last_success_millis," +
                    " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                    " page_count, record_count FROM sync_snapshot_stages",
                14,
            ),
        )
        assertEquals(
            before["records"],
            snapshotRows(
                "SELECT stage_id, aggregate_type, aggregate_id, server_revision, canonical_json," +
                    " page_number, record_position FROM sync_snapshot_records",
                7,
            ),
        )
        assertEquals(
            before["tokens"],
            snapshotRows(
                "SELECT stage_id, page_token, page_number FROM sync_snapshot_tokens",
                3,
            ),
        )
        assertEquals(
            before["workouts"],
            snapshotRows(
                "SELECT id, name, started_at_millis, finished_at_millis, status, account_id," +
                    " server_revision, local_generation, sync_state, deleted_at_millis" +
                    " FROM workouts ORDER BY id",
                10,
            ),
        )
        assertEquals(
            before["routines"],
            snapshotRows(
                "SELECT id, name, is_draft, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis FROM routines ORDER BY id",
                8,
            ),
        )
        assertEquals(
            before["exercises"],
            snapshotRows(
                "SELECT id, name, type, difficulty_level, force_type, mechanics, category," +
                    " instructions, is_user_created, account_id, server_revision," +
                    " local_generation, sync_state, deleted_at_millis FROM exercises ORDER BY id",
                14,
            ),
        )
        assertEquals(
            before["workout_exercises"],
            snapshotRows(
                "SELECT id, workout_id, exercise_id, position, notes, rest_duration_seconds" +
                    " FROM workout_exercises ORDER BY id",
                6,
            ),
        )
        assertEquals(
            before["workout_sets"],
            snapshotRows(
                "SELECT id, workout_exercise_id, position, repetitions, weight_kg, is_completed," +
                    " rest_seconds FROM workout_sets ORDER BY id",
                7,
            ),
        )
        assertEquals(
            before["routine_exercises"],
            snapshotRows(
                "SELECT id, routine_id, exercise_id, position, rest_duration_seconds" +
                    " FROM routine_exercises ORDER BY id",
                5,
            ),
        )
        assertEquals(
            before["routine_sets"],
            snapshotRows(
                "SELECT id, routine_exercise_id, position, repetitions, weight_kg" +
                    " FROM routine_sets ORDER BY id",
                5,
            ),
        )
        assertEquals(
            before["exercise_muscle_groups"],
            snapshotRows(
                "SELECT exercise_id, muscle_group_id, type FROM exercise_muscle_groups" +
                    " ORDER BY exercise_id, muscle_group_id",
                3,
            ),
        )
        assertEquals(
            before["exercise_equipment"],
            snapshotRows(
                "SELECT exercise_id, equipment_id, num_items FROM exercise_equipment" +
                    " ORDER BY exercise_id, equipment_id",
                3,
            ),
        )
        assertEquals(
            before["catalogue"],
            snapshotRows("SELECT id, version FROM catalogue_state", 2),
        )
        // Old authority cannot be kept after the generation change.
        try {
            authority.fencePrivate(preparing)
            fail("old authority must be stale after fence")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.STALE_AUTHORITY, e.failure)
        }
    }

    @Test
    fun abortTriggerOnSecondWrite_rollsBackWithOnlyCommittedVisibleOnReread() = runTest {
        val fresh = authority.read()
        // Abort the device write (second write of the atomic pair): the journal
        // write must roll back with it; reread observes only committed state.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER r09a_abort_device BEFORE UPDATE ON sync_device_state" +
                " BEGIN SELECT RAISE(ABORT, 'r09a-blocked'); END",
        )
        // Seed a device row so the transaction performs an UPDATE (trigger path).
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        val seeded = authority.read()
        try {
            authority.beginPreparation(seeded, ServiceOrigin.parse(ORIGIN_A), ACCOUNT_A, OP_A)
            fail("expected transaction abort")
        } catch (e: RoomSessionAuthorityException) {
            // Closed failure category (unavailable on I/O abort path); never a
            // rollback/success claim: callers must reread durable state.
            assertTrue(
                e.failure == RoomSessionAuthorityFailure.UNAVAILABLE ||
                    e.failure == RoomSessionAuthorityFailure.CORRUPT_AUTHORITY,
            )
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS r09a_abort_device")
        }
        // Reread durable state: still the seeded signed-out observation, journal
        // absent, device unchanged.
        assertEquals(seeded, authority.read())
        assertEquals(0, snapshotRows("SELECT id FROM sync_session_authority", 1).size)
        assertEquals(fresh.sessionGeneration, authority.read().sessionGeneration)
    }

    @Test
    fun reopenObservesPreparingAndPrivate_withoutInMemorySuccess() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "r09a-authority-reopen.db"
        context.deleteDatabase(name)
        try {
            val fileDatabase = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                val fileAuthority = RoomSessionAuthority(fileDatabase)
                val preparing = fileAuthority.beginPreparation(
                    fileAuthority.read(),
                    ServiceOrigin.parse(ORIGIN_A),
                    ACCOUNT_A,
                    OP_A,
                )
                assertEquals(RecoveryRoomPhase.PREPARING, preparing.phase)
            } finally {
                fileDatabase.close()
            }
            // Reopen with a NEW instance: no in-memory success dependency.
            val reopenedPreparing = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                val reread = RoomSessionAuthority(reopenedPreparing).read()
                assertEquals(RecoveryRoomPhase.PREPARING, reread.phase)
                assertEquals(1L, reread.revision)
                assertEquals(1L, reread.sessionGeneration)
                assertEquals(ACCOUNT_A, reread.cachedAccountId)
                val fenced = RoomSessionAuthority(reopenedPreparing).fencePrivate(reread)
                assertEquals(RecoveryRoomPhase.SIGNED_OUT, fenced.phase)
            } finally {
                reopenedPreparing.close()
            }
            val reopenedPrivate = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                val reread = RoomSessionAuthority(reopenedPrivate).read()
                assertEquals(RecoveryRoomPhase.SIGNED_OUT, reread.phase)
                assertEquals(2L, reread.revision)
                assertEquals(2L, reread.sessionGeneration)
            } finally {
                reopenedPrivate.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun staleSignedOutRepository_fencedAfterGenerationAdvance() = runTest {
        // Contract seam: GymRepository(database, RepositorySession.SIGNED_OUT)
        // before the initial reservation; the TestScope pattern below mirrors the
        // ScopedRepositoryTest TEST stale-fence examples (no production inspection).
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        val repo = GymRepository(database, RepositorySession.SIGNED_OUT)
        // Read works before the reservation on the matching generation.
        repo.exerciseSummaries()

        val fresh = authority.read()
        val preparing = authority.beginPreparation(
            fresh,
            ServiceOrigin.parse(ORIGIN_A),
            ACCOUNT_A,
            OP_A,
        )
        authority.fencePrivate(preparing)

        // Read proof: the pre-reservation repository is fenced after the advance.
        try {
            repo.exerciseSummaries()
            fail("expected StaleSessionException on stale read")
        } catch (_: StaleSessionException) {
        }
        // Mutation proof: the same stale handle cannot capture either.
        try {
            repo.createCustomExercise("Name", null, emptySet(), null, null, "")
            fail("expected StaleSessionException on stale mutation")
        } catch (_: StaleSessionException) {
        }
    }

    @Test
    fun callerCancellation_duringPreparation_propagatesIdenticallyWithConsistentReread() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        val fresh = authority.read()
        // Deterministic real-transaction gate: the child opens an actual Room
        // transaction, performs the provisional preparation writes inside it,
        // signals entry, then holds the transaction open. The test cancels only
        // after entry is observed; the nested authority call joins the same
        // actual Room transaction, so cancellation must roll both writes back.
        // CompletableDeferred gates, virtual time only, no sleeps.
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val probe = CancellationException("qa-authority-cancel")
        val child = async {
            database.withTransaction {
                authority.beginPreparation(fresh, ServiceOrigin.parse(ORIGIN_A), ACCOUNT_A, OP_A)
                entered.complete(Unit)
                hold.await()
            }
        }
        entered.await()
        child.cancel(probe)
        val outcome = try {
            child.await()
            null
        } catch (e: Throwable) {
            e
        }
        // Cancellation propagates unchanged, never converted to a failure category.
        assertSame(probe, outcome)
        // Only committed state is observable: no partial journal/device pair.
        assertEquals(fresh, authority.read())
        assertEquals(0, snapshotRows("SELECT id FROM sync_session_authority", 1).size)
        database.openHelper.readableDatabase.query(
            "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals("local_only", cursor.getString(3))
        }
    }

    @Test
    fun postWriteMismatch_rewrittenDeviceTuple_rollsBackWithoutSuccessClaim() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        val before = authority.read()
        // AFTER-trigger rewrites the just-written device tuple to a still-valid
        // but wrong value (lost preparing cache_state): exact post-write recheck
        // must fail closed instead of reporting the altered rows as success.
        // The WHEN guard matches only the provisional 'preparing' write, so the
        // inner rewrite to 'local_only' cannot refire (no recursive abort path).
        db.execSQL(
            "CREATE TRIGGER r09a_rewrite_device AFTER UPDATE ON sync_device_state" +
                " WHEN NEW.cache_state = 'preparing'" +
                " BEGIN UPDATE sync_device_state SET cache_state = 'local_only' WHERE id = 1; END",
        )
        // Fixture precondition proof: the guard is installed as written.
        val triggerSql = database.openHelper.readableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE type = 'trigger'" +
                " AND name = 'r09a_rewrite_device'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertTrue(triggerSql.contains("WHEN NEW.cache_state = 'preparing'"))
        try {
            authority.beginPreparation(before, ServiceOrigin.parse(ORIGIN_A), ACCOUNT_A, OP_A)
            fail("rewritten post-write rows must not report success")
        } catch (e: RoomSessionAuthorityException) {
            // Strict contract: exact post-write mismatch fails as corruption.
            assertEquals(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY, e.failure)
            assertEquals("Room session authority failure", e.message)
            assertNull("cause must stay closed", e.cause)
            assertTrue(e.suppressed.isEmpty())
            // Fixture survives the failure: the rollback was scoped to the
            // authority transaction, not the installed trigger DDL.
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'" +
                    " AND name = 'r09a_rewrite_device'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS r09a_rewrite_device")
        }
        // Rollback, not altered-rows authority: reread is the pre-call observation
        // and the raw rows are byte-identical (snapshot equality alone would not
        // prove the altered rows were rejected).
        assertEquals(before, authority.read())
        assertEquals(0, snapshotRows("SELECT id FROM sync_session_authority", 1).size)
        database.openHelper.readableDatabase.query(
            "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals("local_only", cursor.getString(3))
        }
    }
}
