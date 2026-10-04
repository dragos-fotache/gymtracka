package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid

/**
 * Closed failures for the inert encrypted session vault. The message is fixed
 * and the cause is always null; neither ever carries token, request-body,
 * key, or path material.
 */
internal enum class VaultFailure {
    INVALID_RECORD,
    UNREADABLE,
    UNSUPPORTED_VERSION,
    UNAVAILABLE,
    CONFLICT,
    REVISION_EXHAUSTED,
}

/** Fixed-message vault failure; the cause is closed null so no raw error escapes. */
internal class SessionVaultException(val failure: VaultFailure) : RuntimeException("Session vault failure", null) {
    override fun toString(): String = "SessionVaultException(failure=$failure)"
}

/** Validates one canonical lowercase UUID, mapping any complaint to a closed record failure. */
internal fun requireVaultUuid(value: String): String {
    try {
        requireWorkoutUuid(value, "id")
    } catch (_: SyncModelException) {
        throw SessionVaultException(VaultFailure.INVALID_RECORD)
    }
    return value
}
