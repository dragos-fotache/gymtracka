package com.dragosfotache.gymtracka.ui.account

import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationStop
import com.dragosfotache.gymtracka.data.sync.session.AppSessionDeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStatusPresentationTest {
    private val clean = AccountStartupUiState(syncStatus = AccountSyncStatus(0L, 123L, false))
    private val idle = AccountSyncUiState(stop = AppReconciliationStop.TAIL)

    @Test
    fun knownCleanQueue_hasOneSuccessExplanationAndNoRecovery() {
        val result = accountStatusPresentation(clean, idle, AppSessionDeliveryStatus.IDLE)
        assertEquals("All changes synced", result.sync)
        assertEquals("Signed in", result.authentication)
        assertFalse(result.rejectedWork)
    }

    @Test
    fun unknownOrInterruptedOperationCannotReuseHistoricalSuccess() {
        assertNull(accountStatusPresentation(clean, idle.copy(stop = null), AppSessionDeliveryStatus.IDLE).sync)
        assertNull(accountStatusPresentation(clean, null, AppSessionDeliveryStatus.IDLE).sync)
    }

    @Test
    fun unknownMetadata_neverClaimsSuccess() {
        for (startup in listOf(AccountStartupUiState(), clean.copy(syncStatus = AccountSyncStatus(0L, null, false)))) {
            val result = accountStatusPresentation(startup, idle, AppSessionDeliveryStatus.IDLE)
            assertNull(result.sync)
            assertFalse(result.rejectedWork)
        }
    }

    @Test
    fun unresolvedWorkAndStops_neverClaimSuccessWithAnEmptyQueue() {
        val unresolved = listOf(
            idle.copy(busy = true),
            idle.copy(error = AccountSyncError.UNAVAILABLE),
            idle.copy(error = AccountSyncError.REAUTHENTICATION_REQUIRED),
            idle.copy(error = AccountSyncError.CANCELLED),
            idle.copy(retainedStageId = "retained"),
            idle.copy(recoveryStatus = AccountRecoveryStatus.PREPARATION_FAILED),
        ) + AppReconciliationStop.entries.filter { it != AppReconciliationStop.TAIL }.map { idle.copy(stop = it) }
        val failedStops = setOf(
            AppReconciliationStop.PUSH_STOPPED,
            AppReconciliationStop.PULL_STOPPED,
            AppReconciliationStop.SNAPSHOT_STOPPED,
        )
        for (sync in unresolved) {
            val result = accountStatusPresentation(clean, sync, AppSessionDeliveryStatus.IDLE)
            assertFalse(result.sync == "All changes synced")
            if (sync.error == AccountSyncError.UNAVAILABLE || sync.stop in failedStops) {
                assertEquals("Sync failed", result.sync)
            }
        }
        for (delivery in AppSessionDeliveryStatus.entries.filter { it != AppSessionDeliveryStatus.IDLE }) {
            assertFalse(accountStatusPresentation(clean, idle, delivery).sync == "All changes synced")
        }
    }

    @Test
    fun lastSuccessDoesNotHideNewPendingOrRetainedErrors() {
        for (status in listOf(AccountSyncStatus(2L, 123L, false), AccountSyncStatus(0L, 123L, true))) {
            val result = accountStatusPresentation(
                clean.copy(syncStatus = status),
                idle.copy(stop = AppReconciliationStop.TAIL),
                AppSessionDeliveryStatus.IDLE,
            )
            assertFalse(result.sync == "All changes synced")
        }
    }

    @Test
    fun onlyDefinitiveOrdinaryRejectionOffersRecovery() {
        val genericError = accountStatusPresentation(
            clean.copy(syncStatus = AccountSyncStatus(1L, 123L, true)),
            idle,
            AppSessionDeliveryStatus.IDLE,
        )
        assertFalse(genericError.rejectedWork)
        val rejected = accountStatusPresentation(
            clean.copy(syncStatus = AccountSyncStatus(1L, 123L, true, true)),
            idle.copy(stop = AppReconciliationStop.PUSH_STOPPED),
            AppSessionDeliveryStatus.RETRY_REQUIRED,
        )
        assertTrue(rejected.rejectedWork)
        assertNull(rejected.sync)
    }

    @Test
    fun authenticationRequirementHasNoDuplicatePausedOrGenericSyncError() {
        val result = accountStatusPresentation(
            clean,
            idle.copy(error = AccountSyncError.UNAVAILABLE, stop = AppReconciliationStop.PUSH_STOPPED),
            AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED,
        )
        assertTrue(result.authentication.contains("reauthentication"))
        assertTrue(result.authenticationNeedsAttention)
        assertNull(result.sync)
    }

    @Test
    fun authenticationFailureMismatchEditorAndCancellationRemainDistinct() {
        val messages = AccountStartupError.entries.map { error ->
            val result = accountStatusPresentation(clean.copy(error = error), idle, AppSessionDeliveryStatus.IDLE)
            assertTrue(result.authenticationNeedsAttention)
            assertFalse(result.sync == "All changes synced")
            result.authentication
        }
        assertEquals(AccountStartupError.entries.size, messages.distinct().size)
        assertTrue(messages.any { it == "Authentication failed. Sign in again to resume sync." })
    }

    @Test
    fun emptyRecoveryIsInformationalAndDoesNotInventSyncFailure() {
        val result = accountStatusPresentation(
            clean,
            idle.copy(recoveryStatus = AccountRecoveryStatus.NOTHING_ELIGIBLE),
            AppSessionDeliveryStatus.IDLE,
        )
        assertEquals("All changes synced", result.sync)
        assertEquals(
            "No rejected changes need recovery. No changes were discarded.",
            recoveryStatusText(AccountRecoveryStatus.NOTHING_ELIGIBLE),
        )
        assertEquals(
            AccountRecoveryStatus.entries.size,
            AccountRecoveryStatus.entries.map(::recoveryStatusText).distinct().size,
        )
    }
}
