package com.dragosfotache.gymtracka.data.sync.session

/**
 * R13c selected-account sync presentation snapshot. Pending count covers every
 * outbox row for the account, including frozen/rejected. Last-success comes
 * only from the selected sync_state row and is null when unknown. Retained
 * error is rejected or stored error presence, never raw code/message.
 * Presentation only, never an edit/sync trigger. Fixed/redacted display.
 */
internal data class AccountSyncStatus(
    val pendingCount: Long,
    val lastSuccessMillis: Long?,
    val hasRetainedError: Boolean,
) {
    override fun toString(): String = "AccountSyncStatus(redacted)"
}
