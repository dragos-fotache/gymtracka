package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** History additions, finish, and root deletion capture. Real Room, no mocked DAOs. */
class FinishDeleteCaptureTest : GymDatabaseTest() {

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val GENERATION = 7L
        const val CATALOGUE = "44444444-4444-4444-4444-444444444444"
        const val CATALOGUE_2 = "55555555-5555-5555-5555-555555555555"
        const val TARGET = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val FINISHED_AT = 9_000L
        const val MUTATION_1 = "11111111-1111-1111-1111-111111111111"
        const val MUTATION_2 = "22222222-2222-2222-2222-222222222222"
        const val MUTATION_3 = "33333333-3333-3333-3333-333333333333"
    }

    private fun device() = SyncDeviceStateEntity(activeAccountId = ACCOUNT_A, sessionGeneration = GENERATION)

    private fun repo(
        session: RepositorySession = RepositorySession(ACCOUNT_A, GENERATION),
        mutations: List<String> = listOf(MUTATION_1, MUTATION_2, MUTATION_3) +
            (4..16).map { "%08x-1111-1111-1111-%012x".format(it, it) },
    ): GymRepository {
        val ids = mutations.toMutableList()
        return GymRepository(database, session, { ids.removeFirst() }, { 9_000L })
    }

    private suspend fun outbox() = database.syncDao().getOutboxForAccount(ACCOUNT_A)

    private suspend fun seedActiveTarget() {
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET, startedAtMillis = 5_000L).copy(accountId = ACCOUNT_A),
        )
    }

    @Test
    fun add_historyTieBreakPrefersUuidDescAndIgnoresOtherAccounts() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActiveTarget()
        // Same finishedAt: the higher workout UUID wins the tie.
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = "00000000-0000-0000-0000-000000000000",
                finishedAtMillis = FINISHED_AT,
                status = "finished",
            ).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = "old-entry", workoutId = "00000000-0000-0000-0000-000000000000", exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = "old-set", workoutExerciseId = "old-entry", repetitions = 3, isCompleted = true),
            ),
        )
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                finishedAtMillis = FINISHED_AT,
                status = "finished",
            ).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(
                    id = "new-entry",
                    workoutId = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                    exerciseId = CATALOGUE,
                    restDurationSeconds = 75,
                ),
            ),
            sets = listOf(
                workoutSetFixture(id = "new-set", workoutExerciseId = "new-entry", repetitions = 8, isCompleted = true),
            ),
        )
        // Newer finishedAt but foreign account: invisible in this scope.
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = "99999999-9999-9999-9999-999999999999",
                finishedAtMillis = FINISHED_AT + 1_000L,
                status = "finished",
            ).copy(accountId = ACCOUNT_B),
            exercises = listOf(
                workoutExerciseFixture(id = "foreign-entry", workoutId = "99999999-9999-9999-9999-999999999999", exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = "foreign-set", workoutExerciseId = "foreign-entry", repetitions = 99, isCompleted = true),
            ),
        )

        repoA.addExercisesToWorkout(TARGET, listOf(CATALOGUE))

        val entries = database.workoutDao().getWorkoutExercises(TARGET)
        assertEquals(75, entries.single().restDurationSeconds)
        val sets = database.workoutDao().getWorkoutSets(entries.single().id)
        assertEquals(listOf(8), sets.map { it.repetitions })
        assertTrue(sets.none { it.isCompleted })
        assertTrue(sets.all { it.restSeconds == null })
    }

    @Test
    fun add_repeatedSelectionAppendsConsecutivelyWithExactCommandIds() = runTest {
        insertExercises(
            exerciseFixture(id = CATALOGUE),
            exerciseFixture(id = CATALOGUE_2, name = "Second"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActiveTarget()

        repoA.addExercisesToWorkout(TARGET, listOf(CATALOGUE, CATALOGUE_2, CATALOGUE))

        val entries = database.workoutDao().getWorkoutExercises(TARGET)
        assertEquals(
            listOf(CATALOGUE, CATALOGUE_2, CATALOGUE),
            entries.map { it.exerciseId },
        )
        assertEquals(listOf(0, 1, 2), entries.map { it.position })
        // No-history fallback: one empty set and the 30-second default rest each.
        entries.forEach { entry ->
            assertEquals(30, entry.restDurationSeconds)
            val sets = database.workoutDao().getWorkoutSets(entry.id)
            assertEquals(1, sets.size)
            assertNull(sets.single().repetitions)
            assertNull(sets.single().weightKg)
            assertFalse(sets.single().isCompleted)
        }
        // Semantic payload carries only the Room-generated IDs, in order, no values.
        val request = readDurableOutboxRow(outbox().single()).request
        assertEquals(MutationKind.WORKOUT_ADD_EXERCISES, request.envelope.kind)
        assertEquals(AggregateType.WORKOUT, request.envelope.aggregateType)
        assertEquals(TARGET, request.envelope.aggregateId)
        val payload = request.envelope.payload as MutationPayload.WorkoutAddExercises
        assertEquals(3, payload.additions.size)
        assertEquals(entries.map { it.id }, payload.additions.map { it.id })
        assertEquals(
            entries.map { it.exerciseId },
            payload.additions.map { it.exerciseId },
        )
        payload.additions.forEachIndexed { index, addition ->
            assertEquals(
                database.workoutDao().getWorkoutSets(entries[index].id).map { it.id },
                addition.setIds,
            )
        }
    }

    @Test
    fun add_noOpGuardsNeverCapture() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActiveTarget()
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                finishedAtMillis = FINISHED_AT,
                status = "finished",
            ).copy(accountId = ACCOUNT_A),
        )

        repoA.addExercisesToWorkout(TARGET, emptyList())
        repoA.addExercisesToWorkout("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", listOf(CATALOGUE))
        repoA.addExercisesToWorkout("99999999-9999-9999-9999-999999999999", listOf(CATALOGUE))

        assertTrue(database.workoutDao().getWorkoutExercises(TARGET).isEmpty())
        assertEquals(0L, database.workoutDao().getWorkout(TARGET)?.localGeneration)
        assertEquals(0, outbox().size)
    }

    @Test
    fun add_captureFailureRollsBackChildren() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActiveTarget()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_add_capture BEFORE INSERT ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            var failure: Throwable? = null
            try {
                repoA.addExercisesToWorkout(TARGET, listOf(CATALOGUE))
            } catch (throwable: Throwable) {
                failure = throwable
            }
            assertNotNull(failure)
            assertTrue(database.workoutDao().getWorkoutExercises(TARGET).isEmpty())
            assertEquals(0L, database.workoutDao().getWorkout(TARGET)?.localGeneration)
            assertEquals(0, outbox().size)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_add_capture")
        }
    }

    @Test
    fun finish_renumbersInterleavedSurvivorsAndCapturesFinish() = runTest {
        insertExercises(
            exerciseFixture(id = CATALOGUE),
            exerciseFixture(id = CATALOGUE_2, name = "Second"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET, startedAtMillis = 5_000L).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = "doomed-entry", workoutId = TARGET, exerciseId = CATALOGUE, position = 0),
                workoutExerciseFixture(
                    id = "kept-entry",
                    workoutId = TARGET,
                    exerciseId = CATALOGUE_2,
                    position = 1,
                    notes = "Keep",
                    restDurationSeconds = 75,
                ),
            ),
            sets = listOf(
                workoutSetFixture(id = "doomed-set", workoutExerciseId = "doomed-entry", isCompleted = false),
                workoutSetFixture(
                    id = "drop-1",
                    workoutExerciseId = "kept-entry",
                    position = 0,
                    isCompleted = false,
                ),
                workoutSetFixture(
                    id = "keep-1",
                    workoutExerciseId = "kept-entry",
                    position = 1,
                    repetitions = 8,
                    weightKg = 70.0,
                    isCompleted = true,
                    restSeconds = 60,
                ),
                workoutSetFixture(
                    id = "drop-3",
                    workoutExerciseId = "kept-entry",
                    position = 2,
                    repetitions = null,
                    weightKg = 70.0,
                    isCompleted = true,
                ),
                workoutSetFixture(
                    id = "keep-3",
                    workoutExerciseId = "kept-entry",
                    position = 3,
                    repetitions = 6,
                    weightKg = 80.0,
                    isCompleted = true,
                    restSeconds = 61,
                ),
            ),
        )
        assertTrue(repoA.finishWorkout(TARGET, FINISHED_AT))

        val finished = database.workoutDao().getWorkout(TARGET)!!
        assertEquals("finished", finished.status)
        assertEquals(FINISHED_AT, finished.finishedAtMillis)
        assertEquals(1L, finished.localGeneration)
        val entries = database.workoutDao().getWorkoutExercises(TARGET)
        assertEquals(listOf("kept-entry"), entries.map { it.id })
        assertEquals(0, entries.single().position)
        assertEquals("Keep", entries.single().notes)
        assertEquals(75, entries.single().restDurationSeconds)
        val survivors = database.workoutDao().getWorkoutSets("kept-entry")
        assertEquals(listOf("keep-1", "keep-3"), survivors.map { it.id })
        assertEquals(listOf(0, 1), survivors.map { it.position })
        assertEquals(listOf(8, 6), survivors.map { it.repetitions })
        assertEquals(listOf(70.0, 80.0), survivors.map { it.weightKg })
        assertEquals(listOf(60, 61), survivors.map { it.restSeconds })

        val request = readDurableOutboxRow(outbox().single()).request
        assertEquals(MutationKind.WORKOUT_FINISH, request.envelope.kind)
        assertEquals(TARGET, request.envelope.aggregateId)
        assertEquals(
            FINISHED_AT,
            (request.envelope.payload as MutationPayload.WorkoutFinish).finishedAtMillis,
        )
    }

    @Test
    fun finish_invalidCompletedSetsNeverBecomeHistory() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET, startedAtMillis = 5_000L).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = "entry", workoutId = TARGET, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(
                    id = "invalid-set",
                    workoutExerciseId = "entry",
                    repetitions = null,
                    isCompleted = true,
                ),
            ),
        )

        assertFalse(repoA.hasCompletedSets(TARGET))
        assertFalse(repoA.finishWorkout(TARGET, FINISHED_AT))

        assertEquals("active", database.workoutDao().getWorkout(TARGET)?.status)
        assertEquals(0L, database.workoutDao().getWorkout(TARGET)?.localGeneration)
        assertEquals(1, database.workoutDao().getWorkoutSets("entry").size)
        assertEquals(0, outbox().size)
    }

    @Test
    fun finish_frozenSnapshotStaysFirstAndImmutable() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET, startedAtMillis = 5_000L).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(
                    id = "aaaaaaaa-1111-1111-1111-111111111111",
                    workoutId = TARGET,
                    exerciseId = CATALOGUE,
                ),
            ),
            sets = listOf(
                workoutSetFixture(
                    id = "aaaaaaaa-2222-2222-2222-222222222222",
                    workoutExerciseId = "aaaaaaaa-1111-1111-1111-111111111111",
                    isCompleted = true,
                ),
            ),
        )
        repoA.updateWorkoutName(TARGET, "Edited")
        val firstMutation = outbox().single().mutationId
        database.syncDao().freezePendingMutation(firstMutation, ACCOUNT_A)
        val frozenBefore = database.syncDao().getOutboxMutation(firstMutation, ACCOUNT_A)!!

        assertTrue(repoA.finishWorkout(TARGET, FINISHED_AT))

        val rows = outbox()
        assertEquals(2, rows.size)
        assertEquals("frozen", rows.first().state)
        assertEquals(frozenBefore, database.syncDao().getOutboxMutation(firstMutation, ACCOUNT_A))
        assertEquals("workout.finish", rows.last().mutationType)
    }

    @Test
    fun finish_captureFailureRollsBackEverything() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET, startedAtMillis = 5_000L).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = "entry", workoutId = TARGET, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = "keep", workoutExerciseId = "entry", position = 0, isCompleted = true),
                workoutSetFixture(id = "drop", workoutExerciseId = "entry", position = 1, isCompleted = false),
            ),
        )
        val entryBefore = database.workoutDao().getWorkoutExercise("entry")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_finish_capture BEFORE INSERT ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            var failure: Throwable? = null
            try {
                repoA.finishWorkout(TARGET, FINISHED_AT)
            } catch (throwable: Throwable) {
                failure = throwable
            }
            assertNotNull(failure)
            assertEquals("active", database.workoutDao().getWorkout(TARGET)?.status)
            assertEquals(0L, database.workoutDao().getWorkout(TARGET)?.localGeneration)
            assertEquals(entryBefore, database.workoutDao().getWorkoutExercise("entry"))
            assertEquals(
                listOf("keep", "drop"),
                database.workoutDao().getWorkoutSets("entry").map { it.id },
            )
            assertEquals(0, outbox().size)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_finish_capture")
        }
    }

    @Test
    fun finish_largeWorkoutDiscardsOverOneThousandSets() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val bulkEntry = "aaaaaaaa-1111-1111-1111-111111111111"
        val keptEntry = "aaaaaaaa-3333-3333-3333-333333333333"
        val bulkSets = (0 until 1002).map { position ->
            workoutSetFixture(
                id = "bulk-%04d-1111-1111-1111-111111111111".format(position),
                workoutExerciseId = bulkEntry,
                position = position,
                isCompleted = false,
            )
        }
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET, startedAtMillis = 5_000L).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = bulkEntry, workoutId = TARGET, exerciseId = CATALOGUE, position = 0),
                workoutExerciseFixture(id = keptEntry, workoutId = TARGET, exerciseId = CATALOGUE, position = 1),
            ),
            sets = bulkSets + listOf(
                workoutSetFixture(
                    id = "aaaaaaaa-4444-4444-4444-444444444444",
                    workoutExerciseId = keptEntry,
                    position = 0,
                    isCompleted = false,
                ),
                workoutSetFixture(
                    id = "aaaaaaaa-5555-5555-5555-555555555555",
                    workoutExerciseId = keptEntry,
                    position = 1,
                    repetitions = 8,
                    isCompleted = true,
                ),
                workoutSetFixture(
                    id = "aaaaaaaa-6666-6666-6666-666666666666",
                    workoutExerciseId = keptEntry,
                    position = 2,
                    repetitions = 6,
                    isCompleted = true,
                ),
            ),
        )

        assertTrue(repoA.finishWorkout(TARGET, FINISHED_AT))

        assertEquals("finished", database.workoutDao().getWorkout(TARGET)?.status)
        assertEquals(listOf(keptEntry), database.workoutDao().getWorkoutExercises(TARGET).map { it.id })
        val survivors = database.workoutDao().getWorkoutSets(keptEntry)
        assertEquals(
            listOf(
                "aaaaaaaa-5555-5555-5555-555555555555",
                "aaaaaaaa-6666-6666-6666-666666666666",
            ),
            survivors.map { it.id },
        )
        assertEquals(listOf(0, 1), survivors.map { it.position })
        assertEquals(1, outbox().size)
        assertEquals("workout.finish", outbox().single().mutationType)
    }

    @Test
    fun hasCompletedSets_rejectsStaleSessionInsideFence() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        seedActiveTarget()
        val stale = repo(RepositorySession(ACCOUNT_A, GENERATION - 1))

        try {
            stale.hasCompletedSets(TARGET)
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
        assertEquals(0, outbox().size)
    }

    @Test
    fun finish_noOpGuardsLeaveEverythingUnchanged() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActiveTarget()
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                finishedAtMillis = FINISHED_AT,
                status = "finished",
            ).copy(accountId = ACCOUNT_A),
        )

        assertFalse(repoA.finishWorkout("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", FINISHED_AT))
        assertFalse(repoA.finishWorkout("99999999-9999-9999-9999-999999999999", FINISHED_AT))
        assertFalse(repoA.hasCompletedSets("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
        assertFalse(repoA.hasCompletedSets("99999999-9999-9999-9999-999999999999"))

        assertEquals(0L, database.workoutDao().getWorkout(TARGET)?.localGeneration)
        assertEquals(0, outbox().size)
    }

    @Test
    fun delete_signedOutHardDeletesWithoutCapture() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = null, sessionGeneration = 0L),
        )
        val localRepo = repo(RepositorySession.SIGNED_OUT)
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET).copy(accountId = null),
            exercises = listOf(
                workoutExerciseFixture(id = "entry", workoutId = TARGET, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = "set", workoutExerciseId = "entry"),
            ),
        )

        localRepo.discardWorkout(TARGET)

        assertNull(database.workoutDao().getWorkout(TARGET))
        assertTrue(database.workoutDao().getWorkoutExercises(TARGET).isEmpty())
        assertTrue(database.workoutDao().getWorkoutSets("entry").isEmpty())
        assertEquals(0, outbox().size)
    }

    @Test
    fun delete_accountTombstonesCapturesDeleteAndKeepsHistory() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET, startedAtMillis = 5_000L).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(
                    id = "bbbbbbbb-1111-1111-1111-111111111111",
                    workoutId = TARGET,
                    exerciseId = CATALOGUE,
                ),
            ),
            sets = listOf(
                workoutSetFixture(
                    id = "bbbbbbbb-2222-2222-2222-222222222222",
                    workoutExerciseId = "bbbbbbbb-1111-1111-1111-111111111111",
                    isCompleted = true,
                ),
            ),
        )
        repoA.updateWorkoutName(TARGET, "Renamed")
        val firstMutation = outbox().single().mutationId
        assertTrue(repoA.finishWorkout(TARGET, FINISHED_AT))

        repoA.deleteWorkout(TARGET)

        // Hidden tombstone: invisible to scoped reads, children removed.
        assertNull(database.workoutDao().getScopedWorkout(TARGET, ACCOUNT_A))
        val tombstone = database.workoutDao().getWorkout(TARGET)!!
        assertNotNull(tombstone.deletedAtMillis)
        assertEquals(9_000L, tombstone.deletedAtMillis)
        assertEquals("pending", tombstone.syncState)
        assertEquals(3L, tombstone.localGeneration)
        assertTrue(database.workoutDao().getWorkoutExercises(TARGET).isEmpty())
        // Prior publication evidence survives; the delete appends after it.
        val rows = outbox()
        assertEquals(3, rows.size)
        assertEquals(firstMutation, rows.first().mutationId)
        assertEquals("workout.finish", rows[1].mutationType)
        assertEquals("aggregate.delete", rows.last().mutationType)
        val request = readDurableOutboxRow(rows.last()).request
        assertEquals(MutationKind.AGGREGATE_DELETE, request.envelope.kind)
        assertEquals(AggregateType.WORKOUT, request.envelope.aggregateType)
        assertEquals(TARGET, request.envelope.aggregateId)
        assertEquals(MutationPayload.AggregateDelete, request.envelope.payload)
        // Re-deleting the tombstone is a no-op with no further capture.
        repoA.deleteWorkout(TARGET)
        repoA.deleteWorkout("99999999-9999-9999-9999-999999999999")
        assertEquals(3, outbox().size)
    }

    @Test
    fun delete_isolatesAccounts() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        insertWorkoutAggregate(
            workout = workoutFixture(id = TARGET).copy(accountId = ACCOUNT_A),
        )
        insertWorkoutAggregate(
            workout = workoutFixture(id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb").copy(
                accountId = ACCOUNT_B,
            ),
        )

        repoA.deleteWorkout("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

        assertEquals(TARGET, database.workoutDao().getScopedWorkout(TARGET, ACCOUNT_A)?.id)
        assertEquals(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            database.workoutDao().getWorkout("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")?.id,
        )
        assertNull(database.workoutDao().getWorkout("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")?.deletedAtMillis)
        assertEquals(0, outbox().size)
    }
}
