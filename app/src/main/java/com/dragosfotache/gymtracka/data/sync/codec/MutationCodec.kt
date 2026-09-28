package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ChildExerciseBinding
import com.dragosfotache.gymtracka.data.sync.model.ChildSetBinding
import com.dragosfotache.gymtracka.data.sync.model.ExerciseAddition
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.validateMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutImportModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict JSON codec for typed offline mutation envelopes. Kind wire strings match
 * the OpenAPI Mutation variants exactly. Owner, account, session, and token fields
 * never appear in wire models or encoded requests. IDs are never generated here.
 */

internal fun decodeWorkoutImport(json: JSONObject): WorkoutImportModel {
    val status = json.requiredString("status")
    val finishedAt = json.optionalString("finishedAt")?.let(::parseUtcMillis)
    return validateWorkoutImportModel(
        WorkoutImportModel(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            status = status,
            startedAtMillis = parseUtcMillis(json.requiredString("startedAt")),
            finishedAtMillis = finishedAt,
            exercises = json.requiredObjectArray("exercises").map(::decodeWorkoutExerciseInput),
        ),
    )
}

internal fun encodeWorkoutImport(snapshot: WorkoutImportModel): JSONObject =
    JSONObject().apply {
        put("id", snapshot.id)
        put("name", snapshot.name)
        put("status", snapshot.status)
        put("startedAt", formatUtcMillis(snapshot.startedAtMillis))
        val finishedAt = snapshot.finishedAtMillis
        if (finishedAt == null) putNull("finishedAt") else put("finishedAt", formatUtcMillis(finishedAt))
        put("exercises", JSONArray(snapshot.exercises.map(::encodeWorkoutExerciseInput)))
    }

internal fun decodeImportRecord(aggregateType: AggregateType, json: JSONObject): ImportRecord =
    when (aggregateType) {
        AggregateType.WORKOUT -> ImportRecord.Workout(decodeWorkoutImport(json))
        AggregateType.ROUTINE -> ImportRecord.Routine(decodeRoutineInput(json))
        AggregateType.CUSTOM_EXERCISE -> ImportRecord.CustomExercise(decodeCustomExerciseInput(json))
    }

internal fun encodeImportRecord(record: ImportRecord): JSONObject =
    when (record) {
        is ImportRecord.Workout -> encodeWorkoutImport(record.input)
        is ImportRecord.Routine -> encodeRoutineInput(record.input)
        is ImportRecord.CustomExercise -> encodeCustomExerciseInput(record.input)
    }

internal fun decodeChildSetBinding(json: JSONObject) = ChildSetBinding(
    sourceSetId = json.requiredString("sourceSetId"),
    id = json.requiredString("id"),
)

internal fun encodeChildSetBinding(binding: ChildSetBinding): JSONObject =
    JSONObject().apply {
        put("sourceSetId", binding.sourceSetId)
        put("id", binding.id)
    }

internal fun decodeChildExerciseBinding(json: JSONObject) = ChildExerciseBinding(
    sourceExerciseId = json.requiredString("sourceExerciseId"),
    id = json.requiredString("id"),
    sets = json.requiredObjectArray("sets").map(::decodeChildSetBinding),
)

internal fun encodeChildExerciseBinding(binding: ChildExerciseBinding): JSONObject =
    JSONObject().apply {
        put("sourceExerciseId", binding.sourceExerciseId)
        put("id", binding.id)
        put("sets", JSONArray(binding.sets.map(::encodeChildSetBinding)))
    }

internal fun decodeExerciseAddition(json: JSONObject): ExerciseAddition {
    if (!json.has("setIds") || json.isNull("setIds")) {
        throw SyncModelException("Missing required field: setIds")
    }
    return ExerciseAddition(
        id = json.requiredString("id"),
        exerciseId = json.requiredString("exerciseId"),
        setIds = strictStringList("setIds", json.get("setIds")),
    )
}

internal fun encodeExerciseAddition(addition: ExerciseAddition): JSONObject =
    JSONObject().apply {
        put("id", addition.id)
        put("exerciseId", addition.exerciseId)
        put("setIds", JSONArray(addition.setIds))
    }

internal fun decodeMutationPayload(kind: MutationKind, json: JSONObject): MutationPayload =
    when (kind) {
        MutationKind.WORKOUT_START -> MutationPayload.WorkoutStart(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            startedAtMillis = parseUtcMillis(json.requiredString("startedAt")),
        )
        MutationKind.WORKOUT_UPSERT -> MutationPayload.WorkoutUpsert(
            decodeWorkoutInput(json),
        )
        MutationKind.WORKOUT_FINISH -> MutationPayload.WorkoutFinish(
            finishedAtMillis = parseUtcMillis(json.requiredString("finishedAt")),
        )
        MutationKind.WORKOUT_PERFORM_AGAIN -> MutationPayload.WorkoutPerformAgain(
            sourceWorkoutId = json.requiredString("sourceWorkoutId"),
            id = json.requiredString("id"),
            startedAtMillis = parseUtcMillis(json.requiredString("startedAt")),
            childIds = json.requiredObjectArray("childIds").map(::decodeChildExerciseBinding),
        )
        MutationKind.ROUTINE_START_WORKOUT -> MutationPayload.RoutineStartWorkout(
            sourceRoutineId = json.requiredString("sourceRoutineId"),
            id = json.requiredString("id"),
            startedAtMillis = parseUtcMillis(json.requiredString("startedAt")),
            childIds = json.requiredObjectArray("childIds").map(::decodeChildExerciseBinding),
        )
        MutationKind.WORKOUT_SAVE_AS_ROUTINE -> MutationPayload.WorkoutSaveAsRoutine(
            sourceWorkoutId = json.requiredString("sourceWorkoutId"),
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            childIds = json.requiredObjectArray("childIds").map(::decodeChildExerciseBinding),
        )
        MutationKind.WORKOUT_ADD_EXERCISES -> MutationPayload.WorkoutAddExercises(
            additions = json.requiredObjectArray("additions").map(::decodeExerciseAddition),
        )
        MutationKind.WORKOUT_REPLACE_EXERCISE -> MutationPayload.WorkoutReplaceExercise(
            workoutExerciseId = json.requiredString("workoutExerciseId"),
            exerciseId = json.requiredString("exerciseId"),
        )
        MutationKind.WORKOUT_DELETE_EXERCISE -> MutationPayload.WorkoutDeleteExercise(
            workoutExerciseId = json.requiredString("workoutExerciseId"),
        )
        MutationKind.WORKOUT_DELETE_SET -> MutationPayload.WorkoutDeleteSet(
            workoutSetId = json.requiredString("workoutSetId"),
        )
        MutationKind.ROUTINE_UPSERT -> MutationPayload.RoutineUpsert(
            decodeRoutineInput(json),
        )
        MutationKind.ROUTINE_SAVE -> MutationPayload.RoutineSave(
            name = json.requiredString("name"),
        )
        MutationKind.ROUTINE_DELETE_EXERCISE -> MutationPayload.RoutineDeleteExercise(
            routineExerciseId = json.requiredString("routineExerciseId"),
        )
        MutationKind.ROUTINE_DELETE_SET -> MutationPayload.RoutineDeleteSet(
            routineSetId = json.requiredString("routineSetId"),
        )
        MutationKind.CUSTOM_EXERCISE_UPSERT -> MutationPayload.CustomExerciseUpsert(
            decodeCustomExerciseInput(json),
        )
        MutationKind.AGGREGATE_DELETE -> MutationPayload.AggregateDelete
        MutationKind.AGGREGATE_IMPORT -> throw SyncModelException(
            "Import payloads decode against their envelope aggregate type",
        )
    }

internal fun encodeMutationPayload(payload: MutationPayload): JSONObject =
    when (payload) {
        is MutationPayload.WorkoutStart -> JSONObject().apply {
            put("id", payload.id)
            put("name", payload.name)
            put("startedAt", formatUtcMillis(payload.startedAtMillis))
        }
        is MutationPayload.WorkoutUpsert -> encodeWorkoutInput(payload.input)
        is MutationPayload.WorkoutFinish -> JSONObject().apply {
            put("finishedAt", formatUtcMillis(payload.finishedAtMillis))
        }
        is MutationPayload.WorkoutPerformAgain -> JSONObject().apply {
            put("sourceWorkoutId", payload.sourceWorkoutId)
            put("id", payload.id)
            put("startedAt", formatUtcMillis(payload.startedAtMillis))
            put("childIds", JSONArray(payload.childIds.map(::encodeChildExerciseBinding)))
        }
        is MutationPayload.RoutineStartWorkout -> JSONObject().apply {
            put("sourceRoutineId", payload.sourceRoutineId)
            put("id", payload.id)
            put("startedAt", formatUtcMillis(payload.startedAtMillis))
            put("childIds", JSONArray(payload.childIds.map(::encodeChildExerciseBinding)))
        }
        is MutationPayload.WorkoutSaveAsRoutine -> JSONObject().apply {
            put("sourceWorkoutId", payload.sourceWorkoutId)
            put("id", payload.id)
            put("name", payload.name)
            put("childIds", JSONArray(payload.childIds.map(::encodeChildExerciseBinding)))
        }
        is MutationPayload.WorkoutAddExercises -> JSONObject().apply {
            put("additions", JSONArray(payload.additions.map(::encodeExerciseAddition)))
        }
        is MutationPayload.WorkoutReplaceExercise -> JSONObject().apply {
            put("workoutExerciseId", payload.workoutExerciseId)
            put("exerciseId", payload.exerciseId)
        }
        is MutationPayload.WorkoutDeleteExercise -> JSONObject().apply {
            put("workoutExerciseId", payload.workoutExerciseId)
        }
        is MutationPayload.WorkoutDeleteSet -> JSONObject().apply {
            put("workoutSetId", payload.workoutSetId)
        }
        is MutationPayload.RoutineUpsert -> encodeRoutineInput(payload.input)
        is MutationPayload.RoutineSave -> JSONObject().apply {
            put("name", payload.name)
        }
        is MutationPayload.RoutineDeleteExercise -> JSONObject().apply {
            put("routineExerciseId", payload.routineExerciseId)
        }
        is MutationPayload.RoutineDeleteSet -> JSONObject().apply {
            put("routineSetId", payload.routineSetId)
        }
        is MutationPayload.CustomExerciseUpsert -> encodeCustomExerciseInput(payload.input)
        is MutationPayload.AggregateDelete -> JSONObject()
        is MutationPayload.AggregateImport -> JSONObject().apply {
            put("record", encodeImportRecord(payload.record))
        }
    }

/** Decodes and validates a typed mutation envelope with its payload. */
fun decodeMutationEnvelope(json: JSONObject): MutationEnvelopeModel {
    val kind = MutationKind.fromWire(json.requiredString("type"))
    val aggregateType = AggregateType.fromWire(json.requiredString("aggregateType"))
    val rawPayload = json.opt("payload")
    if (rawPayload !is JSONObject) throw SyncModelException("Mutation payload must be an object")
    if (kind == MutationKind.AGGREGATE_DELETE && rawPayload.length() != 0) {
        throw SyncModelException("Delete payload must be empty")
    }
    val payload = if (kind == MutationKind.AGGREGATE_IMPORT) {
        if (!rawPayload.has("record") || rawPayload.isNull("record")) {
            throw SyncModelException("Import payload requires a record")
        }
        val rawRecord = rawPayload.get("record")
        if (rawRecord !is JSONObject) throw SyncModelException("Import record must be an object")
        MutationPayload.AggregateImport(
            decodeImportRecord(aggregateType, rawRecord),
        )
    } else {
        decodeMutationPayload(kind, rawPayload)
    }
    return validateMutationEnvelope(
        MutationEnvelopeModel(
            mutationId = json.requiredString("mutationId"),
            kind = kind,
            aggregateType = aggregateType,
            aggregateId = json.requiredString("aggregateId"),
            localGeneration = json.requiredLong("localGeneration"),
            knownServerRevision = json.optionalLong("knownServerRevision"),
            payload = payload,
        ),
    ).also { validated ->
        // Frozen raw bytes travel verbatim, so unrepresented fields are rejected here
        // while tolerant canonical-record decoding stays unchanged elsewhere.
        requireNoUnknownFields(json, encodeMutationEnvelope(validated))
    }
}

fun decodeMutationEnvelope(jsonText: String): MutationEnvelopeModel =
    decodeMutationEnvelope(parseStrictJsonObject(jsonText))

/** Encodes a validated mutation envelope. Emits no owner/session/token fields. */
fun encodeMutationEnvelope(envelope: MutationEnvelopeModel): JSONObject {
    val validated = validateMutationEnvelope(envelope)
    return JSONObject().apply {
        put("mutationId", validated.mutationId)
        put("type", validated.kind.wireValue)
        put("aggregateType", validated.aggregateType.wireValue)
        put("aggregateId", validated.aggregateId)
        put("localGeneration", validated.localGeneration)
        val known = validated.knownServerRevision
        if (known == null) putNull("knownServerRevision") else put("knownServerRevision", known)
        put("payload", encodeMutationPayload(validated.payload))
    }
}
