package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pullAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.rejectedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.upsertChange
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.addExercisesRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.finishRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.mutationId
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.startRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.uid
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.upsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutSet
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ExerciseAddition
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused real-backend journeys for `workout.addExercises`: history copying
 * with defaults and order, the deterministic same-finish tie break, and
 * `history_shape_changed` rejection with no partial writes. Exhaustive
 * history/selection subcases live in the inspected backend suite.
 */
class RealBackendHistoryTest {
    companion object {
        const val STARTED_AT = 1_780_000_000_000L
        const val FINISHED_AT = 1_780_008_000_000L
    }

    @Test
    fun addExercisesCopiesHistoryRestAndInputsInOrder(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val history = uid()
            val historyEntry = uid()
            val historySetA = uid()
            val historySetB = uid()
            val target = uid()
            val addedA = uid()
            val addedASetA = uid()
            val addedASetB = uid()
            val addedB = uid()
            val addedBSet = uid()
            pushOne(api, startRequest(mutationId(0), history, "History", STARTED_AT))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        history,
                        "History",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                historyEntry,
                                seeds.bundledExerciseA,
                                0,
                                notes = "history notes",
                                restDurationSeconds = 75,
                                sets = listOf(
                                    workoutSet(historySetA, 0, 8, 100.0, true, 87),
                                    workoutSet(historySetB, 1, 6, 100.0, true, null),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(api, finishRequest(mutationId(2), history, FINISHED_AT))
            pushOne(api, startRequest(mutationId(3), target, "Target", STARTED_AT))

            val batch = acceptedOutcome(
                pushOne(
                    api,
                    addExercisesRequest(
                        mutationId(4),
                        target,
                        listOf(
                            ExerciseAddition(addedA, seeds.bundledExerciseA, listOf(addedASetA, addedASetB)),
                            // No history for B: one empty set with the 30-second fallback.
                            ExerciseAddition(addedB, seeds.bundledExerciseB, listOf(addedBSet)),
                        ),
                    ),
                ),
            )

            val expected = WorkoutRecordModel(
                target,
                "Target",
                "active",
                STARTED_AT,
                null,
                2L,
                listOf(
                    WorkoutExerciseModel(
                        addedA,
                        seeds.bundledExerciseA,
                        0,
                        "",
                        75,
                        listOf(
                            workoutSet(addedASetA, 0, 8, 100.0, false, null),
                            workoutSet(addedASetB, 1, 6, 100.0, false, null),
                        ),
                        seeds.bundledExerciseAName,
                    ),
                    WorkoutExerciseModel(
                        addedB,
                        seeds.bundledExerciseB,
                        1,
                        "",
                        30,
                        listOf(workoutSet(addedBSet, 0, null, null, false, null)),
                        seeds.bundledExerciseBName,
                    ),
                ),
            )
            assertEquals(
                listOf(upsertChange(AggregateType.WORKOUT, target, CanonicalRecord.Workout(expected), 2L)),
                batch.changes,
            )
        }
    }

    @Test
    fun addExercisesBreaksSameFinishTieByWorkoutId(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            // Two random IDs with a deterministic relative order: the higher
            // workout ID must win the same-finish tie. IDs are backend-global
            // reservations, so fixed constants would collide on reruns.
            val pair = listOf(uid(), uid()).sorted()
            val lower = pair[0]
            val higher = pair[1]
            val target = uid()
            val added = uid()
            val addedSet = uid()
            listOf(lower to 60.0, higher to 70.0).forEachIndexed { index, (id, weight) ->
                val entry = uid()
                val set = uid()
                pushOne(api, startRequest(mutationId(index * 3), id, "H$index", STARTED_AT))
                pushOne(
                    api,
                    upsertRequest(
                        mutationId(index * 3 + 1),
                        workoutInput(
                            id,
                            "H$index",
                            STARTED_AT,
                            listOf(
                                workoutEntry(
                                    entry,
                                    seeds.bundledExerciseA,
                                    0,
                                    sets = listOf(workoutSet(set, 0, 8, weight, true, null)),
                                ),
                            ),
                        ),
                    ),
                )
                // Identical finish timestamps force the tie break.
                pushOne(api, finishRequest(mutationId(index * 3 + 2), id, FINISHED_AT))
            }
            pushOne(api, startRequest(mutationId(6), target, "Target", STARTED_AT))

            val batch = acceptedOutcome(
                pushOne(
                    api,
                    addExercisesRequest(
                        mutationId(7),
                        target,
                        listOf(ExerciseAddition(added, seeds.bundledExerciseA, listOf(addedSet))),
                    ),
                ),
            )

            val stored = batch.changes.single().record as CanonicalRecord.Workout
            assertEquals(70.0, stored.record.exercises.single().sets.single().weightKg)
        }
    }

    @Test
    fun addExercisesRejectsChangedHistoryShapeWithoutWrites(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val history = uid()
            val historyEntry = uid()
            val historySet = uid()
            val target = uid()
            pushOne(api, startRequest(mutationId(0), history, "History", STARTED_AT))
            pushOne(
                api,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        history,
                        "History",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                historyEntry,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(historySet, 0, 8, 50.0, true, null)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(api, finishRequest(mutationId(2), history, FINISHED_AT))
            pushOne(api, startRequest(mutationId(3), target, "Target", STARTED_AT))
            val (recordsBefore, _) = snapshotAll(api)
            val (_, tailBefore) = pullAll(api, null)

            // History holds one set; the plan supplies two IDs.
            rejectedOutcome(
                pushOne(
                    api,
                    addExercisesRequest(
                        mutationId(4),
                        target,
                        listOf(ExerciseAddition(uid(), seeds.bundledExerciseA, listOf(uid(), uid()))),
                    ),
                ),
                "history_shape_changed",
            )

            val (recordsAfter, _) = snapshotAll(api)
            assertEquals(recordsBefore, recordsAfter)
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun addExercisesIgnoresForeignAndDeletedHistory(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val seeds = fx.seedIds
            val accountA = fx.newAccount()
            val accountB = fx.newAccount()
            val apiA = fx.apiFor(accountA)
            val apiB = fx.apiFor(accountB)
            // B builds finished history for the bundled exercise.
            val historyB = uid()
            val entryB = uid()
            val setB = uid()
            pushOne(apiB, startRequest(mutationId(0), historyB, "HB", STARTED_AT))
            pushOne(
                apiB,
                upsertRequest(
                    mutationId(1),
                    workoutInput(
                        historyB,
                        "HB",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                entryB,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(setB, 0, 9, 90.0, true, 10)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(apiB, finishRequest(mutationId(2), historyB, FINISHED_AT))
            val (recordsBBefore, _) = snapshotAll(apiB)
            // A builds finished history for the same exercise, then deletes it.
            val historyA = uid()
            val entryA = uid()
            val setA = uid()
            pushOne(apiA, startRequest(mutationId(3), historyA, "HA", STARTED_AT))
            pushOne(
                apiA,
                upsertRequest(
                    mutationId(4),
                    workoutInput(
                        historyA,
                        "HA",
                        STARTED_AT,
                        listOf(
                            workoutEntry(
                                entryA,
                                seeds.bundledExerciseA,
                                0,
                                sets = listOf(workoutSet(setA, 0, 7, 70.0, true, 11)),
                            ),
                        ),
                    ),
                ),
            )
            pushOne(apiA, finishRequest(mutationId(5), historyA, FINISHED_AT))
            pushOne(apiA, deleteRequest(mutationId(6), AggregateType.WORKOUT, historyA))
            val target = uid()
            val added = uid()
            val addedSet = uid()
            pushOne(apiA, startRequest(mutationId(7), target, "Target", STARTED_AT))
            val (_, tailBefore) = pullAll(apiA, null)

            val batch = acceptedOutcome(
                pushOne(
                    apiA,
                    addExercisesRequest(
                        mutationId(8),
                        target,
                        listOf(ExerciseAddition(added, seeds.bundledExerciseA, listOf(addedSet))),
                    ),
                ),
            )

            // Neither foreign nor deleted history may seed the addition: the
            // full default (one empty set, 30-second rest) applies.
            val expected = WorkoutRecordModel(
                target,
                "Target",
                "active",
                STARTED_AT,
                null,
                2L,
                listOf(
                    WorkoutExerciseModel(
                        added,
                        seeds.bundledExerciseA,
                        0,
                        "",
                        30,
                        listOf(workoutSet(addedSet, 0, null, null, false, null)),
                        seeds.bundledExerciseAName,
                    ),
                ),
            )
            assertEquals(
                listOf(upsertChange(AggregateType.WORKOUT, target, CanonicalRecord.Workout(expected), 2L)),
                batch.changes,
            )
            // B's finished history is unchanged, and A published exactly the
            // one addition batch past the pre-action tail.
            val (recordsBAfter, _) = snapshotAll(apiB)
            assertEquals(recordsBBefore, recordsBAfter)
            val (newBatches, _) = pullAll(apiA, tailBefore)
            assertEquals(1, newBatches.size)
        }
    }
}
