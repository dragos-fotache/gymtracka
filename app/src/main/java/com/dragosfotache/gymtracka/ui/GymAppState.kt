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
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val terminalWriteQueue = Mutex()
    private val terminalWriteJobs = mutableListOf<Job>()
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

    fun deleteCustomExercise(exerciseId: String) {
        scope.launch {
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
        destination = Destination.Workout
    }

    fun showHistory() {
        destination = Destination.History
    }

    fun requestNewRoutine() {
        scope.launch {
            editingRoutineId = repository.createRoutineDraft().id
            destination = Destination.Routine
        }
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

    fun cancelRoutine(isDraft: Boolean) {
        val routineId = editingRoutineId ?: return
        scope.launch {
            if (isDraft) repository.deleteRoutine(routineId)
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

    fun confirmStartNewWorkout() {
        scope.launch {
            val startedAtMillis = clock.wallTimeMillis()
            // Repository starts atomically replace the previous active aggregate,
            // so there is no separate discard and no compensating delete here.
            // Only an invalid source falls back to a fresh empty workout.
            val nextWorkout: WorkoutEntity = try {
                when {
                    workoutToPerformAgainId != null -> {
                        repository.performWorkoutAgain(
                            workoutToPerformAgainId.orEmpty(),
                            startedAtMillis,
                        ) ?: repository.startEmptyWorkout(startedAtMillis)
                    }
                    routineToStartId != null -> {
                        repository.startWorkoutFromRoutine(
                            routineToStartId.orEmpty(),
                            startedAtMillis,
                        ) ?: repository.startEmptyWorkout(startedAtMillis)
                    }
                    else -> repository.startEmptyWorkout(startedAtMillis)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            cancelTimer()
            activeWorkout = nextWorkout
            destination = Destination.ActiveWorkout
            showStartNewWorkoutDialog = false
            workoutToPerformAgainId = null
            routineToStartId = null
        }
    }

    private fun startWorkout() {
        scope.launch {
            try {
                activeWorkout = repository.startEmptyWorkout(clock.wallTimeMillis())
                destination = Destination.ActiveWorkout
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
    }

    private fun performAgain(workoutId: String) {
        scope.launch {
            try {
                activeWorkout = repository.performWorkoutAgain(workoutId, clock.wallTimeMillis())
                activeWorkout?.let { destination = Destination.ActiveWorkout }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
    }

    private fun startRoutine(routineId: String) {
        scope.launch {
            try {
                activeWorkout = repository.startWorkoutFromRoutine(routineId, clock.wallTimeMillis())
                activeWorkout?.let { destination = Destination.ActiveWorkout }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
    }

    fun updateActiveWorkoutName(name: String) {
        activeWorkout = activeWorkout?.copy(name = name)
    }

    fun requestDiscardWorkout() {
        showDiscardDialog = true
    }

    fun dismissDiscardDialog() {
        showDiscardDialog = false
    }

    fun confirmDiscardWorkout() {
        val workout = activeWorkout ?: return
        scope.launch {
            try {
                repository.discardWorkout(workout.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            cancelTimer()
            activeWorkout = null
            destination = Destination.Workout
            showDiscardDialog = false
        }
    }

    fun requestFinishWorkout() {
        val workout = activeWorkout ?: return
        scope.launch {
            try {
                drainRegisteredFeatureEdits(workout.id)
                if (repository.hasCompletedSets(workout.id)) {
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

    fun confirmFinishWorkout() {
        val workout = activeWorkout ?: return
        scope.launch {
            try {
                // A late accepted edit must not be overtaken: drain again after
                // the initial request drain before committing the finish.
                drainRegisteredFeatureEdits(workout.id)
                terminateCurrentTimerAndAwaitTerminalWrites()
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
            } catch (_: Exception) {
                showFinishDialog = true
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

    fun startRestTimer(workoutSet: WorkoutSetEntity, durationSeconds: Int) {
        if (durationSeconds <= 0) return
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
        )
        activeRestTimer = timer
        restTimerRemainingSeconds = durationSeconds
        runTimer(timer)
    }

    fun skipRestTimer(timer: ActiveRestTimer) {
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
        val timer = activeRestTimer ?: return
        if (timer.workoutSetId != workoutSetId) return
        timerJob?.cancel()
        timerJob = null
        activeRestTimer = null
        restTimerRemainingSeconds = 0
        releaseRestTimerWakeLock(timer.timerId)
    }

    fun adjustRestTimer(timer: ActiveRestTimer, adjustmentSeconds: Int) {
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

    private suspend fun terminateCurrentTimerAndAwaitTerminalWrites() {
        val timer = activeRestTimer
        if (timer != null) {
            timerJob?.cancel()
            claimAndQueueTerminalWrite(
                timer,
                clock.elapsedRealtimeMillis() - timer.startedAtElapsedMillis,
            )
        }
        awaitTerminalWrites()
    }

    /**
     * Claims one timer's terminal transition exactly once, clears its
     * presentation synchronously, and queues only the DAO write on a serial
     * queue: replacement countdowns start immediately, repeated writes to the
     * same set commit in claim order, and the write survives countdown
     * cancellation. The completion key increments only after a successful
     * commit; a failed write stays terminal and is never retried.
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
        val job = scope.launch {
            terminalWriteQueue.withLock {
                try {
                    repository.updateWorkoutSetRestSeconds(
                        workoutSetId = timer.workoutSetId,
                        elapsedSeconds = elapsedSeconds,
                    )
                    restTimerCompletionKey++
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The timer is terminal even if the local rest write fails; never retry it twice.
                } finally {
                    if (activeRestTimer?.timerId == timer.timerId) {
                        activeRestTimer = null
                        restTimerRemainingSeconds = 0
                    }
                    if (wasActive) releaseRestTimerWakeLock(timer.timerId)
                }
            }
        }
        terminalWriteJobs += job
        job.invokeOnCompletion { terminalWriteJobs -= job }
    }

    private suspend fun awaitTerminalWrites() {
        while (true) {
            val snapshot = terminalWriteJobs.toList()
            if (snapshot.isEmpty()) return
            snapshot.forEach { it.join() }
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
        // Owned terminal writes end with the composition; the parent scope
        // itself is never cancelled here. Gated writes never commit later.
        terminalWriteJobs.toList().forEach { it.cancel() }
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
