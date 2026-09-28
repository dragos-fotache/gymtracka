package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Strict top-level JSON parsing. JSONObject(String) on Android accepts non-JSON
 * syntax (single quotes, unquoted keys, trailing commas) and trailing text, so raw
 * bodies are first validated with kotlinx-serialization in strict default mode and
 * only the normalized object reaches the field helpers. Failures carry a short
 * message that never echoes the raw body. No @Serializable framework is used;
 * conversion stays with the explicit JSONObject field readers.
 */
internal fun parseStrictJsonObject(text: String): JSONObject {
    val element = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        throw SyncModelException("Body is not valid JSON")
    }
    if (element !is JsonObject) throw SyncModelException("Body must be a JSON object")
    return JSONObject(element.toString())
}
