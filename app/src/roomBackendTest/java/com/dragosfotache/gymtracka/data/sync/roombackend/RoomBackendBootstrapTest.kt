package com.dragosfotache.gymtracka.data.sync.roombackend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R10a matrix 1: empty and populated bootstrap plus offline/reopen.
 *
 * Two independent file stores start from a real complete empty snapshot. Store A
 * edits offline (custom plus routine draft plus workout with ordered entries and
 * sets, configured versus elapsed rest and nullable inputs), survives an injected
 * offline failure and a file reopen with unchanged durable intent, then reconnects
 * through the actual engine. Store B bootstraps via a paged real snapshot.
 * Independently expected full graphs, child IDs/order/values, revisions, clean
 * publication and outbox/stage/tail predicates must agree on both stores.
 */
@RunWith(AndroidJUnit4::class)
class RoomBackendBootstrapTest {

    companion object {
        const val CUSTOM_NAME = "R10a Press"
        const val ROUTINE_NAME = "R10a Routine"
        const val WORKOUT_NAME = "R10a Workout"
        const val ENTRY_NOTES = "R10a keep chest up"
        const val ENTRY_REST_SECONDS = 75
        const val SET_REST_SECONDS = 90
        const val STARTED_AT_MILLIS = 1_790_259_100_000L
    }

    @Test
    fun emptyAndPopulatedBootstrapOfflineReopen() = runBlocking(Dispatchers.IO) {
        val inputs = r10aRequireInputs()
        val transport = r10aNewTransport(inputs)
        try {
            val account = r10aMintAccount(transport, inputs.port)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var storeA = r10aOpenStore(context, "boot-a", account.accountId)
            var storeB = r10aOpenStore(context, "boot-b", account.accountId)
            try {
                val apiA = r10aNewApi(transport, account)
                val apiB = r10aNewApi(transport, account)

                // Both stores start from a real complete empty snapshot.
                val emptyA = r10aSnapshotConverge(storeA, account.accountId, apiA, R10aTestLease())
                assertEquals(ProjectionPublicationStatus.PUBLISHED, emptyA.status)
                val emptyB = r10aSnapshotConverge(storeB, account.accountId, apiB, R10aTestLease(), pageLimit = 1)
                assertEquals(ProjectionPublicationStatus.PUBLISHED, emptyB.status)
                assertEmptyVisible(storeA, "A empty", account.accountId)
                assertEmptyVisible(storeB, "B empty", account.accountId)

                // Offline A edits via the repository: custom, routine draft, workout
                // with ordered entries/sets, configured versus elapsed rest, nullable inputs.
                val repoA = storeA.repository()
                val customId = repoA.createCustomExercise(
                    CUSTOM_NAME, null, emptySet(), null, null, "",
                )
                val routineDraft = repoA.createRoutineDraft()
                val routineId = routineDraft.id
                repoA.updateRoutineName(routineId, ROUTINE_NAME)
                repoA.addExercisesToRoutine(routineId, listOf(customId))
                val routineSeed = checkNotNull(repoA.routineDetails(routineId)) { "A must read back its routine" }
                val routineEntryId = routineSeed.exercises.single().routineExercise.id
                val routineSetId = routineSeed.exercises.single().sets.single().id
                val workout = repoA.startEmptyWorkout(STARTED_AT_MILLIS)
                repoA.updateWorkoutName(workout.id, WORKOUT_NAME)
                repoA.addExercisesToWorkout(workout.id, listOf(customId))
                val details = checkNotNull(repoA.workoutDetails(workout.id)) { "A must read back its workout" }
                val entryId = details.exercises.single().workoutExercise.id
                repoA.updateWorkoutExerciseNotes(entryId, ENTRY_NOTES)
                repoA.updateWorkoutExerciseRestDuration(entryId, ENTRY_REST_SECONDS)
                val fallbackSet = details.exercises.single().sets.single()
                val completed = fallbackSet.copy(
                    repetitions = 8,
                    weightKg = 60.0,
                    isCompleted = true,
                )
                assertTrue(repoA.updateWorkoutSet(completed))
                repoA.updateWorkoutSetRestSeconds(completed.id, SET_REST_SECONDS)

                // Inject an offline failure: the loop returns Deferred with no pull;
                // immutable intent is unchanged and the head state stays frozen.
                val clock = R10aMutableNow(System.currentTimeMillis())
                val beforeOffline = storeA.database.r10aOutboxRows(account.accountId)
                assertTrue("A must hold pending intent, got ${beforeOffline.size}", beforeOffline.isNotEmpty())
                val (offlineDelivery, offlinePublication) = r10aDeliverAndPublish(
                    storeA, account.accountId, R10aOfflinePushApi(apiA), R10aTestLease(),
                    nowMillis = clock::get,
                )
                assertTrue(
                    "offline push must defer, got [${offlineDelivery.pushStop}]",
                    offlineDelivery.pushStop is PushDeliveryStop.Deferred,
                )
                assertNull(offlineDelivery.pullStop)
                assertNull(offlineDelivery.hasOutstandingLocalWork)
                assertNull("no publication without an eligible tail", offlinePublication)
                val retainedOffline = storeA.database.r10aOutboxRows(account.accountId)
                r10aAssertOutboxFrozen(beforeOffline, retainedOffline, "A offline")

                // Reopen the same file with no reseed: the post-failure snapshot is exact.
                storeA = r10aReopen(context, storeA)
                assertEquals(retainedOffline, storeA.database.r10aOutboxRows(account.accountId))

                // Deterministic reconnect: advance the injected clock beyond the
                // durable backoff, then run through the actual engine.
                clock.advanceBeyond(storeA.database.r10aOutboxRetryAfterMax(account.accountId))
                val leaseA = R10aTestLease()
                val publicationA = r10aDeliverAndPublish(
                    storeA, account.accountId, r10aNewApi(transport, account), leaseA,
                    nowMillis = clock::get,
                ).second
                val publishedA = checkNotNull(publicationA) { "A must observe an eligible tail" }
                assertEquals(ProjectionPublicationStatus.PUBLISHED, publishedA.status)
                assertEquals(1, leaseA.closeCount)
                assertExpectedGraph(
                    storeA, "A populated", account.accountId,
                    workout.id, routineId, customId, entryId, completed.id,
                    routineEntryId, routineSetId,
                )
                r10aAssertOutboxEmpty(storeA.database, account.accountId, "A populated")
                r10aAssertBaselineComplete(storeA.database, account.accountId, "A populated")
                assertEquals(0, storeA.database.r10aStageCount(account.accountId))

                // B bootstraps via a paged real snapshot and must agree independently.
                val leaseB = R10aTestLease()
                val publishedB = r10aSnapshotConverge(storeB, account.accountId, apiB, leaseB, pageLimit = 1)
                assertEquals(ProjectionPublicationStatus.PUBLISHED, publishedB.status)
                assertEquals(1, leaseB.closeCount)
                assertExpectedGraph(
                    storeB, "B populated", account.accountId,
                    workout.id, routineId, customId, entryId, completed.id,
                    routineEntryId, routineSetId,
                )
                r10aAssertOutboxEmpty(storeB.database, account.accountId, "B populated")
                val cacheB = r10aAssertBaselineComplete(storeB.database, account.accountId, "B populated")
                assertEquals(cacheB.cacheEpoch, cacheB.materializedEpoch)
                assertEquals(0, storeB.database.r10aStageCount(account.accountId))
                r10aAssertQuiescentTail(storeA, account.accountId, apiA, "A quiescent")
                r10aAssertQuiescentTail(storeB, account.accountId, apiB, "B quiescent")
            } finally {
                r10aCloseAndDelete(context, storeA)
                r10aCloseAndDelete(context, storeB)
            }
        } finally {
            transport.client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun assertEmptyVisible(store: R10aStore, label: String, accountId: String) {
        val repo = store.repository()
        assertNull("$label expected no active workout", repo.activeWorkout())
        r10aAssertOutboxEmpty(store.database, accountId, "$label")
    }

    private suspend fun assertExpectedGraph(
        store: R10aStore,
        label: String,
        accountId: String,
        workoutId: String,
        routineId: String,
        customId: String,
        entryId: String,
        setId: String,
        routineEntryId: String,
        routineSetId: String,
    ) {
        val repo = store.repository()
        val details = checkNotNull(repo.workoutDetails(workoutId)) { "$label must expose the workout" }
        assertEquals("$label workout name", WORKOUT_NAME, details.workout.name)
        assertEquals("$label workout account", accountId, details.workout.accountId)
        assertEquals("$label started at", STARTED_AT_MILLIS, details.workout.startedAtMillis)
        assertEquals("$label status", "active", details.workout.status)
        assertNull("$label no finish timestamp", details.workout.finishedAtMillis)
        val entry = details.exercises.single()
        assertEquals("$label entry ID", entryId, entry.workoutExercise.id)
        assertEquals("$label entry exercise", customId, entry.workoutExercise.exerciseId)
        assertEquals("$label entry position", 0, entry.workoutExercise.position)
        assertEquals("$label entry notes", ENTRY_NOTES, entry.workoutExercise.notes)
        assertEquals("$label configured rest", ENTRY_REST_SECONDS, entry.workoutExercise.restDurationSeconds)
        assertEquals("$label entry name projection", CUSTOM_NAME, entry.workoutExercise.exerciseName)
        val set = entry.sets.single()
        assertEquals("$label set ID", setId, set.id)
        assertEquals("$label set position", 0, set.position)
        assertEquals("$label set parent", entryId, set.workoutExerciseId)
        assertEquals("$label set reps", 8, set.repetitions)
        assertEquals(60.0, set.weightKg!!, 0.0)
        assertTrue("$label set completed", set.isCompleted)
        assertEquals("$label elapsed rest", SET_REST_SECONDS, set.restSeconds)
        val routine = checkNotNull(repo.routineDetails(routineId)) { "$label must expose the routine" }
        assertEquals("$label routine name", ROUTINE_NAME, routine.routine.name)
        assertTrue("$label routine draft", routine.routine.isDraft)
        val routineEntry = routine.exercises.single()
        assertEquals("$label routine exercise", customId, routineEntry.routineExercise.exerciseId)
        assertEquals("$label routine position", 0, routineEntry.routineExercise.position)
        assertEquals("$label routine default rest", 30, routineEntry.routineExercise.restDurationSeconds)
        val routineSet = routineEntry.sets.single()
        assertEquals("$label routine entry ID", routineEntryId, routineEntry.routineExercise.id)
        assertEquals("$label routine fallback ID", routineSetId, routineSet.id)
        assertEquals("$label routine fallback position", 0, routineSet.position)
        assertNull("$label routine fallback reps", routineSet.repetitions)
        assertNull("$label routine fallback weight", routineSet.weightKg)
        val customDetails = checkNotNull(repo.exerciseDetails(customId)) { "$label must expose the custom" }
        assertEquals("$label custom name", CUSTOM_NAME, customDetails.exercise.name)
        assertEquals("$label custom type", "reps", customDetails.exercise.type)
        assertTrue("$label custom user-created", customDetails.exercise.isUserCreated)
        assertTrue(
            "$label exact live custom count",
            store.database.r10aCount(
                "SELECT COUNT(*) FROM exercises WHERE is_user_created = 1 AND deleted_at_millis IS NULL",
                emptyArray(),
            ) == 1,
        )
        assertTrue(
            "$label no custom muscle relationships",
            store.database.r10aCount(
                "SELECT COUNT(*) FROM exercise_muscle_groups WHERE exercise_id = ?",
                arrayOf(customId),
            ) == 0,
        )
        assertTrue(
            "$label no custom equipment relationships",
            store.database.r10aCount(
                "SELECT COUNT(*) FROM exercise_equipment WHERE exercise_id = ?",
                arrayOf(customId),
            ) == 0,
        )
        assertTrue(
            "$label exact live workout count",
            store.database.r10aCount(
                "SELECT COUNT(*) FROM workouts WHERE deleted_at_millis IS NULL",
                emptyArray(),
            ) == 1,
        )
        assertTrue(
            "$label exact live routine count",
            store.database.r10aCount(
                "SELECT COUNT(*) FROM routines WHERE deleted_at_millis IS NULL",
                emptyArray(),
            ) == 1,
        )
        val workoutCanonical = checkNotNull(
            store.database.r10aReadCanonical(accountId, "workout", workoutId),
        ) { "$label must hold workout canonical" }
        assertTrue("$label workout revision positive", (workoutCanonical.serverRevision ?: 0L) > 0L)
        assertEquals("$label workout canonical state", "upsert", workoutCanonical.canonicalState)
        val customCanonical = checkNotNull(
            store.database.r10aReadCanonical(accountId, "customExercise", customId),
        ) { "$label must hold custom canonical" }
        assertTrue("$label custom revision positive", (customCanonical.serverRevision ?: 0L) > 0L)
        val active = checkNotNull(repo.activeWorkout()) { "$label must expose the active workout" }
        assertEquals("$label active id", workoutId, active.id)
        assertNotNull("$label cache", store.database.r10aReadCache(accountId))
    }
}
