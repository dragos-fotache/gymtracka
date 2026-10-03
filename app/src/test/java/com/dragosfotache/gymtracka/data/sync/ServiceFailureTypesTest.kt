package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServiceFailureTypesTest {

    // Table: ten exact type/code/message rows.
    @Test
    fun transient_codeAndMessageAreExact() {
        val failure = SyncApiException.Transient()
        assertEquals("transient", failure.code)
        assertEquals("transient sync failure; retry with bounded backoff", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun authenticationRequired_codeAndMessageAreExact() {
        val failure = SyncApiException.AuthenticationRequired()
        assertEquals("authentication_required", failure.code)
        assertEquals("service access authentication required", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun reauthenticationRequired_codeAndMessageAreExact() {
        val failure = SyncApiException.ReauthenticationRequired()
        assertEquals("reauthentication_required", failure.code)
        assertEquals("foreground reauthentication required", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun invalidCursor_codeAndMessageAreExact() {
        val failure = SyncApiException.InvalidCursor()
        assertEquals("invalid_cursor", failure.code)
        assertEquals("change cursor is invalid", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun cursorExpired_codeAndMessageAreExact() {
        val failure = SyncApiException.CursorExpired()
        assertEquals("cursor_expired", failure.code)
        assertEquals("change cursor expired; full resync required", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun invalidSnapshotToken_codeAndMessageAreExact() {
        val failure = SyncApiException.InvalidSnapshotToken()
        assertEquals("invalid_snapshot_token", failure.code)
        assertEquals("snapshot token is invalid", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun snapshotExpired_codeAndMessageAreExact() {
        val failure = SyncApiException.SnapshotExpired()
        assertEquals("snapshot_expired", failure.code)
        assertEquals("snapshot expired; discard staging and restart", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun requestTooLarge_codeAndMessageAreExact() {
        val failure = SyncApiException.RequestTooLarge()
        assertEquals("request_too_large", failure.code)
        assertEquals("request too large; retain intent and require recovery", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun requestRejected_codeAndMessageAreExact() {
        val failure = SyncApiException.RequestRejected("future_permanent_code")
        assertEquals("request_rejected", failure.code)
        assertEquals("permanent top-level request rejection", failure.message)
        assertEquals("future_permanent_code", failure.errorCode)
        assertNull(failure.cause)
    }

    @Test
    fun malformedResponse_codeAndMessageAreExact() {
        val failure = SyncApiException.MalformedResponse()
        assertEquals("malformed_response", failure.code)
        assertEquals("malformed or inconsistent protocol response", failure.message)
        assertNull(failure.cause)
    }

    // Table: zero-argument compatibility for the five new types plus existing zero-arg types.
    @Test
    fun zeroArgumentConstructors_coverAllExpectedTypes() {
        val reauthentication: SyncApiException = SyncApiException.ReauthenticationRequired()
        val invalidCursor: SyncApiException = SyncApiException.InvalidCursor()
        val invalidSnapshot: SyncApiException = SyncApiException.InvalidSnapshotToken()
        val snapshotExpired: SyncApiException = SyncApiException.SnapshotExpired()
        val tooLarge: SyncApiException = SyncApiException.RequestTooLarge()
        val authentication: SyncApiException = SyncApiException.AuthenticationRequired()
        val cursorExpired: SyncApiException = SyncApiException.CursorExpired()
        val malformed: SyncApiException = SyncApiException.MalformedResponse()
        val transientDefault: SyncApiException = SyncApiException.Transient()
        assertTrue(reauthentication is SyncApiException.ReauthenticationRequired)
        assertTrue(invalidCursor is SyncApiException.InvalidCursor)
        assertTrue(invalidSnapshot is SyncApiException.InvalidSnapshotToken)
        assertTrue(snapshotExpired is SyncApiException.SnapshotExpired)
        assertTrue(tooLarge is SyncApiException.RequestTooLarge)
        assertTrue(authentication is SyncApiException.AuthenticationRequired)
        assertTrue(cursorExpired is SyncApiException.CursorExpired)
        assertTrue(malformed is SyncApiException.MalformedResponse)
        assertTrue(transientDefault is SyncApiException.Transient)
    }

    // Table: every category starts with null cause.
    @Test
    fun allCategories_haveNullCause() {
        val failures: List<SyncApiException> = listOf(
            SyncApiException.Transient(),
            SyncApiException.AuthenticationRequired(),
            SyncApiException.ReauthenticationRequired(),
            SyncApiException.InvalidCursor(),
            SyncApiException.CursorExpired(),
            SyncApiException.InvalidSnapshotToken(),
            SyncApiException.SnapshotExpired(),
            SyncApiException.RequestTooLarge(),
            SyncApiException.RequestRejected("future_permanent_code"),
            SyncApiException.MalformedResponse(),
        )
        for (failure in failures) {
            assertNull("cause for ${failure.code}", failure.cause)
        }
    }

    // Table: initCause refusal keeps raw causes out.
    @Test
    fun allCategories_refuseInitCause() {
        val failures: List<SyncApiException> = listOf(
            SyncApiException.Transient(),
            SyncApiException.AuthenticationRequired(),
            SyncApiException.ReauthenticationRequired(),
            SyncApiException.InvalidCursor(),
            SyncApiException.CursorExpired(),
            SyncApiException.InvalidSnapshotToken(),
            SyncApiException.SnapshotExpired(),
            SyncApiException.RequestTooLarge(),
            SyncApiException.RequestRejected("future_permanent_code"),
            SyncApiException.MalformedResponse(),
        )
        for (failure in failures) {
            try {
                failure.initCause(RuntimeException("raw R02c cause"))
                fail("initCause should fail for ${failure.code}")
            } catch (expected: IllegalStateException) {
                assertNull("cause stays null for ${failure.code}", failure.cause)
            }
        }
    }

    // Table: retry-hint boundaries without overflow.
    @Test
    fun transient_nullStaysNull() {
        assertNull(SyncApiException.Transient().retryAfterMillis)
        assertNull(SyncApiException.Transient(null).retryAfterMillis)
    }

    @Test
    fun transient_validHintRetainsValue() {
        assertEquals(0L, SyncApiException.Transient(0L).retryAfterMillis)
        assertEquals(1L, SyncApiException.Transient(1L).retryAfterMillis)
        assertEquals(12345L, SyncApiException.Transient(12345L).retryAfterMillis)
        assertEquals(300000L, SyncApiException.Transient(300000L).retryAfterMillis)
    }

    @Test
    fun transient_negativeClampsToZero() {
        assertEquals(0L, SyncApiException.Transient(-1L).retryAfterMillis)
        assertEquals(0L, SyncApiException.Transient(-999999L).retryAfterMillis)
        assertEquals(0L, SyncApiException.Transient(Long.MIN_VALUE).retryAfterMillis)
    }

    @Test
    fun transient_largePositiveSaturatesAtCap() {
        assertEquals(300000L, SyncApiException.Transient(300001L).retryAfterMillis)
        assertEquals(300000L, SyncApiException.Transient(1000000000L).retryAfterMillis)
        assertEquals(300000L, SyncApiException.Transient(Long.MAX_VALUE).retryAfterMillis)
    }

    // Table: unknown permanent codes stay distinct data without display leakage.
    @Test
    fun requestRejected_unknownCodeIsRetainedWithoutLeakage() {
        val failure = SyncApiException.RequestRejected("future_permanent_code")
        assertEquals("future_permanent_code", failure.errorCode)
        assertEquals("request_rejected", failure.code)
        assertEquals("permanent top-level request rejection", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.message!!.contains("future_permanent_code"))
        assertFalse(failure.toString().contains("future_permanent_code"))
    }

    @Test
    fun requestRejected_secondUnknownCodeIsRetainedExactly() {
        val failure = SyncApiException.RequestRejected("another_unknown_code_123")
        assertEquals("another_unknown_code_123", failure.errorCode)
        assertEquals("permanent top-level request rejection", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("another_unknown_code_123"))
    }

    @Test
    fun requestRejected_longOpaqueCodeIsRetainedWithoutCap() {
        val longCode = "long_opaque_unknown_code_0123456789_abcdefghijklmnopqrstuvwxyz_0123456789_extra"
        assertTrue(longCode.length > 64)
        val failure = SyncApiException.RequestRejected(longCode)
        assertEquals(longCode, failure.errorCode)
        assertEquals("request_rejected", failure.code)
        assertEquals("permanent top-level request rejection", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.message!!.contains(longCode))
        assertFalse(failure.toString().contains(longCode))
    }

    // Table: access-auth versus foreground reauth are exact types, not substrings.
    @Test
    fun authenticationTypes_areDistinctExactTypes() {
        val access: SyncApiException = SyncApiException.AuthenticationRequired()
        val foreground: SyncApiException = SyncApiException.ReauthenticationRequired()
        assertTrue(access is SyncApiException.AuthenticationRequired)
        assertFalse(access is SyncApiException.ReauthenticationRequired)
        assertTrue(foreground is SyncApiException.ReauthenticationRequired)
        assertFalse(foreground is SyncApiException.AuthenticationRequired)
        assertEquals("authentication_required", access.code)
        assertEquals("reauthentication_required", foreground.code)
    }

    // Table: invalid versus expired read tokens are exact types.
    @Test
    fun cursorInvalidVersusExpired_areDistinctExactTypes() {
        val invalid: SyncApiException = SyncApiException.InvalidCursor()
        val expired: SyncApiException = SyncApiException.CursorExpired()
        assertTrue(invalid is SyncApiException.InvalidCursor)
        assertFalse(invalid is SyncApiException.CursorExpired)
        assertTrue(expired is SyncApiException.CursorExpired)
        assertFalse(expired is SyncApiException.InvalidCursor)
    }

    @Test
    fun snapshotInvalidVersusExpired_areDistinctExactTypes() {
        val invalid: SyncApiException = SyncApiException.InvalidSnapshotToken()
        val expired: SyncApiException = SyncApiException.SnapshotExpired()
        assertTrue(invalid is SyncApiException.InvalidSnapshotToken)
        assertFalse(invalid is SyncApiException.SnapshotExpired)
        assertTrue(expired is SyncApiException.SnapshotExpired)
        assertFalse(expired is SyncApiException.InvalidSnapshotToken)
    }

    // Table: category classes do not wrap cancellation.
    @Test
    fun categories_areNotCancellation() {
        val failures: List<SyncApiException> = listOf(
            SyncApiException.Transient(),
            SyncApiException.AuthenticationRequired(),
            SyncApiException.ReauthenticationRequired(),
            SyncApiException.InvalidCursor(),
            SyncApiException.CursorExpired(),
            SyncApiException.InvalidSnapshotToken(),
            SyncApiException.SnapshotExpired(),
            SyncApiException.RequestTooLarge(),
            SyncApiException.RequestRejected("future_permanent_code"),
            SyncApiException.MalformedResponse(),
        )
        for (failure in failures) {
            assertFalse(kotlinx.coroutines.CancellationException::class.java.isInstance(failure))
        }
    }
}
