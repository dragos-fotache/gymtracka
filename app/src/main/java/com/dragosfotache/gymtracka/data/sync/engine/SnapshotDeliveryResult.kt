package com.dragosfotache.gymtracka.data.sync.engine

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStage

/**
 * Immutable safe summary of one bounded snapshot start. A null [download] means
 * push stopped without authorizing snapshot work. The string form is the fixed
 * type name only, never counts, tokens, cursors, bodies or service codes.
 */
class SnapshotStartResult internal constructor(
    val push: PushDeliveryResult,
    val download: SnapshotDownloadResult?,
) {
    override fun toString(): String = "SnapshotStartResult"
}

/**
 * Immutable safe summary of one bounded snapshot download invocation. Counts
 * only atomically committed whole pages/records in THIS invocation; admitted
 * bytes, retired tokens and uncertain commits are never counted. [stage] is the
 * exact latest returned stage capability (nonappendable when downloaded), not
 * a durable resume grant.
 * The string form is the fixed type name only, never counts, tokens, cursors,
 * bodies or credentials.
 */
class SnapshotDownloadResult internal constructor(
    val stage: SnapshotStage,
    val committedPages: Int,
    val committedRecords: Int,
    val stop: SnapshotDownloadStop,
    internal val driver: SnapshotDeliveryLoop,
) {
    override fun toString(): String = "SnapshotDownloadResult"
}

/**
 * Why a bounded snapshot download invocation stopped. Downloaded proves actual
 * validated terminal transport/page commit for this uninterrupted invocation
 * chain only, not complete account graph, baseline, absence, publication or
 * last-success. LimitReached means the client page budget was exhausted without
 * probing a successor. RestartRequired means an authentic snapshot expiry with
 * staged pages retained for explicit abandon/restart policy outside the driver.
 * Failed carries the exact typed transport failure without printing it.
 */
sealed interface SnapshotDownloadStop {
    data object Downloaded : SnapshotDownloadStop

    data object LimitReached : SnapshotDownloadStop

    data object RestartRequired : SnapshotDownloadStop

    class Failed(val failure: SyncApiException) : SnapshotDownloadStop {
        override fun toString(): String = "Failed"
    }
}
