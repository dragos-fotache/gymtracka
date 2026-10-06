package com.dragosfotache.gymtracka.data.sync.session

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.r05cGrantBarrier
import com.dragosfotache.gymtracka.data.sync.r05cQuery
import com.dragosfotache.gymtracka.data.sync.R05cTestLease
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStore
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.SyncStore
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R09d independent QA: newest-invocation fencing, cancellation, ordinary-path
 * refusal and closed-error preservation around completed preparation.
 *
 * Derived ONLY from docs/archive/remaining-work/R09d-task.md fence/rollback contract
 * plus the coordinator-supplied completion contract (failure-class mapping,
 * redaction rules, existing failure-name retention) and vetted QA-owned
 * helpers. No production declarations, diffs, Worker work, generated
 * sources, decompiled classes or schema JSON inspected. Unspecified new
 * categories are asserted for closedness/preservation, never a brittle enum.
 */
@RunWith(AndroidJUnit4::class)
class PreparedSessionCompletionFenceTest {

    private val harness = PreparedEngineHarness("completefence")
    private val F = PreparedEngineFixtures
    private val C = PreparedCompletionSupport

    @After
    fun cleanOnlyOwnTestArtifacts() {
        harness.clean()
    }

    @Test
    fun invalidatedInFlightCompletionFailsClosedWithoutSuccess() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val owner = F.owner()
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, owner)
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Park the in-flight completion at barrier acquisition, retire
            // its invocation, then release: the old call must fail closed
            // with no success and no ACTIVE writes.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lease = R05cTestLease()
            val barrier = r05cGrantBarrier(
                lease = lease,
                onAcquire = {
                    entered.complete(Unit)
                    release.await()
                },
            )
            supervisorScope {
                val job = async { engine.completePreparation(barrier) }
                entered.await()
                engine.invalidate()
                release.complete(Unit)
                try {
                    job.await()
                    fail("expected retired completion to fail closed")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                    C.assertClosedCompletion(e)
                }
            }
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)

            // A new invocation on a fresh engine still completes exactly once.
            val freshTransport = PreparedEngineTransport()
            C.queueCompletionEmptyTail(freshTransport)
            val fresh = F.engine(database, vault, PreparedEngineAuthApi(), freshTransport, owner)
            val result = fresh.completePreparation(r05cGrantBarrier(lease = R05cTestLease()))
            assertEquals(PreparedCompletionStatus.COMPLETED, result.status)
            C.assertRoomActive(harness, database)
            C.assertVaultActiveFor(vault, 2L)
            fresh.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun revokedLeaseRollsBackDomainCacheJournalDeviceTogether() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapNonempty(transport)
            C.queueCompletionTailAtTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val cacheBefore = harness.accountCache(database, F.ACCOUNT)
            val rootsBefore = harness.canonicalRoots(database, F.ACCOUNT)
            assertEquals(2, rootsBefore.size)
            // Revoke the publication lease while the completion tail is in
            // flight (pre-publication): completion must refuse with the pair
            // preserved. Combined post-write rollback is proven separately by
            // the trigger/cancellation cases below.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // Gate counts only the completion tail: it is installed after
            // bootstrap consumed its three frames.
            var calls = 0
            transport.responseGate = {
                calls += 1
                if (calls == 1) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val lease = R05cTestLease()
            // An explicitly invalid lease rolls back with either a closed
            // error or a NOT_READY refusal: never a completion claim.
            var completed = false
            supervisorScope {
                val job = async {
                    engine.completePreparation(r05cGrantBarrier(lease = lease))
                }
                entered.await()
                lease.revoke()
                release.complete(Unit)
                try {
                    val outcome = job.await()
                    C.assertRedactedCompletion(outcome.toString())
                    assertEquals(PreparedCompletionStatus.NOT_READY, outcome.status)
                } catch (e: PreparedEngineException) {
                    C.assertClosedCompletion(e)
                }
                completed = try {
                    job.await().status == PreparedCompletionStatus.COMPLETED
                } catch (_: PreparedEngineException) {
                    false
                }
            }
            assertTrue("revoked lease must never complete", !completed)
            // Atomic rollback: domain untouched, cache/ledger kept,
            // journal/device still PREPARING, vault still PREPARED.
            assertTrue(
                database.r05cQuery("SELECT id FROM routines").isEmpty(),
            )
            assertEquals(cacheBefore, harness.accountCache(database, F.ACCOUNT))
            assertEquals(rootsBefore, harness.canonicalRoots(database, F.ACCOUNT))
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun journalRevisionOverflowRefusesAfterTailWithoutPublication() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Exhaust the checked journal counter: the normative overflow
            // check runs post-actual-tail but BEFORE barrier/publication/Room
            // completion, so the fresh tail pull happens yet nothing is
            // published, activated or promoted.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_session_authority SET revision = 9223372036854775807 WHERE id = 1",
            )
            val cacheBefore = harness.accountCache(database, F.ACCOUNT)
            val barrier = r05cGrantBarrier(lease = R05cTestLease())
            try {
                engine.completePreparation(barrier)
                fail("expected exhausted counter to fail closed")
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            // Actual fresh tail ran (bootstrap 2 + completion tail 1) with no
            // barrier acquisition, publication, ACTIVE writes or promotion.
            assertEquals(3, transport.capturedRequests.size)
            F.assertPull(transport.capturedRequests[2], F.BASELINE)
            assertTrue(barrier.requests.isEmpty())
            assertEquals(cacheBefore, harness.accountCache(database, F.ACCOUNT))
            assertEquals("9223372036854775807", harness.journalRow(database)[0])
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun newerFencePrivateIntentStalesCompletionAndPreservesState() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Gate the completion IN FLIGHT at barrier acquisition, then land
            // the newer fenced intent: an in-flight durable change retires
            // the invocation (a pre-invocation fence would simply be
            // non-PREPARING NOT_READY instead).
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val barrier = r05cGrantBarrier(
                lease = R05cTestLease(),
                onAcquire = {
                    entered.complete(Unit)
                    release.await()
                },
            )
            supervisorScope {
                val job = async { engine.completePreparation(barrier) }
                entered.await()
                val authority = RoomSessionAuthority(database)
                authority.fencePrivate(authority.read())
                assertEquals("signed_out", harness.journalRow(database)[2])
                release.complete(Unit)
                try {
                    job.await()
                    fail("expected fenced completion to fail closed")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                    C.assertClosedCompletion(e)
                }
            }
            // The fence stands unmodified by the failed completion; the
            // prepared vault grant is preserved, never cleaned up.
            assertEquals("signed_out", harness.journalRow(database)[2])
            C.assertVaultPreparedRev1(vault)
            // Fresh genuinely-signed-out recovery stays NOT_READY, never promotes.
            val recovery = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.NOT_READY, recovery.status)
            assertEquals("signed_out", harness.journalRow(database)[2])
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun concurrentCompletionsPromoteExactlyOnce() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Contested shared owner: the first call parks inside barrier
            // acquisition while holding the owner; the second (UNDISPATCHED,
            // so it runs to the owner wait deterministically) retires the
            // first fence but cannot enter the barrier. Release proves the
            // old call goes stale and the second alone completes.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var acquired = 0
            var activeLeases = 0
            var maxActiveLeases = 0
            // Counting barrier: first acquisition parks while holding the
            // shared owner; simultaneous lease holders are counted so the
            // test proves leases never overlap.
            val overlapBarrier = ProjectionPublicationBarrier {
                acquired += 1
                activeLeases += 1
                maxActiveLeases = maxOf(maxActiveLeases, activeLeases)
                if (acquired == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                object : ProjectionPublicationLease {
                    override fun checkValid() {}
                    override fun close() {
                        activeLeases -= 1
                    }
                }
            }
            supervisorScope {
                val first = async { engine.completePreparation(overlapBarrier) }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    engine.completePreparation(overlapBarrier)
                }
                testScheduler.runCurrent()
                // Second retired the first fence yet waits on the shared
                // owner: it must not enter the barrier while first holds it.
                assertEquals(1, acquired)
                release.complete(Unit)
                try {
                    first.await()
                    fail("retired first call must never complete")
                } catch (e: PreparedEngineException) {
                    assertEquals(PreparedEngineFailure.STALE_OPERATION, e.failure)
                    C.assertClosedCompletion(e)
                }
                val result = second.await()
                assertEquals(PreparedCompletionStatus.COMPLETED, result.status)
                C.assertRedactedCompletion(result.toString())
                // Second entered only after the first released: requests grow
                // to two after release, leases never overlap.
                assertEquals(2, acquired)
                assertEquals(1, maxActiveLeases)
            }
            // Exactly one Room revision and one vault promotion, never a
            // duplicate, overwrite or cleanup of the winner.
            assertEquals("2", harness.journalRow(database)[0])
            assertEquals(2L, vault.load().revision)
            assertEquals(VaultSessionPhase.ACTIVE, vault.load().record?.phase)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidatedUncertainCommitNeverCompletesStaleAndRecoverySucceeds() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val realVault = harness.newVault()
            harness.reservePreparedPair(database, realVault)
            val hooked = HookSessionVault(realVault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, hooked, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Park AFTER the real vault CAS committed but BEFORE the result
            // returns: the commit is uncertain to the caller.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var fired = false
            hooked.afterCommitHook = {
                if (!fired) {
                    fired = true
                    entered.complete(Unit)
                    release.await()
                }
            }
            supervisorScope {
                val job = async {
                    engine.completePreparation(r05cGrantBarrier(lease = R05cTestLease()))
                }
                entered.await()
                engine.invalidate()
                release.complete(Unit)
                try {
                    job.await()
                    fail("retired uncertain commit must never report COMPLETED")
                } catch (e: PreparedEngineException) {
                    C.assertClosedCompletion(e)
                }
            }
            assertTrue("promotion CAS must have committed", fired)
            assertEquals(1, hooked.casCalls)
            // No compensating cleanup: the ACTIVE vault and ACTIVE Room
            // persist exactly as committed.
            C.assertRoomActive(harness, database)
            assertEquals(2L, realVault.load().revision)
            assertEquals(VaultSessionPhase.ACTIVE, realVault.load().record?.phase)
            // Fresh recovery rereads the stable pair with no new CAS/HTTP.
            val freshTransport = PreparedEngineTransport()
            val fresh = F.engine(database, hooked, PreparedEngineAuthApi(), freshTransport, F.owner())
            val recovered = fresh.recoverCompletion()
            assertEquals(PreparedCompletionStatus.COMPLETED, recovered.status)
            assertTrue(freshTransport.capturedRequests.isEmpty())
            assertEquals(1, hooked.casCalls)
            assertEquals(2L, realVault.load().revision)
            C.assertRoomActive(harness, database)
            fresh.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun cancelledUncertainCommitKeepsPairForFreshRecovery() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val realVault = harness.newVault()
            harness.reservePreparedPair(database, realVault)
            val hooked = HookSessionVault(realVault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, hooked, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var fired = false
            hooked.afterCommitHook = {
                if (!fired) {
                    fired = true
                    entered.complete(Unit)
                    release.await()
                }
            }
            // Cancellation identity is asserted inside the actual caller,
            // never across the await stack boundary.
            var callerCancelled = false
            supervisorScope {
                val job = async {
                    try {
                        engine.completePreparation(r05cGrantBarrier(lease = R05cTestLease()))
                    } catch (e: CancellationException) {
                        callerCancelled = true
                        throw e
                    }
                }
                entered.await()
                job.cancelAndJoin()
                assertTrue("caller must observe its own cancellation", callerCancelled)
                assertTrue(job.isCancelled)
                release.complete(Unit)
            }
            assertTrue("promotion CAS must have committed", fired)
            // Uncertain commit kept, never compensated.
            C.assertRoomActive(harness, database)
            assertEquals(2L, realVault.load().revision)
            val freshTransport = PreparedEngineTransport()
            val fresh = F.engine(database, hooked, PreparedEngineAuthApi(), freshTransport, F.owner())
            val recovered = fresh.recoverCompletion()
            assertEquals(PreparedCompletionStatus.COMPLETED, recovered.status)
            assertTrue(freshTransport.capturedRequests.isEmpty())
            assertEquals(1, hooked.casCalls)
            fresh.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun preCommitCancellationAfterObservedWriteRollsBackEverything() = runTest {
        val fired = AtomicBoolean(false)
        val deviceWriteSeen = AtomicBoolean(false)
        val targetSeen = AtomicBoolean(false)
        val armed = AtomicBoolean(false)
        lateinit var runnerJob: Job
        // Vetted query-callback strategy: callbacks fire BEFORE their SQL,
        // so the caller Job is cancelled on the query FOLLOWING the targeted
        // completion write. Arming happens at barrier onAcquire (after the
        // tail), and the targeted hook is the completion UPDATE of
        // sync_device_state: with a nonempty graph, all graph/cache/journal
        // writes precede the ready-device write, so cancelling on the next
        // strict-read query proves AFTER-combined-writes cancellation.
        val database = Room.inMemoryDatabaseBuilder(
            harness.context.applicationContext,
            GymDatabase::class.java,
        ).setQueryCallback(
            RoomDatabase.QueryCallback { sql, _ ->
                if (armed.get()) {
                    val lower = sql.lowercase()
                    val isDeviceUpdate = lower.contains("update") &&
                        lower.contains("sync_device_state")
                    if (!deviceWriteSeen.get() && isDeviceUpdate) {
                        deviceWriteSeen.set(true)
                        targetSeen.set(true)
                    } else if (deviceWriteSeen.getAndSet(false)) {
                        fired.set(true)
                        armed.set(false)
                        runnerJob.cancel(CancellationException("r09d post-write cancel"))
                    }
                }
            },
            Executor { command -> command.run() },
        ).build()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapNonempty(transport)
            C.queueCompletionTailAtTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)
            val cacheBefore = harness.accountCache(database, F.ACCOUNT)
            val rootsBefore = harness.canonicalRoots(database, F.ACCOUNT)
            val tailBefore = harness.syncState(database, F.ACCOUNT)
            assertEquals(2, rootsBefore.size)
            var callerCancelled = false
            supervisorScope {
                val child = async {
                    try {
                        // Arming at barrier acquisition: the tail is done, so
                        // the next device UPDATE is the completion write.
                        val barrier = r05cGrantBarrier(
                            lease = R05cTestLease(),
                            onAcquire = { armed.set(true) },
                        )
                        engine.completePreparation(barrier)
                        fail("mid-write cancellation must propagate, never a commit")
                    } catch (e: CancellationException) {
                        callerCancelled = true
                        throw e
                    }
                }
                runnerJob = child
                try {
                    child.await()
                } catch (_: CancellationException) {
                } finally {
                    armed.set(false)
                }
            }
            assertTrue("targeted device UPDATE hook must have fired", targetSeen.get())
            assertTrue("cancel must land on the query after the device write", fired.get())
            assertTrue("caller must observe its own cancellation", callerCancelled)
            assertTrue(runnerJob.isCancelled)
            // Complete pre-commit rollback: published graph gone, every
            // store kept, tail kept, vault never promoted.
            assertTrue(database.r05cQuery("SELECT id FROM routines").isEmpty())
            assertEquals(cacheBefore, harness.accountCache(database, F.ACCOUNT))
            assertEquals(rootsBefore, harness.canonicalRoots(database, F.ACCOUNT))
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))
            assertEquals(tailBefore, harness.syncState(database, F.ACCOUNT))
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun barrierAcquireFailureWritesNothingAndPromotesNothing() = runTest {
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
            val cacheBefore = harness.accountCache(database, F.ACCOUNT)
            val failing = ProjectionPublicationBarrier { throw RuntimeException("r09d-acquire-fault") }
            try {
                engine.completePreparation(failing)
                fail("expected barrier failure to fail closed")
            } catch (e: PreparedEngineException) {
                // Operational barrier failure is an authority outage, never a
                // readiness NOT_READY (vetted corrupt-stage precedent).
                assertEquals(PreparedEngineFailure.AUTHORITY_UNAVAILABLE, e.failure)
                C.assertClosedCompletion(e)
            }
            // No vault promotion follows a failed publication return; Room kept.
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))
            assertEquals(cacheBefore, harness.accountCache(database, F.ACCOUNT))
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun corruptJournalTypeRefusesClosedAndPreservesState() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Corrupt storage type: revision must be an integer journal counter.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_session_authority SET revision = 'r09d-not-a-number' WHERE id = 1",
            )
            val callsBefore = transport.capturedRequests.size
            try {
                engine.completePreparation(r05cGrantBarrier())
                fail("expected corrupt authority to fail closed")
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            // Corrupt state is preserved, never repaired or promoted through.
            assertEquals(0L, harness.countRows(database, "workouts"))
            C.assertVaultPreparedRev1(vault)
            assertEquals(callsBefore, transport.capturedRequests.size)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun validButWrongDeviceTupleRefusesClosedWithoutPromotion() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Valid-type wrong tuple: device claims ready while journal prepares.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_device_state SET cache_state = 'ready' WHERE id = 1",
            )
            try {
                engine.completePreparation(r05cGrantBarrier())
                fail("expected mismatched tuple to fail closed")
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            assertEquals("ready", harness.deviceRow(database)[3])
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun ordinaryWritersStillRefuseAfterBootstrap() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Completion opens no ordinary path: capture/canonical/push stay
            // ACTIVE-only even with a complete baseline present.
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
                        MutationPayload.WorkoutStart(F.OUTBOX_WORKOUT, "R09d Local", F.FIXED_CLOCK)
                    }
                }
                fail("ordinary SyncStore must refuse PREPARING")
            } catch (_: StaleSessionException) {
            }
            try {
                CanonicalStore(database, RepositorySession(F.ACCOUNT, 1L)).withFencedTransaction(0L) {
                    fail("must not enter fenced canonical transaction under PREPARING")
                }
                fail("ordinary CanonicalStore must refuse PREPARING")
            } catch (_: StaleSessionException) {
            }
            try {
                PushStore(database, RepositorySession(F.ACCOUNT, 1L), F.owner()).withRun(0L) { }
                fail("ordinary PushStore must refuse PREPARING")
            } catch (_: StaleSessionException) {
            }
            var acquired = 0
            val lease = R05cTestLease()
            val barrier = r05cGrantBarrier(lease = lease, onAcquire = { acquired += 1 })
            try {
                ProjectionStore(database, RepositorySession(F.ACCOUNT, 1L), barrier).publish(0L)
                fail("ordinary ProjectionStore must refuse PREPARING")
            } catch (_: StaleSessionException) {
            }
            assertEquals(1, acquired)
            assertEquals(1, lease.closeCount)
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun cancelledCompletionDeliversNoSuccessAndRecoveryStaysNotReady() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            transport.responseGate = {
                calls += 1
                if (calls == 3) {
                    entered.complete(Unit)
                    gate.await()
                }
            }
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            supervisorScope {
                val job = async { engine.completePreparation(r05cGrantBarrier(lease = R05cTestLease())) }
                entered.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                gate.complete(Unit)
            }
            // Cancellation has caller precedence: no stale success, pair kept
            // for fresh recovery rather than compensation.
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            val recovery = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.NOT_READY, recovery.status)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun journalTriggerFaultAfterGraphWriteRollsBackEverything() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapNonempty(transport)
            C.queueCompletionTailAtTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)
            val cacheBefore = harness.accountCache(database, F.ACCOUNT)
            val rootsBefore = harness.canonicalRoots(database, F.ACCOUNT)
            val tailBefore = harness.syncState(database, F.ACCOUNT)
            assertEquals(2, rootsBefore.size)
            // AFTER graph-write fault: the activation journal write itself
            // aborts, so the already-materialized domain rows must roll back
            // with cache/journal/device together. Bootstrap never writes an
            // active phase, so the trigger is inert until completion.
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER r09d_no_active AFTER UPDATE ON sync_session_authority" +
                    " WHEN NEW.phase = 'active'" +
                    " BEGIN SELECT RAISE(ABORT, 'r09d-journal-fault'); END",
            )
            try {
                engine.completePreparation(r05cGrantBarrier(lease = R05cTestLease()))
                fail("expected journal trigger fault to fail closed")
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            // Full post-write rollback: the nonempty domain graph is gone,
            // cache/ledger/journal/device/tail kept, vault unpromoted.
            assertTrue(database.r05cQuery("SELECT id FROM routines").isEmpty())
            assertEquals(cacheBefore, harness.accountCache(database, F.ACCOUNT))
            assertEquals(rootsBefore, harness.canonicalRoots(database, F.ACCOUNT))
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))
            assertEquals(tailBefore, harness.syncState(database, F.ACCOUNT))
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun deviceCountZeroAfterGraphWriteRollsBackEverything() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapNonempty(transport)
            C.queueCompletionTailAtTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)
            val cacheBefore = harness.accountCache(database, F.ACCOUNT)
            val rootsBefore = harness.canonicalRoots(database, F.ACCOUNT)
            val tailBefore = harness.syncState(database, F.ACCOUNT)
            // Representative affected-count-zero AFTER earlier graph/journal
            // writes: the ready device write is silently skipped, so the
            // exact whole-row predicate fails and the Room transaction rolls
            // back. Only this trigger differs from the passing happy path,
            // so its refusal proves the hook engaged.
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER r09d_ignore_ready BEFORE UPDATE ON sync_device_state" +
                    " WHEN NEW.cache_state=\"ready\"" +
                    " BEGIN SELECT RAISE(IGNORE); END",
            )
            assertTrue(
                database.r05cQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'trigger'" +
                        " AND name = 'r09d_ignore_ready'",
                ).isNotEmpty(),
            )
            try {
                engine.completePreparation(r05cGrantBarrier(lease = R05cTestLease()))
                fail("expected count-zero device write to fail closed")
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            // Exact whole-state rollback: published graph gone, every store kept.
            assertTrue(database.r05cQuery("SELECT id FROM routines").isEmpty())
            assertEquals(cacheBefore, harness.accountCache(database, F.ACCOUNT))
            assertEquals(rootsBefore, harness.canonicalRoots(database, F.ACCOUNT))
            assertEquals(journalBefore, harness.journalRow(database))
            assertEquals(deviceBefore, harness.deviceRow(database))
            assertEquals(tailBefore, harness.syncState(database, F.ACCOUNT))
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun deviceTupleRewriteAfterWriteRollsBackEverything() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapNonempty(transport)
            C.queueCompletionTailAtTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val journalBefore = harness.journalRow(database)
            val deviceBefore = harness.deviceRow(database)
            val cacheBefore = harness.accountCache(database, F.ACCOUNT)
            val rootsBefore = harness.canonicalRoots(database, F.ACCOUNT)
            // Distinct post-write full-tuple risk: a trigger rewrites the
            // device row to a valid but wrong cached/generation/ready tuple
            // after the completion wrote it, so post-write verification
            // fails and the whole transaction rolls back.
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER r09d_wrong_device AFTER UPDATE ON sync_device_state" +
                    " BEGIN UPDATE sync_device_state" +
                    " SET cached_account_id = '00000000-0000-4000-8000-000000000000'," +
                    " session_generation = 99 WHERE id = 1; END",
            )
            try {
                engine.completePreparation(r05cGrantBarrier(lease = R05cTestLease()))
                fail("expected rewritten device tuple to fail closed")
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            assertTrue(database.r05cQuery("SELECT id FROM routines").isEmpty())
            assertEquals(cacheBefore, harness.accountCache(database, F.ACCOUNT))
            assertEquals(rootsBefore, harness.canonicalRoots(database, F.ACCOUNT))
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
    fun outboxChangeAfterCasPreventsCompletedDelivery() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val realVault = harness.newVault()
            harness.reservePreparedPair(database, realVault)
            val hooked = HookSessionVault(realVault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, hooked, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // New intent lands AFTER the promotion CAS commits: the final
            // alternating rereads must reject it even though identity is
            // unchanged. The commit stands (never compensated); only the
            // COMPLETED claim is refused.
            var fired = false
            hooked.afterCommitHook = {
                if (!fired) {
                    fired = true
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
                }
            }
            var completed = false
            try {
                val outcome =
                    engine.completePreparation(r05cGrantBarrier(lease = R05cTestLease()))
                C.assertRedactedCompletion(outcome.toString())
                completed = outcome.status == PreparedCompletionStatus.COMPLETED
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            assertTrue("post-CAS outbox change must prevent COMPLETED", !completed)
            assertTrue("promotion CAS must have committed", fired)
            assertEquals(1, hooked.casCalls)
            // Commit stood, claim refused: ACTIVE pair kept, intent kept.
            C.assertRoomActive(harness, database)
            assertEquals(2L, realVault.load().revision)
            assertEquals(VaultSessionPhase.ACTIVE, realVault.load().record?.phase)
            assertEquals(1, harness.outboxSnapshot(database, F.ACCOUNT).size)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun redactedDisplaysCarryNoSecrets() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val completed = engine.completePreparation(r05cGrantBarrier(lease = R05cTestLease()))
            assertEquals(PreparedCompletionStatus.COMPLETED, completed.status)
            C.assertRedactedCompletion(completed.toString())
            // Closed validation errors are fixed-safe even after completion.
            try {
                engine.completePreparation(r05cGrantBarrier(), maxPushMutations = 0)
                fail("expected INVALID_INPUT")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.INVALID_INPUT, e.failure)
                C.assertClosedCompletion(e)
            }
            engine.invalidate()
        } finally {
            database.close()
        }
    }
}
