package com.dragosfotache.gymtracka.ui.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppSessionAuthApi
import com.dragosfotache.gymtracka.data.sync.session.AppSessionCoordinator
import com.dragosfotache.gymtracka.data.sync.session.AppSessionException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionHarness
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.session.AppSessionStaleException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransport
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInIntent
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.ui.AccountGymAppState
import com.dragosfotache.gymtracka.ui.GymAppClock
import com.dragosfotache.gymtracka.ui.RestTimerHardware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks read-only selected-account status through real Room/facade admission, including pending
 * states, retained errors, strict last-success storage and handle fences.
 */
@RunWith(AndroidJUnit4::class)
class AccountSyncStatusTest {
    private val harness = AppSessionHarness("startup-status")
    private val dbs = mutableListOf<GymDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val runtimes = mutableListOf<AccountGymAppState>()
    private val startups = mutableListOf<AccountStartupState>()

    @After
    fun tearDown() {
        startups.forEach { runCatching { it.dispose() } }
        startups.clear()
        runtimes.forEach { runCatching { it.dispose() } }
        runtimes.clear()
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harness.clean()
    }

    private companion object {
        const val ACCOUNT = "c13c3001-1111-4111-8111-111111111111"
        const val FOREIGN = "c13c3002-2222-4222-8222-222222222222"
        const val LAST_SUCCESS = 1_700_000_001_000L
        const val LAST_SUCCESS_NEW = 1_700_000_002_000L
    }

    private data class Fixture(
        val database: GymDatabase,
        val transport: AppSessionTransport,
        val runtime: AccountGymAppState,
        val session: AppAccountSession,
    )

    private fun fixture(): Fixture {
        val database = harness.openInMemoryDb().also { dbs += it }
        val transport = AppSessionTransport()
        val uuids = listOf(
            AppSessionFixtures.OP_FRESH,
            AppSessionFixtures.KEY_FRESH,
            AppSessionFixtures.OP_OTHER,
            AppSessionFixtures.STAGE,
            AppSessionFixtures.STAGE_OTHER,
        ).iterator()
        val runtime = AccountGymAppState(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = harness.newVault(),
            authApi = AppSessionAuthApi(),
            transport = transport,
            runOwner = PushRunOwner(),
            scope = MainScope().also { scopes += it },
            clock = TestClock(),
            timerHardware = NoOpHardware(),
            uuidProvider = { uuids.next() },
        ).also { runtimes += it }
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val session = runBlocking {
            val preparing = runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.OP_FRESH,
                AppSessionFixtures.grant(accountId = AppSessionFixtures.ACCOUNT),
            )
            assertTrue(preparing is AppSessionState.Private)
            val active = runtime.sessionCoordinator.completeActivation(
                RoomSessionAuthority(database).read(),
                AppSessionFixtures.STAGE,
            )
            assertTrue("activation must be Active, was $active", active is AppSessionState.Active)
            (active as AppSessionState.Active).session
        }
        return Fixture(database, transport, runtime, session)
    }

    private fun exec(database: GymDatabase, sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun seedBaseline(database: GymDatabase, account: String, lastSuccess: Long?) {
        exec(database, "DELETE FROM sync_state WHERE account_id = '$account'")
        val value = lastSuccess?.toString() ?: "NULL"
        exec(
            database,
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$account', 'c13c-cursor', $value)",
        )
    }

    private fun seedOutbox(
        database: GymDatabase,
        sequence: Long,
        mutationId: String,
        account: String,
        state: String,
        errorCode: String?,
        errorMessage: String?,
    ) {
        val code = errorCode?.let { "'$it'" } ?: "NULL"
        val message = errorMessage?.let { "'$it'" } ?: "NULL"
        exec(
            database,
            "INSERT INTO sync_outbox" +
                " (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES ($sequence, '$mutationId', '$account', 'workout', 'w13c-$sequence'," +
                " 'workout.start', 1, 1, '{}', '$state', 0, NULL, $code, $message)",
        )
    }

    private suspend fun firstStatus(fix: Fixture): AccountSyncStatus = withTimeout(10_000L) {
        fix.runtime.sessionCoordinator.observeSyncStatus(fix.session).first()
    }

    @Test
    fun selectedCountsIncludeFrozenAndRejected_foreignExcluded_lastSuccessExact() = runBlocking {
        val fix = fixture()
        val selected = AppSessionFixtures.ACCOUNT
        exec(fix.database, "DELETE FROM sync_outbox WHERE account_id = '$selected'")
        exec(fix.database, "DELETE FROM sync_outbox WHERE account_id = '$FOREIGN'")
        seedBaseline(fix.database, selected, LAST_SUCCESS)
        seedOutbox(fix.database, 10L, "m13c3001-1111-4111-8111-111111111111", selected, "pending", null, null)
        seedOutbox(fix.database, 20L, "m13c3002-2222-4222-8222-222222222222", selected, "frozen", null, null)
        seedOutbox(fix.database, 30L, "m13c3003-3333-4333-8333-333333333333", selected, "rejected", "gone", "Gone")
        seedOutbox(fix.database, 40L, "m13c3004-4444-4444-8444-444444444444", FOREIGN, "pending", null, null)
        val httpBefore = fix.transport.capturedRequests.size
        val status = firstStatus(fix)
        assertEquals(3L, status.pendingCount)
        assertEquals(LAST_SUCCESS, status.lastSuccessMillis)
        assertTrue(status.hasRetainedError)
        assertEquals("status observation must perform no HTTP", httpBefore, fix.transport.capturedRequests.size)
    }

    @Test
    fun refreshAfterMetadataCommit_withoutHttp_retainedErrorClearsWhenRejectedResolved() = runBlocking {
        val fix = fixture()
        val selected = AppSessionFixtures.ACCOUNT
        exec(fix.database, "DELETE FROM sync_outbox WHERE account_id = '$selected'")
        seedBaseline(fix.database, selected, LAST_SUCCESS)
        seedOutbox(fix.database, 10L, "m13c3101-1111-4111-8111-111111111111", selected, "pending", null, null)
        val first = firstStatus(fix)
        assertEquals(1L, first.pendingCount)
        assertEquals(LAST_SUCCESS, first.lastSuccessMillis)
        assertFalse(first.hasRetainedError)
        val httpBefore = fix.transport.capturedRequests.size
        seedOutbox(fix.database, 20L, "m13c3102-2222-4222-8222-222222222222", selected, "pending", null, null)
        seedBaseline(fix.database, selected, LAST_SUCCESS_NEW)
        val second = withTimeout(10_000L) {
            fix.runtime.sessionCoordinator.observeSyncStatus(fix.session).first()
        }
        assertEquals(2L, second.pendingCount)
        assertEquals(LAST_SUCCESS_NEW, second.lastSuccessMillis)
        assertFalse(second.hasRetainedError)
        assertEquals(httpBefore, fix.transport.capturedRequests.size)
    }

    @Test
    fun missingLastSuccess_isNullNotZeroOrSuccess() = runBlocking {
        val fix = fixture()
        val selected = AppSessionFixtures.ACCOUNT
        exec(fix.database, "DELETE FROM sync_outbox WHERE account_id = '$selected'")
        exec(fix.database, "DELETE FROM sync_state WHERE account_id = '$selected'")
        seedOutbox(fix.database, 10L, "m13c3201-1111-4111-8111-111111111111", selected, "pending", null, null)
        val status = firstStatus(fix)
        assertEquals(1L, status.pendingCount)
        assertNull("unknown/unavailable last-success is not zero/success", status.lastSuccessMillis)
        assertFalse(status.hasRetainedError)
    }

    @Test
    /**
     * Inconsistent-metadata refusal: a nonnumeric TEXT / REAL / BLOB
     * last_success_millis cell must refuse closed UNAVAILABLE, never a
     * SQLite/Room-coerced epoch-zero success. The original raw cell stays
     * unchanged and no HTTP runs. Valid SQL null/missing stays null and
     * integer 0 remains legitimate (covered by the neighboring tests).
     */
    fun malformedLastSuccessStorageType_refusesUnavailableWithoutNormalization() = runBlocking {
        val fix = fixture()
        val selected = AppSessionFixtures.ACCOUNT
        val cells = listOf(
            "'not-a-millis'" to "text",
            "1700000001.5" to "real",
            "x'010203'" to "blob",
        )
        for ((literal, affinity) in cells) {
            exec(fix.database, "DELETE FROM sync_state WHERE account_id = '$selected'")
            exec(
                fix.database,
                "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                    " VALUES ('$selected', 'c13c-cursor', $literal)",
            )
            val httpBefore = fix.transport.capturedRequests.size
            val before = fix.database.openHelper.readableDatabase.query(
                "SELECT typeof(last_success_millis) || '|' || quote(last_success_millis)" +
                    " FROM sync_state WHERE account_id = '$selected'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "seeded row must exist before refusal" }
                cursor.getString(0)
            }
            val outcome = runCatching {
                withTimeout(10_000L) { fix.runtime.sessionCoordinator.observeSyncStatus(fix.session).first() }
            }
            val error = outcome.exceptionOrNull()
            checkNotNull(error) { "malformed $affinity last-success must refuse, not coerce" }
            assertTrue("expected closed UNAVAILABLE, was $error", error is AppSessionException)
            assertEquals(AppSessionReason.UNAVAILABLE, (error as AppSessionException).reason)
            AppSessionFixtures.assertClosed(error)
            // Original raw cell value and storage type unchanged: refusal
            // never normalizes storage (typeof alone cannot prove the value).
            fix.database.openHelper.readableDatabase.query(
                "SELECT typeof(last_success_millis) || '|' || quote(last_success_millis)" +
                    " FROM sync_state WHERE account_id = '$selected'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "row must survive refusal" }
                assertEquals(before, cursor.getString(0))
            }
            assertEquals("refusal performs no HTTP", httpBefore, fix.transport.capturedRequests.size)
        }
    }

    @Test
    fun retiredHandle_cannotBindNewerStatus() = runBlocking {
        val fix = fixture()
        val selected = AppSessionFixtures.ACCOUNT
        exec(fix.database, "DELETE FROM sync_outbox WHERE account_id = '$selected'")
        seedBaseline(fix.database, selected, LAST_SUCCESS)
        seedOutbox(fix.database, 10L, "m13c3301-1111-4111-8111-111111111111", selected, "pending", null, null)
        val before = firstStatus(fix)
        assertEquals(1L, before.pendingCount)
        // Retire the old handle with a fresh same-account grant.
        fix.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        fix.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        fix.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val retired = fix.runtime.sessionCoordinator.acceptGrant(
            RoomSessionAuthority(fix.database).read(),
            AppSessionFixtures.OP_OTHER,
            AppSessionFixtures.grant(
                accountId = selected,
                access = AppSessionFixtures.ACCESS_ROTATED,
                refresh = AppSessionFixtures.REFRESH_ROTATED,
            ),
        )
        assertTrue(retired is AppSessionState.Private)
        var staleObserved = false
        try {
            fix.session.checkCurrent()
        } catch (_: AppSessionStaleException) {
            staleObserved = true
        } catch (_: IllegalStateException) {
            staleObserved = true
        } catch (_: CancellationException) {
            staleObserved = true
        }
        assertTrue("old handle must be retired after transition, was $retired", staleObserved)
        // Old selected status must not rebind to newer work: collecting the old
        // session must promptly reject AppSessionStaleException. Any other
        // outcome (an emission, a hang, a foreign error) fails, never passes.
        try {
            withTimeout(10_000L) {
                fix.runtime.sessionCoordinator.observeSyncStatus(fix.session).first()
            }
            fail("retired handle collection must reject, never emit")
        } catch (e: AppSessionStaleException) {
            AppSessionFixtures.assertStale(e)
            Unit
        }
    }

    @Test
    /**
     * Retirement-only controller gate (no new handle): retiring to the
     * private handle clears old status before another emission. Observed
     * through AccountStartupState collection; retired-handle isolation is
     * covered by its own test.
     */
    fun retiredPrivate_clearsControllerStatusWithoutNewHandle() = runBlocking {
        val fix = fixture()
        val selected = AppSessionFixtures.ACCOUNT
        val controller = AccountStartupState(
            FacadeStatusOps(fix.runtime.sessionCoordinator),
            true,
            MainScope().also { scopes += it },
        ).also { startups += it }
        controller.start()
        // Staged gate: each wait names its stage so a timeout preserves exactly
        // which collector step never emitted (no sleeps; bounded waits only).
        var stage = "collect-initial"
        try {
            withTimeout(10_000L) {
                controller.uiState.first { it.syncStatus?.pendingCount == 0L }
            }
            stage = "refresh-after-seed"
            // Genuine retained intent through the real repository capture path
            // (raw SQL never authorizes or notifies the observation chain).
            fix.session.repository.createCustomExercise("startup Status Live", null, emptySet(), null, null, "")
            withTimeout(10_000L) {
                controller.uiState.first { it.syncStatus?.pendingCount == 1L }
            }
            stage = "clear-after-retire"
            // Retire to the private handle: old status clears before any other
            // emission, never lingers stale on the presentation.
            fix.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
            fix.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            fix.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
            fix.runtime.sessionCoordinator.acceptGrant(
                RoomSessionAuthority(fix.database).read(),
                AppSessionFixtures.OP_OTHER,
                AppSessionFixtures.grant(
                    accountId = selected,
                    access = AppSessionFixtures.ACCESS_ROTATED,
                    refresh = AppSessionFixtures.REFRESH_ROTATED,
                ),
            )
            withTimeout(10_000L) {
                controller.uiState.first { it.syncStatus == null }
            }
            stage = "settle-idle"
            val settled = withTimeout(10_000L) {
                controller.uiState.first { !it.busy }
            }
            assertNull(settled.syncStatus)
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "status-collector gate timed out at stage=$stage " +
                    "[busy=${controller.uiState.value.busy} status=${controller.uiState.value.syncStatus}]",
                e,
            )
        }
    }

    /**
     * Invalidate/recover handle rotation: emergency invalidate retires the
     * live handle; read-only recover re-admits the same Room generation as a
     * DISTINCT handle object. The new metadata emission is held behind a
     * deterministic test gate: the old DTO must read cleared before release,
     * and only the new handle's emission lands afterwards (no stale binding).
     */
    fun invalidateRecover_newHandleClearsOldDtoBeforeEmission() = runBlocking {
        val fix = fixture()
        val selected = AppSessionFixtures.ACCOUNT
        val gate = CompletableDeferred<Unit>()
        var holdNew = false
        val ops = object : AccountStartupOperations {
            private val inner = FacadeStatusOps(fix.runtime.sessionCoordinator)
            override val state = inner.state
            override suspend fun recover() = inner.recover()
            override suspend fun signIn(
                intent: ForegroundSignInIntent,
                expected: RecoveryRoomSnapshot,
                operationId: String,
            ): AppSessionState = inner.signIn(intent, expected, operationId)
            override suspend fun completeActivation(
                expected: RecoveryRoomSnapshot,
                stageId: String,
            ): AppSessionState = inner.completeActivation(expected, stageId)
            override suspend fun observeLegacyImport(): LegacyImportProgress? = inner.observeLegacyImport()
            override fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus> =
                kotlinx.coroutines.flow.flow {
                    if (holdNew) gate.await()
                    emitAll(inner.observeSyncStatus(session))
                }
        }
        val controller = AccountStartupState(
            ops,
            true,
            MainScope().also { scopes += it },
        ).also { startups += it }
        controller.start()
        withTimeout(10_000L) {
            controller.uiState.first { it.syncStatus?.pendingCount == 0L }
        }
        fix.session.repository.createCustomExercise("startup Rotate Kept", null, emptySet(), null, null, "")
        withTimeout(10_000L) {
            controller.uiState.first { it.syncStatus?.pendingCount == 1L }
        }
        // Hold the NEXT metadata emission, then rotate the handle: emergency
        // invalidate retires the live session, read-only recover re-admits it.
        holdNew = true
        fix.runtime.sessionCoordinator.invalidate()
        val revived = withTimeout(10_000L) {
            val current = fix.runtime.sessionCoordinator.recover()
            check(current is AppSessionState.Active) { "recover must re-admit, was $current" }
            current
        }
        val revivedSession = (revived as AppSessionState.Active).session
        assertNotSame(fix.session, revivedSession)
        var oldRetired = false
        try {
            fix.session.checkCurrent()
        } catch (_: Exception) {
            oldRetired = true
        }
        assertTrue("invalidated handle must be retired", oldRetired)
        revivedSession.checkCurrent()
        // Old DTO cleared before the held new emission is released.
        withTimeout(10_000L) {
            controller.uiState.first { it.syncStatus == null }
        }
        gate.complete(Unit)
        withTimeout(10_000L) {
            controller.uiState.first { it.syncStatus?.pendingCount == 1L }
        }
        assertTrue(harness.customNames(fix.database, selected).contains("startup Rotate Kept"))
    }

    /**
     * Minimal startup operations delegating read paths to the real facade so
     * the status-collector gate proves the runtime handle, never a detached
     * fake. Sign-in/activation are never invoked in this observation test.
     */
    private class FacadeStatusOps(
        private val facade: AppSessionCoordinator,
    ) : AccountStartupOperations {
        override val state = facade.state
        override suspend fun recover() = facade.recover()
        override suspend fun signIn(
            intent: ForegroundSignInIntent,
            expected: RecoveryRoomSnapshot,
            operationId: String,
        ): AppSessionState = throw AssertionError("status gate never signs in")
        override suspend fun completeActivation(
            expected: RecoveryRoomSnapshot,
            stageId: String,
        ): AppSessionState = throw AssertionError("status gate never activates")
        override suspend fun observeLegacyImport(): LegacyImportProgress? = facade.observeLegacyImport()
        override fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus> =
            facade.observeSyncStatus(session)
    }

    private class TestClock : GymAppClock {
        override fun wallTimeMillis() = AppSessionFixtures.FIXED_CLOCK
        override fun elapsedRealtimeMillis() = 0L
    }

    private class NoOpHardware : RestTimerHardware {
        override fun vibrate(durationMillis: Long) = Unit
        override fun acquireWakeLock(timeoutMillis: Long) = Unit
        override fun releaseWakeLock() = Unit
    }
}
