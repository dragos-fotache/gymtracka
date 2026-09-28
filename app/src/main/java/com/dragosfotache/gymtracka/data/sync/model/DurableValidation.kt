package com.dragosfotache.gymtracka.data.sync.model

/**
 * Validation for durable requests and affected-root effects. The decoded envelope
 * always derives from the retained request text, so a mismatch is unrepresentable
 * through the codec factory and decoders. Wrapper/column version comparison and
 * Room row identity checks arrive at the capture/storage gate.
 */

fun validateAffectedRoots(
    envelope: MutationEnvelopeModel,
    affectedRoots: List<AffectedRootModel>,
): List<AffectedRootModel> {
    val keys = affectedRoots.map { it.aggregateType to it.aggregateId }
    if (keys.toSet().size != keys.size) {
        throw SyncModelException("Affected roots contain a duplicate aggregate")
    }
    affectedRoots.forEach {
        requireWorkoutUuid(it.aggregateId, "affectedRoots[].aggregateId")
        if (it.localGeneration < 1) {
            throw SyncModelException("Affected root generation must be positive")
        }
    }
    if (affectedRoots.none {
            it.aggregateType == envelope.aggregateType &&
                it.aggregateId == envelope.aggregateId &&
                it.localGeneration == envelope.localGeneration
        }
    ) {
        throw SyncModelException("Affected roots must include the envelope target with its generation")
    }
    return affectedRoots.toList()
}

fun validateDurableRequest(model: DurableRequestModel): DurableRequestModel {
    if (model.payloadVersion != DURABLE_PAYLOAD_VERSION) {
        throw UnsupportedDurableVersionException(model.payloadVersion)
    }
    val envelope = validateMutationEnvelope(model.envelope)
    val roots = validateAffectedRoots(envelope, model.affectedRoots)
    return model.copy(envelope = envelope, affectedRoots = roots)
}
