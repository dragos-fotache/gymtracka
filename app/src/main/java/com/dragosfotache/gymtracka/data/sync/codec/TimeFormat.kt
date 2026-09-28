package com.dragosfotache.gymtracka.data.sync.codec

import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWireMillis
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * RFC 3339 UTC timestamps for the wire contract. Room stores epoch milliseconds;
 * the wire carries UTC `...Z` strings. Locale-independent (java.time only).
 * The shape check below rejects hour 24, leap second 60, and out-of-range
 * month/day values up front so Instant.parse can never silently normalize them
 * into a different timestamp.
 */
private val UTC_TIMESTAMP_SHAPE =
    Regex("""^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(\.\d+)?Z$""")

/** Millis bounds delegate to the shared model-level range guard. */
fun formatUtcMillis(millis: Long): String {
    requireWireMillis(millis, "timestamp")
    return Instant.ofEpochMilli(millis).toString()
}

/**
 * Parses a UTC RFC 3339 timestamp with `Z` (zero or fractional seconds accepted).
 * Rejects missing timezones, non-UTC offsets, invalid dates, and trailing junk.
 * Rejects a nonzero sub-millisecond component rather than silently losing precision;
 * trailing fractional zeroes are fine.
 */
fun parseUtcMillis(value: String): Long {
    if (!UTC_TIMESTAMP_SHAPE.matches(value)) {
        throw SyncModelException("Timestamp must be UTC RFC3339 with Z: $value")
    }
    val instant = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        throw SyncModelException("Invalid timestamp: $value")
    }
    if (instant.nano % 1_000_000 != 0) {
        throw SyncModelException("Timestamp has sub-millisecond precision: $value")
    }
    return instant.toEpochMilli()
}
