package com.dragosfotache.gymtracka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.ui.theme.GymSelectedTab

@Composable
internal fun GymBottomNavigation(appState: GymAppState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Navigation has its own proportions, outside the page's 6 by 12 grid.
        val scale = maxWidth.value / 448f
        Column(
            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                .navigationBarsPadding().padding(bottom = (2 * scale).dp),
        ) {
            Box(Modifier.fillMaxWidth().height((2 * scale).dp).background(MaterialTheme.colorScheme.outline))
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                NavigationTab(
                    "Exercises", "nav-exercises", Icons.AutoMirrored.Filled.List,
                    appState.destination == Destination.Exercises, appState::showExercises,
                    Modifier.weight(1f), scale,
                )
                NavigationTab(
                    "Workout", "nav-workout", Icons.Filled.FitnessCenter,
                    appState.destination == Destination.Workout, appState::showWorkouts,
                    Modifier.weight(1f), scale,
                )
                NavigationTab(
                    "History", "nav-history", Icons.Filled.History,
                    appState.destination == Destination.History, appState::showHistory,
                    Modifier.weight(1f), scale,
                )
                NavigationTab(
                    "Settings", "nav-settings", Icons.Filled.Settings,
                    appState.destination == Destination.Settings, appState::showSettings,
                    Modifier.weight(1f), scale,
                )
            }
        }
    }
}

@Composable
private fun NavigationTab(
    label: String,
    tag: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    scale: Float,
) {
    Box(
        modifier.height(maxOf(48.dp, (58 * scale).dp))
            .background(if (selected) GymSelectedTab else Color.Transparent)
            .testTag(tag)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((36 * scale).dp),
        )
    }
}
