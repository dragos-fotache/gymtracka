package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.muscleGroupFixture
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * real-Room projection observation.
 */
class ProjectionObservationTest : GymDatabaseTest() {
    private fun accountRepo(
        account: String? = CANONICAL_ACCOUNT,
        generation: Long = PROJECTION_GENERATION,
    ) = GymRepository(database, RepositorySession(account, generation))

    private suspend fun seedSelectedDevice() {
        database.projectionSeedDevice(CANONICAL_ACCOUNT, PROJECTION_GENERATION)
    }

    private suspend fun seedLookups() {
        insertMuscleGroups(
            muscleGroupFixture(id = PROJECTION_MUSCLE_PRIMARY, name = "Chest"),
            muscleGroupFixture(id = PROJECTION_MUSCLE_SECONDARY, name = "Shoulders"),
        )
        insertEquipment(equipmentFixture(id = PROJECTION_EQUIPMENT, name = "Dumbbell"))
        insertExercises(exerciseFixture(id = PROJECTION_BUNDLED, name = "Bench Press"))
    }

    private fun customRecord(revision: Long = 1L, name: String = "Fly") = canonicalCustomRecord(
        id = CANONICAL_CUSTOM_A,
        name = name,
        revision = revision,
    )

    private fun workoutChange(
        id: String = CANONICAL_WORKOUT_A,
        revision: Long = 1L,
        status: String = "active",
        startedAt: String = CANONICAL_STARTED_AT,
        finishedAt: String? = null,
        entryId: String = CANONICAL_ENTRY_A1,
        setId: String = CANONICAL_SET_A1,
        completed: Boolean = false,
    ) = canonicalChange(
        CANONICAL_TYPE_WORKOUT,
        id,
        CANONICAL_OP_UPSERT,
        revision,
        canonicalWorkoutRecord(
            id = id,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            revision = revision,
            exercises = canonicalJsonArray(
                canonicalWorkoutEntry(
                    id = entryId,
                    exerciseId = CANONICAL_CUSTOM_A,
                    exerciseName = "Stale Press",
                    sets = canonicalJsonArray(canonicalWorkoutSet(id = setId, repetitions = 8, weightKg = 50.0, isCompleted = completed)),
                ),
            ),
        ),
    )

    private fun historyChange(revision: Long = 1L) = workoutChange(
        id = CANONICAL_WORKOUT_B,
        revision = revision,
        status = "finished",
        startedAt = CANONICAL_STARTED_AT,
        finishedAt = CANONICAL_FINISHED_AT,
        entryId = CANONICAL_ENTRY_A2,
        setId = CANONICAL_SET_A2,
        completed = true,
    )

    private fun routineChange(revision: Long = 1L) = canonicalChange(
        CANONICAL_TYPE_ROUTINE,
        CANONICAL_ROUTINE_B,
        CANONICAL_OP_UPSERT,
        revision,
        canonicalRoutineRecord(
            id = CANONICAL_ROUTINE_B,
            name = "Legs",
            isDraft = false,
            revision = revision,
            exercises = canonicalJsonArray(
                canonicalRoutineEntry(
                    exerciseId = CANONICAL_CUSTOM_A,
                    exerciseName = "Stale Press",
                    sets = canonicalJsonArray(canonicalRoutineSet(repetitions = 10, weightKg = 40.0)),
                ),
            ),
        ),
    )

    private suspend fun publishSimpleGraph(
        customName: String = "Fly",
        customRevision: Long = 1L,
        workoutRevision: Long = 1L,
    ) {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, customRevision, customRecord(customRevision, customName)),
                workoutChange(revision = workoutRevision),
            ),
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
    }

    private suspend fun publishFullGraph(customName: String = "Fly", customRevision: Long = 1L) {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, customRevision, customRecord(customRevision, customName)),
                workoutChange(id = CANONICAL_WORKOUT_A, revision = 1L),
                historyChange(revision = 1L),
                routineChange(revision = 1L),
            ),
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
    }

    @Test
    fun initialSelectedEpochIsZeroWhenAbsent() = runTest {
        seedSelectedDevice()
        assertEquals(0L, accountRepo().observeProjectionEpoch().first())

        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = null, cachedAccountId = null, sessionGeneration = 0L),
        )
        assertEquals(0L, GymRepository(database).observeProjectionEpoch().first())
    }

    @Test
    fun liveCollectorEmitsDistinctCommitsIgnoresNonSignalsAndFailsOnStaleSwitch() = runBlocking {
        seedSelectedDevice()
        val repo = accountRepo()
        val epochs = Channel<Long>(Channel.UNLIMITED)
        val errors = Channel<Throwable>(1)
        val job = launch {
            try {
                repo.observeProjectionEpoch().collect { epochs.trySend(it) }
            } catch (e: Throwable) {
                errors.trySend(e)
            }
        }
        try {
            assertEquals(0L, withTimeout(5_000) { epochs.receive() })

            publishSimpleGraph()
            assertEquals(1L, withTimeout(5_000) { epochs.receive() })

            // Canonical-only, dirty, foreign outbox and foreign cache progress: no new epoch.
            database.projectionApplyCanonical(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(workoutChange(revision = 2L)),
                ),
                1L,
            )
            database.projectionSetCache(CANONICAL_ACCOUNT, projectionDirty = true)
            database.projectionInsertOutbox(
                mutationId = "30000000-0000-4000-8000-000000000001",
                account = CANONICAL_OTHER_ACCOUNT,
            )
            database.projectionSeedCache(CANONICAL_OTHER_ACCOUNT, true, 9L, false, 9L)
            assertNull(withTimeoutOrNull(1_000) { epochs.receive() })

            database.projectionApplyCanonical(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 2L, customRecord(2L, "Fly v2")),
                        workoutChange(revision = 2L),
                    ),
                ),
                1L,
            )
            val second = database.projectionStore(barrier = projectionGrantBarrier()).publish(1L)
            assertEquals(ProjectionPublicationStatus.PUBLISHED, second.status)
            assertEquals(2L, withTimeout(5_000) { epochs.receive() })
            assertNull(withTimeoutOrNull(500) { epochs.receive() })

            // Revoking the selected session terminates the running stream fail-closed.
            database.syncDao().upsertDeviceState(
                SyncDeviceStateEntity(
                    activeAccountId = CANONICAL_OTHER_ACCOUNT,
                    cachedAccountId = CANONICAL_OTHER_ACCOUNT,
                    sessionGeneration = PROJECTION_GENERATION,
                ),
            )
            val failure = withTimeout(5_000) { errors.receive() }
            assertTrue(failure is StaleSessionException)
            assertNull(withTimeoutOrNull(500) { epochs.receive() })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun actualCompletePublicationEmitsNewEpoch() = runTest {
        seedSelectedDevice()
        assertEquals(0L, accountRepo().observeProjectionEpoch().first())
        publishSimpleGraph()
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())

        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 2L, customRecord(2L, "Fly v2")),
                    workoutChange(revision = 2L),
                ),
            ),
            1L,
        )
        val second = database.projectionStore(barrier = projectionGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, second.status)
        assertEquals(2L, accountRepo().observeProjectionEpoch().first())
    }

    @Test
    fun equalParentRenameResolvesCurrentJoinedLabelsEverywhere() = runTest {
        publishFullGraph(customName = "Fly")
        val repo = accountRepo()
        assertEquals("Fly", repo.workoutDetails(CANONICAL_WORKOUT_A)!!.exercises.single().workoutExercise.exerciseName)
        assertEquals("Fly", repo.workoutDetails(CANONICAL_WORKOUT_B)!!.exercises.single().workoutExercise.exerciseName)
        assertEquals("Fly", repo.routineDetails(CANONICAL_ROUTINE_B)!!.exercises.single().routineExercise.exerciseName)
        assertEquals("Fly", repo.exerciseDetails(CANONICAL_CUSTOM_A)!!.exercise.name)

        // Parent revisions stay at 1 while only the custom rename advances: every
        // selected reference must resolve the current label, not stored stale text.
        // Only the custom rename advances; parents are untouched deltas and keep
        // revision 1 while every selected reference resolves the current label.
        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 2L, customRecord(2L, "Fly Renamed")),
                ),
            ),
            1L,
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(2L, repo.observeProjectionEpoch().first())

        assertEquals("Fly Renamed", repo.workoutDetails(CANONICAL_WORKOUT_A)!!.exercises.single().workoutExercise.exerciseName)
        assertEquals("Fly Renamed", repo.workoutDetails(CANONICAL_WORKOUT_B)!!.exercises.single().workoutExercise.exerciseName)
        assertEquals("Fly Renamed", repo.routineDetails(CANONICAL_ROUTINE_B)!!.exercises.single().routineExercise.exerciseName)
        assertEquals("Fly Renamed", repo.exerciseDetails(CANONICAL_CUSTOM_A)!!.exercise.name)
        val historyLabels = repo.finishedWorkoutSummaries().flatMap { summary -> summary.exercises.map { it.exerciseName } }
        assertTrue(historyLabels.contains("Fly Renamed"))
        val routineLabels = repo.savedRoutineDetails().single().exercises.map { it.routineExercise.exerciseName }
        assertEquals(listOf("Fly Renamed"), routineLabels)
        val catalogueNames = repo.exerciseSummaries().filter { it.exercise.id == CANONICAL_CUSTOM_A }.map { it.exercise.name }
        assertEquals(listOf("Fly Renamed"), catalogueNames)
    }

    @Test
    fun canonicalBaselineDirtyOutboxAndForeignProgressEmitNoNewEpoch() = runTest {
        publishSimpleGraph()
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())

        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(workoutChange(revision = 2L)),
            ),
            1L,
        )
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())

        database.projectionSetCache(CANONICAL_ACCOUNT, projectionDirty = true)
        database.projectionInsertOutbox(mutationId = "30000000-0000-4000-8000-000000000001")
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())

        // Baseline progress alone (cache epoch advances while materialized stays put)
        // is not a publication signal: the observed value must stay at materialized 1.
        database.projectionSetCache(CANONICAL_ACCOUNT, cacheEpoch = 2L)
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())

        database.projectionSeedCache(CANONICAL_OTHER_ACCOUNT, true, 9L, false, 9L)
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())
        try {
            accountRepo(CANONICAL_OTHER_ACCOUNT).observeProjectionEpoch().first()
            fail("Foreign scope must stay fenced while device selects A")
        } catch (_: StaleSessionException) {
        }
        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(
                activeAccountId = CANONICAL_OTHER_ACCOUNT,
                cachedAccountId = CANONICAL_OTHER_ACCOUNT,
                sessionGeneration = PROJECTION_GENERATION,
            ),
        )
        assertEquals(9L, accountRepo(CANONICAL_OTHER_ACCOUNT).observeProjectionEpoch().first())
        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(
                activeAccountId = CANONICAL_ACCOUNT,
                cachedAccountId = CANONICAL_ACCOUNT,
                sessionGeneration = PROJECTION_GENERATION,
            ),
        )
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())
    }

    @Test
    fun rollbackAndNoOpIsolationKeepEpoch() = runTest {
        publishSimpleGraph()
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())
        val before = database.projectionDumpAll()

        val unchanged = database.projectionStore(barrier = projectionGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.UNCHANGED, unchanged.status)
        assertEquals(1L, unchanged.materializedEpoch)
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())
        assertEquals(before, database.projectionDumpAll())

        val lease = ProjectionTestLease()
        lease.revoke()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(1L)
            fail("Expected revoked lease failure")
        } catch (_: com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException) {
        }
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())
    }

    @Test
    fun staleSessionNeverFollowsNewScope() = runTest {
        publishSimpleGraph()
        val stale = accountRepo(generation = PROJECTION_GENERATION - 1)
        try {
            stale.observeProjectionEpoch().first()
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }

        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(
                activeAccountId = CANONICAL_OTHER_ACCOUNT,
                cachedAccountId = CANONICAL_OTHER_ACCOUNT,
                sessionGeneration = PROJECTION_GENERATION,
            ),
        )
        try {
            stale.observeProjectionEpoch().first()
            fail("Stale observer must never follow account B")
        } catch (_: StaleSessionException) {
        }
        try {
            stale.workoutDetails(CANONICAL_WORKOUT_A)
            fail("Stale reads must never follow account B")
        } catch (_: StaleSessionException) {
        }
        assertNull(accountRepo(CANONICAL_OTHER_ACCOUNT).workoutDetails(CANONICAL_WORKOUT_A))
    }

    @Test
    fun signedOutCannotObserveRetainedPrivateEpochs() = runTest {
        publishSimpleGraph()
        assertEquals(1L, accountRepo().observeProjectionEpoch().first())

        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = null, cachedAccountId = null, sessionGeneration = 0L),
        )
        assertEquals(0L, GymRepository(database).observeProjectionEpoch().first())
        assertEquals(0L, accountRepo(account = null, generation = 0L).observeProjectionEpoch().first())
    }

    @Test
    fun negativeMetadataFailsClosedWithoutVisiblePublication() = runTest {
        publishSimpleGraph()
        database.projectionSetCache(CANONICAL_ACCOUNT, materializedEpoch = 5L)
        try {
            accountRepo().observeProjectionEpoch().first()
            fail("Out-of-range materialized epoch must fail closed")
        } catch (e: Exception) {
            assertTrue(e !is StaleSessionException)
        }
        try {
            accountRepo().observeProjectionEpoch().first()
            fail("Invalid epoch must never be emitted as visible publication")
        } catch (e: Exception) {
            assertTrue(e !is StaleSessionException)
        }
        assertTrue(database.projectionQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(CANONICAL_WORKOUT_A)).isNotEmpty())
    }
}
