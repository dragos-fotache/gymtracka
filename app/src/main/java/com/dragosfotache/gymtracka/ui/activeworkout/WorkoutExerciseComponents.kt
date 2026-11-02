package com.dragosfotache.gymtracka.ui.activeworkout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutActionButton
import com.dragosfotache.gymtracka.ui.theme.WorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutPlaceholder
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.ui.util.formatKilograms
import com.dragosfotache.gymtracka.ui.util.parseKilograms
import com.dragosfotache.gymtracka.ui.util.reconcileRepetitionsText
import com.dragosfotache.gymtracka.ui.util.reconcileStringField
import com.dragosfotache.gymtracka.ui.util.reconcileWeightText
import kotlinx.coroutines.launch

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
) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExerciseMenu by remember { mutableStateOf(false) }
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
            Box {
                IconButton(
                    onClick = { showExerciseMenu = true },
                    modifier = Modifier.size(grid.line),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Options for ${exercise.workoutExercise.exerciseName}",
                        modifier = Modifier.size(grid.space(24)),
                    )
                }
                DropdownMenu(
                    expanded = showExerciseMenu,
                    onDismissRequest = { showExerciseMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Replace") },
                        onClick = {
                            showExerciseMenu = false
                            onExerciseReplacementRequested(exercise.workoutExercise.id)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            showExerciseMenu = false
                            showDeleteDialog = true
                        },
                    )
                }
            }
        }
        BasicTextField(
            value = notes,
            onValueChange = {
                // The local draft follows only accepted edits; refused input while paused
                // never alters domain-like drafts.
                if (onNotesChanged(exercise.workoutExercise.id, it)) notes = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = grid.line)
                .border(grid.space(2), MaterialTheme.colorScheme.outline)
                .padding(horizontal = grid.space(12), vertical = grid.space(12)),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            minLines = 1,
            maxLines = Int.MAX_VALUE,
            decorationBox = { innerTextField ->
                Box {
                    if (notes.isEmpty()) {
                        Text(
                            "Add notes...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WorkoutPlaceholder,
                        )
                    }
                    innerTextField()
                }
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
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete exercise?") },
            text = {
                Text("Remove ${exercise.workoutExercise.exerciseName} and all of its sets from this workout?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onExerciseDeleted()) showDeleteDialog = false
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
            Modifier.align(Alignment.CenterStart).width(grid.space(if (enlargedText) 48 else 30)),
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
            Modifier.align(Alignment.CenterStart).offset(x = grid.space(348)).width(grid.space(48)),
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
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
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
    var rowWidth by remember(workoutSet.id) { mutableIntStateOf(0) }
    var dragOffset by remember(workoutSet.id) { mutableFloatStateOf(0f) }
    var passedDeleteThreshold by remember(workoutSet.id) { mutableStateOf(false) }
    val swipeOffset = remember(workoutSet.id) { Animatable(0f) }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workout-set-row-${workoutSet.id}")
            .background(MaterialTheme.colorScheme.error)
            .onSizeChanged { rowWidth = it.width },
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = "Delete set",
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
        WorkoutSetColumns(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = swipeOffset.value }
                .pointerInput(workoutSet.id, rowWidth) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragOffset = swipeOffset.value
                            passedDeleteThreshold = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragAmount < 0f || dragOffset < 0f) {
                                change.consume()
                                dragOffset = (dragOffset + dragAmount).coerceIn(-rowWidth.toFloat(), 0f)
                                val passed = rowWidth > 0 && -dragOffset >= rowWidth / 2f
                                if (passed && !passedDeleteThreshold) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                passedDeleteThreshold = passed
                                scope.launch { swipeOffset.snapTo(dragOffset) }
                            }
                        },
                        onDragEnd = {
                            val shouldDelete = passedDeleteThreshold
                            scope.launch {
                                // A refused deletion snaps the row back so paused admission can
                                // never leave an existing set visually gone.
                                if (shouldDelete && onSetDeleted(workoutSet)) {
                                    swipeOffset.animateTo(-rowWidth.toFloat(), spring())
                                } else {
                                    swipeOffset.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { swipeOffset.animateTo(0f, spring()) }
                        },
                    )
                }
                .background(if (completed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background),
            number = {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = grid.textLine).background(MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${workoutSet.position + 1}")
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
                        .size(grid.line)
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
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun CompactSetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showBorder: Boolean,
    keyboardType: KeyboardType,
) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        modifier = modifier
            .height(grid.input)
            .then(
                if (showBorder) {
                    Modifier.border(grid.space(2), MaterialTheme.colorScheme.outline)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = grid.space(6)),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                innerTextField()
            }
        },
    )
}
