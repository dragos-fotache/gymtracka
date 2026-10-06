package com.dragosfotache.gymtracka.data.sync.session

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.MAX_SNAPSHOT_DELIVERY_PAGES
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

/**
 * Inert explicitly constructed private prepared-session engine coordinator.
 * Freshly admits one matching private PREPARING Room / PREPARED vault pair
 * through the R09b reader, then reuses R08 complete snapshot establishment
 * and the R07 admitted shared push-before-pull delivery with valid-tail
 * bookkeeping under uninterrupted physical-DB ownership.
 *
 * Stops at a private canonical baseline and bounded actual tail: never
 * convergence, projection publication, vault promotion, ready/ACTIVE
 * writing, repository/UI rebinding or production construction. Canonical,
 * projection, repository and ordinary push stores remain ACTIVE-only; the
 * explicit private permit is threaded only through prepared PushStore /
 * PushRun writers. Prepared runs reject [PushRun.publishProjection] before
 * any barrier or visible write.
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

    override fun toString(): String = "PreparedSessionEngineCoordinator(redacted)"

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
     * Semantic vault record equality: binding, operation, phase and full
     * grant use value equality; restored pending attempts compare exact
     * operation, key, body text and token, never reference identity.
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
