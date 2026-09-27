package com.dragosfotache.gymtracka.data.gym

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutNamingTest {
    @Test
    fun defaultName_usesDocumentedHourBoundaries() {
        val zone = ZoneId.of("Europe/Bucharest")
        val timeZone = TimeZone.getTimeZone(zone)
        val date = LocalDate.of(2026, 1, 15)

        assertEquals("Night Workout", defaultWorkoutName(date.atTime(4, 59).millis(zone), timeZone))
        assertEquals("Morning Workout", defaultWorkoutName(date.atTime(5, 0).millis(zone), timeZone))
        assertEquals("Morning Workout", defaultWorkoutName(date.atTime(11, 59).millis(zone), timeZone))
        assertEquals("Afternoon Workout", defaultWorkoutName(date.atTime(12, 0).millis(zone), timeZone))
        assertEquals("Afternoon Workout", defaultWorkoutName(date.atTime(16, 59).millis(zone), timeZone))
        assertEquals("Evening Workout", defaultWorkoutName(date.atTime(17, 0).millis(zone), timeZone))
        assertEquals("Evening Workout", defaultWorkoutName(date.atTime(20, 59).millis(zone), timeZone))
        assertEquals("Night Workout", defaultWorkoutName(date.atTime(21, 0).millis(zone), timeZone))
    }

    @Test
    fun defaultName_respectsExplicitTimeZone() {
        val instant = LocalDateTime.of(2026, 1, 15, 6, 0)
            .atZone(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

        assertEquals("Morning Workout", defaultWorkoutName(instant, TimeZone.getTimeZone("UTC")))
        assertEquals(
            "Night Workout",
            defaultWorkoutName(instant, TimeZone.getTimeZone("America/Los_Angeles")),
        )
    }

    private fun LocalDateTime.millis(zoneId: ZoneId): Long =
        atZone(zoneId).toInstant().toEpochMilli()
}
