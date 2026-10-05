package com.dragosfotache.gymtracka.data.sync.engine

import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason

/**
 * Immutable safe summary of one bounded push delivery run. Counts only
 * atomically acknowledged mutations; HTTP 200, classification, consumed tokens
 * and uncertain commits are never counted. String forms show counts, stop
 * kinds, block reasons and retry times, never service-issued error codes, IDs,
 * payloads, batches or credentials.
 */
data class PushDeliveryResult(
    val acknowledgedMutations: Int,
    val stop: PushDeliveryStop,
) {
    override fun toString(): String =
        "PushDeliveryResult(acknowledgedMutations=$acknowledgedMutations, stop=$stop)"
}

/**
 * Why a bounded delivery run stopped. Empty is outbox emptiness at one fenced
 * boundary, not complete sync or convergence. LimitReached means the client
 * run-work budget was exhausted without probing a successor. Deferred carries
 * the absolute retry due time. Blocked preserves the exact service code in
 * [errorCode] without printing it; auth stops are read-only and resumable.
 */
sealed class PushDeliveryStop {
    data object Empty : PushDeliveryStop()

    data object LimitReached : PushDeliveryStop()

    data class Deferred(val retryAfterMillis: Long) : PushDeliveryStop()

    data class Blocked(
        val reason: PushBlockReason,
        val errorCode: String? = null,
    ) : PushDeliveryStop() {
        override fun toString(): String = "Blocked($reason)"
    }

    data object AuthenticationRequired : PushDeliveryStop()

    data object ReauthenticationRequired : PushDeliveryStop()
}
