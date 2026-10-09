package com.dragosfotache.gymtracka.data.sync.legacy

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.session.RecoveryIdentity
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomPhase
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityException
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityFailure
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.SyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R13a independent QA: durable legacy graph and private import capture.
 *
 * Derived ONLY from docs/archive/remaining-work/R13a-task.md exact API/table contract
 * (including coordinator-confirmed harness facts: store lease package,
 * `aggregate.import` mutation type, `customExercise`/`routine`/`workout` wire
 * roots, exact g+1 generation, supplier call rules, closed step-6 exception
 * mapping), task-relevant normative doc sections and allowed harness patterns
 * (in-memory Room + raw SQL, MigrationTestHelper file-reopen, controllable
 * barrier/lease, exported schemas 1-12 column facts). No production
 * declarations/sources/diffs/new schema13/Worker work inspected, including via
 * LSP/generated/decompiled classes. All seeds and oracles are independent raw
 * SQL literals; wire expectations are structural/substring checks, never bytes
 * derived from a production encoder.
 *
 * Every root/child/reference/muscle/equipment ID in a valid graph is a
 * canonical UUID; only slugs/labels stay mnemonic. Invalid mnemonic IDs are
 * covered by the dedicated invalid-fixture test below, never weakened.
 */
@RunWith(AndroidJUnit4::class)
class LegacyImportCaptureTest {
    private lateinit var context: Context
    private lateinit var database: GymDatabase
    private lateinit var authority: RoomSessionAuthority

    private companion object {
        const val ORIGIN = "https://sync.example.test"
        const val ACCOUNT = "a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5"
        const val OTHER_ACCOUNT = "b2c3d4e5-f6a7-4b8c-9d0e-f1a2b3c4d5e6"
        const val OPERATION = "c3d4e5f6-a7b8-4c9d-8e0f-a1b2c3d4e5f6"
        const val OTHER_OPERATION = "d4e5f6a7-b8c9-4d0e-8f1a-b2c3d4e5f6a7"

        const val CUSTOM_RICH = "11111111-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        const val CUSTOM_MIN = "11111111-aaaa-4aaa-8aaa-aaaaaaaaaaa2"
        const val BUNDLED_REF = "22222222-bbbb-4bbb-8bbb-bbbbbbbbbbb1"
        const val ROUTINE_SAVED_BLANK = "33333333-cccc-4ccc-8ccc-cccccccccc01"
        const val ROUTINE_DRAFT_EMPTY = "33333333-cccc-4ccc-8ccc-cccccccccc02"
        const val ROUTINE_POPULATED = "33333333-cccc-4ccc-8ccc-cccccccccc03"
        const val WORKOUT_FINISHED = "44444444-dddd-4ddd-8ddd-dddddddddd01"
        const val WORKOUT_ACTIVE = "44444444-dddd-4ddd-8ddd-dddddddddd02"

        const val MUSCLE_A_ID = "a0a0a0a0-a0a0-4a0a-8a0a-a0a0a0a0a0a0"
        const val MUSCLE_B_ID = "b0b0b0b0-b0b0-4b0b-8b0b-b0b0b0b0b0b0"
        const val EQUIP_A_ID = "c0c0c0c0-c0c0-4c0c-8c0c-c0c0c0c0c0c0"

        const val WE_FIN = "d1d1d1d1-d1d1-4d1d-8d1d-d1d1d1d1d1d1"
        const val WS_FIN = "e1e1e1e1-e1e1-4e1e-8e1e-e1e1e1e1e1e1"
        const val WE_ACT = "d2d2d2d2-d2d2-4d2d-8d2d-d2d2d2d2d2d2"
        const val WS_ACT = "e2e2e2e2-e2e2-4e2e-8e2e-e2e2e2e2e2e2"
        const val RE_POP = "f1f1f1f1-f1f1-4f1f-8f1f-f1f1f1f1f1f1"
        const val RS_POP = "f2f2f2f2-f2f2-4f2f-8f2f-f2f2f2f2f2f2"
        const val WE_BAD = "b1b1b1b1-b1b1-4b1b-8b1b-b1b1b1b1b1b1"
        const val WS_BAD = "b2b2b2b2-b2b2-4b2b-8b2b-b2b2b2b2b2b2"
        const val SHARED_ROOT = "66666666-dddd-4ddd-8ddd-dddddddddddd"

        const val MUTATION_1 = "55555555-1111-4111-8111-111111111111"
        const val MUTATION_2 = "55555555-2222-4222-8222-222222222222"
        const val MUTATION_3 = "55555555-3333-4333-8333-333333333333"
        const val MUTATION_4 = "55555555-4444-4444-8444-444444444444"
        const val MUTATION_5 = "55555555-5555-4555-8555-555555555555"
        const val MUTATION_6 = "55555555-6666-4666-8666-666666666666"
        const val MUTATION_7 = "55555555-7777-4777-8777-777777777777"

        const val OUTBOX_SQL =
            "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id, mutation_type," +
                " local_generation, payload_version, payload_json, state, attempt_count," +
                " retry_after_millis, error_code, error_message FROM sync_outbox ORDER BY sequence"
    }

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        authority = RoomSessionAuthority(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // ---- Independent fakes/helpers (no production DAO/entity/encoder oracle) ----

    private inner class FakeLease(
        var passChecks: Int = Int.MAX_VALUE,
        var failure: Throwable = IllegalStateException("lease invalid"),
        var closeFailure: Throwable? = null,
    ) : ProjectionPublicationLease {
        var checks = 0
        var closes = 0
        var tripAfterOutboxWriteWith: Throwable? = null
        var outboxAtTrip: Int? = null
        var onClose: (() -> Unit)? = null

        override fun checkValid() {
            checks++
            val trip = tripAfterOutboxWriteWith
            if (trip != null && outboxAtTrip == null) {
                val staged = outboxStagedCount()
                if (staged > 0) {
                    outboxAtTrip = staged
                    throw trip
                }
            }
            if (checks > passChecks) throw failure
        }

        override fun close() {
            closes++
            onClose?.invoke()
            closeFailure?.let { throw it }
        }
    }

    private class FakeBarrier(val lease: FakeLease) : LegacyImportCaptureBarrier {
        var acquires = 0

        override suspend fun acquire(expected: RecoveryRoomSnapshot): ProjectionPublicationLease {
            acquires++
            return lease
        }
    }

    /** Test-only dispatcher that enqueues result dispatches until explicitly drained. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(pending) { pending.addLast(block) }
        }

        fun drain() {
            while (true) {
                val next = synchronized(pending) {
                    if (pending.isEmpty()) null else pending.removeFirst()
                } ?: return
                next.run()
            }
        }
    }

    private fun target(
        account: String = ACCOUNT,
        operation: String = OPERATION,
    ): LegacyImportTarget =
        LegacyImportTarget(
            origin = ServiceOrigin.parse(ORIGIN),
            accountId = account,
            operationId = operation,
        )

    private fun defaultMutations(): MutableList<String> =
        mutableListOf(MUTATION_1, MUTATION_2, MUTATION_3, MUTATION_4, MUTATION_5, MUTATION_6, MUTATION_7)

    private fun store(
        mutations: List<String> = defaultMutations(),
    ): LegacyImportStore {
        val lease = FakeLease()
        val ids = mutations.toMutableList()
        return LegacyImportStore(
            database = database,
            barrier = FakeBarrier(lease),
            processCheck = {},
            newMutationId = { ids.removeFirst() },
        )
    }

    private fun storeWithLease(
        lease: FakeLease,
        processCheck: () -> Unit = {},
        mutations: List<String> = defaultMutations(),
    ): LegacyImportStore {
        val ids = mutations.toMutableList()
        return LegacyImportStore(
            database = database,
            barrier = FakeBarrier(lease),
            processCheck = processCheck,
            newMutationId = { ids.removeFirst() },
        )
    }

    private fun outboxStagedCount(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_outbox").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun exec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun count(sql: String): Int =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun rows(sql: String, columns: Int): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun seedLookupTables() {
        exec("INSERT INTO muscle_groups (id, slug, name) VALUES ('$MUSCLE_A_ID', 'muscle-a', 'Muscle A')")
        exec("INSERT INTO muscle_groups (id, slug, name) VALUES ('$MUSCLE_B_ID', 'muscle-b', 'Muscle B')")
        exec("INSERT INTO equipment (id, name, type, usage_type) VALUES ('$EQUIP_A_ID', 'Dumbbell', 'weight', NULL)")
        // Bundled lookup reference only; never an import root.
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$BUNDLED_REF', 'Bundled Press', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Bundled step\"]', 0, NULL, NULL, 0, 'local_only', NULL)",
        )
    }

    private fun seedRichLocalGraph() {
        seedLookupTables()
        // Custom rich: optional metadata preserved, quantity, exact instructions.
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_RICH', 'Rich Custom', 'reps', 'beginner', 'push', 'compound'," +
                " 'strength', '[\"Step one\",\"Step two\"]', 1, NULL, NULL, 3, 'local_only', NULL)",
        )
        exec("INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES ('$CUSTOM_RICH', '$MUSCLE_A_ID', 'primary')")
        exec("INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES ('$CUSTOM_RICH', '$MUSCLE_B_ID', 'secondary')")
        exec("INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items) VALUES ('$CUSTOM_RICH', '$EQUIP_A_ID', 2)")
        // Custom minimal with NULL instructions (distinct from empty text).
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_MIN', 'Min Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " NULL, 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        // Saved routine with an actually blank name (no children) is a legal import.
        exec(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$ROUTINE_SAVED_BLANK', '', 0, NULL, NULL, 2, 'local_only', NULL)",
        )
        // Empty draft (no children) is a legal import.
        exec(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$ROUTINE_DRAFT_EMPTY', '', 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        // Populated routine with child/set fields participates in preservation.
        exec(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$ROUTINE_POPULATED', 'Populated Routine', 0, NULL, NULL, 6, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO routine_exercises (id, routine_id, exercise_id, position, rest_duration_seconds)" +
                " VALUES ('$RE_POP', '$ROUTINE_POPULATED', '$CUSTOM_MIN', 0, 45)",
        )
        exec(
            "INSERT INTO routine_sets (id, routine_exercise_id, position, repetitions, weight_kg)" +
                " VALUES ('$RS_POP', '$RE_POP', 0, 12, 20.0)",
        )
        // Finished workout: completed surviving set, nullable recorded rest, notes + configured rest.
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FINISHED', 'Finished W', 1000, 2000, 'finished', NULL, NULL, 4," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds) VALUES ('$WE_FIN', '$WORKOUT_FINISHED', '$CUSTOM_RICH', 0, 'Keep', 75)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_FIN', '$WE_FIN', 0, 8, 70.5, 1, 60)",
        )
        // Active workout last: no finish time, nullable recorded rest, repeated bundled reference.
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_ACTIVE', 'Active W', 3000, NULL, 'active', NULL, NULL, 5," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds) VALUES ('$WE_ACT', '$WORKOUT_ACTIVE', '$BUNDLED_REF', 0, '', 30)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_ACT', '$WE_ACT', 0, 10, NULL, 0, NULL)",
        )
    }

    private fun seedMinimalValidGraph() {
        seedLookupTables()
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_MIN', 'Min Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " NULL, 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FINISHED', 'Finished W', 1000, 2000, 'finished', NULL, NULL, 1," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds) VALUES ('$WE_FIN', '$WORKOUT_FINISHED', '$CUSTOM_MIN', 0, '', 30)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_FIN', '$WE_FIN', 0, 8, 10.0, 1, NULL)",
        )
    }

    private fun rootBusinessSnapshot(): Map<String, List<List<String?>>> =
        mapOf(
            "exercises" to rows(
                "SELECT id, name, type, difficulty_level, force_type, mechanics, category, instructions," +
                    " is_user_created, server_revision, deleted_at_millis FROM exercises ORDER BY id",
                11,
            ),
            "workouts" to rows(
                "SELECT id, name, started_at_millis, finished_at_millis, status, server_revision," +
                    " deleted_at_millis FROM workouts ORDER BY id",
                7,
            ),
            "routines" to rows(
                "SELECT id, name, is_draft, server_revision, deleted_at_millis FROM routines ORDER BY id",
                5,
            ),
        )

    private fun collectRecordKeys(value: Any, into: MutableSet<String>) {
        when (value) {
            is JSONObject -> {
                val names = value.keys()
                while (names.hasNext()) {
                    val key = names.next()
                    into.add(key)
                    collectRecordKeys(value.get(key), into)
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until value.length()) collectRecordKeys(value.get(i), into)
            }
            else -> Unit
        }
    }

    private fun graphSnapshot(): Map<String, List<List<String?>>> =
        mapOf(
            "exercises" to rows(
                "SELECT id, name, type, difficulty_level, force_type, mechanics, category, instructions," +
                    " is_user_created, account_id, server_revision, local_generation, sync_state," +
                    " deleted_at_millis FROM exercises ORDER BY id",
                14,
            ),
            "workouts" to rows(
                "SELECT id, name, started_at_millis, finished_at_millis, status, account_id," +
                    " server_revision, local_generation, sync_state, deleted_at_millis FROM workouts ORDER BY id",
                10,
            ),
            "workout_exercises" to rows(
                "SELECT id, workout_id, exercise_id, position, notes, rest_duration_seconds" +
                    " FROM workout_exercises ORDER BY id",
                6,
            ),
            "workout_sets" to rows(
                "SELECT id, workout_exercise_id, position, repetitions, weight_kg, is_completed," +
                    " rest_seconds FROM workout_sets ORDER BY id",
                7,
            ),
            "routines" to rows(
                "SELECT id, name, is_draft, account_id, server_revision, local_generation, sync_state," +
                    " deleted_at_millis FROM routines ORDER BY id",
                8,
            ),
            "routine_exercises" to rows(
                "SELECT id, routine_id, exercise_id, position, rest_duration_seconds" +
                    " FROM routine_exercises ORDER BY id",
                5,
            ),
            "routine_sets" to rows(
                "SELECT id, routine_exercise_id, position, repetitions, weight_kg FROM routine_sets ORDER BY id",
                5,
            ),
            "exercise_muscle_groups" to rows(
                "SELECT exercise_id, muscle_group_id, type FROM exercise_muscle_groups" +
                    " ORDER BY exercise_id, muscle_group_id",
                3,
            ),
            "exercise_equipment" to rows(
                "SELECT exercise_id, equipment_id, num_items FROM exercise_equipment ORDER BY exercise_id",
                3,
            ),
        )

    private fun assertClosedShape(e: LegacyImportException) {
        assertEquals("Legacy import failure", e.message)
        assertNull("cause must stay closed", e.cause)
        assertTrue("suppression must be disabled", e.suppressed.isEmpty())
        val rendered = e.toString()
        for (secret in listOf(ACCOUNT, OTHER_ACCOUNT, OPERATION, OTHER_OPERATION)) {
            assertFalse("display must not echo <$secret>", rendered.contains(secret))
        }
    }

    private suspend fun expectLegacyFailure(expected: LegacyImportFailure, block: suspend () -> Unit): LegacyImportException {
        try {
            block()
        } catch (e: LegacyImportException) {
            assertEquals(expected, e.failure)
            assertClosedShape(e)
            return e
        }
        fail("expected LegacyImportException($expected)")
        throw AssertionError("unreachable")
    }

    private suspend fun expectClosedLegacyException(block: suspend () -> Unit): LegacyImportException {
        try {
            block()
        } catch (e: LegacyImportException) {
            assertClosedShape(e)
            return e
        }
        fail("expected closed LegacyImportException")
        throw AssertionError("unreachable")
    }

    // ---- Empty install / refusal without importer ----

    @Test
    fun emptyInstall_returnsNoLocalDataWithNoJournalOrMutation() = kotlinx.coroutines.test.runTest {
        seedLookupTables()
        val expected = authority.read()
        assertEquals(0L, expected.revision)
        val result = store().capture(expected, target())
        assertTrue(result is LegacyImportCaptureResult.NoLocalData)
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_session_authority"))
        assertNull(store().read(target()))
    }

    @Test
    fun emptyInstall_makesNoSupplierCall() = kotlinx.coroutines.test.runTest {
        seedLookupTables()
        var calls = 0
        val ids = defaultMutations()
        val idle = LegacyImportStore(database, FakeBarrier(FakeLease()), {}, { calls++; ids.removeFirst() })
        assertTrue(idle.capture(authority.read(), target()) is LegacyImportCaptureResult.NoLocalData)
        assertEquals(0, calls)
    }

    @Test
    fun ordinaryPreparation_stillRefusesLocalRootsWithoutExplicitImporter() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val before = graphSnapshot()
            val fresh = authority.read()
            try {
                authority.beginPreparation(fresh, ServiceOrigin.parse(ORIGIN), ACCOUNT, OPERATION)
                fail("ordinary preparation must refuse local-only roots")
            } catch (e: RoomSessionAuthorityException) {
                assertEquals(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION, e.failure)
                assertEquals("Room session authority failure", e.message)
            }
            assertEquals(before, graphSnapshot())
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        }

    // ---- Rich valid graph ----

    @Test
    fun richValidGraph_capturesWithPreservedGraphAndOrderedImports() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val before = graphSnapshot()
            val businessBefore = rootBusinessSnapshot()
            val expected = authority.read()
            val result = store().capture(expected, target())
            assertTrue(result is LegacyImportCaptureResult.Retained)
            val evidence = (result as LegacyImportCaptureResult.Retained).evidence
            assertEquals(LegacyImportPhase.CAPTURED, evidence.phase)
            assertNull(evidence.errorCode)
            assertEquals(7L, evidence.importCount)
            assertTrue(evidence.sourceJson.contains(WORKOUT_FINISHED))
            assertTrue(evidence.sourceJson.contains(CUSTOM_RICH))
            assertTrue(evidence.sourceJson.contains(ROUTINE_POPULATED))

            // Root business columns unchanged except allowed account/generation/state
            // metadata; children/relationships fully unchanged.
            val after = graphSnapshot()
            val businessAfter = rootBusinessSnapshot()
            for ((key, rowsBefore) in businessBefore) {
                assertEquals("business $key", rowsBefore, businessAfter[key])
            }
            for (key in listOf("workout_exercises", "workout_sets", "routine_exercises", "routine_sets", "exercise_muscle_groups", "exercise_equipment")) {
                assertEquals(key, before[key], after[key])
            }
            // Bundled lookup untouched; custom/workout/routine roots associated, server revision stays null.
            assertEquals(
                1,
                count("SELECT COUNT(*) FROM exercises WHERE id = '$BUNDLED_REF' AND account_id IS NULL"),
            )
            for (id in listOf(CUSTOM_RICH, CUSTOM_MIN)) {
                rows("SELECT account_id, server_revision FROM exercises WHERE id = '$id'", 2).single().let {
                    assertEquals(ACCOUNT, it[0])
                    assertNull(it[1])
                }
            }
            for (id in listOf(ROUTINE_SAVED_BLANK, ROUTINE_DRAFT_EMPTY, ROUTINE_POPULATED)) {
                rows("SELECT account_id, server_revision FROM routines WHERE id = '$id'", 2).single().let {
                    assertEquals(ACCOUNT, it[0])
                    assertNull(it[1])
                }
            }
            for (id in listOf(WORKOUT_FINISHED, WORKOUT_ACTIVE)) {
                rows("SELECT account_id, server_revision FROM workouts WHERE id = '$id'", 2).single().let {
                    assertEquals(ACCOUNT, it[0])
                    assertNull(it[1])
                }
            }
            // Each eligible root advances exactly g+1 with no ledger.
            val generations = mapOf(
                "SELECT local_generation FROM exercises WHERE id = '$CUSTOM_RICH'" to "4",
                "SELECT local_generation FROM exercises WHERE id = '$CUSTOM_MIN'" to "2",
                "SELECT local_generation FROM routines WHERE id = '$ROUTINE_SAVED_BLANK'" to "3",
                "SELECT local_generation FROM routines WHERE id = '$ROUTINE_DRAFT_EMPTY'" to "2",
                "SELECT local_generation FROM routines WHERE id = '$ROUTINE_POPULATED'" to "7",
                "SELECT local_generation FROM workouts WHERE id = '$WORKOUT_FINISHED'" to "5",
                "SELECT local_generation FROM workouts WHERE id = '$WORKOUT_ACTIVE'" to "6",
            )
            for ((sql, want) in generations) {
                assertEquals(sql, want, rows(sql, 1).single()[0])
            }

            // Dependency order: customs (UUID order), routines (UUID order), finished, active last.
            val ordered = rows("SELECT aggregate_type, aggregate_id FROM sync_outbox ORDER BY sequence", 2)
            assertEquals(
                listOf(
                    listOf("customExercise", CUSTOM_RICH),
                    listOf("customExercise", CUSTOM_MIN),
                    listOf("routine", ROUTINE_SAVED_BLANK),
                    listOf("routine", ROUTINE_DRAFT_EMPTY),
                    listOf("routine", ROUTINE_POPULATED),
                    listOf("workout", WORKOUT_FINISHED),
                    listOf("workout", WORKOUT_ACTIVE),
                ),
                ordered,
            )
            assertEquals(
                listOf(listOf("aggregate.import")),
                rows("SELECT DISTINCT mutation_type FROM sync_outbox", 1),
            )
            // Exact request bytes survive as entries evidence.
            val outboxByMutation = rows("SELECT mutation_id, payload_json FROM sync_outbox ORDER BY sequence", 2)
                .associate { it[0]!! to it[1]!! }
            val entries = rows(
                "SELECT ordinal, aggregate_type, aggregate_id, mutation_id, sequence, payload_json" +
                    " FROM sync_legacy_import_entries ORDER BY ordinal",
                6,
            )
            assertEquals(7, entries.size)
            entries.forEachIndexed { index, row ->
                assertEquals(index.toString(), row[0])
                assertEquals(outboxByMutation[row[3]], row[5])
                assertTrue(row[5]!!.contains(row[2]!!))
            }

            // Independent wire gate: parse each v1 wrapper, envelope and record from
            // contract shapes only (never production encoders). Times compare via
            // Instant epoch millis, never incidental RFC3339 spelling.
            val forbiddenRecordKeys = setOf(
                "revision", "serverRevision", "server_revision", "owner", "ownerId",
                "accountId", "account_id", "totalVolumeKg", "exerciseName", "displayName",
                "displayNames", "isUserCreated",
            )
            val wrapperRows = rows("SELECT sequence, mutation_id, payload_json FROM sync_outbox ORDER BY sequence", 3)
            assertEquals(7, wrapperRows.size)
            val parsedOrder = mutableListOf<Triple<String, String, Long>>()
            val parsedById = mutableMapOf<String, JSONObject>()
            for (row in wrapperRows) {
                val wrapper = JSONObject(row[2]!!)
                assertEquals(1, wrapper.getInt("payloadVersion"))
                val request = JSONObject(wrapper.getString("requestJson"))
                assertEquals(row[1], request.getString("mutationId"))
                assertEquals("aggregate.import", request.getString("type"))
                val aggregateType = request.getString("aggregateType")
                val aggregateId = request.getString("aggregateId")
                val generation = request.getLong("localGeneration")
                assertTrue("positive generation for $aggregateId", generation >= 1L)
                assertTrue(
                    "no knownServerRevision on fresh import for $aggregateId",
                    !request.has("knownServerRevision") || request.isNull("knownServerRevision"),
                )
                val roots = wrapper.getJSONArray("affectedRoots")
                assertEquals("main root only for $aggregateId", 1, roots.length())
                roots.getJSONObject(0).let {
                    assertEquals(aggregateType, it.getString("aggregateType"))
                    assertEquals(aggregateId, it.getString("aggregateId"))
                    assertEquals(generation, it.getLong("localGeneration"))
                }
                val record = request.getJSONObject("payload").getJSONObject("record")
                assertEquals("record id matches envelope for $aggregateId", aggregateId, record.getString("id"))
                val keys = mutableSetOf<String>()
                collectRecordKeys(record, keys)
                val leaked = keys.intersect(forbiddenRecordKeys)
                assertTrue("no owner/revision/derived/label keys in $aggregateId: $leaked", leaked.isEmpty())
                parsedOrder.add(Triple(request.getString("mutationId"), "$aggregateType:$aggregateId", generation))
                parsedById["$aggregateType:$aggregateId"] = record
            }
            assertEquals(
                listOf(
                    Triple(MUTATION_1, "customExercise:$CUSTOM_RICH", 4L),
                    Triple(MUTATION_2, "customExercise:$CUSTOM_MIN", 2L),
                    Triple(MUTATION_3, "routine:$ROUTINE_SAVED_BLANK", 3L),
                    Triple(MUTATION_4, "routine:$ROUTINE_DRAFT_EMPTY", 2L),
                    Triple(MUTATION_5, "routine:$ROUTINE_POPULATED", 7L),
                    Triple(MUTATION_6, "workout:$WORKOUT_FINISHED", 5L),
                    Triple(MUTATION_7, "workout:$WORKOUT_ACTIVE", 6L),
                ),
                parsedOrder,
            )
            parsedById["customExercise:$CUSTOM_RICH"]!!.let {
                assertEquals("Rich Custom", it.getString("name"))
                assertEquals("beginner", it.getString("difficultyLevel"))
                assertEquals("push", it.getString("forceType"))
                assertEquals("compound", it.getString("mechanics"))
                assertEquals("strength", it.getString("category"))
                val instructions = it.getJSONArray("instructions")
                assertEquals(2, instructions.length())
                assertEquals("Step one", instructions.getString(0))
                assertEquals("Step two", instructions.getString(1))
                assertEquals(MUSCLE_A_ID, it.getString("primaryMuscleGroupId"))
                val secondary = it.getJSONArray("secondaryMuscleGroupIds")
                assertEquals(1, secondary.length())
                assertEquals(MUSCLE_B_ID, secondary.getString(0))
                assertEquals(EQUIP_A_ID, it.getString("equipmentId"))
                assertEquals(2, it.getInt("equipmentNumItems"))
            }
            parsedById["customExercise:$CUSTOM_MIN"]!!.let {
                assertEquals("Min Custom", it.getString("name"))
                assertEquals("strength", it.getString("category"))
                assertTrue(it.isNull("primaryMuscleGroupId"))
                assertTrue(it.isNull("equipmentId"))
                assertTrue(it.isNull("equipmentNumItems"))
                assertTrue(it.isNull("difficultyLevel"))
                assertTrue(it.isNull("forceType"))
                assertTrue(it.isNull("mechanics"))
                assertTrue(it.isNull("instructions"))
                if (it.has("secondaryMuscleGroupIds") && !it.isNull("secondaryMuscleGroupIds")) {
                    assertEquals(0, it.getJSONArray("secondaryMuscleGroupIds").length())
                }
            }
            parsedById["routine:$ROUTINE_SAVED_BLANK"]!!.let {
                assertEquals("", it.getString("name"))
                assertEquals(false, it.getBoolean("isDraft"))
                assertEquals(0, it.getJSONArray("exercises").length())
            }
            parsedById["routine:$ROUTINE_DRAFT_EMPTY"]!!.let {
                assertEquals("", it.getString("name"))
                assertEquals(true, it.getBoolean("isDraft"))
                assertEquals(0, it.getJSONArray("exercises").length())
            }
            parsedById["routine:$ROUTINE_POPULATED"]!!.let {
                assertEquals("Populated Routine", it.getString("name"))
                assertEquals(false, it.getBoolean("isDraft"))
                val exercises = it.getJSONArray("exercises")
                assertEquals(1, exercises.length())
                exercises.getJSONObject(0).let { child ->
                    assertEquals(RE_POP, child.getString("id"))
                    assertEquals(CUSTOM_MIN, child.getString("exerciseId"))
                    assertEquals(0, child.getInt("position"))
                    assertEquals(45, child.getInt("restDurationSeconds"))
                    val sets = child.getJSONArray("sets")
                    assertEquals(1, sets.length())
                    sets.getJSONObject(0).let { set ->
                        assertEquals(RS_POP, set.getString("id"))
                        assertEquals(0, set.getInt("position"))
                        assertEquals(12, set.getInt("repetitions"))
                        assertEquals(20.0, set.getDouble("weightKg"), 0.0)
                    }
                }
            }
            parsedById["workout:$WORKOUT_FINISHED"]!!.let {
                assertEquals("Finished W", it.getString("name"))
                assertEquals("finished", it.getString("status"))
                assertEquals(1000L, Instant.parse(it.getString("startedAt")).toEpochMilli())
                assertEquals(2000L, Instant.parse(it.getString("finishedAt")).toEpochMilli())
                val exercises = it.getJSONArray("exercises")
                assertEquals(1, exercises.length())
                exercises.getJSONObject(0).let { child ->
                    assertEquals(WE_FIN, child.getString("id"))
                    assertEquals(CUSTOM_RICH, child.getString("exerciseId"))
                    assertEquals(0, child.getInt("position"))
                    assertEquals("Keep", child.getString("notes"))
                    assertEquals(75, child.getInt("restDurationSeconds"))
                    val sets = child.getJSONArray("sets")
                    assertEquals(1, sets.length())
                    sets.getJSONObject(0).let { set ->
                        assertEquals(WS_FIN, set.getString("id"))
                        assertEquals(0, set.getInt("position"))
                        assertEquals(8, set.getInt("repetitions"))
                        assertEquals(70.5, set.getDouble("weightKg"), 0.0)
                        assertEquals(true, set.getBoolean("isCompleted"))
                        assertEquals(60, set.getInt("restSeconds"))
                    }
                }
            }
            parsedById["workout:$WORKOUT_ACTIVE"]!!.let {
                assertEquals("Active W", it.getString("name"))
                assertEquals("active", it.getString("status"))
                assertEquals(3000L, Instant.parse(it.getString("startedAt")).toEpochMilli())
                assertTrue(it.isNull("finishedAt"))
                val exercises = it.getJSONArray("exercises")
                assertEquals(1, exercises.length())
                exercises.getJSONObject(0).let { child ->
                    assertEquals(WE_ACT, child.getString("id"))
                    assertEquals(BUNDLED_REF, child.getString("exerciseId"))
                    assertEquals(0, child.getInt("position"))
                    assertEquals("", child.getString("notes"))
                    assertEquals(30, child.getInt("restDurationSeconds"))
                    val sets = child.getJSONArray("sets")
                    assertEquals(1, sets.length())
                    sets.getJSONObject(0).let { set ->
                        assertEquals(WS_ACT, set.getString("id"))
                        assertEquals(0, set.getInt("position"))
                        assertEquals(10, set.getInt("repetitions"))
                        assertTrue(set.isNull("weightKg"))
                        assertEquals(false, set.getBoolean("isCompleted"))
                        assertTrue(set.isNull("restSeconds"))
                    }
                }
            }
            // Private preparation authority only, no ACTIVE/cursor/baseline manufacture.
            val reread = authority.read()
            assertEquals(1L, reread.revision)
            assertEquals(1L, reread.sessionGeneration)
            assertEquals(RecoveryRoomPhase.PREPARING, reread.phase)
            assertEquals(ACCOUNT, reread.cachedAccountId)
            assertEquals(RecoveryIdentity(ServiceSessionBinding(ServiceOrigin.parse(ORIGIN), ACCOUNT, 1L), OPERATION), reread.identity)
            assertFalse(reread.baselineComplete)
            assertEquals(reread, evidence.room)
            // Accepted capture maintains an incomplete dirty cache with no materialized baseline.
            rows(
                "SELECT account_id, baseline_complete, projection_dirty, materialized_epoch" +
                    " FROM sync_account_cache",
                4,
            ).single().let {
                assertEquals(ACCOUNT, it[0])
                assertEquals("0", it[1])
                assertEquals("1", it[2])
                assertEquals("0", it[3])
            }
            // One unobserved watermark per import with exact per-root generation watermarks.
            val expectedWatermarks = mapOf(
                "customExercise:$CUSTOM_RICH" to "4",
                "customExercise:$CUSTOM_MIN" to "2",
                "routine:$ROUTINE_SAVED_BLANK" to "3",
                "routine:$ROUTINE_DRAFT_EMPTY" to "2",
                "routine:$ROUTINE_POPULATED" to "7",
                "workout:$WORKOUT_FINISHED" to "5",
                "workout:$WORKOUT_ACTIVE" to "6",
            )
            val canonicals = rows(
                "SELECT aggregate_type, aggregate_id, server_revision, canonical_state," +
                    " canonical_json, local_generation_watermark FROM sync_canonical_roots" +
                    " ORDER BY aggregate_type, aggregate_id",
                6,
            )
            assertEquals(7, canonicals.size)
            for (row in canonicals) {
                val key = "${row[0]}:${row[1]}"
                assertNull("server_revision must stay null for $key", row[2])
                assertEquals("unobserved", row[3])
                assertNull("canonical_json must stay null for $key", row[4])
                assertEquals("watermark for $key", expectedWatermarks[key], row[5])
            }

            // Journal singleton shape with exact fences.
            rows(
                "SELECT id, evidence_version, service_origin, account_id, operation_id, phase," +
                    " import_count, error_code, source_revision, source_session_generation," +
                    " room_revision, room_session_generation FROM sync_legacy_import",
                12,
            ).single().let {
                assertEquals("1", it[0])
                assertEquals("1", it[1])
                val journalOrigin = it[2]!!
                assertEquals("$ORIGIN:443", journalOrigin)
                assertEquals(
                    journalOrigin,
                    rows("SELECT service_origin FROM sync_session_authority WHERE id = 1", 1).single()[0],
                )
                assertEquals(ACCOUNT, it[3])
                assertEquals(OPERATION, it[4])
                assertEquals("captured", it[5])
                assertEquals("7", it[6])
                assertNull(it[7])
                assertEquals("0", it[8])
                assertEquals("0", it[9])
                assertEquals("1", it[10])
                assertEquals("1", it[11])
            }
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import WHERE source_json IS NOT NULL"))
            // Durable reread recovers same work.
            val recovered = store().read(target())
            assertNotNull(recovered)
            assertEquals(evidence.sourceJson, recovered!!.sourceJson)
            assertEquals(evidence.importCount, recovered.importCount)
        }

    @Test
    fun supplierCalls_exactlyOncePerImportAndNoneOnIdempotentRecovery() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            var calls = 0
            val ids = defaultMutations()
            val counting = LegacyImportStore(database, FakeBarrier(FakeLease()), {}, { calls++; ids.removeFirst() })
            val retained = counting.capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(7L, retained.evidence.importCount)
            assertEquals(7, calls)
            counting.capture(authority.read(), target())
            assertEquals(7, calls)
        }

    @Test
    fun repeatedCapture_returnsIdenticalEvidenceWithoutSecondAllocation() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val firstStore = store()
            val first = firstStore.capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            val outboxBefore = rows("SELECT mutation_id, sequence FROM sync_outbox ORDER BY sequence", 2)
            val second = firstStore.capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(first.evidence.sourceJson, second.evidence.sourceJson)
            assertEquals(first.evidence.importCount, second.evidence.importCount)
            assertEquals(outboxBefore, rows("SELECT mutation_id, sequence FROM sync_outbox ORDER BY sequence", 2))
        }

    @Test
    fun fileReopen_recoversIdenticalSourceAndRequestIdentitiesAndIdempotent() = kotlinx.coroutines.test.runTest {
        val dbName = "qa-legacy-reopen.db"
        context.deleteDatabase(dbName)
        var capturedSource = ""
        var capturedOutbox: List<List<String?>> = emptyList()
        val fileDb = Room.databaseBuilder(context, GymDatabase::class.java, dbName)
            .addMigrations(*GymDatabase.MIGRATIONS)
            .build()
        try {
            database = fileDb
            authority = RoomSessionAuthority(fileDb)
            seedRichLocalGraph()
            val ids = defaultMutations()
            val fileStore = LegacyImportStore(fileDb, FakeBarrier(FakeLease()), {}, { ids.removeFirst() })
            val retained = fileStore.capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            capturedSource = retained.evidence.sourceJson
            capturedOutbox = rows(OUTBOX_SQL, 14)
            // Lost in-memory return: close without any further in-memory read.
        } finally {
            fileDb.close()
        }
        val reopened = Room.databaseBuilder(context, GymDatabase::class.java, dbName)
            .addMigrations(*GymDatabase.MIGRATIONS)
            .build()
        try {
            database = reopened
            authority = RoomSessionAuthority(reopened)
            val recovered = store().read(target())
            assertNotNull(recovered)
            assertEquals(capturedSource, recovered!!.sourceJson)
            assertEquals(7L, recovered.importCount)
            assertEquals(capturedOutbox, rows(OUTBOX_SQL, 14))
            // Exact stored wrapper bytes survive reopen; recapture is idempotent.
            val again = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(capturedSource, again.evidence.sourceJson)
            assertEquals(capturedOutbox, rows(OUTBOX_SQL, 14))
        } finally {
            reopened.close()
            context.deleteDatabase(dbName)
            database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
            authority = RoomSessionAuthority(database)
        }
    }

    @Test
    fun staleExpectation_failsWithoutWrites() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val fresh = authority.read()
        val stale = RecoveryRoomSnapshot(
            revision = 99L,
            sessionGeneration = 99L,
            cachedAccountId = null,
            phase = RecoveryRoomPhase.SIGNED_OUT,
            identity = null,
            baselineComplete = false,
        )
        // Sanity: stale differs from current authority.
        assertFalse(stale == fresh)
        expectLegacyFailure(LegacyImportFailure.STALE_AUTHORITY) {
            store().capture(stale, target())
        }
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
    }

    // ---- Invalid source classes block without association/outbox ----

    @Test
    fun finishedWithoutCompletedSet_blocksWithInvalidSourceEvidence() =
        kotlinx.coroutines.test.runTest {
            seedLookupTables()
            exec(
                "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                    " instructions, is_user_created, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis)" +
                    " VALUES ('$CUSTOM_MIN', 'Min Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                    " NULL, 1, NULL, NULL, 1, 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                    " server_revision, local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('$WORKOUT_FINISHED', 'Bad Finished', 1000, 2000, 'finished', NULL, NULL, 1," +
                    " 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                    " rest_duration_seconds) VALUES ('$WE_BAD', '$WORKOUT_FINISHED', '$CUSTOM_MIN', 0, '', 30)",
            )
            exec(
                "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                    " is_completed, rest_seconds) VALUES ('$WS_BAD', '$WE_BAD', 0, 8, 10.0, 0, NULL)",
            )
            val before = graphSnapshot()
            val countingIds = defaultMutations()
            var calls = 0
            val counting = LegacyImportStore(database, FakeBarrier(FakeLease()), {}, { calls++; countingIds.removeFirst() })
            val result = counting.capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
            assertEquals(0L, result.evidence.importCount)
            assertEquals("invalid_source", result.evidence.errorCode)
            assertEquals(0, calls)
            assertEquals(before, graphSnapshot())
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_canonical_roots"))
            val reread = authority.read()
            assertEquals(1L, reread.revision)
            assertEquals(RecoveryRoomPhase.SIGNED_OUT, reread.phase)
            assertNull(reread.cachedAccountId)
        }

    @Test
    fun malformedInstructionsAndScalarCoercion_blockAndRetainRawEvidence() =
        kotlinx.coroutines.test.runTest {
            seedLookupTables()
            exec(
                "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                    " instructions, is_user_created, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis)" +
                    " VALUES ('$CUSTOM_RICH', 'Bad Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                    " 'not-json', 1, NULL, NULL, 1, 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                    " server_revision, local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('$WORKOUT_FINISHED', 'Bad Types', 1000, 2000, 'finished', NULL, NULL, 1," +
                    " 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                    " rest_duration_seconds) VALUES ('$WE_BAD', '$WORKOUT_FINISHED', '$CUSTOM_RICH', 0, '', 30)",
            )
            // Malformed REAL type for an INTEGER column plus invalid boolean flag.
            exec(
                "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                    " is_completed, rest_seconds) VALUES ('$WS_BAD', '$WE_BAD', 0, 8, 'heavy', 2, NULL)",
            )
            val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
            assertEquals("invalid_source", result.evidence.errorCode)
            assertTrue(result.evidence.sourceJson.contains("not-json"))
            assertTrue(result.evidence.sourceJson.contains("heavy"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        }

    @Test
    fun mnemonicChildAndReferenceIds_areInvalidSourceNotSilentlyRepaired() =
        kotlinx.coroutines.test.runTest {
            // Plain mnemonic strings intentionally make the graph invalid.
            exec("INSERT INTO muscle_groups (id, slug, name) VALUES ('muscle-a', 'muscle-a', 'Muscle A')")
            exec("INSERT INTO equipment (id, name, type, usage_type) VALUES ('equip-a', 'Dumbbell', 'weight', NULL)")
            exec(
                "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                    " instructions, is_user_created, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis)" +
                    " VALUES ('$CUSTOM_MIN', 'Min Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                    " NULL, 1, NULL, NULL, 1, 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                    " server_revision, local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('$WORKOUT_FINISHED', 'Mnemonic W', 1000, 2000, 'finished', NULL, NULL, 1," +
                    " 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                    " rest_duration_seconds) VALUES ('we-fin', '$WORKOUT_FINISHED', '$CUSTOM_MIN', 0, '', 30)",
            )
            exec(
                "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                    " is_completed, rest_seconds) VALUES ('ws-fin', 'we-fin', 0, 8, 10.0, 1, NULL)",
            )
            val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
            assertEquals("invalid_source", result.evidence.errorCode)
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(1, count("SELECT COUNT(*) FROM workout_exercises WHERE id = 'we-fin'"))
        }

    @Test
    fun nullableRootWithNonlocalMetadata_blocksWithoutRepair() = kotlinx.coroutines.test.runTest {
        seedLookupTables()
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FINISHED', 'Tomb', 1000, 2000, 'finished', NULL, NULL, 1," +
                " 'local_only', 9000)",
        )
        val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        // Tombstone retained, not repaired or excluded silently.
        assertEquals(1, count("SELECT COUNT(*) FROM workouts WHERE id = '$WORKOUT_FINISHED'"))
    }

    // ---- Overflow / conflict / tamper roll back with zero partial state ----

    @Test
    fun rootGenerationOverflow_failsCounterOverflowWithoutPartialState() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            exec("UPDATE exercises SET local_generation = 9223372036854775807 WHERE id = '$CUSTOM_MIN'")
            val before = graphSnapshot()
            val lease = FakeLease()
            expectLegacyFailure(LegacyImportFailure.COUNTER_OVERFLOW) {
                storeWithLease(lease).capture(authority.read(), target())
            }
            assertEquals(before, graphSnapshot())
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(0L, authority.read().revision)
            assertEquals(1, lease.closes)
        }

    @Test
    fun duplicateMutationId_afterStagedWrites_rollsBackClosed() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val before = graphSnapshot()
            val lease = FakeLease()
            val duplicateIds = mutableListOf(MUTATION_1, MUTATION_1, MUTATION_3, MUTATION_4, MUTATION_5, MUTATION_6, MUTATION_7)
            val conflicting = LegacyImportStore(database, FakeBarrier(lease), {}, { duplicateIds.removeFirst() })
            expectLegacyFailure(LegacyImportFailure.UNAVAILABLE) {
                conflicting.capture(authority.read(), target())
            }
            assertEquals(before, graphSnapshot())
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(0L, authority.read().revision)
            assertEquals(1, lease.closes)
        }

    @Test
    fun entryAppendTriggerFailure_rollsBackUnavailable() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val before = graphSnapshot()
        exec(
            "CREATE TRIGGER qa_abort_entry BEFORE INSERT ON sync_legacy_import_entries" +
                " BEGIN SELECT RAISE(ABORT, 'qa abort'); END",
        )
        val lease = FakeLease()
        expectLegacyFailure(LegacyImportFailure.UNAVAILABLE) {
            storeWithLease(lease).capture(authority.read(), target())
        }
        assertEquals(before, graphSnapshot())
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0L, authority.read().revision)
        assertEquals(1, lease.closes)
    }

    @Test
    fun finalTriggerTamper_rollsBackClosedWithoutPartialState() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val before = graphSnapshot()
        exec(
            "CREATE TRIGGER qa_tamper_outbox AFTER INSERT ON sync_outbox" +
                " BEGIN UPDATE workouts SET local_generation = local_generation + 100" +
                " WHERE id = '$WORKOUT_FINISHED'; END",
        )
        val lease = FakeLease()
        expectClosedLegacyException {
            storeWithLease(lease).capture(authority.read(), target())
        }
        assertEquals(before, graphSnapshot())
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0L, authority.read().revision)
        assertEquals(1, lease.closes)
    }

    // ---- Missing/mismatched retained evidence fails closed, never empty success ----

    @Test
    fun missingOutboxRow_failsCorruptRetainingBytes() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        store().capture(authority.read(), target())
        exec("DELETE FROM sync_outbox WHERE sequence = (SELECT MIN(sequence) FROM sync_outbox)")
        expectLegacyFailure(LegacyImportFailure.CORRUPT_EVIDENCE) {
            store().read(target())
        }
        assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(7, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
    }

    @Test
    fun mismatchedOutboxPayload_failsCorruptAndRestoreRecovers() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val retained = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        val original = rows("SELECT payload_json FROM sync_outbox ORDER BY sequence LIMIT 1", 1).single()[0]!!
        exec("UPDATE sync_outbox SET payload_json = '{\"tampered\":true}' WHERE sequence = (SELECT MIN(sequence) FROM sync_outbox)")
        expectLegacyFailure(LegacyImportFailure.CORRUPT_EVIDENCE) {
            store().read(target())
        }
        exec("UPDATE sync_outbox SET payload_json = '$original' WHERE sequence = (SELECT MIN(sequence) FROM sync_outbox)")
        val recovered = store().read(target())
        assertNotNull(recovered)
        assertEquals(retained.evidence.sourceJson, recovered!!.sourceJson)
    }

    @Test
    fun mismatchedEntryPayload_failsCorrupt() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        store().capture(authority.read(), target())
        exec("UPDATE sync_legacy_import_entries SET payload_json = '{\"tampered\":true}' WHERE ordinal = 0")
        expectLegacyFailure(LegacyImportFailure.CORRUPT_EVIDENCE) {
            store().read(target())
        }
        assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
    }

    @Test
    fun malformedSourceJson_failsCorrupt() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        store().capture(authority.read(), target())
        exec("UPDATE sync_legacy_import SET source_json = 'not json' WHERE id = 1")
        expectLegacyFailure(LegacyImportFailure.CORRUPT_EVIDENCE) {
            store().read(target())
        }
        assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
    }

    // ---- Foreign storage / drift / corruption fail closed ----

    @Test
    fun foreignAccountRoot_refusesWithoutCopyOrAdopt() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        exec("UPDATE workouts SET account_id = '$OTHER_ACCOUNT' WHERE id = '$WORKOUT_FINISHED'")
        expectLegacyFailure(LegacyImportFailure.FOREIGN_STORAGE) {
            store().capture(authority.read(), target())
        }
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(
            OTHER_ACCOUNT,
            rows("SELECT account_id FROM workouts WHERE id = '$WORKOUT_FINISHED'", 1).single()[0],
        )
    }

    @Test
    fun originAccountOperationDrift_failsTargetMismatchWithoutRetarget() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val retained = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertNotNull(retained)
            expectLegacyFailure(LegacyImportFailure.TARGET_MISMATCH) {
                store().read(target(account = OTHER_ACCOUNT))
            }
            expectLegacyFailure(LegacyImportFailure.TARGET_MISMATCH) {
                store().read(target(operation = OTHER_OPERATION))
            }
            // Original evidence unchanged.
            val reread = store().read(target())
            assertEquals(retained.evidence.sourceJson, reread!!.sourceJson)
        }

    @Test
    fun corruptSingletonAndOrphanEntries_failClosedRetainingBytes() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            store().capture(authority.read(), target())
            // Extra singleton row corrupts the journal.
            exec(
                "INSERT INTO sync_legacy_import (id, evidence_version, service_origin, account_id, operation_id," +
                    " source_revision, source_session_generation, room_revision, room_session_generation," +
                    " phase, source_json, import_count, error_code)" +
                    " VALUES (2, 1, '$ORIGIN:443', '$ACCOUNT', '$OPERATION', 0, 0, 1, 1, 'captured', '{}', 0, NULL)",
            )
            expectLegacyFailure(LegacyImportFailure.CORRUPT_EVIDENCE) {
                store().read(target())
            }
            exec("DELETE FROM sync_legacy_import WHERE id = 2")
            // Orphan entry (no journal) also fails closed, never empty success.
            exec("DELETE FROM sync_legacy_import")
            assertTrue(count("SELECT COUNT(*) FROM sync_legacy_import_entries") > 0)
            expectLegacyFailure(LegacyImportFailure.CORRUPT_EVIDENCE) {
                store().read(target())
            }
        }

    // ---- Fencing / leases / process checks / redaction ----

    @Test
    fun nullAccountRepository_refusedAfterCaptureWhileNoJournalKeepsPriorBehavior() =
        kotlinx.coroutines.test.runTest {
            // No journal: null-account fenced write keeps prior behavior (old local fence).
            val idle = SyncStore(database, RepositorySession.SIGNED_OUT) { MUTATION_1 }
            idle.withFencedTransaction { }
            seedRichLocalGraph()
            store().capture(authority.read(), target())
            // After capture/block, freshly constructed null-account repository is refused.
            val fenced = SyncStore(database, RepositorySession.SIGNED_OUT) { MUTATION_1 }
            try {
                fenced.withFencedTransaction { }
                fail("null-account write must be refused after legacy journal")
            } catch (e: LegacyImportException) {
                assertTrue(e.failure == LegacyImportFailure.CORRUPT_EVIDENCE || e.failure == LegacyImportFailure.STALE_AUTHORITY)
            }
        }

    @Test
    fun invalidatedLease_failsUnavailableWithRollbackAndSingleClose() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val before = graphSnapshot()
            val lease = FakeLease(passChecks = 0)
            expectLegacyFailure(LegacyImportFailure.UNAVAILABLE) {
                storeWithLease(lease).capture(authority.read(), target())
            }
            assertEquals(before, graphSnapshot())
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(0L, authority.read().revision)
            assertEquals(1, lease.closes)
        }

    @Test
    fun lostProcessCheck_failsUnavailableWithRollbackAndSingleClose() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val before = graphSnapshot()
            val lease = FakeLease()
            val barrier = FakeBarrier(lease)
            val ids = defaultMutations()
            val gated = LegacyImportStore(
                database = database,
                barrier = barrier,
                processCheck = { if (barrier.acquires > 0) throw IllegalStateException("process lost") },
                newMutationId = { ids.removeFirst() },
            )
            expectLegacyFailure(LegacyImportFailure.UNAVAILABLE) {
                gated.capture(authority.read(), target())
            }
            assertEquals(before, graphSnapshot())
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(0L, authority.read().revision)
            assertEquals(1, lease.closes)
        }

    @Test
    fun explicitLegacyExceptionFromProcessCheck_preservesCategory() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val lease = FakeLease()
            val barrier = FakeBarrier(lease)
            val ids = defaultMutations()
            val gated = LegacyImportStore(
                database = database,
                barrier = barrier,
                processCheck = { if (barrier.acquires > 0) throw LegacyImportException(LegacyImportFailure.FOREIGN_STORAGE) },
                newMutationId = { ids.removeFirst() },
            )
            expectLegacyFailure(LegacyImportFailure.FOREIGN_STORAGE) {
                gated.capture(authority.read(), target())
            }
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(1, lease.closes)
        }

    @Test
    fun leaseCancellationMidway_propagatesCancellationWithRollback() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val before = graphSnapshot()
            val lease = FakeLease(passChecks = 1, failure = CancellationException("lease lost"))
            val cancelling = storeWithLease(lease)
            try {
                cancelling.capture(authority.read(), target())
                fail("lease cancellation must propagate")
            } catch (e: CancellationException) {
                assertEquals("lease lost", e.message)
            }
            assertEquals(before, graphSnapshot())
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(1, lease.closes)
        }

    @Test
    fun evidenceAndException_redactPrivateDomainValues() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val retained = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        val rendered = retained.evidence.toString()
        assertFalse(rendered.contains("Keep"))
        assertFalse(rendered.contains(ACCOUNT))
        assertFalse(rendered.contains(OPERATION))
        assertFalse(rendered.contains(CUSTOM_RICH))
        val targetRendered = target().toString()
        assertFalse(targetRendered.contains(ACCOUNT))
        expectLegacyFailure(LegacyImportFailure.TARGET_MISMATCH) {
            store().read(target(account = OTHER_ACCOUNT))
        }
    }

    @Test
    fun concurrentCapture_commitsOnceWithoutDuplicate() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val expected = authority.read()
        // Catch the loser inside each child: structured concurrency must not
        // cancel the parent; exactly one caller commits, the other goes stale.
        val first = async {
            try {
                Result.success(store().capture(expected, target()))
            } catch (e: LegacyImportException) {
                Result.failure<LegacyImportCaptureResult>(e)
            }
        }
        val second = async {
            try {
                Result.success(store().capture(expected, target()))
            } catch (e: LegacyImportException) {
                Result.failure<LegacyImportCaptureResult>(e)
            }
        }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.first { it.isSuccess }.getOrNull() is LegacyImportCaptureResult.Retained)
        val loser = results.first { it.isFailure }.exceptionOrNull() as LegacyImportException
        assertEquals(LegacyImportFailure.STALE_AUTHORITY, loser.failure)
        assertEquals("Legacy import failure", loser.message)
        assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        val outboxIds = rows("SELECT DISTINCT mutation_id FROM sync_outbox", 1)
        assertEquals(outboxIds.size, rows("SELECT mutation_id FROM sync_outbox", 1).size)
    }

    @Test
    fun cancelledProcessCheck_rollsBackWithoutPartialState() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val lease = FakeLease()
        val ids = defaultMutations()
        var calls = 0
        val cancelling = LegacyImportStore(
            database = database,
            barrier = FakeBarrier(lease),
            processCheck = {
                calls++
                if (calls == 2) throw CancellationException("lost")
            },
            newMutationId = { ids.removeFirst() },
        )
        try {
            cancelling.capture(authority.read(), target())
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(1, lease.closes)
    }

    // ---- Realistic invalid-source safety checks (not arbitrary not-json) ----

    @Test
    fun dualPrimaryMuscles_blockWithoutNullCollapse() = kotlinx.coroutines.test.runTest {
        seedMinimalValidGraph()
        exec("INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES ('$CUSTOM_MIN', '$MUSCLE_A_ID', 'primary')")
        exec("INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES ('$CUSTOM_MIN', '$MUSCLE_B_ID', 'primary')")
        val before = graphSnapshot()
        val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
        assertEquals("invalid_source", result.evidence.errorCode)
        assertTrue(result.evidence.sourceJson.contains(CUSTOM_MIN))
        assertEquals(before, graphSnapshot())
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        // Both primaries retained: multiple primaries never collapse via singleOrNull.
        assertEquals(2, count("SELECT COUNT(*) FROM exercise_muscle_groups WHERE exercise_id = '$CUSTOM_MIN'"))
    }

    @Test
    fun unknownOwnershipFlag_blocksWithoutFiltering() = kotlinx.coroutines.test.runTest {
        seedLookupTables()
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_MIN', 'Flagged Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " NULL, 2, NULL, NULL, 1, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FINISHED', 'Finished W', 1000, 2000, 'finished', NULL, NULL, 1," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds) VALUES ('$WE_FIN', '$WORKOUT_FINISHED', '$CUSTOM_MIN', 0, '', 30)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_FIN', '$WE_FIN', 0, 8, 10.0, 1, NULL)",
        )
        val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
        assertEquals("invalid_source", result.evidence.errorCode)
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        // Unknown flag retained verbatim, never filtered away as bundled/empty.
        assertEquals(2, rows("SELECT is_user_created FROM exercises WHERE id = '$CUSTOM_MIN'", 1).single()[0]!!.toInt())
    }

    @Test
    fun nonstringInstructionEntries_blockWithoutCoercion() = kotlinx.coroutines.test.runTest {
        seedLookupTables()
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_MIN', 'Mixed Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Step one\", 7]', 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FINISHED', 'Finished W', 1000, 2000, 'finished', NULL, NULL, 1," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds) VALUES ('$WE_FIN', '$WORKOUT_FINISHED', '$CUSTOM_MIN', 0, '', 30)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_FIN', '$WE_FIN', 0, 8, 10.0, 1, NULL)",
        )
        val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
        assertEquals("invalid_source", result.evidence.errorCode)
        assertTrue(result.evidence.sourceJson.contains("Step one"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
    }

    @Test
    fun negativeGeneration_blocksWithoutAdvance() = kotlinx.coroutines.test.runTest {
        seedMinimalValidGraph()
        exec("UPDATE exercises SET local_generation = -1 WHERE id = '$CUSTOM_MIN'")
        val before = graphSnapshot()
        val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
        assertEquals("invalid_source", result.evidence.errorCode)
        assertEquals(before, graphSnapshot())
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
    }

    @Test
    fun duplicateChildPositions_blockWithoutRenumbering() = kotlinx.coroutines.test.runTest {
        seedLookupTables()
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_MIN', 'Min Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " NULL, 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FINISHED', 'Finished W', 1000, 2000, 'finished', NULL, NULL, 1," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds) VALUES ('$WE_FIN', '$WORKOUT_FINISHED', '$CUSTOM_MIN', 0, '', 30)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds) VALUES ('$WE_ACT', '$WORKOUT_FINISHED', '$CUSTOM_MIN', 0, '', 30)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_FIN', '$WE_FIN', 0, 8, 10.0, 1, NULL)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_ACT', '$WE_ACT', 0, 8, 10.0, 1, NULL)",
        )
        val before = graphSnapshot()
        val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
        assertEquals("invalid_source", result.evidence.errorCode)
        assertEquals(before, graphSnapshot())
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
    }

    @Test
    fun originEffectivePortEquality_matchesAcrossTextForms() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val retained = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        val sameOriginExplicitPort = LegacyImportTarget(ServiceOrigin.parse("$ORIGIN:443"), ACCOUNT, OPERATION)
        val recovered = store().read(sameOriginExplicitPort)
        assertNotNull(recovered)
        assertEquals(retained.evidence.sourceJson, recovered!!.sourceJson)
        val foreignOrigin = LegacyImportTarget(ServiceOrigin.parse("https://other.example.test:443"), ACCOUNT, OPERATION)
        expectLegacyFailure(LegacyImportFailure.TARGET_MISMATCH) {
            store().read(foreignOrigin)
        }
    }

    // ---- Transaction / counter-advance / high-water clarifications ----

    @Test
    fun captureInsideCallerTransaction_failsInvalidInputBeforeBarrier() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val barrier = FakeBarrier(FakeLease())
        val ids = defaultMutations()
        val storeInTx = LegacyImportStore(database, barrier, {}, { ids.removeFirst() })
        val expected = authority.read()
        database.withTransaction {
            expectLegacyFailure(LegacyImportFailure.INVALID_INPUT) {
                storeInTx.capture(expected, target())
            }
        }
        assertEquals(0, barrier.acquires)
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
    }

    @Test
    fun emptySourceAtMaxAuthorityCounters_returnsNoLocalDataWithoutAdvance() = kotlinx.coroutines.test.runTest {
        seedLookupTables()
        exec("INSERT INTO sync_device_state (id, active_account_id, cached_account_id, session_generation, cache_state) VALUES (1, NULL, NULL, 9223372036854775807, 'local_only')")
        exec("INSERT INTO sync_session_authority (id, revision, session_generation, phase, service_origin, account_id, operation_id) VALUES (1, 9223372036854775807, 9223372036854775807, 'signed_out', NULL, NULL, NULL)")
        val expected = authority.read()
        assertEquals(Long.MAX_VALUE, expected.revision)
        var calls = 0
        val ids = defaultMutations()
        val idle = LegacyImportStore(database, FakeBarrier(FakeLease()), {}, { calls++; ids.removeFirst() })
        assertTrue(idle.capture(expected, target()) is LegacyImportCaptureResult.NoLocalData)
        assertEquals(0, calls)
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        rows("SELECT revision, session_generation FROM sync_session_authority WHERE id = 1", 2).single().let {
            assertEquals(Long.MAX_VALUE.toString(), it[0])
            assertEquals(Long.MAX_VALUE.toString(), it[1])
        }
    }

    @Test
    fun outboxHighWaterOverflow_failsCounterOverflowWithEmptyOutbox() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        exec("INSERT INTO sqlite_sequence (name, seq) VALUES ('sync_outbox', 9223372036854775807)")
        val before = graphSnapshot()
        val lease = FakeLease()
        expectLegacyFailure(LegacyImportFailure.COUNTER_OVERFLOW) {
            storeWithLease(lease).capture(authority.read(), target())
        }
        assertEquals(before, graphSnapshot())
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0L, authority.read().revision)
        assertEquals(1, lease.closes)
    }

    // ---- Post-write gating: trips only after observed SQL ----

    @Test
    fun processCancellationAfterStagedWrite_rollsBackStagedWrites() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        var stagedAtTrip = 0
        var tripped = false
        val lease = FakeLease()
        val gated = storeWithLease(lease, processCheck = {
            if (!tripped) {
                val staged = outboxStagedCount()
                if (staged > 0) {
                    tripped = true
                    stagedAtTrip = staged
                    throw CancellationException("lost after write")
                }
            }
        })
        try {
            gated.capture(authority.read(), target())
            fail("post-write cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue("trip must follow an actual staged write", tripped)
        assertTrue("staged outbox rows must exist at trip", stagedAtTrip > 0)
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0L, authority.read().revision)
        assertEquals(1, lease.closes)
    }

    @Test
    fun leaseInvalidationAfterStagedWrite_rollsBackUnavailable() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val lease = FakeLease()
        lease.tripAfterOutboxWriteWith = IllegalStateException("lease lost")
        expectLegacyFailure(LegacyImportFailure.UNAVAILABLE) {
            storeWithLease(lease).capture(authority.read(), target())
        }
        assertTrue("trip must follow an actual staged write", (lease.outboxAtTrip ?: 0) > 0)
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0L, authority.read().revision)
        assertEquals(1, lease.closes)
    }

    @Test
    fun committedButCancelledCallerHeldDispatch_recoversViaDurableReadAndIdempotent() = kotlinx.coroutines.test.runTest {
        // Genuine caller-cancellation proof: capture is queued on a test-only held
        // dispatcher, the Room commit runs on an owned background transaction
        // executor, and the caller Job is cancelled after the commit runnable
        // returns but before queued result dispatch is released. No sleeps, no
        // callback counts, no close-exception shortcut; the lease never throws.
        val gateRef = AtomicReference<CompletableDeferred<Unit>?>(null)
        val txThread = Executors.newSingleThreadExecutor()
        val queryThread = Executors.newSingleThreadExecutor()
        val gated = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setTransactionExecutor { runnable ->
                // Capture the gate at admission: a runnable admitted before arming
                // (e.g. the authority read) sees null no matter when it finishes.
                val gateForThisRunnable = gateRef.get()
                txThread.execute {
                    runnable.run()
                    gateForThisRunnable?.complete(Unit)
                }
            }
            .setQueryExecutor(queryThread)
            .build()
        val heldDispatch = QueuedDispatcher()
        var callerJob: Job? = null
        try {
            database.close()
            database = gated
            authority = RoomSessionAuthority(gated)
            seedRichLocalGraph()
            val expected = authority.read()
            gateRef.set(CompletableDeferred())
            val postArmGate = gateRef.get()!!
            val quietLease = FakeLease()
            val caller = async(heldDispatch, start = CoroutineStart.UNDISPATCHED) {
                storeWithLease(quietLease).capture(expected, target())
            }
            callerJob = caller
            postArmGate.await()
            // Commit runnable returned: journal and full outbox durable...
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(7, count("SELECT COUNT(*) FROM sync_outbox"))
            // ...yet no success delivered while result dispatch is held.
            assertFalse("success must be held in queued dispatch", caller.isCompleted)
            // Observe the caller failure apart from the join, then cancel before release.
            var observed: Throwable? = null
            caller.invokeOnCompletion { observed = it }
            caller.cancel()
            heldDispatch.drain()
            try {
                caller.await()
                fail("cancelled caller must not deliver success")
            } catch (_: CancellationException) {
            }
            assertTrue("caller cancellation observed apart from await", observed is CancellationException)
            assertEquals(1, quietLease.closes)
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
            val recovered = store().read(target())
            assertNotNull(recovered)
            assertEquals(7L, recovered!!.importCount)
            val again = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(recovered.sourceJson, again.evidence.sourceJson)
        } finally {
            callerJob?.let { if (!it.isCompleted) { it.cancel(); heldDispatch.drain() } }
            txThread.shutdown()
            queryThread.shutdown()
            gated.close()
            database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
            authority = RoomSessionAuthority(database)
        }
    }

    @Test
    fun leaseCloseFailureAfterSuccess_surfacesClosedWithDurableRecovery() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val lease = FakeLease(closeFailure = IllegalStateException("close lost"))
        try {
            storeWithLease(lease).capture(authority.read(), target())
            fail("close failure must not be swallowed")
        } catch (e: LegacyImportException) {
            assertEquals(LegacyImportFailure.UNAVAILABLE, e.failure)
            assertClosedShape(e)
        }
        assertEquals(1, lease.closes)
        assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        val recovered = store().read(target())
        assertNotNull(recovered)
        assertEquals(7L, recovered!!.importCount)
    }

    @Test
    fun cancellationPrecedesLeaseCloseFailure() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val lease = FakeLease(closeFailure = IllegalStateException("close lost"))
        val ids = defaultMutations()
        var calls = 0
        val cancelling = LegacyImportStore(
            database = database,
            barrier = FakeBarrier(lease),
            processCheck = {
                calls++
                if (calls == 2) throw CancellationException("lost")
            },
            newMutationId = { ids.removeFirst() },
        )
        try {
            cancelling.capture(authority.read(), target())
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("lost", e.message)
        }
        assertEquals(1, lease.closes)
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
    }

    @Test
    fun journalInsertTriggerTamperingBusinessAndMetadata_rollsBackAllEffects() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val before = graphSnapshot()
        exec(
            "CREATE TRIGGER qa_tamper_journal AFTER INSERT ON sync_legacy_import" +
                " BEGIN UPDATE workouts SET name = 'Tampered', local_generation = local_generation + 100 WHERE id = '$WORKOUT_FINISHED';" +
                " UPDATE workout_exercises SET notes = 'tampered' WHERE id = '$WE_FIN';" +
                " UPDATE exercises SET account_id = 'tampered' WHERE id = '$CUSTOM_RICH';" +
                " END",
        )
        val lease = FakeLease()
        expectClosedLegacyException {
            storeWithLease(lease).capture(authority.read(), target())
        }
        assertEquals(before, graphSnapshot())
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_account_cache"))
        assertEquals(0, count("SELECT COUNT(*) FROM sync_canonical_roots"))
        assertEquals(0L, authority.read().revision)
        assertEquals(1, lease.closes)
    }

    @Test
    fun sourceDomainChangeAfterCapture_failsCorruptAndRestoreRecovers() = kotlinx.coroutines.test.runTest {
        seedRichLocalGraph()
        val retained = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        // Structurally valid domain change after freezing: recovery must refuse it.
        exec("UPDATE workouts SET name = 'Changed' WHERE id = '$WORKOUT_FINISHED'")
        expectLegacyFailure(LegacyImportFailure.CORRUPT_EVIDENCE) {
            store().read(target())
        }
        assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        // Restore the frozen value: identical bytes recover unchanged.
        exec("UPDATE workouts SET name = 'Finished W' WHERE id = '$WORKOUT_FINISHED'")
        val recovered = store().read(target())
        assertNotNull(recovered)
        assertEquals(retained.evidence.sourceJson, recovered!!.sourceJson)
        assertEquals(retained.evidence.importCount, recovered.importCount)
    }

    @Test
    fun sharedUuidAcrossWorkoutRoutineFamilies_capturesWithPerFamilyGeneration() = kotlinx.coroutines.test.runTest {
        seedLookupTables()
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_MIN', 'Min Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " NULL, 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$SHARED_ROOT', 'Shared Routine', 0, NULL, NULL, 2, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$SHARED_ROOT', 'Shared Workout', 1000, 2000, 'finished', NULL, NULL, 5," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds) VALUES ('$WE_FIN', '$SHARED_ROOT', '$CUSTOM_MIN', 0, '', 30)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_FIN', '$WE_FIN', 0, 8, 10.0, 1, NULL)",
        )
        val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
        assertEquals(LegacyImportPhase.CAPTURED, result.evidence.phase)
        assertEquals(3L, result.evidence.importCount)
        // Root identity is type+UUID: per-family exact g+1 preserved.
        assertEquals("2", rows("SELECT local_generation FROM exercises WHERE id = '$CUSTOM_MIN'", 1).single()[0])
        assertEquals("3", rows("SELECT local_generation FROM routines WHERE id = '$SHARED_ROOT'", 1).single()[0])
        assertEquals("6", rows("SELECT local_generation FROM workouts WHERE id = '$SHARED_ROOT'", 1).single()[0])
        assertEquals(
            listOf(
                listOf("customExercise", CUSTOM_MIN),
                listOf("routine", SHARED_ROOT),
                listOf("workout", SHARED_ROOT),
            ),
            rows("SELECT aggregate_type, aggregate_id FROM sync_outbox ORDER BY sequence", 2),
        )
        val watermarks = rows(
            "SELECT aggregate_type, aggregate_id, local_generation_watermark, canonical_state" +
                " FROM sync_canonical_roots ORDER BY aggregate_type, aggregate_id",
            4,
        ).associate { "${it[0]}:${it[1]}" to (it[2] to it[3]) }
        assertEquals("2" to "unobserved", watermarks["customExercise:$CUSTOM_MIN"])
        assertEquals("3" to "unobserved", watermarks["routine:$SHARED_ROOT"])
        assertEquals("6" to "unobserved", watermarks["workout:$SHARED_ROOT"])
    }

    @Test
    fun callerCancelledDuringLeaseClose_dominatesCloseOutcome() = kotlinx.coroutines.test.runTest {
        // Cancellation precedence at the close boundary: a caller Job cancelled
        // from inside lease.close must surface CancellationException, dominating
        // both a normal close return and a closed close-failure. The cancel
        // targets ONLY the child caller Job; the parent runTest scope stays active.
        // The actual in-caller throwable is recorded via CompletableDeferred,
        // apart from the join state.
        for (throwAfterCancel in listOf(false, true)) {
            database.close()
            database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
            authority = RoomSessionAuthority(database)
            seedRichLocalGraph()
            val expected = authority.read()
            val callerRef = AtomicReference<Job?>(null)
            val observed = CompletableDeferred<Throwable>()
            val lease = FakeLease()
            if (throwAfterCancel) lease.closeFailure = IllegalStateException("close lost")
            lease.onClose = { callerRef.get()?.cancel(CancellationException("cancelled in close")) }
            val ids = defaultMutations()
            val closing = LegacyImportStore(database, FakeBarrier(lease), {}, { ids.removeFirst() })
            val caller = async {
                try {
                    closing.capture(expected, target())
                    observed.complete(AssertionError("cancelled close must not report success"))
                } catch (e: Throwable) {
                    observed.complete(e)
                    throw e
                }
            }
            callerRef.set(caller)
            try {
                caller.await()
            } catch (_: CancellationException) {
            }
            val thrown = observed.await()
            assertTrue(
                "caller cancel during close must dominate (throwAfterCancel=$throwAfterCancel), got $thrown",
                thrown is CancellationException,
            )
            assertEquals("cancelled in close", (thrown as CancellationException).message)
            assertEquals(1, lease.closes)
            // Committed yet hidden: authority advanced, evidence recoverable.
            val post = authority.read()
            assertEquals(1L, post.revision)
            assertEquals(RecoveryRoomPhase.PREPARING, post.phase)
            val recovered = store().read(target())
            assertNotNull(recovered)
            assertEquals(7L, recovered!!.importCount)
        }
    }
}
