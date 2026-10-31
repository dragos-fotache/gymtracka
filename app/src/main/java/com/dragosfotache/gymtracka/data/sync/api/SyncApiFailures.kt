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
 * Invalid read tokens are distinct from expiry: only explicitly and
 * correctly routed expiry requests a full resync or a snapshot-stage
 * restart. Adapters never decode tokens, order them lexically, infer age,
 * or retry a null token implicitly.
 */

/** Deliberate client cap for a service retry hint: five minutes. */
const val MAX_RETRY_AFTER_MILLIS = 300_000L

sealed class SyncApiException(val code: String, message: String) : Exception(message, null) {
    /**
     * Transient transport failure: offline, server error, or throttling.
     * [retryAfterMillis] carries an optional service retry hint in
     * milliseconds, deliberately bounded to [MAX_RETRY_AFTER_MILLIS];
     * null means the caller applies its own bounded backoff.
     * [connectionFailure] is set only for actual I/O faults. A nonnull service hint always
     * takes precedence: its saved deadline is never reset by a connectivity check.
     */
    class Transient(retryAfterMillis: Long? = null, val connectionFailure: Boolean = false) :
        SyncApiException("transient", "transient sync failure; retry with bounded backoff") {
        val retryAfterMillis: Long? = retryAfterMillis?.coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
    }

    /**
     * Service access authentication failure: missing, invalid, or expired
     * access credential. A later serialized refresh-and-unchanged-retry may
     * recover it; it never authorizes account or cache deletion.
     */
    class AuthenticationRequired :
        SyncApiException("authentication_required", "service access authentication required")

    /**
     * Explicit foreground reauthentication failure: refresh cannot recover
     * the session. Requires foreground identity; never proves account
     * deletion and never authorizes account or cache deletion.
     */
    class ReauthenticationRequired :
        SyncApiException("reauthentication_required", "foreground reauthentication required")

    /**
     * Unknown, foreign, or malformed pull cursor. Invalid is not expired:
     * it must not request a full resync on its own.
     */
    class InvalidCursor :
        SyncApiException("invalid_cursor", "change cursor is invalid")

    /** Change cursor is older than retained history; a full resync is required. */
    class CursorExpired :
        SyncApiException("cursor_expired", "change cursor expired; full resync required")

    /**
     * Unknown, foreign, or malformed snapshot token. Invalid is not expired:
     * it must not discard staging or restart a snapshot on its own.
     */
    class InvalidSnapshotToken :
        SyncApiException("invalid_snapshot_token", "snapshot token is invalid")

    /**
     * Authentically expired snapshot token; discard staged pages and restart
     * the snapshot without its token.
     */
    class SnapshotExpired :
        SyncApiException("snapshot_expired", "snapshot expired; discard staging and restart")

    /**
     * Transport request-size rejection (HTTP 413, status authoritative).
     * Not a retryable transport fault nor a successful partial push: retain
     * frozen or unsent intent (frozen work may already have transmitted or
     * committed an uncertain prefix) and never endlessly resend, split a
     * semantic mutation, or mint new IDs automatically.
     */
    class RequestTooLarge :
        SyncApiException("request_too_large", "request too large; retain intent and require recovery")

    /** Permanent top-level request rejection carrying the service error code. */
    class RequestRejected(val errorCode: String) :
        SyncApiException("request_rejected", "permanent top-level request rejection")

    /** Malformed or internally inconsistent protocol response. */
    class MalformedResponse :
        SyncApiException("malformed_response", "malformed or inconsistent protocol response")
}
