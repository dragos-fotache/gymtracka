package com.dragosfotache.gymtracka.ui.activeworkout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.ActiveWorkoutStateRepository
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.ui.ActiveRestTimer
import com.dragosfotache.gymtracka.ui.util.formatCountdown
import com.dragosfotache.gymtracka.ui.util.formatElapsedTime
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

internal class ActiveWorkoutState(
    private val workoutId: String,
    private val workoutStartMillis: Long,
    private val repository: ActiveWorkoutStateRepository,
    private val scope: CoroutineScope,
) {
    var details by mutableStateOf<WorkoutDetails?>(null)
        private set
    var elapsedSeconds by mutableLongStateOf(
        (System.currentTimeMillis() - workoutStartMillis).coerceAtLeast(0L) / 1_000,
    )
        private set
    private var refreshGeneration = 0
    private var nameSaveJob: Job? = null
    private var setEditSequence = 0L
    private val latestAcceptedSetEdit = mutableMapOf<String, Long>()
    private val pendingEditSignals = mutableListOf<CompletableDeferred<Unit>>()
    private data class RetainedEditFailure(val key: String, val error: Exception)
    private val retainedEditFailures = mutableListOf<RetainedEditFailure>()

    init {
        refresh()
        scope.launch {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - workoutStartMillis) / 1_000
                delay(1.seconds)
            }
        }
    }

    fun refresh() {
        val generation = ++refreshGeneration
        scope.launch {
            val loaded = repository.workoutDetails(workoutId)
            if (generation == refreshGeneration) details = loaded
        }
    }

    /**
     * Tracks one accepted finite mutation so finish can drain it. Failures are
     * recorded on the drain signal (a plain join would hide them) and retained
     * under the edit's identity key until an explicit retry or a superseding
     * edit for the same key succeeds — observing the failure does not resolve
     * the unsaved edit. Cancellation propagates. A job that never runs still
     * settles its signal so a drain cannot hang on it. The endless
     * elapsed-time ticker is never tracked.
     */
    private fun launchEdit(key: String, block: suspend () -> Unit): Job {
        val signal = CompletableDeferred<Unit>()
        pendingEditSignals += signal
        val job = scope.launch {
            try {
                block()
                signal.complete(Unit)
                pendingEditSignals -= signal
                retainedEditFailures.removeAll { it.key == key }
            } catch (error: CancellationException) {
                signal.cancel(error)
                pendingEditSignals -= signal
                throw error
            } catch (error: Exception) {
                signal.completeExceptionally(error)
                pendingEditSignals -= signal
                retainedEditFailures.removeAll { it.key == key }
                retainedEditFailures += RetainedEditFailure(key, error)
            }
        }
        job.invokeOnCompletion { error ->
            if (error != null && !signal.isCompleted) {
                signal.cancel(
                    error as? CancellationException
                        ?: CancellationException("Edit did not run", error),
                )
                pendingEditSignals -= signal
            }
        }
        return job
    }

    /**
     * Suspends until every accepted edit has committed. Throws when any drained
     * edit failed, when an unresolved retained failure exists, or when the
     * scope was cancelled, so finish never overtakes an accepted edit and
     * never runs while an accepted edit remains unsaved.
     */
    suspend fun awaitPendingEdits() {
        while (true) {
            val snapshot = pendingEditSignals.toList()
            if (snapshot.isEmpty()) {
                // A cancelled feature scope must fail the drain instead of
                // silently succeeding with no tracked edits left.
                scope.ensureActive()
                retainedEditFailures.firstOrNull()?.let { throw it.error }
                return
            }
            for (signal in snapshot) {
                try {
                    signal.await()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw error
                }
            }
        }
    }

    fun updateWorkoutName(name: String) {
        details = details?.let { current ->
            current.copy(workout = current.workout.copy(name = name))
        }
        val previousSave = nameSaveJob
        nameSaveJob = launchEdit("workout:name") {
            previousSave?.join()
            repository.updateWorkoutName(workoutId, name)
        }
    }

    fun updateNotes(workoutExerciseId: String, notes: String) {
        launchEdit("notes:$workoutExerciseId") {
            repository.updateWorkoutExerciseNotes(workoutExerciseId, notes)
        }
    }

    fun updateRestDuration(workoutExerciseId: String, restDurationSeconds: Int) {
        details = details?.let { current ->
            current.copy(
                exercises = current.exercises.map { exercise ->
                    if (exercise.workoutExercise.id == workoutExerciseId) {
                        exercise.copy(
                            workoutExercise = exercise.workoutExercise.copy(
                                restDurationSeconds = restDurationSeconds,
                            ),
                        )
                    } else {
                        exercise
                    }
                },
            )
        }
        launchEdit("rest:$workoutExerciseId") {
            repository.updateWorkoutExerciseRestDuration(workoutExerciseId, restDurationSeconds)
        }
    }

    fun addSet(exercise: WorkoutExerciseDetails) {
        val previousSet = exercise.sets.lastOrNull()
        launchEdit("sets:${exercise.workoutExercise.id}") {
            repository.addSetToWorkoutExercise(
                workoutExerciseId = exercise.workoutExercise.id,
                position = exercise.sets.size,
                repetitions = previousSet?.repetitions,
                weightKg = previousSet?.weightKg,
            )
            details = repository.workoutDetails(workoutId)
        }
    }

    fun updateSet(workoutSet: WorkoutSetEntity, afterUpdate: (() -> Unit)? = null) {
        // Newer accepted edits supersede older callbacks for the same set: an
        // older completion must not start a timer after a newer edit already
        // unchecked or invalidated that set.
        val sequence = ++setEditSequence
        latestAcceptedSetEdit[workoutSet.id] = sequence
        launchEdit("set:${workoutSet.id}") {
            // The fenced result reports a real incomplete-to-complete
            // transition; no-ops, guards, invalidations, and already-completed
            // edits never start a timer, even when the stored row reads
            // completed (finished roots, wrong parents, stale entities).
            val transitioned = repository.updateWorkoutSet(workoutSet)
            val loaded = try {
                repository.workoutDetails(workoutId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (loaded != null) details = loaded
            if (!transitioned) return@launchEdit
            if (latestAcceptedSetEdit[workoutSet.id] != sequence) return@launchEdit
            val storedExercise = loaded?.exercises?.singleOrNull {
                it.workoutExercise.id == workoutSet.workoutExerciseId
            }
            val stored = storedExercise?.sets?.singleOrNull { it.id == workoutSet.id }
            if (loaded?.workout?.status == "active" && stored?.isCompleted == true) {
                afterUpdate?.invoke()
            }
        }
    }

    fun deleteSet(workoutSet: WorkoutSetEntity) {
        launchEdit("set:${workoutSet.id}") {
            repository.deleteWorkoutSet(workoutSet)
            details = repository.workoutDetails(workoutId)
        }
    }

    fun deleteExercise(exercise: WorkoutExerciseDetails) {
        launchEdit("entry:${exercise.workoutExercise.id}") {
            repository.deleteExerciseFromWorkout(
                workoutExerciseId = exercise.workoutExercise.id,
                workoutId = exercise.workoutExercise.workoutId,
            )
            details = repository.workoutDetails(workoutId)
        }
    }
}

@Composable
private fun rememberActiveWorkoutState(
    workoutId: String,
    workoutStartMillis: Long,
): ActiveWorkoutState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    return remember(workoutId, workoutStartMillis, repository, scope) {
        ActiveWorkoutState(workoutId, workoutStartMillis, repository, scope)
    }
}

@Composable
internal fun ActiveWorkoutScreen(
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
    onRestTimerEnded: (ActiveRestTimer) -> Unit,
    onRestTimerAdjusted: (ActiveRestTimer, Int) -> Unit,
    onRestTimerCancelled: (String) -> Unit = {},
    onExerciseReplacementRequested: (String) -> Unit,
    onRegisterFinishDrain: (workoutId: String, owner: Any, drain: suspend () -> Unit) -> Unit = { _, _, _ -> },
    onUnregisterFinishDrain: (workoutId: String, owner: Any) -> Unit = { _, _ -> },
    state: ActiveWorkoutState = rememberActiveWorkoutState(workoutId, workoutStartMillis),
) {
    LaunchedEffect(restTimerCompletionKey) {
        state.refresh()
    }
    LaunchedEffect(workoutId, state) {
        onRegisterFinishDrain(workoutId, state, state::awaitPendingEdits)
        try {
            awaitCancellation()
        } finally {
            onUnregisterFinishDrain(workoutId, state)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            WorkoutTopBar(
                elapsedSeconds = state.elapsedSeconds,
                totalVolumeKg = state.details?.totalVolumeKg() ?: "0 kg",
                onCollapse = onCollapse,
                onFinish = onFinishWorkout,
            )
        },
        bottomBar = {
            activeRestTimer?.let { timer ->
                RestTimerBar(
                    timer = timer,
                    remainingSeconds = restTimerRemainingSeconds,
                    onSkip = { onRestTimerEnded(timer) },
                    onDurationAdjusted = { onRestTimerAdjusted(timer, it) },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.details?.workout?.let { workout ->
                item {
                    WorkoutNameField(
                        workoutId = workout.id,
                        initialName = workout.name,
                        onNameChanged = { name ->
                            state.updateWorkoutName(name)
                            onWorkoutNameChanged(name)
                        },
                    )
                }
            }
            state.details?.exercises?.let { exercises ->
                items(exercises, key = { it.workoutExercise.id }) { exercise ->
                    WorkoutExerciseCard(
                        exercise = exercise,
                        onNotesChanged = state::updateNotes,
                        onRestDurationChanged = state::updateRestDuration,
                        onSetAdded = { state.addSet(exercise) },
                        onSetChanged = state::updateSet,
                        onSetDeleted = state::deleteSet,
                        onExerciseDeleted = { state.deleteExercise(exercise) },
                        onExerciseReplacementRequested = onExerciseReplacementRequested,
                        onRestStarted = onRestTimerStarted,
                        onRestCancelled = onRestTimerCancelled,
                    )
                }
            }
            item {
                TextButton(
                    onClick = onAddExercise,
                    modifier = Modifier.padding(horizontal = 4.dp).fillMaxWidth(),
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
                    Text("Cancel workout", color = MaterialTheme.colorScheme.error)
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
    var name by remember(workoutId) { mutableStateOf(initialName) }
    BasicTextField(
        value = name,
        onValueChange = {
            name = it
            onNameChanged(it)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier.fillMaxWidth().testTag("workout-name-input"),
    )
}

@Composable
private fun WorkoutTopBar(
    elapsedSeconds: Long,
    totalVolumeKg: String,
    onCollapse: () -> Unit,
    onFinish: () -> Unit,
) {
    Surface(modifier = Modifier.statusBarsPadding(), shadowElevation = 3.dp) {
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
                    modifier = Modifier.size(36.dp).offset(x = (-6).dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Collapse workout",
                        modifier = Modifier.size(26.dp),
                    )
                }
                Text(
                    formatElapsedTime(elapsedSeconds),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                totalVolumeKg,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.Center),
            )
            Button(onClick = onFinish, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text("Finish")
            }
        }
    }
}

@Composable
internal fun ActiveWorkoutPill(
    workout: WorkoutEntity,
    restTimerRemainingSeconds: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDiscard: () -> Unit,
) {
    var elapsedSeconds by remember(workout.id, workout.startedAtMillis) {
        mutableLongStateOf((System.currentTimeMillis() - workout.startedAtMillis).coerceAtLeast(0L) / 1_000)
    }
    LaunchedEffect(workout.id, workout.startedAtMillis) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - workout.startedAtMillis) / 1_000
            delay(1.seconds)
        }
    }
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier
            .testTag("active-workout-pill")
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
                        workout.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatElapsedTime(elapsedSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                restTimerRemainingSeconds?.let {
                    Text(
                        "Rest ${formatCountdown(it)}",
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
