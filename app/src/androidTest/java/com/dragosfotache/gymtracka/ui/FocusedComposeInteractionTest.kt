package com.dragosfotache.gymtracka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutPill
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutScreen
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutState
import com.dragosfotache.gymtracka.ui.activeworkout.RestTimerBar
import com.dragosfotache.gymtracka.ui.exercises.CreateExerciseScreen
import com.dragosfotache.gymtracka.ui.exercises.ExerciseCatalogueState
import com.dragosfotache.gymtracka.ui.exercises.ExerciseCreationState
import com.dragosfotache.gymtracka.ui.exercises.ExercisePickerScreen
import com.dragosfotache.gymtracka.ui.exercises.ExerciseReplacementPickerScreen
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorScreen
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorState
import com.dragosfotache.gymtracka.ui.routines.RoutineListState
import com.dragosfotache.gymtracka.ui.routines.RoutineSection
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryDetailScreen
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryDetailState
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryScreen
import com.dragosfotache.gymtracka.ui.workouts.WorkoutHistoryState
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FocusedComposeInteractionTest : GymDatabaseTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private val stateHolderScopes = mutableListOf<CoroutineScope>()

    private fun trackScope(scope: CoroutineScope): CoroutineScope {
        stateHolderScopes += scope
        return scope
    }

    @After
    fun cancelStateHolderScopes() {
        stateHolderScopes.forEach { it.cancel() }
        stateHolderScopes.clear()
    }

    @Test
    fun catalogueSearchIsLiveAndFilterDraftAppliesOnlyOnShowResults() = runTest {
        insertMuscleGroups(
            com.dragosfotache.gymtracka.data.gym.MuscleGroupEntity("chest", "chest", "Chest"),
            com.dragosfotache.gymtracka.data.gym.MuscleGroupEntity("legs", "legs", "Legs"),
        )
        insertEquipment(
            equipmentFixture("barbell", "Barbell", "weight"),
            equipmentFixture("machine", "Machine", "weight"),
        )
        val pressId = repository.createCustomExercise(
            name = "Alpha Press",
            primaryMuscleGroupId = "chest",
            secondaryMuscleGroupIds = emptySet(),
            equipmentId = "barbell",
            category = "strength",
            instructions = "",
        )
        val rowId = repository.createCustomExercise(
            name = "Beta Row",
            primaryMuscleGroupId = "legs",
            secondaryMuscleGroupIds = emptySet(),
            equipmentId = "machine",
            category = "strength",
            instructions = "",
        )
        lateinit var state: ExerciseCatalogueState

        composeRule.setContent {
            val scope = trackScope(rememberCoroutineScope())
            MaterialTheme {
                state = remember { ExerciseCatalogueState(repository, scope) }
                ExercisePickerScreen(
                    workoutId = "workout",
                    onExercisesAdded = {},
                    onCancel = {},
                    state = state,
                )
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("exercise-row-$pressId").fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithTag("exercise-search").performTextInput("Alpha")
        composeRule.onNodeWithText("Alpha Press").assertIsDisplayed()
        composeRule.onAllNodesWithText("Beta Row").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Clear search").performClick()
        composeRule.onNodeWithTag("filter-equipment").performClick()
        composeRule.onNodeWithTag("filter-option-barbell").performClick()
        composeRule.runOnIdle { assertTrue(state.selectedEquipmentIds.isEmpty()) }
        composeRule.onNodeWithText("Show results").performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(setOf("barbell"), state.selectedEquipmentIds) }
        composeRule.onNodeWithText("Alpha Press").assertIsDisplayed()
        composeRule.onAllNodesWithText("Beta Row").assertCountEquals(0)
        assertTrue(pressId.isNotBlank())
        assertTrue(rowId.isNotBlank())
    }

    @Test
    fun exercisePickerSelectionCommitsToWorkout() = runTest {
        val firstExerciseId = repository.createCustomExercise("Bench", null, emptySet(), null, "strength", "")
        val secondExerciseId = repository.createCustomExercise("Cable Row", null, emptySet(), null, "strength", "")
        insertWorkoutAggregate(workoutFixture(id = "picker-workout"))
        lateinit var state: ExerciseCatalogueState
        val addedCount = AtomicInteger()

        composeRule.setContent {
            val scope = trackScope(rememberCoroutineScope())
            MaterialTheme {
                state = remember { ExerciseCatalogueState(repository, scope) }
                ExercisePickerScreen(
                    workoutId = "picker-workout",
                    onExercisesAdded = { addedCount.incrementAndGet() },
                    onCancel = {},
                    state = state,
                )
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("exercise-row-$firstExerciseId").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("exercise-row-$firstExerciseId").performClick()
        composeRule.onNodeWithTag("exercise-row-$secondExerciseId").performClick()
        composeRule.onNodeWithText("Add selected (2)").performClick()
        composeRule.waitUntil {
            addedCount.get() == 1 &&
                runBlocking { repository.workoutDetails("picker-workout")?.exercises?.size == 2 }
        }
        composeRule.runOnIdle { assertEquals(1, addedCount.get()) }
        val persisted = repository.workoutDetails("picker-workout")!!
        assertEquals(listOf(firstExerciseId, secondExerciseId), persisted.exercises.map { it.workoutExercise.exerciseId })
        assertEquals(listOf(0, 1), persisted.exercises.map { it.workoutExercise.position })
        persisted.exercises.forEach { entry ->
            assertEquals(1, entry.sets.size)
            val initialSet = entry.sets.single()
            assertEquals(0, initialSet.position)
            assertEquals(null, initialSet.weightKg)
            assertEquals(null, initialSet.repetitions)
            assertEquals(null, initialSet.restSeconds)
            assertEquals(false, initialSet.isCompleted)
        }
    }

    @Test
    fun cancellingPickerLeavesWorkoutUnchanged() = runTest {
        val exerciseId = repository.createCustomExercise("Overhead Press", null, emptySet(), null, "strength", "")
        insertWorkoutAggregate(workoutFixture(id = "cancel-picker-workout"))
        val cancelCount = AtomicInteger()
        val addCount = AtomicInteger()
        composeRule.setContent {
            val scope = trackScope(rememberCoroutineScope())
            MaterialTheme {
                val state = remember { ExerciseCatalogueState(repository, scope) }
                ExercisePickerScreen(
                    workoutId = "cancel-picker-workout",
                    onExercisesAdded = { addCount.incrementAndGet() },
                    onCancel = { cancelCount.incrementAndGet() },
                    state = state,
                )
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("exercise-row-$exerciseId").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("exercise-row-$exerciseId").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(1, cancelCount.get())
            assertEquals(0, addCount.get())
        }
        assertTrue(repository.workoutDetails("cancel-picker-workout")!!.exercises.isEmpty())
    }

    @Test
    fun replacementPickerUpdatesRealWorkoutEntryAndPreservesSet() = runTest {
        val oldExerciseId = repository.createCustomExercise("Bench", null, emptySet(), null, "strength", "")
        val secondExerciseId = repository.createCustomExercise("Cable Row", null, emptySet(), null, "strength", "")
        insertWorkoutAggregate(
            workoutFixture(id = "replacement-workout"),
            listOf(
                workoutExerciseFixture(
                    id = "entry-to-replace",
                    workoutId = "replacement-workout",
                    exerciseId = oldExerciseId,
                    position = 0,
                    notes = "keep notes",
                    restDurationSeconds = 45,
                ),
            ),
            listOf(
                workoutSetFixture(
                    id = "preserved-set",
                    workoutExerciseId = "entry-to-replace",
                    position = 0,
                    weightKg = 77.5,
                    repetitions = 9,
                    isCompleted = true,
                    restSeconds = 23,
                ),
            ),
        )
        val replacedCount = AtomicInteger()
        composeRule.setContent {
            val scope = trackScope(rememberCoroutineScope())
            MaterialTheme {
                val state = remember { ExerciseCatalogueState(repository, scope) }
                ExerciseReplacementPickerScreen(
                    workoutExerciseId = "entry-to-replace",
                    onExerciseReplaced = { replacedCount.incrementAndGet() },
                    onCancel = {},
                    state = state,
                )
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("exercise-row-$secondExerciseId").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("exercise-row-$secondExerciseId").performClick()
        composeRule.onNodeWithText("Replace").performClick()
        composeRule.waitUntil {
            replacedCount.get() == 1 && runBlocking {
                repository.workoutDetails("replacement-workout")?.exercises?.singleOrNull()
                    ?.workoutExercise?.exerciseId == secondExerciseId
            }
        }
        composeRule.runOnIdle { assertEquals(1, replacedCount.get()) }
        val replaced = repository.workoutDetails("replacement-workout")!!.exercises.single()
        assertEquals("entry-to-replace", replaced.workoutExercise.id)
        assertEquals("replacement-workout", replaced.workoutExercise.workoutId)
        assertEquals(secondExerciseId, replaced.workoutExercise.exerciseId)
        assertEquals(0, replaced.workoutExercise.position)
        assertEquals("keep notes", replaced.workoutExercise.notes)
        assertEquals(45, replaced.workoutExercise.restDurationSeconds)
        assertEquals(1, replaced.sets.size)
        val preservedSet = replaced.sets.single()
        assertEquals("preserved-set", preservedSet.id)
        assertEquals("entry-to-replace", preservedSet.workoutExerciseId)
        assertEquals(0, preservedSet.position)
        assertEquals(77.5, preservedSet.weightKg!!, 0.0)
        assertEquals(9, preservedSet.repetitions)
        assertEquals(true, preservedSet.isCompleted)
        assertEquals(23, preservedSet.restSeconds)
    }

    @Test
    fun customExerciseRequiresNameAndConfirmsDiscardOnBack() = runTest {
        val backCount = AtomicInteger()
        val savedCount = AtomicInteger()
        composeRule.setContent {
            val scope = trackScope(rememberCoroutineScope())
            MaterialTheme {
                val state = remember { ExerciseCreationState(null, repository, scope) }
                CreateExerciseScreen(
                    onBack = { backCount.incrementAndGet() },
                    onSaved = { savedCount.incrementAndGet() },
                    state = state,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Exercise name required").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, savedCount.get()) }
        assertTrue(repository.exercises().none { it.isUserCreated })
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithTag("exercise-field-name").performTextInput("Custom Press")
        composeRule.onNodeWithContentDescription("Back to exercises").performClick()
        composeRule.onNodeWithText("Discard exercise?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag("exercise-field-name").assertTextEquals("Custom Press")
        composeRule.runOnIdle {
            assertEquals(0, backCount.get())
            assertEquals(0, savedCount.get())
        }
        composeRule.onNodeWithContentDescription("Back to exercises").performClick()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.runOnIdle {
            assertEquals(1, backCount.get())
            assertEquals(0, savedCount.get())
        }
        assertTrue(repository.exercises().none { it.isUserCreated })
    }

    @Test
    fun routineEditorValidatesAndExposesAddSetAndDeleteControls() {
        val exerciseId = runBlocking { repository.createCustomExercise("Squat", null, emptySet(), null, "strength", "") }
        runBlocking {
            insertRoutineAggregate(
                RoutineEntity("routine-ui", "Leg day", isDraft = true),
                listOf(RoutineExerciseEntity("routine-entry", "routine-ui", exerciseId, 0)),
                listOf(RoutineSetEntity("routine-set", "routine-entry", 0, 8, 60.0)),
            )
        }
        val scope = trackScope(MainScope())
        val state = RoutineEditorState("routine-ui", repository, scope)
        val addExerciseCount = AtomicInteger()
        val savedCount = AtomicInteger()
        try {
            composeRule.waitUntil(5_000) { state.details != null }
            composeRule.setContent {
                MaterialTheme {
                    RoutineEditorScreen(
                        routineId = "routine-ui",
                        onAddExercise = { addExerciseCount.incrementAndGet() },
                        onCancel = {},
                        onSaved = { savedCount.incrementAndGet() },
                        state = state,
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("routine-name-input").assertIsDisplayed()
            composeRule.onNodeWithTag("routine-name-input").performTextReplacement("")
            composeRule.onNodeWithText("Save").performClick()
            composeRule.onNodeWithText("Routine name required").assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(0, savedCount.get()) }
            assertTrue(runBlocking { repository.routineDetails("routine-ui")!!.routine.isDraft })
            composeRule.onNodeWithText("OK").performClick()
            composeRule.onNodeWithTag("routine-name-input").performTextInput("Leg day revised")
            composeRule.onNodeWithText("Add Exercise").performClick()
            composeRule.runOnIdle { assertEquals(1, addExerciseCount.get()) }

            composeRule.onNodeWithText("Add Set").performClick()
            composeRule.waitUntil { runBlocking { repository.routineDetails("routine-ui")?.exercises?.single()?.sets?.size == 2 } }
            val afterAdd = runBlocking { repository.routineDetails("routine-ui")!! }
            val addedSets = afterAdd.exercises.single().sets
            assertEquals(listOf(0, 1), addedSets.map { it.position })
            addedSets.forEach { set ->
                assertEquals(60.0, set.weightKg!!, 0.0)
                assertEquals(8, set.repetitions)
            }
            composeRule.onNodeWithTag("routine-delete-set-routine-set").performClick()
            composeRule.waitUntil {
                runBlocking {
                    val details = repository.routineDetails("routine-ui")
                    details?.exercises?.single()?.sets?.size == 1 &&
                        details.routine.name == "Leg day revised"
                }
            }
            val afterDelete = runBlocking { repository.routineDetails("routine-ui")!! }
            assertEquals("Leg day revised", afterDelete.routine.name)
            val survivingSet = afterDelete.exercises.single().sets.single()
            assertTrue(survivingSet.id != "routine-set")
            assertEquals(0, survivingSet.position)
            assertEquals(60.0, survivingSet.weightKg!!, 0.0)
            assertEquals(8, survivingSet.repetitions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun routineWithoutExercisesCannotBeSaved() {
        runBlocking { insertRoutineAggregate(RoutineEntity("empty-routine", "Empty plan")) }
        val scope = trackScope(MainScope())
        val state = RoutineEditorState("empty-routine", repository, scope)
        try {
            composeRule.waitUntil(5_000) { state.details != null }
            composeRule.setContent {
                MaterialTheme {
                    RoutineEditorScreen("empty-routine", onAddExercise = {}, onCancel = {}, onSaved = {}, state = state)
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("routine-name-input").assertIsDisplayed()
            composeRule.onNodeWithText("Save").assertIsNotEnabled()
            assertTrue(runBlocking { repository.routineDetails("empty-routine")!!.exercises.isEmpty() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun routineListDeleteShowsConfirmationAndRemovesRoutine() {
        runBlocking { insertRoutineAggregate(RoutineEntity("routine-delete", "Temporary plan")) }
        val scope = trackScope(MainScope())
        val state = RoutineListState(repository, scope)
        try {
            composeRule.waitUntil(5_000) { state.routines != null }
            composeRule.setContent {
                MaterialTheme { RoutineSection({}, {}, {}, {}, state) }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Routine options for Temporary plan").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Routine options for Temporary plan").performClick()
            composeRule.onNodeWithText("[Delete]").performClick()
            composeRule.onNodeWithText("Delete routine?").assertIsDisplayed()
            composeRule.onNodeWithText("Delete routine").performClick()
            composeRule.waitUntil { runBlocking { repository.routineDetails("routine-delete") == null } }
            assertTrue(runBlocking { repository.savedRoutineDetails().isEmpty() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun activeWorkoutEditsCompletesAndConfirmsExerciseDeletion() = runTest {
        val exerciseId = repository.createCustomExercise("Bench Press", null, emptySet(), null, "strength", "")
        insertWorkoutAggregate(
            workoutFixture(id = "active-ui", name = "Upper day"),
            listOf(workoutExerciseFixture(id = "active-entry", workoutId = "active-ui", exerciseId = exerciseId)),
            listOf(workoutSetFixture(id = "active-set", workoutExerciseId = "active-entry", weightKg = 50.0, repetitions = 8)),
        )
        lateinit var state: ActiveWorkoutState
        val restStartPayloads = mutableListOf<Pair<String, Int>>()

        composeRule.setContent {
            val scope = trackScope(rememberCoroutineScope())
            MaterialTheme {
                state = remember { ActiveWorkoutState("active-ui", 1_000L, repository, scope) }
                ActiveWorkoutScreen(
                    workoutId = "active-ui",
                    workoutStartMillis = 1_000L,
                    activeRestTimer = null,
                    restTimerRemainingSeconds = 0,
                    restTimerCompletionKey = 0,
                    onAddExercise = {},
                    onCollapse = {},
                    onWorkoutNameChanged = {},
                    onFinishWorkout = {},
                    onCancelWorkout = {},
                    onRestTimerStarted = { set, seconds -> restStartPayloads += set.id to seconds },
                    onRestTimerEnded = {},
                    onRestTimerAdjusted = { _, _ -> },
                    onExerciseReplacementRequested = {},
                    state = state,
                )
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("workout-set-weight-active-set").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("workout-set-weight-active-set").performTextReplacement("62.5")
        composeRule.onNodeWithTag("workout-set-reps-active-set").performTextReplacement("6")
        composeRule.onNodeWithTag("workout-set-complete-active-set").performClick()
        composeRule.waitUntil { runBlocking { repository.workoutDetails("active-ui")!!.exercises.single().sets.single().isCompleted } }
        val completedSet = repository.workoutDetails("active-ui")!!.exercises.single().sets.single()
        assertEquals(62.5, completedSet.weightKg!!, 0.0)
        assertEquals(6, completedSet.repetitions)
        assertTrue(completedSet.isCompleted)
        composeRule.waitUntil(5_000) { composeRule.runOnIdle { restStartPayloads.isNotEmpty() } }
        composeRule.runOnIdle { assertEquals(listOf("active-set" to 30), restStartPayloads) }

        composeRule.onNodeWithContentDescription("Options for Bench Press").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete exercise?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        val afterCancel = repository.workoutDetails("active-ui")!!
        val retainedEntry = afterCancel.exercises.single()
        assertEquals("active-entry", retainedEntry.workoutExercise.id)
        val retainedSet = retainedEntry.sets.single()
        assertEquals("active-set", retainedSet.id)
        assertEquals(62.5, retainedSet.weightKg!!, 0.0)
        assertEquals(6, retainedSet.repetitions)
        assertTrue(retainedSet.isCompleted)
        composeRule.onNodeWithContentDescription("Options for Bench Press").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitUntil { runBlocking { repository.workoutDetails("active-ui")!!.exercises.isEmpty() } }
        assertTrue(database.workoutDao().getWorkoutSets("active-entry").isEmpty())
    }

    @Test
    fun invalidatingCompletedSetShowsIncompleteAndStartsNoTimer() = runTest {
        val exerciseId = repository.createCustomExercise("Bench Press", null, emptySet(), null, "strength", "")
        insertWorkoutAggregate(
            workoutFixture(id = "invalidate-ui", name = "Upper day"),
            listOf(workoutExerciseFixture(id = "invalidate-entry", workoutId = "invalidate-ui", exerciseId = exerciseId)),
            listOf(workoutSetFixture(id = "invalidate-set", workoutExerciseId = "invalidate-entry", weightKg = 50.0, repetitions = 8)),
        )
        lateinit var state: ActiveWorkoutState
        val restStartPayloads = mutableListOf<Pair<String, Int>>()
        val cancelledSetIds = mutableListOf<String>()

        composeRule.setContent {
            val scope = trackScope(rememberCoroutineScope())
            MaterialTheme {
                state = remember { ActiveWorkoutState("invalidate-ui", 1_000L, repository, scope) }
                ActiveWorkoutScreen(
                    workoutId = "invalidate-ui",
                    workoutStartMillis = 1_000L,
                    activeRestTimer = null,
                    restTimerRemainingSeconds = 0,
                    restTimerCompletionKey = 0,
                    onAddExercise = {},
                    onCollapse = {},
                    onWorkoutNameChanged = {},
                    onFinishWorkout = {},
                    onCancelWorkout = {},
                    onRestTimerStarted = { set, seconds -> restStartPayloads += set.id to seconds },
                    onRestTimerEnded = {},
                    onRestTimerAdjusted = { _, _ -> },
                    onRestTimerCancelled = { cancelledSetIds += it },
                    onExerciseReplacementRequested = {},
                    state = state,
                )
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("workout-set-weight-invalidate-set").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("workout-set-complete-invalidate-set").performClick()
        composeRule.waitUntil { runBlocking { repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single().isCompleted } }
        composeRule.waitUntil(5_000) { composeRule.runOnIdle { restStartPayloads.isNotEmpty() } }

        // Clearing the weight invalidates the set: persistence goes incomplete,
        // the refreshed row shows a disabled check, and no phantom timer starts.
        composeRule.onNodeWithTag("workout-set-weight-invalidate-set").performTextReplacement("")
        composeRule.waitUntil(5_000) {
            val stored = runBlocking {
                repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single()
            }
            if (!stored.isCompleted && stored.weightKg == null) {
                try {
                    composeRule.onNodeWithTag("workout-set-complete-invalidate-set")
                        .assertIsNotEnabled()
                    true
                } catch (_: AssertionError) {
                    false
                }
            } else {
                false
            }
        }
        composeRule.runOnIdle { assertEquals(listOf("invalidate-set" to 30), restStartPayloads) }

        // Unchecking a completed set reports cancellation for exactly that set.
        composeRule.onNodeWithTag("workout-set-weight-invalidate-set").performTextReplacement("50")
        composeRule.waitUntil {
            runBlocking {
                repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single().weightKg == 50.0
            }
        }
        composeRule.onNodeWithTag("workout-set-complete-invalidate-set").performClick()
        composeRule.waitUntil { runBlocking { repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single().isCompleted } }
        composeRule.onNodeWithTag("workout-set-complete-invalidate-set").performClick()
        composeRule.waitUntil { runBlocking { !repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single().isCompleted } }
        composeRule.runOnIdle { assertEquals(listOf("invalidate-set"), cancelledSetIds) }

        // Negative repetitions disable completion and never start a timer.
        composeRule.onNodeWithTag("workout-set-reps-invalidate-set").performTextReplacement("-2")
        composeRule.waitUntil {
            runBlocking {
                repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single().repetitions == null
            }
        }
        composeRule.onNodeWithTag("workout-set-complete-invalidate-set").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(2, restStartPayloads.size) }

        // Valid zero repetitions stay allowed, and typing valid values never
        // auto-checks: the set remains incomplete until the checkbox is tapped.
        composeRule.onNodeWithTag("workout-set-reps-invalidate-set").performTextReplacement("0")
        composeRule.waitUntil {
            runBlocking {
                repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single().repetitions == 0
            }
        }
        composeRule.runOnIdle {
            assertEquals(
                false,
                runBlocking {
                    repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single().isCompleted
                },
            )
        }
        composeRule.onNodeWithTag("workout-set-complete-invalidate-set").assertIsEnabled()
        composeRule.onNodeWithTag("workout-set-complete-invalidate-set").performClick()
        composeRule.waitUntil { runBlocking { repository.workoutDetails("invalidate-ui")!!.exercises.single().sets.single().isCompleted } }
        composeRule.runOnIdle { assertEquals(3, restStartPayloads.size) }
    }

    @Test
    fun activeWorkoutSetSwipeDeletesPersistedSet() = runTest {
        val exerciseId = repository.createCustomExercise("Deadlift", null, emptySet(), null, "strength", "")
        insertWorkoutAggregate(
            workoutFixture(id = "swipe-ui"),
            listOf(workoutExerciseFixture(id = "swipe-entry", workoutId = "swipe-ui", exerciseId = exerciseId)),
            listOf(workoutSetFixture(id = "swipe-set", workoutExerciseId = "swipe-entry")),
        )
        composeRule.setContent {
            val scope = trackScope(rememberCoroutineScope())
            MaterialTheme {
                val state = remember { ActiveWorkoutState("swipe-ui", 1_000L, repository, scope) }
                ActiveWorkoutScreen(
                    workoutId = "swipe-ui",
                    workoutStartMillis = 1_000L,
                    activeRestTimer = null,
                    restTimerRemainingSeconds = 0,
                    restTimerCompletionKey = 0,
                    onAddExercise = {}, onCollapse = {}, onWorkoutNameChanged = {},
                    onFinishWorkout = {}, onCancelWorkout = {}, onRestTimerStarted = { _, _ -> },
                    onRestTimerEnded = {}, onRestTimerAdjusted = { _, _ -> },
                    onExerciseReplacementRequested = {}, state = state,
                )
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("workout-set-row-swipe-set").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("workout-set-row-swipe-set").performTouchInput { swipeLeft() }
        composeRule.waitUntil { runBlocking { repository.workoutDetails("swipe-ui")?.exercises?.singleOrNull()?.sets?.isEmpty() == true } }
        val detailsAfterSwipe = repository.workoutDetails("swipe-ui")!!
        assertEquals("swipe-ui", detailsAfterSwipe.workout.id)
        val survivingEntry = detailsAfterSwipe.exercises.single()
        assertEquals("swipe-entry", survivingEntry.workoutExercise.id)
        assertEquals(exerciseId, survivingEntry.workoutExercise.exerciseId)
        assertTrue(database.workoutDao().getWorkoutSets("swipe-entry").isEmpty())
    }

    @Test
    fun historyActionsSaveRoutinePerformAgainAndConfirmDelete() {
        val exerciseId = runBlocking { repository.createCustomExercise("Press", null, emptySet(), null, "strength", "") }
        runBlocking {
            insertWorkoutAggregate(
                workoutFixture(
                    id = "history-ui",
                    name = "Push day",
                    startedAtMillis = 1_000L,
                    finishedAtMillis = 61_000L,
                    status = "finished",
                ),
                listOf(workoutExerciseFixture(id = "history-entry", workoutId = "history-ui", exerciseId = exerciseId)),
                listOf(workoutSetFixture(id = "history-set", workoutExerciseId = "history-entry", isCompleted = true)),
            )
        }
        val scope = trackScope(MainScope())
        val state = WorkoutHistoryState(repository, scope)
        try {
            composeRule.waitUntil(5_000) { state.workouts != null }
            val selectedIds = mutableListOf<String>()
            val performedAgainIds = mutableListOf<String>()

            composeRule.setContent {
                MaterialTheme {
                    WorkoutHistoryScreen(
                        onWorkoutSelected = { selectedIds += it },
                        onPerformAgain = { performedAgainIds += it },
                        state = state,
                    )
                }
            }
            composeRule.waitUntil { composeRule.onAllNodesWithTag("history-workout-history-ui").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("history-workout-history-ui").performClick()
            composeRule.runOnIdle { assertEquals(listOf("history-ui"), selectedIds) }

            composeRule.onNodeWithContentDescription("Workout options for Push day").performClick()
            composeRule.onNodeWithText("Save as routine").performClick()
            composeRule.onAllNodesWithTag("history-routine-name-input").assertCountEquals(1)
            composeRule.onNodeWithText("Save").performClick()
            composeRule.waitUntil { runBlocking { repository.savedRoutineDetails().singleOrNull()?.routine?.name == "Push day" } }
            val savedRoutine = runBlocking { repository.savedRoutineDetails().single() }
            assertEquals("Push day", savedRoutine.routine.name)
            assertTrue(!savedRoutine.routine.isDraft)
            assertEquals(1, savedRoutine.exercises.size)
            val savedRoutineExercise = savedRoutine.exercises.single()
            assertEquals(exerciseId, savedRoutineExercise.routineExercise.exerciseId)
            assertEquals(0, savedRoutineExercise.routineExercise.position)
            assertEquals(30, savedRoutineExercise.routineExercise.restDurationSeconds)
            assertEquals(1, savedRoutineExercise.sets.size)
            val savedRoutineSet = savedRoutineExercise.sets.single()
            assertEquals(0, savedRoutineSet.position)
            assertEquals(50.0, savedRoutineSet.weightKg!!, 0.0)
            assertEquals(8, savedRoutineSet.repetitions)
            assertTrue(savedRoutineExercise.routineExercise.id != "history-entry")
            assertTrue(savedRoutineSet.id != "history-set")

            composeRule.onNodeWithContentDescription("Workout options for Push day").performClick()
            composeRule.onNodeWithText("Perform again").performClick()
            composeRule.runOnIdle { assertEquals(listOf("history-ui"), performedAgainIds) }

            composeRule.onNodeWithContentDescription("Workout options for Push day").performClick()
            composeRule.onNodeWithText("Delete").performClick()
            composeRule.onNodeWithText("Delete workout?").assertIsDisplayed()
            composeRule.onNodeWithText("Cancel").performClick()
            val retainedWorkout = runBlocking { repository.workoutDetails("history-ui")!! }
            assertEquals("history-ui", retainedWorkout.workout.id)
            val retainedEntry = retainedWorkout.exercises.single()
            assertEquals("history-entry", retainedEntry.workoutExercise.id)
            val retainedSet = retainedEntry.sets.single()
            assertEquals("history-set", retainedSet.id)
            assertTrue(retainedSet.isCompleted)
            assertEquals(50.0, retainedSet.weightKg!!, 0.0)
            assertEquals(8, retainedSet.repetitions)
            composeRule.onNodeWithContentDescription("Workout options for Push day").performClick()
            composeRule.onNodeWithText("Delete").performClick()
            composeRule.onNodeWithText("Delete").performClick()
            composeRule.waitUntil { runBlocking { repository.workoutDetails("history-ui") == null } }
            composeRule.waitUntil { state.workouts?.isEmpty() == true }
            assertTrue(runBlocking { database.workoutDao().getWorkoutExercises("history-ui").isEmpty() })
            assertTrue(runBlocking { database.workoutDao().getWorkoutSets("history-entry").isEmpty() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun historyDetailSupportsBackAndPerformAgainNavigation() {
        val exerciseId = runBlocking {
            repository.createCustomExercise("Press", null, emptySet(), null, "strength", "")
        }
        runBlocking {
            insertWorkoutAggregate(
                workoutFixture(id = "history-detail", name = "Push day", finishedAtMillis = 61_000L, status = "finished"),
                listOf(workoutExerciseFixture(id = "detail-entry", workoutId = "history-detail", exerciseId = exerciseId)),
                listOf(workoutSetFixture(id = "detail-set", workoutExerciseId = "detail-entry", isCompleted = true)),
            )
        }
        val scope = trackScope(MainScope())
        val detailState = WorkoutHistoryDetailState("history-detail", repository, scope)
        try {
            composeRule.waitUntil(5_000) { detailState.details != null }
            val backCount = AtomicInteger()
            val performedAgainIds = mutableListOf<String>()
            composeRule.setContent {
                MaterialTheme {
                    WorkoutHistoryDetailScreen(
                        workoutId = "history-detail",
                        onBack = { backCount.incrementAndGet() },
                        onPerformAgain = { performedAgainIds += it },
                        state = detailState,
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Perform again").assertIsDisplayed()
            composeRule.onNodeWithText("Perform again").performClick()
            composeRule.runOnIdle { assertEquals(listOf("history-detail"), performedAgainIds) }
            composeRule.onNodeWithContentDescription("Back to workouts").performClick()
            composeRule.runOnIdle { assertEquals(1, backCount.get()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun activeWorkoutPillAndRestTimerBarRespondToActions() {
        var resumeCount = 0
        var discardCount = 0
        val adjustments = mutableListOf<Int>()
        var skipCount = 0
        val timer = ActiveRestTimer("set", 30, 0L, 1L)

        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    ActiveWorkoutPill(
                        workout = WorkoutEntity("pill-workout", "Collapsed workout", 0L),
                        restTimerRemainingSeconds = 12,
                        onClick = { resumeCount++ },
                        onDiscard = { discardCount++ },
                    )
                    RestTimerBar(
                        timer = timer,
                        remainingSeconds = 12,
                        onSkip = { skipCount++ },
                        onDurationAdjusted = { adjustments += it },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Rest 00:12").assertIsDisplayed()
        composeRule.onNodeWithText("00:12").assertIsDisplayed()
        composeRule.onNodeWithTag("active-workout-pill").performClick()
        composeRule.onNodeWithContentDescription("Discard current workout").performClick()
        composeRule.onNodeWithTag("rest-timer-minus").performClick()
        composeRule.onNodeWithTag("rest-timer-plus").performClick()
        composeRule.onNodeWithTag("rest-timer-skip").performClick()
        composeRule.runOnIdle {
            assertEquals(1, resumeCount)
            assertEquals(1, discardCount)
            assertEquals(1, skipCount)
            assertEquals(listOf(-15, 15), adjustments)
        }
    }
}
