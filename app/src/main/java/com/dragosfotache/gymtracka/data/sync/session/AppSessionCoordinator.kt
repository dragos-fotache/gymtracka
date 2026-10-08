package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PullRecoveryReason
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryResult
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStorageException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * R09h app-facing activation and offline recovery boundary. Composes Room12
 * authority, R09b/g preparation/recovery, R09c bootstrap, R09d completion and
 * R04 startup/refresh into one explicitly constructed boundary. Matching
 * established accounts reopen offline without credential freshness/login;
 * safe reconnect reuses bounded push-before-pull; reauth/transient stops
 * preserve cached edits and exact uncertain requests.
 *
 * Construction starts PrivacyLocked and does no I/O. Caller owns ONE
 * coordinator/run owner per physical DB/vault; constructor is inert. Required
 * transition/publication inputs have no permissive defaults. No production
 * startup/UI factory constructs this boundary in this slice. Displays are
 * fixed/redacted and expose no grants, credentials, request bytes or causes.
 *
 * Normative refusal policy: [recover] returns closed Private classifications.
 * [acceptGrant] and [completeActivation] return Private on successful
 * preparation or typed incomplete engine progress, and Active only on
 * authorized completion; refused input/dependency/seam failures throw
 * [AppSessionException]. An exact expected-snapshot mismatch, process
 * invalidation or dead handle throws [AppSessionStaleException]. Caller
 * cancellation is always rethrown. Early refusal and failed acquisition
 * preserve the current healthy Active state/handle; once reservation has
 * started, errors leave the affected operation private/locked until explicit
 * recovery. Exceptions never imply durable rollback.
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
) : ForegroundGrantHandoff {
    private val authority = RoomSessionAuthority(database)
    private val credentialMutex = Mutex()
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private var currentFence: Any = Any()
    private var currentHandle: AppAccountSession? = null
    private var currentRecovered: RecoveredSession? = null
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
                return@withLock admitActive(activeSession.session, observedFence)
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
                    return@withLock admitActive(secondActive.session, observedFence)
                }
                return@withLock publishPrivateForRecovery(second, observedFence)
            }
            return@withLock publishPrivateForRecovery(first, observedFence)
        }
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
     * snapshot is unchanged. Delegates existing R09 preparation exactly
     * once; every guard/lease/error path below is shared with [acceptGrant].
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
        return acceptGrantWithFence(request.expected, targetOperation, session, binding.fence)
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
            validateGrantCompatibility(expected, targetOperation, session, configured)
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
                    reserveGrant(expected, targetOperation, session, observedFence)
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
            val bootstrap = try {
                engine.bootstrap(
                    stageId, maxSnapshotPages, restartRetainedStage = restartRetainedStage,
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
            return@withLock admitActive(activeSession.session, observedFence)
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
     * R12c bounded recovery read: observes the selected account's current cache epoch
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
     * R12c guarded ACTIVE reconciliation over the exact current handle. Holds ONE
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
                if (tailPull?.stop !is PullDeliveryStop.Tail || tail.hasOutstandingLocalWork != false) {
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
     * binding and reserved-operation equality. Foreign grants, origin drift
     * and fresh-operation restarts refuse with IDENTITY_MISMATCH before any
     * drain or invalidation, preserving a healthy handle.
     */
    private fun validateGrantCompatibility(
        expected: RecoveryRoomSnapshot,
        targetOperation: String,
        session: ServiceSession,
        configured: ServiceOrigin,
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
            if (reserved != targetOperation) {
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

    private suspend fun reserveGrant(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
        fence: Any,
    ): PreparedSessionObservation {
        ensureCurrent(fence)
        if (expected.phase == RecoveryRoomPhase.PREPARING || expected.phase == RecoveryRoomPhase.TRANSITIONING) {
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
        if (expected.phase == RecoveryRoomPhase.ACTIVE) {
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
