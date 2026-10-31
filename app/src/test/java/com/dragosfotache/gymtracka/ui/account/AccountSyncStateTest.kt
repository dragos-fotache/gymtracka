package com.dragosfotache.gymtracka.ui.account

import com.dragosfotache.gymtracka.data.sync.session.OrdinaryRecoveryPreparation
import com.dragosfotache.gymtracka.data.sync.session.AccountRecoveryConfirmation
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationResult
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (JVM): AccountSyncState controller inertness and gating
 * without an ACTIVE handle.
 *
 * These tests never construct [AppAccountSession] or
 * [AccountRecoveryConfirmation]: production owns those handles/tokens and tests
 * must not fabricate authority. All tests stay in non-ACTIVE states
 * ([AppSessionState.PrivacyLocked] and Private), where the controller must
 * remain inert: no [AccountSyncOperations.reconcile], no
 * [AccountSyncOperations.prepareOrdinaryRecovery], no
 * [AccountSyncOperations.recoverOrdinaryRejection] and no stuck busy flag.
 * ACTIVE-path behavior (foreground entry queues one bounded run, debounce/
 * conflation, genuine newer commit during run, manual retry, late-result
 * fencing, stale confirmation refusal) is covered in androidTest with real
 * facade handles and in relay journeys; see the coordinator seam request.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountSyncStateTest {

    private class FakeSyncOps(
        initial: AppSessionState = AppSessionState.PrivacyLocked,
    ) : AccountSyncOperations {
        override val state = MutableStateFlow(initial)
        val edits = MutableSharedFlow<Long>(extraBufferCapacity = 64)

        var epochCalls = 0
        var reconcileCalls = 0
        var prepareCalls = 0
        var recoverCalls = 0

        override fun committedEdits(session: AppAccountSession): Flow<Long> = edits

        override suspend fun observeCacheEpoch(session: AppAccountSession): Long {
            epochCalls += 1
            error("observeCacheEpoch must not run without an ACTIVE handle")
        }

        override suspend fun reconcile(
            session: AppAccountSession,
            expectedCacheEpoch: Long,
            restartStageId: String?,
        ): AppReconciliationResult {
            reconcileCalls += 1
            error("reconcile must not run without an ACTIVE handle")
        }

        override suspend fun prepareOrdinaryRecovery(
            session: AppAccountSession,
        ): OrdinaryRecoveryPreparation {
            prepareCalls += 1
            error("prepareOrdinaryRecovery must not run without an ACTIVE handle")
        }

        override suspend fun recoverOrdinaryRejection(
            session: AppAccountSession,
            confirmation: AccountRecoveryConfirmation,
        ): AppReconciliationResult {
            recoverCalls += 1
            error("recoverOrdinaryRejection must not run without an ACTIVE handle")
        }
    }

    private fun idleState() = AccountSyncUiState()

    @Test
    fun construction_isInertWithoutStart() = runTest {
        val ops = FakeSyncOps()
        val sync = AccountSyncState(operations = ops, scope = backgroundScope)
        try {
            runCurrent()
            assertEquals(idleState(), sync.uiState.value)
            assertEquals(0, ops.epochCalls)
            assertEquals(0, ops.reconcileCalls)
            assertEquals(0, ops.prepareCalls)
            assertEquals(0, ops.recoverCalls)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun start_whilePrivacyLocked_queuesNoWork() = runTest {
        val ops = FakeSyncOps()
        val sync = AccountSyncState(operations = ops, scope = backgroundScope)
        try {
            sync.start()
            sync.start()
            advanceUntilIdle()
            runCurrent()
            assertEquals(idleState(), sync.uiState.value)
            assertEquals(0, ops.reconcileCalls)
            assertEquals(0, ops.prepareCalls)
            assertEquals(0, ops.recoverCalls)
            assertFalse(sync.uiState.value.busy)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun foreground_whilePrivacyLocked_triggersNothing() = runTest {
        val ops = FakeSyncOps()
        val sync = AccountSyncState(operations = ops, scope = backgroundScope)
        try {
            sync.start()
            sync.setForeground(true)
            sync.setForeground(false)
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals(0, ops.reconcileCalls)
            assertEquals(0, ops.epochCalls)
            assertFalse(sync.uiState.value.busy)
            assertNull(sync.uiState.value.stop)
            assertNull(sync.uiState.value.error)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun committedEdits_whilePrivacyLocked_triggerNothing() = runTest {
        val ops = FakeSyncOps()
        val sync = AccountSyncState(operations = ops, scope = backgroundScope)
        try {
            sync.start()
            sync.setForeground(true)
            ops.edits.tryEmit(1L)
            ops.edits.tryEmit(2L)
            advanceUntilIdle()
            runCurrent()
            assertEquals(0, ops.reconcileCalls)
            assertFalse(sync.uiState.value.busy)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun repeatedForegroundAndManual_whilePrivacyLocked_stillNoWork() = runTest {
        val ops = FakeSyncOps()
        val sync = AccountSyncState(operations = ops, scope = backgroundScope)
        try {
            sync.start()
            repeat(3) {
                sync.setForeground(true)
                sync.syncNow()
            }
            sync.setForeground(false)
            sync.syncNow()
            advanceUntilIdle()
            runCurrent()
            assertEquals(0, ops.reconcileCalls)
            assertEquals(0, ops.epochCalls)
            assertFalse(sync.uiState.value.busy)
            assertNull(sync.uiState.value.recovery)
            assertNull(sync.uiState.value.retainedStageId)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun prepareRecovery_whilePrivacyLocked_runsNoDestructiveWork() = runTest {
        val ops = FakeSyncOps()
        val sync = AccountSyncState(operations = ops, scope = backgroundScope)
        try {
            sync.start()
            sync.setForeground(true)
            sync.prepareRecovery()
            advanceUntilIdle()
            runCurrent()
            assertEquals(0, ops.prepareCalls)
            assertEquals(0, ops.recoverCalls)
            assertNull(sync.uiState.value.recovery)
            assertFalse(sync.uiState.value.busy)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun cancel_whileIdle_isNoOpWithoutWork() = runTest {
        val ops = FakeSyncOps()
        val sync = AccountSyncState(operations = ops, scope = backgroundScope)
        try {
            sync.start()
            sync.cancel()
            sync.syncNow()
            sync.cancel()
            advanceUntilIdle()
            runCurrent()
            assertEquals(0, ops.reconcileCalls)
            assertFalse(sync.uiState.value.busy)
            assertEquals(idleState(), sync.uiState.value)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun dispose_fencesLaterTriggersWithoutBorrowedScopeDamage() = runTest {
        val ops = FakeSyncOps()
        val sync = AccountSyncState(operations = ops, scope = backgroundScope)
        sync.start()
        sync.setForeground(true)
        sync.dispose()
        // Late triggers after dispose must not reach operations.
        sync.setForeground(true)
        sync.syncNow()
        sync.prepareRecovery()
        sync.cancel()
        ops.edits.tryEmit(3L)
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, ops.reconcileCalls)
        assertEquals(0, ops.prepareCalls)
        assertEquals(0, ops.recoverCalls)
        // Borrowed test scope still works for a fresh controller.
        val second = AccountSyncState(operations = ops, scope = backgroundScope)
        try {
            second.start()
            advanceUntilIdle()
            runCurrent()
            assertEquals(0, ops.reconcileCalls)
        } finally {
            second.dispose()
        }
    }
}
