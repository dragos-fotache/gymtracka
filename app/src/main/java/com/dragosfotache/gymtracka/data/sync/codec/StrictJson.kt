package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

/**
 * Strict top-level JSON parsing. JSONObject(String) on Android accepts non-JSON
 * syntax (single quotes, unquoted keys, trailing commas) and trailing text, so raw
 * bodies are first validated with kotlinx-serialization in strict default mode and
 * only the normalized object reaches the field helpers. Failures carry a short
 * message that never echoes the raw body. No @Serializable framework is used;
 * conversion stays with the explicit JSONObject field readers.
 *
 * Raw JSON object member names must also be unique per object: the original text is
 * scanned for decoded duplicate keys before the normalized object is trusted, so a
 * repeated name can never collapse silently. Valid bytes decode exactly as before.
 */
internal fun parseStrictJsonObject(text: String): JSONObject {
    rejectDuplicateJsonKeys(text)
    val element = try {
        Json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        throw SyncModelException("Body is not valid JSON")
    }
    if (element !is JsonObject) throw SyncModelException("Body must be a JSON object")
    return JSONObject(element.toString())
}

/**
 * Rejects duplicate decoded member names in every object of the original text.
 * In valid JSON a quoted string followed by a colon (after JSON whitespace) is
 * always an object member name, so only those literals are decoded; all other
 * string values stay opaque and are never reparsed. Names decode with the shared
 * strict JSON reader, so escape-equivalent spellings alias while case, Unicode
 * shape, and surrounding text stay significant. Uniqueness is per object:
 * sibling, nested, and array-element scopes are independent.
 *
 * The walk is iterative over a single pass with an explicit scope stack: no
 * recursion, no depth/length cap, and no rescanning. Anything malformed is left
 * to the strict parser below; only a positively identified duplicate throws,
 * with fixed text that carries no body, name, value, or cause.
 */
private fun rejectDuplicateJsonKeys(text: String) {
    // Null entries mark array scopes; object scopes carry their decoded member names.
    val scopes = ArrayDeque<HashSet<String>?>()
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '{' -> {
                scopes.addLast(HashSet())
                index++
            }
            '[' -> {
                scopes.addLast(null)
                index++
            }
            '}', ']' -> {
                if (scopes.isEmpty()) return
                scopes.removeLast()
                index++
            }
            '"' -> {
                val end = scanQuotedString(text, index)
                if (end < 0) return
                if (isJsonMemberName(text, end)) {
                    val keys = scopes.lastOrNull() ?: return
                    val name = decodeJsonMemberName(text.substring(index, end)) ?: return
                    if (!keys.add(name)) {
                        throw SyncModelException("Duplicate JSON member name")
                    }
                }
                index = end
            }
            else -> index++
        }
    }
}

/** Returns the index just past the closing quote of the string at [start], or -1. */
private fun scanQuotedString(text: String, start: Int): Int {
    var index = start + 1
    while (index < text.length) {
        if (text[index] == '"') return index + 1
        index += if (text[index] == '\\') 2 else 1
    }
    return -1
}

/** True when the quoted value ending at [end] is followed by a colon after JSON whitespace. */
private fun isJsonMemberName(text: String, end: Int): Boolean {
    var index = end
    while (index < text.length) {
        when (text[index]) {
            ' ', '\t', '\n', '\r' -> index++
            ':' -> return true
            else -> return false
        }
    }
    return false
}

/**
 * Decodes a quoted member-name literal with the shared strict JSON reader so
 * escape-equivalent spellings alias with exact JSON semantics. Returns null when
 * the literal is undecodable and leaves the verdict to the strict body parser.
 */
private fun decodeJsonMemberName(literal: String): String? {
    return try {
        (Json.parseToJsonElement(literal) as? JsonPrimitive)?.content
    } catch (_: IllegalArgumentException) {
        null
    }
}
