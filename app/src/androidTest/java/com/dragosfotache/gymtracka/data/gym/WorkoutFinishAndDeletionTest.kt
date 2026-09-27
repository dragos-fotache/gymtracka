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
class WorkoutFinishAndDeletionTest : GymDatabaseTest() {
    @Test
    fun finish_requiresACompletedSet_andLeavesDraftUnchanged() = runTest {
        insertExercises(exerciseFixture())
        val workout = workoutFixture()
        val exercise = workoutExerciseFixture()
        val unfinishedSet = workoutSetFixture(isCompleted = false)
        insertWorkoutAggregate(workout, listOf(exercise), listOf(unfinishedSet))

        assertFalse(repository.finishWorkout(workout.id, finishedAtMillis = 9_000L))

        assertEquals(workout, database.workoutDao().getWorkout(workout.id))
        assertEquals(listOf(exercise), database.workoutDao().getWorkoutExercises(workout.id))
        assertEquals(listOf(unfinishedSet), database.workoutDao().getWorkoutSets(exercise.id))
    }

    @Test
    fun finish_discardsDraftData_renumbersSurvivors_andMarksWorkoutFinished() = runTest {
        insertExercises(
            exerciseFixture(id = "exercise-1"),
            exerciseFixture(id = "exercise-2", name = "Squat"),
            exerciseFixture(id = "exercise-3", name = "Row"),
        )
        val workout = workoutFixture()
        val removedExercise = workoutExerciseFixture(
            id = "workout-exercise-1",
            exerciseId = "exercise-1",
            position = 0,
        )
        val firstSurvivor = workoutExerciseFixture(
            id = "workout-exercise-2",
            exerciseId = "exercise-2",
            position = 1,
        )
        val secondSurvivor = workoutExerciseFixture(
            id = "workout-exercise-3",
            exerciseId = "exercise-3",
            position = 2,
        )
        val removedSet = workoutSetFixture(
            id = "set-removed-with-exercise",
            workoutExerciseId = removedExercise.id,
            isCompleted = false,
        )
        val completedSet = workoutSetFixture(
            id = "set-completed-1",
            workoutExerciseId = firstSurvivor.id,
            position = 0,
            isCompleted = true,
            restSeconds = 42,
        )
        val unfinishedSet = workoutSetFixture(
            id = "set-unfinished",
            workoutExerciseId = firstSurvivor.id,
            position = 1,
            isCompleted = false,
        )
        val otherCompletedSet = workoutSetFixture(
            id = "set-completed-2",
            workoutExerciseId = secondSurvivor.id,
            isCompleted = true,
        )
        insertWorkoutAggregate(
            workout,
            listOf(removedExercise, firstSurvivor, secondSurvivor),
            listOf(removedSet, completedSet, unfinishedSet, otherCompletedSet),
        )

        assertTrue(repository.finishWorkout(workout.id, finishedAtMillis = 9_000L))

        val finished = database.workoutDao().getWorkout(workout.id)
        assertEquals("finished", finished?.status)
        assertEquals(9_000L, finished?.finishedAtMillis)
        assertEquals(
            listOf(
                firstSurvivor.copy(position = 0),
                secondSurvivor.copy(position = 1),
            ),
            database.workoutDao().getWorkoutExercises(workout.id),
        )
        assertEquals(listOf(completedSet), database.workoutDao().getWorkoutSets(firstSurvivor.id))
        assertEquals(
            listOf(otherCompletedSet),
            database.workoutDao().getWorkoutSets(secondSurvivor.id),
        )
        assertTrue(database.workoutDao().getWorkoutSets(removedExercise.id).isEmpty())
    }

    @Test
    fun finish_rollsBackCleanup_whenTheFinalStatusUpdateFails() = runTest {
        insertExercises(
            exerciseFixture(id = "exercise-1"),
            exerciseFixture(id = "exercise-2", name = "Squat"),
        )
        val workout = workoutFixture()
        val keptExercise = workoutExerciseFixture(exerciseId = "exercise-1")
        val otherwiseRemovedExercise = workoutExerciseFixture(
            id = "workout-exercise-2",
            exerciseId = "exercise-2",
            position = 1,
        )
        val completedSet = workoutSetFixture(isCompleted = true)
        val unfinishedSet = workoutSetFixture(
            id = "unfinished-set",
            workoutExerciseId = otherwiseRemovedExercise.id,
            isCompleted = false,
        )
        insertWorkoutAggregate(
            workout,
            listOf(keptExercise, otherwiseRemovedExercise),
            listOf(completedSet, unfinishedSet),
        )
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_finished_status
                BEFORE UPDATE OF status ON workouts
                WHEN NEW.status = 'finished'
                BEGIN SELECT RAISE(ABORT, 'forced finish failure'); END""",
        )

        var failure: Throwable? = null
        try {
            repository.finishWorkout(workout.id, finishedAtMillis = 9_000L)
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(failure)
        assertEquals(workout, database.workoutDao().getWorkout(workout.id))
        assertEquals(
            listOf(keptExercise, otherwiseRemovedExercise),
            database.workoutDao().getWorkoutExercises(workout.id),
        )
        assertEquals(listOf(completedSet), database.workoutDao().getWorkoutSets(keptExercise.id))
        assertEquals(
            listOf(unfinishedSet),
            database.workoutDao().getWorkoutSets(otherwiseRemovedExercise.id),
        )
    }

    @Test
    fun deleteSet_renumbersRemainingSets() = runTest {
        insertExercises(exerciseFixture())
        val workout = workoutFixture()
        val exercise = workoutExerciseFixture()
        val first = workoutSetFixture(id = "set-1", position = 0)
        val middle = workoutSetFixture(id = "set-2", position = 1)
        val last = workoutSetFixture(id = "set-3", position = 2)
        insertWorkoutAggregate(workout, listOf(exercise), listOf(first, middle, last))

        repository.deleteWorkoutSet(middle)

        assertEquals(
            listOf(first, last.copy(position = 1)),
            database.workoutDao().getWorkoutSets(exercise.id),
        )
    }

    @Test
    fun deleteExercise_cascadesItsSets_andRenumbersRemainingExercises() = runTest {
        insertExercises(
            exerciseFixture(id = "exercise-1"),
            exerciseFixture(id = "exercise-2", name = "Squat"),
            exerciseFixture(id = "exercise-3", name = "Row"),
        )
        val workout = workoutFixture()
        val first = workoutExerciseFixture(id = "entry-1", exerciseId = "exercise-1", position = 0)
        val middle = workoutExerciseFixture(id = "entry-2", exerciseId = "exercise-2", position = 1)
        val last = workoutExerciseFixture(id = "entry-3", exerciseId = "exercise-3", position = 2)
        val middleSet = workoutSetFixture(id = "middle-set", workoutExerciseId = middle.id)
        insertWorkoutAggregate(workout, listOf(first, middle, last), listOf(middleSet))

        repository.deleteExerciseFromWorkout(middle.id, workout.id)

        assertEquals(
            listOf(first, last.copy(position = 1)),
            database.workoutDao().getWorkoutExercises(workout.id),
        )
        assertTrue(database.workoutDao().getWorkoutSets(middle.id).isEmpty())
    }

    @Test
    fun discardWorkout_cascadesExercisesAndSets() = runTest {
        insertExercises(exerciseFixture())
        val workout = workoutFixture()
        val exercise = workoutExerciseFixture()
        val set = workoutSetFixture()
        insertWorkoutAggregate(workout, listOf(exercise), listOf(set))

        repository.discardWorkout(workout.id)

        assertNull(database.workoutDao().getWorkout(workout.id))
        assertTrue(database.workoutDao().getWorkoutExercises(workout.id).isEmpty())
        assertTrue(database.workoutDao().getWorkoutSets(exercise.id).isEmpty())
    }

    @Test
    fun deleteFinishedWorkout_cascadesExercisesAndSets() = runTest {
        insertExercises(exerciseFixture())
        val workout = workoutFixture(
            status = "finished",
            finishedAtMillis = 2_000L,
        )
        val exercise = workoutExerciseFixture()
        val set = workoutSetFixture(isCompleted = true)
        insertWorkoutAggregate(workout, listOf(exercise), listOf(set))

        repository.deleteWorkout(workout.id)

        assertNull(database.workoutDao().getWorkout(workout.id))
        assertTrue(database.workoutDao().getWorkoutExercises(workout.id).isEmpty())
        assertTrue(database.workoutDao().getWorkoutSets(exercise.id).isEmpty())
    }
}
