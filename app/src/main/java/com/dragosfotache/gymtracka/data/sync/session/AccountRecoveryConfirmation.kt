package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportAcceptanceEntity
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportEntity
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportEntryEntity
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportProgressEntity
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress

/**
 * raw legacy evidence snapshot. Strict journal/entry/source/progress/receipt rows read
 * inside one fenced transaction; data-class equality refuses any drift. Original import
 * evidence is never a discard target.
 */
internal data class LegacyEvidenceSnapshot(
    val journal: SyncLegacyImportEntity?,
    val entries: List<SyncLegacyImportEntryEntity>,
    val progress: SyncLegacyImportProgressEntity?,
    val acceptances: List<SyncLegacyImportAcceptanceEntity>,
) {
    override fun toString(): String = "LegacyEvidenceSnapshot(redacted)"
}

/**
 * opaque process-local ordinary-recovery confirmation. Issued only by
 * [AppSessionCoordinator.prepareOrdinaryRecovery] after frozen-only settlement, full-row
 * capture under a real pause/drain lease and strict legacy proof, and registered with the
 * issuing coordinator. Binding to the issuing owner, exact handle and process fence means a
 * foreign facade token with identical durable account/room/rows still refuses: checks are
 * enforced by the facade at [AppSessionCoordinator.recoverOrdinaryRejection], never by
 * consumers. Single-use; presentation reads only [pendingCount]. Displays are fixed/redacted
 * and expose no IDs, requests, errors or credentials.
 */
internal class AccountRecoveryConfirmation internal constructor(
    val pendingCount: Long,
    internal val owner: AppSessionCoordinator,
    internal val fence: Any,
    internal val handle: AppAccountSession,
    internal val accountId: String,
    internal val sessionGeneration: Long,
    internal val cacheEpoch: Long,
    internal val rows: List<SyncOutboxEntity>,
    internal val room: RecoveryRoomSnapshot,
    internal val legacyProof: LegacyImportProgress?,
    internal val legacyRaw: LegacyEvidenceSnapshot,
) {
    @Volatile
    internal var consumed: Boolean = false

    override fun toString(): String = "AccountRecoveryConfirmation(redacted)"
}
