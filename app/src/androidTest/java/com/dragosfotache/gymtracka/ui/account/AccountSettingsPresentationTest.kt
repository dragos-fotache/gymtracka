package com.dragosfotache.gymtracka.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dragosfotache.gymtracka.data.sync.api.ServerChecker
import com.dragosfotache.gymtracka.data.sync.api.ServerCheckResult
import com.dragosfotache.gymtracka.data.sync.session.AccountRecoveryConfirmation
import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationResult
import com.dragosfotache.gymtracka.data.sync.session.AppSessionDeliveryStatus
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.OrdinaryRecoveryPreparation
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionStatus
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionState
import com.dragosfotache.gymtracka.ui.settings.SettingsScreen
import com.dragosfotache.gymtracka.ui.captureSettingsScreenshot
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import com.dragosfotache.gymtracka.ui.theme.GymBackground
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.round
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private val connection = ServerConnectionState(ServerChecker { ServerCheckResult.CONNECTED }, scope)
    private var startup by mutableStateOf(AccountStartupUiState(syncStatus = AccountSyncStatus(0L, 123L, false)))
    private var delivery by mutableStateOf(AppSessionDeliveryStatus.IDLE)
    private var reauthenticated = 0
    private var cancelled = 0

    @After
    fun close() {
        sync.dispose()
        connection.dispose()
        scope.cancel()
    }

    private fun show(width: Dp? = null, fontScale: Float = 1f) {
        compose.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(GymBackground)) {
                        Box(if (width == null) Modifier.fillMaxSize() else Modifier.width(width)) {
                            SettingsScreen(
                                serverConnectionState = connection,
                                accountContent = {
                                    AccountSettingsContent(
                                        startup, ServerConnectionStatus.CONNECTED, true, delivery,
                                        { reauthenticated++ }, { cancelled++ }, sync,
                                    )
                                },
                            )
                        }
                    }
                }
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
        compose.onNodeWithText("[Resolve rejected changes]").assertExists()
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

    @Test
    fun panelTextAndBracketedActionsShareTheGrid() {
        startup = startup.copy(syncStatus = AccountSyncStatus(0L, System.currentTimeMillis(), false))
        show()
        compose.onNodeWithText("[Check]").assertIsDisplayed()
        compose.onNodeWithText("[Sync now]").assertIsDisplayed()
        assertLabelFits("account-last-success")
        assertGrid("settings-sync", "account-pending", "account-last-success", "account-sync-now")
        assertGrid("settings-account", "account-authentication-status", "account-reauthenticate")
        captureSettingsScreenshot("grid-normal")
        compose.onNodeWithText("[Reauthenticate]").performClick()
        compose.runOnIdle { assertEquals(1, reauthenticated) }
    }

    @Test
    fun narrowDoubleTextKeepsMetadataErrorsAndLongActionsWhole() {
        startup = startup.copy(
            error = AccountStartupError.SIGN_IN_FAILED,
            syncStatus = AccountSyncStatus(12L, System.currentTimeMillis(), true, true),
        )
        delivery = AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED
        show(width = 320.dp, fontScale = 2f)
        assertLabelFits("settings-server-status")
        assertLabelFits("settings-server-recheck")
        captureSettingsScreenshot("grid-large-connection")
        assertLabelFits("account-pending")
        assertLabelFits("account-last-success")
        assertLabelFits("account-recovery-prepare")
        compose.onNodeWithTag("account-recovery-prepare").assertIsNotEnabled()
        captureSettingsScreenshot("grid-large-sync")
        assertLabelFits("account-authentication-message")
        assertLabelFits("account-reauthenticate")
        compose.onNodeWithTag("account-reauthenticate").assertIsEnabled()
        captureSettingsScreenshot("grid-large-account")
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun authenticationInProgressKeepsCancellationAvailable() {
        startup = startup.copy(busy = true)
        show()
        compose.onNodeWithTag("account-reauthenticate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("account-cancel").performScrollTo().assertIsEnabled()
        captureSettingsScreenshot("grid-authenticating")
        compose.onNodeWithTag("account-cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    private fun assertLabelFits(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        repeat(layout.lineCount) { line ->
            assertTrue("The complete $tag line must fit", layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("The complete $tag height must fit", layout.getLineBottom(line) <= layout.size.height + 1f)
            assertTrue("$tag must wrap without ellipsis", !layout.isLineEllipsized(line))
        }
    }

    private fun assertGrid(panelTag: String, vararg labelTags: String) {
        val panel = compose.onNodeWithTag(panelTag).fetchSemanticsNode().boundsInRoot
        val scale = panel.width / 402f
        labelTags.forEach { tag ->
            val label = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertEquals("$tag must share the panel text edge", panel.left + 12 * scale, label.left, 1.5f)
            assertTrue("$tag must stay inside the panel", label.right <= panel.right + 1f)
            val step = 12 * scale
            assertEquals("$tag height must follow the grid", round(label.height / step) * step, label.height, 1.5f)
        }
    }
}
