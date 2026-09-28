package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict org.json readers. Missing required fields, explicit nulls in required
 * positions, and wrong JSON types (numeric strings, fractional integers, bool
 * strings) are rejected instead of coerced. Missing or null optional fields decode
 * as null — never as 0, false, or an empty string.
 */
internal fun JSONObject.requiredString(name: String): String {
    if (!has(name) || isNull(name)) throw SyncModelException("Missing required field: $name")
    val value = get(name)
    if (value !is String) throw SyncModelException("Field $name must be a string")
    return value
}

internal fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    if (value !is String) throw SyncModelException("Field $name must be a string or null")
    return value
}

internal fun JSONObject.requiredInt(name: String): Int {
    if (!has(name) || isNull(name)) throw SyncModelException("Missing required field: $name")
    return strictInt(name, get(name))
}

internal fun JSONObject.optionalInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return strictInt(name, get(name))
}

private fun strictInt(name: String, value: Any?): Int = when (value) {
    is Int -> value
    is Long -> {
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw SyncModelException("Field $name is out of integer range")
        }
        value.toInt()
    }
    else -> throw SyncModelException("Field $name must be an integer")
}

internal fun JSONObject.requiredLong(name: String): Long {
    if (!has(name) || isNull(name)) throw SyncModelException("Missing required field: $name")
    return strictLong(name, get(name))
}

private fun strictLong(name: String, value: Any?): Long = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw SyncModelException("Field $name must be an integer")
}

internal fun JSONObject.requiredBoolean(name: String): Boolean {
    if (!has(name) || isNull(name)) throw SyncModelException("Missing required field: $name")
    val value = get(name)
    if (value !is Boolean) throw SyncModelException("Field $name must be a boolean")
    return value
}

internal fun JSONObject.optionalDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    if (value !is Number) throw SyncModelException("Field $name must be a number or null")
    return value.toDouble()
}

internal fun JSONObject.requiredObjectArray(name: String): List<JSONObject> {
    if (!has(name) || isNull(name)) throw SyncModelException("Missing required field: $name")
    val value = get(name)
    if (value !is JSONArray) throw SyncModelException("Field $name must be an array")
    return List(value.length()) { index ->
        val item = value.get(index)
        if (item !is JSONObject) throw SyncModelException("Field $name must contain objects")
        item
    }
}

internal fun JSONObject.optionalLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return strictLong(name, get(name))
}

internal fun JSONObject.putNull(name: String) {
    put(name, JSONObject.NULL)
}

/** Nullable string array: missing and explicit null both decode as null, never as []. */
internal fun JSONObject.optionalStringList(name: String): List<String>? {
    if (!has(name) || isNull(name)) return null
    return strictStringList(name, get(name))
}

internal fun strictStringList(name: String, value: Any?): List<String> {
    if (value !is JSONArray) throw SyncModelException("Field $name must be an array or null")
    return List(value.length()) { index ->
        val item = value.get(index)
        if (item !is String) throw SyncModelException("Field $name must contain strings")
        item
    }
}

/**
 * Rejects raw wire fields with no representation in the canonical shape. Every key
 * present in the raw object must exist in the canonical encoding; omitted optional
 * fields are permitted, array elements are checked pairwise, and scalar values need
 * no recursion. The raw string itself is never modified.
 */
internal fun requireNoUnknownFields(raw: JSONObject, canonical: JSONObject, path: String = "$") {
    raw.keys().forEach { key ->
        if (!canonical.has(key)) {
            throw SyncModelException("Unknown field $path.$key in mutation request")
        }
        val rawValue = raw.get(key)
        val canonicalValue = canonical.get(key)
        if (rawValue is JSONObject && canonicalValue is JSONObject) {
            requireNoUnknownFields(rawValue, canonicalValue, "$path.$key")
        } else if (rawValue is JSONArray && canonicalValue is JSONArray) {
            if (rawValue.length() != canonicalValue.length()) {
                throw SyncModelException("Unknown field $path.$key in mutation request")
            }
            val rawItems = (0 until rawValue.length()).map { rawValue.get(it) }
            val canonicalItems = (0 until canonicalValue.length()).map { canonicalValue.get(it) }
            // Validators sort children, so object elements match by preserved ID rather
            // than by index; anything else falls back to pairwise comparison.
            val matchable = rawItems.all { it is JSONObject && it.has("id") } &&
                canonicalItems.all { it is JSONObject && it.has("id") }
            if (matchable) {
                rawItems.forEach { rawItem ->
                    val id = (rawItem as JSONObject).get("id")
                    val match = canonicalItems.singleOrNull { (it as JSONObject).get("id") == id }
                        ?: throw SyncModelException("Unknown field $path.$key in mutation request")
                    requireNoUnknownFields(rawItem, match as JSONObject, "$path.$key[$id]")
                }
            } else {
                for (index in rawItems.indices) {
                    val rawItem = rawItems[index]
                    val canonicalItem = canonicalItems[index]
                    if (rawItem is JSONObject && canonicalItem is JSONObject) {
                        requireNoUnknownFields(rawItem, canonicalItem, "$path.$key[$index]")
                    }
                }
            }
        }
    }
}
