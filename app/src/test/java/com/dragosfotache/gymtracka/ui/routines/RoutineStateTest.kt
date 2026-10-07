package com.dragosfotache.gymtracka.ui.routines

import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.testing.FakeRoutineRepository
import com.dragosfotache.gymtracka.testing.routineDetailsFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineStateTest {
    @Test
    fun refresh_ignoresAStaleEarlierLoad() = runTest {
        val first = CompletableDeferred<RoutineDetails?>()
        val second = CompletableDeferred<RoutineDetails?>()
        var request = 0
        val repository = FakeRoutineRepository().apply {
            detailsProvider = { if (request++ == 0) first.await() else second.await() }
        }
        val state = RoutineEditorState("routine", repository, this)
        runCurrent()

        state.refresh()
        runCurrent()
        second.complete(routineDetailsFixture(name = "Newest"))
        runCurrent()
        first.complete(routineDetailsFixture(name = "Stale"))
        runCurrent()

        assertEquals("Newest", state.details?.routine?.name)
        assertEquals("Newest", state.name)
    }

    @Test
    fun nameAutosaves_arePersistedInCallOrder() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var storedName = "Routine"
        val repository = FakeRoutineRepository().apply {
            detailsProvider = { routineDetailsFixture(name = storedName) }
            nameUpdater = { _, name ->
                if (name == "First") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                storedName = name
            }
        }
        val state = RoutineEditorState("routine", repository, this)
        runCurrent()

        state.updateName("First")
        runCurrent()
        firstStarted.await()
        state.updateName("Second")
        runCurrent()

        assertEquals(listOf("First"), repository.nameUpdates.map { it.second })
        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("First", "Second"), repository.nameUpdates.map { it.second })
        assertEquals("Second", state.name)
    }

    @Test
    fun saveValidationTrimsName_andAddSetCopiesPreviousInputs() = runTest {
        val repository = FakeRoutineRepository().apply {
            detailsProvider = { routineDetailsFixture() }
        }
        val state = RoutineEditorState("routine", repository, this)
        runCurrent()

        state.updateName("   ")
        state.requestSave {}
        assertTrue(state.showNameRequiredDialog)
        state.dismissNameRequiredDialog()

        state.updateName("  Updated  ")
        var saved = false
        state.requestSave { saved = true }
        state.addSet(state.details!!.exercises.single())
        advanceUntilIdle()

        assertEquals(listOf("routine" to "Updated"), repository.savedNames)
        assertTrue(saved)
        assertEquals(8, repository.addedSets.single().repetitions)
        assertEquals(50.0, repository.addedSets.single().weightKg)
        assertEquals(1, repository.addedSets.single().position)
    }
}
