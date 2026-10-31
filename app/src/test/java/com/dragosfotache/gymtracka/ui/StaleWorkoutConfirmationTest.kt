package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.testing.FakeGymAppStateRepository
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks that stale confirmation callbacks cannot delete or finish a superseded active workout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleWorkoutConfirmationTest {
    private companion object {
        const val ACCOUNT = "c1200001-1111-4111-8111-111111111111"
        const val GENERATION = 5L
    }
    @Test
    fun staleDiscardConfirmAfterReplacementDeletesNothing() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w-old", "Old", 1_000L)
            details = workoutDetailsFixture(name = "Old")
        }
        val state = state(repository)
        runCurrent()
        assertEquals(Destination.Workout, state.destination)
        state.requestDiscardWorkout()
        assertTrue(state.showDiscardDialog)

        repository.active = WorkoutEntity("w-new", "New", 2_000L)
        repository.details = workoutDetailsFixture(name = "New")
        repository.projectionEpochs.value = 1L
        runCurrent()
        state.refreshProjection()
        runCurrent()

        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals(Destination.Workout, state.destination)
        assertFalse("old root-bound prompt must clear", state.showDiscardDialog)

        state.confirmDiscardWorkout()
        runCurrent()
        assertTrue(repository.discarded.isEmpty())
        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals(Destination.Workout, state.destination)
    }

    @Test
    fun staleFinishConfirmAfterReplacementFinishesNothing() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w-old", "Old", 1_000L)
            details = workoutDetailsFixture(name = "Old")
        }
        val state = state(repository)
        runCurrent()
        state.requestFinishWorkout()
        runCurrent()
        assertTrue(state.showFinishDialog)

        repository.active = WorkoutEntity("w-new", "New", 2_000L)
        repository.details = workoutDetailsFixture(name = "New")
        repository.projectionEpochs.value = 1L
        runCurrent()
        state.refreshProjection()
        runCurrent()

        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals(Destination.Workout, state.destination)
        assertFalse("old root-bound prompt must clear", state.showFinishDialog)
        assertNull(state.finishedWorkoutSummary)

        state.confirmFinishWorkout()
        runCurrent()
        assertTrue("stale finish must not finish the new root", repository.finished.isEmpty())
        assertNull(state.finishedWorkoutSummary)
        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals("no auto-enter into replacement", Destination.Workout, state.destination)
    }

    @Test
    fun deletedRootClearsFinishPromptWithoutPhantomSummary() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w-old", "Old", 1_000L)
            details = workoutDetailsFixture(name = "Old")
        }
        val state = state(repository)
        runCurrent()
        state.requestFinishWorkout()
        runCurrent()
        assertTrue(state.showFinishDialog)

        repository.active = null
        repository.details = null
        repository.projectionEpochs.value = 1L
        runCurrent()
        state.refreshProjection()
        runCurrent()

        assertNull(state.activeWorkout)
        assertFalse("deleted root-bound prompt must clear", state.showFinishDialog)
        assertNull("no phantom summary for deleted root", state.finishedWorkoutSummary)
        assertTrue(repository.finished.isEmpty())
    }

    @Test
    fun staleStartConfirmAfterReplacementStartsNothing() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w-old", "Old", 1_000L)
            details = workoutDetailsFixture(name = "Old")
        }
        val state = state(repository)
        runCurrent()
        state.requestStartWorkout()
        assertTrue(state.showStartNewWorkoutDialog)

        repository.active = WorkoutEntity("w-new", "New", 2_000L)
        repository.details = workoutDetailsFixture(name = "New")
        repository.projectionEpochs.value = 1L
        runCurrent()
        state.refreshProjection()
        runCurrent()

        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals(Destination.Workout, state.destination)
        assertEquals("New", state.activeWorkout?.name)

        state.confirmStartNewWorkout()
        runCurrent()
        assertTrue(repository.discarded.isEmpty())
        assertTrue(repository.startedEmptyAt.isEmpty())
        assertTrue(repository.performedAgainAt.isEmpty())
        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals(Destination.Workout, state.destination)
    }

    private fun kotlinx.coroutines.test.TestScope.state(
        repository: FakeGymAppStateRepository,
        clock: GymAppClock = VirtualClock(testScheduler),
        hardware: RestTimerHardware = FakeTimerHardware(),
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
