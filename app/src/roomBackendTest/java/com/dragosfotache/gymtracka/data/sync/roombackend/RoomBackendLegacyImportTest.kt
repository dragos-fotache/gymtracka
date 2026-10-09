package com.dragosfotache.gymtracka.data.sync.roombackend

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpRequest
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpResponse
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportCaptureBarrier
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportCaptureResult
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPolicy
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementConsent
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportStore
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportTarget
import com.dragosfotache.gymtracka.data.sync.session.AndroidSessionVault
import com.dragosfotache.gymtracka.data.sync.session.AppSessionCoordinator
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R13b independent QA: isolated black-box Room HTTP import-to-activation.
 *
 * Derived ONLY from docs/archive/remaining-work/R13b-task.md (settlement/consent/
 * activation sequencing, risk obligation 8), docs/archive/remaining-work/R13b-harness.md
 * (real relay controls, TLS transport, `/__r10__/same-account-session`
 * contract, vault namespace/alias patterns) and coordinator-supplied missing
 * contracts (typed transient stops, real-collision procedure, ack-rollback
 * trigger, completion-gap lease fault, snapshot-only failure wrapper).
 * Local graphs are seeded genuinely local — default null-account repository
 * or raw local SQL, never an account claim. Domain successes come from the
 * real backend only; wrappers gate/drop real responses or fail selected
 * phases, never synthesize domain data. No backend source, config, host
 * implementation, production diagnostics or production-encoder output
 * inspected. All seeds are this slice's own literals with exact byte
 * equality, never substrings.
 *
 * Requires coordinator relay args (`roomBackendPort`, `roomBackendCertificate`)
 * and the same-account-session host gate; missing inputs FAIL, never skip.
 * Live readiness is coordinator-owned (Docker restoration pending).
 */
@RunWith(AndroidJUnit4::class)
class RoomBackendLegacyImportTest {

    private class Lease(private val onClose: (() -> Unit)? = null) : ProjectionPublicationLease {
        override fun checkValid() = Unit
        override fun close() {
            onClose?.invoke()
        }
    }

    private class CaptureBarrier : LegacyImportCaptureBarrier {
        override suspend fun acquire(expected: RecoveryRoomSnapshot): ProjectionPublicationLease =
            Lease()
    }

    private class PassThroughTransitionBarrier : com.dragosfotache.gymtracka.data.sync.session.AppSessionTransitionBarrier {
        override suspend fun acquire(previous: RepositorySession?): ProjectionPublicationLease =
            Lease()
    }

    private open class PassThroughPublicationBarrier : ProjectionPublicationBarrier {
        override suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease =
            Lease()
    }

    private class UnusedAuth : ServiceAuthApi {
        override suspend fun challenge(
            purpose: com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose,
        ): com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge =
            throw UnsupportedOperationException("R13b relay grants only")

        override suspend fun exchange(
            attempt: com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt,
        ): ServiceSession = throw UnsupportedOperationException("R13b relay grants only")

        override suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession =
            throw SyncApiException.Transient()

        override suspend fun logout(refreshToken: OpaqueToken) = Unit
    }

    /** Fails every HTTP use: proves HTTP-free recovery paths. */
    private class NoHttpTransport : ServiceHttpTransport {
        override suspend fun <T> withResponse(
            request: ServiceHttpRequest,
            consume: suspend (ServiceHttpResponse) -> T,
        ): T = throw AssertionError("no HTTP expected for ${request.path}")
    }

    private val callClient: OkHttpClient by lazy { OkHttpClient() }

    /** Real transport except snapshot pages, which fail closed with HTTP 500. */
    private inner class SnapshotFailTransport(
        private val real: ServiceHttpTransport,
    ) : ServiceHttpTransport {
        var snapshotCalls = 0
            private set

        override suspend fun <T> withResponse(
            request: ServiceHttpRequest,
            consume: suspend (ServiceHttpResponse) -> T,
        ): T {
            if (!request.path.contains("snapshot")) return real.withResponse(request, consume)
            snapshotCalls++
            val httpRequest = Request.Builder()
                .url("https://localhost/" + request.path.trimStart('/'))
                .build()
            val httpResponse = Response.Builder()
                .request(httpRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("R13b snapshot down")
                .header("Content-Type", "application/json")
                .body("""{"error":"r13b-snapshot-down"}""".toResponseBody("application/json".toMediaType()))
                .build()
            val wire = ServiceHttpResponse(callClient.newCall(httpRequest), httpResponse)
            try {
                return consume(wire)
            } finally {
                wire.closeAfterConsumer()
            }
        }
    }

    private data class Grant(
        val accountId: String,
        val accessToken: String,
        val session: ServiceSession,
    )

    private suspend fun mintSessionGrant(
        transport: R10aTransport,
        port: Int,
    ): Grant {
        val body = r10aPostControl(transport, port, "/__r10__/session", bearer = null)
        val json = JSONObject(body)
        val accountId = json.getString("accountId")
        val accessToken = json.getString("accessToken")
        val session = ServiceSession(
            ServiceAccountRef(accountId, null),
            OpaqueToken(accessToken),
            json.getLong("accessExpiresAtMillis"),
            OpaqueToken(json.getString("refreshToken")),
            json.getLong("refreshExpiresAtMillis"),
        )
        return Grant(accountId, accessToken, session)
    }

    private suspend fun mintSameAccountGrant(
        transport: R10aTransport,
        port: Int,
        bearer: String,
    ): Grant {
        val body = r10aPostControl(transport, port, "/__r10__/same-account-session", bearer = bearer)
        val json = JSONObject(body)
        val accountId = json.getString("accountId")
        val accessToken = json.getString("accessToken")
        val session = ServiceSession(
            ServiceAccountRef(accountId, null),
            OpaqueToken(accessToken),
            json.getLong("accessExpiresAtMillis"),
            OpaqueToken(json.getString("refreshToken")),
            json.getLong("refreshExpiresAtMillis"),
        )
        return Grant(accountId, accessToken, session)
    }

    private fun fileDb(context: Context, name: String): GymDatabase {
        context.deleteDatabase(name)
        return Room.databaseBuilder(context, GymDatabase::class.java, name).build()
    }

    private fun reopenDb(context: Context, name: String): GymDatabase =
        Room.databaseBuilder(context, GymDatabase::class.java, name).build()

    private fun vaultNamespace(): String = "gymtracka-test-r13b-${UUID.randomUUID()}"

    private fun testAlias(namespace: String): String =
        "com.dragosfotache.gymtracka.session.$namespace"

    /** Strict test-owned cleanup: failures propagate, never swallowed. */
    private fun deleteNamespace(context: Context, namespace: String) {
        val dir = File(context.noBackupFilesDir, namespace)
        dir.listFiles()?.forEach { check(it.delete()) { "cannot delete $it" } }
        if (dir.exists()) check(dir.delete()) { "cannot delete $dir" }
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            .deleteEntry(testAlias(namespace))
    }

    private fun count(database: GymDatabase, sql: String): Long =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun journalCount(database: GymDatabase): Long =
        count(database, "SELECT COUNT(*) FROM sync_legacy_import")

    private fun rows(database: GymDatabase, sql: String, columns: Int): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    /** Genuinely local seed: default null-account repository, never an account claim. */
    private suspend fun seedLocalGraph(database: GymDatabase) {
        val repository = GymRepository(database)
        val customId = repository.createCustomExercise("R13b Live Press", null, emptySet(), null, null, "")
        val workout = repository.startEmptyWorkout(1_790_259_200_000L)
        repository.updateWorkoutName(workout.id, "R13b Live W")
        repository.addExercisesToWorkout(workout.id, listOf(customId))
    }

    private fun captureStore(
        database: GymDatabase,
        mutations: MutableList<String>,
        onMint: (() -> Unit)? = null,
    ): LegacyImportStore =
        LegacyImportStore(
            database = database,
            barrier = CaptureBarrier(),
            processCheck = {},
            newMutationId = {
                onMint?.invoke()
                mutations.removeFirst()
            },
        )

    private fun facade(
        database: GymDatabase,
        context: Context,
        namespace: String,
        origin: ServiceOrigin,
        transport: ServiceHttpTransport,
        publicationBarrier: ProjectionPublicationBarrier = PassThroughPublicationBarrier(),
        consents: MutableList<com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementRequest> = mutableListOf(),
        clockMillis: () -> Long = System::currentTimeMillis,
    ): AppSessionCoordinator =
        AppSessionCoordinator(
            database = database,
            origin = origin,
            vault = AndroidSessionVault.createForTesting(context, namespace),
            authApi = UnusedAuth(),
            transport = transport,
            runOwner = PushRunOwner(),
            transitionBarrier = PassThroughTransitionBarrier(),
            publicationBarrier = publicationBarrier,
            clockMillis = clockMillis,
            uuidProvider = { UUID.randomUUID().toString() },
            legacyImportPolicy = LegacyImportPolicy(
                replacementConsent = LegacyImportReplacementConsent { request ->
                    consents += request
                    true
                },
            ),
        )

    @Test
    fun fileCapture_survivesCloseReopenWithActualVaultAndExactReplay() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val account = r10aMintAccount(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-reopen-${UUID.randomUUID()}.db"
            try {
                var database = fileDb(context, name)
                assertNotNull(AndroidSessionVault.createForTesting(context, namespace))
                seedLocalGraph(database)
                val target = LegacyImportTarget(
                    origin = ServiceOrigin.parse("https://localhost:${inputs.port}"),
                    accountId = account.accountId,
                    operationId = "d13b3001-1111-4111-8111-111111111111",
                )
                // One unique mutation per root: custom + active workout.
                val mutations = mutableListOf(
                    "70000001-d13b-4d13-8d13-d13bd13bd13b",
                    "70000002-d13b-4d13-8d13-d13bd13bd13b",
                )
                var calls = 0
                val retained = captureStore(database, mutations) { calls++ }
                    .capture(RoomSessionAuthority(database).read(), target)
                assertTrue(retained is LegacyImportCaptureResult.Retained)
                val evidence = (retained as LegacyImportCaptureResult.Retained).evidence
                assertEquals(com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPhase.CAPTURED, evidence.phase)
                assertEquals(2L, evidence.importCount)
                assertEquals(2, calls)
                database.close()

                val reopened = reopenDb(context, name)
                try {
                    val recovered = captureStore(reopened, mutableListOf()) {
                        fail("reopen must not mint mutation IDs")
                    }.read(target)
                    assertNotNull(recovered)
                    assertEquals(evidence.sourceJson, recovered!!.sourceJson)
                    assertEquals(evidence.importCount, recovered.importCount)
                    assertEquals(1L, journalCount(reopened))
                    // Exact frozen replay: no second import, no new mutation IDs.
                    val again = captureStore(reopened, mutableListOf()) { calls++ }
                        .capture(RoomSessionAuthority(reopened).read(), target)
                    assertTrue(again is LegacyImportCaptureResult.Retained)
                    assertEquals(2, calls)
                    assertNotNull(AndroidSessionVault.createForTesting(context, namespace))
                } finally {
                    reopened.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun sharedUuidCrossFamily_survivesReopenWithPerFamilyBinding() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val account = r10aMintAccount(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-shared-${UUID.randomUUID()}.db"
            val shared = "99999999-d13b-4d13-8d13-d13bd13bd13b"
            val sharedRe = "f1000013-d13b-4d13-8d13-d13bd13bd13b"
            val sharedRs = "f2000013-d13b-4d13-8d13-d13bd13bd13b"
            val sharedWe = "d3000013-d13b-4d13-8d13-d13bd13bd13b"
            val sharedWs = "e3000013-d13b-4d13-8d13-d13bd13bd13b"
            try {
                var database = fileDb(context, name)
                assertNotNull(AndroidSessionVault.createForTesting(context, namespace))
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics," +
                        " category, instructions, is_user_created, account_id, server_revision," +
                        " local_generation, sync_state, deleted_at_millis)" +
                        " VALUES ('$shared', 'Shared Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                        " '[\"Step\"]', 1, NULL, NULL, 1, 'local_only', NULL)",
                )
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                        " sync_state, deleted_at_millis)" +
                        " VALUES ('$shared', 'Shared Routine', 0, NULL, NULL, 1, 'local_only', NULL)",
                )
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO routine_exercises (id, routine_id, exercise_id, position, rest_duration_seconds)" +
                        " VALUES ('$sharedRe', '$shared', '$shared', 0, 45)",
                )
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO routine_sets (id, routine_exercise_id, position, repetitions, weight_kg)" +
                        " VALUES ('$sharedRs', '$sharedRe', 0, 10, 40.0)",
                )
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                        " server_revision, local_generation, sync_state, deleted_at_millis)" +
                        " VALUES ('$shared', 'Shared W', 4000, NULL, 'active', NULL, NULL, 1," +
                        " 'local_only', NULL)",
                )
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                        " VALUES ('$sharedWe', '$shared', '$shared', 0, 'Keep', 75)",
                )
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                        " is_completed, rest_seconds)" +
                        " VALUES ('$sharedWs', '$sharedWe', 0, 8, 70.0, 1, 60)",
                )
                val target = LegacyImportTarget(
                    origin = ServiceOrigin.parse("https://localhost:${inputs.port}"),
                    accountId = account.accountId,
                    operationId = "d13b3020-0000-4000-8000-000000000000",
                )
                val mutations = mutableListOf(
                    "70000020-d13b-4d13-8d13-d13bd13bd13b",
                    "70000021-d13b-4d13-8d13-d13bd13bd13b",
                    "70000022-d13b-4d13-8d13-d13bd13bd13b",
                )
                var calls = 0
                val retained = captureStore(database, mutations) { calls++ }
                    .capture(RoomSessionAuthority(database).read(), target)
                assertTrue(retained is LegacyImportCaptureResult.Retained)
                val evidence = (retained as LegacyImportCaptureResult.Retained).evidence
                // Three families share one UUID; binding stays (type, UUID).
                assertEquals(3L, evidence.importCount)
                assertEquals(3, calls)
                assertEquals(
                    listOf("customExercise:$shared", "routine:$shared", "workout:$shared"),
                    rows(
                        database,
                        "SELECT aggregate_type || ':' || aggregate_id" +
                            " FROM sync_legacy_import_entries ORDER BY ordinal",
                        1,
                    ).map { it[0] },
                )
                database.close()

                val reopened = reopenDb(context, name)
                try {
                    val recovered = captureStore(reopened, mutableListOf()) {
                        fail("reopen must not mint mutation IDs")
                    }.read(target)
                    assertNotNull(recovered)
                    assertEquals(evidence.sourceJson, recovered!!.sourceJson)
                    assertEquals(evidence.importCount, recovered.importCount)
                    assertEquals(1L, journalCount(reopened))
                    // Exact frozen replay recovers without new mutation IDs.
                    val callsBeforeReplay = calls
                    val again = captureStore(reopened, mutableListOf()) {
                        fail("replay must not mint mutation IDs")
                    }.capture(RoomSessionAuthority(reopened).read(), target)
                    assertTrue(again is LegacyImportCaptureResult.Retained)
                    assertEquals(callsBeforeReplay, calls)
                    assertNotNull(AndroidSessionVault.createForTesting(context, namespace))
                } finally {
                    reopened.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun wholeGraphJourney_realBackendDeliversAllFamilies() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-whole-${UUID.randomUUID()}.db"
            // Mutable roots/children use independently generated per-test
            // canonical UUIDs: no global cross-account ID reservations on reruns.
            // Pinned/shared catalogue identities come from the bundled asset DB
            // (backend-seeded); they are selected below, never synthesized.
            val customRich = UUID.randomUUID().toString()
            val customMin = UUID.randomUUID().toString()
            val routineBlank = UUID.randomUUID().toString()
            val routineDraft = UUID.randomUUID().toString()
            val routinePop = UUID.randomUUID().toString()
            val workoutFin = UUID.randomUUID().toString()
            val workoutAct = UUID.randomUUID().toString()
            val weFin = UUID.randomUUID().toString()
            val wsFin = UUID.randomUUID().toString()
            val weAct = UUID.randomUUID().toString()
            val wsAct = UUID.randomUUID().toString()
            val rePop = UUID.randomUUID().toString()
            val rsPop = UUID.randomUUID().toString()
            context.deleteDatabase(name)
            var database = Room.databaseBuilder(context, GymDatabase::class.java, name)
                .createFromAsset("databases/gym.db")
                .addMigrations(*GymDatabase.MIGRATIONS)
                .build()
            try {
                try {
                    val muscle = rows(
                        database,
                        "SELECT id FROM muscle_groups ORDER BY id LIMIT 1",
                        1,
                    ).single()[0]!!
                    val equip = rows(
                        database,
                        "SELECT id FROM equipment ORDER BY id LIMIT 1",
                        1,
                    ).single()[0]!!
                    val db = database.openHelper.writableDatabase
                    db.execSQL(
                        "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                            " instructions, is_user_created, account_id, server_revision, local_generation," +
                            " sync_state, deleted_at_millis)" +
                            " VALUES ('$customRich', 'R13b Rich', 'reps', 'beginner', 'push', 'compound'," +
                            " 'strength', '[\"One\",\"Two\"]', 1, NULL, NULL, 3, 'local_only', NULL)",
                    )
                    db.execSQL("INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES ('$customRich', '$muscle', 'primary')")
                    db.execSQL("INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items) VALUES ('$customRich', '$equip', 2)")
                    db.execSQL(
                        "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                            " instructions, is_user_created, account_id, server_revision, local_generation," +
                            " sync_state, deleted_at_millis)" +
                            " VALUES ('$customMin', 'R13b Min', 'reps', NULL, NULL, NULL, 'strength'," +
                            " NULL, 1, NULL, NULL, 1, 'local_only', NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                            " sync_state, deleted_at_millis)" +
                            " VALUES ('$routineBlank', '', 0, NULL, NULL, 2, 'local_only', NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                            " sync_state, deleted_at_millis)" +
                            " VALUES ('$routineDraft', '', 1, NULL, NULL, 1, 'local_only', NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO routines (id, name, is_draft, account_id, server_revision, local_generation," +
                            " sync_state, deleted_at_millis)" +
                            " VALUES ('$routinePop', 'R13b Populated', 0, NULL, NULL, 6, 'local_only', NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO routine_exercises (id, routine_id, exercise_id, position, rest_duration_seconds)" +
                            " VALUES ('$rePop', '$routinePop', '$customRich', 0, 45)",
                    )
                    db.execSQL(
                        "INSERT INTO routine_sets (id, routine_exercise_id, position, repetitions, weight_kg)" +
                            " VALUES ('$rsPop', '$rePop', 0, 10, 40.0)",
                    )
                    db.execSQL(
                        "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                            " server_revision, local_generation, sync_state, deleted_at_millis)" +
                            " VALUES ('$workoutFin', 'R13b Finished', 1000, 2000, 'finished', NULL, NULL, 4," +
                            " 'local_only', NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                            " VALUES ('$weFin', '$workoutFin', '$customRich', 0, 'Keep', 75)",
                    )
                    db.execSQL(
                        "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                            " is_completed, rest_seconds) VALUES ('$wsFin', '$weFin', 0, 8, 70.0, 1, 60)",
                    )
                    db.execSQL(
                        "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                            " server_revision, local_generation, sync_state, deleted_at_millis)" +
                            " VALUES ('$workoutAct', 'R13b Active', 5000, NULL, 'active', NULL, NULL, 5," +
                            " 'local_only', NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                            " VALUES ('$weAct', '$workoutAct', '$customMin', 0, '', 30)",
                    )
                    db.execSQL(
                        "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                            " is_completed, rest_seconds) VALUES ('$wsAct', '$weAct', 0, NULL, NULL, 0, NULL)",
                    )
                    val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                    val operationId = "d13b3021-1111-4111-8111-111111111111"
                    val target = LegacyImportTarget(
                        origin = origin,
                        accountId = grant.accountId,
                        operationId = operationId,
                    )
                    val mutations = ArrayList((1..7).map { UUID.randomUUID().toString() })
                    val retained = captureStore(database, mutations)
                        .capture(RoomSessionAuthority(database).read(), target)
                    assertTrue(retained is LegacyImportCaptureResult.Retained)
                    val evidence = (retained as LegacyImportCaptureResult.Retained).evidence
                    assertEquals(
                        com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPhase.CAPTURED,
                        evidence.phase,
                    )
                    assertEquals(7L, evidence.importCount)
                    // Ordered original requests: customs canonically sorted,
                    // routines canonically sorted, finished then active last.
                    val expectedOrder = listOf(customRich, customMin).sorted().map { "customExercise:$it" } +
                        listOf(routineBlank, routineDraft, routinePop).sorted().map { "routine:$it" } +
                        listOf("workout:$workoutFin", "workout:$workoutAct")
                    assertEquals(
                        expectedOrder,
                        rows(
                            database,
                            "SELECT aggregate_type || ':' || aggregate_id" +
                                " FROM sync_legacy_import_entries ORDER BY ordinal",
                            1,
                        ).map { it[0] },
                    )
                    val sourceBefore = rows(
                        database,
                        "SELECT source_json FROM sync_legacy_import WHERE id = 1",
                        1,
                    ).single()[0]

                    val coordinator = facade(database, context, namespace, origin, transport.transport)
                    coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                    val state = coordinator.completeActivation(
                        RoomSessionAuthority(database).read(),
                        "d13b3022-2222-4222-8222-222222222222",
                    )
                    assertTrue("expected Active, was $state", state is AppSessionState.Active)
                    (state as AppSessionState.Active).session.checkCurrent()

                    // Source bytes preserved; every original request receipted.
                    assertEquals(
                        sourceBefore,
                        rows(database, "SELECT source_json FROM sync_legacy_import WHERE id = 1", 1).single()[0],
                    )
                    assertEquals(1L, journalCount(database))
                    assertEquals(7L, count(database, "SELECT COUNT(*) FROM sync_legacy_import_acceptances"))
                    assertEquals(
                        rows(database, "SELECT mutation_id FROM sync_legacy_import_entries ORDER BY ordinal", 1)
                            .map { it[0] },
                        rows(database, "SELECT mutation_id FROM sync_legacy_import_acceptances ORDER BY ordinal", 1)
                            .map { it[0] },
                    )
                    assertEquals(0L, count(database, "SELECT COUNT(*) FROM sync_outbox"))
                    // Full materialization: names, draft/saved flags, children,
                    // references, nullable fields and optional metadata survive.
                    assertEquals(
                        "R13b Rich",
                        rows(database, "SELECT name FROM exercises WHERE id = '$customRich'", 1).single()[0],
                    )
                    assertEquals(
                        listOf(listOf("$muscle", "primary")),
                        rows(database, "SELECT muscle_group_id, type FROM exercise_muscle_groups WHERE exercise_id = '$customRich'", 2),
                    )
                    assertEquals(
                        "",
                        rows(database, "SELECT name FROM routines WHERE id = '$routineBlank'", 1).single()[0],
                    )
                    assertEquals(
                        listOf(listOf("0"), listOf("1")),
                        rows(
                            database,
                            "SELECT is_draft FROM routines WHERE id IN ('$routineBlank', '$routineDraft') ORDER BY is_draft",
                            1,
                        ),
                    )
                    assertEquals(
                        "R13b Finished",
                        rows(database, "SELECT name FROM workouts WHERE id = '$workoutFin'", 1).single()[0],
                    )
                    assertEquals(
                        "R13b Active",
                        rows(database, "SELECT name FROM workouts WHERE id = '$workoutAct'", 1).single()[0],
                    )
                    database.openHelper.readableDatabase.query(
                        "SELECT repetitions, weight_kg, is_completed FROM workout_sets s" +
                            " JOIN workout_exercises e ON e.id = s.workout_exercise_id" +
                            " WHERE e.workout_id = ?",
                        arrayOf(workoutAct),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertTrue(cursor.isNull(0))
                        assertTrue(cursor.isNull(1))
                        assertEquals(0, cursor.getInt(2))
                        assertTrue(!cursor.moveToNext())
                    }
                    // Optional custom metadata/instructions/equipment quantity survive.
                    assertEquals(
                        listOf(listOf("beginner", "push", "compound")),
                        rows(
                            database,
                            "SELECT difficulty_level, force_type, mechanics" +
                                " FROM exercises WHERE id = '$customRich'",
                            3,
                        ),
                    )
                    assertEquals(
                        """["One","Two"]""",
                        rows(
                            database,
                            "SELECT instructions FROM exercises WHERE id = '$customRich'",
                            1,
                        ).single()[0],
                    )
                    assertEquals(
                        listOf(listOf(equip, "2")),
                        rows(
                            database,
                            "SELECT equipment_id, num_items FROM exercise_equipment" +
                                " WHERE exercise_id = '$customRich'",
                            2,
                        ),
                    )
                    assertEquals(
                        listOf(listOf(muscle, "primary")),
                        rows(
                            database,
                            "SELECT muscle_group_id, type FROM exercise_muscle_groups" +
                                " WHERE exercise_id = '$customRich'",
                            2,
                        ),
                    )
                    // Finished status/completed set/references materialize.
                    assertEquals(
                        listOf(listOf("finished")),
                        rows(database, "SELECT status FROM workouts WHERE id = '$workoutFin'", 1),
                    )
                    database.openHelper.readableDatabase.query(
                        "SELECT repetitions, weight_kg, is_completed, rest_seconds FROM workout_sets s" +
                            " JOIN workout_exercises e ON e.id = s.workout_exercise_id" +
                            " WHERE e.workout_id = ?",
                        arrayOf(workoutFin),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(8, cursor.getInt(0))
                        assertEquals(70.0, cursor.getDouble(1), 0.0)
                        assertEquals(1, cursor.getInt(2))
                        assertEquals(60, cursor.getInt(3))
                        assertTrue(!cursor.moveToNext())
                    }
                    // Nullable recorded rest stays NULL (never repaired or dropped).
                    database.openHelper.readableDatabase.query(
                        "SELECT rest_seconds FROM workout_sets s" +
                            " JOIN workout_exercises e ON e.id = s.workout_exercise_id" +
                            " WHERE e.workout_id = ?",
                        arrayOf(workoutAct),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertTrue(cursor.isNull(0))
                        assertTrue(!cursor.moveToNext())
                    }
                    // Explicit Active handle/vault match the grant account.
                    assertEquals(
                        grant.accountId,
                        rows(database, "SELECT account_id FROM sync_session_authority WHERE id = 1", 1)
                            .single()[0],
                    )
                    // Actual vault ACTIVE binding: a fresh HTTP-free facade
                    // recovers the same handle without any network use.
                    val recovery = AppSessionCoordinator(
                        database = database,
                        origin = origin,
                        vault = AndroidSessionVault.createForTesting(context, namespace),
                        authApi = UnusedAuth(),
                        transport = NoHttpTransport(),
                        runOwner = PushRunOwner(),
                        transitionBarrier = PassThroughTransitionBarrier(),
                        publicationBarrier = PassThroughPublicationBarrier(),
                        clockMillis = System::currentTimeMillis,
                        uuidProvider = { UUID.randomUUID().toString() },
                        legacyImportPolicy = LegacyImportPolicy(
                            replacementConsent = LegacyImportReplacementConsent { true },
                        ),
                    )
                    val recovered = recovery.recover()
                    assertTrue("vault must admit same handle, was $recovered", recovered is AppSessionState.Active)
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun sameAccountSessionControl_returnsCompleteGrantShapeWithoutLogging() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        runBlocking {
            val account = r10aMintAccount(transport, inputs.port)
            val grant = mintSameAccountGrant(transport, inputs.port, account.accessToken)
            assertEquals(account.accountId, grant.accountId)
            val rendered = grant.session.toString()
            assertTrue(rendered.isNotBlank())
            assertFalse(rendered.contains(account.accessToken))
            assertFalse(rendered.contains(grant.accessToken))
        }
    }

    @Test
    fun loseNextPushControl_acceptedForRelayAccount() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        runBlocking {
            val account = r10aMintAccount(transport, inputs.port)
            r10aLoseNextPush(transport, inputs.port, account)
        }
    }

    @Test
    fun blackBoxImportToActivation_realBackendSettlesOriginalSequence() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-activation-${UUID.randomUUID()}.db"
            try {
                val database = fileDb(context, name)
                try {
                    seedLocalGraph(database)
                    val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                    val operationId = "d13b3002-2222-4222-8222-222222222222"
                    val target = LegacyImportTarget(
                        origin = origin,
                        accountId = grant.accountId,
                        operationId = operationId,
                    )
                    val mutations = mutableListOf(
                        "70000003-d13b-4d13-8d13-d13bd13bd13b",
                        "70000004-d13b-4d13-8d13-d13bd13bd13b",
                    )
                    val retained = captureStore(database, mutations)
                        .capture(RoomSessionAuthority(database).read(), target)
                    assertTrue(retained is LegacyImportCaptureResult.Retained)
                    val importCount = (retained as LegacyImportCaptureResult.Retained).evidence.importCount
                    assertEquals(2L, importCount)

                    val coordinator = facade(database, context, namespace, origin, transport.transport)
                    coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                    val stageId = "d13b3003-3333-4333-8333-333333333333"
                    val state = coordinator.completeActivation(
                        RoomSessionAuthority(database).read(),
                        stageId,
                    )
                    assertTrue("expected Active after real settlement, was $state", state is AppSessionState.Active)
                    val active = state as AppSessionState.Active
                    active.session.checkCurrent()
                    assertEquals(
                        grant.accountId,
                        rows(database, "SELECT account_id FROM sync_session_authority WHERE id = 1", 1)
                            .single()[0],
                    )

                    // Original source/entries preserved; receipts cover the full
                    // sequence exactly; original requests fully acknowledged.
                    assertEquals(1L, journalCount(database))
                    assertEquals(
                        importCount,
                        count(database, "SELECT COUNT(*) FROM sync_legacy_import_acceptances"),
                    )
                    assertEquals(0L, count(database, "SELECT COUNT(*) FROM sync_outbox"))
                    assertEquals(
                        rows(
                            database,
                            "SELECT mutation_id FROM sync_legacy_import_entries ORDER BY ordinal",
                            1,
                        ).map { it[0] },
                        rows(
                            database,
                            "SELECT mutation_id FROM sync_legacy_import_acceptances ORDER BY ordinal",
                            1,
                        ).map { it[0] },
                    )
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun lostPushResponse_returnsPreparationRequiredThenExactDuplicateSettles() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-lost-${UUID.randomUUID()}.db"
            try {
                var database = fileDb(context, name)
                seedLocalGraph(database)
                val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                val operationId = "d13b3004-4444-4444-8444-444444444444"
                val target = LegacyImportTarget(
                    origin = origin,
                    accountId = grant.accountId,
                    operationId = operationId,
                )
                val mutations = mutableListOf(
                    "70000005-d13b-4d13-8d13-d13bd13bd13b",
                    "70000006-d13b-4d13-8d13-d13bd13bd13b",
                )
                val retained = captureStore(database, mutations)
                    .capture(RoomSessionAuthority(database).read(), target)
                assertTrue(retained is LegacyImportCaptureResult.Retained)
                val importCount =
                    (retained as LegacyImportCaptureResult.Retained).evidence.importCount
                assertEquals(2L, importCount)

                // Lose the next downstream push response AFTER the real commit:
                // typed transient stop returns Private(PREPARATION_REQUIRED)
                // with the frozen head retained for retry.
                var now = System.currentTimeMillis()
                r10aLoseNextPush(transport, inputs.port, R10aAccount(grant.accountId, grant.accessToken))
                val first = facade(
                    database, context, namespace, origin, transport.transport,
                    clockMillis = { now },
                )
                first.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                val stageId = "d13b3005-5555-4555-8555-555555555555"
                val uncertain = first.completeActivation(RoomSessionAuthority(database).read(), stageId)
                assertTrue("lost push must return Private, was $uncertain", uncertain is AppSessionState.Private)
                assertEquals(
                    AppSessionReason.PREPARATION_REQUIRED,
                    (uncertain as AppSessionState.Private).reason,
                )
                assertEquals(1L, journalCount(database))
                assertEquals(
                    2L,
                    count(database, "SELECT COUNT(*) FROM sync_legacy_import_entries"),
                )
                database.close()

                // Reopen with a fresh facade and reread authority; injected
                // clock advances past retry-after before the bounded retry.
                now += 300_000L
                database = reopenDb(context, name)
                try {
                    val recovered = captureStore(database, mutableListOf()) {
                        fail("reopen must not mint mutation IDs")
                    }.read(target)
                    assertNotNull(recovered)
                    assertEquals(importCount, recovered!!.importCount)
                    val second = facade(
                        database, context, namespace, origin, transport.transport,
                        clockMillis = { now },
                    )
                    val settled = second.completeActivation(
                        RoomSessionAuthority(database).read(),
                        stageId,
                    )
                    assertTrue("exact duplicate must settle, was $settled", settled is AppSessionState.Active)
                    assertEquals(1L, journalCount(database))
                    assertEquals(importCount, count(database, "SELECT COUNT(*) FROM sync_legacy_import_acceptances"))
                    assertEquals(0L, count(database, "SELECT COUNT(*) FROM sync_outbox"))
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun preCreatedCloudRoot_returnsRetainedCollisionWithoutOverwrite() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            // Pre-create the intended root on the SAME grant account through
            // actual production HTTP (ordinary sync path, separate mutation).
            val serverStore = r10aOpenStore(context, "r13b-collide-server", grant.accountId)
            val sharedCustomId: String
            try {
                val serverRepo = serverStore.repository()
                sharedCustomId = serverRepo.createCustomExercise(
                    "R13b Server Press", null, emptySet(), null, null, "",
                )
                val api = r10aNewApi(transport, R10aAccount(grant.accountId, grant.accessToken))
                r10aDeliverAndPublish(serverStore, grant.accountId, api, R10aTestLease())
            } finally {
                r10aCloseAndDelete(context, serverStore)
            }

            val namespace = vaultNamespace()
            val name = "r13b-collision-${UUID.randomUUID()}.db"
            try {
                val database = fileDb(context, name)
                try {
                    // Genuinely local graph reusing that same root UUID.
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics," +
                            " category, instructions, is_user_created, account_id, server_revision," +
                            " local_generation, sync_state, deleted_at_millis)" +
                            " VALUES ('$sharedCustomId', 'R13b Local Press', 'reps', NULL, NULL, NULL," +
                            " 'strength', '[\"Step\"]', 1, NULL, NULL, 1, 'local_only', NULL)",
                    )
                    val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                    val operationId = "d13b3006-6666-4666-8666-666666666666"
                    val target = LegacyImportTarget(
                        origin = origin,
                        accountId = grant.accountId,
                        operationId = operationId,
                    )
                    val retained = captureStore(
                        database,
                        mutableListOf("70000007-d13b-4d13-8d13-d13bd13bd13b"),
                    ).capture(RoomSessionAuthority(database).read(), target)
                    assertTrue(retained is LegacyImportCaptureResult.Retained)

                    val coordinator = facade(database, context, namespace, origin, transport.transport)
                    coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                    val outcome = runCatching {
                        coordinator.completeActivation(
                            RoomSessionAuthority(database).read(),
                            "d13b3007-7777-4777-8777-777777777777",
                        )
                    }
                    val state = outcome.getOrNull()
                    assertTrue(
                        "collision must never activate, was $state/${outcome.exceptionOrNull()}",
                        state is AppSessionState.Private || outcome.exceptionOrNull() != null,
                    )
                    // Retained permanent rejection: original outbox head and
                    // source evidence remain; cloud record never overwritten.
                    assertEquals(1L, journalCount(database))
                    assertTrue(count(database, "SELECT COUNT(*) FROM sync_outbox") > 0)
                    assertEquals(
                        "R13b Local Press",
                        rows(database, "SELECT name FROM exercises WHERE id = '$sharedCustomId'", 1)
                            .single()[0],
                    )
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun acceptanceInsertFault_rollsBackWholeAcknowledgment() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-rollback-${UUID.randomUUID()}.db"
            try {
                val database = fileDb(context, name)
                try {
                    seedLocalGraph(database)
                    val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                    val operationId = "d13b3008-8888-4888-8888-888888888888"
                    val target = LegacyImportTarget(
                        origin = origin,
                        accountId = grant.accountId,
                        operationId = operationId,
                    )
                    val mutations = mutableListOf(
                        "70000008-d13b-4d13-8d13-d13bd13bd13b",
                        "70000009-d13b-4d13-8d13-d13bd13bd13b",
                    )
                    assertTrue(
                        captureStore(database, mutations)
                            .capture(RoomSessionAuthority(database).read(), target)
                            is LegacyImportCaptureResult.Retained,
                    )
                    val coordinator = facade(database, context, namespace, origin, transport.transport)
                    coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                    // Preservation snapshots after the allowed committed phases:
                    // exact original request identity plus retained frozen bytes.
                    // Attempt/state columns may advance legitimately during the
                    // permitted push freeze, so they are excluded deliberately.
                    // Capture already created unobserved generation reservations:
                    // snapshot the complete canonical rows now and require exact
                    // equality after the fault (no applied UPSERT may appear).
                    val beforeCanonical = rows(
                        database,
                        "SELECT account_id, aggregate_type, aggregate_id, server_revision, canonical_state," +
                            " canonical_json, local_generation_watermark, cache_epoch" +
                            " FROM sync_canonical_roots ORDER BY account_id, aggregate_type, aggregate_id",
                        8,
                    )
                    val beforeOutbox = rows(
                        database,
                        "SELECT mutation_id, payload_json FROM sync_outbox ORDER BY sequence",
                        2,
                    )
                    val beforeEntries = rows(
                        database,
                        "SELECT mutation_id FROM sync_legacy_import_entries ORDER BY ordinal",
                        1,
                    )
                    // Fault the receipt insert during an actual successful push:
                    // the whole ack (canonical/metadata/head/receipt) must roll back.
                    database.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER r13b_abort_acceptance BEFORE INSERT" +
                            " ON sync_legacy_import_acceptances BEGIN" +
                            " SELECT RAISE(ABORT, 'r13b-qa-abort-acceptance'); END",
                    )
                    try {
                        val outcome = runCatching {
                            coordinator.completeActivation(
                                RoomSessionAuthority(database).read(),
                                "d13b3009-9999-4999-8999-999999999999",
                            )
                        }
                        val state = outcome.getOrNull()
                        assertTrue(
                            "faulted ack must fail closed, was $state/${outcome.exceptionOrNull()}",
                            state is AppSessionState.Private || outcome.exceptionOrNull() != null,
                        )
                    } finally {
                        database.openHelper.writableDatabase.execSQL(
                            "DROP TRIGGER IF EXISTS r13b_abort_acceptance",
                        )
                    }
                    assertEquals(0L, count(database, "SELECT COUNT(*) FROM sync_legacy_import_acceptances"))
                    assertEquals(
                        beforeOutbox,
                        rows(database, "SELECT mutation_id, payload_json FROM sync_outbox ORDER BY sequence", 2),
                    )
                    assertEquals(
                        beforeEntries,
                        rows(database, "SELECT mutation_id FROM sync_legacy_import_entries ORDER BY ordinal", 1),
                    )
                    assertEquals(
                        beforeCanonical,
                        rows(
                            database,
                            "SELECT account_id, aggregate_type, aggregate_id, server_revision, canonical_state," +
                                " canonical_json, local_generation_watermark, cache_epoch" +
                                " FROM sync_canonical_roots ORDER BY account_id, aggregate_type, aggregate_id",
                            8,
                        ),
                    )
                    assertEquals(
                        0L,
                        count(
                            database,
                            "SELECT COUNT(*) FROM sync_canonical_roots WHERE account_id = '${grant.accountId}'" +
                                " AND canonical_state = 'upsert'",
                        ),
                    )
                    assertEquals(1L, journalCount(database))
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun lineageProgressFault_rollsBackPublicationPreservingPreparedVault() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-linrollback-${UUID.randomUUID()}.db"
            try {
                val database = fileDb(context, name)
                try {
                    seedLocalGraph(database)
                    val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                    val operationId = "d13b3018-8888-4888-8888-888888888888"
                    val target = LegacyImportTarget(
                        origin = origin,
                        accountId = grant.accountId,
                        operationId = operationId,
                    )
                    val mutations = mutableListOf(
                        "70000018-d13b-4d13-8d13-d13bd13bd13b",
                        "70000019-d13b-4d13-8d13-d13bd13bd13b",
                    )
                    assertTrue(
                        captureStore(database, mutations)
                            .capture(RoomSessionAuthority(database).read(), target)
                            is LegacyImportCaptureResult.Retained,
                    )
                    val coordinator = facade(database, context, namespace, origin, transport.transport)
                    coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                    val beforeOutbox = rows(
                        database,
                        "SELECT mutation_id, payload_json FROM sync_outbox ORDER BY sequence",
                        2,
                    )
                    // Preservation baselines after the allowed committed phases.
                    val beforeJournal = rows(
                        database,
                        "SELECT id, evidence_version, service_origin, account_id, operation_id, phase," +
                            " import_count FROM sync_legacy_import",
                        7,
                    )
                    val beforeEntries = rows(
                        database,
                        "SELECT ordinal, aggregate_type, aggregate_id, mutation_id, sequence, payload_json" +
                            " FROM sync_legacy_import_entries ORDER BY ordinal",
                        6,
                    )
                    val beforeAuthority = rows(
                        database,
                        "SELECT revision, session_generation, phase FROM sync_session_authority WHERE id = 1",
                        3,
                    )
                    // AFTER-lineage corruption scoped to the ACTIVE promotion only,
                    // so consent/preparation updates are unaffected. Import acks run
                    // in their own earlier transactions: the accepted prefix stays
                    // durable while the combined publication/ACTIVE transaction
                    // rolls back around it.
                    database.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER r13b_corrupt_lineage AFTER UPDATE" +
                            " ON sync_legacy_import_progress WHEN NEW.room_phase = 'active' BEGIN" +
                            " DELETE FROM sync_legacy_import_acceptances;" +
                            " SELECT RAISE(ABORT, 'r13b-qa-abort-lineage'); END",
                    )
                    try {
                        val outcome = runCatching {
                            coordinator.completeActivation(
                                RoomSessionAuthority(database).read(),
                                "d13b3019-9999-4999-8999-999999999999",
                            )
                        }
                        val state = outcome.getOrNull()
                        assertTrue(
                            "lineage fault must fail closed, was $state/${outcome.exceptionOrNull()}",
                            state is AppSessionState.Private || outcome.exceptionOrNull() != null,
                        )
                        assertTrue(
                            "lineage fault must never activate, was $state",
                            state !is AppSessionState.Active,
                        )
                    } finally {
                        database.openHelper.writableDatabase.execSQL(
                            "DROP TRIGGER IF EXISTS r13b_corrupt_lineage",
                        )
                    }
                    // Accepted prefix survives the publication rollback: both
                    // original receipts durable and ordered, requests fully
                    // acknowledged, frozen journal/entries byte-identical.
                    assertEquals(
                        listOf(
                            "70000018-d13b-4d13-8d13-d13bd13bd13b",
                            "70000019-d13b-4d13-8d13-d13bd13bd13b",
                        ),
                        rows(
                            database,
                            "SELECT mutation_id FROM sync_legacy_import_acceptances ORDER BY ordinal",
                            1,
                        ).map { it[0] },
                    )
                    assertEquals(
                        beforeJournal,
                        rows(
                            database,
                            "SELECT id, evidence_version, service_origin, account_id, operation_id, phase," +
                                " import_count FROM sync_legacy_import",
                            7,
                        ),
                    )
                    assertEquals(
                        beforeEntries,
                        rows(
                            database,
                            "SELECT ordinal, aggregate_type, aggregate_id, mutation_id, sequence, payload_json" +
                                " FROM sync_legacy_import_entries ORDER BY ordinal",
                            6,
                        ),
                    )
                    assertEquals(0L, count(database, "SELECT COUNT(*) FROM sync_outbox"))
                    // Authority never advanced past its pre-publication lineage.
                    assertEquals(
                        beforeAuthority,
                        rows(
                            database,
                            "SELECT revision, session_generation, phase FROM sync_session_authority WHERE id = 1",
                            3,
                        ),
                    )
                    assertEquals(2, beforeOutbox.size)
                    assertEquals(0L, count(database, "SELECT COUNT(*) FROM sync_outbox"))
                } finally {
                    database.close()
                }

                // The actual vault stays PREPARED (not promoted): a fresh
                // HTTP-free recover admits Private, never Active.
                val reopened = reopenDb(context, name)
                try {
                    val recovery = AppSessionCoordinator(
                        database = reopened,
                        origin = ServiceOrigin.parse("https://localhost:${inputs.port}"),
                        vault = AndroidSessionVault.createForTesting(context, namespace),
                        authApi = UnusedAuth(),
                        transport = NoHttpTransport(),
                        runOwner = PushRunOwner(),
                        transitionBarrier = PassThroughTransitionBarrier(),
                        publicationBarrier = PassThroughPublicationBarrier(),
                        clockMillis = System::currentTimeMillis,
                        uuidProvider = { UUID.randomUUID().toString() },
                        legacyImportPolicy = LegacyImportPolicy(
                            replacementConsent = LegacyImportReplacementConsent { true },
                        ),
                    )
                    val outcome = runCatching { recovery.recover() }
                    val state = outcome.getOrNull()
                    assertTrue(
                        "unpromoted vault must not admit, was $state/${outcome.exceptionOrNull()}",
                        state !is AppSessionState.Active,
                    )
                } finally {
                    reopened.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun failedSnapshot_remainsPrivateWithEvidenceUntouched() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-snapfail-${UUID.randomUUID()}.db"
            try {
                val database = fileDb(context, name)
                try {
                    seedLocalGraph(database)
                    val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                    val operationId = "d13b3010-0000-4000-8000-000000000000"
                    val target = LegacyImportTarget(
                        origin = origin,
                        accountId = grant.accountId,
                        operationId = operationId,
                    )
                    val mutations = mutableListOf(
                        "70000010-d13b-4d13-8d13-d13bd13bd13b",
                        "70000011-d13b-4d13-8d13-d13bd13bd13b",
                    )
                    assertTrue(
                        captureStore(database, mutations)
                            .capture(RoomSessionAuthority(database).read(), target)
                            is LegacyImportCaptureResult.Retained,
                    )
                    // Snapshot pages fail; successful push/pull domain outcomes
                    // still delegate to the actual service.
                    val failing = SnapshotFailTransport(transport.transport)
                    val coordinator = facade(database, context, namespace, origin, failing)
                    coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                    val before = rows(database, "SELECT source_json, import_count FROM sync_legacy_import WHERE id = 1", 2)
                    val outcome = runCatching {
                        coordinator.completeActivation(
                            RoomSessionAuthority(database).read(),
                            "d13b3011-1111-4111-8111-111111111111",
                        )
                    }
                    val state = outcome.getOrNull()
                    assertTrue(
                        "failed snapshot must stay Private, was $state/${outcome.exceptionOrNull()}",
                        state is AppSessionState.Private || outcome.exceptionOrNull() != null,
                    )
                    assertTrue("snapshot must have been attempted", failing.snapshotCalls > 0)
                    assertEquals(
                        before,
                        rows(database, "SELECT source_json, import_count FROM sync_legacy_import WHERE id = 1", 2),
                    )
                    assertEquals(1L, journalCount(database))
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun promotionGapCloseFault_recoversExactPairWithoutHttp() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-gap-${UUID.randomUUID()}.db"
            try {
                var database = fileDb(context, name)
                seedLocalGraph(database)
                val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                val operationId = "d13b3012-2222-4222-8222-222222222222"
                val target = LegacyImportTarget(
                    origin = origin,
                    accountId = grant.accountId,
                    operationId = operationId,
                )
                val mutations = mutableListOf(
                    "70000012-d13b-4d13-8d13-d13bd13bd13b",
                    "70000013-d13b-4d13-8d13-d13bd13bd13b",
                )
                assertTrue(
                    captureStore(database, mutations)
                        .capture(RoomSessionAuthority(database).read(), target)
                        is LegacyImportCaptureResult.Retained,
                )
                val gapFired = AtomicBoolean(false)
                // Throw on lease close only AFTER the known Room combined
                // commit (authority ACTIVE), i.e. before vault promotion.
                val gapBarrier = object : PassThroughPublicationBarrier() {
                    override suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease =
                        Lease {
                            val phase = database.openHelper.readableDatabase.query(
                                "SELECT phase FROM sync_session_authority WHERE id = 1",
                            ).use { cursor ->
                                if (!cursor.moveToFirst()) null else cursor.getString(0)
                            }
                            if (phase == "active" && gapFired.compareAndSet(false, true)) {
                                throw IllegalStateException("r13b-qa-gap-close")
                            }
                        }
                }
                val coordinator = facade(
                    database, context, namespace, origin, transport.transport,
                    publicationBarrier = gapBarrier,
                )
                coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                val outcome = runCatching {
                    coordinator.completeActivation(
                        RoomSessionAuthority(database).read(),
                        "d13b3013-3333-4333-8333-333333333333",
                    )
                }
                val state = outcome.getOrNull()
                assertTrue(
                    "gap close fault must fail closed, was $state/${outcome.exceptionOrNull()}",
                    state is AppSessionState.Private || outcome.exceptionOrNull() != null,
                )
                assertTrue("gap must have fired", gapFired.get())
                assertEquals(
                    "active",
                    rows(database, "SELECT phase FROM sync_session_authority WHERE id = 1", 1)
                        .single()[0],
                )
                assertEquals(1L, journalCount(database))
                database.close()

                // Fresh facade recovers the exact completed pair with no HTTP.
                database = reopenDb(context, name)
                try {
                    val recovery = AppSessionCoordinator(
                        database = database,
                        origin = origin,
                        vault = AndroidSessionVault.createForTesting(context, namespace),
                        authApi = UnusedAuth(),
                        transport = NoHttpTransport(),
                        runOwner = PushRunOwner(),
                        transitionBarrier = PassThroughTransitionBarrier(),
                        publicationBarrier = PassThroughPublicationBarrier(),
                        clockMillis = System::currentTimeMillis,
                        uuidProvider = { UUID.randomUUID().toString() },
                        legacyImportPolicy = LegacyImportPolicy(
                            replacementConsent = LegacyImportReplacementConsent { true },
                        ),
                    )
                    val promoted = recovery.recover()
                    assertTrue("gap must promote without HTTP, was $promoted", promoted is AppSessionState.Active)
                    assertEquals(1L, journalCount(database))
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun corruptReceiptInGap_preventsPromotion() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-gapcorrupt-${UUID.randomUUID()}.db"
            try {
                var database = fileDb(context, name)
                seedLocalGraph(database)
                val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                val operationId = "d13b3014-4444-4444-8444-444444444444"
                val target = LegacyImportTarget(
                    origin = origin,
                    accountId = grant.accountId,
                    operationId = operationId,
                )
                val mutations = mutableListOf(
                    "70000014-d13b-4d13-8d13-d13bd13bd13b",
                    "70000015-d13b-4d13-8d13-d13bd13bd13b",
                )
                assertTrue(
                    captureStore(database, mutations)
                        .capture(RoomSessionAuthority(database).read(), target)
                        is LegacyImportCaptureResult.Retained,
                )
                val gapFired = AtomicBoolean(false)
                val gapBarrier = object : PassThroughPublicationBarrier() {
                    override suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease =
                        Lease {
                            val phase = database.openHelper.readableDatabase.query(
                                "SELECT phase FROM sync_session_authority WHERE id = 1",
                            ).use { cursor ->
                                if (!cursor.moveToFirst()) null else cursor.getString(0)
                            }
                            if (phase == "active" && gapFired.compareAndSet(false, true)) {
                                throw IllegalStateException("r13b-qa-gap-close")
                            }
                        }
                }
                val coordinator = facade(
                    database, context, namespace, origin, transport.transport,
                    publicationBarrier = gapBarrier,
                )
                coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant.session)
                runCatching {
                    coordinator.completeActivation(
                        RoomSessionAuthority(database).read(),
                        "d13b3015-5555-4555-8555-555555555555",
                    )
                }
                assertTrue(gapFired.get())
                // Corrupt the acceptance evidence, then recovery must refuse.
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO sync_legacy_import_acceptances (ordinal, mutation_id, batch_json)" +
                        " VALUES (99, '00000000-ffff-4fff-8fff-ffffffffffff', 'not-json')",
                )
                database.close()

                database = reopenDb(context, name)
                try {
                    val recovery = AppSessionCoordinator(
                        database = database,
                        origin = origin,
                        vault = AndroidSessionVault.createForTesting(context, namespace),
                        authApi = UnusedAuth(),
                        transport = NoHttpTransport(),
                        runOwner = PushRunOwner(),
                        transitionBarrier = PassThroughTransitionBarrier(),
                        publicationBarrier = PassThroughPublicationBarrier(),
                        clockMillis = System::currentTimeMillis,
                        uuidProvider = { UUID.randomUUID().toString() },
                        legacyImportPolicy = LegacyImportPolicy(
                            replacementConsent = LegacyImportReplacementConsent { true },
                        ),
                    )
                    val outcome = runCatching { recovery.recover() }
                    val state = outcome.getOrNull()
                    assertTrue(
                        "corrupt receipt must prevent promotion, was $state/${outcome.exceptionOrNull()}",
                        state is AppSessionState.Private || outcome.exceptionOrNull() != null,
                    )
                    assertTrue(
                        "promotion of corrupt evidence must never yield Active",
                        state !is AppSessionState.Active,
                    )
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }

    @Test
    fun freshSameAccountGrant_preservesOriginalMigrationOperation() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val grant = mintSessionGrant(transport, inputs.port)
            val namespace = vaultNamespace()
            val name = "r13b-reauth-${UUID.randomUUID()}.db"
            try {
                val database = fileDb(context, name)
                try {
                    seedLocalGraph(database)
                    val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
                    val operationId = "d13b3016-6666-4666-8666-666666666666"
                    val target = LegacyImportTarget(
                        origin = origin,
                        accountId = grant.accountId,
                        operationId = operationId,
                    )
                    val mutations = mutableListOf(
                        "70000016-d13b-4d13-8d13-d13bd13bd13b",
                        "70000017-d13b-4d13-8d13-d13bd13bd13b",
                    )
                    assertTrue(
                        captureStore(database, mutations)
                            .capture(RoomSessionAuthority(database).read(), target)
                            is LegacyImportCaptureResult.Retained,
                    )
                    val coordinator = facade(database, context, namespace, origin, transport.transport)
                    coordinator.acceptGrant(
                        RoomSessionAuthority(database).read(),
                        operationId,
                        grant.session,
                    )

                    // Fresh same-account grant advances session lineage only.
                    val fresh = mintSameAccountGrant(transport, inputs.port, grant.accessToken)
                    assertEquals(grant.accountId, fresh.accountId)
                    val newOperation = "d13b3017-7777-4777-8777-777777777777"
                    val state = coordinator.reauthenticateLegacyImport(
                        RoomSessionAuthority(database).read(),
                        newOperation,
                        fresh.session,
                    )
                    assertNotNull(state)
                    // Original migration operation retained; no second import journal.
                    assertEquals(
                        operationId,
                        rows(database, "SELECT operation_id FROM sync_legacy_import WHERE id = 1", 1)
                            .single()[0],
                    )
                    assertEquals(1L, journalCount(database))
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
                deleteNamespace(context, namespace)
            }
        }
    }
}
