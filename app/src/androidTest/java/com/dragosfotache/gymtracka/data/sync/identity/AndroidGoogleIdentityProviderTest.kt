package com.dragosfotache.gymtracka.data.sync.identity

import android.app.Activity
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.ClearCredentialProviderConfigurationException
import androidx.credentials.exceptions.ClearCredentialUnknownException
import androidx.credentials.exceptions.ClearCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityException
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityFailureReason
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Independent QA oracles for R11a [AndroidGoogleIdentityProvider].
 *
 * Written from the normative R11a specification only, without inspecting production source.
 * All audience/nonce/token literals are synthetic and independent; no real provider is contacted.
 * A fake [GoogleCredentialClient] records actual SDK requests and returns actual SDK credentials.
 * Google bundle key strings match the public googleid 1.2.1 SDK artifact; no production keys are used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidGoogleIdentityProviderTest {

    companion object {
        private const val AUDIENCE = "synthetic-r11a-web-client-7f3a2b.apps.googleusercontent.com"
        private const val NONCE = "synthetic-r11a-nonce-001"
        private const val TOKEN_TAG = "synthetic-r11a-sub-001"
        private const val GUARD_TIMEOUT_MILLIS = 15_000L
        private const val VIRTUAL_BOUND_MILLIS = 5_000L
        private const val VIRTUAL_LONG_BOUND_MILLIS = 60_000L
        private const val VIRTUAL_PARENT_TIMEOUT_MILLIS = 1_000L

        // Verified against the public googleid 1.2.1 SDK artifact bytecode.
        private const val SDK_BUNDLE_KEY_ID =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID"
        private const val SDK_BUNDLE_KEY_ID_TOKEN =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN"
    }

    private class FakeCredentialClient : GoogleCredentialClient {
        private val lock = Any()
        val getRequests = mutableListOf<GetCredentialRequest>()
        val getActivities = mutableListOf<Activity>()
        val clearRequests = mutableListOf<ClearCredentialStateRequest>()
        val getEntries = AtomicInteger(0)
        val clearEntries = AtomicInteger(0)
        var getBehavior: (suspend (activity: Activity, request: GetCredentialRequest) -> GetCredentialResponse)? = null
        var clearBehavior: (suspend (request: ClearCredentialStateRequest) -> Unit) = { _ -> }

        override suspend fun getCredential(
            activity: Activity,
            request: GetCredentialRequest,
        ): GetCredentialResponse {
            synchronized(lock) {
                getRequests.add(request)
                getActivities.add(activity)
            }
            getEntries.incrementAndGet()
            val behavior = getBehavior ?: error("FakeCredentialClient.getBehavior not stubbed")
            return behavior(activity, request)
        }

        override suspend fun clearCredentialState(request: ClearCredentialStateRequest) {
            synchronized(lock) { clearRequests.add(request) }
            clearEntries.incrementAndGet()
            clearBehavior(request)
        }

        fun getRequestCount(): Int = synchronized(lock) { getRequests.size }

        fun clearRequestCount(): Int = synchronized(lock) { clearRequests.size }

        fun lastGetRequest(): GetCredentialRequest = synchronized(lock) { getRequests.last() }
    }

    private suspend fun currentActivity(
        scenario: ActivityScenario<ComponentActivity>,
    ): ComponentActivity {
        val gate = CompletableDeferred<ComponentActivity>()
        scenario.onActivity { gate.complete(it) }
        return gate.await()
    }

    /**
     * Constructs the provider on Android Main via [ActivityScenario.onActivity].
     * Constructor failures are routed through the gate so device Main never crashes.
     */
    private suspend fun buildProvider(
        scenario: ActivityScenario<ComponentActivity>,
        audience: String?,
        fake: FakeCredentialClient,
        timeoutMillis: Long? = null,
    ): AndroidGoogleIdentityProvider {
        val gate = CompletableDeferred<AndroidGoogleIdentityProvider>()
        scenario.onActivity { activity ->
            try {
                gate.complete(
                    if (timeoutMillis == null) {
                        AndroidGoogleIdentityProvider(activity, audience, fake)
                    } else {
                        AndroidGoogleIdentityProvider(activity, audience, fake, timeoutMillis)
                    },
                )
            } catch (e: Throwable) {
                gate.completeExceptionally(e)
            }
        }
        return gate.await()
    }

    private fun syntheticToken(tag: String): String {
        fun b64(raw: String): String =
            Base64.encodeToString(
                raw.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        val header = b64("""{"alg":"none","typ":"JWT"}""")
        val payload = b64("""{"sub":"$tag","aud":"synthetic-r11a"}""")
        return "$header.$payload.synthetic-signature-$tag"
    }

    private fun successResponse(token: String): GetCredentialResponse {
        val built = GoogleIdTokenCredential.Builder()
            .setId("synthetic-r11a-id")
            .setIdToken(token)
            .build()
        return GetCredentialResponse(
            CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, built.data),
        )
    }

    /** Bundle carrying the real Google SDK keys, for blank/malformed token-path oracles. */
    private fun sdkKeyedBundle(idToken: String): Bundle =
        Bundle().apply {
            putString(SDK_BUNDLE_KEY_ID, "synthetic-r11a-id")
            putString(SDK_BUNDLE_KEY_ID_TOKEN, idToken)
        }

    private fun assertSingleButtonRequest(
        request: GetCredentialRequest,
        audience: String,
        nonce: String,
    ) {
        assertEquals("exactly one credential option", 1, request.credentialOptions.size)
        val option = request.credentialOptions.single()
        assertTrue(
            "explicit Google button option, no auto-select ladder",
            option is GetSignInWithGoogleOption,
        )
        option as GetSignInWithGoogleOption
        assertEquals("audience passes unchanged", audience, option.serverClientId)
        assertEquals("nonce passes unchanged", nonce, option.nonce)
        assertEquals(
            "button credential type",
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            option.type,
        )
        assertNull("no hosted-domain filter", option.hostedDomainFilter)
        assertTrue("no extra claims", option.claims.isNullOrEmpty())
        assertNull("no origin impersonation", request.origin)
        assertFalse(
            "no prefer-immediately-available shortcut",
            request.preferImmediatelyAvailableCredentials,
        )
    }

    private suspend fun expectFailure(
        expected: GoogleIdentityFailureReason,
        vararg secrets: String,
        block: suspend () -> Unit,
    ): GoogleIdentityException {
        try {
            block()
        } catch (e: GoogleIdentityException) {
            assertEquals(expected, e.reason)
            assertNull("no cause chain around SDK failures", e.cause)
            assertTrue("no suppressed SDK failures", e.suppressed.isEmpty())
            val visible = (e.message ?: "") + e.toString()
            secrets.forEach { secret ->
                if (secret.isNotEmpty()) {
                    assertFalse("safe failure must not reveal secrets", visible.contains(secret))
                }
            }
            return e
        }
        fail("expected GoogleIdentityException($expected)")
        error("unreachable")
    }

    private suspend fun expectCallerCancellation(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            // Catching CancellationException already proves the caller path: a translated
            // GoogleIdentityException failure cannot arrive through this catch.
            return
        }
        fail("expected CancellationException")
    }

    @Test
    fun constructionDoesNotContactProvider(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            buildProvider(scenario, AUDIENCE, fake)
            assertEquals(0, fake.getRequestCount())
            assertEquals(0, fake.clearRequestCount())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun successReturnsExactTokenWithSingleButtonRequest(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val activity = currentActivity(scenario)
            val fake = FakeCredentialClient()
            val token = syntheticToken(TOKEN_TAG)
            fake.getBehavior = { _, _ -> successResponse(token) }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val result = withTimeout(GUARD_TIMEOUT_MILLIS) { provider.credentialFor(NONCE) }
            assertEquals("exact nonblank token", token, result.value)
            assertFalse("opaque token display stays redacted", result.toString().contains(token))
            assertEquals(1, fake.getRequestCount())
            assertSingleButtonRequest(fake.lastGetRequest(), AUDIENCE, NONCE)
            assertTrue("get uses the supplied Activity", fake.getActivities.single() === activity)
            assertFalse(
                "config display must not reveal the audience",
                provider.toString().contains(AUDIENCE),
            )
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun nullAndBlankAudienceIsUnconfiguredBeforeLaunch(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val audiences: List<String?> = listOf(null, "", "   ")
            for (audience in audiences) {
                val fake = FakeCredentialClient()
                fake.getBehavior = { _, _ -> successResponse(syntheticToken("unused-$audience")) }
                val provider = buildProvider(scenario, audience, fake)
                expectFailure(GoogleIdentityFailureReason.UNCONFIGURED, AUDIENCE, NONCE) {
                    provider.credentialFor(NONCE)
                }
                assertEquals("no SDK launch when unconfigured", 0, fake.getRequestCount())
                provider.close()
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun whitespacePaddedAudienceIsRejectedWithoutRepair(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val padded = "  $AUDIENCE  "
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> successResponse(syntheticToken("unused-padded")) }
            val provider = buildProvider(scenario, padded, fake)
            expectFailure(GoogleIdentityFailureReason.UNCONFIGURED, AUDIENCE, NONCE, padded) {
                provider.credentialFor(NONCE)
            }
            assertEquals("padded audience never reaches the SDK", 0, fake.getRequestCount())
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun emptyAndBlankNonceIsInvalidBeforeLaunch(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> successResponse(syntheticToken("unused-nonce")) }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            for (nonce in listOf("", "   ")) {
                expectFailure(GoogleIdentityFailureReason.INVALID_CREDENTIAL, AUDIENCE, nonce) {
                    provider.credentialFor(nonce)
                }
            }
            assertEquals("no SDK launch on invalid nonce", 0, fake.getRequestCount())
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun whitespaceNoncePassesExactly(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val nonce = "  synthetic nonce with spaces  "
            val token = syntheticToken("spaced-nonce-token")
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> successResponse(token) }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val result = withTimeout(GUARD_TIMEOUT_MILLIS) { provider.credentialFor(nonce) }
            assertEquals(token, result.value)
            assertSingleButtonRequest(fake.lastGetRequest(), AUDIENCE, nonce)
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun wrongCredentialTypeIsPreciselyInvalid(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                GetCredentialResponse(CustomCredential("com.example.synthetic.WrongType", Bundle()))
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            expectFailure(GoogleIdentityFailureReason.INVALID_CREDENTIAL, AUDIENCE, NONCE) {
                provider.credentialFor(NONCE)
            }
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun nonCustomCredentialIsPreciselyInvalid(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                GetCredentialResponse(PasswordCredential("synthetic-id", "synthetic-password"))
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            expectFailure(GoogleIdentityFailureReason.INVALID_CREDENTIAL, AUDIENCE, NONCE) {
                provider.credentialFor(NONCE)
            }
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun missingOrBlankTokenMaterialIsPreciselyInvalid(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val type = GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            val malformed = listOf(
                Bundle(),
                Bundle().apply { putString("synthetic-irrelevant-key", "synthetic-value") },
                Bundle().apply { putString("synthetic-id-token", "   ") },
            )
            for (data in malformed) {
                val fake = FakeCredentialClient()
                fake.getBehavior = { _, _ -> GetCredentialResponse(CustomCredential(type, data)) }
                val provider = buildProvider(scenario, AUDIENCE, fake)
                expectFailure(GoogleIdentityFailureReason.INVALID_CREDENTIAL, AUDIENCE, NONCE) {
                    provider.credentialFor(NONCE)
                }
                provider.close()
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun blankTokenUnderRealSdkKeysIsPreciselyInvalid(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val type = GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                GetCredentialResponse(CustomCredential(type, sdkKeyedBundle("   ")))
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            expectFailure(GoogleIdentityFailureReason.INVALID_CREDENTIAL, AUDIENCE, NONCE) {
                provider.credentialFor(NONCE)
            }
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun malformedTokenScalarUnderRealSdkKeysIsPreciselyInvalid(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val type = GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            val malformedScalar = "synthetic-malformed-token"
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                GetCredentialResponse(CustomCredential(type, sdkKeyedBundle(malformedScalar)))
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            expectFailure(
                GoogleIdentityFailureReason.INVALID_CREDENTIAL,
                AUDIENCE,
                NONCE,
                malformedScalar,
            ) {
                provider.credentialFor(NONCE)
            }
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun nonResumedActivityRefusesGetBeforeLaunch(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.STARTED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> successResponse(syntheticToken("unused-paused")) }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            expectFailure(GoogleIdentityFailureReason.NOT_FOREGROUND, AUDIENCE, NONCE) {
                provider.credentialFor(NONCE)
            }
            assertEquals("no SDK launch while not resumed", 0, fake.getRequestCount())
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun pauseDuringProviderUiStillAcceptsResult(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val token = syntheticToken("paused-ui-token")
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                entered.complete(Unit)
                release.await()
                successResponse(token)
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val deferred = async { provider.credentialFor(NONCE) }
            entered.await()
            scenario.moveToState(Lifecycle.State.STARTED)
            release.complete(Unit)
            val result = withTimeout(GUARD_TIMEOUT_MILLIS) { deferred.await() }
            assertEquals(token, result.value)
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun destroyDuringCallFencesResultWithSafeCancellation(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                entered.complete(Unit)
                release.await()
                successResponse(syntheticToken("destroyed-token"))
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val deferred = async { provider.credentialFor(NONCE) }
            entered.await()
            scenario.moveToState(Lifecycle.State.DESTROYED)
            release.complete(Unit)
            expectCallerCancellation { deferred.await() }
            expectFailure(GoogleIdentityFailureReason.RETIRED, AUDIENCE, NONCE) {
                provider.credentialFor(NONCE)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun sdkUserCancellationMapsToUserCancelled(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                throw GetCredentialCancellationException("synthetic user dismissed the sheet")
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            expectFailure(
                GoogleIdentityFailureReason.USER_CANCELLED,
                AUDIENCE,
                NONCE,
                "synthetic user dismissed the sheet",
            ) {
                provider.credentialFor(NONCE)
            }
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun callerCancellationPropagatesAndReleasesSlot(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val entered = CompletableDeferred<Unit>()
            val hang = CompletableDeferred<Unit>()
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                entered.complete(Unit)
                hang.await()
                successResponse(syntheticToken("unused-after-cancel"))
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val deferred = async { provider.credentialFor(NONCE) }
            entered.await()
            deferred.cancel()
            expectCallerCancellation { deferred.await() }
            val token = syntheticToken("after-cancel-token")
            fake.getBehavior = { _, _ -> successResponse(token) }
            val retry = withTimeout(GUARD_TIMEOUT_MILLIS) {
                provider.credentialFor("synthetic-r11a-nonce-002")
            }
            assertEquals("cancel releases the slot for a later nonce", token, retry.value)
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun parentCancellationBeatsRacingProviderFault(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                entered.complete(Unit)
                release.await()
                throw GetCredentialUnknownException("synthetic racing fault")
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val deferred = async { provider.credentialFor(NONCE) }
            entered.await()
            deferred.cancel()
            release.complete(Unit)
            expectCallerCancellation { deferred.await() }
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun parentTimeoutPropagatesAsCancellationOnVirtualClock(): Unit = runTest {
        val virtualMain = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(virtualMain)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val hang = CompletableDeferred<Unit>()
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                hang.await()
                successResponse(syntheticToken("unused-after-parent-timeout"))
            }
            val provider = buildProvider(scenario, AUDIENCE, fake, VIRTUAL_LONG_BOUND_MILLIS)
            val deferred = async(virtualMain) {
                withTimeout(VIRTUAL_PARENT_TIMEOUT_MILLIS) { provider.credentialFor(NONCE) }
            }
            advanceUntilIdle()
            expectCallerCancellation { deferred.await() }
            val token = syntheticToken("after-parent-timeout-token")
            fake.getBehavior = { _, _ -> successResponse(token) }
            val retry = async(virtualMain) { provider.credentialFor("synthetic-r11a-nonce-002") }
            advanceUntilIdle()
            assertEquals("parent timeout releases the slot", token, retry.await().value)
            provider.close()
        } finally {
            Dispatchers.resetMain()
            scenario.close()
        }
    }

    @Test
    fun absentProviderMapsToUnavailable(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val faults = listOf(
                NoCredentialException("synthetic no credential"),
                GetCredentialUnsupportedException("synthetic unsupported"),
                GetCredentialProviderConfigurationException("synthetic misconfigured"),
            )
            for (fault in faults) {
                val fake = FakeCredentialClient()
                fake.getBehavior = { _, _ -> throw fault }
                val provider = buildProvider(scenario, AUDIENCE, fake)
                expectFailure(GoogleIdentityFailureReason.UNAVAILABLE, AUDIENCE, NONCE) {
                    provider.credentialFor(NONCE)
                }
                provider.close()
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun otherProviderFaultsMapToRedactedProviderFailure(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val rawMarker = "synthetic-raw-provider-leak-9f2c"
            val token = syntheticToken("leak-check-token")
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> throw GetCredentialUnknownException(rawMarker) }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            expectFailure(
                GoogleIdentityFailureReason.PROVIDER_FAILURE,
                AUDIENCE,
                NONCE,
                token,
                rawMarker,
            ) {
                provider.credentialFor(NONCE)
            }
            val leaking = FakeCredentialClient()
            leaking.getBehavior = { _, _ -> throw RuntimeException(rawMarker) }
            val second = buildProvider(scenario, AUDIENCE, leaking)
            expectFailure(
                GoogleIdentityFailureReason.PROVIDER_FAILURE,
                AUDIENCE,
                NONCE,
                rawMarker,
            ) {
                second.credentialFor(NONCE)
            }
            provider.close()
            second.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun adapterOwnedTimeoutIsBoundedProviderFailureOnVirtualClock(): Unit = runTest {
        val virtualMain = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(virtualMain)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val hang = CompletableDeferred<Unit>()
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                hang.await()
                successResponse(syntheticToken("unused-late-timeout"))
            }
            val provider = buildProvider(scenario, AUDIENCE, fake, VIRTUAL_BOUND_MILLIS)
            val callerJob = SupervisorJob()
            val callerScope = CoroutineScope(callerJob + virtualMain)
            val deferred = callerScope.async { provider.credentialFor(NONCE) }
            testScheduler.runCurrent()
            assertEquals("exactly one bounded SDK attempt", 1, fake.getEntries.get())
            assertTrue(deferred.isActive)
            advanceTimeBy(VIRTUAL_BOUND_MILLIS - 1L)
            assertTrue("bound is not fired early", deferred.isActive)
            advanceTimeBy(1L)
            expectFailure(GoogleIdentityFailureReason.PROVIDER_FAILURE, AUDIENCE, NONCE) {
                deferred.await()
            }
            callerJob.cancel()
            provider.close()
        } finally {
            Dispatchers.resetMain()
            scenario.close()
        }
    }

    @Test
    fun queuedDeliveryRechecksRetirementBeforeCallerResumes(): Unit = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val callerScheduler = TestCoroutineScheduler()
        val callerDispatcher = StandardTestDispatcher(callerScheduler)
        val callerJob = SupervisorJob()
        val callerScope = CoroutineScope(callerJob + callerDispatcher)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)

            // Phase 1: SDK success fully ready on Main while caller delivery stays queued.
            val token = syntheticToken("queued-success-token")
            val successFake = FakeCredentialClient()
            successFake.getBehavior = { _, _ -> successResponse(token) }
            val successProvider = buildProvider(scenario, AUDIENCE, successFake)
            val successCall = callerScope.async {
                successProvider.credentialFor("synthetic-r11a-nonce-q1")
            }
            callerScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertEquals(1, successFake.getEntries.get())
            assertFalse("caller delivery still queued after SDK response", successCall.isCompleted)
            successProvider.close()
            callerScheduler.advanceUntilIdle()
            expectCallerCancellation { successCall.await() }

            // Phase 2: typed SDK fault fully ready on Main while caller delivery stays queued.
            val faultFake = FakeCredentialClient()
            faultFake.getBehavior = { _, _ -> throw NoCredentialException("synthetic queued absence") }
            val faultProvider = buildProvider(scenario, AUDIENCE, faultFake)
            val faultCall = callerScope.async {
                faultProvider.credentialFor("synthetic-r11a-nonce-q2")
            }
            callerScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertEquals(1, faultFake.getEntries.get())
            assertFalse("caller delivery still queued after SDK fault", faultCall.isCompleted)
            faultProvider.close()
            callerScheduler.advanceUntilIdle()
            // The queued classified fault must not surface as UNAVAILABLE after retirement.
            expectCallerCancellation { faultCall.await() }

            // Phase 3: clear success fully ready on Main while caller delivery stays queued.
            val clearFake = FakeCredentialClient()
            val clearProvider = buildProvider(scenario, AUDIENCE, clearFake)
            val clearCall = callerScope.async { clearProvider.clearCredentialState() }
            callerScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertEquals(1, clearFake.clearEntries.get())
            assertFalse("caller delivery still queued after clear response", clearCall.isCompleted)
            clearProvider.close()
            callerScheduler.advanceUntilIdle()
            expectCallerCancellation { clearCall.await() }
        } finally {
            callerJob.cancel()
            Dispatchers.resetMain()
            scenario.close()
        }
    }

    @Test
    fun concurrentOperationsShareOneNonqueueingSlot(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            val provider = buildProvider(scenario, AUDIENCE, fake)

            suspend fun blockFirstGet(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fake.getBehavior = { _, _ ->
                    entered.complete(Unit)
                    release.await()
                    successResponse(syntheticToken("slot-first"))
                }
                return entered to release
            }

            // get/get -> BUSY, then slot released for a later nonce.
            var gates = blockFirstGet()
            val first = async { provider.credentialFor("synthetic-r11a-nonce-a") }
            gates.first.await()
            expectFailure(GoogleIdentityFailureReason.BUSY, AUDIENCE, NONCE) {
                provider.credentialFor("synthetic-r11a-nonce-b")
            }
            assertEquals("never a second UI", 1, fake.getRequestCount())
            gates.second.complete(Unit)
            withTimeout(GUARD_TIMEOUT_MILLIS) { first.await() }

            // get in flight -> clear BUSY.
            gates = blockFirstGet()
            val during = async { provider.credentialFor("synthetic-r11a-nonce-c") }
            gates.first.await()
            expectFailure(GoogleIdentityFailureReason.BUSY, AUDIENCE, NONCE) {
                provider.clearCredentialState()
            }
            assertEquals("clear never queued behind get", 0, fake.clearRequestCount())
            gates.second.complete(Unit)
            withTimeout(GUARD_TIMEOUT_MILLIS) { during.await() }

            // clear in flight -> get BUSY.
            val clearEntered = CompletableDeferred<Unit>()
            val clearRelease = CompletableDeferred<Unit>()
            fake.clearBehavior = {
                clearEntered.complete(Unit)
                clearRelease.await()
            }
            val clearing = async { provider.clearCredentialState() }
            clearEntered.await()
            expectFailure(GoogleIdentityFailureReason.BUSY, AUDIENCE, NONCE) {
                provider.credentialFor("synthetic-r11a-nonce-d")
            }
            assertEquals("get never queued behind clear", 2, fake.getRequestCount())
            clearRelease.complete(Unit)
            withTimeout(GUARD_TIMEOUT_MILLIS) { clearing.await() }

            // clear/clear -> BUSY.
            val secondEntered = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            fake.clearBehavior = {
                secondEntered.complete(Unit)
                secondRelease.await()
            }
            val clearingFirst = async { provider.clearCredentialState() }
            secondEntered.await()
            expectFailure(GoogleIdentityFailureReason.BUSY, AUDIENCE, NONCE) {
                provider.clearCredentialState()
            }
            assertEquals("never a second clear", 2, fake.clearRequestCount())
            secondRelease.complete(Unit)
            withTimeout(GUARD_TIMEOUT_MILLIS) { clearingFirst.await() }

            val token = syntheticToken("slot-released-token")
            fake.getBehavior = { _, _ -> successResponse(token) }
            val retry = withTimeout(GUARD_TIMEOUT_MILLIS) {
                provider.credentialFor("synthetic-r11a-nonce-e")
            }
            assertEquals(token, retry.value)
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun closeIsIdempotentAndPermanentlyRetires(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> successResponse(syntheticToken("pre-close-token")) }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val first = withTimeout(GUARD_TIMEOUT_MILLIS) { provider.credentialFor(NONCE) }
            assertEquals(1, fake.getRequestCount())
            provider.close()
            provider.close()
            expectFailure(GoogleIdentityFailureReason.RETIRED, AUDIENCE, NONCE) {
                provider.credentialFor("synthetic-r11a-nonce-after-close")
            }
            expectFailure(GoogleIdentityFailureReason.RETIRED, AUDIENCE) {
                provider.clearCredentialState()
            }
            assertEquals("no SDK work escapes retirement", 1, fake.getRequestCount())
            assertEquals(0, fake.clearRequestCount())
            assertTrue("completed acceptance linearizes before close", first.value.isNotEmpty())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun cancellationResistantLateSuccessNeverEscapesRetirement(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lateGate = CompletableDeferred<Unit>()
            val lateDone = CompletableDeferred<Unit>()
            val lateToken = syntheticToken("late-token-must-not-escape")
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                entered.complete(Unit)
                try {
                    release.await()
                } catch (e: CancellationException) {
                    // Cancellation-resistant SDK work keeps going after close.
                }
                withContext(NonCancellable) {
                    lateGate.await()
                    lateDone.complete(Unit)
                    successResponse(lateToken)
                }
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val deferred = async { provider.credentialFor(NONCE) }
            entered.await()
            withContext(Dispatchers.Default) { provider.close() }
            release.complete(Unit)
            lateGate.complete(Unit)
            withTimeout(GUARD_TIMEOUT_MILLIS) { lateDone.await() }
            expectCallerCancellation { deferred.await() }
            expectFailure(GoogleIdentityFailureReason.RETIRED, AUDIENCE, NONCE, lateToken) {
                provider.credentialFor("synthetic-r11a-nonce-after-retire")
            }
            assertEquals("late success never published", 1, fake.getRequestCount())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun lateFaultAfterCloseNeverBecomesProviderError(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lateGate = CompletableDeferred<Unit>()
            val lateDone = CompletableDeferred<Unit>()
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ ->
                entered.complete(Unit)
                try {
                    release.await()
                } catch (e: CancellationException) {
                    // Cancellation-resistant SDK work keeps going after close.
                }
                withContext(NonCancellable) {
                    lateGate.await()
                    lateDone.complete(Unit)
                    throw GetCredentialUnknownException("synthetic late fault")
                }
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val deferred = async { provider.credentialFor(NONCE) }
            entered.await()
            provider.close()
            release.complete(Unit)
            lateGate.complete(Unit)
            withTimeout(GUARD_TIMEOUT_MILLIS) { lateDone.await() }
            // Queued caller dispatch rechecks retirement: the late classified fault
            // must not surface as PROVIDER_FAILURE.
            expectCallerCancellation { deferred.await() }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun lateClearAfterCloseNeverReportsSuccess(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lateGate = CompletableDeferred<Unit>()
            val lateDone = CompletableDeferred<Unit>()
            val fake = FakeCredentialClient()
            fake.clearBehavior = {
                entered.complete(Unit)
                try {
                    release.await()
                } catch (e: CancellationException) {
                    // Cancellation-resistant SDK work keeps going after close.
                }
                withContext(NonCancellable) {
                    lateGate.await()
                    lateDone.complete(Unit)
                }
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val deferred = async { provider.clearCredentialState() }
            entered.await()
            provider.close()
            release.complete(Unit)
            lateGate.complete(Unit)
            withTimeout(GUARD_TIMEOUT_MILLIS) { lateDone.await() }
            expectCallerCancellation { deferred.await() }
            expectFailure(GoogleIdentityFailureReason.RETIRED, AUDIENCE) {
                provider.clearCredentialState()
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun destroyAutomaticallyRetiresAdapter(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> successResponse(syntheticToken("unused-destroy")) }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            scenario.moveToState(Lifecycle.State.DESTROYED)
            expectFailure(GoogleIdentityFailureReason.RETIRED, AUDIENCE, NONCE) {
                provider.credentialFor(NONCE)
            }
            expectFailure(GoogleIdentityFailureReason.RETIRED, AUDIENCE) {
                provider.clearCredentialState()
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun recreationRetiresOldAdapterAndNewActivitySucceeds(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> successResponse(syntheticToken("pre-recreation")) }
            val oldProvider = buildProvider(scenario, AUDIENCE, fake)
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.RESUMED)
            expectFailure(GoogleIdentityFailureReason.RETIRED, AUDIENCE, NONCE) {
                oldProvider.credentialFor(NONCE)
            }
            val token = syntheticToken("post-recreation-token")
            val secondFake = FakeCredentialClient()
            secondFake.getBehavior = { _, _ -> successResponse(token) }
            val newProvider = buildProvider(scenario, AUDIENCE, secondFake)
            val result = withTimeout(GUARD_TIMEOUT_MILLIS) {
                newProvider.credentialFor("synthetic-r11a-nonce-recreated")
            }
            assertEquals("a new Activity needs a new adapter", token, result.value)
            oldProvider.close()
            newProvider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun clearIsExplicitPrimitiveWithoutResumedOrAudience(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.STARTED)
            val fake = FakeCredentialClient()
            val provider = buildProvider(scenario, null, fake)
            withTimeout(GUARD_TIMEOUT_MILLIS) { provider.clearCredentialState() }
            assertEquals(1, fake.clearRequestCount())
            assertEquals(0, fake.getRequestCount())
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun clearFailuresClassifyAbsenceAndProviderFaults(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val absence = listOf(
                ClearCredentialUnsupportedException("synthetic clear unsupported"),
                ClearCredentialProviderConfigurationException("synthetic clear misconfigured"),
            )
            for (fault in absence) {
                val fake = FakeCredentialClient()
                fake.clearBehavior = { throw fault }
                val provider = buildProvider(scenario, AUDIENCE, fake)
                expectFailure(GoogleIdentityFailureReason.UNAVAILABLE, AUDIENCE) {
                    provider.clearCredentialState()
                }
                provider.close()
            }
            val rawMarker = "synthetic-clear-leak-41bd"
            val failing = FakeCredentialClient()
            failing.clearBehavior = { throw ClearCredentialUnknownException(rawMarker) }
            val provider = buildProvider(scenario, AUDIENCE, failing)
            expectFailure(GoogleIdentityFailureReason.PROVIDER_FAILURE, AUDIENCE, rawMarker) {
                provider.clearCredentialState()
            }
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun clearCallerCancellationPropagates(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val entered = CompletableDeferred<Unit>()
            val hang = CompletableDeferred<Unit>()
            val fake = FakeCredentialClient()
            fake.clearBehavior = {
                entered.complete(Unit)
                hang.await()
            }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            val deferred = async { provider.clearCredentialState() }
            entered.await()
            deferred.cancel()
            expectCallerCancellation { deferred.await() }
            provider.close()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun clearNeverRunsAutomatically(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            val fake = FakeCredentialClient()
            fake.getBehavior = { _, _ -> throw NoCredentialException("synthetic absent") }
            val provider = buildProvider(scenario, AUDIENCE, fake)
            expectFailure(GoogleIdentityFailureReason.UNAVAILABLE, AUDIENCE, NONCE) {
                provider.credentialFor(NONCE)
            }
            assertEquals("no cleanup clear on get failure", 0, fake.clearRequestCount())
            provider.close()
            assertEquals(0, fake.clearRequestCount())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun nonPositiveTimeoutIsRejectedWithoutCrashingMain(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            for (timeout in listOf(0L, -1L)) {
                val fake = FakeCredentialClient()
                try {
                    buildProvider(scenario, AUDIENCE, fake, timeout)
                    fail("expected IllegalArgumentException for timeout=$timeout")
                } catch (e: IllegalArgumentException) {
                    // Expected: requestTimeoutMillis must be positive.
                }
                assertEquals(0, fake.getRequestCount())
            }
        } finally {
            scenario.close()
        }
    }
}
