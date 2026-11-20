package com.dragosfotache.gymtracka.ui.exercises

import com.dragosfotache.gymtracka.data.gym.ExerciseHistory
import com.dragosfotache.gymtracka.data.gym.ExerciseHistoryStateRepository
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseHistoryStateTest {
    @Test
    fun newestProjectionWinsEvenWhenAnOlderReadFinishesLater() = runTest {
        val repository = FakeHistoryRepository()
        val oldRead = CompletableDeferred<ExerciseHistory?>()
        var calls = 0
        repository.read = { if (calls++ == 0) oldRead.await() else history("New name") }
        val state = ExerciseHistoryState("press", repository, backgroundScope)
        runCurrent()
        repository.epoch.value = 1
        runCurrent()
        assertEquals("New name", state.history?.exerciseName)
        oldRead.complete(history("Old name"))
        runCurrent()
        assertEquals("New name", state.history?.exerciseName)
        assertEquals(listOf("press", "press"), repository.requests)
        assertFalse(state.isLoading)
    }

    @Test
    fun retryRecoversFromFailureAndRevocationClearsVisibleHistory() = runTest {
        val repository = FakeHistoryRepository()
        repository.read = { error("offline") }
        val state = ExerciseHistoryState("press", repository, backgroundScope)
        runCurrent()
        assertTrue(state.refreshFailed)
        assertFalse(state.isLoading)
        repository.read = { history("Press") }
        state.refresh()
        runCurrent()
        assertFalse(state.refreshFailed)
        assertEquals("Press", state.history?.exerciseName)
        repository.read = { throw StaleSessionException("a", 7, null, 8) }
        repository.epoch.value = 1
        runCurrent()
        assertNull(state.history)
        assertTrue(state.refreshFailed)
    }

    @Test
    fun aRemovedExerciseResolvesToUnavailableInsteadOfLoadingForever() = runTest {
        val repository = FakeHistoryRepository()
        val state = ExerciseHistoryState("press", repository, backgroundScope)
        runCurrent()
        repository.read = { null }
        repository.epoch.value = 1
        runCurrent()
        assertNull(state.history)
        assertFalse(state.isLoading)
        assertFalse(state.refreshFailed)
    }

    private fun history(name: String) = ExerciseHistory("press", name, emptyList())

    private class FakeHistoryRepository : ExerciseHistoryStateRepository {
        val epoch = MutableStateFlow(0L)
        val requests = mutableListOf<String>()
        var read: suspend () -> ExerciseHistory? = { ExerciseHistory("press", "Press", emptyList()) }
        override fun observeProjectionEpoch() = epoch
        override suspend fun exerciseHistory(exerciseId: String): ExerciseHistory? {
            requests += exerciseId
            return read()
        }
    }
}
