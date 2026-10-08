package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityFailureReason
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid

/**
 * R11b foreground sign-in intent. INITIAL_SIGN_IN admits only an empty
 * signed-out observation; SAME_ACCOUNT_REAUTHENTICATION admits only an
 * observation retaining a cached account. Displays are fixed.
 */
internal enum class ForegroundSignInIntent {
    INITIAL_SIGN_IN,
    SAME_ACCOUNT_REAUTHENTICATION,
}

/**
 * Opaque exact owner/process authority binding for one captured foreground
 * request. Created only by the facade capture factory under its state
 * monitor; never serialized, saved or treated as durable authority.
 * Displays are fixed and redacted.
 */
internal class ForegroundSignInAuthorityBinding(
    val owner: Any,
    val fence: Any,
) {
    override fun toString(): String = "ForegroundSignInAuthorityBinding(redacted)"
}

/**
 * One foreground sign-in request: the asserted service origin, the sign-in
 * intent, the exact expected Room observation and the canonical operation
 * UUID. Construction is inert: it performs no Room/vault reads, launches no
 * Google UI, calls no HTTP and changes no observable authority. The optional
 * hidden [authorityBinding] is attached only through the hidden facade
 * factory at construction; a naked four-argument request is usable only with test handoffs, never
 * accepted by the real facade. Requests are foreground-memory-only and must
 * never be serialized, saved or treated as durable/reusable authority.
 * Displays are fixed and redacted.
 */
internal class ForegroundSignInRequest private constructor(
    val origin: ServiceOrigin,
    val intent: ForegroundSignInIntent,
    val expected: RecoveryRoomSnapshot,
    val operationId: String,
    internal val authorityBinding: ForegroundSignInAuthorityBinding?,
) {
    /**
     * Plain four-argument construction for test handoffs; leaves the hidden
     * opaque binding null. The real facade never accepts a naked request.
     */
    internal constructor(
        origin: ServiceOrigin,
        intent: ForegroundSignInIntent,
        expected: RecoveryRoomSnapshot,
        operationId: String,
    ) : this(origin, intent, expected, operationId, null)

    companion object {
        /**
         * Hidden facade binding factory: attaches the opaque owner/process
         * fence immutably at construction. Only the facade capture factory
         * uses this; the binding is never externally mutable and the request
         * is never serialized, saved or treated as durable authority.
         */
        internal fun bound(
            origin: ServiceOrigin,
            intent: ForegroundSignInIntent,
            expected: RecoveryRoomSnapshot,
            operationId: String,
            binding: ForegroundSignInAuthorityBinding,
        ): ForegroundSignInRequest =
            ForegroundSignInRequest(origin, intent, expected, operationId, binding)
    }

    override fun toString(): String = "ForegroundSignInRequest(redacted)"
}

/**
 * Private foreground grant handoff: consumes one service grant obtained
 * through the foreground exchange into existing guarded private preparation.
 * Returns private preparation, never a freely reusable grant or ACTIVE
 * account authority. Implemented by the production facade; test doubles
 * supply explicit scripted results.
 */
internal fun interface ForegroundGrantHandoff {
    suspend fun acceptForegroundGrant(
        request: ForegroundSignInRequest,
        session: ServiceSession,
    ): AppSessionState
}

/** Closed foreground sign-in failure categories. Fixed displays only. */
internal enum class ForegroundSignInFailure {
    CONFIGURATION_UNAVAILABLE,
    INVALID_REQUEST,
    BUSY,
    RETIRED,
    TIMED_OUT,
    CHALLENGE_EXPIRED,
    IDENTITY_FAILED,
    TRANSIENT_FAILURE,
    RETRY_EXHAUSTED,
    REQUEST_REJECTED,
    MALFORMED_RESPONSE,
    UNAVAILABLE,
}

/**
 * Closed foreground sign-in refusal with a fixed reason. Fixed message, no
 * cause and no suppressed entries, so no account, origin, operation, token,
 * nonce, request body, audience or raw error escapes. [identityReason] is set
 * only for a classified identity failure. Displays are fixed/redacted.
 */
internal class ForegroundSignInException(
    val reason: ForegroundSignInFailure,
    val identityReason: GoogleIdentityFailureReason? = null,
) : RuntimeException(fixedMessage(reason), null, false, true) {
    companion object {
        private fun fixedMessage(reason: ForegroundSignInFailure): String =
            when (reason) {
                ForegroundSignInFailure.CONFIGURATION_UNAVAILABLE ->
                    "Foreground sign-in is not configured"
                ForegroundSignInFailure.INVALID_REQUEST ->
                    "Foreground sign-in request is invalid"
                ForegroundSignInFailure.BUSY ->
                    "Foreground sign-in is already in progress"
                ForegroundSignInFailure.RETIRED ->
                    "Foreground sign-in is no longer available"
                ForegroundSignInFailure.TIMED_OUT ->
                    "Foreground sign-in timed out"
                ForegroundSignInFailure.CHALLENGE_EXPIRED ->
                    "Foreground sign-in challenge expired"
                ForegroundSignInFailure.IDENTITY_FAILED ->
                    "Foreground sign-in identity failed"
                ForegroundSignInFailure.TRANSIENT_FAILURE ->
                    "Foreground sign-in transient failure"
                ForegroundSignInFailure.RETRY_EXHAUSTED ->
                    "Foreground sign-in retries exhausted"
                ForegroundSignInFailure.REQUEST_REJECTED ->
                    "Foreground sign-in request rejected"
                ForegroundSignInFailure.MALFORMED_RESPONSE ->
                    "Foreground sign-in malformed response"
                ForegroundSignInFailure.UNAVAILABLE ->
                    "Foreground sign-in is unavailable"
            }
    }

    override fun toString(): String = "ForegroundSignInException(reason=$reason)"
}

/**
 * Fixed-text marker for invalid foreground intent/context/operation input.
 * Pure validation control flow: the facade maps it to IDENTITY_MISMATCH and
 * the foreground orchestrator maps it to INVALID_REQUEST. No supplied values
 * escape.
 */
internal class ForegroundSignInContextInvalidException :
    IllegalArgumentException("Foreground sign-in context is invalid") {
    override fun toString(): String = "ForegroundSignInContextInvalidException"
}

/**
 * Validates one foreground intent/context/operation tuple against the
 * facade-configured origin without any Room/vault/Google/HTTP I/O and
 * returns the canonical operation UUID.
 *
 * INITIAL_SIGN_IN requires SIGNED_OUT with no cached account or identity.
 * SAME_ACCOUNT_REAUTHENTICATION requires a cached account; any retained
 * identity must already match the configured origin. PREPARING and
 * TRANSITIONING require exactly the reserved identity operation; ACTIVE
 * requires a fresh different operation; SIGNED_OUT admits the
 * caller-supplied canonical operation. Any violation throws the fixed marker;
 * callers map it to their closed refusal.
 */
internal fun canonicalForegroundOperation(
    configured: ServiceOrigin,
    intent: ForegroundSignInIntent,
    expected: RecoveryRoomSnapshot,
    operationId: String,
): String {
    val canonical = try {
        requireWorkoutUuid(operationId, "id")
    } catch (_: Exception) {
        throw ForegroundSignInContextInvalidException()
    }
    when (intent) {
        ForegroundSignInIntent.INITIAL_SIGN_IN -> {
            if (expected.phase != RecoveryRoomPhase.SIGNED_OUT ||
                expected.cachedAccountId != null ||
                expected.identity != null
            ) {
                throw ForegroundSignInContextInvalidException()
            }
        }
        ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION -> {
            if (expected.cachedAccountId == null) {
                throw ForegroundSignInContextInvalidException()
            }
            val identity = expected.identity
            if (identity != null && identity.binding.origin != configured) {
                throw ForegroundSignInContextInvalidException()
            }
        }
    }
    when (expected.phase) {
        RecoveryRoomPhase.SIGNED_OUT -> {
            // Caller-supplied canonical operation, validated above.
        }
        RecoveryRoomPhase.PREPARING, RecoveryRoomPhase.TRANSITIONING -> {
            val reserved = expected.identity?.operationId
                ?: throw ForegroundSignInContextInvalidException()
            if (reserved != canonical) {
                throw ForegroundSignInContextInvalidException()
            }
        }
        RecoveryRoomPhase.ACTIVE -> {
            val active = expected.identity?.operationId
                ?: throw ForegroundSignInContextInvalidException()
            if (active == canonical) {
                throw ForegroundSignInContextInvalidException()
            }
        }
    }
    return canonical
}
