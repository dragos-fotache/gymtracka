package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel

/**
 * Outcome of one explicit-head claim attempt. Blocked, deferred and empty results
 * never change any row, error, generation or bookkeeping.
 */
sealed class PushHeadResult {
    data object Empty : PushHeadResult()
    data class Deferred(val retryAfterMillis: Long) : PushHeadResult()
    data class Blocked(
        val reason: PushBlockReason,
        val errorCode: String? = null,
    ) : PushHeadResult() {
        override fun toString(): String = "Blocked($reason)"
    }
    data class Claimed(val claim: PushClaim) : PushHeadResult() {
        override fun toString(): String = "Claimed"
    }
}

/** Why an explicit head cannot be admitted for transport. */
enum class PushBlockReason {
    REJECTED,
    RETAINED_ERROR,
    UNSUPPORTED_VERSION,
    CORRUPT,
    ATTEMPTS_EXHAUSTED,
    /**
     * A durable selected-account snapshot stage excludes ordinary push/pull
     * until explicit completion or abandonment.
     */
    SNAPSHOT_STAGE,
}

/**
 * Opaque admission capability for one frozen explicit head. The claim binds the
 * originating live run, the account/session/epoch fences, the frozen row snapshot
 * and the exact durable request. It is not caller-forgeable acknowledgment
 * authority: there is no public constructor or copy.
 *
 * The exposed request is defensively owned. Every access freshly decodes the
 * private exact wrapper snapshot, so mutating any exposed decoded list never
 * changes the retained claim identity or later request getters.
 */
class PushClaim internal constructor(
    internal val run: PushRun,
    val accountId: String,
    val sessionGeneration: Long,
    val expectedCacheEpoch: Long,
    val row: SyncOutboxEntity,
) {
    val request: DurableRequestModel
        get() = decodeDurableRequest(parseStrictJsonObject(row.payloadJson))

    override fun toString(): String = "PushClaim"
}

/** The current explicit head no longer matches an admitted claim. Fixed safe message. */
class StalePushClaimException : IllegalStateException("Stale push claim")

/** Owned SQLite failures from claim operations. Fixed safe message, no raw cause. */
class PushStorageException : IllegalStateException("Push storage failure")
