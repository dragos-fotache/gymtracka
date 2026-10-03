package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.duplicateOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pullAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.rejectedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.deleteRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.mutationId
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.startRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.uid
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.upsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutEntry
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.workoutSet
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused real-backend exact-byte retry coverage: identical retries replay the
 * original accepted batch or rejection without new revisions or log batches,
 * changed bytes under the same ID reject `mutation_id_reused` with evidence
 * intact, a retried winner replays its original batch while the newer winner
 * stands, and dedup namespaces are account-scoped.
 */
class RealBackendRetryTest {
    companion object {
        const val STARTED_AT = 1_780_000_000_000L
    }

    @Test
    fun identicalAcceptedRetryReplaysWithoutNewWrites(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val workout = uid()
            val first = pushOne(api, startRequest(mutationId(0), workout, "W", STARTED_AT))
            val original = acceptedOutcome(first)

            val replay = duplicateOutcome(pushOne(api, startRequest(mutationId(0), workout, "W", STARTED_AT)))

            // Same envelope bytes: identical bytes must reproduce the request,
            // so the replayed batch ID and cursor equal the originals.
            assertEquals(MutationStatus.DUPLICATE, replay.status)
            assertEquals(original.batchId, replay.batch!!.batchId)
            assertEquals(original.cursor, replay.batch!!.cursor)
            assertEquals(original.changes, replay.batch!!.changes)
            // No extra revision or log batch: exactly one pull batch exists and
            // the stored revision is still 1.
            val (batches, _) = pullAll(api, null)
            assertEquals(1, batches.size)
            val (records, _) = snapshotAll(api)
            assertEquals(1L, (records.single().record as CanonicalRecord.Workout).record.revision)
        }
    }

    @Test
    fun identicalRejectedRetryReplaysOriginalCode(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val missing = uid()
            val request = deleteRequest(mutationId(0), AggregateType.WORKOUT, missing)
            rejectedOutcome(pushOne(api, request), "not_found")
            val (_, tailBefore) = pullAll(api, null)

            val replay = duplicateOutcome(pushOne(api, request))

            assertEquals("not_found", replay.errorCode)
            assertEquals(null, replay.batch)
            val (newBatches, _) = pullAll(api, tailBefore)
            assertTrue(newBatches.isEmpty())
        }
    }

    @Test
    fun changedBytesUnderSameIdRejectWithEvidenceIntact(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val workout = uid()
            val original = acceptedOutcome(pushOne(api, startRequest(mutationId(0), workout, "Original", STARTED_AT)))

            // Same mutation ID, different name bytes: not a retry.
            rejectedOutcome(pushOne(api, startRequest(mutationId(0), workout, "Changed", STARTED_AT)), "mutation_id_reused")

            // Original evidence is intact: replaying the exact original bytes
            // still returns the first batch, and the stored name is unchanged.
            val replay = duplicateOutcome(pushOne(api, startRequest(mutationId(0), workout, "Original", STARTED_AT)))
            assertEquals(original.batchId, replay.batch!!.batchId)
            val (records, _) = snapshotAll(api)
            assertEquals("Original", (records.single().record as CanonicalRecord.Workout).record.name)
        }
    }

    @Test
    fun retriedWinnerReplaysOriginalWhileNewerWinnerStands(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workout = uid()
            val entry = uid()
            val set = uid()
            pushOne(api, startRequest(mutationId(0), workout, "W", STARTED_AT))
            val firstEdit = upsertRequest(
                mutationId(1),
                workoutInput(
                    workout,
                    "First",
                    STARTED_AT,
                    listOf(workoutEntry(entry, seeds.bundledExerciseA, 0, sets = listOf(workoutSet(set, 0, 1, 1.0, false, null)))),
                ),
            )
            val firstBatch = acceptedOutcome(pushOne(api, firstEdit))
            acceptedOutcome(
                pushOne(
                    api,
                    upsertRequest(
                        mutationId(2),
                        workoutInput(
                            workout,
                            "Second",
                            STARTED_AT,
                            listOf(workoutEntry(entry, seeds.bundledExerciseA, 0, sets = listOf(workoutSet(set, 0, 2, 2.0, false, null)))),
                        ),
                    ),
                ),
            )

            val replay = duplicateOutcome(pushOne(api, firstEdit))

            assertEquals(firstBatch.batchId, replay.batch!!.batchId)
            val (records, _) = snapshotAll(api)
            assertEquals("Second", (records.single().record as CanonicalRecord.Workout).record.name)
        }
    }

    @Test
    fun whitespaceVariantBytesRejectWithEvidenceIntact(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val workout = uid()
            val originalRequest = startRequest(mutationId(0), workout, "W", STARTED_AT)
            val original = acceptedOutcome(pushOne(api, originalRequest))

            // Same decoded envelope, different wire bytes (interior whitespace
            // after the opening brace): this is not a retry, so the mutation
            // ID cannot be reused.
            val spaced = DurableRequestModel(
                originalRequest.payloadVersion,
                originalRequest.requestJson.replaceFirst("{", "{ "),
                originalRequest.affectedRoots,
                originalRequest.envelope,
            )
            rejectedOutcome(runBlocking { api.push(listOf(spaced)) }, "mutation_id_reused")

            // The exact original bytes still replay the original batch.
            val replay = duplicateOutcome(pushOne(api, originalRequest))
            assertEquals(original.batchId, replay.batch!!.batchId)
            assertEquals(original.changes, replay.batch!!.changes)
        }
    }

    @Test
    fun dedupIdentityIsAccountScoped(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val accountA = fx.newAccount()
            val accountB = fx.newAccount()
            val apiA = fx.apiFor(accountA)
            val apiB = fx.apiFor(accountB)
            // Same mutation UUID in two accounts: independent executions, each
            // owning its root. B cannot observe A's outcome.
            val mutation = mutationId(0)
            acceptedOutcome(pushOne(apiA, startRequest(mutation, uid(), "A", STARTED_AT)))
            acceptedOutcome(pushOne(apiB, startRequest(mutation, uid(), "B", STARTED_AT)))

            val (recordsA, _) = snapshotAll(apiA)
            val (recordsB, _) = snapshotAll(apiB)
            assertEquals("A", (recordsA.single().record as CanonicalRecord.Workout).record.name)
            assertEquals("B", (recordsB.single().record as CanonicalRecord.Workout).record.name)
        }
    }
}
