package com.dragosfotache.gymtracka.data.sync.legacy

import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease

/**
 * Service-confirmed import target for the inert legacy storage seam.
 * The target conveys already service-confirmed identity; it is NOT a
 * visibility or network grant. R13b must derive it from accepted
 * fresh-service evidence under existing process/vault fences.
 */
internal data class LegacyImportTarget(
    val origin: ServiceOrigin,
    val accountId: String,
    val operationId: String,
) {
    init {
        try {
            requireWorkoutUuid(accountId, "accountId")
            requireWorkoutUuid(operationId, "operationId")
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
    }

    override fun toString(): String = "LegacyImportTarget(redacted)"
}

/** Future production admission/drain barrier for explicit caller-owned private work. */
internal fun interface LegacyImportCaptureBarrier {
    suspend fun acquire(expected: RecoveryRoomSnapshot): ProjectionPublicationLease
}

internal enum class LegacyImportPhase { CAPTURED, BLOCKED, SETTLED }

internal data class LegacyImportEvidence(
    val target: LegacyImportTarget,
    val phase: LegacyImportPhase,
    val room: RecoveryRoomSnapshot,
    val sourceJson: String,
    val importCount: Long,
    val errorCode: String?,
    val acceptedCount: Long = 0L,
) {
    override fun toString(): String = "LegacyImportEvidence(redacted)"
}

internal sealed interface LegacyImportCaptureResult {
    data object NoLocalData : LegacyImportCaptureResult {
        override fun toString(): String = "NoLocalData"
    }
    data class Retained(val evidence: LegacyImportEvidence) : LegacyImportCaptureResult {
        override fun toString(): String = "Retained(redacted)"
    }
}

internal enum class LegacyImportFailure {
    INVALID_INPUT,
    STALE_AUTHORITY,
    TARGET_MISMATCH,
    FOREIGN_STORAGE,
    CORRUPT_EVIDENCE,
    COUNTER_OVERFLOW,
    UNAVAILABLE,
}

/**
 * Closed legacy-import failure. Fixed message, no cause/suppression/domain
 * text; only the closed [failure] category escapes.
 */
internal class LegacyImportException(val failure: LegacyImportFailure) :
    RuntimeException("Legacy import failure", null, false, true) {
    override fun toString(): String = "LegacyImportException(failure=$failure)"
}
