package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R09a independent QA: exact-expected, concurrent reservation, overflow and
 * stale-fence obligations.
 *
 * Contract-only; raw SQL literals; no production inspection. Covers task
 * obligation 3: exact expected snapshot, concurrent reservation single winner,
 * checked revision/generation overflow, stale old-repository fence failure.
 */
@RunWith(AndroidJUnit4::class)
class RoomSessionAuthorityConcurrencyTest {
    private lateinit var database: GymDatabase
    private lateinit var authority: RoomSessionAuthority

    private companion object {
        const val ACCOUNT_A = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val OP_A = "11111111-1111-1111-1111-111111111111"
        const val OP_B = "22222222-2222-2222-2222-222222222222"
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

    private fun originA(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_A)

    @Test
    fun staleExpectedSnapshot_failsStaleAuthorityWithoutWrites() = runTest {
        val fresh = authority.read()
        val preparing = authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)

        // The original fresh observation is now stale for both seams.
        try {
            authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_B)
            fail("expected STALE_AUTHORITY")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.STALE_AUTHORITY, e.failure)
            assertEquals("Room session authority failure", e.message)
            assertNull(e.cause)
        }
        try {
            authority.fencePrivate(fresh)
            fail("expected STALE_AUTHORITY")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.STALE_AUTHORITY, e.failure)
        }
        // Already-PREPARING is also STALE for a second reservation in this slice.
        try {
            authority.beginPreparation(preparing, originA(), ACCOUNT_A, OP_B)
            fail("expected STALE_AUTHORITY for second reservation")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.STALE_AUTHORITY, e.failure)
        }
        assertEquals(preparing, authority.read())
    }

    @Test
    fun concurrentReservations_usingOneExpected_haveExactlyOneWinner() = runTest {
        val fresh = authority.read()
        val first = async {
            try {
                Result.success(authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A))
            } catch (e: RoomSessionAuthorityException) {
                Result.failure<RecoveryRoomSnapshot>(e)
            }
        }
        val second = async {
            try {
                Result.success(authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_B))
            } catch (e: RoomSessionAuthorityException) {
                Result.failure<RecoveryRoomSnapshot>(e)
            }
        }
        val outcomes = listOf(first.await(), second.await())
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        val loser = outcomes.first { it.isFailure }.exceptionOrNull()
            as RoomSessionAuthorityException
        assertEquals(RoomSessionAuthorityFailure.STALE_AUTHORITY, loser.failure)
        assertEquals("Room session authority failure", loser.message)
        assertNull(loser.cause)

        // Durable state reflects exactly the single winner.
        val reread = authority.read()
        assertEquals(RecoveryRoomPhase.PREPARING, reread.phase)
        assertEquals(1L, reread.revision)
        assertEquals(1L, reread.sessionGeneration)
        val winnerOp = outcomes.first { it.isSuccess }.getOrNull()?.identity?.operationId
        assertEquals(winnerOp, reread.identity?.operationId)
    }

    @Test
    fun staleFenceAfterGenerationAdvance_failsWithoutChanges() = runTest {
        val fresh = authority.read()
        val preparing = authority.beginPreparation(fresh, originA(), ACCOUNT_A, OP_A)
        // A stale default-repository generation is fenced: the pre-preparation
        // observation can no longer fence after the generation moved.
        try {
            authority.fencePrivate(fresh)
            fail("expected STALE_AUTHORITY for stale fence")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.STALE_AUTHORITY, e.failure)
        }
        assertEquals(preparing, authority.read())
        // The current observation still fences exactly once.
        val fenced = authority.fencePrivate(preparing)
        assertEquals(RecoveryRoomPhase.SIGNED_OUT, fenced.phase)
        assertEquals(2L, fenced.revision)
        assertEquals(2L, fenced.sessionGeneration)
    }

    @Test
    fun revisionOverflow_failsCounterOverflowWithoutWrites() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, 0, 'local_only')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, ${Long.MAX_VALUE}, 0, 'signed_out', NULL, NULL, NULL)",
        )
        val current = authority.read()
        assertEquals(Long.MAX_VALUE, current.revision)
        try {
            authority.fencePrivate(current)
            fail("expected COUNTER_OVERFLOW")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.COUNTER_OVERFLOW, e.failure)
            assertEquals("Room session authority failure", e.message)
            assertNull(e.cause)
        }
        // No partial advance: revision/generation bytes unchanged.
        database.openHelper.readableDatabase.query(
            "SELECT revision, session_generation, phase FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(Long.MAX_VALUE, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals("signed_out", cursor.getString(2))
        }
    }

    @Test
    fun generationOverflow_failsCounterOverflowWithoutWrites() = runTest {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO sync_device_state (id, active_account_id, cached_account_id," +
                " session_generation, cache_state)" +
                " VALUES (1, NULL, NULL, ${Long.MAX_VALUE}, 'local_only')",
        )
        db.execSQL(
            "INSERT INTO sync_session_authority (id, revision, session_generation, phase," +
                " service_origin, account_id, operation_id)" +
                " VALUES (1, 4, ${Long.MAX_VALUE}, 'signed_out', NULL, NULL, NULL)",
        )
        val current = authority.read()
        assertEquals(4L, current.revision)
        assertEquals(Long.MAX_VALUE, current.sessionGeneration)
        try {
            authority.fencePrivate(current)
            fail("expected COUNTER_OVERFLOW")
        } catch (e: RoomSessionAuthorityException) {
            assertEquals(RoomSessionAuthorityFailure.COUNTER_OVERFLOW, e.failure)
        }
        // sync_device_state carries no revision column: assert each store separately.
        database.openHelper.readableDatabase.query(
            "SELECT session_generation FROM sync_device_state WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(Long.MAX_VALUE, cursor.getLong(0))
        }
        database.openHelper.readableDatabase.query(
            "SELECT revision, session_generation FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4L, cursor.getLong(0))
            assertEquals(Long.MAX_VALUE, cursor.getLong(1))
        }
    }
}
