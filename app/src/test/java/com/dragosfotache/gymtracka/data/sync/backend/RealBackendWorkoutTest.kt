package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.assertOpaqueBatch
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.deleteChange
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pullAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.rejectedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.upsertChange
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.addExercisesRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customUpsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.finishRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.mutationId
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineInput
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
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-backend coverage for workout start/upsert/finish plus push envelopes:
 * Android production durable codec -> real HTTP service -> Android canonical
 * decoder. Expected records are hand-built from the contract, never derived
 * from the response. Each test uses a fresh account, so revisions start at 1.
 */
class RealBackendWorkoutTest {
    companion object {
        const val STARTED_AT = 1_780_000_000_000L
        const val FINISHED_AT = 1_780_008_000_000L
    }

    @Test
    fun emptyPushReturnsEmptyResults(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)

            val result = api.push(emptyList())

            assertTrue(result.results.isEmpty())
        }
    }

    @Test
    fun hundredRequestPushExecutesInOrder(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)

            val ids = List(100) { uid() }
            val requests = ids.mapIndexed { index, id -> startRequest(mutationId(index), id, "W$index", STARTED_AT) }
            val result = api.push(requests)

            // Sole-active order: the first start is a lone upsert; every later
            // start carries the ordered old-DELETE/new-UPSERT replacement batch.
            assertEquals(100, result.results.size)
            result.results.forEachIndexed { index, outcome ->
                assertEquals(MutationStatus.ACCEPTED, outcome.status)
                val changes = outcome.batch!!.changes
                if (index == 0) {
                    assertEquals(1, changes.size)
                    assertEquals(ids[0], changes[0].aggregateId)
                } else {
                    assertEquals(2, changes.size)
                    assertEquals(ids[index - 1], changes[0].aggregateId)
                    assertEquals(ids[index], changes[1].aggregateId)
                }
                assertOpaqueBatch(outcome.batch!!)
            }
            // Sole-active order: only the last start survives; replaced actives are tombstoned.
            val (records, _) = snapshotAll(api)
            val workouts = records.filter { it.aggregateType == AggregateType.WORKOUT }
            assertEquals(1, workouts.size)
            assertEquals(
                CanonicalRecord.Workout(
                    WorkoutRecordModel(ids.last(), "W99", "active", STARTED_AT, null, 1L, emptyList()),
                ),
                workouts.single().record,
            )
        }
    }

    @Test
    fun startCreatesExactlyEmptyActive(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val workoutId = uid()

            val batch = acceptedOutcome(pushOne(api, startRequest(mutationId(0), workoutId, "Morning Workout", STARTED_AT)))

            val expected = WorkoutRecordModel(workoutId, "Morning Workout", "active", STARTED_AT, null, 1L, emptyList())
            assertEquals(listOf(upsertChange(AggregateType.WORKOUT, workoutId, CanonicalRecord.Workout(expected), 1L)), batch.changes)
            assertOpaqueBatch(batch)
        }
    }

    @Test
    fun secondStartReplacesActiveDeleteFirst(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val first = uid()
            val second = uid()
            pushOne(api, startRequest(mutationId(0), first, "First", STARTED_AT))

            val batch = acceptedOutcome(pushOne(api, startRequest(mutationId(1), second, "Second", STARTED_AT + 1_000L)))

            val expectedNew = WorkoutRecordModel(second, "Second", "active", STARTED_AT + 1_000L, null, 1L, emptyList())
            assertEquals(
                listOf(
                    deleteChange(AggregateType.WORKOUT, first, 2L),
                    upsertChange(AggregateType.WORKOUT, second, CanonicalRecord.Workout(expectedNew), 1L),
                ),
                batch.changes,
            )
            val (records, _) = snapshotAll(api)
            assertNull(records.firstOrNull { it.aggregateId == first })
            assertEquals(
                CanonicalRecord.Workout(expectedNew),
                records.single { it.aggregateId == second }.record,
            )
        }
    }

    @Test
    fun upsertReplacesCompleteAggregateWithResolvedNames(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workoutId = uid()
            val customId = uid()
            val entryA = uid()
            val entryB = uid()
            val setA1 = uid()
            val setA2 = uid()
            val setB1 = uid()
            pushOne(api, startRequest(mutationId(0), workoutId))
            pushOne(
                api,
                customUpsertRequest(
                    mutationId(1),
                    customInput(customId, "Cable Fly", primaryMuscleGroupId = seeds.muscleA),
                ),
            )

            val input = workoutInput(
                id = workoutId,
                name = "Evening Workout",
                startedAtMillis = STARTED_AT + 5_000L,
                exercises = listOf(
                    workoutEntry(
                        id = entryA,
                        exerciseId = seeds.bundledExerciseA,
                        position = 0,
                        notes = "tired",
                        restDurationSeconds = 90,
                        sets = listOf(
                            workoutSet(setA1, 0, 10, 60.0, true, 61),
                            workoutSet(setA2, 1, null, null, false, null),
                        ),
                    ),
                    workoutEntry(
                        id = entryB,
                        exerciseId = customId,
                        position = 1,
                        restDurationSeconds = 0,
                        sets = listOf(workoutSet(setB1, 0, null, 20.5, false, null)),
                    ),
                ),
            )
            val batch = acceptedOutcome(pushOne(api, upsertRequest(mutationId(2), input)))

            val expected = WorkoutRecordModel(
                id = workoutId,
                name = "Evening Workout",
                status = "active",
                startedAtMillis = STARTED_AT + 5_000L,
                finishedAtMillis = null,
                revision = 2L,
                exercises = listOf(
                    WorkoutExerciseModel(
                        entryA,
                        seeds.bundledExerciseA,
                        0,
                        "tired",
                        90,
                        listOf(
                            workoutSet(setA1, 0, 10, 60.0, true, 61),
                            workoutSet(setA2, 1, null, null, false, null),
                        ),
                        seeds.bundledExerciseAName,
                    ),
                    WorkoutExerciseModel(
                        entryB,
                        customId,
                        1,
                        "",
                        0,
                        listOf(workoutSet(setB1, 0, null, 20.5, false, null)),
                        "Cable Fly",
                    ),
                ),
            )
            assertEquals(
                listOf(upsertChange(AggregateType.WORKOUT, workoutId, CanonicalRecord.Workout(expected), 2L)),
                batch.changes,
            )
        }
    }

    @Test
    fun upsertRejectsFinishedHistoryWithoutEffects(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workoutId = uid()
            val entryId = uid()
            val setId = uid()
            pushOne(api, startRequest(mutationId(0), workoutId))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        workoutId,
                        exercises = listOf(
                            workoutEntry(
                                entryId,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(setId, 0, 8, 50.0, true, 30)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(api, finishRequest(mutationId(2), workoutId, FINISHED_AT))
            val (recordsBefore, _) = snapshotAll(api)
            val (_, tailBefore) = pullAll(api, null)

            rejectedOutcome(
                pushOne(
                    api,
                    upsertRequest(
                        mutationId(3),
                        workoutInput(workoutId, name = "Edited after finish"),
                    ),
                ),
                "invalid_state",
            )

            // Records (not opaque snapshot cursors) must be identical, and the
            // rejected push must publish no batch past the pre-action tail.
            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun finishRemovesUnfinishedAndEmptyExercises(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workoutId = uid()
            val entryKept = uid()
            val entryDropped = uid()
            val kept1 = uid()
            val dropped = uid()
            val kept2 = uid()
            val droppedOnly = uid()
            pushOne(api, startRequest(mutationId(0), workoutId))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        workoutId,
                        exercises = listOf(
                            workoutEntry(
                                entryKept,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(
                                    workoutSet(kept1, 0, 8, 100.0, true, 87),
                                    workoutSet(dropped, 1, 5, 100.0, false, null),
                                    workoutSet(kept2, 2, 8, 100.0, true, null),
                                ),
                            ),
                            workoutEntry(
                                entryDropped,
                                seeds.bundledExerciseB,
                                1,
                                sets = listOf(workoutSet(droppedOnly, 0, null, null, false, null)),
                            ),
                        ),
                    ),
                ),
            )

            // The supplied finish time precedes the start (clock correction):
            // client-compatibility.md forbids rejecting a finish solely for
            // that, so the valid supplied time is preserved in the record.
            val earlyFinish = STARTED_AT - 60_000L
            val batch = acceptedOutcome(pushOne(api, finishRequest(mutationId(2), workoutId, earlyFinish, knownServerRevision = 1L)))

            val expected = WorkoutRecordModel(
                workoutId,
                "Morning Workout",
                "finished",
                STARTED_AT,
                earlyFinish,
                3L,
                listOf(
                    WorkoutExerciseModel(
                        entryKept,
                        seeds.bundledExerciseA,
                        0,
                        "",
                        30,
                        listOf(
                            workoutSet(kept1, 0, 8, 100.0, true, 87),
                            workoutSet(kept2, 1, 8, 100.0, true, null),
                        ),
                        seeds.bundledExerciseAName,
                    ),
                ),
            )
            assertEquals(
                listOf(upsertChange(AggregateType.WORKOUT, workoutId, CanonicalRecord.Workout(expected), 3L)),
                batch.changes,
            )
        }
    }

    @Test
    fun finishRejectsWithoutCompletedSets(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workoutId = uid()
            val entryId = uid()
            val setId = uid()
            pushOne(api, startRequest(mutationId(0), workoutId))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        workoutId,
                        exercises = listOf(
                            workoutEntry(
                                entryId,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(setId, 0, 8, 50.0, false, null)),
                            ),
                        ),
                    ),
                ),
            )
            val expectedStored = WorkoutRecordModel(
                workoutId,
                "Morning Workout",
                "active",
                STARTED_AT,
                null,
                2L,
                listOf(
                    WorkoutExerciseModel(
                        entryId,
                        seeds.bundledExerciseA,
                        0,
                        "",
                        30,
                        listOf(workoutSet(setId, 0, 8, 50.0, false, null)),
                        seeds.bundledExerciseAName,
                    ),
                ),
            )
            val (recordsBefore, _) = snapshotAll(api)
            assertEquals(
                CanonicalRecord.Workout(expectedStored),
                recordsBefore.single { it.aggregateId == workoutId }.record,
            )
            val (_, tailBefore) = pullAll(api, null)

            rejectedOutcome(pushOne(api, finishRequest(mutationId(2), workoutId, FINISHED_AT)), "validation_error")

            // Complete previous domain record (IDs/values/positions/revision)
            // must be intact, and no batch may follow the pre-action tail.
            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            val (tailBatches, _) = pullAll(api, tailBefore)
            assertTrue("Rejected finish must publish no batch", tailBatches.isEmpty())
        }
    }

    @Test
    fun startRejectsIdCollisionWithoutEffects(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val workoutId = uid()
            pushOne(api, startRequest(mutationId(0), workoutId))
            val (recordsBefore, _) = snapshotAll(api)
            val (_, tailBefore) = pullAll(api, null)

            // Reusing a live root ID for a new start collides instead of replacing.
            rejectedOutcome(pushOne(api, startRequest(mutationId(1), workoutId, "Collision", STARTED_AT)), "id_collision")

            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun customRenameRefreshesWorkoutReferenceName(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workoutId = uid()
            val customId = uid()
            val entryId = uid()
            val setId = uid()
            pushOne(api, startRequest(mutationId(0), workoutId))
            pushOne(
                api,
                customUpsertRequest(
                    mutationId(1),
                    customInput(customId, "Old Name", primaryMuscleGroupId = seeds.muscleA),
                ),
            )
            pushOne(
                api,
                upsertRequest(
                    mutationId(2),
                    workoutInput(
                        workoutId,
                        exercises = listOf(
                            workoutEntry(
                                entryId,
                                customId,
                                0,
                                sets = listOf(workoutSet(setId, 0, 8, 40.0, true, 20)),
                            ),
                        ),
                    ),
                ),
            )

            val renameBatch = acceptedOutcome(
                pushOne(
                    api,
                    customUpsertRequest(
                        mutationId(3),
                        customInput(customId, "New Name", primaryMuscleGroupId = seeds.muscleA),
                    ),
                ),
            )
            val renamed = renameBatch.changes.single().record as CanonicalRecord.CustomExercise
            assertEquals("New Name", renamed.record.name)

            // The referencing workout keeps its domain data and revision; only
            // the derived display name follows the rename.
            val (records, _) = snapshotAll(api)
            val stored = records.single { it.aggregateId == workoutId }.record as CanonicalRecord.Workout
            assertEquals(2L, stored.record.revision)
            val storedEntry = stored.record.exercises.single()
            assertEquals(entryId, storedEntry.id)
            assertEquals(customId, storedEntry.exerciseId)
            assertEquals(0, storedEntry.position)
            assertEquals(listOf(workoutSet(setId, 0, 8, 40.0, true, 20)), storedEntry.sets)
            assertEquals("New Name", storedEntry.exerciseName)
        }
    }

    @Test
    fun finishRejectsIneligibleTargetsWithoutEffects(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val seeds = fx.seedIds
            val accountA = fx.newAccount()
            val accountB = fx.newAccount()
            val apiA = fx.apiFor(accountA)
            val apiB = fx.apiFor(accountB)
            // Finished target with a completed set.
            val finished = uid()
            val finishedEntry = uid()
            val finishedSet = uid()
            pushOne(apiA, startRequest(mutationId(0), finished, "F", STARTED_AT))
            pushOne(
                apiA,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        finished,
                        "F",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                finishedEntry,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(finishedSet, 0, 8, 50.0, true, null)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(apiA, finishRequest(mutationId(2), finished, FINISHED_AT))
            // Deleted target: started, then tombstoned.
            val deleted = uid()
            pushOne(apiA, startRequest(mutationId(3), deleted, "D", STARTED_AT))
            pushOne(apiA, deleteRequest(mutationId(4), AggregateType.WORKOUT, deleted))
            // Foreign target owned by B.
            val foreign = uid()
            pushOne(apiB, startRequest(mutationId(5), foreign, "FB", STARTED_AT))
            val missing = uid()

            // Already-finished state is ineligible; missing/deleted/foreign
            // targets are lookup failures. None may change records or logs.
            val cases = listOf(
                Triple(apiA, finished, "invalid_state"),
                Triple(apiA, deleted, "not_found"),
                Triple(apiA, missing, "not_found"),
                Triple(apiA, foreign, "not_found"),
            )
            var counter = 6
            for ((api, target, code) in cases) {
                val (recordsBefore, _) = snapshotAll(api)
                val (_, tailBefore) = pullAll(api, null)
                rejectedOutcome(pushOne(api, finishRequest(mutationId(counter++), target, FINISHED_AT)), code)
                val (recordsAfter, _) = snapshotAll(api)
                assertEquals("Target $target must leave records unchanged", recordsBefore, recordsAfter)
                val (newBatches, _) = pullAll(api, tailBefore)
                assertTrue("Target $target must publish no batch", newBatches.isEmpty())
            }
            // B's foreign root is untouched by A's attempts.
            val (recordsB, _) = snapshotAll(apiB)
            assertEquals(foreign, recordsB.single().aggregateId)
        }
    }
}
