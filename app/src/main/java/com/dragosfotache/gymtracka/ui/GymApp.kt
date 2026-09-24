package com.dragosfotache.gymtracka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutPill
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutScreen
import com.dragosfotache.gymtracka.ui.exercises.ExercisePickerScreen
import com.dragosfotache.gymtracka.ui.exercises.ExercisesScreen
import com.dragosfotache.gymtracka.ui.util.formatElapsedTime
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryDetailScreen
import com.dragosfotache.gymtracka.ui.workouts.WorkoutScreen

@Composable
internal fun GymApp(appState: GymAppState = rememberGymAppState()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (
                appState.destination == Destination.Exercises ||
                appState.destination == Destination.Workout
            ) {
                Box {
                    NavigationBar(modifier = Modifier.height(88.dp)) {
                        NavigationBarItem(
                            selected = appState.destination == Destination.Exercises,
                            onClick = appState::showExercises,
                            modifier = Modifier.offset(y = 4.dp),
                            icon = {
                                Icon(
                                    Icons.Filled.FitnessCenter,
                                    contentDescription = null,
                                )
                            },
                            label = { Text("Exercises") },
                        )
                        NavigationBarItem(
                            selected = appState.destination == Destination.Workout,
                            onClick = appState::showWorkouts,
                            modifier = Modifier.offset(y = 4.dp),
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.DirectionsRun,
                                    contentDescription = null,
                                )
                            },
                            label = { Text("Workouts") },
                        )
                    }
                    appState.activeWorkout?.let { workout ->
                        ActiveWorkoutPill(
                            workout = workout,
                            restTimerRemainingSeconds = appState.activeRestTimer?.let {
                                appState.restTimerRemainingSeconds
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = (-88).dp),
                            onClick = appState::resumeWorkout,
                            onDiscard = appState::requestDiscardWorkout,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        when (appState.destination) {
            Destination.Exercises -> ExercisesScreen(Modifier.padding(innerPadding))
            Destination.Workout -> WorkoutScreen(
                modifier = Modifier.padding(innerPadding),
                onWorkoutSelected = appState::showWorkoutHistory,
                onStartEmptyWorkout = appState::requestStartWorkout,
            )
            Destination.ActiveWorkout -> appState.activeWorkout?.let { workout ->
                ActiveWorkoutScreen(
                    workoutId = workout.id,
                    workoutStartMillis = workout.startedAtMillis,
                    activeRestTimer = appState.activeRestTimer,
                    restTimerRemainingSeconds = appState.restTimerRemainingSeconds,
                    restTimerCompletionKey = appState.restTimerCompletionKey,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onAddExercise = appState::showExercisePicker,
                    onCollapse = appState::showWorkouts,
                    onWorkoutNameChanged = appState::updateActiveWorkoutName,
                    onFinishWorkout = appState::requestFinishWorkout,
                    onCancelWorkout = appState::requestDiscardWorkout,
                    onRestTimerStarted = appState::startRestTimer,
                    onRestTimerEnded = appState::skipRestTimer,
                    onRestTimerAdjusted = appState::adjustRestTimer,
                )
            }
            Destination.AddExercises -> appState.activeWorkout?.let { workout ->
                ExercisePickerScreen(
                    workoutId = workout.id,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onExercisesAdded = appState::resumeWorkout,
                    onCancel = appState::resumeWorkout,
                )
            }
            Destination.WorkoutHistoryDetail -> appState.selectedHistoryWorkoutId?.let { workoutId ->
                WorkoutHistoryDetailScreen(
                    workoutId = workoutId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onBack = appState::closeWorkoutHistory,
                )
            }
        }
    }

    GymAppDialogs(appState)
}

@Composable
private fun GymAppDialogs(appState: GymAppState) {
    if (appState.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = appState::dismissDiscardDialog,
            text = { Text("Are you sure you want to discard the current workout?") },
            confirmButton = {
                TextButton(onClick = appState::confirmDiscardWorkout) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = appState::dismissDiscardDialog) { Text("Cancel") }
            },
        )
    }

    if (appState.showFinishDialog) {
        AlertDialog(
            onDismissRequest = appState::dismissFinishDialog,
            text = { Text("Finish workout? Unfinished sets will be discarded.") },
            confirmButton = {
                TextButton(onClick = appState::confirmFinishWorkout) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = appState::dismissFinishDialog) { Text("Cancel") }
            },
        )
    }

    if (appState.showNoCompletedSetsDialog) {
        AlertDialog(
            onDismissRequest = appState::dismissNoCompletedSetsDialog,
            text = { Text("Complete at least one set before finishing this workout.") },
            confirmButton = {
                TextButton(onClick = appState::dismissNoCompletedSetsDialog) { Text("OK") }
            },
        )
    }

    if (appState.showStartNewWorkoutDialog) {
        AlertDialog(
            onDismissRequest = appState::dismissStartNewWorkoutDialog,
            text = { Text("Discard the current workout and start a new one?") },
            confirmButton = {
                TextButton(onClick = appState::confirmStartNewWorkout) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = appState::dismissStartNewWorkoutDialog) { Text("Cancel") }
            },
        )
    }

    appState.finishedWorkoutSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = appState::dismissFinishedWorkoutSummary,
            title = { Text("Workout complete") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary.name, style = MaterialTheme.typography.titleMedium)
                    Text("Duration: ${formatElapsedTime(summary.durationMillis / 1_000)}")
                    Text("Volume: ${summary.volumeKg}")
                }
            },
            confirmButton = {
                TextButton(onClick = appState::dismissFinishedWorkoutSummary) { Text("Done") }
            },
        )
    }
}
