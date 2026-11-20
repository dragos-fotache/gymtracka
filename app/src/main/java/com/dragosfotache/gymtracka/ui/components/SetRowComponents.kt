package com.dragosfotache.gymtracka.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGrid
import kotlinx.coroutines.launch

/** Shared set-row gesture: reveal red, cross halfway for haptics, then delete on accepted release. */
@Composable
internal fun SwipeToDeleteSetRow(
    setId: String,
    onDelete: () -> Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    deleteDescription: String = "Delete set",
    content: @Composable () -> Unit,
) {
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val delete by rememberUpdatedState(onDelete)
    var rowWidth by remember(setId) { mutableIntStateOf(0) }
    var dragOffset by remember(setId) { mutableFloatStateOf(0f) }
    var passedDeleteThreshold by remember(setId) { mutableStateOf(false) }
    val swipeOffset = remember(setId) { Animatable(0f) }
    Box(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error)
            .onSizeChanged { rowWidth = it.width }
            .semantics { customActions = listOf(CustomAccessibilityAction(deleteDescription) { delete() }) },
    ) {
        if (swipeOffset.value < 0f) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = grid.space(12)).size(grid.space(24)),
            )
        }
        Box(
            Modifier.fillMaxWidth().graphicsLayer { translationX = swipeOffset.value }
                .pointerInput(setId, rowWidth) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragOffset = swipeOffset.value
                            passedDeleteThreshold = false
                        },
                        onHorizontalDrag = { change, amount ->
                            if (amount < 0f || dragOffset < 0f) {
                                change.consume()
                                dragOffset = (dragOffset + amount).coerceIn(-rowWidth.toFloat(), 0f)
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
                                // Refused admission restores the existing row instead of hiding it.
                                if (shouldDelete && delete()) {
                                    swipeOffset.animateTo(-rowWidth.toFloat(), spring())
                                } else {
                                    swipeOffset.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = { scope.launch { swipeOffset.animateTo(0f, spring()) } },
                    )
                }
                .background(backgroundColor),
        ) { content() }
    }
}

@Composable
internal fun CompactSetTextField(
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
        modifier = modifier.height(grid.input)
            .then(if (showBorder) Modifier.border(grid.space(2), MaterialTheme.colorScheme.outline) else Modifier)
            .padding(horizontal = grid.space(6)),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { innerTextField() }
        },
    )
}
