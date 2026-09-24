package com.dragosfotache.gymtracka.ui.workouts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.ui.util.bestSetText
import com.dragosfotache.gymtracka.ui.util.formatRecordedRestDuration
import com.dragosfotache.gymtracka.ui.util.formatWorkoutDuration
import com.dragosfotache.gymtracka.ui.util.historySetText
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class WorkoutHistoryState(
    private val repository: GymRepository,
    private val scope: CoroutineScope,
) {
    var workouts by mutableStateOf<List<WorkoutDetails>?>(null)
        private set
    var workoutToDelete by mutableStateOf<WorkoutEntity?>(null)
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
            workouts = repository.finishedWorkoutDetails()
        }
    }

    private fun load() {
        scope.launch { workouts = repository.finishedWorkoutDetails() }
    }
}

internal class WorkoutHistoryDetailState(
    workoutId: String,
    repository: GymRepository,
    scope: CoroutineScope,
) {
    var details by mutableStateOf<WorkoutDetails?>(null)
        private set

    init {
        scope.launch { details = repository.workoutDetails(workoutId) }
    }
}

@Composable
private fun rememberWorkoutHistoryState(): WorkoutHistoryState {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    return remember(repository, scope) { WorkoutHistoryState(repository, scope) }
}

@Composable
private fun rememberWorkoutHistoryDetailState(workoutId: String): WorkoutHistoryDetailState {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    return remember(workoutId, repository, scope) {
        WorkoutHistoryDetailState(workoutId, repository, scope)
    }
}

@Composable
internal fun WorkoutScreen(
    modifier: Modifier = Modifier,
    onWorkoutSelected: (String) -> Unit,
    onStartEmptyWorkout: () -> Unit,
    state: WorkoutHistoryState = rememberWorkoutHistoryState(),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Workouts", style = MaterialTheme.typography.titleLarge) }
        item {
            Button(onClick = onStartEmptyWorkout, modifier = Modifier.fillMaxWidth()) {
                Text("Start empty workout")
            }
        }
        when (val workouts = state.workouts) {
            null -> item { CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp)) }
            else -> items(workouts, key = { it.workout.id }) { workout ->
                WorkoutHistoryCard(
                    details = workout,
                    onClick = { onWorkoutSelected(workout.workout.id) },
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
}

@Composable
private fun WorkoutHistoryCard(
    details: WorkoutDetails,
    onClick: () -> Unit,
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                            contentDescription = "Workout options",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.width(128.dp),
                    ) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sets", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    details.exercises.forEach { exercise ->
                        Text(
                            "${exercise.sets.size} × ${exercise.workoutExercise.exerciseName}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(modifier = Modifier.weight(0.48f)) {
                    Text("Best set", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    details.exercises.forEach { exercise ->
                        Text(
                            exercise.bestSetText(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
internal fun WorkoutHistoryDetailScreen(
    workoutId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    state: WorkoutHistoryDetailState = rememberWorkoutHistoryDetailState(workoutId),
) {
    BackHandler(onBack = onBack)
    val workoutDetails = state.details
    if (workoutDetails == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val workout = workoutDetails.workout
    val finishedAtMillis = workout.finishedAtMillis ?: workout.startedAtMillis
    val dateText = remember(finishedAtMillis) {
        SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
            .format(Date(finishedAtMillis))
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 34.dp, end = 34.dp, top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp).offset(x = (-13).dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to workouts",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    workout.name,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        item {
            Text(
                dateText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(workoutDetails.exercises, key = { it.workoutExercise.id }) { exercise ->
            WorkoutHistoryExerciseDetails(exercise)
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryMetric(
                    Icons.Filled.Schedule,
                    formatWorkoutDuration(finishedAtMillis - workout.startedAtMillis),
                )
                HistoryMetric(Icons.Filled.FitnessCenter, workoutDetails.totalVolumeKg())
            }
        }
    }
}

@Composable
private fun WorkoutHistoryExerciseDetails(exercise: WorkoutExerciseDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(exercise.workoutExercise.exerciseName, style = MaterialTheme.typography.titleMedium)
        exercise.workoutExercise.notes
            .takeIf { it.isNotBlank() }
            ?.let { notes ->
                Text(
                    notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        exercise.sets.forEachIndexed { index, set ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (index + 1).toString(),
                    modifier = Modifier.width(44.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    set.historySetText(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                set.restSeconds?.let { elapsedRestSeconds ->
                    Text(
                        "Rest: ${formatRecordedRestDuration(elapsedRestSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryMetric(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
