package com.dragosfotache.gymtracka.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.dragosfotache.gymtracka.ui.theme.LocalWorkoutGrid
import com.dragosfotache.gymtracka.ui.theme.WorkoutGridLabel
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
import com.dragosfotache.gymtracka.ui.theme.WorkoutScreenStyle

/** The account runtime supplies controls without transferring session or sync ownership. */
@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    accountContent: (@Composable () -> Unit)? = null,
    serverConnectionState: ServerConnectionState? = null,
) {
    WorkoutScreenStyle {
        val grid = LocalWorkoutGrid.current!!
        Column(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .testTag("settings-screen").padding(top = grid.space(24)),
        ) {
            WorkoutGridLabel(
                "Settings",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = grid.space(23)).semantics { heading() },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                horizontalPadding = 0,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().testTag("settings-content")
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = grid.space(23), vertical = grid.space(24)),
                verticalArrangement = Arrangement.spacedBy(grid.space(24)),
            ) {
                SettingsSection("Connection", "settings-server") {
                    ServerConnectionSection(serverConnectionState)
                }
                if (accountContent != null) {
                    accountContent()
                } else {
                    SettingsSection("Sync", "settings-sync") {
                        SettingsLabel("Pending changes: unavailable", Modifier.testTag("account-pending"))
                        SettingsLabel("Last success: unavailable", Modifier.testTag("account-last-success"), color = WorkoutMuted)
                    }
                    SettingsSection("Account", "settings-account") {
                        SettingsLabel("Signed out", Modifier.testTag("account-authentication-status"))
                        SettingsLabel("Account controls are unavailable in this local session.", color = WorkoutMuted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val grid = LocalWorkoutGrid.current!!
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(grid.space(2), MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = grid.space(12)).padding(bottom = grid.space(12)),
        ) {
            SettingsLabel(
                title,
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}
