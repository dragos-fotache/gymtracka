package com.dragosfotache.gymtracka.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Composition source for the app-wide [FiniteEditCoordinator]. [GymApp] provides the single
 * app-owned coordinator; standalone screens fall back to a local coordinator. Feature factories
 * must use [rememberFiniteEditCoordinator] so no fresh coordinator is constructed when the app
 * provides one.
 */
internal val LocalFiniteEditCoordinator = staticCompositionLocalOf<FiniteEditCoordinator?> { null }

/**
 * Returns the app-provided coordinator when present, otherwise a scope-bound standalone fallback.
 * The fallback is only constructed when no app coordinator is provided, and standalone defaults
 * are preserved. The provided branch is stable for the composition lifetime.
 */
@Composable
internal fun rememberFiniteEditCoordinator(): FiniteEditCoordinator {
    val appEdits = LocalFiniteEditCoordinator.current
    if (appEdits != null) return appEdits
    val scope = rememberCoroutineScope()
    return remember(scope) { FiniteEditCoordinator(scope) }
}

/** Atomic grant outcome for [FiniteEditCoordinator.grantOrTerminalPending]. */
internal enum class GrantOutcome {
    GRANTED,
    TERMINAL_PENDING,
    INVALID,
}

/**
 * R12a app-owned admission barrier for finite local edits and timer publication leases.
 *
 * Production constructs no engine or publisher here: this coordinator only serializes accepted
 * finite writes, retains their outcomes by stable mutation identity, and fences publication
 * behind an explicit pause/drain lease. It never reads or writes Room, never claims
 * visibility/activation, and never joins endless timers, tickers, or observers.
 *
 * Timer ownership fields in owners (e.g. [GymAppState]) are confined to the app-state owner
 * dispatcher; every claim path (UI starts, skip/replace/expiry, exclusive finish, publication
 * adapter) runs there, which keeps claim check-and-set atomic. Lease checks and close stay
 * thread-safe for Room threads.
 *
 * Admission contract:
 * - Normal acceptance ([launchEdit]) is synchronous. The predecessor chain and tracking job are
 *   registered (lazily, before execution) BEFORE [onAccepted] runs, so reentrant accepted
 *   callbacks cannot reverse FIFO order. Finite work is therefore registered before this function
 *   returns and before [onAccepted] changes optimistic state. Callers must capture input snapshots
 *   (selections, editor inputs, source IDs, start/finish choices) at acceptance and must keep
 *   successful navigation callbacks inside the tracked block. If [onAccepted] throws, admission
 *   fails safely: the job is cancelled and the never-started backstop settles the signal.
 * - Paused, disposed, or scope-cancelled admission returns null and invokes neither callback nor
 *   repository work. Standalone scope cancellation is observed through thread-safe parent-Job
 *   active checks: no valid leases are returned and no optimistic edits are accepted afterwards.
 *   Refused edits must not alter domain-like drafts, selection-success, or navigation state.
 * - Pure presentation typing is not persistence acceptance; only its explicit save/commit is gated.
 * - Loads, tickers, and observers are never tracked.
 *
 * Outcome contract:
 * - Outcomes are tracked, not just `Job.join()`. A failed predecessor never erases queued newer
 *   work; newer writes still run in acceptance order. Failures AND cancellations are retained by
 *   stable mutation identity, keyed by the failed edit's ACCEPTANCE sequence (never completion
 *   time): only a newer successful explicit retry/superseding write to the SAME identity settles
 *   it, later-origin failures are preserved, and an older completion never clears a newer failure.
 *   Terminal failures use unique timer-instance keys and are never auto-retried or cleared.
 * - Signal completion and retained-outcome recording are atomic: pending-empty can never be
 *   observed before the corresponding result or failure is recorded. Cancellation is retained,
 *   including jobs that never started; dispose records cancellation evidence for every pending
 *   edit. Drains throw the newest retained origin (or cancellation); there is no raw error UI or
 *   logging here. Drains after dispose cannot report success.
 * - Callers that need failure UI must catch inside the tracked block, apply only safe UI cleanup,
 *   and rethrow the origin. Swallowing converts a failure into false success. Read-only drains
 *   may preserve prior catch behavior; mutation failures must never be swallowed.
 *
 * Pause/lease contract:
 * - [pauseAndDrain] pauses atomically BEFORE suspension, excludes new normal acceptance, repeats
 *   draining registered finite work (normal, terminal, and in-flight exclusive transitions) plus
 *   retained outcomes, and returns a live GRANTED lease: terminal admission during its lifetime
 *   invalidates it before the write. Reentrant or concurrent pauses fail closed instead of
 *   deadlocking. Failure or cancellation before the lease is returned always releases the pause.
 * - [pauseAndDrainHeld] is the internal held variant for the timer adapter, which must perform
 *   intentional pre-grant terminal claims without invalidating its future lease. The adapter
 *   grants through [grantOrTerminalPending], which atomically rechecks pending terminal work and
 *   failures together with marking the lease granted, so a terminal registered just before grant
 *   cannot slip past the last drain.
 * - Lease close is idempotent, nonblocking, nonthrowing, and owner-specific: an old close never
 *   reopens a newer pause. Closed, invalidated, disposed, or scope-cancelled leases throw
 *   [ProjectionLeaseInvalidException] on check. The pause stays held after invalidation until
 *   close, so there is no admission gap before a transaction's final check.
 *
 * Transition contract:
 * - [launchExclusiveEdit] reserves the pause synchronously and registers a finite signal for the
 *   transition itself, so external drains can no longer miss a running transition. Its pre-drain
 *   and any registered feature-lambda recheck running inside the transition exclude only the
 *   current exclusive signal (via coroutine-context marking), never other work: there is no
 *   self-join, while external drains still observe it. Terminal writes run on their own serial
 *   claim-order queue in the coordinator scope and never depend on the exclusive job awaiting
 *   them. A pre-drain failure on another key settles the signal without recording under the
 *   transition key (no repository mutation ran); correcting the original same-key edit makes a
 *   later transition eligible again. Eligibility is fail-closed: another unresolved outcome
 *   rejects the transition synchronously, while a same-key retry may run despite its own previous
 *   retained outcome. The owner-specific pause releases on every settlement path, including
 *   cancellation before coroutine start (never-started backstop) — the pause can never strand.
 *   New concurrent actions are rejected while a transition or publication pause is held.
 * - [launchTerminalEdit] is only for an already claimed timer's final persistence, never a bypass
 *   for ordinary feature edits.
 * - [dispose] cancels only owned finite work, records cancellation evidence, invalidates the
 *   pause/leases, refuses all later admission, and never cancels the parent scope.
 */
internal class FiniteEditCoordinator(private val scope: CoroutineScope) {
    private val lock = Any()
    private val scopeJob = scope.coroutineContext[Job]

    /** Compose-observable pause mirror. Admission and lease validity use the synchronized flags. */
    var isPaused: Boolean by mutableStateOf(false)
        private set

    private var disposed = false
    private var paused = false
    private var pauseGeneration = 0L
    private var sequence = 0L
    private var liveLease: CoordinatorLease? = null
    private var exclusiveOwner: Any? = null

    private data class Tracked(
        val key: String,
        val signal: CompletableDeferred<Unit>,
        val terminal: Boolean,
        val acceptSeq: Long,
    )

    private data class Retained(
        val key: String,
        val error: Exception,
        val acceptSeq: Long,
        val terminal: Boolean,
    )

    private val pending = mutableListOf<Tracked>()
    private val retained = mutableListOf<Retained>()
    // Single shared actual-write tail across normal writes and terminal claims, so acceptance
    // chronology holds across queues: an already claimed terminal rest can never commit after a
    // newer accepted uncheck/autosave. Exclusive transitions stay out of this chain (they drain
    // instead and must never wait on themselves).
    private var lastWrite: Job? = null
    private val owned = mutableSetOf<Job>()
    private val disposedCause = CancellationException("FiniteEditCoordinator is disposed")

    /**
     * Accepts one finite normal write. Returns the tracking job, or null when paused, disposed, or
     * scope-cancelled (neither [onAccepted] nor [block] runs). Accepted writes run in the
     * coordinator's app-lifetime scope in acceptance order via a single FIFO predecessor chain.
     */
    fun launchEdit(key: String, onAccepted: () -> Unit = {}, block: suspend () -> Unit): Job? {
        val predecessor: Job?
        val tracked: Tracked
        val job: Job
        synchronized(lock) {
            if (disposed || paused || scopeJob?.isActive == false) return null
            predecessor = lastWrite
            tracked = Tracked(key, CompletableDeferred(), terminal = false, acceptSeq = ++sequence)
            pending += tracked
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    try {
                        predecessor?.join()
                    } catch (cancelled: CancellationException) {
                        // A cancelled predecessor never blocks a live successor; scope
                        // cancellation still aborts below via ensureActive.
                        ensureActive()
                    }
                    block()
                    settleSuccess(tracked)
                } catch (cancelled: CancellationException) {
                    settleCancelled(tracked, cancelled)
                    throw cancelled
                } catch (failed: Exception) {
                    settleFailure(tracked, failed)
                }
            }
            lastWrite = job
            owned += job
            backstopNeverStarted(job, tracked)
            try {
                onAccepted()
            } catch (error: CancellationException) {
                // Settle the original callback failure atomically before cancelling the lazy
                // job, so the never-started backstop cannot replace it with generic
                // cancellation and no drain can pass in between.
                settleFailure(tracked, error)
                job.cancel(error)
                throw error
            } catch (error: Exception) {
                settleFailure(tracked, error)
                job.cancel(CancellationException("Edit acceptance failed", error))
                throw error
            }
            job.start()
            return job
        }
    }

    /**
     * Suspends until every accepted edit has committed. Throws the newest retained origin,
     * propagates cancellation (including a cancelled/disposed scope or a job that never started),
     * and repeats while new finite work registers. Unrelated success never clears a failure. When
     * called from inside an exclusive transition (context marker), only that transition's own
     * signal and retained key are excluded; external callers observe everything.
     */
    suspend fun awaitPendingEdits() {
        val marker = currentCoroutineContext()[ExclusiveMarker.Key]
        if (marker != null && marker.owner === this) {
            drainInternal(marker.signal, marker.mutationKey)
        } else {
            drainInternal(null, null)
        }
    }

    /**
     * Atomically pauses before suspension, drains registered finite work (normal, terminal, and
     * in-flight exclusive transitions) and retained outcomes, and returns a live GRANTED lease.
     * Throws (releasing the pause) when already paused, disposed, scope-cancelled, or when any
     * drained outcome fails.
     */
    suspend fun pauseAndDrain(): ProjectionPublicationLease {
        val lease = reservePause()
        try {
            grantAfterDrain(lease)
        } catch (cancelled: CancellationException) {
            releaseUnreturnedPause()
            throw cancelled
        } catch (failed: Exception) {
            releaseUnreturnedPause()
            throw failed
        }
        return lease
    }

    /**
     * Internal held variant for the timer adapter: pauses and drains like [pauseAndDrain] but the
     * returned lease is ungranted, so intentional pre-grant terminal claims do not invalidate the
     * future lease. The adapter must grant through [grantOrTerminalPending].
     */
    internal suspend fun pauseAndDrainHeld(): ProjectionPublicationLease {
        val lease = reservePause()
        try {
            drainInternal(null, null)
        } catch (cancelled: CancellationException) {
            releaseUnreturnedPause()
            throw cancelled
        } catch (failed: Exception) {
            releaseUnreturnedPause()
            throw failed
        }
        return lease
    }

    /**
     * Drains and atomically grants a held lease. A terminal admitted during the drain is drained
     * (not invalidating, since the lease is not yet granted) instead of killing the about-to-be
     * returned lease; only post-return admission invalidates.
     */
    private suspend fun grantAfterDrain(lease: ProjectionPublicationLease) {
        while (true) {
            drainInternal(null, null)
            when (grantOrTerminalPending(lease)) {
                GrantOutcome.GRANTED -> return
                GrantOutcome.TERMINAL_PENDING -> continue
                GrantOutcome.INVALID -> throw IllegalStateException(
                    "Publication lease is no longer grantable",
                )
            }
        }
    }

    /**
     * Reserves the pause synchronously for one destructive app-shell transition and registers a
     * finite signal for the transition itself. Returns null when disposed, already paused,
     * scope-cancelled, or while another key's outcome remains unresolved. A same-key retry may run
     * despite its own previous retained outcome.
     */
    fun launchExclusiveEdit(key: String, block: suspend () -> Unit): Job? {
        val tracked: Tracked
        val owner = Any()
        synchronized(lock) {
            if (disposed || paused || scopeJob?.isActive == false) return null
            if (retained.any { it.key != key }) return null
            tracked = Tracked(key, CompletableDeferred(), terminal = false, acceptSeq = ++sequence)
            pending += tracked
            exclusiveOwner = owner
            paused = true
            isPaused = true
            pauseGeneration++
        }
        val job = scope.launch(ExclusiveMarker(tracked, key, this)) {
            try {
                awaitPendingEdits()
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // The transition's own write failed: retain under its key for same-key
                    // retry. The job ends here without rethrowing; later drains surface the
                    // retained origin. Callers apply safe failure UI before rethrowing here.
                    settleFailure(tracked, failed)
                    return@launch
                }
                settleSuccess(tracked)
            } catch (cancelled: CancellationException) {
                settleCancelled(tracked, cancelled)
                throw cancelled
            } catch (predrainFailed: Exception) {
                // Pre-drain failed on another key: no repository mutation ran under this
                // transition, so the signal settles without recording under its key.
                synchronized(lock) {
                    pending -= tracked
                }
                tracked.signal.completeExceptionally(predrainFailed)
            } finally {
                releaseExclusiveOwner(owner)
            }
        }
        synchronized(lock) {
            owned += job
        }
        job.invokeOnCompletion {
            releaseExclusiveOwner(owner)
        }
        backstopNeverStarted(job, tracked)
        return job
    }

    /**
     * Registers an already claimed timer's final persistence even while paused. Serializes terminal
     * claims in claim order on their own queue and synchronously invalidates a granted publication
     * lease before launching the write. Terminal failures keep unique timer-instance keys and are
     * never auto-retried or cleared. Returns null only when disposed or scope-cancelled.
     */
    fun launchTerminalEdit(key: String, block: suspend () -> Unit): Job? {
        val predecessor: Job?
        val tracked: Tracked
        synchronized(lock) {
            if (disposed || scopeJob?.isActive == false) return null
            if (liveLease?.granted == true) liveLease?.valid = false
            predecessor = lastWrite
            tracked = Tracked(key, CompletableDeferred(), terminal = true, acceptSeq = ++sequence)
            pending += tracked
            // Lazy like normal launch: with an immediate/unconfined owner dispatcher, an eager
            // block could reentrantly accept a newer normal write before this tail is installed.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    try {
                        predecessor?.join()
                    } catch (cancelled: CancellationException) {
                        ensureActive()
                    }
                    block()
                    settleSuccess(tracked)
                } catch (cancelled: CancellationException) {
                    settleCancelled(tracked, cancelled)
                    throw cancelled
                } catch (failed: Exception) {
                    settleFailure(tracked, failed)
                }
            }
            lastWrite = job
            owned += job
            backstopNeverStarted(job, tracked)
            job.start()
            return job
        }
    }

    /** Drains terminal writes and surfaces the newest retained terminal origin. */
    internal suspend fun awaitTerminalWrites() {
        currentCoroutineContext().ensureActive()
        while (true) {
            val snapshot: List<Tracked> = synchronized(lock) {
                pending.filter { it.terminal }
            }
            if (snapshot.isEmpty()) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (disposed) throw CancellationException("FiniteEditCoordinator is disposed")
                    if (pending.any { it.terminal }) continue
                    scope.ensureActive()
                    retained.filter { it.terminal }.maxByOrNull { it.acceptSeq }?.let {
                        throw it.error
                    }
                    return
                }
            }
            for (tracked in snapshot) {
                try {
                    tracked.signal.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    throw failed
                }
            }
        }
    }

    /**
     * Atomically grants a held lease only when it is still the live valid one with no pending or
     * retained terminal work; otherwise reports [GrantOutcome.TERMINAL_PENDING] (drain and retry)
     * or [GrantOutcome.INVALID] (release and fail closed).
     */
    internal fun grantOrTerminalPending(lease: ProjectionPublicationLease): GrantOutcome =
        synchronized(lock) {
            val live = liveLease
            if (live !== lease || !live.valid || disposed) return GrantOutcome.INVALID
            if (pending.any { it.terminal } || retained.any { it.terminal }) {
                return GrantOutcome.TERMINAL_PENDING
            }
            live.granted = true
            GrantOutcome.GRANTED
        }

    /** Reports whether a granted live lease is currently active. */
    internal fun grantedLeaseActive(): Boolean = synchronized(lock) {
        val live = liveLease
        live != null && live.granted && live.valid && !disposed && scopeJob?.isActive != false
    }

    /** Invalidates the live lease when granted; a late untracked timer must never race publication. */
    internal fun invalidateLiveLease() {
        synchronized(lock) {
            if (liveLease?.granted == true) liveLease?.valid = false
        }
    }

    /** Thread-safe admission check for owners that must refuse work after teardown. */
    internal fun isDisposed(): Boolean = synchronized(lock) { disposed }

    /**
     * Thread-safe unavailability check: refuses once disposed OR the parent scope is already
     * cancelling, even before app.dispose completion runs.
     */
    internal fun isAdmissionClosed(): Boolean =
        synchronized(lock) { disposed || scopeJob?.isActive == false }

    /**
     * Cancels only owned finite work, records cancellation evidence for every pending edit,
     * invalidates the pause/leases, refuses all later admission, and never cancels the parent
     * scope.
     */
    fun dispose() {
        val toCancel: List<Job>
        synchronized(lock) {
            if (disposed) return
            disposed = true
            paused = false
            isPaused = false
            liveLease?.valid = false
            liveLease = null
            exclusiveOwner = null
            for (tracked in pending) {
                if (retained.none { it.key == tracked.key && it.acceptSeq == tracked.acceptSeq }) {
                    retained += Retained(tracked.key, disposedCause, tracked.acceptSeq, tracked.terminal)
                }
                tracked.signal.cancel(disposedCause)
            }
            pending.clear()
            toCancel = owned.toList()
        }
        toCancel.forEach { it.cancel() }
    }

    private fun reservePause(): CoordinatorLease {
        synchronized(lock) {
            if (disposed) throw IllegalStateException("FiniteEditCoordinator is disposed")
            if (paused) throw IllegalStateException("FiniteEditCoordinator is already paused")
            // Refuse pause admission on standalone scope cancellation (thread-safe parent-Job
            // check); caller cancellation is observed separately via ensureActive in drains.
            if (scopeJob?.isActive == false) {
                throw CancellationException("FiniteEditCoordinator scope is cancelled")
            }
            scope.ensureActive()
            paused = true
            isPaused = true
            val created = CoordinatorLease(generation = ++pauseGeneration, granted = false)
            liveLease = created
            return created
        }
    }

    private suspend fun drainInternal(excludeSignal: Tracked?, excludeRetainedKey: String?) {
        // Caller cancellation fails the drain even with empty pending, so a cancelled caller can
        // never acquire a live lease.
        currentCoroutineContext().ensureActive()
        while (true) {
            val snapshot: List<Tracked> = synchronized(lock) {
                pending.filter { it !== excludeSignal }
            }
            if (snapshot.isEmpty()) {
                // Re-check caller cancellation before reporting success: work may have settled
                // while a cancellation was already pending.
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (disposed) throw CancellationException("FiniteEditCoordinator is disposed")
                    if (pending.any { it !== excludeSignal }) continue
                    // A cancelled scope fails the drain instead of silently succeeding with no
                    // tracked edits left.
                    scope.ensureActive()
                    retained.filter { it.key != excludeRetainedKey }.maxByOrNull { it.acceptSeq }?.let {
                        throw it.error
                    }
                    return
                }
            }
            for (tracked in snapshot) {
                try {
                    tracked.signal.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    throw failed
                }
            }
        }
    }

    private fun settleSuccess(tracked: Tracked) {
        synchronized(lock) {
            pending -= tracked
            // Only outcomes recorded for acceptances at or before this one are settled; an older
            // completion never clears a newer (later-origin) failure under the same identity.
            // Terminal outcomes are immutable and never cleared here.
            retained.removeAll {
                it.key == tracked.key && !it.terminal && it.acceptSeq <= tracked.acceptSeq
            }
            tracked.signal.complete(Unit)
        }
    }

    private fun settleFailure(tracked: Tracked, failed: Exception) {
        synchronized(lock) {
            pending -= tracked
            retained += Retained(tracked.key, failed, tracked.acceptSeq, tracked.terminal)
        }
        tracked.signal.completeExceptionally(failed)
    }

    private fun settleCancelled(tracked: Tracked, cause: CancellationException) {
        synchronized(lock) {
            pending -= tracked
            if (retained.none { it.key == tracked.key && it.acceptSeq == tracked.acceptSeq }) {
                retained += Retained(tracked.key, cause, tracked.acceptSeq, tracked.terminal)
            }
        }
        tracked.signal.cancel(cause)
    }

    private fun releaseUnreturnedPause() {
        synchronized(lock) {
            paused = false
            liveLease = null
            isPaused = false
        }
    }

    private fun releaseExclusiveOwner(owner: Any) {
        synchronized(lock) {
            if (exclusiveOwner === owner) {
                exclusiveOwner = null
                paused = false
                isPaused = false
            }
        }
    }

    /** Settles the drain signal for a job that never started so drains cannot hang on it. */
    private fun backstopNeverStarted(job: Job, tracked: Tracked) {
        job.invokeOnCompletion { error ->
            synchronized(lock) {
                owned -= job
            }
            if (!tracked.signal.isCompleted) {
                // Retain the original cancellation (including never-started) as evidence.
                val cause = error as? CancellationException
                    ?: CancellationException("Edit did not run", error)
                settleCancelled(tracked, cause)
            }
        }
    }

    private inner class CoordinatorLease(
        val generation: Long,
        var granted: Boolean,
    ) : ProjectionPublicationLease {
        var valid = true
        private var closed = false

        override fun checkValid() {
            synchronized(lock) {
                if (!valid || closed || disposed || scopeJob?.isActive == false) {
                    throw ProjectionLeaseInvalidException()
                }
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                valid = false
                // Owner-specific release: an old close never reopens a newer pause. The pause
                // stays held after invalidation until close, leaving no admission gap before a
                // transaction's final check.
                if (liveLease === this && pauseGeneration == generation) {
                    liveLease = null
                    paused = false
                    isPaused = false
                }
            }
        }
    }

    private data class ExclusiveMarker(
        val signal: Tracked,
        val mutationKey: String,
        val owner: FiniteEditCoordinator,
    ) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<ExclusiveMarker>

        override val key: CoroutineContext.Key<*>
            get() = Key
    }
}
