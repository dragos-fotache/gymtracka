package com.dragosfotache.gymtracka.data.sync.engine

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.store.ACK_CONTEXT_CORRUPT_CODE
import com.dragosfotache.gymtracka.data.sync.store.MALFORMED_RESPONSE_CODE
import com.dragosfotache.gymtracka.data.sync.store.PushAcknowledgmentResult
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.PushFailureResult
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushOutcomeResult
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Client run-work budget: at most 100 acknowledged mutations per delivery run. */
const val MAX_PUSH_DELIVERY_MUTATIONS = 100

/**
 * Inert explicitly constructed single-account/session push driver over the
 * accepted push seams. Serializes the whole bounded run under one
 * [PushStore.withRun] lease, freezes each explicit head before HTTP, sends one
 * exact frozen head per HTTP push outside Room, classifies it, atomically
 * acknowledges accepted results, and continues only after acknowledgment
 * succeeds.
 *
 * Explicit constructor pairing is a caller obligation: [api] must already bind
 * the same authorized service/account/session as [store]. The driver never
 * derives identity, reads credentials, follows another session, manufactures a
 * run owner, refreshes credentials, delays, or launches background work.
 */
class PushDeliveryLoop(
    private val store: PushStore,
    private val api: GymSyncApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Delivers up to [maxMutations] heads in outbox order. The bound is a
     * client run-work budget, not a domain-size cap or wire batch size; each
     * HTTP push carries exactly one request. Queued runs cannot claim or send
     * until admitted; earlier committed acknowledgments stay durable if a
     * later head fails.
     */
    suspend fun deliver(
        expectedCacheEpoch: Long,
        maxMutations: Int = MAX_PUSH_DELIVERY_MUTATIONS,
    ): PushDeliveryResult {
        if (maxMutations !in 1..MAX_PUSH_DELIVERY_MUTATIONS) {
            throw IllegalArgumentException("maxMutations must be in 1..100")
        }
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        return store.withRun(expectedCacheEpoch) { run ->
            var acknowledgedMutations = 0
            var stop: PushDeliveryStop? = null
            while (stop == null) {
                val claimTime = nowMillis()
                if (claimTime < 0) {
                    throw IllegalArgumentException("Push delivery time must be nonnegative")
                }
                when (val head = run.claimHead(claimTime)) {
                    is PushHeadResult.Empty -> {
                        stop = PushDeliveryStop.Empty
                    }
                    is PushHeadResult.Deferred -> {
                        stop = PushDeliveryStop.Deferred(head.retryAfterMillis)
                    }
                    is PushHeadResult.Blocked -> {
                        stop = PushDeliveryStop.Blocked(head.reason, head.errorCode)
                    }
                    is PushHeadResult.Claimed -> {
                        val claim = head.claim
                        val request = claim.request
                        run.checkClaim(claim)
                        currentCoroutineContext().ensureActive()
                        val response: PushResultModel
                        try {
                            response = api.push(listOf(request))
                        } catch (failure: SyncApiException) {
                            currentCoroutineContext().ensureActive()
                            val failureTime = nowMillis()
                            if (failureTime < 0) {
                                throw IllegalArgumentException("Push delivery time must be nonnegative")
                            }
                            val handled = run.handleFailure(claim, failure, failureTime)
                            stop = when (handled) {
                                is PushFailureResult.RetryScheduled -> {
                                    PushDeliveryStop.Deferred(handled.retryAfterMillis)
                                }
                                is PushFailureResult.Retained -> {
                                    PushDeliveryStop.Blocked(
                                        PushBlockReason.RETAINED_ERROR,
                                        handled.errorCode,
                                    )
                                }
                                is PushFailureResult.AuthenticationRequired -> {
                                    PushDeliveryStop.AuthenticationRequired
                                }
                                is PushFailureResult.ReauthenticationRequired -> {
                                    PushDeliveryStop.ReauthenticationRequired
                                }
                            }
                            continue
                        }
                        currentCoroutineContext().ensureActive()
                        when (val outcome = run.handleResult(claim, response)) {
                            is PushOutcomeResult.Rejected -> {
                                stop = PushDeliveryStop.Blocked(
                                    PushBlockReason.REJECTED,
                                    outcome.errorCode,
                                )
                            }
                            is PushOutcomeResult.Malformed -> {
                                stop = PushDeliveryStop.Blocked(
                                    PushBlockReason.RETAINED_ERROR,
                                    MALFORMED_RESPONSE_CODE,
                                )
                            }
                            is PushOutcomeResult.Accepted -> {
                                when (run.acknowledgeAccepted(claim, outcome)) {
                                    is PushAcknowledgmentResult.RetainedCorruption -> {
                                        stop = PushDeliveryStop.Blocked(
                                            PushBlockReason.RETAINED_ERROR,
                                            ACK_CONTEXT_CORRUPT_CODE,
                                        )
                                    }
                                    is PushAcknowledgmentResult.Acknowledged -> {
                                        acknowledgedMutations += 1
                                        if (acknowledgedMutations >= maxMutations) {
                                            stop = PushDeliveryStop.LimitReached
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            PushDeliveryResult(acknowledgedMutations, stop)
        }
    }

    override fun toString(): String = "PushDeliveryLoop"
}
