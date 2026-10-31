package com.dragosfotache.gymtracka.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountNetworkActionsTest {
    @Test
    fun everyUnconfirmedConnectionDisablesAccountNetworkActions() {
        for (status in ServerConnectionStatus.entries.filter { it != ServerConnectionStatus.CONNECTED }) {
            assertEquals(AccountNetworkActions(false, false), accountNetworkActions(status, true, false, false, false))
        }
    }

    @Test
    fun healthyConnectionAllowsSyncEvenWithoutForegroundSignInConfiguration() {
        assertEquals(
            AccountNetworkActions(true, true),
            accountNetworkActions(ServerConnectionStatus.CONNECTED, true, false, false, false),
        )
        assertEquals(
            AccountNetworkActions(true, false),
            accountNetworkActions(ServerConnectionStatus.CONNECTED, false, false, false, false),
        )
    }

    @Test
    fun foregroundAuthenticationRequiredAllowsReauthenticationButNotManualSync() {
        assertEquals(
            AccountNetworkActions(false, true),
            accountNetworkActions(ServerConnectionStatus.CONNECTED, true, false, false, true),
        )
    }

    @Test
    fun eitherCompetingOperationDisablesBothNetworkActions() {
        for ((accountBusy, syncBusy) in listOf(true to false, false to true, true to true)) {
            assertEquals(
                AccountNetworkActions(false, false),
                accountNetworkActions(ServerConnectionStatus.CONNECTED, true, accountBusy, syncBusy, false),
            )
        }
    }
}
