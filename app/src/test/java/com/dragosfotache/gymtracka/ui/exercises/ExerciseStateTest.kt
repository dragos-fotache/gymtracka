package com.dragosfotache.gymtracka.ui.exercises

import com.dragosfotache.gymtracka.testing.FakeExerciseRepository
import com.dragosfotache.gymtracka.testing.customExerciseDetailsFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseStateTest {
    @Test
    fun catalogueState_restoresSearchAndFiltersFromInitialValues() = runTest {
        val state = ExerciseCatalogueState(
            repository = FakeExerciseRepository(),
            scope = this,
            initialSearchQuery = "bench",
            initialEquipmentIds = setOf("barbell"),
            initialMuscleGroupIds = setOf("chest"),
        )

        assertEquals("bench", state.searchQuery)
        assertEquals(setOf("barbell"), state.selectedEquipmentIds)
        assertEquals(setOf("chest"), state.selectedMuscleGroupIds)
    }

    @Test
    fun catalogueSelection_isClearedOnlyAfterSuccessfulPersistence() = runTest {
        val repository = FakeExerciseRepository()
        val state = ExerciseCatalogueState(repository, this)
        runCurrent()
        state.toggleExercise("exercise-1")
        state.toggleExercise("exercise-2")
        var added = false

        state.addSelectedExercises("workout") { added = true }
        advanceUntilIdle()

        assertEquals(
            listOf("exercise-1", "exercise-2"),
            repository.addedToWorkout.single().second.toList(),
        )
        assertTrue(added)
        assertTrue(state.selectedExerciseIds.isEmpty())
    }

    @Test
    fun creationValidation_normalizesPrimaryAndWarnsBeforeEmptySave() = runTest {
        val repository = FakeExerciseRepository()
        val state = ExerciseCreationState(null, repository, this)
        runCurrent()

        state.requestSave {}
        assertTrue(state.showNameRequiredDialog)
        state.dismissNameRequiredDialog()

        state.updateSecondaryMuscleGroups(setOf("chest", "triceps"))
        state.updatePrimaryMuscleGroup("chest")
        state.updateName("  My Exercise  ")
        var saved = false
        state.requestSave { saved = true }
        advanceUntilIdle()

        assertEquals(setOf("triceps"), state.secondaryMuscleGroupIds)
        assertEquals(listOf("My Exercise"), repository.createdNames)
        assertTrue(saved)
        assertTrue(state.shouldWarnOnBack)
    }

    @Test
    fun delayedEditLoad_doesNotOverwriteUserInput() = runTest {
        val deferredDetails = CompletableDeferred<com.dragosfotache.gymtracka.data.gym.ExerciseDetails?>()
        val repository = FakeExerciseRepository().apply {
            detailsProvider = { deferredDetails.await() }
        }
        val state = ExerciseCreationState("custom", repository, this)
        runCurrent()

        state.updateName("Typed while loading")
        deferredDetails.complete(customExerciseDetailsFixture("Stale loaded name"))
        advanceUntilIdle()

        assertEquals("Typed while loading", state.name)
        assertTrue(state.shouldWarnOnBack)
        assertFalse(state.showNameRequiredDialog)
    }
}
