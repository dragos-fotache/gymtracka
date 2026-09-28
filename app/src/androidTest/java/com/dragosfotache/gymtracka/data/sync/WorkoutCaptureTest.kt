package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Simple active-workout mutation capture through GymRepository. Real Room, no mocked DAOs. */
class WorkoutCaptureTest : GymDatabaseTest() {

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val GENERATION = 7L
        const val CATALOGUE = "44444444-4444-4444-4444-444444444444"
        const val CATALOGUE_2 = "66666666-6666-6666-6666-666666666666"
        const val HIDDEN_CUSTOM = "55555555-5555-5555-5555-555555555555"
        const val WORKOUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val WORKOUT_2 = "12121212-1212-1212-1212-121212121212"
        const val ENTRY = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val ENTRY_2 = "23232323-2323-2323-2323-232323232323"
        const val ENTRY_3 = "45454545-4545-4545-4545-454545454545"
        const val SET_1 = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val SET_2 = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val SET_H = "34343434-3434-3434-3434-343434343434"
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

    private suspend fun seedWorkout(
        id: String = WORKOUT,
        accountId: String? = ACCOUNT_A,
        status: String = "active",
        finishedAtMillis: Long? = null,
        deletedAtMillis: Long? = null,
        entryId: String = ENTRY,
        exerciseId: String = CATALOGUE,
        sets: List<WorkoutSetEntity> = listOf(
            workoutSetFixture(id = SET_1, workoutExerciseId = ENTRY, position = 0),
        ),
    ) {
        insertWorkoutAggregate(
            workout = workoutFixture(id = id, finishedAtMillis = finishedAtMillis, status = status)
                .copy(accountId = accountId, deletedAtMillis = deletedAtMillis),
            exercises = listOf(
                workoutExerciseFixture(
                    id = entryId,
                    workoutId = id,
                    exerciseId = exerciseId,
                ),
            ),
            sets = sets,
        )
    }

    @Test
    fun workout_scalarEditsCaptureCompleteUpsert() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()

        repoA.updateWorkoutName(WORKOUT, "Morning")
        repoA.updateWorkoutExerciseNotes(ENTRY, "Felt strong")
        repoA.updateWorkoutExerciseRestDuration(ENTRY, 90)

        assertEquals("Morning", database.workoutDao().getWorkout(WORKOUT)?.name)
        // Consecutive never-sent upserts coalesce into a single pending tail row.
        assertEquals(1, outbox().size)
        val last = outbox().single()
        assertEquals("workout.upsert", last.mutationType)
        val envelope = readDurableOutboxRow(last).request.envelope
        assertEquals(AggregateType.WORKOUT, envelope.aggregateType)
        assertEquals(WORKOUT, envelope.aggregateId)
        val input = (envelope.payload as MutationPayload.WorkoutUpsert).input
        assertEquals("Morning", input.name)
        assertEquals("Felt strong", input.exercises.single().notes)
        assertEquals(90, input.exercises.single().restDurationSeconds)
        assertEquals(listOf(SET_1), input.exercises.single().sets.map { it.id })

        // Signed-out edits persist locally with no outbox work.
        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = null, sessionGeneration = 0L),
        )
        insertWorkoutAggregate(
            workout = workoutFixture(id = WORKOUT_2).copy(accountId = null),
            exercises = listOf(
                workoutExerciseFixture(id = "local-entry", workoutId = WORKOUT_2, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = "local-set", workoutExerciseId = "local-entry"),
            ),
        )
        val localRepo = repo(RepositorySession.SIGNED_OUT)
        localRepo.updateWorkoutName(WORKOUT_2, "Local")
        val local = database.workoutDao().getWorkout(WORKOUT_2)!!
        assertEquals("Local", local.name)
        assertEquals(1L, local.localGeneration)
        assertEquals("local_only", local.syncState)
        assertEquals(1, outbox().size)
    }

    @Test
    fun workout_sameFieldEditsAreNoOps() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout(nameSeed = "Morning")
        val generations = database.workoutDao().getWorkout(WORKOUT)?.localGeneration

        repoA.updateWorkoutName(WORKOUT, "Morning")
        repoA.updateWorkoutExerciseNotes(ENTRY, "")
        repoA.updateWorkoutExerciseRestDuration(ENTRY, 30)

        assertEquals(generations, database.workoutDao().getWorkout(WORKOUT)?.localGeneration)
        assertEquals(0, outbox().size)
    }

    @Test
    fun workout_addSetAppendsAtSiblingCount() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout(
            sets = listOf(
                workoutSetFixture(id = SET_1, workoutExerciseId = ENTRY, position = 0),
                workoutSetFixture(id = SET_2, workoutExerciseId = ENTRY, position = 1),
            ),
        )

        // The caller position is stale; the stored sibling count wins.
        val added = repoA.addSetToWorkoutExercise(ENTRY, 99, 5, 60.0)
        assertEquals(2, added.position)
        assertEquals(ENTRY, added.workoutExerciseId)
        assertEquals(5, added.repetitions)
        assertEquals(60.0, added.weightKg)

        val stored = database.workoutDao().getWorkoutSets(ENTRY)
        assertEquals(listOf(0, 1, 2), stored.map { it.position })
        val envelope = readDurableOutboxRow(outbox().single()).request.envelope
        assertEquals(MutationKind.WORKOUT_UPSERT, envelope.kind)
        val input = (envelope.payload as MutationPayload.WorkoutUpsert).input
        assertEquals(3, input.exercises.single().sets.size)
        assertEquals(added.id, input.exercises.single().sets.last().id)
    }

    @Test
    fun workout_addSetRejectsMissingParent() = runTest {
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()

        try {
            repoA.addSetToWorkoutExercise("99999999-9999-9999-9999-999999999999", 0, 5, 50.0)
            fail("Expected SyncModelException")
        } catch (_: SyncModelException) {
        }
        assertEquals(0, outbox().size)
    }

    @Test
    fun workout_updateSetNormalizesAndDerivesCompletion() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()

        // Negative/infinite inputs sanitize to null and force incomplete, even when
        // the caller claims the set is complete.
        val stored = database.workoutDao().getWorkoutSet(SET_1)!!
        repoA.updateWorkoutSet(
            stored.copy(repetitions = -3, weightKg = Double.POSITIVE_INFINITY, isCompleted = true),
        )
        val cleared = database.workoutDao().getWorkoutSet(SET_1)!!
        assertNull(cleared.repetitions)
        assertNull(cleared.weightKg)
        assertEquals(false, cleared.isCompleted)

        // Valid values with requested false stay incomplete: values are necessary,
        // not sufficient, so copied/input sets never complete prematurely.
        repoA.updateWorkoutSet(cleared.copy(repetitions = 8, weightKg = 52.5, isCompleted = false))
        val unchecked = database.workoutDao().getWorkoutSet(SET_1)!!
        assertEquals(8, unchecked.repetitions)
        assertEquals(52.5, unchecked.weightKg)
        assertEquals(false, unchecked.isCompleted)

        // Explicit checking with valid values succeeds.
        repoA.updateWorkoutSet(unchecked.copy(isCompleted = true))
        val completed = database.workoutDao().getWorkoutSet(SET_1)!!
        assertEquals(true, completed.isCompleted)
        assertNull(completed.restSeconds)

        // Explicit unchecking persists with valid values and clears rest.
        database.workoutDao().updateWorkoutSetRestSeconds(SET_1, 60)
        repoA.updateWorkoutSet(completed.copy(isCompleted = false))
        val uncheckedAgain = database.workoutDao().getWorkoutSet(SET_1)!!
        assertEquals(false, uncheckedAgain.isCompleted)
        assertEquals(8, uncheckedAgain.repetitions)
        assertEquals(52.5, uncheckedAgain.weightKg)
        assertNull(uncheckedAgain.restSeconds)
    }

    @Test
    fun workout_updateSetResultReportsOnlyIncompleteToCompleteTransitions() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()

        val stored = database.workoutDao().getWorkoutSet(SET_1)!!
        // Invalid checked input forces incomplete: no transition.
        assertFalse(repoA.updateWorkoutSet(stored.copy(repetitions = null, isCompleted = true)))
        // Valid values with requested false stay incomplete: no transition.
        assertFalse(repoA.updateWorkoutSet(stored.copy(repetitions = 8, weightKg = 52.5)))
        // Explicit checking with valid values transitions: true.
        assertTrue(repoA.updateWorkoutSet(stored.copy(repetitions = 8, weightKg = 52.5, isCompleted = true)))
        val completed = database.workoutDao().getWorkoutSet(SET_1)!!
        // An ordinary already-completed edit writes but never transitions.
        assertFalse(repoA.updateWorkoutSet(completed.copy(weightKg = 55.0)))
        // Explicit unchecking is a real write back to incomplete: still false.
        assertFalse(repoA.updateWorkoutSet(completed.copy(isCompleted = false)))
        // Missing sets and wrong-parent entities are guarded no-ops: false.
        assertFalse(
            repoA.updateWorkoutSet(stored.copy(id = "99999999-9999-9999-9999-999999999999")),
        )
        assertFalse(repoA.updateWorkoutSet(stored.copy(workoutExerciseId = "other-parent")))
    }

    @Test
    fun workout_updateSetPreservesTimerRest() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout(
            sets = listOf(
                workoutSetFixture(
                    id = SET_1,
                    workoutExerciseId = ENTRY,
                    position = 0,
                    repetitions = 8,
                    weightKg = 50.0,
                    isCompleted = true,
                    restSeconds = 75,
                ),
            ),
        )

        // A stale UI snapshot carries restSeconds=null; staying complete must not
        // overwrite the timer-recorded 75s.
        val stale = database.workoutDao().getWorkoutSet(SET_1)!!.copy(weightKg = 55.0, restSeconds = null)
        repoA.updateWorkoutSet(stale)
        assertEquals(75, database.workoutDao().getWorkoutSet(SET_1)?.restSeconds)

        // Changing to incomplete clears recorded rest.
        repoA.updateWorkoutSet(stale.copy(repetitions = null))
        val incomplete = database.workoutDao().getWorkoutSet(SET_1)!!
        assertEquals(false, incomplete.isCompleted)
        assertNull(incomplete.restSeconds)

        // Incomplete back to complete begins with null rest.
        repoA.updateWorkoutSet(incomplete.copy(repetitions = 8, weightKg = 55.0, isCompleted = true))
        val recompleted = database.workoutDao().getWorkoutSet(SET_1)!!
        assertEquals(true, recompleted.isCompleted)
        assertNull(recompleted.restSeconds)
    }

    @Test
    fun workout_terminalRestOwnsElapsedWrites() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()

        repoA.updateWorkoutSetRestSeconds(SET_1, 90)
        assertEquals(90, database.workoutDao().getWorkoutSet(SET_1)?.restSeconds)
        repoA.updateWorkoutSetRestSeconds(SET_1, -5)
        assertEquals(0, database.workoutDao().getWorkoutSet(SET_1)?.restSeconds)
        val envelope = readDurableOutboxRow(outbox().last()).request.envelope
        assertEquals(MutationKind.WORKOUT_UPSERT, envelope.kind)
        assertEquals(
            0,
            (envelope.payload as MutationPayload.WorkoutUpsert)
                .input.exercises.single().sets.single().restSeconds,
        )
    }

    @Test
    fun workout_hiddenCustomChildSurvivesCapture() = runTest {
        insertExercises(
            exerciseFixture(id = CATALOGUE),
            exerciseFixture(id = HIDDEN_CUSTOM, name = "Hidden", isUserCreated = true),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        insertWorkoutAggregate(
            workout = workoutFixture(id = WORKOUT).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = ENTRY, workoutId = WORKOUT, exerciseId = CATALOGUE),
                workoutExerciseFixture(
                    id = ENTRY_2,
                    workoutId = WORKOUT,
                    exerciseId = HIDDEN_CUSTOM,
                    position = 1,
                ),
            ),
            sets = listOf(
                workoutSetFixture(id = SET_1, workoutExerciseId = ENTRY, position = 0),
                workoutSetFixture(id = SET_H, workoutExerciseId = ENTRY_2, position = 0),
            ),
        )

        // The privacy-filtered UI projection hides the foreign custom child...
        assertEquals(1, repoA.workoutDetails(WORKOUT)?.exercises?.size)

        // ...but capture carries every persisted child from the raw aggregate.
        repoA.updateWorkoutName(WORKOUT, "Renamed")
        val input = (
            readDurableOutboxRow(outbox().single()).request.envelope.payload
                as MutationPayload.WorkoutUpsert
            ).input
        assertEquals(listOf(ENTRY, ENTRY_2), input.exercises.map { it.id })
        assertEquals(HIDDEN_CUSTOM, input.exercises.last().exerciseId)
        assertEquals(listOf(SET_H), input.exercises.last().sets.map { it.id })
    }

    @Test
    fun workout_updateSetPreservesStoredPosition() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()

        // A stale caller position never applies, but changed editable values do.
        val stored = database.workoutDao().getWorkoutSet(SET_1)!!
        repoA.updateWorkoutSet(
            stored.copy(position = 7, repetitions = 9, weightKg = 91.0, isCompleted = true),
        )
        val updated = database.workoutDao().getWorkoutSet(SET_1)!!
        assertEquals(0, updated.position)
        assertEquals(9, updated.repetitions)
        assertEquals(91.0, updated.weightKg)
        assertEquals(true, updated.isCompleted)
        assertEquals(1, outbox().size)
    }

    @Test
    fun workout_updateSetRejectsWrongParent() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()
        val before = database.workoutDao().getWorkout(WORKOUT)?.localGeneration

        val stored = database.workoutDao().getWorkoutSet(SET_1)!!
        repoA.updateWorkoutSet(stored.copy(workoutExerciseId = "other-parent", repetitions = 9))
        assertEquals(8, database.workoutDao().getWorkoutSet(SET_1)?.repetitions)
        assertEquals(before, database.workoutDao().getWorkout(WORKOUT)?.localGeneration)
        assertEquals(0, outbox().size)
    }

    @Test
    fun workout_deleteSetCapturesSemanticAndRenumbers() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout(
            sets = listOf(
                workoutSetFixture(id = SET_1, workoutExerciseId = ENTRY, position = 0),
                workoutSetFixture(id = SET_2, workoutExerciseId = ENTRY, position = 1),
            ),
        )

        // The caller set carries a stale position; the actual row wins.
        val stale = database.workoutDao().getWorkoutSet(SET_1)!!.copy(position = 9)
        repoA.deleteWorkoutSet(stale)
        val remaining = database.workoutDao().getWorkoutSets(ENTRY)
        assertEquals(listOf(SET_2), remaining.map { it.id })
        assertEquals(listOf(0), remaining.map { it.position })
        val envelope = readDurableOutboxRow(outbox().single()).request.envelope
        assertEquals(MutationKind.WORKOUT_DELETE_SET, envelope.kind)
        assertEquals(SET_1, (envelope.payload as MutationPayload.WorkoutDeleteSet).workoutSetId)

        // Mismatched parent deletes nothing.
        val before = database.workoutDao().getWorkout(WORKOUT)?.localGeneration
        repoA.deleteWorkoutSet(remaining.single().copy(workoutExerciseId = "other-parent"))
        assertEquals(1, database.workoutDao().getWorkoutSets(ENTRY).size)
        assertEquals(before, database.workoutDao().getWorkout(WORKOUT)?.localGeneration)
        assertEquals(1, outbox().size)
    }

    @Test
    fun workout_deleteExerciseCapturesSemantic() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()

        repoA.deleteExerciseFromWorkout(ENTRY, WORKOUT)
        assertTrue(database.workoutDao().getWorkoutExercises(WORKOUT).isEmpty())
        val envelope = readDurableOutboxRow(outbox().single()).request.envelope
        assertEquals(MutationKind.WORKOUT_DELETE_EXERCISE, envelope.kind)
        assertEquals(
            ENTRY,
            (envelope.payload as MutationPayload.WorkoutDeleteExercise).workoutExerciseId,
        )

        // A mismatched workoutId must not touch an unrelated root.
        seedWorkout(id = WORKOUT_2, entryId = ENTRY)
        val before = database.workoutDao().getWorkout(WORKOUT_2)?.localGeneration
        repoA.deleteExerciseFromWorkout(ENTRY, WORKOUT)
        assertEquals(1, database.workoutDao().getWorkoutExercises(WORKOUT_2).size)
        assertEquals(before, database.workoutDao().getWorkout(WORKOUT_2)?.localGeneration)
        assertEquals(1, outbox().size)
    }

    @Test
    fun workout_deleteExerciseRenumbersThreeEntries() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        insertWorkoutAggregate(
            workout = workoutFixture(id = WORKOUT).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = ENTRY, workoutId = WORKOUT, exerciseId = CATALOGUE),
                workoutExerciseFixture(
                    id = ENTRY_2,
                    workoutId = WORKOUT,
                    exerciseId = CATALOGUE,
                    position = 1,
                ),
                workoutExerciseFixture(
                    id = ENTRY_3,
                    workoutId = WORKOUT,
                    exerciseId = CATALOGUE,
                    position = 2,
                ),
            ),
            sets = listOf(
                workoutSetFixture(id = SET_1, workoutExerciseId = ENTRY, position = 0),
                workoutSetFixture(id = SET_2, workoutExerciseId = ENTRY_2, position = 0),
                workoutSetFixture(id = SET_H, workoutExerciseId = ENTRY_3, position = 0),
            ),
        )

        repoA.deleteExerciseFromWorkout(ENTRY_2, WORKOUT)
        val survivors = database.workoutDao().getWorkoutExercises(WORKOUT)
        assertEquals(listOf(ENTRY, ENTRY_3), survivors.map { it.id })
        assertEquals(listOf(0, 1), survivors.map { it.position })
        assertEquals(
            ENTRY_2,
            (
                readDurableOutboxRow(outbox().single()).request.envelope.payload
                    as MutationPayload.WorkoutDeleteExercise
                ).workoutExerciseId,
        )
    }

    @Test
    fun workout_replacePreservesEntry() = runTest {
        insertExercises(
            exerciseFixture(id = CATALOGUE),
            exerciseFixture(id = CATALOGUE_2, name = "Press"),
            exerciseFixture(id = HIDDEN_CUSTOM, name = "Hidden", isUserCreated = true),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()
        repoA.updateWorkoutExerciseNotes(ENTRY, "Keep me")
        val before = database.workoutDao().getWorkoutExercise(ENTRY)!!
        val setsBefore = database.workoutDao().getWorkoutSets(ENTRY)

        repoA.replaceWorkoutExercise(ENTRY, CATALOGUE_2)
        val replaced = database.workoutDao().getWorkoutExercise(ENTRY)!!
        assertEquals(ENTRY, replaced.id)
        assertEquals(CATALOGUE_2, replaced.exerciseId)
        assertEquals(before.notes, replaced.notes)
        assertEquals(before.restDurationSeconds, replaced.restDurationSeconds)
        assertEquals(before.position, replaced.position)
        assertEquals(before.workoutId, replaced.workoutId)
        // Full set rows survive replacement exactly, not just their count.
        assertEquals(setsBefore, database.workoutDao().getWorkoutSets(ENTRY))
        val envelope = readDurableOutboxRow(outbox().last()).request.envelope
        assertEquals(MutationKind.WORKOUT_REPLACE_EXERCISE, envelope.kind)
        val payload = envelope.payload as MutationPayload.WorkoutReplaceExercise
        assertEquals(ENTRY, payload.workoutExerciseId)
        assertEquals(CATALOGUE_2, payload.exerciseId)

        // Same reference and hidden references change nothing.
        val generations = database.workoutDao().getWorkout(WORKOUT)?.localGeneration
        repoA.replaceWorkoutExercise(ENTRY, CATALOGUE_2)
        try {
            repoA.replaceWorkoutExercise(ENTRY, HIDDEN_CUSTOM)
            fail("Expected SyncModelException")
        } catch (_: SyncModelException) {
        }
        assertEquals(CATALOGUE_2, database.workoutDao().getWorkoutExercise(ENTRY)?.exerciseId)
        assertEquals(generations, database.workoutDao().getWorkout(WORKOUT)?.localGeneration)
        assertEquals(2, outbox().size)
    }

    @Test
    fun workout_guardsRejectForeignFinishedDeletedAndMissing() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        val finishedId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        val tombId = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        val foreignId = "00000000-0000-0000-0000-000000000000"
        val foreignEntry = "11111111-2222-3333-4444-555555555555"
        val foreignSet = "66666666-7777-8888-9999-aaaaaaaaaaaa"
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = finishedId,
                finishedAtMillis = 5_000L,
                status = "finished",
            ).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = "entry-finished", workoutId = finishedId, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = "set-finished", workoutExerciseId = "entry-finished"),
            ),
        )
        insertWorkoutAggregate(
            workout = workoutFixture(id = tombId).copy(accountId = ACCOUNT_A, deletedAtMillis = 3L),
            exercises = listOf(
                workoutExerciseFixture(id = "entry-tomb", workoutId = tombId, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = "set-tomb", workoutExerciseId = "entry-tomb"),
            ),
        )
        insertWorkoutAggregate(
            workout = workoutFixture(id = foreignId).copy(accountId = ACCOUNT_B),
            exercises = listOf(
                workoutExerciseFixture(id = foreignEntry, workoutId = foreignId, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = foreignSet, workoutExerciseId = foreignEntry),
            ),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()

        suspend fun attemptAll(id: String, entryId: String, set: WorkoutSetEntity) {
            repoA.updateWorkoutName(id, "Ghost")
            repoA.updateWorkoutExerciseNotes(entryId, "Ghost")
            repoA.updateWorkoutExerciseRestDuration(entryId, 90)
            repoA.updateWorkoutSet(set.copy(repetitions = 9, weightKg = 90.0))
            repoA.updateWorkoutSetRestSeconds(set.id, 30)
            repoA.replaceWorkoutExercise(entryId, CATALOGUE)
            repoA.deleteWorkoutSet(set)
            repoA.deleteExerciseFromWorkout(entryId, id)
            try {
                repoA.addSetToWorkoutExercise(entryId, 0, 1, 1.0)
                fail("Expected SyncModelException for $id")
            } catch (_: SyncModelException) {
            }
        }
        attemptAll(finishedId, "entry-finished", database.workoutDao().getWorkoutSet("set-finished")!!)
        attemptAll(tombId, "entry-tomb", database.workoutDao().getWorkoutSet("set-tomb")!!)
        attemptAll(foreignId, foreignEntry, database.workoutDao().getWorkoutSet(foreignSet)!!)
        // Missing parents change nothing.
        repoA.updateWorkoutName("99999999-9999-9999-9999-999999999999", "Ghost")
        repoA.updateWorkoutExerciseNotes("99999999-9999-9999-9999-999999999999", "Ghost")

        assertEquals(0L, database.workoutDao().getWorkout(finishedId)?.localGeneration)
        assertEquals(0L, database.workoutDao().getWorkout(tombId)?.localGeneration)
        assertEquals(0L, database.workoutDao().getWorkout(foreignId)?.localGeneration)
        assertEquals(1, database.workoutDao().getWorkoutExercises(finishedId).size)
        assertEquals(1, database.workoutDao().getWorkoutExercises(tombId).size)
        assertEquals(1, database.workoutDao().getWorkoutExercises(foreignId).size)
        assertEquals(0, outbox().size)
    }

    @Test
    fun workout_frozenPredecessorAppendsLater() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()

        repoA.updateWorkoutName(WORKOUT, "First")
        val firstMutation = outbox().single().mutationId
        database.syncDao().freezePendingMutation(firstMutation, ACCOUNT_A)
        val frozenBefore = database.syncDao().getOutboxMutation(firstMutation, ACCOUNT_A)!!

        repoA.updateWorkoutName(WORKOUT, "Later")
        val rows = outbox()
        assertEquals(2, rows.size)
        assertEquals(firstMutation, rows.first().mutationId)
        assertEquals("frozen", rows.first().state)
        assertEquals(frozenBefore, database.syncDao().getOutboxMutation(firstMutation, ACCOUNT_A))
    }

    @Test
    fun workout_captureFailureRollsBack() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()

        // A blocked outbox insert must roll back the domain edit and generation too.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_outbox_insert BEFORE INSERT ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            try {
                repoA.updateWorkoutName(WORKOUT, "Blocked")
                fail("Expected blocked insert to fail")
            } catch (_: Exception) {
            }
            assertEquals("Test Workout", database.workoutDao().getWorkout(WORKOUT)?.name)
            assertEquals(0L, database.workoutDao().getWorkout(WORKOUT)?.localGeneration)
            assertEquals(0, outbox().size)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_outbox_insert")
        }
    }

    @Test
    fun workout_staleFenceThrows() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedWorkout()
        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = ACCOUNT_B, sessionGeneration = 9L),
        )

        try {
            repoA.updateWorkoutName(WORKOUT, "Ghost")
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
        assertEquals(0L, database.workoutDao().getWorkout(WORKOUT)?.localGeneration)
        assertEquals(0, outbox().size)
    }

    private suspend fun seedWorkout(nameSeed: String) {
        seedWorkout()
        database.workoutDao().updateWorkoutName(WORKOUT, nameSeed)
    }
}
