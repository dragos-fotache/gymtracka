package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.gym.GymRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * app-facing account handle. Captures the exact admitted Room
 * identity/revision plus one opaque facade fence. Its repository never follows
 * another scope: every scoped transaction rechecks the facade process fence
 * before effects and before return/commit, so a dead handle cannot read or
 * mutate even when durable generation is unchanged.
 *
 * No externally usable constructor exists: only [AppSessionCoordinator]
 * publishes usable handles. Displays are fixed/redacted and expose no grants,
 * credentials, request bytes or causes. Private observations are advisory.
 */
internal class AppAccountSession internal constructor(
    val identity: RecoveryIdentity,
    val room: RecoveryRoomSnapshot,
    val repository: GymRepository,
    private val fence: Any,
    private val alive: AtomicBoolean,
    private val checkAuthority: suspend () -> Unit,
    private val isFacadeCurrent: () -> Boolean,
    private val onAuthorityFault: () -> Unit,
) {
    internal fun markStale() {
        alive.set(false)
    }

    internal fun isAlive(): Boolean = alive.get()

    internal fun aliveToken(): AtomicBoolean = alive

    /**
     * Narrow read-only currentness check. Guards facade currentness before and
     * after the recovered read-only authority check ([RecoveredSession.checkCurrent],
     * which runs the latch-independent lease authorization). REAUTHENTICATION_REQUIRED
     * keeps offline eligibility; permanent invalidation or Room drift closes the
     * handle. An authority fault while still facade-current fires the
     * same-handle revocation callback once, so later repository reads refuse
     * even at unchanged durable generation. Caller cancellation dominates
     * stale mapping and is rethrown identically when the actual caller is
     * still active. Fixed/redacted failures only; no causes escape.
     */
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!alive.get() || !isFacadeCurrent()) {
            throw AppSessionStaleException()
        }
        try {
            checkAuthority()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (isFacadeCurrent()) {
                onAuthorityFault()
            }
            throw AppSessionStaleException()
        }
        currentCoroutineContext().ensureActive()
        if (!alive.get() || !isFacadeCurrent()) {
            throw AppSessionStaleException()
        }
    }

    override fun toString(): String = "AppAccountSession(redacted)"
}
