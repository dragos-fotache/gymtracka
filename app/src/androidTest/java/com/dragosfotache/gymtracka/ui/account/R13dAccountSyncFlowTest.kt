package com.dragosfotache.gymtracka.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.session.AccountRecoveryConfirmation
import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationResult
import com.dragosfotache.gymtracka.data.sync.session.AppSessionException
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppSessionAuthApi
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransport
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInIntent
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.ui.AccountGymAppState
import com.dragosfotache.gymtracka.ui.GymAppClock
import com.dragosfotache.gymtracka.ui.RestTimerHardware
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R13d independent QA: account-root sync wiring, destructive-warning flow and
 * account-root editor/timer boundaries through the controlled root.
 *
 * Derived ONLY from docs/remaining-work/R13d-task.md (ui.account normative
 * API plus the coordinator triage contracts: sync/recovery controls live
 * inside the account status dialog behind account-open with scrollable
 * content; the controlled AccountStartupApp with the real controller is the
 * normative trigger path; unbounded test UUID supply; edits-drain failure
 * oracle outside Main; entry quiescence before Save with push-specific proof;
 * full owned teardown) and QA-owned R13c harness shapes (AccountGymAppState
 * runtime, FakeOps publication, startup.start()+idle, journey-ready appState,
 * AdvancingClock, NoOpHardware, whole-second rest_seconds oracle). Real
 * Room/activation/transports; no sleeps (bounded waitUntil); hand-written
 * rejected outcome only for seeding; no production encoder oracles.
 */
@RunWith(AndroidJUnit4::class)
class R13dAccountSyncFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var contentEnabled by mutableStateOf(true)
    private var content: (@Composable () -> Unit)? by mutableStateOf(null)

    private val harness = AppSessionHarness("r13d-flow")
    private val dbs = mutableListOf<GymDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val runtimes = mutableListOf<AccountGymAppState>()
    private val startups = mutableListOf<AccountStartupState>()
    private val syncStates = mutableListOf<AccountSyncState>()

    @After
    fun tearDown() {
        runCatching { composeRule.runOnUiThread { contentEnabled = false; content = null } }
        runCatching { composeRule.waitForIdle() }
        syncStates.forEach { runCatching { it.dispose() } }
        syncStates.clear()
        startups.forEach { runCatching { it.dispose() } }
        startups.clear()
        runtimes.forEach { runCatching { it.dispose() } }
        runtimes.clear()
        scopes.forEach { runCatching { it.cancel() } }
        runBlocking {
            scopes.forEach { scope ->
                runCatching { withTimeout(5_000L) { scope.coroutineContext[Job]?.join() } }
            }
        }
        scopes.clear()
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harness.clean()
    }

    private class FakeOps(initial: AppSessionState = AppSessionState.PrivacyLocked) :
        AccountStartupOperations {
        override val state = MutableStateFlow(initial)

        fun publish(next: AppSessionState) {
            state.value = next
        }

        override suspend fun recover(): AppSessionState = state.value

        override suspend fun signIn(
            intent: ForegroundSignInIntent,
            expected: RecoveryRoomSnapshot,
            operationId: String,
        ): AppSessionState = state.value

        override suspend fun completeActivation(
            expected: RecoveryRoomSnapshot,
            stageId: String,
        ): AppSessionState = state.value

        override suspend fun observeLegacyImport(): LegacyImportProgress? = null

        override fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus> =
            emptyFlow()
    }

    private class AdvancingClock(var elapsed: Long = 0L) : GymAppClock {
        override fun wallTimeMillis(): Long = AppSessionFixtures.FIXED_CLOCK + elapsed
        override fun elapsedRealtimeMillis(): Long = elapsed
    }

    private class NoOpHardware : RestTimerHardware {
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) = Unit
        override fun releaseWakeLock() = Unit
    }

    private data class Fixture(
        val database: GymDatabase,
        val transport: AppSessionTransport,
        val runtime: AccountGymAppState,
        val clock: AdvancingClock,
    )

    private fun fixture(): Fixture {
        val database = harness.openInMemoryDb().also { dbs += it }
        val transport = AppSessionTransport()
        val clock = AdvancingClock()
        val runtime = AccountGymAppState(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = harness.newVault(),
            authApi = AppSessionAuthApi(),
            transport = transport,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = clock,
            timerHardware = NoOpHardware(),
            // Unbounded supply: preparation plus repository/child generation
            // share this provider, and a 5-item iterator would exhaust.
            uuidProvider = { UUID.randomUUID().toString() },
        ).also { runtimes += it }
        return Fixture(database, transport, runtime, clock)
    }

    private fun Fixture.activate() {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        runBlocking {
            val preparing = runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(),
            )
            assertTrue(preparing is AppSessionState.Private)
            val active = runtime.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.STAGE,
            )
            assertTrue("setup must reach Active, was $active", active is AppSessionState.Active)
        }
    }

    private fun Fixture.startup(ops: FakeOps): AccountStartupState =
        AccountStartupState(
            operations = ops,
            signInAvailable = true,
            scope = MainScope().also { scopes += it },
        ).also { startups += it }

    private fun Fixture.syncState(debounce: Long = 50L): AccountSyncState =
        AccountSyncState(
            SessionAccountSyncOperations(runtime),
            MainScope().also { scopes += it },
            debounce,
        ).also { syncStates += it }

    private fun Fixture.restSeconds(setId: String): Long? {
        database.openHelper.readableDatabase.query(
            "SELECT rest_seconds FROM workout_sets WHERE id = ?",
            arrayOf(setId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "seeded set must exist" }
            return if (cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    private fun showContent(content: @Composable () -> Unit) {
        composeRule.setContent { if (contentEnabled) this.content?.invoke() }
        composeRule.runOnUiThread { this.content = content }
    }

    private fun openSyncDialog() {
        composeRule.waitUntil(15_000L) {
            try {
                composeRule.onNodeWithTag("account-open").assertExists()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithTag("account-open").assertIsDisplayed()
        composeRule.onNodeWithTag("account-open").assertIsEnabled()
        composeRule.onNodeWithTag("account-open").performClick()
        composeRule.runOnIdle { }
    }

    private fun ensureIdle(sync: AccountSyncState) {
        composeRule.runOnIdle { }
        composeRule.waitUntil(15_000L) { !sync.uiState.value.busy }
    }

    private fun scrollIfNeeded(tag: String) {
        try {
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        } catch (_: AssertionError) {
            composeRule.onNodeWithTag(tag).performScrollTo()
        }
    }

    private data class RowFp(
        val outbox: List<List<String?>>,
        val cacheEpoch: Long?,
        val materialized: Long?,
        val dirty: Long?,
        val cursorNull: Boolean,
        val journal: List<String?>,
    )

    private val OUTBOX_COLUMNS = listOf(
        "sequence", "mutation_id", "account_id", "aggregate_type", "aggregate_id",
        "mutation_type", "local_generation", "payload_version", "payload_json", "state",
        "attempt_count", "retry_after_millis", "error_code", "error_message",
    )

    private fun fingerprintOf(database: GymDatabase): RowFp = RowFp(
        outbox = harness.outboxSnapshot(database, AppSessionFixtures.ACCOUNT),
        cacheEpoch = harness.cacheEpoch(database, AppSessionFixtures.ACCOUNT),
        materialized = harness.materializedEpoch(database, AppSessionFixtures.ACCOUNT),
        dirty = harness.projectionDirty(database, AppSessionFixtures.ACCOUNT),
        cursorNull = harness.syncStateCursor(database, AppSessionFixtures.ACCOUNT) == null,
        journal = harness.journalRow(database),
    )

    /** Changed-element NAMES and row counts only. Never values, IDs or payloads. */
    private fun diffFpNames(before: RowFp, after: RowFp): List<String> = buildList {
        if (before.outbox.size != after.outbox.size) {
            add("outbox.rowCount:${before.outbox.size}->${after.outbox.size}")
        }
        val rows = minOf(before.outbox.size, after.outbox.size)
        for (i in 0 until rows) {
            for (c in OUTBOX_COLUMNS.indices) {
                if (before.outbox[i][c] != after.outbox[i][c]) add("outbox.row$i.${OUTBOX_COLUMNS[c]}")
            }
        }
        if (before.cacheEpoch != after.cacheEpoch) add("cache.cacheEpoch")
        if (before.materialized != after.materialized) add("cache.materializedEpoch")
        if (before.dirty != after.dirty) add("cache.projectionDirty")
        if (before.cursorNull != after.cursorNull) add("syncState.cursorNull")
        if (before.journal != after.journal) add("journal")
    }

    /**
     * Counting delegate over the real facade operations: every call is
     * delegated unchanged (never simulated), only invocations are counted so
     * tests can prove whether a deliberate review reached the facade.
     */
    private class CountingSyncOps(
        val real: AccountSyncOperations,
        val capture: () -> RowFp,
        val diffNames: (RowFp, RowFp) -> List<String>,
    ) : AccountSyncOperations {
        var prepareCalls = 0
        var confirmCalls = 0
        val seenConfirmations = mutableListOf<AccountRecoveryConfirmation>()
        /** Fixed outcome labels only: result stop strings or exception class
         * plus guard file:line and closed reason. Never IDs, bodies, causes
         * or messages. */
        val confirmOutcomes = mutableListOf<String>()
        /** Baseline fingerprint installed by the test right after a fresh
         * token is observed; compared at confirm entry and after refusal. */
        var baselineFp: RowFp? = null
        override val state: StateFlow<AppSessionState>
            get() = real.state
        override fun committedEdits(session: AppAccountSession): Flow<Long> =
            real.committedEdits(session)
        override suspend fun observeCacheEpoch(session: AppAccountSession): Long =
            real.observeCacheEpoch(session)
        override suspend fun reconcile(
            session: AppAccountSession,
            expectedCacheEpoch: Long,
            restartStageId: String?,
        ): AppReconciliationResult = real.reconcile(session, expectedCacheEpoch, restartStageId)
        override suspend fun prepareOrdinaryRecovery(
            session: AppAccountSession,
        ): AccountRecoveryConfirmation? {
            prepareCalls += 1
            return real.prepareOrdinaryRecovery(session)
        }
        override suspend fun recoverOrdinaryRejection(
            session: AppAccountSession,
            confirmation: AccountRecoveryConfirmation,
        ): AppReconciliationResult {
            confirmCalls += 1
            seenConfirmations += confirmation
            val entry = capture()
            baselineFp?.let { base ->
                println(
                    "R13D-ROWDIFF-DIAG phase=confirm-entry changed=${diffNames(base, entry)} " +
                        "rows=${entry.outbox.size}",
                )
            }
            try {
                val result = real.recoverOrdinaryRejection(session, confirmation)
                confirmOutcomes += "stop=" + result.stop.toString()
                return result
            } catch (e: Throwable) {
                val post = capture()
                println(
                    "R13D-ROWDIFF-DIAG phase=refusal-purity changed=${diffNames(entry, post)} " +
                        "rows=${post.outbox.size}",
                )
                val reason = (e as? AppSessionException)?.reason
                val frame = e.stackTrace.firstOrNull {
                    it.className.startsWith("com.dragosfotache.gymtracka")
                } ?: e.stackTrace.firstOrNull()
                val at = if (frame != null) "${frame.fileName}:${frame.lineNumber}" else "unknown"
                confirmOutcomes += "error=" + e.javaClass.simpleName + " at=$at" +
                    (if (reason != null) " reason=$reason" else "")
                throw e
            }
        }
    }

    @Test
    fun manualSync_runsFreshEpochRun_withoutError() {
        val fix = fixture()
        fix.activate()
        val ops = FakeOps()
        ops.publish(fix.runtime.sessionCoordinator.state.value)
        val startup = fix.startup(ops)
        val consent = AccountImportConsent()
        val sync = fix.syncState()
        sync.start()
        // Deterministic post-render traffic: every call succeeds with an
        // empty pull; marks separate entry proof from manual proof.
        fix.transport.serve = { _, _ ->
            AppSessionTransport.Frame(200, AppSessionFixtures.EMPTY_PULL_BODY)
        }
        val mark0 = fix.transport.capturedRequests.size
        showContent { AccountStartupApp(fix.runtime, startup, consent, sync) }
        startup.start()
        composeRule.waitUntil(10_000L) { !startup.uiState.value.busy }
        // Initial foreground entry: a NEW pull after the pre-render mark,
        // then quiescence before touching the dialog.
        composeRule.waitUntil(15_000L) {
            fix.transport.capturedRequests.size > mark0 &&
                fix.transport.capturedRequests.any { it.path == "/v1/sync/pull" }
        }
        composeRule.waitUntil(15_000L) { !sync.uiState.value.busy }
        openSyncDialog()
        composeRule.waitUntil(15_000L) {
            try {
                composeRule.onNodeWithTag("account-sync-now").assertExists()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        scrollIfNeeded("account-sync-now")
        composeRule.onNodeWithTag("account-sync-now").assertIsDisplayed()
        composeRule.onNodeWithTag("account-sync-now").assertIsEnabled()
        ensureIdle(sync)
        composeRule.onNodeWithTag("account-sync-now").performClick()
        val mark1 = fix.transport.capturedRequests.size
        composeRule.waitUntil(15_000L) {
            fix.transport.capturedRequests.subList(mark1, fix.transport.capturedRequests.size).any { it.path == "/v1/sync/pull" }
        }
        composeRule.waitUntil(15_000L) { !sync.uiState.value.busy }
        composeRule.runOnIdle { }
        composeRule.onNodeWithTag("account-sync-error").assertDoesNotExist()
    }

    @Test
    fun recoveryPrepare_showsWarning_cancelPreserves_confirmDiscards() {
        val fix = fixture()
        fix.activate()
        val session =
            (fix.runtime.sessionCoordinator.state.value as AppSessionState.Active).session
        runBlocking {
            session.repository.createCustomExercise("R13d Flow Ordinary", null, emptySet(), null, null, "")
        }
        val mutationId = checkNotNull(
            harness.outboxSnapshot(fix.database, AppSessionFixtures.ACCOUNT)[0][1],
        )
        fix.transport.queueOk(
            "{\"results\":[{\"mutationId\":" + org.json.JSONObject.quote(mutationId) +
                ",\"status\":\"rejected\",\"errorCode\":\"not_found\",\"batch\":null}]}",
        )
        fix.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        runBlocking {
            fix.runtime.sessionCoordinator.reconcile(
                session,
                fix.runtime.sessionCoordinator.observeCacheEpoch(session),
            )
        }
        fix.transport.queue.clear()
        // Deterministic post-seed traffic: the rejected head replays its
        // definitive outcome, snapshot/tail succeed empty.
        fix.transport.serve = { _, request ->
            when {
                request.path.contains("push") ->
                    AppSessionTransport.Frame(
                        200,
                        "{\"results\":[{\"mutationId\":" + org.json.JSONObject.quote(mutationId) + ",\"status\":\"rejected\",\"errorCode\":\"not_found\",\"batch\":null}]}",
                    )
                request.path.contains("snapshot") ->
                    AppSessionTransport.Frame(200, AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
                else -> AppSessionTransport.Frame(200, AppSessionFixtures.EMPTY_PULL_BODY)
            }
        }
        assertEquals(1L, harness.outboxCount(fix.database, AppSessionFixtures.ACCOUNT))
        val ops = FakeOps()
        ops.publish(fix.runtime.sessionCoordinator.state.value)
        val startup = fix.startup(ops)
        val consent = AccountImportConsent()
        val counting = CountingSyncOps(
            real = SessionAccountSyncOperations(fix.runtime),
            capture = { fingerprintOf(fix.database) },
            diffNames = { a, b -> diffFpNames(a, b) },
        )
        val sync = AccountSyncState(
            counting,
            MainScope().also { scopes += it },
            50L,
        ).also { syncStates += it }
        sync.start()
        showContent { AccountStartupApp(fix.runtime, startup, consent, sync) }
        startup.start()
        composeRule.waitUntil(10_000L) { !startup.uiState.value.busy }
        openSyncDialog()
        // First deliberate review captures the opaque token for later proof.
        ensureIdle(sync)
        scrollIfNeeded("account-recovery-prepare")
        composeRule.onNodeWithTag("account-recovery-prepare").assertIsDisplayed()
        composeRule.onNodeWithTag("account-recovery-prepare").assertIsEnabled()
        composeRule.onNodeWithTag("account-recovery-prepare").performClick()
        composeRule.waitUntil(15_000L) { sync.uiState.value.recovery != null }
        val token1 = checkNotNull(sync.uiState.value.recovery) { "first review must yield a token" }
        assertEquals(1L, token1.pendingCount)
        assertEquals("one deliberate review invokes prepare once", 1, counting.prepareCalls)
        composeRule.waitUntil(15_000L) {
            try {
                composeRule.onNodeWithTag("account-recovery-warning").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // Dismiss/cancel: warning gone, queue unchanged, token retained.
        ensureIdle(sync)
        scrollIfNeeded("account-recovery-cancel")
        composeRule.onNodeWithTag("account-recovery-cancel").performClick()
        composeRule.runOnIdle { }
        composeRule.onNodeWithTag("account-recovery-warning").assertDoesNotExist()
        assertEquals(1L, harness.outboxCount(fix.database, AppSessionFixtures.ACCOUNT))
        // Keep changes: a later deliberate review issues a DIFFERENT token.
        ensureIdle(sync)
        scrollIfNeeded("account-recovery-prepare")
        composeRule.onNodeWithTag("account-recovery-prepare").assertIsDisplayed()
        composeRule.onNodeWithTag("account-recovery-prepare").assertIsEnabled()
        composeRule.onNodeWithTag("account-recovery-prepare").performClick()
        composeRule.waitUntil(15_000L) { !sync.uiState.value.busy }
        val token2 = sync.uiState.value.recovery
        checkNotNull(token2) {
            "second review yielded no token [busy=${sync.uiState.value.busy} " +
                "error=${sync.uiState.value.error} stop=${sync.uiState.value.stop} " +
                "prepared=${counting.prepareCalls} pendingCount=${token1.pendingCount}]"
        }
        assertNotSame("second review must issue a fresh token", token1, token2)
        assertEquals("two deliberate reviews invoke prepare twice", 2, counting.prepareCalls)
        counting.baselineFp = fingerprintOf(fix.database)
        // Viewport diagnostics: distinguish an ABSENT warning node from an
        // existing-but-undisplayed one before the strict rearm oracle. All
        // values are safe presentation data; no IDs, bodies or secrets.
        val mergedCount =
            composeRule.onAllNodesWithTag("account-recovery-warning").fetchSemanticsNodes().size
        val unmergedCount =
            composeRule.onAllNodesWithTag("account-recovery-warning", useUnmergedTree = true)
                .fetchSemanticsNodes().size
        var warningDisplayed = false
        var warningBounds = "none"
        if (mergedCount > 0) {
            try {
                composeRule.onNodeWithTag("account-recovery-warning").assertIsDisplayed()
                warningDisplayed = true
            } catch (_: AssertionError) {
                warningDisplayed = false
            }
            warningBounds = runCatching {
                composeRule.onNodeWithTag("account-recovery-warning").fetchSemanticsNode()
                    .boundsInRoot.toString()
            }.getOrDefault("unreadable")
        }
        val prepareControls =
            composeRule.onAllNodesWithTag("account-recovery-prepare").fetchSemanticsNodes().size
        println(
            "R13D-REARM-DIAG merged=$mergedCount unmerged=$unmergedCount " +
                "displayed=$warningDisplayed bounds=$warningBounds prepareControls=$prepareControls " +
                "busy=${sync.uiState.value.busy} error=${sync.uiState.value.error} " +
                "stop=${sync.uiState.value.stop} prepared=${counting.prepareCalls} " +
                "pendingCount=${token2.pendingCount}",
        )
        if (mergedCount > 0 && !warningDisplayed) {
            composeRule.onNodeWithTag("account-recovery-warning").performScrollTo()
        }
        composeRule.waitUntil(15_000L) {
            try {
                composeRule.onNodeWithTag("account-recovery-warning").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // Deliberate confirm discards only the captured ordinary intent.
        ensureIdle(sync)
        scrollIfNeeded("account-recovery-confirm")
        composeRule.onNodeWithTag("account-recovery-confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("account-recovery-confirm").assertIsEnabled()
        composeRule.onNodeWithTag("account-recovery-confirm").performClick()
        // Confirm-side diagnostics first: distinguish ignored busy admission
        // (no delegate call), a wrong displayed token (identity mismatch) and
        // a closed refusal (error class/reason) before the strict oracles.
        composeRule.waitUntil(15_000L) { !sync.uiState.value.busy }
        val recoveryHeld = sync.uiState.value.recovery
        println(
            "R13D-CONFIRM-DIAG confirmCalls=${counting.confirmCalls} " +
                "tokenIdentity=${counting.seenConfirmations.map { it === token2 }} " +
                "outcomes=${counting.confirmOutcomes} busy=${sync.uiState.value.busy} " +
                "error=${sync.uiState.value.error} stop=${sync.uiState.value.stop} " +
                "recoveryHeld=${recoveryHeld != null} recoveryIsToken2=${recoveryHeld === token2} " +
                "outbox=${harness.outboxCount(fix.database, AppSessionFixtures.ACCOUNT)}",
        )
        composeRule.waitUntil(15_000L) {
            harness.outboxCount(fix.database, AppSessionFixtures.ACCOUNT) == 0L
        }
        composeRule.runOnIdle { }
        assertEquals("r09h-baseline", harness.syncStateCursor(fix.database, AppSessionFixtures.ACCOUNT))
        assertTrue(
            "complete EMPTY snapshot replaces the discarded unsynced graph",
            !harness.customNames(fix.database, AppSessionFixtures.ACCOUNT).contains("R13d Flow Ordinary"),
        )
        composeRule.onNodeWithTag("account-sync-error").assertDoesNotExist()
    }

    @Test
    fun controlledRoot_editorSave_triggersPush_timerSkipPersistsOnce() {
        val fix = fixture()
        fix.activate()
        val ops = FakeOps()
        ops.publish(fix.runtime.sessionCoordinator.state.value)
        val startup = fix.startup(ops)
        val consent = AccountImportConsent()
        val sync = fix.syncState()
        sync.start()
        fix.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        fix.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        showContent { AccountStartupApp(fix.runtime, startup, consent, sync) }
        startup.start()
        composeRule.waitUntil(10_000L) { !startup.uiState.value.busy }
        composeRule.waitUntil(15_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState)
        val liveSession =
            (fix.runtime.sessionCoordinator.state.value as AppSessionState.Active).session
        // Explicit foreground admission, then entry quiescence before Save:
        // only a run started after the mark can prove the Save signal.
        sync.setForeground(true)
        composeRule.waitUntil(15_000L) {
            fix.transport.capturedRequests.any { it.path == "/v1/sync/pull" }
        }
        composeRule.waitUntil(15_000L) { !sync.uiState.value.busy }
        // Real explicit-save editor through the account root with real shared
        // edits. Post-entry traffic is served deterministically: the Save push
        // meets a typed transient (trigger proof, never a success claim).
        runBlocking { appState.showCreateExercise() }
        composeRule.runOnIdle { }
        composeRule.onNodeWithTag("exercise-field-name").performTextInput("R13d Flow Press")
        fix.transport.serve = { _, request ->
            if (request.path.contains("push")) {
                AppSessionTransport.Frame(500, """{"errorCode":"probe-transient"}""")
            } else {
                AppSessionTransport.Frame(200, AppSessionFixtures.EMPTY_PULL_BODY)
            }
        }
        val beforeSave = fix.transport.capturedRequests.size
        composeRule.onNodeWithText("Save").performClick()
        // Drain outside Main: surfaces the real save failure instead of a
        // persistence-timeout mystery.
        runBlocking { appState.edits.awaitPendingEdits() }
        composeRule.runOnIdle { }
        assertTrue(
            "explicit Save must persist through shared finite edits",
            harness.customNames(fix.database, AppSessionFixtures.ACCOUNT).contains("R13d Flow Press"),
        )
        composeRule.waitUntil(15_000L) {
            fix.transport.capturedRequests.size > beforeSave &&
                fix.transport.capturedRequests.subList(beforeSave, fix.transport.capturedRequests.size)
                    .any { it.path == "/v1/sync/push" }
        }
        composeRule.waitUntil(15_000L) { !sync.uiState.value.busy }
        runBlocking { appState.closeCreateExercise() }
        composeRule.runOnIdle { }
        // Real timer boundary on a completed set: skip claims terminal
        // persistence exactly once in whole seconds.
        val seeds = runBlocking {
            val customId = liveSession.repository.createCustomExercise("R13d Flow Timer", null, emptySet(), null, null, "")
            val workout = liveSession.repository.startEmptyWorkout(1_700_000_000_000L)
            liveSession.repository.addExercisesToWorkout(workout.id, listOf(customId))
            val entry = checkNotNull(liveSession.repository.workoutDetails(workout.id)).exercises.single()
            val set = entry.sets.single()
            assertTrue(
                liveSession.repository.updateWorkoutSet(
                    set.copy(repetitions = 8, weightKg = 55.0, isCompleted = true),
                ),
            )
            Triple(workout.id, entry.sets.single().id, set.id)
        }
        val (workoutId, _, setId) = seeds
        runBlocking { appState.refreshProjection() }
        composeRule.waitUntil(10_000L) { appState.activeWorkout?.id == workoutId }
        val source = runBlocking {
            checkNotNull(
                (fix.runtime.sessionCoordinator.state.value as AppSessionState.Active)
                    .session.repository.workoutDetails(workoutId),
            ).exercises.single().sets.single()
        }
        assertTrue("timer source must be completed", source.isCompleted)
        composeRule.runOnIdle {
            appState.startRestTimer(source, 90, acceptedEdit = true, expectedWorkoutId = workoutId)
        }
        composeRule.runOnIdle { }
        checkNotNull(appState.activeRestTimer) { "timer must be running" }
        assertNull(fix.restSeconds(setId))
        fix.clock.elapsed += 5_000L
        composeRule.runOnIdle {
            appState.skipRestTimer(checkNotNull(appState.activeRestTimer))
        }
        composeRule.runOnIdle { }
        assertEquals("elapsed whole seconds persist exactly once", 5L, fix.restSeconds(setId))
        assertNull(appState.activeRestTimer)
        assertEquals("no second claim may rewrite rest", 5L, fix.restSeconds(setId))
    }
}
