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
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransport
import com.dragosfotache.gymtracka.data.sync.session.GatedTransactionExecutor
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R12c independent QA: explicit-save editors refuse NORMAL grant scope
 * transitions (outcome 1 safety slice).
 *
 * Hand-written from docs/remaining-work/R12c-task.md plus coordinator
 * fixed facts only: with an open explicit-save create/edit editor, a fresh
 * same-origin/same-account acceptGrant MUST refuse with
 * AppSessionException(UNAVAILABLE) before reservation, leaving the same
 * Active handle/root/editor selection plus journal/device/domain/outbox
 * unchanged; closing the editor permits the later transition; emergency
 * invalidate() still hides immediately. Stable destination/selection plus
 * account-privacy-gate are the observables; no new tags. Real in-memory
 * Room, actual namespaced vault, ONE owner, injected FIXED-clock
 * scope/hardware; the runtime constructs its OWN real barriers.
 */
@RunWith(AndroidJUnit4::class)
class R12cExplicitEditorTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val harness = AppSessionHarness("r12c-editor")
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
        val auth: AppSessionAuthApi,
        val transport: AppSessionTransport,
        val runtime: AccountGymAppState,
    )

    private fun fixture(): Fixture {
        val database = harness.openInMemoryDb().also { dbs += it }
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
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
            vault = harness.newVault(),
            authApi = auth,
            transport = transport,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = TestClock(),
            timerHardware = NoOpHardware(),
            uuidProvider = { uuids.next() },
        ).also { runtimes += it }
        return Fixture(database, auth, transport, runtime)
    }

    private fun Fixture.activate(): AppAccountSession {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        return runBlocking {
            val preparing = runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(),
            )
            assertTrue("preparation must stay Private, was $preparing", preparing is AppSessionState.Private)
            val active = runtime.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.STAGE,
            )
            assertTrue("activation must be Active, was $active", active is AppSessionState.Active)
            (active as AppSessionState.Active).session
        }
    }

    private fun Fixture.preservationSnapshot() = buildList {
        add(harness.journalRow(database).toString())
        add(harness.deviceRow(database).toString())
        add(harness.outboxSnapshot(database, AppSessionFixtures.ACCOUNT).toString())
        add(harness.cacheEpoch(database, AppSessionFixtures.ACCOUNT).toString())
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM workouts").use { cursor ->
            check(cursor.moveToFirst())
            add(cursor.getLong(0).toString())
        }
    }

    private fun Fixture.refuseGrantWhileEditorOpen(): Throwable {
        val error = runCatching {
            runBlocking {
                runtime.sessionCoordinator.acceptGrant(
                    RoomSessionAuthority(database).read(),
                    UUID.randomUUID().toString(),
                    AppSessionFixtures.grant(),
                )
            }
        }.exceptionOrNull()
        return checkNotNull(error) { "open explicit-save editor must refuse NORMAL transition" }
    }

    @Test
    fun openCreateEditorRefusesTransitionUntilClosed() {
        val fix = fixture()
        fix.activate()
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState) { "runtime must bind app-state" }

        runBlocking { appState.showCreateExercise() }
        composeRule.runOnIdle { }
        assertEquals(Destination.CreateExercise, appState.destination)

        val preserved = fix.preservationSnapshot()
        val refusal = fix.refuseGrantWhileEditorOpen()
        AppSessionFixtures.assertRefusal(refusal, AppSessionReason.UNAVAILABLE)
        assertEquals(preserved, fix.preservationSnapshot())
        assertSame("same Active handle must survive refusal", appState, fix.runtime.appState)
        assertEquals(Destination.CreateExercise, appState.destination)
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()

        runBlocking { appState.closeCreateExercise() }
        val preparing = runBlocking {
            fix.runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(fix.database).read(),
                UUID.randomUUID().toString(),
                AppSessionFixtures.grant(),
            )
        }
        assertTrue("closed editor must permit transition, was $preparing", preparing is AppSessionState.Private)
    }

    @Test
    fun openEditEditorRefusesTransitionUntilClosed() {
        val fix = fixture()
        val session = fix.activate()
        val customId = runBlocking {
            session.repository.createCustomExercise("R12c Draft", null, emptySet(), null, null, "")
        }
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState) { "runtime must bind app-state" }

        runBlocking { appState.editCustomExercise(customId) }
        composeRule.runOnIdle { }
        assertEquals(Destination.EditCustomExercise, appState.destination)

        val preserved = fix.preservationSnapshot()
        AppSessionFixtures.assertRefusal(fix.refuseGrantWhileEditorOpen(), AppSessionReason.UNAVAILABLE)
        assertEquals(preserved, fix.preservationSnapshot())
        assertSame("same Active handle must survive refusal", appState, fix.runtime.appState)
        assertEquals(Destination.EditCustomExercise, appState.destination)

        runBlocking { appState.closeEditCustomExercise() }
        val preparing = runBlocking {
            fix.runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(fix.database).read(),
                UUID.randomUUID().toString(),
                AppSessionFixtures.grant(),
            )
        }
        assertTrue("closed editor must permit transition, was $preparing", preparing is AppSessionState.Private)
    }

    @Test
    fun parkedTransitionRefusesNewEditorOpenWithoutDropping() {
        val gate = GatedTransactionExecutor()
        val database = harness.openGatedInMemoryDb(gate).also { dbs += it }
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
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
            vault = harness.newVault(),
            authApi = auth,
            transport = transport,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = TestClock(),
            timerHardware = NoOpHardware(),
            uuidProvider = { uuids.next() },
        ).also { runtimes += it }
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        runBlocking {
            runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(),
            )
            val active = runtime.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.STAGE,
            )
            assertTrue(active is AppSessionState.Active)
        }
        composeRule.setContent { GymApp(runtime) }
        composeRule.waitUntil(10_000L) { runtime.appState != null }
        val appState = checkNotNull(runtime.appState)

        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val probe = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }
        val expected = runBlocking { RoomSessionAuthority(database).read() }
        gate.gateWhen = { runtime.sessionCoordinator.state.value is AppSessionState.PrivacyLocked }
        gate.gateNext = true
        val pending = probe.async {
            runtime.sessionCoordinator.acceptGrant(
                expected,
                AppSessionFixtures.OP_OTHER,
                AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED, refresh = AppSessionFixtures.REFRESH_ROTATED),
            )
        }
        assertTrue("re-auth must park after the visibility lock", gate.entered())
        try {
            // UI entry refusal while admission is paused is a Unit no-op:
            // no entry opens and no draft is created or lost.
            runBlocking { appState.showCreateExercise() }
            composeRule.runOnIdle { }
            assertTrue("attempted entry must not silently appear", appState.destination != Destination.CreateExercise)
        } finally {
            gate.releaseLatch.countDown()
        }
        val preparing = runBlocking { pending.await() }
        assertTrue("parked transition completes after release, was $preparing", preparing is AppSessionState.Private)
        assertTrue("no phantom editor after transition", appState.destination != Destination.CreateExercise)
    }

    @Test
    fun emergencyInvalidateHidesImmediatelyWithEditorOpen() {
        val fix = fixture()
        fix.activate()
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState) { "runtime must bind app-state" }

        runBlocking { appState.showCreateExercise() }
        composeRule.runOnIdle { }
        assertEquals(Destination.CreateExercise, appState.destination)

        fix.runtime.sessionCoordinator.invalidate()
        assertNull("emergency revoke must retire appState synchronously", fix.runtime.appState)
        composeRule.runOnIdle { }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
    }

    private class TestClock : GymAppClock {
        override fun wallTimeMillis() = AppSessionFixtures.FIXED_CLOCK
        override fun elapsedRealtimeMillis() = 0L
    }

    private class NoOpHardware : RestTimerHardware {
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) = Unit
        override fun releaseWakeLock() = Unit
    }
}
