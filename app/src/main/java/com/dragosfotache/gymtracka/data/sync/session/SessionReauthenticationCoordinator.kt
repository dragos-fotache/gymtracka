package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
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
 * Inert settled occupied-credential reauthentication over one R09e Room
 * authority and one encrypted session vault. Replaces a matching established
 * ACTIVE vault grant with a supplied same-service-account grant, privately
 * reserves a new durable session fence, and recovers every local two-store
 * interruption. Preserves retained domain, canonical cache, queue, frozen
 * requests, checkpoints and stages. Reuses Room12, encrypted vault CAS and
 * the existing private PREPARING engine/completion eligibility.
 *
 * Returns only advisory nonsecret preparation observations, never application
 * authorization. Performs no app construction/visibility, ordinary repository
 * rebinding, Google/challenge/exchange/logout HTTP, credential freshness
 * scheduling, account switching, sign-out, legacy import/delete/reassignment,
 * projection/publication, completion, schema/migration, backend/configuration
 * or live account/device actions. R09f reconciliation replays an exact pending
 * attempt only through the supplied shared refresh coordinator and clears it
 * only with its complete returned grant. Never rekeys or rebinds an uncertain
 * predecessor; never reads a clock and
 * never adopts another service UUID's retained data.
 *
 * One application owner constructs one coordinator per physical Room/vault
 * pair with the shared [PushRunOwner] and the shared credential-selection
 * mutex. No independent refresh/transition owner is constructed. Lock order
 * is run owner then credential mutex, held continuously through all
 * reads/writes. Must not be called from a held owner/credential slot.
 * Construction is inert and performs no I/O. No cached success/admission
 * exists. Stops at inert PREPARING/PREPARED eligibility; never bootstraps,
 * publishes, promotes or activates.
 */
internal class SessionReauthenticationCoordinator(
    private val origin: ServiceOrigin,
    private val authority: SessionReauthenticationAuthority,
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
     * Starts settled occupied reauthentication from an ACTIVE expectation:
     * exact authority match, occupied ACTIVE predecessor preflight, one Room
     * reservation, one vault CAS of the full supplied grant as PREPARED,
     * then Room finalization to PREPARING with stable rereads of both
     * stores. A pending predecessor refuses before any write; a racing late
     * write after reservation stops preserved with no retry CAS; a CAS
     * conflict starts no finalization and performs no cleanup.
     */
    suspend fun reauthenticate(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): PreparedSessionObservation {
        currentCoroutineContext().ensureActive()
        validateStartInput(expected, operationId, session)
        val fence = openFence()
        return runOwner.mutex.withLock {
            credentialMutex.withLock {
                reauthenticateLocked(fence, expected, operationId, session)
            }
        }
    }

    /**
     * Resumes after a reservation or commit interruption under the exact
     * expected TRANSITIONING or PREPARING identity with the caller-supplied
     * same-account grant. Never reserves again. A settled ACTIVE predecessor
     * permits one exact-revision CAS then Room finalization; an installed
     * PREPARED target permits only Room finalization with its pending bytes
     * preserved; a PREPARING pair permits read-only idempotence. A wrong
     * supplied grant against an installed target refuses without replacement.
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
     * Inert exact-pending-refresh reconciliation over an exact established ACTIVE Room/ACTIVE vault pair,
     * before a separate R09e supplied-grant replacement. Reconciles a retained exact pending predecessor by
     * replaying its typed attempt ONCE through the supplied shared refresh coordinator, or proves a settled
     * pair stable with read-only rereads when no pending exists. Performs no Room writes, fresh selection or
     * rotation, clock/UUID calls, retries, adoption, reservation, replacement, or cleanup; expired grants are
     * preserved as returned. Returns only an advisory settled old-ACTIVE eligibility observation that R09e
     * must freshly read/admit again; never access credentials or reusable authority.
     *
     * The supplied refresh coordinator must share this coordinator's configured origin, referentially
     * identical vault, and referentially identical credential mutex; mismatch refuses INVALID_INPUT before
     * opening a new invocation fence, locks, or I/O, without superseding valid in-flight work. Lock order is
     * the shared DB run owner then the refresh coordinator's matching credential mutex (owned inside its
     * bounded replay helper), held continuously through preflight, exact refresh HTTP/CAS, and all final
     * rereads. A fresh scoped ACTIVE refresh lease carries a REQUIRED predicate rechecking the exact old
     * Room and current invocation around every seam; it retires at invocation settlement. Safe network
     * failures keep their accepted SyncApiException category/hints with pending retained; local faults map to
     * the closed reauthentication surface via in-lock Room/fence rereads under both owners, with caller
     * cancellation propagating before any reread. Must not be called from a held
     * owner/credential slot.
     */
    suspend fun reconcilePendingRefresh(
        expected: RecoveryRoomSnapshot,
        refreshCoordinator: SessionRefreshCoordinator,
    ): SettledSessionObservation {
        currentCoroutineContext().ensureActive()
        val identity = expected.identity
        if (expected.phase != RecoveryRoomPhase.ACTIVE ||
            identity == null ||
            !expected.baselineComplete
        ) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (identity.binding.origin != origin) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        if (!refreshCoordinator.isCompatibleRefreshOwner(origin, vault, credentialMutex)) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val fence = openFence()
        var latchedRoomFailure: RoomSessionAuthorityFailure? = null
        val lease = SessionRefreshLease(
            binding = identity.binding,
            operationId = identity.operationId,
            phase = VaultSessionPhase.ACTIVE,
            authorizationCheck = {
                val caller = currentCoroutineContext()
                caller.ensureActive()
                synchronized(stateLock) {
                    caller.ensureActive()
                    if (fence !== currentFence) {
                        throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
                    }
                }
                val authorized = try {
                    authority.read()
                } catch (e: CancellationException) {
                    caller.ensureActive()
                    throw e
                } catch (e: RoomSessionAuthorityException) {
                    caller.ensureActive()
                    synchronized(stateLock) {
                        if (fence !== currentFence) {
                            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
                        }
                    }
                    latchedRoomFailure = e.failure
                    throw SessionRefreshException(SessionRefreshFailure.UNAVAILABLE)
                } catch (_: Exception) {
                    caller.ensureActive()
                    synchronized(stateLock) {
                        if (fence !== currentFence) {
                            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
                        }
                    }
                    throw SessionRefreshException(SessionRefreshFailure.UNAVAILABLE)
                }
                caller.ensureActive()
                synchronized(stateLock) {
                    caller.ensureActive()
                    if (fence !== currentFence) {
                        throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
                    }
                }
                if (authorized != expected) {
                    throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
                }
            },
        )
        try {
            val settled = runOwner.mutex.withLock {
                refreshCoordinator.withExactPendingRefresh(
                    lease,
                    preflight = {
                        reconcilePreflightLocked(fence, expected, identity)
                    },
                    settled = { final ->
                        reconcileSettledLocked(fence, expected, identity, final)
                    },
                    failed = { error ->
                        // A strict Room fault observed at an authorization seam is preserved as the
                        // closed mapped error: the predicate latches only its category with no raw
                        // cause, and it is supplied here before the Room/fence mapping below. A
                        // predicate-observed old-Room mismatch stays STALE_SESSION and still refuses
                        // even if a later reread restores the expected snapshot. Cancellation, the
                        // newest fence, and current-Room drift keep precedence inside the mapper.
                        val effective = latchedRoomFailure?.let { failure ->
                            SessionReauthenticationException(mapAuthorityFailure(failure))
                        } ?: error
                        mapReconciliationErrorLocked(fence, expected, effective)
                    },
                )
            }
            ensureFenceCurrent(fence)
            return settled
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            throw reraiseAfterOwners(fence, e)
        } catch (e: SyncApiException) {
            currentCoroutineContext().ensureActive()
            throw reraiseAfterOwners(fence, e)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw reraiseAfterOwners(
                fence,
                SessionReauthenticationException(
                    SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
                ),
            )
        } finally {
            lease.invalidate()
        }
    }

    /**
     * Read-only restart classification. Non-TRANSITIONING/non-PREPARING Room
     * never probes the vault; other-origin identity never probes the vault.
     * Stable reservation-only state yields NeedsGrant; a stable installed
     * target yields NeedsFinalization; a stable PREPARING pair yields
     * Prepared. Cancellation and retired fences propagate as exceptions,
     * never Blocked. Performs no writes, reservations or HTTP.
     */
    suspend fun recover(): SessionReauthenticationRecovery {
        currentCoroutineContext().ensureActive()
        val fence = openFence()
        return runOwner.mutex.withLock {
            credentialMutex.withLock {
                recoverLocked(fence)
            }
        }
    }

    override fun toString(): String = "SessionReauthenticationCoordinator(redacted)"

    private fun validateStartInput(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ) {
        if (expected.phase != RecoveryRoomPhase.ACTIVE) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val identity = expected.identity
        if (identity == null || !expected.baselineComplete) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val targetOperation = try {
            requireWorkoutUuid(operationId, "id")
        } catch (_: Exception) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (targetOperation == identity.operationId) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (identity.binding.origin != origin) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        if (session.account.accountId != identity.binding.accountId) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
    }

    private fun validateResumeInput(
        expected: RecoveryRoomSnapshot,
        session: ServiceSession,
    ) {
        if (expected.phase != RecoveryRoomPhase.TRANSITIONING &&
            expected.phase != RecoveryRoomPhase.PREPARING
        ) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        val identity = expected.identity
        if (identity == null || expected.baselineComplete) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (identity.binding.origin != origin ||
            session.account.accountId != identity.binding.accountId
        ) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
    }

    private suspend fun reauthenticateLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        val targetOperation = canonicalUuid(operationId)
        val identity = expected.identity
        if (identity == null || !expected.baselineComplete) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (targetOperation == identity.operationId) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (identity.binding.origin != origin ||
            session.account.accountId != identity.binding.accountId
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        val retainedAccount = identity.binding.accountId
        val observed = readAuthority(fence)
        if (observed != expected) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        val source = loadVault(fence)
        val sourceRecord = source.record
        if (source.revision < 1L ||
            sourceRecord == null ||
            sourceRecord.phase != VaultSessionPhase.ACTIVE ||
            sourceRecord.binding != identity.binding ||
            sourceRecord.operationId != identity.operationId
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        if (sourceRecord.pendingRefresh != null) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.PENDING_REFRESH,
            )
        }
        if (expected.revision > Long.MAX_VALUE - 2L ||
            expected.sessionGeneration == Long.MAX_VALUE ||
            source.revision == Long.MAX_VALUE
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.COUNTER_OVERFLOW,
            )
        }
        verifyPreflightStable(fence, expected, source.revision, sourceRecord)
        ensureFenceCurrent(fence)
        val newRevision = expected.revision + 1L
        val newGeneration = expected.sessionGeneration + 1L
        val newBinding = ServiceSessionBinding(
            origin = origin,
            accountId = retainedAccount,
            sessionGeneration = newGeneration,
        )
        val requiredTransition = RecoveryRoomSnapshot(
            revision = newRevision,
            sessionGeneration = newGeneration,
            cachedAccountId = retainedAccount,
            phase = RecoveryRoomPhase.TRANSITIONING,
            identity = RecoveryIdentity(
                binding = newBinding,
                operationId = targetOperation,
            ),
            baselineComplete = false,
        )
        val reserved = beginReservation(fence, expected, targetOperation)
        if (reserved != requiredTransition) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        val rereadReserved = readAuthority(fence)
        if (rereadReserved != requiredTransition) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        ensureFenceCurrent(fence)
        val postReserve = loadVault(fence)
        val postReserveRecord = postReserve.record
        if (postReserveRecord?.pendingRefresh != null) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.PENDING_REFRESH,
            )
        }
        if (postReserve.revision != source.revision ||
            postReserveRecord == null ||
            !vaultRecordsEqual(postReserveRecord, sourceRecord)
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.VAULT_CONFLICT,
            )
        }
        ensureFenceCurrent(fence)
        val postReserveRoom = readAuthority(fence)
        if (postReserveRoom != requiredTransition) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        ensureFenceCurrent(fence)
        val intended = VaultSessionRecord(
            binding = newBinding,
            operationId = targetOperation,
            phase = VaultSessionPhase.PREPARED,
            session = session,
            pendingRefresh = null,
        )
        val committed = casVault(fence, source.revision, intended)
        val committedRecord = committed.record
        if (committed.revision != source.revision + 1L ||
            committedRecord == null ||
            !vaultRecordsEqual(committedRecord, intended)
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        verifyTransitionStable(fence, requiredTransition, committed.revision, intended)
        ensureFenceCurrent(fence)
        val finalized = finishReservation(fence, requiredTransition)
        val requiredPreparing = RecoveryRoomSnapshot(
            revision = newRevision + 1L,
            sessionGeneration = newGeneration,
            cachedAccountId = retainedAccount,
            phase = RecoveryRoomPhase.PREPARING,
            identity = RecoveryIdentity(
                binding = newBinding,
                operationId = targetOperation,
            ),
            baselineComplete = false,
        )
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
        if (identity == null || expected.baselineComplete) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
        if (identity.binding.origin != origin ||
            session.account.accountId != identity.binding.accountId
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        val current = readAuthority(fence)
        if (current != expected) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        val snapshot = loadVault(fence)
        if (expected.phase == RecoveryRoomPhase.PREPARING) {
            return resumePreparingLocked(fence, expected, identity, session, snapshot)
        }
        return resumeTransitioningLocked(fence, expected, identity, session, snapshot)
    }

    private suspend fun resumePreparingLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        identity: RecoveryIdentity,
        session: ServiceSession,
        snapshot: VaultSnapshot,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        val record = snapshot.record
        if (record == null ||
            record.binding != identity.binding ||
            record.operationId != identity.operationId ||
            record.phase != VaultSessionPhase.PREPARED ||
            record.session != session
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        verifyPreparedStable(fence, expected, snapshot.revision, record)
        ensureFenceCurrent(fence)
        return PreparedSessionObservation(expected, snapshot.revision)
    }

    private suspend fun resumeTransitioningLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        identity: RecoveryIdentity,
        session: ServiceSession,
        snapshot: VaultSnapshot,
    ): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        val record = snapshot.record
        if (record == null) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        if (record.phase == VaultSessionPhase.PREPARED &&
            record.binding == identity.binding &&
            record.operationId == identity.operationId
        ) {
            if (record.session != session) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
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
            val finalized = finishReservation(fence, expected)
            val requiredPreparing = RecoveryRoomSnapshot(
                revision = expected.revision + 1L,
                sessionGeneration = expected.sessionGeneration,
                cachedAccountId = expected.cachedAccountId,
                phase = RecoveryRoomPhase.PREPARING,
                identity = identity,
                baselineComplete = false,
            )
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
        if (record.phase == VaultSessionPhase.ACTIVE) {
            if (record.binding.origin != origin ||
                record.binding.accountId != identity.binding.accountId
            ) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
            }
            if (record.pendingRefresh != null) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.PENDING_REFRESH,
                )
            }
            if (snapshot.revision < 1L ||
                record.binding.sessionGeneration != identity.binding.sessionGeneration - 1L ||
                record.operationId == identity.operationId
            ) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
            }
            if (expected.revision == Long.MAX_VALUE || snapshot.revision == Long.MAX_VALUE) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.COUNTER_OVERFLOW,
                )
            }
            verifyPreflightStable(fence, expected, snapshot.revision, record)
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
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
            }
            verifyTransitionStable(fence, expected, committed.revision, intended)
            ensureFenceCurrent(fence)
            val finalized = finishReservation(fence, expected)
            val requiredPreparing = RecoveryRoomSnapshot(
                revision = expected.revision + 1L,
                sessionGeneration = expected.sessionGeneration,
                cachedAccountId = expected.cachedAccountId,
                phase = RecoveryRoomPhase.PREPARING,
                identity = identity,
                baselineComplete = false,
            )
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
        ensureFenceCurrent(fence)
        throw SessionReauthenticationException(
            SessionReauthenticationFailure.IDENTITY_MISMATCH,
        )
    }

    private suspend fun recoverLocked(fence: Any): SessionReauthenticationRecovery {
        ensureFenceCurrent(fence)
        val room = try {
            ensureFenceCurrent(fence)
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.Blocked(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.Blocked(
                SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
            )
        }
        if (room.phase != RecoveryRoomPhase.TRANSITIONING &&
            room.phase != RecoveryRoomPhase.PREPARING
        ) {
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.NotReauthenticating
        }
        val identity = room.identity
        if (identity == null || identity.binding.origin != origin) {
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.Blocked(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        val vaultSnapshot = try {
            ensureFenceCurrent(fence)
            val snapshot = vault.load()
            ensureFenceCurrent(fence)
            snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.Blocked(mapVaultFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.Blocked(
                SessionReauthenticationFailure.VAULT_UNAVAILABLE,
            )
        }
        try {
            return classifyRecoveryLocked(fence, room, identity, vaultSnapshot)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            if (e.failure == SessionReauthenticationFailure.STALE_OPERATION) {
                throw e
            }
            return SessionReauthenticationRecovery.Blocked(e.failure)
        }
    }

    private suspend fun classifyRecoveryLocked(
        fence: Any,
        room: RecoveryRoomSnapshot,
        identity: RecoveryIdentity,
        vaultSnapshot: VaultSnapshot,
    ): SessionReauthenticationRecovery {
        ensureFenceCurrent(fence)
        if (room.baselineComplete) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.AUTHORITY_CORRUPT,
            )
        }
        val record = vaultSnapshot.record
        if (room.phase == RecoveryRoomPhase.PREPARING) {
            if (record == null ||
                record.binding != identity.binding ||
                record.operationId != identity.operationId ||
                record.phase != VaultSessionPhase.PREPARED
            ) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
            }
            verifyPreparedStable(fence, room, vaultSnapshot.revision, record)
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.Prepared(
                PreparedSessionObservation(room, vaultSnapshot.revision),
            )
        }
        if (record == null) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        if (record.phase == VaultSessionPhase.PREPARED &&
            record.binding == identity.binding &&
            record.operationId == identity.operationId
        ) {
            verifyTransitionStable(fence, room, vaultSnapshot.revision, record)
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.NeedsFinalization(room, vaultSnapshot.revision)
        }
        if (record.phase == VaultSessionPhase.ACTIVE) {
            if (record.binding.origin != origin ||
                record.binding.accountId != identity.binding.accountId
            ) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
            }
            if (record.pendingRefresh != null) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.PENDING_REFRESH,
                )
            }
            if (vaultSnapshot.revision < 1L ||
                record.binding.sessionGeneration != identity.binding.sessionGeneration - 1L ||
                record.operationId == identity.operationId
            ) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
            }
            verifyPreflightStable(fence, room, vaultSnapshot.revision, record)
            ensureFenceCurrent(fence)
            return SessionReauthenticationRecovery.NeedsGrant(room)
        }
        ensureFenceCurrent(fence)
        throw SessionReauthenticationException(
            SessionReauthenticationFailure.IDENTITY_MISMATCH,
        )
    }

    // Preflight stability for a settled ACTIVE predecessor whose expected grant
    // carries no pending attempt. A late pending write landing during the
    // alternating rereads maps to PENDING_REFRESH before any revision/grant
    // mismatch classification; bytes are retained and no supplied-grant CAS
    // starts. Any other vault drift refuses without adoption.
    private suspend fun verifyPreflightStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) {
        repeat(2) {
            val room = readAuthority(fence)
            if (room != expectedRoom) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.STALE_AUTHORITY,
                )
            }
            val snapshot = loadVault(fence)
            val record = snapshot.record
            if (record?.pendingRefresh != null && expectedRecord.pendingRefresh == null) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.PENDING_REFRESH,
                )
            }
            if (snapshot.revision != expectedVaultRevision ||
                record == null ||
                !vaultRecordsEqual(record, expectedRecord)
            ) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
            }
        }
        val room = readAuthority(fence)
        if (room != expectedRoom) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
    }

    // One shared full alternating Room/vault/Room/vault/Room stable-pair
    // reread for an installed target, used both before finalization and at
    // the final PREPARING pair. Room drift refuses stale authority, vault
    // drift refuses without adoption; semantic pending comparison applies.
    private suspend fun verifyStablePair(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) {
        repeat(2) {
            val room = readAuthority(fence)
            if (room != expectedRoom) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.STALE_AUTHORITY,
                )
            }
            val snapshot = loadVault(fence)
            val record = snapshot.record
            if (snapshot.revision != expectedVaultRevision ||
                record == null ||
                !vaultRecordsEqual(record, expectedRecord)
            ) {
                ensureFenceCurrent(fence)
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.IDENTITY_MISMATCH,
                )
            }
        }
        val room = readAuthority(fence)
        if (room != expectedRoom) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
    }

    private suspend fun verifyTransitionStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) = verifyStablePair(fence, expectedRoom, expectedVaultRevision, expectedRecord)

    private suspend fun verifyPreparedStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) = verifyStablePair(fence, expectedRoom, expectedVaultRevision, expectedRecord)

    // Exact R09f preflight under both shared owners: actual Room must equal the full supplied expected
    // snapshot and the vault must hold a positive-revision occupied ACTIVE record with the exact old
    // binding and operation. Missing, empty, PREPARED, foreign, adjacent-generation, or orphan evidence
    // refuses IDENTITY_MISMATCH with no HTTP/CAS. Pending is preserved exactly for replay, never refused
    // here. Alternating rereads prove the exact revision, full grant/expiry/phase/binding/operation, and
    // semantic pending tuple stable before HTTP admission.
    private suspend fun reconcilePreflightLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        identity: RecoveryIdentity,
    ): VaultSnapshot {
        ensureFenceCurrent(fence)
        val room = readAuthority(fence)
        if (room != expected) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        val source = loadVault(fence)
        val sourceRecord = source.record
        if (source.revision < 1L ||
            sourceRecord == null ||
            sourceRecord.phase != VaultSessionPhase.ACTIVE ||
            sourceRecord.binding != identity.binding ||
            sourceRecord.operationId != identity.operationId
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        verifyStablePair(fence, expected, source.revision, sourceRecord)
        ensureFenceCurrent(fence)
        return source
    }

    // Final R09f proof under both shared owners: the supplied final vault snapshot must be a
    // positive-revision settled ACTIVE record with the exact old binding/operation and no pending
    // predecessor. Alternating rereads prove the unchanged old Room and the exact installed vault before
    // the advisory return. Never compensates, reinstalls, rekeys, or clears.
    private suspend fun reconcileSettledLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        identity: RecoveryIdentity,
        final: VaultSnapshot,
    ): SettledSessionObservation {
        ensureFenceCurrent(fence)
        val finalRecord = final.record
        if (final.revision < 1L ||
            finalRecord == null ||
            finalRecord.phase != VaultSessionPhase.ACTIVE ||
            finalRecord.binding != identity.binding ||
            finalRecord.operationId != identity.operationId ||
            finalRecord.pendingRefresh != null
        ) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.IDENTITY_MISMATCH,
            )
        }
        verifyStablePair(fence, expected, final.revision, finalRecord)
        ensureFenceCurrent(fence)
        return SettledSessionObservation(expected, final.revision)
    }

    // Synchronous fence precedence for errors already mapped inside both owners: a retired newest fence
    // surfaces STALE_OPERATION without any I/O; anything else propagates unchanged. Performs no Room
    // rereads; the in-lock mapper below already owns those.
    private fun reraiseAfterOwners(fence: Any, error: Throwable): Nothing {
        synchronized(stateLock) {
            if (fence !== currentFence) {
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.STALE_OPERATION,
                )
            }
        }
        throw error
    }

    // Closed R09f error mapping running inside both shared owners with caller/fence/Room precedence:
    // caller cancellation propagates unchanged before any Room reread, then a retired newest fence maps
    // to STALE_OPERATION, then the fenced readAuthority reread must equal the full expected snapshot or
    // map to STALE_AUTHORITY. A failed reread keeps its strict mapped fault instead of the triggering
    // error. With a current fence and unchanged Room, safe SyncApiException failures keep their accepted
    // category/hints with pending retained; refresh STALE_SESSION maps to IDENTITY_MISMATCH, refresh
    // UNAVAILABLE to AUTHORITY_UNAVAILABLE, INVALID_INPUT stays INVALID_INPUT; strict Room and vault
    // faults reuse the R09e mappings; raw faults map closed with no secret material. A strict Room fault
    // latched at an authorization seam arrives through the failed callback already mapped, so it survives
    // a good reread with strictness intact.
    private suspend fun mapReconciliationErrorLocked(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        error: Throwable,
    ): Nothing {
        if (error is CancellationException) {
            currentCoroutineContext().ensureActive()
            throw error
        }
        val caller = currentCoroutineContext()
        caller.ensureActive()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.STALE_OPERATION,
                )
            }
        }
        val currentRoom = readAuthority(fence)
        if (currentRoom != expected) {
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.STALE_AUTHORITY,
            )
        }
        when (error) {
            is SessionReauthenticationException -> throw error
            is SyncApiException -> throw error
            is SessionRefreshException -> throw SessionReauthenticationException(
                when (error.failure) {
                    SessionRefreshFailure.STALE_SESSION ->
                        SessionReauthenticationFailure.IDENTITY_MISMATCH
                    SessionRefreshFailure.UNAVAILABLE ->
                        SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE
                    SessionRefreshFailure.INVALID_INPUT ->
                        SessionReauthenticationFailure.INVALID_INPUT
                    SessionRefreshFailure.ACCESS_UNAVAILABLE ->
                        SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE
                },
            )
            is SessionVaultException -> throw SessionReauthenticationException(
                mapVaultFailure(error.failure),
            )
            is RoomSessionAuthorityException -> throw SessionReauthenticationException(
                mapAuthorityFailure(error.failure),
            )
            else -> throw SessionReauthenticationException(
                SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
            )
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
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
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
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(mapVaultFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.VAULT_UNAVAILABLE,
            )
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
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(mapVaultFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.VAULT_UNAVAILABLE,
            )
        }
    }

    private suspend fun beginReservation(
        fence: Any,
        expected: RecoveryRoomSnapshot,
        operationId: String,
    ): RecoveryRoomSnapshot {
        ensureFenceCurrent(fence)
        try {
            val reserved = authority.beginReauthentication(expected, origin, operationId)
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
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
            )
        }
    }

    private suspend fun finishReservation(
        fence: Any,
        expected: RecoveryRoomSnapshot,
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
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
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

    private fun vaultRecordsEqual(
        actual: VaultSessionRecord,
        expected: VaultSessionRecord,
    ): Boolean {
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
