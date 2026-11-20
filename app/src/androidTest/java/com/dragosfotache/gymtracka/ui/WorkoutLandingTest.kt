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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutLandingTest : GymDatabaseTest() {
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
    fun titleOpensReadOnlyDetails() {
        showApp()
        composeRule.onNodeWithTag("routine-options-upper").performClick()
        capture("expanded")
        composeRule.onNodeWithTag("routine-open-upper").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithText("Start routine").assertIsDisplayed() }.isSuccess
        }
        composeRule.onNodeWithText("Start routine").assertIsDisplayed()
        composeRule.onNodeWithTag("routine-name-input").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(Destination.RoutineDetail, appState.destination) }
        assertNull(runBlocking { repository.activeWorkout() })
        capture("routine-details")
        composeRule.onNodeWithText("Start routine").performClick()
        composeRule.waitUntil { composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Friday - Upper B", runBlocking { repository.activeWorkout()!!.name })
    }

    @Test
    fun chevronsToggleIndependentActionsAndEditOpensEditor() {
        showApp()
        composeRule.onNodeWithText("[Edit]").assertDoesNotExist()
        composeRule.onNodeWithTag("routine-options-upper").performClick()
        composeRule.onNodeWithText("[Edit]").assertIsDisplayed()
        composeRule.onNodeWithText("[Delete]").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(Destination.Workout, appState.destination) }
        composeRule.onNodeWithTag("routine-options-upper").performClick()
        composeRule.onNodeWithText("[Edit]").assertDoesNotExist()
        composeRule.onNodeWithTag("routine-options-lower").performClick()
        composeRule.onNodeWithText("[Edit]").performClick()
        composeRule.onNodeWithTag("routine-name-input").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("lower", appState.editingRoutineId) }
    }

    @Test
    fun startCopiesRoutineIntoActiveWorkout() {
        showApp()
        capture("collapsed")
        composeRule.onNodeWithTag("routine-start-upper").performClick()
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty()
        }
        val workout = runBlocking { repository.workoutDetails(repository.activeWorkout()!!.id)!! }
        assertEquals("Friday - Upper B", workout.workout.name)
        assertEquals(8, workout.exercises.single().sets.single().repetitions)
        assertEquals(20.0, workout.exercises.single().sets.single().weightKg!!, 0.0)
        composeRule.runOnIdle { assertEquals(Destination.ActiveWorkout, appState.destination) }
    }

    @Test
    fun narrowScreenWithDoubleTextKeepsActionsAndNavigationUsable() {
        showApp(width = 320.dp, fontScale = 2f)
        composeRule.onNodeWithTag("create-routine").assertIsDisplayed()
        composeRule.onNodeWithTag("routine-options-upper").performClick()
        composeRule.onNodeWithText("[Delete]").performScrollTo().assertIsDisplayed()
        capture("narrow-large-text")
        composeRule.onNodeWithTag("nav-settings").performClick().assertIsSelected()
        composeRule.onNodeWithTag("nav-exercises").performClick().assertIsSelected()
        composeRule.onNodeWithTag("nav-history").performClick().assertIsSelected()
        composeRule.onNodeWithTag("nav-workout").performClick().assertIsSelected()
        composeRule.onNodeWithTag("routine-open-upper").performScrollTo().performClick()
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("routine-details-set-set-upper").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start routine").assertIsDisplayed()
        capture("routine-details-large-text")
        composeRule.onNodeWithContentDescription("Back to workouts").performClick()
        composeRule.onNodeWithTag("create-routine").performClick()
        composeRule.onNodeWithTag("routine-name-input").assertIsDisplayed()
    }

    private fun showApp(width: Dp? = null, fontScale: Float = 1f) {
        runBlocking {
            insertExercises(exerciseFixture("press", "Incline Dumbbell Press"))
            listOf("upper" to "Friday - Upper B", "lower" to "Thursday - Lower B").forEach { (id, name) ->
                insertRoutineAggregate(
                    RoutineEntity(id, name),
                    listOf(RoutineExerciseEntity("entry-$id", id, "press", 0)),
                    listOf(RoutineSetEntity("set-$id", "entry-$id", 0, 8, 20.0)),
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
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("routine-open-upper").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "workout-landing").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
