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
import androidx.compose.ui.test.captureToImage
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
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ExerciseHistoryFlowTest : GymDatabaseTest() {
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
    fun historyShowsWorkoutPanelsAndReturnsWithoutChangingTheActiveWorkoutOrTimer() {
        showApp()
        composeRule.runOnIdle {
            appState.startRestTimer(workoutSetFixture("active-set", "active-entry", isCompleted = true), 120)
        }
        composeRule.waitUntil { appState.activeRestTimer != null }
        val timer = composeRule.runOnIdle { appState.activeRestTimer }
        openActions()
        val actions = composeRule.onNodeWithTag("workout-exercise-actions-active-entry").fetchSemanticsNode().boundsInRoot
        val replace = composeRule.onNodeWithText("[Replace]", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val history = composeRule.onNodeWithText("[History]", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val delete = composeRule.onNodeWithText("[Delete]", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(actions.left, replace.left, 2f)
        assertEquals(actions.center.x, history.center.x, 2f)
        assertEquals(actions.right, delete.right, 2f)
        capture("actions")
        openHistory()
        composeRule.onNodeWithText("Friday - Upper B").assertIsDisplayed()
        composeRule.onNodeWithText("Pause at the bottom").assertIsDisplayed()
        composeRule.onNodeWithText("62.5 kg").assertIsDisplayed()
        composeRule.onNodeWithTag("exercise-history-set-recent-2").assertIsDisplayed()
        composeRule.onNodeWithText("Monday - Upper A").assertIsDisplayed()
        val weightStart = composeRule.assertHistoryColumnGrid(
            "exercise-history-workout-recent", "exercise-history-set-recent-1", "WEIGHT", "62.5 kg",
        )
        composeRule.assertHistoryColumnGrid(
            "exercise-history-workout-recent", "exercise-history-set-recent-2", "WEIGHT", "65 kg",
        )
        assertEquals(
            "Every workout panel must share its column starts", weightStart,
            composeRule.assertHistoryColumnGrid(
                "exercise-history-workout-old", "exercise-history-set-old-1", "WEIGHT", "60 kg",
            ),
            1f,
        )
        composeRule.onNodeWithTag("exercise-history-set-unfinished").assertDoesNotExist()
        capture("history-panels")
        composeRule.runOnIdle {
            assertEquals(Destination.ExerciseHistory, appState.destination)
            assertEquals(timer, appState.activeRestTimer)
        }
        Espresso.pressBack()
        waitForWorkout()
        composeRule.onNodeWithTag("workout-set-weight-active-set").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(Destination.ActiveWorkout, appState.destination)
            assertEquals(timer, appState.activeRestTimer)
        }
        assertEquals("active", runBlocking { repository.activeWorkout()!!.id })
        assertNotNull(runBlocking { repository.workoutDetails("active")!!.exercises.single().sets.single() })
    }

    @Test
    fun narrowHistoryWithLargeTextWrapsPanelsAndScrollsToAllSets() {
        showApp(width = 320.dp, fontScale = 1.5f)
        openActions()
        composeRule.onNodeWithText("[Replace]").assertIsDisplayed()
        composeRule.onNodeWithText("[Delete]").assertIsDisplayed()
        composeRule.onNodeWithText("[History]").assertIsDisplayed()
        listOf("[Replace]", "[History]", "[Delete]").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Action labels must stay intact", 1, layouts.single().lineCount)
        }
        capture("actions-large-text")
        openHistory()
        capture("history-large-text")
        composeRule.onNodeWithTag("exercise-history-set-old-1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to workout").performClick()
        waitForWorkout()
        composeRule.onNodeWithTag("workout-set-weight-active-set").assertIsDisplayed()
    }

    @Test
    fun unusedExerciseShowsAnEmptyHistoryAndCanReturnToWorkout() {
        showApp(withHistory = false)
        openActions()
        openHistory()
        composeRule.onNodeWithText("No completed workouts for this exercise yet.").assertIsDisplayed()
        capture("history-empty")
        composeRule.onNodeWithContentDescription("Back to workout").performClick()
        waitForWorkout()
        composeRule.onNodeWithTag("workout-name-input").assertIsDisplayed()
    }

    private fun openActions() {
        composeRule.onNodeWithContentDescription("Options for Incline Dumbbell Press").performClick()
    }

    private fun openHistory() {
        composeRule.onNodeWithText("[History]").performClick()
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("exercise-history-screen").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Incline Dumbbell Press").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Incline Dumbbell Press").assertIsDisplayed()
    }

    private fun waitForWorkout() {
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("workout-set-weight-active-set").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showApp(width: Dp? = null, fontScale: Float = 1f, withHistory: Boolean = true) {
        runBlocking {
            insertExercises(exerciseFixture("press", "Incline Dumbbell Press"))
            insertWorkoutAggregate(
                workoutFixture("active", "Current workout", System.currentTimeMillis()),
                listOf(workoutExerciseFixture("active-entry", "active", "press")),
                listOf(workoutSetFixture("active-set", "active-entry", isCompleted = true)),
            )
            if (withHistory) {
                insertWorkoutAggregate(
                    workoutFixture("recent", "Friday - Upper B", finishedAtMillis = 1_793_448_000_000, status = "finished"),
                    listOf(workoutExerciseFixture("recent-entry", "recent", "press", notes = "Pause at the bottom")),
                    listOf(
                        workoutSetFixture("recent-1", "recent-entry", weightKg = 62.5, isCompleted = true),
                        workoutSetFixture("recent-2", "recent-entry", position = 1, repetitions = 6, weightKg = 65.0, isCompleted = true),
                        workoutSetFixture("unfinished", "recent-entry", position = 2),
                    ),
                )
                insertWorkoutAggregate(
                    workoutFixture("old", "Monday - Upper A", finishedAtMillis = 1_793_102_400_000, status = "finished"),
                    listOf(workoutExerciseFixture("old-entry", "old", "press")),
                    listOf(workoutSetFixture("old-1", "old-entry", weightKg = 60.0, isCompleted = true)),
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
        composeRule.waitUntil { appState.activeWorkout != null }
        composeRule.runOnIdle { appState.resumeWorkout() }
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("workout-set-weight-active-set").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "exercise-history").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
