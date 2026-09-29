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
import com.dragosfotache.gymtracka.data.sync.model.ExerciseAddition
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Independent tests for the fake `workout.addExercises` command. Expected
 * records and batches are constructed by hand from the specification, never
 * derived from the service under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeWorkoutAddExercisesCommandTest {
    companion object {
        const val W_HIST = "10111111-1111-1111-1111-111111111111"
        const val W_TGT = "30333333-3333-3333-3333-333333333333"
        const val W_FOREIGN = "40444444-4444-4444-4444-444444444444"
        const val UNKNOWN = "99999999-9999-9999-9999-999999999999"
        const val HE1 = "11111111-1111-1111-1111-1111111111a1"
        const val HE2 = "11111111-1111-1111-1111-1111111111a2"
        const val HS1 = "11111111-1111-1111-1111-1111111111b1"
        const val HS2 = "11111111-1111-1111-1111-1111111111b2"
        const val HS3 = "11111111-1111-1111-1111-1111111111b3"
        const val TE0 = "33333333-3333-3333-3333-3333333333a0"
        const val TS0 = "33333333-3333-3333-3333-3333333333b0"
        const val NE1 = "33333333-3333-3333-3333-3333333333a1"
        const val NE2 = "33333333-3333-3333-3333-3333333333a2"
        const val NE3 = "33333333-3333-3333-3333-3333333333a3"
        const val NE4 = "33333333-3333-3333-3333-3333333333a4"
        const val NS1 = "33333333-3333-3333-3333-3333333333b1"
        const val NS2 = "33333333-3333-3333-3333-3333333333b2"
        const val NS3 = "33333333-3333-3333-3333-3333333333b3"
        const val NS4 = "33333333-3333-3333-3333-3333333333b4"
        const val NS5 = "33333333-3333-3333-3333-3333333333b5"
        const val NS6 = "33333333-3333-3333-3333-3333333333b6"
        const val C_OWN = "c0000000-0000-0000-0000-000000000001"
        const val C_FOREIGN = "c0000000-0000-0000-0000-000000000002"
        const val C_DELETED = "c0000000-0000-0000-0000-000000000003"
        const val FINISHED_EARLY = FINISHED_AT - 1_000L
        const val FINISHED_LATE = FINISHED_AT + 5_000L
    }

    private fun key(id: String) = FakeSyncService.RootKey(AggregateType.WORKOUT, id)

    /** Starts, populates, and finishes the two-exercise history workout (revisions 1-3). */
    private suspend fun seedFinishedHistory(harness: FakeSyncHarness) {
        harness.pushA(harness.startRequest(W_HIST, "History One", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W_HIST,
                    name = "History One",
                    startedAtMillis = STARTED_AT,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = HE1,
                            exerciseId = BUNDLED_PUSH_UP,
                            position = 0,
                            notes = "tired",
                            restDurationSeconds = 90,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(HS1, 0, 10, 60.0, true, 61),
                                FakeSyncFixtures.workoutSet(HS2, 1, 8, 50.0, true, 45),
                            ),
                        ),
                        FakeSyncFixtures.workoutEntry(
                            id = HE2,
                            exerciseId = BUNDLED_SQUAT,
                            position = 1,
                            restDurationSeconds = 45,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(HS3, 0, 12, 30.0, true, 20),
                            ),
                        ),
                    ),
                ),
            ),
        )
        harness.pushA(harness.finishRequest(W_HIST, FINISHED_AT))
    }

    /** Imports a finished history workout in a single accepted mutation. */
    private suspend fun importFinishedHistory(
        harness: FakeSyncHarness,
        id: String,
        finishedAt: Long,
        exercises: List<WorkoutExerciseInputModel>,
    ) {
        harness.pushA(
            harness.importRequest(
                ImportRecord.Workout(
                    WorkoutImportModel(id, "History", "finished", STARTED_AT, finishedAt, exercises),
                ),
            ),
        )
    }

    /** Starts the active target with one preserved squat entry (revision 2). */
    private suspend fun seedActiveTarget(harness: FakeSyncHarness) {
        harness.pushA(harness.startRequest(W_TGT, "Active", STARTED_AT))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W_TGT,
                    name = "Active",
                    startedAtMillis = STARTED_AT,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = TE0,
                            exerciseId = BUNDLED_SQUAT,
                            position = 0,
                            notes = "keep",
                            restDurationSeconds = 60,
                            sets = listOf(
                                FakeSyncFixtures.workoutSet(TS0, 0, 6, 20.0, false, null),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun addExercises_copiesHistoryRestAndInputsAndAppendsInOrder() = runTest {
        val harness = FakeSyncHarness(64)
        seedFinishedHistory(harness)
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C_OWN)))
        seedActiveTarget(harness)

        // A stale known revision never affects the appended result.
        val result = harness.pushA(
            harness.addExercisesRequest(
                W_TGT,
                listOf(
                    ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1, NS2)),
                    ExerciseAddition(NE2, BUNDLED_SQUAT, listOf(NS3)),
                    // Repeated catalogue reference with distinct fresh child IDs.
                    ExerciseAddition(NE3, BUNDLED_PUSH_UP, listOf(NS4, NS5)),
                    // Owned custom reference with no history falls back.
                    ExerciseAddition(NE4, C_OWN, listOf(NS6)),
                ),
                knownServerRevision = 1L,
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val expected = WorkoutRecordModel(
            id = W_TGT,
            name = "Active",
            status = "active",
            startedAtMillis = STARTED_AT,
            finishedAtMillis = null,
            revision = 3L,
            exercises = listOf(
                WorkoutExerciseModel(
                    id = TE0,
                    exerciseId = BUNDLED_SQUAT,
                    position = 0,
                    notes = "keep",
                    restDurationSeconds = 60,
                    sets = listOf(FakeSyncFixtures.workoutSet(TS0, 0, 6, 20.0, false, null)),
                    exerciseName = "Squat",
                ),
                WorkoutExerciseModel(
                    id = NE1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 1,
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
                    position = 2,
                    notes = "",
                    restDurationSeconds = 45,
                    sets = listOf(FakeSyncFixtures.workoutSet(NS3, 0, 12, 30.0, false, null)),
                    exerciseName = "Squat",
                ),
                WorkoutExerciseModel(
                    id = NE3,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 3,
                    notes = "",
                    restDurationSeconds = 90,
                    sets = listOf(
                        FakeSyncFixtures.workoutSet(NS4, 0, 10, 60.0, false, null),
                        FakeSyncFixtures.workoutSet(NS5, 1, 8, 50.0, false, null),
                    ),
                    exerciseName = "Push-Up",
                ),
                WorkoutExerciseModel(
                    id = NE4,
                    exerciseId = C_OWN,
                    position = 4,
                    notes = "",
                    restDurationSeconds = 30,
                    sets = listOf(FakeSyncFixtures.workoutSet(NS6, 0, null, null, false, null)),
                    exerciseName = "Cable Fly",
                ),
            ),
        )
        val expectedBatch = harness.expectedBatch(
            6,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W_TGT,
                    ChangeOperation.UPSERT,
                    3L,
                    CanonicalRecord.Workout(expected),
                ),
            ),
        )
        assertEquals(expectedBatch, result.results[0].batch)
        assertEquals(listOf(expectedBatch), harness.acceptedBatches().takeLast(1))
        assertEquals(CanonicalRecord.Workout(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_TGT))
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W_TGT))
        // History notes ("tired") never copy; only rest and set inputs do.
        // The finished source is preserved exactly and stays finished.
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W_HIST))
        assertEquals(W_TGT, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun addExercises_selectsNewestFinishedThenHighestWorkoutId() = runTest {
        val harness = FakeSyncHarness(64)
        val wLo = "0a0a0a0a-0a0a-0a0a-0a0a-0a0a0a0a0a0a"
        val wHi = "9a9a9a9a-9a9a-9a9a-9a9a-9a9a9a9a9a9a"
        val wTieLo = "1b1b1b1b-1b1b-1b1b-1b1b-1b1b1b1b1b1b"
        val wTieHi = "8b8b8b8b-8b8b-8b8b-8b8b-8b8b8b8b8b8b"
        // Lower UUID but newer finish wins for push-ups.
        importFinishedHistory(
            harness,
            wLo,
            FINISHED_AT,
            listOf(
                FakeSyncFixtures.workoutEntry(
                    "0a0a0a0a-0a0a-0a0a-0a0a-0a0a0a0a0aa1",
                    BUNDLED_PUSH_UP,
                    0,
                    listOf(FakeSyncFixtures.workoutSet("0a0a0a0a-0a0a-0a0a-0a0a-0a0a0a0a0ab1", 0, 5, 40.0, true, 10)),
                    restDurationSeconds = 45,
                ),
            ),
        )
        importFinishedHistory(
            harness,
            wHi,
            FINISHED_EARLY,
            listOf(
                FakeSyncFixtures.workoutEntry(
                    "9a9a9a9a-9a9a-9a9a-9a9a-9a9a9a9a9aa1",
                    BUNDLED_PUSH_UP,
                    0,
                    listOf(FakeSyncFixtures.workoutSet("9a9a9a9a-9a9a-9a9a-9a9a-9a9a9a9a9ab1", 0, 10, 60.0, true, 10)),
                    restDurationSeconds = 90,
                ),
            ),
        )
        // Equal finish time: higher workout UUID wins for squats.
        importFinishedHistory(
            harness,
            wTieLo,
            FINISHED_LATE,
            listOf(
                FakeSyncFixtures.workoutEntry(
                    "1b1b1b1b-1b1b-1b1b-1b1b-1b1b1b1b1ba1",
                    BUNDLED_SQUAT,
                    0,
                    listOf(FakeSyncFixtures.workoutSet("1b1b1b1b-1b1b-1b1b-1b1b-1b1b1b1b1bb1", 0, 3, 10.0, true, 10)),
                    restDurationSeconds = 20,
                ),
            ),
        )
        importFinishedHistory(
            harness,
            wTieHi,
            FINISHED_LATE,
            listOf(
                FakeSyncFixtures.workoutEntry(
                    "8b8b8b8b-8b8b-8b8b-8b8b-8b8b8b8b8ba1",
                    BUNDLED_SQUAT,
                    0,
                    listOf(FakeSyncFixtures.workoutSet("8b8b8b8b-8b8b-8b8b-8b8b-8b8b8b8b8bb1", 0, 7, 70.0, true, 10)),
                    restDurationSeconds = 70,
                ),
            ),
        )
        seedActiveTarget(harness)

        val result = harness.pushA(
            harness.addExercisesRequest(
                W_TGT,
                listOf(
                    ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1)),
                    ExerciseAddition(NE2, BUNDLED_SQUAT, listOf(NS2)),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val record = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_TGT) as CanonicalRecord.Workout
        // Newer finish beats the higher UUID: rest 45 with the 5x40 input.
        assertEquals(45, record.record.exercises[1].restDurationSeconds)
        assertEquals(
            listOf(FakeSyncFixtures.workoutSet(NS1, 0, 5, 40.0, false, null)),
            record.record.exercises[1].sets,
        )
        // Tied finish resolves by higher workout UUID: rest 70 with 7x70.
        assertEquals(70, record.record.exercises[2].restDurationSeconds)
        assertEquals(
            listOf(FakeSyncFixtures.workoutSet(NS2, 0, 7, 70.0, false, null)),
            record.record.exercises[2].sets,
        )
        assertEquals("Push-Up", record.record.exercises[1].exerciseName)
        assertEquals("Squat", record.record.exercises[2].exerciseName)
        assertEquals(3L, record.record.revision)
    }

    @Test
    fun addExercises_selectsFirstPositionAndPairsRestWithItsSets() = runTest {
        val harness = FakeSyncHarness(64)
        val wTwo = "2c2c2c2c-2c2c-2c2c-2c2c-2c2c2c2c2c2c"
        // One workout, two occurrences: position 0 must win with its own rest/sets.
        importFinishedHistory(
            harness,
            wTwo,
            FINISHED_AT,
            listOf(
                FakeSyncFixtures.workoutEntry(
                    "2c2c2c2c-2c2c-2c2c-2c2c-2c2c2c2c2ca1",
                    BUNDLED_PUSH_UP,
                    0,
                    listOf(
                        FakeSyncFixtures.workoutSet("2c2c2c2c-2c2c-2c2c-2c2c-2c2c2c2c2cb1", 0, 1, 10.0, true, 5),
                        FakeSyncFixtures.workoutSet("2c2c2c2c-2c2c-2c2c-2c2c-2c2c2c2c2cb2", 1, 2, 15.0, true, 5),
                    ),
                    restDurationSeconds = 55,
                ),
                FakeSyncFixtures.workoutEntry(
                    "2c2c2c2c-2c2c-2c2c-2c2c-2c2c2c2c2ca2",
                    BUNDLED_PUSH_UP,
                    1,
                    listOf(FakeSyncFixtures.workoutSet("2c2c2c2c-2c2c-2c2c-2c2c-2c2c2c2c2cb3", 0, 9, 90.0, true, 5)),
                    restDurationSeconds = 75,
                ),
            ),
        )
        seedActiveTarget(harness)

        val result = harness.pushA(
            harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1, NS2)))),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val record = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_TGT) as CanonicalRecord.Workout
        val added = record.record.exercises[1]
        assertEquals(55, added.restDurationSeconds)
        assertEquals(
            listOf(
                FakeSyncFixtures.workoutSet(NS1, 0, 1, 10.0, false, null),
                FakeSyncFixtures.workoutSet(NS2, 1, 2, 15.0, false, null),
            ),
            added.sets,
        )
    }

    @Test
    fun addExercises_ignoresForeignAndDeletedHistory() = runTest {
        val harness = FakeSyncHarness(64)
        seedFinishedHistory(harness)
        // A foreign finished workout is newer but invisible to the caller.
        harness.pushB(
            harness.importRequest(
                ImportRecord.Workout(
                    WorkoutImportModel(
                        W_FOREIGN,
                        "Theirs",
                        "finished",
                        STARTED_AT,
                        FINISHED_LATE,
                        listOf(
                            FakeSyncFixtures.workoutEntry(
                                "40444444-4444-4444-4444-4444444444a1",
                                BUNDLED_PUSH_UP,
                                0,
                                listOf(
                                    FakeSyncFixtures.workoutSet("40444444-4444-4444-4444-4444444444b1", 0, 99, 99.0, true, 5),
                                ),
                                restDurationSeconds = 999,
                            ),
                        ),
                    ),
                ),
            ),
        )
        // A caller-owned newer history workout is deleted before selection.
        val wDoomed = "5d5d5d5d-5d5d-5d5d-5d5d-5d5d5d5d5d5d"
        importFinishedHistory(
            harness,
            wDoomed,
            FINISHED_LATE,
            listOf(
                FakeSyncFixtures.workoutEntry(
                    "5d5d5d5d-5d5d-5d5d-5d5d-5d5d5d5d5da1",
                    BUNDLED_PUSH_UP,
                    0,
                    listOf(FakeSyncFixtures.workoutSet("5d5d5d5d-5d5d-5d5d-5d5d-5d5d5d5d5db1", 0, 3, 30.0, true, 5)),
                    restDurationSeconds = 70,
                ),
            ),
        )
        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, wDoomed))
        seedActiveTarget(harness)

        val result = harness.pushA(
            harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1, NS2)))),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        // Both newer candidates are skipped: the caller's live history wins.
        val record = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_TGT) as CanonicalRecord.Workout
        assertEquals(90, record.record.exercises[1].restDurationSeconds)
        assertEquals(
            listOf(
                FakeSyncFixtures.workoutSet(NS1, 0, 10, 60.0, false, null),
                FakeSyncFixtures.workoutSet(NS2, 1, 8, 50.0, false, null),
            ),
            record.record.exercises[1].sets,
        )
    }

    @Test
    fun addExercises_withoutHistoryCreatesOneEmptySetAndDefaultRest() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W_TGT, "Active", STARTED_AT))

        val result = harness.pushA(
            harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1)))),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)

        val expected = WorkoutRecordModel(
            id = W_TGT,
            name = "Active",
            status = "active",
            startedAtMillis = STARTED_AT,
            finishedAtMillis = null,
            revision = 2L,
            exercises = listOf(
                WorkoutExerciseModel(
                    id = NE1,
                    exerciseId = BUNDLED_PUSH_UP,
                    position = 0,
                    notes = "",
                    restDurationSeconds = 30,
                    sets = listOf(FakeSyncFixtures.workoutSet(NS1, 0, null, null, false, null)),
                    exerciseName = "Push-Up",
                ),
            ),
        )
        val expectedBatch = harness.expectedBatch(
            1,
            listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W_TGT,
                    ChangeOperation.UPSERT,
                    2L,
                    CanonicalRecord.Workout(expected),
                ),
            ),
        )
        assertEquals(expectedBatch, result.results[0].batch)
        assertEquals(CanonicalRecord.Workout(expected), harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_TGT))
        assertEquals(W_TGT, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun addExercises_rejectsMissingForeignDeletedAndFinishedTargets() = runTest {
        val harness = FakeSyncHarness()
        seedActiveTarget(harness)
        harness.pushB(harness.startRequest(W_FOREIGN, "Theirs", STARTED_AT))
        val additions = listOf(ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1)))

        var state = harness.captureState(key(W_TGT), key(W_FOREIGN), key(UNKNOWN))
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(UNKNOWN, additions)),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_FOREIGN, additions)),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W_TGT))
        state = harness.captureState(key(W_TGT), key(W_FOREIGN))
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_TGT, additions)),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()
        assertEquals(null, harness.service.activeWorkoutId(ACCOUNT_A))

        // A finished target is immutable even though it stays owned.
        seedFinishedHistory(harness)
        state = harness.captureState(key(W_TGT), key(W_HIST))
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_HIST, additions)),
            0,
            FakeSyncService.IMMUTABLE_FINISHED,
        )
        state.assertUnchanged()
    }

    @Test
    fun addExercises_rejectsInvisibleExerciseReferences() = runTest {
        val harness = FakeSyncHarness()
        seedActiveTarget(harness)
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C_OWN)))
        harness.pushB(harness.customUpsertRequest(FakeSyncFixtures.customInput(C_FOREIGN)))
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C_DELETED)))
        harness.pushA(harness.deleteRequest(AggregateType.CUSTOM_EXERCISE, C_DELETED))
        val state = harness.captureState(key(W_TGT))

        // Unknown exercise UUID.
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, UNKNOWN, listOf(NS1))))),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()

        // A muscle-group UUID lives in another namespace and is never an exercise.
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, MUSCLE_CHEST, listOf(NS1))))),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()

        // Foreign custom exercises are invisible to the caller.
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, C_FOREIGN, listOf(NS1))))),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()

        // Deleted custom exercises are invisible too.
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, C_DELETED, listOf(NS1))))),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()
        assertEquals(W_TGT, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun addExercises_rejectsChangedHistoryShape() = runTest {
        val harness = FakeSyncHarness(64)
        seedFinishedHistory(harness)
        seedActiveTarget(harness)
        val state = harness.captureState(key(W_TGT), key(W_HIST))

        // History holds two completed push-up sets: one ID is too few.
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1))))),
            0,
            FakeSyncService.HISTORY_SHAPE_CHANGED,
        )
        state.assertUnchanged()

        // Three IDs are too many.
        assertRejectedWithCode(
            harness.pushA(
                harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1, NS2, NS3)))),
            ),
            0,
            FakeSyncService.HISTORY_SHAPE_CHANGED,
        )
        state.assertUnchanged()

        // A newer finish changes the derived shape: the old one-ID plan now rejects.
        val wNewer = "6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e"
        importFinishedHistory(
            harness,
            wNewer,
            FINISHED_LATE,
            listOf(
                FakeSyncFixtures.workoutEntry(
                    "6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6ea1",
                    BUNDLED_SQUAT,
                    0,
                    listOf(
                        FakeSyncFixtures.workoutSet("6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6eb1", 0, 5, 50.0, true, 5),
                        FakeSyncFixtures.workoutSet("6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6eb2", 1, 6, 55.0, true, 5),
                    ),
                    restDurationSeconds = 120,
                ),
            ),
        )
        val changedState = harness.captureState(key(W_TGT), key(W_HIST), key(wNewer))
        assertRejectedWithCode(
            harness.pushA(harness.addExercisesRequest(W_TGT, listOf(ExerciseAddition(NE2, BUNDLED_SQUAT, listOf(NS3))))),
            0,
            FakeSyncService.HISTORY_SHAPE_CHANGED,
        )
        changedState.assertUnchanged()
    }

    @Test
    fun addExercises_rejectsCollidingIdsAndKeepsBatchAtomic() = runTest {
        val harness = FakeSyncHarness(64)
        seedFinishedHistory(harness)
        seedActiveTarget(harness)
        val state = harness.captureState(key(W_TGT), key(W_HIST))

        // Reusing an entry ID already owned by the target collides.
        assertRejectedWithCode(
            harness.pushA(
                harness.addExercisesRequest(
                    W_TGT,
                    listOf(ExerciseAddition(TE0, BUNDLED_SQUAT, listOf(NS3))),
                ),
            ),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        state.assertUnchanged()

        // Reusing a set ID already owned by the target collides.
        assertRejectedWithCode(
            harness.pushA(
                harness.addExercisesRequest(
                    W_TGT,
                    listOf(ExerciseAddition(NE2, BUNDLED_SQUAT, listOf(TS0))),
                ),
            ),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        state.assertUnchanged()

        // A new child must never reuse the target root UUID: envelope
        // validation rejects the plan locally before the fake executes.
        try {
            harness.addExercisesRequest(
                W_TGT,
                listOf(ExerciseAddition(W_TGT, BUNDLED_SQUAT, listOf(NS3))),
            )
            fail("Expected root-reusing addition to throw")
        } catch (_: SyncModelException) {
        }
        state.assertUnchanged()

        // A child ID retained by another root cannot be reparented.
        assertRejectedWithCode(
            harness.pushA(
                harness.addExercisesRequest(
                    W_TGT,
                    listOf(ExerciseAddition(HE1, BUNDLED_SQUAT, listOf(NS3))),
                ),
            ),
            0,
            FakeSyncService.CHILD_COLLISION,
        )
        state.assertUnchanged()

        // A partial batch is atomic: a valid first addition does not commit
        // when the second addition references an invisible exercise.
        assertRejectedWithCode(
            harness.pushA(
                harness.addExercisesRequest(
                    W_TGT,
                    listOf(
                        ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1, NS2)),
                        ExerciseAddition(NE2, UNKNOWN, listOf(NS3)),
                    ),
                ),
            ),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()

        // A clean retry after rejections still succeeds: nothing half-committed.
        val result = harness.pushA(
            harness.addExercisesRequest(
                W_TGT,
                listOf(
                    ExerciseAddition(NE1, BUNDLED_PUSH_UP, listOf(NS1, NS2)),
                    ExerciseAddition(NE2, BUNDLED_SQUAT, listOf(NS3)),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val record = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W_TGT) as CanonicalRecord.Workout
        assertEquals(3, record.record.exercises.size)
        assertEquals(90, record.record.exercises[1].restDurationSeconds)
        assertEquals(45, record.record.exercises[2].restDurationSeconds)
        assertEquals(3L, record.record.revision)
        assertEquals(W_TGT, harness.service.activeWorkoutId(ACCOUNT_A))
    }
}
