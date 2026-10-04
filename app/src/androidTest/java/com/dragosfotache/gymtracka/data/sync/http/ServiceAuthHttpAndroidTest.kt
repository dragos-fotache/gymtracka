package com.dragosfotache.gymtracka.data.sync.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

// R03c QA Android smoke: platform scalar overflow/duplicates and auth TLS.
// No production source inspected. Plain identifiers only for DEX.

private const val ANDROID_CHALLENGE = "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":\"opaque nonce\",\"expiresAt\":\"2026-10-03T12:05:00Z\"}"
private const val ANDROID_GRANT = "{\"account\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"displayName\":null},\"accessToken\":\"opaque access\",\"accessExpiresAt\":\"2026-10-03T12:15:00Z\",\"refreshToken\":\"opaque refresh\",\"refreshExpiresAt\":\"2026-11-02T12:00:00Z\"}"

@RunWith(AndroidJUnit4::class)
class ServiceAuthHttpAndroidTest {

    private class AndroidTlsFixture {
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

        fun start() {
            server.useHttps(serverCerts.sslSocketFactory())
            server.start()
        }

        fun api(): HttpServiceAuthApi {
            val port = server.url("/").port
            val origin = ServiceOrigin.parse("https://localhost:$port")
            val builder = OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            val client = newServiceHttpClient(builder = builder)
            clients.add(client)
            return HttpServiceAuthApi(origin, OkHttpServiceTransport(origin, client))
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

    @Test fun challengeTlsSmoke() = runBlocking {
        val f = AndroidTlsFixture()
        f.start()
        try {
            f.server.enqueue(jsonOk(ANDROID_CHALLENGE))
            val c = f.api().challenge(GoogleChallengePurpose.SIGN_IN)
            assertEquals("11111111-1111-1111-1111-111111111111", c.challengeId)
            val recorded = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/auth/google/challenge", recorded.url.encodedPath)
        } finally {
            f.stop()
        }
    }

    @Test fun platformScalarOverflowIsMalformed() = runBlocking {
        val f = AndroidTlsFixture()
        f.start()
        try {
            val overflow = "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":1e400,\"expiresAt\":\"2026-10-03T12:05:00Z\"}"
            f.server.enqueue(jsonOk(overflow))
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

    @Test fun platformDuplicateKeysAreMalformed() = runBlocking {
        val f = AndroidTlsFixture()
        f.start()
        try {
            val dup = "{\"challengeId\":\"11111111-1111-1111-1111-111111111111\",\"nonce\":\"a\",\"nonce\":\"b\",\"expiresAt\":\"2026-10-03T12:05:00Z\"}"
            f.server.enqueue(jsonOk(dup))
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

    @Test fun logoutEmpty204Smoke() = runBlocking {
        val f = AndroidTlsFixture()
        f.start()
        try {
            f.server.enqueue(MockResponse.Builder().code(204).clearHeaders().build())
            f.api().logout(OpaqueToken("opaque refresh"))
            val recorded = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/auth/logout", recorded.url.encodedPath)
        } finally {
            f.stop()
        }
    }

    @Test fun classifierSpotChecks() {
        assertTrue(
            classifyAuthHttpFailure(
                AuthHttpOperation.EXCHANGE,
                401,
                "reauthentication_required",
            ) is SyncApiException.ReauthenticationRequired,
        )
        val rejected = classifyAuthHttpFailure(
            AuthHttpOperation.EXCHANGE,
            401,
            "invalid_google_credential",
        )
        assertTrue(rejected is SyncApiException.RequestRejected)
        assertTrue(
            classifyAuthHttpFailure(AuthHttpOperation.REFRESH, 401, null)
                is SyncApiException.ReauthenticationRequired,
        )
        assertTrue(
            classifyAuthHttpFailure(AuthHttpOperation.CHALLENGE, 413, null)
                is SyncApiException.RequestTooLarge,
        )
    }

    @Test fun grantTlsSmoke() = runBlocking {
        val f = AndroidTlsFixture()
        f.start()
        try {
            f.server.enqueue(jsonOk(ANDROID_GRANT))
            val attempt = ServiceRefreshAttempt.capture(
                "44444444-4444-4444-4444-444444444444",
                OpaqueToken("opaque refresh"),
            )
            val s = f.api().refresh(attempt)
            assertEquals("22222222-2222-2222-2222-222222222222", s.account.accountId)
            // Exchange path also exercises the same grant decoder on this platform.
            f.server.enqueue(jsonOk(ANDROID_GRANT))
            val ex = GoogleExchangeAttempt.capture(
                "33333333-3333-3333-3333-333333333333",
                "11111111-1111-1111-1111-111111111111",
                OpaqueToken("t"),
            )
            val s2 = f.api().exchange(ex)
            assertEquals("22222222-2222-2222-2222-222222222222", s2.account.accountId)
        } finally {
            f.stop()
        }
    }
}
