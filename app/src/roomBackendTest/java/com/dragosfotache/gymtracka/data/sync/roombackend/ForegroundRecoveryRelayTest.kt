package com.dragosfotache.gymtracka.data.sync.roombackend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityProvider
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.api.ServerCheckResult
import com.dragosfotache.gymtracka.data.sync.api.ServerChecker
import com.dragosfotache.gymtracka.data.sync.http.HttpServerChecker
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationStop
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInCoordinator
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInIntent
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
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
import com.dragosfotache.gymtracka.ui.account.AccountImportConsent
import com.dragosfotache.gymtracka.ui.account.AccountStartupApp
import com.dragosfotache.gymtracka.ui.account.AccountStartupError
import com.dragosfotache.gymtracka.ui.account.AccountStartupOperations
import com.dragosfotache.gymtracka.ui.account.AccountStartupState
import com.dragosfotache.gymtracka.ui.account.SessionAccountStartupOperations
import com.dragosfotache.gymtracka.ui.account.accountRetryText
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionState
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionStatus
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
 * Checks foreground delivery and ordinary recovery through independent Room/vault stores and the
 * isolated service.
 */
class ForegroundRecoveryRelayTest {
    companion object {
        const val CUSTOM_NAME = "foreground Relay Press"
        const val WORKOUT_NAME = "foreground Relay Active"
        const val RENAMED_NAME = "foreground Relay Renamed"
        const val VICTIM_NAME = "foreground Relay Victim"
        const val STALE_EDIT_NAME = "foreground Relay Stale Edit"
        const val REPLACEMENT_NAME = "foreground Relay Replacement"
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
    private val startups = mutableListOf<AccountStartupState>()
    private val signIns = mutableListOf<ForegroundSignInCoordinator>()

    @After
    fun tearDown() {
        composeRule.runOnUiThread { contentEnabled = false }
        composeRule.waitForIdle()
        startups.forEach { it.dispose() }
        signIns.forEach { it.close() }
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
     * unchanged. Never reads bodies/headers/bearers or synthesizes successful
     * outcomes; failPush injects a pre-send transient failure. Origin/trust/hostname
     * verification remain intact for delegated requests.
     */
    private class MetadataTransport(
        private val delegate: ServiceHttpTransport,
        private val calls: MutableList<WireMeta>,
        @Volatile var phase: String,
    ) : ServiceHttpTransport {
        @Volatile var failPush = false
        @Volatile var pushFailure = SyncApiException.Transient(connectionFailure = true)

        override suspend fun <T> withResponse(
            request: ServiceHttpRequest,
            consume: suspend (ServiceHttpResponse) -> T,
        ): T {
            val tag = phase
            try {
                if (failPush && request.path == "/v1/sync/push") {
                    throw pushFailure
                }
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

    private fun mintRelayGrant(transport: RoomBackendTransport, port: Int): RelayGrant {
        val body = runBlocking(Dispatchers.IO) {
            roomBackendPostControl(transport, port, "/__test__/session", bearer = null)
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

    private class AdvancingClock(@Volatile var elapsed: Long = 0L) : GymAppClock {
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
        transport: RoomBackendTransport,
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
        assertTrue("setup must reach Active, was $active; wire=$wire", active is AppSessionState.Active)
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

    private fun mintSameAccountGrant(transport: RoomBackendTransport, port: Int, bearer: String): RelayGrant {
        val body = runBlocking(Dispatchers.IO) {
            roomBackendPostControl(transport, port, "/__test__/same-account-session", bearer = bearer)
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

    private fun Runtime.reconcile() = runBlocking(Dispatchers.IO) {
        val session = activeSession(this@reconcile)
        state.sessionCoordinator.reconcile(session, state.sessionCoordinator.observeCacheEpoch(session))
    }

    /** Production failure handling builds the durable delay; no SQL edits seed retry metadata. */
    private fun Runtime.freezeOfflineUpload(accountId: String, attempts: Int): List<List<String?>> {
        runBlocking(Dispatchers.IO) {
            activeSession(this@freezeOfflineUpload).repository.createCustomExercise(
                "Reconnect backoff press", null, emptySet(), null, null, "",
            )
        }
        metered.failPush = true
        repeat(attempts) { index ->
            val result = reconcile()
            assertEquals(AppReconciliationStop.PUSH_STOPPED, result.stop)
            assertTrue(result.delivery?.push?.stop is PushDeliveryStop.Deferred)
            val head = harness.outboxSnapshot(database, accountId).single()
            assertEquals("frozen", head[9])
            assertEquals((index + 1).toString(), head[10])
            if (index < attempts - 1) {
                clock.elapsed += checkNotNull(head[11]).toLong() - clock.wallTimeMillis()
            }
        }
        metered.failPush = false
        return harness.outboxSnapshot(database, accountId)
    }

    @Test
    fun restoredConnection_retriesImmediatelyWithoutWaitingForDeadline() = reconnectScenario()

    @Test
    fun restoredConnection_keepsExplicitServiceDelay() = reconnectScenario(serviceDelay = true)

    @Test
    fun initialOnlineCheck_doesNotResetConnectionBackoff() = reconnectScenario(observedOffline = false)

    @Test
    fun restoredConnection_finishesPreparedSignInImmediately() = reconnectScenario(privateActivation = true)

    private fun reconnectScenario(
        serviceDelay: Boolean = false,
        observedOffline: Boolean = true,
        privateActivation: Boolean = false,
    ) {
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
        try {
            val grant = mintRelayGrant(transport, inputs.port)
            val runtime = runtime("reconnect-edge", "reconnect-edge.db", transport)
            var startup by mutableStateOf<AccountStartupState?>(null)
            var online = !observedOffline
            val realChecker = HttpServerChecker(transport.transport)
            val connection = ServerConnectionState(
                ServerChecker { if (online) realChecker.check() else ServerCheckResult.UNREACHABLE },
                MainScope().also { scopes += it },
            )
            val consent = AccountImportConsent()
            composeRule.setContent {
                if (contentEnabled) {
                    val controller = startup
                    if (controller == null) GymApp(runtime.state)
                    else AccountStartupApp(runtime.state, controller, consent, runtime.sync, connection)
                }
            }
            runtime.activate(grant)
            if (serviceDelay) runtime.metered.pushFailure = SyncApiException.Transient(300_000L)
            val frozen = runtime.freezeOfflineUpload(grant.accountId, attempts = 10)
            val retryAt = checkNotNull(frozen.single()[11]).toLong()
            val clockBefore = runtime.clock.wallTimeMillis()
            assertEquals(300_000L, retryAt - clockBefore)
            val cause = runBlocking { runtime.database.syncDao().getOutboxHead(grant.accountId)?.retryCause }
            assertEquals(if (serviceDelay) "unknown" else "connection", cause)

            var providerCalls = 0
            val identity = object : GoogleIdentityProvider {
                override suspend fun credentialFor(nonce: String): OpaqueToken {
                    providerCalls++
                    return OpaqueToken("reconnect-edge-google-proof")
                }
                override suspend fun clearCredentialState() = Unit
            }
            val auth = object : ServiceAuthApi by HttpServiceAuthApi(transport.origin, transport.transport) {
                override suspend fun challenge(purpose: GoogleChallengePurpose) = ServiceChallenge(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), runtime.clock.wallTimeMillis() + 60_000L,
                )
                override suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession =
                    mintSameAccountGrant(transport, inputs.port, grant.accessToken).session
            }
            val signIn = ForegroundSignInCoordinator(
                transport.origin, identity, runtime.state.sessionCoordinator, auth,
                clockMillis = { runtime.clock.wallTimeMillis() },
            ).also { signIns += it }
            val controller = AccountStartupState(
                SessionAccountStartupOperations(runtime.state, signIn), true, MainScope().also { scopes += it },
            ).also { startups += it }
            composeRule.runOnIdle { startup = controller }
            composeRule.waitUntil(30_000L) {
                !controller.uiState.value.busy && !runtime.sync.uiState.value.busy &&
                    runtime.sync.uiState.value.retryAtMillis == retryAt
            }
            composeRule.onNodeWithTag("nav-settings").performClick()
            composeRule.waitUntil(10_000L) {
                connection.status.value == if (observedOffline) ServerConnectionStatus.UNREACHABLE else ServerConnectionStatus.CONNECTED
            }
            assertEquals(0L, connection.reconnections.value)
            if (privateActivation) {
                composeRule.runOnIdle { controller.signIn() }
                composeRule.waitUntil(30_000L) {
                    !controller.uiState.value.busy && controller.uiState.value.error == AccountStartupError.PREPARATION_PAUSED
                }
                composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
                composeRule.waitUntil(10_000L) { connection.status.value == ServerConnectionStatus.UNREACHABLE }
            }
            val mark = runtime.wire.size
            val requests = runtime.sync.uiState.value.connectionCheckRequests
            composeRule.runOnIdle { online = true; connection.recheck() }
            composeRule.waitUntil(10_000L) {
                if (observedOffline) connection.reconnections.value == 1L
                else connection.status.value == ServerConnectionStatus.CONNECTED
            }
            if (serviceDelay || !observedOffline) {
                if (observedOffline) {
                    composeRule.waitUntil(20_000L) { runtime.sync.uiState.value.connectionCheckRequests > requests }
                    awaitSyncIdle(runtime)
                }
                assertEquals(frozen, runtime.harness.outboxSnapshot(runtime.database, grant.accountId))
                assertEquals(mark, runtime.wire.size)
                assertEquals(clockBefore, runtime.clock.wallTimeMillis())
                composeRule.runOnIdle { connection.recheck() }
                composeRule.waitUntil(10_000L) { connection.status.value == ServerConnectionStatus.CONNECTED }
                assertEquals(if (observedOffline) 1L else 0L, connection.reconnections.value)
                assertEquals(mark, runtime.wire.size)
                runtime.clock.elapsed += retryAt - runtime.clock.wallTimeMillis()
            }
            composeRule.waitUntil(60_000L) {
                runtime.state.sessionCoordinator.state.value is AppSessionState.Active &&
                    runtime.harness.outboxCount(runtime.database, grant.accountId) == 0L &&
                    !controller.uiState.value.busy && !runtime.sync.uiState.value.busy
            }
            if (!serviceDelay && observedOffline) assertEquals(clockBefore, runtime.clock.wallTimeMillis())
            assertEquals(if (privateActivation) 1 else 0, providerCalls)
            assertEquals(1, runtime.wire.drop(mark).count { it.path == "/v1/sync/push" && it.status == 200 })
            assertEquals(if (observedOffline) 1L else 0L, connection.reconnections.value)
            val reader = runtime("reconnect-edge-reader", "reconnect-edge-reader.db", transport)
            reader.activate(mintSameAccountGrant(transport, inputs.port, grant.accessToken))
            assertEquals(1, reader.harness.customNames(reader.database, grant.accountId).count { it == "Reconnect backoff press" })
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun reconnectDuringBackoff_preservesOfflineAccess_thenDeliversWithoutReauthentication() {
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
        try {
            val grant = mintRelayGrant(transport, inputs.port)
            val runtime = runtime("reconnect-control", "reconnect-control.db", transport)
            composeRule.setContent { if (contentEnabled) GymApp(runtime.state) }
            runtime.activate(grant)
            val session = activeSession(runtime)
            val frozen = runtime.freezeOfflineUpload(grant.accountId, attempts = 1)
            val retryAt = checkNotNull(frozen.single()[11]).toLong()
            assertEquals(ServerCheckResult.CONNECTED, runBlocking { HttpServerChecker(transport.transport).check() })
            val mark = runtime.wire.size
            val held = runtime.reconcile()
            assertTrue(held.delivery?.push?.stop is PushDeliveryStop.Deferred)
            assertEquals("early retry must not send HTTP", mark, runtime.wire.size)
            assertTrue(runtime.state.sessionCoordinator.state.value is AppSessionState.Active)
            assertEquals(session, activeSession(runtime))
            assertEquals(frozen, runtime.harness.outboxSnapshot(runtime.database, grant.accountId))
            runtime.clock.elapsed += retryAt - runtime.clock.wallTimeMillis()
            assertEquals(AppReconciliationStop.TAIL, runtime.reconcile().stop)
            assertEquals(0L, runtime.harness.outboxCount(runtime.database, grant.accountId))
            assertTrue(runtime.harness.customNames(runtime.database, grant.accountId).contains("Reconnect backoff press"))
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun foregroundBackoff_retriesAutomaticallyWithoutAuthentication() {
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
        try {
            val grant = mintRelayGrant(transport, inputs.port)
            val runtime = runtime("reconnect-auto", "reconnect-auto.db", transport)
            var controller by mutableStateOf<AccountStartupState?>(null)
            val consent = AccountImportConsent()
            composeRule.setContent {
                if (contentEnabled) {
                    val startup = controller
                    if (startup == null) GymApp(runtime.state)
                    else AccountStartupApp(runtime.state, startup, consent, runtime.sync)
                }
            }
            runtime.activate(grant)
            val frozen = runtime.freezeOfflineUpload(grant.accountId, attempts = 10)
            val retryAt = checkNotNull(frozen.single()[11]).toLong()
            val facade = runtime.state.sessionCoordinator
            val operations = object : AccountStartupOperations {
                override val state = facade.state
                override suspend fun recover() = facade.recover()
                override suspend fun signIn(
                    intent: ForegroundSignInIntent,
                    expected: RecoveryRoomSnapshot,
                    operationId: String,
                ): AppSessionState = throw AssertionError("reconnect must not authenticate")
                override suspend fun completeActivation(
                    expected: RecoveryRoomSnapshot,
                    stageId: String,
                ) = facade.completeActivation(expected, stageId)
                override suspend fun observeLegacyImport() = facade.observeLegacyImport()
                override fun observeSyncStatus(session: AppAccountSession) =
                    facade.observeSyncStatus(session)
            }
            val startup = AccountStartupState(operations, true, MainScope().also { scopes += it })
                .also { startups += it }
            composeRule.runOnIdle { controller = startup }
            composeRule.waitUntil(30_000L) { !runtime.sync.uiState.value.busy && runtime.sync.uiState.value.retryAtMillis == retryAt }
            composeRule.onNodeWithTag("nav-settings").performClick()
            composeRule.onNodeWithText(accountRetryText(300L)).assertIsDisplayed()
            assertEquals(frozen, runtime.harness.outboxSnapshot(runtime.database, grant.accountId))
            assertTrue(facade.state.value is AppSessionState.Active)
            val mark = runtime.wire.size
            runtime.clock.elapsed += retryAt - runtime.clock.wallTimeMillis()
            composeRule.waitUntil(60_000L) {
                runtime.sync.uiState.value.stop == AppReconciliationStop.TAIL && !runtime.sync.uiState.value.busy
            }
            assertTrue(runtime.wire.size > mark)
            assertNull(runtime.sync.uiState.value.retryAtMillis)
            assertEquals(0L, runtime.harness.outboxCount(runtime.database, grant.accountId))
            composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun reauthenticationDuringFiveMinuteBackoff_showsWait_thenFinishesAutomatically() {
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
        try {
            val grant = mintRelayGrant(transport, inputs.port)
            val runtime = runtime("reconnect-reauth", "reconnect-reauth.db", transport)
            var startup by mutableStateOf<AccountStartupState?>(null)
            val consent = AccountImportConsent()
            composeRule.setContent {
                if (contentEnabled) {
                    val controller = startup
                    if (controller == null) GymApp(runtime.state)
                    else AccountStartupApp(runtime.state, controller, consent)
                }
            }
            runtime.activate(grant)
            val frozen = runtime.freezeOfflineUpload(grant.accountId, attempts = 10)
            val retryAt = checkNotNull(frozen.single()[11]).toLong()
            assertEquals(300_000L, retryAt - runtime.clock.wallTimeMillis())
            assertEquals(ServerCheckResult.CONNECTED, runBlocking { HttpServerChecker(transport.transport).check() })

            // Google UI is injected, but each returned session is freshly issued by the real
            // isolated service. The production foreground handoff and activation remain intact.
            var providerCalls = 0
            var exchanges = 0
            val identity = object : GoogleIdentityProvider {
                override suspend fun credentialFor(nonce: String): OpaqueToken {
                    providerCalls++
                    return OpaqueToken("reconnect-test-google-proof")
                }
                override suspend fun clearCredentialState() = Unit
            }
            val auth = object : ServiceAuthApi by HttpServiceAuthApi(transport.origin, transport.transport) {
                override suspend fun challenge(purpose: GoogleChallengePurpose) = ServiceChallenge(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), runtime.clock.wallTimeMillis() + 60_000L,
                )
                override suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession {
                    exchanges++
                    return mintSameAccountGrant(transport, inputs.port, grant.accessToken).session
                }
            }
            val signIn = ForegroundSignInCoordinator(
                origin = transport.origin,
                identityProvider = identity,
                handoff = runtime.state.sessionCoordinator,
                authApi = auth,
                clockMillis = { runtime.clock.wallTimeMillis() },
                uuidProvider = { UUID.randomUUID().toString() },
            ).also { signIns += it }
            val controller = AccountStartupState(
                SessionAccountStartupOperations(runtime.state, signIn),
                true,
                MainScope().also { scopes += it },
            ).also { startups += it }
            composeRule.runOnIdle { startup = controller }
            composeRule.waitUntil(15_000L) { !controller.uiState.value.busy }
            val mark = runtime.wire.size
            composeRule.runOnIdle { controller.signIn() }
            composeRule.waitUntil(30_000L) {
                !controller.uiState.value.busy && controller.uiState.value.error == AccountStartupError.PREPARATION_PAUSED
            }
            assertEquals(1, providerCalls)
            assertEquals(1, exchanges)
            assertEquals(frozen, runtime.harness.outboxSnapshot(runtime.database, grant.accountId))
            composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
            composeRule.onNodeWithText("Waiting to sync your saved changes. You do not need to sign in again.").assertIsDisplayed()
            composeRule.onNodeWithTag("account-error").assertDoesNotExist()
            composeRule.onNodeWithTag("account-continue-preparation").assertIsNotEnabled()
            composeRule.onNodeWithTag("account-sign-in").assertIsNotEnabled()
            // Even a deliberate programmatic retry still preserves the engine's deadline.
            composeRule.runOnIdle { controller.continuePreparation() }
            composeRule.waitUntil(15_000L) { !controller.uiState.value.busy }
            assertEquals(AccountStartupError.PREPARATION_PAUSED, controller.uiState.value.error)
            assertEquals("saved-session retry never reacquires Google", 1, providerCalls)
            val reservation = runBlocking { RoomSessionAuthority(runtime.database).read() }

            composeRule.runOnIdle { controller.signIn() }
            composeRule.waitUntil(30_000L) { !controller.uiState.value.busy && exchanges == 2 }
            assertEquals(AccountStartupError.PREPARATION_PAUSED, controller.uiState.value.error)
            assertEquals(2, providerCalls)
            assertTrue(reservation.identity != runBlocking { RoomSessionAuthority(runtime.database).read() }.identity)
            assertEquals(frozen, runtime.harness.outboxSnapshot(runtime.database, grant.accountId))
            assertEquals("neither successful sign-in bypasses upload backoff", mark, runtime.wire.size)

            runtime.clock.elapsed += retryAt - runtime.clock.wallTimeMillis() - 1L
            composeRule.waitUntil(10_000L) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasText(accountRetryText(1L))).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(AccountStartupError.PREPARATION_PAUSED, controller.uiState.value.error)
            assertEquals(mark, runtime.wire.size)
            runtime.clock.elapsed += 1L
            composeRule.waitUntil(60_000L) {
                !controller.uiState.value.busy && runtime.state.sessionCoordinator.state.value is AppSessionState.Active
            }
            assertNull(controller.uiState.value.error)
            assertEquals(2, providerCalls)
            assertEquals(0L, runtime.harness.outboxCount(runtime.database, grant.accountId))
            composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
            assertTrue(runtime.harness.customNames(runtime.database, grant.accountId).contains("Reconnect backoff press"))
            // Independent readback rules out a client-only empty-queue false positive.
            val reader = runtime("reconnect-reader", "reconnect-reader.db", transport)
            reader.activate(mintSameAccountGrant(transport, inputs.port, grant.accessToken))
            assertTrue(reader.harness.customNames(reader.database, grant.accountId).contains("Reconnect backoff press"))
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun foregroundEditReconnectExactReplay() {
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
        try {
            val grant = mintRelayGrant(transport, inputs.port)
            val relayAccount = RoomBackendAccount(grant.accountId, grant.accessToken)
            val runtimeA = runtime("foreground-relay-a", "foreground-relay-a.db", transport)
            val runtimeB = runtime("foreground-relay-b", "foreground-relay-b.db", transport)
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
            runBlocking(Dispatchers.IO) { roomBackendLoseNextPush(transport, inputs.port, relayAccount) }
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
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
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
            val runtimeA = runtime("foreground-reject-a", "foreground-reject-a.db", transport)
            val runtimeB = runtime("foreground-reject-b", "foreground-reject-b.db", transport)
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
