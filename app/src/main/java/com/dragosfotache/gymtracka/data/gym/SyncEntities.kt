package com.dragosfotache.gymtracka.data.gym

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable ordered outbox for aggregate sync mutations. States pending/frozen/rejected;
 * frozen means claimed/possibly transmitted and never coalescible again. No foreign keys
 * to roots: publication evidence must survive root removal. Scheduling fields
 * (retry_after_millis) never affect send order or conflict outcomes.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["mutation_id"], unique = true),
        Index(value = ["account_id", "sequence"]),
        Index(value = ["account_id", "aggregate_type", "aggregate_id"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "sequence") val sequence: Long = 0,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "aggregate_type") val aggregateType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "mutation_type") val mutationType: String,
    @ColumnInfo(name = "local_generation") val localGeneration: Long,
    @ColumnInfo(name = "payload_version", defaultValue = "1") val payloadVersion: Int = 1,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "state", defaultValue = "'pending'") val state: String = "pending",
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "retry_after_millis") val retryAfterMillis: Long? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)

/** Per-account sync cursor. The account key is non-null; there are no pseudo accounts. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "cursor") val cursor: String? = null,
    @ColumnInfo(name = "last_success_millis") val lastSuccessMillis: Long? = null,
)

/**
 * Singleton device row identifying the active account. Future switches/identity changes
 * advance sessionGeneration; future canonical writes compare the current device row
 * inside their transaction. cachedAccountId is storage for the retained signed-out
 * cache and is independent of activeAccountId; no sign-out behavior is implemented
 * in this slice. Holds no credentials or tokens.
 */
@Entity(tableName = "sync_device_state")
data class SyncDeviceStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "active_account_id") val activeAccountId: String? = null,
    @ColumnInfo(name = "cached_account_id") val cachedAccountId: String? = null,
    @ColumnInfo(name = "session_generation", defaultValue = "0") val sessionGeneration: Long = 0,
    @ColumnInfo(name = "cache_state", defaultValue = "'local_only'") val cacheState: String = "local_only",
) {
    init {
        require(id == 1) { "SyncDeviceStateEntity is a singleton row and must use id = 1" }
    }
}

/** Singleton catalogue version, separate from account cursors. */
@Entity(tableName = "catalogue_state")
data class CatalogueStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "version") val version: String? = null,
) {
    init {
        require(id == 1) { "CatalogueStateEntity is a singleton row and must use id = 1" }
    }
}
