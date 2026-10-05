package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.SyncStateEntity

/**
 * Focused pull-checkpoint row predicates shared by the [PushRun] pull seam.
 * Pure helpers only: no Room transactions, run admission or network here.
 * All failures use fixed safe messages without cursors, identities or bodies.
 */

/**
 * Fails closed on corrupt retained cursor rows. A present row with an empty (not
 * whitespace-only) nonnull cursor or a negative nonnull last-success is corrupt
 * and never repaired. Opaque whitespace tokens are preserved exactly, never
 * trimmed or rejected as blank.
 */
internal fun validatePullSyncState(state: SyncStateEntity) {
    val cursor = state.cursor
    if (cursor != null && cursor.isEmpty()) {
        throw PullStorageException()
    }
    val lastSuccess = state.lastSuccessMillis
    if (lastSuccess != null && lastSuccess < 0L) {
        throw PullStorageException()
    }
}

/**
 * Exact full-row admission match for check/commit entry. A missing row or any
 * cursor/last-success difference is stale. Changed session/epoch fences keep
 * their existing errors; this predicate only compares the observed row.
 */
internal fun requireExactPullState(observed: SyncStateEntity, current: SyncStateEntity?) {
    if (current != observed) {
        throw StalePullCheckpointException()
    }
}

/**
 * Post-write advancement expectation for atomic batch commits. The cursor must
 * carry exactly the committed batch cursor while last-success stays unchanged.
 * Trigger-induced tampering is a stale pull boundary, like the pre-write checks:
 * the whole transaction fails while the cleared token stays retired and storage
 * rolls back if uncommitted.
 */
internal fun requireAdvancedPullState(
    observed: SyncStateEntity,
    batchCursor: String,
    current: SyncStateEntity?,
) {
    if (current == null ||
        current.accountId != observed.accountId ||
        current.cursor != batchCursor ||
        current.lastSuccessMillis != observed.lastSuccessMillis
    ) {
        throw StalePullCheckpointException()
    }
}
