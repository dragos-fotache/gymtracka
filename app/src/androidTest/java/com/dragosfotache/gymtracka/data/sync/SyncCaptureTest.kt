package com.dragosfotache.gymtracka.data.sync

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.store.CaptureOutcome
import com.dragosfotache.gymtracka.data.sync.store.ExtraAffectedRoot
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.SyncStore
import com.dragosfotache.gymtracka.data.sync.store.SyncTransaction
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Real-Room coverage for fenced capture primitives. No mocked DAOs. */
class SyncCaptureTest : com.dragosfotache.gymtracka.data.gym.GymDatabaseTest() {

    private companion object {
        const val ACCOUNT = "account-a"
        const val OTHER_ACCOUNT = "account-b"
        const val GENERATION = 7L
        const val WORKOUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val WORKOUT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val OLD_WORKOUT = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val CUSTOM = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val MUTATION_1 = "11111111-1111-1111-1111-111111111111"
        const val MUTATION_2 = "22222222-2222-2222-2222-222222222222"
        const val MUTATION_3 = "33333333-3333-3333-3333-333333333333"
        const val MUTATION_4 = "44444444-4444-4444-4444-444444444444"
        const val MUTATION_5 = "55555555-5555-5555-5555-555555555555"
        const val MUTATION_6 = "66666666-6666-6666-6666-666666666666"
        const val CATALOGUE = "99999999-9999-9999-9999-999999999999"
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

    private fun upsertInput(id: String): WorkoutInputModel =
        WorkoutInputModel(
            id, "W", "active", 1_790_258_400_000L,
            listOf(
                WorkoutExerciseInputModel(
                    "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", CATALOGUE, 0, "", 30,
                    listOf(WorkoutSetModel("ffffffff-ffff-ffff-ffff-ffffffffffff", 0, null, null, false, null)),
                ),
            ),
        )

    private suspend fun outboxCount(): Int =
        database.syncDao().getOutboxForAccount(ACCOUNT).size

    private fun assertRejectsSync(block: suspend () -> Any?) = runTest {
        try {
            block()
        } catch (_: SyncModelException) {
            return@runTest
        }
        fail("Expected SyncModelException")
    }

    @Test
    fun fence_rejectsStaleSessionAndChangesNothing() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(name = "Original")

        suspend fun attempt(session: RepositorySession): StaleSessionException? =
            try {
                store(session).withFencedTransaction { transaction ->
                    database.workoutDao().updateWorkoutName(WORKOUT, "Mutated")
                    transaction.captureEdit(
                        AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                        changed = true,
                    ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
                }
                null
            } catch (error: StaleSessionException) {
                error
            }

        val staleGeneration = attempt(RepositorySession(ACCOUNT, GENERATION - 1))
        assertNotNull(staleGeneration)
        assertEquals(ACCOUNT, staleGeneration?.expectedAccountId)
        assertEquals(ACCOUNT, staleGeneration?.actualAccountId)
        assertNotNull(attempt(RepositorySession(OTHER_ACCOUNT, GENERATION)))
        assertNotNull(attempt(RepositorySession.SIGNED_OUT))

        assertEquals("Original", database.workoutDao().getWorkout(WORKOUT)?.name)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(0, outboxCount())

        val outcome = store().withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = false,
            ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
        }
        assertNull(outcome)
    }

    @Test
    fun fence_signOutGenerationChangeIsStale() = runTest {
        database.syncDao().upsertDeviceState(device(account = null, generation = 1L))
        seedWorkout(account = null)

        try {
            store(RepositorySession.SIGNED_OUT).withFencedTransaction { }
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
        val outcome = store(RepositorySession(null, 1L)).withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = false,
            ) { error("must not run") }
        }
        assertNull(outcome)
    }

    @Test
    fun capture_signedOutSkipsFactoryAndOutbox() = runTest {
        var factoryCalls = 0
        seedWorkout(id = "legacy id with spaces", account = null)
        database.exerciseDao().insertExercise(
            ExerciseEntity(
                id = "legacy custom",
                name = "Legacy",
                type = "reps",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = null,
                instructions = null,
                isUserCreated = true,
            ),
        )

        val outcome = store(RepositorySession.SIGNED_OUT).withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, "legacy id with spaces", MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) {
                factoryCalls++
                error("payload factory must not run while signed out")
            }
        }
        assertNotNull(outcome)
        assertNull(outcome?.request)
        assertEquals(0, factoryCalls)
        val metadata = database.syncCaptureDao().getWorkoutMetadata("legacy id with spaces")
        assertEquals(1L, metadata?.localGeneration)
        assertEquals("local_only", metadata?.syncState)
        assertEquals(0, database.syncDao().getOutboxForAccount(ACCOUNT).size)

        val customOutcome = store(RepositorySession.SIGNED_OUT).withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.CUSTOM_EXERCISE, "legacy custom", MutationKind.CUSTOM_EXERCISE_UPSERT,
                changed = true,
            ) {
                factoryCalls++
                error("payload factory must not run while signed out")
            }
        }
        assertNull(customOutcome?.request)
        assertEquals(0, factoryCalls)
        assertEquals(
            1L,
            database.syncCaptureDao().getExerciseMetadata("legacy custom")?.localGeneration,
        )
    }

    @Test
    fun capture_accountAppendsWithIdentity() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        var factoryCalls = 0

        val outcome = store().withFencedTransaction { transaction ->
            database.workoutDao().updateWorkoutName(WORKOUT, "Edited")
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) {
                factoryCalls++
                MutationPayload.WorkoutUpsert(upsertInput(WORKOUT))
            }
        }
        assertNotNull(outcome)
        assertEquals(false, outcome?.coalesced)
        assertEquals(1, factoryCalls)

        val metadata = database.syncCaptureDao().getWorkoutMetadata(WORKOUT)
        assertEquals(1L, metadata?.localGeneration)
        assertEquals("pending", metadata?.syncState)
        assertEquals(5L, metadata?.serverRevision)

        val rows = database.syncDao().getOutboxForAccount(ACCOUNT)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(MUTATION_1, row.mutationId)
        assertEquals(ACCOUNT, row.accountId)
        assertEquals("workout", row.aggregateType)
        assertEquals(WORKOUT, row.aggregateId)
        assertEquals("workout.upsert", row.mutationType)
        assertEquals(1L, row.localGeneration)
        assertEquals(1, row.payloadVersion)
        assertEquals("pending", row.state)
        assertEquals(0, row.attemptCount)
        assertEquals(outcome?.sequence, row.sequence)

        val read = readDurableOutboxRow(row)
        assertEquals(MUTATION_1, read.request.envelope.mutationId)
        assertEquals(5L, read.request.envelope.knownServerRevision)
        assertEquals(
            listOf(Triple(AggregateType.WORKOUT, WORKOUT, 1L)),
            read.request.affectedRoots.map { Triple(it.aggregateType, it.aggregateId, it.localGeneration) },
        )
    }

    @Test
    fun capture_rejectsBundledWrongOwnerAndMissing() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(account = OTHER_ACCOUNT)
        database.exerciseDao().insertExercise(
            ExerciseEntity(
                id = CUSTOM,
                name = "Bundled",
                type = "cardio",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = null,
                instructions = null,
                isUserCreated = false,
            ),
        )

        assertRejectsSync {
            store().withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.CUSTOM_EXERCISE, CUSTOM, MutationKind.CUSTOM_EXERCISE_UPSERT,
                    changed = true,
                ) {
                    MutationPayload.CustomExerciseUpsert(
                        com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel(
                            CUSTOM, "Bundled", null, emptyList(), null, null,
                            null, null, null, null, null,
                        ),
                    )
                }
            }
        }
        assertRejectsSync {
            store().withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
            }
        }
        assertRejectsSync {
            store().withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
            }
        }
        assertEquals(0, outboxCount())
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
    }

    @Test
    fun capture_coalescesTailOnly() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        seedWorkout(id = WORKOUT_B)
        val syncStore = store()

        suspend fun edit(
            id: String = WORKOUT,
            kind: MutationKind = MutationKind.WORKOUT_UPSERT,
            payload: () -> MutationPayload = { MutationPayload.WorkoutUpsert(upsertInput(id)) },
        ): CaptureOutcome? = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(AggregateType.WORKOUT, id, kind, changed = true, newPayload = payload)
        }

        val first = edit()!!
        assertEquals(false, first.coalesced)
        val firstSequence = first.sequence!!
        val second = edit(id = WORKOUT_B)!!
        assertEquals(false, second.coalesced)
        // Tail belongs to another root: append, never search backwards.
        val third = edit()!!
        assertEquals(false, third.coalesced)
        assertTrue(third.sequence!! > second.sequence!!)
        // Consecutive same-root tail: coalesce in place with stable identity.
        val fourth = edit()!!
        assertEquals(true, fourth.coalesced)
        assertEquals(third.sequence, fourth.sequence)
        assertEquals(MUTATION_3, fourth.request?.envelope?.mutationId)
        assertEquals(3L, fourth.request?.envelope?.localGeneration)
        assertEquals(3L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)

        // Attempted tail is a barrier.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_outbox SET attempt_count = 1 WHERE sequence = ${third.sequence}",
        )
        val fifth = edit()!!
        assertEquals(false, fifth.coalesced)

        // Frozen tail is a barrier.
        database.syncDao().freezePendingMutation(fifth.request!!.envelope.mutationId, ACCOUNT)
        val sixth = edit()!!
        assertEquals(false, sixth.coalesced)

        // Rejected tail is a barrier.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_outbox SET state = 'rejected', error_code = 'gone' WHERE sequence = ${sixth.sequence}",
        )
        val seventh = edit()!!
        assertEquals(false, seventh.coalesced)

        // Semantic commands are barriers even with matching root/type scope.
        val semantic = edit(
            kind = MutationKind.WORKOUT_DELETE_SET,
            payload = { MutationPayload.WorkoutDeleteSet("ffffffff-ffff-ffff-ffff-ffffffffffff") },
        )!!
        assertEquals(false, semantic.coalesced)
        val afterSemantic = edit()!!
        assertEquals(false, afterSemantic.coalesced)

        // A newer-generation tail that is not actually previous work still appends.
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = "99999999-9999-9999-9999-999999999999",
                accountId = ACCOUNT,
                aggregateType = "workout",
                aggregateId = WORKOUT,
                mutationType = "workout.upsert",
                localGeneration = 99L,
                payloadVersion = 1,
                payloadJson = "{}",
                state = "pending",
            ),
        )
        val appended = edit()!!
        assertEquals(false, appended.coalesced)
        assertEquals(
            1,
            database.syncDao().getOutboxForAccount(ACCOUNT).count { it.localGeneration == 99L },
        )
    }

    @Test
    fun capture_unsupportedCorruptAndExtraEffectTailsAppend() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val syncStore = store()
        suspend fun edit(): CaptureOutcome? = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
        }

        // Corrupt wrapper bytes are preserved, never overwritten.
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = "99999999-9999-9999-9999-999999999999",
                accountId = ACCOUNT,
                aggregateType = "workout",
                aggregateId = WORKOUT,
                mutationType = "workout.upsert",
                localGeneration = 0L,
                payloadVersion = 1,
                payloadJson = "{broken",
                state = "pending",
            ),
        )
        val afterCorrupt = edit()!!
        assertEquals(false, afterCorrupt.coalesced)
        assertEquals(
            "{broken",
            database.syncDao().getOutboxMutation("99999999-9999-9999-9999-999999999999", ACCOUNT)?.payloadJson,
        )

        // Unsupported wrapper versions disqualify coalescing too.
        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                accountId = ACCOUNT,
                aggregateType = "workout",
                aggregateId = WORKOUT,
                mutationType = "workout.upsert",
                localGeneration = 0L,
                payloadVersion = 2,
                payloadJson = "{}",
                state = "pending",
            ),
        )
        val afterVersion = edit()!!
        assertEquals(false, afterVersion.coalesced)
        assertEquals(
            2,
            database.syncDao().getOutboxMutation("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", ACCOUNT)?.payloadVersion,
        )
    }

    @Test
    fun capture_extraEffectTailAppendsAndPreservesRow() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        seedWorkout(id = WORKOUT_B)
        val syncStore = store()

        // Same-kind/same-root valid pending autosave wrapper carrying an extra effect.
        val withExtra = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
                extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, WORKOUT_B)),
            ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
        }!!
        assertEquals(false, withExtra.coalesced)
        val tailBefore = database.syncDao().getOutboxForAccount(ACCOUNT).single()
        val tailMutationId = tailBefore.mutationId
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT_B)?.localGeneration)

        // An ordinary single-root autosave must append, never coalesce into it.
        val appended = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
        }!!
        assertEquals(false, appended.coalesced)
        assertTrue(appended.request!!.envelope.mutationId != tailMutationId)

        val rows = database.syncDao().getOutboxForAccount(ACCOUNT)
        assertEquals(2, rows.size)
        val retained = rows.first { it.mutationId == tailMutationId }
        assertEquals(tailBefore.sequence, retained.sequence)
        assertEquals(tailBefore.payloadJson, retained.payloadJson)
        assertEquals(
            withExtra.request?.affectedRoots,
            readDurableOutboxRow(retained).request.affectedRoots,
        )
        // Only the edited root advances again; the extra root is untouched.
        assertEquals(2L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT_B)?.localGeneration)
    }

    @Test
    fun capture_rollbackCoversExtraRootsWithDomainEdits() = runTest {
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
        // The injected provider reuses an existing mutation ID, so the append aborts.
        try {
            store(mutations = listOf(MUTATION_1)).withFencedTransaction { transaction ->
                database.workoutDao().updateWorkoutName(WORKOUT, "Edited A")
                database.workoutDao().updateWorkoutName(WORKOUT_B, "Edited B")
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                    extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, WORKOUT_B)),
                ) {
                    MutationPayload.WorkoutStart(WORKOUT, "A", 1_790_258_400_000L)
                }
            }
            fail("Expected constraint failure")
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals("A", database.workoutDao().getWorkout(WORKOUT)?.name)
        assertEquals("B", database.workoutDao().getWorkout(WORKOUT_B)?.name)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT_B)?.localGeneration)
        assertEquals("local_only", database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.syncState)
        assertEquals(1, outboxCount())
    }

    @Test
    fun capture_multiRootEffectsAdvanceOnce() = runTest {
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
                MutationPayload.WorkoutStart(WORKOUT, "New", 1_790_258_400_000L)
            }
        }!!
        assertEquals(false, outcome.coalesced)
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(OLD_WORKOUT)?.localGeneration)
        assertEquals(
            setOf(
                Triple(AggregateType.WORKOUT, WORKOUT, 1L),
                Triple(AggregateType.WORKOUT, OLD_WORKOUT, 1L),
            ),
            outcome.request?.affectedRoots
                ?.map { Triple(it.aggregateType, it.aggregateId, it.localGeneration) }
                ?.toSet(),
        )

        // An extra duplicating the main root is rejected.
        try {
            syncStore.withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_START,
                    changed = true,
                    extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, WORKOUT)),
                ) {
                    MutationPayload.WorkoutStart(WORKOUT, "New", 1_790_258_400_000L)
                }
            }
            fail("Expected SyncModelException")
        } catch (_: SyncModelException) {
        }
    }

    @Test
    fun capture_tombstonedRootsFollowDeleteRules() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET deleted_at_millis = 9, sync_state = 'deleted' WHERE id = '$WORKOUT'",
        )
        val syncStore = store()

        // Ordinary autosave of a tombstone stays forbidden.
        assertRejectsSync {
            syncStore.withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
            }
        }
        // Aggregate delete of the tombstoned target is allowed and advances it.
        val deleted = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.AGGREGATE_DELETE,
                changed = true,
            ) { MutationPayload.AggregateDelete }
        }!!
        assertEquals(false, deleted.coalesced)
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)

        // Old-active deletion effects may reference tombstoned extras.
        seedWorkout(id = OLD_WORKOUT, name = "Old")
        val replaced = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, OLD_WORKOUT, MutationKind.WORKOUT_START,
                changed = true,
                extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, WORKOUT)),
            ) {
                MutationPayload.WorkoutStart(OLD_WORKOUT, "Old", 1_790_258_400_000L)
            }
        }!!
        assertEquals(2L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(
            2,
            replaced.request?.affectedRoots?.size,
        )
    }

    @Test
    fun capture_generationOverflowRejected() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout(generation = Long.MAX_VALUE)
        assertRejectsSync {
            store().withFencedTransaction { transaction ->
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
            }
        }
        assertEquals(
            Long.MAX_VALUE,
            database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration,
        )
        assertEquals(0, outboxCount())
    }

    @Test
    fun capture_rollbackOnFailedInsert() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
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
        // The injected provider reuses an existing mutation ID, so the insert aborts.
        try {
            store(mutations = listOf(MUTATION_1)).withFencedTransaction { transaction ->
                database.workoutDao().updateWorkoutName(WORKOUT, "Edited")
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
            }
            fail("Expected constraint failure")
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals("W", database.workoutDao().getWorkout(WORKOUT)?.name)
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals("local_only", database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.syncState)
        assertEquals(1, outboxCount())
    }

    @Test
    fun capture_rollbackOnFailedCoalescingUpdate() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val syncStore = store()
        syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
        }
        val before = database.syncDao().getOutboxForAccount(ACCOUNT).single().payloadJson
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_outbox_update BEFORE UPDATE ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            syncStore.withFencedTransaction { transaction ->
                database.workoutDao().updateWorkoutName(WORKOUT, "Edited")
                transaction.captureEdit(
                    AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                    changed = true,
                ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
            }
            fail("Expected trigger failure")
        } catch (_: SQLiteException) {
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_outbox_update")
        }
        assertEquals("W", database.workoutDao().getWorkout(WORKOUT)?.name)
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
        assertEquals(before, database.syncDao().getOutboxForAccount(ACCOUNT).single().payloadJson)
        assertEquals(1, outboxCount())
    }

    @Test
    fun capture_contextCannotEscapeItsTransaction() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        var leaked: SyncTransaction? = null
        store().withFencedTransaction { transaction ->
            leaked = transaction
            1
        }
        try {
            leaked!!.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0L, database.syncCaptureDao().getWorkoutMetadata(WORKOUT)?.localGeneration)
    }

    @Test
    fun reader_checksIdentitiesWithoutMutating() = runTest {
        database.syncDao().upsertDeviceState(device())
        seedWorkout()
        val outcome = store().withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, WORKOUT, MutationKind.WORKOUT_UPSERT,
                changed = true,
            ) { MutationPayload.WorkoutUpsert(upsertInput(WORKOUT)) }
        }!!
        val row = database.syncDao().getOutboxForAccount(ACCOUNT).single()

        val read = readDurableOutboxRow(row)
        assertEquals(outcome.request, read.request)
        // The exact frozen inner bytes survive in the stored wrapper untouched.
        assertEquals(
            outcome.request!!.requestJson,
            JSONObject(row.payloadJson).getString("requestJson"),
        )
        // Reader changes nothing.
        assertEquals("pending", database.syncDao().getOutboxForAccount(ACCOUNT).single().state)
        assertEquals(1, outboxCount())

        fun mutatedRow(mutator: (SyncOutboxEntity) -> SyncOutboxEntity): SyncOutboxEntity =
            mutator(row)

        try {
            readDurableOutboxRow(mutatedRow { it.copy(payloadVersion = 2) })
            fail("Expected UnsupportedDurableVersionException")
        } catch (error: UnsupportedDurableVersionException) {
            assertEquals(2, error.payloadVersion)
        }
        assertRejectsSync { readDurableOutboxRow(mutatedRow { it.copy(mutationId = MUTATION_2) }) }
        assertRejectsSync { readDurableOutboxRow(mutatedRow { it.copy(aggregateType = "routine") }) }
        assertRejectsSync { readDurableOutboxRow(mutatedRow { it.copy(aggregateId = WORKOUT_B) }) }
        assertRejectsSync { readDurableOutboxRow(mutatedRow { it.copy(mutationType = "workout.finish") }) }
        assertRejectsSync { readDurableOutboxRow(mutatedRow { it.copy(localGeneration = 99L) }) }
        assertRejectsSync {
            readDurableOutboxRow(mutatedRow { it.copy(payloadJson = "{broken") })
        }
    }

    @Test
    fun replaceTail_guardsStaleSameAndNonTailAttempts() = runTest {
        database.syncDao().upsertDeviceState(device())
        val dao = database.syncCaptureDao()
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = MUTATION_1,
                accountId = ACCOUNT,
                aggregateType = "workout",
                aggregateId = WORKOUT,
                mutationType = "workout.upsert",
                localGeneration = 1L,
                payloadVersion = 1,
                payloadJson = "{}",
                state = "pending",
            ),
        )
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = MUTATION_2,
                accountId = ACCOUNT,
                aggregateType = "workout",
                aggregateId = WORKOUT,
                mutationType = "workout.upsert",
                localGeneration = 2L,
                payloadVersion = 1,
                payloadJson = "{}",
                state = "pending",
            ),
        )
        val rows = database.syncDao().getOutboxForAccount(ACCOUNT)
        val stale = rows.first { it.mutationId == MUTATION_1 }
        val tail = rows.first { it.mutationId == MUTATION_2 }

        // Current tail with a strictly newer generation replaces exactly one row.
        assertEquals(
            1,
            dao.replaceTailPayload(MUTATION_2, ACCOUNT, tail.sequence, "{\"v\":2}", 1, 3L),
        )
        // Same or older generations never replace.
        assertEquals(
            0,
            dao.replaceTailPayload(MUTATION_2, ACCOUNT, tail.sequence, "{\"v\":2}", 1, 3L),
        )
        assertEquals(
            0,
            dao.replaceTailPayload(MUTATION_2, ACCOUNT, tail.sequence, "{\"v\":0}", 1, 1L),
        )
        // A stale non-tail row is refused even with a newer generation.
        assertEquals(
            0,
            dao.replaceTailPayload(MUTATION_1, ACCOUNT, stale.sequence, "{\"v\":9}", 1, 9L),
        )
        // Frozen rows and other accounts are refused.
        database.syncDao().freezePendingMutation(MUTATION_2, ACCOUNT)
        assertEquals(
            0,
            dao.replaceTailPayload(MUTATION_2, ACCOUNT, tail.sequence, "{\"v\":4}", 1, 4L),
        )
        assertEquals(
            0,
            dao.replaceTailPayload(MUTATION_2, OTHER_ACCOUNT, tail.sequence, "{\"v\":4}", 1, 4L),
        )
    }
}
