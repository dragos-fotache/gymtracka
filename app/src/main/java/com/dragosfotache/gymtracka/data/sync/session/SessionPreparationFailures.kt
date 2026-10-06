package com.dragosfotache.gymtracka.data.sync.session

/**
 * Closed failures for inert prepared-session coordination. The message is
 * fixed and the cause is always null; neither ever carries token,
 * request-body, key, account, origin, operation, SQL or raw error material.
 */
internal enum class SessionPreparationFailure {
    INVALID_INPUT,
    NOT_PREPARING,
    STALE_AUTHORITY,
    IDENTITY_MISMATCH,
    VAULT_OCCUPIED,
    AUTHORITY_UNAVAILABLE,
    VAULT_UNAVAILABLE,
    COUNTER_OVERFLOW,
    LOCAL_DATA_REQUIRES_DECISION,
    ACCOUNT_MISMATCH,
    STALE_OPERATION,
}

/**
 * Fixed-message preparation failure with closed cause and disabled
 * suppression, so no raw error escapes through causes or suppressed entries.
 * The latch it may carry is the closed [failure] category only.
 */
internal class SessionPreparationException(val failure: SessionPreparationFailure) :
    RuntimeException("Session preparation failure", null, false, true) {
    override fun toString(): String = "SessionPreparationException(failure=$failure)"
}
