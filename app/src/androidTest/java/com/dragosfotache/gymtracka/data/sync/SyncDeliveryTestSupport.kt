package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import kotlinx.coroutines.CompletableDeferred

/**
 * R07c independent QA fixtures. Hand-written from docs/archive/remaining-work/R07c-task.md
 * and the coordinator's harness facts only.
 *
 * Every expected value below is a literal authored here; no production encoder
 * output is used as an oracle. Durable seeding reuses the vetted R06a
 * claim/capture helpers and R06c acknowledgment builders plus the R07a
 * pull-seam helpers and R07b pull-driver script types; only sync-driver
 * literals and the combined push+pull script transport are new. No production
 * declaration, implementation, generated code, diff or Worker output was
 * inspected.
 */

// Distinct sync-driver literals: never overlap PULL_*/PD_*/ACK_*/CLAIM_* tokens.
internal const val SYNC_EPOCH = 0L
internal const val SYNC_NOW_MILLIS = 1_790_258_600_000L
internal const val SYNC_CURSOR_TAIL = "sync-cursor-tail"
internal const val SYNC_CURSOR_MID = "sync-cursor-mid"
internal const val SYNC_BATCH_S1 = "cccccccc-bbbb-cccc-dddd-eeeeeeee00a1"
internal const val SYNC_BATCH_S2 = "cccccccc-bbbb-cccc-dddd-eeeeeeee00a2"

internal sealed interface SyncPushScript {
    data class Respond(val result: PushResultModel) : SyncPushScript
    data class Fail(val failure: SyncApiException) : SyncPushScript
    data class Unexpected(val error: Throwable) : SyncPushScript
}

/**
 * Combined push+pull script transport for the shared driver. Never performs
 * production HTTP. Records exact push identities and pull cursor/limit pairs,
 * asserts every call runs outside any Room transaction, then returns or throws
 * the next scripted response in order. Snapshot must never be invoked.
 */
internal class SyncDeliveryScriptApi(
    private val database: GymDatabase,
    private val pushResponses: MutableList<SyncPushScript>,
    private val pullResponses: MutableList<PullScriptResponse>,
) : GymSyncApi {
    val pushCalls = mutableListOf<List<DurableRequestModel>>()
    val pullCalls = mutableListOf<RecordedPullCall>()
    val seenPushInTransaction = mutableListOf<Boolean>()
    val seenPullInTransaction = mutableListOf<Boolean>()

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        val inTx = database.inTransaction()
        seenPushInTransaction += inTx
        check(!inTx) { "push transport must run outside any Room transaction" }
        pushCalls += requests.toList()
        check(pushResponses.isNotEmpty()) { "unexpected push call $pushCalls" }
        return when (val next = pushResponses.removeFirst()) {
            is SyncPushScript.Respond -> next.result
            is SyncPushScript.Fail -> throw next.failure
            is SyncPushScript.Unexpected -> throw next.error
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        val inTx = database.inTransaction()
        seenPullInTransaction += inTx
        check(!inTx) { "pull transport must run outside any Room transaction" }
        pullCalls += RecordedPullCall(cursor, limit)
        check(pullResponses.isNotEmpty()) { "unexpected pull call $pullCalls" }
        return when (val next = pullResponses.removeFirst()) {
            is PullScriptResponse.Page -> next.page
            is PullScriptResponse.Failure -> throw next.failure
            is PullScriptResponse.Unexpected -> throw next.error
        }
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        throw AssertionError("snapshot must not be called by the sync delivery driver")
    }
}

/**
 * Gated combined transport for ownership/cancellation tests. Push gates on
 * [pushGate]; pull gates on [pullGate]. Never performs production HTTP.
 */
internal class SyncDeliveryGatedApi(
    private val database: GymDatabase,
    var pushAfter: SyncPushScript,
    var pullAfter: PullScriptResponse,
) : GymSyncApi {
    val pushEntered = CompletableDeferred<Unit>()
    val pushGate = CompletableDeferred<Unit>()
    val pullEntered = CompletableDeferred<Unit>()
    val pullGate = CompletableDeferred<Unit>()
    val pushCalls = mutableListOf<List<DurableRequestModel>>()
    val pullCalls = mutableListOf<RecordedPullCall>()
    var maxConcurrentPush = 0
    private var concurrentPush = 0

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        check(!database.inTransaction()) { "push transport must run outside any Room transaction" }
        pushCalls += requests.toList()
        concurrentPush += 1
        maxConcurrentPush = maxOf(maxConcurrentPush, concurrentPush)
        try {
            pushEntered.complete(Unit)
            pushGate.await()
            return when (val next = pushAfter) {
                is SyncPushScript.Respond -> next.result
                is SyncPushScript.Fail -> throw next.failure
                is SyncPushScript.Unexpected -> throw next.error
            }
        } finally {
            concurrentPush -= 1
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        check(!database.inTransaction()) { "pull transport must run outside any Room transaction" }
        pullCalls += RecordedPullCall(cursor, limit)
        pullEntered.complete(Unit)
        pullGate.await()
        return when (val next = pullAfter) {
            is PullScriptResponse.Page -> next.page
            is PullScriptResponse.Failure -> throw next.failure
            is PullScriptResponse.Unexpected -> throw next.error
        }
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        throw AssertionError("snapshot must not be called by the sync delivery driver")
    }
}

/** Redaction oracle for sync-driver displays: no cursor, batch, account or domain body may appear. */
internal fun syncAssertRedacted(text: String, label: String) {
    val secrets = listOf(
        PULL_CURSOR_1, PULL_CURSOR_2, PULL_CURSOR_3, PULL_CURSOR_4,
        SYNC_CURSOR_TAIL, SYNC_CURSOR_MID,
        PULL_BATCH_1, PULL_BATCH_2, PULL_BATCH_3,
        SYNC_BATCH_S1, SYNC_BATCH_S2,
        PULL_WORKOUT_A, PULL_ROUTINE_A, PULL_CUSTOM_A, PULL_UNKNOWN_CLOUD,
        CLAIM_WORKOUT_A, CLAIM_MUTATION_1, CLAIM_MUTATION_2,
        PULL_ACCOUNT_A, PULL_ACCOUNT_B, CLAIM_ACCOUNT_A,
        "Pulled Workout", "Ack Workout", "Sync Workout",
        "cursor_expired", "invalid_cursor", "future_permanent_code",
    )
    secrets.forEach { secret ->
        check(!text.contains(secret)) { "$label display leaks [$secret] in [$text]" }
    }
    check(text.isNotBlank()) { "$label display must be non-blank" }
}
