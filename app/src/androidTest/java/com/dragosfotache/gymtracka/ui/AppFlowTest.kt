package com.dragosfotache.gymtracka.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.ui.util.formatKilograms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFlowTest : AppFlowTestSupport() {
    @Test
    fun repeatAndSafelyReplaceAndDiscardActiveWorkoutJourney() {
        runBlocking {
            insertExercises(
                exerciseFixture(id = "flow-press", name = "Press"),
                exerciseFixture(id = "flow-squat", name = "Squat"),
            )
            insertWorkoutAggregate(
                workout = workoutFixture(
                    id = "flow-history",
                    name = "Previous Day",
                    startedAtMillis = 1_000L,
                    finishedAtMillis = 61_000L,
                    status = "finished",
                ),
                exercises = listOf(
                    workoutExerciseFixture(
                        id = "flow-history-entry",
                        workoutId = "flow-history",
                        exerciseId = "flow-press",
                        notes = "controlled",
                        restDurationSeconds = 45,
                    ),
                ),
                sets = listOf(
                    workoutSetFixture(
                        id = "flow-history-set",
                        workoutExerciseId = "flow-history-entry",
                        position = 0,
                        repetitions = 10,
                        weightKg = 40.0,
                        isCompleted = true,
                        restSeconds = 12,
                    ),
                ),
            )
            insertWorkoutAggregate(
                workout = workoutFixture(
                    id = "flow-current",
                    name = "Current Day",
                    startedAtMillis = 2_000L,
                    status = "active",
                ),
                exercises = listOf(
                    workoutExerciseFixture(
                        id = "flow-current-entry",
                        workoutId = "flow-current",
                        exerciseId = "flow-press",
                        notes = "current notes",
                        restDurationSeconds = 25,
                    ),
                ),
                sets = listOf(
                    workoutSetFixture(
                        id = "flow-current-set",
                        workoutExerciseId = "flow-current-entry",
                        position = 0,
                        repetitions = 5,
                        weightKg = 25.0,
                        isCompleted = false,
                        restSeconds = null,
                    ),
                ),
            )
        }
        val sourceBefore = runBlocking { repository.workoutDetails("flow-history")!! }
        val currentBefore = runBlocking { repository.workoutDetails("flow-current")!! }
        setUpGymApp()

        waitForUi("current-workout-discovered") {
            composeRule.onAllNodesWithTag("active-workout-pill").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.activeWorkout()?.id == currentBefore.workout.id }
        }
        composeRule.onNodeWithTag("nav-history").performClick()
        val sourceCardTag = "history-workout-${sourceBefore.workout.id}"
        waitForUi("source-history-card") {
            composeRule.onAllNodesWithTag(sourceCardTag).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Workout options for Previous Day").performClick()
        composeRule.onNodeWithText("[Perform]").performClick()
        val replacePrompt = "Discard the current workout and start a new one?"
        waitForUi("replace-workout-confirmation") {
            composeRule.onAllNodesWithText(replacePrompt).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(replacePrompt).assertIsDisplayed()
        composeRule.onNodeWithText("[Cancel]").performClick()
        waitForUi("replacement-cancelled") {
            composeRule.onAllNodesWithText(replacePrompt).fetchSemanticsNodes().isEmpty() &&
                runBlocking {
                    repository.workoutDetails("flow-current") == currentBefore &&
                        repository.workoutDetails("flow-history") == sourceBefore &&
                        repository.activeWorkout()?.id == currentBefore.workout.id
                }
        }
        assertEquals(currentBefore, runBlocking { repository.workoutDetails("flow-current") })
        assertEquals(sourceBefore, runBlocking { repository.workoutDetails("flow-history") })

        composeRule.onNodeWithContentDescription("Workout options for Previous Day").performClick()
        composeRule.onNodeWithText("[Perform]").performClick()
        waitForUi("replacement-confirmation-reopened") {
            composeRule.onAllNodesWithText(replacePrompt).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("[Yes]").performClick()
        waitForUi("replacement-workout-started") {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    val activeWorkout = repository.activeWorkout() ?: return@runBlocking false
                    activeWorkout.id != currentBefore.workout.id &&
                        repository.workoutDetails(activeWorkout.id)?.workout?.name == "Previous Day"
                }
        }
        val clone = runBlocking { repository.activeWorkout()!! }
        val cloneDetails = runBlocking { repository.workoutDetails(clone.id)!! }
        assertTrue(clone.id != sourceBefore.workout.id)
        assertTrue(clone.id != currentBefore.workout.id)
        assertTrue(clone.startedAtMillis != sourceBefore.workout.startedAtMillis)
        assertTrue(clone.startedAtMillis != currentBefore.workout.startedAtMillis)
        assertEquals("Previous Day", clone.name)
        assertEquals("active", clone.status)
        assertNull(clone.finishedAtMillis)
        assertEquals(1, cloneDetails.exercises.size)
        val cloneEntry = cloneDetails.exercises.single()
        val sourceEntry = sourceBefore.exercises.single()
        assertTrue(cloneEntry.workoutExercise.id != sourceEntry.workoutExercise.id)
        assertEquals(clone.id, cloneEntry.workoutExercise.workoutId)
        assertEquals("flow-press", cloneEntry.workoutExercise.exerciseId)
        assertEquals(0, cloneEntry.workoutExercise.position)
        assertEquals("controlled", cloneEntry.workoutExercise.notes)
        assertEquals(45, cloneEntry.workoutExercise.restDurationSeconds)
        assertEquals(1, cloneEntry.sets.size)
        val cloneSet = cloneEntry.sets.single()
        val sourceSet = sourceEntry.sets.single()
        assertTrue(cloneSet.id != sourceSet.id)
        assertEquals(cloneEntry.workoutExercise.id, cloneSet.workoutExerciseId)
        assertEquals(0, cloneSet.position)
        assertEquals(40.0, cloneSet.weightKg!!, 0.0)
        assertEquals(10, cloneSet.repetitions)
        assertFalse(cloneSet.isCompleted)
        assertNull(cloneSet.restSeconds)
        assertNull(runBlocking { repository.workoutDetails("flow-current") })
        assertTrue(runBlocking { database.workoutDao().getWorkoutExercises("flow-current").isEmpty() })
        currentBefore.exercises.forEach { oldEntry ->
            assertTrue(
                runBlocking { database.workoutDao().getWorkoutSets(oldEntry.workoutExercise.id).isEmpty() },
            )
        }
        assertEquals(sourceBefore, runBlocking { repository.workoutDetails("flow-history") })

        composeRule.onNodeWithContentDescription("Collapse workout").performClick()
        waitForUi("clone-collapsed") {
            composeRule.onAllNodesWithTag("active-workout-pill").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Discard current workout").performClick()
        val discardPrompt = "Are you sure you want to discard the current workout?"
        waitForUi("pill-discard-confirmation") {
            composeRule.onAllNodesWithText(discardPrompt).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(discardPrompt).assertIsDisplayed()
        composeRule.onNodeWithText("[Cancel]").performClick()
        waitForUi("pill-discard-cancelled") {
            composeRule.onAllNodesWithText(discardPrompt).fetchSemanticsNodes().isEmpty() &&
                runBlocking {
                    repository.workoutDetails(clone.id) == cloneDetails &&
                        repository.activeWorkout()?.id == clone.id
                }
        }
        assertEquals(cloneDetails, runBlocking { repository.workoutDetails(clone.id) })
        assertEquals(sourceBefore, runBlocking { repository.workoutDetails("flow-history") })

        composeRule.onNodeWithContentDescription("Discard current workout").performClick()
        waitForUi("pill-discard-confirmation-reopened") {
            composeRule.onAllNodesWithText(discardPrompt).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("[Discard]") and hasAnyAncestor(isDialog())).performClick()
        waitForUi("clone-discarded") {
            composeRule.onAllNodesWithTag("create-routine").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("active-workout-pill").fetchSemanticsNodes().isEmpty() &&
                runBlocking {
                    repository.activeWorkout() == null && repository.workoutDetails(clone.id) == null
                }
        }
        assertTrue(runBlocking { database.workoutDao().getWorkoutExercises(clone.id).isEmpty() })
        cloneDetails.exercises.forEach { oldEntry ->
            assertTrue(
                runBlocking { database.workoutDao().getWorkoutSets(oldEntry.workoutExercise.id).isEmpty() },
            )
        }
        composeRule.onNodeWithTag("nav-history").performClick()
        waitForUi("source-history-remains") {
            composeRule.onAllNodesWithTag(sourceCardTag).fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.workoutDetails("flow-history") == sourceBefore }
        }
        composeRule.onNodeWithTag(sourceCardTag).assertIsDisplayed()
        assertEquals(sourceBefore, runBlocking { repository.workoutDetails("flow-history") })
    }

    @Test
    fun createRoutineAndStartItsWorkoutJourney() {
        runBlocking {
            insertExercises(
                exerciseFixture(id = "flow-press", name = "Press"),
                exerciseFixture(id = "flow-squat", name = "Squat"),
            )
            assertTrue(repository.savedRoutines().isEmpty())
            assertTrue(repository.workouts().isEmpty())
        }
        setUpGymApp()

        waitForUi("workout-home") {
            composeRule.onAllNodesWithTag("create-routine").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create-routine").performClick()
        waitForUi("routine-draft-created") {
            val routineId = editingRoutineId()
            routineId != null &&
                composeRule.onAllNodesWithTag("routine-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.routineDetails(routineId)?.routine?.isDraft == true }
        }
        val routineId = editingRoutineId()
        assertNotNull(routineId)
        val draftDetails = runBlocking { repository.routineDetails(routineId!!) }
        assertNotNull(draftDetails)
        assertTrue(draftDetails!!.routine.isDraft)
        assertTrue(draftDetails.exercises.isEmpty())

        composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Flow Leg Plan")
        waitForUi("routine-name-persisted") {
            runBlocking { repository.routineDetails(routineId!!)?.routine?.name == "Flow Leg Plan" }
        }
        composeRule.onNodeWithText("Add exercise").performClick()
        waitForUi("routine-exercise-picker-open") {
            composeRule.onAllNodesWithTag("exercise-search").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("exercise-row-flow-squat").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exercise-row-flow-squat").performClick()
        composeRule.onNodeWithText("Add selected (1)").performClick()
        waitForUi("squat-added-to-routine") {
            composeRule.onAllNodesWithTag("routine-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    repository.routineDetails(routineId!!)?.exercises?.singleOrNull()
                        ?.routineExercise?.exerciseId == "flow-squat"
                }
        }

        val detailsWithSquat = runBlocking { repository.routineDetails(routineId!!) }
        assertNotNull(detailsWithSquat)
        val templateEntry = detailsWithSquat!!.exercises.single()
        val routineEntryId = templateEntry.routineExercise.id
        assertEquals("flow-squat", templateEntry.routineExercise.exerciseId)
        assertEquals(0, templateEntry.routineExercise.position)
        assertEquals(1, templateEntry.sets.size)
        val routineSetId = templateEntry.sets.single().id
        assertEquals(0, templateEntry.sets.single().position)

        composeRule.onNodeWithTag("routine-set-weight-$routineSetId")
            .performTextReplacement("60")
        waitForUi("routine-weight-persisted") {
            runBlocking {
                repository.routineDetails(routineId!!)?.exercises?.singleOrNull()?.sets
                    ?.singleOrNull()?.weightKg == 60.0
            }
        }
        composeRule.onNodeWithTag("routine-set-reps-$routineSetId")
            .performTextReplacement("5")
        waitForUi("routine-inputs-persisted") {
            runBlocking {
                repository.routineDetails(routineId!!)?.exercises?.singleOrNull()?.sets
                    ?.singleOrNull()?.let { it.weightKg == 60.0 && it.repetitions == 5 } == true
            }
        }

        composeRule.onNodeWithText("Save").performClick()
        val routineCardTag = "routine-card-$routineId"
        waitForUi("routine-saved") {
            composeRule.onAllNodesWithTag(routineCardTag).fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    repository.savedRoutineDetails().singleOrNull()?.let {
                        it.routine.id == routineId && it.routine.name == "Flow Leg Plan" &&
                            !it.routine.isDraft
                    } == true
                }
        }
        composeRule.onNodeWithTag(routineCardTag).assertIsDisplayed()
        val templateBeforeStart = runBlocking { repository.routineDetails(routineId!!)!! }
        assertFalse(templateBeforeStart.routine.isDraft)
        assertEquals("Flow Leg Plan", templateBeforeStart.routine.name)
        assertEquals(1, templateBeforeStart.exercises.size)
        val savedTemplateEntry = templateBeforeStart.exercises.single()
        assertEquals(routineEntryId, savedTemplateEntry.routineExercise.id)
        assertEquals("flow-squat", savedTemplateEntry.routineExercise.exerciseId)
        assertEquals(0, savedTemplateEntry.routineExercise.position)
        assertEquals(1, savedTemplateEntry.sets.size)
        val savedTemplateSet = savedTemplateEntry.sets.single()
        assertEquals(routineSetId, savedTemplateSet.id)
        assertEquals(0, savedTemplateSet.position)
        assertEquals(60.0, savedTemplateSet.weightKg!!, 0.0)
        assertEquals(5, savedTemplateSet.repetitions)

        composeRule.onNodeWithTag("routine-start-$routineId").performClick()
        waitForUi("routine-workout-started") {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    val workout = repository.activeWorkout() ?: return@runBlocking false
                    workout.name == "Flow Leg Plan" &&
                        repository.workoutDetails(workout.id)?.exercises?.singleOrNull()
                            ?.workoutExercise?.exerciseId == "flow-squat"
                }
        }
        composeRule.onNodeWithTag("workout-name-input").assertTextEquals("Flow Leg Plan")
        val activeWorkout = runBlocking { repository.activeWorkout() }
        assertNotNull(activeWorkout)
        val activeDetails = runBlocking { repository.workoutDetails(activeWorkout!!.id) }
        assertNotNull(activeDetails)
        assertEquals("Flow Leg Plan", activeDetails!!.workout.name)
        assertEquals("active", activeDetails.workout.status)
        assertEquals(1, activeDetails.exercises.size)
        val workoutEntry = activeDetails.exercises.single()
        assertEquals("flow-squat", workoutEntry.workoutExercise.exerciseId)
        assertEquals(0, workoutEntry.workoutExercise.position)
        assertEquals(savedTemplateEntry.routineExercise.restDurationSeconds, workoutEntry.workoutExercise.restDurationSeconds)
        assertTrue(activeWorkout!!.id != routineId)
        assertTrue(workoutEntry.workoutExercise.id != routineEntryId)
        assertEquals(1, workoutEntry.sets.size)
        val workoutSet = workoutEntry.sets.single()
        assertEquals(0, workoutSet.position)
        assertEquals(60.0, workoutSet.weightKg!!, 0.0)
        assertEquals(5, workoutSet.repetitions)
        assertFalse(workoutSet.isCompleted)
        assertNull(workoutSet.restSeconds)
        assertTrue(workoutSet.id != routineSetId)

        val savedRoutinesAfterStart = runBlocking { repository.savedRoutineDetails() }
        assertEquals(1, savedRoutinesAfterStart.size)
        assertEquals(templateBeforeStart, runBlocking { repository.routineDetails(routineId!!) })
    }

    @Test
    fun startEditFinishAndInspectWorkoutJourney() {
        runBlocking {
            insertExercises(
                exerciseFixture(id = "flow-press", name = "Press"),
                exerciseFixture(id = "flow-squat", name = "Squat"),
            )
        }
        setUpGymApp()

        waitForUi("workout-home") {
            composeRule.onAllNodesWithText("Start empty workout").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start empty workout").performClick()
        waitForUi("workout-started") {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.activeWorkout() != null }
        }
        val startedWorkout = runBlocking { repository.activeWorkout() }
        assertNotNull(startedWorkout)
        val workoutId = startedWorkout!!.id

        composeRule.onNodeWithTag("workout-name-input").performTextReplacement("Flow Strength")
        waitForUi("workout-name-persisted") {
            runBlocking { repository.activeWorkout()?.name == "Flow Strength" }
        }
        composeRule.onNodeWithText("Add Exercise", ignoreCase = true).performClick()
        waitForUi("exercise-picker-open") {
            composeRule.onAllNodesWithTag("exercise-search").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("exercise-row-flow-press").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exercise-row-flow-press").performClick()
        composeRule.onNodeWithText("Add selected (1)").performClick()
        waitForUi("press-added-to-workout") {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    repository.workoutDetails(workoutId)?.exercises?.singleOrNull()
                        ?.workoutExercise?.exerciseId == "flow-press"
                }
        }
        composeRule.onNodeWithTag("workout-name-input").assertTextEquals("Flow Strength")

        val initialDetails = runBlocking { repository.workoutDetails(workoutId) }
        assertNotNull(initialDetails)
        val initialEntry = initialDetails!!.exercises.single()
        val entryId = initialEntry.workoutExercise.id
        assertEquals("flow-press", initialEntry.workoutExercise.exerciseId)
        assertEquals(0, initialEntry.workoutExercise.position)
        assertEquals(1, initialEntry.sets.size)
        val completedSetId = initialEntry.sets.single().id

        composeRule.onNodeWithTag("workout-set-weight-$completedSetId")
            .performTextReplacement("50")
        waitForUi("weight-persisted") {
            runBlocking {
                repository.workoutDetails(workoutId)?.exercises?.singleOrNull()?.sets
                    ?.singleOrNull()?.weightKg == 50.0
            }
        }
        composeRule.onNodeWithTag("workout-set-reps-$completedSetId")
            .performTextReplacement("8")
        waitForUi("set-inputs-persisted") {
            runBlocking {
                repository.workoutDetails(workoutId)?.exercises?.singleOrNull()?.sets
                    ?.singleOrNull()?.let { it.weightKg == 50.0 && it.repetitions == 8 } == true
            }
        }
        composeRule.onNodeWithTag("workout-set-complete-$completedSetId").performClick()
        waitForUi("set-completion-persisted") {
            runBlocking {
                repository.workoutDetails(workoutId)?.exercises?.singleOrNull()?.sets
                    ?.singleOrNull()?.isCompleted == true
            }
        }

        composeRule.onNodeWithText("Add Set", ignoreCase = true).performClick()
        waitForUi("second-set-added") {
            runBlocking {
                repository.workoutDetails(workoutId)?.exercises?.singleOrNull()?.sets?.size == 2
            }
        }
        val detailsWithUnfinishedSet = runBlocking { repository.workoutDetails(workoutId)!! }
        val setsBeforeFinish = detailsWithUnfinishedSet.exercises.single().sets
        val unfinishedSet = setsBeforeFinish.single { it.id != completedSetId }
        val unfinishedSetId = unfinishedSet.id
        assertEquals(2, setsBeforeFinish.size)
        assertEquals(0, setsBeforeFinish.single { it.id == completedSetId }.position)
        assertEquals(1, unfinishedSet.position)
        assertFalse(unfinishedSet.isCompleted)
        assertEquals(50.0, unfinishedSet.weightKg!!, 0.0)
        assertEquals(8, unfinishedSet.repetitions)

        composeRule.onNodeWithText("Finish").performClick()
        val finishPrompt = "Finish workout? Unfinished sets will be discarded."
        waitForUi("finish-confirmation") {
            composeRule.onAllNodesWithText(finishPrompt).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(finishPrompt).assertIsDisplayed()
        composeRule.onNodeWithText("[Yes]").performClick()

        waitForUi("workout-finished") {
            composeRule.onAllNodesWithText("Workout complete").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Volume: 400 kg").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    val workout = repository.workoutDetails(workoutId)?.workout
                    workout?.status == "finished" && workout.finishedAtMillis != null &&
                        repository.activeWorkout() == null
                }
        }
        composeRule.onNodeWithText("Workout complete").assertIsDisplayed()
        composeRule.onNodeWithText("Flow Strength").assertIsDisplayed()
        composeRule.onNodeWithText("Volume: 400 kg").assertIsDisplayed()
        composeRule.onAllNodesWithTag("active-workout-pill").assertCountEquals(0)

        val finishedDetails = runBlocking { repository.workoutDetails(workoutId) }
        assertNotNull(finishedDetails)
        assertEquals("Flow Strength", finishedDetails!!.workout.name)
        assertEquals("finished", finishedDetails.workout.status)
        assertNotNull(finishedDetails.workout.finishedAtMillis)
        assertNull(runBlocking { repository.activeWorkout() })
        assertEquals(1, finishedDetails.exercises.size)
        val finishedEntry = finishedDetails.exercises.single()
        assertEquals(entryId, finishedEntry.workoutExercise.id)
        assertEquals(0, finishedEntry.workoutExercise.position)
        assertEquals(1, finishedEntry.sets.size)
        val retainedSet = finishedEntry.sets.single()
        assertEquals(completedSetId, retainedSet.id)
        assertEquals(0, retainedSet.position)
        assertEquals(50.0, retainedSet.weightKg!!, 0.0)
        assertEquals(8, retainedSet.repetitions)
        assertTrue(retainedSet.isCompleted)
        assertFalse(finishedEntry.sets.any { it.id == unfinishedSetId })

        composeRule.onNodeWithText("[Done]").performClick()
        composeRule.onNodeWithTag("nav-history").performClick()
        val historyCardTag = "history-workout-$workoutId"
        waitForUi("finished-workout-history-card") {
            composeRule.onAllNodesWithTag(historyCardTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(historyCardTag).assertIsDisplayed()
        composeRule.onNodeWithText("[Flow Strength]").assertIsDisplayed()
        composeRule.onNodeWithText("1 × Press").assertIsDisplayed()
        val formattedBestSet = "${formatKilograms(50.0)} kg × 8"
        composeRule.onNodeWithText("Best set: $formattedBestSet").assertIsDisplayed()
        composeRule.onNodeWithText("Volume: 400 kg").assertIsDisplayed()

        composeRule.onNodeWithTag(historyCardTag).performClick()
        val formattedWeight = "${formatKilograms(50.0)} kg"
        waitForUi("finished-workout-history-detail") {
            composeRule.onAllNodesWithText("Flow Strength").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(formattedWeight).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Volume: 400 kg").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Flow Strength").assertIsDisplayed()
        composeRule.onNodeWithText("Press").assertIsDisplayed()
        composeRule.onNodeWithText(formattedWeight).assertIsDisplayed()
        composeRule.onNodeWithText("Volume: 400 kg").assertIsDisplayed()
    }

    @Test
    fun customExerciseCreateEditAndAddToWorkoutJourney() {
        runBlocking {
            insertExercises(
                exerciseFixture(id = "flow-press", name = "Press"),
                exerciseFixture(id = "flow-squat", name = "Squat"),
            )
        }
        setUpGymApp()

        waitForUi("exercise-tab") {
            composeRule.onAllNodesWithTag("nav-exercises").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("nav-exercises").performClick()
        onTab("nav-exercises")
        waitForUi("exercise-catalogue-loaded") {
            composeRule.onAllNodesWithTag("exercise-row-flow-press").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("exercise-row-flow-squat").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Create").performClick()
        waitForUi("custom-exercise-create-form") {
            composeRule.onAllNodesWithTag("exercise-field-name").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("exercise-field-instructions").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exercise-field-name").performTextReplacement("Flow Custom Press")
        composeRule.onNodeWithTag("exercise-field-instructions")
            .performTextReplacement("Control the movement")
        composeRule.onNodeWithText("Save").performClick()

        waitForUi("custom-exercise-created") {
            composeRule.onAllNodesWithText("[Flow Custom Press]").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    repository.exerciseSummaries().any {
                        it.exercise.isUserCreated && it.exercise.name == "Flow Custom Press"
                    }
                }
        }
        val createdCustomExercises = runBlocking {
            repository.exerciseSummaries().filter { it.exercise.isUserCreated }
        }
        assertEquals(1, createdCustomExercises.size)
        val exerciseId = createdCustomExercises.single().exercise.id
        assertTrue(exerciseId.isNotBlank())
        composeRule.onNodeWithTag("exercise-row-$exerciseId").assertIsDisplayed()

        composeRule.onNodeWithText("[Flow Custom Press]").performClick()
        waitForUi("custom-exercise-detail") {
            composeRule.onAllNodesWithText("Flow Custom Press").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription("Exercise options")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Exercise options").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        waitForUi("custom-exercise-edit-loaded") {
            composeRule.onAllNodesWithTag("exercise-field-name").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Flow Custom Press").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Control the movement").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exercise-field-name").assertTextEquals("Flow Custom Press")
        composeRule.onNodeWithTag("exercise-field-instructions")
            .assertTextEquals("Control the movement")
        composeRule.onNodeWithTag("exercise-field-name")
            .performTextReplacement("Flow Custom Press Revised")
        composeRule.onNodeWithText("Save").performClick()

        waitForUi("custom-exercise-edited") {
            composeRule.onAllNodesWithTag("exercise-row-$exerciseId").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    repository.exerciseDetails(exerciseId)?.exercise?.name == "Flow Custom Press Revised"
                }
        }
        val editedCustomExercises = runBlocking {
            repository.exerciseSummaries().filter { it.exercise.isUserCreated }
        }
        assertEquals(1, editedCustomExercises.size)
        assertEquals(exerciseId, editedCustomExercises.single().exercise.id)
        assertEquals("Flow Custom Press Revised", editedCustomExercises.single().exercise.name)
        assertEquals(
            listOf("Control the movement"),
            runBlocking { repository.exerciseDetails(exerciseId)?.exercise?.instructions },
        )

        composeRule.onNodeWithTag("nav-workout").performClick()
        onTab("nav-workout")
        composeRule.onNodeWithText("Start empty workout").performClick()
        waitForUi("workout-started") {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.activeWorkout() != null }
        }
        val activeWorkout = runBlocking { repository.activeWorkout() }
        assertNotNull(activeWorkout)
        composeRule.onNodeWithText("Add Exercise", ignoreCase = true).performClick()
        waitForUi("exercise-picker-open") {
            composeRule.onAllNodesWithTag("exercise-search").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exercise-search").performTextReplacement("Flow Custom Press Revised")
        waitForUi("revised-custom-exercise-search-result") {
            composeRule.onAllNodesWithTag("exercise-row-$exerciseId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exercise-row-$exerciseId").performClick()
        composeRule.onNodeWithText("Add selected (1)").performClick()

        waitForUi("custom-exercise-added-to-workout") {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking {
                    repository.workoutDetails(activeWorkout!!.id)?.exercises?.any {
                        it.workoutExercise.exerciseId == exerciseId
                    } == true
                }
        }
        composeRule.onNodeWithText("Flow Custom Press Revised").assertIsDisplayed()
        val workoutDetails = runBlocking { repository.workoutDetails(activeWorkout!!.id) }
        val entry = workoutDetails!!.exercises.single()
        assertEquals(exerciseId, entry.workoutExercise.exerciseId)
        assertEquals(0, entry.workoutExercise.position)
        assertEquals(1, entry.sets.size)
        val set = entry.sets.single()
        assertEquals(0, set.position)
        assertEquals(null, set.weightKg)
        assertEquals(null, set.repetitions)
        assertEquals(null, set.restSeconds)
        assertEquals(false, set.isCompleted)
    }

    @Test
    fun actualGymAppStartsAndLoadsFeaturesWithoutPreload() {
        runBlocking {
            insertExercises(
                exerciseFixture(id = "flow-press", name = "Press"),
                exerciseFixture(id = "flow-squat", name = "Squat"),
            )
        }
        setUpGymApp()

        waitForUi("initial-workout") {
            composeRule.onAllNodesWithText("Start empty workout").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("nav-workout").fetchSemanticsNodes().isNotEmpty()
        }
        onTab("nav-workout")
        composeRule.onNodeWithText("Start empty workout").assertIsDisplayed()
        assertTrue(runBlocking { repository.savedRoutines().isEmpty() })

        composeRule.onNodeWithTag("nav-exercises").performClick()
        composeRule.onNodeWithTag("nav-exercises").assertIsSelected()
        waitForUi("catalogue-loaded") {
            composeRule.onAllNodesWithTag("exercise-row-flow-press").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("exercise-row-flow-squat").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exercise-row-flow-press").assertIsDisplayed()
        composeRule.onNodeWithTag("exercise-row-flow-squat").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-history").performClick()
        waitForUi("empty-history-loaded") {
            runBlocking { repository.finishedWorkoutSummaries().isEmpty() } &&
                composeRule.onAllNodes(progressIndicatorNode).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("nav-history").assertIsDisplayed()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-history").assertIsSelected()
        composeRule.onAllNodes(progressIndicatorNode).assertCountEquals(0)
        composeRule.onAllNodes(historyCardNode).assertCountEquals(0)
        assertTrue(runBlocking { repository.finishedWorkoutSummaries().isEmpty() })

        composeRule.onNodeWithTag("nav-workout").performClick()
        composeRule.onNodeWithText("Start empty workout").performClick()
        waitForUi("empty-workout-started") {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.activeWorkout() != null }
        }
        composeRule.onNodeWithTag("workout-name-input").assertIsDisplayed()
        val activeWorkout = runBlocking { repository.activeWorkout() }
        assertNotNull(activeWorkout)
        assertEquals(emptyList<String>(), runBlocking {
            repository.workoutDetails(activeWorkout!!.id)?.exercises?.map { it.workoutExercise.exerciseId }
        })

        composeRule.onNodeWithContentDescription("Collapse workout").performClick()
        waitForUi("active-workout-collapsed") {
            composeRule.onAllNodesWithTag("active-workout-pill").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("active-workout-pill").assertIsDisplayed()
        composeRule.onNodeWithTag("active-workout-pill").performClick()
        waitForUi("active-workout-resumed") {
            composeRule.onAllNodesWithTag("workout-name-input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("workout-name-input").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Collapse workout").performClick()
        waitForUi("workout-collapsed-for-routine") {
            composeRule.onAllNodesWithTag("create-routine").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create-routine").performClick()
        waitForUi("draft-routine-loaded") {
            val draftId = editingRoutineId()
            draftId != null &&
                composeRule.onAllNodesWithTag("routine-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.routineDetails(draftId)?.routine?.isDraft == true }
        }
        composeRule.onNodeWithTag("routine-name-input").assertIsDisplayed()
        val draftId = editingRoutineId()
        assertNotNull(draftId)
        assertTrue(runBlocking { repository.routineDetails(draftId!!)?.routine?.isDraft == true })
    }

    private fun onTab(testTag: String) {
        composeRule.onNodeWithTag(testTag).assertIsDisplayed()
        composeRule.onNodeWithTag(testTag).assertIsSelected()
    }

    private companion object {
        val historyCardNode = SemanticsMatcher("history workout card") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("history-workout-")
        }
        val progressIndicatorNode = SemanticsMatcher("progress indicator") { node ->
            node.config.contains(SemanticsProperties.ProgressBarRangeInfo)
        }
    }
}
