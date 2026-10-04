package com.dragosfotache.gymtracka.data.sync.session

/**
 * Closed failures for startup/session recovery seams. The message is fixed
 * and the cause is always null; neither ever carries token, request-body,
 * key, or account material.
 */
internal enum class SessionRecoveryFailure {
    INVALID_INPUT,
    STALE_SESSION,
    UNAVAILABLE,
}

/**
 * Fixed-message recovery failure with closed cause and disabled suppression,
 * so no raw error escapes through causes or suppressed entries. The latch it
 * may carry is the closed [failure] category only.
 */
internal class SessionRecoveryException(val failure: SessionRecoveryFailure) :
    RuntimeException("Session recovery failure", null, false, true) {
    override fun toString(): String = "SessionRecoveryException(failure=$failure)"
}
