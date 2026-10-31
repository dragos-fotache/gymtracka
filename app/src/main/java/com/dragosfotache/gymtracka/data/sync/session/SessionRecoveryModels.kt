package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid

/**
 * Durable Room authorization observation phase for startup recovery.
 * Process-local evidence only: stored labels alone never authorize network
 * or account visibility. SIGNED_OUT means no authorized identity; PREPARING
 * and TRANSITIONING are recoverable only through the session-authority journal;
 * only ACTIVE with a complete baseline is eligible for offline visibility.
 */
internal enum class RecoveryRoomPhase {
    SIGNED_OUT,
    PREPARING,
    ACTIVE,
    TRANSITIONING,
}

/**
 * Process-local authorization evidence binding a service session to its
 * durable activation operation. The binding already validates
 * origin/account/positive generation; the operation is a canonical
 * lowercase UUID. Displays are fixed and redacted.
 */
internal data class RecoveryIdentity(
    val binding: ServiceSessionBinding,
    val operationId: String,
) {
    init {
        try {
            requireWorkoutUuid(operationId, "id")
        } catch (_: Exception) {
            throw SessionRecoveryException(SessionRecoveryFailure.INVALID_INPUT)
        }
    }

    override fun toString(): String = "RecoveryIdentity(redacted)"
}

/**
 * One immutable Room authorization observation: monotonic revision, durable
 * session generation fence, privately cached account, journal phase,
 * optional activation identity, and complete-cache flag. Equality covers
 * every field; no credential material lives here.
 *
 * SIGNED_OUT requires a null identity and an incomplete baseline; the cached
 * account may retain a private account. All other phases require an
 * identity whose binding generation exactly equals [sessionGeneration] and
 * whose account exactly equals the cached account. Invalid inputs fail
 * closed without echoing identifiers or supplied text.
 */
internal data class RecoveryRoomSnapshot(
    val revision: Long,
    val sessionGeneration: Long,
    val cachedAccountId: String?,
    val phase: RecoveryRoomPhase,
    val identity: RecoveryIdentity?,
    val baselineComplete: Boolean,
) {
    init {
        if (revision < 0L || sessionGeneration < 0L) {
            throw SessionRecoveryException(SessionRecoveryFailure.INVALID_INPUT)
        }
        if (cachedAccountId != null) {
            try {
                requireWorkoutUuid(cachedAccountId, "accountId")
            } catch (_: Exception) {
                throw SessionRecoveryException(SessionRecoveryFailure.INVALID_INPUT)
            }
        }
        if (phase == RecoveryRoomPhase.SIGNED_OUT) {
            if (identity != null || baselineComplete) {
                throw SessionRecoveryException(SessionRecoveryFailure.INVALID_INPUT)
            }
        } else {
            val observed = identity
            if (observed == null ||
                observed.binding.sessionGeneration != sessionGeneration ||
                cachedAccountId != observed.binding.accountId
            ) {
                throw SessionRecoveryException(SessionRecoveryFailure.INVALID_INPUT)
            }
        }
    }

    override fun toString(): String = "RecoveryRoomSnapshot(redacted)"
}

/**
 * Durable Room authorization observation seam for startup recovery. [RoomSessionAuthority] supplies explicit observations.
 */
internal fun interface SessionRecoveryAuthority {
    suspend fun read(): RecoveryRoomSnapshot
}

/** Closed startup privacy/recovery outcome categories. */
internal enum class SessionRecoveryReason {
    CONFIGURATION_UNAVAILABLE,
    SIGNED_OUT,
    TRANSITION_REQUIRED,
    VAULT_MISSING,
    VAULT_UNAVAILABLE,
    IDENTITY_MISMATCH,
    BASELINE_INCOMPLETE,
    AUTHORITY_UNAVAILABLE,
}

/**
 * Startup visibility states. PrivacyLocked is the initial and fenced state;
 * LocalOnly means signed-out local use; Locked means a private cached
 * account exists but must stay hidden; Active carries the one currently
 * authorized handle. Reason displays are closed categories only.
 */
internal sealed interface SessionRecoveryState {
    data object PrivacyLocked : SessionRecoveryState

    data class LocalOnly(val reason: SessionRecoveryReason) : SessionRecoveryState {
        override fun toString(): String = "LocalOnly(reason=$reason)"
    }

    data class Locked(val reason: SessionRecoveryReason) : SessionRecoveryState {
        override fun toString(): String = "Locked(reason=$reason)"
    }

    data class Active(val session: RecoveredSession) : SessionRecoveryState {
        override fun toString(): String = "Active(redacted)"
    }
}
