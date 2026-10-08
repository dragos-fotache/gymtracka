package com.dragosfotache.gymtracka.data.sync.api

/**
 * SDK-independent safe failures for foreground Google identity acquisition.
 *
 * Only fixed safe messages are exposed. Raw provider exceptions, messages,
 * causes, credential bundles, nonces, audiences, accounts, emails and tokens
 * must never appear in these exceptions, display strings or logs.
 */
enum class GoogleIdentityFailureReason {
    UNCONFIGURED,
    NOT_FOREGROUND,
    UNAVAILABLE,
    USER_CANCELLED,
    INVALID_CREDENTIAL,
    PROVIDER_FAILURE,
    BUSY,
    RETIRED,
}

/**
 * Safe Google identity failure carrying only a fixed [reason].
 *
 * The message is a fixed safe string per reason. No cause chain is attached:
 * SDK failures are classified, never wrapped, so no raw provider detail can
 * escape through [cause], [message] or [toString].
 */
class GoogleIdentityException(
    val reason: GoogleIdentityFailureReason,
) : Exception(fixedMessage(reason)) {

    companion object {
        private fun fixedMessage(reason: GoogleIdentityFailureReason): String =
            when (reason) {
                GoogleIdentityFailureReason.UNCONFIGURED -> "Google sign-in is not configured"
                GoogleIdentityFailureReason.NOT_FOREGROUND -> "Google sign-in requires a foreground activity"
                GoogleIdentityFailureReason.UNAVAILABLE -> "Google credentials are unavailable"
                GoogleIdentityFailureReason.USER_CANCELLED -> "Google sign-in was cancelled"
                GoogleIdentityFailureReason.INVALID_CREDENTIAL -> "Invalid Google credential"
                GoogleIdentityFailureReason.PROVIDER_FAILURE -> "Google sign-in failed"
                GoogleIdentityFailureReason.BUSY -> "Google sign-in is already in progress"
                GoogleIdentityFailureReason.RETIRED -> "Google sign-in is no longer available"
            }
    }
}
