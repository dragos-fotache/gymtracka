package com.dragosfotache.gymtracka.ui.exercises

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.ExerciseSummary
import com.dragosfotache.gymtracka.data.gym.ExerciseDetails
import com.dragosfotache.gymtracka.data.gym.EquipmentEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseStateRepository
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.data.gym.MuscleGroupEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ExerciseCatalogueState(
    private val repository: ExerciseStateRepository,
    private val scope: CoroutineScope,
    initialSearchQuery: String = "",
    initialEquipmentIds: Set<String> = emptySet(),
    initialMuscleGroupIds: Set<String> = emptySet(),
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

    init {
        scope.launch { summaries = repository.exerciseSummaries() }
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

    fun addSelectedExercises(workoutId: String, onAdded: () -> Unit) {
        if (selectedExerciseIds.isEmpty()) return
        scope.launch {
            repository.addExercisesToWorkout(workoutId, selectedExerciseIds)
            selectedExerciseIds = emptySet()
            onAdded()
        }
    }

    fun addSelectedExercisesToRoutine(routineId: String, onAdded: () -> Unit) {
        if (selectedExerciseIds.isEmpty()) return
        scope.launch {
            repository.addExercisesToRoutine(routineId, selectedExerciseIds)
            selectedExerciseIds = emptySet()
            onAdded()
        }
    }

    fun replaceSelectedExercise(workoutExerciseId: String, onReplaced: () -> Unit) {
        val exerciseId = selectedExerciseIds.singleOrNull() ?: return
        scope.launch {
            repository.replaceWorkoutExercise(workoutExerciseId, exerciseId)
            selectedExerciseIds = emptySet()
            onReplaced()
        }
    }

    fun deleteCustomExercise(exerciseId: String) {
        scope.launch {
            repository.deleteCustomExercise(exerciseId)
            summaries = repository.exerciseSummaries()
        }
    }
}

internal class ExerciseDetailState(
    exerciseId: String,
    repository: ExerciseStateRepository,
    scope: CoroutineScope,
) {
    var details by mutableStateOf<ExerciseDetails?>(null)
        private set

    init {
        scope.launch { details = repository.exerciseDetails(exerciseId) }
    }
}

internal class ExerciseCreationState(
    private val exerciseId: String?,
    private val repository: ExerciseStateRepository,
    private val scope: CoroutineScope,
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

    fun requestSave(onSaved: () -> Unit) {
        if (name.trim().isEmpty()) {
            showNameRequiredDialog = true
            return
        }
        scope.launch {
            if (exerciseId == null) {
                repository.createCustomExercise(
                    name = name.trim(),
                    primaryMuscleGroupId = primaryMuscleGroupId,
                    secondaryMuscleGroupIds = secondaryMuscleGroupIds,
                    equipmentId = equipmentId,
                    category = category,
                    instructions = instructions,
                )
            } else {
                repository.updateCustomExercise(
                    exerciseId = exerciseId,
                    name = name.trim(),
                    primaryMuscleGroupId = primaryMuscleGroupId,
                    secondaryMuscleGroupIds = secondaryMuscleGroupIds,
                    equipmentId = equipmentId,
                    category = category,
                    instructions = instructions,
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
    return remember(repository, scope, initialSearchQuery, initialEquipmentIds, initialMuscleGroupIds) {
        ExerciseCatalogueState(
            repository = repository,
            scope = scope,
            initialSearchQuery = initialSearchQuery,
            initialEquipmentIds = initialEquipmentIds,
            initialMuscleGroupIds = initialMuscleGroupIds,
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
    return remember(exerciseId, repository, scope) {
        ExerciseCreationState(exerciseId, repository, scope)
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
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Exercises",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            Button(onClick = onCreateExercise) { Text("Create new") }
        }
        ExerciseCatalogueContent(
            state = state,
            modifier = Modifier.weight(1f),
            selectable = false,
            onExerciseSelected = onExerciseSelected,
            onEditCustomExercise = onEditCustomExercise,
            onDeleteCustomExercise = state::deleteCustomExercise,
            listState = listState,
        )
    }
}

@Composable
internal fun ExercisePickerScreen(
    workoutId: String,
    modifier: Modifier = Modifier,
    onExercisesAdded: () -> Unit,
    onCancel: () -> Unit,
    state: ExerciseCatalogueState = rememberExerciseCatalogueState(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), shadowElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Add exercises", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 3.dp) {
                Button(
                    onClick = { state.addSelectedExercises(workoutId, onExercisesAdded) },
                    enabled = state.selectedExerciseIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text("Add selected (${state.selectedExerciseIds.size})")
                }
            }
        },
    ) { innerPadding ->
        ExerciseCatalogueContent(
            state = state,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            selectable = true,
        )
    }
}

@Composable
internal fun RoutineExercisePickerScreen(
    routineId: String,
    modifier: Modifier = Modifier,
    onExercisesAdded: () -> Unit,
    onCancel: () -> Unit,
    state: ExerciseCatalogueState = rememberExerciseCatalogueState(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), shadowElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Add exercises", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 3.dp) {
                Button(
                    onClick = {
                        state.addSelectedExercisesToRoutine(routineId, onExercisesAdded)
                    },
                    enabled = state.selectedExerciseIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text("Add selected (${state.selectedExerciseIds.size})")
                }
            }
        },
    ) { innerPadding ->
        ExerciseCatalogueContent(
            state = state,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            selectable = true,
        )
    }
}

@Composable
internal fun ExerciseReplacementPickerScreen(
    workoutExerciseId: String,
    modifier: Modifier = Modifier,
    onExerciseReplaced: () -> Unit,
    onCancel: () -> Unit,
    state: ExerciseCatalogueState = rememberExerciseCatalogueState(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), shadowElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Replace exercise", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 3.dp) {
                Button(
                    onClick = {
                        state.replaceSelectedExercise(workoutExerciseId, onExerciseReplaced)
                    },
                    enabled = state.selectedExerciseIds.size == 1,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text("Replace")
                }
            }
        },
    ) { innerPadding ->
        ExerciseCatalogueContent(
            state = state,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            selectable = true,
            singleSelection = true,
        )
    }
}

@Composable
private fun ExerciseCatalogueContent(
    state: ExerciseCatalogueState,
    selectable: Boolean,
    singleSelection: Boolean = false,
    onExerciseSelected: ((String) -> Unit)? = null,
    onEditCustomExercise: ((String) -> Unit)? = null,
    onDeleteCustomExercise: ((String) -> Unit)? = null,
    listState: LazyListState? = null,
    modifier: Modifier = Modifier,
) {
    val exercises = state.summaries
    if (exercises == null) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        ExerciseSearchField(
            query = state.searchQuery,
            onQueryChanged = state::updateSearchQuery,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ExerciseFilterControls(
            exercises = exercises,
            selectedEquipmentIds = state.selectedEquipmentIds,
            onEquipmentIdsChanged = state::updateEquipmentIds,
            selectedMuscleGroupIds = state.selectedMuscleGroupIds,
            onMuscleGroupIdsChanged = state::updateMuscleGroupIds,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyColumn(
            state = listState ?: rememberLazyListState(),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            itemsIndexed(
                state.filteredExercises,
                key = { _, summary -> summary.exercise.id },
            ) { index, summary ->
                val selected = summary.exercise.id in state.selectedExerciseIds
                ExerciseListItem(
                    summary = summary,
                    selected = selectable && selected,
                    onClick = if (selectable) {
                        {
                            if (singleSelection) {
                                state.selectExercise(summary.exercise.id)
                            } else {
                                state.toggleExercise(summary.exercise.id)
                            }
                        }
                    } else {
                        onExerciseSelected?.let { onSelected ->
                            { onSelected(summary.exercise.id) }
                        }
                    },
                    onEdit = onEditCustomExercise?.let { onEdit ->
                        { onEdit(summary.exercise.id) }
                    },
                    onDelete = onDeleteCustomExercise?.let { onDelete ->
                        { onDelete(summary.exercise.id) }
                    },
                )
                if (index < state.filteredExercises.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
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
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete exercise?") },
            text = { Text("This will permanently delete ${exercise.name}.") },
            confirmButton = {
                TextButton(onClick = { onDelete(exercise.id) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
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
    exerciseId: String? = null,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: () -> Unit,
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

    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), shadowElevation = 3.dp) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                ) {
                    IconButton(
                        onClick = ::requestBack,
                        modifier = Modifier.align(Alignment.CenterStart).size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to exercises",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        if (exerciseId == null) "Create exercise" else "Edit exercise",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = { state.requestSave(onSaved) },
                        modifier = Modifier.align(Alignment.CenterEnd),
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard exercise?") },
            text = { Text("Going back will discard the exercise you are creating.") },
            confirmButton = {
                TextButton(onClick = onBack) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (state.showNameRequiredDialog) {
        AlertDialog(
            onDismissRequest = state::dismissNameRequiredDialog,
            title = { Text("Exercise name required") },
            text = { Text("Enter a name before saving this exercise.") },
            confirmButton = {
                TextButton(onClick = state::dismissNameRequiredDialog) { Text("OK") }
            },
        )
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF1F2F5),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().testTag(
                    "exercise-field-${label.lowercase().replace(' ', '-')}",
                ),
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF1F2F5),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                value ?: "Select...",
                style = MaterialTheme.typography.bodyMedium,
                color = if (value == null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExerciseListItem(
    summary: ExerciseSummary,
    selected: Boolean,
    onClick: (() -> Unit)?,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("exercise-row-${summary.exercise.id}")
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.background,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
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
                } else if (summary.exercise.isUserCreated && onEdit != null && onDelete != null) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
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
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                },
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            )
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
            Text(
                text = muscleGroupText(summary),
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete exercise?") },
            text = { Text("This will permanently delete ${summary.exercise.name}.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete?.invoke()
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
}

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
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp).testTag("exercise-search"),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "Search exercises",
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

private data class ExerciseFilterOption(val id: String, val name: String)

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
                selectedEquipmentIds,
                equipmentOptions,
                "All Equipment",
                "Equipment",
            ),
            selected = selectedEquipmentIds.isNotEmpty(),
            onClick = { showEquipmentDialog = true },
            onClear = if (selectedEquipmentIds.isNotEmpty()) {
                { onEquipmentIdsChanged(emptySet()) }
            } else {
                null
            },
            modifier = Modifier.weight(1f).testTag("filter-equipment"),
        )
        ExerciseFilterButton(
            text = filterButtonText(
                selectedMuscleGroupIds,
                muscleGroupOptions,
                "All Muscles",
                "Muscles",
            ),
            selected = selectedMuscleGroupIds.isNotEmpty(),
            onClick = { showMuscleGroupDialog = true },
            onClear = if (selectedMuscleGroupIds.isNotEmpty()) {
                { onMuscleGroupIdsChanged(emptySet()) }
            } else {
                null
            },
            modifier = Modifier.weight(1f).testTag("filter-muscles"),
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
    onClear: (() -> Unit)?,
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
        Text(text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Icon(Icons.Filled.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp))
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
    singleSelection: Boolean = false,
    confirmText: String = "Show results",
    clearText: String = "Clear filters",
) {
    var pendingSelectedIds by remember(selectedIds) { mutableStateOf(selectedIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
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
                            .testTag("filter-option-${option.id}")
                            .clip(RoundedCornerShape(16.dp))
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
                                option.name,
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
            TextButton(onClick = { onApply(pendingSelectedIds) }) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = { pendingSelectedIds = emptySet() }) { Text(clearText) }
        },
        containerColor = Color(0xFFECEFF1),
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
