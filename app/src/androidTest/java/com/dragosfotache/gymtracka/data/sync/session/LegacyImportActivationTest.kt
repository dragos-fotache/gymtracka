package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportCaptureBarrier
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportCaptureResult
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportException
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPolicy
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgressPhase
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementConsent
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementRequest
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportStore
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportTarget
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
 * Real Room checks for private import activation and observation, requiring coherent
 * receipt/source/authority evidence before account admission.
 */
@RunWith(AndroidJUnit4::class)
class LegacyImportActivationTest {
    private lateinit var context: Context
    private lateinit var database: GymDatabase
    private val usedNamespaces = mutableListOf<String>()

    private companion object {
        const val ORIGIN = "https://sync-import.example.test"
        const val OTHER_ORIGIN = "https://other-import.example.test"
        const val ACCOUNT = "c13b2001-1111-4111-8111-111111111111"
        const val OPERATION = "d13b2001-1111-4111-8111-111111111111"
        const val NEW_OPERATION = "d13b2002-2222-4222-8222-222222222222"
        const val STAGE = "d13b2003-3333-4333-8333-333333333333"
        const val CUSTOM = "11111111-c13b-4c13-8c13-c13bc13bc13b"
        const val WORKOUT = "55555555-c13b-4c13-8c13-c13bc13bc13b"
        const val WE = "d1c13b00-d100-4d10-8d10-d100d100d100"
        const val WS = "e1c13b00-e100-4e10-8e10-e100e100e100"
        const val GRANT_EXPIRY = 1_700_000_000_000L
        const val MUTATION_A = "70000001-c13b-4c13-8c13-c13bc13bc13b"
        const val MUTATION_B = "70000002-c13b-4c13-8c13-c13bc13bc13b"
        const val MUTATION_C = "70000003-c13b-4c13-8c13-c13bc13bc13b"
        const val SHARED = "99999999-c13b-4c13-8c13-c13bc13bc13b"
        const val RE_SHARED = "f1c13b00-f100-4f10-8f10-f100f100f100"
        const val RS_SHARED = "f2c13b00-f200-4f20-8f20-f200f200f200"
        const val WE_SHARED = "d3c13b00-d300-4d30-8d30-d300d300d300"
        const val WS_SHARED = "e3c13b00-e300-4e30-8e30-e300e300e300"
        const val BATCH_A = "b0000001-c13b-4c13-8c13-c13bc13bc13b"
        const val BATCH_B = "b0000002-c13b-4c13-8c13-c13bc13bc13b"
        const val BATCH_C = "b0000003-c13b-4c13-8c13-c13bc13bc13b"
    }

    private inner class FakeLease : ProjectionPublicationLease {
        override fun checkValid() = Unit
        override fun close() = Unit
    }

    private inner class FakeCaptureBarrier : LegacyImportCaptureBarrier {
        override suspend fun acquire(expected: RecoveryRoomSnapshot): ProjectionPublicationLease =
            FakeLease()
    }

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
    }

    private fun testAlias(namespace: String): String =
        "com.dragosfotache.gymtracka.session.$namespace"

    /** Strict test-owned cleanup: failures propagate, never swallowed. */
    private fun deleteNamespace(namespace: String) {
        val dir = File(context.noBackupFilesDir, namespace)
        dir.listFiles()?.forEach { check(it.delete()) { "cannot delete $it" } }
        if (dir.exists()) check(dir.delete()) { "cannot delete $dir" }
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            .deleteEntry(testAlias(namespace))
    }

    @After
    fun cleanUp() {
        runCatching { database.close() }
        for (namespace in usedNamespaces) deleteNamespace(namespace)
        usedNamespaces.clear()
    }

    private fun coordinator(
        origin: ServiceOrigin?,
        policy: LegacyImportPolicy?,
        auth: AppSessionAuthApi = AppSessionAuthApi(),
        transport: AppSessionTransport = AppSessionTransport(),
        clock: CountingClock = CountingClock(),
        uuids: CountingUuids = CountingUuids(),
    ): AppSessionCoordinator {
        val namespace = "gymtracka-test-import-" + UUID.randomUUID()
        usedNamespaces += namespace
        return AppSessionCoordinator(
            database = database,
            origin = origin,
            vault = AndroidSessionVault.createForTesting(context, namespace),
            authApi = auth,
            transport = transport,
            runOwner = PushRunOwner(),
            transitionBarrier = ScriptedTransitionBarrier(),
            publicationBarrier = ScriptedPublicationBarrier(),
            clockMillis = { clock.read() },
            uuidProvider = { uuids.next() },
            legacyImportPolicy = policy,
        )
    }

    private fun optInPolicy(
        consented: MutableList<LegacyImportReplacementRequest>,
        answer: Boolean = true,
    ): LegacyImportPolicy =
        LegacyImportPolicy(
            replacementConsent = LegacyImportReplacementConsent { request ->
                consented += request
                answer
            },
        )

    private fun grant(accountId: String = ACCOUNT): ServiceSession =
        ServiceSession(
            ServiceAccountRef(accountId, null),
            OpaqueToken("import-access-001"),
            GRANT_EXPIRY,
            OpaqueToken("import-refresh-001"),
            GRANT_EXPIRY + 30L * 24 * 60 * 60 * 1000,
        )

    private fun exec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun seedMinimalGraph() {
        exec(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM', 'Owned Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Step\"]', 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT', 'Local W', 4000, NULL, 'active', NULL, NULL, 2, 'local_only', NULL)",
        )
        exec(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                " VALUES ('$WE', '$WORKOUT', '$CUSTOM', 0, 'Keep', 75)",
        )
        exec(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS', '$WE', 0, 8, 70.0, 1, 60)",
        )
    }

    private suspend fun captureMinimal(): LegacyImportTarget {
        seedMinimalGraph()
        val target = LegacyImportTarget(
            origin = ServiceOrigin.parse(ORIGIN),
            accountId = ACCOUNT,
            operationId = OPERATION,
        )
        val store = LegacyImportStore(
            database = database,
            barrier = FakeCaptureBarrier(),
            processCheck = {},
            newMutationId = { if (mutationCalls++ == 0) MUTATION_A else MUTATION_B },
        )
        val result = store.capture(RoomSessionAuthority(database).read(), target)
        assertTrue(result is LegacyImportCaptureResult.Retained)
        val evidence = (result as LegacyImportCaptureResult.Retained).evidence
        assertEquals(2L, evidence.importCount)
        assertEquals(2, mutationCalls)
        return target
    }

    private var mutationCalls = 0

    /** Hand-written canonical custom record for a seeded custom root. */
    private fun customRecord(id: String, name: String): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", "reps")
        .put("category", "strength")
        .put("difficultyLevel", JSONObject.NULL)
        .put("forceType", JSONObject.NULL)
        .put("mechanics", JSONObject.NULL)
        .put("instructions", org.json.JSONArray().put("Step"))
        .put("isUserCreated", true)
        .put("revision", 1)
        .put("muscleGroups", org.json.JSONArray())
        .put("equipment", org.json.JSONArray())
        .put("primaryMuscleGroupId", JSONObject.NULL)
        .put("secondaryMuscleGroupIds", org.json.JSONArray())
        .put("equipmentId", JSONObject.NULL)
        .put("equipmentNumItems", JSONObject.NULL)

    /** Hand-written canonical active-workout record for a seeded workout root. */
    private fun workoutRecord(
        id: String,
        name: String,
        entryId: String,
        exerciseId: String,
        exerciseName: String,
        setId: String,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("status", "active")
        .put("startedAt", "1970-01-01T00:00:04Z")
        .put("revision", 1)
        .put(
            "exercises",
            org.json.JSONArray().put(
                JSONObject()
                    .put("id", entryId)
                    .put("exerciseId", exerciseId)
                    .put("exerciseName", exerciseName)
                    .put("position", 0)
                    .put("notes", "Keep")
                    .put("restDurationSeconds", 75)
                    .put(
                        "sets",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("id", setId)
                                .put("position", 0)
                                .put("repetitions", 8)
                                .put("weightKg", 70.0)
                                .put("isCompleted", true)
                                .put("restSeconds", 60),
                        ),
                    ),
            ),
        )

    /** Hand-written canonical routine record for a seeded routine root. */
    private fun routineRecord(
        id: String,
        name: String,
        entryId: String,
        exerciseId: String,
        setId: String,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("isDraft", false)
        .put("revision", 1)
        .put(
            "exercises",
            org.json.JSONArray().put(
                JSONObject()
                    .put("id", entryId)
                    .put("exerciseId", exerciseId)
                    .put("exerciseName", "Shared Custom")
                    .put("position", 0)
                    .put("restDurationSeconds", 45)
                    .put(
                        "sets",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("id", setId)
                                .put("position", 0)
                                .put("repetitions", 10)
                                .put("weightKg", 40.0),
                        ),
                    ),
            ),
        )

    private fun recordFor(aggregateType: String, aggregateId: String): JSONObject =
        when {
            aggregateType == "customExercise" && aggregateId == CUSTOM ->
                customRecord(CUSTOM, "Owned Custom")
            aggregateType == "workout" && aggregateId == WORKOUT ->
                workoutRecord(WORKOUT, "Local W", WE, CUSTOM, "Owned Custom", WS)
            aggregateType == "customExercise" && aggregateId == SHARED ->
                customRecord(SHARED, "Shared Custom")
            aggregateType == "routine" && aggregateId == SHARED ->
                routineRecord(SHARED, "Shared Routine", RE_SHARED, SHARED, RS_SHARED)
            aggregateType == "workout" && aggregateId == SHARED ->
                workoutRecord(SHARED, "Shared W", WE_SHARED, SHARED, "Shared Custom", WS_SHARED)
            else -> throw AssertionError("unexpected import root $aggregateType:$aggregateId")
        }

    private fun batchFor(mutationId: String): String = when (mutationId) {
        MUTATION_A -> BATCH_A
        MUTATION_B -> BATCH_B
        MUTATION_C -> BATCH_C
        else -> throw AssertionError("no batch for $mutationId")
    }

    private fun acceptedOutcome(mutationId: String, aggregateType: String, aggregateId: String): JSONObject {
        val record = recordFor(aggregateType, aggregateId)
        val batch = JSONObject()
            .put("batchId", batchFor(mutationId))
            .put("cursor", "import-cursor")
            .put(
                "changes",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("aggregateType", aggregateType)
                        .put("aggregateId", aggregateId)
                        .put("operation", "upsert")
                        .put("revision", 1)
                        .put("record", record),
                ),
            )
        return JSONObject()
            .put("mutationId", mutationId)
            .put("status", "accepted")
            .put("errorCode", JSONObject.NULL)
            .put("batch", batch)
    }

    private fun snapshotChange(
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        record: JSONObject,
    ): JSONObject = JSONObject()
        .put("aggregateType", aggregateType)
        .put("aggregateId", aggregateId)
        .put("operation", "upsert")
        .put("revision", revision)
        .put("record", record)

    private fun snapshotBody(changes: List<JSONObject>): String = JSONObject()
        .put("records", org.json.JSONArray(changes))
        .put("baselineCursor", "import-baseline")
        .put("nextPageToken", JSONObject.NULL)
        .put("hasMore", false)
        .toString()

    private fun minimalSnapshot(): String = snapshotBody(
        listOf(
            snapshotChange("customExercise", CUSTOM, 1, customRecord(CUSTOM, "Owned Custom")),
            snapshotChange(
                "workout", WORKOUT, 1,
                workoutRecord(WORKOUT, "Local W", WE, CUSTOM, "Owned Custom", WS),
            ),
        ),
    )

    private fun sharedSnapshot(): String = snapshotBody(
        listOf(
            snapshotChange("customExercise", SHARED, 1, customRecord(SHARED, "Shared Custom")),
            snapshotChange(
                "routine", SHARED, 1,
                routineRecord(SHARED, "Shared Routine", RE_SHARED, SHARED, RS_SHARED),
            ),
            snapshotChange(
                "workout", SHARED, 1,
                workoutRecord(SHARED, "Shared W", WE_SHARED, SHARED, "Shared Custom", WS_SHARED),
            ),
        ),
    )

    /** Empty terminal tail on test baseline checkpoint. */
    private fun emptyTail(): String = JSONObject()
        .put("batches", org.json.JSONArray())
        .put("nextCursor", "import-baseline")
        .put("hasMore", false)
        .toString()

    /**
     * Serves genuine accepted batches per pushed mutation (whatever batching
     * the engine uses), then the matching complete snapshot plus the pull
     * tail. Push request bodies carry raw envelopes directly; push ALWAYS
     * settles before snapshot.
     */
    private fun serveSettlement(transport: AppSessionTransport, snapshot: String) {
        transport.serve = { _, request ->
            when {
                request.path.contains("push") -> {
                    val mutations = JSONObject(checkNotNull(request.body) { "push requires a body" }).getJSONArray("mutations")
                    val outcomes = (0 until mutations.length()).map { index ->
                        val envelope = mutations.getJSONObject(index)
                        acceptedOutcome(
                            envelope.getString("mutationId"),
                            envelope.getString("aggregateType"),
                            envelope.getString("aggregateId"),
                        )
                    }
                    AppSessionTransport.Frame(
                        200,
                        JSONObject().put("results", org.json.JSONArray(outcomes)).toString(),
                    )
                }
                request.path.contains("snapshot") ->
                    AppSessionTransport.Frame(200, snapshot)
                else -> AppSessionTransport.Frame(200, emptyTail())
            }
        }
    }

    /** Numeric kilogram oracle: exact getDouble business value, never decimal spelling. */
    private fun workoutSetNumerics(workoutId: String): List<Triple<Int, Double, Int>> =
        database.openHelper.readableDatabase.query(
            "SELECT s.repetitions, s.weight_kg, s.is_completed FROM workout_sets s" +
                " JOIN workout_exercises e ON e.id = s.workout_exercise_id" +
                " WHERE e.workout_id = ?",
            arrayOf(workoutId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    assertFalse(cursor.isNull(0))
                    assertFalse(cursor.isNull(1))
                    assertFalse(cursor.isNull(2))
                    add(Triple(cursor.getInt(0), cursor.getDouble(1), cursor.getInt(2)))
                }
            }
        }

    private fun routineSetNumerics(routineId: String): List<Pair<Int, Double>> =
        database.openHelper.readableDatabase.query(
            "SELECT s.repetitions, s.weight_kg FROM routine_sets s" +
                " JOIN routine_exercises e ON e.id = s.routine_exercise_id" +
                " WHERE e.routine_id = ?",
            arrayOf(routineId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    assertFalse(cursor.isNull(0))
                    assertFalse(cursor.isNull(1))
                    add(cursor.getInt(0) to cursor.getDouble(1))
                }
            }
        }

    private fun assertMinimalMaterialized() {
        assertEquals(
            "Owned Custom",
            rows("SELECT name FROM exercises WHERE id = '$CUSTOM'", 1).single()[0],
        )
        assertEquals(
            "Local W",
            rows("SELECT name FROM workouts WHERE id = '$WORKOUT'", 1).single()[0],
        )
        assertEquals(
            listOf(Triple(8, 70.0, 1)),
            workoutSetNumerics(WORKOUT),
        )
    }

    private fun assertSharedMaterialized() {
        assertEquals(
            "Shared Custom",
            rows("SELECT name FROM exercises WHERE id = '$SHARED'", 1).single()[0],
        )
        assertEquals(
            "Shared Routine",
            rows("SELECT name FROM routines WHERE id = '$SHARED'", 1).single()[0],
        )
        assertEquals(
            "Shared W",
            rows("SELECT name FROM workouts WHERE id = '$SHARED'", 1).single()[0],
        )
        assertEquals(
            listOf(10 to 40.0),
            routineSetNumerics(SHARED),
        )
        assertEquals(
            listOf(Triple(8, 70.0, 1)),
            workoutSetNumerics(SHARED),
        )
    }

    /** Safe failure context only: routes, counts, codes, phases. Never tokens, bodies, cursors or source JSON. */
    private fun activationDiagnostics(transport: AppSessionTransport): String = buildString {
        append("routes=")
        append(transport.capturedRequests.map { it.path })
        append("; receipts=")
        append(count("SELECT COUNT(*) FROM sync_legacy_import_acceptances"))
        append("; outbox=")
        append(
            rows("SELECT mutation_id, state, error_code FROM sync_outbox ORDER BY sequence", 3),
        )
        append("; stages=")
        append(count("SELECT COUNT(*) FROM sync_snapshot_stages"))
        append("; authority=")
        append(
            rows(
                "SELECT revision, session_generation, phase FROM sync_session_authority WHERE id = 1",
                3,
            ),
        )
        append("; cache=")
        append(
            rows("SELECT account_id, baseline_complete, cache_epoch FROM sync_account_cache", 3),
        )
    }

    private suspend fun activateOffline(
        seen: MutableList<LegacyImportReplacementRequest>,
    ): AppSessionCoordinator {
        captureMinimal()
        val callsAfterCapture = mutationCalls
        val transport = AppSessionTransport()
        serveSettlement(transport, minimalSnapshot())
        val facade = coordinator(
            origin = ServiceOrigin.parse(ORIGIN),
            policy = optInPolicy(seen, answer = true),
            transport = transport,
        )
        facade.acceptGrant(RoomSessionAuthority(database).read(), OPERATION, grant())
        val state = facade.completeActivation(RoomSessionAuthority(database).read(), STAGE)
        assertTrue(
            "expected Active [${activationDiagnostics(transport)}], was $state",
            state is AppSessionState.Active,
        )
        // Push settled before snapshot: push route first, then snapshot/pull tail.
        val paths = transport.capturedRequests.map { it.path }
        assertTrue(paths.first().contains("push"))
        assertTrue(paths.any { it.contains("snapshot") })
        // Both original mutations settled with durable receipts; no second import.
        assertEquals(callsAfterCapture, mutationCalls)
        assertMinimalMaterialized()
        assertEquals(
            2L,
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM sync_legacy_import_acceptances",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0)
            },
        )
        return facade
    }

    private fun receiptRows(): List<List<String?>> =
        database.openHelper.readableDatabase.query(
            "SELECT ordinal, mutation_id, batch_json FROM sync_legacy_import_acceptances ORDER BY ordinal",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                        ),
                    )
                }
            }
        }

    @Test
    fun settledObserve_reportsCompleteAcceptance() =
        kotlinx.coroutines.test.runTest {
            activateOffline(mutableListOf())
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(mutableListOf()),
            )
            val progress = facade.observeLegacyImport()
            assertNotNull(progress)
            // SETTLED derives only from all valid acceptance receipts; the name
            // comparison keeps this oracle independent of enum declaration order.
            assertEquals("SETTLED", progress!!.phase.name)
            assertTrue(progress.importCount > 0)
            assertEquals(progress.importCount, progress.acceptedCount)
        }

    @Test
    fun swappedReceiptBatches_blockObservationAndOfflineAdmit() =
        kotlinx.coroutines.test.runTest {
            val facade = activateOffline(mutableListOf())
            val receipts = receiptRows()
            assertEquals(2, receipts.size)
            // Both batches stay well-formed; only their root binding is wrong.
            exec(
                "UPDATE sync_legacy_import_acceptances SET batch_json = '" +
                    receipts[1][2]!!.replace("'", "''") + "' WHERE ordinal = 0",
            )
            exec(
                "UPDATE sync_legacy_import_acceptances SET batch_json = '" +
                    receipts[0][2]!!.replace("'", "''") + "' WHERE ordinal = 1",
            )
            try {
                facade.observeLegacyImport()
                fail("receipt bound to the wrong root must fail closed")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            val admitted = runCatching { facade.recover() }
            assertTrue(
                "wrong-root receipt must never admit, was ${admitted.getOrNull()}",
                admitted.getOrNull() !is AppSessionState.Active,
            )
        }

    @Test
    fun validChangedEntryBusinessAfterSettlement_blocksObservationAndOfflineAdmit() =
        kotlinx.coroutines.test.runTest {
            val facade = activateOffline(mutableListOf())
            // Original outbox is gone after settlement; the exact
            // mutation/affected-root/envelope binding must still be checked
            // against the frozen entries.
            assertEquals(0, countOutbox())
            val original = rows(
                "SELECT payload_json FROM sync_legacy_import_entries WHERE ordinal = 0",
                1,
            ).single()[0]!!
            val wrapper = JSONObject(original)
            val request = JSONObject(wrapper.getString("requestJson"))
            request.getJSONObject("payload").getJSONObject("record").put("name", "Changed After Settlement")
            wrapper.put("requestJson", request.toString())
            exec(
                "UPDATE sync_legacy_import_entries SET payload_json = '" +
                    wrapper.toString().replace("'", "''") + "' WHERE ordinal = 0",
            )
            try {
                facade.observeLegacyImport()
                fail("changed entry business must fail closed")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            val admitted = runCatching { facade.recover() }
            assertTrue(
                "changed business must never admit, was ${admitted.getOrNull()}",
                admitted.getOrNull() !is AppSessionState.Active,
            )
        }

    @Test
    fun orphanSourceSet_afterSettlement_blocksObservationAndAdmit() =
        kotlinx.coroutines.test.runTest {
            activateOffline(mutableListOf())
            // Clone a valid stored set row and append it with a fresh set UUID
            // under an unknown canonical parent: keys/widths/tags stay valid,
            // but the orphan binds no entry or domain row.
            val source = JSONObject(
                rows("SELECT source_json FROM sync_legacy_import WHERE id = 1", 1).single()[0]!!,
            )
            val sets = source.getJSONArray("workout_sets")
            assertTrue(sets.length() > 0)
            val orphan = org.json.JSONArray(sets.getJSONArray(0).toString())
            assertEquals(7, orphan.length())
            orphan.put(0, JSONObject().put("t", "text").put("v", "f0000001-c13b-4c13-8c13-c13bc13bc13b"))
            orphan.put(1, JSONObject().put("t", "text").put("v", "d0000001-c13b-4c13-8c13-c13bc13bc13b"))
            sets.put(orphan)
            val tampered = source.toString()
            exec(
                "UPDATE sync_legacy_import SET source_json = '" +
                    tampered.replace("'", "''") + "' WHERE id = 1",
            )
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(mutableListOf()),
            )
            try {
                facade.observeLegacyImport()
                fail("orphan source child must fail closed")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            try {
                LegacyImportStore(
                    database = database,
                    barrier = FakeCaptureBarrier(),
                    processCheck = {},
                    newMutationId = { throw AssertionError("orphan evidence must not recapture") },
                ).read(
                    LegacyImportTarget(
                        origin = ServiceOrigin.parse(ORIGIN),
                        accountId = ACCOUNT,
                        operationId = OPERATION,
                    ),
                )
                fail("corrupt read must throw closed error")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
                assertNull(e.cause)
            }
            val admitted = runCatching { facade.recover() }
            assertTrue(
                "orphan source must never admit, was ${admitted.getOrNull()}",
                admitted.getOrNull() !is AppSessionState.Active,
            )
            // Bytes/evidence retention by direct SQL; never corrupt-DTO success.
            assertEquals(
                tampered,
                rows("SELECT source_json FROM sync_legacy_import WHERE id = 1", 1).single()[0],
            )
            assertEquals(1, journalCount())
            assertEquals(2, receiptRows().size)
            assertEquals(2L, count("SELECT COUNT(*) FROM sync_legacy_import_entries"))
        }

    @Test
    fun foreignProvenanceCell_afterSettlement_blocksObservationAndAdmit() =
        kotlinx.coroutines.test.runTest {
            activateOffline(mutableListOf())
            // Archived workouts rows are 10 cells; index 5 is the originally
            // null account_id provenance cell. Replace ONLY that cell with a
            // foreign canonical UUID; business payload and receipts unchanged.
            val source = JSONObject(
                rows("SELECT source_json FROM sync_legacy_import WHERE id = 1", 1).single()[0]!!,
            )
            val archived = source.getJSONArray("workouts").getJSONArray(0)
            assertEquals(10, archived.length())
            assertEquals("null", archived.getJSONObject(5).getString("t"))
            archived.put(5, JSONObject().put("t", "text").put("v", "ffffffff-ffff-4fff-8fff-ffffffffffff"))
            val tampered = source.toString()
            exec(
                "UPDATE sync_legacy_import SET source_json = '" +
                    tampered.replace("'", "''") + "' WHERE id = 1",
            )
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(mutableListOf()),
            )
            try {
                facade.observeLegacyImport()
                fail("foreign provenance must fail closed")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            try {
                LegacyImportStore(
                    database = database,
                    barrier = FakeCaptureBarrier(),
                    processCheck = {},
                    newMutationId = { throw AssertionError("foreign evidence must not recapture") },
                ).read(
                    LegacyImportTarget(
                        origin = ServiceOrigin.parse(ORIGIN),
                        accountId = ACCOUNT,
                        operationId = OPERATION,
                    ),
                )
                fail("corrupt read must throw closed error")
            } catch (e: LegacyImportException) {
                assertEquals("Legacy import failure", e.message)
                assertNull(e.cause)
            }
            val admitted = runCatching { facade.recover() }
            assertTrue(
                "foreign provenance must never admit, was ${admitted.getOrNull()}",
                admitted.getOrNull() !is AppSessionState.Active,
            )
            assertEquals(
                tampered,
                rows("SELECT source_json FROM sync_legacy_import WHERE id = 1", 1).single()[0],
            )
            assertEquals(1, journalCount())
            assertEquals(2, receiptRows().size)
        }

    @Test
    fun missingConsentAfterSettlement_blocksCompletionAndAdmit() =
        kotlinx.coroutines.test.runTest {
            val facade = activateOffline(mutableListOf())
            assertNotNull(progressConsent())
            exec("UPDATE sync_legacy_import_progress SET active_consent_mutation_id = NULL WHERE id = 1")
            val outcome = runCatching {
                facade.completeActivation(RoomSessionAuthority(database).read(), STAGE)
            }
            val state = outcome.getOrNull()
            assertTrue(
                "missing consent must block completion, was $state/${outcome.exceptionOrNull()}",
                state !is AppSessionState.Active,
            )
            val admitted = runCatching { facade.recover() }
            assertTrue(
                "missing consent must never admit, was ${admitted.getOrNull()}",
                admitted.getOrNull() !is AppSessionState.Active,
            )
        }

    private fun rows(sql: String, columns: Int): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun count(sql: String): Long =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun countOutbox(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_outbox").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun journalCount(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_legacy_import").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun progressConsent(): String? =
        database.openHelper.readableDatabase.query(
            "SELECT active_consent_mutation_id FROM sync_legacy_import_progress WHERE id = 1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    @Test
    fun unconfiguredObserve_refusesConfigurationUnavailable() =
        kotlinx.coroutines.test.runTest {
            val facade = coordinator(origin = null, policy = null)
            try {
                facade.observeLegacyImport()
                fail("unconfigured observe must refuse")
            } catch (e: AppSessionException) {
                assertEquals(AppSessionReason.CONFIGURATION_UNAVAILABLE, e.reason)
                AppSessionFixtures.assertClosed(e)
            }
        }

    @Test
    fun optInObserveWithNoMigration_returnsNullWithoutFabrication() =
        kotlinx.coroutines.test.runTest {
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(mutableListOf()),
            )
            assertNull(facade.observeLegacyImport())
        }

    @Test
    fun optInObserveWithCapture_reportsCapturedAdvisory() =
        kotlinx.coroutines.test.runTest {
            val target = captureMinimal()
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(seen),
            )
            val progress = facade.observeLegacyImport()
            assertNotNull(progress)
            assertEquals(LegacyImportProgressPhase.CAPTURED, progress!!.phase)
            assertTrue(progress.importCount > 0)
            assertEquals(0L, progress.acceptedCount)
            assertFalse(progress.activeReplacementConsented)
            val rendered = progress.toString()
            assertTrue(rendered.isNotBlank())
            assertFalse(rendered.contains(ACCOUNT))
            assertFalse(rendered.contains(OPERATION))
            assertTrue(seen.isEmpty())
            assertEquals(target.operationId, OPERATION)
        }

    @Test
    fun consentRefusal_returnsPrivateWithZeroHttpAndNoConsentRecord() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val transport = AppSessionTransport()
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(seen, answer = false),
                transport = transport,
            )
            facade.acceptGrant(RoomSessionAuthority(database).read(), OPERATION, grant())
            val state = facade.completeActivation(RoomSessionAuthority(database).read(), STAGE)
            assertTrue("refused consent must stay Private, was $state", state is AppSessionState.Private)
            assertEquals(1, seen.size)
            assertEquals(OPERATION, seen.single().target.operationId)
            assertTrue("refusal must send no HTTP", transport.capturedRequests.isEmpty())
            assertNull(progressConsent())
            assertEquals(1, journalCount())
        }

    @Test
    fun consentCancellation_recordsNoConsentAndSendsNoHttp() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val transport = AppSessionTransport()
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = LegacyImportPolicy(
                    replacementConsent = LegacyImportReplacementConsent { request ->
                        seen += request
                        entered.complete(Unit)
                        release.await()
                        true
                    },
                ),
                transport = transport,
            )
            facade.acceptGrant(RoomSessionAuthority(database).read(), OPERATION, grant())
            val activation = async {
                facade.completeActivation(RoomSessionAuthority(database).read(), STAGE)
            }
            entered.await()
            activation.cancel(CancellationException("import-test-cancel"))
            release.complete(Unit)
            try {
                activation.await()
            } catch (_: CancellationException) {
            }
            assertNull(progressConsent())
            assertTrue("cancelled consent must send no HTTP", transport.capturedRequests.isEmpty())
            assertEquals(1, journalCount())
        }

    @Test
    fun consentAcceptance_recordsExactOriginalMutationDurably() =
        kotlinx.coroutines.test.runTest {
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            activateOffline(seen)
            assertEquals(1, seen.size)
            val recorded = progressConsent()
            assertNotNull(recorded)
            assertEquals(seen.single().mutationId, recorded)
            // Consent binds the original captured active-workout request exactly.
            val entryMutations = database.openHelper.readableDatabase.query(
                "SELECT mutation_id FROM sync_legacy_import_entries ORDER BY ordinal",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertTrue(entryMutations.contains(recorded))
        }

    @Test
    fun failedSnapshot_remainsPrivateWithEvidenceUntouched() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val transport = AppSessionTransport()
            // Settle both imports with genuine accepted batches first; fail
            // the snapshot phase ONLY, after the push routes completed.
            transport.serve = { _, request ->
                when {
                    request.path.contains("push") -> {
                        val mutations = JSONObject(checkNotNull(request.body) { "push requires a body" }).getJSONArray("mutations")
                        val outcomes = (0 until mutations.length()).map { index ->
                            val envelope = mutations.getJSONObject(index)
                            acceptedOutcome(
                                envelope.getString("mutationId"),
                                envelope.getString("aggregateType"),
                                envelope.getString("aggregateId"),
                            )
                        }
                        AppSessionTransport.Frame(
                            200,
                            JSONObject().put("results", org.json.JSONArray(outcomes)).toString(),
                        )
                    }
                    else -> AppSessionTransport.Frame(500, """{"error":"import-snapshot-down"}""")
                }
            }
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(seen, answer = true),
                transport = transport,
            )
            facade.acceptGrant(RoomSessionAuthority(database).read(), OPERATION, grant())
            val before = rows(
                "SELECT source_json, import_count FROM sync_legacy_import WHERE id = 1",
                2,
            )
            val outcome = runCatching {
                facade.completeActivation(RoomSessionAuthority(database).read(), STAGE)
            }
            val state = outcome.getOrNull()
            assertTrue(
                "failed snapshot must stay Private, was $state/${outcome.exceptionOrNull()}",
                state is AppSessionState.Private || outcome.exceptionOrNull() is AppSessionException,
            )
            // Push routes completed before the snapshot stop; both receipts durable.
            val paths = transport.capturedRequests.map { it.path }
            assertTrue(paths.first().contains("push"))
            assertTrue(paths.any { !it.contains("push") })
            assertEquals(2, receiptRows().size)
            assertEquals(before, rows("SELECT source_json, import_count FROM sync_legacy_import WHERE id = 1", 2))
            assertEquals(1, journalCount())
        }

    @Test
    fun offlineActivation_allowsLaterOrdinaryEditWithoutRecapture() =
        kotlinx.coroutines.test.runTest {
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val facade = activateOffline(seen)
            assertEquals(1, seen.size)
            val active = facade.recover()
            assertTrue("expected Active, was $active", active is AppSessionState.Active)
            val session = (active as AppSessionState.Active).session
            // Ordinary ACTIVE edit legitimately differs from frozen source: no recapture.
            session.repository.createCustomExercise("import Post Live", null, emptySet(), null, null, "")
            assertEquals(1, journalCount())
            val reread = LegacyImportStore(
                database = database,
                barrier = FakeCaptureBarrier(),
                processCheck = {},
                newMutationId = { throw AssertionError("post-activation edit must not recapture") },
            ).read(
                LegacyImportTarget(
                    origin = ServiceOrigin.parse(ORIGIN),
                    accountId = ACCOUNT,
                    operationId = OPERATION,
                ),
            )
            assertNotNull(reread)
        }

    @Test
    fun reauthenticateWithoutOptIn_refusesUnchanged() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val facade = coordinator(origin = ServiceOrigin.parse(ORIGIN), policy = null)
            val before = RoomSessionAuthority(database).read()
            try {
                facade.reauthenticateLegacyImport(before, NEW_OPERATION, grant())
                fail("unconfigured reauthentication must refuse")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            assertEquals(1, journalCount())
        }

    @Test
    fun reauthenticateWithForeignOrigin_refusesUnchanged() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val facade = coordinator(
                origin = ServiceOrigin.parse(OTHER_ORIGIN),
                policy = optInPolicy(seen),
            )
            val before = RoomSessionAuthority(database).read()
            try {
                facade.reauthenticateLegacyImport(before, NEW_OPERATION, grant())
                fail("foreign origin must refuse")
            } catch (e: AppSessionException) {
                assertEquals(AppSessionReason.IDENTITY_MISMATCH, e.reason)
                AppSessionFixtures.assertClosed(e)
            }
            assertEquals(1, journalCount())
            assertTrue(seen.isEmpty())
        }

    @Test
    fun reauthenticateWithSameOperation_refusesUnchanged() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(seen),
            )
            val before = RoomSessionAuthority(database).read()
            try {
                facade.reauthenticateLegacyImport(before, OPERATION, grant())
                fail("reused operation must refuse")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            assertEquals(1, journalCount())
            assertTrue(seen.isEmpty())
        }

    private fun freshGrant(): ServiceSession =
        ServiceSession(
            ServiceAccountRef(ACCOUNT, null),
            OpaqueToken("import-access-002"),
            GRANT_EXPIRY,
            OpaqueToken("import-refresh-002"),
            GRANT_EXPIRY + 30L * 24 * 60 * 60 * 1000,
        )

    @Test
    fun freshSameAccountReauthentication_preservesOriginalMigration() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(seen),
            )
            val authority = RoomSessionAuthority(database).read()
            facade.acceptGrant(authority, OPERATION, grant())
            val generationBefore = RoomSessionAuthority(database).read().sessionGeneration
            val state = facade.reauthenticateLegacyImport(
                RoomSessionAuthority(database).read(),
                NEW_OPERATION,
                freshGrant(),
            )
            assertNotNull(state)
            // Original migration IDs/bytes/provenance intact; lineage advanced only.
            database.openHelper.readableDatabase.query(
                "SELECT operation_id, source_json, import_count FROM sync_legacy_import WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(OPERATION, cursor.getString(0))
                assertFalse(cursor.isNull(1))
                assertTrue(cursor.getLong(2) > 0)
            }
            assertEquals(1, journalCount())
            assertTrue(
                RoomSessionAuthority(database).read().sessionGeneration > generationBefore,
            )
        }

    @Test
    fun sharedUuidCrossFamily_rejectedRootRetainedForever() =
        kotlinx.coroutines.test.runTest {
            seedSharedGraph()
            val target = captureSharedGraph()
            // Partial settlement: the shared workout root is retained rejected
            // while its same-UUID siblings settle; binding stays per family.
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val transport = AppSessionTransport()
            serveRejectingWorkout(transport)
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(seen, answer = true),
                transport = transport,
            )
            facade.acceptGrant(RoomSessionAuthority(database).read(), OPERATION, grant())
            val partial = facade.completeActivation(RoomSessionAuthority(database).read(), STAGE)
            assertTrue("partial settlement must stay Private, was $partial", partial is AppSessionState.Private)
            assertEquals(
                listOf(MUTATION_C),
                rows("SELECT mutation_id FROM sync_outbox ORDER BY sequence", 1).map { it[0] },
            )
            assertEquals(listOf(MUTATION_A, MUTATION_B), receiptRows().map { it[1] })
            val progress = facade.observeLegacyImport()
            assertNotNull(progress)
            assertEquals(3L, progress!!.importCount)
            assertEquals(2L, progress.acceptedCount)
            assertEquals("CAPTURED", progress.phase.name)

            // Permanent rejection is retained forever: an exact duplicate replay
            // replays the rejection under the same mutation — no new-ID retry,
            // discard or remap is authorized, and Active is never reached.
            val replayed = facade.completeActivation(RoomSessionAuthority(database).read(), STAGE)
            assertTrue("rejection replay must stay Private, was $replayed", replayed is AppSessionState.Private)
            assertEquals(
                listOf(MUTATION_C),
                rows("SELECT mutation_id FROM sync_outbox ORDER BY sequence", 1).map { it[0] },
            )
            assertEquals(listOf(MUTATION_A, MUTATION_B), receiptRows().map { it[1] })
            assertEquals(target.operationId, OPERATION)
        }

    @Test
    fun sharedUuidCrossFamily_fullSettlementSucceeds() =
        kotlinx.coroutines.test.runTest {
            seedSharedGraph()
            captureSharedGraph()
            val seen = mutableListOf<LegacyImportReplacementRequest>()
            val transport = AppSessionTransport()
            serveSettlement(transport, sharedSnapshot())
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(seen, answer = true),
                transport = transport,
            )
            facade.acceptGrant(RoomSessionAuthority(database).read(), OPERATION, grant())
            val settled = facade.completeActivation(RoomSessionAuthority(database).read(), STAGE)
            assertTrue("expected Active, was $settled", settled is AppSessionState.Active)
            assertEquals(3, receiptRows().size)
            assertEquals(0, countOutbox())
            assertSharedMaterialized()
            val progress = facade.observeLegacyImport()
            assertNotNull(progress)
            assertEquals("SETTLED", progress!!.phase.name)
            assertEquals(3L, progress.acceptedCount)
        }
    private fun seedSharedGraph() {
            // Same root UUID independently occurs in all three families: the
            // service namespace is (aggregateType, UUID), never UUID alone.
            exec(
                "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                    " instructions, is_user_created, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis)" +
                    " VALUES ('$SHARED', 'Shared Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                    " '[\"Step\"]', 1, NULL, NULL, 1, 'local_only', NULL)",
            )
            exec(
                "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis)" +
                    " VALUES ('$SHARED', 'Shared Routine', 0, NULL, NULL, 1, 'local_only', NULL)",
            )
            exec(
                "INSERT INTO routine_exercises (id, routine_id, exercise_id, position, rest_duration_seconds)" +
                    " VALUES ('$RE_SHARED', '$SHARED', '$SHARED', 0, 45)",
            )
            exec(
                "INSERT INTO routine_sets (id, routine_exercise_id, position, repetitions, weight_kg)" +
                    " VALUES ('$RS_SHARED', '$RE_SHARED', 0, 10, 40.0)",
            )
            exec(
                "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                    " server_revision, local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('$SHARED', 'Shared W', 4000, NULL, 'active', NULL, NULL, 1," +
                    " 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                    " VALUES ('$WE_SHARED', '$SHARED', '$SHARED', 0, 'Keep', 75)",
            )
            exec(
                "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                    " is_completed, rest_seconds) VALUES ('$WS_SHARED', '$WE_SHARED', 0, 8, 70.0, 1, 60)",
            )
    }

    private suspend fun captureSharedGraph(): LegacyImportTarget {
            val target = LegacyImportTarget(
                origin = ServiceOrigin.parse(ORIGIN),
                accountId = ACCOUNT,
                operationId = OPERATION,
            )
            val callsBefore = mutationCalls
            val store = LegacyImportStore(
                database = database,
                barrier = FakeCaptureBarrier(),
                processCheck = {},
                newMutationId = {
                    when (mutationCalls++ - callsBefore) {
                        0 -> MUTATION_A
                        1 -> MUTATION_B
                        else -> MUTATION_C
                    }
                },
            )
            val captured = store.capture(RoomSessionAuthority(database).read(), target)
            assertTrue(captured is LegacyImportCaptureResult.Retained)
            assertEquals(3L, (captured as LegacyImportCaptureResult.Retained).evidence.importCount)
            assertEquals(
                listOf("customExercise:$SHARED", "routine:$SHARED", "workout:$SHARED"),
                rows(
                    "SELECT aggregate_type || ':' || aggregate_id" +
                        " FROM sync_legacy_import_entries ORDER BY ordinal",
                    1,
                ).map { it[0] },
            )
            return target
    }

    /** Push bodies carry raw envelopes directly: accept per family, reject the workout. */
    private fun serveRejectingWorkout(transport: AppSessionTransport) {
            transport.serve = { _, request ->
                if (!request.path.contains("push")) {
                    AppSessionTransport.Frame(200, emptyTail())
                } else {
                    val mutations = JSONObject(checkNotNull(request.body) { "push requires a body" }).getJSONArray("mutations")
                    val outcomes = (0 until mutations.length()).map { index ->
                        val envelope = mutations.getJSONObject(index)
                        val mid = envelope.getString("mutationId")
                        val type = envelope.getString("aggregateType")
                        if (type == "workout") {
                            JSONObject()
                                .put("mutationId", mid)
                                .put("status", "rejected")
                                .put("errorCode", "gone")
                                .put("batch", JSONObject.NULL)
                        } else {
                            acceptedOutcome(mid, type, envelope.getString("aggregateId"))
                        }
                    }
                    AppSessionTransport.Frame(
                        200,
                        JSONObject().put("results", org.json.JSONArray(outcomes)).toString(),
                    )
                }
            }
    }

    @Test
    fun privateImportForegroundRequest_routesWithNewOperation() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val transport = AppSessionTransport()
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(mutableListOf()),
                transport = transport,
            )
            val request = facade.captureForegroundSignInRequest(
                intent = ForegroundSignInIntent.PRIVATE_IMPORT_REAUTHENTICATION,
                expected = RoomSessionAuthority(database).read(),
                operationId = NEW_OPERATION,
            )
            assertNotNull(request)
            val rendered = request.toString()
            assertTrue(rendered.isNotBlank())
            assertFalse(rendered.contains(ACCOUNT))
            assertFalse(rendered.contains(OPERATION))
            assertFalse(rendered.contains(NEW_OPERATION))
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(1, journalCount())
        }

    @Test
    fun privateImportForegroundRequest_refusesWithoutRetainedMigration() =
        kotlinx.coroutines.test.runTest {
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(mutableListOf()),
            )
            try {
                facade.captureForegroundSignInRequest(
                    intent = ForegroundSignInIntent.PRIVATE_IMPORT_REAUTHENTICATION,
                    expected = RoomSessionAuthority(database).read(),
                    operationId = NEW_OPERATION,
                )
                fail("private import without retained migration must refuse")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            assertEquals(0, journalCount())
        }

    @Test
    fun privateImportForegroundRequest_refusesReusedOperation() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val facade = coordinator(
                origin = ServiceOrigin.parse(ORIGIN),
                policy = optInPolicy(mutableListOf()),
            )
            try {
                facade.captureForegroundSignInRequest(
                    intent = ForegroundSignInIntent.PRIVATE_IMPORT_REAUTHENTICATION,
                    expected = RoomSessionAuthority(database).read(),
                    operationId = OPERATION,
                )
                fail("reused operation must refuse")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            assertEquals(1, journalCount())
        }

    @Test
    fun privateImportForegroundRequest_refusesWhenUnconfigured() =
        kotlinx.coroutines.test.runTest {
            captureMinimal()
            val facade = coordinator(origin = null, policy = null)
            try {
                facade.captureForegroundSignInRequest(
                    intent = ForegroundSignInIntent.PRIVATE_IMPORT_REAUTHENTICATION,
                    expected = RoomSessionAuthority(database).read(),
                    operationId = NEW_OPERATION,
                )
                fail("unconfigured private import must refuse")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertClosed(e)
            }
            assertEquals(1, journalCount())
        }

    private var finishedCalls = 0

    @Test
    fun legacyImportFixtures_selfCheck() {
        val uuidShape = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        for (id in listOf(
            ACCOUNT, OPERATION, NEW_OPERATION, STAGE,
            CUSTOM, WORKOUT, WE, WS, SHARED, RE_SHARED, RS_SHARED, WE_SHARED, WS_SHARED,
            MUTATION_A, MUTATION_B, MUTATION_C, BATCH_A, BATCH_B, BATCH_C,
        )) {
            assertTrue("canonical UUID <$id>", uuidShape.matches(id))
            assertEquals(id, UUID.fromString(id).toString())
        }
        val namespace = "gymtracka-test-import-" + UUID.randomUUID()
        assertTrue(namespace.startsWith("gymtracka-test-"))
        assertTrue(namespace.length <= 63)
        assertTrue(Regex("^[a-z][a-z0-9_-]{0,63}$").matches(namespace))
        // Builders round-trip through real JSON parsing with seed-exact fields.
        val custom = JSONObject(customRecord(CUSTOM, "Owned Custom").toString())
        assertEquals(CUSTOM, custom.getString("id"))
        assertEquals("Owned Custom", custom.getString("name"))
        assertEquals(1, custom.getInt("revision"))
        val workout = JSONObject(
            workoutRecord(WORKOUT, "Local W", WE, CUSTOM, "Owned Custom", WS).toString(),
        )
        assertEquals(WORKOUT, workout.getString("id"))
        assertEquals("active", workout.getString("status"))
        val set = workout.getJSONArray("exercises").getJSONObject(0)
            .getJSONArray("sets").getJSONObject(0)
        assertEquals(WS, set.getString("id"))
        assertEquals(60, set.getInt("restSeconds"))
        val batch = JSONObject(
            acceptedOutcome(MUTATION_A, "customExercise", CUSTOM).toString(),
        )
        assertEquals(MUTATION_A, batch.getString("mutationId"))
        assertEquals("accepted", batch.getString("status"))
        assertEquals(BATCH_A, batch.getJSONObject("batch").getString("batchId"))
        assertTrue(uuidShape.matches(batch.getJSONObject("batch").getString("batchId")))
        val snapshot = JSONObject(minimalSnapshot())
        assertEquals("import-baseline", snapshot.getString("baselineCursor"))
        assertEquals(2, snapshot.getJSONArray("records").length())
    }

    @Test
    fun replacementConsent_reachesSeamOnlyForActiveImport() =
        kotlinx.coroutines.test.runTest {
            // No active import means the warning seam is never consulted: the
            // finished-only graph below completes without any consent call.
            exec(
                "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                    " instructions, is_user_created, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis)" +
                    " VALUES ('$CUSTOM', 'Owned Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                    " '[\"Step\"]', 1, NULL, NULL, 1, 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                    " server_revision, local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('$WORKOUT', 'Finished W', 1000, 2000, 'finished', NULL, NULL, 2," +
                    " 'local_only', NULL)",
            )
            exec(
                "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                    " VALUES ('$WE', '$WORKOUT', '$CUSTOM', 0, 'Keep', 75)",
            )
            exec(
                "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                    " is_completed, rest_seconds) VALUES ('$WS', '$WE', 0, 8, 70.0, 1, 60)",
            )
            val target = LegacyImportTarget(
                origin = ServiceOrigin.parse(ORIGIN),
                accountId = ACCOUNT,
                operationId = OPERATION,
            )
            val store = LegacyImportStore(
                database = database,
                barrier = FakeCaptureBarrier(),
                processCheck = {},
                newMutationId = { if (finishedCalls++ == 0) MUTATION_A else MUTATION_B },
            )
            val captured = store.capture(RoomSessionAuthority(database).read(), target)
            assertTrue(captured is LegacyImportCaptureResult.Retained)
            assertEquals(2L, (captured as LegacyImportCaptureResult.Retained).evidence.importCount)
            assertEquals(1, journalCount())
        }
}
