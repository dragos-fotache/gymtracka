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
class WorkoutAdditionAndHistoryTest : GymDatabaseTest() {
    @Test
    fun addExercises_usesNewestFinishedHistory_andAppendsInInputOrder() = runTest {
        insertExercises(
            exerciseFixture(id = "existing", name = "Existing"),
            exerciseFixture(id = "with-history", name = "Bench Press"),
            exerciseFixture(id = "without-history", name = "Pull-up"),
        )
        val oldWorkout = workoutFixture(
            id = "old-workout",
            finishedAtMillis = 2_000L,
            status = "finished",
        )
        val oldExercise = workoutExerciseFixture(
            id = "old-entry",
            workoutId = oldWorkout.id,
            exerciseId = "with-history",
            restDurationSeconds = 45,
        )
        val oldSet = workoutSetFixture(
            id = "old-set",
            workoutExerciseId = oldExercise.id,
            repetitions = 12,
            weightKg = 30.0,
            isCompleted = true,
            restSeconds = 40,
        )
        insertWorkoutAggregate(oldWorkout, listOf(oldExercise), listOf(oldSet))

        val latestWorkout = workoutFixture(
            id = "latest-workout",
            startedAtMillis = 3_000L,
            finishedAtMillis = 4_000L,
            status = "finished",
        )
        val latestExercise = workoutExerciseFixture(
            id = "latest-entry",
            workoutId = latestWorkout.id,
            exerciseId = "with-history",
            restDurationSeconds = 75,
        )
        val copiedSourceA = workoutSetFixture(
            id = "latest-set-1",
            workoutExerciseId = latestExercise.id,
            position = 0,
            repetitions = 8,
            weightKg = 50.0,
            isCompleted = true,
            restSeconds = 70,
        )
        val ignoredUnfinished = workoutSetFixture(
            id = "latest-set-2",
            workoutExerciseId = latestExercise.id,
            position = 1,
            repetitions = 99,
            weightKg = 99.0,
            isCompleted = false,
        )
        val copiedSourceB = workoutSetFixture(
            id = "latest-set-3",
            workoutExerciseId = latestExercise.id,
            position = 2,
            repetitions = null,
            weightKg = 60.0,
            isCompleted = true,
            restSeconds = 74,
        )
        insertWorkoutAggregate(
            latestWorkout,
            listOf(latestExercise),
            listOf(copiedSourceA, ignoredUnfinished, copiedSourceB),
        )

        val target = workoutFixture(id = "target", startedAtMillis = 5_000L)
        val existing = workoutExerciseFixture(
            id = "existing-entry",
            workoutId = target.id,
            exerciseId = "existing",
        )
        insertWorkoutAggregate(target, listOf(existing))

        repository.addExercisesToWorkout(
            target.id,
            listOf("with-history", "without-history"),
        )

        val entries = database.workoutDao().getWorkoutExercises(target.id)
        assertEquals(listOf("existing", "with-history", "without-history"), entries.map { it.exerciseId })
        assertEquals(listOf(0, 1, 2), entries.map { it.position })
        assertEquals(75, entries[1].restDurationSeconds)
        assertEquals(30, entries[2].restDurationSeconds)

        val copiedSets = database.workoutDao().getWorkoutSets(entries[1].id)
        assertEquals(2, copiedSets.size)
        assertEquals(listOf(0, 1), copiedSets.map { it.position })
        assertEquals(listOf(8, null), copiedSets.map { it.repetitions })
        assertEquals(listOf(50.0, 60.0), copiedSets.map { it.weightKg })
        assertTrue(copiedSets.none(WorkoutSetEntity::isCompleted))
        assertTrue(copiedSets.all { it.restSeconds == null })

        val emptySet = database.workoutDao().getWorkoutSets(entries[2].id).single()
        assertEquals(0, emptySet.position)
        assertNull(emptySet.repetitions)
        assertNull(emptySet.weightKg)
        assertFalse(emptySet.isCompleted)
        assertNull(emptySet.restSeconds)
    }

    @Test
    fun addExercises_rollsBackAllInserts_whenAnyExerciseIsInvalid() = runTest {
        insertExercises(exerciseFixture(id = "valid"))
        val workout = workoutFixture()
        insertWorkoutAggregate(workout)

        var failure: Throwable? = null
        try {
            repository.addExercisesToWorkout(workout.id, listOf("valid", "missing"))
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(failure)
        assertTrue(database.workoutDao().getWorkoutExercises(workout.id).isEmpty())
    }

    @Test
    fun replaceExercise_preservesWorkoutEntryAndAllMutableData() = runTest {
        insertExercises(
            exerciseFixture(id = "before", name = "Bench Press"),
            exerciseFixture(id = "after", name = "Incline Press"),
        )
        val workout = workoutFixture()
        val entry = workoutExerciseFixture(
            exerciseId = "before",
            notes = "Keep elbows tucked",
            restDurationSeconds = 90,
        )
        val set = workoutSetFixture(
            repetitions = 8,
            weightKg = 70.0,
            isCompleted = true,
            restSeconds = 85,
        )
        insertWorkoutAggregate(workout, listOf(entry), listOf(set))

        repository.replaceWorkoutExercise(entry.id, "after")

        assertEquals(entry.copy(exerciseId = "after"), database.workoutDao().getWorkoutExercises(workout.id).single())
        assertEquals(listOf(set), database.workoutDao().getWorkoutSets(entry.id))
    }

    @Test
    fun history_containsOnlyFinishedWorkouts_andCalculatesSummariesDeterministically() = runTest {
        insertExercises(
            exerciseFixture(id = "bench", name = "Bench Press"),
            exerciseFixture(id = "row", name = "Row"),
        )
        val active = workoutFixture(id = "active", startedAtMillis = 9_000L)
        insertWorkoutAggregate(active)

        val older = workoutFixture(
            id = "older",
            startedAtMillis = 1_000L,
            finishedAtMillis = 2_000L,
            status = "finished",
        )
        val olderEntry = workoutExerciseFixture(
            id = "older-entry",
            workoutId = older.id,
            exerciseId = "bench",
        )
        val olderSet = workoutSetFixture(
            id = "older-set",
            workoutExerciseId = olderEntry.id,
            repetitions = 10,
            weightKg = 5.0,
            isCompleted = true,
        )
        insertWorkoutAggregate(older, listOf(olderEntry), listOf(olderSet))

        val newer = workoutFixture(
            id = "newer",
            startedAtMillis = 3_000L,
            finishedAtMillis = 4_000L,
            status = "finished",
        )
        val bench = workoutExerciseFixture(
            id = "newer-bench",
            workoutId = newer.id,
            exerciseId = "bench",
            position = 0,
        )
        val row = workoutExerciseFixture(
            id = "newer-row",
            workoutId = newer.id,
            exerciseId = "row",
            position = 1,
        )
        val firstTieWinner = workoutSetFixture(
            id = "tie-first",
            workoutExerciseId = bench.id,
            position = 0,
            repetitions = 10,
            weightKg = 10.0,
            isCompleted = true,
        )
        val secondTie = workoutSetFixture(
            id = "tie-second",
            workoutExerciseId = bench.id,
            position = 1,
            repetitions = 5,
            weightKg = 20.0,
            isCompleted = true,
        )
        val nullableSet = workoutSetFixture(
            id = "nullable",
            workoutExerciseId = bench.id,
            position = 2,
            repetitions = null,
            weightKg = 100.0,
            isCompleted = true,
        )
        insertWorkoutAggregate(
            newer,
            listOf(bench, row),
            listOf(firstTieWinner, secondTie, nullableSet),
        )

        val history = repository.finishedWorkoutSummaries()

        assertEquals(listOf("newer", "older"), history.map { it.workout.id })
        assertEquals(listOf("newer-bench", "newer-row"), history[0].exercises.map { it.workoutExerciseId })
        val benchSummary = history[0].exercises[0]
        assertEquals(3, benchSummary.setCount)
        assertEquals(200.0, benchSummary.volumeKg, 0.0)
        assertEquals(10.0, benchSummary.bestSetWeightKg)
        assertEquals(10, benchSummary.bestSetRepetitions)
        val rowSummary = history[0].exercises[1]
        assertEquals(0, rowSummary.setCount)
        assertEquals(0.0, rowSummary.volumeKg, 0.0)
        assertNull(rowSummary.bestSetWeightKg)
        assertNull(rowSummary.bestSetRepetitions)
    }
}
