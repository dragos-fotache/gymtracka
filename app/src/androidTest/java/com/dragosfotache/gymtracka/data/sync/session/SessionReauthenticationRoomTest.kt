package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R09e independent QA: real-Room reauthentication authority transitions.
 *
 * Derived ONLY from docs/archive/remaining-work/R09e-task.md Room transitions plus
 * QA-owned literal raw-SQL/trigger/preservation patterns (R09a/b Room
 * authority tests, preservation seeds, post-write trigger rollback,
 * count-zero IGNORE). No production declarations, diffs, Worker work,
 * generated sources, decompiled classes or schema JSON inspected.
 * Deterministic only, no sleeps, no DAO mocks. Real in-memory Room;
 * coordinator/vault behavior lives in SessionReauthenticationRoomVaultTest.
 */
@RunWith(AndroidJUnit4::class)
class SessionReauthenticationRoomTest {

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val ACCOUNT_B = "c6938596-d7e7-50bb-0689-8b3f748dccd1"
        const val OP_OLD = "11111111-1111-1111-1111-111111111111"
        const val OP_NEW = "22222222-2222-2222-2222-222222222222"
        const val OP_OTHER = "33333333-3333-3333-3333-333333333333"
        const val ORIGIN_A = "https://sync.example.test:443"
        const val ORIGIN_B = "https://other.example.test:443"
        const val WORKOUT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val WORKOUT_FOREIGN = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val ROUTINE_A = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val CUSTOM_A = "11111111-1111-1111-1111-111111111112"
        const val MUT_PENDING = "66666666-6666-6666-6666-666666666666"
        const val MUT_FROZEN = "77777777-7777-7777-7777-777777777777"
        const val MUT_REJECTED = "88888888-8888-8888-8888-888888888888"
        const val STAGE_1 = "stage-1"
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun originA(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_A)

    private fun openDb(): GymDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, GymDatabase::class.java).build()

    private fun snapshotRows(database: GymDatabase, sql: String, columns: Int): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun sequenceSnapshot(database: GymDatabase): List<List<String?>> = try {
        snapshotRows(database, "SELECT name, seq FROM sqlite_sequence ORDER BY name", 2)
    } catch (_: Exception) {
        emptyList()
    }

    private fun journalRow(database: GymDatabase): List<String?> =
        snapshotRows(
            database,
            "SELECT revision, session_generation, phase, service_origin, account_id, operation_id" +
                " FROM sync_session_authority WHERE id = 1",
            6,
        ).single()

    private fun deviceRow(database: GymDatabase): List<String?> =
        snapshotRows(
            database,
            "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state WHERE id = 1",
            4,
        ).single()

    /** ACTIVE journal/device at generation 7 plus the complete baseline cache. */
    private fun seedActiveAuthorityWithCompleteCache(database: GymDatabase) {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 11, 7, 'active', '$ORIGIN_A', '$ACCOUNT_A', '$OP_OLD')",
        )
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, '$ACCOUNT_A', '$ACCOUNT_A', 7, 'ready')",
        )
        db.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$ACCOUNT_A', 1, 5, 1, 4)",
        )
    }

    private fun frozenWrapper(mutationId: String, rootId: String, generation: Long): String {
        val inner = "{\"mutationId\":\"$mutationId\",\"type\":\"workout.start\"," +
            "\"aggregateType\":\"workout\",\"aggregateId\":\"$rootId\"," +
            "\"localGeneration\":$generation," +
            "\"payload\":{\"id\":\"$rootId\",\"name\":\"R09e\"}}"
        val escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"payloadVersion\":1,\"requestJson\":\"$escaped\"," +
            "\"affectedRoots\":[{\"aggregateType\":\"workout\",\"aggregateId\":\"$rootId\"," +
            "\"localGeneration\":$generation}]}"
    }

    /** Rich retained scope: children, frozen bytes, canonical, stages/tokens, foreign. */
    private fun seedRichRetained(database: GymDatabase) {
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
                " VALUES ('$ACCOUNT_B', 1, 2, 1, 1)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_stages (stage_id, account_id, session_generation, cache_epoch," +
                " page_limit, source_state_present, source_cursor, source_last_success_millis," +
                " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                " page_count, record_count)" +
                " VALUES ('$STAGE_1', '$ACCOUNT_A', 7, 5, 50, 1, 'cursor-a', 7000, 1," +
                " 'baseline', NULL, 'downloading', 1, 1)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_records (stage_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_json, page_number, record_position)" +
                " VALUES ('$STAGE_1', 'workout', '$WORKOUT_A', 7, '{\"id\":\"$WORKOUT_A\"}', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_tokens (stage_id, page_token, page_number)" +
                " VALUES ('$STAGE_1', 'token-1', 0)",
        )
        val frozen = frozenWrapper(MUT_FROZEN, WORKOUT_A, 4)
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (10, '$MUT_PENDING', '$ACCOUNT_A', 'workout', '$WORKOUT_A'," +
                " 'workout.start', 4, 1, '$frozen', 'pending', 0, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (20, '$MUT_FROZEN', '$ACCOUNT_A', 'routine', '$ROUTINE_A'," +
                " 'routine.save', 2, 1, '$frozen', 'frozen', 1, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (30, '$MUT_REJECTED', '$ACCOUNT_A', 'customExercise', '$CUSTOM_A'," +
                " 'aggregate.delete', 2, 1, '$frozen', 'rejected', 1, NULL, 'gone', 'Gone')",
        )
    }

    /** Every retained table plus sequences; ONLY journal/device are excluded. */
    private fun fullRetainedSnapshot(database: GymDatabase): Map<String, List<List<String?>>> = mapOf(
        "muscles" to snapshotRows(database, "SELECT id, slug, name FROM muscle_groups ORDER BY id", 3),
        "equipment" to snapshotRows(database, "SELECT id, name, type, usage_type FROM equipment ORDER BY id", 4),
        "exercises" to snapshotRows(
            database,
            "SELECT id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis FROM exercises ORDER BY id",
            14,
        ),
        "ex_muscles" to snapshotRows(
            database, "SELECT exercise_id, muscle_group_id, type FROM exercise_muscle_groups ORDER BY 1, 2", 3,
        ),
        "ex_equipment" to snapshotRows(
            database, "SELECT exercise_id, equipment_id, num_items FROM exercise_equipment ORDER BY 1, 2", 3,
        ),
        "workouts" to snapshotRows(
            database,
            "SELECT id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis" +
                " FROM workouts ORDER BY id",
            10,
        ),
        "workout_exercises" to snapshotRows(
            database,
            "SELECT id, workout_id, exercise_id, position, notes, rest_duration_seconds" +
                " FROM workout_exercises ORDER BY id",
            6,
        ),
        "workout_sets" to snapshotRows(
            database,
            "SELECT id, workout_exercise_id, position, repetitions, weight_kg, is_completed, rest_seconds" +
                " FROM workout_sets ORDER BY id",
            7,
        ),
        "routines" to snapshotRows(
            database,
            "SELECT id, name, is_draft, account_id, server_revision," +
                " local_generation, sync_state, deleted_at_millis FROM routines ORDER BY id",
            8,
        ),
        "routine_exercises" to snapshotRows(
            database,
            "SELECT id, routine_id, exercise_id, position, rest_duration_seconds" +
                " FROM routine_exercises ORDER BY id",
            5,
        ),
        "routine_sets" to snapshotRows(
            database,
            "SELECT id, routine_exercise_id, position, repetitions, weight_kg" +
                " FROM routine_sets ORDER BY id",
            5,
        ),
        "sync_state" to snapshotRows(
            database, "SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id", 3,
        ),
        "catalogue" to snapshotRows(database, "SELECT id, version FROM catalogue_state", 2),
        "canonical" to snapshotRows(
            database,
            "SELECT account_id, aggregate_type, aggregate_id, server_revision, canonical_state," +
                " canonical_json, local_generation_watermark, cache_epoch" +
                " FROM sync_canonical_roots ORDER BY account_id, aggregate_type, aggregate_id",
            8,
        ),
        "cache" to snapshotRows(
            database,
            "SELECT account_id, baseline_complete, cache_epoch, projection_dirty," +
                " materialized_epoch FROM sync_account_cache ORDER BY account_id",
            5,
        ),
        "stages" to snapshotRows(
            database,
            "SELECT stage_id, account_id, session_generation, cache_epoch, page_limit," +
                " source_state_present, source_cursor, source_last_success_millis," +
                " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                " page_count, record_count FROM sync_snapshot_stages ORDER BY stage_id",
            14,
        ),
        "records" to snapshotRows(
            database,
            "SELECT stage_id, aggregate_type, aggregate_id, server_revision, canonical_json," +
                " page_number, record_position FROM sync_snapshot_records ORDER BY 1, 2, 3",
            7,
        ),
        "tokens" to snapshotRows(
            database, "SELECT stage_id, page_token, page_number FROM sync_snapshot_tokens ORDER BY 1, 2", 3,
        ),
        "outbox" to snapshotRows(
            database,
            "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message" +
                " FROM sync_outbox ORDER BY sequence",
            14,
        ),
        "sequences" to sequenceSnapshot(database),
    )

    private suspend fun expectRoomFailure(
        expected: RoomSessionAuthorityFailure,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected RoomSessionAuthorityException($expected)")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(expected, e.failure)
            assertNull(e.cause)
            assertTrue(e.suppressed.isEmpty())
        }
    }

    private suspend fun expectClosedRoomFailure(block: suspend () -> Unit): RoomSessionAuthorityException {
        try {
            block()
            fail("expected closed RoomSessionAuthorityException")
        } catch (e: RoomSessionAuthorityException) {
            assertNull(e.cause)
            assertTrue(e.suppressed.isEmpty())
            assertTrue(e.message.orEmpty().isNotEmpty())
            return e
        }
        throw AssertionError("unreachable")
    }

    // ---- Happy reservation + finalization with exact tuples ----

    @Test
    fun beginThenFinishExactGenerationRevisionTuples() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val expected = authority.read()
            assertEquals(RecoveryRoomPhase.ACTIVE, expected.phase)
            assertEquals(true, expected.baselineComplete)

            val transitioning = authority.beginReauthentication(expected, originA(), OP_NEW)
            assertEquals(12L, transitioning.revision)
            assertEquals(8L, transitioning.sessionGeneration)
            assertEquals(RecoveryRoomPhase.TRANSITIONING, transitioning.phase)
            assertEquals(false, transitioning.baselineComplete)
            assertEquals(
                listOf("12", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_NEW),
                journalRow(database),
            )
            assertEquals(listOf(null, ACCOUNT_A, "8", "preparing"), deviceRow(database))

            val preparing = authority.finishReauthentication(transitioning)
            assertEquals(13L, preparing.revision)
            assertEquals(8L, preparing.sessionGeneration)
            assertEquals(RecoveryRoomPhase.PREPARING, preparing.phase)
            assertEquals(
                listOf("13", "8", "preparing", ORIGIN_A, ACCOUNT_A, OP_NEW),
                journalRow(database),
            )
            // Device tuple unchanged by finalization.
            assertEquals(listOf(null, ACCOUNT_A, "8", "preparing"), deviceRow(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun completeRetainedScopePreservedExceptJournalDevice() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            seedRichRetained(database)
            val before = fullRetainedSnapshot(database)

            val authority = RoomSessionAuthority(database)
            val transitioning = authority.beginReauthentication(authority.read(), originA(), OP_NEW)
            authority.finishReauthentication(transitioning)

            assertEquals(before, fullRetainedSnapshot(database))
            // The frozen request bytes and foreign scope survived byte-identical.
            val outbox = checkNotNull(fullRetainedSnapshot(database)["outbox"])
            assertTrue(outbox.any { it[4] == WORKOUT_FOREIGN || it[2] == ACCOUNT_A && it[8] == frozenWrapper(MUT_FROZEN, WORKOUT_A, 4) })
            val workouts = checkNotNull(fullRetainedSnapshot(database)["workouts"])
            assertTrue(workouts.any { it[0] == WORKOUT_FOREIGN && it[5] == ACCOUNT_B })
        } finally {
            database.close()
        }
    }

    @Test
    fun activeWithoutCompleteCacheCannotBegin() = runTest {
        val database = openDb()
        try {
            // Journal/device ACTIVE/ready but no complete cache row: the
            // admission baseline cannot be established, so begin must refuse
            // closed with no durable effects (exact mapping is unspecified).
            val db = database.openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                    " service_origin, account_id, operation_id)" +
                    " VALUES (1, 11, 7, 'active', '$ORIGIN_A', '$ACCOUNT_A', '$OP_OLD')",
            )
            db.execSQL(
                "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                    " session_generation, cache_state)" +
                    " VALUES (1, '$ACCOUNT_A', '$ACCOUNT_A', 7, 'ready')",
            )
            val authority = RoomSessionAuthority(database)
            val snapshot = try {
                authority.read()
            } catch (e: RoomSessionAuthorityException) {
                assertNull(e.cause)
                assertEquals(
                    listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                    journalRow(database),
                )
                return@runTest
            }
            expectClosedRoomFailure {
                authority.beginReauthentication(snapshot, originA(), OP_NEW)
            }
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalRow(database),
            )
        } finally {
            database.close()
        }
    }

    // ---- Local roots at admission and after reservation ----

    @Test
    fun localRootsAtAdmissionRefuseWithoutEffects() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val db = database.openHelper.writableDatabase
            val authority = RoomSessionAuthority(database)
            val fresh = authority.read()

            db.execSQL(
                "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                    " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('local-w', 'Local W', 1, NULL, 'active'," +
                    " NULL, NULL, 1, 'local_only', NULL)",
            )
            expectRoomFailure(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION) {
                authority.beginReauthentication(fresh, originA(), OP_NEW)
            }
            db.execSQL("DELETE FROM workouts WHERE id = 'local-w'")

            db.execSQL(
                "INSERT INTO routines (id, name, is_draft, account_id, server_revision," +
                    " local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('local-r', 'Local R', 1, NULL, NULL, 1, 'local_only', 9000)",
            )
            expectRoomFailure(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION) {
                authority.beginReauthentication(fresh, originA(), OP_NEW)
            }
            db.execSQL("DELETE FROM routines WHERE id = 'local-r'")

            db.execSQL(
                "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                    " instructions, is_user_created, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis)" +
                    " VALUES ('local-custom', 'Local C', 'reps', NULL, NULL, NULL, 'strength'," +
                    " '[\"Step\"]', 1, NULL, NULL, 1, 'local_only', NULL)",
            )
            expectRoomFailure(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION) {
                authority.beginReauthentication(fresh, originA(), OP_NEW)
            }
            db.execSQL("DELETE FROM exercises WHERE id = 'local-custom'")

            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalRow(database),
            )
            assertEquals(listOf(ACCOUNT_A, ACCOUNT_A, "7", "ready"), deviceRow(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun localRootAppearingAfterReservationRefusesFinish() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val transitioning = authority.beginReauthentication(authority.read(), originA(), OP_NEW)
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                    " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('local-late', 'Late', 1, NULL, 'active'," +
                    " NULL, NULL, 1, 'local_only', NULL)",
            )
            expectRoomFailure(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION) {
                authority.finishReauthentication(transitioning)
            }
            // Reservation stands (private); finish did not advance revision.
            assertEquals(
                listOf("12", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_NEW),
                journalRow(database),
            )
        } finally {
            database.close()
        }
    }

    // ---- Malformed tuples, exhaustion, affected-count safeguards ----

    @Test
    fun malformedJournalPhaseRefusesClosed() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_session_authority SET phase = 'bogus-phase' WHERE id = 1",
            )
            val authority = RoomSessionAuthority(database)
            expectClosedRoomFailure {
                authority.read()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun revisionExhaustionRefusedBeforeEffects() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            // No room for BOTH reservation and finalization (needs <= MAX-2).
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_session_authority SET revision = ${Long.MAX_VALUE - 1} WHERE id = 1",
            )
            val authority = RoomSessionAuthority(database)
            val expected = authority.read()
            expectRoomFailure(RoomSessionAuthorityFailure.COUNTER_OVERFLOW) {
                authority.beginReauthentication(expected, originA(), OP_NEW)
            }
            assertEquals((Long.MAX_VALUE - 1).toString(), journalRow(database)[0])
        } finally {
            database.close()
        }
    }

    @Test
    fun generationExhaustionRefusedBeforeEffects() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_session_authority SET session_generation = ${Long.MAX_VALUE} WHERE id = 1",
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_device_state SET session_generation = ${Long.MAX_VALUE} WHERE id = 1",
            )
            val authority = RoomSessionAuthority(database)
            val expected = authority.read()
            expectClosedRoomFailure {
                authority.beginReauthentication(expected, originA(), OP_NEW)
            }
            assertEquals(Long.MAX_VALUE.toString(), journalRow(database)[1])
        } finally {
            database.close()
        }
    }

    @Test
    fun beginDeviceRewriteRollsBackWithoutSuccess() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val db = database.openHelper.writableDatabase
            val authority = RoomSessionAuthority(database)
            val before = authority.read()
            // AFTER-trigger rewrites the just-written device tuple to a
            // still-valid but wrong value: exact post-write recheck must fail
            // closed instead of reporting altered rows as success. The WHEN
            // guard matches only the provisional 'preparing' write, so the
            // inner rewrite cannot refire.
            db.execSQL(
                "CREATE TRIGGER r09e_rewrite_device AFTER UPDATE ON sync_device_state" +
                    " WHEN NEW.cache_state = 'preparing'" +
                    " BEGIN UPDATE sync_device_state SET cache_state = 'local_only' WHERE id = 1; END",
            )
            try {
                expectRoomFailure(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY) {
                    authority.beginReauthentication(before, originA(), OP_NEW)
                }
            } finally {
                db.execSQL("DROP TRIGGER IF EXISTS r09e_rewrite_device")
            }
            assertEquals(before, authority.read())
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalRow(database),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun beginDeviceCountZeroRefusesWithRollback() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val db = database.openHelper.writableDatabase
            val authority = RoomSessionAuthority(database)
            val before = authority.read()
            // Representative affected-count-zero: the preparing device write
            // is silently skipped, so the exact-row predicate fails and the
            // Room transaction rolls back. Only this trigger differs from the
            // passing reservation path, so its refusal proves the hook engaged.
            db.execSQL(
                "CREATE TRIGGER r09e_ignore_preparing BEFORE UPDATE ON sync_device_state" +
                    " WHEN NEW.cache_state = 'preparing'" +
                    " BEGIN SELECT RAISE(IGNORE); END",
            )
            try {
                expectClosedRoomFailure {
                    authority.beginReauthentication(before, originA(), OP_NEW)
                }
            } finally {
                db.execSQL("DROP TRIGGER IF EXISTS r09e_ignore_preparing")
            }
            assertEquals(before, authority.read())
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalRow(database),
            )
            assertEquals(listOf(ACCOUNT_A, ACCOUNT_A, "7", "ready"), deviceRow(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun finishJournalRewriteRollsBackWithoutSuccess() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val db = database.openHelper.writableDatabase
            val authority = RoomSessionAuthority(database)
            val transitioning = authority.beginReauthentication(authority.read(), originA(), OP_NEW)
            // Valid-but-wrong rewrite: operation_id becomes a DIFFERENT
            // canonical UUID while phase/device stay consistent, so the
            // observation is well-formed yet never the expected tuple. The
            // exact post-write recheck must fail CORRUPT_AUTHORITY closed.
            db.execSQL(
                "CREATE TRIGGER r09e_rewrite_finish AFTER UPDATE ON sync_session_authority" +
                    " WHEN NEW.phase = 'preparing' AND OLD.phase = 'transitioning'" +
                    " BEGIN UPDATE sync_session_authority SET operation_id = '$OP_OTHER' WHERE id = 1; END",
            )
            try {
                expectRoomFailure(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY) {
                    authority.finishReauthentication(transitioning)
                }
            } finally {
                db.execSQL("DROP TRIGGER IF EXISTS r09e_rewrite_finish")
            }
            // Journal still the TRANSITIONING reservation; device unchanged.
            assertEquals(
                listOf("12", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_NEW),
                journalRow(database),
            )
            assertEquals(listOf(null, ACCOUNT_A, "8", "preparing"), deviceRow(database))
        } finally {
            database.close()
        }
    }

    // ---- Old ordinary-store generation refusal + bound lease ----

    @Test
    fun oldGenerationOrdinaryStoreRefusesAfterReservation() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val oldSession = RepositorySession(ACCOUNT_A, 7L)
            val owner = PushRunOwner()
            // Reservation advances generation to 8: old generation fenced.
            val transitioning = authority.beginReauthentication(authority.read(), originA(), OP_NEW)
            var blockRan = false
            try {
                PushStore(database, oldSession, owner).withRun(5L) {
                    blockRan = true
                }
                fail("old-generation ordinary store must refuse")
            } catch (_: StaleSessionException) {
            }
            assertFalse(blockRan)
            // Finalization does not resurrect the old session.
            authority.finishReauthentication(transitioning)
            blockRan = false
            try {
                PushStore(database, oldSession, owner).withRun(5L) {
                    blockRan = true
                }
                fail("old-generation ordinary store must stay stale")
            } catch (_: StaleSessionException) {
            }
            assertFalse(blockRan)
        } finally {
            database.close()
        }
    }

    @Test
    fun boundLeasePredicateFailsStaleAfterDurableReservation() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val captured = authority.read()
            val lease = SessionRefreshLease(
                binding = checkNotNull(captured.identity).binding,
                operationId = checkNotNull(captured.identity).operationId,
                phase = VaultSessionPhase.ACTIVE,
                authorizationCheck = {
                    if (authority.read() != captured) {
                        throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
                    }
                },
            )
            lease.checkAuthorization()
            authority.beginReauthentication(captured, originA(), OP_NEW)
            try {
                lease.checkAuthorization()
                fail("old bound lease must be stale")
            } catch (e: SessionRefreshException) {
                assertEquals(SessionRefreshFailure.STALE_SESSION, e.failure)
            }
            // Generation alone never makes an unbound default lease safe: only
            // the Room-checked predicate above provides authorization.
            assertEquals(8L, authority.read().sessionGeneration)
        } finally {
            database.close()
        }
    }

    @Test
    fun staleOperationAndForeignOriginRefuseUnchanged() = runTest {
        val database = openDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val fresh = authority.read()
            expectRoomFailure(RoomSessionAuthorityFailure.STALE_AUTHORITY) {
                authority.beginReauthentication(fresh.copy(revision = 10L), originA(), OP_NEW)
            }
            expectRoomFailure(RoomSessionAuthorityFailure.STALE_AUTHORITY) {
                authority.beginReauthentication(fresh, originA(), OP_OLD)
            }
            expectRoomFailure(RoomSessionAuthorityFailure.STALE_AUTHORITY) {
                authority.finishReauthentication(fresh)
            }
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalRow(database),
            )
            // Other-origin reservation must never be adopted: a different
            // configured origin refuses ACCOUNT_MISMATCH with no effects.
            expectRoomFailure(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH) {
                authority.beginReauthentication(fresh, ServiceOrigin.parse(ORIGIN_B), OP_NEW)
            }
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalRow(database),
            )
            assertEquals(ORIGIN_A, journalRow(database)[3])
        } finally {
            database.close()
        }
    }
}
