package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pullAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.rejectedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.upsertChange
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customUpsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.importRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.mutationId
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineSet
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.startRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.uid
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutImportRecord
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutSet
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused real-backend journeys for `aggregate.import` and `aggregate.delete`:
 * creation of every root type with snapshot fidelity, valid finished imports,
 * saved blank routine imports, collision/tombstone fencing, dependency order,
 * and referenced-custom delete rejection followed by success.
 */
class RealBackendImportTest {
    companion object {
        const val STARTED_AT = 1_780_000_000_000L
        const val FINISHED_AT = 1_780_008_000_000L
    }

    @Test
    fun importCreatesEveryRootTypeWithSnapshotFidelity(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workout = uid()
            val entry = uid()
            val set = uid()
            val routine = uid()
            val custom = uid()

            // Custom import is a creation operation with full relationship fidelity.
            val customBatch = acceptedOutcome(
                pushOne(
                    api,
                    importRequest(
                        mutationId(0),
                        ImportRecord.CustomExercise(
                            customInput(
                                custom,
                                "Cable Fly",
                                primaryMuscleGroupId = seeds.muscleA,
                                equipmentId = seeds.equipment,
                                equipmentNumItems = 2,
                            ),
                        ),
                    ),
                ),
            )
            val expectedCustom = CustomExerciseRecordModel(
                custom,
                "Cable Fly",
                "reps",
                null,
                null,
                null,
                null,
                null,
                true,
                1L,
                listOf(MuscleGroupRefModel(seeds.muscleA, seeds.muscleAName, "primary")),
                listOf(EquipmentRefModel(seeds.equipment, seeds.equipmentName, 2)),
            )
            assertEquals(
                listOf(
                    upsertChange(
                        AggregateType.CUSTOM_EXERCISE,
                        custom,
                        CanonicalRecord.CustomExercise(expectedCustom),
                        1L,
                    ),
                ),
                customBatch.changes,
            )

            val workoutBatch = acceptedOutcome(
                pushOne(
                    api,
                    importRequest(
                        mutationId(1),
                        workoutImportRecord(
                            workout,
                            "Imported",
                            "finished",
                            STARTED_AT,
                            FINISHED_AT,
                            listOf(
                                workoutEntry(
                                    entry,
                                    custom,
                                    0,
                                    notes = "n",
                                    restDurationSeconds = 45,
                                    sets = listOf(workoutSet(set, 0, 8, 60.0, true, 33)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val expectedWorkout = WorkoutRecordModel(
                workout,
                "Imported",
                "finished",
                STARTED_AT,
                FINISHED_AT,
                1L,
                listOf(
                    WorkoutExerciseModel(entry, custom, 0, "n", 45, listOf(workoutSet(set, 0, 8, 60.0, true, 33)), "Cable Fly"),
                ),
            )
            assertEquals(
                listOf(upsertChange(AggregateType.WORKOUT, workout, CanonicalRecord.Workout(expectedWorkout), 1L)),
                workoutBatch.changes,
            )

            // Saved blank routine import preserves legitimate blank/empty state.
            val routineBatch = acceptedOutcome(
                pushOne(api, importRequest(mutationId(2), ImportRecord.Routine(routineInput(routine, "", false, emptyList())))),
            )
            val expectedRoutine = RoutineRecordModel(routine, "", false, 1L, emptyList())
            assertEquals(
                listOf(upsertChange(AggregateType.ROUTINE, routine, CanonicalRecord.Routine(expectedRoutine), 1L)),
                routineBatch.changes,
            )

            val (records, _) = snapshotAll(api)
            assertEquals(3, records.size)
        }
    }

    @Test
    fun importRejectsCollisionsAndMissingDependencies(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workout = uid()
            val missingCustom = uid()
            // A live active workout exists before the rejected import.
            val survivor = uid()
            pushOne(api, startRequest(mutationId(0), survivor, "Survivor", STARTED_AT))
            val (recordsBefore, _) = snapshotAll(api)
            val (_, tailBefore) = pullAll(api, null)

            // Referenced custom must exist before the referencing import.
            rejectedOutcome(
                pushOne(
                    api,
                    importRequest(
                        mutationId(1),
                        workoutImportRecord(
                            workout,
                            "Bad",
                            "active",
                            STARTED_AT,
                            null,
                            listOf(workoutEntry(uid(), missingCustom, 0, sets = listOf(workoutSet(uid(), 0)))),
                        ),
                    ),
                ),
                "invalid_reference",
            )
            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            // The pre-existing active survives the rejection untouched.
            assertEquals(
                survivor,
                recordsAfter.filter { it.aggregateType == AggregateType.WORKOUT }.single().aggregateId,
            )
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun importNeverOverwritesLiveOrTombstonedRoots(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val custom = uid()
            acceptedOutcome(
                pushOne(api, customUpsertRequest(mutationId(0), customInput(custom, "Mine", primaryMuscleGroupId = seeds.muscleA))),
            )
            acceptedOutcome(pushOne(api, deleteRequest(mutationId(1), AggregateType.CUSTOM_EXERCISE, custom)))
            // A live active workout exists before the colliding active import.
            val liveActive = uid()
            pushOne(api, startRequest(mutationId(2), liveActive, "Live", STARTED_AT))
            val (recordsBefore, _) = snapshotAll(api)
            val (_, tailBefore) = pullAll(api, null)

            // Live-ID collision and known-tombstone reimport both refuse to
            // overwrite cloud data.
            rejectedOutcome(
                pushOne(api, importRequest(mutationId(3), ImportRecord.CustomExercise(customInput(custom, "Other", primaryMuscleGroupId = seeds.muscleA)))),
                "id_collision",
            )
            // A colliding ACTIVE import leaves the prior active untouched.
            rejectedOutcome(
                pushOne(
                    api,
                    importRequest(mutationId(4), workoutImportRecord(liveActive, "Hijack", "active", STARTED_AT, null, emptyList())),
                ),
                "id_collision",
            )

            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            assertEquals(
                liveActive,
                recordsAfter.filter { it.aggregateType == AggregateType.WORKOUT }.single().aggregateId,
            )
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun deleteRejectsReferencedCustomThenSucceeds(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val custom = uid()
            val routine = uid()
            val entry = uid()
            val set = uid()
            acceptedOutcome(
                pushOne(api, customUpsertRequest(mutationId(0), customInput(custom, "Used", primaryMuscleGroupId = seeds.muscleA))),
            )
            acceptedOutcome(
                pushOne(
                    api,
                    importRequest(
                        mutationId(1),
                        ImportRecord.Routine(
                            routineInput(
                                routine,
                                "Refs",
                                false,
                                listOf(
                                    routineEntry(
                                        entry,
                                        custom,
                                        0,
                                        sets = listOf(routineSet(set, 0)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            rejectedOutcome(
                pushOne(api, deleteRequest(mutationId(2), AggregateType.CUSTOM_EXERCISE, custom)),
                "exercise_in_use",
            )

            acceptedOutcome(pushOne(api, deleteRequest(mutationId(3), AggregateType.ROUTINE, routine)))
            val deleteBatch = acceptedOutcome(pushOne(api, deleteRequest(mutationId(4), AggregateType.CUSTOM_EXERCISE, custom)))
            assertEquals(custom, deleteBatch.changes.single().aggregateId)
            val (records, _) = snapshotAll(api)
            assertTrue(records.isEmpty())
        }
    }
}
