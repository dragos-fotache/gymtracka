package com.dragosfotache.gymtracka.data.gym

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Independent durable session-authority journal for R09a private preparation/fencing.
 * One singleton row (id 1) with no foreign keys, indexes or domain credentials.
 * Holds only nonsecret identity/transition state: monotonic revision, session
 * generation fence, phase label, canonical service origin provenance, private
 * account and activation operation. Stored labels alone never authorize network
 * or account visibility; only the inert [com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority]
 * adapter interprets them, strictly and transactionally.
 */
@Entity(tableName = "sync_session_authority")
data class SyncSessionAuthorityEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "session_generation") val sessionGeneration: Long,
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "service_origin") val serviceOrigin: String?,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "operation_id") val operationId: String?,
) {
    init {
        require(id == 1) { "SyncSessionAuthorityEntity is a singleton row and must use id = 1" }
    }

    override fun toString(): String = "SyncSessionAuthorityEntity(redacted)"
}
