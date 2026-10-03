package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.api.DEFAULT_PULL_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.DEFAULT_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.MAX_PULL_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MAX_PUSH_REQUESTS
import com.dragosfotache.gymtracka.data.sync.api.MAX_RETRY_AFTER_MILLIS
import com.dragosfotache.gymtracka.data.sync.api.MAX_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MIN_PULL_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MIN_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.api.boundedPullLimit
import com.dragosfotache.gymtracka.data.sync.api.boundedSnapshotLimit
import com.dragosfotache.gymtracka.data.sync.backend.HttpGymSyncApi
import com.dragosfotache.gymtracka.data.sync.backend.SyncHttpResponse
import com.dragosfotache.gymtracka.data.sync.backend.SyncHttpTransport
import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Boundary tests for the sync transport surface: the approved push
 * envelope over real valid distinct durable requests, OpenAPI page limits,
 * verbatim opaque argument recording, cancellation propagation, and fixed
 * failure text with bounded retry hints. Pushes run through the test-only
 * HTTP adapter against a scripted transport; pull and snapshot return canned
 * valid pages so recording assertions execute without a backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncApiBoundaryTest {
    companion object {
        const val ACCOUNT_TOKEN = "boundary-test-token"
        const val ROOT_1 = "11111111-1111-1111-1111-111111111111"
        const val SET_1 = "66666666-6666-6666-6666-666666666661"
    }

    private data class RecordedCall(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String?,
        val authorization: String,
    )

    private class ScriptedTransport(val handler: (RecordedCall) -> SyncHttpResponse) : SyncHttpTransport {
        val calls = mutableListOf<RecordedCall>()
        var transported = false

        override suspend fun exchange(
            method: String,
            path: String,
            query: Map<String, String>,
            headers: Map<String, String>,
            body: String?,
            authorization: String,
        ): SyncHttpResponse {
            transported = true
            val call = RecordedCall(method, path, query.toMap(), body, authorization)
            calls += call
            return handler(call)
        }
    }

    private fun api(transport: ScriptedTransport) = HttpGymSyncApi(transport, ACCOUNT_TOKEN)

    /** Rejected outcomes echo the submitted IDs so count/order checks execute. */
    private fun rejectedResults(call: RecordedCall): String {
        val body = call.body ?: throw AssertionError("push requires a body")
        val array = JSONObject(body).getJSONArray("mutations")
        val outcomes = List(array.length()) { index ->
            val id = array.getJSONObject(index).getString("mutationId")
            "{\"mutationId\":\"$id\",\"status\":\"rejected\",\"errorCode\":\"not_found\",\"batch\":null}"
        }
        return "{\"results\":[" + outcomes.joinToString(",") + "]}"
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

    @Test
    fun pushAcceptsEmptyBatchEnvelope() = runTest {
        // Empty is valid per encodePushMutationsBody/OpenAPI (no minItems).
        val transport = ScriptedTransport { SyncHttpResponse(200, emptyMap(), "{\"results\":[]}") }
        val result = api(transport).push(emptyList())
        assertTrue(result.results.isEmpty())
        assertEquals("{\"mutations\":[]}", transport.calls.single().body)
        assertEquals("Bearer $ACCOUNT_TOKEN", transport.calls.single().authorization)
    }

    @Test
    fun pushAcceptsOneHundredDistinctValidRequests() = runTest {
        val transport = ScriptedTransport { call -> SyncHttpResponse(200, emptyMap(), rejectedResults(call)) }
        // Structurally valid requests pass client validation and reach the
        // transport; per-mutation outcomes arrive in submitted order.
        val result = api(transport).push(List(MAX_PUSH_REQUESTS) { validRequest(it) })
        assertEquals(MAX_PUSH_REQUESTS, result.results.size)
        for (outcome in result.results) {
            assertEquals(MutationStatus.REJECTED, outcome.status)
            assertEquals("not_found", outcome.errorCode)
            assertNull(outcome.batch)
        }
        assertEquals(100, MAX_PUSH_REQUESTS)
        assertTrue(transport.calls.single().body!!.contains("\"mutations\":["))
    }

    @Test
    fun pushRejectsOverOneHundredDistinctValidRequests() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(200, emptyMap(), "{\"results\":[]}") }
        try {
            api(transport).push(List(MAX_PUSH_REQUESTS + 1) { validRequest(it) })
            fail("Expected a 101-request push batch to be rejected")
        } catch (e: SyncModelException) {
            assertTrue(e.message!!.contains("100"))
        }
        assertFalse(transport.transported)
    }

    @Test
    fun pushRejectsDuplicateMutationIds() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(200, emptyMap(), "{\"results\":[]}") }
        try {
            api(transport).push(listOf(validRequest(7), validRequest(7)))
            fail("Expected duplicate mutation IDs to be rejected")
        } catch (e: SyncModelException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
        assertFalse(transport.transported)
    }

    @Test
    fun pushRejectsMalformedWrapper() = runTest {
        val transport = ScriptedTransport { SyncHttpResponse(200, emptyMap(), "{\"results\":[]}") }
        val tampered = validRequest(3).copy(requestJson = "{\"tampered\":true}")
        try {
            api(transport).push(listOf(tampered))
            fail("Expected a text-mismatched wrapper to be rejected")
        } catch (_: SyncModelException) {
        }
        assertFalse(transport.transported)
    }

    @Test
    fun pageLimitsMatchOpenApiBounds() {
        assertEquals(1, MIN_PULL_LIMIT)
        assertEquals(200, MAX_PULL_LIMIT)
        assertEquals(100, DEFAULT_PULL_LIMIT)
        assertEquals(1, MIN_SNAPSHOT_LIMIT)
        assertEquals(500, MAX_SNAPSHOT_LIMIT)
        assertEquals(200, DEFAULT_SNAPSHOT_LIMIT)
        assertEquals(1, boundedPullLimit(0))
        assertEquals(200, boundedPullLimit(10_000))
        assertEquals(100, boundedPullLimit(100))
        assertEquals(1, boundedSnapshotLimit(-3))
        assertEquals(500, boundedSnapshotLimit(10_000))
        assertEquals(200, boundedSnapshotLimit(200))
    }

    @Test
    fun opaquePullArgumentsAreRecordedVerbatim() = runTest {
        val transport = ScriptedTransport {
            SyncHttpResponse(200, emptyMap(), "{\"batches\":[],\"nextCursor\":\"n\",\"hasMore\":false}")
        }
        val opaque = "opaque::99/batches?x=1"
        api(transport).pull(opaque, 5)
        api(transport).pull(null)
        assertEquals(
            listOf(
                RecordedCall("GET", "/v1/sync/pull", mapOf("cursor" to opaque, "limit" to "5"), null, "Bearer $ACCOUNT_TOKEN"),
                RecordedCall("GET", "/v1/sync/pull", mapOf("limit" to DEFAULT_PULL_LIMIT.toString()), null, "Bearer $ACCOUNT_TOKEN"),
            ),
            transport.calls,
        )
    }

    @Test
    fun opaqueSnapshotArgumentsAreRecordedVerbatim() = runTest {
        val transport = ScriptedTransport {
            SyncHttpResponse(
                200,
                emptyMap(),
                "{\"records\":[],\"baselineCursor\":\"b\",\"nextPageToken\":null,\"hasMore\":false}",
            )
        }
        val opaque = "snapshot-token::7"
        api(transport).snapshot(opaque, 7)
        api(transport).snapshot(null)
        assertEquals(
            listOf(
                RecordedCall("GET", "/v1/sync/snapshot", mapOf("pageToken" to opaque, "limit" to "7"), null, "Bearer $ACCOUNT_TOKEN"),
                RecordedCall("GET", "/v1/sync/snapshot", mapOf("limit" to DEFAULT_SNAPSHOT_LIMIT.toString()), null, "Bearer $ACCOUNT_TOKEN"),
            ),
            transport.calls,
        )
    }

    @Test
    fun cancellationPropagatesWithoutConversion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : GymSyncApi {
            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                gate.await()
                throw AssertionError("unreachable")
            }

            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                gate.await()
                throw AssertionError("unreachable")
            }

            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                gate.await()
                throw AssertionError("unreachable")
            }
        }
        val deferred = async { api.pull(null) }
        runCurrent()
        deferred.cancel()
        try {
            deferred.await()
            fail("Expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertFalse(SyncApiException::class.java.isInstance(e))
        }
    }

    @Test
    fun failuresUseFixedSafeTextAndBoundedHints() {
        assertEquals(300_000L, MAX_RETRY_AFTER_MILLIS)
        assertEquals(1_500L, SyncApiException.Transient(1_500L).retryAfterMillis)
        assertNull(SyncApiException.Transient().retryAfterMillis)
        assertEquals(0L, SyncApiException.Transient(-5L).retryAfterMillis)
        assertEquals(MAX_RETRY_AFTER_MILLIS, SyncApiException.Transient(Long.MAX_VALUE).retryAfterMillis)
        val failures = listOf(
            SyncApiException.Transient() to
                ("transient" to "transient sync failure; retry with bounded backoff"),
            SyncApiException.AuthenticationRequired() to
                ("authentication_required" to "service access authentication required"),
            SyncApiException.CursorExpired() to
                ("cursor_expired" to "change cursor expired; full resync required"),
            SyncApiException.RequestRejected("not_found") to
                ("request_rejected" to "permanent top-level request rejection"),
            SyncApiException.MalformedResponse() to
                ("malformed_response" to "malformed or inconsistent protocol response"),
        )
        for ((failure, expected) in failures) {
            assertEquals(expected.first, failure.code)
            assertEquals(expected.second, failure.message)
            assertNull(failure.cause)
            try {
                failure.initCause(RuntimeException("raw-credential-body"))
                fail("Expected a raw cause to be refused for ${failure.code}")
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals(
            "not_found",
            (failures[3].first as SyncApiException.RequestRejected).errorCode,
        )
    }
}
