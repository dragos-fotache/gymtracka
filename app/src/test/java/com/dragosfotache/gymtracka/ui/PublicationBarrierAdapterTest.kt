package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.testing.FakeGymAppStateRepository
import com.dragosfotache.gymtracka.testing.workoutDetailsFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PublicationBarrierAdapterTest {
    private companion object {
        const val ACCOUNT = "account-a"
        const val GENERATION = 3L
        const val EPOCH = 7L
    }

    private fun completedSet() = WorkoutSetEntity("set", "entry", 0, 8, 50.0).copy(isCompleted = true)

    @Test
    fun exactRequestBindingRejectsMismatch() = runTest {
        val repository = FakeGymAppStateRepository()
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION))
        runCurrent()
        val expected = ProjectionPublicationRequest(ACCOUNT, GENERATION, EPOCH)
        val barrier = state.publicationBarrier(expected) { true }

        val lease = barrier.acquire(expected)
        lease.close()

        val mismatches = listOf(
            expected.copy(accountId = "other-account"),
            expected.copy(sessionGeneration = GENERATION + 1),
            expected.copy(expectedCacheEpoch = EPOCH + 1),
            expected.copy(expectedCacheEpoch = -1L),
            ProjectionPublicationRequest(ACCOUNT, 0L, EPOCH),
        )
        mismatches.forEach { mismatched ->
            try {
                barrier.acquire(mismatched).close()
                fail("Mismatched request must be rejected: $mismatched")
            } catch (_: Exception) {
                // Exact binding rejects any mismatch; concrete type is an adapter detail.
            }
        }
    }

    @Test
    fun timerSessionMismatchFailsClosed() = runTest {
        val repository = FakeGymAppStateRepository()
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION))
        runCurrent()
        state.startRestTimer(completedSet(), 30, acceptedEdit = true, expectedWorkoutId = "workout")
        runCurrent()
        val timer = state.activeRestTimer!!
        assertEquals(RepositorySession(ACCOUNT, GENERATION), timer.session)

        val foreign = ProjectionPublicationRequest("other-account", GENERATION, EPOCH)
        val barrier = state.publicationBarrier(foreign) { true }
        try {
            barrier.acquire(foreign).close()
            fail("Foreign session must fail closed when a bound timer is active")
        } catch (_: Exception) {
            // Fail-closed on session difference.
        }
        // Failed acquisition must release the pause.
        runCurrent()
        assertFalse(state.edits.isPaused)
        assertEquals(timer, state.activeRestTimer)
    }

    @Test
    fun impactFalseClaimsTerminalRestExactlyOnceBeforeLeaseReturn() = runTest {
        val repository = FakeGymAppStateRepository()
        val hardware = FakeTimerHardware()
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION), hardware = hardware)
        runCurrent()
        state.startRestTimer(completedSet(), 30, acceptedEdit = true, expectedWorkoutId = "workout")
        runCurrent()
        assertTrue(hardware.isHeld)

        val expected = ProjectionPublicationRequest(ACCOUNT, GENERATION, EPOCH)
        var impactCalls = 0
        val barrier = state.publicationBarrier(expected) {
            impactCalls++
            false
        }
        val lease = barrier.acquire(expected)
        try {
            assertEquals(1, impactCalls)
            // Old timer is terminated and its rest is captured exactly once.
            assertNull(state.activeRestTimer)
            assertEquals(listOf("set" to 0), repository.restUpdates)
            assertEquals(listOf("set" to 0), repository.committedRestUpdates)
            lease.checkValid()
        } finally {
            lease.close()
        }
    }

    @Test
    fun impactTrueRetainsIdentityCountdownAndHardware() = runTest {
        val repository = FakeGymAppStateRepository()
        val hardware = FakeTimerHardware()
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION), hardware = hardware)
        runCurrent()
        state.startRestTimer(completedSet(), 30, acceptedEdit = true, expectedWorkoutId = "workout")
        val before = state.activeRestTimer!!
        val remainingBefore = state.restTimerRemainingSeconds
        runCurrent()
        assertTrue(hardware.isHeld)

        val expected = ProjectionPublicationRequest(ACCOUNT, GENERATION, EPOCH)
        val barrier = state.publicationBarrier(expected) { timer ->
            assertSame(before, timer)
            true
        }
        val lease = barrier.acquire(expected)
        try {
            lease.checkValid()
            assertSame(before, state.activeRestTimer)
            assertEquals(remainingBefore, state.restTimerRemainingSeconds)
            assertTrue(hardware.isHeld)
            assertTrue(repository.restUpdates.isEmpty())
        } finally {
            lease.close()
        }
        // After close the retained timer still runs.
        assertSame(before, state.activeRestTimer)
        assertTrue(hardware.isHeld)
    }

    @Test
    fun impactFailureOrCancellationReleasesPause() = runTest {
        val repository = FakeGymAppStateRepository()
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION))
        runCurrent()
        state.startRestTimer(completedSet(), 30, acceptedEdit = true, expectedWorkoutId = "workout")
        val bound = state.activeRestTimer!!
        runCurrent()
        val expected = ProjectionPublicationRequest(ACCOUNT, GENERATION, EPOCH)

        val failing = state.publicationBarrier(expected) { timer ->
            assertSame(bound, timer)
            throw IllegalStateException("impact failed")
        }
        try {
            failing.acquire(expected).close()
            fail("Impact failure must propagate")
        } catch (expectedFailure: IllegalStateException) {
            assertEquals("impact failed", expectedFailure.message)
        }
        runCurrent()
        assertFalse(state.edits.isPaused)
        assertSame(bound, state.activeRestTimer)

        val cancellable = state.publicationBarrier(expected) { timer ->
            assertSame(bound, timer)
            throw kotlinx.coroutines.CancellationException("impact cancelled")
        }
        try {
            cancellable.acquire(expected).close()
            fail("Impact cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected.
        }
        runCurrent()
        assertFalse(state.edits.isPaused)
        assertSame(bound, state.activeRestTimer)

        // Coordinator is usable again after the failed impact.
        val ok = state.publicationBarrier(expected) { true }
        ok.acquire(expected).close()
    }

    @Test
    fun expiryDuringLeaseInvalidatesBeforeTerminalWrite() = runTest {
        val repository = FakeGymAppStateRepository()
        val writeGate = CompletableDeferred<Unit>()
        repository.restUpdate = { _, _ -> writeGate.await() }
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION))
        runCurrent()
        state.startRestTimer(completedSet().copy(), 1, acceptedEdit = true, expectedWorkoutId = "workout")
        runCurrent()

        val expected = ProjectionPublicationRequest(ACCOUNT, GENERATION, EPOCH)
        val barrier = state.publicationBarrier(expected) { true }
        val lease = barrier.acquire(expected)
        try {
            lease.checkValid()
            advanceTimeBy(1.seconds)
            runCurrent()
            try {
                lease.checkValid()
                fail("Expiry during lease use must invalidate before rest persistence")
            } catch (_: ProjectionLeaseInvalidException) {
                // Expected invalidation before the terminal write.
            }
        } finally {
            lease.close()
            // Unblock the gated terminal write so the test scope can settle.
            writeGate.complete(Unit)
            runCurrent()
        }
        // Terminal rest is still attempted exactly once and stays observable.
        assertEquals(listOf("set" to 1), repository.restUpdates)
    }

    @Test
    fun terminalFailuresDrainWithoutRunningTimerAndStayObservable() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            restUpdate = { _, _ -> throw IllegalStateException("terminal failed") }
        }
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION))
        runCurrent()
        state.startRestTimer(completedSet().copy(), 1, acceptedEdit = true, expectedWorkoutId = "workout")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(state.activeRestTimer)
        assertEquals(1, repository.restUpdates.size)
        assertTrue(repository.committedRestUpdates.isEmpty())

        val expected = ProjectionPublicationRequest(ACCOUNT, GENERATION, EPOCH)
        val barrier = state.publicationBarrier(expected) { true }
        try {
            barrier.acquire(expected).close()
            fail("Terminal origin failure must block publication")
        } catch (expectedFailure: IllegalStateException) {
            assertEquals("terminal failed", expectedFailure.message)
        }
        runCurrent()
        assertFalse(state.edits.isPaused)
        // No duplicate retry: still exactly one attempt.
        assertEquals(1, repository.restUpdates.size)
    }

    @Test
    fun acceptedCompletionTimerDuringPreDrainSessionMismatchFailsClosed() = runTest {
        val repository = FakeGymAppStateRepository()
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION))
        runCurrent()
        assertNull(state.activeRestTimer)

        // Already accepted tracked completion, gated until the publication pre-drain.
        val completionGate = CompletableDeferred<Unit>()
        state.edits.launchEdit(key = "tracked-completion") {
            completionGate.await()
            state.startRestTimer(completedSet(), 30, acceptedEdit = true, expectedWorkoutId = "workout")
        }
        runCurrent()

        val foreign = ProjectionPublicationRequest("other-account", GENERATION, EPOCH)
        val barrier = state.publicationBarrier(foreign) { true }
        // Capture the expected failure inside the child so it cannot cancel the harness parent.
        val acquisition = async {
            try {
                barrier.acquire(foreign).close()
                null as Throwable?
            } catch (expected: Exception) {
                expected
            }
        }
        runCurrent()
        completionGate.complete(Unit)
        runCurrent()
        val acquisitionFailure = acquisition.await()
        assertTrue(acquisitionFailure != null)
        runCurrent()
        assertFalse(state.edits.isPaused)
        val timer = state.activeRestTimer
        assertTrue(timer != null)
        assertEquals(RepositorySession(ACCOUNT, GENERATION), timer!!.session)
        assertTrue(repository.restUpdates.isEmpty())
    }

    @Test
    fun failedTerminalRestBlocksRepeatedFinishAndPublicationOnce() = runTest {
        val repository = FakeGymAppStateRepository().apply {
            restUpdate = { _, _ -> throw IllegalStateException("terminal failed") }
        }
        val state = state(repository, session = RepositorySession(ACCOUNT, GENERATION))
        runCurrent()
        state.startRestTimer(completedSet().copy(), 1, acceptedEdit = true, expectedWorkoutId = "workout")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(state.activeRestTimer)
        assertEquals(1, repository.restUpdates.size)
        assertTrue(repository.committedRestUpdates.isEmpty())

        state.requestFinishWorkout()
        runCurrent()
        state.confirmFinishWorkout()
        runCurrent()
        assertTrue(repository.finished.isEmpty())
        assertEquals(1, repository.restUpdates.size)

        // Repeated confirm must not retry the terminal write nor finish.
        state.confirmFinishWorkout()
        runCurrent()
        assertTrue(repository.finished.isEmpty())
        assertEquals(1, repository.restUpdates.size)
        assertTrue(repository.committedRestUpdates.isEmpty())

        val expected = ProjectionPublicationRequest(ACCOUNT, GENERATION, EPOCH)
        val barrier = state.publicationBarrier(expected) { true }
        try {
            barrier.acquire(expected).close()
            fail("Failed terminal must block publication")
        } catch (expectedFailure: IllegalStateException) {
            assertEquals("terminal failed", expectedFailure.message)
        }
        runCurrent()
        assertTrue(repository.finished.isEmpty())
        assertEquals(1, repository.restUpdates.size)
    }

    @Test
    fun acquisitionCancelledBeforeResultDispatchReleasesPause() = runTest {
        val repository = FakeGymAppStateRepository()
        val ownerQueue = ManualQueueDispatcher()
        val callerQueue = ManualQueueDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + ownerQueue)
        val callerScope = CoroutineScope(SupervisorJob() + callerQueue)
        try {
            val state = GymAppState(
                repository,
                ownerScope,
                VirtualClock(testScheduler),
                FakeTimerHardware(),
                session = RepositorySession(ACCOUNT, GENERATION),
            )
            ownerQueue.drain()
            val expected = ProjectionPublicationRequest(ACCOUNT, GENERATION, EPOCH)
            val barrier = state.publicationBarrier(expected) { true }
            val acquisition = callerScope.async { barrier.acquire(expected) }
            // Run caller until acquire enqueues owner work and suspends.
            callerQueue.drain()
            // Run owner until the grant schedules the caller return.
            ownerQueue.drain()
            assertTrue(state.edits.isPaused)
            // Cancel before the caller result dispatch resumes.
            acquisition.cancel()
            callerQueue.drain()
            ownerQueue.drain()
            try {
                acquisition.await()
                fail("Cancelled acquisition must throw")
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected handoff cancellation.
            }
            assertFalse(state.edits.isPaused)
            assertTrue(repository.finished.isEmpty())
            assertTrue(repository.restUpdates.isEmpty())
            // Parent isolation: the test scope still runs.
            val probe = CompletableDeferred<Unit>()
            backgroundScope.launch { probe.complete(Unit) }
            runCurrent()
            assertTrue(probe.isCompleted)
        } finally {
            ownerScope.cancel()
            callerScope.cancel()
            ownerQueue.drain()
            callerQueue.drain()
        }
    }

    private class ManualQueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.state(
        repository: FakeGymAppStateRepository,
        session: RepositorySession = RepositorySession.SIGNED_OUT,
        clock: GymAppClock = VirtualClock(testScheduler),
        hardware: RestTimerHardware = FakeTimerHardware(),
    ): GymAppState {
        val detailsRepository = repository.apply {
            if (active == null) active = WorkoutEntity("workout", "Lift", 1_000L)
            if (details == null) {
                details = workoutDetailsFixture(
                    sets = listOf(WorkoutSetEntity("set", "entry", 0, 8, 50.0).copy(isCompleted = true)),
                )
            }
        }
        return GymAppState(detailsRepository, backgroundScope, clock, hardware, session = session)
    }

    private class VirtualClock(private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) : GymAppClock {
        override fun wallTimeMillis() = 1_700_000_000_000L + scheduler.currentTime
        override fun elapsedRealtimeMillis() = scheduler.currentTime
    }

    private class FakeTimerHardware : RestTimerHardware {
        var releaseCount = 0
        var isHeld = false
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) {
            isHeld = true
        }
        override fun releaseWakeLock() {
            if (isHeld) {
                releaseCount++
                isHeld = false
            }
        }
    }
}
