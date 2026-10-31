package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runtime authority fencing for recovered handles.
 *
 * Covers acceptance cases 11-14 (stale handles, logout races, shared
 * rotation/retry, safe-failure preservation). Startup precedence lives in
 * SessionRecoveryCoordinatorTest.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionRecoveryAuthorizationTest {
    private val F = SessionRecoveryTestFixtures

    private data class ActiveSetup(
        val coordinator: SessionRecoveryCoordinator,
        val vault: FakeSessionVault,
        val authority: FakeRecoveryAuthority,
        val auth: FakeServiceAuthApi,
        val session: RecoveredSession,
        val record: VaultSessionRecord,
    )

    private suspend fun activeSetup(
        pending: VaultPendingRefresh? = F.pending(refreshValue = "refresh-old"),
        accessValue: String = "access-old",
    ): ActiveSetup {
        val record = F.record(
            session = F.expiredSession(accessValue = accessValue, refreshValue = "refresh-old"),
            pendingRefresh = pending,
        )
        val vault = F.vaultWith(record = record, revision = 1L)
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        val auth = F.auth()
        val coordinator = F.coordinator(vault = vault, authority = authority, authApi = auth)
        val state = coordinator.reconcile()
        if (state !is SessionRecoveryState.Active) {
            throw AssertionError("setup must reconcile Active but was $state")
        }
        return ActiveSetup(coordinator, vault, authority, auth, state.session, record)
    }

    private suspend fun assertRefreshStale(block: suspend () -> Unit): SessionRefreshException {
        val failure = try {
            block()
            throw AssertionError("Expected SessionRefreshException(STALE_SESSION)")
        } catch (e: SessionRefreshException) {
            e
        }
        assertEquals(SessionRefreshFailure.STALE_SESSION, failure.failure)
        return failure
    }

    private suspend fun assertRecoveryStale(block: suspend () -> Unit): SessionRecoveryException {
        val failure = try {
            block()
            throw AssertionError("Expected SessionRecoveryException(STALE_SESSION)")
        } catch (e: SessionRecoveryException) {
            e
        }
        assertEquals(SessionRecoveryFailure.STALE_SESSION, failure.failure)
        return failure
    }

    // ---- Case 11: stale handles refuse before I/O ----

    @Test
    fun oldHandleAfterNewerReconcileRefusesBeforeIo() = runTest {
        val s = activeSetup()
        val old = s.session
        val newer = s.coordinator.reconcile()
        assertTrue(newer is SessionRecoveryState.Active)
        val loadsBefore = s.vault.loads
        val callsBefore = s.auth.refreshCalls
        val stale = assertRecoveryStale {
            old.withAccessToken { "unexpected" }
        }
        F.assertRedacted(stale.message ?: "", listOf(F.ACCOUNT_ID, F.OP_A))
        assertEquals(callsBefore, s.auth.refreshCalls)
        assertEquals(loadsBefore, s.vault.loads)
    }

    @Test
    fun oldHandleAfterInvalidateRefusesBeforeIo() = runTest {
        val s = activeSetup()
        val loadsBefore = s.vault.loads
        val old = s.session
        s.coordinator.invalidate()
        assertTrue(s.coordinator.state is SessionRecoveryState.PrivacyLocked)
        assertRecoveryStale {
            old.withAccessToken { "unexpected" }
        }
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(loadsBefore, s.vault.loads)
    }

    @Test
    fun authorityChangeBeforeRequestFencesWithoutNetwork() = runTest {
        val s = activeSetup()
        s.authority.stable(F.activeCompleteSnapshot(revision = 10L))
        assertRefreshStale {
            s.session.withAccessToken { "unexpected" }
        }
        assertEquals(0, s.auth.refreshCalls)
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.IDENTITY_MISMATCH,
            (state as SessionRecoveryState.Locked).reason,
        )
    }

    @Test
    fun authorityChangeDuringRefreshNeverReturnsStaleResult() = runTest {
        val s = activeSetup()
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        s.auth.refreshGate = {
            s.authority.stable(F.activeCompleteSnapshot(revision = 10L))
        }
        s.auth.answer(rotated)
        assertRefreshStale {
            s.session.withAccessToken { it.token.value }
        }
        assertEquals("refresh-old", s.vault.current().record?.session?.refreshToken?.value)
    }

    @Test
    fun authorityChangeBeforeCommitNeverInstallsGrant() = runTest {
        val s = activeSetup()
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        s.auth.refreshGate = {
            s.authority.stable(
                F.activeCompleteSnapshot(binding = F.binding(accountId = F.OTHER_ACCOUNT_ID)),
            )
        }
        s.auth.answer(rotated)
        assertRefreshStale {
            s.session.withAccessToken { it.token.value }
        }
        val current = s.vault.current().record
        assertEquals(F.OP_A, current?.operationId)
        assertEquals("refresh-old", current?.session?.refreshToken?.value)
        assertEquals(F.ACCOUNT_ID, current?.binding?.accountId)
    }

    @Test
    fun authorityChangeDuringBlockedCasFencesResultButMayLeaveDurableGrant() = runTest {
        val s = activeSetup()
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        s.auth.answer(rotated)
        s.vault.blockCasCalls = 1
        val finished = async {
            try {
                s.session.withAccessToken { it.token.value }
                null
            } catch (e: Throwable) {
                e
            }
        }
        runCurrent()
        runCurrent()
        assertEquals("must be parked in CAS", 0, s.vault.blockCasCalls)
        s.authority.stable(F.activeCompleteSnapshot(revision = 10L))
        s.vault.releaseCas.complete(Unit)
        advanceUntilIdle()
        val error = finished.await()
        assertTrue("blocked CAS must be fenced, was $error", error is SessionRefreshException)
        assertEquals(SessionRefreshFailure.STALE_SESSION, (error as SessionRefreshException).failure)
        // The already-started CAS may commit: a durable rotated grant is
        // legitimate and must not be rolled back by this assertion.
        assertEquals("refresh-new", s.vault.current().record?.session?.refreshToken?.value)
        // ...but there is no visibility: the state stays locked and the
        // handle is permanently dead.
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.IDENTITY_MISMATCH,
            (state as SessionRecoveryState.Locked).reason,
        )
        val loadsBefore = s.vault.loads
        val callsBefore = s.auth.refreshCalls
        val dead = try {
            s.session.withAccessToken { "unexpected" }
            null
        } catch (e: SessionRecoveryException) {
            e
        }
        assertTrue("handle must stay dead", dead != null)
        assertEquals(SessionRecoveryFailure.STALE_SESSION, dead?.failure)
        assertEquals(loadsBefore, s.vault.loads)
        assertEquals(callsBefore, s.auth.refreshCalls)
    }

    @Test
    fun errorUnderDeadHandleSurfacesStaleNotSeamError() = runTest {
        val s = activeSetup()
        val gate = CompletableDeferred<Unit>()
        s.auth.refreshGate = {
            s.authority.stable(F.activeCompleteSnapshot(revision = 10L))
            gate.await()
        }
        s.auth.failWith(SyncApiException.Transient())
        val finished = async {
            try {
                s.session.withAccessToken { "unexpected" }
                null
            } catch (e: Throwable) {
                e
            }
        }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        val error = finished.await()
        assertTrue("stale fence must dominate seam error, was $error", error is SessionRefreshException)
        assertEquals(SessionRefreshFailure.STALE_SESSION, (error as SessionRefreshException).failure)
    }

    @Test
    fun deadHandleStaysDeadEvenIfAuthorityReappears() = runTest {
        val s = activeSetup()
        val original = F.activeCompleteSnapshot(revision = 9L)
        s.authority.stable(F.activeCompleteSnapshot(revision = 10L))
        assertRefreshStale {
            s.session.withAccessToken { "unexpected" }
        }
        s.authority.stable(original)
        val loadsBefore = s.vault.loads
        assertRecoveryStale {
            s.session.withAccessToken { "unexpected" }
        }
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(loadsBefore, s.vault.loads)
    }

    @Test
    fun wrongOriginGenerationAccountNeverReachNetwork() = runTest {
        val variants = listOf(
            "origin" to F.activeCompleteSnapshot(binding = F.binding(root = F.OTHER_ORIGIN_ROOT)),
            "generation" to F.activeCompleteSnapshot(binding = F.binding(generation = 2L)),
            "account" to F.activeCompleteSnapshot(binding = F.binding(accountId = F.OTHER_ACCOUNT_ID)),
        )
        for ((name, changed) in variants) {
            val s = activeSetup()
            s.authority.stable(changed)
            assertRefreshStale { s.session.withAccessToken { "unexpected" } }
            assertEquals("$name network", 0, s.auth.refreshCalls)
            assertEquals("$name vault writes", 0, s.vault.commits)
        }
    }

    // ---- Case 12: logout-boundary races ----

    @Test
    fun invalidateMidRefreshThenTombstoneCannotReinstall() = runTest {
        val s = activeSetup()
        val gate = CompletableDeferred<Unit>()
        s.auth.refreshGate = { gate.await() }
        s.auth.answer(F.session(accessValue = "access-late", refreshValue = "refresh-late"))
        val finished = async {
            try {
                s.session.withAccessToken { it.token.value }
                null
            } catch (e: Throwable) {
                e
            }
        }
        runCurrent()
        s.coordinator.invalidate()
        assertTrue(s.coordinator.state is SessionRecoveryState.PrivacyLocked)
        val tombstone = s.vault.compareAndSet(1L, null)
        assertTrue(tombstone.record == null)
        gate.complete(Unit)
        advanceUntilIdle()
        val error = finished.await()
        assertTrue("late result must not be returned, was $error", error is SessionRefreshException)
        assertEquals(SessionRefreshFailure.STALE_SESSION, (error as SessionRefreshException).failure)
        assertEquals(tombstone, s.vault.current())
        assertTrue(s.coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    @Test
    fun invalidateMidRefreshThenReplacementCannotBeOverwritten() = runTest {
        val s = activeSetup()
        val gate = CompletableDeferred<Unit>()
        s.auth.refreshGate = { gate.await() }
        s.auth.answer(F.session(accessValue = "access-late", refreshValue = "refresh-late"))
        val finished = async {
            try {
                s.session.withAccessToken { it.token.value }
                null
            } catch (e: Throwable) {
                e
            }
        }
        runCurrent()
        s.coordinator.invalidate()
        val replacement = F.record(
            binding = F.binding(accountId = F.OTHER_ACCOUNT_ID),
            operationId = F.OP_B,
            session = F.session(accountId = F.OTHER_ACCOUNT_ID, accessValue = "access-b", refreshValue = "refresh-b"),
        )
        val published = s.vault.compareAndSet(1L, replacement)
        gate.complete(Unit)
        advanceUntilIdle()
        val error = finished.await()
        assertTrue("late A result must not be returned, was $error", error is SessionRefreshException)
        assertEquals(SessionRefreshFailure.STALE_SESSION, (error as SessionRefreshException).failure)
        val current = s.vault.current()
        assertEquals(published, current)
        assertEquals(F.OP_B, current.record?.operationId)
        assertEquals(F.OTHER_ACCOUNT_ID, current.record?.binding?.accountId)
        assertEquals(F.OTHER_ACCOUNT_ID, current.record?.session?.account?.accountId)
        assertEquals("refresh-b", current.record?.session?.refreshToken?.value)
        assertTrue(s.coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    @Test
    fun committedGrantSurvivesInvalidateButLosesVisibility() = runTest {
        val s = activeSetup()
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        s.auth.answer(rotated)
        val returned = s.session.withAccessToken { it.token.value }
        assertEquals("access-new", returned)
        assertEquals("refresh-new", s.vault.current().record?.session?.refreshToken?.value)
        s.coordinator.invalidate()
        assertTrue(s.coordinator.state is SessionRecoveryState.PrivacyLocked)
        assertRecoveryStale {
            s.session.withAccessToken { "unexpected" }
        }
        assertEquals("refresh-new", s.vault.current().record?.session?.refreshToken?.value)
    }

    @Test
    fun callerCancellationDuringRefreshDominatesAndFences() = runTest {
        val s = activeSetup()
        val gate = CompletableDeferred<Unit>()
        s.auth.refreshGate = { gate.await() }
        s.auth.answer(F.session(accessValue = "access-new", refreshValue = "refresh-new"))
        val probe = CancellationException("test-caller-refresh")
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.session.withAccessToken { it.token.value }
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        job.cancel(probe)
        gate.complete(Unit)
        advanceUntilIdle()
        val caught = outcome.await()
        assertTrue("caller cancellation must dominate, was $caught", caught === probe)
    }

    // ---- Case 13: shared rotation and single retry ----

    @Test
    fun concurrentRequestsShareOneRotation() = runTest {
        val s = activeSetup()
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        s.auth.answer(rotated)
        val results = (1..3).map {
            async { s.session.withAccessToken { token -> token.token.value } }
        }.map { it.await() }
        assertTrue(results.all { it == "access-new" })
        assertEquals("one shared rotation", 1, s.auth.refreshCalls)
        assertEquals(F.KEY_A, s.auth.seenAttempts.single().idempotencyKey)
        assertEquals("refresh-new", s.vault.current().record?.session?.refreshToken?.value)
    }

    @Test
    fun authorityInvalidationBeforeAccessRetryBlocksIt() = runTest {
        val s = activeSetup()
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        s.auth.answer(rotated)
        var invocations = 0
        val error = try {
            s.session.withAccessToken { _ ->
                invocations += 1
                if (invocations == 1) {
                    s.authority.stable(F.activeCompleteSnapshot(revision = 10L))
                    throw SyncApiException.AuthenticationRequired()
                }
                "unexpected"
            }
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("invalidated retry must be fenced, was $error", error is SessionRefreshException)
        assertEquals(
            SessionRefreshFailure.STALE_SESSION,
            (error as SessionRefreshException).failure,
        )
        // The pending-first replay kept its exact retained attempt: one auth
        // call, original key, no fresh rotation after the fence.
        assertEquals(1, s.auth.refreshCalls)
        assertEquals(F.KEY_A, s.auth.seenAttempts.single().idempotencyKey)
        assertEquals(1, invocations)
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.IDENTITY_MISMATCH,
            (state as SessionRecoveryState.Locked).reason,
        )
    }

    // ---- Case 14: safe failures preserve eligibility and exact data ----

    @Test
    fun reauthenticationRequiredPropagatesWithCacheIntact() = runTest {
        val s = activeSetup()
        s.auth.failWith(SyncApiException.ReauthenticationRequired())
        val error = try {
            s.session.withAccessToken { "unexpected" }
            null
        } catch (e: SyncApiException.ReauthenticationRequired) {
            e
        }
        assertTrue("reauth failure must propagate closed", error != null)
        assertTrue(s.coordinator.state is SessionRecoveryState.Active)
        val current = s.vault.current().record
        assertEquals(s.record.session.accessExpiresAtMillis, current?.session?.accessExpiresAtMillis)
        assertEquals(s.record.session.refreshExpiresAtMillis, current?.session?.refreshExpiresAtMillis)
        assertEquals("refresh-old", current?.session?.refreshToken?.value)
        assertEquals(F.OP_A, current?.pendingRefresh?.operationId)
        assertEquals(F.KEY_A, current?.pendingRefresh?.attempt?.idempotencyKey)
    }

    @Test
    fun transientFailurePropagatesWithExactUncertainData() = runTest {
        val pending = F.whitespacePending(refreshValue = "refresh-old")
        val s = activeSetup(pending = pending)
        s.auth.failWith(SyncApiException.Transient(30_000L))
        val error = try {
            s.session.withAccessToken { "unexpected" }
            null
        } catch (e: SyncApiException.Transient) {
            e
        }
        assertTrue("transient failure must propagate closed", error != null)
        assertEquals(30_000L, error?.retryAfterMillis)
        assertTrue(s.coordinator.state is SessionRecoveryState.Active)
        val current = s.vault.current().record
        assertEquals(
            pending.attempt.requestJson,
            current?.pendingRefresh?.attempt?.requestJson,
        )
        assertEquals(F.KEY_A, current?.pendingRefresh?.attempt?.idempotencyKey)
        assertEquals("refresh-old", current?.session?.refreshToken?.value)
    }

    @Test
    fun successfulRotationReturnsExactlyBoundToken() = runTest {
        val s = activeSetup()
        val rotated = F.session(
            accessValue = "access-new",
            accessExpiresAtMillis = F.NOW + 900_000L,
            refreshValue = "refresh-new",
            refreshExpiresAtMillis = F.NOW + 2_592_000_000L,
        )
        s.auth.answer(rotated)
        val token = s.session.withAccessToken { it }
        assertEquals(F.binding(), token.binding)
        assertEquals("access-new", token.token.value)
        assertEquals(F.ACCOUNT_ID, token.binding.accountId)
        assertEquals(1L, token.binding.sessionGeneration)
        assertEquals(F.origin(), token.binding.origin)
    }

    // ---- Cancellation-gate clarification: transport callbacks ----
    //
    // Injected transport cancellation stays identical within the active
    // caller even when invalidate or a newer successful reconcile races its
    // delivery; the newer state and handle are unaffected. An actual
    // cancelled-caller cause dominates an injected transport fault.

    @Test
    fun injectedTransportCancellationRacingInvalidateStaysIdentical() = runTest {
        val s = activeSetup()
        val gate = CompletableDeferred<Unit>()
        var fault: Throwable? = null
        s.auth.refreshGate = { _ ->
            gate.await()
            fault?.let { throw it }
        }
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.session.withAccessToken { it.token.value }
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        s.coordinator.invalidate()
        assertTrue(s.coordinator.state is SessionRecoveryState.PrivacyLocked)
        val probe = CancellationException("test-injected-transport-race")
        fault = probe
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        assertTrue(s.coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    @Test
    fun injectedTransportCancellationRacingNewerActiveLeavesNewerUsable() = runTest {
        val s = activeSetup()
        val gate = CompletableDeferred<Unit>()
        var fault: Throwable? = null
        s.auth.refreshGate = { _ ->
            gate.await()
            fault?.let { throw it }
        }
        s.auth.script += { _ -> throw AssertionError("old call must fence in gate") }
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        s.auth.answer(rotated)
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.session.withAccessToken { it.token.value }
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        s.coordinator.invalidate()
        val newer = s.coordinator.reconcile()
        assertTrue(newer is SessionRecoveryState.Active)
        val probe = CancellationException("test-injected-newer-transport")
        fault = probe
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        // One-shot gate: clear the injected fault before the newer handle runs.
        fault = null
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Active)
        val value = (state as SessionRecoveryState.Active).session.withAccessToken { it.token.value }
        assertEquals("access-new", value)
        assertEquals("refresh-new", s.vault.current().record?.session?.refreshToken?.value)
    }

    @Test
    fun callerCauseDominatesInjectedTransportFault() = runTest {
        val s = activeSetup()
        val gate = CompletableDeferred<Unit>()
        var fault: Throwable? = null
        s.auth.refreshGate = { _ ->
            gate.await()
            fault?.let { throw it }
        }
        s.auth.answer(F.session(accessValue = "access-new", refreshValue = "refresh-new"))
        val callerProbe = CancellationException("test-caller-transport-dominates")
        val injected = CancellationException("test-injected-transport-masked")
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.session.withAccessToken { it.token.value }
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
    }

    // ---- Transport-callback shape: fresh access, no rotation ----

    private data class FreshSetup(
        val coordinator: SessionRecoveryCoordinator,
        val vault: FakeSessionVault,
        val authority: FakeRecoveryAuthority,
        val auth: FakeServiceAuthApi,
        val session: RecoveredSession,
    )

    private suspend fun freshSetup(): FreshSetup {
        val record = F.record(
            session = F.session(accessValue = "access-live", refreshValue = "refresh-live"),
        )
        val vault = F.vaultWith(record = record, revision = 1L)
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        val auth = F.auth()
        val coordinator = F.coordinator(vault = vault, authority = authority, authApi = auth)
        val state = coordinator.reconcile()
        if (state !is SessionRecoveryState.Active) {
            throw AssertionError("setup must reconcile Active but was $state")
        }
        return FreshSetup(coordinator, vault, authority, auth, state.session)
    }

    @Test
    fun access401ForcesOneFreshRotationThenRetriesSameCallback() = runTest {
        val s = freshSetup()
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        s.auth.answer(rotated)
        val bearers = mutableListOf<String>()
        var invocations = 0
        val result = s.session.withAccessToken { token ->
            invocations += 1
            bearers += token.token.value
            if (invocations == 1) throw SyncApiException.AuthenticationRequired()
            "recovered-" + token.token.value
        }
        // First 401 forces one serialized fresh refresh, then the SAME frozen
        // callback is retried: original bearer first, rotated bearer second.
        assertEquals("recovered-access-new", result)
        assertEquals(listOf("access-live", "access-new"), bearers)
        assertEquals("one serialized fresh refresh", 1, s.auth.refreshCalls)
        val freshKey = s.auth.seenAttempts.single().idempotencyKey
        assertTrue("fresh rotation needs its own key", freshKey != F.KEY_A && freshKey != F.KEY_B)
        java.util.UUID.fromString(freshKey)
        assertEquals("refresh-new", s.vault.current().record?.session?.refreshToken?.value)
    }

    @Test
    fun persistent401LatchesReauthAndRefusesSubsequentCall() = runTest {
        val s = freshSetup()
        s.auth.answer(F.session(accessValue = "access-new", refreshValue = "refresh-new"))
        var invocations = 0
        val latched = try {
            s.session.withAccessToken {
                invocations += 1
                throw SyncApiException.AuthenticationRequired()
            }
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("persistent 401 must latch reauth, was $latched", latched is SyncApiException.ReauthenticationRequired)
        assertEquals("exactly one retry", 2, invocations)
        assertEquals("one rotation", 1, s.auth.refreshCalls)
        // A subsequent call refuses before callback/HTTP and preserves cache.
        val callsBefore = s.auth.refreshCalls
        val vaultBefore = s.vault.current()
        val refused = try {
            s.session.withAccessToken {
                invocations += 1
                "unexpected"
            }
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("latched reauth must refuse fast, was $refused", refused is SyncApiException.ReauthenticationRequired)
        assertEquals(2, invocations)
        assertEquals(callsBefore, s.auth.refreshCalls)
        assertEquals(vaultBefore, s.vault.current())
        assertTrue(s.coordinator.state is SessionRecoveryState.Active)
    }

    @Test
    fun failedRefreshAfter401PropagatesWithoutRetry() = runTest {
        val s = freshSetup()
        s.auth.failWith(SyncApiException.Transient())
        var invocations = 0
        val error = try {
            s.session.withAccessToken {
                invocations += 1
                throw SyncApiException.AuthenticationRequired()
            }
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("failed refresh must propagate, was $error", error is SyncApiException.Transient)
        assertEquals("one refresh attempt, no retry", 1, s.auth.refreshCalls)
        assertEquals("single callback invocation", 1, invocations)
        val pending = s.vault.current().record?.pendingRefresh?.attempt
        assertEquals(s.auth.seenAttempts.single().idempotencyKey, pending?.idempotencyKey)
        assertEquals(s.auth.seenAttempts.single().requestJson, pending?.requestJson)
    }

    @Test
    fun callerCancellationDuringRequestCallbackDominatesAndStaysActive() = runTest {
        val s = freshSetup()
        val gate = CompletableDeferred<Unit>()
        val probe = CancellationException("test-caller-callback")
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.session.withAccessToken {
                    gate.await()
                    "never"
                }
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        job.cancel(probe)
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        assertTrue(s.coordinator.state is SessionRecoveryState.Active)
        assertEquals(0, s.auth.refreshCalls)
    }

    @Test
    fun injectedCallbackCancellationPropagatesIdentically() = runTest {
        val s = freshSetup()
        val injected = CancellationException("test-injected-callback")
        val caught = try {
            s.session.withAccessToken { throw injected }
            null
        } catch (e: Throwable) {
            e
        }
        assertSame(injected, caught)
        assertTrue(s.coordinator.state is SessionRecoveryState.Active)
        assertEquals(0, s.auth.refreshCalls)
    }

    // ---- Runtime authority faults: UNAVAILABLE, never masked as safe ----

    @Test
    fun runtimeAuthorityFaultLocksWithClosedErrorAndDeadHandle() = runTest {
        val s = activeSetup()
        s.authority.failNow(RuntimeException("test-authority-runtime"))
        // Invoking the handle makes this an in-flight caller: UNAVAILABLE or
        // STALE_SESSION are both closed outcomes of the clarified rule.
        val error = try {
            s.session.withAccessToken { "unexpected" }
            null
        } catch (e: SessionRefreshException) {
            e
        } catch (e: Throwable) {
            null
        }
        assertTrue("authority fault must stay closed", error is SessionRefreshException)
        val failure = (error as SessionRefreshException).failure
        assertTrue(
            "expected UNAVAILABLE or STALE_SESSION, was $failure",
            failure == SessionRefreshFailure.UNAVAILABLE || failure == SessionRefreshFailure.STALE_SESSION,
        )
        assertEquals(0, s.auth.refreshCalls)
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.AUTHORITY_UNAVAILABLE,
            (state as SessionRecoveryState.Locked).reason,
        )
        // The handle is permanently dead afterwards.
        val loadsBefore = s.vault.loads
        val callsBefore = s.auth.refreshCalls
        assertRecoveryStale {
            s.session.withAccessToken { "unexpected" }
        }
        assertEquals(loadsBefore, s.vault.loads)
        assertEquals(callsBefore, s.auth.refreshCalls)
    }

    @Test
    fun leaseCheckAuthorizationPreservesExplicitUnavailable() = runTest {
        val lease = SessionRefreshLease(
            binding = F.binding(),
            operationId = F.OP_A,
            phase = VaultSessionPhase.ACTIVE,
            authorizationCheck = { throw SessionRefreshException(SessionRefreshFailure.UNAVAILABLE) },
        )
        val error = try {
            lease.checkAuthorization()
            null
        } catch (e: SessionRefreshException) {
            e
        } catch (e: Throwable) {
            null
        }
        assertTrue("explicit UNAVAILABLE must be preserved", error != null)
        assertEquals(SessionRefreshFailure.UNAVAILABLE, error?.failure)
    }

    @Test
    fun inFlightAuthorityFaultKeepsFaultReasonWhileCallerSeesClosedError() = runTest {
        val s = activeSetup()
        val gate = CompletableDeferred<Unit>()
        s.auth.refreshGate = { _ -> gate.await() }
        s.auth.answer(F.session(accessValue = "access-new", refreshValue = "refresh-new"))
        val finished = async {
            try {
                s.session.withAccessToken { it.token.value }
                null
            } catch (e: Throwable) {
                e
            }
        }
        runCurrent()
        s.authority.failNow(RuntimeException("test-authority-inflight"))
        gate.complete(Unit)
        advanceUntilIdle()
        val error = finished.await()
        assertTrue("in-flight authority fault must stay closed, was $error", error is SessionRefreshException)
        val failure = (error as SessionRefreshException).failure
        assertTrue(
            "expected UNAVAILABLE or STALE_SESSION, was $failure",
            failure == SessionRefreshFailure.UNAVAILABLE || failure == SessionRefreshFailure.STALE_SESSION,
        )
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.AUTHORITY_UNAVAILABLE,
            (state as SessionRecoveryState.Locked).reason,
        )
    }

    // ---- Request-callback fencing: success and error under authority change ----

    @Test
    fun authorityChangeDuringCallbackSuccessFencesStaleResult() = runTest {
        val s = freshSetup()
        val error = try {
            s.session.withAccessToken {
                s.authority.stable(F.activeCompleteSnapshot(revision = 10L))
                "stale-value"
            }
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("authority change must fence callback result", error is SessionRefreshException)
        assertEquals(SessionRefreshFailure.STALE_SESSION, (error as SessionRefreshException).failure)
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.IDENTITY_MISMATCH,
            (state as SessionRecoveryState.Locked).reason,
        )
    }

    @Test
    fun authorityChangeDuringCallbackErrorFencesStaleCategory() = runTest {
        val s = freshSetup()
        val error = try {
            s.session.withAccessToken {
                s.authority.stable(F.activeCompleteSnapshot(revision = 10L))
                throw SyncApiException.Transient()
            }
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("authority change must fence callback error", error is SessionRefreshException)
        assertEquals(SessionRefreshFailure.STALE_SESSION, (error as SessionRefreshException).failure)
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Locked)
        assertEquals(
            SessionRecoveryReason.IDENTITY_MISMATCH,
            (state as SessionRecoveryState.Locked).reason,
        )
    }

    @Test
    fun injectedCallbackCancellationRacingNewerActiveLeavesNewerUsable() = runTest {
        val s = freshSetup()
        val gate = CompletableDeferred<Unit>()
        var fault: Throwable? = null
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.session.withAccessToken {
                    gate.await()
                    fault?.let { throw it }
                    "never"
                }
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        s.coordinator.invalidate()
        val newer = s.coordinator.reconcile()
        assertTrue(newer is SessionRecoveryState.Active)
        val probe = CancellationException("test-injected-callback-newer")
        fault = probe
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        val state = s.coordinator.state
        assertTrue(state is SessionRecoveryState.Active)
        val value = (state as SessionRecoveryState.Active).session.withAccessToken { it.token.value }
        assertEquals("access-live", value)
        assertEquals(0, s.auth.refreshCalls)
    }

    @Test
    fun callerCancellationDuringCallbackRacingInvalidateStaysPrivate() = runTest {
        val s = freshSetup()
        val gate = CompletableDeferred<Unit>()
        val probe = CancellationException("test-caller-callback-invalidate")
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.session.withAccessToken {
                    gate.await()
                    "never"
                }
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        runCurrent()
        s.coordinator.invalidate()
        job.cancel(probe)
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        assertTrue(s.coordinator.state is SessionRecoveryState.PrivacyLocked)
    }

    // ---- Offline eligibility and replay-before-rotation ordering ----

    @Test
    fun expiredGrantWithoutPendingStaysActiveWithLocalReauth() = runTest {
        val seeded = F.record(session = F.expiredSession(), pendingRefresh = null)
        val vault = F.vaultWith(record = seeded, revision = 1L)
        val authority = FakeRecoveryAuthority(F.activeCompleteSnapshot(revision = 9L))
        val auth = F.auth()
        val clock = SessionRecoveryTestFixtures.CountingClock()
        val uuids = SessionRecoveryTestFixtures.CountingUuids()
        val coordinator = F.coordinator(
            vault = vault,
            authority = authority,
            authApi = auth,
            clock = clock,
            uuids = uuids,
        )
        assertTrue(coordinator.reconcile() is SessionRecoveryState.Active)
        assertEquals(0, clock.calls)
        assertEquals(0, uuids.calls)
        assertEquals(0, auth.refreshCalls)
        val active = coordinator.state as SessionRecoveryState.Active
        val error = try {
            active.session.withAccessToken { "unexpected" }
            null
        } catch (e: SyncApiException.ReauthenticationRequired) {
            e
        } catch (e: Throwable) {
            null
        }
        assertTrue("expired refresh must latch reauth locally", error != null)
        assertEquals(0, vault.commits)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, uuids.calls)
        assertTrue(coordinator.state is SessionRecoveryState.Active)
    }

    @Test
    fun expiredAccessReplayPersistsGrantBeforeFreshRotation() = runTest {
        val body = " { \"refreshToken\" : \"refresh-old\" } "
        val original = VaultPendingRefresh(
            operationId = F.OP_A,
            attempt = ServiceRefreshAttempt.fromJson(F.KEY_A, body),
        )
        val s = activeSetup(pending = original)
        val mid = F.session(
            accessValue = "access-mid",
            accessExpiresAtMillis = F.NOW - 500L,
            refreshValue = "refresh-mid",
        )
        val rotated = F.session(accessValue = "access-new", refreshValue = "refresh-new")
        var secondSeamRefresh: String? = null
        var secondSeamAccessExpires: Long? = null
        var secondSeamRefreshExpires: Long? = null
        var secondSeamPendingKey: String? = null
        var secondSeamAttemptKey: String? = null
        s.auth.answer(mid)
        s.auth.script += { attempt ->
            val current = s.vault.current().record
            secondSeamRefresh = current?.session?.refreshToken?.value
            secondSeamAccessExpires = current?.session?.accessExpiresAtMillis
            secondSeamRefreshExpires = current?.session?.refreshExpiresAtMillis
            secondSeamPendingKey = current?.pendingRefresh?.attempt?.idempotencyKey
            secondSeamAttemptKey = attempt.idempotencyKey
            rotated
        }
        // Replay + fresh rotation complete in the SAME call: the expired
        // access-mid grant is saved durably but never admitted.
        assertEquals("access-new", s.session.withAccessToken { it.token.value })
        // A second call reuses access-new without a third HTTP.
        assertEquals("access-new", s.session.withAccessToken { it.token.value })
        assertEquals(2, s.auth.refreshCalls)
        // Original replay first: exact text/key.
        assertEquals(F.KEY_A, s.auth.seenAttempts[0].idempotencyKey)
        assertEquals(body, s.auth.seenAttempts[0].requestJson)
        // Second seam observes the whole original grant with original (never
        // extended) expiries, plus the fresh pending/attempt key.
        assertEquals("refresh-mid", secondSeamRefresh)
        assertEquals(mid.accessExpiresAtMillis, secondSeamAccessExpires)
        assertEquals(mid.refreshExpiresAtMillis, secondSeamRefreshExpires)
        assertTrue(secondSeamPendingKey != null && secondSeamPendingKey != F.KEY_A)
        java.util.UUID.fromString(secondSeamPendingKey)
        assertEquals(secondSeamPendingKey, secondSeamAttemptKey)
        assertEquals("refresh-new", s.vault.current().record?.session?.refreshToken?.value)
    }
}
