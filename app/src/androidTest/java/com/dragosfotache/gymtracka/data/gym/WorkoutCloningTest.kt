package com.dragosfotache.gymtracka.data.gym

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutCloningTest : GymDatabaseTest() {
    @Test
    fun performAgain_createsFreshActiveAggregate_andStripsCompletionState() = runTest {
        insertExercises(
            exerciseFixture(id = "bench", name = "Bench Press"),
            exerciseFixture(id = "row", name = "Row"),
        )
        val source = workoutFixture(
            id = "source",
            name = "Push and Pull",
            startedAtMillis = 1_000L,
            finishedAtMillis = 2_000L,
            status = "finished",
        )
        val bench = workoutExerciseFixture(
            id = "source-bench",
            workoutId = source.id,
            exerciseId = "bench",
            position = 0,
            notes = "Pause on chest",
            restDurationSeconds = 90,
        )
        val row = workoutExerciseFixture(
            id = "source-row",
            workoutId = source.id,
            exerciseId = "row",
            position = 1,
            notes = "",
            restDurationSeconds = 45,
        )
        val benchSet = workoutSetFixture(
            id = "source-set-1",
            workoutExerciseId = bench.id,
            repetitions = 8,
            weightKg = 70.0,
            isCompleted = true,
            restSeconds = 88,
        )
        val rowSet = workoutSetFixture(
            id = "source-set-2",
            workoutExerciseId = row.id,
            repetitions = null,
            weightKg = 40.0,
            isCompleted = true,
            restSeconds = 40,
        )
        insertWorkoutAggregate(source, listOf(bench, row), listOf(benchSet, rowSet))

        val clone = repository.performWorkoutAgain(source.id, startedAtMillis = 5_000L)

        assertNotNull(clone)
        clone!!
        assertNotEquals(source.id, clone.id)
        assertEquals(source.name, clone.name)
        assertEquals(5_000L, clone.startedAtMillis)
        assertEquals("active", clone.status)
        assertNull(clone.finishedAtMillis)
        val details = repository.workoutDetails(clone.id)!!
        assertEquals(listOf("bench", "row"), details.exercises.map { it.workoutExercise.exerciseId })
        assertEquals(listOf("Pause on chest", ""), details.exercises.map { it.workoutExercise.notes })
        assertEquals(listOf(90, 45), details.exercises.map { it.workoutExercise.restDurationSeconds })
        assertTrue(details.exercises.zip(listOf(bench, row)).all { (copy, original) ->
            copy.workoutExercise.id != original.id
        })
        val copiedSets = details.exercises.flatMap(WorkoutExerciseDetails::sets)
        assertEquals(listOf(8, null), copiedSets.map { it.repetitions })
        assertEquals(listOf(70.0, 40.0), copiedSets.map { it.weightKg })
        assertTrue(copiedSets.none(WorkoutSetEntity::isCompleted))
        assertTrue(copiedSets.all { it.restSeconds == null })
        assertTrue(copiedSets.zip(listOf(benchSet, rowSet)).all { (copy, original) -> copy.id != original.id })
    }

    @Test
    fun performAgain_rejectsMissingAndUnfinishedSources_withoutCreatingWorkouts() = runTest {
        val activeSource = workoutFixture(id = "active-source")
        insertWorkoutAggregate(activeSource)

        assertNull(repository.performWorkoutAgain("missing", startedAtMillis = 2_000L))
        assertNull(repository.performWorkoutAgain(activeSource.id, startedAtMillis = 3_000L))
        assertEquals(listOf(activeSource), repository.workouts())
    }

    @Test
    fun performAgain_rollsBackTheNewWorkout_whenChildInsertionFails() = runTest {
        insertExercises(exerciseFixture())
        val source = workoutFixture(
            id = "source",
            finishedAtMillis = 2_000L,
            status = "finished",
        )
        val sourceExercise = workoutExerciseFixture(workoutId = source.id)
        val sourceSet = workoutSetFixture(isCompleted = true)
        insertWorkoutAggregate(source, listOf(sourceExercise), listOf(sourceSet))
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_cloned_workout_exercise
                BEFORE INSERT ON workout_exercises
                BEGIN SELECT RAISE(ABORT, 'forced clone failure'); END""",
        )

        var failure: Throwable? = null
        try {
            repository.performWorkoutAgain(source.id, startedAtMillis = 5_000L)
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(failure)
        assertEquals(listOf(source), repository.workouts())
        assertEquals(listOf(sourceExercise), database.workoutDao().getWorkoutExercises(source.id))
        assertEquals(listOf(sourceSet), database.workoutDao().getWorkoutSets(sourceExercise.id))
    }

    @Test
    fun startFromRoutine_createsFreshWorkoutTemplate_andRejectsDrafts() = runTest {
        insertExercises(exerciseFixture())
        val routine = routineFixture(name = "Strength Day")
        val routineExercise = routineExerciseFixture(restDurationSeconds = 75)
        val routineSet = routineSetFixture(repetitions = 6, weightKg = 80.0)
        insertRoutineAggregate(routine, listOf(routineExercise), listOf(routineSet))
        val draft = routineFixture(id = "draft", name = "Draft", isDraft = true)
        insertRoutineAggregate(draft)

        val workout = repository.startWorkoutFromRoutine(routine.id, startedAtMillis = 7_000L)

        assertNotNull(workout)
        workout!!
        assertEquals(routine.name, workout.name)
        assertEquals(7_000L, workout.startedAtMillis)
        assertEquals("active", workout.status)
        val details = repository.workoutDetails(workout.id)!!
        val copiedExercise = details.exercises.single()
        assertNotEquals(routineExercise.id, copiedExercise.workoutExercise.id)
        assertEquals(routineExercise.exerciseId, copiedExercise.workoutExercise.exerciseId)
        assertEquals(75, copiedExercise.workoutExercise.restDurationSeconds)
        val copiedSet = copiedExercise.sets.single()
        assertNotEquals(routineSet.id, copiedSet.id)
        assertEquals(6, copiedSet.repetitions)
        assertEquals(80.0, copiedSet.weightKg)
        assertFalse(copiedSet.isCompleted)
        assertNull(copiedSet.restSeconds)
        assertNull(repository.startWorkoutFromRoutine(draft.id, startedAtMillis = 8_000L))
        assertNull(repository.startWorkoutFromRoutine("missing", startedAtMillis = 9_000L))
    }

    @Test
    fun startFromRoutine_rollsBackTheWorkout_whenChildInsertionFails() = runTest {
        insertExercises(exerciseFixture())
        val routine = routineFixture()
        val routineExercise = routineExerciseFixture()
        val routineSet = routineSetFixture()
        insertRoutineAggregate(routine, listOf(routineExercise), listOf(routineSet))
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_routine_workout_exercise
                BEFORE INSERT ON workout_exercises
                BEGIN SELECT RAISE(ABORT, 'forced routine clone failure'); END""",
        )

        var failure: Throwable? = null
        try {
            repository.startWorkoutFromRoutine(routine.id, startedAtMillis = 5_000L)
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(failure)
        assertTrue(repository.workouts().isEmpty())
        assertEquals(routine, database.routineDao().getRoutine(routine.id))
    }

    @Test
    fun saveAsRoutine_copiesTemplateOnly_andRejectsUnfinishedWorkouts() = runTest {
        insertExercises(exerciseFixture())
        val source = workoutFixture(
            id = "finished-source",
            name = "Finished",
            finishedAtMillis = 2_000L,
            status = "finished",
        )
        val sourceExercise = workoutExerciseFixture(
            workoutId = source.id,
            notes = "Workout-only notes",
            restDurationSeconds = 60,
        )
        val sourceSet = workoutSetFixture(
            repetitions = 10,
            weightKg = 40.0,
            isCompleted = true,
            restSeconds = 55,
        )
        insertWorkoutAggregate(source, listOf(sourceExercise), listOf(sourceSet))
        val activeSource = workoutFixture(id = "active-source", startedAtMillis = 3_000L)
        insertWorkoutAggregate(activeSource)

        val routine = repository.saveWorkoutAsRoutine(source.id, "Reusable")

        assertNotNull(routine)
        routine!!
        assertEquals("Reusable", routine.name)
        assertFalse(routine.isDraft)
        val details = repository.routineDetails(routine.id)!!
        val copiedExercise = details.exercises.single()
        assertNotEquals(sourceExercise.id, copiedExercise.routineExercise.id)
        assertEquals(sourceExercise.exerciseId, copiedExercise.routineExercise.exerciseId)
        assertEquals(60, copiedExercise.routineExercise.restDurationSeconds)
        val copiedSet = copiedExercise.sets.single()
        assertNotEquals(sourceSet.id, copiedSet.id)
        assertEquals(10, copiedSet.repetitions)
        assertEquals(40.0, copiedSet.weightKg)
        assertNull(repository.saveWorkoutAsRoutine(activeSource.id, "Invalid"))
        assertNull(repository.saveWorkoutAsRoutine("missing", "Invalid"))
    }

    @Test
    fun saveAsRoutine_rollsBackTheRoutine_whenChildInsertionFails() = runTest {
        insertExercises(exerciseFixture())
        val source = workoutFixture(
            id = "source",
            finishedAtMillis = 2_000L,
            status = "finished",
        )
        val sourceExercise = workoutExerciseFixture(workoutId = source.id)
        val sourceSet = workoutSetFixture(isCompleted = true)
        insertWorkoutAggregate(source, listOf(sourceExercise), listOf(sourceSet))
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_saved_routine_exercise
                BEFORE INSERT ON routine_exercises
                BEGIN SELECT RAISE(ABORT, 'forced save failure'); END""",
        )

        var failure: Throwable? = null
        try {
            repository.saveWorkoutAsRoutine(source.id, "Should Roll Back")
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(failure)
        assertTrue(repository.savedRoutines().isEmpty())
        assertEquals(source, database.workoutDao().getWorkout(source.id))
    }
}
