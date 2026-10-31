package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.projectionGrantBarrier
import com.dragosfotache.gymtracka.data.sync.projectionQuery
import com.dragosfotache.gymtracka.data.sync.ProjectionTestLease
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks complete private preparation and exact vault phase promotion through real storage,
 * preserving grant and pending-request evidence.
 */
@RunWith(AndroidJUnit4::class)
class PreparedSessionCompletionTest {

    private val harness = PreparedEngineHarness("complete")
    private val F = PreparedEngineFixtures
    private val C = PreparedCompletionSupport

    @After
    fun cleanOnlyOwnTestArtifacts() {
        harness.clean()
    }

    @Test
    fun emptyCompletionEstablishesActiveAndPromotesExactGrant() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            val bootstrapped = engine.bootstrap(stageId = F.STAGE_NEW)
            assertNotNull(bootstrapped.baseline)
            assertNotNull(bootstrapped.delivery)
            val cacheBefore = checkNotNull(harness.accountCache(database, F.ACCOUNT))
            assertEquals(1L, cacheBefore.baselineComplete)
            val tailBefore = harness.syncState(database, F.ACCOUNT)
            assertNotNull(tailBefore)

            val lease = ProjectionTestLease()
            val barrier = projectionGrantBarrier(lease = lease)
            val result = engine.completePreparation(barrier)
            assertEquals(PreparedCompletionStatus.COMPLETED, result.status)
            val delivery = checkNotNull(result.delivery)
            assertEquals(false, delivery.hasOutstandingLocalWork)
            assertEquals(PullDeliveryStop.Tail, delivery.pull?.stop)
            // Completion consumes exactly one additional actual pull: no
            // snapshot redownload, no fabricated summary.
            assertEquals(3, transport.capturedRequests.size)
            assertEquals(
                listOf("/v1/sync/snapshot", "/v1/sync/pull", "/v1/sync/pull"),
                transport.snapshotPaths(),
            )
            F.assertPull(transport.capturedRequests[2], F.BASELINE)
            C.assertRedactedCompletion(result.toString())

            // Atomic Room ACTIVE transition; actual tail row preserved.
            C.assertRoomActive(harness, database)
            val tailAfter = harness.syncState(database, F.ACCOUNT)
            assertEquals(tailBefore, tailAfter)
            assertEquals(F.BASELINE, tailAfter?.first)
            assertEquals(F.FIXED_CLOCK, tailAfter?.second)
            // Barrier observed the durable pre-completion epoch; the fresh
            // dirty publication advanced exactly one epoch.
            assertEquals(1, barrier.requests.size)
            val request = barrier.requests.single()
            assertEquals(F.ACCOUNT, request.accountId)
            assertEquals(1L, request.sessionGeneration)
            assertEquals(cacheBefore.cacheEpoch, request.expectedCacheEpoch)
            val cacheAfter = checkNotNull(harness.accountCache(database, F.ACCOUNT))
            assertEquals(1L, cacheAfter.baselineComplete)
            assertEquals(0L, cacheAfter.projectionDirty)
            assertEquals(cacheAfter.cacheEpoch, cacheAfter.materializedEpoch)
            // Fresh dirty empty publication materializes exactly one epoch.
            assertEquals(cacheBefore.cacheEpoch + 1, cacheAfter.cacheEpoch)
            assertEquals(cacheAfter.cacheEpoch, result.cacheEpoch)
            assertEquals(1, lease.closeCount)
            // Exact phase-only promotion: one revision, full grant preserved.
            C.assertVaultActiveFor(vault, 2L)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun nonemptyCompletionMaterializesLatestPullGraph() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapNonempty(transport)
            C.queueCompletionTailAtTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            val bootstrapped = engine.bootstrap(stageId = F.STAGE_NEW)
            assertNotNull(bootstrapped.baseline)
            val cacheBefore = checkNotNull(harness.accountCache(database, F.ACCOUNT))

            val lease = ProjectionTestLease()
            val barrier = projectionGrantBarrier(lease = lease)
            val result = engine.completePreparation(barrier)
            assertEquals(PreparedCompletionStatus.COMPLETED, result.status)
            val delivery = checkNotNull(result.delivery)
            assertEquals(false, delivery.hasOutstandingLocalWork)
            assertEquals(PullDeliveryStop.Tail, delivery.pull?.stop)
            assertEquals(4, transport.capturedRequests.size)
            F.assertPull(transport.capturedRequests[3], F.TAIL)
            C.assertRedactedCompletion(result.toString())

            // Latest actual pull graph is the MATERIALIZED one: rev2 winner
            // in the domain projection, not merely the canonical ledger.
            val roots = harness.canonicalRoots(database, F.ACCOUNT)
            val routeA = roots.single { it.aggregateId == F.ROUTE_A }
            assertEquals(2L, routeA.serverRevision)
            assertTrue(roots.any { it.aggregateId == F.ROUTE_B && it.serverRevision == 1L })
            val domainA = database.projectionQuery(
                "SELECT name, account_id, server_revision, sync_state FROM routines WHERE id = ?",
                arrayOf(F.ROUTE_A),
            ).single()
            assertEquals("Changed cloud draft", domainA["name"])
            assertEquals(F.ACCOUNT, domainA["account_id"])
            assertEquals(2L, domainA["server_revision"])
            assertEquals("synced", domainA["sync_state"])
            val domainB = database.projectionQuery(
                "SELECT name, account_id FROM routines WHERE id = ?",
                arrayOf(F.ROUTE_B),
            ).single()
            assertEquals("Second cloud draft", domainB["name"])
            assertEquals(F.ACCOUNT, domainB["account_id"])
            C.assertRoomActive(harness, database)
            assertEquals(F.TAIL to F.FIXED_CLOCK, harness.syncState(database, F.ACCOUNT))
            val cacheAfter = checkNotNull(harness.accountCache(database, F.ACCOUNT))
            assertEquals(0L, cacheAfter.projectionDirty)
            assertEquals(cacheAfter.cacheEpoch, cacheAfter.materializedEpoch)
            // Fresh dirty nonempty publication advances exactly one epoch.
            assertEquals(cacheBefore.cacheEpoch + 1, cacheAfter.cacheEpoch)
            assertEquals(cacheAfter.cacheEpoch, result.cacheEpoch)
            assertEquals(1, barrier.requests.size)
            assertEquals(cacheBefore.cacheEpoch, barrier.requests.single().expectedCacheEpoch)
            C.assertVaultActiveFor(vault, 2L)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidBudgetsRejectedBeforeEffectsAndHealthyCompletionSurvives() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)
            val lease = ProjectionTestLease()
            val barrier = projectionGrantBarrier(lease = lease)

            val badCalls: List<suspend () -> Unit> = listOf(
                { engine.completePreparation(barrier, maxPushMutations = 0) },
                { engine.completePreparation(barrier, maxPushMutations = 101) },
                { engine.completePreparation(barrier, maxPullPages = 0) },
                { engine.completePreparation(barrier, maxPullPages = 101) },
                { engine.completePreparation(barrier, pullPageLimit = 0) },
                { engine.completePreparation(barrier, pullPageLimit = 201) },
            )
            for (call in badCalls) {
                try {
                    call()
                    fail("expected INVALID_INPUT")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.INVALID_INPUT, e.failure)
                    C.assertClosedCompletion(e)
                }
            }
            // Validation retired nothing: no HTTP, no vault write, Room kept.
            assertEquals(2, transport.capturedRequests.size)
            assertEquals(1L, vault.load().revision)
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))
            C.assertRoomPreparingRev1(harness, database)

            val result = engine.completePreparation(barrier)
            assertEquals(PreparedCompletionStatus.COMPLETED, result.status)
            C.assertRoomActive(harness, database)
            C.assertVaultActiveFor(vault, 2L)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun missingPreparedCredentialsFailsClosedNotPrepared() = runTest {
        val database = harness.openInMemoryDb()
        try {
            // Room reservation with NO vault grant: missing PREPARING
            // credentials stay closed NOT_PREPARED (vetted bootstrap shape).
            val authority = RoomSessionAuthority(database)
            authority.beginPreparation(authority.read(), F.origin(), F.ACCOUNT, F.OP)
            val vault = harness.newVault()
            assertEquals(0L, vault.load().revision)
            val transport = PreparedEngineTransport()
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            try {
                engine.completePreparation(projectionGrantBarrier())
                fail("expected missing credentials to fail closed")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.NOT_PREPARED, e.failure)
                C.assertClosedCompletion(e)
            }
            assertTrue(transport.capturedRequests.isEmpty())
            C.assertRoomPreparingRev1(harness, database)
            assertEquals(0L, vault.load().revision)
            assertNull(vault.load().record)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun missingBaselineAndCheckpointReturnsNotReadyUnchanged() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            val result = engine.completePreparation(projectionGrantBarrier())
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            assertNull(result.delivery)
            C.assertRedactedCompletion(result.toString())
            // No hidden snapshot invented: no HTTP, pair untouched.
            assertTrue(transport.capturedRequests.isEmpty())
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            assertNull(harness.accountCache(database, F.ACCOUNT))
            assertNull(harness.syncState(database, F.ACCOUNT))
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun retainedStageBlocksCompletionWithoutAbandonment() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            transport.queueOk(F.snapshotPage1())
            transport.queueOk(F.snapshotPage2())
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            val limited = engine.bootstrap(stageId = F.STAGE_RETAINED, maxSnapshotPages = 1)
            assertNotNull(limited.snapshot)
            assertNull(limited.baseline)
            val headerBefore = harness.stageHeader(database, F.STAGE_RETAINED)
            assertNotNull(headerBefore)

            val result = engine.completePreparation(projectionGrantBarrier())
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            C.assertRedactedCompletion(result.toString())
            // Retained stage is preserved, never adopted or abandoned here.
            assertEquals(headerBefore, harness.stageHeader(database, F.STAGE_RETAINED))
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingHeadRunsActualPushAndRetainedRejectionPreventsCompletion() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            transport.queueOk(C.pushRejectionBody(F.OUTBOX_NEWER_MUT))
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // A NEW pending head IS transmitted by the actual shared delivery push driver;
            // its rejection is then durably retained and prevents completion.
            harness.seedOutboxRow(
                database,
                sequence = 10L,
                mutationId = F.OUTBOX_NEWER_MUT,
                aggregateId = F.OUTBOX_WORKOUT,
                mutationType = "workout.start",
                localGeneration = 4L,
                state = "pending",
                payloadJson = F.durablePayloadJson(F.OUTBOX_NEWER_MUT, F.OUTBOX_WORKOUT, 4L),
            )
            val result = engine.completePreparation(projectionGrantBarrier())
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            C.assertRedactedCompletion(result.toString())
            // Exactly one actual push transmission, then the stop: no pull
            // around the retained rejection, no empty-tail inference.
            assertEquals(3, transport.capturedRequests.size)
            val push = transport.capturedRequests[2]
            assertEquals(com.dragosfotache.gymtracka.data.sync.http.ServiceHttpMethod.POST, push.method)
            assertEquals("/v1/sync/push", push.path)
            assertEquals(
                "{\"mutations\":[${F.durableInnerJson(F.OUTBOX_NEWER_MUT, F.OUTBOX_WORKOUT, 4L)}]}",
                push.body,
            )
            val rows = harness.outboxSnapshot(database, F.ACCOUNT)
            assertEquals(1, rows.size)
            assertEquals(F.OUTBOX_NEWER_MUT, rows[0][1])
            assertEquals("rejected", rows[0][9])
            assertEquals("gone", rows[0][12])
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun rejectedHeadStopsCompletionWithoutTailInference() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            harness.seedOutboxRow(
                database,
                sequence = 1L,
                mutationId = F.OUTBOX_REJECTED_MUT,
                aggregateId = F.OUTBOX_WORKOUT,
                mutationType = "workout.start",
                localGeneration = 1L,
                state = "rejected",
                errorCode = "permanent",
                payloadJson = F.durablePayloadJson(F.OUTBOX_REJECTED_MUT, F.OUTBOX_WORKOUT, 1L),
            )
            val callsBefore = transport.capturedRequests.size
            val result = engine.completePreparation(projectionGrantBarrier())
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            // A rejected predecessor protects the run: no pull around it, no
            // empty-summary inference of a tail.
            assertEquals(callsBefore, transport.capturedRequests.size)
            assertEquals(1, harness.outboxSnapshot(database, F.ACCOUNT).size)
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun newIntentAtBarrierPreventsCompletionWithoutActiveWrites() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val lease = ProjectionTestLease()
            val barrier = projectionGrantBarrier(
                lease = lease,
                onAcquire = {
                    harness.seedOutboxRow(
                        database,
                        sequence = 1L,
                        mutationId = F.OUTBOX_NEWER_MUT,
                        aggregateId = F.OUTBOX_WORKOUT,
                        mutationType = "workout.start",
                        localGeneration = 1L,
                        state = "pending",
                        payloadJson = F.durablePayloadJson(F.OUTBOX_NEWER_MUT, F.OUTBOX_WORKOUT, 1L),
                    )
                },
            )
            val result = engine.completePreparation(barrier)
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            C.assertRedactedCompletion(result.toString())
            // The intervening edit aborts publication rather than being
            // overwritten: Room stays PREPARING, vault stays PREPARED.
            assertEquals(1, harness.outboxSnapshot(database, F.ACCOUNT).size)
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            assertEquals(1, lease.closeCount)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun corruptRetainedStageFailsClosedWithoutBypassOrCleanup() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Corrupt retained stage seeded through the vetted Room authority column
            // contract (same garbage shape as the bootstrap precedent).
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO sync_snapshot_stages (stage_id, account_id, session_generation, cache_epoch," +
                    " page_limit, source_state_present, source_cursor, source_last_success_millis," +
                    " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                    " page_count, record_count)" +
                    " VALUES (?, ?, 999, 0, 200, 0, NULL, NULL, 0, NULL, 'bootstrap-foreign-token', 'bootstrap-garbage', 3, 7)",
                arrayOf<Any?>(F.STAGE_CORRUPT, F.ACCOUNT),
            )
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO sync_snapshot_records (stage_id, aggregate_type, aggregate_id," +
                    " server_revision, canonical_json, page_number, record_position)" +
                    " VALUES (?, 'workout', 'bootstrap-garbage-root', 1, '{\"id\":\"garbage\"}', 0, 0)",
                arrayOf<Any?>(F.STAGE_CORRUPT),
            )
            val corruptBefore = harness.stageFullRow(database, F.STAGE_CORRUPT)
            assertNotNull(corruptBefore)
            try {
                engine.completePreparation(projectionGrantBarrier(lease = ProjectionTestLease()))
                fail("expected corrupt stage to fail closed, never complete")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.AUTHORITY_UNAVAILABLE, e.failure)
                C.assertClosedCompletion(e)
            }
            // No bypass, no cleanup: corrupt header/children byte-identical,
            // pair untouched, vault unpromoted.
            assertEquals(corruptBefore, harness.stageFullRow(database, F.STAGE_CORRUPT))
            assertEquals(1L, harness.stageRecordCount(database, F.STAGE_CORRUPT))
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun pullBudgetStopPreservesEarlierBatchAndPreventsCompletion() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            // Two-page pull: first page advances to a mid cursor, terminal
            // page would finish at the tail (never reached under budget).
            transport.queueOk(
                "{\"batches\":[{\"batchId\":\"c1000000-2222-4222-8222-222222222222\"," +
                    "\"cursor\":\"completion-mid\",\"changes\":" +
                    "[${F.routineUpsertJson(F.ROUTE_A, "Mid cloud draft", 1)}]}]," +
                    "\"nextCursor\":\"completion-mid\",\"hasMore\":true}",
            )
            transport.queueOk(C.TAIL_EMPTY_PULL_BODY)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val result = engine.completePreparation(
                projectionGrantBarrier(lease = ProjectionTestLease()),
                maxPullPages = 1,
            )
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            C.assertRedactedCompletion(result.toString())
            // Earlier committed batch survives; the tail never established.
            val roots = harness.canonicalRoots(database, F.ACCOUNT)
            assertEquals(1, roots.size)
            assertEquals(1L, roots.single { it.aggregateId == F.ROUTE_A }.serverRevision)
            assertEquals("completion-mid" to F.FIXED_CLOCK, harness.syncState(database, F.ACCOUNT))
            assertTrue(database.projectionQuery("SELECT id FROM routines").isEmpty())
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun transportStopPreventsCompletionWithoutWrites() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            transport.queueCode(500, "{}")
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)
            val result = engine.completePreparation(projectionGrantBarrier(lease = ProjectionTestLease()))
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            C.assertRedactedCompletion(result.toString())
            // Typed transport stop preserved: failed pull attempted once,
            // pair and vault unchanged.
            assertEquals(3, transport.capturedRequests.size)
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun sameSessionRotationBeforeCompletionPromotesFreshGrant() = runTest {
        val database = harness.openInMemoryDb()
        try {
            // Ordinary live-grant bootstrap with NO pending refresh: nothing
            // rotates here, so a later pin of the admission revision would
            // be observable.
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val rotated = F.grant(access = F.ACCESS_ROTATED)
            val auth = PreparedEngineAuthApi()
            auth.answer(rotated)
            val transport = PreparedEngineTransport()
            C.queueBootstrapNonempty(transport)
            C.queueCompletionTailAtTail(transport)
            val engine = F.engine(database, vault, auth, transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            assertEquals(0, auth.refreshCalls)
            assertEquals(1L, vault.load().revision)
            // Authorized test-only setup (same identity/operation): the
            // PREPARED record gains a whitespace-exact pending attempt that
            // completion admission itself observes.
            val whitespaceBody = "{\n  \"refreshToken\": \"bootstrap-refresh-001\"\n}"
            val admitted = checkNotNull(vault.load().record)
            val withPending = admitted.copy(
                pendingRefresh = VaultPendingRefresh(
                    operationId = F.PENDING_OP,
                    attempt = F.pendingAttempt(body = whitespaceBody),
                ),
            )
            assertEquals(2L, vault.compareAndSet(1L, withPending).revision)

            val lease = ProjectionTestLease()
            val barrier = projectionGrantBarrier(lease = lease)
            val result = engine.completePreparation(barrier)
            assertEquals(PreparedCompletionStatus.COMPLETED, result.status)
            val delivery = checkNotNull(result.delivery)
            assertEquals(PullDeliveryStop.Tail, delivery.pull?.stop)
            // Same-completion-tail rotation: exactly one refresh during the
            // completion, none before; the completion pull already bears the
            // ROTATED access, so promotion pins the post-tail grant, never
            // the admission revision.
            assertEquals(1, auth.refreshCalls)
            assertEquals(whitespaceBody, auth.seenAttempts.single().requestJson)
            assertEquals(F.REFRESH_KEY, auth.seenAttempts.single().idempotencyKey)
            F.assertPull(transport.capturedRequests[3], F.TAIL, access = F.ACCESS_ROTATED)
            // Real materialized domain graph under the rotated session.
            val domainA = database.projectionQuery(
                "SELECT name, account_id, server_revision, sync_state FROM routines WHERE id = ?",
                arrayOf(F.ROUTE_A),
            ).single()
            assertEquals("Changed cloud draft", domainA["name"])
            assertEquals(F.ACCOUNT, domainA["account_id"])
            assertEquals(2L, domainA["server_revision"])
            assertEquals("synced", domainA["sync_state"])
            C.assertRoomActive(harness, database)
            // Revision arithmetic: reserve 1 + pending CAS 1 + refresh CAS 1
            // + promotion CAS 1. Pending-at-promotion verbatim preservation
            // is proven separately in recovery, so no pending shape is
            // asserted here.
            C.assertVaultActiveFor(
                vault,
                4L,
                access = F.ACCESS_ROTATED,
                checkPendingNull = false,
            )
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun physicalCollisionPreservesOldGraphAndPrivateAuthority() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapNonempty(transport)
            C.queueCompletionTailAtTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // A true separate physical collision: a FOREIGN-owned row carries
            // the incoming canonical identity (nullable-local rows would only
            // exercise legacy-root refusal). Publication must fail closed,
            // never overwrite or remap the physical row.
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO routines (id, name, is_draft, account_id, server_revision," +
                    " local_generation, sync_state, deleted_at_millis)" +
                    " VALUES (?, 'Foreign survivor', 0, ?, 9, 3, 'synced', NULL)",
                arrayOf<Any?>(F.ROUTE_A, F.OTHER_ACCOUNT),
            )
            val journalBefore = harness.journalRow(database)
            val rootsBefore = harness.canonicalRoots(database, F.ACCOUNT)
            try {
                engine.completePreparation(projectionGrantBarrier(lease = ProjectionTestLease()))
                fail("expected physical collision to fail closed")
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            // Old graph and private authority preserved; the foreign owner's
            // exact bytes survive byte-identical.
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(rootsBefore, harness.canonicalRoots(database, F.ACCOUNT))
            val survivor = database.projectionQuery(
                "SELECT name, account_id, server_revision, local_generation, sync_state," +
                    " deleted_at_millis FROM routines WHERE id = ?",
                arrayOf(F.ROUTE_A),
            ).single()
            assertEquals("Foreign survivor", survivor["name"])
            assertEquals(F.OTHER_ACCOUNT, survivor["account_id"])
            assertEquals(9L, survivor["server_revision"])
            assertEquals(3L, survivor["local_generation"])
            assertEquals("synced", survivor["sync_state"])
            assertNull(survivor["deleted_at_millis"])
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }
}
