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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorScreen
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorState
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

class RoutineEditorAppearanceTest : GymDatabaseTest() {
    @get:Rule
    val composeRule = createComposeRule()
    private val enabled = mutableStateOf(true)
    private lateinit var edits: FiniteEditCoordinator
    private var appJob: Job? = null

    @After
    fun disposeEditor() {
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitForIdle()
        appJob?.let { job -> runBlocking { withTimeout(5_000) { job.join() } } }
        if (::edits.isInitialized) edits.dispose()
    }

    @Test
    fun editorUsesWorkoutControlsAndConfirmsInlineExerciseDeletion() {
        showEditor()
        composeRule.onNodeWithContentDescription("Exercise options").assertDoesNotExist()
        composeRule.onNodeWithTag("routine-delete-set-first-1").assertDoesNotExist()
        composeRule.onNodeWithText("[Rest Timer: 45s]").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsEnabled()
        capture("routine-editor")
        composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Upper revised")
        composeRule.onNodeWithTag("routine-set-weight-first-1").performTextReplacement("62.5")
        composeRule.onNodeWithTag("routine-set-reps-first-1").performTextReplacement("10")
        composeRule.waitUntil {
            runBlocking {
                val details = repository.routineDetails("routine")!!
                details.routine.name == "Upper revised" && details.exercises.first().sets.first().let {
                    it.weightKg == 62.5 && it.repetitions == 10
                }
            }
        }
        composeRule.onNodeWithContentDescription("Delete Incline Dumbbell Press from routine").performClick()
        composeRule.onNodeWithText("Delete exercise?").assertIsDisplayed()
        composeRule.onNode(hasText("[Cancel]") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(2, runBlocking { repository.routineDetails("routine")!!.exercises.size })
        composeRule.onNodeWithContentDescription("Delete Incline Dumbbell Press from routine").performClick()
        composeRule.onNode(hasText("[Delete]") and hasAnyAncestor(isDialog())).performClick()
        composeRule.waitUntil { runBlocking { repository.routineDetails("routine")!!.exercises.size == 1 } }
        val survivor = runBlocking { repository.routineDetails("routine")!!.exercises.single() }
        assertEquals("entry-second", survivor.routineExercise.id)
        assertEquals(0, survivor.routineExercise.position)
        assertEquals(20.0, survivor.sets.single().weightKg!!, 0.0)
    }

    @Test
    fun shortAndRefusedSwipesRestoreRowsAndAcceptedSwipeDeletesAndRenumbers() {
        showEditor()
        val original = runBlocking { repository.routineDetails("routine")!! }
        val row = composeRule.onNodeWithTag("routine-set-row-first-1")
        val weight = composeRule.onNodeWithTag("routine-set-weight-first-1")
        val start = weight.fetchSemanticsNode().boundsInRoot.left
        row.performTouchInput {
            swipe(Offset(width * 0.9f, height / 2f), Offset(width * 0.7f, height / 2f))
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        assertEquals(original, runBlocking { repository.routineDetails("routine")!! })
        assertEquals(start, weight.fetchSemanticsNode().boundsInRoot.left, 1f)
        val lease = runBlocking { edits.pauseAndDrain() }
        try {
            row.performTouchInput { swipeLeft() }
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.waitForIdle()
            assertEquals(original, runBlocking { repository.routineDetails("routine")!! })
            assertEquals(start, weight.fetchSemanticsNode().boundsInRoot.left, 1f)
        } finally {
            lease.close()
        }
        row.performTouchInput { swipeLeft() }
        composeRule.waitUntil {
            runBlocking { repository.routineDetails("routine")!!.exercises.first().sets.size == 2 }
        }
        val remaining = runBlocking { repository.routineDetails("routine")!!.exercises.first().sets }
        assertEquals(listOf("first-2", "first-3"), remaining.map { it.id })
        assertEquals(listOf(0, 1), remaining.map { it.position })
        assertEquals(listOf(65.0, 67.5), remaining.map { it.weightKg })
        composeRule.onNodeWithTag("routine-set-row-first-1").assertDoesNotExist()
        composeRule.onNodeWithTag("routine-set-weight-first-2").assertIsDisplayed()
        capture("routine-after-swipe")
    }

    @Test
    fun narrowDoubleTextKeepsNamesDeleteActionsAndSetInputsReadable() {
        showEditor(width = 320.dp, fontScale = 2f)
        listOf("Cancel", "Save").forEach { assertTextFits(it) }
        composeRule.onNodeWithTag("routine-set-weight-first-1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("routine-set-reps-first-1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete Incline Dumbbell Press from routine").performScrollTo().assertIsDisplayed()
        capture("routine-large-text")
        composeRule.onNodeWithContentDescription("Delete Single-Arm Dumbbell Row With Bench Support from routine")
            .performScrollTo().assertIsDisplayed()
        assertTextFits("Single-Arm Dumbbell Row With Bench Support")
        composeRule.onNodeWithTag("routine-set-weight-second-1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("routine-set-reps-second-1").assertIsDisplayed()
        capture("routine-second-exercise-large-text")
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        repeat(layout.lineCount) { line ->
            assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
            assertTrue(layout.getLineBottom(line) <= layout.size.height + 1f)
        }
    }

    private fun showEditor(width: Dp? = null, fontScale: Float = 1f) {
        runBlocking {
            insertExercises(
                exerciseFixture("first", "Incline Dumbbell Press"),
                exerciseFixture("second", "Single-Arm Dumbbell Row With Bench Support"),
            )
            insertRoutineAggregate(
                RoutineEntity("routine", "Friday - Upper B", isDraft = true),
                listOf(
                    RoutineExerciseEntity("entry-first", "routine", "first", 0, restDurationSeconds = 45),
                    RoutineExerciseEntity("entry-second", "routine", "second", 1),
                ),
                listOf(
                    RoutineSetEntity("first-1", "entry-first", 0, 8, 60.0),
                    RoutineSetEntity("first-2", "entry-first", 1, 6, 65.0),
                    RoutineSetEntity("first-3", "entry-first", 2, 5, 67.5),
                    RoutineSetEntity("second-1", "entry-second", 0, 10, 20.0),
                ),
            )
        }
        composeRule.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(if (width == null) Modifier.fillMaxSize() else Modifier.width(width)) {
                        if (enabled.value) {
                            val scope = rememberCoroutineScope()
                            appJob = scope.coroutineContext[Job]
                            edits = remember { FiniteEditCoordinator(scope) }
                            val state = remember { RoutineEditorState("routine", repository, scope, edits) }
                            RoutineEditorScreen("routine", onAddExercise = {}, onCancel = {}, onSaved = {}, state = state)
                        }
                    }
                }
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("routine-set-row-first-1").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "routine-editor").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
