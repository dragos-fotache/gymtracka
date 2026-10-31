package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room and namespaced Android encrypted-file/Keystore checks for exact pending-refresh
 * reconciliation and durable reopen.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionPendingRefreshRoomVaultTest {

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val ACCOUNT_B = "c6938596-d7e7-50bb-0689-8b3f748dccd1"
        const val OP_OLD = "11111111-1111-1111-1111-111111111111"
        const val OP_NEW = "22222222-2222-2222-2222-222222222222"
        const val KEY_OLD = "33333333-3333-3333-3333-333333333333"
        const val ORIGIN_A = "https://sync.example.test:443"
        const val WORKOUT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val WORKOUT_FOREIGN = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val MUT_FROZEN = "55555555-5555-5555-5555-555555555555"
        const val STAGE_1 = "stage-1"
        const val PENDING_BODY = " { \"refreshToken\" : \"opaque-refresh-old\" } "
    }

    private fun originA(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_A)

    private fun bindingA(generation: Long = 7L, accountId: String = ACCOUNT_A): ServiceSessionBinding =
        ServiceSessionBinding(originA(), accountId, generation)

    private fun oldGrant(): ServiceSession = ServiceSession(
        ServiceAccountRef(ACCOUNT_A, null),
        OpaqueToken("opaque-access-old"),
        1_700_000_000_000L,
        OpaqueToken("opaque-refresh-old"),
        1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    )

    private fun replayedGrant(): ServiceSession = ServiceSession(
        ServiceAccountRef(ACCOUNT_A, null),
        OpaqueToken("opaque-access-replayed"),
        1_700_000_000_000L,
        OpaqueToken("opaque-refresh-replayed"),
        1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    )

    private fun finalGrant(): ServiceSession = ServiceSession(
        ServiceAccountRef(ACCOUNT_A, null),
        OpaqueToken("opaque-access-new"),
        1_700_000_000_000L,
        OpaqueToken("opaque-refresh-new"),
        1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    )

    private fun pendingOld(): VaultPendingRefresh =
        VaultPendingRefresh(OP_OLD, ServiceRefreshAttempt.fromJson(KEY_OLD, PENDING_BODY))

    private fun frozenWrapper(): String {
        val inner = "{\"mutationId\":\"$MUT_FROZEN\",\"type\":\"workout.start\"," +
            "\"aggregateType\":\"workout\",\"aggregateId\":\"$WORKOUT_A\"," +
            "\"localGeneration\":4,\"payload\":{\"id\":\"$WORKOUT_A\",\"name\":\"pending-refresh\"}}"
        val escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"payloadVersion\":1,\"requestJson\":\"$escaped\"," +
            "\"affectedRoots\":[{\"aggregateType\":\"workout\",\"aggregateId\":\"$WORKOUT_A\"," +
            "\"localGeneration\":4}]}"
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val usedNamespaces = mutableListOf<String>()
    private val usedDatabases = mutableListOf<String>()

    private fun testNamespace(suffix: String): String {
        val namespace = "gymtracka-test-pending-refresh-$suffix"
        usedNamespaces.add(namespace)
        return namespace
    }

    private fun testAlias(namespace: String): String =
        "com.dragosfotache.gymtracka.session.$namespace"

    private fun deleteNamespacePath(namespace: String) {
        val dir = File(context.noBackupFilesDir, namespace)
        try {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        } catch (_: Exception) {
        }
        try {
            KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                .deleteEntry(testAlias(namespace))
        } catch (_: Exception) {
        }
    }

    @After
    fun cleanOnlyOwnTestArtifacts() {
        for (namespace in usedNamespaces) deleteNamespacePath(namespace)
        usedNamespaces.clear()
        for (name in usedDatabases) {
            try {
                context.deleteDatabase(name)
            } catch (_: Exception) {
            }
        }
        usedDatabases.clear()
    }

    private fun openInMemoryDb(): GymDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, GymDatabase::class.java).build()

    /** Create-once file DB: deletes any prior copy, then builds and tracks it. */
    private fun createFileDb(name: String): GymDatabase {
        usedDatabases += name
        context.deleteDatabase(name)
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    /** Non-destructive reopen: the same physical DB must survive close/reopen. */
    private fun reopenFileDb(name: String): GymDatabase {
        if (!usedDatabases.contains(name)) usedDatabases += name
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    /** ACTIVE journal/device at generation 7 plus the complete baseline cache. */
    private fun seedActiveAuthorityWithCompleteCache(database: GymDatabase) {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 11, 7, 'active', '$ORIGIN_A', '$ACCOUNT_A', '$OP_OLD')",
        )
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, '$ACCOUNT_A', '$ACCOUNT_A', 7, 'ready')",
        )
        db.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$ACCOUNT_A', 1, 5, 1, 4)",
        )
    }

    /** Retained domain/queue/checkpoint/cache scope with real frozen bytes + foreign rows. */
    private fun seedRetainedWithFrozen(database: GymDatabase) {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_A', 'Active W', 5000, NULL, 'active'," +
                " '$ACCOUNT_A', 5, 3, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FOREIGN', 'Foreign W', 4000, NULL, 'active'," +
                " '$ACCOUNT_B', NULL, 4, 'pending', NULL)",
        )
        val frozen = frozenWrapper()
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (10, '$MUT_FROZEN', '$ACCOUNT_A', 'workout', '$WORKOUT_A'," +
                " 'workout.start', 4, 1, '$frozen', 'frozen', 1, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$ACCOUNT_A', 'cursor-a', 7000)",
        )
        db.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$ACCOUNT_B', 'cursor-b', NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_canonical_roots (account_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_state, canonical_json," +
                " local_generation_watermark, cache_epoch)" +
                " VALUES ('$ACCOUNT_A', 'workout', '$WORKOUT_A', 7, 'upsert'," +
                " '{\"id\":\"$WORKOUT_A\"}', 4, 5)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_stages (stage_id, account_id, session_generation, cache_epoch," +
                " page_limit, source_state_present, source_cursor, source_last_success_millis," +
                " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                " page_count, record_count)" +
                " VALUES ('$STAGE_1', '$ACCOUNT_A', 7, 5, 50, 1, 'cursor-a', 7000, 1," +
                " 'baseline', NULL, 'downloading', 1, 1)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_records (stage_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_json, page_number, record_position)" +
                " VALUES ('$STAGE_1', 'workout', '$WORKOUT_A', 7, '{\"id\":\"$WORKOUT_A\"}', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO sync_snapshot_tokens (stage_id, page_token, page_number)" +
                " VALUES ('$STAGE_1', 'token-1', 0)",
        )
    }

    /** Occupied old ACTIVE source vault with the exact whitespace pending predecessor. */
    private suspend fun installOldActiveVaultWithPending(namespace: String): SessionVault {
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(
            0L,
            VaultSessionRecord(bindingA(), OP_OLD, VaultSessionPhase.ACTIVE, oldGrant(), pendingOld()),
        )
        return vault
    }

    private data class LivePair(
        val reauth: SessionReauthenticationCoordinator,
        val refresh: SessionRefreshCoordinator,
        val runOwner: PushRunOwner,
        val credentialMutex: Mutex,
    )

    private fun coordinators(
        authority: RoomSessionAuthority,
        vault: SessionVault,
        auth: PreparedEngineAuthApi,
        runOwner: PushRunOwner = PushRunOwner(),
        credentialMutex: Mutex = Mutex(),
    ): LivePair {
        val reauth = SessionReauthenticationCoordinator(
            originA(), authority, vault, runOwner, credentialMutex,
        )
        // Normative sharing: same configured origin, referentially identical
        // vault, referentially identical credential mutex. Zero clock/UUID.
        val refresh = SessionRefreshCoordinator(
            origin = originA(),
            vault = vault,
            authApi = auth,
            clockMillis = { throw AssertionError("pending-refresh replay must not read the clock") },
            uuidProvider = { throw AssertionError("pending-refresh replay must not mint UUIDs") },
            mutex = credentialMutex,
        )
        return LivePair(reauth, refresh, runOwner, credentialMutex)
    }

    private fun snapshotRows(database: GymDatabase, sql: String, columns: Int): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun journalCells(database: GymDatabase): List<String?> =
        snapshotRows(
            database,
            "SELECT revision, session_generation, phase, service_origin, account_id, operation_id" +
                " FROM sync_session_authority WHERE id = 1",
            6,
        ).single()

    private fun deviceCells(database: GymDatabase): List<String?> =
        snapshotRows(
            database,
            "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state WHERE id = 1",
            4,
        ).single()

    private fun retainedSnapshot(database: GymDatabase): Map<String, List<List<String?>>> = mapOf(
        "workouts" to snapshotRows(
            database,
            "SELECT id, name, started_at_millis, finished_at_millis, status, account_id," +
                " server_revision, local_generation, sync_state, deleted_at_millis" +
                " FROM workouts ORDER BY id",
            10,
        ),
        "outbox" to snapshotRows(
            database,
            "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message" +
                " FROM sync_outbox ORDER BY sequence",
            14,
        ),
        "sync_state" to snapshotRows(
            database, "SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id", 3,
        ),
        "cache" to snapshotRows(
            database,
            "SELECT account_id, baseline_complete, cache_epoch, projection_dirty," +
                " materialized_epoch FROM sync_account_cache ORDER BY account_id",
            5,
        ),
        "canonical" to snapshotRows(
            database,
            "SELECT account_id, aggregate_type, aggregate_id, server_revision, canonical_state," +
                " canonical_json, local_generation_watermark, cache_epoch" +
                " FROM sync_canonical_roots ORDER BY account_id, aggregate_type, aggregate_id",
            8,
        ),
        "stages" to snapshotRows(
            database,
            "SELECT stage_id, account_id, session_generation, cache_epoch, page_limit," +
                " source_state_present, source_cursor, source_last_success_millis," +
                " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                " page_count, record_count FROM sync_snapshot_stages ORDER BY stage_id",
            14,
        ),
        "records" to snapshotRows(
            database,
            "SELECT stage_id, aggregate_type, aggregate_id, server_revision, canonical_json," +
                " page_number, record_position FROM sync_snapshot_records ORDER BY 1, 2, 3",
            7,
        ),
        "tokens" to snapshotRows(
            database, "SELECT stage_id, page_token, page_number FROM sync_snapshot_tokens ORDER BY 1, 2", 3,
        ),
    )

    private class DelayedReturnVault(
        private val delegate: SessionVault,
        private val gate: CasReturnGate,
    ) : SessionVault {
        override suspend fun load(): VaultSnapshot = delegate.load()
        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            val committed = delegate.compareAndSet(expectedRevision, record)
            gate.afterCommit(committed)
            return committed
        }
    }

    private class CasReturnGate {
        val committedEntered = CompletableDeferred<VaultSnapshot>()
        val release = CompletableDeferred<Unit>()
        suspend fun afterCommit(snapshot: VaultSnapshot) {
            committedEntered.complete(snapshot)
            release.await()
        }
    }

    /**
     * Test-only non-copying cancellation probe mirroring the test reauthentication
     * harness: exact identity survives coroutine stack recovery because
     * [createCopy] returns null. Plain CancellationException risks a copy on
     * the recovery path, which would break assertSame; coroutine debug
     * settings are never touched.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class NoncopyingProbeCancellation(message: String) :
        CancellationException(message),
        CopyableThrowable<NoncopyingProbeCancellation> {
        override fun createCopy(): NoncopyingProbeCancellation? = null
    }

    // ---- Happy replay then a separate reauthentication replacement on the real pair ----

    @Test
    fun happyExactReplayThenSeparateReauthenticationPreservesRetained() = runTest {
        val namespace = testNamespace("happy")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            seedRetainedWithFrozen(database)
            val beforeRetained = retainedSnapshot(database)
            val beforeJournal = journalCells(database)
            val beforeDevice = deviceCells(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVaultWithPending(namespace)
            assertEquals(1L, vault.load().revision)
            val auth = PreparedEngineAuthApi()
            auth.answer(replayedGrant())
            val pair = coordinators(authority, vault, auth)
            val expected = authority.read()

            val observation = pair.reauth.reconcilePendingRefresh(expected, pair.refresh)

            // Exactly one send of the retained whitespace attempt, one CAS.
            assertEquals(1, auth.refreshCalls)
            assertEquals(PENDING_BODY, auth.seenAttempts.single().requestJson)
            assertEquals(KEY_OLD, auth.seenAttempts.single().idempotencyKey)
            assertEquals("opaque-refresh-old", auth.seenAttempts.single().refreshToken.value)
            assertEquals(expected, observation.room)
            assertEquals(2L, observation.vaultRevision)
            val stored = checkNotNull(vault.load().record)
            assertEquals(replayedGrant(), stored.session)
            assertNull(stored.pendingRefresh)
            assertEquals(bindingA(), stored.binding)
            assertEquals(OP_OLD, stored.operationId)
            assertEquals(VaultSessionPhase.ACTIVE, stored.phase)
            // Full table snapshots around replay: journal, device AND retained
            // scope byte-identical; no Room write occurred.
            assertEquals(beforeJournal, journalCells(database))
            assertEquals(beforeDevice, deviceCells(database))
            assertEquals(beforeRetained, retainedSnapshot(database))
            val rendered = observation.toString()
            assertFalse(rendered.contains("opaque-access-replayed"))
            assertFalse(rendered.contains("opaque-refresh-replayed"))
            assertFalse(rendered.contains("opaque-refresh-old"))

            // A SEPARATE reauthentication supplied-grant replacement on the SAME shared
            // runOwner/mutex/coordinator freshly reads/admits the settled
            // pair; only journal/device may change afterwards.
            val settled = authority.read()
            assertEquals(11L, settled.revision)
            assertFalse(pair.runOwner.mutex.isLocked)
            assertFalse(pair.credentialMutex.isLocked)
            val replaced = pair.reauth.reauthenticate(settled, OP_NEW, finalGrant())
            assertEquals(13L, replaced.room.revision)
            assertEquals(8L, replaced.room.sessionGeneration)
            assertEquals(3L, replaced.vaultRevision)
            assertEquals(finalGrant(), vault.load().record?.session)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
            assertEquals(
                listOf("13", "8", "preparing", ORIGIN_A, ACCOUNT_A, OP_NEW),
                journalCells(database),
            )
            assertEquals(beforeRetained, retainedSnapshot(database))
        } finally {
            database.close()
        }
    }

    // ---- Failure before CAS + non-destructive reopen retries the exact bytes ----

    @Test
    fun transientFailureRetainsPendingThenNonDestructiveReopenRetriesExact() = runTest {
        val namespace = testNamespace("retry")
        deleteNamespacePath(namespace)
        val dbName = "pending-refresh-retry.db"
        val database = createFileDb(dbName)
        val auth = PreparedEngineAuthApi()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            seedRetainedWithFrozen(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVaultWithPending(namespace)
            val pair = coordinators(authority, vault, auth)
            val expected = authority.read()
            val boom = SyncApiException.Transient()
            auth.failWith(boom)
            try {
                pair.reauth.reconcilePendingRefresh(expected, pair.refresh)
                fail("expected Transient")
            } catch (e: SyncApiException.Transient) {
                assertSame(boom, e)
            }
            assertEquals(1L, vault.load().revision)
            assertEquals(PENDING_BODY, vault.load().record?.pendingRefresh?.attempt?.requestJson)
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalCells(database),
            )
        } finally {
            database.close()
        }

        // Non-destructive reopen: same physical DB and same vault
        // file/Keystore entry; the exact pending predecessor stands.
        val reopened = reopenFileDb(dbName)
        try {
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(1L, vault.load().revision)
            assertEquals(PENDING_BODY, vault.load().record?.pendingRefresh?.attempt?.requestJson)
            assertEquals(OP_OLD, vault.load().record?.pendingRefresh?.operationId)
            val authority = RoomSessionAuthority(reopened)
            val expected = authority.read()
            assertEquals(11L, expected.revision)
            auth.answer(replayedGrant())
            val pair = coordinators(authority, vault, auth)
            val observation = pair.reauth.reconcilePendingRefresh(expected, pair.refresh)
            assertEquals(2L, observation.vaultRevision)
            assertEquals(2, auth.refreshCalls)
            assertEquals(auth.seenAttempts[0].requestJson, auth.seenAttempts[1].requestJson)
            assertEquals(auth.seenAttempts[0].idempotencyKey, auth.seenAttempts[1].idempotencyKey)
            assertEquals(replayedGrant(), vault.load().record?.session)
            assertNull(vault.load().record?.pendingRefresh)
        } finally {
            reopened.close()
        }
    }

    // ---- Cancellation during HTTP releases locks; restart uses retained ----

    @Test
    fun cancelDuringHttpReleasesLocksAndRestartUsesRetained() = runTest {
        val namespace = testNamespace("cancel")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVaultWithPending(namespace)
            val auth = PreparedEngineAuthApi()
            val runOwner = PushRunOwner()
            val credentialMutex = Mutex()
            val pair = coordinators(authority, vault, auth, runOwner, credentialMutex)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            auth.refreshGate = {
                entered.complete(Unit)
                release.await()
            }
            auth.answer(replayedGrant())
            supervisorScope {
                val done = CompletableDeferred<Throwable>()
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        pair.reauth.reconcilePendingRefresh(authority.read(), pair.refresh)
                        done.complete(AssertionError("expected cancellation during HTTP"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                entered.await()
                val probe = NoncopyingProbeCancellation("http-pending-refresh-cancel")
                job.cancel(probe)
                release.complete(Unit)
                // Exact cancellation identity escapes the HTTP seam (the
                // canceled job is only joined, never awaited).
                assertSame(probe, done.await())
                job.join()
            }
            runCurrent()
            assertFalse(credentialMutex.isLocked)
            assertFalse(runOwner.mutex.isLocked)
            assertEquals(1L, vault.load().revision)
            assertEquals(PENDING_BODY, vault.load().record?.pendingRefresh?.attempt?.requestJson)
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalCells(database),
            )
            // Restart uses the exact retained predecessor and lands.
            auth.refreshGate = null
            auth.answer(replayedGrant())
            val retry = pair.reauth.reconcilePendingRefresh(authority.read(), pair.refresh)
            assertEquals(2L, retry.vaultRevision)
            assertNull(vault.load().record?.pendingRefresh)
        } finally {
            database.close()
        }
    }

    // ---- Durable Room reservation mid-HTTP cannot authorize a late CAS ----

    @Test
    fun roomReservationDuringDelayedRefreshCannotAuthorizeLateCas() = runTest {
        val namespace = testNamespace("roomfence")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVaultWithPending(namespace)
            val auth = PreparedEngineAuthApi()
            val pair = coordinators(authority, vault, auth)
            val expected = authority.read()
            // A real reauthentication reservation lands while the single HTTP send is
            // admitted: old Room authorization is no longer valid.
            auth.refreshGate = {
                authority.beginReauthentication(authority.read(), originA(), OP_NEW)
            }
            auth.answer(replayedGrant())
            try {
                pair.reauth.reconcilePendingRefresh(expected, pair.refresh)
                fail("expected STALE_AUTHORITY after mid-flight reservation")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.STALE_AUTHORITY, e.failure)
                assertNull(e.cause)
            }
            // Late CAS/result never authorized: vault keeps the exact pending
            // predecessor; the TRANSITIONING reservation stands retained for
            // later owner disposition.
            assertEquals(1, auth.refreshCalls)
            assertEquals(1L, vault.load().revision)
            assertEquals(PENDING_BODY, vault.load().record?.pendingRefresh?.attempt?.requestJson)
            assertEquals(
                listOf("12", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_NEW),
                journalCells(database),
            )
        } finally {
            database.close()
        }
    }

    // ---- Committed-CAS delayed result fences without success; settled reread ----

    @Test
    fun committedCasDelayedResultFencesWithoutSuccessThenSettledIdempotence() = runTest {
        val namespace = testNamespace("lostreturn")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val plainVault = installOldActiveVaultWithPending(namespace)
            val gate = CasReturnGate()
            val wrapping = DelayedReturnVault(plainVault, gate)
            val auth = PreparedEngineAuthApi()
            auth.answer(replayedGrant())
            val pair = coordinators(authority, wrapping, auth)
            val expected = authority.read()
            supervisorScope {
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        pair.reauth.reconcilePendingRefresh(expected, pair.refresh)
                        "no-throw"
                    } catch (e: Throwable) {
                        e
                    }
                }
                val committed = gate.committedEntered.await()
                assertEquals(2L, committed.revision)
                // Invalidation lands while the already-started CAS return is
                // held: durable bytes stand, the invocation cannot succeed.
                pair.reauth.invalidate()
                gate.release.complete(Unit)
                val outcome = job.await()
                assertTrue(outcome is SessionReauthenticationException)
                assertEquals(
                    SessionReauthenticationFailure.STALE_OPERATION,
                    (outcome as SessionReauthenticationException).failure,
                )
            }
            assertEquals(2L, plainVault.load().revision)
            assertEquals(replayedGrant(), plainVault.load().record?.session)
            assertNull(plainVault.load().record?.pendingRefresh)
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalCells(database),
            )
            // Fresh reread of the settled ACTIVE pair is read-only
            // idempotence: no new HTTP/CAS, advisory settled observation.
            val fresh = coordinators(authority, plainVault, auth)
            val again = fresh.reauth.reconcilePendingRefresh(authority.read(), fresh.refresh)
            assertEquals(11L, again.room.revision)
            assertEquals(2L, again.vaultRevision)
            assertEquals(1, auth.refreshCalls)
            assertEquals(2L, plainVault.load().revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun committedCasDelayedResultCancelledThenSettledIdempotence() = runTest {
        // Caller cancellation during the held committed-CAS return: the
        // cancellation escapes identically (locks released), durable bytes
        // stand without success, and a restart rereads settled idempotence.
        val namespace = testNamespace("lostrtn-cancel")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val plainVault = installOldActiveVaultWithPending(namespace)
            val gate = CasReturnGate()
            val wrapping = DelayedReturnVault(plainVault, gate)
            val auth = PreparedEngineAuthApi()
            auth.answer(replayedGrant())
            val runOwner = PushRunOwner()
            val credentialMutex = Mutex()
            val pair = coordinators(authority, wrapping, auth, runOwner, credentialMutex)
            val expected = authority.read()
            supervisorScope {
                val done = CompletableDeferred<Throwable>()
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        pair.reauth.reconcilePendingRefresh(expected, pair.refresh)
                        done.complete(AssertionError("expected cancellation during held CAS return"))
                    } catch (e: Throwable) {
                        done.complete(e)
                    }
                }
                val committed = gate.committedEntered.await()
                assertEquals(2L, committed.revision)
                val probe = NoncopyingProbeCancellation("lostreturn-cancel-pending-refresh")
                job.cancel(probe)
                gate.release.complete(Unit)
                assertSame(probe, done.await())
                job.join()
            }
            runCurrent()
            assertFalse(credentialMutex.isLocked)
            assertFalse(runOwner.mutex.isLocked)
            assertEquals(2L, plainVault.load().revision)
            assertEquals(replayedGrant(), plainVault.load().record?.session)
            assertNull(plainVault.load().record?.pendingRefresh)
            // Restart rereads the settled ACTIVE pair read-only.
            val fresh = coordinators(authority, plainVault, auth)
            val again = fresh.reauth.reconcilePendingRefresh(authority.read(), fresh.refresh)
            assertEquals(11L, again.room.revision)
            assertEquals(2L, again.vaultRevision)
            assertEquals(1, auth.refreshCalls)
        } finally {
            database.close()
        }
    }

    // ---- File reopen after success preserves the settled pair ----

    @Test
    fun fileReopenAfterSuccessPreservesSettledPair() = runTest {
        val namespace = testNamespace("final")
        deleteNamespacePath(namespace)
        val dbName = "pending-refresh-final.db"
        val database = createFileDb(dbName)
        // Baseline stored OUTSIDE the closed DB scope: try-as-expression
        // carries the retained snapshot past close for the reopen comparison.
        val beforeRetained: Map<String, List<List<String?>>> = try {
            seedActiveAuthorityWithCompleteCache(database)
            seedRetainedWithFrozen(database)
            val baseline = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVaultWithPending(namespace)
            val auth = PreparedEngineAuthApi()
            auth.answer(replayedGrant())
            val pair = coordinators(authority, vault, auth)
            val observation = pair.reauth.reconcilePendingRefresh(authority.read(), pair.refresh)
            assertEquals(2L, observation.vaultRevision)
            assertEquals(baseline, retainedSnapshot(database))
            baseline
        } finally {
            database.close()
        }

        // Non-destructive reopen of the settled pair: old ACTIVE authority
        // unchanged, exact installed grant with no pending, and the ENTIRE
        // retained snapshot (baseline stored outside the closed DB scope)
        // byte-identical across the close/reopen.
        val reopened = reopenFileDb(dbName)
        try {
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(2L, vault.load().revision)
            assertEquals(replayedGrant(), vault.load().record?.session)
            assertNull(vault.load().record?.pendingRefresh)
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalCells(reopened),
            )
            assertEquals(beforeRetained, retainedSnapshot(reopened))
            val foreign = snapshotRows(
                reopened,
                "SELECT id, account_id FROM workouts WHERE account_id = '$ACCOUNT_B' ORDER BY id",
                2,
            )
            assertEquals(listOf(listOf(WORKOUT_FOREIGN, ACCOUNT_B)), foreign)
            val frozen = snapshotRows(
                reopened,
                "SELECT payload_json, state FROM sync_outbox WHERE sequence = 10",
                2,
            ).single()
            assertEquals(frozenWrapper(), frozen[0])
            assertEquals("frozen", frozen[1])
        } finally {
            reopened.close()
        }
    }
}
