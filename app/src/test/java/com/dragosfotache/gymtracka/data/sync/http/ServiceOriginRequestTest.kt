package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * R03a QA: origin / request / timeout / retry-hint tables.
 * Pure validation from the normative spec; no network I/O.
 */
class ServiceOriginRequestTest {

    // ---------- helpers ----------

    private fun assertOriginInvalid(value: String) {
        try {
            ServiceOrigin.parse(value)
        } catch (e: SyncModelException) {
            assertSafeMessage(e.message, value)
            return
        }
        fail("parse should reject: <${escape(value)}>")
    }

    private fun assertConfiguredInvalid(value: String) {
        try {
            val result = ServiceOrigin.configured(value)
            fail("configured should reject: <${escape(value)}> but returned $result")
        } catch (e: SyncModelException) {
            assertSafeMessage(e.message, value)
        }
    }

    private fun assertSafeMessage(message: String?, sensitive: String) {
        assertTrue("error message must be present", !message.isNullOrBlank())
        if (sensitive.isNotEmpty() && sensitive.length <= 200) {
            // Fixed safe text must not echo raw input, tokens or URLs.
            assertFalse(
                "error must not leak raw input",
                message!!.contains(sensitive),
            )
        }
    }

    private fun escape(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            when {
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 32 || c.code == 127 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun assertRequestInvalid(build: () -> ServiceHttpRequest) {
        // Either construction fails, or transport validation fails before I/O.
        val request: ServiceHttpRequest = try {
            build()
        } catch (e: SyncModelException) {
            return
        }
        val factory = NoIoFactory()
        val origin = ServiceOrigin.parse("https://example.com")
        val transport = OkHttpServiceTransport(origin, factory)
        var consumerInvoked = false
        try {
            runBlocking {
                transport.withResponse(request) { _: ServiceHttpResponse ->
                    consumerInvoked = true
                    "unreachable-consumer"
                }
            }
            fail("invalid request must throw SyncModelException, but returned normally")
        } catch (e: SyncModelException) {
            assertFalse("invalid request must not reach consumer", consumerInvoked)
        } catch (e: Exception) {
            fail("invalid request must fail with SyncModelException, got ${e::class.java.name}: ${e.message}")
        }
        assertFalse("invalid request must fail BEFORE I/O", factory.ioAttempted)
    }

    private class CapturingFactory : Call.Factory {
        val captured = java.util.concurrent.atomic.AtomicReference<Request?>()
        override fun newCall(request: Request): Call {
            captured.set(request)
            return object : Call {
                override fun request(): Request = request
                override fun execute(): Response = throw IOException("async only")
                override fun enqueue(responseCallback: Callback) {
                    val body = "{\"ok\":true}".toResponseBody(
                        "application/json; charset=utf-8".toMediaType(),
                    )
                    val response = Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(body)
                        .build()
                    responseCallback.onResponse(this, response)
                }
                override fun cancel() {}
                override fun isExecuted(): Boolean = false
                override fun isCanceled(): Boolean = false
                override fun timeout(): Timeout = Timeout.NONE
                override fun clone(): Call = this
                override fun addEventListener(eventListener: EventListener) {}
                override fun <T : Any> tag(type: KClass<T>): T? = null
                override fun <T> tag(type: Class<out T>): T? = null
                override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
                    computeIfAbsent()
                override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
                    computeIfAbsent()
            }
        }
    }

    private fun wireUrlFor(request: ServiceHttpRequest): String {
        val factory = CapturingFactory()
        val origin = ServiceOrigin.parse("https://example.com")
        val transport = OkHttpServiceTransport(origin, factory)
        runBlocking {
            transport.withResponse(request) { response ->
                response.readJsonBody()
            }
        }
        return factory.captured.get()!!.url.toString()
    }

    private class NoIoFactory : Call.Factory {
        var ioAttempted: Boolean = false
        override fun newCall(request: Request): Call {
            ioAttempted = true
            return object : Call {
                override fun request(): Request = request
                override fun execute(): Response = throw IOException("must not execute")
                override fun enqueue(responseCallback: Callback) {
                    responseCallback.onFailure(this, IOException("must not run I/O"))
                }
                override fun cancel() {}
                override fun isExecuted(): Boolean = false
                override fun isCanceled(): Boolean = false
                override fun timeout(): Timeout = Timeout.NONE
                override fun clone(): Call = this
                override fun addEventListener(eventListener: EventListener) {}
                override fun <T : Any> tag(type: KClass<T>): T? = null
                override fun <T> tag(type: Class<out T>): T? = null
                override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
                    computeIfAbsent()
                override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
                    computeIfAbsent()
            }
        }
    }

    // ---------- ServiceOrigin.parse valid ----------

    @Test
    fun parse_acceptsBareHttpsOrigin() {
        val origin = ServiceOrigin.parse("https://example.com")
        assertEquals("https", origin.scheme)
        assertEquals("example.com", origin.host)
        assertEquals(443, origin.port)
    }

    @Test
    fun parse_acceptsExplicit443AsEffective443() {
        val bare = ServiceOrigin.parse("https://example.com")
        val explicit = ServiceOrigin.parse("https://example.com:443")
        assertEquals(bare, explicit)
        assertEquals(bare.hashCode(), explicit.hashCode())
        assertEquals(443, explicit.port)
    }

    @Test
    fun parse_acceptsRootSlashOnly() {
        val bare = ServiceOrigin.parse("https://example.com")
        val rooted = ServiceOrigin.parse("https://example.com/")
        assertEquals(bare, rooted)
    }

    @Test
    fun parse_normalizesSchemeAndHostCase() {
        val lower = ServiceOrigin.parse("https://example.com")
        val upper = ServiceOrigin.parse("HTTPS://EXAMPLE.COM")
        assertEquals(lower, upper)
        assertEquals("https", upper.scheme)
        assertEquals("example.com", upper.host)
    }

    @Test
    fun parse_acceptsCustomPortIpAndLocalhost() {
        val custom = ServiceOrigin.parse("https://example.com:8443")
        assertEquals(8443, custom.port)
        val v4 = ServiceOrigin.parse("https://127.0.0.1:8443")
        assertEquals("127.0.0.1", v4.host)
        assertEquals(8443, v4.port)
        val local = ServiceOrigin.parse("https://localhost:8443")
        assertEquals("localhost", local.host)
        val v6 = ServiceOrigin.parse("https://[::1]:8443")
        assertEquals(8443, v6.port)
        assertFalse(v6.host.contains("["))
        assertFalse(v6.host.contains("]"))
    }

    @Test
    fun parse_canonicalIdentityIgnoresOriginalText() {
        val a = ServiceOrigin.parse("https://example.com")
        val b = ServiceOrigin.parse("HTTPS://EXAMPLE.COM/")
        val c = ServiceOrigin.parse("https://example.com:443/")
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
        val otherPort = ServiceOrigin.parse("https://example.com:8443")
        assertNotEquals(a, otherPort)
        val otherHost = ServiceOrigin.parse("https://other.example.com")
        assertNotEquals(a, otherHost)
    }

    @Test
    fun parse_rejectsSuffixComparison() {
        val a = ServiceOrigin.parse("https://example.com")
        val evil = ServiceOrigin.parse("https://evil-example.com")
        assertNotEquals(a, evil)
        val sub = ServiceOrigin.parse("https://sub.example.com")
        assertNotEquals(a, sub)
    }

    // ---------- ServiceOrigin.parse invalid ----------

    @Test
    fun parse_rejectsEmptyWhitespaceControlsBackslash() {
        assertOriginInvalid("")
        assertOriginInvalid("   ")
        assertOriginInvalid("\t https://example.com")
        assertOriginInvalid("https://example.com\n")
        assertOriginInvalid("https://exam\u0000ple.com")
        assertOriginInvalid("https://exam\u001Fple.com")
        assertOriginInvalid("https:\\\\example.com")
        assertOriginInvalid("https://exam\\ple.com")
    }

    @Test
    fun parse_rejectsNonHttpsAndMalformedScheme() {
        assertOriginInvalid("http://example.com")
        assertOriginInvalid("HTTP://example.com")
        assertOriginInvalid("ftp://example.com")
        assertOriginInvalid("//example.com")
        assertOriginInvalid("example.com")
        assertOriginInvalid("https:/example.com")
        assertOriginInvalid("https:///example.com")
    }

    @Test
    fun parse_rejectsUserInfoQueryFragmentAndPaths() {
        assertOriginInvalid("https://user@example.com")
        assertOriginInvalid("https://user:pass@example.com")
        assertOriginInvalid("https://@example.com")
        assertOriginInvalid("https://example.com?x=1")
        assertOriginInvalid("https://example.com#frag")
        assertOriginInvalid("https://example.com?")
        assertOriginInvalid("https://example.com#")
        assertOriginInvalid("https://example.com/v1")
        assertOriginInvalid("https://example.com/v1/")
        assertOriginInvalid("https://example.com//")
        assertOriginInvalid("https://example.com/%2F")
        assertOriginInvalid("https://example.com/.")
    }

    @Test
    fun parse_rejectsBadHostsAndPorts() {
        assertOriginInvalid("https://")
        assertOriginInvalid("https:/// ")
        assertOriginInvalid("https://[::1")
        assertOriginInvalid("https://example.com:")
        assertOriginInvalid("https://example.com:abc")
        assertOriginInvalid("https://example.com:0")
        assertOriginInvalid("https://example.com:65536")
        assertOriginInvalid("https://example.com:-1")
        assertOriginInvalid("https://example.com:443extra")
    }

    @Test
    fun parse_rejectsUnicodeHostZoneAndEncodedAuthority() {
        assertOriginInvalid("https://exämple.com")
        assertOriginInvalid("https://[fe80::1%eth0]")
        assertOriginInvalid("https://[fe80::1%25eth0]")
        assertOriginInvalid("https://%65xample.com")
        assertOriginInvalid("https://example%2Ecom")
    }

    @Test
    fun parse_rejectsUnpairedSurrogate() {
        assertOriginInvalid("https://example.com\uD800")
    }

    // ---------- configured ----------

    @Test
    fun configured_nullIsUnavailable() {
        assertNull(ServiceOrigin.configured(null))
    }

    @Test
    fun configured_emptyIsInvalid() {
        assertConfiguredInvalid("")
    }

    @Test
    fun configured_validAndInvalid() {
        val origin = ServiceOrigin.configured("https://example.com:8443")
        assertEquals("example.com", origin!!.host)
        assertEquals(8443, origin.port)
        assertConfiguredInvalid("http://example.com")
        assertConfiguredInvalid("https://example.com/v1")
        assertConfiguredInvalid("   ")
    }

    // ---------- request path ----------

    @Test
    fun request_acceptsV1SegmentGrammar() {
        ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a")
        ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/abc-DEF_123")
        ServiceHttpRequest(ServiceHttpMethod.POST, "/v1/a/b/c", body = "{}")
        ServiceHttpRequest(
            ServiceHttpMethod.GET,
            "/v1/b5827485-c9d6-49aa-9578-7a2f637cb8bc",
        )
    }

    @Test
    fun request_rejectsInvalidPaths() {
        val bad = listOf(
            "",
            "v1/a",
            "/v1",
            "/v1/",
            "/v1//a",
            "/v1/a/",
            "/v1/a//b",
            "/v1/.",
            "/v1/..",
            "/v1/a/./b",
            "/v1/a/../b",
            "/v1/a b",
            "/v1/ä",
            "/v1/a%b",
            "/v1/a%2Fb",
            "/v1/a\\b",
            "/v1/a?x=1",
            "/v1/a#frag",
            "/v2/a",
            "https://example.com/v1/a",
            "//example.com/v1/a",
            "/v1/a/b?",
            "/v1/\uD800",
        )
        for (path in bad) {
            assertRequestInvalid { ServiceHttpRequest(ServiceHttpMethod.GET, path) }
        }
    }

    // ---------- query ----------

    @Test
    fun request_queryPreservesOrderRepeatsUnicodeAndSnapshot() {
        val backing = mutableListOf(
            "q" to "hello world",
            "q" to "a+b",
            "cursor" to "100%+ /?#&=",
            "emoji" to "caf\u00E9 \uD83D\uDE00",
            "empty" to "",
            "ws" to "  padded  ",
        )
        val request = ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", query = backing)
        assertEquals(backing, request.query)
        backing.add("late" to "must-not-appear")
        assertEquals(6, request.query.size)
        assertFalse(request.query.any { it.first == "late" })
        assertEquals("hello world", request.query[0].second)
        assertEquals("a+b", request.query[1].second)
    }

    @Test
    fun request_rejectsBadQueryAndSurrogates() {
        assertRequestInvalid {
            ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", query = listOf("" to "v"))
        }
        // Note: literal controls (including NUL) in query names/values are allowed
        // per spec and must be percent-encoded on the wire; see
        // queryControlsArePercentEncodedOnWire. Only unpaired surrogates are rejected.
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                query = listOf("ok" to "bad\uD800"),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                query = listOf("\uD83D" to "v"),
            )
        }
    }

    @Test
    fun queryControlsArePercentEncodedOnWire() {
        // Literal NUL and C0 controls round-trip via percent-encoding, never raw.
        val request = ServiceHttpRequest(
            ServiceHttpMethod.GET,
            "/v1/a",
            query = listOf("k" to "a\u0000b", "m" to "x\u001Fc"),
        )
        val wire = wireUrlFor(request)
        assertTrue(wire.startsWith("https://example.com/v1/a?"))
        assertTrue("NUL must be %00 on wire: $wire", wire.contains("%00"))
        assertTrue("U+001F must be %1F on wire: $wire", wire.contains("%1F"))
        assertFalse(wire.contains("\u0000"))
        assertFalse(wire.contains("\u001F"))
    }

    @Test
    fun queryEncodingReservesStructuralChars() {
        val request = ServiceHttpRequest(
            ServiceHttpMethod.GET,
            "/v1/a",
            query = listOf("q" to "a+b c%d/e?f#g&h=i"),
        )
        val wire = wireUrlFor(request)
        assertTrue("space must be %20: $wire", wire.contains("%20"))
        assertTrue("plus must be %2B: $wire", wire.contains("%2B"))
        assertTrue("percent must be %25: $wire", wire.contains("%25"))
    }

    // ---------- headers ----------

    @Test
    fun request_exposedSnapshotsRefuseMutation() {
        val request = ServiceHttpRequest(
            ServiceHttpMethod.GET,
            "/v1/a",
            query = listOf("q" to "1", "q" to "2"),
            headers = mapOf("Authorization" to "Bearer x"),
        )
        val querySize = request.query.size
        try {
            @Suppress("UNCHECKED_CAST")
            (request.query as MutableList<Pair<String, String>>).add("evil" to "x")
            assertEquals(
                "exposed query snapshot must not grow the request",
                querySize,
                request.query.size,
            )
            assertFalse(request.query.any { it.first == "evil" })
        } catch (_: UnsupportedOperationException) {
        } catch (_: ClassCastException) {
        }
        val headerSize = request.headers.size
        try {
            @Suppress("UNCHECKED_CAST")
            (request.headers as MutableMap<String, String>)["Evil"] = "x"
            assertEquals(headerSize, request.headers.size)
            assertFalse(request.headers.containsKey("Evil"))
        } catch (_: UnsupportedOperationException) {
        } catch (_: ClassCastException) {
        }
    }

    @Test
    fun request_acceptsAllowedHeadersAndSnapshots() {
        val backing = mutableMapOf(
            "Authorization" to "Bearer opaque-123",
            "Idempotency-Key" to "b5827485-c9d6-49aa-9578-7a2f637cb8bc",
        )
        val request = ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", headers = backing)
        assertEquals(2, request.headers.size)
        backing["Late"] = "must-not-appear"
        assertEquals(2, request.headers.size)
        assertEquals("Bearer opaque-123", request.headers["Authorization"])
    }

    @Test
    fun request_acceptsCaseInsensitiveAllowedNames() {
        val request = ServiceHttpRequest(
            ServiceHttpMethod.GET,
            "/v1/a",
            headers = mapOf("authorization" to "Bearer x", "IDEMPOTENCY-KEY" to "k"),
        )
        assertEquals(2, request.headers.size)
    }

    @Test
    fun request_preservesInteriorSpacesExactly() {
        val request = ServiceHttpRequest(
            ServiceHttpMethod.GET,
            "/v1/a",
            headers = mapOf("Authorization" to "Bearer  a  b"),
        )
        assertEquals("Bearer  a  b", request.headers["Authorization"])
    }

    @Test
    fun request_rejectsBadHeaders() {
        // Unknown names.
        assertRequestInvalid {
            ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", headers = mapOf("Host" to "x"))
        }
        assertRequestInvalid {
            ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", headers = mapOf("Cookie" to "a=b"))
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Content-Length" to "5"),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Accept-Encoding" to "gzip"),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("X-Custom" to "x"),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", headers = mapOf("" to "x"))
        }
        // Injection controls, tabs, non-ASCII, DEL.
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Authorization" to "a\nb"),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Authorization" to "a\tb"),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Authorization" to "caf\u00E9"),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Authorization" to "a\u007Fb"),
            )
        }
        // Empty values and edge spaces.
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Authorization" to ""),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Authorization" to " lead"),
            )
        }
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf("Authorization" to "trail "),
            )
        }
        // Case-equivalent duplicates must be rejected at transport validation.
        assertRequestInvalid {
            ServiceHttpRequest(
                ServiceHttpMethod.GET,
                "/v1/a",
                headers = mapOf(
                    "Authorization" to "a",
                    "AUTHORIZATION" to "b",
                ),
            )
        }
    }

    // ---------- body rules ----------

    @Test
    fun request_getRequiresNullBody_postRequiresNonNull() {
        ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", body = null)
        assertRequestInvalid {
            ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", body = "{}")
        }
        assertRequestInvalid {
            ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/a", body = "")
        }
        ServiceHttpRequest(ServiceHttpMethod.POST, "/v1/a", body = "{}")
        ServiceHttpRequest(ServiceHttpMethod.POST, "/v1/a", body = "")
        assertRequestInvalid {
            ServiceHttpRequest(ServiceHttpMethod.POST, "/v1/a", body = null)
        }
    }

    @Test
    fun request_rejectsUnpairedSurrogateBody() {
        assertRequestInvalid {
            ServiceHttpRequest(ServiceHttpMethod.POST, "/v1/a", body = "bad\uD800")
        }
    }

    // ---------- timeouts ----------

    @Test
    fun timeouts_defaults() {
        val t = ServiceHttpTimeouts()
        assertEquals(10_000L, t.connectMillis)
        assertEquals(30_000L, t.readMillis)
        assertEquals(30_000L, t.writeMillis)
        assertEquals(60_000L, t.callMillis)
    }

    @Test
    fun timeouts_acceptBoundaries() {
        ServiceHttpTimeouts(1L, 1L, 1L, 1L)
        ServiceHttpTimeouts(
            Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong(),
        )
    }

    @Test
    fun timeouts_rejectZeroNegativeOverflow() {
        val bad = listOf(0L, -1L, Long.MIN_VALUE, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)
        for (v in bad) {
            try {
                ServiceHttpTimeouts(v, 30_000L, 30_000L, 60_000L)
                fail("connect $v must fail")
            } catch (e: SyncModelException) { /* expected */ }
            try {
                ServiceHttpTimeouts(10_000L, v, 30_000L, 60_000L)
                fail("read $v must fail")
            } catch (e: SyncModelException) { /* expected */ }
            try {
                ServiceHttpTimeouts(10_000L, 30_000L, v, 60_000L)
                fail("write $v must fail")
            } catch (e: SyncModelException) { /* expected */ }
            try {
                ServiceHttpTimeouts(10_000L, 30_000L, 30_000L, v)
                fail("call $v must fail")
            } catch (e: SyncModelException) { /* expected */ }
        }
    }

    @Test
    fun timeouts_copyValidates() {
        val t = ServiceHttpTimeouts()
        try {
            t.copy(connectMillis = 0L)
            fail("copy connect zero must fail")
        } catch (e: SyncModelException) { /* expected */ }
        try {
            t.copy(readMillis = 0L)
            fail("copy read zero must fail")
        } catch (e: SyncModelException) { /* expected */ }
        try {
            t.copy(writeMillis = -1L)
            fail("copy write negative must fail")
        } catch (e: SyncModelException) { /* expected */ }
        try {
            t.copy(callMillis = -5L)
            fail("copy call negative must fail")
        } catch (e: SyncModelException) { /* expected */ }
        try {
            t.copy(readMillis = Int.MAX_VALUE.toLong() + 1)
            fail("copy read overflow must fail")
        } catch (e: SyncModelException) { /* expected */ }
    }

    // ---------- toString redaction ----------

    @Test
    fun toString_neverLeaksSensitiveValues() {
        val origin = ServiceOrigin.parse("https://example.com:8443")
        val originText = origin.toString()
        assertFalse(originText.contains("example.com"))
        assertFalse(originText.contains("8443"))
        val request = ServiceHttpRequest(
            ServiceHttpMethod.POST,
            "/v1/secret-path",
            query = listOf("token" to "opaque-secret"),
            headers = mapOf("Authorization" to "Bearer secret-token"),
            body = "{\"secret\":\"value\"}",
        )
        val requestText = request.toString()
        assertFalse(requestText.contains("secret-path"))
        assertFalse(requestText.contains("opaque-secret"))
        assertFalse(requestText.contains("secret-token"))
        assertFalse(requestText.contains("secret"))
        val timeoutsText = ServiceHttpTimeouts().toString()
        assertTrue(timeoutsText.contains("10000") || timeoutsText.contains("connect"))
    }

    // ---------- retryAfterMillis ----------

    @Test
    fun retryAfter_convertsWholeSeconds() {
        assertEquals(1_000L, retryAfterMillis(mapOf("Retry-After" to listOf("1"))))
        assertEquals(299_000L, retryAfterMillis(mapOf("Retry-After" to listOf("299"))))
        assertEquals(300_000L, retryAfterMillis(mapOf("Retry-After" to listOf("300"))))
        assertEquals(300_000L, retryAfterMillis(mapOf("Retry-After" to listOf("301"))))
        assertEquals(1_000L, retryAfterMillis(mapOf("Retry-After" to listOf("001"))))
    }

    @Test
    fun retryAfter_saturatesOverflowBeforeMultiplication() {
        // Any length allowed; validate ALL digits and saturate to 5 minutes.
        assertEquals(300_000L, retryAfterMillis(mapOf("Retry-After" to listOf("99999999999999999999"))))
        assertEquals(300_000L, retryAfterMillis(mapOf("Retry-After" to listOf("1000000"))))
        // Long overflow must not wrap or throw.
        assertEquals(
            300_000L,
            retryAfterMillis(mapOf("Retry-After" to listOf("92233720368547758079223372036854775807"))),
        )
    }

    @Test
    fun retryAfter_lookupIsCaseInsensitiveAndSingleValued() {
        assertEquals(2_000L, retryAfterMillis(mapOf("retry-after" to listOf("2"))))
        assertEquals(2_000L, retryAfterMillis(mapOf("RETRY-AFTER" to listOf("2"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("2", "2"))))
        assertNull(
            retryAfterMillis(
                mapOf(
                    "Retry-After" to listOf("2"),
                    "retry-after" to listOf("2"),
                ),
            ),
        )
    }

    @Test
    fun retryAfter_stripsOuterWhitespaceOnly() {
        assertEquals(5_000L, retryAfterMillis(mapOf("Retry-After" to listOf("  5  "))))
        assertEquals(5_000L, retryAfterMillis(mapOf("Retry-After" to listOf("\t5\t"))))
    }

    @Test
    fun retryAfter_rejectsMalformedAndNonPositive() {
        assertNull(retryAfterMillis(emptyMap()))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf(""))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("   "))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("0"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("000"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("-1"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("+5"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("1.0"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("1e3"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("12a"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("a12"))))
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf(" 1 2 "))))
        // Unicode digits rejected.
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("\u0661"))))
        // HTTP dates rejected.
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("Wed, 21 Oct 2015 07:28:00 GMT"))))
        // Controls rejected.
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("1\u0000"))))
        assertNull(retryAfterMillis(mapOf("Other" to listOf("5"))))
    }

    @Test
    fun retryAfter_hugeDigitsWithFarSuffixAreRejectedNotSaturated() {
        // ALL digits must be scanned: a non-digit far into the string invalidates
        // the whole hint instead of saturating a valid prefix.
        val hugeThenJunk = "9".repeat(5_000) + "x"
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf(hugeThenJunk))))
        val zerosThenJunk = "0".repeat(1_000) + ";"
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf(zerosThenJunk))))
        // Pure huge digits still saturate.
        assertEquals(
            300_000L,
            retryAfterMillis(mapOf("Retry-After" to listOf("9".repeat(5_000)))),
        )
    }

    @Test
    fun retryAfter_rejectsEmbeddedSpacesAndEmptyAcrossKeys() {
        assertNull(retryAfterMillis(mapOf("Retry-After" to listOf("1 0"))))
        assertNull(
            retryAfterMillis(
                mapOf(
                    "Retry-After" to listOf("5"),
                    "RETRY-AFTER" to listOf(""),
                ),
            ),
        )
    }

    @Test
    fun transportFailuresRetainSafeMetadata() {
        val transient = SyncApiException.Transient(retryAfterMillis = 1_000L)
        assertEquals("transient", transient.code)
        assertEquals(1_000L, transient.retryAfterMillis)
        assertNull(transient.cause)
        val malformed = SyncApiException.MalformedResponse()
        assertEquals("malformed_response", malformed.code)
        assertNull(malformed.cause)
    }
}
