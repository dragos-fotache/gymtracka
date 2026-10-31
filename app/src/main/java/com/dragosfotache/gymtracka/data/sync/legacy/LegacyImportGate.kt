package com.dragosfotache.gymtracka.data.sync.legacy

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * pre-send settlement/consent gate for generic push paths. Uses the
 * consolidated strict validator for full journal/entry/source/authority
 * checks with pre-write fencing. A missing journal with any orphan
 * entries/progress/receipts fails closed; no migration at all passes.
 * Corrupt evidence yields CORRUPT; an active import without durable
 * matching consent yields RETAINED_ERROR with zero remote effects. Reads
 * only; closed failures never expose bytes. Cancellation propagates.
 */
internal object LegacyImportGate {
    suspend fun checkHead(
        database: GymDatabase,
        accountId: String,
        head: SyncOutboxEntity,
    ): PushBlockReason? {
        try {
            currentCoroutineContext().ensureActive()
            return checkHeadStrict(database, accountId, head)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            return PushBlockReason.CORRUPT
        }
    }

    /**
     * Empty-queue gate: an illicitly empty outbox with unsettled or orphan
     * migration evidence blocks CORRUPT so snapshot begin and completion
     * can never treat queue absence as settlement. Fully settled or absent
     * migration passes. Cancellation propagates.
     */
    suspend fun checkEmpty(database: GymDatabase, accountId: String): PushBlockReason? {
        try {
            currentCoroutineContext().ensureActive()
            val journal = LegacyImportSettlement.readJournalStrict(database)
            val entries = LegacyImportSettlement.readEntriesStrict(database)
            if (journal == null) {
                if (entries.isNotEmpty() ||
                    LegacyImportSettlement.readProgressStrict(database) != null ||
                    LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
                ) {
                    return PushBlockReason.CORRUPT
                }
                return null
            }
            if (journal.phase == LegacyImportSource.PHASE_BLOCKED) {
                return PushBlockReason.CORRUPT
            }
            val target = try {
                LegacyImportSettlement.parseTargetStrict(journal)
            } catch (_: Exception) {
                return PushBlockReason.CORRUPT
            }
            if (target.accountId != accountId) {
                return PushBlockReason.CORRUPT
            }
            val current = try {
                LegacyImportSettlement.currentAuthority(database)
            } catch (_: Exception) {
                return PushBlockReason.CORRUPT
            }
            val view = try {
                LegacyImportSettlement.validateView(database, journal, entries, target, current)
            } catch (_: Exception) {
                return PushBlockReason.CORRUPT
            }
            if (view.acceptedPrefix != entries.size) {
                return PushBlockReason.CORRUPT
            }
            return null
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            return PushBlockReason.CORRUPT
        }
    }

    private suspend fun checkHeadStrict(
        database: GymDatabase,
        accountId: String,
        head: SyncOutboxEntity,
    ): PushBlockReason? {
        val journal = LegacyImportSettlement.readJournalStrict(database)
        val entries = LegacyImportSettlement.readEntriesStrict(database)
        if (journal == null) {
            if (entries.isNotEmpty() ||
                LegacyImportSettlement.readProgressStrict(database) != null ||
                LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
            ) {
                return PushBlockReason.CORRUPT
            }
            return null
        }
        if (journal.phase == LegacyImportSource.PHASE_BLOCKED) {
            return PushBlockReason.CORRUPT
        }
        val target = LegacyImportSettlement.parseTargetStrict(journal)
        if (target.accountId != accountId) return PushBlockReason.CORRUPT
        val current = LegacyImportSettlement.currentAuthority(database)
        val view = LegacyImportSettlement.validateView(database, journal, entries, target, current)
        val headIndex = entries.indexOfFirst { it.mutationId == head.mutationId }
        if (headIndex >= 0) {
            if (headIndex != view.acceptedPrefix) return PushBlockReason.CORRUPT
            val e = entries[headIndex]
            if (e.sequence != head.sequence ||
                e.aggregateType != head.aggregateType ||
                e.aggregateId != head.aggregateId ||
                e.localGeneration != head.localGeneration ||
                e.payloadVersion != head.payloadVersion.toLong() ||
                e.payloadJson != head.payloadJson
            ) {
                return PushBlockReason.CORRUPT
            }
            if (head.mutationType != MutationKind.AGGREGATE_IMPORT.wireValue) {
                return PushBlockReason.CORRUPT
            }
            if (view.activeMutationId == head.mutationId &&
                view.progress.activeConsentMutationId != head.mutationId
            ) {
                return PushBlockReason.RETAINED_ERROR
            }
            return null
        }
        if (view.acceptedPrefix != entries.size) return PushBlockReason.CORRUPT
        return null
    }

    override fun toString(): String = "LegacyImportGate(redacted)"
}
