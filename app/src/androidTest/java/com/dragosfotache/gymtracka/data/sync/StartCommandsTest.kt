package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.routineExerciseFixture
import com.dragosfotache.gymtracka.data.gym.routineFixture
import com.dragosfotache.gymtracka.data.gym.routineSetFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Atomic start/source-command capture through GymRepository. Real Room, no mocked DAOs. */
class StartCommandsTest : GymDatabaseTest() {

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val GENERATION = 7L
        const val STARTED_AT = 1_700_000_000_000L
        const val CATALOGUE = "44444444-4444-4444-4444-444444444444"
        const val OLD_ACTIVE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val OLD_ENTRY = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val OLD_SET = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val SOURCE = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val SOURCE_ENTRY = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val SOURCE_SET = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        const val SOURCE_ROUTINE = "00000000-0000-0000-0000-000000000000"
        const val SOURCE_ROUTINE_ENTRY = "11111111-2222-3333-4444-555555555555"
        const val SOURCE_ROUTINE_SET = "66666666-7777-8888-9999-aaaaaaaaaaaa"
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

    private suspend fun seedActive(
        id: String = OLD_ACTIVE,
        accountId: String? = ACCOUNT_A,
        entryId: String = OLD_ENTRY,
        setId: String = OLD_SET,
    ) {
        insertWorkoutAggregate(
            workout = workoutFixture(id = id, startedAtMillis = STARTED_AT).copy(accountId = accountId),
            exercises = listOf(
                workoutExerciseFixture(id = entryId, workoutId = id, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = setId, workoutExerciseId = entryId, position = 0),
            ),
        )
    }

    private suspend fun seedFinishedSource() {
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = SOURCE,
                name = "Finished",
                startedAtMillis = STARTED_AT - 10_000L,
                finishedAtMillis = STARTED_AT - 5_000L,
                status = "finished",
            ).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(
                    id = SOURCE_ENTRY,
                    workoutId = SOURCE,
                    exerciseId = CATALOGUE,
                    notes = "Keep",
                    restDurationSeconds = 75,
                ),
            ),
            sets = listOf(
                workoutSetFixture(
                    id = SOURCE_SET,
                    workoutExerciseId = SOURCE_ENTRY,
                    position = 0,
                    repetitions = 8,
                    weightKg = 70.0,
                    isCompleted = true,
                    restSeconds = 60,
                ),
            ),
        )
    }

    private suspend fun seedSavedRoutineSource() {
        insertRoutineAggregate(
            routine = routineFixture(id = SOURCE_ROUTINE, name = "Strength").copy(accountId = ACCOUNT_A),
            exercises = listOf(
                routineExerciseFixture(
                    id = SOURCE_ROUTINE_ENTRY,
                    routineId = SOURCE_ROUTINE,
                    exerciseId = CATALOGUE,
                    restDurationSeconds = 75,
                ),
            ),
            sets = listOf(
                routineSetFixture(
                    id = SOURCE_ROUTINE_SET,
                    routineExerciseId = SOURCE_ROUTINE_ENTRY,
                    repetitions = 6,
                    weightKg = 80.0,
                ),
            ),
        )
    }

    @Test
    fun start_emptyReplacesLocalActiveWithHardDelete() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = null, sessionGeneration = 0L),
        )
        val localRepo = repo(RepositorySession.SIGNED_OUT)
        insertWorkoutAggregate(
            workout = workoutFixture(id = OLD_ACTIVE).copy(accountId = null),
            exercises = listOf(
                workoutExerciseFixture(id = OLD_ENTRY, workoutId = OLD_ACTIVE, exerciseId = CATALOGUE),
            ),
            sets = listOf(
                workoutSetFixture(id = OLD_SET, workoutExerciseId = OLD_ENTRY),
            ),
        )

        val started = localRepo.startEmptyWorkout(STARTED_AT)

        assertNull(database.workoutDao().getWorkout(OLD_ACTIVE))
        assertTrue(database.workoutDao().getWorkoutExercises(OLD_ACTIVE).isEmpty())
        assertEquals("active", started.status)
        assertEquals(1L, started.localGeneration)
        assertEquals("local_only", started.syncState)
        assertEquals(started, database.workoutDao().getWorkout(started.id))
        assertEquals(0, outbox().size)
    }

    @Test
    fun start_emptyReplacesAccountActiveWithTombstone() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()

        val started = repoA.startEmptyWorkout(STARTED_AT)

        // Old active is a hidden tombstone with children removed, generation advanced.
        val old = database.workoutDao().getWorkout(OLD_ACTIVE)!!
        assertNotNull(old.deletedAtMillis)
        assertEquals("pending", old.syncState)
        assertEquals(1L, old.localGeneration)
        assertTrue(database.workoutDao().getWorkoutExercises(OLD_ACTIVE).isEmpty())
        // Only the new semantic command is queued, with both affected roots.
        val row = outbox().single()
        assertEquals("workout.start", row.mutationType)
        val request = readDurableOutboxRow(row).request
        assertEquals(MutationKind.WORKOUT_START, request.envelope.kind)
        assertEquals(AggregateType.WORKOUT, request.envelope.aggregateType)
        assertEquals(started.id, request.envelope.aggregateId)
        val payload = request.envelope.payload as MutationPayload.WorkoutStart
        assertEquals(started.id, payload.id)
        assertEquals(started.name, payload.name)
        assertEquals(STARTED_AT, payload.startedAtMillis)
        assertEquals(
            listOf(started.id to 1L, OLD_ACTIVE to 1L),
            request.affectedRoots.map { it.aggregateId to it.localGeneration },
        )
        assertEquals(1L, started.localGeneration)
        assertEquals("pending", started.syncState)
        assertEquals(listOf(started), database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A))
    }

    @Test
    fun start_missingSourcePreservesActive() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()

        assertNull(repoA.performWorkoutAgain("99999999-9999-9999-9999-999999999999", STARTED_AT))
        assertNull(repoA.startWorkoutFromRoutine("99999999-9999-9999-9999-999999999999", STARTED_AT))
        assertNull(repoA.saveWorkoutAsRoutine("99999999-9999-9999-9999-999999999999", "Name"))

        assertEquals(OLD_ACTIVE, database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A).single().id)
        assertEquals(0L, database.workoutDao().getWorkout(OLD_ACTIVE)?.localGeneration)
        assertEquals(0, outbox().size)
    }

    @Test
    fun start_invalidSourcesPreserveActive() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()
        seedFinishedSource()
        // Foreign finished source is invisible in this scope.
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff",
                finishedAtMillis = STARTED_AT - 5_000L,
                status = "finished",
            ).copy(accountId = ACCOUNT_B),
        )
        // Draft routine source is ineligible.
        insertRoutineAggregate(
            routine = routineFixture(id = "cccccccc-cccc-cccc-cccc-cccccccccccc", isDraft = true)
                .copy(accountId = ACCOUNT_A),
        )

        // Active workout is not a finished source; draft is not a saved source;
        // blank routine names are rejected; foreign sources are invisible.
        assertNull(repoA.performWorkoutAgain(OLD_ACTIVE, STARTED_AT))
        assertNull(repoA.startWorkoutFromRoutine("cccccccc-cccc-cccc-cccc-cccccccccccc", STARTED_AT))
        assertNull(repoA.saveWorkoutAsRoutine(SOURCE, ""))
        assertNull(repoA.saveWorkoutAsRoutine(SOURCE, "   "))
        assertNull(repoA.saveWorkoutAsRoutine(OLD_ACTIVE, "Name"))
        assertNull(
            repoA.performWorkoutAgain("bbbbbbbb-cccc-dddd-eeee-ffffffffffff", STARTED_AT),
        )

        assertEquals(OLD_ACTIVE, database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A).single().id)
        assertEquals(0L, database.workoutDao().getWorkout(OLD_ACTIVE)?.localGeneration)
        assertEquals(0L, database.workoutDao().getWorkout(SOURCE)?.localGeneration)
        assertEquals(0, outbox().size)
    }

    @Test
    fun start_blankOrEmptyRoutineCannotStart() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()
        // Saved-but-blank and saved-but-empty autosaves stay editable, never startable.
        insertRoutineAggregate(
            routine = routineFixture(id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", name = "").copy(
                accountId = ACCOUNT_A,
            ),
            exercises = listOf(
                routineExerciseFixture(id = "entry-blank", routineId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", exerciseId = CATALOGUE),
            ),
            sets = listOf(
                routineSetFixture(id = "set-blank", routineExerciseId = "entry-blank"),
            ),
        )
        insertRoutineAggregate(
            routine = routineFixture(id = "ffffffff-eeee-dddd-cccc-bbbbbbbbbbbb", name = "Empty").copy(
                accountId = ACCOUNT_A,
            ),
        )

        assertNull(repoA.startWorkoutFromRoutine("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", STARTED_AT))
        assertNull(repoA.startWorkoutFromRoutine("ffffffff-eeee-dddd-cccc-bbbbbbbbbbbb", STARTED_AT))

        assertEquals(OLD_ACTIVE, database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A).single().id)
        assertEquals(0L, database.workoutDao().getWorkout(OLD_ACTIVE)?.localGeneration)
        assertEquals(0, outbox().size)
    }

    @Test
    fun start_hiddenReferenceSourcesRejected() = runTest {
        insertExercises(
            exerciseFixture(id = CATALOGUE),
            exerciseFixture(id = HIDDEN_CUSTOM, name = "Hidden", isUserCreated = true),
        )
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()
        // Finished source with a hidden exercise reference.
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = SOURCE,
                finishedAtMillis = STARTED_AT - 5_000L,
                status = "finished",
            ).copy(accountId = ACCOUNT_A),
            exercises = listOf(
                workoutExerciseFixture(id = SOURCE_ENTRY, workoutId = SOURCE, exerciseId = HIDDEN_CUSTOM),
            ),
            sets = listOf(
                workoutSetFixture(id = SOURCE_SET, workoutExerciseId = SOURCE_ENTRY),
            ),
        )
        // Saved routine source with a hidden exercise reference.
        insertRoutineAggregate(
            routine = routineFixture(id = SOURCE_ROUTINE, name = "Strength").copy(accountId = ACCOUNT_A),
            exercises = listOf(
                routineExerciseFixture(
                    id = SOURCE_ROUTINE_ENTRY,
                    routineId = SOURCE_ROUTINE,
                    exerciseId = HIDDEN_CUSTOM,
                ),
            ),
            sets = listOf(
                routineSetFixture(id = SOURCE_ROUTINE_SET, routineExerciseId = SOURCE_ROUTINE_ENTRY),
            ),
        )

        assertNull(repoA.performWorkoutAgain(SOURCE, STARTED_AT))
        assertNull(repoA.saveWorkoutAsRoutine(SOURCE, "Reusable"))
        assertNull(repoA.startWorkoutFromRoutine(SOURCE_ROUTINE, STARTED_AT))

        // Source, old active, generations, and outbox are all preserved.
        assertEquals(1, database.workoutDao().getWorkoutExercises(SOURCE).size)
        assertEquals(1, database.routineDao().getRoutineExercises(SOURCE_ROUTINE).size)
        assertEquals(OLD_ACTIVE, database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A).single().id)
        assertEquals(0L, database.workoutDao().getWorkout(OLD_ACTIVE)?.localGeneration)
        assertEquals(0L, database.workoutDao().getWorkout(SOURCE)?.localGeneration)
        assertEquals(0L, database.routineDao().getRoutine(SOURCE_ROUTINE)?.localGeneration)
        assertEquals(0, outbox().size)
        assertTrue(database.workoutDao().getAllWorkouts().none { it.id != OLD_ACTIVE && it.id != SOURCE })
    }

    @Test
    fun start_performAgainCopiesBindingsAndNulls() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedFinishedSource()
        val sourceSetsBefore = database.workoutDao().getWorkoutSets(SOURCE_ENTRY)
        val sourceEntryBefore = database.workoutDao().getWorkoutExercise(SOURCE_ENTRY)

        val clone = repoA.performWorkoutAgain(SOURCE, STARTED_AT)!!
        assertEquals("Finished", clone.name)
        assertEquals("active", clone.status)
        assertNull(clone.finishedAtMillis)
        assertEquals(1L, clone.localGeneration)

        val details = database.workoutDao().getWorkoutDetails(clone.id)!!
        val copiedEntry = details.exercises.single()
        assertEquals(CATALOGUE, copiedEntry.workoutExercise.exerciseId)
        assertEquals("Keep", copiedEntry.workoutExercise.notes)
        assertEquals(75, copiedEntry.workoutExercise.restDurationSeconds)
        val copiedSet = copiedEntry.sets.single()
        assertEquals(8, copiedSet.repetitions)
        assertEquals(70.0, copiedSet.weightKg)
        assertEquals(false, copiedSet.isCompleted)
        assertNull(copiedSet.restSeconds)

        // Source rows are never modified.
        assertEquals(sourceEntryBefore, database.workoutDao().getWorkoutExercise(SOURCE_ENTRY))
        assertEquals(sourceSetsBefore, database.workoutDao().getWorkoutSets(SOURCE_ENTRY))
        assertEquals(0L, database.workoutDao().getWorkout(SOURCE)?.localGeneration)

        // Exact ordered bindings match the Room rows.
        val request = readDurableOutboxRow(outbox().single()).request
        assertEquals(MutationKind.WORKOUT_PERFORM_AGAIN, request.envelope.kind)
        val payload = request.envelope.payload as MutationPayload.WorkoutPerformAgain
        assertEquals(SOURCE, payload.sourceWorkoutId)
        assertEquals(clone.id, payload.id)
        assertEquals(STARTED_AT, payload.startedAtMillis)
        assertEquals(1, payload.childIds.size)
        assertEquals(SOURCE_ENTRY, payload.childIds.single().sourceExerciseId)
        assertEquals(copiedEntry.workoutExercise.id, payload.childIds.single().id)
        assertEquals(SOURCE_SET, payload.childIds.single().sets.single().sourceSetId)
        assertEquals(copiedSet.id, payload.childIds.single().sets.single().id)
    }

    @Test
    fun start_fromRoutineCopiesAndBinds() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedSavedRoutineSource()

        val started = repoA.startWorkoutFromRoutine(SOURCE_ROUTINE, STARTED_AT)!!
        assertEquals("Strength", started.name)
        assertEquals(1L, started.localGeneration)

        val details = database.workoutDao().getWorkoutDetails(started.id)!!
        val copiedEntry = details.exercises.single()
        assertEquals(CATALOGUE, copiedEntry.workoutExercise.exerciseId)
        assertEquals("", copiedEntry.workoutExercise.notes)
        assertEquals(75, copiedEntry.workoutExercise.restDurationSeconds)
        val copiedSet = copiedEntry.sets.single()
        assertEquals(6, copiedSet.repetitions)
        assertEquals(80.0, copiedSet.weightKg)
        assertEquals(false, copiedSet.isCompleted)
        assertNull(copiedSet.restSeconds)

        val request = readDurableOutboxRow(outbox().single()).request
        assertEquals(AggregateType.WORKOUT, request.envelope.aggregateType)
        assertEquals(MutationKind.ROUTINE_START_WORKOUT, request.envelope.kind)
        val payload = request.envelope.payload as MutationPayload.RoutineStartWorkout
        assertEquals(SOURCE_ROUTINE, payload.sourceRoutineId)
        assertEquals(started.id, payload.id)
        assertEquals(SOURCE_ROUTINE_ENTRY, payload.childIds.single().sourceExerciseId)
        assertEquals(copiedEntry.workoutExercise.id, payload.childIds.single().id)
        assertEquals(SOURCE_ROUTINE_SET, payload.childIds.single().sets.single().sourceSetId)
        assertEquals(copiedSet.id, payload.childIds.single().sets.single().id)
        assertEquals(0L, database.routineDao().getRoutine(SOURCE_ROUTINE)?.localGeneration)
    }

    @Test
    fun start_saveAsRoutineCopiesTemplate() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedFinishedSource()

        val routine = repoA.saveWorkoutAsRoutine(SOURCE, "Reusable")!!
        assertEquals("Reusable", routine.name)
        assertEquals(false, routine.isDraft)
        assertEquals(ACCOUNT_A, routine.accountId)
        assertEquals(1L, routine.localGeneration)

        val details = database.routineDao().getRoutineDetails(routine.id)!!
        val copiedEntry = details.exercises.single()
        assertEquals(CATALOGUE, copiedEntry.routineExercise.exerciseId)
        assertEquals(75, copiedEntry.routineExercise.restDurationSeconds)
        val copiedSet = copiedEntry.sets.single()
        assertEquals(8, copiedSet.repetitions)
        assertEquals(70.0, copiedSet.weightKg)

        // Source is retained untouched; no prior active deletion for this command.
        assertEquals(0L, database.workoutDao().getWorkout(SOURCE)?.localGeneration)
        val request = readDurableOutboxRow(outbox().single()).request
        assertEquals(AggregateType.ROUTINE, request.envelope.aggregateType)
        assertEquals(MutationKind.WORKOUT_SAVE_AS_ROUTINE, request.envelope.kind)
        val payload = request.envelope.payload as MutationPayload.WorkoutSaveAsRoutine
        assertEquals(SOURCE, payload.sourceWorkoutId)
        assertEquals(routine.id, payload.id)
        assertEquals("Reusable", payload.name)
        assertEquals(SOURCE_ENTRY, payload.childIds.single().sourceExerciseId)
        assertEquals(copiedEntry.routineExercise.id, payload.childIds.single().id)
        assertEquals(SOURCE_SET, payload.childIds.single().sets.single().sourceSetId)
        assertEquals(copiedSet.id, payload.childIds.single().sets.single().id)
    }

    @Test
    fun start_replacementIsAtomicUnderFailure() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()
        seedFinishedSource()
        val oldEntryBefore = database.workoutDao().getWorkoutExercise(OLD_ENTRY)
        val oldSetsBefore = database.workoutDao().getWorkoutSets(OLD_ENTRY)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_start_insert BEFORE INSERT ON workout_exercises " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            var failure: Throwable? = null
            try {
                repoA.performWorkoutAgain(SOURCE, STARTED_AT)
            } catch (throwable: Throwable) {
                failure = throwable
            }
            assertNotNull(failure)
            // Old and new roots/children/outbox are completely restored.
            assertEquals(OLD_ACTIVE, database.workoutDao().getWorkout(OLD_ACTIVE)?.id)
            assertEquals(0L, database.workoutDao().getWorkout(OLD_ACTIVE)?.localGeneration)
            assertEquals(null, database.workoutDao().getWorkout(OLD_ACTIVE)?.deletedAtMillis)
            assertEquals(oldEntryBefore, database.workoutDao().getWorkoutExercise(OLD_ENTRY))
            assertEquals(oldSetsBefore, database.workoutDao().getWorkoutSets(OLD_ENTRY))
            assertEquals(
                1,
                database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A).size,
            )
            assertEquals(0, outbox().size)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_start_insert")
        }
    }

    @Test
    fun start_replacementRollbackAfterCapture() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()
        seedFinishedSource()
        val oldEntryBefore = database.workoutDao().getWorkoutExercise(OLD_ENTRY)
        val oldSetsBefore = database.workoutDao().getWorkoutSets(OLD_ENTRY)
        // Fail AFTER capture: block the old-active tombstone that runs last.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_old_tombstone BEFORE UPDATE ON workouts " +
                "WHEN NEW.deleted_at_millis IS NOT NULL " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            var failure: Throwable? = null
            try {
                repoA.performWorkoutAgain(SOURCE, STARTED_AT)
            } catch (throwable: Throwable) {
                failure = throwable
            }
            assertNotNull(failure)
            // Old root/children/generation exactly restored, new rows absent, outbox empty.
            assertEquals(OLD_ACTIVE, database.workoutDao().getWorkout(OLD_ACTIVE)?.id)
            assertEquals(0L, database.workoutDao().getWorkout(OLD_ACTIVE)?.localGeneration)
            assertEquals(null, database.workoutDao().getWorkout(OLD_ACTIVE)?.deletedAtMillis)
            assertEquals("local_only", database.workoutDao().getWorkout(OLD_ACTIVE)?.syncState)
            assertEquals(oldEntryBefore, database.workoutDao().getWorkoutExercise(OLD_ENTRY))
            assertEquals(oldSetsBefore, database.workoutDao().getWorkoutSets(OLD_ENTRY))
            assertEquals(
                1,
                database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A).size,
            )
            assertEquals(0L, database.workoutDao().getWorkout(SOURCE)?.localGeneration)
            assertEquals(0, outbox().size)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_old_tombstone")
        }
    }

    @Test
    fun start_emptyFinishedSourceRejected() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = SOURCE,
                finishedAtMillis = STARTED_AT - 5_000L,
                status = "finished",
            ).copy(accountId = ACCOUNT_A),
        )

        assertNull(repoA.performWorkoutAgain(SOURCE, STARTED_AT))
        assertNull(repoA.saveWorkoutAsRoutine(SOURCE, "Reusable"))

        assertEquals(OLD_ACTIVE, database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A).single().id)
        assertEquals(0L, database.workoutDao().getWorkout(OLD_ACTIVE)?.localGeneration)
        assertEquals(0L, database.workoutDao().getWorkout(SOURCE)?.localGeneration)
        assertEquals(0, outbox().size)
        assertTrue(database.workoutDao().getAllWorkouts().none { it.id != OLD_ACTIVE && it.id != SOURCE })
    }

    @Test
    fun start_frozenStartThenAutosaveAppends() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()

        val started = repoA.startEmptyWorkout(STARTED_AT)
        val firstMutation = outbox().single().mutationId
        database.syncDao().freezePendingMutation(firstMutation, ACCOUNT_A)
        val frozenBefore = database.syncDao().getOutboxMutation(firstMutation, ACCOUNT_A)!!

        repoA.updateWorkoutName(started.id, "Later")
        val rows = outbox()
        assertEquals(2, rows.size)
        assertEquals("frozen", rows.first().state)
        assertEquals(frozenBefore, database.syncDao().getOutboxMutation(firstMutation, ACCOUNT_A))
        assertEquals("workout.upsert", rows.last().mutationType)
    }

    @Test
    fun start_clearsMultipleOldActivesAndIsolatesAccounts() = runTest {
        insertExercises(exerciseFixture(id = CATALOGUE))
        database.syncDao().upsertDeviceState(device())
        val repoA = repo()
        seedActive()
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = "99999999-9999-9999-9999-999999999999",
                startedAtMillis = STARTED_AT - 1_000L,
            ).copy(accountId = ACCOUNT_A),
        )
        insertWorkoutAggregate(
            workout = workoutFixture(
                id = "88888888-8888-8888-8888-888888888888",
                startedAtMillis = STARTED_AT - 2_000L,
            ).copy(accountId = ACCOUNT_B),
        )

        val started = repoA.startEmptyWorkout(STARTED_AT)

        // All selected-scope old actives are replaced; the other account is untouched.
        assertEquals(listOf(started.id), database.workoutDao().getScopedActiveWorkouts(ACCOUNT_A).map { it.id })
        assertEquals(
            "88888888-8888-8888-8888-888888888888",
            database.workoutDao().getScopedActiveWorkouts(ACCOUNT_B).single().id,
        )
        assertEquals(0L, database.workoutDao().getWorkout("88888888-8888-8888-8888-888888888888")?.localGeneration)
        val request = readDurableOutboxRow(outbox().single()).request
        assertEquals(3, request.affectedRoots.size)
    }
}
