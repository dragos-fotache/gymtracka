package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin

/**
 * Durable private reauthentication authority: the read-only recovery
 * observation seam plus atomic settled occupied-credential reservation and
 * finalization. The existing Room adapter implements this interface;
 * reservation stays reservation, finalization stays finalization, neither
 * is activation, promotion or cleanup.
 */
internal interface SessionReauthenticationAuthority : SessionRecoveryAuthority {
    suspend fun beginReauthentication(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        operationId: String,
    ): RecoveryRoomSnapshot

    suspend fun finishReauthentication(expected: RecoveryRoomSnapshot): RecoveryRoomSnapshot
}

/**
 * Read-only restart classification for inert reauthentication. NotReauthenticating
 * covers every non-TRANSITIONING/non-PREPARING Room phase without probing the
 * vault; NeedsGrant carries a stably reread reservation-only observation;
 * NeedsFinalization carries a stably reread TRANSITIONING observation plus the
 * positive vault revision holding its installed target; Prepared carries a
 * stably reread observation; Blocked carries a closed failure. Advisory only,
 * never a reusable write or network authority. Displays are fixed and redacted.
 */
internal sealed interface SessionReauthenticationRecovery {
    data object NotReauthenticating : SessionReauthenticationRecovery

    data class NeedsGrant(val room: RecoveryRoomSnapshot) : SessionReauthenticationRecovery {
        init {
            if (room.phase != RecoveryRoomPhase.TRANSITIONING ||
                room.identity == null ||
                room.baselineComplete
            ) {
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.INVALID_INPUT,
                )
            }
        }

        override fun toString(): String = "NeedsGrant(redacted)"
    }

    data class NeedsFinalization(
        val room: RecoveryRoomSnapshot,
        val vaultRevision: Long,
    ) : SessionReauthenticationRecovery {
        init {
            if (room.phase != RecoveryRoomPhase.TRANSITIONING ||
                room.identity == null ||
                room.baselineComplete ||
                vaultRevision < 1L
            ) {
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.INVALID_INPUT,
                )
            }
        }

        override fun toString(): String = "NeedsFinalization(redacted)"
    }

    data class Prepared(val observation: PreparedSessionObservation) : SessionReauthenticationRecovery {
        override fun toString(): String = "Prepared(redacted)"
    }

    data class Blocked(val failure: SessionReauthenticationFailure) : SessionReauthenticationRecovery {
        override fun toString(): String = "Blocked(failure=$failure)"
    }
}

/**
 * Advisory settled old-ACTIVE eligibility after inert exact-pending-refresh
 * reconciliation: the unchanged old ACTIVE Room observation plus the positive
 * vault revision holding its exact settled ACTIVE grant with no pending
 * predecessor. Carries no grant, token, or lease; reauthentication must freshly
 * read/admit it again before any replacement. Displays are fixed and redacted.
 */
internal data class SettledSessionObservation(
    val room: RecoveryRoomSnapshot,
    val vaultRevision: Long,
) {
    init {
        if (room.phase != RecoveryRoomPhase.ACTIVE ||
            room.identity == null ||
            !room.baselineComplete ||
            vaultRevision < 1L
        ) {
            throw SessionReauthenticationException(
                SessionReauthenticationFailure.INVALID_INPUT,
            )
        }
    }

    override fun toString(): String = "SettledSessionObservation(redacted)"
}
