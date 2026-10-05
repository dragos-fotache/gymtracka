package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import kotlinx.coroutines.CompletableDeferred

/**
 * R07b independent QA fixtures. Hand-written from docs/archive/remaining-work/R07b-task.md
 * and the coordinator's QA harness facts only.
 *
 * Every expected value below is a literal authored here; no production encoder
 * output is used as an oracle. Durable seeding reuses the vetted R07a
 * pull-seam helpers ([pullSeedBaseline], [pullBatch], [pullUpsert],
 * [pullDelete], [pullWorkoutRecord], [pullRoutineRecord], [pullCustomRecord])
 * and the R06a claim/capture helpers; only pull-driver-specific scripted
 * transport builders and fresh cursor/batch literals are new. No production
 * declaration, implementation, generated production code, diff or Worker
 * output was inspected.
 */

// Fresh driver literals: distinct from every PULL_* literal and from each
// other, so cross-page cycle tests can use exact opaque equality.
internal const val PD_CURSOR_5 = "pull-cursor-5"
internal const val PD_CURSOR_6 = "pull-cursor-6"
internal const val PD_CURSOR_7 = "pull-cursor-7"
internal const val PD_CURSOR_8 = "pull-cursor-8"

internal const val PD_BATCH_4 = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeee0004"
internal const val PD_BATCH_5 = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeee0005"
internal const val PD_BATCH_6 = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeee0006"
internal const val PD_BATCH_7 = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeee0007"
internal const val PD_BATCH_8 = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeee0008"

internal data class RecordedPullCall(val cursor: String?, val limit: Int)

/**
 * Scripted pull transport. Never performs production HTTP.
 *
 * Responses are scripted directly as typed [PullResultModel] pages (including
 * malformed typed pages) or typed [SyncApiException] failures, or an
 * unexpected [Throwable] for provenance tests. Each [pull] call records its
 * exact opaque cursor/limit pair, asserts HTTP runs outside any Room
 * transaction, then returns or throws the next scripted response in order.
 * Push and snapshot must never be called by the pull driver.
 */
internal sealed interface PullScriptResponse {
    data class Page(val page: PullResultModel) : PullScriptResponse
    data class Failure(val failure: SyncApiException) : PullScriptResponse
    data class Unexpected(val error: Throwable) : PullScriptResponse
}

internal class PullDeliveryScriptApi(
    private val database: GymDatabase,
    private val responses: MutableList<PullScriptResponse>,
) : GymSyncApi {
    val calls = mutableListOf<RecordedPullCall>()
    val seenInTransaction = mutableListOf<Boolean>()

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        val inTx = database.inTransaction()
        seenInTransaction += inTx
        check(!inTx) { "pull transport must run outside any Room transaction" }
        calls += RecordedPullCall(cursor, limit)
        check(responses.isNotEmpty()) { "unexpected pull call #$calls (cursor=$cursor limit=$limit)" }
        return when (val next = responses.removeFirst()) {
            is PullScriptResponse.Page -> next.page
            is PullScriptResponse.Failure -> throw next.failure
            is PullScriptResponse.Unexpected -> throw next.error
        }
    }

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        throw AssertionError("push must not be called by the pull delivery driver")
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        throw AssertionError("snapshot must not be called by the pull delivery driver")
    }
}

/** Builds a typed pull page directly; caller owns the nested lists. */
internal fun pullDeliveryPage(
    batches: List<SyncBatchModel>,
    nextCursor: String,
    hasMore: Boolean,
): PullResultModel = PullResultModel(batches = batches, nextCursor = nextCursor, hasMore = hasMore)

/** Builds a typed pull page with caller-mutable nested lists for ownership tests. */
internal fun pullDeliveryMutablePage(
    batches: MutableList<SyncBatchModel>,
    nextCursor: String,
    hasMore: Boolean,
): PullResultModel = PullResultModel(batches = batches, nextCursor = nextCursor, hasMore = hasMore)

/** Builds a batch with caller-mutable changes for ownership tests. */
internal fun pullDeliveryMutableBatch(
    changes: MutableList<com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel>,
    batchId: String,
    cursor: String,
): SyncBatchModel = SyncBatchModel(batchId = batchId, cursor = cursor, changes = changes)

/**
 * Gated pull transport for fence/cancellation tests. Never performs
 * production HTTP. Records exact opaque cursor/limit pairs outside Room,
 * signals entry, then suspends on [gate] before returning or throwing the
 * configured [after] response.
 */
internal class PullDeliveryGatedApi(
    private val database: GymDatabase,
    var after: PullScriptResponse,
) : GymSyncApi {
    val entered = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    val calls = mutableListOf<RecordedPullCall>()

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        check(!database.inTransaction()) { "pull transport must run outside any Room transaction" }
        calls += RecordedPullCall(cursor, limit)
        entered.complete(Unit)
        gate.await()
        return when (val next = after) {
            is PullScriptResponse.Page -> next.page
            is PullScriptResponse.Failure -> throw next.failure
            is PullScriptResponse.Unexpected -> throw next.error
        }
    }

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        throw AssertionError("push must not be called by the pull delivery driver")
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        throw AssertionError("snapshot must not be called by the pull delivery driver")
    }
}

/** Redaction oracle for driver displays: no cursor, batch, account or domain body may appear. */
internal fun pullDeliveryAssertRedacted(text: String, label: String) {
    val secrets = listOf(
        PULL_CURSOR_1, PULL_CURSOR_2, PULL_CURSOR_3, PULL_CURSOR_4,
        PD_CURSOR_5, PD_CURSOR_6, PD_CURSOR_7, PD_CURSOR_8,
        PULL_OPAQUE_CURSOR, PULL_WHITESPACE_CURSOR,
        PULL_BATCH_1, PULL_BATCH_2, PULL_BATCH_3,
        PD_BATCH_4, PD_BATCH_5, PD_BATCH_6, PD_BATCH_7, PD_BATCH_8,
        PULL_WORKOUT_A, PULL_WORKOUT_B, PULL_ROUTINE_A, PULL_CUSTOM_A,
        PULL_UNKNOWN_CLOUD, PULL_FOREIGN_WORKOUT,
        PULL_ACCOUNT_A, PULL_ACCOUNT_B,
        "Pulled Workout", "Pulled Routine", "Pulled Custom",
        "cursor_expired", "invalid_cursor", "future_permanent_code",
    )
    secrets.forEach { secret ->
        check(!text.contains(secret)) { "$label display leaks [$secret] in [$text]" }
    }
    check(text.isNotBlank()) { "$label display must be non-blank" }
}
