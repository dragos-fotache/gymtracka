package com.dragosfotache.gymtracka.data.sync.model

/**
 * Shared aggregate shape helpers for the routine and custom-exercise models.
 * Workout logic keeps its own approved helpers; these cover the new aggregates.
 */

/** Rejects gaps, duplicates, and negative values; positions must be 0-based consecutive. */
internal fun checkConsecutivePositions(positions: List<Int>, where: String) {
    positions.forEachIndexed { index, position ->
        if (position != index) throw SyncModelException("$where have a position gap or duplicate")
    }
}

/** Nullable repetitions/weight must be non-negative and finite when present. */
internal fun requireNullableInputs(repetitions: Int?, weightKg: Double?, where: String) {
    if (repetitions != null && repetitions < 0) {
        throw SyncModelException("$where has negative repetitions")
    }
    if (weightKg != null && (weightKg < 0.0 || !weightKg.isFinite())) {
        throw SyncModelException("$where has invalid weightKg")
    }
}

internal fun requirePositiveRevision(revision: Long, where: String) {
    if (revision < 1) throw SyncModelException("$where revision must be positive")
}

/**
 * Four-digit RFC3339 year bounds shared by model validators and TimeFormat.
 * Model validation stays independent of JSON and Room: direct Long values outside
 * this range are rejected even though they pass every other shape check.
 */
const val MIN_WIRE_MILLIS = -62_167_219_200_000L
const val MAX_WIRE_MILLIS = 253_402_300_799_999L

internal fun requireWireMillis(millis: Long, field: String): Long {
    if (millis < MIN_WIRE_MILLIS || millis > MAX_WIRE_MILLIS) {
        throw SyncModelException("$field is outside the RFC3339 four-digit year range")
    }
    return millis
}
