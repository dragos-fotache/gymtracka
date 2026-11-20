package com.dragosfotache.gymtracka.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkoutSummaryAppearanceTest : GymDatabaseTest() {
    @get:Rule
    val composeRule = createComposeRule()
    private val enabled = mutableStateOf(true)
    private lateinit var appState: GymAppState
    private var appJob: Job? = null

    @After
    fun disposeApp() {
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitForIdle()
        appJob?.let { job -> runBlocking { withTimeout(5_000) { job.join() } } }
    }

    @Test
    fun summaryShowsRecordedRestInColumnsAndOmitsEachExercisesFinalRest() {
        showSummary()
        composeRule.onNodeWithText("Workout summary").assertIsDisplayed()
        composeRule.onNodeWithText("Pause at the bottom").assertIsDisplayed()
        composeRule.assertHistoryActionGrid("workout-summary-perform", "Perform again")
        composeRule.onNodeWithTag("workout-summary-set-press-1").assertTextEquals("1", "62.5 kg", "10", "1min 15s")
        composeRule.onNodeWithTag("workout-summary-set-press-2").assertTextEquals("2", "65 kg", "8", "—")
        composeRule.onNodeWithTag("workout-summary-set-press-3").assertTextEquals("3", "67.5 kg", "6", "0s")
        composeRule.onNodeWithTag("workout-summary-set-press-4").assertTextEquals("4", "70 kg", "5")
        val weightStart = composeRule.assertHistoryColumnGrid(
            "workout-summary-exercise-entry-press", "workout-summary-set-press-1", "WEIGHT", "62.5 kg",
        )
        composeRule.assertHistoryColumnGrid(
            "workout-summary-exercise-entry-press", "workout-summary-set-press-1", "REPS", "10",
        )
        composeRule.assertHistoryColumnGrid(
            "workout-summary-exercise-entry-press", "workout-summary-set-press-1", "REST", "1min 15s",
        )
        capture("summary-panels")
        composeRule.onNodeWithTag("workout-summary-set-row-1").performScrollTo().assertTextEquals("1", "40 kg", "12", "12s")
        assertEquals(
            "Every exercise panel must share its column starts", weightStart,
            composeRule.assertHistoryColumnGrid(
                "workout-summary-exercise-entry-row", "workout-summary-set-row-1", "WEIGHT", "40 kg",
            ),
            1f,
        )
        composeRule.onNodeWithTag("workout-summary-set-row-2").assertTextEquals("2", "42.5 kg", "10")
        composeRule.onNodeWithTag("workout-summary-set-solo-1").performScrollTo().assertTextEquals("1", "20 kg", "8")
        composeRule.onNodeWithText("Duration: 45m").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Volume: 2965 kg").assertIsDisplayed()
        capture("summary-totals")
        // Omitting the final rest is presentation only; recorded values remain intact.
        assertEquals(
            listOf(75, null, 0, 999, 12, 91, 123),
            runBlocking { repository.workoutDetails("summary")!!.exercises.flatMap { it.sets }.map { it.restSeconds } },
        )
        composeRule.onNodeWithContentDescription("Back to workouts").performClick()
        composeRule.onNodeWithTag("history-workout-summary").assertIsDisplayed()
        composeRule.onNodeWithTag("history-workout-summary").performClick()
        waitForSummary()
        Espresso.pressBack()
        composeRule.onNodeWithTag("history-workout-summary").assertIsDisplayed()
    }

    @Test
    fun narrowSummaryWithDoubleTextKeepsColumnsAndPerformActionReadable() {
        showSummary(width = 320.dp, fontScale = 2f)
        composeRule.onNodeWithText("Perform again").assertIsDisplayed()
        composeRule.assertHistoryActionGrid("workout-summary-perform", "Perform again")
        assertTextFits("Perform again")
        assertTextFits("Single-Arm Dumbbell Row With Bench Support", within = "workout-summary-exercise-entry-row", scroll = true)
        assertTextFits("REST", within = "workout-summary-exercise-entry-press", scroll = true)
        assertTextFits("1min 15s", within = "workout-summary-set-press-1", scroll = true)
        capture("summary-rest-large-text")
        assertTextFits("62.5 kg", within = "workout-summary-set-press-1", scroll = true)
        capture("summary-weight-large-text")
        composeRule.onNodeWithTag("workout-summary-list").performScrollToNode(hasTestTag("workout-summary-set-solo-1"))
        composeRule.onNodeWithTag("workout-summary-set-solo-1").performScrollTo().assertTextEquals("1", "20 kg", "8")
        composeRule.onNodeWithText("Perform again").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to workouts").assertIsDisplayed()
    }

    private fun assertTextFits(text: String, within: String? = null, scroll: Boolean = false) {
        val node = if (within == null) {
            composeRule.onNodeWithText(text, useUnmergedTree = true)
        } else {
            composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(within)), useUnmergedTree = true)
        }
        if (scroll) node.performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        repeat(layout.lineCount) { line ->
            // Centered Text can retain a wider paragraph; compare the actual glyph span.
            val lineWidth = layout.getLineRight(line) - layout.getLineLeft(line)
            assertTrue("$text must fit horizontally", lineWidth <= layout.size.width + 1f)
            assertTrue("$text must not be ellipsized", !layout.isLineEllipsized(line))
            assertTrue("$text must fit vertically", layout.getLineBottom(line) <= layout.size.height + 1f)
        }
    }

    private fun showSummary(width: Dp? = null, fontScale: Float = 1f) {
        runBlocking {
            insertExercises(
                exerciseFixture("press", "Incline Dumbbell Press"),
                exerciseFixture("row", "Single-Arm Dumbbell Row With Bench Support"),
                exerciseFixture("solo", "Dumbbell Curl"),
            )
            insertWorkoutAggregate(
                workoutFixture(
                    "summary", "Friday - Upper B", startedAtMillis = 1_794_041_580_000,
                    finishedAtMillis = 1_794_044_280_000, status = "finished",
                ),
                listOf(
                    workoutExerciseFixture("entry-press", "summary", "press", notes = "Pause at the bottom"),
                    workoutExerciseFixture("entry-row", "summary", "row", position = 1),
                    workoutExerciseFixture("entry-solo", "summary", "solo", position = 2),
                ),
                listOf(
                    workoutSetFixture("press-1", "entry-press", weightKg = 62.5, repetitions = 10, restSeconds = 75, isCompleted = true),
                    workoutSetFixture("press-2", "entry-press", position = 1, weightKg = 65.0, repetitions = 8, isCompleted = true),
                    workoutSetFixture("press-3", "entry-press", position = 2, weightKg = 67.5, repetitions = 6, restSeconds = 0, isCompleted = true),
                    workoutSetFixture("press-4", "entry-press", position = 3, weightKg = 70.0, repetitions = 5, restSeconds = 999, isCompleted = true),
                    workoutSetFixture("row-1", "entry-row", weightKg = 40.0, repetitions = 12, restSeconds = 12, isCompleted = true),
                    workoutSetFixture("row-2", "entry-row", position = 1, weightKg = 42.5, repetitions = 10, restSeconds = 91, isCompleted = true),
                    workoutSetFixture("solo-1", "entry-solo", weightKg = 20.0, repetitions = 8, restSeconds = 123, isCompleted = true),
                ),
            )
        }
        composeRule.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalGymRepositoryOverride provides repository,
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    Box(if (width == null) Modifier.fillMaxSize() else Modifier.width(width)) {
                        if (enabled.value) {
                            val scope = rememberCoroutineScope()
                            appJob = scope.coroutineContext[Job]
                            appState = remember { GymAppState(repository, scope) }
                            GymApp(appState)
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("nav-history").performClick()
        composeRule.waitUntil { composeRule.onAllNodesWithTag("history-workout-summary").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("[Friday - Upper B]").performClick()
        waitForSummary()
    }

    private fun waitForSummary() {
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("workout-summary-set-press-1").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "workout-summary").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
