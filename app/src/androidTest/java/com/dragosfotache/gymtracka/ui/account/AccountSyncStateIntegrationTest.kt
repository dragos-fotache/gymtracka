package com.dragosfotache.gymtracka.ui.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.session.OrdinaryRecoveryPreparation
import com.dragosfotache.gymtracka.data.sync.session.AccountRecoveryConfirmation
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationResult
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationStop
import com.dragosfotache.gymtracka.data.sync.session.AppSessionAuthApi
import com.dragosfotache.gymtracka.data.sync.session.AppSessionCoordinator
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransport
import com.dragosfotache.gymtracka.data.sync.session.RecoveryIdentity
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomPhase
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Checks the foreground sync controller against actual account runtime binding and committed-edit
 * observations.
 */
@RunWith(AndroidJUnit4::class)
class AccountSyncStateIntegrationTest {
    private companion object {
        const val ORIGIN = "https://sync-foreground.example.test"
        const val ACCOUNT = "c13d1001-1111-4111-8111-111111111111"
        const val OP = "d13d1001-1111-4111-8111-111111111111"
    }

    private val harnesses = mutableListOf<AppSessionHarness>()
    private val dbs = mutableListOf<GymDatabase>()

    @After
    fun tearDown() {
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harnesses.forEach { runCatching { it.clean() } }
        harnesses.clear()
    }

    private data class Stack(
        val database: GymDatabase,
        val coordinator: AppSessionCoordinator,
        val transport: AppSessionTransport,
    )

    private fun stack(tag: String): Stack {
        val harness = AppSessionHarness("foreground-sync-$tag").also { harnesses += it }
        val database = harness.openInMemoryDb().also { dbs += it }
        val transport = AppSessionTransport()
        val coordinator = AppSessionFixtures.coordinator(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = harness.newVault(),
            auth = AppSessionAuthApi(),
            transport = transport,
            runOwner = AppSessionFixtures.owner(),
            transitionBarrier = com.dragosfotache.gymtracka.data.sync.session.ScriptedTransitionBarrier(),
            publicationBarrier = com.dragosfotache.gymtracka.data.sync.session.ScriptedPublicationBarrier(),
        )
        return Stack(database, coordinator, transport)
    }

    private suspend fun Stack.activate(): AppAccountSession {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val preparing = coordinator.acceptGrant(
            RoomSessionAuthority(database).read(),
            AppSessionFixtures.OP_FRESH,
            AppSessionFixtures.grant(),
        )
        assertTrue("setup must reach Private, was $preparing", preparing is AppSessionState.Private)
        val active = coordinator.completeActivation(
            RoomSessionAuthority(database).read(),
            AppSessionFixtures.STAGE,
        )
        assertTrue("setup must reach Active, was $active", active is AppSessionState.Active)
        assertTrue("activation must drain its phase replies", transport.queue.isEmpty())
        return (active as AppSessionState.Active).session
    }

    private suspend fun Stack.epoch(session: AppAccountSession) =
        coordinator.observeCacheEpoch(session)

    /**
     * Delegated state flow: actual collection observes the BACKING flow, but
     * direct VALUE reads serve explicit scripted returns first. This separates
     * synchronous admission/resolve reads from watcher dispatch, which only
     * advances on the virtual scheduler.
     */
    private class ScriptedStateFlow(
        private val backing: MutableStateFlow<AppSessionState>,
        val scriptedReads: ArrayDeque<AppSessionState>,
    ) : StateFlow<AppSessionState> {
        override var value: AppSessionState
            get() = if (scriptedReads.isNotEmpty()) scriptedReads.removeFirst() else backing.value
            set(next) {
                backing.value = next
            }
        override val replayCache: List<AppSessionState>
            get() = backing.replayCache
        override suspend fun collect(collector: FlowCollector<AppSessionState>): Nothing =
            backing.collect(collector)
    }

    private class FakeSyncOps : AccountSyncOperations {
        private val backingState = MutableStateFlow<AppSessionState>(AppSessionState.PrivacyLocked)
        val scriptedReads = ArrayDeque<AppSessionState>()
        override val state: ScriptedStateFlow = ScriptedStateFlow(backingState, scriptedReads)

        fun setBacking(next: AppSessionState) {
            backingState.value = next
        }
        private val sessionFlows = mutableMapOf<AppAccountSession, MutableStateFlow<Long>>()

        /** Per-session matching binding: independent counters from zero. */
        fun flowFor(session: AppAccountSession): MutableStateFlow<Long> =
            sessionFlows.getOrPut(session) { MutableStateFlow(0L) }

        /** Deterministic no-I/O epochs, seeded per handle before use. */
        val epochScripts = mutableMapOf<AppAccountSession, ArrayDeque<Long>>()
        val epochDefaults = mutableMapOf<AppAccountSession, Long>()
        val epochCalls = mutableListOf<AppAccountSession>()

        data class ReconcileCall(val session: AppAccountSession, val epoch: Long, val stage: String?)
        val reconcileCalls = mutableListOf<ReconcileCall>()
        val reconnectCalls = mutableListOf<AppAccountSession>()
        val reconcileScript = ArrayDeque<AppReconciliationResult>()
        var reconcileGate: (suspend () -> Unit)? = null
        var prepareCalls = 0
        var preparation: OrdinaryRecoveryPreparation = OrdinaryRecoveryPreparation.NothingEligible

        override fun committedEdits(session: AppAccountSession): Flow<Long> = flowFor(session)

        override suspend fun observeCacheEpoch(session: AppAccountSession): Long {
            epochCalls += session
            val queued = epochScripts[session]
            if (queued != null && queued.isNotEmpty()) return queued.removeFirst()
            return checkNotNull(epochDefaults[session]) { "no seeded epoch for handle" }
        }

        override suspend fun reconcile(
            session: AppAccountSession,
            expectedCacheEpoch: Long,
            restartStageId: String?,
        ): AppReconciliationResult {
            reconcileCalls += ReconcileCall(session, expectedCacheEpoch, restartStageId)
            reconcileGate?.invoke()
            return if (reconcileScript.isNotEmpty()) reconcileScript.removeFirst()
            else reconciliationResult(AppReconciliationStop.TAIL, expectedCacheEpoch)
        }

        override suspend fun prepareOrdinaryRecovery(
            session: AppAccountSession,
        ): OrdinaryRecoveryPreparation {
            prepareCalls += 1
            return preparation
        }

        override suspend fun reconnect(session: AppAccountSession, expectedCacheEpoch: Long): AppReconciliationResult {
            reconnectCalls += session
            return reconcile(session, expectedCacheEpoch, null)
        }

        override suspend fun recoverOrdinaryRejection(
            session: AppAccountSession,
            confirmation: AccountRecoveryConfirmation,
        ): AppReconciliationResult = reconciliationResult(AppReconciliationStop.TAIL, 0L)
    }

    private fun privateState(): AppSessionState.Private = AppSessionState.Private(
        AppSessionReason.SIGN_IN_REQUIRED,
        RecoveryRoomSnapshot(
            revision = 3L,
            sessionGeneration = 1L,
            cachedAccountId = ACCOUNT,
            phase = RecoveryRoomPhase.PREPARING,
            identity = RecoveryIdentity(
                ServiceSessionBinding(ServiceOrigin.parse(ORIGIN), ACCOUNT, 1L),
                OP,
            ),
            baselineComplete = false,
        ),
    )

    @Test
    fun reconnectDuringBusyRun_coalescesOneFreshRetry_andCannotFollowPrivateAuthority() = runTest(timeout = 90.seconds) {
        val stack = stack("busy-reconnect")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val gate = CompletableDeferred<Unit>()
        ops.reconcileGate = { gate.await() }
        val sync = AccountSyncState(ops, this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            runCurrent()
            assertEquals(1, ops.reconcileCalls.size)
            sync.connectionRestored(session)
            sync.connectionRestored(session)
            runCurrent()
            assertTrue(ops.reconnectCalls.isEmpty())
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, ops.reconcileCalls.size)
            assertEquals(listOf(session), ops.reconnectCalls)
            ops.state.value = privateState()
            runCurrent()
            sync.connectionRestored(session)
            advanceUntilIdle()
            assertEquals(2, ops.reconcileCalls.size)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun foregroundEntry_queuesOneBoundedFreshEpochRun() = runTest(timeout = 90.seconds) {
        val stack = stack("entry")
        val session = stack.activate()
        val ops = FakeSyncOps()
        val epoch = stack.epoch(session)
        ops.epochDefaults[session] = epoch
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals("foreground entry must queue exactly one run", 1, ops.reconcileCalls.size)
            val call = ops.reconcileCalls.single()
            assertTrue("run must carry the exact ACTIVE handle", call.session === session)
            assertEquals("run must use a fresh epoch", epoch, call.epoch)
            assertNull("foreground run carries no stage restart", call.stage)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun sameHandleRedelivery_doesNotQueueAgain() = runTest(timeout = 90.seconds) {
        val stack = stack("redelivery")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, ops.reconcileCalls.size)
            ops.state.value = AppSessionState.Active(session)
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals("same-handle redelivery must not queue again", 1, ops.reconcileCalls.size)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun returnForeground_queuesSecondRun() = runTest(timeout = 90.seconds) {
        val stack = stack("return")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            sync.setForeground(false)
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, ops.reconcileCalls.size)
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals("returning foreground must queue a fresh run", 2, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls.all { it.session === session })
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun manualWhilePrivate_doesNotPoisonLaterAdmission() = runTest(timeout = 90.seconds) {
        val stack = stack("private-manual")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = privateState()
            sync.start()
            sync.setForeground(true)
            sync.syncNow()
            sync.prepareRecovery()
            advanceUntilIdle()
            runCurrent()
            assertEquals("no epoch reads while Private", 0, ops.epochCalls.size)
            assertEquals("no runs while Private", 0, ops.reconcileCalls.size)
            assertEquals("no prepares while Private", 0, ops.prepareCalls)
            assertFalse(sync.uiState.value.busy)
            // Later ACTIVE admission must work untouched by the idle manual.
            ops.state.value = AppSessionState.Active(session)
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals("admission after idle manual must run once", 1, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls.single().session === session)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun editBurst_debouncedCoalescedToSingleRun() = runTest(timeout = 90.seconds) {
        val stack = stack("burst")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 1_000L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, ops.reconcileCalls.size)
            ops.flowFor(session).value = 1L
            ops.flowFor(session).value = 2L
            ops.flowFor(session).value = 3L
            runCurrent()
            assertEquals("edit bursts must debounce, not run per edit", 1, ops.reconcileCalls.size)
            advanceTimeBy(1_000L)
            advanceUntilIdle()
            runCurrent()
            assertEquals("coalesced burst must run exactly once more", 2, ops.reconcileCalls.size)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun newerCommitDuringRun_schedulesFreshEpochRun() = runTest(timeout = 90.seconds) {
        val stack = stack("newer")
        val session = stack.activate()
        val ops = FakeSyncOps()
        val epoch = stack.epoch(session)
        // Scripted variance proves the follow-up re-reads instead of reusing.
        ops.epochScripts[session] = ArrayDeque(listOf(epoch, epoch + 500L))
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            ops.reconcileGate = { ops.flowFor(session).value = 2L }
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals("newer mid-run commit requires a follow-up run", 2, ops.reconcileCalls.size)
            assertEquals(epoch, ops.reconcileCalls[0].epoch)
            assertEquals("follow-up must use a fresh epoch", epoch + 500L, ops.reconcileCalls[1].epoch)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun protectedStop_doesNotSpin_manualRetriesFresh() = runTest(timeout = 90.seconds) {
        val stack = stack("protected")
        val session = stack.activate()
        val ops = FakeSyncOps()
        val epoch = stack.epoch(session)
        ops.epochDefaults[session] = epoch
        ops.reconcileScript += reconciliationResult(AppReconciliationStop.PROTECTED, epoch)
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, ops.reconcileCalls.size)
            advanceTimeBy(30_000L)
            advanceUntilIdle()
            runCurrent()
            assertEquals("protected stop must not spin", 1, ops.reconcileCalls.size)
            assertFalse(sync.uiState.value.busy)
            sync.syncNow()
            advanceUntilIdle()
            runCurrent()
            assertEquals(2, ops.reconcileCalls.size)
            assertEquals("manual retry must read a fresh epoch", epoch, ops.reconcileCalls[1].epoch)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun cancelDuringRun_fencesOwnedWorkAndStaysReusable() = runTest(timeout = 90.seconds) {
        val stack = stack("cancel")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val hang = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var first = true
        ops.reconcileGate = {
            if (first) {
                first = false
                order += "op1-enter"
                entered.complete(Unit)
                withContext(NonCancellable) { hang.await() }
                order += "op1-exit"
                exited.complete(Unit)
            }
        }
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            entered.await()
            assertEquals(1, ops.reconcileCalls.size)
            // Corrected precondition: while busy, a manual action is
            // nonqueueing/ignored; cancel fences owned work without touching
            // borrowed scope, and fresh demand starts only after exit.
            sync.syncNow()
            runCurrent()
            assertEquals("manual while busy must not queue", 1, ops.reconcileCalls.size)
            sync.cancel()
            runCurrent()
            assertEquals("cancel must not drop the retiring run early", 1, ops.reconcileCalls.size)
            hang.complete(Unit)
            exited.await()
            advanceUntilIdle()
            runCurrent()
            assertEquals(listOf("op1-enter", "op1-exit"), order)
            assertFalse("retired run must settle idle", sync.uiState.value.busy)
            assertTrue("borrowed TestScope must stay active", this.coroutineContext[Job]?.isActive == true)
            sync.syncNow()
            advanceUntilIdle()
            runCurrent()
            assertEquals("fresh demand after exit runs again", 2, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls[1].session === session)
            assertFalse(sync.uiState.value.busy)
        } finally {
            hang.complete(Unit)
            sync.dispose()
        }
    }

    @Test
    fun handleReplacement_cancelsOldRun_usesNewHandle() = runTest(timeout = 90.seconds) {
        val first = stack("replace-a")
        val second = stack("replace-b")
        val session1 = first.activate()
        val session2 = second.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session1] = first.epoch(session1)
        ops.epochDefaults[session2] = second.epoch(session2)
        val hang = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        ops.reconcileGate = {
            entered.complete(Unit)
            withContext(NonCancellable) { hang.await() }
            exited.complete(Unit)
        }
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session1)
            sync.start()
            sync.setForeground(true)
            entered.await()
            ops.state.value = AppSessionState.Active(session2)
            hang.complete(Unit)
            exited.await()
            advanceUntilIdle()
            runCurrent()
            ops.reconcileGate = null
            sync.setForeground(false)
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertTrue("old run must have started with the first handle", ops.reconcileCalls.isNotEmpty())
            assertTrue("first run must carry the first handle", ops.reconcileCalls.first().session === session1)
            assertTrue("post-replacement run must carry the new handle", ops.reconcileCalls.last().session === session2)
            assertFalse(sync.uiState.value.busy)
        } finally {
            hang.complete(Unit)
            sync.dispose()
        }
    }

    @Test
    fun foregroundChurnDuringRunWithNewerCommit_singleFollowUp() = runTest(timeout = 90.seconds) {
        val stack = stack("churn")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val hang = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        var first = true
        ops.reconcileGate = {
            if (first) {
                first = false
                entered.complete(Unit)
                withContext(NonCancellable) { hang.await() }
                exited.complete(Unit)
            }
        }
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            entered.await()
            assertEquals(1, ops.reconcileCalls.size)
            ops.flowFor(session).value = 2L
            sync.setForeground(false)
            sync.setForeground(true)
            runCurrent()
            assertEquals("no successor while the resistant op is inside", 1, ops.reconcileCalls.size)
            assertTrue("resistant op must not have exited", !exited.isCompleted)
            hang.complete(Unit)
            exited.await()
            advanceUntilIdle()
            runCurrent()
            assertEquals(2, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls.all { it.session === session })
            assertNull(ops.reconcileCalls[0].stage)
            assertNull(ops.reconcileCalls[1].stage)
            advanceTimeBy(30_000L)
            advanceUntilIdle()
            runCurrent()
            assertEquals("no third spin after the follow-up", 2, ops.reconcileCalls.size)
        } finally {
            hang.complete(Unit)
            sync.dispose()
        }
    }

    @Test
    fun cancellationResistantRetiringOp_exitsBeforeNextStarts() = runTest(timeout = 90.seconds) {
        val stack = stack("resistant")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val hang = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var calls = 0
        ops.reconcileGate = {
            calls += 1
            if (calls == 1) {
                order += "op1-enter"
                entered.complete(Unit)
                withContext(NonCancellable) { hang.await() }
                order += "op1-exit"
                exited.complete(Unit)
            } else {
                order += "op2-enter"
            }
        }
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            entered.await()
            assertEquals(1, ops.reconcileCalls.size)
            sync.cancel()
            sync.syncNow()
            runCurrent()
            assertEquals(listOf("op1-enter"), order)
            assertEquals("no successor while resistant op is inside", 1, ops.reconcileCalls.size)
            hang.complete(Unit)
            exited.await()
            advanceUntilIdle()
            runCurrent()
            assertEquals(listOf("op1-enter", "op1-exit"), order)
            assertFalse("retired run must settle idle", sync.uiState.value.busy)
            assertTrue("borrowed TestScope must stay active", this.coroutineContext[Job]?.isActive == true)
            // Fresh demand only after exit/retirement starts the next op.
            // (Corrected precondition: the retiring manual was ignored, not
            // queued; borrowed scope is never cancelled.)
            sync.syncNow()
            advanceUntilIdle()
            runCurrent()
            assertEquals(2, ops.reconcileCalls.size)
            assertEquals(
                "fresh demand starts only after exit",
                listOf("op1-enter", "op1-exit", "op2-enter"),
                order,
            )
            assertFalse(sync.uiState.value.busy)
        } finally {
            hang.complete(Unit)
            sync.dispose()
        }
    }

    @Test
    fun restartStage_usesCapturedUuidNotLaterStage() = runTest(timeout = 90.seconds) {
        val stack = stack("restart")
        val session = stack.activate()
        val ops = FakeSyncOps()
        val epoch = stack.epoch(session)
        ops.epochDefaults[session] = epoch
        ops.reconcileScript += reconciliationResult(AppReconciliationStop.RETAINED_STAGE, epoch, "STAGE-1")
        ops.reconcileScript += reconciliationResult(AppReconciliationStop.RETAINED_STAGE, epoch, "STAGE-2")
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            sync.syncNow()
            advanceUntilIdle()
            runCurrent()
            assertEquals(2, ops.reconcileCalls.size)
            assertNull(ops.reconcileCalls[0].stage)
            assertNull(ops.reconcileCalls[1].stage)
            sync.restartStage("STAGE-1")
            advanceUntilIdle()
            runCurrent()
            assertEquals(3, ops.reconcileCalls.size)
            assertEquals("restart must carry the captured UUID", "STAGE-1", ops.reconcileCalls[2].stage)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun preparationOutcomesRemainDistinct_andSuccessfulSyncClearsOnlySupersededFailures() = runTest(timeout = 90.seconds) {
        val stack = stack("preparation-outcomes")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        ops.state.value = AppSessionState.Active(session)
        val sync = AccountSyncState(ops, this)
        try {
            sync.start()
            runCurrent()
            for ((outcome, expected) in listOf(
                OrdinaryRecoveryPreparation.NothingEligible to AccountRecoveryStatus.NOTHING_ELIGIBLE,
                OrdinaryRecoveryPreparation.Blocked to AccountRecoveryStatus.BLOCKED,
                OrdinaryRecoveryPreparation.Failed to AccountRecoveryStatus.PREPARATION_FAILED,
            )) {
                ops.preparation = outcome
                sync.prepareRecovery()
                advanceUntilIdle()
                runCurrent()
                assertEquals(expected, sync.uiState.value.recoveryStatus)
                assertNull(sync.uiState.value.error)
                assertNull(sync.uiState.value.recovery)
            }
            ops.reconcileGate = { throw IllegalStateException("scripted failure") }
            sync.syncNow()
            advanceUntilIdle()
            runCurrent()
            assertEquals(AccountSyncError.UNAVAILABLE, sync.uiState.value.error)
            // A successful preparation is not a successful sync and cannot clear its failure.
            sync.prepareRecovery()
            advanceUntilIdle()
            runCurrent()
            assertEquals(AccountSyncError.UNAVAILABLE, sync.uiState.value.error)
            ops.reconcileGate = null
            sync.syncNow()
            advanceUntilIdle()
            runCurrent()
            assertNull(sync.uiState.value.error)
            assertNull(sync.uiState.value.recoveryStatus)
            assertEquals(AppReconciliationStop.TAIL, sync.uiState.value.stop)
            assertEquals(1L, sync.uiState.value.successfulSyncs)
            val gate = CompletableDeferred<Unit>()
            ops.reconcileGate = { gate.await() }
            sync.syncNow()
            runCurrent()
            assertTrue(sync.uiState.value.busy)
            assertNull("in-flight work retires an earlier successful stop", sync.uiState.value.stop)
            sync.cancel()
            advanceUntilIdle()
            runCurrent()
            assertFalse(sync.uiState.value.busy)
            assertNull(sync.uiState.value.stop)
            assertEquals(AccountSyncError.CANCELLED, sync.uiState.value.error)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun deliberateReprepare_permitted_noAutoConsent() = runTest(timeout = 90.seconds) {
        val stack = stack("reprepare")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            sync.prepareRecovery()
            advanceUntilIdle()
            runCurrent()
            assertNull(sync.uiState.value.recovery)
            assertEquals("first deliberate review runs once", 1, ops.prepareCalls)
            sync.setForeground(false)
            sync.setForeground(true)
            ops.flowFor(session).value = 3L
            sync.syncNow()
            advanceUntilIdle()
            runCurrent()
            assertEquals("churn must not auto-prepare", 1, ops.prepareCalls)
            sync.prepareRecovery()
            advanceUntilIdle()
            runCurrent()
            assertEquals("second deliberate review runs again", 2, ops.prepareCalls)
            assertNull(sync.uiState.value.recovery)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun dispose_enteredRunIgnoredAfterDisposal() = runTest(timeout = 90.seconds) {
        val stack = stack("dispose")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val hang = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        ops.reconcileGate = {
            entered.complete(Unit)
            withContext(NonCancellable) { hang.await() }
            exited.complete(Unit)
        }
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        ops.state.value = AppSessionState.Active(session)
        sync.start()
        sync.setForeground(true)
        entered.await()
        assertEquals("run must have entered before disposal", 1, ops.reconcileCalls.size)
        sync.dispose()
        hang.complete(Unit)
        exited.await()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.reconcileCalls.size)
        assertFalse(sync.uiState.value.busy)
        assertNull("late result after dispose must not present", sync.uiState.value.stop)
        sync.setForeground(true)
        sync.syncNow()
        sync.prepareRecovery()
        advanceUntilIdle()
        runCurrent()
        assertEquals("post-dispose triggers must not reach operations", 1, ops.reconcileCalls.size)
        assertEquals(0, ops.prepareCalls)
    }

    @Test
    fun foregroundReturnDuringResistantRun_queuesOneFreshRunAfterExit() = runTest(timeout = 90.seconds) {
        val stack = stack("return-hung")
        val session = stack.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session] = stack.epoch(session)
        val hang = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        var first = true
        ops.reconcileGate = {
            if (first) {
                first = false
                entered.complete(Unit)
                withContext(NonCancellable) { hang.await() }
                exited.complete(Unit)
            }
        }
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session)
            sync.start()
            sync.setForeground(true)
            entered.await()
            assertEquals(1, ops.reconcileCalls.size)
            // No new edit: the committed counter is unchanged, yet the
            // foreground return is independent entry demand that must survive
            // retirement. No overlap before the old op exits.
            sync.setForeground(false)
            sync.setForeground(true)
            runCurrent()
            assertEquals("no overlap before exit", 1, ops.reconcileCalls.size)
            hang.complete(Unit)
            exited.await()
            advanceUntilIdle()
            runCurrent()
            // Exactly one fresh-epoch run after exit, with no extra syncNow
            // or authority emission from the test.
            assertEquals("exactly one fresh run after exit", 2, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls[1].session === session)
            assertNull(ops.reconcileCalls[1].stage)
            advanceTimeBy(30_000L)
            advanceUntilIdle()
            runCurrent()
            assertEquals("no third spin after the fresh run", 2, ops.reconcileCalls.size)
            assertFalse(sync.uiState.value.busy)
        } finally {
            hang.complete(Unit)
            sync.dispose()
        }
    }

    @Test
    fun adoptedHPrivateResolve_sameHReturn_laterCommitTriggersOneRun() = runTest(timeout = 90.seconds) {
        val first = stack("adopted-a")
        val second = stack("adopted-h")
        val observedA = first.activate()
        val liveH = second.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[observedA] = first.epoch(observedA)
        ops.epochDefaults[liveH] = second.epoch(liveH)
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            // Start observed on handle A: foreground entry adopts and runs.
            ops.setBacking(AppSessionState.Active(observedA))
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls.single().session === observedA)
            // Publish backing Active(H) WITHOUT dispatching the watcher: no
            // scheduler advancement happens between these steps, so collection
            // still observes A while direct reads are scripted below.
            ops.setBacking(AppSessionState.Active(liveH))
            // Immediate manual action: acquireSlot reads no state, then
            // launchOwned reads the scripted Active(H) for adopt...
            ops.scriptedReads += AppSessionState.Active(liveH)
            // ...then the scripted second-resolve Private snapshot...
            ops.scriptedReads += privateState()
            sync.syncNow()
            // ...and the backing Private -> Active(H) is restored before any
            // watcher dispatch, so the collector later sees only the
            // conflated final H.
            ops.setBacking(privateState())
            ops.setBacking(AppSessionState.Active(liveH))
            advanceUntilIdle()
            runCurrent()
            // Script consumed exactly the intended reads: launchOwned took
            // Active(H) for adopt, then Private for the second resolve.
            assertTrue("script consumed exactly adopt + refusal", ops.scriptedReads.isEmpty())
            // The refused slot launches ZERO H work: only the original
            // A-entry run remains, and the old A observation retired with it.
            assertEquals("refused slot must run nothing", 1, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls.single().session === observedA)
            // Cancelled old A observer stays silent.
            ops.flowFor(observedA).value = 7L
            advanceUntilIdle()
            runCurrent()
            assertEquals("retired A observer must stay silent", 1, ops.reconcileCalls.size)
            // A genuinely later H commit must trigger exactly one valid run:
            // the H observer latch must be live after the dance.
            ops.flowFor(liveH).value = 9L
            advanceUntilIdle()
            runCurrent()
            assertEquals("later H commit must trigger exactly one run", 2, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls.last().session === liveH)
            assertEquals(
                listOf(observedA, liveH),
                ops.reconcileCalls.map { it.session },
            )
            assertFalse("slot must be idle after the valid run", sync.uiState.value.busy)
            assertTrue(
                "borrowed TestScope must stay alive",
                this.coroutineContext[Job]?.isActive == true,
            )
            advanceTimeBy(30_000L)
            advanceUntilIdle()
            runCurrent()
            assertEquals("no stale or private run may follow", 2, ops.reconcileCalls.size)
        } finally {
            sync.dispose()
        }
    }

    @Test
    fun bindingDelay_backlogTriggers_matchingAttachOnly() = runTest(timeout = 90.seconds) {
        val first = stack("bind-a")
        val second = stack("bind-b")
        val session1 = first.activate()
        val session2 = second.activate()
        val ops = FakeSyncOps()
        ops.epochDefaults[session1] = first.epoch(session1)
        ops.epochDefaults[session2] = second.epoch(session2)
        val sync = AccountSyncState(operations = ops, scope = this, debounceMillis = 100L)
        try {
            ops.state.value = AppSessionState.Active(session1)
            sync.start()
            sync.setForeground(true)
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, ops.reconcileCalls.size)
            // Backlog committed while the second binding is still absent: a
            // first observed NONZERO value is new work, never proof that a
            // prior tail already delivered it.
            ops.flowFor(session2).value = 2L
            ops.state.value = AppSessionState.Active(session2)
            advanceUntilIdle()
            runCurrent()
            assertEquals("new handle plus backlog must queue exactly one run", 2, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls.last().session === session2)
            // The retired binding is detached: its commits no longer demand.
            ops.flowFor(session1).value = 9L
            advanceUntilIdle()
            runCurrent()
            assertEquals("retired binding must stay silent", 2, ops.reconcileCalls.size)
            // The matching binding stays live for future commits.
            ops.flowFor(session2).value = 10L
            advanceUntilIdle()
            runCurrent()
            assertEquals(3, ops.reconcileCalls.size)
            assertTrue(ops.reconcileCalls.last().session === session2)
            assertEquals(
                listOf(session1, session2, session2),
                ops.reconcileCalls.map { it.session },
            )
        } finally {
            sync.dispose()
        }
    }
}

/** Normative six-field construction: the production result type declares NO
 * defaults, so every scripted result supplies all fields explicitly. */
private fun reconciliationResult(stop: AppReconciliationStop, epoch: Long, stage: String? = null) =
    AppReconciliationResult(stop, epoch, null, null, null, stage)
