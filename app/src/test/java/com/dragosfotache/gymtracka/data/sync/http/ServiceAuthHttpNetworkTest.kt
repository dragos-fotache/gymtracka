package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// tests network: trusted local TLS over production adapter + actual transport.
// Expected values are independent literal JSON strings.

private const val NET_CHALLENGE_ID = "11111111-1111-1111-1111-111111111111"
private const val NET_ACCOUNT_ID = "22222222-2222-2222-2222-222222222222"
private const val NET_VALID_CHALLENGE =
    "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":\"opaque nonce\",\"expiresAt\":\"2026-10-03T12:05:00Z\"}"
private const val NET_VALID_GRANT =
    "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null}," +
        "\"accessToken\":\"opaque access\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\"," +
        "\"refreshToken\":\"opaque refresh\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}"

private const val NET_ARBITRARY_EXCHANGE_RAW =
    "  {\n  \"idToken\" : \"a\\u00e9b\\nline\" ,\n  \"challengeId\" : \"11111111-1111-1111-1111-111111111111\"\n}\n"
private const val NET_ARBITRARY_REFRESH_RAW =
    " { \"refreshToken\" : \"r\\u00e9f-\uD83C\uDF89\" } "

private class TlsFixture {
    val localhost: HeldCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .build()
    val serverCerts = okhttp3.tls.HandshakeCertificates.Builder()
        .heldCertificate(localhost)
        .build()
    val clientCerts = okhttp3.tls.HandshakeCertificates.Builder()
        .addTrustedCertificate(localhost.certificate)
        .build()
    val server = MockWebServer()
    private val clients = mutableListOf<OkHttpClient>()

    fun start(): MockWebServer {
        server.useHttps(serverCerts.sslSocketFactory())
        server.start()
        return server
    }

    fun origin(): ServiceOrigin {
        val port = server.url("/").port
        return ServiceOrigin.parse("https://localhost:$port")
    }

    fun trustedClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
        val client = newServiceHttpClient(builder = builder)
        clients.add(client)
        return client
    }

    fun transport(origin: ServiceOrigin): OkHttpServiceTransport {
        return OkHttpServiceTransport(origin, trustedClient())
    }

    fun api(): HttpServiceAuthApi {
        val o = origin()
        return HttpServiceAuthApi(o, transport(o))
    }

    fun stop() {
        for (client in clients) {
            try {
                client.dispatcher.cancelAll()
            } catch (_: Exception) {
            }
            try {
                client.connectionPool.evictAll()
            } catch (_: Exception) {
            }
            try {
                client.dispatcher.executorService.shutdown()
            } catch (_: Exception) {
            }
        }
        clients.clear()
        try {
            server.close()
        } catch (_: Exception) {
        }
    }
}

private fun jsonOk(body: String): MockResponse =
    MockResponse.Builder()
        .code(200)
        .body(body)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .build()

private fun errorOk(status: Int, body: String): MockResponse =
    MockResponse.Builder()
        .code(status)
        .body(body)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .build()

class ServiceAuthHttpNetworkTest {

    @Test fun challengeTlsExactOneKeyBodies() = runBlocking {
        val expected = mapOf(
            GoogleChallengePurpose.SIGN_IN to "{\"purpose\":\"signIn\"}",
            GoogleChallengePurpose.EXPORT to "{\"purpose\":\"export\"}",
            GoogleChallengePurpose.DELETE_ACCOUNT to "{\"purpose\":\"deleteAccount\"}",
            GoogleChallengePurpose.ACCOUNT_STATUS to "{\"purpose\":\"accountStatus\"}",
        )
        for ((purpose, exact) in expected) {
            val f = TlsFixture()
            f.start()
            try {
                f.server.enqueue(jsonOk(NET_VALID_CHALLENGE))
                val c = f.api().challenge(purpose)
                assertEquals(NET_CHALLENGE_ID, c.challengeId)
                val recorded = f.server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("POST", recorded.method)
                assertEquals("/v1/auth/google/challenge", recorded.url.encodedPath)
                assertNull(recorded.url.encodedQuery)
                assertNull(recorded.headers["Authorization"])
                assertNull(recorded.headers["Idempotency-Key"])
                assertEquals(exact, recorded.body?.utf8())
                assertEquals(1, f.server.requestCount)
            } finally {
                f.stop()
            }
        }
    }

    @Test fun exchangeTlsArbitraryRestoredBytesAndKey() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(jsonOk(NET_VALID_GRANT))
            val attempt = GoogleExchangeAttempt.fromJson(
                "33333333-3333-3333-3333-333333333333",
                NET_ARBITRARY_EXCHANGE_RAW,
            )
            val s = f.api().exchange(attempt)
            assertEquals(NET_ACCOUNT_ID, s.account.accountId)
            val recorded = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/auth/google", recorded.url.encodedPath)
            assertEquals("33333333-3333-3333-3333-333333333333", recorded.headers["Idempotency-Key"])
            assertNull(recorded.headers["Authorization"])
            assertEquals(NET_ARBITRARY_EXCHANGE_RAW, recorded.body?.utf8())
            assertTrue(recorded.body?.utf8()?.contains("\\u00e9") == true)
            assertEquals(1, f.server.requestCount)
        } finally {
            f.stop()
        }
    }

    @Test fun refreshTlsArbitraryRestoredBytesAndKey() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(jsonOk(NET_VALID_GRANT))
            val attempt = ServiceRefreshAttempt.fromJson(
                "44444444-4444-4444-4444-444444444444",
                NET_ARBITRARY_REFRESH_RAW,
            )
            f.api().refresh(attempt)
            val recorded = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/auth/refresh", recorded.url.encodedPath)
            assertEquals("44444444-4444-4444-4444-444444444444", recorded.headers["Idempotency-Key"])
            assertEquals(NET_ARBITRARY_REFRESH_RAW, recorded.body?.utf8())
        } finally {
            f.stop()
        }
    }

    @Test fun logoutTlsEmpty204Succeeds() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(MockResponse.Builder().code(204).clearHeaders().build())
            f.api().logout(OpaqueToken("opaque refresh"))
            val recorded = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/auth/logout", recorded.url.encodedPath)
            assertNull(recorded.headers["Idempotency-Key"])
            assertEquals("{\"refreshToken\":\"opaque refresh\"}", recorded.body?.utf8())
        } finally {
            f.stop()
        }
    }

    @Test fun logoutTlsWithByteIsMalformed() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(MockResponse.Builder().code(204).clearHeaders().body("x").build())
            try {
                f.api().logout(OpaqueToken("r"))
                fail("expected MalformedResponse")
            } catch (e: SyncApiException.MalformedResponse) {
                assertNull(e.cause)
            }
        } finally {
            f.stop()
        }
    }

    @Test fun tlsNoRedirectOnFailure() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .clearHeaders()
                    .addHeader("Location", "/v1/auth/google/challenge")
                    .body("")
                    .build(),
            )
            try {
                f.api().challenge()
                fail("expected MalformedResponse for redirect")
            } catch (e: SyncApiException.MalformedResponse) {
                assertNull(e.cause)
            }
            assertEquals(1, f.server.requestCount)
        } finally {
            f.stop()
        }
    }

    @Test fun tlsNoRetryOnTransient() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .body("{}")
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("Retry-After", "0")
                    .build(),
            )
            try {
                f.api().challenge()
                fail("expected Transient")
            } catch (e: SyncApiException.Transient) {
                assertNull(e.cause)
            }
            assertEquals(1, f.server.requestCount)
        } finally {
            f.stop()
        }
    }

    @Test fun tlsStrictMediaIsMalformed() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .clearHeaders()
                    .addHeader("Content-Type", "text/plain")
                    .body(NET_VALID_CHALLENGE)
                    .build(),
            )
            try {
                f.api().challenge()
                fail("expected MalformedResponse")
            } catch (e: SyncApiException.MalformedResponse) {
                assertNull(e.cause)
            }
        } finally {
            f.stop()
        }
    }

    @Test fun tlsTruncationIsMalformed() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            // Declare a long body, send only a short prefix, then orderly peer close:
            // genuine premature framed EOF, not an abrupt pre-body socket failure.
            val shortPrefix = NET_VALID_CHALLENGE.substring(0, 10)
            f.server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(shortPrefix)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .setHeader("Content-Length", "99999")
                    .onResponseEnd(SocketEffect.CloseSocket())
                    .build(),
            )
            try {
                f.api().challenge()
                fail("expected MalformedResponse")
            } catch (e: SyncApiException.MalformedResponse) {
                assertNull(e.cause)
            }
        } finally {
            f.stop()
        }
    }

    @Test fun tlsRawUtf8IsMalformed() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            val raw = Buffer().write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            f.server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .clearHeaders()
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .body(raw)
                    .build(),
            )
            try {
                f.api().challenge()
                fail("expected MalformedResponse")
            } catch (e: SyncApiException.MalformedResponse) {
                assertNull(e.cause)
            }
        } finally {
            f.stop()
        }
    }

    private class BlockedScenario(
        val name: String,
        val status: Int,
        val body: String,
        val path: String,
        val invoke: suspend (HttpServiceAuthApi) -> Unit,
    )

    @Test fun tlsBlockedBodyCancellationPreservesIdentity() = runBlocking {
        val scenarios = listOf(
            BlockedScenario("challenge200", 200, NET_VALID_CHALLENGE, "/v1/auth/google/challenge") { api ->
                api.challenge()
            },
            BlockedScenario("exchange200", 200, NET_VALID_GRANT, "/v1/auth/google") { api ->
                api.exchange(
                    GoogleExchangeAttempt.fromJson(
                        "33333333-3333-3333-3333-333333333333",
                        NET_ARBITRARY_EXCHANGE_RAW,
                    ),
                )
            },
            BlockedScenario("refresh200", 200, NET_VALID_GRANT, "/v1/auth/refresh") { api ->
                api.refresh(
                    ServiceRefreshAttempt.fromJson(
                        "44444444-4444-4444-4444-444444444444",
                        NET_ARBITRARY_REFRESH_RAW,
                    ),
                )
            },
            BlockedScenario(
                "challenge400",
                400,
                "{\"errorCode\":\"future_code\",\"message\":\"safe\"}",
                "/v1/auth/google/challenge",
            ) { api -> api.challenge() },
            BlockedScenario(
                "exchange401",
                401,
                "{\"errorCode\":\"invalid_google_credential\",\"message\":\"bad\"}",
                "/v1/auth/google",
            ) { api ->
                api.exchange(
                    GoogleExchangeAttempt.fromJson(
                        "33333333-3333-3333-3333-333333333333",
                        NET_ARBITRARY_EXCHANGE_RAW,
                    ),
                )
            },
        )
        for (scenario in scenarios) {
            val f = TlsFixture()
            f.start()
            try {
                f.server.enqueue(
                    MockResponse.Builder()
                        .code(scenario.status)
                        .body(scenario.body)
                        .addHeader("Content-Type", "application/json; charset=utf-8")
                        .bodyDelay(5, TimeUnit.SECONDS)
                        .build(),
                )
                // Fixture-owned trusted client; f.stop() always owns cleanup, even on failure.
                val origin = f.origin()
                val trusted = f.trustedClient()
                val readEntered = CompletableDeferred<Unit>()
                val gatingFactory = object : Call.Factory {
                    override fun newCall(request: Request): Call {
                        val real = trusted.newCall(request)
                        return object : Call by real {
                            override fun enqueue(callback: Callback) {
                                real.enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: IOException) {
                                        callback.onFailure(call, e)
                                    }
                                    override fun onResponse(call: Call, response: Response) {
                                        val original = response.body
                                        val cached: BufferedSource = object :
                                            ForwardingSource(original.source()) {
                                            override fun read(sink: Buffer, byteCount: Long): Long {
                                                if (!readEntered.isCompleted) {
                                                    readEntered.complete(Unit)
                                                }
                                                return super.read(sink, byteCount)
                                            }
                                        }.buffer()
                                        val wrapped = object : ResponseBody() {
                                            override fun contentType() = original.contentType()
                                            override fun contentLength() = original.contentLength()
                                            override fun source(): BufferedSource = cached
                                        }
                                        callback.onResponse(call, response.newBuilder().body(wrapped).build())
                                    }
                                })
                            }
                        }
                    }
                }
                val api = HttpServiceAuthApi(origin, OkHttpServiceTransport(origin, gatingFactory))
                val supplied = CancellationException("tls-blocked-body")
                // Observe the CE object INSIDE the child (API boundary), not via async.await recovery.
                val observed = CompletableDeferred<CancellationException>()
                // Offload the call so the event loop stays free for the bounded gate; no sleeps.
                val deferred = async(Dispatchers.IO) {
                    try {
                        scenario.invoke(api)
                        fail("scenario ${scenario.name}: expected CancellationException")
                    } catch (e: CancellationException) {
                        if (!observed.isCompleted) {
                            observed.complete(e)
                        }
                        throw e
                    }
                }
                // Await actual body-read entry (proves more than send), then cancel with supplied identity.
                withTimeout(10000L) { readEntered.await() }
                deferred.cancel(supplied)
                deferred.cancelAndJoin()
                val caught = withTimeout(10000L) { observed.await() }
                assertSame("scenario ${scenario.name}", supplied, caught)
                val recorded = withContext(Dispatchers.IO) { f.server.takeRequest(5, TimeUnit.SECONDS) }!!
                assertEquals("scenario ${scenario.name}", scenario.path, recorded.url.encodedPath)
                assertEquals("scenario ${scenario.name}", 1, f.server.requestCount)
            } finally {
                f.stop()
            }
        }
    }

    @Test fun tlsExchangeInvalidCredentialIsPermanentRejection() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(errorOk(401, "{\"errorCode\":\"invalid_google_credential\",\"message\":\"bad\"}"))
            try {
                f.api().exchange(
                    GoogleExchangeAttempt.fromJson(
                        "33333333-3333-3333-3333-333333333333",
                        NET_ARBITRARY_EXCHANGE_RAW,
                    ),
                )
                fail("expected RequestRejected")
            } catch (e: SyncApiException.RequestRejected) {
                assertEquals("invalid_google_credential", e.errorCode)
            }
        } finally {
            f.stop()
        }
    }

    @Test fun tlsRefresh401IsReauth() = runBlocking {
        val f = TlsFixture()
        f.start()
        try {
            f.server.enqueue(errorOk(401, "{\"errorCode\":\"anything\",\"message\":\"x\"}"))
            try {
                f.api().refresh(
                    ServiceRefreshAttempt.fromJson(
                        "44444444-4444-4444-4444-444444444444",
                        NET_ARBITRARY_REFRESH_RAW,
                    ),
                )
                fail("expected ReauthenticationRequired")
            } catch (e: SyncApiException.ReauthenticationRequired) {
                assertNull(e.cause)
            }
        } finally {
            f.stop()
        }
    }
}
