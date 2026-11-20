package com.dragosfotache.gymtracka.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.MuscleGroupEntity
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.ui.activeworkout.RestDurationDialog
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.exercises.CreateExerciseScreen
import com.dragosfotache.gymtracka.ui.exercises.ExerciseCreationState
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DialogAppearanceTest : GymDatabaseTest() {
    @get:Rule
    val composeRule = createComposeRule()
    private val enabled = mutableStateOf(true)
    private var appJob: Job? = null

    @After
    fun disposeContent() {
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitForIdle()
        appJob?.let { job -> runBlocking { withTimeout(5_000) { job.join() } } }
    }

    @Test
    fun dismissAndConfirmUseOppositeEdgesAndSingleActionStaysRight() {
        val first = mutableStateOf(true)
        var cancelled = 0
        var acknowledged = 0
        composeRule.setContent {
            GymTrackaTheme {
                if (enabled.value) {
                    if (first.value) {
                        GymDialog(
                            onDismissRequest = {}, title = "Delete exercise?",
                            text = { Text("Remove this exercise and its sets?") },
                            dismissAction = GymDialogAction("Cancel", { cancelled++; first.value = false }),
                            confirmAction = GymDialogAction("Delete", {}, destructive = true),
                        )
                    } else {
                        GymDialog(
                            onDismissRequest = {},
                            text = { Text("Complete at least one set before finishing this workout.") },
                            confirmAction = GymDialogAction("OK", { acknowledged++ }),
                        )
                    }
                }
            }
        }
        assertActions("[Cancel]", "[Delete]", stacked = false)
        capture("confirmation")
        composeRule.onNodeWithText("[Cancel]").performClick()
        composeRule.runOnIdle { assertEquals(1, cancelled) }
        val row = composeRule.onNodeWithTag("gym-dialog-actions").fetchSemanticsNode().boundsInRoot
        val ok = composeRule.onNodeWithText("[OK]").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(row.right, ok.right, 1f)
        capture("notice")
        composeRule.onNodeWithText("[OK]").performClick()
        composeRule.runOnIdle { assertEquals(1, acknowledged) }
    }

    @Test
    fun doubleTextStacksLongActionsAndScrollsBodyWithoutHidingButtons() {
        composeRule.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    if (enabled.value) GymDialog(
                        onDismissRequest = {}, title = "Replace current workout?",
                        text = {
                            Column {
                                repeat(20) { Text("Long workout details remain available by scrolling.") }
                                Text("End of details")
                            }
                        },
                        dismissAction = GymDialogAction("Keep current workout", {}),
                        confirmAction = GymDialogAction("Replace current workout", {}, destructive = true),
                    )
                }
            }
        }
        assertActions("[Keep current workout]", "[Replace current workout]", stacked = true)
        composeRule.onNodeWithText("End of details").assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        assertActions("[Keep current workout]", "[Replace current workout]", stacked = true)
        capture("long-actions-double-text")
    }

    @Test
    fun customEditorSelectorsKeepDraftApplyAndClearActionsAtDoubleText() {
        insertMuscleGroups(MuscleGroupEntity("chest", "chest", "Chest"))
        insertEquipment(equipmentFixture("barbell", "Barbell", "weight"))
        runBlocking { repository.createCustomExercise("Seed", "chest", emptySet(), "barbell", "strength", "") }
        lateinit var state: ExerciseCreationState
        composeRule.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    if (enabled.value) {
                        val scope = rememberCoroutineScope()
                        appJob = scope.coroutineContext[Job]
                        state = remember { ExerciseCreationState(null, repository, scope) }
                        CreateExerciseScreen(onBack = {}, onSaved = {}, state = state)
                    }
                }
            }
        }
        listOf(
            "Primary muscle group" to "chest",
            "Secondary muscle group" to "chest",
            "Equipment" to "barbell",
            "Category" to "strength",
        ).forEach { (field, id) ->
            composeRule.onNodeWithText(field).performScrollTo().performClick()
            composeRule.waitUntil { composeRule.onAllNodesWithTag("filter-option-$id").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("filter-option-$id").performScrollTo().performClick()
            capture("selector-${field.lowercase().replace(' ', '-')}")
            assertActions("[Clear selection]", "[Done]", stacked = false)
            composeRule.onNodeWithText("[Done]").performClick()
            composeRule.onNodeWithText(field).performScrollTo().performClick()
            composeRule.onNodeWithText("[Clear selection]").performClick()
            composeRule.onNodeWithText("[Done]").performClick()
        }
        composeRule.runOnIdle {
            assertNull(state.primaryMuscleGroupId)
            assertTrue(state.secondaryMuscleGroupIds.isEmpty())
            assertNull(state.equipmentId)
            assertNull(state.category)
        }
    }

    @Test
    fun restPickerUsesRightAlignedTextActionAndKeepsSelectedDuration() = showRestPicker(1f)

    @Test
    fun restPickerFitsDoubleTextAndKeepsSelectedDuration() = showRestPicker(2f)

    private fun showRestPicker(fontScale: Float) {
        var selected: Int? = null
        composeRule.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    if (enabled.value) RestDurationDialog(125, onDismiss = {}, onDone = { selected = it })
                }
            }
        }
        composeRule.onNodeWithText("Minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Seconds").assertIsDisplayed()
        val row = composeRule.onNodeWithTag("gym-dialog-actions").fetchSemanticsNode().boundsInRoot
        val done = composeRule.onNodeWithText("[Done]").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(row.right, done.right, 1f)
        capture(if (fontScale == 1f) "rest-timer" else "rest-timer-double-text")
        composeRule.onNodeWithText("[Done]").performClick()
        composeRule.runOnIdle { assertEquals(125, selected) }
    }

    private fun assertActions(left: String, right: String, stacked: Boolean) {
        val row = composeRule.onNodeWithTag("gym-dialog-actions").fetchSemanticsNode().boundsInRoot
        val first = composeRule.onNodeWithText(left).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val last = composeRule.onNodeWithText(right).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(row.left, first.left, 1f)
        assertEquals(row.right, last.right, 1f)
        if (stacked) assertTrue("Actions must stack without overlap: $first, $last", first.bottom <= last.top + 1f)
        else assertEquals(first.top, last.top, 1f)
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "dialogs").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
