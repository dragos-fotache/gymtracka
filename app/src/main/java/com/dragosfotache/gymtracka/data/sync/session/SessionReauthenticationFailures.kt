package com.dragosfotache.gymtracka.data.sync.session

/**
 * Closed failures for inert settled occupied-credential reauthentication.
 * The message is fixed and the cause is always null; neither ever carries
 * token, request-body, key, account, origin, operation, SQL or raw error
 * material.
 */
internal enum class SessionReauthenticationFailure {
    INVALID_INPUT,
    STALE_OPERATION,
    STALE_AUTHORITY,
    IDENTITY_MISMATCH,
    PENDING_REFRESH,
    LOCAL_DATA_REQUIRES_DECISION,
    COUNTER_OVERFLOW,
    AUTHORITY_CORRUPT,
    AUTHORITY_UNAVAILABLE,
    VAULT_UNAVAILABLE,
    VAULT_CONFLICT,
}

/**
 * Fixed-message reauthentication failure with closed cause and disabled
 * suppression, so no raw error escapes through causes or suppressed entries.
 * The latch it may carry is the closed [failure] category only.
 */
internal class SessionReauthenticationException(val failure: SessionReauthenticationFailure) :
    RuntimeException("Session reauthentication failure", null, false, true) {
    override fun toString(): String = "SessionReauthenticationException(failure=$failure)"
}
