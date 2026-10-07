package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.testing.FakeActiveWorkoutRepository
import com.dragosfotache.gymtracka.testing.FakeExerciseRepository
import com.dragosfotache.gymtracka.testing.FakeGymAppStateRepository
import com.dragosfotache.gymtracka.testing.FakeRoutineRepository
import com.dragosfotache.gymtracka.testing.FakeWorkoutHistoryRepository
import com.dragosfotache.gymtracka.testing.routineDetailsFixture
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutState
import com.dragosfotache.gymtracka.ui.exercises.ExerciseCatalogueState
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorState
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAppGateTest {
    @Test
    fun sharedPauseRefusesRepresentativeMutationsWithoutSideEffects() = runTest {
        val shared = FiniteEditCoordinator(backgroundScope)
        try {
            val routineRepository = FakeRoutineRepository().apply {
                detailsProvider = { routineDetailsFixture() }
            }
            val activeRepository = FakeActiveWorkoutRepository().apply {
                detailsProvider = { workoutDetailsFixture() }
            }
            val exerciseRepository = FakeExerciseRepository()
            val historyRepository = FakeWorkoutHistoryRepository().apply {
                saveResult = RoutineEntity("routine", "Saved")
            }
            val screenScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val routine = RoutineEditorState("routine", routineRepository, screenScope, edits = shared)
                val active = ActiveWorkoutState("workout", 0L, activeRepository, screenScope, edits = shared)
                val catalogue = ExerciseCatalogueState(exerciseRepository, screenScope, edits = shared)
                val history = WorkoutHistoryState(historyRepository, screenScope, edits = shared)
                runCurrent()

                val lease = shared.pauseAndDrain()
                try {
                    assertTrue(shared.isPaused)
                    routine.updateName("Hacked routine")
                    active.updateWorkoutName("Hacked workout")
                    catalogue.toggleExercise("exercise-1")
                    catalogue.addSelectedExercises("workout") { }
                    val historyWorkout = WorkoutEntity(
                        id = "history-workout",
                        name = "Finished",
                        startedAtMillis = 1_000L,
                        finishedAtMillis = 2_000L,
                        status = "finished",
                    )
                    history.requestSaveAsRoutine(historyWorkout)
                    history.updateRoutineName("Hacked history")
                    history.confirmSaveAsRoutine()
                    runCurrent()

                    assertTrue(routineRepository.nameUpdates.isEmpty())
                    assertTrue(activeRepository.nameUpdates.isEmpty())
                    assertTrue(exerciseRepository.addedToWorkout.isEmpty())
                    assertTrue(historyRepository.saveRequests.isEmpty())
                    // Refused acceptance must not alter optimistic drafts or success state.
                    assertEquals("Routine", routine.name)
                    assertEquals("Workout", active.details?.workout?.name)
                    assertFalse(history.isSavingRoutine)
                    assertTrue(history.saveRoutineError == null)
                } finally {
                    lease.close()
                }
                runCurrent()

                routine.updateName("Allowed routine")
                active.updateWorkoutName("Allowed workout")
                runCurrent()
                shared.awaitPendingEdits()
                assertEquals(listOf("routine" to "Allowed routine"), routineRepository.nameUpdates)
                assertEquals(listOf("workout" to "Allowed workout"), activeRepository.nameUpdates)
            } finally {
                screenScope.cancel()
            }
        } finally {
            shared.dispose()
        }
    }

    @Test
    fun screenDisposalDoesNotLoseAcceptedWork() = runTest {
        val shared = FiniteEditCoordinator(backgroundScope)
        val screenScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val releaseName = CompletableDeferred<Unit>()
            val routineRepository = FakeRoutineRepository().apply {
                detailsProvider = { routineDetailsFixture() }
                nameUpdater = { _, _ -> releaseName.await() }
            }
            val routine = RoutineEditorState("routine", routineRepository, screenScope, edits = shared)
            runCurrent()

            routine.updateName("Accepted")
            runCurrent()
            // Fake records the attempt before the updater gate suspends: one
            // attempted write is registered, but the drain stays suspended.
            assertEquals(listOf("routine" to "Accepted"), routineRepository.nameUpdates)
            val drain: Deferred<Unit> = async { shared.awaitPendingEdits() }
            runCurrent()
            assertTrue(drain.isActive)

            screenScope.cancel()
            runCurrent()
            // Composition disposal cannot forget accepted work: drain still waits.
            assertTrue(drain.isActive)
            assertEquals(listOf("routine" to "Accepted"), routineRepository.nameUpdates)
            releaseName.complete(Unit)
            runCurrent()
            drain.await()
            assertEquals(listOf("routine" to "Accepted"), routineRepository.nameUpdates)
        } finally {
            screenScope.cancel()
            shared.dispose()
        }
    }

    @Test
    fun capturedPickerSelectionsDoNotChangeAfterAcceptance() = runTest {
        val shared = FiniteEditCoordinator(backgroundScope)
        try {
            val commitGate = CompletableDeferred<Unit>()
            val repository = object : FakeExerciseRepository() {
                override suspend fun addExercisesToWorkout(
                    workoutId: String,
                    exerciseIds: Collection<String>,
                ) {
                    commitGate.await()
                    super.addExercisesToWorkout(workoutId, exerciseIds)
                }
            }
            val screenScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val catalogue = ExerciseCatalogueState(repository, screenScope, edits = shared)
                runCurrent()
                catalogue.toggleExercise("exercise-1")
                catalogue.toggleExercise("exercise-2")
                runCurrent()

                var added = false
                catalogue.addSelectedExercises("workout") { added = true }
                runCurrent()
                // Mutate selection after acceptance but before the gated commit runs.
                catalogue.toggleExercise("exercise-3")
                runCurrent()

                commitGate.complete(Unit)
                runCurrent()
                shared.awaitPendingEdits()
                assertTrue(added)
                assertEquals(
                    listOf("exercise-1", "exercise-2"),
                    repository.addedToWorkout.single().second.toList(),
                )
            } finally {
                screenScope.cancel()
            }
        } finally {
            shared.dispose()
        }
    }

    @Test
    fun finishWaitsForSharedAcceptedEdits() = runTest {
        val shared = FiniteEditCoordinator(backgroundScope)
        try {
            val releaseName = CompletableDeferred<Unit>()
            val routineRepository = FakeRoutineRepository().apply {
                detailsProvider = { routineDetailsFixture() }
                nameUpdater = { _, _ -> releaseName.await() }
            }
            val appRepository = FakeGymAppStateRepository().apply {
                active = WorkoutEntity("active", "Lift", 1_000L)
                details = workoutDetailsFixture()
            }
            val routine = RoutineEditorState("routine", routineRepository, backgroundScope, edits = shared)
            val appState = GymAppState(appRepository, backgroundScope, edits = shared)
            runCurrent()

            routine.updateName("Shared edit")
            runCurrent()
            appState.requestFinishWorkout()
            runCurrent()
            // Destructive finish cannot overtake the accepted routine autosave.
            assertTrue(appRepository.finished.isEmpty())

            releaseName.complete(Unit)
            runCurrent()
            shared.awaitPendingEdits()
            runCurrent()
            appState.requestFinishWorkout()
            runCurrent()
            // Request opens the dialog; confirm still drains late work before capture.
            if (!appState.showFinishDialog) {
                // Shared read-only request drain may defer dialog until terminal writes settle.
                runCurrent()
            }
            assertTrue(appState.showFinishDialog)
            appState.confirmFinishWorkout()
            runCurrent()
            assertEquals(1, appRepository.finished.size)
            assertEquals(listOf("routine" to "Shared edit"), routineRepository.nameUpdates)
        } finally {
            shared.dispose()
        }
    }
}
