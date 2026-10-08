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
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.session.SessionVault
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R12c independent QA: scoped account runtime and root privacy fencing
 * (outcomes 1 + 7 slice).
 *
 * Hand-written from docs/remaining-work/R12c-task.md only (fixed TARGET
 * contract in ui): AccountGymAppState owns real transition/publication
 * barriers and watches stable authority state; the account root renders
 * ordinary GymApp content ONLY for its exact current live handle and
 * otherwise renders a generic stable privacy placeholder tagged
 * account-privacy-gate. Construction starts private and never runs HTTP
 * implicitly; the caller invokes delivered authority APIs explicitly. Real
 * in-memory Room plus QA-owned session harness/transport/auth fixtures;
 * independently written literals only; no sleeps, no production oracles.
 */
@RunWith(AndroidJUnit4::class)
class R12cAccountRootPrivacyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val harness = AppSessionHarness("r12c-root")
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

    private fun ownerScope(): CoroutineScope = MainScope().also { scopes += it }

    private fun runtime(
        database: GymDatabase,
        vault: SessionVault,
        auth: AppSessionAuthApi,
        transport: AppSessionTransport,
        owner: PushRunOwner,
    ): AccountGymAppState {
        val uuids = listOf(
            AppSessionFixtures.OP_FRESH,
            AppSessionFixtures.KEY_FRESH,
            AppSessionFixtures.OP_OTHER,
            AppSessionFixtures.STAGE,
            AppSessionFixtures.STAGE_OTHER,
        ).iterator()
        return AccountGymAppState(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = vault,
            authApi = auth,
            transport = transport,
            runOwner = owner,
            scope = ownerScope(),
            clock = TestClock(),
            timerHardware = NoOpHardware(),
            uuidProvider = { uuids.next() },
        ).also { runtimes += it }
    }

    @Test
    fun privateConstructionShowsOnlyPrivacyGate() {
        val database = harness.openInMemoryDb().also { dbs += it }
        val state = runtime(database, harness.newVault(), AppSessionAuthApi(), AppSessionTransport(), PushRunOwner())
        composeRule.setContent { GymApp(state) }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()
    }

    @Test
    fun activatedHandleHidesGateAndBindsLiveAppState() {
        val database = harness.openInMemoryDb().also { dbs += it }
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
        val state = runtime(database, harness.newVault(), auth, transport, PushRunOwner())
        composeRule.setContent { GymApp(state) }
        composeRule.onNodeWithTag("account-privacy-gate").assertIsDisplayed()

        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        runBlocking {
            val preparing = state.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(),
            )
            assertTrue("preparation must stay Private, was $preparing", preparing is AppSessionState.Private)
            val active = state.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.STAGE,
            )
            assertTrue("activation must be Active, was $active", active is AppSessionState.Active)
            assertTrue((active as AppSessionState.Active).session is AppAccountSession)
        }
        composeRule.waitUntil(10_000L) { state.appState != null }
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        assertNotNull(state.appState)
    }

    @Test
    fun legacyLocalRootShowsNoAccountPrivacyGate() {
        composeRule.setContent { GymApp() }
        composeRule.runOnIdle { }
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
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
