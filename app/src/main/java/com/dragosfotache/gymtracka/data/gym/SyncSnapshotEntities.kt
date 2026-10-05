package com.dragosfotache.gymtracka.data.gym

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable frozen-snapshot staging header. One row per account at most, holding the
 * originating run fence, the exact source observation the stage was bound to, the
 * frozen baseline and pending continuation, and page/record progress counters.
 *
 * Staging lives outside canonical and visible domain storage: a retained stage is
 * never a validated complete graph, canonical baseline, deletion authority,
 * bootstrap success or publication. No credential, origin secret or
 * visible-domain/canonical foreign key appears here.
 */
@Entity(
    tableName = "sync_snapshot_stages",
    indices = [
        Index(value = ["account_id"], unique = true),
    ],
)
data class SyncSnapshotStageEntity(
    @PrimaryKey @ColumnInfo(name = "stage_id") val stageId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "session_generation") val sessionGeneration: Long,
    @ColumnInfo(name = "cache_epoch") val cacheEpoch: Long,
    @ColumnInfo(name = "page_limit") val pageLimit: Int,
    @ColumnInfo(name = "source_state_present") val sourceStatePresent: Boolean,
    @ColumnInfo(name = "source_cursor") val sourceCursor: String?,
    @ColumnInfo(name = "source_last_success_millis") val sourceLastSuccessMillis: Long?,
    @ColumnInfo(name = "source_baseline_complete") val sourceBaselineComplete: Boolean,
    @ColumnInfo(name = "baseline_cursor") val baselineCursor: String?,
    @ColumnInfo(name = "next_page_token") val nextPageToken: String?,
    @ColumnInfo(name = "phase", defaultValue = "'downloading'") val phase: String = "downloading",
    @ColumnInfo(name = "page_count", defaultValue = "0") val pageCount: Long = 0,
    @ColumnInfo(name = "record_count", defaultValue = "0") val recordCount: Long = 0,
)

/**
 * One staged whole canonical upsert body. Rows are keyed by staged root and ordered
 * by page and in-page position. Page numbers are 1-based, record positions 0-based.
 * The stage foreign key cascades: abandoning the exact stage removes its records.
 */
@Entity(
    tableName = "sync_snapshot_records",
    primaryKeys = ["stage_id", "aggregate_type", "aggregate_id"],
    indices = [
        Index(value = ["stage_id", "page_number", "record_position"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SyncSnapshotStageEntity::class,
            parentColumns = ["stage_id"],
            childColumns = ["stage_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
)
data class SyncSnapshotRecordEntity(
    @ColumnInfo(name = "stage_id") val stageId: String,
    @ColumnInfo(name = "aggregate_type") val aggregateType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "server_revision") val serverRevision: Long,
    @ColumnInfo(name = "canonical_json") val canonicalJson: String,
    @ColumnInfo(name = "page_number") val pageNumber: Long,
    @ColumnInfo(name = "record_position") val recordPosition: Long,
)

/**
 * Durable continuation evidence: one exact outbound page token per successfully
 * committed nonterminal page. Progress evidence only, never token interpretation.
 * The stage foreign key cascades: abandoning the exact stage removes its tokens.
 */
@Entity(
    tableName = "sync_snapshot_tokens",
    primaryKeys = ["stage_id", "page_token"],
    indices = [
        Index(value = ["stage_id", "page_number"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SyncSnapshotStageEntity::class,
            parentColumns = ["stage_id"],
            childColumns = ["stage_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
)
data class SyncSnapshotTokenEntity(
    @ColumnInfo(name = "stage_id") val stageId: String,
    @ColumnInfo(name = "page_token") val pageToken: String,
    @ColumnInfo(name = "page_number") val pageNumber: Long,
)
