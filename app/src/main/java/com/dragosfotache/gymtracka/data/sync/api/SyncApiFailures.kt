package com.dragosfotache.gymtracka.data.sync.api

/**
 * Explicit top-level service failure categories. Per-mutation rejection
 * remains a typed push outcome, never an exception. Cancellation propagates
 * normally and adapters must never convert it into one of these failures.
 *
 * Messages are fixed category text and constructors take no message, cause,
 * request, or response arguments, so neither credentials nor raw
 * request/response bodies can be echoed or retained through these types.
 * Each failure carries a stable machine-readable [code]; only
 * [RequestRejected] additionally carries the service-issued error code.
 */

/** Deliberate client cap for a service retry hint: five minutes. */
const val MAX_RETRY_AFTER_MILLIS = 300_000L

sealed class SyncApiException(val code: String, message: String) : Exception(message, null) {
    /**
     * Transient transport failure: offline, server error, or throttling.
     * [retryAfterMillis] carries an optional service retry hint in
     * milliseconds, deliberately bounded to [MAX_RETRY_AFTER_MILLIS];
     * null means the caller applies its own bounded backoff.
     */
    class Transient(retryAfterMillis: Long? = null) :
        SyncApiException("transient", "transient sync failure; retry with bounded backoff") {
        val retryAfterMillis: Long? = retryAfterMillis?.coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
    }

    /** Authentication failure or expired session requiring foreground reauthentication. */
    class AuthenticationRequired :
        SyncApiException("authentication_required", "foreground reauthentication required")

    /** Change cursor is older than retained history; a full resync is required. */
    class CursorExpired :
        SyncApiException("cursor_expired", "change cursor expired; full resync required")

    /** Permanent top-level request rejection carrying the service error code. */
    class RequestRejected(val errorCode: String) :
        SyncApiException("request_rejected", "permanent top-level request rejection")

    /** Malformed or internally inconsistent protocol response. */
    class MalformedResponse :
        SyncApiException("malformed_response", "malformed or inconsistent protocol response")
}
