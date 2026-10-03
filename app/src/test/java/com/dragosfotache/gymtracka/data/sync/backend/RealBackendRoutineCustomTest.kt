package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pullAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.rejectedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.upsertChange
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customUpsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteRoutineExerciseRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.mutationId
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineSaveRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineSet
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineUpsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.startRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.uid
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.upsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutSet
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused real-backend journeys for routine draft/save semantics and custom
 * exercises: blank/empty autosave, promotion rules, relationship/metadata
 * fidelity with real catalogue names, and unknown/foreign/bundled reference
 * fencing. Exhaustive validation subcases live in the inspected backend suite.
 */
class RealBackendRoutineCustomTest {
    @Test
    fun routineDraftAutosaveAndPromotionSemantics(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val routine = uid()
            val entry = uid()
            val set = uid()

            // Creation is draft-only, including blank/empty shapes.
            val created = acceptedOutcome(
                pushOne(api, routineUpsertRequest(mutationId(0), routineInput(routine, "", true, emptyList()))),
            )
            assertEquals(
                upsertChange(
                    AggregateType.ROUTINE,
                    routine,
                    CanonicalRecord.Routine(RoutineRecordModel(routine, "", true, 1L, emptyList())),
                    1L,
                ),
                created.changes.single(),
            )

            // Autosave a populated draft, then promote with a nonblank name.
            acceptedOutcome(
                pushOne(
                    api,
                    routineUpsertRequest(
                        mutationId(1),
                        routineInput(
                            routine,
                            "",
                            true,
                            listOf(routineEntry(entry, seeds.bundledExerciseA, 0, sets = listOf(routineSet(set, 0, 8, 40.0)))),
                        ),
                    ),
                ),
            )
            val promoted = acceptedOutcome(pushOne(api, routineSaveRequest(mutationId(2), routine, "Plan")))
            assertEquals(
                RoutineRecordModel(
                    routine,
                    "Plan",
                    false,
                    3L,
                    listOf(
                        RoutineExerciseModel(entry, seeds.bundledExerciseA, 0, 30, listOf(routineSet(set, 0, 8, 40.0)), seeds.bundledExerciseAName),
                    ),
                ),
                (promoted.changes.single().record as CanonicalRecord.Routine).record,
            )

            // A saved routine accepts blank/empty autosave without demotion:
            // ordinary upsert edits it in place and it remains saved.
            val autosaved = acceptedOutcome(
                pushOne(
                    api,
                    routineUpsertRequest(mutationId(3), routineInput(routine, "", false, emptyList())),
                ),
            )
            assertEquals(
                RoutineRecordModel(routine, "", false, 4L, emptyList()),
                (autosaved.changes.single().record as CanonicalRecord.Routine).record,
            )

            // Blank-name promotion of a saved routine rejects without changes.
            val (recordsBefore, _) = snapshotAll(api)
            val (_, tailBefore) = pullAll(api, null)
            rejectedOutcome(pushOne(api, routineSaveRequest(mutationId(4), routine, "  ")), "validation_error")
            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun customUpsertPreservesRelationshipsAndMetadata(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val custom = uid()

            val batch = acceptedOutcome(
                pushOne(
                    api,
                    customUpsertRequest(
                        mutationId(0),
                        customInput(
                            custom,
                            "Cable Fly",
                            primaryMuscleGroupId = seeds.muscleA,
                            secondaryMuscleGroupIds = listOf(seeds.muscleB),
                            equipmentId = seeds.equipment,
                            equipmentNumItems = 2,
                            category = "strength",
                            difficultyLevel = "intermediate",
                            forceType = "push",
                            mechanics = "isolation",
                            instructions = listOf("Step one", "Step two"),
                        ),
                    ),
                ),
            )

            assertEquals(
                CustomExerciseRecordModel(
                    custom,
                    "Cable Fly",
                    "reps",
                    "strength",
                    "intermediate",
                    "push",
                    "isolation",
                    listOf("Step one", "Step two"),
                    true,
                    1L,
                    listOf(
                        MuscleGroupRefModel(seeds.muscleA, seeds.muscleAName, "primary"),
                        MuscleGroupRefModel(seeds.muscleB, seeds.muscleBName, "secondary"),
                    ),
                    listOf(EquipmentRefModel(seeds.equipment, seeds.equipmentName, 2)),
                ),
                batch.changes.single().record.let { (it as CanonicalRecord.CustomExercise).record },
            )
        }
    }

    @Test
    fun customUpsertRejectsUnknownAndBundledReferences(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val unknown = uid()

            // Unknown muscle reference.
            rejectedOutcome(
                pushOne(
                    api,
                    customUpsertRequest(mutationId(0), customInput(uid(), "Bad", primaryMuscleGroupId = unknown)),
                ),
                "invalid_reference",
            )
            // Bundled catalogue rows are immutable: editing one as custom rejects.
            rejectedOutcome(
                pushOne(
                    api,
                    customUpsertRequest(mutationId(1), customInput(seeds.bundledExerciseA, "Hijack", primaryMuscleGroupId = seeds.muscleA)),
                ),
                "read_only",
            )
            val (records, _) = snapshotAll(api)
            assertTrue(records.none { it.aggregateType == AggregateType.CUSTOM_EXERCISE })
        }
    }

    @Test
    fun workoutUpsertRejectsForeignCustomReference(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val accountA = fx.newAccount()
            val accountB = fx.newAccount()
            val apiA = fx.apiFor(accountA)
            val apiB = fx.apiFor(accountB)
            val seeds = fx.seedIds
            val foreignCustom = uid()
            acceptedOutcome(
                pushOne(apiA, customUpsertRequest(mutationId(0), customInput(foreignCustom, "Mine", primaryMuscleGroupId = seeds.muscleA))),
            )
            val workoutB = uid()
            pushOne(apiB, startRequest(mutationId(1), workoutB))
            val (recordsBefore, _) = snapshotAll(apiB)
            val (_, tailBefore) = pullAll(apiB, null)

            rejectedOutcome(
                pushOne(
                    apiB,
                    upsertRequest(
                        mutationId(2),
                        workoutInput(
                            workoutB,
                            exercises = listOf(
                                workoutEntry(uid(), foreignCustom, 0, sets = listOf(workoutSet(uid(), 0))),
                            ),
                        ),
                    ),
                ),
                "invalid_reference",
            )

            val (recordsAfter, _) = snapshotAll(apiB)
            assertEquals(recordsBefore, recordsAfter)
            val (newBatches, _) = pullAll(apiB, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun routineIdentityReservationsRejectReuseAndRemovedTargets(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val routine = uid()
            val entry = uid()
            val set = uid()
            pushOne(
                api,
                routineUpsertRequest(
                    mutationId(0),
                    routineInput(
                        routine,
                        "Plan",
                        isDraft = true,
                        exercises = listOf(
                            routineEntry(entry, seeds.bundledExerciseA, 0, sets = listOf(routineSet(set, 0, 8, 40.0))),
                        ),
                    ),
                ),
            )
            pushOne(api, routineSaveRequest(mutationId(1), routine, "Plan"))

            // A new routine root cannot claim another routine's live entry ID:
            // the routine family shares one identity-reservation namespace.
            var counter = 2
            var recordsBefore = snapshotAll(api).first
            var tailBefore = pullAll(api, null).second
            rejectedOutcome(
                pushOne(
                    api,
                    routineUpsertRequest(mutationId(counter++), routineInput(entry, "Hijack", true, emptyList())),
                ),
                "id_collision",
            )
            assertEquals(recordsBefore, snapshotAll(api).first)
            assertTrue(pullAll(api, tailBefore).first.isEmpty())

            // A removed child stays reserved: semantic creates require fresh IDs.
            acceptedOutcome(pushOne(api, deleteRoutineExerciseRequest(mutationId(counter++), routine, entry)))
            recordsBefore = snapshotAll(api).first
            tailBefore = pullAll(api, null).second
            rejectedOutcome(
                pushOne(
                    api,
                    routineUpsertRequest(
                        mutationId(counter++),
                        routineInput(
                            uid(),
                            "Reuse",
                            true,
                            listOf(routineEntry(entry, seeds.bundledExerciseA, 0, sets = listOf(routineSet(uid(), 0)))),
                        ),
                    ),
                ),
                "id_collision",
            )
            assertEquals(recordsBefore, snapshotAll(api).first)
            assertTrue(pullAll(api, tailBefore).first.isEmpty())
        }
    }
}
