package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin

/**
 * Durable private preparation authority: the read-only recovery observation
 * seam plus one atomic reservation. The existing Room adapter implements this
 * interface; reservation stays reservation, not activation or promotion.
 */
internal interface SessionPreparationAuthority : SessionRecoveryAuthority {
    suspend fun beginPreparation(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
        operationId: String,
    ): RecoveryRoomSnapshot
}

/**
 * One stable nonsecret private prepared observation: the exact PREPARING Room
 * snapshot and the positive vault revision holding its matching PREPARED
 * record. Advisory only: it never authorizes a later write, promotion or
 * network action, which must freshly fence both durable stores instead.
 * Displays are fixed and redacted.
 */
internal data class PreparedSessionObservation(
    val room: RecoveryRoomSnapshot,
    val vaultRevision: Long,
) {
    init {
        if (room.phase != RecoveryRoomPhase.PREPARING ||
            room.identity == null ||
            room.baselineComplete ||
            vaultRevision < 1L
        ) {
            throw SessionPreparationException(SessionPreparationFailure.INVALID_INPUT)
        }
    }

    override fun toString(): String = "PreparedSessionObservation(redacted)"
}

/**
 * Read-only restart classification. NotPreparing covers every non-PREPARING
 * Room phase without probing the vault; NeedsGrant carries a stably reread
 * reservation without credentials; Prepared carries a stably reread
 * observation; Blocked carries a closed failure. Advisory only, never a
 * reusable write or network authority.
 */
internal sealed interface SessionPreparationRecovery {
    data object NotPreparing : SessionPreparationRecovery

    data class NeedsGrant(val room: RecoveryRoomSnapshot) : SessionPreparationRecovery {
        override fun toString(): String = "NeedsGrant(redacted)"
    }

    data class Prepared(val observation: PreparedSessionObservation) : SessionPreparationRecovery {
        override fun toString(): String = "Prepared(redacted)"
    }

    data class Blocked(val failure: SessionPreparationFailure) : SessionPreparationRecovery {
        override fun toString(): String = "Blocked(failure=$failure)"
    }
}
