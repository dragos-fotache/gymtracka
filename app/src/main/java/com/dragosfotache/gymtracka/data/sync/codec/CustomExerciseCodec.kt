package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.validateCustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateCustomExerciseRecordModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict JSON codec for custom-exercise aggregates. Field names follow OpenAPI
 * CustomExerciseInput and canonical Exercise exactly. The instruction array is
 * preserved exactly (null versus [] versus [""]); secondary IDs decode missing as
 * empty but reject explicit null. Quantity is preserved with its equipment
 * selection. No owner, revision (input), display-name writes, or isUserCreated
 * appear in upload input.
 */

internal fun decodeMuscleGroupRef(json: JSONObject) = MuscleGroupRefModel(
    id = json.requiredString("id"),
    name = json.requiredString("name"),
    type = json.requiredString("type"),
)

internal fun encodeMuscleGroupRef(ref: MuscleGroupRefModel): JSONObject =
    JSONObject().apply {
        put("id", ref.id)
        put("name", ref.name)
        put("type", ref.type)
    }

internal fun decodeEquipmentRef(json: JSONObject) = EquipmentRefModel(
    id = json.requiredString("id"),
    name = json.requiredString("name"),
    numItems = json.optionalInt("numItems"),
)

internal fun encodeEquipmentRef(ref: EquipmentRefModel): JSONObject =
    JSONObject().apply {
        put("id", ref.id)
        put("name", ref.name)
        if (ref.numItems == null) putNull("numItems") else put("numItems", ref.numItems)
    }

private fun JSONObject.secondaryMuscleGroupIds(): List<String> {
    if (!has("secondaryMuscleGroupIds")) return emptyList()
    if (isNull("secondaryMuscleGroupIds")) {
        throw SyncModelException("Field secondaryMuscleGroupIds must be an array when present")
    }
    return strictStringList("secondaryMuscleGroupIds", get("secondaryMuscleGroupIds"))
}

/** Decodes and validates custom-exercise upload input. Blank names are rejected. */
fun decodeCustomExerciseInput(json: JSONObject): CustomExerciseInputModel =
    validateCustomExerciseInputModel(
        CustomExerciseInputModel(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            primaryMuscleGroupId = json.optionalString("primaryMuscleGroupId"),
            secondaryMuscleGroupIds = json.secondaryMuscleGroupIds(),
            equipmentId = json.optionalString("equipmentId"),
            equipmentNumItems = json.optionalInt("equipmentNumItems"),
            category = json.optionalString("category"),
            difficultyLevel = json.optionalString("difficultyLevel"),
            forceType = json.optionalString("forceType"),
            mechanics = json.optionalString("mechanics"),
            instructions = json.optionalStringList("instructions"),
        ),
    )

fun decodeCustomExerciseInput(jsonText: String): CustomExerciseInputModel =
    decodeCustomExerciseInput(parseStrictJsonObject(jsonText))

/** Encodes validated upload input. Nullable fields encode as explicit null. */
fun encodeCustomExerciseInput(input: CustomExerciseInputModel): JSONObject {
    val validated = validateCustomExerciseInputModel(input)
    return JSONObject().apply {
        put("id", validated.id)
        put("name", validated.name)
        val primary = validated.primaryMuscleGroupId
        if (primary == null) putNull("primaryMuscleGroupId") else put("primaryMuscleGroupId", primary)
        put("secondaryMuscleGroupIds", JSONArray(validated.secondaryMuscleGroupIds))
        val equipment = validated.equipmentId
        if (equipment == null) putNull("equipmentId") else put("equipmentId", equipment)
        val quantity = validated.equipmentNumItems
        if (quantity == null) putNull("equipmentNumItems") else put("equipmentNumItems", quantity)
        val category = validated.category
        if (category == null) putNull("category") else put("category", category)
        val difficulty = validated.difficultyLevel
        if (difficulty == null) putNull("difficultyLevel") else put("difficultyLevel", difficulty)
        val force = validated.forceType
        if (force == null) putNull("forceType") else put("forceType", force)
        val mechanics = validated.mechanics
        if (mechanics == null) putNull("mechanics") else put("mechanics", mechanics)
        val instructions = validated.instructions
        if (instructions == null) putNull("instructions") else put("instructions", JSONArray(instructions))
    }
}

/** Decodes and validates a canonical custom-exercise record. Bundled rows are rejected. */
fun decodeCustomExerciseRecord(json: JSONObject): CustomExerciseRecordModel =
    validateCustomExerciseRecordModel(
        CustomExerciseRecordModel(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            type = json.requiredString("type"),
            category = json.optionalString("category"),
            difficultyLevel = json.optionalString("difficultyLevel"),
            forceType = json.optionalString("forceType"),
            mechanics = json.optionalString("mechanics"),
            instructions = json.optionalStringList("instructions"),
            isUserCreated = json.requiredBoolean("isUserCreated"),
            revision = json.requiredLong("revision"),
            muscleGroups = json.requiredObjectArray("muscleGroups").map(::decodeMuscleGroupRef),
            equipment = json.requiredObjectArray("equipment").map(::decodeEquipmentRef),
        ),
    )

fun decodeCustomExerciseRecord(jsonText: String): CustomExerciseRecordModel =
    decodeCustomExerciseRecord(parseStrictJsonObject(jsonText))

/** Encodes a validated canonical record, preserving metadata and quantity exactly. */
fun encodeCustomExerciseRecord(record: CustomExerciseRecordModel): JSONObject {
    val validated = validateCustomExerciseRecordModel(record)
    return JSONObject().apply {
        put("id", validated.id)
        put("name", validated.name)
        put("type", validated.type)
        val category = validated.category
        if (category == null) putNull("category") else put("category", category)
        val difficulty = validated.difficultyLevel
        if (difficulty == null) putNull("difficultyLevel") else put("difficultyLevel", difficulty)
        val force = validated.forceType
        if (force == null) putNull("forceType") else put("forceType", force)
        val mechanics = validated.mechanics
        if (mechanics == null) putNull("mechanics") else put("mechanics", mechanics)
        val instructions = validated.instructions
        if (instructions == null) putNull("instructions") else put("instructions", JSONArray(instructions))
        put("isUserCreated", validated.isUserCreated)
        put("revision", validated.revision)
        put("muscleGroups", JSONArray(validated.muscleGroups.map(::encodeMuscleGroupRef)))
        put("equipment", JSONArray(validated.equipment.map(::encodeEquipmentRef)))
    }
}
