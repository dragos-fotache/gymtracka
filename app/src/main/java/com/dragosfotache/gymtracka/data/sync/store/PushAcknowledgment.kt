package com.dragosfotache.gymtracka.data.sync.store

/** Fixed safe persistence for invalid acknowledgment context. */
internal const val ACK_CONTEXT_CORRUPT_CODE = "ack_context_corrupt"

/** Fixed safe persistence for invalid acknowledgment context. */
internal const val ACK_CONTEXT_CORRUPT_MESSAGE = "Push acknowledgment context was invalid; recovery required."

/** Fixed binding rejection when an accepted snapshot does not match its claim. */
internal const val ACCEPTED_CLAIM_MISMATCH_MESSAGE = "Accepted outcome does not match push claim"

/**
 * Outcome of one atomic canonical acknowledgment. Acknowledged carries the applied
 * canonical batch counts and the physical affected-root count changed from pending
 * to synced at exactly their frozen local generation. String forms never print
 * batch bodies, IDs or service codes.
 */
sealed class PushAcknowledgmentResult {
    /**
     * Complete accepted canonical batch applied, exact frozen row removed and
     * generation-scoped local metadata acknowledged in one transaction.
     */
    data class Acknowledged(
        val canonical: CanonicalBatchResult,
        val acknowledgedRoots: Int,
    ) : PushAcknowledgmentResult() {
        override fun toString(): String = "Acknowledged"
    }

    /** Invalid local acknowledgment context retained as a safe frozen error. */
    data object RetainedCorruption : PushAcknowledgmentResult()
}
