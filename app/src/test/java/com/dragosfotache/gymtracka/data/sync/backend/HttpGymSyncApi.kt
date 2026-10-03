package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.MAX_RETRY_AFTER_MILLIS
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.api.boundedPullLimit
import com.dragosfotache.gymtracka.data.sync.api.boundedSnapshotLimit
import com.dragosfotache.gymtracka.data.sync.api.requirePushBatch
import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.codec.decodePushResult
import com.dragosfotache.gymtracka.data.sync.codec.decodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.codec.encodePushMutationsBody
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Test-only HTTP [GymSyncApi] adapter over an injectable [SyncHttpTransport].
 *
 * The account is bound through the constructor bearer token, exactly like the
 * retired fake adapter: wire methods accept no owner-controlled account fields.
 * Push bodies reuse the production [encodePushMutationsBody] (frozen request
 * bytes, 100-request limit, duplicate-ID rejection); responses decode through
 * the production strict result codecs. A `200` whose outcome count/order does
 * not match the submitted requests fails closed with [SyncApiException.MalformedResponse].
 * Per-mutation rejection stays a typed outcome, never an exception.
 * Cancellation propagates unchanged and is never converted into a failure.
 *
 * Failures carry only fixed safe text; raw bodies, tokens and causes are never
 * retained. This class deliberately has no data-class `toString`: the bearer
 * token must never appear in logs or reports.
 */
class HttpGymSyncApi(
    private val transport: SyncHttpTransport,
    private val accessToken: String,
) : GymSyncApi {
    private enum class SyncOperation {
        PUSH,
        PULL,
        SNAPSHOT,
    }

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        // Client-side envelope validation first: programming errors throw
        // SyncModelException before any transport use, mirroring the fake adapter.
        requirePushBatch(requests)
        val response = send(
            method = "POST",
            path = "/v1/sync/push",
            query = emptyMap(),
            headers = mapOf("Content-Type" to "application/json"),
            body = encodePushMutationsBody(requests),
        )
        if (response.statusCode != 200) throw mapHttpError(response, SyncOperation.PUSH)
        val result = decodeSafely { decodePushResult(response.body) }
        if (result.results.size != requests.size) throw SyncApiException.MalformedResponse()
        result.results.forEachIndexed { index, outcome ->
            if (outcome.mutationId != requests[index].envelope.mutationId) {
                throw SyncApiException.MalformedResponse()
            }
        }
        return result
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        val query = LinkedHashMap<String, String>()
        if (cursor != null) query["cursor"] = cursor
        query["limit"] = boundedPullLimit(limit).toString()
        val response = send(
            method = "GET",
            path = "/v1/sync/pull",
            query = query,
            headers = emptyMap(),
            body = null,
        )
        if (response.statusCode != 200) throw mapHttpError(response, SyncOperation.PULL)
        return decodeSafely { decodePullResult(response.body) }
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        val query = LinkedHashMap<String, String>()
        if (pageToken != null) query["pageToken"] = pageToken
        query["limit"] = boundedSnapshotLimit(limit).toString()
        val response = send(
            method = "GET",
            path = "/v1/sync/snapshot",
            query = query,
            headers = emptyMap(),
            body = null,
        )
        if (response.statusCode != 200) throw mapHttpError(response, SyncOperation.SNAPSHOT)
        return decodeSafely { decodeSnapshotResult(response.body) }
    }

    /**
     * Sends one exchange, mapping raw transport throwables (I/O, timeouts,
     * unexpected runtime faults) to a safe [SyncApiException.Transient] with
     * no retained cause. Cancellation propagates unchanged.
     */
    private suspend fun send(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: String?,
    ): SyncHttpResponse {
        try {
            return transport.exchange(method, path, query, headers, body, "Bearer $accessToken")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncApiException.Transient()
        }
    }

    private inline fun <T> decodeSafely(decode: () -> T): T {
        try {
            return decode()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Strict codec failures (SyncModelException, JSON errors) become a
            // malformed-response failure without echoing the raw body.
            throw SyncApiException.MalformedResponse()
        }
    }

    /**
     * Maps a non-200 response to the approved safe failure. The service error
     * envelope is `{errorCode, message?}`; only the machine-readable code is
     * consulted, never the presentation message or raw body.
     */
    private fun mapHttpError(response: SyncHttpResponse, operation: SyncOperation): SyncApiException {
        if (response.statusCode == 413) return SyncApiException.RequestTooLarge()
        val errorCode = serviceErrorCode(response.body)
        if (response.statusCode == 401) {
            return if (errorCode == "reauthentication_required") {
                SyncApiException.ReauthenticationRequired()
            } else {
                SyncApiException.AuthenticationRequired()
            }
        }
        return when (response.statusCode) {
            429 -> SyncApiException.Transient(retryAfterMillis(response.headers))
            500, 502, 503, 504 -> SyncApiException.Transient(retryAfterMillis(response.headers))
            400 -> when {
                operation == SyncOperation.PULL && errorCode == "invalid_cursor" ->
                    SyncApiException.InvalidCursor()
                operation == SyncOperation.SNAPSHOT && errorCode == "invalid_snapshot_token" ->
                    SyncApiException.InvalidSnapshotToken()
                errorCode.isNotEmpty() -> SyncApiException.RequestRejected(errorCode)
                else -> SyncApiException.MalformedResponse()
            }
            410 -> when {
                operation == SyncOperation.PULL && errorCode == "cursor_expired" ->
                    SyncApiException.CursorExpired()
                operation == SyncOperation.SNAPSHOT && errorCode == "snapshot_expired" ->
                    SyncApiException.SnapshotExpired()
                errorCode.isNotEmpty() -> SyncApiException.RequestRejected(errorCode)
                else -> SyncApiException.MalformedResponse()
            }
            in 400..499 ->
                if (errorCode.isNotEmpty()) {
                    SyncApiException.RequestRejected(errorCode)
                } else {
                    SyncApiException.MalformedResponse()
                }
            else -> SyncApiException.MalformedResponse()
        }
    }

    private fun serviceErrorCode(body: String): String {
        if (body.isEmpty()) return ""
        // Strict raw-envelope parsing: kotlinx-serialization ensures strict
        // syntax and typed reads; org.json rejects duplicate keys (its
        // constructor throws on repeats, including escape-variant spellings).
        // The code must then be an explicit string in safe machine-code shape.
        // Anything else is treated as absent so it can never be retained in
        // an exception. The raw body is never stored or echoed.
        val element = try {
            kotlinx.serialization.json.Json.parseToJsonElement(body)
        } catch (e: Exception) {
            return ""
        }
        val fields = (element as? JsonObject) ?: return ""
        try {
            org.json.JSONObject(body)
        } catch (e: Exception) {
            return ""
        }
        val code = fields["errorCode"] as? JsonPrimitive ?: return ""
        if (!code.isString) return ""
        val value = code.content
        return if (value.matches(ERROR_CODE_PATTERN)) value else ""
    }

    private fun retryAfterMillis(headers: Map<String, List<String>>): Long? {
        val raw = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()
            ?: return null
        // Contract sends whole seconds; anything else means caller backoff.
        // Saturate before scaling so hostile values cannot overflow to negative.
        val seconds = raw.toLongOrNull() ?: return null
        if (seconds < 0) return null
        if (seconds > MAX_RETRY_AFTER_SECONDS) return MAX_RETRY_AFTER_MILLIS
        return seconds * 1_000L
    }

    companion object {
        private val ERROR_CODE_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
        private const val MAX_RETRY_AFTER_SECONDS = MAX_RETRY_AFTER_MILLIS / 1_000L
    }
}

/**
 * Raw HTTP exchange result: status, multi-value headers, and body text.
 *
 * `toString` is redacted: bodies and header values never appear in reports.
 */
data class SyncHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    override fun toString(): String =
        "SyncHttpResponse(status=$statusCode, headers=${headers.keys.sorted()}, body=<${body.length} chars redacted>)"
}

/**
 * Injectable suspend HTTP transport. Production tests use [JdkSyncHttpTransport];
 * offline tests script this interface to control method/path/query/headers/body,
 * errors, and cancellation without recreating domain logic. The bearer token
 * travels as a separate [authorization] argument so scripted transports can
 * assert its exact header value without parsing merged header maps.
 */
fun interface SyncHttpTransport {
    suspend fun exchange(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: String?,
        authorization: String,
    ): SyncHttpResponse
}

/**
 * `java.net.http` transport over the host JDK. No new production networking
 * dependency. Requests time out after 30 seconds; coroutine cancellation cancels
 * the in-flight send. Only loopback origins are accepted.
 */
class JdkSyncHttpTransport(baseUrl: String) : SyncHttpTransport {
    private val origin: String = baseUrl.trimEnd('/')
    private val client: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    init {
        val uri = try {
            java.net.URI(origin)
        } catch (e: Exception) {
            throw IllegalArgumentException("Sync origin is not a valid URI")
        }
        require(uri.scheme == "http" || uri.scheme == "https") {
            "Sync origin must use http or https"
        }
        val host = uri.host?.lowercase()
        require(host == "127.0.0.1" || host == "localhost" || host == "::1") {
            "Refusing non-loopback sync origin"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Sync origin must carry no credentials, query, or fragment"
        }
        val path = uri.path
        require(path == null || path.isEmpty() || path == "/") {
            "Sync origin must be root-only"
        }
    }

    override suspend fun exchange(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: String?,
        authorization: String,
    ): SyncHttpResponse {
        val uri = java.net.URI(buildUrl(origin, path, query))
        val publisher = if (body == null) {
            java.net.http.HttpRequest.BodyPublishers.noBody()
        } else {
            java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
        }
        val builder = java.net.http.HttpRequest.newBuilder(uri)
            .method(method, publisher)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", authorization)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val future = client.sendAsync(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, error ->
                if (!cont.isActive) return@whenComplete
                if (error != null) {
                    val cause = (error as? java.util.concurrent.CompletionException)?.cause ?: error
                    cont.resumeWith(Result.failure(cause))
                } else {
                    cont.resume(
                        SyncHttpResponse(response.statusCode(), response.headers().map(), response.body()),
                        null,
                    )
                }
            }
        }
    }

    internal fun buildUrl(origin: String, path: String, query: Map<String, String>): String {
        if (query.isEmpty()) return origin + path
        val encoded = query.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" +
                URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        return "$origin$path?$encoded"
    }
}
