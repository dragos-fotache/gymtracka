package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.ACCOUNT_A
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_PUSH_UP
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.BUNDLED_SQUAT
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.EQUIPMENT_DUMBBELL
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.MUSCLE_BICEPS
import com.dragosfotache.gymtracka.data.sync.FakeSyncFixtures.MUSCLE_CHEST
import com.dragosfotache.gymtracka.data.sync.FakeSyncHarness.Companion.assertRejectedWithCode
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent tests for the fake `routine.upsert`, `routine.save`, and
 * `customExercise.upsert` commands. Expected records and batches are
 * constructed by hand from the specification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeRoutineCustomCommandTest {
    companion object {
        const val R1 = "11111111-1111-1111-1111-111111111111"
        const val R2 = "22222222-2222-2222-2222-222222222222"
        const val RE1 = "33333333-3333-3333-3333-333333333333"
        const val RE2 = "44444444-4444-4444-4444-444444444444"
        const val RS1 = "55555555-5555-5555-5555-555555555555"
        const val RS2 = "66666666-6666-6666-6666-666666666666"
        const val RS3 = "77777777-7777-7777-7777-777777777777"
        const val C1 = "88888888-8888-8888-8888-888888888888"
        const val UNKNOWN_REF = "99999999-9999-9999-9999-999999999999"
    }

    private fun routineKey(id: String) = FakeSyncService.RootKey(AggregateType.ROUTINE, id)
    private fun customKey(id: String) = FakeSyncService.RootKey(AggregateType.CUSTOM_EXERCISE, id)

    private fun entryWithSet() =
        FakeSyncFixtures.routineEntry(
            id = RE1,
            exerciseId = BUNDLED_PUSH_UP,
            position = 0,
            sets = listOf(RoutineSetModel(RS1, 0, 8, 100.0)),
        )

    @Test
    fun routineUpsert_createsDraftOnlyAndRejectsNonDraftCreate() = runTest {
        val harness = FakeSyncHarness()
        val input = FakeSyncFixtures.routineInput(R1, "Strength Plan", true, listOf(entryWithSet()))
        val result = harness.pushA(harness.routineUpsertRequest(input))

        val expectedRecord = RoutineRecordModel(
            id = R1,
            name = "Strength Plan",
            isDraft = true,
            revision = 1L,
            exercises = listOf(
                RoutineExerciseModel(RE1, BUNDLED_PUSH_UP, 0, 30, listOf(RoutineSetModel(RS1, 0, 8, 100.0)), "Push-Up"),
            ),
        )
        val expected = harness.expectedBatch(
            0,
            listOf(
                SyncChangeModel(AggregateType.ROUTINE, R1, ChangeOperation.UPSERT, 1L, CanonicalRecord.Routine(expectedRecord)),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(expected, result.results[0].batch)
        assertEquals(CanonicalRecord.Routine(expectedRecord), harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1))

        // Ordinary creation is draft-only: a non-draft create is rejected, not coerced.
        val state = harness.captureState(routineKey(R1), routineKey(R2))
        val nonDraft = FakeSyncFixtures.routineInput(R2, "Saved Plan", false, listOf(entryWithSet().copy(id = RE2)))
        assertRejectedWithCode(
            harness.pushA(harness.routineUpsertRequest(nonDraft)),
            0,
            FakeSyncService.INVALID_ROUTINE_STATE,
        )
        state.assertUnchanged()
        assertNull(harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R2))
    }

    @Test
    fun routineUpsert_autosavesDraftAndSavedIncludingBlankEmptySaved() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Draft", true, listOf(entryWithSet()))))

        // Draft autosave keeps draft state with new content.
        val edited = FakeSyncFixtures.routineInput(
            id = R1,
            name = "Draft Edited",
            isDraft = true,
            exercises = listOf(
                entryWithSet().copy(
                    exerciseId = BUNDLED_SQUAT,
                    sets = listOf(RoutineSetModel(RS1, 0, null, null), RoutineSetModel(RS2, 1, 5, 20.0)),
                ),
            ),
        )
        val autosave = harness.pushA(harness.routineUpsertRequest(edited))
        assertEquals(MutationStatus.ACCEPTED, autosave.results[0].status)
        var stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine
        assertTrue(stored.record.isDraft)
        assertEquals("Draft Edited", stored.record.name)
        assertEquals("Squat", stored.record.exercises[0].exerciseName)
        assertEquals(2L, stored.record.revision)

        // Promote, then autosave a blank name with empty children: valid for saved routines.
        harness.pushA(harness.routineSaveRequest(R1, "Saved Plan"))
        val blankAutosave = FakeSyncFixtures.routineInput(R1, "", false, emptyList())
        val result = harness.pushA(harness.routineUpsertRequest(blankAutosave))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine
        assertFalse(stored.record.isDraft)
        assertEquals("", stored.record.name)
        assertTrue(stored.record.exercises.isEmpty())
        assertEquals(4L, stored.record.revision)
    }

    @Test
    fun routineUpsert_rejectsDemotionAndBadTargets() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Draft", true, listOf(entryWithSet()))))

        // Autosave never flips draft state: promoting a draft through upsert
        // is rejected, and only routine.save promotes.
        var state = harness.captureState(routineKey(R1), routineKey(R2))
        assertRejectedWithCode(
            harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Promoted", false, listOf(entryWithSet())))),
            0,
            FakeSyncService.INVALID_ROUTINE_STATE,
        )
        state.assertUnchanged()

        harness.pushA(harness.routineSaveRequest(R1, "Saved Plan"))
        state = harness.captureState(routineKey(R1), routineKey(R2))
        // Saved routines cannot demote back to draft.
        assertRejectedWithCode(
            harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Draft", true, listOf(entryWithSet())))),
            0,
            FakeSyncService.INVALID_ROUTINE_STATE,
        )
        state.assertUnchanged()
        // Foreign and deleted targets reject without disclosure. (An unknown
        // ID is a valid draft creation through upsert, covered above.)
        assertRejectedWithCode(
            harness.pushB(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Theirs", true))),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        harness.pushA(harness.deleteRequest(AggregateType.ROUTINE, R1))
        val afterDelete = harness.captureState(routineKey(R1))
        assertRejectedWithCode(
            harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Revived", true))),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        afterDelete.assertUnchanged()
    }

    @Test
    fun routineUpsert_rejectsRootReuseByChildAndRegistryCollision() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Plan", true, listOf(entryWithSet()))))

        val state = harness.captureState(routineKey(R1), routineKey(R2))
        // New entry reuses the incoming root UUID.
        val rootAsEntry = FakeSyncFixtures.routineInput(
            id = R2,
            exercises = listOf(FakeSyncFixtures.routineEntry(R2, BUNDLED_PUSH_UP, 0, listOf(RoutineSetModel(RS2, 0, 5, 10.0)))),
        )
        assertRejectedWithCode(harness.pushA(harness.routineUpsertRequest(rootAsEntry)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()
        // New set reuses the incoming root UUID.
        val rootAsSet = FakeSyncFixtures.routineInput(
            id = R2,
            exercises = listOf(FakeSyncFixtures.routineEntry(RE2, BUNDLED_PUSH_UP, 0, listOf(RoutineSetModel(R2, 0, 5, 10.0)))),
        )
        assertRejectedWithCode(harness.pushA(harness.routineUpsertRequest(rootAsSet)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()
        // New entry reuses another root's child ID.
        val foreignChild = FakeSyncFixtures.routineInput(
            id = R2,
            exercises = listOf(FakeSyncFixtures.routineEntry(RE1, BUNDLED_PUSH_UP, 0, listOf(RoutineSetModel(RS3, 0, 5, 10.0)))),
        )
        assertRejectedWithCode(harness.pushA(harness.routineUpsertRequest(foreignChild)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()
        // New root UUID collides with an existing child ID.
        val rootOverChild = FakeSyncFixtures.routineInput(
            id = RE1,
            exercises = listOf(FakeSyncFixtures.routineEntry(RE2, BUNDLED_PUSH_UP, 0, listOf(RoutineSetModel(RS3, 0, 5, 10.0)))),
        )
        assertRejectedWithCode(harness.pushA(harness.routineUpsertRequest(rootOverChild)), 0, FakeSyncService.CHILD_COLLISION)
        state.assertUnchanged()
    }

    @Test
    fun routineSave_promotesWithSuppliedName() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Draft", true, listOf(entryWithSet()))))

        val result = harness.pushA(harness.routineSaveRequest(R1, "Final Name"))
        val expectedRecord = RoutineRecordModel(
            id = R1,
            name = "Final Name",
            isDraft = false,
            revision = 2L,
            exercises = listOf(
                RoutineExerciseModel(RE1, BUNDLED_PUSH_UP, 0, 30, listOf(RoutineSetModel(RS1, 0, 8, 100.0)), "Push-Up"),
            ),
        )
        val expected = harness.expectedBatch(
            1,
            listOf(
                SyncChangeModel(AggregateType.ROUTINE, R1, ChangeOperation.UPSERT, 2L, CanonicalRecord.Routine(expectedRecord)),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(expected, result.results[0].batch)
        assertEquals(CanonicalRecord.Routine(expectedRecord), harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1))

        // Re-saving an already saved routine renames it.
        val rename = harness.pushA(harness.routineSaveRequest(R1, "Renamed"))
        assertEquals(MutationStatus.ACCEPTED, rename.results[0].status)
        var stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine
        assertEquals("Renamed", stored.record.name)
        assertFalse(stored.record.isDraft)
        assertEquals(3L, stored.record.revision)
    }

    @Test
    fun routineSave_refreshesCanonicalNamesAfterCustomRename() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1, name = "Cable Fly")))
        val routine = FakeSyncFixtures.routineInput(
            id = R1,
            name = "Draft",
            isDraft = true,
            exercises = listOf(
                FakeSyncFixtures.routineEntry(
                    id = RE1,
                    exerciseId = C1,
                    position = 0,
                    sets = listOf(RoutineSetModel(RS1, 0, 8, 40.0)),
                ),
            ),
        )
        harness.pushA(harness.routineUpsertRequest(routine))
        var stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine
        assertEquals("Cable Fly", stored.record.exercises[0].exerciseName)

        // Rename the referenced custom, then promote: the saved record must
        // carry the current canonical name, not the autosave-time name.
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1, name = "Renamed Fly")))
        val result = harness.pushA(harness.routineSaveRequest(R1, "Final Name"))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.ROUTINE, R1) as CanonicalRecord.Routine
        assertFalse(stored.record.isDraft)
        assertEquals("Final Name", stored.record.name)
        assertEquals("Renamed Fly", stored.record.exercises[0].exerciseName)
        assertEquals(2L, stored.record.revision)
    }

    @Test
    fun routineSave_rejectsInvalidPromotionAndBadTargets() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R1, "Draft", true, listOf(entryWithSet()))))
        harness.pushA(harness.routineUpsertRequest(FakeSyncFixtures.routineInput(R2, "Empty Draft", true)))

        var state = harness.captureState(routineKey(R1), routineKey(R2))
        assertRejectedWithCode(harness.pushA(harness.routineSaveRequest(R1, "  ")), 0, FakeSyncService.INVALID_ROUTINE_STATE)
        state.assertUnchanged()
        assertRejectedWithCode(harness.pushA(harness.routineSaveRequest(R1, "")), 0, FakeSyncService.INVALID_ROUTINE_STATE)
        state.assertUnchanged()
        assertRejectedWithCode(harness.pushA(harness.routineSaveRequest(R2, "No Exercises")), 0, FakeSyncService.INVALID_ROUTINE_STATE)
        state.assertUnchanged()
        assertRejectedWithCode(
            harness.pushB(harness.routineSaveRequest(R1, "Theirs")),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        state.assertUnchanged()

        harness.pushA(harness.deleteRequest(AggregateType.ROUTINE, R1))
        val afterDelete = harness.captureState(routineKey(R1))
        assertRejectedWithCode(harness.pushA(harness.routineSaveRequest(R1, "Late")), 0, FakeSyncService.OWNED_ROOT_MISSING)
        afterDelete.assertUnchanged()
    }

    @Test
    fun customUpsert_createsWithResolvedRelationshipsAndExactMetadata() = runTest {
        val harness = FakeSyncHarness()
        val input = FakeSyncFixtures.customInput(
            id = C1,
            name = "Cable Fly",
            primaryMuscleGroupId = MUSCLE_CHEST,
            secondaryMuscleGroupIds = listOf(MUSCLE_BICEPS),
            equipmentId = EQUIPMENT_DUMBBELL,
            equipmentNumItems = 2,
            category = "strength",
            difficultyLevel = null,
            forceType = "pull",
            mechanics = null,
            instructions = listOf("Step one", ""),
        )
        val result = harness.pushA(harness.customUpsertRequest(input))

        val expectedRecord = CustomExerciseRecordModel(
            id = C1,
            name = "Cable Fly",
            type = "reps",
            category = "strength",
            difficultyLevel = null,
            forceType = "pull",
            mechanics = null,
            instructions = listOf("Step one", ""),
            isUserCreated = true,
            revision = 1L,
            muscleGroups = listOf(
                MuscleGroupRefModel(MUSCLE_CHEST, "Chest", "primary"),
                MuscleGroupRefModel(MUSCLE_BICEPS, "Biceps", "secondary"),
            ),
            equipment = listOf(EquipmentRefModel(EQUIPMENT_DUMBBELL, "Dumbbell", 2)),
        )
        val expected = harness.expectedBatch(
            0,
            listOf(
                SyncChangeModel(
                    AggregateType.CUSTOM_EXERCISE,
                    C1,
                    ChangeOperation.UPSERT,
                    1L,
                    CanonicalRecord.CustomExercise(expectedRecord),
                ),
            ),
        )
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        assertEquals(expected, result.results[0].batch)
        assertEquals(
            CanonicalRecord.CustomExercise(expectedRecord),
            harness.service.inspectRecord(ACCOUNT_A, AggregateType.CUSTOM_EXERCISE, C1),
        )
    }

    @Test
    fun customUpsert_updatesAndReplacesRelationshipsExactly() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1)))

        val update = FakeSyncFixtures.customInput(
            id = C1,
            name = "Renamed Fly",
            primaryMuscleGroupId = null,
            secondaryMuscleGroupIds = listOf(MUSCLE_CHEST),
            equipmentId = null,
            equipmentNumItems = null,
            category = null,
            difficultyLevel = "beginner",
            forceType = null,
            mechanics = "isolation",
            instructions = null,
        )
        val result = harness.pushA(harness.customUpsertRequest(update))
        assertEquals(MutationStatus.ACCEPTED, result.results[0].status)
        val stored = harness.service.inspectRecord(ACCOUNT_A, AggregateType.CUSTOM_EXERCISE, C1) as CanonicalRecord.CustomExercise
        val record = stored.record
        assertEquals("Renamed Fly", record.name)
        assertEquals(2L, record.revision)
        assertEquals(listOf(MuscleGroupRefModel(MUSCLE_CHEST, "Chest", "secondary")), record.muscleGroups)
        assertTrue(record.equipment.isEmpty())
        assertNull(record.category)
        assertEquals("beginner", record.difficultyLevel)
        assertNull(record.forceType)
        assertEquals("isolation", record.mechanics)
        assertNull(record.instructions)
        assertEquals("reps", record.type)
        assertTrue(record.isUserCreated)
    }

    @Test
    fun customUpsert_rejectsIneligibleTargetsAndUnknownRefs() = runTest {
        val harness = FakeSyncHarness()
        harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1)))

        var state = harness.captureState(customKey(C1))
        // A bundled catalogue UUID can never become a mutable custom exercise.
        assertRejectedWithCode(
            harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(BUNDLED_PUSH_UP))),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()
        // Another owner's custom exercise is not visible.
        assertRejectedWithCode(
            harness.pushB(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1, name = "Theirs"))),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()
        // Unknown muscle, cross-namespace muscle, and unknown equipment reject.
        assertRejectedWithCode(
            harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1, primaryMuscleGroupId = UNKNOWN_REF))),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()
        assertRejectedWithCode(
            harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1, primaryMuscleGroupId = BUNDLED_PUSH_UP))),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()
        assertRejectedWithCode(
            harness.pushA(
                harness.customUpsertRequest(
                    FakeSyncFixtures.customInput(C1, primaryMuscleGroupId = null, equipmentId = UNKNOWN_REF),
                ),
            ),
            0,
            FakeSyncService.INVALID_REFERENCE,
        )
        state.assertUnchanged()

        // A deleted custom exercise cannot be revived through upsert.
        harness.pushA(harness.deleteRequest(AggregateType.CUSTOM_EXERCISE, C1))
        val afterDelete = harness.captureState(customKey(C1))
        assertRejectedWithCode(
            harness.pushA(harness.customUpsertRequest(FakeSyncFixtures.customInput(C1))),
            0,
            FakeSyncService.OWNED_ROOT_MISSING,
        )
        afterDelete.assertUnchanged()

        // The bundled catalogue was never mutated into custom data.
        assertTrue(harness.service.isKnownReference(BUNDLED_PUSH_UP))
        assertNull(harness.service.inspectRecord(ACCOUNT_A, AggregateType.CUSTOM_EXERCISE, BUNDLED_PUSH_UP))
    }
}
