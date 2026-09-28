package com.dragosfotache.gymtracka.data.gym

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Real-Room coverage for the v9 sync foundation tables and root metadata. */
class SyncFoundationTest : GymDatabaseTest() {

    @Test
    fun oldConstructorPaths_keepWorkingWithInertDefaults() = runTest {
        val workout = WorkoutEntity(id = "workout", name = "Name", startedAtMillis = 1_000L)
        assertNull(workout.accountId)
        assertNull(workout.serverRevision)
        assertEquals(0L, workout.localGeneration)
        assertEquals("local_only", workout.syncState)
        assertNull(workout.deletedAtMillis)

        val routine = RoutineEntity(id = "routine", name = "Routine")
        assertNull(routine.accountId)
        assertEquals(0L, routine.localGeneration)
        assertEquals("local_only", routine.syncState)

        val exercise = ExerciseEntity(
            id = "exercise",
            name = "Exercise",
            type = "reps",
            difficultyLevel = null,
            forceType = null,
            mechanics = null,
            category = null,
            instructions = null,
        )
        assertNull(exercise.accountId)
        assertEquals("local_only", exercise.syncState)

        database.workoutDao().insertWorkout(workout)
        database.routineDao().insertRoutine(routine)
        database.exerciseDao().insertExercise(exercise)

        val storedWorkout = database.workoutDao().getWorkout("workout")
        assertNotNull(storedWorkout)
        assertEquals("local_only", storedWorkout?.syncState)
        assertEquals(workout, storedWorkout)
        // Existing feature behavior is unchanged: the active workout is still found.
        assertEquals("workout", database.workoutDao().getActiveWorkout()?.id)
    }

    @Test
    fun outbox_mutationIdsAreUnique() = runTest {
        val syncDao = database.syncDao()
        syncDao.insertOutbox(pendingEntry("mutation", "account"))
        try {
            syncDao.insertOutbox(pendingEntry("mutation", "account"))
            fail("Duplicate mutationId must abort")
        } catch (_: SQLiteConstraintException) {
            // Expected: unique index on mutation_id.
        }
        assertEquals(1, syncDao.getOutboxForAccount("account").size)
    }

    @Test
    fun outbox_accountReadsUpdatesAndDeletesAreIsolated() = runTest {
        val syncDao = database.syncDao()
        syncDao.insertOutbox(pendingEntry("mutation-a", "account-a"))
        syncDao.insertOutbox(pendingEntry("mutation-b", "account-b"))

        assertEquals(listOf("mutation-a"), syncDao.getOutboxForAccount("account-a").map { it.mutationId })
        assertEquals(listOf("mutation-b"), syncDao.getOutboxForAccount("account-b").map { it.mutationId })
        assertNull(syncDao.getOutboxMutation("mutation-a", "account-b"))

        assertEquals(
            0,
            syncDao.replacePendingPayload("mutation-a", "account-b", "{}", 1, 2L),
        )
        assertEquals(
            0,
            syncDao.freezePendingMutation("mutation-a", "account-b"),
        )
        syncDao.deleteMutation("mutation-a", "account-b")
        assertNotNull(syncDao.getOutboxMutation("mutation-a", "account-a"))

        assertEquals(
            1,
            syncDao.replacePendingPayload("mutation-a", "account-a", "{\"v\":2}", 2, 2L),
        )
        syncDao.deleteMutation("mutation-a", "account-a")
        assertNull(syncDao.getOutboxMutation("mutation-a", "account-a"))
        assertNotNull(syncDao.getOutboxMutation("mutation-b", "account-b"))
    }

    @Test
    fun outbox_orderingFollowsExplicitSequence() = runTest {
        val syncDao = database.syncDao()
        // IDs deliberately oppose insertion order: lexical sorting would yield
        // alpha, mu, zeta, so only sequence order can produce the expected result.
        val first = syncDao.insertOutbox(
            pendingEntry("zeta", "account", retryAfterMillis = 9_000L),
        )
        val second = syncDao.insertOutbox(
            pendingEntry("alpha", "account", retryAfterMillis = 1_000L),
        )
        val third = syncDao.insertOutbox(
            pendingEntry("mu", "account", retryAfterMillis = 5_000L),
        )
        assertTrue(first < second)
        assertTrue(second < third)

        val ordered = syncDao.getOutboxForAccount("account")
        assertEquals(listOf("zeta", "alpha", "mu"), ordered.map { it.mutationId })
        assertEquals(listOf(first, second, third), ordered.map { it.sequence })
    }

    @Test
    fun frozenPayload_rejectsPendingReplacementAndKeepsIdentity() = runTest {
        val syncDao = database.syncDao()
        syncDao.insertOutbox(pendingEntry("mutation", "account"))

        val initial = syncDao.getOutboxMutation("mutation", "account")
        assertNotNull(initial)
        assertEquals("pending", initial?.state)
        assertEquals(1, initial?.payloadVersion)
        assertEquals(0, initial?.attemptCount)
        assertNull(initial?.retryAfterMillis)

        assertEquals(
            1,
            syncDao.replacePendingPayload("mutation", "account", "{\"v\":2}", 2, 3L),
        )
        // Stale and same-generation replacements are rejected and preserve the newer state.
        assertEquals(
            0,
            syncDao.replacePendingPayload("mutation", "account", "{\"stale\":true}", 1, 2L),
        )
        assertEquals(
            0,
            syncDao.replacePendingPayload("mutation", "account", "{\"same\":true}", 3, 3L),
        )
        val stillPending = syncDao.getOutboxMutation("mutation", "account")
        assertEquals("pending", stillPending?.state)
        assertEquals("{\"v\":2}", stillPending?.payloadJson)
        assertEquals(2, stillPending?.payloadVersion)
        assertEquals(3L, stillPending?.localGeneration)

        assertEquals(1, syncDao.freezePendingMutation("mutation", "account"))

        val frozen = syncDao.getOutboxMutation("mutation", "account")
        assertNotNull(frozen)
        assertEquals("frozen", frozen?.state)
        // The metadata transition preserves payload identity.
        assertEquals("mutation", frozen?.mutationId)
        assertEquals("{\"v\":2}", frozen?.payloadJson)
        assertEquals(2, frozen?.payloadVersion)
        assertEquals(3L, frozen?.localGeneration)

        assertEquals(
            0,
            syncDao.replacePendingPayload("mutation", "account", "{\"v\":3}", 3, 4L),
        )
        assertEquals(0, syncDao.freezePendingMutation("mutation", "account"))
        assertEquals("{\"v\":2}", syncDao.getOutboxMutation("mutation", "account")?.payloadJson)
    }

    @Test
    fun outbox_survivesPhysicalRootDeletion() = runTest {
        database.exerciseDao().insertExercise(
            ExerciseEntity(
                id = "exercise",
                name = "Exercise",
                type = "reps",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = null,
                instructions = null,
            ),
        )
        insertWorkoutAggregate(
            workout = WorkoutEntity(id = "workout", name = "Name", startedAtMillis = 1_000L),
            exercises = listOf(
                WorkoutExerciseEntity(
                    id = "workout-exercise",
                    workoutId = "workout",
                    exerciseId = "exercise",
                    position = 0,
                ),
            ),
            sets = listOf(
                WorkoutSetEntity(
                    id = "workout-set",
                    workoutExerciseId = "workout-exercise",
                    position = 0,
                ),
            ),
        )
        database.syncDao().insertOutbox(
            pendingEntry("mutation", "account").copy(aggregateId = "workout"),
        )

        database.workoutDao().deleteWorkout("workout")
        assertNull(database.workoutDao().getWorkout("workout"))

        val surviving = database.syncDao().getOutboxMutation("mutation", "account")
        assertNotNull(surviving)
        assertEquals("workout", surviving?.aggregateId)
    }

    @Test
    fun supportTables_roundTripIndependentlyPerAccount() = runTest {
        val syncDao = database.syncDao()
        syncDao.upsertSyncState(SyncStateEntity("account-a", cursor = "cursor-a"))
        syncDao.upsertSyncState(
            SyncStateEntity("account-b", cursor = "cursor-b", lastSuccessMillis = 7L),
        )
        syncDao.upsertDeviceState(SyncDeviceStateEntity())
        syncDao.upsertCatalogueState(CatalogueStateEntity())

        syncDao.upsertSyncState(SyncStateEntity("account-a", cursor = "cursor-a2"))
        assertEquals("cursor-a2", syncDao.getSyncState("account-a")?.cursor)
        assertEquals("cursor-b", syncDao.getSyncState("account-b")?.cursor)
        assertEquals(7L, syncDao.getSyncState("account-b")?.lastSuccessMillis)
        assertNull(syncDao.getSyncState("unknown"))

        val device = syncDao.getDeviceState()
        assertNotNull(device)
        assertNull(device?.activeAccountId)
        assertEquals(0L, device?.sessionGeneration)
        assertEquals("local_only", device?.cacheState)

        syncDao.upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = "account-a", sessionGeneration = 1L),
        )
        assertEquals("account-a", syncDao.getDeviceState()?.activeAccountId)
        assertEquals(1L, syncDao.getDeviceState()?.sessionGeneration)
        assertEquals("cursor-b", syncDao.getSyncState("account-b")?.cursor)

        assertNull(syncDao.getCatalogueState()?.version)
        syncDao.upsertCatalogueState(CatalogueStateEntity(version = "catalogue-12"))
        assertEquals("catalogue-12", syncDao.getCatalogueState()?.version)
        assertEquals("cursor-a2", syncDao.getSyncState("account-a")?.cursor)
    }

    @Test
    fun singletonRows_rejectNonSingletonIds() {
        try {
            SyncDeviceStateEntity(id = 2)
            fail("Device singleton must reject id != 1")
        } catch (_: IllegalArgumentException) {
            // Expected: application singleton row guard.
        }
        try {
            CatalogueStateEntity(id = 0)
            fail("Catalogue singleton must reject id != 1")
        } catch (_: IllegalArgumentException) {
            // Expected: application singleton row guard.
        }
        assertEquals(1, SyncDeviceStateEntity().id)
        assertEquals(1, CatalogueStateEntity().id)
    }

    @Test
    fun clearingActiveAccount_retainsCachedAccountCursorAndOutbox() = runTest {
        val syncDao = database.syncDao()
        syncDao.upsertSyncState(SyncStateEntity("account-a", cursor = "cursor-a"))
        syncDao.insertOutbox(pendingEntry("mutation", "account-a"))
        syncDao.upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = "account-a", cachedAccountId = "account-a"),
        )

        // Storage coverage only: clearing the active account retains the cached
        // account and its cursor/outbox data. No sign-out behavior is implemented.
        syncDao.upsertDeviceState(
            SyncDeviceStateEntity(activeAccountId = null, cachedAccountId = "account-a"),
        )

        val device = syncDao.getDeviceState()
        assertNotNull(device)
        assertNull(device?.activeAccountId)
        assertEquals("account-a", device?.cachedAccountId)
        assertEquals("cursor-a", syncDao.getSyncState("account-a")?.cursor)
        assertNotNull(syncDao.getOutboxMutation("mutation", "account-a"))
    }

    private fun pendingEntry(
        mutationId: String,
        accountId: String,
        retryAfterMillis: Long? = null,
    ) = SyncOutboxEntity(
        mutationId = mutationId,
        accountId = accountId,
        aggregateType = "workout",
        aggregateId = "aggregate",
        mutationType = "upsert",
        localGeneration = 1L,
        payloadJson = "{\"v\":1}",
        retryAfterMillis = retryAfterMillis,
    )
}
