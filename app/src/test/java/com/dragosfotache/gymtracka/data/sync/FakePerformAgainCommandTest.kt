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
import com.dragosfotache.gymtracka.data.sync.model.ChildExerciseBinding
import com.dragosfotache.gymtracka.data.sync.model.ChildSetBinding
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
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
 * Independent tests for the fake `workout.performAgain` command. Expected
 * records and batches are constructed by hand from the specification, never
 * derived from the service under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakePerformAgainCommandTest {
    companion object {
        const val W_SRC = "11111111-1111-1111-1111-111111111111"
        const val W_OLD = "22222222-2222-2222-2222-222222222222"
        const val W_NEW = "33333333-3333-3333-3333-333333333333"
        const val W_FOREIGN = "44444444-4444-4444-4444-444444444444"
        const val W_TOMB = "55555555-5555-5555-5555-555555555555"
        const val UNKNOWN = "99999999-9999-9999-9999-999999999999"
        const val E1 = "66666666-6666-6666-6666-666666666661"
        const val E2 = "66666666-6666-6666-6666-666666666662"
        const val S1 = "77777777-7777-7777-7777-777777777771"
        const val S2 = "77777777-7777-7777-7777-777777777772"
        const val S3 = "77777777-7777-7777-7777-777777777773"
        const val NE1 = "88888888-8888-8888-8888-888888888881"
        const val NE2 = "88888888-8888-8888-8888-888888888882"
        const val NS1 = "88888888-8888-8888-8888-888888888883"
        const val NS2 = "88888888-8888-8888-8888-888888888884"
        const val NS3 = "88888888-8888-8888-8888-888888888885"
        const val EXTRA_EXERCISE = "88888888-8888-8888-8888-888888888886"
        const val EXTRA_SET = "88888888-8888-8888-8888-888888888887"
        const val EXTRA_SOURCE_SET = "88888888-8888-8888-8888-888888888888"
        const val EXTRA_TARGET_SET = "88888888-8888-8888-8888-888888888889"
        const val NEW_START = STARTED_AT + 60_000L
    }

    private fun key(id: String) = FakeSyncService.RootKey(AggregateType.WORKOUT, id)

    private fun validPlan(): List<ChildExerciseBinding> =
        listOf(
            ChildExerciseBinding(E1, NE1, listOf(ChildSetBinding(S1, NS1), ChildSetBinding(S2, NS2))),
            ChildExerciseBinding(E2, NE2, listOf(ChildSetBinding(S3, NS3))),
        )

    /** Starts, populates, and finishes the two-exercise source workout (revisions 1-3). */
    private suspend fun seedFinishedSource(harness: FakeSyncHarness) {
        harness.pushA(harness.startRequest(W_SRC, "Leg Day", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W_SRC,
                    name = "Leg Day",
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
                                FakeSyncFixtures.workoutSet(S2, 1, 8, 50.0, true, 45),
                            ),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = E2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            restDurationSeconds = 30,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(S3, 0, 5, 40.0, true, 30),
                            ),
                        ),
                    ),
                ),
            ),
        )
        harness.pushA(harness.finishRequest(W_SRC, FINISHED_AT))
    }

    /** Hand-built finished source: all sets completed, so finish preserves every child. */
    private fun expectedSource(): WorkoutRecordModel =
        WorkoutRecordModel(
            id = W_SRC,
            name = "Leg Day",
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
                        FakeSyncFixtures.workoutSet(S2, 1, 8, 50.0, true, 45),
                    ),
                    exerciseName = "Push-Up",
                ),
                WorkoutExerciseModel(
                    id = E2,
                    exerciseId = BUNDLED_SQUAT,
                    position = 1,
                    notes = "",
                    restDurationSeconds = 30,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(S3, 0, 5, 40.0, true, 30),
                    ),
                    exerciseName = "Squat",
                ),
            ),
        )

    /** Hand-built clone: copied template values, incomplete sets, null recorded rest. */
    private fun expectedClone(): WorkoutRecordModel =
        WorkoutRecordModel(
            id = W_NEW,
            name = "Leg Day",
            status = "active",
            startedAtMillis = NEW_START,
            finishedAtMillis = null,
            revision = 1L,
            exercises = listOf(
                WorkoutExerciseModel(
                    id = NE1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    notes = "tired",
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
    fun performAgain_clonesFinishedSourceAndReplacesActiveAtomically() = runTest {
        val harness = FakeSyncHarness()
        seedFinishedSource(harness)
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))

        // A stale known revision never affects the clone result.
        val result = harness.pushA(
            harness.performAgainRequest(W_SRC, W_NEW, NEW_START, validPlan(), knownServerRevision = 1L),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val expected = harness.expectedBatch(
            4,
            listOf(
                SyncChangeModel(AggregateType.WORKOUT, W_OLD, ChangeOperation.DELETE, 2L, null),
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W_NEW,
                    ChangeOperation.UPSERT,
                    1L,
                    CanonicalRecord.Workout(expectedClone()),
                ),
            ),
        )
        assertEquals(expected, result.results[0].batch)
        assertEquals(listOf(expected), harness.acceptedBatches().takeLast(1))
        assertEquals(CanonicalRecord.Workout(expectedClone()), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_NEW))
        assertEquals(1L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W_NEW))
        // The finished source is preserved exactly: record, revision, and active
        // replacement never touches it.
        assertEquals(CanonicalRecord.Workout(expectedSource()), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_SRC))
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W_SRC))
        assertTrue(harness.service.isTombstoned(ACCOUNT_A, AggregateType.WORKOUT, W_OLD))
        assertEquals(2L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W_OLD))
        assertEquals(W_NEW, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun performAgain_withoutPriorActive_emitsSingleUpsert() = runTest {
        val harness = FakeSyncHarness()
        seedFinishedSource(harness)
        // Finish cleared the sole-active pointer, so no tombstone is staged.
        assertNull(harness.service.activeWorkoutId(ACCOUNT_A))

        val result = harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, validPlan()))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val expected = harness.expectedBatch(
            3,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W_NEW,
                    ChangeOperation.UPSERT,
                    1L,
                    CanonicalRecord.Workout(expectedClone()),
                ),
            ),
        )
        assertEquals(expected, result.results[0].batch)
        assertEquals(W_NEW, harness.service.activeWorkoutId(ACCOUNT_A))
        assertEquals(CanonicalRecord.Workout(expectedSource()), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_SRC))
    }

    @Test
    fun performAgain_rejectsUnfinishedMissingDeletedAndForeignSources() = runTest {
        val harness = FakeSyncHarness()
        seedFinishedSource(harness)
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))

        // An active source is not a finished template.
        var state = harness.captureState(key(W_SRC), key(W_OLD), key(W_NEW))
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_OLD, W_NEW, NEW_START, emptyList())),
            0,
            FakeSyncService.SOURCE_NOT_FINISHED,
        )
        state.assertUnchanged()

        // Unknown and foreign sources share the non-disclosing lookup code.
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(UNKNOWN, W_NEW, NEW_START, emptyList())),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        harness.pushB(harness.startRequest(W_FOREIGN, "Theirs", STARTED_AT))
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_FOREIGN, W_NEW, NEW_START, emptyList())),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        // A deleted finished source cannot be cloned.
        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W_SRC))
        state = harness.captureState(key(W_SRC), key(W_OLD), key(W_NEW))
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, validPlan())),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()
        assertEquals(W_OLD, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun performAgain_rejectsBindingMismatches() = runTest {
        val harness = FakeSyncHarness()
        seedFinishedSource(harness)
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))
        val state = harness.captureState(key(W_SRC), key(W_OLD), key(W_NEW))

        // Partial plan: second exercise missing.
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, listOf(validPlan()[0]))),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()

        // Extra plan: unknown third source exercise.
        assertRejectedWithCode(
            harness.pushA(
                harness.performAgainRequest(
                    W_SRC,
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
            harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, listOf(validPlan()[1], validPlan()[0]))),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()

        // Changed set binding: wrong source set ID at the nested position.
        val swappedSets = validPlan()[0].copy(sets = listOf(ChildSetBinding(S1, NS1), ChildSetBinding(EXTRA_SOURCE_SET, NS2)))
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, listOf(swappedSets, validPlan()[1]))),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()

        // Short sets: one source set missing from the first exercise.
        val shortSets = validPlan()[0].copy(sets = listOf(ChildSetBinding(S1, NS1)))
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, listOf(shortSets, validPlan()[1]))),
            0,
            FakeSyncService.CHILD_BINDING_MISMATCH,
        )
        state.assertUnchanged()
        assertEquals(W_OLD, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun performAgain_rejectsCollidingAndReusedIdsThenAcceptsCleanRetry() = runTest {
        val harness = FakeSyncHarness()
        seedFinishedSource(harness)
        harness.pushA(harness.startRequest(W_OLD, "Old Active", STARTED_AT))
        val state = harness.captureState(key(W_SRC), key(W_OLD), key(W_NEW), key(W_TOMB))

        // The target root is create-only: a live root ID collides.
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_SRC, W_OLD, NEW_START, validPlan())),
            0,
            FakeSyncService.ROOT_ALREADY_EXISTS,
        )
        state.assertUnchanged()

        // A tombstoned root ID stays reserved and cannot be recreated by clone.
        harness.pushA(harness.startRequest(W_TOMB, "Doomed", STARTED_AT))
        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W_TOMB))
        assertTrue(harness.service.isTombstoned(ACCOUNT_A, AggregateType.WORKOUT, W_TOMB))
        val tombState = harness.captureState(key(W_SRC), key(W_OLD), key(W_NEW), key(W_TOMB))
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_SRC, W_TOMB, NEW_START, validPlan())),
            0,
            FakeSyncService.ROOT_ALREADY_EXISTS,
        )
        tombState.assertUnchanged()

        // A set target reusing a retained entry ID from the source collides
        // across child levels, even though it is not in this plan's set list.
        val reusedSetTarget = validPlan()[0].copy(sets = listOf(ChildSetBinding(S1, E1), ChildSetBinding(S2, NS2)))
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, listOf(reusedSetTarget, validPlan()[1]))),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        tombState.assertUnchanged()

        // A child target reusing a retained live root ID collides.
        val rootAsChild = validPlan()[0].copy(id = W_OLD)
        assertRejectedWithCode(
            harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, listOf(rootAsChild, validPlan()[1]))),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        tombState.assertUnchanged()

        // A clean retry after rejections still succeeds: nothing was half-committed.
        val result = harness.pushA(harness.performAgainRequest(W_SRC, W_NEW, NEW_START, validPlan()))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(CanonicalRecord.Workout(expectedClone()), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_NEW))
        assertEquals(W_NEW, harness.service.activeWorkoutId(ACCOUNT_A))
    }
}
