package com.dragosfotache.gymtracka.data.gym

import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExerciseHistoryRepositoryTest : GymDatabaseTest() {
    @Test
    fun historyGroupsOccurrencesByWorkoutAndKeepsOnlyThisExercisesCompletedSetsInOrder() = runTest {
        insertExercises(exerciseFixture("press", "Press"), exerciseFixture("other", "Press"))
        val entries = listOf(
            workoutExerciseFixture("second", "new", "press", position = 2, notes = "Lighter back-off"),
            workoutExerciseFixture("first", "new", "press", notes = "Pause at the bottom"),
            workoutExerciseFixture("unrelated", "new", "other", position = 1, notes = "Do not show"),
        )
        insertWorkoutAggregate(
            workoutFixture("new", "Upper B", finishedAtMillis = 4_000, status = "finished"),
            entries,
            listOf(
                workoutSetFixture("second-set", "second", weightKg = 40.0, isCompleted = true),
                workoutSetFixture("first-set-2", "first", position = 1, weightKg = 50.0, isCompleted = true),
                workoutSetFixture("first-set-1", "first", weightKg = 55.0, isCompleted = true),
                workoutSetFixture("unfinished", "first", position = 2, isCompleted = false),
                workoutSetFixture("other-set", "unrelated", isCompleted = true),
            ),
        )
        insertWorkoutAggregate(
            workoutFixture("old", "Upper A", finishedAtMillis = 2_000, status = "finished"),
            listOf(workoutExerciseFixture("old-entry", "old", "press", notes = "   ")),
            listOf(workoutSetFixture("old-set", "old-entry", isCompleted = true)),
        )
        insertWorkoutAggregate(
            workoutFixture("active"),
            listOf(workoutExerciseFixture("active-entry", "active", "press")),
            listOf(workoutSetFixture("active-set", "active-entry", isCompleted = true)),
        )

        val history = repository.exerciseHistory("press")!!
        assertEquals("Press", history.exerciseName)
        assertEquals(listOf("new", "old"), history.workouts.map { it.workoutId })
        val latest = history.workouts.first()
        assertEquals("Upper B", latest.workoutName)
        assertEquals(4_000L, latest.performedAtMillis)
        assertEquals(listOf("Pause at the bottom", "Lighter back-off"), latest.notes)
        assertEquals(listOf("first-set-1", "first-set-2", "second-set"), latest.sets.map { it.id })
        assertEquals(listOf(55.0, 50.0, 40.0), latest.sets.map { it.weightKg })
        assertTrue(history.workouts.last().notes.isEmpty())
    }

    @Test
    fun historyRespectsAccountTombstonesCatalogueVisibilityAndSessionFences() = runTest {
        insertExercises(
            exerciseFixture("press"),
            exerciseFixture("foreign", isUserCreated = true).copy(accountId = "b"),
            exerciseFixture("deleted", isUserCreated = true).copy(accountId = "a", deletedAtMillis = 1),
        )
        listOf("a", "b", "local", "tombstone").forEach { id ->
            insertWorkoutAggregate(
                workoutFixture(id, finishedAtMillis = 4_000, status = "finished").copy(
                    accountId = if (id == "local") null else if (id == "tombstone") "a" else id,
                    deletedAtMillis = if (id == "tombstone") 5_000 else null,
                ),
                listOf(workoutExerciseFixture("entry-$id", id, "press")),
                listOf(workoutSetFixture("set-$id", "entry-$id", isCompleted = true)),
            )
        }
        database.syncDao().upsertDeviceState(SyncDeviceStateEntity(activeAccountId = "a", sessionGeneration = 7))
        val account = GymRepository(database, RepositorySession("a", 7))
        assertEquals(listOf("a"), account.exerciseHistory("press")!!.workouts.map { it.workoutId })
        assertNull(account.exerciseHistory("foreign"))
        assertNull(account.exerciseHistory("deleted"))

        database.syncDao().upsertDeviceState(SyncDeviceStateEntity(activeAccountId = null, sessionGeneration = 0))
        assertEquals(listOf("local"), repository.exerciseHistory("press")!!.workouts.map { it.workoutId })
        try {
            account.exerciseHistory("press")
            fail("A retired account repository must refuse the read")
        } catch (_: StaleSessionException) {
            // Expected: stale reads cannot fall back to local history.
        }
    }

    @Test
    fun unusedVisibleExerciseHasEmptyHistoryAndMissingExerciseIsUnavailable() = runTest {
        insertExercises(exerciseFixture("unused"))
        assertTrue(repository.exerciseHistory("unused")!!.workouts.isEmpty())
        assertNull(repository.exerciseHistory("missing"))
    }
}
