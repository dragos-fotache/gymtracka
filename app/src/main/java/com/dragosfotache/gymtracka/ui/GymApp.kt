package com.dragosfotache.gymtracka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import com.dragosfotache.gymtracka.ui.account.AccountStartupApp
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutPill
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutScreen
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.exercises.ExercisePickerScreen
import com.dragosfotache.gymtracka.ui.exercises.ExerciseHistoryScreen
import com.dragosfotache.gymtracka.ui.exercises.ExerciseReplacementPickerScreen
import com.dragosfotache.gymtracka.ui.exercises.ExercisesScreen
import com.dragosfotache.gymtracka.ui.exercises.ExerciseDetailScreen
import com.dragosfotache.gymtracka.ui.exercises.CreateExerciseScreen
import com.dragosfotache.gymtracka.ui.exercises.RoutineExercisePickerScreen
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorScreen
import com.dragosfotache.gymtracka.ui.routines.RoutineDetailScreen
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionState
import com.dragosfotache.gymtracka.ui.settings.SettingsScreen
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.util.formatElapsedTime
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryDetailScreen
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryScreen
import com.dragosfotache.gymtracka.ui.workouts.WorkoutScreen

@Composable
internal fun GymApp() {
    AccountStartupApp()
}

@Composable
internal fun GymApp(appState: GymAppState) {
    DisposableEffect(appState) {
        onDispose(appState::dispose)
    }
    // The same app-owned coordinator reaches every mutating production feature factory below;
    // standalone screens fall back to their local coordinator.
    CompositionLocalProvider(LocalFiniteEditCoordinator provides appState.edits) {
        GymAppContent(appState)
    }
}

@Composable
internal fun GymAppContent(
    appState: GymAppState,
    settingsAccountContent: (@Composable () -> Unit)? = null,
    serverConnectionState: ServerConnectionState? = null,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (
                appState.destination == Destination.Exercises ||
                appState.destination == Destination.Workout ||
                appState.destination == Destination.History ||
                appState.destination == Destination.Settings
            ) {
                Column {
                    appState.activeWorkout?.let { workout ->
                        ActiveWorkoutPill(
                            workout = workout,
                            restTimerRemainingSeconds = appState.activeRestTimer?.let {
                                appState.restTimerRemainingSeconds
                            },
                            onClick = appState::resumeWorkout,
                            onDiscard = appState::requestDiscardWorkout,
                        )
                    }
                    GymBottomNavigation(appState)
                }
            }
        },
    ) { innerPadding ->
        when (appState.destination) {
            Destination.Exercises -> ExercisesScreen(
                modifier = Modifier.padding(innerPadding),
                onExerciseSelected = appState::showExerciseDetails,
                onCreateExercise = appState::showCreateExercise,
                onEditCustomExercise = appState::editCustomExercise,
                initialFirstVisibleItemIndex = appState.exerciseListFirstVisibleItemIndex,
                initialFirstVisibleItemScrollOffset = appState.exerciseListFirstVisibleItemScrollOffset,
                onListPositionChanged = appState::updateExerciseListPosition,
                initialSearchQuery = appState.exerciseSearchQuery,
                initialEquipmentIds = appState.exerciseSelectedEquipmentIds,
                initialMuscleGroupIds = appState.exerciseSelectedMuscleGroupIds,
                onCatalogueStateChanged = appState::updateExerciseCatalogueState,
            )
            Destination.ExerciseDetail -> appState.selectedExerciseId?.let { exerciseId ->
                ExerciseDetailScreen(
                    exerciseId = exerciseId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onBack = appState::closeExerciseDetails,
                    onEdit = appState::editCustomExercise,
                    onDelete = appState::deleteCustomExercise,
                )
            }
            Destination.CreateExercise -> CreateExerciseScreen(
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                onBack = appState::closeCreateExercise,
                onSaved = appState::closeCreateExercise,
            )
            Destination.EditCustomExercise -> appState.editingCustomExerciseId?.let { exerciseId ->
                CreateExerciseScreen(
                    exerciseId = exerciseId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onBack = appState::closeEditCustomExercise,
                    onSaved = appState::closeEditCustomExercise,
                )
            }
            Destination.Workout -> WorkoutScreen(
                modifier = Modifier.padding(innerPadding),
                onStartEmptyWorkout = appState::requestStartWorkout,
                onNewRoutine = appState::requestNewRoutine,
                onRoutineSelected = appState::showRoutineDetails,
                onEditRoutine = appState::editRoutine,
                onStartRoutine = appState::requestStartRoutine,
            )
            Destination.History -> WorkoutHistoryScreen(
                modifier = Modifier.padding(innerPadding),
                onWorkoutSelected = appState::showWorkoutHistory,
                onPerformAgain = appState::requestPerformAgain,
            )
            Destination.Settings -> SettingsScreen(
                modifier = Modifier.padding(innerPadding),
                accountContent = settingsAccountContent,
                serverConnectionState = serverConnectionState,
            )
            Destination.Routine -> appState.editingRoutineId?.let { routineId ->
                RoutineEditorScreen(
                    routineId = routineId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onAddExercise = appState::showRoutineExercisePicker,
                    onCancel = appState::cancelRoutine,
                    onSaved = appState::finishRoutineEditing,
                )
            }
            Destination.RoutineDetail -> appState.selectedRoutineId?.let { routineId ->
                RoutineDetailScreen(
                    routineId = routineId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onBack = appState::closeRoutineDetails,
                    onStartRoutine = appState::requestStartRoutine,
                )
            }
            Destination.AddRoutineExercises -> appState.editingRoutineId?.let { routineId ->
                RoutineExercisePickerScreen(
                    routineId = routineId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onExercisesAdded = appState::closeRoutineExercisePicker,
                    onCancel = appState::closeRoutineExercisePicker,
                )
            }
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
                    // Accepted-edit timers start only from the already tracked successful
                    // set-completion callback; every other start path uses the default refusal.
                    // The composed selected parent binds the start: a stale callback from a
                    // replaced/finished root refuses inside startRestTimer.
                    onRestTimerStarted = { set, durationSeconds ->
                        appState.startRestTimer(
                            set,
                            durationSeconds,
                            acceptedEdit = true,
                            expectedWorkoutId = workout.id,
                        )
                    },
                    onRestTimerEnded = appState::skipRestTimer,
                    onRestTimerAdjusted = appState::adjustRestTimer,
                    onRestTimerCancelled = appState::cancelRestTimerForSet,
                    onExerciseReplacementRequested = appState::showExerciseReplacementPicker,
                    onExerciseHistoryRequested = appState::showExerciseHistory,
                    onRegisterFinishDrain = appState::registerFinishDrain,
                    onUnregisterFinishDrain = appState::unregisterFinishDrain,
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
            Destination.ReplaceExercise -> appState.replacingWorkoutExerciseId?.let { workoutExerciseId ->
                ExerciseReplacementPickerScreen(
                    workoutExerciseId = workoutExerciseId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onExerciseReplaced = appState::closeExerciseReplacementPicker,
                    onCancel = appState::closeExerciseReplacementPicker,
                )
            }
            Destination.WorkoutHistoryDetail -> appState.selectedHistoryWorkoutId?.let { workoutId ->
                WorkoutHistoryDetailScreen(
                    workoutId = workoutId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onBack = appState::closeWorkoutHistory,
                    onPerformAgain = appState::requestPerformAgain,
                )
            }
            Destination.ExerciseHistory -> appState.selectedHistoryExerciseId?.let { exerciseId ->
                ExerciseHistoryScreen(
                    exerciseId = exerciseId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onBack = appState::closeExerciseHistory,
                )
            }
        }
    }

    GymAppDialogs(appState)
}

@Composable
private fun GymAppDialogs(appState: GymAppState) {
    if (appState.showDiscardDialog) {
        GymDialog(
            onDismissRequest = appState::dismissDiscardDialog,
            text = { Text("Are you sure you want to discard the current workout?") },
            confirmAction = GymDialogAction(
                text = "Discard",
                onClick = appState::confirmDiscardWorkout,
                destructive = true,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = appState::dismissDiscardDialog,
            ),
        )
    }

    if (appState.showFinishDialog) {
        GymDialog(
            onDismissRequest = appState::dismissFinishDialog,
            text = { Text("Finish workout? Unfinished sets will be discarded.") },
            confirmAction = GymDialogAction(
                text = "Yes",
                onClick = appState::confirmFinishWorkout,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = appState::dismissFinishDialog,
            ),
        )
    }

    if (appState.showNoCompletedSetsDialog) {
        GymDialog(
            onDismissRequest = appState::dismissNoCompletedSetsDialog,
            text = { Text("Complete at least one set before finishing this workout.") },
            confirmAction = GymDialogAction(
                text = "OK",
                onClick = appState::dismissNoCompletedSetsDialog,
            ),
        )
    }

    if (appState.showStartNewWorkoutDialog) {
        GymDialog(
            onDismissRequest = appState::dismissStartNewWorkoutDialog,
            text = { Text("Discard the current workout and start a new one?") },
            confirmAction = GymDialogAction(
                text = "Yes",
                onClick = appState::confirmStartNewWorkout,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = appState::dismissStartNewWorkoutDialog,
            ),
        )
    }

    appState.finishedWorkoutSummary?.let { summary ->
        GymDialog(
            onDismissRequest = appState::dismissFinishedWorkoutSummary,
            title = "Workout complete",
            text = {
                val grid = LocalWorkoutGrid.current!!
                Column(verticalArrangement = Arrangement.spacedBy(grid.space(12))) {
                    Text(summary.name, style = MaterialTheme.typography.titleMedium)
                    Text("Duration: ${formatElapsedTime(summary.durationMillis / 1_000)}")
                    Text("Volume: ${summary.volumeKg}")
                }
            },
            confirmAction = GymDialogAction(
                text = "Done",
                onClick = appState::dismissFinishedWorkoutSummary,
            ),
        )
    }
}
