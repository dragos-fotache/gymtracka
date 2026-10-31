package com.dragosfotache.gymtracka.ui.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionStatus
import com.dragosfotache.gymtracka.ui.settings.SettingsSection
import com.dragosfotache.gymtracka.ui.settings.accountNetworkActions
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AccountSettingsContent(
    uiState: AccountStartupUiState,
    connectionStatus: ServerConnectionStatus,
    signInAvailable: Boolean,
    deliveryStatus: com.dragosfotache.gymtracka.data.sync.session.AppSessionDeliveryStatus,
    onReauthenticate: () -> Unit,
    onCancel: () -> Unit,
    syncState: AccountSyncState? = null,
    retrySeconds: Long? = null,
) {
    val syncUi = syncState?.uiState?.collectAsState()?.value
    val actions = accountNetworkActions(
        connection = connectionStatus,
        signInAvailable = signInAvailable,
        accountBusy = uiState.busy,
        syncBusy = syncUi?.busy == true,
        reauthenticationRequired = deliveryStatus ==
            com.dragosfotache.gymtracka.data.sync.session.AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED ||
            syncUi?.error == AccountSyncError.REAUTHENTICATION_REQUIRED,
    )
    val presentation = accountStatusPresentation(uiState, syncUi, deliveryStatus)
    SettingsSection("Sync", "settings-sync") {
        val status = uiState.syncStatus
        if (status == null) {
            Text(
                "Pending changes: unavailable",
                modifier = Modifier.testTag("account-pending"),
            )
            Text(
                "Last success: unavailable",
                modifier = Modifier.testTag("account-last-success"),
            )
        } else {
            Text(
                "Pending changes: ${status.pendingCount}",
                modifier = Modifier.testTag("account-pending"),
            )
            Text(
                "Last success: ${formatLastSuccess(status.lastSuccessMillis)}",
                modifier = Modifier.testTag("account-last-success"),
            )
        }
        if (syncState != null) {
            AccountSyncSection(
                syncState,
                networkActionsEnabled = actions.sync,
                rejectedWorkAvailable = presentation.rejectedWork,
                message = retrySeconds?.let(::accountRetryText) ?: presentation.sync,
            )
        } else {
            presentation.sync?.let { Text(it, modifier = Modifier.testTag("account-sync-status")) }
        }
    }
    SettingsSection("Account", "settings-account") {
        Text("Signed in", modifier = Modifier.testTag("account-authentication-status"))
        TextButton(
            onClick = onReauthenticate,
            enabled = actions.reauthenticate,
            modifier = Modifier.testTag("account-reauthenticate"),
        ) {
            Icon(Icons.Filled.ManageAccounts, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Reauthenticate")
        }
        if (presentation.authentication != "Signed in") {
            Text(
                presentation.authentication,
                modifier = Modifier.testTag("account-authentication-message"),
                color = if (presentation.authenticationNeedsAttention) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!signInAvailable) Text("Foreground sign-in is unavailable on this install.")
        if (uiState.busy) CircularProgressIndicator()
        if (uiState.busy) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("account-cancel"),
            ) { Text("Cancel") }
        }
    }
}

private fun formatLastSuccess(millis: Long?): String {
    if (millis == null) return "unknown"
    return try {
        DateFormat.getDateTimeInstance().format(Date(millis))
    } catch (_: Exception) {
        "unavailable"
    }
}
