package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R12c independent QA: fenced fresh-cache-epoch facade read (narrow new-API
 * slice).
 *
 * Hand-written from docs/remaining-work/R12c-task.md only (fixed TARGET
 * contract in com.dragosfotache.gymtracka.data.sync.session): suspend fun
 * AppSessionCoordinator.observeCacheEpoch(session: AppAccountSession): Long
 * returns the advisory epoch for the next reconcile on the exact-current
 * configured handle, under readonly pre/post authority/process/session/
 * caller fences over a fenced Room metadata read (complete baseline,
 * nonnegative cache/materialized counters, materialized<=cache). Dirty
 * complete caches and queued intent stay eligible. No HTTP/UUID/clock/
 * engine/admission/write/cleanup/rebind/grant. Real in-memory Room plus
 * QA-owned harness/transport/auth fixtures and raw-SQL oracles only for
 * setup/verification; no sleeps, no production oracles.
 */
@RunWith(AndroidJUnit4::class)
class R12cObserveCacheEpochTest {
    private val harness = AppSessionHarness("r12c-observe-epoch")
    private val extraHarnesses = mutableListOf<AppSessionHarness>()
    private val dbs = mutableListOf<GymDatabase>()

    @After
    fun tearDown() {
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harness.clean()
        extraHarnesses.forEach { runCatching { it.clean() } }
        extraHarnesses.clear()
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

    private fun stack(
        database: GymDatabase = db(),
        origin: ServiceOrigin? = AppSessionFixtures.origin(),
        owner: AppSessionHarness = harness,
    ): Stack {
        val vault = owner.newVault()
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
        return (active as AppSessionState.Active).session
    }

    @Test
    fun observesCommittedEpochWithoutNetworkOrDurableMutation() = runTest {
        val stack = stack()
        val session = stack.activate()
        val servedBefore = stack.transport.capturedRequests.size
        val journalBefore = harness.journalRow(stack.database).toString()
        val deviceBefore = harness.deviceRow(stack.database).toString()

        val first = stack.coordinator.observeCacheEpoch(session)
        val second = stack.coordinator.observeCacheEpoch(session)
        assertEquals(first, second)
        assertEquals(first, harness.cacheEpoch(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals(servedBefore, stack.transport.capturedRequests.size)
        assertEquals(journalBefore, harness.journalRow(stack.database).toString())
        assertEquals(deviceBefore, harness.deviceRow(stack.database).toString())
        assertTrue("observed handle stays live", stack.coordinator.state.value is AppSessionState.Active)
    }

    @Test
    fun dirtyCacheWithQueuedIntentStaysEligible() = runTest {
        val stack = stack()
        val session = stack.activate()
        val servedBeforeEdit = stack.transport.capturedRequests.size
        runBlockingRead { session.repository.createCustomExercise("R12c Pending", null, emptySet(), null, null, "") }
        assertEquals("local edit performs no HTTP", servedBeforeEdit, stack.transport.capturedRequests.size)

        val observed = stack.coordinator.observeCacheEpoch(session)
        assertEquals(observed, harness.cacheEpoch(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals("read performs no new HTTP", servedBeforeEdit, stack.transport.capturedRequests.size)
        assertTrue("dirty intent must survive the read", harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT) == 1L)
        assertTrue(stack.coordinator.state.value is AppSessionState.Active)
    }

    @Test
    fun observedEpochMatchesPostRecoveryReconcileInput() = runTest {
        val stack = stack()
        val session = stack.activate()
        stack.database.openHelper.writableDatabase.execSQL(
            "DELETE FROM sync_state WHERE account_id = ?",
            arrayOf(AppSessionFixtures.ACCOUNT),
        )
        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val recovered = stack.coordinator.reconcile(session, stack.coordinator.observeCacheEpoch(session))
        assertEquals(AppReconciliationStop.TAIL, recovered.stop)
        assertEquals(recovered.cacheEpoch, stack.coordinator.observeCacheEpoch(session))
        assertEquals(recovered.cacheEpoch, harness.cacheEpoch(stack.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun revokedHandleRefusesStale() = runTest {
        val stack = stack()
        val session = stack.activate()
        stack.coordinator.invalidate()
        val error = runCatching { stack.coordinator.observeCacheEpoch(session) }.exceptionOrNull()
        AppSessionFixtures.assertStale(
            checkNotNull(error) { "revoked handle must refuse, not observe" },
        )
    }

    @Test
    fun foreignHandleRefusesStale() = runTest {
        val first = stack()
        first.activate()
        val foreignHarness = AppSessionHarness("r12c-observe-epoch-foreign").also { extraHarnesses += it }
        val foreignDb = foreignHarness.openInMemoryDb().also { dbs += it }
        val second = stack(foreignDb, AppSessionFixtures.origin(), foreignHarness)
        val secondSession = second.activate(
            operationId = AppSessionFixtures.OP_OTHER,
            grant = AppSessionFixtures.grant(accountId = AppSessionFixtures.OTHER_ACCOUNT),
        )
        assertEquals(AppSessionFixtures.OTHER_ACCOUNT, secondSession.identity.binding.accountId)
        val error = runCatching { first.coordinator.observeCacheEpoch(secondSession) }.exceptionOrNull()
        AppSessionFixtures.assertStale(
            checkNotNull(error) { "foreign handle must refuse, not observe" },
        )
        assertTrue("home handle stays live", first.coordinator.state.value is AppSessionState.Active)
    }

    @Test
    fun cancelledCallerPropagates() = runTest {
        val stack = stack()
        val session = stack.activate()
        // Cancel the ACTUAL entered child caller: park it on a gate, cancel
        // it there, then capture the exception inside that same caller.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val child = async {
            entered.complete(Unit)
            gate.await()
            stack.coordinator.observeCacheEpoch(session)
        }
        runCurrent()
        assertTrue("child must enter before cancel", entered.isCompleted)
        child.cancel(CancellationException("r12c-observe-cancelled"))
        gate.complete(Unit)
        val error = runCatching { child.await() }.exceptionOrNull()
        assertTrue("cancellation must propagate, was $error", error is CancellationException)
        runCurrent()
        assertTrue("facade stays usable", stack.coordinator.state.value is AppSessionState.Active)
    }

    private fun runBlockingRead(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
