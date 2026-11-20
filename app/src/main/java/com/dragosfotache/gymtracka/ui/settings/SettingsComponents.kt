package com.dragosfotache.gymtracka.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel

@Composable
internal fun SettingsLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    if (LocalWorkoutGrid.current == null) {
        Text(text, modifier, color = color, fontWeight = fontWeight)
    } else {
        WorkoutGridLabel(
            text, modifier.semantics(mergeDescendants = true) {}, color = color,
            fontWeight = fontWeight, horizontalPadding = 0,
        )
    }
}

@Composable
internal fun SettingsAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    description: String = text,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val semantics = modifier.semantics { contentDescription = description }
    if (LocalWorkoutGrid.current == null) {
        TextButton(
            onClick, semantics, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = color),
        ) { Text(text) }
    } else {
        WorkoutGridLabel(
            "[$text]",
            semantics.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            color = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            horizontalPadding = 0,
        )
    }
}

@Composable
internal fun SettingsProgress(modifier: Modifier = Modifier) {
    val grid = LocalWorkoutGrid.current
    if (grid == null) {
        CircularProgressIndicator(modifier)
    } else {
        Box(Modifier.fillMaxWidth().height(grid.line), contentAlignment = Alignment.CenterStart) {
            LinearProgressIndicator(
                modifier = modifier.fillMaxWidth().height(grid.space(2)),
                strokeCap = StrokeCap.Butt,
            )
        }
    }
}
