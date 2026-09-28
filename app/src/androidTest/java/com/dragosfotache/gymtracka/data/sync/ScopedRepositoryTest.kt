package com.dragosfotache.gymtracka.data.sync

import android.database.sqlite.SQLiteConstraintException
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.data.gym.exerciseEquipmentFixture
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.exerciseMuscleGroupFixture
import com.dragosfotache.gymtracka.data.gym.muscleGroupFixture
import com.dragosfotache.gymtracka.data.gym.routineExerciseFixture
import com.dragosfotache.gymtracka.data.gym.routineSetFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow
import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Scoped application reads and custom-exercise capture through GymRepository. */
class ScopedRepositoryTest : GymDatabaseTest() {

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val GENERATION = 7L
        const val CUSTOM_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val CUSTOM_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val CUSTOM_TOMB = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val CUSTOM_LOCAL = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val BUNDLED = "exercise-1"
        const val MUTATION_1 = "11111111-1111-1111-1111-111111111111"
        const val MUTATION_2 = "22222222-2222-2222-2222-222222222222"
        const val MUTATION_3 = "33333333-3333-3333-3333-333333333333"
        const val UUID_MUSCLE_1 = "c1111111-1111-1111-1111-111111111111"
        const val UUID_MUSCLE_2 = "c2222222-2222-2222-2222-222222222222"
        const val UUID_EQUIP_1 = "d1111111-1111-1111-1111-111111111111"
        const val UUID_EQUIP_2 = "d2222222-2222-2222-2222-222222222222"
    }

    private fun device(account: String? = ACCOUNT_A, generation: Long = GENERATION) =
        SyncDeviceStateEntity(activeAccountId = account, sessionGeneration = generation)

    private fun repo(
        session: RepositorySession = RepositorySession(ACCOUNT_A, GENERATION),
        mutations: List<String> = listOf(MUTATION_1, MUTATION_2, MUTATION_3),
        clock: () -> Long = { 9_000L },
    ): GymRepository {
        val ids = mutations.toMutableList()
        return GymRepository(database, session, { ids.removeFirst() }, clock)
    }

    private suspend fun seedCatalogueMatrix() {
        insertMuscleGroups(muscleGroupFixture())
        insertExercises(
            exerciseFixture(id = BUNDLED),
            exerciseFixture(id = CUSTOM_A, name = "Custom A", isUserCreated = true)
                .copy(accountId = ACCOUNT_A, category = "cat-a"),
            exerciseFixture(id = CUSTOM_B, name = "Custom B", isUserCreated = true)
                .copy(accountId = ACCOUNT_B, category = "cat-b"),
            exerciseFixture(id = CUSTOM_LOCAL, name = "Custom Local", isUserCreated = true)
                .copy(category = "cat-local"),
            exerciseFixture(id = CUSTOM_TOMB, name = "Custom Tomb", isUserCreated = true)
                .copy(accountId = ACCOUNT_A, deletedAtMillis = 3L),
        )
        database.exerciseDao().insertExerciseMuscleGroups(
            listOf(
                exerciseMuscleGroupFixture(BUNDLED, "muscle-1", "primary"),
                exerciseMuscleGroupFixture(CUSTOM_B, "muscle-1", "primary"),
            ),
        )
    }

    private fun assertRejectsSync(block: suspend () -> Any?) = runTest {
        try {
            block()
        } catch (_: SyncModelException) {
            return@runTest
        }
        fail("Expected SyncModelException")
    }

    @Test
    fun reads_hideForeignTombstonedAndWrongAccountRoots() = runTest {
        seedCatalogueMatrix()
        val repoA = repo()
        val repoB = repo(RepositorySession(ACCOUNT_B, GENERATION))
        val repoLocal = repo(RepositorySession.SIGNED_OUT)

        database.syncDao().upsertDeviceState(device(ACCOUNT_A))
        assertEquals(
            setOf(BUNDLED, CUSTOM_A),
            repoA.exerciseSummaries().map { it.exercise.id }.toSet(),
        )
        assertEquals(listOf("cat-a", "strength"), repoA.exerciseCategories().sorted())
        assertNull(repoA.exerciseDetails(CUSTOM_B))
        assertNull(repoA.exercise(CUSTOM_TOMB))
        assertNotNull(repoA.exerciseDetails(CUSTOM_A))

        database.syncDao().upsertDeviceState(device(ACCOUNT_B))
        assertEquals(
            setOf(BUNDLED, CUSTOM_B),
            repoB.exerciseSummaries().map { it.exercise.id }.toSet(),
        )

        database.syncDao().upsertDeviceState(device(null, 0L))
        assertEquals(
            setOf(BUNDLED, CUSTOM_LOCAL),
            repoLocal.exerciseSummaries().map { it.exercise.id }.toSet(),
        )

        // Bulk relationship joins never leak hidden custom roots.
        val musclesA = database.exerciseDao().getScopedAllMuscleGroups(ACCOUNT_A)
        assertTrue(musclesA.none { it.exerciseId == CUSTOM_B })
        assertTrue(musclesA.any { it.exerciseId == CUSTOM_A || it.exerciseId == BUNDLED })
    }

    @Test
    fun reads_neverLeakHiddenNamesThroughOwnedRoots() = runTest {
        seedCatalogueMatrix()
        database.syncDao().upsertDeviceState(device())
        val workoutId = "workout-1"
        insertWorkoutAggregate(
            workout = WorkoutEntity(
                id = workoutId,
                name = "W",
                startedAtMillis = 1_000L,
                finishedAtMillis = 2_000L,
                status = "finished",
                accountId = ACCOUNT_A,
            ),
            exercises = listOf(
                WorkoutExerciseEntity("we-bundled", workoutId, BUNDLED, 0),
                WorkoutExerciseEntity("we-foreign", workoutId, CUSTOM_B, 1),
                WorkoutExerciseEntity("we-tomb", workoutId, CUSTOM_TOMB, 2),
            ),
            sets = listOf(
                WorkoutSetEntity("ws-1", "we-bundled", 0, 5, 50.0, true, null),
            ),
        )
        val routineId = "routine-1"
        insertRoutineAggregate(
            routine = RoutineEntity(routineId, "R", isDraft = false, accountId = ACCOUNT_A),
            exercises = listOf(
                RoutineExerciseEntity("re-foreign", routineId, CUSTOM_B, 0),
            ),
            sets = listOf(
                RoutineSetEntity("rs-1", "re-foreign", 0, 5, 50.0),
            ),
        )
        val repoA = repo()

        val details = repoA.workoutDetails(workoutId)!!
        assertEquals(listOf("we-bundled"), details.exercises.map { it.workoutExercise.id })
        assertTrue(details.exercises.none { it.workoutExercise.exerciseName.contains("Custom") })

        val history = repoA.finishedWorkoutSummaries()
        assertEquals(1, history.size)
        assertEquals(listOf("Bench Press"), history.single().exercises.map { it.exerciseName })

        val routine = repoA.routineDetails(routineId)!!
        assertTrue(routine.exercises.isEmpty())

        // Raw internal reads stay complete for later capture/reconciliation.
        val raw = database.workoutDao().getWorkoutDetails(workoutId)!!
        assertEquals(3, raw.exercises.size)
    }

    @Test
    fun history_selectsDeterministicWinnerWithinScope() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        insertExercises(exerciseFixture(id = BUNDLED))
        val dao = database.workoutDao()
        val firstId = "00000000-0000-0000-0000-000000000001"
        val secondId = "00000000-0000-0000-0000-000000000002"
        insertWorkoutAggregate(
            workout = WorkoutEntity(firstId, "First", 1_000L, 5_000L, "finished", accountId = ACCOUNT_A),
            exercises = listOf(WorkoutExerciseEntity("we-first", firstId, BUNDLED, 0, restDurationSeconds = 60)),
            sets = listOf(WorkoutSetEntity("ws-first", "we-first", 0, 5, 50.0, true, null)),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity(secondId, "Second", 1_000L, 5_000L, "finished", accountId = ACCOUNT_A),
            exercises = listOf(WorkoutExerciseEntity("we-second", secondId, BUNDLED, 0, restDurationSeconds = 90)),
            sets = listOf(WorkoutSetEntity("ws-second", "we-second", 0, 8, 80.0, true, null)),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity("other", "Other", 1_000L, 9_000L, "finished", accountId = ACCOUNT_B),
            exercises = listOf(WorkoutExerciseEntity("we-other", "other", BUNDLED, 0, restDurationSeconds = 10)),
            sets = listOf(WorkoutSetEntity("ws-other", "we-other", 0, 1, 10.0, true, null)),
        )

        // Same finishedAt: higher workout UUID wins; other-account rows never leak in.
        assertEquals(90, dao.previousScopedRestDurationForExercise(BUNDLED, ACCOUNT_A))
        assertEquals(
            listOf(8 to 80.0),
            dao.previousScopedCompletedSetsForExercise(BUNDLED, ACCOUNT_A)
                .map { (it.repetitions ?: -1) to (it.weightKg ?: -1.0) },
        )
        assertNull(dao.previousScopedRestDurationForExercise(BUNDLED, null))
    }

    @Test
    fun reads_rejectStaleFences() = runTest {
        seedCatalogueMatrix()
        database.syncDao().upsertDeviceState(device())
        val stale = repo(RepositorySession(ACCOUNT_A, GENERATION - 1))

        suspend fun staleRead(block: suspend () -> Any?) {
            try {
                block()
                fail("Expected StaleSessionException")
            } catch (_: StaleSessionException) {
            }
        }
        staleRead { stale.exerciseSummaries() }
        staleRead { stale.activeWorkout() }
        staleRead { stale.finishedWorkoutSummaries() }
        staleRead { stale.routineDetails("routine-1") }
        try {
            stale.createCustomExercise("Name", null, emptySet(), null, null, "")
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)
    }

    @Test
    fun custom_createCapturesTypedRequest() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = UUID_MUSCLE_1, slug = "uuid-muscle-1", name = "Uuid Muscle"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()

        val id = repoA.createCustomExercise(
            name = "Cable Fly",
            primaryMuscleGroupId = UUID_MUSCLE_1,
            secondaryMuscleGroupIds = emptySet(),
            equipmentId = null,
            category = "strength",
            instructions = " Pull ",
        )
        val stored = database.exerciseDao().getById(id)!!
        assertEquals(ACCOUNT_A, stored.accountId)
        assertEquals(1L, stored.localGeneration)
        assertEquals("pending", stored.syncState)
        assertNull(stored.serverRevision)
        assertEquals(listOf("Pull"), stored.instructions)

        val rows = database.syncDao().getOutboxForAccount(ACCOUNT_A)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(MUTATION_1, row.mutationId)
        assertEquals("customExercise", row.aggregateType)
        assertEquals(id, row.aggregateId)
        assertEquals("customExercise.upsert", row.mutationType)
        assertEquals(1L, row.localGeneration)

        val envelope = readDurableOutboxRow(row).request.envelope
        assertEquals(MUTATION_1, envelope.mutationId)
        assertEquals(AggregateType.CUSTOM_EXERCISE, envelope.aggregateType)
        assertEquals(id, envelope.aggregateId)
        val input = (envelope.payload as MutationPayload.CustomExerciseUpsert).input
        assertEquals("Cable Fly", input.name)
        assertEquals(UUID_MUSCLE_1, input.primaryMuscleGroupId)
        assertTrue(!row.payloadJson.contains(ACCOUNT_A))
        assertTrue(!row.payloadJson.contains("token"))
    }

    @Test
    fun custom_signedOutCreatesLocalOnly() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        val repoLocal = repo(RepositorySession.SIGNED_OUT)

        val id = repoLocal.createCustomExercise(
            name = "Local",
            primaryMuscleGroupId = "muscle-1",
            secondaryMuscleGroupIds = emptySet(),
            equipmentId = null,
            category = null,
            instructions = "",
        )
        val stored = database.exerciseDao().getById(id)!!
        assertNull(stored.accountId)
        assertEquals(1L, stored.localGeneration)
        assertEquals("local_only", stored.syncState)
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)
    }

    @Test
    fun custom_noOpSkipsWorkAndEditsCoalesce() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = UUID_MUSCLE_1, slug = "uuid-muscle-1", name = "Uuid Muscle"),
            muscleGroupFixture(id = UUID_MUSCLE_2, slug = "uuid-muscle-2", name = "Second Muscle"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()

        val id = repoA.createCustomExercise(
            name = "Fly",
            primaryMuscleGroupId = UUID_MUSCLE_1,
            secondaryMuscleGroupIds = setOf(UUID_MUSCLE_2),
            equipmentId = null,
            category = null,
            instructions = "",
        )
        suspend fun outbox() = database.syncDao().getOutboxForAccount(ACCOUNT_A)
        assertEquals(1, outbox().size)

        // Reordered secondaries alone are not a domain edit.
        repoA.updateCustomExercise(id, "Fly", UUID_MUSCLE_1, setOf(UUID_MUSCLE_2), null, null, "")
        assertEquals(1L, database.exerciseDao().getById(id)?.localGeneration)
        assertEquals(1, outbox().size)

        // A real edit coalesces into the pending tail with a stable identity.
        repoA.updateCustomExercise(id, "Fly v2", UUID_MUSCLE_1, setOf(UUID_MUSCLE_2), null, null, "")
        assertEquals(2L, database.exerciseDao().getById(id)?.localGeneration)
        assertEquals(1, outbox().size)
        val row = outbox().single()
        assertEquals(MUTATION_1, row.mutationId)
        assertEquals(2L, row.localGeneration)
        val envelope = readDurableOutboxRow(row).request.envelope
        assertEquals("Fly v2", (envelope.payload as MutationPayload.CustomExerciseUpsert).input.name)

        // A frozen tail forces the next edit to append.
        database.syncDao().freezePendingMutation(MUTATION_1, ACCOUNT_A)
        repoA.updateCustomExercise(id, "Fly v3", UUID_MUSCLE_1, setOf(UUID_MUSCLE_2), null, null, "")
        assertEquals(2, outbox().size)
    }

    @Test
    fun custom_editPreservesMetadataAndQuantity() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = UUID_MUSCLE_1, slug = "uuid-muscle-1", name = "Uuid Muscle"),
            muscleGroupFixture(id = UUID_MUSCLE_2, slug = "uuid-muscle-2", name = "Second Muscle"),
        )
        insertEquipment(
            equipmentFixture(id = UUID_EQUIP_1, name = "Uuid Cable"),
            equipmentFixture(id = UUID_EQUIP_2, name = "Uuid Rope"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val id = CUSTOM_A
        database.exerciseDao().insertExercise(
            ExerciseEntity(
                id = id,
                name = "Custom",
                type = "reps",
                difficultyLevel = "intermediate",
                forceType = "pull",
                mechanics = "compound",
                category = "strength",
                instructions = null,
                isUserCreated = true,
                accountId = ACCOUNT_A,
            ),
        )
        database.exerciseDao().insertExerciseMuscleGroups(
            listOf(
                exerciseMuscleGroupFixture(exerciseId = id, muscleGroupId = UUID_MUSCLE_1, type = "primary"),
                exerciseMuscleGroupFixture(exerciseId = id, muscleGroupId = UUID_MUSCLE_2, type = "secondary"),
            ),
        )
        database.exerciseDao().insertExerciseEquipment(
            listOf(exerciseEquipmentFixture(exerciseId = id, equipmentId = UUID_EQUIP_1, numberOfItems = 3)),
        )

        repoA.updateCustomExercise(
            id, "Renamed", UUID_MUSCLE_1, setOf(UUID_MUSCLE_2), UUID_EQUIP_1, "strength", "",
        )
        val stored = database.exerciseDao().getById(id)!!
        assertEquals("intermediate", stored.difficultyLevel)
        assertEquals("pull", stored.forceType)
        assertEquals("compound", stored.mechanics)
        val equipmentRows = database.exerciseDao().getEquipment(id)
        assertEquals(1, equipmentRows.size)
        assertEquals(UUID_EQUIP_1, equipmentRows.single().id)
        assertEquals(3, equipmentRows.single().numberOfItems)

        val envelope = readDurableOutboxRow(
            database.syncDao().getOutboxForAccount(ACCOUNT_A).single(),
        ).request.envelope
        val input = (envelope.payload as MutationPayload.CustomExerciseUpsert).input
        assertEquals(3, input.equipmentNumItems)
        assertEquals(UUID_EQUIP_1, input.equipmentId)
        assertEquals("intermediate", input.difficultyLevel)
        assertEquals("pull", input.forceType)
        assertEquals("compound", input.mechanics)

        // Changing equipment resets to the UI null quantity in a single retained row.
        repoA.updateCustomExercise(
            id, "Renamed", UUID_MUSCLE_1, setOf(UUID_MUSCLE_2), UUID_EQUIP_2, "strength", "",
        )
        val changedRows = database.exerciseDao().getEquipment(id)
        assertEquals(1, changedRows.size)
        assertEquals(UUID_EQUIP_2, changedRows.single().id)
        assertNull(changedRows.single().numberOfItems)
    }

    @Test
    fun custom_rejectsInvalidAttempts() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val missing = "99999999-9999-9999-9999-999999999999"
        insertExercises(
            exerciseFixture(id = CUSTOM_A, name = "Owned", isUserCreated = true)
                .copy(accountId = ACCOUNT_A),
        )

        assertRejectsSync {
            repoA.createCustomExercise("", null, emptySet(), null, null, "")
        }
        assertRejectsSync {
            repoA.updateCustomExercise(CUSTOM_A, "", null, emptySet(), null, null, "")
        }
        // Missing, bundled, wrong-owner, and deleted attempts change nothing.
        insertExercises(exerciseFixture(id = BUNDLED))
        repoA.updateCustomExercise(missing, "Name", null, emptySet(), null, null, "")
        repoA.updateCustomExercise(BUNDLED, "Name", null, emptySet(), null, null, "")
        assertEquals("Owned", database.exerciseDao().getById(CUSTOM_A)?.name)
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)

        assertEquals(false, repoA.deleteCustomExercise(missing))
        assertEquals(false, repoA.deleteCustomExercise(BUNDLED))
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)
    }

    @Test
    fun custom_signedInBundledIsNoOp() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        insertExercises(exerciseFixture(id = BUNDLED))
        database.exerciseDao().insertExerciseMuscleGroups(
            listOf(exerciseMuscleGroupFixture(BUNDLED, "muscle-1", "primary")),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()

        repoA.updateCustomExercise(BUNDLED, "Changed", null, emptySet(), null, null, "")
        repoA.updateCustomExercise(BUNDLED, "", null, emptySet(), null, null, "")
        assertEquals(false, repoA.deleteCustomExercise(BUNDLED))

        assertEquals("Bench Press", database.exerciseDao().getById(BUNDLED)?.name)
        assertEquals(
            listOf("muscle-1" to "primary"),
            database.exerciseDao().getMuscleGroups(BUNDLED).map { it.id to it.relationshipType },
        )
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)
    }

    @Test
    fun custom_deleteTombstonesUnreferenced() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = UUID_MUSCLE_1, slug = "uuid-muscle-1", name = "Uuid Muscle"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val id = repoA.createCustomExercise("Doomed", UUID_MUSCLE_1, emptySet(), null, null, "")

        assertEquals(true, repoA.deleteCustomExercise(id))
        val stored = database.exerciseDao().getById(id)!!
        assertEquals(9_000L, stored.deletedAtMillis)
        assertEquals(2L, stored.localGeneration)
        assertTrue(database.exerciseDao().getMuscleGroups(id).isEmpty())
        assertNull(repoA.exerciseDetails(id))
        assertTrue(repoA.exerciseSummaries().none { it.exercise.id == id })

        val rows = database.syncDao().getOutboxForAccount(ACCOUNT_A)
        assertEquals(2, rows.size)
        val delete = rows.last()
        val envelope = readDurableOutboxRow(delete).request.envelope
        assertEquals(MutationKind.AGGREGATE_DELETE, envelope.kind)
        assertEquals(id, envelope.aggregateId)

        assertEquals(false, repoA.deleteCustomExercise(id))
        assertEquals(2, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)
    }

    @Test
    fun custom_deleteBlockedByRetainedReferences() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = UUID_MUSCLE_1, slug = "uuid-muscle-1", name = "Uuid Muscle"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val id = repoA.createCustomExercise("Used", UUID_MUSCLE_1, emptySet(), null, null, "")

        insertWorkoutAggregate(
            workout = WorkoutEntity("workout-1", "W", 1_000L, accountId = ACCOUNT_A),
            exercises = listOf(
                WorkoutExerciseEntity("we-1", "workout-1", id, 0),
            ),
        )
        insertRoutineAggregate(
            routine = RoutineEntity("routine-1", "R", isDraft = false, accountId = ACCOUNT_A),
            exercises = listOf(
                RoutineExerciseEntity("re-1", "routine-1", id, 0),
            ),
            sets = listOf(
                RoutineSetEntity("rs-1", "re-1", 0, 5, 50.0),
            ),
        )
        assertEquals(false, repoA.deleteCustomExercise(id))
        assertNotNull(repoA.exerciseDetails(id))
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)

        // A tombstoned root with physically retained children still blocks deletion.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET deleted_at_millis = 5 WHERE id = 'workout-1'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE routines SET deleted_at_millis = 5 WHERE id = 'routine-1'",
        )
        assertEquals(false, repoA.deleteCustomExercise(id))
        assertNotNull(
            "Blocked deletion must not strip the custom row",
            database.exerciseDao().getById(id),
        )

        // Removing the retained children releases both references.
        database.workoutDao().deleteWorkoutExerciseAndReorder("we-1", "workout-1")
        assertEquals(false, repoA.deleteCustomExercise(id))
        database.routineDao().deleteRoutineExerciseAndReorder("re-1", "routine-1")
        assertEquals(true, repoA.deleteCustomExercise(id))
        assertNull(repoA.exerciseDetails(id))
    }

    @Test
    fun custom_signedOutDeleteHardDeletesAndConstrains() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        val repoLocal = repo(RepositorySession.SIGNED_OUT)
        val id = repoLocal.createCustomExercise("Gone", "muscle-1", emptySet(), null, null, "")

        assertEquals(true, repoLocal.deleteCustomExercise(id))
        assertNull(repoLocal.exercise(id))
        assertTrue(database.exerciseDao().getMuscleGroups(id).isEmpty())

        val used = repoLocal.createCustomExercise("Used", "muscle-1", emptySet(), null, null, "")
        insertWorkoutAggregate(
            workout = WorkoutEntity("workout-1", "W", 1_000L),
            exercises = listOf(
                com.dragosfotache.gymtracka.data.gym.WorkoutExerciseEntity("we-1", "workout-1", used, 0),
            ),
        )
        try {
            repoLocal.deleteCustomExercise(used)
            fail("Expected reference constraint failure")
        } catch (_: SQLiteConstraintException) {
        }
        assertNotNull(repoLocal.exercise(used))
    }

    @Test
    fun custom_captureFailureRollsBack() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = UUID_MUSCLE_1, slug = "uuid-muscle-1", name = "Uuid Muscle"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_outbox_insert BEFORE INSERT ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            repoA.createCustomExercise("Lost", UUID_MUSCLE_1, emptySet(), null, null, "")
            fail("Expected trigger failure")
        } catch (_: android.database.sqlite.SQLiteException) {
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_outbox_insert")
        }
        assertTrue(repoA.exerciseSummaries().none { it.exercise.name == "Lost" })
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)
    }

    @Test
    fun reads_coverWorkoutRoutineFamiliesAcrossScopes() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        insertExercises(exerciseFixture(id = BUNDLED))
        insertWorkoutAggregate(
            workout = WorkoutEntity("active-a", "Active A", 1_000L, status = "active", accountId = ACCOUNT_A),
            exercises = listOf(workoutExerciseFixture(id = "we-active-a", workoutId = "active-a")),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity("finished-a", "Finished A", 1_000L, 2_000L, "finished", accountId = ACCOUNT_A),
            exercises = listOf(workoutExerciseFixture(id = "we-finished-a", workoutId = "finished-a")),
            sets = listOf(workoutSetFixture(id = "ws-a", workoutExerciseId = "we-finished-a", isCompleted = true)),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity(
                "tomb-a", "Tomb A", 1_000L, 2_000L, "finished",
                accountId = ACCOUNT_A, deletedAtMillis = 3L,
            ),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity("active-b", "Active B", 1_000L, status = "active", accountId = ACCOUNT_B),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity("finished-b", "Finished B", 1_000L, 2_000L, "finished", accountId = ACCOUNT_B),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity("active-local", "Active Local", 1_000L, status = "active"),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity("finished-local", "Finished Local", 1_000L, 2_000L, "finished"),
            exercises = listOf(workoutExerciseFixture(id = "we-finished-local", workoutId = "finished-local")),
            sets = listOf(workoutSetFixture(id = "ws-local", workoutExerciseId = "we-finished-local", isCompleted = true)),
        )
        insertRoutineAggregate(
            routine = RoutineEntity("saved-a", "Saved A", isDraft = false, accountId = ACCOUNT_A),
            exercises = listOf(routineExerciseFixture(id = "re-a", routineId = "saved-a")),
            sets = listOf(routineSetFixture(id = "rs-a", routineExerciseId = "re-a")),
        )
        insertRoutineAggregate(
            routine = RoutineEntity("draft-a", "", isDraft = true, accountId = ACCOUNT_A),
        )
        insertRoutineAggregate(
            routine = RoutineEntity("tomb-a", "Tomb A", isDraft = false, accountId = ACCOUNT_A, deletedAtMillis = 3L),
        )
        insertRoutineAggregate(
            routine = RoutineEntity("saved-b", "Saved B", isDraft = false, accountId = ACCOUNT_B),
        )
        insertRoutineAggregate(
            routine = RoutineEntity("saved-local", "Saved Local", isDraft = false),
        )

        val repoA = repo()
        val repoB = repo(RepositorySession(ACCOUNT_B, GENERATION))
        val repoLocal = repo(RepositorySession.SIGNED_OUT)

        database.syncDao().upsertDeviceState(device(ACCOUNT_A))
        assertEquals("active-a", repoA.activeWorkout()?.id)
        assertEquals(setOf("active-a", "finished-a"), repoA.workouts().map { it.id }.toSet())
        assertNotNull(repoA.workoutDetails("finished-a"))
        assertNull(repoA.workoutDetails("finished-b"))
        assertNull(repoA.workoutDetails("tomb-a"))
        assertEquals(listOf("finished-a"), repoA.finishedWorkoutSummaries().map { it.workout.id })
        assertEquals(listOf("saved-a"), repoA.savedRoutines().map { it.id })
        assertEquals(listOf("saved-a"), repoA.savedRoutineDetails().map { it.routine.id })
        assertNotNull(repoA.routineDetails("draft-a"))
        assertNull(repoA.routineDetails("saved-b"))
        assertNull(repoA.routineDetails("tomb-a"))

        database.syncDao().upsertDeviceState(device(ACCOUNT_B))
        assertEquals("active-b", repoB.activeWorkout()?.id)
        assertEquals(setOf("active-b", "finished-b"), repoB.workouts().map { it.id }.toSet())
        assertEquals(listOf("finished-b"), repoB.finishedWorkoutSummaries().map { it.workout.id })
        assertEquals(listOf("saved-b"), repoB.savedRoutines().map { it.id })
        assertNull(repoB.routineDetails("saved-a"))

        database.syncDao().upsertDeviceState(device(null, 0L))
        assertEquals("active-local", repoLocal.activeWorkout()?.id)
        assertEquals(
            setOf("active-local", "finished-local"),
            repoLocal.workouts().map { it.id }.toSet(),
        )
        assertEquals(listOf("finished-local"), repoLocal.finishedWorkoutSummaries().map { it.workout.id })
        assertEquals(listOf("saved-local"), repoLocal.savedRoutines().map { it.id })
        assertNull(repoLocal.workoutDetails("finished-a"))
    }

    @Test
    fun reads_isolateEquipmentBulkLinks() = runTest {
        insertEquipment(
            equipmentFixture(id = "equipment-1", name = "Barbell"),
            equipmentFixture(id = "equipment-2", name = "Dumbbell"),
        )
        insertExercises(
            exerciseFixture(id = BUNDLED),
            exerciseFixture(id = CUSTOM_A, name = "Custom A", isUserCreated = true).copy(accountId = ACCOUNT_A),
            exerciseFixture(id = CUSTOM_B, name = "Custom B", isUserCreated = true).copy(accountId = ACCOUNT_B),
        )
        database.exerciseDao().insertExerciseEquipment(
            listOf(
                exerciseEquipmentFixture(exerciseId = CUSTOM_A, equipmentId = "equipment-1"),
                exerciseEquipmentFixture(exerciseId = CUSTOM_B, equipmentId = "equipment-2"),
            ),
        )

        val equipmentA = database.exerciseDao().getScopedAllEquipment(ACCOUNT_A)
        assertEquals(listOf(CUSTOM_A), equipmentA.map { it.exerciseId })
        assertEquals(listOf("Barbell"), equipmentA.map { it.name })
        val equipmentB = database.exerciseDao().getScopedAllEquipment(ACCOUNT_B)
        assertEquals(listOf(CUSTOM_B), equipmentB.map { it.exerciseId })
    }

    @Test
    fun history_excludesTombstonedAndBreaksDuplicateTies() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        insertExercises(exerciseFixture(id = BUNDLED))
        val dao = database.workoutDao()
        // Tombstoned finished workout with the latest timestamp is excluded.
        insertWorkoutAggregate(
            workout = WorkoutEntity("tomb", "Tomb", 1_000L, 9_000L, "finished", accountId = ACCOUNT_A, deletedAtMillis = 3L),
            exercises = listOf(workoutExerciseFixture(id = "we-tomb", workoutId = "tomb", restDurationSeconds = 10)),
            sets = listOf(workoutSetFixture(id = "ws-tomb", workoutExerciseId = "we-tomb", repetitions = 1, weightKg = 10.0, isCompleted = true)),
        )
        // Duplicate occurrences in one workout: the first position wins for rest and sets.
        insertWorkoutAggregate(
            workout = WorkoutEntity("dup", "Dup", 1_000L, 5_000L, "finished", accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = "we-first", workoutId = "dup", position = 0, restDurationSeconds = 60),
                workoutExerciseFixture(id = "we-second", workoutId = "dup", position = 1, restDurationSeconds = 120),
            ),
            sets = listOf(
                workoutSetFixture(id = "ws-first", workoutExerciseId = "we-first", repetitions = 5, weightKg = 50.0, isCompleted = true),
                workoutSetFixture(id = "ws-second", workoutExerciseId = "we-second", repetitions = 8, weightKg = 80.0, isCompleted = true),
            ),
        )

        assertEquals(60, dao.previousScopedRestDurationForExercise(BUNDLED, ACCOUNT_A))
        assertEquals(
            listOf(5 to 50.0),
            dao.previousScopedCompletedSetsForExercise(BUNDLED, ACCOUNT_A)
                .map { (it.repetitions ?: -1) to (it.weightKg ?: -1.0) },
        )
    }

    @Test
    fun custom_reorderedSecondariesAreNoOp() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = UUID_MUSCLE_1, slug = "uuid-muscle-1", name = "Uuid Muscle"),
            muscleGroupFixture(id = UUID_MUSCLE_2, slug = "uuid-muscle-2", name = "Second Muscle"),
            muscleGroupFixture(id = "33333333-3333-3333-3333-333333333333", slug = "third", name = "Third Muscle"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val third = "33333333-3333-3333-3333-333333333333"

        val id = repoA.createCustomExercise(
            name = "Fly",
            primaryMuscleGroupId = UUID_MUSCLE_1,
            secondaryMuscleGroupIds = setOf(UUID_MUSCLE_2, third),
            equipmentId = null,
            category = null,
            instructions = "",
        )
        // Genuine reversed secondary ordering with two secondaries is not a domain edit.
        repoA.updateCustomExercise(id, "Fly", UUID_MUSCLE_1, setOf(third, UUID_MUSCLE_2), null, null, "")
        assertEquals(1L, database.exerciseDao().getById(id)?.localGeneration)
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)
    }

    @Test
    fun custom_editFailureRollsBackScalarsAndRelations() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = UUID_MUSCLE_1, slug = "uuid-muscle-1", name = "Uuid Muscle"),
            muscleGroupFixture(id = UUID_MUSCLE_2, slug = "uuid-muscle-2", name = "Second Muscle"),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        val id = repoA.createCustomExercise(
            "Kept", UUID_MUSCLE_1, setOf(UUID_MUSCLE_2), null, "strength", "Step",
        )
        val beforeRelations = database.exerciseDao().getMuscleGroups(id)
            .map { it.id to it.relationshipType }.toSet()

        // Freeze the create row so the edit appends through a blocked insert.
        val createdMutation = database.syncDao().getOutboxForAccount(ACCOUNT_A).single().mutationId
        database.syncDao().freezePendingMutation(createdMutation, ACCOUNT_A)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_outbox_insert BEFORE INSERT ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            repoA.updateCustomExercise(
                id, "Changed", UUID_MUSCLE_2, setOf(UUID_MUSCLE_1), null, "changed", "Changed step",
            )
            fail("Expected trigger failure")
        } catch (_: android.database.sqlite.SQLiteException) {
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_outbox_insert")
        }
        val stored = database.exerciseDao().getById(id)!!
        assertEquals("Kept", stored.name)
        assertEquals("strength", stored.category)
        assertEquals(listOf("Step"), stored.instructions)
        assertEquals(1L, stored.localGeneration)
        assertEquals(
            beforeRelations,
            database.exerciseDao().getMuscleGroups(id).map { it.id to it.relationshipType }.toSet(),
        )
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT_A).size)
    }
}
