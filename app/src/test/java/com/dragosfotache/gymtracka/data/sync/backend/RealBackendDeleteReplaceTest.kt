package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.upsertChange
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteRoutineExerciseRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteRoutineSetRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteWorkoutExerciseRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteWorkoutSetRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.mutationId
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.replaceExerciseRequest
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
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused real-backend journeys for reference replacement and child deletion:
 * ID/notes/rest/set preservation on replace, consecutive renumbering after
 * exercise/set deletion, last-set retention, and emptied saved routines staying
 * saved. Exhaustive position/rejection subcases live in the inspected backend
 * suite.
 */
class RealBackendDeleteReplaceTest {
    companion object {
        const val STARTED_AT = 1_780_000_000_000L
    }

    @Test
    fun replaceExercisePreservesEntryData(): Unit = runBlocking {
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
                                notes = "keep",
                                restDurationSeconds = 75,
                                sets = listOf(workoutSet(setId, 0, 8, 55.0, true, 12)),
                            ),
                        ),
                    ),
                ),
            )

            val batch = acceptedOutcome(
                pushOne(api, replaceExerciseRequest(mutationId(2), workoutId, entryId, seeds.bundledExerciseB)),
            )

            val expected = WorkoutRecordModel(
                workoutId,
                "Morning Workout",
                "active",
                STARTED_AT,
                null,
                3L,
                listOf(
                    WorkoutExerciseModel(
                        entryId,
                        seeds.bundledExerciseB,
                        0,
                        "keep",
                        75,
                        listOf(workoutSet(setId, 0, 8, 55.0, true, 12)),
                        seeds.bundledExerciseBName,
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
    fun workoutDeleteExerciseRenumbersSurvivors(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workoutId = uid()
            val first = uid()
            val middle = uid()
            val last = uid()
            val setFirst = uid()
            val setLast = uid()
            pushOne(api, startRequest(mutationId(0), workoutId))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        workoutId,
                        exercises = listOf(
                            workoutEntry(first, seeds.bundledExerciseA, 0, sets = listOf(workoutSet(setFirst, 0, 8, 50.0, false, null))),
                            workoutEntry(middle, seeds.bundledExerciseB, 1, sets = listOf(workoutSet(uid(), 0))),
                            workoutEntry(last, seeds.bundledExerciseA, 2, sets = listOf(workoutSet(setLast, 0, 6, 40.0, false, null))),
                        ),
                    ),
                ),
            )

            val batch = acceptedOutcome(pushOne(api, deleteWorkoutExerciseRequest(mutationId(2), workoutId, middle)))

            val expected = WorkoutRecordModel(
                workoutId,
                "Morning Workout",
                "active",
                STARTED_AT,
                null,
                3L,
                listOf(
                    WorkoutExerciseModel(first, seeds.bundledExerciseA, 0, "", 30, listOf(workoutSet(setFirst, 0, 8, 50.0, false, null)), seeds.bundledExerciseAName),
                    WorkoutExerciseModel(last, seeds.bundledExerciseA, 1, "", 30, listOf(workoutSet(setLast, 0, 6, 40.0, false, null)), seeds.bundledExerciseAName),
                ),
            )
            assertEquals(
                listOf(upsertChange(AggregateType.WORKOUT, workoutId, CanonicalRecord.Workout(expected), 3L)),
                batch.changes,
            )
        }
    }

    @Test
    fun workoutDeleteSetRetainsEntryOnLastSet(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workoutId = uid()
            val entryId = uid()
            val first = uid()
            val second = uid()
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
                                sets = listOf(
                                    workoutSet(first, 0, 8, 50.0, false, null),
                                    workoutSet(second, 1, 6, 50.0, false, null),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val afterFirst = acceptedOutcome(pushOne(api, deleteWorkoutSetRequest(mutationId(2), workoutId, first)))
            // Survivor renumbers to 0 with values preserved.
            assertEquals(
                listOf(
                    upsertChange(
                        AggregateType.WORKOUT,
                        workoutId,
                        CanonicalRecord.Workout(
                            WorkoutRecordModel(
                                workoutId,
                                "Morning Workout",
                                "active",
                                STARTED_AT,
                                null,
                                3L,
                                listOf(
                                    WorkoutExerciseModel(
                                        entryId,
                                        seeds.bundledExerciseA,
                                        0,
                                        "",
                                        30,
                                        listOf(workoutSet(second, 0, 6, 50.0, false, null)),
                                        seeds.bundledExerciseAName,
                                    ),
                                ),
                            ),
                        ),
                        3L,
                    ),
                ),
                afterFirst.changes,
            )

            val afterLast = acceptedOutcome(pushOne(api, deleteWorkoutSetRequest(mutationId(3), workoutId, second)))
            // Deleting the last set leaves the exercise entry; this command is
            // not finish cleanup.
            assertEquals(
                listOf(
                    upsertChange(
                        AggregateType.WORKOUT,
                        workoutId,
                        CanonicalRecord.Workout(
                            WorkoutRecordModel(
                                workoutId,
                                "Morning Workout",
                                "active",
                                STARTED_AT,
                                null,
                                4L,
                                listOf(
                                    WorkoutExerciseModel(entryId, seeds.bundledExerciseA, 0, "", 30, emptyList(), seeds.bundledExerciseAName),
                                ),
                            ),
                        ),
                        4L,
                    ),
                ),
                afterLast.changes,
            )
        }
    }

    @Test
    fun routineDeletesRenumberAndEmptySavedStaysSaved(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val routine = uid()
            val first = uid()
            val second = uid()
            val setA = uid()
            val setB = uid()
            pushOne(
                api,
                routineUpsertRequest(
                    mutationId(0),
                    routineInput(
                        routine,
                        "Plan",
                        isDraft = true,
                        exercises = listOf(
                            routineEntry(first, seeds.bundledExerciseA, 0, sets = listOf(routineSet(setA, 0))),
                            routineEntry(second, seeds.bundledExerciseB, 1, sets = listOf(routineSet(setB, 0))),
                        ),
                    ),
                ),
            )
            pushOne(api, routineSaveRequest(mutationId(1), routine, "Plan"))

            val afterSetDelete = acceptedOutcome(pushOne(api, deleteRoutineSetRequest(mutationId(2), routine, setA)))
            // The emptied entry remains with no sets before its exercise is deleted.
            assertEquals(
                RoutineRecordModel(
                    routine,
                    "Plan",
                    false,
                    3L,
                    listOf(
                        RoutineExerciseModel(first, seeds.bundledExerciseA, 0, 30, emptyList(), seeds.bundledExerciseAName),
                        RoutineExerciseModel(second, seeds.bundledExerciseB, 1, 30, listOf(routineSet(setB, 0, 10, 60.0)), seeds.bundledExerciseBName),
                    ),
                ),
                (afterSetDelete.changes.single().record as CanonicalRecord.Routine).record,
            )

            val afterFirstDelete = acceptedOutcome(pushOne(api, deleteRoutineExerciseRequest(mutationId(3), routine, first)))
            assertEquals(
                RoutineRecordModel(
                    routine,
                    "Plan",
                    false,
                    4L,
                    listOf(
                        RoutineExerciseModel(second, seeds.bundledExerciseB, 0, 30, listOf(routineSet(setB, 0, 10, 60.0)), seeds.bundledExerciseBName),
                    ),
                ),
                (afterFirstDelete.changes.single().record as CanonicalRecord.Routine).record,
            )

            val afterLastDelete = acceptedOutcome(pushOne(api, deleteRoutineExerciseRequest(mutationId(4), routine, second)))
            assertEquals(
                RoutineRecordModel(routine, "Plan", false, 5L, emptyList()),
                (afterLastDelete.changes.single().record as CanonicalRecord.Routine).record,
            )
        }
    }
}
