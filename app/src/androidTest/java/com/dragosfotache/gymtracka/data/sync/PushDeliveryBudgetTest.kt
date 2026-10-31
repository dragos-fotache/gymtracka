package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.engine.MAX_PUSH_DELIVERY_MUTATIONS
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * driver boundary: strict work budget and argument validation.
 * Invalid arguments fail before any clock/store/API work; Empty sends
 * nothing; the limit never probes a successor.
 */
class PushDeliveryBudgetTest : GymDatabaseTest() {

    private class CountingApi(private val database: GymDatabase) : GymSyncApi {
        var calls = 0
        val seen = mutableListOf<String>()
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            calls += 1
            check(!database.inTransaction()) { "push must run outside Room" }
            val request = requests.single()
            seen += request.envelope.mutationId
            return ackAcceptedResult(
                request.envelope.mutationId,
                ackBatch(target = ackWorkoutChange(request.envelope.aggregateId, 7L)),
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
    fun invalidArguments_failBeforeWork() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        val before = database.claimSnapshot()
        var clockCalls = 0
        val api = CountingApi(database)
        val loop = PushDeliveryLoop(database.claimPushStore(), api) {
            clockCalls += 1
            ACK_NOW_MILLIS
        }
        listOf(0, -1, MAX_PUSH_DELIVERY_MUTATIONS + 1, 101).forEach { bad ->
            try {
                loop.deliver(0L, bad)
                fail("maxMutations=$bad must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            loop.deliver(-1L, 10)
            fail("negative epoch must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, clockCalls)
        assertEquals(0, api.calls)
        assertEquals(before, database.claimSnapshot())
        assertEquals(0, database.ackRow().attemptCount)

        val badClock = PushDeliveryLoop(database.claimPushStore(), api) { -5L }
        try {
            badClock.deliver(0L, 10)
            fail("negative clock sample must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, api.calls)
        assertEquals(before, database.claimSnapshot())
        assertEquals(0, database.ackRow().attemptCount)
    }

    @Test
    fun empty_sendsNothing() = runTest {
        database.ackSeedFenced()
        val before = database.claimSnapshot()
        val api = CountingApi(database)
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, result.acknowledgedMutations)
        assertTrue(result.stop is PushDeliveryStop.Empty)
        assertEquals(0, api.calls)
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun limit_doesNotProbeSuccessor_exactlyEmptyAtLimit() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Ack Workout")
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Due Later")
        val secondStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        database.claimCaptureStart(secondStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")

        val api = CountingApi(database)
        val owner = claimOwner()
        val loop = PushDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { ACK_NOW_MILLIS }

        val first = loop.deliver(0L, 1)
        assertEquals(1, first.acknowledgedMutations)
        assertTrue(first.stop is PushDeliveryStop.LimitReached)
        assertEquals(listOf(CLAIM_MUTATION_1), api.seen)
        // Successor never frozen or probed in the limited run.
        assertNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
        val tail = database.ackRow(CLAIM_MUTATION_2)
        assertEquals("pending", tail.state)
        assertEquals(0, tail.attemptCount)

        // Second invocation resumes the remaining sequence.
        val second = loop.deliver(0L, 10)
        assertEquals(1, second.acknowledgedMutations)
        assertTrue(second.stop is PushDeliveryStop.Empty)
        assertEquals(listOf(CLAIM_MUTATION_1, CLAIM_MUTATION_2), api.seen)
        assertEquals(0, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun exactlyEmptyAtLimit_returnsLimitReached() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        val api = CountingApi(database)
        val loop = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }
        val result = loop.deliver(0L, 1)
        assertEquals(1, result.acknowledgedMutations)
        assertTrue(result.stop is PushDeliveryStop.LimitReached)
        assertEquals(1, api.calls)
        val next = loop.deliver(0L, 1)
        assertEquals(0, next.acknowledgedMutations)
        assertTrue(next.stop is PushDeliveryStop.Empty)
        assertEquals(1, api.calls)
    }
}
