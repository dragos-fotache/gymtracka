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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dragosfotache.gymtracka.data.gym.GymRepository
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
import kotlin.coroutines.coroutineContext

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
        val currentWorkout = activeWorkout ?: return
        scope.launch {
            var nextWorkout: WorkoutEntity? = null
            try {
                nextWorkout = when {
                    workoutToPerformAgainId != null -> {
                        repository.performWorkoutAgain(
                            workoutToPerformAgainId.orEmpty(),
                            clock.wallTimeMillis(),
                        )
                    }
                    routineToStartId != null -> {
                        repository.startWorkoutFromRoutine(
                            routineToStartId.orEmpty(),
                            clock.wallTimeMillis(),
                        )
                    }
                    else -> repository.startEmptyWorkout(clock.wallTimeMillis())
                } ?: repository.startEmptyWorkout(clock.wallTimeMillis())
                repository.discardWorkout(currentWorkout.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                nextWorkout?.let { created ->
                    try {
                        repository.discardWorkout(created.id)
                    } catch (_: Exception) {
                        // Leave cleanup to normal active-workout discovery if storage is unavailable.
                    }
                }
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
            } catch (_: Exception) {
                Unit
            }
        }
    }

    private fun performAgain(workoutId: String) {
        scope.launch {
            try {
                activeWorkout = repository.performWorkoutAgain(workoutId, clock.wallTimeMillis())
                activeWorkout?.let { destination = Destination.ActiveWorkout }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
        }
    }

    private fun startRoutine(routineId: String) {
        scope.launch {
            try {
                activeWorkout = repository.startWorkoutFromRoutine(routineId, clock.wallTimeMillis())
                activeWorkout?.let { destination = Destination.ActiveWorkout }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
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
                if (repository.hasCompletedSets(workout.id)) {
                    showFinishDialog = true
                } else {
                    showNoCompletedSetsDialog = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
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
                finishActiveTimer()
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

    fun startRestTimer(workoutSet: WorkoutSetEntity, durationSeconds: Int) {
        if (durationSeconds <= 0) return
        activeRestTimer?.let { previousTimer ->
            val elapsedMillis = clock.elapsedRealtimeMillis() - previousTimer.startedAtElapsedMillis
            endingTimerIdsPendingPersistence += previousTimer.timerId
            scope.launch {
                persistRestTime(previousTimer, elapsedMillis)
            }
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
        val elapsedMillis = clock.elapsedRealtimeMillis() - timer.startedAtElapsedMillis
        endingTimerIdsPendingPersistence += timer.timerId
        activeRestTimer = null
        restTimerRemainingSeconds = 0
        releaseRestTimerWakeLock(timer.timerId)
        scope.launch {
            persistRestTime(timer, elapsedMillis)
        }
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
                        persistRestTime(timer, elapsedMillis)
                        return@launch
                    }
                }
                delay(100L)
            }
        }
    }

    private suspend fun finishActiveTimer() {
        val timer = activeRestTimer ?: return
        timerJob?.cancel()
        persistRestTime(timer, clock.elapsedRealtimeMillis() - timer.startedAtElapsedMillis)
    }

    private suspend fun persistRestTime(timer: ActiveRestTimer, elapsedMillis: Long) {
        val wasActive = activeRestTimer?.timerId == timer.timerId
        if (wasActive) {
            endingTimerIdsPendingPersistence.remove(timer.timerId)
            activeRestTimer = null
            restTimerRemainingSeconds = 0
        } else if (!endingTimerIdsPendingPersistence.remove(timer.timerId)) {
            return
        }
        if (wasActive) releaseRestTimerWakeLock()
        try {
            repository.updateWorkoutSetRestSeconds(
                workoutSetId = timer.workoutSetId,
                elapsedSeconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L).toInt(),
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
