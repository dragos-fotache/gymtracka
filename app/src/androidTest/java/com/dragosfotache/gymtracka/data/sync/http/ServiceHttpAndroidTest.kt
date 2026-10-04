package com.dragosfotache.gymtracka.data.sync.http

import android.Manifest
import android.content.pm.PackageManager
import android.security.NetworkSecurityPolicy
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * R03a QA Android smoke (API 37 execution only; API 24 image absent — no min-API claim).
 * Target application context: INTERNET permission, cleartext policy, unconfigured
 * construction and local-CA HTTPS/cancellation where feasible. No personal
 * account or app configuration touched.
 */
@RunWith(AndroidJUnit4::class)
class ServiceHttpAndroidTest {

    @Test
    fun internetPermissionGranted() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val granted = target.packageManager.checkPermission(
            Manifest.permission.INTERNET,
            target.packageName,
        )
        assertEquals(PackageManager.PERMISSION_GRANTED, granted)
    }

    @Test
    fun releaseCleartextTrafficRefused() {
        // Merged manifest must set usesCleartextTraffic=false; enforced here.
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
    }

    @Test
    fun unconfiguredConstructionStartsClean() {
        assertNull(ServiceOrigin.configured(null))
        val origin = ServiceOrigin.parse("https://example.com")
        val transport = OkHttpServiceTransport(origin, newServiceHttpClient())
        assertFalse(transport.toString().contains("example.com"))
        // Record the execution platform; API 24 pass is NOT claimed here.
        assertTrue(Build.VERSION.SDK_INT >= 24)
    }

    @Test
    fun localTrustedHttpsRoundTripOnDevice() = runBlocking {
        val held = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory())
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .body("{\"android\":true}")
                    .build(),
            )
            val clientCerts = HandshakeCertificates.Builder()
                .addTrustedCertificate(held.certificate)
                .build()
            val builder = OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            val origin = ServiceOrigin.parse("https://localhost:${server.port}")
            val transport =
                OkHttpServiceTransport(origin, newServiceHttpClient(builder = builder))
            val body = transport.withResponse(
                ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/android-smoke"),
            ) { it.readJsonBody() }
            assertEquals("{\"android\":true}", body)
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(recorded.target.startsWith("/v1/android-smoke"))
        } finally {
            server.close()
        }
    }

    @Test
    fun cancellableLocalTlsIoOnDevice() = runBlocking {
        val held = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory())
        server.start()
        try {
            val big = buildString {
                append("{\"data\":\"")
                repeat(300_000) { append('q') }
                append("\"}")
            }
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .body(big)
                    .throttleBody(8_192, 500, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val clientCerts = HandshakeCertificates.Builder()
                .addTrustedCertificate(held.certificate)
                .build()
            val builder = OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            val origin = ServiceOrigin.parse("https://localhost:${server.port}")
            val transport =
                OkHttpServiceTransport(origin, newServiceHttpClient(builder = builder))
            val entered = CompletableDeferred<Unit>()
            val firstRead = CompletableDeferred<Unit>()
            val job = launch {
                transport.withResponse(
                    ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/android-cancel"),
                ) { response ->
                    if (!entered.isCompleted) entered.complete(Unit)
                    val buf = ByteArray(8_192)
                    while (true) {
                        val n = response.readBodyChunk(buf)
                        if (!firstRead.isCompleted) firstRead.complete(Unit)
                        if (n == -1) break
                    }
                }
            }
            // Bounded: wait for entry, then for real bytes, then cancel mid-stream.
            withTimeout(10_000L) { entered.await() }
            withTimeout(15_000L) { firstRead.await() }
            withTimeout(15_000L) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally {
            server.close()
        }
    }
}
