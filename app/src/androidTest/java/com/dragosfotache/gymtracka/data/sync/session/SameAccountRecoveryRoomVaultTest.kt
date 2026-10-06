package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.R05cTestLease
import com.dragosfotache.gymtracka.data.sync.r05cGrantBarrier
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
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
 * R09g independent QA: real Room + test-owned Android encrypted vault + file reopen.
 *
 * Derived ONLY from docs/archive/remaining-work/R09g-task.md normative contracts plus
 * QA-owned harness facts (R09e RoomVault literal seeds/retained-row helpers and
 * rewrite-trigger shape, R09a rich child/relationship/catalogue literals,
 * R09b preparation admission, R09c engine auth/lease harness shapes, R09e
 * reauthentication write shape, coroutine Room query-callback lesson,
 * namespace/alias rules, real RoomSessionAuthority,
 * AndroidSessionVault.createForTesting). No production declarations, diffs,
 * Worker work, generated sources, decompiled classes or schema JSON inspected.
 * Deterministic only, no sleeps.
 *
 * Source rule: SIGNED_OUT retained recovery at generation 7 admits only
 * strictly LOWER generations with exact pending evidence for archiving;
 * ACTIVE interruption uses the generation-7 uncertain predecessor. prepare
 * performs TWO Room revisions (reserve + finalize): SIGNED_OUT 10 -> 11 -> 12.
 */
@RunWith(AndroidJUnit4::class)
class SameAccountRecoveryRoomVaultTest {

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val ACCOUNT_B = "c6938596-d7e7-50bb-0689-8b3f748dccd1"
        const val OP_OLD = "11111111-1111-1111-1111-111111111111"
        const val OP_FRESH = "33333333-3333-3333-3333-333333333333"
        const val OP_R09E = "66666666-6666-6666-6666-666666666666"
        const val OP_OTHER = "77777777-7777-7777-7777-777777777777"
        const val KEY_OLD = "44444444-4444-4444-4444-444444444444"
        const val ORIGIN_A = "https://sync.example.test:443"
        const val WORKOUT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val WORKOUT_FOREIGN = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val ROUTINE_A = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val CUSTOM_A = "11111111-1111-1111-1111-111111111112"
        const val LOCAL_ONLY_WORKOUT = "99999999-9999-4999-8999-999999999999"
        const val MUT_FROZEN = "55555555-5555-5555-5555-555555555555"
        const val STAGE_1 = "stage-1"
        const val NOW = 1_700_000_000_000L
    }

    private fun originA(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_A)

    private fun bindingA(generation: Long = 7L, accountId: String = ACCOUNT_A): ServiceSessionBinding =
        ServiceSessionBinding(originA(), accountId, generation)

    private fun oldGrant(): ServiceSession = ServiceSession(
        ServiceAccountRef(ACCOUNT_A, null),
        OpaqueToken("opaque-access-old"),
        NOW,
        OpaqueToken("opaque-refresh-old"),
        NOW + 30L * 24 * 60 * 60 * 1000,
    )

    private fun freshGrant(): ServiceSession = ServiceSession(
        ServiceAccountRef(ACCOUNT_A, null),
        OpaqueToken("opaque-access-fresh-r09g"),
        NOW,
        OpaqueToken("opaque-refresh-fresh-r09g"),
        NOW + 30L * 24 * 60 * 60 * 1000,
    )

    /** Exact pending body whose token equals the old grant refresh (uncertain source). */
    private fun oldPending(): VaultPendingRefresh = VaultPendingRefresh(
        OP_OLD,
        ServiceRefreshAttempt.fromJson(KEY_OLD, " { \"refreshToken\" : \"opaque-refresh-old\" } "),
    )

    private fun frozenWrapper(): String {
        val inner = "{\"mutationId\":\"$MUT_FROZEN\",\"type\":\"workout.start\"," +
            "\"aggregateType\":\"workout\",\"aggregateId\":\"$WORKOUT_A\"," +
            "\"localGeneration\":4,\"payload\":{\"id\":\"$WORKOUT_A\",\"name\":\"R09g\"}}"
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
        val namespace = "gymtracka-test-r09g-$suffix"
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

    private fun createFileDb(name: String): GymDatabase {
        usedDatabases += name
        context.deleteDatabase(name)
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    private fun reopenFileDb(name: String): GymDatabase {
        if (!usedDatabases.contains(name)) usedDatabases += name
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    /** SIGNED_OUT retained-account journal/device (private cache state) at generation 7 plus cache row. */
    private fun seedSignedOutRetainedAuthority(database: GymDatabase) {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 10, 7, 'signed_out', '$ORIGIN_A', '$ACCOUNT_A', NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, '$ACCOUNT_A', 7, 'private')",
        )
        db.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$ACCOUNT_A', 0, 5, 1, 4)",
        )
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

    /** Rich retained scope: domain children/relationships, routines, catalogue, sync rows. */
    private fun seedRichRetained(database: GymDatabase) {
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO muscle_groups (id, slug, name) VALUES ('muscle', 'muscle', 'Muscle')")
        db.execSQL(
            "INSERT INTO equipment (id, name, type, usage_type) VALUES ('equipment', 'Equipment', 'weight', NULL)",
        )
        db.execSQL(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('bundled-1', 'Bundled Press', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Step\"]', 0, NULL, NULL, 0, 'local_only', NULL)",
        )
        db.execSQL(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('$CUSTOM_A', 'Owned Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Custom step\"]', 1, '$ACCOUNT_A', NULL, 2, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type)" +
                " VALUES ('$CUSTOM_A', 'muscle', 'primary')",
        )
        db.execSQL(
            "INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items)" +
                " VALUES ('$CUSTOM_A', 'equipment', NULL)",
        )
        db.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_A', 'Active W', 5000, NULL, 'active'," +
                " '$ACCOUNT_A', 5, 3, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO workout_exercises (id, workout_id, exercise_id, position, notes," +
                " rest_duration_seconds)" +
                " VALUES ('we-active', '$WORKOUT_A', 'bundled-1', 0, 'Keep', 75)",
        )
        db.execSQL(
            "INSERT INTO workout_sets (id, workout_exercise_id, position, repetitions, weight_kg," +
                " is_completed, rest_seconds)" +
                " VALUES ('ws-active', 'we-active', 0, 8, 70.0, 1, 60)",
        )
        db.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$WORKOUT_FOREIGN', 'Foreign W', 4000, NULL, 'active'," +
                " '$ACCOUNT_B', NULL, 4, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision," +
                " local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$ROUTINE_A', 'Draft R', 1, '$ACCOUNT_A', NULL, 1, 'pending', NULL)",
        )
        db.execSQL(
            "INSERT INTO routine_exercises (id, routine_id, exercise_id, position," +
                " rest_duration_seconds)" +
                " VALUES ('re-draft', '$ROUTINE_A', 'bundled-1', 0, 30)",
        )
        db.execSQL(
            "INSERT INTO routine_sets (id, routine_exercise_id, position, repetitions, weight_kg)" +
                " VALUES ('rs-draft', 're-draft', 0, NULL, NULL)",
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
        db.execSQL("INSERT INTO catalogue_state (id, version) VALUES (1, 'catalogue-12')")
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

    /**
     * Installs an uncertain ACTIVE predecessor vault record: exact pending body
     * whose token equals the old grant refresh. Generation parameterized:
     * gen6 for SIGNED_OUT retained sources, gen7 for ACTIVE predecessors.
     */
    private suspend fun installUncertainVault(namespace: String, generation: Long): SessionVault {
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(
            0L,
            VaultSessionRecord(bindingA(generation), OP_OLD, VaultSessionPhase.ACTIVE, oldGrant(), oldPending()),
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
        "workout_exercises" to snapshotRows(
            database, "SELECT id, workout_id, exercise_id, position, notes, rest_duration_seconds FROM workout_exercises ORDER BY id", 6,
        ),
        "workout_sets" to snapshotRows(
            database,
            "SELECT id, workout_exercise_id, position, repetitions, weight_kg, is_completed, rest_seconds" +
                " FROM workout_sets ORDER BY id",
            7,
        ),
        "exercises" to snapshotRows(
            database,
            "SELECT id, name, type, difficulty_level, force_type, mechanics, category, instructions," +
                " is_user_created, account_id, server_revision, local_generation, sync_state," +
                " deleted_at_millis FROM exercises ORDER BY id",
            14,
        ),
        "exercise_muscle_groups" to snapshotRows(
            database, "SELECT exercise_id, muscle_group_id, type FROM exercise_muscle_groups ORDER BY 1, 2", 3,
        ),
        "exercise_equipment" to snapshotRows(
            database, "SELECT exercise_id, equipment_id, num_items FROM exercise_equipment ORDER BY 1, 2", 3,
        ),
        "routines" to snapshotRows(
            database,
            "SELECT id, name, is_draft, account_id, server_revision, local_generation, sync_state," +
                " deleted_at_millis FROM routines ORDER BY id",
            8,
        ),
        "routine_exercises" to snapshotRows(
            database,
            "SELECT id, routine_id, exercise_id, position, rest_duration_seconds FROM routine_exercises ORDER BY id",
            5,
        ),
        "routine_sets" to snapshotRows(
            database,
            "SELECT id, routine_exercise_id, position, repetitions, weight_kg FROM routine_sets ORDER BY id",
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
        "catalogue" to snapshotRows(database, "SELECT id, version FROM catalogue_state", 2),
        "muscle_groups" to snapshotRows(
            database, "SELECT id, slug, name FROM muscle_groups ORDER BY id", 3,
        ),
        "equipment" to snapshotRows(
            database, "SELECT id, name, type, usage_type FROM equipment ORDER BY id", 4,
        ),
    )

    private suspend fun expectRoomFailure(
        expected: RoomSessionAuthorityFailure,
        block: suspend () -> Unit,
    ): RoomSessionAuthorityException {
        try {
            block()
            fail("expected RoomSessionAuthorityException($expected)")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(expected, e.failure)
            return e
        }
        throw AssertionError("unreachable")
    }

    private class HoldAtCasEntryVault(private val delegate: SessionVault) : SessionVault {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun load(): VaultSnapshot = delegate.load()
        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            entered.complete(Unit)
            release.await()
            return delegate.compareAndSet(expectedRevision, record)
        }
    }

    private class CommitThenThrowVault(private val delegate: SessionVault) : SessionVault {
        override suspend fun load(): VaultSnapshot = delegate.load()
        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            delegate.compareAndSet(expectedRevision, record)
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }

    /** Plants a canonical-UUID local-only root through the writable database. */
    private fun plantLocalRoot(database: GymDatabase) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('$LOCAL_ONLY_WORKOUT', 'Local', 9000, NULL, 'active'," +
                " NULL, NULL, 1, 'pending', NULL)",
        )
    }

    private class CommitThenPlantRootVault(
        private val delegate: SessionVault,
        private val database: GymDatabase,
    ) : SessionVault {
        override suspend fun load(): VaultSnapshot = delegate.load()
        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            val committed = delegate.compareAndSet(expectedRevision, record)
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                    " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                    " VALUES ('$LOCAL_ONLY_WORKOUT', 'Local', 9000, NULL, 'active'," +
                    " NULL, NULL, 1, 'pending', NULL)",
            )
            return committed
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class NoncopyingProbeCancellation(message: String) :
        CancellationException(message),
        CopyableThrowable<NoncopyingProbeCancellation> {
        override fun createCopy(): NoncopyingProbeCancellation? = null
    }

    private fun coordinatorFor(database: GymDatabase, vault: SessionVault): SameAccountRecoveryCoordinator =
        SameAccountRecoveryCoordinator(
            originA(), RoomSessionAuthority(database) as SameAccountRecoveryAuthority,
            vault, PushRunOwner(), Mutex(),
        )

    // ---- Signed-out retained recovery on the real pair ----

    @Test
    fun signedOutRetainedMissingVaultReachesPreparationRev12WithPreservedScope() = runTest {
        val namespace = testNamespace("signedout-missing")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedSignedOutRetainedAuthority(database)
            seedRichRetained(database)
            val before = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(0L, vault.load().revision)
            val coordinator = coordinatorFor(database, vault)
            val expected = authority.read()
            val observation = coordinator.prepare(expected, OP_FRESH, freshGrant())
            // Reserve 10->11 TRANSITIONING then finalize 11->12 PREPARING.
            assertEquals(12L, observation.room.revision)
            assertEquals(8L, observation.room.sessionGeneration)
            assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
            assertEquals(1L, observation.vaultRevision)
            assertEquals(freshGrant(), vault.load().record?.session)
            assertEquals(before, retainedSnapshot(database))
            assertFalse(observation.toString().contains("opaque-access-fresh-r09g"))
        } finally {
            database.close()
        }
    }

    @Test
    fun signedOutRetainedGen6UncertainSourceArchivesAtomically() = runTest {
        val namespace = testNamespace("signedout-occupied")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedSignedOutRetainedAuthority(database)
            seedRichRetained(database)
            val before = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            // Generation 6 is strictly below the retained generation 7.
            val vault = installUncertainVault(namespace, 6L)
            val coordinator = coordinatorFor(database, vault)
            val observation = coordinator.prepare(authority.read(), OP_FRESH, freshGrant())
            assertEquals(12L, observation.room.revision)
            assertEquals(2L, observation.vaultRevision)
            val stored = vault.load().record
            requireNotNull(stored)
            assertEquals(freshGrant(), stored.session)
            assertEquals(1, stored.supersededSessions.entries.size)
            assertEquals(1L, stored.supersededSessions.entries.single().sourceRevision)
            assertEquals(oldGrant(), stored.supersededSessions.entries.single().record.session)
            assertEquals(
                " { \"refreshToken\" : \"opaque-refresh-old\" } ",
                stored.supersededSessions.entries.single().record.pendingRefresh?.attempt?.requestJson,
            )
            assertEquals(before, retainedSnapshot(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun activeInterruptedGen7UncertainPredecessorResumesWithoutNewGeneration() = runTest {
        val namespace = testNamespace("interrupted")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            // Generation-7 predecessor is adjacent to the reserved generation 8.
            val vault = installUncertainVault(namespace, 7L)
            val transitioning = authority.beginReauthentication(authority.read(), originA(), OP_FRESH)
            val coordinator = coordinatorFor(database, vault)
            val recovery = coordinator.recover()
            assertTrue(recovery is SameAccountRecovery.NeedsGrant)
            val first = coordinator.resume(transitioning, freshGrant())
            assertEquals(13L, first.room.revision)
            assertEquals(2L, first.vaultRevision)
            assertEquals(1, vault.load().record?.supersededSessions?.entries?.size)
            val second = coordinator.resume(first.room, freshGrant())
            assertEquals(2L, second.vaultRevision)
            assertEquals(1, vault.load().record?.supersededSessions?.entries?.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun localRootAfterReservationBlocksFinalizationWithExactCategory() = runTest {
        val namespace = testNamespace("localroot")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedSignedOutRetainedAuthority(database)
            seedRichRetained(database)
            val before = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            val plainVault = installUncertainVault(namespace, 6L)
            // Prompt pre-seam coherence: fail here, not in a gate timeout.
            val expected = authority.read()
            assertEquals(10L, expected.revision)
            assertEquals(RecoveryRoomPhase.SIGNED_OUT, expected.phase)
            assertEquals(
                listOf("10", "7", "signed_out", ORIGIN_A, ACCOUNT_A, null),
                journalCells(database),
            )
            // Hold the actual target CAS after the Room reservation: the local
            // root lands after reservation but before finalization.
            val holding = HoldAtCasEntryVault(plainVault)
            val coordinator = coordinatorFor(database, holding)
            val job = async {
                try {
                    coordinator.prepare(expected, OP_FRESH, freshGrant())
                    "no-throw"
                } catch (e: Throwable) {
                    e
                }
            }
            holding.entered.await()
            assertEquals(
                listOf("11", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_FRESH),
                journalCells(database),
            )
            plantLocalRoot(database)
            holding.release.complete(Unit)
            val outcome = job.await()
            assertTrue(outcome is SessionReauthenticationException)
            assertEquals(
                SessionReauthenticationFailure.LOCAL_DATA_REQUIRES_DECISION,
                (outcome as SessionReauthenticationException).failure,
            )
            // Reservation retained, target + archive committed, never compensated.
            assertEquals(
                listOf("11", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_FRESH),
                journalCells(database),
            )
            assertEquals(2L, plainVault.load().revision)
            assertEquals(freshGrant(), plainVault.load().record?.session)
            assertEquals(1, plainVault.load().record?.supersededSessions?.entries?.size)
            val after = retainedSnapshot(database).toMutableMap()
            val workouts = after.getValue("workouts").filter { it[0] != LOCAL_ONLY_WORKOUT }
            assertEquals(before.getValue("workouts"), workouts)
            after["workouts"] = workouts
            assertEquals(before, after)
            // Read-only recovery reports the exact local-root block, never success.
            val recovery = coordinatorFor(database, plainVault).recover()
            assertTrue(recovery is SameAccountRecovery.Blocked)
            assertEquals(
                SessionReauthenticationFailure.LOCAL_DATA_REQUIRES_DECISION,
                (recovery as SameAccountRecovery.Blocked).failure,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun localRootPlantedAtFinalVaultSeamBlocksFinalizationWithoutCompensation() = runTest {
        val namespace = testNamespace("finalseam")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedSignedOutRetainedAuthority(database)
            seedRichRetained(database)
            val authority = RoomSessionAuthority(database)
            val plainVault = installUncertainVault(namespace, 6L)
            // Prompt pre-seam coherence: fail here, not in a lost-return hole.
            val seedCheck = authority.read()
            assertEquals(RecoveryRoomPhase.SIGNED_OUT, seedCheck.phase)
            // The root appears AFTER the final vault CAS but BEFORE Room
            // finalization: the committed target stands, finalization refuses.
            val planting = CommitThenPlantRootVault(plainVault, database)
            val coordinator = coordinatorFor(database, planting)
            try {
                coordinator.prepare(authority.read(), OP_FRESH, freshGrant())
                fail("final-seam local root must block finalization")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.LOCAL_DATA_REQUIRES_DECISION, e.failure)
                assertNull(e.cause)
            }
            assertEquals(
                listOf("11", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_FRESH),
                journalCells(database),
            )
            assertEquals(2L, plainVault.load().revision)
            assertEquals(freshGrant(), plainVault.load().record?.session)
            assertEquals(1, plainVault.load().record?.supersededSessions?.entries?.size)
            val recovery = coordinatorFor(database, plainVault).recover()
            assertTrue(recovery is SameAccountRecovery.Blocked)
            assertEquals(
                SessionReauthenticationFailure.LOCAL_DATA_REQUIRES_DECISION,
                (recovery as SameAccountRecovery.Blocked).failure,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun vaultErrorWithRoomDriftReportsDriftPrecedenceAndFreshRecover() = runTest {
        val namespace = testNamespace("precedence")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            seedRichRetained(database)
            val before = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            val vault = installUncertainVault(namespace, 7L)
            // Genuine old ACTIVE expectation saved before the seam — never a
            // fabricated stale input.
            val savedExpected = authority.read()
            var reserved = false
            var thrown = false
            val seamingVault = object : SessionVault {
                override suspend fun load(): VaultSnapshot {
                    val snapshot = vault.load()
                    if (!reserved) {
                        reserved = true
                        // Real Room reservation AFTER the source-vault read:
                        // durable Room drifts while the coordinator is in flight.
                        authority.beginReauthentication(authority.read(), originA(), OP_FRESH)
                        thrown = true
                        throw SessionVaultException(VaultFailure.UNAVAILABLE)
                    }
                    return snapshot
                }
                override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot =
                    vault.compareAndSet(expectedRevision, record)
            }
            val coordinator = coordinatorFor(database, seamingVault)
            try {
                coordinator.prepare(savedExpected, OP_OTHER, freshGrant())
                fail("Room drift must dominate the vault error")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.STALE_AUTHORITY, e.failure)
            }
            assertTrue("seam reservation must have happened", reserved)
            assertTrue("vault throw must have occurred", thrown)
            // Old vault bytes and pending evidence stand unchanged.
            assertEquals(1L, vault.load().revision)
            assertEquals(oldGrant(), vault.load().record?.session)
            assertEquals(
                " { \"refreshToken\" : \"opaque-refresh-old\" } ",
                vault.load().record?.pendingRefresh?.attempt?.requestJson,
            )
            // The drifted reservation stands uncompensated; recovery publishes
            // the CURRENT durable state, never a stale eligible result.
            assertEquals(
                listOf("12", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_FRESH),
                journalCells(database),
            )
            assertEquals(before, retainedSnapshot(database))
            val recovery = coordinatorFor(database, vault).recover()
            assertTrue(recovery is SameAccountRecovery.NeedsGrant)
            assertEquals(12L, (recovery as SameAccountRecovery.NeedsGrant).room.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun returnedSourceDriftWithRoomDriftReportsDriftPrecedence() = runTest {
        val namespace = testNamespace("sourcedrift")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            val authority = RoomSessionAuthority(database)
            val vault = installUncertainVault(namespace, 7L)
            val savedExpected = authority.read()
            val genuine = vault.load()
            // Valid but changed source tuple: same revision/grant, exact body
            // preserved, only the idempotency key differs.
            val changedPending = VaultPendingRefresh(
                OP_OLD,
                ServiceRefreshAttempt.fromJson(
                    "55555555-5555-5555-5555-555555555555",
                    " { \"refreshToken\" : \"opaque-refresh-old\" } ",
                ),
            )
            val changed = VaultSnapshot(
                genuine.revision,
                genuine.record?.copy(pendingRefresh = changedPending),
            )
            var loads = 0
            var reserved = false
            val driftingVault = object : SessionVault {
                override suspend fun load(): VaultSnapshot {
                    loads += 1
                    // First load returns the genuine tuple with no drift, so
                    // the stale preflight passes; the second load injects the
                    // real Room reservation AND the changed-but-valid tuple.
                    if (loads == 1) return genuine
                    if (!reserved) {
                        reserved = true
                        authority.beginReauthentication(authority.read(), originA(), OP_FRESH)
                    }
                    return changed
                }
                override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot =
                    vault.compareAndSet(expectedRevision, record)
            }
            val coordinator = coordinatorFor(database, driftingVault)
            try {
                coordinator.prepare(savedExpected, OP_OTHER, freshGrant())
                fail("Room drift must dominate returned-source drift")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.STALE_AUTHORITY, e.failure)
            }
            assertTrue("reservation seam must have run", reserved)
            assertTrue("rereads must have observed both source tuples", loads >= 2)
            assertEquals(genuine.revision, changed.revision)
            assertEquals(
                "55555555-5555-5555-5555-555555555555",
                changed.record?.pendingRefresh?.attempt?.idempotencyKey,
            )
            assertEquals(1L, vault.load().revision)
            assertEquals(
                listOf("12", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_FRESH),
                journalCells(database),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun newWriterWrongOperationRewriteTriggerRollsBackPostWrite() = runTest {
        val database = openInMemoryDb()
        try {
            seedSignedOutRetainedAuthority(database)
            seedRichRetained(database)
            val before = retainedSnapshot(database)
            val db = database.openHelper.writableDatabase
            val authority = RoomSessionAuthority(database) as SameAccountRecoveryAuthority
            val expected = authority.read()
            // AFTER-trigger rewrites the just-reserved journal operation to a
            // different well-formed UUID: the exact post-write reread must fail
            // closed and the Room transaction must roll back. The WHEN guard
            // matches only the provisional reservation write, so the inner
            // rewrite cannot refire.
            db.execSQL(
                "CREATE TRIGGER r09g_rewrite_op AFTER UPDATE ON sync_session_authority" +
                    " WHEN NEW.operation_id = '$OP_FRESH'" +
                    " BEGIN UPDATE sync_session_authority SET operation_id = '$OP_OTHER' WHERE id = 1; END",
            )
            try {
                expectRoomFailure(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY) {
                    authority.beginSameAccountRecovery(expected, originA(), ACCOUNT_A, OP_FRESH)
                }
            } finally {
                db.execSQL("DROP TRIGGER IF EXISTS r09g_rewrite_op")
            }
            assertEquals(expected, authority.read())
            assertEquals(listOf("10", "7", "signed_out", ORIGIN_A, ACCOUNT_A, null), journalCells(database))
            assertEquals(before, retainedSnapshot(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun newWriterPostWriteCancellationRollsBackWithHookFired() = runTest {
        val queryExecutor = Executors.newSingleThreadExecutor()
        val inCommit = AtomicBoolean(false)
        val writeSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        lateinit var runnerJob: Job
        val probe = NoncopyingProbeCancellation("r09g-postwrite-cancel")
        var caught: Throwable? = null
        // Test-only real Room query hook: observe the first journal/device
        // write, then cancel the caller Job on the FOLLOWING query. Callbacks
        // fire before SQL, so cancelling on the write itself would only prove
        // pre-execution interruption.
        val database = Room.inMemoryDatabaseBuilder(context.applicationContext, GymDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (inCommit.get()) {
                        val lower = sql.lowercase()
                        val isWrite = lower.contains("insert") || lower.contains("update") ||
                            lower.contains("delete")
                        val isAuthorityTable = lower.contains("sync_session_authority") ||
                            lower.contains("sync_device_state")
                        if (!writeSeen.get() && isWrite && isAuthorityTable) {
                            writeSeen.set(true)
                        } else if (writeSeen.getAndSet(false)) {
                            fired.set(true)
                            inCommit.set(false)
                            runnerJob.cancel(probe)
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            seedSignedOutRetainedAuthority(database)
            seedRichRetained(database)
            val authority = RoomSessionAuthority(database) as SameAccountRecoveryAuthority
            val seedCheck = authority.read()
            assertEquals(RecoveryRoomPhase.SIGNED_OUT, seedCheck.phase)
            assertEquals(
                listOf("10", "7", "signed_out", ORIGIN_A, ACCOUNT_A, null),
                journalCells(database),
            )
            val before = authority.read()
            val retainedBefore = retainedSnapshot(database)
            inCommit.set(true)
            runnerJob = async {
                try {
                    authority.beginSameAccountRecovery(before, originA(), ACCOUNT_A, OP_FRESH)
                    "no-throw"
                } catch (e: Throwable) {
                    caught = e
                    throw e
                }
            }
            runnerJob.join()
            inCommit.set(false)
            assertTrue("post-write hook must have fired after the journal/device write", fired.get())
            assertSame(probe, caught)
            assertEquals(before, authority.read())
            assertEquals(listOf("10", "7", "signed_out", ORIGIN_A, ACCOUNT_A, null), journalCells(database))
            assertEquals(retainedBefore, retainedSnapshot(database))
        } finally {
            try {
                database.close()
            } catch (_: Exception) {
            }
            queryExecutor.shutdown()
        }
    }

    @Test
    fun fileReopenLostReturnGapResumesWithExactEvidence() = runTest {
        val namespace = testNamespace("gap")
        deleteNamespacePath(namespace)
        val dbName = "r09g-gap.db"
        val database = createFileDb(dbName)
        lateinit var outerBefore: Map<String, List<List<String?>>>
        try {
            seedSignedOutRetainedAuthority(database)
            seedRichRetained(database)
            outerBefore = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            val plainVault = installUncertainVault(namespace, 6L)
            // Real encrypted CAS-only commit before a lost return: the target +
            // archive stand durably while the invocation reports the exact
            // VAULT_UNAVAILABLE and the Room reservation stays TRANSITIONING.
            val throwing = CommitThenThrowVault(plainVault)
            val coordinator = coordinatorFor(database, throwing)
            try {
                coordinator.prepare(authority.read(), OP_FRESH, freshGrant())
                fail("lost return must report failure")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.VAULT_UNAVAILABLE, e.failure)
                assertNull(e.cause)
            }
            assertEquals(2L, plainVault.load().revision)
            assertEquals(
                listOf("11", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_FRESH),
                journalCells(database),
            )
        } finally {
            database.close()
        }
        // Restart rereads the durable pair: exact operation/generation/archive,
        // resume finalizes with no second vault CAS.
        val reopened = reopenFileDb(dbName)
        try {
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(2L, vault.load().revision)
            assertEquals(freshGrant(), vault.load().record?.session)
            assertEquals(bindingA(generation = 8L), vault.load().record?.binding)
            assertEquals(OP_FRESH, vault.load().record?.operationId)
            assertEquals(1, vault.load().record?.supersededSessions?.entries?.size)
            assertEquals(oldGrant(), vault.load().record?.supersededSessions?.entries?.single()?.record?.session)
            val authority = RoomSessionAuthority(reopened)
            val coordinator = coordinatorFor(reopened, vault)
            val recovery = coordinator.recover()
            assertTrue(recovery is SameAccountRecovery.NeedsFinalization)
            val observation = coordinator.resume(authority.read(), freshGrant())
            assertEquals(12L, observation.room.revision)
            assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
            assertEquals(2L, observation.vaultRevision)
            assertEquals(1, vault.load().record?.supersededSessions?.entries?.size)
            // The ENTIRE retained scope captured before the operation is
            // byte-identical after reopen and resume.
            assertEquals(outerBefore, retainedSnapshot(reopened))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun signedOutReserveThenPreCommitFailureResumesAfterReopenWithSingleArchive() = runTest {
        // SIGNED_OUT gen7 / uncertain gen6 source: the reservation writes
        // TRANSITIONING gen8, the vault CAS fails BEFORE COMMIT, and after
        // reopen the fresh grant resumes at the SAME op/gen with exactly one
        // appended archive, then idempotent resumption duplicates nothing.
        val namespace = testNamespace("precommit")
        deleteNamespacePath(namespace)
        val dbName = "r09g-precommit.db"
        val database = createFileDb(dbName)
        lateinit var outerBefore: Map<String, List<List<String?>>>
        try {
            seedSignedOutRetainedAuthority(database)
            seedRichRetained(database)
            outerBefore = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            val plainVault = installUncertainVault(namespace, 6L)
            val failing = object : SessionVault {
                override suspend fun load(): VaultSnapshot = plainVault.load()
                override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot =
                    throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            val coordinator = coordinatorFor(database, failing)
            try {
                coordinator.prepare(authority.read(), OP_FRESH, freshGrant())
                fail("pre-commit CAS failure must report")
            } catch (e: SessionReauthenticationException) {
                assertEquals(SessionReauthenticationFailure.VAULT_UNAVAILABLE, e.failure)
                assertNull(e.cause)
            }
            // Reservation stands, vault untouched before commit.
            assertEquals(
                listOf("11", "8", "transitioning", ORIGIN_A, ACCOUNT_A, OP_FRESH),
                journalCells(database),
            )
            assertEquals(1L, plainVault.load().revision)
            assertEquals(oldGrant(), plainVault.load().record?.session)
        } finally {
            database.close()
        }
        val reopened = reopenFileDb(dbName)
        try {
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(1L, vault.load().revision)
            val authority = RoomSessionAuthority(reopened)
            val coordinator = coordinatorFor(reopened, vault)
            val recovery = coordinator.recover()
            assertTrue(recovery is SameAccountRecovery.NeedsGrant)
            // Resume uses the fresh grant at the SAME reserved op/gen: no
            // restart, no schema change, no old-credential selection.
            val transitioning = authority.read()
            assertEquals(RecoveryRoomPhase.TRANSITIONING, transitioning.phase)
            assertEquals(OP_FRESH, transitioning.identity?.operationId)
            val observation = coordinator.resume(transitioning, freshGrant())
            assertEquals(12L, observation.room.revision)
            assertEquals(8L, observation.room.sessionGeneration)
            assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
            assertEquals(2L, observation.vaultRevision)
            val stored = vault.load().record
            requireNotNull(stored)
            assertEquals(freshGrant(), stored.session)
            assertEquals(bindingA(generation = 8L), stored.binding)
            assertEquals(OP_FRESH, stored.operationId)
            assertEquals(1, stored.supersededSessions.entries.size)
            assertEquals(1L, stored.supersededSessions.entries.single().sourceRevision)
            assertEquals(oldGrant(), stored.supersededSessions.entries.single().record.session)
            val second = coordinator.resume(observation.room, freshGrant())
            assertEquals(2L, second.vaultRevision)
            assertEquals(1, vault.load().record?.supersededSessions?.entries?.size)
            assertEquals(outerBefore, retainedSnapshot(reopened))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun archiveBearingPairCompletesBootstrapAndCompletionInMinimalScope() = runTest {
        // Separate minimal coherent scope (empty domain, no cache/stage/
        // outbox rows): engine-fixture identity, genuine same-account
        // preparation over a minimal ACTIVE incomplete-baseline scope.
        val engHarness = PreparedEngineHarness("r09g-eng")
        val EF = PreparedEngineFixtures
        val C = PreparedCompletionSupport
        val database = engHarness.openInMemoryDb()
        try {
            val db = database.openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                    " service_origin, account_id, operation_id)" +
                    " VALUES (1, 1, 1, 'active', 'https://sync.example.test:443'," +
                    " '${EF.ACCOUNT}', '${EF.OP}')",
            )
            db.execSQL(
                "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                    " session_generation, cache_state)" +
                    " VALUES (1, '${EF.ACCOUNT}', '${EF.ACCOUNT}', 1, 'ready')",
            )
            // Actual uncertain gen1 source: exact pending body/token.
            val uncertainGen1 = VaultSessionRecord(
                EF.binding(generation = 1L),
                EF.OP,
                VaultSessionPhase.ACTIVE,
                EF.grant(),
                VaultPendingRefresh(EF.OP, EF.pendingAttempt()),
            )
            val vault = engHarness.newVault()
            vault.compareAndSet(0L, uncertainGen1)
            val authority = RoomSessionAuthority(database) as SameAccountRecoveryAuthority
            val coordinator = SameAccountRecoveryCoordinator(
                EF.origin(), authority, vault, PushRunOwner(), Mutex(),
            )
            val rotatedGrant = EF.grant(refresh = "r09c-refresh-r09g")
            val observation = coordinator.prepare(authority.read(), EF.OTHER_OP, rotatedGrant)
            // Room 1->2->3 PREPARING/gen2; vault 1->2 with the genuine archive.
            assertEquals(3L, observation.room.revision)
            assertEquals(2L, observation.room.sessionGeneration)
            assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
            assertEquals(2L, observation.vaultRevision)
            assertEquals(2L, vault.load().revision)
            val pair = vault.load().record
            requireNotNull(pair)
            assertEquals(rotatedGrant, pair.session)
            assertEquals(1, pair.supersededSessions.entries.size)
            assertEquals(1L, pair.supersededSessions.entries.single().sourceRevision)
            // Specified semantic equality: decoded pending attempts are
            // separate objects, so whole-record reference equality is wrong.
            assertEquals(
                VaultSupersededSession(1L, uncertainGen1),
                pair.supersededSessions.entries.single(),
            )
            // Existing engine bootstrap snapshot+tail on the genuine pair.
            val transport = PreparedEngineTransport()
            C.queueBootstrapEmpty(transport)
            C.queueCompletionEmptyTail(transport)
            val engine = EF.engine(database, vault, PreparedEngineAuthApi(), transport, EF.owner())
            val bootstrapped = engine.bootstrap(stageId = EF.STAGE_NEW)
            checkNotNull(bootstrapped.baseline)
            checkNotNull(bootstrapped.delivery)
            assertEquals(2L, vault.load().revision)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
            assertEquals(pair.supersededSessions, vault.load().record?.supersededSessions)
            // Existing completion tail: Room4 ACTIVE/gen2, vault3 ACTIVE — the
            // phase promotion CAS increments the vault revision.
            val lease = R05cTestLease()
            val barrier = r05cGrantBarrier(lease)
            val result = engine.completePreparation(barrier)
            assertEquals(PreparedCompletionStatus.COMPLETED, result.status)
            val delivery = checkNotNull(result.delivery)
            assertEquals(false, delivery.hasOutstandingLocalWork)
            assertEquals(
                listOf("/v1/sync/snapshot", "/v1/sync/pull", "/v1/sync/pull"),
                transport.snapshotPaths(),
            )
            assertEquals(3, transport.capturedRequests.size)
            EF.assertPull(transport.capturedRequests[2], EF.BASELINE)
            assertEquals(
                listOf("4", "2", "active", "https://sync.example.test:443", EF.ACCOUNT, EF.OTHER_OP),
                engHarness.journalRow(database),
            )
            assertEquals(listOf(EF.ACCOUNT, EF.ACCOUNT, "2", "ready"), engHarness.deviceRow(database))
            assertEquals(3L, vault.load().revision)
            assertEquals(VaultSessionPhase.ACTIVE, vault.load().record?.phase)
            assertEquals(rotatedGrant, vault.load().record?.session)
            assertEquals(pair.supersededSessions, vault.load().record?.supersededSessions)
            assertEquals(1, barrier.requests.size)
            assertEquals(EF.ACCOUNT, barrier.requests.single().accountId)
            assertEquals(2L, barrier.requests.single().sessionGeneration)
            assertEquals(1, lease.closeCount)
            C.assertRedactedCompletion(result.toString())
            assertFalse(result.toString().contains("r09c-refresh-r09g"))
            engine.invalidate()
        } finally {
            database.close()
            engHarness.clean()
        }
    }

    @Test
    fun subsequentSettledR09eWriteCarriesArchiveThroughChangedPropagation() = runTest {
        val namespace = testNamespace("r09ewrite")
        deleteNamespacePath(namespace)
        val database = openInMemoryDb()
        try {
            seedActiveAuthorityWithCompleteCache(database)
            seedRichRetained(database)
            val before = retainedSnapshot(database)
            val authority = RoomSessionAuthority(database)
            // Genuine uncertain gen6 source at vault1 (full pending body and
            // token), exact archived settled gen7 target at vault2: the append
            // is allowed because the source is uncertain, not fabricated.
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            val pendingGen6 = VaultPendingRefresh(
                OP_OLD,
                ServiceRefreshAttempt.fromJson(KEY_OLD, " { \"refreshToken\" : \"opaque-refresh-old\" } "),
            )
            val sourceGen6 = VaultSessionRecord(
                bindingA(6L), OP_OLD, VaultSessionPhase.ACTIVE, oldGrant(), pendingGen6,
            )
            vault.compareAndSet(0L, sourceGen6)
            val current = VaultSessionRecord(
                bindingA(7L), OP_OLD, VaultSessionPhase.ACTIVE,
                ServiceSession(
                    ServiceAccountRef(ACCOUNT_A, null),
                    OpaqueToken("opaque-access-r09e-current"),
                    NOW,
                    OpaqueToken("opaque-refresh-r09e-current"),
                    NOW + 30L * 24 * 60 * 60 * 1000,
                ),
                null,
            )
            val archived = current.copy(
                supersededSessions = VaultSupersededSessions(
                    listOf(VaultSupersededSession(1L, sourceGen6)),
                ),
            )
            vault.compareAndSet(1L, archived)
            // Settled R09e replacement write carries the archive forward.
            val rotated = ServiceSession(
                ServiceAccountRef(ACCOUNT_A, null),
                OpaqueToken("opaque-access-r09e-new"),
                NOW,
                OpaqueToken("opaque-refresh-r09e-new"),
                NOW + 30L * 24 * 60 * 60 * 1000,
            )
            val reauth = SessionReauthenticationCoordinator(
                originA(), authority, vault, PushRunOwner(), Mutex(),
            )
            val obs = reauth.reauthenticate(authority.read(), OP_R09E, rotated)
            assertEquals(13L, obs.room.revision)
            assertEquals(3L, obs.vaultRevision)
            assertEquals(rotated, vault.load().record?.session)
            assertEquals(archived.supersededSessions, vault.load().record?.supersededSessions)
            assertEquals(1, vault.load().record?.supersededSessions?.entries?.size)
            assertEquals(before, retainedSnapshot(database))
        } finally {
            database.close()
        }
    }
}
