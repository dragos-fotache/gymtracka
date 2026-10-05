package com.dragosfotache.gymtracka.data.sync.store

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.codec.requiredInt
import com.dragosfotache.gymtracka.data.sync.model.DURABLE_PAYLOAD_VERSION
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

/**
 * Inert Room-backed push-claim boundary. Serializes one account/session run,
 * selects the explicit sequence head, freezes it durably before returning its
 * exact request, and fences replay/late callback admission. A future caller can
 * perform HTTP outside Room while holding this run's ownership.
 *
 * This slice performs no HTTP, outcome handling, acknowledgment/removal,
 * rejection/error persistence, backoff calculation or retry loops. Returning a
 * claim never means delivered or converged. Production constructs neither this
 * store nor an engine.
 */
class PushRunOwner {
    internal val mutex = Mutex()
}

/** Private coroutine-context marker binding capability use to the admitted run. */
private class PushRunScope(val run: PushRun) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PushRunScope>

    override val key: CoroutineContext.Key<*> = Key
}

class PushStore(
    private val database: GymDatabase,
    private val session: RepositorySession,
    private val owner: PushRunOwner,
) {
    init {
        if (session.accountId == null) {
            throw IllegalArgumentException("Push store requires a signed-in account")
        }
        if (session.sessionGeneration <= 0) {
            throw IllegalArgumentException("Push store requires a positive session generation")
        }
    }

    /**
     * Serializes the entire block on the shared owner, including caller-owned HTTP
     * outside Room. Nested entry is rejected before waiting on the mutex; entry
     * from an active Room transaction is rejected; cancellation while waiting
     * performs no Room work. Session/epoch fences are checked before invoking the
     * block. The capability closes and ownership releases in finally on every path,
     * after structured children finish.
     */
    suspend fun <T> withRun(
        expectedCacheEpoch: Long,
        block: suspend (PushRun) -> T,
    ): T {
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        if (database.inTransaction()) {
            throw IllegalStateException("Push run requires no active transaction")
        }
        if (currentCoroutineContext()[PushRunScope.Key] != null) {
            throw IllegalStateException("Nested push run is not allowed")
        }
        currentCoroutineContext().ensureActive()
        val accountId = session.accountId ?: throw IllegalArgumentException(
            "Push store requires a signed-in account",
        )
        return owner.mutex.withLock {
            currentCoroutineContext().ensureActive()
            val run = PushRun(database, accountId, session.sessionGeneration, expectedCacheEpoch)
            try {
                withContext(PushRunScope(run)) {
                    currentCoroutineContext().ensureActive()
                    try {
                        database.withTransaction {
                            currentCoroutineContext().ensureActive()
                            checkCanonicalFences(
                                database,
                                accountId,
                                session.sessionGeneration,
                                expectedCacheEpoch,
                            )
                            currentCoroutineContext().ensureActive()
                        }
                    } catch (_: SQLiteException) {
                        currentCoroutineContext().ensureActive()
                        throw PushStorageException()
                    }
                    currentCoroutineContext().ensureActive()
                    val result = block(run)
                    currentCoroutineContext().ensureActive()
                    result
                }
            } finally {
                run.close()
            }
        }
    }
}

/**
 * Single admitted run capability. Instances are created only by
 * [PushStore.withRun]; use outside the admitted run, after closure, from another
 * run, or from an enclosing Room transaction is rejected. No public constructor.
 */
class PushRun internal constructor(
    private val database: GymDatabase,
    private val accountId: String,
    private val sessionGeneration: Long,
    private val expectedCacheEpoch: Long,
) {
    @Volatile
    private var open = true

    internal fun close() {
        open = false
    }

    private suspend fun requireAdmitted() {
        if (!open || currentCoroutineContext()[PushRunScope.Key]?.run !== this) {
            throw IllegalStateException("Push run is not admitted")
        }
    }

    /**
     * Performs one fenced Room transaction over the selected account's explicit
     * head: validates scheduling/state bookkeeping, reads the full durable wrapper
     * with the existing validated row reader, honors exhaustion/retry timing, then
     * atomically freezes and increments the admission count with a row-count-checked
     * update. All other columns stay unchanged. Blocked/deferred/empty results
     * change nothing. The claimed frozen row and exact durable request return only
     * after commit; freeze means possibly sent forever.
     */
    suspend fun claimHead(nowMillis: Long): PushHeadResult {
        if (nowMillis < 0) {
            throw IllegalArgumentException("Push claim time must be nonnegative")
        }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Push claim requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        try {
            val result = database.withTransaction {
                currentCoroutineContext().ensureActive()
                claimHeadInside(nowMillis)
            }
            currentCoroutineContext().ensureActive()
            return result
        } catch (_: SQLiteException) {
            currentCoroutineContext().ensureActive()
            throw PushStorageException()
        }
    }

    private suspend fun claimHeadInside(nowMillis: Long): PushHeadResult {
        if (!database.inTransaction()) {
            throw IllegalStateException("Push claim requires an active transaction")
        }
        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
        currentCoroutineContext().ensureActive()
        val dao = database.syncDao()
        val head = dao.getOutboxHead(accountId) ?: run {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Empty
        }
        currentCoroutineContext().ensureActive()
        if (head.sequence <= 0L ||
            head.attemptCount < 0 ||
            (head.retryAfterMillis != null && head.retryAfterMillis < 0L)
        ) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
        }
        when (head.state) {
            "pending", "frozen" -> Unit
            "rejected" -> {
                currentCoroutineContext().ensureActive()
                return PushHeadResult.Blocked(PushBlockReason.REJECTED, head.errorCode)
            }
            else -> {
                currentCoroutineContext().ensureActive()
                return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
            }
        }
        if (head.state == "pending" && head.attemptCount != 0) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
        }
        if (head.errorCode != null || head.errorMessage != null) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.RETAINED_ERROR, head.errorCode)
        }
        if (head.payloadVersion != DURABLE_PAYLOAD_VERSION) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.UNSUPPORTED_VERSION)
        }
        val wrapper = try {
            parseStrictJsonObject(head.payloadJson)
        } catch (_: SyncModelException) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
        }
        val wrapperVersion = try {
            wrapper.requiredInt("payloadVersion")
        } catch (_: SyncModelException) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
        }
        if (wrapperVersion != DURABLE_PAYLOAD_VERSION) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.UNSUPPORTED_VERSION)
        }
        try {
            checkPushWrapperShape(wrapper)
        } catch (_: SyncModelException) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
        }
        try {
            readDurableOutboxRow(head)
        } catch (_: UnsupportedDurableVersionException) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.UNSUPPORTED_VERSION)
        } catch (_: SyncModelException) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
        }
        if (head.attemptCount == Int.MAX_VALUE) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.ATTEMPTS_EXHAUSTED)
        }
        val retryAt = head.retryAfterMillis
        if (retryAt != null && retryAt > nowMillis) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Deferred(retryAt)
        }
        currentCoroutineContext().ensureActive()
        val newAttemptCount = head.attemptCount + 1
        val updated = dao.freezeClaimedHead(
            head.mutationId,
            accountId,
            head.state,
            head.attemptCount,
            newAttemptCount,
        )
        if (updated != 1) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val frozen = head.copy(state = "frozen", attemptCount = newAttemptCount)
        if (dao.getOutboxHead(accountId) != frozen) {
            throw IllegalStateException("Push head changed during transaction")
        }
        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
        requireAdmitted()
        currentCoroutineContext().ensureActive()
        val claim = PushClaim(
            run = this,
            accountId = accountId,
            sessionGeneration = sessionGeneration,
            expectedCacheEpoch = expectedCacheEpoch,
            row = frozen,
        )
        return PushHeadResult.Claimed(claim)
    }

    /**
     * Read-only fenced validation that the current explicit head still matches
     * every field of the claim's frozen row. Missing, replaced, nonhead, tampered
     * or reclaimed rows throw [StalePushClaimException]; a late validation after
     * sign-out, switch or publication fails via the unchanged fence errors without
     * changing any storage. Success is only a check at that transaction boundary.
     */
    suspend fun checkClaim(claim: PushClaim) {
        if (claim.run !== this) {
            throw IllegalStateException("Push claim belongs to another run")
        }
        if (claim.accountId != accountId ||
            claim.sessionGeneration != sessionGeneration ||
            claim.expectedCacheEpoch != expectedCacheEpoch
        ) {
            throw StalePushClaimException()
        }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Push claim requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        try {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                val head = database.syncDao().getOutboxHead(accountId)
                currentCoroutineContext().ensureActive()
                requireAdmitted()
                if (head != claim.row) {
                    throw StalePushClaimException()
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (_: SQLiteException) {
            currentCoroutineContext().ensureActive()
            throw PushStorageException()
        }
    }
}

/**
 * Claim-only shape validation for the represented v1 durable wrapper. Fails closed
 * on outer wrapper fields outside `{payloadVersion, requestJson, affectedRoots}` and
 * on affected-root members outside `{aggregateType, aggregateId, localGeneration}`.
 * Runs only after column and wrapper version dispatch, so unknown versions never
 * reach content inspection. Reads keys without normalizing or rewriting anything;
 * absent or mistyped members are left for the full validated row reader to classify.
 */
private fun checkPushWrapperShape(wrapper: JSONObject) {
    for (key in wrapper.keys()) {
        if (key != "payloadVersion" && key != "requestJson" && key != "affectedRoots") {
            throw SyncModelException("Push wrapper has unknown fields")
        }
    }
    val roots = wrapper.optJSONArray("affectedRoots") ?: return
    for (index in 0 until roots.length()) {
        val root = roots.optJSONObject(index)
            ?: throw SyncModelException("Push wrapper has invalid affected roots")
        for (key in root.keys()) {
            if (key != "aggregateType" && key != "aggregateId" && key != "localGeneration") {
                throw SyncModelException("Push wrapper has unknown affected-root fields")
            }
        }
    }
}
