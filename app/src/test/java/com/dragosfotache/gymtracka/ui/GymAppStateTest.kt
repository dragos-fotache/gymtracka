package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.testing.FakeGymAppStateRepository
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GymAppStateTest {
    @Test
    fun discoversActiveWorkoutAndKeepsNavigationTransitionsInMemory() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("active", "Current", 500L)
        }
        val state = state(repository)
        runCurrent()

        assertEquals(repository.active, state.activeWorkout)
        assertEquals(Destination.Workout, state.destination)
        state.resumeWorkout()
        assertEquals(Destination.ActiveWorkout, state.destination)
        state.showExercises()
        assertEquals(Destination.Exercises, state.destination)
        state.showHistory()
        assertEquals(Destination.History, state.destination)
        state.showWorkoutHistory("history")
        assertEquals(Destination.WorkoutHistoryDetail, state.destination)
        state.closeWorkoutHistory()
        assertEquals(Destination.History, state.destination)
    }

    @Test
    fun emptyCloneAndRoutineStartsRequireConfirmationAndUseInjectedWallClock() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("old", "Old", 100L)
        }
        val clock = VirtualClock(testScheduler)
        val state = state(repository, clock)
        runCurrent()

        state.requestStartWorkout()
        assertTrue(state.showStartNewWorkoutDialog)
        state.confirmStartNewWorkout()
        runCurrent()
        assertEquals(listOf("old"), repository.discarded)
        assertEquals(listOf(clock.wallTimeMillis()), repository.startedEmptyAt)
        assertEquals("empty", state.activeWorkout?.id)
        assertEquals(Destination.ActiveWorkout, state.destination)

        state.requestPerformAgain("finished")
        assertTrue(state.showStartNewWorkoutDialog)
        state.dismissStartNewWorkoutDialog()
        state.requestPerformAgain("finished")
        state.confirmStartNewWorkout()
        runCurrent()
        assertEquals(listOf("finished" to clock.wallTimeMillis()), repository.performedAgainAt)
        assertEquals("clone", state.activeWorkout?.id)

        state.requestStartRoutine("routine")
        assertTrue(state.showStartNewWorkoutDialog)
        state.confirmStartNewWorkout()
        runCurrent()
        assertEquals(listOf("routine" to clock.wallTimeMillis()), repository.startedRoutineAt)
        assertEquals("routine-start", state.activeWorkout?.id)
        assertEquals(listOf("old", "empty", "clone"), repository.discarded)
    }

    @Test
    fun finishSuccessAndFailureKeepTheExpectedWorkoutState() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("active", "Lift", 1_000L)
            details = workoutDetailsFixture()
            finishError = IllegalStateException("finish failed")
        }
        val clock = VirtualClock(testScheduler)
        val state = state(repository, clock)
        runCurrent()

        state.requestFinishWorkout()
        runCurrent()
        assertTrue(state.showFinishDialog)
        state.confirmFinishWorkout()
        runCurrent()
        assertEquals("active", state.activeWorkout?.id)
        assertTrue(state.showFinishDialog)
        assertNull(state.finishedWorkoutSummary)

        repository.finishError = null
        advanceTimeBy(1_200L)
        state.confirmFinishWorkout()
        runCurrent()
        assertEquals(listOf("active" to clock.wallTimeMillis()), repository.finished)
        assertNull(state.activeWorkout)
        assertEquals(Destination.Workout, state.destination)
        assertEquals("Lift", state.finishedWorkoutSummary?.name)

    }

    @Test
    fun discardFailureKeepsWorkoutAndDialogThenSuccessClearsIt() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("discard-me", "Discard", 2_000L)
            discardError = IllegalStateException("delete failed")
        }
        val state = state(repository)
        runCurrent()
        state.requestDiscardWorkout()
        state.confirmDiscardWorkout()
        runCurrent()
        assertEquals("discard-me", state.activeWorkout?.id)
        assertTrue(state.showDiscardDialog)

        repository.discardError = null
        state.confirmDiscardWorkout()
        runCurrent()
        assertNull(state.activeWorkout)
        assertEquals(Destination.Workout, state.destination)
    }

    @Test
    fun finishWithoutCompletedSetsAndFinishRejectionKeepWorkoutActive() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("active", "Lift", 1_000L)
            completedSets = false
        }
        val state = state(repository)
        runCurrent()

        state.requestFinishWorkout()
        runCurrent()
        assertTrue(state.showNoCompletedSetsDialog)
        assertFalse(state.showFinishDialog)
        assertTrue(repository.finished.isEmpty())

        repository.completedSets = true
        repository.finishResult = false
        state.requestFinishWorkout()
        runCurrent()
        state.confirmFinishWorkout()
        runCurrent()
        assertEquals("active", state.activeWorkout?.id)
        assertTrue(state.showNoCompletedSetsDialog)
        assertFalse(state.showFinishDialog)
    }

    @Test
    fun startsWithoutExistingWorkoutDoNotAskForReplacement() = runTest {
        val emptyRepository = FakeGymAppStateRepository()
        val emptyState = state(emptyRepository)
        runCurrent()
        emptyState.requestStartWorkout()
        runCurrent()
        assertFalse(emptyState.showStartNewWorkoutDialog)
        assertEquals(1, emptyRepository.startedEmptyAt.size)

        val cloneRepository = FakeGymAppStateRepository()
        val cloneState = state(cloneRepository)
        runCurrent()
        cloneState.requestPerformAgain("finished")
        runCurrent()
        assertFalse(cloneState.showStartNewWorkoutDialog)
        assertEquals(listOf("finished" to VirtualClock(testScheduler).wallTimeMillis()), cloneRepository.performedAgainAt)

        val routineRepository = FakeGymAppStateRepository()
        val routineState = state(routineRepository)
        runCurrent()
        routineState.requestStartRoutine("routine")
        runCurrent()
        assertFalse(routineState.showStartNewWorkoutDialog)
        assertEquals(listOf("routine" to VirtualClock(testScheduler).wallTimeMillis()), routineRepository.startedRoutineAt)
    }

    @Test
    fun failedReplacementCreationOrDiscardPreservesWorkoutTimerAndConfirmation() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("current", "Current", 1_000L)
            startError = IllegalStateException("create failed")
        }
        val hardware = FakeTimerHardware()
        val state = state(repository, hardware = hardware)
        runCurrent()
        state.startRestTimer(WorkoutSetEntity("timer-set", "entry", 0), 30)
        state.requestStartWorkout()
        state.confirmStartNewWorkout()
        runCurrent()
        assertEquals("current", state.activeWorkout?.id)
        assertTrue(state.showStartNewWorkoutDialog)
        assertEquals("timer-set", state.activeRestTimer?.workoutSetId)
        assertTrue(hardware.isHeld)

        repository.startError = null
        repository.discardError = IllegalStateException("discard failed")
        state.confirmStartNewWorkout()
        runCurrent()
        assertEquals("current", state.activeWorkout?.id)
        assertTrue(state.showStartNewWorkoutDialog)
        assertEquals("timer-set", state.activeRestTimer?.workoutSetId)
        assertTrue(hardware.isHeld)
    }

    @Test
    fun timerExpirySignalsCuesPersistsWholeSecondsOnceAndReleasesBoundedLock() = runTest {
        val repository = FakeGymAppStateRepository()
        val clock = VirtualClock(testScheduler)
        val hardware = FakeTimerHardware()
        val state = state(repository, clock, hardware)
        val timer = WorkoutSetEntity("set", "entry", 0)

        state.startRestTimer(timer, 4)
        val activeTimer = state.activeRestTimer!!
        assertEquals(4, state.restTimerRemainingSeconds)
        assertEquals(listOf(5_000L), hardware.acquiredTimeouts)
        advanceTimeBy(4_000L)
        runCurrent()

        assertNull(state.activeRestTimer)
        assertEquals(listOf("set" to 4), repository.restUpdates)
        assertEquals(listOf(100L, 100L, 500L), hardware.vibrations)
        assertEquals(1, state.restTimerCompletionKey)
        assertEquals(1, hardware.releaseCount)
        state.skipRestTimer(activeTimer)
        runCurrent()
        assertEquals(1, repository.restUpdates.size)
    }

    @Test
    fun adjustSkipAndReplacementUseTimerOwnershipAndSnapshotElapsedAtActionTime() = runTest {
        val repository = FakeGymAppStateRepository()
        val clock = VirtualClock(testScheduler)
        val hardware = FakeTimerHardware()
        val state = state(repository, clock, hardware)
        state.startRestTimer(WorkoutSetEntity("first", "entry", 0), 20)
        val first = state.activeRestTimer!!
        state.adjustRestTimer(first, 15)
        assertEquals(35, state.activeRestTimer?.durationSeconds)
        assertEquals(1, hardware.releaseCount)
        assertEquals(listOf(21_000L, 36_000L), hardware.acquiredTimeouts)

        val persistenceGate = CompletableDeferred<Unit>()
        repository.restUpdate = { _, _ -> persistenceGate.await() }
        advanceTimeBy(750L)
        state.startRestTimer(WorkoutSetEntity("second", "entry", 1), 50)
        val second = state.activeRestTimer!!
        runCurrent()
        assertEquals(listOf("first" to 0), repository.restUpdates)
        assertEquals(listOf(21_000L, 36_000L, 51_000L), hardware.acquiredTimeouts)
        assertEquals(2, hardware.releaseCount)
        assertTrue(hardware.isHeld)

        state.skipRestTimer(first)
        state.adjustRestTimer(first, 60)
        assertEquals(second, state.activeRestTimer)
        assertEquals(50, state.activeRestTimer?.durationSeconds)
        advanceTimeBy(5_000L)
        persistenceGate.complete(Unit)
        runCurrent()
        assertEquals(2, hardware.releaseCount)
        assertTrue(hardware.isHeld)

        state.skipRestTimer(second)
        state.skipRestTimer(second)
        state.adjustRestTimer(second, 15)
        runCurrent()
        assertEquals(listOf("first" to 0, "second" to 5), repository.restUpdates)
        assertNull(state.activeRestTimer)
        assertEquals(3, hardware.releaseCount)
    }

    @Test
    fun replacementClaimIsConsumedWhenOldWriteStartsBeforeActiveTimerChanges() = runTest {
        val repository = FakeGymAppStateRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val state = GymAppState(repository, scope, VirtualClock(testScheduler), FakeTimerHardware())
        runCurrent()

        state.startRestTimer(WorkoutSetEntity("old", "entry", 0), 30)
        val oldTimer = state.activeRestTimer!!
        state.startRestTimer(WorkoutSetEntity("new", "entry", 1), 30)
        val newTimer = state.activeRestTimer!!
        assertEquals(listOf("old" to 0), repository.restUpdates)
        state.skipRestTimer(oldTimer)
        state.adjustRestTimer(oldTimer, 15)
        assertEquals(newTimer, state.activeRestTimer)
        assertEquals(1, repository.restUpdates.size)
        scope.cancel()
    }

    @Test
    fun oldExpiryWriteCompletesAfterNewTimerStartsWithoutLosingItsWakeLock() = runTest {
        val repository = FakeGymAppStateRepository()
        val hardware = FakeTimerHardware()
        val state = state(repository, hardware = hardware)
        val oldWriteGate = CompletableDeferred<Unit>()
        repository.restUpdate = { setId, _ ->
            if (setId == "expired") oldWriteGate.await()
        }

        state.startRestTimer(WorkoutSetEntity("expired", "entry", 0), 1)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("expired" to 1), repository.restUpdates)
        assertTrue(repository.committedRestUpdates.isEmpty())
        assertNull(state.activeRestTimer)

        state.startRestTimer(WorkoutSetEntity("current", "entry", 1), 30)
        val newTimer = state.activeRestTimer
        assertTrue(hardware.isHeld)
        oldWriteGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("expired" to 1), repository.committedRestUpdates)
        assertEquals(newTimer, state.activeRestTimer)
        assertTrue(hardware.isHeld)
        assertEquals(1, hardware.releaseCount)

        state.skipRestTimer(newTimer!!)
        runCurrent()
        assertEquals(listOf("expired" to 1, "current" to 0), repository.committedRestUpdates)
    }

    @Test
    fun zeroAdjustmentAndOffDurationDoNotAcquireOrDuplicateTimerWork() = runTest {
        val repository = FakeGymAppStateRepository()
        val hardware = FakeTimerHardware()
        val state = state(repository, hardware = hardware)

        state.startRestTimer(WorkoutSetEntity("off", "entry", 0), 0)
        assertNull(state.activeRestTimer)
        assertTrue(hardware.acquiredTimeouts.isEmpty())
        runCurrent()
        assertTrue(repository.restUpdates.isEmpty())

        state.startRestTimer(WorkoutSetEntity("zero", "entry", 0), 10)
        val timer = state.activeRestTimer!!
        state.adjustRestTimer(timer, -15)
        assertEquals(0, state.activeRestTimer?.durationSeconds)
        runCurrent()
        assertNull(state.activeRestTimer)
        assertEquals(listOf("zero" to 0), repository.restUpdates)
        assertEquals(listOf(500L), hardware.vibrations)
        state.skipRestTimer(timer)
        state.adjustRestTimer(timer, 15)
        runCurrent()
        assertEquals(1, repository.restUpdates.size)
    }

    @Test
    fun failedRestPersistenceIsAttemptedOnceAndScopeCancellationReleasesLock() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            restUpdate = { _, _ -> throw IllegalStateException("write failed") }
        }
        val hardware = FakeTimerHardware()
        val state = state(repository, hardware = hardware)
        state.startRestTimer(WorkoutSetEntity("failed", "entry", 0), 1)
        val failedTimer = state.activeRestTimer!!
        advanceTimeBy(1_000L)
        runCurrent()
        state.skipRestTimer(failedTimer)
        runCurrent()
        assertEquals(1, repository.restUpdates.size)
        assertTrue(repository.committedRestUpdates.isEmpty())
        assertEquals(0, state.restTimerCompletionKey)
        assertNull(state.activeRestTimer)
        assertFalse(hardware.isHeld)

        val cancelledRepository = FakeGymAppStateRepository()
        val cancelledHardware = FakeTimerHardware()
        val ownedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val cancelledState = GymAppState(cancelledRepository, ownedScope, VirtualClock(testScheduler), cancelledHardware)
        runCurrent()
        cancelledState.startRestTimer(WorkoutSetEntity("cancelled", "entry", 0), 30)
        ownedScope.cancel()
        runCurrent()
        assertNull(cancelledState.activeRestTimer)
        assertFalse(cancelledHardware.isHeld)
        assertEquals(1, cancelledHardware.releaseCount)
    }

    @Test
    fun finishingTimerPersistsElapsedSecondsAndReleasesWakeLock() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("workout", "Lift", 1_000L)
            details = workoutDetailsFixture()
        }
        val clock = VirtualClock(testScheduler)
        val hardware = FakeTimerHardware()
        val state = state(repository, clock, hardware)
        runCurrent()
        state.startRestTimer(WorkoutSetEntity("finish-set", "entry", 0), 30)
        advanceTimeBy(1_500L)
        state.requestFinishWorkout()
        runCurrent()
        state.confirmFinishWorkout()
        runCurrent()
        assertEquals(listOf("finish-set" to 1), repository.restUpdates)
        assertNull(state.activeRestTimer)
        assertEquals(1, hardware.releaseCount)
        assertNull(state.activeWorkout)

    }

    @Test
    fun discardInvalidatesTimerHandlesAndReleasesWakeLockWithoutRestWrite() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("discard", "Discard", 2_000L)
        }
        val hardware = FakeTimerHardware()
        val state = state(repository, hardware = hardware)
        runCurrent()
        state.startRestTimer(WorkoutSetEntity("discard-set", "entry", 0), 30)
        val stale = state.activeRestTimer!!
        state.requestDiscardWorkout()
        state.confirmDiscardWorkout()
        runCurrent()
        state.skipRestTimer(stale)
        state.adjustRestTimer(stale, 10)
        runCurrent()
        assertNull(state.activeRestTimer)
        assertTrue(repository.restUpdates.none { it.first == "discard-set" })
        assertEquals(1, hardware.releaseCount)
    }

    @Test
    fun compositionDisposalInvalidatesTimerHandlesAndReleasesWakeLock() = runTest {
        val repository = FakeGymAppStateRepository()
        val hardware = FakeTimerHardware()
        val state = state(repository, hardware = hardware)
        runCurrent()
        state.startRestTimer(WorkoutSetEntity("dispose-set", "entry", 0), 30)
        val stale = state.activeRestTimer!!
        state.dispose()
        state.skipRestTimer(stale)
        state.adjustRestTimer(stale, 10)
        runCurrent()
        assertNull(state.activeRestTimer)
        assertTrue(repository.restUpdates.none { it.first == "dispose-set" })
        assertEquals(1, hardware.releaseCount)
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
        val vibrations = mutableListOf<Long>()
        val acquiredTimeouts = mutableListOf<Long>()
        var releaseCount = 0
        var isHeld = false
        override fun vibrate(durationMillis: Long) {
            vibrations += durationMillis
        }
        override fun acquireWakeLock(timeoutMillis: Long) {
            acquiredTimeouts += timeoutMillis
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
