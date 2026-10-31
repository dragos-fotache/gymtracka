package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Inert same-account recovery over one retained-account Room authority and
 * one encrypted session vault. Supersedes an unrecoverable predecessor only
 * with a supplied fresh service-confirmed foreground same-origin/same-UUID
 * grant while atomically retaining and permanently fencing its exact
 * encrypted evidence. Reuses Room12, vault CAS, the shared owner/credential
 * mutex, reauthentication finalization and preparation/prepared engine/completion preparation boundaries. Stops at
 * advisory private PREPARING/PREPARED eligibility; never bootstraps,
 * publishes, promotes, activates, cleans up, rekeys, replays, rebinds,
 * or performs HTTP, clock/UUID generation, projection or account UI work.
 *
 * One application owner constructs one coordinator per physical Room/vault
 * pair with the shared [PushRunOwner] and the shared credential-selection
 * mutex. No independent refresh/transition owner is constructed. Lock order
 * is run owner then credential mutex, held continuously through all
 * reads/writes. Must not be called from a held owner/credential slot.
 * Construction is inert and performs no I/O. No cached success/admission
 * exists. Archived material is never selected, replayed, rebound, promoted
 * or used as authentication; it is private retained evidence.
 */
internal class SameAccountRecoveryCoordinator(
    private val origin: ServiceOrigin,
    private val authority: SameAccountRecoveryAuthority,
    private val vault: SessionVault,
    private val runOwner: PushRunOwner,
    private val credentialMutex: Mutex,
) {
    private val stateLock = Any()
    private var currentFence: Any = Any()

    /**
     * Synchronously retires in-flight work by opening a fresh fence token.
     * Never waits for locks or I/O and performs no I/O or durable effects.
     * Already-started writes may still commit durably; dead invocations
     * cannot return success or classification.
     */
    fun invalidate() {
        synchronized(stateLock) { currentFence = Any() }
    }

    /**
     * Explicitly starts a new recovery operation (not restart inference)
     * from the exact [expected] retained observation with a fresh operation
     * and a fresh service-confirmed grant: stable source/Room preflight with
     * checked counters, one Room reservation, one vault CAS of the full
     * supplied PREPARED target plus inherited/appended archive, then Room
     * finalization to PREPARING with stable rereads of both stores.
     */
    suspend fun prepare(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): PreparedSessionObservation {
        currentCoroutineContext().ensureActive()
        val targetOperation = validatePrepareInput(expected, operationId, session)
        val fence = openFence()
        return runOwner.mutex.withLock {
            credentialMutex.withLock {
                prepareLocked(fence, expected, targetOperation, session)
            }
        }
    }

    /**
     * Resumes under the exact expected TRANSITIONING/PREPARING identity with
     * the caller-supplied same-account grant. Never reserves again. An
     * installed exact PREPARED target finalizes only TRANSITIONING (PREPARING
     * is read-only idempotence) with supplied full session equality; empty
     * or eligible older-evidence credentials install the exact expected
     * PREPARED target by one CAS with archive handling, then finalize when
     * TRANSITIONING. Any installed ACTIVE target or incompatible/foreign
     * evidence refuses.
     */
    suspend fun resume(
        expected: RecoveryRoomSnapshot,
        session: ServiceSession,
    ): PreparedSessionObservation {
        currentCoroutineContext().ensureActive()
        validateResumeInput(expected, session)
        val fence = openFence()
        return runOwner.mutex.withLock {
            credentialMutex.withLock {
                resumeLocked(fence, expected, session)
            }
        }
    }

    /**
     * Read-only recovery classification. Writes nothing, never returns
     * credentials or app authority. Cancellation and stale invocations
     * propagate as exceptions, never Blocked.
     */
    suspend fun recover(): SameAccountRecovery {
        currentCoroutineContext().ensureActive()
        val fence = openFence()
        return runOwner.mutex.withLock {
            credentialMutex.withLock {
                recoverLocked(fence)
            }
        }
    }

    override fun toString(): String = "SameAccountRecoveryCoordinator(redacted)"

    private suspend fun prepareLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        targetOperation: String,
        session: ServiceSession,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        val retained = expected.cachedAccountId
            ?: throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        if (session.account.accountId != retained) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        val presentOperation = expected.identity?.operationId
        if (presentOperation != null && targetOperation == presentOperation) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val observed = readAuthority(fence, expected)
        if (observed != expected) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        checkSameAccount(fence, expected, retained)
        val source = loadVault(fence, expected)
        requireEligibleSource(fence, expected, retained, source, session, targetOperation)
        if (expected.revision > Long.MAX_VALUE - 2L ||
            expected.sessionGeneration == Long.MAX_VALUE ||
            source.revision == Long.MAX_VALUE
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.COUNTER_OVERFLOW,
            )
        }
        verifyPreflightStable(fence, expected, source.revision, source.record)
        ensureFenceCurrent(fence)
        val newRevision = expected.revision + 1L
        val newGeneration = expected.sessionGeneration + 1L
        val newBinding = ServiceSessionBinding(
            origin = origin,
            accountId = retained,
            sessionGeneration = newGeneration,
        )
        val requiredTransition = RecoveryRoomSnapshot(
            revision = newRevision,
            sessionGeneration = newGeneration,
            cachedAccountId = retained,
            phase = RecoveryRoomPhase.TRANSITIONING,
            identity = RecoveryIdentity(
                binding = newBinding,
                operationId = targetOperation,
            ),
            baselineComplete = false,
        )
        val reserved = beginReservation(fence, expected, retained, targetOperation, requiredTransition)
        if (reserved != requiredTransition) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        val rereadReserved = readAuthority(fence, requiredTransition)
        if (rereadReserved != requiredTransition) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        ensureFenceCurrent(fence)
        val postReserve = loadVault(fence, requiredTransition)
        if (postReserve.revision != source.revision ||
            !vaultSnapshotsSemanticEqual(postReserve.record, source.record)
        ) {
            throw precedence(
                fence,
                requiredTransition,
                null,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.VAULT_CONFLICT,
                ),
            )
        }
        checkSameAccount(fence, requiredTransition, retained)
        ensureFenceCurrent(fence)
        val postReserveRoom = readAuthority(fence, requiredTransition)
        if (postReserveRoom != requiredTransition) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        ensureFenceCurrent(fence)
        val archive = computeArchive(source, session)
        val intended = VaultSessionRecord(
            binding = newBinding,
            operationId = targetOperation,
            phase = VaultSessionPhase.PREPARED,
            session = session,
            pendingRefresh = null,
            supersededSessions = archive,
        )
        val committed = casVault(fence, source.revision, intended, requiredTransition)
        val committedRecord = committed.record
        if (committed.revision != source.revision + 1L ||
            committedRecord == null ||
            !vaultRecordsSemanticEqual(committedRecord, intended)
        ) {
            throw precedence(
                fence,
                requiredTransition,
                null,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                ),
            )
        }
        verifyTransitionStable(fence, requiredTransition, committed.revision, intended)
        ensureFenceCurrent(fence)
        checkSameAccount(fence, requiredTransition, retained)
        ensureFenceCurrent(fence)
        val requiredPreparing = RecoveryRoomSnapshot(
            revision = newRevision + 1L,
            sessionGeneration = newGeneration,
            cachedAccountId = retained,
            phase = RecoveryRoomPhase.PREPARING,
            identity = RecoveryIdentity(
                binding = newBinding,
                operationId = targetOperation,
            ),
            baselineComplete = false,
        )
        val finalized = finishReservation(fence, requiredTransition, requiredPreparing)
        if (finalized != requiredPreparing) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        verifyPreparedStable(fence, requiredPreparing, committed.revision, intended)
        ensureFenceCurrent(fence)
        return PreparedSessionObservation(requiredPreparing, committed.revision)
    }

    private suspend fun resumeLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        session: ServiceSession,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        val identity = expected.identity
        val retained = expected.cachedAccountId
        if (identity == null || retained == null || expected.baselineComplete) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (expected.phase != RecoveryRoomPhase.TRANSITIONING &&
            expected.phase != RecoveryRoomPhase.PREPARING
        ) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (identity.binding.origin != origin ||
            identity.binding.accountId != retained ||
            session.account.accountId != retained
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        val current = readAuthority(fence, expected)
        if (current != expected) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        checkSameAccount(fence, expected, retained)
        val snapshot = loadVault(fence, expected)
        val record = snapshot.record
        if (record != null &&
            record.binding == identity.binding &&
            record.operationId == identity.operationId &&
            record.phase == VaultSessionPhase.PREPARED
        ) {
            return resumeInstalledLocked(fence, expected, identity, retained, session, snapshot, record)
        }
        if (record != null &&
            record.binding == identity.binding &&
            record.operationId == identity.operationId
        ) {
            throw precedence(
                fence,
                expected,
                null,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                ),
            )
        }
        return resumeMissingOrPredecessorLocked(fence, expected, identity, retained, session, snapshot)
    }

    private suspend fun resumeInstalledLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        identity: RecoveryIdentity,
        retained: String,
        session: ServiceSession,
        snapshot: VaultSnapshot,
        record: VaultSessionRecord,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        if (record.session != session) {
            throw precedence(
                fence,
                expected,
                null,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                ),
            )
        }
        requireNoArchivedReactivation(fence, expected, record.supersededSessions, session)
        checkSameAccount(fence, expected, retained)
        if (expected.phase == RecoveryRoomPhase.PREPARING) {
            verifyPreparedStable(fence, expected, snapshot.revision, record)
            ensureFenceCurrent(fence)
            return PreparedSessionObservation(expected, snapshot.revision)
        }
        verifyTransitionStable(fence, expected, snapshot.revision, record)
        ensureFenceCurrent(fence)
        if (expected.revision == Long.MAX_VALUE) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.COUNTER_OVERFLOW,
            )
        }
        ensureFenceCurrent(fence)
        checkSameAccount(fence, expected, retained)
        val requiredPreparingInstalled = RecoveryRoomSnapshot(
            revision = expected.revision + 1L,
            sessionGeneration = expected.sessionGeneration,
            cachedAccountId = retained,
            phase = RecoveryRoomPhase.PREPARING,
            identity = identity,
            baselineComplete = false,
        )
        val finalized = finishReservation(fence, expected, requiredPreparingInstalled)
        val requiredPreparing = requiredPreparingInstalled
        if (finalized != requiredPreparing) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        verifyPreparedStable(fence, requiredPreparing, snapshot.revision, record)
        ensureFenceCurrent(fence)
        return PreparedSessionObservation(requiredPreparing, snapshot.revision)
    }

    private suspend fun resumeMissingOrPredecessorLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        identity: RecoveryIdentity,
        retained: String,
        session: ServiceSession,
        snapshot: VaultSnapshot,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        requireEligibleSource(fence, expected, retained, snapshot, session, identity.operationId)
        if (snapshot.revision == Long.MAX_VALUE) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.COUNTER_OVERFLOW,
            )
        }
        if (expected.phase == RecoveryRoomPhase.TRANSITIONING && expected.revision == Long.MAX_VALUE) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.COUNTER_OVERFLOW,
            )
        }
        verifyPreflightStable(fence, expected, snapshot.revision, snapshot.record)
        ensureFenceCurrent(fence)
        checkSameAccount(fence, expected, retained)
        ensureFenceCurrent(fence)
        val rereadRoom = readAuthority(fence, expected)
        if (rereadRoom != expected) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        ensureFenceCurrent(fence)
        val archive = computeArchive(snapshot, session)
        val intended = VaultSessionRecord(
            binding = identity.binding,
            operationId = identity.operationId,
            phase = VaultSessionPhase.PREPARED,
            session = session,
            pendingRefresh = null,
            supersededSessions = archive,
        )
        val committed = casVault(fence, snapshot.revision, intended, expected)
        val committedRecord = committed.record
        if (committed.revision != snapshot.revision + 1L ||
            committedRecord == null ||
            !vaultRecordsSemanticEqual(committedRecord, intended)
        ) {
            throw precedence(
                fence,
                expected,
                null,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                ),
            )
        }
        if (expected.phase == RecoveryRoomPhase.PREPARING) {
            checkSameAccount(fence, expected, retained)
            verifyPreparedStable(fence, expected, committed.revision, intended)
            ensureFenceCurrent(fence)
            return PreparedSessionObservation(expected, committed.revision)
        }
        verifyTransitionStable(fence, expected, committed.revision, intended)
        ensureFenceCurrent(fence)
        checkSameAccount(fence, expected, retained)
        val requiredPreparingPredecessor = RecoveryRoomSnapshot(
            revision = expected.revision + 1L,
            sessionGeneration = expected.sessionGeneration,
            cachedAccountId = retained,
            phase = RecoveryRoomPhase.PREPARING,
            identity = identity,
            baselineComplete = false,
        )
        val finalized = finishReservation(fence, expected, requiredPreparingPredecessor)
        val requiredPreparing = requiredPreparingPredecessor
        if (finalized != requiredPreparing) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        verifyPreparedStable(fence, requiredPreparing, committed.revision, intended)
        ensureFenceCurrent(fence)
        return PreparedSessionObservation(requiredPreparing, committed.revision)
    }

    /**
     * Fenced read-only classification with the same durable precedence as
     * the write paths: caller cancellation and the newest fence propagate
     * as exceptions (never Blocked), a drifted durable Room classifies as
     * Blocked(STALE_AUTHORITY), and an unchanged stage preserves the
     * observed mapped category. No classification is returned after a dead
     * operation, and the vault is never probed without a retained account.
     */
    private suspend fun recoverLocked(fence: Any): SameAccountRecovery {
        ensureFenceCurrent(fence)
        val room = try {
            ensureFenceCurrent(fence)
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, null, mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, null, SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE)
        }
        val stableRoom = try {
            ensureFenceCurrent(fence)
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE)
        }
        if (stableRoom != room) {
            ensureFenceCurrent(fence)
            return SameAccountRecovery.Blocked(SessionReauthenticationFailure.STALE_AUTHORITY)
        }
        val retained = room.cachedAccountId
        if (retained == null) {
            ensureFenceCurrent(fence)
            return SameAccountRecovery.NoRetainedAccount
        }
        try {
            checkSameAccountRaw(room, retained)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE)
        }
        ensureFenceCurrent(fence)
        val first = try {
            ensureFenceCurrent(fence)
            val snapshot = vault.load()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            return recoverVaultFailure(fence, room, mapVaultFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return recoverVaultFailure(fence, room, SessionReauthenticationFailure.VAULT_UNAVAILABLE)
        }
        ensureFenceCurrent(fence)
        val rereadRoom = try {
            ensureFenceCurrent(fence)
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE)
        }
        if (rereadRoom != room) {
            ensureFenceCurrent(fence)
            return SameAccountRecovery.Blocked(SessionReauthenticationFailure.STALE_AUTHORITY)
        }
        ensureFenceCurrent(fence)
        val second = try {
            ensureFenceCurrent(fence)
            val snapshot = vault.load()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            return recoverVaultFailure(fence, room, mapVaultFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return recoverVaultFailure(fence, room, SessionReauthenticationFailure.VAULT_UNAVAILABLE)
        }
        if (second.revision != first.revision ||
            !vaultSnapshotsSemanticEqual(second.record, first.record)
        ) {
            return recoverVaultFailure(fence, room, SessionReauthenticationFailure.VAULT_CONFLICT)
        }
        ensureFenceCurrent(fence)
        val finalRoom = try {
            ensureFenceCurrent(fence)
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE)
        }
        if (finalRoom != room) {
            ensureFenceCurrent(fence)
            return SameAccountRecovery.Blocked(SessionReauthenticationFailure.STALE_AUTHORITY)
        }
        try {
            checkSameAccountRaw(finalRoom, retained)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return recoverRoomFailure(fence, room, SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE)
        }
        ensureFenceCurrent(fence)
        val classification = classifyRecovery(finalRoom, second)
        ensureFenceCurrent(fence)
        return classification
    }

    /**
     * Closed preference between a previously observed fault and a current
     * reread fault: an observed AUTHORITY_CORRUPT on either side is never
     * downgraded to a generic fault.
     */
    private fun preferCorruptObserved(
        previous: SessionReauthenticationFailure,
        current: SessionReauthenticationFailure,
    ): SessionReauthenticationFailure {
        if (previous == SessionReauthenticationFailure.AUTHORITY_CORRUPT ||
            current == SessionReauthenticationFailure.AUTHORITY_CORRUPT
        ) {
            return SessionReauthenticationFailure.AUTHORITY_CORRUPT
        }
        return current
    }

    /**
     * Blocked mapping for Room seam failures in recover: cancellation and
     * the newest fence win as exceptions; a drifted durable stage wins as
     * Blocked(STALE_AUTHORITY); an unchanged (or unreadable) stage preserves
     * the observed mapped category.
     */
    private suspend fun recoverRoomFailure(
        fence: Any,
        before: RecoveryRoomSnapshot?,
        mapped: SessionReauthenticationFailure,
    ): SameAccountRecovery.Blocked {
        currentCoroutineContext().ensureActive()
        ensureFenceCurrent(fence)
        if (before == null) {
            return SameAccountRecovery.Blocked(mapped)
        }
        val reread = try {
            authority.read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SameAccountRecovery.Blocked(preferCorruptObserved(mapped, mapAuthorityFailure(e.failure)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SameAccountRecovery.Blocked(preferCorruptObserved(mapped, SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE))
        }
        currentCoroutineContext().ensureActive()
        ensureFenceCurrent(fence)
        if (reread != before) {
            return SameAccountRecovery.Blocked(SessionReauthenticationFailure.STALE_AUTHORITY)
        }
        return SameAccountRecovery.Blocked(mapped)
    }

    /**
     * Blocked mapping for vault seam failures in recover, with the same
     * fence and durable-Room precedence as the Room leg above.
     */
    private suspend fun recoverVaultFailure(
        fence: Any,
        beforeRoom: RecoveryRoomSnapshot?,
        mapped: SessionReauthenticationFailure,
    ): SameAccountRecovery.Blocked {
        currentCoroutineContext().ensureActive()
        ensureFenceCurrent(fence)
        if (beforeRoom == null) {
            return SameAccountRecovery.Blocked(mapped)
        }
        val reread = try {
            authority.read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SameAccountRecovery.Blocked(preferCorruptObserved(mapped, mapAuthorityFailure(e.failure)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SameAccountRecovery.Blocked(preferCorruptObserved(mapped, SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE))
        }
        currentCoroutineContext().ensureActive()
        ensureFenceCurrent(fence)
        if (reread != beforeRoom) {
            return SameAccountRecovery.Blocked(SessionReauthenticationFailure.STALE_AUTHORITY)
        }
        return SameAccountRecovery.Blocked(mapped)
    }

    private fun classifyRecovery(room: RecoveryRoomSnapshot, source: VaultSnapshot): SameAccountRecovery {
        val retained = room.cachedAccountId
            ?: return SameAccountRecovery.NoRetainedAccount
        val record = source.record
        if (record == null) {
            return when (room.phase) {
                RecoveryRoomPhase.SIGNED_OUT, RecoveryRoomPhase.ACTIVE ->
                    SameAccountRecovery.EligibleForReplacement(room)
                RecoveryRoomPhase.TRANSITIONING, RecoveryRoomPhase.PREPARING ->
                    SameAccountRecovery.NeedsGrant(room)
            }
        }
        if (record.binding.origin != origin || record.binding.accountId != retained) {
            return SameAccountRecovery.Blocked(SessionReauthenticationFailure.IDENTITY_MISMATCH)
        }
        for (entry in record.supersededSessions.entries) {
            if (entry.record.binding.origin != origin || entry.record.binding.accountId != retained) {
                return SameAccountRecovery.Blocked(SessionReauthenticationFailure.IDENTITY_MISMATCH)
            }
        }
        val identity = room.identity
        return when (room.phase) {
            RecoveryRoomPhase.SIGNED_OUT -> {
                if (record.binding.sessionGeneration >= room.sessionGeneration) {
                    SameAccountRecovery.Blocked(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                } else {
                    SameAccountRecovery.EligibleForReplacement(room)
                }
            }
            RecoveryRoomPhase.ACTIVE -> {
                if (identity == null ||
                    record.binding != identity.binding ||
                    record.operationId != identity.operationId ||
                    (record.phase != VaultSessionPhase.ACTIVE && record.phase != VaultSessionPhase.PREPARED)
                ) {
                    SameAccountRecovery.Blocked(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                } else {
                    SameAccountRecovery.EligibleForReplacement(room)
                }
            }
            RecoveryRoomPhase.TRANSITIONING -> {
                if (identity != null &&
                    record.binding == identity.binding &&
                    record.operationId == identity.operationId &&
                    record.phase == VaultSessionPhase.PREPARED
                ) {
                    SameAccountRecovery.NeedsFinalization(room, source.revision)
                } else if (isOlderRetainedEvidence(room, identity, record)) {
                    SameAccountRecovery.NeedsGrant(room)
                } else {
                    SameAccountRecovery.Blocked(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                }
            }
            RecoveryRoomPhase.PREPARING -> {
                if (identity != null &&
                    record.binding == identity.binding &&
                    record.operationId == identity.operationId &&
                    record.phase == VaultSessionPhase.PREPARED
                ) {
                    SameAccountRecovery.Prepared(PreparedSessionObservation(room, source.revision))
                } else if (isOlderRetainedEvidence(room, identity, record)) {
                    SameAccountRecovery.NeedsGrant(room)
                } else {
                    SameAccountRecovery.Blocked(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                }
            }
        }
    }

    /**
     * Strictly older retained evidence for a private reservation: a
     * readable same-origin/same-account record with a strictly lower
     * session generation, a different operation and an ACTIVE or PREPARED
     * phase. Covers the adjacent generation-1 predecessor and any older
     * readable evidence (for example a pre-CAS crash leaving vault6 behind
     * reserved room8); it never selects, replays or adopts the old
     * credential. Same/newer incompatible bindings, foreign or unknown
     * provenance and unreadable evidence are not evidence.
     */
    private fun isOlderRetainedEvidence(
        room: RecoveryRoomSnapshot,
        identity: RecoveryIdentity?,
        record: VaultSessionRecord,
    ): Boolean {
        if (identity == null) return false
        if (record.binding.origin != origin || record.binding.accountId != room.cachedAccountId) {
            return false
        }
        if (record.binding.sessionGeneration >= room.sessionGeneration) {
            return false
        }
        if (record.operationId == identity.operationId) {
            return false
        }
        return record.phase == VaultSessionPhase.ACTIVE || record.phase == VaultSessionPhase.PREPARED
    }

    private suspend fun requireEligibleSource(
        fence: Any,
        room: RecoveryRoomSnapshot,
        retained: String,
        source: VaultSnapshot,
        supplied: ServiceSession,
        suppliedOperation: String,
    ) {
        ensureFenceCurrent(fence)
        val record = source.record
        if (record == null) {
            return
        }
        // Synthesized source/pair errors route through the same in-owner
        // precedence mapper bound to the exact Room stage, so a concurrent
        // Room drift wins over a stale vault observation.
        suspend fun stageError(failure: SessionReauthenticationFailure): Nothing {
            throw precedence(fence, room, null, SessionReauthenticationException(failure))
        }
        if (record.binding.origin != origin || record.binding.accountId != retained) {
            stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
        }
        for (entry in record.supersededSessions.entries) {
            if (entry.record.binding.origin != origin || entry.record.binding.accountId != retained) {
                stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
            }
            if (supplied.refreshToken == entry.record.session.refreshToken) {
                stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
            }
        }
        if (supplied.refreshToken == record.session.refreshToken &&
            record.pendingRefresh != null
        ) {
            stageError(SessionReauthenticationFailure.INVALID_INPUT)
        }
        if (suppliedOperation == record.operationId) {
            stageError(SessionReauthenticationFailure.INVALID_INPUT)
        }
        val identity = room.identity
        when (room.phase) {
            RecoveryRoomPhase.ACTIVE -> {
                if (identity == null ||
                    record.binding != identity.binding ||
                    record.operationId != identity.operationId ||
                    (record.phase != VaultSessionPhase.ACTIVE && record.phase != VaultSessionPhase.PREPARED)
                ) {
                    stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                }
            }
            RecoveryRoomPhase.SIGNED_OUT -> {
                if (record.binding.sessionGeneration >= room.sessionGeneration) {
                    stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                }
                if (record.pendingRefresh == null &&
                    supplied.refreshToken == record.session.refreshToken
                ) {
                    stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                }
            }
            RecoveryRoomPhase.TRANSITIONING, RecoveryRoomPhase.PREPARING -> {
                if (identity == null) {
                    stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                }
                val exactTarget = record.binding == identity.binding &&
                    record.operationId == identity.operationId &&
                    record.phase == VaultSessionPhase.PREPARED
                val olderEvidence = isOlderRetainedEvidence(room, identity, record)
                if (!exactTarget && !olderEvidence) {
                    stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                }
                if (!exactTarget &&
                    record.pendingRefresh == null &&
                    record.binding.sessionGeneration < room.sessionGeneration &&
                    supplied.refreshToken == record.session.refreshToken
                ) {
                    stageError(SessionReauthenticationFailure.IDENTITY_MISMATCH)
                }
            }
        }
    }

    private fun computeArchive(
        source: VaultSnapshot,
        supplied: ServiceSession,
    ): VaultSupersededSessions {
        val record = source.record
        if (record == null) {
            return VaultSupersededSessions(emptyList())
        }
        val pending = record.pendingRefresh
        if (pending == null) {
            return record.supersededSessions
        }
        if (supplied.refreshToken == record.session.refreshToken) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val stripped = record.copy(supersededSessions = VaultSupersededSessions(emptyList()))
        val appended = try {
            VaultSupersededSession(source.revision, stripped)
        } catch (e: Exception) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        return try {
            VaultSupersededSessions(record.supersededSessions.entries + appended)
        } catch (e: Exception) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
    }

    private suspend fun requireNoArchivedReactivation(
        fence: Any,
        stage: RecoveryRoomSnapshot,
        archive: VaultSupersededSessions,
        supplied: ServiceSession,
    ) {
        ensureFenceCurrent(fence)
        for (entry in archive.entries) {
            if (supplied.refreshToken == entry.record.session.refreshToken) {
                throw precedence(
                    fence,
                    stage,
                    null,
                    SessionReauthenticationException(
                        SessionReauthenticationFailure.IDENTITY_MISMATCH,
                    ),
                )
            }
        }
    }

    /**
     * Alternating Room/vault/Room stability around a vault CAS that this
     * operation did not yet perform: every Room leg must exactly equal the
     * expected observation and every vault leg must hold the expected
     * revision with a semantically exact record (full grant plus pending
     * operation/key/body/token and archive equality on decoded values).
     * Vault revision/record drift maps to VAULT_CONFLICT, Room drift to
     * STALE_AUTHORITY. Ends with the exact local-root/provenance gate so a
     * root introduced during the final vault load cannot slip through.
     */
    private suspend fun verifyPreflightStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord?,
    ) {
        val retained = expectedRoom.cachedAccountId
            ?: throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        suspend fun stageError(failure: SessionReauthenticationFailure): Nothing {
            throw precedence(fence, expectedRoom, null, SessionReauthenticationException(failure))
        }
        ensureFenceCurrent(fence)
        val room = readAuthority(fence, expectedRoom)
        if (room != expectedRoom) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        ensureFenceCurrent(fence)
        val snapshot = loadVault(fence, expectedRoom)
        if (snapshot.revision != expectedVaultRevision ||
            !vaultSnapshotsSemanticEqual(snapshot.record, expectedRecord)
        ) {
            stageError(SessionReauthenticationFailure.VAULT_CONFLICT)
        }
        ensureFenceCurrent(fence)
        val reread = readAuthority(fence, expectedRoom)
        if (reread != expectedRoom) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        checkSameAccount(fence, expectedRoom, retained)
        ensureFenceCurrent(fence)
    }

    /**
     * Complete alternating Room/vault/Room/vault/Room stability around the
     * installed TRANSITIONING pair: Room legs pin the exact expected
     * observation, vault legs pin the expected revision with a semantically
     * exact record including archive bytes. Vault drift maps to
     * VAULT_CONFLICT, Room drift to STALE_AUTHORITY. The terminal exact
     * local-root/provenance gate refuses a Room or root change during the
     * final vault load instead of returning advisory success.
     */
    private suspend fun verifyTransitionStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) {
        val retained = expectedRoom.cachedAccountId
            ?: throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        suspend fun stageError(failure: SessionReauthenticationFailure): Nothing {
            throw precedence(fence, expectedRoom, null, SessionReauthenticationException(failure))
        }
        repeat(2) {
            ensureFenceCurrent(fence)
            val room = readAuthority(fence, expectedRoom)
            if (room != expectedRoom) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.STALE_AUTHORITY,
                )
            }
            ensureFenceCurrent(fence)
            val snapshot = loadVault(fence, expectedRoom)
            val record = snapshot.record
            if (snapshot.revision != expectedVaultRevision ||
                record == null ||
                !vaultRecordsSemanticEqual(record, expectedRecord)
            ) {
                stageError(SessionReauthenticationFailure.VAULT_CONFLICT)
            }
        }
        ensureFenceCurrent(fence)
        val reread = readAuthority(fence, expectedRoom)
        if (reread != expectedRoom) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        checkSameAccount(fence, expectedRoom, retained)
        ensureFenceCurrent(fence)
    }

    /**
     * Complete alternating Room/vault/Room/vault/Room stability around the
     * installed PREPARING pair, with the same fencing, drift mapping and
     * terminal local-root/provenance gate as the TRANSITIONING leg above.
     */
    private suspend fun verifyPreparedStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) {
        val retained = expectedRoom.cachedAccountId
            ?: throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        suspend fun stageError(failure: SessionReauthenticationFailure): Nothing {
            throw precedence(fence, expectedRoom, null, SessionReauthenticationException(failure))
        }
        repeat(2) {
            ensureFenceCurrent(fence)
            val room = readAuthority(fence, expectedRoom)
            if (room != expectedRoom) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.STALE_AUTHORITY,
                )
            }
            ensureFenceCurrent(fence)
            val snapshot = loadVault(fence, expectedRoom)
            val record = snapshot.record
            if (snapshot.revision != expectedVaultRevision ||
                record == null ||
                !vaultRecordsSemanticEqual(record, expectedRecord)
            ) {
                stageError(SessionReauthenticationFailure.VAULT_CONFLICT)
            }
        }
        ensureFenceCurrent(fence)
        val reread = readAuthority(fence, expectedRoom)
        if (reread != expectedRoom) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        checkSameAccount(fence, expectedRoom, retained)
        ensureFenceCurrent(fence)
    }

    private fun vaultSnapshotsSemanticEqual(
        actual: VaultSessionRecord?,
        expected: VaultSessionRecord?,
    ): Boolean {
        if (actual == null || expected == null) {
            return actual == null && expected == null
        }
        return vaultRecordsSemanticEqual(actual, expected)
    }

    /**
     * Closed in-owner error precedence for seam failures: caller
     * cancellation first, then the newest invocation fence, then non-owned
     * durable Room drift. Rereads current durable Room once, raw and without
     * wrapper recursion, against the tracked [before]/[successor] stage
     * tuple. An unchanged durable stage preserves the observed mapped
     * category (including corruption); a possible own uncertain commit or
     * any other drift wins as STALE_AUTHORITY, never success, and nothing
     * is compensated.
     */
    private suspend fun precedence(
        fence: Any,
        before: RecoveryRoomSnapshot?,
        successor: RecoveryRoomSnapshot?,
        mapped: SessionReauthenticationException,
    ): Nothing {
        currentCoroutineContext().ensureActive()
        ensureFenceCurrent(fence)
        if (before == null) {
            throw mapped
        }
        val reread: RecoveryRoomSnapshot = try {
            authority.read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
            )
        }
        currentCoroutineContext().ensureActive()
        ensureFenceCurrent(fence)
        if (reread == before) {
            throw mapped
        }
        if (successor != null && reread == successor) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        throw SessionReauthenticationException(
            SessionReauthenticationFailure.STALE_AUTHORITY,
        )
    }

    private suspend fun checkSameAccount(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        accountId: String,
    ) {
        ensureFenceCurrent(fence)
        try {
            authority.checkSameAccountRecovery(expected, origin, accountId)
            ensureFenceCurrent(fence)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            throw precedence(fence, expected, null, SessionReauthenticationException(mapAuthorityFailure(e.failure)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw precedence(
                fence,
                expected,
                null,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
                ),
            )
        }
    }

    private suspend fun checkSameAccountRaw(room: RecoveryRoomSnapshot, accountId: String) {
        currentCoroutineContext().ensureActive()
        try {
            authority.checkSameAccountRecovery(room, origin, accountId)
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.UNAVAILABLE)
        }
    }

    private suspend fun readAuthority(
        fence: Any,
        before: RecoveryRoomSnapshot? = null,
        successor: RecoveryRoomSnapshot? = null,
    ): RecoveryRoomSnapshot {
        ensureFenceCurrent(fence)
        try {
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            throw precedence(fence, before, successor, SessionReauthenticationException(mapAuthorityFailure(e.failure)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw precedence(
                fence,
                before,
                successor,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
                ),
            )
        }
    }

    private suspend fun loadVault(
        fence: Any,
        beforeRoom: RecoveryRoomSnapshot? = null,
    ): VaultSnapshot {
        ensureFenceCurrent(fence)
        try {
            val snapshot = vault.load()
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            throw precedence(fence, beforeRoom, null, SessionReauthenticationException(mapVaultFailure(e.failure)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw precedence(
                fence,
                beforeRoom,
                null,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.VAULT_UNAVAILABLE,
                ),
            )
        }
    }

    private suspend fun casVault(
        fence: Any,
        expectedRevision: Long,
        record: VaultSessionRecord?,
        beforeRoom: RecoveryRoomSnapshot? = null,
    ): VaultSnapshot {
        ensureFenceCurrent(fence)
        try {
            val snapshot = vault.compareAndSet(expectedRevision, record)
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            throw precedence(fence, beforeRoom, null, SessionReauthenticationException(mapVaultFailure(e.failure)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw precedence(
                fence,
                beforeRoom,
                null,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.VAULT_UNAVAILABLE,
                ),
            )
        }
    }

    private suspend fun beginReservation(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        accountId: String,
        operationId: String,
        successor: RecoveryRoomSnapshot,
    ): RecoveryRoomSnapshot {
        ensureFenceCurrent(fence)
        try {
            val reserved = authority.beginSameAccountRecovery(expected, origin, accountId, operationId)
            ensureFenceCurrent(fence)
            return reserved
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            throw precedence(fence, expected, successor, SessionReauthenticationException(mapAuthorityFailure(e.failure)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw precedence(
                fence,
                expected,
                successor,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
                ),
            )
        }
    }

    private suspend fun finishReservation(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        successor: RecoveryRoomSnapshot,
    ): RecoveryRoomSnapshot {
        ensureFenceCurrent(fence)
        try {
            val finalized = authority.finishReauthentication(expected)
            ensureFenceCurrent(fence)
            return finalized
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            throw precedence(fence, expected, successor, SessionReauthenticationException(mapAuthorityFailure(e.failure)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw precedence(
                fence,
                expected,
                successor,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
                ),
            )
        }
    }

    /**
     * Pure caller-input validation for prepare. Runs before the newest
     * invocation fence so invalid input never supersedes valid in-flight
     * work. Missing retained account, impossible private baseline,
     * reused/malformed operation and malformed UUIDs map to INVALID_INPUT; wrong-account grants and
     * foreign expected identity map to IDENTITY_MISMATCH.
     */
    private fun validatePrepareInput(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): String {
        val targetOperation = canonicalUuid(operationId)
        val sessionAccount = try {
            requireWorkoutUuid(session.account.accountId, "accountId")
        } catch (_: Exception) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val retained = expected.cachedAccountId
            ?: throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        if (expected.baselineComplete && expected.phase != RecoveryRoomPhase.ACTIVE) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (sessionAccount != retained) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        val presentOperation = expected.identity?.operationId
        if (presentOperation != null && targetOperation == presentOperation) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val identity = expected.identity
        if (identity != null && identity.binding.origin != origin) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        return targetOperation
    }

    /**
     * Pure caller-input validation for resume. Runs before the newest
     * invocation fence so invalid input never supersedes valid in-flight
     * work. Non-private phases, missing identity and baseline-complete
     * observations map to INVALID_INPUT; foreign identity or wrong-account
     * grants map to IDENTITY_MISMATCH.
     */
    private fun validateResumeInput(expected: RecoveryRoomSnapshot, session: ServiceSession) {
        val sessionAccount = try {
            requireWorkoutUuid(session.account.accountId, "accountId")
        } catch (_: Exception) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (expected.phase != RecoveryRoomPhase.TRANSITIONING &&
            expected.phase != RecoveryRoomPhase.PREPARING
        ) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val identity = expected.identity
        val retained = expected.cachedAccountId
        if (identity == null || retained == null || expected.baselineComplete) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (identity.binding.origin != origin ||
            identity.binding.accountId != retained ||
            sessionAccount != retained
        ) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
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
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.STALE_OPERATION,
                )
            }
        }
    }

    private fun canonicalUuid(value: String): String {
        try {
            return requireWorkoutUuid(value, "id")
        } catch (_: Exception) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
    }

    private fun mapAuthorityFailure(failure: RoomSessionAuthorityFailure): SessionReauthenticationFailure =
        when (failure) {
            RoomSessionAuthorityFailure.STALE_AUTHORITY -> SessionReauthenticationFailure.STALE_AUTHORITY
            RoomSessionAuthorityFailure.ACCOUNT_MISMATCH -> SessionReauthenticationFailure.IDENTITY_MISMATCH
            RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION ->
                SessionReauthenticationFailure.LOCAL_DATA_REQUIRES_DECISION
            RoomSessionAuthorityFailure.COUNTER_OVERFLOW ->
                SessionReauthenticationFailure.COUNTER_OVERFLOW
            RoomSessionAuthorityFailure.INVALID_INPUT -> SessionReauthenticationFailure.INVALID_INPUT
            RoomSessionAuthorityFailure.CORRUPT_AUTHORITY ->
                SessionReauthenticationFailure.AUTHORITY_CORRUPT
            RoomSessionAuthorityFailure.UNAVAILABLE ->
                SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE
        }

    private fun mapVaultFailure(failure: VaultFailure): SessionReauthenticationFailure =
        when (failure) {
            VaultFailure.CONFLICT -> SessionReauthenticationFailure.VAULT_CONFLICT
            VaultFailure.REVISION_EXHAUSTED -> SessionReauthenticationFailure.COUNTER_OVERFLOW
            VaultFailure.INVALID_RECORD,
            VaultFailure.UNREADABLE,
            VaultFailure.UNSUPPORTED_VERSION,
            VaultFailure.UNAVAILABLE,
            -> SessionReauthenticationFailure.VAULT_UNAVAILABLE
        }
}
