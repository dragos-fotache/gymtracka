package com.dragosfotache.gymtracka.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The account runtime supplies controls without transferring session or sync ownership. */
@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    accountContent: (@Composable () -> Unit)? = null,
    serverConnectionState: ServerConnectionState? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings-screen")
            .verticalScroll(rememberScrollState())
            .padding(top = 24.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Text(
            "Settings",
            modifier = Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        SettingsSection("Connection", "settings-server") {
            ServerConnectionSection(serverConnectionState)
        }
        if (accountContent != null) {
            accountContent()
        } else {
            SettingsSection("Sync", "settings-sync") {
                Text("Pending changes: unavailable", modifier = Modifier.testTag("account-pending"))
                Text("Last success: unavailable", modifier = Modifier.testTag("account-last-success"))
            }
            SettingsSection("Account", "settings-account") {
                Text("Signed out", modifier = Modifier.testTag("account-authentication-status"))
                Text("Account controls are unavailable in this local session.")
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
    Column(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}
