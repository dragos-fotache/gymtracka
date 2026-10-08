package com.dragosfotache.gymtracka.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppSessionCoordinator
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.session.AppSessionStaleException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransitionBarrier
import com.dragosfotache.gymtracka.data.sync.session.SessionVault
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R12c scoped account runtime. Explicitly constructed; starts private and performs
 * no HTTP/sync until the caller invokes delivered authority/reconciliation APIs
 * on [sessionCoordinator]. Owns real transition/publication barriers over the
 * currently attached app-state and watches the stable authority state.
 *
 * Only the exact live Active handle gets a bound repository/app-state/child scope;
 * delivery-status updates on the same handle never reconstruct navigation or timer.
 * Loss of visibility hides models immediately while pinning retiring state; final
 * retirement runs on the owner dispatcher after every live lease closes. Emergency
 * invalidation cancels stale presentation/hardware without claiming persistence.
 */
internal class AccountGymAppState(
    private val database: GymDatabase,
    private val origin: ServiceOrigin?,
    private val vault: SessionVault,
    private val authApi: ServiceAuthApi,
    private val transport: ServiceHttpTransport,
    private val runOwner: PushRunOwner,
    private val scope: CoroutineScope,
    private val clock: GymAppClock,
    private val timerHardware: RestTimerHardware,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
) {
    val sessionCoordinator: AppSessionCoordinator

    private var boundAppState: GymAppState? by mutableStateOf(null)

    /**
     * Public visibility selection: returns the bound app state only when the
     * synchronous exact-live authority still admits it. A queued collector alone
     * can never expose a revoked handle through this getter.
     */
    val appState: GymAppState?
        get() = visibleBinding()?.app

    private val stateLock = Any()
    private var boundHandle: AppAccountSession? = null
    private var boundScope: CoroutineScope? = null
    private val retiring = mutableListOf<RetiredEntry>()
    private val liveTransitionLeases = mutableSetOf<ProjectionPublicationLease>()
    private val livePublicationLeases = mutableSetOf<ProjectionPublicationLease>()
    private val fallbackEdits = FiniteEditCoordinator(scope)
    private var observerJob: Job? = null
    private var disposed = false

    private data class RetiredEntry(
        val state: GymAppState,
        val scope: CoroutineScope,
    )

    init {
        val transitionBarrier = AppSessionTransitionBarrier { previous ->
            acquireTransition(previous)
        }
        val publicationBarrier = ProjectionPublicationBarrier { request ->
            acquirePublication(request)
        }
        sessionCoordinator = AppSessionCoordinator(
            database,
            origin,
            vault,
            authApi,
            transport,
            runOwner,
            transitionBarrier,
            publicationBarrier,
            { clock.wallTimeMillis() },
            uuidProvider,
        )
        observerJob = scope.launch {
            try {
                sessionCoordinator.state.collect { handleState(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            try {
                dispose()
            } catch (_: Exception) {
            }
        }
    }

    internal fun currentHandle(): AppAccountSession? =
        visibleBinding()?.handle

    internal data class VisibleBinding(
        val app: GymAppState,
        val handle: AppAccountSession,
    )

    /**
     * Synchronous exact-live authority/liveness selection used at every render
     * and binding boundary. Rechecks the coordinator's synchronous state value
     * and handle liveness inside one short lock; never trusts a collected snapshot.
     */
    internal fun visibleBinding(): VisibleBinding? =
        synchronized(stateLock) {
            val app = boundAppState
            val handle = boundHandle
            if (app == null || handle == null) return null
            val live = sessionCoordinator.state.value
            if (live !is AppSessionState.Active || live.session !== handle) return null
            if (!handle.isAlive()) return null
            VisibleBinding(app, handle)
        }

    fun dispose() {
        // Fence the owned facade first: synchronously revoke handles/leases so an
        // externally retained handle/repository cannot remain usable against
        // unchanged durable generation when a new runtime recovers. Durable
        // evidence is unchanged. Retiring app-state disposal below is separate
        // normal retirement, not whole-runtime disposal.
        try {
            sessionCoordinator.invalidate()
        } catch (_: Exception) {
        }
        val toDispose: List<RetiredEntry>
        val current: GymAppState?
        val currentScope: CoroutineScope?
        synchronized(stateLock) {
            if (disposed) return
            disposed = true
            current = boundAppState
            currentScope = boundScope
            boundAppState = null
            boundHandle = null
            boundScope = null
            toDispose = retiring.toList() +
                (if (current != null && currentScope != null) listOf(RetiredEntry(current, currentScope)) else emptyList())
            retiring.clear()
        }
        observerJob?.cancel()
        observerJob = null
        toDispose.forEach {
            try {
                it.state.dispose()
            } catch (_: Exception) {
            }
            it.scope.coroutineContext[Job]?.cancel()
        }
        current?.let {
            if (toDispose.none { entry -> entry.state === it }) {
                try {
                    it.dispose()
                } catch (_: Exception) {
                }
            }
        }
        currentScope?.coroutineContext?.get(Job)?.let { job ->
            if (toDispose.none { entry -> entry.scope === currentScope }) {
                job.cancel()
            }
        }
        try {
            fallbackEdits.dispose()
        } catch (_: Exception) {
        }
    }

    private suspend fun handleState(state: AppSessionState) {
        when (state) {
            is AppSessionState.Active -> {
                val handle = state.session
                try {
                    handle.checkCurrent()
                } catch (_: Exception) {
                    return
                }
                synchronized(stateLock) {
                    if (disposed) return
                    if (boundHandle === handle) return
                }
                val live = sessionCoordinator.state.value
                if (live !is AppSessionState.Active || live.session !== handle) {
                    return
                }
                bindNewHandle(handle)
            }
            else -> hideForPrivacy()
        }
    }

    private suspend fun bindNewHandle(handle: AppAccountSession) {
        val childScope = CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]),
        )
        val binding = handle.identity.binding
        val session = RepositorySession(binding.accountId, binding.sessionGeneration)
        val edits = FiniteEditCoordinator(childScope)
        val next = GymAppState(handle.repository, childScope, clock, timerHardware, edits, session)
        next.onTimerBindingFault = { fault ->
            // Exact-handle rule: only the captured handle currently bound, active,
            // current and alive may be retired by its own fault. An older
            // same-account/same-durable-generation callback must never retire a newer
            // recovered handle, so account/generation tuple match alone is insufficient.
            val bound = synchronized(stateLock) { boundHandle }
            if (bound != null && bound === handle && bound.isAlive() &&
                fault.session.accountId == bound.identity.binding.accountId &&
                fault.session.sessionGeneration == bound.identity.binding.sessionGeneration
            ) {
                val live = sessionCoordinator.state.value
                if (live is AppSessionState.Active && live.session === handle) {
                    try {
                        sessionCoordinator.invalidate()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        synchronized(stateLock) {
            if (disposed) {
                try {
                    next.dispose()
                } catch (_: Exception) {
                }
                childScope.coroutineContext[Job]?.cancel()
                return
            }
            val live = sessionCoordinator.state.value
            if (live !is AppSessionState.Active || live.session !== handle) {
                try {
                    next.dispose()
                } catch (_: Exception) {
                }
                childScope.coroutineContext[Job]?.cancel()
                return
            }
            val old = boundAppState
            val oldScope = boundScope
            if (old != null && oldScope != null) {
                retiring.add(RetiredEntry(old, oldScope))
            }
            boundHandle = handle
            boundScope = childScope
            boundAppState = next
        }
        disposeRetiredWithoutLiveLeases()
    }

    private suspend fun hideForPrivacy() {
        val old: GymAppState?
        val oldScope: CoroutineScope?
        synchronized(stateLock) {
            if (disposed) return
            old = boundAppState
            oldScope = boundScope
            if (old == null) return
            if (oldScope != null) {
                retiring.add(RetiredEntry(old, oldScope))
            }
            boundHandle = null
            boundScope = null
            boundAppState = null
        }
        if (hasLiveLeases()) {
            return
        }
        disposeRetiredWithoutLiveLeases()
    }

    private fun hasLiveLeases(): Boolean =
        synchronized(stateLock) {
            liveTransitionLeases.isNotEmpty() || livePublicationLeases.isNotEmpty()
        }

    private suspend fun disposeRetiredWithoutLiveLeases() {
        val pending: List<RetiredEntry> = synchronized(stateLock) {
            if (hasLiveLeasesLocked()) return
            val copy = retiring.toList()
            retiring.clear()
            copy
        }
        for (entry in pending) {
            try {
                entry.state.dispose()
            } catch (_: Exception) {
            }
            entry.scope.coroutineContext[Job]?.cancel()
        }
    }

    private fun hasLiveLeasesLocked(): Boolean =
        liveTransitionLeases.isNotEmpty() || livePublicationLeases.isNotEmpty()

    /**
     * Normal transition acquisition over the currently attached app state, or real
     * owned fallback admission for an initial private runtime only. Refuses while an
     * explicit-save create/edit custom editor is open so its unsaved draft is never
     * hidden or discarded by a scope change; emergency revocation hides instantly
     * through [hideForPrivacy] without acquiring here. Editor/scope checks run on
     * the owner dispatcher with a post-drain recheck before grant use, and the
     * acquired/tracked lease is registered before result dispatch and closed exactly
     * once on every lost-return, cancellation or error path.
     */
    private suspend fun acquireTransition(previous: RepositorySession?): ProjectionPublicationLease {
        val captured: GymAppState? = synchronized(stateLock) { boundAppState }
        var tracked: ProjectionPublicationLease? = null
        try {
            withContext(scope.coroutineContext.minusKey(Job)) {
                scope.ensureActive()
                currentCoroutineContext().ensureActive()
                val current = synchronized(stateLock) { boundAppState }
                if (current == null) {
                    // No ordinary scope: only an initial private runtime (null previous
                    // scope) may use dormant fallback admission, after cancellation
                    // checks. A nonnull previous scope never falls back.
                    if (previous != null) {
                        throw IllegalStateException(
                            "Transition has no app state for previous scope; failing closed",
                        )
                    }
                    tracked = trackTransition(fallbackEdits.pauseAndDrain())
                    return@withContext
                }
                if (current !== captured) {
                    throw IllegalStateException(
                        "Transition scope changed during dispatch; failing closed",
                    )
                }
                if (current.destination == Destination.CreateExercise ||
                    current.destination == Destination.EditCustomExercise
                ) {
                    throw IllegalStateException(
                        "Explicit-save custom editor is open; refusing transition acquisition",
                    )
                }
                val acquired = current.transitionBarrier().acquire(previous)
                // Recheck captured scope/presentation after drain, before grant use:
                // an editor opened during dispatch/drain must refuse, never hide.
                val settled = synchronized(stateLock) { boundAppState }
                if (settled !== current) {
                    acquired.close()
                    throw IllegalStateException(
                        "Transition scope changed during drain; failing closed",
                    )
                }
                if (current.destination == Destination.CreateExercise ||
                    current.destination == Destination.EditCustomExercise
                ) {
                    acquired.close()
                    throw IllegalStateException(
                        "Explicit-save custom editor opened during drain; refusing transition",
                    )
                }
                tracked = trackTransition(acquired)
            }
        } catch (error: CancellationException) {
            tracked?.close()
            throw error
        } catch (error: Exception) {
            tracked?.close()
            throw error
        }
        return tracked
            ?: throw IllegalStateException("Transition acquisition lost its lease; failing closed")
    }

    private fun trackTransition(lease: ProjectionPublicationLease): ProjectionPublicationLease {
        synchronized(stateLock) {
            liveTransitionLeases.add(lease)
        }
        return TrackingLease(lease) {
            val shouldRetire: Boolean = synchronized(stateLock) {
                liveTransitionLeases.remove(lease)
                liveTransitionLeases.isEmpty() && livePublicationLeases.isEmpty()
            }
            if (shouldRetire) {
                try {
                    scope.launch { disposeRetiredWithoutLiveLeases() }
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun acquirePublication(request: ProjectionPublicationRequest): ProjectionPublicationLease {
        val current: GymAppState?
        val handle: AppAccountSession?
        synchronized(stateLock) {
            current = boundAppState
            handle = boundHandle
        }
        val authority = sessionCoordinator.state.value
        val activeCase = authority is AppSessionState.Active &&
            handle != null &&
            current != null &&
            authority.session === handle &&
            request.accountId == handle.identity.binding.accountId &&
            request.sessionGeneration == handle.identity.binding.sessionGeneration
        val preparedCase = !activeCase &&
            authority is AppSessionState.Private &&
            authority.reason == AppSessionReason.PREPARATION_REQUIRED &&
            current == null
        if (!activeCase && !preparedCase) {
            throw IllegalStateException("Publication has no matching active app state; failing closed")
        }
        var tracked: ProjectionPublicationLease? = null
        try {
            withContext(scope.coroutineContext.minusKey(Job)) {
                val acquired = if (activeCase) {
                    val activeHandle = handle
                        ?: throw IllegalStateException("Publication lost its handle; failing closed")
                    val activeState = current
                        ?: throw IllegalStateException("Publication lost its app state; failing closed")
                    val readerBarrier = ProjectionPublicationBarrier {
                        throw IllegalStateException("Timer impact reader never acquires")
                    }
                    val readerProcessCheck: () -> Unit = {
                        if (!activeHandle.isAlive()) {
                            throw AppSessionStaleException()
                        }
                        val live = sessionCoordinator.state.value
                        if (live !is AppSessionState.Active || live.session !== activeHandle) {
                            throw AppSessionStaleException()
                        }
                    }
                    val impact: suspend (ActiveRestTimer) -> Boolean = { timer ->
                        val workoutId = timer.workoutId
                            ?: throw IllegalStateException("Timer impact requires a workout binding; failing closed")
                        val workoutExerciseId = timer.workoutExerciseId
                            ?: throw IllegalStateException("Timer impact requires an entry binding; failing closed")
                        val reader = ProjectionStore(
                            database,
                            RepositorySession(request.accountId, request.sessionGeneration),
                            readerBarrier,
                            readerProcessCheck,
                        )
                        reader.preservesTimer(
                            request.expectedCacheEpoch,
                            workoutId,
                            workoutExerciseId,
                            timer.workoutSetId,
                        )
                    }
                    activeState.publicationBarrier(request, impact).acquire(request)
                } else {
                    fallbackEdits.pauseAndDrain()
                }
                tracked = trackPublication(acquired)
            }
        } catch (error: CancellationException) {
            tracked?.close()
            throw error
        } catch (error: Exception) {
            tracked?.close()
            throw error
        }
        return tracked
            ?: throw IllegalStateException("Publication acquisition lost its lease; failing closed")
    }

    private fun trackPublication(lease: ProjectionPublicationLease): ProjectionPublicationLease {
        synchronized(stateLock) {
            livePublicationLeases.add(lease)
        }
        return TrackingLease(lease) {
            val shouldRetire: Boolean = synchronized(stateLock) {
                livePublicationLeases.remove(lease)
                liveTransitionLeases.isEmpty() && livePublicationLeases.isEmpty()
            }
            if (shouldRetire) {
                try {
                    scope.launch { disposeRetiredWithoutLiveLeases() }
                } catch (_: Exception) {
                }
            }
        }
    }

    private class TrackingLease(
        private val delegate: ProjectionPublicationLease,
        private val onClose: () -> Unit,
    ) : ProjectionPublicationLease {
        private val lock = Any()
        private var closed = false

        override fun checkValid() {
            synchronized(lock) {
                if (closed) {
                    throw ProjectionLeaseInvalidException()
                }
            }
            delegate.checkValid()
        }

        override fun close() {
            val runCallback: Boolean = synchronized(lock) {
                if (closed) return
                closed = true
                true
            }
            try {
                delegate.close()
            } finally {
                if (runCallback) {
                    try {
                        onClose()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}

/**
 * R12c scoped account root. Renders ordinary app content only for the exact
 * current live handle with its repository and shared edits; otherwise renders a
 * generic stable privacy placeholder. Never disposes the runtime-owned app state
 * on hide; the runtime owns final retirement.
 */
@Composable
internal fun GymApp(accountState: AccountGymAppState) {
    // Recompose signal and negative gate only; positive selection stays solely with
    // visibleBinding's synchronous exact-live authority/liveness recheck.
    val authoritySnapshot = accountState.sessionCoordinator.state.collectAsState().value
    val binding = accountState.visibleBinding()
    val selected = if (authoritySnapshot is AppSessionState.Active &&
        binding != null &&
        authoritySnapshot.session === binding.handle
    ) {
        binding
    } else {
        null
    }
    if (selected != null) {
        key(selected.handle) {
            CompositionLocalProvider(
                LocalGymRepositoryOverride provides selected.handle.repository,
                LocalFiniteEditCoordinator provides selected.app.edits,
            ) {
                GymAppContent(selected.app)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().testTag("account-privacy-gate")) {
            Text("Unavailable")
        }
    }
}

/**
 * Production account-runtime factory using the actual Android clock/hardware and
 * the composition scope. Construction starts private and triggers no sync.
 */
@Composable
internal fun rememberAccountGymAppState(
    database: GymDatabase,
    origin: ServiceOrigin?,
    vault: SessionVault,
    authApi: ServiceAuthApi,
    transport: ServiceHttpTransport,
    runOwner: PushRunOwner,
): AccountGymAppState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val clock = remember { SystemGymAppClock }
    val hardware = remember(context) { androidRestTimerHardware(context) }
    val state = remember(database, origin, vault, authApi, transport, runOwner, scope) {
        AccountGymAppState(
            database = database,
            origin = origin,
            vault = vault,
            authApi = authApi,
            transport = transport,
            runOwner = runOwner,
            scope = scope,
            clock = clock,
            timerHardware = hardware,
        )
    }
    DisposableEffect(state) {
        onDispose(state::dispose)
    }
    return state
}
