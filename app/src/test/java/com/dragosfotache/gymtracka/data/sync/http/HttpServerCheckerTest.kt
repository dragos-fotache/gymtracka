package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.ServerCheckResult
import java.util.concurrent.TimeUnit
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpServerCheckerTest {
    private class Fixture : AutoCloseable {
        private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        private val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply {
            useHttps(serverTrust.sslSocketFactory())
            start()
        }
        val origin = ServiceOrigin.parse(server.url("/").toString())
        private val clients = mutableListOf<OkHttpClient>()

        fun client(timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts()): OkHttpClient =
            newServiceHttpClient(
                timeouts,
                OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager),
            ).also { clients += it }

        fun checker(): HttpServerChecker = HttpServerChecker(OkHttpServiceTransport(origin, client()))

        override fun close() {
            clients.forEach {
                it.dispatcher.cancelAll()
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
            server.close()
        }
    }

    private fun response(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body)
        .build()

    @Test
    fun expected401IsConnectedAndRequestHasNoCredentialAccountQueryOrBody() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(response(401, """{"errorCode":"authentication_required","message":"Authentication required"}"""))
            assertEquals(ServerCheckResult.CONNECTED, f.checker().check())
            val request = checkNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/v1/sync/pull", request.url.encodedPath)
            assertNull(request.url.encodedQuery)
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Idempotency-Key"])
            assertNull(request.headers["Cookie"])
            assertEquals(0L, request.body?.size ?: 0L)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test
    fun unexpectedStatusesAndAuthenticationErrorsNeverGrantConnectivityOrRetry() = runBlocking {
        Fixture().use { f ->
            val checker = f.checker()
            val cases = listOf(
                Triple(200, """{"errorCode":"authentication_required"}""", ServerCheckResult.UNEXPECTED_RESPONSE),
                Triple(401, """{"errorCode":"reauthentication_required"}""", ServerCheckResult.UNEXPECTED_RESPONSE),
                Triple(401, """{"errorCode":"different_service"}""", ServerCheckResult.UNEXPECTED_RESPONSE),
                Triple(403, """{"errorCode":"authentication_required"}""", ServerCheckResult.UNEXPECTED_RESPONSE),
                Triple(404, "{}", ServerCheckResult.UNEXPECTED_RESPONSE),
                Triple(429, "{}", ServerCheckResult.UNEXPECTED_RESPONSE),
                Triple(500, "not json", ServerCheckResult.SERVER_ERROR),
                Triple(501, "{}", ServerCheckResult.SERVER_ERROR),
                Triple(503, "{}", ServerCheckResult.SERVER_ERROR),
            )
            for ((code, body, expected) in cases) {
                f.server.enqueue(response(code, body))
                assertEquals("HTTP $code", expected, checker.check())
            }
            assertEquals(cases.size, f.server.requestCount)
        }
    }

    @Test
    fun malformedEnvelopesMediaUtf8AndTruncationCannotConfirmService() = runBlocking {
        Fixture().use { f ->
            val checker = f.checker()
            val bodies = listOf(
                "not json",
                """{"errorCode":"authentication_required","errorCode":"authentication_required"}""",
                """{"errorCode":true}""",
                """{"errorCode":"authentication_required","message":42}""",
                """{"errorCode":"authentication_required"} trailing""",
            )
            for (body in bodies) {
                f.server.enqueue(response(401, body))
                assertEquals(ServerCheckResult.UNEXPECTED_RESPONSE, checker.check())
            }
            val invalidResponses = listOf(
                MockResponse.Builder().code(401).addHeader("Content-Type", "text/plain")
                    .body("""{"errorCode":"authentication_required"}""").build(),
                MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                    .body(Buffer().write(byteArrayOf(0xff.toByte()))).build(),
                MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                    .body("{").setHeader("Content-Length", "999")
                    .onResponseEnd(SocketEffect.CloseSocket()).build(),
                MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                    .addHeader("Content-Encoding", "gzip")
                    .body("""{"errorCode":"authentication_required"}""").build(),
            )
            invalidResponses.forEachIndexed { index, invalid ->
                Fixture().use { isolated ->
                    isolated.server.enqueue(invalid)
                    assertEquals("invalid response $index", ServerCheckResult.UNEXPECTED_RESPONSE, isolated.checker().check())
                    assertEquals(1, isolated.server.requestCount)
                }
            }
        }
    }

    @Test
    fun redirectsAreNotFollowedAndDoNotContactAnotherOrigin() = runBlocking {
        Fixture().use { source ->
            Fixture().use { destination ->
                source.server.enqueue(
                    MockResponse.Builder().code(302)
                        .addHeader("Location", destination.server.url("/v1/sync/pull"))
                        .body("").build(),
                )
                assertEquals(ServerCheckResult.UNEXPECTED_RESPONSE, source.checker().check())
                assertEquals(1, source.server.requestCount)
                assertEquals(0, destination.server.requestCount)
            }
        }
    }

    @Test
    fun untrustedTlsAndWrongHostnameRemainUnreachable() = runBlocking {
        Fixture().use { f ->
            assertEquals(ServerCheckResult.UNREACHABLE, HttpServerChecker(f.origin).check())
            val wrongHostname = ServiceOrigin.parse("https://127.0.0.1:${f.server.port}")
            val checker = HttpServerChecker(OkHttpServiceTransport(wrongHostname, f.client()))
            assertEquals(ServerCheckResult.UNREACHABLE, checker.check())
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test
    fun callerCancellationCancelsTheActualCallWithoutResultOrRetry() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(
                MockResponse.Builder().code(401)
                    .headersDelay(1, TimeUnit.DAYS)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"errorCode":"authentication_required"}""").build(),
            )
            val captured = CompletableDeferred<Call>()
            val client = f.client()
            val factory = object : Call.Factory {
                override fun newCall(request: Request): Call = client.newCall(request).also { captured.complete(it) }
            }
            val checker = HttpServerChecker(OkHttpServiceTransport(f.origin, factory))
            val job = async { checker.check() }
            val call = withTimeout(5_000) { captured.await() }
            withContext(Dispatchers.IO) { checkNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(call.isCanceled())
            assertEquals(1, f.server.requestCount)
        }
    }
}
