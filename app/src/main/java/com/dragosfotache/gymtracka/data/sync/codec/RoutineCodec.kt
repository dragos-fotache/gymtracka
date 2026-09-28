package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineRecordModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict JSON codec for routine aggregates. Field names follow OpenAPI exactly.
 * Routine sets never carry completion or recorded rest. Blank names and empty
 * children are valid shapes; save/promotion rules belong to the service.
 */

internal fun decodeRoutineSet(json: JSONObject) = RoutineSetModel(
    id = json.requiredString("id"),
    position = json.requiredInt("position"),
    repetitions = json.optionalInt("repetitions"),
    weightKg = json.optionalDouble("weightKg"),
)

internal fun encodeRoutineSet(set: RoutineSetModel): JSONObject =
    JSONObject().apply {
        put("id", set.id)
        put("position", set.position)
        if (set.repetitions == null) putNull("repetitions") else put("repetitions", set.repetitions)
        if (set.weightKg == null) putNull("weightKg") else put("weightKg", set.weightKg)
    }

internal fun decodeRoutineExerciseInput(json: JSONObject) = RoutineExerciseInputModel(
    id = json.requiredString("id"),
    exerciseId = json.requiredString("exerciseId"),
    position = json.requiredInt("position"),
    restDurationSeconds = json.requiredInt("restDurationSeconds"),
    sets = json.requiredObjectArray("sets").map(::decodeRoutineSet),
)

internal fun encodeRoutineExerciseInput(entry: RoutineExerciseInputModel): JSONObject =
    JSONObject().apply {
        put("id", entry.id)
        put("exerciseId", entry.exerciseId)
        put("position", entry.position)
        put("restDurationSeconds", entry.restDurationSeconds)
        put("sets", JSONArray(entry.sets.map(::encodeRoutineSet)))
    }

internal fun decodeRoutineExercise(json: JSONObject) = RoutineExerciseModel(
    id = json.requiredString("id"),
    exerciseId = json.requiredString("exerciseId"),
    position = json.requiredInt("position"),
    restDurationSeconds = json.requiredInt("restDurationSeconds"),
    sets = json.requiredObjectArray("sets").map(::decodeRoutineSet),
    exerciseName = json.requiredString("exerciseName"),
)

internal fun encodeRoutineExercise(entry: RoutineExerciseModel): JSONObject =
    JSONObject().apply {
        put("id", entry.id)
        put("exerciseId", entry.exerciseId)
        put("position", entry.position)
        put("restDurationSeconds", entry.restDurationSeconds)
        put("sets", JSONArray(entry.sets.map(::encodeRoutineSet)))
        put("exerciseName", entry.exerciseName)
    }

/** Decodes and validates routine upload input, including blank-name and empty drafts. */
fun decodeRoutineInput(json: JSONObject): RoutineInputModel =
    validateRoutineInputModel(
        RoutineInputModel(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            isDraft = json.requiredBoolean("isDraft"),
            exercises = json.requiredObjectArray("exercises").map(::decodeRoutineExerciseInput),
        ),
    )

fun decodeRoutineInput(jsonText: String): RoutineInputModel =
    decodeRoutineInput(parseStrictJsonObject(jsonText))

/** Encodes validated routine input. Emits no revision, names, or metadata. */
fun encodeRoutineInput(input: RoutineInputModel): JSONObject {
    val validated = validateRoutineInputModel(input)
    return JSONObject().apply {
        put("id", validated.id)
        put("name", validated.name)
        put("isDraft", validated.isDraft)
        put("exercises", JSONArray(validated.exercises.map(::encodeRoutineExerciseInput)))
    }
}

/** Decodes and validates a canonical routine record. */
fun decodeRoutineRecord(json: JSONObject): RoutineRecordModel =
    validateRoutineRecordModel(
        RoutineRecordModel(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            isDraft = json.requiredBoolean("isDraft"),
            revision = json.requiredLong("revision"),
            exercises = json.requiredObjectArray("exercises").map(::decodeRoutineExercise),
        ),
    )

fun decodeRoutineRecord(jsonText: String): RoutineRecordModel =
    decodeRoutineRecord(parseStrictJsonObject(jsonText))

/** Encodes a validated canonical routine record. */
fun encodeRoutineRecord(record: RoutineRecordModel): JSONObject {
    val validated = validateRoutineRecordModel(record)
    return JSONObject().apply {
        put("id", validated.id)
        put("name", validated.name)
        put("isDraft", validated.isDraft)
        put("revision", validated.revision)
        put("exercises", JSONArray(validated.exercises.map(::encodeRoutineExercise)))
    }
}
