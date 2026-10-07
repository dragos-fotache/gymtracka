package com.dragosfotache.gymtracka.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dragosfotache.gymtracka.data.gym.GymAppStateRepository
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

internal enum class Destination {
    Exercises,
    ExerciseDetail,
    CreateExercise,
    EditCustomExercise,
    Workout,
    History,
    Routine,
    RoutineDetail,
    AddRoutineExercises,
    ActiveWorkout,
    AddExercises,
    ReplaceExercise,
    WorkoutHistoryDetail,
}

internal data class FinishedWorkoutSummary(
    val name: String,
    val durationMillis: Long,
    val volumeKg: String,
)

internal data class ActiveRestTimer(
    val workoutSetId: String,
    val durationSeconds: Int,
    val startedAtElapsedMillis: Long,
    val timerId: Long,
    val workoutId: String? = null,
    val workoutExerciseId: String? = null,
    val session: RepositorySession = RepositorySession.SIGNED_OUT,
)

internal interface GymAppClock {
    fun wallTimeMillis(): Long
    fun elapsedRealtimeMillis(): Long
}

private object SystemGymAppClock : GymAppClock {
    override fun wallTimeMillis() = System.currentTimeMillis()
    override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
}

internal interface RestTimerHardware {
    fun vibrate(durationMillis: Long)
    fun acquireWakeLock(timeoutMillis: Long)
    fun releaseWakeLock()
}

internal class GymAppState(
    private val repository: GymAppStateRepository,
    private val scope: CoroutineScope,
    private val clock: GymAppClock = SystemGymAppClock,
    private val timerHardware: RestTimerHardware = NoopRestTimerHardware,
    val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
    private val session: RepositorySession = RepositorySession.SIGNED_OUT,
) {
    var destination by mutableStateOf(Destination.Workout)
        private set
    var activeWorkout by mutableStateOf<WorkoutEntity?>(null)
        private set
    var selectedHistoryWorkoutId by mutableStateOf<String?>(null)
        private set
    var replacingWorkoutExerciseId by mutableStateOf<String?>(null)
        private set
    var editingRoutineId by mutableStateOf<String?>(null)
        private set
    var selectedRoutineId by mutableStateOf<String?>(null)
        private set
    var selectedExerciseId by mutableStateOf<String?>(null)
        private set
    var editingCustomExerciseId by mutableStateOf<String?>(null)
        private set
    var exerciseListFirstVisibleItemIndex by mutableIntStateOf(0)
        private set
    var exerciseListFirstVisibleItemScrollOffset by mutableIntStateOf(0)
        private set
    var exerciseSearchQuery by mutableStateOf("")
        private set
    var exerciseSelectedEquipmentIds by mutableStateOf(emptySet<String>())
        private set
    var exerciseSelectedMuscleGroupIds by mutableStateOf(emptySet<String>())
        private set
    var showDiscardDialog by mutableStateOf(false)
        private set
    var showFinishDialog by mutableStateOf(false)
        private set
    var showNoCompletedSetsDialog by mutableStateOf(false)
        private set
    var showStartNewWorkoutDialog by mutableStateOf(false)
        private set
    private var workoutToPerformAgainId: String? = null
    private var routineToStartId: String? = null
    var finishedWorkoutSummary by mutableStateOf<FinishedWorkoutSummary?>(null)
        private set
    var activeRestTimer by mutableStateOf<ActiveRestTimer?>(null)
        private set
    var restTimerRemainingSeconds by mutableIntStateOf(0)
        private set
    var restTimerCompletionKey by mutableIntStateOf(0)
        private set

    private var timerJob: Job? = null
    private var nextTimerId = 0L
    private var wakeLockTimerId: Long? = null
    private val endingTimerIdsPendingPersistence = mutableSetOf<Long>()
    private var finishDrainWorkoutId: String? = null
    private var finishDrainOwner: Any? = null
    private var finishDrain: (suspend () -> Unit)? = null

    constructor(context: Context, repository: GymAppStateRepository, scope: CoroutineScope) : this(
        repository = repository,
        scope = scope,
        timerHardware = AndroidRestTimerHardware(context.applicationContext),
    )

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { dispose() }
        scope.launch {
            try {
                activeWorkout = repository.activeWorkout()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the app shell usable if local discovery fails temporarily.
            }
        }
    }

    fun showExercises() {
        destination = Destination.Exercises
    }

    fun showExerciseDetails(exerciseId: String) {
        selectedExerciseId = exerciseId
        destination = Destination.ExerciseDetail
    }

    fun closeExerciseDetails() {
        selectedExerciseId = null
        destination = Destination.Exercises
    }

    fun showCreateExercise() {
        destination = Destination.CreateExercise
    }

    fun closeCreateExercise() {
        destination = Destination.Exercises
    }

    fun editCustomExercise(exerciseId: String) {
        editingCustomExerciseId = exerciseId
        destination = Destination.EditCustomExercise
    }

    fun closeEditCustomExercise() {
        editingCustomExerciseId = null
        destination = Destination.Exercises
    }

    fun deleteCustomExercise(exerciseId: String): Job? =
        edits.launchEdit("exercise-delete:$exerciseId") {
            repository.deleteCustomExercise(exerciseId)
            closeExerciseDetails()
        }

    fun updateExerciseListPosition(index: Int, scrollOffset: Int) {
        exerciseListFirstVisibleItemIndex = index
        exerciseListFirstVisibleItemScrollOffset = scrollOffset
    }

    fun updateExerciseCatalogueState(
        searchQuery: String,
        equipmentIds: Set<String>,
        muscleGroupIds: Set<String>,
    ) {
        exerciseSearchQuery = searchQuery
        exerciseSelectedEquipmentIds = equipmentIds
        exerciseSelectedMuscleGroupIds = muscleGroupIds
    }

    fun showWorkouts() {
        destination = Destination.Workout
    }

    fun showHistory() {
        destination = Destination.History
    }

    fun requestNewRoutine(): Job? =
        edits.launchEdit("routine:create-draft") {
            editingRoutineId = repository.createRoutineDraft().id
            destination = Destination.Routine
        }

    fun editRoutine(routineId: String) {
        editingRoutineId = routineId
        destination = Destination.Routine
    }

    fun showRoutineDetails(routineId: String) {
        selectedRoutineId = routineId
        destination = Destination.RoutineDetail
    }

    fun closeRoutineDetails() {
        selectedRoutineId = null
        destination = Destination.Workout
    }

    fun showRoutineExercisePicker() {
        destination = Destination.AddRoutineExercises
    }

    fun closeRoutineExercisePicker() {
        destination = Destination.Routine
    }

    fun cancelRoutine(isDraft: Boolean): Job? {
        val routineId = editingRoutineId ?: return null
        if (!isDraft) {
            editingRoutineId = null
            destination = Destination.Workout
            return null
        }
        return edits.launchEdit("routine-delete:$routineId") {
            repository.deleteRoutine(routineId)
            editingRoutineId = null
            destination = Destination.Workout
        }
    }

    fun finishRoutineEditing() {
        editingRoutineId = null
        destination = Destination.Workout
    }

    fun resumeWorkout() {
        destination = Destination.ActiveWorkout
    }

    fun showExercisePicker() {
        destination = Destination.AddExercises
    }

    fun showExerciseReplacementPicker(workoutExerciseId: String) {
        replacingWorkoutExerciseId = workoutExerciseId
        destination = Destination.ReplaceExercise
    }

    fun closeExerciseReplacementPicker() {
        replacingWorkoutExerciseId = null
        destination = Destination.ActiveWorkout
    }

    fun showWorkoutHistory(workoutId: String) {
        selectedHistoryWorkoutId = workoutId
        destination = Destination.WorkoutHistoryDetail
    }

    fun closeWorkoutHistory() {
        selectedHistoryWorkoutId = null
        destination = Destination.History
    }

    fun requestStartWorkout() {
        if (activeWorkout == null) {
            startWorkout()
        } else {
            showStartNewWorkoutDialog = true
        }
    }

    fun requestPerformAgain(workoutId: String) {
        if (activeWorkout == null) {
            performAgain(workoutId)
        } else {
            workoutToPerformAgainId = workoutId
            showStartNewWorkoutDialog = true
        }
    }

    fun requestStartRoutine(routineId: String) {
        if (activeWorkout == null) {
            startRoutine(routineId)
        } else {
            routineToStartId = routineId
            showStartNewWorkoutDialog = true
        }
    }

    fun dismissStartNewWorkoutDialog() {
        showStartNewWorkoutDialog = false
        workoutToPerformAgainId = null
        routineToStartId = null
    }

    fun confirmStartNewWorkout(): Job? {
        val current = activeWorkout ?: return null
        // Acceptance snapshots: source choices and start time are captured here, never re-read
        // later from mutable state.
        val startedAtMillis = clock.wallTimeMillis()
        val performAgainId = workoutToPerformAgainId
        val fromRoutineId = routineToStartId
        // Active replacement runs exclusively so accepted autosaves and terminal rest writes drain
        // before the destructive Room call. The running timer is cancelled only after repository
        // success; failure keeps the timer, dialog, and active workout intact.
        return edits.launchExclusiveEdit("transition:replace-active:${current.id}") {
            // Repository starts atomically replace the previous active aggregate,
            // so there is no separate discard and no compensating delete here.
            // Only an invalid source falls back to a fresh empty workout.
            val nextWorkout: WorkoutEntity = when {
                performAgainId != null -> {
                    repository.performWorkoutAgain(
                        performAgainId,
                        startedAtMillis,
                    ) ?: repository.startEmptyWorkout(startedAtMillis)
                }
                fromRoutineId != null -> {
                    repository.startWorkoutFromRoutine(
                        fromRoutineId,
                        startedAtMillis,
                    ) ?: repository.startEmptyWorkout(startedAtMillis)
                }
                else -> repository.startEmptyWorkout(startedAtMillis)
            }
            cancelTimer()
            activeWorkout = nextWorkout
            destination = Destination.ActiveWorkout
            showStartNewWorkoutDialog = false
            workoutToPerformAgainId = null
            routineToStartId = null
        }
    }

    private fun startWorkout(): Job? =
        edits.launchExclusiveEdit("transition:start-active") {
            activeWorkout = repository.startEmptyWorkout(clock.wallTimeMillis())
            destination = Destination.ActiveWorkout
        }

    private fun performAgain(workoutId: String): Job? =
        edits.launchExclusiveEdit("transition:start-active") {
            activeWorkout = repository.performWorkoutAgain(workoutId, clock.wallTimeMillis())
            activeWorkout?.let { destination = Destination.ActiveWorkout }
        }

    private fun startRoutine(routineId: String): Job? =
        edits.launchExclusiveEdit("transition:start-active") {
            activeWorkout = repository.startWorkoutFromRoutine(routineId, clock.wallTimeMillis())
            activeWorkout?.let { destination = Destination.ActiveWorkout }
        }

    fun updateActiveWorkoutName(name: String) {
        // Optimistic app-shell name callbacks from new user acceptance must not mutate while the
        // coordinator refuses admission (paused, disposed, or parent scope already cancelling).
        if (edits.isPaused || edits.isAdmissionClosed()) return
        activeWorkout = activeWorkout?.copy(name = name)
    }

    fun requestDiscardWorkout() {
        showDiscardDialog = true
    }

    fun dismissDiscardDialog() {
        showDiscardDialog = false
    }

    fun confirmDiscardWorkout(): Job? {
        val workout = activeWorkout ?: return null
        // Exclusive discard drains accepted feature writes and terminal rest before the destructive
        // Room call. The running timer is cancelled only after repository success (explicit local
        // discard semantics); failure keeps that timer, the dialog, and the active workout intact.
        return edits.launchExclusiveEdit("transition:discard:${workout.id}") {
            repository.discardWorkout(workout.id)
            cancelTimer()
            activeWorkout = null
            destination = Destination.Workout
            showDiscardDialog = false
        }
    }

    fun requestFinishWorkout() {
        val workout = activeWorkout ?: return
        val workoutId = workout.id
        scope.launch {
            try {
                // Global cross-feature barrier first: collapsed routine/picker/history accepted
                // work must not be missed before the eligibility dialog, even when no active
                // feature drain is registered. The request stays read-only and swallows failure.
                edits.awaitPendingEdits()
                drainRegisteredFeatureEdits(workoutId)
                // Local stale-callback fence: a concurrently accepted exclusive finish may have
                // settled while this read-only request was draining; never reopen a dialog for
                // a root that is no longer active.
                if (activeWorkout?.id != workoutId) return@launch
                val hasCompleted = repository.hasCompletedSets(workoutId)
                // Re-fence after the suspending read for the same reason.
                if (activeWorkout?.id != workoutId) return@launch
                if (hasCompleted) {
                    showFinishDialog = true
                } else {
                    showNoCompletedSetsDialog = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
    }

    fun dismissFinishDialog() {
        showFinishDialog = false
    }

    fun dismissNoCompletedSetsDialog() {
        showNoCompletedSetsDialog = false
    }

    fun dismissFinishedWorkoutSummary() {
        finishedWorkoutSummary = null
    }

    fun confirmFinishWorkout(): Job? {
        val workout = activeWorkout ?: return null
        // Exclusive finish drains all accepted feature writes and terminal writes before the
        // destructive Room call, and claims the current timer before capture. A failed terminal
        // write blocks the finish; immutable history is never a retry target.
        return edits.launchExclusiveEdit("transition:finish:${workout.id}") {
            try {
                // A late accepted edit must not be overtaken: recheck under exclusive admission
                // after the initial request drain. The exclusive transition itself is not part of
                // the drained sets, so this recheck cannot self-join.
                drainRegisteredFeatureEdits(workout.id)
                claimCurrentTimerAndAwaitTerminalWrites()
                val finishedAtMillis = clock.wallTimeMillis()
                if (repository.finishWorkout(workout.id, finishedAtMillis)) {
                    val details = repository.workoutDetails(workout.id)
                    finishedWorkoutSummary = FinishedWorkoutSummary(
                        name = activeWorkout?.name ?: workout.name,
                        durationMillis = finishedAtMillis - workout.startedAtMillis,
                        volumeKg = details?.totalVolumeKg() ?: "0 kg",
                    )
                    activeWorkout = null
                    destination = Destination.Workout
                } else {
                    showNoCompletedSetsDialog = true
                }
                showFinishDialog = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Safe UI cleanup preserves the failure dialog state, then the origin is
                // rethrown so the coordinator retains it instead of reporting false success.
                showFinishDialog = true
                throw error
            }
        }
    }

    /**
     * Composition-owned registration of the active feature's suspend edit drain.
     * Only the drain registered for this workout by its current owner runs;
     * disposal detaches only when the owner still matches, so a newer screen
     * registration is never cleared by an older one going away.
     */
    fun registerFinishDrain(workoutId: String, owner: Any, drain: suspend () -> Unit) {
        finishDrainWorkoutId = workoutId
        finishDrainOwner = owner
        finishDrain = drain
    }

    fun unregisterFinishDrain(workoutId: String, owner: Any) {
        if (finishDrainWorkoutId == workoutId && finishDrainOwner === owner) {
            finishDrainWorkoutId = null
            finishDrainOwner = null
            finishDrain = null
        }
    }

    private suspend fun drainRegisteredFeatureEdits(workoutId: String) {
        if (finishDrainWorkoutId == workoutId) {
            finishDrain?.invoke()
        }
    }

    /**
     * Request-bound adapter to the existing publisher interface. The factory binds one exact valid
     * account/nonnegative-epoch/positive-generation request; acquire rejects any request mismatch.
     * The caller must already hold the engine/session authority and supplies the canonical-graph
     * impact decision ([preservesTimer]) for this fenced request; this adapter grants no such
     * authority and invents no canonical reader. It performs no Room work: outbox protection is
     * rechecked by the existing publisher after acquisition.
     *
     * Acquire pauses and drains accepted edits first, then evaluates impact for the resulting
     * current timer (an already accepted set-completion callback may have started its timer during
     * the pre-lease drain and is included). A timer whose session differs from the requested
     * account/generation fails closed, rechecked after the drain. Impact true retains
     * identity/countdown/hardware; impact false claims terminal rest while the old set exists
     * (a no-op when it is gone) and drains it exactly once before the lease is granted. Existing
     * terminal jobs and failures are drained even when no running timer remains. Origin failures
     * and impact-callback failure/cancellation release the pause and propagate; a failed rest
     * write blocks publication and stays observable to later drains. False never drops rest: the
     * newly captured write postpones projection via the publisher's outbox recheck.
     *
     * The acquire body runs on the app-state owner dispatcher without adopting its Job: the
     * caller (e.g. a future engine on another coroutine) keeps its own Job and cancellation, so
     * racing false-impact and expiry claims serialize on the same dispatcher that owns every
     * other timer claim and can never both observe an active timer. Lease checks and close stay
     * thread-safe for Room threads, with no UI access from Room.
     */
    fun publicationBarrier(
        expectedRequest: ProjectionPublicationRequest,
        preservesTimer: suspend (ActiveRestTimer) -> Boolean,
    ): ProjectionPublicationBarrier {
        require(expectedRequest.accountId.isNotBlank()) {
            "Publication barrier requires a nonblank account"
        }
        require(expectedRequest.expectedCacheEpoch >= 0) {
            "Publication barrier requires a nonnegative epoch"
        }
        require(expectedRequest.sessionGeneration > 0) {
            "Publication barrier requires a positive session generation"
        }
        return ProjectionPublicationBarrier { request ->
            if (request != expectedRequest) {
                throw IllegalArgumentException("Publication request mismatch")
            }
            // The acquired lease is held outside withContext: prompt cancellation during result
            // dispatch would otherwise bypass the inner handlers and leak a live lease. Outer
            // close is idempotent; origin and parent Job are preserved.
            var acquired: ProjectionPublicationLease? = null
            try {
                withContext<ProjectionPublicationLease>(scope.coroutineContext.minusKey(Job)) {
                    // Marshal onto the owner dispatcher without adopting its Job: caller
                    // cancellation is preserved and the caller Job is untouched, while timer
                    // session/impact/claim work serializes with every other timer claim.
                    scope.ensureActive()
                    // Initial timer ownership is read on the owner dispatcher too, so the
                    // session check observes the same serialized state as the later drain.
                    activeRestTimer?.let { checkTimerSession(it, request) }
                    val lease: ProjectionPublicationLease = try {
                        edits.pauseAndDrainHeld()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        throw error
                    }
                    acquired = lease
                    try {
                        val current = activeRestTimer
                        // Repeat the session check: an accepted completion may have started a
                        // timer during the drain.
                        current?.let { checkTimerSession(it, request) }
                        val keep = if (current == null) {
                            true
                        } else {
                            preservesTimer(current)
                        }
                        // Re-drain terminals that raced the impact evaluation; a failed terminal
                        // blocks the grant instead of slipping into publication.
                        edits.awaitTerminalWrites()
                        if (current != null && !keep) {
                            claimAndQueueTerminalWrite(
                                current,
                                clock.elapsedRealtimeMillis() - current.startedAtElapsedMillis,
                            )
                            edits.awaitTerminalWrites()
                        }
                        // Atomic grant: a terminal registered just before grant cannot slip past
                        // the last drain. Racers are drained and the grant retried; expiry sources
                        // are one-shot so this terminates instead of deadlocking.
                        grantHeldLease(lease)
                    } catch (error: CancellationException) {
                        lease.close()
                        throw error
                    } catch (error: Exception) {
                        lease.close()
                        throw error
                    }
                }
            } catch (error: CancellationException) {
                acquired?.close()
                throw error
            } catch (error: Exception) {
                acquired?.close()
                throw error
            }
        }
    }

    /**
     * Drains racing terminals and atomically grants a held lease. Repeats while new terminal
     * work appears; fails closed when the lease is no longer grantable.
     */
    private suspend fun grantHeldLease(lease: ProjectionPublicationLease): ProjectionPublicationLease {
        while (true) {
            edits.awaitTerminalWrites()
            when (edits.grantOrTerminalPending(lease)) {
                GrantOutcome.GRANTED -> return lease
                GrantOutcome.TERMINAL_PENDING -> continue
                GrantOutcome.INVALID -> {
                    lease.close()
                    throw IllegalStateException("Publication lease is no longer grantable")
                }
            }
        }
    }

    private fun checkTimerSession(timer: ActiveRestTimer, request: ProjectionPublicationRequest) {
        if (timer.session.accountId != request.accountId ||
            timer.session.sessionGeneration != request.sessionGeneration
        ) {
            throw IllegalStateException(
                "Active timer session differs from publication request; failing closed",
            )
        }
    }

    fun startRestTimer(
        workoutSet: WorkoutSetEntity,
        durationSeconds: Int,
        acceptedEdit: Boolean = false,
    ) {
        if (durationSeconds <= 0) return
        if (edits.isAdmissionClosed()) return
        if (edits.grantedLeaseActive()) {
            // A late untracked timer must never race publication: invalidate the granted lease
            // and refuse to start.
            edits.invalidateLiveLease()
            return
        }
        // Timer starts from new user acceptance must not mutate while paused; only an already
        // accepted set-completion callback may start its timer during the pre-lease drain so the
        // impact assessment can include it.
        if (edits.isPaused && !acceptedEdit) return
        activeRestTimer?.let { previousTimer ->
            val elapsedMillis = clock.elapsedRealtimeMillis() - previousTimer.startedAtElapsedMillis
            endingTimerIdsPendingPersistence += previousTimer.timerId
            claimAndQueueTerminalWrite(previousTimer, elapsedMillis)
        }
        val timer = ActiveRestTimer(
            workoutSetId = workoutSet.id,
            durationSeconds = durationSeconds,
            startedAtElapsedMillis = clock.elapsedRealtimeMillis(),
            timerId = ++nextTimerId,
            workoutId = activeWorkout?.id,
            workoutExerciseId = workoutSet.workoutExerciseId,
            session = session,
        )
        activeRestTimer = timer
        restTimerRemainingSeconds = durationSeconds
        runTimer(timer)
    }

    fun skipRestTimer(timer: ActiveRestTimer) {
        if (edits.isPaused) return
        if (activeRestTimer?.timerId != timer.timerId) return
        timerJob?.cancel()
        claimAndQueueTerminalWrite(
            timer,
            clock.elapsedRealtimeMillis() - timer.startedAtElapsedMillis,
        )
    }

    /**
     * Ends the running timer for an unchecked set without recording rest: the
     * uncheck already cleared recorded rest, so only matching presentation and
     * wake-lock ownership are released. Never touches a newer timer or the
     * pending terminal queue.
     */
    fun cancelRestTimerForSet(workoutSetId: String) {
        if (edits.isPaused) return
        val timer = activeRestTimer ?: return
        if (timer.workoutSetId != workoutSetId) return
        timerJob?.cancel()
        timerJob = null
        activeRestTimer = null
        restTimerRemainingSeconds = 0
        releaseRestTimerWakeLock(timer.timerId)
    }

    fun adjustRestTimer(timer: ActiveRestTimer, adjustmentSeconds: Int) {
        if (edits.isPaused) return
        if (activeRestTimer?.timerId != timer.timerId) return
        val adjustedTimer = timer.copy(
            durationSeconds = (timer.durationSeconds + adjustmentSeconds).coerceAtLeast(0),
        )
        activeRestTimer = adjustedTimer
        val elapsedMillis = clock.elapsedRealtimeMillis() - adjustedTimer.startedAtElapsedMillis
        restTimerRemainingSeconds = (
            (adjustedTimer.durationSeconds * 1_000L - elapsedMillis).coerceAtLeast(0L) + 999L
        ).div(1_000L).toInt()
        runTimer(adjustedTimer)
    }

    private fun runTimer(timer: ActiveRestTimer) {
        timerJob?.cancel()
        holdCpuAwakeFor(timer)
        timerJob = scope.launch {
            val signalledSeconds = mutableSetOf<Int>()
            while (isActive && activeRestTimer == timer) {
                val elapsedMillis = clock.elapsedRealtimeMillis() - timer.startedAtElapsedMillis
                val remainingMillis = (timer.durationSeconds * 1_000L - elapsedMillis).coerceAtLeast(0L)
                restTimerRemainingSeconds = ((remainingMillis + 999L) / 1_000L).toInt()
                when (restTimerRemainingSeconds) {
                    2, 1 -> if (signalledSeconds.add(restTimerRemainingSeconds)) {
                        timerHardware.vibrate(100L)
                    }

                    0 -> {
                        timerHardware.vibrate(500L)
                        if (timerJob === coroutineContext[Job]) timerJob = null
                        claimAndQueueTerminalWrite(timer, elapsedMillis)
                        return@launch
                    }
                }
                delay(100.milliseconds)
            }
        }
    }

    /**
     * Claims the current timer and awaits terminal success before capture. A failed terminal
     * write throws its origin and blocks the enclosing transition.
     */
    private suspend fun claimCurrentTimerAndAwaitTerminalWrites() {
        val timer = activeRestTimer
        if (timer != null) {
            timerJob?.cancel()
            claimAndQueueTerminalWrite(
                timer,
                clock.elapsedRealtimeMillis() - timer.startedAtElapsedMillis,
            )
        }
        edits.awaitTerminalWrites()
    }

    /**
     * Claims one timer's terminal transition exactly once, clears its presentation synchronously,
     * and persists only the DAO write through the coordinator's serial terminal queue: replacement
     * countdowns start immediately, repeated writes to the same set commit in claim order, and the
     * write survives countdown cancellation. The completion key increments only after a successful
     * commit; a failed write stays terminal, is never retried, and remains observable to every
     * later transition and publication drain under its unique timer-instance key. Claiming while a
     * publication lease is granted invalidates that lease before the write.
     */
    private fun claimAndQueueTerminalWrite(timer: ActiveRestTimer, elapsedMillis: Long) {
        val wasActive = activeRestTimer?.timerId == timer.timerId
        if (wasActive) {
            endingTimerIdsPendingPersistence.remove(timer.timerId)
            activeRestTimer = null
            restTimerRemainingSeconds = 0
        } else if (!endingTimerIdsPendingPersistence.remove(timer.timerId)) {
            return
        }
        if (wasActive) releaseRestTimerWakeLock()
        val elapsedSeconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L).toInt()
        edits.launchTerminalEdit("rest:${timer.timerId}") {
            try {
                repository.updateWorkoutSetRestSeconds(
                    workoutSetId = timer.workoutSetId,
                    elapsedSeconds = elapsedSeconds,
                )
                restTimerCompletionKey++
            } finally {
                if (activeRestTimer?.timerId == timer.timerId) {
                    activeRestTimer = null
                    restTimerRemainingSeconds = 0
                }
                if (wasActive) releaseRestTimerWakeLock(timer.timerId)
            }
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        activeRestTimer = null
        restTimerRemainingSeconds = 0
        endingTimerIdsPendingPersistence.clear()
        releaseRestTimerWakeLock()
    }

    /** Keeps the countdown coroutine and its final vibration cues running while the screen is off. */
    private fun holdCpuAwakeFor(timer: ActiveRestTimer) {
        releaseRestTimerWakeLock()
        val remainingMillis = (
            timer.durationSeconds * 1_000L -
                (clock.elapsedRealtimeMillis() - timer.startedAtElapsedMillis)
            ).coerceAtLeast(0L)
        if (remainingMillis == 0L) return

        timerHardware.acquireWakeLock(remainingMillis + WAKE_LOCK_TIMEOUT_BUFFER_MILLIS)
        wakeLockTimerId = timer.timerId
    }

    private fun releaseRestTimerWakeLock(ownerTimerId: Long? = null) {
        if (wakeLockTimerId != null && (ownerTimerId == null || wakeLockTimerId == ownerTimerId)) {
            timerHardware.releaseWakeLock()
            wakeLockTimerId = null
        }
    }

    fun dispose() {
        timerJob?.cancel()
        timerJob = null
        // Owned finite and terminal writes end with the composition; the parent scope
        // itself is never cancelled here. Gated writes never commit later.
        edits.dispose()
        activeRestTimer = null
        restTimerRemainingSeconds = 0
        endingTimerIdsPendingPersistence.clear()
        releaseRestTimerWakeLock()
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_BUFFER_MILLIS = 1_000L
    }
}

private object NoopRestTimerHardware : RestTimerHardware {
    override fun vibrate(durationMillis: Long) = Unit
    override fun acquireWakeLock(timeoutMillis: Long) = Unit
    override fun releaseWakeLock() = Unit
}

private class AndroidRestTimerHardware(private val context: Context) : RestTimerHardware {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun vibrate(durationMillis: Long) = context.vibrateRestTimerCue(durationMillis)

    override fun acquireWakeLock(timeoutMillis: Long) {
        releaseWakeLock()
        val lock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:rest_timer")
            ?: return
        lock.setReferenceCounted(false)
        lock.acquire(timeoutMillis)
        wakeLock = lock
    }

    override fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}

@Composable
internal fun rememberGymAppState(): GymAppState {
    val context = LocalContext.current.applicationContext
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    return remember(context, repository, scope) {
        GymAppState(context, repository, scope)
    }
}

@Suppress("DEPRECATION")
private fun Context.vibrateRestTimerCue(durationMillis: Long) {
    val vibrator = getSystemService(Vibrator::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = VibrationEffect.createOneShot(
            durationMillis,
            VibrationEffect.DEFAULT_AMPLITUDE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMillis)
    }
}
