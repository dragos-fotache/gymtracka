package com.dragosfotache.gymtracka.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutPill
import com.dragosfotache.gymtracka.ui.theme.GymBackground
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import java.io.File
import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveWorkoutPillAppearanceTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val restRemaining = mutableStateOf<Int?>(null)
    private var resumed = 0
    private var discarded = 0

    @Test
    fun workoutLinkAndDiscardKeepSeparateActions() {
        showPill()
        capture("without-rest")
        assertWholeLabel("[Friday – Upper B]")
        composeRule.onNodeWithTag("active-workout-elapsed", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("active-workout-rest", useUnmergedTree = true).assertDoesNotExist()
        assertGridAndBounds()
        composeRule.onNodeWithTag("active-workout-pill").performClick()
        composeRule.onNodeWithContentDescription("Discard current workout").performClick()
        composeRule.runOnIdle {
            assertEquals(1, resumed)
            assertEquals(1, discarded)
        }
    }

    @Test
    fun liveRestUpdatesWithoutReplacingElapsedTimeOrActions() {
        restRemaining.value = 85
        showPill()
        capture("with-rest")
        assertWholeLabel("Rest 01:25")
        assertGridAndBounds()
        composeRule.runOnIdle { restRemaining.value = 12 }
        assertWholeLabel("Rest 00:12")
        composeRule.runOnIdle { restRemaining.value = null }
        composeRule.onNodeWithTag("active-workout-rest", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("active-workout-elapsed", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("[Discard]").assertIsDisplayed()
    }

    @Test
    fun narrowDoubleTextWrapsNameAndStacksCompleteMetrics() {
        val name = "Friday – Upper B – Strength and conditioning"
        restRemaining.value = 85
        showPill(name, width = 320.dp, fontScale = 2f, elapsedSeconds = 3_700)
        capture("narrow-double-text")
        val title = assertWholeLabel("[$name]")
        assertTrue("Long names must wrap", title.lineCount > 1)
        assertWholeLabel("Rest 01:25")
        val elapsed = composeRule.onNodeWithTag("active-workout-elapsed", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rest = composeRule.onNodeWithTag("active-workout-rest", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val discard = composeRule.onNodeWithTag("active-workout-discard", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(elapsed.bottom <= rest.top + 1f)
        assertTrue(rest.bottom <= discard.top + 1f)
        assertGridAndBounds()
        composeRule.onNodeWithText("[Discard]").performClick()
        composeRule.runOnIdle {
            assertEquals(0, resumed)
            assertEquals(1, discarded)
        }
    }

    private fun showPill(
        name: String = "Friday – Upper B",
        width: Dp? = null,
        fontScale: Float = 1f,
        elapsedSeconds: Long = 832,
    ) {
        val workout = WorkoutEntity("pill", name, System.currentTimeMillis() - elapsedSeconds * 1_000)
        composeRule.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(GymBackground), contentAlignment = Alignment.BottomCenter) {
                        ActiveWorkoutPill(
                            workout, restRemaining.value,
                            modifier = if (width == null) Modifier else Modifier.width(width),
                            onClick = { resumed++ }, onDiscard = { discarded++ },
                        )
                    }
                }
            }
        }
    }

    private fun assertWholeLabel(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().also { layout ->
            repeat(layout.lineCount) { line ->
                assertTrue("The complete line must fit: $text", layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("The complete line height must fit: $text", layout.getLineBottom(line) <= layout.size.height + 1f)
                assertTrue("Labels must wrap without ellipsis: $text", !layout.isLineEllipsized(line))
            }
        }
    }

    private fun assertGridAndBounds() {
        val panel = composeRule.onNodeWithTag("active-workout-pill").fetchSemanticsNode().boundsInRoot
        val scale = panel.width / 402f
        val title = composeRule.onNodeWithTag("active-workout-name", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val elapsed = composeRule.onNodeWithTag("active-workout-elapsed", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val discard = composeRule.onNodeWithTag("active-workout-discard", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(title.left, elapsed.left, 1f)
        assertEquals(title.right, discard.right, 1f)
        listOf(title, elapsed, discard).forEach { bounds ->
            assertTrue(bounds.left >= panel.left && bounds.right <= panel.right)
            assertTrue(bounds.top >= panel.top && bounds.bottom <= panel.bottom)
            val offset = bounds.left - title.left
            assertEquals(round(offset / (6 * scale)) * 6 * scale, offset, 1.5f)
        }
        assertEquals(round(panel.height / (12 * scale)) * 12 * scale, panel.height, 1.5f)
        assertWholeLabel("[Discard]")
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "active-workout-pill").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
