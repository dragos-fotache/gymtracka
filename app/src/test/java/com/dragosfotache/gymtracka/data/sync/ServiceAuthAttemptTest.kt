package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R02d attempt tests: capture/restore exact-body invariants, typed delegation,
 * redaction and cancellation. Independent fixtures only. Failure text may name
 * represented fields; raw distinct sentinels must never be echoed.
 */
class ServiceAuthAttemptTest {

    companion object {
        const val ACCOUNT_ID = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val CHALLENGE_ID = "a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5"
        const val KEY_A = "11111111-2222-4333-8444-555555555555"
        const val KEY_B = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val ACCESS_AT_MILLIS = 0L
        const val REFRESH_AT_MILLIS = 0L
    }

    private fun assertSyncFails(vararg secrets: String, block: () -> Unit): SyncModelException {
        try {
            block()
            fail("expected SyncModelException")
            throw AssertionError("unreachable")
        } catch (e: SyncModelException) {
            val message = e.message ?: ""
            assertTrue(message.length in 1..300)
            for (s in secrets) {
                if (s.isNotEmpty()) assertFalse(message.contains(s))
            }
            assertNull(e.cause)
            assertTrue(e.suppressed.isEmpty())
            return e
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            fail("expected SyncModelException but was " + e.javaClass.name)
            throw AssertionError("unreachable")
        }
    }

    private fun cannedSession(): ServiceSession = ServiceSession(
        account = ServiceAccountRef(ACCOUNT_ID, "Example"),
        accessToken = OpaqueToken("canned-access"),
        accessExpiresAtMillis = ACCESS_AT_MILLIS,
        refreshToken = OpaqueToken("canned-refresh"),
        refreshExpiresAtMillis = REFRESH_AT_MILLIS,
    )

    private class RecordingAuthApi(
        val session: ServiceSession,
        var failWith: Throwable? = null,
    ) : ServiceAuthApi {
        val challengePurposes = mutableListOf<GoogleChallengePurpose>()
        val exchangeAttempts = mutableListOf<GoogleExchangeAttempt>()
        val refreshAttempts = mutableListOf<ServiceRefreshAttempt>()
        val logoutTokens = mutableListOf<OpaqueToken>()

        override suspend fun challenge(purpose: GoogleChallengePurpose): ServiceChallenge {
            challengePurposes.add(purpose)
            failWith?.let { throw it }
            return ServiceChallenge(CHALLENGE_ID, "recorded-nonce", 0L)
        }

        override suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession {
            exchangeAttempts.add(attempt)
            failWith?.let { throw it }
            return session
        }

        override suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession {
            refreshAttempts.add(attempt)
            failWith?.let { throw it }
            return session
        }

        override suspend fun logout(refreshToken: OpaqueToken) {
            logoutTokens.add(refreshToken)
            failWith?.let { throw it }
        }
    }

    // ---- Capture ----

    @Test
    fun exchangeCapture_validatesAndEncodesOnce() {
        val attempt = GoogleExchangeAttempt.capture(KEY_A, CHALLENGE_ID, OpaqueToken("google-id-token-001"))
        assertEquals(KEY_A, attempt.idempotencyKey)
        assertEquals(CHALLENGE_ID, attempt.challengeId)
        assertEquals("google-id-token-001", attempt.idToken.value)
        val parsed = JSONObject(attempt.requestJson)
        assertEquals(2, parsed.length())
        assertEquals(CHALLENGE_ID, parsed.getString("challengeId"))
        assertEquals("google-id-token-001", parsed.getString("idToken"))
        assertFalse("key is header data, never body", attempt.requestJson.contains(KEY_A))
        assertEquals(attempt.requestJson, attempt.requestJson)
    }

    @Test
    fun refreshCapture_validatesAndEncodesOnce() {
        val attempt = ServiceRefreshAttempt.capture(KEY_A, OpaqueToken("opaque-refresh-001"))
        assertEquals(KEY_A, attempt.idempotencyKey)
        assertEquals("opaque-refresh-001", attempt.refreshToken.value)
        val parsed = JSONObject(attempt.requestJson)
        assertEquals(1, parsed.length())
        assertEquals("opaque-refresh-001", parsed.getString("refreshToken"))
        assertFalse(attempt.requestJson.contains(KEY_A))
    }

    @Test
    fun capture_preservesOpaqueWhitespaceAndUnicode() {
        val ws = GoogleExchangeAttempt.capture(KEY_A, CHALLENGE_ID, OpaqueToken("   "))
        assertEquals("   ", ws.idToken.value)
        assertEquals("   ", JSONObject(ws.requestJson).getString("idToken"))
        val uni = ServiceRefreshAttempt.capture(KEY_A, OpaqueToken("tökén-🎉 "))
        assertEquals("tökén-🎉 ", uni.refreshToken.value)
        val looking = GoogleExchangeAttempt.capture(KEY_A, CHALLENGE_ID, OpaqueToken("{\"challengeId\":\"x\"}"))
        assertEquals("{\"challengeId\":\"x\"}", looking.idToken.value)
    }

    @Test
    fun capture_rejectsBadHeaderKeysEqually() {
        val badKeys = listOf("", "not-a-uuid", "AAAAAAAA-2222-4333-8444-555555555555", "short")
        var first: String? = null
        for (bad in badKeys) {
            val fragment = if (bad.length >= 8) bad.take(8) else "zzz-badkey"
            val m1 = assertSyncFails(fragment) {
                GoogleExchangeAttempt.capture(bad, CHALLENGE_ID, OpaqueToken("t"))
            }.message
            if (first == null) first = m1
            assertSyncFails(fragment) {
                ServiceRefreshAttempt.capture(bad, OpaqueToken("t"))
            }
        }
        val m2 = assertSyncFails("zzz-key-secret-QQ") {
            GoogleExchangeAttempt.capture("zzz-key-secret-QQ", CHALLENGE_ID, OpaqueToken("t"))
        }.message
        assertEquals("fixed text per key category", first, m2)
    }

    @Test
    fun capture_rejectsBadFields() {
        val m1 = assertSyncFails("zzz-challenge-secret-AAA") {
            GoogleExchangeAttempt.capture(KEY_A, "zzz-challenge-secret-AAA", OpaqueToken("t"))
        }.message
        val m2 = assertSyncFails("zzz-challenge-secret-BBB") {
            GoogleExchangeAttempt.capture(KEY_A, "zzz-challenge-secret-BBB", OpaqueToken("t"))
        }.message
        assertEquals("fixed text per challenge category", m1, m2)
        try {
            OpaqueToken("")
            fail("expected IllegalArgumentException for empty token")
        } catch (e: IllegalArgumentException) {
            // legacy compatible
        }
    }

    @Test
    fun noPublicConstructorOrCopyForAttempts() {
        for (cls in listOf(GoogleExchangeAttempt::class.java, ServiceRefreshAttempt::class.java)) {
            var checked = 0
            for (c in cls.declaredConstructors) {
                if (c.isSynthetic) continue
                assertTrue(cls.simpleName + " constructor must be private", Modifier.isPrivate(c.modifiers))
                checked++
            }
            assertTrue(cls.simpleName + " must declare a private constructor", checked > 0)
            assertTrue(
                cls.simpleName + " must not expose copy",
                cls.declaredMethods.none { it.name == "copy" },
            )
            assertTrue(
                cls.simpleName + " must not expose componentN",
                cls.declaredMethods.none { it.name.startsWith("component") },
            )
        }
    }

    // ---- Restore ----

    @Test
    fun exchangeRestore_retainsOriginalBytes() {
        val original = "{\n  \"idToken\" : \"abc-123\" , \"challengeId\" : \"" + CHALLENGE_ID + "\"\n}"
        val attempt = GoogleExchangeAttempt.fromJson(KEY_A, original)
        assertEquals(original, attempt.requestJson)
        assertEquals(KEY_A, attempt.idempotencyKey)
        assertEquals(CHALLENGE_ID, attempt.challengeId)
        assertEquals("abc-123", attempt.idToken.value)
    }

    @Test
    fun refreshRestore_retainsOuterWhitespaceAndEscapes() {
        val original = "  { \"refreshToken\" : \"a\\u0041c\" }  "
        val attempt = ServiceRefreshAttempt.fromJson(KEY_A, original)
        assertEquals(original, attempt.requestJson)
        assertEquals("aAc", attempt.refreshToken.value)
    }

    @Test
    fun restore_changedBytesRemainDistinctBody() {
        val a = GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"t\"}")
        val b = GoogleExchangeAttempt.fromJson(KEY_A, "{ \"idToken\" : \"t\" , \"challengeId\" : \"" + CHALLENGE_ID + "\" }")
        assertEquals(a.challengeId, b.challengeId)
        assertEquals(a.idToken.value, b.idToken.value)
        assertFalse(a.requestJson == b.requestJson)
    }

    @Test
    fun exchangeRestore_rejectsEachRequiredField() {
        // challengeId missing / null / wrong types
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"idToken\":\"t\"}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":null,\"idToken\":\"t\"}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":42,\"idToken\":\"t\"}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":true,\"idToken\":\"t\"}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":[],\"idToken\":\"t\"}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":{},\"idToken\":\"t\"}") }
        val m1 = assertSyncFails("zzz-challenge-secret-AAA") {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"zzz-challenge-secret-AAA\",\"idToken\":\"t\"}")
        }.message
        val m2 = assertSyncFails("zzz-challenge-secret-BBB") {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"zzz-challenge-secret-BBB\",\"idToken\":\"t\"}")
        }.message
        assertEquals("fixed text per challenge category", m1, m2)
        // idToken missing / null / wrong types / empty
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\"}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":null}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":42}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":false}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":[]}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":{}}") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"\"}") }
        // unquoted opaque regression
        assertSyncFails("zzz-token-secret") {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":zzz-token-secret}")
        }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":NaN}") }
        // forbidden extras with distinct value sentinels
        assertSyncFails("zzz-extra-val-AAA") {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"t\",\"purpose\":\"zzz-extra-val-AAA\"}")
        }
        assertSyncFails("zzz-extra-val-BBB") {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"t\",\"account\":\"zzz-extra-val-BBB\"}")
        }
        assertSyncFails("zzz-extra-val-CCC") {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"t\",\"owner\":\"zzz-extra-val-CCC\"}")
        }
        assertSyncFails("zzz-extra-val-DDD") {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"t\",\"idempotencyKey\":\"zzz-extra-val-DDD\"}")
        }
        // duplicates incl. escaped
        val d1 = assertSyncFails {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"t\",\"idToken\":\"u\"}")
        }.message
        val d2 = assertSyncFails {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"t\",\"\\u0069dToken\":\"u\"}")
        }.message
        assertEquals("fixed text per duplicate category", d1, d2)
        // malformed + bad key
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "") }
        assertSyncFails { GoogleExchangeAttempt.fromJson(KEY_A, "null") }
        assertSyncFails("zzz-key-secret-AAA") { GoogleExchangeAttempt.fromJson("zzz-key-secret-AAA", "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"t\"}") }
    }

    @Test
    fun refreshRestore_rejectsEachRequiredField() {
        // refreshToken missing / null / wrong types / empty
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{}") }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":null}") }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":42}") }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":true}") }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":[]}") }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":{}}") }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":\"\"}") }
        // unquoted opaque regression
        assertSyncFails("zzz-token-secret") {
            ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":zzz-token-secret}")
        }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":NaN}") }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":foobar}") }
        // forbidden extras with distinct value sentinels
        assertSyncFails("zzz-extra-val-EEE") {
            ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":\"t\",\"extra\":\"zzz-extra-val-EEE\"}")
        }
        assertSyncFails("zzz-extra-val-FFF") {
            ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":\"t\",\"purpose\":\"zzz-extra-val-FFF\"}")
        }
        // duplicates incl. escaped refreshToken
        val d1 = assertSyncFails {
            ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":\"a\",\"refreshToken\":\"b\"}")
        }.message
        val d2 = assertSyncFails {
            ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":\"a\",\"\\u0072efreshToken\":\"b\"}")
        }.message
        assertEquals("fixed text per duplicate category", d1, d2)
        // malformed + bad key
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "") }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "null") }
        val k1 = assertSyncFails("zzz-key-secret-AAA") {
            ServiceRefreshAttempt.fromJson("zzz-key-secret-AAA", "{\"refreshToken\":\"t\"}")
        }.message
        val k2 = assertSyncFails("zzz-key-secret-BBB") {
            ServiceRefreshAttempt.fromJson("zzz-key-secret-BBB", "{\"refreshToken\":\"t\"}")
        }.message
        assertEquals("fixed text per key category", k1, k2)
    }

    @Test
    fun restoredUnicodeBody_forwardedByteIdenticalViaTypedBoundary() = runTest {
        val api = RecordingAuthApi(cannedSession())
        val exchangeOriginal = "{ \"idToken\" : \"tökén-🎉-\\u0041\" , \"challengeId\" : \"" + CHALLENGE_ID + "\" }"
        val exchange = GoogleExchangeAttempt.fromJson(KEY_A, exchangeOriginal)
        api.exchange(exchange)
        val recordedExchange = api.exchangeAttempts.single()
        assertEquals(exchangeOriginal, recordedExchange.requestJson)
        assertArrayEquals(
            exchangeOriginal.toByteArray(StandardCharsets.UTF_8),
            recordedExchange.requestJson.toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals("tökén-🎉-A", recordedExchange.idToken.value)
        val refreshOriginal = "  { \"refreshToken\" : \"rêfrësh-🎉-\\u0041\" }  "
        val refresh = ServiceRefreshAttempt.fromJson(KEY_B, refreshOriginal)
        api.refresh(refresh)
        val recordedRefresh = api.refreshAttempts.single()
        assertEquals(refreshOriginal, recordedRefresh.requestJson)
        assertArrayEquals(
            refreshOriginal.toByteArray(StandardCharsets.UTF_8),
            recordedRefresh.requestJson.toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals("rêfrësh-🎉-A", recordedRefresh.refreshToken.value)
    }

    @Test
    fun restore_rejectsLiteralControlsAcceptsEscapes() {
        for (raw in listOf("\n", "\t", "\u0000", "\u001F")) {
            assertSyncFails {
                GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"a" + raw + "b\"}")
            }
            assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":\"a" + raw + "b\"}") }
        }
        val ex = GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"a\\nb\"}")
        assertEquals("a\nb", ex.idToken.value)
        val re = ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":\"x\\u0000y\"}")
        assertEquals("x\u0000y", re.refreshToken.value)
        assertSyncFails {
            GoogleExchangeAttempt.fromJson(KEY_A, "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"idToken\":\"a\\\\\nb\"}")
        }
        assertSyncFails { ServiceRefreshAttempt.fromJson(KEY_A, "{\"refreshToken\":\"a\\\\\nb\"}") }
    }

    // ---- Typed sends, legacy delegation, default challenge ----

    @Test
    fun typedSends_exposeIdenticalKeyAndBody() = runTest {
        val api = RecordingAuthApi(cannedSession())
        val attempt = GoogleExchangeAttempt.capture(KEY_A, CHALLENGE_ID, OpaqueToken("tok"))
        api.exchange(attempt)
        api.exchange(attempt)
        assertEquals(2, api.exchangeAttempts.size)
        assertEquals(KEY_A, api.exchangeAttempts[0].idempotencyKey)
        assertEquals(api.exchangeAttempts[0].requestJson, api.exchangeAttempts[1].requestJson)
        val refresh = ServiceRefreshAttempt.capture(KEY_B, OpaqueToken("ref"))
        api.refresh(refresh)
        api.refresh(refresh)
        assertEquals(api.refreshAttempts[0].requestJson, api.refreshAttempts[1].requestJson)
    }

    @Test
    fun legacyConvenience_delegatesToTypedOnce() = runTest {
        val api = RecordingAuthApi(cannedSession())
        val out = api.exchange(KEY_A, CHALLENGE_ID, OpaqueToken("tok-legacy"))
        assertEquals("canned-access", out.accessToken.value)
        assertEquals(1, api.exchangeAttempts.size)
        val recorded = api.exchangeAttempts[0]
        assertEquals(KEY_A, recorded.idempotencyKey)
        assertEquals(CHALLENGE_ID, recorded.challengeId)
        assertEquals("tok-legacy", recorded.idToken.value)
        val out2 = api.refresh(KEY_B, OpaqueToken("ref-legacy"))
        assertEquals("canned-refresh", out2.refreshToken.value)
        assertEquals(1, api.refreshAttempts.size)
        assertEquals(KEY_B, api.refreshAttempts[0].idempotencyKey)
        assertEquals("ref-legacy", api.refreshAttempts[0].refreshToken.value)
    }

    @Test
    fun defaultNoArgChallenge_usesSignIn() = runTest {
        val api = RecordingAuthApi(cannedSession())
        api.challenge()
        assertEquals(listOf(GoogleChallengePurpose.SIGN_IN), api.challengePurposes)
        api.challenge(GoogleChallengePurpose.EXPORT)
        assertEquals(GoogleChallengePurpose.EXPORT, api.challengePurposes[1])
    }

    // ---- Redaction ----

    @Test
    fun attemptToString_fixedRedacted() {
        val a = GoogleExchangeAttempt.capture(KEY_A, CHALLENGE_ID, OpaqueToken("secret-a"))
        val b = GoogleExchangeAttempt.capture(KEY_B, CHALLENGE_ID, OpaqueToken("secret-b"))
        assertEquals(a.toString(), b.toString())
        for (text in listOf(a.toString(), b.toString())) {
            assertFalse(text.contains("secret-a"))
            assertFalse(text.contains("secret-b"))
            assertFalse(text.contains(KEY_A))
            assertFalse(text.contains(KEY_B))
            assertFalse(text.contains(CHALLENGE_ID))
        }
        val ra = ServiceRefreshAttempt.capture(KEY_A, OpaqueToken("r-secret-a"))
        val rb = ServiceRefreshAttempt.capture(KEY_B, OpaqueToken("r-secret-b"))
        assertEquals(ra.toString(), rb.toString())
        assertFalse(ra.toString().contains("r-secret-a"))
        assertFalse(ra.toString().contains(KEY_A))
    }

    // ---- Cancellation ----

    @Test
    fun cancellation_propagatesWithoutMutatingAttempt() = runTest {
        val cancellation = CancellationException("boom-cancel")
        val api = RecordingAuthApi(cannedSession(), failWith = cancellation)
        val exchange = GoogleExchangeAttempt.capture(KEY_A, CHALLENGE_ID, OpaqueToken("tok"))
        val exchangeJson = exchange.requestJson
        try {
            api.exchange(exchange)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertEquals(exchangeJson, exchange.requestJson)
        assertEquals(KEY_A, exchange.idempotencyKey)
        assertEquals("tok", exchange.idToken.value)
        val refresh = ServiceRefreshAttempt.capture(KEY_B, OpaqueToken("ref"))
        val refreshJson = refresh.requestJson
        try {
            api.refresh(refresh)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertEquals(refreshJson, refresh.requestJson)
        assertEquals(KEY_B, refresh.idempotencyKey)
        assertEquals("ref", refresh.refreshToken.value)
        try {
            api.exchange(KEY_A, CHALLENGE_ID, OpaqueToken("tok-legacy"))
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame("legacy exchange must not wrap cancellation", cancellation, e)
        }
        try {
            api.refresh(KEY_B, OpaqueToken("ref-legacy"))
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame("legacy refresh must not wrap cancellation", cancellation, e)
        }
        assertEquals(exchangeJson, exchange.requestJson)
        assertEquals(refreshJson, refresh.requestJson)
        assertEquals(KEY_A, api.exchangeAttempts[0].idempotencyKey)
        assertEquals(exchangeJson, api.exchangeAttempts[0].requestJson)
        assertEquals(KEY_A, api.exchangeAttempts[1].idempotencyKey)
        assertEquals("tok-legacy", api.exchangeAttempts[1].idToken.value)
        assertEquals(KEY_B, api.refreshAttempts[1].idempotencyKey)
        assertEquals("ref-legacy", api.refreshAttempts[1].refreshToken.value)
    }
}
