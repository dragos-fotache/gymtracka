package com.dragosfotache.gymtracka.ui.exercises

import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseEquipmentRow
import com.dragosfotache.gymtracka.data.gym.ExerciseMuscleGroupRow
import com.dragosfotache.gymtracka.data.gym.ExerciseSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseFilteringTest {
    private val summary = ExerciseSummary(
        exercise = ExerciseEntity(
            id = "bench-press",
            name = "Bench Press",
            type = "reps",
            difficultyLevel = null,
            forceType = null,
            mechanics = null,
            category = "strength",
            instructions = null,
        ),
        muscleGroups = listOf(
            ExerciseMuscleGroupRow("bench-press", "chest", "chest", "Chest", "primary"),
            ExerciseMuscleGroupRow("bench-press", "triceps", "triceps", "Triceps", "secondary"),
        ),
        equipment = listOf(
            ExerciseEquipmentRow("bench-press", "barbell", "Barbell", "weight", null, 1),
            ExerciseEquipmentRow("bench-press", "bench", "Bench", "support", null, 1),
        ),
    )

    @Test
    fun search_isTrimmedCaseInsensitiveAndCoversAllDisplayedMetadata() {
        assertTrue(summary.matchesSearch("  BENCH press "))
        assertTrue(summary.matchesSearch("triceps"))
        assertTrue(summary.matchesSearch("barbell"))
        assertTrue(summary.matchesSearch(""))
        assertFalse(summary.matchesSearch("quadriceps"))
    }

    @Test
    fun filters_matchAnyWithinCategory_andAcrossCategories() {
        assertTrue(summary.matchesFilters(setOf("barbell", "cable"), setOf("chest")))
        assertTrue(summary.matchesFilters(emptySet(), setOf("triceps")))
        assertFalse(summary.matchesFilters(setOf("cable"), setOf("chest")))
        assertFalse(summary.matchesFilters(setOf("barbell"), setOf("quadriceps")))
    }

    @Test
    fun muscleGroupText_keepsPrimaryBeforeSecondary() {
        assertEquals("Chest • Triceps", muscleGroupText(summary))
    }
}
