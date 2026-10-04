package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceChallenge
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceError
import com.dragosfotache.gymtracka.data.sync.codec.decodeServiceSession
import com.dragosfotache.gymtracka.data.sync.codec.encodeGoogleChallengeRequest
import com.dragosfotache.gymtracka.data.sync.codec.encodeLogoutRequest
import com.dragosfotache.gymtracka.data.sync.codec.parseServiceResponseObject
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Production [ServiceAuthApi] over the accepted secure transport. Each explicit call freezes its immutable
 * request bytes before the single send, transmits exactly once with no Authorization bearer, and decodes
 * complete challenge/grant bodies through the strict service response entry point. Typed exchange/refresh
 * attempts supply their retained exact UTF-8 body and header-only idempotency key; the adapter never
 * reserializes parsed fields, regenerates keys, retries, or recaptures restored attempts. Logout succeeds
 * only for a verified bodyless 204. Display is fixed and redacted; origins, bodies, keys, tokens and
 * purposes never appear in logs.
 */
internal class HttpServiceAuthApi internal constructor(
    origin: ServiceOrigin,
    private val transport: ServiceHttpTransport,
) : ServiceAuthApi {
    constructor(
        origin: ServiceOrigin,
        timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
    ) : this(origin, OkHttpServiceTransport(origin, timeouts))

    override suspend fun challenge(purpose: GoogleChallengePurpose): ServiceChallenge {
        val body = try {
            encodeGoogleChallengeRequest(purpose)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid challenge request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid challenge request")
        }
        val request = try {
            ServiceHttpRequest(ServiceHttpMethod.POST, CHALLENGE_PATH, emptyList(), emptyMap(), body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid challenge request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid challenge request")
        }
        return withAuthResponse(request) { response ->
            if (response.statusCode != 200) {
                throwAuthFailure(AuthHttpOperation.CHALLENGE, response)
            }
            strictAuthDecode {
                val text = response.readJsonBody()
                parseServiceResponseObject(text)
                decodeServiceChallenge(text)
            }
        }
    }

    override suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession {
        try {
            parseServiceResponseObject(attempt.requestJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SyncModelException("Invalid exchange request")
        }
        val request = try {
            ServiceHttpRequest(
                ServiceHttpMethod.POST,
                EXCHANGE_PATH,
                emptyList(),
                mapOf("Idempotency-Key" to attempt.idempotencyKey),
                attempt.requestJson,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid exchange request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid exchange request")
        }
        return withAuthResponse(request) { response ->
            if (response.statusCode != 200) {
                throwAuthFailure(AuthHttpOperation.EXCHANGE, response)
            }
            strictAuthDecode {
                val text = response.readJsonBody()
                parseServiceResponseObject(text)
                decodeServiceSession(text)
            }
        }
    }

    override suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession {
        try {
            parseServiceResponseObject(attempt.requestJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SyncModelException("Invalid refresh request")
        }
        val request = try {
            ServiceHttpRequest(
                ServiceHttpMethod.POST,
                REFRESH_PATH,
                emptyList(),
                mapOf("Idempotency-Key" to attempt.idempotencyKey),
                attempt.requestJson,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid refresh request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid refresh request")
        }
        return withAuthResponse(request) { response ->
            if (response.statusCode != 200) {
                throwAuthFailure(AuthHttpOperation.REFRESH, response)
            }
            strictAuthDecode {
                val text = response.readJsonBody()
                parseServiceResponseObject(text)
                decodeServiceSession(text)
            }
        }
    }

    override suspend fun logout(refreshToken: OpaqueToken) {
        val body = try {
            encodeLogoutRequest(refreshToken)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid logout request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid logout request")
        }
        val request = try {
            ServiceHttpRequest(ServiceHttpMethod.POST, LOGOUT_PATH, emptyList(), emptyMap(), body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncModelException) {
            throw SyncModelException("Invalid logout request")
        } catch (error: Exception) {
            throw SyncModelException("Invalid logout request")
        }
        withAuthResponse(request) { response ->
            if (response.statusCode != 204) {
                throwAuthFailure(AuthHttpOperation.LOGOUT, response)
            }
            val first = try {
                response.readBodyChunk(ByteArray(1))
            } catch (error: CancellationException) {
                throw error
            } catch (error: SyncApiException) {
                throw error
            } catch (error: Exception) {
                throw SyncApiException.Transient()
            }
            if (first != -1) {
                throw SyncApiException.MalformedResponse()
            }
        }
    }

    override fun toString(): String = "HttpServiceAuthApi(redacted)"

    /**
     * Single-send boundary for exactly one transport exchange per explicit call. Preserves the original
     * cancellation and safe transport categories; unexpected injected seam exceptions become closed-cause
     * transient without raw causes. Preflight request failures stay outside this boundary as fixed safe
     * model failures; decoder scopes inside the consumer still classify arbitrary decode errors as
     * malformed responses.
     */
    private suspend fun <T> withAuthResponse(
        request: ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T {
        try {
            return transport.withResponse(request, consume)
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw error
        } catch (error: SyncApiException) {
            currentCoroutineContext().ensureActive()
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw SyncApiException.Transient()
        }
    }

    /**
     * Routes a non-success response. Size/rate/retryable statuses need no body read; REFRESH 401
     * immediately requires foreground reauthentication; EXCHANGE 401 performs a best-effort strict error
     * read where any non-cancellation read/codec failure is malformed; all other ordinary 4xx strictly
     * read the error envelope where genuine I/O stays transient and invalid media/framing/grammar/shape
     * is malformed. Unexpected statuses need no body read. Cancellation always wins.
     */
    private suspend fun throwAuthFailure(
        operation: AuthHttpOperation,
        response: ServiceHttpResponse,
    ): Nothing {
        val status = response.statusCode
        if (status == 413) {
            throw SyncApiException.RequestTooLarge()
        }
        if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
            throw SyncApiException.Transient(retryAfterMillis(response.headers))
        }
        if (operation == AuthHttpOperation.REFRESH && status == 401) {
            throw SyncApiException.ReauthenticationRequired()
        }
        if (operation == AuthHttpOperation.EXCHANGE && status == 401) {
            throw readExchange401Failure(response)
        }
        if (status in 400..499) {
            throw classifyAuthHttpFailure(
                operation,
                status,
                readStrictAuthErrorCode(response),
                response.headers,
            )
        }
        throw SyncApiException.MalformedResponse()
    }

    /**
     * Best-effort strict EXCHANGE 401 error read. A valid envelope routes through the frozen table;
     * any non-cancellation read/codec failure is malformed, never an invented proof code or access
     * recovery.
     */
    private suspend fun readExchange401Failure(response: ServiceHttpResponse): SyncApiException {
        try {
            val text = response.readJsonBody()
            return classifyAuthHttpFailure(
                AuthHttpOperation.EXCHANGE,
                401,
                decodeServiceError(text).errorCode,
                response.headers,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SyncApiException.MalformedResponse()
        }
    }

    /**
     * Strict error-code read for ordinary 4xx including CHALLENGE/LOGOUT 401. Genuine generic body I/O
     * stays transient; invalid media/framing/UTF-8/grammar/shape is malformed. Cancellation wins.
     */
    private suspend fun readStrictAuthErrorCode(response: ServiceHttpResponse): String {
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
        private const val CHALLENGE_PATH = "/v1/auth/google/challenge"
        private const val EXCHANGE_PATH = "/v1/auth/google"
        private const val REFRESH_PATH = "/v1/auth/refresh"
        private const val LOGOUT_PATH = "/v1/auth/logout"
    }
}

/**
 * Decodes a successful body strictly; grammar/result failures become closed-cause malformed responses
 * while transport failures and cancellation pass through.
 */
private suspend fun <T> strictAuthDecode(block: suspend () -> T): T {
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
