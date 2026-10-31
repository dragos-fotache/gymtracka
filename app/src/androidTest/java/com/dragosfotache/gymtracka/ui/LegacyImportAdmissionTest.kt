package com.dragosfotache.gymtracka.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransitionBarrier
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the real import transition barrier: accepted-autosave drain, once-only whole-second rest,
 * explicit-save refusal before/after drain, cancellation and lease ownership. Observations use
 * durable Room rows and stable destinations.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LegacyImportAdmissionTest : GymDatabaseTest() {

    private class FakeClock(var elapsed: Long = 0L) : GymAppClock {
        override fun wallTimeMillis(): Long = 1_700_000_000_000L + elapsed
        override fun elapsedRealtimeMillis(): Long = elapsed
    }

    private class FakeHardware : RestTimerHardware {
        var releases = 0
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) = Unit
        override fun releaseWakeLock() {
            releases++
        }
    }

    private fun kotlinx.coroutines.test.TestScope.liveState(
        clock: FakeClock = FakeClock(),
        hardware: FakeHardware = FakeHardware(),
    ): GymAppState =
        GymAppState(
            repository,
            backgroundScope,
            clock,
            hardware,
            session = RepositorySession.SIGNED_OUT,
        )

    private fun barrier(state: GymAppState): AppSessionTransitionBarrier =
        state.legacyImportTransitionBarrier(processCheck = {})

    private fun restSeconds(setId: String): Long? =
        database.openHelper.readableDatabase.query(
            "SELECT rest_seconds FROM workout_sets WHERE id = ?",
            arrayOf(setId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "seeded set must exist" }
            if (cursor.isNull(0)) null else cursor.getLong(0)
        }

    private suspend fun seedCompletedSet(): Triple<String, String, String> {
        val customId = repository.createCustomExercise("import Timer Press", null, emptySet(), null, null, "")
        val workout = repository.startEmptyWorkout(1_700_000_000_000L)
        repository.addExercisesToWorkout(workout.id, listOf(customId))
        val entry = checkNotNull(repository.workoutDetails(workout.id)).exercises.single()
        val set = entry.sets.single()
        assertTrue(repository.updateWorkoutSet(set.copy(repetitions = 8, weightKg = 55.0, isCompleted = true)))
        return Triple(workout.id, entry.workoutExercise.id, set.id)
    }

    @Test
    fun pendingAutosave_drainsBeforeLeaseAndRefusesNewCallbacksWhileHeld() =
        runTest {
            val state = liveState()
            val seedWorkout = repository.startEmptyWorkout(1_700_000_000_000L)
            val gate = CompletableDeferred<Unit>()
            var drained = false
            val pending = state.edits.launchEdit(
                key = "import-autosave",
                onAccepted = {},
            ) {
                gate.await()
                repository.updateWorkoutName(seedWorkout.id, "Drained Name")
                drained = true
            }
            assertNotNull(pending)
            // Real pending work: complete it through the drain, not around it.
            val acquire = async { barrier(state).acquire(RepositorySession.SIGNED_OUT) }
            runCurrent()
            assertTrue("drain must wait for pending work", acquire.isActive)
            gate.complete(Unit)
            val lease = acquire.await()
            try {
                assertTrue(drained)
                assertEquals(
                    "Drained Name",
                    checkNotNull(repository.workoutDetails(seedWorkout.id)).workout.name,
                )
                val refused = state.edits.launchEdit(key = "import-late", onAccepted = {}) {}
                runCurrent()
                assertNull("new callbacks refused while held", refused)
            } finally {
                lease.close()
            }
            runCurrent()
            val after = state.edits.launchEdit(key = "import-after", onAccepted = {}) {}
            assertNotNull("admission released after close", after)
        }

    @Test
    fun runningRest_claimedExactlyOnceWholeSecondsOnDrain() =
        runTest {
            val clock = FakeClock()
            val state = liveState(clock = clock)
            val (workoutId, _, setId) = seedCompletedSet()
            runCurrent()
            val details = checkNotNull(repository.workoutDetails(workoutId))
            state.startRestTimer(
                details.exercises.single().sets.single(),
                90,
                acceptedEdit = true,
                expectedWorkoutId = workoutId,
            )
            runCurrent()
            assertNull(restSeconds(setId))
            clock.elapsed += 5_000L
            val lease = barrier(state).acquire(RepositorySession.SIGNED_OUT)
            runCurrent()
            try {
                assertEquals(5L, restSeconds(setId))
                assertNull(state.activeRestTimer)
            } finally {
                lease.close()
            }
            assertEquals("no second claim may rewrite rest", 5L, restSeconds(setId))
        }

    @Test
    fun failedProcessCheck_releasesAdmissionAndKeepsEditing() =
        runTest {
            val state = liveState()
            val failing: AppSessionTransitionBarrier =
                state.legacyImportTransitionBarrier(
                    processCheck = { throw IllegalStateException("import-process-invalid") },
                )
            try {
                failing.acquire(RepositorySession.SIGNED_OUT)
                runCurrent()
                fail("invalid process must block acquisition")
            } catch (e: IllegalStateException) {
                assertEquals("import-process-invalid", e.message)
            }
            // Admission released: a valid acquire succeeds and editing survives.
            val lease = barrier(state).acquire(RepositorySession.SIGNED_OUT)
            lease.close()
            val edit = state.edits.launchEdit(key = "import-healthy", onAccepted = {}) {}
            assertNotNull(edit)
        }

    @Test
    fun cancelledDrain_propagatesWithoutLeaseAndNextAcquireSucceeds() =
        runTest {
            val state = liveState()
            val gate = CompletableDeferred<Unit>()
            var workRan = false
            val pending = state.edits.launchEdit(key = "import-gated", onAccepted = {}) {
                gate.await()
                workRan = true
            }
            assertNotNull(pending)
            val acquire = async { barrier(state).acquire(RepositorySession.SIGNED_OUT) }
            runCurrent()
            assertTrue(acquire.isActive)
            acquire.cancel(CancellationException("import-test-cancel"))
            runCurrent()
            try {
                acquire.await()
                fail("cancelled drain must propagate")
            } catch (_: CancellationException) {
            }
            assertTrue("no lease granted on cancellation", !workRan)
            gate.complete(Unit)
            runCurrent()
            assertTrue(workRan)
            val lease = barrier(state).acquire(RepositorySession.SIGNED_OUT)
            lease.checkValid()
            lease.close()
        }

    @Test
    fun openCreateEditor_refusesBeforeAndAfterDrainUnchanged() =
        runTest {
            val state = liveState()
            state.showCreateExercise()
            assertEquals(Destination.CreateExercise, state.destination)
            try {
                barrier(state).acquire(RepositorySession.SIGNED_OUT)
                runCurrent()
                fail("open explicit-save editor must refuse barrier acquisition")
            } catch (_: Exception) {
            }
            assertEquals(Destination.CreateExercise, state.destination)
            state.closeCreateExercise()
            val lease = barrier(state).acquire(RepositorySession.SIGNED_OUT)
            lease.close()
        }

    @Test
    fun openEditEditor_refusesUnchangedWithoutAutosaveOrDiscard() =
        runTest {
            val state = liveState()
            val customId = repository.createCustomExercise("import Draft", null, emptySet(), null, null, "")
            val before = database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM exercises WHERE is_user_created != 0",
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            state.editCustomExercise(customId)
            assertEquals(Destination.EditCustomExercise, state.destination)
            try {
                barrier(state).acquire(RepositorySession.SIGNED_OUT)
                runCurrent()
                fail("open explicit-save editor must refuse barrier acquisition")
            } catch (_: Exception) {
            }
            assertEquals(Destination.EditCustomExercise, state.destination)
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM exercises WHERE is_user_created != 0",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(before, cursor.getInt(0))
            }
            state.closeEditCustomExercise()
            val lease = barrier(state).acquire(RepositorySession.SIGNED_OUT)
            lease.close()
        }
}
