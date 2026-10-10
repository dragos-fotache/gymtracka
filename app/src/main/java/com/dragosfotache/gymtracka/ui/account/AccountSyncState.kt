package com.dragosfotache.gymtracka.ui.account

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.session.AccountRecoveryConfirmation
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationResult
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationStop
import com.dragosfotache.gymtracka.data.sync.session.AppSessionException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.session.AppSessionStaleException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.ui.AccountGymAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/** Narrow foreground/edit/manual operation seam over the account runtime and facade. */
internal interface AccountSyncOperations {
    val state: StateFlow<AppSessionState>
    fun committedEdits(session: AppAccountSession): Flow<Long>
    suspend fun observeCacheEpoch(session: AppAccountSession): Long
    suspend fun reconcile(
        session: AppAccountSession,
        expectedCacheEpoch: Long,
        restartStageId: String? = null,
    ): AppReconciliationResult
    suspend fun prepareOrdinaryRecovery(session: AppAccountSession): AccountRecoveryConfirmation?
    suspend fun recoverOrdinaryRejection(
        session: AppAccountSession,
        confirmation: AccountRecoveryConfirmation,
    ): AppReconciliationResult
}

/**
 * R13d facade delegation for sync operations. Resolves every call against the synchronous
 * exact-live binding; a queued collector alone can never authorize another handle. No DAO access.
 */
internal class SessionAccountSyncOperations(
    private val accountState: AccountGymAppState,
) : AccountSyncOperations {
    override val state: StateFlow<AppSessionState>
        get() = accountState.sessionCoordinator.state

    /**
     * Cold event-driven exact-handle committed-edit observation. Tolerates authority going
     * ACTIVE before the feature runtime binds: the flow waits on the account-state binding
     * selection and delegates to the matching handle's shared committed edits only, without
     * polling, sleeps or an alternate scope. Delegation ends when the selection moves on;
     * handle loss and disposal fence the observation (stale while still facade-current
     * surfaces through the controller's turnover handling).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun committedEdits(session: AppAccountSession): Flow<Long> =
        accountState.observeBinding().flatMapLatest { binding ->
            if (binding == null || binding.handle !== session) {
                emptyFlow()
            } else {
                if (!session.isAlive()) {
                    throw AppSessionStaleException()
                }
                binding.app.edits.committedEdits
            }
        }

    override suspend fun observeCacheEpoch(session: AppAccountSession): Long =
        accountState.sessionCoordinator.observeCacheEpoch(session)

    override suspend fun reconcile(
        session: AppAccountSession,
        expectedCacheEpoch: Long,
        restartStageId: String?,
    ): AppReconciliationResult =
        accountState.sessionCoordinator.reconcile(
            session,
            expectedCacheEpoch,
            restartStageId = restartStageId,
        )

    override suspend fun prepareOrdinaryRecovery(
        session: AppAccountSession,
    ): AccountRecoveryConfirmation? =
        accountState.sessionCoordinator.prepareOrdinaryRecovery(session)

    override suspend fun recoverOrdinaryRejection(
        session: AppAccountSession,
        confirmation: AccountRecoveryConfirmation,
    ): AppReconciliationResult =
        accountState.sessionCoordinator.recoverOrdinaryRejection(session, confirmation)
}

/** Closed sync presentation errors. Fixed safe text only. */
internal enum class AccountSyncError {
    UNAVAILABLE,
    REAUTHENTICATION_REQUIRED,
    STALE_CONFIRMATION,
}

/** Foreground/edit/manual presentation snapshot. Safe advisory state only. */
internal data class AccountSyncUiState(
    val busy: Boolean = false,
    val stop: AppReconciliationStop? = null,
    val error: AccountSyncError? = null,
    val retainedStageId: String? = null,
    val recovery: AccountRecoveryConfirmation? = null,
)

/**
 * R13d foreground/edit/manual controller. Composition-owned and inert until [start] plus
 * explicit foreground lifecycle admission; construction alone performs no work and no network.
 *
 * Triggers: foreground entry with a current ACTIVE handle queues one bounded reconciliation;
 * first activation or a new handle while foreground queues one; returning foreground queues
 * one; debounced successfully-committed finite writes queue one; manual [syncNow] always
 * performs a fresh-epoch exact-handle reconciliation. Recomposition and same-handle
 * delivery/status emissions never queue. Results, stops and invalidations alone never retry.
 *
 * Serialization: one owned operation at a time with generation fencing; a real newer commit
 * during a run schedules exactly one subsequent fresh-epoch run. There is no in-run
 * spin around PROTECTED, retained stage/rejection, backoff, network or reauth stops:
 * follow-ups are driven only by genuine newer demand, and every follow-up respects durable
 * backoff through the existing engine. Handle replacement cancels old observations, queued
 * requests and confirmations; late results cannot replace new presentation.
 */
internal class AccountSyncState(
    private val operations: AccountSyncOperations,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 500L,
) {
    private val lock = Any()
    private var generation: Long = 0L
    private var occupied: Boolean = false
    private var currentJob: Job? = null
    private var retiringJob: Job? = null
    private var watcherJob: Job? = null
    private var editsJob: Job? = null
    private var watchedHandle: AppAccountSession? = null
    private var started: Boolean = false
    private var foreground: Boolean = false
    /**
     * R13d raw-vs-covered demand watermarks for the exact watched handle. [observedWatermark]
     * is the highest successfully-committed counter seen raw pre-debounce; [coveredWatermark]
     * is the highest counter known covered by a fresh admission/epoch read. Demand is
     * observed > covered. Both reset on handle replacement so a new handle never inherits
     * the prior handle's demand. Persisted across retirement so genuine demand coalesces
     * through it; results and stops alone never set them.
     */
    private var observedWatermark: Long = 0L
    private var coveredWatermark: Long = 0L
    /**
     * Handle the watermarks and entry demand were last reset for. Detachment (foreground
     * stop, observer restart) preserves coverage; only adopting a different exact handle
     * resets. A rebound handle always owns a fresh edit coordinator starting at zero.
     */
    private var watermarkHandle: AppAccountSession? = null

    /**
     * Counter-readiness gate for the current observer attachment. Completed with the first
     * raw committed-counter emission of the installed handle, so owned entry admission
     * knows the preexisting baseline before its fresh epoch/reconcile. Reused across
     * same-handle reattachments to preserve coverage; replaced on handle turnover while
     * generation fencing retires runs bound to the old gate.
     */
    private var readinessHandle: AppAccountSession? = null
    private var readinessDeferred: CompletableDeferred<Long>? = null

    /**
     * Independent explicit foreground/activation entry demand for the exact current handle.
     * Recorded even when the slot is owned or retiring, so a foreground return or activation
     * during retirement never loses its mandatory run to watermark-only chaining. Consumed by
     * the fresh admitted run alongside the covered counter; never set by results, stops or
     * invalidations. Cleared on handle replacement and disposal.
     */
    private var entryDemand: AppAccountSession? = null
    private var disposed: Boolean = false

    private val _uiState = MutableStateFlow(AccountSyncUiState())
    val uiState: StateFlow<AccountSyncUiState> = _uiState.asStateFlow()

    /** Idempotent start: observes authority and handle turnover. Queues nothing by itself. */
    fun start() {
        val already = synchronized(lock) {
            if (disposed || started) return
            started = true
            true
        }
        if (!already) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                operations.state.collect { snapshot ->
                    currentCoroutineContext().ensureActive()
                    onAuthority(snapshot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        synchronized(lock) {
            if (disposed) {
                job.cancel()
                return
            }
            watcherJob?.cancel()
            watcherJob = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (watcherJob === job) {
                    watcherJob = null
                }
            }
        }
        job.start()
    }

    /**
     * Explicit foreground lifecycle admission. Entry synchronously re-adopts the current
     * handle and restarts commit observation so a same-handle return queues without waiting
     * for a new authority emission. Leaving retires owned work into the held slot (never
     * overlapping a new invocation) while preserving queued demand, and stops observation.
     */
    fun setForeground(admitted: Boolean) {
        if (admitted) {
            val handle = synchronized(lock) {
                if (disposed || !started) return
                if (foreground) return
                foreground = true
                val live = operations.state.value
                val active = (live as? AppSessionState.Active)?.session
                if (active != null && isScopeAlive()) {
                    editsJob?.cancel()
                    editsJob = null
                    watchedHandle = active
                    if (watermarkHandle !== active) {
                        observedWatermark = 0L
                        coveredWatermark = 0L
                        entryDemand = null
                        watermarkHandle = active
                    }
                }
                active
            }
            if (handle != null) {
                observeEdits(handle)
            }
            requestAuto()
            return
        }
        synchronized(lock) {
            if (disposed || !started) return
            if (!foreground) return
            foreground = false
            generation += 1
            retireLocked()
            editsJob?.cancel()
            editsJob = null
            watchedHandle = null
            if (retiringJob == null) {
                _uiState.value = _uiState.value.copy(busy = false)
            }
        }
    }

    /**
     * Moves the live owned job to retirement, keeping the slot occupied until it drains.
     * New seam invocations stay fenced for the whole retirement without cancelling the
     * borrowed scope. Retirement ownership is registered before cancellation so a
     * synchronous/unconfined completion observes the retired identity instead of clearing
     * the live slot and stranding a completed job. Caller holds the lock; at most one job
     * retires at a time.
     */
    private fun retireLocked() {
        val live = currentJob ?: return
        currentJob = null
        retiringJob = live
        live.cancel()
    }

    /**
     * Acquires the single owned-operation slot with its generation fence. Returns null when
     * another live or retiring operation owns it; callers preserve demand instead of
     * overlapping retiring work. The generation is passed to [launchOwned].
     */
    private fun acquireSlot(): Long? =
        synchronized(lock) {
            if (disposed || !started) return null
            if (occupied || !isScopeAlive()) return null
            occupied = true
            generation += 1
            generation
        }

    /** Manual sync: same fresh-epoch exact-handle reconciliation, never bypassing stops. */
    fun syncNow() {
        val slot = acquireSlot() ?: return
        launchOwned(slot) { gen, handle ->
            runReconcileLoop(gen, handle)
        }
    }

    /** Explicit recovery preparation. No-op while another operation owns the slot. */
    fun prepareRecovery() {
        val slot = acquireSlot() ?: return
        launchOwned(slot) { gen, handle ->
            setBusyIfCurrent(gen, true)
            try {
                val confirmation = operations.prepareOrdinaryRecovery(handle)
                if (!checkCurrent(gen)) return@launchOwned
                if (confirmation == null) {
                    finishIfCurrent(gen, stop = null, retainedStageId = null, AccountSyncError.UNAVAILABLE, null)
                } else {
                    finishRecoveryPrepared(gen, confirmation)
                }
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            } catch (e: Exception) {
                if (!checkCurrentSilent(gen)) return@launchOwned
                finishIfCurrent(gen, stop = null, retainedStageId = null, mapError(e), null)
            }
        }
    }

    /**
     * Confirms a deliberate ordinary discard. Called only from captured deliberate UI
     * confirmation with the exact issued token; stale, foreign or replayed tokens refuse.
     */
    fun confirmRecovery(confirmation: AccountRecoveryConfirmation) {
        val slot = acquireSlot() ?: return
        launchOwned(slot) { gen, handle ->
            setBusyIfCurrent(gen, true)
            try {
                val result = operations.recoverOrdinaryRejection(handle, confirmation)
                if (!checkCurrent(gen)) return@launchOwned
                finishIfCurrent(gen, result.stop, result.retainedStageId, null, null)
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            } catch (e: Exception) {
                if (!checkCurrentSilent(gen)) return@launchOwned
                finishIfCurrent(gen, stop = null, retainedStageId = null, mapError(e), null)
            }
        }
    }

    /**
     * Deliberate retained-stage restart with the observed stage UUID. Called only from
     * captured deliberate UI confirmation warning that staged download restarts.
     */
    fun restartStage(stageId: String) {
        val slot = acquireSlot() ?: return
        launchOwned(slot) { gen, handle ->
            setBusyIfCurrent(gen, true)
            try {
                val epoch = operations.observeCacheEpoch(handle)
                if (!checkCurrent(gen)) return@launchOwned
                synchronized(lock) {
                    if (disposed || gen != generation) return@launchOwned
                    if (observedWatermark > coveredWatermark) {
                        coveredWatermark = observedWatermark
                    }
                }
                val result = operations.reconcile(handle, epoch, stageId)
                if (!checkCurrent(gen)) return@launchOwned
                finishIfCurrent(gen, result.stop, result.retainedStageId, null, null)
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            } catch (e: Exception) {
                if (!checkCurrentSilent(gen)) return@launchOwned
                finishIfCurrent(gen, stop = null, retainedStageId = null, mapError(e), null)
            }
        }
    }

    /** Cancels owned work into retirement, preserving queued demand. Durable state is untouched. */
    fun cancel() {
        synchronized(lock) {
            if (disposed) return
            if (currentJob?.isActive != true && retiringJob == null) return
            generation += 1
            retireLocked()
            if (retiringJob == null) {
                _uiState.value = _uiState.value.copy(busy = false)
            }
        }
    }

    /** Fences presentation and cancels owned work. Never cancels the borrowed scope. */
    fun dispose() {
        val current: Job?
        val retiring: Job?
        val watcher: Job?
        val edits: Job?
        synchronized(lock) {
            if (disposed) return
            disposed = true
            generation += 1
            current = currentJob
            retiring = retiringJob
            watcher = watcherJob
            edits = editsJob
            currentJob = null
            retiringJob = null
            watcherJob = null
            editsJob = null
            watchedHandle = null
            watermarkHandle = null
            entryDemand = null
            readinessHandle = null
            readinessDeferred = null
            occupied = false
            _uiState.value = AccountSyncUiState()
        }
        try {
            current?.cancel()
        } catch (_: Exception) {
        }
        try {
            retiring?.cancel()
        } catch (_: Exception) {
        }
        try {
            watcher?.cancel()
        } catch (_: Exception) {
        }
        try {
            edits?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun onAuthority(snapshot: AppSessionState) {
        val queue = synchronized(lock) {
            if (disposed || !started) return
            val handle = (snapshot as? AppSessionState.Active)?.session
            if (handle === watchedHandle) {
                // Same-handle emission is normally a no-op status update. The exception is an
                // adopted handle with no live edits observer (adopt/second-resolve race or
                // silent collector death): repair observation here without turnover, resets,
                // generation changes, demand changes, queued work or slot effects. Watched
                // identity alone never proves observation is running; a healthy observer is
                // never replaced by repeated identical emissions.
                val repair = handle != null &&
                    watchedHandle === handle &&
                    editsJob?.isActive != true &&
                    isScopeAlive()
                if (repair) {
                    observeEdits(handle)
                }
                return
            }
            generation += 1
            retireLocked()
            editsJob?.cancel()
            editsJob = null
            watchedHandle = handle
            if (watermarkHandle !== handle) {
                observedWatermark = 0L
                coveredWatermark = 0L
                entryDemand = null
                watermarkHandle = handle
            }
            _uiState.value = AccountSyncUiState()
            if (handle != null && foreground && isScopeAlive()) {
                true
            } else {
                if (handle != null && isScopeAlive()) {
                    observeEdits(handle)
                }
                false
            }
        }
        if (queue) {
            val target = synchronized(lock) { watchedHandle } ?: return
            observeEdits(target)
            requestAuto()
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeEdits(handle: AppAccountSession) {
        val flow = try {
            operations.committedEdits(handle)
        } catch (_: Exception) {
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Record the raw counter pre-debounce on every emission; the debounced
                // demand check compares watermarks, so delayed replay of covered values
                // stays silent while strictly newer commits always demand.
                flow
                    .transform { value ->
                        currentCoroutineContext().ensureActive()
                        // Record the raw counter pre-debounce, then complete the installed
                        // readiness gate for this exact handle with its first raw emission.
                        val target = synchronized(lock) {
                            if (disposed || watchedHandle !== handle) {
                                null
                            } else {
                                if (value > observedWatermark) observedWatermark = value
                                if (readinessHandle === handle) readinessDeferred else null
                            }
                        }
                        target?.complete(value)
                        emit(value)
                    }
                    .debounce(debounceMillis)
                    .collect {
                        currentCoroutineContext().ensureActive()
                        onEditSignal(handle)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        synchronized(lock) {
            if (disposed || watchedHandle !== handle) {
                job.cancel()
                return
            }
            // Install a fresh readiness gate only for a new handle; same-handle
            // reattachment reuses the settled gate and preserves its coverage.
            if (readinessHandle !== handle) {
                readinessHandle = handle
                readinessDeferred = CompletableDeferred()
            }
            editsJob?.cancel()
            editsJob = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (editsJob === job) {
                    editsJob = null
                }
            }
        }
        job.start()
    }

    private fun onEditSignal(handle: AppAccountSession) {
        // Debounced newer counter value: admit only demand strictly newer than the watermark
        // captured at the latest fresh admission. Values already covered stay silent even
        // when their debounced replay arrives after the covering run.
        val launch = synchronized(lock) {
            if (disposed || !started) return
            if (watchedHandle !== handle) return
            if (!foreground) return
            if (observedWatermark <= coveredWatermark) return
            if (occupied || !isScopeAlive()) return
            occupied = true
            generation += 1
            generation
        }
        launchOwned(launch) { gen, resolved ->
            runReconcileLoop(gen, resolved)
        }
    }

    /**
     * Explicit automatic demand entry (foreground entry, activation, handle turnover).
     * Attempts one fresh-epoch run; when the slot is owned or preconditions fail, the
     * watermarks preserve the demand for the run loop, retirement callback or next entry.
     * Never queues on results, stops or invalidations.
     */
    private fun requestAuto() {
        val launch = synchronized(lock) {
            if (disposed || !started || !foreground) return
            val live = operations.state.value
            if (live !is AppSessionState.Active) return
            if (watchedHandle !== live.session) return
            // Independent entry demand survives an owned/retiring slot; the admitted run
            // consumes it, and retirement/loop chains honor it without newer edits.
            entryDemand = live.session
            if (occupied || !isScopeAlive()) return
            occupied = true
            generation += 1
            generation
        }
        launchOwned(launch) { gen, handle ->
            runReconcileLoop(gen, handle)
        }
    }

    private fun launchOwned(gen: Long, block: suspend (Long, AppAccountSession) -> Unit) {
        val adopted = synchronized(lock) {
            if (disposed || gen != generation) {
                releaseIfCurrent(gen)
                return
            }
            val live = operations.state.value
            val active = (live as? AppSessionState.Active)?.session
            if (active == null) {
                // Nonqueueing admission: a same-generation reservation that finds no Active
                // work releases its owned slot instead of blocking later activation.
                releaseIfCurrent(gen)
                return
            }
            if (watchedHandle !== active) {
                // Adopt the exact live handle for this action: the slot was just acquired so
                // no owned work runs; old edits observation restarts below. The launched run
                // itself uses a fresh epoch, so commits predating adoption stay covered.
                editsJob?.cancel()
                editsJob = null
                watchedHandle = active
                if (watermarkHandle !== active) {
                    observedWatermark = 0L
                    coveredWatermark = 0L
                    entryDemand = null
                    watermarkHandle = active
                }
                true
            } else {
                false
            }
        }
        // Resolve under the same check so a concurrent replacement fences this attempt.
        // A same-generation reservation that finds no Active work releases its owned slot
        // here, so later activation is never blocked with no owner; a stale generation
        // never touches the newer owner's slot.
        val handle = synchronized(lock) {
            if (disposed || gen != generation) {
                releaseIfCurrent(gen)
                return
            }
            val live = operations.state.value
            val active = (live as? AppSessionState.Active)?.session
            if (active == null) {
                releaseIfCurrent(gen)
                return
            }
            if (watchedHandle !== active) {
                releaseIfCurrent(gen)
                return
            }
            active
        }
        if (adopted) {
            observeEdits(handle)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(gen, handle)
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            }
        }
        synchronized(lock) {
            if (disposed || gen != generation) {
                job.cancel()
                return
            }
            currentJob?.cancel()
            currentJob = job
        }
        job.invokeOnCompletion {
            // Unified slot release and demand chaining for live and retiring completions.
            // A new seam invocation can never overlap draining retirement: the slot stays
            // occupied until this callback runs. Preserved demand chains into one follow-up.
            val next = synchronized(lock) {
                val mine = currentJob === job || retiringJob === job
                if (currentJob === job) currentJob = null
                if (retiringJob === job) retiringJob = null
                if (!mine) return@synchronized null
                if (currentJob == null && retiringJob == null) occupied = false
                if (disposed || !started || !foreground || !isScopeAlive()) {
                    return@synchronized null
                }
                val live = operations.state.value
                val active = (live as? AppSessionState.Active)?.session
                if (active == null || active !== watchedHandle) return@synchronized null
                // Chain on explicit entry demand for the current handle or watermark-newer
                // edits only; never on results, stops or invalidations alone.
                if (entryDemand !== active && observedWatermark <= coveredWatermark) {
                    return@synchronized null
                }
                occupied = true
                generation += 1
                generation
            }
            if (next != null) {
                launchOwned(next) { g, h ->
                    runReconcileLoop(g, h)
                }
            } else {
                // Retired completions carry a stale generation by definition; clear busy once
                // nothing owns the slot, without touching newer stop/error/recovery fields.
                synchronized(lock) {
                    if (!disposed && currentJob == null &&
                        retiringJob == null && _uiState.value.busy
                    ) {
                        _uiState.value = _uiState.value.copy(busy = false)
                    }
                }
            }
        }
        if (!job.start()) {
            releaseIfCurrent(gen)
        }
    }

    private suspend fun runReconcileLoop(gen: Long, handle: AppAccountSession) {
        setBusyIfCurrent(gen, true)
        var resolution = handle
        while (true) {
            // Consume explicit entry demand at admission, before any wait: cancelling during
            // counter readiness must not requeue the canceled entry itself, while later
            // foreground/new-edit demand recorded meanwhile survives independently.
            synchronized(lock) {
                if (disposed || gen != generation) return
                if (entryDemand === resolution) entryDemand = null
            }
            // Synchronize admission with the first exact matching counter observation for
            // the current attachment, so the preexisting baseline is known before the fresh
            // epoch/reconcile. No Room, HTTP or state lock is held while awaiting; the wait
            // is cancellation-safe and handle-fenced, with no yield/delay/sleep assumptions.
            val ready = synchronized(lock) {
                if (disposed || gen != generation) return
                if (readinessHandle === resolution) readinessDeferred else null
            }
            if (ready != null) {
                val baseline = try {
                    ready.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                }
                currentCoroutineContext().ensureActive()
                if (!checkCurrent(gen)) return
                val live = operations.state.value
                if (live !is AppSessionState.Active || live.session !== resolution) return
                synchronized(lock) {
                    if (disposed || gen != generation) return
                    if (baseline > coveredWatermark) coveredWatermark = baseline
                }
            }
            // Fresh admission coverage from the synchronized baseline: raw counters known
            // up to this epoch read are covered by this run; only strictly newer commits
            // demand a successor.
            synchronized(lock) {
                if (disposed || gen != generation) return
                if (observedWatermark > coveredWatermark) {
                    coveredWatermark = observedWatermark
                }
            }
            try {
                val epoch = operations.observeCacheEpoch(resolution)
                if (!checkCurrent(gen)) return
                val result = operations.reconcile(resolution, epoch)
                if (!checkCurrent(gen)) return
                finishIfCurrent(gen, result.stop, result.retainedStageId, null, null)
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            } catch (e: Exception) {
                if (!checkCurrentSilent(gen)) return
                finishIfCurrent(gen, stop = null, retainedStageId = null, mapError(e), null)
                return
            }
            // Strictly newer demand only: results and stops alone never requeue. An explicit
            // entry demand for the current handle chains even with no newer edit; otherwise
            // only watermark-newer commits do. Follow-ups always use a fresh epoch and respect
            // durable backoff through the engine.
            val follow = synchronized(lock) {
                if (disposed || gen != generation) return
                val live = operations.state.value
                val next = (live as? AppSessionState.Active)?.session
                if (next == null || next !== watchedHandle) return
                if (!foreground) return
                if (entryDemand !== next && observedWatermark <= coveredWatermark) return
                resolution = next
                setBusyIfCurrent(gen, true)
                true
            }
            if (!follow) return
        }
    }

    private fun mapError(e: Exception): AccountSyncError {
        if (e is AppSessionStaleException) {
            return AccountSyncError.STALE_CONFIRMATION
        }
        if (e is SyncApiException.ReauthenticationRequired) {
            return AccountSyncError.REAUTHENTICATION_REQUIRED
        }
        if (e is AppSessionException && e.reason == AppSessionReason.IDENTITY_MISMATCH) {
            return AccountSyncError.STALE_CONFIRMATION
        }
        return AccountSyncError.UNAVAILABLE
    }

    private fun isScopeAlive(): Boolean {
        val job = scope.coroutineContext[Job] ?: return true
        return job.isActive
    }

    private fun isCurrent(gen: Long): Boolean =
        synchronized(lock) { !disposed && gen == generation }

    private suspend fun checkCurrent(gen: Long): Boolean {
        currentCoroutineContext().ensureActive()
        return isCurrent(gen)
    }

    private fun checkCurrentSilent(gen: Long): Boolean = isCurrent(gen)

    private fun setBusyIfCurrent(gen: Long, busy: Boolean) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            _uiState.value = _uiState.value.copy(busy = busy, error = null)
        }
    }

    private fun finishIfCurrent(
        gen: Long,
        stop: AppReconciliationStop?,
        retainedStageId: String?,
        error: AccountSyncError?,
        recovery: AccountRecoveryConfirmation?,
    ) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            _uiState.value = _uiState.value.copy(
                busy = false,
                stop = stop,
                error = error,
                retainedStageId = retainedStageId,
                recovery = recovery,
            )
        }
    }

    private fun finishRecoveryPrepared(gen: Long, confirmation: AccountRecoveryConfirmation) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            _uiState.value = _uiState.value.copy(busy = false, error = null, recovery = confirmation)
        }
    }

    private fun settleCancelled(gen: Long) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            _uiState.value = _uiState.value.copy(busy = false)
        }
    }

    private fun releaseIfCurrent(gen: Long) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            occupied = false
        }
    }
}
