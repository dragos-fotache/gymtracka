package com.dragosfotache.gymtracka.ui.workouts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutHistoryStateRepository
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.CancellationException
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.ui.history.HistorySetRow
import com.dragosfotache.gymtracka.ui.history.HistorySetTable
import com.dragosfotache.gymtracka.ui.history.historySetColumnWidths
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutActionButton
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutScreenStyle
import com.dragosfotache.gymtracka.ui.util.formatKilograms
import com.dragosfotache.gymtracka.ui.util.formatRecordedRestDuration
import com.dragosfotache.gymtracka.ui.util.formatWorkoutDuration
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class WorkoutHistoryDetailState(
    workoutId: String,
    repository: WorkoutHistoryStateRepository,
    scope: CoroutineScope,
) {
    var details by mutableStateOf<WorkoutDetails?>(null)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    private var loadGeneration = 0

    init {
        // Pure read-only state: no finite drain is needed. Lists and selected
        // details refresh independently; deleting the selected root yields null
        // details, never a fabricated object or navigation change. Each epoch
        // synchronously advances the generation and launches a guarded load
        // outside the collector, so a blocked old load never stalls newer
        // epochs or revocation.
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect {
                    val generation = ++loadGeneration
                    scope.launch {
                        if (generation != loadGeneration || !scope.isActive) return@launch
                        try {
                            val loaded = repository.workoutDetails(workoutId)
                            if (generation != loadGeneration || !scope.isActive) return@launch
                            details = loaded
                            refreshFailed = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (stale: StaleSessionException) {
                            if (generation != loadGeneration || !scope.isActive) return@launch
                            loadGeneration++
                            details = null
                            refreshFailed = true
                        } catch (_: Exception) {
                            if (generation != loadGeneration || !scope.isActive) return@launch
                            refreshFailed = true
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (!scope.isActive) return@launch
                loadGeneration++
                details = null
                refreshFailed = true
            } catch (_: Exception) {
                if (!scope.isActive) return@launch
                loadGeneration++
                refreshFailed = true
            }
        }
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
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        val workoutDetails = state.details
        val setsByExercise = workoutDetails?.exercises.orEmpty().associate { exercise ->
            exercise.workoutExercise.id to exercise.sets.mapIndexed { index, set ->
                HistorySetRow(
                    id = set.id,
                    weight = set.weightKg?.let { "${formatKilograms(it)} kg" } ?: "—",
                    repetitions = set.repetitions?.toString() ?: "—",
                    rest = if (index == exercise.sets.lastIndex) null else {
                        set.restSeconds?.let(::formatRecordedRestDuration) ?: "—"
                    },
                )
            }
        }
        val columnWidths = historySetColumnWidths(setsByExercise.values.flatten(), showRest = true)
        Scaffold(
            modifier = modifier.testTag("workout-summary-screen"),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = grid.space(24))) {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = grid.space(23)).heightIn(min = grid.line),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(grid.line)) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to workouts",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(grid.space(24)).offset(x = -grid.space(3)),
                            )
                        }
                        WorkoutGridLabel(
                            "Workout summary", Modifier.weight(1f),
                            fontWeight = FontWeight.Bold, horizontalPadding = 0,
                        )
                    }
                }
            },
            bottomBar = {
                if (workoutDetails != null) {
                    Box(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = grid.space(23), vertical = grid.space(12)),
                    ) {
                        WorkoutActionButton(
                            text = "Perform again",
                            onClick = { onPerformAgain(workoutDetails.workout.id) },
                            modifier = Modifier.fillMaxWidth().testTag("workout-summary-perform"),
                        )
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("workout-summary-list"),
                contentPadding = PaddingValues(horizontal = grid.space(23), vertical = grid.space(24)),
                verticalArrangement = Arrangement.spacedBy(grid.space(24)),
            ) {
                if (workoutDetails == null) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                } else {
                    val workout = workoutDetails.workout
                    val finishedAtMillis = workout.finishedAtMillis ?: workout.startedAtMillis
                    val date = Date(finishedAtMillis)
                    item {
                        Column {
                            WorkoutGridLabel(workout.name, fontWeight = FontWeight.Bold, horizontalPadding = 0)
                            WorkoutGridLabel(
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(date),
                                color = WorkoutMuted, horizontalPadding = 0, compact = true,
                            )
                            WorkoutGridLabel(
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(date),
                                color = WorkoutMuted, horizontalPadding = 0, compact = true,
                            )
                        }
                    }
                    items(workoutDetails.exercises, key = { it.workoutExercise.id }) { exercise ->
                        WorkoutHistoryExerciseDetails(
                            exercise, setsByExercise.getValue(exercise.workoutExercise.id), columnWidths,
                        )
                    }
                    item {
                        Column {
                            WorkoutGridLabel(
                                "Duration: ${formatWorkoutDuration(finishedAtMillis - workout.startedAtMillis)}",
                                color = WorkoutMuted, horizontalPadding = 0, compact = true,
                            )
                            WorkoutGridLabel(
                                "Volume: ${workoutDetails.totalVolumeKg()}",
                                color = WorkoutMuted, horizontalPadding = 0, compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutHistoryExerciseDetails(
    exercise: WorkoutExerciseDetails,
    sets: List<HistorySetRow>,
    columnWidths: List<Dp>,
) {
    val grid = LocalWorkoutGrid.current!!
    Column(
        Modifier.fillMaxWidth().testTag("workout-summary-exercise-${exercise.workoutExercise.id}")
            .border(grid.space(2), MaterialTheme.colorScheme.outline)
            .padding(vertical = grid.space(12)),
    ) {
        WorkoutGridLabel(exercise.workoutExercise.exerciseName, fontWeight = FontWeight.SemiBold, compact = true)
        exercise.workoutExercise.notes.takeIf { it.isNotBlank() }?.let { notes ->
            WorkoutGridLabel(notes, color = WorkoutMuted, compact = true)
        }
        Spacer(Modifier.height(grid.space(24)))
        HistorySetTable(
            sets = sets,
            rowTagPrefix = "workout-summary-set",
            columnWidths = columnWidths,
            showRest = true,
        )
    }
}
