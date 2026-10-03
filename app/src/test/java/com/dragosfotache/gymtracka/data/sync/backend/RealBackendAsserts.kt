package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Shared helpers for real-backend integration tests. Expected records are always
 * constructed by hand in each test from the contract; nothing derives expected
 * values from the response under test. Server-generated batch IDs and cursors
 * are opaque: helpers assert validity, progress, and replay equality, never
 * predicted strings.
 */
object RealBackendAsserts {
    fun pushOne(api: HttpGymSyncApi, request: DurableRequestModel): PushResultModel =
        runBlocking { api.push(listOf(request)) }

    fun acceptedOutcome(result: PushResultModel, index: Int = 0): SyncBatchModel {
        assertEquals(1, result.results.size)
        val outcome = result.results[index]
        assertEquals(MutationStatus.ACCEPTED, outcome.status)
        assertNull("Accepted outcome must not carry an error code", outcome.errorCode)
        val batch = outcome.batch
        assertNotNull("Accepted outcome requires a batch", batch)
        return batch!!
    }

    fun rejectedOutcome(result: PushResultModel, errorCode: String, index: Int = 0) {
        assertEquals(1, result.results.size)
        val outcome = result.results[index]
        assertEquals(MutationStatus.REJECTED, outcome.status)
        assertEquals(errorCode, outcome.errorCode)
        assertNull("Rejected outcome must not carry a batch", outcome.batch)
    }

    fun duplicateOutcome(result: PushResultModel, index: Int = 0) =
        result.results.also {
            assertEquals(1, it.size)
            assertEquals(MutationStatus.DUPLICATE, it[index].status)
        }[index]

    fun upsertChange(type: AggregateType, id: String, record: CanonicalRecord, revision: Long) =
        SyncChangeModel(type, id, ChangeOperation.UPSERT, revision, record)

    fun deleteChange(type: AggregateType, id: String, revision: Long) =
        SyncChangeModel(type, id, ChangeOperation.DELETE, revision, null)

    /** Asserts opaque batch identity is well-formed without predicting values. */
    fun assertOpaqueBatch(batch: SyncBatchModel) {
        assertTrue("Batch ID must be a UUID", batch.batchId.matches(UUID_PATTERN))
        assertTrue("Batch cursor must be nonempty", batch.cursor.isNotEmpty())
    }

    private val UUID_PATTERN =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Pulls every batch from [cursor] with small pages to exercise pagination.
     *
     * Bounded: fails on a repeated cursor, a repeated batch ID, or more than
     * [maxPages] pages instead of hanging if the server malfunctions. A valid
     * terminal empty page preserves the boundary cursor and need not advance.
     */
    suspend fun pullAll(api: HttpGymSyncApi, cursor: String?, limit: Int = 3, maxPages: Int = 1_000): Pair<List<SyncBatchModel>, String> {
        val batches = mutableListOf<SyncBatchModel>()
        val seenCursors = HashSet<String>()
        val seenBatchIds = HashSet<String>()
        var next: String? = cursor
        var tail = cursor ?: ""
        repeat(maxPages) {
            val page = api.pull(next, limit)
            if (page.batches.isNotEmpty()) {
                require(seenCursors.add(page.nextCursor)) {
                    "Pull repeated a cursor; refusing to page forever"
                }
                page.batches.forEach { batch ->
                    require(seenBatchIds.add(batch.batchId)) {
                        "Pull repeated a batch ID; refusing to page forever"
                    }
                }
            }
            batches += page.batches
            tail = page.nextCursor
            next = page.nextCursor
            if (!page.hasMore) return batches to tail
        }
        throw AssertionError("Pull did not terminate within $maxPages pages")
    }

    /**
     * Reads the whole frozen snapshot with a fixed page size. All pages must
     * share one baseline cursor; the terminal page carries no token.
     *
     * Bounded: fails on a repeated page token, a repeated aggregate key, or
     * more than [maxPages] pages instead of hanging if the server malfunctions.
     */
    suspend fun snapshotAll(api: HttpGymSyncApi, limit: Int = 50, maxPages: Int = 1_000): Pair<List<SyncChangeModel>, String> {
        val records = mutableListOf<SyncChangeModel>()
        val seenTokens = HashSet<String>()
        val seenKeys = HashSet<Pair<AggregateType, String>>()
        var token: String? = null
        var baseline: String? = null
        repeat(maxPages) {
            val page = api.snapshot(token, limit)
            if (baseline == null) baseline = page.baselineCursor
            assertEquals("Snapshot pages must share one baseline", baseline, page.baselineCursor)
            page.records.forEach { change ->
                require(seenKeys.add(change.aggregateType to change.aggregateId)) {
                    "Snapshot repeated an aggregate; refusing to page forever"
                }
            }
            records += page.records
            if (!page.hasMore) {
                assertNull("Terminal snapshot page must not carry a token", page.nextPageToken)
                return records to baseline!!
            }
            val next = page.nextPageToken
            assertNotNull("Non-terminal snapshot page requires a token", next)
            require(seenTokens.add(next!!)) {
                "Snapshot repeated a page token; refusing to page forever"
            }
            token = next
        }
        throw AssertionError("Snapshot did not terminate within $maxPages pages")
    }

    fun recordById(records: List<SyncChangeModel>, type: AggregateType, id: String): CanonicalRecord? =
        records.firstOrNull { it.aggregateType == type && it.aggregateId == id }?.record
}
