package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.testing.FakeGymAppStateRepository
import com.dragosfotache.gymtracka.testing.FakeRoutineRepository
import com.dragosfotache.gymtracka.testing.customExerciseDetailsFixture
import com.dragosfotache.gymtracka.testing.routineDetailsFixture
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import com.dragosfotache.gymtracka.ui.routines.RoutineDetailState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks active-root replacement together with unrelated nested-target removal in one projection
 * refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MixedProjectionRefreshTest {
    private companion object {
        const val ACCOUNT = "c1200001-1111-4111-8111-111111111111"
        const val GENERATION = 5L
        const val CUSTOM_ID = "custom"
        const val ROUTINE_ID = "routine"
    }

    private class EpochRoutineRepo(val epochs: MutableStateFlow<Long>) : FakeRoutineRepository() {
        override fun observeProjectionEpoch(): Flow<Long> = epochs
    }

    private fun appRepository(epochs: MutableStateFlow<Long>) = FakeGymAppStateRepository().apply {
        projectionEpochs = epochs
        active = WorkoutEntity("w-old", "Old", 1_000L)
        details = workoutDetailsFixture(name = "Old")
    }

    private fun kotlinx.coroutines.test.TestScope.appState(
        repository: FakeGymAppStateRepository,
    ) = GymAppState(
        repository,
        backgroundScope,
        VirtualClock(testScheduler),
        FakeTimerHardware(),
        session = RepositorySession(ACCOUNT, GENERATION),
    )

    @Test
    fun activeChangePlusDeletedHistoryReconcilesBoth() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = appRepository(epochs)
        val state = appState(repository)
        runCurrent()
        state.showWorkoutHistory("h-gone")
        runCurrent()
        assertEquals(Destination.WorkoutHistoryDetail, state.destination)

        repository.active = WorkoutEntity("w-new", "New", 2_000L)
        repository.details = null
        epochs.value = 1L
        runCurrent()

        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals(Destination.History, state.destination)
        assertNull(state.finishedWorkoutSummary)
        assertNull(state.activeRestTimer)
        assertTrue(repository.finished.isEmpty())
        assertTrue(repository.discarded.isEmpty())
        assertFalse(state.refreshFailed)
    }

    @Test
    fun activeChangePlusDeletedRoutineRetiresSelectionToWorkout() = runTest {
        val epochs = MutableStateFlow(0L)
        val appRepository = appRepository(epochs)
        val appState = appState(appRepository)
        val routineRepository = EpochRoutineRepo(epochs).apply {
            detailsProvider = { routineDetailsFixture(name = "Plan") }
        }
        appRepository.routineDetailsResult = routineDetailsFixture(name = "Plan")
        appState.showRoutineDetails(ROUTINE_ID)
        runCurrent()
        val detail = RoutineDetailState(ROUTINE_ID, routineRepository, backgroundScope)
        runCurrent()
        assertEquals(ROUTINE_ID, appState.selectedRoutineId)
        assertEquals("Plan", detail.details?.routine?.name)

        appRepository.active = WorkoutEntity("w-new", "New", 2_000L)
        appRepository.details = workoutDetailsFixture(name = "New")
        appRepository.routineDetailsResult = null
        routineRepository.detailsProvider = { null }
        epochs.value = 1L
        runCurrent()

        assertEquals("w-new", appState.activeWorkout?.id)
        assertNull("selected routine ID must retire", appState.selectedRoutineId)
        assertEquals(Destination.Workout, appState.destination)
        assertNull("absent row reads clean, no failure diagnostic", detail.details)
        assertNull(appState.activeRestTimer)
        assertTrue(appRepository.finished.isEmpty())
    }

    @Test
    fun activeChangePlusDeletedCustomRetiresSelectionToExercises() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = appRepository(epochs)
        val state = appState(repository)
        repository.exerciseDetailsResult = customExerciseDetailsFixture("Loaded")
        state.showExerciseDetails(CUSTOM_ID)
        runCurrent()
        assertEquals(CUSTOM_ID, state.selectedExerciseId)

        repository.active = WorkoutEntity("w-new", "New", 2_000L)
        repository.details = workoutDetailsFixture(name = "New")
        repository.exerciseDetailsResult = null
        epochs.value = 1L
        runCurrent()

        assertEquals("w-new", state.activeWorkout?.id)
        assertNull("selected custom ID must retire", state.selectedExerciseId)
        assertEquals(Destination.Exercises, state.destination)
        assertNull(state.activeRestTimer)
        assertTrue(repository.finished.isEmpty())
        assertTrue(repository.discarded.isEmpty())
    }

    @Test
    fun activeChangePlusDeletedCustomEditorKeepsEditingAndFlags() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = appRepository(epochs)
        val state = appState(repository)
        repository.exerciseDetailsResult = customExerciseDetailsFixture("Loaded")
        state.editCustomExercise(CUSTOM_ID)
        runCurrent()
        assertEquals(Destination.EditCustomExercise, state.destination)
        assertEquals(CUSTOM_ID, state.editingCustomExerciseId)

        repository.active = WorkoutEntity("w-new", "New", 2_000L)
        repository.details = workoutDetailsFixture(name = "New")
        repository.exerciseDetailsResult = null
        epochs.value = 1L
        runCurrent()

        assertEquals("w-new", state.activeWorkout?.id)
        assertEquals(Destination.EditCustomExercise, state.destination)
        assertEquals("same editing selection must survive", CUSTOM_ID, state.editingCustomExerciseId)
        assertTrue("app must flag the failed refresh", state.refreshFailed)
        assertNull(state.activeRestTimer)
        assertNull(state.finishedWorkoutSummary)
        assertTrue(repository.finished.isEmpty())
        assertTrue(repository.discarded.isEmpty())
        assertTrue(repository.startedEmptyAt.isEmpty())
        assertTrue(repository.restUpdates.isEmpty())
    }

    @Test
    fun activeChangePlusDeletedRoutineEditorExitsToWorkoutWithoutSave() = runTest {
        val epochs = MutableStateFlow(0L)
        val appRepository = appRepository(epochs)
        val appState = appState(appRepository)
        val routineRepository = EpochRoutineRepo(epochs).apply {
            detailsProvider = { routineDetailsFixture(name = "Plan") }
        }
        appRepository.routineDetailsResult = routineDetailsFixture(name = "Plan")
        appState.editRoutine(ROUTINE_ID)
        runCurrent()
        val detail = RoutineDetailState(ROUTINE_ID, routineRepository, backgroundScope)
        runCurrent()
        assertEquals("Plan", detail.details?.routine?.name)
        val savesBefore = routineRepository.savedNames.size

        appRepository.active = WorkoutEntity("w-new", "New", 2_000L)
        appRepository.details = workoutDetailsFixture(name = "New")
        appRepository.routineDetailsResult = null
        routineRepository.detailsProvider = { null }
        epochs.value = 1L
        runCurrent()

        assertEquals("w-new", appState.activeWorkout?.id)
        assertNull("editing routine ID must retire", appState.editingRoutineId)
        assertEquals(Destination.Workout, appState.destination)
        assertEquals("no additional save on disappearance", savesBefore, routineRepository.savedNames.size)
        assertNull("missing leaf model cleared", detail.details)
        assertNull(appState.activeRestTimer)
    }

    @Test
    fun staleNavigationWinsOverMixedPulse() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = appRepository(epochs)
        val state = appState(repository)
        runCurrent()

        repository.active = WorkoutEntity("w-new", "New", 2_000L)
        repository.details = workoutDetailsFixture(name = "New")
        state.showExercises()
        epochs.value = 1L
        runCurrent()

        assertEquals(Destination.Exercises, state.destination)
        assertEquals("w-new", state.activeWorkout?.id)
    }

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
