package com.dragosfotache.gymtracka.data.sync.legacy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
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
 * Real Room checks for private import delivery settlement, exact original requests, canonical
 * acceptance receipts, retained suffix work and consent-bound progress.
 */
@RunWith(AndroidJUnit4::class)
class LegacyImportDeliveryTest {
    private lateinit var context: Context
    private lateinit var database: GymDatabase
    private lateinit var authority: RoomSessionAuthority

    private companion object {
        const val ORIGIN = "https://sync-import.example.test"
        const val ACCOUNT = "c13b1001-1111-4111-8111-111111111111"
        const val OPERATION = "d13b1001-1111-4111-8111-111111111111"

        const val CUSTOM_RICH = "11111111-b13b-4b13-8b13-b13bb13bb13b"
        const val CUSTOM_MIN = "22222222-b13b-4b13-8b13-b13bb13bb13b"
        const val BUNDLED_REF = "33333333-b13b-4b13-8b13-b13bb13bb13b"
        const val ROUTINE_SAVED_BLANK = "44444444-b13b-4b13-8b13-b13bb13bb001"
        const val ROUTINE_DRAFT_EMPTY = "44444444-b13b-4b13-8b13-b13bb13bb002"
        const val ROUTINE_POPULATED = "44444444-b13b-4b13-8b13-b13bb13bb003"
        const val WORKOUT_FINISHED = "55555555-b13b-4b13-8b13-b13bb13bb001"
        const val WORKOUT_ACTIVE = "55555555-b13b-4b13-8b13-b13bb13bb002"

        const val MUSCLE_A_ID = "a1b13b00-a000-4a00-8a00-a000a000a000"
        const val EQUIP_A_ID = "c1b13b00-c000-4c00-8c00-c000c000c000"
        const val WE_FIN = "d1b13b00-d100-4d10-8d10-d100d100d100"
        const val WS_FIN = "e1b13b00-e100-4e10-8e10-e100e100e100"
        const val WE_ACT = "d2b13b00-d200-4d20-8d20-d200d200d200"
        const val WS_ACT = "e2b13b00-e200-4e20-8e20-e200e200e200"
        const val RE_POP = "f1b13b00-f100-4f10-8f10-f100f100f100"
        const val RS_POP = "f2b13b00-f200-4f20-8f20-f200f200f200"

        const val MUTATION_1 = "70000001-b13b-4b13-8b13-b13bb13bb13b"
        const val MUTATION_2 = "70000002-b13b-4b13-8b13-b13bb13bb13b"
        const val MUTATION_3 = "70000003-b13b-4b13-8b13-b13bb13bb13b"
        const val MUTATION_4 = "70000004-b13b-4b13-8b13-b13bb13bb13b"
        const val MUTATION_5 = "70000005-b13b-4b13-8b13-b13bb13bb13b"
        const val MUTATION_6 = "70000006-b13b-4b13-8b13-b13bb13bb13b"
        const val MUTATION_7 = "70000007-b13b-4b13-8b13-b13bb13bb13b"
    }

    private fun defaultMutations(): MutableList<String> =
        mutableListOf(
            MUTATION_1, MUTATION_2, MUTATION_3, MUTATION_4,
            MUTATION_5, MUTATION_6, MUTATION_7,
        )

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

    private inner class FakeLease : ProjectionPublicationLease {
        var closes = 0
        override fun checkValid() = Unit
        override fun close() {
            closes++
        }
    }

    private inner class FakeBarrier(val lease: FakeLease) : LegacyImportCaptureBarrier {
        override suspend fun acquire(
            expected: com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot,
        ): ProjectionPublicationLease = lease
    }

    private fun target(): LegacyImportTarget =
        LegacyImportTarget(
            origin = ServiceOrigin.parse(ORIGIN),
            accountId = ACCOUNT,
            operationId = OPERATION,
        )

    private var mutationCalls = 0

    private fun store(mutations: MutableList<String> = defaultMutations()): LegacyImportStore =
        LegacyImportStore(
            database = database,
            barrier = FakeBarrier(FakeLease()),
            processCheck = {},
            newMutationId = {
                mutationCalls++
                mutations.removeFirst()
            },
        )

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
        exec("INSERT INTO equipment (id, name, type, usage_type) VALUES ('$EQUIP_A_ID', 'Dumbbell', 'weight', NULL)")
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
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_RICH', 'Rich Custom', 'reps', 'beginner', 'push', 'compound'," +
                " 'strength', '[\"Step one\",\"Step two\"]', 1, NULL, NULL, 3, 'local_only', NULL)",
        )
        exec("INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES ('$CUSTOM_RICH', '$MUSCLE_A_ID', 'primary')")
        exec("INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items) VALUES ('$CUSTOM_RICH', '$EQUIP_A_ID', 2)")
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
                " VALUES ('$ROUTINE_SAVED_BLANK', '', 0, NULL, NULL, 2, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$ROUTINE_DRAFT_EMPTY', '', 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$ROUTINE_POPULATED', 'Populated Routine', 0, NULL, NULL, 6, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO routine_exercises (id, routine_id, exercise_id, position, rest_duration_seconds)" +
                " VALUES ('$RE_POP', '$ROUTINE_POPULATED', '$CUSTOM_RICH', 0, 45)",
        )
        exec(
            "INSERT INTO routine_sets (id, routine_exercise_id, position, repetitions, weight_kg)" +
                " VALUES ('$RS_POP', '$RE_POP', 0, 10, 40.0)",
        )
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FINISHED', 'Finished W', 1000, 2000, 'finished', NULL, NULL, 4," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                " VALUES ('$WE_FIN', '$WORKOUT_FINISHED', '$CUSTOM_RICH', 0, 'Keep', 75)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_FIN', '$WE_FIN', 0, 8, 70.0, 1, 60)",
        )
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_ACTIVE', 'Active W', 5000, NULL, 'active', NULL, NULL, 5," +
                " 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                " VALUES ('$WE_ACT', '$WORKOUT_ACTIVE', '$CUSTOM_MIN', 0, '', 30)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_ACT', '$WE_ACT', 0, 5, NULL, 0, NULL)",
        )
    }

    /** Exact expected import order: customs, routines, workouts with g+1 generations. */
    private fun expectedOrder(): List<Triple<String, String, Long>> =
        listOf(
            Triple(MUTATION_1, "customExercise:$CUSTOM_RICH", 4L),
            Triple(MUTATION_2, "customExercise:$CUSTOM_MIN", 2L),
            Triple(MUTATION_3, "routine:$ROUTINE_SAVED_BLANK", 3L),
            Triple(MUTATION_4, "routine:$ROUTINE_DRAFT_EMPTY", 2L),
            Triple(MUTATION_5, "routine:$ROUTINE_POPULATED", 7L),
            Triple(MUTATION_6, "workout:$WORKOUT_FINISHED", 5L),
            Triple(MUTATION_7, "workout:$WORKOUT_ACTIVE", 6L),
        )

    private suspend fun captureRich(): LegacyImportEvidence {
        val result = store().capture(authority.read(), target())
        assertTrue(result is LegacyImportCaptureResult.Retained)
        val evidence = (result as LegacyImportCaptureResult.Retained).evidence
        assertEquals(LegacyImportPhase.CAPTURED, evidence.phase)
        assertEquals(7L, evidence.importCount)
        return evidence
    }

    @Test
    fun populatedCapture_persistsExactOrderBytesAndProgressAnchor() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            mutationCalls = 0
            val result = store().capture(authority.read(), target())
            assertTrue(result is LegacyImportCaptureResult.Retained)
            val evidence = (result as LegacyImportCaptureResult.Retained).evidence
            assertEquals(LegacyImportPhase.CAPTURED, evidence.phase)
            assertEquals(0L, evidence.acceptedCount)
            assertEquals(7L, evidence.importCount)
            assertEquals(7, mutationCalls)

            // Entries: consecutive ordinals, unique mutations, exact frozen bytes.
            val entries = rows(
                "SELECT ordinal, aggregate_type, aggregate_id, mutation_id, sequence," +
                    " local_generation, payload_version, payload_json" +
                    " FROM sync_legacy_import_entries ORDER BY ordinal",
                8,
            )
            assertEquals(7, entries.size)
            entries.forEachIndexed { index, row ->
                assertEquals(index.toString(), row[0])
            }
            assertEquals(7, entries.map { it[3] }.toSet().size)
            // Outbox mirrors entries byte-for-byte per mutation with import type only.
            val outbox = rows(
                "SELECT mutation_id, mutation_type, local_generation, payload_version, payload_json" +
                    " FROM sync_outbox ORDER BY sequence",
                5,
            )
            assertEquals(7, outbox.size)
            val outboxByMutation = outbox.associate { it[0]!! to it }
            for (row in entries) {
                val frozen = checkNotNull(outboxByMutation[row[3]])
                assertEquals("aggregate.import", frozen[1])
                assertEquals(row[5], frozen[2])
                assertEquals(row[6], frozen[3])
                assertEquals(frozen[4], row[7])
            }
            // Exact envelope parse per contract shape: order, identity, generation,
            // single affected root and record-id equality — never substrings.
            val parsed = mutableListOf<Triple<String, String, Long>>()
            for (row in rows("SELECT mutation_id, payload_json FROM sync_outbox ORDER BY sequence", 2)) {
                val wrapper = JSONObject(row[1]!!)
                assertEquals(1, wrapper.getInt("payloadVersion"))
                val request = JSONObject(wrapper.getString("requestJson"))
                assertEquals(row[0], request.getString("mutationId"))
                assertEquals("aggregate.import", request.getString("type"))
                val aggregateType = request.getString("aggregateType")
                val aggregateId = request.getString("aggregateId")
                val generation = request.getLong("localGeneration")
                val roots = wrapper.getJSONArray("affectedRoots")
                assertEquals(1, roots.length())
                roots.getJSONObject(0).let {
                    assertEquals(aggregateType, it.getString("aggregateType"))
                    assertEquals(aggregateId, it.getString("aggregateId"))
                    assertEquals(generation, it.getLong("localGeneration"))
                }
                assertEquals(
                    aggregateId,
                    request.getJSONObject("payload").getJSONObject("record").getString("id"),
                )
                parsed += Triple(request.getString("mutationId"), "$aggregateType:$aggregateId", generation)
            }
            assertEquals(expectedOrder(), parsed)

            // Frozen source retained; children/custom references survive; blanks not normalized.
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import WHERE source_json IS NOT NULL"))
            assertEquals(1, count("SELECT COUNT(*) FROM exercises WHERE id = '$CUSTOM_RICH'"))
            assertEquals(1, count("SELECT COUNT(*) FROM routine_sets WHERE id = '$RS_POP'"))
            assertEquals(1, count("SELECT COUNT(*) FROM workout_sets WHERE id = '$WS_FIN'"))
            assertEquals(
                "",
                rows("SELECT name FROM routines WHERE id = '$ROUTINE_SAVED_BLANK'", 1).single()[0],
            )
            // Capture initializes the current-lineage progress anchor atomically.
            val progress = rows(
                "SELECT room_revision, room_session_generation, room_phase, operation_id," +
                    " active_consent_mutation_id FROM sync_legacy_import_progress WHERE id = 1",
                5,
            )
            assertEquals(1, progress.size)
            assertEquals("preparing", progress.single()[2])
            assertEquals(OPERATION, progress.single()[3])
            assertNull(progress.single()[4])
            // No acceptance receipts fabricated at capture.
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_acceptances"))

            // Durable reread recovers the same original migration without new mutation IDs.
            val reread = store().read(target())
            assertNotNull(reread)
            assertEquals(evidence.sourceJson, reread!!.sourceJson)
            assertEquals(evidence.importCount, reread.importCount)
            assertEquals(0L, reread.acceptedCount)
            val again = store().capture(authority.read(), target())
            assertTrue(again is LegacyImportCaptureResult.Retained)
            assertEquals(7, mutationCalls)
        }

    @Test
    fun emptyInstall_createsNoJournalProgressOrReceipt() =
        kotlinx.coroutines.test.runTest {
            seedLookupTables()
            val result = store().capture(authority.read(), target())
            assertTrue(result is LegacyImportCaptureResult.NoLocalData)
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_progress"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_acceptances"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
        }

    @Test
    fun changedBusinessName_failsClosedRetainingJournal() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            captureRich()
            // Valid business edit after capture breaks frozen-source correspondence.
            exec("UPDATE workouts SET name = 'Tampered' WHERE id = '$WORKOUT_FINISHED'")
            try {
                store().read(target())
                fail("changed business must fail closed")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
                assertNull(e.cause)
            }
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        }

    @Test
    fun removedChildSet_failsClosedRetainingJournal() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            captureRich()
            exec("DELETE FROM workout_sets WHERE id = '$WS_FIN'")
            try {
                store().read(target())
                fail("removed source child must fail closed")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
                assertNull(e.cause)
            }
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        }

    @Test
    fun validDifferentEntryPayload_failsClosedRetainingJournal() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            captureRich()
            // Well-formed envelope with the same mutation identity but altered
            // business record: still valid JSON, still the wrong source.
            val original = rows(
                "SELECT payload_json FROM sync_legacy_import_entries WHERE ordinal = 0",
                1,
            ).single()[0]!!
            val wrapper = JSONObject(original)
            val request = JSONObject(wrapper.getString("requestJson"))
            request.getJSONObject("payload").getJSONObject("record").put("name", "Tampered Record")
            wrapper.put("requestJson", request.toString())
            exec(
                "UPDATE sync_legacy_import_entries SET payload_json = '" +
                    wrapper.toString().replace("'", "''") + "' WHERE ordinal = 0",
            )
            try {
                store().read(target())
                fail("valid-but-wrong entry business must fail closed")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
                assertNull(e.cause)
            }
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        }

    @Test
    fun tamperedEntryPayload_failsClosedRetainingJournal() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            captureRich()
            exec("UPDATE sync_legacy_import_entries SET payload_json = '{\"tampered\":true}' WHERE ordinal = 0")
            try {
                store().read(target())
                fail("tampered entry must fail closed")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
                assertNull(e.cause)
            }
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        }

    @Test
    fun duplicateSingletonAndOrphanEntries_failClosed() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            captureRich()
            exec(
                "INSERT INTO sync_legacy_import (id, evidence_version, service_origin, account_id, operation_id," +
                    " source_revision, source_session_generation, room_revision, room_session_generation," +
                    " phase, source_json, import_count, error_code)" +
                    " VALUES (2, 1, '$ORIGIN:443', '$ACCOUNT', '$OPERATION', 0, 0, 1, 1, 'captured', '{}', 0, NULL)",
            )
            try {
                store().read(target())
                fail("duplicate singleton must fail closed")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
            }
            exec("DELETE FROM sync_legacy_import WHERE id = 2")
            exec("DELETE FROM sync_legacy_import")
            assertTrue(count("SELECT COUNT(*) FROM sync_legacy_import_entries") > 0)
            try {
                store().read(target())
                fail("orphan entries must fail closed")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
            }
        }

    @Test
    fun malformedAcceptanceReceipt_failsClosedBeforeActivation() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            captureRich()
            // A receipt the strict batch codec cannot validate must fail closed;
            // journal, entries and progress stay retained and unmodified.
            exec(
                "INSERT INTO sync_legacy_import_acceptances (ordinal, mutation_id, batch_json)" +
                    " VALUES (0, '$MUTATION_1', 'not-json')",
            )
            try {
                store().read(target())
                fail("malformed receipt must fail closed")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
                assertNull(e.cause)
            }
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
            assertEquals(7, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import_progress"))
        }

    @Test
    fun orphanAcceptanceReceipt_failsClosed() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            captureRich()
            exec(
                "INSERT INTO sync_legacy_import_acceptances (ordinal, mutation_id, batch_json)" +
                    " VALUES (0, '00000000-ffff-4fff-8fff-ffffffffffff', '{\"orphan\":true}')",
            )
            try {
                store().read(target())
                fail("orphan receipt must fail closed")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
            }
            assertEquals(1, count("SELECT COUNT(*) FROM sync_legacy_import"))
        }

    @Test
    fun evidenceDisplay_isFixedAndRedactedWithDefaultAcceptedCount() =
        kotlinx.coroutines.test.runTest {
            seedRichLocalGraph()
            val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(0L, result.evidence.acceptedCount)
            val rendered = result.evidence.toString()
            assertTrue(rendered.isNotBlank())
            assertFalse(rendered.contains(ACCOUNT))
            assertFalse(rendered.contains(OPERATION))
        }

    @Test
    fun blockedInvalidSource_retainsBlockedEvidenceWithoutProgressOrRequests() =
        kotlinx.coroutines.test.runTest {
            exec("INSERT INTO muscle_groups (id, slug, name) VALUES ('muscle-a', 'muscle-a', 'Muscle A')")
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
                    " rest_duration_seconds) VALUES ('$WE_FIN', '$WORKOUT_FINISHED', '$CUSTOM_MIN', 0, '', 30)",
            )
            val result = store().capture(authority.read(), target()) as LegacyImportCaptureResult.Retained
            assertEquals(LegacyImportPhase.BLOCKED, result.evidence.phase)
            assertEquals("invalid_source", result.evidence.errorCode)
            assertEquals(0, count("SELECT COUNT(*) FROM sync_outbox"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_progress"))
            assertEquals(0, count("SELECT COUNT(*) FROM sync_legacy_import_acceptances"))
        }
}
