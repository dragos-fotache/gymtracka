package com.dragosfotache.gymtracka.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
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

/**
 * foreground/edit/manual sync controls. Reads only the advisory controller snapshot;
 * destructive actions stay disabled while owned work runs. Recovery warnings use explicit
 * request-relative tracking: Resolve captures the observed token and arms before invoking
 * prepare, and only a nonnull token different from that baseline is adopted, with the arm
 * consumed. Stage and recovery confirm callbacks act only on the exact displayed value and
 * refuse when the local selection changed. Authority or token turnover clears stale local
 * visibility instead of consenting another token. Dismissing a warning has zero deletion
 * effects, and re-preparation stays deliberately available. No raw IDs or exception
 * displays. Material icons with content descriptions preserve the established
 * accessibility patterns.
 */
@Composable
internal fun AccountSyncSection(
    syncState: AccountSyncState,
    networkActionsEnabled: Boolean = true,
    rejectedWorkAvailable: Boolean = false,
    message: String? = null,
) {
    val syncUi by syncState.uiState.collectAsState()
    var pendingStageId by remember { mutableStateOf<String?>(null) }
    var pendingToken by remember { mutableStateOf<AccountRecoveryConfirmation?>(null) }
    // Request-relative recovery tracking: the token observed when Resolve is pressed plus
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
    LaunchedEffect(observedToken, recoveryArmed, recoveryBaseline, syncUi.busy) {
        if (recoveryArmed) {
            // A null/unchanged token while preparation is pending is not a completed
            // decision. Only this request's fresh token can open its warning.
            if (observedToken != null && observedToken !== recoveryBaseline) {
                pendingToken = observedToken
                recoveryArmed = false
                recoveryBaseline = null
            } else if (!syncUi.busy) {
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
                    enabled = networkActionsEnabled,
                    modifier = Modifier.testTag("account-sync-now"),
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = "Sync now")
                    Text("Sync now")
                }
            }
        }
        if (syncUi.busy) CircularProgressIndicator(modifier = Modifier.testTag("account-sync-progress"))
        message?.let {
            Text(it, modifier = Modifier.testTag("account-sync-status"))
        }
        if (observedStageId != null) {
            if (message == null) {
                Text("A retained cloud download needs to restart before synchronization can continue.")
            }
            TextButton(
                onClick = { pendingStageId = observedStageId },
                enabled = networkActionsEnabled && !syncUi.busy,
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
                            if (pendingStageId == displayedStage &&
                                syncState.uiState.value.retainedStageId == displayedStage
                            ) {
                                syncState.restartStage(displayedStage)
                                pendingStageId = null
                            }
                        },
                        enabled = networkActionsEnabled && !syncUi.busy && pendingStageId == syncUi.retainedStageId,
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
        syncUi.recoveryStatus?.let {
            Text(recoveryStatusText(it), modifier = Modifier.testTag("account-recovery-status"))
        }
        if (displayedToken == null && rejectedWorkAvailable) {
            Text("The server rejected changes. Resolve them to resume sync; your local changes are kept until you confirm.")
            TextButton(
                onClick = {
                    recoveryBaseline = observedToken
                    recoveryArmed = true
                    syncState.prepareRecovery()
                },
                enabled = networkActionsEnabled && !syncUi.busy,
                modifier = Modifier.testTag("account-recovery-prepare"),
            ) {
                Text("Resolve rejected changes")
            }
        } else if (displayedToken != null) {
            Text(
                text = "Only these ${displayedToken.pendingCount} unsynchronized ordinary changes will be " +
                    "discarded and the complete cloud snapshot reloaded. Already accepted server " +
                    "writes cannot be undone.",
                modifier = Modifier.testTag("account-recovery-warning"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (pendingToken === displayedToken &&
                            syncState.uiState.value.recovery === displayedToken
                        ) {
                            syncState.confirmRecovery(displayedToken)
                            pendingToken = null
                            recoveryArmed = false
                            recoveryBaseline = null
                        }
                    },
                    enabled = networkActionsEnabled && !syncUi.busy,
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
