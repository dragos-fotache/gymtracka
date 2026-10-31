package com.dragosfotache.gymtracka.data.sync.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceError
import com.dragosfotache.gymtracka.data.sync.codec.parseServiceResponseObject
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * tests Android smoke: minSdk-compatible construction, strict error parser
 * including unquoted 1e400 overflow, and a local-TLS endpoint round trip.
 * No backend, no persistence or minimum-API runtime claim.
 */
@RunWith(AndroidJUnit4::class)
class SyncHttpEndpointAndroidTest {

    @Test
    fun androidErrorParserStrictnessSmoke() {
        assertEquals("future_code", decodeServiceError("""{"errorCode":"future_code"}""").errorCode)
        assertEquals("   ", decodeServiceError("""{"errorCode":"   "}""").errorCode)
        assertEquals("1e400", decodeServiceError("""{"errorCode":"1e400"}""").errorCode)
        try {
            decodeServiceError("""{"errorCode":1e400}""")
            fail("expected SyncModelException for unquoted overflow")
        } catch (e: SyncModelException) {
            assertTrue(e.cause == null)
        }
        try {
            decodeServiceError("""{"errorCode":"a","errorCode":"b"}""")
            fail("expected duplicate rejection")
        } catch (e: SyncModelException) {
        }
        val obj = parseServiceResponseObject("""{"s":"x","n":1}""")
        assertEquals("x", obj.getString("s"))
        val classified = classifySyncHttpFailure(SyncHttpOperation.PULL, 400, "invalid_cursor", emptyMap())
        assertTrue(classified is SyncApiException.InvalidCursor)
        val transient = classifySyncHttpFailure(SyncHttpOperation.PUSH, 503, null, emptyMap())
        assertTrue(transient is SyncApiException.Transient)
    }

    @Test
    fun androidOriginBindingAndTransportConstructionSmoke() {
        val origin = ServiceOrigin.parse("https://example.com")
        val binding = ServiceSessionBinding(origin, "11111111-1111-1111-1111-111111111111", 1L)
        assertEquals(binding, binding.copy())
        assertTrue(!binding.toString().contains("11111111-1111-1111-1111-111111111111"))
        // Null configuration stays unavailable without throwing.
        assertEquals(null, ServiceOrigin.configured(null))
        // Transport construction path is minSdk-compatible.
        val factory = OkHttpClient()
        val transport = OkHttpServiceTransport(origin, factory)
        val provider = BoundServiceAccessTokenProvider { expected ->
            BoundServiceAccessToken(binding, OpaqueToken("android-smoke"))
        }
        val api = HttpGymSyncApi(binding, provider, transport)
        assertTrue(!api.toString().contains("android-smoke"))
        factory.dispatcher.executorService.shutdown()
    }

    @Test
    fun androidLocalTlsPullSmoke() = runBlocking {
        val server = MockWebServer()
        var client: OkHttpClient? = null
        try {
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
            val origin = ServiceOrigin.parse("https://localhost:${server.port}")
            val binding = ServiceSessionBinding(origin, "11111111-1111-1111-1111-111111111111", 1L)
            val provider = BoundServiceAccessTokenProvider { expected ->
                BoundServiceAccessToken(binding, OpaqueToken("android-token"))
            }
            val transport = OkHttpServiceTransport(origin, client)
            val api = HttpGymSyncApi(binding, provider, transport)
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"batches":[],"nextCursor":"android-boundary","hasMore":false}""")
                    .addHeader("Content-Type", "application/json")
                    .build(),
            )
            val page = api.pull(null, 100)
            assertEquals("android-boundary", page.nextCursor)
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", recorded.method)
            assertEquals("/v1/sync/pull", recorded.url.encodedPath)
            assertEquals("Bearer android-token", recorded.headers["Authorization"])
        } finally {
            try {
                server.close()
            } catch (_: Exception) {
            }
            try {
                client?.dispatcher?.executorService?.shutdown()
            } catch (_: Exception) {
            }
        }
    }
}
