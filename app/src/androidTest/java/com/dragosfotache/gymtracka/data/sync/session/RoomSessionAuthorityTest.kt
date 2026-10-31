package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room checks for strict private account authority observations, preparation reservation,
 * generation fencing and retained data preservation.
 */
@RunWith(AndroidJUnit4::class)
class RoomSessionAuthorityTest {
    private lateinit var database: GymDatabase
    private lateinit var authority: RoomSessionAuthority

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val ACCOUNT_B = "c6938596-d7e7-50bb-0689-8b3f748dccd1"
        const val OP_A = "11111111-1111-1111-1111-111111111111"
        const val OP_B = "22222222-2222-2222-2222-222222222222"
        const val ORIGIN_A = "https://sync.example.test:443"
        const val ORIGIN_OTHER = "https://other.example.test:443"
    }

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        authority = RoomSessionAuthority(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun originA(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_A)

    private fun bindingA(
        accountId: String = ACCOUNT_A,
        generation: Long = 1L,
        root: String = ORIGIN_A,
    ): ServiceSessionBinding =
        ServiceSessionBinding(ServiceOrigin.parse(root), accountId, generation)

    private fun assertClosedFailure(
        e: RoomSessionAuthorityException,
        expected: RoomSessionAuthorityFailure,
        secrets: List<String> = listOf(ACCOUNT_A, ACCOUNT_B, OP_A, OP_B, ORIGIN_A, ORIGIN_OTHER),
    ) {
        assertEquals(expected, e.failure)
        assertEquals("Room session authority failure", e.message)
        assertNull("cause must stay closed", e.cause)
        assertTrue("suppression must be disabled", e.suppressed.isEmpty())
        val rendered = e.toString()
        for (secret in secrets) {
            assertFalse("display must not echo <$secret>", rendered.contains(secret))
        }
    }

    private suspend fun expectFailure(
        expected: RoomSessionAuthorityFailure,
        block: suspend () -> Unit,
    ): RoomSessionAuthorityException {
        try {
            block()
        } catch (e: RoomSessionAuthorityException) {
            assertClosedFailure(e, expected)
            return e
        }
        fail("expected RoomSessionAuthorityException($expected)")
        throw AssertionError("unreachable")
    }

    private fun rawCount(sql: String): Int =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun snapshotRows(sql: String, columns: Int): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    // ---- Obligation 1: fresh read + empty preparation/private fence ----

    @Test
    fun freshRead_isSignedOutRevisionZeroWithNoIdentity() = runTest {
        val snapshot = authority.read()
        assertEquals(
            RecoveryRoomSnapshot(
                revision = 0L,
                sessionGeneration = 0L,
                cachedAccountId = null,
                phase = RecoveryRoomPhase.SIGNED_OUT,
                identity = null,
                baselineComplete = false,
            ),
            snapshot,
        )
    }

    @Test
    fun emptyPreparation_thenPrivateFence_returnsExactSnapshots() = runTest {
        val fresh = authority.read()
        val preparing = authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        assertEquals(
            RecoveryRoomSnapshot(
                revision = 1L,
                sessionGeneration = 1L,
                cachedAccountId = ACCOUNT_A,
                phase = RecoveryRoomPhase.PREPARING,
                identity = RecoveryIdentity(bindingA(generation = 1L), OP_A),
                baselineComplete = false,
            ),
            preparing,
        )
        // Preparing is private: no baseline/offline eligibility even though bound.
        assertFalse(preparing.baselineComplete)

        // Durable journal + device written atomically, nothing else touched.
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

        val fenced = authority.fencePrivate(preparing)
        assertEquals(
            RecoveryRoomSnapshot(
                revision = 2L,
                sessionGeneration = 2L,
                cachedAccountId = ACCOUNT_A,
                phase = RecoveryRoomPhase.SIGNED_OUT,
                identity = null,
                baselineComplete = false,
            ),
            fenced,
        )
        // Private fencing retains known origin provenance, clears operation.
        database.openHelper.readableDatabase.query(
            "SELECT revision, session_generation, phase, service_origin, account_id, operation_id" +
                " FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
            assertEquals(2L, cursor.getLong(1))
            assertEquals("signed_out", cursor.getString(2))
            assertEquals(ORIGIN_A, cursor.getString(3))
            assertEquals(ACCOUNT_A, cursor.getString(4))
            assertTrue(cursor.isNull(5))
        }
        database.openHelper.readableDatabase.query(
            "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(ACCOUNT_A, cursor.getString(1))
            assertEquals(2L, cursor.getLong(2))
            assertEquals("private", cursor.getString(3))
        }
    }

    @Test
    fun bundledCatalogueAlone_neverBlocksPreparation() = runTest {
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
            "INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type)" +
                " VALUES ('bundled-1', 'muscle', 'primary')",
        )
        db.execSQL(
            "INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items)" +
                " VALUES ('bundled-1', 'equipment', NULL)",
        )

        val fresh = authority.read()
        val preparing = authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        assertEquals(RecoveryRoomPhase.PREPARING, preparing.phase)
        assertEquals(ACCOUNT_A, preparing.cachedAccountId)
    }

    @Test
    fun invalidPreparationInputs_failInvalidInputWithoutWrites() = runTest {
        val fresh = authority.read()
        val beforeJournal = rawCount("SELECT COUNT(*) FROM sync_session_authority")
        val beforeDevice = rawCount("SELECT COUNT(*) FROM sync_device_state")

        expectFailure(RoomSessionAuthorityFailure.INVALID_INPUT) {
            authority.beginPreparation(fresh, originA(), "not-a-uuid", OP_A)
        }
        expectFailure(RoomSessionAuthorityFailure.INVALID_INPUT) {
            authority.beginPreparation(fresh, originA(), ACCOUNT_A.uppercase(), OP_A)
        }
        expectFailure(RoomSessionAuthorityFailure.INVALID_INPUT) {
            authority.beginPreparation(fresh, originA(), ACCOUNT_A, "not-a-uuid")
        }

        assertEquals(beforeJournal, rawCount("SELECT COUNT(*) FROM sync_session_authority"))
        assertEquals(beforeDevice, rawCount("SELECT COUNT(*) FROM sync_device_state"))
        assertEquals(fresh, authority.read())
    }

    // ---- Obligation 2: local roots + account/origin fencing ----

    @Test
    fun localWorkoutRoot_refusesPreparationWithGraphPreserved() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO workouts (id, name, started_at_millis, finished_at_millis, status," +
                " account_id, server_revision, local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('local-w', 'Local W', 4000, NULL, 'active'," +
                " NULL, NULL, 6, 'local_only', NULL)",
        )
        val fresh = authority.read()
        expectFailure(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION) {
            authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        }
        assertEquals(1, rawCount("SELECT COUNT(*) FROM workouts WHERE id = 'local-w'"))
        assertEquals(fresh, authority.read())
    }

    @Test
    fun localRoutineRoot_refusesPreparationWithGraphPreserved() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision," +
                " local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('local-r', 'Local R', 0, NULL, NULL, 1, 'local_only', NULL)",
        )
        val fresh = authority.read()
        expectFailure(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION) {
            authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        }
        assertEquals(1, rawCount("SELECT COUNT(*) FROM routines WHERE id = 'local-r'"))
        assertEquals(fresh, authority.read())
    }

    @Test
    fun localCustomExerciseRoot_refusesPreparationWithGraphPreserved() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO exercises (id, name, type, difficulty_level, force_type, mechanics, category," +
                " instructions, is_user_created, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis)" +
                " VALUES ('custom-local', 'Owned Custom', 'reps', NULL, NULL, NULL, 'strength'," +
                " '[\"Step\"]', 1, NULL, NULL, 0, 'local_only', NULL)",
        )
        val fresh = authority.read()
        expectFailure(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION) {
            authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        }
        assertEquals(1, rawCount("SELECT COUNT(*) FROM exercises WHERE id = 'custom-local'"))
        assertEquals(fresh, authority.read())
    }

    @Test
    fun localDeletedDraftRoutine_refusesPreparationWithRowsAndSnapshotUntouched() = runTest {
        // Deleted drafts are still local-only roots (account_id IS NULL including
        // drafts/deleted rows): preparation refuses without touching anything.
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO routines (id, name, is_draft, account_id, server_revision," +
                " local_generation, sync_state, deleted_at_millis)" +
                " VALUES ('local-draft-tomb', 'Draft Tomb', 1, NULL, NULL, 1, 'local_only', 9000)",
        )
        val beforeRoutines = snapshotRows(
            "SELECT id, name, is_draft, account_id, server_revision, local_generation," +
                " sync_state, deleted_at_millis FROM routines ORDER BY id",
            8,
        )
        val fresh = authority.read()
        expectFailure(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION) {
            authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        }
        assertEquals(
            beforeRoutines,
            snapshotRows(
                "SELECT id, name, is_draft, account_id, server_revision, local_generation," +
                    " sync_state, deleted_at_millis FROM routines ORDER BY id",
                8,
            ),
        )
        assertEquals(fresh, authority.read())
    }

    @Test
    fun failureSuppression_disabledSentinelNotRetained() = runTest {
        val fresh = authority.read()
        authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        val failure = try {
            authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_B)
            fail("expected STALE_AUTHORITY")
            throw AssertionError("unreachable")
        } catch (e: RoomSessionAuthorityException) {
            e
        }
        assertEquals(RoomSessionAuthorityFailure.STALE_AUTHORITY, failure.failure)
        // Suppression is disabled: a sentinel added fault must not be retained.
        failure.addSuppressed(RuntimeException("sentinel-suppressed"))
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun retainedDifferentAccount_refusesWithAccountMismatch() = runTest {
        val fresh = authority.read()
        val preparing = authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        val fenced = authority.fencePrivate(preparing)
        assertEquals(ACCOUNT_A, fenced.cachedAccountId)

        // Same origin but different target account: no switching.
        expectFailure(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH) {
            authority.beginPreparation(fenced, originA(), ACCOUNT_B, OP_B)
        }
        assertEquals(fenced, authority.read())
    }

    @Test
    fun sameAccountDifferentOrUnknownOrigin_refusesWithAccountMismatch() = runTest {
        val fresh = authority.read()
        val preparing = authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        val fenced = authority.fencePrivate(preparing)

        // Same UUID at a different origin refuses.
        expectFailure(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH) {
            authority.beginPreparation(
                fenced,
                ServiceOrigin.parse(ORIGIN_OTHER),
                ACCOUNT_A,
                OP_B,
            )
        }
        // Legacy provenance: journal origin cleared to NULL, same UUID still refuses
        // (no provenance invention).
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_session_authority SET service_origin = NULL WHERE id = 1",
        )
        val withoutProvenance = authority.read()
        assertEquals(RecoveryRoomPhase.SIGNED_OUT, withoutProvenance.phase)
        expectFailure(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH) {
            authority.beginPreparation(withoutProvenance, originA(), ACCOUNT_A, OP_B)
        }
    }

    @Test
    fun knownOrigin_survivesPrivateFenceAndFileReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "authority-known-origin-reopen.db"
        context.deleteDatabase(name)
        try {
            val fileDatabase = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                val fileAuthority = RoomSessionAuthority(fileDatabase)
                val preparing = fileAuthority.beginPreparation(
                    fileAuthority.read(),
                    originA(),
                    ACCOUNT_A,
                    OP_A,
                )
                val fenced = fileAuthority.fencePrivate(preparing)
                assertEquals(RecoveryRoomPhase.SIGNED_OUT, fenced.phase)
                assertEquals(ACCOUNT_A, fenced.cachedAccountId)
            } finally {
                fileDatabase.close()
            }
            val reopened = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                val reread = RoomSessionAuthority(reopened).read()
                assertEquals(RecoveryRoomPhase.SIGNED_OUT, reread.phase)
                assertEquals(ACCOUNT_A, reread.cachedAccountId)
                assertEquals(2L, reread.revision)
                assertEquals(2L, reread.sessionGeneration)
                assertNull(reread.identity)
                assertFalse(reread.baselineComplete)
                reopened.openHelper.readableDatabase.query(
                    "SELECT service_origin, account_id, operation_id, phase" +
                        " FROM sync_session_authority WHERE id = 1",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(ORIGIN_A, cursor.getString(0))
                    assertEquals(ACCOUNT_A, cursor.getString(1))
                    assertTrue(cursor.isNull(2))
                    assertEquals("signed_out", cursor.getString(3))
                }
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    // ---- Obligation 1/5: seeded ACTIVE reader (no ACTIVE writes in Room authority) ----

    @Test
    fun seededActiveCompleteCache_readerReportsBaselineTrue() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, '$ACCOUNT_A', '$ACCOUNT_A', 7, 'local_only')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 3, 7, 'active', '$ORIGIN_A', '$ACCOUNT_A', '$OP_A')",
        )
        db.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$ACCOUNT_A', 1, 5, 0, 4)",
        )

        val snapshot = authority.read()
        assertEquals(
            RecoveryRoomSnapshot(
                revision = 3L,
                sessionGeneration = 7L,
                cachedAccountId = ACCOUNT_A,
                phase = RecoveryRoomPhase.ACTIVE,
                identity = RecoveryIdentity(bindingA(generation = 7L), OP_A),
                baselineComplete = true,
            ),
            snapshot,
        )
    }

    @Test
    fun seededActiveIncompleteCache_readerReportsBaselineFalse() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, '$ACCOUNT_A', '$ACCOUNT_A', 7, 'local_only')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 3, 7, 'active', '$ORIGIN_A', '$ACCOUNT_A', '$OP_A')",
        )
        // No selected cache row: baselineComplete=false, never inferred.
        val snapshot = authority.read()
        assertEquals(RecoveryRoomPhase.ACTIVE, snapshot.phase)
        assertFalse(snapshot.baselineComplete)
    }
}
