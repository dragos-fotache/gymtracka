package com.dragosfotache.gymtracka.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.CANONICAL_FINISHED_AT
import com.dragosfotache.gymtracka.data.sync.CANONICAL_OP_UPSERT
import com.dragosfotache.gymtracka.data.sync.CANONICAL_STARTED_AT
import com.dragosfotache.gymtracka.data.sync.CANONICAL_TYPE_CUSTOM
import com.dragosfotache.gymtracka.data.sync.CANONICAL_TYPE_WORKOUT
import com.dragosfotache.gymtracka.data.sync.canonicalChange
import com.dragosfotache.gymtracka.data.sync.canonicalCustomRecord
import com.dragosfotache.gymtracka.data.sync.canonicalWorkoutEntry
import com.dragosfotache.gymtracka.data.sync.canonicalWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.canonicalWorkoutSet
import com.dragosfotache.gymtracka.data.sync.canonicalJsonArray
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppReconciliationStop
import com.dragosfotache.gymtracka.data.sync.session.AppSessionAuthApi
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransport
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the real runtime publication pipeline, timer impact and once-only terminal rest
 * persistence.
 */
@RunWith(AndroidJUnit4::class)
class AccountRuntimeTimerPipelineTest {
    private companion object {
        const val WORKOUT = "d1200001-1111-4111-8111-111111111111"
        const val ENTRY = "d1200002-2222-4222-8222-222222222222"
        const val SET = "d1200003-3333-4333-8333-333333333333"
        const val EXERCISE_REF = "d1200004-4444-4111-8111-111111111111"
        const val SEED_BATCH = "d1200011-1111-4111-8111-111111111111"
        const val FINISH_BATCH = "d1200012-2222-4222-8222-222222222222"
        const val SEED_CURSOR = "runtime-seed-cursor"
        const val FINISH_CURSOR = "runtime-finish-cursor"

        fun seedPullBodyUncompleted(): String = seedPullBody(setCompleted = false)

        fun pullBody(changeJson: String, batchId: String): String =
            """{"batches":[{"batchId":"$batchId","cursor":"$FINISH_CURSOR","changes":[$changeJson]}],""" +
                """"nextCursor":"$FINISH_CURSOR","hasMore":false}"""

        fun seedPullBody(setCompleted: Boolean = true): String {
            val custom = canonicalChange(
                CANONICAL_TYPE_CUSTOM,
                EXERCISE_REF,
                CANONICAL_OP_UPSERT,
                1L,
                canonicalCustomRecord(id = EXERCISE_REF, name = "Press", revision = 1L),
            ).toString()
            val workout = workoutChange("active", null, 1L, setCompleted)
            return """{"batches":[{"batchId":"$SEED_BATCH","cursor":"$SEED_CURSOR","changes":[$custom,$workout]}],""" +
                """"nextCursor":"$SEED_CURSOR","hasMore":false}"""
        }

        fun workoutChange(status: String, finishedAt: String?, revision: Long, setCompleted: Boolean): String =
            canonicalChange(
                CANONICAL_TYPE_WORKOUT,
                WORKOUT,
                CANONICAL_OP_UPSERT,
                revision,
                canonicalWorkoutRecord(
                    id = WORKOUT,
                    name = "Timed",
                    status = status,
                    startedAt = CANONICAL_STARTED_AT,
                    finishedAt = finishedAt,
                    revision = revision,
                    exercises = canonicalJsonArray(
                        canonicalWorkoutEntry(
                            id = ENTRY,
                            exerciseId = EXERCISE_REF,
                            exerciseName = "Press",
                            sets = canonicalJsonArray(canonicalWorkoutSet(id = SET, isCompleted = setCompleted)),
                        ),
                    ),
                ),
            ).toString()
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val harness = AppSessionHarness("runtime-pipeline")
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
        val clock: AdvancingClock,
        val hardware: RecordingHardware,
        val runtime: AccountGymAppState,
    )

    private fun fixture(): Fixture {
        val database = harness.openInMemoryDb().also { dbs += it }
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
        val clock = AdvancingClock()
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
            vault = harness.newVault(),
            authApi = auth,
            transport = transport,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = clock,
            timerHardware = hardware,
            uuidProvider = { uuids.next() },
        ).also { runtimes += it }
        return Fixture(database, auth, transport, clock, hardware, runtime)
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

    private fun Fixture.seedActiveWorkout(session: AppAccountSession) {
        val mark = transport.capturedRequests.size
        transport.queueOk(seedPullBody())
        val result = runBlocking {
            runtime.sessionCoordinator.reconcile(session, epoch())
        }
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertEquals(listOf("/v1/sync/pull"), pathsFrom(mark))
        assertTrue("seed phase must drain its replies", transport.queue.isEmpty())
        val seeded = harness.canonicalRevision(database, AppSessionFixtures.ACCOUNT, CANONICAL_TYPE_WORKOUT, WORKOUT)
        assertEquals(1L, checkNotNull(seeded) { "seed pull must stage workout rev 1" }.first)
        val visible = runBlocking { checkNotNull(session.repository.workoutDetails(WORKOUT)) }
        assertEquals("active", visible.workout.status)
    }

    private fun Fixture.pathsFrom(mark: Int) =
        transport.capturedRequests.subList(mark, transport.capturedRequests.size).map { it.path }

    private fun Fixture.epoch() = checkNotNull(harness.cacheEpoch(database, AppSessionFixtures.ACCOUNT)) {
        "durable cache epoch must exist"
    }

    private fun Fixture.restSeconds(): Long? {
        database.openHelper.readableDatabase.query(
            "SELECT rest_seconds FROM workout_sets WHERE id = ?",
            arrayOf(SET),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "seeded set must exist" }
            return if (cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    private fun Fixture.visibleStatus(): String? =
        runBlocking { sessionRepository().workoutDetails(WORKOUT)?.workout?.status }

    private fun Fixture.sessionRepository() = runBlocking { currentSession().repository }

    private fun Fixture.currentSession(): AppAccountSession {
        val state = runtime.sessionCoordinator.state.value
        assertTrue("runtime must be Active, was $state", state is AppSessionState.Active)
        return (state as AppSessionState.Active).session
    }

    @Test
    fun runningTimerClaimedOnceBeforeFinishInvalidatesWithProtectedGraph() {
        val fix = fixture()
        val session = fix.activate()
        fix.seedActiveWorkout(session)
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState != null }
        val appState = checkNotNull(fix.runtime.appState)
        val set = runBlocking {
            val details = checkNotNull(session.repository.workoutDetails(WORKOUT))
            details.exercises.single().sets.single()
        }
        composeRule.runOnIdle {
            appState.startRestTimer(
                set,
                90,
                acceptedEdit = true,
                expectedWorkoutId = WORKOUT,
            )
        }
        composeRule.runOnIdle { }
        assertTrue(fix.hardware.isHeld)
        assertNull("no rest written before claim", fix.restSeconds())

        fix.clock.elapsedRealtime += 5_000L
        fix.transport.queueOk(pullBody(workoutChange("finished", CANONICAL_FINISHED_AT, 2L, true), FINISH_BATCH))
        val result = runBlocking { fix.runtime.sessionCoordinator.reconcile(session, fix.epoch()) }
        assertEquals(AppReconciliationStop.PROTECTED, result.stop)
        AppSessionFixtures.assertRedacted(result.toString())
        assertTrue("finish phase must drain its replies", fix.transport.queue.isEmpty())

        assertEquals("claimed rest persisted exactly once", 5L, checkNotNull(fix.restSeconds()))
        assertEquals("visible graph preserved while protected", "active", fix.visibleStatus())
        assertNull("claimed terminal rest drained", appState.activeRestTimer)
        composeRule.runOnIdle { }

        fix.transport.queueCode(500, """{"errorCode":"probe-transient"}""")
        val stopped = runBlocking { fix.runtime.sessionCoordinator.reconcile(session, fix.epoch()) }
        assertEquals(AppReconciliationStop.PUSH_STOPPED, stopped.stop)
        assertTrue("stop phase must drain its replies", fix.transport.queue.isEmpty())
        assertEquals("no second claim may rewrite rest", 5L, checkNotNull(fix.restSeconds()))
    }

    @Test
    fun plainFinishWithoutTimerAppliesNormallyAndReturnsToWorkout() {
        val fix = fixture()
        val session = fix.activate()
        fix.seedActiveWorkout(session)
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState?.activeWorkout?.id == WORKOUT }
        val appState = checkNotNull(fix.runtime.appState)
        composeRule.runOnIdle { appState.resumeWorkout() }
        assertEquals(Destination.ActiveWorkout, appState.destination)

        fix.transport.queueOk(pullBody(workoutChange("finished", CANONICAL_FINISHED_AT, 2L, true), FINISH_BATCH))
        val result = runBlocking { fix.runtime.sessionCoordinator.reconcile(session, fix.epoch()) }
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        AppSessionFixtures.assertRedacted(result.toString())
        assertTrue("finish phase must drain its replies", fix.transport.queue.isEmpty())
        assertEquals("finished", fix.visibleStatus())
        composeRule.waitUntil(10_000L) { appState.destination == Destination.Workout }
        assertNull(fix.restSeconds())
        assertEquals(
            "finish pull must advance the checkpoint",
            FINISH_CURSOR,
            fix.database.openHelper.readableDatabase.query(
                "SELECT cursor FROM sync_state WHERE account_id = ?",
                arrayOf(AppSessionFixtures.ACCOUNT),
            ).use { cursor ->
                check(cursor.moveToFirst())
                if (cursor.isNull(0)) null else cursor.getString(0)
            },
        )
    }

    @Test
    fun failedPushStopsWithoutPullBypassAndPreservesNewerIntent() {
        val fix = fixture()
        val session = fix.activate()
        fix.seedActiveWorkout(session)
        runBlocking { session.repository.updateWorkoutName(WORKOUT, "Local Rename") }
        val mark = fix.transport.capturedRequests.size
        fix.transport.queueCode(500, """{"errorCode":"probe-transient"}""")
        val result = runBlocking { fix.runtime.sessionCoordinator.reconcile(session, fix.epoch()) }
        assertEquals(AppReconciliationStop.PUSH_STOPPED, result.stop)
        assertTrue("stop phase must drain its replies", fix.transport.queue.isEmpty())
        assertTrue(
            "failed push must not pull or snapshot past the stop",
            fix.transport.capturedRequests.subList(mark, fix.transport.capturedRequests.size)
                .none { it.path == "/v1/sync/pull" || it.path == "/v1/sync/snapshot" },
        )
        val details = runBlocking { checkNotNull(session.repository.workoutDetails(WORKOUT)) }
        assertEquals("Local Rename", details.workout.name)
    }

    @Test
    fun noLateRestWriteIntoFinishedHistory() {
        val fix = fixture()
        val session = fix.activate()
        fix.seedActiveWorkout(session)
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState?.activeWorkout?.id == WORKOUT }
        val appState = checkNotNull(fix.runtime.appState)
        fix.transport.queueOk(pullBody(workoutChange("finished", CANONICAL_FINISHED_AT, 2L, true), FINISH_BATCH))
        val result = runBlocking { fix.runtime.sessionCoordinator.reconcile(session, fix.epoch()) }
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertTrue("finish phase must drain its replies", fix.transport.queue.isEmpty())
        assertEquals("finished", fix.visibleStatus())
        val finishedSet = runBlocking {
            checkNotNull(session.repository.workoutDetails(WORKOUT)).exercises.single().sets.single()
        }
        composeRule.runOnIdle {
            appState.startRestTimer(
                finishedSet,
                90,
                acceptedEdit = true,
                expectedWorkoutId = WORKOUT,
            )
        }
        composeRule.runOnIdle { }
        assertNull("no timer may start on a finished source", appState.activeRestTimer)
        assertTrue("refused start must not hold hardware", !fix.hardware.isHeld)
        assertNull("finished history rows must never take late rest writes", fix.restSeconds())
        assertTrue(
            "refused start must not emit outbox intent",
            harness.outboxSnapshot(fix.database, AppSessionFixtures.ACCOUNT).isEmpty(),
        )
    }

    @Test
    fun directNonCallbackAccountStartRefusesAsNoOp() {
        val fix = fixture()
        val session = fix.activate()
        fix.seedActiveWorkout(session)
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState?.activeWorkout?.id == WORKOUT }
        val appState = checkNotNull(fix.runtime.appState)
        val set = runBlocking {
            checkNotNull(session.repository.workoutDetails(WORKOUT)).exercises.single().sets.single()
        }
        // Timer-boundary: a direct manual start without the accepted
        // completion callback seam must refuse as an observable no-op.
        composeRule.runOnIdle { appState.startRestTimer(set, 90) }
        composeRule.runOnIdle { }
        assertNull(appState.activeRestTimer)
        assertTrue(!fix.hardware.isHeld)
        assertNull(fix.restSeconds())
        assertTrue(
            harness.outboxSnapshot(fix.database, AppSessionFixtures.ACCOUNT).isEmpty(),
        )
    }

    @Test
    fun scopedCheckboxCompletionWiresAcceptedCallbackTimerAndIntent() {
        val fix = fixture()
        val session = fix.activate()
        fix.transport.queueOk(seedPullBodyUncompleted())
        val seeded = runBlocking { fix.runtime.sessionCoordinator.reconcile(session, fix.epoch()) }
        assertEquals(AppReconciliationStop.TAIL, seeded.stop)
        assertTrue(fix.transport.queue.isEmpty())
        composeRule.setContent { GymApp(fix.runtime) }
        composeRule.waitUntil(10_000L) { fix.runtime.appState?.activeWorkout?.id == WORKOUT }
        val appState = checkNotNull(fix.runtime.appState)
        composeRule.runOnIdle { appState.resumeWorkout() }
        composeRule.onNodeWithTag("workout-set-complete-$SET").assertIsDisplayed()
        // Real checkbox/state-holder plus shared finite edits drive the
        // accepted completion callback: the scoped timer must start and the
        // completion intent must be captured in the account outbox.
        composeRule.onNodeWithTag("workout-set-complete-$SET").performClick()
        composeRule.runOnIdle { }
        composeRule.waitUntil(10_000L) { appState.activeRestTimer != null }
        assertTrue(fix.hardware.isHeld)
        assertTrue(
            "checkbox must persist the completed set",
            runBlocking {
                checkNotNull(session.repository.workoutDetails(WORKOUT))
                    .exercises.single().sets.single().isCompleted
            },
        )
        assertTrue(
            "accepted completion must capture outbox intent",
            harness.outboxSnapshot(fix.database, AppSessionFixtures.ACCOUNT).isNotEmpty(),
        )
        fix.transport.queueCode(500, """{"errorCode":"probe-transient"}""")
        val stopped = runBlocking { fix.runtime.sessionCoordinator.reconcile(session, fix.epoch()) }
        assertEquals(AppReconciliationStop.PUSH_STOPPED, stopped.stop)
        assertTrue(
            "newer completion intent preserved through the stop",
            harness.outboxSnapshot(fix.database, AppSessionFixtures.ACCOUNT).isNotEmpty(),
        )
    }

    private class AdvancingClock : GymAppClock {
        var elapsedRealtime = 0L
        override fun wallTimeMillis() = AppSessionFixtures.FIXED_CLOCK + elapsedRealtime
        override fun elapsedRealtimeMillis() = elapsedRealtime
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
