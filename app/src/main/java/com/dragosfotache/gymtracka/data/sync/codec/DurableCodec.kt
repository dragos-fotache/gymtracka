package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DURABLE_PAYLOAD_VERSION
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import com.dragosfotache.gymtracka.data.sync.model.validateAffectedRoots
import com.dragosfotache.gymtracka.data.sync.model.validateDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.validateMutationEnvelope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec for the local durable request wrapper and push-body embedding.
 * The wrapper shape is `{payloadVersion, requestJson, affectedRoots}` with the exact
 * frozen mutation text. The wrapper and its effects never enter HTTP; only the
 * embedded request texts form the push body. Failures never echo raw bodies.
 */

/** OpenAPI push limit for one mutations array. */
const val MAX_PUSH_MUTATIONS = 100

/**
 * Captures a validated envelope as an immutable durable request. The wire text is
 * encoded once; the decoded envelope derives from that exact text, so retries can
 * never diverge from the frozen bytes.
 */
fun captureDurableRequest(
    envelope: MutationEnvelopeModel,
    affectedRoots: List<AffectedRootModel>,
): DurableRequestModel {
    val requestJson = encodeMutationEnvelope(validateMutationEnvelope(envelope)).toString()
    val decoded = decodeMutationEnvelope(parseStrictJsonObject(requestJson))
    val roots = validateAffectedRoots(decoded, affectedRoots.toList())
    return DurableRequestModel(DURABLE_PAYLOAD_VERSION, requestJson, roots, decoded)
}

internal fun decodeAffectedRoot(json: JSONObject): AffectedRootModel =
    AffectedRootModel(
        aggregateType = AggregateType.fromWire(json.requiredString("aggregateType")),
        aggregateId = json.requiredString("aggregateId"),
        localGeneration = json.requiredLong("localGeneration"),
    )

internal fun encodeAffectedRoot(root: AffectedRootModel): JSONObject =
    JSONObject().apply {
        put("aggregateType", root.aggregateType.wireValue)
        put("aggregateId", root.aggregateId)
        put("localGeneration", root.localGeneration)
    }

/**
 * Decodes a durable wrapper. The payload version is dispatched first; unsupported
 * versions throw distinctly with version metadata and leave content uninterpreted.
 * The original request text is retained unchanged for retries.
 */
fun decodeDurableRequest(json: JSONObject): DurableRequestModel {
    val version = json.requiredInt("payloadVersion")
    if (version != DURABLE_PAYLOAD_VERSION) throw UnsupportedDurableVersionException(version)
    val requestJson = json.requiredString("requestJson")
    val roots = json.requiredObjectArray("affectedRoots").map(::decodeAffectedRoot)
    val envelope = decodeMutationEnvelope(parseStrictJsonObject(requestJson))
    return validateDurableRequest(DurableRequestModel(version, requestJson, roots, envelope))
}

fun decodeDurableRequest(jsonText: String): DurableRequestModel =
    decodeDurableRequest(parseStrictJsonObject(jsonText))

/**
 * Verifies a hand-built durable model by parsing its exact raw request text and
 * requiring the decoded envelope to match the exposed one. The raw text is never
 * modified. Public constructors cannot be trusted to keep the two consistent, so
 * both encoding paths run this check before trusting IDs or effects.
 */
internal fun requireDurableTextMatches(model: DurableRequestModel): DurableRequestModel {
    // Version dispatch precedes raw-text parsing so unsupported versions fail
    // distinctly even when the stored text is malformed.
    if (model.payloadVersion != DURABLE_PAYLOAD_VERSION) {
        throw UnsupportedDurableVersionException(model.payloadVersion)
    }
    val decoded = decodeMutationEnvelope(parseStrictJsonObject(model.requestJson))
    val envelope = validateMutationEnvelope(model.envelope)
    if (decoded != envelope) {
        throw SyncModelException("Durable request text does not match its envelope")
    }
    return model.copy(envelope = envelope)
}

fun encodeDurableRequest(model: DurableRequestModel): JSONObject {
    val validated = validateDurableRequest(requireDurableTextMatches(model))
    return JSONObject().apply {
        put("payloadVersion", validated.payloadVersion)
        put("requestJson", validated.requestJson)
        put("affectedRoots", JSONArray(validated.affectedRoots.map(::encodeAffectedRoot)))
    }
}

/**
 * Encodes a push body's mutations array by embedding each validated frozen request
 * byte-for-byte. Inner requests are never re-serialized. Enforces the OpenAPI
 * maximum of 100 mutations and rejects duplicate mutation IDs; an empty list
 * encodes a valid empty array.
 */
fun encodePushMutationsBody(requests: List<DurableRequestModel>): String {
    if (requests.size > MAX_PUSH_MUTATIONS) {
        throw SyncModelException("Push body exceeds 100 mutations")
    }
    requests.forEach {
        validateDurableRequest(requireDurableTextMatches(it))
    }
    val ids = requests.map { it.envelope.mutationId }
    if (ids.toSet().size != ids.size) {
        throw SyncModelException("Push body contains duplicate mutation IDs")
    }
    return "{\"mutations\":[" + requests.joinToString(",") { it.requestJson } + "]}"
}
