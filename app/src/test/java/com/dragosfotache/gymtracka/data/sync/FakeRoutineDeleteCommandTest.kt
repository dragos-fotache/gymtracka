package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_A
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_PUSH_UP
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_SQUAT
import com.dragosfotache.gymtracka.data.sync.FakeSyncHarness.Companion.assertRejectedWithCode
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent tests for the fake `routine.deleteExercise` and
 * `routine.deleteSet` commands. Expected records and batches are
 * constructed by hand from the specification, never derived from the
 * service under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeRoutineDeleteCommandTest {
    companion object {
        const val R1 = "30333333-3333-3333-3333-333333333333"
        const val R2 = "31313131-3131-3131-3131-313131313131"
        const val R_FOREIGN = "41414141-4141-4141-4141-414141414141"
        const val UNKNOWN = "99999999-9999-9999-9999-999999999999"
        const val RE1 = "33333333-3333-3333-3333-3333333333a1"
        const val RE2 = "33333333-3333-3333-3333-3333333333a2"
        const val RE3 = "33333333-3333-3333-3333-3333333333a3"
        const val RS1 = "33333333-3333-3333-3333-3333333333b1"
        const val RS2 = "33333333-3333-3333-3333-3333333333b2"
        const val RS3 = "33333333-3333-3333-3333-3333333333b3"
        const val RS4 = "33333333-3333-3333-3333-3333333333b4"
        const val RS5 = "33333333-3333-3333-3333-3333333333b5"
        const val OE1 = "22222222-2222-2222-2222-2222222222a1"
        const val OS1 = "22222222-2222-2222-2222-2222222222b1"
    }

    private fun key(id: String) = FakeSyncService.RootKey(AggregateType.ROUTINE, id)

    private fun routineSet(id: String, position: Int, repetitions: Int?, weightKg: Double?) =
        RoutineSetModel(id, position, repetitions, weightKg)

    /** Creates draft R1 with RE1/RE2/RE3 (RE2 holds three sets) at revision 1. */
    private suspend fun seedDraft(harness: FakeSyncHarness) {
        val created = harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R1,
                    name = "Strength Plan",
                    isDraft = true,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = RE1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            restDurationSeconds = 90,
                            sets = listOf(routineSet(RS1, 0, 10, 60.0)),
                        ),
                        FakeSyncFixtures.routineEntry(
                            id = RE2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            restDurationSeconds = 45,
                            sets = listOf(
                                routineSet(RS2, 0, 8, 50.0),
                                routineSet(RS3, 1, 6, 40.0),
                                routineSet(RS4, 2, 5, 35.0),
                            ),
                        ),
                        FakeSyncFixtures.routineEntry(
                            id = RE3,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 2,
                            restDurationSeconds = 30,
                            sets = listOf(routineSet(RS5, 0, 12, 30.0)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, created.results[0].status)
    }

    private fun expectedSeeded(): RoutineRecordModel =
        RoutineRecordModel(
            id = R1,
            name = "Strength Plan",
            isDraft = true,
            revision = 1L,
            exercises = listOf(
                RoutineExerciseModel(
                    id = RE1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    restDurationSeconds = 90,
                    sets = listOf(routineSet(RS1, 0, 10, 60.0)),
                    exerciseName = "Push-Up",
                ),
                RoutineExerciseModel(
                    id = RE2,
                    exerciseId = BUNDLED_SQUAT,
                    position = 1,
                    restDurationSeconds = 45,
                    sets = listOf(
                        routineSet(RS2, 0, 8, 50.0),
                        routineSet(RS3, 1, 6, 40.0),
                        routineSet(RS4, 2, 5, 35.0),
                    ),
                    exerciseName = "Squat",
                ),
                RoutineExerciseModel(
                    id = RE3,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 2,
                    restDurationSeconds = 30,
                    sets = listOf(routineSet(RS5, 0, 12, 30.0)),
                    exerciseName = "Push-Up",
                ),
            ),
        )

    @Test
    fun deleteExercise_removesMiddleEntryCascadesSetsAndRenumbers() = runTest {
        val harness = FakeSyncHarness(64)
        seedDraft(harness)

        val result = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RE2))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val seeded = expectedSeeded()
        val expected = seeded.copy(
            revision = 2L,
            exercises = listOf(
                seeded.exercises[0],
                seeded.exercises[2].copy(position = 1),
            ),
        )
        val expectedBatch = harness.expectedBatch(
            1,
            listOf(
                SyncChangeModel(
                    AggregateType.ROUTINE,
                    R1,
                    ChangeOperation.UPSERT,
                    2L,
                    CanonicalRecord.Routine(expected),
                ),
            ),
        )
        assertEquals(expectedBatch, result.results[0].batch)
        assertEquals(listOf(expectedBatch), harness.acceptedBatches().takeLast(1))
        assertEquals(CanonicalRecord.Routine(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1))
        assertEquals(2L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.ROUTINE, R1))
        assertTrue((harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine).record.isDraft)
    }

    @Test
    fun deleteExercise_savedRoutineStaysSavedThroughFirstAndLastDeletion() = runTest {
        val harness = FakeSyncHarness(64)
        seedDraft(harness)
        val saved = harness.pushA(harness.routineSaveRequest(R1, "Strength Plan"))
        assertEquals(MutationStatus.ACCEPTED, saved.results[0].status)

        val first = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RE1))
        assertEquals(MutationStatus.ACCEPTED, first.results[0].status)
        val seeded = expectedSeeded()
        val afterFirst = seeded.copy(
            name = "Strength Plan",
            isDraft = false,
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
                    AggregateType.ROUTINE,
                    R1,
                    ChangeOperation.UPSERT,
                    3L,
                    CanonicalRecord.Routine(afterFirst),
                ),
            ),
        )
        assertEquals(firstBatch, first.results[0].batch)

        val last = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RE3))
        assertEquals(MutationStatus.ACCEPTED, last.results[0].status)
        val expected = afterFirst.copy(
            revision = 4L,
            exercises = listOf(afterFirst.exercises[0]),
        )
        val lastBatch = harness.expectedBatch(
            3,
            listOf(
                SyncChangeModel(
                    AggregateType.ROUTINE,
                    R1,
                    ChangeOperation.UPSERT,
                    4L,
                    CanonicalRecord.Routine(expected),
                ),
            ),
        )
        assertEquals(lastBatch, last.results[0].batch)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine
        assertEquals(CanonicalRecord.Routine(expected), CanonicalRecord.Routine(stored.record))
        assertFalse(stored.record.isDraft)
        assertEquals(4L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.ROUTINE, R1))
    }

    @Test
    fun deleteExercise_savedRoutineStaysSavedWhenEmptied() = runTest {
        val harness = FakeSyncHarness(64)
        seedDraft(harness)
        val saved = harness.pushA(harness.routineSaveRequest(R1, "Strength Plan"))
        assertEquals(MutationStatus.ACCEPTED, saved.results[0].status)

        val seeded = expectedSeeded()
        val afterFirst = seeded.copy(
            name = "Strength Plan",
            isDraft = false,
            revision = 3L,
            exercises = listOf(
                seeded.exercises[1].copy(position = 0),
                seeded.exercises[2].copy(position = 1),
            ),
        )
        val first = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RE1))
        assertEquals(MutationStatus.ACCEPTED, first.results[0].status)
        assertEquals(
            harness.expectedBatch(
                2,
                listOf(
                    SyncChangeModel(
                        AggregateType.ROUTINE,
                        R1,
                        ChangeOperation.UPSERT,
                        3L,
                        CanonicalRecord.Routine(afterFirst),
                    ),
                ),
            ),
            first.results[0].batch,
        )

        val afterSecond = afterFirst.copy(
            revision = 4L,
            exercises = listOf(afterFirst.exercises[1].copy(position = 0)),
        )
        val second = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RE2))
        assertEquals(MutationStatus.ACCEPTED, second.results[0].status)
        assertEquals(
            harness.expectedBatch(
                3,
                listOf(
                    SyncChangeModel(
                        AggregateType.ROUTINE,
                        R1,
                        ChangeOperation.UPSERT,
                        4L,
                        CanonicalRecord.Routine(afterSecond),
                    ),
                ),
            ),
            second.results[0].batch,
        )

        // A saved routine may become empty without demotion to draft.
        val expected = afterSecond.copy(revision = 5L, exercises = emptyList())
        val last = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RE3))
        assertEquals(MutationStatus.ACCEPTED, last.results[0].status)
        assertEquals(
            harness.expectedBatch(
                4,
                listOf(
                    SyncChangeModel(
                        AggregateType.ROUTINE,
                        R1,
                        ChangeOperation.UPSERT,
                        5L,
                        CanonicalRecord.Routine(expected),
                    ),
                ),
            ),
            last.results[0].batch,
        )
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine
        assertEquals(emptyList<RoutineExerciseModel>(), stored.record.exercises)
        assertFalse(stored.record.isDraft)
        assertEquals(5L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.ROUTINE, R1))
    }

    @Test
    fun deleteSet_removesMiddleThenLastSetAndRenumbers() = runTest {
        val harness = FakeSyncHarness(64)
        seedDraft(harness)

        val middle = harness.pushA(harness.deleteRoutineSetRequest(R1, RS3))
        assertEquals(MutationStatus.ACCEPTED, middle.results[0].status)
        val seeded = expectedSeeded()
        val afterMiddle = seeded.copy(
            revision = 2L,
            exercises = seeded.exercises.map { entry ->
                if (entry.id != RE2) {
                    entry
                } else {
                    entry.copy(
                        sets = listOf(
                            routineSet(RS2, 0, 8, 50.0),
                            routineSet(RS4, 1, 5, 35.0),
                        ),
                    )
                }
            },
        )
        assertEquals(
            harness.expectedBatch(
                1,
                listOf(
                    SyncChangeModel(
                        AggregateType.ROUTINE,
                        R1,
                        ChangeOperation.UPSERT,
                        2L,
                        CanonicalRecord.Routine(afterMiddle),
                    ),
                ),
            ),
            middle.results[0].batch,
        )

        val last = harness.pushA(harness.deleteRoutineSetRequest(R1, RS4))
        assertEquals(MutationStatus.ACCEPTED, last.results[0].status)
        val expected = afterMiddle.copy(
            revision = 3L,
            exercises = afterMiddle.exercises.map { entry ->
                if (entry.id != RE2) entry else entry.copy(sets = listOf(routineSet(RS2, 0, 8, 50.0)))
            },
        )
        assertEquals(
            harness.expectedBatch(
                2,
                listOf(
                    SyncChangeModel(
                        AggregateType.ROUTINE,
                        R1,
                        ChangeOperation.UPSERT,
                        3L,
                        CanonicalRecord.Routine(expected),
                    ),
                ),
            ),
            last.results[0].batch,
        )
        assertEquals(CanonicalRecord.Routine(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1))
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.ROUTINE, R1))
    }

    @Test
    fun deleteSet_retainsExerciseWhenLastSetRemoved() = runTest {
        val harness = FakeSyncHarness(64)
        seedDraft(harness)

        // RE1 keeps its identity, reference, position, and configured rest
        // with zero sets.
        val result = harness.pushA(harness.deleteRoutineSetRequest(R1, RS1))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val seeded = expectedSeeded()
        val expected = seeded.copy(
            revision = 2L,
            exercises = seeded.exercises.map { entry ->
                if (entry.id != RE1) entry else entry.copy(sets = emptyList())
            },
        )
        assertEquals(
            harness.expectedBatch(
                1,
                listOf(
                    SyncChangeModel(
                        AggregateType.ROUTINE,
                        R1,
                        ChangeOperation.UPSERT,
                        2L,
                        CanonicalRecord.Routine(expected),
                    ),
                ),
            ),
            result.results[0].batch,
        )
        val retained = (harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine).record.exercises[0]
        assertEquals(RE1, retained.id)
        assertEquals(BUNDLED_PUSH_UP, retained.exerciseId)
        assertEquals(0, retained.position)
        assertEquals(90, retained.restDurationSeconds)
        assertEquals(emptyList<RoutineSetModel>(), retained.sets)
        assertEquals("Push-Up", retained.exerciseName)
    }

    @Test
    fun deleteCommands_rejectBadTargetsWithoutStateChange() = runTest {
        val harness = FakeSyncHarness(64)
        seedDraft(harness)
        // Second caller-owned routine used as a wrong-parent lookup source.
        val other = harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R2,
                    name = "Other Plan",
                    isDraft = true,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = OE1,
                            exerciseId = BUNDLED_SQUAT,
                            position = 0,
                            sets = listOf(routineSet(OS1, 0, 5, 20.0)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, other.results[0].status)
        val foreign = harness.pushB(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R_FOREIGN,
                    name = "Foreign Plan",
                    isDraft = true,
                    exercises = emptyList(),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, foreign.results[0].status)

        // Unknown roots.
        var state = harness.captureState(key(R1), key(R2), account = ACCOUNT_A)
        var result = harness.pushA(harness.deleteRoutineExerciseRequest(UNKNOWN, RE1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteRoutineSetRequest(UNKNOWN, RS1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Foreign roots.
        state = harness.captureState(key(R1), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteRoutineExerciseRequest(R_FOREIGN, RE1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteRoutineSetRequest(R_FOREIGN, RS1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Unknown children under the live root.
        state = harness.captureState(key(R1), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteRoutineExerciseRequest(R1, UNKNOWN))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteRoutineSetRequest(R1, UNKNOWN))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Wrong-parent children: OE1/OS1 belong to R2, not R1.
        state = harness.captureState(key(R1), key(R2), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteRoutineExerciseRequest(R1, OE1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteRoutineSetRequest(R1, OS1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()

        // Level confusion: a set ID is not an exercise target and an
        // exercise ID is not a set target.
        state = harness.captureState(key(R1), account = ACCOUNT_A)
        result = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RS1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        result = harness.pushA(harness.deleteRoutineSetRequest(R1, RE1))
        assertRejectedWithCode(result, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
    }

    @Test
    fun deleteCommands_rejectDeletedRootWithoutStateChange() = runTest {
        val harness = FakeSyncHarness(64)
        seedDraft(harness)
        val deleted = harness.pushA(harness.deleteRequest(AggregateType.ROUTINE, R1))
        assertEquals(MutationStatus.ACCEPTED, deleted.results[0].status)

        val state = harness.captureState(key(R1), account = ACCOUNT_A)
        val exercise = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RE1))
        assertRejectedWithCode(exercise, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        val set = harness.pushA(harness.deleteRoutineSetRequest(R1, RS1))
        assertRejectedWithCode(set, 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
    }

    @Test
    fun deleteExercise_retainsRemovedIdsAsCollisionEvidence() = runTest {
        val harness = FakeSyncHarness(64)
        seedDraft(harness)

        val deleted = harness.pushA(harness.deleteRoutineExerciseRequest(R1, RE2))
        assertEquals(MutationStatus.ACCEPTED, deleted.results[0].status)

        // The removed exercise and set IDs cannot be reparented into another
        // root, even though their canonical children are gone.
        val state = harness.captureState(key(R1), key(R2), account = ACCOUNT_A)
        val collision = harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R2,
                    name = "Other Plan",
                    isDraft = true,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = RE2,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            sets = listOf(routineSet(RS3, 0, 6, 40.0)),
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
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R1,
                    name = "Strength Plan",
                    isDraft = true,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = RE1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            restDurationSeconds = 90,
                            sets = listOf(routineSet(RS1, 0, 10, 60.0)),
                        ),
                        FakeSyncFixtures.routineEntry(
                            id = RE2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            restDurationSeconds = 45,
                            sets = listOf(
                                routineSet(RS2, 0, 8, 50.0),
                                routineSet(RS3, 1, 6, 40.0),
                                routineSet(RS4, 2, 5, 35.0),
                            ),
                        ),
                        FakeSyncFixtures.routineEntry(
                            id = RE3,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 2,
                            restDurationSeconds = 30,
                            sets = listOf(routineSet(RS5, 0, 12, 30.0)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, restored.results[0].status)
        val expected = seeded.copy(revision = 3L)
        assertEquals(
            harness.expectedBatch(
                2,
                listOf(
                    SyncChangeModel(
                        AggregateType.ROUTINE,
                        R1,
                        ChangeOperation.UPSERT,
                        3L,
                        CanonicalRecord.Routine(expected),
                    ),
                ),
            ),
            restored.results[0].batch,
        )
        assertEquals(CanonicalRecord.Routine(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1))
    }
}
