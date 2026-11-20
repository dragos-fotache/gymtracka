package com.dragosfotache.gymtracka.ui.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutHistorySummary
import com.dragosfotache.gymtracka.data.gym.WorkoutHistoryStateRepository
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.ui.FiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.rememberFiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutInlineActions
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutScreenStyle
import com.dragosfotache.gymtracka.ui.theme.WorkoutTextAction
import com.dragosfotache.gymtracka.ui.util.bestSetText
import com.dragosfotache.gymtracka.ui.util.formatWorkoutDuration
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class WorkoutHistoryState(
    private val repository: WorkoutHistoryStateRepository,
    private val scope: CoroutineScope,
    private val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
) {
    var workouts by mutableStateOf<List<WorkoutHistorySummary>?>(null)
        private set
    var workoutToDelete by mutableStateOf<WorkoutEntity?>(null)
        private set
    var workoutToSaveAsRoutine by mutableStateOf<WorkoutEntity?>(null)
        private set
    var routineName by mutableStateOf("")
        private set
    var savedRoutineName by mutableStateOf<String?>(null)
        private set
    var isSavingRoutine by mutableStateOf(false)
        private set
    var saveRoutineError by mutableStateOf<String?>(null)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    private var loadGeneration = 0

    init {
        // Single observation-driven load path; the initial epoch triggers the
        // first load. Observer/load jobs stay outside finite edit registrations.
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect { loadHistory() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (!scope.isActive) return@launch
                loadGeneration++
                workouts = null
                workoutToDelete = null
                workoutToSaveAsRoutine = null
                refreshFailed = true
            } catch (_: Exception) {
                // Ordinary stream failures invalidate outstanding loads first so
                // an in-flight success can neither install old data nor erase
                // the newest failure diagnostic.
                if (!scope.isActive) return@launch
                loadGeneration++
                refreshFailed = true
            }
        }
    }

    fun requestDelete(workout: WorkoutEntity) {
        workoutToDelete = workout
    }

    fun dismissDelete() {
        workoutToDelete = null
    }

    fun confirmDelete(): Job? {
        val workout = workoutToDelete ?: return null
        val workoutId = workout.id
        val job = edits.launchEdit(
            "history-delete:$workoutId",
            onAccepted = { loadGeneration++ },
        ) {
            repository.deleteWorkout(workoutId)
            workoutToDelete = null
        }
        if (job != null) {
            // Fresh guarded read after the owned write: join only the owned job
            // so the refresh observes the commit without waiting for unrelated
            // finite work. The load is unregistered, so this is not self-join.
            // This keeps local-only (single-emission) observers from missing
            // their own post-write refresh.
            scope.launch {
                try {
                    job.join()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@launch
                }
                if (!scope.isActive) return@launch
                loadHistory()
            }
        }
        return job
    }

    fun requestSaveAsRoutine(workout: WorkoutEntity) {
        if (isSavingRoutine) return
        workoutToSaveAsRoutine = workout
        routineName = workout.name
        saveRoutineError = null
    }

    fun updateRoutineName(name: String) {
        routineName = name
        saveRoutineError = null
    }

    fun dismissSaveAsRoutine() {
        if (isSavingRoutine) return
        workoutToSaveAsRoutine = null
        routineName = ""
        saveRoutineError = null
    }

    fun confirmSaveAsRoutine(): Job? {
        if (isSavingRoutine) return null
        val workout = workoutToSaveAsRoutine ?: return null
        val name = routineName.trim()
        if (name.isEmpty()) return null
        val workoutId = workout.id
        val job = edits.launchEdit(
            key = "history-save-routine:$workoutId",
            onAccepted = {
                // Success/error UI cleanup runs only for accepted edits, so a refused callback
                // can never leave isSavingRoutine stuck. Outstanding loads are
                // invalidated synchronously; refusal changes nothing.
                loadGeneration++
                isSavingRoutine = true
                saveRoutineError = null
            },
        ) {
            try {
                val savedRoutine = repository.saveWorkoutAsRoutine(workoutId, name)
                if (savedRoutine == null) {
                    saveRoutineError = "This workout is no longer available to save."
                } else {
                    workoutToSaveAsRoutine = null
                    routineName = ""
                    savedRoutineName = name
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Safe error UI first, then rethrow the origin so the coordinator retains it
                // instead of reporting false success.
                saveRoutineError = "Couldn't save the routine. Try again."
                throw error
            } finally {
                isSavingRoutine = false
            }
        }
        if (job != null) loadHistory()
        return job
    }

    fun dismissSavedRoutineConfirmation() {
        savedRoutineName = null
    }

    private fun loadHistory() {
        val generation = ++loadGeneration
        scope.launch {
            // Guarded pure scoped read without global drains: history models
            // carry no autosaved field drafts and dialog drafts live outside
            // the loaded model, so initial and list reads stay usable while
            // unrelated finite writes are pending.
            if (generation != loadGeneration || !scope.isActive) return@launch
            try {
                val loaded = repository.finishedWorkoutSummaries()
                if (generation != loadGeneration || !scope.isActive) return@launch
                workouts = loaded
                refreshFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (generation != loadGeneration || !scope.isActive) return@launch
                loadGeneration++
                workouts = null
                workoutToDelete = null
                workoutToSaveAsRoutine = null
                refreshFailed = true
            } catch (_: Exception) {
                if (generation != loadGeneration || !scope.isActive) return@launch
                refreshFailed = true
            }
        }
    }
}

@Composable
private fun rememberWorkoutHistoryState(): WorkoutHistoryState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    val edits = rememberFiniteEditCoordinator()
    return remember(repository, scope, edits) { WorkoutHistoryState(repository, scope, edits) }
}

@Composable
internal fun WorkoutHistoryScreen(
    modifier: Modifier = Modifier,
    onWorkoutSelected: (String) -> Unit,
    onPerformAgain: (String) -> Unit,
    state: WorkoutHistoryState = rememberWorkoutHistoryState(),
) {
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        Column(modifier.fillMaxSize().padding(top = grid.space(24))) {
            WorkoutGridLabel(
                "History",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                fontWeight = FontWeight.Bold,
                horizontalPadding = 23,
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("workout-history-list"),
                contentPadding = PaddingValues(
                    start = grid.space(23), end = grid.space(23),
                    top = grid.space(24), bottom = grid.space(24),
                ),
                verticalArrangement = Arrangement.spacedBy(grid.space(24)),
            ) {
                when (val workouts = state.workouts) {
                    null -> item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    else -> {
                        if (workouts.isEmpty()) {
                            item { WorkoutGridLabel("No finished workouts yet.", horizontalPadding = 0) }
                        }
                        items(workouts, key = { it.workout.id }) { workout ->
                            WorkoutHistoryCard(
                                details = workout,
                                onClick = { onWorkoutSelected(workout.workout.id) },
                                onSaveAsRoutine = { state.requestSaveAsRoutine(workout.workout) },
                                onPerformAgain = { onPerformAgain(workout.workout.id) },
                                onDeleteRequested = { state.requestDelete(workout.workout) },
                            )
                        }
                    }
                }
            }
        }
        WorkoutHistoryDialogs(state)
    }
}

@Composable
private fun WorkoutHistoryDialogs(state: WorkoutHistoryState) {
    state.workoutToDelete?.let { workout ->
        GymDialog(
            onDismissRequest = state::dismissDelete,
            title = "Delete workout?",
            text = { Text("This will permanently delete ${workout.name} and its sets.") },
            confirmAction = GymDialogAction(
                text = "Delete",
                onClick = state::confirmDelete,
                destructive = true,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = state::dismissDelete,
            ),
        )
    }

    state.workoutToSaveAsRoutine?.let {
        GymDialog(
            onDismissRequest = state::dismissSaveAsRoutine,
            title = "Save as routine",
            text = {
                val grid = LocalWorkoutGrid.current!!
                Column(verticalArrangement = Arrangement.spacedBy(grid.space(12))) {
                    Text(
                        "Routine name",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BasicTextField(
                        value = state.routineName,
                        onValueChange = state::updateRoutineName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("history-routine-name-input")
                            .border(grid.space(2), MaterialTheme.colorScheme.outline)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(grid.space(12)),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    state.saveRoutineError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmAction = GymDialogAction(
                text = "Save",
                onClick = state::confirmSaveAsRoutine,
                enabled = state.routineName.isNotBlank() && !state.isSavingRoutine,
                showProgress = state.isSavingRoutine,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = state::dismissSaveAsRoutine,
                enabled = !state.isSavingRoutine,
            ),
        )
    }

    state.savedRoutineName?.let { name ->
        GymDialog(
            onDismissRequest = state::dismissSavedRoutineConfirmation,
            title = "Routine saved",
            text = { Text("$name was saved as a routine.") },
            confirmAction = GymDialogAction(
                text = "Done",
                onClick = state::dismissSavedRoutineConfirmation,
            ),
        )
    }
}

@Composable
private fun WorkoutHistoryCard(
    details: WorkoutHistorySummary,
    onClick: () -> Unit,
    onSaveAsRoutine: () -> Unit,
    onPerformAgain: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val grid = LocalWorkoutGrid.current!!
    val workout = details.workout
    var actionsExpanded by remember(workout.id) { mutableStateOf(false) }
    val finishedAtMillis = workout.finishedAtMillis ?: workout.startedAtMillis
    val date = Date(finishedAtMillis)
    val dateText = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
    val timeText = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    val durationText = formatWorkoutDuration(finishedAtMillis - workout.startedAtMillis)

    Column(
        Modifier.fillMaxWidth().testTag("history-workout-${workout.id}")
            .border(grid.space(2), MaterialTheme.colorScheme.outline)
            .clickable(role = Role.Button, onClickLabel = "Open workout details", onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WorkoutGridLabel(
                "[${workout.name}]",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = { actionsExpanded = !actionsExpanded },
                modifier = Modifier.width(grid.space(48)).heightIn(min = grid.line)
                    .testTag("history-options-${workout.id}")
                    .semantics { stateDescription = if (actionsExpanded) "Expanded" else "Collapsed" },
            ) {
                Icon(
                    if (actionsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Workout options for ${workout.name}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(grid.space(24)),
                )
            }
        }
        if (actionsExpanded) {
            WorkoutInlineActions(
                left = WorkoutTextAction(
                    text = "[Save]",
                    onClick = {
                        actionsExpanded = false
                        onSaveAsRoutine()
                    },
                    description = "Save ${workout.name} as routine",
                ),
                middle = WorkoutTextAction(
                    text = "[Perform]",
                    onClick = {
                        actionsExpanded = false
                        onPerformAgain()
                    },
                    description = "Perform ${workout.name} again",
                ),
                right = WorkoutTextAction(
                    text = "[Delete]",
                    onClick = {
                        actionsExpanded = false
                        onDeleteRequested()
                    },
                    description = "Delete workout ${workout.name}",
                    destructive = true,
                ),
                modifier = Modifier.padding(horizontal = grid.space(12)).testTag("history-actions-${workout.id}"),
            )
        }
        Column(Modifier.fillMaxWidth().padding(bottom = grid.space(12))) {
            WorkoutGridLabel(
                dateText, Modifier.testTag("history-date-${workout.id}"), color = WorkoutMuted, compact = true,
            )
            WorkoutGridLabel(
                timeText, Modifier.testTag("history-time-${workout.id}"), color = WorkoutMuted, compact = true,
            )
            Spacer(Modifier.height(grid.space(24)))
            details.exercises.forEachIndexed { index, exercise ->
                WorkoutGridLabel(
                    "${exercise.setCount} × ${exercise.exerciseName}",
                    modifier = Modifier.testTag("history-sets-${exercise.workoutExerciseId}"),
                    compact = true,
                )
                WorkoutGridLabel(
                    "Best set: ${exercise.bestSetText()}",
                    modifier = Modifier.testTag("history-best-set-${exercise.workoutExerciseId}"),
                    color = WorkoutMuted,
                    compact = true,
                )
                if (index < details.exercises.lastIndex) Spacer(Modifier.height(grid.space(12)))
            }
            Spacer(Modifier.height(grid.space(24)))
            WorkoutGridLabel("Duration: $durationText", color = WorkoutMuted, compact = true)
            WorkoutGridLabel("Volume: ${details.totalVolumeKg()}", color = WorkoutMuted, compact = true)
        }
    }
}

@Composable
internal fun HistoryMetric(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
