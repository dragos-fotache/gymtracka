package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * R09d independent QA support only. No production declarations inspected.
 *
 * Derived ONLY from docs/archive/remaining-work/R09d-task.md normative API/safety
 * matrix plus the coordinator-supplied completion contract (session-package
 * result/status types, SyncDeliveryResult field shapes, barrier request
 * semantics, frame-consumption sequence, failure-class mapping) and vetted
 * QA-owned helpers (PreparedEngineFixtures/Harness/Transport/AuthApi,
 * R05c lease/barrier fakes). All wire literals are independently authored;
 * completion tail bodies are hand-written strings, never production output.
 */
internal object PreparedCompletionSupport {
    /** Empty terminal pull at the post-bootstrap TAIL cursor (same-cursor rule). */
    const val TAIL_EMPTY_PULL_BODY =
        """{"batches":[],"nextCursor":"r09c-tail","hasMore":false}"""

    fun queueBootstrapEmpty(transport: PreparedEngineTransport) {
        transport.queueOk(PreparedEngineFixtures.EMPTY_SNAPSHOT_BODY)
        transport.queueOk(PreparedEngineFixtures.EMPTY_PULL_BODY)
    }

    /** One additional actual empty pull at the SAME cursor (completion tail). */
    fun queueCompletionEmptyTail(transport: PreparedEngineTransport) {
        transport.queueOk(PreparedEngineFixtures.EMPTY_PULL_BODY)
    }

    fun queueBootstrapNonempty(transport: PreparedEngineTransport) {
        transport.queueOk(PreparedEngineFixtures.snapshotPage1())
        transport.queueOk(PreparedEngineFixtures.snapshotPage2())
        transport.queueOk(PreparedEngineFixtures.pullRevision2Terminal())
    }

    fun queueCompletionTailAtTail(transport: PreparedEngineTransport) {
        transport.queueOk(TAIL_EMPTY_PULL_BODY)
    }

    /** Actual R07 push rejection envelope for one held head (vetted R09c shape). */
    fun pushRejectionBody(mutationId: String): String =
        """{"results":[{"mutationId":"$mutationId","status":"rejected","errorCode":"gone","batch":null}]}"""

    fun defaultSecrets(): List<String> = listOf(
        PreparedEngineFixtures.ACCOUNT,
        PreparedEngineFixtures.OTHER_ACCOUNT,
        PreparedEngineFixtures.OP,
        PreparedEngineFixtures.OTHER_OP,
        PreparedEngineFixtures.ACCESS,
        PreparedEngineFixtures.ACCESS_ROTATED,
        PreparedEngineFixtures.REFRESH,
        PreparedEngineFixtures.REFRESH_KEY,
        PreparedEngineFixtures.PENDING_BODY,
        PreparedEngineFixtures.BASELINE,
        PreparedEngineFixtures.TAIL,
        PreparedEngineFixtures.BATCH_1,
        PreparedEngineFixtures.ORIGIN_ROOT,
        PreparedEngineFixtures.OTHER_ORIGIN_ROOT,
    )

    fun assertClosedCompletion(error: PreparedEngineException, secrets: List<String> = defaultSecrets()) {
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        val message = error.message.orEmpty()
        assertTrue(message.isNotEmpty())
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("closed error must not echo <$secret>", message.contains(secret))
            assertFalse("closed display must not echo <$secret>", error.toString().contains(secret))
        }
    }

    fun assertRedactedCompletion(rendered: String, secrets: List<String> = defaultSecrets()) {
        assertTrue(rendered.isNotEmpty())
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("display must be fixed/redacted but contained <$secret>", rendered.contains(secret))
        }
    }

    fun assertRoomActive(
        harness: PreparedEngineHarness,
        database: GymDatabase,
        account: String = PreparedEngineFixtures.ACCOUNT,
    ) {
        val journal = harness.journalRow(database)
        assertEquals("2", journal[0])
        assertEquals("1", journal[1])
        assertEquals("active", journal[2])
        // Durable canonical origin keeps the explicit effective port
        // (coordinator-supplied storage fact, not production output).
        assertEquals("${PreparedEngineFixtures.ORIGIN_ROOT}:443", journal[3])
        assertEquals(account, journal[4])
        assertEquals(PreparedEngineFixtures.OP, journal[5])
        val device = harness.deviceRow(database)
        assertEquals(account, device[0])
        assertEquals(account, device[1])
        assertEquals("1", device[2])
        assertEquals("ready", device[3])
    }

    fun assertRoomPreparingRev1(
        harness: PreparedEngineHarness,
        database: GymDatabase,
    ) {
        val journal = harness.journalRow(database)
        assertEquals("1", journal[0])
        assertEquals("1", journal[1])
        assertEquals("preparing", journal[2])
    }

    suspend fun assertVaultActiveFor(
        vault: SessionVault,
        expectedRevision: Long,
        account: String = PreparedEngineFixtures.ACCOUNT,
        access: String = PreparedEngineFixtures.ACCESS,
        checkPendingNull: Boolean = true,
    ) {
        assertEquals(expectedRevision, vault.load().revision)
        val record = checkNotNull(vault.load().record)
        assertEquals(VaultSessionPhase.ACTIVE, record.phase)
        assertEquals(account, record.binding?.accountId)
        assertEquals(PreparedEngineFixtures.OP, record.operationId)
        assertEquals(
            PreparedEngineFixtures.grant(accountId = account, access = access),
            record.session,
        )
        if (checkPendingNull) assertNull(record.pendingRefresh)
    }

    suspend fun assertVaultPreparedRev1(vault: SessionVault) {
        assertEquals(1L, vault.load().revision)
        assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
    }
}

/**
 * QA-only vault read/CAS hook (vetted DelayedReturnVault delegate shape).
 * Hook fields are installed per-phase; null means pass-through.
 */
internal class HookSessionVault(
    private val delegate: SessionVault,
    var loadHook: (suspend () -> Unit)? = null,
    var afterCommitHook: (suspend (VaultSnapshot) -> Unit)? = null,
) : SessionVault {
    var casCalls = 0
        private set

    override suspend fun load(): VaultSnapshot {
        loadHook?.invoke()
        return delegate.load()
    }

    override suspend fun compareAndSet(
        expectedRevision: Long,
        record: VaultSessionRecord?,
    ): VaultSnapshot {
        casCalls += 1
        val committed = delegate.compareAndSet(expectedRevision, record)
        afterCommitHook?.invoke(committed)
        return committed
    }
}

/** Lease whose close throws a fixed nonsecret fault (retained-gap harness). */
internal class ThrowingCloseLease : ProjectionPublicationLease {
    var closeCount = 0
        private set

    override fun checkValid() {
    }

    override fun close() {
        closeCount++
        throw RuntimeException("r09d-lease-close-fault")
    }
}
