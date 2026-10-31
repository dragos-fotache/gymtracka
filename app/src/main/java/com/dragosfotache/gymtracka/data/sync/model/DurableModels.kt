package com.dragosfotache.gymtracka.data.sync.model

/**
 * Local versioned durable request wrapper, exactly per the client protocol:
 * `{payloadVersion:1, requestJson, affectedRoots}`. The wrapper and its effects
 * never enter HTTP; they are local publication evidence only. No account, token,
 * or session fields appear here.
 */

/** Durable payload version carried by every wrapper. */
const val DURABLE_PAYLOAD_VERSION = 1

/** Unsupported wrapper versions fail distinctly with version metadata. */
class UnsupportedDurableVersionException(val payloadVersion: Int) :
    IllegalArgumentException("Unsupported durable payload version: $payloadVersion")

/** One locally affected root, including extra roots such as a replaced old active. */
data class AffectedRootModel(
    val aggregateType: AggregateType,
    val aggregateId: String,
    val localGeneration: Long,
)

/**
 * Frozen validated request with its exact wire text. The decoded envelope and
 * effects are exposed read-only for engine use; the request text is the
 * retry authority and is never regenerated or normalized.
 */
data class DurableRequestModel(
    val payloadVersion: Int,
    val requestJson: String,
    val affectedRoots: List<AffectedRootModel>,
    val envelope: MutationEnvelopeModel,
)
