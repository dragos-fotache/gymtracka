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
 * Shared admitted engine runs for R10a scenarios. Spec composition only:
 * one uninterrupted admitted run per physical DB, initial/explicit resync only
 * after a real fenced push Empty, complete only an actual Downloaded baseline,
 * eligible-tail delivery plus paired publication.
 */
internal data class R10aPublication(
    val status: ProjectionPublicationStatus,
    val cacheEpoch: Long,
    val materializedEpoch: Long,
)

internal data class R10aDelivery(
    val pushStop: PushDeliveryStop,
    val acknowledgedMutations: Int,
    val pullStop: PullDeliveryStop?,
    val committedBatches: Int?,
    val hasOutstandingLocalWork: Boolean?,
)

internal suspend fun r10aSnapshotConverge(
    store: R10aStore,
    accountId: String,
    api: GymSyncApi,
    lease: R10aTestLease,
    pageLimit: Int = 1,
    maxPages: Int = 100,
    nowMillis: () -> Long = System::currentTimeMillis,
): R10aPublication {
    var publication: R10aPublication? = null
    val pushStore = PushStore(store.database, store.session, store.owner)
    pushStore.withRun(r10aDurableEpoch(store.database, accountId)) { run ->
        val snapshot = SnapshotDeliveryLoop(pushStore, api, nowMillis)
        val start = snapshot.start(run, UUID.randomUUID().toString(), maxPages = maxPages, pageLimit = pageLimit)
        check(start.push.stop == PushDeliveryStop.Empty) {
            "R10a resync requires a real fenced push Empty, got [${start.push.stop}]"
        }
        var download = checkNotNull(start.download) { "R10a resync requires a present download" }
        while (download.stop == SnapshotDownloadStop.LimitReached) {
            download = snapshot.continueDownload(run, download, maxPages = maxPages)
        }
        check(download.stop == SnapshotDownloadStop.Downloaded) {
            "R10a resync completes only an actual Downloaded baseline, got [${download.stop}]"
        }
        snapshot.complete(run, download)
        val delivery = SyncDeliveryLoop(pushStore, api, nowMillis).deliver(run)
        val pull = checkNotNull(delivery.pull) { "R10a resync requires a pull result" }
        check(pull.stop == PullDeliveryStop.Tail) {
            "R10a resync requires a valid Tail, got [${pull.stop}]"
        }
        val published = run.publishProjection(
            ProjectionStore(store.database, store.session, R10aRecordingBarrier(lease)),
        )
        publication = R10aPublication(published.status, published.cacheEpoch, published.materializedEpoch)
    }
    return checkNotNull(publication) { "R10a admitted run must produce a publication" }
}

/**
 * Ordinary delivery plus paired publication only after an eligible tail.
 * Returns a null publication when no valid Tail was observed.
 */
internal suspend fun r10aDeliverAndPublish(
    store: R10aStore,
    accountId: String,
    api: GymSyncApi,
    lease: R10aTestLease,
    nowMillis: () -> Long = System::currentTimeMillis,
): Pair<R10aDelivery, R10aPublication?> {
    var result: Pair<R10aDelivery, R10aPublication?>? = null
    val pushStore = PushStore(store.database, store.session, store.owner)
    pushStore.withRun(r10aDurableEpoch(store.database, accountId)) { run ->
        val delivery = SyncDeliveryLoop(pushStore, api, nowMillis).deliver(run)
        val observed = R10aDelivery(
            pushStop = delivery.push.stop,
            acknowledgedMutations = delivery.push.acknowledgedMutations,
            pullStop = delivery.pull?.stop,
            committedBatches = delivery.pull?.committedBatches,
            hasOutstandingLocalWork = delivery.hasOutstandingLocalWork,
        )
        val publication = if (delivery.pull?.stop == PullDeliveryStop.Tail) {
            val published = run.publishProjection(
                ProjectionStore(store.database, store.session, R10aRecordingBarrier(lease)),
            )
            R10aPublication(published.status, published.cacheEpoch, published.materializedEpoch)
        } else {
            null
        }
        result = observed to publication
    }
    return checkNotNull(result) { "R10a admitted run must produce a delivery" }
}

/** Full quiescence predicates for one store: valid Tail with nothing outstanding,
 * empty outbox/staging, complete baseline, clean projection, matching epochs and
 * a live checkpoint. Call for BOTH stores. */
internal suspend fun r10aAssertQuiescentTail(
    store: R10aStore,
    accountId: String,
    api: GymSyncApi,
    label: String,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val pushStore = PushStore(store.database, store.session, store.owner)
    val result = SyncDeliveryLoop(pushStore, api, nowMillis).deliver(r10aDurableEpoch(store.database, accountId))
    check(result.push.stop == PushDeliveryStop.Empty) {
        "$label expected quiescent push Empty, got [${result.push.stop}]"
    }
    check(result.pull?.stop == PullDeliveryStop.Tail) {
        "$label expected quiescent pull Tail, got [${result.pull?.stop}]"
    }
    check(result.hasOutstandingLocalWork == false) {
        "$label expected no outstanding work, got [${result.hasOutstandingLocalWork}]"
    }
    r10aAssertOutboxEmpty(store.database, accountId, label)
    check(store.database.r10aStageCount(accountId) == 0) {
        "$label expected empty staging"
    }
    val cache = r10aAssertBaselineComplete(store.database, accountId, label)
    check(cache.cacheEpoch == cache.materializedEpoch) {
        "$label expected matching epochs, got cache=${cache.cacheEpoch} materialized=${cache.materializedEpoch}"
    }
    check(!cache.projectionDirty) { "$label expected a clean projection" }
    val sync = checkNotNull(store.database.r10aReadSyncState(accountId)) {
        "$label expected a sync checkpoint"
    }
    check(!sync.cursor.isNullOrBlank()) { "$label expected a non-blank cursor" }
    checkNotNull(sync.lastSuccessMillis) { "$label expected last-success" }
}

/** Outside-run inspection only: observes the retained stage without granting authority. */
internal suspend fun r10aReadStage(store: R10aStore, accountId: String): SnapshotStage? {
    var stage: SnapshotStage? = null
    val pushStore = PushStore(store.database, store.session, store.owner)
    pushStore.withRun(r10aDurableEpoch(store.database, accountId)) { run ->
        stage = run.readSnapshotStage()
    }
    return stage
}

/** Abandons the stage freshly observed inside the SAME admitted run. */
internal suspend fun r10aAbandonCurrentStage(store: R10aStore, accountId: String) {
    val pushStore = PushStore(store.database, store.session, store.owner)
    pushStore.withRun(r10aDurableEpoch(store.database, accountId)) { run ->
        val current = checkNotNull(run.readSnapshotStage()) {
            "R10a recovery requires a retained stage to abandon"
        }
        run.abandonSnapshotStage(current)
    }
}
