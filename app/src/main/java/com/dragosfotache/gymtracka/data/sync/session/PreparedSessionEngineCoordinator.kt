package com.dragosfotache.gymtracka.data.sync.session

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.MAX_SNAPSHOT_DELIVERY_PAGES
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotStartResult
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.store.CanonicalConflictException
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStorageException
import com.dragosfotache.gymtracka.data.sync.store.PullStorageException
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.PushStorageException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionCollisionException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionGraphException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PullTailCommitResult
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageActiveException
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageIntentException
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageStorageException
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StalePullCheckpointException
import com.dragosfotache.gymtracka.data.sync.store.StalePushClaimException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.StaleSnapshotStageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

/**
 * Inert explicitly constructed private prepared-session engine coordinator.
 * Freshly admits one matching private PREPARING Room / PREPARED vault pair
 * through the R09b reader, then reuses R08 complete snapshot establishment
 * and the R07 admitted shared push-before-pull delivery with valid-tail
 * bookkeeping under uninterrupted physical-DB ownership.
 *
 * [bootstrap] stops at a private canonical baseline and bounded actual tail.
 * [completePreparation] separately coordinates required protected publication
 * plus Room ACTIVE/ready in one transaction and exact vault phase promotion;
 * [recoverCompletion] recovers only that inert two-store completion gap.
 * None establishes convergence, repository/UI rebinding or production
 * construction. Ordinary stores remain ACTIVE-only, and prepared runs still
 * reject public [PushRun.publishProjection] before any barrier or visible
 * write; only the explicit registered completion path admits publication.
 *
 * One coordinator owns a single preparation reader and a single shared
 * refresh coordinator for one Room/vault pair. Exactly one
 * application-owned [PushRunOwner] per physical database is shared with
 * all other drivers; this coordinator creates no owner. Constructor
 * performs no I/O, clock, UUID or HTTP work. Result construction is
 * internal and conveys no reusable authority. Types and displays are
 * fixed and redacted with no secret-bearing causes.
 */
internal class PreparedSessionEngineCoordinator(
    private val database: GymDatabase,
    private val origin: ServiceOrigin,
    private val vault: SessionVault,
    private val authApi: ServiceAuthApi,
    private val transport: ServiceHttpTransport,
    private val runOwner: PushRunOwner,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val authority = RoomSessionAuthority(database)
    private val preparation = SessionPreparationCoordinator(origin, authority, vault)
    private val refresh = SessionRefreshCoordinator(origin, vault, authApi, clockMillis, uuidProvider)

    private val stateLock = Any()
    private var currentFence: Any = Any()
    private var currentLease: SessionRefreshLease? = null
    private var registeredPermit: RegisteredPermit? = null

    /** Privately stored issued fence for the one registered permit. */
    private data class RegisteredPermit(
        val permit: PreparedSessionEnginePermit,
        val fence: Any,
    )

    /**
     * Concrete owner check for [PreparedSessionEnginePermit.isCurrent]:
     * true only for the privately registered instance under its stored
     * issued fence. Only private registration after fresh admission can
     * make it true; every fence rotation, invalidation and invocation
     * settlement clears it.
     */
    internal fun isPermitCurrent(permit: PreparedSessionEnginePermit): Boolean =
        synchronized(stateLock) {
            val registered = registeredPermit
            registered != null && registered.permit === permit && registered.fence === currentFence
        }

    /**
     * Synchronously and idempotently retires in-flight process admission
     * and its lease without waiting for owner, Room or HTTP work. Never
     * cancels or undoes already-started writes and performs no durable
     * cleanup.
     */
    fun invalidate() {
        synchronized(stateLock) {
            currentLease?.invalidate()
            currentLease = null
            registeredPermit = null
            currentFence = Any()
        }
    }

    /**
     * Bootstraps the admitted private session: retained-stage fast path,
     * explicit restart, chunked R08 snapshot download plus completion, or
     * eligible durable-baseline recovery, followed by the R07 admitted
     * shared tail on the same run. Holds the one shared owner continuously
     * from epoch observation through all work with no release, reacquire
     * or nested admission; HTTP stays outside Room.
     */
    suspend fun bootstrap(
        stageId: String,
        maxSnapshotPages: Int = 100,
        snapshotPageLimit: Int = 200,
        maxPushMutations: Int = 100,
        maxPullPages: Int = 100,
        pullPageLimit: Int = 100,
        restartRetainedStage: Boolean = false,
    ): PreparedBootstrapResult {
        currentCoroutineContext().ensureActive()
        val canonicalStageId = try {
            requireWorkoutUuid(stageId, "stageId")
        } catch (_: Exception) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        if (maxSnapshotPages < 1) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        if (snapshotPageLimit !in 1..500) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        if (maxPushMutations !in 1..100) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        if (maxPullPages !in 1..100) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        if (pullPageLimit !in 1..200) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        currentCoroutineContext().ensureActive()
        val fence = openFence()
        var lease: SessionRefreshLease? = null
        var permitRef: PreparedSessionEnginePermit? = null
        try {
            val observation = admitPrepared(fence)
            val identity = observation.room.identity
                ?: throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
            val admittedPermit = PreparedSessionEnginePermit(
                database = database,
                origin = origin,
                accountId = identity.binding.accountId,
                sessionGeneration = identity.binding.sessionGeneration,
                operationId = identity.operationId,
                journalRevision = observation.room.revision,
                owner = this,
            )
            synchronized(stateLock) {
                if (fence !== currentFence) {
                    throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
                }
                registeredPermit = RegisteredPermit(admittedPermit, fence)
            }
            permitRef = admittedPermit
            val leaseCreated = SessionRefreshLease(
                binding = identity.binding,
                operationId = identity.operationId,
                phase = VaultSessionPhase.PREPARED,
                authorizationCheck = { checkLeaseAuthorization(fence, admittedPermit) },
            )
            registerLease(fence, leaseCreated)
            lease = leaseCreated
            val permit = admittedPermit
            val session = RepositorySession(
                accountId = identity.binding.accountId,
                sessionGeneration = identity.binding.sessionGeneration,
            )
            val preparedStore = PushStore(database, session, runOwner, permit)
            val api = SessionRefreshingGymSyncApi(refresh, leaseCreated, transport)
            return preparedStore.withPreparedRun(
                permit = permit,
                readEpoch = { readDurableEpoch(permit, identity.binding.accountId) },
            ) { run ->
                bootstrapLocked(
                    fence = fence,
                    run = run,
                    store = preparedStore,
                    api = api,
                    stageId = canonicalStageId,
                    maxSnapshotPages = maxSnapshotPages,
                    snapshotPageLimit = snapshotPageLimit,
                    maxPushMutations = maxPushMutations,
                    maxPullPages = maxPullPages,
                    pullPageLimit = pullPageLimit,
                    restartRetainedStage = restartRetainedStage,
                )
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: PreparedEngineException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(mapAuthorityFailure(e.failure))
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        } catch (e: SessionRefreshException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(mapRefreshFailure(e.failure))
        } catch (e: SyncApiException.ReauthenticationRequired) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
        } catch (e: SyncApiException.AuthenticationRequired) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
        } catch (e: SyncApiException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw e
        } catch (e: StaleSessionException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: StaleCanonicalEpochException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: StalePushClaimException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: StalePullCheckpointException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: StaleSnapshotStageException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: SnapshotStageActiveException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: SnapshotStageIntentException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: CanonicalStorageException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: CanonicalConflictException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: PushStorageException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: PullStorageException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: SnapshotStageStorageException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: SQLiteException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } finally {
            lease?.invalidate()
            synchronized(stateLock) {
                if (currentLease === lease) {
                    currentLease = null
                }
                if (registeredPermit?.permit === permitRef) {
                    registeredPermit = null
                }
            }
        }
    }

    /**
     * Completes one explicitly constructed private preparation for a
     * fresh/no-legacy pair: fresh actual R07 tail (actual push Empty then
     * actual pull terminal callback) plus complete projection and ACTIVE
     * journal/device in ONE Room transaction, followed by exact
     * PREPARED to ACTIVE vault CAS with durable recovery of the
     * Room-committed/vault-unpromoted gap. Holds the one shared owner
     * continuously through tail, barrier/publication, Room completion, vault
     * CAS and final rereads. ACTIVE here is a stored label; no application
     * construction, recovered handle, UI selection or repository rebinding
     * is performed.
     *
     * Already Room ACTIVE takes the recovery path below without HTTP,
     * barrier, redownload or another publication. A genuinely non-PREPARING
     * signed-out/transitional initial read returns NOT_READY before any
     * vault probe. Retained stages, missing baselines/checkpoints, bounded
     * delivery stops, outstanding work and an explicitly invalid publication
     * lease return NOT_READY unchanged. Missing PREPARED credentials on a
     * PREPARING pair stay closed NOT_PREPARED; identity, race, corruption,
     * operational barrier and other I/O failures throw the closed
     * categories. Cancellation has caller precedence. Invalid budgets fail
     * before retiring valid work.
     */
    suspend fun completePreparation(
        barrier: ProjectionPublicationBarrier,
        maxPushMutations: Int = 100,
        maxPullPages: Int = 100,
        pullPageLimit: Int = 100,
    ): PreparedCompletionResult {
        currentCoroutineContext().ensureActive()
        if (maxPushMutations !in 1..100) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        if (maxPullPages !in 1..100) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        if (pullPageLimit !in 1..200) {
            throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
        }
        currentCoroutineContext().ensureActive()
        val fence = openFence()
        var lease: SessionRefreshLease? = null
        var permitRef: PreparedSessionEnginePermit? = null
        try {
            val preRead = readRoomForCompletion(fence)
            if (preRead.phase == RecoveryRoomPhase.ACTIVE) {
                ensureFenceCurrent(fence)
                return recoverLocked(fence)
            }
            if (preRead.phase != RecoveryRoomPhase.PREPARING) {
                ensureFenceCurrent(fence)
                return PreparedCompletionResult(PreparedCompletionStatus.NOT_READY, null, null)
            }
            val observation = admitPrepared(fence)
            val identity = observation.room.identity
                ?: throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
            val admittedPermit = PreparedSessionEnginePermit(
                database = database,
                origin = origin,
                accountId = identity.binding.accountId,
                sessionGeneration = identity.binding.sessionGeneration,
                operationId = identity.operationId,
                journalRevision = observation.room.revision,
                owner = this,
            )
            synchronized(stateLock) {
                if (fence !== currentFence) {
                    throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
                }
                registeredPermit = RegisteredPermit(admittedPermit, fence)
            }
            permitRef = admittedPermit
            val leaseCreated = SessionRefreshLease(
                binding = identity.binding,
                operationId = identity.operationId,
                phase = VaultSessionPhase.PREPARED,
                authorizationCheck = { checkLeaseAuthorization(fence, admittedPermit) },
            )
            registerLease(fence, leaseCreated)
            lease = leaseCreated
            val permit = admittedPermit
            val session = RepositorySession(
                accountId = identity.binding.accountId,
                sessionGeneration = identity.binding.sessionGeneration,
            )
            val preparedStore = PushStore(database, session, runOwner, permit)
            val api = SessionRefreshingGymSyncApi(refresh, leaseCreated, transport)
            return preparedStore.withPreparedRun(
                permit = permit,
                readEpoch = { readDurableEpoch(permit, identity.binding.accountId) },
            ) { run ->
                completeLocked(
                    fence = fence,
                    run = run,
                    store = preparedStore,
                    api = api,
                    barrier = barrier,
                    permit = permit,
                    lease = leaseCreated,
                    identity = identity,
                    maxPushMutations = maxPushMutations,
                    maxPullPages = maxPullPages,
                    pullPageLimit = pullPageLimit,
                )
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: PreparedEngineException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(mapAuthorityFailure(e.failure))
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        } catch (e: SessionRefreshException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(mapRefreshFailure(e.failure))
        } catch (e: SyncApiException.ReauthenticationRequired) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
        } catch (e: SyncApiException.AuthenticationRequired) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
        } catch (e: SyncApiException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw e
        } catch (e: StaleSessionException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: StaleCanonicalEpochException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: StalePushClaimException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: StalePullCheckpointException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: StaleSnapshotStageException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: SnapshotStageActiveException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: SnapshotStageIntentException) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        } catch (e: ProjectionGraphException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: ProjectionCollisionException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: CanonicalStorageException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: CanonicalConflictException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: PushStorageException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: PullStorageException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: SnapshotStageStorageException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (e: SQLiteException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } finally {
            lease?.invalidate()
            synchronized(stateLock) {
                if (currentLease === lease) {
                    currentLease = null
                }
                if (registeredPermit?.permit === permitRef) {
                    registeredPermit = null
                }
            }
        }
    }

    /**
     * Recovers the inert Room-committed/vault-unpromoted completion gap with
     * no HTTP, refresh, clock, UUID, barrier, publication, domain or journal
     * work. Holds the same shared owner (never a nested prepared admission)
     * so newest-invocation fencing plus owner serialization prevents overlap
     * with bootstrap/completion. Non-ACTIVE Room returns NOT_READY without
     * vault promotion. Strict configured-origin ACTIVE Room with a full ready
     * device tuple, complete materialized clean cache, valid nonnull
     * checkpoint and no stage/outbox/local roots promotes only a matching
     * PREPARED vault via phase-only CAS; a matching ACTIVE vault is
     * read-only idempotence with no extra revision. COMPLETED itself
     * constructs no handle. Cancellation has caller precedence.
     */
    suspend fun recoverCompletion(): PreparedCompletionResult {
        currentCoroutineContext().ensureActive()
        val fence = openFence()
        try {
            return recoverLocked(fence)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: PreparedEngineException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(mapAuthorityFailure(e.failure))
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        } catch (e: SQLiteException) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        }
    }

    override fun toString(): String = "PreparedSessionEngineCoordinator(redacted)"

    /** Pinned post-tail preparation evidence: the fresh post-tail PREPARED vault record/revision (never admission's). */
    private data class PinnedPrepared(
        val vaultRevision: Long,
        val record: VaultSessionRecord,
    )

    /** Combined Room publication/activation outcome: committed values or a read-only new-intent refusal. */
    private sealed interface RoomCompletionOutcome {
        data class Completed(val newRevision: Long, val cacheEpoch: Long) : RoomCompletionOutcome
        data object NotReady : RoomCompletionOutcome
    }

    /**
     * Local read-only control signal for readiness inside one Room
     * transaction. Carries the known cache epoch when available for
     * NOT_READY reporting. Never escapes: callers map it to NOT_READY
     * before any commit or to a closed race failure after one.
     */
    private class RecoveryNotReady(val cacheEpoch: Long? = null) : RuntimeException()

    /**
     * Single-owner completion body on the admitted prepared run. Retained
     * stages and missing baselines/checkpoints return NOT_READY without
     * delivery or hidden abandonment. Otherwise the admitted R07
     * push-before-pull seam runs and only an actual push Empty plus an
     * actual committed terminal tail with no outstanding work proceeds. The
     * refresh lease retires before promotion, post-tail Room/vault evidence
     * pins fresh (never admission's), and the required barrier plus the ONE
     * combined publication/activation Room transaction run under the same
     * owner/handling lock, followed by exact phase-only vault CAS and stable
     * ACTIVE pair rereads. Typed delivery stops are preserved in NOT_READY.
     */
    private suspend fun completeLocked(
        fence: Any,
        run: PushRun,
        store: PushStore,
        api: SessionRefreshingGymSyncApi,
        barrier: ProjectionPublicationBarrier,
        permit: PreparedSessionEnginePermit,
        lease: SessionRefreshLease,
        identity: RecoveryIdentity,
        maxPushMutations: Int,
        maxPullPages: Int,
        pullPageLimit: Int,
    ): PreparedCompletionResult {
        ensureFenceCurrent(fence)
        val accountId = identity.binding.accountId
        val generation = identity.binding.sessionGeneration
        val retained = run.readSnapshotStage()
        ensureFenceCurrent(fence)
        if (retained != null) {
            ensureFenceCurrent(fence)
            return PreparedCompletionResult(PreparedCompletionStatus.NOT_READY, run.observedCacheEpoch(), null)
        }
        val checkpoint = run.readPullCheckpoint()
        ensureFenceCurrent(fence)
        if (checkpoint == null) {
            ensureFenceCurrent(fence)
            return PreparedCompletionResult(PreparedCompletionStatus.NOT_READY, run.observedCacheEpoch(), null)
        }
        var tailCommit: PullTailCommitResult? = null
        val syncDriver = SyncDeliveryLoop(store, api, clockMillis)
        val delivery = syncDriver.deliver(run, maxPushMutations, maxPullPages, pullPageLimit) { committed ->
            tailCommit = committed
        }
        ensureFenceCurrent(fence)
        val tail = tailCommit
        if (delivery.push.stop != PushDeliveryStop.Empty ||
            delivery.pull == null ||
            delivery.pull.stop != PullDeliveryStop.Tail ||
            tail == null ||
            tail.hasOutstandingLocalWork
        ) {
            ensureFenceCurrent(fence)
            return PreparedCompletionResult(PreparedCompletionStatus.NOT_READY, run.observedCacheEpoch(), delivery)
        }
        lease.invalidate()
        ensureFenceCurrent(fence)
        val pinned = pinPostTailPrepared(fence, identity, permit.journalRevision)
        ensureFenceCurrent(fence)
        val projection = ProjectionStore(database, RepositorySession(accountId, generation), barrier)
        val roomResult = run.withPreparedCompletionLock(projection, permit) {
            acquireAndCompleteRoom(
                fence = fence,
                projection = projection,
                permit = permit,
                accountId = accountId,
                generation = generation,
                expectedEpoch = run.observedCacheEpoch(),
                tail = tail,
                barrier = barrier,
            )
        }
        ensureFenceCurrent(fence)
        val completed = roomResult as? RoomCompletionOutcome.Completed
        if (completed == null) {
            ensureFenceCurrent(fence)
            return PreparedCompletionResult(PreparedCompletionStatus.NOT_READY, run.observedCacheEpoch(), delivery)
        }
        synchronized(stateLock) {
            if (registeredPermit?.permit === permit) {
                registeredPermit = null
            }
        }
        if (pinned.record.binding != identity.binding || pinned.record.operationId != identity.operationId) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        ensureFenceCurrent(fence)
        val roomActive = readRoomForCompletion(fence)
        if (roomActive.phase != RecoveryRoomPhase.ACTIVE ||
            roomActive.revision != completed.newRevision ||
            roomActive.sessionGeneration != generation ||
            roomActive.cachedAccountId != accountId
        ) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        val activeIdentity = roomActive.identity
        if (activeIdentity == null ||
            activeIdentity.binding != identity.binding ||
            activeIdentity.operationId != identity.operationId
        ) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        if (!roomActive.baselineComplete) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        }
        verifyCompletionCache(fence, accountId, completed.cacheEpoch)
        ensureFenceCurrent(fence)
        val vaultPinned = loadVaultForCompletion(fence)
        if (vaultPinned.revision != pinned.vaultRevision) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        val pinnedRecord = vaultPinned.record
        if (pinnedRecord == null ||
            pinnedRecord.binding != identity.binding ||
            pinnedRecord.operationId != identity.operationId ||
            pinnedRecord.phase != VaultSessionPhase.PREPARED ||
            pinnedRecord.session.account.accountId != accountId
        ) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        if (!vaultRecordsEqual(pinnedRecord, pinned.record)) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        if (vaultPinned.revision == Long.MAX_VALUE) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        }
        ensureFenceCurrent(fence)
        val preCas = try {
            readFullReady(fence)
        } catch (_: RecoveryNotReady) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        if (preCas.revision != completed.newRevision ||
            preCas.identity.binding != identity.binding ||
            preCas.identity.operationId != identity.operationId ||
            preCas.cacheEpoch != completed.cacheEpoch ||
            preCas.cursor != tail.checkpoint.cursor ||
            preCas.lastSuccessMillis != tail.checkpoint.lastSuccessMillis
        ) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        ensureFenceCurrent(fence)
        verifyCompletionStable(fence, preCas, vaultPinned.revision, pinnedRecord)
        ensureFenceCurrent(fence)
        val promoted = casVaultForCompletion(
            fence,
            vaultPinned.revision,
            pinnedRecord.copy(phase = VaultSessionPhase.ACTIVE),
        )
        val promotedRecord = promoted.record
        if (promotedRecord == null || !vaultRecordsEqual(promotedRecord, pinnedRecord.copy(phase = VaultSessionPhase.ACTIVE))) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        verifyCompletionStable(fence, preCas, promoted.revision, promotedRecord)
        ensureFenceCurrent(fence)
        return PreparedCompletionResult(PreparedCompletionStatus.COMPLETED, completed.cacheEpoch, delivery)
    }

    /**
     * Shared inert completion recovery on the caller's fence: no HTTP,
     * refresh, clock, UUID, barrier, publication, domain or journal writes.
     * Holds the same shared owner (never a nested prepared admission) so
     * fencing plus owner serialization prevents overlap with
     * bootstrap/completion. Used both by [recoverCompletion] and by the
     * already-ACTIVE shortcut of [completePreparation].
     */
    private suspend fun recoverLocked(fence: Any): PreparedCompletionResult {
        return runOwner.mutex.withLock {
            ensureFenceCurrent(fence)
            val first = try {
                readFullReady(fence)
            } catch (e: RecoveryNotReady) {
                currentCoroutineContext().ensureActive()
                ensureFenceCurrent(fence)
                return@withLock PreparedCompletionResult(
                    PreparedCompletionStatus.NOT_READY,
                    e.cacheEpoch,
                    null,
                )
            }
            ensureFenceCurrent(fence)
            val identity = first.identity
            val readyEpoch = first.cacheEpoch
            val syncCursor = first.cursor
            val syncLastSuccess = first.lastSuccessMillis
            ensureFenceCurrent(fence)
            val vaultSnapshot = loadVaultForCompletion(fence)
            val record = vaultSnapshot.record
            if (record == null) {
                ensureFenceCurrent(fence)
                return@withLock PreparedCompletionResult(PreparedCompletionStatus.NOT_READY, readyEpoch, null)
            }
            if (record.binding != identity.binding || record.operationId != identity.operationId) {
                ensureFenceCurrent(fence)
                throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
            }
            ensureFenceCurrent(fence)
            val second = try {
                readFullReady(fence)
            } catch (_: RecoveryNotReady) {
                currentCoroutineContext().ensureActive()
                ensureFenceCurrent(fence)
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            if (second.revision != first.revision ||
                second.identity != identity ||
                second.cacheEpoch != readyEpoch ||
                second.cursor != syncCursor ||
                second.lastSuccessMillis != syncLastSuccess
            ) {
                ensureFenceCurrent(fence)
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            if (record.phase == VaultSessionPhase.PREPARED) {
                if (vaultSnapshot.revision == Long.MAX_VALUE) {
                    ensureFenceCurrent(fence)
                    throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
                }
                ensureFenceCurrent(fence)
                verifyCompletionStable(fence, second, vaultSnapshot.revision, record)
                val promoted = casVaultForCompletion(
                    fence,
                    vaultSnapshot.revision,
                    record.copy(phase = VaultSessionPhase.ACTIVE),
                )
                val promotedRecord = promoted.record
                if (promotedRecord == null || !vaultRecordsEqual(promotedRecord, record.copy(phase = VaultSessionPhase.ACTIVE))) {
                    ensureFenceCurrent(fence)
                    throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
                }
                verifyCompletionStable(fence, second, promoted.revision, promotedRecord)
                ensureFenceCurrent(fence)
                return@withLock PreparedCompletionResult(PreparedCompletionStatus.COMPLETED, readyEpoch, null)
            }
            if (record.phase == VaultSessionPhase.ACTIVE) {
                verifyCompletionStable(fence, second, vaultSnapshot.revision, record)
                ensureFenceCurrent(fence)
                return@withLock PreparedCompletionResult(PreparedCompletionStatus.COMPLETED, readyEpoch, null)
            }
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
    }

    /**
     * Pins fresh post-tail preparation evidence (never admission's): exact
     * PREPARING identity/revision across alternating Room reads plus the
     * matching PREPARED full record. Vault rotation during same-session tail
     * is legitimate, so the fresh post-tail revision/record wins. Checked
     * Room/vault counter exhaustion throws before any Room completion write
     * so avoidable overflow never creates the gap.
     */
    private suspend fun pinPostTailPrepared(
        fence: Any,
        identity: RecoveryIdentity,
        journalRevision: Long,
    ): PinnedPrepared {
        ensureFenceCurrent(fence)
        if (journalRevision == Long.MAX_VALUE) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        }
        val roomFirst = readRoomForCompletion(fence)
        if (roomFirst.phase != RecoveryRoomPhase.PREPARING || roomFirst.revision != journalRevision) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        val firstIdentity = roomFirst.identity
        if (firstIdentity == null ||
            firstIdentity.binding != identity.binding ||
            firstIdentity.operationId != identity.operationId
        ) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        ensureFenceCurrent(fence)
        val vaultSnapshot = loadVaultForCompletion(fence)
        val record = vaultSnapshot.record
        if (record == null ||
            record.binding != identity.binding ||
            record.operationId != identity.operationId ||
            record.phase != VaultSessionPhase.PREPARED ||
            record.session.account.accountId != identity.binding.accountId
        ) {
            ensureFenceCurrent(fence)
            if (record == null) {
                throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
            }
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        if (vaultSnapshot.revision == Long.MAX_VALUE) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        }
        ensureFenceCurrent(fence)
        val roomSecond = readRoomForCompletion(fence)
        if (roomSecond != roomFirst) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        ensureFenceCurrent(fence)
        val vaultSecond = loadVaultForCompletion(fence)
        if (vaultSecond.revision != vaultSnapshot.revision) {
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        val recordSecond = vaultSecond.record
        if (recordSecond == null || !vaultRecordsEqual(recordSecond, record)) {
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        ensureFenceCurrent(fence)
        val roomThird = readRoomForCompletion(fence)
        if (roomThird != roomFirst) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        ensureFenceCurrent(fence)
        return PinnedPrepared(
            vaultRevision = vaultSnapshot.revision,
            record = record,
        )
    }

    /**
     * Acquires the required publication barrier outside Room under the same
     * owner/handling lock, then runs the ONE combined publication/activation
     * Room transaction. New barrier intent or an invalid publication lease
     * returns NotReady without ACTIVE writes; the Room commit followed by a
     * lease close failure propagates without promotion, leaving the retained
     * gap for [recoverCompletion]. No vault/HTTP/clock/UUID work inside Room.
     */
    private suspend fun acquireAndCompleteRoom(
        fence: Any,
        projection: ProjectionStore,
        permit: PreparedSessionEnginePermit,
        accountId: String,
        generation: Long,
        expectedEpoch: Long,
        tail: PullTailCommitResult,
        barrier: ProjectionPublicationBarrier,
    ): RoomCompletionOutcome {
        ensureFenceCurrent(fence)
        val request = ProjectionPublicationRequest(
            accountId = accountId,
            sessionGeneration = generation,
            expectedCacheEpoch = expectedEpoch,
        )
        val publicationLease: ProjectionPublicationLease = try {
            barrier.acquire(request)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        }
        try {
            try {
                return database.withTransaction {
                    completeRoomTransaction(
                        fence = fence,
                        projection = projection,
                        permit = permit,
                        accountId = accountId,
                        generation = generation,
                        expectedEpoch = expectedEpoch,
                        tail = tail,
                        publicationLease = publicationLease,
                    )
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: ProjectionLeaseInvalidException) {
                currentCoroutineContext().ensureActive()
                ensureFenceCurrent(fence)
                return RoomCompletionOutcome.NotReady
            }
        } finally {
            publicationLease.close()
        }
    }

    /**
     * The ONE combined Room transaction: exact tail checkpoint row recheck,
     * reused paired whole-graph publication, and journal/device activation
     * with full post-write readiness verification before commit. Every
     * precommit graph, storage, count, trigger-rewrite, lease and
     * cancellation failure rolls domain, cache, journal and device back
     * together. Tail cursor/last-success survive publication/completion
     * unchanged; catalogue, foreign scopes, local roots and uncertain intent
     * stay preserved.
     */
    private suspend fun completeRoomTransaction(
        fence: Any,
        projection: ProjectionStore,
        permit: PreparedSessionEnginePermit,
        accountId: String,
        generation: Long,
        expectedEpoch: Long,
        tail: PullTailCommitResult,
        publicationLease: ProjectionPublicationLease,
    ): RoomCompletionOutcome {
        currentCoroutineContext().ensureActive()
        if (!database.inTransaction()) {
            throw IllegalStateException("Completion requires an active transaction")
        }
        if (tail.checkpoint.accountId != accountId ||
            tail.checkpoint.sessionGeneration != generation ||
            tail.checkpoint.expectedCacheEpoch != expectedEpoch
        ) {
            throw StalePullCheckpointException()
        }
        val tailState = database.syncDao().getSyncState(accountId)
        if (tailState == null ||
            tailState.cursor != tail.checkpoint.cursor ||
            tailState.lastSuccessMillis != tail.checkpoint.lastSuccessMillis
        ) {
            throw StalePullCheckpointException()
        }
        currentCoroutineContext().ensureActive()
        val publication = projection.publishPreparedInsideTransaction(
            accountId,
            expectedEpoch,
            publicationLease,
            permit,
        )
        if (publication.status == ProjectionPublicationStatus.PROTECTED ||
            publication.status == ProjectionPublicationStatus.INCOMPLETE_BASELINE
        ) {
            currentCoroutineContext().ensureActive()
            return RoomCompletionOutcome.NotReady
        }
        val finalEpoch = publication.cacheEpoch
        val postPublication = database.syncDao().getSyncState(accountId)
        if (postPublication == null ||
            postPublication.cursor != tail.checkpoint.cursor ||
            postPublication.lastSuccessMillis != tail.checkpoint.lastSuccessMillis
        ) {
            throw StalePullCheckpointException()
        }
        currentCoroutineContext().ensureActive()
        val newRevision = authority.completeToActiveInTransaction(permit)
        val cache = database.syncCanonicalDao().getAccountCache(accountId)
        if (cache == null ||
            !cache.baselineComplete ||
            cache.cacheEpoch != finalEpoch ||
            cache.projectionDirty ||
            cache.materializedEpoch != finalEpoch
        ) {
            throw CanonicalStorageException()
        }
        val rereadState = database.syncDao().getSyncState(accountId)
        if (rereadState == null ||
            rereadState.cursor != tail.checkpoint.cursor ||
            rereadState.lastSuccessMillis != tail.checkpoint.lastSuccessMillis
        ) {
            throw StalePullCheckpointException()
        }
        if (database.syncSnapshotDao().getStageForAccount(accountId) != null) {
            throw SnapshotStageActiveException()
        }
        if (database.syncDao().hasOutboxForAccount(accountId)) {
            throw SnapshotStageIntentException()
        }
        publicationLease.checkValid()
        authority.verifyActiveInTransaction(permit, newRevision)
        val finalCache = database.syncCanonicalDao().getAccountCache(accountId)
        if (finalCache == null ||
            !finalCache.baselineComplete ||
            finalCache.cacheEpoch != finalEpoch ||
            finalCache.projectionDirty ||
            finalCache.materializedEpoch != finalEpoch
        ) {
            throw CanonicalStorageException()
        }
        val finalState = database.syncDao().getSyncState(accountId)
        if (finalState == null ||
            finalState.cursor != tail.checkpoint.cursor ||
            finalState.lastSuccessMillis != tail.checkpoint.lastSuccessMillis
        ) {
            throw StalePullCheckpointException()
        }
        if (database.syncSnapshotDao().getStageForAccount(accountId) != null) {
            throw SnapshotStageActiveException()
        }
        if (database.syncDao().hasOutboxForAccount(accountId)) {
            throw SnapshotStageIntentException()
        }
        ensureFenceCurrent(fence)
        currentCoroutineContext().ensureActive()
        return RoomCompletionOutcome.Completed(newRevision, finalEpoch)
    }

    /**
     * Verifies coherent complete materialization for the finished epoch
     * outside the commit transaction (owner still held): complete baseline,
     * clean projection at exactly the finished epoch. Incoherence fails
     * closed without vault promotion.
     */
    private suspend fun verifyCompletionCache(fence: Any, accountId: String, epoch: Long) {
        ensureFenceCurrent(fence)
        val cache = database.syncCanonicalDao().getAccountCache(accountId)
        if (cache == null ||
            !cache.baselineComplete ||
            cache.cacheEpoch != epoch ||
            cache.projectionDirty ||
            cache.materializedEpoch != epoch
        ) {
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        }
        ensureFenceCurrent(fence)
    }

    /**
     * Alternating Room/vault/Room/vault/Room rereads establishing the stable
     * exact ACTIVE pair before COMPLETED: Room ACTIVE at the committed
     * revision with the operation identity and complete baseline, vault
     * ACTIVE at the promoted revision with the semantically exact grant
     * (pending operation/key/body/token equality on decoded values).
     * Already-started writes may have committed after invalidation or
     * cancellation; only a current fence may return completion.
     */
    /**
     * Coherent full READY pin for completion and recovery: strict ACTIVE
     * journal with the ready device tuple, complete materialized clean cache
     * at its epoch, no selected stage, empty selected outbox, valid nonnull
     * checkpoint and no local-only roots. Unreadiness throws the local
     * [RecoveryNotReady] signal (callers map it to NOT_READY before any
     * commit or to a closed race failure after one); corruption, identity
     * and I/O failures throw closed errors. No vault work happens here.
     */
    private data class FullReadyPinned(
        val revision: Long,
        val identity: RecoveryIdentity,
        val cacheEpoch: Long,
        val cursor: String,
        val lastSuccessMillis: Long?,
    )

    private suspend fun readFullReady(fence: Any): FullReadyPinned {
        ensureFenceCurrent(fence)
        try {
            return database.withTransaction {
                currentCoroutineContext().ensureActive()
                val active = authority.readActiveReadyInTransaction()
                val snapshot = active.snapshot
                val identity = snapshot.identity
                    ?: throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY)
                if (identity.binding.origin != origin) {
                    throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
                }
                val accountId = identity.binding.accountId
                val cache = active.cache
                if (cache == null ||
                    !cache.baselineComplete ||
                    cache.projectionDirty ||
                    cache.materializedEpoch != cache.cacheEpoch
                ) {
                    throw RecoveryNotReady(cache?.cacheEpoch)
                }
                val readyEpoch = cache.cacheEpoch
                if (database.syncSnapshotDao().getStageForAccount(accountId) != null) {
                    throw RecoveryNotReady(readyEpoch)
                }
                if (database.syncDao().hasOutboxForAccount(accountId)) {
                    throw RecoveryNotReady(readyEpoch)
                }
                val state = database.syncDao().getSyncState(accountId)
                if (state == null || state.cursor == null) {
                    throw RecoveryNotReady(readyEpoch)
                }
                val cursor = state.cursor
                if (cursor.isEmpty() ||
                    (state.lastSuccessMillis != null && state.lastSuccessMillis < 0L)
                ) {
                    throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY)
                }
                val authorityDao = database.syncSessionAuthorityDao()
                if (authorityDao.hasLocalOnlyWorkoutRoot() ||
                    authorityDao.hasLocalOnlyRoutineRoot() ||
                    authorityDao.hasLocalOnlyCustomExerciseRoot()
                ) {
                    throw RoomSessionAuthorityException(
                        RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION,
                    )
                }
                currentCoroutineContext().ensureActive()
                FullReadyPinned(
                    revision = snapshot.revision,
                    identity = identity,
                    cacheEpoch = readyEpoch,
                    cursor = cursor,
                    lastSuccessMillis = state.lastSuccessMillis,
                )
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RecoveryNotReady) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (e: PreparedEngineException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            if (e.failure == RoomSessionAuthorityFailure.STALE_AUTHORITY) {
                throw RecoveryNotReady()
            }
            throw PreparedEngineException(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        }
    }

    /**
     * Alternating Room/vault/Room/vault/Room rereads establishing the stable
     * exact stored pair around vault CAS: before a new CAS with the PREPARED
     * expected record, and after promotion (or read-only idempotence) with
     * the ACTIVE expected record. Every Room leg is a coherent full READY
     * observation pinned to the exact expected revision, identity, epoch and
     * checkpoint full row, so device cache-state rewrites, dirty/materialized
     * drift and outbox/stage/checkpoint/local-root drift during vault reads
     * fail closed with no promotion instead of returning COMPLETED. Every
     * vault leg requires the exact expected revision with the semantically
     * exact grant (pending operation/key/body/token equality on decoded
     * values). Already-started writes may have committed after invalidation
     * or cancellation; only a current fence may return completion.
     */
    private suspend fun verifyCompletionStable(
        fence: Any,
        expected: FullReadyPinned,
        vaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) {
        repeat(3) { index ->
            ensureFenceCurrent(fence)
            val current = try {
                readFullReady(fence)
            } catch (_: RecoveryNotReady) {
                currentCoroutineContext().ensureActive()
                ensureFenceCurrent(fence)
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            if (current != expected) {
                ensureFenceCurrent(fence)
                if (current.identity.binding != expected.identity.binding ||
                    current.identity.operationId != expected.identity.operationId
                ) {
                    throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
                }
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            if (index < 2) {
                ensureFenceCurrent(fence)
                val snapshot = loadVaultForCompletion(fence)
                if (snapshot.revision != vaultRevision) {
                    throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
                }
                val record = snapshot.record
                if (record == null || !vaultRecordsEqual(record, expectedRecord)) {
                    throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
                }
            }
        }
        ensureFenceCurrent(fence)
    }

    /** Strict fenced Room read for completion and recovery (no vault probe, no writes). */
    private suspend fun readRoomForCompletion(fence: Any): RecoveryRoomSnapshot {
        ensureFenceCurrent(fence)
        try {
            val snapshot = authority.read()
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(mapAuthorityFailure(e.failure))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        }
    }

    /** Fenced vault load with closed categories (no refresh, cleanup or rollback inference). */
    private suspend fun loadVaultForCompletion(fence: Any): VaultSnapshot {
        ensureFenceCurrent(fence)
        try {
            val snapshot = vault.load()
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        }
    }

    /**
     * Single exact phase-only vault CAS preserving the full grant and exact
     * pending operation/key/body/token bytes. No retry-over-conflict loop:
     * a mismatched, orphan or racing vault fails closed for later recovery.
     * CAS/load may commit despite an uncertain or canceled return; never
     * erase, rekey, refresh, compensate, restart generation or infer
     * rollback from an exception.
     */
    private suspend fun casVaultForCompletion(
        fence: Any,
        expectedRevision: Long,
        record: VaultSessionRecord,
    ): VaultSnapshot {
        ensureFenceCurrent(fence)
        try {
            val committed = vault.compareAndSet(expectedRevision, record)
            ensureFenceCurrent(fence)
            if (committed.revision != expectedRevision + 1L) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            val committedRecord = committed.record
            if (committedRecord == null || !vaultRecordsEqual(committedRecord, record)) {
                throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
            }
            return committed
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: PreparedEngineException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            if (e.failure == VaultFailure.CONFLICT) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            if (!isCurrentFence(fence)) {
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        }
    }

    /**
     * Fresh R09b admission without HTTP or Room writes. NotPreparing,
     * NeedsGrant and Blocked observations never admit; Blocked maps to
     * the closed categories. The returned observation is advisory only:
     * the vault revision is freshly reread with full binding, operation,
     * phase and grant-account equality before any permit exists, and every
     * later seam fences independently since a race after the final read
     * cannot be ruled out.
     */
    private suspend fun admitPrepared(fence: Any): PreparedSessionObservation {
        ensureFenceCurrent(fence)
        val recovery = try {
            preparation.recover()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionPreparationException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
        }
        ensureFenceCurrent(fence)
        val observation = when (recovery) {
            is SessionPreparationRecovery.NotPreparing ->
                throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
            is SessionPreparationRecovery.NeedsGrant ->
                throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
            is SessionPreparationRecovery.Blocked ->
                throw PreparedEngineException(mapPreparationFailure(recovery.failure))
            is SessionPreparationRecovery.Prepared -> recovery.observation
        }
        val identity = observation.room.identity
            ?: throw PreparedEngineException(PreparedEngineFailure.NOT_PREPARED)
        ensureFenceCurrent(fence)
        val snapshot = try {
            vault.load()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
        }
        ensureFenceCurrent(fence)
        val record = snapshot.record
        if (snapshot.revision != observation.vaultRevision) {
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
        if (record == null ||
            record.binding != identity.binding ||
            record.operationId != identity.operationId ||
            record.phase != VaultSessionPhase.PREPARED ||
            record.session.account.accountId != identity.binding.accountId
        ) {
            throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
        }
        verifyPreparedStable(fence, observation.room, observation.vaultRevision, record)
        ensureFenceCurrent(fence)
        return observation
    }

    /**
     * Reads the selected durable cache epoch coherently under strict
     * private authority. Runs inside the shared owner entry (after owner
     * acquisition, before run admission); admission rechecks the same
     * epoch again. Missing cache reads as epoch zero, never a fabricated
     * baseline.
     */
    private suspend fun readDurableEpoch(
        permit: PreparedSessionEnginePermit,
        accountId: String,
    ): Long {
        currentCoroutineContext().ensureActive()
        return database.withTransaction {
            currentCoroutineContext().ensureActive()
            authority.checkPreparedInTransaction(permit)
            val cache = database.syncCanonicalDao().getAccountCache(accountId)
            val epoch = cache?.cacheEpoch ?: 0L
            if (epoch < 0L ||
                (cache != null && (cache.materializedEpoch < 0L || cache.materializedEpoch > epoch))
            ) {
                throw IllegalStateException("Invalid prepared account cache")
            }
            currentCoroutineContext().ensureActive()
            epoch
        }
    }

    /**
     * Alternating post-recover Room/vault/Room/vault/Room stability
     * guards: every Room snapshot must exactly equal the admitted
     * PREPARING observation and every vault snapshot must hold the same
     * revision with a semantically exact record (full grant plus pending
     * operation/key/body/token equality on decoded values, never
     * reference identity). Revision drift fails as a race; content
     * mismatch fails identity. No refresh has run at this boundary, so
     * the revision pin is admission stability, not a post-rotation
     * constraint: later seams fence Room identity and vault binding
     * independently without pinning rotated revisions.
     */
    private suspend fun verifyPreparedStable(
        fence: Any,
        expectedRoom: RecoveryRoomSnapshot,
        expectedVaultRevision: Long,
        expectedRecord: VaultSessionRecord,
    ) {
        repeat(3) { index ->
            ensureFenceCurrent(fence)
            val room = try {
                authority.read()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: RoomSessionAuthorityException) {
                currentCoroutineContext().ensureActive()
                ensureFenceCurrent(fence)
                throw PreparedEngineException(mapAuthorityFailure(e.failure))
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureFenceCurrent(fence)
                throw PreparedEngineException(PreparedEngineFailure.AUTHORITY_UNAVAILABLE)
            }
            if (room != expectedRoom) {
                ensureFenceCurrent(fence)
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            if (index < 2) {
                ensureFenceCurrent(fence)
                val snapshot = try {
                    vault.load()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ensureFenceCurrent(fence)
                    throw PreparedEngineException(PreparedEngineFailure.VAULT_UNAVAILABLE)
                }
                ensureFenceCurrent(fence)
                val record = snapshot.record
                if (snapshot.revision != expectedVaultRevision) {
                    throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
                }
                if (record == null || !vaultRecordsEqual(record, expectedRecord)) {
                    throw PreparedEngineException(PreparedEngineFailure.IDENTITY_MISMATCH)
                }
            }
        }
        ensureFenceCurrent(fence)
    }

    /**
     * Semantic vault record equality: binding, operation, phase, full grant
     * and retained archive use value equality; restored pending attempts
     * compare exact operation, key, body text and token, never reference
     * identity.
     */
    private fun vaultRecordsEqual(actual: VaultSessionRecord, expected: VaultSessionRecord): Boolean =
        vaultRecordsSemanticEqual(actual, expected)

    /**
     * Lease authorization around refresh seams and transport admission /
     * results: rechecks the SAME registered exact private permit inside
     * a fresh Room transaction, requiring cacheState "preparing" plus
     * identity, revision and currentness before any credential or
     * network admission/result is used. A valid-type device cache-state
     * rewrite therefore fails here exactly as it would in a prepared
     * transaction. Vault rotation under the same session never
     * invalidates the run because only Room state is fenced here; the
     * refresh coordinator independently fences vault record identity.
     * Performs no vault, HTTP or clock work inside the transaction.
     * Any mismatch, unreadable store or retired invocation fails the
     * run closed without credential repair, cleanup or promotion.
     */
    private suspend fun checkLeaseAuthorization(
        fence: Any,
        permit: PreparedSessionEnginePermit,
    ) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentFence(fence)) {
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        try {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                authority.checkPreparedInTransaction(permit)
                currentCoroutineContext().ensureActive()
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentFence(fence)) {
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
    }

    /**
     * Single-owner bootstrap body on the admitted prepared run. Retained
     * stages return without snapshot, baseline, delivery or HTTP work.
     * Explicit restart abandons only the exact retained stage and then
     * forces a fresh snapshot with the new stage ID even when an older
     * complete baseline survives. Otherwise an eligible durable complete
     * baseline with a valid checkpoint and no stage recovers through the
     * shared tail without redownload. Snapshot budgets chunk into
     * same-driver, same-run continuations; exhaustion, transport failure
     * and expiry retain the stage with no in-call retry or restart, and
     * only an actually issued terminal invokes completion. Typed driver
     * stop summaries are preserved, never reinterpreted.
     */
    private suspend fun bootstrapLocked(
        fence: Any,
        run: PushRun,
        store: PushStore,
        api: SessionRefreshingGymSyncApi,
        stageId: String,
        maxSnapshotPages: Int,
        snapshotPageLimit: Int,
        maxPushMutations: Int,
        maxPullPages: Int,
        pullPageLimit: Int,
        restartRetainedStage: Boolean,
    ): PreparedBootstrapResult {
        ensureFenceCurrent(fence)
        val retained = run.readSnapshotStage()
        ensureFenceCurrent(fence)
        if (retained != null && !restartRetainedStage) {
            return PreparedBootstrapResult(run.observedCacheEpoch(), true, null, null, null)
        }
        var forcedSnapshot = false
        if (retained != null) {
            if (retained.stageId == stageId) {
                throw PreparedEngineException(PreparedEngineFailure.INVALID_INPUT)
            }
            run.abandonSnapshotStage(retained)
            ensureFenceCurrent(fence)
            forcedSnapshot = true
        }
        val syncDriver = SyncDeliveryLoop(store, api, clockMillis)
        if (!forcedSnapshot) {
            val checkpoint = run.readPullCheckpoint()
            ensureFenceCurrent(fence)
            if (checkpoint != null) {
                val delivery = syncDriver.deliver(run, maxPushMutations, maxPullPages, pullPageLimit)
                ensureFenceCurrent(fence)
                return PreparedBootstrapResult(run.observedCacheEpoch(), false, null, null, delivery)
            }
        }
        val snapshotDriver = SnapshotDeliveryLoop(store, api, clockMillis)
        var remaining = maxSnapshotPages
        val start = snapshotDriver.start(
            run,
            stageId,
            maxPushMutations,
            minOf(remaining, MAX_SNAPSHOT_DELIVERY_PAGES),
            snapshotPageLimit,
        )
        ensureFenceCurrent(fence)
        var download = start.download
        if (download != null) {
            remaining -= download.committedPages
        }
        val terminal = download ?: return PreparedBootstrapResult(run.observedCacheEpoch(), false, start, null, null)
        var current = terminal
        while (current.stop is SnapshotDownloadStop.LimitReached && remaining > 0) {
            current = snapshotDriver.continueDownload(
                run,
                current,
                minOf(remaining, MAX_SNAPSHOT_DELIVERY_PAGES),
            )
            ensureFenceCurrent(fence)
            remaining -= current.committedPages
        }
        if (current.stop !is SnapshotDownloadStop.Downloaded) {
            return PreparedBootstrapResult(
                run.observedCacheEpoch(),
                false,
                SnapshotStartResult(start.push, current),
                null,
                null,
            )
        }
        val baseline = snapshotDriver.complete(run, current)
        ensureFenceCurrent(fence)
        val delivery = syncDriver.deliver(run, maxPushMutations, maxPullPages, pullPageLimit)
        ensureFenceCurrent(fence)
        return PreparedBootstrapResult(
            run.observedCacheEpoch(),
            false,
            SnapshotStartResult(start.push, current),
            baseline,
            delivery,
        )
    }

    private fun openFence(): Any {
        val fence = Any()
        synchronized(stateLock) {
            currentLease?.invalidate()
            currentLease = null
            registeredPermit = null
            currentFence = fence
        }
        return fence
    }

    private fun isCurrentFence(fence: Any): Boolean =
        synchronized(stateLock) { fence === currentFence }

    private suspend fun ensureFenceCurrent(fence: Any) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentFence(fence)) {
            currentCoroutineContext().ensureActive()
            throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
        }
    }

    private fun registerLease(fence: Any, lease: SessionRefreshLease) {
        synchronized(stateLock) {
            if (fence !== currentFence) {
                lease.invalidate()
                throw PreparedEngineException(PreparedEngineFailure.STALE_OPERATION)
            }
            currentLease?.invalidate()
            currentLease = lease
        }
    }

    private fun mapPreparationFailure(failure: SessionPreparationFailure): PreparedEngineFailure =
        when (failure) {
            SessionPreparationFailure.IDENTITY_MISMATCH -> PreparedEngineFailure.IDENTITY_MISMATCH
            SessionPreparationFailure.AUTHORITY_UNAVAILABLE -> PreparedEngineFailure.AUTHORITY_UNAVAILABLE
            SessionPreparationFailure.VAULT_UNAVAILABLE -> PreparedEngineFailure.VAULT_UNAVAILABLE
            SessionPreparationFailure.STALE_AUTHORITY,
            SessionPreparationFailure.STALE_OPERATION,
            -> PreparedEngineFailure.STALE_OPERATION
            SessionPreparationFailure.INVALID_INPUT -> PreparedEngineFailure.INVALID_INPUT
            SessionPreparationFailure.NOT_PREPARING -> PreparedEngineFailure.NOT_PREPARED
            SessionPreparationFailure.VAULT_OCCUPIED,
            SessionPreparationFailure.COUNTER_OVERFLOW,
            SessionPreparationFailure.LOCAL_DATA_REQUIRES_DECISION,
            SessionPreparationFailure.ACCOUNT_MISMATCH,
            -> PreparedEngineFailure.AUTHORITY_UNAVAILABLE
        }

    private fun mapAuthorityFailure(failure: RoomSessionAuthorityFailure): PreparedEngineFailure =
        when (failure) {
            RoomSessionAuthorityFailure.STALE_AUTHORITY -> PreparedEngineFailure.STALE_OPERATION
            RoomSessionAuthorityFailure.ACCOUNT_MISMATCH -> PreparedEngineFailure.IDENTITY_MISMATCH
            RoomSessionAuthorityFailure.INVALID_INPUT -> PreparedEngineFailure.INVALID_INPUT
            RoomSessionAuthorityFailure.CORRUPT_AUTHORITY,
            RoomSessionAuthorityFailure.UNAVAILABLE,
            RoomSessionAuthorityFailure.COUNTER_OVERFLOW,
            RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION,
            -> PreparedEngineFailure.AUTHORITY_UNAVAILABLE
        }

    private fun mapRefreshFailure(failure: SessionRefreshFailure): PreparedEngineFailure =
        when (failure) {
            SessionRefreshFailure.STALE_SESSION -> PreparedEngineFailure.STALE_OPERATION
            SessionRefreshFailure.INVALID_INPUT -> PreparedEngineFailure.INVALID_INPUT
            SessionRefreshFailure.ACCESS_UNAVAILABLE -> PreparedEngineFailure.NOT_PREPARED
            SessionRefreshFailure.UNAVAILABLE -> PreparedEngineFailure.VAULT_UNAVAILABLE
        }
}
