package com.dragosfotache.gymtracka.data.gym

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable legacy-import singleton journal for local capture inert capture/recovery.
 * One row with id 1, no foreign keys to domain/outbox so evidence survives
 * their later removal. Holds only nonsecret identity/progress state plus
 * immutable raw source evidence. Phase is `captured` or `blocked`; neither
 * means remotely accepted. Source evidence may contain private domain values
 * and must never appear in logs, displays, reports or throwable chains.
 */
@Entity(
    tableName = "sync_legacy_import",
)
data class SyncLegacyImportEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "evidence_version") val evidenceVersion: Long,
    @ColumnInfo(name = "service_origin") val serviceOrigin: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "source_revision") val sourceRevision: Long,
    @ColumnInfo(name = "source_session_generation") val sourceSessionGeneration: Long,
    @ColumnInfo(name = "room_revision") val roomRevision: Long,
    @ColumnInfo(name = "room_session_generation") val roomSessionGeneration: Long,
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "source_json") val sourceJson: String,
    @ColumnInfo(name = "import_count") val importCount: Long,
    @ColumnInfo(name = "error_code") val errorCode: String?,
) {
    init {
        require(id == 1) { "SyncLegacyImportEntity is a singleton row and must use id = 1" }
    }

    override fun toString(): String = "SyncLegacyImportEntity(redacted)"
}

/**
 * Immutable exact request evidence for each captured AGGREGATE_IMPORT.
 * Ordinal is consecutive zero-based. No foreign keys to domain/outbox.
 * Payload bytes are the exact original durable wrapper bytes.
 */
@Entity(
    tableName = "sync_legacy_import_entries",
    indices = [
        Index(value = ["mutation_id"], unique = true),
        Index(value = ["sequence"], unique = true),
    ],
)
data class SyncLegacyImportEntryEntity(
    @PrimaryKey @ColumnInfo(name = "ordinal") val ordinal: Long,
    @ColumnInfo(name = "aggregate_type") val aggregateType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
    @ColumnInfo(name = "sequence") val sequence: Long,
    @ColumnInfo(name = "local_generation") val localGeneration: Long,
    @ColumnInfo(name = "payload_version") val payloadVersion: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
) {
    override fun toString(): String = "SyncLegacyImportEntryEntity(redacted)"
}

/**
 * current-lineage anchor for one captured migration. Singleton id=1.
 * Binds through the immutable `sync_legacy_import` origin/account; it is
 * NOT independent session authority. `operation_id` is null only for the
 * signed-out fence; `active_consent_mutation_id` holds the exact original
 * active-workout mutation consented for cloud-active replacement.
 */
@Entity(
    tableName = "sync_legacy_import_progress",
)
data class SyncLegacyImportProgressEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "room_revision") val roomRevision: Long,
    @ColumnInfo(name = "room_session_generation") val roomSessionGeneration: Long,
    @ColumnInfo(name = "room_phase") val roomPhase: String,
    @ColumnInfo(name = "operation_id") val operationId: String?,
    @ColumnInfo(name = "active_consent_mutation_id") val activeConsentMutationId: String?,
) {
    init {
        require(id == 1) { "SyncLegacyImportProgressEntity is a singleton row and must use id = 1" }
    }

    override fun toString(): String = "SyncLegacyImportProgressEntity(redacted)"
}

/**
 * immutable acceptance receipt for one validated accepted or
 * original-accepted outcome. Ordinal is consecutive zero-based; `batch_json`
 * is the validated complete accepted canonical batch via the strict batch
 * codec. No cascades into domain/outbox and no automatic cleanup.
 */
@Entity(
    tableName = "sync_legacy_import_acceptances",
    indices = [
        Index(value = ["mutation_id"], unique = true),
    ],
)
data class SyncLegacyImportAcceptanceEntity(
    @PrimaryKey @ColumnInfo(name = "ordinal") val ordinal: Long,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
    @ColumnInfo(name = "batch_json") val batchJson: String,
) {
    override fun toString(): String = "SyncLegacyImportAcceptanceEntity(redacted)"
}
