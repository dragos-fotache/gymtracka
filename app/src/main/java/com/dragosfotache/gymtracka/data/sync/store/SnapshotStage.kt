package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.SyncSnapshotStageEntity

/**
 * Opaque capability for one durable frozen-snapshot stage. Instances are created
 * only by the admitted [PushRun] stage operations; there is no public constructor
 * or copy and tokens use referential identity. The token binds the originating
 * live run, the account/session/epoch fences and the complete observed immutable
 * header. An appendable token additionally binds the origin fence and source
 * observation; a recovery observation ([canAppend] false) only permits explicit
 * abandonment under the current fences, never resumed appends.
 *
 * The exposed fields are immutable snapshots. The string form never prints IDs,
 * tokens or bodies.
 */
class SnapshotStage internal constructor(
    internal val run: PushRun,
    internal val accountId: String,
    internal val sessionGeneration: Long,
    internal val expectedCacheEpoch: Long,
    internal val header: SyncSnapshotStageEntity,
    val stageId: String,
    val pageLimit: Int,
    val baselineCursor: String?,
    val nextPageToken: String?,
    val downloaded: Boolean,
    val pageCount: Long,
    val recordCount: Long,
    val canAppend: Boolean,
) {
    override fun toString(): String = "SnapshotStage"
}

/** The snapshot stage token no longer matches the admitted run or durable header. */
class StaleSnapshotStageException : IllegalStateException("Stale snapshot stage")

/** Owned SQLite or retained-evidence failures from snapshot staging. Fixed safe message. */
class SnapshotStageStorageException : IllegalStateException("Snapshot stage storage operation failed")

/** A durable selected-account stage requires explicit completion or abandonment. */
class SnapshotStageActiveException :
    IllegalStateException("Snapshot stage requires explicit completion or abandonment")

/** Snapshot staging requires a selected account with an empty outbox. */
class SnapshotStageIntentException :
    IllegalStateException("Snapshot staging requires an empty account outbox")
