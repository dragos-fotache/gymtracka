package com.dragosfotache.gymtracka.data.gym

import java.util.Calendar
import java.util.TimeZone

internal fun defaultWorkoutName(
    startedAtMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val hour = Calendar.getInstance(timeZone).run {
        timeInMillis = startedAtMillis
        get(Calendar.HOUR_OF_DAY)
    }
    val timeOfDay = when (hour) {
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Night"
    }
    return "$timeOfDay Workout"
}
