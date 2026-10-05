package com.dragosfotache.gymtracka.data.sync.engine

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.MAX_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MIN_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.codec.encodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException

/** Client run-work budget: at most 100 HTTP snapshot pages per invocation, never a permanent cap. */
const val MAX_SNAPSHOT_DELIVERY_PAGES = 100

/**
 * Inert explicitly constructed frozen-snapshot download driver over the accepted
 * push and snapshot-stage seams. Runs only on an already admitted [PushRun]
 * under the caller's uninterrupted [PushStore.withRun] ownership, never
 * admitting, releasing or reacquiring a run.
 *
 * Explicit constructor pairing is a caller obligation: [api] must already bind
 * the same authorized service/account/session as [store]. The driver owns no
 * admission, mutex or owner; performs no retry, delay, refresh, token parsing,
 * cleanup, publication, scheduling or background work; and never reads durable
 * stage observations to resume.
 *
 * Only an actual fenced push [PushDeliveryStop.Empty] authorizes the initial
 * download. Every other push stop returns a push-only summary without
 * begin/read/check/download/abandon or pull work.
 */
class SnapshotDeliveryLoop(
    private val store: PushStore,
    private val api: GymSyncApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs bounded push work on the SAME admitted [run], then only after an
     * empty push boundary begins one NEW stage with the fixed [pageLimit] and
     * downloads up to [maxPages] snapshot pages. Budgets are per-invocation
     * client run-work budgets, never permanent stage caps. All argument
     * validation rejects before any clock, Room or HTTP effects.
     */
    suspend fun start(
        run: PushRun,
        stageId: String,
        maxPushMutations: Int = MAX_PUSH_DELIVERY_MUTATIONS,
        maxPages: Int = MAX_SNAPSHOT_DELIVERY_PAGES,
        pageLimit: Int = 200,
    ): SnapshotStartResult {
        if (maxPushMutations !in 1..MAX_PUSH_DELIVERY_MUTATIONS) {
            throw IllegalArgumentException("maxPushMutations must be in 1..100")
        }
        if (maxPages !in 1..MAX_SNAPSHOT_DELIVERY_PAGES) {
            throw IllegalArgumentException("maxPages must be in 1..100")
        }
        if (pageLimit !in MIN_SNAPSHOT_LIMIT..MAX_SNAPSHOT_LIMIT) {
            throw IllegalArgumentException("pageLimit must be in 1..500")
        }
        requireWorkoutUuid(stageId, "stageId")
        currentCoroutineContext().ensureActive()
        val push = PushDeliveryLoop(store, api, nowMillis).deliver(run, maxPushMutations)
        if (push.stop != PushDeliveryStop.Empty) {
            currentCoroutineContext().ensureActive()
            return SnapshotStartResult(push, null)
        }
        currentCoroutineContext().ensureActive()
        val stage = run.beginSnapshotStage(stageId, pageLimit)
        val download = downloadPages(run, stage, maxPages)
        currentCoroutineContext().ensureActive()
        return SnapshotStartResult(push, download)
    }

    /**
     * Continues a [SnapshotDownloadStop.LimitReached] result issued by THIS
     * driver on the SAME uninterrupted admitted [run] for up to [maxPages]
     * further pages. Budget and eligibility validate before any effects; the
     * pinned baseline/limit/token history continues with no begin/push/reread
     * and no total page/record ceiling. Any other stop, foreign driver,
     * recovery observation or stale capability rejects before HTTP/effects.
     */
    suspend fun continueDownload(
        run: PushRun,
        previous: SnapshotDownloadResult,
        maxPages: Int = MAX_SNAPSHOT_DELIVERY_PAGES,
    ): SnapshotDownloadResult {
        if (maxPages !in 1..MAX_SNAPSHOT_DELIVERY_PAGES) {
            throw IllegalArgumentException("maxPages must be in 1..100")
        }
        if (previous.stop != SnapshotDownloadStop.LimitReached) {
            throw IllegalArgumentException("Only a LimitReached result can continue")
        }
        if (previous.driver !== this) {
            throw IllegalArgumentException("Snapshot continuation belongs to another driver")
        }
        if (!previous.stage.canAppend || previous.stage.downloaded) {
            throw IllegalArgumentException("Snapshot continuation is not appendable")
        }
        currentCoroutineContext().ensureActive()
        return downloadPages(run, previous.stage, maxPages)
    }

    /**
     * Bounded paging core chaining ONLY the current run-bound capability from
     * begin/returned appends or the bound continuation. Never performs a
     * durable stage read. Before each HTTP call the exact stage fence runs;
     * after each response the whole page is synchronously deep-owned before any
     * suspending fence; typed transport failures map to restart/failure only
     * after the post-failure fence; valid pages commit through the existing
     * member with earlier commits left durable.
     */
    private suspend fun downloadPages(
        run: PushRun,
        initial: SnapshotStage,
        maxPages: Int,
    ): SnapshotDownloadResult {
        var stage = initial
        var committedPages = 0
        var committedRecords = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            run.checkSnapshotStage(stage)
            currentCoroutineContext().ensureActive()
            val pageToken = stage.nextPageToken
            val limit = stage.pageLimit
            val response: SnapshotResultModel
            try {
                response = api.snapshot(pageToken, limit)
            } catch (failure: SyncApiException) {
                currentCoroutineContext().ensureActive()
                run.checkSnapshotStage(stage)
                currentCoroutineContext().ensureActive()
                val stop = if (failure is SyncApiException.SnapshotExpired) {
                    SnapshotDownloadStop.RestartRequired
                } else {
                    SnapshotDownloadStop.Failed(failure)
                }
                return SnapshotDownloadResult(stage, committedPages, committedRecords, stop, this)
            }
            currentCoroutineContext().ensureActive()
            val owned = ownSnapshotPage(response, stage.pageLimit)
            run.checkSnapshotStage(stage)
            currentCoroutineContext().ensureActive()
            if (owned == null) {
                return SnapshotDownloadResult(
                    stage,
                    committedPages,
                    committedRecords,
                    SnapshotDownloadStop.Failed(SyncApiException.MalformedResponse()),
                    this,
                )
            }
            val fresh = run.appendSnapshotPage(stage, owned)
            stage = fresh
            committedPages += 1
            committedRecords += owned.records.size
            currentCoroutineContext().ensureActive()
            if (!owned.hasMore) {
                return SnapshotDownloadResult(
                    stage,
                    committedPages,
                    committedRecords,
                    SnapshotDownloadStop.Downloaded,
                    this,
                )
            }
            if (committedPages >= maxPages) {
                return SnapshotDownloadResult(
                    stage,
                    committedPages,
                    committedRecords,
                    SnapshotDownloadStop.LimitReached,
                    this,
                )
            }
        }
    }

    override fun toString(): String = "SnapshotDeliveryLoop"
}

/**
 * Synchronously deep-owns one typed snapshot page through the existing codec
 * round trip and validates fixed count/nonempty nonterminal shape before any
 * suspension. Returns the owned page, or null when malformed. Never suspends.
 */
private fun ownSnapshotPage(response: SnapshotResultModel, pageLimit: Int): SnapshotResultModel? {
    try {
        val candidate = decodeSnapshotResult(encodeSnapshotResult(response))
        if (candidate.records.size > pageLimit) {
            throw SyncModelException("Snapshot page exceeds the stage page limit")
        }
        if (candidate.hasMore && candidate.records.isEmpty()) {
            throw SyncModelException("Nonterminal snapshot page must not be empty")
        }
        return candidate
    } catch (_: SyncModelException) {
        return null
    } catch (_: JSONException) {
        return null
    }
}
