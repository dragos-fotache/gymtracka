package com.dragosfotache.gymtracka.data.sync.engine

import com.dragosfotache.gymtracka.data.sync.api.DEFAULT_PULL_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.codec.encodePullResult
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.store.PullCheckpoint
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException

/** Client run-work budget: at most 100 HTTP pull pages per delivery run. */
const val MAX_PULL_DELIVERY_PAGES = 100

/**
 * Inert explicitly constructed incremental pull driver over the accepted pull
 * seams. Runs on an already admitted [PushRun], never a store or epoch, so a
 * later caller can invoke it uninterrupted after push on the same run without
 * nested entry. Typical explicit use:
 * `store.withRun(epoch) { run -> PullDeliveryLoop(pairedApi).pull(run) }`.
 *
 * Explicit constructor pairing is a caller obligation: [api] must already bind
 * the same authorized service/account/session as the admitted run. The driver
 * owns no admission, mutex or owner; performs no push, retry, delay, refresh,
 * token parsing or last-success/convergence bookkeeping; and launches no
 * background work. HTTP runs outside Room under the caller's uninterrupted
 * owner; every batch commits in returned order through the run's atomic
 * next-batch seam. Cancellation, storage, canonical and fence failures
 * propagate with earlier committed batches left durable.
 */
class PullDeliveryLoop(
    private val api: GymSyncApi,
) {
    /**
     * Pulls up to [maxPages] HTTP pages of at most [pageLimit] complete batches
     * from the exact durable chain boundary. Budgets count HTTP pages, and at
     * most 20,000 complete batches are fetched per invocation. A missing
     * baseline or cursor returns recovery without any transport; an explicitly
     * expired cursor returns recovery; every other typed transport failure returns
     * that exact failure and every malformed page returns a new MalformedResponse,
     * with earlier commits preserved.
     */
    suspend fun pull(
        run: PushRun,
        maxPages: Int = MAX_PULL_DELIVERY_PAGES,
        pageLimit: Int = DEFAULT_PULL_LIMIT,
    ): PullDeliveryResult {
        if (maxPages !in 1..MAX_PULL_DELIVERY_PAGES) {
            throw IllegalArgumentException("maxPages must be in 1..100")
        }
        if (pageLimit !in 1..200) {
            throw IllegalArgumentException("pageLimit must be in 1..200")
        }
        currentCoroutineContext().ensureActive()
        val initial = run.readPullCheckpoint()
        currentCoroutineContext().ensureActive()
        if (initial == null) {
            return PullDeliveryResult(
                committedBatches = 0,
                stop = PullDeliveryStop.RecoveryRequired(PullRecoveryReason.MISSING_BASELINE_OR_CURSOR),
            )
        }
        // Non-null run-local chain head: replaced only with each commit's fresh
        // returned token, never reread between successful sequential commits.
        var checkpoint: PullCheckpoint = initial
        var committedBatches = 0
        var pagesUsed = 0
        val seenBatchIds = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        seenCursors.add(checkpoint.cursor)
        while (true) {
            currentCoroutineContext().ensureActive()
            run.checkPullCheckpoint(checkpoint)
            currentCoroutineContext().ensureActive()
            val response: PullResultModel
            try {
                response = api.pull(checkpoint.cursor, pageLimit)
            } catch (failure: SyncApiException) {
                currentCoroutineContext().ensureActive()
                run.checkPullCheckpoint(checkpoint)
                currentCoroutineContext().ensureActive()
                return if (failure is SyncApiException.CursorExpired) {
                    PullDeliveryResult(
                        committedBatches = committedBatches,
                        stop = PullDeliveryStop.RecoveryRequired(PullRecoveryReason.CURSOR_EXPIRED),
                    )
                } else {
                    PullDeliveryResult(
                        committedBatches = committedBatches,
                        stop = PullDeliveryStop.Failed(failure),
                    )
                }
            }
            // Own and validate the entire page synchronously before the
            // suspending post-response checkpoint check or any other suspension,
            // so caller mutation during that suspension cannot change it. The
            // suspending fence below still precedes every malformed, tail,
            // recovery or failure outcome and every page commit.
            currentCoroutineContext().ensureActive()
            val page = ownPullPage(response, checkpoint.cursor, pageLimit, seenBatchIds, seenCursors)
            pagesUsed += 1
            run.checkPullCheckpoint(checkpoint)
            currentCoroutineContext().ensureActive()
            when (page) {
                is OwnedPullPage.Malformed -> {
                    return PullDeliveryResult(
                        committedBatches = committedBatches,
                        stop = PullDeliveryStop.Failed(SyncApiException.MalformedResponse()),
                    )
                }
                is OwnedPullPage.EmptyTail -> {
                    return PullDeliveryResult(
                        committedBatches = committedBatches,
                        stop = PullDeliveryStop.Tail,
                    )
                }
                is OwnedPullPage.Data -> {
                    for (batch in page.result.batches) {
                        val commit = run.applyNextPullBatch(checkpoint, batch)
                        checkpoint = commit.checkpoint
                        seenBatchIds.add(batch.batchId)
                        seenCursors.add(batch.cursor)
                        committedBatches += 1
                    }
                    currentCoroutineContext().ensureActive()
                    if (!page.result.hasMore) {
                        return PullDeliveryResult(
                            committedBatches = committedBatches,
                            stop = PullDeliveryStop.Tail,
                        )
                    }
                    if (pagesUsed >= maxPages) {
                        return PullDeliveryResult(
                            committedBatches = committedBatches,
                            stop = PullDeliveryStop.LimitReached,
                        )
                    }
                }
            }
        }
    }

    override fun toString(): String = "PullDeliveryLoop"
}

/** Synchronous whole-page ownership verdict. Pure helper only, no suspension. */
private sealed interface OwnedPullPage {
    data object Malformed : OwnedPullPage

    data object EmptyTail : OwnedPullPage

    class Data(val result: PullResultModel) : OwnedPullPage
}

/**
 * Deep-owns one typed pull page through the existing codec round trip and
 * validates the whole page's run progression before its first commit: at most
 * [pageLimit] batches, an empty page preserving exactly [inputCursor], and for a
 * nonempty page every batch ID new across the invocation and every batch cursor
 * new versus the initial boundary plus all earlier and current page batches.
 * Page-local shape, uniqueness and last-batch/nextCursor equality come from the
 * codec; strings stay opaque and exact. Never suspends.
 */
private fun ownPullPage(
    response: PullResultModel,
    inputCursor: String,
    pageLimit: Int,
    seenBatchIds: Set<String>,
    seenCursors: Set<String>,
): OwnedPullPage {
    val owned: PullResultModel
    try {
        owned = decodePullResult(encodePullResult(response))
    } catch (_: SyncModelException) {
        return OwnedPullPage.Malformed
    } catch (_: JSONException) {
        return OwnedPullPage.Malformed
    }
    if (owned.batches.size > pageLimit) {
        return OwnedPullPage.Malformed
    }
    if (owned.batches.isEmpty()) {
        if (owned.nextCursor != inputCursor) {
            return OwnedPullPage.Malformed
        }
        return OwnedPullPage.EmptyTail
    }
    val pageBatchIds = HashSet<String>(owned.batches.size)
    val pageCursors = HashSet<String>(owned.batches.size)
    for (batch in owned.batches) {
        if (!pageBatchIds.add(batch.batchId) || batch.batchId in seenBatchIds) {
            return OwnedPullPage.Malformed
        }
        if (!pageCursors.add(batch.cursor) || batch.cursor in seenCursors) {
            return OwnedPullPage.Malformed
        }
    }
    return OwnedPullPage.Data(owned)
}
