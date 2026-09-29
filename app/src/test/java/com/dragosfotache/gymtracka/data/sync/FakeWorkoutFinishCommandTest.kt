package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_A
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_B
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_PUSH_UP
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_SQUAT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.FINISHED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.STARTED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncHarness.Companion.assertRejectedWithCode
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Independent tests for the fake `workout.finish` command. Expected records
 * and batches are constructed by hand from the specification, never derived
 * from the service under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeWorkoutFinishCommandTest {
    companion object {
        const val W1 = "11111111-1111-1111-1111-111111111111"
        const val W2 = "22222222-2222-2222-2222-222222222222"
        const val W3 = "33333333-3333-3333-3333-333333333333"
        const val UNKNOWN = "99999999-9999-9999-9999-999999999999"
        const val E1 = "55555555-5555-5555-5555-555555555551"
        const val E2 = "55555555-5555-5555-5555-555555555552"
        const val E3 = "55555555-5555-5555-5555-555555555553"
        const val E9 = "55555555-5555-5555-5555-555555555559"
        const val S1 = "66666666-6666-6666-6666-666666666661"
        const val S2 = "66666666-6666-6666-6666-666666666662"
        const val S3 = "66666666-6666-6666-6666-666666666663"
        const val S4 = "66666666-6666-6666-6666-666666666664"
        const val S5 = "66666666-6666-6666-6666-666666666665"
        const val S6 = "66666666-6666-6666-6666-666666666666"
        const val S7 = "66666666-6666-6666-6666-666666666667"
        const val S8 = "66666666-6666-6666-6666-666666666668"
        const val S9 = "66666666-6666-6666-6666-666666666669"
    }

    private fun key(id: String) = FakeSyncService.RootKey(AggregateType.WORKOUT, id)

    private fun upsertChange(id: String, record: WorkoutRecordModel, revision: Long) =
        SyncChangeModel(AggregateType.WORKOUT, id, ChangeOperation.UPSERT, revision, CanonicalRecord.Workout(record))

    @Test
    fun finish_removesUnfinishedAndEmptyExercisesAndRenumbersSurvivors() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Morning Workout", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    startedAtMillis = STARTED_AT,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            notes = "tired",
                            restDurationSeconds = 90,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 61),
                                FakeSyncFixtures.workoutSet(S2, 1, null, null, false, null),
                                FakeSyncFixtures.workoutSet(S3, 2, 8, 50.0, true, 45),
                            ),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            restDurationSeconds = 0,
                            sets = listOf(FakeSyncFixtures.workoutSet(S4, 0, 5, 40.0, false, null)),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E3,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 2,
                            notes = "easy",
                            restDurationSeconds = 45,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S5, 0, null, null, false, null),
                                FakeSyncFixtures.workoutSet(S6, 1, 5, 20.0, true, null),
                                FakeSyncFixtures.workoutSet(S7, 2, 6, 22.5, false, null),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = harness.pushA(harness.finishRequest(W1, FINISHED_AT))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        // Hand-built from the specification: E2 disappears, unfinished sets go,
        // both levels renumber from zero, and survivor IDs, notes, configured
        // rest (90/45), nullable inputs, and recorded rest survive untouched.
        val expectedRecord = WorkoutRecordModel(
            id = W1,
            name = "Morning Workout",
            status = "finished",
            startedAtMillis = STARTED_AT,
            finishedAtMillis = FINISHED_AT,
            revision = 3L,
            exercises = listOf(
                WorkoutExerciseModel(
                    id = E1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    notes = "tired",
                    restDurationSeconds = 90,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 61),
                        FakeSyncFixtures.workoutSet(S3, 1, 8, 50.0, true, 45),
                    ),
                    exerciseName = "Push-Up",
                ),
                WorkoutExerciseModel(
                    id = E3,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 1,
                    notes = "easy",
                    restDurationSeconds = 45,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(S6, 0, 5, 20.0, true, null),
                    ),
                    exerciseName = "Push-Up",
                ),
            ),
        )
        val expected = harness.expectedBatch(2, listOf(upsertChange(W1, expectedRecord, 3L)))
        assertEquals(expected, result.results[0].batch)
        assertEquals(listOf(expected), harness.acceptedBatches().takeLast(1))
        assertEquals(CanonicalRecord.Workout(expectedRecord), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertNull(harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun finish_acceptsDespiteStaleKnownRevision() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Morning Workout", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 60)),
                        ),
                    ),
                ),
            ),
        )

        // The stored revision is 2; a stale known revision never blocks a valid finish.
        val result = harness.pushA(harness.finishRequest(W1, FINISHED_AT, knownServerRevision = 1L))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout
        assertEquals("finished", stored.record.status)
        assertEquals(FINISHED_AT, stored.record.finishedAtMillis)
        assertEquals(3L, stored.record.revision)
        assertNull(harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun finish_rejectsWithoutCompletedSets() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Morning Workout", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, false, null),
                                FakeSyncFixtures.workoutSet(S2, 1, null, null, false, null),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // A separate account holds the exercise-less active workout: a second
        // start under the same account would tombstone the first.
        harness.pushB(harness.startRequest(W2, "Empty Workout", STARTED_AT))

        val stateA = harness.captureState(key(W1))
        assertRejectedWithCode(harness.pushA(harness.finishRequest(W1, FINISHED_AT)), 0, FakeSyncService.NO_COMPLETED_SETS)
        stateA.assertUnchanged()
        // An exercise-less active workout also has no completed set.
        val stateB = harness.captureState(key(W2), account = ACCOUNT_B)
        assertRejectedWithCode(harness.pushB(harness.finishRequest(W2, FINISHED_AT)), 0, FakeSyncService.NO_COMPLETED_SETS)
        stateB.assertUnchanged()
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
        assertEquals(W2, harness.service.activeWorkoutId(ACCOUNT_B))
    }

    @Test
    fun finish_rejectsFinishTimeBeforeStart() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Morning Workout", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 60)),
                        ),
                    ),
                ),
            ),
        )

        val state = harness.captureState(key(W1))
        assertRejectedWithCode(
            harness.pushA(harness.finishRequest(W1, STARTED_AT - 1L)),
            0,
            FakeSyncService.INVALID_FINISH_TIME,
        )
        state.assertUnchanged()
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun finish_rejectsFinishedDeletedForeignAndMissingTargets() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Morning Workout", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 60)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, harness.pushA(harness.finishRequest(W1, FINISHED_AT)).results[0].status)

        // An already finished workout rejects without touching the finished record.
        var state = harness.captureState(key(W1), key(W2), key(W3))
        assertRejectedWithCode(harness.pushA(harness.finishRequest(W1, FINISHED_AT + 1_000L)), 0, FakeSyncService.IMMUTABLE_FINISHED)
        state.assertUnchanged()

        // A foreign caller sees the same non-disclosing lookup code as for missing roots.
        assertRejectedWithCode(harness.pushB(harness.finishRequest(W1, FINISHED_AT)), 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        assertRejectedWithCode(harness.pushA(harness.finishRequest(UNKNOWN, FINISHED_AT)), 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Deleting the finished workout leaves a tombstone the finish command cannot revive.
        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W1))
        state = harness.captureState(key(W1))
        assertRejectedWithCode(harness.pushA(harness.finishRequest(W1, FINISHED_AT)), 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // A deleted-then-unrelated active workout still finishes normally with
        // the next per-root revision and no gap consumed by the rejections.
        harness.pushA(harness.startRequest(W3, "Fresh Workout", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W3,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E9,
                            exerciseId = BUNDLED_SQUAT,
                            position = 0,
                            sets = listOf(FakeSyncFixtures.workoutSet(S9, 0, 5, 40.0, true, 30)),
                        ),
                    ),
                ),
            ),
        )
        val result = harness.pushA(harness.finishRequest(W3, FINISHED_AT))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W3))
        assertNull(harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun finish_keepsChildReservationsAgainstReparenting() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Morning Workout", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 60),
                                FakeSyncFixtures.workoutSet(S2, 1, null, null, false, null),
                            ),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            sets = listOf(FakeSyncFixtures.workoutSet(S8, 0, 5, 40.0, true, 30)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, harness.pushA(harness.finishRequest(W1, FINISHED_AT)).results[0].status)
        // Account B owns a separate live active workout for the collision probes.
        assertEquals(MutationStatus.ACCEPTED, harness.pushB(harness.startRequest(W2)).results[0].status)

        val state = harness.captureState(key(W1), key(W2))
        // The removed unfinished set ID cannot be reparented into another root.
        assertRejectedWithCode(
            harness.pushB(
                harness.upsertRequest(
                    FakeSyncFixtures.workoutInput(
                        id = W2,
                        exercises = listOf(
                            FakeSyncFixtures.workoutEntry(
                                id = E9,
                                exerciseId = BUNDLED_PUSH_UP,
                                position = 0,
                                sets = listOf(FakeSyncFixtures.workoutSet(S2, 0)),
                            ),
                        ),
                    ),
                ),
            ),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        state.assertUnchanged()
        // Survivor IDs stay reserved to the finished root as well.
        assertRejectedWithCode(
            harness.pushB(
                harness.upsertRequest(
                    FakeSyncFixtures.workoutInput(
                        id = W2,
                        exercises = listOf(
                            FakeSyncFixtures.workoutEntry(
                                id = S1,
                                exerciseId = BUNDLED_PUSH_UP,
                                position = 0,
                                sets = listOf(FakeSyncFixtures.workoutSet(S3, 0)),
                            ),
                        ),
                    ),
                ),
            ),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        state.assertUnchanged()
    }
}
