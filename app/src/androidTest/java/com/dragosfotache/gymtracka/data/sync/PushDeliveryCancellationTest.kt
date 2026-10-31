package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * driver boundary: cancellation during HTTP plus a genuinely late
 * adversarial response cannot acknowledge; unexpected errors propagate
 * without retry writes; a typed failure racing cancellation persists nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushDeliveryCancellationTest : GymDatabaseTest() {

    private class ShieldedApi(private val database: GymDatabase) : GymSyncApi {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            calls += 1
            entered.complete(Unit)
            // Shielded late response: genuinely reaches the canceled caller.
            return withContext(NonCancellable) {
                gate.await()
                returned.complete(Unit)
                ackAcceptedResult(
                    requests.single().envelope.mutationId,
                    ackBatch(target = ackWorkoutChange(requests.single().envelope.aggregateId, 7L)),
                )
            }
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
            throw AssertionError("pull must not be called")
        }

        override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
            throw AssertionError("snapshot must not be called")
        }
    }

    private class FailingApi(private val database: GymDatabase, val error: Throwable) : GymSyncApi {
        var calls = 0
        override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
            check(!database.inTransaction()) { "push must run outside Room" }
            calls += 1
            throw error
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
            throw AssertionError("pull must not be called")
        }

        override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
            throw AssertionError("snapshot must not be called")
        }
    }

    @Test
    fun lateShieldedResponse_afterCancel_cannotAcknowledge() = runTest {
        database.ackSeedFenced()
        val captured = database.ackCaptureWorkoutHead()
        val frozenBytes = captured.payloadJson
        val frozenInner = JSONObject(frozenBytes).getString("requestJson")

        // Same explicit owner across cancellation and recovery: the old run
        // must release ownership so the fresh recovery run can proceed.
        val owner = claimOwner()
        val api = ShieldedApi(database)
        val loop = PushDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { ACK_NOW_MILLIS }
        supervisorScope {
            val runner = async {
                try {
                    loop.deliver(0L, 10)
                    fail("canceled delivery must surface cancellation despite late response")
                } catch (expected: CancellationException) {
                    throw expected
                }
            }
            api.entered.await()
            runner.cancel()
            api.gate.complete(Unit)
            api.returned.await()
            try {
                runner.await()
                fail("canceled delivery must surface cancellation despite late response")
            } catch (_: CancellationException) {
            }
        }
        // No acknowledgment through the dead caller/run.
        assertEquals("frozen", database.ackRow(CLAIM_MUTATION_1).state)
        assertEquals(1, database.ackRow(CLAIM_MUTATION_1).attemptCount)
        assertEquals(frozenBytes, database.ackRow(CLAIM_MUTATION_1).payloadJson)
        assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))

        // Fresh invocation under the same owner replays the exact frozen identity.
        val recovery = object : GymSyncApi {
            var calls = 0
            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                calls += 1
                assertEquals(frozenInner, requests.single().requestJson)
                assertEquals(CLAIM_MUTATION_1, requests.single().envelope.mutationId)
                return ackAcceptedResult(
                    requests.single().envelope.mutationId,
                    ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L)),
                )
            }

            override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
                throw AssertionError("pull must not be called")
            }

            override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
                throw AssertionError("snapshot must not be called")
            }
        }
        val recovered =
            PushDeliveryLoop(database.claimPushStore(claimSession(), owner), recovery) { ACK_NOW_MILLIS }.deliver(0L, 10)
        assertEquals(1, recovered.acknowledgedMutations)
        assertTrue(recovered.stop is PushDeliveryStop.Empty)
        assertEquals(1, recovery.calls)
    }

    @Test
    fun unexpectedError_propagatesWithoutRetryWrites() = runTest {
        database.ackSeedFenced()
        val captured = database.ackCaptureWorkoutHead()
        val api = FailingApi(database, IllegalStateException("boom-transport"))
        try {
            PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
            fail("unexpected API error must propagate")
        } catch (error: IllegalStateException) {
            assertEquals("boom-transport", error.message)
        }
        assertEquals(1, api.calls)
        val head = database.ackRow(CLAIM_MUTATION_1)
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        assertEquals(captured.payloadJson, head.payloadJson)
        assertNull(head.errorCode)
        assertNull(head.retryAfterMillis)
    }

    @Test
    fun typedFailureRacingCancel_persistsNothing() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        // Shielded so the typed failure genuinely fires after cancellation and
        // reaches the driver's typed-failure catch, which must still honor the
        // dead caller and persist nothing.
        val threwTyped = AtomicBoolean(false)
        val api = object : GymSyncApi {
            override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
                check(!database.inTransaction()) { "push must run outside Room" }
                entered.complete(Unit)
                return withContext(NonCancellable) {
                    gate.await()
                    threwTyped.set(true)
                    throw SyncApiException.Transient(5_000L)
                }
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
                    fail("canceled delivery must surface cancellation, not deferred")
                } catch (expected: CancellationException) {
                    throw expected
                }
            }
            entered.await()
            runner.cancel()
            gate.complete(Unit)
            try {
                runner.await()
                fail("canceled delivery must surface cancellation, not deferred")
            } catch (_: CancellationException) {
            }
        }
        // The typed throw path fired, yet the canceled caller persisted nothing.
        assertTrue(threwTyped.get())
        val head = database.ackRow(CLAIM_MUTATION_1)
        assertEquals("frozen", head.state)
        assertNull(head.retryAfterMillis)
        assertNull(head.errorCode)
    }

    @Test
    fun injectedCancellationInActiveCaller_propagatesWithoutConversion() = runTest {
        database.ackSeedFenced()
        val captured = database.ackCaptureWorkoutHead()
        val api = FailingApi(database, CancellationException("injected-delivery-cancel"))
        supervisorScope {
            val runner = async {
                try {
                    PushDeliveryLoop(database.claimPushStore(), api) { ACK_NOW_MILLIS }.deliver(0L, 10)
                    fail("injected cancellation must propagate")
                } catch (expected: CancellationException) {
                    // Identity checked inside the actual caller, not across await recovery.
                    assertSame(api.error, expected)
                    throw expected
                }
            }
            try {
                runner.await()
                fail("injected cancellation must propagate")
            } catch (_: CancellationException) {
            }
        }
        // Cancellation is never converted to transient/malformed outcomes.
        assertEquals(1, api.calls)
        val head = database.ackRow(CLAIM_MUTATION_1)
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        assertEquals(captured.payloadJson, head.payloadJson)
        assertNull(head.errorCode)
        assertNull(head.retryAfterMillis)
        assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
    }
}
