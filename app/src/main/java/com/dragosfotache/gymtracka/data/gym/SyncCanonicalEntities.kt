package com.dragosfotache.gymtracka.data.gym

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Independent canonical/deletion evidence. No domain FK: watermarks and deletion
 * revisions must survive physical removal/rematerialization of the visible root.
 * An unobserved reservation has no server revision or JSON; it is not a server record.
 * Future validated canonical writes own upsert/delete/snapshot_absent transitions.
 */
@Entity(
    tableName = "sync_canonical_roots",
    primaryKeys = ["account_id", "aggregate_type", "aggregate_id"],
)
data class SyncCanonicalRootEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "aggregate_type") val aggregateType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "server_revision") val serverRevision: Long? = null,
    @ColumnInfo(name = "canonical_state", defaultValue = "'unobserved'") val canonicalState: String = "unobserved",
    @ColumnInfo(name = "canonical_json") val canonicalJson: String? = null,
    @ColumnInfo(name = "local_generation_watermark", defaultValue = "0") val localGenerationWatermark: Long = 0,
    @ColumnInfo(name = "cache_epoch", defaultValue = "0") val cacheEpoch: Long = 0,
)

/**
 * Publication bookkeeping only, separate from the replaceable cursor row. An
 * existing cursor/device cache label never establishes a complete canonical baseline.
 * No credentials, visibility authorization, or engine claim is stored here.
 */
@Entity(tableName = "sync_account_cache")
data class SyncAccountCacheEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "baseline_complete", defaultValue = "0") val baselineComplete: Boolean = false,
    @ColumnInfo(name = "cache_epoch", defaultValue = "0") val cacheEpoch: Long = 0,
    @ColumnInfo(name = "projection_dirty", defaultValue = "0") val projectionDirty: Boolean = false,
    @ColumnInfo(name = "materialized_epoch", defaultValue = "0") val materializedEpoch: Long = 0,
)
