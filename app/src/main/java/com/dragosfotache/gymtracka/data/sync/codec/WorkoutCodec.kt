package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutRecordModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict JSON codec for workout aggregates. Field names follow OpenAPI exactly.
 * Nullable fields encode as explicit null. Extra convenience fields (such as the
 * fixture's derived totalVolumeKg) are ignored on decode and never emitted.
 * No account, token, generation, revision (input), or tombstone fields on the wire.
 */

internal fun decodeWorkoutSet(json: JSONObject) = WorkoutSetModel(
        id = json.requiredString("id"),
        position = json.requiredInt("position"),
        repetitions = json.optionalInt("repetitions"),
        weightKg = json.optionalDouble("weightKg"),
        isCompleted = json.requiredBoolean("isCompleted"),
        restSeconds = json.optionalInt("restSeconds"),
    )

internal fun encodeWorkoutSet(set: WorkoutSetModel): JSONObject =
    JSONObject().apply {
        put("id", set.id)
        put("position", set.position)
        if (set.repetitions == null) putNull("repetitions") else put("repetitions", set.repetitions)
        if (set.weightKg == null) putNull("weightKg") else put("weightKg", set.weightKg)
        put("isCompleted", set.isCompleted)
        if (set.restSeconds == null) putNull("restSeconds") else put("restSeconds", set.restSeconds)
    }

internal fun decodeWorkoutExerciseInput(json: JSONObject) = WorkoutExerciseInputModel(
    id = json.requiredString("id"),
    exerciseId = json.requiredString("exerciseId"),
    position = json.requiredInt("position"),
    notes = json.requiredString("notes"),
    restDurationSeconds = json.requiredInt("restDurationSeconds"),
    sets = json.requiredObjectArray("sets").map(::decodeWorkoutSet),
)

internal fun encodeWorkoutExerciseInput(entry: WorkoutExerciseInputModel): JSONObject =
    JSONObject().apply {
        put("id", entry.id)
        put("exerciseId", entry.exerciseId)
        put("position", entry.position)
        put("notes", entry.notes)
        put("restDurationSeconds", entry.restDurationSeconds)
        put("sets", JSONArray(entry.sets.map(::encodeWorkoutSet)))
    }

internal fun decodeWorkoutExercise(json: JSONObject) = WorkoutExerciseModel(
    id = json.requiredString("id"),
    exerciseId = json.requiredString("exerciseId"),
    position = json.requiredInt("position"),
    notes = json.requiredString("notes"),
    restDurationSeconds = json.requiredInt("restDurationSeconds"),
    sets = json.requiredObjectArray("sets").map(::decodeWorkoutSet),
    exerciseName = json.requiredString("exerciseName"),
)

internal fun encodeWorkoutExercise(entry: WorkoutExerciseModel): JSONObject =
    JSONObject().apply {
        put("id", entry.id)
        put("exerciseId", entry.exerciseId)
        put("position", entry.position)
        put("notes", entry.notes)
        put("restDurationSeconds", entry.restDurationSeconds)
        put("sets", JSONArray(entry.sets.map(::encodeWorkoutSet)))
        put("exerciseName", entry.exerciseName)
    }

/** Decodes and validates active upload input. Finished history is rejected here. */
fun decodeWorkoutInput(json: JSONObject): WorkoutInputModel =
    validateWorkoutInputModel(
        WorkoutInputModel(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            status = json.requiredString("status"),
            startedAtMillis = parseUtcMillis(json.requiredString("startedAt")),
            exercises = json.requiredObjectArray("exercises").map(::decodeWorkoutExerciseInput),
        ),
    )

fun decodeWorkoutInput(jsonText: String): WorkoutInputModel =
    decodeWorkoutInput(parseStrictJsonObject(jsonText))

/** Encodes validated active upload input. Emits no revision, names, volume, or metadata. */
fun encodeWorkoutInput(input: WorkoutInputModel): JSONObject {
    val validated = validateWorkoutInputModel(input)
    return JSONObject().apply {
        put("id", validated.id)
        put("name", validated.name)
        put("status", validated.status)
        put("startedAt", formatUtcMillis(validated.startedAtMillis))
        put("exercises", JSONArray(validated.exercises.map(::encodeWorkoutExerciseInput)))
    }
}

/** Decodes and validates a canonical server workout record. */
fun decodeWorkoutRecord(json: JSONObject): WorkoutRecordModel =
    validateWorkoutRecordModel(
        WorkoutRecordModel(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            status = json.requiredString("status"),
            startedAtMillis = parseUtcMillis(json.requiredString("startedAt")),
            finishedAtMillis = json.optionalString("finishedAt")?.let(::parseUtcMillis),
            revision = json.requiredLong("revision"),
            exercises = json.requiredObjectArray("exercises").map(::decodeWorkoutExercise),
        ),
    )

fun decodeWorkoutRecord(jsonText: String): WorkoutRecordModel =
    decodeWorkoutRecord(parseStrictJsonObject(jsonText))

/** Encodes a validated canonical record. Derived volume is never emitted. */
fun encodeWorkoutRecord(record: WorkoutRecordModel): JSONObject {
    val validated = validateWorkoutRecordModel(record)
    return JSONObject().apply {
        put("id", validated.id)
        put("name", validated.name)
        put("status", validated.status)
        put("startedAt", formatUtcMillis(validated.startedAtMillis))
        val finishedAt = validated.finishedAtMillis
        if (finishedAt == null) putNull("finishedAt") else put("finishedAt", formatUtcMillis(finishedAt))
        put("revision", validated.revision)
        put("exercises", JSONArray(validated.exercises.map(::encodeWorkoutExercise)))
    }
}
