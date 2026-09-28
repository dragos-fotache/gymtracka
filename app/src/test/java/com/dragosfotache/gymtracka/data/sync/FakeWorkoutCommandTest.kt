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
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent tests for the fake `workout.start` and `workout.upsert`
 * commands. Expected records and batches are constructed by hand from the
 * specification, never derived from the service under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeWorkoutCommandTest {
    companion object {
        const val W1 = "11111111-1111-1111-1111-111111111111"
        const val W2 = "22222222-2222-2222-2222-222222222222"
        const val W3 = "33333333-3333-3333-3333-333333333333"
        const val E1 = "55555555-5555-5555-5555-555555555551"
        const val E2 = "55555555-5555-5555-5555-555555555552"
        const val E3 = "55555555-5555-5555-5555-555555555553"
        const val S1 = "66666666-6666-6666-6666-666666666661"
        const val S2 = "66666666-6666-6666-6666-666666666662"
        const val S3 = "66666666-6666-6666-6666-666666666663"
        const val S4 = "66666666-6666-6666-6666-666666666664"
        const val C1 = "77777777-7777-7777-7777-777777777771"
        const val UNKNOWN_EXERCISE = "99999999-9999-9999-9999-999999999999"
    }

    private fun key(id: String) = FakeSyncService.RootKey(AggregateType.WORKOUT, id)

    private fun upsertChange(id: String, record: WorkoutRecordModel, revision: Long) =
        SyncChangeModel(AggregateType.WORKOUT, id, ChangeOperation.UPSERT, revision, CanonicalRecord.Workout(record))

    @Test
    fun start_createsExactlyEmptyActiveWithIndependentExpectedRecord() = runTest {
        val harness = FakeSyncHarness()
        val result = harness.pushA(harness.startRequest(W1, "Morning Workout", STARTED_AT))

        assertEquals(1, result.results.size)
        val outcome = result.results[0]
        assertEquals(MutationStatus.ACCEPTED, outcome.status)
        assertNull(outcome.errorCode)
        val expectedRecord = WorkoutRecordModel(W1, "Morning Workout", "active", STARTED_AT, null, 1L, emptyList())
        val expected = harness.expectedBatch(0, listOf(upsertChange(W1, expectedRecord, 1L)))
        assertEquals(expected, outcome.batch)
        assertEquals(listOf(expected), harness.acceptedBatches())
        assertEquals(CanonicalRecord.Workout(expectedRecord), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(1L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun start_replacesPriorActiveAtomicallyDeleteFirst() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
        val result = harness.pushA(harness.startRequest(W2, "Evening Workout", STARTED_AT + 1_000L))

        val expectedNew = WorkoutRecordModel(W2, "Evening Workout", "active", STARTED_AT + 1_000L, null, 1L, emptyList())
        val expected = harness.expectedBatch(
            1,
            listOf(
                SyncChangeModel(AggregateType.WORKOUT, W1, ChangeOperation.DELETE, 2L, null),
                upsertChange(W2, expectedNew, 1L),
            ),
        )
        assertEquals(expected, result.results[0].batch)
        assertNull(harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertTrue(harness.service.isTombstoned(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(2L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W2, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun start_rejectsEveryCollisionWithoutEffects() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))

        var state = harness.captureState(key(W1), key(W2))
        assertRejectedWithCode(harness.pushB(harness.startRequest(W1)), 0, FakeSyncService.ROOT_ALREADY_EXISTS)
        state.assertUnchanged()
        assertRejectedWithCode(harness.pushA(harness.startRequest(W1)), 0, FakeSyncService.ROOT_ALREADY_EXISTS)
        state.assertUnchanged()

        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W1))
        state = harness.captureState(key(W1), key(W2))
        assertRejectedWithCode(harness.pushA(harness.startRequest(W1)), 0, FakeSyncService.ROOT_ALREADY_EXISTS)
        state.assertUnchanged()

        // The next valid creation still takes revision 1 with no rejected gap in the log.
        val before = harness.acceptedBatches().size
        val result = harness.pushA(harness.startRequest(W2))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(before + 1, harness.acceptedBatches().size)
        assertEquals(1L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W2))
    }

    @Test
    fun upsert_replacesCompleteAggregateWithResolvedNamesNullsAndOrder() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1)))

        val input = FakeSyncFixtures.workoutInput(
            id = W1,
            name = "Evening Workout",
            startedAtMillis = STARTED_AT + 5_000L,
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
                    ),
                ),
                FakeSyncFixtures.workoutEntry(
                    id = E2,
                    exerciseId = C1,
                    position = 1,
                    restDurationSeconds = 0,
                    sets = listOf(FakeSyncFixtures.workoutSet(S3, 0, null, 20.5, false, null)),
                ),
            ),
        )
        val result = harness.pushA(harness.upsertRequest(input))

        val expectedRecord = WorkoutRecordModel(
            id = W1,
            name = "Evening Workout",
            status = "active",
            startedAtMillis = STARTED_AT + 5_000L,
            finishedAtMillis = null,
            revision = 2L,
            exercises = listOf(
                WorkoutExerciseModel(E1, BUNDLED_PUSH_UP, 0, "tired", 90, input.exercises[0].sets, "Push-Up"),
                WorkoutExerciseModel(E2, C1, 1, "", 0, input.exercises[1].sets, "Cable Fly"),
            ),
        )
        val expected = harness.expectedBatch(2, listOf(upsertChange(W1, expectedRecord, 2L)))
        assertEquals(expected, result.results[0].batch)
        assertEquals(CanonicalRecord.Workout(expectedRecord), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun upsert_resolvesReferenceByExerciseIdNotEntryId() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))

        // The entry ID happens to equal a catalogue UUID, but visibility must
        // resolve the referenced exercise ID instead.
        val input = FakeSyncFixtures.workoutInput(
            id = W1,
            exercises = listOf(
                FakeSyncFixtures.workoutEntry(
                    id = BUNDLED_PUSH_UP,
                    exerciseId = BUNDLED_SQUAT,
                    position = 0,
                    sets = listOf(FakeSyncFixtures.workoutSet(S1, 0)),
                ),
            ),
        )
        val result = harness.pushA(harness.upsertRequest(input))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout
        assertEquals("Squat", stored.record.exercises[0].exerciseName)

        val state = harness.captureState(key(W1))
        val bad = FakeSyncFixtures.workoutInput(
            id = W1,
            exercises = listOf(
                FakeSyncFixtures.workoutEntry(
                    id = E1,
                    exerciseId = UNKNOWN_EXERCISE,
                    position = 0,
                    sets = listOf(FakeSyncFixtures.workoutSet(S2, 0)),
                ),
            ),
        )
        assertRejectedWithCode(harness.pushA(harness.upsertRequest(bad)), 0, FakeSyncService.INVALID_REFERENCE)
        state.assertUnchanged()
    }

    @Test
    fun upsert_rejectsIneligibleSourcesWithoutEffects() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
        val input = FakeSyncFixtures.workoutInput(id = W1)

        var state = harness.captureState(key(W1), key(W3))
        assertRejectedWithCode(
            harness.pushA(harness.upsertRequest(FakeSyncFixtures.workoutInput(id = W3))),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()
        assertRejectedWithCode(harness.pushB(harness.upsertRequest(input)), 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W1))
        state = harness.captureState(key(W1))
        assertRejectedWithCode(harness.pushA(harness.upsertRequest(input)), 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        val finished = WorkoutImportModel(W3, "Done", "finished", STARTED_AT, FINISHED_AT, emptyList())
        val importResult = harness.pushA(
            harness.importRequest(
                ImportRecord.Workout(
                    finished.copy(
                        exercises = listOf(
                            WorkoutExerciseInputModel(
                                E1,
                                BUNDLED_PUSH_UP,
                                0,
                                "",
                                30,
                                listOf(FakeSyncFixtures.workoutSet(S1, 0, 8, 50.0, true, 60)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, importResult.results[0].status)
        state = harness.captureState(key(W3))
        assertRejectedWithCode(
            harness.pushA(harness.upsertRequest(FakeSyncFixtures.workoutInput(id = W3))),
            0,
            FakeSyncService.IMMUTABLE_FINISHED,
        )
        state.assertUnchanged()
    }

    @Test
    fun upsert_rejectsCrossOwnerCustomReference() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
        harness.pushB(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1)))

        val state = harness.captureState(key(W1))
        val input = FakeSyncFixtures.workoutInput(
            id = W1,
            exercises = listOf(
                FakeSyncFixtures.workoutEntry(
                    id = E1,
                    exerciseId = C1,
                    position = 0,
                    sets = listOf(FakeSyncFixtures.workoutSet(S1, 0)),
                ),
            ),
        )
        assertRejectedWithCode(harness.pushA(harness.upsertRequest(input)), 0, FakeSyncService.INVALID_REFERENCE)
        state.assertUnchanged()
    }

    @Test
    fun upsert_rejectsChildCollisionAndReparenting() = runTest {
        val harness = FakeSyncHarness()
        // Separate accounts keep both workout targets eligible: a second
        // start under the same account would tombstone the first.
        assertEquals(MutationStatus.ACCEPTED, harness.pushA(harness.startRequest(W1)).results[0].status)
        assertEquals(MutationStatus.ACCEPTED, harness.pushB(harness.startRequest(W2)).results[0].status)
        assertEquals(
            MutationStatus.ACCEPTED,
            harness.pushA(
                harness.upsertRequest(
                    FakeSyncFixtures.workoutInput(
                        id = W1,
                        exercises = listOf(
                            FakeSyncFixtures.workoutEntry(
                                id = E1,
                                exerciseId = BUNDLED_PUSH_UP,
                                position = 0,
                                sets = listOf(FakeSyncFixtures.workoutSet(S1, 0)),
                            ),
                        ),
                    ),
                ),
            ).results[0].status,
        )
        assertEquals(
            MutationStatus.ACCEPTED,
            harness.pushB(
                harness.upsertRequest(
                    FakeSyncFixtures.workoutInput(
                        id = W2,
                        exercises = listOf(
                            FakeSyncFixtures.workoutEntry(
                                id = E2,
                                exerciseId = BUNDLED_SQUAT,
                                position = 0,
                                sets = listOf(FakeSyncFixtures.workoutSet(S2, 0)),
                            ),
                        ),
                    ),
                ),
            ).results[0].status,
        )

        fun upsertWith(entryId: String, setId: String) =
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = entryId,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(FakeSyncFixtures.workoutSet(setId, 0)),
                        ),
                    ),
                ),
            )

        val state = harness.captureState(key(W1), key(W2))
        // Foreign entry ID.
        assertRejectedWithCode(harness.pushA(upsertWith(E2, S3)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()
        // Foreign set reparented under a stable entry.
        assertRejectedWithCode(harness.pushA(upsertWith(E1, S2)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()
        // Entry reuses the envelope root UUID.
        assertRejectedWithCode(harness.pushA(upsertWith(W1, S3)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()
        // Set reuses the envelope root UUID.
        assertRejectedWithCode(harness.pushA(upsertWith(E3, W1)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()
        // Entry reuses a set ID from the same root.
        assertRejectedWithCode(harness.pushA(upsertWith(S1, S3)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()

        // Next valid replacement still takes the next revision with no rejected gap.
        val revisionBefore = harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1)
        val valid = harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    name = "Recovered",
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(FakeSyncFixtures.workoutSet(S1, 0)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, valid.results[0].status)
        assertEquals(revisionBefore!! + 1L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
    }

    @Test
    fun upsert_removedChildIdsStayReservedForThisRoot() = runTest {
        val harness = FakeSyncHarness()
        // Separate accounts keep both workout targets eligible: a second
        // start under the same account would tombstone the first.
        assertEquals(MutationStatus.ACCEPTED, harness.pushA(harness.startRequest(W1)).results[0].status)
        assertEquals(MutationStatus.ACCEPTED, harness.pushB(harness.startRequest(W2)).results[0].status)
        val full = FakeSyncFixtures.workoutInput(
            id = W1,
            exercises = listOf(
                FakeSyncFixtures.workoutEntry(
                    id = E1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    sets = listOf(FakeSyncFixtures.workoutSet(S1, 0)),
                ),
                FakeSyncFixtures.workoutEntry(
                    id = E2,
                    exerciseId = BUNDLED_SQUAT,
                    position = 1,
                    sets = listOf(FakeSyncFixtures.workoutSet(S2, 0)),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, harness.pushA(harness.upsertRequest(full)).results[0].status)
        // Remove the second entry through complete replacement.
        assertEquals(
            MutationStatus.ACCEPTED,
            harness.pushA(harness.upsertRequest(full.copy(exercises = listOf(full.exercises[0])))).results[0].status,
        )

        val state = harness.captureState(key(W1), key(W2))
        // The removed entry ID cannot be silently reparented into another root.
        assertRejectedWithCode(
            harness.pushB(
                harness.upsertRequest(
                    FakeSyncFixtures.workoutInput(
                        id = W2,
                        exercises = listOf(
                            FakeSyncFixtures.workoutEntry(
                                id = E2,
                                exerciseId = BUNDLED_SQUAT,
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
        // The removed set ID cannot be reparented either.
        assertRejectedWithCode(
            harness.pushB(
                harness.upsertRequest(
                    FakeSyncFixtures.workoutInput(
                        id = W2,
                        exercises = listOf(
                            FakeSyncFixtures.workoutEntry(
                                id = E3,
                                exerciseId = BUNDLED_SQUAT,
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

        // Restoring the same IDs under the owning root stays valid.
        val restore = harness.pushA(harness.upsertRequest(full))
        assertEquals(MutationStatus.ACCEPTED, restore.results[0].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout
        assertEquals(listOf(E1, E2), stored.record.exercises.map { it.id })
    }

    @Test
    fun upsert_laterCommitWinsDespiteReversedClocksAndStaleKnownRevision() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Morning Workout", STARTED_AT))
        val first = FakeSyncFixtures.workoutInput(
            id = W1,
            name = "First",
            startedAtMillis = STARTED_AT + 10_000L,
            exercises = listOf(
                FakeSyncFixtures.workoutEntry(
                    id = E1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    sets = listOf(FakeSyncFixtures.workoutSet(S1, 0, 10, 60.0, true, 60)),
                ),
            ),
        )
        harness.pushA(harness.upsertRequest(first))

        // Deliberately older client clock and stale known revision: server
        // commit order still wins.
        val second = FakeSyncFixtures.workoutInput(
            id = W1,
            name = "Second",
            startedAtMillis = STARTED_AT - 10_000L,
            exercises = listOf(
                FakeSyncFixtures.workoutEntry(
                    id = E1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    sets = listOf(FakeSyncFixtures.workoutSet(S1, 0, 5, 20.0, true, 30)),
                ),
            ),
        )
        val result = harness.pushA(harness.upsertRequest(second, knownServerRevision = 1L))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout
        assertEquals("Second", stored.record.name)
        assertEquals(STARTED_AT - 10_000L, stored.record.startedAtMillis)
        assertEquals(3L, stored.record.revision)
        assertEquals(5, stored.record.exercises[0].sets[0].repetitions)
    }

    @Test
    fun push_executesMutationsInOrderWithPerMutationAtomicity() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
        val good = FakeSyncFixtures.workoutInput(id = W1, name = "First")
        val bad = FakeSyncFixtures.workoutInput(id = W3)
        val later = FakeSyncFixtures.workoutInput(id = W1, name = "Later")

        val result = harness.pushA(
            harness.upsertRequest(good),
            harness.upsertRequest(bad),
            harness.upsertRequest(later),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertRejectedWithCode(result, 1, FakeSyncService.OWNED_ROOT_MISSING)
        assertEquals(MutationStatus.ACCEPTED, result.results[2].status)
        // Revisions stay monotonic with no gap consumed by the rejection.
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout
        assertEquals("Later", stored.record.name)
        assertEquals(2, harness.acceptedBatches().size - 1)
    }

    @Test
    fun upsert_preservesStableIdsAcrossFullReplacement() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
        val input = FakeSyncFixtures.workoutInput(
            id = W1,
            exercises = listOf(
                FakeSyncFixtures.workoutEntry(
                    id = E1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(S1, 0),
                        FakeSyncFixtures.workoutSet(S2, 1),
                    ),
                ),
            ),
        )
        harness.pushA(harness.upsertRequest(input))
        // Same stable IDs with edited values are accepted, not collisions.
        val edited = input.copy(
            exercises = listOf(
                input.exercises[0].copy(
                    notes = "edited",
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(S1, 0, 12, 70.0, true, 90),
                        FakeSyncFixtures.workoutSet(S2, 1, null, null, false, null),
                    ),
                ),
            ),
        )
        val result = harness.pushA(harness.upsertRequest(edited))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout
        assertEquals("edited", stored.record.exercises[0].notes)
        assertEquals(listOf(S1, S2), stored.record.exercises[0].sets.map { it.id })
        assertEquals(3L, stored.record.revision)
    }
}
