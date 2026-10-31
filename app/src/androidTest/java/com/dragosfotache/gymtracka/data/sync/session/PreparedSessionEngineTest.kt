package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * private prepared-session engine admission and bootstrap.
 */
@RunWith(AndroidJUnit4::class)
class PreparedSessionEngineTest {

    private val harness = PreparedEngineHarness("admit")
    private val F = PreparedEngineFixtures

    @After
    fun cleanOnlyOwnTestArtifacts() {
        harness.clean()
    }

    @Test
    fun constructorPerformsNoIo() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            val auth = PreparedEngineAuthApi()
            val transport = PreparedEngineTransport()
            val owner = F.owner()
            F.engine(database, vault, auth, transport, owner)
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(0, auth.refreshCalls)
            assertEquals(0L, vault.load().revision)
            assertNull(vault.load().record)
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidInputRejectedBeforeEffectsAndHealthyInvocationSurvives() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val auth = PreparedEngineAuthApi()
            // Empty-outbox run: only snapshot then pull frames (push Empty is local).
            val transport = PreparedEngineTransport()
            transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
            transport.queueOk(F.EMPTY_PULL_BODY)
            val engine = F.engine(database, vault, auth, transport, F.owner())
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)

            try {
                engine.bootstrap(stageId = "not-a-uuid")
                fail("expected INVALID_INPUT")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.INVALID_INPUT, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
            }
            val badCalls: List<suspend () -> Unit> = listOf(
                { engine.bootstrap(stageId = F.STAGE_NEW, maxSnapshotPages = 0) },
                { engine.bootstrap(stageId = F.STAGE_NEW, snapshotPageLimit = 0) },
                { engine.bootstrap(stageId = F.STAGE_NEW, snapshotPageLimit = 501) },
                { engine.bootstrap(stageId = F.STAGE_NEW, maxPushMutations = 0) },
                { engine.bootstrap(stageId = F.STAGE_NEW, maxPullPages = 0) },
                { engine.bootstrap(stageId = F.STAGE_NEW, pullPageLimit = 0) },
                { engine.bootstrap(stageId = F.STAGE_NEW, pullPageLimit = 201) },
            )
            for (call in badCalls) {
                try {
                    call()
                    fail("expected INVALID_INPUT")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.INVALID_INPUT, e.failure)
                    assertNull(e.cause)
                }
            }
            // Validation retired nothing: no HTTP, no vault write, Room unchanged.
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(0, auth.refreshCalls)
            assertEquals(1L, vault.load().revision)
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))

            // The same healthy invocation still admits and bootstraps.
            val result = engine.bootstrap(stageId = F.STAGE_NEW)
            assertFalse(result.retainedStage)
            assertNotNull(result.baseline)
            assertNotNull(result.delivery)
            assertEquals(2, transport.capturedRequests.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun unpreparedPairAdmitsNothingAndPerformsNoHttp() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            val auth = PreparedEngineAuthApi()
            val transport = PreparedEngineTransport()
            val engine = F.engine(database, vault, auth, transport, F.owner())
            try {
                engine.bootstrap(stageId = F.STAGE_NEW)
                fail("expected NOT_PREPARED")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.NOT_PREPARED, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
            }
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(0, auth.refreshCalls)
            assertEquals(0L, vault.load().revision)
            assertNull(vault.load().record)
        } finally {
            database.close()
        }
    }

    @Test
    fun reservationWithoutGrantAdmitsNothingAndPerformsNoHttp() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val authority = RoomSessionAuthority(database)
            authority.beginPreparation(authority.read(), F.origin(), F.ACCOUNT, F.OP)
            val vault = harness.newVault()
            assertEquals(0L, vault.load().revision)
            val auth = PreparedEngineAuthApi()
            val transport = PreparedEngineTransport()
            val engine = F.engine(database, vault, auth, transport, F.owner())
            try {
                engine.bootstrap(stageId = F.STAGE_NEW)
                fail("expected NOT_PREPARED")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.NOT_PREPARED, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
            }
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(0, auth.refreshCalls)
            assertEquals(0L, vault.load().revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun emptyAccountAdmitsBootstrapWithExactRequestsAndTailBookkeeping() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)

            // Local push Empty: ZERO push HTTP; only snapshot then pull.
            val transport = PreparedEngineTransport()
            transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
            transport.queueOk(F.EMPTY_PULL_BODY)
            val auth = PreparedEngineAuthApi()
            val engine = F.engine(database, vault, auth, transport, F.owner())

            val result = engine.bootstrap(stageId = F.STAGE_NEW)
            assertFalse(result.retainedStage)
            val snapshot = checkNotNull(result.snapshot)
            val baseline = checkNotNull(result.baseline)
            val delivery = checkNotNull(result.delivery)
            assertEquals(baseline.cacheEpoch, result.cacheEpoch)

            // Start summary: local push Empty, terminal empty page counts as one page.
            assertTrue(snapshot.push.stop is PushDeliveryStop.Empty)
            assertEquals(0, snapshot.push.acknowledgedMutations)
            val download = checkNotNull(snapshot.download)
            assertEquals(1, download.committedPages)
            assertEquals(0, download.committedRecords)
            assertTrue(download.stop is SnapshotDownloadStop.Downloaded)
            assertEquals(F.BASELINE, baseline.baselineCursor)
            assertEquals(1L, baseline.pageCount)
            assertEquals(0L, baseline.recordCount)
            assertFalse(baseline.hasOutstandingLocalWork)
            assertEquals(1L, baseline.cacheEpoch)
            val pull = checkNotNull(delivery.pull)
            assertEquals(0, pull.committedBatches)
            assertTrue(pull.stop is PullDeliveryStop.Tail)

            // Exact wire sequence: snapshot then pull, bearer-only headers.
            assertEquals(2, transport.capturedRequests.size)
            F.assertSnapshotInitial(transport.capturedRequests[0])
            F.assertPull(transport.capturedRequests[1], F.BASELINE)
            assertTrue(transport.snapshotPaths().none { it == "/v1/sync/push" })

            // Actual tail bookkeeping: checkpoint cursor + last-success at the
            // fixed clock; snapshot establishment alone never writes these.
            val state = harness.syncState(database, F.ACCOUNT)
            assertNotNull(state)
            assertEquals(F.BASELINE, state!!.first)
            assertEquals(F.FIXED_CLOCK, state.second)
            val cache = harness.accountCache(database, F.ACCOUNT)
            assertNotNull(cache)
            assertEquals(1L, cache!!.baselineComplete)
            assertEquals(1L, cache.cacheEpoch)
            // No publisher runs in prepared engine: canonical completion dirties projection
            // while materialization stays at zero, even on an empty account.
            assertEquals(1L, cache.projectionDirty)
            assertEquals(0L, cache.materializedEpoch)

            // No publication: neither workouts nor routines become visible.

            // Canonical only: untouched visible domain graph, journal unchanged.
            assertEquals(0L, harness.countRows(database, "workouts"))
            assertEquals(0L, harness.countRows(database, "routines"))
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))
            val device = harness.deviceRow(database)
            assertNull(device[0])
            assertEquals(F.ACCOUNT, device[1])
            assertEquals("preparing", device[3])
            val stored = vault.load()
            assertEquals(1L, stored.revision)
            assertEquals(VaultSessionPhase.PREPARED, stored.record?.phase)
            assertEquals(F.ACCOUNT, stored.record?.binding?.accountId)

            F.assertRedacted(
                result.toString(),
                listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH, F.STAGE_NEW, F.BASELINE),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun nonemptyTwoPageAccountDiscoversOldRootsAndTailsRevision2() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val journalBefore = harness.journalRow(database)

            val transport = PreparedEngineTransport()
            transport.queueOk(F.snapshotPage1())
            transport.queueOk(F.snapshotPage2())
            transport.queueOk(F.pullRevision2Terminal())
            val auth = PreparedEngineAuthApi()
            val engine = F.engine(database, vault, auth, transport, F.owner())

            val result = engine.bootstrap(stageId = F.STAGE_NEW)
            assertFalse(result.retainedStage)
            val baseline = checkNotNull(result.baseline)
            val delivery = checkNotNull(result.delivery)
            assertEquals(F.BASELINE, baseline.baselineCursor)
            assertEquals(2L, baseline.pageCount)
            assertEquals(2L, baseline.recordCount)
            assertFalse(baseline.hasOutstandingLocalWork)
            assertEquals(1L, baseline.cacheEpoch)
            val pull = checkNotNull(delivery.pull)
            assertEquals(1, pull.committedBatches)
            assertTrue(pull.stop is PullDeliveryStop.Tail)
            assertEquals(baseline.cacheEpoch, result.cacheEpoch)

            // Exact bearer/origin/limits/tokens across snapshot + continuation + tail.
            assertEquals(3, transport.capturedRequests.size)
            F.assertSnapshotInitial(transport.capturedRequests[0])
            F.assertSnapshotContinuation(transport.capturedRequests[1], F.PAGE_2_TOKEN)
            F.assertPull(transport.capturedRequests[2], F.BASELINE)

            // Both snapshot roots canonical; first advanced to revision2 by tail.
            val roots = harness.canonicalRoots(database, F.ACCOUNT)
            assertEquals(2, roots.size)
            assertEquals(F.ROUTE_A, roots[0].aggregateId)
            assertEquals(F.ROUTE_B, roots[1].aggregateId)
            assertEquals(2L, roots[0].serverRevision)
            assertEquals(1L, roots[1].serverRevision)
            assertTrue(roots[0].canonicalJson?.contains("Changed cloud draft") == true)
            assertTrue(roots[1].canonicalJson?.contains("Second cloud draft") == true)

            // Nonempty complete batch advances checkpoint; last-success at clock.
            val state = harness.syncState(database, F.ACCOUNT)
            assertNotNull(state)
            assertEquals(F.TAIL, state!!.first)
            assertEquals(F.FIXED_CLOCK, state.second)

            // Canonical only: untouched visible domain graph (records are routines,
            // so the routines oracle is the publication tripwire), journal unchanged.
            assertEquals(0L, harness.countRows(database, "workouts"))
            assertEquals(0L, harness.countRows(database, "routines"))
            assertEquals(0L, harness.countRows(database, "routine_exercises"))
            assertEquals(journalBefore, harness.journalRow(database))
            val device = harness.deviceRow(database)
            assertNull(device[0])
            assertEquals(F.ACCOUNT, device[1])
            assertEquals("preparing", device[3])
            assertEquals(1L, vault.load().revision)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
            val nonemptyCache = harness.accountCache(database, F.ACCOUNT)
            assertNotNull(nonemptyCache)
            assertEquals(1L, nonemptyCache!!.baselineComplete)
            assertEquals(1L, nonemptyCache.cacheEpoch)
            assertEquals(1L, nonemptyCache.projectionDirty)
            assertEquals(0L, nonemptyCache.materializedEpoch)
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidInputWhileHealthyCallParkedDoesNotRetireIt() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val transport = PreparedEngineTransport()
            transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
            transport.queueOk(F.EMPTY_PULL_BODY)
            var calls = 0
            transport.responseGate = {
                calls += 1
                if (calls == 1) {
                    entered.complete(Unit)
                    gate.await()
                }
            }
            val auth = PreparedEngineAuthApi()
            val engine = F.engine(database, vault, auth, transport, F.owner())
            supervisorScope {
                // A healthy invocation is admitted and parked in snapshot HTTP.
                val healthy = async { engine.bootstrap(stageId = F.STAGE_NEW) }
                entered.await()
                // Invalid input on the SAME engine is a preflight refusal only:
                // it neither poisons nor newest-retires the parked call.
                try {
                    engine.bootstrap(stageId = "not-a-uuid")
                    fail("expected INVALID_INPUT")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.INVALID_INPUT, e.failure)
                    F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
                }
                gate.complete(Unit)
                val result = healthy.await()
                assertFalse(result.retainedStage)
                val baseline = checkNotNull(result.baseline)
                val delivery = checkNotNull(result.delivery)
                assertEquals(1L, baseline.pageCount)
                assertEquals(F.BASELINE, baseline.baselineCursor)
                assertNotNull(delivery.pull)
                assertEquals(2, transport.capturedRequests.size)
                F.assertSnapshotInitial(transport.capturedRequests[0])
                F.assertPull(transport.capturedRequests[1], F.BASELINE)
                val state = harness.syncState(database, F.ACCOUNT)
                assertEquals(F.BASELINE, state?.first)
                assertEquals(F.FIXED_CLOCK, state?.second)
            }
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun lostResultAfterCommittedBaselineRecoversTailWithoutRedownload() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val owner = F.owner()
            // The snapshot commits fully; the admitted tail invocation is then
            // retired by invalidation, so its result is genuinely lost with a
            // dirty complete baseline and last_success still null.
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val failingTransport = PreparedEngineTransport()
            failingTransport.queueOk(F.snapshotPage1())
            failingTransport.queueOk(F.snapshotPage2())
            failingTransport.queueOk(F.pullRevision2Terminal())
            var calls = 0
            failingTransport.responseGate = {
                calls += 1
                if (calls == 3) {
                    entered.complete(Unit)
                    gate.await()
                }
            }
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), failingTransport, owner)
            supervisorScope {
                val job = async { engine.bootstrap(stageId = F.STAGE_NEW) }
                entered.await()
                engine.invalidate()
                gate.complete(Unit)
                try {
                    job.await()
                    fail("expected retired tail to fail closed")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                    F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
                }
            }

            // Durable proof from stores, never the lost result: complete dirty
            // baseline committed (stage removed, both roots canonical), yet
            // last_success still null because no tail ever ran.
            val durableEpoch = harness.accountCache(database, F.ACCOUNT)?.cacheEpoch
            assertNotNull(durableEpoch)
            assertNull(harness.stageHeader(database, F.STAGE_NEW))
            assertEquals(2, harness.canonicalRoots(database, F.ACCOUNT).size)
            assertNull(harness.syncState(database, F.ACCOUNT)?.second)

            // The SAME engine recovers: eligible durable baseline + valid
            // checkpoint with no stage skips snapshot and records the tail.
            // A spare pull frame covers the retired run whether or not its
            // parked call transmitted after release.
            failingTransport.queueOk(F.EMPTY_PULL_BODY)
            val recovered = engine.bootstrap(stageId = F.STAGE_OTHER)
            assertFalse(recovered.retainedStage)
            assertNull(recovered.snapshot)
            assertNotNull(recovered.delivery)
            assertEquals(durableEpoch, recovered.cacheEpoch)
            assertEquals(2, failingTransport.snapshotPaths().count { it == "/v1/sync/snapshot" })
            F.assertPull(failingTransport.capturedRequests.last(), F.BASELINE)
            engine.invalidate()

            val state = harness.syncState(database, F.ACCOUNT)
            assertEquals(F.BASELINE, state?.first)
            assertEquals(F.FIXED_CLOCK, state?.second)
        } finally {
            database.close()
        }
    }
}
