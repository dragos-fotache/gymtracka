package com.dragosfotache.gymtracka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.muscleGroupFixture
import com.dragosfotache.gymtracka.data.sync.R05B_ACCOUNT
import com.dragosfotache.gymtracka.data.sync.R05B_BATCH_2
import com.dragosfotache.gymtracka.data.sync.R05B_CURSOR_2
import com.dragosfotache.gymtracka.data.sync.R05B_CUSTOM_A
import com.dragosfotache.gymtracka.data.sync.R05B_ENTRY_A1
import com.dragosfotache.gymtracka.data.sync.R05B_OP_UPSERT
import com.dragosfotache.gymtracka.data.sync.R05B_ROUTINE_B
import com.dragosfotache.gymtracka.data.sync.R05B_SET_A1
import com.dragosfotache.gymtracka.data.sync.R05B_SET_A2
import com.dragosfotache.gymtracka.data.sync.R05B_TYPE_CUSTOM
import com.dragosfotache.gymtracka.data.sync.R05B_TYPE_ROUTINE
import com.dragosfotache.gymtracka.data.sync.R05B_TYPE_WORKOUT
import com.dragosfotache.gymtracka.data.sync.R05B_WORKOUT_A
import com.dragosfotache.gymtracka.data.sync.R05C_BUNDLED
import com.dragosfotache.gymtracka.data.sync.R05C_EQUIPMENT
import com.dragosfotache.gymtracka.data.sync.R05C_GENERATION
import com.dragosfotache.gymtracka.data.sync.R05C_MUSCLE_PRIMARY
import com.dragosfotache.gymtracka.data.sync.R05C_MUSCLE_SECONDARY
import com.dragosfotache.gymtracka.data.sync.r05bChange
import com.dragosfotache.gymtracka.data.sync.r05bCustomRecord
import com.dragosfotache.gymtracka.data.sync.r05bJsonArray
import com.dragosfotache.gymtracka.data.sync.r05bPull
import com.dragosfotache.gymtracka.data.sync.r05bRoutineEntry
import com.dragosfotache.gymtracka.data.sync.r05bRoutineRecord
import com.dragosfotache.gymtracka.data.sync.r05bRoutineSet
import com.dragosfotache.gymtracka.data.sync.r05bWorkoutEntry
import com.dragosfotache.gymtracka.data.sync.r05bWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.r05bWorkoutSet
import com.dragosfotache.gymtracka.data.sync.r05cApplyCanonical
import com.dragosfotache.gymtracka.data.sync.r05cGrantBarrier
import com.dragosfotache.gymtracka.data.sync.r05cPreparePublication
import com.dragosfotache.gymtracka.data.sync.r05cStore
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutScreen
import com.dragosfotache.gymtracka.ui.activeworkout.ActiveWorkoutState
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorScreen
import com.dragosfotache.gymtracka.ui.routines.RoutineEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * R12b independent QA: actual Compose same-ID refresh.
 *
 * Hand-written from docs/remaining-work/R12b-task.md only. Uses real Room +
 * real complete publications for the selected-feature journey, actual feature
 * screens and stable tags from FocusedComposeInteractionTest. No private
 * composables, no production oracles.
 */
@RunWith(AndroidJUnit4::class)
class ProjectionFeatureRefreshTest : GymDatabaseTest() {
    @get:Rule
    val composeRule = createComposeRule()

    private val stateHolderScopes = mutableListOf<CoroutineScope>()
    private val ownedCoordinators = mutableListOf<FiniteEditCoordinator>()
    private var contentEnabled by mutableStateOf(true)

    private fun ownScope(): CoroutineScope {
        val scope = MainScope()
        stateHolderScopes += scope
        return scope
    }

    @After
    fun disposeCompositionAndJoinScopesBeforeDatabase() {
        // Disposal must not require prior idle: remove content on the UI thread,
        // settle, then dispose coordinators/scopes and settle again.
        composeRule.runOnUiThread { contentEnabled = false }
        composeRule.waitForIdle()
        ownedCoordinators.forEach { it.dispose() }
        ownedCoordinators.clear()
        stateHolderScopes.forEach { it.cancel() }
        runBlocking {
            stateHolderScopes.forEach { scope ->
                withTimeout(5.seconds) { scope.coroutineContext[Job]?.join() }
            }
        }
        stateHolderScopes.clear()
        composeRule.waitForIdle()
    }

    private fun accountRepository() = GymRepository(database, RepositorySession(R05B_ACCOUNT, R05C_GENERATION))

    private fun sharedEdits(): FiniteEditCoordinator {
        val coordinatorScope = ownScope()
        return FiniteEditCoordinator(coordinatorScope).also { ownedCoordinators += it }
    }

    /** Real finite admission for a live-screen publication: the barrier acquire
     * itself pauses the shared coordinator on Main and validates the request. */
    private class AdmissionBarrier(
        private val shared: FiniteEditCoordinator,
        private val expectedAccount: String = R05B_ACCOUNT,
        private val expectedGeneration: Long = R05C_GENERATION,
        private val expectedCacheEpoch: Long = -1L,
    ) : ProjectionPublicationBarrier {
        override suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease {
            assertEquals(expectedAccount, request.accountId)
            assertEquals(expectedGeneration, request.sessionGeneration)
            if (expectedCacheEpoch >= 0L) assertEquals(expectedCacheEpoch, request.expectedCacheEpoch)
            return withContext(Dispatchers.Main) { shared.pauseAndDrain() }
        }
    }

    private fun publishLiveUpdate(expectedEpoch: Long, pullJson: String, shared: FiniteEditCoordinator) {
        val publicationScope = ownScope()
        var publication: Job? = null
        var publicationError: Throwable? = null
        composeRule.runOnIdle {
            publication = publicationScope.launch {
                try {
                    shared.awaitPendingEdits()
                    database.r05cApplyCanonical(pullJson, expectedEpoch)
                    val result = database.r05cStore(barrier = AdmissionBarrier(shared, expectedCacheEpoch = expectedEpoch)).publish(expectedEpoch)
                    assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
                } catch (e: Throwable) {
                    publicationError = e
                }
            }
        }
        composeRule.waitUntil(5_000) { publication?.isCompleted == true }
        runBlocking { publication!!.join() }
        publicationError?.let { throw it }
    }

    private suspend fun seedLookups() {
        insertMuscleGroups(
            muscleGroupFixture(id = R05C_MUSCLE_PRIMARY, name = "Chest"),
            muscleGroupFixture(id = R05C_MUSCLE_SECONDARY, name = "Shoulders"),
        )
        insertEquipment(equipmentFixture(id = R05C_EQUIPMENT, name = "Dumbbell"))
        insertExercises(exerciseFixture(id = R05C_BUNDLED, name = "Bench Press"))
    }

    private suspend fun publishActiveGraph(customName: String, weightKg: Double) {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, r05bCustomRecord(id = R05B_CUSTOM_A, name = customName, revision = 1L)),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(
                                exerciseId = R05B_CUSTOM_A,
                                exerciseName = "Stale",
                                sets = r05bJsonArray(r05bWorkoutSet(repetitions = 8, weightKg = weightKg)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
    }

    private fun activeRenameOnlyPull(customName: String, customRevision: Long) = r05bPull(
        batchId = R05B_BATCH_2,
        cursor = R05B_CURSOR_2,
        changes = r05bJsonArray(
            r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, customRevision, r05bCustomRecord(id = R05B_CUSTOM_A, name = customName, revision = customRevision)),
        ),
    )

    private fun activeSetInsertPull() = r05bPull(
        batchId = R05B_BATCH_2,
        cursor = R05B_CURSOR_2,
        changes = r05bJsonArray(
            r05bChange(
                R05B_TYPE_WORKOUT,
                R05B_WORKOUT_A,
                R05B_OP_UPSERT,
                3L,
                r05bWorkoutRecord(
                    revision = 3L,
                    exercises = r05bJsonArray(
                        r05bWorkoutEntry(
                            exerciseId = R05B_CUSTOM_A,
                            exerciseName = "Stale",
                            sets = r05bJsonArray(
                                r05bWorkoutSet(id = R05B_SET_A2, position = 0, repetitions = 5, weightKg = 40.0),
                                r05bWorkoutSet(id = R05B_SET_A1, position = 1, repetitions = 8, weightKg = 13.5),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun activeWeightUpdatePull(weightKg: Double) = r05bPull(
        batchId = R05B_BATCH_2,
        cursor = R05B_CURSOR_2,
        changes = r05bJsonArray(
            r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 2L, r05bCustomRecord(id = R05B_CUSTOM_A, name = "Press Renamed", revision = 2L)),
            r05bChange(
                R05B_TYPE_WORKOUT,
                R05B_WORKOUT_A,
                R05B_OP_UPSERT,
                2L,
                r05bWorkoutRecord(
                    revision = 2L,
                    exercises = r05bJsonArray(
                        r05bWorkoutEntry(
                            exerciseId = R05B_CUSTOM_A,
                            exerciseName = "Stale",
                            sets = r05bJsonArray(r05bWorkoutSet(repetitions = 8, weightKg = weightKg)),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun activeSameIdWeightUpdatesRetainFocusAndRawTextThenReplaceOnGenuineChange() {
        runBlocking { publishActiveGraph(customName = "Press", weightKg = 12.0) }
        val repository = accountRepository()
        val restStarts = mutableListOf<Pair<String, Int>>()
        val endedTimers = mutableListOf<ActiveRestTimer>()
        val shared = sharedEdits()
        val screenScope = ownScope()
        val state = ActiveWorkoutState(R05B_WORKOUT_A, 1_000L, repository, screenScope, edits = shared)

        composeRule.setContent {
            MaterialTheme {
                if (contentEnabled) {
                    ActiveWorkoutScreen(
                        workoutId = R05B_WORKOUT_A,
                        workoutStartMillis = 1_000L,
                        activeRestTimer = null,
                        restTimerRemainingSeconds = 0,
                        restTimerCompletionKey = 0,
                        onAddExercise = {},
                        onCollapse = {},
                        onWorkoutNameChanged = {},
                        onFinishWorkout = {},
                        onCancelWorkout = {},
                        onRestTimerStarted = { set, seconds -> restStarts += set.id to seconds },
                        onRestTimerEnded = { endedTimers += it },
                        onRestTimerAdjusted = { _, _ -> },
                        onExerciseReplacementRequested = {},
                        state = state,
                    )
                }
            }
        }
        val weightTag = "workout-set-weight-$R05B_SET_A1"
        composeRule.waitUntil { composeRule.onAllNodesWithTag(weightTag).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(weightTag).performClick()
        composeRule.onNodeWithTag(weightTag).assertIsFocused()

        // Value-equal raw text: 12.00 sanitizes to the persisted 12.0, no capture.
        composeRule.onNodeWithTag(weightTag).performTextReplacement("12.00")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(weightTag).assertTextEquals("12.00")
        composeRule.onNodeWithTag(weightTag).assertIsFocused()
        assertEquals(12.0, runBlocking { repository.workoutDetails(R05B_WORKOUT_A)!!.exercises.single().sets.single().weightKg })

        // Custom-only rename at unchanged parent revision must retain raw text and focus.
        publishLiveUpdate(1L, activeRenameOnlyPull(customName = "Press Renamed", customRevision = 2L), shared)
        composeRule.waitUntil { composeRule.onAllNodesWithText("Press Renamed").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(weightTag).assertTextEquals("12.00")
        composeRule.onNodeWithTag(weightTag).assertIsFocused()
        assertTrue(restStarts.isEmpty())
        assertTrue(endedTimers.isEmpty())

        // Genuine changed parent revision replaces stale text without clearing focus.
        publishLiveUpdate(2L, activeWeightUpdatePull(weightKg = 13.5), shared)
        composeRule.waitUntil {
            try {
                composeRule.onNodeWithTag(weightTag).assertTextEquals("13.5")
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithTag(weightTag).assertIsFocused()
        assertTrue(restStarts.isEmpty())
        assertTrue(endedTimers.isEmpty())
        val stored = runBlocking { repository.workoutDetails(R05B_WORKOUT_A)!! }
        assertEquals("Press Renamed", stored.exercises.single().workoutExercise.exerciseName)
        assertEquals(false, stored.exercises.single().sets.single().isCompleted)

        // A complete publication inserting an earlier independent-ID set keeps the
        // original same-ID set (weight/reps/ID, consecutive positions) with
        // focus and text on the original tagged field and no timer callback.
        publishLiveUpdate(3L, activeSetInsertPull(), shared)
        val afterInsert = runBlocking { repository.workoutDetails(R05B_WORKOUT_A)!! }
        val insertedSets = afterInsert.exercises.single().sets.sortedBy { it.position }
        assertEquals(listOf(R05B_SET_A2, R05B_SET_A1), insertedSets.map { it.id })
        assertEquals(listOf(0, 1), insertedSets.map { it.position })
        val surviving = insertedSets.single { it.id == R05B_SET_A1 }
        assertEquals(13.5, surviving.weightKg)
        assertEquals(8, surviving.repetitions)
        composeRule.waitUntil {
            try {
                composeRule.onNodeWithTag(weightTag).assertTextEquals("13.5")
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithTag(weightTag).assertIsFocused()
        composeRule.onNodeWithTag("workout-set-weight-$R05B_SET_A2").assertTextEquals("40")
        assertTrue(restStarts.isEmpty())
        assertTrue(endedTimers.isEmpty())
    }

    @Test
    fun routineEditorSameIdNameAndSetUpdateRetainsFocus() {
        runBlocking {
            seedLookups()
            database.r05cPreparePublication(
                changes = r05bJsonArray(
                    r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, r05bCustomRecord(id = R05B_CUSTOM_A, name = "Press", revision = 1L)),
                    r05bChange(
                        R05B_TYPE_ROUTINE,
                        R05B_ROUTINE_B,
                        R05B_OP_UPSERT,
                        1L,
                        r05bRoutineRecord(
                            id = R05B_ROUTINE_B,
                            name = "Legs",
                            isDraft = false,
                            revision = 1L,
                            exercises = r05bJsonArray(
                                r05bRoutineEntry(
                                    exerciseId = R05B_CUSTOM_A,
                                    exerciseName = "Stale",
                                    sets = r05bJsonArray(r05bRoutineSet(repetitions = 8, weightKg = 60.0)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val first = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
            assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        }
        val repository = accountRepository()
        val shared = sharedEdits()
        val screenScope = ownScope()
        val state = RoutineEditorState(R05B_ROUTINE_B, repository, screenScope, edits = shared)
        composeRule.setContent {
            MaterialTheme {
                if (contentEnabled) {
                    RoutineEditorScreen(
                        routineId = R05B_ROUTINE_B,
                        onAddExercise = {},
                        onCancel = {},
                        onSaved = {},
                        state = state,
                    )
                }
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithTag("routine-name-input").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Legs")
        composeRule.onNodeWithTag("routine-name-input").performClick()
        composeRule.onNodeWithTag("routine-name-input").assertIsFocused()

        publishLiveUpdate(
            1L,
            r05bPull(
                batchId = R05B_BATCH_2,
                cursor = R05B_CURSOR_2,
                changes = r05bJsonArray(
                    r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 2L, r05bCustomRecord(id = R05B_CUSTOM_A, name = "Press Renamed", revision = 2L)),
                    r05bChange(
                        R05B_TYPE_ROUTINE,
                        R05B_ROUTINE_B,
                        R05B_OP_UPSERT,
                        2L,
                        r05bRoutineRecord(
                            id = R05B_ROUTINE_B,
                            name = "Legs Renamed",
                            isDraft = false,
                            revision = 2L,
                            exercises = r05bJsonArray(
                                r05bRoutineEntry(
                                    id = R05B_ENTRY_A1,
                                    exerciseId = R05B_CUSTOM_A,
                                    exerciseName = "Stale",
                                    sets = r05bJsonArray(r05bRoutineSet(id = R05B_SET_A1, repetitions = 10, weightKg = 65.5)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            shared,
        )
        composeRule.waitUntil {
            try {
                composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Legs Renamed")
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithTag("routine-name-input").assertIsFocused()
        composeRule.onNodeWithTag("routine-set-weight-$R05B_SET_A1").assertTextEquals("65.5")
        composeRule.onNodeWithTag("routine-set-reps-$R05B_SET_A1").assertTextEquals("10")
        val stored = runBlocking { repository.routineDetails(R05B_ROUTINE_B)!! }
        assertEquals("Legs Renamed", stored.routine.name)
        assertEquals("Press Renamed", stored.exercises.single().routineExercise.exerciseName)
    }

    @Test
    fun refusedRoutineNameLeavesDisplayedDraftIntact() {
        runBlocking {
            seedLookups()
            database.r05cPreparePublication(
                changes = r05bJsonArray(
                    r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, r05bCustomRecord(id = R05B_CUSTOM_A, name = "Press", revision = 1L)),
                    r05bChange(
                        R05B_TYPE_ROUTINE,
                        R05B_ROUTINE_B,
                        R05B_OP_UPSERT,
                        1L,
                        r05bRoutineRecord(id = R05B_ROUTINE_B, name = "Base Plan", isDraft = true, revision = 1L),
                    ),
                ),
            )
            val first = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
            assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        }
        val repository = accountRepository()
        val coordinatorScope = ownScope()
        val shared = FiniteEditCoordinator(coordinatorScope).also { ownedCoordinators += it }
        val screenScope = ownScope()
        val state = RoutineEditorState(R05B_ROUTINE_B, repository, screenScope, edits = shared)
        composeRule.waitUntil(5_000) { state.details != null }
        composeRule.setContent {
            MaterialTheme {
                if (contentEnabled) {
                    RoutineEditorScreen(
                        routineId = R05B_ROUTINE_B,
                        onAddExercise = {},
                        onCancel = {},
                        onSaved = {},
                        state = state,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Base Plan")

        val lease = runBlocking { shared.pauseAndDrain() }
        try {
            composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Hacked")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Base Plan")
            assertEquals("Base Plan", runBlocking { repository.routineDetails(R05B_ROUTINE_B)!!.routine.name })
        } finally {
            runBlocking { lease.close() }
        }

        composeRule.onNodeWithTag("routine-name-input").performTextReplacement("Allowed")
        composeRule.waitUntil { runBlocking { repository.routineDetails(R05B_ROUTINE_B)?.routine?.name == "Allowed" } }
        composeRule.onNodeWithTag("routine-name-input").assertTextEquals("Allowed")
    }

    private fun activeRestUpdatePull(restSeconds: Int) = r05bPull(
        batchId = R05B_BATCH_2,
        cursor = R05B_CURSOR_2,
        changes = r05bJsonArray(
            r05bChange(
                R05B_TYPE_WORKOUT,
                R05B_WORKOUT_A,
                R05B_OP_UPSERT,
                2L,
                r05bWorkoutRecord(
                    revision = 2L,
                    exercises = r05bJsonArray(
                        r05bWorkoutEntry(
                            exerciseId = R05B_CUSTOM_A,
                            exerciseName = "Stale",
                            restDurationSeconds = restSeconds,
                            sets = r05bJsonArray(r05bWorkoutSet(repetitions = 8, weightKg = 50.0)),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun routineRestUpdatePull(restSeconds: Int) = r05bPull(
        batchId = R05B_BATCH_2,
        cursor = R05B_CURSOR_2,
        changes = r05bJsonArray(
            r05bChange(
                R05B_TYPE_ROUTINE,
                R05B_ROUTINE_B,
                R05B_OP_UPSERT,
                2L,
                r05bRoutineRecord(
                    id = R05B_ROUTINE_B,
                    name = "Legs",
                    isDraft = false,
                    revision = 2L,
                    exercises = r05bJsonArray(
                        r05bRoutineEntry(
                            id = R05B_ENTRY_A1,
                            exerciseId = R05B_CUSTOM_A,
                            exerciseName = "Stale",
                            restDurationSeconds = restSeconds,
                            sets = r05bJsonArray(r05bRoutineSet(id = R05B_SET_A1, repetitions = 8, weightKg = 60.0)),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun activeRestDialogDraftSurvivesPublicationAndConfirmsOriginal() {
        runBlocking { publishActiveGraph(customName = "Press", weightKg = 50.0) }
        val repository = accountRepository()
        val restStarts = mutableListOf<Pair<String, Int>>()
        val endedTimers = mutableListOf<ActiveRestTimer>()
        val shared = sharedEdits()
        val screenScope = ownScope()
        val state = ActiveWorkoutState(R05B_WORKOUT_A, 1_000L, repository, screenScope, edits = shared)
        composeRule.setContent {
            MaterialTheme {
                if (contentEnabled) {
                    ActiveWorkoutScreen(
                        workoutId = R05B_WORKOUT_A,
                        workoutStartMillis = 1_000L,
                        activeRestTimer = null,
                        restTimerRemainingSeconds = 0,
                        restTimerCompletionKey = 0,
                        onAddExercise = {},
                        onCollapse = {},
                        onWorkoutNameChanged = {},
                        onFinishWorkout = {},
                        onCancelWorkout = {},
                        onRestTimerStarted = { set, seconds -> restStarts += set.id to seconds },
                        onRestTimerEnded = { endedTimers += it },
                        onRestTimerAdjusted = { _, _ -> },
                        onExerciseReplacementRequested = {},
                        state = state,
                    )
                }
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithText("Rest Timer: 30s").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Rest Timer: 30s").performClick()
        composeRule.onNodeWithText("Rest timer").assertIsDisplayed()
        composeRule.onNodeWithText("Minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Seconds").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()

        // User wheel interaction: Seconds column (last) offers 0..55 in 5s steps;
        // index 9 selects 45s. Strict idle settles the corrected wheel.
        val dialogScrollLists = composeRule.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog()))
        dialogScrollLists[1].performScrollToIndex(9)
        composeRule.waitForIdle()

        // Genuinely changed persisted rest commits while the dialog stays open.
        publishLiveUpdate(1L, activeRestUpdatePull(restSeconds = 60), shared)
        assertEquals(60, runBlocking { repository.workoutDetails(R05B_WORKOUT_A)!!.exercises.single().workoutExercise.restDurationSeconds })
        composeRule.waitUntil(5_000) { state.details?.exercises?.single()?.workoutExercise?.restDurationSeconds == 60 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rest timer").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()

        // Done confirms the pre-publication user-selected 45s draft, not the
        // newer persisted 60s value.
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitUntil { runBlocking { repository.workoutDetails(R05B_WORKOUT_A)?.exercises?.single()?.workoutExercise?.restDurationSeconds == 45 } }
        composeRule.waitUntil { composeRule.onAllNodesWithText("Rest Timer: 45s").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText("Rest timer").assertCountEquals(0)
        composeRule.waitForIdle()
        assertTrue(restStarts.isEmpty())
        assertTrue(endedTimers.isEmpty())
    }

    @Test
    fun routineRestDialogDraftSurvivesPublicationAndConfirmsOriginal() {
        runBlocking {
            seedLookups()
            database.r05cPreparePublication(
                changes = r05bJsonArray(
                    r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, r05bCustomRecord(id = R05B_CUSTOM_A, name = "Press", revision = 1L)),
                    r05bChange(
                        R05B_TYPE_ROUTINE,
                        R05B_ROUTINE_B,
                        R05B_OP_UPSERT,
                        1L,
                        r05bRoutineRecord(
                            id = R05B_ROUTINE_B,
                            name = "Legs",
                            isDraft = false,
                            revision = 1L,
                            exercises = r05bJsonArray(
                                r05bRoutineEntry(
                                    exerciseId = R05B_CUSTOM_A,
                                    exerciseName = "Stale",
                                    sets = r05bJsonArray(r05bRoutineSet(repetitions = 8, weightKg = 60.0)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val first = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
            assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        }
        val repository = accountRepository()
        val shared = sharedEdits()
        val screenScope = ownScope()
        val state = RoutineEditorState(R05B_ROUTINE_B, repository, screenScope, edits = shared)
        composeRule.setContent {
            MaterialTheme {
                if (contentEnabled) {
                    RoutineEditorScreen(
                        routineId = R05B_ROUTINE_B,
                        onAddExercise = {},
                        onCancel = {},
                        onSaved = {},
                        state = state,
                    )
                }
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithText("Rest Timer: 30s").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Rest Timer: 30s").performClick()
        composeRule.onNodeWithText("Rest timer").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()

        publishLiveUpdate(1L, routineRestUpdatePull(restSeconds = 60), shared)
        assertEquals(60, runBlocking { repository.routineDetails(R05B_ROUTINE_B)!!.exercises.single().routineExercise.restDurationSeconds })
        composeRule.waitUntil(5_000) { state.details?.exercises?.single()?.routineExercise?.restDurationSeconds == 60 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rest timer").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()

        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitUntil { runBlocking { repository.routineDetails(R05B_ROUTINE_B)?.exercises?.single()?.routineExercise?.restDurationSeconds == 30 } }
        composeRule.waitUntil { composeRule.onAllNodesWithText("Rest Timer: 30s").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText("Rest timer").assertCountEquals(0)
        composeRule.waitForIdle()
    }
}
