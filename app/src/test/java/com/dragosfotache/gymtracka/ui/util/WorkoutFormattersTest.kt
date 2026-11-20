package com.dragosfotache.gymtracka.ui.util

import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseWithName
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutFormattersTest {
    @Test
    fun workoutDuration_formatsMinutesAndHours() {
        assertEquals("0m", formatWorkoutDuration(-1))
        assertEquals("59m", formatWorkoutDuration(59 * 60_000L))
        assertEquals("1h 5m", formatWorkoutDuration(65 * 60_000L))
    }

    @Test
    fun elapsedTime_switchesToHourFormat() {
        assertEquals("00:09", formatElapsedTime(9))
        assertEquals("59:59", formatElapsedTime(3_599))
        assertEquals("01:00:00", formatElapsedTime(3_600))
    }

    @Test
    fun restDuration_formatsOffAndRecordedTime() {
        assertEquals("off", formatRestDuration(0))
        assertEquals("45s", formatRestDuration(45))
        assertEquals("2min", formatRestDuration(120))
        assertEquals("1min 30s", formatRestDuration(90))
        assertEquals("2min 0s", formatRecordedRestDuration(120))
        assertEquals("1min 15s", formatRecordedRestDuration(75))
    }

    @Test
    fun kilograms_formatAndParseWithTheProvidedLocale() {
        assertEquals("62.5", formatKilograms(62.5, Locale.US))
        assertEquals("62,5", formatKilograms(62.5, Locale.GERMANY))
        assertEquals(62.5, parseKilograms("62.5", Locale.US))
        assertEquals(62.5, parseKilograms("62,5", Locale.GERMANY))
        assertNull(parseKilograms("62,5 kg", Locale.GERMANY))
        assertNull(parseKilograms("", Locale.US))
    }

    @Test
    fun workoutCalculations_handleNullableValuesAndBestSetTies() {
        val first = WorkoutSetEntity("set-1", "entry", 0, 10, 10.0, true)
        val tied = WorkoutSetEntity("set-2", "entry", 1, 5, 20.0, true)
        val nullable = WorkoutSetEntity("set-3", "entry", 2, null, 100.0, true)
        val exercise = WorkoutExerciseDetails(
            workoutExercise = WorkoutExerciseWithName(
                id = "entry",
                workoutId = "workout",
                exerciseId = "exercise",
                position = 0,
                notes = "",
                restDurationSeconds = 30,
                exerciseName = "Exercise",
            ),
            sets = listOf(first, tied, nullable),
        )
        val details = WorkoutDetails(
            workout = WorkoutEntity("workout", "Workout", 0L),
            exercises = listOf(exercise),
        )

        assertEquals("200 kg", details.totalVolumeKg())
        assertEquals("10 kg × 10", exercise.bestSetText())
        assertEquals("—", nullable.historySetText())
    }
}
