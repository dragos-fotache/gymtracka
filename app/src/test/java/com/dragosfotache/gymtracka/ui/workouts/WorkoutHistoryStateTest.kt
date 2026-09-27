package com.dragosfotache.gymtracka.ui.workouts

import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.testing.FakeWorkoutHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutHistoryStateTest {
    private val workout = WorkoutEntity(
        id = "workout",
        name = "Finished Workout",
        startedAtMillis = 1_000L,
        finishedAtMillis = 2_000L,
        status = "finished",
    )

    @Test
    fun saveAsRoutine_handlesSuccessMissingSourceAndFailure() = runTest {
        val repository = FakeWorkoutHistoryRepository().apply {
            saveResult = RoutineEntity("routine", "Saved")
        }
        val state = WorkoutHistoryState(repository, this)
        runCurrent()

        state.requestSaveAsRoutine(workout)
        state.updateRoutineName("  Saved  ")
        state.confirmSaveAsRoutine()
        advanceUntilIdle()

        assertEquals(listOf("workout" to "Saved"), repository.saveRequests)
        assertEquals("Saved", state.savedRoutineName)
        assertNull(state.workoutToSaveAsRoutine)
        assertFalse(state.isSavingRoutine)

        state.dismissSavedRoutineConfirmation()
        repository.saveResult = null
        state.requestSaveAsRoutine(workout)
        state.confirmSaveAsRoutine()
        advanceUntilIdle()
        assertEquals("This workout is no longer available to save.", state.saveRoutineError)

        repository.saveError = IllegalStateException("boom")
        state.confirmSaveAsRoutine()
        advanceUntilIdle()
        assertEquals("Couldn't save the routine. Try again.", state.saveRoutineError)
        assertFalse(state.isSavingRoutine)
    }

    @Test
    fun deleteRefreshesHistoryAfterPersistence() = runTest {
        val repository = FakeWorkoutHistoryRepository()
        val state = WorkoutHistoryState(repository, this)
        runCurrent()

        state.requestDelete(workout)
        state.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf("workout"), repository.deletedWorkoutIds)
        assertEquals(emptyList<Any>(), state.workouts)
        assertNull(state.workoutToDelete)
    }
}
