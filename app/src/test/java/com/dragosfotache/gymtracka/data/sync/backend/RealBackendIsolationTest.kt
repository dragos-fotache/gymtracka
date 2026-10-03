package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.acceptedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pullAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.pushOne
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.rejectedOutcome
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendAsserts.snapshotAll
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customInput
import com.dragosfotache.gymtracka.data.sync.backend.RealBackendRequests.customUpsertRequest
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Focused real-backend account isolation and conflict coverage: foreign
 * reads/writes/references disclose nothing and change nothing, pull/snapshot
 * tokens are account-fenced, and last-write-wins follows server commit order
 * despite reversed client clocks and stale known revisions.
 */
class RealBackendIsolationTest {
    companion object {
        const val STARTED_AT = 1_780_000_000_000L
    }

    @Test
    fun foreignRootsAreInvisibleAndUnchangeable(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val accountA = fx.newAccount()
            val accountB = fx.newAccount()
            val apiA = fx.apiFor(accountA)
            val apiB = fx.apiFor(accountB)
            val seeds = fx.seedIds
            val owned = uid()
            pushOne(apiA, startRequest(mutationId(0), owned, "A's Workout", STARTED_AT))
            val (recordsBeforeB, _) = snapshotAll(apiB)
            val (_, tailBeforeB) = pullAll(apiB, null)

            // B sees none of A's data and cannot mutate it; every attempt is a
            // permanent lookup failure with no foreign disclosure.
            rejectedOutcome(pushOne(apiB, deleteRequest(mutationId(1), AggregateType.WORKOUT, owned)), "not_found")
            rejectedOutcome(
                pushOne(
                    apiB,
                    upsertRequest(
                        mutationId(2),
                        workoutInput(
                            owned,
                            exercises = listOf(
                                workoutEntry(uid(), seeds.bundledExerciseA, 0, sets = listOf(workoutSet(uid(), 0))),
                            ),
                        ),
                    ),
                ),
                "not_found",
            )

            val (recordsAfterB, _) = snapshotAll(apiB)
            assertEquals(recordsBeforeB, recordsAfterB)
            assertTrue(recordsAfterB.isEmpty())
            val (newBatches, _) = pullAll(apiB, tailBeforeB)
            assertTrue(newBatches.isEmpty())
            // A's root is untouched.
            val (recordsA, _) = snapshotAll(apiA)
            assertEquals(owned, recordsA.single().aggregateId)
        }
    }

    @Test
    fun pullAndSnapshotTokensAreAccountFenced(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val accountA = fx.newAccount()
            val accountB = fx.newAccount()
            val apiA = fx.apiFor(accountA)
            val apiB = fx.apiFor(accountB)
            pushOne(apiA, startRequest(mutationId(0), uid(), "A1", STARTED_AT))
            // A second root of another type: two starts would sole-active replace.
            pushOne(
                apiA,
                customUpsertRequest(
                    mutationId(1),
                    customInput(uid(), "A custom", primaryMuscleGroupId = fx.seedIds.muscleA),
                ),
            )
            val (_, cursorA) = pullAll(apiA, null)
            // Two roots with limit 1 force a continuation token.
            val snapshotPage = apiA.snapshot(null, 1)
            assertEquals(true, snapshotPage.hasMore)
            val tokenA = snapshotPage.nextPageToken
            assertTrue("Expected a snapshot continuation token", !tokenA.isNullOrBlank())

            // B replaying A's cursor learns nothing: unknown/foreign cursors
            // are invalid, never silent restarts.
            try {
                apiB.pull(cursorA, 10)
                fail("expected InvalidCursor")
            } catch (e: SyncApiException.InvalidCursor) {
                assertEquals("invalid_cursor", e.code)
            }
            // B replaying A's snapshot token is always rejected, never fenced
            // data: this assertion executes unconditionally, never skipped.
            try {
                apiB.snapshot(tokenA, 1)
                fail("expected InvalidSnapshotToken")
            } catch (e: SyncApiException.InvalidSnapshotToken) {
                assertEquals("invalid_snapshot_token", e.code)
            }
            try {
                apiB.pull("not-a-cursor", 10)
                fail("expected InvalidCursor")
            } catch (e: SyncApiException.InvalidCursor) {
                assertEquals("invalid_cursor", e.code)
            }
        }
    }

    @Test
    fun laterServerCommitWinsDespiteReversedClocks(): Unit = runBlocking {
        RealBackendFixture.load().use { fx ->
            val account = fx.newAccount()
            val api = fx.apiFor(account)
            val seeds = fx.seedIds
            val workout = uid()
            val entry = uid()
            val set = uid()
            pushOne(api, startRequest(mutationId(0), workout, "W", STARTED_AT))
            // First commit claims a far-future client clock and a fresh revision.
            acceptedOutcome(
                pushOne(
                    api,
                    upsertRequest(
                        mutationId(1),
                        workoutInput(
                            workout,
                            "Future Name",
                            STARTED_AT + 100_000_000L,
                            listOf(
                                workoutEntry(entry, seeds.bundledExerciseA, 0, sets = listOf(workoutSet(set, 0, 1, 1.0, false, null))),
                            ),
                        ),
                        knownServerRevision = 1L,
                    ),
                ),
            )
            // Second commit uses an older client clock and a stale known
            // revision; server commit order still makes it the whole winner.
            acceptedOutcome(
                pushOne(
                    api,
                    upsertRequest(
                        mutationId(2),
                        workoutInput(
                            workout,
                            "Past Winner",
                            STARTED_AT - 100_000_000L,
                            listOf(
                                workoutEntry(entry, seeds.bundledExerciseA, 0, sets = listOf(workoutSet(set, 0, 2, 2.0, false, null))),
                            ),
                        ),
                        knownServerRevision = 1L,
                    ),
                ),
            )

            val (records, _) = snapshotAll(api)
            val stored = records.single { it.aggregateId == workout }.record as CanonicalRecord.Workout
            assertEquals("Past Winner", stored.record.name)
            assertEquals(STARTED_AT - 100_000_000L, stored.record.startedAtMillis)
            assertEquals(2, stored.record.exercises.single().sets.single().repetitions)
            assertEquals(3L, stored.record.revision)
        }
    }
}
