package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.engine.SnapshotStartResult
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryResult
import com.dragosfotache.gymtracka.data.sync.store.SnapshotBaselineResult

/**
 * Immutable safe summary of one private prepared bootstrap invocation.
 * [cacheEpoch] reflects only a known durable returned completion or the
 * current admitted run epoch, never promotion authority. Retained result
 * summaries are nonsecret progress. There is no public constructor or
 * copy; instances are created only by [PreparedSessionEngineCoordinator]
 * after commit and active resumption. Displays are fixed and redacted.
 */
internal class PreparedBootstrapResult internal constructor(
    val cacheEpoch: Long,
    val retainedStage: Boolean,
    val snapshot: SnapshotStartResult?,
    val baseline: SnapshotBaselineResult?,
    val delivery: SyncDeliveryResult?,
) {
    override fun toString(): String = "PreparedBootstrapResult"
}

/** Closed failures for private prepared engine admission and bootstrap. */
internal enum class PreparedEngineFailure {
    INVALID_INPUT,
    NOT_PREPARED,
    IDENTITY_MISMATCH,
    STALE_OPERATION,
    AUTHORITY_UNAVAILABLE,
    VAULT_UNAVAILABLE,
}

/**
 * Fixed-message prepared engine failure with closed cause and disabled
 * suppression, so no account, origin, operation, token, cursor, body or
 * raw error escapes through messages, causes or suppressed entries.
 */
internal class PreparedEngineException(val failure: PreparedEngineFailure) :
    RuntimeException("Prepared session engine failure", null, false, true) {
    override fun toString(): String = "PreparedEngineException(failure=$failure)"
}
