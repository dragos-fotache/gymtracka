package com.dragosfotache.gymtracka.ui.activeworkout

import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.testing.FakeActiveWorkoutRepository
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun finishDrainWaitsForAcceptedNameAndSetEdits() = runTest {
        val releaseName = CompletableDeferred<Unit>()
        val releaseSet = CompletableDeferred<Unit>()
        val repository = object : FakeActiveWorkoutRepository() {
            override suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity): Boolean {
                releaseSet.await()
                return super.updateWorkoutSet(workoutSet)
            }
        }.apply {
            detailsProvider = { workoutDetailsFixture() }
            nameUpdater = { _, _ -> releaseName.await() }
        }
        val state = ActiveWorkoutState("workout", 0L, repository, backgroundScope)
        runCurrent()

        state.updateWorkoutName("Final")
        state.updateSet(WorkoutSetEntity("set", "entry", 0, 8, 50.0, isCompleted = true))
        runCurrent()
        val drained = async { state.awaitPendingEdits() }
        runCurrent()
        assertFalse(drained.isCompleted)

        releaseName.complete(Unit)
        runCurrent()
        assertFalse(drained.isCompleted)

        releaseSet.complete(Unit)
        runCurrent()
        drained.await()
        assertEquals(listOf("workout" to "Final"), repository.nameUpdates)
        assertEquals(1, repository.setUpdates.size)
    }

    @Test
    fun staleCompletionNeverFiresAfterNewerIncompletion() = runTest {
        val releaseOldRefresh = CompletableDeferred<Unit>()
        var gateNextRefresh = false
        val repository = object : FakeActiveWorkoutRepository() {
            override suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity): Boolean {
                super.updateWorkoutSet(workoutSet)
                return workoutSet.isCompleted
            }
        }.apply {
            detailsProvider = {
                // Even the older callback's intermediate read shows completed.
                if (gateNextRefresh) {
                    gateNextRefresh = false
                    releaseOldRefresh.await()
                }
                workoutDetailsFixture(
                    sets = listOf(WorkoutSetEntity("set", "entry", 0, 8, 50.0, isCompleted = true)),
                )
            }
        }
        val state = ActiveWorkoutState("workout", 0L, repository, backgroundScope)
        runCurrent()
        val timerStarts = mutableListOf<String>()

        gateNextRefresh = true
        state.updateSet(WorkoutSetEntity("set", "entry", 0, 8, 50.0, isCompleted = true)) {
            timerStarts += "old"
        }
        runCurrent()
        // A newer accepted uncheck supersedes the older completion.
        state.updateSet(WorkoutSetEntity("set", "entry", 0, 8, 50.0)) {
            timerStarts += "new"
        }
        runCurrent()
        releaseOldRefresh.complete(Unit)
        runCurrent()

        assertEquals(2, repository.setUpdates.size)
        assertTrue(timerStarts.isEmpty())
    }

    @Test
    fun completedTransitionStartsTimerOnlyWhenStoredCompleted() = runTest {
        val completedProvider: suspend (String) -> WorkoutDetails? = {
            workoutDetailsFixture(
                sets = listOf(WorkoutSetEntity("set", "entry", 0, 8, 50.0, isCompleted = true)),
            )
        }
        fun transitioningState(provider: suspend (String) -> WorkoutDetails?): Pair<ActiveWorkoutState, MutableList<String>> {
            val repository = FakeActiveWorkoutRepository().apply {
                updateSetTransitionResult = true
                detailsProvider = provider
            }
            val starts = mutableListOf<String>()
            return ActiveWorkoutState("workout", 0L, repository, backgroundScope) to starts
        }

        val (activeState, activeStarts) = transitioningState(completedProvider)
        runCurrent()
        activeState.updateSet(WorkoutSetEntity("set", "entry", 0, 8, 50.0, isCompleted = true)) {
            activeStarts += "active"
        }
        runCurrent()
        assertEquals(listOf("active"), activeStarts)

        // A finished root never starts a timer even for a reported transition.
        val finishedDetails = workoutDetailsFixture(
            sets = listOf(WorkoutSetEntity("set", "entry", 0, 8, 50.0, isCompleted = true)),
        ).copy(workout = finishedDetailsWorkout())
        val (finishedState, finishedStarts) = transitioningState({ finishedDetails })
        runCurrent()
        finishedState.updateSet(WorkoutSetEntity("set", "entry", 0, 8, 50.0, isCompleted = true)) {
            finishedStarts += "finished"
        }
        runCurrent()
        assertTrue(finishedStarts.isEmpty())

        // A missing child never starts a timer either.
        val (missingState, missingStarts) = transitioningState({ workoutDetailsFixture(sets = emptyList()) })
        runCurrent()
        missingState.updateSet(WorkoutSetEntity("set", "entry", 0, 8, 50.0, isCompleted = true)) {
            missingStarts += "missing"
        }
        runCurrent()
        assertTrue(missingStarts.isEmpty())
    }

    private fun finishedDetailsWorkout() = WorkoutEntity(
        id = "workout",
        name = "Workout",
        startedAtMillis = 1_000L,
        finishedAtMillis = 9_000L,
        status = "finished",
    )

    @Test
    fun failedAndCancelledEditsFailTheDrain() = runTest {
        val repository = object : FakeActiveWorkoutRepository() {
            override suspend fun updateWorkoutName(workoutId: String, name: String) {
                super.updateWorkoutName(workoutId, name)
                if (name == "Broken") throw IllegalStateException("accepted edit failed")
            }
        }.apply { detailsProvider = { workoutDetailsFixture() } }
        val state = ActiveWorkoutState("workout", 0L, repository, backgroundScope)
        runCurrent()

        state.updateWorkoutName("Broken")
        runCurrent()
        // A failure that already finished still fails repeated drains: only an
        // explicit retry or a superseding edit for the same key resolves it.
        repeat(2) {
            try {
                state.awaitPendingEdits()
                fail("Expected the retained failure to fail repeated drains")
            } catch (_: IllegalStateException) {
            }
        }
        state.updateWorkoutName("Fixed")
        runCurrent()
        state.awaitPendingEdits()
        assertEquals(
            listOf("workout" to "Broken", "workout" to "Fixed"),
            repository.nameUpdates,
        )

        val releaseGated = CompletableDeferred<Unit>()
        val cancellableRepository = FakeActiveWorkoutRepository().apply {
            detailsProvider = { workoutDetailsFixture() }
            nameUpdater = { _, _ -> releaseGated.await() }
        }
        val owned = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val cancellable = ActiveWorkoutState("workout", 0L, cancellableRepository, owned)
        runCurrent()
        cancellable.updateWorkoutName("Gated")
        runCurrent()
        owned.cancel()
        runCurrent()
        try {
            cancellable.awaitPendingEdits()
            fail("Expected cancellation to fail the drain")
        } catch (_: CancellationException) {
        }
    }
}
