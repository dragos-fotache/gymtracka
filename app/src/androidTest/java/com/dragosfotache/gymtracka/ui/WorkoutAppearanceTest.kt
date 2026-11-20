package com.dragosfotache.gymtracka.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutScreen
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutState
import com.dragosfotache.gymtracka.ui.theme.GymBackground
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkoutAppearanceTest : GymDatabaseTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private var screenScope: CoroutineScope? = null
    private val timer = mutableStateOf<ActiveRestTimer?>(null)
    private val adjustments = mutableListOf<Int>()
    private val replacements = mutableListOf<String>()
    private val historyRequests = mutableListOf<String>()

    @After
    fun cancelScreenScope() {
        screenScope?.cancel()
    }

    @Test
    fun fourColumnWorkoutKeepsCompletedSetsEditable() {
        seedWorkout()
        showWorkout()
        composeRule.onNodeWithText("PREVIOUS").assertDoesNotExist()
        composeRule.onNodeWithText("Add exercise").assertIsDisplayed()
        capture("workout-no-timer")

        composeRule.onNodeWithTag("workout-set-complete-set-2").assertIsOn()
        composeRule.onNodeWithTag("workout-set-weight-set-2").performTextReplacement("122.5")
        composeRule.waitUntil {
            runBlocking { repository.workoutDetails("appearance")!!.exercises.single().sets[1].weightKg == 122.5 }
        }
        composeRule.onNodeWithTag("workout-set-complete-set-2").assertIsOn()
    }

    @Test
    fun inlineExerciseActionsKeepTheWorkoutEditable() {
        seedWorkout()
        showWorkout()
        val options = composeRule.onNodeWithContentDescription("Options for Incline Dumbbell Press")
        options.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        composeRule.onNodeWithText("[Replace]").assertDoesNotExist()
        options.performClick()
        options.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        composeRule.onNodeWithText("[Replace]").assertIsDisplayed()
        composeRule.onNodeWithText("[History]").assertIsDisplayed()
        composeRule.onNodeWithText("[Delete]").assertIsDisplayed()
        composeRule.onNodeWithText("Add notes...").assertIsDisplayed()
        composeRule.onNodeWithText("[Rest Timer: 2min]").assertIsDisplayed()
        composeRule.onNodeWithTag("workout-set-weight-set-1").assertIsDisplayed()
        capture("workout-menu-expanded")

        options.performClick()
        composeRule.onNodeWithText("[Replace]").assertDoesNotExist()
        options.performClick()
        composeRule.onNodeWithText("[History]").performClick()
        assertEquals(listOf(runBlocking { repository.workoutDetails("appearance")!!.exercises.single().workoutExercise.exerciseId }), historyRequests)
        options.performClick()
        composeRule.onNodeWithText("[Replace]").performClick()
        assertEquals(listOf("entry-1"), replacements)
        options.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))

        options.performClick()
        composeRule.onNodeWithText("[Delete]").performClick()
        composeRule.onNodeWithText("Delete exercise?").assertIsDisplayed()
        composeRule.onNodeWithText("[Cancel]").performClick()
        assertEquals(2, runBlocking { repository.workoutDetails("appearance")!!.exercises.single().sets.size })
    }

    @Test
    fun narrowWorkoutKeepsTimerControlsAvailable() {
        seedWorkout(twoExercises = true)
        timer.value = ActiveRestTimer("set-2", 120, 0, 1)
        showWorkout(width = 320.dp)
        checkTimerActions()
        capture("workout-timer-narrow")
    }

    @Test
    fun enlargedTextKeepsTimerControlsAvailable() {
        seedWorkout(twoExercises = true)
        timer.value = ActiveRestTimer("set-2", 120, 0, 1)
        showWorkout(width = 320.dp, fontScale = 1.5f)
        checkTimerActions()
        capture("workout-timer-large-text")
    }

    @Test
    fun workoutWithTimerMatchesTwoExerciseReference() {
        seedWorkout(twoExercises = true)
        timer.value = ActiveRestTimer("set-2", 120, 0, 1)
        showWorkout()
        capture("workout-timer")
        composeRule.onNodeWithTag("rest-timer-skip").performClick()
        composeRule.onNodeWithTag("rest-timer-skip").assertDoesNotExist()
    }

    private fun checkTimerActions() {
        composeRule.onNodeWithTag("rest-timer-minus").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("rest-timer-plus").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("rest-timer-skip").assertIsDisplayed()
        assertEquals(listOf(-15, 15), adjustments)
        val countdownLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("01:25").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(countdownLayouts)
        }
        val countdown = countdownLayouts.single()
        assertTrue("The complete countdown must fit", countdown.getLineRight(0) <= countdown.size.width + 1f)
    }

    private fun seedWorkout(twoExercises: Boolean = false) = runBlocking {
        val press = repository.createCustomExercise("Incline Dumbbell Press", null, emptySet(), null, "strength", "")
        val raises = repository.createCustomExercise("Lateral Raises", null, emptySet(), null, "strength", "")
        val entries = listOf(
            workoutExerciseFixture("entry-1", "appearance", press, restDurationSeconds = 120),
        ) + if (twoExercises) listOf(
            workoutExerciseFixture("entry-2", "appearance", raises, position = 1, restDurationSeconds = 90),
        ) else emptyList()
        insertWorkoutAggregate(
            workoutFixture("appearance", "Morning workout", System.currentTimeMillis() - 832_000),
            entries,
            entries.flatMapIndexed { index, entry ->
                listOf(
                    workoutSetFixture("set-${index * 2 + 1}", entry.id, repetitions = 12, weightKg = 125.5),
                    workoutSetFixture("set-${index * 2 + 2}", entry.id, position = 1, repetitions = 12, weightKg = 120.0, isCompleted = true),
                )
            },
        )
    }

    private fun showWorkout(width: Dp? = null, fontScale: Float = 1f) {
        composeRule.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(GymBackground)) {
                        Box(if (width != null) Modifier.width(width) else Modifier.fillMaxSize()) {
                            val scope = rememberCoroutineScope().also { screenScope = it }
                            val start = remember { System.currentTimeMillis() - 832_000 }
                            val state = remember { ActiveWorkoutState("appearance", start, repository, scope) }
                            ActiveWorkoutScreen(
                                workoutId = "appearance",
                                workoutStartMillis = start,
                                activeRestTimer = timer.value,
                                restTimerRemainingSeconds = 85,
                                restTimerCompletionKey = 0,
                                onAddExercise = {}, onCollapse = {}, onWorkoutNameChanged = {},
                                onFinishWorkout = {}, onCancelWorkout = {}, onRestTimerStarted = { _, _ -> },
                                onRestTimerEnded = { timer.value = null },
                                onRestTimerAdjusted = { _, delta -> adjustments += delta },
                                onExerciseReplacementRequested = { replacements += it },
                                onExerciseHistoryRequested = { historyRequests += it },
                                state = state,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("workout-set-weight-set-1").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "workout-appearance").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
