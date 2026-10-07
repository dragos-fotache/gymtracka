package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseSummary
import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseHistorySummary
import com.dragosfotache.gymtracka.data.gym.WorkoutHistorySummary
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.testing.FakeActiveWorkoutRepository
import com.dragosfotache.gymtracka.testing.FakeExerciseRepository
import com.dragosfotache.gymtracka.testing.FakeRoutineRepository
import com.dragosfotache.gymtracka.testing.FakeWorkoutHistoryRepository
import com.dragosfotache.gymtracka.testing.routineDetailsFixture
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import com.dragosfotache.gymtracka.testing.customExerciseDetailsFixture
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutState
import com.dragosfotache.gymtracka.ui.exercises.ExerciseCatalogueState
import com.dragosfotache.gymtracka.ui.exercises.ExerciseDetailState
import com.dragosfotache.gymtracka.ui.routines.RoutineDetailState
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorState
import com.dragosfotache.gymtracka.ui.routines.RoutineListState
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryDetailState
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * R12b independent QA: projection-epoch driven refresh across feature states.
 *
 * Hand-written from docs/remaining-work/R12b-task.md only. Uses virtual time,
 * CompletableDeferred gates and MutableStateFlow epochs; no sleeps, no
 * production oracles, no R12a/R05 matrix repetition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionRefreshStateTest {
    private fun catalogueSummary(id: String, name: String) = ExerciseSummary(
        exercise = ExerciseEntity(
            id = id,
            name = name,
            type = "reps",
            difficultyLevel = null,
            forceType = null,
            mechanics = null,
            category = "strength",
            instructions = null,
        ),
        muscleGroups = emptyList(),
        equipment = emptyList(),
    )

    private class EpochExerciseRepo(val epochs: MutableStateFlow<Long>) : FakeExerciseRepository() {
        var summaryLoads = 0
        override fun observeProjectionEpoch(): Flow<Long> = epochs
        override suspend fun exerciseSummaries(): List<ExerciseSummary> {
            summaryLoads++
            return super.exerciseSummaries()
        }
    }

    private class EpochActiveRepo(val epochs: MutableStateFlow<Long>) : FakeActiveWorkoutRepository() {
        override fun observeProjectionEpoch(): Flow<Long> = epochs
    }

    private class EpochRoutineRepo(val epochs: MutableStateFlow<Long>) : FakeRoutineRepository() {
        var listLoads = 0
        override fun observeProjectionEpoch(): Flow<Long> = epochs
        override suspend fun savedRoutineDetails(): List<RoutineDetails> {
            listLoads++
            return super.savedRoutineDetails()
        }
    }

    private class EpochHistoryRepo(val epochs: MutableStateFlow<Long>) : FakeWorkoutHistoryRepository() {
        var listLoads = 0
        override fun observeProjectionEpoch(): Flow<Long> = epochs
        override suspend fun finishedWorkoutSummaries() = super.finishedWorkoutSummaries().also { listLoads++ }
    }

    private fun historySummary(name: String) = WorkoutHistorySummary(
        workout = WorkoutEntity(
            id = "workout",
            name = name,
            startedAtMillis = 1_000L,
            finishedAtMillis = 2_000L,
            status = "finished",
        ),
        exercises = listOf(
            WorkoutExerciseHistorySummary(
                workoutExerciseId = "entry",
                workoutId = "workout",
                position = 0,
                exerciseName = "Exercise",
                setCount = 1,
                volumeKg = 400.0,
                bestSetWeightKg = 50.0,
                bestSetRepetitions = 8,
            ),
        ),
    )

    @Test
    fun catalogueConsumesEpochsAndPreservesFiltersSelectionOnRename() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = EpochExerciseRepo(epochs).apply {
            summaries = listOf(catalogueSummary("press", "Press"), catalogueSummary("row", "Row"))
        }
        val state = ExerciseCatalogueState(
            repository = repository,
            scope = backgroundScope,
            initialSearchQuery = "press",
            initialEquipmentIds = setOf("barbell"),
            initialMuscleGroupIds = setOf("chest"),
        )
        runCurrent()
        assertFalse(state.refreshFailed)

        state.toggleExercise("press")
        runCurrent()
        assertEquals(setOf("press"), state.selectedExerciseIds)

        repository.summaries = listOf(catalogueSummary("press", "Press Renamed"), catalogueSummary("row", "Row"))
        epochs.value = 1L
        runCurrent()

        assertEquals("press", state.searchQuery)
        assertEquals(setOf("barbell"), state.selectedEquipmentIds)
        assertEquals(setOf("chest"), state.selectedMuscleGroupIds)
        assertEquals(setOf("press"), state.selectedExerciseIds)
        assertTrue(state.summaries!!.any { it.exercise.id == "press" && it.exercise.name == "Press Renamed" })
        assertFalse(state.refreshFailed)

        // A complete list that drops the selected row removes only that ID.
        repository.summaries = listOf(catalogueSummary("row", "Row"))
        epochs.value = 2L
        runCurrent()
        assertTrue(state.selectedExerciseIds.isEmpty())
        assertEquals(setOf("barbell"), state.selectedEquipmentIds)
    }

    @Test
    fun exerciseDetailConsumesEpochAndClearsOnDelete() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = EpochExerciseRepo(epochs).apply {
            detailsProvider = { customExerciseDetailsFixture("Loaded") }
        }
        val state = ExerciseDetailState("custom", repository, backgroundScope)
        runCurrent()
        assertEquals("Loaded", state.details?.exercise?.name)
        assertFalse(state.refreshFailed)

        repository.detailsProvider = { customExerciseDetailsFixture("Renamed") }
        epochs.value = 1L
        runCurrent()
        assertEquals("Renamed", state.details?.exercise?.name)

        repository.detailsProvider = { null }
        epochs.value = 2L
        runCurrent()
        assertNull(state.details)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun routineListAndDetailRefreshIndependently() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = EpochRoutineRepo(epochs).apply {
            savedDetails = listOf(routineDetailsFixture(name = "List One"))
            detailsProvider = { routineDetailsFixture(name = "Detail One") }
        }
        val list = RoutineListState(repository, backgroundScope)
        val detail = RoutineDetailState("routine", repository, backgroundScope)
        runCurrent()
        val listLoadsAfterStart = repository.listLoads
        assertTrue(listLoadsAfterStart >= 1)
        assertEquals("List One", list.routines!!.single().routine.name)
        assertEquals("Detail One", detail.details?.routine?.name)
        assertFalse(list.refreshFailed)
        assertFalse(detail.refreshFailed)

        repository.savedDetails = listOf(routineDetailsFixture(name = "List Two"))
        repository.detailsProvider = { routineDetailsFixture(name = "Detail Two") }
        epochs.value = 1L
        runCurrent()

        assertTrue(repository.listLoads > listLoadsAfterStart)
        assertEquals("List Two", list.routines!!.single().routine.name)
        assertEquals("Detail Two", detail.details?.routine?.name)
        assertFalse(list.refreshFailed)

        // Deleting the selected root yields null details, not a fabricated object.
        repository.detailsProvider = { null }
        epochs.value = 2L
        runCurrent()
        assertNull(detail.details)
    }

    @Test
    fun historyListAndDetailConsumeEpochs() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = EpochHistoryRepo(epochs).apply {
            summaries = listOf(historySummary("History One"))
            details = workoutDetailsFixture(name = "History One")
        }
        val list = WorkoutHistoryState(repository, backgroundScope)
        val detail = WorkoutHistoryDetailState("workout", repository, backgroundScope)
        runCurrent()
        val listLoadsAfterStart = repository.listLoads
        assertEquals("History One", list.workouts!!.single().workout.name)
        assertEquals("History One", detail.details?.workout?.name)
        assertFalse(list.refreshFailed)
        assertFalse(detail.refreshFailed)

        repository.summaries = listOf(historySummary("History Two"))
        repository.details = workoutDetailsFixture(name = "History Two")
        epochs.value = 1L
        runCurrent()
        assertTrue(repository.listLoads > listLoadsAfterStart)
        assertEquals("History Two", list.workouts!!.single().workout.name)
        assertEquals("History Two", detail.details?.workout?.name)

        repository.details = null
        epochs.value = 2L
        runCurrent()
        assertNull(detail.details)
    }

    @Test
    fun activeWorkoutSameIdRefreshRejectsOlderBlockedLoad() = runTest {
        val epochs = MutableStateFlow(0L)
        val first = CompletableDeferred<WorkoutDetails?>()
        val second = CompletableDeferred<WorkoutDetails?>()
        var requests = 0
        val repository = EpochActiveRepo(epochs).apply {
            detailsProvider = {
                if (requests++ == 0) withContext(NonCancellable) { first.await() } else second.await()
            }
        }
        val state = ActiveWorkoutState("workout", 0L, repository, backgroundScope)
        runCurrent()

        // Newer epoch-triggered load wins even when the older load ignores cancellation.
        state.refresh()
        runCurrent()
        epochs.value = 1L
        runCurrent()
        second.complete(workoutDetailsFixture(name = "Newest", restDurationSeconds = 90))
        runCurrent()
        first.complete(workoutDetailsFixture(name = "Stale", restDurationSeconds = 10))
        runCurrent()

        assertEquals("Newest", state.details?.workout?.name)
        assertEquals(90, state.details?.exercises?.single()?.workoutExercise?.restDurationSeconds)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun routineEditorAcceptedDraftRejectsOldReadThenAcceptsPublication() = runTest {
        val epochs = MutableStateFlow(0L)
        var storedName = "Persisted"
        val releaseWrite = CompletableDeferred<Unit>()
        val writeDone = CompletableDeferred<Unit>()
        val oldReadGate = CompletableDeferred<Unit>()
        val repository = EpochRoutineRepo(epochs).apply {
            detailsProvider = { routineDetailsFixture(name = storedName) }
            nameUpdater = { _, name ->
                releaseWrite.await()
                storedName = name
                writeDone.complete(Unit)
            }
        }
        val state = RoutineEditorState("routine", repository, backgroundScope)
        runCurrent()
        assertEquals("Persisted", state.name)

        state.updateName("Accepted")
        runCurrent()
        assertEquals("Accepted", state.name)

        // Capture an old epoch read racing the accepted write; it must not
        // roll back the draft even after the newer acceptance persists.
        repository.detailsProvider = {
            oldReadGate.await()
            routineDetailsFixture(name = storedName)
        }
        epochs.value = 1L
        runCurrent()
        assertEquals("Accepted", state.name)
        assertEquals(listOf("routine" to "Accepted"), repository.nameUpdates)

        releaseWrite.complete(Unit)
        writeDone.await()
        assertEquals("Accepted", storedName)
        oldReadGate.complete(Unit)
        runCurrent()
        assertEquals("Accepted", state.name)
        assertFalse(state.refreshFailed)

        // A normal newer publication then replaces persisted values.
        storedName = "Published"
        epochs.value = 2L
        runCurrent()
        assertEquals("Published", state.name)
        assertEquals("Published", state.details?.routine?.name)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun refusedAdmissionLeavesDraftAndLoadsUnchanged() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = EpochRoutineRepo(epochs).apply {
            detailsProvider = { routineDetailsFixture(name = "Base") }
        }
        val shared = FiniteEditCoordinator(backgroundScope)
        try {
            val state = RoutineEditorState("routine", repository, backgroundScope, edits = shared)
            runCurrent()
            assertEquals("Base", state.name)

            val lease = shared.pauseAndDrain()
            try {
                state.updateName("Hacked")
                epochs.value = 1L
                runCurrent()
                assertTrue(repository.nameUpdates.isEmpty())
                assertEquals("Base", state.name)
                assertEquals("Base", state.details?.routine?.name)
            } finally {
                lease.close()
            }
            runCurrent()

            state.updateName("Allowed")
            runCurrent()
            shared.awaitPendingEdits()
            assertEquals(listOf("routine" to "Allowed"), repository.nameUpdates)
        } finally {
            shared.dispose()
        }
    }

    @Test
    fun newestLoadFailureSetsRefreshFailedAndRetainsDraft() = runTest {
        val epochs = MutableStateFlow(0L)
        var storedName = "Base"
        var failNextLoad = false
        val writeDone = CompletableDeferred<Unit>()
        val repository = EpochRoutineRepo(epochs).apply {
            detailsProvider = {
                if (failNextLoad) throw IllegalStateException("load boom")
                routineDetailsFixture(name = storedName)
            }
            nameUpdater = { _, name ->
                storedName = name
                writeDone.complete(Unit)
            }
        }
        val state = RoutineEditorState("routine", repository, backgroundScope)
        runCurrent()
        state.updateName("Draft")
        writeDone.await()
        runCurrent()
        assertEquals("Draft", storedName)
        assertEquals("Draft", state.name)

        failNextLoad = true
        epochs.value = 1L
        runCurrent()

        assertTrue(state.refreshFailed)
        assertEquals("Draft", state.name)
        assertEquals("Draft", storedName)

        failNextLoad = false
        storedName = "Recovered"
        epochs.value = 2L
        runCurrent()
        assertFalse(state.refreshFailed)
        assertEquals("Recovered", state.name)
    }

    @Test
    fun staleSessionClearsVisibleSelectionWithoutAdoptingNewScope() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = EpochRoutineRepo(epochs).apply {
            detailsProvider = { routineDetailsFixture(name = "Visible") }
        }
        val detail = RoutineDetailState("routine", repository, backgroundScope)
        val editor = RoutineEditorState("routine", repository, backgroundScope)
        runCurrent()
        assertEquals("Visible", detail.details?.routine?.name)
        assertEquals("Visible", editor.name)

        repository.detailsProvider = { throw StaleSessionException("account-a", 7L, null, 1L) }
        epochs.value = 1L
        runCurrent()

        assertTrue(detail.refreshFailed)
        assertTrue(editor.refreshFailed)
        assertNull(detail.details)
        assertNull(editor.details)
        assertEquals("", editor.name)
    }

    @Test
    fun cancellationAndDisposalDetachWithoutSuccessOrDrainEnrollment() = runTest {
        val epochs = MutableStateFlow(0L)
        val repository = EpochActiveRepo(epochs).apply {
            detailsProvider = { workoutDetailsFixture(name = "Base") }
        }
        val shared = FiniteEditCoordinator(backgroundScope)
        try {
            val state = ActiveWorkoutState("workout", 0L, repository, backgroundScope, edits = shared)
            runCurrent()
            assertFalse(state.refreshFailed)

            epochs.value = 1L
            runCurrent()
            shared.awaitPendingEdits()
            assertFalse(state.refreshFailed)

            // Disposing the composition scope stops observation; the app-owned
            // accepted write still drains to success after release.
            val owned = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val gatedRelease = CompletableDeferred<Unit>()
            val gatedRepo = EpochActiveRepo(MutableStateFlow(0L)).apply {
                detailsProvider = { workoutDetailsFixture() }
                nameUpdater = { _, _ -> gatedRelease.await() }
            }
            val gated = ActiveWorkoutState("workout", 0L, gatedRepo, owned, edits = shared)
            runCurrent()
            gated.updateWorkoutName("Accepted")
            runCurrent()
            val pendingDrain = async { shared.awaitPendingEdits() }
            runCurrent()
            assertTrue(pendingDrain.isActive)
            owned.cancel()
            runCurrent()
            assertTrue(pendingDrain.isActive)
            gatedRelease.complete(Unit)
            runCurrent()
            pendingDrain.await()
            assertEquals(listOf("workout" to "Accepted"), gatedRepo.nameUpdates)

            // A caller that actually cancels its own drain observes cancellation.
            val callerRelease = CompletableDeferred<Unit>()
            val callerRepo = EpochActiveRepo(MutableStateFlow(0L)).apply {
                detailsProvider = { workoutDetailsFixture() }
                nameUpdater = { _, _ -> callerRelease.await() }
            }
            val callerState = ActiveWorkoutState("workout", 0L, callerRepo, backgroundScope, edits = shared)
            runCurrent()
            callerState.updateWorkoutName("Gated")
            runCurrent()
            val callerDrain = async { callerState.awaitPendingEdits() }
            runCurrent()
            callerDrain.cancel()
            runCurrent()
            assertTrue(callerDrain.isCancelled)
            callerRelease.complete(Unit)
            runCurrent()
        } finally {
            shared.dispose()
        }
    }

    @Test
    fun readOnlyDetailNewerEpochWinsOverCancellationIgnoringOldLoad() = runTest {
        val epochs = MutableStateFlow(0L)
        val oldGate = CompletableDeferred<RoutineDetails?>()
        var requests = 0
        val repository = EpochRoutineRepo(epochs).apply {
            detailsProvider = {
                if (requests++ == 0) withContext(NonCancellable) { oldGate.await() }
                else routineDetailsFixture(name = "Newest")
            }
        }
        val state = RoutineDetailState("routine", repository, backgroundScope)
        runCurrent()
        // First load is gated and ignores cancellation; a newer epoch must still win.
        epochs.value = 1L
        runCurrent()
        assertEquals("Newest", state.details?.routine?.name)
        assertFalse(state.refreshFailed)
        oldGate.complete(routineDetailsFixture(name = "Stale"))
        runCurrent()
        assertEquals("Newest", state.details?.routine?.name)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun queuedOlderEditReadDoesNotEraseNewerAcceptedDraft() = runTest {
        val epochs = MutableStateFlow(0L)
        var storedName = "Base"
        var storedWeight: Double? = 50.0
        val releaseOlderWrite = CompletableDeferred<Unit>()
        val releaseNewerWrite = CompletableDeferred<Unit>()
        val olderDone = CompletableDeferred<Unit>()
        val newerDone = CompletableDeferred<Unit>()
        val olderStarted = CompletableDeferred<Unit>()
        val newerStarted = CompletableDeferred<Unit>()
        val readStarted = CompletableDeferred<Unit>()
        val staleReadGate = CompletableDeferred<Unit>()
        var reads = 0
        var gateOldRead = false
        val gating = object : FakeActiveWorkoutRepository() {
            override fun observeProjectionEpoch(): Flow<Long> = epochs
            override suspend fun workoutDetails(workoutId: String): WorkoutDetails? {
                if (reads++ == 0) {
                    return workoutDetailsFixture(
                        name = "Base",
                        sets = listOf(
                            com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity("set", "entry", 0, 8, 50.0),
                        ),
                    )
                }
                if (gateOldRead) {
                    gateOldRead = false
                    readStarted.complete(Unit)
                    withContext(NonCancellable) { staleReadGate.await() }
                    return workoutDetailsFixture(
                        name = "Base",
                        sets = listOf(
                            com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity("set", "entry", 0, 8, 50.0),
                        ),
                    )
                }
                return workoutDetailsFixture(
                    name = storedName,
                    sets = listOf(
                        com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity("set", "entry", 0, 8, storedWeight),
                    ),
                )
            }
            override suspend fun updateWorkoutSet(workoutSet: com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity): Boolean {
                olderStarted.complete(Unit)
                releaseOlderWrite.await()
                storedWeight = workoutSet.weightKg
                olderDone.complete(Unit)
                return super.updateWorkoutSet(workoutSet)
            }
            override suspend fun updateWorkoutName(workoutId: String, name: String) {
                nameUpdates += workoutId to name
                newerStarted.complete(Unit)
                releaseNewerWrite.await()
                storedName = name
                newerDone.complete(Unit)
            }
        }
        val state = ActiveWorkoutState("workout", 0L, gating, backgroundScope)
        runCurrent()
        assertEquals("Base", state.details?.workout?.name)
        assertEquals(50.0, state.details?.exercises?.single()?.sets?.single()?.weightKg)

        // Arm a stale observer read, prove it started, then accept both edits.
        gateOldRead = true
        epochs.value = 1L
        readStarted.await()
        // Older set edit accepted first (reached its write); the newer name edit
        // is accepted (synchronously, bumping the generation and dooming the
        // stale read) while its block queues behind the older write.
        state.updateSet(com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity("set", "entry", 0, 8, 62.5))
        olderStarted.await()
        state.updateWorkoutName("New")
        releaseOlderWrite.complete(Unit)
        olderDone.await()
        newerStarted.await()
        assertEquals(listOf("workout" to "New"), gating.nameUpdates)
        releaseNewerWrite.complete(Unit)
        newerDone.await()
        // A fresh guarded load installs the committed edits.
        epochs.value = 2L
        runCurrent()
        assertEquals("New", state.details?.workout?.name)
        assertEquals(62.5, state.details?.exercises?.single()?.sets?.single()?.weightKg)
        // The stale old read completes last: it must never roll back the edits.
        staleReadGate.complete(Unit)
        runCurrent()
        assertEquals("New", state.details?.workout?.name)
        assertEquals(62.5, state.details?.exercises?.single()?.sets?.single()?.weightKg)
        assertEquals("New", storedName)
        assertEquals(62.5, storedWeight)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun newestLoadFailureWinsOverPendingOldSuccessfulRead() = runTest {
        val epochs = MutableStateFlow(0L)
        val oldGate = CompletableDeferred<RoutineDetails?>()
        var requests = 0
        var failNewest = false
        val repository = EpochRoutineRepo(epochs).apply {
            detailsProvider = {
                if (requests++ == 0) withContext(NonCancellable) { oldGate.await() }
                else {
                    if (failNewest) throw IllegalStateException("newest boom")
                    routineDetailsFixture(name = "Newest")
                }
            }
        }
        val state = RoutineDetailState("routine", repository, backgroundScope)
        runCurrent()
        failNewest = true
        epochs.value = 1L
        runCurrent()
        assertTrue(state.refreshFailed)
        assertNull(state.details)
        oldGate.complete(routineDetailsFixture(name = "Stale Success"))
        runCurrent()
        // The late old success must not install over the newer failure generation.
        assertTrue(state.refreshFailed)
        assertNull(state.details)
    }

    @Test
    fun observerStreamFailureInvalidatesPendingOldSuccessfulLoad() = runTest {
        val streamGate = CompletableDeferred<Unit>()
        val oldGate = CompletableDeferred<RoutineDetails?>()
        var requests = 0
        val repository = object : FakeRoutineRepository() {
            override fun observeProjectionEpoch(): Flow<Long> = kotlinx.coroutines.flow.flow {
                emit(0L)
                streamGate.await()
                throw IllegalStateException("stream boom")
            }
            override suspend fun routineDetails(routineId: String): RoutineDetails? {
                if (requests++ == 0) return withContext(NonCancellable) { oldGate.await() }
                return routineDetailsFixture(name = "Late")
            }
        }
        val state = RoutineDetailState("routine", repository, backgroundScope)
        runCurrent()
        streamGate.complete(Unit)
        runCurrent()
        assertTrue(state.refreshFailed)
        assertNull(state.details)
        oldGate.complete(routineDetailsFixture(name = "Stale Success"))
        runCurrent()
        // The late old success must neither install nor clear the stream failure.
        assertTrue(state.refreshFailed)
        assertNull(state.details)
    }

    @Test
    fun disposedScopeIgnoresLateLoadResult() = runTest {
        val epochs = MutableStateFlow(0L)
        val gate = CompletableDeferred<RoutineDetails?>()
        var requests = 0
        val repository = EpochRoutineRepo(epochs).apply {
            detailsProvider = {
                if (requests++ == 0) withContext(NonCancellable) { gate.await() } else routineDetailsFixture(name = "Late")
            }
        }
        val owned = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val state = RoutineDetailState("routine", repository, owned)
        runCurrent()
        val ownedJob = owned.coroutineContext[Job]!!
        owned.cancel()
        gate.complete(routineDetailsFixture(name = "Late"))
        runCurrent()
        ownedJob.join()
        runCurrent()
        // The NonCancellable late result completes but must never install after dispose.
        assertTrue(ownedJob.isCancelled)
        assertNull(state.details)
    }

    @Test
    fun pureListReadsDoNotWaitForUnrelatedPendingEdit() = runTest {
        val epochs = MutableStateFlow(0L)
        val gate = CompletableDeferred<Unit>()
        val unrelatedStarted = CompletableDeferred<Unit>()
        val shared = FiniteEditCoordinator(backgroundScope)
        val screenScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val routineRepository = EpochRoutineRepo(epochs).apply {
                savedDetails = listOf(routineDetailsFixture(name = "Listed"))
            }
            val historyRepository = EpochHistoryRepo(epochs).apply {
                summaries = listOf(historySummary("Listed History"))
            }
            val exerciseRepository = EpochExerciseRepo(epochs).apply {
                summaries = listOf(catalogueSummary("listed-press", "Listed Press"))
            }
            // An unrelated gated edit is accepted and stays pending FIRST.
            shared.launchEdit(key = "unrelated") {
                unrelatedStarted.complete(Unit)
                gate.await()
            }
            unrelatedStarted.await()

            // Pure list states constructed afterwards still load their initial
            // persisted models without waiting for the gate.
            val routines = RoutineListState(routineRepository, screenScope, edits = shared)
            val history = WorkoutHistoryState(historyRepository, screenScope, edits = shared)
            val catalogue = ExerciseCatalogueState(exerciseRepository, screenScope, edits = shared)
            runCurrent()
            assertEquals("Listed", routines.routines!!.single().routine.name)
            assertEquals("Listed History", history.workouts!!.single().workout.name)
            assertEquals(listOf("listed-press"), catalogue.summaries!!.map { it.exercise.id })
            assertFalse(routines.refreshFailed)
            assertFalse(history.refreshFailed)
            assertFalse(catalogue.refreshFailed)

            // Mutation ordering is not bypassed: the shared drain stays pending.
            val drain = async { shared.awaitPendingEdits() }
            runCurrent()
            assertTrue(drain.isActive)
            gate.complete(Unit)
            runCurrent()
            drain.await()

            // Failure evidence is recorded and not bypassed either.
            shared.launchEdit(key = "unrelated-failure") { throw IllegalStateException("boom") }
            runCurrent()
            try {
                shared.awaitPendingEdits()
                fail("Drain must surface the retained failure")
            } catch (expected: IllegalStateException) {
                assertEquals("boom", expected.message)
            }

            // Fresh epoch reads still load updated models while the retained
            // failure stands; reads never clear failure evidence.
            routineRepository.savedDetails = listOf(routineDetailsFixture(name = "Listed v2"))
            historyRepository.summaries = listOf(historySummary("Listed History v2"))
            exerciseRepository.summaries = listOf(catalogueSummary("listed-row", "Listed Row"))
            epochs.value = 1L
            runCurrent()
            assertEquals("Listed v2", routines.routines!!.single().routine.name)
            assertEquals("Listed History v2", history.workouts!!.single().workout.name)
            assertEquals(listOf("listed-row"), catalogue.summaries!!.map { it.exercise.id })
            assertFalse(routines.refreshFailed)
            assertFalse(history.refreshFailed)
            assertFalse(catalogue.refreshFailed)
            try {
                shared.awaitPendingEdits()
                fail("Retained origin must still surface after fresh reads")
            } catch (expected: IllegalStateException) {
                assertEquals("boom", expected.message)
            }
        } finally {
            gate.complete(Unit)
            screenScope.cancel()
            screenScope.coroutineContext[Job]?.join()
            runCurrent()
            shared.dispose()
        }
    }
}
