package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Inert prepared-session coordinator over one R09a Room authority and one
 * encrypted session vault. Reserves one private Room preparation, durably
 * writes one returned service grant as PREPARED, and rereads both stores.
 * The two stores share no transaction: every crash boundary either resumes
 * the exact operation through durable reread or fails closed and private.
 *
 * One application owner constructs one coordinator per physical Room/vault
 * pair. All suspend operations serialize through one coordinator mutex. Each
 * invocation opens a fresh opaque process fence before waiting for the
 * mutex; the newest invocation retires older work. [invalidate] retires
 * in-flight work synchronously without I/O, durable fencing or cleanup.
 * Caller cancellation dominates stale/error mapping, and an injected
 * cancellation inside an otherwise active caller escapes identically. A
 * retired invocation throws STALE_OPERATION, never a success or report.
 * No cached success, state or process token pretends to be cross-store
 * authority. Returned observations never authorize later actions. No
 * production wiring, promotion, refresh, lease, publication or UI lives
 * here; occupied credentials are preserved, never replaced or cleared.
 */
internal class SessionPreparationCoordinator(
    private val origin: ServiceOrigin,
    private val authority: SessionPreparationAuthority,
    private val vault: SessionVault,
    private val mutex: Mutex = Mutex(),
) {
    private val stateLock = Any()
    private var currentFence: Any = Any()

    /**
     * Synchronously retires in-flight work by opening a fresh fence token.
     * Never waits for I/O or the mutex and performs no I/O, durable fencing
     * or cleanup. Already-started synchronous writes may still commit
     * durably; dead invocations cannot return prepared success.
     */
    fun invalidate() {
        synchronized(stateLock) { currentFence = Any() }
    }

    /**
     * Prepares from a signed-out expectation: exact authority match, empty
     * vault preflight with counter checks, one Room reservation, one vault
     * CAS of the full supplied grant as PREPARED, then stable rereads of
     * both stores. Any occupied vault record refuses VAULT_OCCUPIED with no
     * writes. A Room error, cancellation or uncertain reservation starts no
     * vault write; an uncertain vault outcome keeps the exact Room operation
     * without compensating writes, and durable reread is the only recovery.
     */
    suspend fun prepare(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): PreparedSessionObservation {
        val fence = openFence()
        return mutex.withLock { prepareLocked(fence, expected, operationId, session) }
    }

    /**
     * Resumes after a reservation-only interruption under the exact expected
     * PREPARING identity. With an empty vault it writes PREPARED at the
     * empty snapshot's revision using the same reserved generation and
     * operation with the caller-supplied grant, without reserving again.
     * With an occupied vault it accepts read-only idempotence only for an
     * exactly matching PREPARED identity and semantically equal grant,
     * preserving any retained pending attempt. Never changes Room.
     */
    suspend fun resume(
        expected: RecoveryRoomSnapshot,
        session: ServiceSession,
    ): PreparedSessionObservation {
        val fence = openFence()
        return mutex.withLock { resumeLocked(fence, expected, session) }
    }

    /**
     * Read-only restart classification. Non-PREPARING Room never probes the
     * vault; PREPARING at another origin never probes the vault; empty vault
     * stably rereads to NeedsGrant; matching PREPARED state stably rereads
     * to Prepared. Cancellation and retired fences propagate as exceptions,
     * never Blocked. Performs no writes, promotion, fencing or cleanup.
     */
    suspend fun recover(): SessionPreparationRecovery {
        val fence = openFence()
        return mutex.withLock { recoverLocked(fence) }
    }

    override fun toString(): String = "SessionPreparationCoordinator(redacted)"

    private suspend fun prepareLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        if (expected.phase != RecoveryRoomPhase.SIGNED_OUT) {
            throw SessionPreparationException(SessionPreparationFailure.INVALID_INPUT)
        }
        val targetOperation = canonicalUuid(operationId)
        val targetAccount = canonicalUuid(session.account.accountId)
        val observed = readAuthority(fence)
        if (observed != expected) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.STALE_AUTHORITY)
        }
        val empty = loadVault(fence)
        if (empty.record != null) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.VAULT_OCCUPIED)
        }
        if (expected.revision == Long.MAX_VALUE ||
            expected.sessionGeneration == Long.MAX_VALUE ||
            empty.revision == Long.MAX_VALUE
        ) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.COUNTER_OVERFLOW)
        }
        val rechecked = readAuthority(fence)
        if (rechecked != expected) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.STALE_AUTHORITY)
        }
        val reserved = beginReservation(fence, expected, targetAccount, targetOperation)
        val newRevision = expected.revision + 1L
        val newGeneration = expected.sessionGeneration + 1L
        val required = RecoveryRoomSnapshot(
            revision = newRevision,
            sessionGeneration = newGeneration,
            cachedAccountId = targetAccount,
            phase = RecoveryRoomPhase.PREPARING,
            identity = RecoveryIdentity(
                binding = ServiceSessionBinding(
                    origin = origin,
                    accountId = targetAccount,
                    sessionGeneration = newGeneration,
                ),
                operationId = targetOperation,
            ),
            baselineComplete = false,
        )
        if (reserved != required) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.STALE_AUTHORITY)
        }
        val rereadReserved = readAuthority(fence)
        if (rereadReserved != required) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.STALE_AUTHORITY)
        }
        ensureFenceCurrent(fence)
        val intended = VaultSessionRecord(
            binding = requireNotNull(required.identity).binding,
            operationId = targetOperation,
            phase = VaultSessionPhase.PREPARED,
            session = session,
            pendingRefresh = null,
        )
        val committed = casVault(fence, empty.revision, intended)
        val committedRecord = committed.record
        if (committed.revision != empty.revision + 1L ||
            committedRecord == null ||
            !vaultRecordsEqual(committedRecord, intended)
        ) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.IDENTITY_MISMATCH)
        }
        verifyPreparedStable(fence, required, committed.revision, intended)
        ensureFenceCurrent(fence)
        return PreparedSessionObservation(required, committed.revision)
    }

    private suspend fun resumeLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        session: ServiceSession,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        if (expected.phase != RecoveryRoomPhase.PREPARING) {
            throw SessionPreparationException(SessionPreparationFailure.INVALID_INPUT)
        }
        val identity = expected.identity
        if (identity == null || expected.baselineComplete) {
            throw SessionPreparationException(SessionPreparationFailure.INVALID_INPUT)
        }
        if (identity.binding.origin != origin ||
            session.account.accountId != identity.binding.accountId
        ) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.IDENTITY_MISMATCH)
        }
        val reread = readAuthority(fence)
        if (reread != expected) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.STALE_AUTHORITY)
        }
        val snapshot = loadVault(fence)
        val record = snapshot.record
        if (record == null) {
            val rereadReserved = readAuthority(fence)
            if (rereadReserved != expected) {
                ensureFenceCurrent(fence)
                throw SessionPreparationException(SessionPreparationFailure.STALE_AUTHORITY)
            }
            if (snapshot.revision == Long.MAX_VALUE) {
                ensureFenceCurrent(fence)
                throw SessionPreparationException(SessionPreparationFailure.COUNTER_OVERFLOW)
            }
            ensureFenceCurrent(fence)
            val intended = VaultSessionRecord(
                binding = identity.binding,
                operationId = identity.operationId,
                phase = VaultSessionPhase.PREPARED,
                session = session,
                pendingRefresh = null,
            )
            val committed = casVault(fence, snapshot.revision, intended)
            val committedRecord = committed.record
            if (committed.revision != snapshot.revision + 1L ||
                committedRecord == null ||
                !vaultRecordsEqual(committedRecord, intended)
            ) {
                ensureFenceCurrent(fence)
                throw SessionPreparationException(SessionPreparationFailure.IDENTITY_MISMATCH)
            }
            verifyPreparedStable(fence, expected, committed.revision, intended)
            ensureFenceCurrent(fence)
            return PreparedSessionObservation(expected, committed.revision)
        }
        if (record.binding != identity.binding ||
            record.operationId != identity.operationId ||
            record.phase != VaultSessionPhase.PREPARED ||
            record.session != session
        ) {
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.IDENTITY_MISMATCH)
        }
        verifyPreparedStable(fence, expected, snapshot.revision, record)
        ensureFenceCurrent(fence)
        return PreparedSessionObservation(expected, snapshot.revision)
    }

    private suspend fun recoverLocked(fence: Any): SessionPreparationRecovery {
        ensureFenceCurrent(fence)
        val room = try {
            ensureFenceCurrent(fence)
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.Blocked(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.Blocked(SessionPreparationFailure.AUTHORITY_UNAVAILABLE)
        }
        if (room.phase != RecoveryRoomPhase.PREPARING) {
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.NotPreparing
        }
        val identity = room.identity
        if (identity == null || identity.binding.origin != origin) {
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.Blocked(SessionPreparationFailure.IDENTITY_MISMATCH)
        }
        val vaultSnapshot = try {
            ensureFenceCurrent(fence)
            val snapshot = vault.load()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.Blocked(mapVaultFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.Blocked(SessionPreparationFailure.VAULT_UNAVAILABLE)
        }
        val record = vaultSnapshot.record
        if (record == null) {
            try {
                verifyEmptyStable(fence, room, vaultSnapshot.revision)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SessionPreparationException) {
                currentCoroutineContext().ensureActive()
                ensureFenceCurrent(fence)
                if (e.failure == SessionPreparationFailure.STALE_OPERATION) {
                    throw e
                }
                return SessionPreparationRecovery.Blocked(e.failure)
            }
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.NeedsGrant(room)
        }
        if (record.binding != identity.binding || record.operationId != identity.operationId) {
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.Blocked(SessionPreparationFailure.IDENTITY_MISMATCH)
        }
        if (record.phase != VaultSessionPhase.PREPARED) {
            ensureFenceCurrent(fence)
            return SessionPreparationRecovery.Blocked(SessionPreparationFailure.IDENTITY_MISMATCH)
        }
        try {
            verifyPreparedStable(fence, room, vaultSnapshot.revision, record)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            if (e.failure == SessionPreparationFailure.STALE_OPERATION) {
                throw e
            }
            return SessionPreparationRecovery.Blocked(e.failure)
        }
        ensureFenceCurrent(fence)
        return SessionPreparationRecovery.Prepared(PreparedSessionObservation(room, vaultSnapshot.revision))
    }

    /**
     * Alternating stable reread Room/vault/Room/vault/Room for a prepared
     * result. Every Room snapshot must exactly equal the expected PREPARING
     * observation; every vault snapshot must equal the expected revision and
     * the semantically exact intended record, preserving whitespace, bytes,
     * grant fields, expiry and pending data. Mismatch refuses without
     * writes; a mutation after the final read cannot be ruled out.
     */
    private suspend fun verifyPreparedStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) {
        repeat(3) { index ->
            val room = readAuthority(fence)
            if (room != expectedRoom) {
                ensureFenceCurrent(fence)
                throw SessionPreparationException(SessionPreparationFailure.STALE_AUTHORITY)
            }
            if (index < 2) {
                val snapshot = loadVault(fence)
                val record = snapshot.record
                if (snapshot.revision != expectedVaultRevision ||
                    record == null ||
                    !vaultRecordsEqual(record, expectedRecord)
                ) {
                    ensureFenceCurrent(fence)
                    throw SessionPreparationException(SessionPreparationFailure.IDENTITY_MISMATCH)
                }
            }
        }
    }

    /**
     * Alternating stable reread Room/vault/Room/vault/Room for a
     * reservation-only restart. Every Room snapshot must exactly equal the
     * expected PREPARING observation; every vault snapshot must remain
     * empty at the exact expected revision, including tombstones.
     */
    private suspend fun verifyEmptyStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedEmptyRevision: Long,
    ) {
        repeat(3) { index ->
            val room = readAuthority(fence)
            if (room != expectedRoom) {
                ensureFenceCurrent(fence)
                throw SessionPreparationException(SessionPreparationFailure.STALE_AUTHORITY)
            }
            if (index < 2) {
                val snapshot = loadVault(fence)
                if (snapshot.revision != expectedEmptyRevision || snapshot.record != null) {
                    ensureFenceCurrent(fence)
                    throw SessionPreparationException(SessionPreparationFailure.IDENTITY_MISMATCH)
                }
            }
        }
    }

    private suspend fun readAuthority(fence: Any): RecoveryRoomSnapshot {
        ensureFenceCurrent(fence)
        try {
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionPreparationException(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.AUTHORITY_UNAVAILABLE)
        }
    }

    private suspend fun loadVault(fence: Any): VaultSnapshot {
        ensureFenceCurrent(fence)
        try {
            val snapshot = vault.load()
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionPreparationException(mapVaultFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.VAULT_UNAVAILABLE)
        }
    }

    private suspend fun casVault(
        fence: Any,
        expectedRevision: Long,
        record: VaultSessionRecord?,
    ): VaultSnapshot {
        ensureFenceCurrent(fence)
        try {
            val snapshot = vault.compareAndSet(expectedRevision, record)
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionPreparationException(mapVaultFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.VAULT_UNAVAILABLE)
        }
    }

    private suspend fun beginReservation(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        accountId: String,
        operationId: String,
    ): RecoveryRoomSnapshot {
        ensureFenceCurrent(fence)
        try {
            val reserved = authority.beginPreparation(expected, origin, accountId, operationId)
            ensureFenceCurrent(fence)
            return reserved
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionPreparationException(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionPreparationException(SessionPreparationFailure.AUTHORITY_UNAVAILABLE)
        }
    }

    private fun openFence(): Any {
        val fence = Any()
        synchronized(stateLock) { currentFence = fence }
        return fence
    }

    private suspend fun ensureFenceCurrent(fence: Any) {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                throw SessionPreparationException(SessionPreparationFailure.STALE_OPERATION)
            }
        }
    }

    private fun canonicalUuid(value: String): String {
        try {
            return requireWorkoutUuid(value, "id")
        } catch (_: Exception) {
            throw SessionPreparationException(SessionPreparationFailure.INVALID_INPUT)
        }
    }

    private fun mapAuthorityFailure(failure: RoomSessionAuthorityFailure): SessionPreparationFailure =
        when (failure) {
            RoomSessionAuthorityFailure.STALE_AUTHORITY -> SessionPreparationFailure.STALE_AUTHORITY
            RoomSessionAuthorityFailure.ACCOUNT_MISMATCH -> SessionPreparationFailure.ACCOUNT_MISMATCH
            RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION ->
                SessionPreparationFailure.LOCAL_DATA_REQUIRES_DECISION
            RoomSessionAuthorityFailure.COUNTER_OVERFLOW -> SessionPreparationFailure.COUNTER_OVERFLOW
            RoomSessionAuthorityFailure.INVALID_INPUT -> SessionPreparationFailure.INVALID_INPUT
            RoomSessionAuthorityFailure.CORRUPT_AUTHORITY,
            RoomSessionAuthorityFailure.UNAVAILABLE,
            -> SessionPreparationFailure.AUTHORITY_UNAVAILABLE
        }

    private fun mapVaultFailure(failure: VaultFailure): SessionPreparationFailure =
        when (failure) {
            VaultFailure.CONFLICT -> SessionPreparationFailure.IDENTITY_MISMATCH
            VaultFailure.REVISION_EXHAUSTED -> SessionPreparationFailure.COUNTER_OVERFLOW
            VaultFailure.INVALID_RECORD,
            VaultFailure.UNREADABLE,
            VaultFailure.UNSUPPORTED_VERSION,
            VaultFailure.UNAVAILABLE,
            -> SessionPreparationFailure.VAULT_UNAVAILABLE
        }

    /**
     * Semantic vault record equality. Restored pending attempts compare
     * exact operation, key, body text and token, never reference identity,
     * while grant, binding, operation and phase use value equality.
     */
    private fun vaultRecordsEqual(actual: VaultSessionRecord, expected: VaultSessionRecord): Boolean {
        if (actual.binding != expected.binding ||
            actual.operationId != expected.operationId ||
            actual.phase != expected.phase ||
            actual.session != expected.session
        ) {
            return false
        }
        val actualPending = actual.pendingRefresh
        val expectedPending = expected.pendingRefresh
        if (actualPending == null || expectedPending == null) {
            return actualPending == null && expectedPending == null
        }
        return actualPending.operationId == expectedPending.operationId &&
            actualPending.attempt.idempotencyKey == expectedPending.attempt.idempotencyKey &&
            actualPending.attempt.requestJson == expectedPending.attempt.requestJson &&
            actualPending.attempt.refreshToken == expectedPending.attempt.refreshToken
    }
}
