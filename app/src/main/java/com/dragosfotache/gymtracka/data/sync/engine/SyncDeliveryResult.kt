package com.dragosfotache.gymtracka.data.sync.engine

/**
 * Immutable safe summary of one shared push-before-pull delivery run. Counts and
 * stop kinds only; no identities, tokens, cursors, bodies or service codes.
 *
 * A null [pull] means push stopped without authorizing pull. A null
 * [hasOutstandingLocalWork] means no returned successful valid-tail bookkeeping
 * commit, not a clean bill: it is never false-by-default. A nonnull flag is the
 * selected-account outbox presence observed inside that tail transaction; false
 * is not convergence, projection publication or absence of later edits.
 */
data class SyncDeliveryResult(
    val push: PushDeliveryResult,
    val pull: PullDeliveryResult?,
    val hasOutstandingLocalWork: Boolean?,
) {
    override fun toString(): String =
        "SyncDeliveryResult(push=$push, pull=$pull, " +
            "hasOutstandingLocalWork=$hasOutstandingLocalWork)"
}
