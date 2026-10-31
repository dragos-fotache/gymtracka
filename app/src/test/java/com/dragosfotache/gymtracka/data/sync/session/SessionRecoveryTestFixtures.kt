package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Constructors below use only spec-fixed names/signatures:
 * RecoveryIdentity(binding, operationId), RecoveryRoomSnapshot(revision,
 * sessionGeneration, cachedAccountId, phase, identity, baselineComplete),
 * SessionRecoveryCoordinator(origin, vault, authority, authApi, clockMillis,
 * uuidProvider), VaultSnapshot(revision, record), VaultSessionRecord(binding,
 * operationId, phase, session, pendingRefresh).
 */
internal object SessionRecoveryTestFixtures {
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
    const val OP_D = "66666666-6666-6666-6666-666666666666"
    const val KEY_D = "77777777-7777-7777-7777-777777777777"
    const val OP_E = "88888888-8888-8888-8888-888888888888"
    const val KEY_E = "99999999-9999-9999-9999-999999999999"

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

    /** Session whose access AND refresh are both expired relative to NOW. */
    fun expiredSession(
        accountId: String = ACCOUNT_ID,
        accessValue: String = "access-old",
        refreshValue: String = "refresh-old",
    ): ServiceSession = session(
        accountId = accountId,
        accessValue = accessValue,
        accessExpiresAtMillis = NOW - 1_000L,
        refreshValue = refreshValue,
        refreshExpiresAtMillis = NOW - 1_000L,
    )

    fun identity(
        binding: ServiceSessionBinding = binding(),
        operationId: String = OP_A,
    ): RecoveryIdentity = RecoveryIdentity(
        binding = binding,
        operationId = operationId,
    )

    fun signedOutSnapshot(
        revision: Long = 0L,
        cachedAccountId: String? = null,
    ): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = revision,
        sessionGeneration = 0L,
        cachedAccountId = cachedAccountId,
        phase = RecoveryRoomPhase.SIGNED_OUT,
        identity = null,
        baselineComplete = false,
    )

    fun roomSnapshot(
        revision: Long = 3L,
        binding: ServiceSessionBinding = binding(),
        operationId: String = OP_A,
        phase: RecoveryRoomPhase = RecoveryRoomPhase.ACTIVE,
        baselineComplete: Boolean = true,
    ): RecoveryRoomSnapshot = RecoveryRoomSnapshot(
        revision = revision,
        sessionGeneration = binding.sessionGeneration,
        cachedAccountId = binding.accountId,
        phase = phase,
        identity = identity(binding = binding, operationId = operationId),
        baselineComplete = baselineComplete,
    )

    fun activeCompleteSnapshot(
        revision: Long = 3L,
        binding: ServiceSessionBinding = binding(),
        operationId: String = OP_A,
    ): RecoveryRoomSnapshot = roomSnapshot(
        revision = revision,
        binding = binding,
        operationId = operationId,
        phase = RecoveryRoomPhase.ACTIVE,
        baselineComplete = true,
    )

    fun record(
        binding: ServiceSessionBinding = binding(),
        operationId: String = OP_A,
        phase: VaultSessionPhase = VaultSessionPhase.ACTIVE,
        session: ServiceSession = session(accountId = binding.accountId),
        pendingRefresh: VaultPendingRefresh? = null,
    ): VaultSessionRecord = RefreshTestFixtures.record(
        binding = binding,
        operationId = operationId,
        phase = phase,
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

    /** Pending attempt whose exact body text carries whitespace worth preserving. */
    fun whitespacePending(
        operationId: String = OP_A,
        key: String = KEY_A,
        refreshValue: String = "refresh-1",
    ): VaultPendingRefresh {
        val body = " { \"refreshToken\" : \"$refreshValue\" } "
        val attempt = ServiceRefreshAttempt.fromJson(key, body)
        return VaultPendingRefresh(operationId = operationId, attempt = attempt)
    }

    /** Exact body with newlines/tabs; mirrors the test whitespace fixture spelling. */
    fun multilinePending(
        operationId: String = OP_A,
        key: String = KEY_A,
        refreshValue: String = "refresh-1",
    ): VaultPendingRefresh {
        val body = "{\n  \"refreshToken\" : \"$refreshValue\" \n}"
        val attempt = ServiceRefreshAttempt.fromJson(key, body)
        return VaultPendingRefresh(operationId = operationId, attempt = attempt)
    }

    fun vaultWith(
        record: VaultSessionRecord?,
        revision: Long = 1L,
    ): FakeSessionVault = FakeSessionVault(
        VaultSnapshot(revision = revision, record = record),
    )

    fun auth(): FakeServiceAuthApi = FakeServiceAuthApi()

    /** Counting clock; asserts startup never classifies freshness. */
    class CountingClock(var now: Long = NOW) {
        var calls = 0
            private set

        fun read(): Long {
            calls += 1
            return now
        }
    }

    /** Counting UUID provider with scripted values; one pair per forced rotation. */
    class CountingUuids(values: List<String> = listOf(OP_C, KEY_C, OP_D, KEY_D, OP_E, KEY_E)) {
        private val iterator = values.iterator()
        var calls = 0
            private set

        fun next(): String {
            calls += 1
            return iterator.next()
        }
    }

    fun coordinator(
        origin: ServiceOrigin? = origin(),
        vault: SessionVault,
        authority: SessionRecoveryAuthority,
        authApi: FakeServiceAuthApi,
        clock: CountingClock = CountingClock(),
        uuids: CountingUuids = CountingUuids(),
    ): SessionRecoveryCoordinator = SessionRecoveryCoordinator(
        origin = origin,
        vault = vault,
        authority = authority,
        authApi = authApi,
        clockMillis = { clock.read() },
        uuidProvider = { uuids.next() },
    )

    fun assertRedacted(rendered: String, secrets: List<String>) {
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertTrue(
                "Display must be fixed/redacted but contained: $secret",
                !rendered.contains(secret),
            )
        }
    }

    suspend fun assertInvalidInput(block: suspend () -> Unit): SessionRecoveryException {
        val failure = try {
            block()
            fail("Expected SessionRecoveryException(INVALID_INPUT)")
            throw AssertionError("unreachable")
        } catch (e: SessionRecoveryException) {
            e
        }
        assertEquals(SessionRecoveryFailure.INVALID_INPUT, failure.failure)
        return failure
    }
}

/**
 * Scripted SessionRecoveryAuthority. Each scripted step is consumed once in
 * order; when the script is exhausted the last snapshot is repeated so the
 * multi-read startup protocol (read, reread, reread) can observe either a
 * stable store or a mid-startup change. Fault steps throw on consumption.
 */
internal class FakeRecoveryAuthority(
    initial: RecoveryRoomSnapshot = SessionRecoveryTestFixtures.signedOutSnapshot(),
) : SessionRecoveryAuthority {
    sealed interface Step {
        data class Value(val snapshot: RecoveryRoomSnapshot) : Step
        data class Fault(val error: Throwable) : Step
    }

    private val script = mutableListOf<Step>(Step.Value(initial))
    var reads = 0
        private set

    /** Gate invoked per read before serving the next step. */
    var readGate: (suspend (callIndex: Int) -> Unit)? = null

    fun answer(snapshot: RecoveryRoomSnapshot) {
        script += Step.Value(snapshot)
    }

    /** Replace the whole script (first read serves [first], then [rest]). */
    fun scripted(first: RecoveryRoomSnapshot, vararg rest: RecoveryRoomSnapshot) {
        script.clear()
        script += Step.Value(first)
        rest.forEach { script += Step.Value(it) }
    }

    fun failWith(error: Throwable) {
        script += Step.Fault(error)
    }

    /** Clear the script and fail every read, starting with the first. */
    fun failNow(error: Throwable) {
        script.clear()
        script += Step.Fault(error)
    }

    /** Serve [snapshot] for every read (stable store). */
    fun stable(snapshot: RecoveryRoomSnapshot) {
        script.clear()
        script += Step.Value(snapshot)
    }

    override suspend fun read(): RecoveryRoomSnapshot {
        reads += 1
        readGate?.invoke(reads)
        val step = script.getOrNull(reads - 1) ?: script.last()
        return when (step) {
            is Step.Value -> step.snapshot
            is Step.Fault -> throw step.error
        }
    }
}
