package com.dragosfotache.gymtracka.data.sync.legacy

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportAcceptanceEntity
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.codec.encodeSyncBatch
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * R13b same-transaction acceptance receipt writer for the existing accepted
 * batch transaction. Uses the consolidated strict validator for full
 * journal/entry/source/authority checks with pre-write fencing and
 * post-write rechecks. Persists one immutable receipt for a validated
 * accepted/original-accepted outcome alongside exact head removal. Any
 * failure throws so canonical/metadata/deletion/receipt roll back together.
 * No receipt from queue absence, caches, summaries or flags. Cancellation
 * propagates.
 */
internal object LegacyImportAck {
    /**
     * Owned pre-write context for one legacy acknowledgment: immutable
     * expected journal/entries/prior receipts plus the intended new
     * receipt bytes. Null means ordinary work. Throws on any validation
     * failure so the ack rolls back. Never an authority token.
     */
    internal data class AckReceiptContext(
        val journal: com.dragosfotache.gymtracka.data.gym.SyncLegacyImportEntity,
        val entries: List<com.dragosfotache.gymtracka.data.gym.SyncLegacyImportEntryEntity>,
        val priorAcceptances: List<SyncLegacyImportAcceptanceEntity>,
        val intendedOrdinal: Long,
        val intendedMutationId: String,
        val intendedBatchJson: String,
    ) {
        override fun toString(): String = "LegacyImportAck.AckReceiptContext(redacted)"
    }

    /**
     * Returns owned pre-write context when the claimed row is a legacy
     * import entry (receipt required); null for ordinary work when the
     * migration is absent or fully settled. Throws on any legacy
     * validation failure so the ack rolls back.
     */
    suspend fun precheck(
        database: GymDatabase,
        accountId: String,
        claimRow: SyncOutboxEntity,
        ownedBatch: SyncBatchModel,
    ): AckReceiptContext? {
        currentCoroutineContext().ensureActive()
        val journal = LegacyImportSettlement.readJournalStrict(database)
        val entries = LegacyImportSettlement.readEntriesStrict(database)
        if (journal == null) {
            if (entries.isNotEmpty() ||
                LegacyImportSettlement.readProgressStrict(database) != null ||
                LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
            ) {
                throw IllegalStateException("Push head changed during transaction")
            }
            return null
        }
        if (journal.phase == LegacyImportSource.PHASE_BLOCKED) {
            throw IllegalStateException("Push head changed during transaction")
        }
        if (!entries.any { it.mutationId == claimRow.mutationId }) {
            val target = LegacyImportSettlement.parseTargetStrict(journal)
            if (target.accountId != accountId) {
                throw IllegalStateException("Push head changed during transaction")
            }
            val current = LegacyImportSettlement.currentAuthority(database)
            val view = LegacyImportSettlement.validateView(database, journal, entries, target, current)
            if (view.acceptedPrefix != entries.size) {
                throw IllegalStateException("Push head changed during transaction")
            }
            return null
        }
        val target = LegacyImportSettlement.parseTargetStrict(journal)
        if (target.accountId != accountId) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val current = LegacyImportSettlement.currentAuthority(database)
        val view = LegacyImportSettlement.validateView(database, journal, entries, target, current)
        val index = entries.indexOfFirst { it.mutationId == claimRow.mutationId }
        if (index != view.acceptedPrefix) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val e = entries[index]
        if (e.sequence != claimRow.sequence ||
            e.aggregateType != claimRow.aggregateType ||
            e.aggregateId != claimRow.aggregateId ||
            e.localGeneration != claimRow.localGeneration ||
            e.payloadVersion != claimRow.payloadVersion.toLong() ||
            e.payloadJson != claimRow.payloadJson
        ) {
            throw IllegalStateException("Push head changed during transaction")
        }
        if (view.activeMutationId == claimRow.mutationId &&
            view.progress.activeConsentMutationId != claimRow.mutationId
        ) {
            throw IllegalStateException("Push head changed during transaction")
        }
        try {
            encodeSyncBatch(ownedBatch)
            val batchJson = encodeSyncBatch(ownedBatch).toString()
            val source = try {
                LegacyImportSource.validateSourceJson(journal.sourceJson)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw IllegalStateException("Push head changed during transaction")
            }
            LegacyImportSettlement.validateReceiptBatch(
                batchJson,
                e,
                source,
            )
            currentCoroutineContext().ensureActive()
            return AckReceiptContext(
                journal = journal,
                entries = entries,
                priorAcceptances = view.acceptances,
                intendedOrdinal = view.acceptances.size.toLong(),
                intendedMutationId = claimRow.mutationId,
                intendedBatchJson = batchJson,
            )
        } catch (e2: CancellationException) {
            throw e2
        } catch (_: Exception) {
            throw IllegalStateException("Push head changed during transaction")
        }
    }

    suspend fun persistReceipt(
        database: GymDatabase,
        context: AckReceiptContext,
    ) {
        currentCoroutineContext().ensureActive()
        val existing = LegacyImportSettlement.readAcceptancesStrict(database)
        if (existing != context.priorAcceptances) {
            throw IllegalStateException("Push head changed during transaction")
        }
        if (existing.any { it.mutationId == context.intendedMutationId }) {
            throw IllegalStateException("Push head changed during transaction")
        }
        if (existing.size.toLong() != context.intendedOrdinal) {
            throw IllegalStateException("Push head changed during transaction")
        }
        try {
            database.syncLegacyImportDao().insertAcceptance(
                SyncLegacyImportAcceptanceEntity(
                    ordinal = context.intendedOrdinal,
                    mutationId = context.intendedMutationId,
                    batchJson = context.intendedBatchJson,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalStateException("Push head changed during transaction")
        }
        currentCoroutineContext().ensureActive()
    }

    /**
     * Exact post-write validation after final SQL/trigger effects:
     * source/entries/prior receipts are byte-identical to the pre-write
     * context and the intended receipt is present with exact bytes.
     * Structurally valid drift still fails.
     */
    suspend fun recheck(
        database: GymDatabase,
        accountId: String,
        context: AckReceiptContext,
    ) {
        currentCoroutineContext().ensureActive()
        val journal = LegacyImportSettlement.readJournalStrict(database)
            ?: throw IllegalStateException("Push head changed during transaction")
        if (journal != context.journal) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val entries = LegacyImportSettlement.readEntriesStrict(database)
        if (entries != context.entries) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val target = LegacyImportSettlement.parseTargetStrict(journal)
        if (target.accountId != accountId) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val acceptances = LegacyImportSettlement.readAcceptancesStrict(database)
        if (acceptances.size != context.priorAcceptances.size + 1) {
            throw IllegalStateException("Push head changed during transaction")
        }
        if (acceptances.subList(0, context.priorAcceptances.size) != context.priorAcceptances) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val added = acceptances[context.priorAcceptances.size]
        if (added.ordinal != context.intendedOrdinal ||
            added.mutationId != context.intendedMutationId ||
            added.batchJson != context.intendedBatchJson
        ) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val current = LegacyImportSettlement.currentAuthority(database)
        LegacyImportSettlement.validateView(database, journal, entries, target, current)
        currentCoroutineContext().ensureActive()
    }

    override fun toString(): String = "LegacyImportAck(redacted)"
}
