package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationOutcomeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.validateMutationOutcome
import com.dragosfotache.gymtracka.data.sync.model.validatePullResult
import com.dragosfotache.gymtracka.data.sync.model.validatePushResult
import com.dragosfotache.gymtracka.data.sync.model.validateSnapshotResult
import com.dragosfotache.gymtracka.data.sync.model.validateSyncBatch
import com.dragosfotache.gymtracka.data.sync.model.validateSyncChange
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict JSON codec for canonical changes, batches, and push/pull/snapshot results.
 * Field names follow OpenAPI exactly; cursors stay opaque throughout. Unknown
 * convenience fields are ignored; malformed fields fail without body leakage.
 * No owner, session, or local metadata appears in server responses.
 */

internal fun decodeCanonicalRecord(aggregateType: AggregateType, json: JSONObject): CanonicalRecord =
    when (aggregateType) {
        AggregateType.WORKOUT -> CanonicalRecord.Workout(decodeWorkoutRecord(json))
        AggregateType.ROUTINE -> CanonicalRecord.Routine(decodeRoutineRecord(json))
        AggregateType.CUSTOM_EXERCISE -> CanonicalRecord.CustomExercise(decodeCustomExerciseRecord(json))
    }

internal fun encodeCanonicalRecord(record: CanonicalRecord): JSONObject =
    when (record) {
        is CanonicalRecord.Workout -> encodeWorkoutRecord(record.record)
        is CanonicalRecord.Routine -> encodeRoutineRecord(record.record)
        is CanonicalRecord.CustomExercise -> encodeCustomExerciseRecord(record.record)
    }

internal fun decodeSyncChange(json: JSONObject): SyncChangeModel {
    val aggregateType = AggregateType.fromWire(json.requiredString("aggregateType"))
    val operation = ChangeOperation.fromWire(json.requiredString("operation"))
    val hasRecord = json.has("record") && !json.isNull("record")
    val record = if (!hasRecord) {
        null
    } else {
        val raw = json.get("record")
        if (raw !is JSONObject) throw SyncModelException("Change record must be an object or null")
        decodeCanonicalRecord(aggregateType, raw)
    }
    return validateSyncChange(
        SyncChangeModel(
            aggregateType = aggregateType,
            aggregateId = json.requiredString("aggregateId"),
            operation = operation,
            revision = json.requiredLong("revision"),
            record = record,
        ),
    )
}

internal fun encodeSyncChange(change: SyncChangeModel): JSONObject {
    val validated = validateSyncChange(change)
    return JSONObject().apply {
        put("aggregateType", validated.aggregateType.wireValue)
        put("aggregateId", validated.aggregateId)
        put("operation", validated.operation.wireValue)
        put("revision", validated.revision)
        val record = validated.record
        if (record == null) putNull("record") else put("record", encodeCanonicalRecord(record))
    }
}

internal fun decodeSyncBatch(json: JSONObject): SyncBatchModel =
    validateSyncBatch(
        SyncBatchModel(
            batchId = json.requiredString("batchId"),
            cursor = json.requiredString("cursor"),
            changes = json.requiredObjectArray("changes").map(::decodeSyncChange),
        ),
    )

internal fun encodeSyncBatch(batch: SyncBatchModel): JSONObject {
    val validated = validateSyncBatch(batch)
    return JSONObject().apply {
        put("batchId", validated.batchId)
        put("cursor", validated.cursor)
        put("changes", JSONArray(validated.changes.map(::encodeSyncChange)))
    }
}

internal fun decodeMutationOutcome(json: JSONObject): MutationOutcomeModel {
    val hasBatch = json.has("batch") && !json.isNull("batch")
    val batch = if (!hasBatch) {
        null
    } else {
        val raw = json.get("batch")
        if (raw !is JSONObject) throw SyncModelException("Outcome batch must be an object or null")
        decodeSyncBatch(raw)
    }
    return validateMutationOutcome(
        MutationOutcomeModel(
            mutationId = json.requiredString("mutationId"),
            status = MutationStatus.fromWire(json.requiredString("status")),
            errorCode = json.optionalString("errorCode"),
            batch = batch,
        ),
    )
}

internal fun encodeMutationOutcome(outcome: MutationOutcomeModel): JSONObject {
    val validated = validateMutationOutcome(outcome)
    return JSONObject().apply {
        put("mutationId", validated.mutationId)
        put("status", validated.status.wireValue)
        val errorCode = validated.errorCode
        if (errorCode == null) putNull("errorCode") else put("errorCode", errorCode)
        val batch = validated.batch
        if (batch == null) putNull("batch") else put("batch", encodeSyncBatch(batch))
    }
}

/** Decodes and validates a push response. An empty result list is valid. */
fun decodePushResult(json: JSONObject): PushResultModel =
    validatePushResult(
        PushResultModel(
            results = json.requiredObjectArray("results").map(::decodeMutationOutcome),
        ),
    )

fun decodePushResult(jsonText: String): PushResultModel =
    decodePushResult(parseStrictJsonObject(jsonText))

fun encodePushResult(result: PushResultModel): JSONObject {
    val validated = validatePushResult(result)
    return JSONObject().apply {
        put("results", JSONArray(validated.results.map(::encodeMutationOutcome)))
    }
}

/** Decodes and validates an incremental pull page. */
fun decodePullResult(json: JSONObject): PullResultModel =
    validatePullResult(
        PullResultModel(
            batches = json.requiredObjectArray("batches").map(::decodeSyncBatch),
            nextCursor = json.requiredString("nextCursor"),
            hasMore = json.requiredBoolean("hasMore"),
        ),
    )

fun decodePullResult(jsonText: String): PullResultModel =
    decodePullResult(parseStrictJsonObject(jsonText))

fun encodePullResult(result: PullResultModel): JSONObject {
    val validated = validatePullResult(result)
    return JSONObject().apply {
        put("batches", JSONArray(validated.batches.map(::encodeSyncBatch)))
        put("nextCursor", validated.nextCursor)
        put("hasMore", validated.hasMore)
    }
}

/** Decodes and validates a full-resync snapshot page. */
fun decodeSnapshotResult(json: JSONObject): SnapshotResultModel =
    validateSnapshotResult(
        SnapshotResultModel(
            records = json.requiredObjectArray("records").map(::decodeSyncChange),
            baselineCursor = json.requiredString("baselineCursor"),
            nextPageToken = json.optionalString("nextPageToken"),
            hasMore = json.requiredBoolean("hasMore"),
        ),
    )

fun decodeSnapshotResult(jsonText: String): SnapshotResultModel =
    decodeSnapshotResult(parseStrictJsonObject(jsonText))

fun encodeSnapshotResult(result: SnapshotResultModel): JSONObject {
    val validated = validateSnapshotResult(result)
    return JSONObject().apply {
        put("records", JSONArray(validated.records.map(::encodeSyncChange)))
        put("baselineCursor", validated.baselineCursor)
        val token = validated.nextPageToken
        if (token == null) putNull("nextPageToken") else put("nextPageToken", token)
        put("hasMore", validated.hasMore)
    }
}
