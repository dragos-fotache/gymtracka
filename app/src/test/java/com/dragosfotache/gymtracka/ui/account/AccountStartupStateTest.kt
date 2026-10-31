package com.dragosfotache.gymtracka.ui.account

import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgressPhase
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInIntent
import com.dragosfotache.gymtracka.data.sync.session.RecoveryIdentity
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomPhase
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time controller checks for fresh recovery per action, private intent/operation
 * selection, at-most-one activation, busy/generation fences, cancellation and disposal. Stage IDs
 * are checked for nonblank/freshness semantics rather than a particular derivation.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountStartupStateTest {

    private companion object {
        const val ORIGIN = "https://sync-startup.example.test"
        const val ACCOUNT = "c13c1001-1111-4111-8111-111111111111"
        const val OP_RETAINED = "d13c1001-1111-4111-8111-111111111111"
        const val OP_FRESH_A = "d13c1002-2222-4222-8222-222222222222"
        const val OP_FRESH_B = "d13c1003-3333-4333-8333-333333333333"
        const val OP_FRESH_C = "d13c1004-4444-4444-8444-444444444444"
    }

    private fun binding(account: String = ACCOUNT, generation: Long = 1L) =
        ServiceSessionBinding(ServiceOrigin.parse(ORIGIN), account, generation)

    private fun signedOut(): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = 0L,
        sessionGeneration = 0L,
        cachedAccountId = null,
        phase = RecoveryRoomPhase.SIGNED_OUT,
        identity = null,
        baselineComplete = false,
    )

    private fun preparing(operationId: String = OP_RETAINED, generation: Long = 1L, revision: Long = 3L) =
        RecoveryRoomSnapshot(
            revision = revision,
            sessionGeneration = generation,
            cachedAccountId = ACCOUNT,
            phase = RecoveryRoomPhase.PREPARING,
            identity = RecoveryIdentity(binding(generation = generation), operationId),
            baselineComplete = false,
        )

    private fun activeRoom(operationId: String = OP_RETAINED, generation: Long = 1L) =
        RecoveryRoomSnapshot(
            revision = 5L,
            sessionGeneration = generation,
            cachedAccountId = ACCOUNT,
            phase = RecoveryRoomPhase.ACTIVE,
            identity = RecoveryIdentity(binding(), operationId),
            baselineComplete = true,
        )

    private fun capturedImport(): LegacyImportProgress = LegacyImportProgress(
        phase = LegacyImportProgressPhase.CAPTURED,
        importCount = 2L,
        acceptedCount = 0L,
        activeReplacementRequired = true,
        activeReplacementConsented = false,
    )

    private fun blockedImport(): LegacyImportProgress = LegacyImportProgress(
        phase = LegacyImportProgressPhase.BLOCKED,
        importCount = 1L,
        acceptedCount = 0L,
        activeReplacementRequired = false,
        activeReplacementConsented = false,
    )

    private class FakeOps(
        initial: AppSessionState = AppSessionState.PrivacyLocked,
    ) : AccountStartupOperations {
        override val state = MutableStateFlow(initial)
        var recoverCalls = 0
        var signInCalls = 0
        var completeCalls = 0
        var legacyCalls = 0
        var syncCalls = 0
        val seenIntents = mutableListOf<ForegroundSignInIntent>()
        val seenOperationIds = mutableListOf<String>()
        val seenExpected = mutableListOf<RecoveryRoomSnapshot>()
        val seenStageIds = mutableListOf<String>()
        var recoverGate: (suspend (Int) -> Unit)? = null
        val recoverScript = ArrayDeque<AppSessionState>()
        val recoverFaults = ArrayDeque<Throwable>()
        val signInScript = ArrayDeque<AppSessionState>()
        val completeScript = ArrayDeque<AppSessionState>()
        var legacyValue: LegacyImportProgress? = null
        var legacyFault: Throwable? = null
        var lastRecovered: AppSessionState = initial

        fun answerRecover(vararg values: AppSessionState) {
            recoverScript.addAll(values.toList())
        }

        override suspend fun recover(): AppSessionState {
            recoverCalls += 1
            recoverGate?.invoke(recoverCalls)
            if (recoverFaults.isNotEmpty()) throw recoverFaults.removeFirst()
            val next = if (recoverScript.isNotEmpty()) recoverScript.removeFirst() else lastRecovered
            lastRecovered = next
            state.value = next
            return next
        }

        override suspend fun signIn(
            intent: ForegroundSignInIntent,
            expected: RecoveryRoomSnapshot,
            operationId: String,
        ): AppSessionState {
            signInCalls += 1
            seenIntents += intent
            seenExpected += expected
            seenOperationIds += operationId
            val next = if (signInScript.isNotEmpty()) signInScript.removeFirst() else lastRecovered
            lastRecovered = next
            state.value = next
            return next
        }

        override suspend fun completeActivation(
            expected: RecoveryRoomSnapshot,
            stageId: String,
        ): AppSessionState {
            completeCalls += 1
            seenExpected += expected
            seenStageIds += stageId
            val next = if (completeScript.isNotEmpty()) completeScript.removeFirst() else lastRecovered
            lastRecovered = next
            state.value = next
            return next
        }

        override suspend fun observeLegacyImport(): LegacyImportProgress? {
            legacyCalls += 1
            legacyFault?.let { throw it }
            return legacyValue
        }

        override fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus> {
            syncCalls += 1
            return emptyFlow()
        }
    }

    private class ScriptedUuids(values: List<String>) {
        private val iterator = values.iterator()
        var calls = 0
        fun next(): String {
            calls += 1
            return iterator.next()
        }
    }

    /**
     * UUID supplier that throws CancellationException exactly once at the
     * scripted call, modelling caller cancellation at UUID allocation
     * boundaries. Later calls supply valid canonical UUIDs, proving the
     * cancelled boundary releases occupation for a later retry.
     */
    private class ThrowingUuids(
        private val values: List<String> = listOf(OP_FRESH_A, OP_FRESH_B, OP_FRESH_C),
        private val throwOnCall: Int,
    ) {
        var calls = 0
            private set

        fun next(): String {
            calls += 1
            if (calls == throwOnCall) throw CancellationException("startup-uuid-cancel-fixture")
            return values[(calls - 1) % values.size]
        }
    }

    @Test
    fun cancelDeferredActivation_disarmsRetryWithoutChangingSavedAuthority() = runTest {
        val saved = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
            retryAtMillis = 300_000L,
        )
        val ops = FakeOps(saved)
        val state = AccountStartupState(ops, false, backgroundScope)
        state.start()
        runCurrent()
        state.continuePreparation()
        runCurrent()
        assertEquals(AccountStartupError.PREPARATION_PAUSED, state.uiState.value.error)
        val completions = ops.completeCalls
        state.cancel()
        runCurrent()
        assertEquals(AccountStartupError.CANCELLED, state.uiState.value.error)
        assertEquals(saved, ops.state.value)
        assertEquals(completions, ops.completeCalls)
        state.dispose()
    }

    @Test
    fun laterSuccessfulSyncClearsFailedAuthentication_butKeepsUnrelatedEditorError() = runTest {
        val failed = AccountStartupState(FakeOps(), false, backgroundScope)
        failed.signIn()
        runCurrent()
        assertEquals(AccountStartupError.SIGN_IN_FAILED, failed.uiState.value.error)
        failed.onSyncSucceeded()
        assertEquals(null, failed.uiState.value.error)
        failed.dispose()

        val ops = FakeOps().also {
            it.recoverFaults.add(AccountStartupException(AccountStartupError.EDITOR_OPEN))
        }
        val editor = AccountStartupState(ops, true, backgroundScope)
        editor.signIn()
        runCurrent()
        assertEquals(AccountStartupError.EDITOR_OPEN, editor.uiState.value.error)
        editor.onSyncSucceeded()
        assertEquals(AccountStartupError.EDITOR_OPEN, editor.uiState.value.error)
        editor.dispose()
    }

    @Test
    fun nullOperations_isNoOpUnavailable() = runTest {
        val startup = AccountStartupState(
            operations = null,
            signInAvailable = false,
            scope = backgroundScope,
        )
        assertEquals(AccountStartupError.UNAVAILABLE, startup.uiState.value.error)
        assertFalse(startup.configurationAvailable)
        assertFalse(startup.signInAvailable)
        startup.start()
        startup.signIn()
        startup.continuePreparation()
        startup.cancel()
        runCurrent()
        assertFalse(startup.uiState.value.busy)
        assertEquals(AccountStartupError.UNAVAILABLE, startup.uiState.value.error)
        startup.dispose()
    }

    @Test
    fun start_isIdempotentSingleRecoveryWithoutActivation() = runTest {
        val ops = FakeOps()
        ops.answerRecover(AppSessionState.Private(com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED, signedOut()))
        val startup = AccountStartupState(ops, true, backgroundScope)
        assertTrue(startup.configurationAvailable)
        assertTrue(startup.signInAvailable)
        startup.start()
        startup.start()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.recoverCalls)
        assertEquals(0, ops.signInCalls)
        assertEquals(0, ops.completeCalls)
        assertFalse(startup.uiState.value.busy)
        startup.dispose()
    }

    @Test
    fun signIn_initialUsesFreshUuidAndAtMostOneActivation() = runTest {
        val ops = FakeOps()
        val fresh = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
            signedOut(),
        )
        ops.answerRecover(fresh, fresh)
        // Distinct post-sign-in fresh read: activation must use this newest
        // snapshot, never the stale pre-sign-in one.
        val postSignInRoom = preparing(revision = 9L)
        ops.answerRecover(AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            postSignInRoom,
        ))
        // signIn lands PREPARING, then exactly one activation attempt follows.
        ops.signInScript += AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
        )
        ops.completeScript += AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
        )
        val uuids = ScriptedUuids(listOf(OP_FRESH_A, OP_FRESH_B, OP_FRESH_C))
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        val recoverBeforeSignIn = ops.recoverCalls
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.signInCalls)
        assertEquals(ForegroundSignInIntent.INITIAL_SIGN_IN, ops.seenIntents.single())
        assertEquals(OP_FRESH_A, ops.seenOperationIds.single())
        assertTrue("activation at most once", ops.completeCalls <= 1)
        if (ops.completeCalls == 1) {
            val stageId = ops.seenStageIds.single()
            assertTrue(stageId.isNotBlank())
            // Settled contract: one freshly supplied canonical UUID per activation,
            // never a provider-loop derivation of the sign-in operation id.
            java.util.UUID.fromString(stageId)
            assertNotEquals(OP_FRESH_A, stageId)
            // Fresh recover per action: activation expected must be the newest
            // post-sign-in snapshot, never a stale advisory copy.
            assertEquals(postSignInRoom, ops.seenExpected.last())
        }
        // Second activation draws a distinct fresh stage UUID, never a repeat.
        ops.signInScript += AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
        )
        ops.completeScript += AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
        )
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        if (ops.completeCalls == 2) {
            val first = ops.seenStageIds[0]
            val second = ops.seenStageIds[1]
            java.util.UUID.fromString(second)
            assertNotEquals("each activation needs a fresh stage uuid", first, second)
        }
        assertTrue(ops.recoverCalls > recoverBeforeSignIn)
        startup.dispose()
    }

    @Test
    fun signIn_retainedActiveUsesSameAccountFreshUuidDifferentFromCurrent() = runTest {
        val ops = FakeOps()
        val retained = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
            activeRoom(OP_RETAINED),
        )
        ops.answerRecover(retained, retained)
        ops.signInScript += retained
        val uuids = ScriptedUuids(listOf(OP_FRESH_A, OP_FRESH_B))
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.signInCalls)
        assertEquals(ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION, ops.seenIntents.single())
        assertNotEquals(OP_RETAINED, ops.seenOperationIds.single())
        assertEquals(OP_FRESH_A, ops.seenOperationIds.single())
        startup.dispose()
    }

    @Test
    fun signIn_blockedImportRefusesFreshSessionAndActivation() = runTest {
        val ops = FakeOps()
        val snapshot = preparing(OP_RETAINED)
        val retained = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            snapshot,
        )
        ops.answerRecover(retained, retained)
        ops.legacyValue = blockedImport()
        ops.signInScript += retained
        val uuids = ScriptedUuids(listOf(OP_FRESH_A, OP_FRESH_B))
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, ops.signInCalls)
        assertEquals("blocked observation refuses activation", 0, ops.completeCalls)
        startup.dispose()
    }

    @Test
    fun signIn_interruptedOrdinaryUsesFreshSessionThenFreshActivationExpectation() = runTest {
        for (phase in listOf(RecoveryRoomPhase.PREPARING, RecoveryRoomPhase.TRANSITIONING)) {
            val before = preparing().copy(phase = phase)
            val after = preparing(OP_FRESH_A, generation = 2L, revision = 6L)
            val ops = FakeOps()
            ops.answerRecover(
                AppSessionState.Private(com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED, before),
                AppSessionState.Private(com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED, after),
            )
            ops.signInScript += AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED, after,
            )
            val uuids = ScriptedUuids(listOf(OP_FRESH_A, OP_FRESH_B))
            val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
            startup.signIn()
            runCurrent()
            assertEquals(listOf(ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION), ops.seenIntents)
            assertEquals(listOf(OP_FRESH_A), ops.seenOperationIds)
            assertEquals(listOf(before, after), ops.seenExpected)
            assertEquals(listOf(OP_FRESH_B), ops.seenStageIds)
            startup.dispose()
        }
    }

    @Test
    fun finishSigningIn_resumesFreshSavedExpectationWithoutSignInOrNewReservation() = runTest {
        val saved = preparing()
        val ops = FakeOps()
        ops.answerRecover(AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED, saved,
        ))
        val startup = AccountStartupState(ops, false, backgroundScope, uuidProvider = { OP_FRESH_A })
        startup.continuePreparation()
        runCurrent()
        assertEquals(0, ops.signInCalls)
        assertEquals(listOf(saved), ops.seenExpected)
        assertEquals(listOf(OP_FRESH_A), ops.seenStageIds)
        startup.dispose()
    }

    @Test
    fun signIn_interruptedRejectsReusedOperationWithoutEffects() = runTest {
        val ops = FakeOps()
        ops.answerRecover(AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED, preparing(),
        ))
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { OP_RETAINED })
        startup.signIn()
        runCurrent()
        assertEquals(0, ops.signInCalls)
        assertEquals(0, ops.completeCalls)
        assertEquals(AccountStartupError.UNAVAILABLE, startup.uiState.value.error)
        startup.dispose()
    }

    @Test
    fun signIn_preparingValidImportUsesPrivateImportFreshUuid() = runTest {
        val ops = FakeOps()
        val snapshot = preparing(OP_RETAINED)
        val retained = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            snapshot,
        )
        ops.answerRecover(retained, retained)
        ops.legacyValue = capturedImport()
        ops.signInScript += retained
        val uuids = ScriptedUuids(listOf(OP_FRESH_A, OP_FRESH_B))
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.signInCalls)
        assertEquals(
            ForegroundSignInIntent.PRIVATE_IMPORT_REAUTHENTICATION,
            ops.seenIntents.single(),
        )
        assertEquals(OP_FRESH_A, ops.seenOperationIds.single())
        startup.dispose()
    }

    @Test
    fun corruptImportObservation_refusesSignInAndActivation() = runTest {
        val ops = FakeOps()
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
                preparing(),
            ),
        )
        ops.legacyFault = IllegalStateException("startup-corrupt-fixture")
        val startup = AccountStartupState(ops, true, backgroundScope)
        startup.start()
        advanceUntilIdle()
        runCurrent()
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        startup.continuePreparation()
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, ops.completeCalls)
        assertEquals(0, ops.signInCalls)
        assertFalse(startup.uiState.value.busy)
        assertEquals(AccountStartupError.UNAVAILABLE, startup.uiState.value.error)
        startup.dispose()
    }

    @Test
    fun corruptObservation_retainsPriorProgressNeverNull() = runTest {
        val ops = FakeOps()
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
                preparing(),
            ),
        )
        ops.legacyValue = capturedImport()
        val startup = AccountStartupState(ops, true, backgroundScope)
        startup.start()
        advanceUntilIdle()
        runCurrent()
        assertEquals(LegacyImportProgressPhase.CAPTURED, startup.uiState.value.importProgress?.phase)
        // Observation failure retains prior progress instead of nulling it, and
        // surfaces the settled UNAVAILABLE mapping (not a silent null pass).
        ops.legacyFault = IllegalStateException("startup-observe-fault")
        startup.continuePreparation()
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, ops.completeCalls)
        assertEquals(LegacyImportProgressPhase.CAPTURED, startup.uiState.value.importProgress?.phase)
        assertEquals(AccountStartupError.UNAVAILABLE, startup.uiState.value.error)
        startup.dispose()
    }

    @Test
    fun busySecondAction_isIgnoredWithoutQueueing() = runTest {
        val ops = FakeOps()
        val gate = CompletableDeferred<Unit>()
        ops.recoverGate = { if (it == 1) gate.await() }
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
        )
        val startup = AccountStartupState(ops, true, backgroundScope)
        startup.start()
        runCurrent()
        assertTrue(startup.uiState.value.busy)
        startup.signIn()
        startup.continuePreparation()
        runCurrent()
        assertEquals("second actions must not queue fresh work while busy", 1, ops.recoverCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.recoverCalls)
        assertFalse(startup.uiState.value.busy)
        startup.dispose()
    }

    @Test
    fun cancel_settlesReadOnlyRecoveryWithoutAutoActivation() = runTest {
        val ops = FakeOps()
        val gate = CompletableDeferred<Unit>()
        ops.recoverGate = { if (it == 1) gate.await() }
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
        )
        val startup = AccountStartupState(ops, true, backgroundScope)
        startup.start()
        runCurrent()
        startup.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, ops.completeCalls)
        assertTrue("cancel must settle with a fresh read-only recovery", ops.recoverCalls >= 2)
        assertFalse(startup.uiState.value.busy)
        // Settled contract: normal user cancel after a Private durable settle
        // presents CANCELLED.
        assertEquals(AccountStartupError.CANCELLED, startup.uiState.value.error)
        startup.dispose()
    }

    @Test
    fun dispose_doesNotCancelBorrowedScopeAndLateCompletionCannotOverwriteNewer() = runTest {
        val ops = FakeOps()
        val firstGate = CompletableDeferred<Unit>()
        var gateCount = 0
        ops.recoverGate = {
            gateCount += 1
            if (gateCount == 1) firstGate.await()
        }
        val firstState = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
            signedOut(),
        )
        val secondState = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
        )
        ops.answerRecover(firstState, secondState)
        val startup = AccountStartupState(ops, true, backgroundScope)
        startup.start()
        runCurrent()
        startup.dispose()
        // Borrowed scope survives disposal.
        var borrowedRan = false
        backgroundScope.async { borrowedRan = true }
        runCurrent()
        assertTrue(borrowedRan)
        // Late old completion cannot release a newer slot or grant visibility.
        firstGate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, ops.completeCalls)
        assertEquals(0, ops.signInCalls)
        try {
            backgroundScope.async { throw CancellationException("startup-scope-live") }.await()
            assertTrue(false)
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun busySignIn_cancelIsUsableAndLaterActionPermitted() = runTest {
        val ops = FakeOps()
        val gate = CompletableDeferred<Unit>()
        ops.recoverGate = { if (it == 1) gate.await() }
        val signedOutPrivate = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
            signedOut(),
        )
        ops.answerRecover(signedOutPrivate, signedOutPrivate, signedOutPrivate)
        val startup = AccountStartupState(ops, true, backgroundScope)
        startup.signIn()
        runCurrent()
        assertTrue(startup.uiState.value.busy)
        // Busy initial sign-in must offer usable cancellation (not a stuck spinner).
        startup.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertFalse(startup.uiState.value.busy)
        assertEquals(0, ops.completeCalls)
        // Cancellation fences owned work but permits a later explicit action.
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertTrue(ops.signInCalls >= 1)
        assertFalse(startup.uiState.value.busy)
        startup.dispose()
    }

    @Test
    fun retainedCachedAccountMissingVault_offersSameAccountActionNeverInitial() = runTest {
        val ops = FakeOps()
        // Missing vault with retained Room cache: SIGNED_OUT identity=null but
        // cachedAccountId retained. Foreground same-account action is required;
        // initial sign-in must not bypass the retained cache.
        val retainedCache = RecoveryRoomSnapshot(
            revision = 2L,
            sessionGeneration = 0L,
            cachedAccountId = ACCOUNT,
            phase = RecoveryRoomPhase.SIGNED_OUT,
            identity = null,
            baselineComplete = false,
        )
        val retained = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
            retainedCache,
        )
        ops.answerRecover(retained, retained)
        ops.signInScript += retained
        val uuids = ScriptedUuids(listOf(OP_FRESH_A, OP_FRESH_B))
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.signInCalls)
        assertNotEquals(
            "retained cache must not bypass to initial sign-in",
            ForegroundSignInIntent.INITIAL_SIGN_IN,
            ops.seenIntents.single(),
        )
        assertEquals(ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION, ops.seenIntents.single())
        startup.dispose()
    }

    @Test
    fun corruptImportDuringStart_isErrorNeverNullMigrationAndRetryPermitted() = runTest {
        val ops = FakeOps()
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
                preparing(),
            ),
        )
        ops.legacyFault = IllegalStateException("startup-corrupt-start")
        val startup = AccountStartupState(ops, true, backgroundScope)
        startup.start()
        advanceUntilIdle()
        runCurrent()
        // Corrupt observation is a retained error, never a null/no-migration pass.
        assertEquals(0, ops.completeCalls)
        assertEquals(0, ops.signInCalls)
        assertFalse(startup.uiState.value.busy)
        assertEquals(AccountStartupError.UNAVAILABLE, startup.uiState.value.error)
        // Clearing the corruption permits a later explicit action (no permanent fence).
        ops.legacyFault = null
        ops.legacyValue = blockedImport()
        startup.continuePreparation()
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, ops.completeCalls)
        startup.dispose()
    }

    @Test
    fun failedPostSignInFreshRecovery_neverActivatesViaStaleSnapshot() = runTest {
        val ops = FakeOps()
        val retained = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
            signedOut(),
        )
        val preparingState = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
        )
        ops.answerRecover(retained, retained)
        ops.signInScript += preparingState
        ops.legacyValue = blockedImport()
        // Fresh pre-sign-in recover succeeds; fresh post-sign-in recover fails.
        // The stale pre-sign-in snapshot must never authorize activation.
        ops.recoverFaults += IllegalStateException("startup-post-signin-recover-fault")
        val uuids = ScriptedUuids(listOf(OP_FRESH_A, OP_FRESH_B, OP_FRESH_C))
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        // Consume the scripted post-sign-in fault on the next fresh recover by
        // routing it after sign-in: first sign-in uses recover #2 (ok), the
        // activation-path fresh recover hits the fault.
        ops.recoverFaults.clear()
        ops.answerRecover(retained)
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        ops.recoverFaults += IllegalStateException("startup-post-signin-recover-fault")
        startup.continuePreparation()
        advanceUntilIdle()
        runCurrent()
        assertEquals("failed fresh recovery must not activate via stale snapshot", 0, ops.completeCalls)
        assertFalse(startup.uiState.value.busy)
        startup.dispose()
    }

    @Test
    fun cancelBeforeDispatch_fencesOwnedRereadAndPermitsLaterAction() = runTest {
        val ops = FakeOps()
        val signedOutPrivate = AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
            signedOut(),
        )
        ops.answerRecover(signedOutPrivate, signedOutPrivate)
        val startup = AccountStartupState(ops, true, backgroundScope)
        startup.start()
        // Cancel synchronously before dispatch: owned reread fenced, no launch leak.
        startup.cancel()
        advanceUntilIdle()
        runCurrent()
        assertFalse(startup.uiState.value.busy)
        assertEquals(0, ops.completeCalls)
        assertEquals(0, ops.signInCalls)
        // A later explicit action is still permitted after the fenced cancel.
        startup.start()
        advanceUntilIdle()
        runCurrent()
        assertTrue(ops.recoverCalls >= 1)
        assertFalse(startup.uiState.value.busy)
        startup.dispose()
    }

    @Test
    fun borrowedScopeCancelledBeforeDispatch_releasesOccupancyWithoutSeamAndStaleCannotClearNewer() = runTest {
        val ops = FakeOps()
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
        )
        // Older owned operation: scheduled, then orphaned by borrowed-scope
        // cancellation strictly between scheduling and dispatch. Its body must
        // never execute, so no seam may start.
        val borrowedA = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val older = AccountStartupState(ops, true, borrowedA)
        older.start()
        borrowedA.cancel()
        advanceUntilIdle()
        runCurrent()
        assertEquals(0, ops.recoverCalls)
        assertEquals(0, ops.signInCalls)
        assertEquals(0, ops.completeCalls)
        // Settled contract: pre-body cancellation presents busy=false and CANCELLED.
        assertFalse(older.uiState.value.busy)
        assertEquals(AccountStartupError.CANCELLED, older.uiState.value.error)
        // A newer operation on a live scope must still be admitted: occupancy
        // was released, never leaked by the orphaned slot.
        val gate = CompletableDeferred<Unit>()
        ops.recoverGate = { if (it == 1) gate.await() }
        val newer = AccountStartupState(ops, true, backgroundScope)
        newer.start()
        runCurrent()
        assertTrue(newer.uiState.value.busy)
        assertEquals(1, ops.recoverCalls)
        // Any stale completion from the orphaned slot lands while the newer
        // operation is busy: it must neither clear busy nor fabricate error.
        advanceUntilIdle()
        runCurrent()
        assertTrue(newer.uiState.value.busy)
        assertEquals(1, ops.recoverCalls)
        assertEquals(null, newer.uiState.value.error)
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertFalse(newer.uiState.value.busy)
        assertEquals(1, ops.recoverCalls)
        assertEquals(null, newer.uiState.value.error)
        newer.dispose()
    }

    @Test
    fun uuidCancel_atInitialSignInOperationSelection_settlesCancelledWithoutEffect() = runTest {
        val ops = FakeOps()
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
        )
        val uuids = ThrowingUuids(throwOnCall = 1)
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertFalse(startup.uiState.value.busy)
        assertEquals(AccountStartupError.CANCELLED, startup.uiState.value.error)
        assertEquals(0, ops.signInCalls)
        assertEquals(0, ops.completeCalls)
        var borrowedRan = false
        backgroundScope.async { borrowedRan = true }
        runCurrent()
        assertTrue(borrowedRan)
        // Occupation released: a later action proceeds past the cancelled boundary.
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.signInCalls)
        startup.dispose()
    }

    @Test
    fun uuidCancel_atPostGrantSignInStageAllocation_noActivationEffect() = runTest {
        val ops = FakeOps()
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.SIGN_IN_REQUIRED,
                signedOut(),
            ),
        )
        ops.signInScript += AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
        )
        val uuids = ThrowingUuids(throwOnCall = 2)
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        startup.signIn()
        advanceUntilIdle()
        runCurrent()
        assertFalse(startup.uiState.value.busy)
        assertEquals(AccountStartupError.CANCELLED, startup.uiState.value.error)
        assertEquals("grant reached before stage cancellation", 1, ops.signInCalls)
        assertEquals("stage never allocated", 0, ops.completeCalls)
        var borrowedRan = false
        backgroundScope.async { borrowedRan = true }
        runCurrent()
        assertTrue(borrowedRan)
        startup.dispose()
    }

    @Test
    fun uuidCancel_atContinuePreparationStageAllocation_noActivationEffect() = runTest {
        val ops = FakeOps()
        ops.answerRecover(
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
                preparing(),
            ),
            AppSessionState.Private(
                com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
                preparing(),
            ),
        )
        val uuids = ThrowingUuids(throwOnCall = 1)
        val startup = AccountStartupState(ops, true, backgroundScope, uuidProvider = { uuids.next() })
        startup.start()
        advanceUntilIdle()
        runCurrent()
        startup.continuePreparation()
        advanceUntilIdle()
        runCurrent()
        assertFalse(startup.uiState.value.busy)
        assertEquals(AccountStartupError.CANCELLED, startup.uiState.value.error)
        assertEquals(0, ops.signInCalls)
        assertEquals(0, ops.completeCalls)
        var borrowedRan = false
        backgroundScope.async { borrowedRan = true }
        runCurrent()
        assertTrue(borrowedRan)
        // Occupation released: explicit retry allocates and activates.
        ops.completeScript += AppSessionState.Private(
            com.dragosfotache.gymtracka.data.sync.session.AppSessionReason.PREPARATION_REQUIRED,
            preparing(),
        )
        startup.continuePreparation()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, ops.completeCalls)
        startup.dispose()
    }
}
