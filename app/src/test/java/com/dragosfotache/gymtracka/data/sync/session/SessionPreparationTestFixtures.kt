package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail


internal object SessionPreparationTestFixtures {
    const val ACCOUNT_ID = RefreshTestFixtures.ACCOUNT_ID
    const val OTHER_ACCOUNT_ID = RefreshTestFixtures.OTHER_ACCOUNT_ID
    const val ORIGIN_ROOT = RefreshTestFixtures.ORIGIN_ROOT
    const val OTHER_ORIGIN_ROOT = RefreshTestFixtures.OTHER_ORIGIN_ROOT
    const val OP_A = RefreshTestFixtures.OP_A
    const val OP_B = RefreshTestFixtures.OP_B
    const val KEY_A = RefreshTestFixtures.KEY_A
    const val KEY_B = RefreshTestFixtures.KEY_B
    const val NOW = RefreshTestFixtures.NOW

    const val OP_C = "33333333-3333-3333-3333-333333333333"
    const val KEY_C = "55555555-5555-5555-5555-555555555555"

    fun origin(root: String = ORIGIN_ROOT): ServiceOrigin = ServiceOrigin.parse(root)

    fun binding(
        root: String = ORIGIN_ROOT,
        accountId: String = ACCOUNT_ID,
        generation: Long = 1L,
    ): ServiceSessionBinding = RefreshTestFixtures.binding(
        root = root,
        accountId = accountId,
        generation = generation,
    )

    fun session(
        accountId: String = ACCOUNT_ID,
        accessValue: String = "access-1",
        accessExpiresAtMillis: Long = NOW + RefreshTestFixtures.FIFTEEN_MIN,
        refreshValue: String = "refresh-1",
        refreshExpiresAtMillis: Long = NOW + RefreshTestFixtures.THIRTY_DAYS,
    ): ServiceSession = RefreshTestFixtures.session(
        accountId = accountId,
        accessValue = accessValue,
        accessExpiresAtMillis = accessExpiresAtMillis,
        refreshValue = refreshValue,
        refreshExpiresAtMillis = refreshExpiresAtMillis,
    )

    fun expiredSession(accountId: String = ACCOUNT_ID): ServiceSession =
        SessionRecoveryTestFixtures.expiredSession(accountId = accountId)

    fun signedOut(
        revision: Long = 0L,
        cachedAccountId: String? = null,
    ): RecoveryRoomSnapshot = SessionRecoveryTestFixtures.signedOutSnapshot(
        revision = revision,
        cachedAccountId = cachedAccountId,
    )

    fun preparing(
        revision: Long = 1L,
        generation: Long = 1L,
        accountId: String = ACCOUNT_ID,
        operationId: String = OP_A,
        root: String = ORIGIN_ROOT,
    ): RecoveryRoomSnapshot {
        val b = binding(root = root, accountId = accountId, generation = generation)
        return RecoveryRoomSnapshot(
            revision = revision,
            sessionGeneration = generation,
            cachedAccountId = accountId,
            phase = RecoveryRoomPhase.PREPARING,
            identity = RecoveryIdentity(binding = b, operationId = operationId),
            baselineComplete = false,
        )
    }

    fun preparedRecord(
        binding: ServiceSessionBinding = binding(),
        operationId: String = OP_A,
        session: ServiceSession = session(accountId = binding.accountId),
        pendingRefresh: VaultPendingRefresh? = null,
    ): VaultSessionRecord = VaultSessionRecord(
        binding = binding,
        operationId = operationId,
        phase = VaultSessionPhase.PREPARED,
        session = session,
        pendingRefresh = pendingRefresh,
    )

    fun pending(
        operationId: String = OP_A,
        key: String = KEY_A,
        refreshValue: String = "refresh-1",
    ): VaultPendingRefresh = RefreshTestFixtures.pending(
        operationId = operationId,
        key = key,
        refreshValue = refreshValue,
    )

    fun whitespacePending(
        operationId: String = OP_A,
        key: String = KEY_A,
        refreshValue: String = "refresh-1",
    ): VaultPendingRefresh = SessionRecoveryTestFixtures.whitespacePending(
        operationId = operationId,
        key = key,
        refreshValue = refreshValue,
    )

    fun emptyVault(revision: Long = 0L): FakeSessionVault =
        FakeSessionVault(VaultSnapshot(revision = revision, record = null))

    fun vaultWith(
        record: VaultSessionRecord?,
        revision: Long = 1L,
    ): FakeSessionVault = FakeSessionVault(VaultSnapshot(revision = revision, record = record))

    fun coordinator(
        origin: ServiceOrigin = origin(),
        authority: SessionPreparationAuthority,
        vault: SessionVault,
    ): SessionPreparationCoordinator = SessionPreparationCoordinator(
        origin = origin,
        authority = authority,
        vault = vault,
    )

    suspend fun assertPreparationFailure(
        expected: SessionPreparationFailure,
        block: suspend () -> Unit,
    ): SessionPreparationException {
        try {
            block()
            fail("Expected SessionPreparationException($expected)")
        } catch (e: SessionPreparationException) {
            assertEquals(expected, e.failure)
            return e
        }
        throw AssertionError("unreachable")
    }

    fun assertClosed(e: SessionPreparationException, secrets: List<String>) {
        assertNull("cause must stay closed", e.cause)
        assertTrue("suppression must be disabled", e.suppressed.isEmpty())
        val message = e.message.orEmpty()
        assertTrue("message must be fixed/non-empty", message.isNotEmpty())
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("closed error must not echo <$secret>", message.contains(secret))
            assertFalse("closed error display must not echo <$secret>", e.toString().contains(secret))
        }
    }

    fun assertRedacted(rendered: String, secrets: List<String>) {
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("display must be fixed/redacted but contained <$secret>", rendered.contains(secret))
        }
    }

    /** Semantic pending equality per spec: operation + key + body + refresh value. */
    fun assertSemanticPendingEqual(expected: VaultPendingRefresh?, actual: VaultPendingRefresh?) {
        assertEquals(expected?.operationId, actual?.operationId)
        assertEquals(expected?.attempt?.idempotencyKey, actual?.attempt?.idempotencyKey)
        assertEquals(expected?.attempt?.requestJson, actual?.attempt?.requestJson)
        assertEquals(
            expected?.attempt?.refreshToken?.value,
            actual?.attempt?.refreshToken?.value,
        )
    }

    fun assertSemanticRecordEqual(expected: VaultSessionRecord, actual: VaultSessionRecord) {
        assertEquals(expected.binding, actual.binding)
        assertEquals(expected.operationId, actual.operationId)
        assertEquals(expected.phase, actual.phase)
        assertEquals(expected.session, actual.session)
        assertSemanticPendingEqual(expected.pendingRefresh, actual.pendingRefresh)
    }

    fun defaultSecrets(
        vararg extra: String,
    ): List<String> = listOf(ACCOUNT_ID, OTHER_ACCOUNT_ID, OP_A, OP_B, OP_C, KEY_A, KEY_B) +
        extra.toList()
}

/**
 * Small hand-written SessionPreparationAuthority double with deterministic gates.
 * No DAO mocks. Default begin computes the literal independent +1 preparing
 * observation; tests override [beginBehavior] or queue [beginFaults] for closed
 * authority categories, commit-then-throw uncertainty, or cancellation identity.
 */
internal class ScriptedPreparationAuthority(
    initial: RecoveryRoomSnapshot = SessionPreparationTestFixtures.signedOut(),
) : SessionPreparationAuthority {
    private var durable: RecoveryRoomSnapshot = initial

    /** One-shot read overrides consumed in order; absent entries serve [durable]. */
    val readOverrides = ArrayDeque<RecoveryRoomSnapshot>()
    val readFaults = ArrayDeque<Throwable>()

    /** One-shot begin faults consumed in order before [beginBehavior]/default. */
    val beginFaults = ArrayDeque<Throwable>()

    /**
     * Commit-then-fail injection: when true the default begin first advances
     * [durable] to the computed preparing snapshot and then throws [pendingBeginError].
     */
    var commitBeforeBeginError: Boolean = false
    var pendingBeginError: Throwable? = null

    var reads = 0
        private set
    var begins = 0
        private set
    val seenBegins = mutableListOf<BeginArgs>()

    data class BeginArgs(
        val expected: RecoveryRoomSnapshot,
        val origin: ServiceOrigin,
        val accountId: String,
        val operationId: String,
    )

    /** Gate invoked per read before serving durable/override. */
    var readGate: (suspend (callIndex: Int) -> Unit)? = null

    /** Gate invoked per begin before fault/behavior/default. */
    var beginGate: (suspend (args: BeginArgs) -> Unit)? = null

    /** Full override; when set it replaces the default +1 computation. */
    var beginBehavior: (suspend (args: BeginArgs) -> RecoveryRoomSnapshot)? = null

    fun durableSnapshot(): RecoveryRoomSnapshot = durable

    fun seedDurable(snapshot: RecoveryRoomSnapshot) {
        durable = snapshot
        readOverrides.clear()
        readFaults.clear()
    }

    override suspend fun read(): RecoveryRoomSnapshot {
        reads += 1
        readGate?.invoke(reads)
        if (readFaults.isNotEmpty()) throw readFaults.removeFirst()
        if (readOverrides.isNotEmpty()) return readOverrides.removeFirst()
        return durable
    }

    override suspend fun beginPreparation(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
        operationId: String,
    ): RecoveryRoomSnapshot {
        val args = BeginArgs(expected, origin, accountId, operationId)
        begins += 1
        seenBegins += args
        beginGate?.invoke(args)
        if (beginFaults.isNotEmpty()) throw beginFaults.removeFirst()
        beginBehavior?.let { return it(args) }
        if (expected != durable) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        if (expected.revision == Long.MAX_VALUE || expected.sessionGeneration == Long.MAX_VALUE) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.COUNTER_OVERFLOW)
        }
        val next = RecoveryRoomSnapshot(
            revision = expected.revision + 1,
            sessionGeneration = expected.sessionGeneration + 1,
            cachedAccountId = accountId,
            phase = RecoveryRoomPhase.PREPARING,
            identity = RecoveryIdentity(
                binding = ServiceSessionBinding(
                    origin = origin,
                    accountId = accountId,
                    sessionGeneration = expected.sessionGeneration + 1,
                ),
                operationId = operationId,
            ),
            baselineComplete = false,
        )
        if (commitBeforeBeginError) {
            durable = next
            throw checkNotNull(pendingBeginError) { "pendingBeginError must be set" }
        }
        durable = next
        return next
    }
}

/** Convenience for exact attempt bodies without production help. */
internal fun attemptBody(key: String, refreshValue: String): ServiceRefreshAttempt =
    ServiceRefreshAttempt.fromJson(key, "{\"refreshToken\":\"$refreshValue\"}")
