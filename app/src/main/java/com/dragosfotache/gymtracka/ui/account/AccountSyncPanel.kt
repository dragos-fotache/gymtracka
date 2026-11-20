package com.dragosfotache.gymtracka.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.dragosfotache.gymtracka.data.sync.session.AccountRecoveryConfirmation
import com.dragosfotache.gymtracka.ui.settings.SettingsAction
import com.dragosfotache.gymtracka.ui.settings.SettingsLabel
import com.dragosfotache.gymtracka.ui.settings.SettingsProgress

/**
 * foreground/edit/manual sync controls. Reads only the advisory controller snapshot;
 * destructive actions stay disabled while owned work runs. Recovery warnings use explicit
 * request-relative tracking: Resolve captures the observed token and arms before invoking
 * prepare, and only a nonnull token different from that baseline is adopted, with the arm
 * consumed. Stage and recovery confirm callbacks act only on the exact displayed value and
 * refuse when the local selection changed. Authority or token turnover clears stale local
 * visibility instead of consenting another token. Dismissing a warning has zero deletion
 * effects, and re-preparation stays deliberately available. No raw IDs or exception
 * displays. Bracketed actions retain stable content descriptions and enabled state.
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
    Column {
        if (syncUi.busy) {
            SettingsAction(
                text = "Cancel",
                onClick = { syncState.cancel() },
                modifier = Modifier.testTag("account-sync-cancel"),
                description = "Cancel sync",
            )
        } else {
            SettingsAction(
                text = "Sync now",
                onClick = { syncState.syncNow() },
                enabled = networkActionsEnabled,
                modifier = Modifier.testTag("account-sync-now"),
            )
        }
        if (syncUi.busy) SettingsProgress(modifier = Modifier.testTag("account-sync-progress"))
        message?.let {
            SettingsLabel(it, modifier = Modifier.testTag("account-sync-status"))
        }
        if (observedStageId != null) {
            if (message == null) {
                SettingsLabel("A retained cloud download needs to restart before synchronization can continue.")
            }
            SettingsAction(
                text = "Restart download",
                onClick = { pendingStageId = observedStageId },
                enabled = networkActionsEnabled && !syncUi.busy,
                modifier = Modifier.testTag("account-restart-stage"),
                description = "Restart staged download",
            )
            // Captured-value discipline: the confirm callback below acts only on the exact
            // stage value displayed here, never on a later snapshot read.
            val displayedStage = pendingStageId
            if (displayedStage != null && displayedStage == observedStageId) {
                SettingsLabel("The staged download will restart from the beginning. Local unsynchronized changes are kept.")
                Column {
                    SettingsAction(
                        text = "Restart",
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
                    )
                    SettingsAction(text = "Back", onClick = { pendingStageId = null })
                }
            }
        }
        // Displayed-token discipline: warning and callbacks act only on the exact locally
        // captured token; a changed selection refuses instead of confirming a later one.
        val displayedToken = pendingToken
        syncUi.recoveryStatus?.let {
            SettingsLabel(recoveryStatusText(it), modifier = Modifier.testTag("account-recovery-status"))
        }
        if (displayedToken == null && rejectedWorkAvailable) {
            SettingsLabel("The server rejected changes. Resolve them to resume sync; your local changes are kept until you confirm.")
            SettingsAction(
                text = "Resolve rejected changes",
                onClick = {
                    recoveryBaseline = observedToken
                    recoveryArmed = true
                    syncState.prepareRecovery()
                },
                enabled = networkActionsEnabled && !syncUi.busy,
                modifier = Modifier.testTag("account-recovery-prepare"),
            )
        } else if (displayedToken != null) {
            SettingsLabel(
                text = "Only these ${displayedToken.pendingCount} unsynchronized ordinary changes will be " +
                    "discarded and the complete cloud snapshot reloaded. Already accepted server " +
                    "writes cannot be undone.",
                modifier = Modifier.testTag("account-recovery-warning"),
            )
            Column {
                SettingsAction(
                    text = "Discard and reload",
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
                    destructive = true,
                    modifier = Modifier.testTag("account-recovery-confirm"),
                )
                SettingsAction(
                    text = "Keep changes",
                    onClick = {
                        if (pendingToken === displayedToken) {
                            pendingToken = null
                            recoveryArmed = false
                            recoveryBaseline = null
                        }
                    },
                    modifier = Modifier.testTag("account-recovery-cancel"),
                )
            }
        }
    }
}
