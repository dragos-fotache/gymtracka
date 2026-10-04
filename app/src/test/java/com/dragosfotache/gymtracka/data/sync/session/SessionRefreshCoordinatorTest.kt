package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * R04b QA-owned coordinator tests only. No production declarations.
 *
 * Deterministic JVM: TestScope/virtual time/gates, no sleeps. ONE shared
 * lease per activation. Cancellation identity is asserted INSIDE the
 * launched caller; late-error ordering is cancellation first, then
 * invalidated lease/durable identity over the old operation's category.
 * Uses JUnit4 asserts only (kotlin.test is not a declared dependency).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionRefreshCoordinatorTest {
    private val F = RefreshTestFixtures

    private fun liveSetup(
        accessExpiresAt: Long = F.NOW + F.FIFTEEN_MIN,
        phase: VaultSessionPhase = VaultSessionPhase.ACTIVE,
        operationId: String = F.OP_A,
        revision: Long = 1L,
    ): Triple<FakeSessionVault, FakeServiceAuthApi, SessionRefreshLease> {
        val binding = F.binding()
        val record = F.record(
            binding = binding,
            operationId = operationId,
            phase = phase,
            session = F.session(accessExpiresAtMillis = accessExpiresAt),
        )
        val vault = FakeSessionVault(VaultSnapshot(revision = revision, record = record))
        val auth = FakeServiceAuthApi()
        val lease = F.sharedLease(binding = binding, operationId = operationId, phase = phase)
        return Triple(vault, auth, lease)
    }

    private fun coordinatorOf(
        vault: SessionVault,
        auth: FakeServiceAuthApi,
        clock: () -> Long = { F.NOW },
        uuids: Iterator<String> = sequenceOf(F.OP_A, F.KEY_A).iterator(),
        mutex: Mutex = Mutex(),
    ) = F.coordinator(vault, auth, clock, uuids, mutex)

    /** Runs [block] in a child coroutine; returns true iff it threw exactly [probe], checked INSIDE. */
    private suspend fun kotlinx.coroutines.test.TestScope.cancelledInside(
        probe: CancellationException,
        block: suspend () -> Unit,
    ): Boolean {
        val matched = CompletableDeferred<Boolean>()
        val child = launch {
            try {
                block()
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        runCurrent()
        child.cancel(probe)
        return matched.await()
    }

    // ---- Inert constructors, no implicit authorization ----

    @Test
    fun constructorsPerformNoIo() = runTest {
        val (vault, auth, lease) = liveSetup()
        var clockCalls = 0
        val c = SessionRefreshCoordinator(
            origin = F.origin(),
            vault = vault,
            authApi = auth,
            clockMillis = { clockCalls += 1; F.NOW },
            uuidProvider = { F.KEY_A },
            mutex = Mutex(),
        )
        runCurrent()
        assertEquals(0, vault.loads)
        assertEquals(0, vault.commits)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, clockCalls)
        assertEquals(F.binding(), lease.binding)
    }

    @Test
    fun leaseConstructionRejectsNonCanonicalUuid() = runTest {
        val err = assertFailsWith<SessionRefreshException> {
            SessionRefreshLease(
                binding = F.binding(),
                operationId = "not-a-uuid",
                phase = VaultSessionPhase.ACTIVE,
            )
        }
        assertEquals(SessionRefreshFailure.INVALID_INPUT, err.failure)
    }

    @Test
    fun mismatchedOriginIsStaleWithNoWriteOrHttp() = runTest {
        val (vault, auth, _) = liveSetup()
        val lease = F.sharedLease(binding = F.binding(root = F.OTHER_ORIGIN_ROOT))
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, vault.commits)
    }

    @Test
    fun tombstonedRecordIsStale() = runTest {
        val vault = FakeSessionVault(VaultSnapshot(revision = 2L, record = null))
        val auth = FakeServiceAuthApi()
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(F.sharedLease()) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, vault.commits)
    }

    @Test
    fun absentRecordIsStale() = runTest {
        val vault = FakeSessionVault(VaultSnapshot(revision = 0L, record = null))
        val auth = FakeServiceAuthApi()
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(F.sharedLease()) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun operationMismatchIsStaleNeverAdopts() = runTest {
        val (vault, auth, _) = liveSetup(operationId = F.OP_A)
        val lease = F.sharedLease(operationId = F.OP_B)
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, vault.commits)
        assertEquals(F.OP_A, vault.current().record?.operationId)
    }

    @Test
    fun phaseMismatchIsStale() = runTest {
        val (vault, auth, _) = liveSetup(phase = VaultSessionPhase.PREPARED)
        val lease = F.sharedLease(phase = VaultSessionPhase.ACTIVE)
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun preparedPhaseUsableOnlyByExplicitMatchingLease() = runTest {
        val (vault, auth, lease) = liveSetup(phase = VaultSessionPhase.PREPARED)
        val c = coordinatorOf(vault, auth)
        val token = c.accessToken(lease)
        assertEquals(F.binding(), token.binding)
        assertEquals("access-1", token.token.value)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, vault.commits)
    }

    @Test
    fun generationMismatchIsStale() = runTest {
        val binding = F.binding(generation = 1L)
        val record = F.record(binding = binding, session = F.session())
        val vault = FakeSessionVault(VaultSnapshot(revision = 1L, record = record))
        val auth = FakeServiceAuthApi()
        val lease = F.sharedLease(binding = F.binding(generation = 2L))
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun accountMismatchIsStale() = runTest {
        val (vault, auth, _) = liveSetup()
        val lease = F.sharedLease(binding = F.binding(accountId = F.OTHER_ACCOUNT_ID))
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    // ---- Live access, expiry equality, clock extremes ----

    @Test
    fun liveAccessReturnsExactTaggedCredentialWithNoWriteOrHttp() = runTest {
        val (vault, auth, lease) = liveSetup()
        val c = coordinatorOf(vault, auth, uuids = failingUuids())
        val token = c.accessToken(lease)
        assertEquals(F.binding(), token.binding)
        assertEquals("access-1", token.token.value)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, vault.commits)
        assertEquals(1, vault.loads)
    }

    @Test
    fun accessExpiryAtEqualitySchedulesRefresh() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW)
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth)
        val token = c.accessToken(lease)
        assertEquals("access-2", token.token.value)
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun clockAtMaxLatchesReauthWithoutOverflowNoHttp() = runTest {
        val maxSupported = 253402300799999L
        val (vault, auth, lease) = liveSetup(accessExpiresAt = maxSupported)
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth, clock = { Long.MAX_VALUE })
        assertFailsWith<SyncApiException.ReauthenticationRequired> { c.accessToken(lease) }
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, vault.commits)
    }

    @Test
    fun clockAtMinKeepsSupportedAccessLive() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW)
        val c = coordinatorOf(vault, auth, clock = { Long.MIN_VALUE }, uuids = failingUuids())
        val token = c.accessToken(lease)
        assertEquals("access-1", token.token.value)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun invalidGeneratedUuidIsInvalidInputNoHttp() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val c = coordinatorOf(vault, auth, uuids = iteratorOf("NOT-A-UUID", F.KEY_A))
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.INVALID_INPUT, err.failure)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, vault.commits)
    }

    @Test
    fun throwingUuidProviderIsUnavailableNoHttp() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val c = coordinatorOf(vault, auth, uuids = throwingUuids())
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.UNAVAILABLE, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun throwingClockIsUnavailable() = runTest {
        val (vault, auth, lease) = liveSetup()
        val c = coordinatorOf(vault, auth, clock = { throw RuntimeException("clock-fault") })
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.UNAVAILABLE, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    // ---- Pending-first, persist before send, two CASes ----

    @Test
    fun pendingSettledFirstDespiteLiveAccess() = runTest {
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        val record = F.record(
            binding = binding,
            session = F.session(accessExpiresAtMillis = F.NOW + F.FIFTEEN_MIN),
            pendingRefresh = pending,
        )
        val vault = FakeSessionVault(VaultSnapshot(revision = 5L, record = record))
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val lease = F.sharedLease(binding = binding)
        val c = coordinatorOf(vault, auth, uuids = failingUuids())
        val token = c.accessToken(lease)
        assertEquals("access-2", token.token.value)
        assertEquals(1, auth.refreshCalls)
        val sent = auth.seenAttempts.single()
        assertEquals(pending.attempt.idempotencyKey, sent.idempotencyKey)
        assertEquals(pending.attempt.requestJson, sent.requestJson)
        assertEquals(pending.attempt.refreshToken, sent.refreshToken)
        assertNull(vault.current().record?.pendingRefresh)
    }

    @Test
    fun pendingReplayedDespiteExpiredPredecessorRefresh() = runTest {
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-old")
        val record = F.record(
            binding = binding,
            session = F.session(
                accessExpiresAtMillis = F.NOW - 1,
                refreshValue = "refresh-old",
                refreshExpiresAtMillis = F.NOW - 1,
            ),
            pendingRefresh = pending,
        )
        val vault = FakeSessionVault(VaultSnapshot(revision = 3L, record = record))
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val lease = F.sharedLease(binding = binding)
        val c = coordinatorOf(vault, auth, uuids = failingUuids())
        val token = c.accessToken(lease)
        assertEquals("access-2", token.token.value)
        assertEquals(1, auth.refreshCalls)
        assertEquals(pending.attempt.idempotencyKey, auth.seenAttempts.single().idempotencyKey)
    }

    @Test
    fun persistBeforeSendObservedInsideGateWithTwoCases() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        var gateSawKey: String? = null
        var gateSawBody: String? = null
        auth.refreshGate = {
            val committed = vault.current().record?.pendingRefresh?.attempt
            gateSawKey = committed?.idempotencyKey
            gateSawBody = committed?.requestJson
        }
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth)
        c.accessToken(lease)
        assertEquals(F.KEY_A, gateSawKey)
        assertEquals(auth.seenAttempts.single().requestJson, gateSawBody)
        assertEquals(2, vault.commits)
        assertNull(vault.current().record?.pendingRefresh)
    }

    @Test
    fun casConflictBeforeSendMeansNoHttp() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        vault.casFailures += SessionVaultException(VaultFailure.CONFLICT)
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionVaultException> { c.accessToken(lease) }
        assertEquals(VaultFailure.CONFLICT, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun uncertainCommitReloadedNeverRollbackAssumed() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val committingVault = object : SessionVault by vault {
            var first = true
            override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
                if (first) {
                    first = false
                    vault.compareAndSet(expectedRevision, record)
                    throw SessionVaultException(VaultFailure.CONFLICT)
                }
                return vault.compareAndSet(expectedRevision, record)
            }
        }
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(committingVault, auth)
        assertFailsWith<SessionVaultException> { c.accessToken(lease) }
        assertEquals(0, auth.refreshCalls)
        val current = vault.current()
        assertTrue(current.record?.pendingRefresh != null)
        assertEquals(2L, current.revision)
    }

    @Test
    fun expiredRefreshWithNoPendingLatchesReauthNoWriteNoHttp() = runTest {
        val (_, auth, lease) = liveSetup()
        val expired = F.record(session = F.session(accessExpiresAtMillis = F.NOW - 1, refreshExpiresAtMillis = F.NOW))
        val v2 = FakeSessionVault(VaultSnapshot(revision = 1L, record = expired))
        val c = coordinatorOf(v2, auth)
        assertFailsWith<SyncApiException.ReauthenticationRequired> { c.accessToken(lease) }
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, v2.commits)
        assertFailsWith<SyncApiException.ReauthenticationRequired> { c.accessToken(lease) }
        assertEquals(0, auth.refreshCalls)
    }

    // ---- Replay then fresh rotation, bounded stop ----

    @Test
    fun replayGrantStoredBeforeFreshAttemptWithCurrentToken() = runTest {
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        val record = F.record(
            binding = binding,
            session = F.session(accessExpiresAtMillis = F.NOW - 1, refreshValue = "refresh-1"),
            pendingRefresh = pending,
        )
        val vault = FakeSessionVault(VaultSnapshot(revision = 7L, record = record))
        val auth = FakeServiceAuthApi()
        auth.answer(
            F.session(
                accessValue = "access-replay-expired",
                accessExpiresAtMillis = F.NOW - 1,
                refreshValue = "refresh-2",
            ),
        )
        auth.answer(F.session(accessValue = "access-3", refreshValue = "refresh-3"))
        val lease = F.sharedLease(binding = binding)
        val c = coordinatorOf(vault, auth)
        val token = c.accessToken(lease)
        assertEquals("access-3", token.token.value)
        assertEquals(2, auth.refreshCalls)
        assertEquals(pending.attempt.idempotencyKey, auth.seenAttempts[0].idempotencyKey)
        assertEquals(F.opaque("refresh-2"), auth.seenAttempts[1].refreshToken)
        val replayCommit = vault.committedRecords.firstOrNull { it?.pendingRefresh == null }
            ?: failNow("replay grant must commit before fresh attempt")
        assertEquals("access-replay-expired", replayCommit.session.accessToken.value)
        assertNull(vault.current().record?.pendingRefresh)
    }

    @Test
    fun freshExpiredAccessAfterRotationStopsWithAccessUnavailable() = runTest {
        val binding = F.binding()
        val record = F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1))
        val vault = FakeSessionVault(VaultSnapshot(revision = 1L, record = record))
        val auth = FakeServiceAuthApi()
        auth.answer(
            F.session(
                accessValue = "access-still-expired",
                accessExpiresAtMillis = F.NOW - 1,
                refreshValue = "refresh-2",
            ),
        )
        val lease = F.sharedLease(binding = binding)
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.ACCESS_UNAVAILABLE, err.failure)
        assertEquals(1, auth.refreshCalls)
        assertEquals("access-still-expired", vault.current().record?.session?.accessToken?.value)
        assertNull(vault.current().record?.pendingRefresh)
    }

    @Test
    fun pendingFailureRetainsUncertainPair() = runTest {
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        val record = F.record(binding = binding, session = F.session(), pendingRefresh = pending)
        val vault = FakeSessionVault(VaultSnapshot(revision = 4L, record = record))
        val auth = FakeServiceAuthApi()
        auth.failWith(SyncApiException.Transient(retryAfterMillis = 1000L))
        val lease = F.sharedLease(binding = binding)
        val err = assertFailsWith<SyncApiException.Transient> { cAccess(coordinatorOf(vault, auth), lease) }
        assertEquals(1000L, err.retryAfterMillis)
        assertEquals(1, auth.refreshCalls)
        val current = vault.current()
        assertEquals(pending.attempt.idempotencyKey, current.record?.pendingRefresh?.attempt?.idempotencyKey)
        assertEquals("access-1", current.record?.session?.accessToken?.value)
    }

    private suspend fun cAccess(c: SessionRefreshCoordinator, l: SessionRefreshLease) = c.accessToken(l)

    @Test
    fun mismatchedAccountResultIsMalformedPendingRetained() = runTest {
        val binding = F.binding()
        val record = F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1))
        val vault = FakeSessionVault(VaultSnapshot(revision = 1L, record = record))
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accountId = F.OTHER_ACCOUNT_ID, accessValue = "access-x"))
        val lease = F.sharedLease(binding = binding)
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SyncApiException.MalformedResponse> { c.accessToken(lease) }
        assertTrue(vault.current().record?.pendingRefresh != null)
    }

    @Test
    fun authAuthenticationRequiredBecomesLatchedReauth() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        auth.failWith(SyncApiException.AuthenticationRequired())
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SyncApiException.ReauthenticationRequired> { c.accessToken(lease) }
        assertFailsWith<SyncApiException.ReauthenticationRequired> { c.accessToken(lease) }
        assertEquals(1, auth.refreshCalls)
        assertTrue(vault.current().record?.pendingRefresh != null)
    }

    @Test
    fun unexpectedAuthErrorBecomesClosedTransient() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        auth.failWithUnexpected(RuntimeException("boom"))
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SyncApiException.Transient> { c.accessToken(lease) }
    }

    @Test
    fun vaultLoadFailurePropagatesUnchangedNoHttp() = runTest {
        val (vault, auth, lease) = liveSetup()
        vault.loadFailures += SessionVaultException(VaultFailure.UNREADABLE)
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionVaultException> { c.accessToken(lease) }
        assertEquals(VaultFailure.UNREADABLE, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    // ---- Real-vault restart, exact bodies, storage failures ----

    @Test
    fun realVaultRestartRestoresExactPendingBodies() = runTest {
        val storage = InMemoryVaultFileStorage()
        val vault = realVault(storage)
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        val record = F.record(binding = binding, session = F.session(), pendingRefresh = pending)
        vault.compareAndSet(0L, record)
        val restarted = realVault(storage)
        val snapshot = restarted.load()
        val restored = snapshot.record?.pendingRefresh?.attempt ?: failNow("pending must survive restart")
        assertEquals(pending.attempt.idempotencyKey, restored.idempotencyKey)
        assertEquals(pending.attempt.requestJson, restored.requestJson)
        assertEquals(pending.attempt.refreshToken, restored.refreshToken)
    }

    @Test
    fun realVaultRestartReplayUsesExactOriginalWithoutNewUuids() = runTest {
        val storage = InMemoryVaultFileStorage()
        val first = realVault(storage)
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        first.compareAndSet(0L, F.record(binding = binding, session = F.session(), pendingRefresh = pending))
        val restarted = realVault(storage)
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(restarted, auth, uuids = failingUuids())
        val token = c.accessToken(F.sharedLease(binding = binding))
        assertEquals("access-2", token.token.value)
        assertEquals(pending.attempt.idempotencyKey, auth.seenAttempts.single().idempotencyKey)
        assertEquals(pending.attempt.requestJson, auth.seenAttempts.single().requestJson)
    }

    @Test
    fun realVaultBeforeCommitStorageFailureWritesNothing() = runTest {
        val storage = FailReplaceStorage()
        val vault = realVault(storage)
        val binding = F.binding()
        vault.compareAndSet(
            0L,
            F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1)),
        )
        storage.throwBefore = true
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SessionVaultException> { c.accessToken(F.sharedLease(binding = binding)) }
            .also { assertEquals(VaultFailure.UNAVAILABLE, it.failure) }
        assertEquals(0, auth.refreshCalls)
        storage.throwBefore = false
        val current = realVault(storage).load()
        assertEquals(1L, current.revision)
        assertNull(current.record?.pendingRefresh)
    }

    @Test
    fun realVaultAfterCommitUncertaintyResolvedByRestartReplay() = runTest {
        val storage = FailReplaceStorage()
        val vault = realVault(storage)
        val binding = F.binding()
        vault.compareAndSet(
            0L,
            F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1)),
        )
        storage.writeThenThrow = true
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SessionVaultException> { c.accessToken(F.sharedLease(binding = binding)) }
        assertEquals(0, auth.refreshCalls)
        storage.writeThenThrow = false
        val restarted = realVault(storage)
        val uncertain = restarted.load()
        val pending = uncertain.record?.pendingRefresh?.attempt ?: failNow("uncertain pending must be visible after restart")
        val replayAuth = FakeServiceAuthApi()
        replayAuth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val replayed = coordinatorOf(restarted, replayAuth, uuids = failingUuids())
        val token = replayed.accessToken(F.sharedLease(binding = binding))
        assertEquals("access-2", token.token.value)
        assertEquals(pending.idempotencyKey, replayAuth.seenAttempts.single().idempotencyKey)
        assertEquals(pending.requestJson, replayAuth.seenAttempts.single().requestJson)
    }

    @Test
    fun realVaultFullGrantUncertaintyResolvedByRestart() = runTest {
        val storage = FailReplaceStorage()
        val vault = realVault(storage)
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        vault.compareAndSet(
            0L,
            F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1), pendingRefresh = pending),
        )
        storage.writeThenThrow = true
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SessionVaultException> { c.accessToken(F.sharedLease(binding = binding)) }
        storage.writeThenThrow = false
        val restarted = realVault(storage)
        val uncertain = restarted.load()
        assertEquals("access-2", uncertain.record?.session?.accessToken?.value)
        assertNull(uncertain.record?.pendingRefresh)
        val followUp = FakeServiceAuthApi()
        val reader = coordinatorOf(restarted, followUp, uuids = failingUuids())
        val token = reader.accessToken(F.sharedLease(binding = binding))
        assertEquals("access-2", token.token.value)
        assertEquals(0, followUp.refreshCalls)
    }

    @Test
    fun originalRequestTextRoundTripsIndependently() = runTest {
        val literal = "{ \"refresh\\u0054oken\" : \"r€fresh-ünïcode ✓ \\\"q\\\" \\\\escapes\\\\ \\n\\t\" }"
        val expectedToken = F.opaque("r€fresh-ünïcode ✓ \"q\" \\escapes\\ \n\t")
        val attempt = ServiceRefreshAttempt.fromJson(F.KEY_A, literal)
        assertEquals(F.KEY_A, attempt.idempotencyKey)
        assertEquals(literal, attempt.requestJson)
        assertEquals(expectedToken, attempt.refreshToken)
        val storage = InMemoryVaultFileStorage()
        val vault = realVault(storage)
        val binding = F.binding()
        vault.compareAndSet(
            0L,
            F.record(
                binding = binding,
                session = F.session(refreshValue = "r€fresh-ünïcode ✓ \"q\" \\escapes\\ \n\t"),
                pendingRefresh = VaultPendingRefresh(operationId = F.OP_A, attempt = attempt),
            ),
        )
        val restarted = realVault(storage)
        val restored = restarted.load().record?.pendingRefresh?.attempt
            ?: failNow("pending must survive restart")
        assertEquals(literal, restored.requestJson)
        assertEquals(F.KEY_A, restored.idempotencyKey)
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = F.coordinator(restarted, auth, uuids = failingUuids())
        val token = c.accessToken(F.sharedLease(binding = binding))
        assertEquals("access-2", token.token.value)
        assertEquals(literal, auth.seenAttempts.single().requestJson)
        assertEquals(F.KEY_A, auth.seenAttempts.single().idempotencyKey)
    }

    // ---- External changes, tombstone ABA, stale results ----

    @Test
    fun externalReplacementDuringHttpRefusesLateResult() = runTest {
        val binding = F.binding()
        val record = F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1))
        val vault = FakeSessionVault(VaultSnapshot(revision = 1L, record = record))
        val auth = FakeServiceAuthApi()
        auth.refreshGate = {
            vault.compareAndSet(
                2L,
                F.record(binding = binding, operationId = F.OP_B, session = F.session(accessValue = "access-B")),
            )
        }
        auth.answer(F.session(accessValue = "access-late"))
        val lease = F.sharedLease(binding = binding)
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals("access-B", vault.current().record?.session?.accessToken?.value)
        assertEquals(F.OP_B, vault.current().record?.operationId)
    }

    @Test
    fun externalTombstoneAbaRefusesStaleResult() = runTest {
        val binding = F.binding()
        val record = F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1))
        val vault = FakeSessionVault(VaultSnapshot(revision = 1L, record = record))
        val auth = FakeServiceAuthApi()
        auth.refreshGate = {
            vault.compareAndSet(2L, null)
            vault.compareAndSet(3L, F.record(binding = binding, session = F.session(accessValue = "access-new")))
        }
        auth.answer(F.session(accessValue = "access-late"))
        val lease = F.sharedLease(binding = binding)
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
            .also { assertEquals(SessionRefreshFailure.STALE_SESSION, it.failure) }
        assertEquals("access-new", vault.current().record?.session?.accessToken?.value)
    }

    @Test
    fun callbackPostcheckToleratesSameSessionRotation() = runTest {
        val (vault, auth, lease) = liveSetup()
        val c = coordinatorOf(vault, auth)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waiter = async {
            c.withAccessToken(lease) {
                entered.complete(Unit)
                release.await()
                "result"
            }
        }
        entered.await()
        val loaded = vault.current()
        vault.compareAndSet(
            loaded.revision,
            loaded.record?.copy(session = F.session(accessValue = "access-2", refreshValue = "refresh-2")),
        )
        release.complete(Unit)
        assertEquals("result", waiter.await())
        assertEquals("access-2", vault.current().record?.session?.accessToken?.value)
    }

    // ---- Invalidation ordering, cancellation dominance, latch discipline ----

    @Test
    fun invalidateWhileHttpBlockedMakesLateSuccessStale() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val gate = CompletableDeferred<Unit>()
        auth.refreshGate = { gate.await() }
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth)
        val admitted = CompletableDeferred<Boolean>()
        launch {
            try {
                c.accessToken(lease)
                admitted.complete(false)
            } catch (e: SessionRefreshException) {
                admitted.complete(e.failure == SessionRefreshFailure.STALE_SESSION)
            } catch (e: Throwable) {
                admitted.complete(false)
            }
        }
        runCurrent()
        lease.invalidate()
        gate.complete(Unit)
        assertTrue(admitted.await())
    }

    @Test
    fun invalidateWhileMutexBlockedStopsWaitingCaller() = runTest {
        val (vault, auth, lease) = liveSetup()
        val holderMutex = Mutex(locked = true)
        val c = coordinatorOf(vault, auth, mutex = holderMutex)
        val admitted = CompletableDeferred<Boolean>()
        launch {
            try {
                c.accessToken(lease)
                admitted.complete(false)
            } catch (e: SessionRefreshException) {
                admitted.complete(e.failure == SessionRefreshFailure.STALE_SESSION)
            } catch (e: Throwable) {
                admitted.complete(false)
            }
        }
        runCurrent()
        lease.invalidate()
        holderMutex.unlock()
        assertTrue(admitted.await())
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun invalidateWhileCasBlockedCommitsButAdmitsNothing() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        vault.blockCasCalls = 1
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth)
        val admitted = CompletableDeferred<Boolean>()
        launch {
            try {
                c.accessToken(lease)
                admitted.complete(false)
            } catch (e: SessionRefreshException) {
                admitted.complete(e.failure == SessionRefreshFailure.STALE_SESSION)
            } catch (e: Throwable) {
                admitted.complete(false)
            }
        }
        runCurrent()
        lease.invalidate()
        vault.releaseCas.complete(Unit)
        assertTrue(admitted.await())
    }

    @Test
    fun preSaveLoadInvalidationWritesOnlyPendingNoGrantNoBearer() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        var httpReturned = false
        auth.script += {
            httpReturned = true
            F.session(accessValue = "access-2", refreshValue = "refresh-2")
        }
        vault.loadGate = { if (httpReturned) lease.invalidate() }
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(1, auth.refreshCalls)
        assertEquals(1, vault.commits)
        assertTrue(vault.current().record?.pendingRefresh != null)
    }

    @Test
    fun loadCancelLiveReturnsProbeInsideCaller() = runTest {
        val (vault, auth, lease) = liveSetup()
        val probe = CancellationException("probe-load-cancel")
        vault.loadGate = { kotlin.coroutines.coroutineContext[Job]!!.cancel(probe) }
        val c = coordinatorOf(vault, auth, uuids = failingUuids())
        assertTrue(launchAccessProbe(c, lease, probe).await())
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun clockCancelLiveReturnsProbeInsideCaller() = runTest {
        val (vault, auth, lease) = liveSetup()
        val probe = CancellationException("probe-clock-cancel")
        var childJob: Job? = null
        val c = coordinatorOf(vault, auth, clock = { childJob!!.cancel(probe); F.NOW }, uuids = failingUuids())
        val matched = CompletableDeferred<Boolean>()
        childJob = launch {
            try {
                c.accessToken(lease)
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        assertTrue(matched.await())
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun clockInvalidateLiveReturnsNoCredentials() = runTest {
        val (vault, auth, lease) = liveSetup()
        val c = coordinatorOf(vault, auth, clock = { lease.invalidate(); F.NOW }, uuids = failingUuids())
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun uuidCancelRotationReturnsProbeInsideCaller() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val probe = CancellationException("probe-uuid-cancel")
        var childJob: Job? = null
        val backing = iteratorOf(F.OP_A, F.KEY_A)
        var calls = 0
        val c = coordinatorOf(
            vault,
            auth,
            uuids = object : Iterator<String> {
                override fun hasNext(): Boolean = true
                override fun next(): String {
                    calls += 1
                    if (calls == 1) childJob!!.cancel(probe)
                    return backing.next()
                }
            },
        )
        val matched = CompletableDeferred<Boolean>()
        childJob = launch {
            try {
                c.accessToken(lease)
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        assertTrue(matched.await())
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun uuidInvalidateRotationWritesNothingNoHttp() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val backing = iteratorOf(F.OP_A, F.KEY_A)
        var calls = 0
        val c = coordinatorOf(
            vault,
            auth,
            uuids = object : Iterator<String> {
                override fun hasNext(): Boolean = true
                override fun next(): String {
                    calls += 1
                    if (calls == 1) lease.invalidate()
                    return backing.next()
                }
            },
        )
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, vault.commits)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun concurrentReauthLatchDuringLiveCallbackAdmitsNothing() = runTest {
        val (vault, auth, lease) = liveSetup()
        val enteredB = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val c = coordinatorOf(vault, auth, uuids = failingUuids())
        val doneB = CompletableDeferred<Boolean>()
        launch {
            try {
                c.withAccessToken(lease) {
                    enteredB.complete(Unit)
                    releaseB.await()
                    "result"
                }
                doneB.complete(false)
            } catch (e: SyncApiException.ReauthenticationRequired) {
                doneB.complete(true)
            } catch (e: Throwable) {
                doneB.complete(false)
            }
        }
        enteredB.await()
        assertFailsWith<SyncApiException.ReauthenticationRequired> {
            c.withAccessToken(lease) { throw SyncApiException.ReauthenticationRequired() }
        }
        releaseB.complete(Unit)
        assertTrue(doneB.await())
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun concurrentCallbackLatchDuringSelectionDeniesDirectAdmission() = runTest {
        val (vault, auth, lease) = liveSetup()
        val enteredB = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val c = coordinatorOf(vault, auth, uuids = failingUuids())
        val doneB = CompletableDeferred<Boolean>()
        launch {
            try {
                c.withAccessToken(lease) {
                    enteredB.complete(Unit)
                    releaseB.await()
                    throw SyncApiException.ReauthenticationRequired()
                }
                doneB.complete(false)
            } catch (e: SyncApiException.ReauthenticationRequired) {
                doneB.complete(true)
            } catch (e: Throwable) {
                doneB.complete(false)
            }
        }
        enteredB.await()
        var armed = true
        val observedA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        vault.loadGate = {
            if (armed) {
                armed = false
                observedA.complete(Unit)
                releaseA.await()
            }
        }
        val doneA = CompletableDeferred<Boolean>()
        launch {
            try {
                c.accessToken(lease)
                doneA.complete(false)
            } catch (e: SyncApiException.ReauthenticationRequired) {
                doneA.complete(true)
            } catch (e: Throwable) {
                doneA.complete(false)
            }
        }
        observedA.await()
        releaseB.complete(Unit)
        assertTrue(doneB.await())
        releaseA.complete(Unit)
        assertTrue(doneA.await())
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun liveLoadInvalidationReturnsNoCredentials() = runTest {
        val (vault, auth, lease) = liveSetup()
        vault.loadGate = { lease.invalidate() }
        val c = coordinatorOf(vault, auth, uuids = failingUuids())
        val err = assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
        assertEquals(SessionRefreshFailure.STALE_SESSION, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun callerCancellationDominatesInvalidationInsideCaller() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val probe = CancellationException("probe-cancel")
        val gate = CompletableDeferred<Unit>()
        auth.refreshGate = { gate.await() }
        auth.answer(F.session(accessValue = "access-2"))
        val c = coordinatorOf(vault, auth)
        val matched = CompletableDeferred<Boolean>()
        val child = launch {
            try {
                c.accessToken(lease)
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        runCurrent()
        child.cancel(probe)
        lease.invalidate()
        gate.complete(Unit)
        assertTrue(matched.await())
    }

    @Test
    fun lateErrorAfterInvalidationIsStaleNotOldCategory() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val gate = CompletableDeferred<Unit>()
        auth.refreshGate = { gate.await() }
        auth.failWith(SyncApiException.Transient())
        val c = coordinatorOf(vault, auth)
        val admitted = CompletableDeferred<Boolean>()
        launch {
            try {
                c.accessToken(lease)
                admitted.complete(false)
            } catch (e: SessionRefreshException) {
                admitted.complete(e.failure == SessionRefreshFailure.STALE_SESSION)
            } catch (e: Throwable) {
                admitted.complete(false)
            }
        }
        runCurrent()
        lease.invalidate()
        gate.complete(Unit)
        assertTrue(admitted.await())
    }

    private suspend fun TestScope.launchAccessProbe(
        c: SessionRefreshCoordinator,
        lease: SessionRefreshLease,
        probe: CancellationException,
    ): CompletableDeferred<Boolean> {
        val matched = CompletableDeferred<Boolean>()
        launch {
            try {
                c.accessToken(lease)
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        return matched
    }

    private suspend fun TestScope.cancelDominatesRacingError(
        error: SyncApiException,
        probeName: String,
    ) {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val probe = CancellationException(probeName)
        auth.script += {
            kotlin.coroutines.coroutineContext[Job]!!.cancel(probe)
            throw error
        }
        val c = coordinatorOf(vault, auth)
        assertTrue(launchAccessProbe(c, lease, probe).await())
        assertEquals(1, auth.refreshCalls)
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val token = c.accessToken(lease)
        assertEquals("access-2", token.token.value)
        assertEquals(2, auth.refreshCalls)
        assertEquals(
            auth.seenAttempts[0].idempotencyKey,
            auth.seenAttempts[1].idempotencyKey,
        )
        assertEquals(
            auth.seenAttempts[0].requestJson,
            auth.seenAttempts[1].requestJson,
        )
    }

    @Test
    fun cancelDominatesRacingReauth() = runTest {
        cancelDominatesRacingError(SyncApiException.ReauthenticationRequired(), "probe-sync-reauth")
    }

    @Test
    fun cancelDominatesRacingWrongContextAuth() = runTest {
        cancelDominatesRacingError(SyncApiException.AuthenticationRequired(), "probe-sync-authn")
    }

    @Test
    fun cancelDominatesRacingMalformed() = runTest {
        cancelDominatesRacingError(SyncApiException.MalformedResponse(), "probe-sync-malformed")
    }

    @Test
    fun cancelDominatesRacingTransient() = runTest {
        cancelDominatesRacingError(SyncApiException.Transient(), "probe-sync-transient")
    }

    @Test
    fun callbackCancelDominatesDirectReauthNoLatch() = runTest {
        val (vault, auth, lease) = liveSetup()
        val probe = CancellationException("probe-callback-reauth")
        val c = coordinatorOf(vault, auth)
        var calls = 0
        val matched = CompletableDeferred<Boolean>()
        launch {
            try {
                c.withAccessToken(lease) {
                    calls += 1
                    kotlin.coroutines.coroutineContext[Job]!!.cancel(probe)
                    throw SyncApiException.ReauthenticationRequired()
                }
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        assertTrue(matched.await())
        assertEquals(1, calls)
        assertEquals("ok", c.withAccessToken(lease) { "ok" })
        assertEquals(1, calls)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun callbackCancelDominatesFirst401NoLatch() = runTest {
        val (vault, auth, lease) = liveSetup()
        val probe = CancellationException("probe-callback-401")
        val c = coordinatorOf(vault, auth)
        var calls = 0
        val matched = CompletableDeferred<Boolean>()
        launch {
            try {
                c.withAccessToken(lease) {
                    calls += 1
                    kotlin.coroutines.coroutineContext[Job]!!.cancel(probe)
                    throw SyncApiException.AuthenticationRequired()
                }
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        assertTrue(matched.await())
        assertEquals(1, calls)
        assertEquals(0, auth.refreshCalls)
        assertEquals("ok", c.withAccessToken(lease) { "ok" })
    }

    @Test
    fun callbackCancelDominatesRepeated401NoLatch() = runTest {
        val (vault, auth, lease) = liveSetup()
        val probe = CancellationException("probe-callback-second-401")
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth, uuids = iteratorOf(F.OP_A, F.KEY_A))
        var calls = 0
        val matched = CompletableDeferred<Boolean>()
        launch {
            try {
                c.withAccessToken(lease) {
                    calls += 1
                    if (calls == 2) kotlin.coroutines.coroutineContext[Job]!!.cancel(probe)
                    throw SyncApiException.AuthenticationRequired()
                }
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        assertTrue(matched.await())
        assertEquals(2, calls)
        assertEquals(1, auth.refreshCalls)
        assertEquals("ok", c.withAccessToken(lease) { "ok" })
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun latchedLeaseAdmitsNoLaterSuccessCredentials() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        auth.failWith(SyncApiException.ReauthenticationRequired())
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SyncApiException.ReauthenticationRequired> { c.accessToken(lease) }
        val loaded = vault.current()
        vault.compareAndSet(
            loaded.revision,
            loaded.record?.copy(session = F.session(accessValue = "access-live")),
        )
        try {
            c.accessToken(lease)
            fail("latched lease must not return credentials")
        } catch (e: SyncApiException.ReauthenticationRequired) {
        }
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun casRevisionExhaustedPropagatesIdentityNoHttp() = runTest {
        val binding = F.binding()
        val record = F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1))
        val vault = FakeSessionVault(VaultSnapshot(revision = Long.MAX_VALUE, record = record))
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2"))
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionVaultException> { c.accessToken(F.sharedLease(binding = binding)) }
        assertEquals(VaultFailure.REVISION_EXHAUSTED, err.failure)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun cancellationIdentityAtLoadInsideCaller() = runTest {
        val (vault, auth, lease) = liveSetup()
        val probe = CancellationException("probe-load")
        vault.loadGate = { throw probe }
        val c = coordinatorOf(vault, auth)
        val matched = CompletableDeferred<Boolean>()
        val child = launch {
            try {
                c.accessToken(lease)
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        assertTrue(matched.await())
    }

    @Test
    fun cancelledWaitingCallerWritesNothing() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val probe = CancellationException("probe-wait")
        vault.blockCasCalls = 1
        auth.answer(F.session(accessValue = "access-2"))
        val c = coordinatorOf(vault, auth)
        val matched = CompletableDeferred<Boolean>()
        val child = launch {
            try {
                c.accessToken(lease)
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        runCurrent()
        child.cancel(probe)
        vault.releaseCas.complete(Unit)
        assertTrue(matched.await())
        assertEquals(1L, vault.current().revision)
    }

    @Test
    fun restartLosesOnlyLatchNeverPending() = runTest {
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        val record = F.record(binding = binding, session = F.session(), pendingRefresh = pending)
        val vault = FakeSessionVault(VaultSnapshot(revision = 9L, record = record))
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val freshLease = F.sharedLease(binding = binding)
        val restarted = F.coordinator(vault, auth, uuids = failingUuids())
        val token = restarted.accessToken(freshLease)
        assertEquals("access-2", token.token.value)
        assertEquals(pending.attempt.idempotencyKey, auth.seenAttempts.single().idempotencyKey)
    }

    @Test
    fun postGrantBeforeCasFailureRetainsExactPendingRestartReplaysSamePair() = runTest {
        val binding = F.binding()
        val record = F.record(binding = binding, session = F.session(accessExpiresAtMillis = F.NOW - 1))
        val vault = FakeSessionVault(VaultSnapshot(revision = 1L, record = record))
        vault.casFailures += null
        vault.casFailures += SessionVaultException(VaultFailure.CONFLICT)
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val lease = F.sharedLease(binding = binding)
        val c = coordinatorOf(vault, auth)
        val err = assertFailsWith<SessionVaultException> { c.accessToken(lease) }
        assertEquals(VaultFailure.CONFLICT, err.failure)
        assertEquals(1, auth.refreshCalls)
        val sent = auth.seenAttempts.single()
        val current = vault.current()
        assertEquals(sent.idempotencyKey, current.record?.pendingRefresh?.attempt?.idempotencyKey)
        assertEquals(sent.requestJson, current.record?.pendingRefresh?.attempt?.requestJson)
        assertEquals(F.opaque("refresh-1"), current.record?.pendingRefresh?.attempt?.refreshToken)
        assertEquals("access-1", current.record?.session?.accessToken?.value)
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val replayed = F.coordinator(vault, auth, uuids = failingUuids())
        val token = replayed.accessToken(F.sharedLease(binding = binding))
        assertEquals("access-2", token.token.value)
        assertEquals(2, auth.refreshCalls)
        assertEquals(sent.idempotencyKey, auth.seenAttempts[1].idempotencyKey)
        assertEquals(sent.requestJson, auth.seenAttempts[1].requestJson)
    }

    @Test
    fun legacyReplayViaRealAdapterAndReopenedVaultRetainsExactUncertainPair() = runTest {
        val storage = InMemoryVaultFileStorage()
        val vault = realVault(storage)
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        val grant = F.session()
        vault.compareAndSet(0L, F.record(binding = binding, session = grant, pendingRefresh = pending))
        val revisionBefore = vault.load().revision
        val authTransport = FakeServiceHttpTransport()
        authTransport.queueOk(F.legacyGrantBody())
        val realAuth = com.dragosfotache.gymtracka.data.sync.http.HttpServiceAuthApi(
            origin = F.origin(),
            transport = authTransport,
        )
        val c = F.coordinator(vault, realAuth, uuids = failingUuids())
        assertFailsWith<SyncApiException.MalformedResponse> { c.accessToken(F.sharedLease(binding = binding)) }
        assertEquals(1, authTransport.capturedRequests.size)
        val reopened = realVault(storage)
        val current = reopened.load()
        assertEquals(revisionBefore, current.revision)
        assertEquals(grant, current.record?.session)
        val restored = current.record?.pendingRefresh?.attempt ?: failNow("pending must survive")
        assertEquals(pending.attempt.idempotencyKey, restored.idempotencyKey)
        assertEquals(pending.attempt.requestJson, restored.requestJson)
        assertEquals(pending.attempt.refreshToken, restored.refreshToken)
    }

    @Test
    fun jcaCipherVaultCoordinationReopenExactBodies() = runTest {
        val storage = InMemoryVaultFileStorage()
        val provider = JcaVaultKeyProvider()
        val vault = realVault(storage, AesGcmVaultCipher(provider))
        val binding = F.binding()
        val pending = F.pending(refreshValue = "refresh-1")
        val grant = F.session()
        vault.compareAndSet(0L, F.record(binding = binding, session = grant, pendingRefresh = pending))
        val reopened = realVault(storage, AesGcmVaultCipher(provider))
        val current = reopened.load()
        assertEquals(grant, current.record?.session)
        val restored = current.record?.pendingRefresh?.attempt ?: failNow("pending must survive JCA restart")
        assertEquals(pending.attempt.idempotencyKey, restored.idempotencyKey)
        assertEquals(pending.attempt.requestJson, restored.requestJson)
        assertEquals(pending.attempt.refreshToken, restored.refreshToken)
        val auth = FakeServiceAuthApi()
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = F.coordinator(reopened, auth, uuids = failingUuids())
        val token = c.accessToken(F.sharedLease(binding = binding))
        assertEquals("access-2", token.token.value)
        assertEquals(pending.attempt.idempotencyKey, auth.seenAttempts.single().idempotencyKey)
    }

    // ---- Concurrency: one shared lease, one rotation ----

    @Test
    fun concurrentExpiredCallersShareOneRotation() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        val gate = CompletableDeferred<Unit>()
        auth.refreshGate = { gate.await() }
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        val c = coordinatorOf(vault, auth)
        val d1 = async { c.accessToken(lease) }
        val d2 = async { c.accessToken(lease) }
        runCurrent()
        gate.complete(Unit)
        assertEquals("access-2", d1.await().token.value)
        assertEquals("access-2", d2.await().token.value)
        assertEquals(1, auth.refreshCalls)
    }

    // ---- withAccessToken callback rules ----

    @Test
    fun withAccessTokenPassesExactTokenAndReturnsResult() = runTest {
        val (vault, auth, lease) = liveSetup()
        val c = coordinatorOf(vault, auth)
        val out = c.withAccessToken(lease) { token ->
            assertEquals("access-1", token.token.value)
            assertEquals(F.binding(), token.binding)
            "ok"
        }
        assertEquals("ok", out)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun withAccessTokenNonAuthCategoryNoResend() = runTest {
        val (vault, auth, lease) = liveSetup()
        val c = coordinatorOf(vault, auth)
        var calls = 0
        assertFailsWith<SyncApiException.RequestTooLarge> {
            c.withAccessToken(lease) {
                calls += 1
                throw SyncApiException.RequestTooLarge()
            }
        }
        assertEquals(1, calls)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun withAccessTokenExplicitReauthLatchesNoRecovery() = runTest {
        val (vault, auth, lease) = liveSetup()
        val c = coordinatorOf(vault, auth)
        var calls = 0
        assertFailsWith<SyncApiException.ReauthenticationRequired> {
            c.withAccessToken(lease) {
                calls += 1
                throw SyncApiException.ReauthenticationRequired()
            }
        }
        assertEquals(1, calls)
        assertFailsWith<SyncApiException.ReauthenticationRequired> {
            c.withAccessToken(lease) { "never" }
        }
        assertEquals(1, calls)
        assertEquals(0, auth.refreshCalls)
    }

    @Test
    fun withAccessTokenUnexpectedCallbackErrorBecomesTransient() = runTest {
        val (vault, auth, lease) = liveSetup()
        val c = coordinatorOf(vault, auth)
        var calls = 0
        assertFailsWith<SyncApiException.Transient> {
            c.withAccessToken(lease) {
                calls += 1
                throw IllegalStateException("callback-fault")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun withAccessTokenSecond401LatchesReauth() = runTest {
        val (vault, auth, lease) = liveSetup(accessExpiresAt = F.NOW - 1)
        auth.answer(F.session(accessValue = "access-2", refreshValue = "refresh-2"))
        auth.answer(F.session(accessValue = "access-3", refreshValue = "refresh-3"))
        val c = coordinatorOf(vault, auth, uuids = iteratorOf(F.OP_A, F.KEY_A, F.OP_B, F.KEY_B))
        var calls = 0
        assertFailsWith<SyncApiException.ReauthenticationRequired> {
            c.withAccessToken(lease) {
                calls += 1
                throw SyncApiException.AuthenticationRequired()
            }
        }
        assertEquals(2, calls)
        assertEquals(2, auth.refreshCalls)
        assertFailsWith<SyncApiException.ReauthenticationRequired> {
            c.withAccessToken(lease) { "never" }
        }
        assertEquals(2, calls)
    }

    @Test
    fun withAccessTokenLateErrorCancelFirstThenInvalidatedLease() = runTest {
        val (vault, auth, lease) = liveSetup()
        val probe = CancellationException("probe-callback")
        val c = coordinatorOf(vault, auth)
        val matched = CompletableDeferred<Boolean>()
        val child = launch {
            try {
                c.withAccessToken(lease) {
                    lease.invalidate()
                    throw probe
                }
                matched.complete(false)
            } catch (e: CancellationException) {
                matched.complete(e === probe)
            } catch (e: Throwable) {
                matched.complete(false)
            }
        }
        assertTrue(matched.await())
    }

    // ---- Secrets, redaction, closed causes ----

    @Test
    fun secretsRedactedAndCausesClosed() = runTest {
        val e1 = SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        val e2 = SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        assertEquals(e1.message, e2.message)
        assertNull(e1.cause)
        assertFalse((e1.message ?: "").contains("access-1"))
        val token = F.opaque("super-secret-value")
        assertFalse(token.toString().contains("super-secret-value"))
        val record = F.record()
        assertFalse(record.toString().contains("access-1"))
    }

    @Test
    fun exceptionSuppressAndCauseInitStayClosed() = runTest {
        val e = SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        e.addSuppressed(RuntimeException("raw-secret"))
        assertTrue(e.suppressed.isEmpty())
        assertNull(e.cause)
        var initFailed = false
        try {
            e.initCause(RuntimeException("raw-cause"))
        } catch (expected: IllegalStateException) {
            initFailed = true
        }
        assertTrue(initFailed)
        assertNull(e.cause)
    }

    @Test
    fun invalidationIsIdempotentAndPermanent() = runTest {
        val (_, _, lease) = liveSetup()
        lease.invalidate()
        lease.invalidate()
        val (vault, auth, _) = liveSetup()
        val c = coordinatorOf(vault, auth)
        assertFailsWith<SessionRefreshException> { c.accessToken(lease) }
            .also { assertEquals(SessionRefreshFailure.STALE_SESSION, it.failure) }
    }
}

private fun iteratorOf(vararg values: String): Iterator<String> = values.iterator()

private fun failingUuids(): Iterator<String> = object : Iterator<String> {
    override fun hasNext(): Boolean = true
    override fun next(): String = throw AssertionError("no UUID expected on this path")
}

private fun throwingUuids(): Iterator<String> = object : Iterator<String> {
    override fun hasNext(): Boolean = true
    override fun next(): String = throw RuntimeException("uuid-fault")
}

private class JcaVaultKeyProvider : VaultKeyProvider {
    private var key: SecretKey? = null

    override fun key(createIfMissing: Boolean): SecretKey {
        key?.let { return it }
        if (!createIfMissing) throw IllegalStateException("no key")
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return generator.generateKey().also { key = it }
    }
}

private class FailReplaceStorage(
    var throwBefore: Boolean = false,
    var writeThenThrow: Boolean = false,
) : VaultFileStorage {
    private var bytes: ByteArray? = null
    private val lock = Any()

    override fun read(): ByteArray? = synchronized(lock) { bytes?.copyOf() }

    override fun replace(bytes: ByteArray) {
        if (throwBefore) throw IOException("disk-full")
        synchronized(lock) { this.bytes = bytes.copyOf() }
        if (writeThenThrow) throw IOException("lost-after-write")
    }
}
