package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadResult
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryResult
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationResult

/**
 * ACTIVE reconciliation stops. TAIL means a clean valid tail plus
 * successful/unchanged publication with no outstanding local work.
 * PROTECTED means new local intent/claimed rest postponed projection and the
 * entire visible graph is preserved. PUSH_STOPPED/PULL_STOPPED are bounded
 * typed delivery stops without snapshot/publication. SNAPSHOT_STOPPED retains
 * staged evidence after a typed download failure/budget stop.
 * SNAPSHOT_RESTART_REQUIRED retains the stage after authentic expiry.
 * RETAINED_STAGE means a durable selected stage requires explicit restart.
 */
internal enum class AppReconciliationStop {
    TAIL,
    PROTECTED,
    PUSH_STOPPED,
    PULL_STOPPED,
    SNAPSHOT_STOPPED,
    SNAPSHOT_RESTART_REQUIRED,
    RETAINED_STAGE,
}

/**
 * Immutable safe summary of one guarded ACTIVE reconciliation invocation.
 * Fields are observations, not authority. Displays are fixed/redacted and
 * expose no tokens, cursors, bodies, failures or credentials.
 */
internal class AppReconciliationResult internal constructor(
    val stop: AppReconciliationStop,
    val cacheEpoch: Long,
    val delivery: SyncDeliveryResult?,
    val snapshot: SnapshotDownloadResult?,
    val publication: ProjectionPublicationResult?,
    val retainedStageId: String?,
) {
    override fun toString(): String = "AppReconciliationResult(stop=$stop)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppReconciliationResult) return false
        return stop == other.stop &&
            cacheEpoch == other.cacheEpoch &&
            delivery == other.delivery &&
            publication == other.publication &&
            retainedStageId == other.retainedStageId &&
            snapshotStopKind() == other.snapshotStopKind()
    }

    override fun hashCode(): Int {
        var result = stop.hashCode()
        result = 31 * result + cacheEpoch.hashCode()
        result = 31 * result + (delivery?.hashCode() ?: 0)
        result = 31 * result + snapshotStopKind().hashCode()
        result = 31 * result + (publication?.hashCode() ?: 0)
        result = 31 * result + (retainedStageId?.hashCode() ?: 0)
        return result
    }

    private fun snapshotStopKind(): String =
        snapshot?.stop?.let { it::class.simpleName ?: "Unknown" } ?: "None"
}
