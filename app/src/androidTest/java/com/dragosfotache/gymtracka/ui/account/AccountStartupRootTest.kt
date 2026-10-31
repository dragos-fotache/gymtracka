package com.dragosfotache.gymtracka.ui.account

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityProvider
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPolicy
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgressPhase
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementRequest
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportTarget
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppSessionAuthApi
import com.dragosfotache.gymtracka.data.sync.session.AppSessionCoordinator
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionStaleException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransport
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInCoordinator
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInIntent
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.ui.AccountGymAppState
import com.dragosfotache.gymtracka.ui.GymAppClock
import com.dragosfotache.gymtracka.ui.RestTimerHardware
import com.dragosfotache.gymtracka.ui.captureSettingsScreenshot
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual-root startup checks for configuration/privacy gates, private preparation,
 * invocation-bound import consent and real facade authority. Uses Room, a namespaced vault and
 * scripted identity/auth/transport.
 */
@RunWith(AndroidJUnit4::class)
class AccountStartupRootTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val harness = AppSessionHarness("startup-startup")
    private val dbs = mutableListOf<GymDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val runtimes = mutableListOf<AccountGymAppState>()
    private val startups = mutableListOf<AccountStartupState>()
    private val transports = mutableListOf<AppSessionTransport>()

    @After
    fun tearDown() {
        composeRule.runOnIdle { }
        startups.forEach { runCatching { it.dispose() } }
        startups.clear()
        runtimes.forEach { runCatching { it.dispose() } }
        runtimes.clear()
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harness.clean()
    }

    private fun capturedImport(): LegacyImportProgress = LegacyImportProgress(
        phase = LegacyImportProgressPhase.CAPTURED,
        importCount = 2L,
        acceptedCount = 0L,
        activeReplacementRequired = true,
        activeReplacementConsented = false,
    )

    private fun blockedImport(): LegacyImportProgress = LegacyImportProgress(
        phase = LegacyImportProgressPhase.BLOCKED,
        importCount = 1L,
        acceptedCount = 0L,
        activeReplacementRequired = false,
        activeReplacementConsented = false,
    )

    /**
     * Scripted operations whose presentation/visibility source stays the real
     * facade: state/recover delegate to runtime.sessionCoordinator while
     * sign-in/activation/legacy scripts drive the consent/error flows under
     * test. A fake DTO can therefore never grant visibility on its own.
     */
    private class FakeOps(
        var delegate: AppSessionCoordinator? = null,
        var legacyValue: LegacyImportProgress? = null,
        var legacyFault: Throwable? = null,
        var consent: AccountImportConsent? = null,
    ) : AccountStartupOperations {
        private val backing = MutableStateFlow<AppSessionState>(AppSessionState.PrivacyLocked)
        override val state: StateFlow<AppSessionState>
            get() = delegate?.state ?: backing
        var recoverCalls = 0
        var signInCalls = 0
        var completeCalls = 0
        val signInScript = ArrayDeque<AppSessionState>()
        val completeScript = ArrayDeque<AppSessionState>()

        fun publish(state: AppSessionState) {
            backing.value = state
        }

        override suspend fun recover(): AppSessionState {
            recoverCalls += 1
            val facade = delegate
            if (facade != null) return facade.recover()
            val next = backing.value
            return next
        }

        override suspend fun signIn(
            intent: ForegroundSignInIntent,
            expected: RecoveryRoomSnapshot,
            operationId: String,
        ): AppSessionState {
            signInCalls += 1
            val next = if (signInScript.isNotEmpty()) signInScript.removeFirst() else (delegate?.state?.value ?: backing.value)
            backing.value = next
            return next
        }

        override suspend fun completeActivation(
            expected: RecoveryRoomSnapshot,
            stageId: String,
        ): AppSessionState {
            completeCalls += 1
            val bridge = consent
            if (bridge != null && legacyValue?.activeReplacementRequired == true) {
                bridge.confirm(
                    LegacyImportReplacementRequest(
                        target = LegacyImportTarget(
                            origin = expected.identity?.binding?.origin ?: AppSessionFixtures.origin(),
                            accountId = AppSessionFixtures.ACCOUNT,
                            operationId = AppSessionFixtures.OP_FRESH,
                        ),
                        mutationId = "e13c2001-1111-4111-8111-111111111111",
                    ),
                )
            }
            val next = if (completeScript.isNotEmpty()) completeScript.removeFirst() else (delegate?.state?.value ?: backing.value)
            backing.value = next
            return next
        }

        override suspend fun observeLegacyImport(): LegacyImportProgress? {
            legacyFault?.let { throw it }
            return legacyValue
        }

        override fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus> = emptyFlow()
    }

    private class FakeIdentity : GoogleIdentityProvider {
        val nonces = mutableListOf<String>()
        override suspend fun credentialFor(nonce: String): OpaqueToken {
            nonces += nonce
            return OpaqueToken("startup-google-token")
        }

        override suspend fun clearCredentialState() = Unit
    }

    private class FakeExchangeAuth : ServiceAuthApi {
        val purposes = mutableListOf<GoogleChallengePurpose>()
        val attempts = mutableListOf<GoogleExchangeAttempt>()
        var grant: ServiceSession = AppSessionFixtures.grant()
        override suspend fun challenge(purpose: GoogleChallengePurpose): ServiceChallenge {
            purposes += purpose
            // challengeId/nonce must be canonical UUIDs per ServiceChallenge contract.
            return ServiceChallenge(
                "d13c4011-1111-4111-8111-111111111111",
                "d13c4012-2222-4222-8222-222222222222",
                AppSessionFixtures.FIXED_CLOCK + 300_000L,
            )
        }

        override suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession {
            attempts += attempt
            return grant
        }

        override suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession {
            throw SyncApiException.Transient()
        }

        override suspend fun logout(refreshToken: OpaqueToken) = Unit
    }

    private fun runtime(
        policy: LegacyImportPolicy? = null,
        runtimeUuids: List<String>? = null,
    ): AccountGymAppState {
        val database = harness.openInMemoryDb().also { dbs += it }
        val transport = AppSessionTransport().also { transports += it }
        val uuids = (runtimeUuids ?: listOf(
            AppSessionFixtures.OP_FRESH,
            AppSessionFixtures.KEY_FRESH,
            AppSessionFixtures.OP_OTHER,
            AppSessionFixtures.STAGE,
            AppSessionFixtures.STAGE_OTHER,
        )).iterator()
        return AccountGymAppState(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = harness.newVault(),
            authApi = AppSessionAuthApi(),
            transport = transport,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = TestClock(),
            timerHardware = NoOpHardware(),
            uuidProvider = { uuids.next() },
            legacyImportPolicy = policy,
        ).also { runtimes += it }
    }

    private fun startup(ops: AccountStartupOperations?, signInAvailable: Boolean): AccountStartupState =
        AccountStartupState(
            operations = ops,
            signInAvailable = signInAvailable,
            scope = MainScope().also { scopes += it },
        ).also { startups += it }

    private fun workoutCount(database: GymDatabase): Long {
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM workouts").use { cursor ->
            check(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    private fun prepareReal(runtime: AccountGymAppState, database: GymDatabase) {
        runBlocking {
            val preparing = runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(),
            )
            assertTrue("setup must reach Private preparation, was $preparing", preparing is AppSessionState.Private)
        }
    }

    @Test
    fun nullOperations_showsBothGatesAndPreservesSeededGraphWithoutHttp() {
        val state = runtime()
        val database = dbs.last()
        runBlocking {
            val repository = GymRepository(database)
            val customId = repository.createCustomExercise("startup Local Press", null, emptySet(), null, null, "")
            val workout = repository.startEmptyWorkout(1_700_000_000_000L)
            repository.addExercisesToWorkout(workout.id, listOf(customId))
        }
        val before = workoutCount(database)
        assertTrue(before >= 1L)
        val consent = AccountImportConsent()
        val startupState = startup(null, false)
        composeRule.setContent { AccountStartupApp(state, startupState, consent) }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        composeRule.onNodeWithTag("account-configuration-unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("account-sign-in").assertDoesNotExist()
        composeRule.onNodeWithTag("nav-workout").assertDoesNotExist()
        composeRule.onNodeWithTag("nav-exercises").assertDoesNotExist()
        composeRule.onNodeWithTag("nav-history").assertDoesNotExist()
        composeRule.runOnIdle { }
        assertEquals(before, workoutCount(database))
    }

    @Test
    fun retainedPrivate_offersFinishSigningInAndExplainsFreshSessionAction() {
        val state = runtime()
        val database = dbs.last()
        prepareReal(state, database)
        val consent = AccountImportConsent()
        // Authority stays the real facade; the fake only scripts legacy detail.
        val ops = FakeOps(delegate = state.sessionCoordinator, legacyValue = capturedImport(), consent = consent)
        val startupState = startup(ops, true)
        composeRule.setContent { AccountStartupApp(state, startupState, consent) }
        startupState.start()
        composeRule.waitUntil(10_000L) { !startupState.uiState.value.busy }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        // Retained private preparation must offer an explicit foreground action,
        // never silently sit without one.
        composeRule.onNodeWithTag("account-continue-preparation").assertIsDisplayed()
        composeRule.onNodeWithText("Finish signing in").assertIsDisplayed()
        composeRule.onNodeWithText("Resume your saved sign-in, or sign in again to get a new session for the same account.")
            .assertIsDisplayed()
    }

    @Test
    fun interruptedActivation_repeatedSignInRefusesForeignThenActivatesFreshSameAccountSession() {
        val state = runtime()
        val database = dbs.last()
        prepareReal(state, database)
        val saved = runBlocking { RoomSessionAuthority(database).read() }
        val vault = harness.reopenVault()
        val evidence = runBlocking { vault.load() }
        val identity = FakeIdentity()
        val auth = FakeExchangeAuth().also {
            it.grant = AppSessionFixtures.grant(accountId = AppSessionFixtures.OTHER_ACCOUNT)
        }
        val coordinator = ForegroundSignInCoordinator(
            origin = AppSessionFixtures.origin(), identityProvider = identity,
            handoff = state.sessionCoordinator, authApi = auth,
            clockMillis = { AppSessionFixtures.FIXED_CLOCK },
        )
        val startupState = startup(SessionAccountStartupOperations(state, coordinator), true)
        composeRule.setContent { AccountStartupApp(state, startupState, AccountImportConsent()) }
        startupState.start()
        composeRule.waitUntil(10_000L) { !startupState.uiState.value.busy }
        composeRule.onNodeWithText("Finish signing in").assertIsDisplayed()
        composeRule.onNodeWithTag("account-sign-in").performScrollTo().performClick()
        composeRule.waitUntil(10_000L) { !startupState.uiState.value.busy && auth.attempts.size == 1 }
        assertEquals(AccountStartupError.IDENTITY_MISMATCH, startupState.uiState.value.error)
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        assertEquals(saved, runBlocking { RoomSessionAuthority(database).read() })
        assertEquals(evidence, runBlocking { vault.load() })
        composeRule.onNodeWithText("Account mismatch. Your data stays private.").performScrollTo().assertIsDisplayed()
        captureSettingsScreenshot("interrupted-sign-in-foreign")

        auth.grant = AppSessionFixtures.grant(
            access = AppSessionFixtures.ACCESS_ROTATED, refresh = AppSessionFixtures.REFRESH_ROTATED,
        )
        transports.last().queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transports.last().queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transports.last().queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        composeRule.onNodeWithTag("account-sign-in").performScrollTo().performClick()
        composeRule.waitUntil(15_000L) { !startupState.uiState.value.busy && auth.attempts.size == 2 }
        assertNull(startupState.uiState.value.error)
        val active = state.sessionCoordinator.state.value as AppSessionState.Active
        assertTrue(active.session.identity.binding.sessionGeneration > saved.sessionGeneration)
        assertTrue(active.session.identity.operationId != saved.identity?.operationId)
        assertEquals(auth.grant, runBlocking { vault.load().record?.session })
        assertEquals(2, identity.nonces.size)
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        composeRule.onNodeWithTag("nav-settings").assertIsDisplayed()
        coordinator.close()
    }

    @Test
    fun preparingWithWarning_cancelReturnsFalseAndConfirmIsPerInvocation() {
        val state = runtime()
        val database = dbs.last()
        prepareReal(state, database)
        val preparingSnapshot = runBlocking { RoomSessionAuthority(database).read() }
        val consent = AccountImportConsent()
        val ops = FakeOps(delegate = state.sessionCoordinator, legacyValue = capturedImport(), consent = consent)
        val preparingPrivate = AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, preparingSnapshot)
        ops.signInScript += preparingPrivate
        ops.completeScript += preparingPrivate
        ops.completeScript += preparingPrivate
        val startupState = startup(ops, true)
        composeRule.setContent { AccountStartupApp(state, startupState, consent) }
        startupState.signIn()
        composeRule.waitUntil(10_000L) { consent.pending.value != null }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        composeRule.onNodeWithTag("account-import-warning").assertIsDisplayed()
        // Title + body both explain the replacement: assert both nodes, not one.
        composeRule.onAllNodesWithText("replace", substring = true, ignoreCase = true).assertCountEquals(2)
        composeRule.onNodeWithText("cannot be undone", substring = true, ignoreCase = true).assertIsDisplayed()
        val first = checkNotNull(consent.pending.value)
        composeRule.onNodeWithTag("account-import-cancel").performClick()
        composeRule.waitUntil(10_000L) { consent.pending.value == null }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()

        startupState.signIn()
        composeRule.waitUntil(10_000L) { consent.pending.value != null }
        val second = checkNotNull(consent.pending.value)
        assertNotSame(first, second)
        consent.answer(first, true)
        composeRule.runOnIdle { }
        assertTrue(consent.pending.value === second)
        composeRule.onNodeWithTag("account-import-confirm").performClick()
        composeRule.waitUntil(10_000L) { consent.pending.value == null }
        assertTrue(ops.completeCalls >= 1)
        // Real facade never left Private preparation: no visibility granted.
        assertTrue(state.sessionCoordinator.state.value is AppSessionState.Private)
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
    }

    @Test
    fun blockedImport_neverFallsBackAndMapsUnavailable() {
        val state = runtime()
        val database = dbs.last()
        prepareReal(state, database)
        val preparingSnapshot = runBlocking { RoomSessionAuthority(database).read() }
        val consent = AccountImportConsent()
        val ops = FakeOps(delegate = state.sessionCoordinator, legacyValue = blockedImport(), consent = consent)
        ops.signInScript += AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, preparingSnapshot)
        val startupState = startup(ops, true)
        composeRule.setContent { AccountStartupApp(state, startupState, consent) }
        startupState.signIn()
        composeRule.waitUntil(10_000L) { startupState.uiState.value.error != null || ops.signInCalls >= 1 }
        composeRule.runOnIdle { }
        assertEquals(0, ops.completeCalls)
        assertEquals(AccountStartupError.UNAVAILABLE, startupState.uiState.value.error)
        assertTrue(state.sessionCoordinator.state.value is AppSessionState.Private)
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        composeRule.onNodeWithTag("account-error").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-settings").assertDoesNotExist()
    }

    @Test
    fun fakeDtoActive_cannotGrantVisibilityWithoutRealHandle() {
        val privateState = runtime()
        val other = runtime()
        val otherDb = dbs.last()
        val otherTransport = transports.last()
        val otherSession = runBlocking {
            otherTransport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
            otherTransport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            otherTransport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            val preparing = other.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(otherDb).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(),
            )
            assertTrue(preparing is AppSessionState.Private)
            val active = other.sessionCoordinator.completeActivation(
                RoomSessionAuthority(otherDb).read(),
                AppSessionFixtures.STAGE,
            )
            assertTrue("setup must reach Active, was $active", active is AppSessionState.Active)
            (active as AppSessionState.Active).session
        }
        // Even when a DTO claims Active with a genuinely admitted live session,
        // the controlled root's authority stays privateState.sessionCoordinator:
        // a foreign handle cannot grant visibility to the private runtime.
        val consent = AccountImportConsent()
        val ops = FakeOps()
        ops.publish(AppSessionState.Active(otherSession))
        val startupState = startup(ops, true)
        composeRule.setContent { AccountStartupApp(privateState, startupState, consent) }
        composeRule.runOnIdle { }
        assertTrue(privateState.sessionCoordinator.state.value is AppSessionState.PrivacyLocked)
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-settings").assertDoesNotExist()
    }

    @Test
    fun settingsAccount_missingAudienceKeepsFeaturesVisibleWithoutSecrets() {
        val state = runtime()
        val database = dbs.last()
        val transport = transports.last()
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        runBlocking {
            val preparing = state.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(),
            )
            assertTrue(preparing is AppSessionState.Private)
            val active = state.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.STAGE,
            )
            assertTrue("setup must reach Active, was $active", active is AppSessionState.Active)
        }
        val consent = AccountImportConsent()
        // Missing audience disables sign-in, but the established account root
        // stays visible offline: presentation authority is the live handle.
        val ops = FakeOps(delegate = state.sessionCoordinator)
        val startupState = startup(ops, false)
        composeRule.setContent { AccountStartupApp(state, startupState, consent) }
        startupState.start()
        composeRule.waitUntil(10_000L) { !startupState.uiState.value.busy }
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        composeRule.onNodeWithTag("nav-settings").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.onNodeWithTag("settings-account").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-settings").assertIsSelected()
        composeRule.onNodeWithTag("settings-server").assertIsDisplayed()
        captureSettingsScreenshot("account-top")
        // Missing audience disables reauthentication while offline features remain available.
        composeRule.onNodeWithTag("account-reauthenticate").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("account-reauthenticate").assertIsNotEnabled()
        captureSettingsScreenshot("account-actions")
        composeRule.onNodeWithText(
            "Foreground sign-in is unavailable",
            substring = true,
            ignoreCase = true,
        ).performScrollTo().assertIsDisplayed()
        // Last-success keeps its node with unknown/unavailable text, never a fabricated zero.
        composeRule.onNodeWithTag("account-last-success").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        // No private identifiers, credentials or endpoint strings enter presentation.
        composeRule.onNodeWithText(AppSessionFixtures.ACCOUNT, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(AppSessionFixtures.ORIGIN_ROOT, substring = true).assertDoesNotExist()
    }

    /**
     * Empty-install activation only (no local graph, no legacy policy): proves
     * the controller foreground-grant wiring end to end, not import evidence.
     */
    @Test
    fun controllerServiceGrant_emptyInstallActivationToVisibleJourney() {
        val state = runtime()
        val database = dbs.last()
        val identity = FakeIdentity()
        val auth = FakeExchangeAuth()
        val coordinator = ForegroundSignInCoordinator(
            origin = AppSessionFixtures.origin(),
            identityProvider = identity,
            handoff = state.sessionCoordinator,
            authApi = auth,
            clockMillis = { AppSessionFixtures.FIXED_CLOCK },
            uuidProvider = { AppSessionFixtures.OP_OTHER },
        )
        val realOps = SessionAccountStartupOperations(state, coordinator)
        val startupState = startup(realOps, true)
        val consent = AccountImportConsent()
        composeRule.setContent { AccountStartupApp(state, startupState, consent) }
        startupState.start()
        composeRule.waitUntil(10_000L) { !startupState.uiState.value.busy }
        // Complete snapshot/tails queued BEFORE sign-in: signIn owns its
        // activation attempt, so no Continue is needed on the success path.
        // Deliberately exhausting transport is never a recovery oracle.
        transports.last().queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transports.last().queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transports.last().queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        startupState.signIn()
        composeRule.waitUntil(10_000L) { identity.nonces.size == 1 }
        composeRule.waitUntil(15_000L) { state.appState != null }
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        composeRule.onNodeWithTag("nav-settings").assertIsDisplayed()
    }

    /**
     * SUCCESSFUL same-account foreground reauth through the controller: an
     * initially activated CLEAN matching account whose preserved custom
     * arrives as an independent INITIAL complete canonical snapshot (no
     * outbox intent whatsoever), genuinely distinct rotated exchange-grant
     * tokens for the same UUID/origin, and valid current-cursor PULL tails
     * only (a complete existing cache needs no snapshot). One user reauth
     * invocation owns one activation attempt. Asserts the new exact Active
     * handle, preserved data with no deletion/fallback, and
     * pull-without-snapshot routes. A Private stop fails loudly with
     * diagnostics (no retry, weakening or bypass).
     */
    @Test
    fun sameAccountForegroundReauth_returnsActiveWithPreservedData() {
        val state = runtime()
        val database = dbs.last()
        val transport = transports.last()
        transport.queueOk(reauthSnapshot())
        transport.queueOk(reauthTail())
        transport.queueOk(reauthTail())
        val first = runBlocking {
            val preparing = state.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(),
            )
            assertTrue(preparing is AppSessionState.Private)
            val active = state.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.STAGE,
            )
            assertTrue("setup must reach Active, was $active", active is AppSessionState.Active)
            (active as AppSessionState.Active).session
        }
        assertEquals(AppSessionFixtures.ACCOUNT, first.identity.binding.accountId)
        // Clean fixture: server-published custom visible, zero pending intent.
        assertTrue(harness.customNames(database, AppSessionFixtures.ACCOUNT).contains("startup Reauth Kept"))
        assertEquals(0L, harness.outboxCount(database, AppSessionFixtures.ACCOUNT))
        val identity = FakeIdentity()
        val exchange = FakeExchangeAuth()
        exchange.grant = AppSessionFixtures.grant(
            accountId = AppSessionFixtures.ACCOUNT,
            access = AppSessionFixtures.ACCESS_ROTATED,
            refresh = AppSessionFixtures.REFRESH_ROTATED,
        )
        val coordinator = ForegroundSignInCoordinator(
            origin = AppSessionFixtures.origin(),
            identityProvider = identity,
            handoff = state.sessionCoordinator,
            authApi = exchange,
            clockMillis = { AppSessionFixtures.FIXED_CLOCK },
            uuidProvider = { UUID.randomUUID().toString() },
        )
        val realOps = SessionAccountStartupOperations(state, coordinator)
        val startupState = AccountStartupState(
            operations = realOps,
            signInAvailable = true,
            scope = MainScope().also { scopes += it },
            uuidProvider = { UUID.randomUUID().toString() },
        ).also { startups += it }
        composeRule.setContent { AccountStartupApp(state, startupState, AccountImportConsent()) }
        startupState.start()
        composeRule.waitUntil(10_000L) { !startupState.uiState.value.busy }
        // Complete cache: current-cursor PULL tails only, never another snapshot download.
        transport.queueOk(reauthTail())
        transport.queueOk(reauthTail())
        transport.queueOk(reauthTail())
        val mark = transport.capturedRequests.size
        val preGeneration = first.identity.binding.sessionGeneration
        val preOperation = first.identity.operationId
        startupState.signIn()
        composeRule.waitUntil(10_000L) { identity.nonces.size == 1 }
        // Busy terminal waiter separates the completed invocation from the
        // prior Active: facade-state Active alone is already true from setup,
        // so only the owned attempt settling proves grant installation.
        composeRule.waitUntil(15_000L) { !startupState.uiState.value.busy }
        assertNull(startupState.uiState.value.error)
        val final = state.sessionCoordinator.state.value
        assertTrue(
            "same-account reauth stopped without Active: still $final " +
                "[${importDiagnostics(transport, database)}]",
            final is AppSessionState.Active,
        )
        val second = (final as AppSessionState.Active).session
        // Only the completed invocation's new handle counts: Room/session
        // generation strictly advances, the operation differs, and the
        // session objects rotate with the old handle retired.
        assertTrue(
            "generation must advance, was ${second.identity.binding.sessionGeneration} from $preGeneration",
            second.identity.binding.sessionGeneration > preGeneration,
        )
        assertTrue("operation must differ", preOperation != second.identity.operationId)
        assertNotSame(first, second)
        var retired = false
        runBlocking {
            try {
                first.checkCurrent()
            } catch (_: AppSessionStaleException) {
                retired = true
            }
        }
        assertTrue("superseded handle must be retired", retired)
        runBlocking { second.checkCurrent() }
        // Preserved data with no deletion or fallback.
        assertTrue(harness.customNames(database, AppSessionFixtures.ACCOUNT).contains("startup Reauth Kept"))
        assertEquals(1, identity.nonces.size)
        assertEquals("service grant must be exchanged, not cached", 1, exchange.attempts.size)
        val roundPaths = transport.capturedRequests.drop(mark).map { it.path }
        assertTrue(roundPaths.any { it.contains("pull") })
        assertTrue("complete cache needs no snapshot, routes=$roundPaths", roundPaths.none { it.contains("snapshot") })
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        composeRule.onNodeWithTag("nav-settings").assertIsDisplayed()
    }

    /**
     * Actual eligible legacy import journey: ONLY the raw local-only custom
     * plus local active workout is seeded. The facade's own opt-in capture
     * (minting the controlled stable mutation IDs through the runtime UUID
     * supplier), controller foreground grant, UI warning consent and one
     * definitive Continue settle exact accepted push receipts before the
     * matching complete snapshot publishes. No direct capture/readiness setup:
     * preinstalled source/target would not prove the initial startup path.
     * Wire shapes reuse the private-import settlement fixtures with this
     * test IDs/names. The empty-install test above stays the correctly
     * named non-import proof.
     */
    @Test
    fun controllerServiceGrant_legacyImportSettlesBeforeVisibleJourney() {
        val consent = AccountImportConsent()
        val state = runtime(
            LegacyImportPolicy(consent),
            listOf(IMPORT_MUT_A, IMPORT_MUT_B, IMPORT_SPARE_1, IMPORT_SPARE_2, IMPORT_SPARE_3, IMPORT_SPARE_4),
        )
        val database = dbs.last()
        val transport = transports.last()
        seedImportGraph(database)
        serveImportSettlement(transport)
        val identity = FakeIdentity()
        val coordinator = ForegroundSignInCoordinator(
            origin = AppSessionFixtures.origin(),
            identityProvider = identity,
            handoff = state.sessionCoordinator,
            authApi = FakeExchangeAuth(),
            clockMillis = { AppSessionFixtures.FIXED_CLOCK },
            uuidProvider = { UUID.randomUUID().toString() },
        )
        val realOps = SessionAccountStartupOperations(state, coordinator)
        var uuidCalls = 0
        val startupState = AccountStartupState(
            operations = realOps,
            signInAvailable = true,
            scope = MainScope().also { scopes += it },
            uuidProvider = { if (uuidCalls++ == 0) IMPORT_OP else UUID.randomUUID().toString() },
        ).also { startups += it }
        composeRule.setContent { AccountStartupApp(state, startupState, consent) }
        startupState.start()
        composeRule.waitUntil(10_000L) { !startupState.uiState.value.busy }
        // Hold the first push response so private progress is observed
        // deterministically (no sleep/race) before release to Active.
        val holdPush = CompletableDeferred<Unit>()
        val pushHeld = java.util.concurrent.atomic.AtomicBoolean(false)
        transport.responseGate = {
            if (pushHeld.compareAndSet(false, true)) {
                holdPush.await()
            }
        }
        startupState.signIn()
        composeRule.waitUntil(10_000L) { identity.nonces.size == 1 }
        // signIn owns its activation attempt: the facade suspends on the real
        // import consent while busy (no Continue needed on the success path).
        // Waiting for !busy first would deadlock the pending warning.
        composeRule.waitUntil(10_000L) { consent.pending.value != null }
        assertTrue(startupState.uiState.value.busy)
        composeRule.onNodeWithTag("account-import-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("account-import-confirm").performClick()
        composeRule.waitUntil(10_000L) { consent.pending.value == null }
        // Private progress while the first push response is deterministically held.
        composeRule.waitUntil(10_000L) { pushHeld.get() }
        assertTrue(state.sessionCoordinator.state.value is AppSessionState.Private)
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        holdPush.complete(Unit)
        transport.responseGate = null
        try {
            composeRule.waitUntil(15_000L) { state.sessionCoordinator.state.value is AppSessionState.Active }
        } catch (e: Throwable) {
            throw AssertionError(
                "activation-stop recurrence: still ${state.sessionCoordinator.state.value} " +
                    "[${importDiagnostics(transport, database)}]",
                e,
            )
        }
        // Facade capture minted exactly the controlled stable mutation IDs in
        // dependency order (custom before workout), flattened across ALL actual
        // push bodies: the driver sends exact individual claimed heads.
        val pushIds = transport.capturedRequests
            .filter { it.path.contains("push") }
            .flatMap { request ->
                val mutations = JSONObject(checkNotNull(request.body) { "push requires a body" }).getJSONArray("mutations")
                (0 until mutations.length()).map { mutations.getJSONObject(it).getString("mutationId") }
            }
        assertEquals(listOf(IMPORT_MUT_A, IMPORT_MUT_B), pushIds)
        // Push settled before snapshot: push route first, then snapshot/tail.
        val paths = transport.capturedRequests.map { it.path }
        assertTrue(paths.first().contains("push"))
        assertTrue(paths.any { it.contains("snapshot") })
        assertEquals(0L, harness.outboxCount(database, AppSessionFixtures.ACCOUNT))
        val progress = runBlocking { state.sessionCoordinator.observeLegacyImport() }
        checkNotNull(progress)
        assertEquals(LegacyImportProgressPhase.SETTLED, progress.phase)
        assertEquals(2L, progress.importCount)
        assertEquals(2L, progress.acceptedCount)
        // The pinned consent binds exactly the original WORKOUT mutation.
        val recorded = database.openHelper.readableDatabase.query(
            "SELECT active_consent_mutation_id FROM sync_legacy_import_progress WHERE id = 1",
        ).use { cursor ->
            check(cursor.moveToFirst()) { "consent must be recorded" }
            cursor.getString(0)
        }
        assertEquals(IMPORT_MUT_B, recorded)
        val entryMutations = database.openHelper.readableDatabase.query(
            "SELECT mutation_id FROM sync_legacy_import_entries ORDER BY ordinal",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue(entryMutations.contains(recorded))
        assertTrue(harness.customNames(database, AppSessionFixtures.ACCOUNT).contains("startup Import Press"))
        composeRule.waitUntil(10_000L) { state.appState != null }
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        composeRule.onNodeWithTag("nav-settings").assertIsDisplayed()
    }

    private fun importDiagnostics(transport: AppSessionTransport, database: GymDatabase): String = buildString {
        append("routes=")
        append(transport.capturedRequests.map { it.path })
        append("; outbox=")
        append(database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_outbox").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        })
        append("; receipts=")
        append(database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_legacy_import_acceptances").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        })
    }

    private companion object {
        const val IMPORT_CUSTOM = "11111111-d13c-4d13-8d13-d13bd13bd13b"
        // Independent server-published custom for the clean reauth fixture.
        const val REAUTH_CUSTOM = "22222222-d13c-4d13-8d13-d13bd13bd13b"
        const val IMPORT_WORKOUT = "55555555-d13c-4d13-8d13-d13bd13bd13b"
        const val IMPORT_WE = "d1d13c00-d100-4d10-8d10-d100d100d100"
        const val IMPORT_WS = "e1d13c00-e100-4e10-8e10-e100e100e100"
        const val IMPORT_OP = "d13c4001-1111-4111-8111-111111111111"
        const val IMPORT_MUT_A = "70000001-d13c-4d13-8d13-d13bd13bd13b"
        const val IMPORT_MUT_B = "70000002-d13c-4d13-8d13-d13bd13bd13b"
        const val IMPORT_SPARE_1 = "d13c4002-2222-4222-8222-222222222222"
        const val IMPORT_SPARE_2 = "d13c4003-3333-4333-8333-333333333333"
        const val IMPORT_SPARE_3 = "d13c4004-4444-4444-8444-444444444444"
        const val IMPORT_SPARE_4 = "d13c4005-5555-4555-8555-555555555555"
        const val IMPORT_BATCH_A = "b0000001-d13c-4d13-8d13-d13bd13bd13b"
        const val IMPORT_BATCH_B = "b0000002-d13c-4d13-8d13-d13bd13bd13b"
    }

    private fun seedImportGraph(database: GymDatabase) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$IMPORT_CUSTOM', 'startup Import Press', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Step\"]', 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$IMPORT_WORKOUT', 'startup Local W', 4000, NULL, 'active', NULL, NULL, 2, 'local_only', NULL)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                " VALUES ('$IMPORT_WE', '$IMPORT_WORKOUT', '$IMPORT_CUSTOM', 0, 'Keep', 75)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$IMPORT_WS', '$IMPORT_WE', 0, 8, 70.0, 1, 60)",
        )
    }

    private fun importCustomRecord(): JSONObject = JSONObject()
        .put("id", IMPORT_CUSTOM)
        .put("name", "startup Import Press")
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

    private fun importWorkoutRecord(): JSONObject = JSONObject()
        .put("id", IMPORT_WORKOUT)
        .put("name", "startup Local W")
        .put("status", "active")
        .put("startedAt", "1970-01-01T00:00:04Z")
        .put("revision", 1)
        .put(
            "exercises",
            org.json.JSONArray().put(
                JSONObject()
                    .put("id", IMPORT_WE)
                    .put("exerciseId", IMPORT_CUSTOM)
                    .put("exerciseName", "startup Import Press")
                    .put("position", 0)
                    .put("notes", "Keep")
                    .put("restDurationSeconds", 75)
                    .put(
                        "sets",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("id", IMPORT_WS)
                                .put("position", 0)
                                .put("repetitions", 8)
                                .put("weightKg", 70.0)
                                .put("isCompleted", true)
                                .put("restSeconds", 60),
                        ),
                    ),
            ),
        )

    private fun importRecordFor(aggregateType: String, aggregateId: String): JSONObject = when {
        aggregateType == "customExercise" && aggregateId == IMPORT_CUSTOM -> importCustomRecord()
        aggregateType == "workout" && aggregateId == IMPORT_WORKOUT -> importWorkoutRecord()
        else -> throw AssertionError("unexpected import root $aggregateType:$aggregateId")
    }

    private fun importBatchFor(mutationId: String): String = when (mutationId) {
        IMPORT_MUT_A -> IMPORT_BATCH_A
        IMPORT_MUT_B -> IMPORT_BATCH_B
        else -> throw AssertionError("no batch for $mutationId")
    }

    private fun importSnapshot(): String = JSONObject()
        .put(
            "records",
            org.json.JSONArray(
                listOf(
                    JSONObject()
                        .put("aggregateType", "customExercise")
                        .put("aggregateId", IMPORT_CUSTOM)
                        .put("operation", "upsert")
                        .put("revision", 1)
                        .put("record", importCustomRecord()),
                    JSONObject()
                        .put("aggregateType", "workout")
                        .put("aggregateId", IMPORT_WORKOUT)
                        .put("operation", "upsert")
                        .put("revision", 1)
                        .put("record", importWorkoutRecord()),
                ),
            ),
        )
        .put("baselineCursor", "startup-baseline")
        .put("nextPageToken", JSONObject.NULL)
        .put("hasMore", false)
        .toString()

    private fun importEmptyTail(): String = JSONObject()
        .put("batches", org.json.JSONArray())
        .put("nextCursor", "startup-baseline")
        .put("hasMore", false)
        .toString()

    private fun reauthCustomRecord(): JSONObject = JSONObject()
        .put("id", REAUTH_CUSTOM)
        .put("name", "startup Reauth Kept")
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

    private fun reauthSnapshot(): String = JSONObject()
        .put(
            "records",
            org.json.JSONArray(
                listOf(
                    JSONObject()
                        .put("aggregateType", "customExercise")
                        .put("aggregateId", REAUTH_CUSTOM)
                        .put("operation", "upsert")
                        .put("revision", 1)
                        .put("record", reauthCustomRecord()),
                ),
            ),
        )
        .put("baselineCursor", "startup-reauth-baseline")
        .put("nextPageToken", JSONObject.NULL)
        .put("hasMore", false)
        .toString()

    private fun reauthTail(): String = JSONObject()
        .put("batches", org.json.JSONArray())
        .put("nextCursor", "startup-reauth-baseline")
        .put("hasMore", false)
        .toString()

    private fun serveImportSettlement(transport: AppSessionTransport) {
        transport.serve = { _, request ->
            when {
                request.path.contains("push") -> {
                    val mutations = JSONObject(checkNotNull(request.body) { "push requires a body" }).getJSONArray("mutations")
                    val outcomes = (0 until mutations.length()).map { index ->
                        val envelope = mutations.getJSONObject(index)
                        val mutationId = envelope.getString("mutationId")
                        val aggregateType = envelope.getString("aggregateType")
                        val aggregateId = envelope.getString("aggregateId")
                        JSONObject()
                            .put("mutationId", mutationId)
                            .put("status", "accepted")
                            .put("errorCode", JSONObject.NULL)
                            .put(
                                "batch",
                                JSONObject()
                                    .put("batchId", importBatchFor(mutationId))
                                    .put("cursor", "startup-cursor")
                                    .put(
                                        "changes",
                                        org.json.JSONArray().put(
                                            JSONObject()
                                                .put("aggregateType", aggregateType)
                                                .put("aggregateId", aggregateId)
                                                .put("operation", "upsert")
                                                .put("revision", 1)
                                                .put("record", importRecordFor(aggregateType, aggregateId)),
                                        ),
                                    ),
                            )
                    }
                    AppSessionTransport.Frame(
                        200,
                        JSONObject().put("results", org.json.JSONArray(outcomes)).toString(),
                    )
                }
                request.path.contains("snapshot") ->
                    AppSessionTransport.Frame(200, importSnapshot())
                else -> AppSessionTransport.Frame(200, importEmptyTail())
            }
        }
    }

    private class TestClock : GymAppClock {
        override fun wallTimeMillis() = AppSessionFixtures.FIXED_CLOCK
        override fun elapsedRealtimeMillis() = 0L
    }

    private class NoOpHardware : RestTimerHardware {
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) = Unit
        override fun releaseWakeLock() = Unit
    }
}
