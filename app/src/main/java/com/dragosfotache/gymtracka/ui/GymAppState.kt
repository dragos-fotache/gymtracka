package com.dragosfotache.gymtracka.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class Destination {
    Exercises,
    Workout,
    ActiveWorkout,
    AddExercises,
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
)

internal class GymAppState(
    private val context: Context,
    private val repository: GymRepository,
    private val scope: CoroutineScope,
) {
    var destination by mutableStateOf(Destination.Workout)
        private set
    var activeWorkout by mutableStateOf<WorkoutEntity?>(null)
        private set
    var selectedHistoryWorkoutId by mutableStateOf<String?>(null)
        private set
    var showDiscardDialog by mutableStateOf(false)
        private set
    var showFinishDialog by mutableStateOf(false)
        private set
    var showNoCompletedSetsDialog by mutableStateOf(false)
        private set
    var showStartNewWorkoutDialog by mutableStateOf(false)
        private set
    var finishedWorkoutSummary by mutableStateOf<FinishedWorkoutSummary?>(null)
        private set
    var activeRestTimer by mutableStateOf<ActiveRestTimer?>(null)
        private set
    var restTimerRemainingSeconds by mutableIntStateOf(0)
        private set
    var restTimerCompletionKey by mutableIntStateOf(0)
        private set

    private var timerJob: Job? = null

    init {
        scope.launch {
            activeWorkout = repository.activeWorkout()
        }
    }

    fun showExercises() {
        destination = Destination.Exercises
    }

    fun showWorkouts() {
        destination = Destination.Workout
    }

    fun resumeWorkout() {
        destination = Destination.ActiveWorkout
    }

    fun showExercisePicker() {
        destination = Destination.AddExercises
    }

    fun showWorkoutHistory(workoutId: String) {
        selectedHistoryWorkoutId = workoutId
        destination = Destination.WorkoutHistoryDetail
    }

    fun closeWorkoutHistory() {
        selectedHistoryWorkoutId = null
        destination = Destination.Workout
    }

    fun requestStartWorkout() {
        if (activeWorkout == null) {
            startWorkout()
        } else {
            showStartNewWorkoutDialog = true
        }
    }

    fun dismissStartNewWorkoutDialog() {
        showStartNewWorkoutDialog = false
    }

    fun confirmStartNewWorkout() {
        val currentWorkout = activeWorkout ?: return
        scope.launch {
            cancelTimer()
            repository.discardWorkout(currentWorkout.id)
            activeWorkout = repository.startEmptyWorkout()
            destination = Destination.ActiveWorkout
            showStartNewWorkoutDialog = false
        }
    }

    private fun startWorkout() {
        scope.launch {
            activeWorkout = repository.startEmptyWorkout()
            destination = Destination.ActiveWorkout
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
            cancelTimer()
            repository.discardWorkout(workout.id)
            activeWorkout = null
            destination = Destination.Workout
            showDiscardDialog = false
        }
    }

    fun requestFinishWorkout() {
        val workout = activeWorkout ?: return
        scope.launch {
            if (repository.hasCompletedSets(workout.id)) {
                showFinishDialog = true
            } else {
                showNoCompletedSetsDialog = true
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
            finishActiveTimer()
            val finishedAtMillis = System.currentTimeMillis()
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
        }
    }

    fun startRestTimer(workoutSet: WorkoutSetEntity, durationSeconds: Int) {
        activeRestTimer?.let { previousTimer ->
            scope.launch {
                persistRestTime(
                    previousTimer,
                    SystemClock.elapsedRealtime() - previousTimer.startedAtElapsedMillis,
                )
            }
        }
        val timer = ActiveRestTimer(
            workoutSetId = workoutSet.id,
            durationSeconds = durationSeconds,
            startedAtElapsedMillis = SystemClock.elapsedRealtime(),
        )
        activeRestTimer = timer
        restTimerRemainingSeconds = durationSeconds
        runTimer(timer)
    }

    fun skipRestTimer(timer: ActiveRestTimer) {
        timerJob?.cancel()
        scope.launch {
            persistRestTime(timer, SystemClock.elapsedRealtime() - timer.startedAtElapsedMillis)
        }
    }

    fun adjustRestTimer(timer: ActiveRestTimer, adjustmentSeconds: Int) {
        val adjustedTimer = timer.copy(
            durationSeconds = (timer.durationSeconds + adjustmentSeconds).coerceAtLeast(0),
        )
        activeRestTimer = adjustedTimer
        val elapsedMillis = SystemClock.elapsedRealtime() - adjustedTimer.startedAtElapsedMillis
        restTimerRemainingSeconds = (
            (adjustedTimer.durationSeconds * 1_000L - elapsedMillis).coerceAtLeast(0L) + 999L
        ).div(1_000L).toInt()
        runTimer(adjustedTimer)
    }

    private fun runTimer(timer: ActiveRestTimer) {
        timerJob?.cancel()
        timerJob = scope.launch {
            val signalledSeconds = mutableSetOf<Int>()
            while (isActive && activeRestTimer == timer) {
                val elapsedMillis = SystemClock.elapsedRealtime() - timer.startedAtElapsedMillis
                val remainingMillis = (timer.durationSeconds * 1_000L - elapsedMillis).coerceAtLeast(0L)
                restTimerRemainingSeconds = ((remainingMillis + 999L) / 1_000L).toInt()
                when (restTimerRemainingSeconds) {
                    2, 1 -> if (signalledSeconds.add(restTimerRemainingSeconds)) {
                        context.vibrateRestTimerCue(100L)
                    }

                    0 -> {
                        context.vibrateRestTimerCue(500L)
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
        persistRestTime(timer, SystemClock.elapsedRealtime() - timer.startedAtElapsedMillis)
    }

    private suspend fun persistRestTime(timer: ActiveRestTimer, elapsedMillis: Long) {
        if (activeRestTimer == timer) {
            activeRestTimer = null
            restTimerRemainingSeconds = 0
        }
        repository.updateWorkoutSetRestSeconds(
            workoutSetId = timer.workoutSetId,
            elapsedSeconds = (elapsedMillis / 1_000L).toInt(),
        )
        restTimerCompletionKey++
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        activeRestTimer = null
        restTimerRemainingSeconds = 0
    }
}

@Composable
internal fun rememberGymAppState(): GymAppState {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    return remember(context, repository, scope) {
        GymAppState(context, repository, scope)
    }
}

private fun Context.vibrateRestTimerCue(durationMillis: Long) {
    val vibrator = getSystemService(Vibrator::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMillis)
    }
}
