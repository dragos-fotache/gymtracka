package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.requirePushBatch
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.encodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessToken
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessTokenProvider
import com.dragosfotache.gymtracka.data.sync.http.HttpGymSyncApi
import com.dragosfotache.gymtracka.data.sync.http.OkHttpServiceTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTimeouts
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel

/**
 * Shared pure push preflight: deep-owns caller-mutable wrappers through the
 * approved encode/decode roundtrip (preserving exact requestJson) and enforces
 * the frozen batch envelope. Runs before any credential suspension; programming
 * errors propagate unchanged. Reused by prepared, refreshing and facade paths so
 * independent expected request bytes stay comparable without encoder-derived
 * expectations.
 */
internal fun freezePushRequests(requests: List<DurableRequestModel>): List<DurableRequestModel> {
    val frozen = requests.toList().map { model ->
        decodeDurableRequest(encodeDurableRequest(model))
    }
    requirePushBatch(frozen)
    return frozen
}

/**
 * Unused [GymSyncApi] decorator proving one refresh-and-unchanged-retry.
 * Each call runs inside the coordinator's access selection with the frozen
 * business inputs captured before suspension, and every accepted attempt
 * constructs a fresh [HttpGymSyncApi] whose fixed provider returns ONLY the
 * supplied credential. Mutation instances, requestJson, IDs, order, and
 * read cursor/pageToken/limit survive both attempts exactly.
 *
 * Default HTTPS origin, timeouts, and normal TLS stay in the accepted
 * transport. No account-global bearer cache exists. Nothing constructs this
 * in the app.
 *
 * Push preflight runs before any credential suspension: each wrapper is
 * deep-snapshotted through the approved encode/decode round trip, which
 * validates wrapper/envelope/version while reconstructing caller-mutable
 * nested lists and preserving the EXACT requestJson (never reserialized or
 * recaptured). The frozen batch envelope (count/duplicates) is then
 * enforced. Approved programming errors propagate unchanged, never as
 * Transient; both attempts reuse those private values unchanged.
 */
internal class SessionRefreshingGymSyncApi internal constructor(
    private val coordinator: SessionRefreshCoordinator,
    private val lease: SessionRefreshLease,
    private val transport: ServiceHttpTransport,
) : GymSyncApi {
    constructor(
        coordinator: SessionRefreshCoordinator,
        lease: SessionRefreshLease,
        timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
    ) : this(coordinator, lease, OkHttpServiceTransport(lease.binding.origin, timeouts))

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        val frozen = freezePushRequests(requests)
        return coordinator.withAccessToken(lease) { credential ->
            syncApiFor(credential).push(frozen)
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel =
        coordinator.withAccessToken(lease) { credential ->
            syncApiFor(credential).pull(cursor, limit)
        }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel =
        coordinator.withAccessToken(lease) { credential ->
            syncApiFor(credential).snapshot(pageToken, limit)
        }

    override fun toString(): String = "SessionRefreshingGymSyncApi(redacted)"

    private fun syncApiFor(credential: BoundServiceAccessToken): HttpGymSyncApi =
        HttpGymSyncApi(
            credential.binding,
            BoundServiceAccessTokenProvider { credential },
            transport,
        )
}
