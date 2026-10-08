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

internal object SystemGymAppClock : GymAppClock {
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
    private var discardRequestedWorkoutId: String? = null
    private var finishRequestedWorkoutId: String? = null
    private var startNewRequestedActiveId: String? = null
    var finishedWorkoutSummary by mutableStateOf<FinishedWorkoutSummary?>(null)
        private set
    var activeRestTimer by mutableStateOf<ActiveRestTimer?>(null)
        private set
    var restTimerRemainingSeconds by mutableIntStateOf(0)
        private set
    var restTimerCompletionKey by mutableIntStateOf(0)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    var timerBindingFault: ActiveRestTimer? by mutableStateOf(null)
        private set

    /**
     * Narrow compatible boundary for account-materialization timer faults. The live
     * account runtime supplies exact-handle invalidation; legacy constructor paths
     * leave it null and keep the latched local fault. Never invoked for local
     * sessions or transient read errors.
     */
    internal var onTimerBindingFault: ((ActiveRestTimer) -> Unit)? = null

    private var timerJob: Job? = null
    private var nextTimerId = 0L
    private var wakeLockTimerId: Long? = null
    private val endingTimerIdsPendingPersistence = mutableSetOf<Long>()
    private var finishDrainWorkoutId: String? = null
    private var finishDrainOwner: Any? = null
    private var finishDrain: (suspend () -> Unit)? = null
    private var finishPendingWorkoutId: String? = null
    private var finishPendingToken: Any? = null

    /** True only while the given request token still owns the pending finish request. */
    private fun isFinishRequestCurrent(workoutId: String, token: Any): Boolean =
        finishPendingWorkoutId == workoutId && finishPendingToken === token
    private var projectionObserverJob: Job? = null
    private val refreshLock = Any()
    private var refreshGeneration = 0L

    constructor(context: Context, repository: GymAppStateRepository, scope: CoroutineScope) : this(
        repository = repository,
        scope = scope,
        timerHardware = AndroidRestTimerHardware(context.applicationContext),
    )

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { dispose() }
        // Single discovery path: the materialized-epoch stream's first signal drives
        // initial discovery through the same guarded load. No separate initial load
        // that could duplicate the stream signal or overwrite newer state.
        projectionObserverJob = scope.launch {
            try {
                repository.observeProjectionEpoch().collect {
                    // Claim the load generation synchronously per signal, then launch
                    // the load outside the collector so a newer epoch can invalidate
                    // a still-suspended load instead of queueing behind it.
                    val claimed: Long = synchronized(refreshLock) {
                        refreshGeneration += 1
                        refreshGeneration
                    }
                    scope.launch {
                        try {
                            refreshProjectionAt(claimed)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            if (claimGuard(claimed)) {
                                refreshFailed = true
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Observer failure fences outstanding loads and surfaces; the
                // terminated observer is not automatically retried.
                synchronized(refreshLock) {
                    refreshGeneration += 1
                }
                refreshFailed = true
            }
        }
    }

    /** Latest-generation, disposal and scope guard for every load publication. */
    private fun claimGuard(generation: Long): Boolean =
        synchronized(refreshLock) { generation == refreshGeneration } &&
            scope.coroutineContext[Job]?.isActive != false &&
            !edits.isDisposed()

    /** Invalidates in-flight loads when accepted shell/domain state changes. */
    private fun invalidateLoads() {
        synchronized(refreshLock) {
            refreshGeneration += 1
        }
    }

    /**
     * Invalidates in-flight loads for pure navigation and schedules one fresh guarded
     * model reload, so a navigation-dropped epoch-triggered load cannot leave the pill
     * stale with no future signal. No mutation, no forced navigation into a new active,
     * no endless collector/ticker join: exactly one replacement load whose guards let
     * accepted local mutation drafts win over stale reads.
     */
    private fun invalidateForNavigation() {
        invalidateLoads()
        scope.launch {
            try {
                refreshProjection()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // refreshProjection publishes its own guarded failure evidence.
            }
        }
    }

    /**
     * R12c guarded selected-model reload entry: claims a fresh load generation and
     * runs the guarded load. Used by tests and initial discovery.
     */
    suspend fun refreshProjection() {
        val claimed: Long = synchronized(refreshLock) {
            refreshGeneration += 1
            refreshGeneration
        }
        refreshProjectionAt(claimed)
    }

    /**
     * Guarded load body for one claimed generation. Phase 1 loads without publishing
     * anything; phase 2 publishes atomically under [claimGuard]. Refreshes the
     * active-workout pill (including same-ID renames), preserves a valid unchanged
     * timer, reconciles missing nested selections without forced navigation into
     * replacements, retains open explicit-save custom-editor drafts (marking
     * [refreshFailed]) and never starts timers, creates summary dialogs or emits
     * mutations. A timer-invalidating materialization that bypassed the mandatory
     * prepublication claim fails closed: no late rest write, no silent survival or
     * silent discard of the invalid timer.
     */
    internal suspend fun refreshProjectionAt(myGeneration: Long) {
        try {
            edits.awaitPendingEdits()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (claimGuard(myGeneration)) {
                refreshFailed = true
            }
            return
        }
        val capturedDestination = destination
        val capturedHistoryId = selectedHistoryWorkoutId
        val capturedSelectedRoutine = selectedRoutineId
        val capturedEditingRoutine = editingRoutineId
        val capturedSelectedExercise = selectedExerciseId
        val capturedEditingCustom = editingCustomExerciseId
        val capturedActiveId = activeWorkout?.id
        val capturedActiveName = activeWorkout?.name
        val capturedTimer = activeRestTimer
        if (!claimGuard(myGeneration)) return

        // Phase 1a: newest active discovery; nothing published yet.
        var staleSession = false
        var discoveryFailed = false
        val newestActive = try {
            repository.activeWorkout()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is com.dragosfotache.gymtracka.data.sync.store.StaleSessionException) {
                staleSession = true
            } else {
                discoveryFailed = true
            }
            null
        }
        if (staleSession) {
            if (!claimGuard(myGeneration)) return
            if (destination != capturedDestination) return
            activeWorkout = null
            selectedHistoryWorkoutId = null
            selectedRoutineId = null
            editingRoutineId = null
            selectedExerciseId = null
            editingCustomExerciseId = null
            return
        }
        if (discoveryFailed) {
            if (claimGuard(myGeneration)) {
                refreshFailed = true
            }
            return
        }
        if (!claimGuard(myGeneration)) return
        // A later accepted local start, rename or replacement wins over this snapshot.
        if (activeWorkout?.id != capturedActiveId || activeWorkout?.name != capturedActiveName) return

        // Phase 1b: selected-target existence with captured-ID rechecks after suspension.
        val checkHistory = capturedHistoryId != null &&
            destination == Destination.WorkoutHistoryDetail &&
            selectedHistoryWorkoutId == capturedHistoryId
        var historyExists = true
        if (checkHistory) {
            historyExists = try {
                repository.workoutDetails(capturedHistoryId!!) != null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (claimGuard(myGeneration)) {
                    refreshFailed = true
                }
                return
            }
            if (!claimGuard(myGeneration)) return
            if (destination != capturedDestination || selectedHistoryWorkoutId != capturedHistoryId) return
        }
        val checkRoutineDetail = capturedSelectedRoutine != null &&
            destination == Destination.RoutineDetail &&
            selectedRoutineId == capturedSelectedRoutine
        val checkRoutineEditor = capturedEditingRoutine != null &&
            (destination == Destination.Routine || destination == Destination.AddRoutineExercises) &&
            editingRoutineId == capturedEditingRoutine
        var routineExists = true
        if (checkRoutineDetail || checkRoutineEditor) {
            val targetId = if (checkRoutineDetail) capturedSelectedRoutine else capturedEditingRoutine
            routineExists = try {
                repository.routineDetails(targetId!!) != null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (claimGuard(myGeneration)) {
                    refreshFailed = true
                }
                return
            }
            if (!claimGuard(myGeneration)) return
            if (destination != capturedDestination) return
            if (checkRoutineDetail && selectedRoutineId != capturedSelectedRoutine) return
            if (checkRoutineEditor && editingRoutineId != capturedEditingRoutine) return
        }
        val checkCustomDetail = capturedSelectedExercise != null &&
            destination == Destination.ExerciseDetail &&
            selectedExerciseId == capturedSelectedExercise
        var customDetailExists = true
        if (checkCustomDetail) {
            customDetailExists = try {
                repository.exerciseDetails(capturedSelectedExercise!!) != null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (claimGuard(myGeneration)) {
                    refreshFailed = true
                }
                return
            }
            if (!claimGuard(myGeneration)) return
            if (destination != capturedDestination || selectedExerciseId != capturedSelectedExercise) return
        }
        val checkCustomEdit = capturedEditingCustom != null &&
            destination == Destination.EditCustomExercise &&
            editingCustomExerciseId == capturedEditingCustom
        var customEditExists = true
        if (checkCustomEdit) {
            customEditExists = try {
                repository.exerciseDetails(capturedEditingCustom!!) != null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (claimGuard(myGeneration)) {
                    refreshFailed = true
                }
                return
            }
            if (!claimGuard(myGeneration)) return
            if (destination != capturedDestination || editingCustomExerciseId != capturedEditingCustom) return
        }

        // Phase 1c: timer binding validation for account sessions only; local/default
        // discovery is never an account materialization grant. Ordinary transient
        // read errors retain the valid current timer/models and mark failure; only a
        // confirmed invalid binding faults.
        var faultedTimer: ActiveRestTimer? = null
        if (capturedTimer != null && session != RepositorySession.SIGNED_OUT) {
            try {
                if (timerBindingInvalid(capturedTimer)) {
                    faultedTimer = capturedTimer
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (claimGuard(myGeneration)) {
                    refreshFailed = true
                }
                return
            }
            if (!claimGuard(myGeneration)) return
            val liveTimer = activeRestTimer
            if (liveTimer != null && liveTimer.timerId != capturedTimer.timerId) return
        }

        // Phase 2: single guarded publication; no suspensions below.
        if (!claimGuard(myGeneration)) return
        if (destination != capturedDestination) return
        if (activeWorkout?.id != capturedActiveId || activeWorkout?.name != capturedActiveName) return
        var failed = false
        val activeChanged = capturedActiveId != newestActive?.id
        val inOldActiveFlow = capturedDestination == Destination.ActiveWorkout ||
            capturedDestination == Destination.AddExercises ||
            capturedDestination == Destination.ReplaceExercise
        if (activeChanged) {
            // Any active ID change retires old-root dialog/source intent: a collapsed
            // confirmation must never capture the replacement root. Unrelated tab and
            // nested selections are preserved.
            activeWorkout = newestActive
            replacingWorkoutExerciseId = null
            showDiscardDialog = false
            showFinishDialog = false
            showNoCompletedSetsDialog = false
            showStartNewWorkoutDialog = false
            discardRequestedWorkoutId = null
            finishRequestedWorkoutId = null
            finishPendingWorkoutId = null
            finishPendingToken = null
            startNewRequestedActiveId = null
            workoutToPerformAgainId = null
            routineToStartId = null
            if (inOldActiveFlow) {
                destination = Destination.Workout
            }
        } else {
            activeWorkout = newestActive
        }
        // Captured nested existence reconciliation runs independently of active-ID
        // retirement: the same materialization can replace the active root and remove
        // an independently selected history/routine/custom target. Each check is armed
        // only for its own destination (old active-flow destinations arm none), the
        // pill stays newest, and nothing navigates into a replacement. A vanished
        // explicit-save custom editor retains its draft/destination with failure marked.
        if (checkHistory && !historyExists) {
            selectedHistoryWorkoutId = null
            destination = Destination.History
        } else if ((checkRoutineDetail || checkRoutineEditor) && !routineExists) {
            selectedRoutineId = null
            editingRoutineId = null
            destination = Destination.Workout
        } else if (checkCustomDetail && !customDetailExists) {
            selectedExerciseId = null
            destination = Destination.Exercises
        } else if (checkCustomEdit && !customEditExists) {
            failed = true
        }
        val fault = faultedTimer
        if (fault != null) {
            // Confirmed invalidation that bypassed the mandatory prepublication claim:
            // fail closed for the exact account binding through the runtime lock (or
            // the latched local fault with no runtime attached). Never retro-write
            // rest into deleted/history rows and never erase terminal failure evidence.
            timerBindingFault = fault
            try {
                onTimerBindingFault?.invoke(fault)
            } catch (_: Exception) {
            }
            val live = activeRestTimer
            if (live != null && live.timerId == fault.timerId) {
                timerJob?.cancel()
                timerJob = null
                activeRestTimer = null
                restTimerRemainingSeconds = 0
                releaseRestTimerWakeLock()
            }
            refreshFailed = true
            return
        }
        refreshFailed = failed
    }

    /**
     * True when the running timer's binding is invalid in the visible graph: missing
     * workout/entry/set, non-active parent or uncompleted set. Indeterminate read
     * failures propagate to the caller, which fails closed.
     */
    private suspend fun timerBindingInvalid(timer: ActiveRestTimer): Boolean {
        val workoutId = timer.workoutId ?: return true
        val details = repository.workoutDetails(workoutId) ?: return true
        if (details.workout.status != "active") return true
        val entry = details.exercises.singleOrNull { it.workoutExercise.id == timer.workoutExerciseId }
            ?: return true
        val set = entry.sets.singleOrNull { it.id == timer.workoutSetId } ?: return true
        if (!set.isCompleted) return true
        return false
    }

    /**
     * R12c actual drain/claim transition barrier over this state's owned
     * finite-edit coordinator and running rest timer. Acquired while the old
     * handle is still valid; pauses/drains accepted edits, claims/drains the
     * running rest exactly once, then grants a held lease. Retained drain
     * failures stay observable and block the grant; failures/cancellation close
     * every acquired pause and retain healthy editing. Runs on the app-state
     * owner dispatcher without adopting its Job.
     */
    fun transitionBarrier(): com.dragosfotache.gymtracka.data.sync.session.AppSessionTransitionBarrier =
        com.dragosfotache.gymtracka.data.sync.session.AppSessionTransitionBarrier { previous ->
            if (previous != null && previous != session) {
                throw IllegalStateException(
                    "Transition previous scope differs from app state; failing closed",
                )
            }
            var acquired: ProjectionPublicationLease? = null
            try {
                withContext(scope.coroutineContext.minusKey(Job)) {
                    scope.ensureActive()
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
                        if (current != null) {
                            claimAndQueueTerminalWrite(
                                current,
                                clock.elapsedRealtimeMillis() - current.startedAtElapsedMillis,
                            )
                        }
                        edits.awaitTerminalWrites()
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

    fun showExercises() {
        invalidateForNavigation()
        destination = Destination.Exercises
    }

    fun showExerciseDetails(exerciseId: String) {
        invalidateForNavigation()
        selectedExerciseId = exerciseId
        destination = Destination.ExerciseDetail
    }

    fun closeExerciseDetails() {
        invalidateForNavigation()
        selectedExerciseId = null
        destination = Destination.Exercises
    }

    fun showCreateExercise() {
        // Explicit-save editor entry is refused while finite admission is paused or
        // a publication lease is granted; pure browsing stays usable.
        if (edits.isPaused || edits.grantedLeaseActive()) return
        invalidateForNavigation()
        destination = Destination.CreateExercise
    }

    fun closeCreateExercise() {
        invalidateForNavigation()
        destination = Destination.Exercises
    }

    fun editCustomExercise(exerciseId: String) {
        // Explicit-save editor entry is refused while finite admission is paused or
        // a publication lease is granted; pure browsing stays usable.
        if (edits.isPaused || edits.grantedLeaseActive()) return
        invalidateForNavigation()
        editingCustomExerciseId = exerciseId
        destination = Destination.EditCustomExercise
    }

    fun closeEditCustomExercise() {
        invalidateForNavigation()
        editingCustomExerciseId = null
        destination = Destination.Exercises
    }

    fun deleteCustomExercise(exerciseId: String): Job? {
        invalidateLoads()
        return edits.launchEdit("exercise-delete:$exerciseId") {
            repository.deleteCustomExercise(exerciseId)
            closeExerciseDetails()
        }
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
        invalidateForNavigation()
        destination = Destination.Workout
    }

    fun showHistory() {
        invalidateForNavigation()
        destination = Destination.History
    }

    fun requestNewRoutine(): Job? {
        invalidateLoads()
        return edits.launchEdit("routine:create-draft") {
            editingRoutineId = repository.createRoutineDraft().id
            destination = Destination.Routine
        }
    }

    fun editRoutine(routineId: String) {
        invalidateForNavigation()
        editingRoutineId = routineId
        destination = Destination.Routine
    }

    fun showRoutineDetails(routineId: String) {
        invalidateForNavigation()
        selectedRoutineId = routineId
        destination = Destination.RoutineDetail
    }

    fun closeRoutineDetails() {
        invalidateForNavigation()
        selectedRoutineId = null
        destination = Destination.Workout
    }

    fun showRoutineExercisePicker() {
        invalidateForNavigation()
        destination = Destination.AddRoutineExercises
    }

    fun closeRoutineExercisePicker() {
        invalidateForNavigation()
        destination = Destination.Routine
    }

    fun cancelRoutine(isDraft: Boolean): Job? {
        val routineId = editingRoutineId ?: return null
        invalidateLoads()
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
        invalidateForNavigation()
        editingRoutineId = null
        destination = Destination.Workout
    }

    fun resumeWorkout() {
        invalidateForNavigation()
        destination = Destination.ActiveWorkout
    }

    fun showExercisePicker() {
        invalidateForNavigation()
        destination = Destination.AddExercises
    }

    fun showExerciseReplacementPicker(workoutExerciseId: String) {
        invalidateForNavigation()
        replacingWorkoutExerciseId = workoutExerciseId
        destination = Destination.ReplaceExercise
    }

    fun closeExerciseReplacementPicker() {
        invalidateForNavigation()
        replacingWorkoutExerciseId = null
        destination = Destination.ActiveWorkout
    }

    fun showWorkoutHistory(workoutId: String) {
        invalidateForNavigation()
        selectedHistoryWorkoutId = workoutId
        destination = Destination.WorkoutHistoryDetail
    }

    fun closeWorkoutHistory() {
        invalidateForNavigation()
        selectedHistoryWorkoutId = null
        destination = Destination.History
    }

    fun requestStartWorkout() {
        invalidateLoads()
        if (activeWorkout == null) {
            startWorkout()
        } else {
            startNewRequestedActiveId = activeWorkout?.id
            showStartNewWorkoutDialog = true
        }
    }

    fun requestPerformAgain(workoutId: String) {
        invalidateLoads()
        if (activeWorkout == null) {
            performAgain(workoutId)
        } else {
            workoutToPerformAgainId = workoutId
            startNewRequestedActiveId = activeWorkout?.id
            showStartNewWorkoutDialog = true
        }
    }

    fun requestStartRoutine(routineId: String) {
        invalidateLoads()
        if (activeWorkout == null) {
            startRoutine(routineId)
        } else {
            routineToStartId = routineId
            startNewRequestedActiveId = activeWorkout?.id
            showStartNewWorkoutDialog = true
        }
    }

    fun dismissStartNewWorkoutDialog() {
        invalidateLoads()
        showStartNewWorkoutDialog = false
        workoutToPerformAgainId = null
        routineToStartId = null
        startNewRequestedActiveId = null
    }

    fun confirmStartNewWorkout(): Job? {
        // Old consent never transfers: refuse when the dialog was invalidated or the
        // selected root changed since the request, even if Compose still shows it.
        if (!showStartNewWorkoutDialog) return null
        val current = activeWorkout ?: return null
        if (current.id != startNewRequestedActiveId) {
            showStartNewWorkoutDialog = false
            startNewRequestedActiveId = null
            workoutToPerformAgainId = null
            routineToStartId = null
            return null
        }
        invalidateLoads()
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
            startNewRequestedActiveId = null
            finishRequestedWorkoutId = null
            finishPendingWorkoutId = null
            finishPendingToken = null
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
        invalidateLoads()
        activeWorkout = activeWorkout?.copy(name = name)
    }

    fun requestDiscardWorkout() {
        invalidateLoads()
        discardRequestedWorkoutId = activeWorkout?.id
        showDiscardDialog = true
    }

    fun dismissDiscardDialog() {
        invalidateLoads()
        showDiscardDialog = false
        discardRequestedWorkoutId = null
    }

    fun confirmDiscardWorkout(): Job? {
        // Old consent never transfers: refuse when the dialog was invalidated or the
        // selected root changed since the request, even if Compose still shows it.
        if (!showDiscardDialog) return null
        val workout = activeWorkout ?: return null
        if (workout.id != discardRequestedWorkoutId) {
            showDiscardDialog = false
            discardRequestedWorkoutId = null
            return null
        }
        invalidateLoads()
        // Exclusive discard drains accepted feature writes and terminal rest before the destructive
        // Room call. The running timer is cancelled only after repository success (explicit local
        // discard semantics); failure keeps that timer, the dialog, and the active workout intact.
        return edits.launchExclusiveEdit("transition:discard:${workout.id}") {
            repository.discardWorkout(workout.id)
            cancelTimer()
            // Bind publication to the confirmed root: a remotely replaced active keeps
            // its own state instead of inheriting this confirmation.
            if (activeWorkout?.id == workout.id) {
                activeWorkout = null
                destination = Destination.Workout
                showDiscardDialog = false
                discardRequestedWorkoutId = null
                finishRequestedWorkoutId = null
                finishPendingWorkoutId = null
                finishPendingToken = null
            }
        }
    }

    fun requestFinishWorkout() {
        val workout = activeWorkout ?: return
        invalidateLoads()
        val workoutId = workout.id
        // Capture request target/token synchronously: only this captured pending-or-shown
        // consent for the SAME root may later confirm. Dismiss, supersession and remote
        // root change invalidate it so a canceled request can never reopen its prompt.
        val token = Any()
        finishPendingWorkoutId = workoutId
        finishPendingToken = token
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
                if (!isFinishRequestCurrent(workoutId, token)) return@launch
                val hasCompleted = repository.hasCompletedSets(workoutId)
                // Re-fence after the suspending read for the same reason.
                if (activeWorkout?.id != workoutId) return@launch
                if (!isFinishRequestCurrent(workoutId, token)) return@launch
                if (hasCompleted) {
                    finishRequestedWorkoutId = workoutId
                    showFinishDialog = true
                } else {
                    if (isFinishRequestCurrent(workoutId, token)) {
                        finishPendingWorkoutId = null
                        finishPendingToken = null
                    }
                    showNoCompletedSetsDialog = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
    }

    fun dismissFinishDialog() {
        invalidateLoads()
        showFinishDialog = false
        finishRequestedWorkoutId = null
        finishPendingWorkoutId = null
        finishPendingToken = null
    }

    fun dismissNoCompletedSetsDialog() {
        invalidateLoads()
        showNoCompletedSetsDialog = false
        finishRequestedWorkoutId = null
        finishPendingWorkoutId = null
        finishPendingToken = null
    }

    fun dismissFinishedWorkoutSummary() {
        invalidateLoads()
        finishedWorkoutSummary = null
    }

    fun confirmFinishWorkout(): Job? {
        // Old consent never transfers: only valid captured pending-or-shown consent for
        // the SAME root may confirm. A drained-but-not-yet-shown pending request still
        // reserves exclusive admission and waits for its writes below.
        val workout = activeWorkout ?: return null
        val shownOk = showFinishDialog && workout.id == finishRequestedWorkoutId
        val pendingOk = finishPendingWorkoutId != null && finishPendingWorkoutId == workout.id
        if (!shownOk && !pendingOk) {
            showFinishDialog = false
            finishRequestedWorkoutId = null
            return null
        }
        invalidateLoads()
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
                    // Bind publication to the confirmed root: a remotely replaced active
                    // keeps its own state instead of inheriting this confirmation.
                    if (activeWorkout?.id == workout.id) {
                        val details = repository.workoutDetails(workout.id)
                        finishedWorkoutSummary = FinishedWorkoutSummary(
                            name = activeWorkout?.name ?: workout.name,
                            durationMillis = finishedAtMillis - workout.startedAtMillis,
                            volumeKg = details?.totalVolumeKg() ?: "0 kg",
                        )
                        activeWorkout = null
                        destination = Destination.Workout
                        showFinishDialog = false
                        finishRequestedWorkoutId = null
                        finishPendingWorkoutId = null
                        finishPendingToken = null
                    }
                } else {
                    if (activeWorkout?.id == workout.id) {
                        showNoCompletedSetsDialog = true
                        showFinishDialog = false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Safe UI cleanup preserves the failure dialog state for the confirmed
                // root only, then the origin is rethrown so the coordinator retains it
                // instead of reporting false success.
                if (activeWorkout?.id == workout.id) {
                    showFinishDialog = true
                }
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
        expectedWorkoutId: String? = null,
    ) {
        if (durationSeconds <= 0) return
        if (edits.isAdmissionClosed()) return
        if (session != RepositorySession.SIGNED_OUT) {
            // Account timer-start policy: only the set-completion callback (never a
            // manual or stale trigger) may start, and only for a currently selected
            // active parent with a completed set. Anything else refuses outright: no
            // phantom writeless countdown for finished-row or cross-root callbacks.
            // Local constructor paths keep their legacy behavior.
            if (!acceptedEdit) return
            val selectedId = activeWorkout?.id ?: return
            if (!workoutSet.isCompleted) return
            if (expectedWorkoutId != null && expectedWorkoutId != selectedId) return
        }
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
        invalidateLoads()
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
        invalidateLoads()
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
        invalidateLoads()
        timerJob?.cancel()
        timerJob = null
        activeRestTimer = null
        restTimerRemainingSeconds = 0
        releaseRestTimerWakeLock(timer.timerId)
    }

    fun adjustRestTimer(timer: ActiveRestTimer, adjustmentSeconds: Int) {
        if (edits.isPaused) return
        if (activeRestTimer?.timerId != timer.timerId) return
        invalidateLoads()
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
        projectionObserverJob?.cancel()
        projectionObserverJob = null
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

internal fun androidRestTimerHardware(context: Context): RestTimerHardware =
    AndroidRestTimerHardware(context.applicationContext)

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
