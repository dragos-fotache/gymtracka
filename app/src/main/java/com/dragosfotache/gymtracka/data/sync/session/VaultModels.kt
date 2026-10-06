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
 * grant, the optional uncertain refresh attempt, and the private archive of
 * superseded uncertain predecessors. The record account must exactly equal
 * the grant account, and a retained attempt must carry exactly the stored
 * grant's refresh token. Every archived source matches this record's
 * origin/account with a LOWER generation; the current refresh token never
 * equals an archived source's refresh token. Archived material is never
 * selected, replayed, rebound, promoted or used as authentication.
 */
internal data class VaultSessionRecord(
    val binding: ServiceSessionBinding,
    val operationId: String,
    val phase: VaultSessionPhase,
    val session: ServiceSession,
    val pendingRefresh: VaultPendingRefresh? = null,
    val supersededSessions: VaultSupersededSessions = VaultSupersededSessions(emptyList()),
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
        for (entry in supersededSessions.entries) {
            if (entry.record.binding.origin != binding.origin ||
                entry.record.binding.accountId != binding.accountId
            ) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
            if (entry.record.binding.sessionGeneration >= binding.sessionGeneration) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
            if (entry.record.session.refreshToken == session.refreshToken) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
        }
    }

    override fun toString(): String = "VaultSessionRecord(redacted)"
}

/**
 * One revision-checked vault snapshot. Revision zero requires a null record
 * (genuinely empty store); clearing writes a positive-revision encrypted
 * tombstone instead, so a delayed old revision can never reinstall
 * credentials. Every archived source revision must be positive and strictly
 * below the snapshot revision.
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
        val archived = record?.supersededSessions?.entries.orEmpty()
        for (entry in archived) {
            if (entry.sourceRevision < 1L || entry.sourceRevision >= revision) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
        }
    }

    override fun toString(): String = "VaultSnapshot(redacted)"
}

/**
 * One retained superseded session: the source vault revision that held it
 * plus the full original source record with its archive field emptied
 * (no recursive records). Private retained evidence only; never selected,
 * replayed, rebound, promoted or used as authentication. Displays are
 * fixed and redacted. Equality compares full source revision/binding/
 * operation/phase/grant/deadlines and semantic pending operation/key/body/
 * token, not attempt reference identity.
 */
internal class VaultSupersededSession(
    val sourceRevision: Long,
    val record: VaultSessionRecord,
) {
    init {
        if (sourceRevision < 1L) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        if (record.supersededSessions.entries.isNotEmpty()) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultSupersededSession) return false
        if (sourceRevision != other.sourceRevision) return false
        return vaultRecordsSemanticEqual(record, other.record)
    }

    override fun hashCode(): Int {
        var result = sourceRevision.hashCode()
        result = 31 * result + record.binding.hashCode()
        result = 31 * result + record.operationId.hashCode()
        result = 31 * result + record.phase.hashCode()
        result = 31 * result + record.session.hashCode()
        result = 31 * result + vaultPendingSemanticHash(record.pendingRefresh)
        return result
    }

    override fun toString(): String = "VaultSupersededSession(redacted)"
}

/**
 * Defensively owned, immutable ordered archive of superseded sessions.
 * Empty is allowed. Entries have strictly increasing source revisions and
 * source generations with a consistent origin/account; duplicates refuse.
 * Value equality/hash use [VaultSupersededSession] semantics. No mutable
 * list escapes. Displays are fixed and redacted.
 */
internal class VaultSupersededSessions(entries: List<VaultSupersededSession>) {
    val entries: List<VaultSupersededSession> =
        java.util.Collections.unmodifiableList(entries.toList())

    init {
        var previousRevision = -1L
        var previousGeneration = -1L
        val expectedOrigin = this.entries.firstOrNull()?.record?.binding?.origin
        val expectedAccount = this.entries.firstOrNull()?.record?.binding?.accountId
        for (entry in this.entries) {
            if (entry.sourceRevision < 1L) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
            if (entry.record.supersededSessions.entries.isNotEmpty()) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
            if (entry.sourceRevision <= previousRevision) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
            val generation = entry.record.binding.sessionGeneration
            if (generation <= previousGeneration) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
            if (entry.record.binding.origin != expectedOrigin ||
                entry.record.binding.accountId != expectedAccount
            ) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
            previousRevision = entry.sourceRevision
            previousGeneration = generation
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultSupersededSessions) return false
        return entries == other.entries
    }

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "VaultSupersededSessions(redacted)"
}

internal fun vaultPendingSemanticEqual(a: VaultPendingRefresh?, b: VaultPendingRefresh?): Boolean {
    if (a == null || b == null) {
        return a == null && b == null
    }
    return a.operationId == b.operationId &&
        a.attempt.idempotencyKey == b.attempt.idempotencyKey &&
        a.attempt.requestJson == b.attempt.requestJson &&
        a.attempt.refreshToken == b.attempt.refreshToken
}

internal fun vaultPendingSemanticHash(pending: VaultPendingRefresh?): Int {
    if (pending == null) return 0
    var result = pending.operationId.hashCode()
    result = 31 * result + pending.attempt.idempotencyKey.hashCode()
    result = 31 * result + pending.attempt.requestJson.hashCode()
    result = 31 * result + pending.attempt.refreshToken.hashCode()
    return result
}

internal fun vaultRecordsSemanticEqual(actual: VaultSessionRecord, expected: VaultSessionRecord): Boolean {
    if (actual.binding != expected.binding ||
        actual.operationId != expected.operationId ||
        actual.phase != expected.phase ||
        actual.session != expected.session
    ) {
        return false
    }
    if (!vaultPendingSemanticEqual(actual.pendingRefresh, expected.pendingRefresh)) {
        return false
    }
    return actual.supersededSessions == expected.supersededSessions
}
