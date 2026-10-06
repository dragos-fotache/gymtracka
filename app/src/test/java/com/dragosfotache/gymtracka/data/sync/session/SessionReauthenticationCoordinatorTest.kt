package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R09e independent QA: inert settled occupied-credential reauthentication.
 *
 * Derived ONLY from docs/archive/remaining-work/R09e-task.md normative API/ordering/
 * crash/recovery contracts plus QA-owned fixtures/doubles
 * (SessionReauthenticationTestFixtures, ScriptedReauthenticationAuthority,
 * FakeSessionVault, Refresh/SessionRecovery fixtures). No production
 * declarations, diffs, Worker work, generated sources or decompiled classes
 * inspected. Coordinator-supplied facts used as contracts (read shape, ctor
 * order, inert construction, bound-lease predicate shape, PushRunOwner
 * internal mutex). Literal independent oracles throughout; deterministic
 * gates only (deferred/barrier/fault queues, phase-observed hooks and
 * compareAndSet wrappers, no sleeps, no DAO mocks, no load/read-count seam
 * oracles).
 *
 * Each test proves a distinct new defect; representative refusal cases are
 * grouped without an exhaustive matrix.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionReauthenticationCoordinatorTest {
    private val F = SessionReauthenticationTestFixtures

    private data class Setup(
        val coordinator: SessionReauthenticationCoordinator,
        val authority: ScriptedReauthenticationAuthority,
        val vault: FakeSessionVault,
        val runOwner: PushRunOwner,
        val credentialMutex: Mutex,
    )

    private fun setup(
        authoritySnapshot: RecoveryRoomSnapshot = F.activeSnapshot(),
        vaultRecord: VaultSessionRecord? = F.activeRecord(),
        vaultRevision: Long = 5L,
        root: String = F.ORIGIN_ROOT,
        runOwner: PushRunOwner = PushRunOwner(),
        credentialMutex: Mutex = Mutex(),
    ): Setup {
        val authority = ScriptedReauthenticationAuthority(authoritySnapshot)
        val vault = F.vaultWith(record = vaultRecord, revision = vaultRevision)
        val coordinator = F.coordinator(
            origin = F.origin(root),
            authority = authority,
            vault = vault,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        return Setup(coordinator, authority, vault, runOwner, credentialMutex)
    }

    private fun freshCoordinator(s: Setup, root: String = F.ORIGIN_ROOT): SessionReauthenticationCoordinator =
        F.coordinator(
            origin = F.origin(root),
            authority = s.authority,
            vault = s.vault,
            runOwner = s.runOwner,
            credentialMutex = s.credentialMutex,
        )

    /**
     * Vault wrapper that commits through the delegate and then throws, proving
     * a lost-return seam: durable bytes stand, the invocation reports failure,
     * and no retry CAS/finalization follows from the failed call.
     */
    private class CommitThenThrowVault(
        private val delegate: FakeSessionVault,
        private val onCommitted: suspend (VaultSnapshot) -> Unit = {},
    ) : SessionVault {
        var wrapperCommits = 0
            private set
        var hookFired = false
            private set

        override suspend fun load(): VaultSnapshot = delegate.load()

        override suspend fun compareAndSet(
            expectedRevision: Long,
            record: VaultSessionRecord?,
        ): VaultSnapshot {
            val committed = delegate.compareAndSet(expectedRevision, record)
            wrapperCommits += 1
            hookFired = true
            onCommitted(committed)
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }

    /**
     * Vault wrapper that signals CAS entry BEFORE the delegate commit, then
     * holds: invalidation lands while the already-started CAS is admitted but
     * uncommitted. After release the delegate commit still lands (retained
     * bytes) while the fenced invocation reports STALE_OPERATION, never
     * success or finalization.
     */
    private class CommitHoldAtEntryVault(
        private val delegate: FakeSessionVault,
    ) : SessionVault {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun load(): VaultSnapshot = delegate.load()

        override suspend fun compareAndSet(
            expectedRevision: Long,
            record: VaultSessionRecord?,
        ): VaultSnapshot {
            entered.complete(Unit)
            release.await()
            return delegate.compareAndSet(expectedRevision, record)
        }
    }

    // ---- Construction + happy path ----

    @Test
    fun constructionPerformsNoIo() = runTest {
        val s = setup()
        assertEquals(0, s.authority.reads)
        assertEquals(0, s.vault.loads)
        assertEquals(0, s.vault.commits)
        assertEquals(0, s.authority.begins)
    }

    @Test
    fun happyReauthExactPlusOnePlusTwoPlusOnePreservesFullGrant() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L)
        val source = F.activeRecord(generation = 7L, operationId = F.OP_OLD, session = F.session())
        val s = setup(authoritySnapshot = expected, vaultRecord = source, vaultRevision = 5L)
        val grant = F.newGrant()

        val observation = s.coordinator.reauthenticate(expected, F.OP_NEW, grant)

        val preparing = F.preparingSnapshot(revision = 13L, generation = 8L)
        assertEquals(preparing, observation.room)
        assertEquals(6L, observation.vaultRevision)

        // Authority advanced exactly +2 revisions / +1 generation.
        assertEquals(preparing, s.authority.durableSnapshot())
        assertEquals(1, s.authority.begins)
        assertEquals(1, s.authority.finishes)

        // Vault holds exactly the full supplied grant, PREPARED, pending=null.
        val stored = s.vault.current()
        assertEquals(6L, stored.revision)
        val record = checkNotNull(stored.record)
        assertEquals(VaultSessionPhase.PREPARED, record.phase)
        assertEquals(F.binding(generation = 8L), record.binding)
        assertEquals(F.OP_NEW, record.operationId)
        assertEquals(grant, record.session)
        assertNull(record.pendingRefresh)

        // Old credential refused: same old expected no longer matches, and the
        // failed second attempt changes neither durable store.
        F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            s.coordinator.reauthenticate(expected, F.OP_OTHER, F.newGrant())
        }
        assertEquals(preparing, s.authority.durableSnapshot())
        assertEquals(6L, s.vault.current().revision)
        F.assertRedacted(
            observation.toString(),
            F.defaultSecrets("access-old", "access-new", "refresh-old", "refresh-new", F.ORIGIN_ROOT),
        )
    }

    @Test
    fun expiredSuppliedGrantPreservedWithoutFreshnessAdmission() = runTest {
        val expected = F.activeSnapshot()
        val s = setup(authoritySnapshot = expected, vaultRecord = F.activeRecord(), vaultRevision = 5L)
        val expired = F.expiredGrant()
        val observation = s.coordinator.reauthenticate(expected, F.OP_NEW, expired)
        assertEquals(expired, s.vault.current().record?.session)
        assertEquals(F.preparingSnapshot(), observation.room)
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionReauthenticationRecovery.Prepared)
        assertEquals(expired, s.vault.current().record?.session)
    }

    // ---- Source pending + drift (phase-observed hooks, never load counts) ----

    @Test
    fun sourceWhitespacePendingRefusedBeforeAnyReservationOrCas() = runTest {
        val pending = F.whitespacePending()
        val source = F.activeRecord(pendingRefresh = pending)
        val s = setup(vaultRecord = source, vaultRevision = 5L)
        val beforeRoom = s.authority.durableSnapshot()
        val beforeVault = s.vault.current()
        val e = F.assertReauthFailure(SessionReauthenticationFailure.PENDING_REFRESH) {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        F.assertClosed(e, F.defaultSecrets("refresh-old"))
        assertEquals(beforeRoom, s.authority.durableSnapshot())
        assertEquals(0, s.authority.begins)
        assertEquals(0, s.authority.finishes)
        assertEquals(beforeVault, s.vault.current())
        assertEquals(0, s.vault.commits)
        // Exact whitespace body preserved byte-for-byte.
        assertEquals(
            " { \"refreshToken\" : \"refresh-old\" } ",
            s.vault.current().record?.pendingRefresh?.attempt?.requestJson,
        )
    }

    @Test
    fun latePendingAfterReservationStopsPreservedWithNoRetryCas() = runTest {
        val s = setup()
        var hookFired = false
        s.vault.loadGate = {
            // Install only once the reservation is independently observable in
            // durable Room; never on a fixed load-call count (the
            // Room/vault/Room/vault/Room preflight width is not a seam oracle).
            if (!hookFired && s.authority.durableSnapshot().phase == RecoveryRoomPhase.TRANSITIONING) {
                hookFired = true
                val current = s.vault.current()
                val withPending = checkNotNull(current.record).copy(
                    pendingRefresh = F.pending(refreshValue = "refresh-old"),
                )
                s.vault.compareAndSet(current.revision, withPending)
            }
        }
        F.assertReauthFailure(SessionReauthenticationFailure.PENDING_REFRESH) {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        assertTrue("late-pending hook must have fired after reservation", hookFired)
        // Reservation stood (private); our supplied-grant CAS never ran: no
        // committed record carries the new operation, and finalization never ran.
        assertEquals(F.transitioningSnapshot(), s.authority.durableSnapshot())
        assertEquals(1, s.authority.begins)
        assertEquals(0, s.authority.finishes)
        assertTrue(s.vault.committedRecords.none { it?.operationId == F.OP_NEW })
        val stored = s.vault.current()
        assertEquals("refresh-old", stored.record?.pendingRefresh?.attempt?.refreshToken?.value)
        assertEquals(F.OP_OLD, stored.record?.pendingRefresh?.operationId)
        // A pending predecessor blocks: fresh reread classifies Blocked.
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionReauthenticationRecovery.Blocked)
        assertEquals(
            SessionReauthenticationFailure.PENDING_REFRESH,
            (recovery as SessionReauthenticationRecovery.Blocked).failure,
        )
    }

    @Test
    fun lateSourceRevisionDriftAfterReservationStopsPreserved() = runTest {
        val s = setup()
        var hookFired = false
        s.vault.loadGate = {
            if (!hookFired && s.authority.durableSnapshot().phase == RecoveryRoomPhase.TRANSITIONING) {
                hookFired = true
                val current = s.vault.current()
                val drifted = checkNotNull(current.record).copy(
                    session = F.session(refreshValue = "refresh-DRIFT"),
                )
                s.vault.compareAndSet(current.revision, drifted)
            }
        }
        F.assertReauthFailure(SessionReauthenticationFailure.VAULT_CONFLICT) {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        assertTrue("drift hook must have fired after reservation", hookFired)
        assertEquals(F.transitioningSnapshot(), s.authority.durableSnapshot())
        assertEquals(0, s.authority.finishes)
        assertTrue(s.vault.committedRecords.none { it?.operationId == F.OP_NEW })
        assertEquals("refresh-DRIFT", s.vault.current().record?.session?.refreshToken?.value)
    }

    @Test
    fun roomFencedDuringPostReservationLoadRefusesStaleBeforeCas() = runTest {
        val s = setup()
        var hookFired = false
        s.vault.loadGate = {
            // A Room fence lands while the post-reservation old-vault view
            // loads: the coordinator must freshly recheck Room before the
            // replacement CAS and refuse, never CASing the supplied grant.
            if (!hookFired && s.authority.durableSnapshot().phase == RecoveryRoomPhase.TRANSITIONING) {
                hookFired = true
                s.authority.seedDurable(F.preparingSnapshot())
            }
        }
        F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        assertTrue("fence hook must have fired after reservation", hookFired)
        assertEquals(1, s.authority.begins)
        assertEquals(0, s.authority.finishes)
        assertEquals(0, s.vault.commits)
        assertEquals(5L, s.vault.current().revision)
        assertTrue(s.vault.committedRecords.none { it?.operationId == F.OP_NEW })
    }

    @Test
    fun originOperationGenerationAccountMismatchesNeverAdopt() = runTest {
        // Wrong configured origin.
        val sOrigin = setup(root = F.OTHER_ORIGIN_ROOT)
        F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            sOrigin.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        assertEquals(0, sOrigin.authority.begins)
        assertEquals(0, sOrigin.vault.commits)

        // Same operation as old (not fresh).
        val sOp = setup()
        F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            sOp.coordinator.reauthenticate(F.activeSnapshot(), F.OP_OLD, F.newGrant())
        }
        assertEquals(0, sOp.authority.begins)

        // Supplied grant for a new service UUID (different account) never adopts.
        val sAcct = setup()
        F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            sAcct.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant(accountId = F.OTHER_ACCOUNT_ID))
        }
        assertEquals(0, sAcct.authority.begins)
        assertEquals(0, sAcct.vault.commits)

        // Foreign Room identity / generation mismatch.
        val sGen = setup(authoritySnapshot = F.activeSnapshot(generation = 9L))
        F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            sGen.coordinator.reauthenticate(F.activeSnapshot(generation = 7L), F.OP_NEW, F.newGrant())
        }

        // Non-ACTIVE or incomplete-baseline input never loads the vault.
        val sPhase = setup(authoritySnapshot = F.transitioningSnapshot())
        F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            sPhase.coordinator.reauthenticate(F.transitioningSnapshot(), F.OP_NEW, F.newGrant())
        }
        assertEquals(0, sPhase.vault.loads)
        val sBase = setup(authoritySnapshot = F.activeSnapshot(baselineComplete = false))
        F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            sBase.coordinator.reauthenticate(F.activeSnapshot(baselineComplete = false), F.OP_NEW, F.newGrant())
        }
        assertEquals(0, sBase.vault.loads)

        // Uppercase canonical UUID spelling refused as non-canonical.
        val sCanon = setup()
        F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            sCanon.coordinator.reauthenticate(F.activeSnapshot(), F.LETTER_OP.uppercase(), F.newGrant())
        }
    }

    // ---- Crash boundaries: reservation-only, lost CAS, lost finalization ----

    @Test
    fun reservationOnlyRecoverNeedsGrantThenResumeWithoutGenerationRestart() = runTest {
        val s = setup()
        s.authority.commitBeforeBeginError = true
        s.authority.pendingBeginError =
            RoomSessionAuthorityException(RoomSessionAuthorityFailure.UNAVAILABLE)
        try {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
            fail("expected AUTHORITY_UNAVAILABLE")
        } catch (e: SessionReauthenticationException) {
            assertEquals(SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE, e.failure)
        }
        assertEquals(F.transitioningSnapshot(), s.authority.durableSnapshot())
        assertEquals(0, s.vault.commits)

        s.authority.commitBeforeBeginError = false
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionReauthenticationRecovery.NeedsGrant)
        assertEquals(F.transitioningSnapshot(), (recovery as SessionReauthenticationRecovery.NeedsGrant).room)

        val grant = F.newGrant()
        val observation = freshCoordinator(s).resume(F.transitioningSnapshot(), grant)
        assertEquals(F.preparingSnapshot(), observation.room)
        assertEquals(6L, observation.vaultRevision)
        assertEquals(grant, s.vault.current().record?.session)
        // No generation restart: still generation 8, never a fresh reservation.
        assertEquals(8L, s.authority.durableSnapshot().sessionGeneration)
        assertEquals(1, s.authority.begins)
    }

    @Test
    fun casCommitLostReturnRecoverNeedsFinalizationThenResumeFinalizes() = runTest {
        val delegate = F.vaultWith(record = F.activeRecord(), revision = 5L)
        val authority = ScriptedReauthenticationAuthority(F.activeSnapshot())
        var hookFired = false
        val wrapping = CommitThenThrowVault(delegate) { hookFired = true }
        val coordinator = F.coordinator(authority = authority, vault = wrapping)
        val grant = F.newGrant()
        try {
            coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, grant)
            fail("expected VAULT_UNAVAILABLE")
        } catch (e: SessionReauthenticationException) {
            assertEquals(SessionReauthenticationFailure.VAULT_UNAVAILABLE, e.failure)
        }
        // Exactly one supplied-grant CAS stood through the delegate; the lost
        // return started no finalization and no retry CAS.
        assertTrue("commit hook must have fired", hookFired || wrapping.hookFired)
        assertEquals(1, wrapping.wrapperCommits)
        assertEquals(1, delegate.commits)
        assertEquals(listOf(6L), delegate.committedRevisions)
        assertEquals(F.transitioningSnapshot(), authority.durableSnapshot())
        assertEquals(0, authority.finishes)

        val fresh = F.coordinator(authority = authority, vault = delegate)
        val recovery = fresh.recover()
        assertTrue(recovery is SessionReauthenticationRecovery.NeedsFinalization)
        val needs = recovery as SessionReauthenticationRecovery.NeedsFinalization
        assertEquals(F.transitioningSnapshot(), needs.room)
        assertEquals(6L, needs.vaultRevision)

        val observation = fresh.resume(F.transitioningSnapshot(), grant)
        assertEquals(F.preparingSnapshot(), observation.room)
        assertEquals(6L, observation.vaultRevision)
        assertEquals(1, delegate.commits)
    }

    @Test
    fun finalizationCommitLostReturnRecoverPreparedWithoutNewEffects() = runTest {
        val s = setup()
        s.authority.commitBeforeFinishError = true
        s.authority.pendingFinishError =
            RoomSessionAuthorityException(RoomSessionAuthorityFailure.UNAVAILABLE)
        val grant = F.newGrant()
        // commitBeforeFinishError advances durable to PREPARING before throwing.
        try {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, grant)
            fail("expected AUTHORITY_UNAVAILABLE")
        } catch (e: SessionReauthenticationException) {
            assertEquals(SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE, e.failure)
        }
        assertEquals(F.preparingSnapshot(), s.authority.durableSnapshot())
        assertEquals(6L, s.vault.current().revision)

        s.authority.commitBeforeFinishError = false
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionReauthenticationRecovery.Prepared)
        val observation = (recovery as SessionReauthenticationRecovery.Prepared).observation
        assertEquals(F.preparingSnapshot(), observation.room)
        assertEquals(6L, observation.vaultRevision)
        // Resume is read-only idempotent now: no new CAS.
        val commitsBefore = s.vault.commits
        val again = freshCoordinator(s).resume(F.preparingSnapshot(), grant)
        assertEquals(observation, again)
        assertEquals(commitsBefore, s.vault.commits)
    }

    @Test
    fun installedTargetWrongGrantRefusesAndPendingSemanticsPreserved() = runTest {
        val body = " { \"refreshToken\" : \"refresh-new\" } "
        val targetPending = VaultPendingRefresh(
            operationId = F.OP_NEW,
            attempt = ServiceRefreshAttempt.fromJson(F.KEY_B, body),
        )
        val grant = F.newGrant()
        val target = F.preparedTargetRecord(session = grant, pendingRefresh = targetPending)
        val s = setup(
            authoritySnapshot = F.transitioningSnapshot(),
            vaultRecord = target,
            vaultRevision = 6L,
        )
        // Wrong supplied grant against the installed target refuses, never replaces.
        F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            s.coordinator.resume(F.transitioningSnapshot(), F.session(refreshValue = "refresh-WRONG"))
        }
        assertEquals(target, s.vault.current().record)
        assertEquals(0, s.vault.commits)

        // Correct grant finalizes read-only, preserving target pending bytes exactly.
        val observation = s.coordinator.resume(F.transitioningSnapshot(), grant)
        assertEquals(F.preparingSnapshot(), observation.room)
        assertEquals(6L, observation.vaultRevision)
        assertEquals(0, s.vault.commits)
        val stored = checkNotNull(s.vault.current().record)
        assertEquals(body, stored.pendingRefresh?.attempt?.requestJson)
        assertEquals(F.KEY_B, stored.pendingRefresh?.attempt?.idempotencyKey)
        F.assertSemanticPendingEqual(targetPending, stored.pendingRefresh)
    }

    @Test
    fun realVaultSemanticPendingDecodedFreshOnEveryRead() = runTest {
        val storage = InMemoryVaultFileStorage()
        val vault = realVault(storage)
        val body = " { \"refreshToken\" : \"refresh-new\" } "
        val attempt = ServiceRefreshAttempt.fromJson(F.KEY_B, body)
        val grant = F.newGrant()
        val target = F.preparedTargetRecord(session = grant, pendingRefresh = VaultPendingRefresh(F.OP_NEW, attempt))
        assertEquals(1L, vault.compareAndSet(0L, target).revision)

        val authority = ScriptedReauthenticationAuthority(F.preparingSnapshot(revision = 13L, generation = 8L))
        val coordinator = F.coordinator(authority = authority, vault = vault)
        repeat(2) {
            val recovery = coordinator.recover()
            assertTrue("iteration $it", recovery is SessionReauthenticationRecovery.Prepared)
        }
        val back = checkNotNull(vault.load().record)
        assertEquals(body, back.pendingRefresh?.attempt?.requestJson)
        assertTrue(checkNotNull(back.pendingRefresh).attempt !== attempt)
    }

    @Test
    fun preReservationVaultExhaustionRefusesBeforeReservation() = runTest {
        // Occupied matching ACTIVE source at vault revision MAX: the
        // pre-reservation revision check must refuse COUNTER_OVERFLOW with
        // zero Room begins and zero CAS. (Injected CAS REVISION_EXHAUSTED
        // elsewhere only proves the late failure.)
        val s = setup(vaultRecord = F.activeRecord(), vaultRevision = Long.MAX_VALUE)
        F.assertReauthFailure(SessionReauthenticationFailure.COUNTER_OVERFLOW) {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        assertEquals(0, s.authority.begins)
        assertEquals(0, s.authority.finishes)
        assertEquals(0, s.vault.commits)
        assertEquals(F.activeSnapshot(), s.authority.durableSnapshot())
        assertEquals(Long.MAX_VALUE, s.vault.current().revision)
    }

    @Test
    fun installedTargetAtRoomRevisionMaxRefusesWithoutFinishOrCas() = runTest {
        // Installed PREPARED target with TRANSITIONING Room at revision MAX:
        // finalization has no revision space, so resume refuses
        // COUNTER_OVERFLOW with zero finish and zero CAS.
        val maxTransitioning = F.transitioningSnapshot(revision = Long.MAX_VALUE, generation = 8L)
        val grant = F.newGrant()
        val target = F.preparedTargetRecord(session = grant)
        val s = setup(authoritySnapshot = maxTransitioning, vaultRecord = target, vaultRevision = 6L)
        F.assertReauthFailure(SessionReauthenticationFailure.COUNTER_OVERFLOW) {
            s.coordinator.resume(maxTransitioning, grant)
        }
        assertEquals(0, s.authority.finishes)
        assertEquals(0, s.vault.commits)
        assertEquals(maxTransitioning, s.authority.durableSnapshot())
        assertEquals(target, s.vault.current().record)
    }

    // ---- Shared ownership: queued, newest, invalidation, cancellation ----

    @Test
    fun runOwnerQueuedInvocationHasNoEffectsThenReleaseReadmits() = runTest {
        val s = setup()
        s.runOwner.mutex.lock()
        try {
            supervisorScope {
                // Separate completion signal: the Throwable is CAPTURED in the
                // actual child's catch and asserted via the signal. The canceled
                // job itself is only joined, never awaited (await would rethrow).
                val done = CompletableDeferred<Throwable>()
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
                        done.complete(AssertionError("expected cancellation while queued"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                runCurrent()
                runCurrent()
                assertFalse("queued invocation must not complete while owner held", job.isCompleted)
                assertEquals(0, s.authority.reads)
                assertEquals(0, s.authority.begins)
                assertEquals(0, s.vault.loads)
                assertEquals(0, s.vault.commits)
                // Non-copying probe: exact identity survives stack recovery.
                val probe = SessionReauthenticationTestFixtures.ProbeCancellationException("queued-run-owner-cancel-r09e")
                job.cancel(probe)
                assertSame(probe, done.await())
                job.join()
                assertEquals(0, s.authority.begins)
                assertEquals(0, s.vault.commits)
                assertEquals(F.activeSnapshot(), s.authority.durableSnapshot())
            }
        } finally {
            s.runOwner.mutex.unlock()
        }
        // Release readmits: the same pair now succeeds.
        val observation = s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        assertEquals(F.preparingSnapshot(), observation.room)
        assertEquals(6L, observation.vaultRevision)
    }

    @Test
    fun credentialMutexQueuedInvocationHasNoEffectsThenReleaseReadmits() = runTest {
        val s = setup()
        s.credentialMutex.lock()
        try {
            supervisorScope {
                // Separate completion signal: the Throwable is CAPTURED in the
                // actual child's catch and asserted via the signal. The canceled
                // job itself is only joined, never awaited (await would rethrow).
                val done = CompletableDeferred<Throwable>()
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
                        done.complete(AssertionError("expected cancellation while queued"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                runCurrent()
                runCurrent()
                assertFalse("queued invocation must not complete while credential mutex held", job.isCompleted)
                assertEquals(0, s.authority.begins)
                assertEquals(0, s.vault.commits)
                // Non-copying probe: exact identity survives stack recovery.
                val probe = SessionReauthenticationTestFixtures.ProbeCancellationException("queued-credential-cancel-r09e")
                job.cancel(probe)
                assertSame(probe, done.await())
                job.join()
                assertEquals(0, s.authority.begins)
                assertEquals(0, s.vault.commits)
                assertEquals(F.activeSnapshot(), s.authority.durableSnapshot())
            }
        } finally {
            s.credentialMutex.unlock()
        }
        val observation = s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        assertEquals(F.preparingSnapshot(), observation.room)
        assertEquals(6L, observation.vaultRevision)
    }

    @Test
    fun invalidateRetiresInflightPreReserveWithoutIoOrCleanup() = runTest {
        val s = setup()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        s.authority.readGate = {
            entered.complete(Unit)
            release.await()
        }
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
                    fail("expected STALE_OPERATION")
                } catch (e: SessionReauthenticationException) {
                    e
                }
            }
            entered.await()
            s.coordinator.invalidate()
            release.complete(Unit)
            val outcome = job.await()
            assertEquals(
                SessionReauthenticationFailure.STALE_OPERATION,
                (outcome as SessionReauthenticationException).failure,
            )
        }
        assertEquals(0, s.authority.begins)
        assertEquals(0, s.vault.commits)
        assertEquals(F.activeSnapshot(), s.authority.durableSnapshot())
        assertEquals(5L, s.vault.current().revision)
    }

    @Test
    fun newestInvocationRetiresOlderWithSingleReservationAndCommit() = runTest {
        val s = setup()
        val releaseFirst = CompletableDeferred<Unit>()
        s.authority.readGate = { callIndex ->
            if (callIndex == 1) releaseFirst.await()
        }
        val grant: ServiceSession = F.newGrant()
        supervisorScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, grant)
                    fail("expected older to retire")
                } catch (e: SessionReauthenticationException) {
                    e
                }
            }
            // UNDISPATCHED runs first to its read seam before second opens its
            // newer fence, so retirement is deterministic.
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_OTHER, grant)
            }
            releaseFirst.complete(Unit)
            val firstOutcome = first.await()
            assertEquals(
                SessionReauthenticationFailure.STALE_OPERATION,
                (firstOutcome as SessionReauthenticationException).failure,
            )
            val observation = second.await()
            assertEquals(F.OP_OTHER, observation.room.identity?.operationId)
        }
        assertEquals(1, s.authority.begins)
        assertEquals(listOf(6L), s.vault.committedRevisions)
        assertEquals(F.OP_OTHER, s.vault.current().record?.operationId)
    }

    @Test
    fun lateInvalidationAfterAdmittedCasPreservesBytesWithoutSuccess() = runTest {
        val delegate = F.vaultWith(record = F.activeRecord(), revision = 5L)
        val authority = ScriptedReauthenticationAuthority(F.activeSnapshot())
        val wrapping = CommitHoldAtEntryVault(delegate)
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val coordinator = F.coordinator(
            authority = authority,
            vault = wrapping,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        val grant = F.newGrant()
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, grant)
                    fail("expected STALE_OPERATION")
                } catch (e: SessionReauthenticationException) {
                    e
                }
            }
            // The already-started CAS is admitted but uncommitted; fencing
            // lands before the delegate commit.
            wrapping.entered.await()
            assertEquals(5L, delegate.current().revision)
            coordinator.invalidate()
            wrapping.release.complete(Unit)
            val outcome = job.await()
            assertEquals(
                SessionReauthenticationFailure.STALE_OPERATION,
                (outcome as SessionReauthenticationException).failure,
            )
        }
        // The CAS still committed after invalidation: bytes retained without
        // success and without finalization; fresh reread is the only path.
        assertEquals(6L, delegate.current().revision)
        assertEquals(grant, delegate.current().record?.session)
        assertEquals(0, authority.finishes)
        val recovery = F.coordinator(authority = authority, vault = delegate).recover()
        assertTrue(recovery is SessionReauthenticationRecovery.NeedsFinalization)
    }

    @Test
    fun callerCancellationEscapesIdenticallyWithoutVaultWrite() = runTest {
        val s = setup()
        val injected = CancellationException("pre-cas-cancel-r09e")
        s.authority.beginGate = { throw injected }
        try {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
            fail("expected cancellation identity")
        } catch (e: CancellationException) {
            assertSame(injected, e)
        }
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun alreadyCanceledCallerPreemptsInvalidInputWithoutRetiringEarlier() = runTest {
        val s = setup()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstParked = CompletableDeferred<Unit>()
        s.authority.readGate = { callIndex ->
            if (callIndex == 1) {
                firstParked.complete(Unit)
                releaseFirst.await()
            }
        }
        val grant: ServiceSession = F.newGrant()
        supervisorScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, grant)
            }
            firstParked.await()
            // An already-canceled caller with structurally invalid input: the
            // child actually ENTERS undispatched, cancels its own job before
            // the API call, captures the Throwable in its own catch, and
            // asserts identity via a separate signal (the canceled child is
            // never awaited). The secondApiCalled gate proves the API was attempted; cancellation
            // takes precedence over structural validation, and the dead call
            // must not retire the valid earlier invocation.
            val probe = CancellationException("already-canceled-r09e")
            val secondApiCalled = CompletableDeferred<Unit>()
            val secondOutcome = CompletableDeferred<Throwable>()
            async(start = CoroutineStart.UNDISPATCHED) {
                coroutineContext[Job]!!.cancel(probe)
                try {
                    secondApiCalled.complete(Unit)
                    s.coordinator.reauthenticate(F.activeSnapshot(), "not-a-uuid", grant)
                    secondOutcome.complete(AssertionError("expected cancellation"))
                } catch (e: Throwable) {
                    secondOutcome.complete(e)
                }
            }
            assertTrue(
                "second child must have entered and attempted the API call",
                secondApiCalled.isCompleted,
            )
            assertSame(probe, secondOutcome.await())
            releaseFirst.complete(Unit)
            val observation = first.await()
            assertEquals(F.OP_NEW, observation.room.identity?.operationId)
        }
        assertEquals(1, s.authority.begins)
        assertEquals(listOf(6L), s.vault.committedRevisions)
    }

    // ---- Bound lease + old generation ----

    @Test
    fun oldBoundLeaseFailsStaleAfterDurableReservation() = runTest {
        val expected = F.activeSnapshot()
        val s = setup(authoritySnapshot = expected)
        val lease = SessionRefreshLease(
            binding = checkNotNull(expected.identity).binding,
            operationId = checkNotNull(expected.identity).operationId,
            phase = VaultSessionPhase.ACTIVE,
            authorizationCheck = {
                if (s.authority.durableSnapshot() != expected) {
                    throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
                }
            },
        )
        // Bound to the captured ACTIVE snapshot: passes before reservation.
        lease.checkAuthorization()
        // Durable reservation advances generation: the same predicate now fails.
        s.coordinator.reauthenticate(expected, F.OP_NEW, F.newGrant())
        try {
            lease.checkAuthorization()
            fail("old bound lease must be stale")
        } catch (e: SessionRefreshException) {
            assertEquals(SessionRefreshFailure.STALE_SESSION, e.failure)
        }
        // Old expected snapshot itself is stale for a second attempt.
        F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            s.coordinator.reauthenticate(expected, F.OP_OTHER, F.newGrant())
        }
    }

    // ---- Recovery reads-only, drift, closed mapping ----

    @Test
    fun recoverNonReauthenticatingNeverProbesVault() = runTest {
        for (snapshot in listOf(
            RecoveryRoomSnapshot(
                revision = 0L, sessionGeneration = 0L, cachedAccountId = null,
                phase = RecoveryRoomPhase.SIGNED_OUT, identity = null, baselineComplete = false,
            ),
            F.activeSnapshot(),
        )) {
            val authority = ScriptedReauthenticationAuthority(snapshot)
            val vault = F.vaultWith(record = F.preparedTargetRecord(), revision = 9L)
            vault.loadFailures += SessionVaultException(VaultFailure.UNAVAILABLE)
            val recovery = F.coordinator(authority = authority, vault = vault).recover()
            assertTrue(recovery is SessionReauthenticationRecovery.NotReauthenticating)
            assertEquals(0, vault.loads)
            assertEquals(0, vault.commits)
        }
    }

    @Test
    fun recoverOtherOriginBlocksWithoutVaultRead() = runTest {
        val authority = ScriptedReauthenticationAuthority(F.transitioningSnapshot(root = F.OTHER_ORIGIN_ROOT))
        val vault = F.vaultWith(record = null, revision = 0L)
        vault.loadFailures += SessionVaultException(VaultFailure.UNAVAILABLE)
        val recovery = F.coordinator(authority = authority, vault = vault).recover()
        assertTrue(recovery is SessionReauthenticationRecovery.Blocked)
        assertEquals(
            SessionReauthenticationFailure.IDENTITY_MISMATCH,
            (recovery as SessionReauthenticationRecovery.Blocked).failure,
        )
        assertEquals(0, vault.loads)
    }

    @Test
    fun inconsistentBaselineCompleteFailsClosedAsBlocked() = runTest {
        // TRANSITIONING with baselineComplete=true is an impossible durable
        // tuple: recovery must classify Blocked closed, never escape unrelated
        // model validation, and perform no writes.
        val inconsistent = F.transitioningSnapshot().copy(baselineComplete = true)
        val authority = ScriptedReauthenticationAuthority(inconsistent)
        val vault = F.vaultWith(record = F.preparedTargetRecord(), revision = 6L)
        val recovery = F.coordinator(authority = authority, vault = vault).recover()
        assertTrue(recovery is SessionReauthenticationRecovery.Blocked)
        val failure = (recovery as SessionReauthenticationRecovery.Blocked).failure
        assertEquals(SessionReauthenticationFailure.AUTHORITY_CORRUPT, failure)
        F.assertRedacted(failure.toString(), F.defaultSecrets())
        assertEquals(0, vault.commits)
        assertEquals(0, authority.begins)
        assertEquals(0, authority.finishes)
        // Structural input with a non-ACTIVE phase is INVALID_INPUT closed,
        // regardless of the baseline flag.
        val s = setup()
        F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            s.coordinator.reauthenticate(inconsistent, F.OP_NEW, F.newGrant())
        }
        assertEquals(0, s.authority.begins)
        // Structural resume with baselineComplete=true is likewise INVALID_INPUT.
        F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            s.coordinator.resume(F.preparingSnapshot().copy(baselineComplete = true), F.newGrant())
        }
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun alternatingRereadDriftDetectedBeforeFinalization() = runTest {
        val s = setup()
        val grant = F.newGrant()
        var hookFired = false
        s.authority.readGate = {
            // Once the reservation is durable, a later alternating reread
            // observes drift; never on a fixed read-call count.
            if (!hookFired && s.authority.durableSnapshot().phase == RecoveryRoomPhase.TRANSITIONING) {
                hookFired = true
                s.authority.readOverrides += F.preparingSnapshot()
            }
        }
        F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, grant)
        }
        assertTrue("reread-drift hook must have fired after reservation", hookFired)
        assertEquals(0, s.authority.finishes)
    }

    @Test
    fun closedErrorMappingAndDisplaysStayRedacted() = runTest {
        val s = setup(authoritySnapshot = F.activeSnapshot())
        s.authority.beginFaults += RoomSessionAuthorityException(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY)
        val e = F.assertReauthFailure(SessionReauthenticationFailure.AUTHORITY_CORRUPT) {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        F.assertClosed(e, F.defaultSecrets("access-new", "refresh-new"))
        F.assertRedacted(e.toString(), F.defaultSecrets("access-new", "refresh-new"))

        s.authority.beginFaults += RoomSessionAuthorityException(RoomSessionAuthorityFailure.UNAVAILABLE)
        F.assertReauthFailure(SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE) {
            s.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        // Counter exhaustion before avoidable effects.
        val maxed = setup(authoritySnapshot = F.activeSnapshot(revision = Long.MAX_VALUE - 1))
        F.assertReauthFailure(SessionReauthenticationFailure.COUNTER_OVERFLOW) {
            maxed.coordinator.reauthenticate(F.activeSnapshot(revision = Long.MAX_VALUE - 1), F.OP_NEW, F.newGrant())
        }
        assertEquals(0, maxed.vault.commits)
        val vMax = setup()
        vMax.vault.casFailures += SessionVaultException(VaultFailure.REVISION_EXHAUSTED)
        F.assertReauthFailure(SessionReauthenticationFailure.COUNTER_OVERFLOW) {
            vMax.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.newGrant())
        }
        // Pending predecessor maps closed even when expired (grant expiry never read).
        val expiredPendingSource = F.activeRecord(
            session = F.expiredGrant(),
            pendingRefresh = F.pending(),
        )
        val sPending = setup(vaultRecord = expiredPendingSource)
        F.assertReauthFailure(SessionReauthenticationFailure.PENDING_REFRESH) {
            sPending.coordinator.reauthenticate(F.activeSnapshot(), F.OP_NEW, F.expiredGrant())
        }
    }

    @Test
    fun preparingObservationContractUnchangedFromExistingValidation() = runTest {
        // Existing PreparedSessionObservation validation is unchanged: invalid
        // rooms throw SessionPreparationException(INVALID_INPUT), never the new
        // reauthentication exception. Representative rooms only (existing
        // coverage owns the full primitive matrix).
        for (bad in listOf(F.activeSnapshot(), F.transitioningSnapshot())) {
            try {
                PreparedSessionObservation(room = bad, vaultRevision = 6L)
                fail("expected SessionPreparationException for $bad")
            } catch (e: SessionPreparationException) {
                assertEquals(SessionPreparationFailure.INVALID_INPUT, e.failure)
                assertNull(e.cause)
                assertTrue(e.suppressed.isEmpty())
            }
        }
        // The valid PREPARING observation used by this slice stays advisory and
        // redacted; recovery Blocked displays stay fixed.
        val observation = PreparedSessionObservation(room = F.preparingSnapshot(), vaultRevision = 6L)
        F.assertRedacted(observation.toString(), F.defaultSecrets("access-new", "refresh-new"))
        val blocked = SessionReauthenticationRecovery.Blocked(SessionReauthenticationFailure.PENDING_REFRESH)
        assertTrue(blocked.toString().isNotEmpty())
        F.assertRedacted(blocked.toString(), F.defaultSecrets("access-new", "refresh-new"))
    }
}
