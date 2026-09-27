package com.dragosfotache.gymtracka.ui.util

import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseHistorySummary
import com.dragosfotache.gymtracka.data.gym.WorkoutHistorySummary
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

internal fun WorkoutDetails.totalVolumeKg(): String {
    val volume = exercises.sumOf { exercise ->
        exercise.sets.sumOf { set -> (set.weightKg ?: 0.0) * (set.repetitions ?: 0) }
    }
    return formatTotalVolumeKg(volume)
}

internal fun WorkoutHistorySummary.totalVolumeKg(): String =
    formatTotalVolumeKg(exercises.sumOf(WorkoutExerciseHistorySummary::volumeKg))

internal fun WorkoutExerciseDetails.bestSetText(): String {
    val bestSet = sets.maxByOrNull { set ->
        (set.weightKg ?: 0.0) * (set.repetitions ?: 0)
    } ?: return "—"
    val weight = bestSet.weightKg ?: return "—"
    val repetitions = bestSet.repetitions ?: return "—"
    return "${formatKilograms(weight)} kg × $repetitions"
}

internal fun WorkoutExerciseHistorySummary.bestSetText(): String {
    val weight = bestSetWeightKg ?: return "—"
    val repetitions = bestSetRepetitions ?: return "—"
    return "${formatKilograms(weight)} kg × $repetitions"
}

internal fun WorkoutSetEntity.historySetText(): String {
    val weight = weightKg ?: return "—"
    val repetitions = repetitions ?: return "—"
    return "${formatKilograms(weight)} kg × $repetitions"
}

internal fun formatKilograms(
    weightKg: Double,
    locale: Locale = Locale.getDefault(),
): String = NumberFormat.getNumberInstance(locale).apply {
    isGroupingUsed = false
    minimumFractionDigits = 0
    maximumFractionDigits = 2
}.format(weightKg)

internal fun parseKilograms(
    value: String,
    locale: Locale = Locale.getDefault(),
): Double? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val position = ParsePosition(0)
    val formatter = NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = false }
    val parsed = formatter.parse(trimmed, position) ?: return null
    return parsed.toDouble().takeIf { position.index == trimmed.length }
}

private fun formatTotalVolumeKg(volumeKg: Double): String = if (volumeKg % 1.0 == 0.0) {
    "${volumeKg.toInt()} kg"
} else {
    "${"%.2f".format(volumeKg)} kg"
}

internal fun formatWorkoutDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis.coerceAtLeast(0) / 60_000).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h ${minutes}m" else "${minutes}m"
}

internal fun formatElapsedTime(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

internal fun formatCountdown(totalSeconds: Int): String =
    "%02d:%02d".format(Locale.getDefault(), totalSeconds / 60, totalSeconds % 60)

internal fun formatRestDuration(totalSeconds: Int): String {
    if (totalSeconds == 0) return "off"
    return formatRecordedRestDuration(totalSeconds)
}

internal fun formatRecordedRestDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return buildList {
        if (minutes > 0) add("${minutes}min")
        add("${seconds}s")
    }.joinToString(" ")
}
