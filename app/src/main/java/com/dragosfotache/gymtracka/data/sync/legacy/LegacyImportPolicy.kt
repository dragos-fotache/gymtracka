package com.dragosfotache.gymtracka.data.sync.legacy

/**
 * R13b opt-in private import policy. Construction performs no I/O.
 * Null policy on the facade retains the accepted legacy-refusal policy.
 * Displays are fixed/redacted.
 */
internal fun interface LegacyImportReplacementConsent {
    suspend fun confirm(request: LegacyImportReplacementRequest): Boolean
}

internal data class LegacyImportReplacementRequest(
    val target: LegacyImportTarget,
    val mutationId: String,
) {
    override fun toString(): String = "LegacyImportReplacementRequest(redacted)"
}

internal class LegacyImportPolicy(
    val replacementConsent: LegacyImportReplacementConsent,
) {
    override fun toString(): String = "LegacyImportPolicy(redacted)"
}

internal enum class LegacyImportProgressPhase { BLOCKED, CAPTURED, SETTLED }

internal data class LegacyImportProgress(
    val phase: LegacyImportProgressPhase,
    val importCount: Long,
    val acceptedCount: Long,
    val activeReplacementRequired: Boolean,
    val activeReplacementConsented: Boolean,
) {
    override fun toString(): String = "LegacyImportProgress(redacted)"
}
