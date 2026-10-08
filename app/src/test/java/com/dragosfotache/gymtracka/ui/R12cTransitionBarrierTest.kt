package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.testing.FakeGymAppStateRepository
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * R12c independent QA: real GymAppState.transitionBarrier drain/claim
 * behavior (outcome 4 JVM slice).
 *
 * Hand-written from docs/remaining-work/R12c-task.md only (fixed TARGET
 * contract): fun transitionBarrier(): AppSessionTransitionBarrier pauses /
 * drains accepted edits while the old handle stays valid, claims running
 * rest exactly once, then grants a held lease. Every case binds the same
 * independently completed detail/root/entry/set identity, settles initial
 * discovery before starting the timer, and runs with an explicit matching
 * account session. Account timer starts below use the accepted-completion
 * callback seam (acceptedEdit + expectedWorkoutId); these are
 * timer-boundary tests, not a relaxation of the account refusal policy. Hand-written fakes and virtual time; no sleeps, no
 * production oracles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R12cTransitionBarrierTest {
    private companion object {
        const val ACCOUNT = "c1200001-1111-4111-8111-111111111111"
        const val GENERATION = 5L
    }

    private data class Bound(
        val repository: FakeGymAppStateRepository,
        val state: GymAppState,
        val setId: String,
    )

    private fun kotlinx.coroutines.test.TestScope.bound(durationSeconds: Int = 90): Bound {
        val completed = WorkoutSetEntity("set", "entry", 0, 8, 50.0).copy(isCompleted = true)
        val base = workoutDetailsFixture(name = "Lift", sets = listOf(completed))
        val repository = FakeGymAppStateRepository().apply {
            active = base.workout
            details = base
        }
        val state = state(repository)
        runCurrent()
        state.startRestTimer(
            base.exercises.single().sets.single(),
            durationSeconds,
            acceptedEdit = true,
            expectedWorkoutId = base.workout.id,
        )
        return Bound(repository, state, completed.id)
    }

    @Test
    fun transitionDrainClaimsElapsedWholeSecondsExactlyOnce() = runTest {
        val (repository, state, setId) = bound()
        advanceTimeBy(5.seconds)
        runCurrent()

        val barrier = state.transitionBarrier()
        val lease = barrier.acquire(RepositorySession(ACCOUNT, GENERATION))
        runCurrent()

        assertEquals(listOf(setId to 5), repository.restUpdates)
        assertEquals(listOf(setId to 5), repository.committedRestUpdates)
        assertNull(state.activeRestTimer)
        lease.close()
    }

    @Test
    fun mismatchedPreviousScopeRefusesWithoutDrainingRest() = runTest {
        val (repository, state, setId) = bound()
        advanceTimeBy(5.seconds)
        runCurrent()

        val barrier = state.transitionBarrier()
        try {
            barrier.acquire(RepositorySession("other-account", GENERATION))
            runCurrent()
            fail("Mismatched previous scope must be refused")
        } catch (_: Exception) {
        }
        assertTrue(repository.restUpdates.isEmpty())
        assertEquals(setId, state.activeRestTimer?.workoutSetId)
    }

    @Test
    fun failedTerminalDrainRetainsHealthyEditing() = runTest {
        val (repository, state, setId) = bound()
        advanceTimeBy(2.seconds)
        runCurrent()
        repository.restUpdate = { _, _ -> throw IllegalStateException("r12c-terminal-drain-failed") }

        val barrier = state.transitionBarrier()
        try {
            barrier.acquire(RepositorySession(ACCOUNT, GENERATION))
            runCurrent()
            fail("Failed terminal drain must not grant a transition lease")
        } catch (_: IllegalStateException) {
            // Retained drain failure: healthy editing must survive.
        }
        assertEquals("exactly one terminal write attempt", 1, repository.restUpdates.size)
        assertEquals(setId, repository.restUpdates.single().first)
        assertTrue("failed write must not commit", repository.committedRestUpdates.isEmpty())

        repository.restUpdate = { _, _ -> }
        state.startRestTimer(
            WorkoutSetEntity("set", "entry", 1).copy(isCompleted = true),
            90,
            acceptedEdit = true,
            expectedWorkoutId = "workout",
        )
        runCurrent()
        assertEquals("set", state.activeRestTimer?.workoutSetId)
    }

    @Test
    fun cancelledAcquisitionPropagatesWithoutGrantingLease() = runTest {
        val repository = FakeGymAppStateRepository()
        val state = state(repository)
        val barrier = state.transitionBarrier()
        backgroundScope.cancel(CancellationException("r12c-owner-cancelled"))
        try {
            barrier.acquire(RepositorySession(ACCOUNT, GENERATION))
            runCurrent()
            fail("Cancelled scope must not grant a transition lease")
        } catch (_: CancellationException) {
            // Cancellation always propagates.
        }
        assertTrue(repository.restUpdates.isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.state(
        repository: FakeGymAppStateRepository,
        hardware: RestTimerHardware = FakeTimerHardware(),
        clock: GymAppClock = VirtualClock(testScheduler),
        session: RepositorySession = RepositorySession(ACCOUNT, GENERATION),
    ) = GymAppState(repository, backgroundScope, clock, hardware, session = session)

    private class VirtualClock(private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) : GymAppClock {
        override fun wallTimeMillis() = 1_700_000_000_000L + scheduler.currentTime
        override fun elapsedRealtimeMillis() = scheduler.currentTime
    }

    private class FakeTimerHardware : RestTimerHardware {
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
