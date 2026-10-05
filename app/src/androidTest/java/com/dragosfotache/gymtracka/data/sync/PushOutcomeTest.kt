package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationOutcomeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.PushFailureResult
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushOutcomeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06b independent outcome acceptance: complete accepted/duplicate snapshots
 * stay read-only, rejections/retained failures persist exact codes with fixed
 * context and block successors, malformed evidence stays frozen, and
 * auth/reauth remain read-only without bootstrapping. Real Room, no mocked
 * DAOs, no sleeps.
 *
 * All expectations are literals authored from docs/archive/remaining-work/R06b-task.md;
 * no production encoder output is used as an oracle.
 */
class PushOutcomeTest : GymDatabaseTest() {

    private suspend fun freshHead() {
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM workouts")
        database.outcomeSeedFenced()
        database.outcomeCaptureHead()
    }

    @Test
    fun acceptedMultiRootSnapshot_isReadOnlyAndPreservesBatch() = runTest {
        database.outcomeSeedFenced()
        database.outcomeCaptureHead()
        val sentBatch = outcomeBatch(extraDeletes = listOf(OUTCOME_UNKNOWN_ROOT))

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val frozen = database.syncDao().getOutboxMutation(claim.row.mutationId, claim.accountId)
            // Full-table oracle taken before handling: the read-only branch must
            // leave every table byte-identical.
            val snapBefore = database.claimSnapshot()
            val accepted = run.handleResult(claim, outcomeAcceptedResult(claim.row.mutationId, sentBatch))
                as PushOutcomeResult.Accepted
            // Status and the complete multi-root batch survive, without an error.
            assertEquals(MutationStatus.ACCEPTED, accepted.outcome.status)
            assertEquals(claim.row.mutationId, accepted.outcome.mutationId)
            assertNull(accepted.outcome.errorCode)
            assertEquals(sentBatch, accepted.outcome.batch)
            assertEquals(
                listOf(ChangeOperation.DELETE, ChangeOperation.UPSERT),
                accepted.outcome.batch!!.changes.map { it.operation },
            )
            assertEquals(
                listOf(OUTCOME_UNKNOWN_ROOT, CLAIM_WORKOUT_A),
                accepted.outcome.batch!!.changes.map { it.aggregateId },
            )
            // Nothing changed, including retry fields; no canonical write, no deletion.
            assertEquals(frozen, database.syncDao().getOutboxMutation(claim.row.mutationId, claim.accountId))
            assertEquals(snapBefore, database.claimSnapshot())
            // Read-only handling keeps the claim valid and is idempotent.
            run.checkClaim(claim)
            val replayed = run.handleResult(claim, outcomeAcceptedResult(claim.row.mutationId, sentBatch))
                as PushOutcomeResult.Accepted
            assertEquals(accepted.outcome, replayed.outcome)
            assertEquals(frozen, database.syncDao().getOutboxMutation(claim.row.mutationId, claim.accountId))
        }

        val head = database.outcomeRow()
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
        assertEquals(0L, database.syncCanonicalDao().getAccountCache(CLAIM_ACCOUNT_A)?.cacheEpoch)
    }

    @Test
    fun duplicateAcceptedOldRevision_isReadOnly() = runTest {
        database.outcomeSeedFenced()
        database.outcomeCaptureHead()
        // An old positive revision duplicate is still the original accepted batch:
        // obsolete content is never a reason to reject it here.
        val oldBatch = outcomeBatch(targetRevision = 1L)

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val frozen = database.syncDao().getOutboxMutation(claim.row.mutationId, claim.accountId)
            val duplicate = run.handleResult(
                claim,
                outcomeResult(claim.row.mutationId, MutationStatus.DUPLICATE, null, oldBatch),
            ) as PushOutcomeResult.Accepted
            assertEquals(MutationStatus.DUPLICATE, duplicate.outcome.status)
            assertNull(duplicate.outcome.errorCode)
            assertEquals(oldBatch, duplicate.outcome.batch)
            assertEquals(frozen, database.syncDao().getOutboxMutation(claim.row.mutationId, claim.accountId))
        }

        val head = database.outcomeRow()
        assertEquals("frozen", head.state)
        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun acceptedDeepOwnership_survivesGatedInputAndGetterMutation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entryId = "e1111111-1111-1111-1111-111111111111"
        val setId = "e2222222-2222-2222-2222-222222222222"
        val refId = "e3333333-3333-3333-3333-333333333333"
        val intruderId = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        // Caller-owned nested inputs: mutable at every level.
        val sentSets = mutableListOf(
            WorkoutSetModel(
                id = setId, position = 0, repetitions = 8, weightKg = 80.0,
                isCompleted = true, restSeconds = 90,
            ),
        )
        val sentExercises = mutableListOf(
            WorkoutExerciseModel(
                id = entryId, exerciseId = refId, position = 0, notes = "Keep",
                restDurationSeconds = 60, sets = sentSets, exerciseName = "Press",
            ),
        )
        val sentChanges = mutableListOf(
            SyncChangeModel(
                AggregateType.WORKOUT, CLAIM_WORKOUT_A, ChangeOperation.UPSERT, 7L,
                CanonicalRecord.Workout(
                    WorkoutRecordModel(
                        id = CLAIM_WORKOUT_A, name = "W", status = "active",
                        startedAtMillis = CLAIM_STARTED_MILLIS, finishedAtMillis = null,
                        revision = 7L, exercises = sentExercises,
                    ),
                ),
            ),
        )
        val sentBatch = SyncBatchModel(
            batchId = OUTCOME_BATCH_1,
            cursor = OUTCOME_CURSOR_1,
            changes = sentChanges,
        )
        // Independent literal expectation: immutable, never aliased with the input.
        val expectedBatch = SyncBatchModel(
            batchId = OUTCOME_BATCH_1,
            cursor = OUTCOME_CURSOR_1,
            changes = listOf(
                SyncChangeModel(
                    AggregateType.WORKOUT, CLAIM_WORKOUT_A, ChangeOperation.UPSERT, 7L,
                    CanonicalRecord.Workout(
                        WorkoutRecordModel(
                            id = CLAIM_WORKOUT_A, name = "W", status = "active",
                            startedAtMillis = CLAIM_STARTED_MILLIS, finishedAtMillis = null,
                            revision = 7L,
                            exercises = listOf(
                                WorkoutExerciseModel(
                                    id = entryId, exerciseId = refId, position = 0, notes = "Keep",
                                    restDurationSeconds = 60,
                                    sets = listOf(
                                        WorkoutSetModel(
                                            id = setId, position = 0, repetitions = 8, weightKg = 80.0,
                                            isCompleted = true, restSeconds = 90,
                                        ),
                                    ),
                                    exerciseName = "Press",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val queryExecutor = Executors.newSingleThreadExecutor()
        val backingTx = Executors.newSingleThreadExecutor()
        val armed = AtomicBoolean(false)
        val captured = CompletableDeferred<Runnable>()
        // Deterministic admission barrier: the transaction executor decorator runs
        // normally until armed, then captures the next transaction Runnable without
        // running it. A separate query executor keeps reads off the barrier, so late
        // invalidation work cannot steal the gate.
        val hookDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(
                Executor { command ->
                    if (armed.getAndSet(false)) {
                        captured.complete(command)
                    } else {
                        backingTx.execute(command)
                    }
                },
            )
            .build()
        try {
            hookDb.outcomeSeedFenced()
            hookDb.outcomeCaptureHead()
            hookDb.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val sent = PushResultModel(
                    listOf(
                        MutationOutcomeModel(
                            mutationId = claim.row.mutationId, status = MutationStatus.ACCEPTED,
                            errorCode = null, batch = sentBatch,
                        ),
                    ),
                )
                armed.set(true)
                val accepted = coroutineScope {
                    // Inherit the run context: foreign contexts reject handling.
                    val handling = async(start = CoroutineStart.UNDISPATCHED) {
                        run.handleResult(claim, sent)
                    }
                    // The captured transaction proves handling ran synchronously
                    // through deep-ownership to admission and is now suspended.
                    val runnable = captured.await()
                    assertFalse(handling.isCompleted)
                    // Mutate caller-owned nested lists only now: deterministically
                    // after the first suspension, never racing synchronous capture.
                    sentSets.clear()
                    sentSets.add(
                        WorkoutSetModel(
                            id = intruderId, position = 0, repetitions = 1, weightKg = 1.0,
                            isCompleted = false, restSeconds = null,
                        ),
                    )
                    sentExercises.clear()
                    sentChanges.clear()
                    // Admit the transaction on the owned backing executor and await.
                    backingTx.execute(runnable)
                    handling.await()
                } as PushOutcomeResult.Accepted
                armed.set(false)
                // The retained snapshot matches the full literal batch: inputs were
                // deep-owned before the first suspension.
                assertEquals(MutationStatus.ACCEPTED, accepted.outcome.status)
                assertEquals(expectedBatch, accepted.outcome.batch)
                // Mutating exposed getter nested lists after return cannot change
                // a later getter either: each access is defensively deep-owned.
                try {
                    val exercises = (accepted.outcome.batch!!.changes.single().record as CanonicalRecord.Workout)
                        .record.exercises
                    (exercises.single().sets as? MutableList<WorkoutSetModel>)?.clear()
                    (exercises as? MutableList<WorkoutExerciseModel>)?.clear()
                } catch (_: UnsupportedOperationException) {
                }
                assertEquals(expectedBatch, accepted.outcome.batch)
            }
            assertEquals("frozen", hookDb.outcomeRow().state)
        } finally {
            hookDb.close()
            queryExecutor.shutdown()
            backingTx.shutdown()
        }
    }

    @Test
    fun acceptedDiagnostics_omitUuidsAndBatches() = runTest {
        database.outcomeSeedFenced()
        database.outcomeCaptureHead()

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val accepted = run.handleResult(
                claim,
                outcomeAcceptedResult(claim.row.mutationId, outcomeBatch()),
            ) as PushOutcomeResult.Accepted
            val text = accepted.toString()
            assertFalse(text.contains(claim.row.mutationId))
            assertFalse(text.contains(OUTCOME_BATCH_1))
            assertFalse(text.contains(CLAIM_WORKOUT_A))
            assertTrue(text.isNotBlank())
        }
    }

    @Test
    fun targetFailures_retainFrozenMalformedWithoutAcknowledgment() = runTest {
        val cases = listOf(
            "empty-results" to { _: String ->
                PushResultModel(emptyList())
            },
            "extra-outcome" to { id: String ->
                PushResultModel(
                    listOf(
                        outcomeAcceptedResult(id).results.single(),
                        outcomeResult(CLAIM_MUTATION_2, MutationStatus.ACCEPTED, null, outcomeBatch()).results.single(),
                    ),
                )
            },
            "mismatched-id" to { id: String -> outcomeAcceptedResult(CLAIM_MUTATION_2) },
            "accepted-null-batch" to { id: String ->
                outcomeResult(id, MutationStatus.ACCEPTED, null, null)
            },
            "accepted-with-error" to { id: String ->
                outcomeResult(id, MutationStatus.ACCEPTED, "boom", outcomeBatch())
            },
            "rejected-null-code" to { id: String ->
                outcomeResult(id, MutationStatus.REJECTED, null, null)
            },
            "rejected-with-batch" to { id: String ->
                outcomeResult(id, MutationStatus.REJECTED, "gone", outcomeBatch())
            },
            "duplicate-code-and-batch" to { id: String ->
                outcomeResult(id, MutationStatus.DUPLICATE, "gone", outcomeBatch())
            },
            "missing-target" to { id: String ->
                outcomeResult(
                    id, MutationStatus.ACCEPTED, null,
                    outcomeBatch(targetId = OUTCOME_UNKNOWN_ROOT),
                )
            },
            "wrong-operation-target" to { id: String ->
                outcomeResult(
                    id, MutationStatus.ACCEPTED, null,
                    SyncBatchModel(
                        batchId = OUTCOME_BATCH_1,
                        cursor = OUTCOME_CURSOR_1,
                        changes = listOf(
                            SyncChangeModel(
                                AggregateType.WORKOUT,
                                CLAIM_WORKOUT_A,
                                ChangeOperation.DELETE,
                                7L,
                                null,
                            ),
                        ),
                    ),
                )
            },
        )
        cases.forEach { (label, build) ->
            freshHead()
            val stored = database.outcomeRow()
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val result = run.handleResult(claim, build(claim.row.mutationId))
                assertEquals("$label shape", PushOutcomeResult.Malformed, result)
            }
            try {
                val head = database.outcomeRow()
                assertEquals("$label state", "frozen", head.state)
                assertEquals("$label code", OUTCOME_MALFORMED_CODE, head.errorCode)
                assertEquals("$label message", OUTCOME_MALFORMED_MESSAGE, head.errorMessage)
                assertNull("$label retry", head.retryAfterMillis)
                // Uncertain request identity is preserved byte-for-byte, never synthesized.
                assertEquals("$label bytes", stored.payloadJson, head.payloadJson)
                assertEquals("$label id", stored.mutationId, head.mutationId)
                assertEquals("$label count", 1, head.attemptCount)
                assertEquals("$label rows", 1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
            } catch (error: AssertionError) {
                throw AssertionError("$label: ${error.message}", error)
            }
        }
    }

    @Test
    fun rejectedAndDuplicateRejected_persistExactCodeAndBlockSuccessor() = runTest {
        listOf("gone", "mystery-9").forEach { code ->
            listOf(MutationStatus.REJECTED, MutationStatus.DUPLICATE).forEach { status ->
                database.claimExec("DELETE FROM sync_outbox")
                database.claimExec("DELETE FROM workouts")
                database.outcomeSeedFenced()
                database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A)
                database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A)
                val capture = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1, CLAIM_MUTATION_2))
                database.claimCaptureStart(capture, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A)
                database.claimCaptureStart(capture, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")

                database.claimPushStore().withRun(0L) { run ->
                    val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                    val rejected = run.handleResult(
                        claim,
                        outcomeResult(claim.row.mutationId, status, code, null),
                    ) as PushOutcomeResult.Rejected
                    assertEquals(code, rejected.errorCode)
                }

                val head = database.outcomeRow()
                assertEquals("$code/$status state", "rejected", head.state)
                assertEquals("$code/$status code", code, head.errorCode)
                assertEquals("$code/$status message", OUTCOME_REJECTED_MESSAGE, head.errorMessage)
                assertNull("$code/$status retry", head.retryAfterMillis)

                // The exact retained code is accessible through its field, never the message.
                assertFalse(head.errorMessage!!.contains(code))
                val successor = database.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A)!!
                assertEquals("pending", successor.state)
                assertEquals(0, successor.attemptCount)

                // No filtering around the blocked head: the successor never passes it.
                var blocked: PushHeadResult.Blocked? = null
                database.claimPushStore().withRun(0L) { run ->
                    blocked = run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Blocked
                }
                assertEquals(PushBlockReason.REJECTED, blocked!!.reason)
                assertEquals(code, blocked.errorCode)
                assertEquals("pending", database.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A)!!.state)
            }
        }
    }

    @Test
    fun permanentAndOversizedFailures_stayFrozenUncertain() = runTest {
        database.outcomeSeedFenced()
        database.outcomeCaptureHead()
        val stored = database.outcomeRow()

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val tooLarge = run.handleFailure(claim, SyncApiException.RequestTooLarge(), OUTCOME_NOW_MILLIS)
                as PushFailureResult.Retained
            assertEquals(OUTCOME_TOO_LARGE_CODE, tooLarge.errorCode)
        }
        val large = database.outcomeRow()
        assertEquals("frozen", large.state)
        assertEquals(OUTCOME_TOO_LARGE_CODE, large.errorCode)
        assertEquals(OUTCOME_TOO_LARGE_MESSAGE, large.errorMessage)
        assertNull(large.retryAfterMillis)
        assertEquals(stored.payloadJson, large.payloadJson)
        assertEquals(stored.mutationId, large.mutationId)

        database.claimExec("UPDATE sync_outbox SET error_code = NULL, error_message = NULL WHERE mutation_id = '${large.mutationId}'")
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val rejected = run.handleFailure(
                claim,
                SyncApiException.RequestRejected("future_permanent_code"),
                OUTCOME_NOW_MILLIS,
            ) as PushFailureResult.Retained
            assertEquals("future_permanent_code", rejected.errorCode)
        }
        val retained = database.outcomeRow()
        assertEquals("frozen", retained.state)
        assertEquals("future_permanent_code", retained.errorCode)
        assertEquals(OUTCOME_REQUEST_REJECTED_MESSAGE, retained.errorMessage)
        assertNull(retained.retryAfterMillis)
        assertEquals(stored.payloadJson, retained.payloadJson)
        assertFalse(retained.errorMessage!!.contains("future_permanent_code"))

        // Retained work blocks via existing selection; nothing retries automatically.
        var blocked: PushHeadResult? = null
        database.claimPushStore().withRun(0L) { run ->
            blocked = run.claimHead(OUTCOME_NOW_MILLIS)
        }
        val asBlocked = blocked as PushHeadResult.Blocked
        assertEquals(PushBlockReason.RETAINED_ERROR, asBlocked.reason)
        assertEquals("future_permanent_code", asBlocked.errorCode)
    }

    @Test
    fun authFailures_areReadOnlyAndResumable() = runTest {
        listOf(
            SyncApiException.AuthenticationRequired() to "access",
            SyncApiException.ReauthenticationRequired() to "foreground",
        ).forEach { (failure, label) ->
            freshHead()
            val stored = database.outcomeRow()
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val before = database.syncDao().getOutboxMutation(claim.row.mutationId, claim.accountId)
                val snapBefore = database.claimSnapshot()
                when (run.handleFailure(claim, failure, OUTCOME_NOW_MILLIS)) {
                    PushFailureResult.AuthenticationRequired ->
                        assertEquals("access", label)
                    PushFailureResult.ReauthenticationRequired ->
                        assertEquals("foreground", label)
                    else -> fail("$label must return its matching read-only result")
                }
                // Read-only exact check: the frozen row is byte-identical afterwards.
                assertEquals(
                    "$label row",
                    before,
                    database.syncDao().getOutboxMutation(claim.row.mutationId, claim.accountId),
                )
                assertEquals("$label snapshot", snapBefore, database.claimSnapshot())
                // Resumable: the same claim still validates for a later retry.
                run.checkClaim(claim)
            }
            assertEquals("$label bytes", stored.payloadJson, database.outcomeRow().payloadJson)
            assertEquals("$label state", "frozen", database.outcomeRow().state)
            assertNull("$label code", database.outcomeRow().errorCode)
        }
    }

    @Test
    fun deleteTargetOperation_isEnforced() = runTest {
        val deleteMutation = "d1111111-1111-1111-1111-111111111111"
        val deleteTarget = "d2222222-2222-2222-2222-222222222222"
        suspend fun seedDeleteHead() {
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            database.outcomeSeedFenced()
            database.claimSeedWorkout(deleteTarget, CLAIM_ACCOUNT_A, "Doomed", generation = 1L)
            // Hand-authored frozen wrapper: envelope fields per shared contract/spec.
            val inner = "{\"mutationId\":\"$deleteMutation\",\"type\":\"aggregate.delete\"," +
                "\"aggregateType\":\"workout\",\"aggregateId\":\"$deleteTarget\"," +
                "\"localGeneration\":1,\"knownServerRevision\":null,\"payload\":{}}"
            database.syncDao().insertOutbox(
                SyncOutboxEntity(
                    mutationId = deleteMutation,
                    accountId = CLAIM_ACCOUNT_A,
                    aggregateType = "workout",
                    aggregateId = deleteTarget,
                    mutationType = "aggregate.delete",
                    localGeneration = 1L,
                    payloadVersion = 1,
                    payloadJson = claimWrapRequest(inner, claimRootEntry(deleteTarget, 1L)),
                ),
            )
        }

        // Valid: DELETE operation for an aggregate.delete target is accepted read-only.
        seedDeleteHead()
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(deleteMutation, claim.row.mutationId)
            val accepted = run.handleResult(
                claim,
                outcomeResult(
                    deleteMutation, MutationStatus.ACCEPTED, null,
                    SyncBatchModel(
                        batchId = OUTCOME_BATCH_1,
                        cursor = OUTCOME_CURSOR_1,
                        changes = listOf(
                            SyncChangeModel(
                                AggregateType.WORKOUT, deleteTarget, ChangeOperation.DELETE, 7L, null,
                            ),
                        ),
                    ),
                ),
            ) as PushOutcomeResult.Accepted
            assertEquals(MutationStatus.ACCEPTED, accepted.outcome.status)
            assertEquals(claim.row, database.outcomeRow(deleteMutation))
        }

        // Invalid: UPSERT for an aggregate.delete target retains frozen malformed.
        seedDeleteHead()
        val stored = database.outcomeRow(deleteMutation)
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val result = run.handleResult(
                claim,
                outcomeResult(deleteMutation, MutationStatus.ACCEPTED, null, outcomeBatch(targetId = deleteTarget)),
            )
            assertEquals(PushOutcomeResult.Malformed, result)
        }
        val head = database.outcomeRow(deleteMutation)
        assertEquals("frozen", head.state)
        assertEquals(OUTCOME_MALFORMED_CODE, head.errorCode)
        assertEquals(OUTCOME_MALFORMED_MESSAGE, head.errorMessage)
        assertNull(head.retryAfterMillis)
        assertEquals(stored.payloadJson, head.payloadJson)
    }

    @Test
    fun wrongTokenFailures_cannotBootstrapAndRetainMalformed() = runTest {
        val failures: List<Pair<String, SyncApiException>> = listOf(
            "invalid-cursor" to SyncApiException.InvalidCursor(),
            "cursor-expired" to SyncApiException.CursorExpired(),
            "invalid-snapshot" to SyncApiException.InvalidSnapshotToken(),
            "snapshot-expired" to SyncApiException.SnapshotExpired(),
            "malformed-response" to SyncApiException.MalformedResponse(),
            "blank-rejected" to SyncApiException.RequestRejected(""),
        )
        failures.forEach { (label, failure) ->
            freshHead()
            val stored = database.outcomeRow()
            val cursorBefore = database.syncDao().getSyncState(CLAIM_ACCOUNT_A)
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val retained = run.handleFailure(claim, failure, OUTCOME_NOW_MILLIS)
                    as PushFailureResult.Retained
                assertEquals("$label code", OUTCOME_MALFORMED_CODE, retained.errorCode)
            }
            try {
                val head = database.outcomeRow()
                assertEquals("$label state", "frozen", head.state)
                assertEquals("$label persisted", OUTCOME_MALFORMED_CODE, head.errorCode)
                assertEquals("$label message", OUTCOME_MALFORMED_MESSAGE, head.errorMessage)
                assertNull("$label retry", head.retryAfterMillis)
                assertEquals("$label bytes", stored.payloadJson, head.payloadJson)
                // No bootstrap or stage cleanup: cursor and unrelated rows are untouched.
                assertEquals("$label cursor", cursorBefore, database.syncDao().getSyncState(CLAIM_ACCOUNT_A))
            } catch (error: AssertionError) {
                throw AssertionError("$label: ${error.message}", error)
            }
        }
    }
}
