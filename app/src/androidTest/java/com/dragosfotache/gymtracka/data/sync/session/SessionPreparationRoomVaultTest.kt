package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import android.system.Os
import android.system.OsConstants
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
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * actual Room + actual test-owned Android encrypted vault.
 *
 * Each test proves a distinct new defect with a representative rich fixture;
 * no exhaustive matrix. Deterministic only, no sleeps.
 */
@RunWith(AndroidJUnit4::class)
class SessionPreparationRoomVaultTest {

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val ACCOUNT_B = "c6938596-d7e7-50bb-0689-8b3f748dccd1"
        const val OP_A = "11111111-1111-1111-1111-111111111111"
        const val OP_B = "22222222-2222-2222-2222-222222222222"
        const val KEY_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val ORIGIN_A = "https://sync.example.test:443"
        const val WORKOUT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val MUT_A = "55555555-5555-5555-5555-555555555555"
        const val STAGE_1 = "stage-1"
    }

    private fun originA(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_A)

    private fun bindingA(
        accountId: String = ACCOUNT_A,
        generation: Long = 1L,
        root: String = ORIGIN_A,
    ): ServiceSessionBinding =
        ServiceSessionBinding(ServiceOrigin.parse(root), accountId, generation)

    private fun sessionA(
        accountId: String = ACCOUNT_A,
        access: String = "opaque-access-001",
        accessExpires: Long = 1_700_000_000_000L,
        refresh: String = "opaque-refresh-001",
        refreshExpires: Long = 1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    ): ServiceSession = ServiceSession(
        ServiceAccountRef(accountId, null),
        OpaqueToken(access),
        accessExpires,
        OpaqueToken(refresh),
        refreshExpires,
    )

    private fun preparedRecord(
        binding: ServiceSessionBinding = bindingA(),
        operationId: String = OP_A,
        session: ServiceSession = sessionA(accountId = binding.accountId),
        pendingRefresh: VaultPendingRefresh? = null,
    ): VaultSessionRecord = VaultSessionRecord(
        binding,
        operationId,
        VaultSessionPhase.PREPARED,
        session,
        pendingRefresh,
    )

    private fun attemptBody(key: String, body: String): ServiceRefreshAttempt =
        ServiceRefreshAttempt.fromJson(key, body)

    // ---- Harness: test-owned namespace/alias/database only ----

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val usedNamespaces: MutableList<String> = mutableListOf()
    private val usedDatabases: MutableList<String> = mutableListOf()

    private fun testNamespace(suffix: String): String {
        val namespace = "gymtracka-test-preparation-$suffix"
        usedNamespaces.add(namespace)
        return namespace
    }

    private fun testAlias(namespace: String): String =
        "com.dragosfotache.gymtracka.session.$namespace"

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    private fun noBackupDir(): File = context.noBackupFilesDir

    private fun namespaceDir(namespace: String): File = File(noBackupDir(), namespace)

    private fun vaultFile(namespace: String): File = File(namespaceDir(namespace), "session.vault")

    private fun tempFile(namespace: String): File = File(namespaceDir(namespace), "session.vault.new")

    private fun isSymlinkNoFollow(path: String): Boolean {
        return try {
            val stat = Os.lstat(path)
            (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
        } catch (_: Exception) {
            false
        }
    }

    private fun removeLinkNoFollow(path: String) {
        try {
            if (isSymlinkNoFollow(path)) Os.remove(path)
        } catch (_: Exception) {
        }
    }

    private fun deleteRecursivelyOwned(root: File) {
        if (!root.exists() && !isSymlinkNoFollow(root.absolutePath)) return
        removeLinkNoFollow(root.absolutePath)
        if (root.isDirectory && !isSymlinkNoFollow(root.absolutePath)) {
            root.listFiles()?.forEach { deleteRecursivelyOwned(it) }
        }
        try {
            root.delete()
        } catch (_: Exception) {
        }
    }

    private fun deleteNamespacePath(namespace: String) {
        removeLinkNoFollow(namespaceDir(namespace).absolutePath)
        removeLinkNoFollow(tempFile(namespace).absolutePath)
        removeLinkNoFollow(vaultFile(namespace).absolutePath)
        try {
            tempFile(namespace).delete()
            vaultFile(namespace).delete()
            val dir = namespaceDir(namespace)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { deleteRecursivelyOwned(it) }
                dir.delete()
            } else {
                dir.delete()
            }
        } catch (_: Exception) {
        }
        try {
            androidKeyStore().deleteEntry(testAlias(namespace))
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

    private fun openInMemoryDb(): GymDatabase {
        val appContext = context.applicationContext
        return Room.inMemoryDatabaseBuilder(appContext, GymDatabase::class.java).build()
    }

    private fun openFileDb(name: String): GymDatabase {
        usedDatabases += name
        context.deleteDatabase(name)
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    /**
     * Test-only vault wrapper delaying the CAS return AFTER the delegate's actual
     * commit stood, so a real Room fence can land mid-write on the same pair.
     */
    private class DelayedReturnVault(
        private val delegate: SessionVault,
        private val gate: CasReturnGate,
    ) : SessionVault {
        override suspend fun load(): VaultSnapshot = delegate.load()

        override suspend fun compareAndSet(
            expectedRevision: Long,
            record: VaultSessionRecord?,
        ): VaultSnapshot {
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

    private fun snapshotRows(
        database: GymDatabase,
        sql: String,
        columns: Int,
    ): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun seedRepresentativeFixture(database: GymDatabase) {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_A', 'Active W', 5000, NULL, 'active'," +
                " '$ACCOUNT_A', 5, 3, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_state (account_id, cursor, last_success_millis)" +
                " VALUES ('$ACCOUNT_A', 'cursor-a', 7000)",
        )
        db.execSQL("INSERT INTO catalogue_state (id, version) VALUES (1, 'catalogue-12')")
        db.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$ACCOUNT_A', 1, 5, 0, 4)",
        )
        db.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (10, '$MUT_A', '$ACCOUNT_A', 'workout', '$WORKOUT_A'," +
                " 'workout.start', 4, 1, '{}', 'pending', 0, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_canonical_roots (account_id, aggregate_type, aggregate_id," +
                " server_revision, canonical_state, canonical_json," +
                " local_generation_watermark, cache_epoch)" +
                " VALUES ('$ACCOUNT_A', 'workout', '$WORKOUT_A', 7, 'upsert'," +
                " '{\"id\":\"$WORKOUT_A\"}', 4, 5)",
        )
        // Representative sync stage plus one child record (Room authority literal reuse).
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
    }

    private fun assertRedacted(rendered: String, secrets: List<String>) {
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("display must be fixed/redacted but contained <$secret>", rendered.contains(secret))
        }
    }

    // ---- Obligation 6: real Room + real test-owned vault ----

    @Test
    fun successfulReservationWriteAndReopenRecoversPrepared() = runTest {
        val namespace = testNamespace("happy")
        deleteNamespacePath(namespace)
        val dbName = "preparation-happy.db"
        val database = openFileDb(dbName)
        try {
            seedRepresentativeFixture(database)
            val authority = RoomSessionAuthority(database)
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val coordinator = SessionPreparationCoordinator(originA(), authority, vault)

            val expected = authority.read()
            assertEquals(RecoveryRoomPhase.SIGNED_OUT, expected.phase)
            val grant = sessionA()
            val observation = coordinator.prepare(expected, OP_A, grant)
            assertEquals(1L, observation.room.revision)
            assertEquals(1L, observation.room.sessionGeneration)
            assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
            assertEquals(1L, observation.vaultRevision)

            // Literal journal + device rows for the reservation.
            database.openHelper.readableDatabase.query(
                "SELECT revision, session_generation, phase, service_origin, account_id, operation_id" +
                    " FROM sync_session_authority WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals("preparing", cursor.getString(2))
                assertEquals(ORIGIN_A, cursor.getString(3))
                assertEquals(ACCOUNT_A, cursor.getString(4))
                assertEquals(OP_A, cursor.getString(5))
            }
            database.openHelper.readableDatabase.query(
                "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                    " FROM sync_device_state WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals(ACCOUNT_A, cursor.getString(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals("preparing", cursor.getString(3))
            }
            assertRedacted(
                observation.toString(),
                listOf(ACCOUNT_A, OP_A, "opaque-access-001", "opaque-refresh-001"),
            )
        } finally {
            database.close()
        }

        // Restart: recreate factory/database/coordinator over the same pair.
        val reopened = Room.databaseBuilder(
            context.applicationContext,
            GymDatabase::class.java,
            dbName,
        ).build()
        try {
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val coordinator = SessionPreparationCoordinator(originA(), RoomSessionAuthority(reopened), vault)
            val recovery = coordinator.recover()
            assertTrue(recovery is SessionPreparationRecovery.Prepared)
            val observation = (recovery as SessionPreparationRecovery.Prepared).observation
            assertEquals(1L, observation.room.revision)
            assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
            assertEquals(1L, observation.vaultRevision)
            assertEquals(sessionA(), vault.load().record?.session)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun reservationOnlyRecoversNeedsGrantThenResumeWritesPrepared() = runTest {
        val namespace = testNamespace("resume")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            val authority = RoomSessionAuthority(database)
            val grant = sessionA()
            // Reservation-only interruption: durable Room PREPARING, vault still empty.
            val preparing = authority.beginPreparation(authority.read(), originA(), ACCOUNT_A, OP_A)
            assertEquals(RecoveryRoomPhase.PREPARING, preparing.phase)

            val vault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(0L, vault.load().revision)
            val coordinator = SessionPreparationCoordinator(originA(), authority, vault)
            val recovery = coordinator.recover()
            assertTrue(recovery is SessionPreparationRecovery.NeedsGrant)
            assertEquals(preparing, (recovery as SessionPreparationRecovery.NeedsGrant).room)

            // External caller supplies the original grant under the SAME operation.
            val observation = coordinator.resume(preparing, grant)
            assertEquals(preparing, observation.room)
            assertEquals(1L, observation.vaultRevision)
            val stored = vault.load()
            assertEquals(1L, stored.revision)
            assertEquals(VaultSessionPhase.PREPARED, stored.record?.phase)
            assertEquals(grant, stored.record?.session)
            assertNull(stored.record?.pendingRefresh)
            // No new Room reservation: revision/generation unchanged.
            assertEquals(preparing, authority.read())
        } finally {
            database.close()
        }
    }

    @Test
    fun staleExpectedPrepareRefusesBeforeVaultWrite() = runTest {
        val namespace = testNamespace("staleprepare")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            val authority = RoomSessionAuthority(database)
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val coordinator = SessionPreparationCoordinator(originA(), authority, vault)
            // A concurrent reservation advances durable Room first.
            val staleExpected = authority.read()
            authority.beginPreparation(staleExpected, originA(), ACCOUNT_A, OP_A)
            try {
                coordinator.prepare(staleExpected, OP_B, sessionA())
                fail("expected STALE_AUTHORITY")
            } catch (e: SessionPreparationException) {
                assertEquals(SessionPreparationFailure.STALE_AUTHORITY, e.failure)
                assertNull(e.cause)
            }
            // No credential written: vault still empty.
            assertEquals(0L, vault.load().revision)
            assertNull(vault.load().record)
        } finally {
            database.close()
        }
    }

    @Test
    fun fencedResumeRefusesStaleBeforeVaultWrite() = runTest {
        val namespace = testNamespace("fencedresume")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            val authority = RoomSessionAuthority(database)
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val coordinator = SessionPreparationCoordinator(originA(), authority, vault)
            val preparing = authority.beginPreparation(authority.read(), originA(), ACCOUNT_A, OP_A)
            // Durable Room changes after the reservation (private fence).
            authority.fencePrivate(preparing)
            try {
                coordinator.resume(preparing, sessionA())
                fail("expected STALE_AUTHORITY")
            } catch (e: SessionPreparationException) {
                assertEquals(SessionPreparationFailure.STALE_AUTHORITY, e.failure)
                assertNull(e.cause)
            }
            assertEquals(0L, vault.load().revision)
            assertNull(vault.load().record)
        } finally {
            database.close()
        }
    }

    @Test
    fun postRenameVaultFaultCommitsCompleteRecordAndFreshRecoveryPrepared() = runTest {
        val namespace = testNamespace("postrename")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            val authority = RoomSessionAuthority(database)
            val expected = authority.read()
            val grant = sessionA()
            // SYNC_DIRECTORY fault fires post-rename on test-owned paths only: the
            // coordinator reports failure, but the complete record already stood.
            val failingStorage = AndroidVaultFileStorage(
                context,
                namespace,
                VaultWriteFaults { fault ->
                    if (fault == VaultWriteStage.SYNC_DIRECTORY) {
                        throw SessionVaultException(VaultFailure.UNAVAILABLE)
                    }
                },
            )
            val failingVault = EncryptedSessionVault(
                failingStorage,
                AesGcmVaultCipher(AndroidKeystoreVaultKeyProvider(testAlias(namespace))),
            )
            val coordinator = SessionPreparationCoordinator(originA(), authority, failingVault)
            try {
                coordinator.prepare(expected, OP_A, grant)
                fail("expected post-rename fault")
            } catch (e: SessionPreparationException) {
                assertEquals(SessionPreparationFailure.VAULT_UNAVAILABLE, e.failure)
                assertNull(e.cause)
            }
            // Complete reread: revision1 with the exact grant, never empty.
            val plainVault = AndroidSessionVault.createForTesting(context, namespace)
            val reloaded = plainVault.load()
            assertEquals(1L, reloaded.revision)
            assertEquals(grant, reloaded.record?.session)
            assertEquals(VaultSessionPhase.PREPARED, reloaded.record?.phase)
            val fresh = SessionPreparationCoordinator(originA(), authority, plainVault)
            val recovery = fresh.recover()
            assertTrue(recovery is SessionPreparationRecovery.Prepared)
            assertEquals(grant, (recovery as SessionPreparationRecovery.Prepared).let {
                plainVault.load().record?.session
            })
        } finally {
            database.close()
        }
    }

    @Test
    fun fenceDuringDelayedCasReturnRejectsDeadResultPreservingOrphan() = runTest {
        val namespace = testNamespace("orphan")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            val authority = RoomSessionAuthority(database)
            val plainVault = AndroidSessionVault.createForTesting(context, namespace)
            val gate = CasReturnGate()
            val wrapping = DelayedReturnVault(plainVault, gate)
            val coordinator = SessionPreparationCoordinator(originA(), authority, wrapping)
            val grant = sessionA()
            supervisorScope {
                val job = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        coordinator.prepare(authority.read(), OP_A, grant)
                        "no-throw"
                    } catch (e: Throwable) {
                        e
                    }
                }
                // The delegate's actual commit stood; its return stays delayed while
                // a real Room fence lands on the same pair.
                val committed = gate.committedEntered.await()
                assertEquals(1L, committed.revision)
                val preparing = authority.read()
                assertEquals(RecoveryRoomPhase.PREPARING, preparing.phase)
                authority.fencePrivate(preparing)
                gate.release.complete(Unit)
                val outcome = job.await()
                assertTrue(outcome is SessionPreparationException)
                assertEquals(
                    SessionPreparationFailure.STALE_AUTHORITY,
                    (outcome as SessionPreparationException).failure,
                )
            }
            // Admitted bytes remain private and intact; Room rests fenced.
            val stored = plainVault.load()
            assertEquals(1L, stored.revision)
            assertEquals(VaultSessionPhase.PREPARED, stored.record?.phase)
            assertEquals(grant, stored.record?.session)
            assertEquals(RecoveryRoomPhase.SIGNED_OUT, authority.read().phase)
        } finally {
            database.close()
        }
    }

    @Test
    fun recoverPreparingWithActivePhaseVaultIsBlockedMismatch() = runTest {
        val namespace = testNamespace("activephase")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            val authority = RoomSessionAuthority(database)
            val preparing = authority.beginPreparation(authority.read(), originA(), ACCOUNT_A, OP_A)
            val grant = sessionA()
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val active = VaultSessionRecord(
                bindingA(),
                OP_A,
                VaultSessionPhase.ACTIVE,
                grant,
                null,
            )
            assertEquals(1L, vault.compareAndSet(0L, active).revision)
            val recovery = SessionPreparationCoordinator(originA(), authority, vault).recover()
            assertTrue(recovery is SessionPreparationRecovery.Blocked)
            assertEquals(
                SessionPreparationFailure.IDENTITY_MISMATCH,
                (recovery as SessionPreparationRecovery.Blocked).failure,
            )
            // Unchanged: Room still PREPARING, vault still the ACTIVE record.
            assertEquals(preparing, authority.read())
            assertEquals(active, vault.load().record)
        } finally {
            database.close()
        }
    }

    @Test
    fun occupiedExactPendingAndWrongBindingRefuseUnchanged() = runTest {
        val namespace = testNamespace("occupied")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedRepresentativeFixture(database)
            val authority = RoomSessionAuthority(database)
            val body = " { \"refreshToken\" : \"opaque-refresh-001\" } "
            val pending = VaultPendingRefresh(
                operationId = OP_B,
                attempt = attemptBody(KEY_B, body),
            )
            val occupied = preparedRecord(
                binding = bindingA(generation = 1L),
                operationId = OP_B,
                session = sessionA(),
                pendingRefresh = pending,
            )
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(1L, vault.compareAndSet(0L, occupied).revision)

            val coordinator = SessionPreparationCoordinator(originA(), authority, vault)
            // New preparation refuses the occupied record without writes.
            try {
                coordinator.prepare(authority.read(), OP_A, sessionA())
                fail("expected VAULT_OCCUPIED")
            } catch (e: SessionPreparationException) {
                assertEquals(SessionPreparationFailure.VAULT_OCCUPIED, e.failure)
            }
            // Resume with a different grant refuses without CASing away pending.
            val preparing = authority.beginPreparation(
                authority.read(),
                originA(),
                ACCOUNT_A,
                OP_A,
            )
            try {
                coordinator.resume(preparing, sessionA(refresh = "opaque-refresh-OTHER"))
                fail("expected IDENTITY_MISMATCH")
            } catch (e: SessionPreparationException) {
                assertEquals(SessionPreparationFailure.IDENTITY_MISMATCH, e.failure)
            }
            val after = vault.load()
            assertEquals(1L, after.revision)
            assertEquals(body, after.record?.pendingRefresh?.attempt?.requestJson)
            assertEquals(KEY_B, after.record?.pendingRefresh?.attempt?.idempotencyKey)
        } finally {
            database.close()
        }
    }

    @Test
    fun richFixturePreservedExceptAuthorityDeviceReservation() = runTest {
        val namespace = testNamespace("preserve")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedRepresentativeFixture(database)
            // Whole selected rows: exact payloads, errors, cursors, successes, cache
            // metadata, canonical JSON and generations.
            val before = mapOf(
                "workouts" to snapshotRows(
                    database,
                    "SELECT id, name, started_at_millis, finished_at_millis, status, account_id," +
                        " server_revision, local_generation, sync_state, deleted_at_millis" +
                        " FROM workouts ORDER BY id",
                    10,
                ),
                "sync_state" to snapshotRows(
                    database,
                    "SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id",
                    3,
                ),
                "catalogue" to snapshotRows(database, "SELECT id, version FROM catalogue_state", 2),
                "cache" to snapshotRows(
                    database,
                    "SELECT account_id, baseline_complete, cache_epoch, projection_dirty," +
                        " materialized_epoch FROM sync_account_cache ORDER BY account_id",
                    5,
                ),
                "outbox" to snapshotRows(
                    database,
                    "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                        " mutation_type, local_generation, payload_version, payload_json, state," +
                        " attempt_count, retry_after_millis, error_code, error_message" +
                        " FROM sync_outbox ORDER BY sequence",
                    14,
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
                        " page_count, record_count FROM sync_snapshot_stages",
                    14,
                ),
                "records" to snapshotRows(
                    database,
                    "SELECT stage_id, aggregate_type, aggregate_id, server_revision, canonical_json," +
                        " page_number, record_position FROM sync_snapshot_records",
                    7,
                ),
            )
            val authority = RoomSessionAuthority(database)
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val coordinator = SessionPreparationCoordinator(originA(), authority, vault)
            coordinator.prepare(authority.read(), OP_A, sessionA())

            // Representative domain/outbox/cursor/stage/cache/canonical/catalogue rows
            // byte-identical; only the singleton journal + device reservation moved.
            assertEquals(
                before["workouts"],
                snapshotRows(
                    database,
                    "SELECT id, name, started_at_millis, finished_at_millis, status, account_id," +
                        " server_revision, local_generation, sync_state, deleted_at_millis" +
                        " FROM workouts ORDER BY id",
                    10,
                ),
            )
            assertEquals(
                before["sync_state"],
                snapshotRows(
                    database,
                    "SELECT account_id, cursor, last_success_millis FROM sync_state ORDER BY account_id",
                    3,
                ),
            )
            assertEquals(
                before["catalogue"],
                snapshotRows(database, "SELECT id, version FROM catalogue_state", 2),
            )
            assertEquals(
                before["cache"],
                snapshotRows(
                    database,
                    "SELECT account_id, baseline_complete, cache_epoch, projection_dirty," +
                        " materialized_epoch FROM sync_account_cache ORDER BY account_id",
                    5,
                ),
            )
            assertEquals(
                before["outbox"],
                snapshotRows(
                    database,
                    "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                        " mutation_type, local_generation, payload_version, payload_json, state," +
                        " attempt_count, retry_after_millis, error_code, error_message" +
                        " FROM sync_outbox ORDER BY sequence",
                    14,
                ),
            )
            assertEquals(
                before["canonical"],
                snapshotRows(
                    database,
                    "SELECT account_id, aggregate_type, aggregate_id, server_revision, canonical_state," +
                        " canonical_json, local_generation_watermark, cache_epoch" +
                        " FROM sync_canonical_roots ORDER BY account_id, aggregate_type, aggregate_id",
                    8,
                ),
            )
            assertEquals(
                before["stages"],
                snapshotRows(
                    database,
                    "SELECT stage_id, account_id, session_generation, cache_epoch, page_limit," +
                        " source_state_present, source_cursor, source_last_success_millis," +
                        " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                        " page_count, record_count FROM sync_snapshot_stages",
                    14,
                ),
            )
            assertEquals(
                before["records"],
                snapshotRows(
                    database,
                    "SELECT stage_id, aggregate_type, aggregate_id, server_revision, canonical_json," +
                        " page_number, record_position FROM sync_snapshot_records",
                    7,
                ),
            )
            assertNotNull(vault.load().record)
        } finally {
            database.close()
        }
    }
}
