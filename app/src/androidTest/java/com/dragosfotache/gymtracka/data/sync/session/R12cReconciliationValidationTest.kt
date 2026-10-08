package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R12c independent QA: ACTIVE reconcile argument validation, retained-stage
 * refusal and result redaction (outcomes 5/6 contract slice).
 *
 * Hand-written from docs/remaining-work/R12c-task.md only (fixed TARGET
 * contract in com.dragosfotache.gymtracka.data.sync.session): negative epoch
 * and out-of-range push/pull/snapshot budgets throw IllegalArgumentException
 * before credential/store work; a zero epoch against a nonzero durable epoch
 * is a stale refusal; malformed/mismatching/nonexistent restartStageId throws
 * AppSessionException(IDENTITY_MISMATCH) preserving stage/data with no HTTP.
 * Result fields are observations with fixed/redacted toString. Real
 * in-memory Room plus QA-owned harness/transport/auth fixtures;
 * independently written literals only; no sleeps, no production oracles.
 */
@RunWith(AndroidJUnit4::class)
class R12cReconciliationValidationTest {
    private val harness = AppSessionHarness("r12c-reconcile")
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
        return (active as AppSessionState.Active).session
    }

    private suspend fun Stack.attemptReconcile(
        session: AppAccountSession,
        expectedCacheEpoch: Long,
        maxPushMutations: Int = 100,
        maxPullPages: Int = 100,
        pullPageLimit: Int = 100,
        maxSnapshotPages: Int = 100,
        snapshotPageLimit: Int = 200,
        restartStageId: String? = null,
    ): Result<AppReconciliationResult> = runCatching {
        coordinator.reconcile(
            session = session,
            expectedCacheEpoch = expectedCacheEpoch,
            maxPushMutations = maxPushMutations,
            maxPullPages = maxPullPages,
            pullPageLimit = pullPageLimit,
            maxSnapshotPages = maxSnapshotPages,
            snapshotPageLimit = snapshotPageLimit,
            restartStageId = restartStageId,
        )
    }

    private fun Stack.served() = transport.capturedRequests.size

    private suspend fun Stack.epoch(session: AppAccountSession) = coordinator.observeCacheEpoch(session)

    private fun Stack.preservationSnapshot() = buildList {
        add(harness.journalRow(database).toString())
        add(harness.deviceRow(database).toString())
        add(harness.outboxSnapshot(database, AppSessionFixtures.ACCOUNT).toString())
        add(harness.cacheEpoch(database, AppSessionFixtures.ACCOUNT).toString())
    }

    @Test
    fun negativeEpochThrowsIllegalArgumentWithoutTransportIo() = runTest {
        val stack = stack()
        val session = stack.activate()
        val servedBefore = stack.served()
        val error = stack.attemptReconcile(session, expectedCacheEpoch = -1L).exceptionOrNull()
        assertTrue("negative epoch must throw IllegalArgumentException, was $error", error is IllegalArgumentException)
        assertEquals(servedBefore, stack.served())
    }

    @Test
    fun obsoleteZeroEpochRefusesUnavailableKeepingLiveHandle() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        assertTrue("R12c validation needs a nonzero durable epoch, was $epoch", epoch != 0L)
        val servedBefore = stack.served()
        val preservedBefore = stack.preservationSnapshot()
        val error = stack.attemptReconcile(session, expectedCacheEpoch = 0L).exceptionOrNull()
        // Obsolete nonnegative epoch on a valid current handle is a closed
        // unavailable/retry-eligible refusal (reconnect classification),
        // never stale-handle revocation.
        AppSessionFixtures.assertRefusal(
            checkNotNull(error) { "obsolete zero epoch must refuse, not converge" },
            AppSessionReason.UNAVAILABLE,
        )
        assertEquals(servedBefore, stack.served())
        assertEquals(preservedBefore, stack.preservationSnapshot())
        val state = stack.coordinator.state.value
        assertTrue("live handle must survive, was $state", state is AppSessionState.Active)
        assertEquals(session.identity.binding.accountId, (state as AppSessionState.Active).session.identity.binding.accountId)
        assertEquals(session.identity.binding.sessionGeneration, state.session.identity.binding.sessionGeneration)
    }

    @Test
    fun zeroBudgetsThrowIllegalArgumentWithoutTransportIo() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        val servedBefore = stack.served()
        val failures = listOf(
            stack.attemptReconcile(session, epoch, maxPushMutations = 0),
            stack.attemptReconcile(session, epoch, maxPullPages = 0),
            stack.attemptReconcile(session, epoch, pullPageLimit = 0),
            stack.attemptReconcile(session, epoch, maxSnapshotPages = 0),
            stack.attemptReconcile(session, epoch, snapshotPageLimit = 0),
        )
        failures.forEach { result ->
            val error = result.exceptionOrNull()
            assertTrue("zero budget must throw IllegalArgumentException, was $error", error is IllegalArgumentException)
        }
        assertEquals(servedBefore, stack.served())
    }

    @Test
    fun overMaxBudgetsThrowIllegalArgumentWithoutTransportIo() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        val servedBefore = stack.served()
        val failures = listOf(
            stack.attemptReconcile(session, epoch, maxPushMutations = 101),
            stack.attemptReconcile(session, epoch, maxPullPages = 101),
            stack.attemptReconcile(session, epoch, pullPageLimit = 201),
            stack.attemptReconcile(session, epoch, maxSnapshotPages = 101),
            stack.attemptReconcile(session, epoch, snapshotPageLimit = 501),
        )
        failures.forEach { result ->
            val error = result.exceptionOrNull()
            assertTrue("over-max budget must throw IllegalArgumentException, was $error", error is IllegalArgumentException)
        }
        assertEquals(servedBefore, stack.served())
    }

    @Test
    fun malformedRestartUuidRefusesIdentityMismatchPreservingData() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        val servedBefore = stack.served()
        val preservedBefore = stack.preservationSnapshot()
        listOf("not-a-uuid", "").forEach { restart ->
            val error = stack.attemptReconcile(session, epoch, restartStageId = restart).exceptionOrNull()
            AppSessionFixtures.assertRefusal(
                checkNotNull(error) { "malformed restart <$restart> must refuse, not converge" },
                AppSessionReason.IDENTITY_MISMATCH,
            )
        }
        assertEquals(servedBefore, stack.served())
        assertEquals(preservedBefore, stack.preservationSnapshot())
    }

    @Test
    fun nonexistentRestartStageRefusesIdentityMismatchPreservingData() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        val servedBefore = stack.served()
        val preservedBefore = stack.preservationSnapshot()
        val error = stack.attemptReconcile(
            session,
            epoch,
            restartStageId = "e1200001-1111-4111-8111-111111111111",
        ).exceptionOrNull()
        AppSessionFixtures.assertRefusal(
            checkNotNull(error) { "nonexistent restart stage must refuse, not converge" },
            AppSessionReason.IDENTITY_MISMATCH,
        )
        assertEquals(servedBefore, stack.served())
        assertEquals(preservedBefore, stack.preservationSnapshot())
    }

    @Test
    fun successfulTailResultIsFixedAndRedacted() = runTest {
        val stack = stack()
        val session = stack.activate()
        val epoch = stack.epoch(session)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val result = stack.attemptReconcile(session, epoch).getOrThrow()
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertEquals(epoch, result.cacheEpoch)
        AppSessionFixtures.assertRedacted(result.toString())
    }
}
