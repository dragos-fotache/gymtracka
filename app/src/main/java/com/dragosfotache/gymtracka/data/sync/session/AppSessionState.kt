package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession

/**
 * R09h app-facing transition pause/drain barrier. Acquired while the previous
 * account handle is still valid so accepted edits and terminal rest can persist
 * before invalidation. There is no permissive default; the caller supplies real
 * admission/drain machinery (R12c owns canonical timer-impact decisions).
 *
 * Safety contract: an implementation must release an acquired pause if prompt
 * cancellation prevents delivering its result, so a cancelled acquisition can
 * never hold the app drain open. (R12a owns this release safety.)
 */
internal fun interface AppSessionTransitionBarrier {
    suspend fun acquire(previous: RepositorySession?): ProjectionPublicationLease
}

/** Closed app-facing private reason categories. Fixed displays only. */
internal enum class AppSessionReason {
    CONFIGURATION_UNAVAILABLE,
    SIGN_IN_REQUIRED,
    PREPARATION_REQUIRED,
    IDENTITY_MISMATCH,
    LOCAL_DATA_REQUIRES_DECISION,
    UNAVAILABLE,
}

/** Closed cached delivery status. Safe transient/reauth stops only. */
internal enum class AppSessionDeliveryStatus {
    IDLE,
    RETRY_REQUIRED,
    REAUTHENTICATION_REQUIRED,
}

/**
 * App-facing visibility states. PrivacyLocked is the initial and fenced state;
 * Private carries a nonsecret advisory reason plus an optional advisory Room
 * observation (never authority); Active carries the one current account handle
 * plus its closed delivery status. Displays are fixed/redacted and expose no
 * grants, credentials, request bytes or exception causes.
 */
internal sealed interface AppSessionState {
    data object PrivacyLocked : AppSessionState

    data class Private(
        val reason: AppSessionReason,
        val room: RecoveryRoomSnapshot?,
    ) : AppSessionState {
        override fun toString(): String = "Private(reason=$reason)"
    }

    data class Active(
        val session: AppAccountSession,
        val deliveryStatus: AppSessionDeliveryStatus = AppSessionDeliveryStatus.IDLE,
    ) : AppSessionState {
        override fun toString(): String = "Active(deliveryStatus=$deliveryStatus)"
    }
}

/** Fixed-text process staleness failure. No causes, IDs, tokens or bytes escape. */
internal class AppSessionStaleException : IllegalStateException("App session is no longer current") {
    override fun toString(): String = "AppSessionStaleException"
}

/**
 * Closed app-facing refusal with a fixed reason. Fixed message, no cause and
 * no suppressed entries, so no account, origin, operation, token, cursor,
 * body or raw error escapes. Displays are fixed/redacted.
 */
internal class AppSessionException(val reason: AppSessionReason) :
    RuntimeException("App session failure", null, false, true) {
    override fun toString(): String = "AppSessionException(reason=$reason)"
}
