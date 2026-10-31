package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PullRecoveryReason
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryResult
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportCaptureBarrier
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportCaptureResult
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportException
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportFailure
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPhase
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPolicy
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgressPhase
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportStore
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportTarget
import com.dragosfotache.gymtracka.data.sync.model.DURABLE_PAYLOAD_VERSION
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StalePushClaimException
import com.dragosfotache.gymtracka.data.sync.store.checkCanonicalFences
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStorageException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-facing activation and offline recovery. Composes Room authority, preparation,
 * retained-account recovery, bootstrap, completion and startup/refresh. Matching established
 * accounts reopen offline; recoverable delivery stops preserve cached edits and exact uncertain
 * requests.
 *
 * Construction starts PrivacyLocked and performs no I/O. The account startup factory supplies
 * shared database/vault ownership and required real transition/publication barriers. Displays are
 * fixed and redact grants, credentials, request bytes and raw causes.
 *
 * [recover] returns closed Private classifications. [acceptGrant] and [completeActivation] report
 * private preparation/progress and admit Active only after authorized completion. Refused inputs
 * and dependency failures throw [AppSessionException]; exact snapshot mismatch, invalidation or
 * dead handles throw [AppSessionStaleException]. Caller cancellation is rethrown. Early refusal
 * preserves a healthy handle; once reservation starts, failures leave the affected operation
 * private until explicit recovery. Exceptions do not establish durable rollback.
 */
internal class AppSessionCoordinator(
    private val database: GymDatabase,
    private val origin: ServiceOrigin?,
    private val vault: SessionVault,
    private val authApi: ServiceAuthApi,
    private val transport: ServiceHttpTransport,
    private val runOwner: PushRunOwner,
    private val transitionBarrier: AppSessionTransitionBarrier,
    private val publicationBarrier: ProjectionPublicationBarrier,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
    private val legacyImportPolicy: LegacyImportPolicy? = null,
) : ForegroundGrantHandoff {
    private val authority = RoomSessionAuthority(database)
    private val credentialMutex = Mutex()
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private var currentFence: Any = Any()
    private var currentHandle: AppAccountSession? = null
    private var currentRecovered: RecoveredSession? = null
    private val issuedRecoveries = mutableSetOf<AccountRecoveryConfirmation>()
    private val _state = MutableStateFlow<AppSessionState>(AppSessionState.PrivacyLocked)

    private val preparation: SessionPreparationCoordinator? =
        if (origin != null) SessionPreparationCoordinator(origin, authority, vault, credentialMutex) else null
    private val sameAccount: SameAccountRecoveryCoordinator? =
        if (origin != null) SameAccountRecoveryCoordinator(origin, authority, vault, runOwner, credentialMutex) else null
    private val preparedEngine: PreparedSessionEngineCoordinator? =
        if (origin != null) {
            PreparedSessionEngineCoordinator(
                database, origin, vault, authApi, transport, runOwner,
                clockMillis, uuidProvider, credentialMutex,
            )
        } else {
            null
        }
    private val recovery = SessionRecoveryCoordinator(
        origin, vault, authority, authApi, clockMillis, uuidProvider, credentialMutex,
    )

    val state: StateFlow<AppSessionState> = _state.asStateFlow()

    /**
     * Synchronously fences all work: rotates the facade fence, marks the
     * current handle stale, kills all subordinate permits/leases/handles and
     * publishes PrivacyLocked immediately, all under one short state
     * monitor. Never waits for operation/credential/run-owner mutexes,
     * network or drains. Already-started writes may commit durably; dead
     * work cannot install new state, report success or expose data.
     */
    fun invalidate() {
        synchronized(stateLock) {
            currentFence = Any()
            currentHandle?.markStale()
            currentHandle = null
            currentRecovered = null
            issuedRecoveries.clear()
            _state.value = AppSessionState.PrivacyLocked
            preparation?.invalidate()
            sameAccount?.invalidate()
            preparedEngine?.invalidate()
            recovery.invalidate()
        }
    }

    /**
     * Configured recovery via existing startup reconciliation. Only matching
     * ACTIVE Room/vault with complete baseline admits a new handle, with no
     * HTTP/clock/UUID. Unconfigured returns Private(CONFIGURATION_UNAVAILABLE,
     * null) with no Room/vault/clock/UUID/HTTP. A retained handle revalidates
     * rather than rebinding; revoked/mismatched authority locks it and every
     * authority error fails closed to PrivacyLocked, never a stale Active.
     */
    suspend fun recover(): AppSessionState {
        currentCoroutineContext().ensureActive()
        val observedFence = synchronized(stateLock) { currentFence }
        if (origin == null) {
            return publishIfCurrent(
                AppSessionState.Private(AppSessionReason.CONFIGURATION_UNAVAILABLE, null),
                observedFence,
            )
        }
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            val retained = synchronized(stateLock) { currentHandle }
            if (retained != null) {
                try {
                    retained.checkCurrent()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    return@withLock synchronized(stateLock) { _state.value }
                }
                ensureCurrent(observedFence)
                if (!verifyAdmissionEvidence(observedFence)) {
                    lockIfAlive(observedFence, retained.aliveToken())
                    return@withLock synchronized(stateLock) { _state.value }
                }
                ensureCurrent(observedFence)
                return@withLock synchronized(stateLock) {
                    val current = _state.value as? AppSessionState.Active
                    if (current != null && current.session === retained) {
                        current
                    } else {
                        _state.value
                    }
                }
            }
            ensureCurrent(observedFence)
            val first = try {
                recovery.reconcile()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                return@withLock publishIfCurrent(
                    AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                    observedFence,
                )
            }
            ensureCurrent(observedFence)
            val activeSession = first as? SessionRecoveryState.Active
            if (activeSession != null) {
                return@withLock admitActiveIfEvidencePermits(activeSession.session, observedFence)
            }
            val engine = preparedEngine
            if (engine != null && first is SessionRecoveryState.Locked &&
                first.reason == SessionRecoveryReason.TRANSITION_REQUIRED
            ) {
                try {
                    engine.recoverCompletion()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                }
                ensureCurrent(observedFence)
                val second = try {
                    recovery.reconcile()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    return@withLock publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                }
                ensureCurrent(observedFence)
                val secondActive = second as? SessionRecoveryState.Active
                if (secondActive != null) {
                    return@withLock admitActiveIfEvidencePermits(secondActive.session, observedFence)
                }
                return@withLock publishPrivateForRecovery(second, observedFence)
            }
            return@withLock publishPrivateForRecovery(first, observedFence)
        }
    }

    /**
     * centralized admission evidence gate. Returns true when no
     * migration exists or retained evidence fully revalidates (ACTIVE
     * rooms additionally require definitive settlement); false on any
     * missing/corrupt/partial evidence. Callers maintain the
     * process/handle privacy lock on false and never admit Active.
     */
    private suspend fun verifyAdmissionEvidence(fence: Any): Boolean {
        ensureCurrent(fence)
        val fenceCheck: () -> Unit = {
            synchronized(stateLock) {
                if (fence !== currentFence) throw AppSessionStaleException()
            }
        }
        val store = LegacyImportStore(
            database,
            LegacyImportCaptureBarrier { throw LegacyImportException(com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportFailure.UNAVAILABLE) },
            fenceCheck,
            uuidProvider,
        )
        return try {
            val progress = store.observeAny()
            ensureCurrent(fence)
            if (progress != null && progress.phase != LegacyImportProgressPhase.SETTLED) {
                val current = try {
                    authority.read()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    return false
                }
                if (current.phase == RecoveryRoomPhase.ACTIVE) return false
            }
            true
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            false
        }
    }

    /**
     * Centralized Active admission: fresh-checks retained evidence before
     * installing the handle. Evidence fault publishes Private without
     * admission, preserving the process/handle privacy lock.
     */
    private suspend fun admitActiveIfEvidencePermits(
        recovered: RecoveredSession,
        fence: Any,
    ): AppSessionState {
        ensureCurrent(fence)
        if (!verifyAdmissionEvidence(fence)) {
            return publishIfCurrent(
                AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                fence,
            )
        }
        ensureCurrent(fence)
        return admitActive(recovered, fence)
    }

    /**
     * Accepts a caller-captured exact expected snapshot, fresh canonical
     * operation UUID and authoritative service grant. Cached UUID/origin and
     * reserved-operation compatibility validate BEFORE transition acquisition,
     * so foreign/operation refusals preserve a healthy handle. The transition
     * pause/drain is acquired while the previous handle is still valid; only
     * after lease validation is old admission invalidated and locked to
     * PrivacyLocked under the same lock, then expectation freshly rechecked
     * and reservation performed. Admission is held through reservation; a
     * failed drain throws UNAVAILABLE and reserves nothing. The acquired
     * lease always closes exactly once on every path. Successful preparation
     * returns Private evidence, never Active. Delayed grants against changed
     * expectations cannot replace a newer session.
     */
    suspend fun acceptGrant(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): AppSessionState {
        currentCoroutineContext().ensureActive()
        val targetOperation = try {
            requireWorkoutUuid(operationId, "id")
        } catch (_: Exception) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        val observedFence = synchronized(stateLock) { currentFence }
        return acceptGrantWithFence(expected, targetOperation, session, observedFence)
    }

    /**
     * Foreground grant handoff for one facade-captured request. Refuses a
     * missing/foreign owner binding, an origin different from the facade
     * configuration, or an invalid intent/context/operation before any
     * drain or reservation. Carries the captured facade fence into the
     * shared critical section without recapturing it, so an invalidation
     * between Google/exchange and handoff fails even when the durable Room
     * snapshot is unchanged. A repeated private same-account sign-in uses a
     * fresh recovery reservation, retaining uncertain predecessor evidence;
     * it never compares the new grant with exact saved-session credentials.
     * Direct [acceptGrant] still resumes only the exact saved private grant.
     * Every guard/lease/error path below is shared with [acceptGrant].
     * Real consumer refusals propagate unchanged.
     */
    override suspend fun acceptForegroundGrant(
        request: ForegroundSignInRequest,
        session: ServiceSession,
    ): AppSessionState {
        currentCoroutineContext().ensureActive()
        val binding = request.authorityBinding
        if (binding == null || binding.owner !== this) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        val configured = origin
            ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
        if (request.origin != configured) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        val targetOperation = try {
            canonicalForegroundOperation(
                configured,
                request.intent,
                request.expected,
                request.operationId,
            )
        } catch (_: ForegroundSignInContextInvalidException) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        } catch (_: Exception) {
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        if (request.intent == ForegroundSignInIntent.PRIVATE_IMPORT_REAUTHENTICATION) {
            return reauthenticateWithFence(request.expected, targetOperation, session, binding.fence)
        }
        return acceptGrantWithFence(
            request.expected,
            targetOperation,
            session,
            binding.fence,
            freshPrivateSession = request.intent == ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION &&
                (request.expected.phase == RecoveryRoomPhase.PREPARING ||
                    request.expected.phase == RecoveryRoomPhase.TRANSITIONING),
        )
    }

    /**
     * opt-in private-import progress observation. Strictly validates
     * retained original source/entries/progress/current lineage under
     * configured process authority; null means no migration, never a
     * fabricated empty migration. Performs no HTTP, UUID generation,
     * credential changes or cleanup. Unconfigured refuses
     * CONFIGURATION_UNAVAILABLE without migration reads; missing/corrupt
     * evidence throws closed UNAVAILABLE.
     */
    suspend fun observeLegacyImport(): LegacyImportProgress? {
        currentCoroutineContext().ensureActive()
        val observedFence = synchronized(stateLock) { currentFence }
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            val configured = origin
                ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            ensureCurrent(observedFence)
            val fenceCheck: () -> Unit = {
                synchronized(stateLock) {
                    if (observedFence !== currentFence) throw AppSessionStaleException()
                }
            }
            val store = LegacyImportStore(
                database,
                LegacyImportCaptureBarrier { throw LegacyImportException(com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportFailure.UNAVAILABLE) },
                fenceCheck,
                uuidProvider,
            )
            val retained = try {
                store.retainedTarget()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionStaleException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: LegacyImportException) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            if (retained != null && retained.origin != configured) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
            val progress = try {
                store.observeAny()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionStaleException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: LegacyImportException) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            progress
        }
    }

    /**
     * explicit private-import reauthentication. Requires opt-in, a
     * valid retained captured/settled import, same configured origin and
     * service UUID, the exact current private PREPARING expectation and a
     * NEW operation distinct from current. Composes existing same-account
     * prepare/supersede; retains original migration operation, source, IDs,
     * requests, receipts and consent. Fresh supplied grants are service
     * evidence, not Google assertions.
     */
    suspend fun reauthenticateLegacyImport(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): AppSessionState {
        currentCoroutineContext().ensureActive()
        val targetOperation = try {
            requireWorkoutUuid(operationId, "id")
        } catch (_: Exception) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        val observedFence = synchronized(stateLock) { currentFence }
        return reauthenticateWithFence(expected, targetOperation, session, observedFence)
    }

    /**
     * Synchronous no-I/O factory binding one foreground request to this
     * facade. Uses the actual configured origin and records the opaque
     * exact owner/process fence under the state monitor. Unconfigured
     * capture throws CONFIGURATION_UNAVAILABLE; invalid
     * intent/context/operation throws IDENTITY_MISMATCH. Performs no
     * Room/vault reads, launches no Google UI, calls no HTTP and changes
     * no observable authority.
     */
    fun captureForegroundSignInRequest(
        intent: ForegroundSignInIntent,
        expected: RecoveryRoomSnapshot,
        operationId: String,
    ): ForegroundSignInRequest {
        val configured: ServiceOrigin?
        val fence: Any
        synchronized(stateLock) {
            configured = origin
            fence = currentFence
        }
        val bound = configured
            ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
        val canonical = try {
            canonicalForegroundOperation(bound, intent, expected, operationId)
        } catch (_: ForegroundSignInContextInvalidException) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        } catch (_: Exception) {
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        return ForegroundSignInRequest.bound(
            bound,
            intent,
            expected,
            canonical,
            ForegroundSignInAuthorityBinding(this, fence),
        )
    }

    /**
     * private-import reauthentication critical section over the exact
     * supplied fence. Shares the operation mutex and captured process fence
     * discipline with [acceptGrantWithFence]: every guard, lease and error
     * path runs under the same serialized exclusion.
     */
    private suspend fun reauthenticateWithFence(
        expected: RecoveryRoomSnapshot,
        targetOperation: String,
        session: ServiceSession,
        observedFence: Any,
    ): AppSessionState {
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            val configured = origin
                ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            if (legacyImportPolicy == null) {
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            if (expected.phase != RecoveryRoomPhase.PREPARING) {
                throw AppSessionException(AppSessionReason.PREPARATION_REQUIRED)
            }
            val identity = expected.identity
                ?: throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            if (identity.operationId == targetOperation) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
            ensureCurrent(observedFence)
            val reread = try {
                authority.read()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            if (reread != expected) {
                throw AppSessionStaleException()
            }
            val grantAccount = try {
                requireWorkoutUuid(session.account.accountId, "accountId")
            } catch (_: Exception) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
            if (expected.cachedAccountId == null || grantAccount != expected.cachedAccountId) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
            if (identity.binding.origin != configured ||
                identity.binding.accountId != grantAccount
            ) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
            ensureCurrent(observedFence)
            val fenceCheck: () -> Unit = {
                synchronized(stateLock) {
                    if (observedFence !== currentFence) throw AppSessionStaleException()
                }
            }
            val legacyStore = LegacyImportStore(
                database,
                LegacyImportCaptureBarrier { throw LegacyImportException(com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportFailure.UNAVAILABLE) },
                fenceCheck,
                uuidProvider,
            )
            val evidence = try {
                legacyStore.readForReauth()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionStaleException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            if (evidence.target.origin != configured ||
                evidence.target.accountId != grantAccount ||
                evidence.target.accountId != expected.cachedAccountId
            ) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
            if (evidence.phase != LegacyImportPhase.CAPTURED &&
                evidence.phase != LegacyImportPhase.SETTLED
            ) {
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            if (evidence.room != expected) {
                throw AppSessionStaleException()
            }
            ensureCurrent(observedFence)
            val previous = synchronized(stateLock) { currentHandle }
            val previousSession = previous?.let {
                RepositorySession(it.identity.binding.accountId, it.identity.binding.sessionGeneration)
            }
            val lease = try {
                transitionBarrier.acquire(previousSession)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            var outcome: PreparedSessionObservation? = null
            var failure: Throwable? = null
            try {
                try {
                    lease.checkValid()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                ensureCurrent(observedFence)
                invalidateForReplacement(observedFence)
                val fresh = try {
                    authority.read()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                ensureCurrent(observedFence)
                if (fresh != expected) {
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionStaleException()
                }
                try {
                    lease.checkValid()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                outcome = try {
                    val retained = sameAccount
                        ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
                    try {
                        retained.prepare(expected, targetOperation, session)
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    } catch (e: SessionReauthenticationException) {
                        currentCoroutineContext().ensureActive()
                        throw mapReauthFailure(e)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AppSessionStaleException) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, advisoryRoom()),
                        observedFence,
                    )
                    throw e
                } catch (e: AppSessionException) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(e.reason, advisoryRoom()),
                        observedFence,
                    )
                    throw e
                }
                try {
                    lease.checkValid()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                ensureCurrent(observedFence)
            } catch (e: CancellationException) {
                failure = e
            } catch (e: Throwable) {
                failure = e
            }
            try {
                lease.close()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (failure == null) {
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
            }
            currentCoroutineContext().ensureActive()
            failure?.let { throw it }
            val prepared = outcome ?: throw AppSessionException(AppSessionReason.UNAVAILABLE)
            ensureCurrent(observedFence)
            val freshRoom = try {
                authority.read()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                publishIfCurrent(
                    AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, prepared.room),
                    observedFence,
                )
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            return@withLock publishIfCurrent(
                AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, freshRoom),
                observedFence,
            )
        }
    }

    /**
     * Common acceptGrant critical section over the exact supplied fence.
     * The original three-argument entry supplies the latest fence; the
     * foreground handoff supplies its captured fence. Every guard, lease
     * and error path below is unchanged.
     */
    private suspend fun acceptGrantWithFence(
        expected: RecoveryRoomSnapshot,
        targetOperation: String,
        session: ServiceSession,
        observedFence: Any,
        freshPrivateSession: Boolean = false,
    ): AppSessionState {
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            if (origin == null) {
                throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            }
            val configured = origin
            val reread = try {
                authority.read()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            if (reread != expected) {
                throw AppSessionStaleException()
            }
            // Pre-acquisition compatibility: foreign grants, origin drift and
            // reused/new operations refuse here, before any drain or
            // invalidation, so a healthy Active handle is preserved untouched.
            validateGrantCompatibility(expected, targetOperation, session, configured, freshPrivateSession)
            ensureCurrent(observedFence)
            // Retained signed-out provenance uses the existing pure read-only
            // authority check before any drain or supersession.
            if (expected.phase == RecoveryRoomPhase.SIGNED_OUT && expected.cachedAccountId != null) {
                try {
                    authority.checkSameAccountRecovery(expected, configured, expected.cachedAccountId)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (e: RoomSessionAuthorityException) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    throw when (e.failure) {
                        RoomSessionAuthorityFailure.STALE_AUTHORITY -> AppSessionStaleException()
                        RoomSessionAuthorityFailure.ACCOUNT_MISMATCH ->
                            AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
                        RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION ->
                            AppSessionException(AppSessionReason.LOCAL_DATA_REQUIRES_DECISION)
                        else -> AppSessionException(AppSessionReason.UNAVAILABLE)
                    }
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
            }
            ensureCurrent(observedFence)
            val previous = synchronized(stateLock) { currentHandle }
            val previousSession = previous?.let {
                RepositorySession(it.identity.binding.accountId, it.identity.binding.sessionGeneration)
            }
            val lease = try {
                transitionBarrier.acquire(previousSession)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            var outcome: PreparedSessionObservation? = null
            var failure: Throwable? = null
            try {
                try {
                    lease.checkValid()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                ensureCurrent(observedFence)
                invalidateForReplacement(observedFence)
                val fresh = try {
                    authority.read()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                ensureCurrent(observedFence)
                if (fresh != expected) {
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionStaleException()
                }
                try {
                    lease.checkValid()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                outcome = try {
                    legacyCaptureOutcomeIfOptIn(
                        expected, targetOperation, session, configured, lease, observedFence,
                    ) ?: reserveGrant(expected, targetOperation, session, observedFence, freshPrivateSession)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AppSessionStaleException) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, advisoryRoom()),
                        observedFence,
                    )
                    throw e
                } catch (e: AppSessionException) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(e.reason, advisoryRoom()),
                        observedFence,
                    )
                    throw e
                }
                try {
                    lease.checkValid()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                ensureCurrent(observedFence)
            } catch (e: CancellationException) {
                failure = e
            } catch (e: Throwable) {
                failure = e
            }
            // Guaranteed exactly-once close on every path, including true
            // caller cancellation: cancellation dominates, a close failure
            // fails closed with no success, and no raw causes are attached.
            try {
                lease.close()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (failure == null) {
                    ensureCurrent(observedFence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        observedFence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
            }
            // Caller-cancellation precedence: an actual caller cancellation
            // landing after an injected cancellation/fault dominates it.
            currentCoroutineContext().ensureActive()
            failure?.let { throw it }
            val prepared = outcome ?: throw AppSessionException(AppSessionReason.UNAVAILABLE)
            ensureCurrent(observedFence)
            val freshRoom = try {
                authority.read()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                publishIfCurrent(
                    AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, prepared.room),
                    observedFence,
                )
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            return@withLock publishIfCurrent(
                AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, freshRoom),
                observedFence,
            )
        }
    }

    /**
     * pre-delivery consent seam. Validates migration again; when an
     * original active import lacks durable consent, invokes the required
     * consent seam under exact facade/process/current authority BEFORE any
     * prepared delivery. False (or refusal/cancellation/stale) returns
     * false with zero remote effects; stale/canceled/error responses never
     * record consent. True records the exact original active mutation
     * consent durably under the unchanged binding with fresh rereads.
     * No active import means no warning call (true).
     */
    private suspend fun requestLegacyImportConsentIfNeeded(
        expected: RecoveryRoomSnapshot,
        fence: Any,
    ): Boolean {
        ensureCurrent(fence)
        val fenceCheck: () -> Unit = {
            synchronized(stateLock) {
                if (fence !== currentFence) throw AppSessionStaleException()
            }
        }
        val store = LegacyImportStore(
            database,
            LegacyImportCaptureBarrier { throw LegacyImportException(com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportFailure.UNAVAILABLE) },
            fenceCheck,
            uuidProvider,
        )
        val request = try {
            store.consentRequestForActive()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        ensureCurrent(fence)
        if (request == null) return true
        val policy = legacyImportPolicy
            ?: return false
        val confirmed = try {
            policy.replacementConsent.confirm(request)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            return false
        }
        ensureCurrent(fence)
        if (!confirmed) return false
        val current = try {
            authority.read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        ensureCurrent(fence)
        if (current != expected) {
            throw AppSessionStaleException()
        }
        try {
            store.recordActiveConsent(request.target, request.mutationId, expected)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            val reread = try {
                authority.read()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            if (reread != expected) throw AppSessionStaleException()
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        ensureCurrent(fence)
        return true
    }

    /**
     * post-tail settlement validation. Requires complete matching
     * receipts when a migration exists (SETTLED advisory progress);
     * returns true when no migration or fully settled, false for partial
     * settlement. Corrupt evidence throws closed UNAVAILABLE.
     */
    private suspend fun verifyLegacyImportSettled(fence: Any): Boolean {
        ensureCurrent(fence)
        val fenceCheck: () -> Unit = {
            synchronized(stateLock) {
                if (fence !== currentFence) throw AppSessionStaleException()
            }
        }
        val store = LegacyImportStore(
            database,
            LegacyImportCaptureBarrier { throw LegacyImportException(com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportFailure.UNAVAILABLE) },
            fenceCheck,
            uuidProvider,
        )
        val progress = try {
            store.observeAny()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        ensureCurrent(fence)
        if (progress == null) return true
        return progress.phase == LegacyImportProgressPhase.SETTLED
    }

    /**
     * Privately bootstraps the exact PREPARING expectation with the supplied
     * bounded budget and restart flag, then completes with the mandatory
     * publication barrier. A wrong completion phase throws
     * PREPARATION_REQUIRED. Completion is attempted only after an actual
     * valid bootstrap tail (no retained stage, bounded push Empty, pull Tail,
     * no outstanding work); any typed bootstrap/tail/retained-stage/budget
     * stop returns Private without further completion HTTP and never
     * auto-launches another tail. Only actual COMPLETED plus fresh startup
     * reconciliation supplies Active.
     */
    suspend fun completeActivation(
        expected: RecoveryRoomSnapshot,
        stageId: String,
        maxSnapshotPages: Int = 100,
        restartRetainedStage: Boolean = false,
        connectionRestored: Boolean = false,
    ): AppSessionState {
        currentCoroutineContext().ensureActive()
        try {
            requireWorkoutUuid(stageId, "stageId")
        } catch (_: Exception) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        if (maxSnapshotPages < 1) {
            throw IllegalArgumentException("maxSnapshotPages must be positive")
        }
        val observedFence = synchronized(stateLock) { currentFence }
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            if (origin == null) {
                throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            }
            val engine = preparedEngine
                ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            val reread = try {
                authority.read()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            if (reread != expected) {
                throw AppSessionStaleException()
            }
            if (expected.phase != RecoveryRoomPhase.PREPARING) {
                throw AppSessionException(AppSessionReason.PREPARATION_REQUIRED)
            }
            if (!requestLegacyImportConsentIfNeeded(expected, observedFence)) {
                return@withLock publishIfCurrent(
                    AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, advisoryRoom()),
                    observedFence,
                )
            }
            val bootstrap = try {
                engine.bootstrap(
                    stageId, maxSnapshotPages, restartRetainedStage = restartRetainedStage,
                    connectionRestored = connectionRestored,
                )
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: PreparedEngineException) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw mapEngineFailure(e)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            val tail = bootstrap.delivery
            val tailClean = !bootstrap.retainedStage &&
                tail != null &&
                tail.push.stop is PushDeliveryStop.Empty &&
                tail.pull?.stop is PullDeliveryStop.Tail &&
                tail.hasOutstandingLocalWork == false
            if (!tailClean) {
                return@withLock publishIfCurrent(
                    AppSessionState.Private(
                        AppSessionReason.PREPARATION_REQUIRED,
                        advisoryRoom(),
                        retryAtMillis = (tail?.push?.stop as? PushDeliveryStop.Deferred)?.retryAfterMillis,
                    ),
                    observedFence,
                )
            }
            if (!verifyLegacyImportSettled(observedFence)) {
                return@withLock publishIfCurrent(
                    AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, advisoryRoom()),
                    observedFence,
                )
            }
            val completion = try {
                engine.completePreparation(publicationBarrier)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: PreparedEngineException) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw mapEngineFailure(e)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            if (completion.status != PreparedCompletionStatus.COMPLETED) {
                return@withLock publishIfCurrent(
                    AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, advisoryRoom()),
                    observedFence,
                )
            }
            val reconciled = try {
                recovery.reconcile()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            val activeSession = reconciled as? SessionRecoveryState.Active
                ?: return@withLock publishIfCurrent(
                    AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, advisoryRoom()),
                    observedFence,
                )
            return@withLock admitActiveIfEvidencePermits(activeSession.session, observedFence)
        }
    }

    /**
     * Bounded safe reconnect over the exact current handle. Requires the exact
     * handle plus read-only authority before effects; reuses the shared run
     * owner and recovered refresh machinery with frozen preflight; validates
     * limits before credential work. Transient/reauth stops preserve editing
     * and update only delivery status. Missing/mismatched/dead authority
     * locks, never adopts another account. Caller cancellation is rethrown,
     * never a successful delivery. Never publishes a projection; cursor/
     * retained stops stay typed recovery obligations with a conservative
     * retry status. The returned typed stop is preserved.
     */
    suspend fun reconnect(
        session: AppAccountSession,
        expectedCacheEpoch: Long,
        maxPushMutations: Int = 100,
        maxPullPages: Int = 100,
        pullPageLimit: Int = 100,
    ): SyncDeliveryResult {
        currentCoroutineContext().ensureActive()
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        if (maxPushMutations !in 1..100 || maxPullPages !in 1..100 || pullPageLimit !in 1..200) {
            throw IllegalArgumentException("Invalid reconnect budgets")
        }
        val observedFence = synchronized(stateLock) { currentFence }
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            if (origin == null) {
                throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            }
            val current = synchronized(stateLock) { currentHandle }
            if (current == null || current !== session) {
                throw AppSessionStaleException()
            }
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(observedFence)
            val recovered = synchronized(stateLock) { currentRecovered }
                ?: throw AppSessionStaleException()
            val facadeCheck: () -> Unit = {
                if (!current.isAlive() || !isFacadeCurrent(observedFence, current)) {
                    throw AppSessionStaleException()
                }
            }
            val api = RecoveredSessionGymSyncApi(recovered, transport, facadeCheck)
            val binding = current.identity.binding
            val store = PushStore(
                database,
                RepositorySession(binding.accountId, binding.sessionGeneration),
                runOwner,
                facadeCheck,
            )
            val driver = SyncDeliveryLoop(store, api, clockMillis)
            val result = try {
                driver.deliver(expectedCacheEpoch, maxPushMutations, maxPullPages, pullPageLimit)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionStaleException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SessionVaultException) {
                // Credential binding fault: lock the current handle before
                // the closed return, so no later use can adopt it.
                currentCoroutineContext().ensureActive()
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: SessionRefreshException) {
                currentCoroutineContext().ensureActive()
                if (e.failure == SessionRefreshFailure.ACCESS_UNAVAILABLE) {
                    // Bounded usable-access failure with otherwise retained
                    // valid identity: conservative retry status and closed
                    // UNAVAILABLE, without cleanup.
                    ensureCurrent(observedFence)
                    updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: SessionRecoveryException) {
                currentCoroutineContext().ensureActive()
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: SyncApiException.ReauthenticationRequired) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED)
                throw e
            } catch (e: SyncApiException) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw e
            } catch (_: Exception) {
                // Unrelated store/epoch failures: revalidate the Room handle
                // first. An actual authority failure has already locked via
                // the fault callback and surfaces stale; otherwise a closed
                // UNAVAILABLE with retry status, never a fake stale.
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                try {
                    current.checkCurrent()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionStaleException()
                }
                ensureCurrent(observedFence)
                updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            ensureCurrent(observedFence)
            // Post-delivery read-only check: a typed stop that hides a
            // stale/invalidated lease still fails closed here.
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(observedFence)
            updateDeliveryStatus(current, observedFence, statusFor(result))
            result
        }
    }

    /**
     * bounded recovery read: observes the selected account's current cache epoch
     * for the exact current handle. RecoveryRoomSnapshot carries no cache epoch and
     * materialized-only observation cannot recover dirty progress after a
     * committed-but-cancelled/lost baseline/publication result; this read supplies that
     * epoch without requiring UI callers to query DAOs.
     *
     * Read-only and fenced: reuses the existing facade operation mutex with exact-handle
     * pre/post read-only authority, process and caller-cancellation guards around one
     * fenced Room read transaction. Requires a complete baseline with nonnegative
     * cache/materialized counters (materialized <= cache) under Active account/session
     * fences, then repeats exact handle/process authority after dispatch. Dirty complete
     * cache and queued intent remain eligible. Performs no HTTP, UUID/clock work,
     * engine/run admission, write/publication/cleanup, stage restart, handle rebinding
     * or new visibility. The returned epoch is advisory progress evidence, never a grant.
     *
     * Closed classification: binding drift revokes exactly the affected handle as stale;
     * missing/incomplete baseline or closed read failures report UNAVAILABLE with the
     * healthy handle preserved; a stale epoch observation is never reported as a revoked
     * handle. Caller cancellation always propagates.
     */
    suspend fun observeCacheEpoch(session: AppAccountSession): Long {
        currentCoroutineContext().ensureActive()
        val observedFence = synchronized(stateLock) { currentFence }
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            if (origin == null) {
                throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            }
            val current = synchronized(stateLock) { currentHandle }
            if (current == null || current !== session) {
                throw AppSessionStaleException()
            }
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(observedFence)
            val binding = current.identity.binding
            val facadeCheck: () -> Unit = {
                if (!current.isAlive() || !isFacadeCurrent(observedFence, current)) {
                    throw AppSessionStaleException()
                }
            }
            try {
                facadeCheck()
                val epoch = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    facadeCheck()
                    val device = database.syncDao().getDeviceState()
                    val actualAccountId = device?.activeAccountId
                    val actualGeneration = device?.sessionGeneration ?: 0L
                    if (actualAccountId != binding.accountId ||
                        actualGeneration != binding.sessionGeneration ||
                        device?.cachedAccountId != binding.accountId
                    ) {
                        throw StaleSessionException(
                            binding.accountId,
                            binding.sessionGeneration,
                            actualAccountId,
                            actualGeneration,
                        )
                    }
                    val cache = database.syncCanonicalDao().getAccountCache(binding.accountId)
                    if (cache == null || !cache.baselineComplete) {
                        throw AppSessionException(AppSessionReason.UNAVAILABLE)
                    }
                    if (cache.cacheEpoch < 0 || cache.materializedEpoch < 0 ||
                        cache.materializedEpoch > cache.cacheEpoch
                    ) {
                        throw CanonicalStorageException()
                    }
                    currentCoroutineContext().ensureActive()
                    facadeCheck()
                    cache.cacheEpoch
                }
                currentCoroutineContext().ensureActive()
                facadeCheck()
                try {
                    current.checkCurrent()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionStaleException()
                }
                ensureCurrent(observedFence)
                epoch
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionStaleException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionException) {
                // Closed refusal revalidates authority/process first: a concurrent
                // invalidation behind missing/incomplete metadata must surface stale,
                // never a falsely healthy unavailable.
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                try {
                    current.checkCurrent()
                } catch (e2: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e2
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionStaleException()
                }
                ensureCurrent(observedFence)
                throw e
            } catch (e: StaleSessionException) {
                currentCoroutineContext().ensureActive()
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                try {
                    current.checkCurrent()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionStaleException()
                }
                ensureCurrent(observedFence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
        }
    }

    /**
     * cold read-only selected-account metadata observation for presentation.
     * No HTTP/UUID/clock/cleanup/admission. Each initial/invalidation reread and
     * return/emission requires exact current live handle/process and transactional
     * Room authority/baseline checks. Observes sync_outbox, sync_state and
     * account/device/authority metadata needed for fencing; registered before the
     * initial read. Counts all selected-account outbox rows, including
     * frozen/rejected; retained error is rejected or stored error presence, never
     * raw code/message. Last-success comes only from selected sync_state;
     * negative/inconsistent data closes to fixed UNAVAILABLE, never normalization.
     * Scalar metadata reads plus strict validation of rejected recovery candidates only.
     * Checks caller cancellation
     * and synchronous exact currentness before emission; old status never binds
     * to a new handle. Foreign metadata remains private. Presentation only.
     */
    fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus> = flow {
        currentCoroutineContext().ensureActive()
        val observedFence = synchronized(stateLock) { currentFence }
        if (origin == null) {
            throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
        }
        val admitted = synchronized(stateLock) { currentHandle }
        if (admitted == null || admitted !== session) {
            throw AppSessionStaleException()
        }
        ensureCurrent(observedFence)
        try {
            session.checkCurrent()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(observedFence)
        val signals = Channel<Unit>(Channel.CONFLATED)
        val observer = object : InvalidationTracker.Observer(
            arrayOf(
                "sync_outbox",
                "sync_state",
                "sync_device_state",
                "sync_account_cache",
                "sync_session_authority",
            ),
        ) {
            override fun onInvalidated(tables: Set<String>) {
                signals.trySend(Unit)
            }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            emit(readSyncStatusOnce(session, observedFence))
            while (true) {
                try {
                    signals.receive()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                }
                currentCoroutineContext().ensureActive()
                val live = _state.value
                if (live !is AppSessionState.Active || live.session !== session) {
                    throw AppSessionStaleException()
                }
                ensureCurrent(observedFence)
                emit(readSyncStatusOnce(session, observedFence))
            }
        } finally {
            try {
                database.invalidationTracker.removeObserver(observer)
            } catch (_: Exception) {
            }
            try {
                signals.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun readSyncStatusOnce(
        session: AppAccountSession,
        fence: Any,
    ): AccountSyncStatus {
        currentCoroutineContext().ensureActive()
        ensureCurrent(fence)
        val current = synchronized(stateLock) { currentHandle }
        if (current == null || current !== session) {
            throw AppSessionStaleException()
        }
        try {
            current.checkCurrent()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        val binding = current.identity.binding
        val facadeCheck: () -> Unit = {
            if (!current.isAlive() || !isFacadeCurrent(fence, current)) {
                throw AppSessionStaleException()
            }
        }
        try {
            facadeCheck()
            val status = database.withTransaction {
                currentCoroutineContext().ensureActive()
                facadeCheck()
                // Full ready journal/device/cache tuple, transactionally
                // coherent: strict ACTIVE journal with the ready device tuple
                // plus the selected-account cache row.
                val ready = try {
                    authority.readActiveReadyInTransaction()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RoomSessionAuthorityException) {
                    when (e.failure) {
                        RoomSessionAuthorityFailure.STALE_AUTHORITY,
                        RoomSessionAuthorityFailure.ACCOUNT_MISMATCH,
                        -> throw StaleSessionException(
                            binding.accountId,
                            binding.sessionGeneration,
                            null,
                            0L,
                        )
                        else -> throw AppSessionException(AppSessionReason.UNAVAILABLE)
                    }
                }
                currentCoroutineContext().ensureActive()
                facadeCheck()
                // Exact admission identity/revision: the coherent journal
                // observation must still equal the handle's admitted room.
                if (ready.snapshot != session.room) {
                    throw StaleSessionException(
                        binding.accountId,
                        binding.sessionGeneration,
                        ready.snapshot.identity?.binding?.accountId,
                        ready.snapshot.sessionGeneration,
                    )
                }
                // Coherent selected-account cache: complete baseline with
                // sane epochs. Anything else fails closed unavailable.
                val cache = ready.cache
                if (cache == null || !cache.baselineComplete) {
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                if (cache.accountId != binding.accountId ||
                    cache.cacheEpoch < 0 || cache.materializedEpoch < 0 ||
                    cache.materializedEpoch > cache.cacheEpoch
                ) {
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                val pending = database.syncDao().countOutboxForAccount(binding.accountId)
                if (pending < 0) {
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                val retained = database.syncDao().hasRetainedOutboxError(binding.accountId)
                // Storage-type validation in the same transaction: a typed
                // getter may coerce nonnumeric TEXT/REAL/BLOB to 0 and
                // fabricate epoch-zero success, so only absent row/SQL null
                // or stored integer >= 0 is accepted. Anything else refuses
                // closed UNAVAILABLE with no normalization or cleanup.
                val lastStorageType = try {
                    database.syncDao().getLastSuccessStorageType(binding.accountId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                val lastRaw = if (lastStorageType == null || lastStorageType == "null") {
                    null
                } else if (lastStorageType == "integer") {
                    val stored = try {
                        database.syncDao().getLastSuccessMillis(binding.accountId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        throw AppSessionException(AppSessionReason.UNAVAILABLE)
                    }
                    if (stored == null || stored < 0) {
                        throw AppSessionException(AppSessionReason.UNAVAILABLE)
                    }
                    stored
                } else {
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                currentCoroutineContext().ensureActive()
                facadeCheck()
                AccountSyncStatus(
                    pending,
                    lastRaw,
                    retained,
                    database.syncDao().getRejectedOutboxForAccount(binding.accountId)
                        .any(::isDefinitiveOrdinaryRejection),
                )
            }
            currentCoroutineContext().ensureActive()
            facadeCheck()
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(fence)
            val live = _state.value
            if (live !is AppSessionState.Active || live.session !== session) {
                throw AppSessionStaleException()
            }
            return status
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionException) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            try {
                current.checkCurrent()
            } catch (e2: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e2
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(fence)
            throw e
        } catch (e: StaleSessionException) {
            currentCoroutineContext().ensureActive()
            lockIfAlive(fence, current.aliveToken())
            throw AppSessionStaleException()
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(fence)
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
    }

    /**
     * explicit ordinary-recovery preparation over the exact current handle. Returns an
     * opaque process-local confirmation when a validated definitive ordinary rejected head
     * with a validated pending/rejected remainder is safely confirmable, or null when there
     * is nothing to confirm or recovery would be unsafe. Never freezes or sends a previously
     * pending successor to obtain the decision: only an already-frozen uncertain head is
     * settled unchanged through existing push machinery, respecting durable backoff and
     * retained errors. Accepted settlement acknowledges the exact original result; rejected
     * settlement retains the original rejection. No generic HTTP error establishes definitive
     * rejection, and unsettleable malformed/corrupt/unsupported/oversize evidence refuses
     * unchanged. Performs no pull, snapshot or cleanup during preparation. Original import
     * rows/source/progress/receipts are never discard targets, so corrupt/incomplete/
     * non-settled import evidence refuses; fully settled retained legacy evidence never blocks
     * later ordinary edits. The full ordered remainder is captured under a fresh real
     * pause/drain lease which always closes before returning; no network or Room transaction
     * spans the user's decision. Errors are existing closed categories; caller cancellation
     * is always rethrown.
     */
    suspend fun prepareOrdinaryRecovery(session: AppAccountSession): AccountRecoveryConfirmation? =
        (prepareOrdinaryRecoveryOutcome(session) as? OrdinaryRecoveryPreparation.Prepared)?.confirmation

    /** Typed presentation outcome; all existing capture and confirmation fences remain authoritative. */
    suspend fun prepareOrdinaryRecoveryOutcome(session: AppAccountSession): OrdinaryRecoveryPreparation {
        currentCoroutineContext().ensureActive()
        val observedFence = synchronized(stateLock) { currentFence }
        // Fresh epoch read outside the operation mutex (observeCacheEpoch owns it). Entry
        // fences revalidate inside the admitted run; drift fails closed with no effects.
        val epoch = try {
            observeCacheEpoch(session)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionException) {
            currentCoroutineContext().ensureActive()
            return OrdinaryRecoveryPreparation.Failed
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return OrdinaryRecoveryPreparation.Failed
        }
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            if (origin == null) {
                throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            }
            val current = synchronized(stateLock) { currentHandle }
            if (current == null || current !== session) {
                throw AppSessionStaleException()
            }
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(observedFence)
            val recovered = synchronized(stateLock) { currentRecovered }
                ?: throw AppSessionStaleException()
            val binding = current.identity.binding
            val facadeCheck: () -> Unit = {
                if (!current.isAlive() || !isFacadeCurrent(observedFence, current)) {
                    throw AppSessionStaleException()
                }
            }
            val store = PushStore(
                database,
                RepositorySession(binding.accountId, binding.sessionGeneration),
                runOwner,
                facadeCheck,
            )
            val api = RecoveredSessionGymSyncApi(recovered, transport, facadeCheck)
            // One uninterrupted run-owner admission spans frozen settlement and fenced
            // capture: the run owner is never released between them, so no concurrent run
            // can stage, publish or advance the epoch mid-preparation.
            return@withLock try {
                store.withRun(epoch) { run ->
                    val settled = settleFrozenHeadForRecovery(run, store, api, binding.accountId)
                    ensureCurrent(observedFence)
                    val remaining = database.syncDao().getOutboxForAccount(binding.accountId)
                    val rejected = remaining.filter { it.state == "rejected" }
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    facadeCheck()
                    if (!settled) {
                        OrdinaryRecoveryPreparation.Blocked
                    } else if (rejected.isEmpty()) {
                        if (remaining.any { it.state != "pending" || it.errorCode != null ||
                                it.errorMessage != null || it.mutationType == "aggregate.import"
                            }
                        ) {
                            OrdinaryRecoveryPreparation.Blocked
                        } else {
                            OrdinaryRecoveryPreparation.NothingEligible
                        }
                    } else if (rejected.none(::isDefinitiveOrdinaryRejection)) {
                        OrdinaryRecoveryPreparation.Blocked
                    } else if (!preflightOrdinaryRecovery(observedFence, current, run, epoch)) {
                        // Advisory refusal: no validated definitive ordinary remainder, so the
                        // real editor/timer drain below never starts.
                        ensureCurrent(observedFence)
                        OrdinaryRecoveryPreparation.Blocked
                    } else {
                        withRecoveryLease(
                            observedFence,
                            RepositorySession(binding.accountId, binding.sessionGeneration),
                        ) { lease ->
                            lease.checkValid()
                            ensureCurrent(observedFence)
                            // Strict import-permit view through the accepted seam (own
                            // transaction; no Room transaction is held here).
                            val progress = try {
                                observeOrdinaryLegacyProof(observedFence)
                            } catch (e: CancellationException) {
                                currentCoroutineContext().ensureActive()
                                throw e
                            } catch (_: Exception) {
                                currentCoroutineContext().ensureActive()
                                ensureCurrent(observedFence)
                                return@withRecoveryLease OrdinaryRecoveryPreparation.Blocked
                            }
                            if (progress != null && progress.phase != LegacyImportProgressPhase.SETTLED) {
                                return@withRecoveryLease OrdinaryRecoveryPreparation.Blocked
                            }
                            val confirmation = captureOrdinaryRecoveryTx(
                                observedFence,
                                current,
                                run,
                                epoch,
                                lease,
                                progress,
                            )
                            if (confirmation == null) {
                                return@withRecoveryLease OrdinaryRecoveryPreparation.Blocked
                            }
                            lease.checkValid()
                            ensureCurrent(observedFence)
                            currentCoroutineContext().ensureActive()
                            synchronized(stateLock) {
                                if (observedFence !== currentFence) {
                                    throw AppSessionStaleException()
                                }
                                issuedRecoveries.add(confirmation)
                            }
                            // Final dispatch checks: the registered proof survives them.
                            currentCoroutineContext().ensureActive()
                            ensureCurrent(observedFence)
                            facadeCheck()
                            OrdinaryRecoveryPreparation.Prepared(confirmation)
                        }
                    }
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionStaleException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SyncApiException.ReauthenticationRequired) {
                currentCoroutineContext().ensureActive()
                updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED)
                throw e
            } catch (e: SessionVaultException) {
                currentCoroutineContext().ensureActive()
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: SessionRefreshException) {
                currentCoroutineContext().ensureActive()
                if (e.failure == SessionRefreshFailure.ACCESS_UNAVAILABLE) {
                    ensureCurrent(observedFence)
                    return@withLock OrdinaryRecoveryPreparation.Failed
                }
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: SessionRecoveryException) {
                currentCoroutineContext().ensureActive()
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: StaleSessionException) {
                // Entry/epoch drift without authority evidence: refuse without the
                // permanent privacy lock; a fresh decision rereads the live epoch.
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                return@withLock OrdinaryRecoveryPreparation.Blocked
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                return@withLock OrdinaryRecoveryPreparation.Failed
            }
        }
    }

    /**
     * confirmed ordinary rejection recovery. Discards only the captured exact ordinary
     * rows, resets the selected cursor to null while preserving recorded last-success, marks
     * the projection dirty, then reloads the complete cloud snapshot on the same admitted run
     * with existing snapshot/tail/publication machinery (never a second facade/run admission
     * and never the public reconcile entry, which would deadlock the held mutex/owner).
     * The complete captured rows/authority/epoch/legacy proof are compared immediately before
     * destructive SQL; a new/coalesced edit, changed row, different handle/stage or
     * repeated/foreign token refuses with all remaining intent intact. There is never frozen
     * work silently abandoned, even if injected after preparation. Newly admitted edits stay
     * protected and are never included in the discard. Failure/cancellation after the
     * committed discard keeps the old visible graph with the durable null-cursor marker, so
     * restart requires a complete snapshot and never publishes old canonical state as success.
     */
    suspend fun recoverOrdinaryRejection(
        session: AppAccountSession,
        confirmation: AccountRecoveryConfirmation,
    ): AppReconciliationResult {
        currentCoroutineContext().ensureActive()
        val observedFence = synchronized(stateLock) { currentFence }
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            if (origin == null) {
                throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            }
            val current = synchronized(stateLock) { currentHandle }
            if (current == null || current !== session) {
                throw AppSessionStaleException()
            }
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(observedFence)
            val binding = current.identity.binding
            if (confirmation.accountId != binding.accountId ||
                confirmation.sessionGeneration != binding.sessionGeneration
            ) {
                throw AppSessionStaleException()
            }
            // Opaque owner/handle/fence/registry binding: a foreign facade token with
            // identical durable account/room/rows still refuses here.
            currentCoroutineContext().ensureActive()
            if (confirmation.owner !== this) {
                throw AppSessionStaleException()
            }
            if (confirmation.fence !== observedFence || confirmation.handle !== current) {
                throw AppSessionStaleException()
            }
            if (confirmation.consumed || confirmation.rows.isEmpty()) {
                throw AppSessionStaleException()
            }
            val registered = synchronized(stateLock) {
                confirmation.fence === currentFence &&
                    currentHandle === current &&
                    currentHandle === confirmation.handle &&
                    issuedRecoveries.contains(confirmation)
            }
            if (!registered) {
                throw AppSessionStaleException()
            }
            synchronized(stateLock) {
                issuedRecoveries.remove(confirmation)
            }
            confirmation.consumed = true
            val recovered = synchronized(stateLock) { currentRecovered }
                ?: throw AppSessionStaleException()
            val facadeCheck: () -> Unit = {
                if (!current.isAlive() || !isFacadeCurrent(observedFence, current)) {
                    throw AppSessionStaleException()
                }
            }
            val store = PushStore(
                database,
                RepositorySession(binding.accountId, binding.sessionGeneration),
                runOwner,
                facadeCheck,
            )
            val api = RecoveredSessionGymSyncApi(recovered, transport, facadeCheck)
            run {
                val result = try {
                    store.withRun(confirmation.cacheEpoch) { run ->
                        // Compare and discard under one fresh drain lease; the helper
                        // closes it exactly once before any network below.
                        withRecoveryLease(
                            observedFence,
                            RepositorySession(binding.accountId, binding.sessionGeneration),
                        ) { lease ->
                            compareOrdinaryRecovery(observedFence, current, confirmation)
                            ensureCurrent(observedFence)
                            if (run.readSnapshotStage() != null) {
                                throw AppSessionStaleException()
                            }
                            run.discardExactOrdinaryRows(
                                confirmation.rows,
                                confirmation.room,
                                confirmation.legacyRaw,
                            ) { lease.checkValid() }
                            ensureCurrent(observedFence)
                        }
                        ensureCurrent(observedFence)
                        val stageId = try {
                            uuidProvider()
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            throw e
                        } catch (_: Exception) {
                            currentCoroutineContext().ensureActive()
                            throw AppSessionException(AppSessionReason.UNAVAILABLE)
                        }
                        try {
                            requireWorkoutUuid(stageId, "stageId")
                        } catch (_: Exception) {
                            throw AppSessionException(AppSessionReason.UNAVAILABLE)
                        }
                        val syncDriver = SyncDeliveryLoop(store, api, clockMillis)
                        val snapshotDriver = SnapshotDeliveryLoop(store, api, clockMillis)
                        runSnapshotChain(
                            run = run,
                            store = store,
                            syncDriver = syncDriver,
                            snapshotDriver = snapshotDriver,
                            handle = current,
                            fence = observedFence,
                            facadeCheck = facadeCheck,
                            stageId = stageId,
                            maxPushMutations = 100,
                            maxPullPages = 100,
                            pullPageLimit = 100,
                            maxSnapshotPages = 100,
                            snapshotPageLimit = 200,
                        )
                    }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (e: AppSessionStaleException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (e: AppSessionException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (e: SessionVaultException) {
                    currentCoroutineContext().ensureActive()
                    lockIfAlive(observedFence, current.aliveToken())
                    throw AppSessionStaleException()
                } catch (e: SessionRefreshException) {
                    currentCoroutineContext().ensureActive()
                    if (e.failure == SessionRefreshFailure.ACCESS_UNAVAILABLE) {
                        ensureCurrent(observedFence)
                        updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                        throw AppSessionException(AppSessionReason.UNAVAILABLE)
                    }
                    lockIfAlive(observedFence, current.aliveToken())
                    throw AppSessionStaleException()
                } catch (e: SessionRecoveryException) {
                    currentCoroutineContext().ensureActive()
                    lockIfAlive(observedFence, current.aliveToken())
                    throw AppSessionStaleException()
                } catch (e: SyncApiException.ReauthenticationRequired) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED)
                    throw e
                } catch (e: SyncApiException) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    throw e
                } catch (e: StalePushClaimException) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionStaleException()
                } catch (e: StaleSessionException) {
                    currentCoroutineContext().ensureActive()
                    lockIfAlive(observedFence, current.aliveToken())
                    throw AppSessionStaleException()
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent(observedFence)
                    try {
                        current.checkCurrent()
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw AppSessionStaleException()
                    }
                    ensureCurrent(observedFence)
                    updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                currentCoroutineContext().ensureActive()
                try {
                    current.checkCurrent()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionStaleException()
                }
                ensureCurrent(observedFence)
                result
            }
        }
    }

    /**
     * Settles only an already-frozen uncertain head through existing single-head push
     * machinery. Returns true when validation may proceed (empty queue, pending/rejected
     * head, or freshly settled frozen head). Returns false on backoff deferral, retained
     * errors, auth stops or any unsettleable evidence. Never freezes or sends a previously
     * pending successor. No pull, snapshot or cleanup runs here.
     */
    private suspend fun settleFrozenHeadForRecovery(
        run: com.dragosfotache.gymtracka.data.sync.store.PushRun,
        store: PushStore,
        api: RecoveredSessionGymSyncApi,
        accountId: String,
    ): Boolean {
        // A retained snapshot stage excludes ordinary recovery until explicit restart.
        if (run.readSnapshotStage() != null) {
            return false
        }
        val rows = database.syncDao().getOutboxForAccount(accountId)
        currentCoroutineContext().ensureActive()
        val head = rows.firstOrNull() ?: return true
        if (head.state != "frozen" || head.errorCode != null || head.errorMessage != null) {
            return true
        }
        val result = PushDeliveryLoop(store, api, clockMillis).deliver(run, 1)
        currentCoroutineContext().ensureActive()
        return when (val stop = result.stop) {
            is PushDeliveryStop.Empty -> true
            is PushDeliveryStop.LimitReached -> true
            is PushDeliveryStop.Blocked -> stop.reason == PushBlockReason.REJECTED
            is PushDeliveryStop.ReauthenticationRequired -> throw SyncApiException.ReauthenticationRequired()
            else -> false
        }
    }

    /**
     * Acquires one fresh real pause/drain lease for recovery work and closes it exactly once.
     * Close is never swallowed into a success return: a non-cancellation close fault after a
     * successful block fails closed instead of returning success, and caller cancellation
     * always dominates. A block failure propagates unchanged (never a silent success).
     */
    private suspend fun <T> withRecoveryLease(
        fence: Any,
        session: RepositorySession,
        block: suspend (ProjectionPublicationLease) -> T,
    ): T {
        ensureCurrent(fence)
        val lease = try {
            transitionBarrier.acquire(session)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        val result: T
        try {
            result = block(lease)
        } catch (e: CancellationException) {
            try {
                lease.close()
            } catch (_: Exception) {
            }
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: Throwable) {
            val closeError = try {
                lease.close()
                null
            } catch (ce: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw ce
            } catch (ce: Exception) {
                ce
            }
            if (closeError != null) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(fence)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            throw e
        }
        try {
            lease.close()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        return result
    }

    /** Read-only advisory evidence, never permission to discard or drain editors/timers. */
    private fun isDefinitiveOrdinaryRejection(row: SyncOutboxEntity): Boolean {
        if (row.retryCause != "unknown" || row.state != "rejected" || row.errorCode.isNullOrBlank() || row.attemptCount < 1 ||
            row.sequence <= 0L || row.localGeneration <= 0L ||
            row.payloadVersion != DURABLE_PAYLOAD_VERSION ||
            (row.retryAfterMillis != null && row.retryAfterMillis < 0L)
        ) {
            return false
        }
        return try {
            readDurableOutboxRow(row).request.envelope.kind != MutationKind.AGGREGATE_IMPORT
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pure strict ordinary-remainder validation shared by preliminary eligibility and binding
     * capture. No I/O; callers supply rows read under their own fencing. Requires a nonempty
     * remainder headed by a definitive ordinary rejection, full pending/rejected numeric and
     * state bookkeeping validity, strict durable wrappers and no import-kind mutation.
     */
    private fun ordinaryRemainderValid(rows: List<SyncOutboxEntity>): Boolean {
        if (rows.isEmpty()) return false
        val head = rows.first()
        if (head.state != "rejected" || head.errorCode.isNullOrBlank()) return false
        for (row in rows) {
            if (row.retryCause != "unknown" || row.sequence <= 0L ||
                row.localGeneration <= 0L ||
                row.payloadVersion != DURABLE_PAYLOAD_VERSION ||
                (row.retryAfterMillis != null && row.retryAfterMillis < 0L)
            ) {
                return false
            }
            when (row.state) {
                // Pending rows carry no frozen backoff/error bookkeeping.
                "pending" -> if (row.attemptCount != 0 ||
                    row.errorCode != null ||
                    row.errorMessage != null ||
                    row.retryAfterMillis != null
                ) {
                    return false
                }
                "rejected" -> if (row.attemptCount < 1 || row.errorCode.isNullOrBlank()) {
                    return false
                }
                else -> return false
            }
            val kind = try {
                readDurableOutboxRow(row).request.envelope.kind
            } catch (e: CancellationException) {
                throw e
            } catch (_: UnsupportedDurableVersionException) {
                return false
            } catch (_: SyncModelException) {
                return false
            } catch (_: Exception) {
                return false
            }
            if (kind == MutationKind.AGGREGATE_IMPORT) return false
        }
        return true
    }

    /**
     * Strict preliminary ordinary-recovery eligibility under the same admitted run, before
     * any real editor/timer pause or drain. Revalidates no retained stage, exact live ready
     * authority, cache epoch, process and caller liveness, the full valid ordered rejected
     * ordinary head plus pending/rejected remainder, and the strict settled-or-absent legacy
     * proof, all in a real Room transaction with canonical fences. FALSE returns without any
     * hardware/editor pause or drain; TRUE is advisory only and the caller must still
     * revalidate and capture after the real drain (which may include newly drained rest
     * edits). Frozen-prefix settlement is unaffected: an accepted prefix leaving only a
     * pending remainder is not confirmable and avoids the drain here.
     */
    private suspend fun preflightOrdinaryRecovery(
        fence: Any,
        handle: AppAccountSession,
        run: com.dragosfotache.gymtracka.data.sync.store.PushRun,
        cacheEpoch: Long,
    ): Boolean {
        ensureCurrent(fence)
        try {
            handle.checkCurrent()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        // No stage may be selected: checked on the admitted run outside any Room
        // transaction, mirroring the binding capture below.
        if (run.readSnapshotStage() != null) {
            return false
        }
        val binding = handle.identity.binding
        val eligible = database.withTransaction {
            currentCoroutineContext().ensureActive()
            checkCanonicalFences(database, binding.accountId, binding.sessionGeneration, cacheEpoch)
            currentCoroutineContext().ensureActive()
            if (!handle.isAlive() || !isFacadeCurrent(fence, handle)) {
                throw AppSessionStaleException()
            }
            val rows = database.syncDao().getOutboxForAccount(binding.accountId)
            if (!ordinaryRemainderValid(rows)) {
                return@withTransaction false
            }
            // Exact ACTIVE ready authority, not a merely successful read: the strict
            // journal/device ACTIVE tuple must equal the handle's admitted snapshot
            // (binding and generation included) with a complete baseline cache. Anything
            // else refuses before any drain.
            val ready = try {
                authority.readActiveReadyInTransaction()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withTransaction false
            }
            if (ready.snapshot != handle.room ||
                ready.snapshot.phase != RecoveryRoomPhase.ACTIVE ||
                ready.snapshot.identity?.binding?.accountId != binding.accountId ||
                ready.snapshot.sessionGeneration != binding.sessionGeneration
            ) {
                return@withTransaction false
            }
            val readyCache = ready.cache
            if (readyCache == null || !readyCache.baselineComplete ||
                readyCache.accountId != binding.accountId
            ) {
                return@withTransaction false
            }
            try {
                readLegacyRaw()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withTransaction false
            }
            // Strict settled-or-absent legacy proof as binding evidence in the same
            // transaction: valid-looking tamper cannot become an authorized drain.
            try {
                if (com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportGate.checkEmpty(
                        database,
                        binding.accountId,
                    ) != null
                ) {
                    return@withTransaction false
                }
            } catch (e: CancellationException) {
                throw e
            }
            checkCanonicalFences(database, binding.accountId, binding.sessionGeneration, cacheEpoch)
            currentCoroutineContext().ensureActive()
            if (!handle.isAlive() || !isFacadeCurrent(fence, handle)) {
                throw AppSessionStaleException()
            }
            true
        }
        currentCoroutineContext().ensureActive()
        ensureCurrent(fence)
        return eligible
    }

    /**
     * Captures the full ordered ordinary remainder in one fenced transaction under the
     * caller's held drain lease. Returns null unless the head is a definitive ordinary
     * rejection, every remaining row passes full pending/rejected numeric/state bookkeeping
     * validity, no row is an import mutation, the view permit was settled-or-absent and the
     * exact selected authority plus strict raw legacy evidence reread cleanly. The lease is
     * checked before and after capture; the retained snapshot plus final dispatch checks in
     * the caller keep the proof valid through return.
     */
    private suspend fun captureOrdinaryRecoveryTx(
        fence: Any,
        handle: AppAccountSession,
        run: com.dragosfotache.gymtracka.data.sync.store.PushRun,
        cacheEpoch: Long,
        lease: ProjectionPublicationLease,
        progress: com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress?,
    ): AccountRecoveryConfirmation? {
        ensureCurrent(fence)
        try {
            handle.checkCurrent()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        // No stage may be selected: checked on the admitted run outside any Room
        // transaction (staging needs the same uninterrupted run owner, which this
        // preparation holds, and a nonempty ordinary outbox blocks any new begin).
        if (run.readSnapshotStage() != null) {
            return null
        }
        val binding = handle.identity.binding
        return database.withTransaction {
            currentCoroutineContext().ensureActive()
            checkCanonicalFences(database, binding.accountId, binding.sessionGeneration, cacheEpoch)
            lease.checkValid()
            currentCoroutineContext().ensureActive()
            if (!handle.isAlive() || !isFacadeCurrent(fence, handle)) {
                throw AppSessionStaleException()
            }
            val rows: List<SyncOutboxEntity> = database.syncDao().getOutboxForAccount(binding.accountId)
            currentCoroutineContext().ensureActive()
            if (!ordinaryRemainderValid(rows)) {
                return@withTransaction null
            }
            val room = try {
                authority.read()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withTransaction null
            }
            val raw = try {
                readLegacyRaw()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withTransaction null
            }
            // Strict legacy settlement gate inside the transaction: the advisory view
            // permit is revalidated here as binding evidence, so valid-looking tamper
            // between advisory observation and raw capture cannot become authorized proof.
            // Original source/receipt/lineage stays binding even for settled migrations.
            try {
                if (com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportGate.checkEmpty(
                        database,
                        binding.accountId,
                    ) != null
                ) {
                    return@withTransaction null
                }
            } catch (e: CancellationException) {
                throw e
            }
            // Final in-transaction rechecks before commit: fences, lease, liveness.
            checkCanonicalFences(database, binding.accountId, binding.sessionGeneration, cacheEpoch)
            lease.checkValid()
            currentCoroutineContext().ensureActive()
            if (!handle.isAlive() || !isFacadeCurrent(fence, handle)) {
                throw AppSessionStaleException()
            }
            currentCoroutineContext().ensureActive()
            AccountRecoveryConfirmation(
                pendingCount = rows.size.toLong(),
                owner = this@AppSessionCoordinator,
                fence = fence,
                handle = handle,
                accountId = binding.accountId,
                sessionGeneration = binding.sessionGeneration,
                cacheEpoch = cacheEpoch,
                rows = rows,
                room = room,
                legacyProof = progress,
                legacyRaw = raw,
            )
        }
    }

    /**
     * Immediate pre-destructive comparison on the admitted recovery run. Any new or
     * coalesced edit, changed row, frozen injection, authority/epoch/legacy drift or stage
     * appearance refuses stale with all remaining intent intact.
     */
    private suspend fun compareOrdinaryRecovery(
        fence: Any,
        handle: AppAccountSession,
        confirmation: AccountRecoveryConfirmation,
    ) {
        ensureCurrent(fence)
        try {
            handle.checkCurrent()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        val binding = handle.identity.binding
        if (confirmation.accountId != binding.accountId ||
            confirmation.sessionGeneration != binding.sessionGeneration
        ) {
            throw AppSessionStaleException()
        }
        val room = try {
            authority.read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        if (room != confirmation.room) {
            throw AppSessionStaleException()
        }
        val rows = database.syncDao().getOutboxForAccount(binding.accountId)
        currentCoroutineContext().ensureActive()
        ensureCurrent(fence)
        if (rows != confirmation.rows) {
            throw AppSessionStaleException()
        }
        if (rows.any { it.state != "pending" && it.state != "rejected" }) {
            throw AppSessionStaleException()
        }
        // Outside any Room transaction here (notably on Main-dispatched callers), so the
        // transactional helper dispatches the synchronous cursor reads correctly.
        val progress = try {
            readLegacyRawTransactionally()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        if (progress != confirmation.legacyRaw) {
            throw AppSessionStaleException()
        }
    }

    /**
     * Strict legacy proof for ordinary work. Null means no migration; only a fully settled
     * migration permits ordinary recovery. Original import rows/source/progress/receipts are
     * never discard targets. Corrupt evidence throws and the caller refuses.
     */
    private suspend fun observeOrdinaryLegacyProof(fence: Any): com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress? {
        ensureCurrent(fence)
        val fenceCheck: () -> Unit = {
            synchronized(stateLock) {
                if (fence !== currentFence) throw AppSessionStaleException()
            }
        }
        val store = LegacyImportStore(
            database,
            LegacyImportCaptureBarrier { throw LegacyImportException(LegacyImportFailure.UNAVAILABLE) },
            fenceCheck,
            uuidProvider,
        )
        return store.observeAny()
    }

    /**
     * Strict raw legacy evidence read. Tx-safe cursor observation with no transaction of its
     * own, so it runs both inside the fenced capture/discard transactions and outside them.
     * Corrupt evidence throws and the caller refuses; cancellation propagates.
     *
     * Precondition: the synchronous DAO cursor reads below throw on Main-thread access, so
     * call only inside [database.withTransaction] (which dispatches them off Main and reads
     * the four rows atomically) or from a non-Main dispatcher. Outside-transaction paths
     * must use [readLegacyRawTransactionally]; never allowMainThreadQueries to work around
     * this, and never bypass the staleness mapping on failure.
     */
    private suspend fun readLegacyRaw(): LegacyEvidenceSnapshot =
        LegacyEvidenceSnapshot(
            com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement.readJournalStrict(database),
            com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement.readEntriesStrict(database),
            com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement.readProgressStrict(database),
            com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement.readAcceptancesStrict(database),
        )

    /**
     * Transaction-safe strict raw legacy evidence read for outside-transaction comparison
     * paths (notably the pre-destructive compare invoked from Main-dispatched UI callers).
     * Runs the synchronous cursor reads inside Room's transaction dispatch so Main-thread
     * access is never attempted and the four rows read atomically. Inside-transaction
     * callers keep using [readLegacyRaw] directly. Corrupt evidence throws and the caller
     * refuses; cancellation propagates.
     */
    private suspend fun readLegacyRawTransactionally(): LegacyEvidenceSnapshot =
        database.withTransaction {
            readLegacyRaw()
        }

    /**
     * guarded ACTIVE reconciliation over the exact current handle. Holds ONE
     * admitted run through push/pull, any typed full-resync, an actual
     * post-baseline tail and guarded paired publication. Never calls the
     * separate reconnect entry and never reacquires the owner between phases.
     * Retains reconnect's auth/transient/stale/cancellation classification.
     */
    suspend fun reconcile(
        session: AppAccountSession,
        expectedCacheEpoch: Long,
        maxPushMutations: Int = 100,
        maxPullPages: Int = 100,
        pullPageLimit: Int = 100,
        maxSnapshotPages: Int = 100,
        snapshotPageLimit: Int = 200,
        restartStageId: String? = null,
        connectionRestored: Boolean = false,
    ): AppReconciliationResult {
        currentCoroutineContext().ensureActive()
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        if (maxPushMutations !in 1..100 || maxPullPages !in 1..100 || maxSnapshotPages !in 1..100) {
            throw IllegalArgumentException("Invalid reconcile budgets")
        }
        if (pullPageLimit !in 1..200 || snapshotPageLimit !in 1..500) {
            throw IllegalArgumentException("Invalid reconcile page limits")
        }
        val canonicalRestart = restartStageId?.let {
            try {
                requireWorkoutUuid(it, "stageId")
            } catch (_: Exception) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
        }
        val observedFence = synchronized(stateLock) { currentFence }
        return operationMutex.withLock {
            ensureCurrent(observedFence)
            if (origin == null) {
                throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            }
            val current = synchronized(stateLock) { currentHandle }
            if (current == null || current !== session) {
                throw AppSessionStaleException()
            }
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(observedFence)
            val recovered = synchronized(stateLock) { currentRecovered }
                ?: throw AppSessionStaleException()
            val facadeCheck: () -> Unit = {
                if (!current.isAlive() || !isFacadeCurrent(observedFence, current)) {
                    throw AppSessionStaleException()
                }
            }
            val binding = current.identity.binding
            val store = PushStore(
                database,
                RepositorySession(binding.accountId, binding.sessionGeneration),
                runOwner,
                facadeCheck,
            )
            val api = RecoveredSessionGymSyncApi(recovered, transport, facadeCheck)
            val result = try {
                store.withRun(expectedCacheEpoch) { run ->
                    if (connectionRestored) run.retryConnectionHeadAfterReconnect()
                    reconcileOnRun(
                        run = run,
                        store = store,
                        api = api,
                        handle = current,
                        fence = observedFence,
                        facadeCheck = facadeCheck,
                        maxPushMutations = maxPushMutations,
                        maxPullPages = maxPullPages,
                        pullPageLimit = pullPageLimit,
                        maxSnapshotPages = maxSnapshotPages,
                        snapshotPageLimit = snapshotPageLimit,
                        restartStageId = canonicalRestart,
                    )
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionStaleException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: AppSessionException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SessionVaultException) {
                currentCoroutineContext().ensureActive()
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: SessionRefreshException) {
                currentCoroutineContext().ensureActive()
                if (e.failure == SessionRefreshFailure.ACCESS_UNAVAILABLE) {
                    ensureCurrent(observedFence)
                    updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: SessionRecoveryException) {
                currentCoroutineContext().ensureActive()
                lockIfAlive(observedFence, current.aliveToken())
                throw AppSessionStaleException()
            } catch (e: SyncApiException.ReauthenticationRequired) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED)
                throw e
            } catch (e: SyncApiException) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureCurrent(observedFence)
                try {
                    current.checkCurrent()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionStaleException()
                }
                ensureCurrent(observedFence)
                updateDeliveryStatus(current, observedFence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            // Final exact-handle revalidation after run return, before reporting any
            // typed stop: same-durable-generation invalidation or authority drift
            // racing the run must fail closed stale, never return a stale result.
            // Caller cancellation dominates and never reports a delivery.
            currentCoroutineContext().ensureActive()
            try {
                current.checkCurrent()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionStaleException()
            }
            ensureCurrent(observedFence)
            result
        }
    }

    private suspend fun reconcileOnRun(
        run: com.dragosfotache.gymtracka.data.sync.store.PushRun,
        store: PushStore,
        api: RecoveredSessionGymSyncApi,
        handle: AppAccountSession,
        fence: Any,
        facadeCheck: () -> Unit,
        maxPushMutations: Int,
        maxPullPages: Int,
        pullPageLimit: Int,
        maxSnapshotPages: Int,
        snapshotPageLimit: Int,
        restartStageId: String?,
    ): AppReconciliationResult {
        currentCoroutineContext().ensureActive()
        ensureCurrent(fence)
        try {
            handle.checkCurrent()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        val retained = run.readSnapshotStage()
        ensureCurrent(fence)
        if (retained != null && restartStageId == null) {
            val epoch = run.observedCacheEpoch()
            updateDeliveryStatus(handle, fence, AppSessionDeliveryStatus.RETRY_REQUIRED)
            return AppReconciliationResult(
                stop = AppReconciliationStop.RETAINED_STAGE,
                cacheEpoch = epoch,
                delivery = null,
                snapshot = null,
                publication = null,
                retainedStageId = retained.stageId,
            )
        }
        var forcedSnapshot = false
        var freshStageId: String? = null
        if (retained != null) {
            val requested = restartStageId
            if (requested == null || requested != retained.stageId) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
            // Generate and validate the fresh stage UUID BEFORE abandoning the
            // explicitly selected old stage: a provider failure (cancellation
            // dominates and propagates; any other failure is UNAVAILABLE) must not
            // destroy recovery evidence.
            val fresh = try {
                uuidProvider()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            try {
                requireWorkoutUuid(fresh, "stageId")
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
            run.abandonSnapshotStage(retained)
            ensureCurrent(fence)
            freshStageId = fresh
            forcedSnapshot = true
        } else if (restartStageId != null) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        val syncDriver = SyncDeliveryLoop(store, api, clockMillis)
        val snapshotDriver = SnapshotDeliveryLoop(store, api, clockMillis)
        if (forcedSnapshot) {
            val stageId = freshStageId ?: throw AppSessionException(AppSessionReason.UNAVAILABLE)
            return runSnapshotChain(
                run = run,
                store = store,
                syncDriver = syncDriver,
                snapshotDriver = snapshotDriver,
                handle = handle,
                fence = fence,
                facadeCheck = facadeCheck,
                stageId = stageId,
                maxPushMutations = maxPushMutations,
                maxPullPages = maxPullPages,
                pullPageLimit = pullPageLimit,
                maxSnapshotPages = maxSnapshotPages,
                snapshotPageLimit = snapshotPageLimit,
            )
        }
        val delivery = syncDriver.deliver(run, maxPushMutations, maxPullPages, pullPageLimit)
        ensureCurrent(fence)
        if (delivery.push.stop != PushDeliveryStop.Empty) {
            updateDeliveryStatus(handle, fence, statusFor(delivery))
            return AppReconciliationResult(
                stop = AppReconciliationStop.PUSH_STOPPED,
                cacheEpoch = run.observedCacheEpoch(),
                delivery = delivery,
                snapshot = null,
                publication = null,
                retainedStageId = null,
            )
        }
        val pull = delivery.pull
        if (pull == null) {
            updateDeliveryStatus(handle, fence, statusFor(delivery))
            return AppReconciliationResult(
                stop = AppReconciliationStop.PULL_STOPPED,
                cacheEpoch = run.observedCacheEpoch(),
                delivery = delivery,
                snapshot = null,
                publication = null,
                retainedStageId = null,
            )
        }
        when (val stop = pull.stop) {
            is PullDeliveryStop.Tail -> {
                return publishAfterTail(
                    run = run,
                    handle = handle,
                    fence = fence,
                    facadeCheck = facadeCheck,
                    delivery = delivery,
                    snapshot = null,
                )
            }
            is PullDeliveryStop.RecoveryRequired -> {
                if (stop.reason != PullRecoveryReason.MISSING_BASELINE_OR_CURSOR &&
                    stop.reason != PullRecoveryReason.CURSOR_EXPIRED
                ) {
                    updateDeliveryStatus(handle, fence, statusFor(delivery))
                    return AppReconciliationResult(
                        stop = AppReconciliationStop.PULL_STOPPED,
                        cacheEpoch = run.observedCacheEpoch(),
                        delivery = delivery,
                        snapshot = null,
                        publication = null,
                        retainedStageId = null,
                    )
                }
                val generated = try {
                    uuidProvider()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                val stageId = try {
                    requireWorkoutUuid(generated, "stageId")
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                return runSnapshotChain(
                    run = run,
                    store = store,
                    syncDriver = syncDriver,
                    snapshotDriver = snapshotDriver,
                    handle = handle,
                    fence = fence,
                    facadeCheck = facadeCheck,
                    stageId = stageId,
                    maxPushMutations = maxPushMutations,
                    maxPullPages = maxPullPages,
                    pullPageLimit = pullPageLimit,
                    maxSnapshotPages = maxSnapshotPages,
                    snapshotPageLimit = snapshotPageLimit,
                )
            }
            else -> {
                updateDeliveryStatus(handle, fence, statusFor(delivery))
                return AppReconciliationResult(
                    stop = AppReconciliationStop.PULL_STOPPED,
                    cacheEpoch = run.observedCacheEpoch(),
                    delivery = delivery,
                    snapshot = null,
                    publication = null,
                    retainedStageId = null,
                )
            }
        }
    }

    private suspend fun runSnapshotChain(
        run: com.dragosfotache.gymtracka.data.sync.store.PushRun,
        store: PushStore,
        syncDriver: SyncDeliveryLoop,
        snapshotDriver: SnapshotDeliveryLoop,
        handle: AppAccountSession,
        fence: Any,
        facadeCheck: () -> Unit,
        stageId: String,
        maxPushMutations: Int,
        maxPullPages: Int,
        pullPageLimit: Int,
        maxSnapshotPages: Int,
        snapshotPageLimit: Int,
    ): AppReconciliationResult {
        ensureCurrent(fence)
        // Each driver chunk is bounded by maxSnapshotPages (1..100 per invocation);
        // consecutive same-run continuations have no total account-size ceiling.
        val start = snapshotDriver.start(
            run,
            stageId,
            maxPushMutations,
            maxSnapshotPages,
            snapshotPageLimit,
        )
        ensureCurrent(fence)
        val pendingDownload = start.download
        if (pendingDownload == null) {
            val delivery = SyncDeliveryResult(start.push, null, null)
            updateDeliveryStatus(handle, fence, statusFor(delivery))
            return AppReconciliationResult(
                stop = AppReconciliationStop.PUSH_STOPPED,
                cacheEpoch = run.observedCacheEpoch(),
                delivery = delivery,
                snapshot = null,
                publication = null,
                retainedStageId = null,
            )
        }
        val firstDownload: com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadResult = pendingDownload
        var current: com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadResult = firstDownload
        // Consecutive bounded same-run continuations until actual terminal, typed
        // failure/expiry or cancellation. No permanent page cap: budgets bound each
        // driver chunk, never total account size. Only this driver's issued
        // LimitReached capability on this uninterrupted run continues; durable
        // retained stages are never silently resumed or fabricated into completion.
        while (current.stop is SnapshotDownloadStop.LimitReached) {
            currentCoroutineContext().ensureActive()
            current = snapshotDriver.continueDownload(run, current, maxSnapshotPages)
            ensureCurrent(fence)
        }
        when (current.stop) {
            is SnapshotDownloadStop.RestartRequired -> {
                updateDeliveryStatus(handle, fence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                return AppReconciliationResult(
                    stop = AppReconciliationStop.SNAPSHOT_RESTART_REQUIRED,
                    cacheEpoch = run.observedCacheEpoch(),
                    delivery = null,
                    snapshot = current,
                    publication = null,
                    retainedStageId = current.stage.stageId,
                )
            }
            is SnapshotDownloadStop.Failed -> {
                updateDeliveryStatus(handle, fence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                return AppReconciliationResult(
                    stop = AppReconciliationStop.SNAPSHOT_STOPPED,
                    cacheEpoch = run.observedCacheEpoch(),
                    delivery = null,
                    snapshot = current,
                    publication = null,
                    retainedStageId = current.stage.stageId,
                )
            }
            is SnapshotDownloadStop.LimitReached -> {
                updateDeliveryStatus(handle, fence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                return AppReconciliationResult(
                    stop = AppReconciliationStop.SNAPSHOT_STOPPED,
                    cacheEpoch = run.observedCacheEpoch(),
                    delivery = null,
                    snapshot = current,
                    publication = null,
                    retainedStageId = current.stage.stageId,
                )
            }
            is SnapshotDownloadStop.Downloaded -> {
                snapshotDriver.complete(run, current)
                ensureCurrent(fence)
                val tail = syncDriver.deliver(run, maxPushMutations, maxPullPages, pullPageLimit)
                ensureCurrent(fence)
                if (tail.push.stop != PushDeliveryStop.Empty) {
                    updateDeliveryStatus(handle, fence, statusFor(tail))
                    return AppReconciliationResult(
                        stop = AppReconciliationStop.PUSH_STOPPED,
                        cacheEpoch = run.observedCacheEpoch(),
                        delivery = tail,
                        snapshot = current,
                        publication = null,
                        retainedStageId = null,
                    )
                }
                val tailPull = tail.pull
                if (tailPull == null || tailPull.stop !is PullDeliveryStop.Tail) {
                    updateDeliveryStatus(handle, fence, statusFor(tail))
                    return AppReconciliationResult(
                        stop = AppReconciliationStop.PULL_STOPPED,
                        cacheEpoch = run.observedCacheEpoch(),
                        delivery = tail,
                        snapshot = current,
                        publication = null,
                        retainedStageId = null,
                    )
                }
                if (tail.hasOutstandingLocalWork == true) {
                    // Real completed tail with known outstanding work: report protected without
                    // publication or drain. The full visible graph and new pending bytes stay
                    // preserved; no pending edit is sent after the terminal pull.
                    updateDeliveryStatus(handle, fence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                    return AppReconciliationResult(
                        stop = AppReconciliationStop.PROTECTED,
                        cacheEpoch = run.observedCacheEpoch(),
                        delivery = tail,
                        snapshot = current,
                        publication = null,
                        retainedStageId = null,
                    )
                }
                if (tail.hasOutstandingLocalWork != false) {
                    updateDeliveryStatus(handle, fence, statusFor(tail))
                    return AppReconciliationResult(
                        stop = AppReconciliationStop.PULL_STOPPED,
                        cacheEpoch = run.observedCacheEpoch(),
                        delivery = tail,
                        snapshot = current,
                        publication = null,
                        retainedStageId = null,
                    )
                }
                return publishAfterTail(
                    run = run,
                    handle = handle,
                    fence = fence,
                    facadeCheck = facadeCheck,
                    delivery = tail,
                    snapshot = current,
                )
            }
        }
    }

    private suspend fun publishAfterTail(
        run: com.dragosfotache.gymtracka.data.sync.store.PushRun,
        handle: AppAccountSession,
        fence: Any,
        facadeCheck: () -> Unit,
        delivery: SyncDeliveryResult,
        snapshot: com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadResult?,
    ): AppReconciliationResult {
        ensureCurrent(fence)
        try {
            handle.checkCurrent()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        val binding = handle.identity.binding
        val paired = ProjectionStore(
            database,
            RepositorySession(binding.accountId, binding.sessionGeneration),
            publicationBarrier,
            facadeCheck,
        )
        val publication = run.publishProjection(paired)
        ensureCurrent(fence)
        try {
            handle.checkCurrent()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AppSessionStaleException()
        }
        ensureCurrent(fence)
        when (publication.status) {
            ProjectionPublicationStatus.PROTECTED -> {
                updateDeliveryStatus(handle, fence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                return AppReconciliationResult(
                    stop = AppReconciliationStop.PROTECTED,
                    cacheEpoch = run.observedCacheEpoch(),
                    delivery = delivery,
                    snapshot = snapshot,
                    publication = publication,
                    retainedStageId = null,
                )
            }
            ProjectionPublicationStatus.PUBLISHED,
            ProjectionPublicationStatus.UNCHANGED,
            -> {
                updateDeliveryStatus(handle, fence, AppSessionDeliveryStatus.IDLE)
                return AppReconciliationResult(
                    stop = AppReconciliationStop.TAIL,
                    cacheEpoch = run.observedCacheEpoch(),
                    delivery = delivery,
                    snapshot = snapshot,
                    publication = publication,
                    retainedStageId = null,
                )
            }
            ProjectionPublicationStatus.INCOMPLETE_BASELINE -> {
                updateDeliveryStatus(handle, fence, AppSessionDeliveryStatus.RETRY_REQUIRED)
                throw AppSessionException(AppSessionReason.UNAVAILABLE)
            }
        }
    }

    override fun toString(): String = "AppSessionCoordinator(redacted)"

    /**
     * Pre-acquisition grant compatibility: cached UUID, configured-origin
     * binding and operation identity. Direct saved-session retries require
     * the reserved operation; explicit foreground private recovery requires
     * a new operation. Foreign grants, origin drift and incompatible
     * operations refuse before any drain or invalidation.
     */
    private fun validateGrantCompatibility(
        expected: RecoveryRoomSnapshot,
        targetOperation: String,
        session: ServiceSession,
        configured: ServiceOrigin,
        freshPrivateSession: Boolean,
    ) {
        val grantAccount = try {
            requireWorkoutUuid(session.account.accountId, "accountId")
        } catch (_: Exception) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        val cached = expected.cachedAccountId
        if (cached != null && grantAccount != cached) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        val identity = expected.identity
        if (identity != null) {
            if (grantAccount != identity.binding.accountId) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
            if (identity.binding.origin != configured) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
        }
        if (expected.phase == RecoveryRoomPhase.PREPARING ||
            expected.phase == RecoveryRoomPhase.TRANSITIONING
        ) {
            val reserved = identity?.operationId
                ?: throw AppSessionStaleException()
            val invalidOperation = if (freshPrivateSession) reserved == targetOperation else reserved != targetOperation
            if (invalidOperation) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
        }
        if (expected.phase == RecoveryRoomPhase.ACTIVE) {
            // Fresh replacement reservations require a new operation UUID:
            // reusing the active operation refuses here, before any drain
            // or invalidation, so the healthy handle is preserved.
            val active = identity?.operationId
                ?: throw AppSessionStaleException()
            if (active == targetOperation) {
                throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            }
        }
    }

    /**
     * opt-in private import capture inside the shared acceptGrant
     * critical section. Returns null when inapplicable (no opt-in policy,
     * non-fresh shape, or genuinely empty local storage) so the caller
     * falls back to ordinary preparation. Uses the SAME held transition
     * lease through an explicitly borrowed check-only wrapper whose close
     * never closes the outer owner; the facade still closes the actual
     * owner exactly once. No network/vault/clock calls inside Room. Valid
     * capture already reserved PREPARING, so the returned grant installs
     * through existing preparation resume at its exact tuple.
     */
    private suspend fun legacyCaptureOutcomeIfOptIn(
        expected: RecoveryRoomSnapshot,
        targetOperation: String,
        session: ServiceSession,
        configured: ServiceOrigin,
        outerLease: ProjectionPublicationLease,
        fence: Any,
    ): PreparedSessionObservation? {
        if (legacyImportPolicy == null) return null
        if (expected.phase != RecoveryRoomPhase.SIGNED_OUT || expected.cachedAccountId != null) {
            return null
        }
        ensureCurrent(fence)
        val grantAccount = try {
            requireWorkoutUuid(session.account.accountId, "accountId")
        } catch (_: Exception) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        val target = try {
            LegacyImportTarget(configured, grantAccount, targetOperation)
        } catch (_: Exception) {
            throw AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
        }
        ensureCurrent(fence)
        val fenceCheck: () -> Unit = {
            synchronized(stateLock) {
                if (fence !== currentFence) throw AppSessionStaleException()
            }
        }
        val borrowed = LegacyImportCaptureBarrier { BorrowedCheckOnlyLease(outerLease) }
        val store = LegacyImportStore(database, borrowed, fenceCheck, uuidProvider)
        val captured = try {
            store.capture(expected, target)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: LegacyImportException) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(fence)
            throw AppSessionException(AppSessionReason.UNAVAILABLE)
        }
        ensureCurrent(fence)
        when (captured) {
            is LegacyImportCaptureResult.NoLocalData -> return null
            is LegacyImportCaptureResult.Retained -> {
                val evidence = captured.evidence
                if (evidence.phase == LegacyImportPhase.BLOCKED) {
                    ensureCurrent(fence)
                    publishIfCurrent(
                        AppSessionState.Private(AppSessionReason.UNAVAILABLE, advisoryRoom()),
                        fence,
                    )
                    throw AppSessionException(AppSessionReason.UNAVAILABLE)
                }
                val prep = preparation
                    ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
                try {
                    return prep.resume(evidence.room, session)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (e: SessionPreparationException) {
                    currentCoroutineContext().ensureActive()
                    throw mapPreparationFailure(e)
                }
            }
        }
    }

    private class BorrowedCheckOnlyLease(
        private val outer: ProjectionPublicationLease,
    ) : ProjectionPublicationLease {
        override fun checkValid() = outer.checkValid()
        override fun close() = Unit
    }

    private suspend fun reserveGrant(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
        fence: Any,
        freshPrivateSession: Boolean,
    ): PreparedSessionObservation {
        ensureCurrent(fence)
        if (!freshPrivateSession &&
            (expected.phase == RecoveryRoomPhase.PREPARING || expected.phase == RecoveryRoomPhase.TRANSITIONING)
        ) {
            return resumeExact(expected, session, fence)
        }
        if (expected.phase == RecoveryRoomPhase.SIGNED_OUT) {
            if (expected.cachedAccountId == null) {
                val prep = preparation
                    ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
                return try {
                    prep.prepare(expected, operationId, session)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (e: SessionPreparationException) {
                    currentCoroutineContext().ensureActive()
                    throw mapPreparationFailure(e)
                }
            }
            val retained = sameAccount
                ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            return try {
                retained.prepare(expected, operationId, session)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SessionReauthenticationException) {
                currentCoroutineContext().ensureActive()
                throw mapReauthFailure(e)
            }
        }
        if (expected.phase == RecoveryRoomPhase.ACTIVE || freshPrivateSession) {
            val retained = sameAccount
                ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            return try {
                retained.prepare(expected, operationId, session)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SessionReauthenticationException) {
                currentCoroutineContext().ensureActive()
                throw mapReauthFailure(e)
            }
        }
        throw AppSessionStaleException()
    }

    /**
     * Exact resume routing with a restricted fallback. TRANSITIONING resumes
     * only through retained recovery. PREPARING resumes through fresh
     * preparation; only an explicit compatible IDENTITY_MISMATCH refusal
     * (indicating retained/older-evidence source) falls back to retained
     * recovery with a fresh admission check. Stale, unavailable and invalid
     * failures never perform another writer.
     */
    private suspend fun resumeExact(
        expected: RecoveryRoomSnapshot,
        session: ServiceSession,
        fence: Any,
    ): PreparedSessionObservation {
        ensureCurrent(fence)
        if (expected.phase == RecoveryRoomPhase.TRANSITIONING) {
            val retained = sameAccount
                ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
            try {
                return retained.resume(expected, session)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SessionReauthenticationException) {
                currentCoroutineContext().ensureActive()
                throw mapReauthFailure(e)
            }
        }
        val prep = preparation
        if (prep != null) {
            try {
                return prep.resume(expected, session)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SessionPreparationException) {
                currentCoroutineContext().ensureActive()
                if (e.failure != SessionPreparationFailure.IDENTITY_MISMATCH) {
                    throw mapPreparationFailure(e)
                }
                val retained = sameAccount
                    ?: throw mapPreparationFailure(e)
                ensureCurrent(fence)
                try {
                    return retained.resume(expected, session)
                } catch (e2: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e2
                } catch (e2: SessionReauthenticationException) {
                    currentCoroutineContext().ensureActive()
                    throw mapReauthFailure(e2)
                }
            }
        }
        val retained = sameAccount
            ?: throw AppSessionException(AppSessionReason.CONFIGURATION_UNAVAILABLE)
        try {
            return retained.resume(expected, session)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionReauthenticationException) {
            currentCoroutineContext().ensureActive()
            throw mapReauthFailure(e)
        }
    }

    private fun mapPreparationFailure(e: SessionPreparationException): Exception =
        when (e.failure) {
            SessionPreparationFailure.STALE_AUTHORITY,
            SessionPreparationFailure.STALE_OPERATION,
            -> AppSessionStaleException()
            SessionPreparationFailure.ACCOUNT_MISMATCH,
            SessionPreparationFailure.IDENTITY_MISMATCH,
            -> AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            SessionPreparationFailure.LOCAL_DATA_REQUIRES_DECISION ->
                AppSessionException(AppSessionReason.LOCAL_DATA_REQUIRES_DECISION)
            SessionPreparationFailure.VAULT_OCCUPIED ->
                AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            SessionPreparationFailure.NOT_PREPARING ->
                AppSessionException(AppSessionReason.PREPARATION_REQUIRED)
            SessionPreparationFailure.INVALID_INPUT,
            SessionPreparationFailure.COUNTER_OVERFLOW,
            SessionPreparationFailure.AUTHORITY_UNAVAILABLE,
            SessionPreparationFailure.VAULT_UNAVAILABLE,
            -> AppSessionException(AppSessionReason.UNAVAILABLE)
        }

    private fun mapReauthFailure(e: SessionReauthenticationException): Exception =
        when (e.failure) {
            SessionReauthenticationFailure.STALE_AUTHORITY,
            SessionReauthenticationFailure.STALE_OPERATION,
            SessionReauthenticationFailure.VAULT_CONFLICT,
            -> AppSessionStaleException()
            SessionReauthenticationFailure.IDENTITY_MISMATCH,
            -> AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            SessionReauthenticationFailure.LOCAL_DATA_REQUIRES_DECISION ->
                AppSessionException(AppSessionReason.LOCAL_DATA_REQUIRES_DECISION)
            SessionReauthenticationFailure.PENDING_REFRESH ->
                AppSessionException(AppSessionReason.PREPARATION_REQUIRED)
            SessionReauthenticationFailure.INVALID_INPUT,
            SessionReauthenticationFailure.COUNTER_OVERFLOW,
            SessionReauthenticationFailure.AUTHORITY_CORRUPT,
            SessionReauthenticationFailure.AUTHORITY_UNAVAILABLE,
            SessionReauthenticationFailure.VAULT_UNAVAILABLE,
            -> AppSessionException(AppSessionReason.UNAVAILABLE)
        }

    private fun mapEngineFailure(e: PreparedEngineException): Exception =
        when (e.failure) {
            PreparedEngineFailure.STALE_OPERATION -> AppSessionStaleException()
            PreparedEngineFailure.IDENTITY_MISMATCH ->
                AppSessionException(AppSessionReason.IDENTITY_MISMATCH)
            PreparedEngineFailure.NOT_PREPARED ->
                AppSessionException(AppSessionReason.PREPARATION_REQUIRED)
            PreparedEngineFailure.INVALID_INPUT,
            PreparedEngineFailure.AUTHORITY_UNAVAILABLE,
            PreparedEngineFailure.VAULT_UNAVAILABLE,
            -> AppSessionException(AppSessionReason.UNAVAILABLE)
        }

    private suspend fun admitActive(
        recovered: RecoveredSession,
        fence: Any,
    ): AppSessionState {
        ensureCurrent(fence)
        val snapshot = recovered.snapshot()
        val identity = recovered.identity
        ensureCurrent(fence)
        val alive = AtomicBoolean(true)
        val facadeCheck: () -> Unit = {
            if (!alive.get() || !isFacadeCurrent(fence, alive)) {
                throw AppSessionStaleException()
            }
        }
        val repository = GymRepository(
            database,
            RepositorySession(identity.binding.accountId, identity.binding.sessionGeneration),
            { uuidProvider() },
            clockMillis,
            facadeCheck,
        )
        val handle = AppAccountSession(
            identity = identity,
            room = snapshot,
            repository = repository,
            fence = fence,
            alive = alive,
            checkAuthority = { recovered.checkCurrent() },
            isFacadeCurrent = { isFacadeCurrent(fence, alive) },
            onAuthorityFault = { lockIfAlive(fence, alive) },
        )
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            // Caller activity is rechecked inside the lock immediately
            // before installation, so a cancelled caller cannot publish.
            caller.ensureActive()
            if (fence !== currentFence) {
                alive.set(false)
                throw AppSessionStaleException()
            }
            currentHandle?.markStale()
            preparedEngine?.invalidate()
            currentHandle = handle
            currentRecovered = recovered
            issuedRecoveries.clear()
            val active = AppSessionState.Active(handle, AppSessionDeliveryStatus.IDLE)
            _state.value = active
            return active
        }
    }

    private suspend fun publishPrivateForRecovery(
        recoveryState: SessionRecoveryState,
        fence: Any,
    ): AppSessionState {
        ensureCurrent(fence)
        val room = advisoryRoom()
        val reason = when (recoveryState) {
            is SessionRecoveryState.PrivacyLocked -> return publishIfCurrent(AppSessionState.PrivacyLocked, fence)
            is SessionRecoveryState.LocalOnly -> when (recoveryState.reason) {
                SessionRecoveryReason.CONFIGURATION_UNAVAILABLE -> AppSessionReason.CONFIGURATION_UNAVAILABLE
                SessionRecoveryReason.SIGNED_OUT -> AppSessionReason.SIGN_IN_REQUIRED
                else -> AppSessionReason.UNAVAILABLE
            }
            is SessionRecoveryState.Locked -> when (recoveryState.reason) {
                SessionRecoveryReason.TRANSITION_REQUIRED -> AppSessionReason.PREPARATION_REQUIRED
                SessionRecoveryReason.BASELINE_INCOMPLETE -> AppSessionReason.PREPARATION_REQUIRED
                SessionRecoveryReason.VAULT_MISSING,
                SessionRecoveryReason.VAULT_UNAVAILABLE,
                -> AppSessionReason.SIGN_IN_REQUIRED
                SessionRecoveryReason.IDENTITY_MISMATCH -> AppSessionReason.IDENTITY_MISMATCH
                SessionRecoveryReason.AUTHORITY_UNAVAILABLE,
                SessionRecoveryReason.CONFIGURATION_UNAVAILABLE,
                SessionRecoveryReason.SIGNED_OUT,
                -> AppSessionReason.UNAVAILABLE
            }
            is SessionRecoveryState.Active -> return publishIfCurrent(
                AppSessionState.Private(AppSessionReason.UNAVAILABLE, room), fence,
            )
        }
        return publishIfCurrent(AppSessionState.Private(reason, room), fence)
    }

    private suspend fun advisoryRoom(): RecoveryRoomSnapshot? {
        return try {
            currentCoroutineContext().ensureActive()
            authority.read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private fun isFacadeCurrent(fence: Any, alive: AtomicBoolean): Boolean =
        synchronized(stateLock) { fence === currentFence && currentHandle?.isAlive() == true && alive.get() }

    private fun isFacadeCurrent(fence: Any, handle: AppAccountSession): Boolean =
        synchronized(stateLock) { fence === currentFence && currentHandle === handle && handle.isAlive() }

    private suspend fun ensureCurrent(fence: Any) {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                throw AppSessionStaleException()
            }
        }
    }

    private suspend fun publishIfCurrent(
        next: AppSessionState,
        fence: Any,
    ): AppSessionState {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                throw AppSessionStaleException()
            }
            _state.value = next
            return next
        }
    }

    /**
     * Fault-callback entry: locks only when [token] still identifies the
     * currently admitted handle under the current fence. Later repository
     * reads refuse through the dead process check even at unchanged durable
     * generation.
     */
    private fun lockIfAlive(fence: Any, token: AtomicBoolean) {
        synchronized(stateLock) {
            if (fence !== currentFence) {
                return
            }
            val handle = currentHandle ?: return
            if (handle.aliveToken() !== token || !token.get()) {
                return
            }
            currentFence = Any()
            handle.markStale()
            currentHandle = null
            currentRecovered = null
            issuedRecoveries.clear()
            _state.value = AppSessionState.PrivacyLocked
            preparation?.invalidate()
            sameAccount?.invalidate()
            preparedEngine?.invalidate()
            recovery.invalidate()
        }
    }

    /**
     * Replacement invalidation: kills the current handle, clears admission,
     * locks presentation to PrivacyLocked and retires subordinate recovered
     * and prepared work synchronously under the same lock, before any
     * subsequent Room/vault work. A dead Active is never exposed during the
     * transition; faults afterwards stay private/locked, never old Active.
     */
    private suspend fun invalidateForReplacement(fence: Any) {
        ensureCurrent(fence)
        synchronized(stateLock) {
            if (fence !== currentFence) {
                throw AppSessionStaleException()
            }
            currentHandle?.markStale()
            currentHandle = null
            currentRecovered = null
            issuedRecoveries.clear()
            _state.value = AppSessionState.PrivacyLocked
            recovery.invalidate()
            preparedEngine?.invalidate()
        }
        ensureCurrent(fence)
    }

    /**
     * Final status publication: atomically verifies caller activity plus the
     * exact fence/handle under the state lock and throws Stale on drift,
     * so a reconnect whose admission died mid-delivery can never report a
     * successful delivery with a silently dropped status update.
     */
    private suspend fun updateDeliveryStatus(
        handle: AppAccountSession,
        fence: Any,
        status: AppSessionDeliveryStatus,
    ) {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence || currentHandle !== handle) {
                throw AppSessionStaleException()
            }
            _state.value = AppSessionState.Active(handle, status)
        }
    }

    /**
     * Conservative delivery status: only a clean valid tail (bounded push
     * Empty, pull Tail, no outstanding local work — not convergence) stays
     * IDLE and only reauthentication stops report REAUTHENTICATION_REQUIRED;
     * every other blocked/rejected/cursor/stage/error stop reports
     * RETRY_REQUIRED. The typed method stop itself is preserved in the
     * returned result.
     */
    private fun statusFor(result: SyncDeliveryResult): AppSessionDeliveryStatus {
        if (result.push.stop is PushDeliveryStop.ReauthenticationRequired) {
            return AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED
        }
        val pullStop = result.pull?.stop
        if (pullStop is PullDeliveryStop.Failed &&
            pullStop.failure is SyncApiException.ReauthenticationRequired
        ) {
            return AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED
        }
        if (result.push.stop is PushDeliveryStop.Empty &&
            pullStop is PullDeliveryStop.Tail &&
            result.hasOutstandingLocalWork == false
        ) {
            return AppSessionDeliveryStatus.IDLE
        }
        return AppSessionDeliveryStatus.RETRY_REQUIRED
    }
}
