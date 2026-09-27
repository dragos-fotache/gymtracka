package com.dragosfotache.gymtracka.ui.workouts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutHistoryStateRepository
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.ui.util.formatRecordedRestDuration
import com.dragosfotache.gymtracka.ui.util.formatWorkoutDuration
import com.dragosfotache.gymtracka.ui.util.historySetText
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class WorkoutHistoryDetailState(
    workoutId: String,
    repository: WorkoutHistoryStateRepository,
    scope: CoroutineScope,
) {
    var details by mutableStateOf<WorkoutDetails?>(null)
        private set

    init {
        scope.launch { details = repository.workoutDetails(workoutId) }
    }
}

@Composable
private fun rememberWorkoutHistoryDetailState(workoutId: String): WorkoutHistoryDetailState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    return remember(workoutId, repository, scope) {
        WorkoutHistoryDetailState(workoutId, repository, scope)
    }
}

@Composable
internal fun WorkoutHistoryDetailScreen(
    workoutId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPerformAgain: (String) -> Unit,
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
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(shadowElevation = 3.dp) {
                Button(
                    onClick = { onPerformAgain(workout.id) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text("Perform again")
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 34.dp, end = 34.dp, bottom = 32.dp),
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
