package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncSnapshotRecordEntity
import com.dragosfotache.gymtracka.data.gym.SyncSnapshotStageEntity
import com.dragosfotache.gymtracka.data.gym.SyncSnapshotTokenEntity
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.api.MAX_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MIN_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.codec.decodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.codec.encodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import org.json.JSONException

internal const val SNAPSHOT_STAGE_DOWNLOADING = "downloading"
internal const val SNAPSHOT_STAGE_DOWNLOADED = "downloaded"

/** One coherently read and validated durable stage with its progress evidence. */
internal data class ValidatedSnapshotStage(
    val header: SyncSnapshotStageEntity,
    val records: List<SyncSnapshotRecordEntity>,
    val tokens: List<SyncSnapshotTokenEntity>,
)

/**
 * Deep-owns and validates one entire incoming typed page before any suspension:
 * existing snapshot/record codecs run first, so delete, bundled and invalid
 * aggregates, within-page duplicates and wire continuation shape fail here with
 * the existing safe model error. Caller mutation after suspension cannot change
 * the owned copy.
 */
internal fun ownedSnapshotPage(page: SnapshotResultModel): SnapshotResultModel {
    try {
        return decodeSnapshotResult(encodeSnapshotResult(page))
    } catch (_: JSONException) {
        throw SyncModelException("Snapshot page is malformed")
    }
}

/**
 * Encodes one staged whole canonical upsert as its exact retained record body:
 * the whole canonical record, never the change envelope. The caller supplies an
 * already deep-owned upsert change; a missing body fails with the existing safe
 * model error and is never stored.
 */
internal fun stagedRecordJson(change: SyncChangeModel): String {
    val record = change.record
        ?: throw SyncModelException("Snapshot upsert requires a record")
    return encodeCanonicalJson(record)
}

/**
 * Staging shape checks needing only the admitted token, still before any
 * suspension: page size against the fixed stage limit and nonempty nonterminal
 * pages. Terminal pages may carry zero to limit records.
 */
internal fun checkOwnedPageAgainstStage(page: SnapshotResultModel, stage: SnapshotStage) {
    if (page.records.size > stage.pageLimit) {
        throw SyncModelException("Snapshot page exceeds the stage page limit")
    }
    if (page.hasMore && page.records.isEmpty()) {
        throw SyncModelException("Nonterminal snapshot page must not be empty")
    }
}

/**
 * Coherently reads the selected-account stage with its records and progress
 * evidence inside the caller's active transaction. Returns null when no stage is
 * retained; corrupt retained evidence fails closed as a storage error and is
 * never repaired. Never mutates.
 */
internal suspend fun readValidatedSnapshotStage(
    database: GymDatabase,
    accountId: String,
): ValidatedSnapshotStage? {
    if (!database.inTransaction()) {
        throw IllegalStateException("Snapshot stage reads require an active transaction")
    }
    val dao = database.syncSnapshotDao()
    val header = dao.getStageForAccount(accountId) ?: return null
    val records = dao.getRecords(header.stageId)
    val tokens = dao.getTokens(header.stageId)
    return validateRetainedSnapshotStage(header, records, tokens)
}

/**
 * Validates complete retained stage evidence: header shape, token progress,
 * page/record positions and counts, and per-record body agreement. Any retained
 * corruption fails closed as a storage error; this is per-record/progress
 * validity, not whole-account dependency or publication validation.
 */
internal fun validateRetainedSnapshotStage(
    header: SyncSnapshotStageEntity,
    records: List<SyncSnapshotRecordEntity>,
    tokens: List<SyncSnapshotTokenEntity>,
): ValidatedSnapshotStage {
    try {
        return validateRetainedSnapshotStageOrThrow(header, records, tokens)
    } catch (_: SyncModelException) {
        throw SnapshotStageStorageException()
    } catch (_: JSONException) {
        throw SnapshotStageStorageException()
    }
}

private fun validateRetainedSnapshotStageOrThrow(
    header: SyncSnapshotStageEntity,
    records: List<SyncSnapshotRecordEntity>,
    tokens: List<SyncSnapshotTokenEntity>,
): ValidatedSnapshotStage {
    requireWorkoutUuid(header.stageId, "stageId")
    if (header.accountId.isBlank()) {
        throw SyncModelException("Snapshot stage has a blank account")
    }
    if (header.sessionGeneration <= 0L) {
        throw SyncModelException("Snapshot stage has a nonpositive origin generation")
    }
    if (header.cacheEpoch < 0L) {
        throw SyncModelException("Snapshot stage has a negative origin epoch")
    }
    if (header.pageLimit < MIN_SNAPSHOT_LIMIT || header.pageLimit > MAX_SNAPSHOT_LIMIT) {
        throw SyncModelException("Snapshot stage has an invalid page limit")
    }
    if (header.pageCount < 0L || header.recordCount < 0L) {
        throw SyncModelException("Snapshot stage has negative progress counters")
    }
    if (header.phase != SNAPSHOT_STAGE_DOWNLOADING && header.phase != SNAPSHOT_STAGE_DOWNLOADED) {
        throw SyncModelException("Snapshot stage has an unknown phase")
    }
    if (!header.sourceStatePresent &&
        (header.sourceCursor != null || header.sourceLastSuccessMillis != null)
    ) {
        throw SyncModelException("Snapshot stage has an inconsistent source observation")
    }
    if (header.sourceCursor != null && header.sourceCursor.isEmpty()) {
        throw SyncModelException("Snapshot stage has a corrupt source cursor")
    }
    if (header.sourceLastSuccessMillis != null && header.sourceLastSuccessMillis < 0L) {
        throw SyncModelException("Snapshot stage has a corrupt source observation")
    }
    for (record in records) {
        if (record.stageId != header.stageId) {
            throw SyncModelException("Snapshot record belongs to another stage")
        }
    }
    for (token in tokens) {
        if (token.stageId != header.stageId) {
            throw SyncModelException("Snapshot token belongs to another stage")
        }
        if (token.pageToken.isEmpty()) {
            throw SyncModelException("Snapshot token must be nonempty")
        }
        if (token.pageNumber < 1L) {
            throw SyncModelException("Snapshot token has an invalid page number")
        }
    }
    val expectedTokenCount = if (header.phase == SNAPSHOT_STAGE_DOWNLOADING) {
        header.pageCount
    } else {
        if (header.pageCount < 1L) {
            throw SyncModelException("Downloaded snapshot stage requires committed pages")
        }
        header.pageCount - 1L
    }
    if (tokens.size.toLong() != expectedTokenCount) {
        throw SyncModelException("Snapshot token progress does not match the stage header")
    }
    tokens.forEachIndexed { index, token ->
        if (token.pageNumber != index.toLong() + 1L) {
            throw SyncModelException("Snapshot token progress does not match the stage header")
        }
    }
    if (header.phase == SNAPSHOT_STAGE_DOWNLOADING) {
        if (header.pageCount == 0L) {
            if (header.baselineCursor != null || header.nextPageToken != null) {
                throw SyncModelException("Initial snapshot stage must not carry progress")
            }
        } else {
            if (header.baselineCursor.isNullOrEmpty()) {
                throw SyncModelException("Snapshot stage is missing its frozen baseline")
            }
            val last = tokens.lastOrNull()
                ?: throw SyncModelException("Snapshot stage is missing its continuation")
            if (header.nextPageToken != last.pageToken) {
                throw SyncModelException("Snapshot continuation does not match progress evidence")
            }
        }
    } else {
        if (header.baselineCursor.isNullOrEmpty()) {
            throw SyncModelException("Snapshot stage is missing its frozen baseline")
        }
        if (header.nextPageToken != null) {
            throw SyncModelException("Downloaded snapshot stage must not carry a continuation")
        }
    }
    val byPage = records.groupBy { it.pageNumber }
    for (page in byPage.keys) {
        if (page < 1L || page > header.pageCount) {
            throw SyncModelException("Snapshot record page is outside the committed range")
        }
    }
    // Coverage without materializing the committed page range: present pages are
    // distinct keys inside 1..pageCount, so an exact page-table size proves every
    // nonterminal page is present. A downloaded terminal page may be empty and
    // therefore absent from the record table.
    if (header.phase == SNAPSHOT_STAGE_DOWNLOADING) {
        if (byPage.size.toLong() != header.pageCount) {
            throw SyncModelException("Snapshot page is missing staged records")
        }
    } else {
        // Exactly the N-1 nonterminal pages must be present; the terminal page
        // may be empty and therefore absent. Keys are already range-checked, so
        // counting keys below pageCount proves coverage with no range build.
        val nonterminal = byPage.keys.count { it < header.pageCount }.toLong()
        if (nonterminal != header.pageCount - 1L) {
            throw SyncModelException("Snapshot page is missing staged records")
        }
    }
    var total = 0L
    for ((page, unsorted) in byPage) {
        val rows = unsorted.sortedBy { it.recordPosition }
        val terminal = header.phase == SNAPSHOT_STAGE_DOWNLOADED && page == header.pageCount
        if (!terminal && rows.isEmpty()) {
            throw SyncModelException("Snapshot page is missing staged records")
        }
        if (rows.size > header.pageLimit) {
            throw SyncModelException("Snapshot page exceeds the stage page limit")
        }
        rows.forEachIndexed { index, row ->
            if (row.recordPosition != index.toLong()) {
                throw SyncModelException("Snapshot record positions are not consecutive")
            }
        }
        for (row in rows) {
            val type = try {
                AggregateType.fromWire(row.aggregateType)
            } catch (_: SyncModelException) {
                throw SyncModelException("Snapshot record has an unknown aggregate type")
            }
            requireWorkoutUuid(row.aggregateId, "aggregateId")
            if (row.serverRevision < 1L) {
                throw SyncModelException("Snapshot record has a nonpositive revision")
            }
            val decoded = decodeStoredRecord(type, row.canonicalJson)
            if (decoded.aggregateType != type ||
                decoded.aggregateId != row.aggregateId ||
                decoded.revision != row.serverRevision
            ) {
                throw SyncModelException("Snapshot record does not match its staged body")
            }
        }
        total += rows.size
    }
    if (total != header.recordCount) {
        throw SyncModelException("Snapshot record count does not match the stage header")
    }
    return ValidatedSnapshotStage(header, records, tokens)
}

/**
 * Fails closed on a corrupt retained source row without repair. Maps the existing
 * pull-state shape check onto the staging storage error.
 */
internal fun validateSnapshotSourceState(state: SyncStateEntity) {
    try {
        validatePullSyncState(state)
    } catch (_: PullStorageException) {
        throw SnapshotStageStorageException()
    }
}

/**
 * Requires the current full source observation to still match the header's bound
 * origin exactly: sync-state presence, cursor and last-success plus the baseline
 * flag. A corrupt current row fails closed as storage; any difference is stale.
 */
internal suspend fun requireExactSnapshotSource(
    database: GymDatabase,
    accountId: String,
    header: SyncSnapshotStageEntity,
) {
    if (!database.inTransaction()) {
        throw IllegalStateException("Snapshot source checks require an active transaction")
    }
    val current = database.syncDao().getSyncState(accountId)
    if (current != null) {
        validateSnapshotSourceState(current)
    }
    if ((current != null) != header.sourceStatePresent ||
        current?.cursor != header.sourceCursor ||
        current?.lastSuccessMillis != header.sourceLastSuccessMillis
    ) {
        throw StaleSnapshotStageException()
    }
    val cache = database.syncCanonicalDao().getAccountCache(accountId)
    if ((cache?.baselineComplete ?: false) != header.sourceBaselineComplete) {
        throw StaleSnapshotStageException()
    }
}

/** Builds the run-bound token for one validated header. Observations never append. */
internal fun ValidatedSnapshotStage.toSnapshotToken(
    run: PushRun,
    accountId: String,
    sessionGeneration: Long,
    expectedCacheEpoch: Long,
    canAppend: Boolean,
): SnapshotStage {
    val downloaded = header.phase == SNAPSHOT_STAGE_DOWNLOADED
    return SnapshotStage(
        run = run,
        accountId = accountId,
        sessionGeneration = sessionGeneration,
        expectedCacheEpoch = expectedCacheEpoch,
        header = header,
        stageId = header.stageId,
        pageLimit = header.pageLimit,
        baselineCursor = header.baselineCursor,
        nextPageToken = header.nextPageToken,
        downloaded = downloaded,
        pageCount = header.pageCount,
        recordCount = header.recordCount,
        canAppend = canAppend && !downloaded,
    )
}
