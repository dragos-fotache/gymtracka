package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.codec.decodeGoogleChallengeRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceChallenge
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceSession
import com.dragosfotache.gymtracka.data.sync.codec.encodeGoogleChallengeRequest
import com.dragosfotache.gymtracka.data.sync.codec.encodeLogoutRequest
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * codec boundary tests. Independent fixtures only; production encoders are
 * never used to build expected bodies. Failure text may name represented fields;
 * raw secrets (distinct sentinel values) must never be echoed.
 */
class ServiceAuthCodecTest {

    companion object {
        const val ACCOUNT_ID = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val CHALLENGE_ID = "a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5"
        const val OTHER_UUID = "11111111-2222-4333-8444-555555555555"
        const val MIN_INSTANT = "0000-01-01T00:00:00Z"
        const val MAX_INSTANT = "9999-12-31T23:59:59.999Z"
        const val MIN_MILLIS = -62167219200000L
        const val MAX_MILLIS = 253402300799999L
        const val ACCESS_AT = "2026-09-27T12:15:00Z"
        const val REFRESH_AT = "2026-10-27T12:00:00Z"
    }

    private fun assertSyncFails(rawSecrets: List<String> = emptyList(), block: () -> Unit): SyncModelException {
        try {
            block()
            fail("expected SyncModelException")
            throw AssertionError("unreachable")
        } catch (e: SyncModelException) {
            assertSafe(e, rawSecrets)
            return e
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            fail("expected SyncModelException but was " + e.javaClass.name + ": " + e.message)
            throw AssertionError("unreachable")
        }
    }

    private fun assertSafe(e: SyncModelException, rawSecrets: List<String>) {
        val message = e.message ?: ""
        assertTrue("failure text must be short fixed text", message.length in 1..300)
        for (secret in rawSecrets) {
            if (secret.isEmpty()) continue
            assertFalse(
                "failure must not echo raw input: " + secret.take(24),
                message.contains(secret),
            )
        }
        assertNull("cause must be null", e.cause)
        assertTrue("no suppressed raw exceptions", e.suppressed.isEmpty())
    }

    private fun millisOf(utc: String): Long = Instant.parse(utc).toEpochMilli()

    // ---- Purpose wire values ----

    @Test
    fun purposeWireValues_coverAllFour() {
        assertEquals("signIn", GoogleChallengePurpose.SIGN_IN.wireValue)
        assertEquals("export", GoogleChallengePurpose.EXPORT.wireValue)
        assertEquals("deleteAccount", GoogleChallengePurpose.DELETE_ACCOUNT.wireValue)
        assertEquals("accountStatus", GoogleChallengePurpose.ACCOUNT_STATUS.wireValue)
    }

    @Test
    fun purposeFromWire_roundTrips() {
        assertEquals(GoogleChallengePurpose.SIGN_IN, GoogleChallengePurpose.fromWire("signIn"))
        assertEquals(GoogleChallengePurpose.EXPORT, GoogleChallengePurpose.fromWire("export"))
        assertEquals(GoogleChallengePurpose.DELETE_ACCOUNT, GoogleChallengePurpose.fromWire("deleteAccount"))
        assertEquals(GoogleChallengePurpose.ACCOUNT_STATUS, GoogleChallengePurpose.fromWire("accountStatus"))
    }

    @Test
    fun purposeFromWire_rejectsUnknownAndCaseMismatch() {
        assertSyncFails(listOf("SignIn")) { GoogleChallengePurpose.fromWire("SignIn") }
        assertSyncFails(listOf("SIGNIN")) { GoogleChallengePurpose.fromWire("SIGNIN") }
        val m1 = assertSyncFails(listOf("zzz-enum-secret-AAA")) {
            GoogleChallengePurpose.fromWire("zzz-enum-secret-AAA")
        }.message
        val m2 = assertSyncFails(listOf("zzz-enum-secret-BBB")) {
            GoogleChallengePurpose.fromWire("zzz-enum-secret-BBB")
        }.message
        assertEquals("fixed text per invalid category", m1, m2)
    }

    // ---- Challenge request encode ----

    @Test
    fun challengeEncode_explicitPurposePerValue() {
        val cases = mapOf(
            GoogleChallengePurpose.SIGN_IN to "signIn",
            GoogleChallengePurpose.EXPORT to "export",
            GoogleChallengePurpose.DELETE_ACCOUNT to "deleteAccount",
            GoogleChallengePurpose.ACCOUNT_STATUS to "accountStatus",
        )
        for ((purpose, wire) in cases) {
            val body = encodeGoogleChallengeRequest(purpose)
            val parsed = JSONObject(body)
            assertEquals("exactly one field for " + wire, 1, parsed.length())
            assertEquals(wire, parsed.getString("purpose"))
            assertTrue("wire value observable in body", body.contains("\"" + wire + "\""))
        }
    }

    @Test
    fun challengeEncode_defaultEncodesSignInExplicitly() {
        val body = encodeGoogleChallengeRequest()
        val parsed = JSONObject(body)
        assertEquals(1, parsed.length())
        assertEquals("signIn", parsed.getString("purpose"))
        val explicit = JSONObject(encodeGoogleChallengeRequest(GoogleChallengePurpose.SIGN_IN))
        assertEquals(explicit.getString("purpose"), parsed.getString("purpose"))
        assertEquals(GoogleChallengePurpose.SIGN_IN, decodeGoogleChallengeRequest(body))
    }

    // ---- Challenge request decode ----

    @Test
    fun challengeRequestDecode_emptyObjectDefaultsSignIn() {
        assertEquals(GoogleChallengePurpose.SIGN_IN, decodeGoogleChallengeRequest("{}"))
        assertEquals(GoogleChallengePurpose.SIGN_IN, decodeGoogleChallengeRequest("  {  }  "))
        assertEquals(GoogleChallengePurpose.SIGN_IN, decodeGoogleChallengeRequest("{\n\t}\n"))
    }

    @Test
    fun challengeRequestDecode_eachPurpose() {
        assertEquals(GoogleChallengePurpose.SIGN_IN, decodeGoogleChallengeRequest("{\"purpose\":\"signIn\"}"))
        assertEquals(GoogleChallengePurpose.EXPORT, decodeGoogleChallengeRequest("{\"purpose\":\"export\"}"))
        assertEquals(
            GoogleChallengePurpose.DELETE_ACCOUNT,
            decodeGoogleChallengeRequest("{ \"purpose\" : \"deleteAccount\" }"),
        )
        assertEquals(
            GoogleChallengePurpose.ACCOUNT_STATUS,
            decodeGoogleChallengeRequest("{\"purpose\":\"accountStatus\"}"),
        )
    }

    @Test
    fun challengeRequestDecode_rejectsClosedFieldViolations() {
        assertSyncFails { decodeGoogleChallengeRequest("{\"purpose\":null}") }
        assertSyncFails { decodeGoogleChallengeRequest("{\"purpose\":42}") }
        assertSyncFails { decodeGoogleChallengeRequest("{\"purpose\":true}") }
        assertSyncFails { decodeGoogleChallengeRequest("{\"purpose\":{}}") }
        assertSyncFails { decodeGoogleChallengeRequest("{\"purpose\":[]}") }
        assertSyncFails(listOf("SignIn")) { decodeGoogleChallengeRequest("{\"purpose\":\"SignIn\"}") }
        val m1 = assertSyncFails(listOf("zzz-enum-secret-AAA")) {
            decodeGoogleChallengeRequest("{\"purpose\":\"zzz-enum-secret-AAA\"}")
        }.message
        val m2 = assertSyncFails(listOf("zzz-enum-secret-BBB")) {
            decodeGoogleChallengeRequest("{\"purpose\":\"zzz-enum-secret-BBB\"}")
        }.message
        assertEquals("fixed text per invalid enum category", m1, m2)
        val f1 = assertSyncFails(listOf("zzz-extra-secret-AAA")) {
            decodeGoogleChallengeRequest("{\"purpose\":\"signIn\",\"zzz-extra-secret-AAA\":1}")
        }.message
        val f2 = assertSyncFails(listOf("zzz-extra-secret-BBB")) {
            decodeGoogleChallengeRequest("{\"purpose\":\"signIn\",\"zzz-extra-secret-BBB\":1}")
        }.message
        assertEquals("fixed text per unknown-field category", f1, f2)
        assertSyncFails(listOf("zzz-unknown-secret-CCC")) {
            decodeGoogleChallengeRequest("{\"zzz-unknown-secret-CCC\":\"signIn\"}")
        }
        val d1 = assertSyncFails {
            decodeGoogleChallengeRequest("{\"purpose\":\"signIn\",\"purpose\":\"export\"}")
        }.message
        val d2 = assertSyncFails {
            decodeGoogleChallengeRequest("{\"purpose\":\"signIn\",\"\\u0070urpose\":\"export\"}")
        }.message
        assertEquals("fixed text per duplicate category", d1, d2)
    }

    @Test
    fun challengeRequestDecode_rejectsUnquotedOpaquePurpose() {
        assertSyncFails(listOf("foobar")) { decodeGoogleChallengeRequest("{\"purpose\":foobar}") }
        assertSyncFails(listOf("NaN")) { decodeGoogleChallengeRequest("{\"purpose\":NaN}") }
    }

    @Test
    fun challengeRequestDecode_rejectsMalformedShapes() {
        assertSyncFails { decodeGoogleChallengeRequest("") }
        assertSyncFails { decodeGoogleChallengeRequest("   ") }
        assertSyncFails { decodeGoogleChallengeRequest("{") }
        assertSyncFails { decodeGoogleChallengeRequest("null") }
        assertSyncFails { decodeGoogleChallengeRequest("[]") }
        assertSyncFails { decodeGoogleChallengeRequest("\"signIn\"") }
        assertSyncFails { decodeGoogleChallengeRequest("42") }
        assertSyncFails(listOf("zzz-trailing-secret")) { decodeGoogleChallengeRequest("{} zzz-trailing-secret") }
    }

    // ---- Challenge response decode ----

    @Test
    fun challengeResponseDecode_validMinimal() {
        val json = "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n-abc-123\",\"expiresAt\":\"" + ACCESS_AT + "\"}"
        val out = decodeServiceChallenge(json)
        assertEquals(CHALLENGE_ID, out.challengeId)
        assertEquals("n-abc-123", out.nonce)
        assertEquals(millisOf(ACCESS_AT), out.expiresAtMillis)
    }

    @Test
    fun challengeResponseDecode_millisecondLossless() {
        val a = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"2026-09-27T12:15:00.123Z\"}",
        )
        val b = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"2026-09-27T12:15:00.123000Z\"}",
        )
        assertEquals(a.expiresAtMillis, b.expiresAtMillis)
        assertEquals(millisOf("2026-09-27T12:15:00.123Z"), a.expiresAtMillis)
        val farZeroTail = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"2026-09-27T12:15:00.123000000000Z\"}",
        )
        assertEquals(a.expiresAtMillis, farZeroTail.expiresAtMillis)
        assertSyncFails {
            decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"2026-09-27T12:15:00.123000000001Z\"}")
        }
        val plain = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}",
        )
        assertEquals(millisOf(ACCESS_AT), plain.expiresAtMillis)
    }

    @Test
    fun challengeResponseDecode_acceptsBoundaryAndPreEpoch() {
        val min = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"" + MIN_INSTANT + "\"}",
        )
        assertEquals(MIN_MILLIS, min.expiresAtMillis)
        val max = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"" + MAX_INSTANT + "\"}",
        )
        assertEquals(MAX_MILLIS, max.expiresAtMillis)
        val pre = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"1960-01-02T03:04:05Z\"}",
        )
        assertEquals(millisOf("1960-01-02T03:04:05Z"), pre.expiresAtMillis)
    }

    @Test
    fun challengeResponseDecode_preservesOpaqueNonce() {
        val ws = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"   \",\"expiresAt\":\"" + ACCESS_AT + "\"}",
        )
        assertEquals("   ", ws.nonce)
        val uni = decodeServiceChallenge(
            "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"tökén-🎉 \",\"expiresAt\":\"" + ACCESS_AT + "\"}",
        )
        assertEquals("tökén-🎉 ", uni.nonce)
    }

    @Test
    fun challengeResponseDecode_ignoresAdditiveExtension() {
        val json = "{\"expiresAt\":\"" + ACCESS_AT + "\",\"nonce\":\"n\"," +
            "\"challengeId\":\"" + CHALLENGE_ID + "\",\"debugHelp\":\"additive-ok\"}"
        val out = decodeServiceChallenge(json)
        assertEquals(CHALLENGE_ID, out.challengeId)
        assertEquals(millisOf(ACCESS_AT), out.expiresAtMillis)
    }

    @Test
    fun challengeResponseDecode_rejectsRequiredTypeNullEmpty() {
        assertSyncFails { decodeServiceChallenge("{\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}") }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"expiresAt\":\"" + ACCESS_AT + "\"}") }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\"}") }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":null,\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}") }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":null,\"expiresAt\":\"" + ACCESS_AT + "\"}") }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":null}") }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":42,\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}") }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":false,\"expiresAt\":\"" + ACCESS_AT + "\"}") }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":123}") }
        assertSyncFails {
            decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
        }
    }

    @Test
    fun challengeResponseDecode_rejectsUnquotedOpaqueNonce() {
        assertSyncFails(listOf("zzz-nonce-secret")) {
            decodeServiceChallenge(
                "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":zzz-nonce-secret,\"expiresAt\":\"" + ACCESS_AT + "\"}",
            )
        }
        assertSyncFails { decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":NaN,\"expiresAt\":\"" + ACCESS_AT + "\"}") }
    }

    @Test
    fun challengeResponseDecode_rejectsBadUuids() {
        val badUuids = listOf(
            "",
            "not-a-uuid",
            "A1B2C3D4-E5F6-4A7B-8C9D-E0F1A2B3C4D5",
            "a1b2c3d4e5f64a7b8c9de0f1a2b3c4d5",
            "{a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5}",
            "a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d",
            "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
            " a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5",
        )
        var first: String? = null
        for (bad in badUuids) {
            val message = assertSyncFails(listOf(bad.take(8))) {
                decodeServiceChallenge("{\"challengeId\":\"" + bad + "\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
            }.message
            if (first == null) first = message
        }
        val other = assertSyncFails(listOf("zzz-uuid-secret-QQ")) {
            decodeServiceChallenge("{\"challengeId\":\"zzz-uuid-secret-QQ\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
        }.message
        assertEquals("fixed text per UUID category", first, other)
    }

    @Test
    fun challengeResponseDecode_rejectsBadTimestamps() {
        val badTimes = listOf(
            "2026-09-27T12:15:00",
            "2026-09-27T12:15:00+00:00",
            "2026-09-27t12:15:00z",
            "2026-09-27T24:00:00Z",
            "2026-09-27T12:15:60Z",
            "2026-13-01T00:00:00Z",
            "2026-02-30T00:00:00Z",
            "2026-09-27T12:15:00.123456Z",
            "2026-09-27T12:15:00.000000001Z",
            "2026-09-27T12:15:00Z ",
            "2026-09-27T12:15:00ZZ",
            "2026-09-27T12:15:00.Z",
            "2026-09-27T12:15:00.0.000Z",
            "",
        )
        var first: String? = null
        for (bad in badTimes) {
            if (bad.isEmpty()) {
                first = assertSyncFails {
                    decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"\"}")
                }.message
            } else {
                val message = assertSyncFails(listOf(bad.take(10))) {
                    decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"" + bad + "\"}")
                }.message
                if (first == null) first = message
            }
        }
        assertTrue((first ?: "").isNotEmpty())
    }

    @Test
    fun challengeResponseDecode_rejectsDuplicatesAndMalformed() {
        val d1 = assertSyncFails {
            decodeServiceChallenge(
                "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"challengeId\":\"" + OTHER_UUID +
                    "\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}",
            )
        }.message
        val d2 = assertSyncFails {
            decodeServiceChallenge(
                "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"\\u0063hallengeId\":\"" + OTHER_UUID +
                    "\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}",
            )
        }.message
        assertEquals("fixed text per duplicate category", d1, d2)
        assertSyncFails(listOf("zzz-ignored-dup-AAA")) {
            decodeServiceChallenge(
                "{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT +
                    "\",\"zzz-ignored-dup-AAA\":{\"x\":1,\"x\":2}}",
            )
        }
        assertSyncFails { decodeServiceChallenge("") }
        assertSyncFails { decodeServiceChallenge("{") }
        assertSyncFails { decodeServiceChallenge("null") }
        assertSyncFails { decodeServiceChallenge("[]") }
    }

    @Test
    fun strictJson_rejectsLiteralControlsButAcceptsEscapes() {
        for (raw in listOf("\n", "\t", "\u0000", "\u001F")) {
            assertSyncFails {
                decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"a" + raw + "b\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
            }
        }
        val escN = decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"a\\nb\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
        assertEquals("a\nb", escN.nonce)
        val escT = decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"a\\tb\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
        assertEquals("a\tb", escT.nonce)
        val escU = decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"a\\u0000b\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
        assertEquals("a\u0000b", escU.nonce)
        assertSyncFails {
            decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"a\\\\\nb\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
        }
        assertSyncFails {
            decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\",\"zzz-note\":\"a\nb\"}")
        }
        for (raw in listOf("\n", "\u0000")) {
            assertSyncFails { decodeServiceSession(sessionJson(accessToken = "a" + raw + "b")) }
            assertSyncFails { decodeServiceSession(sessionJson(refreshToken = "a" + raw + "b")) }
        }
        assertSyncFails { decodeServiceSession(sessionJson(displayNameJson = "\"a\nb\"")) }
        val sessEsc = decodeServiceSession(sessionJson(accessToken = "a\\nb", refreshToken = "x\\u0000y", displayNameJson = "\"p\\tq\""))
        assertEquals("a\nb", sessEsc.accessToken.value)
        assertEquals("x\u0000y", sessEsc.refreshToken.value)
        assertEquals("p\tq", sessEsc.account.displayName)
    }

    // ---- Session response decode ----

    private fun sessionJson(
        accountId: String = ACCOUNT_ID,
        displayNameJson: String? = "\"Example\"",
        accessToken: String = "opaque-access-001",
        accessAt: String = ACCESS_AT,
        refreshToken: String = "opaque-refresh-001",
        refreshAt: String = REFRESH_AT,
    ): String {
        val account = if (displayNameJson == null) {
            "{\"id\":\"" + accountId + "\"}"
        } else {
            "{\"id\":\"" + accountId + "\",\"displayName\":" + displayNameJson + "}"
        }
        return "{\"account\":" + account + ",\"accessToken\":\"" + accessToken +
            "\",\"accessExpiresAt\":\"" + accessAt + "\",\"refreshToken\":\"" + refreshToken +
            "\",\"refreshExpiresAt\":\"" + refreshAt + "\"}"
    }

    @Test
    fun sessionDecode_validExample() {
        val out = decodeServiceSession(sessionJson())
        assertEquals(ACCOUNT_ID, out.account.accountId)
        assertEquals("Example", out.account.displayName)
        assertEquals("opaque-access-001", out.accessToken.value)
        assertEquals("opaque-refresh-001", out.refreshToken.value)
        assertEquals(millisOf(ACCESS_AT), out.accessExpiresAtMillis)
        assertEquals(millisOf(REFRESH_AT), out.refreshExpiresAtMillis)
    }

    @Test
    fun sessionDecode_displayNameFidelity() {
        assertNull(decodeServiceSession(sessionJson(displayNameJson = "null")).account.displayName)
        assertNull(decodeServiceSession(sessionJson(displayNameJson = null)).account.displayName)
        assertEquals("   ", decodeServiceSession(sessionJson(displayNameJson = "\"   \"")).account.displayName)
        assertEquals("", decodeServiceSession(sessionJson(displayNameJson = "\"\"")).account.displayName)
        assertEquals(
            "José 🎉",
            decodeServiceSession(sessionJson(displayNameJson = "\"José 🎉\"")).account.displayName,
        )
    }

    @Test
    fun sessionDecode_tokenFidelityWhitespaceAndUnicode() {
        val ws = decodeServiceSession(sessionJson(accessToken = "   ", refreshToken = "  "))
        assertEquals("   ", ws.accessToken.value)
        assertEquals("  ", ws.refreshToken.value)
        val uni = decodeServiceSession(sessionJson(accessToken = "tökén-🎉", refreshToken = "rêfrësh-🎉"))
        assertEquals("tökén-🎉", uni.accessToken.value)
        assertEquals("rêfrësh-🎉", uni.refreshToken.value)
    }

    @Test
    fun sessionDecode_fractionalZeroTailLosslessAndFarTailRejected() {
        val zeroTail = decodeServiceSession(sessionJson(accessAt = "2026-09-27T12:15:00.123000000000Z"))
        assertEquals(millisOf("2026-09-27T12:15:00.123Z"), zeroTail.accessExpiresAtMillis)
        assertSyncFails { decodeServiceSession(sessionJson(accessAt = "2026-09-27T12:15:00.123000000001Z")) }
        assertSyncFails { decodeServiceSession(sessionJson(refreshAt = "2026-09-27T12:15:00.123000000001Z")) }
    }

    @Test
    fun sessionDecode_expiredReplayAndUnorderedExpiryAccepted() {
        val replay = decodeServiceSession(
            sessionJson(accessAt = "2020-01-01T00:00:00Z", refreshAt = REFRESH_AT),
        )
        assertEquals(millisOf("2020-01-01T00:00:00Z"), replay.accessExpiresAtMillis)
        assertEquals(millisOf(REFRESH_AT), replay.refreshExpiresAtMillis)
        val unordered = decodeServiceSession(
            sessionJson(accessAt = REFRESH_AT, refreshAt = ACCESS_AT),
        )
        assertEquals(millisOf(REFRESH_AT), unordered.accessExpiresAtMillis)
        assertEquals(millisOf(ACCESS_AT), unordered.refreshExpiresAtMillis)
    }

    @Test
    fun sessionDecode_ignoresAdditiveFields() {
        val base = sessionJson()
        val withRoot = base.dropLast(1) + ",\"serverHelp\":\"additive\"}"
        assertEquals(ACCOUNT_ID, decodeServiceSession(withRoot).account.accountId)
        val withAccount = "{\"account\":{\"id\":\"" + ACCOUNT_ID +
            "\",\"displayName\":\"Example\",\"plan\":\"additive\"}," +
            "\"accessToken\":\"opaque-access-001\",\"accessExpiresAt\":\"" + ACCESS_AT +
            "\",\"refreshToken\":\"opaque-refresh-001\",\"refreshExpiresAt\":\"" + REFRESH_AT + "\"}"
        assertEquals("Example", decodeServiceSession(withAccount).account.displayName)
    }

    private fun fullSessionParts(
        accountJson: String,
        accessTokenJson: String,
        accessAtJson: String,
        refreshTokenJson: String,
        refreshAtJson: String,
    ): String = "{\"account\":" + accountJson + ",\"accessToken\":" + accessTokenJson +
        ",\"accessExpiresAt\":" + accessAtJson + ",\"refreshToken\":" + refreshTokenJson +
        ",\"refreshExpiresAt\":" + refreshAtJson + "}"

    @Test
    fun sessionDecode_rejectsEachRequiredFieldMissingNullWrongType() {
        val goodAccount = "{\"id\":\"" + ACCOUNT_ID + "\",\"displayName\":\"Example\"}"
        val goodAccessToken = "\"a\""
        val goodAccessAt = "\"" + ACCESS_AT + "\""
        val goodRefreshToken = "\"r\""
        val goodRefreshAt = "\"" + REFRESH_AT + "\""
        val scalarWrongs = listOf("null", "42", "true", "[]", "{}")
        // account field: missing once, then one-field-at-a-time wrong fragments.
        assertSyncFails { decodeServiceSession("{\"accessToken\":\"a\",\"accessExpiresAt\":\"" + ACCESS_AT + "\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"" + REFRESH_AT + "\"}") }
        for (frag in listOf("null", "\"str\"", "42", "[]")) {
            assertSyncFails { decodeServiceSession(fullSessionParts(frag, goodAccessToken, goodAccessAt, goodRefreshToken, goodRefreshAt)) }
        }
        // account.id: missing once, then one-field-at-a-time wrong fragments.
        assertSyncFails { decodeServiceSession(fullSessionParts("{\"displayName\":\"x\"}", goodAccessToken, goodAccessAt, goodRefreshToken, goodRefreshAt)) }
        for (frag in scalarWrongs) {
            assertSyncFails { decodeServiceSession(fullSessionParts("{\"id\":" + frag + "}", goodAccessToken, goodAccessAt, goodRefreshToken, goodRefreshAt)) }
        }
        assertSyncFails(listOf("zzz-account-secret")) {
            decodeServiceSession(fullSessionParts("{\"id\":\"zzz-account-secret\"}", goodAccessToken, goodAccessAt, goodRefreshToken, goodRefreshAt))
        }
        // optional displayName wrong types (null/absent valid elsewhere).
        for (frag in listOf("42", "true", "[]", "{}")) {
            assertSyncFails { decodeServiceSession(fullSessionParts("{\"id\":\"" + ACCOUNT_ID + "\",\"displayName\":" + frag + "}", goodAccessToken, goodAccessAt, goodRefreshToken, goodRefreshAt)) }
        }
        // accessToken: missing once, then wrong fragments, then empty raw string.
        assertSyncFails { decodeServiceSession("{\"account\":" + goodAccount + ",\"accessExpiresAt\":\"" + ACCESS_AT + "\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"" + REFRESH_AT + "\"}") }
        for (frag in scalarWrongs) {
            assertSyncFails { decodeServiceSession(fullSessionParts(goodAccount, frag, goodAccessAt, goodRefreshToken, goodRefreshAt)) }
        }
        assertSyncFails { decodeServiceSession(fullSessionParts(goodAccount, "\"\"", goodAccessAt, goodRefreshToken, goodRefreshAt)) }
        // accessExpiresAt: missing once, then wrong fragments.
        assertSyncFails { decodeServiceSession("{\"account\":" + goodAccount + ",\"accessToken\":\"a\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"" + REFRESH_AT + "\"}") }
        for (frag in scalarWrongs) {
            assertSyncFails { decodeServiceSession(fullSessionParts(goodAccount, goodAccessToken, frag, goodRefreshToken, goodRefreshAt)) }
        }
        // refreshToken: missing once, then wrong fragments, then empty raw string.
        assertSyncFails { decodeServiceSession("{\"account\":" + goodAccount + ",\"accessToken\":\"a\",\"accessExpiresAt\":\"" + ACCESS_AT + "\",\"refreshExpiresAt\":\"" + REFRESH_AT + "\"}") }
        for (frag in scalarWrongs) {
            assertSyncFails { decodeServiceSession(fullSessionParts(goodAccount, goodAccessToken, goodAccessAt, frag, goodRefreshAt)) }
        }
        assertSyncFails { decodeServiceSession(fullSessionParts(goodAccount, goodAccessToken, goodAccessAt, "\"\"", goodRefreshAt)) }
        // refreshExpiresAt: missing once, then wrong fragments.
        assertSyncFails { decodeServiceSession("{\"account\":" + goodAccount + ",\"accessToken\":\"a\",\"accessExpiresAt\":\"" + ACCESS_AT + "\",\"refreshToken\":\"r\"}") }
        for (frag in scalarWrongs) {
            assertSyncFails { decodeServiceSession(fullSessionParts(goodAccount, goodAccessToken, goodAccessAt, goodRefreshToken, frag)) }
        }
    }

    @Test
    fun sessionDecode_rejectsUnquotedOpaqueValues() {
        val goodAccount = "{\"id\":\"" + ACCOUNT_ID + "\"}"
        assertSyncFails(listOf("zzz-token-secret")) {
            decodeServiceSession(fullSessionParts(goodAccount, "zzz-token-secret", "\"" + ACCESS_AT + "\"", "\"r\"", "\"" + REFRESH_AT + "\""))
        }
        assertSyncFails {
            decodeServiceSession(fullSessionParts(goodAccount, "NaN", "\"" + ACCESS_AT + "\"", "\"r\"", "\"" + REFRESH_AT + "\""))
        }
        assertSyncFails {
            decodeServiceSession(fullSessionParts(goodAccount, "\"a\"", "\"" + ACCESS_AT + "\"", "foobar", "\"" + REFRESH_AT + "\""))
        }
        assertSyncFails {
            decodeServiceSession("{\"account\":{\"id\":\"" + ACCOUNT_ID + "\",\"displayName\":NaN},\"accessToken\":\"a\",\"accessExpiresAt\":\"" + ACCESS_AT + "\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"" + REFRESH_AT + "\"}")
        }
    }

    @Test
    fun sessionDecode_rejectsBadUuidAndTimestamps() {
        val m1 = assertSyncFails(listOf("zzz-uuid-secret-AAA")) {
            decodeServiceSession(sessionJson(accountId = "zzz-uuid-secret-AAA"))
        }.message
        val m2 = assertSyncFails(listOf("zzz-uuid-secret-BBB")) {
            decodeServiceSession(sessionJson(accountId = "zzz-uuid-secret-BBB"))
        }.message
        assertEquals("fixed text per UUID category", m1, m2)
        assertSyncFails { decodeServiceSession(sessionJson(accessAt = "2026-09-27T12:15:00+00:00")) }
        assertSyncFails { decodeServiceSession(sessionJson(refreshAt = "not-a-time")) }
        assertSyncFails { decodeServiceSession(sessionJson(accessAt = "2026-09-27T12:15:00.123456Z")) }
        assertSyncFails { decodeServiceSession(sessionJson(accessAt = "2026-09-27T12:15:00.Z")) }
        assertSyncFails { decodeServiceSession(sessionJson(refreshAt = "2026-09-27T12:15:00.Z")) }
        assertSyncFails { decodeServiceSession(sessionJson(accessAt = "2026-09-27T12:15:00.0.000Z")) }
        assertSyncFails { decodeServiceSession(sessionJson(refreshAt = "2026-09-27T12:15:00.0.000Z")) }
    }

    @Test
    fun sessionDecode_rejectsDuplicatesIncludingAccountAndIgnored() {
        val d1 = assertSyncFails {
            decodeServiceSession(sessionJson().dropLast(1) + ",\"accessToken\":\"dup\"}")
        }.message
        val d2 = assertSyncFails {
            decodeServiceSession(
                "{\"account\":{\"id\":\"" + ACCOUNT_ID + "\",\"\\u0069d\":\"" + OTHER_UUID +
                    "\"},\"accessToken\":\"a\",\"accessExpiresAt\":\"" + ACCESS_AT +
                    "\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"" + REFRESH_AT + "\"}",
            )
        }.message
        assertEquals("fixed text per duplicate category", d1, d2)
        assertSyncFails(listOf("zzz-ignored-dup-AAA")) {
            decodeServiceSession(sessionJson().dropLast(1) + ",\"zzz-ignored-dup-AAA\":{\"x\":1,\"x\":2}}")
        }
        assertSyncFails { decodeServiceSession("") }
        assertSyncFails { decodeServiceSession("null") }
        assertSyncFails { decodeServiceSession("[1]") }
    }

    // ---- Logout encode ----

    @Test
    fun logoutEncode_exactSingleField() {
        val body = encodeLogoutRequest(OpaqueToken("opaque-refresh-001"))
        val parsed = JSONObject(body)
        assertEquals(1, parsed.length())
        assertEquals("opaque-refresh-001", parsed.getString("refreshToken"))
    }

    @Test
    fun logoutEncode_preservesOpaqueTokenBytes() {
        val ws = encodeLogoutRequest(OpaqueToken("   "))
        assertEquals("   ", JSONObject(ws).getString("refreshToken"))
        val uni = encodeLogoutRequest(OpaqueToken("tökén-🎉 "))
        assertEquals("tökén-🎉 ", JSONObject(uni).getString("refreshToken"))
        val jsonLooking = encodeLogoutRequest(OpaqueToken("{\"refreshToken\":\"x\"}"))
        assertEquals("{\"refreshToken\":\"x\"}", JSONObject(jsonLooking).getString("refreshToken"))
    }

    @Test
    fun opaqueToken_emptyRemainsIllegalArgument() {
        try {
            OpaqueToken("")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // compatible legacy behavior
        }
    }

    // ---- Direct model validation: UUID + expiry boundaries ----

    @Test
    fun accountRef_validatesUuidOnConstructAndCopy() {
        val ok = ServiceAccountRef(ACCOUNT_ID, "Example")
        assertEquals(ACCOUNT_ID, ok.accountId)
        val m1 = assertSyncFails(listOf("zzz-uuid-secret-AAA")) {
            ServiceAccountRef("zzz-uuid-secret-AAA", null)
        }.message
        val m2 = assertSyncFails(listOf("zzz-uuid-secret-BBB")) {
            ServiceAccountRef("zzz-uuid-secret-BBB", null)
        }.message
        assertEquals("fixed text per UUID category", m1, m2)
        assertSyncFails { ok.copy(accountId = "B5827485-C9D6-49AA-9578-7A2F637CB8BC") }
    }

    @Test
    fun challengeModel_validatesUuidAndExpiryBounds() {
        val min = ServiceChallenge(CHALLENGE_ID, "n", MIN_MILLIS)
        assertEquals(MIN_MILLIS, min.expiresAtMillis)
        val max = ServiceChallenge(CHALLENGE_ID, "n", MAX_MILLIS)
        assertEquals(MAX_MILLIS, max.expiresAtMillis)
        assertSyncFails { ServiceChallenge(CHALLENGE_ID, "n", MIN_MILLIS - 1) }
        assertSyncFails { ServiceChallenge(CHALLENGE_ID, "n", MAX_MILLIS + 1) }
        assertSyncFails { ServiceChallenge(CHALLENGE_ID, "n", Long.MIN_VALUE) }
        assertSyncFails { ServiceChallenge(CHALLENGE_ID, "n", Long.MAX_VALUE) }
        assertSyncFails { ServiceChallenge("NOT-A-UUID", "n", 0L) }
        assertSyncFails { min.copy(expiresAtMillis = MAX_MILLIS + 1) }
        assertSyncFails { min.copy(expiresAtMillis = MIN_MILLIS - 1) }
        assertSyncFails { min.copy(challengeId = "A1B2C3D4-E5F6-4A7B-8C9D-E0F1A2B3C4D5") }
        assertSyncFails { ServiceChallenge(CHALLENGE_ID, "", 0L) }
        assertSyncFails { min.copy(nonce = "") }
        assertEquals("   ", ServiceChallenge(CHALLENGE_ID, "   ", 0L).nonce)
        assertEquals("   ", min.copy(nonce = "   ").nonce)
    }

    @Test
    fun sessionModel_validatesAllExpiryBoundsOnConstructAndCopy() {
        fun build(access: Long, refresh: Long) = ServiceSession(
            account = ServiceAccountRef(ACCOUNT_ID, null),
            accessToken = OpaqueToken("a"),
            accessExpiresAtMillis = access,
            refreshToken = OpaqueToken("r"),
            refreshExpiresAtMillis = refresh,
        )
        build(MIN_MILLIS, MIN_MILLIS)
        build(MAX_MILLIS, MAX_MILLIS)
        build(0L, 0L)
        assertSyncFails { build(MIN_MILLIS - 1, 0L) }
        assertSyncFails { build(MAX_MILLIS + 1, 0L) }
        assertSyncFails { build(Long.MIN_VALUE, 0L) }
        assertSyncFails { build(Long.MAX_VALUE, 0L) }
        assertSyncFails { build(0L, MIN_MILLIS - 1) }
        assertSyncFails { build(0L, MAX_MILLIS + 1) }
        assertSyncFails { build(0L, Long.MIN_VALUE) }
        assertSyncFails { build(0L, Long.MAX_VALUE) }
        val ok = build(0L, 0L)
        assertSyncFails { ok.copy(accessExpiresAtMillis = MIN_MILLIS - 1) }
        assertSyncFails { ok.copy(accessExpiresAtMillis = MAX_MILLIS + 1) }
        assertSyncFails { ok.copy(accessExpiresAtMillis = Long.MIN_VALUE) }
        assertSyncFails { ok.copy(accessExpiresAtMillis = Long.MAX_VALUE) }
        assertSyncFails { ok.copy(refreshExpiresAtMillis = MIN_MILLIS - 1) }
        assertSyncFails { ok.copy(refreshExpiresAtMillis = MAX_MILLIS + 1) }
        assertSyncFails { ok.copy(refreshExpiresAtMillis = Long.MIN_VALUE) }
        assertSyncFails { ok.copy(refreshExpiresAtMillis = Long.MAX_VALUE) }
    }

    // ---- Redaction ----

    @Test
    fun redaction_tokenChallengeSession() {
        val secretToken = "secret-token-xyz-001"
        val tokenText = OpaqueToken(secretToken).toString()
        assertFalse(tokenText.contains(secretToken))
        val nonce = "nonce-secret-abc"
        val challenge = ServiceChallenge(CHALLENGE_ID, nonce, 0L)
        val challengeText = challenge.toString()
        assertFalse("nonce must not print", challengeText.contains(nonce))
        val session = ServiceSession(
            ServiceAccountRef(ACCOUNT_ID, "Private Name"),
            OpaqueToken("access-secret-1"),
            0L,
            OpaqueToken("refresh-secret-1"),
            0L,
        )
        val sessionText = session.toString()
        assertFalse(sessionText.contains("access-secret-1"))
        assertFalse(sessionText.contains("refresh-secret-1"))
        assertFalse(sessionText.contains("Private Name"))
    }

    // ---- Safe failure sentinels ----

    @Test
    fun failures_neverEchoMaliciousInputs() {
        val evilTimeA = "2026-13-99T99:99:99.666666666+00:00<script>zzz-evil-time-AAA</script>"
        val evilTimeB = "2026-13-99T99:99:99.666666666+00:00<script>zzz-evil-time-BBB</script>"
        val m1 = assertSyncFails(listOf("zzz-evil-time-AAA")) {
            decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"" + evilTimeA + "\"}")
        }.message
        val m2 = assertSyncFails(listOf("zzz-evil-time-BBB")) {
            decodeServiceChallenge("{\"challengeId\":\"" + CHALLENGE_ID + "\",\"nonce\":\"n\",\"expiresAt\":\"" + evilTimeB + "\"}")
        }.message
        assertEquals("fixed text per timestamp category", m1, m2)
        val u1 = assertSyncFails(listOf("zzz-evil-uuid-AAA")) {
            decodeServiceChallenge("{\"challengeId\":\"zzz-evil-uuid-AAA\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
        }.message
        val u2 = assertSyncFails(listOf("zzz-evil-uuid-BBB")) {
            decodeServiceChallenge("{\"challengeId\":\"zzz-evil-uuid-BBB\",\"nonce\":\"n\",\"expiresAt\":\"" + ACCESS_AT + "\"}")
        }.message
        assertEquals("fixed text per UUID category", u1, u2)
        assertSyncFails(listOf("zzz-unknown-field-secret-123")) {
            decodeGoogleChallengeRequest("{\"zzz-unknown-field-secret-123\":\"signIn\"}")
        }
        val e1 = assertSyncFails(listOf("zzz-evil-enum-AAA")) {
            decodeGoogleChallengeRequest("{\"purpose\":\"zzz-evil-enum-AAA\"}")
        }.message
        val e2 = assertSyncFails(listOf("zzz-evil-enum-BBB")) {
            decodeGoogleChallengeRequest("{\"purpose\":\"zzz-evil-enum-BBB\"}")
        }.message
        assertEquals("fixed text per enum category", e1, e2)
        assertSyncFails(listOf("zzz-looking-token-999")) {
            decodeServiceSession(sessionJson(accessToken = "zzz-looking-token-999", accessAt = "bogus-time"))
        }
    }
}
