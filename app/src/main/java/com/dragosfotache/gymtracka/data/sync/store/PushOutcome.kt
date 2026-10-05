package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationOutcome
import com.dragosfotache.gymtracka.data.sync.codec.encodeMutationOutcome
import com.dragosfotache.gymtracka.data.sync.model.MutationOutcomeModel

/** Fixed safe persistence for a permanently rejected push mutation. */
internal const val PUSH_REJECTED_MESSAGE = "Push mutation was rejected; recovery required."

/** Fixed safe persistence for an invalid push response. */
internal const val PUSH_MALFORMED_MESSAGE = "Push response was invalid; recovery required."

/** Fixed safe persistence for an oversized push request. */
internal const val PUSH_TOO_LARGE_MESSAGE = "Push request was too large; recovery required."

/** Fixed safe persistence for a permanently rejected push request. */
internal const val PUSH_REQUEST_REJECTED_MESSAGE = "Push request was rejected; recovery required."

/** Fixed safe persistence when a retry time cannot be represented. */
internal const val PUSH_RETRY_EXHAUSTED_MESSAGE = "Push retry time was exhausted; recovery required."

/** Retained code for invalid push responses. */
internal const val MALFORMED_RESPONSE_CODE = "malformed_response"

/** Retained code for oversized push requests. */
internal const val REQUEST_TOO_LARGE_CODE = "request_too_large"

/** Retained code when a retry time cannot be represented. */
internal const val RETRY_TIME_EXHAUSTED_CODE = "retry_time_exhausted"

/**
 * Classified outcome of one claimed push response. Accepted snapshots are owned
 * and read-only; they never authorize acknowledgment. Rejected codes preserve the
 * exact service-issued value without allowlisting. String forms never print
 * batches, UUIDs, payloads or service-issued error-code values.
 */
sealed class PushOutcomeResult {
    /**
     * Valid complete single-head outcome preserving its status and whole batch.
     * The snapshot is privately owned; every [outcome] access returns a fresh
     * defensive deep copy so mutating any getter result cannot change retained
     * state or a later getter.
     */
    class Accepted internal constructor(
        private val snapshot: MutationOutcomeModel,
    ) : PushOutcomeResult() {
        val outcome: MutationOutcomeModel
            get() = decodeMutationOutcome(encodeMutationOutcome(snapshot))

        override fun toString(): String = "Accepted"
    }

    /** Permanent per-mutation rejection carrying the exact service code. */
    data class Rejected(val errorCode: String) : PushOutcomeResult() {
        override fun toString(): String = "Rejected"
    }

    /** Invalid response retained as a safe frozen error. */
    data object Malformed : PushOutcomeResult()
}

/**
 * Classified outcome of one claimed push transport failure. Retry scheduling is
 * absolute and bounded; retained codes preserve the exact service value.
 * String forms never print batches, UUIDs, payloads or service-issued
 * error-code values.
 */
sealed class PushFailureResult {
    /** Transient fault with an absolute due time. */
    data class RetryScheduled(val retryAfterMillis: Long) : PushFailureResult()

    /** Retained permanent failure carrying the exact service code. */
    data class Retained(val errorCode: String) : PushFailureResult() {
        override fun toString(): String = "Retained"
    }

    /** Service access authentication failure; read-only, resumable. */
    data object AuthenticationRequired : PushFailureResult()

    /** Foreground reauthentication failure; read-only, never poisons outbox. */
    data object ReauthenticationRequired : PushFailureResult()
}
