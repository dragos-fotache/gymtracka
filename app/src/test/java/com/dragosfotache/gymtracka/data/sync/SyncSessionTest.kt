package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Pure session-fence rules: no database, no network, no Google types. */
class SyncSessionTest {

    @Test
    fun session_defaultsToSignedOut() {
        assertNull(RepositorySession.SIGNED_OUT.accountId)
        assertEquals(0L, RepositorySession.SIGNED_OUT.sessionGeneration)
    }

    @Test
    fun session_rejectsBlankAccountsAndNegativeGenerations() {
        try {
            RepositorySession("   ", 0L)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            RepositorySession("account-a", -1L)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("account-a", RepositorySession("account-a", 0L).accountId)
    }

    @Test
    fun staleException_carriesBothSides() {
        val error = StaleSessionException("account-a", 7L, null, 1L)
        assertEquals("account-a", error.expectedAccountId)
        assertEquals(7L, error.expectedGeneration)
        assertNull(error.actualAccountId)
        assertEquals(1L, error.actualGeneration)
    }
}
