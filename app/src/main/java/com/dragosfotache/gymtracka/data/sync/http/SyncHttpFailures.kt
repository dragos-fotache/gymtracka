package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException

/** Sync operation selecting the context-sensitive rows of the failure table. */
internal enum class SyncHttpOperation { PUSH, PULL, SNAPSHOT }

/**
 * Pure operation/status/code failure classifier. No JSON parsing, body reads, I/O,
 * retries or auth endpoints: callers supply the strictly decoded error code, with
 * null meaning an invalid or unavailable strict error envelope. An empty supplied
 * code is treated as absent; nonempty codes are retained exactly. This freezes the
 * accepted status precedence: 413/429/retryable 5xx and 401 keep their categories
 * regardless of code text, and a read-token code on the wrong operation or wrong
 * ordinary 4xx status stays a permanent rejection, never an expiry shortcut.
 */
internal fun classifySyncHttpFailure(
    operation: SyncHttpOperation,
    statusCode: Int,
    errorCode: String?,
    headers: Map<String, List<String>> = emptyMap(),
): SyncApiException {
    val code = if (errorCode.isNullOrEmpty()) null else errorCode
    if (statusCode == 413) {
        return SyncApiException.RequestTooLarge()
    }
    if (statusCode == 429 ||
        statusCode == 500 ||
        statusCode == 502 ||
        statusCode == 503 ||
        statusCode == 504
    ) {
        return SyncApiException.Transient(retryAfterMillis(headers))
    }
    if (statusCode == 401) {
        if (code == "reauthentication_required") {
            return SyncApiException.ReauthenticationRequired()
        }
        return SyncApiException.AuthenticationRequired()
    }
    if (statusCode in 400..499) {
        if (operation == SyncHttpOperation.PULL && statusCode == 400 && code == "invalid_cursor") {
            return SyncApiException.InvalidCursor()
        }
        if (operation == SyncHttpOperation.PULL && statusCode == 410 && code == "cursor_expired") {
            return SyncApiException.CursorExpired()
        }
        if (operation == SyncHttpOperation.SNAPSHOT &&
            statusCode == 400 &&
            code == "invalid_snapshot_token"
        ) {
            return SyncApiException.InvalidSnapshotToken()
        }
        if (operation == SyncHttpOperation.SNAPSHOT && statusCode == 410 && code == "snapshot_expired") {
            return SyncApiException.SnapshotExpired()
        }
        if (code != null) {
            return SyncApiException.RequestRejected(code)
        }
        return SyncApiException.MalformedResponse()
    }
    return SyncApiException.MalformedResponse()
}
