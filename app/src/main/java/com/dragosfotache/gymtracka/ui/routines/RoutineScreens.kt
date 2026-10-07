package com.dragosfotache.gymtracka.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.data.gym.RoutineStateRepository
import com.dragosfotache.gymtracka.ui.FiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberFiniteEditCoordinator
import com.dragosfotache.gymtracka.ui.rememberGymRepository
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseDetails
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.ui.activeworkout.RestDurationDialog
import com.dragosfotache.gymtracka.ui.activeworkout.RestDurationRow
import com.dragosfotache.gymtracka.ui.util.formatKilograms
import com.dragosfotache.gymtracka.ui.util.parseKilograms
import com.dragosfotache.gymtracka.ui.util.formatRestDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class RoutineListState(
    private val repository: RoutineStateRepository,
    private val scope: CoroutineScope,
    private val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
) {
    var routines by mutableStateOf<List<RoutineDetails>?>(null)
        private set
    var routineToDelete by mutableStateOf<RoutineDetails?>(null)
        private set

    init {
        load()
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
        return edits.launchEdit("routine-delete:$routineId") {
            repository.deleteRoutine(routineId)
            routineToDelete = null
            load()
        }
    }

    private fun load() {
        scope.launch { routines = repository.savedRoutineDetails() }
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
    private var nameInitialized = false
    private var refreshGeneration = 0
    private var nameSaveJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val generation = ++refreshGeneration
        scope.launch {
            val loaded = repository.routineDetails(routineId)
            if (generation != refreshGeneration) return@launch
            details = loaded
            if (!nameInitialized) {
                name = details?.routine?.name.orEmpty()
                nameInitialized = true
            }
        }
    }

    fun updateName(value: String): Job? {
        val previousSave = nameSaveJob
        val job = edits.launchEdit(
            key = "routine:$routineId:name",
            onAccepted = { name = value },
        ) {
            previousSave?.join()
            repository.updateRoutineName(routineId, value)
        }
        if (job != null) nameSaveJob = job
        return job
    }

    fun requestSave(onSaved: () -> Unit): Job? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            showNameRequiredDialog = true
            return null
        }
        return edits.launchEdit("routine:$routineId:save") {
            repository.saveRoutine(routineId, trimmedName)
            onSaved()
        }
    }

    fun dismissNameRequiredDialog() {
        showNameRequiredDialog = false
    }

    fun updateRestDuration(routineExerciseId: String, seconds: Int): Job? =
        edits.launchEdit(
            key = "routine-rest:$routineExerciseId",
            onAccepted = {
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

    fun addSet(exercise: RoutineExerciseDetails): Job? {
        val routineExerciseId = exercise.routineExercise.id
        val position = exercise.sets.size
        val previousSet = exercise.sets.lastOrNull()
        val previousRepetitions = previousSet?.repetitions
        val previousWeightKg = previousSet?.weightKg
        return edits.launchEdit("routine-sets:$routineExerciseId") {
            repository.addSetToRoutineExercise(
                routineExerciseId = routineExerciseId,
                position = position,
                repetitions = previousRepetitions,
                weightKg = previousWeightKg,
            )
            refresh()
        }
    }

    fun updateSet(set: RoutineSetEntity): Job? {
        val snapshot = set
        return edits.launchEdit("routine-set:${snapshot.id}") {
            repository.updateRoutineSet(snapshot)
            refresh()
        }
    }

    fun deleteSet(set: RoutineSetEntity): Job? {
        val snapshot = set
        return edits.launchEdit("routine-set:${snapshot.id}") {
            repository.deleteRoutineSet(snapshot)
            refresh()
        }
    }

    fun deleteExercise(exercise: RoutineExerciseDetails): Job? {
        val routineExerciseId = exercise.routineExercise.id
        return edits.launchEdit("routine-entry:$routineExerciseId") {
            repository.deleteRoutineExercise(routineExerciseId, routineId)
            refresh()
        }
    }
}

internal class RoutineDetailState(
    routineId: String,
    repository: RoutineStateRepository,
    scope: CoroutineScope,
) {
    var details by mutableStateOf<RoutineDetails?>(null)
        private set

    init {
        scope.launch { details = repository.routineDetails(routineId) }
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Routines",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Button(onClick = onNewRoutine, modifier = Modifier.fillMaxWidth()) {
            Text("New routine")
        }
        state.routines.orEmpty().forEach { routine ->
            RoutineCard(
                details = routine,
                onOpenDetails = { onRoutineSelected(routine.routine.id) },
                onEdit = { onEditRoutine(routine.routine.id) },
                onStart = { onStartRoutine(routine.routine.id) },
                onDelete = { state.requestDelete(routine) },
            )
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
    var menuExpanded by remember { mutableStateOf(false) }
    val exerciseNames = details.exercises.joinToString(", ") { it.routineExercise.exerciseName }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("routine-card-${details.routine.id}")
            .clickable(onClick = onOpenDetails),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    details.routine.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Routine options for ${details.routine.name}",
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
                                onEdit()
                            },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        )
                        DropdownMenuItem(
                            text = { Text("Delete routine", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        )
                    }
                }
            }
            Text(
                exerciseNames,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().testTag("routine-start-${details.routine.id}"),
            ) {
                Text("Start routine")
            }
        }
    }
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
                RoutineSetRow(set, onSetChanged, onSetDeleted)
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
        RestDurationDialog(
            initialRestSeconds = restSeconds,
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
