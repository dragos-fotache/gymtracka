package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import org.json.JSONObject

/**
 * Auth-only lexical guard for new auth request readers. The shared strict parser can
 * normalize arbitrary unquoted scalar tokens into strings, so these readers
 * first enforce JSON scalar grammar locally: quoted literals are skipped
 * opaquely (never reparsed), and outside them only JSON structural
 * punctuation/whitespace, true/false/null and RFC8259 numbers are permitted.
 * Full syntax/string/duplicate validation stays with the shared parser below;
 * this small iterative check only blocks unquoted arbitrary scalars (such as
 * NaN, foobar or bare signIn) from becoming credential/profile/purpose
 * strings. Failures carry fixed text without echoing the raw body.
 */
internal fun requireAuthJsonScalars(text: String) {
    val token = StringBuilder()
    fun flushToken() {
        if (token.isEmpty()) return
        val word = token.toString()
        token.clear()
        if (word != "true" && word != "false" && word != "null" && !AUTH_SCALAR_NUMBER.matches(word)) {
            throw SyncModelException("Body is not valid JSON")
        }
    }
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '"') {
            flushToken()
            index++
            var closed = false
            while (index < text.length) {
                val inner = text[index]
                if (inner == '\\') {
                    index++
                    if (index >= text.length) break
                    if (text[index] <= '\u001F') throw SyncModelException("Body is not valid JSON")
                    index++
                    continue
                }
                if (inner == '"') {
                    closed = true
                    index++
                    break
                }
                if (inner <= '\u001F') throw SyncModelException("Body is not valid JSON")
                index++
            }
            if (!closed) return
            continue
        }
        when {
            char == '{' || char == '}' || char == '[' || char == ']' || char == ':' || char == ',' ||
                char == ' ' || char == '\t' || char == '\n' || char == '\r' -> {
                flushToken()
                index++
            }
            char.isLetterOrDigit() || char == '-' || char == '+' || char == '.' -> {
                token.append(char)
                index++
            }
            else -> throw SyncModelException("Body is not valid JSON")
        }
    }
    flushToken()
}

private val AUTH_SCALAR_NUMBER =
    Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

private fun requireAuthUuid(value: String, field: String): String {
    try {
        requireWorkoutUuid(value, field)
    } catch (_: SyncModelException) {
        throw SyncModelException("Invalid $field")
    }
    return value
}

private fun requireAuthInstant(value: String, field: String): Long {
    try {
        return parseUtcMillis(stripAuthZeroFraction(value))
    } catch (_: SyncModelException) {
        throw SyncModelException("Invalid $field")
    }
}

/**
 * Strips only trailing zero fractional-second digits before delegating to the
 * shared strict UTC reader, so long valid zero tails (for example `.123000000000`)
 * keep their lossless millisecond value even though Instant.parse caps fractions
 * at nine digits. Fractions with nonzero sub-millisecond digits are left intact
 * for the shared reader to reject; TimeFormat itself is unchanged.
 */
private fun stripAuthZeroFraction(value: String): String {
    if (!value.endsWith("Z")) return value
    val dot = value.indexOf('.')
    val time = value.indexOf('T')
    if (dot < 0 || time < 0 || dot < time) return value
    val end = value.length - 1
    val fraction = value.substring(dot + 1, end)
    if (fraction.isEmpty() || !fraction.all { it in '0'..'9' }) return value
    var trim = end
    while (trim > dot + 1 && value[trim - 1] == '0') trim--
    if (trim == dot + 1) return value.substring(0, dot) + "Z"
    if (trim == end) return value
    return value.substring(0, trim) + "Z"
}

/**
 * Encodes a Google challenge request with exactly one explicit purpose field.
 * The default signIn purpose is encoded explicitly; object key
 * order/whitespace carries no canonicalization contract.
 */
fun encodeGoogleChallengeRequest(purpose: GoogleChallengePurpose = GoogleChallengePurpose.SIGN_IN): String =
    JSONObject().apply {
        put("purpose", purpose.wireValue)
    }.toString()

/**
 * Decodes a Google challenge request. `{}` defaults to SIGN_IN; otherwise only
 * the optional purpose field with an exact case-sensitive enum is accepted.
 * Null, wrong types, unknown enum values and unknown fields fail.
 */
fun decodeGoogleChallengeRequest(jsonText: String): GoogleChallengePurpose {
    requireAuthJsonScalars(jsonText)
    val raw = parseStrictJsonObject(jsonText)
    val keys = raw.keys().asSequence().toSet()
    if (keys.isEmpty()) return GoogleChallengePurpose.SIGN_IN
    if (keys != setOf("purpose")) throw SyncModelException("Unknown field in challenge request")
    if (raw.isNull("purpose")) throw SyncModelException("Field purpose must be a string")
    val value = raw.get("purpose")
    if (value !is String) throw SyncModelException("Field purpose must be a string")
    return GoogleChallengePurpose.fromWire(value)
}

/**
 * Decodes a challenge response with a required canonical lowercase UUID,
 * nonempty nonce string and UTC RFC3339 expiry mapped losslessly to millis.
 * Additive response fields are ignored but never bypass duplicate checking.
 */
fun decodeServiceChallenge(jsonText: String): ServiceChallenge {
    requireAuthJsonScalars(jsonText)
    val raw = parseStrictJsonObject(jsonText)
    val challengeId = requireAuthUuid(raw.requiredString("challengeId"), "challengeId")
    val nonce = raw.requiredString("nonce")
    if (nonce.isEmpty()) throw SyncModelException("Invalid nonce")
    val expiresAtMillis = requireAuthInstant(raw.requiredString("expiresAt"), "expiresAt")
    return ServiceChallenge(challengeId, nonce, expiresAtMillis)
}

/**
 * Decodes a service session response. The account object requires a canonical
 * lowercase id; optional/null displayName maps to null while blank/Unicode
 * values are preserved. Tokens must be nonempty strings and expiries UTC
 * RFC3339 strings. Additive response fields (including account additions) are
 * ignored but never bypass duplicate checking.
 */
fun decodeServiceSession(jsonText: String): ServiceSession {
    requireAuthJsonScalars(jsonText)
    val raw = parseStrictJsonObject(jsonText)
    if (!raw.has("account") || raw.isNull("account")) {
        throw SyncModelException("Missing required field: account")
    }
    val accountRaw = raw.get("account")
    if (accountRaw !is JSONObject) throw SyncModelException("Field account must be an object")
    val accountId = requireAuthUuid(accountRaw.requiredString("id"), "account id")
    val displayName = if (!accountRaw.has("displayName") || accountRaw.isNull("displayName")) {
        null
    } else {
        val name = accountRaw.get("displayName")
        if (name !is String) throw SyncModelException("Field displayName must be a string or null")
        name
    }
    val accessTokenText = raw.requiredString("accessToken")
    if (accessTokenText.isEmpty()) throw SyncModelException("Invalid accessToken")
    val accessExpiresAtMillis = requireAuthInstant(raw.requiredString("accessExpiresAt"), "accessExpiresAt")
    val refreshTokenText = raw.requiredString("refreshToken")
    if (refreshTokenText.isEmpty()) throw SyncModelException("Invalid refreshToken")
    val refreshExpiresAtMillis = requireAuthInstant(raw.requiredString("refreshExpiresAt"), "refreshExpiresAt")
    return ServiceSession(
        account = ServiceAccountRef(accountId, displayName),
        accessToken = OpaqueToken(accessTokenText),
        accessExpiresAtMillis = accessExpiresAtMillis,
        refreshToken = OpaqueToken(refreshTokenText),
        refreshExpiresAtMillis = refreshExpiresAtMillis,
    )
}

/**
 * Encodes a logout request with exactly the required refreshToken field.
 * Logout carries no idempotency key or attempt.
 */
fun encodeLogoutRequest(refreshToken: OpaqueToken): String =
    JSONObject().apply {
        put("refreshToken", refreshToken.value)
    }.toString()
