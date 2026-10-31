package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
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
 * Checks app-shell refresh after materialized-epoch changes, preserving selection and navigation
 * currentness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppShellRefreshTest {
    private companion object {
        const val ACCOUNT = "c1200001-1111-4111-8111-111111111111"
        const val GENERATION = 5L
    }
    @Test
    fun sameIdRenameRefreshesPillWithoutNavigationTimerOrMutations() = runTest {
        val completed = WorkoutSetEntity("set", "entry", 0, 8, 50.0).copy(isCompleted = true)
        val base = workoutDetailsFixture(name = "Morning", sets = listOf(completed))
        val repository = FakeGymAppStateRepository().apply {
            active = base.workout
            details = base
        }
        val state = state(repository)
        runCurrent()
        assertEquals("Morning", state.activeWorkout?.name)
        state.resumeWorkout()
        assertEquals(Destination.ActiveWorkout, state.destination)
        state.startRestTimer(
            base.exercises.single().sets.single(),
            30,
            acceptedEdit = true,
            expectedWorkoutId = base.workout.id,
        )
        runCurrent()
        val timer = checkNotNull(state.activeRestTimer) { "timer must bind the completed set" }

        val renamed = workoutDetailsFixture(name = "Morning Renamed", sets = listOf(completed))
        repository.active = renamed.workout
        repository.details = renamed
        repository.projectionEpochs.value = 1L
        runCurrent()
        state.refreshProjection()
        runCurrent()

        assertEquals(base.workout.id, state.activeWorkout?.id)
        assertEquals("Morning Renamed", state.activeWorkout?.name)
        assertEquals(Destination.ActiveWorkout, state.destination)
        assertEquals(timer.timerId, state.activeRestTimer?.timerId)
        assertFalse(state.refreshFailed)
        assertTrue(repository.finished.isEmpty())
        assertTrue(repository.discarded.isEmpty())
        assertTrue(repository.startedEmptyAt.isEmpty())
        assertFalse(state.showStartNewWorkoutDialog)
    }

    @Test
    fun remoteReplacementReturnsToWorkoutWithoutAutoEnteringReplacement() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w-old", "Old", 1_000L)
            details = workoutDetailsFixture(name = "Old")
        }
        val state = state(repository)
        runCurrent()
        state.resumeWorkout()
        assertEquals(Destination.ActiveWorkout, state.destination)

        repository.active = WorkoutEntity("w-new", "New", 2_000L)
        repository.details = workoutDetailsFixture(name = "New")
        repository.projectionEpochs.value = 1L
        runCurrent()
        state.refreshProjection()
        runCurrent()

        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals(Destination.Workout, state.destination)
        assertFalse(state.refreshFailed)
        assertNull(state.activeRestTimer)
        assertFalse(state.showStartNewWorkoutDialog)
    }

    @Test
    fun missingSelectedHistoryReturnsToHistoryWithoutPhantomSummary() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w1", "Current", 1_000L)
            details = workoutDetailsFixture(name = "Current")
        }
        val state = state(repository)
        runCurrent()
        state.showWorkoutHistory("h-missing")
        assertEquals(Destination.WorkoutHistoryDetail, state.destination)

        repository.details = null
        runCurrent()
        state.refreshProjection()
        runCurrent()

        assertEquals(Destination.History, state.destination)
        assertTrue(repository.finished.isEmpty())
        assertTrue(repository.discarded.isEmpty())
    }

    @Test
    fun failedReloadMarksRefreshFailedWithoutNavigatingAwayOrWriting() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w1", "Current", 1_000L)
            details = workoutDetailsFixture(name = "Current")
        }
        val state = state(repository)
        runCurrent()
        state.showExercises()
        assertEquals(Destination.Exercises, state.destination)

        repository.activeWorkoutError = IllegalStateException("runtime-epoch-read-failed")
        state.refreshProjection()
        runCurrent()

        assertTrue(state.refreshFailed)
        assertEquals(Destination.Exercises, state.destination)
        assertEquals("w1", state.activeWorkout?.id)
        assertTrue(repository.finished.isEmpty())
        assertTrue(repository.discarded.isEmpty())
    }

    @Test
    fun refreshNeverStartsTimersDialogsOrMutations() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w1", "Current", 1_000L)
            details = workoutDetailsFixture(name = "Current")
        }
        val state = state(repository)
        runCurrent()
        assertNull(state.activeRestTimer)

        repository.projectionEpochs.value = 1L
        runCurrent()
        state.refreshProjection()
        runCurrent()

        assertNull(state.activeRestTimer)
        assertFalse(state.showStartNewWorkoutDialog)
        assertTrue(repository.finished.isEmpty())
        assertTrue(repository.discarded.isEmpty())
        assertTrue(repository.startedEmptyAt.isEmpty())
        assertTrue(repository.performedAgainAt.isEmpty())
        assertTrue(repository.startedRoutineAt.isEmpty())
    }

    @Test
    fun failedTimerTargetReadRetainsRunningTimerAndFlagsRefresh() = runTest {
        val completed = WorkoutSetEntity("set", "entry", 0, 8, 50.0).copy(isCompleted = true)
        val base = workoutDetailsFixture(name = "Morning", sets = listOf(completed))
        val repository = FakeGymAppStateRepository().apply {
            active = base.workout
            details = base
        }
        val hardware = FakeTimerHardware()
        val state = state(
            repository,
            hardware = hardware,
            session = RepositorySession(ACCOUNT, GENERATION),
        )
        runCurrent()
        state.startRestTimer(
            base.exercises.single().sets.single(),
            30,
            acceptedEdit = true,
            expectedWorkoutId = base.workout.id,
        )
        runCurrent()
        val timer = checkNotNull(state.activeRestTimer) { "timer must bind the completed set" }
        assertTrue(hardware.isHeld)

        repository.detailsError = IllegalStateException("runtime-target-read-failed")
        state.refreshProjection()
        runCurrent()
        assertTrue("failed target read must flag latest refresh", state.refreshFailed)
        assertEquals(timer.timerId, state.activeRestTimer?.timerId)
        assertTrue("valid running timer/hardware must survive failed read", hardware.isHeld)

        repository.detailsError = null
        state.refreshProjection()
        runCurrent()
        assertFalse(state.refreshFailed)
        assertEquals(timer.timerId, state.activeRestTimer?.timerId)
    }

    private fun kotlinx.coroutines.test.TestScope.state(
        repository: FakeGymAppStateRepository,
        clock: GymAppClock = VirtualClock(testScheduler),
        hardware: RestTimerHardware = FakeTimerHardware(),
        session: RepositorySession = RepositorySession.SIGNED_OUT,
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
