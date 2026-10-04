package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException

/** Service-auth operation selecting the context-sensitive rows of the failure table. */
internal enum class AuthHttpOperation { CHALLENGE, EXCHANGE, REFRESH, LOGOUT }

/**
 * Pure operation/status/code failure classifier for service-auth routes. No JSON parsing, body reads, I/O,
 * retries or bearer endpoints: callers supply the strictly decoded error code, with null meaning an invalid
 * or unavailable strict error envelope. An empty supplied code is treated as absent; nonempty codes are
 * retained exactly with no whitelist, trim or casing. This freezes the accepted status precedence: 413 and
 * 429/retryable 5xx keep their categories regardless of code text, REFRESH 401 always requires foreground
 * reauthentication, and only EXCHANGE 401 with the exact reauthentication code promotes to reauthentication.
 * It never returns AuthenticationRequired or read-token expiry categories: these auth routes do not require
 * an access bearer.
 */
internal fun classifyAuthHttpFailure(
    operation: AuthHttpOperation,
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
    if (operation == AuthHttpOperation.REFRESH && statusCode == 401) {
        return SyncApiException.ReauthenticationRequired()
    }
    if (operation == AuthHttpOperation.EXCHANGE &&
        statusCode == 401 &&
        code == "reauthentication_required"
    ) {
        return SyncApiException.ReauthenticationRequired()
    }
    if (statusCode in 400..499) {
        if (code != null) {
            return SyncApiException.RequestRejected(code)
        }
        return SyncApiException.MalformedResponse()
    }
    return SyncApiException.MalformedResponse()
}
