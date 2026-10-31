package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityException
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityFailureReason
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityProvider
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.ui.FiniteEditCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guarded foreground grant handoff over actual Room,
 * Keystore vault and the real finite-edit transition barrier (androidTest).
 *
 * All account/operation/key/token literals below are test synthetic
 * values; wire bodies are hand-written strings, never production encoder output.
 * Real facade requests come only from captureForegroundSignInRequest with four
 * named-request semantics; naked four-argument construction is used solely to
 * prove the real facade refuses it. Deterministic gates only, no sleeps.
 *
 * Covers foreground exchange obligation 5 (first EMPTY preparation, same-account recovery with
 * uncertain predecessor evidence, foreign/owner/naked/stale/invalidated refusals,
 * cancelled/retired exchange preservation, representative simultaneous attempts).
 * Existing full session authority race matrices are reused, not repeated. JVM ordering/expiry/
 * budget/busy/cancel/retire/late-seam obligations live in
 * ForegroundSignInCoordinatorTest.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundSignInHandoffTest {

    private companion object {
        const val ORIGIN_ROOT = "https://sync-sign-in.example.test"
        const val OTHER_ORIGIN_ROOT = "https://other-sign-in.example.test"
        const val ACCOUNT = "b11b0011-1111-4111-8111-111111111111"
        const val OTHER_ACCOUNT = "b11b0012-2222-4222-8222-222222222222"
        const val CHALLENGE_ID = "a11b0011-1111-4111-8111-111111111111"
        const val NONCE = "sign-in-handoff-nonce-001"
        const val TOKEN = "sign-in-handoff-token-001"
        const val EXCHANGE_KEY = "c11b0011-1111-4111-8111-111111111111"
        const val PENDING_KEY = "c11b0012-2222-4222-8222-222222222222"
        const val OP_FRESH = "d11b0011-1111-4111-8111-111111111111"
        const val OP_SETUP = "d11b0012-2222-4222-8222-222222222222"
        const val OP_NEW = "d11b0013-3333-4333-8333-333333333333"
        const val OP_RIVAL = "d11b0014-4444-4444-8444-444444444444"
        const val FIXED_CLOCK = 1_699_999_900_000L
        const val GRANT_EXPIRY = 1_700_000_000_000L
        const val PENDING_BODY = """{"refreshToken":"sign-in-handoff-refresh-001"}"""
    }

    private val harness = AppSessionHarness("sign-in")
    private val dbs = mutableListOf<GymDatabase>()
    private val edits = mutableListOf<FiniteEditCoordinator>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        // Testing-strategy teardown convention: dispose finite-edit coordination and
        // cancel/join owned scopes BEFORE closing Room, then clean test storage.
        edits.forEach { runCatching { it.dispose() } }
        edits.clear()
        scopes.forEach { runCatching { it.cancel() } }
        runBlocking {
            scopes.forEach { it.coroutineContext[Job]?.join() }
        }
        scopes.clear()
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harness.clean()
    }

    private fun db(): GymDatabase = harness.openInMemoryDb().also { dbs += it }

    private fun origin(root: String = ORIGIN_ROOT): ServiceOrigin = ServiceOrigin.parse(root)

    private fun grant(
        accountId: String = ACCOUNT,
        access: String = "sign-in-handoff-access-001",
        refresh: String = "sign-in-handoff-refresh-001",
    ): ServiceSession = ServiceSession(
        ServiceAccountRef(accountId, null),
        OpaqueToken(access),
        GRANT_EXPIRY,
        OpaqueToken(refresh),
        GRANT_EXPIRY + 30L * 24 * 60 * 60 * 1000,
    )

    /** Scripted Google provider: records exact nonces, never clears. */
    private class FakeIdentity : GoogleIdentityProvider {
        val nonces = mutableListOf<String>()
        var clears = 0
        var gate: (suspend (nonce: String) -> Unit)? = null

        override suspend fun credentialFor(nonce: String): OpaqueToken {
            nonces += nonce
            gate?.invoke(nonce)
            return OpaqueToken(TOKEN)
        }

        override suspend fun clearCredentialState() {
            clears += 1
        }
    }

    /** Scripted foreground auth: one SIGN_IN challenge plus typed exchanges only. */
    private class FakeForegroundAuth(
        var challengeValue: ServiceChallenge = ServiceChallenge(CHALLENGE_ID, NONCE, FIXED_CLOCK + 60_000L),
    ) : ServiceAuthApi {
        val purposes = mutableListOf<GoogleChallengePurpose>()
        val attempts = mutableListOf<GoogleExchangeAttempt>()
        var refreshCalls = 0
        var logoutCalls = 0
        var exchangeGate: (suspend (GoogleExchangeAttempt) -> Unit)? = null
        val exchangeScript = ArrayDeque<suspend (GoogleExchangeAttempt) -> ServiceSession>()

        override suspend fun challenge(purpose: GoogleChallengePurpose): ServiceChallenge {
            purposes += purpose
            return challengeValue
        }

        override suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession {
            attempts += attempt
            exchangeGate?.invoke(attempt)
            if (exchangeScript.isNotEmpty()) return exchangeScript.removeFirst().invoke(attempt)
            throw AssertionError("FakeForegroundAuth.exchange not stubbed")
        }

        override suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession {
            refreshCalls += 1
            throw AssertionError("sign-in must never call refresh")
        }

        override suspend fun logout(refreshToken: OpaqueToken) {
            logoutCalls += 1
            throw AssertionError("sign-in must never call logout")
        }
    }

    private data class Stack(
        val database: GymDatabase,
        val vault: SessionVault,
        val facadeAuth: AppSessionAuthApi,
        val transport: AppSessionTransport,
        val owner: PushRunOwner,
        val transition: AppSessionTransitionBarrier,
        val publication: ScriptedPublicationBarrier,
        val facade: AppSessionCoordinator,
        val clock: CountingClock,
    )

    private fun stack(
        database: GymDatabase = db(),
        root: String? = ORIGIN_ROOT,
        vault: SessionVault? = null,
    ): Stack {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        val realEdits = FiniteEditCoordinator(scope)
        edits += realEdits
        val realTransition = AppSessionTransitionBarrier { _: RepositorySession? -> realEdits.pauseAndDrain() }
        val publication = ScriptedPublicationBarrier()
        val realVault = vault ?: harness.newVault()
        val facadeAuth = AppSessionAuthApi()
        val transport = AppSessionTransport()
        val owner = AppSessionFixtures.owner()
        val clock = CountingClock(FIXED_CLOCK)
        val facade = AppSessionFixtures.coordinator(
            database = database,
            origin = root?.let { origin(it) },
            vault = realVault,
            auth = facadeAuth,
            transport = transport,
            runOwner = owner,
            transitionBarrier = realTransition,
            publicationBarrier = publication,
            clock = clock,
        )
        return Stack(database, realVault, facadeAuth, transport, owner, realTransition, publication, facade, clock)
    }

    private fun foreground(
        identity: GoogleIdentityProvider,
        facade: AppSessionCoordinator,
        auth: FakeForegroundAuth,
        root: String? = ORIGIN_ROOT,
        clock: CountingClock = CountingClock(FIXED_CLOCK),
        uuids: CountingUuids = CountingUuids(listOf(EXCHANGE_KEY)),
        timeoutMillis: Long = 180_000L,
    ): ForegroundSignInCoordinator = ForegroundSignInCoordinator(
        origin = root?.let { origin(it) },
        identityProvider = identity,
        handoff = facade,
        authApi = auth,
        clockMillis = { clock.read() },
        uuidProvider = { uuids.next() },
        attemptTimeoutMillis = timeoutMillis,
    )


    /**
     * Real-dispatcher test runner: the foreground budget and all gates run on real
     * threads/time because the handoff under test performs real Room/Keystore-vault
     * work on real executors. A virtual runTest clock would auto-advance through the
     * real suspensions straight into the invocation budget (TIMED_OUT) without any
     * timing oracle changing. Coordination stays gate-deterministic; no sleeps.
     */
    private fun runDeviceTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(90_000L, block)
    }

    private suspend fun readAuthority(database: GymDatabase): RecoveryRoomSnapshot =
        RoomSessionAuthority(database).read()

    private suspend fun Stack.activateExisting(
        operationId: String = OP_SETUP,
        accountGrant: ServiceSession = grant(),
    ): AppAccountSession {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val preparing = facade.acceptGrant(readAuthority(database), operationId, accountGrant)
        assertTrue("setup preparation must stay Private, was $preparing", preparing is AppSessionState.Private)
        val active = facade.completeActivation(readAuthority(database), AppSessionFixtures.STAGE)
        assertTrue("setup completion must supply Active, was $active", active is AppSessionState.Active)
        return (active as AppSessionState.Active).session
    }

    // ---- Obligation 5: first EMPTY preparation ----

    @Test
    fun firstEmptyAccountYieldsPreparingPrivateWithNoBootstrapHttpOrActivation() = runDeviceTest {
        val s = stack()
        val facadeClockBefore = s.clock.calls
        val expected = readAuthority(s.database)
        assertTrue(expected.phase == RecoveryRoomPhase.SIGNED_OUT)
        val captured = s.facade.captureForegroundSignInRequest(
            intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
            expected = expected,
            operationId = OP_FRESH,
        )
        val identity = FakeIdentity()
        val auth = FakeForegroundAuth()
        val freshGrant = grant()
        auth.exchangeScript += { _ -> freshGrant }
        val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
        try {
            val state = orchestrator.signIn(captured)
            assertTrue("foreground success must be Private, was $state", state is AppSessionState.Private)
            assertEquals(listOf(GoogleChallengePurpose.SIGN_IN), auth.purposes)
            assertEquals(listOf(NONCE), identity.nonces)
            assertEquals(0, identity.clears)
            val attempt = auth.attempts.single()
            assertEquals(CHALLENGE_ID, attempt.challengeId)
            assertEquals(TOKEN, attempt.idToken.value)
            // Exact Room evidence: revision 1, generation 1, PREPARING reservation.
            val room = readAuthority(s.database)
            assertEquals(1L, room.revision)
            assertEquals(1L, room.sessionGeneration)
            assertEquals(RecoveryRoomPhase.PREPARING, room.phase)
            assertEquals(ACCOUNT, room.cachedAccountId)
            val journal = harness.journalRow(s.database)
            assertEquals(6, journal.size)
            assertEquals("1", journal[0])
            assertEquals("1", journal[1])
            assertEquals("preparing", journal[2])
            assertEquals("https://sync-sign-in.example.test:443", journal[3])
            assertEquals(ACCOUNT, journal[4])
            assertEquals(OP_FRESH, journal[5])
            // Exact vault evidence: revision 1 PREPARED with the whole fresh grant.
            val stored = s.vault.load()
            assertEquals(1L, stored.revision)
            val record = checkNotNull(stored.record)
            assertEquals(VaultSessionPhase.PREPARED, record.phase)
            assertEquals(OP_FRESH, record.operationId)
            assertEquals(freshGrant, record.session)
            assertNull(record.pendingRefresh)
            // No bootstrap HTTP and no activation: transport idle, facade Private.
            assertEquals(0, s.transport.capturedRequests.size)
            assertTrue(s.facade.state.value is AppSessionState.Private)
            assertEquals("preparation never publishes", 0, s.publication.acquires)
            assertTrue(facadeClockBefore < s.clock.calls)
        } finally {
            orchestrator.close()
        }
    }

    // ---- Obligation 5: same-account recovery with uncertain evidence ----

    @Test
    fun sameAccountRecoveryPreservesDomainOutboxAndUncertainEvidence() = runDeviceTest {
        val s = stack()
        val handle = s.activateExisting()
        handle.repository.createCustomExercise("sign-in Keep Me", null, emptySet(), null, null, "")
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, ACCOUNT))
        assertEquals(1, frozenBefore.size)
        // Uncertain encrypted predecessor evidence on the ACTIVE record. The pending
        // refresh token must equal the predecessor stored refresh, byte for byte.
        val loaded = s.vault.load()
        val current = checkNotNull(loaded.record)
        assertEquals("sign-in-handoff-refresh-001", current.session.refreshToken.value)
        val pendingAttempt = ServiceRefreshAttempt.fromJson(PENDING_KEY, PENDING_BODY)
        assertEquals(PENDING_KEY, pendingAttempt.idempotencyKey)
        assertEquals(PENDING_BODY, pendingAttempt.requestJson)
        assertEquals(current.session.refreshToken.value, pendingAttempt.refreshToken.value)
        val uncertain = current.copy(
            pendingRefresh = VaultPendingRefresh(
                operationId = current.operationId,
                attempt = pendingAttempt,
            ),
        )
        s.vault.compareAndSet(loaded.revision, uncertain)
        val transportAfterSetup = s.transport.capturedRequests.size
        val publicationAfterSetup = s.publication.acquires

        val expected = readAuthority(s.database)
        assertTrue(expected.phase == RecoveryRoomPhase.ACTIVE)
        val captured = s.facade.captureForegroundSignInRequest(
            intent = ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION,
            expected = expected,
            operationId = OP_NEW,
        )
        val identity = FakeIdentity()
        val auth = FakeForegroundAuth()
        val freshGrant = grant(access = "sign-in-handoff-access-002", refresh = "sign-in-handoff-refresh-002")
        auth.exchangeScript += { _ -> freshGrant }
        val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
        try {
            val state = orchestrator.signIn(captured)
            assertTrue("recovery must stay Private, was $state", state is AppSessionState.Private)
            // Pending queue and domain survive with identical frozen identity.
            assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, ACCOUNT)))
            assertEquals(listOf("sign-in Keep Me"), harness.customNames(s.database, ACCOUNT))
            // Uncertain predecessor evidence retained and fenced, never dropped.
            val retained = checkNotNull(s.vault.load().record?.supersededSessions)
            assertTrue("superseded evidence must be retained, was $retained", retained.entries.isNotEmpty())
            val archived = retained.entries.single()
            assertEquals(PENDING_BODY, archived.record.pendingRefresh?.attempt?.requestJson)
            assertEquals(PENDING_KEY, archived.record.pendingRefresh?.attempt?.idempotencyKey)
            assertEquals(
                "sign-in-handoff-refresh-001",
                archived.record.pendingRefresh?.attempt?.refreshToken?.value,
            )
            // Fresh grant installs only as PREPARED credentials, never an app handle.
            val stored = checkNotNull(s.vault.load().record)
            assertEquals(VaultSessionPhase.PREPARED, stored.phase)
            assertEquals(freshGrant, stored.session)
            assertEquals(OP_NEW, stored.operationId)
            assertTrue(s.facade.state.value is AppSessionState.Private)
            try {
                handle.checkCurrent()
                fail("superseded handle must be dead")
            } catch (e: AppSessionStaleException) {
                AppSessionFixtures.assertClosed(e)
            }
            assertEquals("recovery admits no new transport beyond setup", transportAfterSetup, s.transport.capturedRequests.size)
            assertEquals("recovery never publishes", publicationAfterSetup, s.publication.acquires)
            assertEquals(0, auth.refreshCalls)
            assertEquals(0, identity.clears)
        } finally {
            orchestrator.close()
        }
    }

    // ---- Obligation 5: foreign/owner/naked/stale/invalidated refusals ----

    @Test
    fun repeatedSignInFromPreparingPreservesQueuedEditsAndUncertainCredentials() = runDeviceTest {
        repeatedPrivateSignIn(transitioning = false)
    }

    @Test
    fun repeatedSignInFromTransitioningPreservesQueuedEditsAndOlderUncertainCredentials() = runDeviceTest {
        repeatedPrivateSignIn(transitioning = true)
    }

    private suspend fun repeatedPrivateSignIn(transitioning: Boolean) {
        val s = stack()
        val handle = s.activateExisting()
        handle.repository.createCustomExercise("Interrupted sign-in kept", null, emptySet(), null, null, "")
        val outbox = harness.outboxSnapshot(s.database, ACCOUNT)
        if (transitioning) {
            // Crash gap: Room reserved a new identity; the old vault remains intact.
            RoomSessionAuthority(s.database).beginSameAccountRecovery(
                readAuthority(s.database), origin(), ACCOUNT, OP_FRESH,
            )
        } else {
            s.facade.acceptGrant(readAuthority(s.database), OP_FRESH, grant())
        }
        val loaded = s.vault.load()
        val record = checkNotNull(loaded.record)
        val uncertain = record.copy(pendingRefresh = VaultPendingRefresh(
            record.operationId, ServiceRefreshAttempt.fromJson(PENDING_KEY, PENDING_BODY),
        ))
        val source = s.vault.compareAndSet(loaded.revision, uncertain)
        s.facade.recover()
        val expected = readAuthority(s.database)
        assertEquals(if (transitioning) RecoveryRoomPhase.TRANSITIONING else RecoveryRoomPhase.PREPARING, expected.phase)
        val request = s.facade.captureForegroundSignInRequest(
            ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION, expected, OP_NEW,
        )
        val identity = FakeIdentity()
        val auth = FakeForegroundAuth()
        val fresh = grant(access = "new-private-access", refresh = "new-private-refresh")
        auth.exchangeScript += { throw com.dragosfotache.gymtracka.data.sync.api.SyncApiException.Transient() }
        auth.exchangeScript += { fresh }
        val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
        val transportCount = s.transport.capturedRequests.size
        val publications = s.publication.acquires
        try {
            assertTrue(orchestrator.signIn(request) is AppSessionState.Private)
            val after = readAuthority(s.database)
            assertEquals(RecoveryRoomPhase.PREPARING, after.phase)
            assertEquals(expected.sessionGeneration + 1L, after.sessionGeneration)
            assertEquals(OP_NEW, after.identity?.operationId)
            val stored = checkNotNull(s.vault.load().record)
            assertEquals(fresh, stored.session)
            assertEquals(VaultSessionPhase.PREPARED, stored.phase)
            assertNull(stored.pendingRefresh)
            val archived = stored.supersededSessions.entries.single()
            assertEquals(source.revision, archived.sourceRevision)
            assertEquals(uncertain.binding, archived.record.binding)
            assertEquals(uncertain.operationId, archived.record.operationId)
            assertEquals(uncertain.phase, archived.record.phase)
            assertEquals(uncertain.session, archived.record.session)
            assertEquals(uncertain.operationId, archived.record.pendingRefresh?.operationId)
            assertTrue(archived.record.supersededSessions.entries.isEmpty())
            assertEquals(PENDING_BODY, archived.record.pendingRefresh?.attempt?.requestJson)
            assertEquals(PENDING_KEY, archived.record.pendingRefresh?.attempt?.idempotencyKey)
            assertEquals(outbox, harness.outboxSnapshot(s.database, ACCOUNT))
            assertEquals(listOf("Interrupted sign-in kept"), harness.customNames(s.database, ACCOUNT))
            assertEquals(2, auth.attempts.size)
            assertSame(auth.attempts[0], auth.attempts[1])
            assertEquals(1, identity.nonces.size)
            assertEquals(0, identity.clears)
            assertEquals(0, auth.refreshCalls)
            assertEquals(transportCount, s.transport.capturedRequests.size)
            assertEquals(publications, s.publication.acquires)
            assertTrue(s.facade.state.value is AppSessionState.Private)
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun privateFreshSignInRefusesWrongAccountOriginAndRetiredRequestWithoutChangingEvidence() = runDeviceTest {
        val s = stack()
        s.facade.acceptGrant(readAuthority(s.database), OP_SETUP, grant())
        val expected = readAuthority(s.database)
        val before = s.vault.load()
        val request = s.facade.captureForegroundSignInRequest(
            ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION, expected, OP_NEW,
        )
        try {
            s.facade.acceptForegroundGrant(request, grant(accountId = OTHER_ACCOUNT))
            fail("foreign account must refuse")
        } catch (e: AppSessionException) {
            AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
        }
        val foreign = stack(database = s.database, root = OTHER_ORIGIN_ROOT, vault = s.vault)
        try {
            foreign.facade.captureForegroundSignInRequest(
                ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION, expected, OP_NEW,
            )
            fail("foreign origin must refuse")
        } catch (e: AppSessionException) {
            AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
        }
        s.facade.invalidate()
        try {
            s.facade.acceptForegroundGrant(request, grant(access = "fresh", refresh = "fresh"))
            fail("retired request must refuse")
        } catch (_: AppSessionStaleException) {
        }
        assertEquals(expected, readAuthority(s.database))
        assertEquals(before, s.vault.load())
        assertTrue(s.transport.capturedRequests.isEmpty())
    }

    @Test
    fun savedGrantResumeStillRequiresExactCredentialsAndKeepsReservation() = runDeviceTest {
        val s = stack()
        s.facade.acceptGrant(readAuthority(s.database), OP_SETUP, grant())
        val expected = readAuthority(s.database)
        val before = s.vault.load()
        assertTrue(s.facade.acceptGrant(expected, OP_SETUP, grant()) is AppSessionState.Private)
        try {
            s.facade.acceptGrant(expected, OP_SETUP, grant(access = "fresh", refresh = "fresh"))
            fail("exact saved resume cannot install a different session")
        } catch (e: AppSessionException) {
            AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
        }
        assertEquals(expected, readAuthority(s.database))
        assertEquals(before, s.vault.load())
        assertTrue(s.transport.capturedRequests.isEmpty())
    }

    @Test
    fun foreignAccountGrantRefusesUnchanged() = runDeviceTest {
        val s = stack()
        val handle = s.activateExisting()
        handle.repository.createCustomExercise("sign-in Mine", null, emptySet(), null, null, "")
        val journalBefore = harness.journalRow(s.database)
        val vaultBefore = s.vault.load()
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, ACCOUNT))
        val expected = readAuthority(s.database)
        val captured = s.facade.captureForegroundSignInRequest(
            intent = ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION,
            expected = expected,
            operationId = OP_NEW,
        )
        val identity = FakeIdentity()
        val auth = FakeForegroundAuth()
        auth.exchangeScript += { _ -> grant(accountId = OTHER_ACCOUNT) }
        val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
        try {
            try {
                orchestrator.signIn(captured)
                fail("foreign grant must throw")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
            }
            handle.checkCurrent()
            assertEquals(journalBefore, harness.journalRow(s.database))
            assertEquals(vaultBefore, s.vault.load())
            assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, ACCOUNT)))
            assertEquals(listOf("sign-in Mine"), harness.customNames(s.database, ACCOUNT))
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun mismatchedOriginRequestRefusesBeforeExchange() = runDeviceTest {
        val s = stack()
        val expected = readAuthority(s.database)
        val captured = s.facade.captureForegroundSignInRequest(
            intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
            expected = expected,
            operationId = OP_FRESH,
        )
        val identity = FakeIdentity()
        val auth = FakeForegroundAuth()
        auth.exchangeScript += { _ -> grant() }
        // Orchestrator configured for a foreign origin: request origin mismatch.
        val orchestrator = foreground(identity, s.facade, auth, root = OTHER_ORIGIN_ROOT, clock = s.clock)
        try {
            try {
                orchestrator.signIn(captured)
                fail("mismatched origin must throw")
            } catch (e: ForegroundSignInException) {
                assertEquals(ForegroundSignInFailure.INVALID_REQUEST, e.reason)
            }
            assertEquals(0, auth.attempts.size)
            assertEquals(0L, s.vault.load().revision)
            assertEquals(emptyList<String?>(), harness.journalRow(s.database))
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun mismatchedOwnerFacadeRefusesNakedAndForeignCaptureUnchanged() = runDeviceTest {
        val database = db()
        val sharedVault = harness.newVault()
        val first = stack(database = database, vault = sharedVault)
        val second = stack(database = database, vault = sharedVault)
        // Naked four-argument request (never captured) against the real facade.
        run {
            val naked = ForegroundSignInRequest(
                origin = origin(),
                intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
                expected = readAuthority(database),
                operationId = OP_FRESH,
            )
            val identity = FakeIdentity()
            val auth = FakeForegroundAuth()
            auth.exchangeScript += { _ -> grant() }
            val orchestrator = foreground(identity, first.facade, auth, clock = first.clock)
            try {
                try {
                    orchestrator.signIn(naked)
                    fail("naked request must throw")
                } catch (e: AppSessionException) {
                    AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
                }
                assertEquals(0L, first.vault.load().revision)
            } finally {
                orchestrator.close()
            }
        }
        // Owner mismatch: captured by the first facade, handed to the second.
        run {
            val captured = first.facade.captureForegroundSignInRequest(
                intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
                expected = readAuthority(database),
                operationId = OP_FRESH,
            )
            val identity = FakeIdentity()
            val auth = FakeForegroundAuth()
            auth.exchangeScript += { _ -> grant() }
            val orchestrator = foreground(identity, second.facade, auth, clock = second.clock)
            try {
                try {
                    orchestrator.signIn(captured)
                    fail("owner mismatch must throw")
                } catch (e: AppSessionException) {
                    AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
                }
                assertEquals(0L, second.vault.load().revision)
                assertEquals(emptyList<String?>(), harness.journalRow(database))
            } finally {
                orchestrator.close()
            }
        }
    }

    @Test
    fun staleExpectationAndInvalidationAtUnchangedRoomRefuseUnchanged() = runDeviceTest {
        // Stale Room expectation: a newer reservation moves first.
        run {
            val s = stack()
            s.activateExisting()
            val staleExpected = readAuthority(s.database)
            val captured = s.facade.captureForegroundSignInRequest(
                intent = ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION,
                expected = staleExpected,
                operationId = OP_NEW,
            )
            // Newer reservation advances the journal before the tested handoff.
            s.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
            s.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            s.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            s.facade.acceptGrant(
                readAuthority(s.database),
                OP_RIVAL,
                grant(access = "sign-in-handoff-access-rival", refresh = "sign-in-handoff-refresh-rival"),
            )
            val journalAfterNewer = harness.journalRow(s.database)
            val identity = FakeIdentity()
            val auth = FakeForegroundAuth()
            auth.exchangeScript += { _ -> grant(access = "sign-in-handoff-access-delayed") }
            val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
            try {
                try {
                    orchestrator.signIn(captured)
                    fail("delayed grant must throw stale")
                } catch (e: AppSessionStaleException) {
                    AppSessionFixtures.assertClosed(e)
                }
                assertEquals(journalAfterNewer, harness.journalRow(s.database))
            } finally {
                orchestrator.close()
            }
        }
        // Process invalidation at unchanged Room: fence captured, Room identical.
        run {
            val s = stack()
            val expected = readAuthority(s.database)
            val journalBefore = harness.journalRow(s.database)
            val captured = s.facade.captureForegroundSignInRequest(
                intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
                expected = expected,
                operationId = OP_FRESH,
            )
            s.facade.invalidate()
            assertTrue(s.facade.state.value is AppSessionState.PrivacyLocked)
            val identity = FakeIdentity()
            val auth = FakeForegroundAuth()
            auth.exchangeScript += { _ -> grant() }
            val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
            try {
                try {
                    orchestrator.signIn(captured)
                    fail("invalidated fence must throw stale")
                } catch (e: AppSessionStaleException) {
                    AppSessionFixtures.assertClosed(e)
                }
                assertEquals("fencing is process-local", journalBefore, harness.journalRow(s.database))
                assertEquals(0L, s.vault.load().revision)
            } finally {
                orchestrator.close()
            }
        }
    }

    @Test
    fun invalidCaptureThrowsSynchronouslyWithoutIo() = runDeviceTest {
        val s = stack()
        // INITIAL against an ACTIVE expectation is an invalid context.
        s.activateExisting()
        val active = readAuthority(s.database)
        val clockBefore = s.clock.calls
        val transportAfterSetup = s.transport.capturedRequests.size
        val publicationAfterSetup = s.publication.acquires
        try {
            s.facade.captureForegroundSignInRequest(
                intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
                expected = active,
                operationId = OP_NEW,
            )
            fail("invalid capture must throw")
        } catch (e: AppSessionException) {
            AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
        }
        assertEquals("capture performs no clock I/O", clockBefore, s.clock.calls)
        assertEquals("capture admits no transport", transportAfterSetup, s.transport.capturedRequests.size)
        assertEquals("capture never publishes", publicationAfterSetup, s.publication.acquires)
    }

    // ---- Obligation 5: cancelled/retired exchange preservation ----

    @Test
    fun cancelledAndRetiredExchangeResultsPreserveStoresWithoutHandoff() = runDeviceTest {
        // Caller cancellation while the exchange is admitted.
        run {
            val s = stack()
            val expected = readAuthority(s.database)
            val captured = s.facade.captureForegroundSignInRequest(
                intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
                expected = expected,
                operationId = OP_FRESH,
            )
            val identity = FakeIdentity()
            val auth = FakeForegroundAuth()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            auth.exchangeGate = {
                entered.complete(Unit)
                release.await()
            }
            auth.exchangeScript += { _ -> grant() }
            val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
            try {
                supervisorScope {
                    val done = CompletableDeferred<Throwable>()
                    val job = async(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            orchestrator.signIn(captured)
                            done.complete(AssertionError("expected cancellation"))
                        } catch (e: Throwable) {
                            done.complete(e)
                        }
                    }
                    entered.await()
                    val probe = CancellationException("sign-in-handoff-cancel")
                    job.cancel(probe)
                    release.complete(Unit)
                    assertSame(probe, done.await())
                    job.join()
                }
                assertEquals(0L, s.vault.load().revision)
                assertEquals(emptyList<String?>(), harness.journalRow(s.database))
                assertEquals(0, identity.clears)
            } finally {
                release.complete(Unit)
                orchestrator.close()
            }
        }
        // Retirement while the exchange is admitted, then a later RETIRED refusal.
        run {
            val s = stack()
            val expected = readAuthority(s.database)
            val captured = s.facade.captureForegroundSignInRequest(
                intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
                expected = expected,
                operationId = OP_FRESH,
            )
            val identity = FakeIdentity()
            val auth = FakeForegroundAuth()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            auth.exchangeGate = {
                entered.complete(Unit)
                release.await()
            }
            auth.exchangeScript += { _ -> grant() }
            val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
            val done = CompletableDeferred<Throwable>()
            supervisorScope {
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        orchestrator.signIn(captured)
                        done.complete(AssertionError("expected retired cancellation"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                entered.await()
                orchestrator.close()
                release.complete(Unit)
                val outcome = done.await()
                assertTrue("in-flight retirement yields cancellation, was $outcome", outcome is CancellationException)
                job.join()
            }
            assertEquals(0L, s.vault.load().revision)
            try {
                orchestrator.signIn(captured)
                fail("retired orchestrator must throw")
            } catch (e: ForegroundSignInException) {
                assertEquals(ForegroundSignInFailure.RETIRED, e.reason)
            }
            assertEquals(0, identity.clears)
            orchestrator.close()
        }
    }

    // ---- Obligation 5: representative simultaneous attempts ----

    @Test
    fun representativeSimultaneousAttemptsCannotInstallTwice() = runDeviceTest {
        val s = stack()
        val expected = readAuthority(s.database)
        val first = s.facade.captureForegroundSignInRequest(
            intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
            expected = expected,
            operationId = OP_FRESH,
        )
        val second = s.facade.captureForegroundSignInRequest(
            intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
            expected = expected,
            operationId = OP_RIVAL,
        )
        val firstAuth = FakeForegroundAuth()
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        firstAuth.exchangeGate = {
            firstEntered.complete(Unit)
            firstRelease.await()
        }
        firstAuth.exchangeScript += { _ -> grant() }
        val secondAuth = FakeForegroundAuth()
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        secondAuth.exchangeGate = {
            secondEntered.complete(Unit)
            secondRelease.await()
        }
        secondAuth.exchangeScript += { _ -> grant(access = "sign-in-handoff-access-rival") }
        val firstOrchestrator = foreground(FakeIdentity(), s.facade, firstAuth, clock = s.clock)
        val secondOrchestrator = foreground(
            FakeIdentity(),
            s.facade,
            secondAuth,
            clock = s.clock,
            uuids = CountingUuids(listOf("e11b0014-4444-4444-8444-444444444444")),
        )
        try {
            supervisorScope {
                val winner = async(start = CoroutineStart.UNDISPATCHED) { firstOrchestrator.signIn(first) }
                val rival = async(start = CoroutineStart.UNDISPATCHED) { secondOrchestrator.signIn(second) }
                // Both invocations genuinely in flight on the same expectation, each
                // parked at its own exchange send with one attempt recorded.
                firstEntered.await()
                secondEntered.await()
                assertEquals(1, firstAuth.attempts.size)
                assertEquals(1, secondAuth.attempts.size)
                // Deterministic winner: release first; it installs alone.
                firstRelease.complete(Unit)
                assertTrue(winner.await() is AppSessionState.Private)
                // Rival release then refuses at the real handoff: same-account/same-origin
                // captures against the now-mismatched Room expectation are stale, never
                // a second install. Owner/naked/foreign-context refusals keep their own
                // IDENTITY_MISMATCH category elsewhere.
                secondRelease.complete(Unit)
                try {
                    rival.await()
                    fail("rival attempt must not install twice")
                } catch (e: AppSessionStaleException) {
                    AppSessionFixtures.assertClosed(e)
                }
            }
            val stored = checkNotNull(s.vault.load().record)
            assertEquals(1L, s.vault.load().revision)
            assertEquals(OP_FRESH, stored.operationId)
            assertEquals("sign-in-handoff-access-001", stored.session.accessToken.value)
            val journal = harness.journalRow(s.database)
            assertEquals(6, journal.size)
            assertEquals("1", journal[0])
            assertEquals("1", journal[1])
            assertEquals("preparing", journal[2])
            assertEquals("https://sync-sign-in.example.test:443", journal[3])
            assertEquals(ACCOUNT, journal[4])
            assertEquals(OP_FRESH, journal[5])
        } finally {
            firstOrchestrator.close()
            secondOrchestrator.close()
        }
    }

    @Test
    fun identityFailureConsumesNoHandoffAndClosesSafe() = runDeviceTest {
        val s = stack()
        val expected = readAuthority(s.database)
        val captured = s.facade.captureForegroundSignInRequest(
            intent = ForegroundSignInIntent.INITIAL_SIGN_IN,
            expected = expected,
            operationId = OP_FRESH,
        )
        val identity = object : GoogleIdentityProvider {
            override suspend fun credentialFor(nonce: String): OpaqueToken =
                throw GoogleIdentityException(GoogleIdentityFailureReason.USER_CANCELLED)

            override suspend fun clearCredentialState() {
                fail("no automatic clear on identity failure")
            }
        }
        val auth = FakeForegroundAuth()
        val orchestrator = foreground(identity, s.facade, auth, clock = s.clock)
        try {
            try {
                orchestrator.signIn(captured)
                fail("identity failure must throw")
            } catch (e: ForegroundSignInException) {
                assertEquals(ForegroundSignInFailure.IDENTITY_FAILED, e.reason)
                assertEquals(GoogleIdentityFailureReason.USER_CANCELLED, e.identityReason)
            }
            assertEquals(0, auth.attempts.size)
            assertEquals(0L, s.vault.load().revision)
        } finally {
            orchestrator.close()
        }
    }
}
