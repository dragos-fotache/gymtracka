package com.dragosfotache.gymtracka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorScreen
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PausedLeafRefusalTest : GymDatabaseTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private val stateHolderScopes = mutableListOf<CoroutineScope>()
    private val ownedCoordinators = mutableListOf<FiniteEditCoordinator>()

    private fun trackScope(scope: CoroutineScope): CoroutineScope {
        stateHolderScopes += scope
        return scope
    }

    private fun trackCoordinator(coordinator: FiniteEditCoordinator): FiniteEditCoordinator {
        ownedCoordinators += coordinator
        return coordinator
    }

    @After
    fun cancelStateHolderScopes() {
        ownedCoordinators.forEach { it.dispose() }
        ownedCoordinators.clear()
        stateHolderScopes.forEach { it.cancel() }
        runBlocking {
            stateHolderScopes.forEach { scope ->
                withTimeout(5.seconds) { scope.coroutineContext[Job]?.join() }
            }
        }
        stateHolderScopes.clear()
    }

    @Test
    fun pausedAutosaveTextRefusesVisibleAndRoomChangeThenSucceedsAfterClose() {
        runBlocking { insertRoutineAggregate(RoutineEntity("leaf-routine", "Leaf plan")) }
        val coordinator = trackCoordinator(FiniteEditCoordinator(trackScope(MainScope())))
        val state = RoutineEditorState(
            "leaf-routine",
            repository,
            trackScope(MainScope()),
            edits = coordinator,
        )
        try {
            composeRule.waitUntil(5_000) { state.details != null }
            composeRule.setContent {
                MaterialTheme {
                    RoutineEditorScreen(
                        routineId = "leaf-routine",
                        onAddExercise = {},
                        onCancel = {},
                        onSaved = {},
                        state = state,
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Leaf plan")

            val lease = runBlocking { coordinator.pauseAndDrain() }
            try {
                composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Hacked")
                composeRule.waitForIdle()
                // Persisted-value autosave draft must not change while paused.
                composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Leaf plan")
                assertEquals(
                    "Leaf plan",
                    runBlocking { repository.routineDetails("leaf-routine")!!.routine.name },
                )
            } finally {
                runBlocking { lease.close() }
            }

            composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Allowed")
            composeRule.waitUntil {
                runBlocking { repository.routineDetails("leaf-routine")?.routine?.name == "Allowed" }
            }
            composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Allowed")
            assertEquals(
                "Allowed",
                runBlocking { repository.routineDetails("leaf-routine")!!.routine.name },
            )
        } finally {
            // Scopes cancelled in @After; join composition before DB teardown via rule ordering.
            composeRule.waitForIdle()
        }
    }
}
