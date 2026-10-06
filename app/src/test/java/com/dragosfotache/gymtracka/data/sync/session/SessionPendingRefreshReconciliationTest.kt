package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.HttpServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
 * R09f independent QA: inert exact-pending-refresh reconciliation before reauthentication.
 *
 * Derived ONLY from docs/archive/remaining-work/R09f-task.md normative API/protocol/fence
 * contracts plus QA-owned fixtures/doubles (SessionReauthenticationTestFixtures,
 * ScriptedReauthenticationAuthority, Refresh/SessionRecovery fixtures,
 * FakeSessionVault, FakeServiceAuthApi). No production declarations, diffs,
 * Worker work, generated sources, decompiled classes or schema JSON inspected.
 * Coordinator-supplied harness facts used as contracts (ctor orders, inert
 * construction, model shapes, SyncApiException ctor shapes, failure set,
 * namespace/seed literals). Literal independent oracles throughout;
 * deterministic gates only (deferred/barrier/fault queues, phase-observed
 * hooks, no sleeps, no DAO mocks, no load/read-count seam oracles).
 *
 * Only the normative external API is referenced: suspend
 * reconcilePendingRefresh(expected, refreshCoordinator) and
 * SettledSessionObservation(room, vaultRevision). Internal helper/
 * compatibility predicate names are never referenced. Clock/UUID providers
 * throw AssertionError by default, proving zero calls in this replay-only
 * operation.
 *
 * Each test proves a distinct new obligation; representative refusal cases are
 * grouped without an exhaustive matrix (existing R04/R09e matrices reused, not
 * duplicated).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionPendingRefreshReconciliationTest {
    private val F = SessionReauthenticationTestFixtures

    private data class PairSetup(
        val reauth: SessionReauthenticationCoordinator,
        val refresh: SessionRefreshCoordinator,
        val authority: ScriptedReauthenticationAuthority,
        val vault: FakeSessionVault,
        val auth: FakeServiceAuthApi,
        val runOwner: PushRunOwner,
        val credentialMutex: Mutex,
    )

    private data class WrappedSetup(
        val reauth: SessionReauthenticationCoordinator,
        val refresh: SessionRefreshCoordinator,
        val authority: ScriptedReauthenticationAuthority,
        val auth: FakeServiceAuthApi,
    )

    private fun setupOnVault(
        vault: SessionVault,
        authoritySnapshot: RecoveryRoomSnapshot = F.activeSnapshot(),
        auth: FakeServiceAuthApi = FakeServiceAuthApi(),
        runOwner: PushRunOwner = PushRunOwner(),
        credentialMutex: Mutex = Mutex(),
        clock: () -> Long = { throw AssertionError("R09f replay must not read the clock") },
        uuids: () -> String = { throw AssertionError("R09f replay must not mint UUIDs") },
    ): WrappedSetup {
        val authority = ScriptedReauthenticationAuthority(authoritySnapshot)
        val origin = F.origin()
        val reauth = F.coordinator(
            origin = origin,
            authority = authority,
            vault = vault,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        // Normative sharing: same configured origin, referentially identical
        // vault, referentially identical credential mutex.
        val refresh = SessionRefreshCoordinator(
            origin = origin,
            vault = vault,
            authApi = auth,
            clockMillis = clock,
            uuidProvider = uuids,
            mutex = credentialMutex,
        )
        return WrappedSetup(reauth, refresh, authority, auth)
    }

    private fun setupPair(
        authoritySnapshot: RecoveryRoomSnapshot = F.activeSnapshot(),
        vaultRecord: VaultSessionRecord? = F.activeRecord(),
        vaultRevision: Long = 5L,
        auth: FakeServiceAuthApi = FakeServiceAuthApi(),
        runOwner: PushRunOwner = PushRunOwner(),
        credentialMutex: Mutex = Mutex(),
        clock: () -> Long = { throw AssertionError("R09f replay must not read the clock") },
        uuids: () -> String = { throw AssertionError("R09f replay must not mint UUIDs") },
    ): PairSetup {
        val vault = F.vaultWith(record = vaultRecord, revision = vaultRevision)
        val w = setupOnVault(vault, authoritySnapshot, auth, runOwner, credentialMutex, clock, uuids)
        return PairSetup(w.reauth, w.refresh, w.authority, vault, auth, runOwner, credentialMutex)
    }

    private fun pendingSetup(
        oldAccessValue: String = "access-live",
        oldAccessExpiresAt: Long = F.NOW + RefreshTestFixtures.FIFTEEN_MIN,
        oldRefreshExpiresAt: Long = F.NOW - 1_000L,
        auth: FakeServiceAuthApi = FakeServiceAuthApi(),
    ): PairSetup {
        // Old access still live, consumed predecessor refresh deadline past:
        // replay must still send the exact retained attempt ONCE.
        val old = F.session(
            accessValue = oldAccessValue,
            accessExpiresAtMillis = oldAccessExpiresAt,
            refreshValue = "refresh-old",
            refreshExpiresAtMillis = oldRefreshExpiresAt,
        )
        val record = F.activeRecord(session = old, pendingRefresh = F.whitespacePending())
        return setupPair(vaultRecord = record, auth = auth)
    }

    private fun assertUntouchedRoomAndNoReservation(s: PairSetup, expected: RecoveryRoomSnapshot) {
        assertEquals(expected, s.authority.durableSnapshot())
        assertEquals(0, s.authority.begins)
        assertEquals(0, s.authority.finishes)
    }

    // ---- Construction + read-only idempotence ----

    @Test
    fun constructionPerformsNoIo() = runTest {
        val s = setupPair()
        assertEquals(0, s.authority.reads)
        assertEquals(0, s.vault.loads)
        assertEquals(0, s.vault.commits)
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(0, s.authority.begins)
    }

    @Test
    fun nonPendingExpiredPairIsReadOnlyIdempotence() = runTest {
        val expired = F.expiredGrant()
        val record = F.activeRecord(session = expired, pendingRefresh = null)
        val s = setupPair(vaultRecord = record, vaultRevision = 5L)
        val expected = F.activeSnapshot()

        val observation = s.reauth.reconcilePendingRefresh(expected, s.refresh)

        assertEquals(expected, observation.room)
        assertEquals(5L, observation.vaultRevision)
        // No HTTP/CAS/clock/UUID even though both deadlines are past.
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, expected)
        assertEquals(record, s.vault.current().record)
        F.assertRedacted(
            observation.toString(),
            F.defaultSecrets("access-old", "refresh-old", F.ORIGIN_ROOT),
        )
    }

    // ---- Pending-first exact replay ----

    @Test
    fun pendingWhitespaceReplayExactOnceDespiteLiveAccessAndExpiredConsumedDeadline() = runTest {
        val s = pendingSetup()
        val returned = F.newGrant()
        s.auth.answer(returned)
        val expected = F.activeSnapshot()

        val observation = s.reauth.reconcilePendingRefresh(expected, s.refresh)

        // ONE exact transmission of the retained typed attempt.
        assertEquals(1, s.auth.refreshCalls)
        assertEquals(1, s.auth.seenAttempts.size)
        val sent = s.auth.seenAttempts.single()
        assertEquals(F.KEY_A, sent.idempotencyKey)
        assertEquals(" { \"refreshToken\" : \"refresh-old\" } ", sent.requestJson)
        assertEquals("refresh-old", sent.refreshToken.value)
        // One full-grant CAS at the sent revision: exact returned grant, no
        // pending, old binding/operation/ACTIVE phase preserved.
        assertEquals(1, s.vault.commits)
        assertEquals(listOf(6L), s.vault.committedRevisions)
        val stored = checkNotNull(s.vault.current().record)
        assertEquals(F.binding(generation = 7L), stored.binding)
        assertEquals(F.OP_OLD, stored.operationId)
        assertEquals(VaultSessionPhase.ACTIVE, stored.phase)
        assertEquals(returned, stored.session)
        assertNull(stored.pendingRefresh)
        // Advisory return on the unchanged old ACTIVE authority.
        assertEquals(expected, observation.room)
        assertEquals(6L, observation.vaultRevision)
        assertUntouchedRoomAndNoReservation(s, expected)
        F.assertRedacted(
            observation.toString(),
            F.defaultSecrets("access-live", "access-new", "refresh-old", "refresh-new", F.ORIGIN_ROOT),
        )
    }

    @Test
    fun returnedExpiredGrantStoredWithoutFreshRotation() = runTest {
        val s = pendingSetup()
        val expired = F.expiredGrant()
        s.auth.answer(expired)

        val observation = s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)

        // Expired access AND refresh still stored exactly; no second rotation.
        assertEquals(1, s.auth.refreshCalls)
        assertEquals(expired, s.vault.current().record?.session)
        assertNull(s.vault.current().record?.pendingRefresh)
        assertEquals(6L, s.vault.current().revision)
        assertEquals(6L, observation.vaultRevision)
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
    }

    // ---- Representative missing/mismatched/invalid refusals (grouped) ----

    @Test
    fun representativeMissingMismatchedAndInvalidInputRefuseWithoutHttpOrCas() = runTest {
        // Missing vault record.
        run {
            val s = setupPair(vaultRecord = null)
            F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
            }
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
        }
        // Foreign operation in an otherwise matching vault.
        run {
            val s = setupPair(vaultRecord = F.activeRecord(operationId = F.OP_NEW))
            F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
            }
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            assertEquals(F.OP_NEW, s.vault.current().record?.operationId)
        }
        // Foreign binding origin in the caller snapshot vs configured origin.
        run {
            val foreignExpected = F.activeSnapshot(root = F.OTHER_ORIGIN_ROOT, accountId = F.ACCOUNT_ID)
            val s = setupPair(authoritySnapshot = foreignExpected)
            F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
                s.reauth.reconcilePendingRefresh(foreignExpected, s.refresh)
            }
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            assertEquals(0, s.authority.begins)
        }
        // Incompatible supplied refresh coordinator (foreign configured
        // origin): pure compatibility mismatch, INVALID_INPUT before any
        // fence/locks/I/O, no silent independent owner.
        run {
            val s = setupPair()
            val foreign = SessionRefreshCoordinator(
                origin = F.origin(F.OTHER_ORIGIN_ROOT),
                vault = s.vault,
                authApi = s.auth,
                clockMillis = { throw AssertionError("no clock") },
                uuidProvider = { throw AssertionError("no uuid") },
                mutex = s.credentialMutex,
            )
            F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), foreign)
            }
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
        }
        // Independently owned vault instance on the supplied refresh
        // coordinator: same compatibility refusal, neither vault touched.
        run {
            val s = setupPair()
            val otherVault = F.vaultWith(record = F.activeRecord(), revision = 5L)
            val alien = SessionRefreshCoordinator(
                origin = F.origin(),
                vault = otherVault,
                authApi = s.auth,
                clockMillis = { throw AssertionError("no clock") },
                uuidProvider = { throw AssertionError("no uuid") },
                mutex = s.credentialMutex,
            )
            F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), alien)
            }
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            assertEquals(0, otherVault.commits)
            assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
        }
        // Non-ACTIVE caller snapshot is invalid input, not a silent queue.
        run {
            val transitioning = F.transitioningSnapshot()
            val s = setupPair(authoritySnapshot = transitioning)
            F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
                s.reauth.reconcilePendingRefresh(transitioning, s.refresh)
            }
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            assertEquals(0, s.authority.begins)
        }
        // Incomplete baseline is invalid input.
        run {
            val incomplete = F.activeSnapshot(baselineComplete = false)
            val s = setupPair(authoritySnapshot = incomplete)
            F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
                s.reauth.reconcilePendingRefresh(incomplete, s.refresh)
            }
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            assertEquals(0, s.authority.begins)
        }
    }

    @Test
    fun roomDriftStaleAndVaultOverflowRefuseBeforeTransmission() = runTest {
        // Room drift before admission refuses STALE_AUTHORITY with no HTTP.
        run {
            val s = pendingSetup()
            s.authority.seedDurable(F.activeSnapshot(revision = 12L))
            val e = F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
            }
            F.assertClosed(e, F.defaultSecrets("refresh-old"))
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            assertEquals(0, s.authority.begins)
        }
        // No vault revision space refuses COUNTER_OVERFLOW before HTTP; Room
        // needs no increment space since no Room mutation occurs.
        run {
            val pending = F.whitespacePending()
            val s = setupPair(
                vaultRecord = F.activeRecord(pendingRefresh = pending),
                vaultRevision = Long.MAX_VALUE,
            )
            F.assertReauthFailure(SessionReauthenticationFailure.COUNTER_OVERFLOW) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
            }
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.authority.begins)
            assertEquals(0, s.vault.commits)
            assertEquals(Long.MAX_VALUE, s.vault.current().revision)
        }
    }

    /**
     * Delegating vault that serves the valid initial snapshot once, then
     * installs a VALID consistent full-record drift before serving the
     * alternating reread. The drift pair stays internally consistent
     * (pending token matches the drifted session), so only the
     * snapshot-vs-reread comparison can refuse it.
     */
    private class DriftOnSecondLoadVault(
        private val delegate: FakeSessionVault,
        private val drifted: VaultSessionRecord,
    ) : SessionVault {
        var loads = 0
            private set
        var driftInstalled = false
            private set

        override suspend fun load(): VaultSnapshot {
            loads += 1
            if (loads == 1) return delegate.load()
            if (!driftInstalled) {
                driftInstalled = true
                delegate.compareAndSet(delegate.current().revision, drifted)
            }
            return delegate.load()
        }

        override suspend fun compareAndSet(
            expectedRevision: Long,
            record: VaultSessionRecord?,
        ): VaultSnapshot = delegate.compareAndSet(expectedRevision, record)
    }

    @Test
    fun vaultDriftBetweenSnapshotAndRereadRefusesWithoutAdoption() = runTest {
        val validPending = F.activeRecord(
            session = F.session(refreshValue = "refresh-old"),
            pendingRefresh = F.whitespacePending(),
        )
        val delegate = F.vaultWith(record = validPending, revision = 5L)
        // Consistent drift: pending token matches the drifted session, so the
        // setup itself is valid and only mid-preflight drift is observed.
        val drifted = F.activeRecord(
            session = F.session(refreshValue = "refresh-DRIFT"),
            pendingRefresh = F.pending(refreshValue = "refresh-DRIFT"),
        )
        val wrapping = DriftOnSecondLoadVault(delegate, drifted)
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val w = setupOnVault(wrapping, runOwner = runOwner, credentialMutex = credentialMutex)
        F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            w.reauth.reconcilePendingRefresh(F.activeSnapshot(), w.refresh)
        }
        assertTrue("drift must have landed between snapshot and reread", wrapping.driftInstalled)
        // No HTTP: the alternating reread refused before admission. The
        // drift stands preserved, never adopted or repaired; Room untouched.
        assertEquals(0, w.auth.refreshCalls)
        assertEquals(1, delegate.commits)
        assertEquals(drifted, delegate.current().record)
        assertEquals(F.activeSnapshot(), w.authority.durableSnapshot())
        assertEquals(0, w.authority.begins)
        assertEquals(0, w.authority.finishes)
    }

    @Test
    fun casConflictAfterOneHttpSendRetainsPendingWithoutRetry() = runTest {
        val s = pendingSetup()
        s.vault.casFailures += SessionVaultException(VaultFailure.CONFLICT)
        s.auth.answer(F.newGrant())
        F.assertReauthFailure(SessionReauthenticationFailure.VAULT_CONFLICT) {
            s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
        }
        assertEquals(1, s.auth.refreshCalls)
        assertEquals(1, s.vault.commits)
        assertEquals(
            " { \"refreshToken\" : \"refresh-old\" } ",
            s.vault.current().record?.pendingRefresh?.attempt?.requestJson,
        )
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
    }

    // ---- Safe refresh failures retain exact pending (grouped by mapping) ----

    @Test
    fun transientAndRateLimitRetainExactPendingWithSameInstance() = runTest {
        val s = pendingSetup()
        val expected = F.activeSnapshot()
        // Plain transient keeps the same instance; restart retries the exact
        // retained attempt.
        val boom = SyncApiException.Transient()
        s.auth.failWith(boom)
        try {
            s.reauth.reconcilePendingRefresh(expected, s.refresh)
            fail("expected Transient")
        } catch (e: SyncApiException.Transient) {
            assertSame(boom, e)
        }
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, expected)
        assertEquals(
            " { \"refreshToken\" : \"refresh-old\" } ",
            s.vault.current().record?.pendingRefresh?.attempt?.requestJson,
        )
        // Rate limit is Transient with a bounded hint; hint preserved, still
        // no CAS/reservation/cleanup, and the retry sends the same bytes.
        val limited = SyncApiException.Transient(retryAfterMillis = 1000L)
        s.auth.failWith(limited)
        try {
            s.reauth.reconcilePendingRefresh(expected, s.refresh)
            fail("expected rate-limited Transient")
        } catch (e: SyncApiException.Transient) {
            assertSame(limited, e)
            assertEquals(1000L, e.retryAfterMillis)
        }
        assertEquals(2, s.auth.refreshCalls)
        assertEquals(F.KEY_A, s.auth.seenAttempts[0].idempotencyKey)
        assertEquals(F.KEY_A, s.auth.seenAttempts[1].idempotencyKey)
        assertEquals(s.auth.seenAttempts[0].requestJson, s.auth.seenAttempts[1].requestJson)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, expected)
    }

    @Test
    fun explicitReauthWrongAccountAndMalformedRetainExactPending() = runTest {
        val s = pendingSetup()
        val expected = F.activeSnapshot()
        // Explicit AuthenticationRequired maps to ReauthenticationRequired as
        // existing refresh machinery does (new instance, same seam).
        s.auth.failWith(SyncApiException.AuthenticationRequired())
        try {
            s.reauth.reconcilePendingRefresh(expected, s.refresh)
            fail("expected ReauthenticationRequired")
        } catch (e: SyncApiException.ReauthenticationRequired) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        }
        assertEquals(0, s.vault.commits)
        // Wrong-account grant becomes MalformedResponse; never installed.
        s.auth.answer(F.newGrant(accountId = F.OTHER_ACCOUNT_ID))
        try {
            s.reauth.reconcilePendingRefresh(expected, s.refresh)
            fail("expected MalformedResponse for wrong account")
        } catch (e: SyncApiException.MalformedResponse) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        }
        // Strict malformed replay keeps the same instance.
        val malformed = SyncApiException.MalformedResponse()
        s.auth.failWith(malformed)
        try {
            s.reauth.reconcilePendingRefresh(expected, s.refresh)
            fail("expected MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertSame(malformed, e)
        }
        assertEquals(3, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, expected)
        assertEquals(
            " { \"refreshToken\" : \"refresh-old\" } ",
            s.vault.current().record?.pendingRefresh?.attempt?.requestJson,
        )
        assertEquals(F.OP_OLD, s.vault.current().record?.pendingRefresh?.operationId)
        assertTrue(s.vault.committedRecords.isEmpty())
    }

    @Test
    fun roomDriftTakesPrecedenceOverLateRefreshError() = runTest {
        val s = pendingSetup()
        val expected = F.activeSnapshot()
        val boom = SyncApiException.Transient()
        // Room drifts while the single HTTP send is admitted; the error path
        // recheck must surface STALE_AUTHORITY, never the late category.
        s.auth.refreshGate = {
            s.authority.seedDurable(F.activeSnapshot(revision = 99L))
        }
        s.auth.failWith(boom)
        F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            s.reauth.reconcilePendingRefresh(expected, s.refresh)
        }
        assertEquals(1, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertEquals(0, s.authority.begins)
        assertEquals(
            " { \"refreshToken\" : \"refresh-old\" } ",
            s.vault.current().record?.pendingRefresh?.attempt?.requestJson,
        )
    }

    @Test
    fun strictLegacyReplayThroughActualAdapterRetainsExactPending() = runTest {
        // End-to-end strict decoding through the actual production adapter
        // over the QA-owned scripted transport: a nonzero sub-millisecond
        // tail in either instant stays MalformedResponse. A fake that throws
        // MalformedResponse directly cannot prove this strict path.
        val transport = FakeServiceHttpTransport()
        transport.queueOk(RefreshTestFixtures.legacyGrantBody())
        val adapter = HttpServiceAuthApi(F.origin(), transport)
        val delegate = F.vaultWith(
            record = F.activeRecord(
                session = F.session(refreshValue = "refresh-old"),
                pendingRefresh = F.whitespacePending(),
            ),
            revision = 5L,
        )
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val authority = ScriptedReauthenticationAuthority(F.activeSnapshot())
        val origin = F.origin()
        val reauth = F.coordinator(
            origin = origin,
            authority = authority,
            vault = delegate,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        val refresh = SessionRefreshCoordinator(
            origin = origin,
            vault = delegate,
            authApi = adapter,
            clockMillis = { throw AssertionError("R09f replay must not read the clock") },
            uuidProvider = { throw AssertionError("R09f replay must not mint UUIDs") },
            mutex = credentialMutex,
        )
        try {
            reauth.reconcilePendingRefresh(F.activeSnapshot(), refresh)
            fail("expected MalformedResponse for strict legacy replay")
        } catch (e: SyncApiException.MalformedResponse) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        }
        // Exactly one HTTP send; the legacy bytes never install, the exact
        // whitespace pending stands, Room has no reservation.
        assertEquals(1, transport.capturedRequests.size)
        assertEquals(0, delegate.commits)
        assertEquals(
            " { \"refreshToken\" : \"refresh-old\" } ",
            delegate.current().record?.pendingRefresh?.attempt?.requestJson,
        )
        assertEquals(F.activeSnapshot(), authority.durableSnapshot())
        assertEquals(0, authority.begins)
        assertEquals(0, authority.finishes)
    }

    @Test
    fun roomChangeInsideStartedCasCommitsBytesButReportsStale() = runTest {
        // Old-Room change landing INSIDE an already-started CAS (at the
        // vault seam, after pre-CAS guards passed): the admitted CAS still
        // commits (no compensation), but the final rereads refuse
        // STALE_AUTHORITY and no retry follows from the failed call.
        val delegate = F.vaultWith(
            record = F.activeRecord(
                session = F.session(refreshValue = "refresh-old"),
                pendingRefresh = F.whitespacePending(),
            ),
            revision = 5L,
        )
        val auth = FakeServiceAuthApi()
        auth.answer(F.newGrant())
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val authority = ScriptedReauthenticationAuthority(F.activeSnapshot())
        var hookFired = false
        val wrapping = object : SessionVault {
            override suspend fun load(): VaultSnapshot = delegate.load()
            override suspend fun compareAndSet(
                expectedRevision: Long,
                record: VaultSessionRecord?,
            ): VaultSnapshot {
                if (!hookFired) {
                    hookFired = true
                    authority.seedDurable(F.activeSnapshot(revision = 12L))
                }
                return delegate.compareAndSet(expectedRevision, record)
            }
        }
        val origin = F.origin()
        val reauth = F.coordinator(
            origin = origin,
            authority = authority,
            vault = wrapping,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        val refresh = SessionRefreshCoordinator(
            origin = origin,
            vault = wrapping,
            authApi = auth,
            clockMillis = { throw AssertionError("R09f replay must not read the clock") },
            uuidProvider = { throw AssertionError("R09f replay must not mint UUIDs") },
            mutex = credentialMutex,
        )
        F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            reauth.reconcilePendingRefresh(F.activeSnapshot(), refresh)
        }
        assertTrue("pre-CAS Room change hook must have fired", hookFired)
        assertEquals(1, auth.refreshCalls)
        // Committed bytes stand without success or retry: exact returned
        // grant installed, pending cleared, Room drift retained for later
        // owner disposition.
        assertEquals(6L, delegate.current().revision)
        assertEquals(F.newGrant(), delegate.current().record?.session)
        assertNull(delegate.current().record?.pendingRefresh)
        assertEquals(F.activeSnapshot(revision = 12L), authority.durableSnapshot())
        assertEquals(0, authority.begins)
        assertEquals(0, authority.finishes)
    }

    @Test
    fun roomChangeDuringPreCasReloadRefusesWithZeroCas() = runTest {
        // Pre-CAS LOAD guard: after the single HTTP send is admitted, the
        // next vault preload hook changes old Room BEFORE returning. The
        // pre-CAS Room check refuses STALE_AUTHORITY with ZERO CAS and the
        // exact pending predecessor retained.
        val delegate = F.vaultWith(
            record = F.activeRecord(
                session = F.session(refreshValue = "refresh-old"),
                pendingRefresh = F.whitespacePending(),
            ),
            revision = 5L,
        )
        val auth = FakeServiceAuthApi()
        auth.answer(F.newGrant())
        var admitted = false
        auth.refreshGate = {
            admitted = true
        }
        var driftInstalled = false
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val authority = ScriptedReauthenticationAuthority(F.activeSnapshot())
        val wrapping = object : SessionVault {
            override suspend fun load(): VaultSnapshot {
                // Only the post-admission preload (the pre-CAS reload) may
                // drift; earlier preflight loads must see the valid pair.
                if (admitted && !driftInstalled) {
                    driftInstalled = true
                    authority.seedDurable(F.activeSnapshot(revision = 12L))
                }
                return delegate.load()
            }
            override suspend fun compareAndSet(
                expectedRevision: Long,
                record: VaultSessionRecord?,
            ): VaultSnapshot = delegate.compareAndSet(expectedRevision, record)
        }
        val origin = F.origin()
        val reauth = F.coordinator(
            origin = origin,
            authority = authority,
            vault = wrapping,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        val refresh = SessionRefreshCoordinator(
            origin = origin,
            vault = wrapping,
            authApi = auth,
            clockMillis = { throw AssertionError("R09f replay must not read the clock") },
            uuidProvider = { throw AssertionError("R09f replay must not mint UUIDs") },
            mutex = credentialMutex,
        )
        F.assertReauthFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            reauth.reconcilePendingRefresh(F.activeSnapshot(), refresh)
        }
        assertTrue("HTTP must have been admitted before the preload drift", admitted)
        assertTrue("drift must have landed during the pre-CAS reload", driftInstalled)
        assertEquals(1, auth.refreshCalls)
        assertEquals(0, delegate.commits)
        assertEquals(
            " { \"refreshToken\" : \"refresh-old\" } ",
            delegate.current().record?.pendingRefresh?.attempt?.requestJson,
        )
        assertEquals(F.activeSnapshot(revision = 12L), authority.durableSnapshot())
        assertEquals(0, authority.begins)
        assertEquals(0, authority.finishes)
    }

    @Test
    fun nonadjacentRevisionAfterOneCasRefusesWithoutRetry() = runTest {
        // Inert vault-contract adversarial probe for the exact +1 proof: a
        // faulty vault double-commits a single invocation CAS, so it returns
        // and durably holds a NONADJACENT revision (7 instead of sent+1=6)
        // with an otherwise exact installed grant. Reload verification must
        // refuse IDENTITY_MISMATCH with no retry, replacement, or cleanup.
        val delegate = F.vaultWith(
            record = F.activeRecord(
                session = F.session(refreshValue = "refresh-old"),
                pendingRefresh = F.whitespacePending(),
            ),
            revision = 5L,
        )
        val auth = FakeServiceAuthApi()
        auth.answer(F.newGrant())
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val authority = ScriptedReauthenticationAuthority(F.activeSnapshot())
        val wrapping = object : SessionVault {
            override suspend fun load(): VaultSnapshot = delegate.load()
            override suspend fun compareAndSet(
                expectedRevision: Long,
                record: VaultSessionRecord?,
            ): VaultSnapshot {
                delegate.compareAndSet(expectedRevision, record)
                return delegate.compareAndSet(expectedRevision + 1, record)
            }
        }
        val origin = F.origin()
        val reauth = F.coordinator(
            origin = origin,
            authority = authority,
            vault = wrapping,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        val refresh = SessionRefreshCoordinator(
            origin = origin,
            vault = wrapping,
            authApi = auth,
            clockMillis = { throw AssertionError("R09f replay must not read the clock") },
            uuidProvider = { throw AssertionError("R09f replay must not mint UUIDs") },
            mutex = credentialMutex,
        )
        F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            reauth.reconcilePendingRefresh(F.activeSnapshot(), refresh)
        }
        assertEquals(1, auth.refreshCalls)
        assertEquals(2, delegate.commits)
        // The double-committed bytes stand exactly as written (grant
        // installed, pending cleared) with the nonadjacent durable revision;
        // nothing is compensated, reinstalled, or retried.
        assertEquals(7L, delegate.current().revision)
        assertEquals(F.newGrant(), delegate.current().record?.session)
        assertNull(delegate.current().record?.pendingRefresh)
        assertEquals(F.activeSnapshot(), authority.durableSnapshot())
        assertEquals(0, authority.begins)
        assertEquals(0, authority.finishes)
    }

    @Test
    fun queuedCredentialHasNoEffectsAndAuthorityFaultMapsClosed() = runTest {
        val s = setupPair(vaultRecord = F.activeRecord(pendingRefresh = null))
        s.credentialMutex.lock()
        try {
            supervisorScope {
                val done = CompletableDeferred<Throwable>()
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
                        done.complete(AssertionError("expected cancellation while queued"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                runCurrent()
                runCurrent()
                assertFalse("queued invocation must not complete while credential mutex held", job.isCompleted)
                assertEquals(0, s.authority.reads)
                assertEquals(0, s.vault.loads)
                assertEquals(0, s.auth.refreshCalls)
                assertEquals(0, s.vault.commits)
                val probe = SessionReauthenticationTestFixtures.ProbeCancellationException("queued-credential-r09f-cancel")
                job.cancel(probe)
                assertSame(probe, done.await())
                job.join()
                assertEquals(F.activeSnapshot(), s.authority.durableSnapshot())
            }
        } finally {
            s.credentialMutex.unlock()
        }
        // Strict Room UNAVAILABLE maps through the existing R09e mapping to
        // AUTHORITY_UNAVAILABLE: closed surface, no HTTP/CAS. Error-path
        // old-Room checks still run under BOTH owners: the read gate asserts
        // the shared DB owner and credential mutex are both held.
        var errorPathLocksHeld = false
        s.authority.readGate = {
            errorPathLocksHeld = s.runOwner.mutex.isLocked && s.credentialMutex.isLocked
        }
        s.authority.readFaults += RoomSessionAuthorityException(RoomSessionAuthorityFailure.UNAVAILABLE)
        val e = F.assertReauthFailure(SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE) {
            s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
        }
        F.assertClosed(e, F.defaultSecrets())
        assertTrue("error-path Room check must run under both owners", errorPathLocksHeld)
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
        // One-shot CORRUPT_AUTHORITY at a required authority check remains
        // AUTHORITY_CORRUPT even though the next reread is good again: no
        // matrix, just the representative corrupt alongside unavailable.
        s.authority.readGate = null
        s.authority.readFaults += RoomSessionAuthorityException(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY)
        val corrupt = F.assertReauthFailure(SessionReauthenticationFailure.AUTHORITY_CORRUPT) {
            s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
        }
        F.assertClosed(corrupt, F.defaultSecrets())
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
        // The next reread is good again, yet the surfaced category stands:
        // a following valid call still reconciles read-only on its own.
        val after = s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
        assertEquals(F.activeSnapshot(), after.room)
        assertEquals(5L, after.vaultRevision)
    }

    // ---- Newest/invalidate/caller cancellation + shared ownership ----

    @Test
    fun queuedValidUnaffectedByInvalidReleaseReadmitsToSuccess() = runTest {
        // A valid invocation queued on the shared owner has no effects; an
        // invalid input and an incompatible refresh coordinator fail fast on
        // their own WITHOUT retiring the queued valid work, which succeeds
        // once the owner is released: the supersession oracle is the later
        // success, not a cancellation.
        val s = setupPair(vaultRecord = F.activeRecord(pendingRefresh = null))
        s.runOwner.mutex.lock()
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
            }
            runCurrent()
            runCurrent()
            assertFalse("queued invocation must not complete while owner held", job.isCompleted)
            assertEquals(0, s.authority.reads)
            assertEquals(0, s.vault.loads)
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
                s.reauth.reconcilePendingRefresh(F.transitioningSnapshot(), s.refresh)
            }
            val alien = SessionRefreshCoordinator(
                origin = F.origin(),
                vault = F.vaultWith(record = F.activeRecord(), revision = 5L),
                authApi = s.auth,
                clockMillis = { throw AssertionError("no clock") },
                uuidProvider = { throw AssertionError("no uuid") },
                mutex = s.credentialMutex,
            )
            F.assertReauthFailure(SessionReauthenticationFailure.INVALID_INPUT) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), alien)
            }
            assertFalse("invalid calls must not retire the queued valid work", job.isCompleted)
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.vault.commits)
            // Release readmits: the undisturbed valid invocation now succeeds.
            s.runOwner.mutex.unlock()
            val observation = job.await()
            assertEquals(F.activeSnapshot(), observation.room)
            assertEquals(5L, observation.vaultRevision)
        }
        assertFalse(s.runOwner.mutex.isLocked)
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
    }

    @Test
    fun newestValidInvocationRetiresDelayedOlderWithoutEffects() = runTest {
        // A newest VALID invocation retires an older delayed invocation: the
        // older reports STALE_OPERATION once released, the newer succeeds,
        // and neither performs HTTP/CAS/reservation on this settled pair.
        val s = setupPair(vaultRecord = F.activeRecord(pendingRefresh = null))
        val releaseFirst = CompletableDeferred<Unit>()
        s.authority.readGate = { callIndex ->
            if (callIndex == 1) releaseFirst.await()
        }
        supervisorScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
                    fail("expected older invocation to retire")
                } catch (e: SessionReauthenticationException) {
                    e
                }
            }
            // UNDISPATCHED runs first to its read seam before second opens
            // its newer fence, so retirement is deterministic.
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
            }
            releaseFirst.complete(Unit)
            val firstOutcome = first.await()
            assertEquals(
                SessionReauthenticationFailure.STALE_OPERATION,
                (firstOutcome as SessionReauthenticationException).failure,
            )
            val observation = second.await()
            assertEquals(F.activeSnapshot(), observation.room)
            assertEquals(5L, observation.vaultRevision)
        }
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
    }

    @Test
    fun invalidateDuringHttpFencesLateResultWithoutCasThenRetrySucceeds() = runTest {
        val s = pendingSetup()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        s.auth.refreshGate = {
            entered.complete(Unit)
            release.await()
        }
        s.auth.answer(F.newGrant())
        s.auth.answer(F.newGrant())
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
                    fail("expected STALE_OPERATION")
                } catch (e: SessionReauthenticationException) {
                    e
                }
            }
            entered.await()
            s.reauth.invalidate()
            release.complete(Unit)
            val outcome = job.await()
            assertEquals(
                SessionReauthenticationFailure.STALE_OPERATION,
                (outcome as SessionReauthenticationException).failure,
            )
        }
        // HTTP was admitted once, but the fenced late result committed nothing.
        assertEquals(1, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
        assertEquals(
            " { \"refreshToken\" : \"refresh-old\" } ",
            s.vault.current().record?.pendingRefresh?.attempt?.requestJson,
        )
        // Retry after invalidation uses the exact retained attempt and lands.
        val observation = s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
        assertEquals(6L, observation.vaultRevision)
        assertEquals(2, s.auth.refreshCalls)
        assertNull(s.vault.current().record?.pendingRefresh)
    }

    @Test
    fun callerCancellationDuringHttpEscapesIdenticallyWithNoCas() = runTest {
        val s = pendingSetup()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        s.auth.refreshGate = {
            entered.complete(Unit)
            release.await()
        }
        s.auth.answer(F.newGrant())
        supervisorScope {
            val done = CompletableDeferred<Throwable>()
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
                    done.complete(AssertionError("expected cancellation during HTTP"))
                } catch (e: Throwable) {
                    done.complete(e)
                }
            }
            entered.await()
            val probe = SessionReauthenticationTestFixtures.ProbeCancellationException("http-r09f-cancel")
            job.cancel(probe)
            release.complete(Unit)
            assertSame(probe, done.await())
            job.join()
        }
        assertEquals(1, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
        assertFalse(s.credentialMutex.isLocked)
        assertFalse(s.runOwner.mutex.isLocked)
    }

    @Test
    fun sharedCredentialMutexGatedReconcileBlocksOrdinaryRefreshUntilRelease() = runTest {
        // Real contention, not synchronous completion: the reconcile holds the
        // shared credential mutex across a gated preflight read, an ordinary
        // shared refresh selection on a matching lease blocks on that mutex,
        // and both complete once released with no deadlock or writes.
        // accessToken legitimately reads the clock for its live-access
        // selection, so only this case supplies a real clock (UUID still
        // throws: replay mints nothing here).
        val live = F.session(accessValue = "access-live")
        val s = setupPair(
            vaultRecord = F.activeRecord(session = live, pendingRefresh = null),
            clock = { F.NOW },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gated = true
        s.authority.readGate = {
            if (gated) {
                gated = false
                entered.complete(Unit)
                release.await()
            }
        }
        val lease = SessionRefreshLease(
            binding = F.binding(generation = 7L),
            operationId = F.OP_OLD,
            phase = VaultSessionPhase.ACTIVE,
        )
        supervisorScope {
            val reconciled = async(start = CoroutineStart.UNDISPATCHED) {
                s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
            }
            entered.await()
            val token = async(start = CoroutineStart.UNDISPATCHED) {
                s.refresh.accessToken(lease)
            }
            runCurrent()
            runCurrent()
            assertFalse("ordinary selection must block on the held credential mutex", token.isCompleted)
            assertFalse("gated reconcile must still hold the mutex", reconciled.isCompleted)
            release.complete(Unit)
            val observation = reconciled.await()
            val credential = token.await()
            assertEquals(F.activeSnapshot(), observation.room)
            assertEquals(5L, observation.vaultRevision)
            assertEquals("access-live", credential.token.value)
        }
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(0, s.vault.commits)
        assertUntouchedRoomAndNoReservation(s, F.activeSnapshot())
        assertFalse(s.credentialMutex.isLocked)
        assertFalse(s.runOwner.mutex.isLocked)
    }

    /**
     * Delegating vault restoring a DISTINCT decoded attempt instance on
     * EVERY read with byte-identical content, mirroring vault decode
     * behavior: separate instances compare by content, never reference.
     */
    private class FreshAttemptEachReadVault(
        private val delegate: FakeSessionVault,
    ) : SessionVault {
        override suspend fun load(): VaultSnapshot {
            val snap = delegate.load()
            val record = snap.record ?: return snap
            val pending = record.pendingRefresh ?: return snap
            val fresh = VaultPendingRefresh(
                operationId = pending.operationId,
                attempt = ServiceRefreshAttempt.fromJson(
                    pending.attempt.idempotencyKey,
                    pending.attempt.requestJson,
                ),
            )
            return VaultSnapshot(revision = snap.revision, record = record.copy(pendingRefresh = fresh))
        }

        override suspend fun compareAndSet(
            expectedRevision: Long,
            record: VaultSessionRecord?,
        ): VaultSnapshot = delegate.compareAndSet(expectedRevision, record)
    }

    @Test
    fun semanticRereadsAcrossDistinctRestoredAttemptsSucceedExactly() = runTest {
        // The SAME wrapper is referentially supplied to both coordinators;
        // each reread restores a distinct attempt instance, and the exact
        // replay still succeeds with byte-identical content.
        val delegate = F.vaultWith(
            record = F.activeRecord(
                session = F.session(refreshValue = "refresh-old"),
                pendingRefresh = F.whitespacePending(),
            ),
            revision = 5L,
        )
        val wrapping = FreshAttemptEachReadVault(delegate)
        val first = wrapping.load().record?.pendingRefresh?.attempt
        val second = wrapping.load().record?.pendingRefresh?.attempt
        assertTrue("each reread must restore a distinct instance", checkNotNull(first) !== checkNotNull(second))
        assertEquals(first.requestJson, second.requestJson)
        assertEquals(first.idempotencyKey, second.idempotencyKey)
        val auth = FakeServiceAuthApi()
        auth.answer(F.newGrant())
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val w = setupOnVault(wrapping, auth = auth, runOwner = runOwner, credentialMutex = credentialMutex)
        val observation = w.reauth.reconcilePendingRefresh(F.activeSnapshot(), w.refresh)
        assertEquals(1, auth.refreshCalls)
        val sent = auth.seenAttempts.single()
        assertEquals(F.KEY_A, sent.idempotencyKey)
        assertEquals(" { \"refreshToken\" : \"refresh-old\" } ", sent.requestJson)
        assertEquals("refresh-old", sent.refreshToken.value)
        assertEquals(6L, observation.vaultRevision)
        assertNull(delegate.current().record?.pendingRefresh)
        assertEquals(F.newGrant(), delegate.current().record?.session)
    }

    @Test
    fun closedLocalFailuresNeverEchoSecrets() = runTest {
        val s = setupPair(vaultRecord = null)
        val e = F.assertReauthFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            s.reauth.reconcilePendingRefresh(F.activeSnapshot(), s.refresh)
        }
        F.assertClosed(e, F.defaultSecrets("refresh-old", "access-live"))
    }
}
