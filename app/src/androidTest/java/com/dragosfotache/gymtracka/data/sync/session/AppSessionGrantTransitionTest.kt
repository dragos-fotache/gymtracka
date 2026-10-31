package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * grant handling and transition/drain fencing, real Room.
 *
 * Covers risk obligations 4 (same-account preservation; different-UUID and
 * legacy-local refusal unchanged) and 6 (failed drain, drain/network/Room/
 * vault invalidation and cancellation fences, delayed grants).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppSessionGrantTransitionTest {

    private val harness = AppSessionHarness("grants")
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

    private suspend fun summaries(handle: AppAccountSession): List<String> =
        handle.repository.exerciseSummaries().map { it.exercise.name }

    // ---- Obligation 4: same-account preservation; foreign/local refusal ----

    @Test
    fun sameAccountFreshGrantPreservesPendingDomainAndUncertainEvidence() = runTest(timeout = 90.seconds) {
        val s = stack()
        val handle = s.activate()
        handle.repository.createCustomExercise("session Keep Me", null, emptySet(), null, null, "")
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
        assertEquals(1, frozenBefore.size)
        // Uncertain encrypted evidence: ACTIVE record carrying an unresolved
        // pending refresh attempt with exact independent bytes.
        val current = checkNotNull(s.vault.load().record)
        val vaultRev = s.vault.load().revision
        val uncertain = current.copy(
            pendingRefresh = VaultPendingRefresh(
                operationId = current.operationId,
                attempt = ServiceRefreshAttempt.fromJson(AppSessionFixtures.KEY_FRESH, AppSessionFixtures.PENDING_BODY),
            ),
        )
        s.vault.compareAndSet(vaultRev, uncertain)

        val freshGrant = AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED, refresh = AppSessionFixtures.REFRESH_ROTATED)
        val state = s.coordinator.acceptGrant(readAuthority(s.database), AppSessionFixtures.OP_OTHER, freshGrant)
        assertTrue("same-account recovery evidence must stay Private, was $state", state is AppSessionState.Private)
        // Pending queue and domain survive with identical frozen identity.
        // The old handle is dead, so domain is proven via the SQL oracle.
        assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
        assertEquals(listOf("session Keep Me"), harness.customNames(s.database, AppSessionFixtures.ACCOUNT))
        // Uncertain predecessor evidence is retained and fenced, never dropped.
        val retained = checkNotNull(s.vault.load().record?.supersededSessions)
        assertTrue("superseded evidence must be retained, was $retained", retained.entries.isNotEmpty())
        val archived = retained.entries.single()
        assertEquals(AppSessionFixtures.PENDING_BODY, archived.record.pendingRefresh?.attempt?.requestJson)
        // The superseded handle is dead; the fresh grant installs only as
        // preparation, never as an app handle.
        try {
            handle.checkCurrent()
            error("superseded handle must be dead")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        try {
            handle.repository.exerciseSummaries()
            error("superseded facade read must refuse")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        assertTrue(s.coordinator.state.value !is AppSessionState.Active)
    }

    @Test
    fun differentAccountGrantAndLegacyLocalRootsRefuseUnchanged() = runTest(timeout = 90.seconds) {
        // Different service UUID must not adopt retained data or queue.
        run {
            val s = stack()
            val handle = s.activate()
            handle.repository.createCustomExercise("session Mine", null, emptySet(), null, null, "")
            val journalBefore = harness.journalRow(s.database)
            val vaultBefore = s.vault.load()
            val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
            val foreign = AppSessionFixtures.grant(accountId = AppSessionFixtures.OTHER_ACCOUNT)
            try {
                s.coordinator.acceptGrant(readAuthority(s.database), AppSessionFixtures.OP_OTHER, foreign)
                error("foreign grant must throw")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
            }
            handle.checkCurrent()
            assertTrue(summaries(handle).contains("session Mine"))
            assertEquals(journalBefore, harness.journalRow(s.database))
            assertEquals(vaultBefore, s.vault.load())
            assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
        }
        // Reused active operation refuses before any drain: a fresh reservation
        // needs a fresh canonical operation UUID, even for a same-account grant.
        run {
            val s = stack()
            val handle = s.activate()
            handle.repository.createCustomExercise("session Kept", null, emptySet(), null, null, "")
            val expected = readAuthority(s.database)
            val reusedOp = checkNotNull(expected.identity).operationId
            val journalBefore = harness.journalRow(s.database)
            val vaultBefore = s.vault.load()
            val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
            val acquiresBefore = s.transition.acquires
            try {
                s.coordinator.acceptGrant(
                    expected,
                    reusedOp,
                    AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED),
                )
                error("reused operation must throw")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertRefusal(e, AppSessionReason.IDENTITY_MISMATCH)
            }
            handle.checkCurrent()
            assertTrue(summaries(handle).contains("session Kept"))
            assertEquals(journalBefore, harness.journalRow(s.database))
            assertEquals(vaultBefore, s.vault.load())
            assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
            assertEquals("early refusal acquires no drain", acquiresBefore, s.transition.acquires)
        }
        // Pre-existing signed-out local data requires an explicit decision.
        run {
            val s = stack()
            val database = s.database
            val localRepo = com.dragosfotache.gymtracka.data.gym.GymRepository(
                database,
                com.dragosfotache.gymtracka.data.sync.store.RepositorySession.SIGNED_OUT,
            )
            localRepo.createCustomExercise("session Legacy", null, emptySet(), null, null, "")
            assertEquals(emptyList<String?>(), harness.journalRow(database))
            try {
                s.coordinator.acceptGrant(
                    readAuthority(database), AppSessionFixtures.OP_FRESH, AppSessionFixtures.grant(),
                )
                error("legacy local roots must throw")
            } catch (e: AppSessionException) {
                AppSessionFixtures.assertRefusal(e, AppSessionReason.LOCAL_DATA_REQUIRES_DECISION)
            }
            // No import, deletion, reassignment or reservation happened.
            assertEquals(emptyList<String?>(), harness.journalRow(database))
            assertEquals(0L, s.vault.load().revision)
            assertEquals(0L, harness.outboxCount(database, AppSessionFixtures.ACCOUNT))
            assertTrue(s.coordinator.state.value !is AppSessionState.Active)
        }
    }

    // ---- Obligation 6: drain, invalidation, cancellation, delayed grants ----

    @Test
    fun failedTransitionDrainRetainsHandleCredentialsAndMakesNoReservation() = runTest(timeout = 90.seconds) {
        val s = stack()
        val handle = s.activate()
        handle.repository.createCustomExercise("session Live Edit", null, emptySet(), null, null, "")
        val journalBefore = harness.journalRow(s.database)
        val acquiresBefore = s.transition.acquires
        val vaultBefore = s.vault.load()
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
        s.transition.acquireFaults += RuntimeException("session-drain-fault")
        try {
            s.coordinator.acceptGrant(
                readAuthority(s.database),
                AppSessionFixtures.OP_OTHER,
                AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED),
            )
            error("failed drain must throw")
        } catch (e: AppSessionException) {
            AppSessionFixtures.assertRefusal(e, AppSessionReason.UNAVAILABLE)
        }
        assertEquals("failed drain must not reserve", journalBefore, harness.journalRow(s.database))
        assertEquals(vaultBefore, s.vault.load())
        assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
        assertEquals("failed drain acquires once", acquiresBefore + 1, s.transition.acquires)
        handle.checkCurrent()
        assertTrue(summaries(handle).contains("session Live Edit"))
    }

    @Test
    fun invalidationDuringObservedDrainCannotRepublishDeadHandle() = runTest(timeout = 90.seconds) {
        val s = stack()
        val handle = s.activate()
        s.transition.resetEnteredAcquire()
        s.transition.blockNextAcquire = true
        val outcome = CompletableDeferred<Result<AppSessionState>>()
        val job = launch {
            try {
                outcome.complete(Result.success(s.coordinator.acceptGrant(
                    readAuthority(s.database),
                    AppSessionFixtures.OP_OTHER,
                    AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED),
                )))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        // Observe actual drain entry before invalidating (real executors need
        // the gate, not bare runCurrent).
        s.transition.enteredAcquire.await()
        // Synchronous invalidation publishes PrivacyLocked immediately.
        s.coordinator.invalidate()
        assertTrue(s.coordinator.state.value is AppSessionState.PrivacyLocked)
        s.transition.releaseAcquire.complete(Unit)
        job.join()
        val failure = outcome.await().exceptionOrNull()
        assertTrue("drain work dead at invalidation must fail, got $failure", failure != null)
        if (failure !is kotlinx.coroutines.CancellationException) {
            AppSessionFixtures.assertClosed(failure!!)
        }
        assertTrue(job.isCompleted)
        assertTrue("dead drain must not republish", s.coordinator.state.value is AppSessionState.PrivacyLocked)
        try {
            handle.checkCurrent()
            error("invalidated handle must be dead")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        // Credentials and queue survive for the next live recovery.
        assertEquals(VaultSessionPhase.ACTIVE, s.vault.load().record?.phase)
        assertEquals(0L, harness.outboxCount(s.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun callerCancellationDuringObservedDrainTakesPrecedence() = runTest(timeout = 90.seconds) {
        val s = stack()
        s.activate()
        val healthyState = s.coordinator.state.value
        assertTrue("expected Active, was $healthyState", healthyState is AppSessionState.Active)
        val healthy = (healthyState as AppSessionState.Active).session
        healthy.repository.createCustomExercise("session Cancel Kept", null, emptySet(), null, null, "")
        val journalBefore = harness.journalRow(s.database)
        val vaultBefore = s.vault.load()
        val frozenBefore = harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT))
        s.transition.resetEnteredAcquire()
        s.transition.blockNextAcquire = true
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                s.coordinator.acceptGrant(
                    readAuthority(s.database),
                    AppSessionFixtures.OP_OTHER,
                    AppSessionFixtures.grant(),
                )
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        s.transition.enteredAcquire.await()
        val probe = kotlinx.coroutines.CancellationException("session-caller-cancel")
        job.cancel(probe)
        s.transition.releaseAcquire.complete(Unit)
        job.join()
        assertSame(probe, outcome.await())
        assertTrue(job.isCompleted)
        // Still-blocked cancellation preserves the SAME healthy Active handle
        // and credentials: nothing was superseded or reserved.
        val state = s.coordinator.state.value
        assertTrue("cancelled drain must preserve Active, was $state", state is AppSessionState.Active)
        val same = (state as AppSessionState.Active).session
        same.checkCurrent()
        assertTrue(summaries(same).contains("session Cancel Kept"))
        assertEquals(journalBefore, harness.journalRow(s.database))
        assertEquals(vaultBefore, s.vault.load())
        assertEquals(frozenBefore, harness.frozenIdentity(harness.outboxSnapshot(s.database, AppSessionFixtures.ACCOUNT)))
    }

    @Test
    fun delayedGrantAgainstChangedExpectationThrowsStaleUnchanged() = runTest(timeout = 90.seconds) {
        val s = stack()
        val handle = s.activate()
        val staleExpected = readAuthority(s.database)
        // A newer reservation moves first.
        val newer = s.coordinator.acceptGrant(
            readAuthority(s.database),
            AppSessionFixtures.OP_OTHER,
            AppSessionFixtures.grant(access = AppSessionFixtures.ACCESS_ROTATED),
        )
        assertTrue(newer is AppSessionState.Private)
        val journalAfterNewer = harness.journalRow(s.database)
        assertEquals(AppSessionFixtures.OP_OTHER, journalAfterNewer[5])
        // The delayed callback against the superseded expectation throws stale.
        try {
            s.coordinator.acceptGrant(
                staleExpected,
                AppSessionFixtures.OP_STALE,
                AppSessionFixtures.grant(access = "session-access-delayed"),
            )
            error("delayed grant must throw stale")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
        assertEquals("delayed grant must not move the journal", journalAfterNewer, harness.journalRow(s.database))
        assertTrue(s.coordinator.state.value !is AppSessionState.Active)
        try {
            handle.checkCurrent()
            error("superseded handle must stay dead")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertClosed(e)
        }
    }
}
