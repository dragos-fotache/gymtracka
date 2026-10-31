package com.dragosfotache.gymtracka.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.ui.settings.SettingsScreen
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTabTest : AppFlowTestSupport() {
    @Test
    fun settingsSelectionPreservesWorkoutAndRunningTimerAcrossAllTabs() {
        runBlocking {
            insertExercises(exerciseFixture(id = "settings-press", name = "Press"))
            insertWorkoutAggregate(
                workout = workoutFixture(
                    id = "settings-workout",
                    name = "Settings workout",
                    startedAtMillis = System.currentTimeMillis(),
                ),
                exercises = listOf(
                    workoutExerciseFixture(
                        id = "settings-entry",
                        workoutId = "settings-workout",
                        exerciseId = "settings-press",
                        restDurationSeconds = 300,
                    ),
                ),
                sets = listOf(
                    workoutSetFixture(
                        id = "settings-set",
                        workoutExerciseId = "settings-entry",
                        repetitions = 8,
                        weightKg = 20.0,
                    ),
                ),
            )
        }
        setUpGymApp()
        waitForUi("settings-active-discovery") {
            composeRule.onAllNodesWithTag("active-workout-pill").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("active-workout-pill").performClick()
        waitForUi("settings-set-loaded") {
            composeRule.onAllNodesWithTag("workout-set-complete-settings-set").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("workout-set-complete-settings-set").performClick()
        waitForUi("settings-timer-started") { appState()?.activeRestTimer != null }
        val timer = composeRule.runOnIdle { appState()!!.activeRestTimer!! }
        composeRule.onNodeWithContentDescription("Collapse workout").performClick()

        listOf("nav-settings", "nav-exercises", "nav-history", "nav-workout", "nav-settings").forEach { tag ->
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.onNodeWithTag(tag).assertIsSelected()
            composeRule.onNodeWithTag("active-workout-pill").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals("settings-workout", appState()!!.activeWorkout!!.id)
                assertEquals(timer, appState()!!.activeRestTimer)
            }
        }
        composeRule.onNodeWithTag("settings-server").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-account").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-server-status").assertTextEquals("Offline")
        captureSettingsScreenshot("timer")
        composeRule.onNodeWithTag("account-status").assertDoesNotExist()
        composeRule.onNodeWithTag("account-open").assertDoesNotExist()
        assertNull(
            runBlocking { repository.workoutDetails("settings-workout")!!.exercises.single().sets.single().restSeconds },
        )

        composeRule.onNodeWithTag("active-workout-pill").performClick()
        composeRule.runOnIdle {
            assertEquals(Destination.ActiveWorkout, appState()!!.destination)
            assertEquals(timer, appState()!!.activeRestTimer)
        }
        composeRule.onNodeWithContentDescription("Collapse workout").performClick()
        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.runOnIdle { appState()!!.skipRestTimer(timer) }
        waitForUi("settings-rest-persisted") {
            runBlocking { repository.workoutDetails("settings-workout")!!.exercises.single().sets.single().restSeconds != null }
        }
        composeRule.runOnIdle { assertNull(appState()!!.activeRestTimer) }
        assertNotNull(runBlocking { repository.activeWorkout() })
        composeRule.onNodeWithTag("nav-settings").assertIsSelected()
    }

    @Test
    fun sectionsScrollAtDoubleFontSizeWithoutClaimingConnectivity() {
        composeRule.setContent {
            GymTrackaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    SettingsScreen(modifier = Modifier.height(360.dp))
                }
            }
        }
        composeRule.onNodeWithText("Connection").assertIsDisplayed()
        captureSettingsScreenshot("large-text-top")
        composeRule.onNodeWithTag("settings-server-status").assertTextEquals("Offline")
        composeRule.onNodeWithText("Account controls are unavailable in this local session.").performScrollTo()
        composeRule.onNodeWithText("Account controls are unavailable in this local session.").assertIsDisplayed()
        composeRule.onNodeWithText("Connection").assertIsNotDisplayed()
        captureSettingsScreenshot("large-text-bottom")
        composeRule.onNodeWithText("Connection").performScrollTo().assertIsDisplayed()
    }
}

/** Optional device artifacts for visual review of these synthetic UI fixtures. */
internal fun captureSettingsScreenshot(name: String) {
    if (InstrumentationRegistry.getArguments().getString("settingsScreenshots") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        File(instrumentation.targetContext.cacheDir, "settings-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally {
        bitmap.recycle()
    }
}
