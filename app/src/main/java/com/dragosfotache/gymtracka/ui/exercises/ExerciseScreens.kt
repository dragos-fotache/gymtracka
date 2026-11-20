package com.dragosfotache.gymtracka.ui.exercises

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.ExerciseSummary
import com.dragosfotache.gymtracka.data.gym.ExerciseDetails
import com.dragosfotache.gymtracka.data.gym.EquipmentEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseStateRepository
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.util.pruneMissingSelectedIds
import kotlinx.coroutines.CancellationException
import com.dragosfotache.gymtracka.ui.FiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberFiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.data.gym.MuscleGroupEntity
import com.dragosfotache.gymtracka.ui.theme.GymAccent
import com.dragosfotache.gymtracka.ui.theme.GymBackground
import com.dragosfotache.gymtracka.ui.theme.GymSelectedTab
import com.dragosfotache.gymtracka.ui.theme.GymSurface
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutActionButton
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutScreenStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ExerciseCatalogueState(
    private val repository: ExerciseStateRepository,
    private val scope: CoroutineScope,
    initialSearchQuery: String = "",
    initialEquipmentIds: Set<String> = emptySet(),
    initialMuscleGroupIds: Set<String> = emptySet(),
    private val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
) {
    var summaries by mutableStateOf<List<ExerciseSummary>?>(null)
        private set
    var searchQuery by mutableStateOf(initialSearchQuery)
        private set
    var selectedEquipmentIds by mutableStateOf(initialEquipmentIds)
        private set
    var selectedMuscleGroupIds by mutableStateOf(initialMuscleGroupIds)
        private set
    var selectedExerciseIds by mutableStateOf(emptySet<String>())
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    private var loadGeneration = 0

    init {
        // Single observation-driven load path: the initial epoch emission triggers
        // the first load, so no separate uncontrolled startup load exists. The
        // observer/collector job stays outside finite edit registrations.
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect { loadSummaries() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (!scope.isActive) return@launch
                loadGeneration++
                summaries = null
                selectedExerciseIds = emptySet()
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

    /**
     * Guarded scoped catalogue read outside global drains. Catalogue summaries
     * carry no autosaved domain text drafts, so initial and picker reads stay
     * usable while unrelated finite writes are pending; opening a picker never
     * waits for an unrelated autosave. Accepted catalogue mutations invalidate
     * synchronously via onAccepted, and a fresh guarded read is scheduled after
     * successful catalogue-owned writes. Generation and scope guards prevent
     * stale/closed-scope installs; selection is pruned only on proven absence,
     * never reset on rename.
     */
    private fun loadSummaries() {
        val generation = ++loadGeneration
        scope.launch {
            if (generation != loadGeneration || !scope.isActive) return@launch
            try {
                val loaded = repository.exerciseSummaries()
                if (generation != loadGeneration || !scope.isActive) return@launch
                selectedExerciseIds = pruneMissingSelectedIds(
                    selectedExerciseIds,
                    loaded.map { it.exercise.id }.toSet(),
                )
                summaries = loaded
                refreshFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stale: StaleSessionException) {
                if (generation != loadGeneration || !scope.isActive) return@launch
                loadGeneration++
                summaries = null
                selectedExerciseIds = emptySet()
                refreshFailed = true
            } catch (_: Exception) {
                if (generation != loadGeneration || !scope.isActive) return@launch
                refreshFailed = true
            }
        }
    }

    val filteredExercises: List<ExerciseSummary>
        get() = summaries.orEmpty().filter {
            it.matchesSearch(searchQuery) && it.matchesFilters(
                equipmentIds = selectedEquipmentIds,
                muscleGroupIds = selectedMuscleGroupIds,
            )
        }

    fun updateSearchQuery(value: String) {
        searchQuery = value
    }

    fun updateEquipmentIds(value: Set<String>) {
        selectedEquipmentIds = value
    }

    fun updateMuscleGroupIds(value: Set<String>) {
        selectedMuscleGroupIds = value
    }

    fun toggleExercise(exerciseId: String) {
        selectedExerciseIds = if (exerciseId in selectedExerciseIds) {
            selectedExerciseIds - exerciseId
        } else {
            selectedExerciseIds + exerciseId
        }
    }

    fun selectExercise(exerciseId: String) {
        selectedExerciseIds = setOf(exerciseId)
    }

    fun addSelectedExercises(workoutId: String, onAdded: () -> Unit): Job? {
        // The selection snapshot is captured at acceptance; later selection changes never leak
        // into the tracked commit. Selection clearing and navigation run only on success inside
        // tracked work, so refusal alters neither selection-success nor navigation state.
        val selected = selectedExerciseIds
        if (selected.isEmpty()) return null
        val job = edits.launchEdit(
            "workout:$workoutId:add-exercises",
            onAccepted = { loadGeneration++ },
        ) {
            repository.addExercisesToWorkout(workoutId, selected)
            selectedExerciseIds = emptySet()
            onAdded()
        }
        if (job != null) loadSummaries()
        return job
    }

    fun addSelectedExercisesToRoutine(routineId: String, onAdded: () -> Unit): Job? {
        val selected = selectedExerciseIds
        if (selected.isEmpty()) return null
        val job = edits.launchEdit(
            "routine:$routineId:add-exercises",
            onAccepted = { loadGeneration++ },
        ) {
            repository.addExercisesToRoutine(routineId, selected)
            selectedExerciseIds = emptySet()
            onAdded()
        }
        if (job != null) loadSummaries()
        return job
    }

    fun replaceSelectedExercise(workoutExerciseId: String, onReplaced: () -> Unit): Job? {
        val exerciseId = selectedExerciseIds.singleOrNull() ?: return null
        val job = edits.launchEdit(
            "entry:$workoutExerciseId:replace",
            onAccepted = { loadGeneration++ },
        ) {
            repository.replaceWorkoutExercise(workoutExerciseId, exerciseId)
            selectedExerciseIds = emptySet()
            onReplaced()
        }
        if (job != null) loadSummaries()
        return job
    }

    fun deleteCustomExercise(exerciseId: String): Job? {
        val job = edits.launchEdit(
            "exercise-delete:$exerciseId",
            onAccepted = { loadGeneration++ },
        ) {
            repository.deleteCustomExercise(exerciseId)
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
                loadSummaries()
            }
        }
        return job
    }
}

internal class ExerciseDetailState(
    exerciseId: String,
    repository: ExerciseStateRepository,
    scope: CoroutineScope,
) {
    var details by mutableStateOf<ExerciseDetails?>(null)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    private var loadGeneration = 0

    init {
        // Pure read-only state: no finite drain is needed. Each epoch
        // synchronously advances the generation and launches a guarded load
        // outside the collector, so a blocked old load never stalls newer
        // epochs or revocation, and a cancellation-ignoring old load is
        // rejected by the generation check. Deleting the selected root yields
        // null details, never a fabricated object or navigation change.
        scope.launch {
            try {
                repository.observeProjectionEpoch().collect {
                    val generation = ++loadGeneration
                    scope.launch {
                        if (generation != loadGeneration || !scope.isActive) return@launch
                        try {
                            val loaded = repository.exerciseDetails(exerciseId)
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

internal class ExerciseCreationState(
    private val exerciseId: String?,
    private val repository: ExerciseStateRepository,
    private val scope: CoroutineScope,
    private val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
) {
    var name by mutableStateOf("")
        private set
    var instructions by mutableStateOf("")
        private set
    var primaryMuscleGroupId by mutableStateOf<String?>(null)
        private set
    var secondaryMuscleGroupIds by mutableStateOf(emptySet<String>())
        private set
    var equipmentId by mutableStateOf<String?>(null)
        private set
    var category by mutableStateOf<String?>(null)
        private set
    var muscleGroups by mutableStateOf<List<MuscleGroupEntity>?>(null)
        private set
    var equipment by mutableStateOf<List<EquipmentEntity>?>(null)
        private set
    var categories by mutableStateOf<List<String>?>(null)
        private set
    var showNameRequiredDialog by mutableStateOf(false)
        private set
    private var isDirty = false

    init {
        scope.launch { muscleGroups = repository.muscleGroups() }
        scope.launch { equipment = repository.equipment() }
        scope.launch { categories = repository.exerciseCategories() }
        exerciseId?.let { id ->
            scope.launch {
                val loaded = repository.exerciseDetails(id)?.takeIf { it.exercise.isUserCreated }
                if (isDirty) return@launch
                loaded?.let { details ->
                    name = details.exercise.name
                    instructions = details.exercise.instructions?.joinToString("\n").orEmpty()
                    primaryMuscleGroupId = details.muscleGroups
                        .firstOrNull { it.relationshipType == "primary" }
                        ?.id
                    secondaryMuscleGroupIds = details.muscleGroups
                        .filter { it.relationshipType == "secondary" }
                        .mapTo(mutableSetOf()) { it.id }
                    equipmentId = details.equipment.firstOrNull()?.id
                    category = details.exercise.category
                }
            }
        }
    }

    private val hasEnteredData: Boolean
        get() = name.isNotBlank() ||
            instructions.isNotBlank() ||
            primaryMuscleGroupId != null ||
            secondaryMuscleGroupIds.isNotEmpty() ||
            equipmentId != null ||
            category != null

    val shouldWarnOnBack: Boolean
        get() = if (exerciseId == null) hasEnteredData else isDirty

    fun updateName(value: String) {
        name = value
        isDirty = true
    }

    fun updateInstructions(value: String) {
        instructions = value
        isDirty = true
    }

    fun updatePrimaryMuscleGroup(id: String?) {
        primaryMuscleGroupId = id
        secondaryMuscleGroupIds = id?.let { secondaryMuscleGroupIds - it }
            ?: secondaryMuscleGroupIds
        isDirty = true
    }

    fun updateSecondaryMuscleGroups(ids: Set<String>) {
        secondaryMuscleGroupIds = primaryMuscleGroupId?.let { ids - it } ?: ids
        isDirty = true
    }

    fun updateEquipment(id: String?) {
        equipmentId = id
        isDirty = true
    }

    fun updateCategory(value: String?) {
        category = value
        isDirty = true
    }

    fun requestSave(onSaved: () -> Unit): Job? {
        // Pure presentation typing is not persistence acceptance; only this save is gated. Every
        // custom-editor input is snapshotted here at acceptance, never re-read later.
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            showNameRequiredDialog = true
            return null
        }
        val targetExerciseId = exerciseId
        val snapshotPrimary = primaryMuscleGroupId
        val snapshotSecondary = secondaryMuscleGroupIds.toSet()
        val snapshotEquipment = equipmentId
        val snapshotCategory = category
        val snapshotInstructions = instructions
        val key = if (targetExerciseId == null) "exercise:create" else "exercise-update:$targetExerciseId"
        return edits.launchEdit(key) {
            if (targetExerciseId == null) {
                repository.createCustomExercise(
                    name = trimmedName,
                    primaryMuscleGroupId = snapshotPrimary,
                    secondaryMuscleGroupIds = snapshotSecondary,
                    equipmentId = snapshotEquipment,
                    category = snapshotCategory,
                    instructions = snapshotInstructions,
                )
            } else {
                repository.updateCustomExercise(
                    exerciseId = targetExerciseId,
                    name = trimmedName,
                    primaryMuscleGroupId = snapshotPrimary,
                    secondaryMuscleGroupIds = snapshotSecondary,
                    equipmentId = snapshotEquipment,
                    category = snapshotCategory,
                    instructions = snapshotInstructions,
                )
            }
            onSaved()
        }
    }

    fun dismissNameRequiredDialog() {
        showNameRequiredDialog = false
    }
}

@Composable
private fun rememberExerciseCatalogueState(
    initialSearchQuery: String = "",
    initialEquipmentIds: Set<String> = emptySet(),
    initialMuscleGroupIds: Set<String> = emptySet(),
): ExerciseCatalogueState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    val edits = rememberFiniteEditCoordinator()
    return remember(repository, scope, initialSearchQuery, initialEquipmentIds, initialMuscleGroupIds, edits) {
        ExerciseCatalogueState(
            repository = repository,
            scope = scope,
            initialSearchQuery = initialSearchQuery,
            initialEquipmentIds = initialEquipmentIds,
            initialMuscleGroupIds = initialMuscleGroupIds,
            edits = edits,
        )
    }
}

@Composable
private fun rememberExerciseDetailState(exerciseId: String): ExerciseDetailState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    return remember(exerciseId, repository, scope) {
        ExerciseDetailState(exerciseId, repository, scope)
    }
}

@Composable
private fun rememberExerciseCreationState(exerciseId: String?): ExerciseCreationState {
    val repository = rememberGymRepository()
    val scope = rememberCoroutineScope()
    val edits = rememberFiniteEditCoordinator()
    return remember(exerciseId, repository, scope, edits) {
        ExerciseCreationState(exerciseId, repository, scope, edits)
    }
}

@Composable
internal fun ExercisesScreen(
    modifier: Modifier = Modifier,
    onExerciseSelected: (String) -> Unit,
    onCreateExercise: () -> Unit,
    onEditCustomExercise: (String) -> Unit,
    initialFirstVisibleItemIndex: Int,
    initialFirstVisibleItemScrollOffset: Int,
    onListPositionChanged: (Int, Int) -> Unit,
    initialSearchQuery: String,
    initialEquipmentIds: Set<String>,
    initialMuscleGroupIds: Set<String>,
    onCatalogueStateChanged: (String, Set<String>, Set<String>) -> Unit,
    state: ExerciseCatalogueState = rememberExerciseCatalogueState(
        initialSearchQuery = initialSearchQuery,
        initialEquipmentIds = initialEquipmentIds,
        initialMuscleGroupIds = initialMuscleGroupIds,
    ),
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
    )
    LaunchedEffect(state.summaries) {
        if (
            state.summaries != null &&
            (initialFirstVisibleItemIndex > 0 || initialFirstVisibleItemScrollOffset > 0)
        ) {
            listState.scrollToItem(
                index = initialFirstVisibleItemIndex.coerceAtMost(
                    state.filteredExercises.lastIndex.coerceAtLeast(0),
                ),
                scrollOffset = initialFirstVisibleItemScrollOffset,
            )
        }
    }
    DisposableEffect(listState) {
        onDispose {
            onListPositionChanged(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
            onCatalogueStateChanged(
                state.searchQuery,
                state.selectedEquipmentIds,
                state.selectedMuscleGroupIds,
            )
        }
    }
    CatalogueDesignScreen(
        state = state,
        listState = listState,
        modifier = modifier,
        onCreate = onCreateExercise,
        onExerciseSelected = onExerciseSelected,
        onEdit = onEditCustomExercise,
    )
}

@Composable
internal fun ExercisePickerScreen(
    workoutId: String,
    modifier: Modifier = Modifier,
    onExercisesAdded: () -> Unit,
    onCancel: () -> Unit,
    state: ExerciseCatalogueState = rememberExerciseCatalogueState(),
) {
    CataloguePickerScreen(
        state = state,
        title = "Add exercises",
        confirmText = "Add selected (${state.selectedExerciseIds.size})",
        onConfirm = { state.addSelectedExercises(workoutId, onExercisesAdded) },
        onCancel = onCancel,
        modifier = modifier,
    )
}

@Composable
internal fun RoutineExercisePickerScreen(
    routineId: String,
    modifier: Modifier = Modifier,
    onExercisesAdded: () -> Unit,
    onCancel: () -> Unit,
    state: ExerciseCatalogueState = rememberExerciseCatalogueState(),
) {
    CataloguePickerScreen(
        state = state,
        title = "Add exercises",
        confirmText = "Add selected (${state.selectedExerciseIds.size})",
        onConfirm = { state.addSelectedExercisesToRoutine(routineId, onExercisesAdded) },
        onCancel = onCancel,
        modifier = modifier,
    )
}

@Composable
internal fun ExerciseReplacementPickerScreen(
    workoutExerciseId: String,
    modifier: Modifier = Modifier,
    onExerciseReplaced: () -> Unit,
    onCancel: () -> Unit,
    state: ExerciseCatalogueState = rememberExerciseCatalogueState(),
) {
    CataloguePickerScreen(
        state = state,
        title = "Replace exercise",
        confirmText = "Replace",
        onConfirm = { state.replaceSelectedExercise(workoutExerciseId, onExerciseReplaced) },
        onCancel = onCancel,
        modifier = modifier,
        singleSelection = true,
    )
}

@Composable
private fun CataloguePickerScreen(
    state: ExerciseCatalogueState,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
    singleSelection: Boolean = false,
) {
    BackHandler(onBack = onCancel)
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        Column(modifier.fillMaxSize().statusBarsPadding().imePadding().padding(top = grid.space(24))) {
            Row(
                Modifier.fillMaxWidth().background(GymSurface).padding(horizontal = grid.space(23)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkoutGridLabel(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, horizontalPadding = 0)
                WorkoutGridLabel(
                    "Cancel",
                    Modifier.clickable(role = Role.Button, onClick = onCancel),
                    color = GymAccent,
                    horizontalPadding = 0,
                )
            }
            Spacer(Modifier.height(grid.space(24)))
            ExerciseCatalogueContent(
                summaries = state.summaries,
                filteredExercises = state.filteredExercises,
                searchQuery = state.searchQuery,
                onSearchQueryChanged = state::updateSearchQuery,
                selectedEquipmentIds = state.selectedEquipmentIds,
                onEquipmentIdsChanged = state::updateEquipmentIds,
                selectedMuscleGroupIds = state.selectedMuscleGroupIds,
                onMuscleGroupIdsChanged = state::updateMuscleGroupIds,
                selectedExerciseIds = state.selectedExerciseIds,
                singleSelection = singleSelection,
                onExerciseSelected = if (singleSelection) state::selectExercise else state::toggleExercise,
                listState = rememberLazyListState(),
                bottomSpacing = grid.space(24),
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.fillMaxWidth().background(GymSurface).padding(horizontal = grid.space(23), vertical = grid.space(12))) {
                Button(
                    onClick = onConfirm,
                    enabled = if (singleSelection) state.selectedExerciseIds.size == 1 else state.selectedExerciseIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = grid.line),
                    shape = RectangleShape,
                    contentPadding = PaddingValues(grid.space(12)),
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}

@Composable
internal fun ExerciseDetailScreen(
    exerciseId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    state: ExerciseDetailState = rememberExerciseDetailState(exerciseId),
) {
    BackHandler(onBack = onBack)
    val details = state.details
    if (details == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val exercise = details.exercise
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp).offset(x = (-8).dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to exercises",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    exercise.name,
                    modifier = Modifier.padding(start = 4.dp).weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (exercise.isUserCreated) {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "Exercise options",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.width(128.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    menuExpanded = false
                                    onEdit(exercise.id)
                                },
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                },
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
        }
        item {
            ExerciseDetailValue(
                label = "Muscles",
                value = details.muscleGroups.joinToString(" • ") { it.name }.ifEmpty { "None" },
            )
        }
        item {
            ExerciseDetailValue(
                label = "Equipment",
                value = details.equipment.joinToString { it.name }.ifEmpty { "None" },
            )
        }
        exercise.category?.let { category ->
            item { ExerciseDetailValue("Category", category) }
        }
        exercise.difficultyLevel?.let { difficulty ->
            item { ExerciseDetailValue("Difficulty", difficulty) }
        }
        exercise.forceType?.let { forceType ->
            item { ExerciseDetailValue("Force", forceType) }
        }
        exercise.mechanics?.let { mechanics ->
            item { ExerciseDetailValue("Mechanics", mechanics) }
        }
        exercise.instructions?.takeIf { it.isNotEmpty() }?.let { instructions ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Instructions", style = MaterialTheme.typography.titleMedium)
                    instructions.forEachIndexed { index, instruction ->
                        Text(
                            "${index + 1}. $instruction",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        GymDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = "Delete exercise?",
            text = { Text("This will permanently delete ${exercise.name}.") },
            confirmAction = GymDialogAction(
                text = "Delete",
                onClick = { onDelete(exercise.id) },
                destructive = true,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = { showDeleteDialog = false },
            ),
        )
    }
}

@Composable
private fun ExerciseDetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CreateExerciseScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    exerciseId: String? = null,
    state: ExerciseCreationState = rememberExerciseCreationState(exerciseId),
) {
    var showDiscardDialog by remember { mutableStateOf(false) }
    fun requestBack() {
        if (state.shouldWarnOnBack) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(onBack = ::requestBack)
    val muscleOptions = state.muscleGroups.orEmpty().map { ExerciseFilterOption(it.id, it.name) }
    val equipmentOptions = state.equipment.orEmpty().map { ExerciseFilterOption(it.id, it.name) }
    val categoryOptions = state.categories.orEmpty().map { ExerciseFilterOption(it, it) }
    var selectionDialog by remember { mutableStateOf<ExerciseCreationSelection?>(null) }

    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        Scaffold(
            modifier = modifier.testTag("exercise-editor-screen"),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = grid.space(24))) {
                    Row(
                        Modifier.fillMaxWidth().background(GymSurface)
                            .padding(horizontal = grid.space(23)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = ::requestBack,
                            modifier = Modifier.size(grid.line),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to exercises",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(grid.space(24)).offset(x = -grid.space(3)),
                            )
                        }
                        WorkoutGridLabel(
                            if (exerciseId == null) "Create exercise" else "Edit exercise",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            horizontalPadding = 0,
                        )
                        WorkoutActionButton(
                            "Save", { state.requestSave(onSaved) },
                            Modifier.width(grid.space(if (grid.fontScale > 1f) 120 else 96)),
                        )
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding().testTag("exercise-editor-list"),
                contentPadding = PaddingValues(horizontal = grid.space(23), vertical = grid.space(24)),
                verticalArrangement = Arrangement.spacedBy(grid.space(24)),
            ) {
                item {
                    ExerciseCreationTextField(
                        label = "Name",
                        value = state.name,
                        onValueChange = state::updateName,
                        singleLine = true,
                    )
                }
                item {
                    ExerciseCreationSelectionButton(
                        label = "Primary muscle group",
                        value = state.muscleGroups
                            ?.firstOrNull { it.id == state.primaryMuscleGroupId }
                            ?.name,
                        onClick = { selectionDialog = ExerciseCreationSelection.PrimaryMuscle },
                    )
                }
                item {
                    ExerciseCreationSelectionButton(
                        label = "Secondary muscle group",
                        value = state.muscleGroups
                            ?.filter { it.id in state.secondaryMuscleGroupIds }
                            ?.joinToString { it.name }
                            ?.takeIf { it.isNotEmpty() },
                        onClick = { selectionDialog = ExerciseCreationSelection.SecondaryMuscles },
                    )
                }
                item {
                    ExerciseCreationSelectionButton(
                        label = "Equipment",
                        value = state.equipment?.firstOrNull { it.id == state.equipmentId }?.name,
                        onClick = { selectionDialog = ExerciseCreationSelection.Equipment },
                    )
                }
                item {
                    ExerciseCreationSelectionButton(
                        label = "Category",
                        value = state.category,
                        onClick = { selectionDialog = ExerciseCreationSelection.Category },
                    )
                }
                item {
                    ExerciseCreationTextField(
                        label = "Instructions",
                        value = state.instructions,
                        onValueChange = state::updateInstructions,
                        minLines = 4,
                    )
                }
            }
        }

        when (selectionDialog) {
            ExerciseCreationSelection.PrimaryMuscle -> ExerciseFilterDialog(
                title = "Primary muscle group",
                options = muscleOptions,
                selectedIds = state.primaryMuscleGroupId?.let(::setOf).orEmpty(),
                onDismiss = { selectionDialog = null },
                onApply = { selectedIds ->
                    state.updatePrimaryMuscleGroup(selectedIds.singleOrNull())
                    selectionDialog = null
                },
                singleSelection = true,
                confirmText = "Done",
                clearText = "Clear selection",
            )
            ExerciseCreationSelection.SecondaryMuscles -> ExerciseFilterDialog(
                title = "Secondary muscle groups",
                options = muscleOptions,
                selectedIds = state.secondaryMuscleGroupIds,
                onDismiss = { selectionDialog = null },
                onApply = { selectedIds ->
                    state.updateSecondaryMuscleGroups(selectedIds)
                    selectionDialog = null
                },
                confirmText = "Done",
                clearText = "Clear selection",
            )
            ExerciseCreationSelection.Equipment -> ExerciseFilterDialog(
                title = "Equipment",
                options = equipmentOptions,
                selectedIds = state.equipmentId?.let(::setOf).orEmpty(),
                onDismiss = { selectionDialog = null },
                onApply = { selectedIds ->
                    state.updateEquipment(selectedIds.singleOrNull())
                    selectionDialog = null
                },
                singleSelection = true,
                confirmText = "Done",
                clearText = "Clear selection",
            )
            ExerciseCreationSelection.Category -> ExerciseFilterDialog(
                title = "Category",
                options = categoryOptions,
                selectedIds = state.category?.let(::setOf).orEmpty(),
                onDismiss = { selectionDialog = null },
                onApply = { selectedIds ->
                    state.updateCategory(selectedIds.singleOrNull())
                    selectionDialog = null
                },
                singleSelection = true,
                confirmText = "Done",
                clearText = "Clear selection",
            )
            null -> Unit
        }

        if (showDiscardDialog) {
            GymDialog(
                onDismissRequest = { showDiscardDialog = false },
                title = "Discard exercise?",
                text = { Text("Going back will discard the exercise you are creating.") },
                confirmAction = GymDialogAction(
                    text = "Discard",
                    onClick = onBack,
                    destructive = true,
                ),
                dismissAction = GymDialogAction(
                    text = "Cancel",
                    onClick = { showDiscardDialog = false },
                ),
            )
        }

        if (state.showNameRequiredDialog) {
            GymDialog(
                onDismissRequest = state::dismissNameRequiredDialog,
                title = "Exercise name required",
                text = { Text("Enter a name before saving this exercise.") },
                confirmAction = GymDialogAction(
                    text = "OK",
                    onClick = state::dismissNameRequiredDialog,
                ),
            )
        }
    }
}

private enum class ExerciseCreationSelection {
    PrimaryMuscle,
    SecondaryMuscles,
    Equipment,
    Category,
}

@Composable
private fun ExerciseCreationTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    val grid = LocalWorkoutGrid.current!!
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().border(
            grid.space(2),
            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        color = GymBackground,
        shape = RectangleShape,
    ) {
        Column {
            WorkoutGridLabel(label, Modifier.fillMaxWidth(), color = WorkoutMuted)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label }
                    .testTag("exercise-field-${label.lowercase().replace(' ', '-')}")
                    .heightIn(min = maxOf(grid.line, grid.textLine * minLines + grid.space(24)))
                    .padding(horizontal = grid.space(12), vertical = grid.space(12)),
                singleLine = singleLine,
                minLines = minLines,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun ExerciseCreationSelectionButton(
    label: String,
    value: String?,
    onClick: () -> Unit,
) {
    val grid = LocalWorkoutGrid.current!!
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("exercise-selector-${label.lowercase().replace(' ', '-')}")
            .clickable(role = Role.Button, onClick = onClick),
        shape = RectangleShape,
        color = GymBackground,
        border = BorderStroke(grid.space(2), MaterialTheme.colorScheme.outline),
    ) {
        Column {
            WorkoutGridLabel(label, Modifier.fillMaxWidth(), color = WorkoutMuted)
            WorkoutGridLabel(
                "[${value ?: "Select..."}]", Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private data class ExerciseFilterOption(val id: String, val name: String)

@Composable
private fun ExerciseFilterDialog(
    title: String,
    options: List<ExerciseFilterOption>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
    singleSelection: Boolean = false,
    confirmText: String = "Show results",
    clearText: String = "Clear filters",
) {
    var pendingSelectedIds by remember(selectedIds) { mutableStateOf(selectedIds) }
    GymDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = {
            val grid = LocalWorkoutGrid.current!!
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(grid.space(12)),
                verticalArrangement = Arrangement.spacedBy(grid.space(12)),
            ) {
                items(options, key = { it.id }) { option ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = grid.line)
                            .testTag("filter-option-${option.id}")
                            .clickable {
                                pendingSelectedIds = if (option.id in pendingSelectedIds) {
                                    pendingSelectedIds - option.id
                                } else if (singleSelection) {
                                    setOf(option.id)
                                } else {
                                    pendingSelectedIds + option.id
                                }
                            },
                        color = if (option.id in pendingSelectedIds) {
                            GymSelectedTab
                        } else {
                            GymBackground
                        },
                        shape = RectangleShape,
                        border = BorderStroke(grid.space(2), MaterialTheme.colorScheme.outline),
                    ) {
                        WorkoutGridLabel(option.name, Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmAction = GymDialogAction(
            text = confirmText,
            onClick = { onApply(pendingSelectedIds) },
        ),
        dismissAction = GymDialogAction(
            text = clearText,
            onClick = { pendingSelectedIds = emptySet() },
        ),
    )
}

internal fun muscleGroupText(summary: ExerciseSummary): String {
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

internal fun ExerciseSummary.matchesSearch(query: String): Boolean {
    val searchTerm = query.trim()
    return searchTerm.isEmpty() || listOf(
        exercise.name,
        muscleGroups.joinToString { it.name },
        equipment.joinToString { it.name },
    ).any { it.contains(searchTerm, ignoreCase = true) }
}

internal fun ExerciseSummary.matchesFilters(
    equipmentIds: Set<String>,
    muscleGroupIds: Set<String>,
): Boolean =
    (equipmentIds.isEmpty() || equipment.any { it.id in equipmentIds }) &&
        (muscleGroupIds.isEmpty() || muscleGroups.any { it.id in muscleGroupIds })
