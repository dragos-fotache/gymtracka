package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.http.HttpServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R04b QA-owned decorator tests only. No production declarations.
 *
 * Covers the UNUSED SessionRefreshingGymSyncApi: one refresh-and-unchanged-
 * retry over the serialized coordinator path, plus the actual
 * HttpServiceAuthApi through the scripted transport (client fixture, NOT a
 * server simulator). Deterministic JVM: TestScope/virtual time/gates.
 * Field-based request assertions only; redacted toString is never an oracle.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionRefreshingGymSyncApiTest {
    private val F = RefreshTestFixtures

    private data class Activation(
        val vault: FakeSessionVault,
        val auth: FakeServiceAuthApi,
        val lease: SessionRefreshLease,
        val coordinator: SessionRefreshCoordinator,
        val transport: FakeServiceHttpTransport,
        val api: SessionRefreshingGymSyncApi,
    )

    private fun liveActivation(
        accessExpiresAt: Long = F.NOW + F.FIFTEEN_MIN,
        operationId: String = F.OP_A,
        phase: VaultSessionPhase = VaultSessionPhase.ACTIVE,
    ): Activation {
        val binding = F.binding()
        val record = F.record(
            binding = binding,
            operationId = operationId,
            phase = phase,
            session = F.session(accessExpiresAtMillis = accessExpiresAt),
        )
        val vault = FakeSessionVault(VaultSnapshot(revision = 1L, record = record))
        val auth = FakeServiceAuthApi()
        val lease = F.sharedLease(binding = binding, operationId = operationId, phase = phase)
        val coordinator = F.coordinator(vault, auth)
        val transport = FakeServiceHttpTransport()
        val api = SessionRefreshingGymSyncApi(
            coordinator = coordinator,
            lease = lease,
            transport = transport,
        )
        return Activation(vault, auth, lease, coordinator, transport, api)
    }

    private fun copiedRequest(wrapper: String): DurableRequestModel {
        val approved = decodeDurableRequest(wrapper)
        return DurableRequestModel(
            payloadVersion = approved.payloadVersion,
            requestJson = approved.requestJson,
            affectedRoots = approved.affectedRoots.toList(),
            envelope = approved.envelope,
        )
    }

    // ---- Constructors inert; no account-global bearer cache ----

    @Test
    fun constructorsPerformNoIo() = runTest {
        val a = liveActivation()
        runCurrent()
        assertEquals(0, a.vault.loads)
        assertEquals(0, a.vault.commits)
        assertEquals(0, a.auth.refreshCalls)
        assertTrue(a.transport.capturedRequests.isEmpty())
        SessionRefreshingGymSyncApi(
            coordinator = a.coordinator,
            lease = a.lease,
        )
        runCurrent()
        assertEquals(0, a.vault.loads)
        assertEquals(0, a.auth.refreshCalls)
    }

    // ---- Push snapshot, byte-exact retry, read passthrough ----

    @Test
    fun pushSnapshotsListBeforeSelectionWithMatchingOutcome() = runTest {
        val a = liveActivation()
        val proceed = CompletableDeferred<Unit>()
        a.vault.loadGate = { proceed.await() }
        a.transport.queueOk(F.rejectedPushBodyFor(listOf(F.MUT_A)))
        val input = mutableListOf(copiedRequest(F.WRAPPER_A))
        val deferred = async { a.api.push(input) }
        runCurrent()
        input.clear()
        proceed.complete(Unit)
        val result = deferred.await()
        assertEquals(1, result.results.size)
        assertEquals(F.MUT_A, result.results[0].mutationId)
        assertEquals(1, a.transport.capturedRequests.size)
        val body = a.transport.capturedRequests.single().body ?: failNow("push must carry a body")
        assertTrue(body.contains(F.REQ_JSON_A))
    }

    @Test
    fun liveAccessPushSendsOnceWithNoRefresh() = runTest {
        val a = liveActivation()
        a.transport.queueOk(F.EMPTY_PUSH_BODY)
        val result = a.api.push(emptyList())
        assertEquals(0, result.results.size)
        assertEquals(1, a.transport.capturedRequests.size)
        assertEquals(0, a.auth.refreshCalls)
        assertEquals(0, a.vault.commits)
    }

    @Test
    fun callback401ThenSuccessRetriesSameBytesWithRotatedBearer() = runTest {
        val a = liveActivation()
        a.transport.queueJson(401, F.unauthorizedBody("access_expired"))
        a.transport.queueOk(F.EMPTY_PUSH_BODY)
        a.auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val result = a.api.push(emptyList())
        assertEquals(0, result.results.size)
        assertEquals(2, a.transport.capturedRequests.size)
        assertEquals(1, a.auth.refreshCalls)
        val first = a.transport.capturedRequests[0]
        val second = a.transport.capturedRequests[1]
        assertEquals(first.method, second.method)
        assertEquals(first.path, second.path)
        assertEquals(first.query, second.query)
        assertEquals(first.body, second.body)
        assertFalse(first.headers.values.any { it.contains("access-2") })
        assertTrue(second.headers.values.any { it.contains("access-2") })
    }

    @Test
    fun nonemptyPushByteExactAndOrderedAcrossRetry() = runTest {
        val a = liveActivation()
        a.transport.queueJson(401, F.unauthorizedBody("access_expired"))
        a.transport.queueOk(F.rejectedPushBodyFor(listOf(F.MUT_A, F.MUT_B)))
        a.auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val reqs = listOf(copiedRequest(F.WRAPPER_A), copiedRequest(F.WRAPPER_B))
        val result = a.api.push(reqs)
        assertEquals(2, result.results.size)
        assertEquals(F.MUT_A, result.results[0].mutationId)
        assertEquals(F.MUT_B, result.results[1].mutationId)
        assertEquals("fixture", result.results[0].errorCode)
        assertEquals(2, a.transport.capturedRequests.size)
        val first = a.transport.capturedRequests[0].body ?: failNow("first attempt must carry a body")
        val second = a.transport.capturedRequests[1].body ?: failNow("retry must carry a body")
        assertEquals(first, second)
        assertTrue(first.indexOf(F.REQ_JSON_A) >= 0)
        assertTrue(first.indexOf(F.REQ_JSON_A) < first.indexOf(F.REQ_JSON_B))
    }

    @Test
    fun callerMutationOfOwnedListsKeepsRawBytesIdentical() = runTest {
        val a = liveActivation()
        val approved = decodeDurableRequest(F.WRAPPER_A)
        val roots = approved.affectedRoots.toMutableList()
        val req = DurableRequestModel(
            payloadVersion = approved.payloadVersion,
            requestJson = approved.requestJson,
            affectedRoots = roots,
            envelope = approved.envelope,
        )
        var gates = 0
        a.transport.responseGate = {
            gates += 1
            if (gates == 1) {
                roots.add(roots[0])
            }
            if (gates == 2) {
                roots.clear()
            }
        }
        a.transport.queueJson(401, F.unauthorizedBody("access_expired"))
        a.transport.queueOk(F.rejectedPushBodyFor(listOf(F.MUT_A)))
        a.auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val result = a.api.push(listOf(req))
        assertEquals(F.MUT_A, result.results.single().mutationId)
        assertEquals(2, a.transport.capturedRequests.size)
        val first = a.transport.capturedRequests[0].body ?: failNow("first attempt must carry a body")
        val second = a.transport.capturedRequests[1].body ?: failNow("retry must carry a body")
        assertEquals(first, second)
        assertTrue(first.contains(F.REQ_JSON_A))
    }

    @Test
    fun preflightCountFailureIsExactTypeWithNoCredentialIo() = runTest {
        val a = liveActivation()
        val reqs = List(101) { copiedRequest(F.WRAPPER_A) }
        assertFailsWith<SyncModelException> { a.api.push(reqs) }
        assertEquals(0, a.vault.loads)
        assertEquals(0, a.vault.commits)
        assertEquals(0, a.auth.refreshCalls)
        assertTrue(a.transport.capturedRequests.isEmpty())
    }

    @Test
    fun preflightDuplicateFailureIsExactTypeWithNoCredentialIo() = runTest {
        val a = liveActivation()
        val reqs = listOf(copiedRequest(F.WRAPPER_A), copiedRequest(F.WRAPPER_A))
        assertFailsWith<SyncModelException> { a.api.push(reqs) }
        assertEquals(0, a.vault.loads)
        assertEquals(0, a.auth.refreshCalls)
        assertTrue(a.transport.capturedRequests.isEmpty())
    }

    @Test
    fun preflightUnsupportedVersionFailureIsExactTypeWithNoCredentialIo() = runTest {
        val a = liveActivation()
        val approved = decodeDurableRequest(F.WRAPPER_A)
        val bad = DurableRequestModel(
            payloadVersion = 999,
            requestJson = approved.requestJson,
            affectedRoots = approved.affectedRoots.toList(),
            envelope = approved.envelope,
        )
        assertFailsWith<UnsupportedDurableVersionException> { a.api.push(listOf(bad)) }
        assertEquals(0, a.vault.loads)
        assertEquals(0, a.vault.commits)
        assertEquals(0, a.auth.refreshCalls)
        assertTrue(a.transport.capturedRequests.isEmpty())
    }

    @Test
    fun preflightMismatchedBodyFailureIsExactTypeWithNoCredentialIo() = runTest {
        val a = liveActivation()
        val approved = decodeDurableRequest(F.WRAPPER_A)
        val bad = DurableRequestModel(
            payloadVersion = approved.payloadVersion,
            requestJson = F.REQ_JSON_B,
            affectedRoots = approved.affectedRoots.toList(),
            envelope = approved.envelope,
        )
        assertFailsWith<SyncModelException> { a.api.push(listOf(bad)) }
        assertEquals(0, a.vault.loads)
        assertEquals(0, a.vault.commits)
        assertEquals(0, a.auth.refreshCalls)
        assertTrue(a.transport.capturedRequests.isEmpty())
    }

    @Test
    fun rejectedOutcomeDoesNotResend() = runTest {
        val a = liveActivation()
        a.transport.queueOk(F.rejectedPushBodyFor(listOf(F.MUT_A)))
        val result = a.api.push(listOf(copiedRequest(F.WRAPPER_A)))
        assertEquals(1, result.results.size)
        assertEquals(1, a.transport.capturedRequests.size)
        assertEquals(0, a.auth.refreshCalls)
    }

    @Test
    fun repeated401LatchesReauth() = runTest {
        val a = liveActivation()
        a.transport.queueJson(401, F.unauthorizedBody("access_expired"))
        a.transport.queueJson(401, F.unauthorizedBody("access_expired"))
        a.auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        assertFailsWith<SyncApiException.ReauthenticationRequired> { a.api.pull(null) }
        assertEquals(2, a.transport.capturedRequests.size)
        assertFailsWith<SyncApiException.ReauthenticationRequired> { a.api.pull(null) }
        assertEquals(2, a.transport.capturedRequests.size)
        assertEquals(1, a.auth.refreshCalls)
    }

    @Test
    fun explicitReauthOnFirstCallbackHasNoRecovery() = runTest {
        val a = liveActivation()
        a.transport.queueJson(401, F.reauthBody())
        assertFailsWith<SyncApiException.ReauthenticationRequired> { a.api.pull(null) }
        assertEquals(1, a.transport.capturedRequests.size)
        assertEquals(0, a.auth.refreshCalls)
        assertFailsWith<SyncApiException.ReauthenticationRequired> { a.api.pull(null) }
        assertEquals(1, a.transport.capturedRequests.size)
    }

    @Test
    fun identicalReadQueriesAcrossRetry() = runTest {
        val a = liveActivation()
        a.transport.queueJson(401, F.unauthorizedBody("access_expired"))
        a.transport.queueOk(F.EMPTY_PULL_BODY)
        a.auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val result = a.api.pull("same-input", 100)
        assertEquals(PullResultModel(batches = emptyList(), nextCursor = "same-input", hasMore = false), result)
        assertEquals(2, a.transport.capturedRequests.size)
        val first = a.transport.capturedRequests[0]
        val second = a.transport.capturedRequests[1]
        assertEquals(first.method, second.method)
        assertEquals(first.path, second.path)
        assertEquals(first.query, second.query)
        assertEquals(first.body, second.body)
    }

    @Test
    fun snapshotPassthrough() = runTest {
        val a = liveActivation()
        a.transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
        val result = a.api.snapshot(null, 200)
        assertEquals(
            SnapshotResultModel(
                records = emptyList(),
                baselineCursor = "baseline",
                nextPageToken = null,
                hasMore = false,
            ),
            result,
        )
        assertEquals(1, a.transport.capturedRequests.size)
    }

    @Test
    fun concurrentLiveRejectedOldTokenRotatesOnce() = runTest {
        val a = liveActivation()
        val lock = Any()
        var entered = 0
        val barrier = CompletableDeferred<Unit>()
        a.transport.responseGate = {
            synchronized(lock) {
                entered += 1
                if (entered >= 2) barrier.complete(Unit)
            }
            barrier.await()
        }
        a.transport.serve = { _, request ->
            if (request.headers.values.any { it.contains("access-2") }) {
                FakeServiceHttpTransport.Frame(200, F.EMPTY_PULL_BODY)
            } else {
                FakeServiceHttpTransport.Frame(401, F.unauthorizedBody("access_expired"))
            }
        }
        a.auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val d1 = async { a.api.pull("same-input", 100) }
        val d2 = async { a.api.pull("same-input", 100) }
        val r1 = d1.await()
        val r2 = d2.await()
        assertEquals("same-input", r1.nextCursor)
        assertEquals("same-input", r2.nextCursor)
        assertEquals(1, a.auth.refreshCalls)
        assertEquals(4, a.transport.capturedRequests.size)
        val queries = a.transport.capturedRequests.map { Triple(it.method, it.path, it.query) }
        assertTrue(queries.toSet().size == 1)
        val retries = a.transport.capturedRequests.filter {
            it.headers.values.any { h -> h.contains("access-2") }
        }
        assertEquals(2, retries.size)
    }

    @Test
    fun decoratorCancellationIdentityInsideWaitingCaller() = runTest {
        val a = liveActivation()
        val probe = CancellationException("probe-decorator")
        a.transport.responseGate = { throw probe }
        val matched = CompletableDeferred<Boolean>()
        val child = launch {
            try {
                a.api.pull(null)
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        runCurrent()
        child.cancel(probe)
        assertTrue(matched.await())
        assertTrue(a.transport.capturedRequests.isEmpty())
    }

    // ---- Actual HttpServiceAuthApi through scripted transport ----

    private fun realAuthApi(transport: FakeServiceHttpTransport): HttpServiceAuthApi =
        HttpServiceAuthApi(origin = F.origin(), transport = transport)

    @Test
    fun realAuthAdapterSendsExactBodyAndHeaderKeyOnce() = runTest {
        val transport = FakeServiceHttpTransport()
        transport.queueOk(
            F.grantBody(accessValue = "access-2", refreshValue = "refresh-2"),
        )
        val api = realAuthApi(transport)
        val attempt = F.pending(refreshValue = "refresh-1").attempt
        val session = api.refresh(attempt)
        assertEquals("access-2", session.accessToken.value)
        assertEquals("refresh-2", session.refreshToken.value)
        assertEquals(1, transport.capturedRequests.size)
        val captured = transport.capturedRequests.single()
        assertEquals(attempt.requestJson, captured.body)
        assertTrue(captured.headers.values.contains(attempt.idempotencyKey))
    }

    @Test
    fun realAuthAdapterRoutes401Reauth() = runTest {
        val transport = FakeServiceHttpTransport()
        transport.queueJson(401, F.reauthBody())
        val api = realAuthApi(transport)
        assertFailsWith<SyncApiException.ReauthenticationRequired> {
            api.refresh(F.pending().attempt)
        }
    }

    @Test
    fun realAuthAdapterLegacyPrecisionIsMalformed() = runTest {
        val transport = FakeServiceHttpTransport()
        transport.queueOk(F.legacyGrantBody())
        val api = realAuthApi(transport)
        assertFailsWith<SyncApiException.MalformedResponse> {
            api.refresh(F.pending().attempt)
        }
    }

    @Test
    fun endToEndRefreshThenPullUsesCommittedGrant() = runTest {
        val binding = F.binding()
        val record = F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1))
        val vault = FakeSessionVault(VaultSnapshot(revision = 1L, record = record))
        val authTransport = FakeServiceHttpTransport()
        authTransport.queueOk(F.grantBody(accessValue = "access-2", refreshValue = "refresh-2"))
        val coordinator = SessionRefreshCoordinator(
            origin = F.origin(),
            vault = vault,
            authApi = realAuthApi(authTransport),
            clockMillis = { F.NOW },
            uuidProvider = { F.OP_A },
            mutex = Mutex(),
        )
        val lease = F.sharedLease(binding = binding)
        val syncTransport = FakeServiceHttpTransport()
        syncTransport.queueOk(F.EMPTY_PULL_BODY)
        val api = SessionRefreshingGymSyncApi(
            coordinator = coordinator,
            lease = lease,
            transport = syncTransport,
        )
        val result = api.pull("same-input", 100)
        assertEquals("same-input", result.nextCursor)
        assertEquals(1, authTransport.capturedRequests.size)
        assertEquals(1, syncTransport.capturedRequests.size)
        assertTrue(syncTransport.capturedRequests.single().headers.values.any { it.contains("access-2") })
        if (vault.current().record?.pendingRefresh != null) fail("pending must clear after committed rotation")
    }
}
