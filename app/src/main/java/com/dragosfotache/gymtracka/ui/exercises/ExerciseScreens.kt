package com.dragosfotache.gymtracka.ui.exercises

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.ExerciseSummary
import com.dragosfotache.gymtracka.data.gym.GymRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ExerciseCatalogueState(
    private val repository: GymRepository,
    private val scope: CoroutineScope,
) {
    var summaries by mutableStateOf<List<ExerciseSummary>?>(null)
        private set
    var searchQuery by mutableStateOf("")
        private set
    var selectedEquipmentIds by mutableStateOf(emptySet<String>())
        private set
    var selectedMuscleGroupIds by mutableStateOf(emptySet<String>())
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

    fun addSelectedExercises(workoutId: String, onAdded: () -> Unit) {
        if (selectedExerciseIds.isEmpty()) return
        scope.launch {
            repository.addExercisesToWorkout(workoutId, selectedExerciseIds)
            selectedExerciseIds = emptySet()
            onAdded()
        }
    }
}

@Composable
private fun rememberExerciseCatalogueState(): ExerciseCatalogueState {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { GymRepository(context) }
    val scope = rememberCoroutineScope()
    return remember(repository, scope) { ExerciseCatalogueState(repository, scope) }
}

@Composable
internal fun ExercisesScreen(
    modifier: Modifier = Modifier,
    state: ExerciseCatalogueState = rememberExerciseCatalogueState(),
) {
    ExerciseCatalogueContent(
        state = state,
        modifier = modifier,
        selectable = false,
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
private fun ExerciseCatalogueContent(
    state: ExerciseCatalogueState,
    selectable: Boolean,
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
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.filteredExercises, key = { it.exercise.id }) { summary ->
                val selected = summary.exercise.id in state.selectedExerciseIds
                ExerciseListItem(
                    summary = summary,
                    selected = selectable && selected,
                    onClick = if (selectable) {
                        { state.toggleExercise(summary.exercise.id) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ExerciseListItem(
    summary: ExerciseSummary,
    selected: Boolean,
    onClick: (() -> Unit)?,
) {
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
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
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
            modifier = Modifier.weight(1f),
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
            TextButton(onClick = { onApply(pendingSelectedIds) }) { Text("Show results") }
        },
        dismissButton = {
            TextButton(onClick = { pendingSelectedIds = emptySet() }) { Text("Clear filters") }
        },
        containerColor = Color(0xFFECEFF1),
    )
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
