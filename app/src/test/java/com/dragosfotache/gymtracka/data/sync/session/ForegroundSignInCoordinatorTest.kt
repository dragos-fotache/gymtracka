package com.dragosfotache.gymtracka.data.sync.session

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
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R11b independent QA: bounded foreground exchange and guarded grant handoff (JVM).
 *
 * Derived ONLY from docs/remaining-work/R11b-task.md (normative new-API contract,
 * intent/process/durable guards, invocation ordering/retry/lifetime, safe failures)
 * plus coordinator-supplied API/harness facts and QA-owned independent literals.
 * No production declarations/source/diffs, Worker work, generated/decompiled
 * production artifacts or production LSP inspected.
 *
 * All credential-adjacent literals below are this slice's own synthetic values;
 * expected exchange bodies are asserted via independent JSONObject parsing, never
 * the production encoder. Deterministic virtual time/gates only, no sleeps.
 *
 * Covers R11b obligations 1-4 (ordering/exact capture, expiry equality/uncertain
 * replay, permanent/no-retry/validation closure, budget/busy/cancel/retire/late
 * seams). Focused Room/vault/real-barrier handoff lives in androidTest
 * ForegroundSignInHandoffTest; whole R09 matrices are reused, not repeated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSignInCoordinatorTest {

    private companion object {
        const val ORIGIN_ROOT = "https://sync-r11b.example.test"
        const val OTHER_ORIGIN_ROOT = "https://other-r11b.example.test"
        const val ACCOUNT = "b11b0001-1111-4111-8111-111111111111"
        const val OTHER_ACCOUNT = "b11b0002-2222-4222-8222-222222222222"
        const val CHALLENGE_ID = "a11b0001-1111-4111-8111-111111111111"
        const val NONCE = "r11b-nonce-001"
        const val TOKEN = "r11b-google-token-001"
        const val EXCHANGE_KEY = "c11b0001-1111-4111-8111-111111111111"
        const val EXCHANGE_KEY_2 = "c11b0002-2222-4222-8222-222222222222"
        const val OP_INITIAL = "d11b0001-1111-4111-8111-111111111111"
        const val OP_REAUTH = "d11b0002-2222-4222-8222-222222222222"
        const val OP_OTHER = "d11b0003-3333-4333-8333-333333333333"
        const val NOW = 1_700_000_000_000L
        const val GRANT_EXPIRY = 1_700_000_900_000L
        const val CHALLENGE_TTL = 60_000L
    }

    private fun origin(root: String = ORIGIN_ROOT): ServiceOrigin = ServiceOrigin.parse(root)

    private fun grant(
        accountId: String = ACCOUNT,
        access: String = "r11b-access-001",
        refresh: String = "r11b-refresh-001",
        accessExpires: Long = GRANT_EXPIRY,
    ): ServiceSession = ServiceSession(
        ServiceAccountRef(accountId, null),
        OpaqueToken(access),
        accessExpires,
        OpaqueToken(refresh),
        GRANT_EXPIRY + 30L * 24 * 60 * 60 * 1000,
    )

    private fun signedOut(): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = 0L,
        sessionGeneration = 0L,
        cachedAccountId = null,
        phase = RecoveryRoomPhase.SIGNED_OUT,
        identity = null,
        baselineComplete = false,
    )

    private fun cachedActive(
        accountId: String = ACCOUNT,
        generation: Long = 3L,
        operationId: String = OP_REAUTH,
        root: String = ORIGIN_ROOT,
    ): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = 7L,
        sessionGeneration = generation,
        cachedAccountId = accountId,
        phase = RecoveryRoomPhase.ACTIVE,
        identity = RecoveryIdentity(
            binding = ServiceSessionBinding(origin(root), accountId, generation),
            operationId = operationId,
        ),
        baselineComplete = true,
    )

    private fun request(
        intent: ForegroundSignInIntent = ForegroundSignInIntent.INITIAL_SIGN_IN,
        expected: RecoveryRoomSnapshot = signedOut(),
        operationId: String = OP_INITIAL,
        root: String = ORIGIN_ROOT,
    ): ForegroundSignInRequest = ForegroundSignInRequest(
        origin = origin(root),
        intent = intent,
        expected = expected,
        operationId = operationId,
    )

    private fun challenge(
        challengeId: String = CHALLENGE_ID,
        nonce: String = NONCE,
        expiresAtMillis: Long = NOW + CHALLENGE_TTL,
    ): ServiceChallenge = ServiceChallenge(challengeId, nonce, expiresAtMillis)

    /** Scripted Google provider: records exact nonces, never clears. */
    private class FakeIdentity(
        var token: OpaqueToken = OpaqueToken(TOKEN),
    ) : GoogleIdentityProvider {
        val nonces = mutableListOf<String>()
        var clears = 0
        var gate: (suspend (nonce: String) -> Unit)? = null
        val script = ArrayDeque<suspend (String) -> OpaqueToken>()

        override suspend fun credentialFor(nonce: String): OpaqueToken {
            nonces += nonce
            gate?.invoke(nonce)
            if (script.isNotEmpty()) return script.removeFirst().invoke(nonce)
            return token
        }

        override suspend fun clearCredentialState() {
            clears += 1
        }
    }

    /** Scripted ServiceAuthApi: records purposes/attempts, refresh/logout never used. */
    private class FakeAuth(
        var challengeValue: ServiceChallenge = ServiceChallenge(CHALLENGE_ID, NONCE, NOW + CHALLENGE_TTL),
    ) : ServiceAuthApi {
        val purposes = mutableListOf<GoogleChallengePurpose>()
        val attempts = mutableListOf<GoogleExchangeAttempt>()
        var refreshCalls = 0
        var logoutCalls = 0
        var challengeGate: (suspend () -> Unit)? = null
        var exchangeGate: (suspend (GoogleExchangeAttempt) -> Unit)? = null
        val challengeScript = ArrayDeque<suspend () -> ServiceChallenge>()
        val exchangeScript = ArrayDeque<suspend (GoogleExchangeAttempt) -> ServiceSession>()

        override suspend fun challenge(purpose: GoogleChallengePurpose): ServiceChallenge {
            purposes += purpose
            challengeGate?.invoke()
            if (challengeScript.isNotEmpty()) return challengeScript.removeFirst().invoke()
            return challengeValue
        }

        override suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession {
            attempts += attempt
            exchangeGate?.invoke(attempt)
            if (exchangeScript.isNotEmpty()) return exchangeScript.removeFirst().invoke(attempt)
            throw AssertionError("FakeAuth.exchange not stubbed")
        }

        override suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession {
            refreshCalls += 1
            throw AssertionError("R11b must never call refresh")
        }

        override suspend fun logout(refreshToken: OpaqueToken) {
            logoutCalls += 1
            throw AssertionError("R11b must never call logout")
        }
    }

    /** Scripted handoff: records exact request/session identity; defaults to Private preparation. */
    private class FakeHandoff(
        var result: AppSessionState? = null,
    ) : ForegroundGrantHandoff {
        val requests = mutableListOf<ForegroundSignInRequest>()
        val sessions = mutableListOf<ServiceSession>()
        var gate: (suspend (ForegroundSignInRequest, ServiceSession) -> Unit)? = null
        var fault: Throwable? = null
        /** Completed with the computed result AFTER it is fixed; never suspends inside handoff. */
        val computed = CompletableDeferred<AppSessionState>()

        override suspend fun acceptForegroundGrant(
            request: ForegroundSignInRequest,
            session: ServiceSession,
        ): AppSessionState {
            requests += request
            sessions += session
            gate?.invoke(request, session)
            fault?.let { throw it }
            val decided = result
                ?: AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, request.expected)
            computed.complete(decided)
            return decided
        }
    }

    private fun coordinator(
        identity: FakeIdentity,
        handoff: FakeHandoff,
        auth: FakeAuth,
        root: String? = ORIGIN_ROOT,
        clock: MutableClock = MutableClock(NOW),
        uuids: MutableUuids = MutableUuids(listOf(EXCHANGE_KEY)),
        timeoutMillis: Long = 180_000L,
    ): ForegroundSignInCoordinator = ForegroundSignInCoordinator(
        origin = root?.let { origin(it) },
        identityProvider = identity,
        handoff = handoff,
        authApi = auth,
        clockMillis = { clock.read() },
        uuidProvider = { uuids.next() },
        attemptTimeoutMillis = timeoutMillis,
    )

    private class MutableClock(var now: Long) {
        var calls = 0
        fun read(): Long {
            calls += 1
            return now
        }
    }

    private class MutableUuids(private val values: List<String>) {
        private var index = 0
        var calls = 0
        fun next(): String {
            calls += 1
            return values[index++ % values.size]
        }
    }

    /**
     * Test-only manually driven dispatcher: [dispatch] enqueues Runnables without
     * running them; [runNext] removes and runs exactly ONE Runnable; [drainUntil]
     * drives one runnable at a time until [predicate] holds and fails loudly after a
     * bounded number of steps without progress. Lets tests freeze the exact queued
     * caller-resume runnable deterministically: no sleeps, no virtual clock.
     */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val queue = ArrayDeque<Pair<CoroutineContext, Runnable>>()

        fun pendingCount(): Int = synchronized(lock) { queue.size }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(lock) { queue.addLast(context to block) }
        }

        fun runNext(): Boolean {
            val next = synchronized(lock) { if (queue.isEmpty()) null else queue.removeFirst() }
                ?: return false
            next.second.run()
            return true
        }

        fun drainUntil(maxSteps: Int = 200, predicate: () -> Boolean) {
            var idleRounds = 0
            repeat(maxSteps) {
                if (predicate()) return
                if (runNext()) idleRounds = 0 else idleRounds++
                if (idleRounds > 10) throw AssertionError("dispatcher idle with predicate unmet")
            }
            if (!predicate()) throw AssertionError("predicate unmet after $maxSteps steps")
        }
    }

    private fun assertForegroundFailure(
        expected: ForegroundSignInFailure,
        error: Throwable,
        secrets: List<String> = defaultSecrets(),
    ): ForegroundSignInException {
        assertTrue("expected ForegroundSignInException($expected) but was $error", error is ForegroundSignInException)
        val failure = error as ForegroundSignInException
        assertEquals(expected, failure.reason)
        assertNull("no cause chain", failure.cause)
        assertTrue("no suppressed entries", failure.suppressed.isEmpty())
        val visible = (failure.message ?: "") + failure.toString()
        assertTrue("fixed safe message must be non-blank", visible.isNotBlank())
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("safe failure must not reveal <$secret>", visible.contains(secret))
        }
        return failure
    }

    private fun defaultSecrets(): List<String> = listOf(
        ACCOUNT, OTHER_ACCOUNT, CHALLENGE_ID, NONCE, TOKEN, EXCHANGE_KEY, EXCHANGE_KEY_2,
        OP_INITIAL, OP_REAUTH, OP_OTHER, ORIGIN_ROOT, OTHER_ORIGIN_ROOT,
        "r11b-access-001", "r11b-refresh-001", "r11b-hostile-secret",
    )

    private suspend fun expectFailure(
        expected: ForegroundSignInFailure,
        block: suspend () -> Unit,
    ): ForegroundSignInException {
        try {
            block()
            fail("expected ForegroundSignInException($expected)")
        } catch (e: ForegroundSignInException) {
            return assertForegroundFailure(expected, e)
        }
        throw AssertionError("unreachable")
    }

    // ---- Obligation 1: ordering and exact capture ----

    @Test
    fun constructionIsInert() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        val clock = MutableClock(NOW)
        val uuids = MutableUuids(listOf(EXCHANGE_KEY))
        val c = coordinator(identity, handoff, auth, clock = clock, uuids = uuids)
        assertEquals(0, identity.nonces.size)
        assertEquals(0, identity.clears)
        assertEquals(0, auth.purposes.size)
        assertEquals(0, auth.attempts.size)
        assertEquals(0, handoff.requests.size)
        assertEquals(0, clock.calls)
        assertEquals(0, uuids.calls)
        c.close()
    }

    @Test
    fun happyPathUsesOneSignInChallengeOneProviderOneTypedAttempt() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val expectedGrant = grant()
        val auth = FakeAuth(challenge(nonce = NONCE))
        auth.exchangeScript += { attempt -> expectedGrant }
        val c = coordinator(identity, handoff, auth)
        try {
            val req = request()
            val state = c.signIn(req)
            assertTrue("success must be Private, was $state", state is AppSessionState.Private)
            assertEquals(listOf(GoogleChallengePurpose.SIGN_IN), auth.purposes)
            assertEquals("Google called once with the exact nonce", listOf(NONCE), identity.nonces)
            assertEquals(0, identity.clears)
            val attempt = auth.attempts.single()
            assertEquals(EXCHANGE_KEY, attempt.idempotencyKey)
            assertEquals(CHALLENGE_ID, attempt.challengeId)
            assertEquals(TOKEN, attempt.idToken.value)
            val parsed = JSONObject(attempt.requestJson)
            assertEquals(2, parsed.length())
            assertEquals(CHALLENGE_ID, parsed.getString("challengeId"))
            assertEquals(TOKEN, parsed.getString("idToken"))
            assertFalse("key is header data, never body", attempt.requestJson.contains(EXCHANGE_KEY))
            assertSame("handoff receives the original grant instance", expectedGrant, handoff.sessions.single())
            assertSame("handoff receives the original request", req, handoff.requests.single())
            assertEquals(0, auth.refreshCalls)
            assertEquals(0, auth.logoutCalls)
        } finally {
            c.close()
        }
    }

    @Test
    fun lostResponseRetryReusesSameAttemptAndHonorsRequiredDelays() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val expectedGrant = grant()
        val auth = FakeAuth()
        auth.exchangeScript += { _ -> throw SyncApiException.Transient() }
        auth.exchangeScript += { _ -> throw SyncApiException.Transient(retryAfterMillis = 1_500L) }
        auth.exchangeScript += { _ -> expectedGrant }
        val c = coordinator(identity, handoff, auth)
        try {
            val job = async(start = CoroutineStart.UNDISPATCHED) { c.signIn(request()) }
            runCurrent()
            assertEquals("first send immediate", 1, auth.attempts.size)
            advanceTimeBy(999L)
            runCurrent()
            assertEquals("delay before send two is max(1000, hint)", 1, auth.attempts.size)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, auth.attempts.size)
            // First retry waits max(1000, no hint)=1000; second retry waits max(2000, hint 1500)=2000.
            advanceTimeBy(1_999L)
            runCurrent()
            assertEquals(2, auth.attempts.size)
            advanceTimeBy(1L)
            runCurrent()
            val state = job.await()
            assertTrue(state is AppSessionState.Private)
            assertEquals(3, auth.attempts.size)
            assertSame(auth.attempts[0], auth.attempts[1])
            assertSame(auth.attempts[0], auth.attempts[2])
            assertEquals(auth.attempts[0].requestJson, auth.attempts[1].requestJson)
            assertEquals(auth.attempts[0].requestJson, auth.attempts[2].requestJson)
            assertEquals(EXCHANGE_KEY, auth.attempts[2].idempotencyKey)
            assertEquals("single Google acquisition across retries", listOf(NONCE), identity.nonces)
            assertEquals("single challenge across retries", 1, auth.purposes.size)
            assertSame(expectedGrant, handoff.sessions.single())
            assertEquals(0, identity.clears)
            assertEquals(0, auth.refreshCalls)
        } finally {
            c.close()
        }
    }

    @Test
    fun largerHintDelaysSecondSendBeyondOneSecond() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        auth.exchangeScript += { _ -> throw SyncApiException.Transient(retryAfterMillis = 5_000L) }
        auth.exchangeScript += { _ -> grant() }
        val c = coordinator(identity, handoff, auth)
        try {
            val job = async(start = CoroutineStart.UNDISPATCHED) { c.signIn(request()) }
            runCurrent()
            assertEquals(1, auth.attempts.size)
            advanceTimeBy(4_999L)
            runCurrent()
            assertEquals("larger hint honored, never early to fit budget", 1, auth.attempts.size)
            advanceTimeBy(1L)
            runCurrent()
            job.await()
            assertEquals(2, auth.attempts.size)
            assertSame(auth.attempts[0], auth.attempts[1])
        } finally {
            c.close()
        }
    }

    @Test
    fun thirdTransientExhaustsWithoutFurtherSends() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        repeat(3) { auth.exchangeScript += { _ -> throw SyncApiException.Transient() } }
        val c = coordinator(identity, handoff, auth)
        val done = CompletableDeferred<Throwable>()
        try {
            supervisorScope {
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        c.signIn(request())
                        done.complete(AssertionError("expected RETRY_EXHAUSTED"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                advanceTimeBy(10_000L)
                job.join()
            }
            val error = done.await()
            assertTrue("expected ForegroundSignInException but was $error", error is ForegroundSignInException)
            assertForegroundFailure(ForegroundSignInFailure.RETRY_EXHAUSTED, error)
            assertEquals(3, auth.attempts.size)
            assertSame(auth.attempts[0], auth.attempts[1])
            assertSame(auth.attempts[0], auth.attempts[2])
            assertEquals(0, handoff.requests.size)
            assertEquals(listOf(NONCE), identity.nonces)
        } finally {
            c.close()
        }
    }

    // ---- Obligation 2: expiry equality and uncertain replay ----

    @Test
    fun challengeExpiryEqualityBeforeProviderRefusesWithoutEffects() = runTest {
        val clock = MutableClock(NOW)
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth(challenge(expiresAtMillis = NOW))
        val uuids = MutableUuids(listOf(EXCHANGE_KEY))
        val c = coordinator(identity, handoff, auth, clock = clock, uuids = uuids)
        try {
            val error = expectFailure(ForegroundSignInFailure.CHALLENGE_EXPIRED) { c.signIn(request()) }
            assertForegroundFailure(ForegroundSignInFailure.CHALLENGE_EXPIRED, error)
            assertEquals(1, auth.purposes.size)
            assertEquals("no Google acquisition on expired challenge", 0, identity.nonces.size)
            assertEquals(0, auth.attempts.size)
            assertEquals(0, handoff.requests.size)
        } finally {
            c.close()
        }
    }

    @Test
    fun challengeExpiryEqualityBeforeFirstSendRefusesAfterSingleAcquisition() = runTest {
        val clock = MutableClock(NOW)
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth(challenge(expiresAtMillis = NOW + 1_000L))
        identity.gate = { clock.now = NOW + 1_000L }
        val c = coordinator(identity, handoff, auth, clock = clock)
        try {
            expectFailure(ForegroundSignInFailure.CHALLENGE_EXPIRED) { c.signIn(request()) }
            assertEquals(listOf(NONCE), identity.nonces)
            assertEquals("no exchange after expiry", 0, auth.attempts.size)
            assertEquals(0, handoff.requests.size)
            assertEquals(0, identity.clears)
        } finally {
            c.close()
        }
    }

    @Test
    fun uncertainRetryBeyondChallengeExpiryReturnsExpiredGrantUnchanged() = runTest {
        val clock = MutableClock(NOW)
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth(challenge(expiresAtMillis = NOW + 500L))
        val expiredGrant = grant(accessExpires = NOW - 1_000L)
        auth.exchangeScript += { _ -> throw SyncApiException.Transient() }
        auth.exchangeScript += { _ -> expiredGrant }
        val c = coordinator(identity, handoff, auth, clock = clock)
        try {
            val job = async(start = CoroutineStart.UNDISPATCHED) { c.signIn(request()) }
            runCurrent()
            // Let the required 1000ms retry delay push the retry beyond challenge expiry.
            clock.now = NOW + 5_000L
            advanceTimeBy(5_000L)
            val state = job.await()
            assertTrue(state is AppSessionState.Private)
            assertEquals("uncertain replay still sends twice", 2, auth.attempts.size)
            assertSame(auth.attempts[0], auth.attempts[1])
            val delivered = handoff.sessions.single()
            assertSame("original expired grant preserved, not renewed", expiredGrant, delivered)
            assertEquals(NOW - 1_000L, delivered.accessExpiresAtMillis)
            assertEquals("no second Google invocation for replay", listOf(NONCE), identity.nonces)
            assertEquals(0, auth.refreshCalls)
            assertEquals(0, auth.logoutCalls)
            assertEquals(0, identity.clears)
        } finally {
            c.close()
        }
    }

    // ---- Obligation 3: no-retry, validation, closure ----

    @Test
    fun permanentRejectionReauthTooLargeAndMalformedDoNotRetry() = runTest {
        val permanents: List<SyncApiException> = listOf(
            SyncApiException.RequestRejected("invalid_google_credential"),
            SyncApiException.RequestTooLarge(),
            SyncApiException.ReauthenticationRequired(),
        )
        for (fault in permanents) {
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            auth.exchangeScript += { _ -> throw fault }
            val c = coordinator(identity, handoff, auth)
            try {
                val error = expectFailure(ForegroundSignInFailure.REQUEST_REJECTED) { c.signIn(request()) }
                assertForegroundFailure(ForegroundSignInFailure.REQUEST_REJECTED, error)
                assertEquals("no retry on permanent $fault", 1, auth.attempts.size)
                assertEquals(0, handoff.requests.size)
            } finally {
                c.close()
            }
        }
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        auth.exchangeScript += { _ -> throw SyncApiException.MalformedResponse() }
        val c = coordinator(identity, handoff, auth)
        try {
            expectFailure(ForegroundSignInFailure.MALFORMED_RESPONSE) { c.signIn(request()) }
            assertEquals(1, auth.attempts.size)
            assertEquals(0, handoff.requests.size)
        } finally {
            c.close()
        }
    }

    @Test
    fun challengeTransientMapsToTransientFailureWithoutProviderOrExchange() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        auth.challengeScript += { throw SyncApiException.Transient(retryAfterMillis = 1_000L) }
        val c = coordinator(identity, handoff, auth)
        try {
            expectFailure(ForegroundSignInFailure.TRANSIENT_FAILURE) { c.signIn(request()) }
            assertEquals(1, auth.purposes.size)
            assertEquals("challenge failure does not reacquire Google", 0, identity.nonces.size)
            assertEquals(0, auth.attempts.size)
            assertEquals(0, handoff.requests.size)
        } finally {
            c.close()
        }
    }

    @Test
    fun classifiedIdentityFailuresMapToIdentityFailedWithClosedReason() = runTest {
        val reasons = listOf(
            GoogleIdentityFailureReason.USER_CANCELLED,
            GoogleIdentityFailureReason.UNAVAILABLE,
            GoogleIdentityFailureReason.PROVIDER_FAILURE,
            GoogleIdentityFailureReason.UNCONFIGURED,
            GoogleIdentityFailureReason.INVALID_CREDENTIAL,
            GoogleIdentityFailureReason.NOT_FOREGROUND,
            GoogleIdentityFailureReason.BUSY,
            GoogleIdentityFailureReason.RETIRED,
        )
        for (reason in reasons) {
            val identity = FakeIdentity()
            identity.script += { _ -> throw GoogleIdentityException(reason) }
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            val c = coordinator(identity, handoff, auth)
            try {
                val error = expectFailure(ForegroundSignInFailure.IDENTITY_FAILED) { c.signIn(request()) }
                assertEquals(reason, error.identityReason)
                assertEquals(1, auth.purposes.size)
                assertEquals(listOf(NONCE), identity.nonces)
                assertEquals(0, auth.attempts.size)
                assertEquals(0, handoff.requests.size)
                assertEquals(0, identity.clears)
            } finally {
                c.close()
            }
        }
    }

    @Test
    fun hostileDependencyFaultMapsToUnavailableWithoutLeakingSecrets() = runTest {
        val hostile = "r11b-hostile-secret-leak-9f2c"
        val identity = FakeIdentity()
        identity.script += { _ -> throw RuntimeException("boom $hostile nonce=$NONCE token=$TOKEN") }
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        val c = coordinator(identity, handoff, auth)
        try {
            val error = expectFailure(ForegroundSignInFailure.UNAVAILABLE) { c.signIn(request()) }
            assertForegroundFailure(ForegroundSignInFailure.UNAVAILABLE, error)
            assertNull("unexpected faults carry no identity reason", error.identityReason)
            assertEquals(0, auth.attempts.size)
            assertEquals(0, handoff.requests.size)
        } finally {
            c.close()
        }
    }

    @Test
    fun unconfiguredOriginHasZeroEffects() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        val clock = MutableClock(NOW)
        val uuids = MutableUuids(listOf(EXCHANGE_KEY))
        val c = ForegroundSignInCoordinator(
            origin = null,
            identityProvider = identity,
            handoff = handoff,
            authApi = null,
            clockMillis = { clock.read() },
            uuidProvider = { uuids.next() },
        )
        try {
            expectFailure(ForegroundSignInFailure.CONFIGURATION_UNAVAILABLE) { c.signIn(request()) }
            assertEquals(0, auth.purposes.size)
            assertEquals(0, identity.nonces.size)
            assertEquals(0, auth.attempts.size)
            assertEquals(0, handoff.requests.size)
            assertEquals(0, clock.calls)
            assertEquals(0, uuids.calls)
        } finally {
            c.close()
        }
    }

    @Test
    fun mismatchedOriginAndInvalidIntentOrOperationHaveZeroEffects() = runTest {
        // Mismatched request origin.
        run {
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            auth.exchangeScript += { _ -> grant() }
            val c = coordinator(identity, handoff, auth, root = ORIGIN_ROOT)
            try {
                expectFailure(ForegroundSignInFailure.INVALID_REQUEST) {
                    c.signIn(request(root = OTHER_ORIGIN_ROOT))
                }
                assertEquals(0, auth.purposes.size)
                assertEquals(0, identity.nonces.size)
                assertEquals(0, auth.attempts.size)
                assertEquals(0, handoff.requests.size)
            } finally {
                c.close()
            }
        }
        // INITIAL with a cached ACTIVE expectation.
        run {
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            val c = coordinator(identity, handoff, auth)
            try {
                expectFailure(ForegroundSignInFailure.INVALID_REQUEST) {
                    c.signIn(request(intent = ForegroundSignInIntent.INITIAL_SIGN_IN, expected = cachedActive()))
                }
                assertEquals(0, auth.purposes.size)
                assertEquals(0, auth.attempts.size)
            } finally {
                c.close()
            }
        }
        // SAME_ACCOUNT without a cached account.
        run {
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            val c = coordinator(identity, handoff, auth)
            try {
                expectFailure(ForegroundSignInFailure.INVALID_REQUEST) {
                    c.signIn(
                        request(
                            intent = ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION,
                            expected = signedOut(),
                            operationId = OP_REAUTH,
                        ),
                    )
                }
                assertEquals(0, auth.purposes.size)
                assertEquals(0, auth.attempts.size)
            } finally {
                c.close()
            }
        }
        // Non-canonical operation UUID.
        run {
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            val c = coordinator(identity, handoff, auth)
            try {
                expectFailure(ForegroundSignInFailure.INVALID_REQUEST) {
                    c.signIn(request(operationId = "not-a-uuid"))
                }
                assertEquals(0, auth.purposes.size)
                assertEquals(0, identity.nonces.size)
                assertEquals(0, auth.attempts.size)
            } finally {
                c.close()
            }
        }
    }

    // ---- Obligation 4: budget, busy, cancellation, retirement, late seams ----

    @Test
    fun wholeBudgetTimeoutReleasesSlotForLaterCall() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        auth.exchangeGate = {
            entered.complete(Unit)
            release.await()
        }
        auth.exchangeScript += { _ -> grant() }
        val uuids = MutableUuids(listOf(EXCHANGE_KEY, EXCHANGE_KEY_2))
        val c = coordinator(identity, handoff, auth, uuids = uuids, timeoutMillis = 5_000L)
        val done = CompletableDeferred<Throwable>()
        try {
            supervisorScope {
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        c.signIn(request())
                        done.complete(AssertionError("expected TIMED_OUT"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                entered.await()
                advanceTimeBy(5_000L)
                runCurrent()
                job.join()
            }
            val error = done.await()
            assertTrue("expected ForegroundSignInException but was $error", error is ForegroundSignInException)
            assertForegroundFailure(ForegroundSignInFailure.TIMED_OUT, error)
            assertEquals(0, handoff.requests.size)
            release.complete(Unit)
            runCurrent()
            assertEquals("dead result consumes no handoff", 0, handoff.requests.size)
            assertEquals(EXCHANGE_KEY, auth.attempts.single().idempotencyKey)
            // Slot released: a later call succeeds with a distinct key.
            auth.exchangeGate = null
            auth.exchangeScript.clear()
            auth.exchangeScript += { _ -> grant() }
            val state = c.signIn(request())
            assertTrue(state is AppSessionState.Private)
            assertEquals(2, auth.attempts.size)
            assertEquals(EXCHANGE_KEY_2, auth.attempts[1].idempotencyKey)
        } finally {
            release.complete(Unit)
            c.close()
        }
    }

    @Test
    fun simultaneousCallsRefuseBusyWithoutEffects() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        auth.exchangeGate = {
            entered.complete(Unit)
            release.await()
        }
        auth.exchangeScript += { _ -> grant() }
        auth.exchangeScript += { _ -> grant() }
        val c = coordinator(identity, handoff, auth)
        try {
            supervisorScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { c.signIn(request()) }
                entered.await()
                expectFailure(ForegroundSignInFailure.BUSY) { c.signIn(request(operationId = OP_OTHER)) }
                assertEquals("BUSY adds no challenge/provider/exchange", 1, auth.purposes.size)
                assertEquals(1, identity.nonces.size)
                assertEquals(1, auth.attempts.size)
                release.complete(Unit)
                assertTrue(first.await() is AppSessionState.Private)
            }
        } finally {
            release.complete(Unit)
            c.close()
        }
    }

    @Test
    fun callerCancellationAtSeamsPropagatesWithoutHandoff() = runTest {
        val seams = listOf("challenge", "provider", "exchange", "delay", "handoff")
        for (seam in seams) {
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            when (seam) {
                "challenge" -> {
                    auth.exchangeScript += { _ -> grant() }
                    auth.challengeGate = {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                "provider" -> {
                    auth.exchangeScript += { _ -> grant() }
                    identity.gate = {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                "exchange" -> {
                    auth.exchangeScript += { _ -> grant() }
                    auth.exchangeGate = {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                "delay" -> {
                    auth.exchangeScript += { _ -> throw SyncApiException.Transient() }
                }
                "handoff" -> {
                    auth.exchangeScript += { _ -> grant() }
                    handoff.gate = { _, _ ->
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
            val c = coordinator(identity, handoff, auth)
            try {
                supervisorScope {
                    val probe = CancellationException("r11b-caller-cancel-$seam")
                    val done = CompletableDeferred<Throwable>()
                    val job = async(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            c.signIn(request())
                            done.complete(AssertionError("expected cancellation at $seam"))
                        } catch (e: Throwable) {
                            done.complete(e)
                        }
                    }
                    if (seam == "delay") {
                        // First send dispatched immediately; cancellation lands in the retry delay.
                        runCurrent()
                        assertEquals("first send dispatched, now in retry delay", 1, auth.attempts.size)
                    } else {
                        entered.await()
                    }
                    job.cancel(probe)
                    if (seam != "delay") release.complete(Unit)
                    assertSame("caller cancellation dominates at $seam", probe, done.await())
                    job.join()
                }
                if (seam == "handoff") {
                    // Handoff admitted exactly once, yet the caller saw cancellation, never success.
                    assertEquals(1, handoff.requests.size)
                } else {
                    assertEquals("cancelled $seam consumes no handoff", 0, handoff.requests.size)
                }
                if (seam == "delay") {
                    assertEquals("no retry send after delay cancellation", 1, auth.attempts.size)
                }
            } finally {
                release.complete(Unit)
                c.close()
            }
        }
    }

    @Test
    fun retirementCancelsInflightAndRefusesLaterCalls() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        auth.exchangeGate = {
            entered.complete(Unit)
            release.await()
        }
        auth.exchangeScript += { _ -> grant() }
        val c = coordinator(identity, handoff, auth)
        val done = CompletableDeferred<Throwable>()
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    c.signIn(request())
                    done.complete(AssertionError("expected retired cancellation"))
                } catch (e: Throwable) {
                    done.complete(e)
                }
            }
            entered.await()
            c.close()
            release.complete(Unit)
            val outcome = done.await()
            assertTrue(
                "in-flight retirement yields cancellation, was $outcome",
                outcome is CancellationException,
            )
            job.join()
        }
        expectFailure(ForegroundSignInFailure.RETIRED) { c.signIn(request(operationId = OP_OTHER)) }
        assertEquals(0, identity.clears)
        assertEquals(0, auth.refreshCalls)
        c.close()
    }

    @Test
    fun cancellationResistantLateExchangeCannotConsumeHandoffOrReportSuccess() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val lateRelease = CompletableDeferred<Unit>()
        val lateDone = CompletableDeferred<Unit>()
        auth.exchangeScript += { _ -> grant() }
        auth.exchangeGate = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                // Cancellation-resistant injected seam keeps going after budget/retire.
            }
            withContext(NonCancellable) {
                lateRelease.await()
                lateDone.complete(Unit)
            }
        }
        val uuids = MutableUuids(listOf(EXCHANGE_KEY, EXCHANGE_KEY_2))
        val c = coordinator(identity, handoff, auth, uuids = uuids, timeoutMillis = 3_000L)
        val done = CompletableDeferred<Throwable>()
        try {
            supervisorScope {
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        c.signIn(request())
                        done.complete(AssertionError("expected TIMED_OUT"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                entered.await()
                advanceTimeBy(3_000L)
                runCurrent()
                // Caller completes promptly at the budget, before any late release.
                assertTrue("budget must release the caller promptly", job.isCompleted)
                job.join()
            }
            val error = done.await()
            assertTrue("expected ForegroundSignInException but was $error", error is ForegroundSignInException)
            assertForegroundFailure(ForegroundSignInFailure.TIMED_OUT, error)
            assertEquals("timed-out invocation consumes no handoff", 0, handoff.requests.size)
            // Newer invocation owns the slot while the old resistant exchange stays parked.
            auth.exchangeGate = null
            val state = c.signIn(request(operationId = OP_OTHER))
            assertTrue(state is AppSessionState.Private)
            assertEquals(1, handoff.requests.size)
            assertEquals(2, auth.attempts.size)
            assertEquals(EXCHANGE_KEY, auth.attempts[0].idempotencyKey)
            assertEquals(EXCHANGE_KEY_2, auth.attempts[1].idempotencyKey)
            assertEquals("r11b-access-001", handoff.sessions.single().accessToken.value)
            // Release the old resistant exchange now: it must neither clear nor
            // consume the newer invocation.
            auth.exchangeScript += { _ -> grant(access = "r11b-late-access") }
            lateRelease.complete(Unit)
            lateDone.await()
            runCurrent()
            assertEquals("detached late exchange never consumes handoff", 1, handoff.requests.size)
            assertEquals("r11b-access-001", handoff.sessions.single().accessToken.value)
            assertEquals("late result never clears provider state", 0, identity.clears)
        } finally {
            lateRelease.complete(Unit)
            c.close()
        }
    }

    @Test
    fun queuedFinalDispatchCannotReportSuccessAfterCancellationOrRetire() = runTest {
        // Distinct from suspended-handoff cancellation: here the handoff result is fully
        // COMPUTED (recorded + decided + confirmed via [FakeHandoff.computed]) and only the
        // final caller dispatch is held queued. Cancellation/retirement in that window must
        // prevent any success report even though the service outcome exists.
        run {
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            auth.exchangeScript += { _ -> grant() }
            auth.exchangeScript += { _ -> grant() }
            val uuids = MutableUuids(listOf(EXCHANGE_KEY, EXCHANGE_KEY_2))
            val driver = ManualDispatcher()
            val c = coordinator(identity, handoff, auth, uuids = uuids)
            val done = CompletableDeferred<Throwable>()
            try {
                supervisorScope {
                    val job = async(driver) {
                        try {
                            c.signIn(request())
                            done.complete(AssertionError("expected queued-dispatch cancellation"))
                        } catch (e: Throwable) {
                            done.complete(e)
                        }
                    }
                    // Drive one runnable at a time until the synchronous fake has
                    // actually returned its computed Private result.
                    driver.drainUntil { handoff.computed.isCompleted }
                    assertTrue(handoff.computed.await() is AppSessionState.Private)
                    // The fake has returned; caller resumption is still queued.
                    assertTrue("caller resumption still queued", driver.pendingCount() > 0)
                    assertTrue("caller outcome not completed", !job.isCompleted)
                    assertTrue("caller result not reported", !done.isCompleted)
                    assertEquals(1, handoff.requests.size)
                    val probe = CancellationException("r11b-queued-dispatch-cancel")
                    job.cancel(probe)
                    driver.drainUntil { done.isCompleted }
                    assertSame("queued dispatch cancellation dominates", probe, done.await())
                    job.join()
                }
                // Slot released and never reported success: a later call succeeds alone.
                val state = c.signIn(request(operationId = OP_OTHER))
                assertTrue(state is AppSessionState.Private)
                assertEquals(2, handoff.requests.size)
                assertEquals(2, auth.attempts.size)
                assertEquals(EXCHANGE_KEY, auth.attempts[0].idempotencyKey)
                assertEquals(EXCHANGE_KEY_2, auth.attempts[1].idempotencyKey)
            } finally {
                c.close()
            }
        }
        run {
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth()
            auth.exchangeScript += { _ -> grant() }
            val driver = ManualDispatcher()
            val c = coordinator(identity, handoff, auth)
            val done = CompletableDeferred<Throwable>()
            supervisorScope {
                val job = async(driver) {
                    try {
                        c.signIn(request())
                        done.complete(AssertionError("expected queued-dispatch retirement"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                // Retirement lands after the synchronous fake has actually returned
                // but before the queued caller resume runs.
                driver.drainUntil { handoff.computed.isCompleted }
                assertTrue(handoff.computed.await() is AppSessionState.Private)
                assertTrue("caller resumption still queued", driver.pendingCount() > 0)
                assertTrue("caller outcome not completed", !job.isCompleted)
                c.close()
                driver.drainUntil { done.isCompleted }
                val outcome = done.await()
                assertTrue(
                    "retirement before caller dispatch yields cancellation, was $outcome",
                    outcome is CancellationException,
                )
                job.join()
            }
            expectFailure(ForegroundSignInFailure.RETIRED) { c.signIn(request(operationId = OP_OTHER)) }
            c.close()
        }
    }

    @Test
    fun seamOwnedTimeoutWithLiveCallerMapsToUnavailable() = runTest {
        // Normative lifetime #7 clarification: a dependency/seam-owned timeout while the
        // caller is still live maps to UNAVAILABLE, never invocation TIMED_OUT, and never
        // escapes as caller cancellation. Actual caller cancellation still wins (covered by
        // the per-seam cancellation test).
        val identity = FakeIdentity()
        identity.script += { _ ->
            withTimeout(1_000L) { awaitCancellation() }
        }
        val handoff = FakeHandoff()
        val auth = FakeAuth()
        val c = coordinator(identity, handoff, auth)
        val done = CompletableDeferred<Throwable>()
        try {
            supervisorScope {
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        c.signIn(request())
                        done.complete(AssertionError("expected UNAVAILABLE"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                advanceTimeBy(5_000L)
                job.join()
            }
            val error = done.await()
            assertTrue("expected ForegroundSignInException but was $error", error is ForegroundSignInException)
            assertForegroundFailure(ForegroundSignInFailure.UNAVAILABLE, error)
            assertNull("seam timeout carries no identity reason", (error as ForegroundSignInException).identityReason)
            assertEquals(0, auth.attempts.size)
            assertEquals(0, handoff.requests.size)
            assertEquals(0, identity.clears)
        } finally {
            c.close()
        }
    }

    @Test
    fun queuedAdmissionExpiryRefusesBeforeSend() = runTest {
        // One representative queued window per phase, sharing this harness: drive queued
        // work one runnable at a time until the provider/exchange invocation is queued
        // but not yet admitted, move the injected wall clock to expiry BEFORE running
        // that queued invocation, then drain. Admission freshness must refuse
        // CHALLENGE_EXPIRED with zero newly admitted effects. No token-return gate
        // holds: the expiry lands strictly inside a queued-dispatch window.
        for (phase in listOf("provider", "exchange")) {
            val clock = MutableClock(NOW)
            val identity = FakeIdentity()
            val handoff = FakeHandoff()
            val auth = FakeAuth(challenge(expiresAtMillis = NOW + 1_000L))
            auth.exchangeScript += { _ -> grant() }
            val uuids = MutableUuids(listOf(EXCHANGE_KEY))
            val driver = ManualDispatcher()
            val c = coordinator(identity, handoff, auth, clock = clock, uuids = uuids)
            val done = CompletableDeferred<Throwable>()
            try {
                supervisorScope {
                    val job = async(driver) {
                        try {
                            c.signIn(request())
                            done.complete(AssertionError("expected CHALLENGE_EXPIRED at $phase"))
                        } catch (e: Throwable) {
                            done.complete(e)
                        }
                    }
                    if (phase == "provider") {
                        // Completed challenge + live pre-provider check window: challenge
                        // dispatched, provider invocation queued but no credential admitted.
                        // The caller continuation after challenge is still queued here, so
                        // drive exactly it on the live clock: the pre-provider check runs
                        // live, provider work stays queued and unadmitted.
                        driver.drainUntil { auth.purposes.size == 1 }
                        driver.runNext()
                        assertEquals("no credential admitted while provider queued", 0, identity.nonces.size)
                        assertTrue("provider work still queued", driver.pendingCount() > 0)
                    } else {
                        // Completed provider + UUID capture + live pre-send check window:
                        // exchange invocation queued but no send admitted.
                        driver.drainUntil { identity.nonces.size == 1 && uuids.calls == 1 }
                        assertEquals("no send admitted while exchange queued", 0, auth.attempts.size)
                    }
                    clock.now = NOW + 1_000L
                    driver.drainUntil { done.isCompleted }
                    job.join()
                }
                val error = done.await()
                assertTrue("expected ForegroundSignInException at $phase but was $error", error is ForegroundSignInException)
                assertForegroundFailure(ForegroundSignInFailure.CHALLENGE_EXPIRED, error)
                if (phase == "provider") {
                    // The expired provider invocation was never admitted: no credential
                    // acquisition happened, so this cannot be a later first-send refusal
                    // after an expired Google acquisition.
                    assertEquals("expired provider never admitted", 0, identity.nonces.size)
                } else {
                    assertEquals("live acquisition retained", 1, identity.nonces.size)
                }
                assertEquals("no exchange send at $phase", 0, auth.attempts.size)
                assertEquals(0, handoff.requests.size)
                assertEquals(0, identity.clears)
            } finally {
                c.close()
            }
        }
    }

    @Test
    fun nonPrivateHandoffResultRefusesUnavailable() = runTest {
        val identity = FakeIdentity()
        val handoff = FakeHandoff(result = AppSessionState.PrivacyLocked)
        val auth = FakeAuth()
        auth.exchangeScript += { _ -> grant() }
        val c = coordinator(identity, handoff, auth)
        try {
            expectFailure(ForegroundSignInFailure.UNAVAILABLE) { c.signIn(request()) }
            assertEquals(1, auth.attempts.size)
            assertEquals(1, handoff.requests.size)
        } finally {
            c.close()
        }
    }
}
