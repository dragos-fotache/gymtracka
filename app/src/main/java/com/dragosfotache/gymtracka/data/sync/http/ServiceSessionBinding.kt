package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid

/**
 * Immutable service/account/session credential selection for one bound sync call.
 * The origin already carries immutable canonical identity; the account is an exact
 * canonical lowercase UUID and the generation is a positive session counter. Zero
 * or signed-out bindings are unrepresentable: construction and copy both validate.
 * Equality covers origin, account and generation. Display is fixed and redacted;
 * the properties themselves are sensitive inputs, not log text.
 */
internal data class ServiceSessionBinding(
    val origin: ServiceOrigin,
    val accountId: String,
    val sessionGeneration: Long,
) {
    init {
        try {
            requireWorkoutUuid(accountId, "accountId")
        } catch (_: SyncModelException) {
            throw SyncModelException("Invalid accountId")
        }
        if (sessionGeneration < 1L) {
            throw SyncModelException("Invalid session generation")
        }
    }

    override fun toString(): String = "ServiceSessionBinding(redacted)"
}

/**
 * Bearer credential tagged with the exact binding it was selected for. The adapter
 * compares the tag against its expected binding before any I/O, so an accidentally
 * selected global or current-account token is detected instead of sent.
 */
internal class BoundServiceAccessToken(
    val binding: ServiceSessionBinding,
    val token: OpaqueToken,
) {
    override fun toString(): String = "BoundServiceAccessToken(redacted)"
}

/**
 * Bound provider of the opaque service bearer credential. Called exactly once per
 * explicit sync invocation with the immutable expected binding. Stores no
 * credentials and applies no expiry or rotation policy; the vault and refresh coordinator own that.
 */
internal fun interface BoundServiceAccessTokenProvider {
    suspend fun accessToken(binding: ServiceSessionBinding): BoundServiceAccessToken
}
