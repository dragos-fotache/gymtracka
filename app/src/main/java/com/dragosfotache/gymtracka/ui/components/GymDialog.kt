package com.dragosfotache.gymtracka.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutContentStyle
import com.dragosfotache.gymtracka.ui.theme.WorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel

internal data class GymDialogAction(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val showProgress: Boolean = false,
    val modifier: Modifier = Modifier,
)

/** All app-owned modals share a square outlined surface and explicit left/right actions. */
@Composable
internal fun GymDialog(
    onDismissRequest: () -> Unit,
    confirmAction: GymDialogAction,
    modifier: Modifier = Modifier,
    title: String? = null,
    dismissAction: GymDialogAction? = null,
    text: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val grid = LocalWorkoutGrid.current ?: WorkoutGrid(windowWidth.value / 448f, density.fontScale)
    WorkoutContentStyle(grid) {
        Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(
                Modifier.padding(horizontal = grid.space(23), vertical = grid.space(24))
                    .widthIn(max = 560.dp).fillMaxWidth().testTag("gym-dialog"),
            ) {
                Surface(
                    modifier = modifier.fillMaxWidth().heightIn(max = maxHeight),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(grid.space(2), MaterialTheme.colorScheme.outline),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.padding(grid.space(24))) {
                        if (title != null) {
                            Text(title, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(grid.space(24)))
                        }
                        Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text() }
                        Spacer(Modifier.height(grid.space(24)))
                        GymDialogActions(confirmAction, dismissAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun GymDialogActions(confirmAction: GymDialogAction, dismissAction: GymDialogAction?) {
    val grid = LocalWorkoutGrid.current!!
    Layout(
        modifier = Modifier.fillMaxWidth().testTag("gym-dialog-actions"),
        content = {
            dismissAction?.let { GymDialogTextAction(it) }
            GymDialogTextAction(confirmAction)
        },
    ) { measurables, constraints ->
        val actions = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val stacked = actions.size == 2 && actions.sumOf { it.width } + grid.space(24).roundToPx() > constraints.maxWidth
        val gap = if (stacked) grid.space(12).roundToPx() else 0
        val height = if (stacked) actions.sumOf { it.height } + gap else actions.maxOf { it.height }
        layout(constraints.maxWidth, height) {
            actions.forEachIndexed { index, action ->
                val confirm = index == actions.lastIndex
                action.placeRelative(
                    x = if (confirm) constraints.maxWidth - action.width else 0,
                    y = if (stacked && confirm) height - action.height else 0,
                )
            }
        }
    }
}

@Composable
private fun GymDialogTextAction(action: GymDialogAction) {
    val grid = LocalWorkoutGrid.current!!
    val color = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        action.destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = action.modifier.clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(grid.space(12)),
    ) {
        if (action.showProgress) CircularProgressIndicator(Modifier.size(grid.space(24)), color = color)
        WorkoutGridLabel("[${action.text}]", color = color, horizontalPadding = 0)
    }
}
