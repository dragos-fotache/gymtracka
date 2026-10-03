package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.backend.HttpGymSyncApi
import com.dragosfotache.gymtracka.data.sync.backend.JdkSyncHttpTransport
import com.dragosfotache.gymtracka.data.sync.backend.SyncHttpResponse
import com.dragosfotache.gymtracka.data.sync.backend.SyncHttpTransport
import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Offline tests for the test-only HTTP adapter: production envelope validation,
 * exact method/path/query/header/body mapping, outcome count/order fail-closed
 * behavior, safe error mapping, bounded retry hints, cancellation propagation,
 * and secret hygiene. Every transport here is scripted; no backend or config
 * is touched, so these tests run in the ordinary offline JVM suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpSyncApiTest {
    companion object {
        const val TOKEN = "test-access-token"
        const val ROOT_1 = "11111111-1111-1111-1111-111111111111"
        const val SET_1 = "66666666-6666-6666-6666-666666666661"
    }

    private data class RecordedCall(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String?,
        val authorization: String,
    )

    private class ScriptedTransport(val handler: (RecordedCall) -> SyncHttpResponse) : SyncHttpTransport {
        val calls = mutableListOf<RecordedCall>()

        override suspend fun exchange(
            method: String,
            path: String,
            query: Map<String, String>,
            headers: Map<String, String>,
            body: String?,
            authorization: String,
        ): SyncHttpResponse {
            val call = RecordedCall(method, path, query.toMap(), headers.toMap(), body, authorization)
            calls += call
            return handler(call)
        }
    }

    private fun validRequest(index: Int): DurableRequestModel {
        val mutationId = "12345678-1234-1234-1234-%012x".format(index)
        return captureDurableRequest(
            MutationEnvelopeModel(
                mutationId = mutationId,
                kind = MutationKind.WORKOUT_DELETE_SET,
                aggregateType = AggregateType.WORKOUT,
                aggregateId = ROOT_1,
                localGeneration = 1L,
                knownServerRevision = null,
                payload = MutationPayload.WorkoutDeleteSet(SET_1),
            ),
            listOf(AffectedRootModel(AggregateType.WORKOUT, ROOT_1, 1L)),
        )
    }

    /** Rejected outcomes need only an error code, so scripted envelopes stay small. */
    private fun rejectedResults(call: RecordedCall): String {
        val body: String = call.body ?: throw AssertionError("push requires a body")
        val ids = JSONObject(body)
            .getJSONArray("mutations")
            .let { array -> List(array.length()) { array.getJSONObject(it).getString("mutationId") } }
        val outcomes = ids.joinToString(",") {
            "{\"mutationId\":\"$it\",\"status\":\"rejected\",\"errorCode\":\"not_found\",\"batch\":null}"
        }
        return "{\"results\":[$outcomes]}"
    }

    @Test
    fun pushPostsFrozenBodyWithBearerAuth() = runTest {
        val transport = ScriptedTransport { call -> SyncHttpResponse(200, emptyMap(), rejectedResults(call)) }
        val api = HttpGymSyncApi(transport, TOKEN)

        val request = validRequest(1)
        val result = api.push(listOf(request))

        assertEquals(1, transport.calls.size)
        val call = transport.calls[0]
        assertEquals("POST", call.method)
        assertEquals("/v1/sync/push", call.path)
        assertTrue(call.query.isEmpty())
        assertEquals("application/json", call.headers["Content-Type"])
        assertEquals("Bearer $TOKEN", call.authorization)
        // Frozen request bytes travel unchanged inside the mutations array.
        assertEquals("{\"mutations\":[" + request.requestJson + "]}", call.body)
        assertEquals(1, result.results.size)
        assertEquals(request.envelope.mutationId, result.results[0].mutationId)
    }

    @Test
    fun pushAcceptsEmptyBatchEnvelope() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(200, emptyMap(), "{\"results\":[]}") }
        val api = HttpGymSyncApi(transport, TOKEN)

        assertEquals(0, api.push(emptyList()).results.size)
        assertEquals("{\"mutations\":[]}", transport.calls.single().body)
    }

    @Test
    fun pushAcceptsOneHundredRequests() = runTest {
        val transport = ScriptedTransport { call -> SyncHttpResponse(200, emptyMap(), rejectedResults(call)) }
        val api = HttpGymSyncApi(transport, TOKEN)

        val requests = List(100) { validRequest(it) }
        assertEquals(100, api.push(requests).results.size)
    }

    @Test
    fun pushRejectsOverOneHundredRequestsBeforeTransport() = runTest {
        var transported = false
        val transport = ScriptedTransport {
            transported = true
            SyncHttpResponse(200, emptyMap(), "{\"results\":[]}")
        }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.push(List(101) { validRequest(it) })
            fail("expected SyncModelException")
        } catch (e: SyncModelException) {
            // Expected: client-side envelope validation.
        }
        assertFalse(transported)
    }

    @Test
    fun pushRejectsDuplicateMutationIdsBeforeTransport() = runTest {
        var transported = false
        val transport = ScriptedTransport {
            transported = true
            SyncHttpResponse(200, emptyMap(), "{\"results\":[]}")
        }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.push(listOf(validRequest(1), validRequest(1)))
            fail("expected SyncModelException")
        } catch (e: SyncModelException) {
            // Expected.
        }
        assertFalse(transported)
    }

    @Test
    fun pushFailsClosedOnOutcomeCountMismatch() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(200, emptyMap(), "{\"results\":[]}") }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.push(listOf(validRequest(1)))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            // Expected: one request in, zero outcomes out.
        }
    }

    @Test
    fun pushFailsClosedOnOutcomeOrderMismatch() = runTest {
        val first = validRequest(1)
        val second = validRequest(2)
        val swapped = "{\"results\":[" +
            "{\"mutationId\":\"${second.envelope.mutationId}\",\"status\":\"rejected\"," +
            "\"errorCode\":\"not_found\",\"batch\":null}," +
            "{\"mutationId\":\"${first.envelope.mutationId}\",\"status\":\"rejected\"," +
            "\"errorCode\":\"not_found\",\"batch\":null}]}"
        val transport = ScriptedTransport { SyncHttpResponse(200, emptyMap(), swapped) }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.push(listOf(first, second))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            // Expected: outcomes must match submitted order.
        }
    }

    @Test
    fun pushFailsClosedOnMalformedSuccessBody() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(200, emptyMap(), "{\"results\":[}") }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.push(listOf(validRequest(1)))
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            // Expected: undecodable 200 never surfaces raw text.
        }
    }

    @Test
    fun pullEncodesCursorVerbatimAndCoercesLimits() = runTest {
        val seen = mutableListOf<RecordedCall>()
        val transport = ScriptedTransport { call ->
            seen += call
            SyncHttpResponse(200, emptyMap(), "{\"batches\":[],\"nextCursor\":\"n\",\"hasMore\":false}")
        }
        val api = HttpGymSyncApi(transport, TOKEN)
        val opaque = "opaque cursor/with?reserved&chars="

        api.pull(opaque, 5)
        api.pull(null, 10_000)
        api.pull(null, -3)

        assertEquals("/v1/sync/pull", seen[0].path)
        assertEquals("GET", seen[0].method)
        assertEquals(opaque, seen[0].query["cursor"])
        assertEquals("5", seen[0].query["limit"])
        assertEquals("Bearer $TOKEN", seen[0].authorization)
        assertEquals(null, seen[0].body)
        assertFalse(seen[1].query.containsKey("cursor"))
        assertEquals("200", seen[1].query["limit"])
        assertEquals("1", seen[2].query["limit"])
    }

    @Test
    fun snapshotEncodesTokenVerbatimAndCoercesLimits() = runTest {
        val seen = mutableListOf<RecordedCall>()
        val transport = ScriptedTransport { call ->
            seen += call
            SyncHttpResponse(
                200,
                emptyMap(),
                "{\"records\":[],\"baselineCursor\":\"b\",\"nextPageToken\":null,\"hasMore\":false}",
            )
        }
        val api = HttpGymSyncApi(transport, TOKEN)
        val opaque = "opaque token/with?reserved&chars="

        api.snapshot(opaque, 7)
        api.snapshot(null, 10_000)

        assertEquals("/v1/sync/snapshot", seen[0].path)
        assertEquals("GET", seen[0].method)
        assertEquals(opaque, seen[0].query["pageToken"])
        assertEquals("7", seen[0].query["limit"])
        assertEquals("500", seen[1].query["limit"])
        assertFalse(seen[1].query.containsKey("pageToken"))
    }

    @Test
    fun urlEncodingEscapesReservedQueryCharacters() = runTest {
        val transport = JdkSyncHttpTransport("http://127.0.0.1:18080")
        val built = transport.buildUrl(
            "http://127.0.0.1:18080",
            "/v1/sync/pull",
            mapOf("cursor" to "a b+c&d=e", "limit" to "5"),
        )
        assertEquals("http://127.0.0.1:18080/v1/sync/pull?cursor=a+b%2Bc%26d%3De&limit=5", built)
    }

    @Test
    fun jdkTransportRefusesNonLoopbackOrigins() {
        try {
            JdkSyncHttpTransport("https://example.com")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun mapsAuthenticationRequired() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(401, emptyMap(), "{\"errorCode\":\"authentication_required\"}") }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.push(listOf(validRequest(1)))
            fail("expected AuthenticationRequired")
        } catch (e: SyncApiException.AuthenticationRequired) {
            // Expected.
        }
    }

    @Test
    fun mapsCursorExpiredOnlyForPull() = runTest {
        val expired = SyncHttpResponse(410, emptyMap(), "{\"errorCode\":\"cursor_expired\",\"fullResyncRequired\":true}")
        val api = HttpGymSyncApi(ScriptedTransport { expired }, TOKEN)

        try {
            api.pull("stale", 10)
            fail("expected CursorExpired")
        } catch (e: SyncApiException.CursorExpired) {
            // Expected.
        }
        try {
            api.snapshot("stale", 10)
            fail("expected RequestRejected for snapshot expiry")
        } catch (e: SyncApiException.RequestRejected) {
            assertEquals("cursor_expired", e.errorCode)
        }
    }

    @Test
    fun mapsSnapshotExpiredNotCursor() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(410, emptyMap(), "{\"errorCode\":\"snapshot_expired\"}") }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.snapshot("stale", 10)
            fail("expected SnapshotExpired")
        } catch (_: SyncApiException.SnapshotExpired) {
            // Snapshot expiry is distinct from invalid tokens and pull-cursor expiry.
        }
    }

    @Test
    fun mapsPermanentRejectionWithServiceCode() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(400, emptyMap(), "{\"errorCode\":\"future_permanent_code\"}") }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.pull("bogus", 10)
            fail("expected RequestRejected")
        } catch (e: SyncApiException.RequestRejected) {
            assertEquals("future_permanent_code", e.errorCode)
        }
    }

    @Test
    fun mapsBlankErrorEnvelopeAsMalformed() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(400, emptyMap(), "") }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.pull(null, 10)
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            // Expected.
        }
    }

    @Test
    fun mapsThrottlingAndServerFaultsToBoundedTransient() = runTest {
        val limited = ScriptedTransport {
            SyncHttpResponse(429, mapOf("Retry-After" to listOf("120")), "{\"errorCode\":\"rate_limited\"}")
        }
        try {
            HttpGymSyncApi(limited, TOKEN).push(listOf(validRequest(1)))
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertEquals(120_000L, e.retryAfterMillis)
        }

        val capped = ScriptedTransport {
            SyncHttpResponse(503, mapOf("Retry-After" to listOf("3600")), "{\"errorCode\":\"temporarily_unavailable\"}")
        }
        try {
            HttpGymSyncApi(capped, TOKEN).pull(null, 10)
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertEquals(300_000L, e.retryAfterMillis)
        }

        val bare = ScriptedTransport { SyncHttpResponse(500, emptyMap(), "{\"errorCode\":\"internal_error\"}") }
        try {
            HttpGymSyncApi(bare, TOKEN).snapshot(null, 10)
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertEquals(null, e.retryAfterMillis)
        }
    }

    @Test
    fun mapsRawTransportFailuresToTransientWithoutCause() = runTest {
        val transport = SyncHttpTransport { _, _, _, _, _, _ ->
            throw java.io.IOException("simulated offline")
        }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.push(listOf(validRequest(1)))
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertEquals(null, e.cause)
            assertFalse(e.message.orEmpty().contains("offline"))
        }
    }

    @Test
    fun cancellationPropagatesWithoutConversion() = runTest {
        val entered = CompletableDeferred<Unit>()
        val hanging = SyncHttpTransport { _, _, _, _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        val api = HttpGymSyncApi(hanging, TOKEN)

        val deferred = async { api.pull(null, 10) }
        // Cancel only after the transport is confirmed in-flight.
        entered.await()
        assertTrue(entered.isCompleted)
        deferred.cancelAndJoin()
        val thrown: Throwable? = try {
            deferred.await()
            null
        } catch (e: CancellationException) {
            e
        }
        assertTrue(thrown is CancellationException)
        // Any CancellationException subtype is fine; a converted SyncApiException must never appear.
        assertTrue(thrown !is SyncApiException)
    }

    @Test
    fun jdkTransportCancellationCancelsInFlightSend() = runTest {
        // Local loopback server exercising the real JDK future path: the
        // handler blocks until released, so the client send is genuinely
        // in-flight when cancelled. No sleeps; latch bounds the wait.
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/v1/sync/pull") { exchange ->
            entered.complete(Unit)
            release.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val body = "{\"batches\":[],\"nextCursor\":\"n\",\"hasMore\":false}"
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        server.start()
        try {
            val api = HttpGymSyncApi(JdkSyncHttpTransport("http://127.0.0.1:" + server.address.port), TOKEN)
            val deferred = async { api.pull(null, 10) }
            entered.await()
            deferred.cancelAndJoin()
            val thrown: Throwable? = try {
                deferred.await()
                null
            } catch (e: CancellationException) {
                e
            }
            assertTrue(thrown is CancellationException)
            assertTrue(thrown !is SyncApiException)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun retryAfterSaturatesBeforeScaling() = runTest {
        suspend fun transientFor(header: String): SyncApiException.Transient {
            val transport = ScriptedTransport {
                SyncHttpResponse(429, mapOf("Retry-After" to listOf(header)), "{\"errorCode\":\"rate_limited\"}")
            }
            try {
                HttpGymSyncApi(transport, TOKEN).push(listOf(validRequest(1)))
                fail("expected Transient")
                throw AssertionError("unreachable")
            } catch (e: SyncApiException.Transient) {
                return e
            }
        }

        // Long.MAX_VALUE seconds must saturate, never overflow to negative.
        assertEquals(300_000L, transientFor(Long.MAX_VALUE.toString()).retryAfterMillis)
        assertEquals(300_000L, transientFor("1000000").retryAfterMillis)
        assertEquals(120_000L, transientFor("120").retryAfterMillis)
        assertEquals(0L, transientFor("0").retryAfterMillis)
        assertEquals(null, transientFor("-5").retryAfterMillis)
        assertEquals(null, transientFor("soon").retryAfterMillis)
        assertEquals(null, transientFor("12.5").retryAfterMillis)
    }

    @Test
    fun nonStringErrorCodeIsTreatedAsAbsent() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(400, emptyMap(), "{\"errorCode\":404}") }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.pull(null, 10)
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            // Expected: a numeric code coerces nowhere.
        }
    }

    @Test
    fun duplicateKeyErrorEnvelopeIsRejected() = runTest {
        val transport = ScriptedTransport {
            SyncHttpResponse(400, emptyMap(), "{\"errorCode\":\"not_found\",\"errorCode\":\"not_found\"}")
        }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.pull(null, 10)
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            // Expected: duplicate keys fail strict envelope parsing.
        }
    }

    @Test
    fun escapedDuplicateKeyErrorEnvelopeIsRejected() = runTest {
        // Double backslash keeps the escape on the wire: same key twice under
        // different spellings ("errorCode" and "error\u0043ode").
        val transport = ScriptedTransport {
            SyncHttpResponse(400, emptyMap(), "{\"errorCode\":\"not_found\",\"error\\u0043ode\":\"other\"}")
        }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.pull(null, 10)
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            // Expected: escape-variant duplicates resolve to neither copy.
        }
    }

    @Test
    fun secretLikeErrorCodeNeverReachesExceptions() = runTest {
        val planted = "Bearer planted-secret-token"
        val transport = ScriptedTransport {
            SyncHttpResponse(400, emptyMap(), "{\"errorCode\":\"" + planted + "\"}")
        }
        val api = HttpGymSyncApi(transport, TOKEN)

        try {
            api.pull(null, 10)
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertFalse(e.toString().contains(planted))
            assertFalse(e.message.orEmpty().contains(planted))
        }
    }

    @Test
    fun responseToStringNeverLeaksBodyOrSecrets() {
        val response = SyncHttpResponse(
            200,
            mapOf("Authorization" to listOf("Bearer planted")),
            "{\"secret\":\"planted-body\"}",
        )
        val text = response.toString()
        assertFalse(text.contains("planted-body"))
        assertFalse(text.contains("Bearer planted"))
        assertTrue(text.contains("200"))
    }

    @Test
    fun failuresUseFixedSafeTextAndBoundedHints() {
        val transient = SyncApiException.Transient(600_000L)
        assertEquals(300_000L, transient.retryAfterMillis)
        assertFalse(transient.message.orEmpty().contains("600000"))

        val rejected = SyncApiException.RequestRejected("not_found")
        assertEquals("not_found", rejected.errorCode)
        assertFalse(rejected.message.orEmpty().contains("Bearer"))
    }

    @Test
    fun adapterToStringNeverLeaksBearerToken() {
        val api = HttpGymSyncApi(ScriptedTransport { SyncHttpResponse(200, emptyMap(), "{}") }, TOKEN)
        assertFalse(api.toString().contains(TOKEN))
    }
}
