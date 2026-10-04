package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared strict service-error boundary for HTTP sync/auth adapters. The error shape
 * is a required nonempty `errorCode` JSON string plus an optional absent/null/string
 * `message` that is validated and then discarded; presentation text is never a
 * machine-readable contract and unknown codes are retained exactly. Other response
 * fields are additive and ignored, but their raw grammar and duplicates still
 * validate. This reader never infers recovery from flags and never echoes unknown
 * fields, codes or input. Failures are fixed text with no cause.
 */

/** Strict service error carrying only the exact nonempty error code. */
internal class ServiceError(val errorCode: String) {
    init {
        if (errorCode.isEmpty()) {
            throw SyncModelException("Invalid service error")
        }
    }

    override fun toString(): String = "ServiceError(redacted)"
}

/** Decodes a strict `{errorCode, message?}` envelope, discarding the message. */
internal fun decodeServiceError(jsonText: String): ServiceError {
    try {
        val raw = parseServiceResponseObject(jsonText)
        if (!raw.has("errorCode") || raw.isNull("errorCode")) {
            throw SyncModelException("Invalid service error")
        }
        val code = raw.get("errorCode")
        if (code !is String) {
            throw SyncModelException("Invalid service error")
        }
        if (code.isEmpty()) {
            throw SyncModelException("Invalid service error")
        }
        if (raw.has("message") && !raw.isNull("message") && raw.get("message") !is String) {
            throw SyncModelException("Invalid service error")
        }
        return ServiceError(code)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SyncModelException) {
        throw error
    } catch (error: Exception) {
        throw SyncModelException("Invalid service error")
    }
}

/**
 * Strict HTTP-only service response entry point. Reuses the auth scalar grammar and
 * the shared duplicate-checked object parser, then verifies that ORIGINAL JSON
 * scalar kinds survived into the resulting tree: string stays String, boolean stays
 * Boolean, null stays null, number stays Number and objects/arrays keep their kinds.
 * This type-fidelity check (not domain validation, not a new parser) stops numeric
 * overflow fallbacks such as an unquoted `1e400` decoded as String from satisfying
 * a string field. No coercion or repair is applied; valid escaped opaque data passes
 * through unchanged. Parser or tree failures are fixed text without raw causes.
 */
internal fun parseServiceResponseObject(jsonText: String): JSONObject {
    try {
        requireAuthJsonScalars(jsonText)
        val element = try {
            Json.parseToJsonElement(jsonText)
        } catch (_: IllegalArgumentException) {
            throw SyncModelException("Body is not valid JSON")
        }
        if (element !is JsonObject) {
            throw SyncModelException("Body must be a JSON object")
        }
        val json = parseStrictJsonObject(jsonText)
        verifyOriginalScalarKinds(element, json)
        return json
    } catch (error: CancellationException) {
        throw error
    } catch (error: SyncModelException) {
        throw error
    } catch (error: Exception) {
        throw SyncModelException("Body is not valid JSON")
    }
}

/**
 * Iterative work-stack comparison of the strict original typed tree against the
 * decoded tree. Any kind mismatch, missing or extra member, length disagreement or
 * nonfinite decoded Double/Float fails with fixed text. Wide finite numbers stay
 * valid and are left to strict typed readers; there is no new number-magnitude cap.
 * No recursion, no depth or length cap, no normalization.
 */
private fun verifyOriginalScalarKinds(root: JsonObject, json: JSONObject) {
    if (json.length() != root.size) {
        throw SyncModelException("Body is not valid JSON")
    }
    val stack = ArrayDeque<Pair<JsonElement, Any?>>()
    for ((key, child) in root) {
        if (!json.has(key)) {
            throw SyncModelException("Body is not valid JSON")
        }
        stack.addLast(Pair(child, json.get(key)))
    }
    while (stack.isNotEmpty()) {
        val (element, value) = stack.removeLast()
        when {
            element is JsonObject -> {
                if (value !is JSONObject) {
                    throw SyncModelException("Body is not valid JSON")
                }
                if (value.length() != element.size) {
                    throw SyncModelException("Body is not valid JSON")
                }
                for ((key, child) in element) {
                    if (!value.has(key)) {
                        throw SyncModelException("Body is not valid JSON")
                    }
                    stack.addLast(Pair(child, value.get(key)))
                }
            }
            element is JsonArray -> {
                if (value !is JSONArray) {
                    throw SyncModelException("Body is not valid JSON")
                }
                if (value.length() != element.size) {
                    throw SyncModelException("Body is not valid JSON")
                }
                for (index in 0 until value.length()) {
                    stack.addLast(Pair(element[index], value.get(index)))
                }
            }
            element is JsonNull -> {
                if (value != JSONObject.NULL) {
                    throw SyncModelException("Body is not valid JSON")
                }
            }
            element is JsonPrimitive && element.isString -> {
                if (value !is String) {
                    throw SyncModelException("Body is not valid JSON")
                }
            }
            element is JsonPrimitive -> {
                val content = element.content
                if (content == "true" || content == "false") {
                    if (value !is Boolean) {
                        throw SyncModelException("Body is not valid JSON")
                    }
                } else {
                    if (value !is Number) {
                        throw SyncModelException("Body is not valid JSON")
                    }
                    if (value is Double && !value.isFinite()) {
                        throw SyncModelException("Body is not valid JSON")
                    }
                    if (value is Float && !value.isFinite()) {
                        throw SyncModelException("Body is not valid JSON")
                    }
                }
            }
        }
    }
}
