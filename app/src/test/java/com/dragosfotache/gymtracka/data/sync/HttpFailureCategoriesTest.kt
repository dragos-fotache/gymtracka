package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.backend.HttpGymSyncApi
import com.dragosfotache.gymtracka.data.sync.backend.SyncHttpResponse
import com.dragosfotache.gymtracka.data.sync.backend.SyncHttpTransport
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HttpFailureCategoriesTest {

    private val accessToken = "r02c-test-access-token"

    private fun response(statusCode: Int, body: String): SyncHttpResponse {
        return SyncHttpResponse(statusCode, emptyMap(), body)
    }

    private fun transportReturning(next: SyncHttpResponse): SyncHttpTransport {
        return SyncHttpTransport { _, _, _, _, _, _ -> next }
    }

    private fun errorBody(code: String): String {
        return """{"errorCode":"$code"}"""
    }

    private suspend fun capturePushError(next: SyncHttpResponse): SyncApiException {
        val api = HttpGymSyncApi(transportReturning(next), accessToken)
        try {
            api.push(emptyList())
        } catch (failure: SyncApiException) {
            return failure
        }
        fail("push should throw SyncApiException for status ${next.statusCode}")
        throw AssertionError("unreachable")
    }

    private suspend fun capturePullError(next: SyncHttpResponse): SyncApiException {
        val api = HttpGymSyncApi(transportReturning(next), accessToken)
        try {
            api.pull(null)
        } catch (failure: SyncApiException) {
            return failure
        }
        fail("pull should throw SyncApiException for status ${next.statusCode}")
        throw AssertionError("unreachable")
    }

    private suspend fun captureSnapshotError(next: SyncHttpResponse): SyncApiException {
        val api = HttpGymSyncApi(transportReturning(next), accessToken)
        try {
            api.snapshot(null)
        } catch (failure: SyncApiException) {
            return failure
        }
        fail("snapshot should throw SyncApiException for status ${next.statusCode}")
        throw AssertionError("unreachable")
    }

    // Table: 401 with parsed reauthentication_required on push/pull/snapshot.
    @Test
    fun push401ReauthenticationCode_isReauthenticationRequired() = runTest {
        val failure = capturePushError(response(401, errorBody("reauthentication_required")))
        assertTrue(failure is SyncApiException.ReauthenticationRequired)
        assertEquals("reauthentication_required", failure.code)
        assertEquals("foreground reauthentication required", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun pull401ReauthenticationCode_isReauthenticationRequired() = runTest {
        val failure = capturePullError(response(401, errorBody("reauthentication_required")))
        assertTrue(failure is SyncApiException.ReauthenticationRequired)
        assertEquals("foreground reauthentication required", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot401ReauthenticationCode_isReauthenticationRequired() = runTest {
        val failure = captureSnapshotError(response(401, errorBody("reauthentication_required")))
        assertTrue(failure is SyncApiException.ReauthenticationRequired)
        assertEquals("foreground reauthentication required", failure.message)
        assertNull(failure.cause)
    }

    // Table: other 401 including absent/malformed body is conservative access rejection.
    @Test
    fun push401AbsentBody_isAuthenticationRequired() = runTest {
        val failure = capturePushError(response(401, ""))
        assertTrue(failure is SyncApiException.AuthenticationRequired)
        assertEquals("authentication_required", failure.code)
        assertEquals("service access authentication required", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun pull401MalformedBody_isAuthenticationRequired() = runTest {
        val failure = capturePullError(response(401, """{oops"""))
        assertTrue(failure is SyncApiException.AuthenticationRequired)
        assertEquals("service access authentication required", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot401OtherCode_isAuthenticationRequired() = runTest {
        val failure = captureSnapshotError(response(401, errorBody("authentication_required")))
        assertTrue(failure is SyncApiException.AuthenticationRequired)
        assertFalse(failure is SyncApiException.ReauthenticationRequired)
        assertNull(failure.cause)
    }

    // Table: correctly routed read-token codes.
    @Test
    fun pull400InvalidCursor_isInvalidCursor() = runTest {
        val failure = capturePullError(response(400, errorBody("invalid_cursor")))
        assertTrue(failure is SyncApiException.InvalidCursor)
        assertEquals("invalid_cursor", failure.code)
        assertEquals("change cursor is invalid", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun pull410CursorExpired_isCursorExpired() = runTest {
        val failure = capturePullError(response(410, errorBody("cursor_expired")))
        assertTrue(failure is SyncApiException.CursorExpired)
        assertEquals("cursor_expired", failure.code)
        assertEquals("change cursor expired; full resync required", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot400InvalidSnapshotToken_isInvalidSnapshotToken() = runTest {
        val failure = captureSnapshotError(response(400, errorBody("invalid_snapshot_token")))
        assertTrue(failure is SyncApiException.InvalidSnapshotToken)
        assertEquals("invalid_snapshot_token", failure.code)
        assertEquals("snapshot token is invalid", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot410SnapshotExpired_isSnapshotExpired() = runTest {
        val failure = captureSnapshotError(response(410, errorBody("snapshot_expired")))
        assertTrue(failure is SyncApiException.SnapshotExpired)
        assertEquals("snapshot_expired", failure.code)
        assertEquals("snapshot expired; discard staging and restart", failure.message)
        assertNull(failure.cause)
    }

    // Table: read-token code on wrong operation remains RequestRejected, never expiry.
    @Test
    fun push400InvalidCursor_isRequestRejected() = runTest {
        val failure = capturePushError(response(400, errorBody("invalid_cursor")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("invalid_cursor", (failure as SyncApiException.RequestRejected).errorCode)
        assertEquals("request_rejected", failure.code)
        assertNull(failure.cause)
    }

    @Test
    fun pull400InvalidSnapshotToken_isRequestRejected() = runTest {
        val failure = capturePullError(response(400, errorBody("invalid_snapshot_token")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("invalid_snapshot_token", (failure as SyncApiException.RequestRejected).errorCode)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot400InvalidCursor_isRequestRejected() = runTest {
        val failure = captureSnapshotError(response(400, errorBody("invalid_cursor")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("invalid_cursor", (failure as SyncApiException.RequestRejected).errorCode)
        assertNull(failure.cause)
    }

    @Test
    fun pull410SnapshotExpired_isRequestRejected() = runTest {
        val failure = capturePullError(response(410, errorBody("snapshot_expired")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("snapshot_expired", (failure as SyncApiException.RequestRejected).errorCode)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot410CursorExpired_isRequestRejected() = runTest {
        val failure = captureSnapshotError(response(410, errorBody("cursor_expired")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("cursor_expired", (failure as SyncApiException.RequestRejected).errorCode)
        assertNull(failure.cause)
    }

    // Table: wrong ordinary 4xx status with recognizable code remains RequestRejected.
    @Test
    fun pull422CursorExpired_isRequestRejected() = runTest {
        val failure = capturePullError(response(422, errorBody("cursor_expired")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("cursor_expired", (failure as SyncApiException.RequestRejected).errorCode)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot422SnapshotExpired_isRequestRejected() = runTest {
        val failure = captureSnapshotError(response(422, errorBody("snapshot_expired")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("snapshot_expired", (failure as SyncApiException.RequestRejected).errorCode)
        assertNull(failure.cause)
    }

    @Test
    fun pull400CursorExpiredWrongStatus_isRequestRejected() = runTest {
        val failure = capturePullError(response(400, errorBody("cursor_expired")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("cursor_expired", (failure as SyncApiException.RequestRejected).errorCode)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot400SnapshotExpiredWrongStatus_isRequestRejected() = runTest {
        val failure = captureSnapshotError(response(400, errorBody("snapshot_expired")))
        assertTrue(failure is SyncApiException.RequestRejected)
        assertEquals("snapshot_expired", (failure as SyncApiException.RequestRejected).errorCode)
        assertNull(failure.cause)
    }

    // Table: status precedence keeps authoritative categories over mismatched token codes.
    @Test
    fun pull401CursorExpired_isAuthenticationRequiredByPrecedence() = runTest {
        val failure = capturePullError(response(401, errorBody("cursor_expired")))
        assertTrue(failure is SyncApiException.AuthenticationRequired)
        assertFalse(failure is SyncApiException.CursorExpired)
        assertNull(failure.cause)
    }

    @Test
    fun pull503CursorExpired_isTransientByPrecedence() = runTest {
        val failure = capturePullError(response(503, errorBody("cursor_expired")))
        assertTrue(failure is SyncApiException.Transient)
        assertEquals("transient", failure.code)
        assertNull(failure.cause)
    }

    @Test
    fun pull429InvalidCursor_isTransientByPrecedence() = runTest {
        val failure = capturePullError(response(429, errorBody("invalid_cursor")))
        assertTrue(failure is SyncApiException.Transient)
        assertNull(failure.cause)
    }

    @Test
    fun push413CursorExpired_isRequestTooLargeByPrecedence() = runTest {
        val failure = capturePushError(response(413, errorBody("cursor_expired")))
        assertTrue(failure is SyncApiException.RequestTooLarge)
        assertEquals("request_too_large", failure.code)
        assertEquals("request too large; retain intent and require recovery", failure.message)
        assertNull(failure.cause)
    }

    // Table: 413 on any operation is status-authoritative.
    @Test
    fun push413ValidBody_isRequestTooLarge() = runTest {
        val failure = capturePushError(response(413, errorBody("request_too_large")))
        assertTrue(failure is SyncApiException.RequestTooLarge)
        assertNull(failure.cause)
    }

    @Test
    fun pull413MissingBody_isRequestTooLarge() = runTest {
        val failure = capturePullError(response(413, ""))
        assertTrue(failure is SyncApiException.RequestTooLarge)
        assertNull(failure.cause)
    }

    @Test
    fun snapshot413MalformedBody_isRequestTooLarge() = runTest {
        val failure = captureSnapshotError(response(413, """{broken"""))
        assertTrue(failure is SyncApiException.RequestTooLarge)
        assertNull(failure.cause)
    }

    // Table: 429 and retryable 5xx are bounded Transient without raw body/cause.
    @Test
    fun push429_isBoundedTransient() = runTest {
        val failure = capturePushError(response(429, errorBody("rate_limited")))
        assertTrue(failure is SyncApiException.Transient)
        assertEquals("transient sync failure; retry with bounded backoff", failure.message)
        assertNull(failure.cause)
        val hint = (failure as SyncApiException.Transient).retryAfterMillis
        assertTrue(hint == null || (hint in 0L..300000L))
    }

    @Test
    fun retryable5xx_areBoundedTransient() = runTest {
        val statuses = listOf(500, 502, 503, 504)
        for (status in statuses) {
            val failure = capturePullError(response(status, errorBody("temporarily_unavailable")))
            assertTrue("status $status", failure is SyncApiException.Transient)
            assertEquals("transient", failure.code)
            assertNull(failure.cause)
            val hint = (failure as SyncApiException.Transient).retryAfterMillis
            assertTrue("status $status hint $hint", hint == null || (hint in 0L..300000L))
        }
    }

    @Test
    fun snapshot503_isBoundedTransient() = runTest {
        val failure = captureSnapshotError(response(503, errorBody("temporarily_unavailable")))
        assertTrue(failure is SyncApiException.Transient)
        assertNull(failure.cause)
    }

    // Table: Retry-After whole seconds map to bounded millis with case-insensitive name.
    @Test
    fun retryAfterHeaderSeconds_areParsedBounded() = runTest {
        val cases = listOf(
            Triple("Retry-After", "120", 120000L),
            Triple("retry-after", "120", 120000L),
            Triple("Retry-After", "9223372036854775807", 300000L),
        )
        for ((name, value, expected) in cases) {
            val next = SyncHttpResponse(429, mapOf(name to listOf(value)), errorBody("rate_limited"))
            val failure = capturePushError(next)
            assertTrue("$name: $value", failure is SyncApiException.Transient)
            assertEquals(expected, (failure as SyncApiException.Transient).retryAfterMillis)
            assertNull(failure.cause)
        }
        val nullCases = listOf(
            Pair("Retry-After", "-5"),
            Pair("Retry-After", "not-a-number"),
        )
        for ((name, value) in nullCases) {
            val next = SyncHttpResponse(429, mapOf(name to listOf(value)), errorBody("rate_limited"))
            val failure = capturePushError(next)
            assertTrue("$name: $value", failure is SyncApiException.Transient)
            assertNull((failure as SyncApiException.Transient).retryAfterMillis)
        }
    }

    // Table: other 4xx with parsed safe machine code keeps exact code including unknown.
    @Test
    fun other4xxWithSafeCode_isRequestRejectedExact() = runTest {
        val cases = listOf(
            Pair(422, "validation_error"),
            Pair(404, "not_found"),
            Pair(409, "invalid_state"),
            Pair(422, "future_permanent_code"),
        )
        for ((status, code) in cases) {
            val failure = capturePushError(response(status, errorBody(code)))
            assertTrue("status $status code $code", failure is SyncApiException.RequestRejected)
            assertEquals(code, (failure as SyncApiException.RequestRejected).errorCode)
            assertEquals("permanent top-level request rejection", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.message!!.contains(code))
            assertFalse(failure.toString().contains(code))
        }
    }

    // Table: other 4xx without valid error code is MalformedResponse.
    @Test
    fun other4xxWithoutCode_isMalformedResponse() = runTest {
        val bodies = listOf(
            "",
            """{broken""",
            """{}""",
            """{"message":"only a message"}""",
        )
        for (body in bodies) {
            val failure = capturePushError(response(422, body))
            assertTrue("body $body", failure is SyncApiException.MalformedResponse)
            assertEquals("malformed_response", failure.code)
            assertEquals("malformed or inconsistent protocol response", failure.message)
            assertNull(failure.cause)
        }
    }

    // Table: unexpected non-200 statuses are MalformedResponse.
    @Test
    fun unexpectedStatuses_areMalformedResponse() = runTest {
        val statuses = listOf(201, 302, 600)
        for (status in statuses) {
            val failure = capturePullError(response(status, errorBody("not_found")))
            assertTrue("status $status", failure is SyncApiException.MalformedResponse)
            assertNull(failure.cause)
        }
    }

    // Table: transport exception is Transient without raw cause.
    @Test
    fun transportIOException_isTransientWithoutCause() = runTest {
        val transport = SyncHttpTransport { _, _, _, _, _, _ ->
            throw IOException("simulated R02c transport failure")
        }
        val api = HttpGymSyncApi(transport, accessToken)
        try {
            api.push(emptyList())
        } catch (failure: SyncApiException.Transient) {
            assertEquals("transient", failure.code)
            assertNull(failure.cause)
            return@runTest
        }
        fail("transport IOException should become Transient")
    }

    // Table: transport cancellation propagates as the original instance.
    @Test
    fun pushCancellation_propagatesOriginalInstance() = runTest {
        val original = CancellationException("original R02c cancellation")
        val transport = SyncHttpTransport { _, _, _, _, _, _ ->
            throw original
        }
        val api = HttpGymSyncApi(transport, accessToken)
        try {
            api.push(emptyList())
            fail("cancellation should propagate")
        } catch (actual: CancellationException) {
            assertSame(original, actual)
        }
    }

    @Test
    fun pullCancellation_propagatesOriginalInstance() = runTest {
        val original = CancellationException("original R02c pull cancellation")
        val transport = SyncHttpTransport { _, _, _, _, _, _ ->
            throw original
        }
        val api = HttpGymSyncApi(transport, accessToken)
        try {
            api.pull("opaque-cursor")
            fail("cancellation should propagate")
        } catch (actual: CancellationException) {
            assertSame(original, actual)
        }
    }

    // Table: transport cancellation on snapshot propagates as the original instance.
    @Test
    fun snapshotCancellation_propagatesOriginalInstance() = runTest {
        val original = CancellationException("original R02c snapshot cancellation")
        val transport = SyncHttpTransport { _, _, _, _, _, _ ->
            throw original
        }
        val api = HttpGymSyncApi(transport, accessToken)
        try {
            api.snapshot("opaque-token")
            fail("cancellation should propagate")
        } catch (actual: CancellationException) {
            assertSame(original, actual)
        }
    }

    // Table: HTTP 200 per-mutation rejected outcome returns normally, including unknown code.
    @Test
    fun push200RejectedOutcome_returnsNormalResult() = runTest {
        val innerExact = """{"mutationId":"11111111-1111-1111-1111-111111111111","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1,"payload":{"workoutSetId":"33333333-3333-3333-3333-333333333333"}}"""
        val wrapper = """{"payloadVersion":1,"requestJson":${JSONObject.quote(innerExact)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1}]}"""
        val request = decodeDurableRequest(wrapper)
        val body = """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"rejected","errorCode":"future_permanent_code","batch":null}]}"""
        val api = HttpGymSyncApi(transportReturning(response(200, body)), accessToken)
        val result = api.push(listOf(request))
        assertEquals(1, result.results.size)
        assertEquals("11111111-1111-1111-1111-111111111111", result.results[0].mutationId)
        assertEquals("future_permanent_code", result.results[0].errorCode)
        assertEquals(MutationStatus.REJECTED, result.results[0].status)
    }

    // Table: frozen push body is byte-exact with no automatic replay.
    @Test
    fun pushFrozenBody_isByteExactSingleSend() = runTest {
        val innerExact = """{"mutationId":"11111111-1111-1111-1111-111111111111","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1,"payload":{"workoutSetId":"33333333-3333-3333-3333-333333333333"}}"""
        val wrapper = """{"payloadVersion":1,"requestJson":${JSONObject.quote(innerExact)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1}]}"""
        val request = decodeDurableRequest(wrapper)
        assertEquals(innerExact, request.requestJson)
        assertEquals("11111111-1111-1111-1111-111111111111", request.envelope.mutationId)
        val expectedBody = """{"mutations":[$innerExact]}"""
        var calls = 0
        var seenPath: String? = null
        var seenBody: String? = null
        var seenAuthorization: String? = null
        val transport = SyncHttpTransport { _, path, _, _, body, authorization ->
            calls += 1
            seenPath = path
            seenBody = body
            seenAuthorization = authorization
            response(200, """{"results":[{"mutationId":"11111111-1111-1111-1111-111111111111","status":"rejected","errorCode":"future_permanent_code","batch":null}]}""")
        }
        val api = HttpGymSyncApi(transport, accessToken)
        api.push(listOf(request))
        assertEquals(1, calls)
        assertEquals("/v1/sync/push", seenPath)
        assertEquals(expectedBody, seenBody)
        assertEquals("Bearer $accessToken", seenAuthorization)
    }

    // Table: frozen push bytes on HTTP 413 stay exact with a single send and no rewrite.
    @Test
    fun pushFrozenBody413_isSingleSendRequestTooLarge() = runTest {
        val innerExact = """{"mutationId":"11111111-1111-1111-1111-111111111111","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1,"payload":{"workoutSetId":"33333333-3333-3333-3333-333333333333"}}"""
        val wrapper = """{"payloadVersion":1,"requestJson":${JSONObject.quote(innerExact)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1}]}"""
        val request = decodeDurableRequest(wrapper)
        assertEquals(innerExact, request.requestJson)
        assertEquals("11111111-1111-1111-1111-111111111111", request.envelope.mutationId)
        val expectedBody = """{"mutations":[$innerExact]}"""
        var calls = 0
        var seenBody: String? = null
        var seenAuthorization: String? = null
        val transport = SyncHttpTransport { _, path, _, _, body, authorization ->
            calls += 1
            assertEquals("/v1/sync/push", path)
            seenBody = body
            seenAuthorization = authorization
            SyncHttpResponse(413, emptyMap(), "")
        }
        val api = HttpGymSyncApi(transport, accessToken)
        try {
            api.push(listOf(request))
            fail("413 frozen push should throw RequestTooLarge")
        } catch (failure: SyncApiException.RequestTooLarge) {
            assertEquals("request_too_large", failure.code)
            assertEquals("request too large; retain intent and require recovery", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(1, calls)
        assertEquals(expectedBody, seenBody)
        assertEquals("Bearer $accessToken", seenAuthorization)
    }

    // Table: bearer binding plus opaque cursor/pageToken forwarding on successful empty pages.
    @Test
    fun pullForwardsBearerAndOpaqueCursor() = runTest {
        var seenPath: String? = null
        var seenCursor: String? = null
        var seenAuthorization: String? = null
        val transport = SyncHttpTransport { _, path, query, _, _, authorization ->
            seenPath = path
            seenCursor = query["cursor"]
            seenAuthorization = authorization
            response(200, """{"batches":[],"nextCursor":"opaque","hasMore":false}""")
        }
        val api = HttpGymSyncApi(transport, accessToken)
        api.pull("opaque-cursor-123")
        assertEquals("/v1/sync/pull", seenPath)
        assertEquals("opaque-cursor-123", seenCursor)
        assertEquals("Bearer $accessToken", seenAuthorization)
    }

    @Test
    fun snapshotForwardsBearerAndOpaquePageToken() = runTest {
        var seenPath: String? = null
        var seenPageToken: String? = null
        var seenAuthorization: String? = null
        val transport = SyncHttpTransport { _, path, query, _, _, authorization ->
            seenPath = path
            seenPageToken = query["pageToken"]
            seenAuthorization = authorization
            response(200, """{"records":[],"baselineCursor":"opaque","hasMore":false}""")
        }
        val api = HttpGymSyncApi(transport, accessToken)
        api.snapshot("opaque-token-456")
        assertEquals("/v1/sync/snapshot", seenPath)
        assertEquals("opaque-token-456", seenPageToken)
        assertEquals("Bearer $accessToken", seenAuthorization)
    }

    // Table: adapter failures use fixed safe sentinel messages without raw leakage.
    @Test
    fun adapterFailures_useFixedMessagesWithoutCause() = runTest {
        val transient = capturePushError(response(503, """{"errorCode":"temporarily_unavailable","message":"raw service text"}"""))
        assertEquals("transient sync failure; retry with bounded backoff", transient.message)
        assertNull(transient.cause)
        assertFalse(transient.toString().contains("raw service text"))
        val rejected = capturePushError(response(422, """{"errorCode":"future_permanent_code","message":"raw service text"}"""))
        assertEquals("permanent top-level request rejection", rejected.message)
        assertNull(rejected.cause)
        assertFalse(rejected.toString().contains("raw service text"))
        assertFalse(rejected.toString().contains("future_permanent_code"))
    }
}
