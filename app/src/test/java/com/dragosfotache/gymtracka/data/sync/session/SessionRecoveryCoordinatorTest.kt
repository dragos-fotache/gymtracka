package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.HttpServiceAuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R04c independent QA: startup/session-recovery coordination.
 *
 * Observable black-box tests built only from the R04c spec's normative API
 * facts plus QA-owned fixtures. No production source inspected. Deterministic
 * JVM: TestScope/virtual time/deferred gates, no sleeps. Cancellation
 * identity is asserted INSIDE the actual caller.
 *
 * Covers acceptance cases 1-10 and 15 (startup algorithm, precedence gates,
 * races, validation/redaction). Runtime authority fencing lives in
 * SessionRecoveryAuthorizationTest.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionRecoveryCoordinatorTest {
    private val F = SessionRecoveryTestFixtures

    private fun setup(
        origin: String? = F.ORIGIN_ROOT,
        vaultRecord: VaultSessionRecord? = F.record(),
        vaultRevision: Long = 1L,
        authoritySnapshot: RecoveryRoomSnapshot = F.activeCompleteSnapshot(),
        clock: SessionRecoveryTestFixtures.CountingClock = SessionRecoveryTestFixtures.CountingClock(),
        uuids: SessionRecoveryTestFixtures.CountingUuids = SessionRecoveryTestFixtures.CountingUuids(),
    ): Quad {
        val vault = F.vaultWith(record = vaultRecord, revision = vaultRevision)
        val authority = FakeRecoveryAuthority()
        authority.stable(authoritySnapshot)
        val auth = F.auth()
        val coordinator = F.coordinator(
            origin = origin?.let { F.origin(it) },
            vault = vault,
            authority = authority,
            authApi = auth,
            clock = clock,
            uuids = uuids,
        )
        return Quad(coordinator, vault, authority, auth, clock, uuids)
    }

    private data class Quad(
        val coordinator: SessionRecoveryCoordinator,
        val vault: FakeSessionVault,
        val authority: FakeRecoveryAuthority,
        val auth: FakeServiceAuthApi,
        val clock: SessionRecoveryTestFixtures.CountingClock,
        val uuids: SessionRecoveryTestFixtures.CountingUuids,
    )

    private fun Quad.assertSilent(message: String) {
        assertEquals("$message: vault loads", 0, vault.loads)
        assertEquals("$message: vault commits", 0, vault.commits)
        assertEquals("$message: network", 0, auth.refreshCalls)
        assertEquals("$message: clock", 0, clock.calls)
        assertEquals("$message: uuid", 0, uuids.calls)
    }

    // ---- Case 1: initial state and null origin ----

    @Test
    fun constructorIsPrivacyLockedWithNoSeamActivity() = runTest {
        val q = setup()
        assertTrue(q.coordinator.state is SessionRecoveryState.PrivacyLocked)
        assertEquals(0, q.authority.reads)
        q.assertSilent("constructor")
    }

    @Test
    fun nullOriginIsLocalOnlyWithZeroActivity() = runTest {
        val q = setup(origin = null)
        val state = q.coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.LocalOnly)
        assertEquals(
            SessionRecoveryReason.CONFIGURATION_UNAVAILABLE,
            (state as SessionRecoveryState.LocalOnly).reason,
        )
        assertEquals(0, q.authority.reads)
        q.assertSilent("null origin")
    }

    // ---- Case 2: signed-out and preparing/transitioning ----

    @Test
    fun signedOutEmptyIsLocalOnlyWithoutVaultAccess() = runTest {
        val authority = FakeRecoveryAuthority(F.signedOutSnapshot())
        val vault = F.vaultWith(record = null, revision = 0L)
        val auth = F.auth()
        val clock = SessionRecoveryTestFixtures.CountingClock()
        val uuids = SessionRecoveryTestFixtures.CountingUuids()
        val coordinator = F.coordinator(
            vault = vault, authority = authority, authApi = auth, clock = clock, uuids = uuids,
        )
        val state = coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.LocalOnly)
        assertEquals(SessionRecoveryReason.SIGNED_OUT, (state as SessionRecoveryState.LocalOnly).reason)
        assertEquals(0, vault.loads)
        assertEquals(0, vault.commits)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, clock.calls)
        assertEquals(0, uuids.calls)
    }

    @Test
    fun signedOutRetainsPrivateCacheAndNeverAdoptsOrphanVault() = runTest {
        val authority = FakeRecoveryAuthority(F.signedOutSnapshot(cachedAccountId = F.ACCOUNT_ID))
        val orphan = F.record()
        val vault = F.vaultWith(record = orphan, revision = 4L)
        val auth = F.auth()
        val coordinator = F.coordinator(vault = vault, authority = authority, authApi = auth)
        val state = coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.LocalOnly)
        assertEquals(SessionRecoveryReason.SIGNED_OUT, (state as SessionRecoveryState.LocalOnly).reason)
        assertEquals(0, vault.loads)
        assertEquals(0, vault.commits)
        assertEquals(0, auth.refreshCalls)
        assertEquals(orphan, vault.current().record)
    }

    @Test
    fun preparingAndTransitioningAreLockedWithoutVaultOrNetwork() = runTest {
        for (phase in listOf(RecoveryRoomPhase.PREPARING, RecoveryRoomPhase.TRANSITIONING)) {
            val q = setup(authoritySnapshot = F.roomSnapshot(phase = phase, baselineComplete = false))
            val state = q.coordinator.reconcile()
            assertTrue("$phase", state is SessionRecoveryState.Locked)
            assertEquals(
                SessionRecoveryReason.TRANSITION_REQUIRED,
                (state as SessionRecoveryState.Locked).reason,
            )
            assertEquals("$phase vault", 0, q.vault.loads)
            assertEquals("$phase network", 0, q.auth.refreshCalls)
        }
    }

    // ---- Case 3: baseline and origin gates ----

    @Test
    fun activeIncompleteBaselineIsLockedWithoutVaultOrNetwork() = runTest {
        val q = setup(authoritySnapshot = F.roomSnapshot(baselineComplete = false))
        val state = q.coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.BASELINE_INCOMPLETE,
            (state as SessionRecoveryState.Locked).reason,
        )
        assertEquals(0, q.vault.loads)
        assertEquals(0, q.auth.refreshCalls)
    }

    @Test
    fun activeWrongConfiguredOriginIsLockedWithoutVaultOrNetwork() = runTest {
        val vault = F.vaultWith(record = F.record())
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot())
        val auth = F.auth()
        val coordinator = F.coordinator(
            origin = F.origin(F.OTHER_ORIGIN_ROOT),
            vault = vault,
            authority = authority,
            authApi = auth,
        )
        val state = coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.IDENTITY_MISMATCH,
            (state as SessionRecoveryState.Locked).reason,
        )
        assertEquals(0, vault.loads)
        assertEquals(0, auth.refreshCalls)
    }

    // ---- Case 4: vault absence and failures ----

    @Test
    fun emptyAndTombstonedVaultAreMissingWithNoRepairOrNetwork() = runTest {
        for ((record, revision) in listOf<Pair<VaultSessionRecord?, Long>>(null to 0L, null to 7L)) {
            val q = setup(vaultRecord = record, vaultRevision = revision)
            val state = q.coordinator.reconcile()
            assertTrue("rev=$revision", state is SessionRecoveryState.Locked)
            assertEquals(
                SessionRecoveryReason.VAULT_MISSING,
                (state as SessionRecoveryState.Locked).reason,
            )
            assertEquals(0, q.vault.commits)
            assertEquals(0, q.auth.refreshCalls)
        }
    }

    @Test
    fun vaultLoadFailuresAreUnavailableWithNoRepairOrNetwork() = runTest {
        for (failure in listOf(
            VaultFailure.UNREADABLE,
            VaultFailure.UNSUPPORTED_VERSION,
            VaultFailure.UNAVAILABLE,
            VaultFailure.INVALID_RECORD,
        )) {
            val q = setup()
            q.vault.loadFailures += SessionVaultException(failure)
            val state = q.coordinator.reconcile()
            assertTrue("$failure", state is SessionRecoveryState.Locked)
            assertEquals(
                SessionRecoveryReason.VAULT_UNAVAILABLE,
                (state as SessionRecoveryState.Locked).reason,
            )
            assertEquals("$failure commits", 0, q.vault.commits)
            assertEquals("$failure network", 0, q.auth.refreshCalls)
        }
    }

    // ---- Case 5: matching active handle and closed mismatches ----

    @Test
    fun matchingActiveCompleteYieldsOneUsableHandle() = runTest {
        val q = setup()
        val state = q.coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Active)
        assertEquals("startup must not classify freshness", 0, q.clock.calls)
        val active = state as SessionRecoveryState.Active
        assertEquals(F.identity(), active.session.identity)
        q.auth.answer(F.session(accessValue = "access-live", refreshValue = "refresh-live"))
        val binding = active.session.withAccessToken { token -> token.binding }
        assertEquals(F.binding(), binding)
    }

    @Test
    fun everyFieldMismatchFailsClosedWithNoNetwork() = runTest {
        val base = F.record()
        val mismatched = listOf(
            "account" to base.copy(
                binding = F.binding(accountId = F.OTHER_ACCOUNT_ID),
                session = F.session(accountId = F.OTHER_ACCOUNT_ID),
            ),
            "origin" to base.copy(binding = F.binding(root = F.OTHER_ORIGIN_ROOT)),
            "generation" to base.copy(binding = F.binding(generation = 2L)),
            "operation" to base.copy(operationId = F.OP_B),
        )
        for ((name, record) in mismatched) {
            val q = setup(vaultRecord = record)
            val state = q.coordinator.reconcile()
            assertTrue("$name", state is SessionRecoveryState.Locked)
            assertEquals(
                "$name",
                SessionRecoveryReason.IDENTITY_MISMATCH,
                (state as SessionRecoveryState.Locked).reason,
            )
            assertEquals("$name network", 0, q.auth.refreshCalls)
            assertEquals("$name commits", 0, q.vault.commits)
        }
    }

    // ---- Case 6: prepared vault needs future recovery, no promotion ----

    @Test
    fun matchingPreparedVaultRequiresRecoveryAndExposesNothing() = runTest {
        val prepared = F.record(phase = VaultSessionPhase.PREPARED)
        val q = setup(vaultRecord = prepared)
        val state = q.coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.TRANSITION_REQUIRED,
            (state as SessionRecoveryState.Locked).reason,
        )
        assertEquals(0, q.vault.commits)
        assertEquals(0, q.auth.refreshCalls)
        assertEquals(prepared, q.vault.current().record)
    }

    // ---- Case 7: expired grants stay eligible offline ----

    @Test
    fun expiredGrantStaysActiveWithoutClockOrNetwork() = runTest {
        val expired = F.record(session = F.expiredSession())
        val q = setup(vaultRecord = expired)
        val state = q.coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Active)
        assertEquals(0, q.clock.calls)
        assertEquals(0, q.auth.refreshCalls)
        assertEquals(expired.session.refreshToken, q.vault.current().record?.session?.refreshToken)
    }

    // ---- Case 8: exact restored pending replay across real encrypted restart ----

    @Test
    fun realVaultRestartReplaysExactPendingBodyWithOneRotation() = runTest {
        val storage = InMemoryVaultFileStorage()
        val cipher = IdentityVaultCipher()
        val first = realVault(storage, cipher, Dispatchers.Unconfined)
        val body = "{\n  \"refreshToken\" : \"refresh-old\" \n}"
        val pending = VaultPendingRefresh(
            operationId = F.OP_A,
            attempt = com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt.fromJson(F.KEY_A, body),
        )
        val seeded = F.record(session = F.expiredSession(refreshValue = "refresh-old"), pendingRefresh = pending)
        first.compareAndSet(0L, seeded)

        val restarted = realVault(storage, IdentityVaultCipher(), Dispatchers.Unconfined)
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        val auth = F.auth()
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        auth.answer(rotated)
        val coordinator = F.coordinator(vault = restarted, authority = authority, authApi = auth)
        val state = coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Active)
        val active = state as SessionRecoveryState.Active
        val returned = active.session.withAccessToken { token -> token.token.value }
        assertEquals("access-new", returned)
        assertEquals(1, auth.refreshCalls)
        assertEquals(F.KEY_A, auth.seenAttempts.single().idempotencyKey)
        assertEquals(body, auth.seenAttempts.single().requestJson)
        assertEquals("refresh-new", restarted.load().record?.session?.refreshToken?.value)
    }

    @Test
    fun realVaultRestartPreservesEscapedPendingBody() = runTest {
        val body = "{ \"refreshToken\" : \"re\\/fresh-old\" }"
        val storage = InMemoryVaultFileStorage()
        realVault(storage, IdentityVaultCipher(), Dispatchers.Unconfined).compareAndSet(
            0L,
            F.record(
                session = F.session(
                    accessValue = "access-old",
                    accessExpiresAtMillis = F.NOW - 1_000L,
                    refreshValue = "re/fresh-old",
                    refreshExpiresAtMillis = F.NOW - 1_000L,
                ),
                pendingRefresh = VaultPendingRefresh(
                    operationId = F.OP_A,
                    attempt = ServiceRefreshAttempt.fromJson(F.KEY_A, body),
                ),
            ),
        )
        val restarted = realVault(storage, IdentityVaultCipher(), Dispatchers.Unconfined)
        // Distinct restored instances carry the exact escaped bytes.
        assertEquals(body, restarted.load().record?.pendingRefresh?.attempt?.requestJson)
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        val auth = F.auth()
        auth.answer(F.session(accessValue = "access-new", refreshValue = "refresh-new"))
        val coordinator = F.coordinator(vault = restarted, authority = authority, authApi = auth)
        assertTrue(coordinator.reconcile() is SessionRecoveryState.Active)
        val active = coordinator.state as SessionRecoveryState.Active
        assertEquals("access-new", active.session.withAccessToken { it.token.value })
        assertEquals(F.KEY_A, auth.seenAttempts.single().idempotencyKey)
        assertEquals(body, auth.seenAttempts.single().requestJson)
    }

    @Test
    fun strictMalformedReplayPreservesExactUncertainData() = runTest {
        val exactPending = F.multilinePending(refreshValue = "refresh-old")
        val seeded = F.record(
            session = F.expiredSession(refreshValue = "refresh-old"),
            pendingRefresh = exactPending,
        )
        val q = setup(vaultRecord = seeded)
        q.auth.failWith(SyncApiException.MalformedResponse())
        val state = q.coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Active)
        val active = state as SessionRecoveryState.Active
        val error = try {
            active.session.withAccessToken { "unexpected" }
            null
        } catch (e: SyncApiException.MalformedResponse) {
            e
        } catch (e: Throwable) {
            null
        }
        assertTrue("malformed failure must propagate closed", error != null)
        val current = q.vault.current().record
        assertEquals(F.OP_A, current?.pendingRefresh?.operationId)
        assertEquals(F.KEY_A, current?.pendingRefresh?.attempt?.idempotencyKey)
        assertEquals(
            exactPending.attempt.requestJson,
            current?.pendingRefresh?.attempt?.requestJson,
        )
        assertEquals("refresh-old", current?.session?.refreshToken?.value)
        assertTrue(q.coordinator.state is SessionRecoveryState.Active)
    }

    @Test
    fun transientFailurePreservesExactPendingWithSingleAttempt() = runTest {
        val body = " { \"refreshToken\" : \"refresh-old\" } "
        val seeded = F.record(
            session = F.expiredSession(refreshValue = "refresh-old"),
            pendingRefresh = VaultPendingRefresh(
                operationId = F.OP_A,
                attempt = ServiceRefreshAttempt.fromJson(F.KEY_A, body),
            ),
        )
        val q = setup(vaultRecord = seeded)
        q.auth.failWith(SyncApiException.Transient())
        val state = q.coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Active)
        val active = state as SessionRecoveryState.Active
        val error = try {
            active.session.withAccessToken { "unexpected" }
            null
        } catch (e: SyncApiException.Transient) {
            e
        } catch (e: Throwable) {
            null
        }
        assertTrue("transient must propagate its category", error != null)
        assertEquals("one actual refresh attempt", 1, q.auth.refreshCalls)
        assertEquals(F.KEY_A, q.auth.seenAttempts.single().idempotencyKey)
        assertEquals(0, q.vault.commits)
        assertEquals(0, q.uuids.calls)
        val current = q.vault.current().record
        assertEquals(F.OP_A, current?.pendingRefresh?.operationId)
        assertEquals(F.KEY_A, current?.pendingRefresh?.attempt?.idempotencyKey)
        assertEquals(body, current?.pendingRefresh?.attempt?.requestJson)
        assertEquals(OpaqueToken("refresh-old"), current?.pendingRefresh?.attempt?.refreshToken)
        assertEquals("refresh-old", current?.session?.refreshToken?.value)
        assertEquals(seeded.session.accessExpiresAtMillis, current?.session?.accessExpiresAtMillis)
        assertEquals(seeded.session.refreshExpiresAtMillis, current?.session?.refreshExpiresAtMillis)
        assertTrue(q.coordinator.state is SessionRecoveryState.Active)
    }

    @Test
    fun injectedCancelPreservesExactPendingWithSingleAttempt() = runTest {
        val body = " { \"refreshToken\" : \"refresh-old\" } "
        val seeded = F.record(
            session = F.expiredSession(refreshValue = "refresh-old"),
            pendingRefresh = VaultPendingRefresh(
                operationId = F.OP_A,
                attempt = ServiceRefreshAttempt.fromJson(F.KEY_A, body),
            ),
        )
        val q = setup(vaultRecord = seeded)
        val probe = CancellationException("qa-transport-cancel")
        q.auth.cancelWith(probe)
        val state = q.coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Active)
        val active = state as SessionRecoveryState.Active
        val caught = try {
            active.session.withAccessToken { "unexpected" }
            null
        } catch (e: Throwable) {
            e
        }
        assertSame(probe, caught)
        assertEquals("one actual refresh attempt", 1, q.auth.refreshCalls)
        assertEquals(F.KEY_A, q.auth.seenAttempts.single().idempotencyKey)
        assertEquals(0, q.vault.commits)
        assertEquals(0, q.uuids.calls)
        val current = q.vault.current().record
        assertEquals(F.OP_A, current?.pendingRefresh?.operationId)
        assertEquals(F.KEY_A, current?.pendingRefresh?.attempt?.idempotencyKey)
        assertEquals(body, current?.pendingRefresh?.attempt?.requestJson)
        assertEquals("refresh-old", current?.session?.refreshToken?.value)
        assertTrue(q.coordinator.state is SessionRecoveryState.Active)
    }

    // ---- Case 9: mid-startup changes cannot publish; equal instances may pass ----

    @Test
    fun authorityChangeBetweenStartupReadsCannotPublish() = runTest {
        val first = F.activeCompleteSnapshot(revision = 9L)
        val changed = first.copy(revision = 10L)
        val authority = FakeRecoveryAuthority()
        authority.scripted(first, changed, changed, changed)
        val vault = F.vaultWith(record = F.record(), revision = 1L)
        val coordinator = F.coordinator(vault = vault, authority = authority, authApi = F.auth())
        val state = coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.IDENTITY_MISMATCH,
            (state as SessionRecoveryState.Locked).reason,
        )
    }

    @Test
    fun vaultChangeBetweenStartupReadsCannotPublish() = runTest {
        val vault = F.vaultWith(record = F.record(), revision = 1L)
        var loads = 0
        vault.loadGate = {
            loads += 1
            if (loads == 2) {
                vault.compareAndSet(1L, F.record(operationId = F.OP_B))
            }
        }
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        val coordinator = F.coordinator(vault = vault, authority = authority, authApi = F.auth())
        val state = coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.IDENTITY_MISMATCH,
            (state as SessionRecoveryState.Locked).reason,
        )
    }

    @Test
    fun equalRestoredInstancesPassStartupChecks() = runTest {
        val snapshot = F.activeCompleteSnapshot(revision = 9L)
        val authority = FakeRecoveryAuthority()
        authority.scripted(snapshot, snapshot.copy(), snapshot.copy(), snapshot.copy())
        val storage = InMemoryVaultFileStorage()
        val seeded = F.record()
        realVault(storage, IdentityVaultCipher(), Dispatchers.Unconfined).compareAndSet(0L, seeded)
        val restarted = realVault(storage, IdentityVaultCipher(), Dispatchers.Unconfined)
        val coordinator = F.coordinator(vault = restarted, authority = authority, authApi = F.auth())
        val state = coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Active)
    }

    // ---- Case 10: cancellation, errors, newest-wins, invalidate ----

    @Test
    fun authorityFailureLocksButPreservesDataForLaterSuccess() = runTest {
        val q = setup()
        q.authority.failNow(RuntimeException("qa-authority-boom"))
        val locked = q.coordinator.reconcile()
        assertTrue(locked is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.AUTHORITY_UNAVAILABLE,
            (locked as SessionRecoveryState.Locked).reason,
        )
        val healthy = FakeRecoveryAuthority(F.activeCompleteSnapshot())
        val retry = F.coordinator(vault = q.vault, authority = healthy, authApi = q.auth)
        assertTrue(retry.reconcile() is SessionRecoveryState.Active)
    }

    @Test
    fun injectedCancellationPropagatesIdenticallyAndLeavesPrivacyLocked() = runTest {
        val q = setup()
        val probe = CancellationException("qa-injected")
        q.authority.failNow(probe)
        var caught: Throwable? = null
        try {
            q.coordinator.reconcile()
        } catch (e: Throwable) {
            caught = e
        }
        assertSame(probe, caught)
        assertTrue(q.coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    @Test
    fun callerCancellationDuringBlockedLoadKeepsPrivacyLocked() = runTest {
        val gate = CompletableDeferred<Unit>()
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        val vault = F.vaultWith(record = F.record(), revision = 1L)
        vault.loadGate = { gate.await() }
        val coordinator = F.coordinator(vault = vault, authority = authority, authApi = F.auth())
        val probe = CancellationException("qa-caller")
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                coordinator.reconcile()
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        job.cancel(probe)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
        assertFalse(coordinator.state is SessionRecoveryState.Active)
    }

    @Test
    fun cancelledNewerReconcileLeavesPrivacyLockedNotOlderActive() = runTest {
        val q = setup()
        val first = q.coordinator.reconcile()
        assertTrue(first is SessionRecoveryState.Active)
        val old = (first as SessionRecoveryState.Active).session
        val gate = CompletableDeferred<Unit>()
        q.authority.readGate = { gate.await() }
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                q.coordinator.reconcile()
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        // The newer reconcile started: the old handle is immediately dead and
        // PrivacyLocked is published before any read completes.
        assertTrue(q.coordinator.state is SessionRecoveryState.PrivacyLocked)
        val loadsBefore = q.vault.loads
        val dead = try {
            old.withAccessToken { "unexpected" }
            null
        } catch (e: SessionRecoveryException) {
            e
        }
        assertTrue("old handle must die at newer start", dead != null)
        assertEquals(SessionRecoveryFailure.STALE_SESSION, dead?.failure)
        assertEquals(loadsBefore, q.vault.loads)
        assertEquals(0, q.auth.refreshCalls)
        val probe = CancellationException("qa-newer")
        job.cancel(probe)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        // Cancelling the current call leaves PrivacyLocked; it cannot restore
        // the older Active handle.
        assertTrue(q.coordinator.state is SessionRecoveryState.PrivacyLocked)
        assertFalse(q.coordinator.state is SessionRecoveryState.Active)
    }

    @Test
    fun overlappingReconcilesNewestWinsAndOlderIsStale() = runTest {
        val gate = CompletableDeferred<Unit>()
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        authority.readGate = { callIndex -> if (callIndex == 1) gate.await() }
        val vault = F.vaultWith(record = F.record(), revision = 1L)
        val auth = F.auth()
        val coordinator = F.coordinator(vault = vault, authority = authority, authApi = auth)
        val finished = CompletableDeferred<Result<SessionRecoveryState>>()
        val job = launch {
            try {
                finished.complete(Result.success(coordinator.reconcile()))
            } catch (e: Throwable) {
                finished.complete(Result.failure(e))
            }
        }
        runCurrent()
        val newer = coordinator.reconcile()
        assertTrue(newer is SessionRecoveryState.Active)
        gate.complete(Unit)
        advanceUntilIdle()
        val stale = finished.await().exceptionOrNull()
        assertTrue("older reconcile must be stale", stale is SessionRecoveryException)
        assertEquals(SessionRecoveryFailure.STALE_SESSION, (stale as SessionRecoveryException).failure)
        assertTrue(job.isCompleted)
        val state = coordinator.state
        assertTrue(state is SessionRecoveryState.Active)
        // Seeded access is live with no pending: runtime returns the stored
        // token with zero refresh calls; staging an answer cannot change that.
        val value = (state as SessionRecoveryState.Active).session.withAccessToken { it.token.value }
        assertEquals("access-1", value)
        assertEquals("no refresh for live access", 0, auth.refreshCalls)
    }

    @Test
    fun invalidateDuringBlockedReadIsSynchronousAndStalesOlderCall() = runTest {
        val gate = CompletableDeferred<Unit>()
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        authority.readGate = { gate.await() }
        val coordinator = F.coordinator(
            vault = F.vaultWith(record = F.record(), revision = 1L),
            authority = authority,
            authApi = F.auth(),
        )
        val finished = CompletableDeferred<Result<SessionRecoveryState>>()
        val job = launch {
            try {
                finished.complete(Result.success(coordinator.reconcile()))
            } catch (e: Throwable) {
                finished.complete(Result.failure(e))
            }
        }
        runCurrent()
        coordinator.invalidate()
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
        coordinator.invalidate()
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
        gate.complete(Unit)
        advanceUntilIdle()
        val stale = finished.await().exceptionOrNull()
        assertTrue("blocked reconcile must be stale after invalidate", stale is SessionRecoveryException)
        assertEquals(SessionRecoveryFailure.STALE_SESSION, (stale as SessionRecoveryException).failure)
        assertTrue(job.isCompleted)
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    // ---- Case 15: validation, redaction, closed faults ----

    @Test
    fun invalidIdentityInputsThrowFixedRedactedError() = runTest {
        val binding = F.binding()
        val failures = listOf(
            F.assertInvalidInput { RecoveryIdentity(binding = binding, operationId = "not-a-uuid") },
            F.assertInvalidInput { RecoveryIdentity(binding = binding, operationId = "") },
            F.assertInvalidInput {
                RecoveryIdentity(binding = binding, operationId = F.ACCOUNT_ID.uppercase())
            },
        )
        val first = failures.first()
        for (other in failures.drop(1)) {
            assertEquals(first.message, other.message)
        }
        assertNull(first.cause)
        assertTrue(first.suppressed.isEmpty())
        F.assertRedacted(first.message ?: "", listOf(F.ACCOUNT_ID, F.OP_A, "not-a-uuid"))
        F.assertRedacted(
            RecoveryIdentity(binding = binding, operationId = F.OP_A).toString(),
            listOf(F.ACCOUNT_ID, F.OP_A),
        )
    }

    @Test
    fun invalidSnapshotInputsThrowWithoutEcho() = runTest {
        val binding = F.binding()
        val identity = F.identity()
        F.assertInvalidInput {
            RecoveryRoomSnapshot(
                revision = -1L,
                sessionGeneration = 1L,
                cachedAccountId = F.ACCOUNT_ID,
                phase = RecoveryRoomPhase.ACTIVE,
                identity = identity,
                baselineComplete = true,
            )
        }
        F.assertInvalidInput {
            RecoveryRoomSnapshot(
                revision = 1L,
                sessionGeneration = -1L,
                cachedAccountId = F.ACCOUNT_ID,
                phase = RecoveryRoomPhase.ACTIVE,
                identity = identity,
                baselineComplete = true,
            )
        }
        F.assertInvalidInput {
            RecoveryRoomSnapshot(
                revision = 1L,
                sessionGeneration = 1L,
                cachedAccountId = null,
                phase = RecoveryRoomPhase.ACTIVE,
                identity = identity,
                baselineComplete = true,
            )
        }
        F.assertInvalidInput {
            RecoveryRoomSnapshot(
                revision = 0L,
                sessionGeneration = 0L,
                cachedAccountId = null,
                phase = RecoveryRoomPhase.SIGNED_OUT,
                identity = identity,
                baselineComplete = false,
            )
        }
        F.assertInvalidInput {
            RecoveryRoomSnapshot(
                revision = 0L,
                sessionGeneration = 0L,
                cachedAccountId = null,
                phase = RecoveryRoomPhase.SIGNED_OUT,
                identity = null,
                baselineComplete = true,
            )
        }
        F.assertInvalidInput {
            RecoveryRoomSnapshot(
                revision = 1L,
                sessionGeneration = 2L,
                cachedAccountId = binding.accountId,
                phase = RecoveryRoomPhase.ACTIVE,
                identity = identity,
                baselineComplete = true,
            )
        }
        F.assertInvalidInput {
            RecoveryRoomSnapshot(
                revision = 1L,
                sessionGeneration = 1L,
                cachedAccountId = F.OTHER_ACCOUNT_ID,
                phase = RecoveryRoomPhase.ACTIVE,
                identity = identity,
                baselineComplete = true,
            )
        }
        F.assertInvalidInput {
            RecoveryRoomSnapshot(
                revision = 1L,
                sessionGeneration = 1L,
                cachedAccountId = F.ACCOUNT_ID,
                phase = RecoveryRoomPhase.ACTIVE,
                identity = null,
                baselineComplete = true,
            )
        }
        val copied = F.assertInvalidInput {
            F.activeCompleteSnapshot().copy(revision = -5L)
        }
        assertNull(copied.cause)
    }

    @Test
    fun snapshotEqualityCoversEveryFieldAndDisplaysStayRedacted() = runTest {
        val base = F.activeCompleteSnapshot()
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertFalse(base == base.copy(revision = base.revision + 1))
        assertFalse(
            base == base.copy(
                sessionGeneration = 2L,
                identity = F.identity(binding = F.binding(generation = 2L)),
            ),
        )
        assertFalse(
            base == base.copy(
                cachedAccountId = F.OTHER_ACCOUNT_ID,
                identity = F.identity(binding = F.binding(accountId = F.OTHER_ACCOUNT_ID)),
            ),
        )
        assertFalse(base == base.copy(phase = RecoveryRoomPhase.TRANSITIONING))
        assertFalse(base == base.copy(identity = F.identity(operationId = F.OP_B)))
        assertFalse(base == base.copy(baselineComplete = !base.baselineComplete))
        F.assertRedacted(base.toString(), listOf(F.ACCOUNT_ID, F.OP_A))
        F.assertRedacted(F.signedOutSnapshot(cachedAccountId = F.ACCOUNT_ID).toString(), listOf(F.ACCOUNT_ID))
        F.assertRedacted(
            SessionRecoveryState.LocalOnly(SessionRecoveryReason.SIGNED_OUT).toString(),
            listOf(F.ACCOUNT_ID, F.OP_A),
        )
        F.assertRedacted(
            SessionRecoveryState.Locked(SessionRecoveryReason.IDENTITY_MISMATCH).toString(),
            listOf(F.ACCOUNT_ID, F.OP_A),
        )
    }

    @Test
    fun unexpectedAuthorityFaultsAreClosedAsUnavailable() = runTest {
        for (fault in listOf<Throwable>(
            RuntimeException("qa-boom"),
            SessionVaultException(VaultFailure.UNREADABLE),
        )) {
            val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot())
            authority.failNow(fault)
            val coordinator = F.coordinator(
                vault = F.vaultWith(record = F.record(), revision = 1L),
                authority = authority,
                authApi = F.auth(),
            )
            val state = coordinator.reconcile()
            assertTrue(state is SessionRecoveryState.Locked)
            assertEquals(
                SessionRecoveryReason.AUTHORITY_UNAVAILABLE,
                (state as SessionRecoveryState.Locked).reason,
            )
        }
    }

    @Test
    fun boundaryRevisionsConstructButNegativesDoNot() = runTest {
        val snapshot = RecoveryRoomSnapshot(
            revision = Long.MAX_VALUE,
            sessionGeneration = Long.MAX_VALUE,
            cachedAccountId = F.ACCOUNT_ID,
            phase = RecoveryRoomPhase.ACTIVE,
            identity = RecoveryIdentity(
                binding = F.binding(generation = Long.MAX_VALUE),
                operationId = F.OP_A,
            ),
            baselineComplete = true,
        )
        assertEquals(Long.MAX_VALUE, snapshot.revision)
        F.assertInvalidInput { F.activeCompleteSnapshot().copy(sessionGeneration = -1L) }
    }

    // ---- Cancellation-gate clarification: injected vs caller cancellation ----
    //
    // Injected CancellationException stays identical within an active actual
    // caller even when invalidate, a newer reconcile, or an authority change
    // races its delivery; no stale/authority category may mask it. An actual
    // cancelled-caller cause dominates injected cancellation.

    @Test
    fun injectedAuthorityCancellationRacingInvalidateStaysIdentical() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fault: Throwable? = null
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        authority.readGate = { _ ->
            gate.await()
            fault?.let { throw it }
        }
        val coordinator = F.coordinator(
            vault = F.vaultWith(record = F.record(), revision = 1L),
            authority = authority,
            authApi = F.auth(),
        )
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                coordinator.reconcile()
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        coordinator.invalidate()
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
        val probe = CancellationException("qa-injected-race")
        fault = probe
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    @Test
    fun injectedVaultCancellationRacingInvalidateStaysIdentical() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fault: Throwable? = null
        val vault = F.vaultWith(record = F.record(), revision = 1L)
        vault.loadGate = {
            gate.await()
            fault?.let { throw it }
        }
        val coordinator = F.coordinator(
            vault = vault,
            authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L)),
            authApi = F.auth(),
        )
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                coordinator.reconcile()
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        coordinator.invalidate()
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
        val probe = CancellationException("qa-injected-vault-race")
        fault = probe
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    @Test
    fun injectedAuthorityCancellationRacingNewerReconcileLeavesNewerActive() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fault: Throwable? = null
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        authority.readGate = { callIndex ->
            if (callIndex == 1) {
                gate.await()
                fault?.let { throw it }
            }
        }
        val vault = F.vaultWith(record = F.record(), revision = 1L)
        val auth = F.auth()
        val coordinator = F.coordinator(vault = vault, authority = authority, authApi = auth)
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                coordinator.reconcile()
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        val newer = coordinator.reconcile()
        assertTrue(newer is SessionRecoveryState.Active)
        val probe = CancellationException("qa-injected-newer-race")
        fault = probe
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        val state = coordinator.state
        assertTrue(state is SessionRecoveryState.Active)
        // Seeded access is live with no pending: the newer handle returns the
        // stored token with zero refresh calls.
        val value = (state as SessionRecoveryState.Active).session.withAccessToken { it.token.value }
        assertEquals("access-1", value)
        assertEquals("no refresh for live access", 0, auth.refreshCalls)
    }

    @Test
    fun callerCauseDominatesInjectedFaultAtVaultRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fault: Throwable? = null
        val vault = F.vaultWith(record = F.record(), revision = 1L)
        vault.loadGate = {
            gate.await()
            fault?.let { throw it }
        }
        val coordinator = F.coordinator(
            vault = vault,
            authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L)),
            authApi = F.auth(),
        )
        val callerProbe = CancellationException("qa-caller-dominates")
        val injected = CancellationException("qa-injected-masked")
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                coordinator.reconcile()
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        job.cancel(callerProbe)
        fault = injected
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(callerProbe, outcome.await())
        assertTrue(job.isCompleted)
        assertTrue(coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    // ---- Strict legacy boundary through the QA-owned HTTP transport ----

    @Test
    fun strictLegacyGrantBodyIsMalformedOnRefresh() = runTest {
        val transport = FakeServiceHttpTransport()
        transport.queueJson(200, RefreshTestFixtures.legacyGrantBody())
        val httpAuth = HttpServiceAuthApi(F.origin(), transport)
        val attempt = ServiceRefreshAttempt.capture(
            idempotencyKey = F.KEY_A,
            refreshToken = OpaqueToken("refresh-old"),
        )
        val error = try {
            httpAuth.refresh(attempt)
            null
        } catch (e: SyncApiException.MalformedResponse) {
            e
        } catch (e: Throwable) {
            null
        }
        assertTrue("legacy grant tail must stay MalformedResponse", error != null)
        assertEquals(1, transport.capturedRequests.size)
    }

    @Test
    fun legacyMalformedReplayThroughRecoveryPreservesExactUncertainData() = runTest {
        val transport = FakeServiceHttpTransport()
        transport.queueJson(200, RefreshTestFixtures.legacyGrantBody())
        val httpAuth = HttpServiceAuthApi(F.origin(), transport)
        val seeded = F.record(
            session = F.expiredSession(refreshValue = "refresh-old"),
            pendingRefresh = F.pending(refreshValue = "refresh-old"),
        )
        val vault = F.vaultWith(record = seeded, revision = 1L)
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        val coordinator = SessionRecoveryCoordinator(
            origin = F.origin(),
            vault = vault,
            authority = authority,
            authApi = httpAuth,
        )
        val state = coordinator.reconcile()
        assertTrue(state is SessionRecoveryState.Active)
        val active = state as SessionRecoveryState.Active
        val error = try {
            active.session.withAccessToken { "unexpected" }
            null
        } catch (e: SyncApiException.MalformedResponse) {
            e
        } catch (e: Throwable) {
            null
        }
        assertTrue("legacy malformed must propagate closed", error != null)
        assertEquals(1, transport.capturedRequests.size)
        val current = vault.current().record
        assertEquals(F.OP_A, current?.pendingRefresh?.operationId)
        assertEquals(F.KEY_A, current?.pendingRefresh?.attempt?.idempotencyKey)
        assertEquals(OpaqueToken("refresh-old"), current?.pendingRefresh?.attempt?.refreshToken)
        assertEquals("refresh-old", current?.session?.refreshToken?.value)
        assertTrue(coordinator.state is SessionRecoveryState.Active)
    }
}
