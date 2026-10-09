package com.dragosfotache.gymtracka.ui.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityProvider
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionAuthApi
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransport
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInCoordinator
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInIntent
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.ui.AccountGymAppState
import com.dragosfotache.gymtracka.ui.Destination
import com.dragosfotache.gymtracka.ui.GymApp
import com.dragosfotache.gymtracka.ui.GymAppClock
import com.dragosfotache.gymtracka.ui.RestTimerHardware
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R13c independent QA: editor-gated reauthentication + finite drain.
 *
 * Derived ONLY from docs/archive/remaining-work/R13c-task.md lifecycle section plus the
 * coordinator appendix (reauth with an open explicit-save custom editor refuses
 * before Google launch, editor/draft stays visible with typed content intact,
 * user told to finish Save/Discard or cancel; barrier recheck after suspension;
 * no hidden save/discard; real ACTIVE same-account reauth must actually invoke
 * the provider plus the real editor/timer drain, not merely recover; finite
 * accepted edits + terminal whole-second rest drain exactly once on successful
 * transition; failed/cancelled drain retains privacy/healthy handle). Real
 * in-memory Room + test vault + scripted identity/auth; real MainScope/Android
 * dispatcher with bounded waitUntil guards; no sleeps. Reuses accepted
 * R12c/R13b admission authority; no engine matrix repeat.
 */
@RunWith(AndroidJUnit4::class)
class R13cEditorReauthTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val harness = AppSessionHarness("r13c-editor")
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

    private class FakeIdentity : GoogleIdentityProvider {
        val nonces = mutableListOf<String>()
        var clears = 0
        override suspend fun credentialFor(nonce: String): OpaqueToken {
            nonces += nonce
            return OpaqueToken("r13c-google-token")
        }

        override suspend fun clearCredentialState() {
            clears += 1
        }
    }

    private class FakeExchangeAuth : ServiceAuthApi {
        val purposes = mutableListOf<GoogleChallengePurpose>()
        val attempts = mutableListOf<GoogleExchangeAttempt>()
        override suspend fun challenge(purpose: GoogleChallengePurpose): ServiceChallenge {
            purposes += purpose
            // challengeId/nonce must be canonical UUIDs per ServiceChallenge contract.
            return ServiceChallenge(
                "d13c4011-1111-4111-8111-111111111111",
                "d13c4012-2222-4222-8222-222222222222",
                AppSessionFixtures.FIXED_CLOCK + 300_000L,
            )
        }

        override suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession {
            attempts += attempt
            return AppSessionFixtures.grant()
        }

        override suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession {
            throw SyncApiException.Transient()
        }

        override suspend fun logout(refreshToken: OpaqueToken) = Unit
    }

    private class AdvancingClock(var elapsed: Long = 0L) : GymAppClock {
        override fun wallTimeMillis(): Long = AppSessionFixtures.FIXED_CLOCK + elapsed
        override fun elapsedRealtimeMillis(): Long = elapsed
    }

    private data class Fixture(
        val database: GymDatabase,
        val transport: AppSessionTransport,
        val runtime: AccountGymAppState,
        val identity: FakeIdentity,
        val clock: AdvancingClock,
    )

    private fun fixture(
        identity: FakeIdentity = FakeIdentity(),
        clock: AdvancingClock = AdvancingClock(),
    ): Fixture {
        val database = harness.openInMemoryDb().also { dbs += it }
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
            authApi = AppSessionAuthApi(),
            transport = transport,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = clock,
            timerHardware = NoOpHardware(),
            uuidProvider = { uuids.next() },
        ).also { runtimes += it }
        return Fixture(database, transport, runtime, identity, clock)
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
            assertTrue("activation must be Active, was $active", active is AppSessionState.Active)
        }
    }

    private fun Fixture.operations(auth: FakeExchangeAuth = FakeExchangeAuth()): SessionAccountStartupOperations =
        SessionAccountStartupOperations(
            accountState = runtime,
            signInCoordinator = ForegroundSignInCoordinator(
                origin = AppSessionFixtures.origin(),
                identityProvider = identity,
                handoff = runtime.sessionCoordinator,
                authApi = auth,
                clockMillis = { clock.wallTimeMillis() },
                uuidProvider = { UUID.randomUUID().toString() },
            ),
        )

    private fun restSeconds(setId: String): Long? {
        dbs.last().openHelper.readableDatabase.query(
            "SELECT rest_seconds FROM workout_sets WHERE id = ?",
            arrayOf(setId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "seeded set must exist" }
            return if (cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    @Test
    fun openCreateEditor_refusesReauthBeforeGoogleLaunch_preservesTypedDraft() {
        val fix = fixture()
        fix.activate()
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState)
        runBlocking { appState.showCreateExercise() }
        composeRule.runOnIdle { }
        assertEquals(Destination.CreateExercise, appState.destination)
        // Type an actual draft: refusal must preserve content, not just the destination.
        composeRule.onNodeWithTag("exercise-field-name").performTextInput("R13c Draft Press")
        composeRule.onNodeWithTag("exercise-field-name").assertTextEquals("R13c Draft Press")
        val ops = fix.operations()
        val expected = runBlocking { RoomSessionAuthority(fix.database).read() }
        val error = runBlocking {
            runCatching {
                ops.signIn(
                    intent = ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION,
                    expected = expected,
                    operationId = UUID.randomUUID().toString(),
                )
            }.exceptionOrNull()
        }
        checkNotNull(error) { "open editor must refuse reauth before provider launch" }
        assertTrue("expected AccountStartupException(EDITOR_OPEN), was $error", error is AccountStartupException)
        assertEquals(AccountStartupError.EDITOR_OPEN, (error as AccountStartupException).error)
        assertTrue("no Google launch before refusal", fix.identity.nonces.isEmpty())
        assertEquals(Destination.CreateExercise, appState.destination)
        composeRule.onNodeWithTag("exercise-field-name").assertTextEquals("R13c Draft Press")
        assertSame(appState, fix.runtime.appState)
        composeRule.onNodeWithTag("account-privacy-gate").assertDoesNotExist()
        runBlocking { appState.closeCreateExercise() }
        composeRule.runOnIdle { }
        assertTrue(appState.destination != Destination.CreateExercise)
    }

    @Test
    fun closedEditor_reauthInvokesProviderAndDrainsTimerExactlyOnce() {
        val fix = fixture()
        fix.activate()
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState)
        // Live handle captured before reauth retires it: its binding is the
        // barrier previous-scope the app state still holds.
        val liveSession = (fix.runtime.sessionCoordinator.state.value as AppSessionState.Active).session
        val seeds = runBlocking {
            val session = liveSession
            val customId = session.repository.createCustomExercise("R13c Timer Press", null, emptySet(), null, null, "")
            val workout = session.repository.startEmptyWorkout(1_700_000_000_000L)
            session.repository.addExercisesToWorkout(workout.id, listOf(customId))
            val entry = checkNotNull(session.repository.workoutDetails(workout.id)).exercises.single()
            val set = entry.sets.single()
            assertTrue(session.repository.updateWorkoutSet(set.copy(repetitions = 8, weightKg = 55.0, isCompleted = true)))
            Triple(workout.id, entry.workoutExercise.id, set.id)
        }
        val (workoutId, _, setId) = seeds
        // The timer start is accepted only once the app projection has bound
        // the active workout (direct repository writes alone do not bind it).
        runBlocking { appState.refreshProjection() }
        composeRule.waitUntil(10_000L) { appState.activeWorkout?.id == workoutId }
        val source = runBlocking {
            checkNotNull(
                (fix.runtime.sessionCoordinator.state.value as AppSessionState.Active)
                    .session.repository.workoutDetails(workoutId),
            ).exercises.single().sets.single()
        }
        assertTrue("timer source must be completed", source.isCompleted)
        assertEquals(setId, source.id)
        composeRule.runOnIdle {
            appState.startRestTimer(
                source,
                90,
                acceptedEdit = true,
                expectedWorkoutId = workoutId,
            )
        }
        composeRule.runOnIdle { }
        assertNotNull(appState.activeRestTimer)
        assertNull(restSeconds(setId))
        // Drive the real controller wiring (not a direct facade grant): the same
        // sign-in that invokes the provider must drain the terminal write.
        fix.clock.elapsed += 5_000L
        val ops = fix.operations()
        val expected = runBlocking { RoomSessionAuthority(fix.database).read() }
        val result = runBlocking {
            ops.signIn(
                intent = ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION,
                expected = expected,
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertTrue("reauth must land Private preparation, was $result", result is AppSessionState.Private)
        assertEquals("provider must actually be invoked, not merely recovered", 1, fix.identity.nonces.size)
        composeRule.runOnIdle { }
        // The same real reauth transition drains the running timer exactly
        // once with elapsed whole seconds (no second grant, barrier replay or
        // completion call involved).
        assertEquals("elapsed whole seconds drain exactly once", 5L, restSeconds(setId))
        assertNull(appState.activeRestTimer)
        assertEquals("no second claim may rewrite rest", 5L, restSeconds(setId))
    }

    private class NoOpHardware : RestTimerHardware {
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) = Unit
        override fun releaseWakeLock() = Unit
    }
}
