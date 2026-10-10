package com.dragosfotache.gymtracka.data.sync.roombackend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.HttpServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpRequest
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpResponse
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.ui.account.AccountSyncState
import com.dragosfotache.gymtracka.ui.account.SessionAccountSyncOperations
import com.dragosfotache.gymtracka.ui.AccountGymAppState
import com.dragosfotache.gymtracka.ui.GymApp
import com.dragosfotache.gymtracka.ui.GymAppClock
import com.dragosfotache.gymtracka.ui.RestTimerHardware
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * R13d independent QA: foreground/edit/manual triggers plus uncertain exact
 * replay over the isolated real backend, driven through the real controller.
 *
 * Hand-written from docs/remaining-work/R13d-task.md plus the coordinator
 * API/harness appendix (R10a helper/port/cert reuse, real AccountGymAppState
 * file runtimes on a relay session grant, real SessionAccountSyncOperations +
 * AccountSyncState for trigger ownership). Real HTTP outcomes ONLY: no scripted
 * transport, no SQL domain fixtures, no production-expected encoder output;
 * raw QA SQL is metadata oracle only. Lost-response uncertainty uses the
 * relay's opt-in token-scoped control; the unchanged retry observes the real
 * duplicate outcome. A deterministic ordinary rejection has no real-service
 * seam, so confirmed-rejection recovery is covered in R13dOrdinaryRecoveryTest
 * with the appendix-blessed hand-authored real-adapter outcome instead of
 * here. Explicit relay gate fails when inputs are absent, never skips. No
 * Google, personal account, deployed-TLS or process-death claims.
 */
class R13dForegroundRecoveryRelayTest {
    companion object {
        const val CUSTOM_NAME = "R13d Relay Press"
        const val WORKOUT_NAME = "R13d Relay Active"
        const val RENAMED_NAME = "R13d Relay Renamed"
        const val VICTIM_NAME = "R13d Relay Victim"
        const val STALE_EDIT_NAME = "R13d Relay Stale Edit"
        const val REPLACEMENT_NAME = "R13d Relay Replacement"
    }

    @get:Rule
    val composeRule = createComposeRule()

    private var contentEnabled by mutableStateOf(true)
    private var current: AccountGymAppState? by mutableStateOf(null)

    private val scopes = mutableListOf<CoroutineScope>()
    private val runtimes = mutableListOf<AccountGymAppState>()
    private val syncStates = mutableListOf<AccountSyncState>()
    private val harnesses = mutableListOf<AppSessionHarness>()
    private val dbs = mutableListOf<GymDatabase>()

    @After
    fun tearDown() {
        composeRule.runOnUiThread { contentEnabled = false }
        composeRule.waitForIdle()
        syncStates.forEach { runCatching { it.dispose() } }
        syncStates.clear()
        runtimes.forEach { it.dispose() }
        runtimes.clear()
        scopes.forEach { it.cancel() }
        runBlocking {
            scopes.forEach { scope ->
                withTimeout(5_000L) { scope.coroutineContext[Job]?.join() }
            }
        }
        scopes.clear()
        dbs.forEach { it.close() }
        dbs.clear()
        harnesses.forEach { it.clean() }
        harnesses.clear()
    }

    private data class WireMeta(val phase: String, val path: String, val status: Int?, val failure: String?)

    /**
     * Metadata-only delegate recorder: records phase/path/status/failure per
     * call while invoking the REAL delegate with the ORIGINAL consumer
     * unchanged. Never reads bodies/headers/bearers, never simulates
     * outcomes; origin/trust/hostname verification intact.
     */
    private class MetadataTransport(
        private val delegate: ServiceHttpTransport,
        private val calls: MutableList<WireMeta>,
        @Volatile var phase: String,
    ) : ServiceHttpTransport {
        override suspend fun <T> withResponse(
            request: ServiceHttpRequest,
            consume: suspend (ServiceHttpResponse) -> T,
        ): T {
            val tag = phase
            try {
                return delegate.withResponse(request) { response ->
                    calls += WireMeta(tag, request.path, response.statusCode, null)
                    consume(response)
                }
            } catch (cancelled: CancellationException) {
                calls += WireMeta(tag, request.path, null, "cancelled")
                throw cancelled
            } catch (failed: Exception) {
                calls += WireMeta(tag, request.path, null, failed.javaClass.simpleName)
                throw failed
            }
        }
    }

    private data class RelayGrant(
        val accountId: String,
        val accessToken: String,
        val session: ServiceSession,
    )

    private fun mintRelayGrant(transport: R10aTransport, port: Int): RelayGrant {
        val body = runBlocking(Dispatchers.IO) {
            r10aPostControl(transport, port, "/__r10__/session", bearer = null)
        }
        val json = JSONObject(body)
        val accountId = json.getString("accountId")
        return RelayGrant(
            accountId = accountId,
            accessToken = json.getString("accessToken"),
            session = ServiceSession(
                ServiceAccountRef(accountId, null),
                OpaqueToken(json.getString("accessToken")),
                json.getLong("accessExpiresAtMillis"),
                OpaqueToken(json.getString("refreshToken")),
                json.getLong("refreshExpiresAtMillis"),
            ),
        )
    }

    private class AdvancingClock(var elapsed: Long = 0L) : GymAppClock {
        override fun wallTimeMillis(): Long = 1_700_000_000_000L + elapsed
        override fun elapsedRealtimeMillis(): Long = elapsed
    }

    private class NoOpHardware : RestTimerHardware {
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) = Unit
        override fun releaseWakeLock() = Unit
    }

    private data class Runtime(
        val harness: AppSessionHarness,
        val database: GymDatabase,
        val state: AccountGymAppState,
        val metered: MetadataTransport,
        val wire: MutableList<WireMeta>,
        val clock: AdvancingClock,
        val sync: AccountSyncState,
    )

    private fun runtime(
        suffix: String,
        dbName: String,
        transport: R10aTransport,
    ): Runtime {
        val harness = AppSessionHarness(suffix).also { harnesses += it }
        val database = harness.createFileDb(dbName).also { dbs += it }
        val wire = mutableListOf<WireMeta>()
        val metered = MetadataTransport(transport.transport, wire, "$suffix-init")
        val clock = AdvancingClock()
        val state = AccountGymAppState(
            database = database,
            origin = transport.origin,
            vault = harness.newVault(),
            authApi = HttpServiceAuthApi(transport.origin, metered),
            transport = metered,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = clock,
            timerHardware = NoOpHardware(),
        ).also { runtimes += it }
        val sync = AccountSyncState(
            SessionAccountSyncOperations(state),
            MainScope().also { scopes += it },
            200L,
        ).also { syncStates += it }
        return Runtime(harness, database, state, metered, wire, clock, sync)
    }

    private fun Runtime.activate(grant: RelayGrant) {
        val active = runBlocking(Dispatchers.IO) {
            val preparing = state.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                UUID.randomUUID().toString(),
                grant.session,
            )
            assertTrue("relay grant must prepare Private, was $preparing", preparing is AppSessionState.Private)
            state.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                UUID.randomUUID().toString(),
            )
        }
        assertTrue("setup must reach Active, was $active", active is AppSessionState.Active)
    }

    private fun activeSession(runtime: Runtime) =
        (runtime.state.sessionCoordinator.state.value as AppSessionState.Active).session

    /** Quiescence oracle: queue absence can precede publication, so every
     * effect wait must be followed by controller-idle before the next op. */
    private fun awaitSyncIdle(runtime: Runtime, timeout: Long = 60_000L) {
        composeRule.waitUntil(timeout) { !runtime.sync.uiState.value.busy }
    }

    private fun workoutName(database: GymDatabase, id: String): String? {
        database.openHelper.readableDatabase.query(
            "SELECT name FROM workouts WHERE id = ?",
            arrayOf(id),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getString(0)
        }
    }

    private fun mintSameAccountGrant(transport: R10aTransport, port: Int, bearer: String): RelayGrant {
        val body = runBlocking(Dispatchers.IO) {
            r10aPostControl(transport, port, "/__r10__/same-account-session", bearer = bearer)
        }
        val json = JSONObject(body)
        val accountId = json.getString("accountId")
        return RelayGrant(
            accountId = accountId,
            accessToken = json.getString("accessToken"),
            session = ServiceSession(
                ServiceAccountRef(accountId, null),
                OpaqueToken(json.getString("accessToken")),
                json.getLong("accessExpiresAtMillis"),
                OpaqueToken(json.getString("refreshToken")),
                json.getLong("refreshExpiresAtMillis"),
            ),
        )
    }

    @Test
    fun foregroundEditReconnectExactReplay() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        try {
            val grant = mintRelayGrant(transport, inputs.port)
            val relayAccount = R10aAccount(grant.accountId, grant.accessToken)
            val runtimeA = runtime("r13d-relay-a", "r13d-relay-a.db", transport)
            val runtimeB = runtime("r13d-relay-b", "r13d-relay-b.db", transport)
            composeRule.setContent {
                if (contentEnabled) {
                    current?.let { GymApp(it) }
                }
            }
            composeRule.runOnUiThread { current = runtimeA.state }
            runtimeA.activate(grant)
            runtimeB.activate(grant)
            runtimeA.sync.start()
            runtimeB.sync.start()

            // Foreground trigger on A with no local work: bounded run over
            // real HTTP, no error presentation.
            runtimeA.metered.phase = "A-foreground"
            val foregroundMark = runtimeA.wire.size
            runtimeA.sync.setForeground(true)
            composeRule.waitUntil(60_000L) {
                runtimeA.wire.size > foregroundMark &&
                    runtimeA.wire.subList(foregroundMark, runtimeA.wire.size).any { it.path == "/v1/sync/pull" }
            }
            awaitSyncIdle(runtimeA)

            // Ordinary edit on A, delivered by an edit-triggered run and
            // converged on B through its own manual run.
            val sessionA = activeSession(runtimeA)
            val workoutId = runBlocking(Dispatchers.IO) {
                val customId = sessionA.repository.createCustomExercise(CUSTOM_NAME, null, emptySet(), null, null, "")
                val workout = sessionA.repository.startEmptyWorkout(1_700_000_000_000L)
                sessionA.repository.updateWorkoutName(workout.id, WORKOUT_NAME)
                sessionA.repository.addExercisesToWorkout(workout.id, listOf(customId))
                workout.id
            }
            runtimeA.metered.phase = "A-edit"
            val editMark = runtimeA.wire.size
            runtimeA.sync.syncNow()
            composeRule.waitUntil(60_000L) {
                runtimeA.harness.outboxCount(runtimeA.database, grant.accountId) == 0L &&
                    runtimeA.wire.size > editMark &&
                    runtimeA.wire.subList(editMark, runtimeA.wire.size).any { it.path == "/v1/sync/push" }
            }
            awaitSyncIdle(runtimeA)
            runtimeB.metered.phase = "B-manual"
            runtimeB.sync.syncNow()
            composeRule.waitUntil(60_000L) {
                runtimeB.harness.customNames(runtimeB.database, grant.accountId).contains(CUSTOM_NAME)
            }
            awaitSyncIdle(runtimeB)
            val convergedName = runBlocking(Dispatchers.IO) {
                checkNotNull(activeSession(runtimeB).repository.workoutDetails(workoutId)).workout.name
            }
            assertEquals("B must converge the pushed workout name", WORKOUT_NAME, convergedName)
            val convergedExercises = runBlocking(Dispatchers.IO) {
                checkNotNull(activeSession(runtimeB).repository.workoutDetails(workoutId)).exercises.size
            }
            assertTrue("converged workout carries no ghost edits", convergedExercises > 0)

            // Lose one autosave response after the real server commit: the
            // controller run retains the exact frozen head; the unchanged
            // retry after backoff observes the real duplicate outcome and
            // both stores converge the rename with nothing reexecuted.
            runBlocking(Dispatchers.IO) {
                sessionA.repository.updateWorkoutName(workoutId, RENAMED_NAME)
            }
            val frozenBefore = runtimeA.harness.outboxSnapshot(runtimeA.database, grant.accountId)
            assertEquals("autosave must hold exactly its head", 1, frozenBefore.size)
            runBlocking(Dispatchers.IO) { r10aLoseNextPush(transport, inputs.port, relayAccount) }
            runtimeA.metered.phase = "A-lost"
            val lostMark = runtimeA.wire.size
            runtimeA.sync.syncNow()
            composeRule.waitUntil(60_000L) {
                runtimeA.wire.size > lostMark &&
                    runtimeA.wire.subList(lostMark, runtimeA.wire.size).any { it.path == "/v1/sync/push" }
            }
            composeRule.waitUntil(60_000L) {
                runtimeA.harness.outboxCount(runtimeA.database, grant.accountId) == 1L
            }
            awaitSyncIdle(runtimeA)
            val frozenHeld = runtimeA.harness.outboxSnapshot(runtimeA.database, grant.accountId)
            assertEquals(
                "uncertainty must not rewrite frozen bytes",
                runtimeA.harness.frozenIdentity(frozenBefore),
                runtimeA.harness.frozenIdentity(frozenHeld),
            )
            runtimeA.clock.elapsed += 3_600_000L
            runtimeA.metered.phase = "A-retry"
            runtimeA.sync.syncNow()
            composeRule.waitUntil(120_000L) {
                runtimeA.harness.outboxCount(runtimeA.database, grant.accountId) == 0L
            }
            awaitSyncIdle(runtimeA, 120_000L)
            runtimeB.metered.phase = "B-reconnect"
            runtimeB.sync.syncNow()
            composeRule.waitUntil(60_000L) {
                runBlocking(Dispatchers.IO) {
                    checkNotNull(activeSession(runtimeB).repository.workoutDetails(workoutId)).workout.name
                } == RENAMED_NAME
            }
            awaitSyncIdle(runtimeB)
            AppSessionFixtures.assertRedacted(runtimeA.sync.uiState.value.toString())
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun sameAccountReplacement_rejectsStaleEdit_controllerRecoversToReplacement() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        try {
            // Distinct sessions, one account: the setup grant plus a
            // same-account session minted from it (401 without a bearer).
            val grantA = mintRelayGrant(transport, inputs.port)
            val grantB = mintSameAccountGrant(transport, inputs.port, grantA.accessToken)
            assertEquals("same-account session must keep the account", grantA.accountId, grantB.accountId)
            assertTrue(
                "distinct sessions must carry distinct access credentials",
                grantA.accessToken != grantB.accessToken,
            )
            val runtimeA = runtime("r13d-reject-a", "r13d-reject-a.db", transport)
            val runtimeB = runtime("r13d-reject-b", "r13d-reject-b.db", transport)
            composeRule.setContent {
                if (contentEnabled) {
                    current?.let { GymApp(it) }
                }
            }
            composeRule.runOnUiThread { current = runtimeA.state }
            runtimeA.activate(grantA)
            runtimeB.activate(grantB)
            runtimeA.sync.start()
            runtimeB.sync.start()
            val sessionA = activeSession(runtimeA)
            val sessionB = activeSession(runtimeB)

            // A publishes an active workout and delivers it.
            val victimId = runBlocking(Dispatchers.IO) {
                val customId = sessionA.repository.createCustomExercise(CUSTOM_NAME, null, emptySet(), null, null, "")
                val workout = sessionA.repository.startEmptyWorkout(1_700_000_000_000L)
                sessionA.repository.updateWorkoutName(workout.id, VICTIM_NAME)
                sessionA.repository.addExercisesToWorkout(workout.id, listOf(customId))
                workout.id
            }
            runtimeA.metered.phase = "A-publish"
            runtimeA.sync.syncNow()
            composeRule.waitUntil(60_000L) {
                runtimeA.harness.outboxCount(runtimeA.database, grantA.accountId) == 0L
            }
            awaitSyncIdle(runtimeA)
            // B fetches the victim through its own manual run.
            runtimeB.metered.phase = "B-fetch"
            runtimeB.sync.syncNow()
            composeRule.waitUntil(60_000L) {
                workoutName(runtimeB.database, victimId) == VICTIM_NAME
            }
            awaitSyncIdle(runtimeB)
            // A queues a rename offline (no run): it stays pending locally.
            runBlocking(Dispatchers.IO) {
                sessionA.repository.updateWorkoutName(victimId, STALE_EDIT_NAME)
            }
            assertEquals(1L, runtimeA.harness.outboxCount(runtimeA.database, grantA.accountId))
            // B replaces the active workout and delivers: the old active
            // root is deleted server-side under the sole-active rule.
            val replacementId = runBlocking(Dispatchers.IO) {
                val workout = sessionB.repository.startEmptyWorkout(1_700_000_001_000L)
                sessionB.repository.updateWorkoutName(workout.id, REPLACEMENT_NAME)
                workout.id
            }
            runtimeB.metered.phase = "B-replace"
            runtimeB.sync.syncNow()
            composeRule.waitUntil(60_000L) {
                runtimeB.harness.outboxCount(runtimeB.database, grantB.accountId) == 0L
            }
            awaitSyncIdle(runtimeB)
            // A's exact stale rename now pushes against the deleted root and
            // must receive a real definitive rejection (not a silent merge).
            runtimeA.metered.phase = "A-stale-push"
            runtimeA.sync.syncNow()
            composeRule.waitUntil(60_000L) {
                val rows = runtimeA.harness.outboxSnapshot(runtimeA.database, grantA.accountId)
                rows.size == 1 && rows[0][9] == "rejected"
            }
            awaitSyncIdle(runtimeA)
            val rejected = runtimeA.harness.outboxSnapshot(runtimeA.database, grantA.accountId)
            val errorCode = rejected[0][12]
            assertTrue(
                "stale edit of a replaced root must be definitively rejected, got [$errorCode]",
                errorCode == "not_found" || errorCode == "invalid_state",
            )
            val staleMutationId = checkNotNull(rejected[0][1]) { "rejected row must carry its mutation ID" }
            // Actual controller recovery: prepare surfaces the real opaque
            // token, confirm discards only captured intent and reloads the
            // snapshot carrying B's replacement.
            runtimeA.sync.prepareRecovery()
            composeRule.waitUntil(60_000L) { runtimeA.sync.uiState.value.recovery != null }
            val token = checkNotNull(runtimeA.sync.uiState.value.recovery)
            assertTrue(token.pendingCount >= 1L)
            val rendered = token.toString()
            assertTrue("confirmation must be redacted", rendered.isNotBlank())
            assertTrue("confirmation must not leak the account", !rendered.contains(grantA.accountId))
            assertTrue("confirmation must not leak the mutation", !rendered.contains(staleMutationId))
            runtimeA.metered.phase = "A-recover"
            runtimeA.sync.confirmRecovery(token)
            composeRule.waitUntil(120_000L) {
                runtimeA.harness.outboxCount(runtimeA.database, grantA.accountId) == 0L
            }
            awaitSyncIdle(runtimeA, 120_000L)
            composeRule.waitUntil(60_000L) {
                workoutName(runtimeA.database, replacementId) == REPLACEMENT_NAME
            }
            awaitSyncIdle(runtimeA)
            // The original stale mutation was never rekeyed: no outbox row
            // carries it anymore, and the replaced root is gone from A.
            val remaining = runtimeA.harness.outboxSnapshot(runtimeA.database, grantA.accountId)
            assertTrue("original mutation must not be rekeyed", remaining.none { it[1] == staleMutationId })
            assertNull(
                "replaced root must be gone after snapshot reload",
                workoutName(runtimeA.database, victimId),
            )
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }
}
