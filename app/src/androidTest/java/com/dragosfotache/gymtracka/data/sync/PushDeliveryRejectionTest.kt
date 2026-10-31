package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * driver boundary: rejected/duplicate-rejected, malformed, oversized
 * and unknown permanent failures retain the head and stop successors;
 * auth stays resumable without refresh; unknown codes never leak into
 * string summaries.
 */
class PushDeliveryRejectionTest : GymDatabaseTest() {

    private class ScriptApi(
        private val database: GymDatabase,
        var action: (DurableRequestModel) -> PushResultModel,
    ) : GymSyncApi {
        var calls = 0
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            calls += 1
            return action(requests.single())
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
            throw AssertionError("pull must not be called")
        }

        override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
            throw AssertionError("snapshot must not be called")
        }
    }

    private class ThrowingApi(
        private val database: GymDatabase,
        val error: SyncApiException,
    ) : GymSyncApi {
        var calls = 0
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            calls += 1
            throw error
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
            throw AssertionError("pull must not be called")
        }

        override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
            throw AssertionError("snapshot must not be called")
        }
    }

    private suspend fun seedTwoHeads() {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Ack Workout")
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Due Later")
        val tailStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        database.claimCaptureStart(tailStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")
    }

    @Test
    fun rejected_stopsSuccessor_retainsHead() = runTest {
        seedTwoHeads()
        val stored = database.ackRow(CLAIM_MUTATION_1)
        val api = ScriptApi(database) { request ->
            outcomeResult(request.envelope.mutationId, MutationStatus.REJECTED, "gone", null)
        }
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, result.acknowledgedMutations)
        val blocked = result.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.REJECTED, blocked.reason)
        assertEquals("gone", blocked.errorCode)
        assertEquals(1, api.calls)
        // Rejected head persists in rejected state with exact bytes; successor never sent.
        val head = database.ackRow(CLAIM_MUTATION_1)
        assertEquals("rejected", head.state)
        assertEquals("gone", head.errorCode)
        assertEquals(OUTCOME_REJECTED_MESSAGE, head.errorMessage)
        assertFalse(head.errorMessage!!.contains("gone"))
        assertNull(head.retryAfterMillis)
        assertEquals(stored.payloadJson, head.payloadJson)
        assertEquals("pending", database.ackRow(CLAIM_MUTATION_2).state)
        // Later invocation sends nothing around retained errors.
        val again = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertTrue(again.stop is PushDeliveryStop.Blocked)
        assertEquals(1, api.calls)
    }

    @Test
    fun duplicateWithOriginalRejection_stopsSuccessor() = runTest {
        seedTwoHeads()
        val api = ScriptApi(database) { request ->
            outcomeResult(request.envelope.mutationId, MutationStatus.DUPLICATE, "gone", null)
        }
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, result.acknowledgedMutations)
        val blocked = result.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.REJECTED, blocked.reason)
        assertEquals("gone", blocked.errorCode)
        assertEquals(1, api.calls)
        assertEquals("rejected", database.ackRow(CLAIM_MUTATION_1).state)
        assertEquals("gone", database.ackRow(CLAIM_MUTATION_1).errorCode)
        assertEquals("pending", database.ackRow(CLAIM_MUTATION_2).state)
    }

    @Test
    fun mismatchedIdResult_blocksAsMalformed() = runTest {
        seedTwoHeads()
        val api = ScriptApi(database) {
            // Exact-ID violation: outcome names the successor while the head is admitted.
            outcomeAcceptedResult(CLAIM_MUTATION_2, outcomeBatch())
        }
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, result.acknowledgedMutations)
        val blocked = result.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.RETAINED_ERROR, blocked.reason)
        assertEquals("malformed_response", blocked.errorCode)
        assertEquals(1, api.calls)
        assertEquals("frozen", database.ackRow(CLAIM_MUTATION_1).state)
        assertEquals("pending", database.ackRow(CLAIM_MUTATION_2).state)
    }

    @Test
    fun malformedResult_blocksWithFixedCode() = runTest {
        seedTwoHeads()
        val api = ScriptApi(database) { PushResultModel(emptyList()) }
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, result.acknowledgedMutations)
        val blocked = result.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.RETAINED_ERROR, blocked.reason)
        assertEquals("malformed_response", blocked.errorCode)
        assertEquals(1, api.calls)
        assertEquals("frozen", database.ackRow(CLAIM_MUTATION_1).state)
    }

    @Test
    fun oversizedAndUnknownPermanent_retainHead_unknownCodeHiddenFromSummary() = runTest {
        seedTwoHeads()
        val oversized = ThrowingApi(database, SyncApiException.RequestTooLarge())
        val first = PushDeliveryLoop(database.claimPushStore(), oversized) { ACK_NOW_MILLIS }.deliver(0L, 10)
        val blockedLarge = first.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.RETAINED_ERROR, blockedLarge.reason)
        assertEquals("request_too_large", blockedLarge.errorCode)
        assertEquals(1, oversized.calls)

        database.claimExec(
            "UPDATE sync_outbox SET error_code = NULL, error_message = NULL WHERE mutation_id = '$CLAIM_MUTATION_1'",
        )
        val mystery = "mystery-9"
        val unknown = ThrowingApi(database, SyncApiException.RequestRejected(mystery))
        val second = PushDeliveryLoop(database.claimPushStore(), unknown) { ACK_NOW_MILLIS }.deliver(0L, 10)
        val blocked = second.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.RETAINED_ERROR, blocked.reason)
        assertEquals(mystery, blocked.errorCode)
        // Exact unknown code accessible but absent from string summaries.
        val text = second.toString() + " " + blocked.toString()
        assertFalse(text.contains(mystery))
        assertEquals(mystery, database.ackRow(CLAIM_MUTATION_1).errorCode)
        assertEquals(1, unknown.calls)
        assertEquals("pending", database.ackRow(CLAIM_MUTATION_2).state)
    }

    @Test
    fun authFailures_stayResumableWithoutRefresh() = runTest {
        seedTwoHeads()
        val stored = database.ackRow(CLAIM_MUTATION_1)
        val api = ThrowingApi(database, SyncApiException.AuthenticationRequired())
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, result.acknowledgedMutations)
        assertTrue(result.stop is PushDeliveryStop.AuthenticationRequired)
        assertEquals(1, api.calls)
        // Read-only: frozen bytes unchanged, no error code.
        assertEquals(stored.payloadJson, database.ackRow(CLAIM_MUTATION_1).payloadJson)
        assertEquals("frozen", database.ackRow(CLAIM_MUTATION_1).state)
        assertEquals(1, database.ackRow(CLAIM_MUTATION_1).attemptCount)
        assertNull(database.ackRow(CLAIM_MUTATION_1).errorCode)
        assertNull(database.ackRow(CLAIM_MUTATION_1).retryAfterMillis)

        // The next invocation naturally re-admits the unchanged frozen
        // identity: attempt 2, still no errors/retry, successor untouched,
        // and still no driver refresh retry.
        val reauth = ThrowingApi(database, SyncApiException.ReauthenticationRequired())
        val second = PushDeliveryLoop(database.claimPushStore(), reauth) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, second.acknowledgedMutations)
        assertTrue(second.stop is PushDeliveryStop.ReauthenticationRequired)
        assertEquals(1, reauth.calls)
        val readmitted = database.ackRow(CLAIM_MUTATION_1)
        assertEquals("frozen", readmitted.state)
        assertEquals(2, readmitted.attemptCount)
        assertEquals(stored.payloadJson, readmitted.payloadJson)
        assertNull(readmitted.errorCode)
        assertNull(readmitted.retryAfterMillis)
        assertEquals("pending", database.ackRow(CLAIM_MUTATION_2).state)
    }

    @Test
    fun acceptedHeadThenOversizedSecond_preservesFirstCommit() = runTest {
        seedTwoHeads()
        database.syncDao().upsertSyncState(SyncStateEntity(CLAIM_ACCOUNT_A, "cursor-9", 12345L))
        val cacheBefore = database.ackCacheRow(CLAIM_ACCOUNT_A)
        val secondBytes = database.ackRow(CLAIM_MUTATION_2).payloadJson
        var calls = 0
        val api = object : GymSyncApi {
            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                check(!database.inTransaction()) { "push must run outside Room" }
                calls += 1
                val request = requests.single()
                if (request.envelope.mutationId == CLAIM_MUTATION_1) {
                    return ackAcceptedResult(
                        request.envelope.mutationId,
                        ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L)),
                    )
                }
                throw SyncApiException.RequestTooLarge()
            }

            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                throw AssertionError("pull must not be called")
            }

            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                throw AssertionError("snapshot must not be called")
            }
        }
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(1, result.acknowledgedMutations)
        val blocked = result.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.RETAINED_ERROR, blocked.reason)
        assertEquals("request_too_large", blocked.errorCode)
        assertEquals(2, calls)
        // Earlier committed acknowledgment stays durable.
        assertNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
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
        // Later head fails in place with retained error; no bypass.
        val second = database.ackRow(CLAIM_MUTATION_2)
        assertEquals("frozen", second.state)
        assertEquals("request_too_large", second.errorCode)
        assertNull(second.retryAfterMillis)
        assertEquals(secondBytes, second.payloadJson)
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
        // Checkpoint unchanged.
        assertEquals("cursor-9|12345", database.ackSyncState(CLAIM_ACCOUNT_A))
        val beforeParts = cacheBefore.split("|")
        val afterParts = database.ackCacheRow(CLAIM_ACCOUNT_A).split("|")
        assertEquals(beforeParts[0], afterParts[0])
        assertEquals(beforeParts[1], afterParts[1])
        assertEquals(beforeParts[3], afterParts[3])
    }

    @Test
    fun wrongOperationDeleteTarget_blocksWithoutSuccessor() = runTest {
        seedTwoHeads()
        val api = ScriptApi(database) { request ->
            outcomeResult(
                request.envelope.mutationId,
                MutationStatus.ACCEPTED,
                null,
                SyncBatchModel(
                    batchId = ACK_BATCH_1,
                    cursor = ACK_CURSOR_1,
                    changes = listOf(
                        SyncChangeModel(AggregateType.WORKOUT, CLAIM_WORKOUT_A, ChangeOperation.DELETE, 7L, null),
                    ),
                ),
            )
        }
        val result = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(0, result.acknowledgedMutations)
        val blocked = result.stop as PushDeliveryStop.Blocked
        assertEquals(PushBlockReason.RETAINED_ERROR, blocked.reason)
        assertEquals(1, api.calls)
        assertEquals("pending", database.ackRow(CLAIM_MUTATION_2).state)
    }
}
