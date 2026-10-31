package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
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
 * Real Room checks for corrupt authority/device/cache evidence, strict SQLite scalar validation,
 * and preservation without repair.
 */
@RunWith(AndroidJUnit4::class)
class RoomSessionAuthorityCorruptionTest {
    private lateinit var database: GymDatabase
    private lateinit var authority: RoomSessionAuthority

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val OP_A = "11111111-1111-1111-1111-111111111111"
        const val ORIGIN_A = "https://sync.example.test:443"
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

    private fun snapshotTable(sql: String, columns: Int): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until columns).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private suspend fun expectCorrupt(block: suspend () -> Unit) {
        try {
            block()
            fail("expected CORRUPT_AUTHORITY")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY, e.failure)
            assertEquals("Room session authority failure", e.message)
            assertNull("cause must stay closed", e.cause)
            assertTrue(e.suppressed.isEmpty())
            val rendered = e.toString()
            assertFalse(rendered.contains(ACCOUNT_A))
            assertFalse(rendered.contains(OP_A))
            assertFalse(rendered.contains(ORIGIN_A))
        }
    }

    @Test
    fun legacyActiveDeviceWithoutJournal_failsClosedNeverAdopted() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, '$ACCOUNT_A', '$ACCOUNT_A', 7, 'local_only')",
        )
        // Old active row is physically preserved but must not become authority.
        expectCorrupt { authority.read() }
        assertEquals(
            ACCOUNT_A,
            database.openHelper.readableDatabase.query(
                "SELECT active_account_id FROM sync_device_state WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            },
        )
        // Preparation from corrupt observation fails closed as corruption.
        try {
            authority.beginPreparation(
                RecoveryRoomSnapshot(
                    revision = 0L,
                    sessionGeneration = 0L,
                    cachedAccountId = null,
                    phase = RecoveryRoomPhase.SIGNED_OUT,
                    identity = null,
                    baselineComplete = false,
                ),
                com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin.parse(ORIGIN_A),
                ACCOUNT_A,
                OP_A,
            )
            fail("expected closed failure for legacy-active write")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY, e.failure)
            assertEquals("Room session authority failure", e.message)
            assertNull(e.cause)
        }
    }

    @Test
    fun unknownPhaseString_failsClosedWithBytesPreserved() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, '$ACCOUNT_A', 1, 'preparing')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 1, 1, 'forging', '$ORIGIN_A', '$ACCOUNT_A', '$OP_A')",
        )
        val before = snapshotTable(
            "SELECT revision, session_generation, phase, service_origin, account_id, operation_id" +
                " FROM sync_session_authority",
            6,
        )
        expectCorrupt { authority.read() }
        assertEquals(
            before,
            snapshotTable(
                "SELECT revision, session_generation, phase, service_origin, account_id," +
                    " operation_id FROM sync_session_authority",
                6,
            ),
        )
    }

    @Test
    fun nonnumericTextRevision_failsClosedWithoutCoercion() = runTest {
        // Quoted digits take INTEGER affinity in SQLite, so only a genuinely
        // nonnumeric TEXT revision exercises the strict no-coercion path. The
        // tuple is otherwise a valid signed_out observation to isolate the defect.
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 'rev-text', 0, 'signed_out', NULL, NULL, NULL)",
        )
        // Independent storage-class proof before invoking read.
        val storageClass = database.openHelper.readableDatabase.query(
            "SELECT typeof(revision) FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("text", storageClass)
        expectCorrupt { authority.read() }
        // Exact bytes preserved: the TEXT revision is still stored as text.
        val afterClass = database.openHelper.readableDatabase.query(
            "SELECT typeof(revision) FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("text", afterClass)
    }

    @Test
    fun realFractionalRevision_failsClosed() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 1.5, 0, 'signed_out', NULL, NULL, NULL)",
        )
        val storageClass = database.openHelper.readableDatabase.query(
            "SELECT typeof(revision) FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("real", storageClass)
        expectCorrupt { authority.read() }
    }

    @Test
    fun negativeCounters_failClosed() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, -1, 'local_only')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 1, -1, 'signed_out', NULL, NULL, NULL)",
        )
        expectCorrupt { authority.read() }
    }

    @Test
    fun extraSingletonRows_failClosed() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 1, 1, 'signed_out', NULL, NULL, NULL)",
        )
        // Extra singleton row beyond id=1 is corruption, not an ignored spare.
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (2, 1, 1, 'signed_out', NULL, NULL, NULL)",
        )
        expectCorrupt { authority.read() }
    }

    @Test
    fun invalidNullableShapes_failClosed() = runTest {
        // Non-null origin with no cached account is corrupt (provenance without owner).
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 1, 'preparing')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 1, 1, 'signed_out', '$ORIGIN_A', NULL, NULL)",
        )
        expectCorrupt { authority.read() }
    }

    @Test
    fun invalidUuidAndOrigin_failClosed() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, 'not-a-uuid', 1, 'preparing')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 1, 1, 'preparing', 'http://clear.example.test:80', 'not-a-uuid', 'bad-op')",
        )
        expectCorrupt { authority.read() }
    }

    @Test
    fun inconsistentDeviceJournalIdentity_failsClosed() = runTest {
        val db = database.openHelper.writableDatabase
        // Journal preparing for A but device caches nothing: mismatch is corrupt.
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 1, 'preparing')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 1, 1, 'preparing', '$ORIGIN_A', '$ACCOUNT_A', '$OP_A')",
        )
        expectCorrupt { authority.read() }
    }

    @Test
    fun selectedBaselineShapes_failClosedOrBaselineFalseWithBytesPreserved() = runTest {
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
        // baseline_complete must be integer 0/1; 2 is malformed and fails closed.
        // A baseline-false return is NOT an allowed outcome for malformed bytes.
        db.execSQL(
            "INSERT INTO sync_account_cache (account_id, baseline_complete, cache_epoch," +
                " projection_dirty, materialized_epoch)" +
                " VALUES ('$ACCOUNT_A', 2, 5, 0, 4)",
        )
        expectCorrupt { authority.read() }
        // materialized_epoch > cache_epoch is malformed and fails closed as well.
        db.execSQL("UPDATE sync_account_cache SET baseline_complete = 1, materialized_epoch = 9 WHERE account_id = '$ACCOUNT_A'")
        expectCorrupt { authority.read() }
    }

    @Test
    fun seededActiveIncompleteCache_neverEligible() = runTest {
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
                " VALUES ('$ACCOUNT_A', 0, 5, 0, 4)",
        )
        val snapshot = authority.read()
        assertEquals(RecoveryRoomPhase.ACTIVE, snapshot.phase)
        assertFalse("incomplete baseline must not be eligible", snapshot.baselineComplete)
    }
}
