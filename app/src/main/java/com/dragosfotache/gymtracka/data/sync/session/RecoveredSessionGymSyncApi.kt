package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.MAX_PULL_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MIN_PULL_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MAX_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MIN_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessToken
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessTokenProvider
import com.dragosfotache.gymtracka.data.sync.http.HttpGymSyncApi
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * facade-bound [GymSyncApi] over one admitted [RecoveredSession] and its
 * shared refresh machinery. No second unbound ACTIVE lease is minted.
 *
 * Push wrappers are frozen/deep-owned through the shared approved
 * encode/decode roundtrip plus batch preflight BEFORE any credential
 * suspension; both attempts reuse those private values unchanged. Read limits
 * validate before credential work. Every transport callback result is checked
 * against both recovered Room authority (via the session's fenced callback
 * guards) and the facade process handle before return. Displays are
 * fixed/redacted. Caller cancellation dominates and is never represented as
 * a successful delivery.
 */
internal class RecoveredSessionGymSyncApi internal constructor(
    private val session: RecoveredSession,
    private val transport: ServiceHttpTransport,
    private val facadeCheck: () -> Unit,
) : GymSyncApi {
    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        val frozen = freezePushRequests(requests)
        currentCoroutineContext().ensureActive()
        facadeCheck()
        return session.withAccessToken { credential ->
            facadeCheck()
            val result = apiFor(credential).push(frozen)
            facadeCheck()
            result
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        if (limit !in MIN_PULL_LIMIT..MAX_PULL_LIMIT) {
            throw IllegalArgumentException("pull limit must be in 1..200")
        }
        currentCoroutineContext().ensureActive()
        facadeCheck()
        return session.withAccessToken { credential ->
            facadeCheck()
            val result = apiFor(credential).pull(cursor, limit)
            facadeCheck()
            result
        }
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        if (limit !in MIN_SNAPSHOT_LIMIT..MAX_SNAPSHOT_LIMIT) {
            throw IllegalArgumentException("snapshot limit must be in 1..500")
        }
        currentCoroutineContext().ensureActive()
        facadeCheck()
        return session.withAccessToken { credential ->
            facadeCheck()
            val result = apiFor(credential).snapshot(pageToken, limit)
            facadeCheck()
            result
        }
    }

    override fun toString(): String = "RecoveredSessionGymSyncApi(redacted)"

    private fun apiFor(credential: BoundServiceAccessToken): HttpGymSyncApi =
        HttpGymSyncApi(
            credential.binding,
            BoundServiceAccessTokenProvider { credential },
            transport,
        )
}
