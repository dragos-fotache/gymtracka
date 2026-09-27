package com.dragosfotache.gymtracka.ui.activeworkout

import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.testing.FakeActiveWorkoutRepository
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWorkoutStateTest {
    @Test
    fun refresh_ignoresAStaleEarlierLoad() = runTest {
        val first = CompletableDeferred<WorkoutDetails?>()
        val second = CompletableDeferred<WorkoutDetails?>()
        var request = 0
        val repository = FakeActiveWorkoutRepository().apply {
            detailsProvider = { if (request++ == 0) first.await() else second.await() }
        }
        val state = ActiveWorkoutState("workout", 0L, repository, backgroundScope)
        runCurrent()

        state.refresh()
        runCurrent()
        second.complete(workoutDetailsFixture(name = "Newest"))
        runCurrent()
        first.complete(workoutDetailsFixture(name = "Stale"))
        runCurrent()

        assertEquals("Newest", state.details?.workout?.name)
    }

    @Test
    fun workoutNameAutosaves_arePersistedInCallOrder() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val repository = FakeActiveWorkoutRepository().apply {
            detailsProvider = { workoutDetailsFixture() }
            nameUpdater = { _, name ->
                if (name == "First") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            }
        }
        val state = ActiveWorkoutState("workout", 0L, repository, backgroundScope)
        runCurrent()

        state.updateWorkoutName("First")
        runCurrent()
        firstStarted.await()
        state.updateWorkoutName("Second")
        runCurrent()

        assertEquals(listOf("First"), repository.nameUpdates.map { it.second })
        assertEquals("Second", state.details?.workout?.name)

        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(listOf("First", "Second"), repository.nameUpdates.map { it.second })
    }

    @Test
    fun addSet_copiesThePreviousInputsAndRefreshes() = runTest {
        val repository = object : FakeActiveWorkoutRepository() {
            var addedRepetitions: Int? = null
            var addedWeight: Double? = null
            override suspend fun addSetToWorkoutExercise(
                workoutExerciseId: String,
                position: Int,
                repetitions: Int?,
                weightKg: Double?,
            ) = super.addSetToWorkoutExercise(workoutExerciseId, position, repetitions, weightKg).also {
                addedRepetitions = repetitions
                addedWeight = weightKg
            }
        }.apply { detailsProvider = { workoutDetailsFixture() } }
        val state = ActiveWorkoutState("workout", 0L, repository, backgroundScope)
        runCurrent()

        state.addSet(state.details!!.exercises.single())
        runCurrent()

        assertEquals(8, repository.addedRepetitions)
        assertEquals(50.0, repository.addedWeight)
        assertFalse(state.details!!.exercises.single().sets.single().isCompleted)
    }
}
