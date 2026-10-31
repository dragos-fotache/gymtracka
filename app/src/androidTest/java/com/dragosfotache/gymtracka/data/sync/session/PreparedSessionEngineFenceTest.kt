package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.projectionGrantBarrier
import com.dragosfotache.gymtracka.data.sync.ProjectionTestLease
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStore
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.SyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
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
 * process/identity fencing around the prepared engine.
 */
@RunWith(AndroidJUnit4::class)
class PreparedSessionEngineFenceTest {

    private val harness = PreparedEngineHarness("fence")
    private val F = PreparedEngineFixtures

    @After
    fun cleanOnlyOwnTestArtifacts() {
        harness.clean()
    }

    @Test
    fun wrongOriginRefusesBeforeHttpWithoutCleanup() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault, origin = F.origin())
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)
            val transport = PreparedEngineTransport()
            val auth = PreparedEngineAuthApi()
            val engine = F.engine(database, vault, auth, transport, F.owner(), origin = F.origin(F.OTHER_ORIGIN_ROOT))
            try {
                engine.bootstrap(stageId = F.STAGE_NEW)
                fail("expected IDENTITY_MISMATCH")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.IDENTITY_MISMATCH, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH, F.OTHER_ORIGIN_ROOT))
            }
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(0, auth.refreshCalls)
            assertEquals(1L, vault.load().revision)
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun foreignVaultRecordRefusesBeforeHttpWithoutCleanup() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            val authority = RoomSessionAuthority(database)
            val coordinator = SessionPreparationCoordinator(F.origin(), authority, vault)
            coordinator.prepare(authority.read(), F.OP, F.grant(accountId = F.ACCOUNT))
            vault.compareAndSet(
                1L,
                F.preparedRecord(
                    binding = F.binding(accountId = F.OTHER_ACCOUNT),
                    operationId = F.OP,
                    session = F.grant(accountId = F.OTHER_ACCOUNT),
                ),
            )
            val journalBefore = harness.journalRow(database)
            val transport = PreparedEngineTransport()
            val auth = PreparedEngineAuthApi()
            val engine = F.engine(database, vault, auth, transport, F.owner())
            try {
                engine.bootstrap(stageId = F.STAGE_NEW)
                fail("expected IDENTITY_MISMATCH")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.IDENTITY_MISMATCH, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OTHER_ACCOUNT, F.OP, F.ACCESS))
            }
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(0, auth.refreshCalls)
            assertEquals(2L, vault.load().revision)
            assertEquals(F.OTHER_ACCOUNT, vault.load().record?.binding?.accountId)
            assertEquals(journalBefore, harness.journalRow(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun tombstoneBeforeCallIsNeedsGrantNotPrepared() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            vault.compareAndSet(1L, null)
            assertEquals(2L, vault.load().revision)
            val journalBefore = harness.journalRow(database)
            val transport = PreparedEngineTransport()
            val auth = PreparedEngineAuthApi()
            val engine = F.engine(database, vault, auth, transport, F.owner())
            // A tombstoned vault reads as NeedsGrant: deterministic NOT_PREPARED.
            try {
                engine.bootstrap(stageId = F.STAGE_NEW)
                fail("expected NOT_PREPARED")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.NOT_PREPARED, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
            }
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(0, auth.refreshCalls)
            assertEquals(journalBefore, harness.journalRow(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun midflightVaultMismatchFailsClosedWithoutApplyingTail() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val transport = PreparedEngineTransport()
            transport.queueOk(F.snapshotPage1())
            transport.queueOk(F.snapshotPage2())
            transport.queueOk(F.pullRevision2Terminal())
            var calls = 0
            transport.responseGate = {
                calls += 1
                // Park every snapshot/pull call; the vault is swapped while parked.
                entered.complete(Unit)
                gate.await()
            }
            val auth = PreparedEngineAuthApi()
            val engine = F.engine(database, vault, auth, transport, F.owner())
            supervisorScope {
                val job = async { engine.bootstrap(stageId = F.STAGE_NEW) }
                entered.await()
                // Midflight the vault record changes identity under the same pair.
                vault.compareAndSet(
                1L,
                F.preparedRecord(
                    binding = F.binding(accountId = F.OTHER_ACCOUNT),
                    operationId = F.OP,
                    session = F.grant(accountId = F.OTHER_ACCOUNT),
                ),
            )
            gate.complete(Unit)
                try {
                    job.await()
                    fail("expected midflight mismatch to fail closed")
                } catch (e: PreparedEngineException) {
                    // Provided seam contract: a post-admission vault identity change
                    // surfaces as refresh STALE_SESSION, mapped to outer STALE_OPERATION
                    // (initial-admission mismatch stays IDENTITY_MISMATCH).
                    assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                    F.assertClosed(e, listOf(F.ACCOUNT, F.OTHER_ACCOUNT, F.OP, F.ACCESS))
                }
            }
            // Nothing applied: no tail bookkeeping, journal still preparing.
            val state = harness.syncState(database, F.ACCOUNT)
            assertTrue(state == null || state.second != F.FIXED_CLOCK)
            assertEquals("preparing", harness.journalRow(database)[2])
        } finally {
            database.close()
        }
    }

    @Test
    fun stagePagePostWriteRollsBackPageWritePreservingBegin() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val journalBefore = harness.journalRow(database)
            // Test-owned AFTER-write tripwire (accepted tests trigger pattern):
            // any stage-record write bumps the journal revision inside the
            // same transaction.
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER bootstrap_tamper AFTER INSERT ON sync_snapshot_records " +
                    "BEGIN UPDATE sync_session_authority SET revision = revision + 1 WHERE id = 1; END",
            )
            val transport = PreparedEngineTransport()
            transport.queueOk(F.snapshotPage1())
            transport.queueOk(F.snapshotPage2())
            transport.queueOk(F.pullRevision2Terminal())
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            try {
                engine.bootstrap(stageId = F.STAGE_NEW)
                fail("expected post-write fence to fail closed")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
            }
            // The exact post-write journal fence rolls back the page write AND
            // the trigger tamper, but the earlier committed begin stands: the
            // downloading header survives at pageCount 0/recordCount 0 with
            // zero children, journal byte-identical, and no canonical/cache/tail.
            assertEquals(journalBefore, harness.journalRow(database))
            val header = checkNotNull(harness.stageHeader(database, F.STAGE_NEW))
            assertEquals(0L, header.pageCount)
            assertEquals(0L, header.recordCount)
            assertEquals(0L, harness.stageRecordCount(database, F.STAGE_NEW))
            assertTrue(harness.canonicalRoots(database, F.ACCOUNT).isEmpty())
            val state = harness.syncState(database, F.ACCOUNT)
            assertTrue(state == null || state.second != F.FIXED_CLOCK)
            assertNull(harness.accountCache(database, F.ACCOUNT))
            assertEquals(1L, vault.load().revision)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
        } finally {
            database.close()
        }
    }

    @Test
    fun baselinePostWriteRollsBackCanonicalCommitPreservingStage() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val journalBefore = harness.journalRow(database)
            // Tripwire on the canonical commit instead: the downloaded terminal
            // stage/pages stand while the baseline post-write fails.
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER bootstrap_tamper_base AFTER INSERT ON sync_canonical_roots " +
                    "BEGIN UPDATE sync_session_authority SET revision = revision + 1 WHERE id = 1; END",
            )
            val transport = PreparedEngineTransport()
            transport.queueOk(F.snapshotPage1())
            transport.queueOk(F.snapshotPage2())
            transport.queueOk(F.pullRevision2Terminal())
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            try {
                engine.bootstrap(stageId = F.STAGE_NEW)
                fail("expected baseline post-write fence to fail closed")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
            }
            // Tamper and baseline commit both rolled back (journal identical),
            // while the already downloaded terminal stage/pages survive.
            assertEquals(journalBefore, harness.journalRow(database))
            val header = checkNotNull(harness.stageHeader(database, F.STAGE_NEW))
            assertEquals(2L, header.pageCount)
            assertEquals(2L, header.recordCount)
            assertEquals(2L, harness.stageRecordCount(database, F.STAGE_NEW))
            assertTrue(harness.canonicalRoots(database, F.ACCOUNT).isEmpty())
            assertNull(harness.accountCache(database, F.ACCOUNT))
            val state = harness.syncState(database, F.ACCOUNT)
            assertTrue(state == null || state.second != F.FIXED_CLOCK)
            assertEquals(1L, vault.load().revision)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidationWhileAdmittedRetiresCurrentButAdmitsNewInvocation() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val owner = F.owner()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val transport = PreparedEngineTransport()
            // Frames for the retired run (one snapshot) plus the later new
            // invocation (snapshot then pull); the gate parks ONLY the first call.
            transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
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
            val engine = F.engine(database, vault, auth, transport, owner)
            // Admit the call (parked inside the snapshot request), then retire it.
            // A supervisor isolates the expected child failure: it must surface
            // through await, never poison the runTest parent scope.
            supervisorScope {
                val current = async { engine.bootstrap(stageId = F.STAGE_NEW) }
                entered.await()
                engine.invalidate()
                engine.invalidate()
                gate.complete(Unit)
                try {
                    current.await()
                    fail("expected retired invocation to fail closed")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                    F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
                }
            }
            // No durable cleanup and no committed baseline from the retired run;
            // its empty begin header stands preserved.
            assertEquals("preparing", harness.journalRow(database)[2])
            assertEquals(1L, vault.load().revision)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
            assertNull(harness.accountCache(database, F.ACCOUNT))
            val retiredHeader = checkNotNull(harness.stageHeader(database, F.STAGE_NEW))
            assertEquals(0L, retiredHeader.pageCount)
            assertEquals(0L, retiredHeader.recordCount)

            // Invalidation retires the invocation, not the coordinator — but the
            // SAME engine must not silently bypass the retained begin: the next
            // invocation returns retained with no new HTTP...
            val retained = engine.bootstrap(stageId = F.STAGE_OTHER)
            assertTrue(retained.retainedStage)
            assertNull(retained.snapshot)
            assertNull(retained.baseline)
            assertNull(retained.delivery)
            assertEquals(1, transport.capturedRequests.size)

            // ...then an authorized restart with a NEW stage UUID abandons that
            // exact stage and completes fresh.
            val result = engine.bootstrap(stageId = F.STAGE_FRESH, restartRetainedStage = true)
            assertFalse(result.retainedStage)
            assertNotNull(result.baseline)
            assertNotNull(result.delivery)
            assertNull(harness.stageHeader(database, F.STAGE_NEW))
            assertEquals(3, transport.capturedRequests.size)
            F.assertSnapshotInitial(transport.capturedRequests[1])
            F.assertPull(transport.capturedRequests[2], F.BASELINE)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun newestQueuedInvocationWinsOnSameCoordinator() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val transport = PreparedEngineTransport()
            // The retired run consumes one snapshot frame; the winning
            // invocation consumes snapshot then pull. Gate parks call #1 only.
            transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
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
            // Fencing is PER coordinator (one per Room/vault pair): a single
            // engine, transport and owner for both invocations.
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            supervisorScope {
                val first = async { engine.bootstrap(stageId = F.STAGE_NEW) }
                entered.await()

                // The second invocation starts UNDISPATCHED so its opaque newest
                // fence exists before suspension; it then queues on the held slot.
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    engine.bootstrap(stageId = F.STAGE_OTHER)
                }
                gate.complete(Unit)
                try {
                    first.await()
                    fail("expected parked invocation to fail closed")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                }
                // The winning invocation must not silently bypass the retained
                // begin committed by the retired run: retained, no new HTTP.
                val won = second.await()
                assertTrue(won.retainedStage)
                assertNull(won.snapshot)
                assertNull(won.baseline)
                assertNull(won.delivery)
                assertEquals(1, transport.capturedRequests.size)
            }
            // The authorized restart with a NEW stage UUID wins outright on
            // the SAME coordinator.
            val fresh = engine.bootstrap(stageId = F.STAGE_FRESH, restartRetainedStage = true)
            assertFalse(fresh.retainedStage)
            val baseline = checkNotNull(fresh.baseline)
            assertNotNull(fresh.delivery)
            // Durable epoch observed inside the single shared slot, not outside it.
            assertEquals(baseline.cacheEpoch, fresh.cacheEpoch)
            assertNull(harness.stageHeader(database, F.STAGE_NEW))
            assertEquals(3, transport.capturedRequests.size)
            F.assertSnapshotInitial(transport.capturedRequests[1])
            F.assertPull(transport.capturedRequests[2], F.BASELINE)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingFirstRotationUsesExactKeyBodyBeforeSyncTransmission() = runTest {
        val database = harness.openInMemoryDb()
        try {
            // Room reservation plus a vault record carrying an exact pending refresh.
            val authority = RoomSessionAuthority(database)
            val preparing = authority.beginPreparation(authority.read(), F.origin(), F.ACCOUNT, F.OP)
            assertEquals("preparing", harness.journalRow(database)[2])
            val vault = harness.newVault()
            // The pending refresh carries its OWN attempt identity, distinct
            // from the activation operation; the lease binds record.operationId
            // while pending operation/key/body are retained separately.
            val pending = VaultPendingRefresh(
                operationId = F.PENDING_OP,
                attempt = F.pendingAttempt(),
            )
            vault.compareAndSet(
                0L,
                F.preparedRecord(
                    binding = F.binding(),
                    operationId = F.OP,
                    session = F.grant(),
                    pendingRefresh = pending,
                ),
            )
            assertEquals(1L, vault.load().revision)
            assertEquals(F.OP, vault.load().record?.operationId)
            assertEquals(F.PENDING_OP, vault.load().record?.pendingRefresh?.operationId)
            assertEquals(F.REFRESH_KEY, vault.load().record?.pendingRefresh?.attempt?.idempotencyKey)

            val rotated = F.grant(access = F.ACCESS_ROTATED)
            val auth = PreparedEngineAuthApi()
            auth.answer(rotated)
            val transport = PreparedEngineTransport()
            transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
            transport.queueOk(F.EMPTY_PULL_BODY)
            val engine = F.engine(database, vault, auth, transport, F.owner())
            val result = engine.bootstrap(stageId = F.STAGE_NEW)
            assertNotNull(result.baseline)
            assertNotNull(result.delivery)

            // Pending-first: exactly one rotation with the exact stored key/body.
            assertEquals(1, auth.refreshCalls)
            assertEquals(1, auth.seenAttempts.size)
            assertEquals(F.REFRESH_KEY, auth.seenAttempts[0].idempotencyKey)
            assertEquals(F.PENDING_BODY, auth.seenAttempts[0].requestJson)
            // Fresh full-grant CAS before transmission: vault advanced, every
            // sync request bears the rotated access only.
            assertEquals(2L, vault.load().revision)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
            assertEquals(2, transport.capturedRequests.size)
            for (call in transport.capturedRequests) {
                assertEquals(mapOf("Authorization" to "Bearer ${F.ACCESS_ROTATED}"), call.headers)
            }
            assertEquals(preparing.sessionGeneration, harness.journalRow(database)[1]?.toLongOrNull())
        } finally {
            database.close()
        }
    }

    @Test
    fun lateRoomFenceRefusesTailButPreservesEarlierSnapshotCommit() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val transport = PreparedEngineTransport()
            transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
            transport.queueOk(F.EMPTY_PULL_BODY)
            // Park only the pull call: the snapshot chunk commits first.
            var calls = 0
            transport.responseGate = {
                calls += 1
                if (calls == 2) {
                    entered.complete(Unit)
                    gate.await()
                }
            }
            val auth = PreparedEngineAuthApi()
            val engine = F.engine(database, vault, auth, transport, F.owner())
            supervisorScope {
                val job = async { engine.bootstrap(stageId = F.STAGE_NEW) }
                // Fence durable Room while the pull request is midflight.
                entered.await()
                val authority = RoomSessionAuthority(database)
                authority.fencePrivate(authority.read())
                gate.complete(Unit)
                try {
                    job.await()
                    fail("expected late fence to fail closed")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                    F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
                }
            }
            // Earlier snapshot commit survives; tail bookkeeping refused; the
            // fence itself stands (no engine cleanup/repair).
            assertEquals(1L, harness.accountCache(database, F.ACCOUNT)?.baselineComplete)
            val state = harness.syncState(database, F.ACCOUNT)
            assertTrue(state == null || state.second != F.FIXED_CLOCK)
            assertEquals("signed_out", harness.journalRow(database)[2])
            assertEquals(1L, vault.load().revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun ordinaryWritersAndPublisherStillRefusePreparing() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val owner: PushRunOwner = F.owner()
            val store = PushStore(database, RepositorySession(F.ACCOUNT, 1L), owner)
            try {
                store.withRun(0L) { }
                fail("ordinary PushStore must refuse PREPARING")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleSessionException) {
                // Expected: ACTIVE-only admission rejects the private device state.
            }
            // Ordinary standalone capture writers refuse the same private state:
            // no local root may be captured outside an ACTIVE session.
            val captureStore = SyncStore(database, RepositorySession(F.ACCOUNT, 1L), DeterministicUuids())
            try {
                captureStore.withFencedTransaction { transaction ->
                    transaction.captureEdit(
                        AggregateType.WORKOUT,
                        F.OUTBOX_WORKOUT,
                        MutationKind.WORKOUT_START,
                        changed = true,
                        extraRoots = emptyList(),
                    ) {
                        MutationPayload.WorkoutStart(F.OUTBOX_WORKOUT, "bootstrap Local", F.FIXED_CLOCK)
                    }
                }
                fail("ordinary SyncStore must refuse PREPARING")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleSessionException) {
                // Expected: capture fenced to ACTIVE-only sessions.
            }
            // Ordinary standalone canonical writers refuse the same private state.
            try {
                CanonicalStore(database, RepositorySession(F.ACCOUNT, 1L)).withFencedTransaction(0L) {
                    fail("must not enter fenced canonical transaction under PREPARING")
                }
                fail("ordinary CanonicalStore must refuse PREPARING")
            } catch (_: StaleSessionException) {
                // Expected: ACTIVE-only canonical admission.
            }
            // The ordinary publisher acquires its REQUIRED barrier first, then
            // its transactional ACTIVE fence refuses: acquired once, lease
            // closed once, StaleSessionException, domain and cache unchanged.
            // (Only the private PushRun.publishProjection path rejects before
            // the barrier; the coordinator exposes no callback to invoke it,

            var acquired = 0
            val lease = ProjectionTestLease()
            val barrier = projectionGrantBarrier(lease = lease, onAcquire = { acquired += 1 })
            try {
                ProjectionStore(database, RepositorySession(F.ACCOUNT, 1L), barrier).publish(0L)
                fail("ordinary ProjectionStore must refuse PREPARING")
            } catch (_: StaleSessionException) {
                // Expected: ACTIVE-only publication admission.
            }
            assertEquals(1, acquired)
            assertEquals(1, lease.closeCount)
            assertTrue(harness.canonicalRoots(database, F.ACCOUNT).isEmpty())
            assertNull(harness.accountCache(database, F.ACCOUNT))
            assertEquals(0L, harness.countRows(database, "workouts"))
            assertEquals(0L, harness.countRows(database, "routines"))
            assertEquals("preparing", harness.journalRow(database)[2])
            assertEquals(1L, vault.load().revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun callerCancellationPrecedesStaleMapping() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val transport = PreparedEngineTransport()
            transport.queueOk(F.EMPTY_SNAPSHOT_BODY)
            transport.queueOk(F.EMPTY_PULL_BODY)
            transport.responseGate = {
                entered.complete(Unit)
                gate.await()
            }
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            supervisorScope {
                val job = async { engine.bootstrap(stageId = F.STAGE_NEW) }
                entered.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                gate.complete(Unit)
            }
            // Cancellation identity preserved; pair left for fresh recovery.
            assertEquals(1L, vault.load().revision)
        } finally {
            database.close()
        }
    }

}
