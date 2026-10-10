package com.dragosfotache.gymtracka.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.sync.session.AccountRecoveryConfirmation
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationStop

/**
 * R13d foreground/edit/manual sync controls. Reads only the advisory controller snapshot;
 * destructive actions stay disabled while owned work runs. Recovery warnings use explicit
 * request-relative tracking: Review captures the observed token and arms before invoking
 * prepare, and only a nonnull token different from that baseline is adopted, with the arm
 * consumed. Stage and recovery confirm callbacks act only on the exact displayed value and
 * refuse when the local selection changed. Authority or token turnover clears stale local
 * visibility instead of consenting another token. Dismissing a warning has zero deletion
 * effects, and re-preparation stays deliberately available. No raw IDs or exception
 * displays. Material icons with content descriptions preserve the established
 * accessibility patterns.
 */
@Composable
internal fun AccountSyncSection(syncState: AccountSyncState) {
    val syncUi by syncState.uiState.collectAsState()
    var pendingStageId by remember { mutableStateOf<String?>(null) }
    var pendingToken by remember { mutableStateOf<AccountRecoveryConfirmation?>(null) }
    // Request-relative recovery tracking: the token observed when Review is pressed plus
    // an arming flag set before prepare is invoked. The effect adopts only a nonnull token
    // different from that request baseline, so a repeated prepare round-trip can neither
    // wipe an already-displayed token nor auto-adopt an unrelated later one.
    var recoveryBaseline by remember { mutableStateOf<AccountRecoveryConfirmation?>(null) }
    var recoveryArmed by remember { mutableStateOf(false) }
    val observedStageId = syncUi.retainedStageId
    val observedToken = syncUi.recovery
    LaunchedEffect(observedStageId) {
        if (pendingStageId != null && pendingStageId != observedStageId) {
            pendingStageId = null
        }
    }
    LaunchedEffect(observedToken, recoveryArmed, recoveryBaseline) {
        if (recoveryArmed) {
            // A null/unchanged token while preparation is pending is not a completed
            // decision. Only this request's fresh token can open its warning.
            if (observedToken != null && observedToken !== recoveryBaseline) {
                pendingToken = observedToken
                recoveryArmed = false
                recoveryBaseline = null
            }
        } else if (pendingToken != null && observedToken !== pendingToken) {
            // Turnover or an unrelated token replacement retires the displayed warning;
            // it never automatically adopts the replacement.
            pendingToken = null
            recoveryBaseline = null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (syncUi.busy) {
                TextButton(
                    onClick = { syncState.cancel() },
                    modifier = Modifier.testTag("account-sync-cancel"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel sync")
                    Text("Cancel")
                }
            } else {
                TextButton(
                    onClick = { syncState.syncNow() },
                    modifier = Modifier.testTag("account-sync-now"),
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = "Sync now")
                    Text("Sync now")
                }
            }
        }
        syncUi.stop?.let { stop ->
            Text(
                text = fixedSyncStop(stop),
                modifier = Modifier.testTag("account-sync-stop"),
            )
        }
        syncUi.error?.let { error ->
            Text(
                text = fixedSyncError(error),
                modifier = Modifier.testTag("account-sync-error"),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (observedStageId != null) {
            TextButton(
                onClick = { pendingStageId = observedStageId },
                enabled = !syncUi.busy,
                modifier = Modifier.testTag("account-restart-stage"),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Restart staged download")
                Text("Restart download")
            }
            // Captured-value discipline: the confirm callback below acts only on the exact
            // stage value displayed here, never on a later snapshot read.
            val displayedStage = pendingStageId
            if (displayedStage != null && displayedStage == observedStageId) {
                Text("The staged download will restart from the beginning. Local unsynchronized changes are kept.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            if (pendingStageId == displayedStage) {
                                syncState.restartStage(displayedStage)
                                pendingStageId = null
                            }
                        },
                        enabled = !syncUi.busy && pendingStageId == syncUi.retainedStageId,
                        modifier = Modifier.testTag("account-restart-confirm"),
                    ) {
                        Text("Restart")
                    }
                    TextButton(onClick = { pendingStageId = null }) {
                        Text("Back")
                    }
                }
            }
        }
        // Displayed-token discipline: warning and callbacks act only on the exact locally
        // captured token; a changed selection refuses instead of confirming a later one.
        val displayedToken = pendingToken
        if (displayedToken == null) {
            TextButton(
                onClick = {
                    recoveryBaseline = observedToken
                    recoveryArmed = true
                    syncState.prepareRecovery()
                },
                enabled = !syncUi.busy,
                modifier = Modifier.testTag("account-recovery-prepare"),
            ) {
                Text("Review unsent changes")
            }
        } else {
            Text(
                text = "Only these ${displayedToken.pendingCount} unsynchronized ordinary changes will be " +
                    "discarded and the complete cloud snapshot reloaded. Already accepted server " +
                    "writes cannot be undone.",
                modifier = Modifier.testTag("account-recovery-warning"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (pendingToken === displayedToken) {
                            syncState.confirmRecovery(displayedToken)
                            pendingToken = null
                            recoveryArmed = false
                            recoveryBaseline = null
                        }
                    },
                    enabled = !syncUi.busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag("account-recovery-confirm"),
                ) {
                    Text("Discard and reload")
                }
                TextButton(
                    onClick = {
                        if (pendingToken === displayedToken) {
                            pendingToken = null
                            recoveryArmed = false
                            recoveryBaseline = null
                        }
                    },
                    modifier = Modifier.testTag("account-recovery-cancel"),
                ) {
                    Text("Keep changes")
                }
            }
        }
    }
}

private fun fixedSyncStop(stop: AppReconciliationStop): String =
    when (stop) {
        AppReconciliationStop.TAIL -> "Sync complete."
        AppReconciliationStop.PROTECTED -> "New changes arrived during sync and were kept."
        AppReconciliationStop.PUSH_STOPPED -> "Sync paused during upload. You can retry manually."
        AppReconciliationStop.PULL_STOPPED -> "Sync paused during download. You can retry manually."
        AppReconciliationStop.SNAPSHOT_STOPPED -> "Snapshot download paused. You can retry manually."
        AppReconciliationStop.SNAPSHOT_RESTART_REQUIRED -> "Snapshot expired. Restart the staged download."
        AppReconciliationStop.RETAINED_STAGE -> "A staged download is waiting. Restart it to continue."
    }

private fun fixedSyncError(error: AccountSyncError): String =
    when (error) {
        AccountSyncError.UNAVAILABLE -> "Sync unavailable. Your visible data is unchanged."
        AccountSyncError.REAUTHENTICATION_REQUIRED ->
            "Sign in again for delivery. Offline editing remains available."
        AccountSyncError.STALE_CONFIRMATION -> "That action is no longer current. Try again."
    }
