package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_A
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_PUSH_UP
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_SQUAT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.FINISHED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.STARTED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncHarness.Companion.assertRejectedWithCode
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Independent tests for the fake `workout.deleteExercise` and
 * `workout.deleteSet` commands. Expected records and batches are
 * constructed by hand from the specification, never derived from the
 * service under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeWorkoutDeleteCommandTest {
    companion object {
        const val W1 = "10111111-1111-1111-1111-111111111111"
        const val W2 = "20222222-2222-2222-2222-222222222222"
        const val W_FOREIGN = "40444444-4444-4444-4444-444444444444"
        const val R1 = "30333333-3333-3333-3333-333333333333"
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
    }

    private fun key(id: String) = FakeSyncService.RootKey(AggregateType.WORKOUT, id)

    private fun routineKey(id: String) = FakeSyncService.RootKey(AggregateType.ROUTINE, id)

    /** Starts W1 with E1/E2/E3 (E2 holds three sets) at revisions 1-2. */
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
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            notes = "middle",
                            restDurationSeconds = 45,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 30),
                                FakeSyncFixtures.workoutSet(S3, 1, 6, 40.0, false, null),
                                FakeSyncFixtures.workoutSet(S4, 2, 5, 35.0, false, null),
                            ),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E3,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 2,
                            notes = "last",
                            restDurationSeconds = 30,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S5, 0, 12, 30.0, false, null),
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
                    exerciseId = BUNDLED_SQUAT,
                    position = 1,
                    notes = "middle",
                    restDurationSeconds = 45,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 30),
                        FakeSyncFixtures.workoutSet(S3, 1, 6, 40.0, false, null),
                        FakeSyncFixtures.workoutSet(S4, 2, 5, 35.0, false, null),
                    ),
                    exerciseName = "Squat",
                ),
                WorkoutExerciseModel(
                    id = E3,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 2,
                    notes = "last",
                    restDurationSeconds = 30,
                    sets = listOf(FakeSyncFixtures.workoutSet(S5, 0, 12, 30.0, false, null)),
                    exerciseName = "Push-Up",
                ),
            ),
        )

    @Test
    fun deleteExercise_removesMiddleEntryCascadesSetsAndRenumbers() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)

        val result = harness.pushA(harness.deleteWorkoutExerciseRequest(W1, E2))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val seeded = expectedSeeded()
        val expected = seeded.copy(
            revision = 3L,
            exercises = listOf(
                seeded.exercises[0],
                seeded.exercises[2].copy(position = 1),
            ),
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
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun deleteExercise_firstThenLastLeavesSingleRenumberedSurvivor() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)

        val first = harness.pushA(harness.deleteWorkoutExerciseRequest(W1, E1))
        assertEquals(MutationStatus.ACCEPTED, first.results[0].status)
        val seeded = expectedSeeded()
        val afterFirst = seeded.copy(
            revision = 3L,
            exercises = listOf(
                seeded.exercises[1].copy(position = 0),
                seeded.exercises[2].copy(position = 1),
            ),
        )
        val firstBatch = harness.expectedBatch(
            2,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    3L,
                    CanonicalRecord.Workout(afterFirst),
                ),
            ),
        )
        assertEquals(firstBatch, first.results[0].batch)

        val last = harness.pushA(harness.deleteWorkoutExerciseRequest(W1, E3))
        assertEquals(MutationStatus.ACCEPTED, last.results[0].status)
        val expected = afterFirst.copy(
            revision = 4L,
            exercises = listOf(afterFirst.exercises[0]),
        )
        val lastBatch = harness.expectedBatch(
            3,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    4L,
                    CanonicalRecord.Workout(expected),
                ),
            ),
        )
        assertEquals(lastBatch, last.results[0].batch)
        assertEquals(CanonicalRecord.Workout(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(4L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun deleteSet_removesMiddleThenLastSetAndRenumbers() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)

        val middle = harness.pushA(harness.deleteWorkoutSetRequest(W1, S3))
        assertEquals(MutationStatus.ACCEPTED, middle.results[0].status)
        val seeded = expectedSeeded()
        val afterMiddle = seeded.copy(
            revision = 3L,
            exercises = seeded.exercises.map { entry ->
                if (entry.id != E2) {
                    entry
                } else {
                    entry.copy(
                        sets = listOf(
                            FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 30),
                            FakeSyncFixtures.workoutSet(S4, 1, 5, 35.0, false, null),
                        ),
                    )
                }
            },
        )
        val middleBatch = harness.expectedBatch(
            2,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    3L,
                    CanonicalRecord.Workout(afterMiddle),
                ),
            ),
        )
        assertEquals(middleBatch, middle.results[0].batch)

        val last = harness.pushA(harness.deleteWorkoutSetRequest(W1, S4))
        assertEquals(MutationStatus.ACCEPTED, last.results[0].status)
        val expected = afterMiddle.copy(
            revision = 4L,
            exercises = afterMiddle.exercises.map { entry ->
                if (entry.id != E2) {
                    entry
                } else {
                    entry.copy(
                        sets = listOf(
                            FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 30),
                        ),
                    )
                }
            },
        )
        val lastBatch = harness.expectedBatch(
            3,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    4L,
                    CanonicalRecord.Workout(expected),
                ),
            ),
        )
        assertEquals(lastBatch, last.results[0].batch)
        assertEquals(CanonicalRecord.Workout(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(4L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun deleteSet_retainsExerciseWhenLastSetRemovedThenDeletesFirstSet() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)

        // E1 keeps its identity, notes, and configured rest with zero sets.
        val emptied = harness.pushA(harness.deleteWorkoutSetRequest(W1, S1))
        assertEquals(MutationStatus.ACCEPTED, emptied.results[0].status)
        val seeded = expectedSeeded()
        val afterEmptied = seeded.copy(
            revision = 3L,
            exercises = seeded.exercises.map { entry ->
                if (entry.id != E1) entry else entry.copy(sets = emptyList())
            },
        )
        val emptiedBatch = harness.expectedBatch(
            2,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    3L,
                    CanonicalRecord.Workout(afterEmptied),
                ),
            ),
        )
        assertEquals(emptiedBatch, emptied.results[0].batch)
        val retained = (harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout).record.exercises[0]
        assertEquals(E1, retained.id)
        assertEquals(0, retained.position)
        assertEquals("first", retained.notes)
        assertEquals(90, retained.restDurationSeconds)
        assertEquals(emptyList<WorkoutSetModel>(), retained.sets)

        // Deleting the first set of E2 renumbers its survivors from zero.
        val first = harness.pushA(harness.deleteWorkoutSetRequest(W1, S2))
        assertEquals(MutationStatus.ACCEPTED, first.results[0].status)
        val expected = afterEmptied.copy(
            revision = 4L,
            exercises = afterEmptied.exercises.map { entry ->
                if (entry.id != E2) {
                    entry
                } else {
                    entry.copy(
                        sets = listOf(
                            FakeSyncFixtures.workoutSet(S3, 0, 6, 40.0, false, null),
                            FakeSyncFixtures.workoutSet(S4, 1, 5, 35.0, false, null),
                        ),
                    )
                }
            },
        )
        val firstBatch = harness.expectedBatch(
            3,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    4L,
                    CanonicalRecord.Workout(expected),
                ),
            ),
        )
        assertEquals(firstBatch, first.results[0].batch)
        assertEquals(CanonicalRecord.Workout(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun deleteCommands_rejectBadTargetsWithoutStateChange() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)
        // Finished caller-owned root used as a finished-target case and as a
        // wrong-parent lookup source; a finished import keeps W1 active.
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
                                sets = listOf(FakeSyncFixtures.workoutSet(OS1, 0, 5, 20.0, true, 10)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, imported.results[0].status)
        val foreign = harness.pushB(harness.startRequest(W_FOREIGN, "Foreign", STARTED_AT))
        assertEquals(MutationStatus.ACCEPTED, foreign.results[0].status)

        // Unknown roots.
        var state = harness.captureState(key(W1), key(W2), account = ACCOUNT_A)
        var result = harness.pushA(harness.deleteWorkoutExerciseRequest(UNKNOWN, E1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteWorkoutSetRequest(UNKNOWN, S1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Foreign roots.
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteWorkoutExerciseRequest(W_FOREIGN, E1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteWorkoutSetRequest(W_FOREIGN, S1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Finished workout targets.
        state = harness.captureState(key(W2), key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteWorkoutExerciseRequest(W2, OE1))
        assertRejectedWithCode(result, 0, FakeSyncService.IMMUTABLE_FINISHED)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteWorkoutSetRequest(W2, OS1))
        assertRejectedWithCode(result, 0, FakeSyncService.IMMUTABLE_FINISHED)
        state.assertUnchanged()

        // Unknown children under the live root.
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteWorkoutExerciseRequest(W1, UNKNOWN))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteWorkoutSetRequest(W1, UNKNOWN))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Wrong-parent children: OE1/OS1 belong to W2, not W1.
        state = harness.captureState(key(W1), key(W2), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteWorkoutExerciseRequest(W1, OE1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteWorkoutSetRequest(W1, OS1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Level confusion: a set ID is not an exercise target and an
        // exercise ID is not a set target.
        state = harness.captureState(key(W1), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteWorkoutExerciseRequest(W1, S1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteWorkoutSetRequest(W1, E1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
    }

    @Test
    fun deleteExercise_rejectsDeletedRootWithoutStateChange() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)
        val deleted = harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W1))
        assertEquals(MutationStatus.ACCEPTED, deleted.results[0].status)

        val state = harness.captureState(key(W1), account = ACCOUNT_A)
        val exercise = harness.pushA(harness.deleteWorkoutExerciseRequest(W1, E1))
        assertRejectedWithCode(exercise, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        val set = harness.pushA(harness.deleteWorkoutSetRequest(W1, S1))
        assertRejectedWithCode(set, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
    }

    @Test
    fun deleteExercise_retainsRemovedIdsAsCollisionEvidence() = runTest {
        val harness = FakeSyncHarness(64)
        seedTarget(harness)

        val deleted = harness.pushA(harness.deleteWorkoutExerciseRequest(W1, E2))
        assertEquals(MutationStatus.ACCEPTED, deleted.results[0].status)

        // The removed exercise and set IDs cannot be reparented into another
        // root, even though their canonical children are gone.
        val state = harness.captureState(key(W1), routineKey(R1), account = ACCOUNT_A)
        val collision = harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R1,
                    isDraft = true,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = E2,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(
                                RoutineSetModel(
                                    S3,
                                    0,
                                    6,
                                    40.0,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertRejectedWithCode(collision, 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()

        // The same IDs restore cleanly into their owning root.
        val seeded = expectedSeeded()
        val restored = harness.pushA(
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
                            sets = listOf(FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 61)),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            notes = "middle",
                            restDurationSeconds = 45,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 30),
                                FakeSyncFixtures.workoutSet(S3, 1, 6, 40.0, false, null),
                                FakeSyncFixtures.workoutSet(S4, 2, 5, 35.0, false, null),
                            ),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E3,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 2,
                            notes = "last",
                            restDurationSeconds = 30,
                            sets = listOf(FakeSyncFixtures.workoutSet(S5, 0, 12, 30.0, false, null)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, restored.results[0].status)
        val expected = seeded.copy(revision = 4L)
        val expectedBatch = harness.expectedBatch(
            3,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W1,
                    ChangeOperation.UPSERT,
                    4L,
                    CanonicalRecord.Workout(expected),
                ),
            ),
        )
        assertEquals(expectedBatch, restored.results[0].batch)
        assertEquals(CanonicalRecord.Workout(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
    }
}
