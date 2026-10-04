package com.dragosfotache.gymtracka.data.sync.session

/**
 * Closed failures for the unused serialized session-refresh machinery. The
 * message is fixed and the cause is always null; neither ever carries token,
 * request-body, key, or account material.
 */
internal enum class SessionRefreshFailure {
    INVALID_INPUT,
    STALE_SESSION,
    ACCESS_UNAVAILABLE,
    UNAVAILABLE,
}

/**
 * Fixed-message refresh failure with closed cause and disabled suppression, so no raw
 * error escapes through causes or suppressed entries. The latch/state it may carry
 * is the closed [failure] category only.
 */
internal class SessionRefreshException(val failure: SessionRefreshFailure) :
    RuntimeException("Session refresh failure", null, false, true) {
    override fun toString(): String = "SessionRefreshException(failure=$failure)"
}
