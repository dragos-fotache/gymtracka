package com.dragosfotache.gymtracka.ui.routines

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.data.gym.RoutineStateRepository
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.util.reconcileRepetitionsText
import com.dragosfotache.gymtracka.ui.util.reconcileWeightText
import kotlinx.coroutines.CancellationException
import com.dragosfotache.gymtracka.ui.FiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberFiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseDetails
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.ui.history.HistorySetRow
import com.dragosfotache.gymtracka.ui.history.HistorySetTable
import com.dragosfotache.gymtracka.ui.history.historySetColumnWidths
import com.dragosfotache.gymtracka.ui.components.CompactSetTextField
import com.dragosfotache.gymtracka.ui.components.SwipeToDeleteSetRow
import com.dragosfotache.gymtracka.ui.activeworkout.RestDurationDialog
import com.dragosfotache.gymtracka.ui.activeworkout.RestDurationRow
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutActionButton
import com.dragosfotache.gymtracka.ui.theme.WorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutScreenStyle
import com.dragosfotache.gymtracka.ui.theme.WorkoutSeparator
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
        GymDialog(
            onDismissRequest = state::dismissDelete,
            title = "Delete routine?",
            text = { Text("This will permanently delete ${routine.routine.name}.") },
            confirmAction = GymDialogAction(
                text = "Delete routine",
                onClick = state::confirmDelete,
                destructive = true,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = state::dismissDelete,
            ),
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
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        val details = state.details
        val setsByExercise = details?.exercises.orEmpty().associate { exercise ->
            exercise.routineExercise.id to exercise.sets.map { set ->
                HistorySetRow(
                    id = set.id,
                    weight = set.weightKg?.let { "${formatKilograms(it)} kg" } ?: "—",
                    repetitions = set.repetitions?.toString() ?: "—",
                )
            }
        }
        val columnWidths = historySetColumnWidths(setsByExercise.values.flatten())
        Scaffold(
            modifier = modifier.testTag("routine-details-screen"),
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
                            "Routine details", Modifier.weight(1f),
                            fontWeight = FontWeight.Bold, horizontalPadding = 0,
                        )
                    }
                }
            },
            bottomBar = {
                if (details != null) {
                    Box(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = grid.space(23), vertical = grid.space(12)),
                    ) {
                        WorkoutActionButton(
                            "Start routine", { onStartRoutine(details.routine.id) },
                            Modifier.fillMaxWidth().testTag("routine-details-start"),
                        )
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("routine-details-list"),
                contentPadding = PaddingValues(horizontal = grid.space(23), vertical = grid.space(24)),
                verticalArrangement = Arrangement.spacedBy(grid.space(24)),
            ) {
                if (details == null) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                } else {
                    item {
                        WorkoutGridLabel(details.routine.name, fontWeight = FontWeight.Bold, horizontalPadding = 0)
                    }
                    items(details.exercises, key = { it.routineExercise.id }) { exercise ->
                        RoutineDetailExercisePanel(
                            exercise, setsByExercise.getValue(exercise.routineExercise.id), columnWidths,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineDetailExercisePanel(
    exercise: RoutineExerciseDetails,
    sets: List<HistorySetRow>,
    columnWidths: List<Dp>,
) {
    val grid = LocalWorkoutGrid.current!!
    Column(
        Modifier.fillMaxWidth().testTag("routine-details-exercise-${exercise.routineExercise.id}")
            .border(grid.space(2), MaterialTheme.colorScheme.outline).padding(vertical = grid.space(12)),
    ) {
        WorkoutGridLabel(exercise.routineExercise.exerciseName, fontWeight = FontWeight.SemiBold, compact = true)
        WorkoutGridLabel(
            "Rest Timer: ${formatRestDuration(exercise.routineExercise.restDurationSeconds)}",
            color = WorkoutMuted, compact = true,
        )
        Spacer(Modifier.height(grid.space(24)))
        HistorySetTable(sets, "routine-details-set", columnWidths)
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
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        Scaffold(
            modifier = modifier,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = grid.space(24))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = grid.space(23)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorkoutGridLabel(
                        "Cancel",
                        Modifier.weight(1f).clickable(role = Role.Button) { onCancel(details.routine.isDraft) },
                        color = MaterialTheme.colorScheme.primary,
                        horizontalPadding = 0,
                    )
                    WorkoutActionButton(
                        "Save", { state.requestSave(onSaved) }, Modifier.width(grid.space(96)),
                        enabled = details.exercises.isNotEmpty(),
                    )
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("routine-editor-list"),
                contentPadding = PaddingValues(horizontal = grid.space(23)),
            ) {
                item {
                    Spacer(Modifier.height(grid.space(24)))
                    RoutineNameField(state.name, onNameChanged = { state.updateName(it) != null })
                }
                items(details.exercises, key = { it.routineExercise.id }) { exercise ->
                    WorkoutSeparator()
                    RoutineExerciseCard(
                        exercise = exercise,
                        onRestDurationChanged = { id, seconds -> state.updateRestDuration(id, seconds) != null },
                        onSetAdded = { state.addSet(exercise) },
                        onSetChanged = { state.updateSet(it) != null },
                        onSetDeleted = { state.deleteSet(it) != null },
                        onExerciseDeleted = { state.deleteExercise(exercise) != null },
                    )
                }
                item {
                    Spacer(Modifier.height(grid.space(24)))
                    WorkoutActionButton("Add exercise", onAddExercise, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(grid.space(24)))
                }
            }
        }
        if (state.showNameRequiredDialog) {
            GymDialog(
                onDismissRequest = state::dismissNameRequiredDialog,
                title = "Routine name required",
                text = { Text("Enter a name before saving this routine.") },
                confirmAction = GymDialogAction(
                    text = "OK",
                    onClick = state::dismissNameRequiredDialog,
                ),
            )
        }
    }
}

@Composable
private fun RoutineNameField(name: String, onNameChanged: (String) -> Boolean) {
    val grid = LocalWorkoutGrid.current!!
    Box(
        Modifier.fillMaxWidth().height(grid.line).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = grid.space(12)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = name,
            onValueChange = { onNameChanged(it) },
            modifier = Modifier.fillMaxWidth().testTag("routine-name-input"),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.ExtraBold,
            ),
            decorationBox = { field ->
                Box {
                    if (name.isEmpty()) Text("Routine name", color = WorkoutMuted, fontWeight = FontWeight.ExtraBold)
                    field()
                }
            },
        )
    }
}

@Composable
private fun RoutineExerciseCard(
    exercise: RoutineExerciseDetails,
    onRestDurationChanged: (String, Int) -> Boolean,
    onSetAdded: () -> Unit,
    onSetChanged: (RoutineSetEntity) -> Boolean,
    onSetDeleted: (RoutineSetEntity) -> Boolean,
    onExerciseDeleted: () -> Boolean,
) {
    val grid = LocalWorkoutGrid.current!!
    var confirmDelete by remember { mutableStateOf(false) }
    var restDialogVisible by remember { mutableStateOf(false) }
    var restSeconds by remember(exercise.routineExercise.id) {
        mutableIntStateOf(exercise.routineExercise.restDurationSeconds)
    }
    LaunchedEffect(exercise.routineExercise.id, exercise.routineExercise.restDurationSeconds) {
        restSeconds = exercise.routineExercise.restDurationSeconds
    }
    Column(Modifier.fillMaxWidth().testTag("routine-exercise-${exercise.routineExercise.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WorkoutGridLabel(exercise.routineExercise.exerciseName, Modifier.weight(1f), horizontalPadding = 0)
            WorkoutGridLabel(
                "[Delete]",
                Modifier.semantics { contentDescription = "Delete ${exercise.routineExercise.exerciseName} from routine" }
                    .clickable(role = Role.Button) { confirmDelete = true },
                color = MaterialTheme.colorScheme.error,
                horizontalPadding = 0,
            )
        }
        RestDurationRow(restSeconds, onClick = { restDialogVisible = true })
        Box {
            RoutineSetColumns(
                number = { Text("#", color = WorkoutMuted) },
                weight = { Text("WEIGHT", color = WorkoutMuted, fontWeight = FontWeight.Bold) },
                repetitions = { Text("REPS", color = WorkoutMuted, fontWeight = FontWeight.Bold) },
            )
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(grid.space(4))
                    .background(MaterialTheme.colorScheme.outline),
            )
        }
        exercise.sets.forEach { set ->
            key(set.id) { RoutineSetRow(set, onSetChanged, onSetDeleted) }
        }
        WorkoutActionButton("Add set", onSetAdded, Modifier.fillMaxWidth())
    }
    if (confirmDelete) {
        GymDialog(
            onDismissRequest = { confirmDelete = false },
            title = "Delete exercise?",
            text = { Text("Remove ${exercise.routineExercise.exerciseName} and all its sets from this routine?") },
            confirmAction = GymDialogAction(
                text = "Delete",
                onClick = { if (onExerciseDeleted()) confirmDelete = false },
                destructive = true,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = { confirmDelete = false },
            ),
        )
    }
    if (restDialogVisible) {
        // Keep the entry snapshot through same-ID publication while the dialog is open.
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
private fun RoutineSetColumns(
    number: @Composable () -> Unit,
    weight: @Composable () -> Unit,
    repetitions: @Composable () -> Unit,
) {
    val grid = LocalWorkoutGrid.current!!
    Box(Modifier.fillMaxWidth().heightIn(min = grid.line)) {
        Box(
            Modifier.align(Alignment.CenterStart).width(grid.space(if (grid.fontScale > 1.2f) 48 else 36)),
            contentAlignment = Alignment.Center,
        ) { number() }
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = grid.space(60)).width(grid.space(156)),
            contentAlignment = Alignment.Center,
        ) { weight() }
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = grid.space(240)).width(grid.space(156)),
            contentAlignment = Alignment.Center,
        ) { repetitions() }
    }
}

@Composable
private fun RoutineSetRow(
    set: RoutineSetEntity,
    onSetChanged: (RoutineSetEntity) -> Boolean,
    onSetDeleted: (RoutineSetEntity) -> Boolean,
) {
    val grid = LocalWorkoutGrid.current!!
    var weightText by remember(set.id) { mutableStateOf(set.weightKg?.let(::formatKilograms).orEmpty()) }
    var repetitionsText by remember(set.id) { mutableStateOf(set.repetitions?.toString().orEmpty()) }
    LaunchedEffect(set.id, set.weightKg) { weightText = reconcileWeightText(weightText, set.weightKg) }
    LaunchedEffect(set.id, set.repetitions) { repetitionsText = reconcileRepetitionsText(repetitionsText, set.repetitions) }
    SwipeToDeleteSetRow(
        setId = set.id,
        onDelete = { onSetDeleted(set) },
        modifier = Modifier.testTag("routine-set-row-${set.id}"),
        deleteDescription = "Delete set ${set.position + 1}",
    ) {
        RoutineSetColumns(
            number = {
                Box(Modifier.size(grid.input).background(MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) {
                    Text("${set.position + 1}", fontSize = (18 * grid.scale).sp)
                }
            },
            weight = {
                CompactSetTextField(
                    value = weightText,
                    onValueChange = {
                        val candidate = set.copy(weightKg = parseKilograms(it), repetitions = repetitionsText.toIntOrNull())
                        if (onSetChanged(candidate)) weightText = it
                    },
                    modifier = Modifier.width(grid.space(if (grid.fontScale > 1.2f) 144 else 84))
                        .testTag("routine-set-weight-${set.id}"),
                    showBorder = true,
                    keyboardType = KeyboardType.Decimal,
                )
            },
            repetitions = {
                CompactSetTextField(
                    value = repetitionsText,
                    onValueChange = {
                        val candidate = set.copy(weightKg = parseKilograms(weightText), repetitions = it.toIntOrNull())
                        if (onSetChanged(candidate)) repetitionsText = it
                    },
                    modifier = Modifier.width(grid.space(if (grid.fontScale > 1.2f) 96 else 60))
                        .testTag("routine-set-reps-${set.id}"),
                    showBorder = true,
                    keyboardType = KeyboardType.Number,
                )
            },
        )
    }
}
