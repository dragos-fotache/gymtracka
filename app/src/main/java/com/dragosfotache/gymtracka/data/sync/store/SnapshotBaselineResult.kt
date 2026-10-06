package com.dragosfotache.gymtracka.data.sync.store

/**
 * Immutable safe summary of one committed complete-snapshot baseline. Instances
 * are created only by the admitted [PushRun] baseline transaction after commit
 * and active resumption; there is no public constructor or copy. Counts report
 * durable stage page/record totals and are not another authority. The string
 * form is the fixed type name only, never epochs, cursors, counts or identity.
 */
class SnapshotBaselineResult internal constructor(
    val cacheEpoch: Long,
    val baselineCursor: String,
    val pageCount: Long,
    val recordCount: Long,
    val hasOutstandingLocalWork: Boolean,
) {
    override fun toString(): String = "SnapshotBaselineResult"
}
