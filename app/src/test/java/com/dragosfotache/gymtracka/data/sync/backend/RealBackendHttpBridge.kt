package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.http.OkHttpServiceTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.newServiceHttpClient
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.BufferedSink

/**
 * Test-only TLS relay to a fixture-validated disposable loopback service. The actual
 * production transport sends HTTPS with normal hostname verification and trust in
 * this run's local certificate; only the relay's upstream leg is loopback HTTP.
 * Request bytes and encoded query are forwarded without JSON parsing. Responses
 * come from the real backend, never a simulated domain handler or auth bypass.
 * No certificate, config, credential, body or URL is logged or displayed.
 */
internal class RealBackendHttpBridge(
    baseUrl: String,
    private val roomControls: RoomRelayControls? = null,
) : AutoCloseable {
    private val upstreamOrigin = requireLoopbackOrigin(baseUrl)
    private val server = MockWebServer()
    private val upstreamClient = newServiceHttpClient()
    private val closed = AtomicBoolean(false)
    private val transportClient: OkHttpClient
    val origin: ServiceOrigin
    val transport: ServiceHttpTransport
    val certificateBase64: String

    init {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        certificateBase64 = java.util.Base64.getEncoder().encodeToString(certificate.certificate.encoded)
        val serverTrust = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientTrust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        transportClient = newServiceHttpClient(
            builder = OkHttpClient.Builder()
                .sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager),
        )
        server.useHttps(serverTrust.sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = forward(request)
        }
        try {
            server.start()
            origin = ServiceOrigin.parse("https://localhost:${server.port}")
            transport = OkHttpServiceTransport(origin, transportClient)
        } catch (_: Exception) {
            close()
            throw IllegalStateException("Isolated TLS relay startup failed")
        }
    }

    private fun forward(recorded: RecordedRequest): MockResponse {
        roomControls?.dispatch(recorded)?.let { return it }
        val target = recorded.target
        val path = target.substringBefore('?')
        val method = recorded.method
        val allowed = (method == "POST" && path in POST_ROUTES && !target.contains('?')) ||
            (method == "GET" && (path == "/v1/sync/pull" || path == "/v1/sync/snapshot"))
        if (!allowed || !target.startsWith("/v1/") || target.any { it < ' ' || it == '\\' }) {
            return relayFailure()
        }
        try {
            val request = Request.Builder().url(upstreamOrigin + target)
            FORWARDED_REQUEST_HEADERS.forEach { name ->
                recorded.headers.values(name).forEach { request.addHeader(name, it) }
            }
            if (method == "POST") {
                request.post(
                    RelayBody(
                        recorded.body?.toByteArray() ?: ByteArray(0),
                        recorded.headers["Content-Type"]?.toMediaTypeOrNull(),
                    ),
                )
            } else {
                request.get()
            }
            return upstreamClient.newCall(request.build()).execute().use { response ->
                // bytes() verifies upstream completion; the relay never publishes a
                // truncated real-service response as successful JSON.
                val bytes = response.body.bytes()
                val result = MockResponse.Builder()
                    .code(response.code)
                    .body(Buffer().write(bytes))
                for (index in 0 until response.headers.size) {
                    val name = response.headers.name(index)
                    if (!RESPONSE_FRAMING_HEADERS.any { it.equals(name, ignoreCase = true) }) {
                        result.addHeader(name, response.headers.value(index))
                    }
                }
                if (method == "POST" && path == "/v1/sync/push" && response.code == 200 &&
                    roomControls?.consumeResponseLoss(recorded) == true
                ) {
                    // Preserve real headers, but withhold the definitive outcome
                    // body after upstream commit until the client's read timeout.
                    // Body effects work for dynamic dispatch on HTTP/1 and HTTP/2.
                    result.onResponseBody(SocketEffect.Stall)
                }
                result.build()
            }
        } catch (_: Exception) {
            // This is a relay transport failure, not an invented domain outcome.
            // The real service may have committed; unchanged retry identity remains required.
            return relayFailure()
        }
    }

    private fun relayFailure(): MockResponse = MockResponse.Builder()
        .code(503)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .body("{\"errorCode\":\"temporarily_unavailable\"}")
        .build()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        transportClient.dispatcher.cancelAll()
        upstreamClient.dispatcher.cancelAll()
        try {
            server.close()
        } finally {
            try {
                transportClient.connectionPool.evictAll()
                transportClient.dispatcher.executorService.shutdown()
            } finally {
                upstreamClient.connectionPool.evictAll()
                upstreamClient.dispatcher.executorService.shutdown()
            }
        }
    }

    override fun toString(): String = "RealBackendHttpBridge(redacted)"

    /** The second validation is test-owned defense in depth, not production insecure mode. */
    private fun requireLoopbackOrigin(value: String): String {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalStateException("Invalid isolated relay origin")
        }
        if (uri.scheme != "http" || uri.host !in setOf("127.0.0.1", "localhost") ||
            uri.port !in 1..65535 || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || (uri.rawPath != "" && uri.rawPath != "/")
        ) {
            throw IllegalStateException("Invalid isolated relay origin")
        }
        return value.removeSuffix("/")
    }

    private class RelayBody(
        private val bytes: ByteArray,
        private val mediaType: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType
        override fun contentLength(): Long = bytes.size.toLong()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
        }
    }

    companion object {
        private val POST_ROUTES = setOf(
            "/v1/sync/push",
            "/v1/auth/google/challenge",
            "/v1/auth/google",
            "/v1/auth/refresh",
            "/v1/auth/logout",
        )
        private val FORWARDED_REQUEST_HEADERS = listOf(
            "Authorization",
            "Idempotency-Key",
            "Content-Type",
            "Accept",
            "Accept-Encoding",
        )
        private val RESPONSE_FRAMING_HEADERS = listOf(
            "Content-Length",
            "Transfer-Encoding",
            "Connection",
            "Keep-Alive",
            "Trailer",
            "Upgrade",
        )
    }
}
