package com.dragosfotache.gymtracka.data.sync.roombackend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RoomBackend matrix 2: actual lost response, exact replay and active replacement.
 *
 * A custom plus a populated ACTIVE workout fully settle on both stores first.
 * The relay then withholds A's single active autosave response (rename, no
 * queued successor) after the real server commit; the loop reports Deferred and
 * A keeps its exact frozen head across a file reopen. B pulls the actual
 * committed named populated ACTIVE root and starts a replacement WITHOUT an
 * explicit delete first: the sole-active start removes the old root in its
 * atomic batch. A's unchanged retry observes the original duplicate batch
 * (recorded via a delegating wrapper, never synthesized), then catches up the
 * complete delete plus new-active batch. Exactly one current active survives
 * with no old children/ghost root, the delete revision is retained, nothing
 * reexecutes and the newer winner is not lost. A final explicit ordinary
 * deletion (with descendants) leaves an empty visible root set on both stores.
 */
@RunWith(AndroidJUnit4::class)
class RoomBackendLostResponseTest {

    companion object {
        const val SETTLED_NAME = "RoomBackend Settled"
        const val AUTOSAVE_NAME = "RoomBackend Autosaved"
        const val REPLACEMENT_NAME = "RoomBackend Replacement"
        const val STARTED_AT_MILLIS = 1_790_259_200_000L
    }

    @Test
    fun lostResponseExactReplayActiveReplacement() = runBlocking(Dispatchers.IO) {
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
        try {
            val account = roomBackendMintAccount(transport, inputs.port)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var storeA = roomBackendOpenStore(context, "loss-a", account.accountId)
            var storeB = roomBackendOpenStore(context, "loss-b", account.accountId)
            try {
                val apiA = roomBackendNewApi(transport, account)
                val apiB = roomBackendNewApi(transport, account)
                roomBackendSnapshotConverge(storeA, account.accountId, apiA, RoomBackendTestLease())
                roomBackendSnapshotConverge(storeB, account.accountId, apiB, RoomBackendTestLease())

                // Settle a custom plus a populated ACTIVE workout on both stores.
                val repoA = storeA.repository()
                val customId = repoA.createCustomExercise(
                    "RoomBackend Loss Press", null, emptySet(), null, null, "",
                )
                val settled = repoA.startEmptyWorkout(STARTED_AT_MILLIS)
                repoA.updateWorkoutName(settled.id, SETTLED_NAME)
                repoA.addExercisesToWorkout(settled.id, listOf(customId))
                val settledEntry = checkNotNull(repoA.workoutDetails(settled.id)).exercises.single()
                assertTrue(
                    repoA.updateWorkoutSet(
                        settledEntry.sets.single().copy(repetitions = 8, weightKg = 55.0, isCompleted = true),
                    ),
                )
                val clock = RoomBackendMutableNow(System.currentTimeMillis())
                val (_, settleA) = roomBackendDeliverAndPublish(
                    storeA, account.accountId, apiA, RoomBackendTestLease(), nowMillis = clock::get,
                )
                assertNotNull(settleA)
                val (_, settleB) =
                    roomBackendDeliverAndPublish(storeB, account.accountId, apiB, RoomBackendTestLease())
                assertNotNull(settleB)
                assertEquals(
                    SETTLED_NAME,
                    checkNotNull(storeB.repository().workoutDetails(settled.id)).workout.name,
                )

                // Lose ONE active autosave with no queued successor.
                repoA.updateWorkoutName(settled.id, AUTOSAVE_NAME)
                val frozen = storeA.database.roomBackendOutboxRows(account.accountId)
                assertTrue("A must hold exactly the autosave head", frozen.size == 1)
                val frozenMutationId = frozen.single().mutationId
                roomBackendLoseNextPush(transport, inputs.port, account)
                val (lostDelivery, lostPublication) = roomBackendDeliverAndPublish(
                    storeA, account.accountId, apiA, RoomBackendTestLease(), nowMillis = clock::get,
                )
                assertTrue(
                    "lost response must defer, got [${lostDelivery.pushStop}]",
                    lostDelivery.pushStop is PushDeliveryStop.Deferred,
                )
                assertNull(lostDelivery.pullStop)
                assertNull(lostDelivery.hasOutstandingLocalWork)
                assertNull("no publication without an eligible tail", lostPublication)
                val retainedLoss = storeA.database.roomBackendOutboxRows(account.accountId)
                roomBackendAssertOutboxFrozen(frozen, retainedLoss, "A lost response")

                // Reopen with a fresh owner: the post-loss snapshot is exact.
                storeA = roomBackendReopen(context, storeA)
                assertEquals(retainedLoss, storeA.database.roomBackendOutboxRows(account.accountId))
                clock.advanceBeyond(storeA.database.roomBackendOutboxRetryAfterMax(account.accountId))

                // B pulls the actual committed named populated ACTIVE root, then
                // starts a replacement with no explicit delete first.
                val (discovery, discoveredPublication) =
                    roomBackendDeliverAndPublish(storeB, account.accountId, apiB, RoomBackendTestLease())
                assertEquals(PushDeliveryStop.Empty, discovery.pushStop)
                assertEquals(PullDeliveryStop.Tail, discovery.pullStop)
                assertNotNull(discoveredPublication)
                val discovered = checkNotNull(storeB.repository().workoutDetails(settled.id)) {
                    "B must pull the committed autosave"
                }
                assertTrue("B must see the ACTIVE root", discovered.workout.status == "active")
                assertEquals(AUTOSAVE_NAME, discovered.workout.name)
                assertEquals(1, discovered.exercises.single().sets.size)
                val settledRevisionB = checkNotNull(
                    storeB.database.roomBackendReadCanonical(account.accountId, "workout", settled.id),
                ).serverRevision
                val repoB = storeB.repository()
                val replacement = repoB.startEmptyWorkout(STARTED_AT_MILLIS + 1_000L)
                repoB.updateWorkoutName(replacement.id, REPLACEMENT_NAME)
                repoB.addExercisesToWorkout(replacement.id, listOf(customId))
                val replacementEntry =
                    checkNotNull(repoB.workoutDetails(replacement.id)).exercises.single()
                assertTrue(
                    repoB.updateWorkoutSet(
                        replacementEntry.sets.single().copy(repetitions = 5, weightKg = 40.0, isCompleted = true),
                    ),
                )
                val replaceOutcome =
                    roomBackendDeliverAndPublish(storeB, account.accountId, apiB, RoomBackendTestLease())
                assertNotNull("B replacement requires an eligible tail", replaceOutcome.second)
                assertNull(
                    "sole-active start removes the old root atomically",
                    storeB.repository().workoutDetails(settled.id),
                )
                assertEquals(
                    listOf(replacement.id),
                    listOfNotNull(storeB.repository().activeWorkout()).map { it.id },
                )

                // A's unchanged retry observes the original duplicate batch, then
                // catches up the complete delete plus new-active batch.
                val recorder = RoomBackendRecordingPushApi(roomBackendNewApi(transport, account))
                val retryOutcome = roomBackendDeliverAndPublish(
                    storeA, account.accountId, recorder, RoomBackendTestLease(), nowMillis = clock::get,
                )
                assertNotNull("A retry requires an eligible tail", retryOutcome.second)
                val duplicate = recorder.pushResults.flatMap { it.results }
                    .singleOrNull { it.mutationId == frozenMutationId }
                assertNotNull("A must observe the original duplicate outcome", duplicate)
                val confirmed = checkNotNull(duplicate)
                assertEquals(MutationStatus.DUPLICATE, confirmed.status)
                val duplicateBatch = checkNotNull(confirmed.batch) {
                    "duplicate carries the original batch, never a reexecution"
                }
                val duplicateUpsert = duplicateBatch.changes
                    .singleOrNull { it.aggregateId == settled.id && it.operation == ChangeOperation.UPSERT }
                assertNotNull("duplicate batch targets the autosave upsert", duplicateUpsert)
                assertEquals(
                    "duplicate upsert keeps the settled revision",
                    settledRevisionB,
                    checkNotNull(duplicateUpsert).revision,
                )
                roomBackendAssertOutboxEmpty(storeA.database, account.accountId, "A after retry")
                val repoA2 = storeA.repository()
                assertNull("old root must not survive as a ghost", repoA2.workoutDetails(settled.id))
                assertTrue(
                    "old children must cascade",
                    storeA.database.roomBackendCount(
                        "SELECT COUNT(*) FROM workout_exercises WHERE workout_id = ?",
                        arrayOf(settled.id),
                    ) == 0,
                )
                val replacementDetails = checkNotNull(repoA2.workoutDetails(replacement.id)) {
                    "A must catch up the newer winner"
                }
                assertEquals(REPLACEMENT_NAME, replacementDetails.workout.name)
                assertTrue(
                    "A must keep the replacement children",
                    replacementDetails.exercises.single().sets.size == 1,
                )
                val deleteCanonical = checkNotNull(
                    storeA.database.roomBackendReadCanonical(account.accountId, "workout", settled.id),
                ) { "A must retain the delete revision" }
                assertEquals("delete", deleteCanonical.canonicalState)
                assertTrue((deleteCanonical.serverRevision ?: 0L) > 0L)
                val replacementRevisionA = checkNotNull(
                    storeA.database.roomBackendReadCanonical(account.accountId, "workout", replacement.id),
                ).serverRevision
                val replacementRevisionB = checkNotNull(
                    storeB.database.roomBackendReadCanonical(account.accountId, "workout", replacement.id),
                ).serverRevision
                assertEquals(replacementRevisionB, replacementRevisionA)
                assertEquals(
                    listOf(replacement.id),
                    listOfNotNull(repoA2.activeWorkout()).map { it.id },
                )
                assertEquals(
                    "final DELETE revision agrees",
                    checkNotNull(storeB.database.roomBackendReadCanonical(account.accountId, "workout", settled.id))
                        .serverRevision,
                    checkNotNull(storeA.database.roomBackendReadCanonical(account.accountId, "workout", settled.id))
                        .serverRevision,
                )

                // Explicit ordinary deletions with descendants, plus the freed custom:
                // a fully empty root graph on both stores.
                repoA2.deleteWorkout(replacement.id)
                assertNotNull(
                    roomBackendDeliverAndPublish(
                        storeA, account.accountId, roomBackendNewApi(transport, account), RoomBackendTestLease(),
                    ).second,
                )
                roomBackendDeliverAndPublish(storeB, account.accountId, apiB, RoomBackendTestLease())
                assertTrue("freed custom deletes", repoA2.deleteCustomExercise(customId))
                assertNotNull(
                    roomBackendDeliverAndPublish(
                        storeA, account.accountId, roomBackendNewApi(transport, account), RoomBackendTestLease(),
                    ).second,
                )
                roomBackendDeliverAndPublish(storeB, account.accountId, apiB, RoomBackendTestLease())
                for ((label, store) in listOf("A" to storeA, "B" to storeB)) {
                    assertNull("$label no active workout", store.repository().activeWorkout())
                    assertNull("$label replacement removed", store.repository().workoutDetails(replacement.id))
                    assertTrue(
                        "$label workout descendants gone",
                        store.database.roomBackendCount(
                            "SELECT COUNT(*) FROM workout_exercises WHERE workout_id = ?",
                            arrayOf(replacement.id),
                        ) == 0,
                    )
                    assertTrue(
                        "$label no live workouts",
                        store.database.roomBackendCount(
                            "SELECT COUNT(*) FROM workouts WHERE deleted_at_millis IS NULL",
                            emptyArray(),
                        ) == 0,
                    )
                    assertTrue(
                        "$label no live routines",
                        store.database.roomBackendCount(
                            "SELECT COUNT(*) FROM routines WHERE deleted_at_millis IS NULL",
                            emptyArray(),
                        ) == 0,
                    )
                    assertTrue(
                        "$label no live custom exercises",
                        store.database.roomBackendCount(
                            "SELECT COUNT(*) FROM exercises " +
                                "WHERE is_user_created = 1 AND deleted_at_millis IS NULL",
                            emptyArray(),
                        ) == 0,
                    )
                    assertEquals(
                        "$label custom deleted",
                        "delete",
                        checkNotNull(
                            store.database.roomBackendReadCanonical(account.accountId, "customExercise", customId),
                        ).canonicalState,
                    )
                    roomBackendAssertOutboxEmpty(store.database, account.accountId, "$label deleted")
                }
                roomBackendAssertQuiescentTail(storeA, account.accountId, apiA, "A quiescent")
                roomBackendAssertQuiescentTail(storeB, account.accountId, apiB, "B quiescent")
            } finally {
                roomBackendCloseAndDelete(context, storeA)
                roomBackendCloseAndDelete(context, storeB)
            }
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }
}
