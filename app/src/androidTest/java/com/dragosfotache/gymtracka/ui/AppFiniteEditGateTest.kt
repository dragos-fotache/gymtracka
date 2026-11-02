package com.dragosfotache.gymtracka.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class AppFiniteEditGateTest : AppFlowTestSupport() {
    private val acquisitionScopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelAcquisitionScopes() {
        acquisitionScopes.forEach { it.cancel() }
        runBlocking {
            acquisitionScopes.forEach { scope ->
                withTimeout(5.seconds) { scope.coroutineContext[Job]?.join() }
            }
        }
        acquisitionScopes.clear()
    }

    @Test
    fun pausedRootGateRefusesRoutineAutosaveVisibleAndRoom() {
        runBlocking {
            insertExercises(
                exerciseFixture(id = "gate-press", name = "Press"),
                exerciseFixture(id = "gate-squat", name = "Squat"),
            )
            assertTrue(repository.savedRoutines().isEmpty())
        }
        setUpGymApp()

        waitForUi("workout-home") {
            composeRule.onAllNodesWithTag("create-routine").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create-routine").performClick()
        waitForUi("routine-draft-created") {
            val routineId = editingRoutineId()
            routineId != null &&
                composeRule.onAllNodesWithTag("routine-name-input").fetchSemanticsNodes().isNotEmpty()
        }
        val routineId = editingRoutineId()
        assertNotNull(routineId)

        composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Base Plan")
        waitForUi("routine-base-persisted") {
            runBlocking { repository.routineDetails(routineId!!)?.routine?.name == "Base Plan" }
        }

        val root = appState()
        assertNotNull(root)
        // Harness-safe acquisition: launch on a test-owned dispatcher via runOnIdle
        // and bound the wait on Deferred completion so the clock can advance.
        val acquisitionScope = MainScope().also { acquisitionScopes += it }
        var acquisition: Deferred<ProjectionPublicationLease>? = null
        composeRule.runOnIdle {
            acquisition = acquisitionScope.async { root!!.edits.pauseAndDrain() }
        }
        composeRule.waitUntil(5_000) { acquisition?.isCompleted == true }
        val lease = runBlocking { acquisition!!.await() }
        try {
            composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Hacked")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Base Plan")
            assertEquals(
                "Base Plan",
                runBlocking { repository.routineDetails(routineId!!)!!.routine.name },
            )
        } finally {
            lease.close()
        }

        composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Allowed")
        waitForUi("routine-allowed-persisted") {
            runBlocking { repository.routineDetails(routineId!!)?.routine?.name == "Allowed" }
        }
        composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Allowed")
    }

    @Test
    fun predecessorGatedAutosaveSurvivesPickerDisposal() {
        runBlocking {
            insertExercises(
                exerciseFixture(id = "flow-press", name = "Press"),
                exerciseFixture(id = "flow-squat", name = "Squat"),
            )
        }
        setUpGymApp()

        waitForUi("workout-home") {
            composeRule.onAllNodesWithTag("create-routine").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("create-routine").performClick()
        waitForUi("routine-draft-created") {
            val routineId = editingRoutineId()
            routineId != null &&
                composeRule.onAllNodesWithTag("routine-name-input").fetchSemanticsNodes().isNotEmpty()
        }
        val routineId = editingRoutineId()
        assertNotNull(routineId)
        val root = appState()
        assertNotNull(root)

        // Gate a predecessor on the shared app coordinator before accepting UI work.
        val predecessorGate = CompletableDeferred<Unit>()
        val predecessor = root!!.edits.launchEdit("predecessor") { predecessorGate.await() }
        assertNotNull(predecessor)

        // Accept a routine-name autosave while the predecessor holds the FIFO chain.
        composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Surviving Plan")
        // Dispose the routine editor feature by navigating to the picker.
        composeRule.onNodeWithText("Add Exercise").performClick()
        waitForUi("routine-exercise-picker-open") {
            composeRule.onAllNodesWithTag("exercise-search").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("exercise-row-flow-squat").fetchSemanticsNodes().isNotEmpty()
        }

        // Release the predecessor: the accepted autosave must survive disposal.
        predecessorGate.complete(Unit)
        composeRule.onNodeWithTag("exercise-row-flow-squat").performClick()
        composeRule.onNodeWithText("Add selected (1)").performClick()
        waitForUi("surviving-name-persisted") {
            composeRule.onAllNodesWithTag("routine-name-input").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { repository.routineDetails(routineId!!)?.routine?.name == "Surviving Plan" }
        }
        composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Surviving Plan")
        assertEquals(
            "Surviving Plan",
            runBlocking { repository.routineDetails(routineId!!)!!.routine.name },
        )
        assertEquals(
            "flow-squat",
            runBlocking { repository.routineDetails(routineId!!)!!.exercises.single().routineExercise.exerciseId },
        )
    }
}
