package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R03b QA: bound credential selection, frozen push bytes, GET query/limit/page
 * tables, result consistency and actual adapter response mapping.
 *
 * Scripted [Call.Factory] seam only: capture in newCall, response callback ONLY
 * AFTER enqueue captures it. Independent raw JSON, no production encoders.
 */
class BoundSyncHttpApiTest {

    private val origin = ServiceOrigin.parse("https://example.com")
    private val accountA = "a111b222-c333-4d55-8e66-f77788889999"
    private val accountB = "b222c333-d444-4e55-8f66-a77788889999"
    private val bindingA = ServiceSessionBinding(origin, accountA, 1L)
    private val tokenA = OpaqueToken("opaque-token-a")

    @Test
    fun `binding validates canonical UUID and generation on constructor and copy`() {
        ServiceSessionBinding(origin, accountA, 1L)
        ServiceSessionBinding(origin, accountA, Long.MAX_VALUE)
        assertFailsPreflight { ServiceSessionBinding(origin, accountA, 0L) }
        assertFailsPreflight { ServiceSessionBinding(origin, accountA, -1L) }
        assertFailsPreflight { ServiceSessionBinding(origin, "", 1L) }
        assertFailsPreflight { ServiceSessionBinding(origin, "NOT-A-UUID", 1L) }
        // Hex-letter fixture: uppercase differs from canonical lowercase.
        assertTrue(accountA != accountA.uppercase())
        assertFailsPreflight { ServiceSessionBinding(origin, accountA.uppercase(), 1L) }
        assertFailsPreflight { ServiceSessionBinding(origin, "  $accountA  ", 1L) }
        val ok = ServiceSessionBinding(origin, accountA, 1L)
        assertFailsPreflight { ok.copy(sessionGeneration = 0L) }
        assertFailsPreflight { ok.copy(accountId = "bad") }
    }

    @Test
    fun `binding equality includes origin account generation and display is redacted`() {
        val same = ServiceSessionBinding(origin, accountA, 1L)
        assertEquals(bindingA, same)
        assertTrue(bindingA != ServiceSessionBinding(origin, accountB, 1L))
        assertTrue(bindingA != ServiceSessionBinding(origin, accountA, 2L))
        val otherOrigin = ServiceOrigin.parse("https://example.com:443")
        assertEquals(bindingA, ServiceSessionBinding(otherOrigin, accountA, 1L))
        val text = bindingA.toString()
        assertTrue(!text.contains(accountA))
        assertTrue(!text.contains("example.com"))
        val tagged = BoundServiceAccessToken(bindingA, tokenA)
        assertTrue(!tagged.toString().contains("opaque-token-a"))
        assertTrue(!tagged.toString().contains(accountA))
        val api = HttpGymSyncApi(bindingA, providerReturning(tagged), ServiceHttpTimeouts())
        assertTrue(!api.toString().contains(accountA))
        assertTrue(!api.toString().contains("opaque-token-a"))
    }

    private fun providerReturning(
        token: BoundServiceAccessToken,
        calls: AtomicInteger = AtomicInteger(0),
        onCall: ((ServiceSessionBinding) -> Unit)? = null,
    ): BoundServiceAccessTokenProvider {
        return BoundServiceAccessTokenProvider { binding ->
            calls.incrementAndGet()
            onCall?.invoke(binding)
            token
        }
    }

    private class ScriptedFactory(
        var statusCode: Int = 200,
        var bodyText: String = """{"results":[]}""",
        var contentType: String = "application/json",
        var extraHeaders: Map<String, String> = emptyMap(),
        var failure: IOException? = null,
    ) : Call.Factory {
        val captured = mutableListOf<Request>()
        val callCount = AtomicInteger(0)
        val enqueueCount = AtomicInteger(0)

        override fun newCall(request: Request): Call {
            captured.add(request)
            callCount.incrementAndGet()
            return FakeCall(request, this)
        }

        fun buildResponse(request: Request): Response {
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message("test")
                .header("Content-Type", contentType)
            for ((k, v) in extraHeaders) {
                builder.header(k, v)
            }
            val media = contentType.substringBefore(";").trim().toMediaType()
            builder.body(bodyText.toResponseBody(media))
            return builder.build()
        }
    }

    private class FakeCall(
        private val req: Request,
        private val factory: ScriptedFactory,
    ) : Call {
        private var executed = false
        private var canceled = false

        override fun request(): Request = req

        override fun execute(): Response {
            executed = true
            factory.failure?.let { throw it }
            if (canceled) throw IOException("canceled")
            return factory.buildResponse(req)
        }

        override fun enqueue(responseCallback: Callback) {
            factory.enqueueCount.incrementAndGet()
            executed = true
            factory.failure?.let {
                responseCallback.onFailure(this, it)
                return
            }
            if (canceled) {
                responseCallback.onFailure(this, IOException("canceled"))
                return
            }
            responseCallback.onResponse(this, factory.buildResponse(req))
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout()

        override fun addEventListener(eventListener: EventListener) {
        }

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun clone(): Call = FakeCall(req, factory)
    }

    private fun apiWith(
        binding: ServiceSessionBinding = bindingA,
        provider: BoundServiceAccessTokenProvider,
        factory: ScriptedFactory,
    ): HttpGymSyncApi {
        val transport = OkHttpServiceTransport(binding.origin, factory)
        return HttpGymSyncApi(binding, provider, transport)
    }

    private val inner1 =
        """{"mutationId":"11111111-1111-1111-1111-111111111111","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1,"payload":{"workoutSetId":"33333333-3333-3333-3333-333333333333"}}"""
    private val inner2 =
        """{"mutationId":"44444444-4444-4444-4444-444444444444","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":2,"payload":{"workoutSetId":"55555555-5555-5555-5555-555555555555"}}"""

    private fun durableFor(
        inner: String,
        root: String,
        generation: Long,
    ): com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel {
        val wrapper = """{"payloadVersion":1,"requestJson":${JSONObject.quote(inner)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"$root","localGeneration":$generation}]}"""
        return decodeDurableRequest(wrapper)
    }

    private fun batchJson(batchId: String, cursor: String, changes: String): String {
        return """{"batchId":"$batchId","cursor":"$cursor","changes":[$changes]}"""
    }

    private fun workoutUpsertChange(root: String, revision: Long): String {
        return """{"aggregateType":"workout","aggregateId":"$root","operation":"upsert","revision":$revision,"record":{"id":"$root","name":"W","status":"active","startedAt":"2026-10-03T12:00:00Z","finishedAt":null,"revision":$revision,"exercises":[]}}"""
    }

    private fun detUuid(n: Int): String {
        return "11111111-1111-4111-8111-" + "%012x".format(n.toLong())
    }

    private fun detInner(n: Int, root: String): String {
        val mid = detUuid(n)
        val setId = detUuid(100000 + n)
        return """{"mutationId":"$mid","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"$root","localGeneration":$n,"payload":{"workoutSetId":"$setId"}}"""
    }

    @Test
    fun `provider selected exactly once with expected binding`() = runTest {
        val factory = ScriptedFactory(bodyText = """{"results":[]}""")
        val seen = mutableListOf<ServiceSessionBinding>()
        val calls = AtomicInteger(0)
        val provider = BoundServiceAccessTokenProvider { binding ->
            calls.incrementAndGet()
            seen.add(binding)
            BoundServiceAccessToken(bindingA, tokenA)
        }
        val api = apiWith(provider = provider, factory = factory)
        api.push(emptyList())
        assertEquals(1, calls.get())
        assertEquals(listOf(bindingA), seen)
        assertEquals(1, factory.callCount.get())
        assertEquals("Bearer opaque-token-a", factory.captured.single().header("Authorization"))
    }

    @Test
    fun `all three binding mismatch axes fail before IO`() = runTest {
        val cases = listOf(
            ServiceSessionBinding(ServiceOrigin.parse("https://other.example"), accountA, 1L),
            ServiceSessionBinding(origin, accountB, 1L),
            ServiceSessionBinding(origin, accountA, 2L),
        )
        for (wrong in cases) {
            val factory = ScriptedFactory(bodyText = """{"results":[]}""")
            val provider = BoundServiceAccessTokenProvider { expected ->
                assertEquals(bindingA, expected)
                BoundServiceAccessToken(wrong, tokenA)
            }
            val api = apiWith(provider = provider, factory = factory)
            try {
                api.push(emptyList())
                fail("expected AuthenticationRequired for $wrong")
            } catch (e: SyncApiException.AuthenticationRequired) {
            }
            assertEquals(0, factory.callCount.get())
        }
    }

    @Test
    fun `suspended lookup returning other binding cannot be used`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = ScriptedFactory(bodyText = """{"results":[]}""")
        val bindingB = ServiceSessionBinding(origin, accountB, 1L)
        val provider = BoundServiceAccessTokenProvider { expected ->
            assertEquals(bindingA, expected)
            entered.complete(Unit)
            release.await()
            BoundServiceAccessToken(bindingB, OpaqueToken("token-b"))
        }
        val api = apiWith(provider = provider, factory = factory)
        // Catch the EXACT expected result INSIDE the child so the async failure
        // never cancels the runTest parent; no broad catches.
        val deferred = async {
            try {
                api.push(emptyList())
                null
            } catch (e: SyncApiException.AuthenticationRequired) {
                e
            }
        }
        entered.await()
        release.complete(Unit)
        val observed = deferred.await()
        assertTrue(observed is SyncApiException.AuthenticationRequired)
        assertEquals(0, factory.callCount.get())
    }

    @Test
    fun `caller list mutation after freeze still sends frozen bytes`() = runTest {
        val root = "22222222-2222-2222-2222-222222222222"
        val r1 = durableFor(inner1, root, 1)
        val r2 = durableFor(inner2, root, 2)
        val caller = mutableListOf(r1, r2)
        val batch = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "opaque-cursor-1", workoutUpsertChange(root, 1))
        val body = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch},{"mutationId":"44444444-4444-4444-4444-444444444444","status":"accepted","errorCode":null,"batch":$batch}]}"""
        val factory = ScriptedFactory(bodyText = body)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = BoundServiceAccessTokenProvider { binding ->
            entered.complete(Unit)
            release.await()
            BoundServiceAccessToken(bindingA, tokenA)
        }
        val api = apiWith(provider = provider, factory = factory)
        val deferred = async { api.push(caller) }
        entered.await()
        caller.clear()
        release.complete(Unit)
        val result = deferred.await()
        assertEquals(2, result.results.size)
        val req = factory.captured.single()
        val buffer = okio.Buffer()
        req.body!!.writeTo(buffer)
        assertEquals("""{"mutations":[$inner1,$inner2]}""", buffer.readUtf8())
    }

    @Test
    fun `preflight duplicate fails safe before lookup`() = runTest {
        val root = "22222222-2222-2222-2222-222222222222"
        val dup1 = durableFor(inner1, root, 1)
        val dup2 = durableFor(inner1, root, 1)
        val calls = AtomicInteger(0)
        val factory = ScriptedFactory()
        val provider = BoundServiceAccessTokenProvider { binding ->
            calls.incrementAndGet()
            BoundServiceAccessToken(bindingA, tokenA)
        }
        val api = apiWith(provider = provider, factory = factory)
        try {
            api.push(listOf(dup1, dup2))
            fail("expected preflight failure")
        } catch (e: SyncModelException) {
            assertTrue(e.cause == null)
        }
        assertEquals(0, calls.get())
        assertEquals(0, factory.callCount.get())
    }

    @Test
    fun `corrupt wrapper via copy fails in adapter before lookup`() = runTest {
        val root = "22222222-2222-2222-2222-222222222222"
        val valid = durableFor(inner1, root, 1)
        // Version corruption through data-class copy reaches the adapter (not decode).
        run {
            val calls = AtomicInteger(0)
            val factory = ScriptedFactory()
            val api = apiWith(
                provider = BoundServiceAccessTokenProvider { binding ->
                    calls.incrementAndGet()
                    BoundServiceAccessToken(bindingA, tokenA)
                },
                factory = factory,
            )
            try {
                api.push(listOf(valid.copy(payloadVersion = 999)))
                fail("expected version failure")
            } catch (e: Exception) {
                assertTrue(e.cause == null)
            }
            assertEquals(0, calls.get())
            assertEquals(0, factory.callCount.get())
        }
        // Corrupt requestJson through copy reaches the adapter preflight.
        run {
            val calls = AtomicInteger(0)
            val factory = ScriptedFactory()
            val api = apiWith(
                provider = BoundServiceAccessTokenProvider { binding ->
                    calls.incrementAndGet()
                    BoundServiceAccessToken(bindingA, tokenA)
                },
                factory = factory,
            )
            try {
                api.push(listOf(valid.copy(requestJson = "not-json{{{")))
                fail("expected corrupt failure")
            } catch (e: SyncModelException) {
                assertTrue(e.cause == null)
            }
            assertEquals(0, calls.get())
            assertEquals(0, factory.callCount.get())
        }
    }

    @Test
    fun `valid100 succeeds and invalid101 fails preflight`() = runTest {
        val root = "22222222-2222-2222-2222-222222222222"
        val hundred = (1..100).map { n -> durableFor(detInner(n, root), root, n.toLong()) }
        val batch = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "opaque-cursor-1", workoutUpsertChange(root, 1))
        val outcomes = (1..100).joinToString(",") { n ->
            """{"mutationId":"${detUuid(n)}","status":"rejected","errorCode":"future_code","batch":null}"""
        }
        val factory = ScriptedFactory(bodyText = """{"results":[$outcomes]}""")
        val lookups = AtomicInteger(0)
        val api = apiWith(
            provider = BoundServiceAccessTokenProvider { binding ->
                lookups.incrementAndGet()
                BoundServiceAccessToken(bindingA, tokenA)
            },
            factory = factory,
        )
        val result = api.push(hundred)
        assertEquals(100, result.results.size)
        assertEquals(detUuid(1), result.results[0].mutationId)
        assertEquals(detUuid(100), result.results[99].mutationId)
        assertEquals(1, lookups.get())
        assertEquals(1, factory.callCount.get())

        val over = (1..101).map { n -> durableFor(detInner(n, root), root, n.toLong()) }
        val factory2 = ScriptedFactory(bodyText = """{"results":[]}""")
        val lookups2 = AtomicInteger(0)
        val api2 = apiWith(
            provider = BoundServiceAccessTokenProvider { binding ->
                lookups2.incrementAndGet()
                BoundServiceAccessToken(bindingA, tokenA)
            },
            factory = factory2,
        )
        try {
            api2.push(over)
            fail("expected over-100 preflight")
        } catch (e: SyncModelException) {
            assertTrue(e.cause == null)
        }
        assertEquals(0, lookups2.get())
        assertEquals(0, factory2.callCount.get())
    }

    @Test
    fun `unpaired surrogates fail before lookup with no send`() = runTest {
        val loneHigh = "\uD800"
        val loneLow = "\uDC00"
        val badTokens = listOf("a${loneHigh}b", "a${loneLow}b", loneHigh, loneLow)
        for (bad in badTokens) {
            var lookups = 0
            var factory = ScriptedFactory(bodyText = """{"batches":[],"nextCursor":"x","hasMore":false}""")
            var api = apiWith(
                provider = BoundServiceAccessTokenProvider { binding ->
                    lookups++
                    BoundServiceAccessToken(bindingA, tokenA)
                },
                factory = factory,
            )
            try {
                api.pull(bad, 100)
                fail("expected preflight for unpaired surrogate pull")
            } catch (e: SyncModelException) {
                assertTrue(e.cause == null)
            }
            assertEquals(0, lookups)
            assertEquals(0, factory.callCount.get())

            lookups = 0
            factory = ScriptedFactory(bodyText = """{"records":[],"baselineCursor":"b","nextPageToken":null,"hasMore":false}""")
            api = apiWith(
                provider = BoundServiceAccessTokenProvider { binding ->
                    lookups++
                    BoundServiceAccessToken(bindingA, tokenA)
                },
                factory = factory,
            )
            try {
                api.snapshot(bad, 200)
                fail("expected preflight for unpaired surrogate snapshot")
            } catch (e: SyncModelException) {
                assertTrue(e.cause == null)
            }
            assertEquals(0, lookups)
            assertEquals(0, factory.callCount.get())
        }
        // Push: valid workout.start whose name holds a raw lone surrogate. Decode must
        // succeed; the adapter preflight must then fail before lookup.
        val wid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val mid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val surrogateInner =
            """{"mutationId":"$mid","type":"workout.start","aggregateType":"workout","aggregateId":"$wid","localGeneration":1,"payload":{"id":"$wid","name":"a${loneHigh}b","startedAt":"2026-10-03T12:00:00Z"}}"""
        val wrapper = """{"payloadVersion":1,"requestJson":${JSONObject.quote(surrogateInner)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"$wid","localGeneration":1}]}"""
        val decoded = decodeDurableRequest(wrapper)
        val f3 = ScriptedFactory()
        var lookups3 = 0
        val a3 = apiWith(
            provider = BoundServiceAccessTokenProvider { binding ->
                lookups3++
                BoundServiceAccessToken(bindingA, tokenA)
            },
            factory = f3,
        )
        try {
            a3.push(listOf(decoded))
            fail("expected preflight for surrogate push body")
        } catch (e: SyncModelException) {
            assertTrue(e.cause == null)
        }
        assertEquals(0, lookups3)
        assertEquals(0, f3.callCount.get())
    }

    @Test
    fun `bearer leading trailing space is unrepresentable never trimmed`() = runTest {
        val spaced = listOf(" leading", "trailing ", " both ", "\tafter", "before\t")
        for (raw in spaced) {
            val factory = ScriptedFactory(bodyText = """{"results":[]}""")
            val lookups = AtomicInteger(0)
            val provider = BoundServiceAccessTokenProvider { binding ->
                lookups.incrementAndGet()
                BoundServiceAccessToken(bindingA, OpaqueToken(raw))
            }
            val api = apiWith(provider = provider, factory = factory)
            try {
                api.push(emptyList())
                fail("expected AuthenticationRequired for spaced token")
            } catch (e: SyncApiException.AuthenticationRequired) {
            }
            assertEquals(1, lookups.get())
            assertEquals(0, factory.callCount.get())
        }
    }

    @Test
    fun `unrepresentable bearer controls and non-ascii fail without IO`() = runTest {
        for (raw in listOf("has\nnewline", "non-ascii-é")) {
            val factory = ScriptedFactory(bodyText = """{"results":[]}""")
            val api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, OpaqueToken(raw))), factory = factory)
            try {
                api.push(emptyList())
                fail("expected AuthenticationRequired")
            } catch (e: SyncApiException.AuthenticationRequired) {
            }
            assertEquals(0, factory.callCount.get())
        }
    }

    @Test
    fun `provider safe categories propagate same object`() = runTest {
        val auth = SyncApiException.AuthenticationRequired()
        var factory = ScriptedFactory()
        var api = apiWith(provider = BoundServiceAccessTokenProvider { throw auth }, factory = factory)
        try {
            api.push(emptyList())
            fail("expected AuthenticationRequired")
        } catch (e: SyncApiException.AuthenticationRequired) {
            assertSame(auth, e)
        }

        val reauth = SyncApiException.ReauthenticationRequired()
        factory = ScriptedFactory()
        api = apiWith(provider = BoundServiceAccessTokenProvider { throw reauth }, factory = factory)
        try {
            api.push(emptyList())
            fail("expected ReauthenticationRequired")
        } catch (e: SyncApiException.ReauthenticationRequired) {
            assertSame(reauth, e)
        }

        val transient = SyncApiException.Transient(1000L)
        factory = ScriptedFactory()
        api = apiWith(provider = BoundServiceAccessTokenProvider { throw transient }, factory = factory)
        try {
            api.push(emptyList())
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertSame(transient, e)
        }

        val unexpected: List<SyncApiException> = listOf(
            SyncApiException.InvalidCursor(),
            SyncApiException.CursorExpired(),
            SyncApiException.InvalidSnapshotToken(),
            SyncApiException.SnapshotExpired(),
            SyncApiException.RequestTooLarge(),
            SyncApiException.RequestRejected("future_code"),
            SyncApiException.MalformedResponse(),
        )
        for (foreign in unexpected) {
            val f2 = ScriptedFactory()
            val a2 = apiWith(provider = BoundServiceAccessTokenProvider { throw foreign }, factory = f2)
            try {
                a2.push(emptyList())
                fail("expected Transient for $foreign")
            } catch (e: SyncApiException.Transient) {
                assertTrue(e.cause == null)
                assertTrue(e.suppressed.isEmpty())
            }
            assertEquals(0, f2.callCount.get())
        }

        val sentinel = CancellationException("stop")
        factory = ScriptedFactory()
        api = apiWith(provider = BoundServiceAccessTokenProvider { throw sentinel }, factory = factory)
        try {
            api.push(emptyList())
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertTrue(e === sentinel)
        }
    }

    @Test
    fun `no retry or refresh on failures single send each explicit call`() = runTest {
        val factory = ScriptedFactory(statusCode = 413, bodyText = "", contentType = "application/json")
        val calls = AtomicInteger(0)
        val provider = BoundServiceAccessTokenProvider { binding ->
            calls.incrementAndGet()
            BoundServiceAccessToken(bindingA, tokenA)
        }
        val api = apiWith(provider = provider, factory = factory)
        repeat(2) {
            try {
                api.push(emptyList())
                fail("expected RequestTooLarge")
            } catch (e: SyncApiException.RequestTooLarge) {
            }
        }
        assertEquals(2, calls.get())
        assertEquals(2, factory.callCount.get())
    }

    @Test
    fun `push sends exact frozen bytes order count IDs`() = runTest {
        val root = "22222222-2222-2222-2222-222222222222"
        val r1 = durableFor(inner1, root, 1)
        val r2 = durableFor(inner2, root, 2)
        val batch = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "opaque-cursor-1", workoutUpsertChange(root, 1))
        val body = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch},{"mutationId":"44444444-4444-4444-4444-444444444444","status":"accepted","errorCode":null,"batch":$batch}]}"""
        val factory = ScriptedFactory(bodyText = body)
        val api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        val result = api.push(listOf(r1, r2))
        assertEquals(2, result.results.size)
        assertEquals("11111111-1111-1111-1111-111111111111", result.results[0].mutationId)
        assertEquals("44444444-4444-4444-4444-444444444444", result.results[1].mutationId)
        val req = factory.captured.single()
        assertEquals("POST", req.method)
        assertTrue(req.url.encodedPath == "/v1/sync/push")
        val buffer = okio.Buffer()
        req.body!!.writeTo(buffer)
        assertEquals("""{"mutations":[$inner1,$inner2]}""", buffer.readUtf8())
    }

    @Test
    fun `exact raw whitespace and number spelling repeated sends`() = runTest {
        val root = "22222222-2222-2222-2222-222222222222"
        // Whitespace/order/number spelling inside the frozen mutation is preserved byte-for-byte.
        val spacedInner =
            """{ "mutationId" : "11111111-1111-1111-1111-111111111111" , "type" : "workout.deleteSet" , "aggregateType" : "workout" , "aggregateId" : "$root" , "localGeneration" : 1 , "payload" : { "workoutSetId" : "33333333-3333-3333-3333-333333333333" } }"""
        val r = durableFor(spacedInner, root, 1)
        val batch = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "opaque-cursor-1", workoutUpsertChange(root, 1))
        val body = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch}]}"""
        val factory = ScriptedFactory(bodyText = body)
        val api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        api.push(listOf(r))
        api.push(listOf(r))
        assertEquals(2, factory.captured.size)
        val first = okio.Buffer().also { factory.captured[0].body!!.writeTo(it) }.readUtf8()
        val second = okio.Buffer().also { factory.captured[1].body!!.writeTo(it) }.readUtf8()
        assertEquals("""{"mutations":[$spacedInner]}""", first)
        assertEquals(first, second)
    }

    @Test
    fun `duplicate original outcomes returned normally and rejected HTTP200 is not an exception`() = runTest {
        val root = "22222222-2222-2222-2222-222222222222"
        val r1 = durableFor(inner1, root, 1)
        val batch = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "opaque-cursor-1", workoutUpsertChange(root, 1))
        val dupAccepted = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"duplicate","errorCode":null,"batch":$batch}]}"""
        val factory = ScriptedFactory(bodyText = dupAccepted)
        val api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        val result = api.push(listOf(r1))
        assertEquals(1, result.results.size)

        val dupRejected =
            """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"duplicate","errorCode":"original_rejection","batch":null}]}"""
        val factory2 = ScriptedFactory(bodyText = dupRejected)
        val api2 = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory2)
        val result2 = api2.push(listOf(r1))
        assertEquals("original_rejection", result2.results[0].errorCode)

        val rejectBody = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"rejected","errorCode":"future_code","batch":null}]}"""
        val factory3 = ScriptedFactory(bodyText = rejectBody)
        val api3 = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory3)
        val result3 = api3.push(listOf(r1))
        assertEquals("future_code", result3.results[0].errorCode)
    }

    @Test
    fun `push result inconsistencies are malformed`() = runTest {
        val root = "22222222-2222-2222-2222-222222222222"
        val r1 = durableFor(inner1, root, 1)
        val r2 = durableFor(inner2, root, 2)
        val batch = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "opaque-cursor-1", workoutUpsertChange(root, 1))
        var factory = ScriptedFactory(bodyText = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch}]}""")
        var api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.push(listOf(r1, r2))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
        }
        val swapped = """{"results":[{"mutationId":"44444444-4444-4444-4444-444444444444","status":"accepted","errorCode":null,"batch":$batch},{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch}]}"""
        factory = ScriptedFactory(bodyText = swapped)
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.push(listOf(r1, r2))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
        }
        // Altered ID, extra outcome and duplicated outcome IDs are all malformed.
        val altered = """{"results":[{"mutationId":"99999999-9999-9999-9999-999999999999","status":"accepted","errorCode":null,"batch":$batch},{"mutationId":"44444444-4444-4444-4444-444444444444","status":"accepted","errorCode":null,"batch":$batch}]}"""
        factory = ScriptedFactory(bodyText = altered)
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.push(listOf(r1, r2))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
        }
        val extra = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch},{"mutationId":"44444444-4444-4444-4444-444444444444","status":"accepted","errorCode":null,"batch":$batch},{"mutationId":"44444444-4444-4444-4444-444444444444","status":"accepted","errorCode":null,"batch":$batch}]}"""
        factory = ScriptedFactory(bodyText = extra)
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.push(listOf(r1, r2))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
        }
        factory = ScriptedFactory(bodyText = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"accepted","errorCode":null,"batch":$batch}]}""")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.push(emptyList())
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
        }
    }

    @Test
    fun `multi-root workout routine custom with nonempty children succeed`() = runTest {
        val wid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val rid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val cid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val exeRef = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val workoutChange =
            """{"aggregateType":"workout","aggregateId":"$wid","operation":"upsert","revision":3,"record":{"id":"$wid","name":"Full W","status":"active","startedAt":"2026-10-03T12:00:00Z","finishedAt":null,"revision":3,"exercises":[{"id":"eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee","exerciseId":"$exeRef","exerciseName":"Full W Ex","position":0,"notes":"n","restDurationSeconds":30,"sets":[{"id":"ffffffff-ffff-4fff-8fff-ffffffffffff","position":0,"repetitions":8,"weightKg":12.5,"isCompleted":true,"restSeconds":61},{"id":"00000001-0001-4001-8001-000000000001","position":1,"repetitions":null,"weightKg":null,"isCompleted":false,"restSeconds":null}]}]}}"""
        val routineChange =
            """{"aggregateType":"routine","aggregateId":"$rid","operation":"upsert","revision":2,"record":{"id":"$rid","name":"Full R","isDraft":false,"revision":2,"exercises":[{"id":"00000002-0002-4002-8002-000000000002","exerciseId":"$exeRef","exerciseName":"Full R Ex","position":0,"restDurationSeconds":45,"sets":[{"id":"00000003-0003-4003-8003-000000000003","position":0,"repetitions":10,"weightKg":20}]}]}}"""
        val customChange =
            """{"aggregateType":"customExercise","aggregateId":"$cid","operation":"upsert","revision":1,"record":{"id":"$cid","name":"Full C","type":"reps","category":"strength","difficultyLevel":"intermediate","forceType":"push","mechanics":"compound","instructions":["cue one","cue two"],"isUserCreated":true,"revision":1,"muscleGroups":[{"id":"00000004-0004-4004-8004-000000000004","name":"Chest","type":"primary"}],"equipment":[{"id":"00000005-0005-4005-8005-000000000005","name":"Dumbbell","numItems":2}]}}"""
        val batch = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "cursor-multi", "$workoutChange")
        // Pull page carrying workout family plus snapshot-style routine/custom proves
        // multi-root decoding with nulls/rest/metadata through the production path.
        val pullBody = """{"batches":[$batch],"nextCursor":"cursor-multi","hasMore":false}"""
        val factory = ScriptedFactory(bodyText = pullBody)
        val api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        val page = api.pull("cursor-prev", 100)
        assertEquals(1, page.batches.size)
        assertEquals("cursor-multi", page.nextCursor)
        assertEquals(false, page.hasMore)
        assertEquals(1, page.batches[0].changes.size)
        val change0 = page.batches[0].changes[0]
        assertEquals(wid, change0.aggregateId)
        assertEquals(3L, change0.revision)
        assertEquals(ChangeOperation.UPSERT, change0.operation)
        val workoutRecord = (change0.record as CanonicalRecord.Workout).record
        assertEquals(wid, workoutRecord.id)
        assertEquals("Full W", workoutRecord.name)
        assertEquals("active", workoutRecord.status)
        assertEquals(null, workoutRecord.finishedAtMillis)
        assertEquals(3L, workoutRecord.revision)
        assertEquals(1, workoutRecord.exercises.size)
        val wex = workoutRecord.exercises[0]
        assertEquals("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", wex.id)
        assertEquals(exeRef, wex.exerciseId)
        assertEquals("Full W Ex", wex.exerciseName)
        assertEquals(0, wex.position)
        assertEquals("n", wex.notes)
        assertEquals(30, wex.restDurationSeconds)
        assertEquals(2, wex.sets.size)
        assertEquals(8, wex.sets[0].repetitions)
        assertEquals(12.5, wex.sets[0].weightKg)
        assertEquals(true, wex.sets[0].isCompleted)
        assertEquals(61, wex.sets[0].restSeconds)
        assertEquals(null, wex.sets[1].repetitions)
        assertEquals(null, wex.sets[1].weightKg)
        assertEquals(false, wex.sets[1].isCompleted)
        assertEquals(null, wex.sets[1].restSeconds)

        val snapshotBody =
            """{"records":[$routineChange,$customChange],"baselineCursor":"baseline-multi","nextPageToken":null,"hasMore":false}"""
        val factory2 = ScriptedFactory(bodyText = snapshotBody)
        val api2 = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory2)
        val snap = api2.snapshot(null, 200)
        assertEquals(2, snap.records.size)
        assertEquals("baseline-multi", snap.baselineCursor)
        assertEquals(false, snap.hasMore)
        val snapRoutine = snap.records[0]
        assertEquals(rid, snapRoutine.aggregateId)
        assertEquals(2L, snapRoutine.revision)
        val routineRecord = (snapRoutine.record as CanonicalRecord.Routine).record
        assertEquals(rid, routineRecord.id)
        assertEquals("Full R", routineRecord.name)
        assertEquals(false, routineRecord.isDraft)
        assertEquals(2L, routineRecord.revision)
        assertEquals(1, routineRecord.exercises.size)
        val rex = routineRecord.exercises[0]
        assertEquals("00000002-0002-4002-8002-000000000002", rex.id)
        assertEquals(exeRef, rex.exerciseId)
        assertEquals("Full R Ex", rex.exerciseName)
        assertEquals(0, rex.position)
        assertEquals(45, rex.restDurationSeconds)
        assertEquals(1, rex.sets.size)
        assertEquals(10, rex.sets[0].repetitions)
        assertEquals(20.0, rex.sets[0].weightKg)
        val snapCustom = snap.records[1]
        assertEquals(cid, snapCustom.aggregateId)
        val customRecord = (snapCustom.record as CanonicalRecord.CustomExercise).record
        assertEquals(cid, customRecord.id)
        assertEquals("Full C", customRecord.name)
        assertEquals("reps", customRecord.type)
        assertEquals("strength", customRecord.category)
        assertEquals("intermediate", customRecord.difficultyLevel)
        assertEquals("push", customRecord.forceType)
        assertEquals("compound", customRecord.mechanics)
        assertEquals(listOf("cue one", "cue two"), customRecord.instructions)
        assertEquals(true, customRecord.isUserCreated)
        assertEquals(1L, customRecord.revision)
        assertEquals(1, customRecord.muscleGroups.size)
        assertEquals("Chest", customRecord.muscleGroups[0].name)
        assertEquals("primary", customRecord.muscleGroups[0].type)
        assertEquals(1, customRecord.equipment.size)
        assertEquals("Dumbbell", customRecord.equipment[0].name)
        assertEquals(2, customRecord.equipment[0].numItems)
    }

    @Test
    fun `pull query defaults limits opaque values`() = runTest {
        var factory = ScriptedFactory(bodyText = """{"batches":[],"nextCursor":"opaque-boundary","hasMore":false}""")
        var api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        api.pull(null)
        var req = factory.captured.single()
        assertEquals("GET", req.method)
        assertEquals("/v1/sync/pull", req.url.encodedPath)
        assertEquals(null, req.url.queryParameter("cursor"))
        assertEquals("100", req.url.queryParameter("limit"))

        factory = ScriptedFactory(bodyText = """{"batches":[],"nextCursor":"same-cursor","hasMore":false}""")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        api.pull("same-cursor", 5)
        req = factory.captured.single()
        assertEquals("same-cursor", req.url.queryParameter("cursor"))
        assertEquals("5", req.url.queryParameter("limit"))

        // Min/max/overflow clamping actually succeeds with empty pages.
        val clampCases = listOf(0 to "1", -5 to "1", 1000 to "200", Int.MAX_VALUE to "200", Int.MIN_VALUE to "1")
        for ((input, expected) in clampCases) {
            val f = ScriptedFactory(bodyText = """{"batches":[],"nextCursor":"b","hasMore":false}""")
            val a = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = f)
            a.pull(null, input)
            assertEquals(expected, f.captured.single().url.queryParameter("limit"))
        }

        // Opaque nonnull values survive without trim/decode/reorder.
        val opaque = "  a+b%c/d?e=f&g  é+%20"
        val f2 = ScriptedFactory(bodyText = """{"batches":[],"nextCursor":"$opaque","hasMore":false}""")
        val a2 = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = f2)
        a2.pull(opaque, 100)
        assertEquals(opaque, f2.captured.single().url.queryParameter("cursor"))
    }

    @Test
    fun `snapshot query defaults limits opaque values`() = runTest {
        var factory = ScriptedFactory(bodyText = """{"records":[],"baselineCursor":"opaque baseline","nextPageToken":null,"hasMore":false}""")
        var api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        api.snapshot(null)
        var req = factory.captured.single()
        assertEquals("/v1/sync/snapshot", req.url.encodedPath)
        assertEquals(null, req.url.queryParameter("pageToken"))
        assertEquals("200", req.url.queryParameter("limit"))

        for ((input, expected) in listOf(0 to "1", -3 to "1", 1000 to "500", Int.MAX_VALUE to "500")) {
            val f = ScriptedFactory(bodyText = """{"records":[],"baselineCursor":"b","nextPageToken":null,"hasMore":false}""")
            val a = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = f)
            a.snapshot(null, input)
            assertEquals(expected, f.captured.single().url.queryParameter("limit"))
        }
    }

    @Test
    fun `pull page consistency enforced`() = runTest {
        var factory = ScriptedFactory(bodyText = """{"batches":[],"nextCursor":"supplied","hasMore":false}""")
        var api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        val tail = api.pull("supplied", 100)
        assertEquals("supplied", tail.nextCursor)

        factory = ScriptedFactory(bodyText = """{"batches":[],"nextCursor":"retained-start","hasMore":false}""")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        val empty = api.pull(null, 100)
        assertTrue(empty.nextCursor.isNotEmpty())

        // Changed empty tail is malformed.
        factory = ScriptedFactory(bodyText = """{"batches":[],"nextCursor":"other","hasMore":false}""")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.pull("supplied", 100)
            fail("expected MalformedResponse for changed empty tail")
        } catch (e: SyncApiException.MalformedResponse) {
        }

        // Nonprogress: nonempty page that does not advance from nonnull cursor.
        val root = "22222222-2222-2222-2222-222222222222"
        val change = workoutUpsertChange(root, 1)
        val stuck = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "supplied", change)
        factory = ScriptedFactory(bodyText = """{"batches":[$stuck],"nextCursor":"supplied","hasMore":true}""")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.pull("supplied", 100)
            fail("expected MalformedResponse for nonprogress")
        } catch (e: SyncApiException.MalformedResponse) {
        }

        val b1 = batchJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "c1", change)
        val b2 = batchJson("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "c2", change)
        factory = ScriptedFactory(bodyText = """{"batches":[$b1,$b2],"nextCursor":"c2","hasMore":false}""")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.pull(null, 1)
            fail("expected MalformedResponse for over-limit page")
        } catch (e: SyncApiException.MalformedResponse) {
        }
    }

    @Test
    fun `snapshot page consistency enforced`() = runTest {
        var factory = ScriptedFactory(bodyText = """{"records":[],"baselineCursor":"opaque baseline","nextPageToken":null,"hasMore":false}""")
        var api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        val terminal = api.snapshot(null, 200)
        assertEquals("opaque baseline", terminal.baselineCursor)

        factory = ScriptedFactory(
            bodyText = """{"records":[${workoutUpsertChange("22222222-2222-2222-2222-222222222222", 1)}],"baselineCursor":"b","nextPageToken":"same","hasMore":true}""",
        )
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.snapshot("same", 200)
            fail("expected MalformedResponse for equal continuation")
        } catch (e: SyncApiException.MalformedResponse) {
        }

        // Empty nonterminal page is malformed.
        factory = ScriptedFactory(bodyText = """{"records":[],"baselineCursor":"b","nextPageToken":"next","hasMore":true}""")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.snapshot(null, 200)
            fail("expected MalformedResponse for empty nonterminal")
        } catch (e: SyncApiException.MalformedResponse) {
        }

        // Over-limit snapshot page is malformed.
        val r = workoutUpsertChange("22222222-2222-2222-2222-222222222222", 1)
        factory = ScriptedFactory(bodyText = """{"records":[$r,$r],"baselineCursor":"b","nextPageToken":null,"hasMore":false}""")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.snapshot(null, 1)
            fail("expected MalformedResponse for over-limit snapshot")
        } catch (e: SyncApiException.MalformedResponse) {
        }
    }

    @Test
    fun `classifySyncHttpFailure status operation matrix`() {
        fun check(op: SyncHttpOperation, code: Int, err: String?, expected: Class<out SyncApiException>) {
            val got = classifySyncHttpFailure(op, code, err, emptyMap())
            assertTrue("op=$op code=$code err=$err got=$got", expected.isInstance(got))
        }
        for (op in SyncHttpOperation.values()) {
            check(op, 413, null, SyncApiException.RequestTooLarge::class.java)
            check(op, 413, "anything", SyncApiException.RequestTooLarge::class.java)
            check(op, 429, null, SyncApiException.Transient::class.java)
            check(op, 500, null, SyncApiException.Transient::class.java)
            check(op, 502, null, SyncApiException.Transient::class.java)
            check(op, 503, null, SyncApiException.Transient::class.java)
            check(op, 504, null, SyncApiException.Transient::class.java)
            check(op, 401, "reauthentication_required", SyncApiException.ReauthenticationRequired::class.java)
            check(op, 401, null, SyncApiException.AuthenticationRequired::class.java)
            check(op, 401, "", SyncApiException.AuthenticationRequired::class.java)
            check(op, 401, "cursor_expired", SyncApiException.AuthenticationRequired::class.java)
            check(op, 200, null, SyncApiException.MalformedResponse::class.java)
            check(op, 204, null, SyncApiException.MalformedResponse::class.java)
            check(op, 302, null, SyncApiException.MalformedResponse::class.java)
            check(op, 422, null, SyncApiException.MalformedResponse::class.java)
            check(op, 422, "", SyncApiException.MalformedResponse::class.java)
        }
        check(SyncHttpOperation.PULL, 400, "invalid_cursor", SyncApiException.InvalidCursor::class.java)
        check(SyncHttpOperation.PULL, 410, "cursor_expired", SyncApiException.CursorExpired::class.java)
        check(SyncHttpOperation.SNAPSHOT, 400, "invalid_snapshot_token", SyncApiException.InvalidSnapshotToken::class.java)
        check(SyncHttpOperation.SNAPSHOT, 410, "snapshot_expired", SyncApiException.SnapshotExpired::class.java)
        check(SyncHttpOperation.PUSH, 400, "invalid_cursor", SyncApiException.RequestRejected::class.java)
        check(SyncHttpOperation.SNAPSHOT, 400, "invalid_cursor", SyncApiException.RequestRejected::class.java)
        check(SyncHttpOperation.PULL, 400, "invalid_snapshot_token", SyncApiException.RequestRejected::class.java)
        check(SyncHttpOperation.PUSH, 410, "cursor_expired", SyncApiException.RequestRejected::class.java)
        check(SyncHttpOperation.PUSH, 410, "snapshot_expired", SyncApiException.RequestRejected::class.java)
        check(SyncHttpOperation.PULL, 422, "cursor_expired", SyncApiException.RequestRejected::class.java)
        check(SyncHttpOperation.PULL, 503, "cursor_expired", SyncApiException.Transient::class.java)
        check(SyncHttpOperation.PULL, 401, "cursor_expired", SyncApiException.AuthenticationRequired::class.java)
        val rejected = classifySyncHttpFailure(SyncHttpOperation.PUSH, 422, "future_unknown_code_é_  ", emptyMap())
        assertTrue(rejected is SyncApiException.RequestRejected)
        assertEquals("future_unknown_code_é_  ", (rejected as SyncApiException.RequestRejected).errorCode)
        check(SyncHttpOperation.PUSH, 422, "", SyncApiException.MalformedResponse::class.java)
    }

    @Test
    fun `adapter response status context body hint table on all operations`() = runTest {
        // 413 authoritative with no body read even when body carries another code.
        for (op in listOf("push", "pull", "snapshot")) {
            val factory = ScriptedFactory(statusCode = 413, bodyText = """{"errorCode":"cursor_expired"}""")
            val api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
            try {
                when (op) {
                    "push" -> api.push(emptyList())
                    "pull" -> api.pull(null, 100)
                    else -> api.snapshot(null, 200)
                }
                fail("expected RequestTooLarge for $op")
            } catch (e: SyncApiException.RequestTooLarge) {
            }
            assertEquals(1, factory.callCount.get())
        }
        // 429/listed 5xx are Transient with hint, status authoritative over body code.
        val withHint = mapOf("Retry-After" to "2")
        for (code in listOf(429, 500, 502, 503, 504)) {
            val factory = ScriptedFactory(
                statusCode = code,
                bodyText = """{"errorCode":"cursor_expired"}""",
                extraHeaders = withHint,
            )
            val api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
            try {
                api.pull(null, 100)
                fail("expected Transient for $code")
            } catch (e: SyncApiException.Transient) {
                assertEquals(2000L, e.retryAfterMillis)
            }
        }
        // 401 explicit reauth vs malformed fallback.
        var factory = ScriptedFactory(statusCode = 401, bodyText = """{"errorCode":"reauthentication_required"}""")
        var api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.pull(null, 100)
            fail("expected ReauthenticationRequired")
        } catch (e: SyncApiException.ReauthenticationRequired) {
        }
        factory = ScriptedFactory(statusCode = 401, bodyText = "not-json{{{")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.pull(null, 100)
            fail("expected AuthenticationRequired fallback")
        } catch (e: SyncApiException.AuthenticationRequired) {
        }
        // Ordinary 4xx bad body -> Malformed.
        factory = ScriptedFactory(statusCode = 422, bodyText = "bad-body{{{")
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.push(emptyList())
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
        }
        // Genuine body I/O failure while reading ordinary 4xx remains Transient.
        factory = ScriptedFactory(statusCode = 422, bodyText = """{"errorCode":"x"}""", failure = IOException("offline"))
        api = apiWith(provider = providerReturning(BoundServiceAccessToken(bindingA, tokenA)), factory = factory)
        try {
            api.pull(null, 100)
            fail("expected Transient for I/O")
        } catch (e: SyncApiException.Transient) {
            assertTrue(e.cause == null)
        }
        // Cancellation unchanged through response mapping.
        val cancelFactory = ScriptedFactory(statusCode = 200, bodyText = """{"results":[]}""", failure = null)
        val sentinel = CancellationException("io-cancel")
        val cancelApi = apiWith(
            provider = BoundServiceAccessTokenProvider { throw sentinel },
            factory = cancelFactory,
        )
        try {
            cancelApi.snapshot(null, 200)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertTrue(e === sentinel)
        }
        assertEquals(0, cancelFactory.callCount.get())
    }

    @Test
    fun `transient hint caps from headers`() {
        fun transientWith(retryAfter: String?): SyncApiException.Transient {
            val headers = if (retryAfter == null) emptyMap() else mapOf("Retry-After" to listOf(retryAfter))
            val got = classifySyncHttpFailure(SyncHttpOperation.PULL, 503, null, headers)
            assertTrue(got is SyncApiException.Transient)
            return got as SyncApiException.Transient
        }
        assertEquals(1000L, transientWith("1").retryAfterMillis)
        assertEquals(299000L, transientWith("299").retryAfterMillis)
        assertEquals(300000L, transientWith("300").retryAfterMillis)
        assertEquals(300000L, transientWith("9999999999999999999999").retryAfterMillis)
        assertEquals(null, transientWith(null).retryAfterMillis)
        assertEquals(null, transientWith("0").retryAfterMillis)
        assertEquals(null, transientWith("-5").retryAfterMillis)
        assertEquals(null, transientWith("1.5").retryAfterMillis)
        assertEquals(null, transientWith("not-a-number").retryAfterMillis)
    }

    private fun assertFailsPreflight(block: () -> Unit) {
        try {
            block()
            fail("expected preflight SyncModelException")
        } catch (e: SyncModelException) {
            assertTrue(e.cause == null)
        }
    }
}
