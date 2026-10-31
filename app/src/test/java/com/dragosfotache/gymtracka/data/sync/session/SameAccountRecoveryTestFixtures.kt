package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Literal Room/vault/grant bindings and deterministic authority, refresh and file fault doubles
 * for same-account recovery. Expected identities and bytes are independent of production output.
 */
internal object SameAccountRecoveryTestFixtures {
    const val ACCOUNT_ID = RefreshTestFixtures.ACCOUNT_ID
    const val OTHER_ACCOUNT_ID = RefreshTestFixtures.OTHER_ACCOUNT_ID
    const val ORIGIN_ROOT = RefreshTestFixtures.ORIGIN_ROOT
    const val OTHER_ORIGIN_ROOT = RefreshTestFixtures.OTHER_ORIGIN_ROOT
    const val OP_OLD = RefreshTestFixtures.OP_A
    const val OP_NEW = RefreshTestFixtures.OP_B
    const val OP_FRESH = "33333333-3333-3333-3333-333333333333"
    const val KEY_A = RefreshTestFixtures.KEY_A
    const val KEY_B = RefreshTestFixtures.KEY_B
    const val KEY_FRESH = "55555555-5555-5555-5555-555555555555"
    const val NOW = RefreshTestFixtures.NOW

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

    fun freshGrant(accountId: String = ACCOUNT_ID): ServiceSession = session(
        accountId = accountId,
        accessValue = "access-fresh-retained-recovery",
        refreshValue = "refresh-fresh-retained-recovery",
    )

    fun otherGrant(): ServiceSession = session(
        accountId = ACCOUNT_ID,
        accessValue = "access-other-retained-recovery",
        refreshValue = "refresh-other-retained-recovery",
    )

    fun signedOut(
        revision: Long = 10L,
        cachedAccountId: String? = ACCOUNT_ID,
    ): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = revision,
        sessionGeneration = 7L,
        cachedAccountId = cachedAccountId,
        phase = RecoveryRoomPhase.SIGNED_OUT,
        identity = null,
        baselineComplete = false,
    )

    fun activeSnapshot(
        revision: Long = 11L,
        generation: Long = 7L,
        accountId: String = ACCOUNT_ID,
        operationId: String = OP_OLD,
        root: String = ORIGIN_ROOT,
        baselineComplete: Boolean = false,
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
        operationId: String = OP_FRESH,
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
        operationId: String = OP_FRESH,
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
        operationId: String = OP_FRESH,
        session: ServiceSession = freshGrant(),
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

    fun archivedSource(
        sourceRevision: Long = 5L,
        record: VaultSessionRecord = activeRecord(),
    ): VaultSupersededSession = VaultSupersededSession(
        sourceRevision = sourceRevision,
        record = record,
    )

    fun vaultWith(
        record: VaultSessionRecord?,
        revision: Long = 5L,
    ): FakeSessionVault = FakeSessionVault(VaultSnapshot(revision = revision, record = record))

    fun coordinator(
        origin: ServiceOrigin = origin(),
        authority: SameAccountRecoveryAuthority,
        vault: SessionVault,
        runOwner: PushRunOwner = PushRunOwner(),
        credentialMutex: Mutex = Mutex(),
    ): SameAccountRecoveryCoordinator = SameAccountRecoveryCoordinator(
        origin = origin,
        authority = authority,
        vault = vault,
        runOwner = runOwner,
        credentialMutex = credentialMutex,
    )

    suspend fun assertRecoveryFailure(
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
        assertEquals(expected.supersededSessions, actual.supersededSessions)
    }

    fun defaultSecrets(vararg extra: String): List<String> =
        listOf(ACCOUNT_ID, OTHER_ACCOUNT_ID, OP_OLD, OP_NEW, OP_FRESH, KEY_A, KEY_B, KEY_FRESH) +
            extra.toList()
}

/**
 * Dedicated hand-written SameAccountRecoveryAuthority double. No DAO mocks.
 *
 * Defaults mirror the normative reservation/finalization arithmetic from the
 * spec: check reads only; begin reserves TRANSITIONING at +1/+1 with the
 * same account/origin and a fresh operation; finish delegates to the existing
 * TRANSITIONING->PREPARING +1 writer. Tests override behaviors, queue faults,
 * flip [hasLocalRoots], or inject commit-then-throw uncertainty.
 */
internal class FakeSameAccountAuthority(
    initial: RecoveryRoomSnapshot = SameAccountRecoveryTestFixtures.signedOut(),
) : SameAccountRecoveryAuthority {
    private var durable: RecoveryRoomSnapshot = initial

    val readOverrides = ArrayDeque<RecoveryRoomSnapshot>()
    val readFaults = ArrayDeque<Throwable>()
    val checkFaults = ArrayDeque<Throwable>()
    val beginFaults = ArrayDeque<Throwable>()
    val beginSameFaults = ArrayDeque<Throwable>()
    val finishFaults = ArrayDeque<Throwable>()

    var hasLocalRoots: Boolean = false
    /** Specified hidden retained provenance for SIGNED_OUT (identity=null).
     * Never inferred from vault/cache labels; defaults to the configured origin. */
    var retainedOrigin: ServiceOrigin = ServiceOrigin.parse(SameAccountRecoveryTestFixtures.ORIGIN_ROOT)
    var unknownProvenance: Boolean = false
    var commitBeforeBeginSameError: Boolean = false
    var pendingBeginSameError: Throwable? = null
    var commitBeforeFinishError: Boolean = false
    var pendingFinishError: Throwable? = null

    var reads = 0
        private set
    var checks = 0
        private set
    var begins = 0
        private set
    var beginSames = 0
        private set
    var finishes = 0
        private set

    data class BeginSameArgs(
        val expected: RecoveryRoomSnapshot,
        val origin: ServiceOrigin,
        val accountId: String,
        val operationId: String,
    )

    var readGate: (suspend (callIndex: Int) -> Unit)? = null
    var checkGate: (suspend () -> Unit)? = null
    var beginSameGate: (suspend (args: BeginSameArgs) -> Unit)? = null
    var finishGate: (suspend (expected: RecoveryRoomSnapshot) -> Unit)? = null

    var beginSameBehavior: (suspend (args: BeginSameArgs) -> RecoveryRoomSnapshot)? = null
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

    private fun requireSameAccountTarget(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
    ) {
        if (expected != durable) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        if (hasLocalRoots) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION)
        }
        val cached = expected.cachedAccountId
        if (cached == null || cached != accountId) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
        }
        val identity = expected.identity
        if (identity != null) {
            if (identity.binding.origin != origin || identity.binding.accountId != accountId) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
            }
            return
        }
        // SIGNED_OUT retained provenance: explicit stored origin, never
        // inferred from the supplied vault or cache labels.
        if (unknownProvenance || retainedOrigin != origin) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
        }
    }

    override suspend fun checkSameAccountRecovery(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
    ) {
        checks += 1
        checkGate?.invoke()
        if (checkFaults.isNotEmpty()) throw checkFaults.removeFirst()
        requireSameAccountTarget(expected, origin, accountId)
    }

    override suspend fun beginSameAccountRecovery(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
        operationId: String,
    ): RecoveryRoomSnapshot {
        val args = BeginSameArgs(expected, origin, accountId, operationId)
        beginSames += 1
        beginSameGate?.invoke(args)
        if (beginSameFaults.isNotEmpty()) throw beginSameFaults.removeFirst()
        beginSameBehavior?.let { return it(args) }
        requireSameAccountTarget(expected, origin, accountId)
        val presentOp = expected.identity?.operationId
        if (presentOp != null && presentOp == operationId) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        if (expected.revision >= Long.MAX_VALUE - 1 || expected.sessionGeneration == Long.MAX_VALUE) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.COUNTER_OVERFLOW)
        }
        val next = RecoveryRoomSnapshot(
            revision = expected.revision + 1,
            sessionGeneration = expected.sessionGeneration + 1,
            cachedAccountId = accountId,
            phase = RecoveryRoomPhase.TRANSITIONING,
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
        if (commitBeforeBeginSameError) {
            durable = next
            throw checkNotNull(pendingBeginSameError) { "pendingBeginSameError must be set" }
        }
        durable = next
        return next
    }

    override suspend fun beginReauthentication(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        operationId: String,
    ): RecoveryRoomSnapshot {
        begins += 1
        if (beginFaults.isNotEmpty()) throw beginFaults.removeFirst()
        if (expected != durable) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
    }

    override suspend fun finishReauthentication(
        expected: RecoveryRoomSnapshot,
    ): RecoveryRoomSnapshot {
        finishes += 1
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
