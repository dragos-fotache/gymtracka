package com.dragosfotache.gymtracka.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled catalogue through the app shell, independent of configured account startup. */
@RunWith(AndroidJUnit4::class)
class CatalogueAppearanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: GymDatabase
    private lateinit var repository: GymRepository
    private lateinit var appState: GymAppState
    private val enabled = mutableStateOf(true)
    private var appJob: Job? = null
    private val databaseName = "catalogue-appearance-test.db"

    @Before
    fun openCatalogue() {
        context.deleteDatabase(databaseName)
        database = Room.databaseBuilder(context, GymDatabase::class.java, databaseName)
            .createFromAsset("databases/gym.db")
            .addMigrations(*GymDatabase.MIGRATIONS)
            .build()
        repository = GymRepository(database)
        assertEquals(899, runBlocking { repository.exerciseSummaries().size })
    }

    @After
    fun disposeApp() {
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitForIdle()
        appJob?.let { job -> runBlocking { withTimeout(5_000) { job.join() } } }
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun referenceLabelsAndBothFilterPanelsWorkWithBundledCatalogue() {
        showApp()
        assertSingleLine("[Equipment]")
        assertSingleLine("[Muscles]")
        capture("catalogue")
        composeRule.onNodeWithTag("filter-equipment").performClick()
        val machine = runBlocking { repository.equipment().single { it.name == "Machine" }.id }
        val machineBounds = composeRule.onNodeWithTag("filter-option-$machine").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val optionBounds = composeRule.onNodeWithTag("catalogue-filter-options").fetchSemanticsNode().boundsInRoot
        assertTrue("The reference's fifth option row must fit completely", machineBounds.bottom <= optionBounds.bottom)
        capture("equipment")
        val body = runBlocking { repository.equipment().single { it.name == "Body Only" }.id }
        composeRule.onNodeWithTag("filter-option-$body").performClick().assertIsOn()
        composeRule.onNodeWithText("[Show]").assertIsDisplayed().performClick()
        assertSingleLine("[Equipment]")
        composeRule.onNodeWithTag("exercise-search").performTextReplacement("3/4 Sit-Up")
        composeRule.onNodeWithText("[3/4 Sit-Up]").assertIsDisplayed()
        composeRule.onNodeWithTag("filter-muscles").performClick()
        val chest = runBlocking { repository.muscleGroups().single { it.name == "Chest" }.id }
        composeRule.onNodeWithTag("filter-option-$chest").performScrollTo().performClick()
        composeRule.onNodeWithText("[Show]").performClick()
        composeRule.onNodeWithText("No exercises found").assertIsDisplayed()
        composeRule.onNodeWithTag("filter-muscles").performClick()
        capture("muscles-selected")
        composeRule.onNodeWithText("[Clear]").performClick()
        composeRule.onNodeWithText("[Show]").performClick()
        composeRule.onNodeWithText("[3/4 Sit-Up]").assertIsDisplayed()
        // Applied equipment survives leaving and returning to the catalogue.
        composeRule.onNodeWithTag("nav-workout").performClick()
        composeRule.onNodeWithTag("nav-exercises").performClick()
        composeRule.onNodeWithTag("filter-equipment").performClick()
        composeRule.onNodeWithTag("filter-option-$body").assertIsOn()
    }

    @Test
    fun laterOptionsScrollAndBackDiscardsOnlyDraft() {
        showApp()
        composeRule.onNodeWithTag("filter-muscles").performClick()
        val last = runBlocking { repository.exerciseSummaries().flatMap { it.muscleGroups }.maxBy { it.name }.id }
        scrollToOption(last).performClick().assertIsOn()
        composeRule.onNodeWithText("[Show]").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("filter-muscles").performClick()
        scrollToOption(last).assertIsOff()
        composeRule.onNodeWithTag("filter-option-$last").performClick()
        composeRule.onNodeWithText("[Show]").performClick()
        composeRule.onNodeWithTag("filter-muscles").performClick()
        scrollToOption(last).assertIsOn()
        capture("muscles-scrolled")
    }

    @Test
    fun narrowCatalogueKeepsCategoryLabelsOnOneLine() {
        showApp(width = 320.dp)
        assertSingleLine("[Equipment]")
        assertSingleLine("[Muscles]")
        composeRule.onNodeWithTag("filter-equipment").performClick()
        composeRule.onNodeWithText("[Show]").assertIsDisplayed()
        capture("equipment-narrow")
    }

    @Test
    fun doubleTextStacksFiltersAndKeepsOptionsAndActionsReachable() {
        showApp(width = 320.dp, fontScale = 2f)
        capture("catalogue-double-text")
        assertSingleLine("Create")
        assertSingleLine("[Equipment]")
        assertSingleLine("[Muscles]")
        composeRule.onNodeWithTag("filter-equipment").performClick()
        val last = runBlocking { repository.exerciseSummaries().flatMap { it.equipment }.maxBy { it.name }.id }
        scrollToOption(last).performClick().assertIsOn()
        composeRule.onNodeWithText("[Show]").assertIsDisplayed()
        composeRule.onNodeWithText("[Clear]").assertIsDisplayed()
        capture("equipment-double-text")
        composeRule.onNodeWithText("[Show]").performClick()
        assertSingleLine("[Equipment]")
        composeRule.onNodeWithTag("filter-muscles").performClick()
        composeRule.onNodeWithText("[Show]").assertIsDisplayed().performClick()
    }

    @Test
    fun workoutPickerSharesCatalogueAndRetainsSelectionAcrossSearch() {
        val exercises = runBlocking { repository.exerciseSummaries().take(2) }
        openWorkoutPicker()
        composeRule.onNodeWithText("Add selected (0)").assertIsNotEnabled()
        capture("workout-picker")
        composeRule.onNodeWithTag("exercise-row-${exercises[0].exercise.id}").performClick().assertIsOn()
        composeRule.onNodeWithTag("exercise-search").performTextReplacement(exercises[1].exercise.name)
        composeRule.onNodeWithTag("exercise-row-${exercises[1].exercise.id}").performClick().assertIsOn()
        composeRule.onNodeWithText("Add selected (2)").assertIsDisplayed()
        composeRule.onNodeWithTag("exercise-search").performTextReplacement("no matching exercise")
        composeRule.onNodeWithText("No exercises found").assertIsDisplayed()
        composeRule.onNodeWithText("Add selected (2)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Clear search").performClick()
        composeRule.onNodeWithTag("exercise-row-${exercises[0].exercise.id}").assertIsOn()
        composeRule.onNodeWithTag("exercise-row-${exercises[1].exercise.id}").assertIsOn()
        composeRule.onNodeWithTag("filter-equipment").performClick()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("catalogue-filter-panel").assertDoesNotExist()
        capture("workout-picker-selected")
        val workoutId = runBlocking { repository.activeWorkout()!!.id }
        assertTrue(runBlocking { repository.workoutDetails(workoutId)!!.exercises.isEmpty() })
        composeRule.onNodeWithText("Add selected (2)").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.workoutDetails(workoutId)!!.exercises.size == 2 }
        }
        assertEquals(
            exercises.map { it.exercise.id },
            runBlocking { repository.workoutDetails(workoutId)!!.exercises.map { it.workoutExercise.exerciseId } },
        )
    }

    @Test
    fun narrowDoubleTextPickerKeepsFiltersSelectionAndCancelReachable() {
        val first = runBlocking { repository.exerciseSummaries().first().exercise.id }
        openWorkoutPicker(width = 320.dp, fontScale = 2f)
        assertSingleLine("[Equipment]")
        assertSingleLine("[Muscles]")
        composeRule.onNodeWithTag("exercise-row-$first").performClick().assertIsOn()
        composeRule.onNodeWithText("Add selected (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        capture("workout-picker-double-text")
        composeRule.onNodeWithTag("filter-equipment").performClick()
        val last = runBlocking { repository.exerciseSummaries().flatMap { it.equipment }.maxBy { it.name }.id }
        scrollToOption(last).performClick().assertIsOn()
        composeRule.onNodeWithText("[Show]").assertIsDisplayed()
        composeRule.onNodeWithText("Add selected (1)").assertIsDisplayed()
        capture("workout-picker-filter-double-text")
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("catalogue-filter-panel").assertDoesNotExist()
        composeRule.onNodeWithTag("exercise-row-$first").assertIsOn()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { repository.workoutDetails(repository.activeWorkout()!!.id)!!.exercises.isEmpty() })
    }

    private fun openWorkoutPicker(width: Dp? = null, fontScale: Float = 1f) {
        showApp(width, fontScale)
        composeRule.onNodeWithTag("nav-workout").performClick()
        composeRule.onNodeWithText("Start empty workout").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add Exercise", ignoreCase = true).performScrollTo().performClick()
        val first = runBlocking { repository.exerciseSummaries().first().exercise.id }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("exercise-row-$first").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showApp(width: Dp? = null, fontScale: Float = 1f) {
        val firstExerciseId = runBlocking { repository.exerciseSummaries().first().exercise.id }
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
                            appState = remember { GymAppState(repository, scope).apply { showExercises() } }
                            GymApp(appState)
                        }
                    }
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("exercise-search").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("exercise-row-$firstExerciseId")
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollToOption(id: String): SemanticsNodeInteraction {
        composeRule.onNodeWithTag("catalogue-filter-options").performScrollToNode(hasTestTag("filter-option-$id"))
        return composeRule.onNodeWithTag("filter-option-$id")
    }

    private fun assertSingleLine(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("$text must fit its label", 1, results.single().lineCount)
        val layout = results.single()
        // A shrink-wrapped Text can keep a wider cached paragraph; inspect its actual line bounds.
        assertFalse("$text must not be ellipsized", layout.isLineEllipsized(0))
        assertTrue("$text must fit horizontally", layout.getLineRight(0) <= layout.size.width + 1f)
        assertTrue("$text must fit vertically", layout.getLineBottom(0) <= layout.size.height + 1f)
    }

    private fun capture(name: String) {
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        val directory = File(context.filesDir, "catalogue-appearance").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
