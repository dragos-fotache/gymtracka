package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates

/**
 * R03a QA: concrete local-TLS network behaviour with real I/O.
 * Bounded latch/server waits only; no sleeps, no backend, no domain logic.
 */
class ServiceHttpNetworkTest {

    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private data class TlsFixture(
        val server: MockWebServer,
        val origin: ServiceOrigin,
        val trustedBuilder: OkHttpClient.Builder,
    )

    private fun startTlsServer(
        san: String = "localhost",
        commonName: String = "localhost",
    ): TlsFixture {
        val held = HeldCertificate.Builder()
            .commonName(commonName)
            .addSubjectAlternativeName(san)
            .build()
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()
        val webServer = MockWebServer()
        webServer.useHttps(serverCerts.sslSocketFactory())
        webServer.start()
        server = webServer
        val origin = ServiceOrigin.parse("https://localhost:${webServer.port}")
        val clientCerts = HandshakeCertificates.Builder()
            .addTrustedCertificate(held.certificate)
            .build()
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
        return TlsFixture(webServer, origin, builder)
    }

    private fun transport(
        origin: ServiceOrigin,
        timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
        builder: OkHttpClient.Builder = OkHttpClient.Builder(),
    ): OkHttpServiceTransport {
        return OkHttpServiceTransport(origin, newServiceHttpClient(timeouts, builder))
    }

    private fun jsonResponse(body: String, code: Int = 200): MockResponse {
        return MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()
    }

    @Test
    fun trustedCaSuccessWithHostnameVerification() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(jsonResponse("{\"ok\":true}"))
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        val body = t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/net-probe")) {
            it.readJsonBody()
        }
        assertEquals("{\"ok\":true}", body)
        val recorded = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertTrue(recorded.target.startsWith("/v1/net-probe"))
        assertEquals("application/json", recorded.headers["Accept"])
        assertEquals("identity", recorded.headers["Accept-Encoding"])
    }

    @Test
    fun defaultClientRejectsUntrustedLocalCa() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(jsonResponse("{\"ok\":true}"))
        val t = transport(fixture.origin) // platform trust only
        try {
            t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/net-probe")) {
                it.readJsonBody()
            }
            fail("untrusted CA must fail")
        } catch (_: SyncApiException.Transient) {
        }
    }

    @Test
    fun wrongHostnameRejectedDespiteTrustedCa() = runBlocking {
        val fixture = startTlsServer(san = "wrong.invalid", commonName = "wrong.invalid")
        fixture.server.enqueue(jsonResponse("{\"ok\":true}"))
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        try {
            t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/net-probe")) {
                it.readJsonBody()
            }
            fail("hostname mismatch must fail")
        } catch (_: SyncApiException.Transient) {
        }
    }

    @Test
    fun noRedirectForwardedToTarget() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/v1/redirect-target")
                .body("")
                .build(),
        )
        fixture.server.enqueue(jsonResponse("{\"must\":\"not-be-fetched\"}"))
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        val status = t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/net-start")) {
            it.statusCode
        }
        assertEquals(302, status)
        val first = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(first.target.startsWith("/v1/net-start"))
        assertEquals(1, fixture.server.requestCount)
        assertNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
    }

    @Test
    fun crossOriginPostRedirectSendsNothingToDestination() = runBlocking {
        val held = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()
        val source = MockWebServer()
        source.useHttps(serverCerts.sslSocketFactory())
        source.start()
        val destination = MockWebServer()
        destination.useHttps(serverCerts.sslSocketFactory())
        destination.start()
        server = source
        try {
            source.enqueue(
                MockResponse.Builder()
                    .code(307)
                    .addHeader(
                        "Location",
                        "https://localhost:${destination.port}/v1/stolen-target",
                    )
                    .body("")
                    .build(),
            )
            val clientCerts = HandshakeCertificates.Builder()
                .addTrustedCertificate(held.certificate)
                .build()
            val builder = OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            val origin = ServiceOrigin.parse("https://localhost:${source.port}")
            val t = transport(origin, builder = builder)
            val secretBody = "{\"secret\":\"credential-body-\uD83D\uDE00\"}"
            val request = ServiceHttpRequest(
                ServiceHttpMethod.POST,
                "/v1/credential-post",
                headers = mapOf("Authorization" to "Bearer cross-origin-secret-token"),
                body = secretBody,
            )
            val status = t.withResponse(request) { it.statusCode }
            assertEquals(307, status)
            // Source retains the EXACT credential-bearing request, exactly once.
            val seen = source.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(seen.target.startsWith("/v1/credential-post"))
            assertEquals("Bearer cross-origin-secret-token", seen.headers["Authorization"])
            assertEquals(secretBody, seen.body!!.utf8())
            assertEquals(1, source.requestCount)
            assertNull(source.takeRequest(2, TimeUnit.SECONDS))
            // Destination receives NOTHING: no credential/body movement across origins.
            assertEquals(0, destination.requestCount)
            assertNull(destination.takeRequest(2, TimeUnit.SECONDS))
        } finally {
            try {
                destination.close()
            } catch (_: Exception) {
            }
            try {
                source.close()
            } catch (_: Exception) {
            }
            server = null
        }
    }

    @Test
    fun noReplayOn503WithRetryAfterZero() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "0")
                .body("{\"errorCode\":\"temporarily_unavailable\"}")
                .build(),
        )
        fixture.server.enqueue(jsonResponse("{\"must\":\"not-be-fetched\"}"))
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        try {
            t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/net-probe")) {
                it.readJsonBody()
            }
        } catch (_: SyncApiException.Transient) {
        } catch (_: SyncApiException.MalformedResponse) {
        }
        // Exactly one on-wire exchange: no automatic replay/follow-up.
        val first = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(first.target.startsWith("/v1/net-probe"))
        assertEquals(1, fixture.server.requestCount)
        assertNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
    }

    @Test
    fun noReplayOn408() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(408)
                .addHeader("Content-Type", "application/json")
                .body("{\"errorCode\":\"internal_error\"}")
                .build(),
        )
        fixture.server.enqueue(jsonResponse("{\"must\":\"not-be-fetched\"}"))
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        try {
            t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/net-probe")) {
                it.readJsonBody()
            }
        } catch (_: SyncApiException.Transient) {
        } catch (_: SyncApiException.MalformedResponse) {
        }
        val seen408 = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(seen408.target.startsWith("/v1/net-probe"))
        assertEquals(1, fixture.server.requestCount)
        assertNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
    }

    @Test
    fun noCookieJarOrCacheAcrossExplicitCalls() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Set-Cookie", "session=abc; Path=/")
                .body("{\"first\":1}")
                .build(),
        )
        fixture.server.enqueue(jsonResponse("{\"second\":2}"))
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/first")) {
            it.readJsonBody()
        }
        t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/second")) {
            it.readJsonBody()
        }
        val first = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
        val second = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(first.target.startsWith("/v1/first"))
        assertTrue(second.target.startsWith("/v1/second"))
        assertNull(second.headers["Cookie"])
    }

    @Test
    fun policyFlagsAreLockedDown() {
        // Externally observable client policy only: the spec mandates guard
        // interceptors, so list emptiness must NOT be asserted here.
        val client = newServiceHttpClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.cookieJar === CookieJar.NO_COOKIES)
        assertNull(client.cache)
        assertNull(client.proxy)
        // Default timeout policy is observable through public getters.
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(60_000, client.callTimeoutMillis)
    }

    @Test
    fun exactUtf8BytesAndQueryEncodingOnWire() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(jsonResponse("{\"ok\":true}"))
        val bodyText = "{\"note\":\"caf\u00E9 \uD83D\uDE00 \u2014\"}"
        val request = ServiceHttpRequest(
            ServiceHttpMethod.POST,
            "/v1/net-echo",
            query = listOf("q" to "a+b c%d", "nul" to "x\u0000y"),
            headers = mapOf("Idempotency-Key" to "b5827485-c9d6-49aa-9578-7a2f637cb8bc"),
            body = bodyText,
        )
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        t.withResponse(request) { it.readJsonBody() }
        val recorded = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
        // Query encoding: %20, %2B, %25, %00 present; no raw spaces/NUL.
        assertTrue(recorded.target.contains("%20"))
        assertTrue(recorded.target.contains("%2B"))
        assertTrue(recorded.target.contains("%25"))
        assertTrue(recorded.target.contains("%00"))
        // Body exact UTF-8 bytes.
        val wireBody = recorded.body!!.utf8()
        assertEquals(bodyText, wireBody)
        assertEquals(
            bodyText.toByteArray(Charsets.UTF_8).toList(),
            recorded.body!!.toByteArray().toList(),
        )
        // POST content type owned by transport.
        assertEquals(
            "application/json; charset=utf-8",
            recorded.headers["Content-Type"],
        )
    }

    @Test
    fun concreteReadTimeoutSurfacesTransient() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .body("{\"slow\":true}")
                .bodyDelay(3, TimeUnit.SECONDS)
                .build(),
        )
        val timeouts = ServiceHttpTimeouts(
            connectMillis = 10_000L,
            readMillis = 500L,
            writeMillis = 10_000L,
            callMillis = 30_000L,
        )
        val t = transport(fixture.origin, timeouts = timeouts, builder = fixture.trustedBuilder)
        try {
            t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/slow")) {
                it.readJsonBody()
            }
            fail("read timeout must surface Transient")
        } catch (_: SyncApiException.Transient) {
        }
    }

    @Test
    fun blockedBodyReadCancellationPreservesCancellation() = runBlocking {
        val fixture = startTlsServer()
        val big = buildString {
            append("{\"data\":\"")
            repeat(500_000) { append('z') }
            append("\"}")
        }
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .body(big)
                .throttleBody(8_192, 500, TimeUnit.MILLISECONDS)
                .build(),
        )
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        val firstRead = CompletableDeferred<Unit>()
        val job = launch {
            t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/big")) { response ->
                // Actually block inside incremental reads; signal after real bytes flow.
                val buf = ByteArray(8_192)
                while (true) {
                    val n = response.readBodyChunk(buf)
                    if (!firstRead.isCompleted) firstRead.complete(Unit)
                    if (n == -1) break
                }
            }
        }
        // Bounded: a faulty implementation that never returns bytes fails here, not hangs.
        withTimeout(15_000L) { firstRead.await() }
        withTimeout(15_000L) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun largeCompleteBodyHasNoTruncation() = runBlocking {
        val fixture = startTlsServer()
        val big = buildString {
            append("{\"data\":\"")
            repeat(200_000) { append('y') }
            append("\"}")
        }
        fixture.server.enqueue(jsonResponse(big))
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        val out = t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/big")) {
            it.readJsonBody()
        }
        assertEquals(big, out)
    }

    @Test
    fun oneSendGuardIsPerCallForSharedRequest() = runBlocking {
        val fixture = startTlsServer()
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "0")
                .body("{\"errorCode\":\"temporarily_unavailable\"}")
                .build(),
        )
        fixture.server.enqueue(jsonResponse("{\"second\":2}"))
        fixture.server.enqueue(jsonResponse("{\"third\":3}"))
        val client = newServiceHttpClient(builder = fixture.trustedBuilder)
        val shared = Request.Builder()
            .url("https://localhost:${fixture.server.port}/v1/shared-request")
            .header("Accept", "application/json")
            .build()
        // Same okhttp Request instance reused across fresh Calls: each Call sends once
        // (guard is per Call, not per reusable request) and none auto-retries.
        // A refused internal follow-up on the first 503 may surface as an IOException
        // here (the safe Transient mapping lives in the transport layer); either way
        // exactly one wire send must have occurred.
        val firstStatus = withContext(Dispatchers.IO) {
            try {
                client.newCall(shared).execute().use { it.code }
            } catch (_: IOException) {
                -1
            }
        }
        assertTrue(firstStatus == 503 || firstStatus == -1)
        val seen = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(seen.target.startsWith("/v1/shared-request"))
        assertEquals(1, fixture.server.requestCount)
        val secondBody = withContext(Dispatchers.IO) {
            client.newCall(shared).execute().use {
                assertEquals(200, it.code)
                it.body.string()
            }
        }
        assertEquals("{\"second\":2}", secondBody)
        // Explicit repeat of the same Request instance sends independently.
        val thirdBody = withContext(Dispatchers.IO) {
            client.newCall(shared).execute().use {
                assertEquals(200, it.code)
                it.body.string()
            }
        }
        assertEquals("{\"third\":3}", thirdBody)
        assertEquals(3, fixture.server.requestCount)
    }

    @Test
    fun midBodyDisconnectNeverYieldsPartialSuccess() = runBlocking {
        val fixture = startTlsServer()
        val big = buildString {
            append("{\"data\":\"")
            repeat(30_000) { append('d') }
            append("\"}")
        }
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .body(big)
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )
        val t = transport(fixture.origin, builder = fixture.trustedBuilder)
        try {
            t.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/cut")) {
                it.readJsonBody()
            }
            fail("cut connection must not succeed")
        } catch (e: Exception) {
            assertTrue(
                e is SyncApiException.Transient || e is SyncApiException.MalformedResponse,
            )
        }
    }
}
