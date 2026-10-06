package com.dragosfotache.gymtracka.data.sync.store

import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.session.PreparedSessionEnginePermit

data class CanonicalBatchResult(val appliedRoots: Int, val ignoredRoots: Int)

class StaleCanonicalEpochException(message: String = "Stale canonical cache epoch") :
    IllegalStateException(message)

class CanonicalConflictException(message: String = "Canonical batch conflicts with retained evidence") :
    IllegalStateException(message)

class CanonicalStorageException(message: String = "Invalid retained canonical evidence") :
    IllegalStateException(message)

class CanonicalStore(
    private val database: GymDatabase,
    private val session: RepositorySession,
) {
    init {
        if (session.accountId == null) {
            throw IllegalArgumentException("Canonical store requires a signed-in account")
        }
        if (session.sessionGeneration <= 0) {
            throw IllegalArgumentException("Canonical store requires a positive session generation")
        }
    }

    suspend fun applyBatch(batch: SyncBatchModel, expectedCacheEpoch: Long): CanonicalBatchResult {
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        val owned = ownedCanonicalBatch(batch)
        val accountId = session.accountId ?: throw IllegalArgumentException(
            "Canonical store requires a signed-in account",
        )
        return database.withTransaction {
            applyOwnedCanonicalBatch(database, accountId, session.sessionGeneration, expectedCacheEpoch, owned, null)
        }
    }

    suspend fun <T> withFencedTransaction(
        expectedCacheEpoch: Long,
        block: suspend (CanonicalTransaction) -> T,
    ): T {
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        val accountId = session.accountId ?: throw IllegalArgumentException(
            "Canonical store requires a signed-in account",
        )
        return database.withTransaction {
            checkCanonicalFences(database, accountId, session.sessionGeneration, expectedCacheEpoch, null)
            val transaction = CanonicalTransaction(
                database,
                accountId,
                session.sessionGeneration,
                expectedCacheEpoch,
            )
            try {
                block(transaction)
            } finally {
                transaction.close()
            }
        }
    }
}

class CanonicalTransaction internal constructor(
    private val database: GymDatabase,
    private val accountId: String,
    private val sessionGeneration: Long,
    private val expectedCacheEpoch: Long,
) {
    private var open = true

    internal fun close() {
        open = false
    }

    private fun requireActive() {
        if (!open) {
            throw IllegalStateException("CanonicalTransaction used outside its fenced transaction")
        }
        if (!database.inTransaction()) {
            throw IllegalStateException("Canonical writes require an active database transaction")
        }
    }

    suspend fun applyBatch(batch: SyncBatchModel): CanonicalBatchResult {
        val owned = ownedCanonicalBatch(batch)
        requireActive()
        return applyOwnedCanonicalBatch(database, accountId, sessionGeneration, expectedCacheEpoch, owned, null)
    }
}

/**
 * Shared owned-batch writer used by both the single-batch convenience and composed
 * transactions (including push acknowledgment). Callers supply an already deep-owned batch; this helper rechecks
 * fences, validates retained evidence, arbitrates revisions, validates family
 * bindings (including retained-only consistency for all-no-op batches), and commits
 * selected writes with dirty marking atomically. Must run inside the caller's
 * active Room transaction; never opens its own transaction or the network.
 */
internal suspend fun applyOwnedCanonicalBatch(
    database: GymDatabase,
    accountId: String,
    sessionGeneration: Long,
    expectedCacheEpoch: Long,
    owned: SyncBatchModel,
    permit: PreparedSessionEnginePermit? = null,
): CanonicalBatchResult {
    if (!database.inTransaction()) {
        throw IllegalStateException("Canonical writes require an active database transaction")
    }
    val accountEpoch = checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, permit)
    val retained = loadValidatedRetained(database, accountId, accountEpoch)
    val decisions = selectCanonicalChanges(owned.changes, retained)
    val selected = decisions.filter { it.selected }.map { it.change }
    checkFamilyBindings(retained, selected)
    if (selected.isEmpty()) {
        return CanonicalBatchResult(appliedRoots = 0, ignoredRoots = owned.changes.size)
    }
    val dao = database.syncCanonicalDao()
    dao.insertAccountCache(accountId)
    for (decision in decisions) {
        if (!decision.selected) {
            continue
        }
        val change = decision.change
        val key = change.aggregateType.wireValue to change.aggregateId
        val existing = retained.byKey[key]
        val state = if (change.operation == ChangeOperation.UPSERT) "upsert" else "delete"
        val json = if (change.operation == ChangeOperation.UPSERT) {
            encodeCanonicalJson(change.record as CanonicalRecord)
        } else {
            null
        }
        if (existing == null) {
            dao.insertCanonicalRoot(
                accountId = accountId,
                aggregateType = change.aggregateType.wireValue,
                aggregateId = change.aggregateId,
                serverRevision = change.revision,
                canonicalState = state,
                canonicalJson = json,
                watermark = 0L,
                cacheEpoch = accountEpoch,
            )
        } else {
            val updated = dao.updateCanonicalBody(
                accountId = accountId,
                aggregateType = change.aggregateType.wireValue,
                aggregateId = change.aggregateId,
                serverRevision = change.revision,
                canonicalState = state,
                canonicalJson = json,
                cacheEpoch = accountEpoch,
            )
            if (updated != 1) {
                throw IllegalStateException("Canonical root changed during transaction")
            }
        }
    }
    if (dao.markProjectionDirty(accountId) != 1) {
        throw IllegalStateException("Account cache changed during transaction")
    }
    return CanonicalBatchResult(
        appliedRoots = selected.size,
        ignoredRoots = owned.changes.size - selected.size,
    )
}
