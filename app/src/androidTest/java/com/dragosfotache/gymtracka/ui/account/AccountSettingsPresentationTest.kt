package com.dragosfotache.gymtracka.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.dragosfotache.gymtracka.data.sync.session.AccountRecoveryConfirmation
import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationResult
import com.dragosfotache.gymtracka.data.sync.session.AppSessionDeliveryStatus
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.OrdinaryRecoveryPreparation
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionStatus
import com.dragosfotache.gymtracka.ui.settings.SettingsScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Presentation-only fixtures never construct a session or authorize recovery. Real handles live in flow tests. */
class AccountSettingsPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val scope = MainScope()
    private val ops = object : AccountSyncOperations {
        override val state = MutableStateFlow<AppSessionState>(AppSessionState.PrivacyLocked)
        override fun committedEdits(session: AppAccountSession) = emptyFlow<Long>()
        override suspend fun observeCacheEpoch(session: AppAccountSession): Long = error("No account work expected")
        override suspend fun reconcile(
            session: AppAccountSession,
            expectedCacheEpoch: Long,
            restartStageId: String?,
        ): AppReconciliationResult = error("No account work expected")
        override suspend fun prepareOrdinaryRecovery(
            session: AppAccountSession,
        ): OrdinaryRecoveryPreparation = error("No account work expected")
        override suspend fun recoverOrdinaryRejection(
            session: AppAccountSession,
            confirmation: AccountRecoveryConfirmation,
        ): AppReconciliationResult = error("No account work expected")
    }
    private val sync = AccountSyncState(ops, scope)
    private var startup by mutableStateOf(AccountStartupUiState(syncStatus = AccountSyncStatus(0L, 123L, false)))
    private var delivery by mutableStateOf(AppSessionDeliveryStatus.IDLE)

    @After
    fun close() {
        sync.dispose()
        scope.cancel()
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(accountContent = {
                    AccountSettingsContent(startup, ServerConnectionStatus.CONNECTED, true, delivery, {}, {}, sync)
                })
            }
        }
    }

    @Test
    fun cleanUnknownAndRejectedQueuesOfferOnlyRelevantRecovery() {
        show()
        // This inert fixture has no completed sync result; metadata alone cannot claim success.
        compose.onNodeWithTag("account-sync-status").assertDoesNotExist()
        compose.onNodeWithTag("account-recovery-prepare").assertDoesNotExist()
        compose.onNodeWithText("Sync unavailable. Your visible data is unchanged.").assertDoesNotExist()
        compose.runOnIdle { startup = AccountStartupUiState() }
        compose.onNodeWithTag("account-pending").assertTextEquals("Pending changes: unavailable")
        compose.onNodeWithTag("account-last-success").assertTextEquals("Last success: unavailable")
        compose.onNodeWithTag("account-sync-status").assertDoesNotExist()
        compose.onNodeWithTag("account-recovery-prepare").assertDoesNotExist()
        compose.runOnIdle { startup = AccountStartupUiState(syncStatus = AccountSyncStatus(2L, null, true)) }
        compose.onNodeWithTag("account-last-success").assertTextEquals("Last success: unknown")
        compose.onNodeWithTag("account-recovery-prepare").assertDoesNotExist()
        compose.runOnIdle { startup = startup.copy(syncStatus = AccountSyncStatus(2L, null, true, true)) }
        compose.onNodeWithTag("account-recovery-prepare").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Resolve rejected changes").assertExists()
        compose.onNodeWithTag("account-sync-status").assertDoesNotExist()
    }

    @Test
    fun authenticationFailureLivesBesideReauthenticate_andDoesNotStackPausedMessages() {
        startup = startup.copy(error = AccountStartupError.SIGN_IN_FAILED)
        delivery = AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED
        show()
        compose.onNodeWithTag("account-authentication-status").performScrollTo()
            .assertTextEquals("Signed in")
        compose.onNodeWithTag("account-authentication-message").performScrollTo()
            .assertTextEquals("Authentication failed. Sign in again to resume sync.")
        compose.onNodeWithTag("account-reauthenticate").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("account-sync-now").assertIsNotEnabled()
        compose.onNodeWithTag("account-sync-status").assertDoesNotExist()
        compose.onNodeWithText("Delivery paused. Offline editing remains available.").assertDoesNotExist()
        compose.runOnIdle {
            startup = startup.copy(error = null)
            delivery = AppSessionDeliveryStatus.IDLE
        }
        compose.onNodeWithTag("account-authentication-status").assertTextEquals("Signed in")
        // This inert fixture has no completed sync result; metadata alone cannot claim success.
        compose.onNodeWithTag("account-sync-status").assertDoesNotExist()
        compose.onNodeWithTag("account-sync-now").assertIsEnabled()
    }
}
