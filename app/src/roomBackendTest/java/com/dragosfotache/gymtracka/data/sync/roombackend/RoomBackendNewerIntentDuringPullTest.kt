package com.dragosfotache.gymtracka.data.sync.roombackend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R10a matrix 3: newer local intent during an actual pull.
 *
 * A populated workout plus its custom settle into BOTH stores first. B commits
 * a remote rename/notes edit. A starts delivery with the real pull response
 * gated: the response is actually received, then held while A edits the SAME
 * already-visible workout (new local name/notes). The tail may return with
 * outstanding work flagged, and the paired publication must stay PROTECTED:
 * the remote canonical applies BUT the remote domain is not materialized, so
 * the whole visible local graph stays unchanged while canonical_json carries
 * the remote values, and the exact pending wrapper/generation survive. After A
 * sends the later complete upsert, both stores converge on the same sole-active
 * root with no dropped or merged children.
 */
@RunWith(AndroidJUnit4::class)
class RoomBackendNewerIntentDuringPullTest {

    companion object {
        const val SETTLED_NAME = "R10a Settled Intent"
        const val SETTLED_NOTES = "R10a settled notes"
        const val REMOTE_NAME = "R10a Remote Rename"
        const val REMOTE_NOTES = "R10a remote notes"
        const val LOCAL_NAME = "R10a Local Rename"
        const val LOCAL_NOTES = "R10a local notes"
        const val STARTED_AT_MILLIS = 1_790_259_300_000L
    }

    @Test
    fun newerLocalIntentDuringActualPull() = runBlocking(Dispatchers.IO) {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        try {
            val account = r10aMintAccount(transport, inputs.port)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val storeA = r10aOpenStore(context, "intent-a", account.accountId)
            val storeB = r10aOpenStore(context, "intent-b", account.accountId)
            try {
                val apiA = r10aNewApi(transport, account)
                val apiB = r10aNewApi(transport, account)
                r10aSnapshotConverge(storeA, account.accountId, apiA, R10aTestLease())
                r10aSnapshotConverge(storeB, account.accountId, apiB, R10aTestLease())

                // Settle a populated workout plus its custom into BOTH stores.
                val repoB = storeB.repository()
                val customId = repoB.createCustomExercise(
                    "R10a Intent Press", null, emptySet(), null, null, "",
                )
                val settled = repoB.startEmptyWorkout(STARTED_AT_MILLIS)
                repoB.updateWorkoutName(settled.id, SETTLED_NAME)
                repoB.addExercisesToWorkout(settled.id, listOf(customId))
                val settledEntry = checkNotNull(repoB.workoutDetails(settled.id)).exercises.single()
                val entryId = settledEntry.workoutExercise.id
                repoB.updateWorkoutExerciseNotes(entryId, SETTLED_NOTES)
                assertTrue(
                    repoB.updateWorkoutSet(
                        settledEntry.sets.single().copy(repetitions = 6, weightKg = 45.0, isCompleted = true),
                    ),
                )
                assertNotNull(r10aDeliverAndPublish(storeB, account.accountId, apiB, R10aTestLease()).second)
                assertNotNull(
                    r10aDeliverAndPublish(storeA, account.accountId, apiA, R10aTestLease()).second,
                )
                assertEquals(
                    SETTLED_NAME,
                    checkNotNull(storeA.repository().workoutDetails(settled.id)).workout.name,
                )

                // B commits a remote rename/notes edit through its engine.
                repoB.updateWorkoutName(settled.id, REMOTE_NAME)
                repoB.updateWorkoutExerciseNotes(entryId, REMOTE_NOTES)
                assertNotNull(
                    r10aDeliverAndPublish(storeB, account.accountId, apiB, R10aTestLease()).second,
                )

                // A starts delivery with the received real pull response gated.
                val gate = CompletableDeferred<Unit>()
                val gated = R10aGatePullApi(apiA, gate)
                val pushStoreA = PushStore(storeA.database, storeA.session, storeA.owner)
                val epochA = r10aDurableEpoch(storeA.database, account.accountId)
                val pending = async(Dispatchers.IO) {
                    pushStoreA.withRun(epochA) { run ->
                        SyncDeliveryLoop(pushStoreA, gated).deliver(run)
                    }
                }
                withTimeout(30_000L) { gated.pullEntered.await() }

                // Newer local intent edits the SAME already-visible workout.
                val repoA = storeA.repository()
                repoA.updateWorkoutName(settled.id, LOCAL_NAME)
                repoA.updateWorkoutExerciseNotes(entryId, LOCAL_NOTES)
                val pendingRows = storeA.database.r10aOutboxRows(account.accountId)
                assertTrue("A must hold the newer intent", pendingRows.isNotEmpty())

                // Release the held actual production pull response.
                gate.complete(Unit)
                val delivery = withTimeout(60_000L) { pending.await() }
                assertEquals(PullDeliveryStop.Tail, delivery.pull?.stop)
                assertEquals(true, delivery.hasOutstandingLocalWork)

                // Paired publication must stay PROTECTED: remote canonical applies
                // but the remote domain is NOT materialized.
                val leaseA = R10aTestLease()
                val guarded = pushStoreA.withRun(
                    r10aDurableEpoch(storeA.database, account.accountId),
                ) { run ->
                    run.publishProjection(
                        ProjectionStore(storeA.database, storeA.session, R10aRecordingBarrier(leaseA)),
                    )
                }
                assertEquals(ProjectionPublicationStatus.PROTECTED, guarded.status)
                assertEquals(1, leaseA.closeCount)
                val guardedDetails = checkNotNull(storeA.repository().workoutDetails(settled.id)) {
                    "PROTECTED must preserve the entire visible local graph"
                }
                assertEquals(LOCAL_NAME, guardedDetails.workout.name)
                assertEquals(LOCAL_NOTES, guardedDetails.exercises.single().workoutExercise.notes)
                assertTrue(
                    "PROTECTED must keep the local children",
                    guardedDetails.exercises.single().sets.single().repetitions == 6,
                )
                val canonicalObj = JSONObject(
                    checkNotNull(
                        storeA.database.r10aReadCanonicalJson(account.accountId, "workout", settled.id),
                    ) { "remote canonical must apply under PROTECTED" },
                )
                assertEquals(REMOTE_NAME, canonicalObj.getString("name"))
                val canonicalExercises = canonicalObj.getJSONArray("exercises")
                var remoteNotes: String? = null
                for (i in 0 until canonicalExercises.length()) {
                    val entry = canonicalExercises.getJSONObject(i)
                    if (entry.getString("id") == entryId) remoteNotes = entry.getString("notes")
                }
                assertEquals(REMOTE_NOTES, remoteNotes)
                assertEquals(
                    "PROTECTED must preserve the exact pending wrapper/generation",
                    pendingRows,
                    storeA.database.r10aOutboxRows(account.accountId),
                )

                // After A sends the later complete upsert, both converge.
                assertNotNull(
                    r10aDeliverAndPublish(
                        storeA, account.accountId, r10aNewApi(transport, account), R10aTestLease(),
                    ).second,
                )
                assertNotNull(r10aDeliverAndPublish(storeB, account.accountId, apiB, R10aTestLease()).second)
                for ((label, store) in listOf("A" to storeA, "B" to storeB)) {
                    val final = checkNotNull(store.repository().workoutDetails(settled.id)) {
                        "$label must converge on the sole-active root"
                    }
                    assertEquals("$label later name", LOCAL_NAME, final.workout.name)
                    assertEquals("$label later notes", LOCAL_NOTES, final.exercises.single().workoutExercise.notes)
                    assertEquals("$label entry identity", entryId, final.exercises.single().workoutExercise.id)
                    assertEquals("$label no dropped children", 1, final.exercises.single().sets.size)
                    assertEquals(
                        "$label no merged children",
                        45.0,
                        final.exercises.single().sets.single().weightKg ?: -1.0,
                        0.0,
                    )
                    assertEquals(
                        "$label canonical winner",
                        "upsert",
                        checkNotNull(store.database.r10aReadCanonical(account.accountId, "workout", settled.id))
                            .canonicalState,
                    )
                    r10aAssertOutboxEmpty(store.database, account.accountId, "$label converged")
                }
                r10aAssertQuiescentTail(storeA, account.accountId, apiA, "A quiescent")
                r10aAssertQuiescentTail(storeB, account.accountId, apiB, "B quiescent")
            } finally {
                r10aCloseAndDelete(context, storeA)
                r10aCloseAndDelete(context, storeB)
            }
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }
}
