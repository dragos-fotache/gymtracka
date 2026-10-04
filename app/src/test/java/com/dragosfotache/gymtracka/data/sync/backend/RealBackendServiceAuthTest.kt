package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpMethod
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpRequest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Black-box production-auth interoperability; no Google success/bypass claim. */
class RealBackendServiceAuthTest {
    @Test
    fun allPurposeChallengesAreDistinctAndHaveServiceLifetime(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val api = fixture.authApi()
            val start = System.currentTimeMillis()
            val challenges = GoogleChallengePurpose.entries.map { api.challenge(it) }
            val end = System.currentTimeMillis()
            assertEquals(4, challenges.map { it.challengeId }.toSet().size)
            assertEquals(4, challenges.map { it.nonce }.toSet().size)
            challenges.forEach {
                assertTrue(it.nonce.isNotEmpty())
                assertTrue(it.expiresAtMillis >= start + 300_000L - 1_000L)
                assertTrue(it.expiresAtMillis <= end + 300_000L + 1_000L)
            }
            assertTrue(api.challenge().nonce.isNotEmpty())
        }
    }

    @Test
    fun refreshReturnsSameAccountAndOriginalGrantOnExactReplay(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val session = fixture.newAuthSession()
            val api = fixture.authApi()
            val exact = " \n{ \"refreshToken\" : ${JSONObject.quote(session.refreshToken.value)} }\n "
            val attempt = ServiceRefreshAttempt.fromJson(key(), exact)
            val before = System.currentTimeMillis()
            val grant = api.refresh(attempt)
            val after = System.currentTimeMillis()
            assertEquals(session.account.accountId, grant.account.accountId)
            assertNotEquals(session.refreshToken, grant.refreshToken)
            assertTrue(grant.accessToken.value.isNotEmpty())
            assertTrue(grant.accessExpiresAtMillis >= before + 900_000L - 1_000L)
            assertTrue(grant.accessExpiresAtMillis <= after + 900_000L + 1_000L)
            assertTrue(grant.refreshExpiresAtMillis >= before + 2_592_000_000L - 1_000L)
            assertTrue(grant.refreshExpiresAtMillis <= after + 2_592_000_000L + 1_000L)
            assertEquals(grant, api.refresh(ServiceRefreshAttempt.fromJson(attempt.idempotencyKey, exact)))
            assertEquals(exact, attempt.requestJson)
        }
    }

    @Test
    fun changedBytesUnderCommittedKeyRejectWithoutReplacingOriginalGrant(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val session = fixture.newAuthSession()
            val api = fixture.authApi()
            val body = "{\"refreshToken\":${JSONObject.quote(session.refreshToken.value)}}"
            val attempt = ServiceRefreshAttempt.fromJson(key(), body)
            val grant = api.refresh(attempt)
            rejected("idempotency_key_reused") {
                api.refresh(ServiceRefreshAttempt.fromJson(attempt.idempotencyKey, " $body"))
            }
            assertEquals(grant, api.refresh(attempt))
        }
    }

    @Test
    fun unknownAndExpiredRefreshRequireForegroundIdentity(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val api = fixture.authApi()
            reauthentication { api.refresh(ServiceRefreshAttempt.capture(key(), OpaqueToken("unknown-refresh"))) }
            val expired = fixture.newAuthSession(expiredRefresh = true)
            reauthentication { api.refresh(ServiceRefreshAttempt.capture(key(), expired.refreshToken)) }
        }
    }

    @Test
    fun consumedTokenDifferentKeyRevokesOnlyItsOwnSession(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val original = fixture.newAuthSession()
            val other = fixture.newAuthSession(existingAccount = original.account)
            val api = fixture.authApi()
            val attempt = ServiceRefreshAttempt.capture(key(), original.refreshToken)
            val grant = api.refresh(attempt)
            reauthentication { api.refresh(ServiceRefreshAttempt.capture(key(), original.refreshToken)) }
            reauthentication { api.refresh(attempt) }
            reauthentication { api.refresh(ServiceRefreshAttempt.capture(key(), grant.refreshToken)) }
            val healthy = api.refresh(ServiceRefreshAttempt.capture(key(), other.refreshToken))
            assertEquals(other.account.accountId, healthy.account.accountId)
        }
    }

    @Test
    fun logoutImmediatelyRevokesAccessAndRefreshButNotOtherSessions(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val session = fixture.newAuthSession()
            val other = fixture.newAuthSession(existingAccount = session.account)
            val api = fixture.authApi()
            assertFalse(fixture.apiFor(session.account).pull(null).hasMore)
            api.logout(session.refreshToken)
            api.logout(session.refreshToken)
            try {
                fixture.apiFor(session.account).pull(null)
                fail("Expected revoked access rejection")
            } catch (failure: SyncApiException.AuthenticationRequired) {
                assertEquals("authentication_required", failure.code)
            }
            reauthentication { api.refresh(ServiceRefreshAttempt.capture(key(), session.refreshToken)) }
            assertFalse(fixture.apiFor(other.account).pull(null).hasMore)
            assertEquals(other.account.accountId, api.refresh(ServiceRefreshAttempt.capture(key(), other.refreshToken)).account.accountId)
        }
    }

    @Test
    fun logoutWithPreviouslyRotatedCredentialRevokesOriginalReplay(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val session = fixture.newAuthSession()
            val api = fixture.authApi()
            val attempt = ServiceRefreshAttempt.capture(key(), session.refreshToken)
            val grant = api.refresh(attempt)
            api.logout(session.refreshToken)
            reauthentication { api.refresh(attempt) }
            reauthentication { api.refresh(ServiceRefreshAttempt.capture(key(), grant.refreshToken)) }
        }
    }

    @Test
    fun unknownAndExpiredLogoutAreBodylessSuccess(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val api = fixture.authApi()
            api.logout(OpaqueToken("unknown-logout"))
            val expired = fixture.newAuthSession(expiredRefresh = true)
            api.logout(expired.refreshToken)
        }
    }

    @Test
    fun unverifiedGoogleCredentialIsPermanentProofRejection(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            val api = fixture.authApi()
            val challenge = api.challenge()
            val attempt = GoogleExchangeAttempt.capture(key(), challenge.challengeId, OpaqueToken("not-a-google-id-token"))
            rejected("invalid_google_credential") { api.exchange(attempt) }
            rejected("invalid_google_credential") { api.exchange(attempt) }
        }
    }

    /** Diagnostic contract assertion: never logs response bodies or credentials. */
    @Test
    fun successfulAuthExpiriesAreLosslessMilliseconds(): Unit = runBlocking {
        RealBackendFixture.load().use { fixture ->
            RealBackendHttpBridge(fixture.baseUrl).use { bridge ->
                suspend fun response(
                    path: String,
                    body: String,
                    headers: Map<String, String> = emptyMap(),
                ): JSONObject = bridge.transport.withResponse(
                    ServiceHttpRequest(ServiceHttpMethod.POST, path, headers = headers, body = body),
                ) {
                    assertEquals(200, it.statusCode)
                    JSONObject(it.readJsonBody())
                }
                val challenge = response("/v1/auth/google/challenge", "{}")
                val setup = fixture.newAuthSession()
                val grant = response(
                    "/v1/auth/refresh",
                    "{\"refreshToken\":${JSONObject.quote(setup.refreshToken.value)}}",
                    mapOf("Idempotency-Key" to key()),
                )
                fun compatible(value: String): Boolean {
                    val fraction = Regex("\\.(\\d+)Z$").find(value)?.groupValues?.get(1) ?: return value.endsWith("Z")
                    return fraction.drop(3).all { it == '0' }
                }
                val challengeCompatible = compatible(challenge.getString("expiresAt"))
                val accessCompatible = compatible(grant.getString("accessExpiresAt"))
                val refreshCompatible = compatible(grant.getString("refreshExpiresAt"))
                println(
                    "Auth lossless-millisecond expiry: challenge=$challengeCompatible, " +
                        "access=$accessCompatible, refresh=$refreshCompatible",
                )
                assertTrue(
                    "All auth expiries must be losslessly representable in milliseconds",
                    challengeCompatible && accessCompatible && refreshCompatible,
                )
            }
        }
    }

    private fun key(): String = UUID.randomUUID().toString()

    private suspend fun reauthentication(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected foreground reauthentication")
        } catch (failure: SyncApiException.ReauthenticationRequired) {
            assertEquals("reauthentication_required", failure.code)
        }
    }

    private suspend fun rejected(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected permanent auth rejection")
        } catch (failure: SyncApiException.RequestRejected) {
            assertEquals(code, failure.errorCode)
        }
    }
}
