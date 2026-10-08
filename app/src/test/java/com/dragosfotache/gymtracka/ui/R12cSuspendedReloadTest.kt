package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.testing.FakeGymAppStateRepository
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * R12c independent QA: suspended-reload ordering for app-shell refresh.
 *
 * Hand-written from docs/remaining-work/R12c-task.md only (fixed TARGET
 * contract): latest-load/scope guards win — an older suspended load must not
 * restore stale models or overwrite later accepted starts/navigation, an old
 * failure must not override a newer success, and a newer materialized epoch
 * invalidates the older suspended load without waiting for it. Hand-written
 * fakes with first-call read gates, virtual time, independently expected
 * literals; no sleeps, no production oracles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R12cSuspendedReloadTest {
    @Test
    fun staleSuspendedLoadDoesNotOverwriteNewerAcceptedName() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w1", "Old", 1_000L)
            details = workoutDetailsFixture(name = "Old")
        }
        val state = state(repository)
        runCurrent()
        assertEquals("Old", state.activeWorkout?.name)

        var calls = 0
        val gate = CompletableDeferred<Unit>()
        repository.activeWorkoutGate = {
            calls++
            if (calls == 1) gate.await()
        }
        val stale = async { state.refreshProjection() }
        runCurrent()

        repository.active = WorkoutEntity("w1", "New", 1_000L)
        repository.details = workoutDetailsFixture(name = "New")
        repository.activeWorkoutGate = null
        state.refreshProjection()
        runCurrent()
        assertEquals("New", state.activeWorkout?.name)
        assertFalse(state.refreshFailed)

        gate.complete(Unit)
        stale.await()
        runCurrent()
        assertEquals("New", state.activeWorkout?.name)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun oldFailureDoesNotOverrideNewerSuccess() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w1", "Old", 1_000L)
            details = workoutDetailsFixture(name = "Old")
        }
        val state = state(repository)
        runCurrent()

        var calls = 0
        val gate = CompletableDeferred<Unit>()
        repository.activeWorkoutGate = {
            calls++
            if (calls == 1) gate.await()
        }
        val older = async { state.refreshProjection() }
        runCurrent()

        repository.active = WorkoutEntity("w1", "New", 1_000L)
        repository.details = workoutDetailsFixture(name = "New")
        repository.activeWorkoutGate = null
        state.refreshProjection()
        runCurrent()
        assertEquals("New", state.activeWorkout?.name)
        assertFalse(state.refreshFailed)

        repository.activeWorkoutError = IllegalStateException("r12c-late-failure")
        repository.activeWorkoutGate = { }
        gate.complete(Unit)
        try {
            older.await()
        } catch (_: IllegalStateException) {
        }
        runCurrent()
        assertEquals("New", state.activeWorkout?.name)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun newerNavigationWinsOverSuspendedReload() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("w1", "Current", 1_000L)
            details = workoutDetailsFixture(name = "Current")
        }
        val state = state(repository)
        runCurrent()

        var calls = 0
        val gate = CompletableDeferred<Unit>()
        repository.activeWorkoutGate = {
            calls++
            if (calls == 1) gate.await()
        }
        val suspended = async { state.refreshProjection() }
        runCurrent()

        repository.active = WorkoutEntity("w2", "Later", 2_000L)
        repository.details = workoutDetailsFixture(name = "Later")
        state.showExercises()
        // One sole materialization epoch must drive the latest load on its
        // own: no manual refresh and no second epoch may be requested here.
        repository.projectionEpochs.value = 1L
        repository.activeWorkoutGate = null
        runCurrent()
        assertEquals("sole epoch must refresh the pill without extra trigger", "w2", state.activeWorkout?.id)

        gate.complete(Unit)
        suspended.await()
        runCurrent()

        assertEquals(Destination.Exercises, state.destination)
        assertEquals("w2", state.activeWorkout?.id)
    }

    private fun kotlinx.coroutines.test.TestScope.state(
        repository: FakeGymAppStateRepository,
        clock: GymAppClock = VirtualClock(testScheduler),
        hardware: RestTimerHardware = FakeTimerHardware(),
    ) = GymAppState(repository, backgroundScope, clock, hardware)

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
