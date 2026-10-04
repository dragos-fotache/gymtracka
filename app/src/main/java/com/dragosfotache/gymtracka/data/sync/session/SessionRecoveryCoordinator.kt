package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessToken
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/**
 * Process-local authorized handle for one startup recovery observation.
 * No externally usable constructor exists: only [SessionRecoveryCoordinator]
 * publishes usable handles. The handle delegates credential selection to the
 * owner's single refresh coordinator through its private shared lease and
 * fences every transport callback against the exact Room authority
 * observation. Old handles refuse before I/O; in-flight stale work surfaces
 * as refresh stale; caller cancellation dominates either category.
 */
internal class RecoveredSession internal constructor(
    val identity: RecoveryIdentity,
    private val owner: SessionRecoveryCoordinator,
    private val fence: Any,
    private val originalSnapshot: RecoveryRoomSnapshot,
    private val lease: SessionRefreshLease,
    private val coordinator: SessionRefreshCoordinator,
) {
    private val alive = AtomicBoolean(true)

    internal fun markStale() {
        alive.set(false)
    }

    internal fun isAlive(): Boolean = alive.get()

    internal fun fence(): Any = fence

    /**
     * Runs [request] with the recovered credential through the owner's
     * serialized refresh machinery. The transport callback is checked
     * against Room authority immediately before invocation and after
     * success or error; stale results and errors are never returned.
     * Safe network/vault failures propagate unchanged under a current
     * identity; only authority or fence failure locks visibility. An
     * injected cancellation is rethrown identically when the actual caller
     * is still active; actual caller cancellation always dominates.
     */
    suspend fun <T> withAccessToken(request: suspend (BoundServiceAccessToken) -> T): T {
        currentCoroutineContext().ensureActive()
        if (!alive.get() || !owner.isHandleCurrent(this, fence)) {
            throw SessionRecoveryException(SessionRecoveryFailure.STALE_SESSION)
        }
        return coordinator.withAccessToken(lease) { token ->
            owner.guardBeforeUse(this, fence, originalSnapshot)
            try {
                val result = request(token)
                owner.guardAfterSuccess(this, fence, originalSnapshot)
                result
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                owner.guardAfterError(this, fence, originalSnapshot)
                throw e
            }
        }
    }

    override fun toString(): String = "RecoveredSession(redacted)"
}

/**
 * Unused startup privacy/recovery coordination over the accepted vault and
 * refresh machinery. Startup performs reads only: no refresh, vault
 * repair/promotion/clear, Room mutation, or logout. The constructor performs
 * no I/O, network, UUID, or clock work; the initial state is PrivacyLocked.
 *
 * Every reconcile opens a fresh opaque process-local fence token,
 * immediately invalidates the previous handle/lease, publishes PrivacyLocked,
 * then follows strict precedence without examining the vault before authority
 * eligibility. Fences are reference tokens compared by identity: newest start
 * wins and older completion cannot publish or invalidate newer work. There is
 * no counter and no overflow. Invalidate is synchronous, idempotent, and
 * never waits for reads, network, or mutexes.
 */
internal class SessionRecoveryCoordinator(
    private val origin: ServiceOrigin?,
    private val vault: SessionVault,
    private val authority: SessionRecoveryAuthority,
    private val authApi: ServiceAuthApi,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
) {
    private val stateLock = Any()
    private var currentFence: Any = Any()
    private var currentState: SessionRecoveryState = SessionRecoveryState.PrivacyLocked
    private var currentLease: SessionRefreshLease? = null
    private var currentHandle: RecoveredSession? = null
    private val sharedMutex = Mutex()
    private val refreshCoordinator: SessionRefreshCoordinator? =
        if (origin != null) {
            SessionRefreshCoordinator(origin, vault, authApi, clockMillis, uuidProvider, sharedMutex)
        } else {
            null
        }

    /** Current visibility state; updated only under the short state lock. */
    val state: SessionRecoveryState
        get() = synchronized(stateLock) { currentState }

    /**
     * Synchronously fences all work: opens a fresh fence token, permanently
     * invalidates the current handle/lease, clears them, and publishes
     * PrivacyLocked. Never waits for reads, network, or mutexes.
     */
    fun invalidate() {
        synchronized(stateLock) {
            currentFence = Any()
            currentLease?.invalidate()
            currentHandle?.markStale()
            currentLease = null
            currentHandle = null
            currentState = SessionRecoveryState.PrivacyLocked
        }
    }

    /**
     * Reconciles Room authority with the encrypted vault and publishes the
     * startup visibility state. Reads only; never writes the vault, repairs
     * keys, refreshes credentials, or touches clocks/UUIDs. Observed
     * cancellation of the current operation invalidates its own partially
     * published handle/lease and leaves PrivacyLocked, but never resets a
     * newer fence or state.
     */
    suspend fun reconcile(): SessionRecoveryState {
        val myFence: Any = Any()
        synchronized(stateLock) {
            currentFence = myFence
            currentLease?.invalidate()
            currentHandle?.markStale()
            currentLease = null
            currentHandle = null
            currentState = SessionRecoveryState.PrivacyLocked
        }
        try {
            val result = reconcileLocked(myFence)
            val caller = currentCoroutineContext()
            synchronized(stateLock) {
                caller.ensureActive()
                if (myFence !== currentFence) {
                    throw SessionRecoveryException(SessionRecoveryFailure.STALE_SESSION)
                }
            }
            return result
        } catch (e: CancellationException) {
            val toThrow: CancellationException = try {
                currentCoroutineContext().ensureActive()
                e
            } catch (caller: CancellationException) {
                caller
            }
            synchronized(stateLock) {
                if (myFence === currentFence) {
                    currentLease?.invalidate()
                    currentHandle?.markStale()
                    currentLease = null
                    currentHandle = null
                    currentState = SessionRecoveryState.PrivacyLocked
                }
            }
            throw toThrow
        }
    }

    override fun toString(): String = "SessionRecoveryCoordinator(redacted)"

    internal fun isHandleCurrent(handle: RecoveredSession, fence: Any): Boolean =
        synchronized(stateLock) { fence === currentFence && currentHandle === handle }

    internal fun isFenceCurrent(fence: Any): Boolean =
        synchronized(stateLock) { fence === currentFence }

    /**
     * Lease authorization predicate: checks the captured fence, rereads
     * authority, requires exact original snapshot equality, and rechecks
     * caller and fence on every exit. Mismatch permanently kills the handle
     * and locks visibility only if still current; authority errors lock as
     * unavailable. Never adopts new authorization. Injected cancellation is
     * rethrown identically when the actual caller is still active.
     */
    internal suspend fun checkAuthorityForLease(fence: Any, original: RecoveryRoomSnapshot) {
        currentCoroutineContext().ensureActive()
        ensureFenceCurrentForPredicate(fence)
        val observed: RecoveryRoomSnapshot
        try {
            observed = authority.read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrentForPredicate(fence)
            lockForHandle(fence, SessionRecoveryReason.AUTHORITY_UNAVAILABLE)
            throw SessionRefreshException(SessionRefreshFailure.UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()
        ensureFenceCurrentForPredicate(fence)
        if (observed != original) {
            lockForHandle(fence, SessionRecoveryReason.IDENTITY_MISMATCH)
            currentCoroutineContext().ensureActive()
            ensureFenceCurrentForPredicate(fence)
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        currentCoroutineContext().ensureActive()
        ensureFenceCurrentForPredicate(fence)
    }

    /** Guard immediately before invoking a transport callback. */
    internal suspend fun guardBeforeUse(
        handle: RecoveredSession,
        fence: Any,
        original: RecoveryRoomSnapshot,
    ) {
        verifyGuardAuthority(handle, fence, original)
    }

    /** Guard after a successful transport callback; never returns stale data. */
    internal suspend fun guardAfterSuccess(
        handle: RecoveredSession,
        fence: Any,
        original: RecoveryRoomSnapshot,
    ) {
        verifyGuardAuthority(handle, fence, original)
    }

    /**
     * Guard after a transport callback error; replaces the original error
     * with stale/unavailable only when fence or authority actually failed,
     * otherwise returns normally so the caller rethrows the original.
     */
    internal suspend fun guardAfterError(
        handle: RecoveredSession,
        fence: Any,
        original: RecoveryRoomSnapshot,
    ) {
        verifyGuardAuthority(handle, fence, original)
    }

    private suspend fun verifyGuardAuthority(
        handle: RecoveredSession,
        fence: Any,
        original: RecoveryRoomSnapshot,
    ) {
        requireHandleCurrent(handle, fence)
        val observed = readAuthorityForGuard(handle, fence)
        requireHandleCurrent(handle, fence)
        if (observed != original) {
            failGuard(handle, fence, SessionRecoveryReason.IDENTITY_MISMATCH)
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        requireHandleCurrent(handle, fence)
    }

    private suspend fun readAuthorityForGuard(
        handle: RecoveredSession,
        fence: Any,
    ): RecoveryRoomSnapshot {
        try {
            return authority.read()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!handleCurrentSync(handle, fence)) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            failGuard(handle, fence, SessionRecoveryReason.AUTHORITY_UNAVAILABLE)
            throw SessionRefreshException(SessionRefreshFailure.UNAVAILABLE)
        }
    }

    private suspend fun requireHandleCurrent(handle: RecoveredSession, fence: Any) {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (!(fence === currentFence && currentHandle === handle && handle.isAlive())) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
        }
    }

    private fun handleCurrentSync(handle: RecoveredSession, fence: Any): Boolean =
        synchronized(stateLock) { fence === currentFence && currentHandle === handle && handle.isAlive() }

    private suspend fun ensureFenceCurrent(fence: Any) {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                throw SessionRecoveryException(SessionRecoveryFailure.STALE_SESSION)
            }
        }
    }

    private suspend fun ensureFenceCurrentForPredicate(fence: Any) {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
        }
    }

    private fun failGuard(handle: RecoveredSession, fence: Any, reason: SessionRecoveryReason) {
        synchronized(stateLock) {
            if (fence === currentFence && currentHandle === handle) {
                currentLease?.invalidate()
                handle.markStale()
                currentLease = null
                currentHandle = null
                currentState = SessionRecoveryState.Locked(reason)
            } else {
                handle.markStale()
            }
        }
    }

    private fun lockForHandle(fence: Any, reason: SessionRecoveryReason) {
        synchronized(stateLock) {
            if (fence === currentFence) {
                currentLease?.invalidate()
                currentHandle?.markStale()
                currentLease = null
                currentHandle = null
                currentState = SessionRecoveryState.Locked(reason)
            }
        }
    }

    private suspend fun reconcileLocked(myFence: Any): SessionRecoveryState {
        val configuredOrigin = origin
        if (configuredOrigin == null) {
            return publishAndReturn(myFence, SessionRecoveryState.LocalOnly(SessionRecoveryReason.CONFIGURATION_UNAVAILABLE))
        }

        val snapshot = readAuthorityOrLock(myFence) ?: return currentPublishedOrStale(myFence)

        if (snapshot.phase == RecoveryRoomPhase.SIGNED_OUT) {
            return publishAndReturn(myFence, SessionRecoveryState.LocalOnly(SessionRecoveryReason.SIGNED_OUT))
        }
        if (snapshot.phase == RecoveryRoomPhase.PREPARING ||
            snapshot.phase == RecoveryRoomPhase.TRANSITIONING
        ) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.TRANSITION_REQUIRED))
        }
        val roomIdentity = snapshot.identity
        if (roomIdentity == null) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.IDENTITY_MISMATCH))
        }
        if (roomIdentity.binding.origin != configuredOrigin) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.IDENTITY_MISMATCH))
        }
        if (!snapshot.baselineComplete) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.BASELINE_INCOMPLETE))
        }

        val vaultSnapshot = loadVaultOrLock(myFence) ?: return currentPublishedOrStale(myFence)
        val record = vaultSnapshot.record
        if (record == null) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.VAULT_MISSING))
        }
        if (record.binding != roomIdentity.binding || record.operationId != roomIdentity.operationId) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.IDENTITY_MISMATCH))
        }
        if (record.phase != VaultSessionPhase.ACTIVE) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.TRANSITION_REQUIRED))
        }

        val secondAuthority = readAuthorityOrLock(myFence) ?: return currentPublishedOrStale(myFence)
        if (secondAuthority != snapshot) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.IDENTITY_MISMATCH))
        }
        val secondVault = loadVaultOrLock(myFence) ?: return currentPublishedOrStale(myFence)
        if (!vaultSnapshotsEqual(vaultSnapshot, secondVault)) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.IDENTITY_MISMATCH))
        }
        val thirdAuthority = readAuthorityOrLock(myFence) ?: return currentPublishedOrStale(myFence)
        if (thirdAuthority != snapshot) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.IDENTITY_MISMATCH))
        }

        currentCoroutineContext().ensureActive()
        ensureFenceCurrent(myFence)
        val refresh = refreshCoordinator
        if (refresh == null) {
            return publishAndReturn(myFence, SessionRecoveryState.Locked(SessionRecoveryReason.AUTHORITY_UNAVAILABLE))
        }
        val lease = SessionRefreshLease(
            record.binding,
            record.operationId,
            VaultSessionPhase.ACTIVE,
            authorizationCheck = { checkAuthorityForLease(myFence, snapshot) },
        )
        val handle = RecoveredSession(roomIdentity, this, myFence, snapshot, lease, refresh)
        return publishActive(myFence, handle, lease)
    }

    private suspend fun readAuthorityOrLock(fence: Any): RecoveryRoomSnapshot? {
        try {
            val snapshot = authority.read()
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            publishAndReturn(fence, SessionRecoveryState.Locked(SessionRecoveryReason.AUTHORITY_UNAVAILABLE))
            return null
        }
    }

    private suspend fun loadVaultOrLock(fence: Any): VaultSnapshot? {
        try {
            val snapshot = vault.load()
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureFenceCurrent(fence)
            publishAndReturn(fence, SessionRecoveryState.Locked(SessionRecoveryReason.VAULT_UNAVAILABLE))
            return null
        }
    }

    private suspend fun publishAndReturn(
        fence: Any,
        newState: SessionRecoveryState,
    ): SessionRecoveryState {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                throw SessionRecoveryException(SessionRecoveryFailure.STALE_SESSION)
            }
            currentState = newState
        }
        return newState
    }

    private suspend fun publishActive(
        fence: Any,
        handle: RecoveredSession,
        lease: SessionRefreshLease,
    ): SessionRecoveryState {
        val caller = currentCoroutineContext()
        val active = SessionRecoveryState.Active(handle)
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                lease.invalidate()
                handle.markStale()
                throw SessionRecoveryException(SessionRecoveryFailure.STALE_SESSION)
            }
            currentLease = lease
            currentHandle = handle
            currentState = active
        }
        return active
    }

    private suspend fun currentPublishedOrStale(fence: Any): SessionRecoveryState {
        val caller = currentCoroutineContext()
        synchronized(stateLock) {
            caller.ensureActive()
            if (fence !== currentFence) {
                throw SessionRecoveryException(SessionRecoveryFailure.STALE_SESSION)
            }
            return currentState
        }
    }

    /** Vault reload equality without relying on attempt referential equality. */
    private fun vaultSnapshotsEqual(first: VaultSnapshot, second: VaultSnapshot): Boolean {
        if (first.revision != second.revision) {
            return false
        }
        val firstRecord = first.record
        val secondRecord = second.record
        if (firstRecord == null || secondRecord == null) {
            return firstRecord == null && secondRecord == null
        }
        if (firstRecord.binding != secondRecord.binding ||
            firstRecord.operationId != secondRecord.operationId ||
            firstRecord.phase != secondRecord.phase ||
            firstRecord.session != secondRecord.session
        ) {
            return false
        }
        val firstPending = firstRecord.pendingRefresh
        val secondPending = secondRecord.pendingRefresh
        if (firstPending == null || secondPending == null) {
            return firstPending == null && secondPending == null
        }
        return firstPending.operationId == secondPending.operationId &&
            firstPending.attempt.idempotencyKey == secondPending.attempt.idempotencyKey &&
            firstPending.attempt.requestJson == secondPending.attempt.requestJson &&
            firstPending.attempt.refreshToken == secondPending.attempt.refreshToken
    }
}
