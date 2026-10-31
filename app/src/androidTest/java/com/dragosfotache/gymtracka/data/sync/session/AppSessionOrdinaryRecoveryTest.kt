package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportCaptureBarrier
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportCaptureResult
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPolicy
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementConsent
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementRequest
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportStore
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportTarget
import com.dragosfotache.gymtracka.data.sync.session.AppSessionCoordinator
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Real Room checks for exact ordinary-recovery preparation, confirmation, discard and protected
 * reload.
 */
@RunWith(AndroidJUnit4::class)
class AppSessionOrdinaryRecoveryTest {
    private companion object {
        const val EXERCISE_ONE = "foreground Ordinary One"
        const val EXERCISE_TWO = "foreground Ordinary Two"
        const val ROUTINE_ID = "b13d1001-1111-4111-8111-111111111111"
        const val MALFORMED_ID = "f13d9001-1111-4111-8111-111111111111"
        const val OP_IMPORT = "d13d1001-1111-4111-8111-111111111111"
        const val STAGE_I = "d13d1002-2222-4222-8222-222222222222"
        const val MUTATION_I3 = "70000003-d13d-4d13-8d13-d13bd13bd13b"
        const val CUSTOM_X = "22222222-d13d-4d13-8d13-d13bd13bd13b"
        const val MUTATION_I1 = "70000001-d13d-4d13-8d13-d13bd13bd13b"
        const val MUTATION_I2 = "70000002-d13d-4d13-8d13-d13bd13bd13b"
        const val BATCH_I1 = "b0000001-d13d-4d13-8d13-d13bd13bd13b"
        const val BATCH_I2 = "b0000002-d13d-4d13-8d13-d13bd13bd13b"
        const val CUSTOM_I = "11111111-d13d-4d13-8d13-d13bd13bd13b"
        const val WORKOUT_I = "55555555-d13d-4d13-8d13-d13bd13bd13b"
        const val WE_I = "d1d13b00-d100-4d10-8d10-d100d100d100"
        const val WS_I = "e1d13b00-e100-4e10-8e10-e100e100e100"
    }

    private val harness = AppSessionHarness("foreground-recovery")
    private val harnesses = mutableListOf<AppSessionHarness>()
    private val dbs = mutableListOf<GymDatabase>()

    @After
    fun tearDown() {
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        harnesses.forEach { runCatching { it.clean() } }
        harnesses.clear()
        harness.clean()
    }

    private data class Stack(
        val database: GymDatabase,
        val coordinator: AppSessionCoordinator,
        val transport: AppSessionTransport,
        val vault: com.dragosfotache.gymtracka.data.sync.session.SessionVault,
        val auth: AppSessionAuthApi,
        val owner: PushRunOwner,
        val transition: ScriptedTransitionBarrier,
        val publication: ScriptedPublicationBarrier,
    )

    private fun stack(tag: String = "t"): Stack {
        val scoped = AppSessionHarness("foreground-recovery-$tag").also { harnesses += it }
        val database = scoped.openInMemoryDb().also { dbs += it }
        val transport = AppSessionTransport()
        val vault = scoped.newVault()
        val auth = AppSessionAuthApi()
        val owner = AppSessionFixtures.owner()
        val transition = ScriptedTransitionBarrier()
        val publication = ScriptedPublicationBarrier()
        val coordinator = AppSessionFixtures.coordinator(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = vault,
            auth = auth,
            transport = transport,
            runOwner = owner,
            transitionBarrier = transition,
            publicationBarrier = publication,
        )
        return Stack(database, coordinator, transport, vault, auth, owner, transition, publication)
    }

    private suspend fun Stack.activate(): AppAccountSession {
        transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val preparing = coordinator.acceptGrant(
            RoomSessionAuthority(database).read(),
            AppSessionFixtures.OP_FRESH,
            AppSessionFixtures.grant(),
        )
        assertTrue("setup must reach Private, was $preparing", preparing is AppSessionState.Private)
        val active = coordinator.completeActivation(
            RoomSessionAuthority(database).read(),
            AppSessionFixtures.STAGE,
        )
        assertTrue("setup must reach Active, was $active", active is AppSessionState.Active)
        assertTrue("activation must drain its phase replies", transport.queue.isEmpty())
        return (active as AppSessionState.Active).session
    }

    private fun Stack.lastSuccess(): Long? {
        database.openHelper.readableDatabase.query(
            "SELECT last_success_millis FROM sync_state WHERE account_id = ?",
            arrayOf(AppSessionFixtures.ACCOUNT),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getLong(0)
        }
    }

    private fun Stack.cacheRow(): List<String?> {
        database.openHelper.readableDatabase.query(
            "SELECT baseline_complete, cache_epoch, materialized_epoch, projection_dirty" +
                " FROM sync_account_cache WHERE account_id = ?",
            arrayOf(AppSessionFixtures.ACCOUNT),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return emptyList()
            return (0 until 4).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
    }

    private fun assertNoNewDrain(
        transition: ScriptedTransitionBarrier,
        publication: ScriptedPublicationBarrier,
        t0: Int,
        p0: Int,
        label: String,
    ) {
        assertEquals("$label must not acquire the transition drain", t0, transition.acquires)
        assertEquals("$label must not acquire the publication drain", p0, publication.acquires)
    }

    private fun rejectedPushBody(mutationId: String): String =
        "{\"results\":[{\"mutationId\":" + org.json.JSONObject.quote(mutationId) +
            ",\"status\":\"rejected\",\"errorCode\":\"not_found\",\"batch\":null}]}"

    /**
     * Seeds one ordinary edit through real repository capture (setup, never
     * the oracle), drives a definitive ordinary rejection through the real
     * adapter with a hand-authored outcome, and leaves recovery confirmable.
     */
    private suspend fun Stack.seedDefinitiveRejection(
        session: AppAccountSession,
        name: String = EXERCISE_ONE,
    ): String {
        session.repository.createCustomExercise(name, null, emptySet(), null, null, "")
        val before = harness.outboxSnapshot(database, AppSessionFixtures.ACCOUNT)
        assertEquals("setup must capture exactly one ordinary row", 1, before.size)
        val mutationId = checkNotNull(before[0][1]) { "captured row must carry a mutation ID" }
        transport.queueOk(rejectedPushBody(mutationId))
        transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val result = coordinator.reconcile(session, coordinator.observeCacheEpoch(session))
        assertTrue(
            "rejected head must stop the run, got ${result.stop}",
            result.stop != AppReconciliationStop.TAIL,
        )
        assertNull(result.retainedStageId)
        transport.queue.clear()
        val rejected = harness.outboxSnapshot(database, AppSessionFixtures.ACCOUNT)
        assertEquals(1, rejected.size)
        assertEquals("rejected", rejected[0][9])
        assertEquals("not_found", rejected[0][12])
        assertEquals(
            "rejection must keep exact frozen identity",
            harness.frozenIdentity(before),
            harness.frozenIdentity(rejected),
        )
        return mutationId
    }

    @Test
    fun advisoryEligibilityAndTypedPreparation_preserveCleanPendingRejectedAndBlockedWork() = runTest(timeout = 90.seconds) {
        val stack = stack("eligibility")
        val session = stack.activate()
        val t0 = stack.transition.acquires
        val p0 = stack.publication.acquires
        val http0 = stack.transport.capturedRequests.size
        assertEquals(OrdinaryRecoveryPreparation.NothingEligible, stack.coordinator.prepareOrdinaryRecoveryOutcome(session))
        assertTrue(!stack.coordinator.observeSyncStatus(session).first().hasRejectedOrdinaryWork)
        session.repository.createCustomExercise(EXERCISE_ONE, null, emptySet(), null, null, "")
        assertTrue(!stack.coordinator.observeSyncStatus(session).first().hasRejectedOrdinaryWork)
        assertEquals(OrdinaryRecoveryPreparation.NothingEligible, stack.coordinator.prepareOrdinaryRecoveryOutcome(session))
        assertEquals(http0, stack.transport.capturedRequests.size)
        assertNoNewDrain(stack.transition, stack.publication, t0, p0, "clean/pending eligibility")
        val pending = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        val mutation = checkNotNull(pending.single()[1])
        stack.transport.queueOk(rejectedPushBody(mutation))
        stack.coordinator.reconcile(session, stack.coordinator.observeCacheEpoch(session))
        val rejected = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        val httpRejected = stack.transport.capturedRequests.size
        assertTrue(stack.coordinator.observeSyncStatus(session).first().hasRejectedOrdinaryWork)
        assertEquals(rejected, harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals(httpRejected, stack.transport.capturedRequests.size)
        val prepared = stack.coordinator.prepareOrdinaryRecoveryOutcome(session)
        assertTrue(prepared is OrdinaryRecoveryPreparation.Prepared)
        assertEquals(1L, (prepared as OrdinaryRecoveryPreparation.Prepared).confirmation.pendingCount)
        assertEquals(rejected, harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT))
        // Corrupt retained rejection can never advertise ordinary discard eligibility.
        stack.database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_outbox SET payload_json = '{}' WHERE account_id = ?",
            arrayOf(AppSessionFixtures.ACCOUNT),
        )
        val corrupt = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        val drains = stack.transition.acquires
        assertTrue(!stack.coordinator.observeSyncStatus(session).first().hasRejectedOrdinaryWork)
        assertEquals(OrdinaryRecoveryPreparation.Blocked, stack.coordinator.prepareOrdinaryRecoveryOutcome(session))
        assertEquals(corrupt, harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals(drains, stack.transition.acquires)
    }

    @Test
    fun preparationFailureIsNotAnEmptyQueue_andKeepsRejectedConfirmationEvidence() = runTest(timeout = 90.seconds) {
        val stack = stack("preparation-failed")
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        val before = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        // A real drain acquisition fault is a failure, never an empty or ineligible queue.
        stack.transition.acquireFaults.add(IllegalStateException("scripted acquisition failure"))
        val failure = runCatching { stack.coordinator.prepareOrdinaryRecoveryOutcome(session) }.exceptionOrNull()
        assertTrue(failure is AppSessionException)
        assertEquals(AppSessionReason.UNAVAILABLE, (failure as AppSessionException).reason)
        assertEquals(before, harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun frozenPreparationAuthenticationStop_preservesExactWorkAndRequestsReauthentication() = runTest(timeout = 90.seconds) {
        val stack = stack("prep-auth")
        val session = stack.activate()
        session.repository.createCustomExercise(EXERCISE_ONE, null, emptySet(), null, null, "")
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        stack.transport.responseGate = {
            entered.complete(Unit)
            hold.await()
        }
        val run = backgroundScope.async {
            stack.coordinator.reconcile(session, stack.coordinator.observeCacheEpoch(session))
        }
        entered.await()
        run.cancel()
        hold.complete(Unit)
        runCatching { run.await() }
        stack.transport.responseGate = null
        val frozen = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals("frozen", frozen.single()[9])
        val t0 = stack.transition.acquires
        stack.transport.queueCode(401, """{"errorCode":"reauthentication_required"}""")
        val failure = runCatching { stack.coordinator.prepareOrdinaryRecoveryOutcome(session) }.exceptionOrNull()
        assertTrue(failure is com.dragosfotache.gymtracka.data.sync.api.SyncApiException.ReauthenticationRequired)
        assertEquals(AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED,
            (stack.coordinator.state.value as AppSessionState.Active).deliveryStatus)
        val after = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals(harness.frozenIdentity(frozen), harness.frozenIdentity(after))
        assertEquals("frozen", after.single()[9])
        assertEquals(t0, stack.transition.acquires)
    }

    @Test
    fun definitiveRejection_prepareConfirm_discardsReloadsSnapshot() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        val confirmation = stack.coordinator.prepareOrdinaryRecovery(session)
        val token = checkNotNull(confirmation) { "definitive ordinary rejection must be confirmable" }
        assertEquals(1L, token.pendingCount)
        AppSessionFixtures.assertRedacted(token.toString())
        assertTrue(
            "old graph stays visible before publication",
            harness.customNames(stack.database, AppSessionFixtures.ACCOUNT).contains(EXERCISE_ONE),
        )
        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val result = stack.coordinator.recoverOrdinaryRejection(session, token)
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertEquals(0L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals("session-baseline", harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT))
        assertNotNull(
            "a valid tail supplies fresh last-success after reload",
            stack.lastSuccess(),
        )
        assertTrue(
            "complete EMPTY snapshot replaces the discarded unsynced graph",
            !harness.customNames(stack.database, AppSessionFixtures.ACCOUNT).contains(EXERCISE_ONE),
        )
        assertEquals(0L, harness.projectionDirty(stack.database, AppSessionFixtures.ACCOUNT))
        AppSessionFixtures.assertRedacted(result.toString())
    }

    @Test
    fun prepare_withNothingConfirmable_returnsNull() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        val t0clean = stack.transition.acquires
        val p0clean = stack.publication.acquires
        assertNull(
            "clean account must offer no confirmable ordinary rejection",
            stack.coordinator.prepareOrdinaryRecovery(session),
        )
        assertEquals(0L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        assertNoNewDrain(stack.transition, stack.publication, t0clean, p0clean, "clean prepare")
    }

    @Test
    fun staleNewEdit_refusesWithIntentIntact() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        val token = checkNotNull(stack.coordinator.prepareOrdinaryRecovery(session))
        // A newer committed edit after preparation invalidates the captured
        // snapshot: confirmation must refuse and keep everything.
        session.repository.createCustomExercise(EXERCISE_TWO, null, emptySet(), null, null, "")
        val before = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals(2, before.size)
        val error = runCatching {
            stack.coordinator.recoverOrdinaryRejection(session, token)
        }.exceptionOrNull()
        AppSessionFixtures.assertStale(
            checkNotNull(error) { "stale confirmation must refuse, not discard" },
        )
        assertEquals(
            "stale refusal must leave every captured row byte-identical",
            before,
            harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT),
        )
        assertEquals("stale refusal must keep all intent", 2L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        assertTrue(harness.customNames(stack.database, AppSessionFixtures.ACCOUNT).contains(EXERCISE_ONE))
        assertTrue(harness.customNames(stack.database, AppSessionFixtures.ACCOUNT).contains(EXERCISE_TWO))
    }

    @Test
    fun replayedToken_secondConfirmRefuses() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        val token = checkNotNull(stack.coordinator.prepareOrdinaryRecovery(session))
        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val first = stack.coordinator.recoverOrdinaryRejection(session, token)
        assertEquals(AppReconciliationStop.TAIL, first.stop)
        val error = runCatching {
            stack.coordinator.recoverOrdinaryRejection(session, token)
        }.exceptionOrNull()
        AppSessionFixtures.assertStale(
            checkNotNull(error) { "replayed confirmation must refuse" },
        )
        assertEquals(0L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals("session-baseline", harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun foreignToken_refusesWithIntentIntact() = runTest(timeout = 90.seconds) {
        val first = stack("foreign-a")
        val second = stack("foreign-b")
        val sessionA = first.activate()
        val sessionB = second.activate()
        first.seedDefinitiveRejection(sessionA)
        second.seedDefinitiveRejection(sessionB, EXERCISE_TWO)
        val foreign = checkNotNull(second.coordinator.prepareOrdinaryRecovery(sessionB))
        val error = runCatching {
            first.coordinator.recoverOrdinaryRejection(sessionA, foreign)
        }.exceptionOrNull()
        AppSessionFixtures.assertStale(
            checkNotNull(error) { "foreign confirmation must refuse" },
        )
        assertEquals("foreign refusal must keep local intent", 1L, harness.outboxCount(first.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun frozenUncertain_rejectedSettlementRetainsOriginal() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        session.repository.createCustomExercise(EXERCISE_ONE, null, emptySet(), null, null, "")
        val pending = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals(1, pending.size)
        val mutationId = checkNotNull(pending[0][1])
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        stack.transport.responseGate = {
            entered.complete(Unit)
            hold.await()
        }
        stack.transport.queueOk(rejectedPushBody(mutationId))
        val run = backgroundScope.async {
            stack.coordinator.reconcile(session, stack.coordinator.observeCacheEpoch(session))
        }
        entered.await()
        runCurrent()
        val frozen = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals("uncertain head must freeze exactly once", 1, frozen.size)
        assertEquals("frozen", frozen[0][9])
        assertEquals(
            "uncertainty must not rewrite frozen bytes",
            harness.frozenIdentity(pending),
            harness.frozenIdentity(frozen),
        )
        hold.complete(Unit)
        run.await()
        stack.transport.responseGate = null
        stack.transport.queue.clear()
        val settled = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals(1, settled.size)
        assertEquals("rejected", settled[0][9])
        assertEquals("not_found", settled[0][12])
        assertEquals(
            "rejected settlement must retain the original exact request",
            harness.frozenIdentity(pending),
            harness.frozenIdentity(settled),
        )
        val token = stack.coordinator.prepareOrdinaryRecovery(session)
        assertNotNull("settled rejection must become confirmable", token)
    }

    @Test
    fun malformedOutboxRow_prepareRefusesUnchanged() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        // Narrowly documented malformed fixture via raw SQL: unsupported
        // payload version with corrupt bytes. Never a backend outcome.
        stack.database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (1, ?, ?, 'routine', ?, 'routine.upsert', 1, 99, '{not-json', 'pending'," +
                " 0, NULL, NULL, NULL)",
            arrayOf(MALFORMED_ID, AppSessionFixtures.ACCOUNT, ROUTINE_ID),
        )
        val before = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        val t0malformed = stack.transition.acquires
        val p0malformed = stack.publication.acquires
        assertNull(
            "unsettled malformed evidence must refuse, never delete",
            stack.coordinator.prepareOrdinaryRecovery(session),
        )
        assertEquals("malformed row must survive refusal", before, harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT))
        assertNoNewDrain(stack.transition, stack.publication, t0malformed, p0malformed, "malformed prepare")
    }

    @Test
    fun snapshotFailureAfterDiscard_preservesGraphAndNullCursor() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        val successBefore = stack.lastSuccess()
        val token = checkNotNull(stack.coordinator.prepareOrdinaryRecovery(session))
        stack.transport.queueCode(500, """{"errorCode":"probe-transient"}""")
        val stopped = stack.coordinator.recoverOrdinaryRejection(session, token)
        assertEquals(AppReconciliationStop.SNAPSHOT_STOPPED, stopped.stop)
        val stage = checkNotNull(stopped.retainedStageId) {
            "failed recovery must retain an exact restart stage"
        }
        assertTrue(stage.isNotBlank())
        // Committed discard keeps the old visible graph with a durable
        // null-cursor marker: restart requires a complete snapshot, never a
        // publication of old canonical state as success.
        assertEquals(0L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        assertNull(harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals(successBefore, stack.lastSuccess())
        assertTrue(harness.customNames(stack.database, AppSessionFixtures.ACCOUNT).contains(EXERCISE_ONE))
        stack.transport.queue.clear()
        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val recovered = stack.coordinator.reconcile(
            session,
            stack.coordinator.observeCacheEpoch(session),
            restartStageId = stage,
        )
        assertEquals(AppReconciliationStop.TAIL, recovered.stop)
        assertEquals("session-baseline", harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT))
        assertTrue(
            "complete EMPTY snapshot replaces the discarded unsynced graph",
            !harness.customNames(stack.database, AppSessionFixtures.ACCOUNT).contains(EXERCISE_ONE),
        )
    }

    // ---- Legacy import destructive boundary ----

    private class ImportLease : ProjectionPublicationLease {
        override fun checkValid() = Unit
        override fun close() = Unit
    }

    private inner class ImportCaptureBarrier : LegacyImportCaptureBarrier {
        override suspend fun acquire(expected: RecoveryRoomSnapshot): ProjectionPublicationLease =
            ImportLease()
    }

    private fun Stack.seedLocalOnlyGraph() {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_I', 'foreground Legacy Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Step\"]', 1, NULL, NULL, 1, 'local_only', NULL)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_I', 'foreground Legacy W', 4000, NULL, 'active', NULL, NULL, 2, 'local_only', NULL)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes, rest_duration_seconds)" +
                " VALUES ('$WE_I', '$WORKOUT_I', '$CUSTOM_I', 0, 'Keep', 75)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds) VALUES ('$WS_I', '$WE_I', 0, 8, 70.0, 1, 60)",
        )
    }

    private var importMutationCalls = 0

    private suspend fun Stack.captureImport(): LegacyImportTarget {
        seedLocalOnlyGraph()
        val target = LegacyImportTarget(
            AppSessionFixtures.origin(),
            AppSessionFixtures.ACCOUNT,
            OP_IMPORT,
        )
        val store = LegacyImportStore(
            database = database,
            barrier = ImportCaptureBarrier(),
            processCheck = {},
            newMutationId = { if (importMutationCalls++ == 0) MUTATION_I1 else MUTATION_I2 },
        )
        val result = store.capture(RoomSessionAuthority(database).read(), target)
        assertTrue("capture must retain genuine local-only roots", result is LegacyImportCaptureResult.Retained)
        assertEquals(
            2L,
            (result as LegacyImportCaptureResult.Retained).evidence.importCount,
        )
        return target
    }

    private fun importRecordFor(aggregateType: String, aggregateId: String): org.json.JSONObject {
        if (aggregateType == "customExercise" && aggregateId == CUSTOM_I) {
            return org.json.JSONObject()
                .put("id", CUSTOM_I)
                .put("name", "foreground Legacy Custom")
                .put("type", "reps")
                .put("category", "strength")
                .put("difficultyLevel", org.json.JSONObject.NULL)
                .put("forceType", org.json.JSONObject.NULL)
                .put("mechanics", org.json.JSONObject.NULL)
                .put("instructions", org.json.JSONArray().put("Step"))
                .put("isUserCreated", true)
                .put("revision", 1)
                .put("muscleGroups", org.json.JSONArray())
                .put("equipment", org.json.JSONArray())
                .put("primaryMuscleGroupId", org.json.JSONObject.NULL)
                .put("secondaryMuscleGroupIds", org.json.JSONArray())
                .put("equipmentId", org.json.JSONObject.NULL)
                .put("equipmentNumItems", org.json.JSONObject.NULL)
        }
        if (aggregateType == "workout" && aggregateId == WORKOUT_I) {
            return org.json.JSONObject()
                .put("id", WORKOUT_I)
                .put("name", "foreground Legacy W")
                .put("status", "active")
                .put("startedAt", "1970-01-01T00:00:04Z")
                .put("revision", 1)
                .put(
                    "exercises",
                    org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("id", WE_I)
                            .put("exerciseId", CUSTOM_I)
                            .put("exerciseName", "foreground Legacy Custom")
                            .put("position", 0)
                            .put("notes", "Keep")
                            .put("restDurationSeconds", 75)
                            .put(
                                "sets",
                                org.json.JSONArray().put(
                                    org.json.JSONObject()
                                        .put("id", WS_I)
                                        .put("position", 0)
                                        .put("repetitions", 8)
                                        .put("weightKg", 70.0)
                                        .put("isCompleted", true)
                                        .put("restSeconds", 60),
                                ),
                            ),
                    ),
                )
        }
        throw AssertionError("unexpected import root $aggregateType:$aggregateId")
    }

    private fun importBatchFor(mutationId: String): String = when (mutationId) {
        MUTATION_I1 -> BATCH_I1
        MUTATION_I2 -> BATCH_I2
        else -> throw AssertionError("no batch for $mutationId")
    }

    private fun importAcceptedOutcome(
        mutationId: String,
        aggregateType: String,
        aggregateId: String,
    ): org.json.JSONObject {
        val batch = org.json.JSONObject()
            .put("batchId", importBatchFor(mutationId))
            .put("cursor", "foreground-import-cursor")
            .put(
                "changes",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("aggregateType", aggregateType)
                        .put("aggregateId", aggregateId)
                        .put("operation", "upsert")
                        .put("revision", 1)
                        .put("record", importRecordFor(aggregateType, aggregateId)),
                ),
            )
        return org.json.JSONObject()
            .put("mutationId", mutationId)
            .put("status", "accepted")
            .put("errorCode", org.json.JSONObject.NULL)
            .put("batch", batch)
    }

    private fun importSnapshot(): String = org.json.JSONObject()
        .put(
            "records",
            org.json.JSONArray(
                listOf(
                    org.json.JSONObject()
                        .put("aggregateType", "customExercise")
                        .put("aggregateId", CUSTOM_I)
                        .put("operation", "upsert")
                        .put("revision", 1)
                        .put("record", importRecordFor("customExercise", CUSTOM_I)),
                    org.json.JSONObject()
                        .put("aggregateType", "workout")
                        .put("aggregateId", WORKOUT_I)
                        .put("operation", "upsert")
                        .put("revision", 1)
                        .put("record", importRecordFor("workout", WORKOUT_I)),
                ),
            ),
        )
        .put("baselineCursor", "foreground-baseline")
        .put("nextPageToken", org.json.JSONObject.NULL)
        .put("hasMore", false)
        .toString()

    private fun importEmptyTail(): String = org.json.JSONObject()
        .put("batches", org.json.JSONArray())
        .put("nextCursor", "foreground-baseline")
        .put("hasMore", false)
        .toString()

    /**
     * Serves genuine accepted batches per pushed mutation (IDs read from the
     * actual request envelope, never asserted expectations), then the matching
     * complete snapshot plus the pull tail.
     */
    private fun serveImportSettlement(transport: AppSessionTransport) {
        transport.serve = { _, request ->
            when {
                request.path.contains("push") -> {
                    val mutations = org.json.JSONObject(
                        checkNotNull(request.body) { "push requires a body" },
                    ).getJSONArray("mutations")
                    val outcomes = (0 until mutations.length()).map { index ->
                        val envelope = mutations.getJSONObject(index)
                        importAcceptedOutcome(
                            envelope.getString("mutationId"),
                            envelope.getString("aggregateType"),
                            envelope.getString("aggregateId"),
                        )
                    }
                    AppSessionTransport.Frame(
                        200,
                        org.json.JSONObject().put("results", org.json.JSONArray(outcomes)).toString(),
                    )
                }
                request.path.contains("snapshot") ->
                    AppSessionTransport.Frame(200, importSnapshot())
                else -> AppSessionTransport.Frame(200, importEmptyTail())
            }
        }
    }

    private fun Stack.policyCoordinator(
        seen: MutableList<LegacyImportReplacementRequest>,
        transition: ScriptedTransitionBarrier,
        publication: ScriptedPublicationBarrier,
    ): AppSessionCoordinator = AppSessionCoordinator(
        database = database,
        origin = AppSessionFixtures.origin(),
        vault = vault,
        authApi = auth,
        transport = transport,
        runOwner = PushRunOwner(),
        transitionBarrier = transition,
        publicationBarrier = publication,
        clockMillis = { AppSessionFixtures.FIXED_CLOCK },
        uuidProvider = runUuids(listOf(OP_IMPORT, STAGE_I, OP_IMPORT, STAGE_I)),
        legacyImportPolicy = LegacyImportPolicy(
            replacementConsent = LegacyImportReplacementConsent { request ->
                seen += request
                true
            },
        ),
    )

    private fun runUuids(values: List<String>): () -> String {
        val iterator = values.iterator()
        var fallback = 0
        return {
            if (iterator.hasNext()) iterator.next()
            else "e13d900${fallback++}-1111-4111-8111-111111111111"
        }
    }

    private fun importAcceptanceCount(database: GymDatabase): Long {
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sync_legacy_import_acceptances",
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun importEntryCount(database: GymDatabase): Long {
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sync_legacy_import_entries",
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    @Test
    fun rejectedImportRow_refusesOrdinaryRecovery() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        // Narrow injected corrupt-import fixture in ACTIVE scope: a rejected
        // aggregate.import row with a valid wrapper. P18 capture after ACTIVE
        // authority is forbidden, so no capture runs here; the row stands in
        // for preserved preactivation import evidence. Import intent is never
        // an ordinary discard target: preparation must refuse closed while
        // queue and source stay byte-identical.
        val inner = org.json.JSONObject()
            .put("mutationId", MUTATION_I3)
            .put("type", "aggregate.import")
            .put("aggregateType", "customExercise")
            .put("aggregateId", CUSTOM_X)
            .put("localGeneration", 1)
            .put("payload", org.json.JSONObject())
            .toString()
        val wrapper = org.json.JSONObject()
            .put("payloadVersion", 1)
            .put("requestJson", inner)
            .put(
                "affectedRoots",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("aggregateType", "customExercise")
                        .put("aggregateId", CUSTOM_X)
                        .put("localGeneration", 1),
                ),
            )
            .toString()
        stack.database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES ((SELECT COALESCE(MAX(sequence), 0) + 1 FROM sync_outbox), ?, ?," +
                " 'customExercise', ?, 'aggregate.import', 1, 1, ?, 'rejected'," +
                " 1, NULL, 'not_found', NULL)",
            arrayOf(MUTATION_I3, AppSessionFixtures.ACCOUNT, CUSTOM_X, wrapper),
        )
        val before = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals(2, before.size)
        val t0import = stack.transition.acquires
        val p0import = stack.publication.acquires
        // The ordinary rejection still needs attention; import evidence blocks preparation.
        assertTrue(stack.coordinator.observeSyncStatus(session).first().hasRejectedOrdinaryWork)
        assertEquals(OrdinaryRecoveryPreparation.Blocked, stack.coordinator.prepareOrdinaryRecoveryOutcome(session))
        assertNull(
            "rejected import intent must refuse ordinary recovery",
            stack.coordinator.prepareOrdinaryRecovery(session),
        )
        assertEquals(
            "refusal must keep queue and source byte-identical",
            before,
            harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT),
        )
        assertNoNewDrain(stack.transition, stack.publication, t0import, p0import, "import-row refusal")
    }

    /**
     * Genuine pre-activation import settlement through the real opt-in
     * path: capture, accepted delivery, complete snapshot, receipts plus
     * SETTLED proof. Returns the Active handle on the settled account.
     */
    private suspend fun Stack.settleImport(
        transition: ScriptedTransitionBarrier = ScriptedTransitionBarrier(),
        publication: ScriptedPublicationBarrier = ScriptedPublicationBarrier(),
    ): Pair<AppSessionCoordinator, AppAccountSession> {
        captureImport()
        val seen = mutableListOf<LegacyImportReplacementRequest>()
        val facade = policyCoordinator(seen, transition, publication)
        serveImportSettlement(transport)
        val preparing = facade.acceptGrant(
            RoomSessionAuthority(database).read(),
            OP_IMPORT,
            AppSessionFixtures.grant(),
        )
        assertTrue("settlement must prepare Private, was $preparing", preparing is AppSessionState.Private)
        val active = facade.completeActivation(RoomSessionAuthority(database).read(), STAGE_I)
        assertTrue("settlement must reach Active, was $active", active is AppSessionState.Active)
        val session = (active as AppSessionState.Active).session
        assertEquals("settlement must receipt both imports", 2L, importAcceptanceCount(database))
        val settledProgress = facade.observeLegacyImport()
        assertEquals(2L, settledProgress!!.importCount)
        assertEquals(
            "settled proof derives only from full acceptance",
            settledProgress.importCount,
            settledProgress.acceptedCount,
        )
        return facade to session
    }

    @Test
    fun pendingOnly_prepareAcquiresNoDrain() = runTest(timeout = 90.seconds) {
        val stack = stack("pending")
        val session = stack.activate()
        // One never-sent edit: nothing confirmable, and the empty decision
        // must not touch real drains.
        session.repository.createCustomExercise(EXERCISE_TWO, null, emptySet(), null, null, "")
        assertEquals(1L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        val t0pending = stack.transition.acquires
        val p0pending = stack.publication.acquires
        assertNull(
            "pending-only queue offers no confirmable rejection",
            stack.coordinator.prepareOrdinaryRecovery(session),
        )
        assertEquals(1L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        assertNoNewDrain(stack.transition, stack.publication, t0pending, p0pending, "pending-only prepare")
    }

    @Test
    fun settledImportWithoutOrdinary_prepareNullWithoutDrain() = runTest(timeout = 90.seconds) {
        val stack = stack("import-quiet")
        val transition = ScriptedTransitionBarrier()
        val publication = ScriptedPublicationBarrier()
        val (facade, session) = stack.settleImport(transition, publication)
        // Settled import present, zero ordinary rows: nothing confirmable,
        // no drain, receipts untouched by the mere question. The session comes
        // from the actual settled facade, never the unused coordinator.
        assertEquals(0L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        val t0quiet = transition.acquires
        val p0quiet = publication.acquires
        assertNull(
            "settled import alone offers no confirmable rejection",
            facade.prepareOrdinaryRecovery(session),
        )
        assertEquals(2L, importAcceptanceCount(stack.database))
        assertEquals(2L, importEntryCount(stack.database))
        assertNoNewDrain(transition, publication, t0quiet, p0quiet, "settled-quiet prepare")
    }

    @Test
    fun settledImport_permitsOrdinaryRecoveryWithoutTouchingReceipts() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val (facade, session) = stack.settleImport()
        // Fully settled retained evidence must not disable recovery of later
        // ordinary edits forever.
        session.repository.createCustomExercise(EXERCISE_ONE, null, emptySet(), null, null, "")
        val mutationId = checkNotNull(
            harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)[0][1],
        )
        stack.transport.queue.clear()
        stack.transport.serve = null
        stack.transport.queueOk(rejectedPushBody(mutationId))
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val pushed = facade.reconcile(session, facade.observeCacheEpoch(session))
        assertTrue(pushed.stop != AppReconciliationStop.TAIL)
        stack.transport.queue.clear()
        val token = checkNotNull(facade.prepareOrdinaryRecovery(session)) {
            "settled import must permit ordinary recovery"
        }
        assertEquals(1L, token.pendingCount)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val result = facade.recoverOrdinaryRejection(session, token)
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertEquals(0L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals("session-baseline", harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals(
            "original import receipts are never discard targets",
            2L,
            importAcceptanceCount(stack.database),
        )
        assertEquals("import entries survive ordinary recovery", 2L, importEntryCount(stack.database))
    }

    @Test
    fun confirmInvalidatedWhileQueued_refusesWithIntentIntact() = runTest(timeout = 90.seconds) {
        val gate = com.dragosfotache.gymtracka.data.sync.session.GatedTransactionExecutor()
        val database = harness.openGatedInMemoryDb(gate).also { dbs += it }
        val transport = AppSessionTransport()
        val vault = harness.newVault()
        val auth = AppSessionAuthApi()
        val owner = AppSessionFixtures.owner()
        val transition = ScriptedTransitionBarrier()
        val publication = ScriptedPublicationBarrier()
        val coordinator = AppSessionFixtures.coordinator(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = vault,
            auth = auth,
            transport = transport,
            runOwner = owner,
            transitionBarrier = transition,
            publicationBarrier = publication,
        )
        val gated = Stack(database, coordinator, transport, vault, auth, owner, transition, publication)
        val session = gated.activate()
        gated.seedDefinitiveRejection(session)
        val token = checkNotNull(gated.coordinator.prepareOrdinaryRecovery(session))
        val before = harness.outboxSnapshot(database, AppSessionFixtures.ACCOUNT)
        // Arm only now: seeding/prepare transactions must run ungated.
        gate.gateNext = true
        val outcome = CompletableDeferred<Result<AppReconciliationResult>>()
        val job = launch {
            try {
                outcome.complete(Result.success(gated.coordinator.recoverOrdinaryRejection(session, token)))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        runCurrent()
        assertTrue("confirm must enter the gated transaction", gate.entered())
        try {
            // Retiring the handle while the first transaction is still queued
            // poisons admission: the guards refuse stale and nothing commits.
            // This proves entry/queued staleness only; post-final-SQL
            // rollback is proven separately by the abort-trigger test below.
            gated.coordinator.invalidate()
            gate.releaseLatch.countDown()
            job.join()
        } finally {
            gate.releaseLatch.countDown()
        }
        val failure = outcome.await().exceptionOrNull()
        val refused = failure != null
        assertTrue("invalidated confirm must not succeed, got $failure", refused)
        if (failure !is kotlinx.coroutines.CancellationException) {
            AppSessionFixtures.assertStale(failure!!)
        }
        assertEquals(
            "refused confirm leaves intent byte-identical",
            before,
            harness.outboxSnapshot(database, AppSessionFixtures.ACCOUNT),
        )
        gate.shutdown()
    }

    @Test
    fun confirmAbortedAtFinalDelete_restoresEverythingAndRetriesClean() = runTest(timeout = 90.seconds) {
        val stack = stack()
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        val token = checkNotNull(stack.coordinator.prepareOrdinaryRecovery(session))
        val rowsBefore = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        val cursorBefore = harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT)
        val cacheBefore = stack.cacheRow()
        val namesBefore = harness.customNames(stack.database, AppSessionFixtures.ACCOUNT)
        // AFTER-WRITES fault inside the final guard: abort the captured-row
        // deletions the discard must perform. The whole transaction must roll
        // back; nothing may be half-deleted.
        stack.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER foreground_abort_discard AFTER DELETE ON sync_outbox" +
                " BEGIN SELECT RAISE(ABORT, 'foreground-discard-fault'); END",
        )
        stack.transport.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        stack.transport.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val failure = runCatching {
            stack.coordinator.recoverOrdinaryRejection(session, token)
        }.exceptionOrNull()
        val fault = checkNotNull(failure) { "aborted final guard must fail, not discard" }
        assertTrue("aborted confirm must not cancel", fault !is kotlinx.coroutines.CancellationException)
        AppSessionFixtures.assertClosed(fault)
        assertEquals(
            "aborted discard restores every row",
            rowsBefore,
            harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT),
        )
        assertEquals("aborted discard restores the cursor", cursorBefore, harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT))
        assertEquals("aborted discard restores cache bytes", cacheBefore, stack.cacheRow())
        assertEquals(
            "aborted discard restores domain names",
            namesBefore,
            harness.customNames(stack.database, AppSessionFixtures.ACCOUNT),
        )
        // Dropping the fault leaves a retryable state: a fresh decision
        // completes the same recovery end to end on the queued bodies.
        stack.database.openHelper.writableDatabase.execSQL("DROP TRIGGER foreground_abort_discard")
        val retry = checkNotNull(stack.coordinator.prepareOrdinaryRecovery(session))
        val result = stack.coordinator.recoverOrdinaryRejection(session, retry)
        assertEquals(AppReconciliationStop.TAIL, result.stop)
        assertEquals(0L, harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT))
    }

    @Test
    fun committedDiscard_survivesFileReopenWithNullCursor() = runTest(timeout = 90.seconds) {
        val name = "foreground-recovery-file.db"
        val database = harness.createFileDb(name).also { dbs += it }
        val transport = AppSessionTransport()
        val vault = harness.newVault()
        val auth = AppSessionAuthApi()
        val owner = AppSessionFixtures.owner()
        val transition = ScriptedTransitionBarrier()
        val publication = ScriptedPublicationBarrier()
        val coordinator = AppSessionFixtures.coordinator(
            database = database,
            origin = AppSessionFixtures.origin(),
            vault = vault,
            auth = auth,
            transport = transport,
            runOwner = owner,
            transitionBarrier = transition,
            publicationBarrier = publication,
        )
        val fileStack = Stack(database, coordinator, transport, vault, auth, owner, transition, publication)
        val session = fileStack.activate()
        fileStack.seedDefinitiveRejection(session)
        val successBefore = fileStack.lastSuccess()
        val token = checkNotNull(fileStack.coordinator.prepareOrdinaryRecovery(session))
        fileStack.transport.queueCode(500, """{"errorCode":"probe-transient"}""")
        val stopped = fileStack.coordinator.recoverOrdinaryRejection(session, token)
        assertEquals(AppReconciliationStop.SNAPSHOT_STOPPED, stopped.stop)
        val stage = checkNotNull(stopped.retainedStageId)
        // Crash between committed discard and snapshot success: the durable
        // null-cursor marker plus empty outbox must survive a file reopen.
        database.close()
        val reopened = harness.reopenFileDb(name).also { dbs += it }
        assertEquals(0L, harness.outboxCount(reopened, AppSessionFixtures.ACCOUNT))
        assertNull(harness.syncStateCursor(reopened, AppSessionFixtures.ACCOUNT))
        assertEquals(
            successBefore,
            reopened.openHelper.readableDatabase.query(
                "SELECT last_success_millis FROM sync_state WHERE account_id = ?",
                arrayOf(AppSessionFixtures.ACCOUNT),
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
            },
        )
        val transport2 = AppSessionTransport()
        val vault2 = harness.reopenVault()
        val coordinator2 = AppSessionFixtures.coordinator(
            database = reopened,
            origin = AppSessionFixtures.origin(),
            vault = vault2,
            auth = AppSessionAuthApi(),
            transport = transport2,
            runOwner = AppSessionFixtures.owner(),
            transitionBarrier = ScriptedTransitionBarrier(),
            publicationBarrier = ScriptedPublicationBarrier(),
        )
        // The reopened handle must restore the same ACTIVE account: offline
        // restart keeps established accounts usable without a fresh login.
        val restate = coordinator2.recover()
        val live = when (restate) {
            is AppSessionState.Active -> restate.session
            else -> error("reopen must restore an Active handle, was $restate")
        }
        transport2.queueOk(AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
        transport2.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        transport2.queueOk(AppSessionFixtures.EMPTY_PULL_BODY)
        val recovered = coordinator2.reconcile(
            live,
            coordinator2.observeCacheEpoch(live),
            restartStageId = stage,
        )
        assertEquals(AppReconciliationStop.TAIL, recovered.stop)
        assertEquals("session-baseline", harness.syncStateCursor(reopened, AppSessionFixtures.ACCOUNT))
    }

    private fun ordinaryCustomRecord(id: String, name: String): org.json.JSONObject = org.json.JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", "reps")
        .put("category", "strength")
        .put("difficultyLevel", org.json.JSONObject.NULL)
        .put("forceType", org.json.JSONObject.NULL)
        .put("mechanics", org.json.JSONObject.NULL)
        .put("instructions", org.json.JSONArray().put("Step"))
        .put("isUserCreated", true)
        .put("revision", 1)
        .put("muscleGroups", org.json.JSONArray())
        .put("equipment", org.json.JSONArray())
        .put("primaryMuscleGroupId", org.json.JSONObject.NULL)
        .put("secondaryMuscleGroupIds", org.json.JSONArray())
        .put("equipmentId", org.json.JSONObject.NULL)
        .put("equipmentNumItems", org.json.JSONObject.NULL)

    private fun ordinaryAcceptedOutcome(mutationId: String, aggregateId: String, name: String): org.json.JSONObject {
        val batch = org.json.JSONObject()
            .put("batchId", "b0000001-d13d-4d13-8d13-d13bd13bd13b")
            .put("cursor", "foreground-ordinary-cursor")
            .put(
                "changes",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("aggregateType", "customExercise")
                        .put("aggregateId", aggregateId)
                        .put("operation", "upsert")
                        .put("revision", 1)
                        .put("record", ordinaryCustomRecord(aggregateId, name)),
                ),
            )
        return org.json.JSONObject()
            .put("mutationId", mutationId)
            .put("status", "accepted")
            .put("errorCode", org.json.JSONObject.NULL)
            .put("batch", batch)
    }

    @Test
    fun frozenHeadAccepted_prepareSettlesWithoutTouchingSuccessor() = runTest(timeout = 90.seconds) {
        val stack = stack("frozen-acc")
        val session = stack.activate()
        session.repository.createCustomExercise("foreground Frozen Head", null, emptySet(), null, null, "")
        session.repository.createCustomExercise("foreground Pending Tail", null, emptySet(), null, null, "")
        val pending = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals(2, pending.size)
        val headId = checkNotNull(pending[0][1]) { "head must carry a mutation ID" }
        val tailId = checkNotNull(pending[1][1]) { "tail must carry a mutation ID" }
        // Freeze the head without delivering it: suspend the push response,
        // enter the run, then cancel the caller. Uncertainty is retained and
        // the tail is never touched.
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var firstResponse = true
        stack.transport.responseGate = {
            if (firstResponse) {
                firstResponse = false
                entered.complete(Unit)
                hold.await()
            }
        }
        val run = backgroundScope.async {
            stack.coordinator.reconcile(session, stack.coordinator.observeCacheEpoch(session))
        }
        entered.await()
        runCurrent()
        val frozen = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals("uncertain head must freeze exactly once", 2, frozen.size)
        assertEquals("frozen", frozen[0][9])
        assertEquals("tail stays pending while head is uncertain", "pending", frozen[1][9])
        run.cancel()
        hold.complete(Unit)
        runCatching { run.await() }
        stack.transport.responseGate = null
        val retained = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals(
            "cancelled uncertainty keeps exact frozen bytes",
            harness.frozenIdentity(pending),
            harness.frozenIdentity(retained),
        )
        // Preparation settles ONLY the frozen head through its exact replay:
        // any other push during preparation fails the test loudly.
        var pushes = 0
        stack.transport.serve = { _, request ->
            check(request.path.contains("push")) { "preparation must not pull/snapshot" }
            pushes += 1
            val envelope = org.json.JSONObject(checkNotNull(request.body) { "push requires a body" })
                .getJSONArray("mutations").getJSONObject(0)
            check(envelope.getString("mutationId") == headId) {
                "preparation must never send the pending successor"
            }
            AppSessionTransport.Frame(
                200,
                org.json.JSONObject().put(
                    "results",
                    org.json.JSONArray().put(
                        ordinaryAcceptedOutcome(
                            headId,
                            checkNotNull(pending[0][4]) { "head needs an aggregate ID" },
                            "foreground Frozen Head",
                        ),
                    ),
                ).toString(),
            )
        }
        assertNull(
            "accepted settlement leaves no ordinary discard",
            stack.coordinator.prepareOrdinaryRecovery(session),
        )
        assertEquals("preparation sends exactly one replay POST", 1, pushes)
        val settled = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals("accepted head is acknowledged away", 1, settled.size)
        assertEquals("successor keeps its identity", tailId, settled[0][1])
        assertEquals(
            "successor full row unchanged by preparation",
            harness.frozenIdentity(listOf(pending[1])),
            harness.frozenIdentity(settled),
        )
        assertEquals("successor stays pending", "pending", settled[0][9])
        stack.transport.serve = null
    }

    @Test
    fun coalescedTail_refusesWithRowsAndDomainIntact() = runTest(timeout = 90.seconds) {
        val stack = stack("coalesce")
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        val token = checkNotNull(stack.coordinator.prepareOrdinaryRecovery(session))
        // Same-root same-kind tail: the second rename must coalesce into the
        // first instead of appending a new row.
        val workoutId = session.repository.startEmptyWorkout(1_700_000_000_000L).id
        session.repository.updateWorkoutName(workoutId, "foreground Coal One")
        val firstTail = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        session.repository.updateWorkoutName(workoutId, "foreground Coal Two")
        val coalesced = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals("coalesced tail keeps row count", firstTail.size, coalesced.size)
        val before = firstTail.last { it[4] == workoutId }
        val after = coalesced.last { it[4] == workoutId }
        assertEquals("same sequence", before[0], after[0])
        assertEquals("same mutation ID", before[1], after[1])
        assertTrue("newer payload bytes", before[8] != after[8])
        assertTrue(
            "generation strictly increases on coalesce",
            (after[6]?.toLongOrNull() ?: 0L) > (before[6]?.toLongOrNull() ?: 0L),
        )
        val error = runCatching {
            stack.coordinator.recoverOrdinaryRejection(session, token)
        }.exceptionOrNull()
        AppSessionFixtures.assertStale(checkNotNull(error) { "coalesced tail must refuse" })
        assertEquals(
            "refusal keeps full rows identical",
            coalesced,
            harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT),
        )
        assertEquals(
            "domain keeps the newer coalesced value",
            "foreground Coal Two",
            session.repository.workoutDetails(workoutId)?.workout?.name,
        )
    }

    @Test
    fun editAdmittedDuringTerminalPullProtectedWithGraph() = runTest(timeout = 90.seconds) {
        val stack = stack("snapshot-edit")
        val session = stack.activate()
        stack.seedDefinitiveRejection(session)
        val token = checkNotNull(stack.coordinator.prepareOrdinaryRecovery(session))
        // Admit a genuinely newer edit during the POST-BASELINE terminal
        // pull (after push-empty, before tail/publication): the push stage is
        // already past, so no delivery can sweep it in. The run must stop
        // PROTECTED with the pending row exact and the whole visible graph
        // (old discarded-scope rows plus the new row) preserved.
        stack.transport.serve = { _, request ->
            when {
                request.path.contains("snapshot") ->
                    AppSessionTransport.Frame(200, AppSessionFixtures.EMPTY_SNAPSHOT_BODY)
                else -> AppSessionTransport.Frame(200, AppSessionFixtures.EMPTY_PULL_BODY)
            }
        }
        var responses = 0
        val pullEntered = CompletableDeferred<Unit>()
        val pullHold = CompletableDeferred<Unit>()
        stack.transport.responseGate = {
            responses += 1
            if (responses == 2) {
                pullEntered.complete(Unit)
                pullHold.await()
            }
        }
        val recovery = backgroundScope.async {
            stack.coordinator.recoverOrdinaryRejection(session, token)
        }
        pullEntered.await()
        val newId = session.repository.createCustomExercise("foreground Protected New", null, emptySet(), null, null, "")
        val admitted = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        pullHold.complete(Unit)
        val result = recovery.await()
        stack.transport.responseGate = null
        stack.transport.serve = null
        val paths = stack.transport.capturedRequests.map { it.path }
        assertTrue("recovery must perform real I/O", paths.isNotEmpty())
        val cursor = harness.syncStateCursor(stack.database, AppSessionFixtures.ACCOUNT)
        println(
            "accountD-PATHS-DIAG paths=$paths stop=${result.stop} " +
                "pushStop=${result.delivery?.push?.stop?.javaClass?.simpleName} " +
                "pullStop=${result.delivery?.pull?.stop?.javaClass?.simpleName} " +
                "outbox=${harness.outboxCount(stack.database, AppSessionFixtures.ACCOUNT)} " +
                "cursorNull=${cursor == null} cursorIsBaseline=${cursor == "session-baseline"}",
        )
        assertEquals(AppReconciliationStop.PROTECTED, result.stop)
        val remaining = harness.outboxSnapshot(stack.database, AppSessionFixtures.ACCOUNT)
        assertEquals("exactly the protected newer edit remains", 1, remaining.size)
        assertEquals("protected edit keeps its identity", newId, remaining[0][4])
        assertEquals(
            "protected edit keeps exact bytes",
            admitted,
            remaining,
        )
        val names = harness.customNames(stack.database, AppSessionFixtures.ACCOUNT)
        assertTrue("old visible graph remains", names.contains(EXERCISE_ONE))
        assertTrue("new visible graph remains", names.contains("foreground Protected New"))
    }
}
