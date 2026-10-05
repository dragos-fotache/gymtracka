package com.dragosfotache.gymtracka.data.sync.store

/**
 * Opaque immutable callback authority for one eligible incremental pull boundary.
 * There is no public constructor or copy; instances use referential identity and
 * privately bind the originating live run, account, generation, epoch and the
 * complete observed sync-state row (cursor plus last-success). The cursor string
 * stays opaque: this seam never parses, trims, sorts or lexically compares it.
 * String forms never print cursors, accounts or domain bodies.
 */
class PullCheckpoint internal constructor(
    internal val run: PushRun,
    internal val accountId: String,
    internal val sessionGeneration: Long,
    internal val expectedCacheEpoch: Long,
    val cursor: String,
    internal val lastSuccessMillis: Long?,
) {
    override fun toString(): String = "PullCheckpoint"
}

/**
 * Immutable owned commit summary for one atomic next-batch commit. The carried
 * checkpoint is the fresh token binding the new durable cursor. The string form
 * never prints tokens, identities or domain bodies.
 */
data class PullBatchCommitResult(
    val canonical: CanonicalBatchResult,
    val checkpoint: PullCheckpoint,
) {
    override fun toString(): String = "PullBatchCommitResult"
}

/** The pull checkpoint boundary no longer matches the admitted token. Fixed safe message. */
class StalePullCheckpointException : IllegalStateException("Stale pull checkpoint")

/** Owned SQLite failures from pull-checkpoint operations. Fixed safe message, no raw cause. */
class PullStorageException : IllegalStateException("Pull checkpoint storage operation failed")
