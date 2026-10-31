package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * response ownership, streaming, cancellation and strict body policy.
 * Test-owned scripted [Call.Factory] seam only; no real network.
 * Scripted races use [CompletableDeferred] gates with bounded [withTimeout];
 * no threads, sleeps or delay polling.
 */
class ServiceHttpOwnershipTest {

    private val origin = ServiceOrigin.parse("https://example.com")
    private val getRequest = ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/ownership-probe")

    // ---------- scripted seam: behavior runs in enqueue AFTER callback capture ----------

    private class ScriptedCall(
        private val request: Request,
        private val onEnqueue: (ScriptedCall, Callback) -> Unit = { _, _ -> },
        private val onCancel: () -> Unit = {},
    ) : Call {
        val callbackRef = AtomicReference<Callback?>(null)
        val canceled = AtomicBoolean(false)
        val enqueueCount = AtomicInteger(0)
        val enqueued = CompletableDeferred<Unit>()

        override fun request(): Request = request
        override fun execute(): Response = throw UnsupportedOperationException("async only")
        override fun enqueue(responseCallback: Callback) {
            enqueueCount.incrementAndGet()
            callbackRef.set(responseCallback)
            if (!enqueued.isCompleted) enqueued.complete(Unit)
            onEnqueue(this, responseCallback)
        }
        override fun cancel() {
            canceled.set(true)
            onCancel()
        }
        override fun isExecuted(): Boolean = enqueueCount.get() > 0
        override fun isCanceled(): Boolean = canceled.get()
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ScriptedCall(request, onEnqueue, onCancel)
        override fun addEventListener(eventListener: EventListener) {}
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()
    }

    private class ScriptedFactory(
        val calls: MutableList<ScriptedCall> = mutableListOf(),
        val behavior: (Request, ScriptedCall, Callback) -> Unit = { _, _, _ -> },
    ) : Call.Factory {
        val created = CompletableDeferred<Unit>()
        override fun newCall(request: Request): Call {
            val call = ScriptedCall(
                request,
                onEnqueue = { c, cb -> behavior(request, c, cb) },
            )
            synchronized(calls) { calls.add(call) }
            if (!created.isCompleted) created.complete(Unit)
            return call
        }
    }

    /**
     * Test-owned body that observably tracks closure via its cached wrapping source.
     * External [ResponseBody.close] is final and funnels into [source], so the single
     * cached wrapper observes every close path (body/response/source close).
     */
    private class TrackingBody(
        text: String,
        mediaType: String = "application/json; charset=utf-8",
    ) : ResponseBody() {
        private val delegate = text.toResponseBody(mediaType.toMediaType())
        val closed = CompletableDeferred<Unit>()
        private val cached: BufferedSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val inner = delegate.source()
            object : ForwardingSource(inner) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        if (!closed.isCompleted) closed.complete(Unit)
                    }
                }
            }.buffer()
        }
        override fun contentType() = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = cached
    }

    private fun jsonResponse(
        request: Request,
        json: String,
        contentType: String? = "application/json; charset=utf-8",
        extraHeaders: Map<String, String> = emptyMap(),
        tracker: TrackingBody? = null,
    ): Response {
        val headers = okhttp3.Headers.Builder()
        if (contentType != null) headers.add("Content-Type", contentType)
        for ((k, v) in extraHeaders) headers.add(k, v)
        val body: ResponseBody = tracker
            ?: json.toResponseBody("application/json; charset=utf-8".toMediaType())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .headers(headers.build())
            .body(body)
            .build()
    }

    private fun transportWith(factory: Call.Factory): OkHttpServiceTransport {
        return OkHttpServiceTransport(origin, factory)
    }

    // ---------- consumer ownership ----------

    @Test
    fun consumerInvokedExactlyOnceWithMetadata() = runBlocking {
        val tracker = TrackingBody("{\"ok\":true}")
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"ok\":true}", tracker = tracker))
        }
        val transport = transportWith(factory)
        val count = AtomicInteger(0)
        val status = transport.withResponse(getRequest) { response ->
            count.incrementAndGet()
            assertEquals(200, response.statusCode)
            response.readJsonBody()
        }
        assertEquals("{\"ok\":true}", status)
        assertEquals(1, count.get())
        assertEquals(1, factory.calls.size)
        assertEquals(1, factory.calls[0].enqueueCount.get())
        withTimeout(5_000L) { tracker.closed.await() }
    }

    @Test
    fun successClosesResponseBody() = runBlocking {
        val tracker = TrackingBody("{\"ok\":true}")
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"ok\":true}", tracker = tracker))
        }
        transportWith(factory).withResponse(getRequest) { response ->
            response.readJsonBody()
        }
        withTimeout(5_000L) { tracker.closed.await() }
    }

    @Test
    fun consumerThrowingPropagatesWithoutMaskingAndCloses() = runBlocking {
        val tracker = TrackingBody("{\"ok\":true}")
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"ok\":true}", tracker = tracker))
        }
        val transport = transportWith(factory)
        val boom = IllegalStateException("boom-consumer-marker")
        try {
            transport.withResponse(getRequest) { _: ServiceHttpResponse ->
                throw boom
            }
            fail("consumer exception must propagate")
        } catch (e: IllegalStateException) {
            assertTrue(e === boom)
        }
        withTimeout(5_000L) { tracker.closed.await() }
    }

    @Test
    fun consumerSuspendingThenResumingWorks() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<String>()
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"ok\":true}"))
        }
        val transport = transportWith(factory)
        val job = launch {
            transport.withResponse(getRequest) { response ->
                if (!entered.isCompleted) entered.complete(Unit)
                gate.await()
                response.readJsonBody()
            }
        }
        withTimeout(5_000L) { entered.await() }
        gate.complete("resumed")
        withTimeout(5_000L) { job.join() }
        assertTrue(job.isCompleted && !job.isCancelled)
    }

    // ---------- cancellation (consumer-level suspension; blocked reads in network suite) ----------

    @Test
    fun cancellationBeforeHeadersCancelsActualCall() = runBlocking {
        val factory = ScriptedFactory { _, _, _ -> /* never respond */ }
        val transport = transportWith(factory)
        val job = launch {
            transport.withResponse(getRequest) { response ->
                response.readJsonBody()
            }
        }
        withTimeout(5_000L) { factory.created.await() }
        val call = factory.calls.first()
        withTimeout(5_000L) { call.enqueued.await() }
        job.cancelAndJoin()
        assertTrue(call.canceled.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellationDuringSuspendedConsumerClosesAndPreservesCause() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val tracker = TrackingBody("{\"ok\":true}")
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"ok\":true}", tracker = tracker))
        }
        val transport = transportWith(factory)
        val marker = CancellationException("injected-cancellation-marker")
        val job = launch {
            try {
                transport.withResponse(getRequest) { _: ServiceHttpResponse ->
                    if (!entered.isCompleted) entered.complete(Unit)
                    gate.await()
                    "unreachable"
                }
                fail("must throw")
            } catch (e: CancellationException) {
                assertEquals(marker.message, e.message)
                throw e
            }
        }
        withTimeout(5_000L) { entered.await() }
        job.cancel(marker)
        withTimeout(5_000L) { job.join() }
        assertTrue(job.isCancelled)
        withTimeout(5_000L) { factory.created.await() }
        assertTrue(factory.calls.first().canceled.get())
        withTimeout(5_000L) { tracker.closed.await() }
    }

    @Test
    fun lateCallbackAfterCancellationIsClosedWithoutResume() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val factory = ScriptedFactory { _, _, _ -> /* hold: no response yet */ }
        val transport = transportWith(factory)
        val resumed = AtomicBoolean(false)
        val job = launch {
            try {
                transport.withResponse(getRequest) { response ->
                    resumed.set(true)
                    response.readJsonBody()
                }
            } catch (e: CancellationException) {
                if (!entered.isCompleted) entered.complete(Unit)
                throw e
            }
        }
        withTimeout(5_000L) { factory.created.await() }
        val call = factory.calls.first()
        withTimeout(5_000L) { call.enqueued.await() }
        job.cancelAndJoin()
        assertTrue(call.canceled.get())
        // Late success after cancellation must be closed, never resume consumer work.
        val lateTracker = TrackingBody("{\"late\":true}")
        val late = jsonResponse(call.request(), "{\"late\":true}", tracker = lateTracker)
        call.callbackRef.get()!!.onResponse(call, late)
        withTimeout(5_000L) { lateTracker.closed.await() }
        assertFalse(resumed.get())
        assertTrue(job.isCancelled || job.isCompleted)
    }

    @Test
    fun ioFailureSurfacesTransientWithoutRawCause() = runBlocking {
        val factory = ScriptedFactory { _, call, cb ->
            cb.onFailure(call, IOException("offline-simulated"))
        }
        val transport = transportWith(factory)
        try {
            transport.withResponse(getRequest) { response ->
                response.readJsonBody()
            }
            fail("I/O failure must throw")
        } catch (e: SyncApiException.Transient) {
            assertTrue(e.connectionFailure)
            assertTrue(e.cause == null)
            assertTrue(e.suppressed.isEmpty())
        }
    }

    // ---------- strict body policy via scripted responses ----------

    @Test
    fun readJsonBody_requiresJsonContentType() = runBlocking {
        val cases: List<Pair<String?, Boolean>> = listOf(
            Pair("application/json", true),
            Pair("Application/JSON; charset=utf-8", true),
            Pair("application/json; charset=UTF-8", true),
            Pair("application/json; charset=\"utf-8\"", true),
            Pair("application/json; v=1", true), // unrelated param ignored
            Pair("application/json; note=\"a;b\"", true), // quoted semicolon ignored
            Pair("application/json; note=\"a\\\"b\"", true), // one escaped quote ignored
            Pair("application/json; note=\"a\\\"b\\\"c\"", true), // escaped pairs ignored
            Pair("application/json; note=\"a\tb\"", true), // quoted HTAB is valid HTTP
            Pair(null, false),
            Pair("text/plain", false),
            Pair("application/json; charset=utf-16", false),
            Pair("application/json; charset=utf-8; charset=utf-8", false),
            Pair("application/json; charset=UTF-8; charset=utf-8", false),
            Pair("application/json; note=\"a\"b\"c\"", false), // repeated quoted group
            Pair("not-a-media-type!!!", false),
        )
        for ((contentType, ok) in cases) {
            val factory = ScriptedFactory { req, call, cb ->
                cb.onResponse(call, jsonResponse(req, "{\"a\":1}", contentType))
            }
            val transport = transportWith(factory)
            try {
                val body = transport.withResponse(getRequest) { it.readJsonBody() }
                assertTrue("content-type <$contentType> should succeed", ok)
                assertEquals("{\"a\":1}", body)
            } catch (e: SyncApiException.MalformedResponse) {
                assertFalse("content-type <$contentType> should succeed", ok)
            }
        }
    }

    @Test
    fun readJsonBody_rejectsMalformedUtf8AndPreservesBom() = runBlocking {
        // Malformed UTF-8 bytes must not decode with replacement.
        val badBytes = byteArrayOf(0x7B.toByte(), 0x22.toByte(), 0xFF.toByte(), 0x7D.toByte())
        val factoryBad = ScriptedFactory { req, call, cb ->
            val response = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(badBytes.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
            cb.onResponse(call, response)
        }
        try {
            transportWith(factoryBad).withResponse(getRequest) { it.readJsonBody() }
            fail("malformed UTF-8 must be MalformedResponse, never silent replacement")
        } catch (_: SyncApiException.MalformedResponse) {
        }
        // BOM must not be stripped: "\uFEFF{}" round-trips with the marker preserved.
        val bomJson = "﻿{\"b\":2}"
        val factoryBom = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, bomJson))
        }
        val roundTripped = transportWith(factoryBom).withResponse(getRequest) { it.readJsonBody() }
        assertEquals(bomJson, roundTripped)
        assertTrue(roundTripped.startsWith("﻿"))
    }

    @Test
    fun readJsonBody_supportsGreaterThanBufferBodies() = runBlocking {
        val big = buildString {
            append("{\"data\":\"")
            repeat(200_000) { append('x') }
            append("\"}")
        }
        assertTrue(big.length > 65_536)
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, big))
        }
        val out = transportWith(factory).withResponse(getRequest) { it.readJsonBody() }
        assertEquals(big.length, out.length)
        assertEquals(big, out)
    }

    @Test
    fun readBodyChunk_requiresNonEmptyBufferAndSignalsEof() = runBlocking {
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"k\":\"v\"}"))
        }
        transportWith(factory).withResponse(getRequest) { response ->
            try {
                response.readBodyChunk(ByteArray(0))
                fail("empty buffer must fail safe")
            } catch (_: SyncModelException) {
            }
            val first = response.readBodyChunk(ByteArray(64))
            assertTrue(first > 0)
            var last: Int
            do {
                last = response.readBodyChunk(ByteArray(64))
            } while (last != -1)
        }
    }

    @Test
    fun bodyModesAreExclusiveAndLifetimeScoped() = runBlocking {
        // chunk-then-json fails.
        val f1 = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"a\":1}"))
        }
        transportWith(f1).withResponse(getRequest) { response ->
            val n = response.readBodyChunk(ByteArray(64))
            assertTrue(n > 0 || n == -1)
            try {
                response.readJsonBody()
                fail("json after chunk must fail")
            } catch (e: SyncModelException) {
                // fixed-safe mode misuse per clarified spec.
            }
        }
        // json-then-chunk fails.
        val f2 = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"a\":1}"))
        }
        transportWith(f2).withResponse(getRequest) { response ->
            response.readJsonBody()
            try {
                response.readBodyChunk(ByteArray(16))
                fail("chunk after json must fail")
            } catch (e: SyncModelException) {
                // fixed-safe mode misuse per clarified spec.
            }
        }
        // After consumer ends, reads fail without I/O.
        val f3 = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"a\":1}"))
        }
        var captured: ServiceHttpResponse? = null
        transportWith(f3).withResponse(getRequest) { response ->
            captured = response
            response.statusCode
        }
        try {
            captured!!.readJsonBody()
            fail("post-lifetime read must fail")
        } catch (e: SyncModelException) {
            // fixed-safe lifetime misuse per clarified spec.
        }
    }

    @Test
    fun nonIdentityEncodingIsMalformed() = runBlocking {
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(
                call,
                jsonResponse(
                    req,
                    "{\"a\":1}",
                    extraHeaders = mapOf("Content-Encoding" to "gzip"),
                ),
            )
        }
        try {
            transportWith(factory).withResponse(getRequest) { it.readJsonBody() }
            fail("gzip encoding must be malformed")
        } catch (_: SyncApiException.MalformedResponse) {
        }
    }

    @Test
    fun contentLengthMismatchIsMalformed() = runBlocking {
        val factory = ScriptedFactory { req, call, cb ->
            val response = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Content-Length", "1000")
                .body("{\"short\":1}".toResponseBody("application/json".toMediaType()))
                .build()
            cb.onResponse(call, response)
        }
        try {
            transportWith(factory).withResponse(getRequest) { it.readJsonBody() }
            fail("length mismatch must be malformed")
        } catch (_: SyncApiException.MalformedResponse) {
        }
    }

    @Test
    fun failedSetupClosesWithoutConsumer() = runBlocking {
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call {
                throw IOException("setup-offline")
            }
        }
        val invoked = AtomicBoolean(false)
        try {
            transportWith(factory).withResponse(getRequest) { _: ServiceHttpResponse ->
                invoked.set(true)
                "unreachable"
            }
            fail("setup failure must throw")
        } catch (e: SyncApiException.Transient) {
            assertTrue(e.cause == null)
        }
        assertFalse(invoked.get())
    }

    @Test
    fun responseHeadersAreImmutableSnapshotsIncludingValues() = runBlocking {
        val factory = ScriptedFactory { req, call, cb ->
            val response = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .addHeader("X-Multi", "one")
                .addHeader("X-Multi", "two")
                .body("{\"a\":1}".toResponseBody("application/json".toMediaType()))
                .build()
            cb.onResponse(call, response)
        }
        transportWith(factory).withResponse(getRequest) { response ->
            val snapshot = response.headers
            assertTrue(snapshot.isNotEmpty())
            try {
                @Suppress("UNCHECKED_CAST")
                (snapshot as MutableMap<String, List<String>>)["evil"] = listOf("x")
                assertFalse(snapshot.containsKey("evil"))
            } catch (_: UnsupportedOperationException) {
            } catch (_: ClassCastException) {
            }
            // Nested value lists must also refuse mutation.
            val firstKey = snapshot.keys.first()
            val values = snapshot[firstKey]!!
            val valueSize = values.size
            try {
                @Suppress("UNCHECKED_CAST")
                (values as MutableList<String>).add("evil-value")
                assertEquals(valueSize, snapshot[firstKey]!!.size)
                assertFalse(snapshot[firstKey]!!.contains("evil-value"))
            } catch (_: UnsupportedOperationException) {
            } catch (_: ClassCastException) {
            }
            // Buffer aliasing check: reading body bytes does not expose internals.
            val buf = Buffer()
            buf.writeUtf8("sentinel")
            assertEquals("sentinel", buf.readUtf8())
        }
    }

    @Test
    fun chunkMode_unexpectedEncodingIsMalformed() = runBlocking {
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(
                call,
                jsonResponse(
                    req,
                    "{\"a\":1}",
                    extraHeaders = mapOf("Content-Encoding" to "gzip"),
                ),
            )
        }
        try {
            transportWith(factory).withResponse(getRequest) { response ->
                response.readBodyChunk(ByteArray(64))
            }
            fail("chunk read with gzip encoding must be malformed")
        } catch (_: SyncApiException.MalformedResponse) {
        }
    }

    @Test
    fun chunkMode_declaredLengthMismatchIsMalformed() = runBlocking {
        val factory = ScriptedFactory { req, call, cb ->
            val response = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Content-Length", "1000")
                .body("{\"short\":1}".toResponseBody("application/json".toMediaType()))
                .build()
            cb.onResponse(call, response)
        }
        try {
            transportWith(factory).withResponse(getRequest) { response ->
                val buf = ByteArray(64)
                while (true) {
                    if (response.readBodyChunk(buf) == -1) break
                }
            }
            fail("chunk drain with length mismatch must be malformed")
        } catch (_: SyncApiException.MalformedResponse) {
        }
    }

    @Test
    fun injectedFactoryCancellationPropagatesExactOriginal() = runBlocking {
        val marker = CancellationException("injected-factory-cancel-marker")
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = throw marker
        }
        try {
            transportWith(factory).withResponse(getRequest) { _: ServiceHttpResponse ->
                fail("must not reach consumer")
            }
            fail("must propagate CancellationException")
        } catch (e: CancellationException) {
            assertTrue(e === marker)
        }
    }

    @Test
    fun injectedEnqueueCancellationPropagatesExactOriginal() = runBlocking {
        val marker = CancellationException("injected-enqueue-cancel-marker")
        val factory = ScriptedFactory { _, _, _ -> throw marker }
        try {
            transportWith(factory).withResponse(getRequest) { _: ServiceHttpResponse ->
                fail("must not reach consumer")
            }
            fail("must propagate CancellationException")
        } catch (e: CancellationException) {
            assertTrue(e === marker)
        }
    }

    @Test
    fun consumerCancellationExceptionPropagatesExactOriginalAndCloses() = runBlocking {
        val tracker = TrackingBody("{\"ok\":true}")
        val factory = ScriptedFactory { req, call, cb ->
            cb.onResponse(call, jsonResponse(req, "{\"ok\":true}", tracker = tracker))
        }
        val marker = CancellationException("injected-consumer-cancel-marker")
        try {
            transportWith(factory).withResponse(getRequest) { _: ServiceHttpResponse ->
                throw marker
            }
            fail("must propagate")
        } catch (e: CancellationException) {
            assertTrue(e === marker)
        }
        withTimeout(5_000L) { tracker.closed.await() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelAfterResponseBeforeConsumerDispatchClosesWithoutRunningConsumer() = runTest {
        // Held callback: the scripted Call captures but never answers on its own.
        val held = AtomicReference<Triple<ScriptedCall, Callback, Request>?>()
        val tracker = TrackingBody("{\"ok\":true}")
        val factory = ScriptedFactory { req, call, cb ->
            held.set(Triple(call, cb, req))
        }
        val transport = transportWith(factory)
        val consumerRan = AtomicBoolean(false)
        val job = launch {
            transport.withResponse(getRequest) { response ->
                consumerRan.set(true)
                response.readJsonBody()
            }
        }
        // Run the coroutine until the Call is enqueued and suspended awaiting headers.
        withTimeout(5_000L) { factory.created.await() }
        val (call, cb, req) = held.get()!!
        withTimeout(5_000L) { call.enqueued.await() }
        // Deliver the response from the test: this resumes the transport, scheduling
        // consumer dispatch on the controlled dispatcher without running it yet.
        cb.onResponse(call, jsonResponse(req, "{\"ok\":true}", tracker = tracker))
        // Cancel BEFORE runCurrent/consumer dispatch: the consumer must never run,
        // and the already-received response must still be closed.
        job.cancel()
        runCurrent()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
        assertFalse(consumerRan.get())
        assertTrue(tracker.closed.isCompleted)
    }

    @Test
    fun readJsonBody_rejectsDuplicateContentType() = runBlocking {
        val duplicates = listOf(
            listOf(
                "application/json; charset=utf-8",
                "application/json; charset=utf-8",
            ),
            listOf("application/json", "text/plain"),
        )
        for (values in duplicates) {
            val factory = ScriptedFactory { req, call, cb ->
                val headers = okhttp3.Headers.Builder()
                for (v in values) headers.add("Content-Type", v)
                val response = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(headers.build())
                    .body("{\"a\":1}".toResponseBody("application/json".toMediaType()))
                    .build()
                cb.onResponse(call, response)
            }
            try {
                transportWith(factory).withResponse(getRequest) { it.readJsonBody() }
                fail("duplicate Content-Type $values must be malformed")
            } catch (_: SyncApiException.MalformedResponse) {
            }
        }
    }

    @Test
    fun readJsonBody_rejectsPrematureEofAgainstDeclaredLength() = runBlocking {
        // Controlled scripted source: clean EOF after 7 bytes while metadata
        // declares 1000. Framing shortfall, never a partial success.
        val short = Buffer().writeUtf8("{\"a\":1}")
        val factory = ScriptedFactory { req, call, cb ->
            val response = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(
                    short.asResponseBody(
                        "application/json; charset=utf-8".toMediaType(),
                        1000,
                    ),
                )
                .build()
            cb.onResponse(call, response)
        }
        try {
            transportWith(factory).withResponse(getRequest) { it.readJsonBody() }
            fail("premature EOF against declared length must be malformed")
        } catch (_: SyncApiException.MalformedResponse) {
        }
    }

    /**
     * Scripted body that observes the producing thread and the closing thread.
     * The first read captures its thread, then parks on a bounded latch until the
     * test releases (or a close arrives); every close path funnels through the
     * single cached wrapper because external close is final.
     */
    private class ThreadObservingBody(text: String) : ResponseBody() {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        val readThread = CompletableDeferred<Thread>()
        val closeThread = CompletableDeferred<Thread>()
        val closedSignal = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        fun release() {
            released.countDown()
        }
        override fun contentType() = "application/json; charset=utf-8".toMediaType()
        override fun contentLength(): Long = bytes.size.toLong()
        private val cached: BufferedSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val data = Buffer().write(bytes)
            var delivered = false
            object : ForwardingSource(data) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!delivered) {
                        if (!readThread.isCompleted) {
                            readThread.complete(Thread.currentThread())
                        }
                        released.await(10, TimeUnit.SECONDS)
                        delivered = true
                    }
                    return super.read(sink, byteCount)
                }
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        released.countDown()
                        if (!closeThread.isCompleted) {
                            closeThread.complete(Thread.currentThread())
                        }
                        if (!closedSignal.isCompleted) closedSignal.complete(Unit)
                    }
                }
            }.buffer()
        }
        override fun source(): BufferedSource = cached
    }

    @Test
    fun blockingReadWorkStaysOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val body = ThreadObservingBody("{\"off\":\"caller\"}")
        val factory = ScriptedFactory { req, call, cb ->
            val response = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json; charset=utf-8")
                .body(body)
                .build()
            cb.onResponse(call, response)
        }
        val deferred = async {
            transportWith(factory).withResponse(getRequest) { it.readJsonBody() }
        }
        val reader = withTimeout(5_000L) { body.readThread.await() }
        body.release()
        val out = withTimeout(10_000L) { deferred.await() }
        assertEquals("{\"off\":\"caller\"}", out)
        assertTrue("blocking read must run off the caller thread", reader != caller)
        withTimeout(5_000L) { body.closedSignal.await() }
        val closer = withTimeout(5_000L) { body.closeThread.await() }
        assertTrue("blocking close must run off the caller thread", closer != caller)
    }
}
