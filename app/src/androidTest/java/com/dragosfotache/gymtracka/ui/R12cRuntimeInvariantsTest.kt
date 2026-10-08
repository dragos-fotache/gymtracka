package com.dragosfotache.gymtracka.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppSessionAuthApi
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransport
import com.dragosfotache.gymtracka.data.sync.session.GatedTransactionExecutor
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.session.SessionVault
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R12c independent QA: scoped runtime black-box invariants (outcomes 1/4/5).
 *
 * Hand-written from docs/remaining-work/R12c-task.md only (fixed TARGET
 * contract in ui): same-durable-generation process invalidation immediately
 * hides the account root and retires appState; cancelling the supplied owner
 * scope retires child timers/hardware; cancelling a parked facade
 * reservation releases admission without scope pinning; a real same-account
 * reauthentication parks on the facade-owned internal lease (never a
 * competing external lease), shows privacy while the transition is pending,
 * then binds the replacement and fences the superseded handle. Real
 * gated in-memory Room, actual namespaced vault, ONE owner, injected owner
 * scope/clock/hardware; the runtime constructs its OWN real barriers.
 * Independently written literals only; no sleeps, no production oracles.
 */
@RunWith(AndroidJUnit4::class)
class R12cRuntimeInvariantsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val harness = AppSessionHarness("r12c-runtime")
    private val dbs = mutableListOf<GymDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val runtimes = mutableListOf<AccountGymAppState>()

    @After
    fun tearDown() {
        composeRule.runOnIdle { }
        runtimes.forEach { runCatching { it.dispose() } }
        runtimes.clear()
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harness.clean()
    }

    private data class Fixture(
        val database: GymDatabase,
        val gate: GatedTransactionExecutor,
        val vault: SessionVault,
        val auth: AppSessionAuthApi,
        val transport: AppSessionTransport,
        val hardware: RecordingHardware,
        val runtime: AccountGymAppState,
    )

    private fun fixture(): Fixture {
        val gate = GatedTransactionExecutor()
        val database = harness.openGatedInMemoryDb(gate).also { dbs += it }
        val vault = harness.newVault()
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
        val hardware = RecordingHardware()
        val uuids = listOf(
            AppSessionFixtures.OP_FRESH,
            AppSessionFixtures.KEY_FRESH,
            AppSessionFixtures.OP_OTHER,
            AppSessionFixtures.STAGE,
            AppSessionFixtures.STAGE_OTHER,
        ).iterator()
        val runtime = AccountGymAppState(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = vault,
            authApi = auth,
            transport = transport,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = TestClock(),
            timerHardware = hardware,
            uuidProvider = { uuids.next() },
        ).also { runtimes += it }
        return Fixture(database, gate, vault, auth, transport, hardware, runtime)
    }

    private fun Fixture.activate(
        operationId: String = AppSessionFixtures.OP_FRESH,
        stageId: String = AppSessionFixtures.STAGE,
        grant: com.dragosfotache.gymtracka.data.sync.api.ServiceSession = AppSessionFixtures.grant(),
    ): AppAccountSession {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        return runBlocking {
            val preparing = runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                operationId,
                grant,
            )
            assertTrue("preparation must stay Private, was $preparing", preparing is AppSessionState.Private)
            val active = runtime.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                stageId,
            )
            assertTrue("activation must be Active, was $active", active is AppSessionState.Active)
            (active as AppSessionState.Active).session
        }
    }

    @Test
    fun processInvalidationRetiresAppStateSynchronouslyBeforeSettle() {
        val fix = fixture()
        composeRule.setContent { GymApp(fix.runtime) }
        fix.activate()
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()

        fix.runtime.sessionCoordinator.invalidate()
        assertNull("invalidation must retire appState synchronously", fix.runtime.appState)
        composeRule.runOnIdle { }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
    }

    @Test
    fun cancelledOwnerScopeRetiresValidChildTimerHardware() {
        val fix = fixture()
        val live = fix.activate()
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState) { "runtime must bind app-state" }
        val (workoutId, completedSetId) = runBlocking {
            val repo = live.repository
            val customId = repo.createCustomExercise("R12c Timed", null, emptySet(), null, null, "")
            val workout = repo.startEmptyWorkout(1_700_000_000_000L)
            repo.addExercisesToWorkout(workout.id, listOf(customId))
            val details = checkNotNull(repo.workoutDetails(workout.id)) { "scoped parent must read back" }
            val completed = details.exercises.single().sets.single().copy(repetitions = 8, weightKg = 50.0, isCompleted = true)
            check(repo.updateWorkoutSet(completed)) { "scoped completion must persist" }
            workout.id to completed.id
        }
        runBlocking { appState.refreshProjection() }
        composeRule.waitUntil(10_000L) { appState.activeWorkout?.id == workoutId }
        val selected = checkNotNull(appState.activeWorkout)
        assertEquals(workoutId, selected.id)
        val source = runBlocking { checkNotNull(live.repository.workoutDetails(workoutId)).exercises.single().sets.single() }
        assertTrue("timer source must be completed", source.isCompleted)
        assertEquals(completedSetId, source.id)
        composeRule.runOnIdle {
            appState.startRestTimer(
                source,
                90,
                acceptedEdit = true,
                expectedWorkoutId = workoutId,
            )
        }
        composeRule.runOnIdle { }
        assertTrue("timer must hold hardware while running", fix.hardware.isHeld)

        scopes.forEach { it.cancel(CancellationException("r12c-owner-cancelled")) }
        composeRule.runOnIdle { }
        assertTrue("cancelled owner scope must release hardware", !fix.hardware.isHeld)
        assertTrue(fix.hardware.releaseCount >= 1)
    }

    @Test
    fun cancelledParkedReservationReleasesAdmissionForExplicitRecovery() {
        val fix = fixture()
        val firstSession = fix.activate()
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val oldApp = checkNotNull(fix.runtime.appState)
        val probe = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }
        val journalBefore = harness.journalRow(fix.database).toString()
        val deviceBefore = harness.deviceRow(fix.database).toString()
        val outboxBefore = harness.outboxSnapshot(fix.database, AppSessionFixtures.ACCOUNT).toString()
        val cacheBefore = harness.cacheEpoch(fix.database, AppSessionFixtures.ACCOUNT).toString()
        val vaultBefore = runBlocking { fix.vault.load().record?.phase }.toString()

        val expected = runBlocking { RoomSessionAuthority(fix.database).read() }
        fix.gate.gateWhen = { fix.runtime.sessionCoordinator.state.value is AppSessionState.PrivacyLocked }
        fix.gate.gateNext = true
        val parked = probe.async {
            fix.runtime.sessionCoordinator.acceptGrant(
                expected,
                AppSessionFixtures.OP_OTHER,
                AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED, refresh = AppSessionFixtures.REFRESH_ROTATED),
            )
        }
        assertTrue("reservation must park after the visibility lock", fix.gate.entered())
        // The public PrivacyLocked lock proves the old handle is already
        // revoked: no rollback of the old Active is expected on cancel.
        assertNull("old root stays private while parked", fix.runtime.appState)
        parked.cancel(CancellationException("r12c-reservation-cancelled"))
        fix.gate.releaseLatch.countDown()
        runBlocking {
            try {
                parked.await()
                fail("Cancelled reservation must propagate cancellation")
            } catch (_: CancellationException) {
            }
        }

        // No rollback is inferred: reread durable evidence, which must be
        // unchanged by the cancelled post-lock reservation.
        assertEquals(journalBefore, harness.journalRow(fix.database).toString())
        assertEquals(deviceBefore, harness.deviceRow(fix.database).toString())
        assertEquals(outboxBefore, harness.outboxSnapshot(fix.database, AppSessionFixtures.ACCOUNT).toString())
        assertEquals(cacheBefore, harness.cacheEpoch(fix.database, AppSessionFixtures.ACCOUNT).toString())
        assertEquals(vaultBefore, runBlocking { fix.vault.load().record?.phase }.toString())
        runBlocking {
            try {
                firstSession.checkCurrent()
                fail("Revoked old handle must stay stale")
            } catch (_: Exception) {
            }
        }

        // Explicit fresh recovery admits a NEW live handle on the same
        // durable binding; admission is usable and the old handle is gone.
        val recovered = runBlocking { fix.runtime.sessionCoordinator.recover() }
        assertTrue("explicit recovery must admit, was $recovered", recovered is AppSessionState.Active)
        val fresh = (recovered as AppSessionState.Active).session
        assertEquals(AppSessionFixtures.ACCOUNT, fresh.identity.binding.accountId)
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null && fix.runtime.appState !== oldApp }
        runBlocking {
            checkNotNull(fix.runtime.appState).transitionBarrier()
                .acquire(RepositorySession(AppSessionFixtures.ACCOUNT, fresh.identity.binding.sessionGeneration))
                .close()
        }
    }

    @Test
    fun cancelledDrainPreservesHealthyActiveAndReleasesAdmission() {
        val fix = fixture()
        val firstSession = fix.activate()
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState)
        val edits = appState.edits
        val probe = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }
        val journalBefore = harness.journalRow(fix.database).toString()
        val outboxBefore = harness.outboxSnapshot(fix.database, AppSessionFixtures.ACCOUNT)

        // Register uniquely keyed REAL scoped-repository work on the
        // owner dispatcher; it is accepted/tracked before return.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var draftId: String? = null
        var editJob: Job? = null
        composeRule.runOnIdle {
            editJob = edits.launchEdit("r12c-drain") {
                entered.complete(Unit)
                release.await()
                draftId = firstSession.repository.createRoutineDraft().id
            }
        }
        val job = checkNotNull(editJob) { "drain work must be accepted, not refused" }
        composeRule.waitUntil(10_000L) { entered.isCompleted }

        val expected = runBlocking { RoomSessionAuthority(fix.database).read() }
        val parked = probe.async {
            fix.runtime.sessionCoordinator.acceptGrant(
                expected,
                AppSessionFixtures.OP_OTHER,
                AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED, refresh = AppSessionFixtures.REFRESH_ROTATED),
            )
        }
        // The ACTUAL transition drain pauses on the registered work before
        // any visibility lock: admission holds, handle stays healthy.
        composeRule.waitUntil(10_000L) { edits.isPaused }
        assertTrue(
            "drain must park before the lock",
            fix.runtime.sessionCoordinator.state.value is AppSessionState.Active,
        )
        parked.cancel(CancellationException("r12c-drain-cancelled"))
        runBlocking {
            try {
                parked.await()
                fail("Drain-parked cancellation must propagate")
            } catch (_: CancellationException) {
            }
        }
        assertTrue(
            "cancelled drain preserves the healthy handle",
            fix.runtime.sessionCoordinator.state.value is AppSessionState.Active,
        )
        assertTrue("app-state stays bound", fix.runtime.appState === appState)
        runBlocking { firstSession.checkCurrent() }
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        assertEquals(journalBefore, harness.journalRow(fix.database).toString())

        // Release the accepted work on the runtime/write scopes only; the
        // grant caller stays cancelled. The real edit must land exactly once.
        release.complete(Unit)
        runBlocking {
            job.join()
            edits.awaitPendingEdits()
        }
        val id = checkNotNull(draftId) { "accepted edit must produce its draft" }
        runBlocking {
            checkNotNull(firstSession.repository.routineDetails(id)) { "draft must survive" }
        }
        val intentIds = fix.database.openHelper.readableDatabase.query(
            "SELECT aggregate_id FROM sync_outbox WHERE account_id = ?",
            arrayOf(AppSessionFixtures.ACCOUNT),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals("exactly one captured intent", outboxBefore.size + 1, intentIds.size)
        assertEquals("intent targets the returned draft", listOf(id), intentIds)
        runBlocking {
            appState.transitionBarrier()
                .acquire(RepositorySession(AppSessionFixtures.ACCOUNT, firstSession.identity.binding.sessionGeneration))
                .close()
        }
    }

    @Test
    fun sameAccountReauthShowsPrivacyWhilePendingThenBindsReplacement() {
        val fix = fixture()
        composeRule.setContent { GymApp(fix.runtime) }
        val firstSession = fix.activate()
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val oldApp = checkNotNull(fix.runtime.appState) { "runtime must bind app-state" }
        val probe = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }

        // Path-aware replies: the COMPLETE baseline needs no redownload, so
        // completion takes an actual valid tail; never queue a snapshot body
        // at a pull position.
        fix.transport.serve = { _, request ->
            if (request.path == "/v1/sync/snapshot") {
                AppSessionTransport.Frame(200, AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
            } else {
                AppSessionTransport.Frame(200, AppSessionFixtures.EMPTY_PULL_BODY)
            }
        }
        val expected = runBlocking { RoomSessionAuthority(fix.database).read() }
        fix.gate.gateWhen = { fix.runtime.sessionCoordinator.state.value is AppSessionState.PrivacyLocked }
        fix.gate.gateNext = true
        val pending = probe.async {
            fix.runtime.sessionCoordinator.acceptGrant(
                expected,
                AppSessionFixtures.OP_OTHER,
                AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED, refresh = AppSessionFixtures.REFRESH_ROTATED),
            )
        }
        assertTrue("re-auth reservation must park after the visibility lock", fix.gate.entered())
        composeRule.waitUntil(10_000L) {
            fix.runtime.appState == null ||
                composeRule.onNodeWithTag("account-privacy-gate").fetchSemanticsNode().let { true }
        }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
        fix.gate.releaseLatch.countDown()
        val preparing = runBlocking { pending.await() }
        assertTrue("re-auth must stay Private, was $preparing", preparing is AppSessionState.Private)

        val active = runBlocking {
            fix.runtime.sessionCoordinator.completeActivation(
                RoomSessionAuthority(fix.database).read(),
                AppSessionFixtures.STAGE_OTHER,
            )
        }
        assertTrue("re-auth must be Active, was $active", active is AppSessionState.Active)
        assertTrue(
            "re-auth completion must take an actual valid tail",
            fix.transport.capturedRequests.map { it.path }.contains("/v1/sync/pull"),
        )
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null && fix.runtime.appState !== oldApp }
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()

        runBlocking {
            try {
                firstSession.checkCurrent()
                fail("Superseded handle must fail closed after same-account re-auth")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun disposedRuntimeStalesRetainedHandleWhilePreservingEvidenceForRecovery() {
        val fix = fixture()
        val session = fix.activate()
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val journalBefore = harness.journalRow(fix.database).toString()
        val deviceBefore = harness.deviceRow(fix.database).toString()
        val vaultBefore = runBlocking { fix.vault.load().record?.phase }.toString()

        fix.runtime.dispose()
        runBlocking {
            try {
                session.checkCurrent()
                fail("Disposed runtime must stale the retained handle")
            } catch (_: Exception) {
            }
        }
        assertEquals("Room evidence must survive disposal", journalBefore, harness.journalRow(fix.database).toString())
        assertEquals("device evidence must survive disposal", deviceBefore, harness.deviceRow(fix.database).toString())
        assertEquals("vault evidence must survive disposal", vaultBefore, runBlocking { fix.vault.load().record?.phase }.toString())

        val recovered = runBlocking {
            AccountGymAppState(
                database = fix.database,
                origin = AppSessionFixtures.origin(),
                vault = harness.reopenVault(),
                authApi = AppSessionAuthApi(),
                transport = AppSessionTransport(),
                runOwner = PushRunOwner(),
                scope = MainScope().also { scopes += it },
                clock = TestClock(),
                timerHardware = RecordingHardware(),
            ).also { runtimes += it }.sessionCoordinator.recover()
        }
        assertTrue("fresh recovery must readmit Active, was $recovered", recovered is AppSessionState.Active)
    }

    private class TestClock : GymAppClock {
        override fun wallTimeMillis() = AppSessionFixtures.FIXED_CLOCK
        override fun elapsedRealtimeMillis() = 0L
    }

    private class RecordingHardware : RestTimerHardware {
        var releaseCount = 0
        var isHeld = false
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) {
            isHeld = true
        }
        override fun releaseWakeLock() {
            if (isHeld) {
                releaseCount++
                isHeld = false
            }
        }
    }
}
