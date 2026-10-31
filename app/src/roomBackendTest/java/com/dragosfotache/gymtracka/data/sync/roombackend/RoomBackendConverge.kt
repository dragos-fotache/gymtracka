package com.dragosfotache.gymtracka.data.sync.roombackend

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStage
import java.util.UUID

/**
 * Shared admitted engine runs for RoomBackend scenarios. Spec composition only:
 * one uninterrupted admitted run per physical DB, initial/explicit resync only
 * after a real fenced push Empty, complete only an actual Downloaded baseline,
 * eligible-tail delivery plus paired publication.
 */
internal data class RoomBackendPublication(
    val status: ProjectionPublicationStatus,
    val cacheEpoch: Long,
    val materializedEpoch: Long,
)

internal data class RoomBackendDelivery(
    val pushStop: PushDeliveryStop,
    val acknowledgedMutations: Int,
    val pullStop: PullDeliveryStop?,
    val committedBatches: Int?,
    val hasOutstandingLocalWork: Boolean?,
)

internal suspend fun roomBackendSnapshotConverge(
    store: RoomBackendStore,
    accountId: String,
    api: GymSyncApi,
    lease: RoomBackendTestLease,
    pageLimit: Int = 1,
    maxPages: Int = 100,
    nowMillis: () -> Long = System::currentTimeMillis,
): RoomBackendPublication {
    var publication: RoomBackendPublication? = null
    val pushStore = PushStore(store.database, store.session, store.owner)
    pushStore.withRun(roomBackendDurableEpoch(store.database, accountId)) { run ->
        val snapshot = SnapshotDeliveryLoop(pushStore, api, nowMillis)
        val start = snapshot.start(run, UUID.randomUUID().toString(), maxPages = maxPages, pageLimit = pageLimit)
        check(start.push.stop == PushDeliveryStop.Empty) {
            "RoomBackend resync requires a real fenced push Empty, got [${start.push.stop}]"
        }
        var download = checkNotNull(start.download) { "RoomBackend resync requires a present download" }
        while (download.stop == SnapshotDownloadStop.LimitReached) {
            download = snapshot.continueDownload(run, download, maxPages = maxPages)
        }
        check(download.stop == SnapshotDownloadStop.Downloaded) {
            "RoomBackend resync completes only an actual Downloaded baseline, got [${download.stop}]"
        }
        snapshot.complete(run, download)
        val delivery = SyncDeliveryLoop(pushStore, api, nowMillis).deliver(run)
        val pull = checkNotNull(delivery.pull) { "RoomBackend resync requires a pull result" }
        check(pull.stop == PullDeliveryStop.Tail) {
            "RoomBackend resync requires a valid Tail, got [${pull.stop}]"
        }
        val published = run.publishProjection(
            ProjectionStore(store.database, store.session, RoomBackendRecordingBarrier(lease)),
        )
        publication = RoomBackendPublication(published.status, published.cacheEpoch, published.materializedEpoch)
    }
    return checkNotNull(publication) { "RoomBackend admitted run must produce a publication" }
}

/**
 * Ordinary delivery plus paired publication only after an eligible tail.
 * Returns a null publication when no valid Tail was observed.
 */
internal suspend fun roomBackendDeliverAndPublish(
    store: RoomBackendStore,
    accountId: String,
    api: GymSyncApi,
    lease: RoomBackendTestLease,
    nowMillis: () -> Long = System::currentTimeMillis,
): Pair<RoomBackendDelivery, RoomBackendPublication?> {
    var result: Pair<RoomBackendDelivery, RoomBackendPublication?>? = null
    val pushStore = PushStore(store.database, store.session, store.owner)
    pushStore.withRun(roomBackendDurableEpoch(store.database, accountId)) { run ->
        val delivery = SyncDeliveryLoop(pushStore, api, nowMillis).deliver(run)
        val observed = RoomBackendDelivery(
            pushStop = delivery.push.stop,
            acknowledgedMutations = delivery.push.acknowledgedMutations,
            pullStop = delivery.pull?.stop,
            committedBatches = delivery.pull?.committedBatches,
            hasOutstandingLocalWork = delivery.hasOutstandingLocalWork,
        )
        val publication = if (delivery.pull?.stop == PullDeliveryStop.Tail) {
            val published = run.publishProjection(
                ProjectionStore(store.database, store.session, RoomBackendRecordingBarrier(lease)),
            )
            RoomBackendPublication(published.status, published.cacheEpoch, published.materializedEpoch)
        } else {
            null
        }
        result = observed to publication
    }
    return checkNotNull(result) { "RoomBackend admitted run must produce a delivery" }
}

/** Full quiescence predicates for one store: valid Tail with nothing outstanding,
 * empty outbox/staging, complete baseline, clean projection, matching epochs and
 * a live checkpoint. Call for BOTH stores. */
internal suspend fun roomBackendAssertQuiescentTail(
    store: RoomBackendStore,
    accountId: String,
    api: GymSyncApi,
    label: String,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val pushStore = PushStore(store.database, store.session, store.owner)
    val result = SyncDeliveryLoop(pushStore, api, nowMillis).deliver(roomBackendDurableEpoch(store.database, accountId))
    check(result.push.stop == PushDeliveryStop.Empty) {
        "$label expected quiescent push Empty, got [${result.push.stop}]"
    }
    check(result.pull?.stop == PullDeliveryStop.Tail) {
        "$label expected quiescent pull Tail, got [${result.pull?.stop}]"
    }
    check(result.hasOutstandingLocalWork == false) {
        "$label expected no outstanding work, got [${result.hasOutstandingLocalWork}]"
    }
    roomBackendAssertOutboxEmpty(store.database, accountId, label)
    check(store.database.roomBackendStageCount(accountId) == 0) {
        "$label expected empty staging"
    }
    val cache = roomBackendAssertBaselineComplete(store.database, accountId, label)
    check(cache.cacheEpoch == cache.materializedEpoch) {
        "$label expected matching epochs, got cache=${cache.cacheEpoch} materialized=${cache.materializedEpoch}"
    }
    check(!cache.projectionDirty) { "$label expected a clean projection" }
    val sync = checkNotNull(store.database.roomBackendReadSyncState(accountId)) {
        "$label expected a sync checkpoint"
    }
    check(!sync.cursor.isNullOrBlank()) { "$label expected a non-blank cursor" }
    checkNotNull(sync.lastSuccessMillis) { "$label expected last-success" }
}

/** Outside-run inspection only: observes the retained stage without granting authority. */
internal suspend fun roomBackendReadStage(store: RoomBackendStore, accountId: String): SnapshotStage? {
    var stage: SnapshotStage? = null
    val pushStore = PushStore(store.database, store.session, store.owner)
    pushStore.withRun(roomBackendDurableEpoch(store.database, accountId)) { run ->
        stage = run.readSnapshotStage()
    }
    return stage
}

/** Abandons the stage freshly observed inside the SAME admitted run. */
internal suspend fun roomBackendAbandonCurrentStage(store: RoomBackendStore, accountId: String) {
    val pushStore = PushStore(store.database, store.session, store.owner)
    pushStore.withRun(roomBackendDurableEpoch(store.database, accountId)) { run ->
        val current = checkNotNull(run.readSnapshotStage()) {
            "RoomBackend recovery requires a retained stage to abandon"
        }
        run.abandonSnapshotStage(current)
    }
}
