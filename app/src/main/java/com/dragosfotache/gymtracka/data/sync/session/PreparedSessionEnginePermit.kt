package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin

/**
 * Exact private Room permit for one freshly admitted prepared session.
 * Binds the SAME physical database instance, service origin, cached
 * account, session generation, activation operation and durable journal
 * revision that admitted it.
 *
 * Instances are inert until registered: [isCurrent] calls back into the
 * concrete owning [PreparedSessionEngineCoordinator], which requires
 * referential identity against its privately registered current permit
 * under the privately stored issued fence. Only the coordinator's
 * private registration after fresh admission can make it true, and
 * every fence rotation, invalidation and invocation settlement clears
 * it. There is no injectable validity seam, no registering factory
 * taking raw fields, no optional/default construction and no
 * coroutine-global permissive context: ordinary ACTIVE writers always
 * pass a null permit and keep their existing device checks. Display is
 * fixed and redacted.
 */
internal class PreparedSessionEnginePermit internal constructor(
    internal val database: GymDatabase,
    internal val origin: ServiceOrigin,
    internal val accountId: String,
    internal val sessionGeneration: Long,
    internal val operationId: String,
    internal val journalRevision: Long,
    private val owner: PreparedSessionEngineCoordinator,
) {
    /**
     * True only while the owning coordinator still registers this exact
     * instance under its stored issued fence. Retired invocations fail
     * closed without waiting for owner, Room or HTTP work.
     */
    internal fun isCurrent(): Boolean =
        try {
            owner.isPermitCurrent(this)
        } catch (_: Exception) {
            false
        }

    override fun toString(): String = "PreparedSessionEnginePermit(redacted)"
}
