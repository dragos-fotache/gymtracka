package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * driver boundary: ordered multi-head success through real capture,
 * scripted API and canonical acknowledgment. Each HTTP call observes exactly
 * one committed frozen explicit head outside Room.
 */
class PushDeliverySuccessTest : GymDatabaseTest() {

    private class ScriptApi(
        private val database: GymDatabase,
    ) : GymSyncApi {
        val calls = mutableListOf<List<DurableRequestModel>>()
        val seenInTransaction = mutableListOf<Boolean>()

        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            seenInTransaction += database.inTransaction()
            check(requests.size == 1) { "driver must send exactly one head" }
            val request = requests.single()
            val index = calls.size
            if (index == 0) {
                check(request.envelope.mutationId == CLAIM_MUTATION_1) {
                    "first push must carry the explicit head, got ${request.envelope.mutationId}"
                }
                val frozen = checkNotNull(
                    database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A),
                ) { "explicit head must be committed at first push entry" }
                check(frozen.state == "frozen") { "head must be frozen at send, got ${frozen.state}" }
                check(frozen.attemptCount == 1) { "head admission must increment once, got ${frozen.attemptCount}" }
                check(JSONObject(frozen.payloadJson).getString("requestJson") == request.requestJson) {
                    "transport must observe the exact frozen bytes"
                }
            } else {
                check(request.envelope.mutationId == CLAIM_MUTATION_2) {
                    "second push must carry the explicit successor, got ${request.envelope.mutationId}"
                }
                // Predecessor removal is already durable before successor send.
                check(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A) == null) {
                    "predecessor must be removed before successor is sent"
                }
                val frozen = checkNotNull(
                    database.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A),
                ) { "successor must be committed at second push entry" }
                check(frozen.state == "frozen") { "successor must be frozen at send, got ${frozen.state}" }
                check(frozen.attemptCount == 1) { "successor admission must increment once, got ${frozen.attemptCount}" }
                check(JSONObject(frozen.payloadJson).getString("requestJson") == request.requestJson) {
                    "transport must observe the exact frozen successor bytes"
                }
            }
            calls += requests.toList()
            val batch = if (request.envelope.mutationId == CLAIM_MUTATION_1) {
                ackBatch(
                    target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L),
                    extraDeletes = listOf(ackDeleteChange(targetId = ACK_UNKNOWN_CLOUD, revision = 11L)),
                )
            } else {
                ackBatch(target = ackRoutineChange(ACK_ROUTINE_A, 7L))
            }
            return ackAcceptedResult(request.envelope.mutationId, batch)
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
            throw AssertionError("pull must not be called by delivery driver")
        }

        override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
            throw AssertionError("snapshot must not be called by delivery driver")
        }
    }

    @Test
    fun orderedTwoHeadSuccess_acknowledgesInSequence() = runTest {
        database.ackSeedFenced()
        val head = database.ackCaptureWorkoutHead(
            mutationId = CLAIM_MUTATION_1,
            rootId = CLAIM_WORKOUT_A,
            name = "Ack Workout",
        )
        val tail = database.ackCaptureRoutineHead(
            mutationId = CLAIM_MUTATION_2,
            rootId = ACK_ROUTINE_A,
            name = "Ack Routine",
        )
        database.syncDao().upsertSyncState(SyncStateEntity(CLAIM_ACCOUNT_A, "cursor-9", 12345L))
        val cacheBefore = database.ackCacheRow(CLAIM_ACCOUNT_A)
        val headInner = JSONObject(head.payloadJson).getString("requestJson")
        val tailInner = JSONObject(tail.payloadJson).getString("requestJson")

        val owner = claimOwner()
        val api = ScriptApi(database)
        val store = database.claimPushStore(claimSession(), owner)
        val loop = PushDeliveryLoop(store, api) { ACK_NOW_MILLIS }

        val result = loop.deliver(0L, 100)

        assertEquals(2, result.acknowledgedMutations)
        assertTrue(result.stop is PushDeliveryStop.Empty)
        // Exactly one frozen head per HTTP call, outside Room, in order.
        assertEquals(2, api.calls.size)
        assertEquals(listOf(false, false), api.seenInTransaction)
        assertEquals(CLAIM_MUTATION_1, api.calls[0].single().envelope.mutationId)
        assertEquals(CLAIM_MUTATION_2, api.calls[1].single().envelope.mutationId)
        assertEquals(headInner, api.calls[0].single().requestJson)
        assertEquals(tailInner, api.calls[1].single().requestJson)

        // Exact removal of both frozen rows.
        assertNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
        assertNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A))
        assertEquals(0, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)

        // Full canonical evidence with independent business literals.
        val workoutStored = JSONObject(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A)!!)
        assertEquals("W", workoutStored.getString("name"))
        assertEquals(7L, workoutStored.getLong("revision"))
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        val routineStored = JSONObject(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "routine", ACK_ROUTINE_A)!!)
        assertEquals("R", routineStored.getString("name"))
        assertEquals(7L, routineStored.getLong("revision"))
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "routine", ACK_ROUTINE_A))
        // Extra cloud deletion is stored without a canonical body.
        assertEquals(11L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", ACK_UNKNOWN_CLOUD))
        assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", ACK_UNKNOWN_CLOUD))
        assertEquals(
            "delete",
            database.syncCanonicalDao().getRoot(CLAIM_ACCOUNT_A, "workout", ACK_UNKNOWN_CLOUD)?.canonicalState,
        )
        assertEquals(
            "upsert",
            database.syncCanonicalDao().getRoot(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A)?.canonicalState,
        )
        assertEquals(
            "upsert",
            database.syncCanonicalDao().getRoot(CLAIM_ACCOUNT_A, "routine", ACK_ROUTINE_A)?.canonicalState,
        )

        // Generation-scoped metadata and watermarks settle at the frozen generation.
        assertEquals(
            mapOf(
                "account_id" to CLAIM_ACCOUNT_A,
                "server_revision" to 7L,
                "local_generation" to 1L,
                "sync_state" to "synced",
                "deleted_at_millis" to null,
            ),
            database.ackMeta("workouts", CLAIM_WORKOUT_A),
        )
        assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "routine", ACK_ROUTINE_A))
        assertEquals(true, database.ackProjectionDirty(CLAIM_ACCOUNT_A))

        // Preserved domain business names (not canonical labels).
        assertEquals(
            1,
            database.ackCount("SELECT COUNT(*) FROM workouts WHERE id = '$CLAIM_WORKOUT_A' AND name = 'Ack Workout'"),
        )
        assertEquals(
            1,
            database.ackCount("SELECT COUNT(*) FROM routines WHERE id = '$ACK_ROUTINE_A' AND name = 'Ack Routine'"),
        )

        // Preserved checkpoints: cursor/last-success unchanged, cache epoch unchanged.
        assertEquals("cursor-9|12345", database.ackSyncState(CLAIM_ACCOUNT_A))
        val beforeParts = cacheBefore.split("|")
        val afterParts = database.ackCacheRow(CLAIM_ACCOUNT_A).split("|")
        assertEquals(beforeParts[0], afterParts[0])
        assertEquals(beforeParts[1], afterParts[1])
        assertEquals(beforeParts[3], afterParts[3])
    }
}
