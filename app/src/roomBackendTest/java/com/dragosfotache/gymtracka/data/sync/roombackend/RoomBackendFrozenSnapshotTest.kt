package com.dragosfotache.gymtracka.data.sync.roombackend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RoomBackend matrix 4: frozen snapshot plus post-baseline catchup.
 *
 * B downloads a multi-root baseline with page limit 1 and maxPages 1, so the
 * first page stops without the limit alone ending the invocation. A peer
 * updates the EXISTING workout name after the first page; B continues the same
 * frozen view/limit/baseline on the same driver/run, completes atomically, and
 * an actual pull then catches up the post-baseline rename. After complete but
 * before catch-up, canonical_json still carries the original frozen name while
 * the domain shows the old graph; only the pull delivers the final new name.
 * Post-baseline changes are neither lost nor guessed from incremental absence.
 */
@RunWith(AndroidJUnit4::class)
class RoomBackendFrozenSnapshotTest {

    companion object {
        const val FROZEN_WORKOUT = "RoomBackend Frozen"
        const val FROZEN_ROUTINE = "RoomBackend Frozen Routine"
        const val RENAMED_WORKOUT = "RoomBackend Renamed After Page"
        const val STARTED_AT_MILLIS = 1_790_259_400_000L
    }

    @Test
    fun frozenSnapshotPostBaselineCatchup() = runBlocking(Dispatchers.IO) {
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
        try {
            val account = roomBackendMintAccount(transport, inputs.port)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val storeA = roomBackendOpenStore(context, "frozen-a", account.accountId)
            val storeB = roomBackendOpenStore(context, "frozen-b", account.accountId)
            try {
                val apiA = roomBackendNewApi(transport, account)
                val apiB = roomBackendNewApi(transport, account)
                roomBackendSnapshotConverge(storeA, account.accountId, apiA, RoomBackendTestLease())
                roomBackendSnapshotConverge(storeB, account.accountId, apiB, RoomBackendTestLease())

                // A commits several real roots.
                val repoA = storeA.repository()
                val customId = repoA.createCustomExercise(
                    "RoomBackend Frozen Press", null, emptySet(), null, null, "",
                )
                val routineId = repoA.createRoutineDraft().id
                repoA.updateRoutineName(routineId, FROZEN_ROUTINE)
                repoA.addExercisesToRoutine(routineId, listOf(customId))
                val frozen = repoA.startEmptyWorkout(STARTED_AT_MILLIS)
                repoA.updateWorkoutName(frozen.id, FROZEN_WORKOUT)
                repoA.addExercisesToWorkout(frozen.id, listOf(customId))
                val frozenEntry = checkNotNull(repoA.workoutDetails(frozen.id)).exercises.single()
                assertTrue(
                    repoA.updateWorkoutSet(
                        frozenEntry.sets.single().copy(repetitions = 7, weightKg = 52.5, isCompleted = true),
                    ),
                )
                assertNotNull(
                    roomBackendDeliverAndPublish(storeA, account.accountId, apiA, RoomBackendTestLease()).second,
                )

                // B downloads the frozen baseline on one driver/run. A sibling
                // coroutine commits the peer rename only after the first page
                // stops; B holds the same run while the peer commits outside it.
                val pushStoreB = PushStore(storeB.database, storeB.session, storeB.owner)
                val firstPage = CompletableDeferred<Unit>()
                val peerCommitted = CompletableDeferred<Unit>()
                val snapshotJob = async(Dispatchers.IO) {
                    pushStoreB.withRun(roomBackendDurableEpoch(storeB.database, account.accountId)) { run ->
                        val snapshot = SnapshotDeliveryLoop(pushStoreB, apiB)
                        val start = snapshot.start(
                            run, UUID.randomUUID().toString(), maxPages = 1, pageLimit = 1,
                        )
                        assertEquals(PushDeliveryStop.Empty, start.push.stop)
                        var download = checkNotNull(start.download) { "B requires a present download" }
                        assertEquals(
                            "first page must stop without ending the invocation",
                            SnapshotDownloadStop.LimitReached,
                            download.stop,
                        )
                        assertNull(
                            "staged progress must not be visible",
                            storeB.repository().workoutDetails(frozen.id),
                        )
                        assertNull(
                            "staged progress must not be visible",
                            storeB.repository().routineDetails(routineId),
                        )
                        firstPage.complete(Unit)
                        withTimeout(60_000L) { peerCommitted.await() }
                        while (download.stop == SnapshotDownloadStop.LimitReached) {
                            download = snapshot.continueDownload(run, download, maxPages = 100)
                        }
                        assertEquals(SnapshotDownloadStop.Downloaded, download.stop)
                        snapshot.complete(run, download)
                    }
                }
                val peerJob = async(Dispatchers.IO) {
                    withTimeout(60_000L) { firstPage.await() }
                    // The peer updates the EXISTING workout name after the first page.
                    repoA.updateWorkoutName(frozen.id, RENAMED_WORKOUT)
                    assertNotNull(
                        roomBackendDeliverAndPublish(storeA, account.accountId, apiA, RoomBackendTestLease()).second,
                    )
                    peerCommitted.complete(Unit)
                }
                withTimeout(120_000L) { peerJob.await() }
                withTimeout(120_000L) { snapshotJob.await() }

                // After complete but before catch-up: canonical holds the frozen
                // original while the domain still shows the old graph.
                val frozenObj = JSONObject(
                    checkNotNull(
                        storeB.database.roomBackendReadCanonicalJson(account.accountId, "workout", frozen.id),
                    ) { "B must hold the frozen canonical" },
                )
                assertEquals(FROZEN_WORKOUT, frozenObj.getString("name"))

                // An actual pull catches up the rename, then publication converges B.
                val catchupLease = RoomBackendTestLease()
                val (catchup, catchupPublication) =
                    roomBackendDeliverAndPublish(storeB, account.accountId, apiB, catchupLease)
                assertEquals(PullDeliveryStop.Tail, catchup.pullStop)
                assertNotNull(catchupPublication)
                assertEquals(ProjectionPublicationStatus.PUBLISHED, checkNotNull(catchupPublication).status)
                val repoB = storeB.repository()
                assertEquals(
                    RENAMED_WORKOUT,
                    checkNotNull(repoB.workoutDetails(frozen.id)) {
                        "post-baseline rename must not be lost"
                    }.workout.name,
                )
                assertEquals(
                    FROZEN_ROUTINE,
                    checkNotNull(repoB.routineDetails(routineId)) { "B must hold frozen routine" }.routine.name,
                )
                val renamedObj = JSONObject(
                    checkNotNull(
                        storeB.database.roomBackendReadCanonicalJson(account.accountId, "workout", frozen.id),
                    ),
                )
                assertEquals(RENAMED_WORKOUT, renamedObj.getString("name"))
                roomBackendAssertOutboxEmpty(storeB.database, account.accountId, "B frozen")
                assertEquals(0, storeB.database.roomBackendStageCount(account.accountId))
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
