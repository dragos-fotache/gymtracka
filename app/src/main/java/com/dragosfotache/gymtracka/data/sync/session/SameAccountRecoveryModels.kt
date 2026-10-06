package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin

/**
 * Durable same-account recovery authority: the existing settled
 * reauthentication authority plus a narrow retained-account reservation.
 * Check reads only; begin reserves one private TRANSITIONING fence for the
 * same retained origin/account with a fresh operation. Neither activates,
 * promotes, cleans up, nor inspects the vault.
 */
internal interface SameAccountRecoveryAuthority : SessionReauthenticationAuthority {
    suspend fun checkSameAccountRecovery(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
    )

    suspend fun beginSameAccountRecovery(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
        operationId: String,
    ): RecoveryRoomSnapshot
}

/**
 * Read-only same-account recovery classification. NoRetainedAccount covers a
 * cache-empty Room observation without probing the vault; EligibleForReplacement
 * carries a stable SIGNED_OUT/ACTIVE retained observation with an eligible
 * source; NeedsGrant carries a private observation with a missing or older
 * predecessor source; NeedsFinalization carries a stable TRANSITIONING
 * observation plus the positive vault revision holding its installed target;
 * Prepared carries a stable observation; Blocked carries a closed failure.
 * Advisory only, never credentials, reusable writes or network authority.
 * Displays are fixed and redacted.
 */
internal sealed interface SameAccountRecovery {
    data object NoRetainedAccount : SameAccountRecovery {
        override fun toString(): String = "NoRetainedAccount"
    }

    data class EligibleForReplacement(val room: RecoveryRoomSnapshot) : SameAccountRecovery {
        init {
            if ((room.phase != RecoveryRoomPhase.SIGNED_OUT && room.phase != RecoveryRoomPhase.ACTIVE) ||
                room.cachedAccountId == null
            ) {
                throw SessionReauthenticationException(
                    SessionReauthenticationFailure.INVALID_INPUT,
                )
            }
        }

        override fun toString(): String = "EligibleForReplacement(redacted)"
    }

    data class NeedsGrant(val room: RecoveryRoomSnapshot) : SameAccountRecovery {
        init {
            if (room.cachedAccountId == null) {
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
    ) : SameAccountRecovery {
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

    data class Prepared(val observation: PreparedSessionObservation) : SameAccountRecovery {
        override fun toString(): String = "Prepared(redacted)"
    }

    data class Blocked(val failure: SessionReauthenticationFailure) : SameAccountRecovery {
        override fun toString(): String = "Blocked(failure=$failure)"
    }
}
