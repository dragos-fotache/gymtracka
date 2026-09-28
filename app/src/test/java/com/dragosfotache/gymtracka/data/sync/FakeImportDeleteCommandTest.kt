package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_A
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_B
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_PUSH_UP
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_SQUAT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.FINISHED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.STARTED_AT
import com.dragosfotache.gymtracka.data.sync.FakeSyncHarness.Companion.assertRejectedWithCode
import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.ExerciseAddition
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Independent tests for the fake `aggregate.import` and `aggregate.delete`
 * commands, the ten explicitly unsupported kinds, account isolation, and
 * state-aliasing defenses. Expected records and batches are constructed by
 * hand from the specification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeImportDeleteCommandTest {
    companion object {
        const val W1 = "11111111-1111-1111-1111-111111111111"
        const val W2 = "22222222-2222-2222-2222-222222222222"
        const val W3 = "33333333-3333-3333-3333-333333333333"
        const val R1 = "44444444-4444-4444-4444-444444444444"
        const val R2 = "55555555-5555-5555-5555-555555555555"
        const val E1 = "66666666-6666-6666-6666-666666666666"
        const val E2 = "77777777-7777-7777-7777-777777777777"
        const val E3 = "88888888-8888-8888-8888-888888888883"
        const val S1 = "99999999-9999-9999-9999-999999999991"
        const val S2 = "99999999-9999-9999-9999-999999999992"
        const val S3 = "99999999-9999-9999-9999-999999999993"
        const val C1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val C9 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa09"
        const val UNKNOWN_EXERCISE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    }

    private fun workoutKey(id: String) = FakeSyncService.RootKey(AggregateType.WORKOUT, id)
    private fun routineKey(id: String) = FakeSyncService.RootKey(AggregateType.ROUTINE, id)
    private fun customKey(id: String) = FakeSyncService.RootKey(AggregateType.CUSTOM_EXERCISE, id)

    private fun activeImport(
        id: String,
        name: String = "Imported Workout",
        exercises: List<WorkoutExerciseInputModel> = listOf(
            WorkoutExerciseInputModel(
                E1,
                BUNDLED_PUSH_UP,
                0,
                "imported notes",
                45,
                listOf(FakeSyncFixtures.workoutSet(S1, 0, 8, 50.0, false, null)),
            ),
        ),
    ) = ImportRecord.Workout(WorkoutImportModel(id, name, "active", STARTED_AT, null, exercises))

    @Test
    fun import_createsAllThreeTypesWithIndependentExpectedRecords() = runTest {
        val harness = FakeSyncHarness()
        val workoutResult = harness.pushA(harness.importRequest(activeImport(W1)))
        val expectedWorkout = WorkoutRecordModel(
            id = W1,
            name = "Imported Workout",
            status = "active",
            startedAtMillis = STARTED_AT,
            finishedAtMillis = null,
            revision = 1L,
            exercises = listOf(
                WorkoutExerciseModel(
                    E1,
                    BUNDLED_PUSH_UP,
                    0,
                    "imported notes",
                    45,
                    listOf(FakeSyncFixtures.workoutSet(S1, 0, 8, 50.0, false, null)),
                    "Push-Up",
                ),
            ),
        )
        val expectedWorkoutBatch = harness.expectedBatch(
            0,
            listOf(
                SyncChangeModel(AggregateType.WORKOUT, W1, ChangeOperation.UPSERT, 1L, CanonicalRecord.Workout(expectedWorkout)),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, workoutResult.results[0].status)
        assertEquals(expectedWorkoutBatch, workoutResult.results[0].batch)
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))

        val routineResult = harness.pushA(
            harness.importRequest(
                ImportRecord.Routine(
                    FakeSyncFixtures.routineInput(
                        id = R1,
                        name = "Imported Plan",
                        isDraft = true,
                        exercises = listOf(
                            FakeSyncFixtures.routineEntry(
                                E2,
                                BUNDLED_SQUAT,
                                0,
                                listOf(RoutineSetModel(S2, 0, 5, 20.0)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val expectedRoutine = RoutineRecordModel(
            id = R1,
            name = "Imported Plan",
            isDraft = true,
            revision = 1L,
            exercises = listOf(
                RoutineExerciseModel(
                    E2,
                    BUNDLED_SQUAT,
                    0,
                    30,
                    listOf(RoutineSetModel(S2, 0, 5, 20.0)),
                    "Squat",
                ),
            ),
        )
        val expectedRoutineBatch = harness.expectedBatch(
            1,
            listOf(
                SyncChangeModel(AggregateType.ROUTINE, R1, ChangeOperation.UPSERT, 1L, CanonicalRecord.Routine(expectedRoutine)),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, routineResult.results[0].status)
        assertEquals(expectedRoutineBatch, routineResult.results[0].batch)
        assertEquals(
            CanonicalRecord.Routine(expectedRoutine),
            harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1),
        )

        val customResult = harness.pushA(
            harness.importRequest(ImportRecord.CustomExercise(FakeSyncFixtures.customInput(C1))),
        )
        val expectedCustom = CustomExerciseRecordModel(
            id = C1,
            name = "Cable Fly",
            type = "reps",
            category = null,
            difficultyLevel = null,
            forceType = null,
            mechanics = null,
            instructions = null,
            isUserCreated = true,
            revision = 1L,
            muscleGroups = listOf(MuscleGroupRefModel(FakeSyncFixtures.MUSCLE_CHEST, "Chest", "primary")),
            equipment = emptyList(),
        )
        val expectedCustomBatch = harness.expectedBatch(
            2,
            listOf(
                SyncChangeModel(
                    AggregateType.CUSTOM_EXERCISE,
                    C1,
                    ChangeOperation.UPSERT,
                    1L,
                    CanonicalRecord.CustomExercise(expectedCustom),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, customResult.results[0].status)
        assertEquals(expectedCustomBatch, customResult.results[0].batch)
        assertEquals(
            CanonicalRecord.CustomExercise(expectedCustom),
            harness.service.inspectRecord(ACCOUNT_A, AggregateType.CUSTOM_EXERCISE, C1),
        )
    }

    @Test
    fun import_preservesSavedSnapshotIncludingBlankEmpty() = runTest {
        val harness = FakeSyncHarness()
        // Unlike ordinary routine.upsert creation (draft-only), import
        // preserves a saved snapshot, including valid blank/empty states.
        val result = harness.pushA(
            harness.importRequest(
                ImportRecord.Routine(FakeSyncFixtures.routineInput(R2, "", false, emptyList())),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R2) as CanonicalRecord.Routine
        assertEquals(false, stored.record.isDraft)
        assertEquals("", stored.record.name)
        assertTrue(stored.record.exercises.isEmpty())
    }

    @Test
    fun import_rejectsEveryCollisionWithoutEffects() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.importRequest(activeImport(W1)))

        var state = harness.captureState(workoutKey(W1), workoutKey(W2))
        assertRejectedWithCode(harness.pushB(harness.importRequest(activeImport(W1))), 0, FakeSyncService.ROOT_ALREADY_EXISTS)
        state.assertUnchanged()
        assertRejectedWithCode(harness.pushA(harness.importRequest(activeImport(W1))), 0, FakeSyncService.ROOT_ALREADY_EXISTS)
        state.assertUnchanged()

        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W1))
        state = harness.captureState(workoutKey(W1))
        assertRejectedWithCode(harness.pushA(harness.importRequest(activeImport(W1))), 0, FakeSyncService.ROOT_ALREADY_EXISTS)
        state.assertUnchanged()
    }

    @Test
    fun import_activeReplacesPriorActiveWhileFinishedDoesNot() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Current", STARTED_AT))

        val result = harness.pushA(harness.importRequest(activeImport(W2, "Replacement")))
        val expected = harness.expectedBatch(
            1,
            listOf(
                SyncChangeModel(AggregateType.WORKOUT, W1, ChangeOperation.DELETE, 2L, null),
                SyncChangeModel(
                    AggregateType.WORKOUT,
                    W2,
                    ChangeOperation.UPSERT,
                    1L,
                    CanonicalRecord.Workout(
                        WorkoutRecordModel(
                            W2,
                            "Replacement",
                            "active",
                            STARTED_AT,
                            null,
                            1L,
                            listOf(
                                WorkoutExerciseModel(
                                    E1,
                                    BUNDLED_PUSH_UP,
                                    0,
                                    "imported notes",
                                    45,
                                    listOf(FakeSyncFixtures.workoutSet(S1, 0, 8, 50.0, false, null)),
                                    "Push-Up",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(expected, result.results[0].batch)
        assertTrue(harness.service.isTombstoned(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W2, harness.service.activeWorkoutId(ACCOUNT_A))

        // A finished import preserves its status/time and never disturbs the active workout.
        val finishedResult = harness.pushA(
            harness.importRequest(
                ImportRecord.Workout(
                    WorkoutImportModel(
                        id = W3,
                        name = "Done",
                        status = "finished",
                        startedAtMillis = STARTED_AT,
                        finishedAtMillis = FINISHED_AT,
                        exercises = listOf(
                            WorkoutExerciseInputModel(
                                E2,
                                BUNDLED_SQUAT,
                                0,
                                "",
                                30,
                                listOf(FakeSyncFixtures.workoutSet(S2, 0, 8, 50.0, true, 60)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, finishedResult.results[0].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W3) as CanonicalRecord.Workout
        assertEquals("finished", stored.record.status)
        assertEquals(FINISHED_AT, stored.record.finishedAtMillis)
        assertEquals(W2, harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun import_rejectedActiveImportLeavesPriorActiveUntouched() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Current", STARTED_AT))
        val oldActive = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1)

        // A colliding active import rejects before staging any replacement:
        // the old active record, revision, ownership, tombstone flag, active
        // pointer, and log stay exactly unchanged.
        var state = harness.captureState(workoutKey(W1), workoutKey(W2))
        assertRejectedWithCode(harness.pushA(harness.importRequest(activeImport(W1))), 0, FakeSyncService.ROOT_ALREADY_EXISTS)
        state.assertUnchanged()
        assertEquals(oldActive, harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))

        // An invalid active import also stages no replacement.
        state = harness.captureState(workoutKey(W1), workoutKey(W2))
        assertRejectedWithCode(
            harness.pushA(
                harness.importRequest(
                    ImportRecord.Workout(
                        WorkoutImportModel(
                            W2,
                            "Bad Ref",
                            "active",
                            STARTED_AT,
                            null,
                            listOf(WorkoutExerciseInputModel(E2, UNKNOWN_EXERCISE, 0, "", 30, emptyList())),
                        ),
                    ),
                ),
            ),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()
        assertEquals(oldActive, harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(W1, harness.service.activeWorkoutId(ACCOUNT_A))
        assertEquals(1L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(ACCOUNT_A, harness.service.ownerOf(AggregateType.WORKOUT, W1))
        assertFalse(harness.service.isTombstoned(ACCOUNT_A, AggregateType.WORKOUT, W1))
    }

    @Test
    fun import_rejectsUnknownReferenceAndMissingCustomDep() = runTest {
        val harness = FakeSyncHarness()
        var state = harness.captureState(workoutKey(W1))
        assertRejectedWithCode(
            harness.pushA(
                harness.importRequest(
                    ImportRecord.Workout(
                        WorkoutImportModel(
                            W1,
                            "Bad Ref",
                            "active",
                            STARTED_AT,
                            null,
                            listOf(
                                WorkoutExerciseInputModel(E1, UNKNOWN_EXERCISE, 0, "", 30, emptyList()),
                            ),
                        ),
                    ),
                ),
            ),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()

        // Custom dependencies must already exist: dependency-ordered creation
        // in one push succeeds, the dangling reference alone rejects.
        val ordered = harness.pushA(
            harness.importRequest(ImportRecord.CustomExercise(FakeSyncFixtures.customInput(C1))),
            harness.importRequest(
                ImportRecord.Workout(
                    WorkoutImportModel(
                        W1,
                        "Dependent",
                        "active",
                        STARTED_AT,
                        null,
                        listOf(WorkoutExerciseInputModel(E1, C1, 0, "", 30, emptyList())),
                    ),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, ordered.results[0].status)
        assertEquals(MutationStatus.ACCEPTED, ordered.results[1].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) as CanonicalRecord.Workout
        assertEquals("Cable Fly", stored.record.exercises[0].exerciseName)

        state = harness.captureState(workoutKey(W2))
        assertRejectedWithCode(
            harness.pushA(
                harness.importRequest(
                    ImportRecord.Workout(
                        WorkoutImportModel(
                            W2,
                            "Dangling",
                            "active",
                            STARTED_AT,
                            null,
                            listOf(WorkoutExerciseInputModel(E2, C9, 0, "", 30, emptyList())),
                        ),
                    ),
                ),
            ),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()
    }

    @Test
    fun delete_removesLiveRootWithTombstoneEvidenceAndClearsActive() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
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
        )

        val result = harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W1))
        val expected = harness.expectedBatch(
            2,
            listOf(SyncChangeModel(AggregateType.WORKOUT, W1, ChangeOperation.DELETE, 3L, null)),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(expected, result.results[0].batch)
        assertNull(harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertTrue(harness.service.isTombstoned(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(3L, harness.service.inspectRevision(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(ACCOUNT_A, harness.service.ownerOf(AggregateType.WORKOUT, W1))
        assertNull(harness.service.activeWorkoutId(ACCOUNT_A))
    }

    @Test
    fun delete_reDeleteEmitsNewDeletionBatchForEveryType() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
        harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1)))
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1)))

        val cases = listOf(
            AggregateType.WORKOUT to W1,
            AggregateType.ROUTINE to R1,
            AggregateType.CUSTOM_EXERCISE to C1,
        )
        for ((type, id) in cases) {
            val first = harness.pushA(harness.deleteRequest(type, id))
            assertEquals(MutationStatus.ACCEPTED, first.results[0].status)
            val revisionAfterFirst = harness.service.inspectRevision(ACCOUNT_A, type, id)
            val second = harness.pushA(harness.deleteRequest(type, id))
            assertEquals(MutationStatus.ACCEPTED, second.results[0].status)
            // Idempotent in effect (still tombstoned) with a new deletion revision and batch.
            assertTrue(harness.service.isTombstoned(ACCOUNT_A, type, id))
            assertEquals(revisionAfterFirst!! + 1L, harness.service.inspectRevision(ACCOUNT_A, type, id))
            val batches = harness.service.loggedBatches(ACCOUNT_A)
            val last = batches.last()
            assertEquals(
                SyncChangeModel(type, id, ChangeOperation.DELETE, revisionAfterFirst + 1L, null),
                last.changes.single { it.aggregateId == id },
            )
            assertNull(harness.service.inspectRecord(ACCOUNT_A, type, id))
        }
    }

    @Test
    fun delete_rejectsMissingAndForeignIdenticallyWithoutDisclosure() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))

        val state = harness.captureState(workoutKey(W1), workoutKey(W2))
        assertRejectedWithCode(harness.pushB(harness.deleteRequest(AggregateType.WORKOUT, W1)), 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        assertRejectedWithCode(harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W2)), 0, FakeSyncService.OWNED_ROOT_MISSING)
        state.assertUnchanged()
        // No foreign data leaks through inspection or outcomes.
        assertNull(harness.service.inspectRecord(ACCOUNT_B, AggregateType.WORKOUT, W1))
        assertNull(harness.service.inspectRevision(ACCOUNT_B, AggregateType.WORKOUT, W1))
        assertFalse(harness.service.isTombstoned(ACCOUNT_B, AggregateType.WORKOUT, W1))
    }

    @Test
    fun delete_customReferencedBlocksUntilParentsDeleted() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1)))
        harness.pushA(harness.startRequest(W1))
        harness.pushA(
            harness.upsertRequest(
                FakeSyncFixtures.workoutInput(
                    id = W1,
                    exercises = listOf(
                        FakeSyncFixtures.workoutEntry(
                            id = E1,
                            exerciseId = C1,
                            position = 0,
                            sets = listOf(FakeSyncFixtures.workoutSet(S1, 0)),
                        ),
                    ),
                ),
            ),
        )
        harness.pushA(
            harness.routineUpsertRequest(
                FakeSyncFixtures.routineInput(
                    id = R1,
                    exercises = listOf(
                        FakeSyncFixtures.routineEntry(
                            id = E2,
                            exerciseId = C1,
                            position = 0,
                            sets = listOf(RoutineSetModel(S2, 0, 5, 20.0)),
                        ),
                    ),
                ),
            ),
        )
        harness.pushA(
            harness.importRequest(
                ImportRecord.Workout(
                    WorkoutImportModel(
                        id = W3,
                        name = "History",
                        status = "finished",
                        startedAtMillis = STARTED_AT,
                        finishedAtMillis = FINISHED_AT,
                        exercises = listOf(
                            WorkoutExerciseInputModel(
                                E3,
                                C1,
                                0,
                                "",
                                30,
                                listOf(FakeSyncFixtures.workoutSet(S3, 0, 8, 50.0, true, 60)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        var state = harness.captureState(customKey(C1), workoutKey(W1), routineKey(R1), workoutKey(W3))
        // Live references from an active workout, a draft routine, and
        // finished history all block the delete atomically.
        assertRejectedWithCode(
            harness.pushA(harness.deleteRequest(AggregateType.CUSTOM_EXERCISE, C1)),
            0,
            FakeSyncService.CUSTOM_EXERCISE_REFERENCED,
        )
        state.assertUnchanged()

        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W1))
        harness.pushA(harness.deleteRequest(AggregateType.ROUTINE, R1))
        state = harness.captureState(customKey(C1))
        // Finished history alone still blocks.
        assertRejectedWithCode(
            harness.pushA(harness.deleteRequest(AggregateType.CUSTOM_EXERCISE, C1)),
            0,
            FakeSyncService.CUSTOM_EXERCISE_REFERENCED,
        )
        state.assertUnchanged()

        // Deleted parents no longer block: removing history unblocks the custom delete.
        harness.pushA(harness.deleteRequest(AggregateType.WORKOUT, W3))
        val result = harness.pushA(harness.deleteRequest(AggregateType.CUSTOM_EXERCISE, C1))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertTrue(harness.service.isTombstoned(ACCOUNT_A, AggregateType.CUSTOM_EXERCISE, C1))
    }

    @Test
    fun unsupported_allTenKindsRejectWithoutEffectsAndReadsStillThrow() = runTest {
        val harness = FakeSyncHarness()
        val requests = unsupportedRequests(harness)
        assertEquals(10, requests.size)

        val state = harness.captureState()
        val result = harness.pushA(*requests.toTypedArray())
        assertEquals(10, result.results.size)
        val mutationIds = result.results.map { it.mutationId }
        assertEquals(mutationIds.toSet().size, mutationIds.size)
        result.results.forEach { outcome ->
            assertEquals(MutationStatus.REJECTED, outcome.status)
            assertEquals(FakeSyncService.UNSUPPORTED_MUTATION_KIND, outcome.errorCode)
            assertNull(outcome.batch)
        }
        state.assertUnchanged()
        assertTrue(harness.service.loggedBatches(ACCOUNT_A).isEmpty())

        try {
            harness.apiA.pull(null, 100)
            fail("Expected fake pull to stay explicitly stubbed")
        } catch (_: UnsupportedOperationException) {
        }
        try {
            harness.apiA.snapshot(null, 200)
            fail("Expected fake snapshot to stay explicitly stubbed")
        } catch (_: UnsupportedOperationException) {
        }
    }

    @Test
    fun accountIsolation_scopesReadsWritesAndTypeScopedRoots() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1, "Mine", STARTED_AT))
        // The same UUID under another aggregate type is a different root and
        // may be owned by another account.
        val routineResult = harness.pushB(
            harness.routineUpsertRequest(FakeSyncFixtures.routineInput(W1, "Theirs", true)),
        )
        assertEquals(MutationStatus.ACCEPTED, routineResult.results[0].status)

        // Each account sees only its own root.
        assertTrue(harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1) is CanonicalRecord.Workout)
        assertNull(harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, W1))
        assertNull(harness.service.inspectRecord(ACCOUNT_B, AggregateType.WORKOUT, W1))
        assertTrue(harness.service.inspectRecord(ACCOUNT_B, AggregateType.ROUTINE, W1) is CanonicalRecord.Routine)

        // Cross-account mutation of the same type rejects without disclosure.
        val state = harness.captureState(workoutKey(W1))
        assertRejectedWithCode(
            harness.pushB(harness.upsertRequest(FakeSyncFixtures.workoutInput(W1, "Theirs"))),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        // Command responses never carry another owner's data.
        val ownResult = harness.pushA(
            harness.upsertRequest(FakeSyncFixtures.workoutInput(W1, "Mine Edited")),
        )
        val batch = ownResult.results[0].batch!!
        assertTrue(batch.changes.all { it.aggregateType == AggregateType.WORKOUT && it.aggregateId == W1 })
    }

    @Test
    fun storedState_isDetachedFromCallerMutationsAndReturnedValues() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.startRequest(W1))
        val customRequest = harness.customUpsertRequest(
            FakeSyncFixtures.customInput(
                id = C1,
                primaryMuscleGroupId = null,
                secondaryMuscleGroupIds = listOf(FakeSyncFixtures.MUSCLE_CHEST, FakeSyncFixtures.MUSCLE_BICEPS),
                equipmentId = FakeSyncFixtures.EQUIPMENT_DUMBBELL,
                equipmentNumItems = 1,
                instructions = listOf("Step one", ""),
            ),
        )
        val workoutRequest = harness.upsertRequest(
            FakeSyncFixtures.workoutInput(
                id = W1,
                exercises = listOf(
                    FakeSyncFixtures.workoutEntry(
                        id = E1,
                        exerciseId = BUNDLED_PUSH_UP,
                        position = 0,
                        sets = listOf(
                            FakeSyncFixtures.workoutSet(S1, 0, 8, 50.0, false, null),
                            FakeSyncFixtures.workoutSet(S2, 1, 10, 60.0, true, 61),
                        ),
                    ),
                    FakeSyncFixtures.workoutEntry(
                        id = E2,
                        exerciseId = BUNDLED_SQUAT,
                        position = 1,
                        sets = listOf(
                            FakeSyncFixtures.workoutSet(S3, 0, 5, 20.0, false, null),
                        ),
                    ),
                ),
            ),
        )
        val result = harness.pushA(customRequest, workoutRequest)
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(MutationStatus.ACCEPTED, result.results[1].status)
        val storedWorkout = harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1)
        val storedCustom = harness.service.inspectRecord(ACCOUNT_A, AggregateType.CUSTOM_EXERCISE, C1)

        // (a) Mutating retained request-envelope nested collections after push
        // cannot corrupt stored canonical state.
        val workoutEnvInput = (workoutRequest.envelope.payload as MutationPayload.WorkoutUpsert).input
        assertNotSame(
            workoutEnvInput.exercises,
            ((storedWorkout as CanonicalRecord.Workout).record.exercises),
        )
        assertTrue(workoutEnvInput.exercises[0].sets.isNotEmpty())
        assertEquals(2, workoutEnvInput.exercises[0].sets.size)
        // Sorted set lists may be fixed-size: mutate by element replacement.
        val envSets = workoutEnvInput.exercises[0].sets as MutableList<WorkoutSetModel>
        envSets[0] = envSets[0].copy(weightKg = 999.0)
        (workoutEnvInput.exercises as MutableList).clear()
        val customEnvInput = (customRequest.envelope.payload as MutationPayload.CustomExerciseUpsert).input
        assertTrue(customEnvInput.instructions!!.isNotEmpty())
        assertEquals(2, customEnvInput.secondaryMuscleGroupIds.size)
        (customEnvInput.instructions as MutableList).clear()
        val envSecondaries = customEnvInput.secondaryMuscleGroupIds as MutableList<String>
        envSecondaries[0] = UNKNOWN_EXERCISE
        assertEquals(storedWorkout, harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(storedCustom, harness.service.inspectRecord(ACCOUNT_A, AggregateType.CUSTOM_EXERCISE, C1))
        assertEquals(3, harness.service.loggedBatches(ACCOUNT_A).size)

        // (b) Mutating the push-returned outcome batch nested values cannot
        // corrupt the stored record or log.
        val outcomeBatch = result.results[1].batch!!
        val outcomeExercises =
            ((outcomeBatch.changes[0].record as CanonicalRecord.Workout).record.exercises as MutableList<WorkoutExerciseModel>)
        assertTrue(outcomeExercises.isNotEmpty())
        outcomeExercises.clear()
        (outcomeBatch.changes as MutableList).clear()
        assertEquals(storedWorkout, harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(3, harness.service.loggedBatches(ACCOUNT_A).size)
        assertTrue(
            (harness.service.loggedBatches(ACCOUNT_A)[2].changes[0].record as CanonicalRecord.Workout)
                .record.exercises.isNotEmpty(),
        )

        // (c) Mutating a logged-batch nested record cannot corrupt later reads.
        val logged = harness.service.loggedBatches(ACCOUNT_A)[2]
        (((logged.changes[0].record as CanonicalRecord.Workout).record.exercises) as MutableList).clear()
        (logged.changes as MutableList).clear()
        assertEquals(storedWorkout, harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1))
        assertEquals(3, harness.service.loggedBatches(ACCOUNT_A).size)
        assertTrue(
            (harness.service.loggedBatches(ACCOUNT_A)[2].changes[0].record as CanonicalRecord.Workout)
                .record.exercises.isNotEmpty(),
        )
        // Every inspection returns a fresh copy.
        assertNotSame(
            harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1),
            harness.service.inspectRecord(ACCOUNT_A, AggregateType.WORKOUT, W1),
        )
    }

    private fun unsupportedRequests(harness: FakeSyncHarness): List<DurableRequestModel> {
        fun envelope(
            kind: MutationKind,
            type: AggregateType,
            id: String,
            payload: MutationPayload,
        ): DurableRequestModel =
            captureDurableRequest(
                MutationEnvelopeModel(
                    mutationId = harness.nextMutationId(),
                    kind = kind,
                    aggregateType = type,
                    aggregateId = id,
                    localGeneration = 1L,
                    knownServerRevision = null,
                    payload = payload,
                ),
                listOf(AffectedRootModel(type, id, 1L)),
            )
        return listOf(
            envelope(
                MutationKind.WORKOUT_FINISH,
                AggregateType.WORKOUT,
                W1,
                MutationPayload.WorkoutFinish(FINISHED_AT),
            ),
            envelope(
                MutationKind.WORKOUT_PERFORM_AGAIN,
                AggregateType.WORKOUT,
                W2,
                MutationPayload.WorkoutPerformAgain(W1, W2, STARTED_AT, emptyList()),
            ),
            envelope(
                MutationKind.ROUTINE_START_WORKOUT,
                AggregateType.WORKOUT,
                W2,
                MutationPayload.RoutineStartWorkout(R1, W2, STARTED_AT, emptyList()),
            ),
            envelope(
                MutationKind.WORKOUT_SAVE_AS_ROUTINE,
                AggregateType.ROUTINE,
                R1,
                MutationPayload.WorkoutSaveAsRoutine(W1, R1, "Saved Plan", emptyList()),
            ),
            envelope(
                MutationKind.WORKOUT_ADD_EXERCISES,
                AggregateType.WORKOUT,
                W1,
                MutationPayload.WorkoutAddExercises(
                    listOf(ExerciseAddition(E1, BUNDLED_PUSH_UP, listOf(S1))),
                ),
            ),
            envelope(
                MutationKind.WORKOUT_REPLACE_EXERCISE,
                AggregateType.WORKOUT,
                W1,
                MutationPayload.WorkoutReplaceExercise(E1, BUNDLED_SQUAT),
            ),
            envelope(
                MutationKind.WORKOUT_DELETE_EXERCISE,
                AggregateType.WORKOUT,
                W1,
                MutationPayload.WorkoutDeleteExercise(E1),
            ),
            envelope(
                MutationKind.WORKOUT_DELETE_SET,
                AggregateType.WORKOUT,
                W1,
                MutationPayload.WorkoutDeleteSet(S1),
            ),
            envelope(
                MutationKind.ROUTINE_DELETE_EXERCISE,
                AggregateType.ROUTINE,
                R1,
                MutationPayload.RoutineDeleteExercise(E1),
            ),
            envelope(
                MutationKind.ROUTINE_DELETE_SET,
                AggregateType.ROUTINE,
                R1,
                MutationPayload.RoutineDeleteSet(S1),
            ),
        )
    }
}
