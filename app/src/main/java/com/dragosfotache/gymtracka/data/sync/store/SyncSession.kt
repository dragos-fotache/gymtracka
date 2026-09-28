package com.dragosfotache.gymtracka.data.sync.store

/**
 * Immutable repository session fence. The expected account ID is a service identity:
 * an opaque nonblank string that is not necessarily a client aggregate UUID.
 * Null with generation 0 is the signed-out default, equivalent to absent device state.
 * Fence checks never depend on Google SDK or network types.
 */
data class RepositorySession(
    val accountId: String?,
    val sessionGeneration: Long,
) {
    init {
        if (accountId != null && accountId.isBlank()) {
            throw IllegalArgumentException("Session accountId must be a nonblank service identity")
        }
        if (sessionGeneration < 0) {
            throw IllegalArgumentException("Session generation must be nonnegative")
        }
    }

    companion object {
        val SIGNED_OUT = RepositorySession(accountId = null, sessionGeneration = 0L)
    }
}

/**
 * Thrown when the device identity no longer matches the repository fence. The
 * transaction commits nothing; later lifecycle code constructs a new repository
 * with the new fence explicitly instead of silently following the new account.
 */
class StaleSessionException(
    val expectedAccountId: String?,
    val expectedGeneration: Long,
    val actualAccountId: String?,
    val actualGeneration: Long,
) : IllegalStateException(
    "Sync session changed during transaction: " +
        "expected account=$expectedAccountId generation=$expectedGeneration, " +
        "found account=$actualAccountId generation=$actualGeneration",
)
