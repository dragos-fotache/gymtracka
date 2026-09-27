package com.dragosfotache.gymtracka.ui.activeworkout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.ui.util.formatKilograms
import com.dragosfotache.gymtracka.ui.util.parseKilograms
import kotlinx.coroutines.launch

@Composable
internal fun WorkoutExerciseCard(
    exercise: WorkoutExerciseDetails,
    onNotesChanged: (String, String) -> Unit,
    onRestDurationChanged: (String, Int) -> Unit,
    onSetAdded: () -> Unit,
    onSetChanged: (WorkoutSetEntity, (() -> Unit)?) -> Unit,
    onSetDeleted: (WorkoutSetEntity) -> Unit,
    onExerciseDeleted: () -> Unit,
    onExerciseReplacementRequested: (String) -> Unit,
    onRestStarted: (WorkoutSetEntity, Int) -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExerciseMenu by remember { mutableStateOf(false) }
    var showRestDurationDialog by remember { mutableStateOf(false) }
    var notes by remember(exercise.workoutExercise.id) {
        mutableStateOf(exercise.workoutExercise.notes)
    }
    var restSeconds by remember(exercise.workoutExercise.id) {
        mutableIntStateOf(exercise.workoutExercise.restDurationSeconds)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Options for ${exercise.workoutExercise.exerciseName}",
                            modifier = Modifier.size(20.dp),
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
                    notes = it
                    onNotesChanged(exercise.workoutExercise.id, it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                minLines = 1,
                maxLines = Int.MAX_VALUE,
                decorationBox = { innerTextField ->
                    Box {
                        if (notes.isEmpty()) {
                            Text(
                                "Add notes...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            RestDurationRow(restSeconds = restSeconds, onClick = { showRestDurationDialog = true })
            SetTableHeader()
            exercise.sets.forEach { workoutSet ->
                WorkoutSetRow(
                    workoutSet = workoutSet,
                    restDurationSeconds = restSeconds,
                    onSetChanged = onSetChanged,
                    onSetDeleted = onSetDeleted,
                    onRestStarted = onRestStarted,
                )
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
                        showDeleteDialog = false
                        onExerciseDeleted()
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
        RestDurationDialog(
            initialRestSeconds = restSeconds,
            onDismiss = { showRestDurationDialog = false },
            onDone = {
                restSeconds = it
                onRestDurationChanged(exercise.workoutExercise.id, it)
                showRestDurationDialog = false
            },
        )
    }
}

@Composable
private fun SetTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SET", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(0.5f))
        Spacer(Modifier.width(8.dp))
        Text("PREVIOUS", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(0.75f))
        Spacer(Modifier.width(8.dp))
        Text("WEIGHT", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("REPS", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = "Completed")
        }
    }
}

@Composable
private fun WorkoutSetRow(
    workoutSet: WorkoutSetEntity,
    restDurationSeconds: Int,
    onSetChanged: (WorkoutSetEntity, (() -> Unit)?) -> Unit,
    onSetDeleted: (WorkoutSetEntity) -> Unit,
    onRestStarted: (WorkoutSetEntity, Int) -> Unit,
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
    var completed by remember(workoutSet.id) { mutableStateOf(workoutSet.isCompleted) }
    var rowWidth by remember(workoutSet.id) { mutableIntStateOf(0) }
    var dragOffset by remember(workoutSet.id) { mutableFloatStateOf(0f) }
    var passedDeleteThreshold by remember(workoutSet.id) { mutableStateOf(false) }
    val swipeOffset = remember(workoutSet.id) { Animatable(0f) }
    val canComplete = parseKilograms(weightText) != null && repetitionsText.toIntOrNull() != null
    val isCheckEnabled = canComplete || completed
    val completedRowColor = Color(0xFFE1F3E5)
    val completedCheckColor = Color(0xFF2E7D32)
    val rowShape = RoundedCornerShape(12.dp)

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
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.error)
            .onSizeChanged { rowWidth = it.width },
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = "Delete set",
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
        Row(
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
                                swipeOffset.animateTo(
                                    if (shouldDelete) -rowWidth.toFloat() else 0f,
                                    spring(),
                                )
                                if (shouldDelete) onSetDeleted(workoutSet)
                            }
                        },
                        onDragCancel = {
                            scope.launch { swipeOffset.animateTo(0f, spring()) }
                        },
                    )
                }
                .clip(rowShape)
                .background(if (completed) completedRowColor else MaterialTheme.colorScheme.background)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(0.5f).size(36.dp),
                color = if (completed) completedRowColor else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${workoutSet.position + 1}", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("–", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(0.75f))
            Spacer(Modifier.width(8.dp))
            CompactSetTextField(
                value = weightText,
                onValueChange = {
                    weightText = it
                    onSetChanged(editedSet(), null)
                },
                modifier = Modifier.weight(1f).testTag("workout-set-weight-${workoutSet.id}"),
                showBorder = !completed,
                keyboardType = KeyboardType.Decimal,
            )
            Spacer(Modifier.width(8.dp))
            CompactSetTextField(
                value = repetitionsText,
                onValueChange = {
                    repetitionsText = it
                    onSetChanged(editedSet(), null)
                },
                modifier = Modifier.weight(1f).testTag("workout-set-reps-${workoutSet.id}"),
                showBorder = !completed,
                keyboardType = KeyboardType.Number,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .testTag("workout-set-complete-${workoutSet.id}")
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            completed -> completedCheckColor
                            isCheckEnabled -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                    )
                    .clickable(
                        enabled = isCheckEnabled,
                        onClick = {
                            focusManager.clearFocus()
                            val isNowCompleted = !completed
                            completed = isNowCompleted
                            val updatedSet = editedSet(isNowCompleted)
                            onSetChanged(
                                updatedSet,
                                if (isNowCompleted && restDurationSeconds > 0) {
                                    { onRestStarted(updatedSet, restDurationSeconds) }
                                } else {
                                    null
                                },
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Complete set",
                    tint = when {
                        completed -> Color.White
                        isCheckEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    },
                )
            }
        }
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
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        modifier = modifier
            .height(36.dp)
            .then(
                if (showBorder) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.surfaceVariant, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                innerTextField()
            }
        },
    )
}
