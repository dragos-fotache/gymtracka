package com.dragosfotache.gymtracka.data.sync.session

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * R09h independent QA: process fencing and bounded reconnect, real Room.
 *
 * Derived ONLY from docs/remaining-work/R09h-task.md (coordinator operation
 * mutex, synchronous invalidation killing process permits, facade repository
 * process guard inside every scoped transaction, PushStore/PushRun process
 * checks with rollback of uncommitted work, reconnect binding the exact
 * current handle, bounded SyncDeliveryLoop reuse, frozen-identity retries,
 * transient/reauthentication stops keeping cached editing eligibility with
 * only a closed delivery-status update, no ACTIVE projection construction,
 * caller cancellation rethrown) plus QA-owned harness facts (R09c engine/
 * transport shapes, R06a owner shape, R07c hasOutstandingLocalWork Boolean?,
 * hand-written snapshot/pull literals, raw-SQL outbox/cache/cursor oracles,
 * createCustomExercise minimal-edit seam, coordinator-supplied
 * exerciseSummaries fenced-read seam, known SyncApiException subtypes
 * Transient/ReauthenticationRequired). No production source, diffs, Worker
 * output, LSP or decompiled code inspected. Deterministic only, no sleeps;
 * async coordination awaits entered gates/jobs under test timeouts, and the
 * in-flight write uses a gated transaction executor for an explicit
 * entered-then-invalidated rollback proof.
 *
 * Covers risk obligations 7 (process-only invalidation fencing and
 * transactional in-flight guard rollback at the narrowest real-Room layer)
 * and 8 (representative successful reconnect, admitted response invalidated
 * before checkpoint applies nothing, transient/reauth stops keep eligibility
 * queue and exact retry identity). Expected wire bytes are asserted as
 * frozen mutation/request-byte stability plus structural preservation, never
 * production encoder output; transient backoff bookkeeping columns are
 * excluded from identity on purpose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppSessionFencingReconnectTest {

    private val harness = AppSessionHarness("fencing")
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

    private fun stack(
        database: GymDatabase = db(),
        origin: ServiceOrigin? = AppSessionFixtures.origin(),
        clock: CountingClock = CountingClock(),
        vault: SessionVault? = null,
        transition: ScriptedTransitionBarrier? = null,
    ): Stack {
        val realVault = vault ?: harness.newVault()
        val auth = AppSessionAuthApi()
        val transport = AppSessionTransport()
        val owner = AppSessionFixtures.owner()
        val realTransition = transition ?: ScriptedTransitionBarrier()
        val publication = ScriptedPublicationBarrier()
        val coordinator = AppSessionFixtures.coordinator(
            database = database,
            origin = origin,
            vault = realVault,
            auth = auth,
            transport = transport,
            runOwner = owner,
            transitionBarrier = realTransition,
            publicationBarrier = publication,
            clock = clock,
        )
        return Stack(database, realVault, auth, transport, owner, realTransition, publication, coordinator)
    }

    private suspend fun readAuthority(database: GymDatabase): RecoveryRoomSnapshot =
        RoomSessionAuthority(database).read()

    private suspend fun Stack.activate(
        operationId: String = AppSessionFixtures.OP_FRESH,
        grant: ServiceSession = AppSessionFixtures.grant(),
    ): AppAccountSession {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val preparing = coordinator.acceptGrant(readAuthority(database), operationId, grant)
        assertTrue("preparation must stay Private, was $preparing", preparing is AppSessionState.Private)
        val active = coordinator.completeActivation(readAuthority(database), AppSessionFixtures.STAGE)
        assertTrue("completion must supply Active, was $active", active is AppSessionState.Active)
        return (active as AppSessionState.Active).session
    }

    private fun Stack.currentHandle(): AppAccountSession {
        val state = coordinator.state.value
        assertTrue("expected Active, was $state", state is AppSessionState.Active)
        return (state as AppSessionState.Active).session
    }

    // ---- Obligation 7: process-only invalidation fencing ----

    @Test
    fun processOnlyInvalidationFencesOldFacadeReadsAndWritesAtUnchangedGeneration() = runTest(timeout = 90.seconds) {
        val s = stack()
        val handle = s.activate()
        handle.repository.createCustomExercise("R09h Live", null, emptySet(), null, null, "")
        val journalBefore = harness.journalRow(s.database)
        val vaultBefore = s.vault.load()

        s.coordinator.invalidate()
        assertTrue(
            "invalidation must publish PrivacyLocked synchronously",
            s.coordinator.state.value is AppSessionState.PrivacyLocked,
        )
        // Durable generation is unchanged: fencing is process-local.
        assertEquals(journalBefore, harness.journalRow(s.database))
        assertEquals(vaultBefore, s.vault.load())
        // The old facade handle can neither check authority, read nor mutate.
        try {
            handle.checkCurrent()
            error("dead handle must not pass authority check")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        try {
            handle.repository.exerciseSummaries()
            error("dead facade read must refuse")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        try {
            handle.repository.createCustomExercise("R09h Dead Write", null, emptySet(), null, null, "")
            error("dead facade repository must refuse mutation")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        assertEquals(1L, harness.outboxCount(s.database, AppSessionFixtures.ACCOUNT))
        // A fresh recovery mints a NEW handle; the dead one stays dead.
        val revived = s.coordinator.recover()
        assertTrue("matching cache must revive, was $revived", revived is AppSessionState.Active)
        val fresh = (revived as AppSessionState.Active).session
        fresh.checkCurrent()
        assertTrue(fresh.repository.exerciseSummaries().map { it.exercise.name }.contains("R09h Live"))
        try {
            handle.checkCurrent()
            error("old handle must stay dead after revival")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
    }

    @Test
    fun writeEnteredInTransactionThenInvalidatedRollsBackExplicitly() = runTest(timeout = 90.seconds) {
        val gate = GatedTransactionExecutor()
        val database = harness.openGatedInMemoryDb(gate)
        dbs += database
        val s = stack(database = database)
        val handle = s.activate()
        val journalBefore = harness.journalRow(s.database)
        // Arm only now: activation transactions must run ungated.
        gate.gateNext = true

        val outcome = CompletableDeferred<Result<String>>()
        val job = launch {
            try {
                val id = handle.repository.createCustomExercise("R09h Raced", null, emptySet(), null, null, "")
                outcome.complete(Result.success(id))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        runCurrent()
        // Deterministic proof the write is parked INSIDE the Room transaction
        // machinery (bounded latch, no sleeps) before invalidation lands.
        assertTrue("write must enter the gated transaction", gate.entered())
        try {
            s.coordinator.invalidate()
            assertTrue(s.coordinator.state.value is AppSessionState.PrivacyLocked)
            gate.releaseLatch.countDown()
            job.join()
        // The in-transaction guard refuses the dead handle: exact stale, and
        // the uncommitted work rolls back with explicit durable proof.
        val failure = outcome.await().exceptionOrNull()
        assertTrue("in-flight dead write must fail, got $failure", failure != null)
        if (failure !is kotlinx.coroutines.CancellationException) {
            AppSessionFixtures.assertStale(failure!!)
        }
        assertEquals("rolled-back write leaves no outbox", 0L, harness.outboxCount(s.database, AppSessionFixtures.ACCOUNT))
        assertTrue(
            "rolled-back write leaves no domain row",
            harness.customNames(s.database, AppSessionFixtures.ACCOUNT).none { it.startsWith("R09h") },
        )
        assertEquals("rollback changes no journal", journalBefore, harness.journalRow(s.database))
        assertTrue(s.coordinator.state.value is AppSessionState.PrivacyLocked)
        try {
            handle.checkCurrent()
            error("raced handle must be dead")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        } finally {
            // Safety net so a failed oracle cannot park the executor thread forever.
            gate.releaseLatch.countDown()
        }
    }

    // ---- Obligation 8: successful reconnect without publication ----

    @Test
    fun successfulReconnectAppliesTerminalPullWithoutPublication() = runTest(timeout = 90.seconds) {
        val s = stack()
        s.activate()
        val epochBefore = checkNotNull(harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(AppSessionFixtures.BASELINE, harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT))
        val materializedBefore = harness.materializedEpoch(s.database, AppSessionFixtures.ACCOUNT)
        val vaultBefore = s.vault.load()
        val handle = s.currentHandle()

        val wireBefore = s.transport.capturedRequests.size
        s.transport.queueOk(AppSessionFixtures.PULL_CHANGE_BODY)
        val result = s.coordinator.reconnect(handle, epochBefore)
        assertEquals(false, result.hasOutstandingLocalWork)
        val state = s.coordinator.state.value
        assertTrue("successful reconnect must stay Active IDLE, was $state", state is AppSessionState.Active)
        assertEquals(AppSessionDeliveryStatus.IDLE, (state as AppSessionState.Active).deliveryStatus)
        // Bounded pull oracle over reconnect-scoped wire only (bootstrap calls precede it).
        val pull = s.transport.capturedRequests.subList(wireBefore, s.transport.capturedRequests.size).single()
        assertEquals("/v1/sync/pull", pull.path)
        assertEquals(
            listOf("cursor" to AppSessionFixtures.BASELINE, "limit" to "100"),
            pull.query,
        )
        assertEquals(mapOf("Authorization" to "Bearer ${AppSessionFixtures.ACCESS}"), pull.headers)
        assertNull(pull.body)
        // Checkpoint advanced but the batch uses without incrementing the
        // cache epoch, and no publication ran: materialized epoch and vault
        // are untouched while projection stays dirty for R12c.
        assertEquals(AppSessionFixtures.TAIL, harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(epochBefore, harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(1L, harness.projectionDirty(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(materializedBefore, harness.materializedEpoch(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(vaultBefore, s.vault.load())
        assertEquals(0, s.auth.refreshCalls)
        // R12c owns publication: the pulled change is canonical (positive
        // revision row) but not visible (no routine materialized).
        assertEquals(
            1L,
            harness.canonicalRevision(
                s.database,
                AppSessionFixtures.ACCOUNT,
                "routine",
                AppSessionFixtures.ROUTE_CHANGE,
            )?.first,
        )
        assertNull(handle.repository.routineDetails(AppSessionFixtures.ROUTE_CHANGE))
    }

    @Test
    fun admittedPullInvalidatedBeforeCheckpointAppliesNothing() = runTest(timeout = 90.seconds) {
        val s = stack()
        s.activate()
        val epoch = checkNotNull(harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        val vaultBefore = s.vault.load()
        val handle = s.currentHandle()
        assertEquals(AppSessionFixtures.BASELINE, harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT))

        // Hold the admitted 200 response before its checkpoint can commit.
        val admitted = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        s.transport.responseGate = {
            admitted.complete(Unit)
            hold.await()
        }
        s.transport.queueOk(AppSessionFixtures.PULL_CHANGE_BODY)
        val outcome = CompletableDeferred<Result<Any?>>()
        val job = launch {
            try {
                @Suppress("UNCHECKED_CAST")
                val result = s.coordinator.reconnect(handle, epoch) as Any?
                outcome.complete(Result.success(result))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        // Observe genuinely admitted I/O (real executors need the gate).
        admitted.await()
        s.coordinator.invalidate()
        assertTrue(s.coordinator.state.value is AppSessionState.PrivacyLocked)
        hold.complete(Unit)
        job.join()
        val result = outcome.await()
        assertTrue("dead reconnect must not report success, got $result", result.isFailure)
        val failure = result.exceptionOrNull()!!
        if (failure !is kotlinx.coroutines.CancellationException) {
            AppSessionFixtures.assertClosed(failure)
        }
        // No checkpoint, acknowledgment, or cleanup rode the dead handle.
        assertEquals(AppSessionFixtures.BASELINE, harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(0L, harness.outboxCount(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(vaultBefore, s.vault.load())
        assertTrue(s.coordinator.state.value is AppSessionState.PrivacyLocked)
    }

    // ---- Obligation 8: stops keep eligibility, queue and retry identity ----

    @Test
    fun transientReconnectStopKeepsEditingQueueAndExactRetryIdentity() = runTest(timeout = 90.seconds) {
        val clock = CountingClock()
        val s = stack(clock = clock)
        s.activate()
        val handle = s.currentHandle()
        handle.repository.createCustomExercise("R09h Fenced", null, emptySet(), null, null, "")
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(1, frozenBefore.size)
        val vaultBefore = s.vault.load()
        val epoch = checkNotNull(harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        val cursorBefore = harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT)
        val wireBefore = s.transport.capturedRequests.size
        s.transport.serve = { _, request ->
            if (request.path.contains("push")) {
                AppSessionTransport.Frame(500, """{"errorCode":"probe-transient"}""")
            } else {
                AppSessionTransport.Frame(200, AppSessionFixtures.TAIL_EMPTY_PULL_BODY)
            }
        }
        val result = s.coordinator.reconnect(handle, epoch)
        assertNull("push stop has no valid tail", result.hasOutstandingLocalWork)
        val state = s.coordinator.state.value
        assertTrue("transient stop must stay Active, was $state", state is AppSessionState.Active)
        assertEquals(AppSessionDeliveryStatus.RETRY_REQUIRED, (state as AppSessionState.Active).deliveryStatus)
        // Frozen mutation/request bytes survive; only backoff bookkeeping may move.
        assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
        assertEquals(cursorBefore, harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(epoch, harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(checkNotNull(vaultBefore.record).session, s.vault.load().record?.session)
        assertEquals(0, s.auth.refreshCalls)
        val pushes = s.transport.capturedRequests.subList(wireBefore, s.transport.capturedRequests.size)
            .filter { it.path.contains("push") }
        assertEquals(1, pushes.size)
        // Immediate retry honors retryAfter: no new HTTP before due.
        val retryAfter = checkNotNull(
            harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT).single()[11]?.toLong(),
            { "transient stop must record an absolute retryAfter" },
        )
        val wireAfterFirst = s.transport.capturedRequests.size
        val immediate = s.coordinator.reconnect(handle, epoch)
        assertNull("pre-due retry has no valid tail", immediate.hasOutstandingLocalWork)
        assertEquals("no HTTP before due", wireAfterFirst, s.transport.capturedRequests.size)
        assertEquals(
            AppSessionDeliveryStatus.RETRY_REQUIRED,
            (s.coordinator.state.value as AppSessionState.Active).deliveryStatus,
        )
        // At due (injected clock, no sleeps) the same frozen body is sent.
        clock.now = retryAfter
        val due = s.coordinator.reconnect(handle, epoch)
        assertNull("push stop has no valid tail", due.hasOutstandingLocalWork)
        val pushesAfter = s.transport.capturedRequests.subList(wireBefore, s.transport.capturedRequests.size)
            .filter { it.path.contains("push") }
        assertEquals(2, pushesAfter.size)
        assertEquals(pushes[0].body, pushesAfter[1].body)
        assertEquals(pushes[0].headers, pushesAfter[1].headers)
        assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
        // Cached editing eligibility is intact after the stop.
        handle.checkCurrent()
        handle.repository.createCustomExercise("R09h Still Editing", null, emptySet(), null, null, "")
        assertEquals(2L, harness.outboxCount(s.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun reauthenticationStopKeepsEditingQueueAndCredentials() = runTest(timeout = 90.seconds) {
        val s = stack()
        s.activate()
        val queuedHandle = s.currentHandle()
        queuedHandle.repository.createCustomExercise("R09h Reauth Queued", null, emptySet(), null, null, "")
        assertTrue(queuedHandle.repository.exerciseSummaries().map { it.exercise.name }.contains("R09h Reauth Queued"))
        // Expire only access: refresh stays live so the stop is typed
        // reauthentication rather than a transport failure.
        val loaded = s.vault.load()
        val live = checkNotNull(loaded.record)
        val expiredAccess = live.session.copy(accessExpiresAtMillis = AppSessionFixtures.FIXED_CLOCK - 1_000L)
        s.vault.compareAndSet(loaded.revision, live.copy(session = expiredAccess))
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
        val vaultBefore = s.vault.load()
        val epoch = checkNotNull(harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        val cursorBefore = harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT)
        val handle = s.currentHandle()

        s.auth.failWith(SyncApiException.ReauthenticationRequired())
        s.auth.failWith(SyncApiException.ReauthenticationRequired())
        val result = s.coordinator.reconnect(handle, epoch)
        assertNull("reauth stop has no valid tail", result.hasOutstandingLocalWork)
        val state = s.coordinator.state.value
        assertTrue("reauth stop must stay Active, was $state", state is AppSessionState.Active)
        assertEquals(
            AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED,
            (state as AppSessionState.Active).deliveryStatus,
        )
        // Queue and checkpoint survive; the expired-access attempt persists a
        // pending refresh BEFORE HTTP while binding/grant/archives stay put.
        assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
        assertEquals(cursorBefore, harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(epoch, harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        val persisted = s.vault.load()
        assertEquals(vaultBefore.record?.binding, persisted.record?.binding)
        assertEquals(vaultBefore.record?.session, persisted.record?.session)
        assertEquals(vaultBefore.record?.supersededSessions, persisted.record?.supersededSessions)
        val pending = checkNotNull(persisted.record?.pendingRefresh)
        assertTrue(
            pending.attempt.idempotencyKey.matches(
                Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
            ),
        )
        assertTrue(pending.attempt.requestJson.contains(AppSessionFixtures.REFRESH))
        // A subsequent retry leaves the persisted pending attempt unchanged.
        val retry = s.coordinator.reconnect(handle, epoch)
        assertNull("reauth stop has no valid tail", retry.hasOutstandingLocalWork)
        val again = checkNotNull(s.vault.load().record?.pendingRefresh)
        assertEquals(pending.attempt.idempotencyKey, again.attempt.idempotencyKey)
        assertEquals(pending.attempt.requestJson, again.attempt.requestJson)
        assertEquals(persisted.record?.session, s.vault.load().record?.session)
        assertEquals(
            AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED,
            (s.coordinator.state.value as AppSessionState.Active).deliveryStatus,
        )
        handle.checkCurrent()
        assertTrue(handle.repository.exerciseSummaries().map { it.exercise.name }.contains("R09h Reauth Queued"))
        handle.repository.createCustomExercise("R09h Reauth Editing", null, emptySet(), null, null, "")
    }

    @Test
    fun callerCancellationDuringReconnectIsRethrownNeverSuccessful() = runTest(timeout = 90.seconds) {
        val s = stack()
        s.activate()
        val handle = s.currentHandle()
        handle.repository.createCustomExercise("R09h Fenced", null, emptySet(), null, null, "")
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
        val epoch = checkNotNull(harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))

        val admitted = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        s.transport.responseGate = {
            admitted.complete(Unit)
            hold.await()
        }
        s.transport.queueOk(AppSessionFixtures.PULL_CHANGE_BODY)
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.coordinator.reconnect(handle, epoch)
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        admitted.await()
        val probe = kotlinx.coroutines.CancellationException("r09h-reconnect-cancel")
        job.cancel(probe)
        hold.complete(Unit)
        job.join()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        assertEquals("cancelled reconnect must not checkpoint", frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
        assertEquals(AppSessionFixtures.BASELINE, harness.syncStateCursor(s.database, AppSessionFixtures.ACCOUNT))
        // Cancellation is not authority revocation: the SAME healthy Active,
        // its queue and read/edit eligibility all survive, as for a cancelled
        // drain.
        val state = s.coordinator.state.value
        assertTrue("cancelled reconnect must preserve Active, was $state", state is AppSessionState.Active)
        val same = (state as AppSessionState.Active).session
        same.checkCurrent()
        assertTrue(same.repository.exerciseSummaries().map { it.exercise.name }.contains("R09h Fenced"))
        same.repository.createCustomExercise("R09h Cancel Kept", null, emptySet(), null, null, "")
        assertEquals(2L, harness.outboxCount(s.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun reconnectNeverConstructsOrPublishesAnActiveProjection() = runTest(timeout = 90.seconds) {
        val s = stack()
        s.activate()
        val epochBefore = checkNotNull(harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        val materializedBefore = harness.materializedEpoch(s.database, AppSessionFixtures.ACCOUNT)
        val handle = s.currentHandle()
        s.transport.serve = { _, request ->
            if (request.path.contains("push")) {
                AppSessionTransport.Frame(500, """{"errorCode":"probe-transient"}""")
            } else {
                AppSessionTransport.Frame(200, AppSessionFixtures.TAIL_EMPTY_PULL_BODY)
            }
        }
        s.coordinator.reconnect(handle, epochBefore)
        // R12c owns publication: reconnect advances no materialization epoch.
        assertEquals(epochBefore, harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(materializedBefore, harness.materializedEpoch(s.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun invalidationAfterExercisesInsertRollsBackWholeTransaction() = runTest(timeout = 90.seconds) {
        // Direct Room query callback: observe the exercises INSERT, then fire
        // invalidation synchronously on the very next query. Callbacks run
        // before their SQL executes, so the fault lands strictly post-write
        // inside the still-uncommitted scoped transaction.
        val armed = AtomicBoolean(false)
        val insertSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        val coordinatorRef = AtomicReference<AppSessionCoordinator>()
        val database = Room.inMemoryDatabaseBuilder(
            harness.context.applicationContext,
            GymDatabase::class.java,
        ).setQueryCallback({ sql, _ ->
            val q = sql.lowercase()
            if (armed.get()) {
                if (!insertSeen.get() && q.contains("insert") && q.contains("exercises")) {
                    insertSeen.set(true)
                } else if (insertSeen.get() && fired.compareAndSet(false, true)) {
                    coordinatorRef.get()?.invalidate()
                }
            }
        }, Executor { it.run() }).build()
        dbs += database
        val s = stack(database = database)
        coordinatorRef.set(s.coordinator)
        val handle = s.activate()
        val journalBefore = harness.journalRow(s.database)
        armed.set(true)

        val outcome = CompletableDeferred<Result<String>>()
        val job = launch {
            try {
                val id = handle.repository.createCustomExercise("R09h Post Write", null, emptySet(), null, null, "")
                outcome.complete(Result.success(id))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        job.join()
        // The hook fired strictly after the INSERT was observed...
        assertTrue("exercises insert must be observed", insertSeen.get())
        assertTrue("invalidation must fire on the following query", fired.get())
        val failure = outcome.await().exceptionOrNull()
        assertTrue("post-write invalidation must fail stale, got $failure", failure != null)
        AppSessionFixtures.assertStale(failure!!)
        // ...and the whole transaction rolled back: zero domain and outbox.
        assertEquals(0L, harness.outboxCount(s.database, AppSessionFixtures.ACCOUNT))
        assertTrue(harness.customNames(s.database, AppSessionFixtures.ACCOUNT).isEmpty())
        assertEquals(journalBefore, harness.journalRow(s.database))
        assertTrue(s.coordinator.state.value is AppSessionState.PrivacyLocked)
    }

    @Test
    fun cancellationAfterLeaseAcquiredClosesLeaseOnceAndStaysPrivate() = runTest(timeout = 90.seconds) {
        // Distinct from cancelling while acquire never yields: here the drain
        // lease IS held and the Room reservation is done; only the vault
        // write is gated. Cancellation must still win without dead success.
        val tLease = AppSessionTestLease()
        val transition = ScriptedTransitionBarrier(tLease)
        val gatedVault = GatedCasVault(harness.newVault())
        val s = stack(vault = gatedVault, transition = transition)
        val handle = s.activate()
        assertEquals("activation lease closes once", 1, tLease.closeCount)
        gatedVault.gateNext = true
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.coordinator.acceptGrant(
                    readAuthority(s.database),
                    AppSessionFixtures.OP_OTHER,
                    AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED),
                )
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        // CAS entered: reservation committed, transition lease held.
        gatedVault.entered.await()
        val closeBefore = tLease.closeCount
        val probe = kotlinx.coroutines.CancellationException("r09h-lease-cancel")
        job.cancel(probe)
        gatedVault.release.complete(Unit)
        job.join()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        // The acquired lease is always closed exactly once, even on cancel.
        assertEquals(closeBefore + 1, tLease.closeCount)
        // No dead success: never Active with a new handle.
        assertTrue(s.coordinator.state.value !is AppSessionState.Active)
        try {
            handle.checkCurrent()
            error("superseded handle must stay dead")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
    }

    @Test
    fun reconnectWithClearedVaultBindingFailsClosedWithoutHttp() = runTest(timeout = 90.seconds) {
        // Runtime facade error composition (not startup mismatch): the vault
        // binding is cleared AFTER activation, then reconnect must fail
        // closed before admitting any HTTP. A primitive refresh failure alone
        // is never authority visibility; valid Transient/Reauth stops keep
        // editing instead (proven by the neighbouring stop tests).
        val s = stack()
        val handle = s.activate()
        handle.repository.createCustomExercise("R09h Bound", null, emptySet(), null, null, "")
        val journalBefore = harness.journalRow(s.database)
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
        val epoch = checkNotNull(harness.cacheEpoch(s.database, AppSessionFixtures.ACCOUNT))
        val wireBefore = s.transport.capturedRequests.size
        val loaded = s.vault.load()
        s.vault.compareAndSet(loaded.revision, null)

        try {
            s.coordinator.reconnect(handle, epoch)
            error("binding-fault reconnect must throw stale")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        // No HTTP admitted and the facade locks immediately...
        assertEquals(wireBefore, s.transport.capturedRequests.size)
        assertTrue(s.coordinator.state.value is AppSessionState.PrivacyLocked)
        try {
            handle.checkCurrent()
            error("faulted handle must be dead")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        try {
            handle.repository.exerciseSummaries()
            error("faulted facade read must refuse")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        try {
            handle.repository.createCustomExercise("R09h Faulted Write", null, emptySet(), null, null, "")
            error("faulted facade write must refuse")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        // ...while Room generation, queue and domain stay untouched.
        assertEquals(journalBefore, harness.journalRow(s.database))
        assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
        assertEquals(listOf("R09h Bound"), harness.customNames(s.database, AppSessionFixtures.ACCOUNT))
    }

    /** Vault CAS entry gate: observes the held post-reservation write, then releases. */
    private class GatedCasVault(private val delegate: SessionVault) : SessionVault {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile
        var gateNext = false

        override suspend fun load(): VaultSnapshot = delegate.load()

        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            if (gateNext) {
                gateNext = false
                entered.complete(Unit)
                release.await()
            }
            return delegate.compareAndSet(expectedRevision, record)
        }
    }
}
