package com.dragosfotache.gymtracka.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionStatus
import com.dragosfotache.gymtracka.ui.settings.SettingsAction
import com.dragosfotache.gymtracka.ui.settings.SettingsLabel
import com.dragosfotache.gymtracka.ui.settings.SettingsProgress
import com.dragosfotache.gymtracka.ui.settings.SettingsSection
import com.dragosfotache.gymtracka.ui.settings.accountNetworkActions
import com.dragosfotache.gymtracka.ui.theme.WorkoutMuted
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
            SettingsLabel(
                "Pending changes: unavailable",
                modifier = Modifier.testTag("account-pending"),
            )
            SettingsLabel(
                "Last success: unavailable",
                modifier = Modifier.testTag("account-last-success"),
                color = WorkoutMuted,
            )
        } else {
            SettingsLabel(
                "Pending changes: ${status.pendingCount}",
                modifier = Modifier.testTag("account-pending"),
            )
            SettingsLabel(
                "Last success: ${formatLastSuccess(status.lastSuccessMillis)}",
                modifier = Modifier.testTag("account-last-success"),
                color = WorkoutMuted,
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
            presentation.sync?.let { SettingsLabel(it, modifier = Modifier.testTag("account-sync-status")) }
        }
    }
    SettingsSection("Account", "settings-account") {
        SettingsLabel("Signed in", modifier = Modifier.testTag("account-authentication-status"))
        SettingsAction(
            text = "Reauthenticate",
            onClick = onReauthenticate,
            enabled = actions.reauthenticate,
            modifier = Modifier.testTag("account-reauthenticate"),
        )
        if (presentation.authentication != "Signed in") {
            SettingsLabel(
                presentation.authentication,
                modifier = Modifier.testTag("account-authentication-message"),
                color = if (presentation.authenticationNeedsAttention) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!signInAvailable) SettingsLabel("Foreground sign-in is unavailable on this install.", color = WorkoutMuted)
        if (uiState.busy) SettingsProgress()
        if (uiState.busy) {
            SettingsAction(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.testTag("account-cancel"),
            )
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
