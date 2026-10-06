package com.dragosfotache.gymtracka.data.sync.session

/**
 * Closed failures for the inert Room session-authority adapter. The message is
 * fixed and the cause is always null; neither ever carries account, origin,
 * operation, SQL or raw error material.
 */
internal enum class RoomSessionAuthorityFailure {
    INVALID_INPUT,
    CORRUPT_AUTHORITY,
    STALE_AUTHORITY,
    ACCOUNT_MISMATCH,
    LOCAL_DATA_REQUIRES_DECISION,
    COUNTER_OVERFLOW,
    UNAVAILABLE,
}

/**
 * Fixed-message authority failure with closed cause and disabled suppression,
 * so no raw error escapes through causes or suppressed entries. The latch it
 * may carry is the closed [failure] category only.
 */
internal class RoomSessionAuthorityException(val failure: RoomSessionAuthorityFailure) :
    RuntimeException("Room session authority failure", null, false, true) {
    override fun toString(): String = "RoomSessionAuthorityException(failure=$failure)"
}
