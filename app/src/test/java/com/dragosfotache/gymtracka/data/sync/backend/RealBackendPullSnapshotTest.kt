package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.assertOpaqueBatch
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pullAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customUpsertRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.mutationId
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.startRequest
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.uid
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused real-backend pull/snapshot journeys: complete-batch pagination with
 * an indivisible active-replacement batch, frozen snapshot baselines across
 * pages despite intervening commits, and dependency-agnostic record reads.
 * Cursor-expiry-after-pruning is covered by the backend pruning suite (the
 * isolated backend disables scheduled pruning); foreign-token fencing lives in
 * the isolation suite.
 */
class RealBackendPullSnapshotTest {
    companion object {
        const val STARTED_AT = 1_780_000_000_000L
    }

    @Test
    fun pullPagesCompleteBatchesWithReplacementIntact(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val first = uid()
            val second = uid()
            val custom = uid()
            val seeds = fx.seedIds
            pushOne(api, startRequest(mutationId(0), first, "First", STARTED_AT))
            val replacement = acceptedOutcome(pushOne(api, startRequest(mutationId(1), second, "Second", STARTED_AT)))
            assertEquals(2, replacement.changes.size)
            pushOne(api, customUpsertRequest(mutationId(2), customInput(custom, "C", primaryMuscleGroupId = seeds.muscleA)))

            // One batch per page: replacement DELETE+UPSERT never splits, order
            // is commit order, and the tail page is valid.
            val page1 = api.pull(null, 1)
            assertEquals(true, page1.hasMore)
            assertEquals(1, page1.batches.size)
            assertEquals(first, page1.batches.single().changes.single().aggregateId)

            val page2 = api.pull(page1.nextCursor, 1)
            assertEquals(true, page2.hasMore)
            // Limit counts batches: one batch carrying both replacement changes.
            assertEquals(1, page2.batches.size)
            assertEquals(2, page2.batches.single().changes.size)
            assertEquals(replacement.batchId, page2.batches.single().batchId)
            assertEquals(replacement.changes, page2.batches.single().changes)

            val page3 = api.pull(page2.nextCursor, 1)
            assertEquals(false, page3.hasMore)
            assertEquals(1, page3.batches.size)
            assertEquals(custom, page3.batches.single().changes.single().aggregateId)

            val tail = api.pull(page3.nextCursor, 1)
            assertEquals(false, tail.hasMore)
            assertTrue(tail.batches.isEmpty())
            assertOpaqueBatch(page3.batches.single())
        }
    }

    @Test
    fun snapshotFreezesBaselineAcrossInterveningCommits(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workout = uid()
            val custom = uid()
            pushOne(api, startRequest(mutationId(0), workout, "W", STARTED_AT))
            pushOne(api, customUpsertRequest(mutationId(1), customInput(custom, "Old", primaryMuscleGroupId = seeds.muscleA)))

            // One record per page forces a continuation token.
            val firstPage = api.snapshot(null, 1)
            assertEquals(true, firstPage.hasMore)
            val token = firstPage.nextPageToken!!
            val baseline = firstPage.baselineCursor

            // Intervening commits: a rename and a brand-new root.
            pushOne(api, customUpsertRequest(mutationId(2), customInput(custom, "New", primaryMuscleGroupId = seeds.muscleA)))
            val late = uid()
            pushOne(api, startRequest(mutationId(3), late, "Late", STARTED_AT))

            // The frozen page repeats the original view and baseline: the old
            // label stays, and the late root is absent.
            val secondPage = api.snapshot(token, 1)
            assertEquals(false, secondPage.hasMore)
            assertEquals(baseline, secondPage.baselineCursor)
            assertEquals(firstPage.records.size + secondPage.records.size, 2)
            val frozenCustom = (firstPage.records + secondPage.records)
                .first { it.aggregateId == custom }.record as CanonicalRecord.CustomExercise
            assertEquals("Old", frozenCustom.record.name)

            // Pulling from the baseline surfaces the intervening commits.
            val (laterBatches, _) = pullAll(api, baseline, limit = 10)
            val laterIds = laterBatches.flatMap { it.changes }.map { it.aggregateId }
            assertTrue(laterIds.contains(custom))
            assertTrue(laterIds.contains(late))
        }
    }

    @Test
    fun snapshotOrdersTypesThenUuids(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workout = uid()
            val custom = uid()
            pushOne(api, startRequest(mutationId(0), workout, "W", STARTED_AT))
            pushOne(api, customUpsertRequest(mutationId(1), customInput(custom, "C", primaryMuscleGroupId = seeds.muscleA)))

            val (records, _) = snapshotAll(api, limit = 1)

            // Contract order: custom exercises, then routines, then workouts.
            assertEquals(
                listOf(AggregateType.CUSTOM_EXERCISE, AggregateType.WORKOUT),
                records.map { it.aggregateType },
            )
            records.forEach { assertEquals(ChangeOperation.UPSERT, it.operation) }
        }
    }
}
