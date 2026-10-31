package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// exact-body service-auth adapter over accepted HTTP transport I/O and sync HTTP parsing.
// Expected values are independent literal JSON strings.

private const val CHALLENGE_ID = "11111111-1111-1111-1111-111111111111"
private const val ACCOUNT_ID = "22222222-2222-2222-2222-222222222222"
private const val EXCHANGE_KEY = "33333333-3333-3333-3333-333333333333"
private const val REFRESH_KEY = "44444444-4444-4444-4444-444444444444"
private const val OTHER_KEY = "55555555-5555-5555-5555-555555555555"

private const val VALID_CHALLENGE_JSON =
    "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":\"opaque nonce\",\"expiresAt\":\"2026-10-03T12:05:00Z\"}"
private const val VALID_GRANT_JSON =
    "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null}," +
        "\"accessToken\":\"opaque access\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\"," +
        "\"refreshToken\":\"opaque refresh\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}"

private fun testOrigin(): ServiceOrigin = ServiceOrigin.parse("https://example.com")

private fun jsonMedia(): okhttp3.MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

private class CloseTrackingBody(
    private val bytes: ByteString,
    private val mediaType: okhttp3.MediaType?,
    private val contentLengthOverride: Long? = null,
    private val readFailure: IOException? = null,
    val onClose: () -> Unit = {},
) : ResponseBody() {
    private val backing = Buffer().write(bytes)
    private var closed = false
    private val tracked: BufferedSource = object : ForwardingSource(backing) {
        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
            super.close()
        }
    }.buffer()
    override fun contentType(): okhttp3.MediaType? = mediaType
    override fun contentLength(): Long = contentLengthOverride ?: bytes.size.toLong()
    override fun source(): BufferedSource {
        if (readFailure != null) {
            throw readFailure
        }
        return tracked
    }
}

private class ReadFailingBody(
    private val mediaType: okhttp3.MediaType?,
    val onClose: () -> Unit = {},
) : ResponseBody() {
    private var closed = false
    private val failing: BufferedSource = object : ForwardingSource(Buffer()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            throw IOException("mid-body-read")
        }
        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
            super.close()
        }
    }.buffer()
    override fun contentType(): okhttp3.MediaType? = mediaType
    override fun contentLength(): Long = -1L
    override fun source(): BufferedSource = failing
}

private fun readFailingResponse(request: Request, status: Int, onClose: () -> Unit = {}): Response {
    val media = "application/json; charset=utf-8".toMediaTypeOrNull()
    return Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("stub")
        .headers(Headers.Builder().add("Content-Type", "application/json; charset=utf-8").build())
        .body(ReadFailingBody(media, onClose))
        .build()
}

private class FakeDirectTransport(
    private val failure: Throwable,
) : ServiceHttpTransport {
    override suspend fun <T> withResponse(
        request: ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T {
        throw failure
    }
}

private class GatedLateFailureTransport(
    private val entered: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
    private val failure: Throwable,
) : ServiceHttpTransport {
    override suspend fun <T> withResponse(
        request: ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T {
        if (!entered.isCompleted) {
            entered.complete(Unit)
        }
        withContext(NonCancellable) { release.await() }
        throw failure
    }
}

// Independent arbitrary raw request bodies (never produced via production encoders).
// Exchange shape per OpenAPI: {challengeId, idToken} closed; order/whitespace/escapes preserved.
private const val ARBITRARY_EXCHANGE_RAW =
    "  {\n  \"idToken\" : \"a\\u00e9b\\nline\" ,\n  \"challengeId\" : \"11111111-1111-1111-1111-111111111111\"\n}\n"
private const val ARBITRARY_REFRESH_RAW =
    " { \"refreshToken\" : \"r\\u00e9f-\uD83C\uDF89\" } "

private class FakeAuthCall(
    private val req: Request,
    private val respond: (Request) -> Response,
    private val failWith: IOException?,
    private val hang: Boolean,
    val onCancel: () -> Unit = {},
) : Call {
    @Volatile var executed = false
    @Volatile var canceled = false
    @Volatile var cancelCalls = 0
    override fun request(): Request = req
    override fun execute(): Response = throw UnsupportedOperationException("scripted async only")
    override fun enqueue(responseCallback: Callback) {
        executed = true
        if (hang) {
            return
        }
        if (failWith != null) {
            responseCallback.onFailure(this, failWith)
            return
        }
        try {
            val resp = respond(req)
            if (canceled) {
                resp.close()
                responseCallback.onFailure(this, IOException("Canceled"))
            } else {
                responseCallback.onResponse(this, resp)
            }
        } catch (e: IOException) {
            responseCallback.onFailure(this, e)
        }
    }
    override fun cancel() {
        canceled = true
        cancelCalls++
        onCancel()
    }
    override fun isExecuted(): Boolean = executed
    override fun isCanceled(): Boolean = canceled
    override fun timeout(): Timeout = Timeout()
    override fun clone(): Call = FakeAuthCall(req, respond, failWith, hang, onCancel)
    override fun addEventListener(eventListener: EventListener) = Unit
    override fun <T> tag(type: Class<out T>): T? = null
    override fun <T : Any> tag(type: KClass<T>): T? = null
    override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
}

private class ScriptedAuthFactory(
    var handler: (Request) -> Response = { throw IOException("no stub") },
    var failure: IOException? = null,
    var hang: Boolean = false,
) : Call.Factory {
    val captured = mutableListOf<Request>()
    val calls = mutableListOf<FakeAuthCall>()
    val count = AtomicInteger(0)
    override fun newCall(request: Request): Call {
        captured.add(request)
        count.incrementAndGet()
        val call = FakeAuthCall(request, handler, failure, hang)
        calls.add(call)
        return call
    }
}

private fun scriptedApi(
    factory: ScriptedAuthFactory,
    origin: ServiceOrigin = testOrigin(),
): HttpServiceAuthApi {
    val transport = OkHttpServiceTransport(origin, factory)
    return HttpServiceAuthApi(origin, transport)
}

private fun jsonResponse(
    request: Request,
    status: Int,
    bodyText: String,
    contentType: String? = "application/json; charset=utf-8",
    contentLengthHeader: String? = null,
    onClose: () -> Unit = {},
): Response {
    val media = contentType?.toMediaTypeOrNull()
    val bytes = bodyText.encodeUtf8()
    val body = CloseTrackingBody(bytes, media, onClose = onClose)
    val hb = Headers.Builder()
    if (contentType != null) {
        hb.add("Content-Type", contentType)
    }
    if (contentLengthHeader != null) {
        hb.add("Content-Length", contentLengthHeader)
    }
    return Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("stub")
        .headers(hb.build())
        .body(body)
        .build()
}

private fun rawBytesResponse(
    request: Request,
    status: Int,
    raw: ByteString,
    contentType: String? = "application/json; charset=utf-8",
    contentLengthHeader: String? = null,
    onClose: () -> Unit = {},
): Response {
    val media = contentType?.toMediaTypeOrNull()
    val body = CloseTrackingBody(raw, media, onClose = onClose)
    val hb = Headers.Builder()
    if (contentType != null) {
        hb.add("Content-Type", contentType)
    }
    if (contentLengthHeader != null) {
        hb.add("Content-Length", contentLengthHeader)
    }
    return Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("stub")
        .headers(hb.build())
        .body(body)
        .build()
}

private fun empty204(request: Request, withContentType: Boolean = false, onClose: () -> Unit = {}): Response {
    val hb = Headers.Builder()
    if (withContentType) {
        hb.add("Content-Type", "application/json; charset=utf-8")
    }
    val body = CloseTrackingBody(ByteString.EMPTY, null, onClose = onClose)
    return Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(204)
        .message("No Content")
        .headers(hb.build())
        .body(body)
        .build()
}

private fun singleByte204(request: Request, byte: String = "x", onClose: () -> Unit = {}): Response {
    val body = CloseTrackingBody(byte.encodeUtf8(), null, onClose = onClose)
    return Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(204)
        .message("No Content")
        .headers(Headers.Builder().build())
        .body(body)
        .build()
}

private fun errorJson(status: Int, code: String): String =
    "{\"errorCode\":" + org.json.JSONObject.quote(code) + ",\"message\":\"safe presentation\"}"

private fun requestBodyUtf8(request: Request): String {
    val buf = Buffer()
    val body = request.body ?: return ""
    body.writeTo(buf)
    return buf.readUtf8()
}

private fun assertRedacted(display: String, vararg sensitive: String) {
    sensitive.forEach { s ->
        if (s.isNotEmpty()) {
            assertFalse("leaked sensitive value in display", display.contains(s))
        }
    }
}

class ServiceAuthHttpApiTest {

    // ---- pure classifier: authoritative statuses ----

    @Test fun classify413AlwaysTooLarge() {
        for (op in AuthHttpOperation.values()) {
            assertTrue(classifyAuthHttpFailure(op, 413, null) is SyncApiException.RequestTooLarge)
            assertTrue(classifyAuthHttpFailure(op, 413, "") is SyncApiException.RequestTooLarge)
            assertTrue(classifyAuthHttpFailure(op, 413, "anything") is SyncApiException.RequestTooLarge)
            assertTrue(
                classifyAuthHttpFailure(op, 413, "reauthentication_required") is SyncApiException.RequestTooLarge,
            )
        }
    }

    @Test fun classifyRateAndListed5xxAreTransientWithCappedHints() {
        val ops = AuthHttpOperation.values()
        val statuses = listOf(429, 500, 502, 503, 504)
        for (op in ops) {
            for (status in statuses) {
                val t = classifyAuthHttpFailure(op, status, null) as SyncApiException.Transient
                assertNull(t.retryAfterMillis)
                val t1 = classifyAuthHttpFailure(op, status, null, mapOf("Retry-After" to listOf("1")))
                assertEquals(1000L, (t1 as SyncApiException.Transient).retryAfterMillis)
                val t299 = classifyAuthHttpFailure(op, status, null, mapOf("retry-after" to listOf("299")))
                assertEquals(299000L, (t299 as SyncApiException.Transient).retryAfterMillis)
                val t300 = classifyAuthHttpFailure(op, status, null, mapOf("Retry-After" to listOf("300")))
                assertEquals(300000L, (t300 as SyncApiException.Transient).retryAfterMillis)
                val tBig = classifyAuthHttpFailure(op, status, null, mapOf("Retry-After" to listOf("10000000000000000000000")))
                assertEquals(300000L, (tBig as SyncApiException.Transient).retryAfterMillis)
            }
        }
    }

    @Test fun classifyOuterWhitespaceHintsAreValid() {
        // strips outer SP/HTAB: padded hints remain valid bounded delays.
        for (v in listOf(" 1", "1 ", "\t1\t", "  299  ")) {
            val t = classifyAuthHttpFailure(AuthHttpOperation.CHALLENGE, 429, null, mapOf("Retry-After" to listOf(v)))
            val expected = if (v.trim().toLong() >= 300L) 300000L else v.trim().toLong() * 1000L
            assertEquals(expected, (t as SyncApiException.Transient).retryAfterMillis)
        }
    }

    @Test fun classifyMalformedHintsYieldNullBackoff() {
        val bad = listOf("", "0", "00", "-1", "+1", "1.0", "1e2", "Wed, 21 Oct 2015 07:28:00 GMT", "١٢٣")
        for (v in bad) {
            val t = classifyAuthHttpFailure(AuthHttpOperation.CHALLENGE, 429, null, mapOf("Retry-After" to listOf(v)))
            assertNull((t as SyncApiException.Transient).retryAfterMillis)
        }
        val multi = classifyAuthHttpFailure(
            AuthHttpOperation.EXCHANGE, 503, null,
            mapOf("Retry-After" to listOf("1", "2")),
        )
        assertNull((multi as SyncApiException.Transient).retryAfterMillis)
        val splitKeys = classifyAuthHttpFailure(
            AuthHttpOperation.REFRESH, 503, null,
            mapOf("Retry-After" to listOf("5"), "retry-after" to listOf("6")),
        )
        assertNull((splitKeys as SyncApiException.Transient).retryAfterMillis)
    }

    @Test fun classifyRefresh401AlwaysReauth() {
        for (code in listOf(null, "", "anything", "reauthentication_required", "invalid_google_credential", "  spaced  ")) {
            val e = classifyAuthHttpFailure(AuthHttpOperation.REFRESH, 401, code)
            assertTrue("REFRESH 401 $code", e is SyncApiException.ReauthenticationRequired)
        }
    }

    @Test fun classifyExchange401ExactReauthOnly() {
        assertTrue(
            classifyAuthHttpFailure(AuthHttpOperation.EXCHANGE, 401, "reauthentication_required")
                is SyncApiException.ReauthenticationRequired,
        )
        val rejected = classifyAuthHttpFailure(AuthHttpOperation.EXCHANGE, 401, "invalid_google_credential")
        assertTrue(rejected is SyncApiException.RequestRejected)
        assertEquals("invalid_google_credential", (rejected as SyncApiException.RequestRejected).errorCode)
        assertTrue(classifyAuthHttpFailure(AuthHttpOperation.EXCHANGE, 401, null) is SyncApiException.MalformedResponse)
        assertTrue(classifyAuthHttpFailure(AuthHttpOperation.EXCHANGE, 401, "") is SyncApiException.MalformedResponse)
        // Exactness: whitespace/case do not promote.
        val spaced = classifyAuthHttpFailure(AuthHttpOperation.EXCHANGE, 401, " reauthentication_required")
        assertTrue(spaced is SyncApiException.RequestRejected)
        val cased = classifyAuthHttpFailure(AuthHttpOperation.EXCHANGE, 401, "Reauthentication_Required")
        assertTrue(cased is SyncApiException.RequestRejected)
    }

    @Test fun classifyChallengeLogout401NotPromoted() {
        for (op in listOf(AuthHttpOperation.CHALLENGE, AuthHttpOperation.LOGOUT)) {
            val e = classifyAuthHttpFailure(op, 401, "reauthentication_required")
            assertTrue("$op 401 reauth stays rejected", e is SyncApiException.RequestRejected)
            assertEquals("reauthentication_required", (e as SyncApiException.RequestRejected).errorCode)
        }
    }

    @Test fun classifyWrongStatusReauthStaysRejected() {
        for (op in AuthHttpOperation.values()) {
            val e = classifyAuthHttpFailure(op, 422, "reauthentication_required")
            assertTrue("$op 422 reauth stays rejected", e is SyncApiException.RequestRejected)
        }
        val e400 = classifyAuthHttpFailure(AuthHttpOperation.REFRESH, 400, "reauthentication_required")
        assertTrue(e400 is SyncApiException.RequestRejected)
    }

    @Test fun classifyOrdinary4xxRetentionAndMalformed() {
        val ops = AuthHttpOperation.values()
        val statuses = listOf(400, 401, 403, 404, 409, 422)
        for (op in ops) {
            for (status in statuses) {
                if (op == AuthHttpOperation.REFRESH && status == 401) continue
                if (op == AuthHttpOperation.EXCHANGE && status == 401) continue
                if ((op == AuthHttpOperation.CHALLENGE || op == AuthHttpOperation.LOGOUT) && status == 401) {
                    // Still ordinary: nonempty -> rejected, empty -> malformed (tested above for reauth).
                    continue
                }
                val kept = classifyAuthHttpFailure(op, status, "future_code")
                assertTrue("$op $status", kept is SyncApiException.RequestRejected)
                assertTrue(classifyAuthHttpFailure(op, status, null) is SyncApiException.MalformedResponse)
                assertTrue(classifyAuthHttpFailure(op, status, "") is SyncApiException.MalformedResponse)
            }
        }
        // Unknown/long/Unicode/whitespace codes retained exactly.
        val unicode = "tökén-🎉  spaced\t"
        val kept = classifyAuthHttpFailure(AuthHttpOperation.CHALLENGE, 400, unicode)
        assertEquals(unicode, (kept as SyncApiException.RequestRejected).errorCode)
        val longCode = "c".repeat(600)
        assertEquals(longCode, (classifyAuthHttpFailure(AuthHttpOperation.LOGOUT, 404, longCode) as SyncApiException.RequestRejected).errorCode)
        val conflict = classifyAuthHttpFailure(AuthHttpOperation.EXCHANGE, 409, "idempotency_key_reused")
        assertEquals("idempotency_key_reused", (conflict as SyncApiException.RequestRejected).errorCode)
    }

    @Test fun classifyEverythingElseMalformed() {
        val others = listOf(100, 101, 200, 201, 204, 202, 301, 302, 501, 505, 599, 600, 0, -1)
        for (op in AuthHttpOperation.values()) {
            for (status in others) {
                assertTrue(
                    "$op $status",
                    classifyAuthHttpFailure(op, status, "reauthentication_required") is SyncApiException.MalformedResponse,
                )
                assertTrue("$op $status null", classifyAuthHttpFailure(op, status, null) is SyncApiException.MalformedResponse)
            }
        }
        // Success status passed to failure-only classifier is malformed even for refresh/exchange.
        assertTrue(
            classifyAuthHttpFailure(AuthHttpOperation.REFRESH, 200, null) is SyncApiException.MalformedResponse,
        )
        assertTrue(
            classifyAuthHttpFailure(AuthHttpOperation.EXCHANGE, 200, "reauthentication_required")
                is SyncApiException.MalformedResponse,
        )
    }

    @Test fun classifierNeverReturnsAccessRecovery() {
        for (op in AuthHttpOperation.values()) {
            for (status in listOf(400, 401, 403, 404, 409, 413, 422, 429, 500, 503)) {
                val e = classifyAuthHttpFailure(op, status, "reauthentication_required")
                assertFalse("$op $status must never be AuthenticationRequired", e is SyncApiException.AuthenticationRequired)
            }
        }
    }

    @Test fun classifierFailuresHaveClosedCauses() {
        val e = classifyAuthHttpFailure(AuthHttpOperation.CHALLENGE, 400, "future_code")
        assertNull(e.cause)
        val t = classifyAuthHttpFailure(AuthHttpOperation.CHALLENGE, 503, null, mapOf("Retry-After" to listOf("2")))
        assertNull(t.cause)
        assertEquals(2000L, (t as SyncApiException.Transient).retryAfterMillis)
    }

    // ---- challenge requests ----

    @Test fun challengeFourPurposesSendExplicitBodies() = runTest {
        val expected = mapOf(
            GoogleChallengePurpose.SIGN_IN to "signIn",
            GoogleChallengePurpose.EXPORT to "export",
            GoogleChallengePurpose.DELETE_ACCOUNT to "deleteAccount",
            GoogleChallengePurpose.ACCOUNT_STATUS to "accountStatus",
        )
        for ((purpose, wire) in expected) {
            val factory = ScriptedAuthFactory()
            factory.handler = { req -> jsonResponse(req, 200, VALID_CHALLENGE_JSON) }
            val api = scriptedApi(factory)
            api.challenge(purpose)
            assertEquals(1, factory.count.get())
            val req = factory.captured.single()
            assertEquals("POST", req.method)
            assertEquals("/v1/auth/google/challenge", req.url.encodedPath)
            assertTrue(req.url.encodedQuery.isNullOrEmpty())
            assertNull(req.header("Authorization"))
            assertNull(req.header("Idempotency-Key"))
            val body = requestBodyUtf8(req)
            assertTrue("purpose $purpose must send wire $wire, got: $body", body.contains("\"" + wire + "\""))
            assertTrue(body.contains("purpose"))
        }
    }

    @Test fun challengeDefaultEqualsSignIn() = runTest {
        val f1 = ScriptedAuthFactory()
        f1.handler = { req -> jsonResponse(req, 200, VALID_CHALLENGE_JSON) }
        val api1 = scriptedApi(f1)
        api1.challenge()
        val f2 = ScriptedAuthFactory()
        f2.handler = { req -> jsonResponse(req, 200, VALID_CHALLENGE_JSON) }
        val api2 = scriptedApi(f2)
        api2.challenge(GoogleChallengePurpose.SIGN_IN)
        assertEquals(requestBodyUtf8(f1.captured.single()), requestBodyUtf8(f2.captured.single()))
    }

    @Test fun challengeSuccessDecodesFields() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, VALID_CHALLENGE_JSON) }
        val api = scriptedApi(factory)
        val c = api.challenge(GoogleChallengePurpose.SIGN_IN)
        assertEquals(CHALLENGE_ID, c.challengeId)
        assertEquals("opaque nonce", c.nonce)
        val expectedMillis = java.time.Instant.parse("2026-10-03T12:05:00Z").toEpochMilli()
        assertEquals(expectedMillis, c.expiresAtMillis)
    }

    @Test fun challengeToleratesAdditiveFields() = runTest {
        val withExtra = "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":\"opaque nonce\",\"expiresAt\":\"2026-10-03T12:05:00Z\",\"extra\":1,\"account\":{\"id\":\"x\"}}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, withExtra) }
        val api = scriptedApi(factory)
        val c = api.challenge()
        assertEquals(CHALLENGE_ID, c.challengeId)
    }

    @Test fun challengeDuplicateKeysAreMalformed() = runTest {
        val dup = "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":\"a\",\"nonce\":\"b\",\"expiresAt\":\"2026-10-03T12:05:00Z\"}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, dup) }
        val api = scriptedApi(factory)
        try {
            api.challenge()
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun challengeScalarOverflowIsMalformed() = runTest {
        // Numeric overflow must not satisfy a string field via JSONObject fallback.
        val overflow = "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":1e400,\"expiresAt\":\"2026-10-03T12:05:00Z\"}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, overflow) }
        val api = scriptedApi(factory)
        try {
            api.challenge()
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun challengeWrongTypesAreMalformed() = runTest {
        val badList = listOf(
            "{\"challengeId\":123,\"nonce\":\"a\",\"expiresAt\":\"2026-10-03T12:05:00Z\"}",
            "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":true,\"expiresAt\":\"2026-10-03T12:05:00Z\"}",
            "{\"challengeId\":\"not-a-uuid\",\"nonce\":\"a\",\"expiresAt\":\"2026-10-03T12:05:00Z\"}",
            "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":\"a\",\"expiresAt\":\"not-time\"}",
            "{\"nonce\":\"a\",\"expiresAt\":\"2026-10-03T12:05:00Z\"}",
            "not json",
            "[1,2]",
            "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":\"a\",\"expiresAt\":\"2026-10-03T12:05:00Z\"} trailing",
        )
        for (bad in badList) {
            val factory = ScriptedAuthFactory()
            factory.handler = { req -> jsonResponse(req, 200, bad) }
            val api = scriptedApi(factory)
            try {
                api.challenge()
                fail("expected MalformedResponse for $bad")
            } catch (e: SyncApiException.MalformedResponse) {
                assertNull(e.cause)
            }
        }
    }

    // ---- exchange / refresh exact bodies ----

    @Test fun exchangeSendsExactBytesAndKeyOnce() = runTest {
        val attempt = GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("opaque-token"))
        var closed = false
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON, onClose = { closed = true }) }
        val api = scriptedApi(factory)
        val session = api.exchange(attempt)
        assertEquals(ACCOUNT_ID, session.account.accountId)
        assertEquals(1, factory.count.get())
        val req = factory.captured.single()
        assertEquals("POST", req.method)
        assertEquals("/v1/auth/google", req.url.encodedPath)
        assertNull(req.header("Authorization"))
        assertEquals(EXCHANGE_KEY, req.header("Idempotency-Key"))
        assertEquals(attempt.requestJson, requestBodyUtf8(req))
        assertTrue(closed)
    }

    @Test fun refreshSendsExactBytesAndKeyOnce() = runTest {
        val attempt = ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("opaque refresh"))
        var closed = false
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON, onClose = { closed = true }) }
        val api = scriptedApi(factory)
        val session = api.refresh(attempt)
        assertEquals(ACCOUNT_ID, session.account.accountId)
        val req = factory.captured.single()
        assertEquals("/v1/auth/refresh", req.url.encodedPath)
        assertEquals(REFRESH_KEY, req.header("Idempotency-Key"))
        assertEquals(attempt.requestJson, requestBodyUtf8(req))
        assertTrue(closed)
    }

    @Test fun arbitraryRestoredExchangeRawPreservedOnRepeats() = runTest {
        // Independent raw: reversed order, inner/outer whitespace, alternate \u00e9 escape, newline.
        val restored = GoogleExchangeAttempt.fromJson(EXCHANGE_KEY, ARBITRARY_EXCHANGE_RAW)
        assertEquals(ARBITRARY_EXCHANGE_RAW, restored.requestJson)
        assertEquals(EXCHANGE_KEY, restored.idempotencyKey)
        val f1 = ScriptedAuthFactory()
        f1.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        scriptedApi(f1).exchange(restored)
        val f2 = ScriptedAuthFactory()
        f2.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        scriptedApi(f2).exchange(restored)
        assertEquals(ARBITRARY_EXCHANGE_RAW, requestBodyUtf8(f1.captured.single()))
        assertEquals(ARBITRARY_EXCHANGE_RAW, requestBodyUtf8(f2.captured.single()))
        assertEquals(EXCHANGE_KEY, f1.captured.single().header("Idempotency-Key"))
        assertEquals(EXCHANGE_KEY, f2.captured.single().header("Idempotency-Key"))
        // Raw escape form preserved, not normalized to literal char.
        assertTrue(requestBodyUtf8(f1.captured.single()).contains("\\u00e9"))
    }

    @Test fun arbitraryRestoredRefreshRawPreservedOnRepeats() = runTest {
        val restored = ServiceRefreshAttempt.fromJson(REFRESH_KEY, ARBITRARY_REFRESH_RAW)
        assertEquals(ARBITRARY_REFRESH_RAW, restored.requestJson)
        val g1 = ScriptedAuthFactory()
        g1.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        scriptedApi(g1).refresh(restored)
        val g2 = ScriptedAuthFactory()
        g2.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        scriptedApi(g2).refresh(restored)
        assertEquals(ARBITRARY_REFRESH_RAW, requestBodyUtf8(g1.captured.single()))
        assertEquals(ARBITRARY_REFRESH_RAW, requestBodyUtf8(g2.captured.single()))
        assertEquals(REFRESH_KEY, g1.captured.single().header("Idempotency-Key"))
    }

    @Test fun explicitRetryAfterIOExceptionSendsIdenticalBytes() = runTest {
        val exRestored = GoogleExchangeAttempt.fromJson(EXCHANGE_KEY, ARBITRARY_EXCHANGE_RAW)
        val factory = ScriptedAuthFactory()
        var first = true
        factory.handler = { req ->
            if (first) {
                first = false
                throw IOException("lost response")
            }
            jsonResponse(req, 200, VALID_GRANT_JSON)
        }
        val api = scriptedApi(factory)
        try {
            api.exchange(exRestored)
            fail("expected Transient on lost response")
        } catch (e: SyncApiException.Transient) {
            assertNull(e.cause)
        }
        val s = api.exchange(exRestored)
        assertEquals(ACCOUNT_ID, s.account.accountId)
        assertEquals(2, factory.count.get())
        assertEquals(ARBITRARY_EXCHANGE_RAW, requestBodyUtf8(factory.captured[0]))
        assertEquals(ARBITRARY_EXCHANGE_RAW, requestBodyUtf8(factory.captured[1]))
        assertEquals(EXCHANGE_KEY, factory.captured[1].header("Idempotency-Key"))

        val rfRestored = ServiceRefreshAttempt.fromJson(REFRESH_KEY, ARBITRARY_REFRESH_RAW)
        val factory2 = ScriptedAuthFactory()
        var first2 = true
        factory2.handler = { req ->
            if (first2) {
                first2 = false
                throw IOException("lost response")
            }
            jsonResponse(req, 200, VALID_GRANT_JSON)
        }
        val api2 = scriptedApi(factory2)
        try {
            api2.refresh(rfRestored)
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertNull(e.cause)
        }
        api2.refresh(rfRestored)
        assertEquals(ARBITRARY_REFRESH_RAW, requestBodyUtf8(factory2.captured[0]))
        assertEquals(ARBITRARY_REFRESH_RAW, requestBodyUtf8(factory2.captured[1]))
    }

    @Test fun noBearerQueryOnAuthRoutes() = runTest {
        val ex = GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t"))
        val rf = ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r"))
        val cases = listOf<suspend (HttpServiceAuthApi) -> Unit>(
            { api -> api.challenge() },
            { api -> api.exchange(ex) },
            { api -> api.refresh(rf) },
            { api -> api.logout(OpaqueToken("r")) },
        )
        for (fn in cases) {
            val factory = ScriptedAuthFactory()
            factory.handler = { req ->
                if (req.url.encodedPath == "/v1/auth/logout") {
                    empty204(req)
                } else {
                    jsonResponse(req, 200, if (req.url.encodedPath.contains("challenge")) VALID_CHALLENGE_JSON else VALID_GRANT_JSON)
                }
            }
            fn(scriptedApi(factory))
            val req = factory.captured.single()
            assertNull(req.header("Authorization"))
            assertTrue(req.url.encodedQuery.isNullOrEmpty())
        }
    }

    @Test fun challengeLogoutHaveNoIdempotencyKey() = runTest {
        val f1 = ScriptedAuthFactory()
        f1.handler = { req -> jsonResponse(req, 200, VALID_CHALLENGE_JSON) }
        scriptedApi(f1).challenge()
        assertNull(f1.captured.single().header("Idempotency-Key"))
        val f2 = ScriptedAuthFactory()
        f2.handler = { req -> empty204(req) }
        scriptedApi(f2).logout(OpaqueToken("opaque refresh"))
        assertNull(f2.captured.single().header("Idempotency-Key"))
    }

    @Test fun unicodeBlankEscapedCredentialsPreserved() = runTest {
        val tricky = listOf(" ", "  ", "tökén-🎉", "a\u0001b", "quote\"slash\\", "line\nbreak")
        for (v in tricky) {
            val ex = GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken(v))
            val factory = ScriptedAuthFactory()
            factory.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
            scriptedApi(factory).exchange(ex)
            assertEquals(ex.requestJson, requestBodyUtf8(factory.captured.single()))
            val rf = ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken(v))
            val factory2 = ScriptedAuthFactory()
            factory2.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
            scriptedApi(factory2).refresh(rf)
            assertEquals(rf.requestJson, requestBodyUtf8(factory2.captured.single()))
        }
    }

    @Test fun unpairedSurrogatePreflightFailsBeforeSend() = runTest {
        val badString = "bad\uD800char"
        val badJson = "{\"challengeId\":\"$CHALLENGE_ID\",\"idToken\":" + org.json.JSONObject.quote(badString) + "}"
        // fromJson may itself reject; if it does, that also satisfies safe preflight without send.
        // Otherwise exchange must fail with SyncModelException and no I/O.
        try {
            val restored = GoogleExchangeAttempt.fromJson(EXCHANGE_KEY, badJson)
            val factory = ScriptedAuthFactory()
            factory.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
            try {
                scriptedApi(factory).exchange(restored)
                fail("expected SyncModelException for unpaired surrogate")
            } catch (e: SyncModelException) {
                assertNull(e.cause)
                assertEquals(0, factory.count.get())
            }
        } catch (e: SyncModelException) {
            assertNull(e.cause)
        }
    }

    @Test fun logoutSendsExactBodyAndNoKey() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> empty204(req) }
        scriptedApi(factory).logout(OpaqueToken("opaque refresh"))
        val req = factory.captured.single()
        assertEquals("POST", req.method)
        assertEquals("/v1/auth/logout", req.url.encodedPath)
        assertNull(req.header("Idempotency-Key"))
        assertEquals("{\"refreshToken\":\"opaque refresh\"}", requestBodyUtf8(req))
    }

    @Test fun logoutUnicodeBodyUsesExactQuoting() = runTest {
        val value = "tökén-🎉 \"q\""
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> empty204(req) }
        scriptedApi(factory).logout(OpaqueToken(value))
        val expected = "{\"refreshToken\":" + org.json.JSONObject.quote(value) + "}"
        assertEquals(expected, requestBodyUtf8(factory.captured.single()))
    }

    @Test fun legacyDelegationMatchesTyped() = runTest {
        val key = EXCHANGE_KEY
        val token = OpaqueToken("legacy-token")
        val typed = GoogleExchangeAttempt.capture(key, CHALLENGE_ID, token)
        val fTyped = ScriptedAuthFactory()
        fTyped.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        scriptedApi(fTyped).exchange(typed)
        val fLegacy = ScriptedAuthFactory()
        fLegacy.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        @Suppress("DEPRECATION")
        scriptedApi(fLegacy).exchange(key, CHALLENGE_ID, token)
        assertEquals(requestBodyUtf8(fTyped.captured.single()), requestBodyUtf8(fLegacy.captured.single()))
        assertEquals(key, fLegacy.captured.single().header("Idempotency-Key"))

        val rKey = REFRESH_KEY
        val rToken = OpaqueToken("legacy-refresh")
        val rTyped = ServiceRefreshAttempt.capture(rKey, rToken)
        val gTyped = ScriptedAuthFactory()
        gTyped.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        scriptedApi(gTyped).refresh(rTyped)
        val gLegacy = ScriptedAuthFactory()
        gLegacy.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        @Suppress("DEPRECATION")
        scriptedApi(gLegacy).refresh(rKey, rToken)
        assertEquals(requestBodyUtf8(gTyped.captured.single()), requestBodyUtf8(gLegacy.captured.single()))
    }

    // ---- grant decoding ----

    @Test fun grantSuccessDecodesAllFields() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        val api = scriptedApi(factory)
        val attempt = GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t"))
        val s = api.exchange(attempt)
        assertEquals(ACCOUNT_ID, s.account.accountId)
        assertNull(s.account.displayName)
        assertEquals("opaque access", s.accessToken.value)
        assertEquals("opaque refresh", s.refreshToken.value)
        assertEquals(java.time.Instant.parse("2026-10-03T12:15:00Z").toEpochMilli(), s.accessExpiresAtMillis)
        assertEquals(java.time.Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(), s.refreshExpiresAtMillis)
    }

    @Test fun grantWithDisplayNameAndExpiredTimesStillDecodes() = runTest {
        val expired = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":\"Example\"}," +
            "\"accessToken\":\"a\",\"accessExpiresAt\":\"2020-01-01T00:00:00Z\"," +
            "\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2020-01-02T00:00:00Z\"}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, expired) }
        val s = scriptedApi(factory).refresh(ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r")))
        assertEquals("Example", s.account.displayName)
        // No clock validation: expired values are representable.
        assertEquals(java.time.Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(), s.accessExpiresAtMillis)
    }

    @Test fun grantRequiredFieldTableIsMalformed() = runTest {
        val base = mapOf(
            "account" to "{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null}",
            "accessToken" to "\"opaque access\"",
            "accessExpiresAt" to "\"2026-10-03T12:15:00Z\"",
            "refreshToken" to "\"opaque refresh\"",
            "refreshExpiresAt" to "\"2026-11-02T12:00:00Z\"",
        )
        for (missing in base.keys) {
            val kept = base.filterKeys { it != missing }
            val body = "{" + kept.entries.joinToString(",") { (k, v) -> "\"" + k + "\":" + v } + "}"
            val factory = ScriptedAuthFactory()
            factory.handler = { req -> jsonResponse(req, 200, body) }
            try {
                scriptedApi(factory).exchange(GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t")))
                fail("expected MalformedResponse for missing $missing")
            } catch (e: SyncApiException.MalformedResponse) {
                assertNull(e.cause)
            }
        }
        // Missing nested account.id.
        val noId = "{\"account\":{\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}"
        val f2 = ScriptedAuthFactory()
        f2.handler = { req -> jsonResponse(req, 200, noId) }
        try {
            scriptedApi(f2).refresh(ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r")))
            fail("expected MalformedResponse for missing account.id")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun grantTypeUuidTimeTableIsMalformed() = runTest {
        val badBodies = listOf(
            // Wrong types.
            "{\"account\":null,\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}",
            "{\"account\":[],\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}",
            "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":123,\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}",
            "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":123,\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}",
            "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":123},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}",
            // Bad UUIDs.
            "{\"account\":{\"id\":\"NOT-UUID\",\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}",
            "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":\"not-time\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}",
            // Nonzero submillisecond must reject; fixtures stay millisecond exact.
            "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00.0001Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}",
            "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:15:00.5001Z\"}",
        )
        for (bad in badBodies) {
            val factory = ScriptedAuthFactory()
            factory.handler = { req -> jsonResponse(req, 200, bad) }
            try {
                scriptedApi(factory).refresh(ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r")))
                fail("expected MalformedResponse for $bad")
            } catch (e: SyncApiException.MalformedResponse) {
                assertNull(e.cause)
            }
        }
    }

    @Test fun grantMillisecondExactTimesAreLossless() = runTest {
        // Millisecond-exact fractional times (including trailing-zero .500Z) decode losslessly.
        val body = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00.500Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00.000Z\"}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, body) }
        val s = scriptedApi(factory).refresh(ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r")))
        assertEquals(ACCOUNT_ID, s.account.accountId)
        assertEquals(java.time.Instant.parse("2026-10-03T12:15:00.500Z").toEpochMilli(), s.accessExpiresAtMillis)
        assertEquals(java.time.Instant.parse("2026-11-02T12:00:00.000Z").toEpochMilli(), s.refreshExpiresAtMillis)
    }

    @Test fun grantUnorderedExpiredFidelity() = runTest {
        // Unordered lifetimes remain representable; expired values do not schedule.
        val unordered = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-11-02T12:00:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-10-03T12:15:00Z\"}"
        val f1 = ScriptedAuthFactory()
        f1.handler = { req -> jsonResponse(req, 200, unordered) }
        val s = scriptedApi(f1).exchange(GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t")))
        assertEquals(ACCOUNT_ID, s.account.accountId)
        assertTrue(s.accessExpiresAtMillis > s.refreshExpiresAtMillis)
    }

    @Test fun grantBlankUnicodeDisplayAndTokens() = runTest {
        val body = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":\" tökén-\uD83C\uDF89 \" },\"accessToken\":\" \",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"tökén-\uD83C\uDF89\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, body) }
        val s = scriptedApi(factory).refresh(ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r")))
        assertEquals(" tökén-\uD83C\uDF89 ", s.account.displayName)
        assertEquals(" ", s.accessToken.value)
        assertEquals("tökén-\uD83C\uDF89", s.refreshToken.value)
    }

    @Test fun grantAdditiveAccountTolerated() = runTest {
        val body = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null,\"extra\":1},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\",\"extraTop\":[]}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, body) }
        val s = scriptedApi(factory).exchange(GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t")))
        assertEquals(ACCOUNT_ID, s.account.accountId)
    }

    @Test fun grantDuplicateKeysAreMalformed() = runTest {
        val dup = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"id\":\"other\",\"displayName\":null}," +
            "\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, dup) }
        try {
            scriptedApi(factory).exchange(GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t")))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
        // Nested escaped duplicate: raw backslash-u0061 == 'a' collides after decoding.
        val escapedDup = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"a\",\"\\u0061ccessToken\":\"b\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}"
        val f2 = ScriptedAuthFactory()
        f2.handler = { req -> jsonResponse(req, 200, escapedDup) }
        try {
            scriptedApi(f2).refresh(ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r")))
            fail("expected MalformedResponse for escaped duplicate")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
        // Nested ignored-object aliases also validate: raw backslash-u0061 collides inside an additive object.
        val nestedAliasDup = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"a\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\",\"extra\":{\"a\":1,\"\\u0061\":2}}"
        val f3 = ScriptedAuthFactory()
        f3.handler = { req -> jsonResponse(req, 200, nestedAliasDup) }
        try {
            scriptedApi(f3).exchange(GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t")))
            fail("expected MalformedResponse for nested escaped duplicate")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun grantScalarOverflowIsMalformed() = runTest {
        val overflow = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null}," +
            "\"accessToken\":1e400,\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"r\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}"
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, overflow) }
        try {
            scriptedApi(factory).refresh(ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r")))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    // ---- logout 204 verification ----

    @Test fun logout204EmptySucceedsWithoutContentType() = runTest {
        val factory = ScriptedAuthFactory()
        var closed = false
        factory.handler = { req -> empty204(req, withContentType = false, onClose = { closed = true }) }
        scriptedApi(factory).logout(OpaqueToken("opaque refresh"))
        assertEquals(1, factory.count.get())
        assertTrue(closed)
    }

    @Test fun logout204WithByteIsMalformed() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> singleByte204(req, "x") }
        try {
            scriptedApi(factory).logout(OpaqueToken("r"))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun logout204LengthMismatchIsMalformed() = runTest {
        // Framing must still validate: declared length 1 with verified-empty body is malformed.
        val factory = ScriptedAuthFactory()
        factory.handler = { req ->
            val hb = Headers.Builder().add("Content-Length", "1").build()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .headers(hb)
                .body(CloseTrackingBody(ByteString.EMPTY, null))
                .build()
        }
        try {
            scriptedApi(factory).logout(OpaqueToken("r"))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun logout204InvalidLengthIsMalformed() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req ->
            val hb = Headers.Builder().add("Content-Length", "not-a-number").build()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .headers(hb)
                .body(CloseTrackingBody(ByteString.EMPTY, null))
                .build()
        }
        try {
            scriptedApi(factory).logout(OpaqueToken("r"))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun logoutUnexpected200IsMalformed() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 200, VALID_GRANT_JSON) }
        try {
            scriptedApi(factory).logout(OpaqueToken("r"))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    // ---- actual failure routing ----

    @Test fun authoritativeStatusesNeedNoBodyRead() = runTest {
        // Even with broken bodies, 413/429/5xx stay authoritative.
        for (status in listOf(413, 429, 500, 502, 503, 504)) {
            val factory = ScriptedAuthFactory()
            factory.handler = { req ->
                rawBytesResponse(req, status, ByteString.of(0xFF.toByte()), contentType = "text/plain")
            }
            val api = scriptedApi(factory)
            try {
                api.challenge()
                fail("expected failure for $status")
            } catch (e: SyncApiException) {
                if (status == 413) {
                    assertTrue(e is SyncApiException.RequestTooLarge)
                } else {
                    assertTrue("$status", e is SyncApiException.Transient)
                }
                assertNull(e.cause)
            }
        }
    }

    @Test fun refresh401NeedsNoBodyRead() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req ->
            rawBytesResponse(req, 401, ByteString.of(0xFF.toByte()), contentType = "text/plain")
        }
        try {
            scriptedApi(factory).refresh(ServiceRefreshAttempt.capture(REFRESH_KEY, OpaqueToken("r")))
            fail("expected ReauthenticationRequired")
        } catch (e: SyncApiException.ReauthenticationRequired) {
            assertNull(e.cause)
        }
    }

    @Test fun exchange401BestEffortRouting() = runTest {
        // Valid reauth -> Reauth.
        val f1 = ScriptedAuthFactory()
        f1.handler = { req -> jsonResponse(req, 401, errorJson(401, "reauthentication_required")) }
        try {
            scriptedApi(f1).exchange(GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t")))
            fail("expected Reauth")
        } catch (e: SyncApiException.ReauthenticationRequired) {
            assertNull(e.cause)
        }
        // Valid permanent proof rejection -> RequestRejected.
        val f2 = ScriptedAuthFactory()
        f2.handler = { req -> jsonResponse(req, 401, errorJson(401, "invalid_google_credential")) }
        try {
            scriptedApi(f2).exchange(GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t")))
            fail("expected RequestRejected")
        } catch (e: SyncApiException.RequestRejected) {
            assertEquals("invalid_google_credential", e.errorCode)
        }
        // Broken body -> Malformed, never invented code or access recovery.
        val f3 = ScriptedAuthFactory()
        f3.handler = { req -> jsonResponse(req, 401, "not json", contentType = "application/json; charset=utf-8") }
        try {
            scriptedApi(f3).exchange(GoogleExchangeAttempt.capture(EXCHANGE_KEY, CHALLENGE_ID, OpaqueToken("t")))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun other4xxInvalidEnvelopeIsMalformed() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 400, "not json") }
        try {
            scriptedApi(factory).challenge()
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun other4xxMidReadIoIsTransient() = runTest {
        // Genuine generic body I/O during error-envelope read stays Transient.
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> readFailingResponse(req, 400) }
        try {
            scriptedApi(factory).challenge()
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertNull(e.cause)
        }
    }

    @Test fun exchange401MidReadIoIsMalformed() = runTest {
        // Best-effort EXCHANGE401 error read: non-cancellation read failure is Malformed, never Transient recovery.
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> readFailingResponse(req, 401) }
        try {
            scriptedApi(factory).exchange(GoogleExchangeAttempt.fromJson(EXCHANGE_KEY, ARBITRARY_EXCHANGE_RAW))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun unexpectedStatusIsMalformedWithoutBodyRead() = runTest {
        val factory = ScriptedAuthFactory()
        factory.handler = { req -> jsonResponse(req, 302, "not json", contentType = "text/plain") }
        try {
            scriptedApi(factory).challenge()
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }

    @Test fun errorPresentationMessageNeverRoutes() = runTest {
        // Message claiming reauth must not change category; code drives routing.
        val factory = ScriptedAuthFactory()
        factory.handler = { req ->
            jsonResponse(req, 400, "{\"errorCode\":\"future_code\",\"message\":\"reauthentication_required\"}")
        }
        try {
            scriptedApi(factory).challenge()
            fail("expected RequestRejected")
        } catch (e: SyncApiException.RequestRejected) {
            assertEquals("future_code", e.errorCode)
        }
    }

    // ---- redaction / one-send / cancellation ----

    @Test fun adapterDisplayIsRedacted() {
        val api = HttpServiceAuthApi(testOrigin())
        val display = api.toString()
        assertRedacted(display, "example.com", EXCHANGE_KEY, "opaque", ACCOUNT_ID, "Bearer", "Idempotency")
        assertEquals(api.toString(), HttpServiceAuthApi(testOrigin()).toString())
    }

    @Test fun eachExplicitCallSendsOnceAndCloses() = runTest {
        val factory = ScriptedAuthFactory()
        var closes = 0
        factory.handler = { req -> jsonResponse(req, 200, VALID_CHALLENGE_JSON, onClose = { closes++ }) }
        val api = scriptedApi(factory)
        api.challenge()
        api.challenge()
        assertEquals(2, factory.count.get())
        assertEquals(2, closes)
    }

    @Test fun cancellationBeforeHeadersPropagates() = runTest {
        val factory = ScriptedAuthFactory()
        factory.hang = true
        val api = scriptedApi(factory)
        val deferred = async { api.challenge() }
        // Let the scripted call start, then cancel.
        kotlinx.coroutines.delay(10)
        deferred.cancelAndJoin()
        try {
            deferred.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // Identity preserved through accepted transport, not wrapped.
        }
        assertTrue(factory.calls.first().canceled)
    }

    @Test fun directTransportCancellationIdentityAllOperations() = runTest {
        val boom = CancellationException("injected-cancel")
        val origin = testOrigin()
        val api = HttpServiceAuthApi(origin, FakeDirectTransport(boom))
        val ex = GoogleExchangeAttempt.fromJson(EXCHANGE_KEY, ARBITRARY_EXCHANGE_RAW)
        val rf = ServiceRefreshAttempt.fromJson(REFRESH_KEY, ARBITRARY_REFRESH_RAW)
        try {
            api.challenge()
            fail("expected cancel")
        } catch (e: CancellationException) {
            assertSame(boom, e)
        }
        try {
            api.exchange(ex)
            fail("expected cancel")
        } catch (e: CancellationException) {
            assertSame(boom, e)
        }
        try {
            api.refresh(rf)
            fail("expected cancel")
        } catch (e: CancellationException) {
            assertSame(boom, e)
        }
        try {
            api.logout(OpaqueToken("r"))
            fail("expected cancel")
        } catch (e: CancellationException) {
            assertSame(boom, e)
        }
    }

    @Test fun cancelledCallerWinsOverLateTransportFailure() = runTest {
        val ex = GoogleExchangeAttempt.fromJson(EXCHANGE_KEY, ARBITRARY_EXCHANGE_RAW)
        val rf = ServiceRefreshAttempt.fromJson(REFRESH_KEY, ARBITRARY_REFRESH_RAW)
        val cases: List<Triple<String, Throwable, suspend (HttpServiceAuthApi) -> Unit>> = listOf(
            Triple("challenge-malformed", SyncApiException.MalformedResponse() as Throwable, { api: HttpServiceAuthApi -> api.challenge() }),
            Triple("challenge-transient", SyncApiException.Transient(2000L) as Throwable) { api: HttpServiceAuthApi -> api.challenge() },
            Triple("challenge-unknown", RuntimeException("late") as Throwable) { api: HttpServiceAuthApi -> api.challenge() },
            Triple("exchange-malformed", SyncApiException.MalformedResponse() as Throwable) { api: HttpServiceAuthApi -> api.exchange(ex) },
            Triple("exchange-transient", SyncApiException.Transient(2000L) as Throwable) { api: HttpServiceAuthApi -> api.exchange(ex) },
            Triple("exchange-unknown", RuntimeException("late") as Throwable) { api: HttpServiceAuthApi -> api.exchange(ex) },
            Triple("refresh-malformed", SyncApiException.MalformedResponse() as Throwable) { api: HttpServiceAuthApi -> api.refresh(rf) },
            Triple("refresh-transient", SyncApiException.Transient(2000L) as Throwable) { api: HttpServiceAuthApi -> api.refresh(rf) },
            Triple("refresh-unknown", RuntimeException("late") as Throwable) { api: HttpServiceAuthApi -> api.refresh(rf) },
            Triple("logout-malformed", SyncApiException.MalformedResponse() as Throwable) { api: HttpServiceAuthApi -> api.logout(OpaqueToken("r")) },
            Triple("logout-transient", SyncApiException.Transient(2000L) as Throwable) { api: HttpServiceAuthApi -> api.logout(OpaqueToken("r")) },
            Triple("logout-unknown", RuntimeException("late") as Throwable) { api: HttpServiceAuthApi -> api.logout(OpaqueToken("r")) },
        )
        for ((name, failure, invoke) in cases) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val api = HttpServiceAuthApi(testOrigin(), GatedLateFailureTransport(entered, release, failure))
            val supplied = CancellationException("cancel-wins-$name")
            val observed = CompletableDeferred<CancellationException>()
            val job = launch {
                try {
                    invoke(api)
                    fail("case $name: expected CancellationException")
                } catch (e: CancellationException) {
                    if (!observed.isCompleted) {
                        observed.complete(e)
                    }
                    throw e
                }
            }
            withTimeout(10000L) { entered.await() }
            job.cancel(supplied)
            release.complete(Unit)
            job.join()
            val caught = withTimeout(10000L) { observed.await() }
            assertSame("case $name", supplied, caught)
        }
    }

    @Test fun directTransportSafeFailureIdentity() = runTest {
        val origin = testOrigin()
        val transient = SyncApiException.Transient(2000L)
        val apiT = HttpServiceAuthApi(origin, FakeDirectTransport(transient))
        try {
            apiT.challenge()
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertSame(transient, e)
        }
        val malformed = SyncApiException.MalformedResponse()
        val apiM = HttpServiceAuthApi(origin, FakeDirectTransport(malformed))
        val ex = GoogleExchangeAttempt.fromJson(EXCHANGE_KEY, ARBITRARY_EXCHANGE_RAW)
        try {
            apiM.exchange(ex)
            fail("expected Malformed")
        } catch (e: SyncApiException.MalformedResponse) {
            assertSame(malformed, e)
        }
        val reauth = SyncApiException.ReauthenticationRequired()
        val apiR = HttpServiceAuthApi(origin, FakeDirectTransport(reauth))
        val rf = ServiceRefreshAttempt.fromJson(REFRESH_KEY, ARBITRARY_REFRESH_RAW)
        try {
            apiR.refresh(rf)
            fail("expected Reauth")
        } catch (e: SyncApiException.ReauthenticationRequired) {
            assertSame(reauth, e)
        }
    }

    @Test fun directTransportUnknownSeamIsClosedTransient() = runTest {
        val origin = testOrigin()
        val api = HttpServiceAuthApi(origin, FakeDirectTransport(RuntimeException("boom")))
        try {
            api.challenge()
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertNull(e.cause)
            assertFalse(e.message.orEmpty().contains("boom"))
        }
        try {
            api.logout(OpaqueToken("r"))
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertNull(e.cause)
        }
    }

    @Test fun scriptedOfflineBeforeHeadersIsTransient() = runTest {
        val f1 = ScriptedAuthFactory()
        f1.failure = IOException("offline-transport")
        try {
            scriptedApi(f1).challenge()
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertNull(e.cause)
        }
    }

    @Test fun strictMediaUtf8TruncationAreMalformed() = runTest {
        // Wrong media.
        val f1 = ScriptedAuthFactory()
        f1.handler = { req -> jsonResponse(req, 200, VALID_CHALLENGE_JSON, contentType = "text/plain") }
        try {
            scriptedApi(f1).challenge()
            fail("expected MalformedResponse for media")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
        // Invalid UTF-8 bytes.
        val f2 = ScriptedAuthFactory()
        f2.handler = { req -> rawBytesResponse(req, 200, ByteString.of(0xFF.toByte(), 0xFE.toByte())) }
        try {
            scriptedApi(f2).challenge()
            fail("expected MalformedResponse for utf8")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
        // Truncation via Content-Length mismatch.
        val f3 = ScriptedAuthFactory()
        f3.handler = { req ->
            jsonResponse(req, 200, VALID_CHALLENGE_JSON, contentLengthHeader = "99999")
        }
        try {
            scriptedApi(f3).challenge()
            fail("expected MalformedResponse for truncation")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
    }
}
