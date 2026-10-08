package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.R05B_TYPE_ROUTINE
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R12c independent QA: ACTIVE reconcile snapshot/retained recovery,
 * per-invocation snapshot bounds and revocation races (outcome 6 slice).
 *
 * Hand-written from docs/remaining-work/R12c-task.md only (fixed TARGET
 * contract in com.dragosfotache.gymtracka.data.sync.session):
 * maxSnapshotPages bounds EACH same-run driver invocation (1..100), so a
 * two-page snapshot with maxSnapshotPages=1 chains LimitReached then
 * Downloaded on the uninterrupted run, completes, tails and publishes
 * without a retained loop; only missing baseline/cursor, explicit cursor
 * expiry (HTTP 410 cursor_expired + fullResyncRequired) or matching
 * explicit restart starts full-resync; middle-page failure retains the
 * stage for exact restart; foreign/invalid cursors (HTTP 400
 * invalid_cursor) never snapshot; stopped/retained returns reject
 * exact-handle revocation raced at return; cancellation propagates. Typed
 * recovery is always entered explicitly (durable cursor removal or genuine
 * expiry) before any snapshot body is queued, nonterminal pages carry a
 * complete independently written upsert record, and oracles assert actual
 * request sequences plus published graph, not merely stop codes. Real
 * in-memory Room plus QA-owned harness/transport/auth fixtures; no sleeps,
 * no production oracles.
 */
@RunWith(AndroidJUnit4::class)
class R12cReconciliationRecoveryTest {
    private companion object {
        const val CHUNK_ROUTINE_A = "c1200011-1111-4111-8111-111111111111"
        const val CHUNK_ROUTINE_B = "c1200022-2222-4222-8222-222222222222"
        const val PAGE_TWO_TOKEN = "r12c-page-2"

        fun routineRecord(id: String, name: String, revision: Long) =
            """{"id":"$id","name":"$name","isDraft":true,"revision":$revision,"exercises":[]}"""

        fun snapshotPage(
            id: String,
            recordJson: String?,
            revision: Long,
            nextToken: String?,
            hasMore: Boolean,
        ): String {
            val records = if (recordJson == null) {
                "[]"
            } else {
                """[{"aggregateType":"routine","aggregateId":"$id","operation":"upsert","revision":$revision,"record":$recordJson}]"""
            }
            val token = nextToken?.let { "\"$it\"" } ?: "null"
            return """{"records":$records,"baselineCursor":"r09h-baseline","nextPageToken":$token,"hasMore":$hasMore}"""
        }

        val PAGE_ONE_BODY = snapshotPage(CHUNK_ROUTINE_A, routineRecord(CHUNK_ROUTINE_A, "R12c Chunked A", 1L), 1L, PAGE_TWO_TOKEN, true)
        val PAGE_TWO_BODY = snapshotPage(CHUNK_ROUTINE_B, routineRecord(CHUNK_ROUTINE_B, "R12c Chunked B", 1L), 1L, null, false)
    }

    private val harness = AppSessionHarness("r12c-recovery")
    private val dbs = mutableListOf<GymDatabase>()

    @After
    fun tearDown() {
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harness.clean()
    }

    private fun db(): GymDatabase = harness.openInMemoryDb().also { dbs += it }

    private data class Stack(
        val database: GymDatabase,
        val vault: SessionVault,
        val auth: AppSessionAuthApi,
        val transport: AppSessionTransport,
        val owner: PushRunOwner,
        val transition: ScriptedTransitionBarrier,
        val publication: ScriptedPublicationBarrier,
        val coordinator: AppSessionCoordinator,
    )

    private fun stack(database: GymDatabase = db(), origin: ServiceOrigin? = AppSessionFixtures.origin()): Stack {
        val vault = harness.newVault()
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
        val owner = AppSessionFixtures.owner()
        val transition = ScriptedTransitionBarrier()
        val publication = ScriptedPublicationBarrier()
        val coordinator = AppSessionFixtures.coordinator(
            database = database,
            origin = origin,
            vault = vault,
            auth = auth,
            transport = transport,
            runOwner = owner,
            transitionBarrier = transition,
            publicationBarrier = publication,
        )
        return Stack(database, vault, auth, transport, owner, transition, publication, coordinator)
    }

    private suspend fun Stack.activate(
        operationId: String = AppSessionFixtures.OP_FRESH,
        grant: com.dragosfotache.gymtracka.data.sync.api.ServiceSession = AppSessionFixtures.grant(),
    ): AppAccountSession {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val preparing = coordinator.acceptGrant(RoomSessionAuthority(database).read(), operationId, grant)
        assertTrue("preparation must stay Private, was $preparing", preparing is AppSessionState.Private)
        val active = coordinator.completeActivation(RoomSessionAuthority(database).read(), AppSessionFixtures.STAGE)
        assertTrue("activation must be Active, was $active", active is AppSessionState.Active)
        assertTrue("activation must drain its phase replies", transport.queue.isEmpty())
        return (active as AppSessionState.Active).session
    }

    private suspend fun Stack.epoch(session: AppAccountSession) = coordinator.observeCacheEpoch(session)

    private fun Stack.pathsFrom(mark: Int) = transport.capturedRequests.subList(mark, transport.capturedRequests.size).map { it.path }

    private fun databaseDeleteSyncState(database: GymDatabase) {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM sync_state WHERE account_id = ?",
            arrayOf(AppSessionFixtures.ACCOUNT),
        )
    }

    @Test
    fun missingCursorRecoversWholeSnapshotThenTailAndPublish() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        databaseDeleteSyncState(stack.database)
        val mark = stack.transport.capturedRequests.size
        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val result = stack.coordinator.reconcile(session = session, expectedCacheEpoch = epoch)
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertNull(result.retainedStageId)
        assertEquals(listOf("/v1/sync/snapshot", "/v1/sync/pull"), stack.pathsFrom(mark))
        // TWO checked advances: snapshot canonical completion +1, eligible publication +1.
        assertEquals(epoch + 2, result.cacheEpoch)
        assertEquals(epoch + 2, harness.cacheEpoch(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals(epoch + 2, harness.materializedEpoch(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals(0L, harness.projectionDirty(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals("r09h-baseline", harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT))
        AppSessionFixtures.assertRedacted(result.toString())
    }

    @Test
    fun wireCursorExpiryRecoversWholeSnapshotThenTail() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        val mark = stack.transport.capturedRequests.size
        stack.transport.queueCode(410, """{"errorCode":"cursor_expired","fullResyncRequired":true}""")
        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val result = stack.coordinator.reconcile(session = session, expectedCacheEpoch = epoch)
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertNull(result.retainedStageId)
        assertEquals(listOf("/v1/sync/pull", "/v1/sync/snapshot", "/v1/sync/pull"), stack.pathsFrom(mark))
        assertEquals(epoch + 2, result.cacheEpoch)
        assertEquals(epoch + 2, harness.materializedEpoch(stack.database, AppSessionFixtures.ACCOUNT))
        AppSessionFixtures.assertRedacted(result.toString())
    }

    @Test
    fun middlePageFailureRetainsStageForExactRestartOnly() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        databaseDeleteSyncState(stack.database)
        val mark = stack.transport.capturedRequests.size
        stack.transport.queueOk(PAGE_ONE_BODY)
        stack.transport.queueCode(500, """{"errorCode":"probe-transient"}""")
        val stopped = stack.coordinator.reconcile(session = session, expectedCacheEpoch = epoch)
        assertEquals(AppReconciliationStop.SNAPSHOT_STOPPED, stopped.stop)
        val retained = checkNotNull(stopped.retainedStageId) { "typed failure must retain the stage" }
        assertEquals(
            listOf("/v1/sync/snapshot", "/v1/sync/snapshot"),
            stack.pathsFrom(mark).filter { it == "/v1/sync/snapshot" },
        )

        val heldMark = stack.transport.capturedRequests.size
        val held = stack.coordinator.reconcile(session = session, expectedCacheEpoch = epoch)
        assertEquals(AppReconciliationStop.RETAINED_STAGE, held.stop)
        assertEquals(retained, held.retainedStageId)
        assertEquals(heldMark, stack.transport.capturedRequests.size)

        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val recovered = stack.coordinator.reconcile(
            session = session,
            expectedCacheEpoch = epoch,
            restartStageId = retained,
        )
        assertEquals(AppReconciliationStop.TAIL, recovered.stop)
        assertNull(recovered.retainedStageId)
    }

    @Test
    fun twoPageSnapshotWithSinglePageBoundChainsWithoutRetainedLoop() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        databaseDeleteSyncState(stack.database)
        val mark = stack.transport.capturedRequests.size
        stack.transport.queueOk(PAGE_ONE_BODY)
        stack.transport.queueOk(PAGE_TWO_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val result = stack.coordinator.reconcile(
            session = session,
            expectedCacheEpoch = epoch,
            maxSnapshotPages = 1,
        )
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertNull(result.retainedStageId)
        assertEquals(
            listOf("/v1/sync/snapshot", "/v1/sync/snapshot", "/v1/sync/pull"),
            stack.pathsFrom(mark),
        )
        val publishedA = harness.canonicalRevision(stack.database, AppSessionFixtures.ACCOUNT, R05B_TYPE_ROUTINE, CHUNK_ROUTINE_A)
        assertEquals(1L, checkNotNull(publishedA) { "chunked snapshot must publish root A" }.first)
        val publishedB = harness.canonicalRevision(stack.database, AppSessionFixtures.ACCOUNT, R05B_TYPE_ROUTINE, CHUNK_ROUTINE_B)
        assertEquals(1L, checkNotNull(publishedB) { "chunked snapshot must publish root B" }.first)

        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val quiescentMark = stack.transport.capturedRequests.size
        val quiescent = stack.coordinator.reconcile(session = session, expectedCacheEpoch = result.cacheEpoch)
        assertEquals(AppReconciliationStop.TAIL, quiescent.stop)
        assertNull(quiescent.retainedStageId)
        assertEquals(listOf("/v1/sync/pull"), stack.pathsFrom(quiescentMark))
    }

    @Test
    fun invalidCursorNeverSnapshotsAndLeavesCacheUnchanged() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        val cacheBefore = harness.cacheEpoch(stack.database, AppSessionFixtures.ACCOUNT)
        val outboxBefore = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        val mark = stack.transport.capturedRequests.size
        stack.transport.queueCode(400, """{"errorCode":"invalid_cursor"}""")
        val result = stack.coordinator.reconcile(session = session, expectedCacheEpoch = epoch)
        assertEquals(AppReconciliationStop.PULL_STOPPED, result.stop)
        assertNull(result.snapshot)
        assertTrue(stack.pathsFrom(mark).none { it == "/v1/sync/snapshot" })
        assertEquals(cacheBefore, harness.cacheEpoch(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals(outboxBefore, harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun uuidCancellationPropagatesOnTypedRecovery() = runTest {
        val database = db()
        val vault = harness.newVault()
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
        var failUuids = false
        val coordinator = AppSessionCoordinator(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = vault,
            authApi = auth,
            transport = transport,
            runOwner = AppSessionFixtures.owner(),
            transitionBarrier = ScriptedTransitionBarrier(),
            publicationBarrier = ScriptedPublicationBarrier(),
            clockMillis = { AppSessionFixtures.FIXED_CLOCK },
            uuidProvider = {
                if (failUuids) throw CancellationException("r12c-uuid-cancelled")
                "e1200001-1111-4111-8111-111111111111"
            },
        )
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val preparing = coordinator.acceptGrant(RoomSessionAuthority(database).read(), AppSessionFixtures.OP_FRESH, AppSessionFixtures.grant())
        assertTrue(preparing is AppSessionState.Private)
        val active = coordinator.completeActivation(RoomSessionAuthority(database).read(), AppSessionFixtures.STAGE)
        assertTrue(active is AppSessionState.Active)
        val session = (active as AppSessionState.Active).session
        val epoch = coordinator.observeCacheEpoch(session)
        databaseDeleteSyncState(database)
        failUuids = true
        val error = runCatching { coordinator.reconcile(session, epoch) }.exceptionOrNull()
        assertTrue("UUID cancellation must propagate, was $error", error is CancellationException)
    }

    @Test
    fun restartProviderFailurePreservesSelectedStageUntilValidId() = runTest {
        val database = db()
        val vault = harness.newVault()
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
        var failUuids = false
        val coordinator = AppSessionCoordinator(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = vault,
            authApi = auth,
            transport = transport,
            runOwner = AppSessionFixtures.owner(),
            transitionBarrier = ScriptedTransitionBarrier(),
            publicationBarrier = ScriptedPublicationBarrier(),
            clockMillis = { AppSessionFixtures.FIXED_CLOCK },
            uuidProvider = {
                if (failUuids) throw IllegalStateException("r12c-uuid-exhausted")
                "e1200002-2222-4222-8222-222222222222"
            },
        )
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val preparing = coordinator.acceptGrant(RoomSessionAuthority(database).read(), AppSessionFixtures.OP_FRESH, AppSessionFixtures.grant())
        assertTrue(preparing is AppSessionState.Private)
        val active = coordinator.completeActivation(RoomSessionAuthority(database).read(), AppSessionFixtures.STAGE)
        assertTrue(active is AppSessionState.Active)
        val session = (active as AppSessionState.Active).session
        val epoch = coordinator.observeCacheEpoch(session)
        databaseDeleteSyncState(database)
        transport.queueOk(PAGE_ONE_BODY)
        transport.queueCode(500, """{"errorCode":"probe-transient"}""")
        val stopped = coordinator.reconcile(session, epoch)
        assertEquals(AppReconciliationStop.SNAPSHOT_STOPPED, stopped.stop)
        val retained = checkNotNull(stopped.retainedStageId)

        failUuids = true
        val failure = runCatching { coordinator.reconcile(session, epoch, restartStageId = retained) }.exceptionOrNull()
        // Non-cancel provider failures are closed UNAVAILABLE without
        // leaking the raw cause/message; the selected stage is preserved.
        AppSessionFixtures.assertRefusal(
            checkNotNull(failure) { "restart ID failure must refuse, not converge" },
            AppSessionReason.UNAVAILABLE,
        )
        val held = coordinator.reconcile(session, epoch)
        assertEquals(AppReconciliationStop.RETAINED_STAGE, held.stop)
        assertEquals("old selected stage preserved until valid ID", retained, held.retainedStageId)

        failUuids = false
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val recovered = coordinator.reconcile(session, epoch, restartStageId = retained)
        assertEquals(AppReconciliationStop.TAIL, recovered.stop)
        assertNull(recovered.retainedStageId)
    }

    @Test
    fun revocationRacedAtReturnRejectsStaleInsteadOfTail() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        assertTrue(stack.transport.queue.isEmpty())
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        var fireOnce = true
        stack.transport.responseGate = {
            if (fireOnce) {
                fireOnce = false
                stack.coordinator.invalidate()
            }
        }
        val error = runCatching {
            stack.coordinator.reconcile(session = session, expectedCacheEpoch = epoch)
        }.exceptionOrNull()
        AppSessionFixtures.assertStale(
            checkNotNull(error) { "revocation raced at return must reject stale, not TAIL" },
        )
    }
}
