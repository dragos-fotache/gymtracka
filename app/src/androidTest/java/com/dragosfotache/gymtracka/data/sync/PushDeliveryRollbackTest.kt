package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.PushStorageException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06d driver boundary: triggered acknowledgment rollback propagates and
 * retains the exact frozen row without sending a successor; recovery
 * re-admits it. Invalid acknowledgment context yields retained corruption,
 * never success.
 */
class PushDeliveryRollbackTest : GymDatabaseTest() {

    private class AcceptingApi(
        private val database: GymDatabase,
        var calls: Int = 0,
    ) : GymSyncApi {
        val seen = mutableListOf<String>()
        var admittedSnapshot = ""
        val bodies = mutableListOf<String>()
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            calls += 1
            val request = requests.single()
            seen += request.envelope.mutationId
            bodies += request.requestJson
            // Complete admitted-state snapshot at API entry, before the failed ack.
            admittedSnapshot = database.claimSnapshot()
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
    fun ackRemovalTrigger_rollsBackBlocksSuccessor_recovers() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A)
        val frozenInner = JSONObject(database.ackRow(CLAIM_MUTATION_1).payloadJson).getString("requestJson")
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Due Later")
        val tailStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        database.claimCaptureStart(tailStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")

        // Same explicit owner across failure and recovery: the failed run must
        // release ownership so the fresh recovery run can proceed.
        val owner = claimOwner()
        val api = AcceptingApi(database)
        database.claimExec(
            "CREATE TRIGGER delivery_ack_block BEFORE DELETE ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'delivery_ack_blocked'); END",
        )
        try {
            try {
                PushDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { ACK_NOW_MILLIS }
                    .deliver(0L, 10)
                fail("removal trigger failure must propagate as storage failure")
            } catch (error: PushStorageException) {
                assertEquals("Push storage failure", error.message)
                assertNull(error.cause)
                val leaked = error.message ?: ""
                assertFalse(leaked.contains("delivery_ack_blocked"))
                assertFalse(leaked.contains("RAISE"))
                assertFalse(leaked.contains("sync_outbox"))
            }
        } finally {
            database.claimExec("DROP TRIGGER IF EXISTS delivery_ack_block")
        }
        // Exactly one HTTP send; the successor was never admitted in that run.
        assertEquals(1, api.calls)
        assertEquals(listOf(CLAIM_MUTATION_1), api.seen)
        // Rollback restores the exact admitted state, trigger excluded.
        assertEquals(api.admittedSnapshot, database.claimSnapshot())
        // The failed ack leaves the head frozen with an incremented admission,
        // never the pending captured row.
        val retained = database.ackRow(CLAIM_MUTATION_1)
        assertEquals("frozen", retained.state)
        assertEquals(1, retained.attemptCount)
        assertEquals(frozenInner, JSONObject(retained.payloadJson).getString("requestJson"))
        assertEquals("pending", database.ackRow(CLAIM_MUTATION_2).state)
        assertEquals(0, database.ackRow(CLAIM_MUTATION_2).attemptCount)
        assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))

        // Fresh admission under the same owner recovers: the first response
        // replays the duplicate of the original accepted batch with identical
        // request bytes, then the successor settles.
        val batch = ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))
        var recoveryCalls = 0
        val recovery = object : GymSyncApi {
            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                recoveryCalls += 1
                val request = requests.single()
                return if (request.envelope.mutationId == CLAIM_MUTATION_1) {
                    assertEquals(frozenInner, request.requestJson)
                    ackAcceptedResult(request.envelope.mutationId, batch, MutationStatus.DUPLICATE)
                } else {
                    ackAcceptedResult(
                        request.envelope.mutationId,
                        ackBatch(target = ackWorkoutChange(request.envelope.aggregateId, 7L)),
                    )
                }
            }

            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                throw AssertionError("pull must not be called")
            }

            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                throw AssertionError("snapshot must not be called")
            }
        }
        val result =
            PushDeliveryLoop(database.claimPushStore(claimSession(), owner), recovery) { ACK_NOW_MILLIS }
                .deliver(0L, 10)
        assertEquals(2, result.acknowledgedMutations)
        assertTrue(result.stop is PushDeliveryStop.Empty)
        assertEquals(2, recoveryCalls)
    }

    @Test
    fun invalidAckContext_returnsRetainedCorruption() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Ack Workout")
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Due Later")
        val tailStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        database.claimCaptureStart(tailStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")
        // Representative invalid context: physical generation lowered below
        // the frozen affected generation.
        database.claimExec("UPDATE workouts SET local_generation = 0 WHERE id = '$CLAIM_WORKOUT_A'")

        val api = AcceptingApi(database)
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, result.acknowledgedMutations)
        val blocked = result.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.RETAINED_ERROR, blocked.reason)
        assertEquals(ACK_CONTEXT_CORRUPT_CODE, blocked.errorCode)
        assertEquals(1, api.calls)
        assertEquals(ACK_CONTEXT_CORRUPT_CODE, database.ackRow(CLAIM_MUTATION_1).errorCode)
        assertEquals("pending", database.ackRow(CLAIM_MUTATION_2).state)
    }
}
