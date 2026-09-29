package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_A
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_PUSH_UP
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_SQUAT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.FINISHED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.MUSCLE_CHEST
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.STARTED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncHarness.Companion.assertRejectedWithCode
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Independent tests for the fake `workout.replaceExercise` command.
 * Expected records and batches are constructed by hand from the
 * specification, never derived from the service under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeWorkoutReplaceExerciseCommandTest {
    companion object {
        const val W1 = "10111111-1111-1111-1111-111111111111"
        const val W2 = "20222222-2222-2222-2222-222222222222"
        const val W_FOREIGN = "40444444-4444-4444-4444-444444444444"
        const val UNKNOWN = "99999999-9999-9999-9999-999999999999"
        const val E1 = "11111111-1111-1111-1111-1111111111a1"
        const val E2 = "11111111-1111-1111-1111-1111111111a2"
        const val E3 = "11111111-1111-1111-1111-1111111111a3"
        const val S1 = "11111111-1111-1111-1111-1111111111b1"
        const val S2 = "11111111-1111-1111-1111-1111111111b2"
        const val S3 = "11111111-1111-1111-1111-1111111111b3"
        const val S4 = "11111111-1111-1111-1111-1111111111b4"
        const val S5 = "11111111-1111-1111-1111-1111111111b5"
        const val OE1 = "22222222-2222-2222-2222-2222222222a1"
        const val OS1 = "22222222-2222-2222-2222-2222222222b1"
        const val C_OWN = "c0000000-0000-0000-0000-000000000001"
        const val C_FOREIGN = "c0000000-0000-0000-0000-000000000002"
    }

    private fun key(id: String) = FakeSyncService.RootKey(AggregateType.WORKOUT, id)

    /** Starts W1 and populates three entries with notes, rest, and recorded rest (revisions 1-2). */
    private suspend fun seedTarget(harness: FakeSyncHarness) {
        val started = harness.pushA(harness.startRequest(W1, "Active", STARTED_AT))
        assertEquals(MutationStatus.ACCEPTED, started.results[0].status)
        val upserted = harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    name = "Active",
                    startedAtMillis = STARTED_AT,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            notes = "first",
                            restDurationSeconds = 90,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 61),
                            ),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E2,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 1,
                            notes = "middle",
                            restDurationSeconds = 45,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 30),
                                FakeSyncFixtures.workoutSet(S3, 1, 6, 40.0, false, null),
                            ),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E3,
                            exerciseId = BUNDLED_SQUAT,
                            position = 2,
                            notes = "last",
                            restDurationSeconds = 30,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S4, 0, 12, 30.0, false, null),
                                FakeSyncFixtures.workoutSet(S5, 1, 10, 30.0, false, null),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, upserted.results[0].status)
    }

    private fun expectedSeeded(): WorkoutRecordModel =
        WorkoutRecordModel(
            id = W1,
            name = "Active",
            status = "active",
            startedAtMillis = STARTED_AT,
            finishedAtMillis = null,
            revision = 2L,
            exercises = listOf(
                WorkoutExerciseModel(
                    id = E1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    notes = "first",
                    restDurationSeconds = 90,
                    sets = listOf(FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 61)),
                    exerciseName = "Push-Up",
                ),
                WorkoutExerciseModel(
                    id = E2,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 1,
                    notes = "middle",
                    restDurationSeconds = 45,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 30),
                        FakeSyncFixtures.workoutSet(S3, 1, 6, 40.0, false, null),
                    ),
                    exerciseName = "Push-Up",
                ),
                WorkoutExerciseModel(
                    id = E3,
                    exerciseId = BUNDLED_SQUAT,
                    position = 2,
                    notes = "last",
                    restDurationSeconds = 30,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(S4, 0, 12, 30.0, false, null),
                        FakeSyncFixtures.workoutSet(S5, 1, 10, 30.0, false, null),
                    ),
                    exerciseName = "Squat",
                ),
            ),
        )

    @Test
    fun replaceExercise_changesOnlyReferenceAndNamePreservingEverything() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)

        // A stale known revision never affects the replacement result.
        val result = harness.pushA(
            harness.replaceExerciseRequest(W1, E2, BUNDLED_SQUAT, knownServerRevision = 1L),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val seeded = expectedSeeded()
        val expected = seeded.copy(
            revision = 3L,
            exercises = seeded.exercises.map { entry ->
                if (entry.id == E2) entry.copy(exerciseId = BUNDLED_SQUAT, exerciseName = "Squat") else entry
            },
        )
        val expectedBatch = harness.expectedBatch(
            2,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    3L,
                    CanonicalRecord.Workout(expected),
                ),
            ),
        )
        assertEquals(expectedBatch, result.results[0].batch)
        assertEquals(listOf(expectedBatch), harness.acceptedBatches().takeLast(1))
        assertEquals(CanonicalRecord.Workout(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        // Identity, notes, configured rest, positions, sets, completion, and
        // recorded rest survive; only the reference and display name change.
        val replaced = (harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout).record.exercises[1]
        assertEquals(E2, replaced.id)
        assertEquals(1, replaced.position)
        assertEquals("middle", replaced.notes)
        assertEquals(45, replaced.restDurationSeconds)
        assertEquals(
            listOf(
                FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 30),
                FakeSyncFixtures.workoutSet(S3, 1, 6, 40.0, false, null),
            ),
            replaced.sets,
        )
        // Active pointer is unchanged.
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun replaceExercise_resolvesOwnedCustomName() = runTest {
        val harness = FakeSyncHarness(64)
        val custom = harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C_OWN, "Cable Fly")))
        assertEquals(MutationStatus.ACCEPTED, custom.results[0].status)
        seedTarget(harness)

        val result = harness.pushA(harness.replaceExerciseRequest(W1, E1, C_OWN))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val seeded = expectedSeeded()
        val expected = seeded.copy(
            revision = 3L,
            exercises = seeded.exercises.map { entry ->
                if (entry.id == E1) entry.copy(exerciseId = C_OWN, exerciseName = "Cable Fly") else entry
            },
        )
        val expectedBatch = harness.expectedBatch(
            3,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    3L,
                    CanonicalRecord.Workout(expected),
                ),
            ),
        )
        assertEquals(expectedBatch, result.results[0].batch)
        assertEquals(CanonicalRecord.Workout(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun replaceExercise_rejectsBadTargetsAndReferencesWithoutStateChange() = runTest {
        val harness = FakeSyncHarness(64)
        val custom = harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C_OWN, "Cable Fly")))
        assertEquals(MutationStatus.ACCEPTED, custom.results[0].status)
        seedTarget(harness)
        // Finished caller-owned root holding an entry used both as a
        // finished-target case and as a wrong-parent lookup source. A
        // finished import never disturbs the sole-active pointer.
        val imported = harness.pushA(
            harness.importRequest(
                ImportRecord.Workout(
                    WorkoutImportModel(
                        W2,
                        "Other",
                        "finished",
                        STARTED_AT,
                        FINISHED_AT,
                        listOf(
                            FakeSyncFixtures.workoutEntry(
                                id = OE1,
                                exerciseId = BUNDLED_SQUAT,
                                position = 0,
                                notes = "other",
                                restDurationSeconds = 75,
                                sets = listOf(FakeSyncFixtures.workoutSet(OS1, 0, 5, 20.0, true, 10)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // Foreign root owned by account B.
        val foreign = harness.pushB(harness.startRequest(W_FOREIGN, "Foreign", STARTED_AT))
        assertEquals(MutationStatus.ACCEPTED, imported.results[0].status)
        assertEquals(MutationStatus.ACCEPTED, foreign.results[0].status)

        // Unknown root.
        var state = harness.captureState(key(W1), key(W2), account = ACCOUNT_A)
        var result = harness.pushA(harness.replaceExerciseRequest(UNKNOWN, E1, BUNDLED_SQUAT))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Foreign root.
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.replaceExerciseRequest(W_FOREIGN, E1, BUNDLED_SQUAT))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Finished workout target.
        state = harness.captureState(key(W2), key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.replaceExerciseRequest(W2, OE1, BUNDLED_SQUAT))
        assertRejectedWithCode(result, 0, FakeSyncService.IMMUTABLE_FINISHED)
        state.assertUnchanged()

        // Unknown child under a live root.
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.replaceExerciseRequest(W1, UNKNOWN, BUNDLED_SQUAT))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Wrong-parent child: OE1 belongs to W2, not W1.
        state = harness.captureState(key(W1), key(W2), account = ACCOUNT_A)
        result = harness.pushA(harness.replaceExerciseRequest(W1, OE1, BUNDLED_SQUAT))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Set ID passed as the exercise target.
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.replaceExerciseRequest(W1, S1, BUNDLED_SQUAT))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Unknown new exercise reference.
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.replaceExerciseRequest(W1, E1, UNKNOWN))
        assertRejectedWithCode(result, 0, FakeSyncService.INVALID_REFERENCE)
        state.assertUnchanged()

        // Foreign custom reference is invisible to the caller.
        val foreignCustom = harness.pushB(harness.customUpsertRequest(FakeSyncFixtures.customInput(C_FOREIGN, "Other Fly")))
        assertEquals(MutationStatus.ACCEPTED, foreignCustom.results[0].status)
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.replaceExerciseRequest(W1, E1, C_FOREIGN))
        assertRejectedWithCode(result, 0, FakeSyncService.INVALID_REFERENCE)
        state.assertUnchanged()

        // Cross-namespace reference: a muscle UUID is never an exercise.
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.replaceExerciseRequest(W1, E1, MUSCLE_CHEST))
        assertRejectedWithCode(result, 0, FakeSyncService.INVALID_REFERENCE)
        state.assertUnchanged()
    }

    @Test
    fun replaceExercise_rejectsDeletedRootWithoutStateChange() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)
        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W1))

        val state = harness.captureState(key(W1), account = ACCOUNT_A)
        val result = harness.pushA(harness.replaceExerciseRequest(W1, E1, BUNDLED_SQUAT))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
    }
}
