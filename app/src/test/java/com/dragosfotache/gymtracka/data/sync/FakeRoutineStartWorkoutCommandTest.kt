package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_A
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_PUSH_UP
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_SQUAT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.STARTED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncHarness.Companion.assertRejectedWithCode
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.ChildExerciseBinding
import com.dragosfotache.gymtracka.data.sync.model.ChildSetBinding
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent tests for the fake `routine.startWorkout` command. Expected
 * records and batches are constructed by hand from the specification, never
 * derived from the service under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeRoutineStartWorkoutCommandTest {
    companion object {
        const val R_SRC = "11111111-1111-1111-1111-111111111111"
        const val W_OLD = "22222222-2222-2222-2222-222222222222"
        const val W_NEW = "33333333-3333-3333-3333-333333333333"
        const val W_TOMB = "55555555-5555-5555-5555-555555555555"
        const val UNKNOWN = "99999999-9999-9999-9999-999999999999"
        const val R_FOREIGN = "44444444-4444-4444-4444-444444444441"
        const val E1 = "66666666-6666-6666-6666-666666666661"
        const val E2 = "66666666-6666-6666-6666-666666666662"
        const val E3 = "66666666-6666-6666-6666-666666666663"
        const val S1 = "77777777-7777-7777-7777-777777777771"
        const val S2 = "77777777-7777-7777-7777-777777777772"
        const val S3 = "77777777-7777-7777-7777-777777777773"
        const val S4 = "77777777-7777-7777-7777-777777777774"
        const val NE1 = "88888888-8888-8888-8888-888888888881"
        const val NE2 = "88888888-8888-8888-8888-888888888882"
        const val NS1 = "88888888-8888-8888-8888-888888888883"
        const val NS2 = "88888888-8888-8888-8888-888888888884"
        const val NS3 = "88888888-8888-8888-8888-888888888885"
        const val EXTRA_EXERCISE = "88888888-8888-8888-8888-888888888886"
        const val EXTRA_SET = "88888888-8888-8888-8888-888888888887"
        const val EXTRA_SOURCE_SET = "88888888-8888-8888-8888-888888888888"
        const val EXTRA_TARGET_SET = "88888888-8888-8888-8888-888888888889"
        const val FE1 = "99999999-9999-9999-9999-999999999981"
        const val FS1 = "99999999-9999-9999-9999-999999999982"
        const val NEW_START = STARTED_AT + 60_000L
    }

    private fun routineKey(id: String) = FakeSyncService.RootKey(AggregateType.ROUTINE, id)
    private fun workoutKey(id: String) = FakeSyncService.RootKey(AggregateType.WORKOUT, id)

    private fun validPlan(): List<ChildExerciseBinding> =
        listOf(
            ChildExerciseBinding(E1, NE1, listOf(ChildSetBinding(S1, NS1), ChildSetBinding(S2, NS2))),
            ChildExerciseBinding(E2, NE2, listOf(ChildSetBinding(S3, NS3))),
        )

    /** Creates the draft routine and saves it (revisions 1-2). */
    private suspend fun seedSavedRoutine(harness: FakeSyncHarness) {
        harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R_SRC,
                    name = "Strength Plan",
                    isDraft = true,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            restDurationSeconds = 90,
                            sets = listOf(
                                RoutineSetModel(S1, 0, 10, 60.0),
                                RoutineSetModel(S2, 1, 8, 50.0),
                            ),
                        ),
                        FakeSyncFixtures.routineEntry(
                            id = E2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            sets = listOf(RoutineSetModel(S3, 0, 5, 40.0)),
                        ),
                    ),
                ),
            ),
        )
        harness.pushA(harness.routineSaveRequest(R_SRC, "Strength Plan"))
    }

    /** Hand-built saved source routine. */
    private fun expectedSource(): RoutineRecordModel =
        RoutineRecordModel(
            id = R_SRC,
            name = "Strength Plan",
            isDraft = false,
            revision = 2L,
            exercises = listOf(
                RoutineExerciseModel(
                    id = E1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    restDurationSeconds = 90,
                    sets = listOf(
                        RoutineSetModel(S1, 0, 10, 60.0),
                        RoutineSetModel(S2, 1, 8, 50.0),
                    ),
                    exerciseName = "Push-Up",
                ),
                RoutineExerciseModel(
                    id = E2,
                    exerciseId = BUNDLED_SQUAT,
                    position = 1,
                    restDurationSeconds = 30,
                    sets = listOf(RoutineSetModel(S3, 0, 5, 40.0)),
                    exerciseName = "Squat",
                ),
            ),
        )

    /** Hand-built started workout: routine name/rest/inputs, empty notes, fresh sets. */
    private fun expectedWorkout(): WorkoutRecordModel =
        WorkoutRecordModel(
            id = W_NEW,
            name = "Strength Plan",
            status = "active",
            startedAtMillis = NEW_START,
            finishedAtMillis = null,
            revision = 1L,
            exercises = listOf(
                WorkoutExerciseModel(
                    id = NE1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    notes = "",
                    restDurationSeconds = 90,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(NS1, 0, 10, 60.0, false, null),
                        FakeSyncFixtures.workoutSet(NS2, 1, 8, 50.0, false, null),
                    ),
                    exerciseName = "Push-Up",
                ),
                WorkoutExerciseModel(
                    id = NE2,
                    exerciseId = BUNDLED_SQUAT,
                    position = 1,
                    notes = "",
                    restDurationSeconds = 30,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(NS3, 0, 5, 40.0, false, null),
                    ),
                    exerciseName = "Squat",
                ),
            ),
        )

    @Test
    fun startWorkout_startsFromSavedRoutineAndReplacesActiveAtomically() = runTest {
        val harness = FakeSyncHarness()
        seedSavedRoutine(harness)
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))

        // A stale known revision never affects the started workout.
        val result = harness.pushA(
            harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, validPlan(), knownServerRevision = 1L),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val expected = harness.expectedBatch(
            3,
            listOf(
                SyncChangeModel(AggregateType.WORKOUT, W_OLD, ChangeOperation.DELETE, 2L, null),
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W_NEW,
                    ChangeOperation.UPSERT,
                    1L,
                    CanonicalRecord.Workout(expectedWorkout()),
                ),
            ),
        )
        assertEquals(expected, result.results[0].batch)
        assertEquals(listOf(expected), harness.acceptedBatches().takeLast(1))
        assertEquals(CanonicalRecord.Workout(expectedWorkout()), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_NEW))
        assertEquals(1L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W_NEW))
        // The saved source is preserved exactly: record, revision, and active
        // replacement never touches it.
        assertEquals(CanonicalRecord.Routine(expectedSource()), harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R_SRC))
        assertEquals(2L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.ROUTINE, R_SRC))
        assertTrue(harness.service.isTombstoned(ACCOUNT_A, AggregateType.WORKOUT, W_OLD))
        assertEquals(2L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W_OLD))
        assertEquals(W_NEW, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun startWorkout_withoutPriorActive_emitsSingleUpsert() = runTest {
        val harness = FakeSyncHarness()
        seedSavedRoutine(harness)
        // Routine commands never set the sole-active pointer, so no tombstone is staged.
        assertNull(harness.service.activeWorkoutId(ACCOUNT_A))

        val result = harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, validPlan()))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val expected = harness.expectedBatch(
            2,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W_NEW,
                    ChangeOperation.UPSERT,
                    1L,
                    CanonicalRecord.Workout(expectedWorkout()),
                ),
            ),
        )
        assertEquals(expected, result.results[0].batch)
        assertEquals(W_NEW, harness.service.activeWorkoutId(ACCOUNT_A))
        assertEquals(CanonicalRecord.Routine(expectedSource()), harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R_SRC))
    }

    @Test
    fun startWorkout_rejectsDraftAndInvalidSavedSources() = runTest {
        val harness = FakeSyncHarness()
        // A draft routine cannot start a workout.
        harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R_SRC,
                    name = "Strength Plan",
                    isDraft = true,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(RoutineSetModel(S1, 0, 10, 60.0)),
                        ),
                    ),
                ),
            ),
        )
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))
        var state = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, validPlan())),
            0,
            FakeSyncService.INVALID_ROUTINE_STATE,
        )
        state.assertUnchanged()

        // A saved routine that became blank through autosave cannot start.
        harness.pushA(harness.routineSaveRequest(R_SRC, "Strength Plan"))
        harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R_SRC,
                    name = "",
                    isDraft = false,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(RoutineSetModel(S1, 0, 10, 60.0)),
                        ),
                    ),
                ),
            ),
        )
        state = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW))
        assertRejectedWithCode(
            harness.pushA(
                harness.startRoutineWorkoutRequest(
                    R_SRC,
                    W_NEW,
                    NEW_START,
                    listOf(ChildExerciseBinding(E1, NE1, listOf(ChildSetBinding(S1, NS1)))),
                ),
            ),
            0,
            FakeSyncService.INVALID_ROUTINE_STATE,
        )
        state.assertUnchanged()

        // A saved routine that became exercise-less through autosave cannot start.
        harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(id = R_SRC, name = "Strength Plan", isDraft = false, exercises = emptyList()),
            ),
        )
        state = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, emptyList())),
            0,
            FakeSyncService.INVALID_ROUTINE_STATE,
        )
        state.assertUnchanged()
        assertEquals(W_OLD, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun startWorkout_rejectsMissingDeletedForeignAndChangedSources() = runTest {
        val harness = FakeSyncHarness()
        seedSavedRoutine(harness)
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))

        // Unknown and foreign sources share the non-disclosing lookup code.
        var state = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(UNKNOWN, W_NEW, NEW_START, validPlan())),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        harness.pushB(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R_FOREIGN,
                    name = "Theirs",
                    isDraft = true,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = FE1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(RoutineSetModel(FS1, 0, 10, 60.0)),
                        ),
                    ),
                ),
            ),
        )
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_FOREIGN, W_NEW, NEW_START, validPlan())),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        // A source changed since plan capture no longer matches the plan.
        // E3/S4 stay fresh: the foreign routine uses FE1/FS1.
        val changedUpsert = harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R_SRC,
                    name = "Strength Plan",
                    isDraft = false,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            restDurationSeconds = 90,
                            sets = listOf(
                                RoutineSetModel(S1, 0, 10, 60.0),
                                RoutineSetModel(S2, 1, 8, 50.0),
                            ),
                        ),
                        FakeSyncFixtures.routineEntry(
                            id = E2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            sets = listOf(RoutineSetModel(S3, 0, 5, 40.0)),
                        ),
                        FakeSyncFixtures.routineEntry(
                            id = E3,
                            exerciseId = BUNDLED_SQUAT,
                            position = 2,
                            sets = listOf(RoutineSetModel(S4, 0, 5, 40.0)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, changedUpsert.results[0].status)
        state = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, validPlan())),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()

        // A deleted saved source cannot start a workout.
        harness.pushA(harness.deleteRequest(AggregateType.ROUTINE, R_SRC))
        state = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, validPlan())),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()
        assertEquals(W_OLD, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun startWorkout_rejectsBindingMismatches() = runTest {
        val harness = FakeSyncHarness()
        seedSavedRoutine(harness)
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))
        val state = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW))

        // Partial plan: second exercise missing.
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, listOf(validPlan()[0]))),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()

        // Extra plan: unknown third source exercise.
        assertRejectedWithCode(
            harness.pushA(
                harness.startRoutineWorkoutRequest(
                    R_SRC,
                    W_NEW,
                    NEW_START,
                    validPlan() + ChildExerciseBinding(EXTRA_EXERCISE, EXTRA_SET, listOf(ChildSetBinding(EXTRA_SOURCE_SET, EXTRA_TARGET_SET))),
                ),
            ),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()

        // Reordered plan: source exercises swapped.
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, listOf(validPlan()[1], validPlan()[0]))),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()

        // Changed set binding: wrong source set ID at the nested position.
        val swappedSets = validPlan()[0].copy(sets = listOf(ChildSetBinding(S1, NS1), ChildSetBinding(EXTRA_SOURCE_SET, NS2)))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, listOf(swappedSets, validPlan()[1]))),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()

        // Short sets: one source set missing from the first exercise.
        val shortSets = validPlan()[0].copy(sets = listOf(ChildSetBinding(S1, NS1)))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, listOf(shortSets, validPlan()[1]))),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()
        assertEquals(W_OLD, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun startWorkout_rejectsCollidingAndReusedIdsThenAcceptsCleanRetry() = runTest {
        val harness = FakeSyncHarness()
        seedSavedRoutine(harness)
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))
        val state = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW), workoutKey(W_TOMB))

        // The target root is create-only: a live root ID collides.
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_OLD, NEW_START, validPlan())),
            0,
            FakeSyncService.ROOT_ALREADY_EXISTS,
        )
        state.assertUnchanged()

        // A tombstoned root ID stays reserved and cannot be recreated by start.
        harness.pushA(harness.startRequest(W_TOMB, "Doomed", STARTED_AT))
        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W_TOMB))
        assertTrue(harness.service.isTombstoned(ACCOUNT_A, AggregateType.WORKOUT, W_TOMB))
        val tombState = harness.captureState(routineKey(R_SRC), workoutKey(W_OLD), workoutKey(W_NEW), workoutKey(W_TOMB))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_TOMB, NEW_START, validPlan())),
            0,
            FakeSyncService.ROOT_ALREADY_EXISTS,
        )
        tombState.assertUnchanged()

        // A set target reusing a retained entry ID from the source collides
        // across child levels, even though it is not in this plan's set list.
        val reusedSetTarget = validPlan()[0].copy(sets = listOf(ChildSetBinding(S1, E1), ChildSetBinding(S2, NS2)))
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, listOf(reusedSetTarget, validPlan()[1]))),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        tombState.assertUnchanged()

        // A child target reusing a retained live root ID collides.
        val rootAsChild = validPlan()[0].copy(id = W_OLD)
        assertRejectedWithCode(
            harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, listOf(rootAsChild, validPlan()[1]))),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        tombState.assertUnchanged()

        // A clean retry after rejections still succeeds: nothing was half-committed.
        val result = harness.pushA(harness.startRoutineWorkoutRequest(R_SRC, W_NEW, NEW_START, validPlan()))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(CanonicalRecord.Workout(expectedWorkout()), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_NEW))
        assertEquals(W_NEW, harness.service.activeWorkoutId(ACCOUNT_A))
    }
}
