package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * prepared vault/Room coordination.
 *
 * Each test proves a distinct new defect; representative refusal cases are grouped
 * without an exhaustive matrix. Safety obligations (redaction, closed causes,
 * no-write proofs, cancellation identity, newest/invalidate fencing) are asserted
 * where the defect lives rather than repeated per case.
 */
class SessionPreparationCoordinatorTest {
    private val F = SessionPreparationTestFixtures

    private companion object {
        /** Lettered canonical UUID: its uppercase spelling is non-canonical. */
        const val LETTER_OP = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    }

    private fun setup(
        authoritySnapshot: RecoveryRoomSnapshot = F.signedOut(),
        vaultRecord: VaultSessionRecord? = null,
        vaultRevision: Long = 0L,
        root: String = F.ORIGIN_ROOT,
    ): Setup {
        val authority = ScriptedPreparationAuthority(authoritySnapshot)
        val vault = F.vaultWith(record = vaultRecord, revision = vaultRevision)
        val coordinator = F.coordinator(
            origin = F.origin(root),
            authority = authority,
            vault = vault,
        )
        return Setup(coordinator, authority, vault)
    }

    private data class Setup(
        val coordinator: SessionPreparationCoordinator,
        val authority: ScriptedPreparationAuthority,
        val vault: FakeSessionVault,
    )

    private fun freshCoordinator(s: Setup, root: String = F.ORIGIN_ROOT): SessionPreparationCoordinator =
        F.coordinator(origin = F.origin(root), authority = s.authority, vault = s.vault)

    // ---- Obligation 1: happy preparation, tombstone CAS, resume idempotence ----

    @Test
    fun prepareHappyPathWritesPreparedWithExactPlusOneAndStableObservation() = runTest {
        val s = setup()
        val expected = F.signedOut()
        val grant = F.session()
        val observation = s.coordinator.prepare(expected, F.OP_A, grant)

        val preparing = F.preparing(revision = 1L, generation = 1L)
        assertEquals(preparing, observation.room)
        assertEquals(1L, observation.vaultRevision)
        assertFalse(observation.room.baselineComplete)

        // Vault holds exactly one PREPARED record with the entire grant unchanged.
        val stored = s.vault.current()
        assertEquals(1L, stored.revision)
        val record = checkNotNull(stored.record)
        assertEquals(VaultSessionPhase.PREPARED, record.phase)
        assertEquals(preparing.identity, RecoveryIdentity(record.binding, record.operationId))
        assertEquals(grant, record.session)
        assertNull(record.pendingRefresh)

        // Stable nonsecret result: fixed/redacted displays.
        F.assertRedacted(
            observation.toString(),
            F.defaultSecrets("access-1", "refresh-1", F.ORIGIN_ROOT),
        )
        assertEquals(1, s.authority.begins)
    }

    @Test
    fun prepareTombstoneRevisionWritesAtCheckedEmptyRevision() = runTest {
        // Positive tombstone is an allowed empty base: CAS at its exact revision.
        val s = setup(vaultRecord = null, vaultRevision = 7L)
        val observation = s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
        assertEquals(8L, observation.vaultRevision)
        assertEquals(F.preparing(), observation.room)
        val stored = s.vault.current()
        assertEquals(8L, stored.revision)
        assertEquals(VaultSessionPhase.PREPARED, stored.record?.phase)
    }

    @Test
    fun resumeMissingGrantUsesReservedOperationWithoutNewReservation() = runTest {
        val s = setup()
        val expectedSignedOut = F.signedOut()
        // Reserve through the authority double, then simulate reservation-only crash:
        // durable Room stays PREPARING while the vault view is still empty.
        val grant = F.session()
        val reserved = s.authority.beginPreparation(expectedSignedOut, F.origin(), F.ACCOUNT_ID, F.OP_A)
        assertEquals(F.preparing(), reserved)
        val beginsAfterReserve = s.authority.begins

        // Fresh coordinator over the same pair: recover recognizes NeedsGrant.
        val fresh = freshCoordinator(s)
        val recovery = fresh.recover()
        assertTrue(recovery is SessionPreparationRecovery.NeedsGrant)
        assertEquals(reserved, (recovery as SessionPreparationRecovery.NeedsGrant).room)

        // Resume supplies the external grant under the SAME reserved operation.
        val observation = fresh.resume(reserved, grant)
        assertEquals(reserved, observation.room)
        assertEquals(1L, observation.vaultRevision)
        val stored = s.vault.current()
        assertEquals(grant, stored.record?.session)
        assertEquals(F.OP_A, stored.record?.operationId)
        // No new Room reservation happened during resume.
        assertEquals(beginsAfterReserve, s.authority.begins)
        assertEquals(reserved, s.authority.durableSnapshot())
    }

    @Test
    fun resumeMatchingPreparedPerformsNoCasAndPreservesPending() = runTest {
        val preparing = F.preparing()
        val pending = F.whitespacePending()
        val grant = F.session(refreshValue = "refresh-1")
        val record = F.preparedRecord(session = grant, pendingRefresh = pending)
        val s = setup(authoritySnapshot = preparing, vaultRecord = record, vaultRevision = 3L)
        val commitsBefore = s.vault.commits

        val observation = s.coordinator.resume(preparing, grant)
        assertEquals(preparing, observation.room)
        assertEquals(3L, observation.vaultRevision)
        // Read-only idempotence: no CAS, pending bytes preserved exactly.
        assertEquals(commitsBefore, s.vault.commits)
        val stored = s.vault.current()
        assertEquals(3L, stored.revision)
        F.assertSemanticPendingEqual(pending, stored.record?.pendingRefresh)
        assertEquals(pending.attempt.requestJson, stored.record?.pendingRefresh?.attempt?.requestJson)
        assertEquals(0, s.authority.begins)
    }

    // ---- Obligation 2: crash / uncertain seams ----

    @Test
    fun reservationCommitThenThrowStartsNoVaultWriteAndRecoverNeedsGrant() = runTest {
        val s = setup()
        s.authority.commitBeforeBeginError = true
        s.authority.pendingBeginError =
            RoomSessionAuthorityException(RoomSessionAuthorityFailure.UNAVAILABLE)
        try {
            s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
            fail("expected AUTHORITY_UNAVAILABLE")
        } catch (e: SessionPreparationException) {
            assertEquals(SessionPreparationFailure.AUTHORITY_UNAVAILABLE, e.failure)
            F.assertClosed(e, F.defaultSecrets())
        }
        // Durable Room advanced; vault untouched.
        assertEquals(F.preparing(), s.authority.durableSnapshot())
        assertEquals(0, s.vault.commits)
        assertEquals(0L, s.vault.current().revision)

        // Fresh reread is the only recovery path.
        s.authority.commitBeforeBeginError = false
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionPreparationRecovery.NeedsGrant)
        assertEquals(F.preparing(), (recovery as SessionPreparationRecovery.NeedsGrant).room)
    }

    @Test
    fun postcommitReservationCancellationNeedsGrantWithoutVaultWrite() = runTest {
        val s = setup()
        val injected = CancellationException("postcommit-reserve-cancel-preparation")
        s.authority.commitBeforeBeginError = true
        s.authority.pendingBeginError = injected
        try {
            s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
            fail("expected injected cancellation identity")
        } catch (e: CancellationException) {
            assertSame(injected, e)
        }
        // Reservation stood (private), but no credential was written.
        assertEquals(F.preparing(), s.authority.durableSnapshot())
        assertEquals(0, s.vault.commits)
        assertEquals(0L, s.vault.current().revision)

        s.authority.commitBeforeBeginError = false
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionPreparationRecovery.NeedsGrant)
        assertEquals(F.preparing(), (recovery as SessionPreparationRecovery.NeedsGrant).room)
    }

    @Test
    fun reservationCancellationIdentityEscapesWithoutVaultWrite() = runTest {
        val s = setup()
        val injected = CancellationException("reserve-cancel-preparation")
        s.authority.beginFaults += injected
        try {
            s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
            fail("expected injected cancellation identity")
        } catch (e: CancellationException) {
            assertSame(injected, e)
        }
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun vaultCommitThenThrowHasNoCompensatingWriteAndRecoverPrepared() = runTest {
        val s = setup()
        val grant = F.session()
        // Post-commit seam failure: preflight load succeeds, first stable reread
        // pair fails after the CAS stood.
        var loads = 0
        s.vault.loadGate = {
            loads += 1
            if (loads in 2..3) throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        try {
            s.coordinator.prepare(F.signedOut(), F.OP_A, grant)
            fail("expected VAULT_UNAVAILABLE")
        } catch (e: SessionPreparationException) {
            assertEquals(SessionPreparationFailure.VAULT_UNAVAILABLE, e.failure)
        }
        // Exactly one commit stood; no compensating CAS/retry/cleanup.
        assertEquals(listOf(1L), s.vault.committedRevisions)
        assertEquals(1L, s.vault.current().revision)

        s.vault.loadGate = null
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionPreparationRecovery.Prepared)
        val observation = (recovery as SessionPreparationRecovery.Prepared).observation
        assertEquals(F.preparing(), observation.room)
        assertEquals(1L, observation.vaultRevision)
    }

    @Test
    fun vaultCommitThenCancelEscapesIdenticallyWithFreshPreparedRecovery() = runTest {
        val s = setup()
        val grant = F.session()
        val injected = CancellationException("postcommit-vault-cancel-preparation")
        var loads = 0
        s.vault.loadGate = {
            loads += 1
            if (loads in 2..3) throw injected
        }
        try {
            s.coordinator.prepare(F.signedOut(), F.OP_A, grant)
            fail("expected injected cancellation identity")
        } catch (e: CancellationException) {
            assertSame(injected, e)
        }
        assertEquals(listOf(1L), s.vault.committedRevisions)

        s.vault.loadGate = null
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionPreparationRecovery.Prepared)
        assertEquals(grant, s.vault.current().record?.session)
    }

    @Test
    fun realVaultRecoverAcrossDecodeRereadsPreservesSemanticPendingAndExpiries() = runTest {
        val storage = InMemoryVaultFileStorage()
        val vault = realVault(storage)
        val body = " { \"refreshToken\" : \"refresh-1\" } "
        val attempt = ServiceRefreshAttempt.fromJson(F.KEY_A, body)
        val grant = F.session(
            accessExpiresAtMillis = VaultTestFixtures.MIN_WIRE_MILLIS,
            refreshExpiresAtMillis = VaultTestFixtures.MAX_WIRE_MILLIS,
        )
        val record = VaultSessionRecord(
            binding = F.binding(),
            operationId = F.OP_A,
            phase = VaultSessionPhase.PREPARED,
            session = grant,
            pendingRefresh = VaultPendingRefresh(operationId = F.OP_A, attempt = attempt),
        )
        assertEquals(1L, vault.compareAndSet(0L, record).revision)

        // Coordinator recovery across real decode rereads, twice for stability.
        val authority = ScriptedPreparationAuthority(F.preparing())
        val coordinator = F.coordinator(authority = authority, vault = vault)
        repeat(2) {
            val recovery = coordinator.recover()
            assertTrue("iteration $it", recovery is SessionPreparationRecovery.Prepared)
            val observation = (recovery as SessionPreparationRecovery.Prepared).observation
            assertEquals(F.preparing(), observation.room)
            assertEquals(1L, observation.vaultRevision)
        }
        // Independently restored attempt compares by exact fields, not reference;
        // expiries/bytes preserved exactly through the real codec.
        val reloaded = vault.load()
        val back = checkNotNull(reloaded.record)
        assertEquals(F.binding(), back.binding)
        assertEquals(F.OP_A, back.operationId)
        assertEquals(grant, back.session)
        assertEquals(VaultTestFixtures.MIN_WIRE_MILLIS, back.session.accessExpiresAtMillis)
        assertEquals(VaultTestFixtures.MAX_WIRE_MILLIS, back.session.refreshExpiresAtMillis)
        val reattempt = checkNotNull(back.pendingRefresh).attempt
        assertEquals(F.KEY_A, reattempt.idempotencyKey)
        assertEquals(body, reattempt.requestJson)
        assertEquals("refresh-1", reattempt.refreshToken.value)
        assertTrue(reattempt !== attempt)
    }

    // ---- Obligation 3: representative refusal / no-write cases ----

    @Test
    fun wrongExpectedPhaseAndBadOperationRefusedBeforeIo() = runTest {
        val s = setup(authoritySnapshot = F.preparing())
        // Wrong phase => INVALID_INPUT before any store access.
        F.assertPreparationFailure(SessionPreparationFailure.INVALID_INPUT) {
            s.coordinator.prepare(F.preparing(), F.OP_A, F.session())
        }
        assertEquals(0, s.authority.reads)
        assertEquals(0, s.vault.loads)
        assertEquals(0, s.vault.commits)

        val s2 = setup()
        // OP_A is digits-only so its uppercase spelling stays valid; the lettered
        // canonical UUID below is the true non-canonical uppercase refusal.
        val letterUpper = LETTER_OP.uppercase()
        for (bad in listOf("not-a-uuid", letterUpper, "", "11111111-1111-1111-1111-11111111111")) {
            F.assertPreparationFailure(SessionPreparationFailure.INVALID_INPUT) {
                s2.coordinator.prepare(F.signedOut(), bad, F.session())
            }
        }
        assertEquals(0, s2.authority.reads)
        assertEquals(0, s2.vault.loads)
        assertEquals(0, s2.authority.begins)

        // The lowercase lettered spelling itself is canonical and accepted.
        val s3 = setup()
        val accepted = s3.coordinator.prepare(F.signedOut(), LETTER_OP, F.session())
        assertEquals(LETTER_OP, accepted.room.identity?.operationId)
    }

    @Test
    fun occupiedVaultRefusedWithoutWrites() = runTest {
        val variants = listOf(
            // Same-account prepared orphan with a different operation.
            F.preparedRecord(operationId = F.OP_B),
            // Active credentials for the same account.
            VaultSessionRecord(
                binding = F.binding(),
                operationId = F.OP_A,
                phase = VaultSessionPhase.ACTIVE,
                session = F.session(),
                pendingRefresh = null,
            ),
            // Foreign-account orphan with a retained pending attempt.
            F.preparedRecord(
                binding = F.binding(accountId = F.OTHER_ACCOUNT_ID),
                operationId = F.OP_B,
                session = F.session(accountId = F.OTHER_ACCOUNT_ID),
                pendingRefresh = F.pending(operationId = F.OP_B, key = F.KEY_B),
            ),
        )
        for (record in variants) {
            val s = setup(vaultRecord = record, vaultRevision = 4L)
            val before = s.vault.current()
            F.assertPreparationFailure(SessionPreparationFailure.VAULT_OCCUPIED) {
                s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
            }
            assertEquals(before, s.vault.current())
            assertEquals(0, s.vault.commits)
            assertEquals(F.signedOut(), s.authority.durableSnapshot())
            assertEquals(0, s.authority.begins)
        }
    }

    @Test
    fun staleAuthorityAndStaleCasMapClosedWithoutWrites() = runTest {
        // Wrong full expected snapshot => STALE_AUTHORITY.
        val s = setup(authoritySnapshot = F.preparing())
        F.assertPreparationFailure(SessionPreparationFailure.STALE_AUTHORITY) {
            s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
        }
        assertEquals(0, s.vault.commits)

        // Stale vault CAS (CONFLICT) => IDENTITY_MISMATCH, no success.
        val s2 = setup()
        s2.vault.casFailures += SessionVaultException(VaultFailure.CONFLICT)
        F.assertPreparationFailure(SessionPreparationFailure.IDENTITY_MISMATCH) {
            s2.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
        }
        assertEquals(0L, s2.vault.current().revision)

        // Tombstone conflict variant maps identically.
        val s3 = setup(vaultRecord = null, vaultRevision = 5L)
        s3.vault.casFailures += SessionVaultException(VaultFailure.CONFLICT)
        F.assertPreparationFailure(SessionPreparationFailure.IDENTITY_MISMATCH) {
            s3.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
        }
        assertEquals(5L, s3.vault.current().revision)
        assertNull(s3.vault.current().record)
    }

    @Test
    fun prepareChangedRoomWhileLoadingEmptyVaultRefusesBeforeCas() = runTest {
        val s = setup()
        var loads = 0
        s.vault.loadGate = {
            loads += 1
            // Durable Room changes while the empty vault view loads: the pre-write
            // authority recheck must refuse before any credential CAS.
            if (loads == 1) s.authority.seedDurable(F.signedOut(revision = 5L))
        }
        F.assertPreparationFailure(SessionPreparationFailure.STALE_AUTHORITY) {
            s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
        }
        assertEquals(0, s.vault.commits)
        assertEquals(0L, s.vault.current().revision)
        assertNull(s.vault.current().record)
    }

    @Test
    fun prepareChangedRoomAfterReservationRefusesBeforeCas() = runTest {
        val s = setup()
        // A concurrent writer advances durable Room immediately after this
        // invocation's own reservation commits and before its vault CAS.
        s.authority.beginBehavior = { args ->
            if (args.expected != s.authority.durableSnapshot()) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            val reserved = RecoveryRoomSnapshot(
                revision = args.expected.revision + 1,
                sessionGeneration = args.expected.sessionGeneration + 1,
                cachedAccountId = args.accountId,
                phase = RecoveryRoomPhase.PREPARING,
                identity = RecoveryIdentity(
                    binding = ServiceSessionBinding(
                        origin = args.origin,
                        accountId = args.accountId,
                        sessionGeneration = args.expected.sessionGeneration + 1,
                    ),
                    operationId = args.operationId,
                ),
                baselineComplete = false,
            )
            s.authority.seedDurable(reserved)
            s.authority.seedDurable(F.preparing(revision = 2L, generation = 2L))
            reserved
        }
        F.assertPreparationFailure(SessionPreparationFailure.STALE_AUTHORITY) {
            s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
        }
        assertEquals(0, s.vault.commits)
        assertEquals(0L, s.vault.current().revision)
        assertNull(s.vault.current().record)
    }

    @Test
    fun resumeChangedRoomWhileLoadingEmptyVaultRefusesBeforeCas() = runTest {
        val preparing = F.preparing()
        val s = setup(authoritySnapshot = preparing)
        var loads = 0
        s.vault.loadGate = {
            loads += 1
            // Reservation pre-exists; Room changes while resume loads the empty
            // vault view, so the pre-write recheck must refuse before CAS.
            if (loads == 1) s.authority.seedDurable(F.preparing(revision = 2L, generation = 2L))
        }
        F.assertPreparationFailure(SessionPreparationFailure.STALE_AUTHORITY) {
            s.coordinator.resume(preparing, F.session())
        }
        assertEquals(0, s.vault.commits)
        assertEquals(0L, s.vault.current().revision)
        assertNull(s.vault.current().record)
    }

    @Test
    fun resumeMismatchCasesRefuseUnchanged() = runTest {
        val preparing = F.preparing()
        val grant = F.session()
        // Wrong configured origin and wrong grant account => IDENTITY_MISMATCH.
        val wrongOrigin = setup(authoritySnapshot = preparing)
        val otherOriginCoordinator = F.coordinator(
            origin = F.origin(F.OTHER_ORIGIN_ROOT),
            authority = wrongOrigin.authority,
            vault = wrongOrigin.vault,
        )
        F.assertPreparationFailure(SessionPreparationFailure.IDENTITY_MISMATCH) {
            otherOriginCoordinator.resume(preparing, grant)
        }
        F.assertPreparationFailure(SessionPreparationFailure.IDENTITY_MISMATCH) {
            wrongOrigin.coordinator.resume(preparing, F.session(accountId = F.OTHER_ACCOUNT_ID))
        }
        // Different operation / vault phase all refuse.
        val occupied = F.preparedRecord(operationId = F.OP_B)
        val s = setup(authoritySnapshot = preparing, vaultRecord = occupied, vaultRevision = 2L)
        F.assertPreparationFailure(SessionPreparationFailure.IDENTITY_MISMATCH) {
            s.coordinator.resume(preparing, grant)
        }
        assertEquals(occupied, s.vault.current().record)
        assertEquals(0, s.vault.commits)

        val activeRecord = VaultSessionRecord(
            binding = F.binding(),
            operationId = F.OP_A,
            phase = VaultSessionPhase.ACTIVE,
            session = grant,
            pendingRefresh = null,
        )
        val sActive = setup(authoritySnapshot = preparing, vaultRecord = activeRecord, vaultRevision = 2L)
        F.assertPreparationFailure(SessionPreparationFailure.IDENTITY_MISMATCH) {
            sActive.coordinator.resume(preparing, grant)
        }
        // Resume never changes Room.
        assertEquals(preparing, sActive.authority.durableSnapshot())
    }

    @Test
    fun closedAuthorityCategoriesPropagateWithoutWrap() = runTest {
        val mapping = mapOf(
            RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION to
                SessionPreparationFailure.LOCAL_DATA_REQUIRES_DECISION,
            RoomSessionAuthorityFailure.ACCOUNT_MISMATCH to
                SessionPreparationFailure.ACCOUNT_MISMATCH,
            RoomSessionAuthorityFailure.STALE_AUTHORITY to
                SessionPreparationFailure.STALE_AUTHORITY,
            RoomSessionAuthorityFailure.COUNTER_OVERFLOW to
                SessionPreparationFailure.COUNTER_OVERFLOW,
        )
        for ((roomFailure, expected) in mapping) {
            val s = setup()
            s.authority.beginFaults += RoomSessionAuthorityException(roomFailure)
            F.assertPreparationFailure(expected) {
                s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
            }
            assertEquals(0, s.vault.commits)
        }
        // Corrupt/unavailable collapse to AUTHORITY_UNAVAILABLE.
        for (roomFailure in listOf(
            RoomSessionAuthorityFailure.CORRUPT_AUTHORITY,
            RoomSessionAuthorityFailure.UNAVAILABLE,
        )) {
            val s = setup()
            s.authority.beginFaults += RoomSessionAuthorityException(roomFailure)
            F.assertPreparationFailure(SessionPreparationFailure.AUTHORITY_UNAVAILABLE) {
                s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
            }
        }
    }

    @Test
    fun counterOverflowRefusedWithoutWrap() = runTest {
        // Room at max revision/generation.
        val maxed = F.signedOut(revision = Long.MAX_VALUE)
        val s = setup(authoritySnapshot = maxed)
        F.assertPreparationFailure(SessionPreparationFailure.COUNTER_OVERFLOW) {
            s.coordinator.prepare(maxed, F.OP_A, F.session())
        }
        assertEquals(0, s.vault.commits)
        // Vault exhaustion maps identically.
        val s2 = setup()
        s2.vault.casFailures += SessionVaultException(VaultFailure.REVISION_EXHAUSTED)
        F.assertPreparationFailure(SessionPreparationFailure.COUNTER_OVERFLOW) {
            s2.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
        }
    }

    // ---- Obligation 4: stable rereads, closed faults, recover gates ----

    @Test
    fun stableRoomRereadDetectsChangeDuringPreparation() = runTest {
        val s = setup()
        val grant = F.session()
        var roomReads = 0
        s.authority.readGate = {
            roomReads += 1
            if (roomReads == 4) s.authority.readOverrides += F.preparing(revision = 2L, generation = 2L)
        }
        F.assertPreparationFailure(SessionPreparationFailure.STALE_AUTHORITY) {
            s.coordinator.prepare(F.signedOut(), F.OP_A, grant)
        }
    }

    @Test
    fun stableVaultRereadDetectsChangedGrantPendingAndRevision() = runTest {
        val s = setup()
        val grant = F.session()
        var loads = 0
        s.vault.loadGate = {
            loads += 1
            if (loads == 2) {
                // An external replacement lands between the coordinator's stable
                // rereads: new revision with a changed full grant + pending body.
                // loadGate runs outside the vault lock, so a direct CAS is safe.
                val current = s.vault.current()
                val mutatedGrant = F.session(refreshValue = "refresh-MUTATED")
                val mutated = F.preparedRecord(
                    binding = checkNotNull(current.record).binding,
                    operationId = F.OP_A,
                    session = mutatedGrant,
                    pendingRefresh = F.pending(
                        operationId = F.OP_A,
                        key = F.KEY_A,
                        refreshValue = "refresh-MUTATED",
                    ),
                )
                s.vault.compareAndSet(current.revision, mutated)
            }
        }
        F.assertPreparationFailure(SessionPreparationFailure.IDENTITY_MISMATCH) {
            s.coordinator.prepare(F.signedOut(), F.OP_A, grant)
        }
        // Coordinator's rev1 commit plus the external rev2 replacement both stand;
        // no prepared success was returned from the racing invocation.
        assertEquals(listOf(1L, 2L), s.vault.committedRevisions)
        assertEquals("refresh-MUTATED", s.vault.current().record?.session?.refreshToken?.value)
        assertEquals(
            "refresh-MUTATED",
            s.vault.current().record?.pendingRefresh?.attempt?.refreshToken?.value,
        )
    }

    @Test
    fun unreadableVaultResumeStaysClosedAndRecoverMapsBlocked() = runTest {
        val s = setup(authoritySnapshot = F.preparing())
        s.vault.loadFailures += SessionVaultException(VaultFailure.UNREADABLE)
        try {
            s.coordinator.resume(F.preparing(), F.session())
            fail("expected closed vault fault")
        } catch (e: SessionPreparationException) {
            assertEquals(SessionPreparationFailure.VAULT_UNAVAILABLE, e.failure)
            F.assertClosed(e, F.defaultSecrets("access-1", "refresh-1"))
        }
        // Recover maps an unreadable authority read to Blocked, not a throw.
        val s2 = setup()
        s2.authority.readFaults += RoomSessionAuthorityException(RoomSessionAuthorityFailure.UNAVAILABLE)
        val blocked = s2.coordinator.recover()
        assertTrue(blocked is SessionPreparationRecovery.Blocked)
        assertEquals(
            SessionPreparationFailure.AUTHORITY_UNAVAILABLE,
            (blocked as SessionPreparationRecovery.Blocked).failure,
        )
        F.assertRedacted(blocked.toString(), F.defaultSecrets())
        // Recover maps an unreadable vault to Blocked as well.
        val s3 = setup(authoritySnapshot = F.preparing())
        s3.vault.loadFailures += SessionVaultException(VaultFailure.UNREADABLE)
        val vaultBlocked = s3.coordinator.recover()
        assertTrue(vaultBlocked is SessionPreparationRecovery.Blocked)
        assertEquals(
            SessionPreparationFailure.VAULT_UNAVAILABLE,
            (vaultBlocked as SessionPreparationRecovery.Blocked).failure,
        )
    }

    @Test
    fun recoverNonPreparingNeverProbesVault() = runTest {
        for (phase in listOf(
            RecoveryRoomPhase.SIGNED_OUT,
            RecoveryRoomPhase.ACTIVE,
            RecoveryRoomPhase.TRANSITIONING,
        )) {
            val snapshot = when (phase) {
                RecoveryRoomPhase.SIGNED_OUT -> F.signedOut()
                else -> RecoveryRoomSnapshot(
                    revision = 3L,
                    sessionGeneration = 1L,
                    cachedAccountId = F.ACCOUNT_ID,
                    phase = phase,
                    identity = RecoveryIdentity(binding = F.binding(), operationId = F.OP_A),
                    baselineComplete = true,
                )
            }
            val authority = ScriptedPreparationAuthority(snapshot)
            val vault = F.vaultWith(record = F.preparedRecord(), revision = 9L)
            vault.loadFailures += SessionVaultException(VaultFailure.UNAVAILABLE)
            val coordinator = F.coordinator(authority = authority, vault = vault)
            val recovery = coordinator.recover()
            assertTrue("$phase", recovery is SessionPreparationRecovery.NotPreparing)
            assertEquals(0, vault.loads)
            assertEquals(0, vault.commits)
        }
    }

    @Test
    fun recoverPreparingWithActivePhaseVaultIsBlockedMismatch() = runTest {
        val preparing = F.preparing()
        val grant = F.session()
        // Otherwise matching binding/operation/grant but ACTIVE vault phase.
        val activeRecord = VaultSessionRecord(
            binding = F.binding(),
            operationId = F.OP_A,
            phase = VaultSessionPhase.ACTIVE,
            session = grant,
            pendingRefresh = null,
        )
        val authority = ScriptedPreparationAuthority(preparing)
        val vault = F.vaultWith(record = activeRecord, revision = 2L)
        val before = vault.current()
        val recovery = F.coordinator(authority = authority, vault = vault).recover()
        assertTrue(recovery is SessionPreparationRecovery.Blocked)
        val failure = (recovery as SessionPreparationRecovery.Blocked).failure
        assertEquals(SessionPreparationFailure.IDENTITY_MISMATCH, failure)
        // NOT_PREPARING stays a reserved enum value, never a recovery reason.
        assertFalse(failure == SessionPreparationFailure.NOT_PREPARING)
        assertEquals(before, vault.current())
        assertEquals(0, vault.commits)
        assertEquals(0, authority.begins)
    }

    @Test
    fun expiredGrantPreservedWithoutFreshnessAdmission() = runTest {
        val expired = F.expiredSession()
        val s = setup()
        val observation = s.coordinator.prepare(F.signedOut(), F.OP_A, expired)
        assertEquals(expired, s.vault.current().record?.session)
        assertEquals(F.preparing(), observation.room)
        // Recovery treats the expired stored grant as valid evidence, not admission.
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionPreparationRecovery.Prepared)
        val recovered = (recovery as SessionPreparationRecovery.Prepared).observation
        assertEquals(F.preparing(), recovered.room)
        assertEquals(expired, s.vault.current().record?.session)
    }

    @Test
    fun recoverPreparingAtAnotherOriginRefusesWithoutVaultRead() = runTest {
        val preparingOther = F.preparing(root = F.OTHER_ORIGIN_ROOT)
        val authority = ScriptedPreparationAuthority(preparingOther)
        val vault = F.vaultWith(record = null, revision = 0L)
        vault.loadFailures += SessionVaultException(VaultFailure.UNAVAILABLE)
        val coordinator = F.coordinator(authority = authority, vault = vault)
        val recovery = coordinator.recover()
        assertTrue(recovery is SessionPreparationRecovery.Blocked)
        assertEquals(
            SessionPreparationFailure.IDENTITY_MISMATCH,
            (recovery as SessionPreparationRecovery.Blocked).failure,
        )
        assertEquals(0, vault.loads)
    }

    // ---- Obligation 5: invalidate / newest / cancellation fencing ----

    @Test
    fun invalidateRetiresInflightPreReserveWithoutIoOrCleanup() = runTest {
        val s = setup()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        s.authority.readGate = {
            entered.complete(Unit)
            release.await()
        }
        val grant = F.session()
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.coordinator.prepare(F.signedOut(), F.OP_A, grant)
                    fail("expected STALE_OPERATION from retired invocation")
                } catch (e: SessionPreparationException) {
                    e
                }
            }
            entered.await()
            s.coordinator.invalidate()
            release.complete(Unit)
            val outcome = job.await()
            assertEquals(SessionPreparationFailure.STALE_OPERATION, (outcome as SessionPreparationException).failure)
        }
        // No Room reservation, no vault write, no cleanup of durable stores.
        assertEquals(0, s.authority.begins)
        assertEquals(0, s.vault.commits)
        assertEquals(F.signedOut(), s.authority.durableSnapshot())
        assertEquals(0L, s.vault.current().revision)
    }

    @Test
    fun seamErrorAfterInvalidateReportsStaleNotMappedError() = runTest {
        val s = setup()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loads = 0
        s.vault.loadGate = {
            loads += 1
            if (loads == 1) {
                entered.complete(Unit)
                release.await()
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
        }
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
                    fail("expected STALE_OPERATION")
                } catch (e: SessionPreparationException) {
                    e
                }
            }
            entered.await()
            s.coordinator.invalidate()
            release.complete(Unit)
            val outcome = job.await()
            // Newest fence wins over seam-error mapping.
            assertEquals(SessionPreparationFailure.STALE_OPERATION, (outcome as SessionPreparationException).failure)
        }
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun newestInvocationRetiresOlderWithSingleReservationAndCommit() = runTest {
        val s = setup()
        val releaseFirst = CompletableDeferred<Unit>()
        s.authority.readGate = { callIndex ->
            if (callIndex == 1) releaseFirst.await()
        }
        val grant: ServiceSession = F.session()
        supervisorScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.coordinator.prepare(F.signedOut(), F.OP_A, grant)
                    fail("expected older invocation to retire")
                } catch (e: SessionPreparationException) {
                    e
                }
            }
            // UNDISPATCHED ran first to its read seam and second runs here to the
            // coordinator mutex, so its newer fence deterministically exists before
            // the first invocation is released.
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                s.coordinator.prepare(F.signedOut(), F.OP_B, grant)
            }
            releaseFirst.complete(Unit)
            val firstOutcome = first.await()
            assertEquals(
                SessionPreparationFailure.STALE_OPERATION,
                (firstOutcome as SessionPreparationException).failure,
            )
            val observation = second.await()
            assertEquals(F.OP_B, observation.room.identity?.operationId)
        }
        // Exactly one Room reservation and one vault commit: the winner's.
        assertEquals(1, s.authority.begins)
        assertEquals(listOf(1L), s.vault.committedRevisions)
        assertEquals(F.OP_B, s.vault.current().record?.operationId)
    }

    @Test
    fun lateInvalidationAfterAdmittedCasPreservesBytesWithoutSuccess() = runTest {
        val s = setup()
        val grant = F.session()
        val enteredReread = CompletableDeferred<Unit>()
        val releaseReread = CompletableDeferred<Unit>()
        var loads = 0
        s.vault.loadGate = {
            loads += 1
            if (loads >= 2) {
                enteredReread.complete(Unit)
                releaseReread.await()
            }
        }
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.coordinator.prepare(F.signedOut(), F.OP_A, grant)
                    fail("expected STALE_OPERATION for fenced post-commit invocation")
                } catch (e: SessionPreparationException) {
                    e
                }
            }
            enteredReread.await()
            // The admitted synchronous CAS already committed: fencing cannot erase it.
            assertEquals(1L, s.vault.current().revision)
            s.coordinator.invalidate()
            releaseReread.complete(Unit)
            val outcome = job.await()
            assertEquals(SessionPreparationFailure.STALE_OPERATION, (outcome as SessionPreparationException).failure)
        }
        // Durable bytes preserved privately; fresh reread is the only recovery path.
        assertEquals(1L, s.vault.current().revision)
        assertEquals(grant, s.vault.current().record?.session)
        val recovery = freshCoordinator(s).recover()
        assertTrue(recovery is SessionPreparationRecovery.Prepared)
    }

    @Test
    fun callerCancellationAtPreCasBoundaryEscapesIdentically() = runTest {
        val s = setup()
        val injected = CancellationException("pre-cas-cancel-preparation")
        s.authority.beginGate = { throw injected }
        // Authority double throws the active cancellation identity directly; the
        // coordinator must let the identical object escape, not map it.
        try {
            s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
            fail("expected cancellation identity")
        } catch (e: CancellationException) {
            assertSame(injected, e)
        }
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun actualCallerCancellationPreemptsRacingSeamError() = runTest {
        val s = setup()
        val callerCancel = CancellationException("actual-caller-cancel-preparation")
        val holder = arrayOfNulls<Job>(1)
        // The seam cancels the ACTUAL caller and then reports its own error; the
        // real caller cancellation must win over the racing seam mapping.
        s.authority.readGate = {
            holder[0]?.cancel(callerCancel)
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        var outcome: Any? = null
        supervisorScope {
            val child = async(start = CoroutineStart.UNDISPATCHED) {
                holder[0] = coroutineContext[Job]
                try {
                    s.coordinator.prepare(F.signedOut(), F.OP_A, F.session())
                    "no-throw"
                } catch (e: Throwable) {
                    outcome = e
                    throw e
                }
            }
            try {
                child.await()
            } catch (_: Throwable) {
            }
            assertSame(callerCancel, outcome)
        }
        assertFalse(outcome is SessionPreparationException)
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun observationValidationAndExceptionShapeStayClosed() = runTest {
        // Representable invalid observation inputs throw INVALID_INPUT.
        val activeSnapshot = RecoveryRoomSnapshot(
            revision = 3L,
            sessionGeneration = 1L,
            cachedAccountId = F.ACCOUNT_ID,
            phase = RecoveryRoomPhase.ACTIVE,
            identity = RecoveryIdentity(binding = F.binding(), operationId = F.OP_A),
            baselineComplete = true,
        )
        for (bad in listOf(F.signedOut(), F.preparing().copy(baselineComplete = true), activeSnapshot)) {
            try {
                PreparedSessionObservation(room = bad, vaultRevision = 1L)
                fail("expected INVALID_INPUT for $bad")
            } catch (e: SessionPreparationException) {
                assertEquals(SessionPreparationFailure.INVALID_INPUT, e.failure)
                F.assertClosed(e, F.defaultSecrets())
            }
        }
        for (badRevision in listOf(0L, -1L)) {
            try {
                PreparedSessionObservation(room = F.preparing(), vaultRevision = badRevision)
                fail("expected INVALID_INPUT for revision $badRevision")
            } catch (e: SessionPreparationException) {
                assertEquals(SessionPreparationFailure.INVALID_INPUT, e.failure)
            }
        }
        // Fixed/redacted model displays, including nested room.
        val observation = PreparedSessionObservation(room = F.preparing(), vaultRevision = 1L)
        F.assertRedacted(
            observation.toString(),
            F.defaultSecrets("access-1", "refresh-1"),
        )
        val blocked = SessionPreparationRecovery.Blocked(SessionPreparationFailure.VAULT_OCCUPIED)
        assertTrue(blocked.toString().isNotEmpty())
    }
}
