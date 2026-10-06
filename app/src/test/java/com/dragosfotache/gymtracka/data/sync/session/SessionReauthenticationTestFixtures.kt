package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * R09e independent QA fixtures only. No production declarations.
 *
 * All values derive from docs/archive/remaining-work/R09e-task.md normative API plus
 * QA-owned R04b/R04c/R09b fixtures (RefreshTestFixtures,
 * SessionRecoveryTestFixtures, SessionPreparationTestFixtures,
 * FakeSessionVault). No production source, diffs, Worker work, generated
 * sources, decompiled classes or schema JSON inspected. Coordinator-supplied
 * harness facts (read() shape, ctor order, inert construction, model shapes)
 * used as contracts.
 *
 * Spec-fixed constructors used only:
 * ServiceOrigin.parse, ServiceSessionBinding(origin, accountId, generation),
 * RecoveryIdentity(binding, operationId), RecoveryRoomSnapshot(revision,
 * sessionGeneration, cachedAccountId, phase, identity, baselineComplete),
 * ServiceSession(account, accessToken, accessExpiresAtMillis, refreshToken,
 * refreshExpiresAtMillis), VaultSnapshot(revision, record),
 * VaultSessionRecord(binding, operationId, phase, session, pendingRefresh),
 * VaultPendingRefresh(operationId, attempt),
 * ServiceRefreshAttempt.fromJson(key, body).
 */
internal object SessionReauthenticationTestFixtures {
    const val ACCOUNT_ID = RefreshTestFixtures.ACCOUNT_ID
    const val OTHER_ACCOUNT_ID = RefreshTestFixtures.OTHER_ACCOUNT_ID
    const val ORIGIN_ROOT = RefreshTestFixtures.ORIGIN_ROOT
    const val OTHER_ORIGIN_ROOT = RefreshTestFixtures.OTHER_ORIGIN_ROOT
    const val OP_OLD = RefreshTestFixtures.OP_A
    const val OP_NEW = RefreshTestFixtures.OP_B
    const val KEY_A = RefreshTestFixtures.KEY_A
    const val KEY_B = RefreshTestFixtures.KEY_B
    const val NOW = RefreshTestFixtures.NOW

    const val OP_OTHER = "33333333-3333-3333-3333-333333333333"
    const val KEY_OTHER = "55555555-5555-5555-5555-555555555555"
    const val LETTER_OP = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

    fun origin(root: String = ORIGIN_ROOT): ServiceOrigin = ServiceOrigin.parse(root)

    fun binding(
        root: String = ORIGIN_ROOT,
        accountId: String = ACCOUNT_ID,
        generation: Long = 7L,
    ): ServiceSessionBinding = RefreshTestFixtures.binding(
        root = root,
        accountId = accountId,
        generation = generation,
    )

    fun session(
        accountId: String = ACCOUNT_ID,
        accessValue: String = "access-old",
        accessExpiresAtMillis: Long = NOW + RefreshTestFixtures.FIFTEEN_MIN,
        refreshValue: String = "refresh-old",
        refreshExpiresAtMillis: Long = NOW + RefreshTestFixtures.THIRTY_DAYS,
    ): ServiceSession = RefreshTestFixtures.session(
        accountId = accountId,
        accessValue = accessValue,
        accessExpiresAtMillis = accessExpiresAtMillis,
        refreshValue = refreshValue,
        refreshExpiresAtMillis = refreshExpiresAtMillis,
    )

    fun newGrant(accountId: String = ACCOUNT_ID): ServiceSession = session(
        accountId = accountId,
        accessValue = "access-new",
        refreshValue = "refresh-new",
    )

    fun expiredGrant(accountId: String = ACCOUNT_ID): ServiceSession =
        SessionRecoveryTestFixtures.expiredSession(accountId = accountId)

    fun activeSnapshot(
        revision: Long = 11L,
        generation: Long = 7L,
        accountId: String = ACCOUNT_ID,
        operationId: String = OP_OLD,
        root: String = ORIGIN_ROOT,
        baselineComplete: Boolean = true,
    ): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = revision,
        sessionGeneration = generation,
        cachedAccountId = accountId,
        phase = RecoveryRoomPhase.ACTIVE,
        identity = RecoveryIdentity(
            binding = binding(root = root, accountId = accountId, generation = generation),
            operationId = operationId,
        ),
        baselineComplete = baselineComplete,
    )

    fun transitioningSnapshot(
        revision: Long = 12L,
        generation: Long = 8L,
        accountId: String = ACCOUNT_ID,
        operationId: String = OP_NEW,
        root: String = ORIGIN_ROOT,
    ): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = revision,
        sessionGeneration = generation,
        cachedAccountId = accountId,
        phase = RecoveryRoomPhase.TRANSITIONING,
        identity = RecoveryIdentity(
            binding = binding(root = root, accountId = accountId, generation = generation),
            operationId = operationId,
        ),
        baselineComplete = false,
    )

    fun preparingSnapshot(
        revision: Long = 13L,
        generation: Long = 8L,
        accountId: String = ACCOUNT_ID,
        operationId: String = OP_NEW,
        root: String = ORIGIN_ROOT,
    ): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = revision,
        sessionGeneration = generation,
        cachedAccountId = accountId,
        phase = RecoveryRoomPhase.PREPARING,
        identity = RecoveryIdentity(
            binding = binding(root = root, accountId = accountId, generation = generation),
            operationId = operationId,
        ),
        baselineComplete = false,
    )

    fun activeRecord(
        generation: Long = 7L,
        operationId: String = OP_OLD,
        session: ServiceSession = session(),
        pendingRefresh: VaultPendingRefresh? = null,
        root: String = ORIGIN_ROOT,
        accountId: String = ACCOUNT_ID,
    ): VaultSessionRecord = VaultSessionRecord(
        binding = binding(root = root, accountId = accountId, generation = generation),
        operationId = operationId,
        phase = VaultSessionPhase.ACTIVE,
        session = session,
        pendingRefresh = pendingRefresh,
    )

    fun preparedTargetRecord(
        generation: Long = 8L,
        operationId: String = OP_NEW,
        session: ServiceSession = newGrant(),
        pendingRefresh: VaultPendingRefresh? = null,
        root: String = ORIGIN_ROOT,
        accountId: String = ACCOUNT_ID,
    ): VaultSessionRecord = VaultSessionRecord(
        binding = binding(root = root, accountId = accountId, generation = generation),
        operationId = operationId,
        phase = VaultSessionPhase.PREPARED,
        session = session,
        pendingRefresh = pendingRefresh,
    )

    fun pending(
        operationId: String = OP_OLD,
        key: String = KEY_A,
        refreshValue: String = "refresh-old",
    ): VaultPendingRefresh = RefreshTestFixtures.pending(
        operationId = operationId,
        key = key,
        refreshValue = refreshValue,
    )

    fun whitespacePending(
        operationId: String = OP_OLD,
        key: String = KEY_A,
        refreshValue: String = "refresh-old",
    ): VaultPendingRefresh = SessionRecoveryTestFixtures.whitespacePending(
        operationId = operationId,
        key = key,
        refreshValue = refreshValue,
    )

    fun vaultWith(
        record: VaultSessionRecord?,
        revision: Long = 5L,
    ): FakeSessionVault = FakeSessionVault(VaultSnapshot(revision = revision, record = record))

    fun coordinator(
        origin: ServiceOrigin = origin(),
        authority: SessionReauthenticationAuthority,
        vault: SessionVault,
        runOwner: PushRunOwner = PushRunOwner(),
        credentialMutex: Mutex = Mutex(),
    ): SessionReauthenticationCoordinator = SessionReauthenticationCoordinator(
        origin = origin,
        authority = authority,
        vault = vault,
        runOwner = runOwner,
        credentialMutex = credentialMutex,
    )

    suspend fun assertReauthFailure(
        expected: SessionReauthenticationFailure,
        block: suspend () -> Unit,
    ): SessionReauthenticationException {
        try {
            block()
            fail("Expected SessionReauthenticationException($expected)")
        } catch (e: SessionReauthenticationException) {
            assertEquals(expected, e.failure)
            return e
        }
        throw AssertionError("unreachable")
    }

    fun assertClosed(e: SessionReauthenticationException, secrets: List<String>) {
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

    fun defaultSecrets(vararg extra: String): List<String> =
        listOf(ACCOUNT_ID, OTHER_ACCOUNT_ID, OP_OLD, OP_NEW, OP_OTHER, KEY_A, KEY_B) + extra.toList()

    /**
     * Test-only non-copying cancellation probe: exact identity survives
     * coroutine stack recovery because [createCopy] returns null. Proves
     * cancellation identity across seams without touching debug/stack
     * recovery or global configuration.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    class ProbeCancellationException(message: String) :
        CancellationException(message),
        CopyableThrowable<ProbeCancellationException> {
        override fun createCopy(): ProbeCancellationException? = null
    }
}

/**
 * Hand-written SessionReauthenticationAuthority double with deterministic
 * gates. No DAO mocks. Defaults compute the literal independent reservation
 * (+1 revision/+1 generation TRANSITIONING) and finalization (+1 revision
 * PREPARING); tests override behaviors or queue faults for closed categories,
 * commit-then-throw uncertainty, or cancellation identity.
 */
internal class ScriptedReauthenticationAuthority(
    initial: RecoveryRoomSnapshot = SessionReauthenticationTestFixtures.activeSnapshot(),
) : SessionReauthenticationAuthority {
    private var durable: RecoveryRoomSnapshot = initial

    val readOverrides = ArrayDeque<RecoveryRoomSnapshot>()
    val readFaults = ArrayDeque<Throwable>()
    val beginFaults = ArrayDeque<Throwable>()
    val finishFaults = ArrayDeque<Throwable>()

    var commitBeforeBeginError: Boolean = false
    var pendingBeginError: Throwable? = null
    var commitBeforeFinishError: Boolean = false
    var pendingFinishError: Throwable? = null

    var reads = 0
        private set
    var begins = 0
        private set
    var finishes = 0
        private set
    val seenBegins = mutableListOf<BeginArgs>()
    val seenFinishes = mutableListOf<RecoveryRoomSnapshot>()

    data class BeginArgs(
        val expected: RecoveryRoomSnapshot,
        val origin: ServiceOrigin,
        val operationId: String,
    )

    var readGate: (suspend (callIndex: Int) -> Unit)? = null
    var beginGate: (suspend (args: BeginArgs) -> Unit)? = null
    var finishGate: (suspend (expected: RecoveryRoomSnapshot) -> Unit)? = null

    var beginBehavior: (suspend (args: BeginArgs) -> RecoveryRoomSnapshot)? = null
    var finishBehavior: (suspend (expected: RecoveryRoomSnapshot) -> RecoveryRoomSnapshot)? = null

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

    override suspend fun beginReauthentication(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        operationId: String,
    ): RecoveryRoomSnapshot {
        val args = BeginArgs(expected, origin, operationId)
        begins += 1
        seenBegins += args
        beginGate?.invoke(args)
        if (beginFaults.isNotEmpty()) throw beginFaults.removeFirst()
        beginBehavior?.let { return it(args) }
        if (expected != durable) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        if (expected.phase != RecoveryRoomPhase.ACTIVE || !expected.baselineComplete) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        val identity = checkNotNull(expected.identity)
        if (operationId == identity.operationId) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        if (expected.revision > Long.MAX_VALUE - 2 || expected.sessionGeneration == Long.MAX_VALUE) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.COUNTER_OVERFLOW)
        }
        val next = RecoveryRoomSnapshot(
            revision = expected.revision + 1,
            sessionGeneration = expected.sessionGeneration + 1,
            cachedAccountId = identity.binding.accountId,
            phase = RecoveryRoomPhase.TRANSITIONING,
            identity = RecoveryIdentity(
                binding = ServiceSessionBinding(
                    origin = origin,
                    accountId = identity.binding.accountId,
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

    override suspend fun finishReauthentication(
        expected: RecoveryRoomSnapshot,
    ): RecoveryRoomSnapshot {
        finishes += 1
        seenFinishes += expected
        finishGate?.invoke(expected)
        if (finishFaults.isNotEmpty()) throw finishFaults.removeFirst()
        finishBehavior?.let { return it(expected) }
        if (expected != durable) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        if (expected.phase != RecoveryRoomPhase.TRANSITIONING) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        if (expected.revision == Long.MAX_VALUE) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.COUNTER_OVERFLOW)
        }
        val next = expected.copy(revision = expected.revision + 1, phase = RecoveryRoomPhase.PREPARING)
        if (commitBeforeFinishError) {
            durable = next
            throw checkNotNull(pendingFinishError) { "pendingFinishError must be set" }
        }
        durable = next
        return next
    }
}
