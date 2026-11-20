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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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

class WorkoutHistoryAppearanceTest : GymDatabaseTest() {
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
    fun historySeparatesMetadataAndExerciseRowsAndTogglesInlineActionsWithoutNavigation() {
        showHistory()
        val date = composeRule.onNodeWithTag("history-date-upper", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithTag("history-time-upper", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Time belongs below the date", date.bottom <= time.top + 1f)
        val sets = composeRule.onNodeWithTag("history-sets-entry-press", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val best = composeRule.onNodeWithTag("history-best-set-entry-press", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Best set belongs below Sets", sets.bottom <= best.top + 1f)
        capture("history-collapsed")

        val options = composeRule.onNodeWithTag("history-options-upper")
        options.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        composeRule.onNodeWithText("[Save]").assertDoesNotExist()
        options.performClick()
        options.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        composeRule.onNodeWithText("[Save]").assertIsDisplayed()
        composeRule.onNodeWithText("[Perform]").assertIsDisplayed()
        composeRule.onNodeWithText("[Delete]").assertIsDisplayed()
        composeRule.onNodeWithTag("history-options-lower")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        composeRule.runOnIdle { assertEquals(Destination.History, appState.destination) }
        capture("history-expanded")
        options.performClick()
        composeRule.onNodeWithText("[Save]").assertDoesNotExist()

        composeRule.onNodeWithTag("history-workout-upper").performClick()
        composeRule.waitUntil { appState.destination == Destination.WorkoutHistoryDetail }
        composeRule.runOnIdle { assertEquals("upper", appState.selectedHistoryWorkoutId) }
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("history-workout-upper").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun narrowHistoryWithDoubleTextKeepsLongSummariesAndAllActionsReadable() {
        showHistory(width = 320.dp, fontScale = 2f)
        composeRule.onNodeWithTag("history-options-upper").performClick()
        capture("history-actions-large-text")
        listOf("[Save]", "[Perform]", "[Delete]").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
            assertTextFits(label, singleLine = true)
        }
        composeRule.onNodeWithTag("history-options-upper").performClick()
        composeRule.onNodeWithTag("history-sets-entry-row", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertTextFits("2 × Single-Arm Dumbbell Row With Bench Support")
        capture("history-summary-large-text")
        composeRule.onNodeWithTag("history-best-set-entry-row", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertTextFits("Best set: 60.5 kg × 10", withinTag = "history-best-set-entry-row")
    }

    @Test
    fun emptyHistoryUsesTheSameScreenChrome() {
        showHistory(withWorkouts = false)
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("No finished workouts yet.").assertIsDisplayed()
        capture("history-empty")
    }

    private fun assertTextFits(text: String, singleLine: Boolean = false, withinTag: String? = null) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = if (withinTag == null) {
            composeRule.onNodeWithText(text, useUnmergedTree = true)
        } else {
            composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(withinTag)), useUnmergedTree = true)
        }
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        if (singleLine) assertEquals(1, layout.lineCount)
        repeat(layout.lineCount) { line ->
            assertTrue("The whole line of $text must fit", layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("All lines must fit vertically", layout.getLineBottom(line) <= layout.size.height + 1f)
        }
    }

    private fun showHistory(width: Dp? = null, fontScale: Float = 1f, withWorkouts: Boolean = true) {
        if (withWorkouts) runBlocking {
            insertExercises(
                exerciseFixture("press", "Incline Dumbbell Press"),
                exerciseFixture("row", "Single-Arm Dumbbell Row With Bench Support"),
                exerciseFixture("deadlift", "Romanian Deadlift"),
            )
            listOf("upper", "lower").forEachIndexed { index, id ->
                val entries = if (id == "upper") listOf(
                    workoutExerciseFixture("entry-press", id, "press"),
                    workoutExerciseFixture("entry-row", id, "row", position = 1),
                ) else listOf(workoutExerciseFixture("entry-deadlift", id, "deadlift"))
                insertWorkoutAggregate(
                    workoutFixture(
                        id, if (id == "upper") "Friday - Upper B" else "Thursday - Lower B",
                        startedAtMillis = 1_794_041_580_000 - index * 86_400_000L,
                        finishedAtMillis = 1_794_044_280_000 - index * 86_400_000L,
                        status = "finished",
                    ),
                    entries,
                    entries.flatMap { entry ->
                        listOf(
                            workoutSetFixture("set-${entry.id}-1", entry.id, repetitions = 10, weightKg = 60.5, isCompleted = true),
                            workoutSetFixture("set-${entry.id}-2", entry.id, position = 1, repetitions = 8, weightKg = 62.5, isCompleted = true),
                        )
                    },
                )
            }
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
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("workout-history-list").fetchSemanticsNodes().isNotEmpty() &&
                (!withWorkouts || composeRule.onAllNodesWithTag("history-workout-upper").fetchSemanticsNodes().isNotEmpty())
        }
        if (!withWorkouts) composeRule.waitUntil {
            composeRule.onAllNodesWithText("No finished workouts yet.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "workout-history").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
