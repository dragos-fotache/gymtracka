package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.store.ExtraAffectedRoot
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.SyncStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * storage/capture acceptance. Real Room, no mocked DAOs.
 */
class CanonicalStorageCaptureTest : GymDatabaseTest() {

    private companion object {
        const val ACCOUNT = "account-a"
        const val OTHER_ACCOUNT = "account-b"
        const val GENERATION = 7L
        const val STARTED_AT = 1_790_258_400_000L

        const val WORKOUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val WORKOUT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val OLD_WORKOUT = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val ROUTINE = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val CUSTOM = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val BUNDLED = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        const val MISSING = "99999999-9999-9999-9999-999999999999"

        const val MUTATION_1 = "11111111-1111-1111-1111-111111111111"
        const val MUTATION_2 = "22222222-2222-2222-2222-222222222222"
        const val MUTATION_3 = "33333333-3333-3333-3333-333333333333"
        const val MUTATION_4 = "44444444-4444-4444-4444-444444444444"
        const val MUTATION_5 = "55555555-5555-5555-5555-555555555555"
        const val MUTATION_6 = "66666666-6666-6666-6666-666666666666"
        const val CANONICAL_RECORD_42 = """{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"W","status":"active","startedAt":"2026-09-24T14:00:00Z","finishedAt":null,"revision":42,"exercises":[{"id":"eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee","exerciseId":"ffffffff-ffff-ffff-ffff-ffffffffffff","exerciseName":"Press","position":0,"notes":"","restDurationSeconds":30,"sets":[]}],"totalVolumeKg":0.0}"""
        const val CANONICAL_RECORD_11 = """{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"W","status":"finished","startedAt":"2026-09-24T14:00:00Z","finishedAt":"2026-09-24T15:02:12Z","revision":11,"exercises":[{"id":"eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee","exerciseId":"ffffffff-ffff-ffff-ffff-ffffffffffff","exerciseName":"Press","position":0,"notes":"Keep","restDurationSeconds":90,"sets":[{"id":"cccccccc-cccc-cccc-cccc-cccccccccccc","position":0,"repetitions":8,"weightKg":100.0,"isCompleted":true,"restSeconds":87}]}],"totalVolumeKg":800.0}"""
    }

    private fun device(account: String? = ACCOUNT, generation: Long = GENERATION) =
        SyncDeviceStateEntity(activeAccountId = account, sessionGeneration = generation)

    private fun store(
        session: RepositorySession = RepositorySession(ACCOUNT, GENERATION),
        mutations: List<String> = listOf(
            MUTATION_1, MUTATION_2, MUTATION_3, MUTATION_4, MUTATION_5, MUTATION_6,
        ) + (7..32).map { "%08x-1111-1111-1111-%012x".format(it, it) },
    ): SyncStore {
        val ids = mutations.toMutableList()
        return SyncStore(database, session) { ids.removeFirst() }
    }

    private suspend fun seedWorkout(
        id: String = WORKOUT,
        account: String? = ACCOUNT,
        generation: Long = 0L,
        revision: Long? = 5L,
        name: String = "W",
    ) {
        database.workoutDao().insertWorkout(
            WorkoutEntity(
                id = id,
                name = name,
                startedAtMillis = 1_000L,
                accountId = account,
                serverRevision = revision,
                localGeneration = generation,
            ),
        )
    }

    private suspend fun seedRoutine(
        id: String = ROUTINE,
        account: String? = ACCOUNT,
    ) {
        database.routineDao().insertRoutine(
            RoutineEntity(
                id = id,
                name = "R",
                isDraft = true,
                accountId = account,
            ),
        )
    }

    private suspend fun seedCustom(
        id: String = CUSTOM,
        account: String? = ACCOUNT,
        bundled: Boolean = false,
    ) {
        database.exerciseDao().insertExercise(
            ExerciseEntity(
                id = id,
                name = "Custom",
                type = "reps",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = null,
                instructions = null,
                isUserCreated = !bundled,
                accountId = if (bundled) null else account,
            ),
        )
    }

    private fun workoutUpsertInput(id: String) =
        WorkoutInputModel(id = id, name = "W", status = "active", startedAtMillis = STARTED_AT, exercises = emptyList())

    private fun routineUpsertInput(id: String) =
        RoutineInputModel(id = id, name = "R", isDraft = true, exercises = emptyList())

    private fun customUpsertInput(id: String) =
        CustomExerciseInputModel(
            id = id,
            name = "Custom",
            primaryMuscleGroupId = null,
            secondaryMuscleGroupIds = emptyList(),
            equipmentId = null,
            equipmentNumItems = null,
            category = null,
            difficultyLevel = null,
            forceType = null,
            mechanics = null,
            instructions = null,
        )

    private suspend fun canonicalCount(): Int =
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM sync_canonical_roots",
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private suspend fun cacheCount(): Int =
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM sync_account_cache",
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun seedCanonicalRow(
        accountId: String,
        aggregateType: String,
        aggregateId: String,
        serverRevision: Long?,
        canonicalState: String,
        canonicalJson: String?,
        watermark: Long,
        cacheEpoch: Long,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO sync_canonical_roots
                (account_id, aggregate_type, aggregate_id, server_revision,
                 canonical_state, canonical_json, local_generation_watermark, cache_epoch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                accountId, aggregateType, aggregateId, serverRevision,
                canonicalState, canonicalJson, watermark, cacheEpoch,
            ),
        )
    }

    private fun seedCacheRow(
        accountId: String,
        baselineComplete: Boolean,
        cacheEpoch: Long,
        projectionDirty: Boolean,
        materializedEpoch: Long,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO sync_account_cache
                (account_id, baseline_complete, cache_epoch, projection_dirty, materialized_epoch)
                VALUES (?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                accountId, if (baselineComplete) 1 else 0, cacheEpoch,
                if (projectionDirty) 1 else 0, materializedEpoch,
            ),
        )
    }

    @Test
    fun emptySchema_startsInert() = runTest {
        assertEquals(0, canonicalCount())
        assertEquals(0, cacheCount())
        assertTrue(database.syncCanonicalDao().getRootsForAccount(ACCOUNT).isEmpty())
        assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT))
        assertNull(database.syncCanonicalDao().getAccountCache(ACCOUNT))
    }

    @Test
    fun canonicalKeys_isolateAccountAndType() = runTest {
        seedCanonicalRow(ACCOUNT, "workout", WORKOUT, null, "unobserved", null, 3L, 0L)
        seedCanonicalRow(OTHER_ACCOUNT, "workout", WORKOUT, null, "unobserved", null, 9L, 0L)
        seedCanonicalRow(ACCOUNT, "routine", WORKOUT, null, "unobserved", null, 5L, 0L)

        val sameAccountSameType = database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)
        assertNotNull(sameAccountSameType)
        assertEquals(3L, sameAccountSameType?.localGenerationWatermark)
        assertEquals("unobserved", sameAccountSameType?.canonicalState)
        assertNull(sameAccountSameType?.serverRevision)
        assertNull(sameAccountSameType?.canonicalJson)

        val otherAccount = database.syncCanonicalDao().getRoot(OTHER_ACCOUNT, "workout", WORKOUT)
        assertEquals(9L, otherAccount?.localGenerationWatermark)

        val otherType = database.syncCanonicalDao().getRoot(ACCOUNT, "routine", WORKOUT)
        assertEquals(5L, otherType?.localGenerationWatermark)

        assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", MISSING))
        assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "customExercise", WORKOUT))

        val accountRoots = database.syncCanonicalDao().getRootsForAccount(ACCOUNT)
        assertEquals(2, accountRoots.size)
        assertTrue(accountRoots.all { it.accountId == ACCOUNT })
        assertTrue(database.syncCanonicalDao().getRootsForAccount("unknown").isEmpty())
    }

    @Test
    fun ledger_survivesPhysicalRootRemoval() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val syncStore = store()

        val outcome = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }!!
        assertEquals(1L, outcome.request?.affectedRoots?.single()?.localGeneration)

        database.workoutDao().deleteWorkout(WORKOUT)
        assertNull(database.workoutDao().getWorkout(WORKOUT))

        val retained = database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)
        assertNotNull(retained)
        assertEquals(1L, retained?.localGenerationWatermark)
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun workout_changedCaptureReservesLedgerAndDirtiesProjection() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val syncStore = store()

        val outcome = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }!!
        assertEquals(false, outcome.coalesced)
        assertEquals(MUTATION_1, outcome.request?.envelope?.mutationId)
        assertEquals(1L, outcome.request?.envelope?.localGeneration)
        assertEquals(
            listOf(Triple(AggregateType.WORKOUT, WORKOUT, 1L)),
            outcome.request?.affectedRoots?.map { Triple(it.aggregateType, it.aggregateId, it.localGeneration) },
        )

        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(1L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)
        assertEquals(true, database.syncCanonicalDao().getAccountCache(ACCOUNT)?.projectionDirty)
        assertEquals(false, database.syncCanonicalDao().getAccountCache(ACCOUNT)?.baselineComplete)
        val rows = database.syncDao().getOutboxForAccount(ACCOUNT)
        assertEquals(1, rows.size)
        assertEquals(MUTATION_1, rows.single().mutationId)
        assertEquals(1L, rows.single().localGeneration)
    }

    @Test
    fun routine_changedCaptureReservesLedger() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedRoutine()
        val syncStore = store()

        val outcome = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.ROUTINE, ROUTINE, MutationKind.ROUTINE_UPSERT,
                changed = true,
            ) { MutationPayload.RoutineUpsert(routineUpsertInput(ROUTINE)) }
        }!!
        assertEquals(false, outcome.coalesced)
        assertEquals(1L, outcome.request?.envelope?.localGeneration)
        assertEquals(1L, database.syncCanonicalDao().getRoot(ACCOUNT, "routine", ROUTINE)?.localGenerationWatermark)
        assertEquals(true, database.syncCanonicalDao().getAccountCache(ACCOUNT)?.projectionDirty)
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun custom_changedCaptureReservesLedger() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedCustom()
        val syncStore = store()

        val outcome = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.CUSTOM_EXERCISE, CUSTOM, MutationKind.CUSTOM_EXERCISE_UPSERT,
                changed = true,
            ) { MutationPayload.CustomExerciseUpsert(customUpsertInput(CUSTOM)) }
        }!!
        assertEquals(false, outcome.coalesced)
        assertEquals(1L, outcome.request?.envelope?.localGeneration)
        assertEquals(
            1L,
            database.syncCanonicalDao().getRoot(ACCOUNT, "customExercise", CUSTOM)?.localGenerationWatermark,
        )
        assertEquals(true, database.syncCanonicalDao().getAccountCache(ACCOUNT)?.projectionDirty)
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun capture_noOpCreatesNoLedgerOrCacheOrOutbox() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val outcome = store().withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = false,
            ) { error("must not run") }
        }
        assertNull(outcome)
        assertEquals(0, canonicalCount())
        assertEquals(0, cacheCount())
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
    }

    @Test
    fun capture_signedOutCreatesNoLedgerOrCacheOrOutbox() = runTest {
        seedWorkout(account = null)
        var factoryCalls = 0
        val outcome = store(RepositorySession.SIGNED_OUT).withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) {
                factoryCalls++
                error("payload factory must not run while signed out")
            }
        }
        assertNotNull(outcome)
        assertNull(outcome?.request)
        assertEquals(0, factoryCalls)
        assertEquals(0, canonicalCount())
        assertEquals(0, cacheCount())
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun defaultsOnlySeeds_useInertDefaults() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_canonical_roots (account_id, aggregate_type, aggregate_id)" +
                " VALUES ('$ACCOUNT', 'workout', '$WORKOUT')",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_account_cache (account_id) VALUES ('$ACCOUNT')",
        )

        val root = database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)!!
        assertEquals(ACCOUNT, root.accountId)
        assertEquals("workout", root.aggregateType)
        assertEquals(WORKOUT, root.aggregateId)
        assertNull(root.serverRevision)
        assertEquals("unobserved", root.canonicalState)
        assertNull(root.canonicalJson)
        assertEquals(0L, root.localGenerationWatermark)
        assertEquals(0L, root.cacheEpoch)

        val cache = database.syncCanonicalDao().getAccountCache(ACCOUNT)!!
        assertEquals(ACCOUNT, cache.accountId)
        assertEquals(false, cache.baselineComplete)
        assertEquals(0L, cache.cacheEpoch)
        assertEquals(false, cache.projectionDirty)
        assertEquals(0L, cache.materializedEpoch)
    }

    @Test
    fun capture_rejectsBundledForeignMissingAndStale() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(account = OTHER_ACCOUNT)
        seedCustom(id = BUNDLED, account = null, bundled = true)
        seedCanonicalRow(ACCOUNT, "workout", WORKOUT_B, null, "unobserved", null, 5L, 0L)
        seedCacheRow(ACCOUNT, false, 0L, false, 0L)
        val syncStore = store()

        suspend fun rejects(block: suspend () -> Any?) {
            try {
                block()
            } catch (_: SyncModelException) {
                return
            }
            fail("Expected SyncModelException")
        }

        rejects {
            syncStore.withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.CUSTOM_EXERCISE, BUNDLED, MutationKind.CUSTOM_EXERCISE_UPSERT,
                    changed = true,
                ) { MutationPayload.CustomExerciseUpsert(customUpsertInput(BUNDLED)) }
            }
        }
        rejects {
            syncStore.withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) {
                    MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT)
                }
            }
        }
        rejects {
            syncStore.withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, MISSING, MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) {
                    MutationPayload.WorkoutStart(MISSING, "W", STARTED_AT)
                }
            }
        }
        try {
            store(RepositorySession(ACCOUNT, GENERATION - 1)).withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) {
                    MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT)
                }
            }
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }

        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT_B)?.localGenerationWatermark,
        )
        assertEquals(
            false,
            database.syncCanonicalDao().getAccountCache(ACCOUNT)?.projectionDirty,
        )
        assertEquals(1, canonicalCount())
        assertEquals(1, cacheCount())
    }

    @Test
    fun ledger_belowEqualAboveDomainUsesMaxPlusOne() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(generation = 5L)
        val syncStore = store()

        // Ledger below domain: 3 < 5, next is 6.
        seedCanonicalRow(ACCOUNT, "workout", WORKOUT, null, "unobserved", null, 3L, 0L)
        val below = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }!!
        assertEquals(6L, below.request?.envelope?.localGeneration)
        assertEquals(6L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)
        assertEquals(6L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT).size)

        // Ledger equal to domain: next is 7. Semantic starts never coalesce: the
        // second command appends with a fresh identity while still advancing the ledger.
        val equal = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }!!
        assertEquals(false, equal.coalesced)
        assertTrue(below.request?.envelope?.mutationId != equal.request?.envelope?.mutationId)
        assertEquals(7L, equal.request?.envelope?.localGeneration)
        assertEquals(7L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)
        assertEquals(2, database.syncDao().getOutboxForAccount(ACCOUNT).size)

        // Ledger above domain: force domain back to 2 via SQL, ledger stays 7, next is 8.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = 2 WHERE id = '$WORKOUT'",
        )
        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
        val above = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }!!
        assertEquals(8L, above.request?.envelope?.localGeneration)
        assertEquals(8L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)
        assertEquals(8L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
    }

    @Test
    fun ledger_recreatedRootCannotResetGeneration() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val syncStore = store()

        repeat(3) {
            syncStore.withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
            }
        }
        assertEquals(3L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)

        database.workoutDao().deleteWorkout(WORKOUT)
        database.workoutDao().insertWorkout(
            WorkoutEntity(
                id = WORKOUT,
                name = "Recreated",
                startedAtMillis = 1_000L,
                accountId = ACCOUNT,
                serverRevision = 5L,
                localGeneration = 0L,
            ),
        )
        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")

        val after = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "Recreated", STARTED_AT) }
        }!!
        assertEquals(4L, after.request?.envelope?.localGeneration)
        assertEquals(4L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)
    }

    @Test
    fun ledger_rejectsOverflowAndNegativeGenerations() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(generation = Long.MAX_VALUE)
        try {
            store().withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
            }
            fail("Expected SyncModelException for overflow")
        } catch (_: SyncModelException) {
        }
        assertEquals(
            Long.MAX_VALUE,
            database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration,
        )
        assertEquals(0, canonicalCount())
        assertEquals(0, cacheCount())
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)

        // Negative domain generation is rejected.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = -1 WHERE id = '$WORKOUT'",
        )
        try {
            store().withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
            }
            fail("Expected SyncModelException for negative domain generation")
        } catch (_: SyncModelException) {
        }
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)

        // Negative ledger watermark is rejected.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = 0 WHERE id = '$WORKOUT'",
        )
        seedCanonicalRow(ACCOUNT, "workout", WORKOUT_B, null, "unobserved", null, -4L, 0L)
        seedWorkout(id = WORKOUT_B)
        try {
            store().withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT_B, MutationKind.WORKOUT_START,
                    changed = true,
                ) { MutationPayload.WorkoutStart(WORKOUT_B, "W", STARTED_AT) }
            }
            fail("Expected SyncModelException for negative ledger watermark")
        } catch (_: SyncModelException) {
        }
        assertEquals(
            -4L,
            database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT_B)?.localGenerationWatermark,
        )
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT_B)?.localGeneration)
    }

    @Test
    fun ledger_maxValueBoundaryRefusesThenAdvances() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        seedCanonicalRow(ACCOUNT, "workout", WORKOUT, null, "unobserved", null, Long.MAX_VALUE, 0L)
        try {
            store().withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
            }
            fail("Expected SyncModelException when the ledger is already at Long.MAX_VALUE")
        } catch (_: SyncModelException) {
        }
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(
            Long.MAX_VALUE,
            database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark,
        )
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)

        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET local_generation_watermark = ${Long.MAX_VALUE - 1} " +
                "WHERE account_id = '$ACCOUNT' AND aggregate_type = 'workout' AND aggregate_id = '$WORKOUT'",
        )
        val outcome = store().withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }!!
        assertEquals(Long.MAX_VALUE, outcome.request?.envelope?.localGeneration)
        assertEquals(
            Long.MAX_VALUE,
            database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark,
        )
        assertEquals(Long.MAX_VALUE, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun coalescedTail_advancesLedgerWithStableIdentity() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val syncStore = store()

        val first = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) { MutationPayload.WorkoutUpsert(workoutUpsertInput(WORKOUT)) }
        }!!
        assertEquals(false, first.coalesced)
        assertEquals(MUTATION_1, first.request?.envelope?.mutationId)

        val second = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) { MutationPayload.WorkoutUpsert(workoutUpsertInput(WORKOUT)) }
        }!!
        assertEquals(true, second.coalesced)
        assertEquals(MUTATION_1, second.request?.envelope?.mutationId)
        assertEquals(2L, second.request?.envelope?.localGeneration)
        assertEquals(
            2L,
            second.request?.affectedRoots?.single()?.localGeneration,
        )
        assertEquals(2L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)
        assertEquals(2L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT).size)
        assertEquals(2L, database.syncDao().getOutboxForAccount(ACCOUNT).single().localGeneration)
    }

    @Test
    fun frozenTail_keepsExactBytesAndLaterWorkAdvancesLedger() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val syncStore = store()

        val first = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }!!
        val frozenBefore = database.syncDao().getOutboxMutation(MUTATION_1, ACCOUNT)!!
        database.syncDao().freezePendingMutation(MUTATION_1, ACCOUNT)

        val second = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W2", STARTED_AT) }
        }!!
        assertEquals(false, second.coalesced)
        assertEquals(MUTATION_2, second.request?.envelope?.mutationId)
        assertEquals(2L, second.request?.envelope?.localGeneration)
        assertEquals(2L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)

        val rows = database.syncDao().getOutboxForAccount(ACCOUNT)
        assertEquals(2, rows.size)
        assertEquals("frozen", rows.first().state)
        assertEquals(frozenBefore.payloadJson, rows.first().payloadJson)
        assertEquals(frozenBefore.payloadVersion, rows.first().payloadVersion)
        assertEquals(first.request?.requestJson, rows.first().payloadJson.let {
            org.json.JSONObject(it).getString("requestJson")
        })
    }

    @Test
    fun multiRoot_startAdvancesBothLedgersOnce() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(id = OLD_WORKOUT, name = "Old")
        seedWorkout(id = WORKOUT, name = "New")
        val syncStore = store()

        val outcome = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
                extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, OLD_WORKOUT)),
            ) {
                MutationPayload.WorkoutStart(WORKOUT, "New", STARTED_AT)
            }
        }!!
        assertEquals(false, outcome.coalesced)
        assertEquals(1L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)
        assertEquals(1L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", OLD_WORKOUT)?.localGenerationWatermark)
        assertEquals(
            setOf(
                Triple(AggregateType.WORKOUT, WORKOUT, 1L),
                Triple(AggregateType.WORKOUT, OLD_WORKOUT, 1L),
            ),
            outcome.request?.affectedRoots
                ?.map { Triple(it.aggregateType, it.aggregateId, it.localGeneration) }
                ?.toSet(),
        )
        assertEquals(true, database.syncCanonicalDao().getAccountCache(ACCOUNT)?.projectionDirty)
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun multiRoot_duplicateExtraIsRejectedWithoutSideEffects() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        seedCanonicalRow(ACCOUNT, "workout", WORKOUT_B, null, "unobserved", null, 5L, 0L)
        seedCacheRow(ACCOUNT, false, 0L, false, 0L)
        val syncStore = store()

        try {
            syncStore.withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                    extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, WORKOUT)),
                ) {
                    MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT)
                }
            }
            fail("Expected SyncModelException for duplicate extra root")
        } catch (_: SyncModelException) {
        }
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT_B)?.localGenerationWatermark,
        )
        assertEquals(
            false,
            database.syncCanonicalDao().getAccountCache(ACCOUNT)?.projectionDirty,
        )
        assertEquals(1, canonicalCount())
        assertEquals(1, cacheCount())
    }

    @Test
    fun rollback_extraFailureRestoresLedgerDirtyAndOutbox() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(name = "A")
        seedWorkout(id = WORKOUT_B, name = "B")
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = MUTATION_1,
                accountId = ACCOUNT,
                aggregateType = "workout",
                aggregateId = WORKOUT_B,
                mutationType = "workout.upsert",
                localGeneration = 1L,
                payloadVersion = 1,
                payloadJson = "{}",
                state = "pending",
            ),
        )
        try {
            store(mutations = listOf(MUTATION_1)).withFencedTransaction { transaction ->
                database.workoutDao().updateWorkoutName(WORKOUT, "Edited A")
                database.workoutDao().updateWorkoutName(WORKOUT_B, "Edited B")
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                    extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, WORKOUT_B)),
                ) {
                    MutationPayload.WorkoutStart(WORKOUT, "A", STARTED_AT)
                }
            }
            fail("Expected constraint failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
        }
        assertEquals("A", database.workoutDao().getWorkout(WORKOUT)?.name)
        assertEquals("B", database.workoutDao().getWorkout(WORKOUT_B)?.name)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT_B)?.localGeneration)
        assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT))
        assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT_B))
        assertNull(database.syncCanonicalDao().getAccountCache(ACCOUNT))
        assertEquals(1, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun rollback_blockedOutboxInsertRestoresAllRows() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_capture_outbox BEFORE INSERT ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            try {
                store().withFencedTransaction { transaction ->
                    database.workoutDao().updateWorkoutName(WORKOUT, "Edited")
                    transaction.captureEdit(
                        AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                        changed = true,
                    ) { MutationPayload.WorkoutStart(WORKOUT, "Edited", STARTED_AT) }
                }
                fail("Expected trigger failure")
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
            }
            assertEquals("W", database.workoutDao().getWorkout(WORKOUT)?.name)
            assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
            assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT))
            assertNull(database.syncCanonicalDao().getAccountCache(ACCOUNT))
            assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_capture_outbox")
        }
    }

    @Test
    fun rollback_payloadFactoryFailureRestoresAllRows() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        try {
            store().withFencedTransaction { transaction ->
                database.workoutDao().updateWorkoutName(WORKOUT, "Edited")
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                ) { error("injected payload failure") }
            }
            fail("Expected injected payload failure")
        } catch (_: IllegalStateException) {
        }
        assertEquals("W", database.workoutDao().getWorkout(WORKOUT)?.name)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT))
        assertNull(database.syncCanonicalDao().getAccountCache(ACCOUNT))
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun rollback_cancellationRestoresAllRows() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        // The capture runs in an owned child of the still-active observing scope. A gate
        // fires only after every effect is written inside the fence; the child then parks
        // before commit so the observer can cancel the actual capture caller deterministically.
        val effectsWritten = CompletableDeferred<Unit>()
        val captureJob = launch {
            store().withFencedTransaction { transaction ->
                database.workoutDao().updateWorkoutName(WORKOUT, "Edited")
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                ) { MutationPayload.WorkoutStart(WORKOUT, "Edited", STARTED_AT) }
                effectsWritten.complete(Unit)
                awaitCancellation()
            }
        }
        effectsWritten.await()
        captureJob.cancel()
        captureJob.join()
        assertTrue(captureJob.isCancelled)
        assertEquals("W", database.workoutDao().getWorkout(WORKOUT)?.name)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT))
        assertNull(database.syncCanonicalDao().getAccountCache(ACCOUNT))
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)
    }

    @Test
    fun rollback_extraLedgerWriteFailureRestoresMainWatermark() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(name = "Main")
        seedWorkout(id = WORKOUT_B, name = "Extra")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_capture_extra_ledger BEFORE INSERT ON sync_canonical_roots " +
                "WHEN NEW.aggregate_id = '$WORKOUT_B' " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            try {
                store().withFencedTransaction { transaction ->
                    database.workoutDao().updateWorkoutName(WORKOUT, "Edited")
                    transaction.captureEdit(
                        AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                        changed = true,
                        extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, WORKOUT_B)),
                    ) {
                        MutationPayload.WorkoutStart(WORKOUT, "Edited", STARTED_AT)
                    }
                }
                fail("Expected extra ledger trigger failure")
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
            }
            assertEquals("Main", database.workoutDao().getWorkout(WORKOUT)?.name)
            assertEquals("Extra", database.workoutDao().getWorkout(WORKOUT_B)?.name)
            assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
            assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT_B)?.localGeneration)
            assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT))
            assertNull(database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT_B))
            assertNull(database.syncCanonicalDao().getAccountCache(ACCOUNT))
            assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_capture_extra_ledger")
        }
    }

    @Test
    fun existingCanonicalAndCache_surviveCaptureExceptWatermarkAndDirty() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        seedCanonicalRow(
            ACCOUNT, "workout", WORKOUT,
            42L, "upsert", CANONICAL_RECORD_42, 1L, 7L,
        )
        seedCacheRow(ACCOUNT, true, 7L, false, 3L)
        seedCanonicalRow(OTHER_ACCOUNT, "workout", WORKOUT, null, "unobserved", null, 9L, 4L)
        seedCacheRow(OTHER_ACCOUNT, true, 4L, false, 6L)
        val syncStore = store()

        val outcome = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }!!
        assertEquals(2L, outcome.request?.envelope?.localGeneration)

        val preserved = database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)!!
        assertEquals(42L, preserved.serverRevision)
        assertEquals("upsert", preserved.canonicalState)
        assertEquals(CANONICAL_RECORD_42, preserved.canonicalJson)
        assertEquals(2L, preserved.localGenerationWatermark)
        assertEquals(7L, preserved.cacheEpoch)

        val cache = database.syncCanonicalDao().getAccountCache(ACCOUNT)!!
        assertEquals(true, cache.baselineComplete)
        assertEquals(7L, cache.cacheEpoch)
        assertEquals(true, cache.projectionDirty)
        assertEquals(3L, cache.materializedEpoch)

        // A separate cursor replacement cannot reset publication bookkeeping.
        database.syncDao().upsertSyncState(SyncStateEntity(ACCOUNT, cursor = "cursor-2"))
        val afterCursor = database.syncCanonicalDao().getAccountCache(ACCOUNT)!!
        assertEquals(true, afterCursor.baselineComplete)
        assertEquals(7L, afterCursor.cacheEpoch)
        assertEquals(true, afterCursor.projectionDirty)
        assertEquals(3L, afterCursor.materializedEpoch)

        val unrelated = database.syncCanonicalDao().getRoot(OTHER_ACCOUNT, "workout", WORKOUT)!!
        assertEquals(9L, unrelated.localGenerationWatermark)
        assertEquals(4L, unrelated.cacheEpoch)
        val unrelatedCache = database.syncCanonicalDao().getAccountCache(OTHER_ACCOUNT)!!
        assertEquals(true, unrelatedCache.baselineComplete)
        assertEquals(4L, unrelatedCache.cacheEpoch)
        assertEquals(false, unrelatedCache.projectionDirty)
        assertEquals(6L, unrelatedCache.materializedEpoch)
    }

    @Test
    fun delete_captureReservesLedgerWithDeleteKind() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val syncStore = store()

        val outcome = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.AGGREGATE_DELETE,
                changed = true,
            ) { MutationPayload.AggregateDelete }
        }!!
        assertEquals(false, outcome.coalesced)
        assertEquals(MutationKind.AGGREGATE_DELETE, outcome.request?.envelope?.kind)
        assertEquals(1L, outcome.request?.envelope?.localGeneration)
        assertEquals(1L, database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark)
        assertEquals("aggregate.delete", database.syncDao().getOutboxForAccount(ACCOUNT).single().mutationType)
    }

    @Test
    fun reservation_advancementPreservesCanonicalData() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        seedCanonicalRow(
            ACCOUNT, "workout", WORKOUT,
            11L, "upsert", CANONICAL_RECORD_11, 0L, 2L,
        )
        val syncStore = store()

        syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
        }

        val preserved = database.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)!!
        assertEquals(11L, preserved.serverRevision)
        assertEquals("upsert", preserved.canonicalState)
        assertEquals(CANONICAL_RECORD_11, preserved.canonicalJson)
        assertEquals(1L, preserved.localGenerationWatermark)
        assertEquals(2L, preserved.cacheEpoch)
    }

    @Test
    fun fileBacked_closeReopenKeepsWatermarkAndOutboxIdentity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "capture-canonical-file-backed.db"
        context.deleteDatabase(name)
        var beforePayload = ""
        var beforeState = ""
        var beforeWatermark: Long? = null
        try {
            val fileDatabase = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                fileDatabase.syncDao().upsertDeviceState(device())
                fileDatabase.workoutDao().insertWorkout(
                    WorkoutEntity(
                        id = WORKOUT,
                        name = "W",
                        startedAtMillis = 1_000L,
                        accountId = ACCOUNT,
                        serverRevision = 5L,
                        localGeneration = 0L,
                    ),
                )
                val ids = mutableListOf(MUTATION_1, MUTATION_2)
                val fileStore = SyncStore(
                    fileDatabase,
                    RepositorySession(ACCOUNT, GENERATION),
                ) { ids.removeFirst() }
                val outcome = fileStore.withFencedTransaction { transaction ->
                    transaction.captureEdit(
                        AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                        changed = true,
                    ) { MutationPayload.WorkoutStart(WORKOUT, "W", STARTED_AT) }
                }!!
                assertEquals(MUTATION_1, outcome.request?.envelope?.mutationId)
                beforePayload = fileDatabase.syncDao().getOutboxMutation(MUTATION_1, ACCOUNT)!!.payloadJson
                beforeState = fileDatabase.syncDao().getOutboxMutation(MUTATION_1, ACCOUNT)!!.state
                beforeWatermark = fileDatabase.syncCanonicalDao()
                    .getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark
            } finally {
                fileDatabase.close()
            }

            val reopened = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                assertEquals(
                    beforeWatermark,
                    reopened.syncCanonicalDao().getRoot(ACCOUNT, "workout", WORKOUT)?.localGenerationWatermark,
                )
                assertEquals(1L, beforeWatermark)
                assertEquals(
                    true,
                    reopened.syncCanonicalDao().getAccountCache(ACCOUNT)?.projectionDirty,
                )
                val rows = reopened.syncDao().getOutboxForAccount(ACCOUNT)
                assertEquals(1, rows.size)
                assertEquals(MUTATION_1, rows.single().mutationId)
                assertEquals(1L, rows.single().localGeneration)
                assertEquals(beforePayload, rows.single().payloadJson)
                assertEquals(beforeState, rows.single().state)
                assertEquals("pending", rows.single().state)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
