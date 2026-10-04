package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R03b QA: real local TLS endpoint tests.
 *
 * TEST-owned trusted TLS only: production adapter + actual OkHttpServiceTransport
 * send HTTPS to a locally trusted MockWebServer origin. No isolated backend,
 * no sleeps, bounded server waits only.
 */
class SyncHttpEndpointNetworkTest {

    private val inner =
        """{"mutationId":"11111111-1111-1111-1111-111111111111","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1,"payload":{"workoutSetId":"33333333-3333-3333-3333-333333333333"}}"""
    private val root = "22222222-2222-2222-2222-222222222222"

    private fun durable(): com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel {
        val wrapper = """{"payloadVersion":1,"requestJson":${JSONObject.quote(inner)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"$root","localGeneration":1}]}"""
        return decodeDurableRequest(wrapper)
    }

    private fun batchJson(): String {
        val change = """{"aggregateType":"workout","aggregateId":"$root","operation":"upsert","revision":1,"record":{"id":"$root","name":"W","status":"active","startedAt":"2026-10-03T12:00:00Z","finishedAt":null,"revision":1,"exercises":[]}}"""
        return """{"batchId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","cursor":"opaque-cursor-1","changes":[$change]}"""
    }

    private class TlsFixture {
        val server = MockWebServer()
        lateinit var origin: ServiceOrigin
        lateinit var client: OkHttpClient

        fun start() {
            val held = okhttp3.tls.HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
            val serverCerts = okhttp3.tls.HandshakeCertificates.Builder()
                .heldCertificate(held)
                .build()
            server.useHttps(serverCerts.sslSocketFactory())
            server.start()
            val clientCerts = okhttp3.tls.HandshakeCertificates.Builder()
                .addTrustedCertificate(held.certificate)
                .build()
            val trustedBuilder = OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            client = newServiceHttpClient(builder = trustedBuilder)
            origin = ServiceOrigin.parse("https://localhost:${server.port}")
        }

        fun close() {
            try {
                server.close()
            } catch (_: Exception) {
            }
            try {
                client.dispatcher.executorService.shutdown()
            } catch (_: Exception) {
            }
        }

        fun api(accountId: String = "11111111-1111-1111-1111-111111111111", token: String = "net-token"): HttpGymSyncApi {
            val binding = ServiceSessionBinding(origin, accountId, 1L)
            val provider = BoundServiceAccessTokenProvider { expected ->
                BoundServiceAccessToken(binding, OpaqueToken(token))
            }
            val transport = OkHttpServiceTransport(origin, client)
            return HttpGymSyncApi(binding, provider, transport)
        }
    }

    private fun jsonResponse(code: Int, body: String): MockResponse {
        return MockResponse.Builder()
            .code(code)
            .body(body)
            .addHeader("Content-Type", "application/json")
            .build()
    }

    @Test
    fun `push over TLS sends exact route bytes and auth once`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            val batch = batchJson()
            tls.server.enqueue(jsonResponse(200, """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch}]}"""))
            val api = tls.api()
            val result = api.push(listOf(durable()))
            assertEquals(1, result.results.size)
            val recorded = tls.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", recorded.method)
            assertEquals("/v1/sync/push", recorded.url.encodedPath)
            assertEquals(null, recorded.url.queryParameter("cursor"))
            assertEquals("Bearer net-token", recorded.headers["Authorization"])
            assertEquals("""{"mutations":[$inner]}""", recorded.body!!.utf8())
            assertEquals(1, tls.server.requestCount)
        } finally {
            tls.close()
        }
    }

    @Test
    fun `pull over TLS preserves opaque query and empty tail boundary`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            tls.server.enqueue(jsonResponse(200, """{"batches":[],"nextCursor":"opaque-boundary","hasMore":false}"""))
            val api = tls.api()
            val page = api.pull(null, 100)
            assertEquals("opaque-boundary", page.nextCursor)
            val recorded = tls.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", recorded.method)
            assertEquals("/v1/sync/pull", recorded.url.encodedPath)
            assertEquals("100", recorded.url.queryParameter("limit"))
        } finally {
            tls.close()
        }
    }

    @Test
    fun `snapshot over TLS sends pageToken and limit`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            tls.server.enqueue(jsonResponse(200, """{"records":[],"baselineCursor":"opaque baseline","nextPageToken":null,"hasMore":false}"""))
            val api = tls.api()
            val page = api.snapshot(null, 200)
            assertEquals("opaque baseline", page.baselineCursor)
            val recorded = tls.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", recorded.method)
            assertEquals("/v1/sync/snapshot", recorded.url.encodedPath)
            assertEquals("200", recorded.url.queryParameter("limit"))
        } finally {
            tls.close()
        }
    }

    @Test
    fun `tls 401 without body is auth required with single send and no refresh`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            tls.server.enqueue(jsonResponse(401, ""))
            val api = tls.api()
            try {
                api.push(emptyList())
                fail("expected AuthenticationRequired")
            } catch (e: SyncApiException.AuthenticationRequired) {
            }
            assertEquals(1, tls.server.requestCount)
        } finally {
            tls.close()
        }
    }

    @Test
    fun `tls 401 explicit reauth is reauthentication required`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            tls.server.enqueue(jsonResponse(401, """{"errorCode":"reauthentication_required"}"""))
            val api = tls.api()
            try {
                api.pull(null, 100)
                fail("expected ReauthenticationRequired")
            } catch (e: SyncApiException.ReauthenticationRequired) {
            }
            assertEquals(1, tls.server.requestCount)
        } finally {
            tls.close()
        }
    }

    @Test
    fun `tls wrong media type is malformed`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            tls.server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"results":[]}""")
                    .addHeader("Content-Type", "text/plain")
                    .build(),
            )
            val api = tls.api()
            try {
                api.push(emptyList())
                fail("expected MalformedResponse")
            } catch (e: SyncApiException.MalformedResponse) {
            }
        } finally {
            tls.close()
        }
    }

    @Test
    fun `tls truncated JSON is malformed not partial success`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            tls.server.enqueue(jsonResponse(200, """{"results":[{"mutationId":"""))
            val api = tls.api()
            try {
                api.push(listOf(durable()))
                fail("expected MalformedResponse")
            } catch (e: SyncApiException.MalformedResponse) {
            }
        } finally {
            tls.close()
        }
    }

    @Test
    fun `tls cancellation propagates original and sends at most once`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            tls.server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"batches":[],"nextCursor":"b","hasMore":false}""")
                    .addHeader("Content-Type", "application/json")
                    .headersDelay(5, TimeUnit.SECONDS)
                    .build(),
            )
            val api = tls.api()
            val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                api.pull(null, 100)
                fail("expected cancellation")
            }
            // Bounded wait for the request to arrive, then cancel; no sleeps.
            val recorded = tls.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/sync/pull", recorded.url.encodedPath)
            job.cancel(CancellationException("test-cancel"))
            withTimeout(5000L) {
                try {
                    job.join()
                } catch (_: CancellationException) {
                }
            }
            assertTrue(job.isCancelled)
            assertTrue(tls.server.requestCount <= 1)
        } finally {
            tls.close()
        }
    }

    @Test
    fun `repeated explicit tls push embeds identical frozen bytes`() = runBlocking {
        val tls = TlsFixture()
        tls.start()
        try {
            val batch = batchJson()
            val body = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch}]}"""
            tls.server.enqueue(jsonResponse(200, body))
            tls.server.enqueue(jsonResponse(200, body))
            val api = tls.api()
            api.push(listOf(durable()))
            api.push(listOf(durable()))
            val first = tls.server.takeRequest(5, TimeUnit.SECONDS)!!
            val second = tls.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(first.body!!.utf8(), second.body!!.utf8())
            assertEquals("""{"mutations":[$inner]}""", first.body!!.utf8())
            assertEquals(2, tls.server.requestCount)
        } finally {
            tls.close()
        }
    }
}
