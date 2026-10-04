package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import java.io.IOException
import java.net.ProtocolException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException as CoroutinesCancellationException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import javax.net.ssl.HostnameVerifier
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.cancellation.CancellationException as StdlibCancellationException

/**
 * Concrete [ServiceHttpTransport] over a [Call.Factory]. Every request URL is built from the
 * fixed [ServiceOrigin] plus a validated path; arbitrary absolute/relative URL text is never
 * resolved. Cancellation cancels the actual call before headers and during body reads.
 */
internal class OkHttpServiceTransport : ServiceHttpTransport {
    private val origin: ServiceOrigin
    private val callFactory: Call.Factory

    constructor(
        origin: ServiceOrigin,
        timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
    ) {
        this.origin = origin
        this.callFactory = newServiceHttpClient(timeouts)
    }

    internal constructor(origin: ServiceOrigin, callFactory: Call.Factory) {
        this.origin = origin
        this.callFactory = callFactory
    }

    override suspend fun <T> withResponse(
        request: ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T {
        val call = try {
            callFactory.newCall(buildOkRequest(origin, request))
        } catch (error: Exception) {
            if (error is CoroutinesCancellationException || error is StdlibCancellationException) throw error
            throw SyncApiException.Transient()
        }
        val okResponse = awaitHeaders(call)
        val wrapped = try {
            ServiceHttpResponse(call, okResponse)
        } catch (error: Exception) {
            try {
                okResponse.close()
            } catch (_: Exception) {
            }
            if (error is CoroutinesCancellationException || error is StdlibCancellationException) throw error
            throw SyncApiException.Transient()
        }
        // Structured public-API cancellation watcher for the whole consumer: when the parent
        // scope cancels while blocking I/O runs, the watcher wakes, cancels the actual call
        // and closes the response immediately. No detached threads; joined on exit.
        // The exact consumer-thrown Throwable is captured inside the scope and restored outside,
        // so scope stack-trace recovery copies never replace it; parent cancellation and new
        // failures still propagate untouched when the consumer threw nothing.
        var failure: Throwable? = null
        try {
            return coroutineScope {
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        try {
                            call.cancel()
                        } catch (_: Exception) {
                        }
                        wrapped.closeAfterConsumer()
                    }
                }
                try {
                    consume(wrapped)
                } catch (consumerError: Throwable) {
                    failure = consumerError
                    throw consumerError
                } finally {
                    watcher.cancel()
                    withContext(NonCancellable) {
                        try {
                            watcher.join()
                        } catch (_: Exception) {
                            // Watcher performed safe cleanup only; the consumer outcome stands.
                        }
                    }
                    try {
                        call.cancel()
                    } catch (_: Exception) {
                    }
                    wrapped.closeAfterConsumer()
                }
            }
        } catch (scopeError: Throwable) {
            throw failure ?: scopeError
        }
    }

    override fun toString(): String = "OkHttpServiceTransport(redacted)"
}

/**
 * Trusted construction seam: a fresh secure default builder carrying ONLY the seed builder's
 * TLS trust setup (SSL socket factory plus X509 trust manager) via public OkHttp API, then the
 * frozen policy/timeouts/normal verifier/guard. No custom DNS/proxy/sockets/dispatchers,
 * interceptors, logging, caches or automatic auth/retry behavior survives. Never user config.
 */
internal fun newServiceHttpClient(
    timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
    builder: OkHttpClient.Builder = OkHttpClient.Builder(),
): OkHttpClient {
    val seed = builder.build()
    val fresh = OkHttpClient.Builder()
    val trustManager = seed.x509TrustManager
    if (trustManager != null) {
        fresh.sslSocketFactory(seed.sslSocketFactory, trustManager)
    }
    fresh.connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
    fresh.readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
    fresh.writeTimeout(timeouts.writeMillis, TimeUnit.MILLISECONDS)
    fresh.callTimeout(timeouts.callMillis, TimeUnit.MILLISECONDS)
    fresh.followRedirects(false)
    fresh.followSslRedirects(false)
    fresh.retryOnConnectionFailure(false)
    fresh.cache(null)
    fresh.cookieJar(CookieJar.NO_COOKIES)
    fresh.authenticator(Authenticator.NONE)
    fresh.proxyAuthenticator(Authenticator.NONE)
    fresh.certificatePinner(CertificatePinner.DEFAULT)
    fresh.eventListener(EventListener.NONE)
    fresh.hostnameVerifier(defaultHostnameVerifier)
    fresh.addInterceptor(OneSendBindInterceptor())
    fresh.addNetworkInterceptor(OneSendGuardInterceptor())
    return fresh.build()
}

/** Normal hostname verification from a fresh default public client; never a custom verifier. */
private val defaultHostnameVerifier: HostnameVerifier by lazy(LazyThreadSafetyMode.PUBLICATION) {
    OkHttpClient().hostnameVerifier
}

/** Fresh per-call one-send marker, bound by the application interceptor for EVERY call. */
private class OneSendGuard {
    val fired = AtomicBoolean(false)
}

/**
 * Binds a fresh guard to every call at the application boundary. Application interceptors run
 * once per call (never per follow-up), so a request object reused across explicit calls can
 * never share one guard.
 */
private class OneSendBindInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val bound = chain.request().newBuilder()
            .tag(OneSendGuard::class.java, OneSendGuard())
            .build()
        return chain.proceed(bound)
    }
}

/**
 * Per-call on-wire guard at the network boundary. The first exchange marks the call's guard;
 * any second exchange for the same call (redirect follow-up, 503 Retry-After:0 follow-up,
 * coalesced follow-up) is refused before a second request is sent. The refusal surfaces as a
 * safe transport failure, never a second send. Explicit repeated calls carry fresh guards.
 */
private class OneSendGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val guard = chain.request().tag(OneSendGuard::class.java)
        if (guard != null && !guard.fired.compareAndSet(false, true)) {
            throw IOException("Refused second network exchange")
        }
        return chain.proceed(chain.request())
    }
}

private fun buildOkRequest(origin: ServiceOrigin, request: ServiceHttpRequest): Request {
    val base = StringBuilder("https://")
    if (origin.host.contains(':')) {
        base.append('[').append(origin.host).append(']')
    } else {
        base.append(origin.host)
    }
    if (origin.port != 443) base.append(':').append(origin.port)
    base.append(request.path)
    val urlBuilder = base.toString().toHttpUrl().newBuilder()
    for ((name, value) in request.query) {
        urlBuilder.addEncodedQueryParameter(encodeQueryComponent(name), encodeQueryComponent(value))
    }
    val url: HttpUrl = urlBuilder.build()
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("Accept-Encoding", "identity")
    for ((name, value) in request.headers) {
        builder.header(name, value)
    }
    when (request.method) {
        ServiceHttpMethod.GET -> builder.get()
        ServiceHttpMethod.POST -> {
            builder.header("Content-Type", "application/json; charset=utf-8")
            builder.post(OneShotBody(request.body!!.toByteArray(StandardCharsets.UTF_8)))
        }
    }
    return builder.build()
}

/** UTF-8 query-component encoding: unreserved passthrough, spaces as %20, all else %XX. */
private fun encodeQueryComponent(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val encoded = StringBuilder(bytes.size)
    for (byte in bytes) {
        val unsigned = byte.toInt() and 0xFF
        if (isUnreserved(unsigned)) {
            encoded.append(unsigned.toChar())
        } else {
            encoded.append('%')
            encoded.append(HEX_DIGITS[(unsigned shr 4) and 0xF])
            encoded.append(HEX_DIGITS[unsigned and 0xF])
        }
    }
    return encoded.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun isUnreserved(unsigned: Int): Boolean {
    return unsigned in 'a'.code..'z'.code ||
        unsigned in 'A'.code..'Z'.code ||
        unsigned in '0'.code..'9'.code ||
        unsigned == '-'.code ||
        unsigned == '.'.code ||
        unsigned == '_'.code ||
        unsigned == '~'.code
}

/** One-shot POST body: exact supplied bytes as UTF-8, never replayed on the wire. */
private class OneShotBody(private val bytes: ByteArray) : RequestBody() {
    override fun contentType(): MediaType? = JSON_UTF8

    override fun contentLength(): Long = bytes.size.toLong()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }

    companion object {
        private val JSON_UTF8: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}

private suspend fun awaitHeaders(call: Call): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
        }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // A completed continuation means caller cancellation won; the original
                    // CancellationException propagates instead of this induced failure.
                    if (continuation.isCompleted) return
                    if (e is ProtocolException) {
                        continuation.resumeWithException(SyncApiException.MalformedResponse())
                    } else {
                        continuation.resumeWithException(SyncApiException.Transient())
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    // Late response after cancellation is closed, never delivered as success.
                    if (continuation.isCompleted) {
                        try {
                            response.close()
                        } catch (_: Exception) {
                        }
                        return
                    }
                    // Cancel-after-resume-before-dispatch closes instead of delivering success.
                    continuation.resume(response) { _, _, _ ->
                        try {
                            response.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            })
        } catch (error: Exception) {
            if (error is CoroutinesCancellationException || error is StdlibCancellationException) throw error
            if (!continuation.isCompleted) continuation.resumeWithException(SyncApiException.Transient())
        }
    }
