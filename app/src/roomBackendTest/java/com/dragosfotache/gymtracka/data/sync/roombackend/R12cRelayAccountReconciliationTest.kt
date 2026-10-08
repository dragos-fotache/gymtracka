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
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationStop
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * R12c independent QA: actual scoped ACTIVE runtime/reconcile over the
 * isolated real backend (outcome 8 new-boundary gate).
 *
 * Hand-written from docs/remaining-work/R12c-task.md plus the coordinator
 * stated relay contract only: test-only POST /__r10__/session (via existing
 * r10aPostControl over the trusted transport) returns accountId,
 * accessToken, accessExpiresAtMillis, refreshToken, refreshExpiresAtMillis
 * from guarded synthetic setup followed by ACTUAL service refresh. The test
 * constructs ServiceSession(ServiceAccountRef(accountId,null),
 * OpaqueToken(access), accessExpires, OpaqueToken(refresh), refreshExpires)
 * without printing bodies/credentials, drives a REAL AccountGymAppState over
 * a fresh test-owned file Room DB plus actual namespaced vault (never the
 * seeded helper) with HttpServiceAuthApi(origin, transport), and exercises
 * acceptGrant, completeActivation with a fresh UUID stage, then
 * sessionCoordinator.reconcile. Live binding readiness uses the actual
 * scoped GymApp root through one setContent plus bounded waitUntil on the
 * live appState (normal Main scheduling proves real construction). Store A
 * takes real intended domain input through its session repository, pushes
 * through the real facade, and store B — activated on the same synthetic
 * grant into independent fresh Room/vault — converges the SAME IDs/names/
 * references through actual snapshot/pull/publication, ending quiescent.
 * Real HTTP outcomes ONLY: no SQL domain fixtures, no production-expected
 * encoder output, no fake leases, no old scripted pipeline. Raw QA SQL is
 * metadata oracle only; every reconcile epoch input is the published fresh
 * facade read. Explicit relay gate fails when inputs are absent, never
 * skips. No Google, personal account, deployed-TLS or process-death claims.
 */
class R12cRelayAccountReconciliationTest {
    companion object {
        const val CUSTOM_NAME = "R12c Relay Press"
        const val ROUTINE_NAME = "R12c Relay Routine"
    }

    @get:Rule
    val composeRule = createComposeRule()

    private var contentEnabled by mutableStateOf(true)
    private var current: AccountGymAppState? by mutableStateOf(null)

    private val scopes = mutableListOf<CoroutineScope>()
    private val runtimes = mutableListOf<AccountGymAppState>()
    private val harnesses = mutableListOf<AppSessionHarness>()
    private val dbs = mutableListOf<GymDatabase>()

    @After
    fun tearDown() {
        composeRule.runOnUiThread { contentEnabled = false }
        composeRule.waitForIdle()
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
     * Metadata-only delegate recorder: records phase/path/status/failure
     * class per call while invoking the REAL delegate with the ORIGINAL
     * consumer unchanged. Never reads bodies/headers/bearers, never
     * simulates outcomes; origin/trust/hostname verification intact.
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

    private data class RelayGrant(val accountId: String, val session: ServiceSession)

    private fun mintRelayGrant(transport: R10aTransport, port: Int): RelayGrant {
        val body = runBlocking(Dispatchers.IO) {
            r10aPostControl(transport, port, "/__r10__/session", bearer = null)
        }
        val json = JSONObject(body)
        val accountId = json.getString("accountId")
        val grant = ServiceSession(
            ServiceAccountRef(accountId, null),
            OpaqueToken(json.getString("accessToken")),
            json.getLong("accessExpiresAtMillis"),
            OpaqueToken(json.getString("refreshToken")),
            json.getLong("refreshExpiresAtMillis"),
        )
        return RelayGrant(accountId, grant)
    }

    private fun runtime(
        suffix: String,
        dbName: String,
        transport: R10aTransport,
        metered: ServiceHttpTransport,
    ): Triple<AppSessionHarness, GymDatabase, AccountGymAppState> {
        val harness = AppSessionHarness(suffix).also { harnesses += it }
        val database = harness.createFileDb(dbName).also { dbs += it }
        val state = AccountGymAppState(
            database = database,
            origin = transport.origin,
            vault = harness.newVault(),
            authApi = HttpServiceAuthApi(transport.origin, metered),
            transport = metered,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = FixedClock(),
            timerHardware = NoOpHardware(),
        ).also { runtimes += it }
        return Triple(harness, database, state)
    }

    @Test
    fun relayDomainInputConvergesAcrossIndependentStores() {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        try {
            composeRule.setContent {
                if (contentEnabled) {
                    current?.let { GymApp(it) }
                }
            }
            val relay = mintRelayGrant(transport, inputs.port)
            val wire = mutableListOf<WireMeta>()
            val metered = MetadataTransport(transport.transport, wire, "A-accept")

            val (harnessA, databaseA, runtimeA) = runtime("r12c-relay-a", "r12c-relay-a.db", transport, metered)
            val activeA = runBlocking(Dispatchers.IO) {
                val preparing = runtimeA.sessionCoordinator.acceptGrant(
                    RoomSessionAuthority(databaseA).read(),
                    UUID.randomUUID().toString(),
                    relay.session,
                )
                assertTrue("relay grant must prepare Private, was $preparing", preparing is AppSessionState.Private)
                metered.phase = "A-complete"
                runtimeA.sessionCoordinator.completeActivation(
                    RoomSessionAuthority(databaseA).read(),
                    UUID.randomUUID().toString(),
                )
            }
            if (activeA !is AppSessionState.Active) {
                fail(
                    "A complete stopped ${activeA.javaClass.simpleName}; " +
                        "cache=${harnessA.cacheEpoch(databaseA, relay.accountId)} " +
                        "materialized=${harnessA.materializedEpoch(databaseA, relay.accountId)} " +
                        "dirty=${harnessA.projectionDirty(databaseA, relay.accountId)} " +
                        "stages=${databaseA.r10aStageCount(relay.accountId)} " +
                        "wire=" + wire.map { "${it.phase}:${it.path}:${it.status}:${it.failure}" },
                )
            }
            val sessionA = (activeA as AppSessionState.Active).session
            assertEquals(relay.accountId, sessionA.identity.binding.accountId)
            composeRule.runOnUiThread { current = runtimeA }
            composeRule.waitUntil(10_000L) { runtimeA.appState != null }

            val customId = runBlocking(Dispatchers.IO) {
                sessionA.repository.createCustomExercise(CUSTOM_NAME, null, emptySet(), null, null, "")
            }
            val routineId = runBlocking(Dispatchers.IO) {
                val draft = sessionA.repository.createRoutineDraft()
                sessionA.repository.updateRoutineName(draft.id, ROUTINE_NAME)
                sessionA.repository.addExercisesToRoutine(draft.id, listOf(customId))
                draft.id
            }
            metered.phase = "A-reconcile"
            val epochA = runBlocking(Dispatchers.IO) { runtimeA.sessionCoordinator.observeCacheEpoch(sessionA) }
            val reconciledA = runBlocking(Dispatchers.IO) { runtimeA.sessionCoordinator.reconcile(sessionA, epochA) }
            assertEquals(AppReconciliationStop.TAIL, reconciledA.stop)
            assertTrue("result display must be fixed/redacted", reconciledA.toString().isNotBlank())
            assertEquals("A outbox must be quiescent", 0L, harnessA.outboxCount(databaseA, relay.accountId))
            assertEquals(
                "A paired materialization must match",
                reconciledA.cacheEpoch,
                harnessA.materializedEpoch(databaseA, relay.accountId),
            )
            for (secret in listOf(relay.accountId)) {
                assertTrue("result must not echo account", !reconciledA.toString().contains(secret))
            }

            val (harnessB, databaseB, runtimeB) = runtime("r12c-relay-b", "r12c-relay-b.db", transport, metered)
            metered.phase = "B-accept"
            val activeB = runBlocking(Dispatchers.IO) {
                val preparing = runtimeB.sessionCoordinator.acceptGrant(
                    RoomSessionAuthority(databaseB).read(),
                    UUID.randomUUID().toString(),
                    relay.session,
                )
                assertTrue("same grant must prepare second store, was $preparing", preparing is AppSessionState.Private)
                metered.phase = "B-complete"
                runtimeB.sessionCoordinator.completeActivation(
                    RoomSessionAuthority(databaseB).read(),
                    UUID.randomUUID().toString(),
                )
            }
            if (activeB !is AppSessionState.Active) {
                fail(
                    "B complete stopped ${activeB.javaClass.simpleName}; " +
                        "cache=${harnessB.cacheEpoch(databaseB, relay.accountId)} " +
                        "materialized=${harnessB.materializedEpoch(databaseB, relay.accountId)} " +
                        "dirty=${harnessB.projectionDirty(databaseB, relay.accountId)} " +
                        "stages=${databaseB.r10aStageCount(relay.accountId)} " +
                        "wire=" + wire.map { "${it.phase}:${it.path}:${it.status}:${it.failure}" },
                )
            }
            val sessionB = (activeB as AppSessionState.Active).session
            composeRule.runOnUiThread { current = runtimeB }
            composeRule.waitUntil(10_000L) { runtimeB.appState != null }

            metered.phase = "B-reconcile"
            val epochB = runBlocking(Dispatchers.IO) { runtimeB.sessionCoordinator.observeCacheEpoch(sessionB) }
            val reconciledB = runBlocking(Dispatchers.IO) { runtimeB.sessionCoordinator.reconcile(sessionB, epochB) }
            assertEquals(AppReconciliationStop.TAIL, reconciledB.stop)
            val seenCustomB = runBlocking(Dispatchers.IO) {
                checkNotNull(sessionB.repository.exerciseDetails(customId)) {
                    "B must converge the R12c custom row through actual service outcomes"
                }
            }
            assertEquals(customId, seenCustomB.exercise.id)
            assertEquals(CUSTOM_NAME, seenCustomB.exercise.name)
            val seenB = runBlocking(Dispatchers.IO) {
                checkNotNull(sessionB.repository.routineDetails(routineId)) {
                    "B must converge the R12c routine through actual service outcomes"
                }
            }
            assertEquals(routineId, seenB.routine.id)
            assertEquals(ROUTINE_NAME, seenB.routine.name)
            assertTrue(
                "B must see the R12c custom association",
                seenB.exercises.any { it.routineExercise.exerciseId == customId },
            )
            assertEquals("B outbox must be quiescent", 0L, harnessB.outboxCount(databaseB, relay.accountId))
            assertEquals(
                "B paired materialization must match",
                reconciledB.cacheEpoch,
                harnessB.materializedEpoch(databaseB, relay.accountId),
            )
            composeRule.runOnUiThread { current = null }
            composeRule.waitForIdle()
        } finally {
            transport.client.connectionPool.evictAll()
        }
    }

    private class FixedClock : GymAppClock {
        override fun wallTimeMillis() = 1_700_000_000_000L
        override fun elapsedRealtimeMillis() = 0L
    }

    private class NoOpHardware : RestTimerHardware {
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) = Unit
        override fun releaseWakeLock() = Unit
    }
}
