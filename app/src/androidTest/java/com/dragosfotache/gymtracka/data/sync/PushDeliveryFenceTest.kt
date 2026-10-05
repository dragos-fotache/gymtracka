package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R06d driver boundary: account/session/epoch changes during gated HTTP make
 * late accepted/typed results write nothing; a deterministic
 * transaction-executor admission barrier after claim commit proves the
 * pre-send fence blocks API admission.
 */
class PushDeliveryFenceTest : GymDatabaseTest() {

    private class GatedAcceptedApi(private val database: GymDatabase) : GymSyncApi {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            calls += 1
            entered.complete(Unit)
            gate.await()
            return ackAcceptedResult(
                requests.single().envelope.mutationId,
                ackBatch(target = ackWorkoutChange(requests.single().envelope.aggregateId, 7L)),
            )
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
            throw AssertionError("pull must not be called")
        }

        override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
            throw AssertionError("snapshot must not be called")
        }
    }

    @Test
    fun lateAcceptedAfterSessionSwitch_writesNothing() = runTest {
        database.ackSeedFenced()
        val captured = database.ackCaptureWorkoutHead()
        val api = GatedAcceptedApi(database)
        val loop = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }
        supervisorScope {
            val runner = async {
                try {
                    loop.deliver(0L, 10)
                    fail("late accepted after session switch must propagate fence failure")
                } catch (expected: StaleSessionException) {
                    throw expected
                }
            }
            api.entered.await()
            // Account/session switch while bytes are in flight.
            database.claimSeedDevice(CLAIM_ACCOUNT_B, CLAIM_GENERATION)
            // Frozen DB immediately before releasing the late result.
            val beforeRelease = database.claimSnapshot()
            api.gate.complete(Unit)
            try {
                runner.await()
                fail("late accepted after session switch must propagate fence failure")
            } catch (_: StaleSessionException) {
            }
            assertEquals(1, api.calls)
            // Late success invalidated: nothing beyond the intentional switch.
            assertEquals(beforeRelease, database.claimSnapshot())
        }
        // Exact frozen work retained, no canonical anywhere.
        assertEquals(captured.payloadJson, database.ackRow(CLAIM_MUTATION_1).payloadJson)
        assertEquals("frozen", database.ackRow(CLAIM_MUTATION_1).state)
        assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_B, "workout", CLAIM_WORKOUT_A))
    }

    @Test
    fun lateTypedFailureAfterEpochBump_writesNothing() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : GymSyncApi {
            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                calls += 1
                entered.complete(Unit)
                gate.await()
                throw SyncApiException.Transient(5_000L)
            }

            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                throw AssertionError("pull must not be called")
            }

            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                throw AssertionError("snapshot must not be called")
            }
        }
        val loop = PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }
        supervisorScope {
            val runner = async {
                try {
                    loop.deliver(0L, 10)
                    fail("late typed failure after epoch bump must propagate fence failure")
                } catch (expected: StaleCanonicalEpochException) {
                    throw expected
                }
            }
            entered.await()
            database.claimUpdateCache(CLAIM_ACCOUNT_A, 9L)
            val beforeRelease = database.claimSnapshot()
            gate.complete(Unit)
            try {
                runner.await()
                fail("late typed failure after epoch bump must propagate fence failure")
            } catch (_: StaleCanonicalEpochException) {
            }
            assertEquals(1, calls)
            assertEquals(beforeRelease, database.claimSnapshot())
        }
        val head = database.ackRow(CLAIM_MUTATION_1)
        assertNull(head.retryAfterMillis)
        assertNull(head.errorCode)
    }

    @Test
    fun preSendFenceRead_blocksApiAdmission() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryExecutor = Executors.newSingleThreadExecutor()
        val backingTx = Executors.newSingleThreadExecutor()
        // Run admission, claim and pre-send check are separate fenced
        // transactions. The first clock sample occurs after run entry, so
        // arming there makes the first held transaction the claim and the
        // second the pre-send check.
        val armLatch = AtomicBoolean(false)
        val txCount = AtomicInteger(0)
        val checkGate = CompletableDeferred<Runnable>()
        val fired = AtomicBoolean(false)
        val hookDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(
                Executor { command ->
                    if (armLatch.get()) {
                        when (txCount.incrementAndGet()) {
                            1 -> backingTx.execute(command)
                            2 -> checkGate.complete(command)
                            else -> backingTx.execute(command)
                        }
                    } else {
                        backingTx.execute(command)
                    }
                },
            )
            .build()
        try {
            hookDb.ackSeedFenced()
            val captured = hookDb.ackCaptureWorkoutHead()
            var calls = 0
            val api = object : GymSyncApi {
                override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                    calls += 1
                    throw AssertionError("stale pre-send check must block API admission")
                }

                override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                    throw AssertionError("pull must not be called")
                }

                override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                    throw AssertionError("snapshot must not be called")
                }
            }
            var clockCalls = 0
            val loop = PushDeliveryLoop(hookDb.claimPushStore(), api) {
                clockCalls += 1
                if (clockCalls == 1) armLatch.set(true)
                ACK_NOW_MILLIS
            }
            supervisorScope {
                val runner = async {
                    try {
                        loop.deliver(0L, 10)
                        fail("stale session must fail before send")
                    } catch (expected: StaleSessionException) {
                        throw expected
                    }
                }
                // The held runnable is the pre-send check: the claim has
                // already committed outside any open claim transaction.
                val check = checkGate.await()
                val frozen = checkNotNull(
                    hookDb.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A),
                ) { "claim must be committed before the pre-send check" }
                assertEquals("frozen", frozen.state)
                assertEquals(1, frozen.attemptCount)
                assertEquals(captured.payloadJson, frozen.payloadJson)
                hookDb.claimExec(
                    "UPDATE sync_device_state SET active_account_id = '$CLAIM_ACCOUNT_B'",
                )
                fired.set(true)
                backingTx.execute(check)
                try {
                    runner.await()
                    fail("stale session must fail before send")
                } catch (_: StaleSessionException) {
                }
            }
            assertTrue(fired.get())
            assertEquals(0, calls)
            // The admitted claim stands frozen; the stale check sent nothing.
            val head = hookDb.ackRow(CLAIM_MUTATION_1)
            assertEquals("frozen", head.state)
            assertEquals(1, head.attemptCount)
            assertEquals(captured.payloadJson, head.payloadJson)
            assertNull(hookDb.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        } finally {
            hookDb.close()
            queryExecutor.shutdown()
            backingTx.shutdown()
        }
    }
}
