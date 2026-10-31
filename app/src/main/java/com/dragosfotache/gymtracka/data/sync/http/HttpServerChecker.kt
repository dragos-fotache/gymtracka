package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.ServerChecker
import com.dragosfotache.gymtracka.data.sync.api.ServerCheckResult
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceError
import kotlinx.coroutines.CancellationException

/** A single credential-free probe. Never enters an authenticated sync/auth adapter. */
internal class HttpServerChecker internal constructor(
    private val transport: ServiceHttpTransport,
) : ServerChecker {
    constructor(origin: ServiceOrigin) : this(
        OkHttpServiceTransport(
            origin,
            ServiceHttpTimeouts(
                connectMillis = 3_000L,
                readMillis = 5_000L,
                writeMillis = 5_000L,
                callMillis = 5_000L,
            ),
        ),
    )

    override suspend fun check(): ServerCheckResult = try {
        transport.withResponse(ServiceHttpRequest(ServiceHttpMethod.GET, "/v1/sync/pull")) { response ->
            when {
                response.statusCode in 500..599 -> ServerCheckResult.SERVER_ERROR
                response.statusCode != 401 -> ServerCheckResult.UNEXPECTED_RESPONSE
                decodeServiceError(response.readJsonBody()).errorCode == "authentication_required" ->
                    ServerCheckResult.CONNECTED
                else -> ServerCheckResult.UNEXPECTED_RESPONSE
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: SyncApiException.Transient) {
        ServerCheckResult.UNREACHABLE
    } catch (_: Exception) {
        ServerCheckResult.UNEXPECTED_RESPONSE
    }

    override fun toString(): String = "HttpServerChecker(redacted)"
}
