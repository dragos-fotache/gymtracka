package com.dragosfotache.gymtracka.ui.util

/**
 * same-ID remembered-field reconciliation for projection refresh.
 *
 * Raw accepted text is not identical to its parsed persistence representation. When
 * incoming weight/repetitions equal the currently accepted draft's sanitized parsed
 * value, the raw text is retained (for example `12.00` or incomplete input that
 * stores null). A genuinely different persisted value replaces stale text; focus
 * nodes are never recreated by these helpers (callers must not re-key fields by
 * epoch). String name/notes reconcile exactly; rest displays reconcile to the
 * committed integer. Generation/drain guards in the owning state prevent old pending
 * snapshots from masquerading as new persisted values.
 */

internal fun reconcileWeightText(currentText: String, incomingKg: Double?): String {
    // Compare sanitized persistence values: negative or nonfinite raw input
    // sanitizes to null on write, so an accepted invalid/incomplete draft is
    // retained when the persisted value is equally null. A genuinely different
    // persisted value replaces stale text.
    val sanitizedCurrent = parseKilograms(currentText)?.takeIf { it >= 0.0 && it.isFinite() }
    if (sanitizedCurrent == incomingKg) return currentText
    return incomingKg?.let(::formatKilograms).orEmpty()
}

internal fun reconcileRepetitionsText(currentText: String, incomingRepetitions: Int?): String {
    // Negative raw input sanitizes to null on write; retain the accepted draft
    // when the persisted value is equally null.
    val sanitizedCurrent = currentText.toIntOrNull()?.takeIf { it >= 0 }
    if (sanitizedCurrent == incomingRepetitions) return currentText
    return incomingRepetitions?.toString().orEmpty()
}

internal fun reconcileStringField(currentText: String, incoming: String): String {
    if (currentText == incoming) return currentText
    return incoming
}

/**
 * Prunes selected picker IDs only when the latest complete exercise list proves
 * they no longer exist. A rename keeps the same ID and therefore preserves
 * selection.
 */
internal fun pruneMissingSelectedIds(
    selectedIds: Set<String>,
    existingIds: Set<String>,
): Set<String> = selectedIds.intersect(existingIds)
