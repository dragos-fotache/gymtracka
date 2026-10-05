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
 * R08b independent QA fixtures. Hand-written from docs/archive/remaining-work/R08b-task.md
 * and the coordinator's source-independent API/harness facts only.
 *
 * Every expected value below is a literal authored here; no production encoder
 * output is used as an oracle and no production source was inspected. Durable
 * seeding reuses the vetted R06a claim/capture helpers, R06c acknowledgment
 * builders and R08a staging builders ([stagePage], [stageWorkoutUpsert],
 * [stageRoutineUpsert], [stageCustomUpsert], [stageDelete]); only
 * delivery-specific literals and scripted snapshot transports are new.
 */

// Fresh delivery literals: valid lowercase UUIDs, distinct from every
// CLAIM_*/PULL_*/PD_*/ACK_*/OUTCOME_*/SYNC_*/STAGE_* literal and from each
// other, so cross-run and foreign-capability tests use exact equality.
internal const val DELIVERY_EPOCH = 0L
internal const val DELIVERY_NOW_MILLIS = 1_790_258_700_000L

internal const val DELIVERY_ID_1 = "e0e0e0e0-1111-4111-8111-111111111111"
internal const val DELIVERY_ID_2 = "e0e0e0e0-2222-4222-8222-222222222222"
internal const val DELIVERY_ID_3 = "e0e0e0e0-3333-4333-8333-333333333333"
internal const val DELIVERY_ID_FOREIGN = "e0e0e0e0-4444-4444-8444-444444444444"

internal const val DELIVERY_BASELINE_1 = "delivery-baseline-1"
internal const val DELIVERY_BASELINE_2 = "delivery-baseline-2"
internal const val DELIVERY_TOKEN_1 = "delivery-token-1"
internal const val DELIVERY_TOKEN_2 = "delivery-token-2"
internal const val DELIVERY_TOKEN_3 = "delivery-token-3"
internal const val DELIVERY_WS_TOKEN = "  \t delivery-ws "

internal const val DELIVERY_LIMIT = 5

/** Wire account: valid canonical UUID for HTTP binding AND Room seeding in adapter tests.
 * CLAIM_ACCOUNT_A ("account-a") is Room-only, never an HTTP UUID. */
internal const val DELIVERY_WIRE_ACCOUNT = "a111b222-c333-4d55-8e66-f77788889999"

/** Deterministic fresh UUID for large continuation matrices. */
internal fun deliveryUuid(n: Int): String {
    require(n in 0..999999) { "delivery fixture index out of range" }
    return "f0000000-0000-4000-8000-" + "%012x".format(n.toLong())
}

/** Deterministic fresh opaque token for large continuation matrices. */
internal fun deliveryToken(n: Int): String = "delivery-token-$n"

internal data class RecordedSnapshotCall(val token: String?, val limit: Int)

/**
 * Scripted snapshot response. Never performs production HTTP.
 *
 * Pages are typed [SnapshotResultModel] values (including malformed typed
 * pages); failures are typed [SyncApiException]; unexpected is any other
 * [Throwable] for provenance tests.
 */
internal sealed interface SnapshotScriptResponse {
    data class Page(val page: SnapshotResultModel) : SnapshotScriptResponse
    data class Failure(val failure: SyncApiException) : SnapshotScriptResponse
    data class Unexpected(val error: Throwable) : SnapshotScriptResponse
}

/**
 * Combined push+snapshot script transport for the snapshot driver. Never
 * performs production HTTP.
 *
 * Records exact push identities and snapshot token/limit pairs, asserts every
 * call runs outside any Room transaction, then returns or throws the next
 * scripted response in order. Pull must never be invoked by this driver.
 */
internal class SnapshotDeliveryScriptApi(
    private val database: GymDatabase,
    private val pushResponses: MutableList<SyncPushScript>,
    private val snapshotResponses: MutableList<SnapshotScriptResponse>,
) : GymSyncApi {
    val pushCalls = mutableListOf<List<DurableRequestModel>>()
    val snapshotCalls = mutableListOf<RecordedSnapshotCall>()
    val seenPushInTransaction = mutableListOf<Boolean>()
    val seenSnapshotInTransaction = mutableListOf<Boolean>()

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        val inTx = database.inTransaction()
        seenPushInTransaction += inTx
        check(!inTx) { "push transport must run outside any Room transaction" }
        pushCalls += requests.toList()
        // Empty is ONLY a fenced Room claimHead observation, never HTTP
        // zero-request probing: any unsolicited push call must fail here.
        check(pushResponses.isNotEmpty()) { "unexpected push call $pushCalls" }
        return when (val next = pushResponses.removeFirst()) {
            is SyncPushScript.Respond -> next.result
            is SyncPushScript.Fail -> throw next.failure
            is SyncPushScript.Unexpected -> throw next.error
        }
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        val inTx = database.inTransaction()
        seenSnapshotInTransaction += inTx
        check(!inTx) { "snapshot transport must run outside any Room transaction" }
        snapshotCalls += RecordedSnapshotCall(pageToken, limit)
        check(snapshotResponses.isNotEmpty()) {
            "unexpected snapshot call $snapshotCalls (token=$pageToken limit=$limit)"
        }
        return when (val next = snapshotResponses.removeFirst()) {
            is SnapshotScriptResponse.Page -> next.page
            is SnapshotScriptResponse.Failure -> throw next.failure
            is SnapshotScriptResponse.Unexpected -> throw next.error
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        throw AssertionError("pull must not be called by the snapshot delivery driver")
    }
}

/**
 * Gated push+snapshot transport for fence/cancellation tests. Push gates on
 * [pushGate]; snapshot gates on [snapshotGate]. Never performs production HTTP.
 */
internal class SnapshotDeliveryGatedApi(
    private val database: GymDatabase,
    var pushAfter: SyncPushScript? = null,
    var snapshotAfter: SnapshotScriptResponse,
) : GymSyncApi {
    val pushEntered = CompletableDeferred<Unit>()
    val pushGate = CompletableDeferred<Unit>()
    val snapshotEntered = CompletableDeferred<Unit>()
    val snapshotGate = CompletableDeferred<Unit>()
    val pushCalls = mutableListOf<List<DurableRequestModel>>()
    val snapshotCalls = mutableListOf<RecordedSnapshotCall>()

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        check(!database.inTransaction()) { "push transport must run outside any Room transaction" }
        pushCalls += requests.toList()
        pushEntered.complete(Unit)
        pushGate.await()
        // No zero-request tolerance: Empty is a fenced Room observation,
        // never an HTTP probe. Unsolicited push must fail.
        return when (val next = checkNotNull(pushAfter) { "push must be scripted for gated api" }) {
            is SyncPushScript.Respond -> next.result
            is SyncPushScript.Fail -> throw next.failure
            is SyncPushScript.Unexpected -> throw next.error
        }
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        check(!database.inTransaction()) { "snapshot transport must run outside any Room transaction" }
        snapshotCalls += RecordedSnapshotCall(pageToken, limit)
        snapshotEntered.complete(Unit)
        snapshotGate.await()
        return when (val next = snapshotAfter) {
            is SnapshotScriptResponse.Page -> next.page
            is SnapshotScriptResponse.Failure -> throw next.failure
            is SnapshotScriptResponse.Unexpected -> throw next.error
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        throw AssertionError("pull must not be called by the snapshot delivery driver")
    }
}

/** Redaction oracle for driver/result displays: no IDs, tokens, baselines or bodies may appear. */
internal fun deliveryAssertRedacted(text: String, label: String) {
    val secrets = listOf(
        DELIVERY_ID_1, DELIVERY_ID_2, DELIVERY_ID_3, DELIVERY_ID_FOREIGN,
        STAGE_ID_1, STAGE_ID_2, STAGE_ID_3, STAGE_ID_FOREIGN,
        DELIVERY_BASELINE_1, DELIVERY_BASELINE_2,
        STAGE_BASELINE_1, STAGE_BASELINE_2,
        DELIVERY_TOKEN_1, DELIVERY_TOKEN_2, DELIVERY_TOKEN_3,
        STAGE_TOKEN_1, STAGE_TOKEN_2, STAGE_TOKEN_3,
        STAGE_SYNC_CURSOR, STAGE_SYNC_CURSOR_2,
        STAGE_WORKOUT_1, STAGE_WORKOUT_2, STAGE_WORKOUT_3,
        STAGE_ROUTINE_1, STAGE_ROUTINE_2, STAGE_CUSTOM_1, STAGE_CUSTOM_2,
        CLAIM_ACCOUNT_A, CLAIM_ACCOUNT_B,
        DELIVERY_WIRE_ACCOUNT,
        CLAIM_WORKOUT_A, CLAIM_MUTATION_1,
        "Staged Workout", "Staged Routine", "Staged Custom",
        "Delivery Workout",
        "invalid_snapshot_token", "snapshot_expired", "future_permanent_code",
        "cursor_expired", "invalid_cursor",
    )
    secrets.forEach { secret ->
        check(!text.contains(secret)) { "$label display leaks [$secret] in [$text]" }
    }
    check(text.isNotBlank()) { "$label display must be non-blank" }
}
