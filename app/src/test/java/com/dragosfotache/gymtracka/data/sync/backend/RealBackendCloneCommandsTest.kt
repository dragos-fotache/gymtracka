package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.assertOpaqueBatch
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.deleteChange
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pullAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.rejectedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.upsertChange
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.childBinding
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteWorkoutExerciseRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.finishRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.mutationId
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.performAgainRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineSaveRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineSet
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.routineUpsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.saveAsRoutineRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.startRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.startWorkoutRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.uid
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.upsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutSet
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused real-backend journeys for the clone/source commands
 * (`workout.performAgain`, `routine.startWorkout`, `workout.saveAsRoutine`):
 * exact child bindings, source preservation, completion/rest clearing, old
 * active replacement, and changed-binding failure with no partial effects.
 * Exhaustive binding/collision subcases live in the inspected backend suite
 * and are mapped in the retirement coverage document.
 */
class RealBackendCloneCommandsTest {
    companion object {
        const val STARTED_AT = 1_780_000_000_000L
        const val FINISHED_AT = 1_780_008_000_000L
    }

    @Test
    fun performAgainClonesFinishedSourceAndReplacesActive(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            performAgainJourney(fx, withOldActive = true)
        }
    }

    @Test
    fun performAgainWithoutPriorActiveEmitsSingleUpsert(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            performAgainJourney(fx, withOldActive = false)
        }
    }

    /** Shared clone journey across both prior-active states. */
    private suspend fun performAgainJourney(fx: RealBackendFixture, withOldActive: Boolean) {
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val source = uid()
            val sourceEntry = uid()
            val sourceSet = uid()
            val oldActive = uid()
            val clone = uid()
            val cloneEntry = uid()
            val cloneSet = uid()
            api.push(listOf(startRequest(mutationId(0), source, "Source", STARTED_AT)))
            api.push(
                listOf(
                    upsertRequest(
                        mutationId(1),
                        workoutInput(
                            source,
                            "Source",
                            STARTED_AT,
                            listOf(
                                workoutEntry(
                                    sourceEntry,
                                    seeds.bundledExerciseA,
                                    0,
                                    notes = "keep me",
                                    restDurationSeconds = 90,
                                    sets = listOf(workoutSet(sourceSet, 0, 8, 100.0, true, 87)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            api.push(listOf(finishRequest(mutationId(2), source, FINISHED_AT)))
            if (withOldActive) {
                api.push(listOf(startRequest(mutationId(3), oldActive, "Old Active", STARTED_AT)))
            }
            val (sourceBefore, _) = snapshotAll(api)
            val sourceRecordBefore = sourceBefore.single { it.aggregateId == source }

            val batch = acceptedOutcome(
                api.push(
                    listOf(
                        performAgainRequest(
                            mutationId(4),
                            source,
                            clone,
                            STARTED_AT + 86_400_000L,
                            listOf(childBinding(sourceEntry, cloneEntry, listOf(sourceSet to cloneSet))),
                        ),
                    ),
                ),
            )

            // Template values copy; completion and recorded rest clear. With a
            // prior active the batch is the exact ordered old-DELETE
            // (revision 2, null record) then new-UPSERT; without one it is a
            // single UPSERT.
            val expected = WorkoutRecordModel(
                clone,
                "Source",
                "active",
                STARTED_AT + 86_400_000L,
                null,
                1L,
                listOf(
                    WorkoutExerciseModel(
                        cloneEntry,
                        seeds.bundledExerciseA,
                        0,
                        "keep me",
                        90,
                        listOf(workoutSet(cloneSet, 0, 8, 100.0, false, null)),
                        seeds.bundledExerciseAName,
                    ),
                ),
            )
            if (withOldActive) {
                assertEquals(2, batch.changes.size)
                assertEquals(
                    deleteChange(
                        AggregateType.WORKOUT,
                        oldActive,
                        2L,
                    ),
                    batch.changes[0],
                )
                assertEquals(
                    upsertChange(AggregateType.WORKOUT, clone, CanonicalRecord.Workout(expected), 1L),
                    batch.changes[1],
                )
            } else {
                assertEquals(
                    listOf(upsertChange(AggregateType.WORKOUT, clone, CanonicalRecord.Workout(expected), 1L)),
                    batch.changes,
                )
            }
            assertOpaqueBatch(batch)
            // The complete finished source record is preserved byte-for-byte
            // in domain terms (IDs/order/values/completion/revision).
            val (records, _) = snapshotAll(api)
            assertEquals(sourceRecordBefore, records.single { it.aggregateId == source })
    }

    @Test
    fun startWorkoutCopiesSavedRoutineWithoutTouchingSource(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val routine = uid()
            val routineEntry = uid()
            val routineSet = uid()
            val workout = uid()
            val workoutEntry = uid()
            val workoutSet = uid()
            pushOne(
                api,
                routineUpsertRequest(
                    mutationId(0),
                    routineInput(
                        routine,
                        "Leg Day",
                        isDraft = true,
                        exercises = listOf(
                            routineEntry(
                                routineEntry,
                                seeds.bundledExerciseB,
                                0,
                                restDurationSeconds = 45,
                                sets = listOf(routineSet(routineSet, 0, 12, 30.0)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(api, routineSaveRequest(mutationId(1), routine, "Leg Day"))
            val (routineBefore, _) = snapshotAll(api)
            val routineRecordBefore = routineBefore.single { it.aggregateId == routine }

            val batch = acceptedOutcome(
                pushOne(
                    api,
                    startWorkoutRequest(
                        mutationId(2),
                        routine,
                        workout,
                        STARTED_AT,
                        listOf(childBinding(routineEntry, workoutEntry, listOf(routineSet to workoutSet))),
                    ),
                ),
            )

            val expected = WorkoutRecordModel(
                workout,
                "Leg Day",
                "active",
                STARTED_AT,
                null,
                1L,
                listOf(
                    WorkoutExerciseModel(
                        workoutEntry,
                        seeds.bundledExerciseB,
                        0,
                        "",
                        45,
                        listOf(workoutSet(workoutSet, 0, 12, 30.0, false, null)),
                        seeds.bundledExerciseBName,
                    ),
                ),
            )
            assertEquals(
                listOf(upsertChange(AggregateType.WORKOUT, workout, CanonicalRecord.Workout(expected), 1L)),
                batch.changes,
            )
            // The saved source routine is preserved completely.
            val (records, _) = snapshotAll(api)
            assertEquals(routineRecordBefore, records.single { it.aggregateId == routine })
        }
    }

    @Test
    fun saveAsRoutineCopiesFinishedSourceWithoutTouchingActive(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val source = uid()
            val sourceEntry = uid()
            val sourceSet = uid()
            val active = uid()
            val routine = uid()
            val routineEntry = uid()
            val routineSet = uid()
            pushOne(api, startRequest(mutationId(0), source, "Source", STARTED_AT))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        source,
                        "Source",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                sourceEntry,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(sourceSet, 0, 5, 60.0, true, 40)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(api, finishRequest(mutationId(2), source, FINISHED_AT))
            pushOne(api, startRequest(mutationId(3), active, "Active", STARTED_AT))
            val (preRecords, _) = snapshotAll(api)
            val sourceRecordBefore = preRecords.single { it.aggregateId == source }
            val activeRecordBefore = preRecords.single { it.aggregateId == active }

            val batch = acceptedOutcome(
                pushOne(
                    api,
                    saveAsRoutineRequest(
                        mutationId(4),
                        source,
                        routine,
                        "Saved Plan",
                        listOf(childBinding(sourceEntry, routineEntry, listOf(sourceSet to routineSet))),
                    ),
                ),
            )

            val expected = RoutineRecordModel(
                routine,
                "Saved Plan",
                false,
                1L,
                listOf(
                    RoutineExerciseModel(
                        routineEntry,
                        seeds.bundledExerciseA,
                        0,
                        30,
                        listOf(routineSet(routineSet, 0, 5, 60.0)),
                        seeds.bundledExerciseAName,
                    ),
                ),
            )
            assertEquals(
                listOf(upsertChange(AggregateType.ROUTINE, routine, CanonicalRecord.Routine(expected), 1L)),
                batch.changes,
            )
            // Finished source and current active are preserved by ID; the new
            // routine is the only addition.
            val (records, _) = snapshotAll(api)
            assertEquals(sourceRecordBefore, records.single { it.aggregateId == source })
            assertEquals(activeRecordBefore, records.single { it.aggregateId == active })
            assertEquals(3, records.size)
        }
    }

    @Test
    fun changedSourceBindingRejectsWithoutPartialEffects(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val source = uid()
            val sourceEntry = uid()
            val sourceSet = uid()
            pushOne(api, startRequest(mutationId(0), source, "Source", STARTED_AT))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        source,
                        "Source",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                sourceEntry,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(sourceSet, 0, 5, 60.0, true, null)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(api, finishRequest(mutationId(2), source, FINISHED_AT))
            val (recordsBefore, _) = snapshotAll(api)
            val (_, tailBefore) = pullAll(api, null)

            // Binding names a set the finished source does not have.
            rejectedOutcome(
                pushOne(
                    api,
                    performAgainRequest(
                        mutationId(3),
                        source,
                        uid(),
                        STARTED_AT,
                        listOf(childBinding(sourceEntry, uid(), listOf(uid() to uid()))),
                    ),
                ),
                "source_shape_changed",
            )

            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun performAgainRejectsRemovedChildTargetIds(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            // Finished clone source.
            val source = uid()
            val sourceEntry = uid()
            val sourceSet = uid()
            pushOne(api, startRequest(mutationId(0), source, "Source", STARTED_AT))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        source,
                        "Source",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                sourceEntry,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(sourceSet, 0, 5, 60.0, true, null)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(api, finishRequest(mutationId(2), source, FINISHED_AT))
            // An active workout loses an entry, reserving its removed ID.
            val donor = uid()
            val removedEntry = uid()
            pushOne(api, startRequest(mutationId(3), donor, "Donor", STARTED_AT))
            pushOne(
                api,
                upsertRequest(
                    mutationId(4),
                    workoutInput(
                        donor,
                        "Donor",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                removedEntry,
                                seeds.bundledExerciseB,
                                0,
                                sets = listOf(workoutSet(uid(), 0)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(api, deleteWorkoutExerciseRequest(mutationId(5), donor, removedEntry))
            val (recordsBefore, _) = snapshotAll(api)
            val (_, tailBefore) = pullAll(api, null)

            // A removed child ID cannot serve as a semantic clone target.
            rejectedOutcome(
                pushOne(
                    api,
                    performAgainRequest(
                        mutationId(6),
                        source,
                        uid(),
                        STARTED_AT,
                        listOf(childBinding(sourceEntry, removedEntry, listOf(sourceSet to uid()))),
                    ),
                ),
                "id_collision",
            )

            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }
}
