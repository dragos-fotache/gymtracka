package com.dragosfotache.gymtracka.ui.workouts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutHistorySummary
import com.dragosfotache.gymtracka.data.gym.WorkoutHistoryStateRepository
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.ui.util.bestSetText
import com.dragosfotache.gymtracka.ui.util.formatWorkoutDuration
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class WorkoutHistoryState(
    private val repository: WorkoutHistoryStateRepository,
    private val scope: CoroutineScope,
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

    init {
        load()
    }

    fun requestDelete(workout: WorkoutEntity) {
        workoutToDelete = workout
    }

    fun dismissDelete() {
        workoutToDelete = null
    }

    fun confirmDelete() {
        val workout = workoutToDelete ?: return
        scope.launch {
            repository.deleteWorkout(workout.id)
            workoutToDelete = null
            workouts = repository.finishedWorkoutSummaries()
        }
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

    fun confirmSaveAsRoutine() {
        if (isSavingRoutine) return
        val workout = workoutToSaveAsRoutine ?: return
        val name = routineName.trim()
        if (name.isEmpty()) return
        isSavingRoutine = true
        saveRoutineError = null
        scope.launch {
            try {
                val savedRoutine = repository.saveWorkoutAsRoutine(workout.id, name)
                if (savedRoutine == null) {
                    saveRoutineError = "This workout is no longer available to save."
                } else {
                    workoutToSaveAsRoutine = null
                    routineName = ""
                    savedRoutineName = name
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                saveRoutineError = "Couldn't save the routine. Try again."
            } finally {
                isSavingRoutine = false
            }
        }
    }

    fun dismissSavedRoutineConfirmation() {
        savedRoutineName = null
    }

    private fun load() {
        scope.launch { workouts = repository.finishedWorkoutSummaries() }
    }
}

@Composable
private fun rememberWorkoutHistoryState(): WorkoutHistoryState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    return remember(repository, scope) { WorkoutHistoryState(repository, scope) }
}

@Composable
internal fun WorkoutHistoryScreen(
    modifier: Modifier = Modifier,
    onWorkoutSelected: (String) -> Unit,
    onPerformAgain: (String) -> Unit,
    state: WorkoutHistoryState = rememberWorkoutHistoryState(),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("History", style = MaterialTheme.typography.titleLarge) }
        when (val workouts = state.workouts) {
            null -> item { CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp)) }
            else -> items(workouts, key = { it.workout.id }) { workout ->
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

    state.workoutToDelete?.let { workout ->
        AlertDialog(
            onDismissRequest = state::dismissDelete,
            title = { Text("Delete workout?") },
            text = { Text("This will permanently delete ${workout.name} and its sets.") },
            confirmButton = {
                TextButton(onClick = state::confirmDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissDelete) { Text("Cancel") }
            },
        )
    }

    state.workoutToSaveAsRoutine?.let {
        AlertDialog(
            onDismissRequest = state::dismissSaveAsRoutine,
            title = { Text("Save as routine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
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
            confirmButton = {
                TextButton(
                    onClick = state::confirmSaveAsRoutine,
                    enabled = state.routineName.isNotBlank() && !state.isSavingRoutine,
                ) {
                    if (state.isSavingRoutine) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Save")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = state::dismissSaveAsRoutine,
                    enabled = !state.isSavingRoutine,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    state.savedRoutineName?.let { name ->
        AlertDialog(
            onDismissRequest = state::dismissSavedRoutineConfirmation,
            title = { Text("Routine saved") },
            text = { Text("$name was saved as a routine.") },
            confirmButton = {
                TextButton(onClick = state::dismissSavedRoutineConfirmation) { Text("Done") }
            },
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
    val workout = details.workout
    var showMenu by remember { mutableStateOf(false) }
    val finishedAtMillis = workout.finishedAtMillis ?: workout.startedAtMillis
    val dateText = remember(finishedAtMillis) {
        SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
            .format(Date(finishedAtMillis))
    }
    val durationText = formatWorkoutDuration(finishedAtMillis - workout.startedAtMillis)

    Card(
        modifier = Modifier.fillMaxWidth().testTag("history-workout-${workout.id}").clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    workout.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Workout options for ${workout.name}",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.width(152.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Save as routine") },
                            onClick = {
                                showMenu = false
                                onSaveAsRoutine()
                            },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        )
                        DropdownMenuItem(
                            text = { Text("Perform again") },
                            onClick = {
                                showMenu = false
                                onPerformAgain()
                            },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDeleteRequested()
                            },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        )
                    }
                }
            }
            Text(
                dateText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Sets",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Best set",
                    modifier = Modifier.weight(0.48f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            details.exercises.forEach { exercise ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "${exercise.setCount} × ${exercise.exerciseName}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        exercise.bestSetText(),
                        modifier = Modifier.weight(0.48f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryMetric(Icons.Filled.Schedule, durationText)
                HistoryMetric(Icons.Filled.FitnessCenter, details.totalVolumeKg())
            }
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
