package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R06d driver boundary: while HTTP is gated, a newer same-root intent lands.
 * Acknowledging the old head preserves newer generation/domain bytes and
 * leaves the tail untouched within budget. No checkpoint advance.
 */
class PushDeliveryNewerIntentTest : GymDatabaseTest() {

    private class GatedApi(private val database: GymDatabase) : GymSyncApi {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            val request = requests.single()
            calls += request.envelope.mutationId
            entered.complete(Unit)
            gate.await()
            return ackAcceptedResult(
                request.envelope.mutationId,
                ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L)),
            )
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
            throw AssertionError("pull must not be called")
        }

        override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
            throw AssertionError("snapshot must not be called")
        }
    }

    @Test
    fun gatedHttp_newerSameRootIntent_preserved() = runTest {
        database.ackSeedFenced()
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A, "Ack Workout")
        val headStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        database.claimCaptureStart(headStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Ack Workout")
        database.syncDao().upsertSyncState(
            com.dragosfotache.gymtracka.data.gym.SyncStateEntity(CLAIM_ACCOUNT_A, "cursor-9", 12345L),
        )

        val api = GatedApi(database)
        val owner = claimOwner()
        val loop = PushDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { ACK_NOW_MILLIS }
        val runner = async { loop.deliver(0L, 1) }
        api.entered.await()

        // Newer same-root upsert lands while the old head is in flight.
        val tailStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        val tail = database.claimCaptureUpsert(tailStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Edited Later")
        database.claimExec("UPDATE workouts SET name = 'Edited Later' WHERE id = '$CLAIM_WORKOUT_A'")
        api.gate.complete(Unit)

        val result = runner.await()
        assertEquals(1, result.acknowledgedMutations)
        assertTrue(result.stop is PushDeliveryStop.LimitReached)
        assertEquals(listOf(CLAIM_MUTATION_1), api.calls)

        // Old head removed; newer generation/domain bytes preserved.
        assertEquals(null, database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        assertEquals(
            mapOf(
                "account_id" to CLAIM_ACCOUNT_A,
                "server_revision" to 5L,
                "local_generation" to 2L,
                "sync_state" to "pending",
                "deleted_at_millis" to null,
            ),
            database.ackMeta("workouts", CLAIM_WORKOUT_A),
        )
        assertEquals(2L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        assertEquals(
            1,
            database.ackCount("SELECT COUNT(*) FROM workouts WHERE id = '$CLAIM_WORKOUT_A' AND name = 'Edited Later'"),
        )
        // Tail untouched within budget: exact row equality, still pending.
        assertEquals(tail, database.ackRow(tail.mutationId))
        assertEquals("pending", database.ackRow(tail.mutationId).state)
        assertEquals(2L, database.ackRow(tail.mutationId).localGeneration)
        // No checkpoint advance.
        assertEquals("cursor-9|12345", database.ackSyncState(CLAIM_ACCOUNT_A))
    }
}
