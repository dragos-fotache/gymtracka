package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit internal authorization seam for one authorized activation: the
 * bound origin/account/generation plus the durable activation operation and
 * journal phase that a refresh selection must match exactly. One shared lease
 * per authorized activation is used by future foreground/background callers;
 * a lease alone never proves vault phase authorizes network or UI.
 *
 * States are process-local only: usable, foreground-reauth-required, and
 * permanently invalidated. Invalidation dominates every other state, is
 * idempotent and synchronous, and never waits for network, vault, or mutex.
 * It performs no durable cleanup; later R09 owns tombstoning. Display is
 * fixed and redacted.
 */
internal class SessionRefreshLease(
    val binding: ServiceSessionBinding,
    val operationId: String,
    val phase: VaultSessionPhase,
) {
    private val state = AtomicInteger(USABLE)

    init {
        try {
            requireWorkoutUuid(operationId, "id")
        } catch (_: Exception) {
            throw SessionRefreshException(SessionRefreshFailure.INVALID_INPUT)
        }
    }

    /**
     * Immediately stops future admission under this lease; waiting and late
     * results become stale (caller cancellation still dominates). Never
     * recalls already selected or transmitted bytes.
     */
    fun invalidate() {
        state.set(INVALIDATED)
    }

    /** True only while neither reauth-latched nor invalidated. */
    internal fun isUsable(): Boolean = state.get() == USABLE

    /**
     * Linearizable synchronous admission observation from ONE atomic
     * snapshot: usable admits, reauth-latched needs foreground
     * reauthentication, invalidated stops as stale. Separate reads could
     * misclassify an invalidation landing between them; this cannot.
     * Admission still cannot recall an already-admitted caller.
     */
    internal fun checkAdmission() {
        when (state.get()) {
            USABLE -> return
            REAUTHENTICATION_REQUIRED -> throw SyncApiException.ReauthenticationRequired()
            else -> throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
    }

    /** True once invalidated; permanent and dominant. */
    internal fun isInvalidated(): Boolean = state.get() == INVALIDATED

    /**
     * Records foreground reauthentication without clearing vault, account, or
     * cache. Applies only from usable; never revives an invalidated lease.
     */
    internal fun latchReauthentication() {
        state.compareAndSet(USABLE, REAUTHENTICATION_REQUIRED)
    }

    override fun toString(): String = "SessionRefreshLease(redacted)"

    private companion object {
        private const val USABLE = 0
        private const val REAUTHENTICATION_REQUIRED = 1
        private const val INVALIDATED = 2
    }
}
