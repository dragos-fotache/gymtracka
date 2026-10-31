package com.dragosfotache.gymtracka.ui.account

import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationStop
import com.dragosfotache.gymtracka.data.sync.session.AppSessionDeliveryStatus

/** One explanation per action. Metadata and connection observations never grant account authority. */
internal data class AccountStatusPresentation(
    val authentication: String,
    val authenticationNeedsAttention: Boolean,
    val sync: String?,
    val rejectedWork: Boolean,
)

internal fun accountStatusPresentation(
    startup: AccountStartupUiState,
    sync: AccountSyncUiState?,
    delivery: AppSessionDeliveryStatus,
): AccountStatusPresentation {
    val requiresAuthentication = delivery == AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED ||
        sync?.error == AccountSyncError.REAUTHENTICATION_REQUIRED
    val authMessage = when {
        startup.busy -> "Authenticating…"
        startup.error != null -> activeAuthenticationError(startup.error)
        requiresAuthentication ->
            "Session needs reauthentication. Sign in again to resume sync. Offline editing remains available."
        else -> "Signed in"
    }
    val authProblem = requiresAuthentication || startup.error != null
    val status = startup.syncStatus
    val rejected = status?.hasRejectedOrdinaryWork == true
    val syncMessage = when {
        sync?.busy == true -> "Syncing…"
        requiresAuthentication -> null
        sync?.error == AccountSyncError.CANCELLED -> "Sync cancelled. Changes already accepted by the server are kept."
        sync?.error == AccountSyncError.STALE_CONFIRMATION ->
            "That sync action is no longer current. Try Sync now again."
        sync?.error == AccountSyncError.UNAVAILABLE -> "Sync failed"
        sync?.retainedStageId != null || rejected -> null // Explanation belongs beside its recovery action.
        status?.hasRetainedError == true -> "A change needs attention before sync can continue. Try Sync now."
        sync?.stop == AppReconciliationStop.PROTECTED -> "New changes arrived during sync and were kept."
        sync?.stop == AppReconciliationStop.PUSH_STOPPED -> "Sync failed"
        sync?.stop == AppReconciliationStop.PULL_STOPPED -> "Sync failed"
        sync?.stop == AppReconciliationStop.SNAPSHOT_STOPPED -> "Sync failed"
        sync?.stop == AppReconciliationStop.SNAPSHOT_RESTART_REQUIRED ||
            sync?.stop == AppReconciliationStop.RETAINED_STAGE -> "A cloud download needs to be restarted."
        delivery == AppSessionDeliveryStatus.RETRY_REQUIRED -> "Sync failed"
        cleanQueue(status, startup, sync, authProblem) -> "All changes synced"
        sync?.stop == AppReconciliationStop.TAIL && status != null && status.pendingCount > 0L ->
            "New changes are waiting to sync."
        else -> null
    }
    return AccountStatusPresentation(authMessage, authProblem, syncMessage, rejected)
}

private fun cleanQueue(
    status: AccountSyncStatus?,
    startup: AccountStartupUiState,
    sync: AccountSyncUiState?,
    authProblem: Boolean,
): Boolean = status != null && status.pendingCount == 0L && status.lastSuccessMillis != null &&
    !status.hasRetainedError && !status.hasRejectedOrdinaryWork && !startup.busy && !authProblem &&
    sync?.error == null && sync?.recovery == null &&
    (sync?.recoveryStatus == null || sync.recoveryStatus == AccountRecoveryStatus.NOTHING_ELIGIBLE) &&
    sync?.stop == AppReconciliationStop.TAIL

private fun activeAuthenticationError(error: AccountStartupError): String = when (error) {
    AccountStartupError.UNAVAILABLE -> "Account action unavailable. Try Reauthenticate again."
    AccountStartupError.SIGN_IN_FAILED -> "Authentication failed. Sign in again to resume sync."
    AccountStartupError.PREPARATION_PAUSED -> "Account preparation paused. Try Reauthenticate again."
    AccountStartupError.IDENTITY_MISMATCH -> "Account mismatch. Reauthenticate with the same account."
    AccountStartupError.EDITOR_OPEN -> "Finish Save or Discard in the open editor, then try Reauthenticate again."
    AccountStartupError.CANCELLED -> "Authentication cancelled."
}

internal fun recoveryStatusText(status: AccountRecoveryStatus): String = when (status) {
    AccountRecoveryStatus.NOTHING_ELIGIBLE -> "No rejected changes need recovery. No changes were discarded."
    AccountRecoveryStatus.BLOCKED ->
        "Recovery is blocked by retained work. Keep your changes and resolve other pending actions before trying again."
    AccountRecoveryStatus.PREPARATION_FAILED -> "Could not prepare recovery. No changes were discarded. Try again."
    AccountRecoveryStatus.RECOVERY_FAILED ->
        "Recovery could not finish. Try Sync now, then resolve rejected changes again if needed."
    AccountRecoveryStatus.STALE_CONFIRMATION ->
        "Local changes were updated after this warning. Resolve rejected changes again."
}
