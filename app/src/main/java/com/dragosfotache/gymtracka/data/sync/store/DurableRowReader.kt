package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.model.DURABLE_PAYLOAD_VERSION
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException

/** A stored outbox row with its decoded full durable request. Read-only. */
data class DurableOutboxRow(
    val row: SyncOutboxEntity,
    val request: DurableRequestModel,
)

/**
 * Focused durable-row reader for later engine use. Checks the payload column version
 * before content, decodes the wrapper with decodeDurableRequest (version-first), and
 * exposes the full request with its exact raw text and effects. Every row/envelope
 * identity is then compared: mutation ID, aggregate type/ID, mutation type, and local
 * generation, with the affected target matching the row itself. Unknown versions throw
 * distinctly; corrupt mismatches fail explicitly. The payload column is never parsed
 * as a bare mutation. Neither path mutates or deletes data, and the frozen inner
 * JSON is never rewritten.
 */
fun readDurableOutboxRow(row: SyncOutboxEntity): DurableOutboxRow {
    if (row.payloadVersion != DURABLE_PAYLOAD_VERSION) {
        throw UnsupportedDurableVersionException(row.payloadVersion)
    }
    val request = decodeDurableRequest(parseStrictJsonObject(row.payloadJson))
    val envelope = request.envelope
    if (envelope.mutationId != row.mutationId) {
        throw SyncModelException("Outbox row mutation ID does not match its request")
    }
    if (envelope.aggregateType.wireValue != row.aggregateType) {
        throw SyncModelException("Outbox row aggregate type does not match its request")
    }
    if (envelope.aggregateId != row.aggregateId) {
        throw SyncModelException("Outbox row aggregate ID does not match its request")
    }
    if (envelope.kind.wireValue != row.mutationType) {
        throw SyncModelException("Outbox row mutation type does not match its request")
    }
    if (envelope.localGeneration != row.localGeneration) {
        throw SyncModelException("Outbox row generation does not match its request")
    }
    val target = request.affectedRoots.singleOrNull {
        it.aggregateType == envelope.aggregateType && it.aggregateId == envelope.aggregateId
    } ?: throw SyncModelException("Outbox row affected target does not match its request")
    if (target.localGeneration != row.localGeneration) {
        throw SyncModelException("Outbox row affected generation does not match its request")
    }
    return DurableOutboxRow(row, request)
}
