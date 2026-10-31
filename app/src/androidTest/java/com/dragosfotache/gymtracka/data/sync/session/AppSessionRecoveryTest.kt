package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * app-facing recovery and activation boundary, real Room.
 *
 * Covers risk obligations 1 (constructor/unconfigured privacy without I/O),
 * 2 (real-Room preparation to ACTIVE with no premature handle) and 5
 * (exact gap promotion; mismatched/missing/incomplete stay private).
 * Obligation 3's reconnect half lives in AppSessionFencingReconnectTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppSessionRecoveryTest {

    private val harness = AppSessionHarness("recovery")
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
        val clock: CountingClock,
        val uuids: CountingUuids,
        val coordinator: AppSessionCoordinator,
    )

    private fun stack(
        database: GymDatabase = db(),
        origin: ServiceOrigin? = AppSessionFixtures.origin(),
        vault: SessionVault = harness.newVault(),
        clock: CountingClock = CountingClock(),
        uuids: CountingUuids = CountingUuids(),
    ): Stack {
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
            clock = clock,
            uuids = uuids,
        )
        return Stack(database, vault, auth, transport, owner, transition, publication, clock, uuids, coordinator)
    }

    private suspend fun readAuthority(database: GymDatabase): RecoveryRoomSnapshot =
        RoomSessionAuthority(database).read()

    /** Full boundary activation with granting barriers and empty cloud bodies. */
    private suspend fun Stack.activate(
        operationId: String = AppSessionFixtures.OP_FRESH,
        stageId: String = AppSessionFixtures.STAGE,
        grant: ServiceSession = AppSessionFixtures.grant(),
    ): AppAccountSession {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val preparing = coordinator.acceptGrant(readAuthority(database), operationId, grant)
        assertTrue("preparation evidence must stay Private, was $preparing", preparing is AppSessionState.Private)
        val active = coordinator.completeActivation(readAuthority(database), stageId)
        assertTrue("completion must supply Active, was $active", active is AppSessionState.Active)
        return (active as AppSessionState.Active).session
    }

    private suspend fun summaries(handle: AppAccountSession): List<String> =
        handle.repository.exerciseSummaries().map { it.exercise.name }

    // ---- Obligation 1: constructor and unconfigured initial use ----

    @Test
    fun constructorIsPrivacyLockedWithNoSeamActivity() = runTest {
        val database = db()
        val delegate = harness.newVault()
        val vault = CountingVault(delegate)
        val s = stack(database = database, vault = vault)
        assertTrue(s.coordinator.state.value is AppSessionState.PrivacyLocked)
        vault.assertSilent("constructor")
        assertEquals("constructor: auth", 0, s.auth.refreshCalls)
        assertEquals("constructor: transport", 0, s.transport.capturedRequests.size)
        assertEquals("constructor: clock", 0, s.clock.calls)
        assertEquals("constructor: uuid", 0, s.uuids.calls)
        assertEquals("constructor: transition", 0, s.transition.acquires)
        assertEquals("constructor: publication", 0, s.publication.acquires)
    }

    @Test
    fun unconfiguredRecoverStaysPrivateWithoutRoomVaultClockUuidOrHttp() = runTest {
        val database = db()
        val delegate = harness.newVault()
        val vault = CountingVault(delegate)
        val s = stack(database = database, origin = null, vault = vault)
        val state = s.coordinator.recover()
        assertTrue("unconfigured must be Private, was $state", state is AppSessionState.Private)
        val private = state as AppSessionState.Private
        assertEquals(AppSessionReason.CONFIGURATION_UNAVAILABLE, private.reason)
        assertNull(private.room)
        vault.assertSilent("unconfigured recover")
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(0, s.transport.capturedRequests.size)
        assertEquals(0, s.clock.calls)
        assertEquals(0, s.uuids.calls)
        assertEquals(0, s.transition.acquires)
        assertEquals(0, s.publication.acquires)
        AppSessionFixtures.assertRedacted(state.toString())
    }

    @Test
    fun unconfiguredRecoverWithClosedDbStaysPrivateWithoutRoomIo() = runTest {
        // A closed Room database throws on any access: returning Private here
        // proves unconfigured recover performs no Room I/O either.
        val database = db()
        dbs.remove(database)
        database.close()
        val delegate = harness.newVault()
        val vault = CountingVault(delegate)
        val s = stack(database = database, origin = null, vault = vault)
        val state = s.coordinator.recover()
        assertTrue("unconfigured must be Private, was $state", state is AppSessionState.Private)
        assertEquals(AppSessionReason.CONFIGURATION_UNAVAILABLE, (state as AppSessionState.Private).reason)
        assertNull(state.room)
        vault.assertSilent("closed-db unconfigured recover")
        assertEquals(0, s.auth.refreshCalls)
        assertEquals(0, s.transport.capturedRequests.size)
        assertEquals(0, s.clock.calls)
        assertEquals(0, s.uuids.calls)
        AppSessionFixtures.assertRedacted(state.toString())
    }

    // ---- Obligation 2: real-Room preparation to ACTIVE, no premature handle ----

    @Test
    fun realRoomPreparationToActivePublishesHandleOnlyAfterFreshReconcile() = runTest(timeout = 90.seconds) {
        val s = stack()
        val grant = AppSessionFixtures.grant()
        val handle = s.activate(grant = grant)
        handle.checkCurrent()
        // Fenced offline read proves handle authority over scoped data.
        handle.repository.createCustomExercise("session Active Proof", null, emptySet(), null, null, "")
        assertTrue(summaries(handle).contains("session Active Proof"))
        // Durable oracles: journal ACTIVE, device ready, vault ACTIVE.
        val journal = harness.journalRow(s.database)
        assertEquals("active", journal[2])
        assertEquals(AppSessionFixtures.ACCOUNT, journal[4])
        assertEquals(AppSessionFixtures.OP_FRESH, journal[5])
        val device = harness.deviceRow(s.database)
        assertEquals(AppSessionFixtures.ACCOUNT, device[0])
        assertEquals(AppSessionFixtures.ACCOUNT, device[1])
        val record = checkNotNull(s.vault.load().record)
        assertEquals(VaultSessionPhase.ACTIVE, record.phase)
        assertEquals(AppSessionFixtures.ACCOUNT, record.binding.accountId)
        assertEquals(grant, record.session)
        // Bounded bootstrap HTTP: snapshot then pull then completion tail.
        val paths = s.transport.capturedRequests.map { it.path }
        assertEquals(listOf("/v1/sync/snapshot", "/v1/sync/pull", "/v1/sync/pull"), paths)
        assertEquals(0, s.auth.refreshCalls)
        AppSessionFixtures.assertRedacted(s.coordinator.state.value.toString())
        AppSessionFixtures.assertRedacted(handle.toString())
    }

    @Test
    fun noPrematureHandleOnBootstrapStopOrFailedBarrier() = runTest(timeout = 90.seconds) {
        // Case A: bootstrap transport stop stays Private (typed progress).
        run {
            val s = stack()
            s.transport.queueCode(500, """{"errorCode":"probe-transient"}""")
            val preparing = s.coordinator.acceptGrant(
                readAuthority(s.database), AppSessionFixtures.OP_FRESH, AppSessionFixtures.grant(),
            )
            assertTrue(preparing is AppSessionState.Private)
            val stopped = s.coordinator.completeActivation(readAuthority(s.database), AppSessionFixtures.STAGE)
            assertTrue("bootstrap stop must stay Private, was $stopped", stopped is AppSessionState.Private)
            assertTrue(s.coordinator.state.value !is AppSessionState.Active)
            assertEquals(VaultSessionPhase.PREPARED, s.vault.load().record?.phase)
            assertEquals("preparing", harness.journalRow(s.database)[2])
        }
        // Case B: failed publication seam throws UNAVAILABLE with NO journal increment.
        run {
            val s = stack()
            s.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
            s.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            s.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            s.publication.acquireFaults += RuntimeException("session-publication-fault")
            val preparing = s.coordinator.acceptGrant(
                readAuthority(s.database), AppSessionFixtures.OP_FRESH, AppSessionFixtures.grant(),
            )
            assertTrue(preparing is AppSessionState.Private)
            val journalBefore = harness.journalRevision(s.database)
            try {
                s.coordinator.completeActivation(readAuthority(s.database), AppSessionFixtures.STAGE)
                error("failed publication seam must throw")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertRefusal(e, AppSessionReason.UNAVAILABLE)
            }
            assertTrue(s.coordinator.state.value !is AppSessionState.Active)
            assertEquals(VaultSessionPhase.PREPARED, s.vault.load().record?.phase)
            assertEquals("failed barrier commits no completion", journalBefore, harness.journalRevision(s.database))
            // The operation stays private until explicit recovery.
            val retry = s.coordinator.recover()
            assertTrue("failed completion must recover Private, was $retry", retry is AppSessionState.Private)
        }
    }

    // ---- Obligation 3 (recover half): matching ACTIVE physically reopens offline ----

    @Test
    fun physicalReopenOfMatchingActiveWithExpiredCredsAndQueuedEditsStaysOffline() = runTest(timeout = 90.seconds) {
        val dbName = "session-reopen-recovery"
        val firstDb = harness.createFileDb(dbName)
        dbs += firstDb
        val first = stack(database = firstDb)
        val handle = first.activate()
        handle.repository.createCustomExercise("session Queued", null, emptySet(), null, null, "")
        assertEquals(1L, harness.outboxCount(firstDb, AppSessionFixtures.ACCOUNT))
        assertTrue(summaries(handle).contains("session Queued"))
        // Retire the old controller first, then close the physical DB.
        first.coordinator.invalidate()
        firstDb.close()
        dbs.remove(firstDb)

        // Reopen NEW objects over the same physical DB path and vault namespace.
        val secondDb = harness.reopenFileDb(dbName)
        dbs += secondDb
        val expiredClock = CountingClock(now = AppSessionFixtures.GRANT_EXPIRY + 60_000L)
        // Disjoint UUIDs: the retained outbox already owns the default sequence
        // head, and mutation_id uniqueness is durable across the reopen.
        val expiredUuids = CountingUuids(
            listOf(
                "e0900010-1010-4110-8110-101010101010",
                "e0900011-1111-4111-8111-111111111112",
            ),
        )
        val second = stack(database = secondDb, vault = harness.reopenVault(), clock = expiredClock, uuids = expiredUuids)
        // Same-account queued intent survived the physical reopen.
        assertEquals(1L, harness.outboxCount(secondDb, AppSessionFixtures.ACCOUNT))
        val state = second.coordinator.recover()
        assertTrue("matching ACTIVE must recover offline, was $state", state is AppSessionState.Active)
        val active = state as AppSessionState.Active
        assertEquals(AppSessionDeliveryStatus.IDLE, active.deliveryStatus)
        assertEquals(0, second.auth.refreshCalls)
        assertEquals(0, second.transport.capturedRequests.size)
        assertEquals("offline recovery must not consult clock", 0, expiredClock.calls)
        assertEquals("offline recovery must not mint UUIDs", 0, expiredUuids.calls)
        // Offline fenced read authority + new same-account edit without HTTP.
        active.session.checkCurrent()
        assertTrue(summaries(active.session).contains("session Queued"))
        active.session.repository.createCustomExercise("session Offline New", null, emptySet(), null, null, "")
        assertEquals(0, second.transport.capturedRequests.size)
        assertEquals(0, second.auth.refreshCalls)
        assertEquals(2L, harness.outboxCount(secondDb, AppSessionFixtures.ACCOUNT))
        val queued = harness.outboxSnapshot(secondDb, AppSessionFixtures.ACCOUNT)
        assertTrue(queued.all { it[2] == AppSessionFixtures.ACCOUNT })
        assertEquals(
            listOf("session Offline New", "session Queued"),
            summaries(active.session).filter { it.startsWith("session") },
        )
    }

    // ---- Obligation 5: exact ACTIVE/PREPARED gap promotes; others stay private ----

    @Test
    fun exactCompletedGapPromotesWhileMismatchedMissingIncompleteStayPrivate() = runTest(timeout = 90.seconds) {
        // Exact gap: Room ACTIVE committed, vault promotion interrupted.
        run {
            val s = stack()
            s.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
            s.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            s.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            val preparing = s.coordinator.acceptGrant(
                readAuthority(s.database), AppSessionFixtures.OP_FRESH, AppSessionFixtures.grant(),
            )
            assertTrue(preparing is AppSessionState.Private)
            // Retire the first controller: durable preparation survives, and no
            // two coordinators stay live over the same DB/vault pair.
            s.coordinator.invalidate()
            val failingVault = FailPromotionOnceVault(s.vault)
            val racing = AppSessionFixtures.coordinator(
                database = s.database,
                origin = AppSessionFixtures.origin(),
                vault = failingVault,
                auth = s.auth,
                transport = s.transport,
                runOwner = s.owner,
                transitionBarrier = s.transition,
                publicationBarrier = s.publication,
                clock = s.clock,
                uuids = s.uuids,
            )
            try {
                racing.completeActivation(readAuthority(s.database), AppSessionFixtures.STAGE)
                error("interrupted promotion must not report success")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertRefusal(e, AppSessionReason.UNAVAILABLE)
            }
            // Retire the racing controller before startup recovery.
            racing.invalidate()
            assertEquals("gap: Room must be ACTIVE", "active", harness.journalRow(s.database)[2])
            assertEquals("gap: vault must stay PREPARED", VaultSessionPhase.PREPARED, s.vault.load().record?.phase)
            // Fresh reconciliation promotes exactly this completed gap.
            val promoted = s.coordinator.recover()
            assertTrue("exact gap must promote to Active, was $promoted", promoted is AppSessionState.Active)
            val fresh = (promoted as AppSessionState.Active).session
            fresh.checkCurrent()
            assertEquals(VaultSessionPhase.ACTIVE, s.vault.load().record?.phase)
            assertEquals(AppSessionFixtures.ACCOUNT, s.vault.load().record?.binding?.accountId)
        }
        // Mismatched gap: ACTIVE Room for A, PREPARED vault for B stays private.
        run {
            val s = stack()
            s.activate()
            val rev = s.vault.load().revision
            val foreign = AppSessionFixtures.grant(accountId = AppSessionFixtures.OTHER_ACCOUNT)
            s.vault.compareAndSet(
                rev,
                VaultSessionRecord(
                    binding = AppSessionFixtures.binding(accountId = AppSessionFixtures.OTHER_ACCOUNT),
                    operationId = AppSessionFixtures.OP_OTHER,
                    phase = VaultSessionPhase.PREPARED,
                    session = foreign,
                    pendingRefresh = null,
                ),
            )
            val journalBefore = harness.journalRow(s.database)
            // Retire the Active controller: Active recover revalidates the live
            // handle and never rereads the vault, so fresh startup needs a
            // retired (or new) controller to fail closed on the vault gap.
            s.coordinator.invalidate()
            val state = s.coordinator.recover()
            assertTrue("mismatched gap must stay Private, was $state", state is AppSessionState.Private)
            assertEquals(AppSessionReason.IDENTITY_MISMATCH, (state as AppSessionState.Private).reason)
            assertEquals(journalBefore, harness.journalRow(s.database))
            assertEquals(AppSessionFixtures.OTHER_ACCOUNT, s.vault.load().record?.binding?.accountId)
        }
        // Missing credentials stay private with no repair.
        run {
            val s = stack()
            s.activate()
            val rev = s.vault.load().revision
            s.vault.compareAndSet(rev, null)
            val journalBefore = harness.journalRow(s.database)
            // Same fresh-startup rule as the mismatched gap above.
            s.coordinator.invalidate()
            val state = s.coordinator.recover()
            assertTrue("missing credentials must stay Private, was $state", state is AppSessionState.Private)
            assertEquals(journalBefore, harness.journalRow(s.database))
            assertNull(s.vault.load().record)
        }
        // Incomplete preparation never auto-restarts or invents credentials.
        run {
            val s = stack()
            val preparing = s.coordinator.acceptGrant(
                readAuthority(s.database), AppSessionFixtures.OP_FRESH, AppSessionFixtures.grant(),
            )
            assertTrue(preparing is AppSessionState.Private)
            val journalBefore = harness.journalRow(s.database)
            val vaultBefore = s.vault.load()
            val state = s.coordinator.recover()
            assertTrue("incomplete preparation must stay Private, was $state", state is AppSessionState.Private)
            assertEquals(AppSessionReason.PREPARATION_REQUIRED, (state as AppSessionState.Private).reason)
            assertEquals(journalBefore, harness.journalRow(s.database))
            assertEquals(vaultBefore, s.vault.load())
            assertEquals(0, s.auth.refreshCalls)
            assertEquals(0, s.transport.capturedRequests.size)
        }
    }

    /** Counting SessionVault delegate proving zero coordinator I/O. */
    private class CountingVault(private val delegate: SessionVault) : SessionVault {
        var loads = 0
        var commits = 0

        fun assertSilent(label: String) {
            assertEquals("$label: vault loads", 0, loads)
            assertEquals("$label: vault commits", 0, commits)
        }

        override suspend fun load(): VaultSnapshot {
            loads += 1
            return delegate.load()
        }

        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            commits += 1
            return delegate.compareAndSet(expectedRevision, record)
        }
    }

    /** Fails the next vault CAS once with a storage fault (not stale), so Room commits while promotion does not. */
    private class FailPromotionOnceVault(private val delegate: SessionVault) : SessionVault {
        var failed = false

        override suspend fun load(): VaultSnapshot = delegate.load()

        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            if (!failed) {
                failed = true
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            return delegate.compareAndSet(expectedRevision, record)
        }
    }
}
