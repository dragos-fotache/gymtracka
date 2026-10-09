package com.dragosfotache.gymtracka.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13c independent QA (JVM): build-input configuration seam.
 *
 * Derived ONLY from docs/archive/remaining-work/R13c-task.md configuration/UI section
 * plus the coordinator appendix (strict original HTTPS root-origin parsing via
 * ServiceOrigin; malformed/absent origin yields unavailable without I/O; missing
 * audience disables sign-in only while valid origin still recovers offline).
 * Independent literals only; no production oracles, no I/O, no sleeps.
 */
class AccountStartupConfigurationTest {

    private companion object {
        const val VALID_ORIGIN = "https://sync-r13c.example.test"
        const val OTHER_ORIGIN = "https://other-r13c.example.test"
        const val AUDIENCE = "r13c-audience-001"
    }

    @Test
    fun nullOrigin_yieldsUnavailable() {
        val config = AccountStartupConfiguration.fromValues(null, AUDIENCE)
        assertNull(config.origin)
        // Audience mapping is independent: a valid audience still parses while
        // the missing origin alone disables sign-in.
        assertEquals(AUDIENCE, config.serverClientId)
        assertFalse(config.signInAvailable)
    }

    @Test
    fun emptyAndBlankOrigin_yieldUnavailable() {
        for (raw in listOf("", "   ", "\t\n ")) {
            val config = AccountStartupConfiguration.fromValues(raw, AUDIENCE)
            assertNull("raw=<$raw> must map null", config.origin)
            assertFalse(config.signInAvailable)
        }
    }

    @Test
    fun invalidOrigins_yieldNullWithoutDiscovery() {
        val invalid = listOf(
            "http://sync-r13c.example.test",
            "https://sync-r13c.example.test/api",
            "https://sync-r13c.example.test?x=1",
            "https://sync-r13c.example.test#frag",
            "https://user@sync-r13c.example.test",
            " https://sync-r13c.example.test",
            "https://sync-r13c.example.test ",
            "not-a-url",
        )
        for (raw in invalid) {
            val config = AccountStartupConfiguration.fromValues(raw, AUDIENCE)
            assertNull("raw=<$raw> must map null origin", config.origin)
            assertFalse("raw=<$raw> must disable sign-in", config.signInAvailable)
        }
    }

    @Test
    fun trailingSlash_normalizesToSameRootOrigin() {
        val bare = AccountStartupConfiguration.fromValues(VALID_ORIGIN, AUDIENCE)
        val slashed = AccountStartupConfiguration.fromValues("$VALID_ORIGIN/", AUDIENCE)
        assertNotNull(bare.origin)
        assertNotNull(slashed.origin)
        // Value equality on canonical scheme/host/effective port (redacted
        // toString is deliberately equal for ALL origins and proves nothing).
        assertEquals(bare.origin, slashed.origin)
        assertTrue(slashed.signInAvailable)
    }

    @Test
    fun validOrigin_missingAudience_stillRecoversOfflineWithoutSignIn() {
        for (audience in listOf(null, "", "   ", " padded-audience ")) {
            val config = AccountStartupConfiguration.fromValues(VALID_ORIGIN, audience)
            assertNotNull("origin must survive missing audience", config.origin)
            assertNull("audience must map null", config.serverClientId)
            assertFalse("sign-in must be disabled without audience", config.signInAvailable)
        }
    }

    @Test
    fun validOriginAndAudience_enablesSignIn() {
        val config = AccountStartupConfiguration.fromValues(VALID_ORIGIN, AUDIENCE)
        assertNotNull(config.origin)
        assertEquals(AUDIENCE, config.serverClientId)
        assertTrue(config.signInAvailable)
    }

    @Test
    fun changedOrigin_neverAdoptsOldCredentialsByDisplay() {
        val first = AccountStartupConfiguration.fromValues(VALID_ORIGIN, AUDIENCE)
        val second = AccountStartupConfiguration.fromValues(OTHER_ORIGIN, AUDIENCE)
        assertNotNull(first.origin)
        assertNotNull(second.origin)
        assertTrue(first.signInAvailable)
        assertTrue(second.signInAvailable)
        // Fixed safe display: neither endpoint string may enter diagnostics,
        // so distinct origins render without echoing credentials.
        for (rendered in listOf(first.toString(), second.toString())) {
            assertTrue(rendered.isNotBlank())
            assertFalse(rendered.contains(VALID_ORIGIN))
            assertFalse(rendered.contains(OTHER_ORIGIN))
            assertFalse(rendered.contains(AUDIENCE))
        }
    }

    @Test
    fun displayIsFixedAndRedacted() {
        val first = AccountStartupConfiguration.fromValues(VALID_ORIGIN, AUDIENCE).toString()
        val second = AccountStartupConfiguration.fromValues(VALID_ORIGIN, AUDIENCE).toString()
        assertTrue(first.isNotBlank())
        assertEquals(first, second)
        assertFalse(first.contains(VALID_ORIGIN))
        assertFalse(first.contains(AUDIENCE))
    }
}
