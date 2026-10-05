package com.dragosfotache.gymtracka.data.sync.engine

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException

/**
 * Immutable safe summary of one bounded incremental pull run. Counts only
 * atomically committed complete batches; admitted bytes, retired tokens and
 * uncertain commits are never counted. String forms show counts and stop kinds
 * only, never cursors, batch IDs, bodies or credentials.
 */
data class PullDeliveryResult(
    val committedBatches: Int,
    val stop: PullDeliveryStop,
) {
    override fun toString(): String =
        "PullDeliveryResult(committedBatches=$committedBatches, stop=$stop)"
}

/**
 * Why a bounded pull run stopped. Tail is HTTP-observed exhaustion at one fenced
 * boundary, not account convergence. LimitReached means the client page budget was
 * exhausted without probing a successor. RecoveryRequired asks for baseline work
 * or a full resync, never a null-cursor bootstrap. Failed carries the exact typed
 * transport failure without printing it.
 */
sealed class PullDeliveryStop {
    data object Tail : PullDeliveryStop()

    data object LimitReached : PullDeliveryStop()

    data class RecoveryRequired(val reason: PullRecoveryReason) : PullDeliveryStop()

    class Failed(val failure: SyncApiException) : PullDeliveryStop() {
        override fun toString(): String = "Failed"
    }
}

/** Why a pull run needs recovery instead of reporting convergence or failure. */
enum class PullRecoveryReason {
    MISSING_BASELINE_OR_CURSOR,
    CURSOR_EXPIRED,
}
