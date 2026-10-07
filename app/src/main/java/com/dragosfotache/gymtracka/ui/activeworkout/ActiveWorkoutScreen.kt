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
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.ui.util.reconcileStringField
import com.dragosfotache.gymtracka.ui.FiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberFiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.ui.ActiveRestTimer
import com.dragosfotache.gymtracka.ui.util.formatCountdown
import com.dragosfotache.gymtracka.ui.util.formatElapsedTime
import com.dragosfotache.gymtracka.ui.util.totalVolumeKg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

internal class ActiveWorkoutState(
    private val workoutId: String,
    private val workoutStartMillis: Long,
    private val repository: ActiveWorkoutStateRepository,
    private val scope: CoroutineScope,
    private val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
) {
    var details by mutableStateOf<WorkoutDetails?>(null)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    var elapsedSeconds by mutableLongStateOf(
        (System.currentTimeMillis() - workoutStartMillis).coerceAtLeast(0L) / 1_000,
    )
        private set
    private var refreshGeneration = 0
    private var nameSaveJob: Job? = null
    private var setEditSequence = 0L
    private val latestAcceptedSetEdit = mutableMapOf<String, Long>()

    init {
        // Single observation-driven load path; the initial epoch triggers the
        // first load. The observer and the endless elapsed-time ticker stay
        // outside finite edit registrations and are never drained.
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect { refresh() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (!scope.isActive) return@launch
                refreshGeneration++
                details = null
                refreshFailed = true
            } catch (_: Exception) {
                // Ordinary stream failures invalidate outstanding loads first so
                // an in-flight success can neither install old data nor erase
                // the newest failure diagnostic.
                if (!scope.isActive) return@launch
                refreshGeneration++
                refreshFailed = true
            }
        }
        scope.launch {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - workoutStartMillis) / 1_000
                delay(1.seconds)
            }
        }
    }

    /**
     * Observer/explicit/post-edit reload: waits for accepted finite work outside
     * tracked edits before reading, then rechecks generation and scope liveness.
     * Scheduled after an accepted write returns, the own write is already
     * registered, so the drain joins it without self-join; loads are
     * unregistered. A load started before a newer refresh or accepted edit never
     * installs afterward, including a cancellation-ignoring fake and never after
     * disposal. Accepted app-owned writes still survive disposal. Accepted
     * name/rest drafts survive because acceptances invalidate older loads
     * synchronously and loads drain first; a normal new publication then
     * replaces the persisted values. Never starts/ends timers or invokes
     * completion callbacks.
     */
    fun refresh() {
        val generation = ++refreshGeneration
        scope.launch {
            try {
                edits.awaitPendingEdits()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == refreshGeneration && scope.isActive) refreshFailed = true
                return@launch
            }
            if (generation != refreshGeneration || !scope.isActive) return@launch
            try {
                val loaded = repository.workoutDetails(workoutId)
                if (generation != refreshGeneration || !scope.isActive) return@launch
                details = loaded
                refreshFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (generation != refreshGeneration || !scope.isActive) return@launch
                refreshGeneration++
                details = null
                refreshFailed = true
            } catch (_: Exception) {
                if (generation != refreshGeneration || !scope.isActive) return@launch
                refreshFailed = true
            }
        }
    }

    /**
     * Suspends until every accepted edit has committed. Delegates to the shared app coordinator so
     * finish never overtakes an accepted edit from any feature; throws on drained or retained
     * failures and detects scope cancellation. The endless elapsed-time ticker is never tracked.
     */
    suspend fun awaitPendingEdits() {
        edits.awaitPendingEdits()
    }

    /**
     * Accepts one finite mutation through the shared coordinator. Returns the tracking job, or
     * null when refused (paused/disposed): refused edits change neither optimistic state (applied
     * in [onAccepted]) nor the set-sequence guard, and never reach the repository.
     */
    fun updateWorkoutName(name: String): Job? {
        val previousSave = nameSaveJob
        val job = edits.launchEdit(
            key = "workout:$workoutId:name",
            onAccepted = {
                // Invalidate outstanding loads synchronously so an old read can
                // never roll back the accepted draft. Refusal changes nothing.
                refreshGeneration++
                details = details?.let { current ->
                    current.copy(workout = current.workout.copy(name = name))
                }
            },
        ) {
            previousSave?.join()
            repository.updateWorkoutName(workoutId, name)
        }
        if (job != null) {
            nameSaveJob = job
            // Successful writes schedule a guarded refresh too: onAccepted may
            // have invalidated an in-flight epoch load carrying unrelated
            // publication labels, which must still be reloaded. The drain joins
            // only after this tracked write returns; loads are unregistered.
            refresh()
        }
        return job
    }

    fun updateNotes(workoutExerciseId: String, notes: String): Job? {
        val job = edits.launchEdit(
            "notes:$workoutExerciseId",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.updateWorkoutExerciseNotes(workoutExerciseId, notes)
        }
        if (job != null) refresh()
        return job
    }

    fun updateRestDuration(workoutExerciseId: String, restDurationSeconds: Int): Job? {
        val job = edits.launchEdit(
            key = "rest:$workoutExerciseId",
            onAccepted = {
                refreshGeneration++
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
            },
        ) {
            repository.updateWorkoutExerciseRestDuration(workoutExerciseId, restDurationSeconds)
        }
        if (job != null) refresh()
        return job
    }

    fun addSet(exercise: WorkoutExerciseDetails): Job? {
        val workoutExerciseId = exercise.workoutExercise.id
        val position = exercise.sets.size
        val previousSet = exercise.sets.lastOrNull()
        val previousRepetitions = previousSet?.repetitions
        val previousWeightKg = previousSet?.weightKg
        val job = edits.launchEdit(
            "sets:$workoutExerciseId",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.addSetToWorkoutExercise(
                workoutExerciseId = workoutExerciseId,
                position = position,
                repetitions = previousRepetitions,
                weightKg = previousWeightKg,
            )
        }
        if (job != null) refresh()
        return job
    }

    fun updateSet(workoutSet: WorkoutSetEntity, afterUpdate: (() -> Unit)? = null): Job? {
        val snapshot = workoutSet
        var sequence = 0L
        // Validity captured at acceptance: an older queued write must never mint
        // a newest install over a newer accepted draft.
        var acceptedGeneration = -1
        val job = edits.launchEdit(
            key = "set:${snapshot.id}",
            onAccepted = {
                // Newer accepted edits supersede older callbacks for the same set: an
                // older completion must not start a timer after a newer edit already
                // unchecked or invalidated that set. Refused edits never touch this guard.
                // Outstanding loads are invalidated synchronously alongside, and the
                // acceptance generation is captured for the in-write read below.
                refreshGeneration++
                acceptedGeneration = refreshGeneration
                sequence = ++setEditSequence
                latestAcceptedSetEdit[snapshot.id] = sequence
            },
        ) {
            // The fenced result reports a real incomplete-to-complete
            // transition; no-ops, guards, invalidations, and already-completed
            // edits never start a timer, even when the stored row reads
            // completed (finished roots, wrong parents, stale entities).
            val transitioned = repository.updateWorkoutSet(snapshot)
            // In-write read needed for the set-completion callback stays
            // independent and installs only on the acceptance-captured validity:
            // a newer accepted edit means this read predates its write and must
            // not install. Scope liveness is verified before any install.
            val loaded = try {
                repository.workoutDetails(workoutId)
            } catch (error: CancellationException) {
                throw error
            } catch (stale: StaleSessionException) {
                if (acceptedGeneration == refreshGeneration && scope.isActive) {
                    refreshGeneration++
                    details = null
                    refreshFailed = true
                }
                null
            } catch (_: Exception) {
                if (acceptedGeneration == refreshGeneration && scope.isActive) refreshFailed = true
                null
            }
            if (loaded != null && acceptedGeneration == refreshGeneration && scope.isActive) {
                details = loaded
                refreshFailed = false
            }
            if (!transitioned) return@launchEdit
            if (latestAcceptedSetEdit[snapshot.id] != sequence) return@launchEdit
            val storedExercise = loaded?.exercises?.singleOrNull {
                it.workoutExercise.id == snapshot.workoutExerciseId
            }
            val stored = storedExercise?.sets?.singleOrNull { it.id == snapshot.id }
            if (loaded?.workout?.status == "active" && stored?.isCompleted == true) {
                afterUpdate?.invoke()
            }
        }
        // Successful post-edit refresh is an untracked draining load scheduled
        // after the tracked write returns; loads are unregistered, so the drain
        // joining the own write is not self-join.
        if (job != null) refresh()
        return job
    }

    fun deleteSet(workoutSet: WorkoutSetEntity): Job? {
        val snapshot = workoutSet
        val job = edits.launchEdit(
            "set:${snapshot.id}",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.deleteWorkoutSet(snapshot)
        }
        if (job != null) refresh()
        return job
    }

    fun deleteExercise(exercise: WorkoutExerciseDetails): Job? {
        val workoutExerciseId = exercise.workoutExercise.id
        val parentWorkoutId = exercise.workoutExercise.workoutId
        val job = edits.launchEdit(
            "entry:$workoutExerciseId",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.deleteExerciseFromWorkout(
                workoutExerciseId = workoutExerciseId,
                workoutId = parentWorkoutId,
            )
        }
        if (job != null) refresh()
        return job
    }
}

@Composable
private fun rememberActiveWorkoutState(
    workoutId: String,
    workoutStartMillis: Long,
): ActiveWorkoutState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    val edits = rememberFiniteEditCoordinator()
    return remember(workoutId, workoutStartMillis, repository, scope, edits) {
        ActiveWorkoutState(workoutId, workoutStartMillis, repository, scope, edits)
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
                            // The app-shell name callback runs only for accepted edits; refused
                            // edits change neither the local draft nor the pill state.
                            if (state.updateWorkoutName(name) != null) {
                                onWorkoutNameChanged(name)
                                true
                            } else {
                                false
                            }
                        },
                    )
                }
            }
            state.details?.exercises?.let { exercises ->
                items(exercises, key = { it.workoutExercise.id }) { exercise ->
                    WorkoutExerciseCard(
                        exercise = exercise,
                        onNotesChanged = { id, notes -> state.updateNotes(id, notes) != null },
                        onRestDurationChanged = { id, seconds ->
                            state.updateRestDuration(id, seconds) != null
                        },
                        onSetAdded = { state.addSet(exercise) },
                        onSetChanged = { set, afterUpdate ->
                            state.updateSet(set, afterUpdate) != null
                        },
                        onSetDeleted = { set -> state.deleteSet(set) != null },
                        onExerciseDeleted = { state.deleteExercise(exercise) != null },
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
    onNameChanged: (String) -> Boolean,
) {
    var name by remember(workoutId) { mutableStateOf(initialName) }
    // Same-ID projection refresh (for example a custom rename materialized at
    // an unchanged parent revision) reconciles exactly without recreating focus
    // nodes. Refused keystrokes never reach here; the draft only follows accepted
    // edits and newer persisted values.
    LaunchedEffect(workoutId, initialName) {
        name = reconcileStringField(name, initialName)
    }
    BasicTextField(
        value = name,
        onValueChange = {
            // The local draft follows only accepted edits; refused keystrokes while paused
            // never alter domain-like drafts.
            if (onNameChanged(it)) name = it
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
