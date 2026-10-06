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
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
 * R09e independent QA: real Room + actual test-owned Android encrypted vault.
 *
 * Derived ONLY from docs/archive/remaining-work/R09e-task.md coordinator ordering/
 * crash/recovery contracts plus QA-owned harness facts (R09a/b literal
 * seeds, RoomSessionAuthority read/beginReauthentication/
 * finishReauthentication, AndroidSessionVault.createForTesting
 * namespace/alias rules, test-owned cleanup, literal journal/device SQL,
 * delayed-CAS gate, PushRunOwner internal mutex). No production
 * declarations, diffs, Worker work, generated sources, decompiled classes or
 * schema JSON inspected. Deterministic only, no sleeps.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionReauthenticationRoomVaultTest {

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val ACCOUNT_B = "c6938596-d7e7-50bb-0689-8b3f748dccd1"
        const val OP_OLD = "11111111-1111-1111-1111-111111111111"
        const val OP_NEW = "22222222-2222-2222-2222-222222222222"
        const val KEY_NEW = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val ORIGIN_A = "https://sync.example.test:443"
        const val WORKOUT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val WORKOUT_FOREIGN = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val MUT_FROZEN = "55555555-5555-5555-5555-555555555555"
        const val STAGE_1 = "stage-1"
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

    private fun newGrant(): ServiceSession = ServiceSession(
        ServiceAccountRef(ACCOUNT_A, null),
        OpaqueToken("opaque-access-new"),
        1_700_000_000_000L,
        OpaqueToken("opaque-refresh-new"),
        1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    )

    private fun frozenWrapper(): String {
        val inner = "{\"mutationId\":\"$MUT_FROZEN\",\"type\":\"workout.start\"," +
            "\"aggregateType\":\"workout\",\"aggregateId\":\"$WORKOUT_A\"," +
            "\"localGeneration\":4,\"payload\":{\"id\":\"$WORKOUT_A\",\"name\":\"R09e\"}}"
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
        val namespace = "gymtracka-test-r09e-$suffix"
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

    /** Occupied old ACTIVE source vault matching the seeded predecessor. */
    private suspend fun installOldActiveVault(namespace: String): SessionVault {
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(
            0L,
            VaultSessionRecord(bindingA(), OP_OLD, VaultSessionPhase.ACTIVE, oldGrant(), null),
        )
        return vault
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

    // ---- Happy + pending refusal on the real pair ----

    @Test
    fun happyReauthExactRevisionsFullGrantAndRetainedScopeOnRealPair() = runTest {
        val namespace = testNamespace("happy")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            seedRetainedWithFrozen(database)
            val before = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVault(namespace)
            val expected = authority.read()
            val coordinator = SessionReauthenticationCoordinator(
                originA(), authority, vault, PushRunOwner(), Mutex(),
            )
            val grant = newGrant()
            val observation = coordinator.reauthenticate(expected, OP_NEW, grant)
            assertEquals(13L, observation.room.revision)
            assertEquals(8L, observation.room.sessionGeneration)
            assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
            assertEquals(2L, observation.vaultRevision)
            val stored = vault.load()
            assertEquals(2L, stored.revision)
            assertEquals(VaultSessionPhase.PREPARED, stored.record?.phase)
            assertEquals(bindingA(generation = 8L), stored.record?.binding)
            assertEquals(OP_NEW, stored.record?.operationId)
            assertEquals(grant, stored.record?.session)
            assertEquals(
                listOf("13", "8", "preparing", ORIGIN_A, ACCOUNT_A, OP_NEW),
                journalCells(database),
            )
            // Retained scope byte-identical: frozen bytes, foreign rows, stages.
            assertEquals(before, retainedSnapshot(database))
            val rendered = observation.toString()
            assertFalse(rendered.contains("opaque-access-new"))
            assertFalse(rendered.contains("opaque-refresh-new"))
        } finally {
            database.close()
        }
    }

    @Test
    fun sourceWhitespacePendingRefusedBeforeReservationOnRealPair() = runTest {
        val namespace = testNamespace("pending")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val body = " { \"refreshToken\" : \"opaque-refresh-old\" } "
            val pending = VaultPendingRefresh(OP_OLD, ServiceRefreshAttempt.fromJson(KEY_NEW, body))
            vault.compareAndSet(0L, VaultSessionRecord(bindingA(), OP_OLD, VaultSessionPhase.ACTIVE, oldGrant(), pending))
            val coordinator = SessionReauthenticationCoordinator(originA(), authority, vault, PushRunOwner(), Mutex())
            try {
                coordinator.reauthenticate(authority.read(), OP_NEW, newGrant())
                fail("expected PENDING_REFRESH")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.PENDING_REFRESH, e.failure)
                assertNull(e.cause)
            }
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalCells(database),
            )
            assertEquals(body, vault.load().record?.pendingRefresh?.attempt?.requestJson)
        } finally {
            database.close()
        }
    }

    @Test
    fun missingVaultWithTransitioningBlocksAndNeverAuthorizesResume() = runTest {
        val namespace = testNamespace("missing")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val transitioning = authority.beginReauthentication(authority.read(), originA(), OP_NEW)
            // Empty vault: no settled predecessor to resume from.
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(0L, vault.load().revision)
            val coordinator = SessionReauthenticationCoordinator(originA(), authority, vault, PushRunOwner(), Mutex())
            val recovery = coordinator.recover()
            assertTrue(recovery is SessionReauthenticationRecovery.Blocked)
            val failure = (recovery as SessionReauthenticationRecovery.Blocked).failure
            assertTrue(failure.toString().isNotEmpty())
            assertFalse(failure.toString().contains(OP_OLD))
            // The missing vault must never authorize a supplied-grant resume.
            try {
                coordinator.resume(transitioning, newGrant())
                fail("missing vault must not authorize resume")
            } catch (e: SessionReauthenticationException) {
                assertNull(e.cause)
                assertTrue(e.suppressed.isEmpty())
            }
            assertEquals(0L, vault.load().revision)
            assertEquals(transitioning, authority.read())
        } finally {
            database.close()
        }
    }

    @Test
    fun reservationOnlyRetainsOldActiveThenResumeWithoutRestart() = runTest {
        val namespace = testNamespace("resume")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVault(namespace)
            // Reservation-only interruption: Room TRANSITIONING while the
            // occupied old ACTIVE predecessor is retained in the vault.
            val transitioning = authority.beginReauthentication(authority.read(), originA(), OP_NEW)
            val coordinator = SessionReauthenticationCoordinator(originA(), authority, vault, PushRunOwner(), Mutex())
            val recovery = coordinator.recover()
            assertTrue(recovery is SessionReauthenticationRecovery.NeedsGrant)
            assertEquals(transitioning, (recovery as SessionReauthenticationRecovery.NeedsGrant).room)
            assertEquals(OP_OLD, vault.load().record?.operationId)
            assertEquals(VaultSessionPhase.ACTIVE, vault.load().record?.phase)

            val observation = coordinator.resume(transitioning, newGrant())
            assertEquals(13L, observation.room.revision)
            assertEquals(8L, observation.room.sessionGeneration)
            assertEquals(2L, observation.vaultRevision)
            assertEquals(newGrant(), vault.load().record?.session)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
        } finally {
            database.close()
        }
    }

    @Test
    fun installedTargetWrongGrantRefusesPendingPreservedOnRealPair() = runTest {
        val namespace = testNamespace("wronggrant")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val transitioning = authority.beginReauthentication(authority.read(), originA(), OP_NEW)
            val vault = installOldActiveVault(namespace)
            val body = " { \"refreshToken\" : \"opaque-refresh-new\" } "
            val target = VaultSessionRecord(
                bindingA(generation = 8L), OP_NEW, VaultSessionPhase.PREPARED, newGrant(),
                VaultPendingRefresh(OP_NEW, ServiceRefreshAttempt.fromJson(KEY_NEW, body)),
            )
            // Installed target: the supplied-grant CAS already committed over
            // the retained old ACTIVE predecessor before the lost return.
            assertEquals(2L, vault.compareAndSet(1L, target).revision)
            val coordinator = SessionReauthenticationCoordinator(originA(), authority, vault, PushRunOwner(), Mutex())
            try {
                coordinator.resume(transitioning, oldGrant())
                fail("expected IDENTITY_MISMATCH")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.IDENTITY_MISMATCH, e.failure)
            }
            assertEquals(body, vault.load().record?.pendingRefresh?.attempt?.requestJson)
            val observation = coordinator.resume(transitioning, newGrant())
            assertEquals(2L, observation.vaultRevision)
            assertEquals(body, vault.load().record?.pendingRefresh?.attempt?.requestJson)
        } finally {
            database.close()
        }
    }

    @Test
    fun fenceDuringDelayedCasReturnPreservesOrphanOnRealPair() = runTest {
        val namespace = testNamespace("orphan")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val plainVault = installOldActiveVault(namespace)
            val gate = CasReturnGate()
            val wrapping = DelayedReturnVault(plainVault, gate)
            val coordinator = SessionReauthenticationCoordinator(originA(), authority, wrapping, PushRunOwner(), Mutex())
            val grant = newGrant()
            supervisorScope {
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        coordinator.reauthenticate(authority.read(), OP_NEW, grant)
                        "no-throw"
                    } catch (e: Throwable) {
                        e
                    }
                }
                val committed = gate.committedEntered.await()
                assertEquals(2L, committed.revision)
                authority.fencePrivate(authority.read())
                gate.release.complete(Unit)
                val outcome = job.await()
                assertTrue(outcome is SessionReauthenticationException)
                assertEquals(
                    SessionReauthenticationFailure.STALE_AUTHORITY,
                    (outcome as SessionReauthenticationException).failure,
                )
            }
            // Admitted bytes remain private and intact.
            assertEquals(2L, plainVault.load().revision)
            assertEquals(grant, plainVault.load().record?.session)
        } finally {
            database.close()
        }
    }

    @Test
    fun queuedMutexHoldersBlockEffectsThenReleaseReadmitsOnRealPair() = runTest {
        val namespace = testNamespace("queued")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVault(namespace)
            val runOwner = PushRunOwner()
            val credentialMutex = Mutex()
            val coordinator = SessionReauthenticationCoordinator(originA(), authority, vault, runOwner, credentialMutex)
            val expected = authority.read()

            for (held in listOf(runOwner.mutex, credentialMutex)) {
                held.lock()
                try {
                    supervisorScope {
                        // Separate completion signal: identity is asserted INSIDE
                        // the actual child's catch. The canceled job is only
                        // joined, never awaited (await would rethrow the probe).
                        val done = CompletableDeferred<Throwable>()
                        val job = async(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                coordinator.reauthenticate(expected, OP_NEW, newGrant())
                                done.complete(AssertionError("expected cancellation while queued"))
                            } catch (e: Throwable) {
                                done.complete(e)
                            }
                        }
                        runCurrent()
                        runCurrent()
                        assertFalse("queued invocation must not complete while mutex held", job.isCompleted)
                        assertEquals(expected, authority.read())
                        assertEquals(1L, vault.load().revision)
                        val probe = CancellationException("queued-cancel-r09e")
                        job.cancel(probe)
                        assertSame(probe, done.await())
                        job.join()
                        assertEquals(expected, authority.read())
                        assertEquals(1L, vault.load().revision)
                    }
                } finally {
                    held.unlock()
                }
            }
            // Release readmits: the same pair now succeeds.
            val observation = coordinator.reauthenticate(authority.read(), OP_NEW, newGrant())
            assertEquals(13L, observation.room.revision)
            assertEquals(2L, observation.vaultRevision)
        } finally {
            database.close()
        }
    }

    // ---- Reopen + R09b recognition with foreign preservation ----

    @Test
    fun fileReopenFinalPairPlusGapPlusR09bRecognitionAndForeignPreserved() = runTest {
        val namespace = testNamespace("reopen")
        deleteNamespacePath(namespace)
        val dbName = "r09e-reopen.db"
        val database = createFileDb(dbName)
        try {
            seedActiveAuthorityWithCompleteCache(database)
            seedRetainedWithFrozen(database)
            val before = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVault(namespace)
            val coordinator = SessionReauthenticationCoordinator(originA(), authority, vault, PushRunOwner(), Mutex())
            val observation = coordinator.reauthenticate(authority.read(), OP_NEW, newGrant())
            assertEquals(13L, observation.room.revision)
            assertEquals(2L, observation.vaultRevision)
            assertEquals(before, retainedSnapshot(database))
        } finally {
            database.close()
        }

        // Representative lost-result gap on a second pair: the occupied old
        // ACTIVE predecessor is installed before reserve and retained across
        // the close/reopen.
        val gapNamespace = testNamespace("gap")
        deleteNamespacePath(gapNamespace)
        val gapDbName = "r09e-gap.db"
        val gapDb = createFileDb(gapDbName)
        val gapTransitioning = try {
            seedActiveAuthorityWithCompleteCache(gapDb)
            val gapAuthority = RoomSessionAuthority(gapDb)
            installOldActiveVault(gapNamespace)
            gapAuthority.beginReauthentication(gapAuthority.read(), originA(), OP_NEW)
        } finally {
            gapDb.close()
        }
        val reopenedGap = reopenFileDb(gapDbName)
        try {
            val gapVault = AndroidSessionVault.createForTesting(context, gapNamespace)
            assertEquals(OP_OLD, gapVault.load().record?.operationId)
            assertEquals(VaultSessionPhase.ACTIVE, gapVault.load().record?.phase)
            val recovery = SessionReauthenticationCoordinator(
                originA(), RoomSessionAuthority(reopenedGap), gapVault, PushRunOwner(), Mutex(),
            ).recover()
            assertTrue(recovery is SessionReauthenticationRecovery.NeedsGrant)
            assertEquals(gapTransitioning.revision, (recovery as SessionReauthenticationRecovery.NeedsGrant).room.revision)
        } finally {
            reopenedGap.close()
        }

        // Non-destructive reopen of the finished pair: final matching
        // PREPARING/PREPARED, R09b recognition, foreign rows intact.
        val reopened = reopenFileDb(dbName)
        try {
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val recovery = SessionReauthenticationCoordinator(
                originA(), RoomSessionAuthority(reopened), vault, PushRunOwner(), Mutex(),
            ).recover()
            assertTrue(recovery is SessionReauthenticationRecovery.Prepared)
            val finalObservation = (recovery as SessionReauthenticationRecovery.Prepared).observation
            assertEquals(13L, finalObservation.room.revision)
            assertEquals(2L, finalObservation.vaultRevision)
            assertEquals(newGrant(), vault.load().record?.session)
            val preparationRecovery = SessionPreparationCoordinator(
                originA(), RoomSessionAuthority(reopened), vault,
            ).recover()
            assertTrue(preparationRecovery is SessionPreparationRecovery.Prepared)
            val prepared = (preparationRecovery as SessionPreparationRecovery.Prepared).observation
            assertEquals(finalObservation.room, prepared.room)
            assertEquals(finalObservation.vaultRevision, prepared.vaultRevision)
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

    @Test
    fun invalidationWithoutEffectsAndInvalidInputClosedOnRealPair() = runTest {
        val namespace = testNamespace("invalidate")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val vault = installOldActiveVault(namespace)
            val coordinator = SessionReauthenticationCoordinator(originA(), authority, vault, PushRunOwner(), Mutex())
            // Synchronous retire without waiting for locks/I/O, no durable effects.
            coordinator.invalidate()
            assertEquals(
                listOf("11", "7", "active", ORIGIN_A, ACCOUNT_A, OP_OLD),
                journalCells(database),
            )
            assertEquals(1L, vault.load().revision)
            try {
                coordinator.reauthenticate(authority.read(), "not-a-uuid", newGrant())
                fail("expected INVALID_INPUT")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.INVALID_INPUT, e.failure)
                assertNull(e.cause)
                assertTrue(e.suppressed.isEmpty())
            }
        } finally {
            database.close()
        }
    }
}
