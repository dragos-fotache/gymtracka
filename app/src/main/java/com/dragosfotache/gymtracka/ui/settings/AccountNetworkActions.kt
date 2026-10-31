package com.dragosfotache.gymtracka.ui.settings

internal data class AccountNetworkActions(
    val sync: Boolean,
    val reauthenticate: Boolean,
)

/** UI availability only. The account runtime and engine still enforce actual admission. */
internal fun accountNetworkActions(
    connection: ServerConnectionStatus,
    signInAvailable: Boolean,
    accountBusy: Boolean,
    syncBusy: Boolean,
    reauthenticationRequired: Boolean,
): AccountNetworkActions {
    val idleAndConnected = connection == ServerConnectionStatus.CONNECTED && !accountBusy && !syncBusy
    return AccountNetworkActions(
        sync = idleAndConnected && !reauthenticationRequired,
        reauthenticate = idleAndConnected && signInAvailable,
    )
}
