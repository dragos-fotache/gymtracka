package com.dragosfotache.gymtracka.data.sync.api

import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.codec.requireAuthJsonScalars
import com.dragosfotache.gymtracka.data.sync.codec.requiredString
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import org.json.JSONObject

/**
 * Purpose-bound Google challenge selection for POST /v1/auth/google/challenge.
 * The wire values match OpenAPI exactly; matching is case-sensitive and no
 * unknown purpose is ever defaulted or remapped.
 */
enum class GoogleChallengePurpose(val wireValue: String) {
    SIGN_IN("signIn"),
    EXPORT("export"),
    DELETE_ACCOUNT("deleteAccount"),
    ACCOUNT_STATUS("accountStatus"),
    ;

    companion object {
        fun fromWire(value: String): GoogleChallengePurpose =
            entries.singleOrNull { it.wireValue == value }
                ?: throw SyncModelException("Unknown challenge purpose")
    }
}

/**
 * Validates a caller-supplied idempotency key as an exact lowercase
 * 36-character wire UUID. The key is header data and is never encoded into a
 * request body. Failures carry fixed text without echoing the supplied key.
 */
private fun requireAttemptKey(value: String): String {
    try {
        requireWorkoutUuid(value, "idempotencyKey")
    } catch (_: SyncModelException) {
        throw SyncModelException("Invalid idempotency key")
    }
    return value
}

private fun requireAttemptChallengeId(value: String): String {
    try {
        requireWorkoutUuid(value, "challengeId")
    } catch (_: SyncModelException) {
        throw SyncModelException("Invalid challengeId")
    }
    return value
}

/** Parses and validates an exchange body, returning its exact parsed fields. */
private fun parseExchangeBody(requestJson: String): Pair<String, OpaqueToken> {
    requireAuthJsonScalars(requestJson)
    val raw = parseStrictJsonObject(requestJson)
    if (raw.keys().asSequence().toSet() != setOf("challengeId", "idToken")) {
        throw SyncModelException("Invalid exchange request")
    }
    val challengeId = requireAttemptChallengeId(raw.requiredString("challengeId"))
    val tokenText = raw.requiredString("idToken")
    if (tokenText.isEmpty()) throw SyncModelException("Invalid idToken")
    return challengeId to OpaqueToken(tokenText)
}

/** Re-parses the retained body so constructors cannot bypass validation. */
private fun requireExchangeBodyMatches(
    requestJson: String,
    challengeId: String,
    idToken: OpaqueToken,
) {
    val parsed = parseExchangeBody(requestJson)
    if (parsed.first != challengeId || parsed.second != idToken) {
        throw SyncModelException("Invalid exchange request")
    }
}

/** Parses and validates a refresh body, returning its exact parsed token. */
private fun parseRefreshBody(requestJson: String): OpaqueToken {
    requireAuthJsonScalars(requestJson)
    val raw = parseStrictJsonObject(requestJson)
    if (raw.keys().asSequence().toSet() != setOf("refreshToken")) {
        throw SyncModelException("Invalid refresh request")
    }
    val tokenText = raw.requiredString("refreshToken")
    if (tokenText.isEmpty()) throw SyncModelException("Invalid refreshToken")
    return OpaqueToken(tokenText)
}

/** Re-parses the retained body so constructors cannot bypass validation. */
private fun requireRefreshBodyMatches(requestJson: String, refreshToken: OpaqueToken) {
    if (parseRefreshBody(requestJson) != refreshToken) {
        throw SyncModelException("Invalid refresh request")
    }
}

/**
 * Immutable foreground-memory-only Google exchange attempt. The validated
 * original [requestJson] is retained unchanged and adapters use that property
 * as the complete UTF-8 body, never reserialized parsed fields. The
 * idempotency key is header data, never a JSON body field. [toString] is
 * fixed redacted text; [requestJson] and token getters are sensitive
 * transport inputs, not logging/display APIs.
 */
class GoogleExchangeAttempt private constructor(
    val idempotencyKey: String,
    val requestJson: String,
    val challengeId: String,
    val idToken: OpaqueToken,
) {
    init {
        requireAttemptKey(idempotencyKey)
        requireExchangeBodyMatches(requestJson, challengeId, idToken)
    }

    override fun toString(): String = "GoogleExchangeAttempt(redacted)"

    companion object {
        fun capture(
            idempotencyKey: String,
            challengeId: String,
            idToken: OpaqueToken,
        ): GoogleExchangeAttempt {
            val key = requireAttemptKey(idempotencyKey)
            val validatedChallengeId = requireAttemptChallengeId(challengeId)
            val body = JSONObject().apply {
                put("challengeId", validatedChallengeId)
                put("idToken", idToken.value)
            }.toString()
            return GoogleExchangeAttempt(key, body, validatedChallengeId, idToken)
        }

        fun fromJson(idempotencyKey: String, requestJson: String): GoogleExchangeAttempt {
            val key = requireAttemptKey(idempotencyKey)
            val parsed = parseExchangeBody(requestJson)
            return GoogleExchangeAttempt(key, requestJson, parsed.first, parsed.second)
        }
    }
}

/**
 * Immutable storage-neutral refresh attempt, input to encrypted-vault refresh persistence. This request type performs no storage I/O. The validated original [requestJson]
 * is retained unchanged and adapters use that property as the complete UTF-8
 * body, never reserialized parsed fields. The idempotency key is header data,
 * never a JSON body field. [toString] is fixed redacted text; [requestJson]
 * and token getters are sensitive transport inputs, not logging/display APIs.
 */
class ServiceRefreshAttempt private constructor(
    val idempotencyKey: String,
    val requestJson: String,
    val refreshToken: OpaqueToken,
) {
    init {
        requireAttemptKey(idempotencyKey)
        requireRefreshBodyMatches(requestJson, refreshToken)
    }

    override fun toString(): String = "ServiceRefreshAttempt(redacted)"

    companion object {
        fun capture(idempotencyKey: String, refreshToken: OpaqueToken): ServiceRefreshAttempt {
            val key = requireAttemptKey(idempotencyKey)
            val body = JSONObject().apply {
                put("refreshToken", refreshToken.value)
            }.toString()
            return ServiceRefreshAttempt(key, body, refreshToken)
        }

        fun fromJson(idempotencyKey: String, requestJson: String): ServiceRefreshAttempt {
            val key = requireAttemptKey(idempotencyKey)
            val parsed = parseRefreshBody(requestJson)
            return ServiceRefreshAttempt(key, requestJson, parsed)
        }
    }
}
