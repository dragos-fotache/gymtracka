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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        state.showSettings()
        assertEquals(Destination.Settings, state.destination)
        state.showHistory()
        assertEquals(Destination.History, state.destination)
        state.showWorkoutHistory("history")
        assertEquals(Destination.WorkoutHistoryDetail, state.destination)
        state.closeWorkoutHistory()
        assertEquals(Destination.History, state.destination)
    }

    @Test
    fun settingsNavigationKeepsTimerOwnershipAndPersistsTerminalRestExactlyOnce() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("active", "Current", 500L)
        }
        val hardware = FakeTimerHardware()
        val state = state(repository, hardware = hardware)
        runCurrent()
        state.startRestTimer(WorkoutSetEntity("set", "entry", 0), 10)
        runCurrent()
        val timer = state.activeRestTimer!!

        state.showSettings()
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(Destination.Settings, state.destination)
        assertEquals(timer, state.activeRestTimer)
        assertEquals(8, state.restTimerRemainingSeconds)
        state.showExercises()
        state.showHistory()
        state.showWorkouts()
        state.showSettings()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(timer, state.activeRestTimer)
        assertEquals("active", state.activeWorkout?.id)
        assertTrue(hardware.isHeld)
        assertEquals(1, hardware.acquiredTimeouts.size)
        assertEquals(0, hardware.releaseCount)
        assertTrue(repository.restUpdates.isEmpty())

        state.skipRestTimer(timer)
        state.skipRestTimer(timer)
        runCurrent()
        assertEquals(listOf("set" to 3), repository.restUpdates)
        assertNull(state.activeRestTimer)
        assertFalse(hardware.isHeld)
        assertEquals(1, hardware.releaseCount)
        assertEquals(Destination.Settings, state.destination)
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
        assertTrue(repository.discarded.isEmpty())
        assertEquals(listOf(clock.wallTimeMillis()), repository.startedEmptyAt)
        assertEquals("empty", state.activeWorkout?.id)
        assertEquals(Destination.ActiveWorkout, state.destination)
        assertFalse(state.showStartNewWorkoutDialog)

        state.requestPerformAgain("finished")
        assertTrue(state.showStartNewWorkoutDialog)
        state.dismissStartNewWorkoutDialog()
        state.requestPerformAgain("finished")
        state.confirmStartNewWorkout()
        runCurrent()
        assertTrue(repository.discarded.isEmpty())
        assertEquals(listOf("finished" to clock.wallTimeMillis()), repository.performedAgainAt)
        assertEquals("clone", state.activeWorkout?.id)

        state.requestStartRoutine("routine")
        assertTrue(state.showStartNewWorkoutDialog)
        state.confirmStartNewWorkout()
        runCurrent()
        assertTrue(repository.discarded.isEmpty())
        assertEquals(listOf("routine" to clock.wallTimeMillis()), repository.startedRoutineAt)
        assertEquals("routine-start", state.activeWorkout?.id)
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
        advanceTimeBy(1_200.milliseconds)
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
    fun failedStartKeepsWorkoutTimerAndConfirmationWithoutDiscard() = runTest {
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
        assertTrue(repository.discarded.isEmpty())

        // An invalid source falls back to a fresh empty workout instead.
        repository.startError = null
        repository.performAgainResult = null
        state.requestPerformAgain("missing-source")
        state.confirmStartNewWorkout()
        runCurrent()
        assertEquals(listOf("missing-source" to VirtualClock(testScheduler).wallTimeMillis()), repository.performedAgainAt)
        assertEquals(1, repository.startedEmptyAt.size)
        assertEquals("empty", state.activeWorkout?.id)
        assertFalse(state.showStartNewWorkoutDialog)
        assertTrue(repository.discarded.isEmpty())
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
        advanceTimeBy(4.seconds)
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
        advanceTimeBy(750.milliseconds)
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
        advanceTimeBy(5.seconds)
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
        advanceTimeBy(1.seconds)
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
        advanceTimeBy(1.seconds)
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
        advanceTimeBy(1_500.milliseconds)
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

    @Test
    fun finishRequestWaitsForRegisteredFeatureDrain() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("active", "Lift", 1_000L)
        }
        val state = state(repository)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        var drained = false
        val owner = Any()
        state.registerFinishDrain("active", owner) {
            gate.await()
            drained = true
        }

        state.requestFinishWorkout()
        runCurrent()
        assertFalse(drained)
        assertFalse(state.showFinishDialog)
        assertFalse(state.showNoCompletedSetsDialog)

        gate.complete(Unit)
        runCurrent()
        assertTrue(drained)
        assertTrue(state.showFinishDialog)
    }

    @Test
    fun failedFeatureDrainNeverTriggersFinish() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("active", "Lift", 1_000L)
        }
        val state = state(repository)
        runCurrent()
        val owner = Any()
        state.registerFinishDrain("active", owner) {
            throw IllegalStateException("accepted edit failed")
        }

        state.requestFinishWorkout()
        runCurrent()
        assertFalse(state.showFinishDialog)
        assertFalse(state.showNoCompletedSetsDialog)
        assertTrue(repository.finished.isEmpty())

        state.unregisterFinishDrain("active", Any())
        state.requestFinishWorkout()
        runCurrent()
        assertFalse(state.showFinishDialog)

        state.unregisterFinishDrain("active", owner)
        state.requestFinishWorkout()
        runCurrent()
        assertTrue(state.showFinishDialog)

        // A drain registered for another workout never runs here.
        state.registerFinishDrain("other", owner) {
            fail("Drain for another workout must not run")
        }
        state.confirmFinishWorkout()
        runCurrent()
        assertEquals(1, repository.finished.size)
    }

    @Test
    fun confirmFinishDrainsLateAcceptedEdits() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            active = WorkoutEntity("active", "Lift", 1_000L)
        }
        val state = state(repository)
        runCurrent()
        val owner = Any()
        var releases = 0
        state.registerFinishDrain("active", owner) {
            if (releases++ == 0) return@registerFinishDrain
        }

        state.requestFinishWorkout()
        runCurrent()
        assertTrue(state.showFinishDialog)

        val lateGate = CompletableDeferred<Unit>()
        state.registerFinishDrain("active", owner) { lateGate.await() }
        state.confirmFinishWorkout()
        runCurrent()
        assertTrue(repository.finished.isEmpty())
        assertTrue(state.showFinishDialog)

        lateGate.complete(Unit)
        runCurrent()
        assertEquals(1, repository.finished.size)
        assertNull(state.activeWorkout)
    }

    @Test
    fun repeatedTerminalWritesToOneSetCommitInClaimOrder() = runTest {
        val repository = FakeGymAppStateRepository()
        val clock = VirtualClock(testScheduler)
        val state = state(repository, clock)
        val firstWriteGate = CompletableDeferred<Unit>()
        repository.restUpdate = { setId, _ ->
            if (setId == "same-set") firstWriteGate.await()
        }

        state.startRestTimer(WorkoutSetEntity("same-set", "entry", 0), 30)
        advanceTimeBy(2.seconds)
        state.startRestTimer(WorkoutSetEntity("same-set", "entry", 0), 30)
        val second = state.activeRestTimer!!
        runCurrent()
        // The replacement countdown starts immediately while the old write waits.
        assertEquals("same-set", second.workoutSetId)
        assertEquals(listOf("same-set" to 2), repository.restUpdates)
        assertTrue(repository.committedRestUpdates.isEmpty())

        advanceTimeBy(5.seconds)
        state.skipRestTimer(second)
        runCurrent()
        // The newer write is queued behind the gated older one: still nothing committed.
        assertEquals(listOf("same-set" to 2), repository.restUpdates)
        assertTrue(repository.committedRestUpdates.isEmpty())

        firstWriteGate.complete(Unit)
        runCurrent()
        // Both transitions commit exactly once, oldest first, so the final
        // elapsed value comes from the newer transition.
        assertEquals(
            listOf("same-set" to 2, "same-set" to 5),
            repository.committedRestUpdates,
        )
        assertEquals(2, state.restTimerCompletionKey)
        assertNull(state.activeRestTimer)
    }

    @Test
    fun confirmFinishWaitsForGatedTerminalWrite() = runTest {
        // Replacement leaves a current timer; skip/expiry leave no active
        // timer. In all three, finish must stay blocked until the earlier
        // gated terminal write commits, then commit everything exactly once.
        listOf("replacement", "skip", "expiry").forEach { scenario ->
            val repository = FakeGymAppStateRepository().apply {
                active = WorkoutEntity("active", "Lift", 1_000L)
                details = workoutDetailsFixture()
            }
            val clock = VirtualClock(testScheduler)
            val state = state(repository, clock)
            runCurrent()
            val writeGate = CompletableDeferred<Unit>()
            repository.restUpdate = { _, _ -> writeGate.await() }

            state.startRestTimer(WorkoutSetEntity("old-set", "entry", 0), 30)
            advanceTimeBy(1.seconds)
            var expectedAttempts = listOf("old-set" to 1)
            var expectedCommits = listOf("old-set" to 1)
            when (scenario) {
                "replacement" -> {
                    state.startRestTimer(WorkoutSetEntity("new-set", "entry", 1), 30)
                    runCurrent()
                    assertEquals("new-set", state.activeRestTimer?.workoutSetId)
                    expectedAttempts = listOf("old-set" to 1)
                    expectedCommits = listOf("old-set" to 1, "new-set" to 1)
                }

                "skip" -> {
                    state.skipRestTimer(state.activeRestTimer!!)
                    runCurrent()
                    assertNull(state.activeRestTimer)
                }

                "expiry" -> {
                    // Skip the 30s timer, then let a fresh 1s timer expire
                    // while gated: the skip attempt is recorded, the expiry
                    // write queues behind it on the serial queue.
                    state.skipRestTimer(state.activeRestTimer!!)
                    runCurrent()
                    state.startRestTimer(WorkoutSetEntity("old-set", "entry", 0), 1)
                    advanceTimeBy(1.seconds)
                    runCurrent()
                    assertNull(state.activeRestTimer)
                    expectedCommits = listOf("old-set" to 1, "old-set" to 1)
                }
            }
            runCurrent()
            assertEquals(expectedAttempts, repository.restUpdates)
            assertTrue(repository.committedRestUpdates.isEmpty())

            state.requestFinishWorkout()
            runCurrent()
            // Normative request drain waits for accepted terminal writes.
            assertFalse(state.showFinishDialog)
            assertTrue(repository.finished.isEmpty())
            assertEquals(expectedAttempts, repository.restUpdates)
            assertTrue(repository.committedRestUpdates.isEmpty())

            advanceTimeBy(1.seconds)
            val confirm = state.confirmFinishWorkout()
            assertNotNull(confirm)
            runCurrent()
            assertTrue(repository.finished.isEmpty())
            assertEquals(expectedAttempts, repository.restUpdates)
            assertTrue(repository.committedRestUpdates.isEmpty())
            assertFalse(state.showFinishDialog)

            writeGate.complete(Unit)
            runCurrent()
            confirm?.join()
            runCurrent()
            assertEquals(expectedCommits, repository.committedRestUpdates)
            assertEquals(1, repository.finished.size)
            assertNull(state.activeWorkout)
            assertFalse(state.showFinishDialog)
        }
    }

    @Test
    fun disposalCancelsGatedTerminalWritesWithoutLaterCommit() = runTest {
        val repository = FakeGymAppStateRepository()
        val state = state(repository)
        val writeGate = CompletableDeferred<Unit>()
        repository.restUpdate = { _, _ -> writeGate.await() }

        state.startRestTimer(WorkoutSetEntity("old-set", "entry", 0), 30)
        advanceTimeBy(1.seconds)
        state.startRestTimer(WorkoutSetEntity("new-set", "entry", 1), 30)
        runCurrent()
        // Replacement never cancels the older terminal write: it is pending.
        assertEquals(listOf("old-set" to 1), repository.restUpdates)
        assertEquals("new-set", state.activeRestTimer?.workoutSetId)

        state.dispose()
        writeGate.complete(Unit)
        runCurrent()
        assertTrue(repository.committedRestUpdates.isEmpty())
        assertNull(state.activeRestTimer)

        // disposed admission must refuse: no new timer and no rest write.
        state.startRestTimer(WorkoutSetEntity("fresh-set", "entry", 2), 30)
        runCurrent()
        assertNull(state.activeRestTimer)
        assertTrue(repository.restUpdates.none { it.first == "fresh-set" })

        // Dispose must not cancel the injected parent scope.
        val parentProbe = CompletableDeferred<Unit>()
        backgroundScope.launch { parentProbe.complete(Unit) }
        runCurrent()
        assertTrue(parentProbe.isCompleted)
    }

    @Test
    fun uncheckingEndsOnlyTheMatchingTimerWithoutRestWrite() = runTest {
        val repository = FakeGymAppStateRepository()
        val hardware = FakeTimerHardware()
        val state = state(repository, hardware = hardware)
        state.startRestTimer(WorkoutSetEntity("timed-set", "entry", 0), 30)

        state.cancelRestTimerForSet("other-set")
        assertEquals("timed-set", state.activeRestTimer?.workoutSetId)
        assertTrue(hardware.isHeld)

        state.cancelRestTimerForSet("timed-set")
        runCurrent()
        assertNull(state.activeRestTimer)
        assertFalse(hardware.isHeld)
        assertTrue(repository.restUpdates.isEmpty())
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
