package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.api.AccessTokenProvider
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityProvider
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Boundary tests for the M4a identity/token surface: redacted credential
 * display, exact credential/key forwarding through hand-written fakes, and
 * the four documented auth signatures. Vault storage, refresh orchestration,
 * account transitions, and real Google/HTTP adapters remain later tasks.
 */
class ServiceAuthBoundaryTest {
    companion object {
        const val ACCOUNT_ID = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val CHALLENGE_ID = "c0ffee00-1234-5678-9abc-def012345678"
        const val KEY_EXCHANGE = "11111111-1111-1111-1111-111111111111"
        const val KEY_REFRESH = "22222222-2222-2222-2222-222222222222"
    }

    private class FakeGoogleIdentityProvider : GoogleIdentityProvider {
        val nonces = mutableListOf<String>()
        var credential = OpaqueToken("google-id-token")
        var clears = 0

        override suspend fun credentialFor(nonce: String): OpaqueToken {
            require(nonce.isNotEmpty()) { "nonce must not be empty" }
            nonces += nonce
            return credential
        }

        override suspend fun clearCredentialState() {
            clears++
        }
    }

    private class FakeServiceAuthApi : ServiceAuthApi {
        data class ExchangeCall(
            val idempotencyKey: String,
            val challengeId: String,
            val idToken: OpaqueToken,
        )

        data class RefreshCall(
            val idempotencyKey: String,
            val refreshToken: OpaqueToken,
        )

        val exchangeCalls = mutableListOf<ExchangeCall>()
        val refreshCalls = mutableListOf<RefreshCall>()
        val loggedOutTokens = mutableListOf<OpaqueToken>()
        var session = ServiceSession(
            account = ServiceAccountRef(ACCOUNT_ID, "Example"),
            accessToken = OpaqueToken("access-credential"),
            accessExpiresAtMillis = 1_700_000_000_000L,
            refreshToken = OpaqueToken("refresh-credential"),
            refreshExpiresAtMillis = 1_730_000_000_000L,
        )

        override suspend fun challenge(): ServiceChallenge =
            ServiceChallenge(CHALLENGE_ID, "server-nonce", 1_700_000_000_000L)

        override suspend fun exchange(
            idempotencyKey: String,
            challengeId: String,
            idToken: OpaqueToken,
        ): ServiceSession {
            requireWorkoutUuid(idempotencyKey, "idempotencyKey")
            requireWorkoutUuid(challengeId, "challengeId")
            exchangeCalls += ExchangeCall(idempotencyKey, challengeId, idToken)
            return session
        }

        override suspend fun refresh(idempotencyKey: String, refreshToken: OpaqueToken): ServiceSession {
            requireWorkoutUuid(idempotencyKey, "idempotencyKey")
            refreshCalls += RefreshCall(idempotencyKey, refreshToken)
            return session
        }

        override suspend fun logout(refreshToken: OpaqueToken) {
            loggedOutTokens += refreshToken
        }
    }

    private class FakeAccessTokenProvider(val token: OpaqueToken) : AccessTokenProvider {
        var calls = 0

        override suspend fun accessToken(): OpaqueToken {
            calls++
            return token
        }
    }

    @Test
    fun tokenBearingObjectsRedactToString() {
        val access = OpaqueToken("opaque-service-credential")
        assertFalse(access.toString().contains("opaque-service-credential"))
        assertTrue(access.toString().contains("redacted"))
        val session = ServiceSession(
            account = ServiceAccountRef(ACCOUNT_ID, "Example"),
            accessToken = OpaqueToken("access-secret"),
            accessExpiresAtMillis = 1L,
            refreshToken = OpaqueToken("refresh-secret"),
            refreshExpiresAtMillis = 2L,
        )
        val text = session.toString()
        assertTrue(text.contains(ACCOUNT_ID))
        assertFalse(text.contains("access-secret"))
        assertFalse(text.contains("refresh-secret"))
    }

    @Test
    fun challengeExchangeRefreshLogoutForwardExactly() = runTest {
        val identity = FakeGoogleIdentityProvider()
        val auth = FakeServiceAuthApi()
        val challenge = auth.challenge()
        assertEquals(CHALLENGE_ID, challenge.challengeId)

        val idToken = identity.credentialFor(challenge.nonce)
        assertSame(identity.credential, idToken)
        assertEquals(listOf("server-nonce"), identity.nonces)

        val grant = auth.exchange(KEY_EXCHANGE, challenge.challengeId, idToken)
        assertEquals(ACCOUNT_ID, grant.account.accountId)
        assertEquals(1, auth.exchangeCalls.size)
        val exchange = auth.exchangeCalls.single()
        assertEquals(KEY_EXCHANGE, exchange.idempotencyKey)
        assertEquals(CHALLENGE_ID, exchange.challengeId)
        assertSame(idToken, exchange.idToken)

        // Identical retry replays the same grant with the same key.
        val replayed = auth.exchange(KEY_EXCHANGE, challenge.challengeId, idToken)
        assertEquals(grant, replayed)
        assertEquals(2, auth.exchangeCalls.size)

        val renewed = auth.refresh(KEY_REFRESH, grant.refreshToken)
        assertEquals(ACCOUNT_ID, renewed.account.accountId)
        assertSame(grant.refreshToken, auth.refreshCalls.single().refreshToken)

        auth.logout(renewed.refreshToken)
        auth.logout(renewed.refreshToken)
        assertEquals(2, auth.loggedOutTokens.size)

        identity.clearCredentialState()
        assertEquals(1, identity.clears)
    }

    @Test
    fun invalidKeysAndBlankNonceAreRejected() = runTest {
        val identity = FakeGoogleIdentityProvider()
        val auth = FakeServiceAuthApi()
        try {
            identity.credentialFor("")
            fail("Expected a blank nonce to be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            auth.exchange("not-a-uuid", CHALLENGE_ID, OpaqueToken("id-token"))
            fail("Expected a non-UUID idempotency key to be rejected")
        } catch (e: SyncModelException) {
            assertTrue(e.message!!.contains("idempotencyKey"))
        }
        try {
            auth.refresh(KEY_REFRESH, OpaqueToken("refresh"))
            assertEquals(1, auth.refreshCalls.size)
            auth.exchange(KEY_EXCHANGE, "ABCDEF12-1234-5678-9ABC-DEF012345678", OpaqueToken("id-token"))
            fail("Expected an uppercase challenge ID to be rejected")
        } catch (e: SyncModelException) {
            assertTrue(e.message!!.contains("challengeId"))
        }
    }

    @Test
    fun accessTokenProviderSuppliesBoundCredential() = runTest {
        val token = OpaqueToken("bound-access-credential")
        val provider = FakeAccessTokenProvider(token)
        assertSame(token, provider.accessToken())
        assertEquals(1, provider.calls)
    }
}
