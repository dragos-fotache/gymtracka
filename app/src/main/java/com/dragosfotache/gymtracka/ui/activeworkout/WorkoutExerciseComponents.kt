package com.dragosfotache.gymtracka.ui.activeworkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import com.dragosfotache.gymtracka.ui.components.CompactSetTextField
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.components.SwipeToDeleteSetRow
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutActionButton
import com.dragosfotache.gymtracka.ui.theme.WorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutInlineActions
import com.dragosfotache.gymtracka.ui.theme.WorkoutTextAction
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutPlaceholder
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.ui.util.formatKilograms
import com.dragosfotache.gymtracka.ui.util.parseKilograms
import com.dragosfotache.gymtracka.ui.util.reconcileRepetitionsText
import com.dragosfotache.gymtracka.ui.util.reconcileStringField
import com.dragosfotache.gymtracka.ui.util.reconcileWeightText

@Composable
internal fun WorkoutExerciseCard(
    exercise: WorkoutExerciseDetails,
    onNotesChanged: (String, String) -> Boolean,
    onRestDurationChanged: (String, Int) -> Boolean,
    onSetAdded: () -> Unit,
    onSetChanged: (WorkoutSetEntity, (() -> Unit)?) -> Boolean,
    onSetDeleted: (WorkoutSetEntity) -> Boolean,
    onExerciseDeleted: () -> Boolean,
    onExerciseReplacementRequested: (String) -> Unit,
    onRestStarted: (WorkoutSetEntity, Int) -> Unit,
    onRestCancelled: (String) -> Unit,
    onExerciseHistoryRequested: (String) -> Unit = {},
) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExerciseMenu by remember(exercise.workoutExercise.id) { mutableStateOf(false) }
    var showRestDurationDialog by remember { mutableStateOf(false) }
    var notes by remember(exercise.workoutExercise.id) {
        mutableStateOf(exercise.workoutExercise.notes)
    }
    var restSeconds by remember(exercise.workoutExercise.id) {
        mutableIntStateOf(exercise.workoutExercise.restDurationSeconds)
    }
    // Same-ID projection refresh adopts the latest persisted notes/rest without
    // recreating focus nodes or resetting menu/dialog/swipe state. The accepted
    // local draft is preserved while its write is outstanding because the owning
    // state invalidates older loads synchronously and drains before reading.
    LaunchedEffect(exercise.workoutExercise.id, exercise.workoutExercise.notes) {
        notes = reconcileStringField(notes, exercise.workoutExercise.notes)
    }
    LaunchedEffect(exercise.workoutExercise.id, exercise.workoutExercise.restDurationSeconds) {
        restSeconds = exercise.workoutExercise.restDurationSeconds
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = grid.line),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                exercise.workoutExercise.exerciseName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = { showExerciseMenu = !showExerciseMenu },
                modifier = Modifier.width(grid.space(48)).heightIn(min = grid.line)
                    .semantics { stateDescription = if (showExerciseMenu) "Expanded" else "Collapsed" },
            ) {
                Icon(
                    if (showExerciseMenu) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Options for ${exercise.workoutExercise.exerciseName}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(grid.space(24)),
                )
            }
        }
        if (showExerciseMenu) {
            WorkoutExerciseActions(
                exerciseName = exercise.workoutExercise.exerciseName,
                modifier = Modifier.testTag("workout-exercise-actions-${exercise.workoutExercise.id}"),
                onReplace = {
                    showExerciseMenu = false
                    onExerciseReplacementRequested(exercise.workoutExercise.id)
                },
                onHistory = {
                    showExerciseMenu = false
                    onExerciseHistoryRequested(exercise.workoutExercise.exerciseId)
                },
                onDelete = {
                    showExerciseMenu = false
                    showDeleteDialog = true
                },
            )
        }
        WorkoutNotesField(
            notes = notes,
            onNotesChanged = {
                // The local draft follows only accepted edits; refused input while paused
                // never alters domain-like drafts.
                if (onNotesChanged(exercise.workoutExercise.id, it)) notes = it
            },
        )
        RestDurationRow(restSeconds = restSeconds, onClick = { showRestDurationDialog = true })
        SetTableHeader()
        exercise.sets.forEach { workoutSet ->
            // Stable row identity by set ID: an earlier remote insert/remove
            // reshuffles positional slots, so without this the focused set's
            // focus, swipe and raw draft state could move or drop even when
            // its ID survives. Never keyed by epoch.
            key(workoutSet.id) {
                WorkoutSetRow(
                    workoutSet = workoutSet,
                    restDurationSeconds = restSeconds,
                    onSetChanged = onSetChanged,
                    onSetDeleted = onSetDeleted,
                    onRestStarted = onRestStarted,
                    onRestCancelled = onRestCancelled,
                )
            }
        }
        WorkoutActionButton("Add set", onSetAdded, Modifier.fillMaxWidth())
    }

    if (showDeleteDialog) {
        GymDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = "Delete exercise?",
            text = {
                Text("Remove ${exercise.workoutExercise.exerciseName} and all of its sets from this workout?")
            },
            confirmAction = GymDialogAction(
                text = "Delete",
                onClick = {
                    if (onExerciseDeleted()) showDeleteDialog = false
                },
                destructive = true,
            ),
            dismissAction = GymDialogAction(
                text = "Cancel",
                onClick = { showDeleteDialog = false },
            ),
        )
    }

    if (showRestDurationDialog) {
        // Snapshot the dialog entry value while open: a same-ID publication
        // refresh must not rewrite the unconfirmed dialog draft. The snapshot
        // resets only on dialog close/reopen or true entry replacement.
        val dialogInitialRest = remember(exercise.workoutExercise.id) { restSeconds }
        RestDurationDialog(
            initialRestSeconds = dialogInitialRest,
            onDismiss = { showRestDurationDialog = false },
            onDone = {
                if (onRestDurationChanged(exercise.workoutExercise.id, it)) {
                    restSeconds = it
                    showRestDurationDialog = false
                }
            },
        )
    }
}

@Composable
private fun WorkoutExerciseActions(
    exerciseName: String,
    modifier: Modifier = Modifier,
    onReplace: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    WorkoutInlineActions(
        left = WorkoutTextAction("[Replace]", onReplace, "Replace $exerciseName"),
        middle = WorkoutTextAction("[History]", onHistory, "History for $exerciseName"),
        right = WorkoutTextAction("[Delete]", onDelete, "Delete $exerciseName", destructive = true),
        modifier = modifier,
    )
}

@Composable
private fun WorkoutNotesField(notes: String, onNotesChanged: (String) -> Unit) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    BoxWithConstraints(
        Modifier.fillMaxWidth().border(grid.space(2), MaterialTheme.colorScheme.outline),
    ) {
        val padding = grid.space(12)
        val textWidth = (constraints.maxWidth - with(density) { (padding * 2).roundToPx() }).coerceAtLeast(1)
        val lines = measurer.measure(
            notes.ifEmpty { "Add notes..." }, style, constraints = Constraints(maxWidth = textWidth),
        ).lineCount
        val rowHeight = maxOf(grid.line, grid.textLine * lines + grid.space(24))
        BasicTextField(
            value = notes,
            onValueChange = onNotesChanged,
            modifier = Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = padding),
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (notes.isEmpty()) {
                        Text("Add notes...", style = style, color = WorkoutPlaceholder)
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun SetTableHeader() {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    Box {
        WorkoutSetColumns(
            number = { Text("#", color = WorkoutMuted) },
            weight = { Text("WEIGHT", color = WorkoutMuted, fontWeight = FontWeight.Bold) },
            repetitions = { Text("REPS", color = WorkoutMuted, fontWeight = FontWeight.Bold) },
            completion = {
                Icon(
                    Icons.Filled.Check, contentDescription = "Completed",
                    tint = WorkoutMuted, modifier = Modifier.size(grid.space(24)),
                )
            },
        )
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(grid.space(4))
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

/** Shared column origins keep the header, inputs and completion controls on the same grid. */
@Composable
private fun WorkoutSetColumns(
    number: @Composable () -> Unit,
    weight: @Composable () -> Unit,
    repetitions: @Composable () -> Unit,
    completion: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    val enlargedText = grid.fontScale > 1.2f
    Box(modifier.fillMaxWidth().heightIn(min = grid.line)) {
        Box(
            Modifier.align(Alignment.CenterStart).width(grid.space(if (enlargedText) 48 else 36)),
            contentAlignment = Alignment.Center,
        ) { number() }
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = grid.space(if (enlargedText) 48 else 90))
                .width(grid.space(if (enlargedText) 144 else 84)),
            contentAlignment = Alignment.Center,
        ) { weight() }
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = grid.space(if (enlargedText) 216 else 234))
                .width(grid.space(if (enlargedText) 96 else 60)),
            contentAlignment = Alignment.Center,
        ) { repetitions() }
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = grid.space(366)).width(grid.space(36)),
            contentAlignment = Alignment.Center,
        ) { completion() }
    }
}

@Composable
private fun WorkoutSetRow(
    workoutSet: WorkoutSetEntity,
    restDurationSeconds: Int,
    onSetChanged: (WorkoutSetEntity, (() -> Unit)?) -> Boolean,
    onSetDeleted: (WorkoutSetEntity) -> Boolean,
    onRestStarted: (WorkoutSetEntity, Int) -> Unit,
    onRestCancelled: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var weightText by remember(workoutSet.id) {
        mutableStateOf(workoutSet.weightKg?.let(::formatKilograms).orEmpty())
    }
    var repetitionsText by remember(workoutSet.id) {
        mutableStateOf(workoutSet.repetitions?.toString().orEmpty())
    }
    // Same-ID projection refresh retains accepted raw numeric text when the
    // incoming value sanitizes equal (for example `12.00` or incomplete input
    // storing null, including accepted negative/nonfinite drafts whose persisted
    // value is null) and replaces genuinely different persisted values without
    // clearing focus.
    LaunchedEffect(workoutSet.id, workoutSet.weightKg) {
        weightText = reconcileWeightText(weightText, workoutSet.weightKg)
    }
    LaunchedEffect(workoutSet.id, workoutSet.repetitions) {
        repetitionsText = reconcileRepetitionsText(repetitionsText, workoutSet.repetitions)
    }
    // Presentation follows the persisted result: re-key on the stored flag so a
    // refresh that forced incomplete (invalid inputs, newer uncheck) cannot
    // leave a stale checked box. Draft strings stay keyed on the row ID so
    // refreshes never overwrite in-progress typing or steal focus.
    var completed by remember(workoutSet.id, workoutSet.isCompleted) {
        mutableStateOf(workoutSet.isCompleted)
    }
    // Completion needs finite nonnegative kilograms and a nonnegative integer
    // repetition count, matching the repository validity rule. Valid values
    // never auto-check: only the checkbox toggles the local flag.
    val canComplete = parseKilograms(weightText)?.let { it >= 0.0 && it.isFinite() } == true &&
        repetitionsText.toIntOrNull()?.let { it >= 0 } == true
    val isCheckEnabled = canComplete || completed
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()

    fun editedSet(isCompleted: Boolean = completed): WorkoutSetEntity = workoutSet.copy(
        weightKg = parseKilograms(weightText),
        repetitions = repetitionsText.toIntOrNull(),
        isCompleted = isCompleted,
        restSeconds = if (isCompleted) workoutSet.restSeconds else null,
    )

    SwipeToDeleteSetRow(
        setId = workoutSet.id,
        onDelete = { onSetDeleted(workoutSet) },
        modifier = Modifier.testTag("workout-set-row-${workoutSet.id}"),
        backgroundColor = if (completed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        deleteDescription = "Delete set ${workoutSet.position + 1}",
    ) {
        WorkoutSetColumns(
            modifier = Modifier.fillMaxWidth(),
            number = {
                Box(
                    Modifier.size(grid.input).background(MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${workoutSet.position + 1}", fontSize = (18 * grid.scale).sp)
                }
            },
            weight = {
                CompactSetTextField(
                    value = weightText,
                    onValueChange = {
                        val candidate = editedSet().copy(
                            weightKg = parseKilograms(it),
                            repetitions = repetitionsText.toIntOrNull(),
                        )
                        if (onSetChanged(candidate, null)) weightText = it
                    },
                    modifier = Modifier.fillMaxWidth().testTag("workout-set-weight-${workoutSet.id}"),
                    showBorder = !completed,
                    keyboardType = KeyboardType.Decimal,
                )
            },
            repetitions = {
                CompactSetTextField(
                    value = repetitionsText,
                    onValueChange = {
                        val candidate = editedSet().copy(
                            weightKg = parseKilograms(weightText),
                            repetitions = it.toIntOrNull(),
                        )
                        if (onSetChanged(candidate, null)) repetitionsText = it
                    },
                    modifier = Modifier.fillMaxWidth().testTag("workout-set-reps-${workoutSet.id}"),
                    showBorder = !completed,
                    keyboardType = KeyboardType.Number,
                )
            },
            completion = {
                Box(
                    modifier = Modifier
                        .requiredSize(maxOf(48.dp, grid.line))
                        .testTag("workout-set-complete-${workoutSet.id}")
                        .semantics { contentDescription = "Complete set ${workoutSet.position + 1}" }
                        .toggleable(
                            value = completed,
                            enabled = isCheckEnabled,
                            role = Role.Checkbox,
                            onValueChange = {
                                focusManager.clearFocus()
                                val isNowCompleted = !completed
                                val updatedSet = editedSet(isNowCompleted)
                                // The local checked flag follows only accepted edits; refused
                                // completion changes neither the draft nor the timer.
                                if (onSetChanged(
                                        updatedSet,
                                        if (isNowCompleted && restDurationSeconds > 0) {
                                            { onRestStarted(updatedSet, restDurationSeconds) }
                                        } else {
                                            null
                                        },
                                    )
                                ) {
                                    if (!isNowCompleted) {
                                        // Unchecking ends only the matching running
                                        // timer; the uncheck write already cleared
                                        // recorded rest, so nothing is persisted here.
                                        onRestCancelled(workoutSet.id)
                                    }
                                    completed = isNowCompleted
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (completed) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(grid.space(24)),
                        )
                    } else {
                        Box(
                            Modifier.size(grid.space(36)).background(
                                MaterialTheme.colorScheme.primary.copy(alpha = if (isCheckEnabled) 1f else 0.35f),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(grid.space(24)),
                            )
                        }
                    }
                }
            },
        )
    }
}
