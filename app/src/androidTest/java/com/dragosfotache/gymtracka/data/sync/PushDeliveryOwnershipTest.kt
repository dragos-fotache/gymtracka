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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06d driver boundary: two callers sharing one run owner never overlap
 * transport; a canceled queued caller creates no extra admission; ownership
 * releases on every stop path so a later caller resumes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushDeliveryOwnershipTest : GymDatabaseTest() {

    private class GatedApi(private val database: GymDatabase) : GymSyncApi {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var concurrent = 0
        var maxConcurrent = 0
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            calls += 1
            concurrent += 1
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                entered.complete(Unit)
                gate.await()
                return ackAcceptedResult(
                    requests.single().envelope.mutationId,
                    ackBatch(target = ackWorkoutChange(requests.single().envelope.aggregateId, 7L)),
                )
            } finally {
                concurrent -= 1
            }
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
            throw AssertionError("pull must not be called")
        }

        override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
            throw AssertionError("snapshot must not be called")
        }
    }

    @Test
    fun sharedOwner_serializesTransport_canceledQueueCreatesNoAdmission() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        val owner = claimOwner()
        val api = GatedApi(database)
        val firstLoop = PushDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { ACK_NOW_MILLIS }
        val secondLoop = PushDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { ACK_NOW_MILLIS }

        val first = async { firstLoop.deliver(0L, 10) }
        api.entered.await()
        val second = async { secondLoop.deliver(0L, 10) }
        runCurrent()
        // Queued caller never overlaps transport.
        assertEquals(1, api.calls)
        assertEquals(1, api.maxConcurrent)

        // Canceled queued caller creates no extra admission.
        second.cancel()
        try {
            second.await()
            fail("queued delivery must observe cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, database.ackRow(CLAIM_MUTATION_1).attemptCount)

        api.gate.complete(Unit)
        val done = first.await()
        assertEquals(1, done.acknowledgedMutations)
        assertTrue(done.stop is PushDeliveryStop.Empty)
        assertEquals(1, api.calls)
        assertEquals(1, api.maxConcurrent)

        // Ownership released: a later caller resumes to Empty.
        val later = PushDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, later.acknowledgedMutations)
        assertTrue(later.stop is PushDeliveryStop.Empty)
        assertEquals(1, api.calls)
    }
}
