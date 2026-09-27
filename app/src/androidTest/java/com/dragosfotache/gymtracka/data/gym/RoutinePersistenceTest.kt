package com.dragosfotache.gymtracka.data.gym

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutinePersistenceTest : GymDatabaseTest() {
    @Test
    fun draftLifecycle_staysHidden_andCancellationDeletesOnlyTheDraft() = runTest {
        val saved = routineFixture(id = "saved", name = "Saved")
        insertRoutineAggregate(saved)

        val draft = repository.createRoutineDraft()
        repository.updateRoutineName(draft.id, "Autosaved draft name")

        assertEquals(listOf(saved), repository.savedRoutines())
        assertEquals("Autosaved draft name", repository.routineDetails(draft.id)?.routine?.name)

        repository.deleteRoutine(draft.id)

        assertNull(repository.routineDetails(draft.id))
        assertEquals(saved, repository.routineDetails(saved.id)?.routine)
    }

    @Test
    fun saveRoutine_promotesDraftAndPersistsItsName() = runTest {
        val draft = repository.createRoutineDraft()

        repository.saveRoutine(draft.id, "Lower Body")

        val saved = repository.savedRoutines().single()
        assertEquals(draft.id, saved.id)
        assertEquals("Lower Body", saved.name)
        assertFalse(saved.isDraft)
    }

    @Test
    fun addExercises_appendsInOrderWithOneEmptySet_andDoesNotCopyWorkoutHistory() = runTest {
        insertExercises(
            exerciseFixture(id = "existing", name = "Existing"),
            exerciseFixture(id = "squat", name = "Squat"),
            exerciseFixture(id = "row", name = "Row"),
        )
        val history = workoutFixture(
            id = "history",
            finishedAtMillis = 2_000L,
            status = "finished",
        )
        val historicalExercise = workoutExerciseFixture(
            id = "history-squat",
            workoutId = history.id,
            exerciseId = "squat",
            restDurationSeconds = 90,
        )
        val historicalSet = workoutSetFixture(
            id = "history-set",
            workoutExerciseId = historicalExercise.id,
            repetitions = 5,
            weightKg = 100.0,
            isCompleted = true,
        )
        insertWorkoutAggregate(history, listOf(historicalExercise), listOf(historicalSet))

        val routine = routineFixture()
        val existing = routineExerciseFixture(
            id = "existing-entry",
            exerciseId = "existing",
        )
        val existingSet = routineSetFixture(
            id = "existing-set",
            routineExerciseId = existing.id,
        )
        insertRoutineAggregate(routine, listOf(existing), listOf(existingSet))

        repository.addExercisesToRoutine(routine.id, listOf("squat", "row"))

        val details = repository.routineDetails(routine.id)!!
        assertEquals(listOf("existing", "squat", "row"), details.exercises.map { it.routineExercise.exerciseId })
        assertEquals(listOf(0, 1, 2), details.exercises.map { it.routineExercise.position })
        details.exercises.drop(1).forEach { added ->
            assertEquals(30, added.routineExercise.restDurationSeconds)
            val set = added.sets.single()
            assertEquals(0, set.position)
            assertNull(set.repetitions)
            assertNull(set.weightKg)
        }
    }

    @Test
    fun addExercises_rollsBackAllChildren_whenAnyExerciseIsInvalid() = runTest {
        insertExercises(exerciseFixture(id = "valid"))
        val routine = routineFixture()
        insertRoutineAggregate(routine)

        var failure: Throwable? = null
        try {
            repository.addExercisesToRoutine(routine.id, listOf("valid", "missing"))
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(failure)
        assertTrue(database.routineDao().getRoutineExercises(routine.id).isEmpty())
    }

    @Test
    fun routineEdits_persistNameSetValuesAndRestDuration() = runTest {
        insertExercises(exerciseFixture())
        val routine = routineFixture()
        val exercise = routineExerciseFixture()
        val set = routineSetFixture(repetitions = null, weightKg = null)
        insertRoutineAggregate(routine, listOf(exercise), listOf(set))

        repository.updateRoutineName(routine.id, "Updated")
        repository.updateRoutineExerciseRestDuration(exercise.id, 75)
        repository.updateRoutineSet(set.copy(repetitions = 8, weightKg = 62.5))

        val details = repository.routineDetails(routine.id)!!
        assertEquals("Updated", details.routine.name)
        assertEquals(75, details.exercises.single().routineExercise.restDurationSeconds)
        assertEquals(8, details.exercises.single().sets.single().repetitions)
        assertEquals(62.5, details.exercises.single().sets.single().weightKg)
    }

    @Test
    fun deleteSet_renumbersRemainingRoutineSets() = runTest {
        insertExercises(exerciseFixture())
        val routine = routineFixture()
        val exercise = routineExerciseFixture()
        val first = routineSetFixture(id = "set-1", position = 0)
        val middle = routineSetFixture(id = "set-2", position = 1)
        val last = routineSetFixture(id = "set-3", position = 2)
        insertRoutineAggregate(routine, listOf(exercise), listOf(first, middle, last))

        repository.deleteRoutineSet(middle)

        assertEquals(
            listOf(first, last.copy(position = 1)),
            database.routineDao().getRoutineSets(exercise.id),
        )
    }

    @Test
    fun deleteExercise_cascadesSets_andRenumbersRemainingRoutineExercises() = runTest {
        insertExercises(
            exerciseFixture(id = "exercise-1"),
            exerciseFixture(id = "exercise-2", name = "Squat"),
            exerciseFixture(id = "exercise-3", name = "Row"),
        )
        val routine = routineFixture()
        val first = routineExerciseFixture(id = "entry-1", exerciseId = "exercise-1", position = 0)
        val middle = routineExerciseFixture(id = "entry-2", exerciseId = "exercise-2", position = 1)
        val last = routineExerciseFixture(id = "entry-3", exerciseId = "exercise-3", position = 2)
        val middleSet = routineSetFixture(routineExerciseId = middle.id)
        insertRoutineAggregate(routine, listOf(first, middle, last), listOf(middleSet))

        repository.deleteRoutineExercise(middle.id, routine.id)

        assertEquals(
            listOf(first, last.copy(position = 1)),
            database.routineDao().getRoutineExercises(routine.id),
        )
        assertTrue(database.routineDao().getRoutineSets(middle.id).isEmpty())
    }

    @Test
    fun deleteRoutine_cascadesAllChildren() = runTest {
        insertExercises(exerciseFixture())
        val routine = routineFixture()
        val exercise = routineExerciseFixture()
        val set = routineSetFixture()
        insertRoutineAggregate(routine, listOf(exercise), listOf(set))

        repository.deleteRoutine(routine.id)

        assertNull(database.routineDao().getRoutine(routine.id))
        assertTrue(database.routineDao().getRoutineExercises(routine.id).isEmpty())
        assertTrue(database.routineDao().getRoutineSets(exercise.id).isEmpty())
    }
}
