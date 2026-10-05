package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStorageException
import com.dragosfotache.gymtracka.data.sync.store.ExtraAffectedRoot
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06a independent core-claim acceptance: explicit-head selection, durable
 * freeze-before-transport, exact byte preservation, blocked/deferred/empty
 * classification and entry fences. Real Room, no mocked DAOs, no sleeps.
 *
 * All expectations are literals authored from docs/archive/remaining-work/R06a-task.md
 * and the coordinator's contract facts. Exactness is proven by byte equality
 * of stored rows before and after claiming, never by regenerating bytes with
 * production codecs.
 */
class PushClaimTest : GymDatabaseTest() {

    private suspend fun seedFenced(vararg caches: Pair<String, Long>) {
        database.claimSeedDevice()
        // Idempotent across loop iterations that wipe and reseed fixtures.
        database.claimExec("DELETE FROM sync_account_cache")
        caches.forEach { (account, epoch) -> database.claimSeedCache(account, epoch) }
    }

    private suspend fun captureHead(
        mutationId: String = CLAIM_MUTATION_1,
        rootId: String = CLAIM_WORKOUT_A,
        name: String = "Claimed Workout",
    ): SyncOutboxEntity {
        database.claimSeedWorkout(rootId, CLAIM_ACCOUNT_A)
        val store = database.claimCaptureStore(
            claimSession(),
            mutableListOf(mutationId),
        )
        return database.claimCaptureStart(store, CLAIM_ACCOUNT_A, rootId, name)
    }

    private suspend fun captureUpsertHead(name: String): SyncOutboxEntity {
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        val store = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        return database.claimCaptureUpsert(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, name)
    }

    private suspend fun claimOnce(
        epoch: Long = 0L,
        nowMillis: Long = CLAIM_NOW_MILLIS,
    ): PushHeadResult {
        var result: PushHeadResult = PushHeadResult.Empty
        database.claimPushStore().withRun(epoch) { run ->
            result = run.claimHead(nowMillis)
        }
        return result
    }

    // Acceptance 1: ordering, isolation, empty.

    @Test
    fun emptyAccount_returnsEmptyWithoutCreatingStorage() = runTest {
        database.claimSeedDevice()
        val before = database.claimSnapshot()

        val result = claimOnce()

        assertEquals(PushHeadResult.Empty, result)
        assertEquals(before, database.claimSnapshot())
        assertTrue(database.claimOutboxFor(CLAIM_ACCOUNT_A).isEmpty())
        assertNull(database.syncCanonicalDao().getAccountCache(CLAIM_ACCOUNT_A))
    }

    @Test
    fun foreignRows_neverCompeteWithSelectedHead() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_B)
        database.claimSeedWorkout(CLAIM_WORKOUT_OLD, CLAIM_ACCOUNT_B)
        val storeA = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        val storeB = database.claimCaptureStore(
            claimSession(CLAIM_ACCOUNT_B),
            mutableListOf(CLAIM_MUTATION_2, CLAIM_MUTATION_3),
        )
        // Foreign rows exist on both sides of the selected head sequence.
        database.claimSeedDevice(CLAIM_ACCOUNT_B, CLAIM_GENERATION)
        val foreignFirst = database.claimCaptureStart(storeB, CLAIM_ACCOUNT_B, CLAIM_WORKOUT_B, "Foreign 1")
        database.claimSeedDevice()
        val head = database.claimCaptureStart(storeA, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
        database.claimSeedDevice(CLAIM_ACCOUNT_B, CLAIM_GENERATION)
        // Distinct foreign root so the second row appends instead of coalescing.
        val foreignLast = database.claimCaptureStart(storeB, CLAIM_ACCOUNT_B, CLAIM_WORKOUT_OLD, "Foreign 2")
        database.claimSeedDevice()
        val foreignBefore = listOf(
            database.syncDao().getOutboxMutation(foreignFirst.mutationId, CLAIM_ACCOUNT_B),
            database.syncDao().getOutboxMutation(foreignLast.mutationId, CLAIM_ACCOUNT_B),
        )

        val result = claimOnce()
        val claimed = result as PushHeadResult.Claimed

        assertEquals(head.mutationId, claimed.claim.row.mutationId)
        assertEquals(CLAIM_ACCOUNT_A, claimed.claim.accountId)
        assertEquals(
            foreignBefore,
            listOf(
                database.syncDao().getOutboxMutation(foreignFirst.mutationId, CLAIM_ACCOUNT_B),
                database.syncDao().getOutboxMutation(foreignLast.mutationId, CLAIM_ACCOUNT_B),
            ),
        )
        foreignBefore.forEach {
            assertEquals("pending", it?.state)
            assertEquals(0, it?.attemptCount)
        }
    }

    @Test
    fun sequenceHead_selectedAcrossStatesAndTypes() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A)
        val store = database.claimCaptureStore(
            claimSession(),
            mutableListOf(CLAIM_MUTATION_1, CLAIM_MUTATION_2, CLAIM_MUTATION_3),
        )
        val head = database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
        // Later rows carry other states/types but must never outrank the head.
        val frozenOther = database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Second")
        database.syncDao().freezePendingMutation(frozenOther.mutationId, CLAIM_ACCOUNT_A)
        database.claimExec(
            "UPDATE sync_outbox SET attempt_count = 1, aggregate_type = ?, mutation_type = ? " +
                "WHERE mutation_id = ? AND account_id = ?",
            arrayOf<Any?>("routine", "routine.upsert", frozenOther.mutationId, CLAIM_ACCOUNT_A),
        )
        val rejectedOther = database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Third")
        database.claimExec(
            "UPDATE sync_outbox SET state = 'rejected', error_code = 'gone' WHERE mutation_id = ? AND account_id = ?",
            arrayOf<Any?>(rejectedOther.mutationId, CLAIM_ACCOUNT_A),
        )
        val frozenBefore = database.syncDao().getOutboxMutation(frozenOther.mutationId, CLAIM_ACCOUNT_A)
        val rejectedBefore = database.syncDao().getOutboxMutation(rejectedOther.mutationId, CLAIM_ACCOUNT_A)

        val first = claimOnce() as PushHeadResult.Claimed
        assertEquals(head.mutationId, first.claim.row.mutationId)
        assertEquals("frozen", first.claim.row.state)
        assertEquals(1, first.claim.row.attemptCount)

        // The head replays; no successor is admitted while it remains head.
        val second = claimOnce() as PushHeadResult.Claimed
        assertEquals(head.mutationId, second.claim.row.mutationId)
        assertEquals(2, second.claim.row.attemptCount)
        assertEquals(first.claim.request.requestJson, second.claim.request.requestJson)
        assertEquals(frozenBefore, database.syncDao().getOutboxMutation(frozenOther.mutationId, CLAIM_ACCOUNT_A))
        assertEquals(rejectedBefore, database.syncDao().getOutboxMutation(rejectedOther.mutationId, CLAIM_ACCOUNT_A))
    }

    // Acceptance 2: freeze before transport, exact bytes, multi-root effects.

    @Test
    fun pendingFreeze_commitsBeforeTransportWithExactBytes() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        val trickyName = "O'Brien \"quoted\" back\\slash Café ☕\tTab"
        val stored = captureUpsertHead(trickyName)
        assertEquals("pending", stored.state)
        assertEquals(0, stored.attemptCount)
        val transport = ClaimTestTransport(database)

        var frozenInsideBlock: SyncOutboxEntity? = null
        database.claimPushStore().withRun(0L) { run ->
            val result = run.claimHead(CLAIM_NOW_MILLIS)
            val claimed = result as PushHeadResult.Claimed
            assertEquals(stored.mutationId, claimed.claim.row.mutationId)
            assertEquals(CLAIM_ACCOUNT_A, claimed.claim.accountId)
            assertEquals(CLAIM_GENERATION, claimed.claim.sessionGeneration)
            assertEquals(0L, claimed.claim.expectedCacheEpoch)
            // The freeze is already committed before caller transport entry.
            frozenInsideBlock = database.syncDao().getOutboxMutation(stored.mutationId, CLAIM_ACCOUNT_A)
            assertEquals("frozen", frozenInsideBlock?.state)
            assertEquals(1, frozenInsideBlock?.attemptCount)
            assertEquals(stored.sequence, frozenInsideBlock?.sequence)
            assertEquals(stored.payloadJson, frozenInsideBlock?.payloadJson)
            assertEquals(stored.localGeneration, frozenInsideBlock?.localGeneration)
            // Exact durable request: identical wrapper bytes and inner request text.
            assertEquals(1, claimed.claim.request.payloadVersion)
            assertEquals(
                JSONObject(stored.payloadJson).getString("requestJson"),
                claimed.claim.request.requestJson,
            )
            assertEquals(stored.mutationId, claimed.claim.request.envelope.mutationId)
            assertEquals(MutationKind.WORKOUT_UPSERT, claimed.claim.request.envelope.kind)
            assertEquals(AggregateType.WORKOUT, claimed.claim.request.envelope.aggregateType)
            assertEquals(CLAIM_WORKOUT_A, claimed.claim.request.envelope.aggregateId)
            assertEquals(1L, claimed.claim.request.envelope.localGeneration)
            assertEquals(
                listOf(Triple(AggregateType.WORKOUT, CLAIM_WORKOUT_A, 1L)),
                claimed.claim.request.affectedRoots.map {
                    Triple(it.aggregateType, it.aggregateId, it.localGeneration)
                },
            )
            run.checkClaim(claimed.claim)
            transport.push(listOf(claimed.claim.request))
            run.checkClaim(claimed.claim)
        }

        assertEquals(1, transport.calls.size)
        val after = database.syncDao().getOutboxMutation(stored.mutationId, CLAIM_ACCOUNT_A)
        assertEquals(frozenInsideBlock, after)
        assertEquals(stored.payloadJson, after?.payloadJson)
        // The tricky name survives byte-for-byte inside the frozen request text.
        assertTrue(after?.payloadJson?.contains("O'Brien") == true)
    }

    @Test
    fun multiRootStart_preservesAllEffectsAndChildPlans() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        database.claimSeedWorkout(CLAIM_WORKOUT_OLD, CLAIM_ACCOUNT_A)
        val store = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        val stored = database.claimCaptureStart(
            store,
            CLAIM_ACCOUNT_A,
            CLAIM_WORKOUT_A,
            "New Active",
            extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, CLAIM_WORKOUT_OLD)),
        )

        val result = claimOnce() as PushHeadResult.Claimed

        assertEquals(
            setOf(
                Triple(AggregateType.WORKOUT, CLAIM_WORKOUT_A, 1L),
                Triple(AggregateType.WORKOUT, CLAIM_WORKOUT_OLD, 1L),
            ),
            result.claim.request.affectedRoots.map {
                Triple(it.aggregateType, it.aggregateId, it.localGeneration)
            }.toSet(),
        )
        assertEquals(2, result.claim.request.affectedRoots.size)
        assertEquals(CLAIM_WORKOUT_A, result.claim.request.envelope.aggregateId)
        assertEquals(MutationKind.WORKOUT_START, result.claim.request.envelope.kind)
        assertEquals(
            JSONObject(stored.payloadJson).getString("requestJson"),
            result.claim.request.requestJson,
        )
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(CLAIM_WORKOUT_A)?.localGeneration)
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(CLAIM_WORKOUT_OLD)?.localGeneration)
    }

    // Acceptance 2b: hand-authored noncanonical fixtures with child plans.

    private companion object {
        const val PERF_MUTATION = "a1111111-1111-1111-1111-111111111111"
        const val PERF_NEW = "a2222222-2222-2222-2222-222222222222"
        const val PERF_SOURCE = "a3333333-3333-3333-3333-333333333333"
        const val PERF_EX_A = "a4444444-4444-4444-4444-444444444444"
        const val PERF_EX_B = "a5555555-5555-5555-5555-555555555555"
        const val PERF_SRC_EX_A = "a6666666-6666-6666-6666-666666666666"
        const val PERF_SRC_SET_A = "a7777777-7777-7777-7777-777777777777"
        const val PERF_SET_A = "a8888888-8888-8888-8888-888888888888"
        const val PERF_SRC_EX_B = "a9999999-9999-9999-9999-999999999999"
        const val ADD_MUTATION = "b1111111-1111-1111-1111-111111111111"
        const val ADD_TARGET = "b2222222-2222-2222-2222-222222222222"
        const val ADD_EX_A = "b3333333-3333-3333-3333-333333333333"
        const val ADD_EX_B = "b4444444-4444-4444-4444-444444444444"
        const val ADD_SET_A = "b5555555-5555-5555-5555-555555555555"
        const val ADD_SET_B = "b6666666-6666-6666-6666-666666666666"
        const val ADD_SET_C = "b7777777-7777-7777-7777-777777777777"
        const val ADD_CATALOGUE = "b8888888-8888-8888-8888-888888888888"
        const val NUM_MUTATION = "c1111111-1111-1111-1111-111111111111"
        const val NUM_WORKOUT = "c2222222-2222-2222-2222-222222222222"
        const val NUM_EXERCISE = "c3333333-3333-3333-3333-333333333333"
        const val NUM_SET = "c4444444-4444-4444-4444-444444444444"
        const val NUM_CATALOGUE = "c5555555-5555-5555-5555-555555555555"
    }

    private fun numericUpsertInner(): String =
        "{ \"payload\" : { \"id\" : \"$NUM_WORKOUT\" , \"name\" : \"Numeric\" , " +
            "\"status\" : \"active\" , \"startedAt\" : \"2026-09-24T14:00:00Z\" , \"exercises\" : [ " +
            "{ \"id\" : \"$NUM_EXERCISE\" , \"exerciseId\" : \"$NUM_CATALOGUE\" , \"position\" : 0 , " +
            "\"notes\" : \"\" , \"restDurationSeconds\" : 60 , \"sets\" : [ " +
            "{ \"id\" : \"$NUM_SET\" , \"position\" : 0 , \"repetitions\" : 8 , \"weightKg\" : 1e1 , " +
            "\"isCompleted\" : true , \"restSeconds\" : null } ] } ] } , " +
            "\"knownServerRevision\" : null , \"localGeneration\" : 1 , " +
            "\"aggregateId\" : \"$NUM_WORKOUT\" , \"aggregateType\" : \"workout\" , " +
            "\"type\" : \"workout.upsert\" , \"mutationId\" : \"$NUM_MUTATION\" }"

    private fun performAgainInner(): String =
        "{ \"payload\" : { \"childIds\" : [ " +
            "{ \"sets\" : [ { \"id\" : \"$PERF_SET_A\" , \"sourceSetId\" : \"$PERF_SRC_SET_A\" } ] , " +
            "\"id\" : \"$PERF_EX_A\" , \"sourceExerciseId\" : \"$PERF_SRC_EX_A\" } , " +
            "{ \"sets\" : [ ] , \"id\" : \"$PERF_EX_B\" , \"sourceExerciseId\" : \"$PERF_SRC_EX_B\" } ] , " +
            "\"startedAt\" : \"2026-09-24T14:00:00Z\" , \"id\" : \"$PERF_NEW\" , " +
            "\"sourceWorkoutId\" : \"a3333333-3333-3333-3333-33333333333\\u0033\" } , " +
            "\"knownServerRevision\" : null , \"localGeneration\"   :   1 , " +
            "\"aggregateId\" : \"$PERF_NEW\" , \"aggregateType\" : \"workout\" , " +
            "\"type\" : \"workout.performAgain\" , \"mutationId\" : \"$PERF_MUTATION\" }"

    private fun addExercisesInner(): String =
        "{ \"payload\" : { \"additions\" : [ " +
            "{ \"setIds\" : [ \"b5555555-5555-5555-5555-55555555555\\u0035\" , \"$ADD_SET_B\" ] , " +
            "\"exerciseId\" : \"$ADD_CATALOGUE\" , \"id\" : \"$ADD_EX_A\" } , " +
            "{ \"setIds\" : [ \"$ADD_SET_C\" ] , \"exerciseId\" : \"$ADD_CATALOGUE\" , \"id\" : \"$ADD_EX_B\" } ] } , " +
            "\"knownServerRevision\" : null , \"localGeneration\" : 1 , " +
            "\"aggregateId\" : \"$ADD_TARGET\" , \"aggregateType\" : \"workout\" , " +
            "\"type\" : \"workout.addExercises\" , \"mutationId\" : \"$ADD_MUTATION\" }"

    private suspend fun seedHandAuthored(
        mutationId: String,
        aggregateId: String,
        mutationType: String,
        inner: String,
    ): String {
        database.claimSeedDevice()
        database.claimSeedWorkout(aggregateId, CLAIM_ACCOUNT_A, "Hand Authored", generation = 1L)
        val wrapper = claimWrapRequest(inner, claimRootEntry(aggregateId, 1L))
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = mutationId,
                accountId = CLAIM_ACCOUNT_A,
                aggregateType = "workout",
                aggregateId = aggregateId,
                mutationType = mutationType,
                localGeneration = 1L,
                payloadVersion = 1,
                payloadJson = wrapper,
            ),
        )
        return wrapper
    }

    @Test
    fun handAuthoredPerformAgain_preservesNoncanonicalBytes() = runTest {
        val inner = performAgainInner()
        // The fixture itself carries the noncanonical proof: scrambled order,
        // odd spacing, numeric spacing and a unicode escape for a hex digit.
        assertTrue(inner.contains("\"localGeneration\"   :   1"))
        assertTrue(inner.contains("\\u0033"))
        val wrapper = seedHandAuthored(PERF_MUTATION, PERF_NEW, "workout.performAgain", inner)
        assertTrue(wrapper.contains("\"payloadVersion\" : 1"))

        val result = claimOnce() as PushHeadResult.Claimed

        assertEquals(wrapper, result.claim.row.payloadJson)
        assertEquals(inner, result.claim.request.requestJson)
        assertEquals("frozen", result.claim.row.state)
        assertEquals(1, result.claim.row.attemptCount)
        assertEquals(PERF_MUTATION, result.claim.request.envelope.mutationId)
        assertEquals(PERF_NEW, result.claim.request.envelope.aggregateId)
        assertEquals(1L, result.claim.request.envelope.localGeneration)
        assertEquals(
            listOf(Triple(AggregateType.WORKOUT, PERF_NEW, 1L)),
            result.claim.request.affectedRoots.map {
                Triple(it.aggregateType, it.aggregateId, it.localGeneration)
            },
        )
        // Copied child plans survive byte-for-byte with escapes decoded:
        // the escaped source UUID is absent raw but decodes to the literal.
        assertTrue(inner.contains(PERF_EX_A))
        assertTrue(inner.contains(PERF_SET_A))
        assertTrue(inner.contains(PERF_SRC_SET_A))
        assertTrue(inner.contains("\\u0033"))
        val plan = decodeMutationEnvelope(inner).payload as MutationPayload.WorkoutPerformAgain
        assertEquals(PERF_SOURCE, plan.sourceWorkoutId)
        assertEquals(PERF_NEW, plan.id)
        assertEquals(listOf(PERF_EX_A, PERF_EX_B), plan.childIds.map { it.id })
        assertEquals(listOf(PERF_SRC_EX_A, PERF_SRC_EX_B), plan.childIds.map { it.sourceExerciseId })
        assertEquals(listOf(PERF_SET_A), plan.childIds[0].sets.map { it.id })
        assertEquals(listOf(PERF_SRC_SET_A), plan.childIds[0].sets.map { it.sourceSetId })
        assertTrue(plan.childIds[1].sets.isEmpty())
    }

    @Test
    fun handAuthoredUpsert_preservesNumericSpelling() = runTest {
        val inner = numericUpsertInner()
        // Exponent spelling without fraction or dot.
        assertTrue(inner.contains("\"weightKg\" : 1e1"))
        val wrapper = seedHandAuthored(NUM_MUTATION, NUM_WORKOUT, "workout.upsert", inner)

        val result = claimOnce() as PushHeadResult.Claimed

        assertEquals(wrapper, result.claim.row.payloadJson)
        assertEquals(inner, result.claim.request.requestJson)
        assertTrue(result.claim.request.requestJson.contains("\"weightKg\" : 1e1"))
        // The retained spelling decodes to the expected numeric value.
        val set = (decodeMutationEnvelope(inner).payload as MutationPayload.WorkoutUpsert)
            .input.exercises.single().sets.single()
        assertEquals(NUM_SET, set.id)
        assertEquals(10.0, set.weightKg)
        assertEquals(NUM_MUTATION, result.claim.request.envelope.mutationId)
    }

    @Test
    fun handAuthoredAddExercises_preservesNoncanonicalBytes() = runTest {
        val inner = addExercisesInner()
        assertTrue(inner.contains("\\u0035"))
        val wrapper = seedHandAuthored(ADD_MUTATION, ADD_TARGET, "workout.addExercises", inner)

        val result = claimOnce() as PushHeadResult.Claimed

        assertEquals(wrapper, result.claim.row.payloadJson)
        assertEquals(inner, result.claim.request.requestJson)
        assertEquals(ADD_MUTATION, result.claim.request.envelope.mutationId)
        assertEquals(ADD_TARGET, result.claim.request.envelope.aggregateId)
        assertEquals(
            listOf(Triple(AggregateType.WORKOUT, ADD_TARGET, 1L)),
            result.claim.request.affectedRoots.map {
                Triple(it.aggregateType, it.aggregateId, it.localGeneration)
            },
        )
        // Added set plans survive byte-for-byte with escapes decoded.
        assertTrue(inner.contains(ADD_EX_A))
        assertTrue(inner.contains("\\u0035"))
        assertTrue(inner.contains(ADD_SET_B))
        assertTrue(inner.contains(ADD_SET_C))
        assertTrue(inner.contains(ADD_CATALOGUE))
        val additions = (decodeMutationEnvelope(inner).payload as MutationPayload.WorkoutAddExercises).additions
        assertEquals(listOf(ADD_EX_A, ADD_EX_B), additions.map { it.id })
        assertEquals(ADD_CATALOGUE, additions[0].exerciseId)
        assertEquals(ADD_CATALOGUE, additions[1].exerciseId)
        assertEquals(listOf(ADD_SET_A, ADD_SET_B), additions[0].setIds)
        assertEquals(listOf(ADD_SET_C), additions[1].setIds)
    }

    // Acceptance 4: blocked classification without successor/repair/leak.

    private suspend fun seedBlockedHead(
        mutationId: String,
        secondMutationId: String,
        headSetup: suspend (SyncOutboxEntity) -> Unit,
    ): Pair<SyncOutboxEntity, String> {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A)
        val store = database.claimCaptureStore(claimSession(), mutableListOf(mutationId, secondMutationId))
        val head = database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
        // Distinct successor root so the later row appends instead of coalescing.
        database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")
        headSetup(head)
        val before = database.claimSnapshot()
        // Read the head by position: tamper may have replaced its mutation ID.
        return database.claimOutboxFor(CLAIM_ACCOUNT_A).first() to before
    }

    private suspend fun assertBlockedUnchanged(
        result: PushHeadResult,
        reason: PushBlockReason,
        errorCode: String?,
        before: String,
    ) {
        val blocked = result as PushHeadResult.Blocked
        assertEquals(reason, blocked.reason)
        assertEquals(errorCode, blocked.errorCode)
        assertEquals(before, database.claimSnapshot())
        // No successor admitted: the later row is still never-sent pending.
        val rows = database.claimOutboxFor(CLAIM_ACCOUNT_A)
        assertEquals(2, rows.size)
        assertEquals("pending", rows[1].state)
        assertEquals(0, rows[1].attemptCount)
    }

    @Test
    fun rejectedHead_reportsOriginalCodeWithoutSuccessor() = runTest {
        listOf("gone", "mystery-9").forEach { code ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
            val (_, before) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
                database.claimExec(
                    "UPDATE sync_outbox SET state = 'rejected', error_code = ? WHERE sequence = ?",
                    arrayOf<Any?>(code, head.sequence),
                )
            }
            assertBlockedUnchanged(claimOnce(), PushBlockReason.REJECTED, code, before)
        }
    }

    @Test
    fun retainedErrorHead_reportsWithoutMessageLeak() = runTest {
        // Frozen head with a retained 413 context.
        val (_, frozenBefore) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
            database.syncDao().freezePendingMutation(head.mutationId, CLAIM_ACCOUNT_A)
            database.claimExec(
                "UPDATE sync_outbox SET error_code = ?, error_message = ? WHERE sequence = ?",
                arrayOf<Any?>("413", "secret-response-bytes", head.sequence),
            )
        }
        assertBlockedUnchanged(claimOnce(), PushBlockReason.RETAINED_ERROR, "413", frozenBefore)

        // Pending head carrying a retained error is equally blocked.
        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
        database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
        val (_, pendingBefore) = seedBlockedHead(CLAIM_MUTATION_3, CLAIM_MUTATION_4) { head ->
            database.claimExec(
                "UPDATE sync_outbox SET error_code = ?, error_message = ? WHERE sequence = ?",
                arrayOf<Any?>("too_large", "secret-response-bytes", head.sequence),
            )
        }
        assertBlockedUnchanged(claimOnce(), PushBlockReason.RETAINED_ERROR, "too_large", pendingBefore)
    }

    @Test
    fun unsupportedVersions_reportWithoutChange() = runTest {
        val (_, columnBefore) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
            database.claimExec(
                "UPDATE sync_outbox SET payload_version = 2 WHERE sequence = ?",
                arrayOf<Any?>(head.sequence),
            )
        }
        assertBlockedUnchanged(claimOnce(), PushBlockReason.UNSUPPORTED_VERSION, null, columnBefore)

        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
        database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
        val (_, wrapperBefore) = seedBlockedHead(CLAIM_MUTATION_3, CLAIM_MUTATION_4) { head ->
            database.claimExec(
                "UPDATE sync_outbox SET payload_json = ? WHERE sequence = ?",
                arrayOf<Any?>(claimWrapperWithVersion(head.payloadJson, 99), head.sequence),
            )
        }
        assertBlockedUnchanged(claimOnce(), PushBlockReason.UNSUPPORTED_VERSION, null, wrapperBefore)
    }

    @Test
    fun corruptWrappers_reportWithoutRepair() = runTest {
        val cases = listOf(
            "malformed" to { wrapper: String -> "{broken" },
            "duplicateMember" to { wrapper: String -> claimWrapperWithDuplicateMember(wrapper) },
            "forbiddenField" to { wrapper: String -> claimWrapperWithForbiddenField(wrapper) },
        )
        cases.forEach { (label, tamper) ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
            val (_, before) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
                database.claimExec(
                    "UPDATE sync_outbox SET payload_json = ? WHERE sequence = ?",
                    arrayOf<Any?>(tamper(head.payloadJson), head.sequence),
                )
            }
            try {
                assertBlockedUnchanged(claimOnce(), PushBlockReason.CORRUPT, null, before)
            } catch (error: AssertionError) {
                throw AssertionError("$label: ${error.message}", error)
            }
        }

        // Row/envelope identity mismatches against an otherwise valid wrapper.
        val mismatches = listOf(
            "mutationId" to "mutation_id = 'aaaaaaaa-1111-1111-1111-111111111111'",
            "aggregateType" to "aggregate_type = 'routine'",
            "aggregateId" to "aggregate_id = '$CLAIM_WORKOUT_B'",
            "mutationType" to "mutation_type = 'workout.finish'",
            "localGeneration" to "local_generation = 99",
        )
        mismatches.forEach { (label, assignment) ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
            val (_, before) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
                database.claimExec(
                    "UPDATE sync_outbox SET $assignment WHERE sequence = ${head.sequence}",
                )
            }
            try {
                assertBlockedUnchanged(claimOnce(), PushBlockReason.CORRUPT, null, before)
            } catch (error: AssertionError) {
                throw AssertionError("$label: ${error.message}", error)
            }
        }
    }

    @Test
    fun corruptEffects_reportWithoutRepair() = runTest {
        val cases = listOf(
            "emptyRoots",
            "duplicateRoots",
            "rootGenerationMismatch",
            "effectUnknownMember",
            "forbiddenInnerField",
            "duplicateInnerMember",
        )
        cases.forEach { label ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
            val (_, before) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
                val tampered = when (label) {
                    "emptyRoots" ->
                        JSONObject(head.payloadJson).apply {
                            put("affectedRoots", org.json.JSONArray())
                        }.toString()
                    "duplicateRoots" -> {
                        val entry = JSONObject(head.payloadJson)
                            .getJSONArray("affectedRoots").getJSONObject(0).toString()
                        JSONObject(head.payloadJson).apply {
                            put("affectedRoots", org.json.JSONArray("[$entry,$entry]"))
                        }.toString()
                    }
                    "rootGenerationMismatch" -> {
                        val entry = JSONObject(head.payloadJson)
                            .getJSONArray("affectedRoots").getJSONObject(0)
                        entry.put("localGeneration", 99)
                        JSONObject(head.payloadJson).apply {
                            put("affectedRoots", org.json.JSONArray().put(entry))
                        }.toString()
                    }
                    "effectUnknownMember" -> {
                        val entry = JSONObject(head.payloadJson)
                            .getJSONArray("affectedRoots").getJSONObject(0)
                        entry.put("r06aEffectUnknown", 7)
                        JSONObject(head.payloadJson).apply {
                            put("affectedRoots", org.json.JSONArray().put(entry))
                        }.toString()
                    }
                    "forbiddenInnerField" -> {
                        val inner = JSONObject(head.payloadJson).getString("requestJson")
                        val withExtra = inner.replaceFirst(
                            "{",
                            "{\"r06aInnerUnknown\":1,",
                        )
                        JSONObject(head.payloadJson).apply {
                            put("requestJson", withExtra)
                        }.toString()
                    }
                    else -> {
                        val inner = JSONObject(head.payloadJson).getString("requestJson")
                        val duplicated = inner.replaceFirst(
                            "\"mutationId\"",
                            "\"mutationId\":\"${head.mutationId}\",\"mutationId\"",
                        )
                        JSONObject(head.payloadJson).apply {
                            put("requestJson", duplicated)
                        }.toString()
                    }
                }
                database.claimExec(
                    "UPDATE sync_outbox SET payload_json = ? WHERE sequence = ?",
                    arrayOf<Any?>(tampered, head.sequence),
                )
            }
            try {
                assertBlockedUnchanged(claimOnce(), PushBlockReason.CORRUPT, null, before)
            } catch (error: AssertionError) {
                throw AssertionError("$label: ${error.message}", error)
            }
        }
    }

    @Test
    fun unsupportedVersion_precedesContentChecks() = runTest {
        // Version dispatch precedes wrapper/content parsing: a future version
        // carrying an extra member or malformed inner bytes still reports
        // UNSUPPORTED_VERSION, never CORRUPT, with storage untouched.
        val cases = listOf("extraMember", "malformedInner")
        cases.forEach { label ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
            val (_, before) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
                val tampered = when (label) {
                    "extraMember" ->
                        JSONObject(head.payloadJson).apply {
                            put("payloadVersion", 2)
                            put("r06aUnknownField", 7)
                        }.toString()
                    else ->
                        JSONObject(head.payloadJson).apply {
                            put("payloadVersion", 2)
                            put("requestJson", "{broken")
                        }.toString()
                }
                database.claimExec(
                    "UPDATE sync_outbox SET payload_json = ? WHERE sequence = ?",
                    arrayOf<Any?>(tampered, head.sequence),
                )
            }
            try {
                assertBlockedUnchanged(claimOnce(), PushBlockReason.UNSUPPORTED_VERSION, null, before)
            } catch (error: AssertionError) {
                throw AssertionError("$label: ${error.message}", error)
            }
        }
    }

    @Test
    fun invalidBookkeeping_reportsCorrupt() = runTest {
        val cases = listOf(
            "unknownState" to "state = 'weird'",
            "zeroSequence" to "sequence = 0",
            "negativeAttempts" to "attempt_count = -1",
            "negativeRetry" to "retry_after_millis = -5",
            "pendingWithAttempts" to "attempt_count = 1",
        )
        cases.forEach { (label, assignment) ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
            val (_, before) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
                database.claimExec("UPDATE sync_outbox SET $assignment WHERE sequence = ${head.sequence}")
            }
            try {
                assertBlockedUnchanged(claimOnce(), PushBlockReason.CORRUPT, null, before)
            } catch (error: AssertionError) {
                throw AssertionError("$label: ${error.message}", error)
            }
        }
    }

    @Test
    fun exhaustedAttempts_reportsWithoutOverflowOrChange() = runTest {
        // A pending row with nonzero attempts is corrupt, so the exhausted
        // head must already be frozen to reach the admission-count check.
        val (_, before) = seedBlockedHead(CLAIM_MUTATION_1, CLAIM_MUTATION_2) { head ->
            database.syncDao().freezePendingMutation(head.mutationId, CLAIM_ACCOUNT_A)
            database.claimExec(
                "UPDATE sync_outbox SET attempt_count = ? WHERE sequence = ?",
                arrayOf<Any?>(Int.MAX_VALUE, head.sequence),
            )
        }
        assertBlockedUnchanged(claimOnce(), PushBlockReason.ATTEMPTS_EXHAUSTED, null, before)
        assertEquals(
            Int.MAX_VALUE,
            database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)?.attemptCount,
        )
    }

    // Acceptance 5: deferred scheduling.

    @Test
    fun deferredHead_blocksLaterDueRowWithoutChange() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A)
        val store = database.claimCaptureStore(
            claimSession(),
            mutableListOf(CLAIM_MUTATION_1, CLAIM_MUTATION_2),
        )
        val head = database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
        database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")
        val retryAt = CLAIM_NOW_MILLIS + 60_000L
        database.claimExec(
            "UPDATE sync_outbox SET retry_after_millis = ? WHERE sequence = ?",
            arrayOf<Any?>(retryAt, head.sequence),
        )
        val before = database.claimSnapshot()

        val result = claimOnce() as PushHeadResult.Deferred

        assertEquals(retryAt, result.retryAfterMillis)
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun retryBoundaryEquality_admitsDueHead() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        val stored = captureHead()
        database.claimExec(
            "UPDATE sync_outbox SET retry_after_millis = ? WHERE sequence = ?",
            arrayOf<Any?>(CLAIM_NOW_MILLIS, stored.sequence),
        )

        val result = claimOnce() as PushHeadResult.Claimed

        assertEquals(stored.mutationId, result.claim.row.mutationId)
        assertEquals(CLAIM_NOW_MILLIS, result.claim.row.retryAfterMillis)
        assertEquals(1, result.claim.row.attemptCount)
    }

    @Test
    fun negativeCallerTime_rejectsBeforeMutation() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        captureHead()
        val before = database.claimSnapshot()

        try {
            claimOnce(nowMillis = -1L)
            fail("Negative claim time must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(before, database.claimSnapshot())
    }

    // Fences at entry.

    @Test
    fun withRunEpochMismatch_rejectsBeforeBlock() = runTest {
        database.claimSeedDevice()
        database.claimSeedCache(CLAIM_ACCOUNT_A, 3L)
        var entered = false
        val before = database.claimSnapshot()

        try {
            database.claimPushStore().withRun(2L) {
                entered = true
            }
            fail("Stale cache epoch must be rejected")
        } catch (_: StaleCanonicalEpochException) {
        }
        assertEquals(false, entered)
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun malformedCache_failsClosedAtEntry() = runTest {
        database.claimSeedDevice()
        database.claimSeedCache(CLAIM_ACCOUNT_A, -1L)
        try {
            database.claimPushStore().withRun(0L) { }
            fail("Negative cache epoch must fail closed")
        } catch (_: CanonicalStorageException) {
        }

        database.claimExec(
            "UPDATE sync_account_cache SET cache_epoch = 1, materialized_epoch = 2 WHERE account_id = ?",
            arrayOf<Any?>(CLAIM_ACCOUNT_A),
        )
        try {
            database.claimPushStore().withRun(1L) { }
            fail("Materialized epoch above cache epoch must fail closed")
        } catch (_: CanonicalStorageException) {
        }
    }

    @Test
    fun sessionMismatch_rejectsAtEntry() = runTest {
        database.claimSeedDevice()
        val before = database.claimSnapshot()

        listOf(
            Pair("stale-generation", claimSession(generation = CLAIM_GENERATION - 1)),
            Pair("wrong-account", claimSession(account = CLAIM_ACCOUNT_B)),
        ).forEach { (label, session) ->
            var entered = false
            try {
                database.claimPushStore(session).withRun(0L) {
                    entered = true
                }
                fail("Stale session must be rejected for $label")
            } catch (_: StaleSessionException) {
            }
            assertEquals(false, entered)
        }

        // Absent device state is signed-out: a signed-in session is stale.
        database.claimExec("DELETE FROM sync_device_state")
        try {
            database.claimPushStore().withRun(0L) { }
            fail("Missing device state must be stale for a signed-in session")
        } catch (_: StaleSessionException) {
        }
        database.claimSeedDevice()
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun lateSessionSwitch_beforeClaimFailsWithoutChange() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        val stored = captureHead()
        val before = database.claimSnapshot()

        database.claimPushStore().withRun(0L) {
            database.syncDao().upsertDeviceState(
                com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity(
                    activeAccountId = CLAIM_ACCOUNT_B,
                    sessionGeneration = 99L,
                ),
            )
            try {
                it.claimHead(CLAIM_NOW_MILLIS)
                fail("Late session switch must fail")
            } catch (_: StaleSessionException) {
            }
        }

        val head = database.syncDao().getOutboxMutation(stored.mutationId, CLAIM_ACCOUNT_A)
        assertEquals("pending", head?.state)
        assertEquals(0, head?.attemptCount)
        database.claimSeedDevice()
        assertEquals(before, database.claimSnapshot())
    }

    @Test
    fun latePublication_beforeCheckFailsWithoutChangingWork() = runTest {
        database.claimSeedDevice()
        database.claimSeedCache(CLAIM_ACCOUNT_A, 0L)
        val stored = captureHead()

        database.claimPushStore().withRun(0L) { run ->
            val claimed = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            // Publication advances the cache epoch before late validation.
            database.claimUpdateCache(CLAIM_ACCOUNT_A, 1L)
            try {
                run.checkClaim(claimed)
                fail("Late publication must invalidate the callback")
            } catch (_: StaleCanonicalEpochException) {
            }
        }

        val head = database.syncDao().getOutboxMutation(stored.mutationId, CLAIM_ACCOUNT_A)
        assertEquals("frozen", head?.state)
        assertEquals(1, head?.attemptCount)
        assertEquals(stored.payloadJson, head?.payloadJson)
        assertNotNull(head)
    }

    @Test
    fun sameAccountEditWithoutEpochAdvance_keepsClaimValid() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
        val captureStore = database.claimCaptureStore(
            claimSession(),
            mutableListOf(CLAIM_MUTATION_1, CLAIM_MUTATION_2),
        )
        val head = database.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)

        database.claimPushStore().withRun(0L) { run ->
            val claimed = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
            // Same-account autosave appends later work and advances watermarks
            // without advancing the cache epoch: not a stale claim by itself.
            database.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Edited")
            run.checkClaim(claimed)
            assertEquals(head.payloadJson, claimed.row.payloadJson)
        }

        val rows = database.claimOutboxFor(CLAIM_ACCOUNT_A)
        assertEquals(2, rows.size)
        assertEquals("frozen", rows[0].state)
        assertEquals("pending", rows[1].state)
        assertEquals(2L, database.syncCaptureDao().getWorkoutMetadata(CLAIM_WORKOUT_A)?.localGeneration)
    }

    @Test
    fun lateFenceChanges_beforeCheckClaimFailWithoutWrites() = runTest {
        val transport = ClaimTestTransport(database)
        val cases = listOf(
            "accountSwitch" to Pair(
                "UPDATE sync_device_state SET active_account_id = '$CLAIM_ACCOUNT_B', session_generation = 99",
                StaleSessionException::class.java,
            ),
            "generationBump" to Pair(
                "UPDATE sync_device_state SET session_generation = 8",
                StaleSessionException::class.java,
            ),
            "signOut" to Pair(
                "UPDATE sync_device_state SET active_account_id = NULL",
                StaleSessionException::class.java,
            ),
            "absentDevice" to Pair(
                "DELETE FROM sync_device_state",
                StaleSessionException::class.java,
            ),
            "negativeCache" to Pair(
                "UPDATE sync_account_cache SET cache_epoch = -1 WHERE account_id = '$CLAIM_ACCOUNT_A'",
                CanonicalStorageException::class.java,
            ),
            "materializedAboveCache" to Pair(
                "UPDATE sync_account_cache SET materialized_epoch = 5 WHERE account_id = '$CLAIM_ACCOUNT_A'",
                CanonicalStorageException::class.java,
            ),
        )
        cases.forEach { (label, changeAndType) ->
            val (change, expected) = changeAndType
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_account_cache")
            database.claimSeedDevice()
            database.claimSeedCache(CLAIM_ACCOUNT_A, 0L)
            val stored = captureHead()

            database.claimPushStore().withRun(0L) { run ->
                val claimed = (run.claimHead(CLAIM_NOW_MILLIS) as PushHeadResult.Claimed).claim
                run.checkClaim(claimed)
                transport.push(listOf(claimed.request))
                // Late fence change after controlled transport, before check.
                database.claimExec(change)
                try {
                    run.checkClaim(claimed)
                    fail("$label must fail late validation")
                } catch (error: Exception) {
                    assertEquals("$label fence type", expected, error::class.java)
                }
            }

            // No unintended storage writes: the frozen head is byte-identical.
            val head = database.syncDao().getOutboxMutation(stored.mutationId, CLAIM_ACCOUNT_A)
            assertEquals("$label state", "frozen", head?.state)
            assertEquals("$label attempts", 1, head?.attemptCount)
            assertEquals("$label bytes", stored.payloadJson, head?.payloadJson)
            assertEquals(
                "$label successors",
                1,
                database.claimOutboxFor(CLAIM_ACCOUNT_A).size,
            )
        }
    }

    @Test
    fun triggerMutatingFences_rollsBackTriggerAndFreeze() = runTest {
        seedFenced(CLAIM_ACCOUNT_A to 0L)
        captureHead()
        val before = database.claimSnapshot()

        // AFTER UPDATE trigger advances the device generation behind the claim:
        // the post-write fence aborts and rolls back both mutations.
        database.claimExec(
            "CREATE TRIGGER push_claim_fence_trip AFTER UPDATE ON sync_outbox " +
                "BEGIN UPDATE sync_device_state SET session_generation = 99; END",
        )
        try {
            try {
                claimOnce()
                fail("Fence-tripping trigger must abort the claim")
            } catch (_: StaleSessionException) {
            }
        } finally {
            database.claimExec("DROP TRIGGER IF EXISTS push_claim_fence_trip")
        }
        assertEquals(before, database.claimSnapshot())

        // AFTER UPDATE trigger advances the cache epoch instead.
        database.claimExec(
            "CREATE TRIGGER push_claim_epoch_trip AFTER UPDATE ON sync_outbox " +
                "BEGIN UPDATE sync_account_cache SET cache_epoch = 9 WHERE account_id = '$CLAIM_ACCOUNT_A'; END",
        )
        try {
            try {
                claimOnce()
                fail("Epoch-tripping trigger must abort the claim")
            } catch (_: StaleCanonicalEpochException) {
            }
        } finally {
            database.claimExec("DROP TRIGGER IF EXISTS push_claim_epoch_trip")
        }
        assertEquals(before, database.claimSnapshot())

        // Recovery after both trigger faults are removed.
        val recovered = claimOnce() as PushHeadResult.Claimed
        assertEquals(1, recovered.claim.row.attemptCount)
        assertEquals("frozen", recovered.claim.row.state)
    }
}
