package com.dragosfotache.gymtracka.data.sync.roombackend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PullRecoveryReason
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RoomBackend matrix 5: interrupted and expired recovery plus absence.
 *
 * Where real retention controls are unavailable, an explicit cursor expiry is
 * scripted: the old visible graph and checkpoint remain complete and unchanged,
 * and expiry demands a snapshot. A real snapshot then fails on its middle page
 * deterministically, returning Failed without claiming last-success; after
 * reopen the retained stage has no append authority, so it is explicitly
 * abandoned (observed fresh inside the same admitted run) and a NEW snapshot
 * fully recovers. A previously known root deleted while offline stays deleted on
 * the observer and becomes snapshot_absent (at its original revision) on the
 * snapshotter, removed from both visible graphs only after the complete
 * snapshot/publication.
 * Final full graph/canonical/tail/cache predicates hold on BOTH stores with
 * expected fields and child IDs, never mere counts or peer equality.
 */
@RunWith(AndroidJUnit4::class)
class RoomBackendRecoveryAbsenceTest {

    companion object {
        const val KNOWN_NAME = "RoomBackend Known"
        const val EXTRA_NAME = "RoomBackend Extra Root"
        const val FINISHED_AT_MILLIS = 1_790_259_560_000L
        const val STARTED_AT_MILLIS = 1_790_259_500_000L
    }

    @Test
    fun interruptedExpiredRecoveryAbsence() = runBlocking(Dispatchers.IO) {
        val inputs = roomBackendRequireInputs()
        val transport = roomBackendNewTransport(inputs)
        try {
            val account = roomBackendMintAccount(transport, inputs.port)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var storeA = roomBackendOpenStore(context, "recover-a", account.accountId)
            var storeB = roomBackendOpenStore(context, "recover-b", account.accountId)
            try {
                val apiA = roomBackendNewApi(transport, account)
                val apiB = roomBackendNewApi(transport, account)
                roomBackendSnapshotConverge(storeA, account.accountId, apiA, RoomBackendTestLease())
                roomBackendSnapshotConverge(storeB, account.accountId, apiB, RoomBackendTestLease())

                // Settle known (finished BEFORE any second active exists), an
                // extra active root and their custom on both stores.
                val repoA = storeA.repository()
                val customId = repoA.createCustomExercise(
                    "RoomBackend Recovery Press", null, emptySet(), null, null, "",
                )
                val known = repoA.startEmptyWorkout(STARTED_AT_MILLIS)
                repoA.updateWorkoutName(known.id, KNOWN_NAME)
                repoA.addExercisesToWorkout(known.id, listOf(customId))
                val knownEntry = checkNotNull(repoA.workoutDetails(known.id)).exercises.single()
                val knownEntryId = knownEntry.workoutExercise.id
                val knownSetId = knownEntry.sets.single().id
                assertTrue(
                    repoA.updateWorkoutSet(
                        knownEntry.sets.single().copy(repetitions = 9, weightKg = 70.0, isCompleted = true),
                    ),
                )
                assertTrue(repoA.finishWorkout(known.id, FINISHED_AT_MILLIS))
                val extra = repoA.startEmptyWorkout(STARTED_AT_MILLIS + 30_000L)
                repoA.updateWorkoutName(extra.id, EXTRA_NAME)
                repoA.addExercisesToWorkout(extra.id, listOf(customId))
                val extraEntry = checkNotNull(repoA.workoutDetails(extra.id)).exercises.single()
                val extraEntryId = extraEntry.workoutExercise.id
                val extraSetId = extraEntry.sets.single().id
                assertTrue(
                    repoA.updateWorkoutSet(
                        extraEntry.sets.single().copy(repetitions = 4, weightKg = 30.0, isCompleted = true),
                    ),
                )
                assertNotNull(
                    roomBackendDeliverAndPublish(storeA, account.accountId, apiA, RoomBackendTestLease()).second,
                )
                assertNotNull(
                    roomBackendDeliverAndPublish(storeB, account.accountId, apiB, RoomBackendTestLease()).second,
                )
                assertEquals(
                    KNOWN_NAME,
                    checkNotNull(storeB.repository().workoutDetails(known.id)).workout.name,
                )
                assertEquals(
                    EXTRA_NAME,
                    checkNotNull(storeB.repository().workoutDetails(extra.id)).workout.name,
                )

                // A deletes the known finished root while B stays offline; the delete commits.
                repoA.deleteWorkout(known.id)
                assertNotNull(
                    roomBackendDeliverAndPublish(storeA, account.accountId, apiA, RoomBackendTestLease()).second,
                )

                // Scripted explicit cursor expiry: B's graph and checkpoint stay
                // complete and unchanged, and expiry demands a snapshot.
                val checkpointBefore = storeB.database.roomBackendReadSyncState(account.accountId)
                assertNotNull(checkpointBefore)
                val cacheBefore = checkNotNull(storeB.database.roomBackendReadCache(account.accountId))
                val knownDetailsBefore = checkNotNull(storeB.repository().workoutDetails(known.id)) {
                    "B must hold the known graph before expiry"
                }
                val extraDetailsBefore = checkNotNull(storeB.repository().workoutDetails(extra.id)) {
                    "B must hold the extra graph before expiry"
                }
                val customDetailsBefore = checkNotNull(storeB.repository().exerciseDetails(customId)) {
                    "B must hold the custom before expiry"
                }
                val knownRevisionSettled = checkNotNull(
                    storeB.database.roomBackendReadCanonical(account.accountId, "workout", known.id),
                ).serverRevision
                val pushStoreB = PushStore(storeB.database, storeB.session, storeB.owner)
                val expiry = pushStoreB.withRun(roomBackendDurableEpoch(storeB.database, account.accountId)) { run ->
                    SyncDeliveryLoop(pushStoreB, RoomBackendExpireOncePullApi(apiB)).deliver(run)
                }
                assertEquals(PushDeliveryStop.Empty, expiry.push.stop)
                assertEquals(
                    PullDeliveryStop.RecoveryRequired(PullRecoveryReason.CURSOR_EXPIRED),
                    expiry.pull?.stop,
                )
                assertEquals(
                    KNOWN_NAME,
                    checkNotNull(storeB.repository().workoutDetails(known.id)).workout.name,
                )
                assertEquals(checkpointBefore, storeB.database.roomBackendReadSyncState(account.accountId))
                assertEquals(cacheBefore, storeB.database.roomBackendReadCache(account.accountId))
                assertEquals(knownDetailsBefore, storeB.repository().workoutDetails(known.id))
                assertEquals(extraDetailsBefore, storeB.repository().workoutDetails(extra.id))
                assertEquals(customDetailsBefore, storeB.repository().exerciseDetails(customId))

                // Begin a real snapshot with maxPages 1; the middle page fails
                // deterministically and returns Failed without last-success.
                val failing = RoomBackendFailOnceSnapshotApi(apiB, SyncApiException.Transient(), failOnCall = 2)
                val snapshot = SnapshotDeliveryLoop(pushStoreB, failing)
                pushStoreB.withRun(roomBackendDurableEpoch(storeB.database, account.accountId)) { run ->
                    val start = snapshot.start(run, UUID.randomUUID().toString(), maxPages = 1, pageLimit = 1)
                    assertEquals(PushDeliveryStop.Empty, start.push.stop)
                    val first = checkNotNull(start.download) { "B requires a present download" }
                    assertEquals(SnapshotDownloadStop.LimitReached, first.stop)
                    val second = snapshot.continueDownload(run, first, maxPages = 100)
                    assertTrue(
                        "middle page must return Failed, got [${second.stop}]",
                        second.stop is SnapshotDownloadStop.Failed,
                    )
                }
                assertEquals(
                    KNOWN_NAME,
                    checkNotNull(storeB.repository().workoutDetails(known.id)).workout.name,
                )
                assertEquals(checkpointBefore, storeB.database.roomBackendReadSyncState(account.accountId))
                assertEquals(cacheBefore, storeB.database.roomBackendReadCache(account.accountId))
                assertEquals(knownDetailsBefore, storeB.repository().workoutDetails(known.id))
                assertEquals(extraDetailsBefore, storeB.repository().workoutDetails(extra.id))
                assertEquals(customDetailsBefore, storeB.repository().exerciseDetails(customId))

                // Reopen with a fresh owner: the retained stage has no append authority.
                storeB = roomBackendReopen(context, storeB)
                val retained = roomBackendReadStage(storeB, account.accountId)
                assertNotNull("interrupted stage must be retained", retained)
                assertFalse(checkNotNull(retained).canAppend)

                // Explicit abandon (fresh observation inside the same run), then a
                // NEW snapshot fully recovers.
                roomBackendAbandonCurrentStage(storeB, account.accountId)
                assertNull(roomBackendReadStage(storeB, account.accountId))
                val recovered = roomBackendSnapshotConverge(storeB, account.accountId, apiB, RoomBackendTestLease(), pageLimit = 1)
                assertEquals(ProjectionPublicationStatus.PUBLISHED, recovered.status)

                // Absence is per-store by design: A observed the deletion (delete),
                // while B's full snapshot omits it (snapshot_absent at the original
                // revision). Both keep positive retained revisions and the identical
                // visible surviving graph.
                for ((label, store) in listOf("A" to storeA, "B" to storeB)) {
                    assertNull("$label known removed", store.repository().workoutDetails(known.id))
                    val knownCanonical = checkNotNull(
                        store.database.roomBackendReadCanonical(account.accountId, "workout", known.id),
                    ) { "$label must retain known evidence" }
                    if (label == "A") {
                        assertEquals("$label observed deletion", "delete", knownCanonical.canonicalState)
                        assertTrue(
                            "$label delete increments the revision",
                            (knownCanonical.serverRevision ?: 0L) > (knownRevisionSettled ?: 0L),
                        )
                    } else {
                        assertEquals("$label snapshot omission", "snapshot_absent", knownCanonical.canonicalState)
                        assertEquals(
                            "$label preserves the original revision",
                            knownRevisionSettled,
                            knownCanonical.serverRevision,
                        )
                    }
                    val extraDetails = checkNotNull(store.repository().workoutDetails(extra.id)) {
                        "$label must hold the extra root"
                    }
                    assertEquals("$label extra name", EXTRA_NAME, extraDetails.workout.name)
                    assertEquals("$label extra entry", extraEntryId, extraDetails.exercises.single().workoutExercise.id)
                    assertEquals(
                        "$label extra set",
                        4,
                        extraDetails.exercises.single().sets.single().repetitions,
                    )
                    assertEquals(
                        "$label known children gone",
                        0,
                        store.database.roomBackendCount(
                            "SELECT COUNT(*) FROM workout_exercises WHERE workout_id = ?",
                            arrayOf(known.id),
                        ),
                    )
                    roomBackendAssertOutboxEmpty(store.database, account.accountId, "$label recovered")
                    val cache = roomBackendAssertBaselineComplete(store.database, account.accountId, "$label recovered")
                    assertEquals("$label epochs match", cache.cacheEpoch, cache.materializedEpoch)
                    assertEquals("$label staging empty", 0, store.database.roomBackendStageCount(account.accountId))
                }
                val extraSetA = checkNotNull(storeA.repository().workoutDetails(extra.id))
                    .exercises.single().sets.single().id
                val extraSetB = checkNotNull(storeB.repository().workoutDetails(extra.id))
                    .exercises.single().sets.single().id
                assertEquals("extra set identity is the original on A", extraSetId, extraSetA)
                assertEquals("extra set identity is the original on B", extraSetId, extraSetB)
                assertTrue("distinct roots keep distinct sets", extraSetA != knownSetId)
                assertTrue(
                    "known set id disappears on A",
                    storeA.database.roomBackendCount(
                        "SELECT COUNT(*) FROM workout_sets WHERE id = ?",
                        arrayOf(knownSetId),
                    ) == 0,
                )
                assertTrue(
                    "known set id disappears on B",
                    storeB.database.roomBackendCount(
                        "SELECT COUNT(*) FROM workout_sets WHERE id = ?",
                        arrayOf(knownSetId),
                    ) == 0,
                )
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
