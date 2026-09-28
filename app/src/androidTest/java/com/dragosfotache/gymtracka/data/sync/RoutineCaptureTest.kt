package com.dragosfotache.gymtracka.data.sync

import android.database.sqlite.SQLiteException
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Routine-local mutation capture through GymRepository. Real Room, no mocked DAOs. */
class RoutineCaptureTest : GymDatabaseTest() {

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val GENERATION = 7L
        const val CATALOGUE = "44444444-4444-4444-4444-444444444444"
        const val HIDDEN_CUSTOM = "55555555-5555-5555-5555-555555555555"
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

    private suspend fun seedRoutine(
        id: String,
        name: String = "Routine",
        draft: Boolean = false,
        exercises: List<RoutineExerciseEntity> = emptyList(),
        sets: List<RoutineSetEntity> = emptyList(),
    ) {
        insertRoutineAggregate(
            routine = RoutineEntity(id, name, isDraft = draft, accountId = ACCOUNT_A),
            exercises = exercises,
            sets = sets,
        )
    }

    @Test
    fun routine_draftCreationCapturesUpsert() = runTest {
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()

        val draft = repoA.createRoutineDraft()
        assertEquals(true, draft.isDraft)
        assertEquals("", draft.name)
        assertEquals(ACCOUNT_A, draft.accountId)
        assertEquals(1L, draft.localGeneration)
        assertEquals("pending", draft.syncState)

        val rows = outbox()
        assertEquals(1, rows.size)
        assertEquals(MUTATION_1, rows.single().mutationId)
        assertEquals("routine.upsert", rows.single().mutationType)
        val envelope = readDurableOutboxRow(rows.single()).request.envelope
        assertEquals(AggregateType.ROUTINE, envelope.aggregateType)
        assertEquals(draft.id, envelope.aggregateId)
        assertTrue(
            (envelope.payload as MutationPayload.RoutineUpsert).input.exercises.isEmpty(),
        )

        val localDraft = repo(RepositorySession.SIGNED_OUT).let { local ->
            database.syncDao().upsertDeviceState(
                SyncDeviceStateEntity(activeAccountId = null, sessionGeneration = 0L),
            )
            local.createRoutineDraft()
        }
        assertEquals(1L, localDraft.localGeneration)
        assertEquals("local_only", localDraft.syncState)
        assertEquals(1, outbox().size)
    }

    @Test
    fun routine_savePromotesAndValidatesAtBoundary() = runTest {
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()
        insertExercises(exerciseFixture(id = CATALOGUE))

        // Empty drafts and blank names never promote or capture.
        repoA.saveRoutine(draft.id, "Saved")
        assertEquals(1, outbox().size)
        repoA.saveRoutine(draft.id, "")
        assertEquals(true, database.routineDao().getRoutine(draft.id)?.isDraft)
        assertEquals(1, outbox().size)
        repoA.saveRoutine("99999999-9999-9999-9999-999999999999", "Saved")
        assertEquals(1, outbox().size)

        repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE))
        repoA.saveRoutine(draft.id, "Saved")
        val saved = database.routineDao().getRoutine(draft.id)!!
        assertEquals(false, saved.isDraft)
        assertEquals("Saved", saved.name)
        val rows = outbox()
        assertEquals(MutationKind.ROUTINE_SAVE, readDurableOutboxRow(rows.last()).request.envelope.kind)
        assertEquals(
            "Saved",
            (
                readDurableOutboxRow(rows.last()).request.envelope.payload
                    as MutationPayload.RoutineSave
                ).name,
        )

        // Re-saving the same name is a no-op; renaming captures another save.
        repoA.saveRoutine(draft.id, "Saved")
        assertEquals(rows.size, outbox().size)
        repoA.saveRoutine(draft.id, "Renamed")
        assertEquals(rows.size + 1, outbox().size)
    }

    @Test
    fun routine_nameAndRestEditsCoalesce() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()

        repoA.updateRoutineName(draft.id, "Morning")
        assertEquals(1, outbox().size)
        // Unchanged name is a no-op.
        repoA.updateRoutineName(draft.id, "Morning")
        assertEquals(1, outbox().size)
        assertEquals(2L, database.routineDao().getRoutine(draft.id)?.localGeneration)

        repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE))
        val entryId = database.routineDao().getRoutineExercises(draft.id).single().id
        repoA.updateRoutineExerciseRestDuration(entryId, 90)
        repoA.updateRoutineExerciseRestDuration(entryId, 90)
        assertEquals(90, database.routineDao().getRoutineExercise(entryId)?.restDurationSeconds)
        // Missing parents change nothing.
        repoA.updateRoutineName("99999999-9999-9999-9999-999999999999", "Ghost")
        repoA.updateRoutineExerciseRestDuration("99999999-9999-9999-9999-999999999999", 45)
        assertEquals(1, outbox().size)
    }

    @Test
    fun routine_addExercisesAllOrNothingAndPreservesOrder() = runTest {
        insertExercises(
            exerciseFixture(id = CATALOGUE),
            exerciseFixture(id = HIDDEN_CUSTOM, name = "Hidden", isUserCreated = true)
                .copy(accountId = "account-b"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()

        repoA.addExercisesToRoutine(draft.id, emptyList())
        assertEquals(1, outbox().size)
        repoA.addExercisesToRoutine("99999999-9999-9999-9999-999999999999", listOf(CATALOGUE))
        assertEquals(1, outbox().size)
        // Mixed valid/hidden/missing selections reject the entire selection.
        val generations = database.routineDao().getRoutine(draft.id)?.localGeneration
        try {
            repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE, HIDDEN_CUSTOM, "missing"))
            fail("Expected SyncModelException")
        } catch (_: SyncModelException) {
        }
        assertTrue(database.routineDao().getRoutineExercises(draft.id).isEmpty())
        assertEquals(generations, database.routineDao().getRoutine(draft.id)?.localGeneration)
        assertEquals(1, outbox().size)

        // Duplicates of the same catalogue exercise get distinct fresh children.
        repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE, CATALOGUE))
        val entries = database.routineDao().getRoutineExercises(draft.id)
        assertEquals(listOf(0, 1), entries.map { it.position })
        assertEquals(2, entries.map { it.id }.toSet().size)
        assertTrue(entries.all { it.exerciseId == CATALOGUE })
        val sets = entries.flatMap { database.routineDao().getRoutineSets(it.id) }
        assertEquals(2, sets.size)
        assertTrue(sets.all { it.repetitions == null && it.weightKg == null })
        assertTrue(
            database.routineDao().getRoutineExercises(draft.id)
                .all { it.restDurationSeconds == 30 },
        )
        val envelope = readDurableOutboxRow(outbox().last()).request.envelope
        assertEquals(2, (envelope.payload as MutationPayload.RoutineUpsert).input.exercises.size)
    }

    @Test
    fun routine_addSetUsesSiblingCountAndSanitizes() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()
        repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE))
        val entryId = database.routineDao().getRoutineExercises(draft.id).single().id
        database.routineDao().insertRoutineSets(
            listOf(
                RoutineSetEntity("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", entryId, 1, 6, 60.0),
            ),
        )

        // A stale caller position is ignored; negative/nonfinite inputs sanitize to null.
        repoA.addSetToRoutineExercise(entryId, 99, -5, Double.NaN)
        val sets = database.routineDao().getRoutineSets(entryId)
        assertEquals(listOf(0, 1, 2), sets.map { it.position })
        assertNull(sets[2].repetitions)
        assertNull(sets[2].weightKg)

        repoA.addSetToRoutineExercise("99999999-9999-9999-9999-999999999999", 0, 1, 1.0)
        assertEquals(3, database.routineDao().getRoutineSets(entryId).size)
    }

    @Test
    fun routine_updateSetGuardsStaleRows() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()
        repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE))
        val entryId = database.routineDao().getRoutineExercises(draft.id).single().id
        val actual = database.routineDao().getRoutineSets(entryId).single()
        val before = database.routineDao().getRoutine(draft.id)?.localGeneration

        // A stale entity with a mismatched parent is rejected outright, even with
        // new values: nothing applies and no work is captured.
        repoA.updateRoutineSet(
            actual.copy(position = 7, routineExerciseId = "other-parent", repetitions = 9, weightKg = 90.0),
        )
        val stored = database.routineDao().getRoutineSet(actual.id)!!
        assertNull(stored.repetitions)
        assertNull(stored.weightKg)
        assertEquals(0, stored.position)
        assertEquals(entryId, stored.routineExerciseId)
        assertEquals(before, database.routineDao().getRoutine(draft.id)?.localGeneration)

        // Unchanged values are a no-op; negative values sanitize to null.
        repoA.updateRoutineSet(stored.copy(repetitions = null, weightKg = null))
        assertEquals(before, database.routineDao().getRoutine(draft.id)?.localGeneration)
        repoA.updateRoutineSet(stored.copy(repetitions = -3, weightKg = 20.0))
        assertNull(database.routineDao().getRoutineSet(actual.id)?.repetitions)
        assertEquals(20.0, database.routineDao().getRoutineSet(actual.id)?.weightKg)

        repoA.updateRoutineSet(stored.copy(id = "99999999-9999-9999-9999-999999999999"))
        assertEquals(1, outbox().size)
    }

    @Test
    fun routine_deleteExerciseUsesSemanticCommand() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()
        repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE, CATALOGUE))
        val entries = database.routineDao().getRoutineExercises(draft.id)

        repoA.deleteRoutineExercise(entries[0].id, draft.id)
        val remaining = database.routineDao().getRoutineExercises(draft.id)
        assertEquals(listOf(entries[1].id), remaining.map { it.id })
        assertEquals(listOf(0), remaining.map { it.position })
        assertTrue(database.routineDao().getRoutineSets(entries[0].id).isEmpty())
        val envelope = readDurableOutboxRow(outbox().last()).request.envelope
        assertEquals(MutationKind.ROUTINE_DELETE_EXERCISE, envelope.kind)
        assertEquals(entries[0].id, (envelope.payload as MutationPayload.RoutineDeleteExercise).routineExerciseId)

        // Mismatched parent or missing entry changes nothing.
        repoA.deleteRoutineExercise(entries[1].id, "99999999-9999-9999-9999-999999999999")
        repoA.deleteRoutineExercise("99999999-9999-9999-9999-999999999999", draft.id)
        assertEquals(1, database.routineDao().getRoutineExercises(draft.id).size)
    }

    @Test
    fun routine_deleteSetCascadesAndCaptures() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()
        repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE))
        val entryId = database.routineDao().getRoutineExercises(draft.id).single().id
        repoA.addSetToRoutineExercise(entryId, 0, 5, 50.0)
        val sets = database.routineDao().getRoutineSets(entryId)
        assertEquals(2, sets.size)

        // The caller set carries a stale position; the actual row wins.
        repoA.deleteRoutineSet(sets[0].copy(position = 9))
        val remaining = database.routineDao().getRoutineSets(entryId)
        assertEquals(listOf(sets[1].id), remaining.map { it.id })
        assertEquals(listOf(0), remaining.map { it.position })
        // A mismatched caller parent rejects the delete outright.
        val generations = database.routineDao().getRoutine(draft.id)?.localGeneration
        repoA.deleteRoutineSet(remaining.single().copy(routineExerciseId = "other-parent"))
        assertEquals(1, database.routineDao().getRoutineSets(entryId).size)
        assertEquals(generations, database.routineDao().getRoutine(draft.id)?.localGeneration)
        val envelope = readDurableOutboxRow(outbox().last()).request.envelope
        assertEquals(MutationKind.ROUTINE_DELETE_SET, envelope.kind)
        assertEquals(sets[0].id, (envelope.payload as MutationPayload.RoutineDeleteSet).routineSetId)

        repoA.deleteRoutineSet(sets[0].copy(id = "99999999-9999-9999-9999-999999999999"))
        assertEquals(1, database.routineDao().getRoutineSets(entryId).size)
    }

    @Test
    fun routine_deleteTombstonesAndHardDeletes() = runTest {
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()

        repoA.deleteRoutine(draft.id)
        val stored = database.routineDao().getRoutine(draft.id)!!
        assertNotNull(stored.deletedAtMillis)
        assertEquals(2L, stored.localGeneration)
        assertNull(repoA.routineDetails(draft.id))
        assertTrue(repoA.savedRoutineDetails().none { it.routine.id == draft.id })
        val envelope = readDurableOutboxRow(outbox().last()).request.envelope
        assertEquals(MutationKind.AGGREGATE_DELETE, envelope.kind)

        // Deleting an already-hidden root is a no-op.
        val count = outbox().size
        repoA.deleteRoutine(draft.id)
        repoA.deleteRoutine("99999999-9999-9999-9999-999999999999")
        assertEquals(count, outbox().size)

        // Signed-out deletion stays a hard delete with cascades.
        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = null, sessionGeneration = 0L),
        )
        insertExercises(exerciseFixture(id = CATALOGUE))
        val localRepo = repo(RepositorySession.SIGNED_OUT)
        val local = localRepo.createRoutineDraft()
        localRepo.addExercisesToRoutine(local.id, listOf(CATALOGUE))
        localRepo.deleteRoutine(local.id)
        assertNull(database.routineDao().getRoutine(local.id))
        assertTrue(database.routineDao().getRoutineExercises(local.id).isEmpty())
    }

    @Test
    fun routine_savedAutosaveKeepsDraftClearWithBlankAndEmpty() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()
        repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE))
        repoA.saveRoutine(draft.id, "Saved")
        val savedId = draft.id

        // Saved autosaves accept blank names and empty children without demoting.
        repoA.updateRoutineName(savedId, "")
        val blanked = database.routineDao().getRoutine(savedId)!!
        assertEquals("", blanked.name)
        assertEquals(false, blanked.isDraft)
        val blankEnvelope = readDurableOutboxRow(outbox().last()).request.envelope
        assertEquals(
            MutationKind.ROUTINE_UPSERT,
            blankEnvelope.kind,
        )
        assertEquals(
            "",
            (blankEnvelope.payload as MutationPayload.RoutineUpsert).input.name,
        )

        val entryId = database.routineDao().getRoutineExercises(savedId).single().id
        repoA.deleteRoutineExercise(entryId, savedId)
        repoA.updateRoutineName(savedId, "Still Saved")
        val emptied = database.routineDao().getRoutine(savedId)!!
        assertEquals(false, emptied.isDraft)
        assertTrue(database.routineDao().getRoutineExercises(savedId).isEmpty())
        val emptyEnvelope = readDurableOutboxRow(outbox().last()).request.envelope
        assertEquals(
            0,
            (emptyEnvelope.payload as MutationPayload.RoutineUpsert).input.exercises.size,
        )
        // The intervening semantic delete stays a barrier: the rename appended.
        assertEquals(MutationKind.ROUTINE_DELETE_EXERCISE, readDurableOutboxRow(
            outbox()[outbox().size - 2],
        ).request.envelope.kind)
    }

    @Test
    fun routine_foreignAndTombstonedRootsStayUntouched() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        val foreignId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val tombId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        insertRoutineAggregate(
            routine = RoutineEntity(foreignId, "Foreign", isDraft = false, accountId = "account-b"),
            exercises = listOf(
                RoutineExerciseEntity("re-foreign", foreignId, CATALOGUE, 0),
            ),
            sets = listOf(
                RoutineSetEntity("rs-foreign", "re-foreign", 0, 5, 50.0),
            ),
        )
        insertRoutineAggregate(
            routine = RoutineEntity(
                tombId, "Tomb", isDraft = false, accountId = ACCOUNT_A, deletedAtMillis = 3L,
            ),
            exercises = listOf(
                RoutineExerciseEntity("re-tomb", tombId, CATALOGUE, 0),
            ),
            sets = listOf(
                RoutineSetEntity("rs-tomb", "re-tomb", 0, 5, 50.0),
            ),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()

        suspend fun attemptAll(id: String, entryId: String, setId: RoutineSetEntity) {
            repoA.updateRoutineName(id, "Ghost")
            repoA.saveRoutine(id, "Ghost")
            repoA.addExercisesToRoutine(id, listOf(CATALOGUE))
            repoA.addSetToRoutineExercise(entryId, 0, 1, 1.0)
            repoA.updateRoutineSet(setId.copy(repetitions = 9, weightKg = 90.0))
            repoA.updateRoutineExerciseRestDuration(entryId, 90)
            repoA.deleteRoutineExercise(entryId, id)
            repoA.deleteRoutineSet(setId)
            repoA.deleteRoutine(id)
        }
        val foreignSet = database.routineDao().getRoutineSet("rs-foreign")!!
        attemptAll(foreignId, "re-foreign", foreignSet)
        val tombSet = database.routineDao().getRoutineSet("rs-tomb")!!
        attemptAll(tombId, "re-tomb", tombSet)

        assertEquals(0L, database.routineDao().getRoutine(foreignId)?.localGeneration)
        assertEquals(0L, database.routineDao().getRoutine(tombId)?.localGeneration)
        assertEquals("Foreign", database.routineDao().getRoutine(foreignId)?.name)
        assertEquals(1, database.routineDao().getRoutineExercises(foreignId).size)
        assertEquals(1, database.routineDao().getRoutineExercises(tombId).size)
        assertEquals(5, database.routineDao().getRoutineSet("rs-foreign")?.repetitions)
        assertEquals(5, database.routineDao().getRoutineSet("rs-tomb")?.repetitions)
        assertEquals(0, outbox().size)
    }

    @Test
    fun routine_frozenPredecessorNeverChanges() = runTest {
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()
        val firstMutation = outbox().single().mutationId
        database.syncDao().freezePendingMutation(firstMutation, ACCOUNT_A)
        val frozenBefore = database.syncDao().getOutboxMutation(firstMutation, ACCOUNT_A)!!

        repoA.updateRoutineName(draft.id, "Later")
        val rows = outbox()
        assertEquals(2, rows.size)
        assertEquals(firstMutation, rows.first().mutationId)
        assertEquals("frozen", rows.first().state)
        // The frozen predecessor row and its wrapper bytes are untouched.
        assertEquals(frozenBefore, database.syncDao().getOutboxMutation(firstMutation, ACCOUNT_A))
    }

    @Test
    fun routine_captureFailureRollsBack() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val draft = repoA.createRoutineDraft()
        // Freeze the create row so the addition appends through a blocked insert.
        val createdMutation = outbox().single().mutationId
        database.syncDao().freezePendingMutation(createdMutation, ACCOUNT_A)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_outbox_insert BEFORE INSERT ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            repoA.addExercisesToRoutine(draft.id, listOf(CATALOGUE))
            fail("Expected trigger failure")
        } catch (_: android.database.sqlite.SQLiteException) {
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_outbox_insert")
        }
        assertTrue(database.routineDao().getRoutineExercises(draft.id).isEmpty())
        assertEquals(1L, database.routineDao().getRoutine(draft.id)?.localGeneration)
        assertEquals(1, outbox().size)
    }

    @Test
    fun routine_staleFenceThrows() = runTest {
        database.syncDao().upsertDeviceState(device())
        val stale = repo(RepositorySession(ACCOUNT_A, GENERATION - 1))
        try {
            stale.updateRoutineName("routine-1", "Ghost")
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
    }
}
