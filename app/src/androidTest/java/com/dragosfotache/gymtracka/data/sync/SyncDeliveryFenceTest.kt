package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StalePullCheckpointException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R07c independent ownership/fence acceptance for the shared driver.
 *
 * Real Room, no DAO/run mocks, no duplicated engine. Every expectation is a
 * literal authored from docs/archive/remaining-work/R07c-task.md; representative
 * tamper classes share one recovery path (no late last-success or cleanup)
 * rather than duplicating every R07a/b fence matrix row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncDeliveryFenceTest : GymDatabaseTest() {

    @Test
    fun sharedOwnerSerializes_queuedCallerCannotEnterPushPullTailGap() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val owner = claimOwner()
        val api = SyncDeliveryGatedApi(
            database,
            pushAfter = SyncPushScript.Respond(
                ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ),
            pullAfter = PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
        )
        val first = SyncDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { SYNC_NOW_MILLIS }
        val second = SyncDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { SYNC_NOW_MILLIS }

        val firstJob = async { first.deliver(SYNC_EPOCH) }
        api.pushEntered.await()
        // Queue the second caller while the first owns push -> pull -> tail.
        val secondJob = async { second.deliver(SYNC_EPOCH) }
        runCurrent()
        assertEquals(1, api.pushCalls.size)
        assertEquals(0, api.pullCalls.size)

        api.pushGate.complete(Unit)
        api.pullEntered.await()
        runCurrent()
        // Still no second-caller transport while the first holds the shared run.
        assertEquals(1, api.pushCalls.size)
        assertEquals(1, api.pullCalls.size)

        api.pullGate.complete(Unit)
        val done = firstJob.await()
        assertTrue(done.push.stop is PushDeliveryStop.Empty)
        assertTrue(checkNotNull(done.pull).stop is PullDeliveryStop.Tail)
        assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())

        // Ownership released: the queued caller now runs to Empty without extra push work.
        val queued = secondJob.await()
        assertEquals(0, queued.push.acknowledgedMutations)
        assertTrue(queued.push.stop is PushDeliveryStop.Empty)
        assertTrue(checkNotNull(queued.pull).stop is PullDeliveryStop.Tail)
        assertEquals(1, api.pushCalls.size)
        assertEquals(1, api.maxConcurrentPush)
    }

    @Test
    fun canceledQueuedCaller_createsNoExtraAdmission_parentSettles() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val owner = claimOwner()
        val api = SyncDeliveryGatedApi(
            database,
            pushAfter = SyncPushScript.Respond(
                ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ),
            pullAfter = PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
        )
        val first = SyncDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { SYNC_NOW_MILLIS }
        val second = SyncDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { SYNC_NOW_MILLIS }

        val firstJob = async { first.deliver(SYNC_EPOCH) }
        api.pushEntered.await()
        val queued = async { second.deliver(SYNC_EPOCH) }
        runCurrent()
        queued.cancel()
        try {
            queued.await()
            fail("queued delivery must observe cancellation")
        } catch (_: CancellationException) {
        }
        // Canceled queue creates no extra head admission.
        assertEquals(1, database.ackRow(CLAIM_MUTATION_1).attemptCount)

        api.pushGate.complete(Unit)
        api.pullEntered.await()
        api.pullGate.complete(Unit)
        val done = firstJob.await()
        assertEquals(1, done.push.acknowledgedMutations)
        assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
        assertEquals(1, api.pushCalls.size)
    }

    @Test
    fun newCaptureDuringPull_preservedAndReportedOutstanding() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val owner = claimOwner()
        val api = SyncDeliveryGatedApi(
            database,
            pushAfter = SyncPushScript.Respond(
                ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ),
            pullAfter = PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
        )
        val loop = SyncDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { SYNC_NOW_MILLIS }
        val job = async { loop.deliver(SYNC_EPOCH) }
        api.pushEntered.await()
        api.pushGate.complete(Unit)
        api.pullEntered.await()
        // The push phase has committed at this boundary: snapshot post-ack
        // domain, since ack metadata legitimately changes the pre-push row.
        val ackedDomain = checkNotNull(database.workoutDao().getWorkout(CLAIM_WORKOUT_A))
        // New local intent captured while the shared run owns pull HTTP.
        val captureStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Sync Workout B")
        database.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Sync Workout B")
        // Snapshot the NEW intent at the pull-enter boundary: root, outbox,
        // watermark and cache must all survive the valid tail exactly.
        val newDomain = checkNotNull(database.workoutDao().getWorkout(CLAIM_WORKOUT_B))
        val newOutbox = checkNotNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A))
        val newWatermark = database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_B)
        val cacheAtCapture = database.ackCacheRow(CLAIM_ACCOUNT_A)
        api.pullGate.complete(Unit)

        val result = job.await()
        assertTrue(checkNotNull(result.pull).stop is PullDeliveryStop.Tail)
        // Empty proves only the earlier boundary; newly captured work remains protected.
        assertEquals(true, result.hasOutstandingLocalWork)
        assertEquals(SYNC_NOW_MILLIS, database.pullLastSuccess())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        assertEquals(ackedDomain, database.workoutDao().getWorkout(CLAIM_WORKOUT_A))
        assertEquals(newDomain, database.workoutDao().getWorkout(CLAIM_WORKOUT_B))
        assertEquals(newOutbox, database.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A))
        assertEquals(newWatermark, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_B))
        assertEquals(cacheAtCapture, database.ackCacheRow(CLAIM_ACCOUNT_A))
        // False is not convergence: the new head still requires a later push.
        assertEquals("pending", checkNotNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_2, CLAIM_ACCOUNT_A)).state)
    }

    private data class LateTamper(
        val label: String,
        val expected: KClass<out Throwable>,
        val mutate: suspend () -> Unit,
    )

    @Test
    fun lateRowBaselineSessionEpoch_fenceEmptyTailWithoutLastSuccess() = runTest {
        val tampers = listOf(
            LateTamper("row-deleted", StalePullCheckpointException::class) {
                database.claimExec("DELETE FROM sync_state WHERE account_id = '$PULL_ACCOUNT_A'")
            },
            LateTamper("cursor-moved", StalePullCheckpointException::class) {
                database.syncDao().upsertSyncState(SyncStateEntity(PULL_ACCOUNT_A, SYNC_CURSOR_MID, null))
            },
            LateTamper("baseline-incomplete", StalePullCheckpointException::class) {
                database.claimExec("UPDATE sync_account_cache SET baseline_complete = 0 WHERE account_id = '$PULL_ACCOUNT_A'")
            },
            LateTamper("session-changed", StaleSessionException::class) {
                database.claimSeedDevice(PULL_ACCOUNT_A, PULL_GENERATION + 1)
            },
            LateTamper("epoch-changed", StaleCanonicalEpochException::class) {
                database.claimUpdateCache(PULL_ACCOUNT_A, 9L)
            },
        )
        tampers.forEach { tamper ->
            database.claimExec("DELETE FROM sync_state")
            database.claimExec("DELETE FROM sync_account_cache")
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
            val api = SyncDeliveryGatedApi(
                database,
                pushAfter = SyncPushScript.Respond(
                    ackAcceptedResult("unused", ackBatch(target = ackWorkoutChange())),
                ),
                pullAfter = PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
            )
            // No push work: the driver goes straight to pull -> empty tail.
            val loop = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), api) { SYNC_NOW_MILLIS }
            var tampered = ""
            // The failing child must not cancel this scope: isolate it so the
            // exact fence type can be classified and asserted outside.
            val thrown: Throwable? = supervisorScope {
                val job = async { loop.deliver(SYNC_EPOCH) }
                api.pullEntered.await()
                tamper.mutate()
                tampered = database.claimSnapshot()
                api.pullGate.complete(Unit)
                try {
                    job.await()
                    null
                } catch (e: StaleSessionException) {
                    e
                } catch (e: StaleCanonicalEpochException) {
                    e
                } catch (e: StalePullCheckpointException) {
                    e
                }
            }
            assertTrue(
                "${tamper.label} must propagate ${tamper.expected.simpleName}",
                tamper.expected.isInstance(thrown),
            )
            assertNull("${tamper.label} must not record last-success", database.pullLastSuccess())
            assertEquals("${tamper.label} tampered state unchanged", tampered, database.claimSnapshot())
        }
    }

    @Test
    fun tailGap_queuedStandalonePushCannotAdmitUntilTailSettles() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tailArmed = AtomicBoolean(false)
        val tailObserved = AtomicBoolean(false)
        // Entry is signaled with a suspending Deferred completed from the hook:
        // blocking the runTest dispatcher on a latch here would prevent the
        // driver from ever reaching the tail. Only the RELEASE stays a bounded
        // latch, because it blocks the querying thread, not the test thread.
        val tailEntered = CompletableDeferred<Unit>()
        val tailRelease = CountDownLatch(1)
        // Test-only real Room query-callback hook (setQueryCallback): observe
        // the first sync_state UPDATE — the tail bookkeeping entry after the
        // pull API returns — then block the querying thread on the release
        // latch while the test proves the queued caller cannot admit.
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (tailArmed.get()) {
                        val lower = sql.lowercase()
                        if (lower.contains("update") && lower.contains("sync_state")) {
                            tailObserved.set(true)
                            tailEntered.complete(Unit)
                            check(tailRelease.await(15, TimeUnit.SECONDS)) {
                                "tail release latch timed out"
                            }
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
            callbackDb.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
            val owner = claimOwner()
            val store = callbackDb.claimPushStore(claimSession(), owner)
            val api = SyncDeliveryGatedApi(
                callbackDb,
                pushAfter = SyncPushScript.Respond(
                    ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ),
                pullAfter = PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
            )
            val first = async {
                SyncDeliveryLoop(store, api) { SYNC_NOW_MILLIS }.deliver(SYNC_EPOCH)
            }
            try {
                api.pushEntered.await()
                api.pushGate.complete(Unit)
                api.pullEntered.await()
                tailArmed.set(true)
                api.pullGate.complete(Unit)
                // Suspend (never block): the driver reaches the tail on this
                // dispatcher while the hook fires from the Room thread.
                tailEntered.await()
                // Queue a standalone same-owner push while the tail transaction is held.
                val secondApi = SyncDeliveryScriptApi(callbackDb, mutableListOf(), mutableListOf())
                val second = async {
                    PushDeliveryLoop(callbackDb.claimPushStore(claimSession(), owner), secondApi) { SYNC_NOW_MILLIS }
                        .deliver(SYNC_EPOCH, 100)
                }
                try {
                    runCurrent()
                    assertTrue("queued standalone push must not settle during tail", !second.isCompleted)
                    assertEquals(0, secondApi.pushCalls.size)
                } finally {
                    tailArmed.set(false)
                    tailRelease.countDown()
                }
                val queued = second.await()
                assertTrue(queued.stop is PushDeliveryStop.Empty)
                assertEquals(0, queued.acknowledgedMutations)
                assertEquals(0, secondApi.pushCalls.size)
            } finally {
                tailArmed.set(false)
                tailRelease.countDown()
            }
            val done = first.await()
            assertTrue(checkNotNull(done.pull).stop is PullDeliveryStop.Tail)
            assertEquals(SYNC_NOW_MILLIS, callbackDb.pullLastSuccess())
            assertTrue(tailObserved.get())
        } finally {
            tailRelease.countDown()
            callbackDb.close()
        }
    }

    @Test
    fun canceledPullHttp_recordsNoTailTimestamp_earlierAckSurvives() = runTest {
        database.pullSeedBaseline(cursor = PULL_CURSOR_1, lastSuccessMillis = null)
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Sync Workout")
        val owner = claimOwner()
        val api = SyncDeliveryGatedApi(
            database,
            pushAfter = SyncPushScript.Respond(
                ackAcceptedResult(CLAIM_MUTATION_1, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ),
            pullAfter = PullScriptResponse.Page(pullDeliveryPage(emptyList(), PULL_CURSOR_1, false)),
        )
        val job = async {
            SyncDeliveryLoop(database.claimPushStore(claimSession(), owner), api) { SYNC_NOW_MILLIS }
                .deliver(SYNC_EPOCH)
        }
        api.pushEntered.await()
        api.pushGate.complete(Unit)
        api.pullEntered.await()
        job.cancel()
        try {
            job.await()
            fail("canceled pull HTTP must propagate cancellation")
        } catch (_: CancellationException) {
        }
        api.pullGate.complete(Unit)
        // A canceled pull never reaches the valid-tail callback: no timestamp.
        assertNull(database.pullLastSuccess())
        assertEquals(PULL_CURSOR_1, database.pullSyncCursor())
        assertEquals(1, api.pushCalls.size)
        assertEquals(1, api.pullCalls.size)
        // The earlier push acknowledgment remains durable after cancellation.
        assertNull(database.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
        assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
    }
}
