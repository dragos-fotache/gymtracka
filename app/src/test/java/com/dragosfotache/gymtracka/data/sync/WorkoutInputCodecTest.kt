package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.codec.encodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Active-input codec behavior: nulls, empties, escaping, and forbidden wire fields. */
class WorkoutInputCodecTest {

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun inputWithContent(): WorkoutInputModel {
        val firstExercise = uuid()
        val secondExercise = uuid()
        return WorkoutInputModel(
            id = uuid(),
            name = "Morning Workout",
            status = "active",
            startedAtMillis = 1_780_000_000_000L,
            exercises = listOf(
                WorkoutExerciseInputModel(
                    id = firstExercise,
                    exerciseId = uuid(),
                    position = 0,
                    notes = "",
                    restDurationSeconds = 0,
                    sets = listOf(
                        WorkoutSetModel(uuid(), 0, 10, 60.0, true, 61),
                        WorkoutSetModel(uuid(), 1, null, null, false, null),
                        WorkoutSetModel(uuid(), 2, 0, 0.0, false, 0),
                    ),
                ),
                WorkoutExerciseInputModel(
                    id = secondExercise,
                    exerciseId = uuid(),
                    position = 1,
                    notes = "Ünïcodé \"quotes\"\nnewline\ttab \\ slash",
                    restDurationSeconds = 90,
                    sets = listOf(
                        WorkoutSetModel(uuid(), 0, null, 20.5, false, null),
                    ),
                ),
            ),
        )
    }

    @Test
    fun activeInput_roundTripsNullsZerosAndUnicode() {
        val input = inputWithContent()
        assertEquals(input, decodeWorkoutInput(encodeWorkoutInput(input).toString()))
    }

    @Test
    fun activeInput_encodesNullsExplicitlyAndOmitsForbiddenFields() {
        val encoded = encodeWorkoutInput(inputWithContent())
        val sets = encoded.getJSONArray("exercises").getJSONObject(0).getJSONArray("sets")

        val incomplete = sets.getJSONObject(1)
        assertTrue(incomplete.isNull("repetitions"))
        assertTrue(incomplete.isNull("weightKg"))
        assertTrue(incomplete.isNull("restSeconds"))
        assertEquals(false, incomplete.getBoolean("isCompleted"))

        val zeroed = sets.getJSONObject(2)
        assertEquals(0, zeroed.getInt("repetitions"))
        assertEquals(0.0, zeroed.getDouble("weightKg"), 0.0)

        assertTrue(!encoded.has("revision"))
        assertTrue(!encoded.has("finishedAt"))
        assertTrue(!encoded.has("totalVolumeKg"))
        assertTrue(!encoded.has("accountId"))
        assertTrue(!encoded.has("localGeneration"))
        val exercise = encoded.getJSONArray("exercises").getJSONObject(0)
        assertTrue(!exercise.has("exerciseName"))
        assertTrue(!exercise.has("revision"))
    }

    @Test
    fun activeInput_missingNullableDecodesAsNullAndEmptyWorkoutIsValid() {
        val sparse = WorkoutInputModel(
            id = uuid(),
            name = "Night Workout",
            status = "active",
            startedAtMillis = 1_780_000_000_000L,
            exercises = emptyList(),
        )
        assertEquals(sparse, decodeWorkoutInput(encodeWorkoutInput(sparse).toString()))

        // Nullable fields may be absent entirely, not just explicit null.
        val encoded = encodeWorkoutInput(inputWithContent())
        val incomplete = encoded.getJSONArray("exercises").getJSONObject(0)
            .getJSONArray("sets").getJSONObject(1)
        incomplete.remove("repetitions")
        incomplete.remove("weightKg")
        incomplete.remove("restSeconds")
        val decoded = decodeWorkoutInput(encoded.toString())
        assertNull(decoded.exercises[0].sets[1].repetitions)
        assertNull(decoded.exercises[0].sets[1].weightKg)
        assertNull(decoded.exercises[0].sets[1].restSeconds)
    }
}
