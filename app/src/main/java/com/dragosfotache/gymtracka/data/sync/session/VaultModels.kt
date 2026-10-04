package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.codec.parseServiceResponseObject
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import kotlinx.coroutines.CancellationException

/**
 * Immutable stored session models for the inert encrypted session vault.
 *
 * Prepared/active are stored journal labels only; neither value authorizes an
 * account scope or a network request. Every model validates in its constructor
 * and therefore in `copy` as well; displays are fixed and redacted. No clock,
 * UUID generation, Google/exchange/proof field, or length cap lives here.
 */

/** Stored journal label for the vault record lifecycle. */
internal enum class VaultSessionPhase {
    PREPARED,
    ACTIVE,
}

/** Uncertain refresh attempt retained alongside its stored grant. */
internal data class VaultPendingRefresh(
    val operationId: String,
    val attempt: ServiceRefreshAttempt,
) {
    init {
        requireVaultUuid(operationId)
        // Strict original-scalar fidelity for the retained body: the typed
        // attempt alone can accept an overflow-normalized scalar (for example an
        // unquoted 1e400 decoded as String), so the approved response preflight
        // re-checks that original JSON scalar kinds survived before restore.
        try {
            parseServiceResponseObject(attempt.requestJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
    }

    override fun toString(): String = "VaultPendingRefresh(redacted)"
}

/**
 * One bound session record: the service origin/account/generation binding,
 * the durable activation/session operation, the journal phase, the stored
 * grant, and the optional uncertain refresh attempt. The record account must
 * exactly equal the grant account, and a retained attempt must carry exactly
 * the stored grant's refresh token.
 */
internal data class VaultSessionRecord(
    val binding: ServiceSessionBinding,
    val operationId: String,
    val phase: VaultSessionPhase,
    val session: ServiceSession,
    val pendingRefresh: VaultPendingRefresh? = null,
) {
    init {
        requireVaultUuid(operationId)
        if (binding.accountId != session.account.accountId) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        val pending = pendingRefresh
        if (pending != null && pending.attempt.refreshToken != session.refreshToken) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
    }

    override fun toString(): String = "VaultSessionRecord(redacted)"
}

/**
 * One revision-checked vault snapshot. Revision zero requires a null record
 * (genuinely empty store); clearing writes a positive-revision encrypted
 * tombstone instead, so a delayed old revision can never reinstall
 * credentials.
 */
internal data class VaultSnapshot(
    val revision: Long,
    val record: VaultSessionRecord?,
) {
    init {
        if (revision < 0L) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        if (revision == 0L && record != null) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
    }

    override fun toString(): String = "VaultSnapshot(redacted)"
}
