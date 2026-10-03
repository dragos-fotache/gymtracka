package com.dragosfotache.gymtracka.data.sync.api

import com.dragosfotache.gymtracka.data.sync.codec.encodePushMutationsBody
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel

/**
 * SDK-independent sync transport boundary. Implemented by the future production
 * HTTP adapter and by the test-only HTTP adapter; never by Room, engine, UI,
 * or worker code. No Android, Room, Google, or HTTP types appear here.
 *
 * The instance binds the authenticated account and session through its
 * adapter: wire methods never accept owner-controlled account fields, and the
 * account is always derived from bearer authentication. Cursors and page
 * tokens are opaque: adapters must never decode them as client sequence
 * numbers or compare them lexically.
 */

/** Maximum durable requests per push call (OpenAPI mutations maxItems). */
const val MAX_PUSH_REQUESTS = 100

/** Pull page limits in complete commit batches (OpenAPI pull limit). */
const val MIN_PULL_LIMIT = 1
const val MAX_PULL_LIMIT = 200
const val DEFAULT_PULL_LIMIT = 100

/** Snapshot page limits in records (OpenAPI snapshot limit). */
const val MIN_SNAPSHOT_LIMIT = 1
const val MAX_SNAPSHOT_LIMIT = 500
const val DEFAULT_SNAPSHOT_LIMIT = 200

interface GymSyncApi {
    /**
     * Submits frozen durable requests in outbox order. Input uses the approved
     * frozen request objects: wire text and IDs stay unchanged across retries,
     * and the returned batches are complete and atomic. Per-mutation rejection
     * remains a [PushResultModel] outcome, never an exception. Top-level
     * failures throw SyncApiException subtypes (transient, access
     * authentication, reauthentication, request-too-large, request-rejected,
     * or malformed-response categories); cancellation propagates unchanged.
     */
    suspend fun push(requests: List<DurableRequestModel>): PushResultModel

    /**
     * Reads ordered account change batches after [cursor]. A null cursor reads
     * from the start; callers without a persisted account cursor bootstrap
     * from a full snapshot instead of retained incremental history. An
     * unknown, foreign, or malformed cursor throws `InvalidCursor` (never an
     * expiry shortcut); a cursor older than retained history throws
     * `CursorExpired` and requires a full resync. A read-token code on the
     * wrong operation or an ordinary wrong 4xx status stays a permanent
     * request rejection; 401/413/429 and retryable 5xx keep their own
     * categories. Cancellation propagates unchanged.
     */
    suspend fun pull(cursor: String?, limit: Int = DEFAULT_PULL_LIMIT): PullResultModel

    /**
     * Pages current non-deleted aggregates for a full resync. A null
     * [pageToken] starts the snapshot; the token binds one stable snapshot
     * and its authenticated account. An unknown, foreign, or malformed token
     * throws `InvalidSnapshotToken` (never an expiry shortcut); an
     * authentically expired token throws `SnapshotExpired`, discarding staged
     * pages and restarting without the token. Cancellation propagates
     * unchanged.
     */
    suspend fun snapshot(pageToken: String?, limit: Int = DEFAULT_SNAPSHOT_LIMIT): SnapshotResultModel
}

/**
 * Enforces the approved OpenAPI push batch envelope by delegating to
 * [encodePushMutationsBody]: at most [MAX_PUSH_REQUESTS] requests, every
 * wrapper text-matched and validated, and no duplicate mutation IDs. An empty
 * list is valid (it encodes an empty mutations array). Throws
 * [SyncModelException][com.dragosfotache.gymtracka.data.sync.model.SyncModelException]
 * for a programming error, never a service failure.
 */
fun requirePushBatch(requests: List<DurableRequestModel>): List<DurableRequestModel> {
    encodePushMutationsBody(requests)
    return requests
}

/** Bounds a pull page limit to [MIN_PULL_LIMIT]..[MAX_PULL_LIMIT]. */
fun boundedPullLimit(limit: Int): Int = limit.coerceIn(MIN_PULL_LIMIT, MAX_PULL_LIMIT)

/** Bounds a snapshot page limit to [MIN_SNAPSHOT_LIMIT]..[MAX_SNAPSHOT_LIMIT]. */
fun boundedSnapshotLimit(limit: Int): Int = limit.coerceIn(MIN_SNAPSHOT_LIMIT, MAX_SNAPSHOT_LIMIT)
