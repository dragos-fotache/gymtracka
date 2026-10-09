package com.dragosfotache.gymtracka.data.sync.legacy

import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13b independent QA (offline): fixed/redacted display invariants for the
 * new legacy-import consent/policy/progress/target types.
 *
 * Display-only contract: every display is non-blank, deterministic for equal
 * values, and echoes no origin/account/operation/mutation secret. Behavioral
 * consent/progress obligations live in the facade activation matrix; nothing
 * here simulates production seams.
 */
class LegacyImportPolicyTest {

    private companion object {
        const val ORIGIN = "https://sync-r13b.example.test"
        const val ACCOUNT = "c13b4001-1111-4111-8111-111111111111"
        const val OPERATION = "d13b4001-1111-4111-8111-111111111111"
        const val MUTATION = "70000004-d13b-4d13-8d13-d13bd13bd13b"
    }

    private fun target(): LegacyImportTarget =
        LegacyImportTarget(
            origin = ServiceOrigin.parse(ORIGIN),
            accountId = ACCOUNT,
            operationId = OPERATION,
        )

    private fun request(): LegacyImportReplacementRequest =
        LegacyImportReplacementRequest(target = target(), mutationId = MUTATION)

    private fun policy(): LegacyImportPolicy =
        LegacyImportPolicy(
            replacementConsent = LegacyImportReplacementConsent { true },
        )

    private fun progress(): LegacyImportProgress =
        LegacyImportProgress(
            phase = LegacyImportProgressPhase.CAPTURED,
            importCount = 3L,
            acceptedCount = 0L,
            activeReplacementRequired = true,
            activeReplacementConsented = false,
        )

    private fun assertRedacted(rendered: String) {
        assertTrue(rendered.isNotBlank())
        assertFalse(rendered.contains(ORIGIN))
        assertFalse(rendered.contains(ACCOUNT))
        assertFalse(rendered.contains(OPERATION))
        assertFalse(rendered.contains(MUTATION))
    }

    @Test
    fun target_displayIsFixedAndRedacted() {
        val first = target().toString()
        val second = target().toString()
        assertEquals(first, second)
        assertRedacted(first)
    }

    @Test
    fun replacementRequest_displayIsFixedAndRedacted() {
        val first = request().toString()
        val second = request().toString()
        assertEquals(first, second)
        assertRedacted(first)
    }

    @Test
    fun policy_displayIsFixedAndRedacted() {
        val rendered = policy().toString()
        assertTrue(rendered.isNotBlank())
        assertFalse(rendered.contains(ORIGIN))
        assertFalse(rendered.contains(ACCOUNT))
        assertFalse(rendered.contains(OPERATION))
        assertFalse(rendered.contains(MUTATION))
    }

    @Test
    fun progress_displayIsFixedAndRedacted() {
        val first = progress().toString()
        val second = progress().toString()
        assertEquals(first, second)
        assertRedacted(first)
    }
}
