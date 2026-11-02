package com.dragosfotache.gymtracka.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.data.gym.RoutineStateRepository
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.ui.util.reconcileRepetitionsText
import com.dragosfotache.gymtracka.ui.util.reconcileWeightText
import kotlinx.coroutines.CancellationException
import com.dragosfotache.gymtracka.ui.FiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberFiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseDetails
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.ui.activeworkout.RestDurationDialog
import com.dragosfotache.gymtracka.ui.activeworkout.RestDurationRow
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutActionButton
import com.dragosfotache.gymtracka.ui.theme.WorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.util.formatKilograms
import com.dragosfotache.gymtracka.ui.util.parseKilograms
import com.dragosfotache.gymtracka.ui.util.formatRestDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

internal class RoutineListState(
    private val repository: RoutineStateRepository,
    private val scope: CoroutineScope,
    private val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
) {
    var routines by mutableStateOf<List<RoutineDetails>?>(null)
        private set
    var routineToDelete by mutableStateOf<RoutineDetails?>(null)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    private var loadGeneration = 0

    init {
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect { loadRoutines() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (!scope.isActive) return@launch
                loadGeneration++
                routines = null
                routineToDelete = null
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

    fun requestDelete(routine: RoutineDetails) {
        routineToDelete = routine
    }

    fun dismissDelete() {
        routineToDelete = null
    }

    fun confirmDelete(): Job? {
        val routine = routineToDelete ?: return null
        val routineId = routine.routine.id
        val job = edits.launchEdit(
            "routine-delete:$routineId",
            onAccepted = { loadGeneration++ },
        ) {
            repository.deleteRoutine(routineId)
            routineToDelete = null
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
                loadRoutines()
            }
        }
        return job
    }

    /**
     * Observer/explicit/post-edit reload: guarded pure scoped read without
     * global drains. List models carry no autosaved field drafts and dialog
     * drafts live outside the loaded model, so initial and list reads stay
     * usable while unrelated finite writes are pending. Generation and scope
     * guards prevent stale/closed-scope installs.
     */
    private fun loadRoutines() {
        val generation = ++loadGeneration
        scope.launch {
            if (generation != loadGeneration || !scope.isActive) return@launch
            try {
                val loaded = repository.savedRoutineDetails()
                if (generation != loadGeneration || !scope.isActive) return@launch
                routines = loaded
                refreshFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (generation != loadGeneration || !scope.isActive) return@launch
                loadGeneration++
                routines = null
                routineToDelete = null
                refreshFailed = true
            } catch (_: Exception) {
                if (generation != loadGeneration || !scope.isActive) return@launch
                refreshFailed = true
            }
        }
    }
}

internal class RoutineEditorState(
    private val routineId: String,
    private val repository: RoutineStateRepository,
    private val scope: CoroutineScope,
    private val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
) {
    var details by mutableStateOf<RoutineDetails?>(null)
        private set
    var name by mutableStateOf("")
        private set
    var showNameRequiredDialog by mutableStateOf(false)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    private var refreshGeneration = 0
    private var nameSaveJob: Job? = null

    init {
        // Single observation-driven load path; the initial epoch triggers the
        // first load. Observer/load jobs stay outside finite edit registrations.
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect { refresh() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (!scope.isActive) return@launch
                refreshGeneration++
                details = null
                name = ""
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
    }

    /**
     * Observer/explicit/post-edit reload: waits for accepted finite work outside
     * tracked edits before reading, then rechecks generation and scope liveness.
     * Scheduled after an accepted write returns, the own write is already
     * registered, so the drain joins it without self-join; loads are
     * unregistered. A load started before a newer refresh or accepted edit never
     * installs afterward, including a cancellation-ignoring fake and never after
     * disposal. The accepted name draft survives because acceptances invalidate
     * older loads synchronously and loads drain first; a normal new publication
     * then replaces the persisted values, including the editor name.
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
                val loaded = repository.routineDetails(routineId)
                if (generation != refreshGeneration || !scope.isActive) return@launch
                details = loaded
                name = loaded?.routine?.name.orEmpty()
                refreshFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (generation != refreshGeneration || !scope.isActive) return@launch
                refreshGeneration++
                details = null
                name = ""
                refreshFailed = true
            } catch (_: Exception) {
                if (generation != refreshGeneration || !scope.isActive) return@launch
                refreshFailed = true
            }
        }
    }

    fun updateName(value: String): Job? {
        val previousSave = nameSaveJob
        val job = edits.launchEdit(
            key = "routine:$routineId:name",
            onAccepted = {
                // Invalidate outstanding loads synchronously; the accepted draft
                // is applied here so an old read can never roll it back. Refusal
                // invalidates nothing and leaves the draft unchanged.
                refreshGeneration++
                name = value
            },
        ) {
            previousSave?.join()
            repository.updateRoutineName(routineId, value)
        }
        if (job != null) {
            nameSaveJob = job
            // Successful writes schedule a guarded refresh too: onAccepted may
            // have invalidated an in-flight epoch load carrying unrelated
            // publication labels, which must still be reloaded.
            refresh()
        }
        return job
    }

    fun requestSave(onSaved: () -> Unit): Job? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            showNameRequiredDialog = true
            return null
        }
        val job = edits.launchEdit(
            "routine:$routineId:save",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.saveRoutine(routineId, trimmedName)
            onSaved()
        }
        if (job != null) refresh()
        return job
    }

    fun dismissNameRequiredDialog() {
        showNameRequiredDialog = false
    }

    fun updateRestDuration(routineExerciseId: String, seconds: Int): Job? {
        val job = edits.launchEdit(
            key = "routine-rest:$routineExerciseId",
            onAccepted = {
                refreshGeneration++
                details = details?.let { current ->
                    current.copy(
                        exercises = current.exercises.map { exercise ->
                            if (exercise.routineExercise.id == routineExerciseId) {
                                exercise.copy(
                                    routineExercise = exercise.routineExercise.copy(
                                        restDurationSeconds = seconds,
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
            repository.updateRoutineExerciseRestDuration(routineExerciseId, seconds)
        }
        if (job != null) refresh()
        return job
    }

    fun addSet(exercise: RoutineExerciseDetails): Job? {
        val routineExerciseId = exercise.routineExercise.id
        val position = exercise.sets.size
        val previousSet = exercise.sets.lastOrNull()
        val previousRepetitions = previousSet?.repetitions
        val previousWeightKg = previousSet?.weightKg
        val job = edits.launchEdit(
            "routine-sets:$routineExerciseId",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.addSetToRoutineExercise(
                routineExerciseId = routineExerciseId,
                position = position,
                repetitions = previousRepetitions,
                weightKg = previousWeightKg,
            )
        }
        if (job != null) refresh()
        return job
    }

    fun updateSet(set: RoutineSetEntity): Job? {
        val snapshot = set
        val job = edits.launchEdit(
            "routine-set:${snapshot.id}",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.updateRoutineSet(snapshot)
        }
        if (job != null) refresh()
        return job
    }

    fun deleteSet(set: RoutineSetEntity): Job? {
        val snapshot = set
        val job = edits.launchEdit(
            "routine-set:${snapshot.id}",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.deleteRoutineSet(snapshot)
        }
        if (job != null) refresh()
        return job
    }

    fun deleteExercise(exercise: RoutineExerciseDetails): Job? {
        val routineExerciseId = exercise.routineExercise.id
        val job = edits.launchEdit(
            "routine-entry:$routineExerciseId",
            onAccepted = { refreshGeneration++ },
        ) {
            repository.deleteRoutineExercise(routineExerciseId, routineId)
        }
        if (job != null) refresh()
        return job
    }
}

internal class RoutineDetailState(
    routineId: String,
    repository: RoutineStateRepository,
    scope: CoroutineScope,
) {
    var details by mutableStateOf<RoutineDetails?>(null)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    private var loadGeneration = 0

    init {
        // Pure read-only state: no finite drain is needed. Each epoch
        // synchronously advances the generation and launches a guarded load
        // outside the collector, so a blocked old load never stalls newer
        // epochs or revocation, and a cancellation-ignoring old load is
        // rejected by the generation check.
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect {
                    val generation = ++loadGeneration
                    scope.launch {
                        if (generation != loadGeneration || !scope.isActive) return@launch
                        try {
                            val loaded = repository.routineDetails(routineId)
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
private fun rememberRoutineListState(): RoutineListState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    val edits = rememberFiniteEditCoordinator()
    return remember(repository, scope, edits) { RoutineListState(repository, scope, edits) }
}

@Composable
private fun rememberRoutineEditorState(routineId: String): RoutineEditorState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    val edits = rememberFiniteEditCoordinator()
    return remember(routineId, repository, scope, edits) {
        RoutineEditorState(routineId, repository, scope, edits)
    }
}

@Composable
private fun rememberRoutineDetailState(routineId: String): RoutineDetailState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    return remember(routineId, repository, scope) {
        RoutineDetailState(routineId, repository, scope)
    }
}

@Composable
internal fun RoutineSection(
    onNewRoutine: () -> Unit,
    onRoutineSelected: (String) -> Unit,
    onEditRoutine: (String) -> Unit,
    onStartRoutine: (String) -> Unit,
    state: RoutineListState = rememberRoutineListState(),
) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    Column(verticalArrangement = Arrangement.spacedBy(grid.space(24))) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkoutGridLabel("Routines", Modifier.weight(1f))
            WorkoutActionButton(
                "Create", onNewRoutine,
                Modifier.width(grid.space(36 + ceil(72 * grid.fontScale / 6).toInt() * 6))
                    .testTag("create-routine"),
            )
        }
        state.routines.orEmpty().forEach { routine ->
            key(routine.routine.id) {
                RoutineCard(
                    details = routine,
                    onOpenDetails = { onRoutineSelected(routine.routine.id) },
                    onEdit = { onEditRoutine(routine.routine.id) },
                    onStart = { onStartRoutine(routine.routine.id) },
                    onDelete = { state.requestDelete(routine) },
                )
            }
        }
    }

    state.routineToDelete?.let { routine ->
        AlertDialog(
            onDismissRequest = state::dismissDelete,
            title = { Text("Delete routine?") },
            text = { Text("This will permanently delete ${routine.routine.name}.") },
            confirmButton = {
                TextButton(onClick = state::confirmDelete) {
                    Text("Delete routine", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissDelete) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RoutineCard(
    details: RoutineDetails,
    onOpenDetails: () -> Unit,
    onEdit: () -> Unit,
    onStart: () -> Unit,
    onDelete: () -> Unit,
) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    var actionsExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("routine-card-${details.routine.id}")
            .border(grid.space(2), MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkoutGridLabel(
                "[${details.routine.name}]",
                modifier = Modifier.weight(1f)
                    .testTag("routine-open-${details.routine.id}")
                    .clickable(role = Role.Button, onClickLabel = "Open routine details", onClick = onOpenDetails),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Box(
                Modifier.width(grid.space(48)).heightIn(min = grid.line)
                    .testTag("routine-options-${details.routine.id}")
                    .semantics {
                        contentDescription = "Routine options for ${details.routine.name}"
                        stateDescription = if (actionsExpanded) "Expanded" else "Collapsed"
                    }
                    .clickable(role = Role.Button) { actionsExpanded = !actionsExpanded },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (actionsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(grid.space(24)),
                )
            }
        }
        if (actionsExpanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RoutineAction("Edit", details.routine.name, onEdit)
                RoutineAction("Delete", details.routine.name, onDelete, destructive = true)
            }
        }
        WorkoutActionButton(
            "Start", onStart,
            Modifier.fillMaxWidth().testTag("routine-start-${details.routine.id}"),
        )
    }
}

@Composable
private fun RoutineAction(
    label: String,
    routineName: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    WorkoutGridLabel(
        "[$label]",
        modifier = Modifier
            .semantics { contentDescription = "$label routine $routineName" }
            .clickable(role = Role.Button, onClick = onClick),
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun RoutineDetailScreen(
    routineId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onStartRoutine: (String) -> Unit,
    state: RoutineDetailState = rememberRoutineDetailState(routineId),
) {
    BackHandler(onBack = onBack)
    val details = state.details
    if (details == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 34.dp, end = 34.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                modifier = Modifier.offset(x = (-8).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to workouts",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    details.routine.name,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        item {
            Button(
                onClick = { onStartRoutine(details.routine.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start routine")
            }
        }
        items(details.exercises, key = { it.routineExercise.id }) { exercise ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(exercise.routineExercise.exerciseName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Rest Timer: ${formatRestDuration(exercise.routineExercise.restDurationSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                exercise.sets.forEachIndexed { index, set ->
                    Text(
                        "${index + 1}. ${set.weightKg?.let(::formatKilograms) ?: "–"} kg × ${set.repetitions ?: "–"}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RoutineEditorScreen(
    routineId: String,
    modifier: Modifier = Modifier,
    onAddExercise: () -> Unit,
    onCancel: (isDraft: Boolean) -> Unit,
    onSaved: () -> Unit,
    state: RoutineEditorState = rememberRoutineEditorState(routineId),
) {
    val details = state.details
    if (details == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), shadowElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onCancel(details.routine.isDraft) }) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { state.requestSave(onSaved) },
                        enabled = details.exercises.isNotEmpty(),
                    ) {
                        Text("Save")
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RoutineNameField(
                    name = state.name,
                    onNameChanged = { state.updateName(it) != null },
                )
            }
            items(details.exercises, key = { it.routineExercise.id }) { exercise ->
                RoutineExerciseCard(
                    exercise = exercise,
                    onRestDurationChanged = { id, seconds ->
                        state.updateRestDuration(id, seconds) != null
                    },
                    onSetAdded = { state.addSet(exercise) },
                    onSetChanged = { set -> state.updateSet(set) != null },
                    onSetDeleted = state::deleteSet,
                    onExerciseDeleted = { state.deleteExercise(exercise) },
                )
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
        }
    }

    if (state.showNameRequiredDialog) {
        AlertDialog(
            onDismissRequest = state::dismissNameRequiredDialog,
            title = { Text("Routine name required") },
            text = { Text("Enter a name before saving this routine.") },
            confirmButton = {
                TextButton(onClick = state::dismissNameRequiredDialog) { Text("OK") }
            },
        )
    }
}

@Composable
private fun RoutineNameField(name: String, onNameChanged: (String) -> Boolean) {
    BasicTextField(
        value = name,
        // The field value is driven by accepted state; refused input while paused leaves it
        // unchanged without altering the draft.
        onValueChange = { onNameChanged(it) },
        modifier = Modifier.fillMaxWidth().testTag("routine-name-input"),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        textStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        ),
        decorationBox = { innerTextField ->
            Box {
                if (name.isEmpty()) {
                    Text(
                        "Routine name",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun RoutineExerciseCard(
    exercise: RoutineExerciseDetails,
    onRestDurationChanged: (String, Int) -> Boolean,
    onSetAdded: () -> Unit,
    onSetChanged: (RoutineSetEntity) -> Boolean,
    onSetDeleted: (RoutineSetEntity) -> Unit,
    onExerciseDeleted: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var restDialogVisible by remember { mutableStateOf(false) }
    var restSeconds by remember(exercise.routineExercise.id) {
        mutableIntStateOf(exercise.routineExercise.restDurationSeconds)
    }
    // Same-ID projection refresh adopts the latest committed rest without
    // recreating focus nodes or resetting menu/dialog state.
    LaunchedEffect(exercise.routineExercise.id, exercise.routineExercise.restDurationSeconds) {
        restSeconds = exercise.routineExercise.restDurationSeconds
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    exercise.routineExercise.exerciseName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Exercise options")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onExerciseDeleted()
                            },
                        )
                    }
                }
            }
            RestDurationRow(restSeconds = restSeconds, onClick = { restDialogVisible = true })
            RoutineSetTableHeader()
            exercise.sets.forEach { set ->
                // Stable row identity by set ID: an earlier remote insert/remove
                // reshuffles positional slots, so without this the focused set's
                // focus and raw draft state could move or drop even when its ID
                // survives. Never keyed by epoch.
                key(set.id) {
                    RoutineSetRow(set, onSetChanged, onSetDeleted)
                }
            }
            TextButton(
                onClick = onSetAdded,
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

    if (restDialogVisible) {
        // Snapshot the dialog entry value while open: a same-ID publication
        // refresh must not rewrite the unconfirmed dialog draft. The snapshot
        // resets only on dialog close/reopen or true entry replacement.
        val dialogInitialRest = remember(exercise.routineExercise.id) { restSeconds }
        RestDurationDialog(
            initialRestSeconds = dialogInitialRest,
            onDismiss = { restDialogVisible = false },
            onDone = { seconds ->
                if (onRestDurationChanged(exercise.routineExercise.id, seconds)) {
                    restSeconds = seconds
                    restDialogVisible = false
                }
            },
        )
    }
}

@Composable
private fun RoutineSetTableHeader() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("SET", modifier = Modifier.weight(0.55f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(8.dp))
        Text("WEIGHT", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(8.dp))
        Text("REPS", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(8.dp))
        Spacer(Modifier.size(36.dp))
    }
}

@Composable
private fun RoutineSetRow(
    set: RoutineSetEntity,
    onSetChanged: (RoutineSetEntity) -> Boolean,
    onSetDeleted: (RoutineSetEntity) -> Unit,
) {
    var weightText by remember(set.id) { mutableStateOf(set.weightKg?.let(::formatKilograms).orEmpty()) }
    var repetitionsText by remember(set.id) { mutableStateOf(set.repetitions?.toString().orEmpty()) }
    // Same-ID projection refresh: retain accepted raw numeric text when the
    // incoming value sanitizes equal (for example `12.00`), replace genuinely
    // different persisted values without clearing focus.
    LaunchedEffect(set.id, set.weightKg) {
        weightText = reconcileWeightText(weightText, set.weightKg)
    }
    LaunchedEffect(set.id, set.repetitions) {
        repetitionsText = reconcileRepetitionsText(repetitionsText, set.repetitions)
    }
    fun editedSet() = set.copy(
        weightKg = parseKilograms(weightText),
        repetitions = repetitionsText.toIntOrNull(),
    )

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.weight(0.55f).size(36.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("${set.position + 1}", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(8.dp))
        RoutineSetTextField(
            value = weightText,
            onValueChange = {
                val candidate = set.copy(
                    weightKg = parseKilograms(it),
                    repetitions = repetitionsText.toIntOrNull(),
                )
                if (onSetChanged(candidate)) weightText = it
            },
            modifier = Modifier.weight(1f).testTag("routine-set-weight-${set.id}"),
            keyboardType = KeyboardType.Decimal,
        )
        Spacer(Modifier.width(8.dp))
        RoutineSetTextField(
            value = repetitionsText,
            onValueChange = {
                val candidate = set.copy(
                    weightKg = parseKilograms(weightText),
                    repetitions = it.toIntOrNull(),
                )
                if (onSetChanged(candidate)) repetitionsText = it
            },
            modifier = Modifier.weight(1f).testTag("routine-set-reps-${set.id}"),
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { onSetDeleted(set) },
            modifier = Modifier.size(36.dp).testTag("routine-delete-set-${set.id}"),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete set", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RoutineSetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    keyboardType: KeyboardType,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(36.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
    )
}
