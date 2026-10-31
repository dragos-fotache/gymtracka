package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.projectionGrantBarrier
import com.dragosfotache.gymtracka.data.sync.projectionQuery
import com.dragosfotache.gymtracka.data.sync.ProjectionTestLease
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
 * Checks durable recovery of the Room-committed, vault-unpromoted gap and refusal of incompatible
 * completion evidence.
 */
@RunWith(AndroidJUnit4::class)
class PreparedSessionCompletionRecoveryTest {

    private val harness = PreparedEngineHarness("completerec")
    private val F = PreparedEngineFixtures
    private val C = PreparedCompletionSupport

    @After
    fun cleanOnlyOwnTestArtifacts() {
        harness.clean()
    }

    @Test
    fun leaseCloseFailureGapRecoversExactPairAfterFileReopen() = runTest {
        val dbName = "completion-complete-gap.db"
        val database = harness.createFileDb(dbName)
        val auth = PreparedEngineAuthApi()
        val transport = PreparedEngineTransport()
        C.queueBootstrapEmpty(transport)
        C.queueCompletionEmptyTail(transport)
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val engine = F.engine(database, vault, auth, transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val throwing = ThrowingCloseLease()
            val barrier = projectionGrantBarrier(lease = throwing)
            // Normative order: lease close precedes the known Room return
            // and promotion, so the fault MUST surface and leave the exact
            // Room-ACTIVE / vault-PREPARED gap: never silent success.
            val thrown: Throwable? = try {
                engine.completePreparation(barrier)
                null
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
                e
            } catch (t: RuntimeException) {
                t
            }
            assertNotNull("throwing lease.close must fail the completion", thrown)
            assertEquals(1, throwing.closeCount)
            // Room committed exactly once; it must NOT have rolled back, and
            // the vault must NOT have promoted: exact revision-1 gap.
            C.assertRoomActive(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }

        // File Room reopen + actual vault reopen: the gap is durable.
        val reopened = harness.reopenFileDb(dbName)
        try {
            val vault = harness.reopenVault()
            C.assertRoomActive(harness, reopened)
            assertEquals(1L, vault.load().revision)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
            val callsBefore = transport.capturedRequests.size
            val refreshesBefore = auth.refreshCalls
            val recoveryEngine = F.engine(reopened, vault, auth, transport, F.owner())
            val recovered = recoveryEngine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.COMPLETED, recovered.status)
            C.assertRedactedCompletion(recovered.toString())
            // Recovery performs no HTTP/refresh/publication/domain writes.
            assertEquals(callsBefore, transport.capturedRequests.size)
            assertEquals(refreshesBefore, auth.refreshCalls)
            C.assertRoomActive(harness, reopened)
            C.assertVaultActiveFor(vault, 2L)
            assertEquals(checkNotNull(harness.accountCache(reopened, F.ACCOUNT)).cacheEpoch, recovered.cacheEpoch)

            // Idempotence: repeat recovery adds no revision.
            val again = recoveryEngine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.COMPLETED, again.status)
            assertEquals(2L, vault.load().revision)
            assertEquals(callsBefore, transport.capturedRequests.size)
            C.assertRoomActive(harness, reopened)
            recoveryEngine.invalidate()
        } finally {
            reopened.close()
        }
    }

    @Test
    fun recoveryAfterCleanCompletionIsIdempotentWithoutWrites() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val auth = PreparedEngineAuthApi()
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, auth, transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            val completed = engine.completePreparation(projectionGrantBarrier(lease = ProjectionTestLease()))
            assertEquals(PreparedCompletionStatus.COMPLETED, completed.status)
            C.assertVaultActiveFor(vault, 2L)
            val callsBefore = transport.capturedRequests.size

            val recovered = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.COMPLETED, recovered.status)
            assertEquals(completed.cacheEpoch, recovered.cacheEpoch)
            C.assertRedactedCompletion(recovered.toString())
            // Read-only idempotence: no extra vault revision, no HTTP.
            assertEquals(2L, vault.load().revision)
            assertEquals(callsBefore, transport.capturedRequests.size)
            C.assertRoomActive(harness, database)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun activeWithMissingCheckpointRefusesUnchanged() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            assertEquals(
                PreparedCompletionStatus.COMPLETED,
                engine.completePreparation(projectionGrantBarrier(lease = ProjectionTestLease())).status,
            )
            C.assertVaultActiveFor(vault, 2L)
            // Break the checkpoint: recovery must refuse without repair.
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM sync_state WHERE account_id = ?",
                arrayOf<Any?>(F.ACCOUNT),
            )
            assertNull(harness.syncState(database, F.ACCOUNT))
            val callsBefore = transport.capturedRequests.size
            val refused = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.NOT_READY, refused.status)
            C.assertRedactedCompletion(refused.toString())
            assertEquals(callsBefore, transport.capturedRequests.size)
            assertNull(harness.syncState(database, F.ACCOUNT))
            C.assertRoomActive(harness, database)
            C.assertVaultActiveFor(vault, 2L)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun activeWithIncompleteDirtyCacheRefusesUnchanged() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            assertEquals(
                PreparedCompletionStatus.COMPLETED,
                engine.completePreparation(projectionGrantBarrier(lease = ProjectionTestLease())).status,
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sync_account_cache SET baseline_complete = 0, projection_dirty = 1," +
                    " materialized_epoch = 0 WHERE account_id = ?",
                arrayOf<Any?>(F.ACCOUNT),
            )
            val refused = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.NOT_READY, refused.status)
            // Corrupt cache preserved, never reconstructed or promoted through.
            val cache = checkNotNull(harness.accountCache(database, F.ACCOUNT))
            assertEquals(0L, cache.baselineComplete)
            assertEquals(1L, cache.projectionDirty)
            C.assertRoomActive(harness, database)
            C.assertVaultActiveFor(vault, 2L)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun activeWithMissingVaultRefusesUnchanged() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            assertEquals(
                PreparedCompletionStatus.COMPLETED,
                engine.completePreparation(projectionGrantBarrier(lease = ProjectionTestLease())).status,
            )
            // Simulate vault loss: fresh empty vault for the same Room.
            harness.clean()
            val lost = harness.reopenVault()
            assertEquals(0L, lost.load().revision)
            assertNull(lost.load().record)
            val lostEngine = F.engine(database, lost, PreparedEngineAuthApi(), transport, F.owner())
            val refused = lostEngine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.NOT_READY, refused.status)
            C.assertRedactedCompletion(refused.toString())
            assertEquals(0L, lost.load().revision)
            C.assertRoomActive(harness, database)
            lostEngine.invalidate()
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun activeWithMismatchedVaultRefusesUnchanged() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            assertEquals(
                PreparedCompletionStatus.COMPLETED,
                engine.completePreparation(projectionGrantBarrier(lease = ProjectionTestLease())).status,
            )
            // Orphan credential: a VALID record (binding and session account
            // agree) for an unrelated account. Recovery against Room ACTIVE
            // fails closed IDENTITY_MISMATCH; nothing is cleaned or rebuilt.
            val active = checkNotNull(vault.load().record)
            val orphan = active.copy(
                binding = F.binding(accountId = F.OTHER_ACCOUNT),
                session = F.grant(accountId = F.OTHER_ACCOUNT),
            )
            assertEquals(3L, vault.compareAndSet(2L, orphan).revision)
            try {
                engine.recoverCompletion()
                fail("expected binding mismatch to fail closed")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.IDENTITY_MISMATCH, e.failure)
                C.assertClosedCompletion(e)
            }
            assertEquals(3L, vault.load().revision)
            assertEquals(F.OTHER_ACCOUNT, vault.load().record?.binding?.accountId)
            C.assertRoomActive(harness, database)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun expiredGrantAndPendingWhitespacePreservedAcrossRecovery() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val auth = PreparedEngineAuthApi()
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = F.engine(database, vault, auth, transport, F.owner())
            assertNotNull(engine.bootstrap(stageId = F.STAGE_NEW).baseline)
            // Legitimate Room-ACTIVE / vault-PREPARED gap first: the throwing
            // lease.close fails the completion after Room committed.
            val throwing = ThrowingCloseLease()
            try {
                engine.completePreparation(projectionGrantBarrier(lease = throwing))
                fail("expected gap-producing close fault")
            } catch (_: PreparedEngineException) {
            } catch (_: RuntimeException) {
            }
            C.assertRoomActive(harness, database)
            C.assertVaultPreparedRev1(vault)
            // Age the unpromoted PREPARED record out of band: expired grant
            // plus whitespace-exact pending bytes at rev1 -> rev2. Recovery
            // must phase-promote THIS record verbatim, never refresh it.
            val whitespaceBody = "{\n  \"refreshToken\": \"bootstrap-refresh-001\"\n}"
            val gapRecord = checkNotNull(vault.load().record)
            val aged = gapRecord.copy(
                session = F.grant(accessExpires = F.FIXED_CLOCK - 60_000L),
                pendingRefresh = VaultPendingRefresh(
                    operationId = F.PENDING_OP,
                    attempt = F.pendingAttempt(body = whitespaceBody),
                ),
            )
            assertEquals(VaultSessionPhase.PREPARED, aged.phase)
            assertEquals(2L, vault.compareAndSet(1L, aged).revision)
            val callsBefore = transport.capturedRequests.size
            val recovered = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.COMPLETED, recovered.status)
            C.assertRedactedCompletion(recovered.toString())
            // Phase-only promotion: exactly one revision, full expired grant
            // plus exact pending bytes copied, no HTTP/refresh performed.
            assertEquals(3L, vault.load().revision)
            assertEquals(VaultSessionPhase.ACTIVE, vault.load().record?.phase)
            assertEquals(callsBefore, transport.capturedRequests.size)
            assertEquals(0, auth.refreshCalls)
            // Exact full grant: operation, binding, session bytes.
            assertEquals(F.OP, vault.load().record?.operationId)
            assertEquals(F.ACCOUNT, vault.load().record?.binding?.accountId)
            assertEquals(
                F.grant(accessExpires = F.FIXED_CLOCK - 60_000L),
                vault.load().record?.session,
            )
            // Exact pending identity: operation, key, whitespace-verbatim
            // body and token.
            assertEquals(F.PENDING_OP, vault.load().record?.pendingRefresh?.operationId)
            assertEquals(F.REFRESH_KEY, vault.load().record?.pendingRefresh?.attempt?.idempotencyKey)
            assertEquals(
                whitespaceBody,
                vault.load().record?.pendingRefresh?.attempt?.requestJson,
            )
            assertEquals(F.REFRESH, vault.load().record?.pendingRefresh?.attempt?.refreshToken?.value)
            // Empty-graph materialization stayed clean and domain-empty.
            assertTrue(database.projectionQuery("SELECT id FROM routines").isEmpty())
            val cache = checkNotNull(harness.accountCache(database, F.ACCOUNT))
            assertEquals(1L, cache.baselineComplete)
            assertEquals(0L, cache.projectionDirty)
            assertEquals(cache.cacheEpoch, cache.materializedEpoch)
            C.assertRoomActive(harness, database)
            // Repeated recovery adds no extra CAS/revision.
            val again = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.COMPLETED, again.status)
            assertEquals(3L, vault.load().revision)
            assertEquals(callsBefore, transport.capturedRequests.size)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun roomChangeDuringVaultLoadPreventsCasAndCompletion() = runTest {
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
            // Legitimate gap first: Room ACTIVE, vault still PREPARED.
            val throwing = ThrowingCloseLease()
            try {
                engine.completePreparation(projectionGrantBarrier(lease = throwing))
                org.junit.Assert.fail("expected gap-producing close fault")
            } catch (_: PreparedEngineException) {
            } catch (_: RuntimeException) {
            }
            C.assertRoomActive(harness, database)
            C.assertVaultPreparedRev1(realVault)
            // Now a Room rewrite lands DURING a recovery vault load: no new
            // CAS may begin and no COMPLETED may be delivered.
            var fired = false
            hooked.loadHook = {
                if (!fired) {
                    fired = true
                    database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM sync_state WHERE account_id = ?",
                        arrayOf<Any?>(F.ACCOUNT),
                    )
                }
            }
            val casBefore = hooked.casCalls
            var completed = false
            try {
                val outcome = engine.recoverCompletion()
                C.assertRedactedCompletion(outcome.toString())
                completed = outcome.status == PreparedCompletionStatus.COMPLETED
            } catch (e: PreparedEngineException) {
                C.assertClosedCompletion(e)
            }
            assertTrue("mid-load Room change must be observed", fired)
            assertFalse("mid-load Room change must prevent COMPLETED", completed)
            assertEquals("no new CAS may begin after the change", casBefore, hooked.casCalls)
            // Gap preserved exactly: ACTIVE Room kept, PREPARED vault kept.
            C.assertRoomActive(harness, database)
            C.assertVaultPreparedRev1(realVault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun nonActiveRoomReturnsNotReadyWithoutVaultPromotion() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val auth = PreparedEngineAuthApi()
            val transport = PreparedEngineTransport()
            val engine = F.engine(database, vault, auth, transport, F.owner())
            val result = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            C.assertRedactedCompletion(result.toString())
            // No vault promotion, no HTTP, no refresh, Room untouched.
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(0, auth.refreshCalls)
            C.assertRoomPreparingRev1(harness, database)
            C.assertVaultPreparedRev1(vault)
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun preparingWithActiveVaultNeverAuthorizesCompletion() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            // Legitimate vault-state setup (not completion evidence): promote
            // the vault record out of band so Room/PREPARING meets vault/ACTIVE.
            val prepared = checkNotNull(vault.load().record)
            val mismatch = prepared.copy(phase = VaultSessionPhase.ACTIVE)
            assertEquals(2L, vault.compareAndSet(1L, mismatch).revision)
            val transport = PreparedEngineTransport()
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            val result = engine.recoverCompletion()
            assertEquals(PreparedCompletionStatus.NOT_READY, result.status)
            C.assertRedactedCompletion(result.toString())
            // Refused unchanged: no cleanup, no reconstruction, no HTTP.
            assertTrue(transport.capturedRequests.isEmpty())
            C.assertRoomPreparingRev1(harness, database)
            assertEquals(2L, vault.load().revision)
            assertEquals(VaultSessionPhase.ACTIVE, vault.load().record?.phase)
            engine.invalidate()
        } finally {
            database.close()
        }
    }
}
