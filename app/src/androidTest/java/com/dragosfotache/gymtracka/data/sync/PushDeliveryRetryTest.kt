package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
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
 * R06d driver boundary: lost-response transient uses failure-time backoff,
 * stays deferred without extra calls, survives file reopen with exact
 * identity, then duplicate-accepted settles once before the successor.
 */
class PushDeliveryRetryTest : GymDatabaseTest() {

    private companion object {
        const val DW_MUTATION = "d1111111-1111-1111-1111-111111111111"
        const val DW_NEW = "d2222222-2222-2222-2222-222222222222"
        const val DW_EX = "d4444444-4444-4444-4444-444444444444"
        const val DW_SRC_EX = "d5555555-5555-5555-5555-555555555555"
        const val DW_SET = "d6666666-6666-6666-6666-666666666666"
    }

    /** Hand-authored noncanonical upsert inner with a child graph, odd spacing and an escape. */
    private fun handInner(): String =
        "{ \"payload\" : { \"id\" : \"$DW_NEW\" , \"name\" : \"Numeric\" , " +
            "\"status\" : \"active\" , \"startedAt\" : \"2026-09-24T14:00:00Z\" , \"exercises\" : [ " +
            "{ \"id\" : \"$DW_EX\" , \"exerciseId\" : \"$DW_SRC_EX\" , \"position\" : 0 , " +
            "\"notes\" : \"\" , \"restDurationSeconds\" : 60 , \"sets\" : [ " +
            "{ \"id\" : \"$DW_SET\" , \"position\" : 0 , \"repetitions\" : 8 , \"weightKg\" : 1e1 , " +
            "\"isCompleted\" : true , \"restSeconds\" : null } ] } ] } , " +
            "\"knownServerRevision\" : null , \"localGeneration\"   :   1 , " +
            "\"aggregateId\" : \"d2222222-2222-2222-2222-22222222222\\u0032\" , \"aggregateType\" : \"workout\" , " +
            "\"type\" : \"workout.upsert\" , \"mutationId\" : \"$DW_MUTATION\" }"

    private suspend fun GymDatabase.seedHandAuthored(inner: String) {
        claimSeedDevice()
        claimExec("DELETE FROM sync_account_cache")
        claimSeedCache(CLAIM_ACCOUNT_A, 0L)
        // Real fenced capture of a pending upsert at generation 1, so the
        // watermark/dirty capture context exists; then substitute matching
        // hand-authored bytes only while pending/never-sent, preserving the
        // captured identity, generation and reservation.
        claimSeedWorkout(DW_NEW, CLAIM_ACCOUNT_A, "Hand Authored", generation = 0L)
        val store = claimCaptureStore(claimSession(), mutableListOf(DW_MUTATION))
        val captured = claimCaptureUpsert(store, CLAIM_ACCOUNT_A, DW_NEW, "Numeric")
        val wrapper = claimWrapRequest(inner, claimRootEntry(DW_NEW, 1L))
        claimExec(
            "UPDATE sync_outbox SET payload_json = ? WHERE sequence = ? AND state = 'pending' AND attempt_count = 0",
            arrayOf<Any?>(wrapper, captured.sequence),
        )
        val substituted = checkNotNull(syncDao().getOutboxMutation(DW_MUTATION, CLAIM_ACCOUNT_A)) {
            "substituted row must be readable"
        }
        check(substituted.payloadJson == wrapper) { "hand-authored substitution must apply while pending" }
        check(substituted.state == "pending" && substituted.attemptCount == 0) {
            "substitution must not admit or freeze the row"
        }
    }

    private class ScriptApi(private val database: GymDatabase) : GymSyncApi {
        val ids = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val effects = mutableListOf<List<Triple<AggregateType, String, Long>>>()
        val admissionAttempts = mutableListOf<Int>()
        var mode: String = "transient"
        var hint: Long? = 5_000L
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            val request = requests.single()
            ids += request.envelope.mutationId
            bodies += request.requestJson
            effects += request.affectedRoots.map {
                Triple(it.aggregateType, it.aggregateId, it.localGeneration)
            }
            val frozen = checkNotNull(
                database.syncDao().getOutboxMutation(request.envelope.mutationId, CLAIM_ACCOUNT_A),
            ) { "transmitted head must be committed frozen at entry" }
            admissionAttempts += frozen.attemptCount
            return when (mode) {
                "transient" -> throw SyncApiException.Transient(hint)
                "duplicate" -> ackAcceptedResult(
                    request.envelope.mutationId,
                    ackBatch(target = ackWorkoutChange(request.envelope.aggregateId, 7L)),
                    MutationStatus.DUPLICATE,
                )
                else -> error("unknown mode")
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
    fun transientUsesFailureTime_reopenReplaysExactBytes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("delivery-retry")
        val entryTime = ACK_NOW_MILLIS
        val failureTime = ACK_NOW_MILLIS + 60_000L
        val inner = handInner()
        assertTrue(inner.contains("\"localGeneration\"   :   1"))
        assertTrue(inner.contains("\\u0032"))
        var preCloseBytes = ""
        val fileDatabase = claimFileDatabase(context, name)
        try {
            fileDatabase.seedHandAuthored(inner)
            preCloseBytes = fileDatabase.ackRow(DW_MUTATION).payloadJson
            assertTrue(preCloseBytes.contains("\"payloadVersion\" : 1"))

            val times = ArrayDeque(listOf(entryTime, failureTime))
            val api = ScriptApi(fileDatabase)
            val loop = PushDeliveryLoop(fileDatabase.claimPushStore(), api) { times.removeFirst() }
            val first = loop.deliver(0L, 10)
            assertEquals(0, first.acknowledgedMutations)
            val deferred = first.stop as PushDeliveryStop.Deferred
            // Backoff derives from failure time, not entry time.
            assertEquals(failureTime + 5_000L, deferred.retryAfterMillis)
            // Actual transmitted identity: exact bytes, effects and child graph.
            assertEquals(listOf(DW_MUTATION), api.ids)
            assertEquals(inner, api.bodies.single())
            assertEquals(
                listOf(Triple(AggregateType.WORKOUT, DW_NEW, 1L)),
                api.effects.single(),
            )
            assertTrue(api.bodies.single().contains(DW_EX))
            assertTrue(api.bodies.single().contains(DW_SET))
            assertTrue(api.bodies.single().contains(DW_SRC_EX))
            assertEquals(listOf(1), api.admissionAttempts)
            assertEquals("frozen", fileDatabase.ackRow(DW_MUTATION).state)

            // Before due: deferred without another HTTP call.
            val early = PushDeliveryLoop(fileDatabase.claimPushStore(), api) { failureTime }.deliver(0L, 10)
            assertEquals(0, early.acknowledgedMutations)
            assertEquals(failureTime + 5_000L, (early.stop as PushDeliveryStop.Deferred).retryAfterMillis)
            assertEquals(listOf(DW_MUTATION), api.ids)
        } finally {
            fileDatabase.close()
        }

        val reopened = claimFileDatabase(context, name)
        try {
            reopened.claimSeedDevice()
            val head = reopened.ackRow(DW_MUTATION)
            assertEquals("frozen", head.state)
            assertEquals(failureTime + 5_000L, head.retryAfterMillis)
            assertEquals(preCloseBytes, head.payloadJson)
            assertEquals(inner, JSONObject(head.payloadJson).getString("requestJson"))

            val api = ScriptApi(reopened)
            api.mode = "duplicate"
            // At due time the same frozen identity is replayed with an
            // incremented admission; duplicate settles once.
            val due = failureTime + 5_000L
            val loop = PushDeliveryLoop(reopened.claimPushStore(), api) { due }
            val result = loop.deliver(0L, 1)
            assertEquals(1, result.acknowledgedMutations)
            assertTrue(result.stop is PushDeliveryStop.LimitReached)
            assertEquals(listOf(DW_MUTATION), api.ids)
            assertEquals(inner, api.bodies.single())
            assertEquals(
                listOf(Triple(AggregateType.WORKOUT, DW_NEW, 1L)),
                api.effects.single(),
            )
            assertEquals(listOf(2), api.admissionAttempts)
            assertNull(reopened.syncDao().getOutboxMutation(DW_MUTATION, CLAIM_ACCOUNT_A))
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
