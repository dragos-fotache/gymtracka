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
import com.dragosfotache.gymtracka.testing.service.FakeAccountSyncApi
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 * failure text with bounded retry hints. Push executes the seventeen implemented
 * fake commands; pull and snapshot stay later tasks, and these tests pin
 * that boundary behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncApiBoundaryTest {
    companion object {
        const val ACCOUNT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val ROOT_1 = "11111111-1111-1111-1111-111111111111"
        const val SET_1 = "66666666-6666-6666-6666-666666666661"
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
        // Empty is valid per encodePushMutationsBody/OpenAPI (no minItems);
        // execution returns an empty result with no state changes.
        val service = FakeSyncService()
        val api = FakeAccountSyncApi(ACCOUNT_A, service)
        val result = api.push(emptyList())
        assertTrue(result.results.isEmpty())
        assertEquals(listOf(0), api.pushSizes)
        assertTrue(service.loggedBatches(ACCOUNT_A).isEmpty())
    }

    @Test
    fun pushAcceptsOneHundredDistinctValidRequests() = runTest {
        val service = FakeSyncService()
        val api = FakeAccountSyncApi(ACCOUNT_A, service)
        // Structurally valid delete-set commands execute and reject explicitly:
        // the envelope root is unknown, so the lookup fails closed.
        val result = api.push(List(MAX_PUSH_REQUESTS) { validRequest(it) })
        assertEquals(MAX_PUSH_REQUESTS, result.results.size)
        for (outcome in result.results) {
            assertEquals(MutationStatus.REJECTED, outcome.status)
            assertEquals(FakeSyncService.OWNED_ROOT_MISSING, outcome.errorCode)
            assertNull(outcome.batch)
        }
        assertEquals(listOf(100), api.pushSizes)
        assertEquals(100, MAX_PUSH_REQUESTS)
        assertTrue(service.loggedBatches(ACCOUNT_A).isEmpty())
    }

    @Test
    fun pushRejectsOverOneHundredDistinctValidRequests() = runTest {
        val api = FakeAccountSyncApi(ACCOUNT_A, FakeSyncService())
        try {
            api.push(List(MAX_PUSH_REQUESTS + 1) { validRequest(it) })
            fail("Expected a 101-request push batch to be rejected")
        } catch (e: SyncModelException) {
            assertTrue(e.message!!.contains("100"))
        }
        assertTrue(api.pushSizes.isEmpty())
    }

    @Test
    fun pushRejectsDuplicateMutationIds() = runTest {
        val api = FakeAccountSyncApi(ACCOUNT_A, FakeSyncService())
        try {
            api.push(listOf(validRequest(7), validRequest(7)))
            fail("Expected duplicate mutation IDs to be rejected")
        } catch (e: SyncModelException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
        assertTrue(api.pushSizes.isEmpty())
    }

    @Test
    fun pushRejectsMalformedWrapper() = runTest {
        val api = FakeAccountSyncApi(ACCOUNT_A, FakeSyncService())
        val tampered = validRequest(3).copy(requestJson = "{\"tampered\":true}")
        try {
            api.push(listOf(tampered))
            fail("Expected a text-mismatched wrapper to be rejected")
        } catch (_: SyncModelException) {
        }
        assertTrue(api.pushSizes.isEmpty())
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
        val api = FakeAccountSyncApi(ACCOUNT_A, FakeSyncService())
        val opaque = "opaque::99/batches?x=1"
        try {
            api.pull(opaque, 5)
            fail("Expected fake pull to be explicitly stubbed")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("later assignment"))
        }
        try {
            api.pull(null)
            fail("Expected fake pull to be explicitly stubbed")
        } catch (_: UnsupportedOperationException) {
        }
        assertEquals(
            listOf(
                FakeAccountSyncApi.PullCall(opaque, 5),
                FakeAccountSyncApi.PullCall(null, DEFAULT_PULL_LIMIT),
            ),
            api.pullCalls,
        )
    }

    @Test
    fun opaqueSnapshotArgumentsAreRecordedVerbatim() = runTest {
        val api = FakeAccountSyncApi(ACCOUNT_A, FakeSyncService())
        val opaque = "snapshot-token::7"
        try {
            api.snapshot(opaque, 7)
            fail("Expected fake snapshot to be explicitly stubbed")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("later assignment"))
        }
        try {
            api.snapshot(null)
            fail("Expected fake snapshot to be explicitly stubbed")
        } catch (_: UnsupportedOperationException) {
        }
        assertEquals(
            listOf(
                FakeAccountSyncApi.SnapshotCall(opaque, 7),
                FakeAccountSyncApi.SnapshotCall(null, DEFAULT_SNAPSHOT_LIMIT),
            ),
            api.snapshotCalls,
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
                ("authentication_required" to "foreground reauthentication required"),
            SyncApiException.CursorExpired() to
                ("cursor_expired" to "change cursor expired; full resync required"),
            SyncApiException.RequestRejected("owned_root_missing") to
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
            "owned_root_missing",
            (failures[3].first as SyncApiException.RequestRejected).errorCode,
        )
    }
}
