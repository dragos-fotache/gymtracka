package com.dragosfotache.gymtracka.ui.exercises

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.dragosfotache.gymtracka.data.gym.ExerciseHistory
import com.dragosfotache.gymtracka.data.gym.ExerciseHistoryStateRepository
import com.dragosfotache.gymtracka.data.gym.ExerciseHistoryWorkout
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.ui.history.HistorySetRow
import com.dragosfotache.gymtracka.ui.history.HistorySetTable
import com.dragosfotache.gymtracka.ui.history.historySetColumnWidths
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutScreenStyle
import com.dragosfotache.gymtracka.ui.util.formatKilograms
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ExerciseHistoryState(
    private val exerciseId: String,
    private val repository: ExerciseHistoryStateRepository,
    private val scope: CoroutineScope,
) {
    var history by mutableStateOf<ExerciseHistory?>(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    private var loadGeneration = 0

    init {
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect { refresh() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!scope.isActive) return@launch
                loadGeneration++
                if (error is StaleSessionException) history = null
                isLoading = false
                refreshFailed = true
            }
        }
    }

    fun refresh() {
        val generation = ++loadGeneration
        isLoading = true
        scope.launch {
            // History is read-only; an unrelated pending edit need not delay browsing it.
            try {
                val loaded = repository.exerciseHistory(exerciseId)
                if (generation != loadGeneration || !scope.isActive) return@launch
                history = loaded
                refreshFailed = false
                isLoading = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != loadGeneration || !scope.isActive) return@launch
                if (error is StaleSessionException) {
                    loadGeneration++
                    history = null
                }
                refreshFailed = true
                isLoading = false
            }
        }
    }
}

@Composable
private fun rememberExerciseHistoryState(exerciseId: String): ExerciseHistoryState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    return remember(exerciseId, repository, scope) { ExerciseHistoryState(exerciseId, repository, scope) }
}

@Composable
internal fun ExerciseHistoryScreen(
    exerciseId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    state: ExerciseHistoryState = rememberExerciseHistoryState(exerciseId),
) {
    BackHandler(onBack = onBack)
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        val setsByWorkout = state.history?.workouts.orEmpty().associate { workout ->
            workout.workoutId to workout.sets.map { set ->
                HistorySetRow(
                    id = set.id,
                    weight = set.weightKg?.let { "${formatKilograms(it)} kg" } ?: "—",
                    repetitions = set.repetitions?.toString() ?: "—",
                )
            }
        }
        val columnWidths = historySetColumnWidths(setsByWorkout.values.flatten())
        Scaffold(
            modifier = modifier.testTag("exercise-history-screen"),
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
                                contentDescription = "Back to workout",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(grid.space(24)).offset(x = -grid.space(3)),
                            )
                        }
                        WorkoutGridLabel("Exercise history", fontWeight = FontWeight.Bold, horizontalPadding = 0)
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = grid.space(23), vertical = grid.space(24)),
                verticalArrangement = Arrangement.spacedBy(grid.space(24)),
            ) {
                if (state.refreshFailed) {
                    item {
                        Text("Couldn't load exercise history.")
                        WorkoutGridLabel(
                            "[Retry]",
                            modifier = Modifier.clickable(role = Role.Button, onClick = state::refresh),
                            color = MaterialTheme.colorScheme.primary,
                            horizontalPadding = 0,
                        )
                    }
                }
                val history = state.history
                if (history != null) {
                    item {
                        WorkoutGridLabel(history.exerciseName, fontWeight = FontWeight.Bold, horizontalPadding = 0)
                    }
                    if (history.workouts.isEmpty()) {
                        item { WorkoutGridLabel("No completed workouts for this exercise yet.", horizontalPadding = 0) }
                    }
                    items(history.workouts, key = { it.workoutId }) { workout ->
                        ExerciseHistoryPanel(workout, setsByWorkout.getValue(workout.workoutId), columnWidths)
                    }
                } else if (state.isLoading) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                } else if (!state.refreshFailed) {
                    item { WorkoutGridLabel("Exercise is no longer available.", horizontalPadding = 0) }
                }
            }
        }
    }
}

@Composable
private fun ExerciseHistoryPanel(
    workout: ExerciseHistoryWorkout,
    sets: List<HistorySetRow>,
    columnWidths: List<Dp>,
) {
    val grid = LocalWorkoutGrid.current!!
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(workout.performedAtMillis))
    Column(
        Modifier.fillMaxWidth().testTag("exercise-history-workout-${workout.workoutId}")
            .border(grid.space(2), MaterialTheme.colorScheme.outline)
            .padding(vertical = grid.space(12)),
    ) {
        WorkoutGridLabel(workout.workoutName, fontWeight = FontWeight.SemiBold, compact = true)
        WorkoutGridLabel(date, color = WorkoutMuted, compact = true)
        workout.notes.forEach { WorkoutGridLabel(it, color = WorkoutMuted, compact = true) }
        Spacer(Modifier.height(grid.space(24)))
        HistorySetTable(
            sets = sets,
            rowTagPrefix = "exercise-history-set",
            columnWidths = columnWidths,
        )
    }
}
