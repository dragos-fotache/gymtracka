package com.dragosfotache.gymtracka.data.sync.engine

import com.dragosfotache.gymtracka.data.sync.api.DEFAULT_PULL_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.store.PullTailCommitResult
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Inert explicitly constructed shared push-before-pull driver over the accepted
 * push, pull and valid-tail seams. Admits exactly one [PushStore.withRun] lease
 * spanning push, all pull HTTP/commits and tail bookkeeping: it invokes the
 * admitted push overload (never the epoch overload) and then the internal
 * pull-with-tail seam on that same admitted run, with no extra mutex, owner or
 * nested admission.
 *
 * Explicit constructor pairing is a caller obligation: [api] must already bind
 * the same authorized service/account/session as [store]. The driver creates no
 * owner, credentials or scope; performs no push retry, successor bypass, pull
 * bootstrap, snapshot, discard, refresh, delay, publication or credential/cache
 * cleanup; and launches no background work.
 *
 * Only a returned [PushDeliveryStop.Empty] permits pull: an actual fenced empty
 * explicit-head selection, not convergence. Every other push stop returns a
 * push-only summary without checkpoint, pull or last-success work. New local
 * capture while the network runs stays protected: it is reported independently
 * at tail, never silently pushed, rebased or dropped, and never reenters push
 * to manufacture convergence. Earlier returned acknowledgments and batch commits
 * remain durable after any later failure; the driver never retries within the run
 * nor infers rollback from cancellation after a synchronous commit.
 */
class SyncDeliveryLoop(
    private val store: PushStore,
    private val api: GymSyncApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Delivers bounded push work then, only after an empty push boundary, bounded
     * pull work with transactional valid-tail/last-success bookkeeping. Budgets
     * are client run-work budgets: at most [maxPushMutations] acknowledged heads,
     * [maxPullPages] HTTP pull pages of at most [pullPageLimit] complete batches.
     * All validation rejects before admission, clock, HTTP or Room effects.
     */
    suspend fun deliver(
        expectedCacheEpoch: Long,
        maxPushMutations: Int = MAX_PUSH_DELIVERY_MUTATIONS,
        maxPullPages: Int = MAX_PULL_DELIVERY_PAGES,
        pullPageLimit: Int = DEFAULT_PULL_LIMIT,
    ): SyncDeliveryResult {
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        if (maxPushMutations !in 1..MAX_PUSH_DELIVERY_MUTATIONS) {
            throw IllegalArgumentException("maxPushMutations must be in 1..100")
        }
        if (maxPullPages !in 1..MAX_PULL_DELIVERY_PAGES) {
            throw IllegalArgumentException("maxPullPages must be in 1..100")
        }
        if (pullPageLimit !in 1..200) {
            throw IllegalArgumentException("pullPageLimit must be in 1..200")
        }
        currentCoroutineContext().ensureActive()
        return store.withRun(expectedCacheEpoch) { run ->
            deliver(run, maxPushMutations, maxPullPages, pullPageLimit)
        }
    }

    /**
     * Internal admitted-run overload sharing the public behavior and
     * validation without another admission or owner gap: bounded push on
     * the already admitted [run], then only after an empty push boundary
     * the internal pull-with-tail seam with its exact terminal callback
     * recording last success through [PushRun.recordPullTail]. Retains the
     * push-only stop policy and the nullable outstanding-work contract.
     */
    internal suspend fun deliver(
        run: PushRun,
        maxPushMutations: Int = MAX_PUSH_DELIVERY_MUTATIONS,
        maxPullPages: Int = MAX_PULL_DELIVERY_PAGES,
        pullPageLimit: Int = DEFAULT_PULL_LIMIT,
        onTailCommit: ((PullTailCommitResult) -> Unit)? = null,
    ): SyncDeliveryResult {
        if (maxPushMutations !in 1..MAX_PUSH_DELIVERY_MUTATIONS) {
            throw IllegalArgumentException("maxPushMutations must be in 1..100")
        }
        if (maxPullPages !in 1..MAX_PULL_DELIVERY_PAGES) {
            throw IllegalArgumentException("maxPullPages must be in 1..100")
        }
        if (pullPageLimit !in 1..200) {
            throw IllegalArgumentException("pullPageLimit must be in 1..200")
        }
        currentCoroutineContext().ensureActive()
        val pushResult = PushDeliveryLoop(store, api, nowMillis).deliver(run, maxPushMutations)
        if (pushResult.stop != PushDeliveryStop.Empty) {
            currentCoroutineContext().ensureActive()
            return SyncDeliveryResult(
                push = pushResult,
                pull = null,
                hasOutstandingLocalWork = null,
            )
        }
        var tailCommit: PullTailCommitResult? = null
        val pullResult = PullDeliveryLoop(api).pullWithTail(
            run,
            maxPullPages,
            pullPageLimit,
        ) { checkpoint ->
            val tailTime = nowMillis()
            tailCommit = run.recordPullTail(checkpoint, tailTime)
        }
        currentCoroutineContext().ensureActive()
        val committed = tailCommit
        if (committed != null) {
            onTailCommit?.invoke(committed)
        }
        return SyncDeliveryResult(
            push = pushResult,
            pull = pullResult,
            hasOutstandingLocalWork = tailCommit?.hasOutstandingLocalWork,
        )
    }

    override fun toString(): String = "SyncDeliveryLoop"
}
