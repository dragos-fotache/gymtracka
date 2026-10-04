package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.api.boundedPullLimit
import com.dragosfotache.gymtracka.data.sync.api.boundedSnapshotLimit
import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.codec.decodePushResult
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceError
import com.dragosfotache.gymtracka.data.sync.codec.decodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.codec.encodePushMutationsBody
import com.dragosfotache.gymtracka.data.sync.codec.parseServiceResponseObject
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import kotlinx.coroutines.CancellationException

/**
 * Production [GymSyncApi] over the accepted secure transport. Each explicit call
 * freezes its request bytes and expected identity before the single credential
 * selection, sends exactly once with only an `Authorization` bearer added, and
 * decodes complete pages through the strict service response entry point. No
 * refresh, retry, outbox, Room, cursor publication or snapshot staging lives here;
 * a transport failure never claims the service committed nothing. Display is fixed
 * and redacted; bindings, tokens, bodies and query values never appear in logs.
 */
internal class HttpGymSyncApi internal constructor(
    private val binding: ServiceSessionBinding,
    private val tokenProvider: BoundServiceAccessTokenProvider,
    private val transport: ServiceHttpTransport,
) : GymSyncApi {
    constructor(
        binding: ServiceSessionBinding,
        tokenProvider: BoundServiceAccessTokenProvider,
        timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
    ) : this(binding, tokenProvider, OkHttpServiceTransport(binding.origin, timeouts))

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        val frozen = requests.toList()
        val body = try {
            encodePushMutationsBody(frozen)
        } catch (error: CancellationException) {
            throw error
        } catch (error: UnsupportedDurableVersionException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid push request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid push request")
        }
        val expectedIds = frozen.map { it.envelope.mutationId }
        val draft = try {
            ServiceHttpRequest(ServiceHttpMethod.POST, PUSH_PATH, emptyList(), emptyMap(), body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid push request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid push request")
        }
        val token = selectToken()
        val request = authorizedRequest(ServiceHttpMethod.POST, PUSH_PATH, draft.query, draft.body, token)
        return transport.withResponse(request) { response ->
            if (response.statusCode != 200) {
                throwHttpFailure(SyncHttpOperation.PUSH, response)
            }
            val result = strictDecode {
                decodePushResult(parseServiceResponseObject(response.readJsonBody()))
            }
            if (result.results.map { it.mutationId } != expectedIds) {
                throw SyncApiException.MalformedResponse()
            }
            result
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        val bounded = boundedPullLimit(limit)
        val draftQuery = ArrayList<Pair<String, String>>(2)
        if (cursor != null) {
            draftQuery.add(Pair("cursor", cursor))
        }
        draftQuery.add(Pair("limit", bounded.toString()))
        val draft = try {
            ServiceHttpRequest(ServiceHttpMethod.GET, PULL_PATH, draftQuery)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid pull request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid pull request")
        }
        val token = selectToken()
        val request = authorizedRequest(ServiceHttpMethod.GET, PULL_PATH, draft.query, null, token)
        return transport.withResponse(request) { response ->
            if (response.statusCode != 200) {
                throwHttpFailure(SyncHttpOperation.PULL, response)
            }
            val result = strictDecode {
                decodePullResult(parseServiceResponseObject(response.readJsonBody()))
            }
            if (result.batches.size > bounded) {
                throw SyncApiException.MalformedResponse()
            }
            if (cursor != null) {
                if (result.batches.isEmpty()) {
                    if (result.nextCursor != cursor) {
                        throw SyncApiException.MalformedResponse()
                    }
                } else if (result.nextCursor == cursor) {
                    throw SyncApiException.MalformedResponse()
                }
            }
            result
        }
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        val bounded = boundedSnapshotLimit(limit)
        val draftQuery = ArrayList<Pair<String, String>>(2)
        if (pageToken != null) {
            draftQuery.add(Pair("pageToken", pageToken))
        }
        draftQuery.add(Pair("limit", bounded.toString()))
        val draft = try {
            ServiceHttpRequest(ServiceHttpMethod.GET, SNAPSHOT_PATH, draftQuery)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid snapshot request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid snapshot request")
        }
        val token = selectToken()
        val request = authorizedRequest(ServiceHttpMethod.GET, SNAPSHOT_PATH, draft.query, null, token)
        return transport.withResponse(request) { response ->
            if (response.statusCode != 200) {
                throwHttpFailure(SyncHttpOperation.SNAPSHOT, response)
            }
            val result = strictDecode {
                decodeSnapshotResult(parseServiceResponseObject(response.readJsonBody()))
            }
            if (result.records.size > bounded) {
                throw SyncApiException.MalformedResponse()
            }
            if (result.hasMore) {
                val continuation = result.nextPageToken
                if (result.records.isEmpty() || continuation.isNullOrEmpty()) {
                    throw SyncApiException.MalformedResponse()
                }
                if (pageToken != null && continuation == pageToken) {
                    throw SyncApiException.MalformedResponse()
                }
            }
            result
        }
    }

    override fun toString(): String = "HttpGymSyncApi(redacted)"

    /**
     * Selects the bearer exactly once per explicit call under the immutable expected
     * binding. A tag mismatch is access rejection before I/O. Only provider access,
     * reauth and transient failures propagate unchanged; any other provider failure
     * is an unexpected wrong-context category and becomes closed-cause transient.
     * Cancellation always propagates.
     */
    private suspend fun selectToken(): OpaqueToken {
        val selected = try {
            tokenProvider.accessToken(binding)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncApiException.AuthenticationRequired) {
            throw error
        } catch (error: SyncApiException.ReauthenticationRequired) {
            throw error
        } catch (error: SyncApiException.Transient) {
            throw error
        } catch (error: SyncApiException) {
            throw SyncApiException.Transient()
        } catch (error: Exception) {
            throw SyncApiException.Transient()
        }
        if (selected.binding != binding) {
            throw SyncApiException.AuthenticationRequired()
        }
        return selected.token
    }

    /**
     * Builds the final immutable request with only the bearer added. An
     * unrepresentable bearer is access rejection without I/O, never trimming or a
     * different credential; the token's own leading/trailing spaces are rejected
     * even though the prefix would hide them as interior header whitespace.
     * Body/query input errors stay preflight failures.
     */
    private fun authorizedRequest(
        method: ServiceHttpMethod,
        path: String,
        query: List<Pair<String, String>>,
        body: String?,
        token: OpaqueToken,
    ): ServiceHttpRequest {
        val raw = token.value
        if (raw.isEmpty() || raw.first() == ' ' || raw.last() == ' ') {
            throw SyncApiException.AuthenticationRequired()
        }
        val bearer = "Bearer $raw"
        if (!isRepresentableHeaderValue(bearer)) {
            throw SyncApiException.AuthenticationRequired()
        }
        try {
            return ServiceHttpRequest(method, path, query, mapOf("Authorization" to bearer), body)
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid sync request")
        }
    }

    /**
     * Routes a non-200 response. Size/rate/retryable statuses never read a body;
     * 401 distinguishes explicit reauth with best effort and falls back to access
     * rejection; other ordinary 4xx decode strict JSON where media/grammar/envelope
     * failures are malformed but genuine I/O stays transient; anything unexpected
     * is malformed without a body read. Cancellation always wins.
     */
    private suspend fun throwHttpFailure(operation: SyncHttpOperation, response: ServiceHttpResponse): Nothing {
        val status = response.statusCode
        if (status == 413) {
            throw SyncApiException.RequestTooLarge()
        }
        if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
            throw SyncApiException.Transient(retryAfterMillis(response.headers))
        }
        if (status == 401) {
            throw readReauthOrAccess(response)
        }
        if (status in 400..499) {
            throw classifySyncHttpFailure(operation, status, readStrictErrorCode(response), response.headers)
        }
        throw SyncApiException.MalformedResponse()
    }

    private suspend fun readReauthOrAccess(response: ServiceHttpResponse): SyncApiException {
        try {
            val error = decodeServiceError(response.readJsonBody())
            if (error.errorCode == "reauthentication_required") {
                return SyncApiException.ReauthenticationRequired()
            }
            return SyncApiException.AuthenticationRequired()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return SyncApiException.AuthenticationRequired()
        }
    }

    private suspend fun readStrictErrorCode(response: ServiceHttpResponse): String {
        val text = try {
            response.readJsonBody()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncApiException.Transient) {
            throw error
        } catch (error: Exception) {
            throw SyncApiException.MalformedResponse()
        }
        try {
            return decodeServiceError(text).errorCode
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SyncApiException.MalformedResponse()
        }
    }

    companion object {
        private const val PUSH_PATH = "/v1/sync/push"
        private const val PULL_PATH = "/v1/sync/pull"
        private const val SNAPSHOT_PATH = "/v1/sync/snapshot"
    }
}

/**
 * Decodes a successful body strictly; grammar/result failures become closed-cause
 * malformed responses while transport failures and cancellation pass through.
 */
private suspend fun <T> strictDecode(block: suspend () -> T): T {
    try {
        return block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: SyncApiException) {
        throw error
    } catch (error: Exception) {
        throw SyncApiException.MalformedResponse()
    }
}

/** Mirrors the transport header rule without trimming or rewriting credentials. */
private fun isRepresentableHeaderValue(value: String): Boolean {
    if (value.isEmpty()) {
        return false
    }
    if (value.first() == ' ' || value.last() == ' ') {
        return false
    }
    for (element in value) {
        if (element < ' ' || element > '~') {
            return false
        }
    }
    return true
}
