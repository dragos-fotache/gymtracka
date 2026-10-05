package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushStorageException
import com.dragosfotache.gymtracka.data.sync.store.StalePushClaimException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R06a independent contract checks: exception hierarchy with fixed safe
 * messages, opaque-capability construction limits and head-result shapes.
 * Pure JVM: no database, no network. Room-backed behavior lives in the
 * androidTest PushClaim suites.
 */
class PushClaimContractTest {

    @Test
    fun claimExceptions_areIllegalStateWithFixedSafeMessages() {
        val firstStale = StalePushClaimException()
        val secondStale = StalePushClaimException()
        assertEquals(IllegalStateException::class.java, StalePushClaimException::class.java.superclass)
        assertEquals(secondStale.message, firstStale.message)
        assertTrue((firstStale.message ?: "").isNotBlank())
        assertNull(firstStale.cause)

        val firstStorage = PushStorageException()
        val secondStorage = PushStorageException()
        assertEquals(IllegalStateException::class.java, PushStorageException::class.java.superclass)
        assertEquals(secondStorage.message, firstStorage.message)
        assertTrue((firstStorage.message ?: "").isNotBlank())
        assertNull(firstStorage.cause)
    }

    @Test
    fun blockReasons_coverExactlyTheContractSet() {
        assertEquals(
            setOf(
                PushBlockReason.REJECTED,
                PushBlockReason.RETAINED_ERROR,
                PushBlockReason.UNSUPPORTED_VERSION,
                PushBlockReason.CORRUPT,
                PushBlockReason.ATTEMPTS_EXHAUSTED,
                PushBlockReason.SNAPSHOT_STAGE,
            ),
            PushBlockReason.entries.toSet(),
        )
        PushBlockReason.entries.forEach { reason ->
            assertEquals(reason, PushBlockReason.valueOf(reason.name))
        }
    }

    @Test
    fun headResults_exposeValueShapes() {
        assertEquals(PushHeadResult.Empty, PushHeadResult.Empty)
        assertEquals(
            PushHeadResult.Deferred(60_000L),
            PushHeadResult.Deferred(60_000L),
        )
        assertEquals(
            PushHeadResult.Blocked(PushBlockReason.REJECTED, "gone"),
            PushHeadResult.Blocked(PushBlockReason.REJECTED, "gone"),
        )
        assertNull(PushHeadResult.Blocked(PushBlockReason.CORRUPT).errorCode)
        assertEquals(PushBlockReason.RETAINED_ERROR, PushHeadResult.Blocked(PushBlockReason.RETAINED_ERROR, "413").reason)
    }

    @Test
    fun diagnostics_neverLeakPayloadText() {
        // Blocked diagnostics must not print the retained error code value.
        // (Constructor visibility is coordinator-reviewed, not reflected here.)
        val blocked = PushHeadResult.Blocked(PushBlockReason.REJECTED, "sentinel-secret-code")
        assertFalse(blocked.toString().contains("sentinel-secret-code"))
        assertTrue(blocked.toString().isNotBlank())
        // Failure diagnostics carry only fixed safe text and no raw causes.
        assertNull(StalePushClaimException().cause)
        assertNull(PushStorageException().cause)
    }
}
