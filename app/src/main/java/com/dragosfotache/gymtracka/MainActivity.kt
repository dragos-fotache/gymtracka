package com.dragosfotache.gymtracka

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.ExerciseSummary
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GymTrackaTheme {
                GymApp()
            }
        }
    }
}

private enum class Destination { Exercises, Workout, ActiveWorkout, AddExercises, WorkoutHistoryDetail }

private data class FinishedWorkoutSummary(
    val name: String,
    val durationMillis: Long,
    val volumeKg: String,
)

private data class ActiveRestTimer(
    val workoutSetId: String,
    val durationSeconds: Int,
    val startedAtElapsedMillis: Long,
)

@Composable
private fun GymApp() {
    var destination by remember { mutableStateOf(Destination.Workout) }
    var activeWorkout by remember { mutableStateOf<WorkoutEntity?>(null) }
    var selectedHistoryWorkoutId by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showNoCompletedSetsDialog by remember { mutableStateOf(false) }
    var showStartNewWorkoutDialog by remember { mutableStateOf(false) }
    var finishedWorkoutSummary by remember { mutableStateOf<FinishedWorkoutSummary?>(null) }
    var activeRestTimer by remember { mutableStateOf<ActiveRestTimer?>(null) }
    var restTimerRemainingSeconds by remember { mutableIntStateOf(0) }
    var restTimerCompletionKey by remember { mutableIntStateOf(0) }
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()

    val finishRestTimer: suspend (ActiveRestTimer, Long) -> Unit = { timer, elapsedMillis ->
        if (
            activeRestTimer?.workoutSetId == timer.workoutSetId &&
            activeRestTimer?.startedAtElapsedMillis == timer.startedAtElapsedMillis
        ) {
            activeRestTimer = null
            restTimerRemainingSeconds = 0
        }
        repository.updateWorkoutSetRestSeconds(
            workoutSetId = timer.workoutSetId,
            elapsedSeconds = (elapsedMillis / 1_000L).toInt(),
        )
        restTimerCompletionKey++
    }

    LaunchedEffect(activeRestTimer) {
        val timer = activeRestTimer ?: return@LaunchedEffect
        val signalledSeconds = mutableSetOf<Int>()
        while (true) {
            val elapsedMillis = SystemClock.elapsedRealtime() - timer.startedAtElapsedMillis
            val remainingMillis = (timer.durationSeconds * 1_000L - elapsedMillis).coerceAtLeast(0L)
            restTimerRemainingSeconds = ((remainingMillis + 999L) / 1_000L).toInt()

            when (restTimerRemainingSeconds) {
                2, 1 -> if (signalledSeconds.add(restTimerRemainingSeconds)) {
                    context.vibrateRestTimerCue(durationMillis = 100L)
                }

                0 -> {
                    context.vibrateRestTimerCue(durationMillis = 500L)
                    finishRestTimer(timer, elapsedMillis)
                    break
                }
            }
            delay(100L)
        }
    }

    LaunchedEffect(repository) {
        activeWorkout = repository.activeWorkout()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (destination == Destination.Exercises || destination == Destination.Workout) {
                Box {
                    NavigationBar(modifier = Modifier.height(88.dp)) {
                        NavigationBarItem(
                            selected = destination == Destination.Exercises,
                            onClick = { destination = Destination.Exercises },
                            modifier = Modifier.offset(y= (4).dp),
                            icon = {
                                Icon(
                       Icons.Filled.FitnessCenter,
                                    contentDescription = null,
                                    modifier = Modifier.padding(top = 0.dp)
                                )
                            },
                            label = { Text("Exercises") },
                        )
                        NavigationBarItem(
                            selected = destination == Destination.Workout,
                            onClick = { destination = Destination.Workout },
                            modifier = Modifier.offset(y= (4).dp),
                            icon = {
                                Icon(
                       Icons.AutoMirrored.Filled.DirectionsRun,
                                    contentDescription = null,
                                    modifier = Modifier.padding(top = 0.dp)
                                )
                            },
                            label = { Text("Workouts") },
                        )
                    }
                    activeWorkout?.let { workout ->
                        ActiveWorkoutPill(
                            workout = workout,
                            restTimerRemainingSeconds = activeRestTimer?.let {
                                restTimerRemainingSeconds
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = (-88).dp),
                            onClick = { destination = Destination.ActiveWorkout },
                            onDiscard = { showDiscardDialog = true },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        when (destination) {
            Destination.Exercises -> ExercisesScreen(Modifier.padding(innerPadding))
            Destination.Workout -> WorkoutScreen(
                modifier = Modifier.padding(innerPadding),
                onWorkoutSelected = { workoutId ->
                    selectedHistoryWorkoutId = workoutId
                    destination = Destination.WorkoutHistoryDetail
                },
                onStartEmptyWorkout = {
                    if (activeWorkout == null) {
                        scope.launch {
                            val workout = repository.startEmptyWorkout()
                            activeWorkout = workout
                            destination = Destination.ActiveWorkout
                        }
                    } else {
                        showStartNewWorkoutDialog = true
                    }
                },
            )
            Destination.ActiveWorkout -> activeWorkout?.let { workout ->
                ActiveWorkoutScreen(
                    workoutId = workout.id,
                    workoutStartMillis = workout.startedAtMillis,
                    activeRestTimer = activeRestTimer,
                    restTimerRemainingSeconds = restTimerRemainingSeconds,
                    restTimerCompletionKey = restTimerCompletionKey,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onAddExercise = { destination = Destination.AddExercises },
                    onCollapse = { destination = Destination.Workout },
                    onWorkoutNameChanged = { name -> activeWorkout = workout.copy(name = name) },
                    onFinishWorkout = {
                        scope.launch {
                            if (repository.hasCompletedSets(workout.id)) {
                                showFinishDialog = true
                            } else {
                                showNoCompletedSetsDialog = true
                            }
                        }
                    },
                    onCancelWorkout = { showDiscardDialog = true },
                    onRestTimerStarted = { workoutSet, durationSeconds ->
                        activeRestTimer?.let { previousTimer ->
                            scope.launch {
                                finishRestTimer(
                                    previousTimer,
                                    SystemClock.elapsedRealtime() - previousTimer.startedAtElapsedMillis,
                                )
                            }
                        }
                        activeRestTimer = ActiveRestTimer(
                            workoutSetId = workoutSet.id,
                            durationSeconds = durationSeconds,
                            startedAtElapsedMillis = SystemClock.elapsedRealtime(),
                        )
                        restTimerRemainingSeconds = durationSeconds
                    },
                    onRestTimerEnded = { timer, elapsedMillis ->
                        scope.launch { finishRestTimer(timer, elapsedMillis) }
                    },
                    onRestTimerAdjusted = { timer, adjustmentSeconds ->
                        val adjustedTimer = timer.copy(
                            durationSeconds = (timer.durationSeconds + adjustmentSeconds).coerceAtLeast(0),
                        )
                        activeRestTimer = adjustedTimer
                        val elapsedMillis = SystemClock.elapsedRealtime() - adjustedTimer.startedAtElapsedMillis
                        restTimerRemainingSeconds = (
                            (adjustedTimer.durationSeconds * 1_000L - elapsedMillis).coerceAtLeast(0L) + 999L
                        ).div(1_000L).toInt()
                    },
                )
            }
            Destination.AddExercises -> activeWorkout?.let { workout ->
                ExercisePickerScreen(
                    workoutId = workout.id,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onExercisesAdded = { destination = Destination.ActiveWorkout },
                    onCancel = { destination = Destination.ActiveWorkout },
                )
            }
            Destination.WorkoutHistoryDetail -> selectedHistoryWorkoutId?.let { workoutId ->
                WorkoutHistoryDetailScreen(
                    workoutId = workoutId,
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    onBack = {
                        selectedHistoryWorkoutId = null
                        destination = Destination.Workout
                    },
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            text = { Text("Are you sure you want to discard the current workout?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeWorkout?.let { workout ->
                            scope.launch {
                                repository.discardWorkout(workout.id)
                                activeWorkout = null
                                destination = Destination.Workout
                                showDiscardDialog = false
                            }
                        }
                    },
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showFinishDialog) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            text = { Text("Finish workout? Unfinished sets will be discarded.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeWorkout?.let { workout ->
                            scope.launch {
                                val finishedAtMillis = System.currentTimeMillis()
                                activeRestTimer?.let { timer ->
                                    finishRestTimer(
                                        timer,
                                        SystemClock.elapsedRealtime() - timer.startedAtElapsedMillis,
                                    )
                                }
                                if (repository.finishWorkout(workout.id, finishedAtMillis)) {
                                    val details = repository.workoutDetails(workout.id)
                                    finishedWorkoutSummary = FinishedWorkoutSummary(
                                        name = workout.name,
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
                    },
                ) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showNoCompletedSetsDialog) {
        AlertDialog(
            onDismissRequest = { showNoCompletedSetsDialog = false },
            text = { Text("Complete at least one set before finishing this workout.") },
            confirmButton = {
                TextButton(onClick = { showNoCompletedSetsDialog = false }) { Text("OK") }
            },
        )
    }

    if (showStartNewWorkoutDialog) {
        AlertDialog(
            onDismissRequest = { showStartNewWorkoutDialog = false },
            text = { Text("Discard the current workout and start a new one?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeWorkout?.let { currentWorkout ->
                            scope.launch {
                                repository.discardWorkout(currentWorkout.id)
                                val newWorkout = repository.startEmptyWorkout()
                                activeWorkout = newWorkout
                                destination = Destination.ActiveWorkout
                                showStartNewWorkoutDialog = false
                            }
                        }
                    },
                ) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showStartNewWorkoutDialog = false }) { Text("Cancel") }
            },
        )
    }

    finishedWorkoutSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { finishedWorkoutSummary = null },
            title = { Text("Workout complete") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary.name, style = MaterialTheme.typography.titleMedium)
                    Text("Duration: ${formatElapsedTime(summary.durationMillis / 1_000)}")
                    Text("Volume: ${summary.volumeKg}")
                }
            },
            confirmButton = {
                TextButton(onClick = { finishedWorkoutSummary = null }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun WorkoutScreen(
    modifier: Modifier = Modifier,
    onWorkoutSelected: (String) -> Unit,
    onStartEmptyWorkout: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    var historyReloadKey by remember { mutableIntStateOf(0) }
    var workoutToDelete by remember { mutableStateOf<WorkoutEntity?>(null) }
    val history by produceState<List<WorkoutDetails>?>(initialValue = null, context, historyReloadKey) {
        value = GymRepository(context).finishedWorkoutDetails()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Workouts",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        item {
            Button(
                onClick = onStartEmptyWorkout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start empty workout")
            }
        }
        when (val workouts = history) {
            null -> item {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
            else -> items(workouts, key = { it.workout.id }) { workout ->
                WorkoutHistoryCard(
                    details = workout,
                    onClick = { onWorkoutSelected(workout.workout.id) },
                    onDeleteRequested = { workoutToDelete = workout.workout },
                )
            }
        }
    }

    workoutToDelete?.let { workout ->
        AlertDialog(
            onDismissRequest = { workoutToDelete = null },
            title = { Text("Delete workout?") },
            text = { Text("This will permanently delete ${workout.name} and its sets.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deleteWorkout(workout.id)
                            workoutToDelete = null
                            historyReloadKey++
                        }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { workoutToDelete = null }) { Text("Cancel") }
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
        border = androidx.compose.foundation.BorderStroke(
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
                    text = workout.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(36.dp),
                    ) {
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
                            text = {
                                Text(
                                    "Delete",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
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
                            text = "${exercise.sets.size} × ${exercise.workoutExercise.exerciseName}",
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
                            text = exercise.bestSetText(),
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
private fun WorkoutHistoryDetailScreen(
    workoutId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current.applicationContext
    val details by produceState<WorkoutDetails?>(initialValue = null, workoutId) {
        value = GymRepository(context).workoutDetails(workoutId)
    }

    when (val workoutDetails = details) {
        null -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        else -> {
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
                            modifier = Modifier
                                .size(36.dp)
                                .offset(x = (-13).dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to workouts",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            text = workout.name,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                item {
                    Text(
                        text = dateText,
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
}

@Composable
private fun WorkoutHistoryExerciseDetails(exercise: WorkoutExerciseDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = exercise.workoutExercise.exerciseName,
            style = MaterialTheme.typography.titleMedium,
        )
        exercise.sets.forEachIndexed { index, set ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier.width(44.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = set.historySetText(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                set.restSeconds?.let { elapsedRestSeconds ->
                    Text(
                        text = "Rest: ${formatRecordedRestDuration(elapsedRestSeconds)}",
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
private fun HistoryMetric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ActiveWorkoutPill(
    workout: WorkoutEntity,
    restTimerRemainingSeconds: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDiscard: () -> Unit,
) {
    var elapsedSeconds by remember(workout.id, workout.startedAtMillis) {
        mutableStateOf((System.currentTimeMillis() - workout.startedAtMillis).coerceAtLeast(0L) / 1_000)
    }
    LaunchedEffect(workout.id, workout.startedAtMillis) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - workout.startedAtMillis) / 1_000
            delay(1_000)
        }
    }

    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(
                elevation = 24.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.42f),
                spotColor = Color.Black.copy(alpha = 0.5f),
            )
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = workout.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatElapsedTime(elapsedSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                restTimerRemainingSeconds?.let { remainingSeconds ->
                    Text(
                        text = "Rest ${formatCountdown(remainingSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onDiscard) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Discard current workout",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ActiveWorkoutScreen(
    workoutId: String,
    workoutStartMillis: Long,
    activeRestTimer: ActiveRestTimer?,
    restTimerRemainingSeconds: Int,
    restTimerCompletionKey: Int,
    modifier: Modifier = Modifier,
    onAddExercise: () -> Unit,
    onCollapse: () -> Unit,
    onWorkoutNameChanged: (String) -> Unit,
    onFinishWorkout: () -> Unit,
    onCancelWorkout: () -> Unit,
    onRestTimerStarted: (WorkoutSetEntity, Int) -> Unit,
    onRestTimerEnded: (ActiveRestTimer, Long) -> Unit,
    onRestTimerAdjusted: (ActiveRestTimer, Int) -> Unit,
) {
    var elapsedSeconds by remember(workoutStartMillis) {
        mutableStateOf((System.currentTimeMillis() - workoutStartMillis).coerceAtLeast(0L) / 1_000)
    }
    var reloadKey by remember { mutableStateOf(0) }
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val details by produceState<WorkoutDetails?>(
        initialValue = null,
        workoutId,
        reloadKey,
        restTimerCompletionKey,
    ) {
        value = repository.workoutDetails(workoutId)
    }

    LaunchedEffect(workoutStartMillis) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - workoutStartMillis) / 1_000
            delay(1_000)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            WorkoutTopBar(
                elapsedSeconds = elapsedSeconds,
                totalVolumeKg = details?.totalVolumeKg() ?: "0 kg",
                onCollapse = onCollapse,
                onFinish = onFinishWorkout,
            )
        },
        bottomBar = {
            activeRestTimer?.let { timer ->
                RestTimerBar(
                    timer = timer,
                    remainingSeconds = restTimerRemainingSeconds,
                    onSkip = {
                        onRestTimerEnded(
                            timer,
                            SystemClock.elapsedRealtime() - timer.startedAtElapsedMillis,
                        )
                    },
                    onDurationAdjusted = { adjustmentSeconds ->
                        onRestTimerAdjusted(timer, adjustmentSeconds)
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            details?.workout?.let { workout ->
                item {
                    WorkoutNameField(
                        workoutId = workout.id,
                        initialName = workout.name,
                        onNameChanged = onWorkoutNameChanged,
                    )
                }
            }
            details?.exercises?.let { exercises ->
                items(exercises, key = { it.workoutExercise.id }) { exercise ->
                    WorkoutExerciseCard(
                        exercise = exercise,
                        onSetAdded = { reloadKey++ },
                        onSetChanged = { reloadKey++ },
                        onExerciseDeleted = { reloadKey++ },
                        onRestStarted = onRestTimerStarted,
                    )
                }
            }
            item {
                TextButton(
                    onClick = onAddExercise,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add Exercise", modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                TextButton(onClick = onCancelWorkout, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Cancel workout",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutNameField(
    workoutId: String,
    initialName: String,
    onNameChanged: (String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    var name by remember(workoutId) { mutableStateOf(initialName) }

    BasicTextField(
        value = name,
        onValueChange = { value ->
            name = value
            onNameChanged(value)
            scope.launch { repository.updateWorkoutName(workoutId, value) }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WorkoutTopBar(
    elapsedSeconds: Long,
    totalVolumeKg: String,
    onCollapse: () -> Unit,
    onFinish: () -> Unit,
) {
    Surface(
        modifier = Modifier.statusBarsPadding(),
        shadowElevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier
                        .size(36.dp)
                        .offset(x = (-6).dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Collapse workout",
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = formatElapsedTime(elapsedSeconds),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                text = totalVolumeKg,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.Center),
            )
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                Button(onClick = onFinish) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun ExercisePickerScreen(
    workoutId: String,
    modifier: Modifier = Modifier,
    onExercisesAdded: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedEquipmentIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedMuscleGroupIds by remember { mutableStateOf(emptySet<String>()) }
    val summaries by produceState<List<ExerciseSummary>?>(initialValue = null, context) {
        value = repository.exerciseSummaries()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), shadowElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Add exercises",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 3.dp) {
                Button(
                    onClick = {
                        scope.launch {
                            repository.addExercisesToWorkout(workoutId, selectedIds)
                            onExercisesAdded()
                        }
                    },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text("Add selected (${selectedIds.size})")
                }
            }
        },
    ) { innerPadding ->
        when (val exercises = summaries) {
            null -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            else -> Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                ExerciseSearchField(
                    query = searchQuery,
                    onQueryChanged = { searchQuery = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ExerciseFilterControls(
                    exercises = exercises,
                    selectedEquipmentIds = selectedEquipmentIds,
                    onEquipmentIdsChanged = { selectedEquipmentIds = it },
                    selectedMuscleGroupIds = selectedMuscleGroupIds,
                    onMuscleGroupIdsChanged = { selectedMuscleGroupIds = it },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        exercises.filter {
                            it.matchesSearch(searchQuery) && it.matchesFilters(
                                equipmentIds = selectedEquipmentIds,
                                muscleGroupIds = selectedMuscleGroupIds,
                            )
                        },
                        key = { it.exercise.id },
                    ) { summary ->
                        ExerciseListItem(
                            summary = summary,
                            selected = summary.exercise.id in selectedIds,
                            onClick = {
                                selectedIds = if (summary.exercise.id in selectedIds) {
                                    selectedIds - summary.exercise.id
                                } else {
                                    selectedIds + summary.exercise.id
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseListItem(
    summary: ExerciseSummary,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val muscleGroups = muscleGroupText(summary)
    val equipment = summary.equipment.joinToString { it.name }.ifEmpty { "None" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.background,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = summary.exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(end = if (selected) 40.dp else 0.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF43A047)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Text(
                text = muscleGroups,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = summary.equipment.joinToString { it.name }.ifEmpty { "None" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkoutExerciseCard(
    exercise: WorkoutExerciseDetails,
    onSetAdded: () -> Unit,
    onSetChanged: () -> Unit,
    onExerciseDeleted: () -> Unit,
    onRestStarted: (WorkoutSetEntity, Int) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRestDurationDialog by remember { mutableStateOf(false) }
    var notes by remember(exercise.workoutExercise.id) {
        mutableStateOf(exercise.workoutExercise.notes)
    }
    var restSeconds by remember(exercise.workoutExercise.id) {
        mutableIntStateOf(30)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = exercise.workoutExercise.exerciseName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete ${exercise.workoutExercise.exerciseName}",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            BasicTextField(
                value = notes,
                onValueChange = { value ->
                    notes = value
                    scope.launch {
                        repository.updateWorkoutExerciseNotes(
                            workoutExerciseId = exercise.workoutExercise.id,
                            notes = value,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                minLines = 1,
                maxLines = Int.MAX_VALUE,
                decorationBox = { innerTextField ->
                    Box {
                        if (notes.isEmpty()) {
                            Text(
                                text = "Add notes...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            RestDurationRow(
                restSeconds = restSeconds,
                onClick = { showRestDurationDialog = true },
            )
            SetTableHeader()
            exercise.sets.forEach { workoutSet ->
                WorkoutSetRow(
                    workoutSet = workoutSet,
                    restDurationSeconds = restSeconds,
                    onSetChanged = onSetChanged,
                    onRestStarted = onRestStarted,
                )
            }
            val previousSet = exercise.sets.lastOrNull()
            TextButton(
                onClick = {
                    scope.launch {
                        repository.addSetToWorkoutExercise(
                            workoutExerciseId = exercise.workoutExercise.id,
                            position = exercise.sets.size,
                            repetitions = previousSet?.repetitions,
                            weightKg = previousSet?.weightKg,
                        )
                        onSetAdded()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add Set", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete exercise?") },
            text = { Text("Remove ${exercise.workoutExercise.exerciseName} and all of its sets from this workout?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deleteExerciseFromWorkout(
                                workoutExerciseId = exercise.workoutExercise.id,
                                workoutId = exercise.workoutExercise.workoutId,
                            )
                            showDeleteDialog = false
                            onExerciseDeleted()
                        }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRestDurationDialog) {
        RestDurationDialog(
            initialRestSeconds = restSeconds,
            onDismiss = { showRestDurationDialog = false },
            onDone = { selectedRestSeconds ->
                restSeconds = selectedRestSeconds
                showRestDurationDialog = false
            },
        )
    }
}

@Composable
private fun RestDurationRow(
    restSeconds: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = "Rest Timer: ${formatRestDuration(restSeconds)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun RestDurationDialog(
    initialRestSeconds: Int,
    onDismiss: () -> Unit,
    onDone: (Int) -> Unit,
) {
    var selectedMinutes by remember(initialRestSeconds) {
        mutableIntStateOf((initialRestSeconds / 60).coerceIn(0, 9))
    }
    var selectedSeconds by remember(initialRestSeconds) {
        mutableIntStateOf(((initialRestSeconds % 60) / 5 * 5).coerceIn(0, 55))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rest timer", style = MaterialTheme.typography.titleMedium) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                RestDurationPickerColumn(
                    label = "Minutes",
                    values = (0..9).toList(),
                    selectedValue = selectedMinutes,
                    onValueSelected = { selectedMinutes = it },
                    modifier = Modifier.width(84.dp),
                )
                RestDurationPickerColumn(
                    label = "Seconds",
                    values = (0..55 step 5).toList(),
                    selectedValue = selectedSeconds,
                    onValueSelected = { selectedSeconds = it },
                    modifier = Modifier.width(84.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDone(selectedMinutes * 60 + selectedSeconds) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun RestDurationPickerColumn(
    label: String,
    values: List<Int>,
    selectedValue: Int,
    onValueSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = values.indexOf(selectedValue).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    var centeredIndex by remember { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.centeredItemIndex() }.collect { index ->
            if (index != null && index != centeredIndex) {
                centeredIndex = index
                onValueSelected(values[index])
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            listState.centeredItemIndex()?.let { index ->
                centeredIndex = index
                onValueSelected(values[index])
                listState.animateScrollToItem(index)
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 40.dp),
            ) {
                items(values, key = { it }) { value ->
                    val isSelected = values[centeredIndex] == value
                    Text(
                        text = value.toString(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun LazyListState.centeredItemIndex(): Int? {
    val layoutInfo = layoutInfo
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return layoutInfo.visibleItemsInfo.minByOrNull { item ->
        abs((item.offset + item.size / 2) - viewportCenter)
    }?.index
}

private fun formatRestDuration(totalSeconds: Int): String {
    if (totalSeconds == 0) return "off"

    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return buildList {
        if (minutes > 0) add("${minutes}min")
        add("${seconds}s")
    }.joinToString(" ")
}

private fun formatRecordedRestDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return buildList {
        if (minutes > 0) add("${minutes}min")
        add("${seconds}s")
    }.joinToString(" ")
}

@Composable
private fun RestTimerBar(
    timer: ActiveRestTimer,
    remainingSeconds: Int,
    onSkip: () -> Unit,
    onDurationAdjusted: (Int) -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(
                            (remainingSeconds.toFloat() / timer.durationSeconds.coerceAtLeast(1))
                                .coerceIn(0f, 1f),
                        )
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RestTimerAdjustmentButton(
                        text = "−15",
                        onClick = { onDurationAdjusted(-15) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = formatCountdown(remainingSeconds),
                            style = MaterialTheme.typography.displaySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    RestTimerAdjustmentButton(
                        text = "+15",
                        onClick = { onDurationAdjusted(15) },
                    )
                    Spacer(Modifier.width(24.dp))
                    Button(
                        onClick = {
                            onSkip()
                        },
                    ) {
                        Text("Skip")
                    }
                }
            }
        }
    }
}

@Composable
private fun RestTimerAdjustmentButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(56.dp)
            .height(44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

private fun Context.vibrateRestTimerCue(durationMillis: Long) {
    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMillis)
    }
}

private fun formatCountdown(totalSeconds: Int): String =
    "%02d:%02d".format(Locale.getDefault(), totalSeconds / 60, totalSeconds % 60)

@Composable
private fun SetTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SET",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(0.5f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "PREVIOUS",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(0.75f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "WEIGHT",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "REPS",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.width(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = "Completed")
        }
    }
}

@Composable
private fun WorkoutSetRow(
    workoutSet: WorkoutSetEntity,
    restDurationSeconds: Int,
    onSetChanged: () -> Unit,
    onRestStarted: (WorkoutSetEntity, Int) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    var weightText by remember(workoutSet.id) {
        mutableStateOf(workoutSet.weightKg?.let(::formatKilograms).orEmpty())
    }
    var repetitionsText by remember(workoutSet.id) { mutableStateOf(workoutSet.repetitions?.toString().orEmpty()) }
    var completed by remember(workoutSet.id) { mutableStateOf(workoutSet.isCompleted) }
    var rowWidth by remember(workoutSet.id) { mutableIntStateOf(0) }
    var dragOffset by remember(workoutSet.id) { mutableFloatStateOf(0f) }
    var passedDeleteThreshold by remember(workoutSet.id) { mutableStateOf(false) }
    val swipeOffset = remember(workoutSet.id) { Animatable(0f) }
    val canComplete = weightText.toDoubleOrNull() != null && repetitionsText.toIntOrNull() != null
    val isCheckEnabled = canComplete || completed
    val completedRowColor = Color(0xFFE1F3E5)
    val completedCheckColor = Color(0xFF2E7D32)
    val rowShape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.error)
            .onSizeChanged { rowWidth = it.width },
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = "Delete set",
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = swipeOffset.value }
                .pointerInput(workoutSet.id, rowWidth) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragOffset = swipeOffset.value
                            passedDeleteThreshold = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragAmount < 0f || dragOffset < 0f) {
                                change.consume()
                                dragOffset = (dragOffset + dragAmount).coerceIn(-rowWidth.toFloat(), 0f)
                                val hasPassedThreshold = rowWidth > 0 && -dragOffset >= rowWidth / 2f
                                if (hasPassedThreshold && !passedDeleteThreshold) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                passedDeleteThreshold = hasPassedThreshold
                                scope.launch { swipeOffset.snapTo(dragOffset) }
                            }
                        },
                        onDragEnd = {
                            val shouldDelete = passedDeleteThreshold
                            scope.launch {
                                swipeOffset.animateTo(
                                    targetValue = if (shouldDelete) -rowWidth.toFloat() else 0f,
                                    animationSpec = spring(),
                                )
                                if (shouldDelete) {
                                    repository.deleteWorkoutSet(workoutSet)
                                    onSetChanged()
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { swipeOffset.animateTo(0f, spring()) }
                        },
                    )
                }
                .clip(rowShape)
                .background(if (completed) completedRowColor else MaterialTheme.colorScheme.background)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(0.5f).size(36.dp),
                color = if (completed) completedRowColor else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${workoutSet.position + 1}", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "–",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.75f),
            )
            Spacer(Modifier.width(8.dp))
            CompactSetTextField(
                value = weightText,
                onValueChange = { value ->
                    weightText = value
                    scope.launch {
                        repository.updateWorkoutSet(workoutSet.copy(weightKg = value.toDoubleOrNull()))
                        onSetChanged()
                    }
            },
            modifier = Modifier.weight(1f),
            showBorder = !completed,
            keyboardType = KeyboardType.Decimal,
        )
            Spacer(Modifier.width(8.dp))
            CompactSetTextField(
                value = repetitionsText,
                onValueChange = { value ->
                    repetitionsText = value
                    scope.launch {
                        repository.updateWorkoutSet(workoutSet.copy(repetitions = value.toIntOrNull()))
                        onSetChanged()
                    }
            },
            modifier = Modifier.weight(1f),
            showBorder = !completed,
            keyboardType = KeyboardType.Number,
        )
            Spacer(Modifier.width(8.dp))
            val checkCellShape = RoundedCornerShape(12.dp)
            Box(
            modifier = Modifier
                .size(36.dp)
                .clip(checkCellShape)
                .background(
                    when {
                        completed -> completedCheckColor
                        isCheckEnabled -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                )
                .clickable(
                    enabled = isCheckEnabled,
                    onClick = {
                    focusManager.clearFocus()
                    val isNowCompleted = !completed
                    completed = isNowCompleted
                    val updatedSet = workoutSet.copy(
                        isCompleted = isNowCompleted,
                        restSeconds = if (isNowCompleted) null else workoutSet.restSeconds,
                    )
                    scope.launch {
                        repository.updateWorkoutSet(updatedSet)
                        if (isNowCompleted && restDurationSeconds > 0) {
                            onRestStarted(updatedSet, restDurationSeconds)
                        }
                        onSetChanged()
                    }
                    },
                ),
            contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Complete set",
                    tint = when {
                        completed -> Color.White
                        isCheckEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactSetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showBorder: Boolean,
    keyboardType: KeyboardType,
) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        modifier = modifier
            .height(36.dp)
            .then(
                if (showBorder) Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape,
                ) else Modifier,
            )
            .padding(horizontal = 8.dp),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { innerTextField() }
        },
    )
}

private fun WorkoutDetails.totalVolumeKg(): String {
    val volume = exercises.sumOf { exercise ->
        exercise.sets.sumOf { set -> (set.weightKg ?: 0.0) * (set.repetitions ?: 0) }
    }
    return if (volume % 1.0 == 0.0) "${volume.toInt()} kg" else "${"%.2f".format(volume)} kg"
}

private fun WorkoutExerciseDetails.bestSetText(): String {
    val bestSet = sets.maxByOrNull { set ->
        (set.weightKg ?: 0.0) * (set.repetitions ?: 0)
    } ?: return "—"
    val weight = bestSet.weightKg ?: return "—"
    val repetitions = bestSet.repetitions ?: return "—"
    return "${formatKilograms(weight)} kg × $repetitions"
}

private fun WorkoutSetEntity.historySetText(): String {
    val weight = weightKg ?: return "—"
    val repetitions = repetitions ?: return "—"
    return "${formatKilograms(weight)} kg × $repetitions"
}

private fun formatKilograms(weightKg: Double): String =
    if (weightKg % 1.0 == 0.0) weightKg.toInt().toString() else "%.2f".format(weightKg)

private fun formatWorkoutDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis.coerceAtLeast(0) / 60_000).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h ${minutes}m" else "${minutes}m"
}

private fun muscleGroupText(summary: ExerciseSummary): String {
    val primary = summary.muscleGroups
        .filter { it.relationshipType == "primary" }
        .joinToString { it.name }
    val secondary = summary.muscleGroups
        .filter { it.relationshipType == "secondary" }
        .joinToString { it.name }
    return listOf(primary, secondary)
        .filter { it.isNotEmpty() }
        .joinToString(" • ")
        .ifEmpty { "None" }
}

private fun ExerciseSummary.matchesSearch(query: String): Boolean {
    val searchTerm = query.trim()
    return searchTerm.isEmpty() || listOf(
        exercise.name,
        muscleGroups.joinToString { it.name },
        equipment.joinToString { it.name },
    ).any { it.contains(searchTerm, ignoreCase = true) }
}

private fun ExerciseSummary.matchesFilters(
    equipmentIds: Set<String>,
    muscleGroupIds: Set<String>,
): Boolean =
    (equipmentIds.isEmpty() || equipment.any { it.id in equipmentIds }) &&
        (muscleGroupIds.isEmpty() || muscleGroups.any { it.id in muscleGroupIds })

@Composable
private fun ExerciseSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF1F2F5))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChanged,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search exercises",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE4E6EB))
                    .clickable { onQueryChanged("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private data class ExerciseFilterOption(
    val id: String,
    val name: String,
)

@Composable
private fun ExerciseFilterControls(
    exercises: List<ExerciseSummary>,
    selectedEquipmentIds: Set<String>,
    onEquipmentIdsChanged: (Set<String>) -> Unit,
    selectedMuscleGroupIds: Set<String>,
    onMuscleGroupIdsChanged: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEquipmentDialog by remember { mutableStateOf(false) }
    var showMuscleGroupDialog by remember { mutableStateOf(false) }
    val equipmentOptions = exercises
        .flatMap { it.equipment }
        .map { ExerciseFilterOption(it.id, it.name) }
        .distinctBy { it.id }
        .sortedBy { it.name }
    val muscleGroupOptions = exercises
        .flatMap { it.muscleGroups }
        .map { ExerciseFilterOption(it.id, it.name) }
        .distinctBy { it.id }
        .sortedBy { it.name }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExerciseFilterButton(
            text = filterButtonText(
                selectedIds = selectedEquipmentIds,
                options = equipmentOptions,
                allLabel = "All Equipment",
                selectedLabel = "Equipment",
            ),
            selected = selectedEquipmentIds.isNotEmpty(),
            onClick = { showEquipmentDialog = true },
            onClear = if (selectedEquipmentIds.isNotEmpty()) {
                { onEquipmentIdsChanged(emptySet()) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        ExerciseFilterButton(
            text = filterButtonText(
                selectedIds = selectedMuscleGroupIds,
                options = muscleGroupOptions,
                allLabel = "All Muscles",
                selectedLabel = "Muscles",
            ),
            selected = selectedMuscleGroupIds.isNotEmpty(),
            onClick = { showMuscleGroupDialog = true },
            onClear = if (selectedMuscleGroupIds.isNotEmpty()) {
                { onMuscleGroupIdsChanged(emptySet()) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
    }

    if (showEquipmentDialog) {
        ExerciseFilterDialog(
            title = "Equipment",
            options = equipmentOptions,
            selectedIds = selectedEquipmentIds,
            onDismiss = { showEquipmentDialog = false },
            onApply = {
                onEquipmentIdsChanged(it)
                showEquipmentDialog = false
            },
        )
    }
    if (showMuscleGroupDialog) {
        ExerciseFilterDialog(
            title = "Muscle groups",
            options = muscleGroupOptions,
            selectedIds = selectedMuscleGroupIds,
            onDismiss = { showMuscleGroupDialog = false },
            onApply = {
                onMuscleGroupIdsChanged(it)
                showMuscleGroupDialog = false
            },
        )
    }
}

@Composable
private fun ExerciseFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFFE8F5E9) else Color(0xFFF1F2F5),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        onClear?.let { clear ->
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD5EBD9))
                    .clickable(onClick = clear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear filter",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun filterButtonText(
    selectedIds: Set<String>,
    options: List<ExerciseFilterOption>,
    allLabel: String,
    selectedLabel: String,
): String {
    val selectedNames = options.filter { it.id in selectedIds }.map { it.name }
    return when {
        selectedNames.isEmpty() -> allLabel
        selectedNames.size <= 2 -> selectedNames.joinToString(", ")
        else -> "$selectedLabel (${selectedNames.size})"
    }
}

@Composable
private fun ExerciseFilterDialog(
    title: String,
    options: List<ExerciseFilterOption>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    var pendingSelectedIds by remember(selectedIds) { mutableStateOf(selectedIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(options, key = { it.id }) { option ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                pendingSelectedIds = if (option.id in pendingSelectedIds) {
                                    pendingSelectedIds - option.id
                                } else {
                                    pendingSelectedIds + option.id
                                }
                            },
                        color = if (option.id in pendingSelectedIds) {
                            Color(0xFFE8F5E9)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(pendingSelectedIds) }) { Text("Show results") }
        },
        dismissButton = {
            TextButton(onClick = { pendingSelectedIds = emptySet() }) { Text("Clear filters") }
        },
        containerColor = Color(0xFFECEFF1),
    )
}

private fun formatElapsedTime(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun ExercisesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var searchQuery by remember { mutableStateOf("") }
    var selectedEquipmentIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedMuscleGroupIds by remember { mutableStateOf(emptySet<String>()) }
    val summaries by produceState<List<ExerciseSummary>?>(initialValue = null, context) {
        value = GymRepository(context).exerciseSummaries()
    }

    when (val exercises = summaries) {
        null -> Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        else -> Column(modifier = modifier.fillMaxSize()) {
            ExerciseSearchField(
                query = searchQuery,
                onQueryChanged = { searchQuery = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ExerciseFilterControls(
                exercises = exercises,
                selectedEquipmentIds = selectedEquipmentIds,
                onEquipmentIdsChanged = { selectedEquipmentIds = it },
                selectedMuscleGroupIds = selectedMuscleGroupIds,
                onMuscleGroupIdsChanged = { selectedMuscleGroupIds = it },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    exercises.filter {
                        it.matchesSearch(searchQuery) && it.matchesFilters(
                            equipmentIds = selectedEquipmentIds,
                            muscleGroupIds = selectedMuscleGroupIds,
                        )
                    },
                    key = { it.exercise.id },
                ) { summary ->
                    ExerciseListItem(summary)
                }
            }
        }
    }
}
