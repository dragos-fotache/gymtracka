package com.dragosfotache.gymtracka.data.sync

import org.json.JSONObject

/** Order-independent structural equality for JSON documents in transport tests. */
internal fun jsonValueEquals(expected: Any?, actual: Any?): Boolean {
    if (expected is JSONObject && actual is JSONObject) {
        if (expected.length() != actual.length()) return false
        return expected.keys().asSequence().all { key ->
            actual.has(key) && jsonValueEquals(expected.get(key), actual.get(key))
        }
    }
    if (expected is org.json.JSONArray && actual is org.json.JSONArray) {
        if (expected.length() != actual.length()) return false
        return (0 until expected.length()).all { jsonValueEquals(expected.get(it), actual.get(it)) }
    }
    if (expected === JSONObject.NULL || actual === JSONObject.NULL) return expected === actual
    if (expected is Number && actual is Number) return expected.toDouble() == actual.toDouble()
    return expected == actual
}
